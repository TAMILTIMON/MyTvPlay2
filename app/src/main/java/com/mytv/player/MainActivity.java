package com.mytv.player;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

/** Home screen: on a TV it is the player home; on a phone it opens the remote app first. */
public class MainActivity extends AppCompatActivity {
    public static final String EXTRA_PLAYER_HOME = "player_home";

    private TextView header, info;
    private LinearLayout appsRow, watchRow;
    private TextView watchLabel;
    private android.widget.HorizontalScrollView watchScroll;
    private int watchSeen = -1;

    private void buildWatch() {
        if (watchRow == null) return;
        watchSeen = Store.version;
        watchRow.removeAllViews();
        List<Store.Watch> h = Store.history();
        int vis = h.isEmpty() ? View.GONE : View.VISIBLE;
        watchLabel.setVisibility(vis);
        watchScroll.setVisibility(vis);
        boolean tv = Ui.isTv(this);
        int w = Ui.dp(this, tv ? 208 : 156), ht = w * 9 / 16;
        if (h.size() > 12) h = h.subList(0, 12);
        for (final Store.Watch x : h) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
            card.setBackground(Ui.focusCard(this, 18));
            card.setFocusable(true);
            card.setClickable(true);
            if (tv) card.setOnFocusChangeListener((v, f) -> v.animate().scaleX(f ? 1.06f : 1f).scaleY(f ? 1.06f : 1f).setDuration(120).start());
            android.widget.FrameLayout pic = new android.widget.FrameLayout(this);
            java.io.File tf = Thumbs.file(this, x.u);
            if (tf.exists()) {
                android.widget.ImageView iv = new android.widget.ImageView(this);
                iv.setImageURI(android.net.Uri.fromFile(tf));
                iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
                pic.addView(iv, new android.widget.FrameLayout.LayoutParams(w, ht));
            } else {
                TextView ph = Ui.text(this, 30, 0xFFFFFFFF, true);
                ph.setText("▶");
                ph.setGravity(Gravity.CENTER);
                ph.setBackground(Ui.gradient(this, 12));
                pic.addView(ph, new android.widget.FrameLayout.LayoutParams(w, ht));
            }
            View bar = new View(this);
            bar.setBackground(Ui.gradient(this, 2));
            int pw = x.len > 0 ? (int) (w * Math.min(1f, (float) x.pos / x.len)) : 0;
            pic.addView(bar, new android.widget.FrameLayout.LayoutParams(Math.max(1, pw), Ui.dp(this, 4), Gravity.BOTTOM));
            card.addView(pic, new LinearLayout.LayoutParams(w, ht));
            TextView n = Ui.text(this, 14, Ui.TEXT, true);
            n.setText(x.t);
            n.setSingleLine(true);
            n.setEllipsize(android.text.TextUtils.TruncateAt.END);
            n.setPadding(0, Ui.dp(this, 6), 0, 0);
            card.addView(n, new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView left = Ui.text(this, 12, Ui.DIM, false);
            long mins = Math.max(1, (x.len - x.pos) / 60000);
            left.setText(mins + " " + T.t("min left"));
            card.addView(left);
            card.setOnClickListener(v -> {
                List<Store.Item> one = new ArrayList<>();
                one.add(new Store.Item(x.t, x.u, ""));
                Remote.play(this, one, 0);
            });
            card.setOnLongClickListener(v -> {
                new androidx.appcompat.app.AlertDialog.Builder(this).setTitle(x.t)
                        .setItems(new String[]{T.t("Remove from Continue watching")}, (d, wh) -> {
                            Store.removeHistory(x.u);
                            buildWatch();
                        }).show();
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, Ui.dp(this, 12), 0);
            watchRow.addView(card, lp);
        }
    }
    private final List<String> rows = new ArrayList<>();
    private final List<Runnable> actions = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private ListView listView;
    private String current = null;
    private String filter = "";

    private interface OnDelete {
        void run(boolean[] checked);
    }

    /** Multi-select dialog with a "Select all" button that doesn't close the dialog. */
    private void multiSelect(String title, final String[] names, final OnDelete onDelete) {
        final boolean[] checked = new boolean[names.length];
        final androidx.appcompat.app.AlertDialog d = new androidx.appcompat.app.AlertDialog.Builder(this).setTitle(title)
                .setMultiChoiceItems(names, checked, (dd, w, on) -> checked[w] = on)
                .setNeutralButton(T.t("Select all"), null)
                .setPositiveButton(T.t("Delete"), null)
                .setNegativeButton(T.t("Cancel"), null).create();
        d.setOnShowListener(x -> {
            d.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener(v -> {
                boolean all = true;
                for (boolean c : checked) all &= c;
                for (int i = 0; i < checked.length; i++) {
                    checked[i] = !all;
                    d.getListView().setItemChecked(i, !all);
                }
                ((android.widget.Button) v).setText(!all ? T.t("Select none")
                        : T.t("Select all"));
            });
            d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
                int n = 0;
                for (boolean c : checked) if (c) n++;
                if (n == 0) {
                    android.widget.Toast.makeText(this,T.t("Nothing selected"),
                            android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                final int count = n;
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(T.t("Delete ") + count + T.t("?"))
                        .setMessage(T.t("This cannot be undone."))
                        .setPositiveButton(T.t("Delete"), (a, b) -> {
                            d.dismiss();
                            onDelete.run(checked);
                        })
                        .setNegativeButton(T.t("Cancel"), null).show();
            });
        });
        d.show();
    }

    private void deletePlaylists() {
        final List<String> names = Store.names();
        String[] labels = new String[names.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = names.get(i) + "   ·   " + Store.count(names.get(i));
        multiSelect(T.t("Delete playlists"), labels, checked -> {
            List<String> del = new ArrayList<>();
            for (int i = 0; i < checked.length; i++) if (checked[i]) del.add(names.get(i));
            int n = Store.delPlaylists(del);
            android.widget.Toast.makeText(this, n + T.t(" deleted"), android.widget.Toast.LENGTH_SHORT).show();
            refresh();
        });
    }

    private void deleteItems() {
        final String pl = current;
        final List<Store.Item> items = Store.items(pl);
        final List<Integer> idx = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        String fl = filter.toLowerCase(java.util.Locale.ROOT);
        for (int i = 0; i < items.size(); i++) {
            if (!fl.isEmpty() && !items.get(i).t.toLowerCase(java.util.Locale.ROOT).contains(fl)) continue;
            idx.add(i);
            labels.add(items.get(i).t);
        }
        multiSelect(T.t("Select to delete · ") + idx.size(), labels.toArray(new String[0]), checked -> {
            java.util.Set<Integer> del = new java.util.HashSet<>();
            for (int i = 0; i < checked.length; i++) if (checked[i]) del.add(idx.get(i));
            int n = Store.delMany(pl, del);
            filter = "";
            android.widget.Toast.makeText(this, n + T.t(" deleted"), android.widget.Toast.LENGTH_SHORT).show();
            refresh();
        });
    }

    private void askFilter() {
        if (!filter.isEmpty()) {
            filter = "";
            refresh();
            return;
        }
        final android.widget.EditText e = new android.widget.EditText(this);
        e.setSingleLine(true);
        e.setHint(T.t("Channel / video name"));
        new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("🔍").setView(e)
                .setPositiveButton(T.t("Search"), (d, w) -> {
                    filter = e.getText().toString().trim();
                    refresh();
                }).show();
    }
    private int seen = -1;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (Store.version != seen) {
                refresh();
                if (watchSeen != Store.version) buildWatch();
            }
            updateInfo();
            handler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Ui.apply(this);
        super.onCreate(savedInstanceState);
        RemoteService.start(this);
        if (!Ui.isTv(this) && !getIntent().getBooleanExtra(EXTRA_PLAYER_HOME, false) && savedInstanceState == null) {
            startActivity(new Intent(this, ConnectActivity.class));
            finish();
            return;
        }
        int pad = Ui.dp(this, Ui.isTv(this) ? 48 : 16);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Ui.background());
        root.setPadding(pad, Ui.dp(this, 28), pad, Ui.dp(this, 16));

        // top bar: logo · title … theme · settings (corner)
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView logo = Ui.text(this, 22, 0xFFFFFFFF, true);
        logo.setText("▶");
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(Ui.gradient(this, 14));
        top.addView(logo, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(Ui.dp(this, 14), 0, 0, 0);
        header = Ui.text(this, 26, Ui.TEXT, true);
        TextView sub = Ui.text(this, 13, Ui.DIM, false);
        sub.setText(Discovery.deviceName(this));
        titles.addView(header);
        titles.addView(sub);
        top.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        boolean narrow = getResources().getConfiguration().screenWidthDp < 600;
        Button theme = Ui.button(this, narrow ? (Ui.dark() ? "☀" : "🌙") : Ui.dark() ? T.t("☀ Light") : T.t("🌙 Dark"), false);
        theme.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Store.putStr("theme", Ui.dark() ? "light" : "dark");
                recreate();
            }
        });
        top.addView(theme);
        Button gear = Ui.button(this, narrow ? "⚙" : T.t("⚙ Settings"), true);
        gear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                SettingsDialog.show(MainActivity.this);
            }
        });
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.setMargins(Ui.dp(this, 10), 0, 0, 0);
        top.addView(gear, glp);
        root.addView(top);

        info = Ui.text(this, 14, Ui.DIM, false);
        info.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12));
        info.setBackground(Ui.card(this, Ui.CARD, Ui.STROKE, 14));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.setMargins(0, Ui.dp(this, 18), 0, Ui.dp(this, 14));
        root.addView(info, ilp);

        // ---- Continue watching
        watchLabel = Ui.text(this, 12, Ui.DIM, true);
        watchLabel.setText(T.t("CONTINUE WATCHING"));
        watchLabel.setLetterSpacing(0.18f);
        watchLabel.setPadding(Ui.dp(this, 4), 0, 0, Ui.dp(this, 8));
        root.addView(watchLabel);
        watchScroll = new android.widget.HorizontalScrollView(this);
        watchScroll.setHorizontalScrollBarEnabled(false);
        watchScroll.setPadding(Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6));
        watchScroll.setClipToPadding(false);
        watchRow = new LinearLayout(this);
        watchScroll.addView(watchRow);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.setMargins(0, 0, 0, Ui.dp(this, 12));
        root.addView(watchScroll, wlp);

        // ---- Apps hub: Netflix, JioHotstar, Spotify, SonyLIV … in one place
        TextView appsLabel = Ui.text(this, 12, Ui.DIM, true);
        appsLabel.setText(T.t("APPS"));
        appsLabel.setLetterSpacing(0.18f);
        appsLabel.setPadding(Ui.dp(this, 4), 0, 0, Ui.dp(this, 8));
        root.addView(appsLabel);
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        hs.setPadding(Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6));
        appsRow = new LinearLayout(this);
        hs.addView(appsRow);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.setMargins(0, 0, 0, Ui.dp(this, 14));
        root.addView(hs, alp);

        listView = new ListView(this);
        listView.setDivider(null);
        listView.setSelector(Ui.listSelector(this));
        listView.setDrawSelectorOnTop(true);
        listView.setClipToPadding(false);
        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, rows) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView t = (TextView) super.getView(position, convertView, parent);
                t.setTextSize(Ui.isTv(MainActivity.this) ? 21 : 17);
                t.setTextColor(Ui.TEXT);
                t.setPadding(Ui.dp(MainActivity.this, 20), Ui.dp(MainActivity.this, 16),
                        Ui.dp(MainActivity.this, 20), Ui.dp(MainActivity.this, 16));
                if (convertView == null) t.setBackground(Ui.card(MainActivity.this, Ui.CARD, Ui.STROKE, 16));
                return t;
            }
        };
        listView.setAdapter(adapter);
        listView.setDividerHeight(Ui.dp(this, 8));
        listView.setDivider(new android.graphics.drawable.ColorDrawable(0x00000000));
        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < actions.size() && actions.get(position) != null) actions.get(position).run();
        });
        root.addView(listView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        listView.requestFocus();
        refresh();
        updateInfo();
        Perms.firstRun(this);
        final String crash = Store.getStr("lastcrash", "");
        if (!crash.isEmpty()) {
            Store.putStr("lastcrash", "");
            new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("⚠ The app closed unexpectedly last time")
                    .setMessage("Please send this to the developer:\n\n" + crash.substring(0, Math.min(900, crash.length())))
                    .setPositiveButton("OK", null)
                    .setNeutralButton("Copy", (d, w) -> {
                        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", crash));
                    }).show();
        }
    }

    private void buildApps() {
        if (appsRow == null) return;
        appsRow.removeAllViews();
        boolean tv = Ui.isTv(this);
        for (final AppsHub.App a : AppsHub.favorites(this)) {
            addTile(a.name, AppsHub.icon(this, a.pkg), tv, v -> {
                String m = AppsHub.launch(this, a.pkg);
                if (!a.installed) android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_LONG).show();
            });
        }
        addTile("＋ Apps", null, tv, v -> editApps());
    }

    private void addTile(String name, android.graphics.drawable.Drawable icon, boolean tv, View.OnClickListener l) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 8));
        t.setBackground(Ui.focusCard(this, 18));
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnClickListener(l);
        if (tv) t.setOnFocusChangeListener((v, f) -> v.animate().scaleX(f ? 1.08f : 1f).scaleY(f ? 1.08f : 1f).setDuration(120).start());
        int iw = Ui.dp(this, tv ? 128 : 56), ih = Ui.dp(this, tv ? 72 : 56);
        if (icon != null) {
            android.widget.ImageView iv = new android.widget.ImageView(this);
            iv.setImageDrawable(icon);
            iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            t.addView(iv, new LinearLayout.LayoutParams(iw, ih));
        } else {
            TextView plus = Ui.text(this, 28, 0xFFFFFFFF, true);
            plus.setText("＋");
            plus.setGravity(Gravity.CENTER);
            plus.setBackground(Ui.gradient(this, 14));
            t.addView(plus, new LinearLayout.LayoutParams(iw, ih));
            name = T.t("Add apps");
        }
        TextView n = Ui.text(this, 13, Ui.TEXT, false);
        n.setText(name);
        n.setSingleLine(true);
        n.setGravity(Gravity.CENTER);
        n.setPadding(0, Ui.dp(this, 6), 0, 0);
        t.addView(n, new LinearLayout.LayoutParams(iw, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, Ui.dp(this, 12), 0);
        appsRow.addView(t, lp);
    }

    private void editApps() {
        final List<AppsHub.App> all = AppsHub.choices(this);
        final List<String> chosen = new ArrayList<>();
        for (AppsHub.App f : AppsHub.favorites(this)) chosen.add(f.pkg);
        String[] names = new String[all.size()];
        boolean[] checked = new boolean[all.size()];
        for (int i = 0; i < all.size(); i++) {
            AppsHub.App a = all.get(i);
            names[i] = a.name + (a.installed ? "" : T.t("   (not installed – tap tile to get it)"));
            checked[i] = chosen.contains(a.pkg);
        }
        new androidx.appcompat.app.AlertDialog.Builder(this).setTitle(T.t("Apps on the home screen"))
                .setMultiChoiceItems(names, checked, (d, w, on) -> {
                    String p = all.get(w).pkg;
                    if (on && !chosen.contains(p)) chosen.add(p);
                    if (!on) chosen.remove(p);
                })
                .setPositiveButton(T.t("Save"), (d, w) -> {
                    AppsHub.saveFavorites(chosen);
                    buildApps();
                })
                .setNegativeButton(T.t("Cancel"), null).show();
    }

    private void updateInfo() {
        if (info == null) return;
        StringBuilder sb = new StringBuilder();
        if (Pair.enabled()) sb.append("🔐  PIN ").append(Pair.pin()).append("   ·   ");
        if (RemoteService.error != null) sb.append(RemoteService.error);
        else sb.append("📱  Control from your phone: install this same app on the phone (it finds this TV by itself), "
                + "or open  http://").append(Net.ip()).append(":").append(RemoteService.PORT).append("  in a browser.");

        info.setText(sb.toString());
    }

    private void refresh() {
        seen = Store.version;
        rows.clear();
        actions.clear();
        if (current == null) {
            header.setText("TamilCastPlay");
            if (Ui.isTv(this) && !Perms.overlayOk(this)) {
                rows.add(T.t("🔓   Allow the phone to start videos when this app is closed  (recommended)"));
                actions.add(() -> Perms.openOverlaySettings(this));
            }
            rows.add("🌐   " + T.t("Browser · search the web & play links"));
            actions.add(() -> startActivity(new Intent(this, BrowserActivity.class)));
            if (Ui.isTv(this) && !Perms.accessOk(this)) {
                rows.add("🎮   " + T.t("Control the whole TV from the phone") + "  (" + T.t("recommended") + ")");
                actions.add(() -> Perms.openAccessSettings(this));
            }
            rows.add(T.t("📁   Local files  ·  USB drive / storage"));
            actions.add(() -> startActivity(new Intent(this, FileBrowserActivity.class)));
            if (!Ui.isTv(this)) {
                rows.add(T.t("📱   Remote control a TV"));
                actions.add(() -> startActivity(new Intent(this, ConnectActivity.class)));
            }
            for (final String n : Store.names()) {
                rows.add((Store.RECENT.equals(n) ? "🕘   " : "🎵   ") + n + "   ·   " + Store.count(n));
                actions.add(() -> {
                    current = n;
                    refresh();
                });
            }
            if (!Store.names().isEmpty()) {
                rows.add("🗑   " + T.t("Select & delete playlists…"));
                actions.add(this::deletePlaylists);
            }
            if (Store.names().isEmpty()) {
                rows.add(T.t("No playlists yet — add videos, links, magnets or .torrent files from your phone."));
                actions.add(null);
            }
        } else {
            if (!Store.names().contains(current)) {
                current = null;
                refresh();
                return;
            }
            header.setText(current);
            final List<Store.Item> items = Store.items(current);
            if (!items.isEmpty()) {
                rows.add("🔍   " + T.t("Search channel / name") + (filter.isEmpty() ? "" : "  “" + filter + "”  ✕") + "   ·   " + items.size());
                actions.add(this::askFilter);
                rows.add("🗑   " + T.t("Select & delete…") + (filter.isEmpty() ? "" : "  (" + T.t("search results") + ")"));
                actions.add(this::deleteItems);
            }
            String fl = filter.toLowerCase(java.util.Locale.ROOT);
            for (int i = 0; i < items.size(); i++) {
                if (!fl.isEmpty() && !items.get(i).t.toLowerCase(java.util.Locale.ROOT).contains(fl)) continue;
                final int idx = i;
                rows.add((Formats.isTorrent(items.get(i).u) ? "🧲   " : "▶   ") + items.get(i).t);
                actions.add(() -> Remote.play(this, Store.items(current), idx));
            }
            if (items.isEmpty()) {
                rows.add(T.t("This playlist is empty."));
                actions.add(null);
            }
        }
        adapter.notifyDataSetChanged();
    }

    @Override
    protected void onResume() {
        super.onResume();
        buildApps();
        buildWatch();
        refresh();
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    @Override
    public void onBackPressed() {
        if (current != null) {
            current = null;
            filter = "";
            refresh();
        } else {
            super.onBackPressed();
        }
    }
}
