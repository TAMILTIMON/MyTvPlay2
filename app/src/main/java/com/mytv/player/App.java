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

    @Override
    public void onCreate() {
        super.onCreate();
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
        RemoteService.start(this);
        seedIptv();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            public void onActivityStarted(Activity a) { visible++; }
            public void onActivityStopped(Activity a) { visible = Math.max(0, visible - 1); }
            public void onActivityCreated(Activity a, Bundle b) { }
            public void onActivityResumed(Activity a) { }
            public void onActivityPaused(Activity a) { }
            public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            public void onActivityDestroyed(Activity a) { }
        });
    }
}
