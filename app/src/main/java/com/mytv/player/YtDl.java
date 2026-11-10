package com.mytv.player;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import java.io.File;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-app video/audio download via youtubedl-android (yt-dlp).
 * Gradle: library + ffmpeg 0.18.1; Manifest extractNativeLibs=true; App: YtDl.init(this)
 * Saves to Download/ZHPlay. GPL-3.0 library.
 */
public final class YtDl {
    private static final String TAG = "ZH-YtDl";
    private static final String CH = "zh_ytdl";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService EX = Executors.newFixedThreadPool(2);
    private static final AtomicBoolean INIT_OK = new AtomicBoolean(false);
    private static final AtomicBoolean INIT_TRIED = new AtomicBoolean(false);
    private static final AtomicInteger NOTIF_ID = new AtomicInteger(7100);
    private static volatile String lastError = "";

    private YtDl() {}

    public static boolean isReady() { return INIT_OK.get(); }
    public static String lastError() { return lastError == null ? "" : lastError; }

    public static void init(final Context app) {
        if (INIT_TRIED.getAndSet(true)) return;
        final Context c = app.getApplicationContext();
        ensureChannel(c);
        EX.execute(() -> {
            try {
                Class<?> yt = Class.forName("com.yausername.youtubedl_android.YoutubeDL");
                Object inst = yt.getMethod("getInstance").invoke(null);
                yt.getMethod("init", Context.class).invoke(inst, c);
                try {
                    Class<?> ff = Class.forName("com.yausername.ffmpeg.FFmpeg");
                    Object ffi = ff.getMethod("getInstance").invoke(null);
                    ff.getMethod("init", Context.class).invoke(ffi, c);
                } catch (Throwable ignored) {}
                INIT_OK.set(true);
                lastError = "";
                Log.i(TAG, "yt-dlp ready");
            } catch (ClassNotFoundException e) {
                lastError = "Library not added. See YTDL-INAPP-SETUP.txt";
                Log.w(TAG, lastError);
            } catch (Throwable t) {
                lastError = t.getMessage() == null ? t.toString() : t.getMessage();
                Log.e(TAG, "init failed", t);
            }
        });
    }

    public static File downloadDir(Context c) {
        File pub = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File dir = new File(pub, "ZHPlay");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        if (!dir.canWrite()) {
            dir = new File(c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "ZHPlay");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    public static void downloadVideo(Context ctx, String url) { download(ctx, url, false); }
    public static void downloadAudio(Context ctx, String url) { download(ctx, url, true); }

    public static void download(final Context ctx, final String url, final boolean audio) {
        try {
            YtDlQueue.init(ctx);
            YtDlQueue.enqueue(ctx, url, audio, null);
            return;
        } catch (Throwable ignored) {}

        if (url == null || url.trim().isEmpty()) return;
        final String clean = stripPlayerHeaders(url.trim());
        if (!clean.startsWith("http")) {
            toast(ctx, T.t("Only online links can be downloaded"));
            return;
        }
        final Context c = ctx.getApplicationContext();
        if (!INIT_OK.get()) {
            init(c);
            toast(ctx, T.t("Preparing downloader…"));
            MAIN.postDelayed(() -> {
                if (INIT_OK.get()) startDownload(ctx, clean, audio);
                else toast(ctx, T.t("In-app downloader not ready") + ": "
                        + (lastError.isEmpty() ? T.t("Add yt-dlp library to the build") : lastError));
            }, 1800);
            return;
        }
        startDownload(ctx, clean, audio);
    }

    private static String stripPlayerHeaders(String u) {
        int bar = u.indexOf('|');
        return bar > 0 ? u.substring(0, bar) : u;
    }

    private static void startDownload(final Context ctx, final String url, final boolean audio) {
        final int nid = NOTIF_ID.incrementAndGet();
        final String title = audio ? T.t("Downloading audio…") : T.t("Downloading video…");
        toast(ctx, "⬇ " + title);
        notifyProgress(ctx, nid, title, T.t("Starting…"), 0, false);

        EX.execute(() -> {
            try {
                Class<?> reqCl = Class.forName("com.yausername.youtubedl_android.YoutubeDLRequest");
                Object request = reqCl.getConstructor(String.class).newInstance(url);
                java.lang.reflect.Method opt1 = reqCl.getMethod("addOption", String.class);
                java.lang.reflect.Method opt2 = reqCl.getMethod("addOption", String.class, String.class);

                opt1.invoke(request, "--no-mtime");
                opt1.invoke(request, "--no-playlist");
                opt1.invoke(request, "--newline");
                opt1.invoke(request, "--no-check-certificates");

                File dir = downloadDir(ctx);
                String outTpl = new File(dir, "%(title).80B [%(id)s].%(ext)s").getAbsolutePath();

                if (audio) {
                    opt1.invoke(request, "-x");
                    opt2.invoke(request, "--audio-format", "m4a");
                    opt2.invoke(request, "--audio-quality", "0");
                    opt2.invoke(request, "-f", "bestaudio[ext=m4a]/bestaudio/best");
                } else {
                    opt2.invoke(request, "-f",
                            "bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/best[ext=mp4]/best");
                    opt2.invoke(request, "--merge-output-format", "mp4");
                }
                opt2.invoke(request, "-o", outTpl);

                Class<?> yt = Class.forName("com.yausername.youtubedl_android.YoutubeDL");
                Object inst = yt.getMethod("getInstance").invoke(null);

                Object response;
                try {
                    response = yt.getMethod("execute", reqCl).invoke(inst, request);
                } catch (NoSuchMethodException e) {
                    String pid = "zh-" + UUID.randomUUID().toString().substring(0, 8);
                    response = yt.getMethod("execute", reqCl, String.class).invoke(inst, request, pid);
                }

                int exit = 0;
                try {
                    Object ec = response.getClass().getMethod("getExitCode").invoke(response);
                    if (ec instanceof Integer) exit = (Integer) ec;
                } catch (Throwable ignored) {}

                scanDir(ctx, dir);

                if (exit == 0) {
                    final String done = "✓ " + T.t("Saved to") + " Download/ZHPlay";
                    MAIN.post(() -> {
                        toast(ctx, done);
                        notifyProgress(ctx, nid, T.t("Download complete"), "Download/ZHPlay", 100, true);
                    });
                } else {
                    final String msg = T.t("Download finished with errors") + " (" + exit + ")";
                    MAIN.post(() -> {
                        toast(ctx, msg);
                        notifyProgress(ctx, nid, T.t("Download issue"), msg, 0, true);
                    });
                }
            } catch (Throwable t) {
                Log.e(TAG, "download failed", t);
                Throwable c = t.getCause() != null ? t.getCause() : t;
                final String msg = c.getMessage() == null ? c.toString() : c.getMessage();
                MAIN.post(() -> {
                    toast(ctx, "⬇ " + T.t("Download failed") + ": " + msg);
                    notifyProgress(ctx, nid, T.t("Download failed"), msg, 0, true);
                });
            }
        });
    }

    private static void scanDir(Context ctx, File dir) {
        try {
            File[] files = dir.listFiles();
            if (files == null) return;
            String[] paths = new String[files.length];
            for (int i = 0; i < files.length; i++) paths[i] = files[i].getAbsolutePath();
            MediaScannerConnection.scanFile(ctx, paths, null, null);
        } catch (Throwable ignored) {}
    }

    private static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(CH, "ZH Play downloads", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Video / audio download progress");
            nm.createNotificationChannel(ch);
        } catch (Throwable ignored) {}
    }

    private static void notifyProgress(Context ctx, int id, String title, String text, int progress, boolean done) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            ensureChannel(ctx);
            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CH)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setOngoing(!done)
                    .setOnlyAlertOnce(true)
                    .setAutoCancel(done);
            if (!done && progress > 0) b.setProgress(100, progress, false);
            else if (!done) b.setProgress(0, 0, true);
            else b.setProgress(0, 0, false);
            nm.notify(id, b.build());
        } catch (Throwable ignored) {}
    }

    private static void toast(Context ctx, String msg) {
        MAIN.post(() -> {
            try {
                UiKit.makeToast(ctx, msg, Toast.LENGTH_LONG).show();
            } catch (Throwable t) {
                Toast.makeText(ctx.getApplicationContext(), msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    public static void cancel(String processId) {
        try {
            Class<?> yt = Class.forName("com.yausername.youtubedl_android.YoutubeDL");
            Object inst = yt.getMethod("getInstance").invoke(null);
            yt.getMethod("destroyProcessById", String.class).invoke(inst, processId);
        } catch (Throwable ignored) {}
    }
}
