package com.mytv.player;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/** Poster + info for a movie file: TMDB (free key) or, without a key, a picture from the video itself. */
public final class MovieInfo {
    private MovieInfo() {
    }

    public static String year(String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b(19|20)\\d{2}\\b").matcher(name);
        return m.find() ? m.group() : "";
    }

    public static String title(String name) {
        String t = SubSearch.cleanTitle(name);
        String y = year(t);
        if (!y.isEmpty()) t = t.replace(y, "").trim();
        return t.replaceAll("(?i)\\bS\\d{1,2}E\\d{1,2}\\b", "").trim();
    }

    public static JSONObject cached(String url) {
        try {
            String s = Store.getStr("mi_" + Thumbs.key(url), "");
            return s.isEmpty() ? null : new JSONObject(s);
        } catch (Exception e) {
            return null;
        }
    }

    /** Looks the movie up (once) and caches the answer. Background thread. */
    public static JSONObject get(String url, String name) {
        JSONObject c = cached(url);
        if (c != null) return c;
        JSONObject out = new JSONObject();
        try {
            out.put("title", title(name)).put("year", year(name));
            String key = Store.getStr("tmdb_key", "").trim();
            if (!key.isEmpty()) {
                String q = URLEncoder.encode(title(name), "UTF-8");
                String y = year(name);
                boolean bearer = key.length() > 40;
                HttpURLConnection con = (HttpURLConnection) new URL("https://api.themoviedb.org/3/search/multi?query=" + q
                        + (bearer ? "" : "&api_key=" + key) + (y.isEmpty() ? "" : "&year=" + y)).openConnection();
                if (bearer) con.setRequestProperty("Authorization", "Bearer " + key);
                con.setConnectTimeout(8000);
                con.setReadTimeout(10000);
                StringBuilder sb = new StringBuilder();
                try (InputStream in = con.getInputStream()) {
                    byte[] b = new byte[16384];
                    int n;
                    while ((n = in.read(b)) > 0) sb.append(new String(b, 0, n, "UTF-8"));
                }
                JSONArray res = new JSONObject(sb.toString()).optJSONArray("results");
                if (res != null && res.length() > 0) {
                    JSONObject r = res.getJSONObject(0);
                    out.put("title", r.optString("title", r.optString("name", title(name))));
                    String date = r.optString("release_date", r.optString("first_air_date", ""));
                    if (date.length() >= 4) out.put("year", date.substring(0, 4));
                    out.put("overview", r.optString("overview"));
                    out.put("rating", r.optDouble("vote_average", 0));
                    if (!r.optString("poster_path").isEmpty()) out.put("poster", "https://image.tmdb.org/t/p/w342" + r.optString("poster_path"));
                }
            }
            Store.putStr("mi_" + Thumbs.key(url), out.toString());
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Poster file (downloaded from TMDB, or a frame from the video). Background thread. */
    public static File poster(Context c, String url, String name) {
        File d = new File(c.getFilesDir(), "posters");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        File f = new File(d, Thumbs.key(url) + ".jpg");
        if (f.exists() && f.length() > 0) return f;
        JSONObject info = get(url, name);
        try {
            if (!info.optString("poster").isEmpty()) {
                byte[] b = Net.fetch(info.optString("poster"), 3_000_000).data;
                try (FileOutputStream o = new FileOutputStream(f)) {
                    o.write(b);
                }
                return f;
            }
        } catch (Exception ignored) {
        }
        if (!url.startsWith("/")) return null;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(url);
            long len = 0;
            try {
                len = Long.parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            } catch (Exception ignored) {
            }
            Bitmap b = r.getFrameAtTime(Math.max(60000, len / 10) * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (b == null) return null;
            // portrait crop from the middle of the frame
            int h = b.getHeight(), w = Math.min(b.getWidth(), h * 2 / 3);
            Bitmap crop = Bitmap.createBitmap(b, (b.getWidth() - w) / 2, 0, w, h);
            Bitmap s = Bitmap.createScaledBitmap(crop, 300, 450, true);
            try (FileOutputStream o = new FileOutputStream(f)) {
                s.compress(Bitmap.CompressFormat.JPEG, 85, o);
            }
            return f;
        } catch (Throwable e) {
            return null;
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
    }
}
