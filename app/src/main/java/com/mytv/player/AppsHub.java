package com.mytv.player;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "One place" app hub: shows Netflix, JioHotstar, Spotify, SonyLIV… as tiles and opens the official
 * apps (each keeps its own login). Users can add any installed app.
 */
public final class AppsHub {
    private AppsHub() {
    }

    /** name, then package names to try (TV version first, phone version last). */
    private static final String[][] KNOWN = {
            {"Netflix", "com.netflix.ninja", "com.netflix.mediaclient"},
            {"JioHotstar", "in.startv.hotstartvonly", "in.startv.hotstar"},
            {"SonyLIV", "com.sonyliv"},
            {"Spotify", "com.spotify.tv.android", "com.spotify.music"},
            {"YouTube", "com.google.android.youtube.tv", "com.google.android.youtube"},
            {"Prime Video", "com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient"},
            {"ZEE5", "com.graymatrix.did"},
            {"YouTube Music", "com.google.android.youtube.tvmusic", "com.google.android.apps.youtube.music"},
            {"VLC", "org.videolan.vlc"},
            {"MX Player", "com.mxtech.videoplayer.television", "com.mxtech.videoplayer.ad"},
    };

    public static final class App {
        public final String name, pkg;
        public final boolean installed;

        App(String name, String pkg, boolean installed) {
            this.name = name;
            this.pkg = pkg;
            this.installed = installed;
        }
    }

    public static boolean installed(Context c, String pkg) {
        try {
            c.getPackageManager().getApplicationInfo(pkg, 0);
            return launchIntent(c, pkg) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static Intent launchIntent(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        Intent i = null;
        if (Ui.isTv(c)) i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null && !Ui.isTv(c)) i = pm.getLeanbackLaunchIntentForPackage(pkg);
        return i;
    }

    public static String label(Context c, String pkg) {
        for (String[] k : KNOWN) for (int i = 1; i < k.length; i++) if (k[i].equals(pkg)) return k[0];
        try {
            PackageManager pm = c.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return String.valueOf(pm.getApplicationLabel(ai));
        } catch (Exception e) {
            return pkg;
        }
    }

    /** Package of a known app that is installed here, else the one to install from the store. */
    private static String pick(Context c, String[] k) {
        for (int i = 1; i < k.length; i++) if (installed(c, k[i])) return k[i];
        return Ui.isTv(c) ? k[1] : k[k.length - 1];
    }

    /** The tiles to show: the user's list, or (first time) every known app that is installed. */
    public static List<App> favorites(Context c) {
        List<App> out = new ArrayList<>();
        String saved = Store.getStr("hub", "");
        if (saved.isEmpty() && !Store.getBool("hub_set", false)) {
            for (String[] k : KNOWN) {
                String p = pick(c, k);
                if (installed(c, p)) out.add(new App(k[0], p, true));
            }
            return out;
        }
        for (String p : saved.split(",")) {
            p = p.trim();
            if (p.isEmpty()) continue;
            out.add(new App(label(c, p), p, installed(c, p)));
        }
        return out;
    }

    public static void saveFavorites(List<String> pkgs) {
        StringBuilder sb = new StringBuilder();
        for (String p : pkgs) {
            if (sb.length() > 0) sb.append(',');
            sb.append(p);
        }
        Store.putStr("hub", sb.toString());
        Store.putBool("hub_set", true);
    }

    /** Everything the user can choose from: known apps (installed or not) + all installed apps. */
    public static List<App> choices(Context c) {
        Map<String, App> m = new LinkedHashMap<>();
        for (String[] k : KNOWN) {
            String p = pick(c, k);
            m.put(p, new App(k[0], p, installed(c, p)));
        }
        PackageManager pm = c.getPackageManager();
        List<App> rest = new ArrayList<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String p = ri.activityInfo.packageName;
                if (p.equals(c.getPackageName()) || m.containsKey(p)) continue;
                boolean dup = false;
                for (App a : rest) if (a.pkg.equals(p)) dup = true;
                if (!dup) rest.add(new App(String.valueOf(ri.loadLabel(pm)), p, true));
            }
        }
        Collections.sort(rest, new Comparator<App>() {
            public int compare(App a, App b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        List<App> out = new ArrayList<>(m.values());
        out.addAll(rest);
        return out;
    }

    /** Opens the app, or its Play Store page when it is not installed. Returns a message. */
    public static String launch(Context c, String pkg) {
        Intent i = launchIntent(c, pkg);
        try {
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                c.startActivity(i);
                return "Opening " + label(c, pkg);
            }
            Intent s = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg));
            s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                c.startActivity(s);
            } catch (Exception e) {
                c.startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=" + pkg))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            return label(c, pkg) + " is not installed — opening the Play Store";
        } catch (Exception e) {
            return "Could not open " + label(c, pkg);
        }
    }

    public static Drawable icon(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            Drawable banner = Ui.isTv(c) ? pm.getApplicationBanner(pkg) : null;
            return banner != null ? banner : pm.getApplicationIcon(pkg);
        } catch (Exception e) {
            return null;
        }
    }

    private static final Map<String, String> iconCache = new HashMap<>();

    /** Small PNG icon as base64 for the phone page. */
    public static synchronized String iconBase64(Context c, String pkg) {
        if (iconCache.containsKey(pkg)) return iconCache.get(pkg);
        String out = "";
        try {
            Drawable d = c.getPackageManager().getApplicationIcon(pkg);
            Bitmap b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(b);
            d.setBounds(0, 0, 96, 96);
            d.draw(cv);
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, o);
            out = "data:image/png;base64," + Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP);
        } catch (Exception ignored) {
        }
        iconCache.put(pkg, out);
        return out;
    }
}
