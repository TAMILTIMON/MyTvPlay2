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
    }

    public static JSONObject post(String ip, String path, Map<String, String> params) throws Exception {
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
