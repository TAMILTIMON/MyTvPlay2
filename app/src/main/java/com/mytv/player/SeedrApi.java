package com.mytv.player;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Seedr client for ZH Play. Every call here mirrors the reference client "seedrcc" 2.1.2 exactly
 * (endpoints, client ids, function names, field names) — nothing is guessed.
 *
 *   Login (password)  POST oauth_test/token.php   grant_type=password, client_id=seedr_chrome, username, password, type=login
 *   Refresh           POST oauth_test/token.php   grant_type=refresh_token, client_id=seedr_chrome, refresh_token
 *   Device code       GET  api/device/code        client_id=seedr_xbmc
 *   Device authorize  GET  api/device/authorize   client_id=seedr_xbmc, device_code   (also how a device token is renewed)
 *   Everything else   oauth_test/resource.php?access_token=…&func=…   (GET for settings / memory, POST form for the rest)
 *
 *   list_contents   content_type=folder, content_id        → folders[], files[] (folder_file_id, play_video…), torrents[], space_used, space_max
 *   add_torrent     torrent_magnet, folder_id=-1  (or a multipart torrent_file)  → result:true, user_torrent_id, title
 *   fetch_file      folder_file_id                         → url, name
 *   delete          delete_arr=[{"type":"file|folder|torrent","id":N}]
 *   rename          rename_to + file_id | folder_id
 *   search_files    search_query                           → folders[], files[]
 *   get_settings    → account{username, email, space_used, space_max, premium…}
 *   get_memory_bandwidth → space_used, space_max, bandwidth_used, bandwidth_max, is_premium
 *
 * Personal Access Tokens (console/tokens) only work on the new /api/v0.1/p API, which Seedr has not
 * opened for files yet (only /user answers), so ZH Play signs in with the account itself.
 * All calls are blocking: run them off the main thread.
 */
public final class SeedrApi {
    private SeedrApi() {
    }

    private static final String RESOURCE = "https://www.seedr.cc/oauth_test/resource.php";
    private static final String TOKEN_URL = "https://www.seedr.cc/oauth_test/token.php";
    private static final String DEVICE_CODE_URL = "https://www.seedr.cc/api/device/code";
    private static final String DEVICE_AUTH_URL = "https://www.seedr.cc/api/device/authorize";
    private static final String PASSWORD_CLIENT = "seedr_chrome";
    private static final String DEVICE_CLIENT = "seedr_xbmc";
    private static final int TIMEOUT = 25_000;

    private static final String K_ACCESS = "seedr_access";
    private static final String K_REFRESH = "seedr_refresh";
    private static final String K_DEVICE = "seedr_device";
    private static final String K_EMAIL = "seedr_email";

    /** Last raw answer from Seedr (shown when something unexpected comes back). */
    public static volatile String lastRaw = "";

    // ------------------------------------------------------------------ session

    public static boolean hasToken() {
        return !Store.getStr(K_ACCESS, "").isEmpty();
    }

    public static String email() {
        return Store.getStr(K_EMAIL, "");
    }

    public static void clearAll() {
        Store.putStr(K_ACCESS, "");
        Store.putStr(K_REFRESH, "");
        Store.putStr(K_DEVICE, "");
    }

    // ------------------------------------------------------------------ models

    public static class SeedrFile {
        public String id = "", name = "", ext = "", progress = "", progressUrl = "";
        public long size, dlRate;
        public int seeders, peers;
        public boolean isFolder, isTorrent, playVideo, playAudio;

        public String displaySize() {
            return fmtBytes(size);
        }

        public String subtitle() {
            if (isTorrent) {
                String s = (progress.isEmpty() ? "0" : progress) + "%";
                if (dlRate > 0) s += "  ↓ " + fmtSpeed(dlRate);
                if (seeders > 0) s += "  " + seeders + " seeds";
                return s;
            }
            if (isFolder) return T.t("Folder") + (size > 0 ? "  ·  " + displaySize() : "");
            return (ext.isEmpty() ? "" : ext.toUpperCase(Locale.US) + "  ·  ") + displaySize();
        }

        /** A video or song ZH Play can stream. */
        public boolean playable() {
            return !isFolder && !isTorrent && (playVideo || playAudio || Formats.isVideo(name) || Formats.isAudio(name));
        }
    }

    public static class AccountInfo {
        public String username = "", email = "";
        public long spaceUsed, spaceTotal;
        public boolean premium;

        public String quota() {
            return spaceTotal <= 0 ? "" : fmtBytes(spaceUsed) + " / " + fmtBytes(spaceTotal);
        }
    }

    public static class AddResult {
        public boolean ok;
        boolean retry;
        public String msg = "", title = "";
    }

    public static class DeviceCode {
        public String deviceCode = "", userCode = "", verifyUrl = "";
        public int expiresIn = 600, interval = 5;
    }

    // ------------------------------------------------------------------ sign in

    /** Sign in with the Seedr e-mail and password. */
    public static AccountInfo login(String email, String password) throws Exception {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("username", email.trim());
        f.put("password", password);
        f.put("grant_type", "password");
        f.put("client_id", PASSWORD_CLIENT);
        f.put("type", "login");
        JSONObject r = request("POST", TOKEN_URL, null, f, null);
        String tok = r.optString("access_token", "");
        if (tok.isEmpty()) {
            String e = r.optString("error_description", r.optString("error", ""));
            throw new Exception(e.isEmpty() || e.contains("invalid") ? T.t("Wrong e-mail or password") : e);
        }
        Store.putStr(K_ACCESS, tok);
        Store.putStr(K_REFRESH, r.optString("refresh_token", ""));
        Store.putStr(K_DEVICE, "");
        Store.putStr(K_EMAIL, email.trim());
        return getAccount();
    }

    /** Step 1 of signing in with a code: show userCode and verifyUrl. */
    public static DeviceCode startDeviceFlow() throws Exception {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("client_id", DEVICE_CLIENT);
        JSONObject r = request("GET", DEVICE_CODE_URL, q, null, null);
        DeviceCode d = new DeviceCode();
        d.deviceCode = r.optString("device_code", "");
        d.userCode = r.optString("user_code", "");
        d.verifyUrl = r.optString("verification_url", "https://www.seedr.cc/devices");
        d.expiresIn = r.optInt("expires_in", 600);
        d.interval = Math.max(5, r.optInt("interval", 5));
        if (d.deviceCode.isEmpty() || d.userCode.isEmpty()) throw new Exception(T.t("Seedr did not give a code") + ": " + r);
        return d;
    }

    /** Step 2: true once the code was approved on seedr.cc/devices; false while still waiting. */
    public static boolean pollDeviceFlow(String deviceCode) throws Exception {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("device_code", deviceCode);
        q.put("client_id", DEVICE_CLIENT);
        JSONObject r = request("GET", DEVICE_AUTH_URL, q, null, null);
        String tok = r.optString("access_token", "");
        if (tok.isEmpty()) {
            String e = r.optString("error", "");
            if (e.isEmpty() || e.contains("pending") || e.contains("slow")) return false;
            throw new Exception(e);
        }
        Store.putStr(K_ACCESS, tok);
        Store.putStr(K_REFRESH, r.optString("refresh_token", ""));
        Store.putStr(K_DEVICE, deviceCode);
        return true;
    }

    /** A fresh access token from the refresh token (password sign-in) or the device code. */
    private static boolean renew() {
        try {
            JSONObject r;
            String rt = Store.getStr(K_REFRESH, ""), dc = Store.getStr(K_DEVICE, "");
            if (!rt.isEmpty()) {
                Map<String, String> f = new LinkedHashMap<>();
                f.put("refresh_token", rt);
                f.put("grant_type", "refresh_token");
                f.put("client_id", PASSWORD_CLIENT);
                r = request("POST", TOKEN_URL, null, f, null);
            } else if (!dc.isEmpty()) {
                Map<String, String> q = new LinkedHashMap<>();
                q.put("device_code", dc);
                q.put("client_id", DEVICE_CLIENT);
                r = request("GET", DEVICE_AUTH_URL, q, null, null);
            } else {
                return false;
            }
            String tok = r.optString("access_token", "");
            if (tok.isEmpty()) return false;
            Store.putStr(K_ACCESS, tok);
            String nrt = r.optString("refresh_token", "");
            if (!nrt.isEmpty()) Store.putStr(K_REFRESH, nrt);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ account

    public static AccountInfo getAccount() throws Exception {
        JSONObject r = api("GET", "get_settings", null, null);
        AccountInfo a = new AccountInfo();
        JSONObject acc = r.optJSONObject("account");
        if (acc == null) acc = r;
        a.username = acc.optString("username", "");
        a.email = acc.optString("email", email());
        a.premium = acc.optInt("premium", 0) == 1 || acc.optBoolean("premium", false);
        a.spaceUsed = acc.optLong("space_used", 0);
        a.spaceTotal = acc.optLong("space_max", 0);
        if (a.spaceTotal <= 0) {
            try {
                JSONObject m = api("GET", "get_memory_bandwidth", null, null);
                a.spaceUsed = m.optLong("space_used", 0);
                a.spaceTotal = m.optLong("space_max", 0);
                a.premium = a.premium || m.optBoolean("is_premium", false);
            } catch (Exception ignored) {
            }
        }
        if (a.username.isEmpty()) a.username = a.email;
        return a;
    }

    // ------------------------------------------------------------------ files

    /** Folders, files and running downloads in a folder ("0" = the top). */
    public static List<SeedrFile> listFolder(String folderId) throws Exception {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("content_type", "folder");
        f.put("content_id", folderId == null || folderId.isEmpty() ? "0" : folderId);
        return parseContents(api("POST", "list_contents", f, null));
    }

    public static List<SeedrFile> search(String query) throws Exception {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("search_query", query);
        return parseContents(api("POST", "search_files", f, null));
    }

    /** A direct https link to stream or download a file (pass the file's folder_file_id). */
    public static String fetchFileUrl(String folderFileId) throws Exception {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("folder_file_id", folderFileId);
        JSONObject r = api("POST", "fetch_file", f, null);
        String url = r.optString("url", "");
        if (url.isEmpty()) throw new Exception(T.t("Seedr gave no link for this file") + " — " + shortRaw());
        return url;
    }

    public static void deleteFile(String folderFileId) throws Exception {
        delete("file", folderFileId);
    }

    public static void deleteFolder(String id) throws Exception {
        delete("folder", id);
    }

    public static void deleteTorrent(String id) throws Exception {
        delete("torrent", id);
    }

    private static void delete(String type, String id) throws Exception {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("delete_arr", "[{\"type\":\"" + type + "\",\"id\":" + id + "}]");
        checkResult(api("POST", "delete", f, null));
    }

    public static void renameFile(String folderFileId, String name) throws Exception {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("rename_to", name);
        f.put("file_id", folderFileId);
        checkResult(api("POST", "rename", f, null));
    }

    public static void renameFolder(String id, String name) throws Exception {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("rename_to", name);
        f.put("folder_id", id);
        checkResult(api("POST", "rename", f, null));
    }

    // ------------------------------------------------------------------ adding

    /** Add a magnet link, or an http(s) link to a .torrent file (downloaded here and uploaded). */
    public static AddResult addMagnet(String link) throws Exception {
        link = link.trim();
        if (link.startsWith("http") && !link.startsWith("magnet:")) {
            Map<String, String> f = new LinkedHashMap<>();
            f.put("folder_id", "-1");
            return addResult(api("POST", "add_torrent", f, download(link)));
        }
        // Seedr's API can refuse magnets its website accepts — e.g. hybrid v1+v2 links with a second
        // "xt=urn:btmh:" hash, or very long tracker lists. The v1 info-hash alone names the same
        // torrent, so if the full link is refused, try simpler forms of the same link.
        AddResult last = null;
        for (String m : magnetVariants(link)) {
            Map<String, String> f = new LinkedHashMap<>();
            f.put("torrent_magnet", m);
            f.put("folder_id", "-1");
            last = addResult(api("POST", "add_torrent", f, null));
            if (last.ok || !last.retry) return last;
        }
        return last;
    }

    /** The link as given, then without the v2 hash, then hash + name (+ a few trackers), then the bare hash. */
    static List<String> magnetVariants(String magnet) {
        List<String> out = new ArrayList<>();
        out.add(magnet);
        String q = magnet.indexOf('?') >= 0 ? magnet.substring(magnet.indexOf('?') + 1) : "";
        String btih = null, dn = null;
        List<String> keep = new ArrayList<>(), trackers = new ArrayList<>();
        for (String part : q.split("&")) {
            if (part.isEmpty()) continue;
            String low = part.toLowerCase(Locale.US);
            if (low.startsWith("xt=urn:btih:")) btih = part.substring(12);
            else if (low.startsWith("xt=urn:btmh:")) continue;           // BitTorrent v2 hash
            else if (low.startsWith("dn=")) dn = part.substring(3);
            else if (low.startsWith("tr=")) trackers.add(part);
            if (!low.startsWith("xt=urn:btmh:")) keep.add(part);
        }
        if (btih == null) return out;                                     // not a normal v1 magnet: send as is
        String noV2 = "magnet:?" + TextUtils_join("&", keep);
        if (!noV2.equals(magnet)) out.add(noV2);
        StringBuilder small = new StringBuilder("magnet:?xt=urn:btih:").append(btih);
        if (dn != null) small.append("&dn=").append(dn);
        for (int i = 0; i < Math.min(5, trackers.size()); i++) small.append('&').append(trackers.get(i));
        if (!out.contains(small.toString())) out.add(small.toString());
        String bare = "magnet:?xt=urn:btih:" + btih + (dn != null ? "&dn=" + dn : "");
        if (!out.contains(bare)) out.add(bare);
        String hashOnly = "magnet:?xt=urn:btih:" + btih;
        if (!out.contains(hashOnly)) out.add(hashOnly);
        return out;
    }

    private static String TextUtils_join(String sep, List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append(sep);
            sb.append(p);
        }
        return sb.toString();
    }

    private static AddResult addResult(JSONObject r) {
        AddResult a = new AddResult();
        Object res = r.opt("result");
        a.ok = Boolean.TRUE.equals(res) || "true".equals(String.valueOf(res));
        a.title = r.optString("title", "");
        if (a.ok) {
            a.msg = "☁ " + T.t("Added to Seedr") + (a.title.isEmpty() ? "" : ": " + a.title);
        } else {
            String why = (String.valueOf(res) + " " + r.optString("error", "")).toLowerCase(Locale.US);
            // a definite answer about the account (queue full, no space, already there, signed out): don't retry
            a.retry = !(why.contains("queue_full") || why.contains("not_enough_space") || why.contains("already")
                    || why.contains("exists") || why.contains("access_denied") || r.optInt("status_code", 0) == 401);
            a.msg = explainAdd(r);
        }
        return a;
    }

    /** Seedr answers a failed add with a reason code; turn it into a sentence. */
    private static String explainAdd(JSONObject r) {
        String why = String.valueOf(r.opt("result"));
        String err = r.optString("error", "");
        int status = r.optInt("status_code", r.optInt("code", 0));
        String s = (why + " " + err).toLowerCase(Locale.US);
        if (s.contains("queue_full")) {
            return T.t("Seedr: a download is already running (the free plan allows 1 at a time). It was put on your wishlist and starts by itself when the current one finishes.");
        }
        if (s.contains("not_enough_space")) {
            return T.t("Seedr: not enough free space. Delete some files in Seedr, then add it again.")
                    + (s.contains("wishlist_full") ? "" : " " + T.t("(It was saved to your wishlist.)"));
        }
        if (s.contains("parsing") || s.contains("invalid") || s.contains("magnet")) {
            return T.t("Seedr could not read this magnet / torrent link.");
        }
        if (s.contains("already") || s.contains("exists")) return T.t("This torrent is already in your Seedr.");
        if (status == 404) {
            return T.t("Seedr's app connection refused this link, even in simpler forms. Tap “Add on seedr.cc” to add it on the website instead.");
        }
        if (status == 401 || s.contains("access_denied")) return T.t("Seedr sign-in expired — sign in again in Seedr ⚙");
        return T.t("Seedr did not add it") + " — " + shortRaw();
    }

    // ------------------------------------------------------------------ parsing

    private static List<SeedrFile> parseContents(JSONObject r) {
        List<SeedrFile> out = new ArrayList<>();
        JSONArray tor = r.optJSONArray("torrents");
        if (tor != null) for (int i = 0; i < tor.length(); i++) {
            JSONObject o = tor.optJSONObject(i);
            if (o == null) continue;
            SeedrFile f = new SeedrFile();
            f.isTorrent = true;
            f.id = o.optString("id", "");
            f.name = o.optString("name", T.t("Downloading…"));
            f.size = o.optLong("size", 0);
            f.progress = progressText(o.opt("progress"));
            f.progressUrl = o.optString("progress_url", "");
            f.seeders = o.optInt("seeders", 0);
            f.peers = o.optInt("downloading_from", 0);
            f.dlRate = o.optLong("download_rate", 0);
            out.add(f);
        }
        JSONArray fol = r.optJSONArray("folders");
        if (fol != null) for (int i = 0; i < fol.length(); i++) {
            JSONObject o = fol.optJSONObject(i);
            if (o == null) continue;
            SeedrFile f = new SeedrFile();
            f.isFolder = true;
            f.id = o.optString("id", "");
            f.name = o.optString("name", o.optString("fullname", ""));
            f.size = o.optLong("size", 0);
            out.add(f);
        }
        JSONArray fil = r.optJSONArray("files");
        if (fil == null) fil = r.optJSONArray("folder_files");
        if (fil != null) for (int i = 0; i < fil.length(); i++) {
            JSONObject o = fil.optJSONObject(i);
            if (o == null) continue;
            SeedrFile f = new SeedrFile();
            f.id = o.has("folder_file_id") ? o.optString("folder_file_id") : o.optString("file_id", o.optString("id", ""));
            f.name = o.optString("name", "");
            int dot = f.name.lastIndexOf('.');
            f.ext = dot > 0 ? f.name.substring(dot + 1) : "";
            f.size = o.optLong("size", 0);
            f.playVideo = o.optBoolean("play_video", false);
            f.playAudio = o.optBoolean("play_audio", false);
            out.add(f);
        }
        return out;
    }

    /** "45", 45, 0.45 or 45.2 → "45". */
    private static String progressText(Object p) {
        if (p == null) return "0";
        try {
            double d = Double.parseDouble(String.valueOf(p));
            if (d > 0 && d <= 1 && String.valueOf(p).contains(".")) d *= 100;
            return String.valueOf((int) Math.round(d));
        } catch (Exception e) {
            return String.valueOf(p);
        }
    }

    /** Live state of a running download, from its progress_url. Null if unavailable. */
    public static SeedrFile progress(String progressUrl) {
        if (progressUrl == null || progressUrl.isEmpty()) return null;
        try {
            JSONObject r = request("GET", progressUrl, null, null, null);
            SeedrFile f = new SeedrFile();
            f.isTorrent = true;
            f.name = r.optString("title", r.optString("name", ""));
            f.progress = progressText(r.opt("progress"));
            JSONObject stats = r.optJSONObject("stats");
            JSONObject src = stats != null ? stats : r;
            f.dlRate = src.optLong("download_rate", 0);
            f.seeders = src.optInt("seeders", 0);
            f.peers = src.optInt("downloading_from", src.optInt("leechers", 0));
            f.size = r.optLong("size", 0);
            return f;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ http

    /** A resource.php call with the saved token; renews the token once if Seedr says it expired. */
    private static JSONObject api(String method, String func, Map<String, String> form, byte[] torrentFile) throws Exception {
        String tok = Store.getStr(K_ACCESS, "");
        if (tok.isEmpty()) throw new Exception(T.t("Not signed in to Seedr"));
        JSONObject r = resourceCall(method, func, form, torrentFile, tok);
        String err = r.optString("error", "");
        if ("expired_token".equals(err) || "invalid_token".equals(err) || r.optInt("status_code", 0) == 401) {
            if (renew()) r = resourceCall(method, func, form, torrentFile, Store.getStr(K_ACCESS, ""));
            else throw new Exception(T.t("Seedr sign-in expired — sign in again in Seedr ⚙"));
        }
        if ("access_denied".equals(r.optString("error", ""))) {
            throw new Exception(T.t("Seedr refused access — sign in again in Seedr ⚙"));
        }
        return r;
    }

    private static JSONObject resourceCall(String method, String func, Map<String, String> form, byte[] torrentFile, String tok) throws Exception {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("access_token", tok);
        q.put("func", func);
        return request(method, RESOURCE, q, form, torrentFile);
    }

    private static void checkResult(JSONObject r) throws Exception {
        Object res = r.opt("result");
        if (res != null && !Boolean.TRUE.equals(res) && !"true".equals(String.valueOf(res))) {
            throw new Exception(T.t("Seedr said no") + ": " + shortRaw());
        }
        if (r.optInt("status_code", 200) >= 400) throw new Exception(T.t("Seedr said no") + ": " + shortRaw());
    }

    private static JSONObject request(String method, String url, Map<String, String> query, Map<String, String> form,
                                      byte[] torrentFile) throws Exception {
        StringBuilder u = new StringBuilder(url);
        if (query != null && !query.isEmpty()) u.append(url.contains("?") ? '&' : '?').append(encode(query));
        HttpURLConnection c = (HttpURLConnection) new URL(u.toString()).openConnection();
        c.setConnectTimeout(TIMEOUT);
        c.setReadTimeout(TIMEOUT);
        c.setRequestMethod(method);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) ZHPlay");
        c.setRequestProperty("Accept", "application/json");
        if ("POST".equals(method)) {
            c.setDoOutput(true);
            byte[] body;
            if (torrentFile != null) {
                String b = "----zhplay" + System.currentTimeMillis();
                c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + b);
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                if (form != null) for (Map.Entry<String, String> e : form.entrySet()) {
                    bo.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"" + e.getKey() + "\"\r\n\r\n"
                            + e.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
                }
                bo.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"torrent_file\"; filename=\"file.torrent\"\r\n"
                        + "Content-Type: application/x-bittorrent\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                bo.write(torrentFile);
                bo.write(("\r\n--" + b + "--\r\n").getBytes(StandardCharsets.UTF_8));
                body = bo.toByteArray();
            } else {
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                body = (form == null ? "" : encode(form)).getBytes(StandardCharsets.UTF_8);
            }
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream o = c.getOutputStream()) {
                o.write(body);
            }
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = "";
        if (in != null) {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String ln;
                while ((ln = br.readLine()) != null) sb.append(ln);
            }
            text = sb.toString().trim();
        }
        lastRaw = "HTTP " + code + " " + text;
        android.util.Log.d("Seedr", method + " " + url + (query != null ? " func=" + query.get("func") : "") + " → " + lastRaw);
        JSONObject j;
        if (text.startsWith("{")) {
            j = new JSONObject(text);
        } else if (text.startsWith("[")) {
            j = new JSONObject().put("files", new JSONArray(text));
        } else if (text.startsWith("<")) {
            throw new Exception(T.t("Seedr sent a web page instead of data (HTTP") + " " + code + ")");
        } else {
            j = new JSONObject();
        }
        if (code >= 400 && !j.has("status_code")) j.put("status_code", code);
        return j;
    }

    private static String encode(Map<String, String> m) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (e.getValue() == null) continue;
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), "UTF-8")).append('=').append(URLEncoder.encode(e.getValue(), "UTF-8"));
        }
        return sb.toString();
    }

    private static byte[] download(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT);
        c.setReadTimeout(TIMEOUT);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) ZHPlay");
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) {
                bo.write(b, 0, n);
                if (bo.size() > 8 * 1024 * 1024) throw new Exception(T.t("That file is too big to be a .torrent"));
            }
            byte[] out = bo.toByteArray();
            if (out.length == 0 || out[0] != 'd') throw new Exception(T.t("That link is not a .torrent file"));
            return out;
        }
    }

    private static String shortRaw() {
        String s = lastRaw;
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }

    public static String fmtBytes(long b) {
        if (b <= 0) return "";
        if (b >= 1_073_741_824L) return String.format(Locale.US, "%.2f GB", b / 1_073_741_824.0);
        if (b >= 1_048_576L) return String.format(Locale.US, "%.1f MB", b / 1_048_576.0);
        return String.format(Locale.US, "%.0f KB", b / 1024.0);
    }

    static String fmtSpeed(long bps) {
        if (bps >= 1_048_576) return String.format(Locale.US, "%.1f MB/s", bps / 1_048_576.0);
        if (bps >= 1024) return String.format(Locale.US, "%.0f KB/s", bps / 1024.0);
        return bps + " B/s";
    }
}
