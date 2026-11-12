package com.mytv.player;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.widget.RemoteViews;

/**
 * A ZH Play feature as its own 1×1 home-screen icon. Each feature is a separate entry in the
 * phone's widget list (long-press the home screen → Widgets → ZH Play → ZH Browser, ZH VPN, …).
 */
public class FeatureWidget extends AppWidgetProvider {

    /** Which feature this widget opens (each subclass below names one). */
    protected String featureId() {
        return "browser";
    }

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) {
            try {
                m.updateAppWidget(id, views(c, featureId()));
            } catch (Throwable ignored) {
            }
        }
    }

    static RemoteViews views(Context c, String fid) {
        Shortcuts.Item it = Shortcuts.find(fid);
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_feature);
        if (it == null) return v;
        v.setImageViewBitmap(R.id.fw_icon, Shortcuts.icon(c, it.icon, it.color));
        v.setTextViewText(R.id.fw_label, it.label);
        Intent open = new Intent(c, ShortcutLaunchActivity.class).setAction(Intent.ACTION_VIEW)
                .putExtra(ShortcutLaunchActivity.EXTRA_ID, it.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        v.setOnClickPendingIntent(R.id.fw_root, PendingIntent.getActivity(c, 7000 + Shortcuts.indexOf(it.id), open, flags));
        return v;
    }

    // ---- one per feature: each is its own entry in the widget list
    public static class Browser extends FeatureWidget {
        @Override protected String featureId() { return "browser"; }
    }

    public static class Player extends FeatureWidget {
        @Override protected String featureId() { return "player"; }
    }

    public static class Videos extends FeatureWidget {
        @Override protected String featureId() { return "videos"; }
    }

    public static class Music extends FeatureWidget {
        @Override protected String featureId() { return "music"; }
    }

    public static class Gallery extends FeatureWidget {
        @Override protected String featureId() { return "gallery"; }
    }

    public static class Vpn extends FeatureWidget {
        @Override protected String featureId() { return "vpn"; }
    }

    public static class Seedr extends FeatureWidget {
        @Override protected String featureId() { return "seedr"; }
    }

    public static class Remote extends FeatureWidget {
        @Override protected String featureId() { return "remote"; }
    }

    public static class Files extends FeatureWidget {
        @Override protected String featureId() { return "files"; }
    }

    public static class Board extends FeatureWidget {
        @Override protected String featureId() { return "board"; }
    }

    public static class Dj extends FeatureWidget {
        @Override protected String featureId() { return "dj"; }
    }

    public static class Editor extends FeatureWidget {
        @Override protected String featureId() { return "editor"; }
    }

    public static class Library extends FeatureWidget {
        @Override protected String featureId() { return "library"; }
    }

    public static class Eq extends FeatureWidget {
        @Override protected String featureId() { return "eq"; }
    }
}
