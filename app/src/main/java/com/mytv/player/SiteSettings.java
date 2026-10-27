package com.mytv.player;

import android.app.Activity;
import android.webkit.CookieManager;
import android.webkit.WebStorage;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Per-site permissions for the browser, like Chrome / Brave:
 * location, camera, microphone, notifications, motion sensors, files, pop-ups, autoplay, JavaScript, cookies, ad blocker.
 * Values: "ask", "allow", "block".
 */
public final class SiteSettings {
    private SiteSettings() {
    }

    public static final String[] KEYS = {"location", "camera", "mic", "notifications", "motion", "files", "popups", "autoplay", "js", "cookies", "ads"};
    static final String[] NAMES = {"Location", "Camera", "Microphone", "Notifications", "Motion sensors", "Files (upload)", "Pop-ups",
            "Autoplay sound", "JavaScript", "Cookies", "Ad & tracker blocker"};
    static final String[] ICONS = {"📍", "📷", "🎙", "🔔", "📳", "📁", "🪟", "🔊", "⚙", "🍪", "🛡"};
    /** Defaults when a site has no own choice. */
    static final String[] DEFAULTS = {"ask", "ask", "ask", "block", "allow", "allow", "block", "allow", "allow", "allow", "allow"};
    /** Which choices make sense for each setting. */
    private static final String[][] CHOICES = {{"ask", "allow", "block"}, {"ask", "allow", "block"}, {"ask", "allow", "block"},
            {"allow", "block"}, {"allow", "block"}, {"allow", "block"}, {"allow", "block"}, {"allow", "block"}, {"allow", "block"},
            {"allow", "block"}, {"allow", "block"}};

    private static JSONObject all() {
        try {
            return new JSONObject(Store.getStr("site_perm", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public static String get(String host, String key) {
        int k = idx(key);
        if (host == null || host.isEmpty()) return DEFAULTS[k];
        if ("ads".equals(key)) return Shield.siteOff(host) ? "block" : "allow";
        JSONObject s = all().optJSONObject(host);
        return s == null ? DEFAULTS[k] : s.optString(key, DEFAULTS[k]);
    }

    public static boolean allowed(String host, String key) {
        return "allow".equals(get(host, key));
    }

    public static void set(String host, String key, String v) {
        if (host == null || host.isEmpty()) return;
        if ("ads".equals(key)) {
            Shield.setSite(host, "allow".equals(v));        // allow = blocker on for this site
            return;
        }
        try {
            JSONObject a = all();
            JSONObject s = a.optJSONObject(host);
            if (s == null) s = new JSONObject();
            if (v.equals(DEFAULTS[idx(key)])) s.remove(key);
            else s.put(key, v);
            if (s.length() == 0) a.remove(host);
            else a.put(host, s);
            Store.putStr("site_perm", a.toString());
        } catch (Exception ignored) {
        }
    }

    public static void reset(String host) {
        JSONObject a = all();
        a.remove(host);
        Store.putStr("site_perm", a.toString());
        Shield.setSite(host, true);
    }

    /** Sites that have at least one own choice. */
    public static List<String> sites() {
        List<String> l = new ArrayList<>();
        Iterator<String> it = all().keys();
        while (it.hasNext()) l.add(it.next());
        java.util.Collections.sort(l);
        return l;
    }

    private static int idx(String key) {
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(key)) return i;
        return 0;
    }

    static String label(String v) {
        return "allow".equals(v) ? T.t("Allowed") : "block".equals(v) ? T.t("Blocked") : T.t("Ask first");
    }

    /** Page script for what the engine can't block itself: notifications and motion sensors. */
    public static String pageJs(String host) {
        StringBuilder j = new StringBuilder("(function(){if(window.__zhSite)return;window.__zhSite=1;");
        if (!allowed(host, "notifications")) {
            j.append("try{if(window.Notification){Object.defineProperty(Notification,'permission',{get:function(){return 'denied'}});")
                    .append("Notification.requestPermission=function(cb){if(cb)cb('denied');return Promise.resolve('denied');};}}catch(e){}");
        }
        if (!allowed(host, "motion")) {
            j.append("try{var ae=window.addEventListener;window.addEventListener=function(t,f,o){if(t==='devicemotion'||t==='deviceorientation'")
                    .append("||t==='deviceorientationabsolute')return;return ae.call(this,t,f,o);};window.ondevicemotion=null;")
                    .append("Object.defineProperty(window,'ondevicemotion',{set:function(){},get:function(){return null}});")
                    .append("Object.defineProperty(window,'ondeviceorientation',{set:function(){},get:function(){return null}});}catch(e){}");
        }
        return j.append("})();").toString();
    }

    // ------------------------------------------------------------------ dialogs
    /** One site's settings (from the address bar lock / the menu). */
    public static void show(final Activity a, final String host, final Runnable changed) {
        if (host == null || host.isEmpty()) {
            UiKit.makeToast(a, T.t("Open a website first"), android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] rows = new String[KEYS.length + 2];
        for (int i = 0; i < KEYS.length; i++) rows[i] = ICONS[i] + "  " + T.t(NAMES[i]) + ":  " + label(get(host, KEYS[i]));
        rows[KEYS.length] = "🧹  " + T.t("Clear this site's data (cookies, storage)");
        rows[KEYS.length + 1] = "↺  " + T.t("Reset this site's permissions");
        new UiDialog(a).setTitle(host).setItems(rows, (d, w) -> {
            if (w < KEYS.length) {
                final int k = w;
                final String[] ch = CHOICES[k];
                String[] names = new String[ch.length];
                int cur = 0;
                for (int i = 0; i < ch.length; i++) {
                    names[i] = label(ch[i]);
                    if (ch[i].equals(get(host, KEYS[k]))) cur = i;
                }
                new UiDialog(a).setTitle(T.t(NAMES[k]) + " · " + host).setSingleChoiceItems(names, cur, (d2, c) -> {
                    set(host, KEYS[k], ch[c]);
                    d2.dismiss();
                    if (changed != null) changed.run();
                    show(a, host, changed);
                }).show();
            } else if (w == KEYS.length) {
                clearData(host);
                UiKit.makeToast(a, T.t("Cleared"), android.widget.Toast.LENGTH_SHORT).show();
                if (changed != null) changed.run();
            } else {
                reset(host);
                if (changed != null) changed.run();
                show(a, host, changed);
            }
        }).show();
    }

    /** All sites with own choices. */
    public static void showAll(final Activity a, final Runnable changed) {
        final List<String> l = sites();
        if (l.isEmpty()) {
            new UiDialog(a).setTitle(T.t("Site settings"))
                    .setMessage(T.t("No site has its own settings yet. Tap the lock in the address bar on a website to change its permissions."))
                    .setPositiveButton(T.t("OK"), null).show();
            return;
        }
        new UiDialog(a).setTitle(T.t("Site settings")).setItems(l.toArray(new String[0]), (d, w) -> show(a, l.get(w), changed)).show();
    }

    static void clearData(String host) {
        CookieManager cm = CookieManager.getInstance();
        for (String scheme : new String[]{"https://", "http://"}) {
            String c = cm.getCookie(scheme + host);
            if (c != null) {
                for (String part : c.split(";")) {
                    String name = part.split("=")[0].trim();
                    cm.setCookie(scheme + host, name + "=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT");
                    cm.setCookie(scheme + host, name + "=; Max-Age=0; Domain=" + host + "; Path=/");
                }
            }
            WebStorage.getInstance().deleteOrigin(scheme + host);
        }
        cm.flush();
    }
}
