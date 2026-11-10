package com.mytv.player;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Download manager with queue for in-app yt-dlp jobs.
 * UI: YtDlActivity. Engine: reflection on youtubedl-android (same as YtDl).
 */
public final class YtDlQueue {
    private static final String TAG = "ZH-YtDlQ";
    private static final String CH = "zh_ytdl_q";
    private static final int NOTIF = 7201;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final AtomicBoolean INIT_OK = new AtomicBoolean(false);
    private static final AtomicBoolean INIT_TRIED = new AtomicBoolean(false);
    private static volatile String lastError = "";
    private static volatile Context appCtx;

    public enum Status { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

    public static final class Job {
        public final String id;
        public final String url;
        public final boolean audio;
        public String title;
        public volatile Status status = Status.QUEUED;
        public volatile int progress; // 0..100 or -1 indeterminate
        public volatile String message = "";
        public volatile long addedAt = System.currentTimeMillis();

        Job(String url, boolean audio, String title) {
            this.id = UUID.randomUUID().toString().substring(0, 8);
            this.url = url;
            this.audio = audio;
            this.title = title == null || title.isEmpty() ? url : title;
        }
    }

    private static final List<Job> QUEUE = Collections.synchronizedList(new ArrayList<>());
    private static final List<Runnable> LISTENERS = Collections.synchronizedList(new ArrayList<>());

    private YtDlQueue() {}

    public static void init(Context c) {
        appCtx = c.getApplicationContext();
        ensureChannel(appCtx);
        if (INIT_TRIED.getAndSet(true)) return;
        WORKER.execute(() -> {
            try {
                Class<?> yt = Class.forName("com.yausername.youtubedl_android.YoutubeDL");
                Object inst = yt.getMethod("getInstance").invoke(null);
                yt.getMethod("init", Context.class).invoke(inst, appCtx);
                try {
                    Class<?> ff = Class.forName("com.yausername.ffmpeg.FFmpeg");
                    Object ffi = ff.getMethod("getInstance").invoke(null);
                    ff.getMethod("init", Context.class).invoke(ffi, appCtx);
                } catch (Throwable ignored) {}
                INIT_OK.set(true);
                lastError = "";
                Log.i(TAG, "queue engine ready");
            } catch (ClassNotFoundException e) {
                lastError = "youtubedl-android not in build";
            } catch (Throwable t) {
                lastError = t.getMessage() == null ? t.toString() : t.getMessage();
            }
        });
    }

    public static boolean isReady() { return INIT_OK.get(); }
    public static String lastError() { return lastError == null ? "" : lastError; }

    public static List<Job> snapshot() {
        synchronized (QUEUE) {
            return new ArrayList<>(QUEUE);
        }
    }

    public static int queuedCount() {
        int n = 0;
        synchronized (QUEUE) {
            for (Job j : QUEUE)
                if (j.status == Status.QUEUED || j.status == Status.RUNNING) n++;
        }
        return n;
    }

    public static void addListener(Runnable r) { LISTENERS.add(r); }
    public static void removeListener(Runnable r) { LISTENERS.remove(r); }

    private static void notifyUi() {
        MAIN.post(() -> {
            for (Runnable r : new ArrayList<>(LISTENERS)) {
                try { r.run(); } catch (Throwable ignored) {}
            }
            refreshNotif();
        });
    }

    /** Enqueue a video or audio download. Returns job id. */
    public static String enqueue(Context ctx, String url, boolean audio, String title) {
        if (appCtx == null) init(ctx);
        String clean = strip(url);
        if (clean == null || !clean.startsWith("http")) {
            UiKit.makeToast(ctx, T.t("Only online links can be downloaded"), android.widget.Toast.LENGTH_SHORT).show();
            return null;
        }
        // de-dupe same url+audio still queued
        synchronized (QUEUE) {
            for (Job j : QUEUE) {
                if ((j.status == Status.QUEUED || j.status == Status.RUNNING)
                        && j.audio == audio && j.url.equals(clean)) {
                    UiKit.makeToast(ctx, T.t("Already in download queue"), android.widget.Toast.LENGTH_SHORT).show();
                    return j.id;
                }
            }
        }
        Job job = new Job(clean, audio, title);
        QUEUE.add(job);
        UiKit.makeToast(ctx, "⬇ " + T.t(audio ? "Audio queued" : "Video queued")
                + " · " + queuedCount(), android.widget.Toast.LENGTH_SHORT).show();
        notifyUi();
        pump();
        return job.id;
    }

    public static void cancel(String id) {
        synchronized (QUEUE) {
            for (Job j : QUEUE) {
                if (j.id.equals(id)) {
                    if (j.status == Status.QUEUED) {
                        j.status = Status.CANCELLED;
                        j.message = T.t("Cancelled");
                    } else if (j.status == Status.RUNNING) {
                        j.status = Status.CANCELLED;
                        j.message = T.t("Cancelling…");
                        try {
                            Class<?> yt = Class.forName("com.yausername.youtubedl_android.YoutubeDL");
                            Object inst = yt.getMethod("getInstance").invoke(null);
                            yt.getMethod("destroyProcessById", String.class).invoke(inst, j.id);
                        } catch (Throwable ignored) {}
                    }
                    break;
                }
            }
        }
        notifyUi();
        pump();
    }

    public static void clearFinished() {
        synchronized (QUEUE) {
            QUEUE.removeIf(j -> j.status == Status.DONE || j.status == Status.FAILED || j.status == Status.CANCELLED);
        }
        notifyUi();
    }

    public static void open(Context ctx) {
        ctx.startActivity(new Intent(ctx, YtDlActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private static void pump() {
        if (!RUNNING.compareAndSet(false, true)) return;
        WORKER.execute(() -> {
            try {
                while (true) {
                    Job next = null;
                    synchronized (QUEUE) {
                        for (Job j : QUEUE) {
                            if (j.status == Status.QUEUED) { next = j; break; }
                        }
                    }
                    if (next == null) break;
                    if (!INIT_OK.get()) {
                        init(appCtx);
                        try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
                        if (!INIT_OK.get()) {
                            next.status = Status.FAILED;
                            next.message = lastError.isEmpty() ? T.t("Downloader not ready") : lastError;
                            notifyUi();
                            continue;
                        }
                    }
                    runJob(next);
                }
            } finally {
                RUNNING.set(false);
                // if something was added while finishing, restart
                synchronized (QUEUE) {
                    for (Job j : QUEUE) {
                        if (j.status == Status.QUEUED) {
                            pump();
                            break;
                        }
                    }
                }
                notifyUi();
            }
        });
    }

    private static void runJob(Job job) {
        job.status = Status.RUNNING;
        job.progress = -1;
        job.message = T.t("Downloading…");
        notifyUi();
        try {
            Class<?> reqCl = Class.forName("com.yausername.youtubedl_android.YoutubeDLRequest");
            Object request = reqCl.getConstructor(String.class).newInstance(job.url);
            java.lang.reflect.Method opt1 = reqCl.getMethod("addOption", String.class);
            java.lang.reflect.Method opt2 = reqCl.getMethod("addOption", String.class, String.class);
            opt1.invoke(request, "--no-mtime");
            opt1.invoke(request, "--no-playlist");
            opt1.invoke(request, "--newline");
            opt1.invoke(request, "--no-check-certificates");
            File dir = downloadDir();
            String out = new File(dir, "%(title).80B [%(id)s].%(ext)s").getAbsolutePath();
            if (job.audio) {
                opt1.invoke(request, "-x");
                opt2.invoke(request, "--audio-format", "m4a");
                opt2.invoke(request, "--audio-quality", "0");
                opt2.invoke(request, "-f", "bestaudio[ext=m4a]/bestaudio/best");
            } else {
                opt2.invoke(request, "-f", "bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/best[ext=mp4]/best");
                opt2.invoke(request, "--merge-output-format", "mp4");
            }
            opt2.invoke(request, "-o", out);

            Class<?> yt = Class.forName("com.yausername.youtubedl_android.YoutubeDL");
            Object inst = yt.getMethod("getInstance").invoke(null);
            Object response;
            try {
                response = yt.getMethod("execute", reqCl, String.class).invoke(inst, request, job.id);
            } catch (NoSuchMethodException e) {
                response = yt.getMethod("execute", reqCl).invoke(inst, request);
            }
            if (job.status == Status.CANCELLED) {
                job.message = T.t("Cancelled");
                notifyUi();
                return;
            }
            int exit = 0;
            try {
                Object ec = response.getClass().getMethod("getExitCode").invoke(response);
                if (ec instanceof Integer) exit = (Integer) ec;
            } catch (Throwable ignored) {}
            scan(dir);
            if (exit == 0) {
                job.status = Status.DONE;
                job.progress = 100;
                job.message = T.t("Saved to") + " Download/ZHPlay";
            } else {
                job.status = Status.FAILED;
                job.message = T.t("Failed") + " (" + exit + ")";
            }
        } catch (Throwable t) {
            if (job.status != Status.CANCELLED) {
                job.status = Status.FAILED;
                Throwable c = t.getCause() != null ? t.getCause() : t;
                job.message = c.getMessage() == null ? c.toString() : c.getMessage();
            }
            Log.e(TAG, "job failed " + job.id, t);
        }
        notifyUi();
    }

    public static File downloadDir() {
        Context c = appCtx;
        File pub = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File dir = new File(pub, "ZHPlay");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        if (c != null && !dir.canWrite()) {
            dir = new File(c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "ZHPlay");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    private static void scan(File dir) {
        try {
            if (appCtx == null) return;
            File[] files = dir.listFiles();
            if (files == null) return;
            String[] paths = new String[files.length];
            for (int i = 0; i < files.length; i++) paths[i] = files[i].getAbsolutePath();
            MediaScannerConnection.scanFile(appCtx, paths, null, null);
        } catch (Throwable ignored) {}
    }

    private static String strip(String u) {
        if (u == null) return null;
        u = u.trim();
        int bar = u.indexOf('|');
        return bar > 0 ? u.substring(0, bar) : u;
    }

    private static void ensureChannel(Context c) {
        if (c == null || Build.VERSION.SDK_INT < 26) return;
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel(CH, "ZH Play downloads", NotificationManager.IMPORTANCE_LOW));
        } catch (Throwable ignored) {}
    }

    private static void refreshNotif() {
        if (appCtx == null) return;
        try {
            NotificationManager nm = (NotificationManager) appCtx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            ensureChannel(appCtx);
            int active = queuedCount();
            if (active == 0) {
                nm.cancel(NOTIF);
                return;
            }
            Job running = null;
            synchronized (QUEUE) {
                for (Job j : QUEUE) if (j.status == Status.RUNNING) { running = j; break; }
            }
            String title = active + " " + T.t("download(s)");
            String text = running != null ? running.title : T.t("Queued");
            Intent i = new Intent(appCtx, YtDlActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(appCtx, 0, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
            NotificationCompat.Builder b = new NotificationCompat.Builder(appCtx, CH)
                    .setContentTitle("⬇ " + title)
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(pi)
                    .setProgress(0, 0, true);
            nm.notify(NOTIF, b.build());
        } catch (Throwable ignored) {}
    }
}
