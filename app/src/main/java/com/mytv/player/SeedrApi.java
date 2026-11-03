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
 * Seedr API — confirmed working endpoints from live curl tests:
 *
 *   Device code:  POST https://www.seedr.cc/api/device/code
 *                 body: client_id=seedr_chrome&scope=device_read
 *                 → {device_code, user_code, verification_url, expires_in, interval}
 *
 *   Poll token:   POST https://www.seedr.cc/api/token
 *                 body: grant_type=urn:ietf:params:oauth:grant-type:device_code
 *                       &device_code=...&client_id=seedr_chrome
 *                 → {access_token, refresh_token} or {error: authorization_pending}
 *
 *   Data:         POST https://www.seedr.cc/oauth_test/resource.php
 *                 ?access_token=TOKEN&func=FUNC
 *                 body: form fields
 *
 *   Password:     POST https://www.seedr.cc/oauth_test/token.php
 *                 body: grant_type=password&username=EMAIL&password=PWD&client_id=seedr_chrome
 */
public final class SeedrApi {
    private SeedrApi() {}

    // Confirmed from curl tests
    private static final String DEVICE_CODE_URL = "https://www.seedr.cc/api/device/code";
    private static final String TOKEN_URL       = "https://www.seedr.cc/api/token";
    private static final String PASSWORD_URL    = "https://www.seedr.cc/oauth_test/token.php";
    private static final String RESOURCE        = "https://www.seedr.cc/oauth_test/resource.php";
    private static final String CLIENT_ID       = "seedr_chrome";
    private static final String SCOPE           = "device_read";
    private static final int    TIMEOUT         = 25_000;

    private static final String KEY_TOKEN   = "seedr_tok4";
    private static final String KEY_REFRESH = "seedr_ref4";
    private static final String KEY_EMAIL   = "seedr_email2";

    // ------------------------------------------------------------------ public

    public static boolean hasToken() { return !Store.getStr(KEY_TOKEN,"").isEmpty(); }
    public static String  email()    { return Store.getStr(KEY_EMAIL,""); }
    public static void clearAll() {
        Store.putStr(KEY_TOKEN,""); Store.putStr(KEY_REFRESH,""); Store.putStr(KEY_EMAIL,"");
    }
    private static String token() { return Store.getStr(KEY_TOKEN,""); }

    // ------------------------------------------------------------------ models

    public static class SeedrFile {
        public String  id, name, ext, url, progress, progressUrl;
        public long    size, dlRate;
        public int     seeders, peers;
        public boolean isFolder, isTorrent;

        public String displaySize() {
            if (size<=0) return "";
            if (size>1_073_741_824L) return String.format(Locale.US,"%.1f GB",size/1_073_741_824.0);
            if (size>    1_048_576L) return String.format(Locale.US,"%.1f MB",size/    1_048_576.0);
            return String.format(Locale.US,"%.0f KB",size/1024.0);
        }
        public String subtitle() {
            if (isTorrent) {
                String s=(progress==null?"0":progress)+"%";
                if (dlRate>0) s+="  ↓ "+fmtSpeed(dlRate);
                if (seeders>0) s+="  "+seeders+" seeds";
                return s;
            }
            if (isFolder) return "Folder"+(size>0?"  "+displaySize():"");
            return (ext==null||ext.isEmpty()?"":ext.toUpperCase(Locale.US)+"  ")+displaySize();
        }
    }

    public static class AccountInfo {
        public String username, email;
        public long   spaceUsed, spaceTotal;
        public String quota() {
            if (spaceTotal<=0) return "";
            return fmt(spaceUsed)+" / "+fmt(spaceTotal);
        }
        static String fmt(long b) {
            if (b>1_073_741_824L) return String.format(Locale.US,"%.1f GB",b/1_073_741_824.0);
            return String.format(Locale.US,"%.0f MB",b/1_048_576.0);
        }
    }

    public static class AddResult {
        public boolean ok;
        public String  msg, torrentId, progressUrl;
    }

    public static class DeviceCode {
        public String deviceCode, userCode, verifyUrl;
        public int    expiresIn, interval;
    }

    // ------------------------------------------------------------------ Device Flow login

    /** Step 1: request a device code. Show userCode + verifyUrl to the user. */
    public static DeviceCode startDeviceFlow() throws Exception {
        JSONObject r = postForm(DEVICE_CODE_URL, new JSONObject()
                .put("client_id", CLIENT_ID)
                .put("scope",     SCOPE));
        DeviceCode dc = new DeviceCode();
        dc.deviceCode = r.optString("device_code","");
        dc.userCode   = r.optString("user_code","");
        dc.verifyUrl  = r.optString("verification_url",
                        r.optString("verification_uri","https://www.seedr.cc/devices"));
        dc.expiresIn  = r.optInt("expires_in", 1800);
        dc.interval   = r.optInt("interval", 5);
        if (dc.deviceCode.isEmpty())
            throw new Exception("Device flow failed: "+r.toString());
        return dc;
    }

    /**
     * Step 2: poll until the user authorizes.
     * Returns true when the token is saved, false = still pending, throws on error.
     */
    public static boolean pollDeviceFlow(String deviceCode) throws Exception {
        JSONObject r = postForm(TOKEN_URL, new JSONObject()
                .put("grant_type","urn:ietf:params:oauth:grant-type:device_code")
                .put("device_code", deviceCode)
                .put("client_id",   CLIENT_ID));
        String err = r.optString("error","");
        if ("authorization_pending".equals(err) || "slow_down".equals(err)) return false;
        if (!err.isEmpty()) throw new Exception("Authorization failed: "+err);
        String tok = r.optString("access_token","");
        if (tok.isEmpty()) return false;
        Store.putStr(KEY_TOKEN,   tok);
        Store.putStr(KEY_REFRESH, r.optString("refresh_token",""));
        return true;
    }

    /** Login with email + password (alternative to device flow). */
    public static AccountInfo login(String emailStr, String password) throws Exception {
        JSONObject r = postForm(PASSWORD_URL, new JSONObject()
                .put("grant_type","password")
                .put("client_id",  CLIENT_ID)
                .put("username",   emailStr.trim())
                .put("password",   password)
                .put("type",       "login"));
        String tok = r.optString("access_token","");
        if (tok.isEmpty()) {
            String err = r.optString("error_description", r.optString("error","Login failed"));
            throw new Exception(err);
        }
        Store.putStr(KEY_TOKEN,   tok);
        Store.putStr(KEY_REFRESH, r.optString("refresh_token",""));
        Store.putStr(KEY_EMAIL,   emailStr.trim());
        return getAccount();
    }

    // ------------------------------------------------------------------ data API

    public static AccountInfo getAccount() throws Exception {
        JSONObject r = call("get_settings", null);
        AccountInfo a = new AccountInfo();
        a.username = r.optString("username", r.optString("name",""));
        a.email    = Store.getStr(KEY_EMAIL,"");
        // get storage separately
        try {
            JSONObject m   = call("get_memory_bandwidth", null);
            JSONObject mem = m.optJSONObject("memory");
            if (mem!=null) {
                a.spaceUsed  = mem.optLong("bandwidth_used", mem.optLong("space_used", 0));
                a.spaceTotal = mem.optLong("bandwidth_max",  mem.optLong("space_max",  0));
            }
        } catch (Exception ignored) {}
        return a;
    }

    public static volatile String lastRaw = "";

    public static List<SeedrFile> listFolder(String folderId) throws Exception {
        JSONObject body = new JSONObject()
                .put("content_type","folder")
                .put("content_id", folderId==null||folderId.equals("0") ? "0" : folderId);
        JSONObject r = call("list_contents", body);
        lastRaw = r.toString();
        android.util.Log.d("SeedrAPI","keys="+keysOf(r)+
                " raw="+lastRaw.substring(0,Math.min(500,lastRaw.length())));
        return parseContents(r);
    }

    public static AddResult addMagnet(String magnet) throws Exception {
        JSONObject r = call("add_torrent", new JSONObject()
                .put("torrent_magnet", magnet)
                .put("folder_id","-1"));
        AddResult res = new AddResult();
        Object result = r.opt("result");
        res.ok = (result instanceof Boolean && (Boolean)result)
              || r.optInt("result",0)==1;
        res.torrentId   = r.optString("torrent_id","");
        res.progressUrl = r.optString("progress_url","");
        res.msg = res.ok ? "✓ Added to Seedr"
                : r.optString("error","Failed — free accounts allow 1 active torrent");
        return res;
    }

    public static String fetchFileUrl(String fileId) throws Exception {
        JSONObject r = call("fetch_file",
                new JSONObject().put("folder_file_id", fileId));
        String url = r.optString("url", r.optString("download_url",""));
        if (url.isEmpty()) throw new Exception("No URL returned");
        return url;
    }

    public static void deleteFile(String id)    throws Exception {
        call("delete",new JSONObject().put("delete_arr","[{\"type\":\"file\",\"id\":"+id+"}]"));
    }
    public static void deleteFolder(String id)  throws Exception {
        call("delete",new JSONObject().put("delete_arr","[{\"type\":\"folder\",\"id\":"+id+"}]"));
    }
    public static void deleteTorrent(String id) throws Exception {
        call("delete",new JSONObject().put("delete_arr","[{\"type\":\"torrent\",\"id\":"+id+"}]"));
    }
    public static void renameFile(String id, String name) throws Exception {
        call("rename_file",new JSONObject().put("file_id",id).put("rename_to",name));
    }
    public static void renameFolder(String id, String name) throws Exception {
        call("rename_folder",new JSONObject().put("folder_id",id).put("rename_to",name));
    }
    public static List<SeedrFile> search(String q) throws Exception {
        return parseContents(call("search_files",new JSONObject().put("search_query",q)));
    }

    // ------------------------------------------------------------------ internal

    private static JSONObject call(String func, JSONObject body) throws Exception {
        String tok = token();
        if (tok.isEmpty()) throw new Exception("Not logged in — open Seedr → ⚙ → Login");
        JSONObject r = callWith(tok, func, body);
        String err = r.optString("error","");
        if ("expired_token".equals(err) || "access_denied".equals(err)) {
            tok = doRefresh();
            if (!tok.isEmpty()) r = callWith(tok, func, body);
            else throw new Exception("Session expired — please log in again");
        }
        return r;
    }

    private static JSONObject callWith(String tok, String func, JSONObject body) throws Exception {
        String url = RESOURCE
                   + "?access_token=" + URLEncoder.encode(tok,"UTF-8")
                   + "&func="         + URLEncoder.encode(func,"UTF-8");
        HttpURLConnection c = conn(url,"POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type","application/x-www-form-urlencoded");
        StringBuilder sb = new StringBuilder();
        if (body!=null) {
            java.util.Iterator<String> it = body.keys();
            while (it.hasNext()) {
                String k=it.next(); Object v=body.opt(k); if(v==null) continue;
                if (sb.length()>0) sb.append('&');
                sb.append(URLEncoder.encode(k,"UTF-8")).append('=')
                  .append(URLEncoder.encode(String.valueOf(v),"UTF-8"));
            }
        }
        byte[] b = sb.toString().getBytes(StandardCharsets.UTF_8);
        c.setRequestProperty("Content-Length",String.valueOf(b.length));
        try(OutputStream o=c.getOutputStream()){o.write(b);}
        return readJson(c);
    }

    private static String doRefresh() {
        String rt = Store.getStr(KEY_REFRESH,"");
        if (rt.isEmpty()) return "";
        try {
            JSONObject r = postForm(TOKEN_URL, new JSONObject()
                    .put("grant_type","refresh_token")
                    .put("client_id", CLIENT_ID)
                    .put("refresh_token", rt));
            String newTok = r.optString("access_token","");
            if (!newTok.isEmpty()) {
                Store.putStr(KEY_TOKEN,   newTok);
                Store.putStr(KEY_REFRESH, r.optString("refresh_token",""));
                return newTok;
            }
        } catch (Exception ignored){}
        return "";
    }

    // ------------------------------------------------------------------ parsing

    private static List<SeedrFile> parseContents(JSONObject r) {
        List<SeedrFile> out = new ArrayList<>();
        JSONArray tor = r.optJSONArray("torrents");
        if (tor!=null) for (int i=0;i<tor.length();i++) {
            JSONObject o=tor.optJSONObject(i); if(o==null) continue;
            SeedrFile f=new SeedrFile(); f.isTorrent=true;
            f.id=sid(o); f.name=o.optString("name","Downloading…");
            f.size=o.optLong("size",0);
            Object prog=o.opt("progress");
            if (prog instanceof Number)
                f.progress=String.valueOf((int)Math.round(((Number)prog).doubleValue()));
            else f.progress=prog!=null?prog.toString():"0";
            f.progressUrl=o.optString("progress_url","");
            f.seeders=o.optInt("seeders",0);
            f.peers=o.optInt("downloading_from",o.optInt("peers",0));
            f.dlRate=o.optLong("download_rate",0);
            out.add(f);
        }
        JSONArray fol=r.optJSONArray("folders");
        if (fol!=null) for (int i=0;i<fol.length();i++) {
            JSONObject o=fol.optJSONObject(i); if(o==null) continue;
            SeedrFile f=new SeedrFile(); f.isFolder=true;
            f.id=sid(o); f.name=o.optString("name",""); f.size=o.optLong("size",0);
            out.add(f);
        }
        JSONArray fil=r.optJSONArray("folder_files");
        if (fil==null) fil=r.optJSONArray("files");
        if (fil!=null) for (int i=0;i<fil.length();i++) {
            JSONObject o=fil.optJSONObject(i); if(o==null) continue;
            SeedrFile f=new SeedrFile();
            f.id=o.has("folder_file_id")?String.valueOf(o.opt("folder_file_id")):sid(o);
            f.name=o.optString("name",""); f.ext=o.optString("ext","");
            f.size=o.optLong("size",0);
            out.add(f);
        }
        return out;
    }

    private static String sid(JSONObject o){return String.valueOf(o.opt("id"));}
    private static String keysOf(JSONObject j){
        StringBuilder sb=new StringBuilder();
        java.util.Iterator<String> it=j.keys();
        while(it.hasNext()){if(sb.length()>0)sb.append(",");sb.append(it.next());}
        return sb.toString();
    }

    // ------------------------------------------------------------------ HTTP

    private static JSONObject postForm(String url, JSONObject body) throws Exception {
        HttpURLConnection c=conn(url,"POST"); c.setDoOutput(true);
        c.setRequestProperty("Content-Type","application/x-www-form-urlencoded");
        StringBuilder sb=new StringBuilder();
        java.util.Iterator<String> it=body.keys();
        while(it.hasNext()){String k=it.next();if(sb.length()>0)sb.append('&');
            sb.append(URLEncoder.encode(k,"UTF-8")).append('=')
              .append(URLEncoder.encode(String.valueOf(body.opt(k)),"UTF-8"));}
        byte[] b=sb.toString().getBytes(StandardCharsets.UTF_8);
        c.setRequestProperty("Content-Length",String.valueOf(b.length));
        try(OutputStream o=c.getOutputStream()){o.write(b);}
        return readJson(c);
    }

    private static HttpURLConnection conn(String url,String method) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT); c.setReadTimeout(TIMEOUT);
        c.setRequestMethod(method);
        c.setRequestProperty("User-Agent","seedr_chrome");
        return c;
    }

    private static JSONObject readJson(HttpURLConnection c) throws Exception {
        int code=c.getResponseCode();
        java.io.InputStream in=(code>=400)?c.getErrorStream():c.getInputStream();
        if(in==null) return new JSONObject().put("ok",code<400);
        BufferedReader br=new BufferedReader(new InputStreamReader(in,"UTF-8"));
        StringBuilder sb=new StringBuilder(); String ln;
        while((ln=br.readLine())!=null) sb.append(ln);
        br.close();
        String text=sb.toString().trim();
        if(text.isEmpty()) return new JSONObject().put("ok",code<400);
        if(text.startsWith("<")) throw new Exception("Seedr returned HTML — try logging in again");
        if(text.startsWith("[")) {
            JSONArray arr=new JSONArray(text);
            JSONObject w=new JSONObject();
            if(arr.length()>0){JSONObject f=arr.optJSONObject(0);
                if(f!=null&&f.has("folder_file_id")) w.put("folder_files",arr);
                else if(f!=null&&f.optBoolean("is_folder",false)) w.put("folders",arr);
                else w.put("files",arr);
            } else w.put("folder_files",arr);
            return w;
        }
        return new JSONObject(text);
    }

    static String fmtSpeed(long bps){
        if(bps>1_048_576) return String.format(Locale.US,"%.1f MB/s",bps/1_048_576.0);
        if(bps>1024)      return String.format(Locale.US,"%.0f KB/s",bps/1024.0);
        return bps+" B/s";
    }
}
