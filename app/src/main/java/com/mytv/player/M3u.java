package com.mytv.player;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/** Reads .m3u / .m3u8 / .pls playlists (the text a playlist link returns) into a list of songs or stations. */
final class M3u {
    private M3u() {
    }

    static final class Entry {
        String url, title;
        long durSec;
    }

    static final class Result {
        String name;                       // #PLAYLIST: tag, if any
        final List<Entry> items = new ArrayList<>();
        boolean isStream;                  // the link itself is one HLS stream, not a list of songs
        int skipped;                       // lines that were not web links (local file paths etc.)
    }

    static Result parse(String text, String baseUrl) {
        Result r = new Result();
        if (text == null) return r;
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        String[] lines = text.split("\r?\n|\r");
        // an HLS stream (adaptive video/audio) is not a list of songs: keep the link as one entry
        for (String l : lines) {
            if (l.startsWith("#EXT-X-STREAM-INF") || l.startsWith("#EXT-X-TARGETDURATION") || l.startsWith("#EXT-X-MEDIA-SEQUENCE")) {
                Entry e = new Entry();
                e.url = baseUrl;
                e.title = null;
                r.items.add(e);
                r.isStream = true;
                return r;
            }
        }
        if (text.regionMatches(true, 0, "[playlist]", 0, 10) || text.toLowerCase().contains("\nfile1=")) return parsePls(lines, baseUrl, r);
        String title = null;
        long dur = 0;
        for (String raw : lines) {
            String l = raw.trim();
            if (l.isEmpty()) continue;
            if (l.startsWith("#")) {
                if (l.regionMatches(true, 0, "#PLAYLIST:", 0, 10)) r.name = l.substring(10).trim();
                else if (l.regionMatches(true, 0, "#EXTINF:", 0, 8)) {
                    String rest = l.substring(8);
                    int comma = commaOutsideQuotes(rest);
                    String head = comma >= 0 ? rest.substring(0, comma) : rest;
                    title = comma >= 0 ? rest.substring(comma + 1).trim() : null;
                    try {
                        dur = (long) Double.parseDouble(head.trim().split("\\s+")[0]);
                    } catch (Exception e) {
                        dur = 0;
                    }
                    // tvg-name / title attributes are used when the text after the comma is empty
                    if (title == null || title.isEmpty()) title = attr(head, "tvg-name");
                }
                continue;
            }
            String u = absolute(l, baseUrl);
            if (u == null) {
                r.skipped++;
                title = null;
                continue;
            }
            Entry e = new Entry();
            e.url = u;
            e.title = title == null || title.isEmpty() ? null : title;
            e.durSec = Math.max(0, dur);
            r.items.add(e);
            title = null;
            dur = 0;
        }
        return r;
    }

    private static Result parsePls(String[] lines, String base, Result r) {
        java.util.TreeMap<Integer, Entry> m = new java.util.TreeMap<>();
        for (String raw : lines) {
            String l = raw.trim();
            int eq = l.indexOf('=');
            if (eq < 0) continue;
            String k = l.substring(0, eq).toLowerCase(), v = l.substring(eq + 1).trim();
            try {
                if (k.startsWith("file")) {
                    int n = Integer.parseInt(k.substring(4));
                    String u = absolute(v, base);
                    if (u == null) {
                        r.skipped++;
                        continue;
                    }
                    Entry e = m.get(n);
                    if (e == null) m.put(n, e = new Entry());
                    e.url = u;
                } else if (k.startsWith("title")) {
                    int n = Integer.parseInt(k.substring(5));
                    Entry e = m.get(n);
                    if (e == null) m.put(n, e = new Entry());
                    e.title = v;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        for (Entry e : m.values()) if (e.url != null) r.items.add(e);
        return r;
    }

    /** The comma that ends the #EXTINF attributes (commas inside "quotes", e.g. in group-title, don't count). */
    private static int commaOutsideQuotes(String s) {
        boolean q = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') q = !q;
            else if (c == ',' && !q) return i;
        }
        return -1;
    }

    /** Page links of GitLab / GitHub (…/blob/…) → the raw file, so pasting the browser address works too. */
    static String rawLink(String u) {
        if (u.contains("/-/blob/")) return u.replace("/-/blob/", "/-/raw/");
        if (u.matches("(?i)^https?://(www\\.)?github\\.com/[^/]+/[^/]+/blob/.*")) return u.replaceFirst("/blob/", "/raw/");
        return u;
    }

    private static String attr(String head, String key) {
        int i = head.indexOf(key + "=\"");
        if (i < 0) return null;
        int s = i + key.length() + 2, e = head.indexOf('"', s);
        return e > s ? head.substring(s, e) : null;
    }

    /** http(s) link, resolved against the playlist's own address when relative. Anything else → null. */
    private static String absolute(String l, String base) {
        String lo = l.toLowerCase();
        if (lo.startsWith("http://") || lo.startsWith("https://")) return l;
        if (lo.startsWith("file:") || lo.startsWith("content:") || l.startsWith("/") && !l.startsWith("//") && base == null) return null;
        if (lo.matches("^[a-z][a-z0-9+.-]*:.*") || lo.matches("^[a-z]:[\\\\/].*")) return null;   // rtsp:, C:\ …
        try {
            return new URL(new URL(base), l).toString();
        } catch (Exception e) {
            return null;
        }
    }
}
