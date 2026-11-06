package com.mytv.player;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Google sign-in + admin control from GitHub.
 *
 * The admin edits admin/access.json in the GitHub repo; every ZH Play reads it (cached, refreshed every
 * 15 minutes) and follows it. Emails in that file are SCRAMBLED: sha256("zhplay:" + email in lower case),
 * so the public repo never shows anyone's address (the app shows your scrambled form: Account → Admin).
 *
 *   web_client_id     the OAuth "Web client ID" from Google Cloud — sign-in is only required once this is set
 *   mode              "anyone" (every Google account) or "invite_only" (only "allowed" + "admins")
 *   admins / allowed / blocked   lists of scrambled emails
 *   features_off      tiles / features switched off for everyone, e.g. ["torrents","vpn","seedr"]
 *   user_features_off {"<scrambled email>": ["dj", …]} for one person
 *   min_build         builds below this number must update first
 *   message           a note shown once in the app (change the text to show a new one)
 *   maintenance / maintenance_message   switches the app off for everyone except admins
 *   login_from        "2026-11-08" — from when sign-in is required (else 1 day after this version first ran)
 */
final class Auth {
    private Auth() {
    }

    static final String CONFIG_URL = "https://raw.githubusercontent.com/TAMILTIMON/MyTvPlay2/main/admin/access.json";
    static final String EDIT_URL = "https://github.com/TAMILTIMON/MyTvPlay2/edit/main/admin/access.json";
    private static final String SALT = "zhplay:";
    private static final long NOTICE_MS = 24 * 3600 * 1000L;
    private static final long REFRESH_MS = 15 * 60 * 1000L;

    static volatile boolean noticeShown;

    static final String OK = "ok", NOTICE = "notice", SIGNIN = "signin", BLOCKED = "blocked",
            MAINTENANCE = "maintenance", UPDATE = "update";

    // ------------------------------------------------------------------ account

    static boolean signedIn() {
        return !email().isEmpty();
    }

    static String email() {
        return Store.getStr("au_email", "");
    }

    static String name() {
        return Store.getStr("au_name", "");
    }

    static void signIn(String email, String name, String photo) {
        Store.putStr("au_email", email == null ? "" : email.trim().toLowerCase(Locale.ROOT));
        Store.putStr("au_name", name == null ? "" : name);
        Store.putStr("au_photo", photo == null ? "" : photo);
        Store.putLong("au_at", System.currentTimeMillis());
    }

    static void signOut() {
        Store.putStr("au_email", "");
        Store.putStr("au_name", "");
        Store.putStr("au_photo", "");
    }

    /** sha256("zhplay:" + email) as hex — what goes in access.json. */
    static String hash(String email) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest((SALT + email.trim().toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format(Locale.ROOT, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ the admin file

    static JSONObject cfg() {
        try {
            return new JSONObject(Store.getStr("acc_cfg", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** Reads access.json from GitHub in the background (at most every 15 min unless forced). */
    static void refresh(final Context c, final boolean force, final Runnable done) {
        if (!force && System.currentTimeMillis() - Store.getLong("acc_cfg_at", 0) < REFRESH_MS) {
            if (done != null) Remote.MAIN.post(done);
            return;
        }
        new Thread(() -> {
            try {
                HttpURLConnection h = (HttpURLConnection) new URL(CONFIG_URL + "?t=" + System.currentTimeMillis() / 60000).openConnection();
                h.setConnectTimeout(8000);
                h.setReadTimeout(8000);
                h.setUseCaches(false);
                if (h.getResponseCode() == 200) {
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(h.getInputStream(), StandardCharsets.UTF_8))) {
                        String l;
                        while ((l = r.readLine()) != null) sb.append(l).append('\n');
                    }
                    JSONObject o = new JSONObject(sb.toString());        // only valid JSON replaces the cache
                    Store.putStr("acc_cfg", o.toString());
                    Store.putLong("acc_cfg_at", System.currentTimeMillis());
                }
            } catch (Exception ignored) {
                // offline / GitHub down / typo in the file: keep using the last good copy
            }
            if (done != null) Remote.MAIN.post(done);
        }).start();
    }

    /** The OAuth Web client ID: from access.json, or built into the app (res/values: google_web_client_id). */
    static String webClientId(Context c) {
        String id = cfg().optString("web_client_id", "").trim();
        if (id.endsWith(".apps.googleusercontent.com")) return id;
        try {
            String r = c.getString(R.string.google_web_client_id).trim();
            if (r.endsWith(".apps.googleusercontent.com")) return r;
        } catch (Exception ignored) {
        }
        return "";
    }

    private static boolean listed(String key, String h) {
        JSONArray a = cfg().optJSONArray(key);
        if (a == null || h.isEmpty()) return false;
        for (int i = 0; i < a.length(); i++) if (h.equalsIgnoreCase(a.optString(i).trim())) return true;
        return false;
    }

    static boolean isAdmin() {
        return signedIn() && listed("admins", hash(email()));
    }

    /** A tile / feature the admin hasn't switched off (for everyone, or for this person). */
    static boolean featureOn(String key) {
        JSONObject c = cfg();
        JSONArray off = c.optJSONArray("features_off");
        if (off != null) for (int i = 0; i < off.length(); i++) if (key.equals(off.optString(i))) return false;
        JSONObject per = c.optJSONObject("user_features_off");
        if (per != null && signedIn()) {
            JSONArray mine = per.optJSONArray(hash(email()));
            if (mine != null) for (int i = 0; i < mine.length(); i++) if (key.equals(mine.optString(i))) return false;
        }
        return true;
    }

    /** The admin's note (access.json "message"), once per new text. */
    static void showMessage(android.app.Activity a) {
        String m = cfg().optString("message", "").trim();
        if (m.isEmpty() || m.equals(Store.getStr("au_msg_seen", ""))) return;
        Store.putStr("au_msg_seen", m);
        new UiDialog(a).setTitle("📢  ZH Play").setMessage(m).setPositiveButton(T.t("OK"), null).show();
    }

    /** Removes the tiles the admin switched off (features_off). */
    static void filter(java.util.Map<String, ?> tiles) {
        java.util.Iterator<String> it = tiles.keySet().iterator();
        while (it.hasNext()) if (!featureOn(it.next())) it.remove();
    }

    /** From when sign-in is required: access.json "login_from", else 1 day after this version first ran. */
    static long deadline() {
        String from = cfg().optString("login_from", "").trim();
        if (from.matches("\\d{4}-\\d{2}-\\d{2}")) {
            try {
                java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
                java.util.Date d = f.parse(from);
                if (d != null) return d.getTime();
            } catch (Exception ignored) {
            }
        }
        long first = Store.getLong("au_first", 0);
        if (first == 0) {
            first = System.currentTimeMillis();
            if (freshInstall) first -= NOTICE_MS;              // new install: no notice, sign in at once
            Store.putLong("au_first", first);
        }
        return first + NOTICE_MS;
    }

    /** Set at start-up: this is a brand-new install (not an update of an existing ZH Play). */
    static volatile boolean freshInstall;

    static void noteInstall(Context c) {
        try {
            android.content.pm.PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            // installed and never updated, and no earlier ZH Play settings on this device
            freshInstall = pi.firstInstallTime == pi.lastUpdateTime && Store.getStr("last_tv", "").isEmpty()
                    && Store.getLong("au_first", 0) == 0 && !Store.getBool("seed_done", false);
        } catch (Exception ignored) {
        }
    }

    /** ok · notice (sign in soon) · signin · blocked · maintenance · update. */
    static String state(Context c) {
        JSONObject cf = cfg();
        boolean admin = isAdmin();
        if (cf.optBoolean("maintenance", false) && !admin) return MAINTENANCE;
        int min = cf.optInt("min_build", 0), mine = Updater.installed(c);
        if (min > 0 && mine > 0 && mine < min && !admin) return UPDATE;
        if (webClientId(c).isEmpty()) return OK;              // not set up yet: nobody is locked out
        if (signedIn()) {
            String h = hash(email());
            if (listed("blocked", h)) return BLOCKED;
            if ("invite_only".equals(cf.optString("mode")) && !admin && !listed("allowed", h)) return BLOCKED;
            return OK;
        }
        if (Ui.isTv(c)) return SIGNIN;                          // TVs: sign in right after the update, no notice
        return System.currentTimeMillis() < deadline() ? NOTICE : SIGNIN;
    }

    static boolean locked(Context c) {
        String s = state(c);
        return !OK.equals(s) && !NOTICE.equals(s);
    }

    // ------------------------------------------------------------------ TV sign-in by QR

    private static volatile String tvCode;
    private static volatile long tvCodeAt;

    /** The code in the TV's QR (changes every 10 minutes and after each sign-in). */
    static String tvCode() {
        if (tvCode == null || System.currentTimeMillis() - tvCodeAt > 10 * 60 * 1000L) {
            String chars = "BCDFGHJKLMNPQRSTVWXZ23456789";
            java.security.SecureRandom r = new java.security.SecureRandom();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
            tvCode = sb.toString();
            tvCodeAt = System.currentTimeMillis();
        }
        return tvCode;
    }

    static boolean tvCodeOk(String c) {
        String cur = tvCode;
        return cur != null && c != null && cur.equalsIgnoreCase(c.trim()) && System.currentTimeMillis() - tvCodeAt < 15 * 60 * 1000L;
    }

    static void tvCodeUsed() {
        tvCode = null;
    }

    /** The link in the TV's QR: the phone's camera opens it, the page hands over to ZH Play on the phone. */
    static String tvLoginUrl() {
        return "http://" + Net.ip() + ":" + RemoteService.PORT + "/login?c=" + tvCode();
    }
}
