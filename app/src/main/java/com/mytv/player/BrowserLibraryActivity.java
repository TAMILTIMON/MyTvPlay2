package com.mytv.player;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** History and bookmarks: site icon, title, site, time; grouped by day; search; edit / delete; open in a new tab. */
public class BrowserLibraryActivity extends AppCompatActivity {
    public static final String EXTRA_TAB = "tab", RESULT_URL = "url", RESULT_NEW_TAB = "new_tab";

    private int tab;
    private String query = "";
    private LinearLayout list;
    private final Button[] tabBtns = new Button[2];

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    @Override
    protected void onCreate(Bundle b) {
        Ui.apply(this);
        super.onCreate(b);
        tab = getIntent().getIntExtra(EXTRA_TAB, 0);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Ui.background());
        root.setPadding(dp(14), dp(14), dp(14), 0);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(UiKit.roundButton(this, "arrow-left", 42, false, v -> finish()));
        TextView t = Ui.text(this, 20, Ui.TEXT, true);
        t.setText("  " + T.t("History & bookmarks"));
        top.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        top.addView(UiKit.roundButton(this, "trash", 42, false, v -> clearMenu()));
        root.addView(top);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(0, dp(12), 0, dp(8));
        String[][] tn = {{"clock", "History"}, {"star", "Bookmarks"}};
        for (int i = 0; i < 2; i++) {
            final int k = i;
            Button bb = Ui.button(this, T.t(tn[i][1]), false);
            bb.setCompoundDrawablesWithIntrinsicBounds(new UiKit.Icon(this, tn[i][0], 18, Ui.TEXT), null, null, null);
            bb.setOnClickListener(v -> {
                tab = k;
                render();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1);
            lp.setMargins(dp(3), 0, dp(3), 0);
            tabs.addView(bb, lp);
            tabBtns[i] = bb;
        }
        root.addView(tabs);

        EditText q = new EditText(this);
        q.setHint(T.t("Search title or address"));
        q.setSingleLine(true);
        q.setTextColor(Ui.TEXT);
        q.setHintTextColor(Ui.DIM);
        q.setBackground(Ui.card(this, Ui.FIELD, Ui.STROKE, 18));
        q.setPadding(dp(14), dp(10), dp(14), dp(10));
        q.setCompoundDrawablesWithIntrinsicBounds(new UiKit.Icon(this, "search", 18, Ui.DIM), null, null, null);
        q.setCompoundDrawablePadding(dp(8));
        q.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence c, int a, int b2, int d) { }
            public void onTextChanged(CharSequence c, int a, int b2, int d) { }
            public void afterTextChanged(Editable e) {
                query = e.toString().trim().toLowerCase(Locale.ROOT);
                render();
            }
        });
        root.addView(q);

        ScrollView sv = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(8), 0, dp(30));
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        render();
    }

    private boolean matches(BrowserData.Entry e) {
        return query.isEmpty() || e.title.toLowerCase(Locale.ROOT).contains(query) || e.url.toLowerCase(Locale.ROOT).contains(query);
    }

    private static long dayStart(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private String dayName(long day) {
        long today = dayStart(System.currentTimeMillis());
        if (day == today) return T.t("Today");
        if (day == today - 86400000L) return T.t("Yesterday");
        return new SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(new Date(day));
    }

    private void render() {
        for (int i = 0; i < 2; i++) {
            tabBtns[i].setBackground(i == tab ? Ui.gradient(this, 18) : Ui.card(this, Ui.CARD, Ui.STROKE, 18));
            tabBtns[i].setTextColor(i == tab ? Color.WHITE : Ui.TEXT);
        }
        list.removeAllViews();
        if (tab == 0) {
            List<BrowserData.Entry> h = BrowserData.history();
            long lastDay = -1;
            int shown = 0;
            for (BrowserData.Entry e : h) {
                if (!matches(e)) continue;
                long d = dayStart(e.time);
                if (d != lastDay) {
                    lastDay = d;
                    TextView g = Ui.text(this, 12, Ui.DIM, true);
                    g.setText(dayName(d).toUpperCase(Locale.getDefault()));
                    g.setLetterSpacing(0.08f);
                    g.setPadding(dp(4), dp(14), 0, dp(6));
                    list.addView(g);
                }
                list.addView(row(e, new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(e.time)), false));
                if (++shown >= 600) break;
            }
            if (shown == 0) empty(T.t(query.isEmpty() ? "No history yet" : "Nothing found"));
        } else {
            List<BrowserData.Entry> bm = BrowserData.bookmarks();
            int shown = 0;
            Button add = Ui.button(this, "＋ " + T.t("Add a bookmark"), false);
            add.setOnClickListener(v -> edit(null));
            list.addView(add, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
            for (BrowserData.Entry e : bm) {
                if (!matches(e)) continue;
                list.addView(row(e, "", true));
                shown++;
            }
            if (shown == 0) empty(T.t(query.isEmpty() ? "No bookmarks yet — use ☆ in the browser menu" : "Nothing found"));
        }
    }

    private void empty(String s) {
        TextView t = Ui.text(this, 14, Ui.DIM, false);
        t.setText(s);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(40), 0, 0);
        list.addView(t);
    }

    /** Site icon (or a coloured letter), title, site address, time; tap = open, hold = menu. */
    private View row(final BrowserData.Entry e, String time, final boolean bookmark) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(10), dp(10), dp(8), dp(10));
        r.setBackground(Ui.focusCard(this, 14));
        ImageView ic = new ImageView(this);
        Bitmap fav = BrowserData.icon(this, e.url);
        String host = BrowserData.host(e.url);
        if (fav != null) {
            ic.setImageBitmap(fav);
            ic.setBackground(Ui.card(this, Color.WHITE, 0, 10));
            ic.setPadding(dp(5), dp(5), dp(5), dp(5));
        } else {
            PhoneMedia.M fake = new PhoneMedia.M();
            fake.title = host;
            ic.setImageDrawable(PhoneMedia.placeholder(this, fake, false));
        }
        ic.setClipToOutline(true);
        ic.setScaleType(ImageView.ScaleType.FIT_CENTER);
        r.addView(ic, new LinearLayout.LayoutParams(dp(38), dp(38)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), 0, dp(6), 0);
        TextView t = Ui.text(this, 15, Ui.TEXT, true);
        t.setText(e.title == null || e.title.isEmpty() ? host : e.title);
        t.setMaxLines(2);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView s = Ui.text(this, 12, Ui.DIM, false);
        s.setText(host + (time.isEmpty() ? "" : "  ·  " + time) + (e.visits > 1 ? "  ·  " + e.visits + "×" : ""));
        s.setSingleLine(true);
        col.addView(t);
        col.addView(s);
        r.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        r.addView(UiKit.roundButton(this, "ellipsis-vertical", 36, false, v -> menu(e, bookmark)));
        r.setOnClickListener(v -> open(e.url, false));
        r.setOnLongClickListener(v -> {
            menu(e, bookmark);
            return true;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(2), 0, dp(2));
        r.setLayoutParams(lp);
        return r;
    }

    private void open(String url, boolean newTab) {
        setResult(RESULT_OK, new Intent().putExtra(RESULT_URL, url).putExtra(RESULT_NEW_TAB, newTab));
        finish();
    }

    private void menu(final BrowserData.Entry e, final boolean bookmark) {
        boolean isBm = BrowserData.isBookmarked(e.url);
        List<String> items = new ArrayList<>();
        items.add("▶  " + T.t("Open"));
        items.add("⧉  " + T.t("Open in a new tab"));
        items.add("📋  " + T.t("Copy link"));
        if (bookmark) {
            items.add("✏  " + T.t("Edit"));
            items.add("🗑  " + T.t("Remove bookmark"));
        } else {
            items.add((isBm ? "☆  " + T.t("Remove bookmark") : "★  " + T.t("Add to bookmarks")));
            items.add("🗑  " + T.t("Delete from history"));
        }
        new UiDialog(this).setTitle(e.title).setItems(items.toArray(new String[0]), (d, w) -> {
            switch (w) {
                case 0: open(e.url, false); break;
                case 1: open(e.url, true); break;
                case 2: {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("link", e.url));
                    UiKit.makeToast(this, T.t("Copied"), android.widget.Toast.LENGTH_SHORT).show();
                    break;
                }
                case 3:
                    if (bookmark) edit(e);
                    else {
                        BrowserData.toggleBookmark(e.title, e.url);
                        render();
                    }
                    break;
                default:
                    if (bookmark) BrowserData.toggleBookmark(e.title, e.url);
                    else BrowserData.deleteHistory(java.util.Collections.singletonList(e.url));
                    render();
            }
        }).show();
    }

    private void edit(final BrowserData.Entry e) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), 0, dp(20), 0);
        final EditText t = new EditText(this), u = new EditText(this);
        t.setHint(T.t("Name"));
        u.setHint("https://");
        if (e != null) {
            t.setText(e.title);
            u.setText(e.url);
        }
        box.addView(t);
        box.addView(u);
        new UiDialog(this).setTitle(T.t(e == null ? "Add a bookmark" : "Edit")).setView(box)
                .setPositiveButton(T.t("Save"), (d, w) -> {
                    String url = u.getText().toString().trim();
                    if (url.isEmpty()) return;
                    if (!url.contains("://")) url = "https://" + url;
                    List<BrowserData.Entry> l = BrowserData.bookmarks();
                    BrowserData.Entry n = new BrowserData.Entry();
                    n.title = t.getText().toString().trim().isEmpty() ? BrowserData.host(url) : t.getText().toString().trim();
                    n.url = url;
                    n.time = System.currentTimeMillis();
                    int at = 0;
                    if (e != null) for (int i = 0; i < l.size(); i++) if (l.get(i).url.equals(e.url)) {
                        l.remove(i);
                        at = i;
                        break;
                    }
                    l.add(Math.min(at, l.size()), n);
                    BrowserData.saveBookmarks(l);
                    render();
                }).setNegativeButton(T.t("Cancel"), null).show();
    }

    private void clearMenu() {
        if (tab == 1) {
            UiKit.makeToast(this, T.t("Hold a bookmark to remove it"), android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        String[] o = {T.t("Last hour"), T.t("Today"), T.t("Last 7 days"), T.t("Everything")};
        new UiDialog(this).setTitle(T.t("Clear history")).setItems(o, (d, w) -> {
            long now = System.currentTimeMillis();
            if (w == 0) BrowserData.clearSince(now - 3600_000L);
            else if (w == 1) BrowserData.clearSince(dayStart(now));
            else if (w == 2) BrowserData.clearSince(now - 7 * 86400_000L);
            else BrowserData.clearHistory();
            render();
        }).show();
    }
}
