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

        public Store.Item item() {
            return new Store.Item(title, uri.toString(), "");
        }
    }

    public static List<M> query(Context c, boolean audio) {
        List<M> out = new ArrayList<>();
        Uri base = audio ? MediaStore.Audio.Media.EXTERNAL_CONTENT_URI : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String[] cols = audio
                ? new String[]{MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.ALBUM}
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
                    m.sub = artist == null || artist.startsWith("<") ? (cur.getString(7) == null ? "" : cur.getString(7)) : artist;
                    m.album = cur.getLong(6);
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

    public static Bitmap cached(M m) {
        return ART.get(m.uri.toString());
    }

    /** Video frame or album cover (background thread). */
    @SuppressWarnings("deprecation")
    public static Bitmap art(Context c, M m, boolean audio) {
        String k = m.uri.toString();
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
        if (b != null) ART.put(k, b);
        else NONE.put(k, true);
        return b;
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
