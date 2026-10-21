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
        for (Intent i : tries) {
            try {
                a.startActivity(i);
                return;
            } catch (Exception ignored) {
            }
        }
        help(a, "Display over other apps");
    }

    public static void openFilesSettings(Activity a) {
        if (Build.VERSION.SDK_INT < 30) return;
        Intent[] tries = {
                new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + a.getPackageName())),
                new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.getPackageName())),
        };
        for (Intent i : tries) {
            try {
                a.startActivity(i);
                return;
            } catch (Exception ignored) {
            }
        }
        help(a, "All files access");
    }

    /** Some TVs hide these screens: explain the manual path. */
    private static void help(Activity a, String what) {
        new AlertDialog.Builder(a).setTitle(T.t("Open it in TV Settings"))
                .setMessage("This TV has no direct shortcut. Go to:\n\nSettings → Apps → Special app access → "
                        + what + " → TamilCastPlay → ON\n\nIf your TV doesn't have that option, everything still works "
                        + "while TamilCastPlay is open on the TV.")
                .setPositiveButton(T.t("OK"), null).show();
    }
}
