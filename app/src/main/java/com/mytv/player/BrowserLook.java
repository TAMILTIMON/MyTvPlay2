package com.mytv.player;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

/** New-tab page look (Brave-style): background picture / animated gradient, dim, blur, cards, accent, sections. */
public final class BrowserLook {
    private BrowserLook() {
    }

    // built-in backgrounds (animated gradients, no download needed)
    static final String[] BG_IDS = {"none", "aurora", "sunset", "ocean", "forest", "midnight", "rose", "mesh", "gold", "photo"};
    static final String[] BG_NAMES = {"Plain", "Aurora", "Sunset", "Ocean", "Forest", "Midnight", "Rose", "Neon mesh", "Golden hour", "My photo…"};
    private static final String[][] GRAD = {
            {}, {"#0f2027", "#2c5364", "#7b2ff7", "#00d2ff"}, {"#ff512f", "#dd2476", "#ff9a3c", "#4a148c"},
            {"#000428", "#004e92", "#00b4db", "#0083b0"}, {"#0b3d0b", "#134e5e", "#71b280", "#2c5f2d"},
            {"#0b0913", "#1a1a40", "#270082", "#0b0913"}, {"#ff9a9e", "#fad0c4", "#a18cd1", "#fbc2eb"},
            {"#12c2e9", "#c471ed", "#f64f59", "#1d2671"}, {"#f7971e", "#ffd200", "#ff512f", "#5f2c82"}};
    static final String[] ENGINES = {"Google", "DuckDuckGo", "Bing", "Brave Search", "Startpage"};
    private static final String[] ENGINE_URLS = {"https://www.google.com/search?q=", "https://duckduckgo.com/?q=",
            "https://www.bing.com/search?q=", "https://search.brave.com/search?q=", "https://www.startpage.com/do/search?q="};
    static final String[] ACCENTS = {"#8B5CF6", "#E83C30", "#0EA5E9", "#22C55E", "#F59E0B", "#EC4899", "#14B8A6", "#6366F1"};

    static String bg() {
        return Store.getStr("bl_bg", "aurora");
    }

    static int dim() {
        return Store.getInt("bl_dim", 35);           // % darkening over the picture
    }

    static int blur() {
        return Store.getInt("bl_blur", 0);           // px
    }

    static boolean glass() {
        return Store.getBool("bl_glass", true);
    }

    static boolean anim() {
        return Store.getBool("bl_anim", true);
    }

    static boolean show(String part) {               // clock, shortcuts, recent, stats
        return Store.getBool("bl_show_" + part, true);
    }

    static String searchUrl() {
        return ENGINE_URLS[Math.max(0, Math.min(ENGINE_URLS.length - 1, Store.getInt("bl_engine", 0)))];
    }

    static String accent() {
        String a = Store.getStr("bl_accent", "");
        return a.isEmpty() ? String.format("#%06X", Ui.ACCENT & 0xFFFFFF) : a;
    }

    /** The user's own background picture, scaled to ≤ 1600 px so pages stay fast. */
    static void setPhoto(Activity a, Uri u) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = a.getContentResolver().openInputStream(u)) {
            BitmapFactory.decodeStream(in, null, o);
        }
        int s = 1;
        while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= 1600) s *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = s;
        Bitmap b;
        try (InputStream in = a.getContentResolver().openInputStream(u)) {
            b = BitmapFactory.decodeStream(in, null, o);
        }
        if (b == null) throw new Exception("not a picture");
        float k = Math.min(1f, 1600f / Math.max(b.getWidth(), b.getHeight()));
        if (k < 1f) b = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * k), Math.round(b.getHeight() * k), true);
        File f = new File(a.getFilesDir(), "browser_bg.jpg");
        try (FileOutputStream out = new FileOutputStream(f)) {
            b.compress(Bitmap.CompressFormat.JPEG, 82, out);
        }
        Store.putStr("bl_bg", "photo");
    }

    private static String photoDataUri(Activity a) {
        File f = new File(a.getFilesDir(), "browser_bg.jpg");
        if (!f.isFile()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return "data:image/jpeg;base64," + Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    /** CSS for the page background layer (+ dim, blur, animation). */
    static String backgroundCss(Activity a, boolean dark) {
        String id = bg();
        StringBuilder c = new StringBuilder();
        String layer = null;
        if ("photo".equals(id)) {
            String d = photoDataUri(a);
            if (d != null) layer = "url(" + d + ") center/cover no-repeat";
        } else {
            for (int i = 1; i < BG_IDS.length - 1; i++) {
                if (BG_IDS[i].equals(id)) {
                    String[] g = GRAD[i];
                    layer = "linear-gradient(-45deg," + g[0] + "," + g[1] + "," + g[2] + "," + g[3] + ")";
                }
            }
        }
        if (layer == null) return "";
        c.append("body:before{content:'';position:fixed;inset:-40px;z-index:-2;background:").append(layer).append(";");
        if (!"photo".equals(id)) c.append("background-size:400% 400%;");
        if (blur() > 0) c.append("filter:blur(").append(blur()).append("px);");
        if (anim() && !"photo".equals(id)) c.append("animation:zhbg 18s ease infinite;");
        if (anim() && "photo".equals(id)) c.append("animation:zhzoom 30s ease-in-out infinite alternate;");
        c.append("}");
        c.append("body:after{content:'';position:fixed;inset:0;z-index:-1;background:rgba(0,0,0,").append(dim() / 100f).append(")}");
        c.append("@keyframes zhbg{0%{background-position:0% 50%}50%{background-position:100% 50%}100%{background-position:0% 50%}}");
        c.append("@keyframes zhzoom{from{transform:scale(1)}to{transform:scale(1.08)}}");
        c.append("body{background:transparent!important;color:#fff!important}");
        return c.toString();
    }

    /** Fade-in of the page parts. */
    static String animCss() {
        if (!anim()) return "";
        return ".wrap>*{animation:zhin .5s ease both}.wrap>*:nth-child(2){animation-delay:.05s}.wrap>*:nth-child(3){animation-delay:.1s}"
                + ".wrap>*:nth-child(4){animation-delay:.15s}.wrap>*:nth-child(5){animation-delay:.2s}.wrap>*:nth-child(6){animation-delay:.25s}"
                + "@keyframes zhin{from{opacity:0;transform:translateY(10px)}to{opacity:1;transform:none}}"
                + ".s em{transition:transform .2s}.s:active em{transform:scale(.92)}";
    }

    static boolean hasBackground() {
        return !"none".equals(bg());
    }

    // ------------------------------------------------------------------ settings dialog
    interface Done {
        void run();
    }

    static void settings(final Activity a, final Done changed, final Runnable pickPhoto) {
        String[] items = {
                "🖼  " + T.t("Background") + ": " + T.t(BG_NAMES[idx(BG_IDS, bg())]),
                "🌓  " + T.t("Darken background") + ": " + dim() + " %",
                "💧  " + T.t("Blur background") + ": " + (blur() == 0 ? T.t("Off") : blur() + " px"),
                "🪟  " + T.t("Cards") + ": " + T.t(glass() ? "Glass" : "Solid"),
                "✨  " + T.t("Animations") + ": " + T.t(anim() ? "On" : "Off"),
                "🎨  " + T.t("Accent colour"),
                "🔍  " + T.t("Search engine") + ": " + ENGINES[Store.getInt("bl_engine", 0)],
                "🕒  " + T.t("Clock & greeting") + ": " + T.t(show("clock") ? "On" : "Off"),
                "⭐  " + T.t("Shortcuts") + ": " + T.t(show("shortcuts") ? "On" : "Off"),
                "🕘  " + T.t("Recent sites") + ": " + T.t(show("recent") ? "On" : "Off"),
                "🛡  " + T.t("Blocked-ads counter") + ": " + T.t(show("stats") ? "On" : "Off"),
                "↺  " + T.t("Reset appearance")};
        new UiDialog(a).setTitle(T.t("Browser appearance")).setItems(items, (d, w) -> {
            switch (w) {
                case 0:
                    new UiDialog(a).setTitle(T.t("Background")).setItems(tr(BG_NAMES), (d2, k) -> {
                        if ("photo".equals(BG_IDS[k])) pickPhoto.run();
                        else {
                            Store.putStr("bl_bg", BG_IDS[k]);
                            again(a, changed, pickPhoto);
                        }
                    }).show();
                    return;
                case 1:
                    choose(a, T.t("Darken background"), new String[]{"0 %", "20 %", "35 %", "50 %", "65 %"}, new int[]{0, 20, 35, 50, 65}, "bl_dim", changed, pickPhoto);
                    return;
                case 2:
                    choose(a, T.t("Blur background"), new String[]{T.t("Off"), "4 px", "8 px", "16 px"}, new int[]{0, 4, 8, 16}, "bl_blur", changed, pickPhoto);
                    return;
                case 3: Store.putBool("bl_glass", !glass()); break;
                case 4: Store.putBool("bl_anim", !anim()); break;
                case 5: {
                    String[] n = {T.t("App colour"), "Violet", "Red", "Sky", "Green", "Amber", "Pink", "Teal", "Indigo"};
                    new UiDialog(a).setTitle(T.t("Accent colour")).setItems(n, (d2, k) -> {
                        Store.putStr("bl_accent", k == 0 ? "" : ACCENTS[k - 1]);
                        again(a, changed, pickPhoto);
                    }).show();
                    return;
                }
                case 6:
                    new UiDialog(a).setTitle(T.t("Search engine")).setItems(ENGINES, (d2, k) -> {
                        Store.putInt("bl_engine", k);
                        again(a, changed, pickPhoto);
                    }).show();
                    return;
                case 7: Store.putBool("bl_show_clock", !show("clock")); break;
                case 8: Store.putBool("bl_show_shortcuts", !show("shortcuts")); break;
                case 9: Store.putBool("bl_show_recent", !show("recent")); break;
                case 10: Store.putBool("bl_show_stats", !show("stats")); break;
                default:
                    for (String k : new String[]{"bl_bg", "bl_accent"}) Store.putStr(k, k.equals("bl_bg") ? "aurora" : "");
                    Store.putInt("bl_dim", 35);
                    Store.putInt("bl_blur", 0);
                    Store.putInt("bl_engine", 0);
                    for (String k : new String[]{"bl_glass", "bl_anim", "bl_show_clock", "bl_show_shortcuts", "bl_show_recent", "bl_show_stats"})
                        Store.putBool(k, true);
            }
            again(a, changed, pickPhoto);
        }).show();
    }

    private static void again(Activity a, Done changed, Runnable pickPhoto) {
        changed.run();
        settings(a, changed, pickPhoto);              // stay in the menu: see the change behind it
    }

    private static void choose(Activity a, String title, String[] names, int[] vals, String key, Done changed, Runnable pickPhoto) {
        new UiDialog(a).setTitle(title).setItems(names, (d, w) -> {
            Store.putInt(key, vals[w]);
            again(a, changed, pickPhoto);
        }).show();
    }

    private static int idx(String[] a, String v) {
        for (int i = 0; i < a.length; i++) if (a[i].equals(v)) return i;
        return 0;
    }

    private static String[] tr(String[] a) {
        String[] o = new String[a.length];
        for (int i = 0; i < a.length; i++) o[i] = T.t(a[i]);
        return o;
    }
}
