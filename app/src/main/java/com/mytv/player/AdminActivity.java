package com.mytv.player;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Admin panel: a dashboard (stats + feature cards), each feature on its own page —
 * Users (search, filters, per-user menus by group, block), Broadcast, Scroller, Notify, Live monitor, Push setup.
 * Only for admins in access.json; the Firebase rules also accept changes only from the admin's e-mail.
 */
public class AdminActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private FrameLayout frame;
    private final ArrayList<Runnable> stack = new ArrayList<>();     // page history (back)
    private JSONObject members = new JSONObject();
    private Runnable pageTick;

    /** Menus an admin can switch off per user, in groups. {key, label, icon} */
    static final String[][][] GROUPS = {
            {{"Media", "🎬"}, {"videos", "Videos"}, {"music", "Music"}, {"playlists", "Playlists"}, {"library", "Library"}, {"local_files", "Local files"}},
            {{"Tools", "🧰"}, {"browser", "Browser"}, {"dj", "DJ mixer"}, {"editor", "Song editor"}, {"board", "Whiteboard"},
                    {"present", "Present"}, {"eq", "Equalizer"}, {"ai_assistant", "AI assistant"}},
            {{"Cloud & network", "☁"}, {"seedr", "Seedr"}, {"torrents", "Torrents / magnets"}, {"vpn", "VPN"},
                    {"network", "Network folders"}, {"storage", "SD Card & USB"}, {"share", "Share phone videos"}},
            {{"TV & remote", "📺"}, {"cast_screen", "Cast screen"}, {"live_tv_screen", "Live TV screen"}, {"apps", "Apps"}, {"updates", "Updates"}}};

    static void open(Context c) {
        if (!Auth.isAdmin()) {
            UiKit.makeToast(c, T.t("Admins only"), android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        c.startActivity(new Intent(c, AdminActivity.class));
    }

    // ================================================================== small UI kit

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

    private int cardColor() {
        return Ui.dark() ? 0xFF161D2E : 0xFFFFFFFF;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable g = round(cardColor(), dp(20));
        g.setStroke(dp(1), Ui.dark() ? 0x22FFFFFF : 0x14000000);
        c.setBackground(g);
        return c;
    }

    private LinearLayout.LayoutParams gap(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(top), 0, 0);
        return lp;
    }

    private TextView label(String s) {
        TextView t = text(12, Ui.DIM, true);
        t.setText(s.toUpperCase(Locale.getDefault()));
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(2), 0, 0, dp(6));
        return t;
    }

    /** A labelled, rounded input. */
    private EditText input(LinearLayout into, String lab, String hint, String value, boolean multi) {
        into.addView(label(lab), gap(14));
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.DIM);
        e.setTextSize(15);
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable g = round(Ui.dark() ? 0xFF0F1524 : 0xFFF3F4F6, dp(14));
        g.setStroke(dp(1), Ui.dark() ? 0x33FFFFFF : 0x1A000000);
        e.setBackground(g);
        if (multi) {
            e.setMinLines(3);
            e.setGravity(Gravity.TOP);
        } else e.setSingleLine(true);
        into.addView(e, new LinearLayout.LayoutParams(-1, -2));
        return e;
    }

    private TextView button(String s, boolean primary, boolean danger, View.OnClickListener l) {
        TextView b = ZhPopup.button(this, s, primary, danger);
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setOnClickListener(l);
        return b;
    }

    private void buttons(LinearLayout into, TextView... bs) {
        LinearLayout r = new LinearLayout(this);
        for (int i = 0; i < bs.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(50), 1);
            lp.setMargins(i == 0 ? 0 : dp(8), 0, 0, 0);
            r.addView(bs[i], lp);
        }
        into.addView(r, gap(16));
    }

    private TextView chip(String s, boolean selected, View.OnClickListener l) {
        TextView c = text(14, selected ? Color.WHITE : Ui.TEXT, true);
        c.setText(s);
        c.setPadding(dp(16), dp(9), dp(16), dp(9));
        c.setBackground(selected ? Ui.gradient(this, 20) : Ui.focusCard(this, 20));
        c.setFocusable(true);
        c.setOnClickListener(l);
        return c;
    }

    private TextView avatar(String name, int sizeDp) {
        TextView a = text(sizeDp / 2.6f, Color.WHITE, true);
        String n = name == null || name.trim().isEmpty() ? "?" : name.trim();
        a.setText(n.substring(0, 1).toUpperCase(Locale.getDefault()));
        a.setGravity(Gravity.CENTER);
        int[] colors = {0xFF7C3AED, 0xFF2563EB, 0xFF059669, 0xFFDB2777, 0xFFEA580C, 0xFF0891B2};
        a.setBackground(round(colors[Math.abs(n.hashCode()) % colors.length], dp(sizeDp / 2f)));
        return a;
    }

    private void say(String s) {
        ZhPopup.banner(this, s);
    }

    // ================================================================== pages

    /** A page: header (back + title + subtitle) and a scrolling body. */
    private LinearLayout page(String title, String sub, boolean home) {
        if (pageTick != null) {
            main.removeCallbacks(pageTick);
            pageTick = null;
        }
        frame.removeAllViews();
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(14), dp(14), dp(14), dp(10));
        head.addView(UiKit.darkButton(this, "arrow-left", 42, false, v -> onBackPressed()), new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(dp(12), 0, 0, 0);
        TextView t = text(21, Ui.TEXT, true);
        t.setText(title);
        names.addView(t);
        if (sub != null) {
            TextView s = text(13, Ui.DIM, false);
            s.setText(sub);
            names.addView(s);
        }
        head.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        outer.addView(head);
        ScrollView sv = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(4), dp(16), dp(40));
        sv.addView(body);
        outer.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        frame.addView(outer);
        return body;
    }

    private void go(Runnable p) {
        stack.add(p);
        p.run();
    }

    @Override
    public void onBackPressed() {
        if (stack.size() > 1) {
            stack.remove(stack.size() - 1);
            stack.get(stack.size() - 1).run();
        } else super.onBackPressed();
    }

    @Override
    protected void onCreate(Bundle b) {
        Ui.apply(this);
        super.onCreate(b);
        frame = new FrameLayout(this);
        frame.setBackgroundColor(Ui.BG);
        setContentView(frame);
        go(this::home);
        loadMembers(null);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (pageTick != null) main.removeCallbacks(pageTick);
    }

    // ------------------------------------------------------------------ dashboard

    private TextView sUsers, sToday, sWeek, sTv;

    private void home() {
        LinearLayout body = page("🛡  " + T.t("Admin"), null, true);
        // profile card
        LinearLayout me = card();
        me.setOrientation(LinearLayout.HORIZONTAL);
        me.setGravity(Gravity.CENTER_VERTICAL);
        me.addView(avatar(Auth.name(), 52), new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), 0, 0, 0);
        TextView n = text(17, Ui.TEXT, true);
        n.setText(Auth.name());
        TextView e = text(13, Ui.DIM, false);
        e.setText(Auth.email());
        TextView badge = text(12, Color.WHITE, true);
        badge.setText(Cloud.signedIn() ? "  ● " + T.t("Cloud connected") + "  " : "  ● " + T.t("Cloud not connected") + "  ");
        badge.setBackground(round(Cloud.signedIn() ? 0xFF16A34A : 0xFFDC2626, dp(10)));
        col.addView(n);
        col.addView(e);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.topMargin = dp(6);
        col.addView(badge, blp);
        me.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(me, gap(4));

        // stats
        LinearLayout stats = new LinearLayout(this);
        sUsers = stat(stats, "👥", T.t("Users"), true);
        sToday = stat(stats, "🟢", T.t("Today"), false);
        sWeek = stat(stats, "📅", T.t("Week"), false);
        sTv = stat(stats, "📺", "TV", false);
        body.addView(stats, gap(12));
        fillStats();

        // features
        body.addView(label(T.t("Manage")), gap(20));
        JSONObject bc = Broadcast.current();
        String ph = Broadcast.phase(bc);
        String[][] tiles = {
                {"👥", T.t("Users"), T.t("menus, block, who uses the app")},
                {"📡", T.t("Broadcast"), "live".equals(ph) ? "🔴 " + T.t("LIVE now") : "soon".equals(ph) ? "📅 " + T.t("scheduled") : T.t("YouTube, premiere, live stream")},
                {"📢", T.t("News scroller"), T.t("a line on every home screen")},
                {"📣", T.t("Send notification"), T.t("to every phone and TV")},
                {"📊", T.t("Live monitor"), T.t("who is watching")},
                {"🔑", T.t("Push setup"), Fcm.ready() ? "✅ " + T.t("ready") : "⚠ " + T.t("import the key")}};
        Runnable[] actions = {() -> go(this::users), () -> go(this::broadcast), () -> go(this::scroller),
                () -> go(this::notifyPage), () -> go(this::monitor), () -> go(this::pushSetup)};
        LinearLayout row = null;
        for (int i = 0; i < tiles.length; i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                body.addView(row, gap(i == 0 ? 0 : 10));
            }
            final int k = i;
            LinearLayout t = card();
            t.setFocusable(true);
            t.setClickable(true);
            t.setBackground(Ui.focusCard(this, 20));
            t.setOnClickListener(v -> actions[k].run());
            TextView ic = text(26, Ui.TEXT, false);
            ic.setText(tiles[i][0]);
            TextView tt = text(16, Ui.TEXT, true);
            tt.setText(tiles[i][1]);
            tt.setPadding(0, dp(8), 0, dp(2));
            TextView ts = text(12, Ui.DIM, false);
            ts.setText(tiles[i][2]);
            ts.setMaxLines(2);
            t.addView(ic);
            t.addView(tt);
            t.addView(ts);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
            lp.setMargins(i % 2 == 0 ? 0 : dp(10), 0, 0, 0);
            row.addView(t, lp);
        }
        body.addView(button("🔄  " + T.t("Refresh"), false, false, v -> loadMembers(this::fillStats)), new LinearLayout.LayoutParams(-1, dp(48)));
        ((LinearLayout.LayoutParams) body.getChildAt(body.getChildCount() - 1).getLayoutParams()).topMargin = dp(16);
    }

    private TextView stat(LinearLayout into, String icon, String lab, boolean first) {
        LinearLayout c = card();
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(dp(8), dp(14), dp(8), dp(14));
        TextView num = text(22, Ui.TEXT, true);
        num.setText("–");
        num.setGravity(Gravity.CENTER);
        TextView l = text(12, Ui.DIM, false);
        l.setText(icon + " " + lab);
        l.setGravity(Gravity.CENTER);
        c.addView(num);
        c.addView(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.setMargins(first ? 0 : dp(8), 0, 0, 0);
        into.addView(c, lp);
        return num;
    }

    private void fillStats() {
        if (sUsers == null) return;
        long now = System.currentTimeMillis(), day = 24 * 3600 * 1000L;
        int total = 0, today = 0, week = 0, tvs = 0;
        for (Iterator<String> it = members.keys(); it.hasNext(); ) {
            JSONObject u = members.optJSONObject(it.next());
            if (u == null) continue;
            total++;
            long last = u.optLong("last", 0);
            if (now - last < day) today++;
            if (now - last < 7 * day) week++;
            if (u.optBoolean("tv")) tvs++;
        }
        sUsers.setText(String.valueOf(total));
        sToday.setText(String.valueOf(today));
        sWeek.setText(String.valueOf(week));
        sTv.setText(String.valueOf(tvs));
    }

    // ------------------------------------------------------------------ users

    private String userFilter = "all", userQuery = "";

    private void users() {
        final LinearLayout body = page("👥  " + T.t("Users"), members.length() + " " + T.t("people use ZH Play"), false);
        EditText q = input(body, T.t("Search"), T.t("name, e-mail or device"), userQuery, false);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        final LinearLayout chips = new LinearLayout(this);
        hs.addView(chips);
        body.addView(hs, gap(12));
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        body.addView(list, gap(8));
        final Runnable[] draw = new Runnable[1];
        draw[0] = () -> {
            chips.removeAllViews();
            String[][] fs = {{"all", T.t("All")}, {"active", T.t("Active today")}, {"phone", T.t("Phones")}, {"tv", "TVs"}};
            for (String[] f : fs) {
                TextView c = chip(f[1], f[0].equals(userFilter), v -> {
                    userFilter = f[0];
                    draw[0].run();
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.setMargins(0, 0, dp(8), 0);
                chips.addView(c, lp);
            }
            renderUserList(list);
        };
        q.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void afterTextChanged(Editable e) {
                userQuery = e.toString().trim().toLowerCase(Locale.ROOT);
                renderUserList(list);
            }
        });
        draw[0].run();
        loadMembers(() -> renderUserList(list));
    }

    private void renderUserList(LinearLayout list) {
        list.removeAllViews();
        long now = System.currentTimeMillis();
        List<String> ids = new ArrayList<>();
        for (Iterator<String> it = members.keys(); it.hasNext(); ) ids.add(it.next());
        Collections.sort(ids, (a, b) -> Long.compare(members.optJSONObject(b).optLong("last"), members.optJSONObject(a).optLong("last")));
        int shown = 0;
        for (final String id : ids) {
            final JSONObject u = members.optJSONObject(id);
            if (u == null) continue;
            boolean tv = u.optBoolean("tv");
            long last = u.optLong("last", 0), mins = (now - last) / 60000;
            if ("active".equals(userFilter) && now - last > 24 * 3600 * 1000L) continue;
            if ("phone".equals(userFilter) && tv) continue;
            if ("tv".equals(userFilter) && !tv) continue;
            String hay = (u.optString("name") + " " + u.optString("email") + " " + u.optString("device")).toLowerCase(Locale.ROOT);
            if (!userQuery.isEmpty() && !hay.contains(userQuery)) continue;
            shown++;
            LinearLayout r = card();
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setBackground(Ui.focusCard(this, 18));
            r.setFocusable(true);
            r.setOnClickListener(v -> go(() -> userDetail(id, u)));
            FrameLayout av = new FrameLayout(this);
            av.addView(avatar(u.optString("name"), 44), new FrameLayout.LayoutParams(dp(44), dp(44)));
            View dot = new View(this);
            GradientDrawable dg = round(mins < 15 ? 0xFF22C55E : mins < 1440 ? 0xFFF59E0B : 0xFF6B7280, dp(6));
            dg.setStroke(dp(2), cardColor());
            dot.setBackground(dg);
            av.addView(dot, new FrameLayout.LayoutParams(dp(13), dp(13), Gravity.BOTTOM | Gravity.END));
            r.addView(av, new LinearLayout.LayoutParams(dp(46), dp(46)));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(12), 0, dp(6), 0);
            TextView n = text(15, Ui.TEXT, true);
            n.setText(u.optString("name", "?"));
            TextView e = text(13, Ui.DIM, false);
            e.setText(u.optString("email"));
            TextView d = text(12, Ui.DIM, false);
            d.setText((tv ? "📺 " : "📱 ") + u.optString("device") + "  ·  " + u.optString("ver"));
            col.addView(n);
            col.addView(e);
            col.addView(d);
            r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
            TextView seen = text(12, mins < 15 ? 0xFF22C55E : Ui.DIM, true);
            seen.setText(mins < 15 ? T.t("online") : mins < 60 ? mins + "m" : mins < 2880 ? (mins / 60) + "h" : (mins / 1440) + "d");
            r.addView(seen);
            list.addView(r, gap(8));
        }
        if (shown == 0) {
            TextView none = text(14, Ui.DIM, false);
            none.setText(members.length() == 0 ? T.t("No users yet — people appear after they open ZH Play signed in.") : T.t("Nobody matches."));
            none.setGravity(Gravity.CENTER);
            none.setPadding(0, dp(30), 0, 0);
            list.addView(none);
        }
    }

    /** One user: their menus by group, and block. */
    private void userDetail(final String id, final JSONObject u) {
        final LinearLayout body = page(u.optString("name", "?"), u.optString("email"), false);
        LinearLayout info = card();
        info.setOrientation(LinearLayout.HORIZONTAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.addView(avatar(u.optString("name"), 56), new LinearLayout.LayoutParams(dp(56), dp(56)));
        TextView det = text(13, Ui.DIM, false);
        long mins = (System.currentTimeMillis() - u.optLong("last", 0)) / 60000;
        det.setText((u.optBoolean("tv") ? "📺 TV" : "📱 " + T.t("Phone")) + "\n" + u.optString("device") + "\n"
                + T.t("Version") + " " + u.optString("ver") + "\n" + T.t("Last seen") + ": "
                + (mins < 60 ? mins + " min" : mins < 2880 ? (mins / 60) + " h" : (mins / 1440) + " d") + " " + T.t("ago"));
        det.setPadding(dp(14), 0, 0, 0);
        det.setLineSpacing(0, 1.2f);
        info.addView(det);
        body.addView(info, gap(4));
        final TextView loading = text(14, Ui.DIM, false);
        loading.setText(T.t("Loading…"));
        body.addView(loading, gap(16));
        new Thread(() -> {
            JSONObject p;
            try {
                String s = Cloud.http("GET", Broadcast.url("/perms/" + id), null);
                p = s == null || s.trim().equals("null") ? new JSONObject() : new JSONObject(s);
            } catch (Exception e) {
                p = new JSONObject();
            }
            final JSONObject perms = p;
            main.post(() -> {
                body.removeView(loading);
                JSONArray off = perms.optJSONArray("off");
                final java.util.HashSet<String> offSet = new java.util.HashSet<>();
                if (off != null) for (int i = 0; i < off.length(); i++) offSet.add(off.optString(i));
                final java.util.HashMap<String, ZhPopup.Toggle> toggles = new java.util.HashMap<>();
                for (String[][] g : GROUPS) {
                    body.addView(label(g[0][1] + "  " + T.t(g[0][0])), gap(18));
                    LinearLayout c = card();
                    c.setPadding(dp(16), dp(4), dp(16), dp(4));
                    for (int i = 1; i < g.length; i++) {
                        LinearLayout r = new LinearLayout(this);
                        r.setGravity(Gravity.CENTER_VERTICAL);
                        r.setPadding(0, dp(10), 0, dp(10));
                        TextView l = text(15, Ui.TEXT, false);
                        l.setText(T.t(g[i][1]));
                        r.addView(l, new LinearLayout.LayoutParams(0, -2, 1));
                        ZhPopup.Toggle t = new ZhPopup.Toggle(this, !offSet.contains(g[i][0]));
                        r.addView(t, new LinearLayout.LayoutParams(dp(48), dp(28)));
                        r.setOnClickListener(v -> t.performClick());
                        toggles.put(g[i][0], t);
                        c.addView(r);
                    }
                    body.addView(c);
                }
                body.addView(label("🚫  " + T.t("Access")), gap(18));
                LinearLayout bc = card();
                LinearLayout br = new LinearLayout(this);
                br.setGravity(Gravity.CENTER_VERTICAL);
                TextView bl = text(15, 0xFFEF4444, true);
                bl.setText(T.t("Block this user"));
                br.addView(bl, new LinearLayout.LayoutParams(0, -2, 1));
                final ZhPopup.Toggle block = new ZhPopup.Toggle(this, perms.optBoolean("blocked", false));
                br.addView(block, new LinearLayout.LayoutParams(dp(48), dp(28)));
                bc.addView(br);
                body.addView(bc);

                // ---- manage: sign out / remove
                body.addView(label("⚙  " + T.t("Manage")), gap(18));
                LinearLayout mc = card();
                TextView mh = text(13, Ui.DIM, false);
                mh.setText(T.t("Sign out: they must sign in again on all their devices.") + "\n"
                        + T.t("Remove: signs them out, deletes their record and their ZH Play cloud account. They can join again by signing in (use Block to stop that)."));
                mh.setLineSpacing(0, 1.15f);
                mc.addView(mh);
                body.addView(mc);
                buttons(body, button("🚪  " + T.t("Sign out user"), false, false, v -> ZhPopup.confirm(this, "🚪",
                                T.t("Sign out") + " " + u.optString("name") + "?", T.t("Within a minute on their open apps; at the latest when they next open ZH Play."),
                                T.t("Sign out"), false, () -> signOutUser(id, null))),
                        button("🗑  " + T.t("Remove user"), false, true, v -> ZhPopup.confirm(this, "🗑",
                                T.t("Remove") + " " + u.optString("name") + "?", u.optString("email") + "\n" + T.t("They are signed out everywhere and disappear from the list."),
                                T.t("Remove"), true, () -> removeUser(id, u))));
                buttons(body, button(T.t("All on"), false, false, v -> {
                    for (ZhPopup.Toggle t : toggles.values()) t.set(true, true);
                }), button("💾  " + T.t("Save"), true, false, v -> {
                    try {
                        JSONArray newOff = new JSONArray();
                        for (java.util.Map.Entry<String, ZhPopup.Toggle> e : toggles.entrySet()) if (!e.getValue().on()) newOff.put(e.getKey());
                        final JSONObject np = new JSONObject().put("off", newOff).put("blocked", block.on());
                        final boolean blockNow = block.on();
                        Runnable save = () -> {
                            put("/perms/" + id, np, "✓ " + T.t("Saved — applies on their app within a minute"));
                            blockEmail(u.optString("email"), blockNow);   // also by e-mail: holds if they sign in again
                        };
                        if (block.on() && !perms.optBoolean("blocked", false)) {
                            ZhPopup.confirm(this, "🚫", T.t("Block") + " " + u.optString("name") + "?",
                                    T.t("They won't be able to use ZH Play until you unblock them."), T.t("Block"), true, save);
                        } else save.run();
                    } catch (Exception ex) {
                        say("⚠ " + ex.getMessage());
                    }
                }));
            });
        }).start();
    }

    /** Marks the user signed out (their apps check it every minute). */
    private void signOutUser(final String id, final Runnable then) {
        new Thread(() -> {
            String msg = "✓ " + T.t("Signed out — their apps return to the sign-in screen");
            try {
                Cloud.http("PUT", Broadcast.url("/perms/" + id + "/signout"), new JSONObject().put(".sv", "timestamp").toString());
            } catch (Exception e) {
                msg = "⚠ " + e.getMessage() + (String.valueOf(e.getMessage()).contains("401") ? " — " + T.t("update the Firebase rules (ADMIN_GUIDE)") : "");
            }
            final String m = msg;
            main.post(() -> {
                say(m);
                if (then != null && !m.startsWith("⚠")) then.run();
            });
        }).start();
    }

    /** Sign out + delete their record + delete their cloud account (needs the Firebase key). */
    private void removeUser(final String id, final JSONObject u) {
        signOutUser(id, () -> new Thread(() -> {
            String msg = "✓ " + u.optString("name") + " " + T.t("removed");
            try {
                Cloud.http("DELETE", Broadcast.url("/members/" + id), null);
                String err = Fcm.ready() ? Fcm.deleteUser(id) : null;
                if (err != null) msg = "⚠ " + T.t("Removed from the list, but the cloud account wasn't deleted") + ": " + err;
                else if (!Fcm.ready()) msg += " — " + T.t("import the Firebase key to also delete their cloud account");
            } catch (Exception e) {
                msg = "⚠ " + e.getMessage() + (String.valueOf(e.getMessage()).contains("401") ? " — " + T.t("update the Firebase rules (ADMIN_GUIDE)") : "");
            }
            final String m = msg;
            main.post(() -> {
                say(m);
                members.remove(id);
                onBackPressed();                              // back to the user list
            });
        }).start());
    }

    /** Blocks / unblocks an e-mail (scrambled) for everyone — holds even if they sign in again. */
    private void blockEmail(final String email, final boolean on) {
        if (email == null || !email.contains("@")) return;
        new Thread(() -> {
            try {
                String path = Broadcast.url("/pub/blocked/" + Auth.hash(email));
                if (on) Cloud.http("PUT", path, "true");
                else Cloud.http("DELETE", path, null);
            } catch (Exception ignored) {
            }
        }).start();
    }

    // ------------------------------------------------------------------ broadcast

    private void broadcast() {
        final LinearLayout body = page("📡  " + T.t("Broadcast"), T.t("schedule, start and end a live show"), false);
        final JSONObject cur = Broadcast.current() == null ? new JSONObject() : Broadcast.current();
        String ph = Broadcast.phase(cur);
        LinearLayout status = card();
        status.setOrientation(LinearLayout.HORIZONTAL);
        status.setGravity(Gravity.CENTER_VERTICAL);
        TextView pill = text(13, Color.WHITE, true);
        pill.setText("live".equals(ph) ? "  🔴 LIVE  " : "soon".equals(ph) ? "  📅 " + T.t("SCHEDULED") + "  " : "  ■ " + T.t("NOT LIVE") + "  ");
        pill.setBackground(round("live".equals(ph) ? 0xFFDC2626 : "soon".equals(ph) ? 0xFF2563EB : 0xFF4B5563, dp(12)));
        status.addView(pill);
        TextView st = text(14, Ui.TEXT, false);
        st.setText(cur.optString("title").isEmpty() ? "  " + T.t("No broadcast yet") : "  " + cur.optString("title"));
        status.addView(st, new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(status, gap(4));

        LinearLayout form = card();
        form.addView(label(T.t("Type")));
        final String[] kinds = {"youtube", "premiere", "hls"};
        final String[] names = {"▶ YouTube Live", "🎞 " + T.t("Premiere"), "📶 " + T.t("Live .m3u8")};
        final int[] kind = {Math.max(0, java.util.Arrays.asList(kinds).indexOf(cur.optString("kind", "youtube")))};
        final LinearLayout kindRow = new LinearLayout(this);
        final TextView hint = text(12, Ui.DIM, false);
        final Runnable[] drawKinds = new Runnable[1];
        drawKinds[0] = () -> {
            kindRow.removeAllViews();
            for (int i = 0; i < kinds.length; i++) {
                final int k = i;
                TextView c = chip(names[i], i == kind[0], v -> {
                    kind[0] = k;
                    drawKinds[0].run();
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.setMargins(0, 0, dp(8), 0);
                kindRow.addView(c, lp);
            }
            hint.setText(kind[0] == 0 ? T.t("Your YouTube live link — viewers watch in YouTube.")
                    : kind[0] == 1 ? T.t("Any video file link (Seedr, Drive…) — everyone in sync, late joiners jump to the current point.")
                    : T.t("A live stream link ending in .m3u8 — plays in ZH Play."));
        };
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(kindRow);
        form.addView(hs);
        hint.setPadding(dp(2), dp(8), 0, 0);
        form.addView(hint);
        drawKinds[0].run();
        final EditText title = input(form, T.t("Title"), T.t("e.g. Friday movie night"), cur.optString("title"), false);
        final EditText url = input(form, T.t("Link"), "https://…", cur.optString("url"), false);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        final Calendar start = Calendar.getInstance();
        if (cur.optLong("start", 0) > 0) start.setTimeInMillis(cur.optLong("start"));
        else start.add(Calendar.HOUR_OF_DAY, 1);
        form.addView(label(T.t("Starts")), gap(14));
        final TextView when = button("🕗  " + Broadcast.when(start.getTimeInMillis()), false, false, null);
        when.setOnClickListener(v -> new DatePickerDialog(this, (p, y, m, d) -> {
            start.set(y, m, d);
            new TimePickerDialog(this, (tp, hh, mm) -> {
                start.set(Calendar.HOUR_OF_DAY, hh);
                start.set(Calendar.MINUTE, mm);
                start.set(Calendar.SECOND, 0);
                when.setText("🕗  " + Broadcast.when(start.getTimeInMillis()));
            }, start.get(Calendar.HOUR_OF_DAY), start.get(Calendar.MINUTE), true).show();
        }, start.get(Calendar.YEAR), start.get(Calendar.MONTH), start.get(Calendar.DAY_OF_MONTH)).show());
        form.addView(when, new LinearLayout.LayoutParams(-1, dp(50)));
        final EditText minutes = input(form, T.t("Length (minutes)"), "120", String.valueOf(cur.optLong("end", 0) > cur.optLong("start", 0)
                ? (cur.optLong("end") - cur.optLong("start")) / 60000 : 120), false);
        minutes.setInputType(InputType.TYPE_CLASS_NUMBER);
        body.addView(form, gap(12));

        buttons(body, button("💾  " + T.t("Save"), false, false, v -> saveBroadcast(cur, kinds[kind[0]], title, url, start, minutes, false)),
                button("📣  " + T.t("Save + notify"), true, false, v -> saveBroadcast(cur, kinds[kind[0]], title, url, start, minutes, true)));
        buttons(body, button("▶  " + T.t("Start now"), true, false, v -> setState("live")),
                button("■  " + T.t("End now"), false, true, v -> setState("ended")));
    }

    private void saveBroadcast(JSONObject cur, String kind, EditText title, EditText url, Calendar start, EditText minutes, boolean notify) {
        String u = url.getText().toString().trim(), t = title.getText().toString().trim();
        if (u.isEmpty() || t.isEmpty()) {
            say("⚠ " + T.t("Fill in the title and the link"));
            return;
        }
        long len;
        try {
            len = Math.max(1, Long.parseLong(minutes.getText().toString().trim()));
        } catch (Exception e) {
            len = 120;
        }
        try {
            String id = cur.optString("url").equals(u) && !cur.optString("id").isEmpty() ? cur.optString("id") : Broadcast.newId();
            JSONObject b = new JSONObject().put("id", id).put("title", t).put("kind", kind).put("url", u)
                    .put("start", start.getTimeInMillis()).put("end", start.getTimeInMillis() + len * 60000).put("state", "scheduled");
            put("/pub/broadcast", b, "💾 " + T.t("Broadcast saved"));
            if (notify) pushAsync("📅 " + t, T.t("Live on ZH Play at") + " " + Broadcast.when(start.getTimeInMillis()), "", id);
        } catch (Exception e) {
            say("⚠ " + e.getMessage());
        }
    }

    private void setState(final String st) {
        final JSONObject b = Broadcast.current();
        if (b == null || b.optString("url").isEmpty()) {
            say("⚠ " + T.t("Save a broadcast first"));
            return;
        }
        boolean live = "live".equals(st);
        ZhPopup.confirm(this, live ? "🔴" : "■", live ? T.t("Go live now?") : T.t("End the broadcast?"),
                b.optString("title") + (live ? "\n" + T.t("Everyone is notified and sees “LIVE NOW”.") : "\n" + T.t("It disappears from every app.")),
                live ? T.t("Go live") : T.t("End"), !live, () -> {
                    try {
                        b.put("state", st).put(live ? "startedAt" : "endedAt", System.currentTimeMillis());
                        if (live) b.put("start", Math.min(b.optLong("start", System.currentTimeMillis()), System.currentTimeMillis()));
                        put("/pub/broadcast", b, live ? "🔴 " + T.t("Live — everyone sees it now") : "✓ " + T.t("Broadcast ended"));
                        if (live) pushAsync("🔴 " + T.t("LIVE NOW") + ": " + b.optString("title"), T.t("Tap to watch on ZH Play"), "", b.optString("id"));
                        main.postDelayed(this::broadcast, 1500);
                    } catch (Exception e) {
                        say("⚠ " + e.getMessage());
                    }
                });
    }

    // ------------------------------------------------------------------ scroller

    private void scroller() {
        LinearLayout body = page("📢  " + T.t("News scroller"), T.t("scrolls on every home screen"), false);
        JSONObject tk = Broadcast.pub().optJSONObject("ticker");
        LinearLayout c = card();
        final EditText t = input(c, T.t("Text"), T.t("e.g. New movies added every Friday!"), tk == null ? "" : tk.optString("text"), true);
        c.addView(label(T.t("Preview")), gap(16));
        final TextView prev = text(14, Color.WHITE, true);
        prev.setBackground(round(0xEE111827, dp(10)));
        prev.setPadding(dp(12), dp(10), dp(12), dp(10));
        prev.setSingleLine(true);
        prev.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
        prev.setMarqueeRepeatLimit(-1);
        prev.setSelected(true);
        prev.setText("📢  " + t.getText());
        t.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int x) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int x) {
            }

            public void afterTextChanged(Editable e) {
                prev.setText("📢  " + e);
            }
        });
        c.addView(prev);
        body.addView(c, gap(4));
        buttons(body, button(T.t("Clear"), false, false, v -> ZhPopup.confirm(this, "📢", T.t("Remove the scroller?"), null, T.t("Remove"), true, () -> {
                    t.setText("");
                    put("/pub/ticker", obj("text", ""), "✓ " + T.t("Scroller removed"));
                })),
                button("📢  " + T.t("Show to everyone"), true, false, v -> put("/pub/ticker", obj("text", t.getText().toString().trim()), "✓ " + T.t("Scroller updated"))));
    }

    // ------------------------------------------------------------------ notify

    private void notifyPage() {
        LinearLayout body = page("📣  " + T.t("Send notification"), T.t("to every phone and TV, even when closed"), false);
        LinearLayout c = card();
        final EditText title = input(c, T.t("Title"), "ZH Play", "", false);
        final EditText msg = input(c, T.t("Message"), T.t("What do you want to tell everyone?"), "", true);
        final EditText link = input(c, T.t("Link (optional)"), T.t("video, YouTube or web page"), "", false);
        link.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        c.addView(label(T.t("Open the link in")), gap(14));
        final String[] modes = {"auto", "player", "browser"};
        final String[] modeNames = {"✨ " + T.t("Auto"), "▶ " + T.t("ZH Play player"), "🌐 " + T.t("Browser")};
        final int[] mode = {0};
        final LinearLayout modeRow = new LinearLayout(this);
        final Runnable[] drawModes = new Runnable[1];
        drawModes[0] = () -> {
            modeRow.removeAllViews();
            for (int i = 0; i < modes.length; i++) {
                final int k = i;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.setMargins(0, 0, dp(8), 0);
                modeRow.addView(chip(modeNames[i], i == mode[0], v -> {
                    mode[0] = k;
                    drawModes[0].run();
                }), lp);
            }
        };
        drawModes[0].run();
        HorizontalScrollView ms = new HorizontalScrollView(this);
        ms.setHorizontalScrollBarEnabled(false);
        ms.addView(modeRow);
        c.addView(ms);
        TextView mh = text(12, Ui.DIM, false);
        mh.setText(T.t("Auto: videos, Drive / Dropbox video links and streams play in ZH Play; YouTube in YouTube; pages in the browser."));
        mh.setPadding(dp(2), dp(8), 0, 0);
        c.addView(mh);
        body.addView(c, gap(4));
        // preview of how it looks
        body.addView(label(T.t("Preview")), gap(18));
        LinearLayout pv = card();
        pv.setOrientation(LinearLayout.HORIZONTAL);
        TextView ic = text(18, Color.WHITE, false);
        ic.setText("🔔");
        ic.setGravity(Gravity.CENTER);
        ic.setBackground(round(Ui.ACCENT, dp(16)));
        pv.addView(ic, new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout pc = new LinearLayout(this);
        pc.setOrientation(LinearLayout.VERTICAL);
        pc.setPadding(dp(12), 0, 0, 0);
        final TextView pt = text(15, Ui.TEXT, true);
        final TextView pb = text(14, Ui.DIM, false);
        pt.setText("ZH Play");
        pc.addView(pt);
        pc.addView(pb);
        pv.addView(pc, new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(pv);
        TextWatcher w = new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int x) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int x) {
            }

            public void afterTextChanged(Editable e) {
                pt.setText(title.getText().length() == 0 ? "ZH Play" : title.getText());
                pb.setText(msg.getText());
            }
        };
        title.addTextChangedListener(w);
        msg.addTextChangedListener(w);
        body.addView(button("📣  " + T.t("Send to everyone"), true, false, v -> {
            final String t = title.getText().toString().trim().isEmpty() ? "ZH Play" : title.getText().toString().trim();
            final String m = msg.getText().toString().trim(), l = link.getText().toString().trim();
            if (m.isEmpty()) {
                say("⚠ " + T.t("Write a message"));
                return;
            }
            if (!Fcm.ready() && Store.getStr("gh_pat", "").isEmpty()) {
                ZhPopup.confirm(this, "🔑", T.t("Push isn't set up"), T.t("Import the Firebase key once (Push setup)."), T.t("Open"), false, () -> go(this::pushSetup));
                return;
            }
            final String om = modes[mode[0]];
            ZhPopup.confirm(this, "📣", T.t("Send to everyone?"), t + "\n" + m, T.t("Send"), false, () -> pushAsync(t, m, l, "", om));
        }), gap(18));
        ((LinearLayout.LayoutParams) body.getChildAt(body.getChildCount() - 1).getLayoutParams()).height = dp(52);
    }

    // ------------------------------------------------------------------ monitor

    private void monitor() {
        final LinearLayout body = page("📊  " + T.t("Live monitor"), T.t("updates every 10 seconds"), false);
        final JSONObject b = Broadcast.current();
        if (b == null || b.optString("id").isEmpty()) {
            TextView t = text(15, Ui.DIM, false);
            t.setText(T.t("No broadcast yet — create one in Broadcast."));
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(40), 0, 0);
            body.addView(t);
            return;
        }
        TextView title = text(17, Ui.TEXT, true);
        title.setText(b.optString("title") + "   ·   " + Broadcast.kindLabel(b.optString("kind")));
        body.addView(title, gap(4));
        LinearLayout nums = new LinearLayout(this);
        final TextView nNow = stat(nums, "👀", T.t("Watching"), true), nJoined = stat(nums, "🙋", T.t("Joined"), false),
                nPeak = stat(nums, "📈", T.t("Peak"), false);
        body.addView(nums, gap(12));
        body.addView(label(T.t("Viewers")), gap(18));
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        body.addView(list);
        TextView note = text(12, Ui.DIM, false);
        note.setText(T.t("YouTube viewers count when they tap Watch; in-app viewers count while watching."));
        note.setPadding(0, dp(14), 0, 0);
        body.addView(note);
        pageTick = new Runnable() {
            public void run() {
                new Thread(() -> {
                    try {
                        String s = Cloud.http("GET", Broadcast.url("/live/" + b.optString("id")), null);
                        final JSONObject v = s == null || s.trim().equals("null") ? new JSONObject() : new JSONObject(s);
                        main.post(() -> {
                            long now = System.currentTimeMillis();
                            int joined = 0, watching = 0;
                            list.removeAllViews();
                            for (Iterator<String> it = v.keys(); it.hasNext(); ) {
                                JSONObject x = v.optJSONObject(it.next());
                                if (x == null) continue;
                                joined++;
                                boolean on = now - x.optLong("at", 0) < 3 * 60 * 1000L;
                                if (on) watching++;
                                LinearLayout r = new LinearLayout(AdminActivity.this);
                                r.setGravity(Gravity.CENTER_VERTICAL);
                                r.setPadding(0, dp(6), 0, dp(6));
                                r.addView(avatar(x.optString("name"), 34), new LinearLayout.LayoutParams(dp(34), dp(34)));
                                TextView n = text(14, Ui.TEXT, false);
                                n.setText("  " + x.optString("name") + (x.optBoolean("tv") ? "  📺" : ""));
                                r.addView(n, new LinearLayout.LayoutParams(0, -2, 1));
                                TextView sdot = text(12, on ? 0xFF22C55E : Ui.DIM, true);
                                sdot.setText(on ? "● " + T.t("watching") : T.t("left"));
                                r.addView(sdot);
                                list.addView(r);
                            }
                            int peak = Math.max(Store.getInt("peak_" + b.optString("id"), 0), watching);
                            Store.putInt("peak_" + b.optString("id"), peak);
                            nNow.setText(String.valueOf(watching));
                            nJoined.setText(String.valueOf(joined));
                            nPeak.setText(String.valueOf(peak));
                        });
                    } catch (Exception e) {
                        main.post(() -> say("⚠ " + e.getMessage()));
                    }
                }).start();
                main.postDelayed(this, 10000);
            }
        };
        pageTick.run();
    }

    // ------------------------------------------------------------------ push setup

    private void pushSetup() {
        LinearLayout body = page("🔑  " + T.t("Push setup"), T.t("once — then send from here"), false);
        LinearLayout c = card();
        TextView st = text(16, Ui.TEXT, true);
        st.setText(Fcm.ready() ? "✅  " + T.t("Ready — pushes go straight from this phone") : "⚠  " + T.t("Not set up yet"));
        c.addView(st);
        if (Fcm.ready()) {
            TextView p = text(13, Ui.DIM, false);
            p.setText(T.t("Firebase project") + ": " + Fcm.projectOf());
            p.setPadding(0, dp(4), 0, 0);
            c.addView(p);
        }
        TextView steps = text(14, Ui.DIM, false);
        steps.setText("1.  Firebase → ⚙ Project settings → Service accounts\n2.  Generate new private key (a .json downloads)\n3.  Import it here\n\n"
                + T.t("The key stays only on this phone. Never upload it anywhere."));
        steps.setPadding(0, dp(14), 0, 0);
        steps.setLineSpacing(0, 1.2f);
        c.addView(steps);
        body.addView(c, gap(4));
        buttons(body, button(T.t("Remove key"), false, true, v -> ZhPopup.confirm(this, "🔑", T.t("Remove the key?"),
                        T.t("You won't be able to send notifications until you import it again."), T.t("Remove"), true, () -> {
                            Fcm.forget();
                            go(this::pushSetup);
                        })),
                button("📂  " + T.t("Import key"), true, false, v -> startActivityForResult(
                        new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 77)));
        body.addView(button("📣  " + T.t("Send a test"), false, false, v -> pushAsync("ZH Play", T.t("Test from the admin"), "", "")), gap(10));
        ((LinearLayout.LayoutParams) body.getChildAt(body.getChildCount() - 1).getLayoutParams()).height = dp(50);
        // advanced (optional): send through the GitHub workflow "Broadcast" instead of the key
        body.addView(label(T.t("Advanced (optional)")), gap(24));
        LinearLayout adv = card();
        TextView ah = text(13, Ui.DIM, false);
        ah.setText(T.t("Only if you prefer sending through GitHub: a fine-grained token for MyTvPlay2 with Contents: Read and write. Used only when no key is imported."));
        adv.addView(ah);
        final EditText gh = input(adv, T.t("GitHub token"), "github_pat_…", Store.getStr("gh_pat", "").isEmpty() ? "" : "••••••••", false);
        body.addView(adv);
        buttons(body, button(T.t("Clear token"), false, false, v -> {
            Store.putStr("gh_pat", "");
            gh.setText("");
            say("✓ " + T.t("Token removed"));
        }), button(T.t("Save token"), false, false, v -> {
            String t = gh.getText().toString().trim();
            if (!t.startsWith("•")) Store.putStr("gh_pat", t);
            say("✓ " + T.t("Saved"));
        }));
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != 77 || res != RESULT_OK || data == null || data.getData() == null) return;
        try (java.io.InputStream in = getContentResolver().openInputStream(data.getData())) {
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while (in != null && (n = in.read(b)) > 0 && o.size() < 64 * 1024) o.write(b, 0, n);
            String err = Fcm.importKey(o.toString("UTF-8"));
            say(err == null ? "✅ " + T.t("Firebase key imported") : "⚠ " + err);
        } catch (Exception e) {
            say("⚠ " + e.getMessage());
        }
        if (!stack.isEmpty()) stack.get(stack.size() - 1).run();
    }

    // ================================================================== data

    private static JSONObject obj(String k, Object v) {
        try {
            return new JSONObject().put(k, v).put("at", new JSONObject().put(".sv", "timestamp"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void put(final String path, final JSONObject o, final String ok) {
        new Thread(() -> {
            String msg = ok;
            try {
                Cloud.http("PUT", Broadcast.url(path), o.toString());
                Broadcast.sync(getApplicationContext(), true);
            } catch (Exception e) {
                msg = "⚠ " + e.getMessage() + (String.valueOf(e.getMessage()).contains("401") ? " — " + T.t("check the Firebase rules") : "");
            }
            final String m = msg;
            main.post(() -> say(m));
        }).start();
    }

    private void pushAsync(final String title, final String body, final String link, final String bid) {
        pushAsync(title, body, link, bid, "auto");
    }

    private void pushAsync(final String title, final String body, final String link, final String bid, final String open) {
        say("📣 " + T.t("Sending…"));
        new Thread(() -> {
            final String r = Push.send(title, body, link, bid, open);
            saveToInbox(title, body, link, open);           // missed it? it's in 🔔 Notifications
            main.post(() -> say(r));
        }).start();
    }

    /** Keeps the message in pub/inbox so every app can show it later; trims to the latest 50. */
    private void saveToInbox(String title, String body, String link, String open) {
        try {
            JSONObject m = new JSONObject().put("title", title).put("body", body).put("link", link == null ? "" : link)
                    .put("open", open == null ? "auto" : open).put("at", new JSONObject().put(".sv", "timestamp"));
            Cloud.http("POST", Broadcast.url("/pub/inbox"), m.toString());
            String s = Cloud.http("GET", Broadcast.url("/pub/inbox"), null);
            if (s == null || s.trim().equals("null")) return;
            JSONObject all = new JSONObject(s);
            if (all.length() <= 50) return;
            List<String> ids = new ArrayList<>();
            for (Iterator<String> it = all.keys(); it.hasNext(); ) ids.add(it.next());
            Collections.sort(ids, (a, b) -> Long.compare(all.optJSONObject(a).optLong("at"), all.optJSONObject(b).optLong("at")));
            for (int i = 0; i < ids.size() - 50; i++) Cloud.http("DELETE", Broadcast.url("/pub/inbox/" + ids.get(i)), null);
        } catch (Exception ignored) {
        }
    }

    private void loadMembers(final Runnable then) {
        new Thread(() -> {
            try {
                String s = Cloud.http("GET", Broadcast.url("/members"), null);
                final JSONObject m = s == null || s.trim().equals("null") ? new JSONObject() : new JSONObject(s);
                main.post(() -> {
                    members = m;
                    fillStats();
                    if (then != null) then.run();
                });
            } catch (Exception e) {
                main.post(() -> say("⚠ " + e.getMessage() + (String.valueOf(e.getMessage()).contains("401") ? " — " + T.t("check the Firebase rules") : "")));
            }
        }).start();
    }
}
