package com.mytv.player;

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
 * Seedr v0.1/p REST API — works with Personal Access Tokens (PAT).
 *
 * Auth: Authorization: Bearer YOUR_PAT   (permanent — no 6-hour expiry)
 * Base: https://www.seedr.cc/api/v0.1/p
 *
 * PAT scopes needed: torrent.add + torrent.view + folder.view + file.view + file.delete
 * → "Full Control" scope covers all of these.
 */
public final class SeedrApi {
    private SeedrApi() {}

    private static final String BASE    = "https://www.seedr.cc/api/v0.1/p";
    private static final String KEY_PAT = "seedr_pat";
    private static final int    TIMEOUT = 25_000;

    // ------------------------------------------------------------------ token

    public static void  savePat(String t) { Store.putStr(KEY_PAT, t.trim()); }
    public static String pat()            { return Store.getStr(KEY_PAT, ""); }
    public static boolean hasToken()      { return !pat().isEmpty(); }
    public static void  clearAll()        { Store.putStr(KEY_PAT, ""); }

    // ------------------------------------------------------------------ models

    public static class SeedrFile {
        public String  id, name, ext, url, progress, progressUrl;
        public long    size, dlRate;
        public int     seeders, peers;
        public boolean isFolder, isTorrent;

        public String displaySize() {
            if (size <= 0) return "";
            if (size > 1_073_741_824L) return String.format(Locale.US, "%.1f GB", size / 1_073_741_824.0);
            if (size >     1_048_576L) return String.format(Locale.US, "%.1f MB", size /     1_048_576.0);
            return String.format(Locale.US, "%.0f KB", size / 1024.0);
        }

        public String subtitle() {
            if (isTorrent) {
                String s = (progress == null ? "0" : progress) + "%";
                if (seeders > 0) s += "  " + seeders + " seeds";
                return s;
            }
            if (isFolder) return "Folder" + (size > 0 ? "  " + displaySize() : "");
            return (ext == null || ext.isEmpty() ? "" : ext.toUpperCase(Locale.US) + "  ") + displaySize();
        }
    }

    public static class AccountInfo {
        public String username, email;
        public long   spaceUsed, spaceTotal;
        public String quota() {
            if (spaceTotal <= 0) return "";
            return fmt(spaceUsed) + " / " + fmt(spaceTotal);
        }
        static String fmt(long b) {
            if (b > 1_073_741_824L) return String.format(Locale.US, "%.1f GB", b / 1_073_741_824.0);
            return String.format(Locale.US, "%.0f MB", b / 1_048_576.0);
        }
    }

    public static class AddResult {
        public boolean ok;
        public String  msg, torrentId, progressUrl;
    }

    // ------------------------------------------------------------------ API calls

    /** GET /user → account info + storage */
    public static AccountInfo getAccount() throws Exception {
        JSONObject r = get("/user");
        AccountInfo a = new AccountInfo();
        // v0.1/p returns fields directly in the root object
        a.username   = r.optString("username",  r.optString("name", r.optString("display_name", "")));
        a.email      = r.optString("email", "");
        a.spaceUsed  = r.optLong("storage_used",  r.optLong("bandwidth_used",  r.optLong("space_used",  0)));
        a.spaceTotal = r.optLong("storage_max",   r.optLong("bandwidth_max",   r.optLong("space_max",   0)));
        // some responses nest under "account" or "memory"
        if (a.username.isEmpty()) {
            JSONObject acc = r.optJSONObject("account");
            if (acc != null) {
                a.username = acc.optString("username", acc.optString("name", ""));
                a.email    = acc.optString("email", "");
            }
        }
        if (a.spaceTotal == 0) {
            JSONObject mem = r.optJSONObject("memory");
            if (mem != null) {
                a.spaceUsed  = mem.optLong("bandwidth_used", mem.optLong("space_used",  0));
                a.spaceTotal = mem.optLong("bandwidth_max",  mem.optLong("space_max",   0));
            }
        }
        return a;
    }

    /** GET /folder/{id} → list files, folders, active torrents */
    public static List<SeedrFile> listFolder(String folderId) throws Exception {
        String path = (folderId == null || folderId.equals("0")) ? "/folder" : "/folder/" + folderId;
        JSONObject r = get(path);
        return parseContents(r);
    }

    /** POST /torrent  body: {magnet:"magnet:..."} */
    public static AddResult addMagnet(String magnet) throws Exception {
        JSONObject body = new JSONObject().put("magnet", magnet);
        JSONObject r = post("/torrent", body);
        AddResult res = new AddResult();
        // success: {id, name, ...}  failure: {error: "..."}
        res.ok          = !r.has("error") && (r.has("id") || r.has("torrent_id") || r.optBoolean("result", false));
        res.torrentId   = r.optString("id",   r.optString("torrent_id",  ""));
        res.progressUrl = r.optString("progress_url", "");
        res.msg         = r.optString("error", res.ok ? "Added to Seedr" : "Unknown error — check token scope (needs Full Control)");
        return res;
    }

    /** GET /file/{id} → {url: "https://..."} */
    public static String fetchFileUrl(String fileId) throws Exception {
        JSONObject r = get("/file/" + fileId);
        String url = r.optString("url", r.optString("download_url", r.optString("link", "")));
        if (url.isEmpty()) throw new Exception("No URL returned for file " + fileId);
        return url;
    }

    /** DELETE /file/{id} */
    public static void deleteFile(String id)    throws Exception { delete("/file/"    + id); }
    /** DELETE /folder/{id} */
    public static void deleteFolder(String id)  throws Exception { delete("/folder/"  + id); }
    /** DELETE /torrent/{id} */
    public static void deleteTorrent(String id) throws Exception { delete("/torrent/" + id); }

    /** PATCH /file/{id}   body: {name:"..."} */
    public static void renameFile(String id, String name) throws Exception {
        patch("/file/" + id, new JSONObject().put("name", name));
    }

    /** PATCH /folder/{id}  body: {name:"..."} */
    public static void renameFolder(String id, String name) throws Exception {
        patch("/folder/" + id, new JSONObject().put("name", name));
    }

    /** GET /search?q={query} */
    public static List<SeedrFile> search(String query) throws Exception {
        JSONObject r = get("/search?q=" + URLEncoder.encode(query, "UTF-8"));
        return parseContents(r);
    }

    // ------------------------------------------------------------------ response parsing

    private static List<SeedrFile> parseContents(JSONObject r) throws Exception {
        List<SeedrFile> out = new ArrayList<>();

        // active torrents
        JSONArray torrents = r.optJSONArray("torrents");
        if (torrents == null) torrents = r.optJSONArray("transferring");
        if (torrents != null) for (int i = 0; i < torrents.length(); i++) {
            JSONObject o = torrents.getJSONObject(i);
            SeedrFile f  = new SeedrFile();
            f.isTorrent   = true;
            f.id          = str(o, "id");
            f.name        = o.optString("name", "Downloading…");
            f.size        = o.optLong("size", 0);
            f.progress    = String.valueOf((int) Math.round(o.optDouble("progress", 0) * 100));
            f.progressUrl = o.optString("progress_url", "");
            f.seeders     = o.optInt("seeders", 0);
            f.peers       = o.optInt("downloading_from", o.optInt("peers", 0));
            f.dlRate      = o.optLong("download_rate", 0);
            out.add(f);
        }

        // folders
        JSONArray folders = r.optJSONArray("folders");
        if (folders != null) for (int i = 0; i < folders.length(); i++) {
            JSONObject o = folders.getJSONObject(i);
            SeedrFile f  = new SeedrFile();
            f.isFolder = true;
            f.id       = str(o, "id");
            f.name     = o.optString("name", "");
            f.size     = o.optLong("size", 0);
            out.add(f);
        }

        // files — try all known field names
        JSONArray files = r.optJSONArray("folder_files");
        if (files == null) files = r.optJSONArray("files");
        if (files == null) files = r.optJSONArray("results");
        if (files != null) for (int i = 0; i < files.length(); i++) {
            JSONObject o = files.getJSONObject(i);
            SeedrFile f  = new SeedrFile();
            f.id   = o.has("folder_file_id") ? str(o,"folder_file_id") : str(o,"id");
            f.name = o.optString("name", "");
            f.ext  = o.optString("ext",  o.optString("extension", ""));
            f.size = o.optLong("size", 0);
            out.add(f);
        }
        return out;
    }

    private static String str(JSONObject o, String key) { return String.valueOf(o.opt(key)); }

    // ------------------------------------------------------------------ HTTP core

    private static JSONObject get(String path) throws Exception {
        return request("GET", path, null);
    }

    private static JSONObject post(String path, JSONObject body) throws Exception {
        return request("POST", path, body);
    }

    private static JSONObject patch(String path, JSONObject body) throws Exception {
        return request("PATCH", path, body);
    }

    private static void delete(String path) throws Exception {
        request("DELETE", path, null);
    }

    private static JSONObject request(String method, String path, JSONObject body) throws Exception {
        String tok = pat();
        if (tok.isEmpty()) throw new Exception("No Seedr token — open ZH Play → Seedr → ⚙ to connect");

        HttpURLConnection c = (HttpURLConnection) new URL(BASE + path).openConnection();
        c.setConnectTimeout(TIMEOUT);
        c.setReadTimeout(TIMEOUT);
        c.setRequestProperty("User-Agent",    "ZHPlay/1.0");
        c.setRequestProperty("Accept",        "application/json");
        c.setRequestProperty("Authorization", "Bearer " + tok);
        c.setRequestMethod(method);

        if (body != null && (method.equals("POST") || method.equals("PATCH") || method.equals("PUT"))) {
            byte[] b = body.toString().getBytes(StandardCharsets.UTF_8);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type",   "application/json");
            c.setRequestProperty("Content-Length",  String.valueOf(b.length));
            try (OutputStream out = c.getOutputStream()) { out.write(b); }
        }

        int code = c.getResponseCode();
        java.io.InputStream in = (code >= 400) ? c.getErrorStream() : c.getInputStream();

        if (in == null) {
            // DELETE 204 No Content = success
            if (code == 204 || code == 200) return new JSONObject().put("ok", true);
            throw new Exception("HTTP " + code);
        }

        BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder(); String ln;
        while ((ln = br.readLine()) != null) sb.append(ln);
        br.close();

        String text = sb.toString().trim();
        if (text.isEmpty()) return new JSONObject().put("ok", code < 400);

        // if we got HTML instead of JSON — wrong endpoint or auth failure
        if (text.startsWith("<")) {
            if (code == 401 || code == 403)
                throw new Exception("Token rejected (HTTP " + code + "). Make sure you selected 'Full Control' scope when creating the token.");
            throw new Exception("Seedr returned HTML instead of JSON (HTTP " + code + "). Check your token.");
        }

        if (text.startsWith("[")) {
            // array response — wrap it
            JSONArray arr = new JSONArray(text);
            JSONObject wrap = new JSONObject();
            // guess what type based on first item
            if (arr.length() > 0) {
                JSONObject first = arr.optJSONObject(0);
                if (first != null && (first.has("folder_file_id") || first.has("ext")))
                    wrap.put("folder_files", arr);
                else if (first != null && first.has("folders"))
                    wrap.put("folders", arr);
                else
                    wrap.put("results", arr);
            }
            return wrap;
        }

        JSONObject j = new JSONObject(text);
        if (code == 401 || code == 403)
            throw new Exception("Token rejected: " + j.optString("error", j.optString("message", "access denied")));
        return j;
    }
}
