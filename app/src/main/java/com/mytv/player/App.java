package com.mytv.player;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

public class App extends Application {
    public static final String DEFAULT_IPTV = "https://iptv-org.github.io/iptv/index.m3u";
    public static final String DEFAULT_IPTV_NAME = "IPTV · World (iptv-org)";

    /** Adds the iptv-org channel list once on TVs. Delete or rename it like any playlist; it won't come back. */
    private void seedIptv() {
        if (Store.getBool("seed_done", false) || !Ui.isTv(this)) return;
        new Thread(() -> {
            try {
                java.util.List<Store.Item> l = Store.parseM3u(Net.fetchText(DEFAULT_IPTV));
                if (l.isEmpty()) return;
                Store.replaceAll(DEFAULT_IPTV_NAME, l);
                Store.putStr("plsrc_" + DEFAULT_IPTV_NAME, DEFAULT_IPTV);
                Store.putBool("seed_done", true);
            } catch (Exception ignored) {
                // offline: try again next start
            }
        }).start();
    }
    /** Number of visible activities of this app (0 = app is in the background). */
    public static volatile int visible = 0;

    /** The ZH Play screen in front (typing from the phone goes straight into its text box). */
    public static volatile java.lang.ref.WeakReference<Activity> top;

    /** "Exit ZH Play" (phone): asks first, then closes everything. */
    public static void confirmExit(final Activity a) {
        new UiDialog(a).setTitle("⏻  " + T.t("Exit ZH Play"))
                .setMessage(T.t("Closes ZH Play completely to save battery: stops music, browser audio, screen casting, the VPN, torrent downloads and the background remote service, and clears all ZH Play notifications. Open the app again any time to start it."))
                .setPositiveButton(T.t("Exit"), (d, w) -> exitAll(a))
                .setNegativeButton(T.t("Cancel"), null).show();
    }

    /** Stops every service, removes every notification, closes every screen, ends the process. */
    public static void exitAll(Activity a) {
        final android.content.Context c = a.getApplicationContext();
        Store.putBool("exited", true);
        Class<?>[] services = {MusicService.class, BrowserPlayService.class, CastScreenService.class,
                TvControlService.class, MediaWatch.class, RemoteService.class};
        for (Class<?> s : services) {
            try {
                c.stopService(new android.content.Intent(c, s));
            } catch (Throwable ignored) {
            }
        }
        final android.app.NotificationManager nm = (android.app.NotificationManager) c.getSystemService(android.content.Context.NOTIFICATION_SERVICE);
        try {
            if (nm != null) nm.cancelAll();
        } catch (Throwable ignored) {
        }
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) c.getSystemService(android.content.Context.ACTIVITY_SERVICE);
            if (am != null && android.os.Build.VERSION.SDK_INT >= 21)
                for (android.app.ActivityManager.AppTask t : am.getAppTasks()) t.finishAndRemoveTask();
        } catch (Throwable ignored) {
        }
        try {
            a.finishAffinity();
        } catch (Throwable ignored) {
        }
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            try {
                if (nm != null) nm.cancelAll();          // anything a stopping service posted on its way out
            } catch (Throwable ignored) {
            }
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(0);
        }, 500);
    }

    public static Activity top() {
        java.lang.ref.WeakReference<Activity> r = top;
        Activity a = r == null ? null : r.get();
        return a == null || a.isFinishing() ? null : a;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        TrackerList.warmUp(this);   // fetch & rank public trackers in background
        Store.init(this);
        // remember why the app closed, to show it next time (helps fixing problems)
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((th, ex) -> {
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                ex.printStackTrace(new java.io.PrintWriter(sw));
                String st = sw.toString();
                Store.putStr("lastcrash", st.length() > 3000 ? st.substring(0, 3000) : st);
            } catch (Throwable ignored) {
            }
            if (prev != null) prev.uncaughtException(th, ex);
        });
        Net.cleanSubtitleCache(this);
        TorrentEngine.cleanupAsync(this);
        // after "Exit ZH Play" the phone stays fully closed until the user opens the app again
        Auth.noteInstall(this);
        if (Ui.isTv(this) || !Store.getBool("exited", false)) RemoteService.start(this);
        seedIptv();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            public void onActivityStarted(Activity a) {
                visible++;
                // Google sign-in / admin gate (admin/access.json on GitHub)
                if (Cloud.signedIn() && Cloud.cachedIdToken().isEmpty()) {
                    new Thread(() -> {                                // keep the account proof fresh (1 h tokens)
                        try {
                            Cloud.idToken();
                        } catch (Exception ignored) {
                        }
                    }).start();
                }
                if (!(a instanceof AuthActivity)) {
                    if (Auth.locked(a)) AuthActivity.open(a);
                    Auth.refresh(a, false, () -> {
                        if (a.isFinishing() || visible == 0) return;
                        if (Auth.locked(a)) AuthActivity.open(a);
                        else if (Auth.NOTICE.equals(Auth.state(a)) && !Auth.noticeShown) {
                            Auth.noticeShown = true;                 // 1-day notice: once per app start
                            AuthActivity.open(a);
                        }
                    });
                }
                if (Store.getBool("exited", false)) {          // opened again after Exit: back to normal
                    Store.putBool("exited", false);
                    RemoteService.start(a.getApplicationContext());
                }
            }
            public void onActivityStopped(Activity a) { visible = Math.max(0, visible - 1); }
            public void onActivityCreated(Activity a, Bundle b) { }
            public void onActivityResumed(Activity a) { top = new java.lang.ref.WeakReference<>(a); }
            public void onActivityPaused(Activity a) { }
            public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            public void onActivityDestroyed(Activity a) { }
        });
    }
}
