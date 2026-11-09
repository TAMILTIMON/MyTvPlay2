package com.mytv.player;

import android.content.Context;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;

import org.json.JSONObject;

/**
 * Instant notifications to every ZH Play (Firebase Cloud Messaging, topic "all").
 * Set up from admin/access.json (firebase_app_id, firebase_api_key, firebase_db_url) — no google-services.json.
 * Sending is done by the GitHub Actions workflow "Broadcast" (it holds the service key as a secret);
 * the admin panel starts that workflow with the admin's GitHub token (kept only on the admin's phone).
 */
final class Push {
    private Push() {
    }

    static final String REPO = "TAMILTIMON/MyTvPlay2";
    private static volatile boolean ready;

    static String appId() {
        return Auth.cfg().optString("firebase_app_id", "").trim();
    }

    /** project id = the database name ("https://zhplay-default-rtdb…" → zhplay) unless set. */
    static String projectId() {
        String p = Auth.cfg().optString("firebase_project_id", "").trim();
        if (!p.isEmpty()) return p;
        String u = Cloud.dbUrl().replace("https://", "");
        int i = u.indexOf("-default-rtdb");
        return i > 0 ? u.substring(0, i) : "";
    }

    static void init(Context c) {
        if (ready) return;
        String app = appId(), key = Cloud.apiKey(), proj = projectId();
        if (app.isEmpty() || key.isEmpty() || proj.isEmpty()) return;          // not set up yet
        try {
            String[] parts = app.split(":");                                    // 1:<sender id>:android:<hash>
            if (FirebaseApp.getApps(c).isEmpty()) {
                FirebaseApp.initializeApp(c, new FirebaseOptions.Builder().setApiKey(key).setApplicationId(app)
                        .setProjectId(proj).setGcmSenderId(parts.length > 1 ? parts[1] : "").setDatabaseUrl(Cloud.dbUrl()).build());
            }
            Broadcast.channel(c);
            FirebaseMessaging.getInstance().subscribeToTopic("all");
            ready = true;
        } catch (Throwable ignored) {
            // Play services missing / wrong id: the in-app bar and the start-time reminder still work
        }
    }

    /** Admin: starts the GitHub "Broadcast" workflow, which sends the notification to every app. Blocking. */
    static String send(String title, String body, String bid) {
        return send(title, body, "", bid);
    }

    /** Direct from this phone if the Firebase key is imported (Admin → Push setup); else via GitHub. */
    static String send(String title, String body, String link, String bid) {
        return send(title, body, link, bid, "auto");
    }

    static String send(String title, String body, String link, String bid, String open) {
        return send(title, body, link, bid, open, "");
    }

    static String send(String title, String body, String link, String bid, String open, String img) {
        if (Fcm.ready()) return Fcm.send(title, body, link, bid, open, img);
        String tok = Store.getStr("gh_pat", "");
        if (tok.isEmpty()) return "✗ " + T.t("Import the Firebase key first (Admin → Push setup)");
        try {
            JSONObject payload = new JSONObject().put("event_type", "zh_broadcast")
                    .put("client_payload", new JSONObject().put("title", title).put("body", body).put("bid", bid == null ? "" : bid));
            java.net.HttpURLConnection h = (java.net.HttpURLConnection) new java.net.URL("https://api.github.com/repos/" + REPO + "/dispatches").openConnection();
            h.setRequestMethod("POST");
            h.setDoOutput(true);
            h.setRequestProperty("Authorization", "Bearer " + tok);
            h.setRequestProperty("Accept", "application/vnd.github+json");
            h.setRequestProperty("Content-Type", "application/json");
            byte[] b = payload.toString().getBytes("UTF-8");
            try (java.io.OutputStream o = h.getOutputStream()) {
                o.write(b);
            }
            int code = h.getResponseCode();
            if (code == 204) return "📣 " + T.t("Sending to everyone — it arrives in about a minute");
            return "✗ GitHub " + code + (code == 401 || code == 403 || code == 404 ? " — " + T.t("check the GitHub token (needs repo / actions access)") : "");
        } catch (Exception e) {
            return "✗ " + e.getMessage();
        }
    }
}
