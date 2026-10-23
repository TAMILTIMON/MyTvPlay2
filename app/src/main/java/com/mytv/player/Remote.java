package com.mytv.player;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Bridge between the phone web page (background threads) and the player on the TV (main thread). */
public final class Remote {
    private Remote() {
    }

    public static volatile PlayerActivity player;
    public static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static ArrayList<Store.Item> handoff;

    /** Runs code on the TV's main thread and waits for the answer. */
    public static <T> T call(final Callable<T> c) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return c.call();
        final Object[] box = new Object[2];
        final CountDownLatch latch = new CountDownLatch(1);
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    box[0] = c.call();
                } catch (Throwable t) {
                    box[1] = t;
                }
                latch.countDown();
            }
        });
        if (!latch.await(5, TimeUnit.SECONDS)) throw new Exception("The TV did not answer in time");
        if (box[1] != null) throw new Exception(String.valueOf(((Throwable) box[1]).getMessage()));
        @SuppressWarnings("unchecked") T r = (T) box[0];
        return r;
    }

    public static synchronized ArrayList<Store.Item> takeHandoff() {
        ArrayList<Store.Item> l = handoff;
        handoff = null;
        return l;
    }

    /** Opens the player with this list (or replaces what is playing). */
    public static void play(Context ctx, List<Store.Item> items, int index) {
        synchronized (Remote.class) {
            handoff = new ArrayList<>(items);
        }
        Intent i = new Intent(ctx, PlayerActivity.class);
        i.putExtra(PlayerActivity.EXTRA_HANDOFF, true);
        i.putExtra(PlayerActivity.EXTRA_INDEX, index);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        ctx.startActivity(i);
    }

    public static void openHome(Context ctx) {
        Intent i = new Intent(ctx, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        ctx.startActivity(i);
    }

    public static int keyCode(String k) {
        switch (k) {
            case "up": return KeyEvent.KEYCODE_DPAD_UP;
            case "down": return KeyEvent.KEYCODE_DPAD_DOWN;
            case "left": return KeyEvent.KEYCODE_DPAD_LEFT;
            case "right": return KeyEvent.KEYCODE_DPAD_RIGHT;
            case "ok": return KeyEvent.KEYCODE_DPAD_CENTER;
            case "back": return KeyEvent.KEYCODE_BACK;
            case "menu": return KeyEvent.KEYCODE_MENU;
            case "playpause": return KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
            default: return -1;
        }
    }

    /**
     * Presses a key inside this app (works on menus and dialogs too).
     * Must be called from a background thread.
     */
    /** System keys that work everywhere on the TV, even without the accessibility switch. */
    public static String systemKey(Context ctx, String k) {
        android.media.AudioManager am = (android.media.AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return "No audio";
        int s = android.media.AudioManager.STREAM_MUSIC, ui = android.media.AudioManager.FLAG_SHOW_UI;
        switch (k) {
            case "vol_up": am.adjustStreamVolume(s, android.media.AudioManager.ADJUST_RAISE, ui); return null;
            case "vol_down": am.adjustStreamVolume(s, android.media.AudioManager.ADJUST_LOWER, ui); return null;
            case "vol_mute": am.adjustStreamVolume(s, android.media.AudioManager.ADJUST_TOGGLE_MUTE, ui); return null;
            default: break;
        }
        int code;
        switch (k) {
            case "media_playpause": code = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE; break;
            case "media_next": code = KeyEvent.KEYCODE_MEDIA_NEXT; break;
            case "media_prev": code = KeyEvent.KEYCODE_MEDIA_PREVIOUS; break;
            case "media_ff": code = KeyEvent.KEYCODE_MEDIA_FAST_FORWARD; break;
            case "media_rew": code = KeyEvent.KEYCODE_MEDIA_REWIND; break;
            case "media_stop": code = KeyEvent.KEYCODE_MEDIA_STOP; break;
            default: return "Unknown key";
        }
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
        return null;
    }

    /** Arrows / OK / Back / Home / Recents: our own screens directly, any other app via TvAccess. */
    public static String press(Context ctx, String k) {
        if (k.startsWith("vol_") || k.startsWith("media_")) return systemKey(ctx, k);
        final TvAccess acc = TvAccess.inst;
        boolean ours = App.visible > 0;
        // ADB mode: real key presses that work in every app (YouTube too)
        if (AdbClient.enabled() && !(ours && !k.equals("home") && !k.equals("recents"))) {
            int kc = adbCode(k);
            if (kc > 0) {
                String err = AdbClient.key(ctx, kc);
                if (err == null) return null;
                if (acc == null && !ours) return err;
            }
        }
        if (k.equals("home") || k.equals("recents")) {
            if (acc != null) return acc.key(k) ? null : "Not possible on this TV";
            if (k.equals("home")) {
                openHome(ctx);
                return null;
            }
            return needAccess();
        }
        if (ours) return pressKey(keyCode(k));
        if (acc != null) return acc.key(k) ? null
                : "This app doesn't accept accessibility keys. Turn on the ADB full remote (TV ⚙ Settings, or the 🔌 button)";
        return needAccess();
    }

    private static int adbCode(String k) {
        switch (k) {
            case "up": return 19;
            case "down": return 20;
            case "left": return 21;
            case "right": return 22;
            case "ok": return 23;
            case "back": return 4;
            case "home": return 3;
            case "recents": return 187;
            case "menu": return 82;
            default: return -1;
        }
    }

    public static String needAccess() {
        return "To control other apps, turn on TV Settings > Accessibility > TamilCastPlay (⚙ Settings in the app opens it)";
    }

    public static String type(Context ctx, String text) {
        if (App.visible > 0) {
            try {
                new Instrumentation().sendStringSync(text);
                return null;
            } catch (Throwable ignored) {
            }
        }
        if (AdbClient.enabled() && AdbClient.text(ctx, text) == null) return null;
        TvAccess acc = TvAccess.inst;
        if (acc == null) return needAccess();
        return acc.type(text) ? null : "Select a search / text box on the TV first";
    }

    public static String pressKey(final int code) {
        if (code < 0) return "Unknown key";
        try {
            new Instrumentation().sendKeyDownUpSync(code);
            return null;
        } catch (Throwable t) {
            // App is not in front: try the player directly.
            final PlayerActivity p = player;
            if (p != null) {
                try {
                    call(new Callable<Boolean>() {
                        @Override
                        public Boolean call() {
                            return p.handleKey(code, 0);
                        }
                    });
                    return null;
                } catch (Exception ignored) {
                }
            }
            return "Open TamilCastPlay on the TV first";
        }
    }
}
