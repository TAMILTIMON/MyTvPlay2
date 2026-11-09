package com.mytv.player;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Build;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ZH Play on the Android TV home screen ("Watch Next" / "Continue watching" row), Android 8+ TVs:
 *   - movies stopped part-way, with progress — select = resume at that point
 *   - the admin's broadcast: "🔴 LIVE NOW" or "📅 … at 8:00 PM" — select = watch
 * Uses Android's own TV provider (no library). Each row is ours (internal id "cw:…" / "bc:…") and is
 * updated or removed as things change. Launchers that don't show this row simply ignore it.
 */
final class TvHome {
    private TvHome() {
    }

    private static final int MAX_CONTINUE = 8;
    private static volatile long lastRun;
    private static volatile boolean pending;

    /** Updates the TV home row (debounced; safe to call often, from any thread). */
    static void refresh(final Context ctx) {
        if (ctx == null || Build.VERSION.SDK_INT < 26 || !Ui.isTv(ctx) || pending) return;
        pending = true;
        final Context c = ctx.getApplicationContext();
        long wait = Math.max(0, 3000 - (System.currentTimeMillis() - lastRun));
        Remote.MAIN.postDelayed(() -> new Thread(() -> {
            try {
                sync(c);
            } catch (Throwable ignored) {
                // launcher without a TV provider, or it refused: nothing to do
            } finally {
                lastRun = System.currentTimeMillis();
                pending = false;
            }
        }).start(), wait);
    }

    private static void sync(Context c) {
        ContentResolver cr = c.getContentResolver();
        Uri all = TvContract.WatchNextPrograms.CONTENT_URI;

        // what we put there before: internal id → row id
        Map<String, Long> mine = new HashMap<>();
        try (Cursor q = cr.query(all, new String[]{TvContract.WatchNextPrograms._ID,
                TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID}, null, null, null)) {
            if (q != null) while (q.moveToNext()) {
                String id = q.getString(1);
                if (id != null && (id.startsWith("cw:") || id.startsWith("bc:"))) mine.put(id, q.getLong(0));
            }
        }

        Set<String> keep = new HashSet<>();
        Uri logo = Uri.parse("android.resource://" + c.getPackageName() + "/drawable/zh_logo");

        // ---- continue watching (not broadcasts, not finished)
        List<Store.Watch> hist = Store.history();
        int n = 0;
        for (Store.Watch w : hist) {
            if (n >= MAX_CONTINUE) break;
            if (Broadcast.fromBroadcast(w.t) || w.len <= 0 || w.pos <= 0 || w.pos > w.len - 60000) continue;
            String key = "cw:" + Store.resumeKey(w.u);
            if (key.length() > 200) key = key.substring(0, 200);
            if (!keep.add(key)) continue;
            n++;
            Intent open = new Intent(c, LiveActivity.class).putExtra("url", w.u).putExtra("open", "player")
                    .putExtra("title", w.t).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ContentValues v = new ContentValues();
            v.put(TvContract.WatchNextPrograms.COLUMN_TYPE, TvContract.WatchNextPrograms.TYPE_MOVIE);
            v.put(TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE, TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE);
            v.put(TvContract.WatchNextPrograms.COLUMN_TITLE, w.t);
            v.put(TvContract.WatchNextPrograms.COLUMN_SHORT_DESCRIPTION, "ZH Play  ·  " + T.t("resume at") + " " + clock(w.pos));
            v.put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI, logo.toString());
            v.put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_ASPECT_RATIO, TvContract.WatchNextPrograms.ASPECT_RATIO_16_9);
            v.put(TvContract.WatchNextPrograms.COLUMN_INTENT_URI, open.toUri(Intent.URI_INTENT_SCHEME));
            v.put(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, w.when);
            v.put(TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, (int) Math.min(Integer.MAX_VALUE, w.pos));
            v.put(TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS, (int) Math.min(Integer.MAX_VALUE, w.len));
            v.put(TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID, key);
            upsert(cr, all, mine.get(key), v);
        }

        // ---- the admin's broadcast: live now, or coming soon
        JSONObject b = Broadcast.current();
        String ph = Broadcast.phase(b);
        if (!ph.isEmpty()) {
            String key = "bc:" + b.optString("id");
            keep.add(key);
            boolean live = "live".equals(ph);
            Intent open = new Intent(c, LiveActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ContentValues v = new ContentValues();
            v.put(TvContract.WatchNextPrograms.COLUMN_TYPE, TvContract.WatchNextPrograms.TYPE_EVENT);
            v.put(TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE, live
                    ? TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_NEW : TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_WATCHLIST);
            v.put(TvContract.WatchNextPrograms.COLUMN_TITLE, (live ? "🔴 " + T.t("LIVE") + ": " : "📅 ") + b.optString("title"));
            v.put(TvContract.WatchNextPrograms.COLUMN_SHORT_DESCRIPTION, live ? T.t("Live now on ZH Play — select to watch")
                    : T.t("Live on ZH Play at") + " " + Broadcast.when(b.optLong("start")));
            v.put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI, logo.toString());
            v.put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_ASPECT_RATIO, TvContract.WatchNextPrograms.ASPECT_RATIO_16_9);
            v.put(TvContract.WatchNextPrograms.COLUMN_INTENT_URI, open.toUri(Intent.URI_INTENT_SCHEME));
            v.put(TvContract.WatchNextPrograms.COLUMN_LIVE, live ? 1 : 0);
            String pic = Thumb.web(b.optString("img"));
            if (!pic.isEmpty()) v.put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI, pic);
            v.put(TvContract.WatchNextPrograms.COLUMN_START_TIME_UTC_MILLIS, b.optLong("start"));
            v.put(TvContract.WatchNextPrograms.COLUMN_END_TIME_UTC_MILLIS, b.optLong("end"));
            // live first in the row: newest engagement
            v.put(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, live ? System.currentTimeMillis() : b.optLong("start"));
            v.put(TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID, key);
            upsert(cr, all, mine.get(key), v);
        }

        // ---- remove what's no longer true (finished movies, ended broadcasts)
        for (Map.Entry<String, Long> e : mine.entrySet()) {
            if (!keep.contains(e.getKey())) {
                try {
                    cr.delete(TvContract.buildWatchNextProgramUri(e.getValue()), null, null);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void upsert(ContentResolver cr, Uri all, Long rowId, ContentValues v) {
        try {
            if (rowId != null) {
                if (cr.update(TvContract.buildWatchNextProgramUri(rowId), v, null, null) > 0) return;
            }
            cr.insert(all, v);
        } catch (Exception ignored) {
        }
    }

    private static String clock(long ms) {
        long s = ms / 1000, h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, sec) : String.format(java.util.Locale.ROOT, "%d:%02d", m, sec);
    }
}
