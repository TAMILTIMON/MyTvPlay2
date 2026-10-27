package com.mytv.player;

import android.content.Context;

import org.json.JSONObject;

/** Slow TV checks (keyboard open, other apps' media) done in the background while phones are connected. */
public final class BgState {
    private BgState() {
    }

    public static volatile boolean kbd;
    public static volatile JSONObject other;
    private static volatile long lastAsk;
    private static boolean running;

    /** Called on every status request; keeps the checker alive for 15 s after the last one. */
    public static synchronized void wake(final Context c) {
        lastAsk = System.currentTimeMillis();
        if (running) return;
        running = true;
        new Thread(() -> {
            while (System.currentTimeMillis() - lastAsk < 15000) {
                try {
                    kbd = FastKeys.imeShown(c) || System.currentTimeMillis() - TvAccess.editFocusAt < 5000;
                    other = Remote.player == null ? SessionWatcher.info(c) : null;
                    Thread.sleep(700);
                } catch (Throwable ignored) {
                }
            }
            synchronized (BgState.class) {
                running = false;
            }
        }, "bgstate").start();
    }
}
