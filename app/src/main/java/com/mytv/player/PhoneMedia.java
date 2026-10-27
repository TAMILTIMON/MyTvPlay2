package com.mytv.player;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Every video / song on this phone (internal storage, SD card, WhatsApp, downloads…) via Android's media library. */
public final class PhoneMedia {
    private PhoneMedia() {
    }

    public static final class M {
        public long id, dur, size, added, album;
        public Uri uri;
        public String title, sub, folder;
        public String artist = "", albumName = "", genre = "", bucket = "";
        /** set for an album / artist / genre / folder / playlist card (not a song) */
        public String groupKey;
        public int count;

        public Store.Item item() {
            return new Store.Item(title, uri.toString(), "");
        }
    }

    public static List<M> query(Context c, boolean audio) {
        List<M> out = new ArrayList<>();
        Uri base = audio ? MediaStore.Audio.Media.EXTERNAL_CONTENT_URI : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String[] cols = audio
                ? (Build.VERSION.SDK_INT >= 30
                ? new String[]{MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Audio.Media.GENRE}
                : Build.VERSION.SDK_INT >= 29
                ? new String[]{MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.BUCKET_DISPLAY_NAME}
                : new String[]{MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.ALBUM})
                : new String[]{MediaStore.Video.Media._ID, MediaStore.Video.Media.TITLE, MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Video.Media.DURATION, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.DISPLAY_NAME};
        String where = audio ? MediaStore.Audio.Media.DURATION + " >= 20000" : null;   // skip ringtones / notification sounds
        try (Cursor cur = c.getContentResolver().query(base, cols, where, null, MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
            if (cur == null) return out;
            while (cur.moveToNext()) {
                M m = new M();
                m.id = cur.getLong(0);
                m.uri = ContentUris.withAppendedId(base, m.id);
                m.title = cur.getString(1);
                m.dur = cur.getLong(3);
                m.size = cur.getLong(4);
                m.added = cur.getLong(5) * 1000;
                if (audio) {
                    String artist = cur.getString(2);
                    m.artist = artist == null || artist.startsWith("<") ? "" : artist;
                    m.albumName = cur.getString(7) == null || cur.getString(7).startsWith("<") ? "" : cur.getString(7);
                    m.sub = !m.artist.isEmpty() ? m.artist : m.albumName;
                    m.album = cur.getLong(6);
                    if (cur.getColumnCount() > 8 && cur.getString(8) != null) m.bucket = cur.getString(8);
                    if (cur.getColumnCount() > 9 && cur.getString(9) != null) m.genre = cur.getString(9);
                    MusicLib.applyEdits(m);
                    m.folder = m.sub;
                } else {
                    m.folder = cur.getString(2) == null ? "" : cur.getString(2);
                    m.sub = m.folder;
                    if (m.title == null || m.title.isEmpty()) m.title = cur.getString(6);
                }
                if (m.title == null) m.title = "";
                out.add(m);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    // ------------------------------------------------------------------ thumbnails / album art
    private static final LruCache<String, Bitmap> ART = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount();
        }
    };
    private static final Map<String, Boolean> NONE = new HashMap<>();

    /** A card standing for a group of songs (cover = its first song). */
    public static M group(String key, String title, List<M> songs) {
        M g = new M();
        g.groupKey = key;
        g.title = title;
        g.count = songs.size();
        g.sub = songs.size() + " " + T.t("songs");
        g.folder = g.sub;
        g.uri = songs.isEmpty() ? Uri.parse("zh://empty/" + key) : songs.get(0).uri;
        g.album = songs.isEmpty() ? 0 : songs.get(0).album;
        for (M m : songs) g.dur += m.dur;
        g.id = -Math.abs(key.hashCode());
        return g;
    }

    public static Bitmap cached(M m) {
        return ART.get(m.uri.toString());
    }

    /** Video frame or album cover (background thread). */
    @SuppressWarnings("deprecation")
    public static Bitmap art(Context c, M m, boolean audio) {
        String k = m.uri.toString();
        if (k.startsWith("http://") || k.startsWith("https://")) return null;   // online song: no cover lookup
        Bitmap b = ART.get(k);
        if (b != null || NONE.containsKey(k)) return b;
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                b = c.getContentResolver().loadThumbnail(m.uri, new Size(audio ? 256 : 384, audio ? 256 : 216), null);
            } else if (audio) {
                Uri art = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), m.album);
                try (InputStream in = c.getContentResolver().openInputStream(art)) {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inSampleSize = 2;
                    b = BitmapFactory.decodeStream(in, null, o);
                }
            } else {
                b = MediaStore.Video.Thumbnails.getThumbnail(c.getContentResolver(), m.id, MediaStore.Video.Thumbnails.MINI_KIND, null);
            }
        } catch (Throwable ignored) {
        }
        if (b == null && audio) b = embedded(c, m);       // the cover stored inside the song file
        if (b != null) ART.put(k, b);
        else NONE.put(k, true);
        return b;
    }

    /** Cover picture embedded in an MP3 / M4A / FLAC file. */
    static Bitmap embedded(Context c, M m) {
        android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
        try {
            r.setDataSource(c, m.uri);
            byte[] pic = r.getEmbeddedPicture();
            if (pic == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(pic, 0, pic.length, o);
            int s = 1;
            while (o.outWidth / (s * 2) >= 480) s *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = s;
            return BitmapFactory.decodeByteArray(pic, 0, pic.length, o);
        } catch (Throwable e) {
            return null;
        } finally {
            try {
                r.release();
            } catch (Throwable ignored) {
            }
        }
    }

    /** Colourful card with the first letter, for songs / videos without a picture. */
    public static android.graphics.drawable.Drawable placeholder(Context c, M m, boolean audio) {
        final int[][] cols = {{0xFF8B5CF6, 0xFFEC4899}, {0xFF0EA5E9, 0xFF6366F1}, {0xFF10B981, 0xFF0EA5E9}, {0xFFF97316, 0xFFEF4444},
                {0xFFEAB308, 0xFFF97316}, {0xFF14B8A6, 0xFF8B5CF6}, {0xFFD946EF, 0xFF6366F1}, {0xFF22C55E, 0xFFEAB308}};
        final int[] g = cols[Math.abs(m.title.hashCode()) % cols.length];
        final String letter = m.title.trim().isEmpty() ? "♪" : m.title.trim().substring(0, 1).toUpperCase(java.util.Locale.ROOT);
        final UiKit.Icon note = new UiKit.Icon(c, audio ? "list-music" : "film", 18, 0xCCFFFFFF);
        return new android.graphics.drawable.Drawable() {
            final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

            public void draw(android.graphics.Canvas cv) {
                android.graphics.Rect b = getBounds();
                p.setShader(new android.graphics.LinearGradient(b.left, b.top, b.right, b.bottom, g[0], g[1],
                        android.graphics.Shader.TileMode.CLAMP));
                cv.drawRect(b, p);
                p.setShader(null);
                p.setColor(0xF2FFFFFF);
                p.setTextAlign(android.graphics.Paint.Align.CENTER);
                p.setFakeBoldText(true);
                p.setTextSize(Math.min(b.width(), b.height()) * 0.42f);
                android.graphics.Paint.FontMetrics fm = p.getFontMetrics();
                cv.drawText(letter, b.exactCenterX(), b.exactCenterY() - (fm.ascent + fm.descent) / 2, p);
                int is = Math.max(16, Math.min(b.width(), b.height()) / 6);
                note.setBounds(b.right - is - is / 2, b.bottom - is - is / 2, b.right - is / 2, b.bottom - is / 2);
                note.draw(cv);
            }

            public void setAlpha(int a) {
                p.setAlpha(a);
            }

            public void setColorFilter(android.graphics.ColorFilter f) {
                p.setColorFilter(f);
            }

            public int getOpacity() {
                return android.graphics.PixelFormat.OPAQUE;
            }
        };
    }

    /** Everything known about a song / video (for the details sheet). Background thread. */
    public static java.util.LinkedHashMap<String, String> details(Context c, M m, boolean audio) {
        java.util.LinkedHashMap<String, String> d = new java.util.LinkedHashMap<>();
        android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
        try {
            r.setDataSource(c, m.uri);
            if (audio) {
                put(d, T.t("Artist"), r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST));
                put(d, T.t("Album"), r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM));
                put(d, T.t("Composer"), r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_COMPOSER));
                put(d, T.t("Year"), r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_YEAR));
                put(d, T.t("Genre"), r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_GENRE));
                put(d, T.t("Track"), r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER));
            } else {
                String w = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                String h = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                if (w != null && h != null) put(d, T.t("Resolution"), w + " × " + h + "  (" + quality(Integer.parseInt(h), Integer.parseInt(w)) + ")");
                if (Build.VERSION.SDK_INT >= 23) {
                    String fps = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE);
                    if (fps != null) put(d, T.t("Frame rate"), fps + " fps");
                }
            }
            put(d, T.t("Duration"), Net.fmtTime(m.dur));
            String br = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE);
            if (br != null) put(d, T.t("Bitrate"), (Long.parseLong(br) / 1000) + " kbps");
            if (audio && Build.VERSION.SDK_INT >= 31) {
                String sr = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_SAMPLERATE);
                if (sr != null) put(d, T.t("Sample rate"), (Integer.parseInt(sr) / 1000f) + " kHz");
            }
            put(d, T.t("Format"), c.getContentResolver().getType(m.uri));
        } catch (Throwable ignored) {
        } finally {
            try {
                r.release();
            } catch (Throwable ignored) {
            }
        }
        put(d, T.t("Size"), TvInfo.gb(m.size));
        put(d, T.t("Folder"), m.folder);
        put(d, T.t("Added"), new java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.getDefault()).format(new java.util.Date(m.added)));
        put(d, T.t("File"), LocalShare.displayName(c, m.uri));
        return d;
    }

    private static String quality(int h, int w) {
        int s = Math.min(h, w) == 0 ? Math.max(h, w) : Math.min(h, w);
        return s >= 2000 ? "4K" : s >= 1000 ? "1080p" : s >= 700 ? "720p" : s >= 470 ? "480p" : s + "p";
    }

    private static void put(java.util.Map<String, String> d, String k, String v) {
        if (v != null && !v.trim().isEmpty() && !"null".equals(v)) d.put(k, v.trim());
    }

    // ------------------------------------------------------------------ hand over to the TV
    /**
     * Plays these items on the connected TV (from `index`, at `startMs`), streamed from this phone.
     * Returns a message for the user. Background thread.
     */
    public static String sendToTv(Context c, List<Store.Item> items, int index, long startMs) {
        String tv = Store.getStr("last_tv", "");
        if (tv.isEmpty()) return T.t("Connect to a TV first (Switch TV)");
        StringBuilder us = new StringBuilder(), ts = new StringBuilder();
        for (int i = index; i < items.size() && i < index + 100; i++) {
            Store.Item it = items.get(i);
            String u = it.u;
            if (u.startsWith("content://")) u = LocalShare.share(c, Uri.parse(u), safeName(it.t, u, c));
            else if (u.startsWith("/")) u = LocalShare.share(c, Uri.fromFile(new java.io.File(u)), new java.io.File(u).getName());
            us.append(u).append('\n');
            ts.append(it.t).append('\n');
        }
        try {
            HashMap<String, String> p = new HashMap<>();
            p.put("urls", us.toString());
            p.put("titles", ts.toString());
            p.put("mode", "now");
            org.json.JSONObject r = TvClient.post(tv, "/playmany", p);
            if (!r.optBoolean("ok", true)) return r.optString("msg", T.t("Cannot reach the TV"));
            if (startMs > 5000) {
                // continue at the same second once the TV has started
                for (int k = 0; k < 10; k++) {
                    Thread.sleep(1000);
                    org.json.JSONObject s = TvClient.post(tv, "/state", new HashMap<String, String>());
                    if (s.optBoolean("player") && s.optLong("len") > 0) {
                        HashMap<String, String> q = new HashMap<>();
                        q.put("c", "seek");
                        q.put("v", String.valueOf(startMs));
                        TvClient.post(tv, "/cmd", q);
                        break;
                    }
                }
            }
            return T.t("Playing on the TV");
        } catch (Exception e) {
            return T.t("Cannot reach the TV");
        }
    }

    private static String safeName(String title, String uri, Context c) {
        String n = LocalShare.displayName(c, Uri.parse(uri));
        return n == null || n.isEmpty() ? title : n;
    }

    public static String time(long ms) {
        return Net.fmtTime(ms);
    }
}
