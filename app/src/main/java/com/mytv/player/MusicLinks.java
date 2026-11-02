package com.mytv.player;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Music → Links: songs and radio streams by address, .m3u / .m3u8 playlists, and YouTube videos to LISTEN to.
 * Normal links play in the music player; YouTube plays in the ZH Play browser in audio-only background mode
 * (YouTube doesn't publish plain audio addresses).
 */
public final class MusicLinks {
    private MusicLinks() {
    }

    static final String KEY = "music_links";
    public static final int REQ_M3U = 85;

    // ------------------------------------------------------------------ the saved links
    static JSONArray all() {
        try {
            return new JSONArray(Store.getStr(KEY, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    public static List<PhoneMedia.M> list() {
        List<PhoneMedia.M> out = new ArrayList<>();
        JSONArray a = all();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            PhoneMedia.M m = new PhoneMedia.M();
            m.id = -1000 - i;                                    // not a phone song
            m.uri = Uri.parse(o.optString("u"));
            m.title = o.optString("t");
            boolean yt = isYouTube(o.optString("u"));
            m.sub = yt ? "YouTube · " + T.t("audio only") : o.optString("from", "").isEmpty() ? T.t("Stream / link") : o.optString("from");
            m.artist = yt ? "YouTube" : "";
            m.albumName = "";
            out.add(m);
        }
        return out;
    }

    private static void save(JSONArray a) {
        Store.putStr(KEY, a.toString());
    }

    /** Adds one link (no duplicates). */
    public static boolean add(String title, String url, String from) {
        url = url.trim();
        if (url.isEmpty()) return false;
        if (isYouTube(url)) url = youtubeWatch(url);
        JSONArray a = all();
        for (int i = 0; i < a.length(); i++) if (url.equals(a.optJSONObject(i).optString("u"))) return false;
        try {
            a.put(new JSONObject().put("t", title == null || title.trim().isEmpty() ? nameOf(url) : title.trim()).put("u", url)
                    .put("from", from == null ? "" : from));
        } catch (Exception ignored) {
        }
        save(a);
        return true;
    }

    public static void remove(String url) {
        JSONArray a = all(), keep = new JSONArray();
        for (int i = 0; i < a.length(); i++) if (!url.equals(a.optJSONObject(i).optString("u"))) keep.put(a.opt(i));
        save(keep);
    }

    public static void rename(String url, String title) {
        JSONArray a = all();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (url.equals(o.optString("u"))) try {
                o.put("t", title);
            } catch (Exception ignored) {
            }
        }
        save(a);
    }

    // ------------------------------------------------------------------ YouTube
    private static final Pattern YT = Pattern.compile("(?:youtube\\.com/(?:watch\\?(?:.*&)?v=|shorts/|live/|embed/)|youtu\\.be/)([A-Za-z0-9_-]{11})");

    public static boolean isYouTube(String u) {
        return u != null && YT.matcher(u).find();
    }

    static String youtubeWatch(String u) {
        Matcher m = YT.matcher(u);
        return m.find() ? "https://m.youtube.com/watch?v=" + m.group(1) : u;
    }

    /** YouTube to listen: the browser, audio only, in the background (notification: play / pause, timeline). */
    public static void playYouTube(Context c, String url) {
        Store.putBool("b_audio", true);
        c.startActivity(new Intent(c, BrowserActivity.class).putExtra(BrowserActivity.EXTRA_URL, youtubeWatch(url))
                .putExtra(BrowserActivity.EXTRA_LISTEN, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    // ------------------------------------------------------------------ playlists (.m3u / .m3u8)
    /** {title, url} pairs of a track playlist; null when the text is a single HLS stream (radio / live). */
    static List<String[]> parseM3u(String text, String base) {
        if (text.contains("#EXT-X-")) return null;               // HLS: one stream, not a list of songs
        List<String[]> out = new ArrayList<>();
        String title = null;
        for (String raw : text.replace("\r", "").split("\n")) {
            String l = raw.trim();
            if (l.isEmpty()) continue;
            if (l.startsWith("#EXTINF")) {
                int c = l.indexOf(',');
                title = c >= 0 ? l.substring(c + 1).trim() : null;
            } else if (!l.startsWith("#")) {
                String u = l;
                if (!u.matches("(?i)^[a-z][a-z0-9+.-]*:.*") && base != null) {              // relative address
                    try {
                        u = new java.net.URL(new java.net.URL(base), u).toString();
                    } catch (Exception ignored) {
                    }
                }
                out.add(new String[]{title == null ? nameOf(u) : title, u});
                title = null;
            }
        }
        return out;
    }

    static String nameOf(String u) {
        if (isYouTube(u)) return "YouTube " + youtubeWatch(u).replaceAll(".*v=", "");
        String n = u.replaceAll("[?#].*$", "").replaceAll("/+$", "");
        n = n.substring(n.lastIndexOf('/') + 1);
        try {
            n = java.net.URLDecoder.decode(n, "UTF-8");
        } catch (Exception ignored) {
        }
        n = n.replaceAll("(?i)\\.(mp3|m4a|aac|ogg|opus|flac|wav|m3u8?|pls)$", "");
        return n.isEmpty() ? u : n;
    }

    /** A pasted address: a playlist is opened and its songs added; anything else becomes one link. */
    static void addAddress(final Activity a, final String url, final String title, final Runnable done) {
        final String u = url.trim();
        if (u.isEmpty()) return;
        String low = u.toLowerCase(Locale.ROOT).replaceAll("[?#].*$", "");
        if (!low.endsWith(".m3u") && !low.endsWith(".m3u8")) {
            boolean ok = add(title, u, "");
            UiKit.makeToast(a, ok ? "✓ " + T.t("Added to Music → Links") : T.t("Already in Links"), Toast.LENGTH_SHORT).show();
            if (done != null) done.run();
            return;
        }
        UiKit.makeToast(a, T.t("Reading the playlist…"), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String msg;
            try {
                String text = Net.fetchText(u);
                List<String[]> songs = parseM3u(text, u);
                if (songs == null) {
                    add(title == null || title.isEmpty() ? nameOf(u) : title, u, T.t("Live stream"));
                    msg = "✓ " + T.t("Live stream added");
                } else {
                    int n = 0;
                    for (String[] s : songs) if (add(s[0], s[1], nameOf(u))) n++;
                    msg = "✓ " + n + " " + T.t("songs added from the playlist");
                }
            } catch (Exception e) {
                msg = T.t("Can't read this playlist") + ": " + e.getMessage();
            }
            final String m = msg;
            new Handler(Looper.getMainLooper()).post(() -> {
                UiKit.makeToast(a, m, Toast.LENGTH_LONG).show();
                if (done != null) done.run();
            });
        }).start();
    }

    /** "＋ Add link / M3U": paste an address, or pick an .m3u file. */
    public static void addDialog(final Activity a, final Runnable done) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(a, 20), Ui.dp(a, 6), Ui.dp(a, 20), 0);
        final EditText url = new EditText(a);
        url.setHint(T.t("Address: song, radio stream, .m3u playlist or YouTube"));
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        android.content.ClipboardManager cm = (android.content.ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
        try {
            CharSequence clip = cm != null && cm.getPrimaryClip() != null ? cm.getPrimaryClip().getItemAt(0).getText() : null;
            if (clip != null && clip.toString().trim().matches("(?i)^https?://\\S+$")) url.setText(clip.toString().trim());   // from the clipboard
        } catch (Exception ignored) {
        }
        box.addView(url);
        final EditText name = new EditText(a);
        name.setHint(T.t("Name (optional)"));
        name.setSingleLine(true);
        box.addView(name);
        new UiDialog(a).setTitle("＋ " + T.t("Add link / M3U")).setView(box)
                .setPositiveButton(T.t("Add"), (d, w) -> addAddress(a, url.getText().toString(), name.getText().toString(), done))
                .setNeutralButton(T.t("M3U file…"), (d, w) -> a.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_M3U))
                .setNegativeButton(T.t("Cancel"), null).show();
    }

    /** onActivityResult: an .m3u file was picked. */
    public static boolean onResult(final Activity a, int req, int res, Intent data, final Runnable done) {
        if (req != REQ_M3U) return false;
        if (res != Activity.RESULT_OK || data == null || data.getData() == null) return true;
        try (java.io.InputStream in = a.getContentResolver().openInputStream(data.getData())) {
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0 && o.size() < 4 * 1024 * 1024) o.write(b, 0, n);
            List<String[]> songs = parseM3u(o.toString("UTF-8"), null);
            String from = LocalShare.displayName(a, data.getData());
            int added = 0;
            if (songs == null) UiKit.makeToast(a, T.t("This file is a live stream — paste its web address instead"), Toast.LENGTH_LONG).show();
            else for (String[] s : songs) if (add(s[0], s[1], from == null ? "M3U" : from)) added++;
            if (songs != null) UiKit.makeToast(a, "✓ " + added + " " + T.t("songs added from the playlist"), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            UiKit.makeToast(a, T.t("Can't read this playlist") + ": " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        if (done != null) done.run();
        return true;
    }
}
