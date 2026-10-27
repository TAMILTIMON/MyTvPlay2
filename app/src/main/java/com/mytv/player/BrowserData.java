package com.mytv.player;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/** Browser history (title, address, time, visits), bookmarks, site icons and the open tabs. */
public final class BrowserData {
    private BrowserData() {
    }

    public static final class Entry {
        public String title = "", url = "";
        public long time;        // last visit / added
        public int visits = 1;
    }

    private static final int MAX_HISTORY = 3000;

    // ------------------------------------------------------------------ history
    public static synchronized List<Entry> history() {
        List<Entry> l = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Store.getStr("bhist", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Entry e = new Entry();
                e.title = o.optString("t");
                e.url = o.optString("u");
                e.time = o.optLong("w");
                e.visits = Math.max(1, o.optInt("n", 1));
                l.add(e);
            }
        } catch (Exception ignored) {
        }
        return l;
    }

    private static void saveHistory(List<Entry> l) {
        JSONArray a = new JSONArray();
        for (int i = 0; i < l.size() && i < MAX_HISTORY; i++) {
            Entry e = l.get(i);
            try {
                a.put(new JSONObject().put("t", e.title).put("u", e.url).put("w", e.time).put("n", e.visits));
            } catch (Exception ignored) {
            }
        }
        Store.putStr("bhist", a.toString());
    }

    /** A visit: newest first, one row per address, counts visits. */
    public static synchronized void visit(String title, String url) {
        if (url == null || !url.startsWith("http")) return;
        List<Entry> l = history();
        Entry e = null;
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).url.equals(url)) {
                e = l.remove(i);
                e.visits++;
                break;
            }
        }
        if (e == null) {
            e = new Entry();
            e.url = url;
        }
        if (title != null && !title.isEmpty() && !title.equals(url)) e.title = title;
        e.time = System.currentTimeMillis();
        l.add(0, e);
        saveHistory(l);
    }

    /** The page's real title arrives after loading: fill it in. */
    public static synchronized void title(String url, String title) {
        if (url == null || title == null || title.isEmpty() || title.equals(url) || title.startsWith("http")) return;
        List<Entry> l = history();
        for (Entry e : l) {
            if (e.url.equals(url)) {
                if (!title.equals(e.title)) {
                    e.title = title;
                    saveHistory(l);
                }
                return;
            }
        }
    }

    public static synchronized void deleteHistory(java.util.Collection<String> urls) {
        List<Entry> l = history(), keep = new ArrayList<>();
        for (Entry e : l) if (!urls.contains(e.url)) keep.add(e);
        saveHistory(keep);
    }

    public static synchronized void clearHistory() {
        Store.putStr("bhist", "[]");
    }

    /** Clears visits from `since` (ms) until now — "last hour", "today", … */
    public static synchronized void clearSince(long since) {
        List<Entry> l = history(), keep = new ArrayList<>();
        for (Entry e : l) if (e.time < since) keep.add(e);
        saveHistory(keep);
    }

    // ------------------------------------------------------------------ bookmarks
    private static final String[][] DEFAULTS = {{"YouTube", "https://m.youtube.com"}, {"Google", "https://www.google.com"},
            {"Wikipedia", "https://www.wikipedia.org"}};

    public static synchronized List<Entry> bookmarks() {
        List<Entry> l = new ArrayList<>();
        String js = Store.getStr("bmarks2", "");
        if (js.isEmpty()) {
            // older format: "title<TAB>url" lines
            String old = Store.getStr("bookmarks", "");
            if (old.isEmpty() && !Store.getBool("bm_set", false)) {
                for (String[] b : DEFAULTS) l.add(entry(b[0], b[1], 0));
            } else {
                for (String line : old.split("\n")) {
                    int tab = line.indexOf('\t');
                    if (tab > 0) l.add(entry(line.substring(0, tab), line.substring(tab + 1), 0));
                }
            }
            return l;
        }
        try {
            JSONArray a = new JSONArray(js);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                l.add(entry(o.optString("t"), o.optString("u"), o.optLong("w")));
            }
        } catch (Exception ignored) {
        }
        return l;
    }

    private static Entry entry(String t, String u, long w) {
        Entry e = new Entry();
        e.title = t;
        e.url = u;
        e.time = w;
        return e;
    }

    public static synchronized void saveBookmarks(List<Entry> l) {
        JSONArray a = new JSONArray();
        for (Entry e : l) {
            try {
                a.put(new JSONObject().put("t", e.title).put("u", e.url).put("w", e.time));
            } catch (Exception ignored) {
            }
        }
        Store.putStr("bmarks2", a.toString());
        // keep the old format in step (start page / older code)
        StringBuilder sb = new StringBuilder();
        for (Entry e : l) sb.append(e.title.replace("\t", " ").replace("\n", " ")).append('\t').append(e.url).append('\n');
        Store.putStr("bookmarks", sb.toString());
        Store.putBool("bm_set", true);
    }

    public static synchronized boolean isBookmarked(String url) {
        for (Entry e : bookmarks()) if (e.url.equals(url)) return true;
        return false;
    }

    /** Adds or removes; returns true when it is now bookmarked. */
    public static synchronized boolean toggleBookmark(String title, String url) {
        List<Entry> l = bookmarks();
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).url.equals(url)) {
                l.remove(i);
                saveBookmarks(l);
                return false;
            }
        }
        l.add(0, entry(title == null || title.isEmpty() ? host(url) : title, url, System.currentTimeMillis()));
        saveBookmarks(l);
        return true;
    }

    // ------------------------------------------------------------------ site icons
    public static String host(String url) {
        try {
            String h = Uri.parse(url).getHost();
            return h == null ? url : h.replaceFirst("^www\\.", "").replaceFirst("^m\\.", "");
        } catch (Exception e) {
            return url;
        }
    }

    private static File iconFile(Context c, String url) {
        File d = new File(c.getFilesDir(), "favicons");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return new File(d, host(url).replaceAll("[^a-zA-Z0-9.-]", "_") + ".png");
    }

    public static void saveIcon(Context c, String url, Bitmap b) {
        if (url == null || b == null || !url.startsWith("http")) return;
        try (FileOutputStream o = new FileOutputStream(iconFile(c, url))) {
            Bitmap s = b.getWidth() > 96 ? Bitmap.createScaledBitmap(b, 96, 96 * b.getHeight() / Math.max(1, b.getWidth()), true) : b;
            s.compress(Bitmap.CompressFormat.PNG, 100, o);
        } catch (Exception ignored) {
        }
    }

    public static Bitmap icon(Context c, String url) {
        File f = iconFile(c, url);
        return f.isFile() ? BitmapFactory.decodeFile(f.getAbsolutePath()) : null;
    }

    // ------------------------------------------------------------------ open tabs (restored after closing the app)
    public static void saveTabs(List<String[]> tabs, int current) {
        JSONArray a = new JSONArray();
        for (String[] t : tabs) {
            try {
                a.put(new JSONObject().put("u", t[1]).put("t", t[0]));
            } catch (Exception ignored) {
            }
        }
        try {
            Store.putStr("b_tabs", new JSONObject().put("cur", current).put("tabs", a).toString());
        } catch (Exception ignored) {
        }
    }

    /** {title, url} of the saved tabs; the current index is the last element's [2]. */
    public static List<String[]> savedTabs() {
        List<String[]> l = new ArrayList<>();
        try {
            JSONObject o = new JSONObject(Store.getStr("b_tabs", "{}"));
            JSONArray a = o.optJSONArray("tabs");
            if (a != null) for (int i = 0; i < a.length(); i++) {
                JSONObject t = a.getJSONObject(i);
                l.add(new String[]{t.optString("t"), t.optString("u"), String.valueOf(o.optInt("cur"))});
            }
        } catch (Exception ignored) {
        }
        return l;
    }
}
