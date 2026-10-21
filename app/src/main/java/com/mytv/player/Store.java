package com.mytv.player;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** Keeps playlists, resume positions and player settings on the TV. */
public class Store {
    public static final String RECENT = "Recently played";
    private static final int MAX_RECENT = 40;
    public static final int EQ_BANDS = 10;

    public static class Item {
        public final String t, u, s; // title, url, subtitle url (optional)

        public Item(String t, String u, String s) {
            this.u = u == null ? "" : u.trim();
            this.t = (t == null || t.trim().isEmpty()) ? Formats.titleFromUrl(this.u) : t.trim();
            this.s = s == null ? "" : s.trim();
        }
    }

    private static SharedPreferences prefs, posPrefs, setPrefs;
    private static final LinkedHashMap<String, List<Item>> data = new LinkedHashMap<>();
    public static volatile int version = 0;
    private static File dir;
    /** Playlists are saved one file each, in the background (big IPTV lists stay fast). */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    public static synchronized void init(Context c) {
        if (prefs != null) return;
        Context app = c.getApplicationContext();
        prefs = app.getSharedPreferences("mytv", Context.MODE_PRIVATE);
        posPrefs = app.getSharedPreferences("mytv_pos", Context.MODE_PRIVATE);
        setPrefs = app.getSharedPreferences("mytv_settings", Context.MODE_PRIVATE);
        dir = new File(app.getFilesDir(), "playlists");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        load();
    }

    private static File fileFor(String name) {
        return new File(dir, "pl_" + Integer.toHexString(name.hashCode()) + "_" + name.length() + ".json");
    }

    private static List<Item> readItems(JSONArray it) throws Exception {
        List<Item> l = new ArrayList<>(it.length());
        for (int j = 0; j < it.length(); j++) {
            JSONObject x = it.getJSONObject(j);
            l.add(new Item(x.optString("t"), x.optString("u"), x.optString("s", "")));
        }
        return l;
    }

    private static void load() {
        data.clear();
        try {
            String legacy = prefs.getString("data", null);
            if (legacy != null) {
                // old format (everything in one preference) -> move to files
                JSONArray a = new JSONObject(legacy).getJSONArray("playlists");
                for (int i = 0; i < a.length(); i++) {
                    JSONObject p = a.getJSONObject(i);
                    data.put(p.getString("name"), readItems(p.getJSONArray("items")));
                }
                for (String n : data.keySet()) saveList(n);
                saveIndex();
                prefs.edit().remove("data").apply();
                return;
            }
            JSONArray names = new JSONArray(prefs.getString("index", "[]"));
            for (int i = 0; i < names.length(); i++) {
                String n = names.getString(i);
                List<Item> l = new ArrayList<>();
                File f = fileFor(n);
                if (f.exists()) {
                    byte[] b = new byte[(int) f.length()];
                    try (FileInputStream in = new FileInputStream(f)) {
                        int off = 0, r;
                        while (off < b.length && (r = in.read(b, off, b.length - off)) > 0) off += r;
                    }
                    l = readItems(new JSONArray(new String(b, "UTF-8")));
                }
                data.put(n, l);
            }
        } catch (Exception ignored) {
        }
    }

    private static JSONArray itemsJson(List<Item> items, int from, int max) throws Exception {
        JSONArray a = new JSONArray();
        for (int i = from; i < items.size() && a.length() < max; i++) {
            Item it = items.get(i);
            JSONObject x = new JSONObject().put("t", it.t).put("u", it.u);
            if (!it.s.isEmpty()) x.put("s", it.s);
            a.put(x);
        }
        return a;
    }

    private static void saveIndex() {
        JSONArray a = new JSONArray();
        for (String n : data.keySet()) a.put(n);
        prefs.edit().putString("index", a.toString()).apply();
    }

    private static void saveList(final String name) {
        final List<Item> copy = data.containsKey(name) ? new ArrayList<>(data.get(name)) : null;
        final File f = fileFor(name);
        IO.execute(new Runnable() {
            public void run() {
                try {
                    if (copy == null) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                        return;
                    }
                    File tmp = new File(f.getPath() + ".tmp");
                    try (FileOutputStream o = new FileOutputStream(tmp)) {
                        o.write(itemsJson(copy, 0, Integer.MAX_VALUE).toString().getBytes("UTF-8"));
                    }
                    //noinspection ResultOfMethodCallIgnored
                    tmp.renameTo(f);
                } catch (Exception ignored) {
                }
            }
        });
    }

    private static void changed(String name, boolean namesChanged) {
        saveList(name);
        if (namesChanged) saveIndex();
        version++;
    }

    private static String cleanName(String n) {
        n = n == null ? "" : n.trim();
        return n.isEmpty() ? "My Playlist" : n;
    }

    /** For the phone page: every playlist with its size and only the first `limit` items. */
    public static synchronized String toJson() {
        return toJson(300);
    }

    public static synchronized String toJson(int limit) {
        try {
            JSONArray arr = new JSONArray();
            for (String name : names()) {
                List<Item> l = data.get(name);
                arr.put(new JSONObject().put("name", name).put("count", l.size()).put("items", itemsJson(l, 0, limit)));
            }
            return new JSONObject().put("playlists", arr).toString();
        } catch (Exception e) {
            return "{\"playlists\":[]}";
        }
    }

    /** One page of a playlist (phone page "show more" / search). */
    public static synchronized String pageJson(String name, int from, int max, String query) {
        try {
            List<Item> l = data.get(name);
            if (l == null) l = new ArrayList<>();
            JSONArray a = new JSONArray();
            String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
            for (int i = Math.max(0, from); i < l.size() && a.length() < max; i++) {
                Item it = l.get(i);
                if (!q.isEmpty() && !it.t.toLowerCase(Locale.ROOT).contains(q)) continue;
                a.put(new JSONObject().put("i", i).put("t", it.t).put("u", it.u));
            }
            return new JSONObject().put("name", name).put("count", l.size()).put("items", a).toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /** Playlist names, "Recently played" first. */
    public static synchronized List<String> names() {
        List<String> l = new ArrayList<>();
        if (data.containsKey(RECENT)) l.add(RECENT);
        for (String n : data.keySet()) if (!RECENT.equals(n)) l.add(n);
        return l;
    }

    public static synchronized int count(String name) {
        List<Item> l = data.get(name);
        return l == null ? 0 : l.size();
    }

    public static synchronized List<Item> items(String name) {
        List<Item> l = data.get(name);
        return l == null ? new ArrayList<Item>() : new ArrayList<>(l);
    }

    private static List<Item> list(String name) {
        List<Item> l = data.get(name);
        if (l == null) {
            l = new ArrayList<>();
            data.put(name, l);
        }
        return l;
    }

    public static synchronized void add(String name, Item item) {
        String n = cleanName(name);
        boolean isNew = !data.containsKey(n);
        list(n).add(item);
        changed(n, isNew);
    }

    public static synchronized int addAll(String name, List<Item> items) {
        String n = cleanName(name);
        boolean isNew = !data.containsKey(n);
        list(n).addAll(items);
        changed(n, isNew);
        return items.size();
    }

    public static synchronized void addRecent(Item item) {
        boolean isNew = !data.containsKey(RECENT);
        List<Item> l = list(RECENT);
        Iterator<Item> i = l.iterator();
        while (i.hasNext()) if (i.next().u.equals(item.u)) i.remove();
        l.add(0, item);
        while (l.size() > MAX_RECENT) l.remove(l.size() - 1);
        changed(RECENT, isNew);
    }

    public static synchronized void delItem(String name, int idx) {
        List<Item> l = data.get(name);
        if (l != null && idx >= 0 && idx < l.size()) {
            l.remove(idx);
            changed(name, false);
        }
    }

    public static synchronized void moveItem(String name, int idx, int dir) {
        List<Item> l = data.get(name);
        if (l == null) return;
        int to = idx + dir;
        if (idx < 0 || idx >= l.size() || to < 0 || to >= l.size()) return;
        Item it = l.remove(idx);
        l.add(to, it);
        changed(name, false);
    }

    /** Deletes the items at these positions. Returns how many were removed. */
    public static synchronized int delMany(String name, java.util.Set<Integer> idx) {
        List<Item> l = data.get(name);
        if (l == null || idx.isEmpty()) return 0;
        List<Item> keep = new ArrayList<>(l.size());
        for (int i = 0; i < l.size(); i++) if (!idx.contains(i)) keep.add(l.get(i));
        int removed = l.size() - keep.size();
        l.clear();
        l.addAll(keep);
        if (removed > 0) changed(name, false);
        return removed;
    }

    /** "Select all" delete: every item (or every item matching the search), except some. */
    public static synchronized int delMatching(String name, String query, java.util.Set<Integer> except) {
        List<Item> l = data.get(name);
        if (l == null) return 0;
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        java.util.Set<Integer> del = new java.util.HashSet<>();
        for (int i = 0; i < l.size(); i++) {
            if (except.contains(i)) continue;
            if (q.isEmpty() || l.get(i).t.toLowerCase(Locale.ROOT).contains(q)) del.add(i);
        }
        return delMany(name, del);
    }

    public static synchronized int delPlaylists(java.util.Collection<String> names) {
        int n = 0;
        for (String name : names) {
            if (data.remove(name) != null) {
                saveList(name);
                n++;
            }
        }
        if (n > 0) {
            saveIndex();
            version++;
        }
        return n;
    }

    public static synchronized void delPlaylist(String name) {
        if (data.remove(name) != null) changed(name, true);
    }

    // ---------- continue watching ----------
    public static final class Watch {
        public final String t, u;
        public final long pos, len, when;

        Watch(String t, String u, long pos, long len, long when) {
            this.t = t;
            this.u = u;
            this.pos = pos;
            this.len = len;
            this.when = when;
        }
    }

    public static synchronized List<Watch> history() {
        List<Watch> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(getStr("history", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Watch(o.optString("t"), o.optString("u"), o.optLong("p"), o.optLong("l"), o.optLong("w")));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void saveHistory(List<Watch> l) {
        try {
            JSONArray a = new JSONArray();
            for (Watch w : l) {
                a.put(new JSONObject().put("t", w.t).put("u", w.u).put("p", w.pos).put("l", w.len).put("w", w.when));
            }
            putStr("history", a.toString());
            version++;
        } catch (Exception ignored) {
        }
    }

    public static synchronized void putHistory(String title, String url, long pos, long len) {
        List<Watch> l = history();
        Iterator<Watch> i = l.iterator();
        while (i.hasNext()) if (i.next().u.equals(url)) i.remove();
        l.add(0, new Watch(title, url, pos, len, System.currentTimeMillis()));
        while (l.size() > 30) l.remove(l.size() - 1);
        saveHistory(l);
    }

    public static synchronized void removeHistory(String url) {
        List<Watch> l = history();
        Iterator<Watch> i = l.iterator();
        boolean changed = false;
        while (i.hasNext()) if (i.next().u.equals(url)) {
            i.remove();
            changed = true;
        }
        if (changed) saveHistory(l);
    }

    // ---------- resume positions ----------
    public static long pos(String url) {
        return posPrefs == null ? 0 : posPrefs.getLong(url, 0);
    }

    public static void setPos(String url, long p) {
        if (posPrefs != null) posPrefs.edit().putLong(url, p).apply();
    }

    // ---------- settings ----------
    public static int getInt(String k, int d) {
        return setPrefs == null ? d : setPrefs.getInt(k, d);
    }

    public static void putInt(String k, int v) {
        if (setPrefs != null) setPrefs.edit().putInt(k, v).apply();
    }

    public static float getFloat(String k, float d) {
        return setPrefs == null ? d : setPrefs.getFloat(k, d);
    }

    public static void putFloat(String k, float v) {
        if (setPrefs != null) setPrefs.edit().putFloat(k, v).apply();
    }

    public static String getStr(String k, String d) {
        return setPrefs == null ? d : setPrefs.getString(k, d);
    }

    public static void putStr(String k, String v) {
        if (setPrefs != null) setPrefs.edit().putString(k, v).apply();
    }

    public static boolean getBool(String k, boolean d) {
        return setPrefs == null ? d : setPrefs.getBoolean(k, d);
    }

    public static void putBool(String k, boolean v) {
        if (setPrefs != null) setPrefs.edit().putBoolean(k, v).apply();
    }

    public static float[] getBands() {
        float[] b = new float[EQ_BANDS];
        String[] p = getStr("eq_bands", "").split(",");
        for (int i = 0; i < b.length && i < p.length; i++) {
            try {
                b[i] = Float.parseFloat(p[i]);
            } catch (Exception ignored) {
            }
        }
        return b;
    }

    public static void putBands(float[] b) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format(Locale.US, "%.1f", b[i]));
        }
        putStr("eq_bands", sb.toString());
    }

    /** Reads a PLS playlist (File1=..., Title1=...). */
    public static List<Item> parsePls(String text) {
        java.util.TreeMap<Integer, String[]> m = new java.util.TreeMap<>();
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            java.util.regex.Matcher x = java.util.regex.Pattern.compile("(?i)^(file|title)(\\d+)=(.*)$").matcher(line);
            if (!x.find()) continue;
            int n = Integer.parseInt(x.group(2));
            String[] e = m.get(n);
            if (e == null) {
                e = new String[2];
                m.put(n, e);
            }
            if (x.group(1).equalsIgnoreCase("file")) e[0] = x.group(3).trim();
            else e[1] = x.group(3).trim();
        }
        List<Item> out = new ArrayList<>();
        for (String[] e : m.values()) if (e[0] != null && Formats.looksPlayable(e[0])) out.add(new Item(e[1], e[0], ""));
        return out;
    }

    private static final java.util.regex.Pattern ATTR =
            java.util.regex.Pattern.compile("(http-user-agent|http-referrer|http-referer)=\"([^\"]*)\"", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** Reads an M3U playlist (IPTV lists too), or a plain list of links, one per line. */
    public static List<Item> parseM3u(String text) {
        List<Item> out = new ArrayList<>();
        String title = null, ua = null, ref = null;
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("\uFEFF")) line = line.substring(1);
            if (line.startsWith("#EXTINF")) {
                int c = line.lastIndexOf(',');
                title = c >= 0 ? line.substring(c + 1).trim() : null;
                java.util.regex.Matcher m = ATTR.matcher(c >= 0 ? line.substring(0, c) : line);
                while (m.find()) {
                    if (m.group(1).toLowerCase(Locale.ROOT).contains("agent")) ua = m.group(2);
                    else ref = m.group(2);
                }
            } else if (line.startsWith("#EXTVLCOPT:")) {
                String o = line.substring(11).trim();
                if (o.toLowerCase(Locale.ROOT).startsWith("http-user-agent=")) ua = o.substring(16);
                else if (o.toLowerCase(Locale.ROOT).startsWith("http-referrer=")) ref = o.substring(14);
                else if (o.toLowerCase(Locale.ROOT).startsWith("http-referer=")) ref = o.substring(13);
            } else if (line.startsWith("#")) {
                continue;
            } else if (Formats.looksPlayable(line)) {
                String u = line;
                if ((ua != null || ref != null) && u.startsWith("http") && u.indexOf('|') < 0) {
                    StringBuilder h = new StringBuilder("|");
                    if (ref != null) h.append("Referer=").append(android.net.Uri.encode(ref));
                    if (ua != null) h.append(ref != null ? "&" : "").append("User-Agent=").append(android.net.Uri.encode(ua));
                    u = u + h;
                }
                out.add(new Item(title, u, ""));
                title = null;
                ua = null;
                ref = null;
            }
        }
        return out;
    }
}
