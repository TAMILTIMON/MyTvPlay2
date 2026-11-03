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
 * Seedr API — supports Personal Access Tokens (PAT) with Full Control scope.
 *
 * Two-endpoint strategy:
 *   1. resource.php  — stable, known field names (from PyPI seedrcc library)
 *   2. v0.1/p Bearer — fallback when PAT works as Bearer but not as access_token
 *
 * Exact field names confirmed from seedrcc PyPI package source:
 *   list_contents:  content_type="folder", content_id="0"  → {folders, folder_files, torrents}
 *   add_torrent:    torrent_magnet="..", folder_id="-1"
 *   fetch_file:     folder_file_id=".."                    → {url}
 *   delete:         delete_arr='[{"type":"file","id":123}]'
 *   rename_file:    file_id="..", rename_to=".."
 *   rename_folder:  folder_id="..", rename_to=".."
 *   search_files:   search_query=".."                      → {folder_files}
 */
public final class SeedrApi {
    private SeedrApi() {}

    private static final String RESOURCE  = "https://www.seedr.cc/oauth_test/resource.php";
    private static final String TOKEN_URL = "https://www.seedr.cc/oauth_test/token.php";
    private static final String V1_BASE   = "https://www.seedr.cc/api/v0.1/p";
    private static final String CLIENT_ID = "seedr_chrome";
    private static final int    TIMEOUT   = 25_000;

    private static final String KEY_PAT     = "seedr_pat";
    private static final String KEY_TOKEN   = "seedr_token";   // working OAuth token
    private static final String KEY_REFRESH = "seedr_refresh";
    private static final String KEY_MODE    = "seedr_mode";    // "oauth" or "v1"

    // ------------------------------------------------------------------ public

    public static void  savePat(String t) { Store.putStr(KEY_PAT, t.trim()); }
    public static String pat()            { return Store.getStr(KEY_PAT, ""); }
    public static boolean hasToken()      { return !Store.getStr(KEY_TOKEN,"").isEmpty() || !pat().isEmpty(); }
    public static void  clearAll()        {
        Store.putStr(KEY_PAT,""); Store.putStr(KEY_TOKEN,"");
        Store.putStr(KEY_REFRESH,""); Store.putStr(KEY_MODE,"");
    }

    // ------------------------------------------------------------------ models

    public static class SeedrFile {
        public String  id, name, ext, url, progress, progressUrl;
        public long    size, dlRate;
        public int     seeders, peers;
        public boolean isFolder, isTorrent;

        public String displaySize() {
            if (size<=0) return "";
            if (size>1_073_741_824L) return String.format(Locale.US,"%.1f GB",size/1_073_741_824.0);
            if (size>1_048_576L)     return String.format(Locale.US,"%.1f MB",size/1_048_576.0);
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

    // ------------------------------------------------------------------ connect

    /**
     * Called when user pastes their token. Tries multiple strategies to find
     * what works, then saves the working configuration permanently.
     */
    public static AccountInfo connect(String inputToken) throws Exception {
        inputToken = inputToken.trim();
        savePat(inputToken);

        // Strategy 1: PAT as OAuth access_token on resource.php
        try {
            Store.putStr(KEY_TOKEN, inputToken);
            Store.putStr(KEY_MODE, "oauth");
            JSONObject r = resourceCall("get_settings", null);
            AccountInfo acc = parseAccount(r);
            if (!acc.username.isEmpty()) return acc;
        } catch (Exception e1) {
            // if HTML response, try strategy 2
        }

        // Strategy 2: PAT as Bearer on v0.1/p
        try {
            JSONObject r2 = v1Call("GET", "/user", null, inputToken);
            AccountInfo acc = parseAccount(r2);
            Store.putStr(KEY_TOKEN, "");
            Store.putStr(KEY_MODE, "v1");
            return acc;
        } catch (Exception e2) {
            // Strategy 3: try OAuth exchange (PAT as password)
            try {
                JSONObject tok = postForm(TOKEN_URL, new JSONObject()
                        .put("grant_type","password").put("client_id",CLIENT_ID)
                        .put("username","").put("password",inputToken).put("type","login"));
                String oauthTok = tok.optString("access_token","");
                if (!oauthTok.isEmpty()) {
                    Store.putStr(KEY_TOKEN, oauthTok);
                    Store.putStr(KEY_REFRESH, tok.optString("refresh_token",""));
                    Store.putStr(KEY_MODE,"oauth");
                    JSONObject r3 = resourceCall("get_settings", null);
                    return parseAccount(r3);
                }
            } catch (Exception ignored) {}

            clearAll();
            throw new Exception(
                "Token not accepted by Seedr.\n\n" +
                "• Make sure you selected 'Full Control' scope\n" +
                "• Try generating a fresh token at:\n  seedr.cc/api/v0.1/console/tokens\n\n" +
                "Error: " + e2.getMessage());
        }
    }

    // ------------------------------------------------------------------ data API

    public static AccountInfo getAccount() throws Exception {
        JSONObject r = call("get_settings", null);
        AccountInfo acc = parseAccount(r);
        if (acc.spaceTotal == 0) {
            // try separate memory call
            try {
                JSONObject m = call("get_memory_bandwidth", null);
                JSONObject mem = m.optJSONObject("memory");
                if (mem!=null) {
                    acc.spaceUsed  = mem.optLong("bandwidth_used",0);
                    acc.spaceTotal = mem.optLong("bandwidth_max",0);
                }
            } catch (Exception ignored) {}
        }
        return acc;
    }

    public static List<SeedrFile> listFolder(String folderId) throws Exception {
        JSONObject body = new JSONObject()
                .put("content_type","folder")
                .put("content_id", folderId==null||folderId.equals("0") ? "0" : folderId);
        return parseContents(call("list_contents", body));
    }

    public static AddResult addMagnet(String magnet) throws Exception {
        JSONObject body = new JSONObject()
                .put("torrent_magnet", magnet)
                .put("folder_id","-1");
        JSONObject r = call("add_torrent", body);
        AddResult res = new AddResult();
        Object result = r.opt("result");
        res.ok = (result instanceof Boolean && (Boolean)result)
              || r.optInt("result",0)==1
              || (!r.has("error") && (r.has("torrent_id")||r.has("id")));
        res.torrentId   = r.optString("torrent_id", String.valueOf(r.optInt("id")));
        res.progressUrl = r.optString("progress_url","");
        res.msg = r.optString("error", res.ok
                ? "✓ Added to Seedr"
                : "Failed — check token has Full Control scope");
        return res;
    }

    public static String fetchFileUrl(String fileId) throws Exception {
        JSONObject body = new JSONObject().put("folder_file_id", fileId);
        JSONObject r = call("fetch_file", body);
        String url = r.optString("url", r.optString("download_url",""));
        if (url.isEmpty()) throw new Exception("No download URL in response");
        return url;
    }

    public static void deleteFile(String id)    throws Exception {
        call("delete", new JSONObject().put("delete_arr","[{\"type\":\"file\",\"id\":"+id+"}]"));
    }
    public static void deleteFolder(String id)  throws Exception {
        call("delete", new JSONObject().put("delete_arr","[{\"type\":\"folder\",\"id\":"+id+"}]"));
    }
    public static void deleteTorrent(String id) throws Exception {
        call("delete", new JSONObject().put("delete_arr","[{\"type\":\"torrent\",\"id\":"+id+"}]"));
    }

    public static void renameFile(String id, String name) throws Exception {
        call("rename_file", new JSONObject().put("file_id",id).put("rename_to",name));
    }
    public static void renameFolder(String id, String name) throws Exception {
        call("rename_folder", new JSONObject().put("folder_id",id).put("rename_to",name));
    }

    public static List<SeedrFile> search(String q) throws Exception {
        return parseContents(call("search_files",
                new JSONObject().put("search_query", q)));
    }

    // ------------------------------------------------------------------ routing

    private static JSONObject call(String func, JSONObject body) throws Exception {
        String mode = Store.getStr(KEY_MODE,"oauth");
        if ("v1".equals(mode)) return v1mapped(func, body);
        return resourceCallWithRefresh(func, body);
    }

    private static JSONObject resourceCallWithRefresh(String func, JSONObject body) throws Exception {
        JSONObject r = resourceCall(func, body);
        String err = r.optString("error","");
        if ("expired_token".equals(err)||"access_denied".equals(err)) {
            // try refresh
            String rt = Store.getStr(KEY_REFRESH,"");
            if (!rt.isEmpty()) {
                try {
                    JSONObject ref = postForm(TOKEN_URL, new JSONObject()
                            .put("grant_type","refresh_token")
                            .put("client_id",CLIENT_ID)
                            .put("refresh_token",rt));
                    String newTok = ref.optString("access_token","");
                    if (!newTok.isEmpty()) {
                        Store.putStr(KEY_TOKEN, newTok);
                        Store.putStr(KEY_REFRESH, ref.optString("refresh_token",""));
                        return resourceCall(func, body);
                    }
                } catch (Exception ignored){}
            }
            // fall back to v0.1/p with original PAT
            Store.putStr(KEY_MODE,"v1");
            return v1mapped(func, body);
        }
        return r;
    }

    /** Map resource.php func names to v0.1/p REST calls */
    private static JSONObject v1mapped(String func, JSONObject body) throws Exception {
        String tok = pat();
        if (tok.isEmpty()) tok = Store.getStr(KEY_TOKEN,"");
        switch(func) {
            case "get_settings":
            case "get_memory_bandwidth":
                return v1Call("GET","/user",null,tok);
            case "list_contents": {
                String id = body!=null ? body.optString("content_id","0"):"0";
                return v1Call("GET", id.equals("0") ? "/folder" : "/folder/"+id, null, tok);
            }
            case "add_torrent": {
                JSONObject b = new JSONObject();
                if (body!=null) {
                    if (body.has("torrent_magnet")) b.put("magnet", body.getString("torrent_magnet"));
                    if (body.has("folder_id"))      b.put("folder_id", body.getString("folder_id"));
                }
                return v1Call("POST","/torrent",b,tok);
            }
            case "fetch_file": {
                String id = body!=null ? body.optString("folder_file_id","0"):"0";
                return v1Call("GET","/file/"+id,null,tok);
            }
            case "delete": {
                if (body==null) return new JSONObject();
                JSONArray arr = new JSONArray(body.optString("delete_arr","[]"));
                if (arr.length()==0) return new JSONObject();
                JSONObject item = arr.getJSONObject(0);
                return v1Call("DELETE","/"+item.optString("type")+"/"+item.optString("id"),null,tok);
            }
            case "rename_file":
                return v1Call("PATCH","/file/"+body.optString("file_id"),
                        new JSONObject().put("name",body.optString("rename_to")),tok);
            case "rename_folder":
                return v1Call("PATCH","/folder/"+body.optString("folder_id"),
                        new JSONObject().put("name",body.optString("rename_to")),tok);
            case "search_files":
                return v1Call("GET","/search?q="+URLEncoder.encode(
                        body!=null?body.optString("search_query",""):"","UTF-8"),null,tok);
            default: throw new Exception("Unknown func: "+func);
        }
    }

    // ------------------------------------------------------------------ parsing

    private static AccountInfo parseAccount(JSONObject r) {
        AccountInfo a = new AccountInfo();
        JSONObject src = r.has("username") ? r
                : (r.optJSONObject("account")!=null ? r.optJSONObject("account") : r);
        a.username   = src.optString("username", src.optString("name", src.optString("display_name","")));
        a.email      = src.optString("email","");
        a.spaceUsed  = r.optLong("bandwidth_used",  r.optLong("storage_used",  r.optLong("space_used", 0)));
        a.spaceTotal = r.optLong("bandwidth_max",   r.optLong("storage_max",   r.optLong("space_max",  0)));
        if (a.spaceTotal==0) {
            JSONObject mem = r.optJSONObject("memory");
            if (mem!=null) {
                a.spaceUsed  = mem.optLong("bandwidth_used",0);
                a.spaceTotal = mem.optLong("bandwidth_max", 0);
            }
        }
        return a;
    }

    private static List<SeedrFile> parseContents(JSONObject r) {
        List<SeedrFile> out = new ArrayList<>();
        // active torrents
        JSONArray tor = r.optJSONArray("torrents");
        if (tor==null) tor=r.optJSONArray("transferring");
        if (tor!=null) for (int i=0;i<tor.length();i++) {
            JSONObject o=tor.optJSONObject(i); if(o==null) continue;
            SeedrFile f=new SeedrFile(); f.isTorrent=true;
            f.id=s(o,"id"); f.name=o.optString("name","Downloading…");
            f.size=o.optLong("size",0);
            Object prog=o.opt("progress");
            if (prog instanceof Number) f.progress=String.valueOf((int)Math.round(((Number)prog).doubleValue()*100));
            else f.progress=prog!=null?prog.toString():"0";
            f.progressUrl=o.optString("progress_url","");
            f.seeders=o.optInt("seeders",0);
            f.peers=o.optInt("downloading_from",o.optInt("peers",0));
            f.dlRate=o.optLong("download_rate",0);
            out.add(f);
        }
        // folders
        JSONArray fol=r.optJSONArray("folders");
        if (fol!=null) for (int i=0;i<fol.length();i++) {
            JSONObject o=fol.optJSONObject(i); if(o==null) continue;
            SeedrFile f=new SeedrFile(); f.isFolder=true;
            f.id=s(o,"id"); f.name=o.optString("name",""); f.size=o.optLong("size",0);
            out.add(f);
        }
        // files
        JSONArray fil=r.optJSONArray("folder_files");
        if (fil==null) fil=r.optJSONArray("files");
        if (fil==null) fil=r.optJSONArray("results");
        if (fil!=null) for (int i=0;i<fil.length();i++) {
            JSONObject o=fil.optJSONObject(i); if(o==null) continue;
            SeedrFile f=new SeedrFile();
            f.id=o.has("folder_file_id")?s(o,"folder_file_id"):s(o,"id");
            f.name=o.optString("name",""); f.ext=o.optString("ext","");
            f.size=o.optLong("size",0);
            out.add(f);
        }
        return out;
    }

    private static String s(JSONObject o,String k){return String.valueOf(o.opt(k));}

    // ------------------------------------------------------------------ HTTP

    private static JSONObject resourceCall(String func, JSONObject body) throws Exception {
        String tok = Store.getStr(KEY_TOKEN,"");
        if (tok.isEmpty()) tok = pat();
        String url = RESOURCE+"?access_token="+URLEncoder.encode(tok,"UTF-8")
                             +"&func="+URLEncoder.encode(func,"UTF-8");
        HttpURLConnection c = conn(url,"POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type","application/x-www-form-urlencoded");
        StringBuilder sb=new StringBuilder();
        if (body!=null){java.util.Iterator<String> it=body.keys();
            while(it.hasNext()){String k=it.next();Object v=body.opt(k);if(v==null)continue;
                if(sb.length()>0)sb.append('&');
                sb.append(URLEncoder.encode(k,"UTF-8")).append('=')
                  .append(URLEncoder.encode(String.valueOf(v),"UTF-8"));}}
        byte[] b=sb.toString().getBytes(StandardCharsets.UTF_8);
        c.setRequestProperty("Content-Length",String.valueOf(b.length));
        try(OutputStream o=c.getOutputStream()){o.write(b);}
        return readJson(c);
    }

    private static JSONObject v1Call(String method, String path, JSONObject body, String bearer) throws Exception {
        HttpURLConnection c=conn(V1_BASE+path,method);
        c.setRequestProperty("Authorization","Bearer "+bearer);
        c.setRequestProperty("Accept","application/json");
        if (body!=null&&!method.equals("GET")&&!method.equals("DELETE")){
            byte[] b=body.toString().getBytes(StandardCharsets.UTF_8);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type","application/json");
            c.setRequestProperty("Content-Length",String.valueOf(b.length));
            try(OutputStream o=c.getOutputStream()){o.write(b);}
        }
        return readJson(c);
    }

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

    private static HttpURLConnection conn(String url, String method) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT); c.setReadTimeout(TIMEOUT);
        c.setRequestMethod(method);
        c.setRequestProperty("User-Agent","ZHPlay/1.0");
        return c;
    }

    private static JSONObject readJson(HttpURLConnection c) throws Exception {
        int code=c.getResponseCode();
        java.io.InputStream in=(code>=400)?c.getErrorStream():c.getInputStream();
        if (in==null) return new JSONObject().put("ok",code<400);
        BufferedReader br=new BufferedReader(new InputStreamReader(in,"UTF-8"));
        StringBuilder sb=new StringBuilder(); String ln;
        while((ln=br.readLine())!=null) sb.append(ln);
        br.close();
        String text=sb.toString().trim();
        if (text.isEmpty()) return new JSONObject().put("ok",code<400);
        if (text.startsWith("<")) throw new Exception("HTML response (HTTP "+code+") — token rejected by this endpoint");
        if (text.startsWith("[")) {
            JSONArray arr=new JSONArray(text);
            JSONObject w=new JSONObject();
            if (arr.length()>0){JSONObject f=arr.optJSONObject(0);
                if (f!=null&&(f.has("folder_file_id")||f.has("ext"))) w.put("folder_files",arr);
                else if (f!=null&&f.has("folders")) w.put("folders",arr);
                else w.put("results",arr);}
            return w;
        }
        JSONObject j=new JSONObject(text);
        if ((code==401||code==403)&&!j.has("error")) j.put("error","access_denied");
        return j;
    }

    static String fmtSpeed(long bps){
        if(bps>1_048_576) return String.format(Locale.US,"%.1f MB/s",bps/1_048_576.0);
        if(bps>1024)      return String.format(Locale.US,"%.0f KB/s",bps/1024.0);
        return bps+" B/s";
    }
}
