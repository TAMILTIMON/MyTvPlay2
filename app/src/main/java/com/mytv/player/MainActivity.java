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
    private final List<String> rows = new ArrayList<>();
    private final List<Runnable> actions = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private ListView listView;
    private String current = null;
    private int seen = -1;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (Store.version != seen) refresh();
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

        Button theme = Ui.button(this, Ui.dark() ? "☀ Light" : "🌙 Dark", false);
        theme.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Store.putStr("theme", Ui.dark() ? "light" : "dark");
                recreate();
            }
        });
        top.addView(theme);
        Button gear = Ui.button(this, "⚙ Settings", true);
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
                t.setBackground(Ui.card(MainActivity.this, Ui.CARD, Ui.STROKE, 16));
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
    }

    private void updateInfo() {
        if (info == null) return;
        StringBuilder sb = new StringBuilder();
        if (RemoteService.error != null) sb.append(RemoteService.error);
        else sb.append("📱  Control from your phone: install this same app on the phone (it finds this TV by itself), "
                + "or open  http://").append(Net.ip()).append(":").append(RemoteService.PORT).append("  in a browser.");
        if (Build.VERSION.SDK_INT >= 23 && Ui.isTv(this) && !Settings.canDrawOverlays(this)) {
            sb.append("\nTip: to let the phone start videos while this app is closed, run once on your PC:  "
                    + "adb shell appops set com.mytv.player SYSTEM_ALERT_WINDOW allow");
        }
        info.setText(sb.toString());
    }

    private void refresh() {
        seen = Store.version;
        rows.clear();
        actions.clear();
        if (current == null) {
            header.setText("My TV Player");
            rows.add("📁   Local files  ·  USB drive / storage");
            actions.add(() -> startActivity(new Intent(this, FileBrowserActivity.class)));
            if (!Ui.isTv(this)) {
                rows.add("📱   Remote control a TV");
                actions.add(() -> startActivity(new Intent(this, ConnectActivity.class)));
            }
            for (final String n : Store.names()) {
                rows.add((Store.RECENT.equals(n) ? "🕘   " : "🎵   ") + n + "   ·   " + Store.items(n).size());
                actions.add(() -> {
                    current = n;
                    refresh();
                });
            }
            if (Store.names().isEmpty()) {
                rows.add("No playlists yet — add videos, links, magnets or .torrent files from your phone.");
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
            for (int i = 0; i < items.size(); i++) {
                final int idx = i;
                rows.add((Formats.isTorrent(items.get(i).u) ? "🧲   " : "▶   ") + items.get(i).t);
                actions.add(() -> Remote.play(this, Store.items(current), idx));
            }
            if (items.isEmpty()) {
                rows.add("This playlist is empty.");
                actions.add(null);
            }
        }
        adapter.notifyDataSetChanged();
    }

    @Override
    protected void onResume() {
        super.onResume();
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
            refresh();
        } else {
            super.onBackPressed();
        }
    }
}
