package com.mytv.player;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Seedr REST API client for ZH Play.
 *
 * Auth: Personal Access Token (PAT) from seedr.cc/api/v0.1/console/tokens
 *   Scope needed: "Read & Add Torrents" (torrent.add + torrent.view + file.view + folder.view)
 *
 * All calls are synchronous — run them on a background thread.
 *
 * Endpoint: POST https://www.seedr.cc/oauth_test/resource.php
 *   ?access_token=PAT&func=FUNCTION
 *   Body: form-encoded parameters
 */
public final class SeedrApi {
    private SeedrApi() {}

    // v0.1 API (Personal Access Tokens from seedr.cc/api/v0.1/console/tokens) — uses Bearer header
    // Base URL confirmed from official docs: https://www.seedr.cc/api/v0.1/p
    private static final String BASE_V1 = "https://www.seedr.cc/api/v0.1/p";
    // Old OAuth API (password / device login tokens) — uses ?access_token= param
    private static final String BASE_OAUTH = "https://www.seedr.cc/oauth_test/resource.php";

    /** Detect which API this token needs. PAT tokens start with "seedr_" or are very long (>40 chars). */
    private static boolean isPat() {
        String t = token();
        return t.length() > 40 || t.startsWith("seedr_") || t.startsWith("sk_");
    }
    private static final String TOKEN_KEY = "seedr_api_token";
    private static final int TIMEOUT = 20_000;

    // ------------------------------------------------------------------ token
    public static void saveToken(String token) { Store.putStr(TOKEN_KEY, token.trim()); }
    public static String token() { return Store.getStr(TOKEN_KEY, ""); }
    public static boolean hasToken() { return !token().isEmpty(); }
    public static void clearToken() { Store.putStr(TOKEN_KEY, ""); }

    // ------------------------------------------------------------------ models
    public static class SeedrFile {
        public String id, folderId, name, ext, url;
        public long size;
        public boolean isFolder, isTorrent;

        public String displaySize() {
            if (size <= 0) return "";
            if (size > 1_073_741_824L) return String.format(Locale.US, "%.1f GB", size / 1_073_741_824.0);
            if (size > 1_048_576L)     return String.format(Locale.US, "%.1f MB", size / 1_048_576.0);
            return String.format(Locale.US, "%.0f KB", size / 1024.0);
        }
    }

    public static class AddResult {
        public boolean ok;
        public String torrentId, progressUrl, msg;
    }

    public static class AccountInfo {
        public String username, email;
        public long spaceUsed, spaceTotal;

        public String quota() {
            if (spaceTotal <= 0) return "";
            return mb(spaceUsed) + " / " + mb(spaceTotal) + " used";
        }
        private static String mb(long b) {
            if (b > 1_073_741_824L) return String.format(Locale.US, "%.1f GB", b / 1_073_741_824.0);
            return String.format(Locale.US, "%.0f MB", b / 1_048_576.0);
        }
    }

    public static class TorrentProgress {
        public String name;
        public int progress;          // 0–100
        public long dlRate, ulRate;   // bytes/s
        public int seeders, peers;
        public boolean done;
    }

    // ------------------------------------------------------------------ API calls

    /** Verify the token and return account info.
     * v0.1/p/user returns: {username, email, storage_used, storage_max, ...} flat JSON */
    public static AccountInfo getAccount() throws Exception {
        JSONObject r = get("get_settings");
        // v0.1 API returns flat JSON; old OAuth API nests under "account"
        JSONObject src = r.has("username") ? r : r.optJSONObject("account") != null ? r.optJSONObject("account") : r;
        AccountInfo a = new AccountInfo();
        a.username = src.optString("username", src.optString("display_name", src.optString("name", "")));
        a.email    = src.optString("email", "");
        // storage — v0.1 may use storage_used/storage_max or bandwidth_used/bandwidth_max
        a.spaceUsed  = src.optLong("storage_used",  src.optLong("bandwidth_used",  src.optLong("space_used",  0)));
        a.spaceTotal = src.optLong("storage_max",   src.optLong("bandwidth_max",   src.optLong("space_max",   0)));
        if (a.spaceTotal == 0) {
            // try a nested memory object (old API)
            JSONObject mem = r.optJSONObject("memory");
            if (mem != null) {
                a.spaceUsed  = mem.optLong("bandwidth_used", mem.optLong("space_used",  0));
                a.spaceTotal = mem.optLong("bandwidth_max",  mem.optLong("space_max",   0));
            }
        }
        return a;
    }

    /** List files and folders in a folder (root = "0"). */
    public static List<SeedrFile> listFolder(String folderId) throws Exception {
        JSONObject body = new JSONObject().put("content_id", folderId == null ? "0" : folderId);
        JSONObject r = post("list_contents", body);
        List<SeedrFile> out = new ArrayList<>();
        JSONArray folders = r.optJSONArray("folders");
        if (folders != null) for (int i = 0; i < folders.length(); i++) {
            JSONObject o = folders.getJSONObject(i);
            SeedrFile f = new SeedrFile();
            f.id = String.valueOf(o.optInt("id")); f.folderId = folderId;
            f.name = o.optString("name"); f.isFolder = true;
            f.size = o.optLong("size", 0);
            out.add(f);
        }
        JSONArray files = r.optJSONArray("files");
        if (files == null) files = r.optJSONArray("folder_files");
        if (files != null) for (int i = 0; i < files.length(); i++) {
            JSONObject o = files.getJSONObject(i);
            SeedrFile f = new SeedrFile();
            f.id = String.valueOf(o.optInt("folder_file_id", o.optInt("id")));
            f.folderId = folderId;
            f.name = o.optString("name"); f.ext = o.optString("ext");
            f.size = o.optLong("size", 0); f.isFolder = false;
            out.add(f);
        }
        // active downloading torrents
        JSONArray torrents = r.optJSONArray("torrents");
        if (torrents != null) for (int i = 0; i < torrents.length(); i++) {
            JSONObject o = torrents.getJSONObject(i);
            SeedrFile f = new SeedrFile();
            f.id = String.valueOf(o.optInt("id")); f.folderId = folderId;
            f.name = o.optString("name") + "  ⬇ " + o.optString("progress","?") + "%";
            f.isTorrent = true; f.size = o.optLong("size", 0);
            f.url = o.optString("progress_url");  // reuse url field for progress polling
            out.add(f);
        }
        return out;
    }

    /** Get a direct streaming/download URL for a file. */
    public static String fetchFileUrl(String fileId) throws Exception {
        JSONObject body = new JSONObject().put("folder_file_id", fileId);
        JSONObject r = post("fetch_file", body);
        String url = r.optString("url", r.optString("download_url", ""));
        if (url.isEmpty()) throw new Exception("No URL in response");
        return url;
    }

    /** Add a magnet or torrent URL. Returns progress info so you can poll. */
    public static AddResult addMagnet(String magnet) throws Exception {
        JSONObject body = new JSONObject()
                .put("torrent_magnet", magnet)
                .put("folder_id", "-1");
        JSONObject r = post("add_torrent", body);
        AddResult res = new AddResult();
        res.ok = r.optBoolean("result", !r.has("error"));
        res.torrentId   = r.optString("torrent_id",    String.valueOf(r.optInt("id")));
        res.progressUrl = r.optString("progress_url",  "");
        res.msg = r.optString("error", r.optBoolean("result", false) ? "Added to Seedr" : "Failed");
        return res;
    }

    /** Poll download progress for an active torrent. */
    public static TorrentProgress getProgress(String progressUrl) throws Exception {
        JSONObject r = getUrl(progressUrl);
        TorrentProgress p = new TorrentProgress();
        p.name     = r.optString("name", "");
        p.progress = (int) Math.round(r.optDouble("progress", 0) * 100);
        p.dlRate   = r.optLong("download_rate", 0);
        p.ulRate   = r.optLong("upload_rate", 0);
        p.seeders  = r.optInt("seeders", 0);
        p.peers    = r.optInt("downloading_from", r.optInt("peers", 0));
        p.done     = p.progress >= 100 || "Finished".equalsIgnoreCase(r.optString("status"));
        return p;
    }

    /** Delete a completed folder or file. */
    public static void deleteFolder(String folderId) throws Exception {
        post("delete_folder", new JSONObject().put("folder_id", folderId));
    }

    public static void deleteFile(String fileId) throws Exception {
        post("delete_file", new JSONObject().put("folder_file_id", fileId));
    }

    /** Delete an active downloading torrent. */
    public static void deleteTorrent(String torrentId) throws Exception {
        post("delete_torrent", new JSONObject().put("torrent_id", torrentId));
    }

    /** Rename a file. */
    public static void renameFile(String fileId, String name) throws Exception {
        post("rename_file", new JSONObject().put("folder_file_id", fileId).put("rename_to", name));
    }

    /** Rename a folder. */
    public static void renameFolder(String folderId, String name) throws Exception {
        post("rename_folder", new JSONObject().put("folder_id", folderId).put("rename_to", name));
    }

    /** Search for files across the account. */
    public static List<SeedrFile> search(String query) throws Exception {
        JSONObject r = post("search_files", new JSONObject().put("search_query", query));
        List<SeedrFile> out = new ArrayList<>();
        JSONArray files = r.optJSONArray("files");
        if (files == null) files = r.optJSONArray("folder_files");
        if (files != null) for (int i = 0; i < files.length(); i++) {
            JSONObject o = files.getJSONObject(i);
            SeedrFile f = new SeedrFile();
            f.id = String.valueOf(o.optInt("folder_file_id", o.optInt("id")));
            f.name = o.optString("name"); f.ext = o.optString("ext");
            f.size = o.optLong("size", 0);
            out.add(f);
        }
        return out;
    }

    // ------------------------------------------------------------------ HTTP

    private static JSONObject get(String func) throws Exception {
        return call("GET", func, null);
    }

    private static JSONObject post(String func, JSONObject body) throws Exception {
        return call("POST", func, body);
    }

    private static JSONObject getUrl(String url) throws Exception {
        HttpURLConnection c = open(url + "?access_token=" + URLEncoder.encode(token(), "UTF-8"));
        c.setRequestMethod("GET");
        return readJson(c);
    }

    private static JSONObject call(String method, String func, JSONObject body) throws Exception {
        String tok = token();
        if (tok.isEmpty()) throw new Exception("No Seedr API token. Go to ZH Play → Seedr → ⚙ → Change API Token");
        return isPat() ? callV1(method, func, body, tok) : callOAuth(method, func, body, tok);
    }

    /** v0.1 REST API: Authorization: Bearer TOKEN, path-based routing. */
    private static JSONObject callV1(String method, String func, JSONObject body, String tok) throws Exception {
        // map the old func names to v0.1 REST paths
        String path;
        switch (func) {
            case "get_settings":        path = "/user"; method = "GET"; break;
            case "get_memory_bandwidth":path = "/user"; method = "GET"; break;
            case "list_contents":       path = "/folder/" + (body != null ? body.optString("content_id","0") : "0"); method = "GET"; body = null; break;
            case "add_torrent":         path = "/torrent"; method = "POST"; break;
            case "fetch_file":          path = "/file/" + (body != null ? body.optString("folder_file_id","0") + "/url" : "0/url"); method = "GET"; body = null; break;
            case "delete_file":         path = "/file/" + (body != null ? body.optString("folder_file_id","0") : "0"); method = "DELETE"; body = null; break;
            case "delete_folder":       path = "/folder/" + (body != null ? body.optString("folder_id","0") : "0"); method = "DELETE"; body = null; break;
            case "delete_torrent":      path = "/torrent/" + (body != null ? body.optString("torrent_id","0") : "0"); method = "DELETE"; body = null; break;
            case "rename_file":         path = "/file/" + (body != null ? body.optString("folder_file_id","0") : "0"); method = "PATCH"; break;
            case "rename_folder":       path = "/folder/" + (body != null ? body.optString("folder_id","0") : "0"); method = "PATCH"; break;
            case "search_files":        path = "/search?query=" + URLEncoder.encode(body != null ? body.optString("search_query","") : "", "UTF-8"); method = "GET"; body = null; break;
            default:                    path = "/" + func; break;
        }
        HttpURLConnection c = open(BASE_V1 + path);
        c.setRequestMethod(method);
        c.setRequestProperty("Authorization", "Bearer " + tok);
        c.setRequestProperty("Accept", "application/json");
        if (body != null && body.length() > 0 && (method.equals("POST") || method.equals("PATCH"))) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            byte[] b = body.toString().getBytes(StandardCharsets.UTF_8);
            c.setRequestProperty("Content-Length", String.valueOf(b.length));
            try (OutputStream out = c.getOutputStream()) { out.write(b); }
        }
        return readJson(c);
    }

    /** Old OAuth API: ?access_token=TOKEN&func=FUNC, form body. */
    private static JSONObject callOAuth(String method, String func, JSONObject body, String tok) throws Exception {
        String url = BASE_OAUTH + "?access_token=" + URLEncoder.encode(tok, "UTF-8")
                         + "&func=" + URLEncoder.encode(func, "UTF-8");
        HttpURLConnection c = open(url);
        c.setRequestMethod(method);
        if (body != null && body.length() > 0) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            StringBuilder sb = new StringBuilder();
            java.util.Iterator<String> it = body.keys();
            while (it.hasNext()) {
                String k = it.next(); Object v = body.get(k);
                if (sb.length() > 0) sb.append('&');
                sb.append(URLEncoder.encode(k, "UTF-8")).append('=')
                  .append(URLEncoder.encode(String.valueOf(v), "UTF-8"));
            }
            byte[] b = sb.toString().getBytes(StandardCharsets.UTF_8);
            c.setRequestProperty("Content-Length", String.valueOf(b.length));
            try (OutputStream out = c.getOutputStream()) { out.write(b); }
        }
        return readJson(c);
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT); c.setReadTimeout(TIMEOUT);
        c.setRequestProperty("User-Agent", "ZHPlay/1.0");
        return c;
    }

    private static JSONObject readJson(HttpURLConnection c) throws Exception {
        int code = c.getResponseCode();
        java.io.InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) throw new Exception("HTTP " + code);
        BufferedReader r = new BufferedReader(new InputStreamReader(in));
        StringBuilder sb = new StringBuilder(); String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        String text = sb.toString().trim();
        if (text.isEmpty()) return new JSONObject();
        if (text.startsWith("[")) return new JSONObject().put("array", new JSONArray(text));
        JSONObject j = new JSONObject(text);
        if (j.has("error") && !j.optString("error").isEmpty())
            throw new Exception(j.optString("error"));
        return j;
    }
}
