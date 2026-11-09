package com.mytv.player;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * 🔔 Notifications — everything the admin sent, so nothing is missed (phone and TV):
 * the live / upcoming broadcast on top, then announcements newest first, unread marked.
 * Source: pub/inbox in Firebase (written by the admin panel whenever it sends a notification).
 */
public class InboxActivity extends Activity {
    private LinearLayout body;

    static void open(Context c) {
        Intent i = new Intent(c, InboxActivity.class);
        if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }

    /** Messages newest first: {id, title, body, link, open, at}. */
    static List<JSONObject> messages() {
        List<JSONObject> l = new ArrayList<>();
        JSONObject in = Broadcast.pub().optJSONObject("inbox");
        if (in != null) for (Iterator<String> it = in.keys(); it.hasNext(); ) {
            String k = it.next();
            JSONObject m = in.optJSONObject(k);
            if (m == null) continue;
            try {
                m.put("id", k);
            } catch (Exception ignored) {
            }
            l.add(m);
        }
        Collections.sort(l, (a, b) -> Long.compare(b.optLong("at"), a.optLong("at")));
        return l;
    }

    /** New since the inbox was last opened (+1 while a broadcast is live). */
    static int unread() {
        long seen = Store.getLong("inbox_seen", 0);
        int n = 0;
        for (JSONObject m : messages()) if (m.optLong("at") > seen) n++;
        return n;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static GradientDrawable round(int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(r);
        return g;
    }

    private TextView text(float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    @Override
    protected void onCreate(Bundle b) {
        Ui.apply(this);
        super.onCreate(b);
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Ui.BG);
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(14), dp(14), dp(14), dp(10));
        head.addView(UiKit.darkButton(this, "arrow-left", 42, false, v -> finish()), new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView t = text(21, Ui.TEXT, true);
        t.setText("🔔  " + T.t("Notifications"));
        t.setPadding(dp(12), 0, 0, 0);
        head.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(UiKit.darkButton(this, "refresh-cw", 42, false, v -> {
            Broadcast.sync(getApplicationContext(), true);
            ZhPopup.banner(this, null, T.t("Checking for new messages…"), ZhPopup.INFO, null);
            v.postDelayed(this::render, 2500);
        }), new LinearLayout.LayoutParams(dp(42), dp(42)));
        outer.addView(head);
        ScrollView sv = new ScrollView(this);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(4), dp(16), dp(40));
        sv.addView(body);
        outer.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        frame.addView(outer);
        setContentView(frame);
        render();
        Broadcast.sync(getApplicationContext(), true);
        body.postDelayed(this::render, 2500);
    }

    private long seenBefore;

    private void render() {
        if (isFinishing()) return;
        if (seenBefore == 0) seenBefore = Store.getLong("inbox_seen", 0);
        body.removeAllViews();

        // ---- the broadcast first
        JSONObject b = Broadcast.current();
        String ph = Broadcast.phase(b);
        if (!ph.isEmpty()) {
            boolean live = "live".equals(ph);
            LinearLayout c = new LinearLayout(this);
            c.setOrientation(LinearLayout.VERTICAL);
            c.setPadding(dp(18), dp(16), dp(18), dp(16));
            c.setBackground(round(live ? 0xFFB91C1C : 0xFF1E3A8A, dp(20)));
            c.setFocusable(true);
            c.setClickable(true);
            TextView pill = text(12, Color.WHITE, true);
            pill.setText(live ? "🔴  " + T.t("LIVE NOW") : "📅  " + T.t("COMING UP"));
            TextView tt = text(19, Color.WHITE, true);
            tt.setText(b.optString("title"));
            tt.setPadding(0, dp(6), 0, dp(4));
            TextView ts = text(14, 0xDDFFFFFF, false);
            ts.setText(live ? T.t("Select to watch") : T.t("Starts") + " " + Broadcast.when(b.optLong("start")));
            c.addView(pill);
            c.addView(tt);
            c.addView(ts);
            if (live) c.setOnClickListener(v -> Broadcast.watch(this));
            else c.setOnClickListener(v -> ZhPopup.message(this, "📅", b.optString("title"),
                    T.t("Starts") + " " + Broadcast.when(b.optLong("start")) + "\n" + T.t("You'll get a reminder when it begins.")));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, dp(4), 0, dp(8));
            body.addView(c, lp);
            if (live) c.requestFocus();
        }

        // ---- messages
        List<JSONObject> list = messages();
        if (list.isEmpty()) {
            TextView none = text(15, Ui.DIM, false);
            none.setText(ph.isEmpty() ? T.t("No notifications yet.") : "");
            none.setGravity(Gravity.CENTER);
            none.setPadding(0, dp(40), 0, 0);
            body.addView(none);
        }
        boolean first = ph.isEmpty();
        for (final JSONObject m : list) {
            boolean isNew = m.optLong("at") > seenBefore;
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(14), dp(14), dp(14), dp(14));
            r.setBackground(Ui.focusCard(this, 18));
            r.setFocusable(true);
            r.setClickable(true);
            TextView ic = text(18, Color.WHITE, false);
            ic.setText(m.optString("link").isEmpty() ? "📢" : "▶");
            ic.setGravity(Gravity.CENTER);
            ic.setBackground(round(isNew ? Ui.ACCENT : 0xFF4B5563, dp(20)));
            r.addView(ic, new LinearLayout.LayoutParams(dp(40), dp(40)));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(12), 0, dp(8), 0);
            TextView tt = text(15, Ui.TEXT, true);
            tt.setText(m.optString("title", "ZH Play"));
            TextView bb = text(14, Ui.DIM, false);
            bb.setText(m.optString("body"));
            bb.setMaxLines(3);
            TextView when = text(12, Ui.DIM, false);
            when.setText(ago(m.optLong("at")));
            when.setPadding(0, dp(4), 0, 0);
            col.addView(tt);
            col.addView(bb);
            col.addView(when);
            r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
            if (isNew) {
                View dot = new View(this);
                dot.setBackground(round(Ui.ACCENT, dp(5)));
                r.addView(dot, new LinearLayout.LayoutParams(dp(10), dp(10)));
            }
            r.setOnClickListener(v -> openMessage(m));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, dp(8), 0, 0);
            body.addView(r, lp);
            if (first) {
                r.requestFocus();
                first = false;
            }
        }
        // opened = read (the dots stay until the page is closed)
        long newest = list.isEmpty() ? 0 : list.get(0).optLong("at");
        if (newest > Store.getLong("inbox_seen", 0)) Store.putLong("inbox_seen", newest);
    }

    private void openMessage(JSONObject m) {
        String link = m.optString("link");
        if (link.isEmpty()) {
            ZhPopup.message(this, "📢", m.optString("title", "ZH Play"), m.optString("body"));
            return;
        }
        startActivity(new Intent(this, LiveActivity.class).putExtra("url", link)
                .putExtra("open", m.optString("open", "auto")).putExtra("title", m.optString("title")));
    }

    private static String ago(long at) {
        if (at <= 0) return "";
        long m = (System.currentTimeMillis() - at) / 60000;
        if (m < 1) return T.t("just now");
        if (m < 60) return m + " " + T.t("min ago");
        if (m < 1440) return (m / 60) + " h " + T.t("ago");
        return Broadcast.when(at);
    }
}
