package com.mytv.player;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Control the TV from anywhere, through the admin's own Firebase (REST, no SDK):
 *   phone  → POST users/{uid}/tvs/{tv}/cmd/{id} = {p: path, q: params, t: time}
 *   TV     ← listens (server-sent events), runs it on itself (its own web server), writes res/{id}
 *   phone  ← reads res/{id}
 * Only the signed-in Google account can read / write users/{its uid} (database rules).
 * Also proves "same account" on the same Wi-Fi: the TV checks the phone's Firebase token → no PIN.
 * Settings in admin/access.json: firebase_api_key, firebase_db_url.
 */
final class Cloud {
    private Cloud() {
    }

    // ------------------------------------------------------------------ settings & session

    static String apiKey() {
        return Auth.cfg().optString("firebase_api_key", "").trim();
    }

    static String dbUrl() {
        String u = Auth.cfg().optString("firebase_db_url", "").trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    static boolean enabled() {
        return !apiKey().isEmpty() && dbUrl().startsWith("https://");
    }

    static boolean signedIn() {
        return enabled() && !Store.getStr("fb_refresh", "").isEmpty() && !uid().isEmpty();
    }

    static String uid() {
        return Store.getStr("fb_uid", "");
    }

    static String refreshToken() {
        return Store.getStr("fb_refresh", "");
    }

    /** This device's id in the cloud (stable). */
    static String deviceId() {
        String id = Store.getStr("cloud_id", "");
        if (id.isEmpty()) {
            id = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            Store.putStr("cloud_id", id);
        }
        return id;
    }

    /** Google ID token (from "Sign in with Google") → a Firebase session for the same account. Blocking. */
    static void signInWithGoogle(String googleIdToken) throws Exception {
        if (!enabled() || googleIdToken == null || googleIdToken.isEmpty()) return;
        JSONObject body = new JSONObject()
                .put("postBody", "id_token=" + URLEncoder.encode(googleIdToken, "UTF-8") + "&providerId=google.com")
                .put("requestUri", "http://localhost")
                .put("returnIdpCredential", true)
                .put("returnSecureToken", true);
        JSONObject r = new JSONObject(http("POST", "https://identitytoolkit.googleapis.com/v1/accounts:signInWithIdp?key=" + apiKey(), body.toString()));
        save(r.optString("localId"), r.optString("refreshToken"), r.optString("idToken"), r.optLong("expiresIn", 3600));
    }

    /** The TV takes the phone's session when the phone signs it in. */
    static void adopt(String uid, String refresh) {
        if (uid == null || uid.isEmpty() || refresh == null || refresh.isEmpty()) return;
        save(uid, refresh, "", 0);
    }

    static void signOut() {
        save("", "", "", 0);
    }

    private static void save(String uid, String refresh, String id, long expiresIn) {
        Store.putStr("fb_uid", uid == null ? "" : uid);
        Store.putStr("fb_refresh", refresh == null ? "" : refresh);
        Store.putStr("fb_id", id == null ? "" : id);
        Store.putLong("fb_exp", id == null || id.isEmpty() ? 0 : System.currentTimeMillis() + (expiresIn - 120) * 1000L);
    }

    /** A valid Firebase ID token (renewed with the refresh token when needed). Blocking. */
    static synchronized String idToken() throws Exception {
        String id = Store.getStr("fb_id", "");
        if (!id.isEmpty() && System.currentTimeMillis() < Store.getLong("fb_exp", 0)) return id;
        String rt = refreshToken();
        if (rt.isEmpty()) throw new Exception("Not signed in to ZH Play cloud");
        JSONObject r;
        try {
            r = new JSONObject(http("POST", "https://securetoken.googleapis.com/v1/token?key=" + apiKey(),
                    "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(rt, "UTF-8")));
        } catch (Exception e) {
            String m = String.valueOf(e.getMessage());
            if (m.contains("USER_NOT_FOUND") || m.contains("USER_DISABLED") || m.contains("INVALID_REFRESH_TOKEN")) {
                // the admin removed this account: back to the sign-in screen
                signOut();
                Auth.signOut();
            }
            throw e;
        }
        save(r.optString("user_id", uid()), r.optString("refresh_token", rt), r.optString("id_token"), r.optLong("expires_in", 3600));
        return Store.getStr("fb_id", "");
    }

    /** The current token if it's still fresh (no network) — sent to TVs on the same Wi-Fi. */
    static String cachedIdToken() {
        if (!signedIn() || System.currentTimeMillis() >= Store.getLong("fb_exp", 0)) return "";
        return Store.getStr("fb_id", "");
    }

    private static final Map<String, Object[]> verified = new HashMap<>();

    /** TV side: the Firebase account (uid) a phone's token belongs to, or null. Cached for 30 min. */
    static String verify(String token) {
        if (token == null || token.isEmpty() || !enabled()) return null;
        synchronized (verified) {
            Object[] v = verified.get(token);
            if (v != null && System.currentTimeMillis() < (Long) v[1]) return (String) v[0];
        }
        try {
            JSONObject r = new JSONObject(http("POST", "https://identitytoolkit.googleapis.com/v1/accounts:lookup?key=" + apiKey(),
                    new JSONObject().put("idToken", token).toString()));
            String u = r.getJSONArray("users").getJSONObject(0).optString("localId", "");
            synchronized (verified) {
                if (verified.size() > 50) verified.clear();
                verified.put(token, new Object[]{u, System.currentTimeMillis() + 30 * 60 * 1000L});
            }
            return u.isEmpty() ? null : u;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ database

    private static String db(String rel) throws Exception {
        return dbUrl() + "/users/" + uid() + rel + ".json?auth=" + URLEncoder.encode(idToken(), "UTF-8");
    }

    /** The TVs of this account: id → {name, seen, ip}. */
    static JSONObject tvs() throws Exception {
        String s = http("GET", db("/tvinfo"), null);
        return s == null || s.trim().equals("null") ? new JSONObject() : new JSONObject(s);
    }

    private static final Map<String, Object[]> stateCache = new HashMap<>();

    /** Phone: runs one command on a TV through the cloud and returns its answer (≈ 0.5–2 s). */
    static JSONObject send(String tvId, String path, Map<String, String> params) throws Exception {
        if (!signedIn()) throw new Exception("Sign in on this phone to control the TV from anywhere");
        if ("/state".equals(path)) {                        // the remote asks often: answer from a 4 s cache
            synchronized (stateCache) {
                Object[] c = stateCache.get(tvId);
                if (c != null && System.currentTimeMillis() - (Long) c[1] < 4000) return (JSONObject) c[0];
            }
        }
        JSONObject q = new JSONObject();
        if (params != null) for (Map.Entry<String, String> e : params.entrySet()) q.put(e.getKey(), e.getValue() == null ? "" : e.getValue());
        JSONObject cmd = new JSONObject().put("p", path).put("q", q).put("t", new JSONObject().put(".sv", "timestamp"));
        String id = new JSONObject(http("POST", db("/tvs/" + tvId + "/cmd"), cmd.toString())).getString("name");
        long until = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < until) {
            Thread.sleep(350);
            String s = http("GET", db("/tvs/" + tvId + "/res/" + id), null);
            if (s != null && !s.trim().equals("null")) {
                try {
                    http("DELETE", db("/tvs/" + tvId + "/res/" + id), null);
                } catch (Exception ignored) {
                }
                JSONObject r = new JSONObject(s);
                if ("/state".equals(path)) {
                    synchronized (stateCache) {
                        stateCache.put(tvId, new Object[]{r, System.currentTimeMillis()});
                    }
                }
                return r;
            }
        }
        try {
            http("DELETE", db("/tvs/" + tvId + "/cmd/" + id), null);   // nobody took it
        } catch (Exception ignored) {
        }
        throw new Exception("The TV didn't answer — is it switched on and online?");
    }

    // ------------------------------------------------------------------ TV: listen for commands

    private static volatile boolean tvRunning;

    /** TV: listen for commands from this account's phones, anywhere (started with the remote service). */
    static void startTv(final Context app) {
        if (tvRunning) return;
        tvRunning = true;
        Thread t = new Thread(() -> {
            long beat = 0;
            int fails = 0;
            while (true) {
                try {
                    if (!signedIn() || Auth.locked(app)) {
                        Thread.sleep(30000);
                        continue;
                    }
                    if (System.currentTimeMillis() - beat > 5 * 60 * 1000L) {        // "this TV is online"
                        JSONObject info = new JSONObject().put("name", Discovery.deviceName(app)).put("ip", Net.ip())
                                .put("seen", new JSONObject().put(".sv", "timestamp"));
                        http("PUT", db("/tvinfo/" + deviceId()), info.toString());
                        beat = System.currentTimeMillis();
                        Store.putLong("fb_beat", beat);
                        Store.putStr("fb_err", "");
                    }
                    listen(app);                                  // returns when the stream ends / token expires
                    fails = 0;
                } catch (Throwable e) {
                    Store.putStr("fb_err", String.valueOf(e.getMessage()));
                    fails++;
                    try {
                        Thread.sleep(Math.min(60000, 3000L * fails));
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }, "zh-cloud");
        t.setDaemon(true);
        t.start();
    }

    private static final Set<String> done = new HashSet<>();

    private static void listen(Context app) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(db("/tvs/" + deviceId() + "/cmd")).openConnection();
        c.setRequestProperty("Accept", "text/event-stream");
        c.setConnectTimeout(15000);
        c.setReadTimeout(70000);                                  // Firebase sends keep-alive every ~30 s
        c.setInstanceFollowRedirects(true);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            String event = "", line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("event:")) event = line.substring(6).trim();
                else if (line.startsWith("data:")) {
                    String data = line.substring(5).trim();
                    if ("auth_revoked".equals(event) || "cancel".equals(event)) return;      // reconnect with a new token
                    if (!"put".equals(event) && !"patch".equals(event)) continue;
                    JSONObject d = new JSONObject(data);
                    String path = d.optString("path", "/");
                    Object v = d.opt("data");
                    if (!(v instanceof JSONObject)) continue;
                    JSONObject o = (JSONObject) v;
                    if ("/".equals(path)) {
                        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                            String id = it.next();
                            Object cmd = o.opt(id);
                            if (cmd instanceof JSONObject) run(app, id, (JSONObject) cmd);
                        }
                    } else {
                        run(app, path.substring(1).split("/")[0], o);
                    }
                }
            }
        } finally {
            c.disconnect();
        }
    }

    /** Runs one command on this TV (through its own web server) and writes the answer back. */
    private static void run(Context app, final String id, final JSONObject cmd) {
        synchronized (done) {
            if (!done.add(id)) return;
            if (done.size() > 500) done.clear();
        }
        new Thread(() -> {
            try {
                long t = cmd.optLong("t", 0);
                if (t > 0 && System.currentTimeMillis() - t > 120000) {          // waited too long (TV was off): drop
                    http("DELETE", db("/tvs/" + deviceId() + "/cmd/" + id), null);
                    return;
                }
                Map<String, String> q = new HashMap<>();
                JSONObject qo = cmd.optJSONObject("q");
                if (qo != null) for (Iterator<String> it = qo.keys(); it.hasNext(); ) {
                    String k = it.next();
                    q.put(k, qo.optString(k));
                }
                JSONObject res;
                try {
                    res = TvClient.postDirect("127.0.0.1", cmd.optString("p", "/ping"), q);
                } catch (Exception e) {
                    res = new JSONObject().put("ok", false).put("msg", "TV: " + e.getMessage());
                }
                http("PUT", db("/tvs/" + deviceId() + "/res/" + id), res.toString());
                http("DELETE", db("/tvs/" + deviceId() + "/cmd/" + id), null);
            } catch (Exception ignored) {
            }
        }).start();
    }

    // ------------------------------------------------------------------ http

    static String http(String method, String url, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(20000);
        c.setRequestMethod(method);
        if (body != null) {
            c.setDoOutput(true);
            boolean json = body.startsWith("{") || body.startsWith("[") || body.startsWith("\"");
            c.setRequestProperty("Content-Type", json ? "application/json" : "application/x-www-form-urlencoded");
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(b.length);
            try (OutputStream o = c.getOutputStream()) {
                o.write(b);
            }
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        StringBuilder sb = new StringBuilder();
        if (in != null) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) sb.append(l);
            }
        }
        c.disconnect();
        if (code >= 400) {
            String m = sb.toString();
            try {
                JSONObject e = new JSONObject(m);
                Object err = e.opt("error");
                m = err instanceof JSONObject ? ((JSONObject) err).optString("message", m) : String.valueOf(err);
            } catch (Exception ignored) {
            }
            throw new Exception("Cloud " + code + ": " + m);
        }
        return sb.toString();
    }
}
