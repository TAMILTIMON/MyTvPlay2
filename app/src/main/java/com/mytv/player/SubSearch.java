package com.mytv.player;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Finds subtitles online by movie / episode name.
 * OpenSubtitles.com (free API key from opensubtitles.com → API consumers) + Podnapisi (no key).
 */
public final class SubSearch {
    private SubSearch() {
    }

    public static final class Hit {
        public String name, lang, source;
        String fileId, url;
    }

    public static volatile List<Hit> last = new ArrayList<>();

    public static String langs() {
        return Store.getStr("sub_langs", "en,ta,hi");
    }

    /** "Movie.Name.2021.1080p.WEB.x264-GRP.mkv" -> "Movie Name 2021" */
    public static String cleanTitle(String t) {
        String s = t.replaceAll("(?i)\\.(mkv|mp4|avi|mov|wmv|ts|m4v|webm)$", "");
        s = s.replaceAll("[\\[\\(][^\\]\\)]*[\\]\\)]", " ").replaceAll("[._]+", " ");
        java.util.regex.Matcher se = java.util.regex.Pattern.compile("(?i)\\bS\\d{1,2}E\\d{1,2}\\b").matcher(s);
        if (se.find()) return s.substring(0, se.end()).trim();
        java.util.regex.Matcher y = java.util.regex.Pattern.compile("\\b(19|20)\\d{2}\\b").matcher(s);
        if (y.find() && y.start() > 0) return s.substring(0, y.end()).trim();
        s = s.replaceAll("(?i)\\b(2160p|1080p|720p|480p|x264|x265|hevc|h264|web-?dl|webrip|bluray|brrip|hdrip|dvdrip|aac|ddp?5\\.1|esub).*$", "");
        return s.replaceAll("\\s+", " ").trim();
    }

    private static String get(String url, String key) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        c.setRequestProperty("User-Agent", "TamilCastPlay v2");
        c.setRequestProperty("Accept", "application/json");
        if (key != null) c.setRequestProperty("Api-Key", key);
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        StringBuilder sb = new StringBuilder();
        if (in != null) {
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) sb.append(new String(b, 0, n, "UTF-8"));
            in.close();
        }
        if (code >= 400) throw new Exception("Subtitle site answered " + code);
        return sb.toString();
    }

    public static List<Hit> search(String query) throws Exception {
        List<Hit> out = new ArrayList<>();
        String q = URLEncoder.encode(query, "UTF-8");
        String key = Store.getStr("os_key", "").trim();
        String err = null;
        if (!key.isEmpty()) {
            try {
                JSONObject o = new JSONObject(get("https://api.opensubtitles.com/api/v1/subtitles?query=" + q
                        + "&languages=" + URLEncoder.encode(langs(), "UTF-8"), key));
                JSONArray d = o.optJSONArray("data");
                if (d != null) for (int i = 0; i < d.length() && out.size() < 40; i++) {
                    JSONObject at = d.getJSONObject(i).getJSONObject("attributes");
                    JSONArray files = at.optJSONArray("files");
                    if (files == null || files.length() == 0) continue;
                    Hit h = new Hit();
                    h.source = "OpenSubtitles";
                    h.lang = at.optString("language");
                    h.name = at.optString("release", files.getJSONObject(0).optString("file_name"));
                    h.fileId = String.valueOf(files.getJSONObject(0).optLong("file_id"));
                    out.add(h);
                }
            } catch (Exception e) {
                err = e.getMessage();
            }
        }
        // no-key source
        for (String lang : langs().split(",")) {
            lang = lang.trim();
            if (lang.isEmpty()) continue;
            try {
                JSONObject o = new JSONObject(get("https://www.podnapisi.net/subtitles/search/advanced?keywords=" + q
                        + "&language=" + lang, null));
                JSONArray d = o.optJSONArray("data");
                if (d != null) for (int i = 0; i < d.length() && i < 15; i++) {
                    JSONObject x = d.getJSONObject(i);
                    Hit h = new Hit();
                    h.source = "Podnapisi";
                    h.lang = x.optString("language", lang);
                    JSONArray rel = x.optJSONArray("custom_releases");
                    JSONObject mv = x.optJSONObject("movie");
                    h.name = rel != null && rel.length() > 0 ? rel.optString(0) : mv != null ? mv.optString("title") : query;
                    h.url = "https://www.podnapisi.net/subtitles/" + x.optString("id") + "/download";
                    out.add(h);
                }
            } catch (Exception e) {
                if (err == null) err = e.getMessage();
            }
        }
        if (out.isEmpty() && err != null && key.isEmpty()) throw new Exception("No subtitles found. Tip: add a free OpenSubtitles API key in Subtitles settings.");
        last = out;
        return out;
    }

    /** Downloads one result and returns the subtitle file (zip / gz unpacked). */
    public static File download(Context c, Hit h) throws Exception {
        if (h.fileId != null) {
            HttpURLConnection con = (HttpURLConnection) new URL("https://api.opensubtitles.com/api/v1/download").openConnection();
            con.setDoOutput(true);
            con.setRequestMethod("POST");
            con.setRequestProperty("Api-Key", Store.getStr("os_key", "").trim());
            con.setRequestProperty("User-Agent", "TamilCastPlay v2");
            con.setRequestProperty("Content-Type", "application/json");
            con.setRequestProperty("Accept", "application/json");
            try (OutputStream o = con.getOutputStream()) {
                o.write(("{\"file_id\":" + h.fileId + "}").getBytes("UTF-8"));
            }
            int code = con.getResponseCode();
            InputStream in = code >= 400 ? con.getErrorStream() : con.getInputStream();
            StringBuilder sb = new StringBuilder();
            byte[] b = new byte[8192];
            int n;
            while (in != null && (n = in.read(b)) > 0) sb.append(new String(b, 0, n, "UTF-8"));
            JSONObject r = new JSONObject(sb.toString());
            if (!r.has("link")) throw new Exception(r.optString("message", "Download limit reached"));
            return Net.downloadSubtitle(c, r.getString("link"));
        }
        return Net.downloadSubtitle(c, h.url);
    }

    public static String label(Hit h) {
        return "[" + h.lang.toUpperCase(Locale.ROOT) + "] " + h.name + "  · " + h.source;
    }
}
