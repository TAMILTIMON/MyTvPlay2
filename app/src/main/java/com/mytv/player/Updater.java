package com.mytv.player;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.appcompat.app.AlertDialog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Checks your GitHub releases for a newer ZH Play and installs it (no laptop needed). */
public final class Updater {
    private Updater() {
    }

    public static String repo() {
        return Store.getStr("update_repo", "TAMILTIMON/MyTvPlay2");
    }

    /**
     * The build number of this copy. The self-installed ("full") app has ONE fixed Android version code (so Android
     * accepts older builds too — that makes rolling back possible); the build number is the last part of the
     * version name ("5.0.2.187" → 187), which is what tells newer from older.
     */
    public static int installed(Context c) {
        try {
            android.content.pm.PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            String n = pi.versionName == null ? "" : pi.versionName;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\.(\\d{3,})$").matcher(n);
            if (m.find()) return Integer.parseInt(m.group(1));
            return pi.versionCode < 1_000_000 ? pi.versionCode : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public static String installedName(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    public static final class Info {
        public int code;
        public String url = "", name = "", date = "";
    }

    // ------------------------------------------------------------------ updates menu: check · earlier versions · automatic
    public static void menu(final Activity a) {
        if (playBuild(a)) {
            check(a, false);
            return;
        }
        boolean paused = Store.getBool("upd_paused", false);
        new UiDialog(a).setTitle(T.t("Updates") + " · " + installedName(a)).setItems(new String[]{
                "⬆  " + T.t("Check for updates"),
                "↩  " + T.t("Earlier versions (roll back)"),
                "🔁  " + T.t("Automatic updates") + ": " + T.t(paused ? "Paused" : "On")}, (d, w) -> {
            if (w == 0) {
                Store.putLong("upd_last", 0);
                check(a, false);
            } else if (w == 1) earlier(a);
            else {
                Store.putBool("upd_paused", !Store.getBool("upd_paused", false));
                UiKit.makeToast(a, T.t("Automatic updates") + ": " + T.t(Store.getBool("upd_paused", false) ? "Paused" : "On"),
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        }).show();
    }

    /** All releases (newest first), each with the APK for this device. */
    static java.util.List<Info> releases() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.github.com/repos/" + repo() + "/releases?per_page=40").openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "ZH Play");
        if (c.getResponseCode() != 200) throw new Exception("No releases found on github.com/" + repo());
        StringBuilder sb = new StringBuilder();
        try (InputStream in = c.getInputStream()) {
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) sb.append(new String(b, 0, n, "UTF-8"));
        }
        JSONArray arr = new JSONArray(sb.toString());
        java.util.List<Info> out = new java.util.ArrayList<>();
        for (int k = 0; k < arr.length(); k++) {
            Info i = fromRelease(arr.getJSONObject(k));
            if (!i.url.isEmpty()) out.add(i);
        }
        java.util.Collections.sort(out, (x, y) -> y.code - x.code);
        return out;
    }

    /** Pick an earlier build and install it; settings, playlists and saved boards stay. */
    static void earlier(final Activity a) {
        UiKit.makeToast(a, T.t("Loading the versions…"), android.widget.Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                final java.util.List<Info> all = releases();
                final int mine = installed(a);
                // builds before this numbering have a lower Android version code: Android refuses them
                if (Store.getInt("upd_fixed_from", 0) == 0 && mine > 0) Store.putInt("upd_fixed_from", mine);
                final int from = Store.getInt("upd_fixed_from", mine);
                a.runOnUiThread(() -> {
                    if (all.isEmpty()) {
                        UiKit.makeToast(a, T.t("No versions found"), android.widget.Toast.LENGTH_LONG).show();
                        return;
                    }
                    String[] rows = new String[all.size()];
                    for (int k = 0; k < rows.length; k++) {
                        Info i = all.get(k);
                        rows[k] = (i.code == mine ? "✅  " : i.code > mine ? "⬆  " : i.code < from ? "🚫  " : "↩  ") + i.name + ": " + i.date
                                + (i.code == mine ? " · " + T.t("installed") : i.code > mine ? " · " + T.t("newer")
                                : i.code < from ? " · " + T.t("too old to go back to") : "");
                    }
                    new UiDialog(a).setTitle(T.t("Earlier versions (roll back)")).setItems(rows, (d, w) -> {
                        final Info i = all.get(w);
                        if (i.code == mine) {
                            UiKit.makeToast(a, T.t("This version is installed"), android.widget.Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (i.code < from) {
                            new UiDialog(a).setTitle(i.name).setMessage(T.t("Android can't go back to builds made before version 5.1 (they were numbered differently). Every build from 5.1 on can be installed again from here."))
                                    .setPositiveButton(T.t("OK"), null).show();
                            return;
                        }
                        boolean older = i.code < mine;
                        new UiDialog(a).setTitle(i.name)
                                .setMessage(T.t(older ? "Go back to this version? Features added later will be missing. Your settings, playlists and saved boards stay. Automatic updates will pause (turn them on again in Updates)."
                                        : "Install this version?"))
                                .setPositiveButton(T.t("Install"), (d2, w2) -> {
                                    if (older) Store.putBool("upd_paused", true);
                                    download(a, i);
                                })
                                .setNegativeButton(T.t("Cancel"), null).show();
                    }).setNegativeButton(T.t("Close"), null).show();
                });
            } catch (final Exception e) {
                a.runOnUiThread(() -> UiKit.makeToast(a, e.getMessage(), android.widget.Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    /** Latest release on GitHub, or null. Run on a background thread. */
    public static Info latest() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.github.com/repos/" + repo() + "/releases/latest").openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "ZH Play");
        if (c.getResponseCode() != 200) throw new Exception("No release found on github.com/" + repo());
        StringBuilder sb = new StringBuilder();
        try (InputStream in = c.getInputStream()) {
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) sb.append(new String(b, 0, n, "UTF-8"));
        }
        return fromRelease(new JSONObject(sb.toString()));
    }

    private static Info fromRelease(JSONObject o) throws Exception {
        Info i = new Info();
        String pub = o.optString("published_at", "");
        i.date = pub.length() >= 10 ? pub.substring(0, 10) : "";
        String tag = o.optString("tag_name", "v0").replaceAll("[^0-9]", "");
        i.code = tag.isEmpty() ? 0 : Integer.parseInt(tag);
        i.name = o.optString("name");
        String abi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "";
        String want = abi.contains("arm64") ? "arm64-v8a" : abi.contains("armeabi") ? "armeabi-v7a" : "universal";
        JSONArray as = o.optJSONArray("assets");
        String uni = "";
        if (as != null) for (int k = 0; k < as.length(); k++) {
            String n = as.getJSONObject(k).optString("name"), u = as.getJSONObject(k).optString("browser_download_url");
            if (!n.endsWith(".apk")) continue;
            if (n.contains(want)) i.url = u;
            if (n.contains("universal")) uni = u;
        }
        if (i.url.isEmpty()) i.url = uni;
        return i;
    }

    /** Checks and asks. quiet = only show something when an update exists. */
    /** The Google Play build: Play updates the app (apps from Play may not install their own updates). */
    public static boolean playBuild(android.content.Context c) {
        try {
            return c.getResources().getBoolean(R.bool.play_store);
        } catch (Exception e) {
            return false;
        }
    }

    public static void check(final Activity a, final boolean quiet) {
        if (playBuild(a)) {
            if (quiet) return;
            new UiDialog(a).setTitle(T.t("Updates"))
                    .setMessage(T.t("This copy of ZH Play comes from Google Play — Play installs new versions automatically."))
                    .setPositiveButton(T.t("Open in Play"), (d, w) -> {
                        try {
                            a.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("market://details?id=" + a.getPackageName())));
                        } catch (Exception e) {
                            a.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://play.google.com/store/apps/details?id=" + a.getPackageName())));
                        }
                    })
                    .setNegativeButton(T.t("Close"), null).show();
            return;
        }
        if (quiet && Store.getBool("upd_paused", false)) return;          // the user went back to an older build
        if (quiet && System.currentTimeMillis() - Store.getLong("upd_last", 0) < 12 * 3600 * 1000L) return;
        Store.putLong("upd_last", System.currentTimeMillis());
        new Thread(() -> {
            try {
                final Info i = latest();
                final int mine = installed(a);
                a.runOnUiThread(() -> {
                    if (a.isFinishing()) return;
                    if (i.code > mine && !i.url.isEmpty()) {
                        new UiDialog(a).setTitle("⬆  " + T.t("Update available"))
                                .setMessage(i.name + "\n\n" + T.t("Installed") + ": " + installedName(a) + " (" + mine + ")\n"
                                        + T.t("New") + ": " + i.code)
                                .setPositiveButton(T.t("Update now"), (d, w) -> download(a, i))
                                .setNegativeButton(T.t("Later"), null).show();
                    } else if (!quiet) {
                        UiKit.makeToast(a, "✅ " + T.t("You have the latest version") + " (" + installedName(a) + ")",
                                android.widget.Toast.LENGTH_LONG).show();
                    }
                });
            } catch (final Exception e) {
                if (!quiet) a.runOnUiThread(() -> UiKit.makeToast(a, e.getMessage(), android.widget.Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private static void download(final Activity a, final Info i) {

        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            new UiDialog(a).setTitle(T.t("Allow installing updates"))
                    .setMessage(T.t("On the next screen switch “Allow from this source” ON for ZH Play, then press Update again."))
                    .setPositiveButton(T.t("Allow"), (d, w) -> Perms.launchFirst(a, new Intent[]{
                            new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())),
                            new Intent(Settings.ACTION_SECURITY_SETTINGS)}))
                    .setNegativeButton(T.t("Cancel"), null).show();
            return;
        }
        UiKit.makeToast(a, T.t("Downloading update…"), android.widget.Toast.LENGTH_LONG).show();
        new Thread(() -> {
            try {
                File dir = a.getExternalCacheDir() != null ? a.getExternalCacheDir() : a.getCacheDir();
                File f = new File(dir, "ZHPlay-update.apk");
                HttpURLConnection c = (HttpURLConnection) new URL(i.url).openConnection();
                c.setInstanceFollowRedirects(true);
                c.setConnectTimeout(10000);
                c.setReadTimeout(30000);
                try (InputStream in = c.getInputStream(); FileOutputStream o = new FileOutputStream(f)) {
                    byte[] b = new byte[65536];
                    int n;
                    while ((n = in.read(b)) > 0) o.write(b, 0, n);
                }
                a.runOnUiThread(() -> install(a, f));
            } catch (final Exception e) {
                a.runOnUiThread(() -> UiKit.makeToast(a, "Update failed: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private static void install(Activity a, File f) {
        try {
            Uri u = androidx.core.content.FileProvider.getUriForFile(a, a.getPackageName() + ".files", f);
            Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
        } catch (Exception e) {
            UiKit.makeToast(a, "Install failed: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
        }
    }
}
