package com.mytv.player;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.Locale;

/**
 * Admin broadcasts and announcements (Firebase, written only by the admin — see the database rules):
 *   pub/ticker     {text}                                  → scrolling bar on the home screens
 *   pub/broadcast  {id, title, kind: youtube|premiere|hls, url, start, end, state: scheduled|live|ended}
 *   members/{uid}  {name, email, device, tv, ver, last}    → the admin's user list (each user writes their own)
 *   perms/{uid}    {off: [tile…], blocked}                 → which menus each user gets (admin writes)
 *   live/{bid}/{uid} {name, email, joined, at}             → who is watching (each viewer writes their own)
 */
final class Broadcast {
    private Broadcast() {
    }

    static final String CH = "zh_live";
    private static long lastSync, lastMember;
    static volatile String watching;              // broadcast id this app joined

    // ------------------------------------------------------------------ cached data

    static JSONObject pub() {
        try {
            return new JSONObject(Store.getStr("pub_json", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static JSONObject perms() {
        try {
            return new JSONObject(Store.getStr("my_perms", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static boolean permOff(String key) {
        JSONArray a = perms().optJSONArray("off");
        if (a != null) for (int i = 0; i < a.length(); i++) if (key.equals(a.optString(i))) return true;
        return false;
    }

    static boolean blockedHere() {
        return perms().optBoolean("blocked", false);
    }

    static JSONObject current() {
        return pub().optJSONObject("broadcast");
    }

    /** "live" now (started by the admin, or inside its time frame and not ended), "soon", or "". */
    static String phase(JSONObject b) {
        if (b == null || b.optString("url").isEmpty()) return "";
        long now = System.currentTimeMillis(), start = b.optLong("start", 0), end = b.optLong("end", 0);
        String st = b.optString("state", "scheduled");
        if ("ended".equals(st) || (end > 0 && now > end && !"live".equals(st))) return "";
        if ("live".equals(st) || (start > 0 && now >= start)) return "live";
        if (start > now && start - now < 7 * 24 * 3600 * 1000L) return "soon";
        return "";
    }

    // ------------------------------------------------------------------ sync (on app start / every minute)

    static void sync(final Context c, boolean force) {
        if (!Cloud.signedIn()) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastSync < 55000) return;
        lastSync = now;
        new Thread(() -> {
            try {
                String p = Cloud.http("GET", url("/pub"), null);
                Store.putStr("pub_json", p == null || p.trim().equals("null") ? "{}" : p);
                String m = Cloud.http("GET", url("/perms/" + Cloud.uid()), null);
                Store.putStr("my_perms", m == null || m.trim().equals("null") ? "{}" : m);
                if (System.currentTimeMillis() - lastMember > 30 * 60 * 1000L) {     // "I'm using ZH Play"
                    JSONObject me = new JSONObject().put("name", Auth.name()).put("email", Auth.email())
                            .put("device", Build.MANUFACTURER + " " + Build.MODEL).put("tv", Ui.isTv(c))
                            .put("ver", Updater.installedName(c)).put("last", new JSONObject().put(".sv", "timestamp"));
                    Cloud.http("PUT", url("/members/" + Cloud.uid()), me.toString());
                    lastMember = System.currentTimeMillis();
                }
                if (watching != null && App.visible > 0) beat(watching);
                remind(c);
                Remote.MAIN.post(() -> refreshBars());
            } catch (Exception ignored) {
            }
        }).start();
    }

    /** Firebase REST address for a path outside users/{uid}. */
    static String url(String path) throws Exception {
        return Cloud.dbUrl() + path + ".json?auth=" + java.net.URLEncoder.encode(Cloud.idToken(), "UTF-8");
    }

    // ------------------------------------------------------------------ the bar on the home screens

    private static final java.util.WeakHashMap<Activity, TextView> bars = new java.util.WeakHashMap<>();

    /** Adds / updates the scrolling bar (announcement or 🔴 LIVE) at the bottom of a home screen. */
    static void attach(final Activity a) {
        FrameLayout root = a.findViewById(android.R.id.content);
        if (root == null) return;
        TextView t = bars.get(a);
        if (t == null) {
            t = new TextView(a);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.MARQUEE);
            t.setMarqueeRepeatLimit(-1);
            t.setHorizontallyScrolling(true);
            t.setSelected(true);
            t.setTextSize(15);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setTextColor(Color.WHITE);
            int pad = Math.round(12 * a.getResources().getDisplayMetrics().density);
            t.setPadding(pad, pad * 2 / 3, pad, pad * 2 / 3);
            t.setFocusable(true);
            t.setOnClickListener(v -> {
                if ("live".equals(phase(current()))) watch(a);
            });
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
            root.addView(t, lp);
            bars.put(a, t);
        }
        update(t);
    }

    static void refreshBars() {
        for (TextView t : bars.values()) if (t != null) update(t);
    }

    private static void update(TextView t) {
        JSONObject b = current();
        String ph = phase(b), ticker = pub().optJSONObject("ticker") == null ? "" : pub().optJSONObject("ticker").optString("text", "").trim();
        String txt;
        int bg;
        if ("live".equals(ph)) {
            txt = "🔴 " + T.t("LIVE NOW") + ":  " + b.optString("title") + "   —   " + T.t("tap to watch") + (ticker.isEmpty() ? "" : "      •      " + ticker);
            bg = 0xEED81B1B;
        } else if ("soon".equals(ph)) {
            txt = "📅 " + b.optString("title") + "  —  " + T.t("live at") + " " + when(b.optLong("start")) + (ticker.isEmpty() ? "" : "      •      " + ticker);
            bg = 0xEE1E3A8A;
        } else if (!ticker.isEmpty()) {
            txt = "📢  " + ticker;
            bg = 0xEE111827;
        } else {
            t.setVisibility(View.GONE);
            return;
        }
        if (!txt.contentEquals(t.getText())) t.setText(txt + "            ");
        t.setBackgroundColor(bg);
        t.setVisibility(View.VISIBLE);
        t.setSelected(true);
    }

    static String when(long ms) {
        return java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(new java.util.Date(ms));
    }

    // ------------------------------------------------------------------ watching

    /** Opens the broadcast: YouTube Live in YouTube, a premiere at the current point, a live .m3u8 in ZH Play. */
    static void watch(Activity a) {
        JSONObject b = current();
        if (b == null || !"live".equals(phase(b))) {
            UiKit.makeToast(a, T.t("Nothing is live right now"), android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        String id = b.optString("id"), url = b.optString("url"), title = b.optString("title", "Live");
        watching = id;
        join(a.getApplicationContext(), id);
        String kind = b.optString("kind", "hls");
        if ("youtube".equals(kind)) {
            try {
                a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                a.startActivity(new Intent(a, BrowserActivity.class).putExtra(BrowserActivity.EXTRA_URL, url));
            }
            return;
        }
        Store.Item it = new Store.Item("🔴 " + title, url, "");
        if ("premiere".equals(kind)) {                    // everyone at the same point: join where it is now
            long start = "live".equals(b.optString("state")) && b.optLong("startedAt", 0) > 0 ? b.optLong("startedAt") : b.optLong("start");
            Store.setPos(url, Math.max(0, System.currentTimeMillis() - start));
        } else {
            Store.setPos(url, 0);
        }
        Remote.play(a, Collections.singletonList(it), 0);
    }

    private static void join(final Context c, final String bid) {
        new Thread(() -> {
            try {
                JSONObject me = new JSONObject().put("name", Auth.name()).put("email", Auth.email())
                        .put("tv", Ui.isTv(c)).put("joined", new JSONObject().put(".sv", "timestamp"))
                        .put("at", new JSONObject().put(".sv", "timestamp"));
                Cloud.http("PUT", url("/live/" + bid + "/" + Cloud.uid()), me.toString());
            } catch (Exception ignored) {
            }
        }).start();
    }

    /** "Still watching" (every minute while ZH Play shows it). */
    private static void beat(String bid) {
        try {
            Cloud.http("PUT", url("/live/" + bid + "/" + Cloud.uid() + "/at"), new JSONObject().put(".sv", "timestamp").toString());
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ reminder at start time (also without push)

    static void channel(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(new NotificationChannel(CH, "ZH Play live", NotificationManager.IMPORTANCE_HIGH));
        }
    }

    private static void remind(Context c) {
        JSONObject b = current();
        if (b == null) return;
        long start = b.optLong("start", 0);
        String key = b.optString("id") + ":" + start;
        if (start <= System.currentTimeMillis() || key.equals(Store.getStr("live_alarm", ""))) return;
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            PendingIntent pi = PendingIntent.getBroadcast(c, 4401, new Intent(c, Reminder.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
            if (am != null) {
                if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, start, pi);
                else am.set(AlarmManager.RTC_WAKEUP, start, pi);
                Store.putStr("live_alarm", key);
            }
        } catch (Exception ignored) {
        }
    }

    /** A notification that opens the broadcast. */
    static void notify(Context c, String title, String body) {
        notify(c, title, body, "");
    }

    static void notify(Context c, String title, String body, String link) {
        try {
            channel(c);
            Intent open = new Intent(c, LiveActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("url", link == null ? "" : link);
            PendingIntent pi = PendingIntent.getActivity(c, 4402, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
            android.app.Notification.Builder nb = Build.VERSION.SDK_INT >= 26 ? new android.app.Notification.Builder(c, CH) : new android.app.Notification.Builder(c);
            nb.setSmallIcon(R.drawable.lc_tv).setContentTitle(title).setContentText(body).setAutoCancel(true).setContentIntent(pi)
                    .setStyle(new android.app.Notification.BigTextStyle().bigText(body));      // full message, not one cut line
            if (Build.VERSION.SDK_INT >= 21) nb.setColor(0xFF8B5CF6).setCategory(android.app.Notification.CATEGORY_RECOMMENDATION);
            try {
                nb.setLargeIcon(android.graphics.BitmapFactory.decodeResource(c.getResources(), R.drawable.zh_logo));
            } catch (Throwable ignored) {
            }
            if (Build.VERSION.SDK_INT < 26) nb.setPriority(android.app.Notification.PRIORITY_HIGH);
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(4403, nb.build());
        } catch (Exception ignored) {
        }
    }

    /** Alarm at the broadcast's start time. */
    public static class Reminder extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            JSONObject b = current();
            if (b == null || "ended".equals(b.optString("state"))) return;
            Broadcast.notify(c, "🔴 " + T.t("LIVE NOW") + ": " + b.optString("title"), T.t("Tap to watch on ZH Play"));
        }
    }

    // ------------------------------------------------------------------ admin helpers

    static String kindLabel(String k) {
        return "youtube".equals(k) ? "YouTube Live" : "premiere".equals(k) ? T.t("Premiere (video file, in sync)") : T.t("Live stream (.m3u8)");
    }

    static String newId() {
        return Long.toString(System.currentTimeMillis(), 36).toUpperCase(Locale.ROOT);
    }
}
