package com.mytv.player;

import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.service.notification.NotificationListenerService;

/**
 * Needed by Android so ZH Play may see "what's playing" in other apps (YouTube, Netflix…).
 * Switched on once: TV ⚙ Settings > "Show other apps' videos on the remote".
 * While ZH Play is exited (Exit / auto-close) it lets go, so Android doesn't keep ZH Play running for it.
 */
public class MediaWatch extends NotificationListenerService {
    private static volatile MediaWatch inst;

    @Override
    public void onListenerConnected() {
        inst = this;
        if (Store.getBool("exited", false)) unbindNow();
    }

    @Override
    public void onListenerDisconnected() {
        inst = null;
    }

    /** ZH Play is closing: stop being kept alive by Android for this listener. */
    static void unbindNow() {
        MediaWatch m = inst;
        if (m == null || Build.VERSION.SDK_INT < 24) return;
        try {
            m.requestUnbind();
        } catch (Throwable ignored) {
        }
    }

    /** ZH Play opened again: listen again (if the person allowed it). */
    static void rebind(Context c) {
        if (Build.VERSION.SDK_INT < 24) return;
        try {
            NotificationListenerService.requestRebind(new ComponentName(c, MediaWatch.class));
        } catch (Throwable ignored) {
        }
    }
}
