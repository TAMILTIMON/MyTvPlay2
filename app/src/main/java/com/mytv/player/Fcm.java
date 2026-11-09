package com.mytv.player;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;

/**
 * Sends a notification to every ZH Play straight from the admin's phone (no GitHub):
 * the Firebase service-account key (imported once in the admin panel, kept only on that phone) signs a
 * Google login (JWT, RS256) → access token → Firebase Cloud Messaging HTTP v1 → topic "all".
 */
final class Fcm {
    private Fcm() {
    }

    private static final String KEY = "fcm_sa";
    private static String token;
    private static long tokenUntil;

    static boolean ready() {
        return !Store.getStr(KEY, "").isEmpty();
    }

    /** Checks and keeps the key file's content. Returns null when fine, else what's wrong. */
    static String importKey(String json) {
        try {
            JSONObject o = new JSONObject(json);
            if (!"service_account".equals(o.optString("type"))) return T.t("That isn't a Firebase service-account key (type must be service_account)");
            if (o.optString("client_email").isEmpty() || !o.optString("private_key").contains("PRIVATE KEY") || o.optString("project_id").isEmpty())
                return T.t("The key file is incomplete");
            privateKey(o.optString("private_key"));          // must load
            Store.putStr(KEY, o.toString());
            token = null;
            return null;
        } catch (Exception e) {
            return T.t("Can't read the key") + ": " + e.getMessage();
        }
    }

    static void forget() {
        Store.putStr(KEY, "");
        token = null;
    }

    static String projectOf() {
        try {
            return new JSONObject(Store.getStr(KEY, "{}")).optString("project_id");
        } catch (Exception e) {
            return "";
        }
    }

    /** Sends to every ZH Play (topic "all"). Blocking. A message for the admin. */
    static String send(String title, String body, String link, String bid) {
        return send(title, body, link, bid, "auto");
    }

    static String send(String title, String body, String link, String bid, String open) {
        return send(title, body, link, bid, open, "");
    }

    static String send(String title, String body, String link, String bid, String open, String img) {
        try {
            JSONObject sa = new JSONObject(Store.getStr(KEY, "{}"));
            String at = accessToken(sa);
            JSONObject data = new JSONObject().put("kind", "broadcast").put("bid", bid == null ? "" : bid).put("url", link == null ? "" : link)
                    .put("open", open == null ? "auto" : open)
                    .put("title", title).put("body", body);
            JSONObject msg = new JSONObject().put("message", new JSONObject()
                    .put("topic", "all")
                    .put("notification", Thumb.web(img).isEmpty() ? new JSONObject().put("title", title).put("body", body)
                            : new JSONObject().put("title", title).put("body", body).put("image", Thumb.web(img)))
                    .put("data", data)
                    .put("android", new JSONObject().put("priority", "high")
                            .put("notification", new JSONObject().put("channel_id", Broadcast.CH).put("click_action", "com.tamil.castplay.OPEN_PUSH"))));
            String r = post("https://fcm.googleapis.com/v1/projects/" + sa.optString("project_id") + "/messages:send",
                    msg.toString(), "application/json", at);
            return new JSONObject(r).has("name") ? "📣 " + T.t("Sent to everyone") : "⚠ " + r;
        } catch (Exception e) {
            return "⚠ " + T.t("Push failed") + ": " + e.getMessage();
        }
    }

    /** OAuth access token for FCM (cached ~55 min). */
    private static synchronized String accessToken(JSONObject sa) throws Exception {
        return accessToken(sa, "https://www.googleapis.com/auth/firebase.messaging");
    }

    private static final java.util.Map<String, Object[]> tokens = new java.util.HashMap<>();

    private static synchronized String accessToken(JSONObject sa, String scope) throws Exception {
        Object[] c = tokens.get(scope);
        if (c != null && System.currentTimeMillis() < (Long) c[1]) return (String) c[0];
        String jwt = jwt(sa, System.currentTimeMillis() / 1000, scope);
        String r = post("https://oauth2.googleapis.com/token",
                "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", "UTF-8") + "&assertion=" + jwt,
                "application/x-www-form-urlencoded", null);
        JSONObject o = new JSONObject(r);
        String t = o.getString("access_token");
        tokens.put(scope, new Object[]{t, System.currentTimeMillis() + (o.optLong("expires_in", 3600) - 300) * 1000L});
        return t;
    }

    /** Admin: deletes a user's ZH Play cloud account (Firebase Authentication). They can sign in again later. */
    static String deleteUser(String uid) {
        if (!ready()) return "✗ " + T.t("Import the Firebase key first (Push setup)");
        try {
            JSONObject sa = new JSONObject(Store.getStr(KEY, "{}"));
            String at = accessToken(sa, "https://www.googleapis.com/auth/cloud-platform");
            post("https://identitytoolkit.googleapis.com/v1/projects/" + sa.optString("project_id") + "/accounts:delete",
                    new JSONObject().put("localId", uid).toString(), "application/json", at);
            return null;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    private static synchronized String accessTokenOld(JSONObject sa) throws Exception {
        if (token != null && System.currentTimeMillis() < tokenUntil) return token;
        String jwt = jwt(sa, System.currentTimeMillis() / 1000);
        String r = post("https://oauth2.googleapis.com/token",
                "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", "UTF-8") + "&assertion=" + jwt,
                "application/x-www-form-urlencoded", null);
        JSONObject o = new JSONObject(r);
        token = o.getString("access_token");
        tokenUntil = System.currentTimeMillis() + (o.optLong("expires_in", 3600) - 300) * 1000L;
        return token;
    }

    /** The signed Google login (RS256) for this service account. */
    static String jwt(JSONObject sa, long now) throws Exception {
        return jwt(sa, now, "https://www.googleapis.com/auth/firebase.messaging");
    }

    static String jwt(JSONObject sa, long now, String scope) throws Exception {
        String header = b64url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        JSONObject c = new JSONObject().put("iss", sa.getString("client_email"))
                .put("scope", scope)
                .put("aud", "https://oauth2.googleapis.com/token").put("iat", now).put("exp", now + 3600);
        String claims = b64url(c.toString().getBytes(StandardCharsets.UTF_8));
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(privateKey(sa.getString("private_key")));
        s.update((header + "." + claims).getBytes(StandardCharsets.UTF_8));
        return header + "." + claims + "." + b64url(s.sign());
    }

    private static PrivateKey privateKey(String pem) throws Exception {
        String b = pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(b64decode(b)));
    }

    private static String post(String url, String body, String type, String bearer) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(15000);
        h.setReadTimeout(20000);
        h.setRequestMethod("POST");
        h.setDoOutput(true);
        h.setRequestProperty("Content-Type", type);
        if (bearer != null) h.setRequestProperty("Authorization", "Bearer " + bearer);
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        h.setFixedLengthStreamingMode(b.length);
        try (OutputStream o = h.getOutputStream()) {
            o.write(b);
        }
        int code = h.getResponseCode();
        StringBuilder sb = new StringBuilder();
        java.io.InputStream in = code >= 400 ? h.getErrorStream() : h.getInputStream();
        if (in != null) try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) sb.append(l);
        }
        if (code >= 400) {
            String m = sb.toString();
            try {
                JSONObject e = new JSONObject(m);
                Object err = e.opt("error");
                m = err instanceof JSONObject ? ((JSONObject) err).optString("message", m) : e.optString("error_description", String.valueOf(err));
            } catch (Exception ignored) {
            }
            throw new Exception(code + " " + m);
        }
        return sb.toString();
    }

    // ---- base64 (own: works on every Android version and off-device)
    private static final char[] A = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();

    static String b64url(byte[] d) {
        StringBuilder sb = new StringBuilder((d.length * 4 + 2) / 3);
        for (int i = 0; i < d.length; i += 3) {
            int n = (d[i] & 0xff) << 16 | (i + 1 < d.length ? (d[i + 1] & 0xff) << 8 : 0) | (i + 2 < d.length ? d[i + 2] & 0xff : 0);
            sb.append(A[n >> 18 & 63]).append(A[n >> 12 & 63]);
            if (i + 1 < d.length) sb.append(A[n >> 6 & 63]);
            if (i + 2 < d.length) sb.append(A[n & 63]);
        }
        return sb.toString();
    }

    static byte[] b64decode(String s) {
        s = s.replace('-', '+').replace('_', '/').replace("=", "");
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        int buf = 0, bits = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            int v = ch >= 'A' && ch <= 'Z' ? ch - 'A' : ch >= 'a' && ch <= 'z' ? ch - 'a' + 26 : ch >= '0' && ch <= '9' ? ch - '0' + 52 : ch == '+' ? 62 : ch == '/' ? 63 : -1;
            if (v < 0) continue;
            buf = buf << 6 | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                o.write(buf >> bits & 0xff);
            }
        }
        return o.toByteArray();
    }
}
