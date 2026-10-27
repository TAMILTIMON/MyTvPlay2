package com.mytv.player;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.widget.RemoteViews;

/** Home-screen widget: what's playing, ±10 s, play / pause, TV volume, mute, power. */
public class TvWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        Store.init(c);
        update(c, null, null, false);
    }

    private static PendingIntent act(Context c, String action, int req) {
        Intent i = new Intent(c, TvControlService.class).setAction(action);
        int f = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return Build.VERSION.SDK_INT >= 26 ? PendingIntent.getForegroundService(c, 100 + req, i, f)
                : PendingIntent.getService(c, 100 + req, i, f);
    }

    public static void update(Context c, String title, String sub, boolean playing) {
        try {
            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_remote);
            String tv = Store.getStr("last_tv", "");
            v.setTextViewText(R.id.w_title, title == null || title.isEmpty() ? "ZH Play" : title);
            v.setTextViewText(R.id.w_sub, sub != null ? UiKit.noEmoji(sub) : tv.isEmpty() ? "Open the app once to pick your TV" : "Tap ▶ to connect · " + tv);
            v.setImageViewResource(R.id.w_play, playing ? R.drawable.lc_pause : R.drawable.lc_play);
            v.setOnClickPendingIntent(R.id.w_play, act(c, TvControlService.A_TOGGLE, 1));
            v.setOnClickPendingIntent(R.id.w_rew, act(c, TvControlService.A_REW, 2));
            v.setOnClickPendingIntent(R.id.w_ff, act(c, TvControlService.A_FF, 3));
            v.setOnClickPendingIntent(R.id.w_vdown, act(c, TvControlService.A_VOLDOWN, 4));
            v.setOnClickPendingIntent(R.id.w_mute, act(c, TvControlService.A_MUTE, 5));
            v.setOnClickPendingIntent(R.id.w_vup, act(c, TvControlService.A_VOLUP, 6));
            v.setOnClickPendingIntent(R.id.w_power, act(c, TvControlService.A_POWER, 7));
            Intent open = tv.isEmpty() ? new Intent(c, ConnectActivity.class)
                    : new Intent(c, NativeRemoteActivity.class).putExtra(NativeRemoteActivity.EXTRA_IP, tv)
                    .putExtra(NativeRemoteActivity.EXTRA_NAME, "TV");
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            int f = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
            v.setOnClickPendingIntent(R.id.w_title, PendingIntent.getActivity(c, 99, open, f));
            AppWidgetManager.getInstance(c).updateAppWidget(new ComponentName(c, TvWidget.class), v);
        } catch (Throwable ignored) {
        }
    }
}
