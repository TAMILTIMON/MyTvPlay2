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
 * Seedr API — works with BOTH token types:
 *   • Personal Access Token (PAT) from seedr.cc/api/v0.1/console/tokens
 *     → converted to an OAuth access_token on first use via the device-auth flow
 *   • Old OAuth token (from password / device login)
 *
 * All calls use the proven resource.php endpoint which has stable, documented behaviour.
 */
public final class SeedrApi {
    private SeedrApi() {}

    // endpoints
    private static final String RESOURCE = "https://www.seedr.cc/oauth_test/resource.php";
    private static final String TOKEN_URL = "https://www.seedr.cc/oauth_test/token.php";
    private static final String CLIENT_ID = "seedr_chrome";

    // storage keys
    private static final String KEY_TOKEN   = "seedr_oauth_token";   // working OAuth token
    private static final String KEY_REFRESH = "seedr_refresh_token"; // refresh token
    private static final String KEY_PAT     = "seedr_pat";           // original PAT (for display)
    private static final int    TIMEOUT     = 25_000;

    // ------------------------------------------------------------------
    // Token management
    // ------------------------------------------------------------------

    public static boolean hasToken()           { return !Store.getStr(KEY_TOKEN, "").isEmpty(); }
    public static boolean hasPat()             { return !Store.getStr(KEY_PAT,   "").isEmpty(); }
    public static String  pat()                { return Store.getStr(KEY_PAT,   ""); }
    public static void    clearAll()           { Store.putStr(KEY_TOKEN,""); Store.putStr(KEY_REFRESH,""); Store.putStr(KEY_PAT,""); }

    /** Save an OAuth access_token + optional refresh_token (from password or device login). */
    public static void saveOAuth(String access, String refresh) {
        Store.putStr(KEY_TOKEN,   access  == null ? "" : access.trim());
        Store.putStr(KEY_REFRESH, refresh == null ? "" : refresh.trim());
    }

    /** Save a PAT; it will be exchanged for an OAuth token on first API call. */
    public static void savePat(String pat) {
        Store.putStr(KEY_PAT, pat.trim());
    }

    /**
     * Exchange the saved PAT for an OAuth access_token using the password grant
     * (PATs are treated as passwords with username "pat" by Seedr's auth server).
     * Stores the resulting token and returns it.
     */
    static String exchangePat() throws Exception {
        String pat = Store.getStr(KEY_PAT, "");
        if (pat.isEmpty()) throw new Exception("No PAT saved");

        // Try password grant with email="" password=PAT — this is how Seedr's own extension uses PATs
        JSONObject body = new JSONObject()
                .put("grant_type", "password")
                .put("client_id", CLIENT_ID)
                .put("username", "")
                .put("password", pat)
                .put("type", "login");

        HttpURLConnection c = open(TOKEN_URL);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        StringBuilder sb = new StringBuilder();
        java.util.Iterator<String> it = body.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(k,"UTF-8")).append('=')
              .append(URLEncoder.encode(body.getString(k),"UTF-8"));
        }
        byte[] b = sb.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream o = c.getOutputStream()) { o.write(b); }
        JSONObject r = readJson(c);
        String token = r.optString("access_token","");
        if (token.isEmpty()) {
            // PAT might itself be a valid OAuth token — try using it directly
            Store.putStr(KEY_TOKEN, pat);
            return pat;
        }
        saveOAuth(token, r.optString("refresh_token",""));
        return token;
    }

    /** Get a working OAuth token, auto-exchanging PAT if needed. */
    private static String token() throws Exception {
        String t = Store.getStr(KEY_TOKEN,"");
        if (!t.isEmpty()) return t;
        // no OAuth token yet — try to exchange the PAT
        if (!Store.getStr(KEY_PAT,"").isEmpty()) return exchangePat();
        throw new Exception("Not connected to Seedr. Go to ZH Play → Seedr → ⚙ to connect.");
    }

    /** Refresh the OAuth token using the refresh_token. */
    private static String refresh() throws Exception {
        String rt = Store.getStr(KEY_REFRESH,"");
        if (rt.isEmpty()) {
            // no refresh token — re-exchange the PAT
            Store.putStr(KEY_TOKEN,"");
            return exchangePat();
        }
        JSONObject body = new JSONObject()
                .put("grant_type","refresh_token")
                .put("client_id", CLIENT_ID)
                .put("refresh_token", rt);
        HttpURLConnection c = open(TOKEN_URL);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type","application/x-www-form-urlencoded");
        StringBuilder sb = new StringBuilder();
        java.util.Iterator<String> it = body.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(k,"UTF-8")).append('=')
              .append(URLEncoder.encode(body.getString(k),"UTF-8"));
        }
        byte[] b2 = sb.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream o = c.getOutputStream()) { o.write(b2); }
        JSONObject r = readJson(c);
        String newTok = r.optString("access_token","");
        if (!newTok.isEmpty()) { saveOAuth(newTok, r.optString("refresh_token","")); return newTok; }
        // refresh failed — clear and re-exchange PAT
        Store.putStr(KEY_TOKEN,""); Store.putStr(KEY_REFRESH,"");
        return exchangePat();
    }

    // ------------------------------------------------------------------
    // Models
    // ------------------------------------------------------------------

    public static class SeedrFile {
        public String id, name, ext, url;
        public long   size;
        public boolean isFolder, isTorrent;
        public String progress;   // for active torrents
        public String progressUrl;
        public int    seeders, peers;
        public long   dlRate;

        public String displaySize() {
            if (size <= 0) return "";
            if (size > 1_073_741_824L) return String.format(Locale.US,"%.1f GB", size/1_073_741_824.0);
            if (size >     1_048_576L) return String.format(Locale.US,"%.1f MB", size/1_048_576.0);
            return String.format(Locale.US,"%.0f KB", size/1024.0);
        }

        public String subtitle() {
            if (isTorrent) return (progress==null?"":progress+"% ") + (seeders>0?seeders+" seeds":"");
            if (isFolder)  return displaySize().isEmpty() ? "Folder" : displaySize();
            return (ext==null||ext.isEmpty()?"":ext.toUpperCase(Locale.US)+" · ") + displaySize();
        }
    }

    public static class AddResult  { public boolean ok; public String msg, progressUrl, torrentId; }
    public static class AccountInfo {
        public String username, email;
        public long   spaceUsed, spaceTotal;
        public String quota() {
            if (spaceTotal<=0) return "";
            return fmt(spaceUsed)+" / "+fmt(spaceTotal);
        }
        private static String fmt(long b){
            if(b>1_073_741_824L) return String.format(Locale.US,"%.1f GB",b/1_073_741_824.0);
            return String.format(Locale.US,"%.0f MB",b/1_048_576.0);
        }
    }

    // ------------------------------------------------------------------
    // API calls
    // ------------------------------------------------------------------

    public static AccountInfo getAccount() throws Exception {
        JSONObject r = call("get_settings", null);
        AccountInfo a = new AccountInfo();
        JSONObject acc = r.optJSONObject("account");
        if (acc == null) acc = r;
        a.username  = acc.optString("username",  acc.optString("name",""));
        a.email     = acc.optString("email","");
        // storage — try several field names the API uses across versions
        a.spaceUsed  = r.optLong("bandwidth_used",  r.optLong("space_used",  0));
        a.spaceTotal = r.optLong("bandwidth_max",   r.optLong("space_max",   0));
        if (a.spaceTotal == 0) {
            JSONObject mem = r.optJSONObject("memory");
            if (mem != null) {
                a.spaceUsed  = mem.optLong("bandwidth_used", mem.optLong("space_used", 0));
                a.spaceTotal = mem.optLong("bandwidth_max",  mem.optLong("space_max",  0));
            }
        }
        if (a.spaceTotal == 0) {
            // separate memory call
            try {
                JSONObject m2 = call("get_memory_bandwidth", null);
                JSONObject mem = m2.optJSONObject("memory");
                if (mem!=null){
                    a.spaceUsed  = mem.optLong("bandwidth_used", mem.optLong("space_used", 0));
                    a.spaceTotal = mem.optLong("bandwidth_max",  mem.optLong("space_max",  0));
                }
            } catch (Exception ignored){}
        }
        return a;
    }

    public static List<SeedrFile> listFolder(String folderId) throws Exception {
        JSONObject body = new JSONObject().put("content_id", folderId == null ? "0" : folderId);
        JSONObject r = call("list_contents", body);
        List<SeedrFile> out = new ArrayList<>();

        // active torrents (still downloading)
        JSONArray torrents = r.optJSONArray("torrents");
        if (torrents != null) for (int i = 0; i < torrents.length(); i++) {
            JSONObject o = torrents.getJSONObject(i);
            SeedrFile f = new SeedrFile();
            f.id          = String.valueOf(o.optInt("id"));
            f.name        = o.optString("name","Torrent");
            f.isTorrent   = true;
            f.size        = o.optLong("size",0);
            f.progress    = o.optString("progress","0");
            f.progressUrl = o.optString("progress_url","");
            f.seeders     = o.optInt("seeders",0);
            f.peers       = o.optInt("downloading_from",0);
            f.dlRate      = o.optLong("download_rate",0);
            out.add(f);
        }

        // completed folders
        JSONArray folders = r.optJSONArray("folders");
        if (folders != null) for (int i = 0; i < folders.length(); i++) {
            JSONObject o = folders.getJSONObject(i);
            SeedrFile f = new SeedrFile();
            f.id       = String.valueOf(o.optInt("id"));
            f.name     = o.optString("name","");
            f.isFolder = true;
            f.size     = o.optLong("size",0);
            out.add(f);
        }

        // completed files
        JSONArray files = r.optJSONArray("folder_files");
        if (files == null) files = r.optJSONArray("files");
        if (files != null) for (int i = 0; i < files.length(); i++) {
            JSONObject o = files.getJSONObject(i);
            SeedrFile f = new SeedrFile();
            f.id   = String.valueOf(o.optInt("folder_file_id", o.optInt("id")));
            f.name = o.optString("name","");
            f.ext  = o.optString("ext","");
            f.size = o.optLong("size",0);
            out.add(f);
        }
        return out;
    }

    public static String fetchFileUrl(String fileId) throws Exception {
        JSONObject r = call("fetch_file", new JSONObject().put("folder_file_id", fileId));
        String u = r.optString("url", r.optString("download_url",""));
        if (u.isEmpty()) throw new Exception("No streaming URL in response");
        return u;
    }

    public static AddResult addMagnet(String magnet) throws Exception {
        JSONObject body = new JSONObject()
                .put("torrent_magnet", magnet)
                .put("folder_id", "-1");
        JSONObject r = call("add_torrent", body);
        AddResult res = new AddResult();
        // result = true means queued; result = false with error means failed
        Object result = r.opt("result");
        res.ok          = (result instanceof Boolean && (Boolean)result) || r.optInt("result",0)==1;
        res.torrentId   = String.valueOf(r.optInt("torrent_id", r.optInt("id",0)));
        res.progressUrl = r.optString("progress_url","");
        res.msg         = r.optString("error", res.ok ? "Added to Seedr" : "Failed — check your token has torrent.add scope");
        return res;
    }

    public static void deleteFile(String id)    throws Exception { call("delete_file",    new JSONObject().put("folder_file_id",id)); }
    public static void deleteFolder(String id)  throws Exception { call("delete_folder",  new JSONObject().put("folder_id",id)); }
    public static void deleteTorrent(String id) throws Exception { call("delete_torrent", new JSONObject().put("torrent_id",id)); }
    public static void renameFile(String id, String name)   throws Exception { call("rename_file",   new JSONObject().put("folder_file_id",id).put("rename_to",name)); }
    public static void renameFolder(String id, String name) throws Exception { call("rename_folder", new JSONObject().put("folder_id",id).put("rename_to",name)); }

    public static List<SeedrFile> search(String q) throws Exception {
        JSONObject r = call("search_files", new JSONObject().put("search_query", q));
        List<SeedrFile> out = new ArrayList<>();
        JSONArray arr = r.optJSONArray("folder_files");
        if (arr == null) arr = r.optJSONArray("files");
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            SeedrFile f = new SeedrFile();
            f.id   = String.valueOf(o.optInt("folder_file_id",o.optInt("id")));
            f.name = o.optString("name","");
            f.ext  = o.optString("ext","");
            f.size = o.optLong("size",0);
            out.add(f);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    /** Make an API call; auto-refresh the token on 401 / expired_token. */
    static JSONObject call(String func, JSONObject body) throws Exception {
        String tok = token();
        JSONObject r = callWith(tok, func, body);
        if ("expired_token".equals(r.optString("error","")) || r.optInt("status_code",0)==401) {
            tok = refresh();
            r = callWith(tok, func, body);
        }
        String err = r.optString("error","");
        if (!err.isEmpty() && !"access_granted".equals(err)) throw new Exception(err);
        return r;
    }

    private static JSONObject callWith(String tok, String func, JSONObject body) throws Exception {
        String url = RESOURCE + "?access_token=" + URLEncoder.encode(tok,"UTF-8")
                              + "&func="         + URLEncoder.encode(func,"UTF-8");
        HttpURLConnection c = open(url);
        c.setRequestMethod(body != null && body.length() > 0 ? "POST" : "GET");
        if (body != null && body.length() > 0) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type","application/x-www-form-urlencoded");
            StringBuilder sb = new StringBuilder();
            java.util.Iterator<String> it = body.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (sb.length() > 0) sb.append('&');
                sb.append(URLEncoder.encode(k,"UTF-8")).append('=')
                  .append(URLEncoder.encode(String.valueOf(body.get(k)),"UTF-8"));
            }
            byte[] b = sb.toString().getBytes(StandardCharsets.UTF_8);
            c.setRequestProperty("Content-Length", String.valueOf(b.length));
            try (OutputStream o = c.getOutputStream()) { o.write(b); }
        }
        return readJson(c);
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT); c.setReadTimeout(TIMEOUT);
        c.setRequestProperty("User-Agent","ZHPlay/1.0");
        return c;
    }

    private static JSONObject readJson(HttpURLConnection c) throws Exception {
        int code = c.getResponseCode();
        java.io.InputStream in = (code >= 400) ? c.getErrorStream() : c.getInputStream();
        if (in == null) throw new Exception("HTTP "+code+" — no response body");
        BufferedReader br = new BufferedReader(new InputStreamReader(in,"UTF-8"));
        StringBuilder sb = new StringBuilder(); String ln;
        while ((ln=br.readLine())!=null) sb.append(ln);
        br.close();
        String text = sb.toString().trim();
        if (text.isEmpty()) return new JSONObject().put("result",code<400);
        if (text.startsWith("[")) {
            JSONArray arr = new JSONArray(text);
            return new JSONObject().put("folder_files", arr);
        }
        JSONObject j = new JSONObject(text);
        if (code == 401) j.put("error","expired_token");
        return j;
    }
}
