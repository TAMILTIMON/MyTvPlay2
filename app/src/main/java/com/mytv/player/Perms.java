package com.mytv.player;

import android.Manifest;
import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import androidx.appcompat.app.AlertDialog;

/** Asks for the special permissions inside the app, so nobody needs a laptop / adb. */
public final class Perms {
    private Perms() {
    }

    public static boolean overlayOk(android.content.Context c) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
    }

    public static boolean accessOk(android.content.Context c) {
        if (TvAccess.on()) return true;
        String s = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && s.contains(c.getPackageName());
    }

    /** Steps + ways to turn on whole-TV control, then opens the best Settings screen this TV has. */
    public static void openAccessSettings(final Activity a) {
        String steps = T.t("On the next screen choose “TamilCastPlay phone remote” and switch it ON. Then the phone remote works in every app (Home, Netflix, settings…).")
                + "\n\n" + T.t("If the screen does not open, go to:") + "\n"
                + "⚙ Settings → Device Preferences → Accessibility → TamilCastPlay phone remote → ON\n"
                + "(" + T.t("some TVs: Settings → System → Accessibility, or Settings → Apps → Special app access") + ")";
        new AlertDialog.Builder(a)
                .setTitle("🎮  " + T.t("Control the whole TV from the phone"))
                .setMessage(steps)
                .setPositiveButton(T.t("Allow"), (d, w) -> {
                    if (!launchFirst(a, accessIntents(a))) accessHelp(a);
                })
                .setNeutralButton(T.t("Other ways"), (d, w) -> accessHelp(a))
                .setNegativeButton(T.t("Later"), null).show();
    }

    private static Intent[] accessIntents(Activity a) {
        return new Intent[]{
                new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                // Android TV / Google TV settings app
                new Intent().setClassName("com.android.tv.settings", "com.android.tv.settings.accessibility.AccessibilityActivity"),
                new Intent().setClassName("com.android.tv.settings", "com.android.tv.settings.system.AccessibilityActivity"),
                new Intent().setClassName("com.android.tv.settings", "com.android.tv.settings.MainSettings"),
                // phone-style settings app
                new Intent().setClassName("com.android.settings", "com.android.settings.Settings$AccessibilitySettingsActivity"),
                // last resort: the TV's main settings
                new Intent(Settings.ACTION_SETTINGS),
        };
    }

    /**
     * Starts the first intent that really opens a screen. Skips ones that only lead to
     * Android's "no app can handle this" chooser.
     */
    public static boolean launchFirst(Activity a, Intent[] tries) {
        android.content.pm.PackageManager pm = a.getPackageManager();
        for (Intent i : tries) {
            try {
                android.content.pm.ResolveInfo ri = pm.resolveActivity(i, 0);
                if (ri == null || ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName, cls = ri.activityInfo.name;
                if ("android".equals(pkg) || cls.contains("ResolverActivity") || cls.contains("Chooser")) continue;
                if (!ri.activityInfo.exported && !pkg.equals(a.getPackageName())) continue;
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                a.startActivity(i);
                if (Settings.ACTION_SETTINGS.equals(i.getAction()) || cls.endsWith("MainSettings")) {
                    android.widget.Toast.makeText(a, T.t("Device Preferences → Accessibility → TamilCastPlay phone remote → ON"),
                            android.widget.Toast.LENGTH_LONG).show();
                }
                return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    /** When the TV hides Accessibility completely: one adb command (from a PC, or a phone adb app). */
    private static void accessHelp(final Activity a) {
        final String cmd = "adb shell settings put secure enabled_accessibility_services "
                + a.getPackageName() + "/" + TvAccess.class.getName()
                + "\nadb shell settings put secure accessibility_enabled 1";
        new AlertDialog.Builder(a).setTitle(T.t("Other ways"))
                .setMessage(T.t("Your TV may hide the Accessibility screen. Turn it on once with adb (from a PC, or a phone app such as “Remote ADB Shell” / “Bugjaeger”):")
                        + "\n\n" + cmd + "\n\n"
                        + T.t("TV volume and ⏯ already work without it. Inside TamilCastPlay the phone remote always works."))
                .setPositiveButton(T.t("OK"), null)
                .setNeutralButton(T.t("Copy"), (d, w) -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) a.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("adb", cmd));
                }).show();
    }

    public static boolean filesOk() {
        return Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager();
    }

    /** First start: explain and open the right settings screen. Shown once (and again from ⚙ Settings). */
    public static void firstRun(final Activity a) {
        if (Build.VERSION.SDK_INT >= 33 && !Ui.isTv(a)
                && a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !Store.getBool("asked_notif", false)) {
            Store.putBool("asked_notif", true);
            a.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
        }
        if (!Ui.isTv(a)) return; // the phone only needs this when used as a player
        if (overlayOk(a) || Store.getBool("asked_overlay", false)) return;
        Store.putBool("asked_overlay", true);
        askOverlay(a);
    }

    public static void askOverlay(final Activity a) {
        new AlertDialog.Builder(a)
                .setTitle(T.t("📱  Allow the phone to start videos"))
                .setMessage("So your phone can play videos on this TV even when TamilCastPlay is closed, "
                        + "allow “Display over other apps”.\n\nOn the next screen choose TamilCastPlay and switch it ON, "
                        + "then press Back.")
                .setPositiveButton(T.t("Allow"), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        openOverlaySettings(a);
                    }
                })
                .setNegativeButton(T.t("Later"), null).show();
    }

    public static void openOverlaySettings(Activity a) {
        if (Build.VERSION.SDK_INT < 23) return;
        String pkg = "package:" + a.getPackageName();
        Intent[] tries = {
                new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse(pkg)),
                new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse(pkg)),
        };
        if (!launchFirst(a, tries)) help(a, "Display over other apps");
    }

    public static void openFilesSettings(Activity a) {
        if (Build.VERSION.SDK_INT < 30) return;
        Intent[] tries = {
                new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + a.getPackageName())),
                new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.getPackageName())),
        };
        if (!launchFirst(a, tries)) help(a, "All files access");
    }

    /** Some TVs hide these screens: explain the manual path. */
    private static void help(Activity a, String what) {
        new AlertDialog.Builder(a).setTitle(T.t("Open it in TV Settings"))
                .setMessage("This TV has no direct shortcut. Go to:\n\nSettings → Apps → Special app access → "
                        + what + " → TamilCastPlay → ON\n\nIf your TV doesn't have that option, everything still works "
                        + "while TamilCastPlay is open on the TV.\n\nadb: adb shell appops set " + a.getPackageName() + " "
                        + ("All files access".equals(what) ? "MANAGE_EXTERNAL_STORAGE" : "SYSTEM_ALERT_WINDOW") + " allow")
                .setPositiveButton(T.t("OK"), null).show();
    }
}
