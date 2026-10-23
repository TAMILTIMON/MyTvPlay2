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

/** Checks your GitHub releases for a newer TamilCastPlay and installs it (no laptop needed). */
public final class Updater {
    private Updater() {
    }

    public static String repo() {
        return Store.getStr("update_repo", "TAMILTIMON/MyTvPlay2");
    }

    public static int installed(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode;
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
        public String url = "", name = "";
    }

    /** Latest release on GitHub, or null. Run on a background thread. */
    public static Info latest() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.github.com/repos/" + repo() + "/releases/latest").openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "TamilCastPlay");
        if (c.getResponseCode() != 200) throw new Exception("No release found on github.com/" + repo());
        StringBuilder sb = new StringBuilder();
        try (InputStream in = c.getInputStream()) {
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) sb.append(new String(b, 0, n, "UTF-8"));
        }
        JSONObject o = new JSONObject(sb.toString());
        Info i = new Info();
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
    public static void check(final Activity a, final boolean quiet) {
        if (quiet && System.currentTimeMillis() - Store.getLong("upd_last", 0) < 12 * 3600 * 1000L) return;
        Store.putLong("upd_last", System.currentTimeMillis());
        new Thread(() -> {
            try {
                final Info i = latest();
                final int mine = installed(a);
                a.runOnUiThread(() -> {
                    if (a.isFinishing()) return;
                    if (i.code > mine && !i.url.isEmpty()) {
                        new AlertDialog.Builder(a).setTitle("⬆  " + T.t("Update available"))
                                .setMessage(i.name + "\n\n" + T.t("Installed") + ": " + installedName(a) + " (" + mine + ")\n"
                                        + T.t("New") + ": " + i.code)
                                .setPositiveButton(T.t("Update now"), (d, w) -> download(a, i))
                                .setNegativeButton(T.t("Later"), null).show();
                    } else if (!quiet) {
                        android.widget.Toast.makeText(a, "✅ " + T.t("You have the latest version") + " (" + installedName(a) + ")",
                                android.widget.Toast.LENGTH_LONG).show();
                    }
                });
            } catch (final Exception e) {
                if (!quiet) a.runOnUiThread(() -> android.widget.Toast.makeText(a, e.getMessage(), android.widget.Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private static void download(final Activity a, final Info i) {
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(a).setTitle(T.t("Allow installing updates"))
                    .setMessage(T.t("On the next screen switch “Allow from this source” ON for TamilCastPlay, then press Update again."))
                    .setPositiveButton(T.t("Allow"), (d, w) -> Perms.launchFirst(a, new Intent[]{
                            new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())),
                            new Intent(Settings.ACTION_SECURITY_SETTINGS)}))
                    .setNegativeButton(T.t("Cancel"), null).show();
            return;
        }
        android.widget.Toast.makeText(a, T.t("Downloading update…"), android.widget.Toast.LENGTH_LONG).show();
        new Thread(() -> {
            try {
                File dir = a.getExternalCacheDir() != null ? a.getExternalCacheDir() : a.getCacheDir();
                File f = new File(dir, "TamilCastPlay-update.apk");
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
                a.runOnUiThread(() -> android.widget.Toast.makeText(a, "Update failed: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show());
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
            android.widget.Toast.makeText(a, "Install failed: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
        }
    }
}
