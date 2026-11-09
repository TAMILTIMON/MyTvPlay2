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

    /** 16:9 picture with a soft dark fade at the bottom for the text. */
    private FrameLayout picture(String img, String fallbackEmoji, int fallbackColor, int heightDp) {
        FrameLayout f = new FrameLayout(this);
        f.setBackground(round(fallbackColor, dp(16)));
        f.setClipToOutline(true);
        TextView em = text(34, 0x88FFFFFF, false);
        em.setText(fallbackEmoji);
        em.setGravity(Gravity.CENTER);
        f.addView(em, new FrameLayout.LayoutParams(-1, -1));
        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        f.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        if (img != null && !img.isEmpty() && !"none".equals(img)) Thumb.load(iv, img);
        View fade = new View(this);
        fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0x00000000, 0xDD000000}));
        f.addView(fade, new FrameLayout.LayoutParams(-1, -1));
        f.setMinimumHeight(dp(heightDp));
        return f;
    }

    /** TV remote: the focused card grows a little (like Netflix). */
    private void focusGrow(View v) {
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnFocusChangeListener((x, has) -> {
            x.animate().scaleX(has ? 1.07f : 1f).scaleY(has ? 1.07f : 1f).setDuration(140).start();
            x.setElevation(has ? dp(10) : 0);
        });
    }

    private void render() {
        if (isFinishing()) return;
        if (seenBefore == 0) seenBefore = Store.getLong("inbox_seen", 0);
        body.removeAllViews();
        boolean tv = Ui.isTv(this);
        View firstFocus = null;

        // ---- hero: the live / upcoming broadcast with its picture
        final JSONObject b = Broadcast.current();
        String ph = Broadcast.phase(b);
        if (!ph.isEmpty()) {
            final boolean live = "live".equals(ph);
            FrameLayout hero = picture(b.optString("img").isEmpty() ? Thumb.auto(b.optString("url")) : b.optString("img"),
                    live ? "🔴" : "📅", live ? 0xFF7F1D1D : 0xFF1E3A8A, tv ? 260 : 210);
            LinearLayout txt = new LinearLayout(this);
            txt.setOrientation(LinearLayout.VERTICAL);
            txt.setPadding(dp(18), dp(14), dp(18), dp(16));
            TextView pill = text(12, Color.WHITE, true);
            pill.setText(live ? "  🔴 " + T.t("LIVE NOW") + "  " : "  📅 " + T.t("COMING UP") + "  ");
            pill.setBackground(round(live ? 0xFFDC2626 : 0xFF2563EB, dp(10)));
            TextView tt = text(tv ? 24 : 20, Color.WHITE, true);
            tt.setText(b.optString("title"));
            tt.setPadding(0, dp(8), 0, dp(2));
            TextView ts = text(14, 0xDDFFFFFF, false);
            ts.setText(live ? "▶  " + T.t("Select to watch") : T.t("Starts") + " " + Broadcast.when(b.optLong("start")));
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-2, -2);
            txt.addView(pill, plp);
            txt.addView(tt);
            txt.addView(ts);
            hero.addView(txt, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
            focusGrow(hero);
            hero.setOnClickListener(v -> {
                if (live) Broadcast.watch(this);
                else ZhPopup.message(this, "📅", b.optString("title"),
                        T.t("Starts") + " " + Broadcast.when(b.optLong("start")) + "\n" + T.t("You'll get a reminder when it begins."));
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(tv ? 260 : 210));
            lp.setMargins(dp(6), dp(6), dp(6), dp(10));
            body.addView(hero, lp);
            firstFocus = hero;
        }

        // ---- rows of poster cards: New · Earlier
        List<JSONObject> all = messages();
        List<JSONObject> fresh = new ArrayList<>(), older = new ArrayList<>();
        for (JSONObject m : all) (m.optLong("at") > seenBefore ? fresh : older).add(m);
        if (all.isEmpty() && ph.isEmpty()) {
            TextView none = text(15, Ui.DIM, false);
            none.setText(T.t("No notifications yet."));
            none.setGravity(Gravity.CENTER);
            none.setPadding(0, dp(60), 0, 0);
            body.addView(none);
        }
        View f1 = row(T.t("New"), fresh, true, tv);
        View f2 = row(fresh.isEmpty() ? T.t("Messages") : T.t("Earlier"), older, false, tv);
        if (firstFocus == null) firstFocus = f1 != null ? f1 : f2;
        if (firstFocus != null) firstFocus.requestFocus();

        long newest = all.isEmpty() ? 0 : all.get(0).optLong("at");
        if (newest > Store.getLong("inbox_seen", 0)) Store.putLong("inbox_seen", newest);
    }

    /** A titled, sideways-scrolling row of cards. Returns its first card (for focus). */
    private View row(String title, List<JSONObject> items, boolean isNew, boolean tv) {
        if (items.isEmpty()) return null;
        TextView h = text(17, Ui.TEXT, true);
        h.setText(title + (isNew ? "  ·  " + items.size() : ""));
        h.setPadding(dp(6), dp(16), 0, dp(8));
        body.addView(h);
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        hs.setPadding(dp(4), dp(6), dp(4), dp(10));
        LinearLayout r = new LinearLayout(this);
        hs.addView(r);
        int w = dp(tv ? 300 : 230), ph = Math.round(w * 9f / 16f);
        View first = null;
        for (final JSONObject m : items) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            String img = m.optString("img").isEmpty() ? Thumb.auto(m.optString("link")) : m.optString("img");
            FrameLayout pic = picture(img, m.optString("link").isEmpty() ? "📢" : "▶", isNew ? Ui.ACCENT : 0xFF374151, 0);
            TextView ct = text(14, Color.WHITE, true);
            ct.setText(m.optString("title", "ZH Play"));
            ct.setMaxLines(2);
            ct.setPadding(dp(10), 0, dp(10), dp(8));
            pic.addView(ct, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
            if (isNew) {
                View dot = new View(this);
                dot.setBackground(round(0xFFF97316, dp(6)));
                FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(dp(12), dp(12), Gravity.TOP | Gravity.END);
                dl.setMargins(0, dp(8), dp(8), 0);
                pic.addView(dot, dl);
            }
            card.addView(pic, new LinearLayout.LayoutParams(w, ph));
            TextView when = text(12, Ui.DIM, false);
            when.setText(ago(m.optLong("at")));
            when.setPadding(dp(4), dp(6), 0, 0);
            card.addView(when);
            focusGrow(card);
            card.setOnClickListener(v -> details(m, img));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(dp(6), 0, dp(10), 0);
            r.addView(card, lp);
            if (first == null) first = card;
        }
        body.addView(hs);
        return first;
    }

    /** Netflix-like detail sheet: picture, title, message, ▶ Play / Open. */
    private void details(final JSONObject m, String img) {
        final FrameLayout root = (FrameLayout) getWindow().getDecorView();
        final FrameLayout dim = new FrameLayout(this);
        dim.setBackgroundColor(0xB3000000);
        dim.setClickable(true);
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(Ui.dark() ? 0xFF161D2E : 0xFFFFFFFF, dp(24)));
        c.setClipToOutline(true);
        int w = Math.min(dp(560), getResources().getDisplayMetrics().widthPixels - dp(32));
        c.addView(picture(img, m.optString("link").isEmpty() ? "📢" : "▶", Ui.ACCENT, 0), new LinearLayout.LayoutParams(w, Math.round(w * 9f / 16f)));
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.setPadding(dp(20), dp(16), dp(20), dp(18));
        TextView tt = text(20, Ui.TEXT, true);
        tt.setText(m.optString("title", "ZH Play"));
        TextView when = text(12, Ui.DIM, false);
        when.setText(ago(m.optLong("at")));
        when.setPadding(0, dp(2), 0, dp(10));
        TextView bb = text(15, Ui.DIM, false);
        bb.setText(m.optString("body"));
        bb.setLineSpacing(0, 1.15f);
        tx.addView(tt);
        tx.addView(when);
        tx.addView(bb);
        final Runnable close = () -> dim.animate().alpha(0f).setDuration(150).withEndAction(() -> root.removeView(dim)).start();
        LinearLayout btns = new LinearLayout(this);
        btns.setPadding(0, dp(18), 0, 0);
        TextView cl = ZhPopup.button(this, T.t("Close"), false, false);
        cl.setOnClickListener(v -> close.run());
        btns.addView(cl, new LinearLayout.LayoutParams(0, dp(48), 1));
        TextView go = null;
        if (!m.optString("link").isEmpty()) {
            go = ZhPopup.button(this, "▶  " + T.t("Play"), true, false);
            go.setOnClickListener(v -> {
                close.run();
                startActivity(new Intent(this, LiveActivity.class).putExtra("url", m.optString("link"))
                        .putExtra("open", m.optString("open", "auto")).putExtra("title", m.optString("title")));
            });
            LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(0, dp(48), 1);
            gl.setMargins(dp(10), 0, 0, 0);
            btns.addView(go, gl);
        }
        tx.addView(btns);
        c.addView(tx);
        dim.addView(c, new FrameLayout.LayoutParams(w, -2, Gravity.CENTER));
        dim.setAlpha(0f);
        root.addView(dim, new FrameLayout.LayoutParams(-1, -1));
        dim.animate().alpha(1f).setDuration(160).start();
        (go != null ? go : cl).requestFocus();
        dim.setOnClickListener(v -> close.run());
    }

    private void openMessage(JSONObject m) {
        details(m, m.optString("img").isEmpty() ? Thumb.auto(m.optString("link")) : m.optString("img"));
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
