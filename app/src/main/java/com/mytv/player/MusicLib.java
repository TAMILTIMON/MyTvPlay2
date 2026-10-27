package com.mytv.player;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phone music library extras: your playlists, ♥ favourites, recently / most played,
 * edited song info and lyrics. Everything is saved on the phone (per profile).
 */
public final class MusicLib {
    private MusicLib() {
    }

    public static final String FAV = "♥ Favourites", RECENT = "Recently played", MOST = "Most played";

    // ------------------------------------------------------------------ playlists (song uris, in order)
    private static JSONObject all() {
        try {
            return new JSONObject(Store.getStr(Store.pk("m_playlists"), "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static void save(JSONObject o) {
        Store.putStr(Store.pk("m_playlists"), o.toString());
    }

    public static synchronized List<String> playlistNames() {
        List<String> l = new ArrayList<>();
        JSONArray order = all().optJSONArray("_order");
        if (order != null) for (int i = 0; i < order.length(); i++) l.add(order.optString(i));
        return l;
    }

    public static synchronized List<String> songs(String name) {
        List<String> l = new ArrayList<>();
        if (FAV.equals(name)) return new ArrayList<>(favs());
        if (RECENT.equals(name)) return recent();
        if (MOST.equals(name)) return most();
        JSONArray a = all().optJSONArray(name);
        if (a != null) for (int i = 0; i < a.length(); i++) l.add(a.optString(i));
        return l;
    }

    public static synchronized boolean create(String name) {
        name = name.trim();
        JSONObject o = all();
        if (name.isEmpty() || o.has(name) || name.startsWith("_")) return false;
        try {
            o.put(name, new JSONArray());
            JSONArray order = o.optJSONArray("_order");
            if (order == null) order = new JSONArray();
            order.put(name);
            o.put("_order", order);
        } catch (Exception ignored) {
        }
        save(o);
        return true;
    }

    public static synchronized void delete(String name) {
        JSONObject o = all();
        o.remove(name);
        JSONArray order = o.optJSONArray("_order"), out = new JSONArray();
        if (order != null) for (int i = 0; i < order.length(); i++) if (!order.optString(i).equals(name)) out.put(order.optString(i));
        try {
            o.put("_order", out);
        } catch (Exception ignored) {
        }
        save(o);
    }

    public static synchronized boolean rename(String from, String to) {
        to = to.trim();
        JSONObject o = all();
        if (to.isEmpty() || o.has(to) || !o.has(from)) return false;
        try {
            o.put(to, o.get(from));
            o.remove(from);
            JSONArray order = o.optJSONArray("_order");
            if (order != null) for (int i = 0; i < order.length(); i++) if (order.optString(i).equals(from)) order.put(i, to);
        } catch (Exception ignored) {
        }
        save(o);
        return true;
    }

    private static void setSongs(String name, List<String> l) {
        JSONObject o = all();
        try {
            o.put(name, new JSONArray(l));
        } catch (Exception ignored) {
        }
        save(o);
    }

    /** Returns how many were added (songs already in the playlist are skipped). */
    public static synchronized int add(String name, List<String> uris) {
        List<String> l = songs(name);
        int n = 0;
        for (String u : uris) if (!l.contains(u)) {
            l.add(u);
            n++;
        }
        setSongs(name, l);
        return n;
    }

    public static synchronized void remove(String name, String uri) {
        if (FAV.equals(name)) {
            setFav(uri, false);
            return;
        }
        List<String> l = songs(name);
        l.remove(uri);
        setSongs(name, l);
    }

    public static synchronized void move(String name, int from, int to) {
        List<String> l = songs(name);
        if (from < 0 || from >= l.size() || to < 0 || to >= l.size()) return;
        String x = l.remove(from);
        l.add(to, x);
        setSongs(name, l);
    }

    // ------------------------------------------------------------------ favourites
    private static List<String> list(String key) {
        List<String> l = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Store.getStr(Store.pk(key), "[]"));
            for (int i = 0; i < a.length(); i++) l.add(a.getString(i));
        } catch (Exception ignored) {
        }
        return l;
    }

    public static synchronized List<String> favs() {
        return list("m_fav");
    }

    public static synchronized boolean isFav(String uri) {
        return favs().contains(uri);
    }

    public static synchronized void setFav(String uri, boolean on) {
        List<String> l = favs();
        l.remove(uri);
        if (on) l.add(0, uri);
        Store.putStr(Store.pk("m_fav"), new JSONArray(l).toString());
    }

    // ------------------------------------------------------------------ recently / most played
    public static synchronized void played(String uri) {
        List<String> r = list("m_recent");
        r.remove(uri);
        r.add(0, uri);
        while (r.size() > 100) r.remove(r.size() - 1);
        Store.putStr(Store.pk("m_recent"), new JSONArray(r).toString());
        try {
            JSONObject c = new JSONObject(Store.getStr(Store.pk("m_counts"), "{}"));
            c.put(uri, c.optInt(uri) + 1);
            Store.putStr(Store.pk("m_counts"), c.toString());
        } catch (Exception ignored) {
        }
    }

    public static synchronized List<String> recent() {
        return list("m_recent");
    }

    public static synchronized List<String> most() {
        List<String> l = new ArrayList<>();
        try {
            final JSONObject c = new JSONObject(Store.getStr(Store.pk("m_counts"), "{}"));
            java.util.Iterator<String> k = c.keys();
            while (k.hasNext()) l.add(k.next());
            Collections.sort(l, (a, b) -> c.optInt(b) - c.optInt(a));
        } catch (Exception ignored) {
        }
        return l.size() > 100 ? new ArrayList<>(l.subList(0, 100)) : l;
    }

    public static synchronized int plays(String uri) {
        try {
            return new JSONObject(Store.getStr(Store.pk("m_counts"), "{}")).optInt(uri);
        } catch (Exception e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ edited song info (shown everywhere in ZH Play)
    public static synchronized JSONObject edits(String uri) {
        try {
            JSONObject all = new JSONObject(Store.getStr("m_edits", "{}"));
            return all.optJSONObject(uri);
        } catch (Exception e) {
            return null;
        }
    }

    public static synchronized void saveEdits(String uri, String title, String artist, String album, String genre) {
        try {
            JSONObject all = new JSONObject(Store.getStr("m_edits", "{}"));
            all.put(uri, new JSONObject().put("title", title.trim()).put("artist", artist.trim()).put("album", album.trim())
                    .put("genre", genre.trim()));
            Store.putStr("m_edits", all.toString());
        } catch (Exception ignored) {
        }
    }

    public static synchronized void clearEdits(String uri) {
        try {
            JSONObject all = new JSONObject(Store.getStr("m_edits", "{}"));
            all.remove(uri);
            Store.putStr("m_edits", all.toString());
        } catch (Exception ignored) {
        }
    }

    static void applyEdits(PhoneMedia.M m) {
        JSONObject e = edits(m.uri.toString());
        if (e == null) return;
        if (!e.optString("title").isEmpty()) m.title = e.optString("title");
        if (!e.optString("artist").isEmpty()) m.artist = e.optString("artist");
        if (!e.optString("album").isEmpty()) m.albumName = e.optString("album");
        if (!e.optString("genre").isEmpty()) m.genre = e.optString("genre");
        m.sub = !m.artist.isEmpty() ? m.artist : m.albumName;
    }

    // ------------------------------------------------------------------ helpers
    /** Songs of a list of uris, in that order (only songs still on the phone). */
    public static List<PhoneMedia.M> resolve(List<String> uris, List<PhoneMedia.M> all) {
        Map<String, PhoneMedia.M> by = new HashMap<>();
        for (PhoneMedia.M m : all) by.put(m.uri.toString(), m);
        JSONObject remote = null;
        List<PhoneMedia.M> out = new ArrayList<>();
        for (String u : uris) {
            if (by.containsKey(u)) out.add(by.get(u));
            else if (u.startsWith("http://") || u.startsWith("https://")) {   // song / station from an imported M3U link
                if (remote == null) remote = remoteAll();
                out.add(remoteSong(u, remote.optJSONObject(u)));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ online songs (from M3U links)
    private static JSONObject remoteAll() {
        try {
            return new JSONObject(Store.getStr(Store.pk("m_remote"), "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static PhoneMedia.M remoteSong(String url, JSONObject meta) {
        PhoneMedia.M m = new PhoneMedia.M();
        m.uri = Uri.parse(url);
        String t = meta == null ? "" : meta.optString("t", "");
        if (t.isEmpty()) {
            String p = m.uri.getLastPathSegment();
            t = p == null || p.isEmpty() ? (m.uri.getHost() == null ? url : m.uri.getHost()) : p;
        }
        m.title = t;
        m.dur = meta == null ? 0 : meta.optLong("d", 0) * 1000L;
        m.sub = T.t("Online") + (m.uri.getHost() == null ? "" : " · " + m.uri.getHost());
        m.folder = T.t("Online");
        m.id = -Math.abs(url.hashCode());
        applyEdits(m);
        return m;
    }

    /** Remembers titles / lengths of online songs so the playlist shows names instead of web addresses. */
    public static synchronized void saveRemote(List<M3u.Entry> items) {
        JSONObject o = remoteAll();
        try {
            for (M3u.Entry e : items) {
                JSONObject j = new JSONObject();
                if (e.title != null) j.put("t", e.title);
                if (e.durSec > 0) j.put("d", e.durSec);
                o.put(e.url, j);
            }
        } catch (Exception ignored) {
        }
        Store.putStr(Store.pk("m_remote"), o.toString());
    }

    // ------------------------------------------------------------------ lyrics (LRCLIB, free; saved for offline)
    public static final class Line {
        public long t;      // -1 = not timed
        public String text;
    }

    /** Lyrics lines, from the phone's cache or LRCLIB. Background thread. Empty list = none found. */
    public static List<Line> lyrics(PhoneMedia.M m) {
        String key = "lyr_" + Integer.toHexString((m.title + "|" + m.artist).toLowerCase().hashCode());
        String cached = Store.getStr(key, null);
        if (cached == null) {
            cached = fetch(m);
            Store.putStr(key, cached);
        }
        return parse(cached);
    }

    private static String fetch(PhoneMedia.M m) {
        String title = m.title.replaceAll("\\(.*?\\)|\\[.*?\\]", "").replaceAll("[_]+", " ").replaceAll("\\s+", " ").trim();
        String[] urls = {
                "https://lrclib.net/api/get?track_name=" + Uri.encode(title) + "&artist_name=" + Uri.encode(m.artist)
                        + (m.dur > 0 ? "&duration=" + (m.dur / 1000) : ""),
                "https://lrclib.net/api/search?q=" + Uri.encode(title + " " + m.artist)};
        for (String u : urls) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(10000);
                c.setRequestProperty("User-Agent", "ZHPlay (https://github.com/TAMILTIMON/MyTvPlay2)");
                if (c.getResponseCode() != 200) continue;
                StringBuilder sb = new StringBuilder();
                try (java.io.InputStream in = c.getInputStream()) {
                    byte[] b = new byte[16384];
                    int n;
                    while ((n = in.read(b)) > 0) sb.append(new String(b, 0, n, "UTF-8"));
                }
                String body = sb.toString().trim();
                JSONObject o = body.startsWith("[") ? new JSONArray(body).optJSONObject(0) : new JSONObject(body);
                if (o == null) continue;
                String synced = o.optString("syncedLyrics", ""), plain = o.optString("plainLyrics", "");
                if (synced != null && !synced.isEmpty() && !"null".equals(synced)) return synced;
                if (plain != null && !plain.isEmpty() && !"null".equals(plain)) return plain;
            } catch (Exception ignored) {
            }
        }
        return "";
    }

    private static final Pattern LRC = Pattern.compile("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?\\]");

    static List<Line> parse(String s) {
        List<Line> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String raw : s.split("\n")) {
            Matcher m = LRC.matcher(raw);
            List<Long> times = new ArrayList<>();
            int end = 0;
            while (m.find() && m.start() == end) {
                String f = m.group(3) == null ? "0" : m.group(3);
                while (f.length() < 3) f += "0";
                times.add((Long.parseLong(m.group(1)) * 60 + Long.parseLong(m.group(2))) * 1000 + Long.parseLong(f.substring(0, 3)));
                end = m.end();
            }
            String text = raw.substring(end).trim();
            if (times.isEmpty()) {
                if (raw.trim().matches("^\\[[a-z]+:.*\\]$")) continue;   // [ar:…] tags
                Line l = new Line();
                l.t = -1;
                l.text = text;
                out.add(l);
            } else for (long t : times) {
                Line l = new Line();
                l.t = t;
                l.text = text;
                out.add(l);
            }
        }
        boolean timed = false;
        for (Line l : out) if (l.t >= 0) timed = true;
        if (timed) {
            // synced lyrics: blank lines / stray text between verses have no time — drop them
            List<Line> keep = new ArrayList<>();
            for (Line l : out) if (l.t >= 0) keep.add(l);
            out = keep;
            Collections.sort(out, (a, b) -> Long.compare(a.t, b.t));
        } else {
            // plain lyrics: no empty lines at the start / end
            while (!out.isEmpty() && out.get(0).text.isEmpty()) out.remove(0);
            while (!out.isEmpty() && out.get(out.size() - 1).text.isEmpty()) out.remove(out.size() - 1);
        }
        return out;
    }

    /** Index of the line being sung at `pos` (timed lyrics), or -1. */
    public static int lineAt(List<Line> lines, long pos) {
        int idx = -1;
        for (int i = 0; i < lines.size(); i++) {
            long t = lines.get(i).t;
            if (t < 0) continue;            // untimed line: never "the current" one
            if (t <= pos) idx = i;
            else break;
        }
        return idx;
    }

    /** Groups of the library: key → songs (for Albums / Artists / Genres / Folders). */
    public static LinkedHashMap<String, List<PhoneMedia.M>> group(List<PhoneMedia.M> all, int by) {
        LinkedHashMap<String, List<PhoneMedia.M>> g = new LinkedHashMap<>();
        for (PhoneMedia.M m : all) {
            String k = by == 0 ? m.albumName : by == 1 ? m.artist : by == 2 ? m.genre : m.bucket;
            if (k == null || k.trim().isEmpty()) k = T.t("Unknown");
            List<PhoneMedia.M> l = g.get(k);
            if (l == null) g.put(k, l = new ArrayList<>());
            l.add(m);
        }
        List<Map.Entry<String, List<PhoneMedia.M>>> es = new ArrayList<>(g.entrySet());
        Collections.sort(es, (a, b) -> a.getKey().compareToIgnoreCase(b.getKey()));
        LinkedHashMap<String, List<PhoneMedia.M>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<PhoneMedia.M>> e : es) out.put(e.getKey(), e.getValue());
        return out;
    }
}
