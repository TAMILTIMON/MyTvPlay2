package com.mytv.player;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Admin panel (only for admins in access.json; Firebase rules also only accept the admin's e-mail):
 * users + per-user menus, the news scroller, broadcasts (YouTube Live / premiere file / .m3u8 live),
 * start / end, push to everyone, and the live monitor.
 */
public class AdminActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout box;
    private TextView stats, monitor;
    private LinearLayout userList;
    private TextView keyStatus;

    private void showKeyStatus() {
        if (keyStatus != null) keyStatus.setText(Fcm.ready() ? "✅ " + T.t("Key imported — pushes go straight from this phone") + " (" + Fcm.projectOf() + ")"
                : "⚠ " + T.t("No key yet — import it once to send notifications from here"));
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
            toast(err == null ? "✅ " + T.t("Firebase key imported") : "⚠ " + err);
        } catch (Exception e) {
            toast("⚠ " + e.getMessage());
        }
        showKeyStatus();
    }
    private JSONObject members = new JSONObject();

    /** Menus an admin can switch off per user (phone board, TV board and remote tiles). */
    static final String[][] FEATURES = {
            {"browser", "Browser"}, {"videos", "Videos"}, {"music", "Music"}, {"dj", "DJ mixer"}, {"editor", "Song editor"},
            {"board", "Whiteboard"}, {"present", "Present"}, {"vpn", "VPN"}, {"seedr", "Seedr"}, {"torrents", "Torrents / magnets"},
            {"storage", "SD Card & USB"}, {"network", "Network folders"}, {"local_files", "Local files"}, {"library", "Library"},
            {"ai_assistant", "AI assistant"}, {"cast_screen", "Cast screen"}, {"live_tv_screen", "Live TV screen"}, {"apps", "Apps"},
            {"playlists", "Playlists"}, {"share", "Share phone videos"}, {"eq", "Equalizer"}, {"updates", "Updates"}};

    static void open(Context c) {
        if (!Auth.isAdmin()) {
            UiKit.makeToast(c, T.t("Admins only"), Toast.LENGTH_LONG).show();
            return;
        }
        c.startActivity(new Intent(c, AdminActivity.class));
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        UiKit.makeToast(this, s, Toast.LENGTH_LONG).show();
    }

    private TextView text(float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private void section(String s) {
        TextView t = text(13, Ui.DIM, true);
        t.setText(s.toUpperCase(java.util.Locale.getDefault()));
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(4), dp(22), 0, dp(6));
        box.addView(t);
    }

    private TextView btn(String label, boolean primary, View.OnClickListener l) {
        TextView t = text(15, primary ? Color.WHITE : Ui.TEXT, true);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(12), dp(14), dp(12));
        t.setBackground(primary ? Ui.gradient(this, 20) : Ui.focusCard(this, 20));
        t.setFocusable(true);
        t.setOnClickListener(l);
        return t;
    }

    private void row(View... vs) {
        LinearLayout r = new LinearLayout(this);
        for (int i = 0; i < vs.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.setMargins(i == 0 ? 0 : dp(6), dp(6), 0, 0);
            r.addView(vs[i], lp);
        }
        box.addView(r);
    }

    private EditText field(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.DIM);
        e.setSingleLine(true);
        return e;
    }

    // ================================================================== layout

    @Override
    protected void onCreate(Bundle b) {
        Ui.apply(this);
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(18), dp(18), dp(40));
        sv.addView(box);
        setContentView(sv);

        TextView h = text(22, Ui.TEXT, true);
        h.setText("🛡  " + T.t("ZH Play admin"));
        box.addView(h);
        TextView who = text(13, Ui.DIM, false);
        who.setText(Auth.email() + (Cloud.signedIn() ? "" : "\n⚠ " + T.t("ZH Play cloud not connected — Account → Cloud status")));
        box.addView(who);

        // ---- users
        section(T.t("Users"));
        stats = text(15, Ui.TEXT, false);
        box.addView(stats);
        row(btn("🔄 " + T.t("Refresh"), false, v -> loadAll()));
        userList = new LinearLayout(this);
        userList.setOrientation(LinearLayout.VERTICAL);
        box.addView(userList);

        // ---- scroller
        section(T.t("News scroller (all apps)"));
        JSONObject tk = Broadcast.pub().optJSONObject("ticker");
        final EditText ticker = field(T.t("e.g. Live tonight 8 PM: …"), tk == null ? "" : tk.optString("text"));
        box.addView(ticker);
        row(btn(T.t("Show to everyone"), true, v -> put("/pub/ticker", obj("text", ticker.getText().toString().trim()), T.t("Scroller updated"))),
                btn(T.t("Clear"), false, v -> {
                    ticker.setText("");
                    put("/pub/ticker", obj("text", ""), T.t("Scroller cleared"));
                }));

        // ---- broadcast
        section(T.t("Broadcast"));
        final JSONObject cur = Broadcast.current() == null ? new JSONObject() : Broadcast.current();
        final String[] kinds = {"youtube", "premiere", "hls"};
        final int[] kind = {Math.max(0, java.util.Arrays.asList(kinds).indexOf(cur.optString("kind", "youtube")))};
        final TextView kindBtn = btn("", false, null);
        kindBtn.setText(T.t("Type") + ": " + Broadcast.kindLabel(kinds[kind[0]]));
        kindBtn.setOnClickListener(v -> new UiDialog(this).setTitle(T.t("Type"))
                .setItems(new String[]{Broadcast.kindLabel("youtube"), Broadcast.kindLabel("premiere"), Broadcast.kindLabel("hls")}, (d, w) -> {
                    kind[0] = w;
                    kindBtn.setText(T.t("Type") + ": " + Broadcast.kindLabel(kinds[w]));
                }).show());
        box.addView(kindBtn);
        final EditText title = field(T.t("Title"), cur.optString("title"));
        final EditText url = field(T.t("Link: YouTube live URL, video file link, or .m3u8"), cur.optString("url"));
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        box.addView(title);
        box.addView(url);
        final Calendar start = Calendar.getInstance();
        if (cur.optLong("start", 0) > 0) start.setTimeInMillis(cur.optLong("start"));
        else start.add(Calendar.HOUR_OF_DAY, 1);
        final EditText minutes = field(T.t("Length in minutes"), String.valueOf(cur.optLong("end", 0) > cur.optLong("start", 0)
                ? (cur.optLong("end") - cur.optLong("start")) / 60000 : 120));
        minutes.setInputType(InputType.TYPE_CLASS_NUMBER);
        final TextView startBtn = btn("", false, null);
        startBtn.setText("🕗 " + T.t("Starts") + ": " + Broadcast.when(start.getTimeInMillis()));
        startBtn.setOnClickListener(v -> new DatePickerDialog(this, (dp, y, m, d) -> {
            start.set(y, m, d);
            new TimePickerDialog(this, (tp, hh, mm) -> {
                start.set(Calendar.HOUR_OF_DAY, hh);
                start.set(Calendar.MINUTE, mm);
                start.set(Calendar.SECOND, 0);
                startBtn.setText("🕗 " + T.t("Starts") + ": " + Broadcast.when(start.getTimeInMillis()));
            }, start.get(Calendar.HOUR_OF_DAY), start.get(Calendar.MINUTE), true).show();
        }, start.get(Calendar.YEAR), start.get(Calendar.MONTH), start.get(Calendar.DAY_OF_MONTH)).show());
        box.addView(startBtn);
        box.addView(minutes);
        row(btn("💾 " + T.t("Save schedule"), true, v -> saveBroadcast(cur, kinds[kind[0]], title, url, start, minutes, "scheduled", false)),
                btn("📣 " + T.t("Save + notify all"), false, v -> saveBroadcast(cur, kinds[kind[0]], title, url, start, minutes, "scheduled", true)));
        row(btn("▶ " + T.t("Start now"), true, v -> setState("live")), btn("■ " + T.t("End now"), false, v -> setState("ended")));

        // ---- monitor
        section(T.t("Live monitor"));
        monitor = text(14, Ui.TEXT, false);
        box.addView(monitor);

        // ---- send anything to everyone
        section(T.t("Send a notification to everyone"));
        final EditText nTitle = field(T.t("Title"), "");
        final EditText nBody = field(T.t("Message"), "");
        nBody.setSingleLine(false);
        nBody.setMinLines(2);
        final EditText nLink = field(T.t("Link to open (optional): video, YouTube or web page"), "");
        nLink.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        box.addView(nTitle);
        box.addView(nBody);
        box.addView(nLink);
        box.addView(btn("📣 " + T.t("Send to everyone"), true, v -> {
            String t = nTitle.getText().toString().trim(), m = nBody.getText().toString().trim();
            if (t.isEmpty() && m.isEmpty()) {
                toast(T.t("Write a title or a message"));
                return;
            }
            final String ft = t.isEmpty() ? "ZH Play" : t, fl = nLink.getText().toString().trim();
            new UiDialog(this).setTitle("📣 " + T.t("Send to every ZH Play?")).setMessage(ft + "\n" + m)
                    .setPositiveButton(T.t("Send"), (d, w) -> new Thread(() -> {
                        final String r = Push.send(ft, m, fl, "");
                        main.post(() -> toast(r));
                    }).start())
                    .setNegativeButton(T.t("Cancel"), null).show();
        }));

        // ---- push setup: the Firebase key, once (no GitHub needed)
        section(T.t("Push setup"));
        keyStatus = text(14, Ui.TEXT, false);
        box.addView(keyStatus);
        showKeyStatus();
        row(btn("📂 " + T.t("Import Firebase key (.json)"), true, v -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            startActivityForResult(pick, 77);
        }), btn(T.t("Remove key"), false, v -> {
            Fcm.forget();
            showKeyStatus();
        }));
        TextView kh = text(12, Ui.DIM, false);
        kh.setText(T.t("Firebase → ⚙ Project settings → Service accounts → Generate new private key → import that file here. It stays only on this phone — never upload it anywhere."));
        box.addView(kh);
        section(T.t("Optional: send through GitHub instead"));
        TextView ph = text(13, Ui.DIM, false);
        ph.setText(T.t("Instant notifications are sent by the GitHub workflow “Broadcast”. Paste a GitHub token (fine-grained, only this repo, permission Contents: Read and write) — it stays only on this phone."));
        box.addView(ph);
        final EditText gh = field("github_pat_…", Store.getStr("gh_pat", "").isEmpty() ? "" : "••••••••");
        box.addView(gh);
        row(btn(T.t("Save token"), false, v -> {
            String t = gh.getText().toString().trim();
            if (!t.startsWith("•")) Store.putStr("gh_pat", t);
            toast(T.t("Saved"));
        }), btn("📣 " + T.t("Test push"), false, v -> pushAsync("ZH Play", T.t("Test from the admin"), "")));

        loadAll();
    }

    @Override
    protected void onResume() {
        super.onResume();
        main.postDelayed(tick, 10000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(tick);
    }

    private final Runnable tick = new Runnable() {
        public void run() {
            loadMonitor();
            main.postDelayed(this, 10000);
        }
    };

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
                msg = "⚠ " + e.getMessage() + (String.valueOf(e.getMessage()).contains("401") || String.valueOf(e.getMessage()).contains("ermission")
                        ? "\n" + T.t("Check the Firebase rules (admin e-mail) — see ADMIN_GUIDE") : "");
            }
            final String m = msg;
            main.post(() -> toast(m));
        }).start();
    }

    private void saveBroadcast(JSONObject cur, String kind, EditText title, EditText url, Calendar start, EditText minutes,
                               String state, boolean notify) {
        String u = url.getText().toString().trim(), t = title.getText().toString().trim();
        if (u.isEmpty() || t.isEmpty()) {
            toast(T.t("Fill in the title and the link"));
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
                    .put("start", start.getTimeInMillis()).put("end", start.getTimeInMillis() + len * 60000).put("state", state);
            put("/pub/broadcast", b, "💾 " + T.t("Broadcast saved"));
            if (notify) pushAsync("📅 " + t, T.t("Live on ZH Play at") + " " + Broadcast.when(start.getTimeInMillis()), id);
        } catch (Exception e) {
            toast("⚠ " + e.getMessage());
        }
    }

    private void setState(final String st) {
        final JSONObject b = Broadcast.current();
        if (b == null || b.optString("url").isEmpty()) {
            toast(T.t("Save a broadcast first"));
            return;
        }
        new UiDialog(this).setTitle("live".equals(st) ? "▶ " + T.t("Start now?") : "■ " + T.t("End now?")).setMessage(b.optString("title"))
                .setPositiveButton(T.t("Yes"), (d, w) -> {
                    try {
                        b.put("state", st).put("live".equals(st) ? "startedAt" : "endedAt", System.currentTimeMillis());
                        if ("live".equals(st)) b.put("start", Math.min(b.optLong("start", System.currentTimeMillis()), System.currentTimeMillis()));
                        put("/pub/broadcast", b, "live".equals(st) ? "🔴 " + T.t("Live — everyone sees it now") : "■ " + T.t("Ended"));
                        if ("live".equals(st)) pushAsync("🔴 " + T.t("LIVE NOW") + ": " + b.optString("title"), T.t("Tap to watch on ZH Play"), b.optString("id"));
                    } catch (Exception e) {
                        toast("⚠ " + e.getMessage());
                    }
                }).setNegativeButton(T.t("Cancel"), null).show();
    }

    private void pushAsync(final String title, final String body, final String bid) {
        new Thread(() -> {
            final String r = Push.send(title, body, bid);
            main.post(() -> toast(r));
        }).start();
    }

    private void loadAll() {
        stats.setText(T.t("Loading…"));
        new Thread(() -> {
            try {
                String s = Cloud.http("GET", Broadcast.url("/members"), null);
                final JSONObject m = s == null || s.trim().equals("null") ? new JSONObject() : new JSONObject(s);
                main.post(() -> {
                    members = m;
                    renderUsers();
                    loadMonitor();
                });
            } catch (Exception e) {
                main.post(() -> stats.setText("⚠ " + e.getMessage() + "\n" + T.t("Check the Firebase rules (admin e-mail) — see ADMIN_GUIDE")));
            }
        }).start();
    }

    private void renderUsers() {
        long now = System.currentTimeMillis(), day = 24 * 3600 * 1000L;
        int total = 0, today = 0, week = 0, tvs = 0;
        final List<String> ids = new ArrayList<>();
        for (Iterator<String> it = members.keys(); it.hasNext(); ) {
            String id = it.next();
            JSONObject u = members.optJSONObject(id);
            if (u == null) continue;
            ids.add(id);
            total++;
            long last = u.optLong("last", 0);
            if (now - last < day) today++;
            if (now - last < 7 * day) week++;
            if (u.optBoolean("tv")) tvs++;
        }
        Collections.sort(ids, (a, b) -> Long.compare(members.optJSONObject(b).optLong("last"), members.optJSONObject(a).optLong("last")));
        stats.setText("👥 " + total + " " + T.t("users") + "   ·   🟢 " + today + " " + T.t("today") + "   ·   " + week + " " + T.t("this week")
                + "   ·   📺 " + tvs + " TV");
        userList.removeAllViews();
        for (final String id : ids) {
            JSONObject u = members.optJSONObject(id);
            long mins = (now - u.optLong("last", 0)) / 60000;
            String seen = mins < 60 ? mins + " min" : mins < 2880 ? (mins / 60) + " h" : (mins / 1440) + " d";
            TextView r = text(14, Ui.TEXT, false);
            r.setText((u.optBoolean("tv") ? "📺 " : "📱 ") + u.optString("name", "?") + "   " + u.optString("email")
                    + "\n      " + u.optString("device") + " · " + u.optString("ver") + " · " + T.t("seen") + " " + seen + " " + T.t("ago"));
            r.setPadding(dp(12), dp(10), dp(12), dp(10));
            r.setBackground(Ui.focusCard(this, 14));
            r.setFocusable(true);
            r.setOnClickListener(v -> editUser(id, u));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, dp(6), 0, 0);
            userList.addView(r, lp);
        }
    }

    /** Which menus this user gets; block / unblock. */
    private void editUser(final String id, final JSONObject u) {
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
                JSONArray off = perms.optJSONArray("off");
                final boolean[] on = new boolean[FEATURES.length + 1];
                String[] labels = new String[FEATURES.length + 1];
                for (int i = 0; i < FEATURES.length; i++) {
                    labels[i] = T.t(FEATURES[i][1]);
                    boolean isOff = false;
                    if (off != null) for (int k = 0; k < off.length(); k++) if (FEATURES[i][0].equals(off.optString(k))) isOff = true;
                    on[i] = !isOff;
                }
                labels[FEATURES.length] = "🚫 " + T.t("Block this user");
                on[FEATURES.length] = perms.optBoolean("blocked", false);
                new UiDialog(this).setTitle(u.optString("name") + " — " + T.t("menus"))
                        .setMultiChoiceItems(labels, on, (d, w, c) -> on[w] = c)
                        .setPositiveButton(T.t("Save"), (d, w) -> {
                            try {
                                JSONArray newOff = new JSONArray();
                                for (int i = 0; i < FEATURES.length; i++) if (!on[i]) newOff.put(FEATURES[i][0]);
                                JSONObject np = new JSONObject().put("off", newOff).put("blocked", on[FEATURES.length]);
                                put("/perms/" + id, np, "✓ " + T.t("Saved — applies on their app within a minute"));
                            } catch (Exception e) {
                                toast("⚠ " + e.getMessage());
                            }
                        }).setNegativeButton(T.t("Cancel"), null).show();
            });
        }).start();
    }

    private void loadMonitor() {
        final JSONObject b = Broadcast.current();
        if (b == null || b.optString("id").isEmpty()) {
            monitor.setText(T.t("No broadcast saved yet."));
            return;
        }
        new Thread(() -> {
            String txt;
            try {
                String s = Cloud.http("GET", Broadcast.url("/live/" + b.optString("id")), null);
                JSONObject v = s == null || s.trim().equals("null") ? new JSONObject() : new JSONObject(s);
                long now = System.currentTimeMillis();
                int joined = 0, watching = 0;
                StringBuilder names = new StringBuilder();
                for (Iterator<String> it = v.keys(); it.hasNext(); ) {
                    JSONObject x = v.optJSONObject(it.next());
                    if (x == null) continue;
                    joined++;
                    boolean now2 = now - x.optLong("at", 0) < 3 * 60 * 1000L;
                    if (now2) watching++;
                    names.append(now2 ? "🟢 " : "⚪ ").append(x.optString("name")).append(x.optBoolean("tv") ? " (TV)" : "").append('\n');
                }
                int peak = Math.max(Store.getInt("peak_" + b.optString("id"), 0), watching);
                Store.putInt("peak_" + b.optString("id"), peak);
                String ph = Broadcast.phase(b);
                txt = "“" + b.optString("title") + "”  ·  " + Broadcast.kindLabel(b.optString("kind")) + "\n"
                        + ("live".equals(ph) ? "🔴 " + T.t("LIVE") : "soon".equals(ph) ? "📅 " + T.t("Scheduled") + ": " + Broadcast.when(b.optLong("start"))
                        : "■ " + T.t("Not live")) + "\n"
                        + "👀 " + watching + " " + T.t("watching now") + "   ·   " + joined + " " + T.t("joined") + "   ·   " + T.t("peak") + " " + peak
                        + (names.length() > 0 ? "\n\n" + names : "")
                        + "\n(" + T.t("YouTube viewers count when they tap Watch; in-app viewers count while watching") + ")";
            } catch (Exception e) {
                txt = "⚠ " + e.getMessage();
            }
            final String t = txt;
            main.post(() -> monitor.setText(t));
        }).start();
    }
}
