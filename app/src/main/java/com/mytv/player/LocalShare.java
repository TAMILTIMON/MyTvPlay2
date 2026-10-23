package com.mytv.player;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.provider.OpenableColumns;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** Shares songs/videos picked on the phone so the TV can stream them (no copying). */
public final class LocalShare {
    private LocalShare() {
    }

    private static final Map<String, Uri> files = new HashMap<>();
    private static int next = 1;
    private static PowerManager.WakeLock wake;
    private static WifiManager.WifiLock wifi;

    public static String displayName(Context c, Uri u) {
        try (Cursor cur = c.getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                String n = cur.getString(0);
                if (n != null) return n;
            }
        } catch (Exception ignored) {
        }
        String s = u.getLastPathSegment();
        return s == null ? "file" : s;
    }

    /** Returns the link the TV can play. */
    public static synchronized String share(Context c, Uri u, String name) {
        String id = Long.toHexString(new java.security.SecureRandom().nextLong() & Long.MAX_VALUE) + (next++);
        files.put(id, u);
        keepAwake(c);
        return "http://" + Net.ip() + ":" + RemoteService.PORT + "/local/" + id + "/" + Uri.encode(name);
    }

    private static void keepAwake(Context c) {
        try {
            Context app = c.getApplicationContext();
            if (wake == null) {
                PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mytv:share");
            }
            if (wifi == null) {
                WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
                wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "mytv:share");
            }
            wake.acquire(4L * 3600 * 1000);
            if (!wifi.isHeld()) wifi.acquire();
        } catch (Throwable ignored) {
        }
    }

    public static synchronized void releaseLocks() {
        try {
            if (wake != null && wake.isHeld()) wake.release();
            if (wifi != null && wifi.isHeld()) wifi.release();
        } catch (Throwable ignored) {
        }
    }

    public static synchronized Uri get(String id) {
        return files.get(id);
    }

    public static final class Opened {
        public InputStream in;
        public long size;
    }

    public static Opened open(Context c, Uri u, long from) throws IOException {
        ParcelFileDescriptor pfd = c.getContentResolver().openFileDescriptor(u, "r");
        if (pfd == null) throw new IOException("file not available");
        Opened o = new Opened();
        o.size = pfd.getStatSize();
        if (o.size < 0) {
            try (Cursor cur = c.getContentResolver().query(u, new String[]{OpenableColumns.SIZE}, null, null, null)) {
                if (cur != null && cur.moveToFirst()) o.size = cur.getLong(0);
            } catch (Exception ignored) {
            }
        }
        FileInputStream fis = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
        if (from > 0) fis.getChannel().position(from);
        o.in = fis;
        return o;
    }
}
