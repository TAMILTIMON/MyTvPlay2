package com.mytv.player;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Auto-close: when ZH Play has been in the background (not used) for the chosen time, it closes completely —
 * the same as "Exit ZH Play": services stopped, notifications cleared, removed from Recent apps, no battery use.
 * Coming back to ZH Play before then cancels it. While music / background browser audio / screen casting is
 * running it waits (checks again 15 minutes later), unless the person turned that off.
 */
final class AutoExit {
    private AutoExit() {
    }

    /** Choices in minutes (0 = off). */
    static final int[] MINUTES = {0, 15, 30, 60, 120, 360, 720, 1440, 4320};

    static int minutes() {
        return Store.getInt("auto_exit_min", 0);
    }

    static boolean waitWhileBusy() {
        return Store.getBool("auto_exit_wait", true);
    }

    static String label(int min) {
        if (min <= 0) return T.t("Off");
        if (min < 60) return min + " " + T.t("min");
        if (min < 1440) return (min / 60) + " " + T.t(min == 60 ? "hour" : "hours") + (min % 60 > 0 ? " " + (min % 60) + " " + T.t("min") : "");
        return (min / 1440) + " " + T.t(min == 1440 ? "day" : "days") + (min % 1440 >= 60 ? " " + (min % 1440 / 60) + " " + T.t("hours") : "");
    }

    private static PendingIntent pi(Context c) {
        return PendingIntent.getBroadcast(c, 4501, new Intent(c, Fire.class),
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
    }

    /** ZH Play just went to the background: start the countdown. */
    static void onBackground(Context c) {
        int min = minutes();
        if (min <= 0) return;
        schedule(c.getApplicationContext(), min * 60_000L);
    }

    /** ZH Play is in use again: no countdown. */
    static void onForeground(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(pi(c.getApplicationContext()));
        } catch (Throwable ignored) {
        }
    }

    private static void schedule(Context c, long inMs) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            long at = System.currentTimeMillis() + inMs;
            if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(c));
            else am.set(AlarmManager.RTC_WAKEUP, at, pi(c));
        } catch (Throwable ignored) {
        }
    }

    /** Music or background browser audio is actually PLAYING, or the screen is being cast, right now.
     *  (A paused song's notification doesn't count — otherwise auto-close would wait forever.) */
    static boolean busy(Context c) {
        try {
            return MusicService.playing() || BrowserPlayService.playing || CastScreenService.running || Updater.busy();
        } catch (Throwable e) {
            return false;
        }
    }

    /** The countdown ended. */
    public static class Fire extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            if (minutes() <= 0 || App.visible > 0) return;          // turned off, or in use again
            if (waitWhileBusy() && busy(c)) {
                schedule(c.getApplicationContext(), 15 * 60_000L);   // music still playing: look again later
                return;
            }
            App.exitAll(c.getApplicationContext());
        }
    }

    // ------------------------------------------------------------------ the setting

    static void settings(final Activity a, final Runnable changed) {
        String[] items = new String[MINUTES.length + 2];
        for (int k = 0; k < MINUTES.length; k++) items[k] = (MINUTES[k] == minutes() ? "● " : "○ ") + label(MINUTES[k]);
        boolean custom = minutes() > 0 && java.util.Arrays.binarySearch(MINUTES, minutes()) < 0;
        items[MINUTES.length] = (custom ? "● " : "○ ") + T.t("Custom…") + (custom ? "  (" + label(minutes()) + ")" : "");
        items[MINUTES.length + 1] = "🎵  " + T.t("Wait while music / casting is playing") + ": " + T.t(waitWhileBusy() ? "On" : "Off");
        new UiDialog(a).setTitle("⏻  " + T.t("Close ZH Play when not used for"))
                .setItems(items, (d, w) -> {
                    if (w < MINUTES.length) {
                        set(a, MINUTES[w], changed);
                    } else if (w == MINUTES.length) {
                        customDialog(a, changed);
                    } else {
                        Store.putBool("auto_exit_wait", !waitWhileBusy());
                        settings(a, changed);
                    }
                }).show();
    }

    private static void customDialog(final Activity a, final Runnable changed) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(a);
        row.setPadding(Ui.dp(a, 20), Ui.dp(a, 8), Ui.dp(a, 20), 0);
        final android.widget.EditText num = new android.widget.EditText(a);
        num.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        num.setHint("45");
        row.addView(num, new android.widget.LinearLayout.LayoutParams(0, -2, 1));
        final String[] units = {T.t("minutes"), T.t("hours"), T.t("days")};
        final int[] mult = {1, 60, 1440};
        final int[] unit = {0};
        final android.widget.TextView u = new android.widget.TextView(a);
        u.setText(units[0] + "  ▾");
        u.setTextSize(16);
        u.setTextColor(Ui.TEXT);
        u.setPadding(Ui.dp(a, 12), Ui.dp(a, 10), Ui.dp(a, 4), Ui.dp(a, 10));
        u.setOnClickListener(v -> new UiDialog(a).setItems(units, (d, w) -> {
            unit[0] = w;
            u.setText(units[w] + "  ▾");
        }).show());
        row.addView(u);
        new UiDialog(a).setTitle(T.t("Close ZH Play after")).setView(row)
                .setPositiveButton(T.t("Set"), (d, w) -> {
                    try {
                        int n = Integer.parseInt(num.getText().toString().trim());
                        if (n > 0) set(a, Math.min(n * mult[unit[0]], 30 * 1440), changed);
                    } catch (Exception ignored) {
                    }
                })
                .setNegativeButton(T.t("Cancel"), null).show();
    }

    private static void set(final Activity a, int min, Runnable changed) {
        Runnable apply = () -> {
            Store.putInt("auto_exit_min", min);
            UiKit.makeToast(a, min <= 0 ? T.t("Auto-close is off")
                    : "⏻ " + T.t("ZH Play closes itself after") + " " + label(min) + " " + T.t("of not being used"), android.widget.Toast.LENGTH_LONG).show();
            if (changed != null) changed.run();
        };
        if (min > 0 && Ui.isTv(a)) {
            // on a TV the app also answers the phone remote in the background
            ZhPopup.confirm(a, "📺", T.t("Auto-close on this TV?"),
                    T.t("When ZH Play closes, the phone remote can't reach this TV until ZH Play is opened again."),
                    T.t("Set"), false, apply);
        } else {
            apply.run();
        }
    }
}
