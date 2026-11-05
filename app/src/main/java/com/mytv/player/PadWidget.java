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
            v.setOnClickPendingIntent(R.id.wp_voice, PendingIntent.getActivity(c, 303, voiceIntent(c), flags()));
        }
        for (int i = 0; i < KEYS.length; i++) {
            v.setOnClickPendingIntent((Integer) KEYS[i][0], service(c, TvControlService.A_KEY, (String) KEYS[i][1], 310 + i));
        }
        theme(c, v, R.id.wp_root, layout != R.layout.widget_pad_s ? R.id.wp_title : 0, R.id.wp_ok,
                layout != R.layout.widget_pad_s ? R.id.wp_voice : 0,
                new int[]{R.id.wp_up, R.id.wp_down, R.id.wp_left, R.id.wp_right, R.id.wp_back, R.id.wp_home, R.id.wp_menu,
                        R.id.wp_vdown, R.id.wp_mute, R.id.wp_vup},
                layout == R.layout.widget_pad_l ? new int[]{R.id.wp_rew, R.id.wp_play, R.id.wp_ff, R.id.wp_next} : new int[0]);
        if (layout == R.layout.widget_pad_l) {
            v.setOnClickPendingIntent(R.id.wp_rew, service(c, TvControlService.A_REW, null, 330));
            v.setOnClickPendingIntent(R.id.wp_play, service(c, TvControlService.A_TOGGLE, null, 331));
            v.setOnClickPendingIntent(R.id.wp_ff, service(c, TvControlService.A_FF, null, 332));
            v.setOnClickPendingIntent(R.id.wp_next, service(c, TvControlService.A_NEXT, null, 333));
        }
        return v;
    }

    /** Mic on the widget: the remote opens and listens at once (type on the TV, search, ask the AI…). */
    static Intent voiceIntent(Context c) {
        String tv = Store.getStr("last_tv", "");
        Intent i = tv.isEmpty() ? new Intent(c, ConnectActivity.class)
                : new Intent(c, NativeRemoteActivity.class).putExtra(NativeRemoteActivity.EXTRA_IP, tv)
                .putExtra(NativeRemoteActivity.EXTRA_NAME, "TV").putExtra(NativeRemoteActivity.EXTRA_VOICE, true);
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    /**
     * The widget in the app's own colours (the theme and accent chosen in ZH Play).
     * Icons are tinted on every Android version; backgrounds (panel, keys, OK) from Android 12 on.
     */
    static void theme(Context c, RemoteViews v, int root, int title, int ok, int voice, int[] keys, int[] extra) {
        try {
            Store.init(c);
            boolean dark = Ui.isDark(c);
            int[] p = Palette.resolve(dark);
            int accent = p[0], bg = p[3], text = p[7];
            boolean tintBg = Build.VERSION.SDK_INT >= 31;
            boolean darkBg = !tintBg || Palette.lum(bg) < 0.5f;     // before Android 12 the panel stays dark
            int icon = darkBg ? 0xFFFFFFFF : text;
            for (int id : keys) v.setInt(id, "setColorFilter", icon);
            for (int id : extra) v.setInt(id, "setColorFilter", icon);
            if (voice != 0) v.setInt(voice, "setColorFilter", accent);
            if (title != 0) v.setTextColor(title, darkBg ? 0xFFFFFFFF : text);
            if (tintBg) {
                v.setColorStateList(root, "setBackgroundTintList", android.content.res.ColorStateList.valueOf(0xF2000000 | (bg & 0xFFFFFF)));
                android.content.res.ColorStateList key = android.content.res.ColorStateList.valueOf(darkBg ? 0x26FFFFFF : 0x1A000000);
                for (int id : keys) v.setColorStateList(id, "setBackgroundTintList", key);
                for (int id : extra) v.setColorStateList(id, "setBackgroundTintList", key);
                if (voice != 0) v.setColorStateList(voice, "setBackgroundTintList", android.content.res.ColorStateList.valueOf((accent & 0xFFFFFF) | 0x33000000));
                if (ok != 0) v.setColorStateList(ok, "setBackgroundTintList", android.content.res.ColorStateList.valueOf(accent));
            }
        } catch (Throwable ignored) {
        }
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
