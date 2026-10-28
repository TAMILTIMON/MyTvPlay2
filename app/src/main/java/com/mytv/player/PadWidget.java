package com.mytv.player;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.SizeF;
import android.widget.RemoteViews;

import java.util.HashMap;
import java.util.Map;

/**
 * Full TV remote as a home-screen widget (no need to open ZH Play). Resizable:
 * small = D-pad · medium = D-pad + back / home / menu + volume · wide = side by side · large = everything.
 * Buttons go through the TV control service (the same connection as the notification remote).
 */
public class PadWidget extends AppWidgetProvider {
    // button id → TV key name (or a player command)
    private static final Object[][] KEYS = {
            {R.id.wp_up, "up"}, {R.id.wp_down, "down"}, {R.id.wp_left, "left"}, {R.id.wp_right, "right"}, {R.id.wp_ok, "ok"},
            {R.id.wp_back, "back"}, {R.id.wp_home, "home"}, {R.id.wp_menu, "menu"},
            {R.id.wp_vdown, "vol_down"}, {R.id.wp_mute, "vol_mute"}, {R.id.wp_vup, "vol_up"}};

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) m.updateAppWidget(id, views(c, m.getAppWidgetOptions(id)));
    }

    @Override
    public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle opts) {
        m.updateAppWidget(id, views(c, opts));       // resized: pick the layout that fits
    }

    /** Refresh every placed remote widget (e.g. after switching TV). */
    public static void refreshAll(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, PadWidget.class));
        for (int id : ids) m.updateAppWidget(id, views(c, m.getAppWidgetOptions(id)));
    }

    private static RemoteViews views(Context c, Bundle opts) {
        if (Build.VERSION.SDK_INT >= 31) {
            // Android 12+: the launcher picks the best layout while you resize
            Map<SizeF, RemoteViews> m = new HashMap<>();
            m.put(new SizeF(100f, 100f), build(c, R.layout.widget_pad_s));
            m.put(new SizeF(170f, 230f), build(c, R.layout.widget_pad_m));
            m.put(new SizeF(250f, 110f), build(c, R.layout.widget_pad_w));
            m.put(new SizeF(250f, 330f), build(c, R.layout.widget_pad_l));
            return new RemoteViews(m);
        }
        int w = opts == null ? 0 : opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH);
        int h = opts == null ? 0 : opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT);
        int layout;
        if (w >= 250 && h >= 330) layout = R.layout.widget_pad_l;
        else if (w >= 250 && h < 230) layout = R.layout.widget_pad_w;
        else if (w >= 170 && h >= 230) layout = R.layout.widget_pad_m;
        else layout = R.layout.widget_pad_s;
        return build(c, layout);
    }

    private static RemoteViews build(Context c, int layout) {
        RemoteViews v = new RemoteViews(c.getPackageName(), layout);
        String tv = Store.getStr("last_tv", "");
        if (layout != R.layout.widget_pad_s) {
            v.setTextViewText(R.id.wp_title, tv.isEmpty() ? T.t("Open ZH Play once to pick your TV") : "ZH Play · " + tv);
            Intent open = new Intent(c, ConnectActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            v.setOnClickPendingIntent(R.id.wp_title, PendingIntent.getActivity(c, 300, open, flags()));
            v.setOnClickPendingIntent(R.id.wp_logo, PendingIntent.getActivity(c, 301, open, flags()));
            v.setOnClickPendingIntent(R.id.wp_power, service(c, TvControlService.A_POWER, null, 302));
        }
        for (int i = 0; i < KEYS.length; i++) {
            v.setOnClickPendingIntent((Integer) KEYS[i][0], service(c, TvControlService.A_KEY, (String) KEYS[i][1], 310 + i));
        }
        if (layout == R.layout.widget_pad_l) {
            v.setOnClickPendingIntent(R.id.wp_rew, service(c, TvControlService.A_REW, null, 330));
            v.setOnClickPendingIntent(R.id.wp_play, service(c, TvControlService.A_TOGGLE, null, 331));
            v.setOnClickPendingIntent(R.id.wp_ff, service(c, TvControlService.A_FF, null, 332));
            v.setOnClickPendingIntent(R.id.wp_next, service(c, TvControlService.A_NEXT, null, 333));
        }
        return v;
    }

    private static int flags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
    }

    /** One pending action per button (the data URI keeps them apart). */
    private static PendingIntent service(Context c, String action, String key, int req) {
        Intent i = new Intent(c, TvControlService.class).setAction(action)
                .setData(Uri.parse("zhplay://pad/" + (key == null ? action : key)));
        if (key != null) i.putExtra("k", key);
        return Build.VERSION.SDK_INT >= 26 ? PendingIntent.getForegroundService(c, req, i, flags()) : PendingIntent.getService(c, req, i, flags());
    }
}
