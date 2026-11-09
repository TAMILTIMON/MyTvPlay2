package com.mytv.player;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Map;

/** Phone side: talks to a TV (with the saved pairing token). Call from background threads. */
public final class TvClient {
    private TvClient() {
    }

    public static final class AuthNeeded extends Exception {
        AuthNeeded() {
            super("PIN needed");
        }
    }

    public static String token(String ip) {
        return Store.getStr("tok_" + ip, "");
    }

    public static void setToken(String ip, String t) {
        Store.putStr("tok_" + ip, t == null ? "" : t);
        if (t != null && !t.isEmpty()) TvGroup.remember(ip);
    }

    public static JSONObject post(String ip, String path, Map<String, String> params) throws Exception {
        TvGroup.fanOut(ip, path, params);           // a TV group: the same action on the other TVs too
        return postDirect(ip, path, params);
    }

    private static final Map<String, Long> away = new java.util.HashMap<>();

    /** One TV only (used for the group copies). Same Wi-Fi first; through ZH Play cloud when away. */
    public static JSONObject postDirect(String ip, String path, Map<String, String> params) throws Exception {
        if (ip.startsWith("cloud:")) return Cloud.send(ip.substring(6), path, params);
        String cid = Store.getStr("cloud_tv_" + ip, "");
        boolean canCloud = !cid.isEmpty() && Cloud.signedIn();
        Long a;
        synchronized (away) {
            a = away.get(ip);
        }
        if (canCloud && a != null && System.currentTimeMillis() < a) return Cloud.send(cid, path, params);
        try {
            JSONObject r = directPost(ip, path, params);
            String id = r.optString("cloud_id", "");
            if (!id.isEmpty() && !id.equals(cid)) Store.putStr("cloud_tv_" + ip, id);
            synchronized (away) {
                away.remove(ip);
            }
            return r;
        } catch (AuthNeeded e) {
            throw e;
        } catch (java.io.IOException e) {
            if (!canCloud) throw e;
            synchronized (away) {
                away.put(ip, System.currentTimeMillis() + 60000);       // not on this Wi-Fi: use the cloud for a minute
            }
            return Cloud.send(cid, path, params);
        }
    }

    /** Encrypted when the TV supports it (ZH Play 8.9+); plain only for an older TV (with a warning). */
    private static JSONObject directPost(String ip, String path, Map<String, String> params) throws Exception {
        if (ZhCrypt.usable(ip)) {
            try {
                return securePost(ip, path, params, null, true);
            } catch (ZhCrypt.Unsupported u) {
                ZhCrypt.markOld(ip);
                warnOld();
            }
        }
        return plainPost(ip, path, params);
    }

    private static volatile boolean warned;

    private static void warnOld() {
        if (warned) return;
        warned = true;
        Remote.MAIN.post(() -> {
            android.app.Activity a = App.top();
            if (a != null) ZhPopup.banner(a, "⚠ " + T.t("Update ZH Play on the TV — until then this connection isn't encrypted"));
        });
    }

    /** One request inside the encryption: {p, q, t, z} or a GET {g}. */
    private static JSONObject securePost(String ip, String path, Map<String, String> params, String get, boolean retry) throws Exception {
        ZhCrypt.Session se = ZhCrypt.session(ip, RemoteService.PORT);
        JSONObject env = new JSONObject().put("t", token(ip)).put("z", Cloud.cachedIdToken());
        if (get != null) env.put("g", get);
        else {
            JSONObject q = new JSONObject();
            if (params != null) for (Map.Entry<String, String> e : params.entrySet()) q.put(e.getKey(), e.getValue() == null ? "" : e.getValue());
            env.put("p", path).put("q", q);
        }
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + ip + ":" + RemoteService.PORT + "/e").openConnection();
        JSONObject r;
        try {
            c.setDoOutput(true);
            c.setConnectTimeout(5000);
            c.setReadTimeout(get != null ? 30000 : 15000);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c.setRequestProperty("X-ZH-S", se.sid);
            try (OutputStream o = c.getOutputStream()) {
                o.write(("d=" + URLEncoder.encode(ZhCrypt.seal(se.key, env.toString()), "UTF-8")).getBytes("UTF-8"));
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            StringBuilder sb = new StringBuilder();
            if (in != null) {
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) sb.append(new String(b, 0, n, "UTF-8"));
                in.close();
            }
            String body = sb.toString().trim();
            if (!body.startsWith("{")) throw new ZhCrypt.Unsupported();
            r = new JSONObject(body);
        } finally {
            c.disconnect();
        }
        if (r.optBoolean("rehello")) {                          // the TV restarted / forgot us: new key, once
            ZhCrypt.drop(ip);
            if (retry) return securePost(ip, path, params, get, false);
            throw new java.io.IOException("TV refused the encrypted session");
        }
        if (!r.has("d")) throw new ZhCrypt.Unsupported();
        JSONObject inner = new JSONObject(ZhCrypt.open(se.key, r.getString("d")));
        if (inner.optInt("c") == 401) throw new AuthNeeded();
        if (get != null) {
            JSONObject g = new JSONObject().put("c", inner.optInt("c"));
            g.put("x", inner.optString("x"));
            return g;
        }
        String b = inner.optString("b");
        if (b.isEmpty()) return new JSONObject();
        try {
            return new JSONObject(b);
        } catch (Exception e) {
            return new JSONObject().put("ok", inner.optInt("c") < 400).put("raw", b);
        }
    }

    /** A short-lived pass for a plain connection (upload, screen cast, web remote) — the real key never travels in the clear. */
    public static String ticket(String ip, long ttlMs, boolean multi) {
        if (ZhCrypt.usable(ip) && !ZhCrypt.isOld(ip)) {
            try {
                Map<String, String> m = new java.util.HashMap<>();
                m.put("ttl", String.valueOf(ttlMs));
                m.put("multi", multi ? "1" : "0");
                String t = securePost(ip, "/ticket", m, null, true).optString("ticket");
                if (!t.isEmpty()) return t;
            } catch (Exception ignored) {
            }
        }
        return token(ip);                                      // older TV
    }

    private static JSONObject plainPost(String ip, String path, Map<String, String> params) throws Exception {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (body.length() > 0) body.append('&');
            body.append(URLEncoder.encode(e.getKey(), "UTF-8")).append('=')
                    .append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), "UTF-8"));
        }
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + ip + ":" + RemoteService.PORT + path).openConnection();
        try {
            c.setDoOutput(true);
            c.setConnectTimeout(5000);
            c.setReadTimeout(15000);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c.setRequestProperty("X-Token", token(ip));
            String zid = Cloud.cachedIdToken();
            if (!zid.isEmpty()) c.setRequestProperty("X-ZH-ID", zid);    // same Google account → the TV skips the PIN
            try (OutputStream o = c.getOutputStream()) {
                o.write(body.toString().getBytes("UTF-8"));
            }
            int code = c.getResponseCode();
            if (code == 401) throw new AuthNeeded();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            StringBuilder sb = new StringBuilder();
            byte[] b = new byte[8192];
            int n;
            if (in != null) {
                while ((n = in.read(b)) > 0) sb.append(new String(b, 0, n, "UTF-8"));
                in.close();
            }
            return sb.length() == 0 ? new JSONObject() : new JSONObject(sb.toString());
        } finally {
            c.disconnect();
        }
    }

    /** GET raw bytes (thumbnails). */
    public static byte[] bytes(String ip, String pathAndQuery) throws Exception {
        if (ZhCrypt.usable(ip) && !ZhCrypt.isOld(ip)) {
            try {
                JSONObject g = securePost(ip, null, null, pathAndQuery, true);
                return g.optInt("c") == 200 ? Fcm.b64decode(g.optString("x")) : null;
            } catch (ZhCrypt.Unsupported u) {
                ZhCrypt.markOld(ip);
            }
        }
        String sep = pathAndQuery.contains("?") ? "&" : "?";
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + ip + ":" + RemoteService.PORT + pathAndQuery
                + sep + "t=" + URLEncoder.encode(token(ip), "UTF-8")).openConnection();
        try {
            c.setConnectTimeout(5000);
            c.setReadTimeout(10000);
            if (c.getResponseCode() != 200) return null;
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] b = new byte[16384];
                int n;
                while ((n = in.read(b)) > 0) o.write(b, 0, n);
            }
            return o.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    /** multipart upload of one file (field name "file"). */
    public static JSONObject upload(String ip, String path, String fileName, byte[] data) throws Exception {
        String bd = "----tcp" + System.currentTimeMillis();
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + ip + ":" + RemoteService.PORT + path).openConnection();
        try {
            c.setDoOutput(true);
            c.setConnectTimeout(5000);
            c.setReadTimeout(60000);
            c.setRequestMethod("POST");
            c.setRequestProperty("X-Token", ticket(ip, 120000, false));      // one-time pass, not the lasting key
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + bd);
            try (OutputStream o = c.getOutputStream()) {
                o.write(("--" + bd + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                        + fileName.replace("\"", "") + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes("UTF-8"));
                o.write(data);
                o.write(("\r\n--" + bd + "--\r\n").getBytes("UTF-8"));
            }
            int code = c.getResponseCode();
            if (code == 401) throw new AuthNeeded();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            StringBuilder sb = new StringBuilder();
            byte[] b = new byte[8192];
            int n;
            if (in != null) {
                while ((n = in.read(b)) > 0) sb.append(new String(b, 0, n, "UTF-8"));
                in.close();
            }
            return sb.length() == 0 ? new JSONObject() : new JSONObject(sb.toString());
        } finally {
            c.disconnect();
        }
    }

    /** Enters the PIN; saves the token. Returns null when OK, else the error message. */
    public static String pair(String ip, String pin) {
        try {
            java.util.HashMap<String, String> p = new java.util.HashMap<>();
            p.put("pin", pin);
            JSONObject r = post(ip, "/pair", p);
            if (r.optBoolean("ok") && r.has("token")) {
                setToken(ip, r.getString("token"));
                return null;
            }
            return r.optString("msg", "Wrong PIN");
        } catch (Exception e) {
            return "Cannot reach the TV";
        }
    }
}
