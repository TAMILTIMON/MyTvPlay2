package com.mytv.player;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.LinkedHashMap;
import java.util.Map;

/** Phone app home: finds TVs on the Wi-Fi automatically and connects with one tap. */
public class ConnectActivity extends AppCompatActivity {
    private LinearLayout list;
    private TextView status;
    private ProgressBar spinner;
    private final Map<String, Discovery.Device> found = new LinkedHashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Runnable stopNsd;
    private boolean autoOpened;
    private int scanId = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Ui.apply(this);
        super.onCreate(savedInstanceState);
        autoOpened = savedInstanceState != null || getIntent().getBooleanExtra("no_auto", false);
        int pad = Ui.dp(this, 18);
        ScrollView sv = new ScrollView(this);
        sv.setBackground(Ui.background());
        sv.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, Ui.dp(this, 36), pad, pad);
        sv.addView(root);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(UiKit.logoBadge(this, 50, false), new LinearLayout.LayoutParams(Ui.dp(this, 50), Ui.dp(this, 50)));
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(Ui.dp(this, 12), 0, 0, 0);
        TextView h = Ui.text(this, 24, Ui.TEXT, true);
        h.setText("ZH Play");
        TextView s = Ui.text(this, 13, Ui.DIM, false);
        s.setText(T.t("Remote · cast phone videos & songs"));
        t.addView(h);
        t.addView(s);
        top.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        top.addView(UiKit.roundButton(this, Ui.dark() ? "sun" : "moon", 44, false, v -> {
            Store.putStr("theme", Ui.dark() ? "light" : "dark");
            getIntent().putExtra("no_auto", true);
            recreate();
        }));
        root.addView(top);

        LinearLayout sr = new LinearLayout(this);
        sr.setGravity(Gravity.CENTER_VERTICAL);
        sr.setPadding(0, Ui.dp(this, 24), 0, Ui.dp(this, 10));
        TextView label = Ui.text(this, 13, Ui.DIM, true);
        label.setText(UiKit.noEmoji(T.t("TVS ON YOUR WI-FI")));
        label.setLetterSpacing(0.15f);
        sr.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        spinner = new ProgressBar(this);
        sr.addView(spinner, new LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22)));
        root.addView(sr);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);
        status = Ui.text(this, 14, Ui.DIM, false);
        status.setPadding(Ui.dp(this, 4), Ui.dp(this, 8), 0, Ui.dp(this, 16));
        root.addView(status);

        // ---- this phone's own board (works without a TV) — customizable like the remote's board
        LinearLayout oh = new LinearLayout(this);
        oh.setGravity(Gravity.CENTER_VERTICAL);
        TextView ol = Ui.text(this, 13, Ui.DIM, true);
        ol.setText(T.t("This phone").toUpperCase(java.util.Locale.ROOT));
        ol.setLetterSpacing(0.15f);
        ol.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 6));
        oh.addView(ol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView cz = Ui.text(this, 12, Ui.ACCENT, true);
        cz.setText("✎ " + T.t("Customize"));
        cz.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 6));
        cz.setOnClickListener(v -> customizePhoneBoard());
        oh.addView(cz);
        root.addView(oh);
        phoneTiles = new java.util.LinkedHashMap<>();
        phoneTiles.put("scan", UiKit.tile(this, "refresh-cw", T.t("Scan again"), T.t("find TVs on this Wi-Fi"), 0xFF8B5CF6, v -> startScan()));
        phoneTiles.put("address", UiKit.tile(this, "keyboard", T.t("Enter TV address"), T.t("type the TV's IP"), 0xFF3B82F6, v -> manual()));
        phoneTiles.put("group", UiKit.tile(this, "layers", T.t("TV group"), T.t("control several TVs at once"), 0xFF14B8A6, v -> TvGroup.manage(this, null)));
        phoneTiles.put("browser", UiKit.tile(this, "search", T.t("Browser"), T.t("search & send videos to TV"), 0xFF22C55E,
                v -> startActivity(new Intent(this, BrowserActivity.class))));
        phoneTiles.put("player", UiKit.tile(this, "smartphone", T.t("Use this phone"), T.t("as a player"), 0xFFF97316,
                v -> startActivity(new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_PLAYER_HOME, true))));
        phoneTiles.put("videos", UiKit.tile(this, "film", T.t("Videos"), T.t("on this phone"), 0xFF0EA5E9,
                v -> startActivity(new Intent(this, PhoneLibraryActivity.class))));
        phoneTiles.put("music", UiKit.tile(this, "list-music", T.t("Music"), T.t("on this phone"), 0xFFD946EF,
                v -> startActivity(new Intent(this, PhoneLibraryActivity.class).putExtra(PhoneLibraryActivity.EXTRA_AUDIO, true))));
        phoneTiles.put("features", UiKit.tile(this, "wand-sparkles", T.t("Features"), T.t("cutter, karaoke, converter…"), 0xFFF59E0B,
                v -> startActivity(new Intent(this, ToolsActivity.class))));
        phoneTiles.put("dj", UiKit.tile(this, "disc-3", T.t("DJ mixer"), T.t("two decks, sync, effects"), 0xFFEC4899,
                v -> startActivity(new Intent(this, DjActivity.class))));
        phoneTiles.put("editor", UiKit.tile(this, "audio-waveform", T.t("Song editor"), T.t("tracks, cut, effects"), 0xFF6366F1,
                v -> startActivity(new Intent(this, EditorActivity.class))));
        phoneTiles.put("board", UiKit.tile(this, "presentation", T.t("Whiteboard"), T.t("draw, shapes, rulers, formulas"), 0xFF10B981,
                v -> startActivity(new Intent(this, PresentActivity.class).putExtra(PresentActivity.EXTRA_BOARD, true))));
        phoneTiles.put("present", UiKit.tile(this, "presentation", T.t("Present"), T.t("PDF slides on the TV"), 0xFF8B5CF6, v -> {
            if (Store.getStr("last_tv", "").isEmpty()) UiKit.makeToast(this, T.t("Connect to a TV first"), android.widget.Toast.LENGTH_SHORT).show();
            else startActivity(new Intent(this, PresentActivity.class));
        }));
        phoneTiles.put("vpn", UiKit.tile(this, "lock", "VPN", T.t("WireGuard / Proton free"), 0xFF10B981, v -> WgVpn.panel(this)));
        phoneTiles.put("eq", UiKit.tile(this, "sliders-vertical", T.t("Equalizer"), T.t("for the music player"), 0xFF22D3EE,
                v -> startActivity(new Intent(this, PowerEqActivity.class))));
        phoneTiles.put("settings", UiKit.tile(this, "settings", T.t("Settings"), T.t("language, theme, …"), 0xFF64748B, v -> SettingsDialog.show(this)));
        phoneBoard = new android.widget.FrameLayout(this);
        phoneBoard.addView(BoardLayout.build(this, phoneTiles, "phone_board"));
        root.addView(phoneBoard);
        if (false) {
        Button rescan = Ui.button(this, T.t("Scan again"), true);
        rescan.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startScan();
            }
        });
        root.addView(rescan, full());
        Button manual = Ui.button(this, T.t("⌨  Enter TV address"), false);
        manual.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                manual();
            }
        });
        root.addView(manual, full());
        Button browse = Ui.button(this, "🌐  " + T.t("Browser · search & send videos to TV"), false);
        browse.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(ConnectActivity.this, BrowserActivity.class));
            }
        });
        root.addView(browse, full());
        Button self = Ui.button(this, T.t("📺  Use this phone as a player"), false);
        self.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(ConnectActivity.this, MainActivity.class)
                        .putExtra(MainActivity.EXTRA_PLAYER_HOME, true));
            }
        });
        root.addView(self, full());
        }
        TextView tip = Ui.text(this, 13, Ui.DIM, false);
        tip.setText("\nPhone and TV must be on the same Wi-Fi, and ZH Play must be installed on the TV "
                + T.t("(it runs in the background after the first start)."));
        root.addView(tip);
        setContentView(sv);
        UiKit.splash(this);
        RemoteService.start(this);
    }

    private LinearLayout.LayoutParams full() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        return lp;
    }

    @Override
    protected void onResume() {
        super.onResume();
        startScan();
    }

    @Override
    protected void onPause() {
        super.onPause();
        scanId++;
        if (stopNsd != null) stopNsd.run();
        stopNsd = null;
    }

    private void startScan() {
        final int id = ++scanId;
        found.clear();
        renderList();
        spinner.setVisibility(View.VISIBLE);
        status.setText(T.t("Looking for TVs…"));
        final String last = Store.getStr("last_tv", "");
        Discovery.Listener l = new Discovery.Listener() {
            public void onFound(final Discovery.Device d) {
                main.post(new Runnable() {
                    public void run() {
                        if (id != scanId || found.containsKey(d.ip)) return;
                        found.put(d.ip, d);
                        renderList();
                        if (!autoOpened && d.ip.equals(last)) {
                            autoOpened = true;
                            open(d.ip, d.name);
                        }
                    }
                });
            }

            public void onDone() {
                main.post(new Runnable() {
                    public void run() {
                        if (id != scanId) return;
                        spinner.setVisibility(View.GONE);
                        status.setText(found.isEmpty()
                                ? T.t("No TV found. Open ZH Play once on the TV, check both are on the same Wi-Fi, then scan again.")
                                : found.size() + (found.size() == 1 ? T.t(" device found. Tap to connect.") : T.t(" devices found. Tap one to connect.")));
                    }
                });
            }
        };
        if (stopNsd != null) stopNsd.run();
        stopNsd = Discovery.discover(this, l);
        Discovery.scan(l);
        if (!last.isEmpty() && !autoOpened) {
            // quick direct check of the last TV
            new Thread(new Runnable() {
                public void run() {
                    Discovery.Device d = Discovery.ping(Store.getStr("last_tv", ""), 1500);
                    if (d != null) l.onFound(d);
                }
            }).start();
        }
    }

    private void renderList() {
        list.removeAllViews();
        for (final Discovery.Device d : found.values()) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));
            row.setBackground(Ui.focusCard(this, 18));
            row.setFocusable(true);
            row.setClickable(true);
            android.widget.FrameLayout ic = new android.widget.FrameLayout(this);
            ic.setBackground(Ui.gradient(this, 14));
            ic.addView(UiKit.icon(this, d.tv ? "tv" : "smartphone", 22, 0xFFFFFFFF),
                    new android.widget.FrameLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48), Gravity.CENTER));
            row.addView(ic, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
            LinearLayout tx = new LinearLayout(this);
            tx.setOrientation(LinearLayout.VERTICAL);
            tx.setPadding(Ui.dp(this, 14), 0, 0, 0);
            TextView n = Ui.text(this, 17, Ui.TEXT, true);
            n.setText(d.name);
            TextView ip = Ui.text(this, 13, Ui.DIM, false);
            ip.setText(d.ip + (d.ip.equals(Store.getStr("last_tv", "")) ? T.t("  ·  last used") : ""));
            tx.addView(n);
            tx.addView(ip);
            row.addView(tx, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.addView(UiKit.icon(this, "chevron-right", 22, Ui.ACCENT));
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    open(d.ip, d.name);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 0, Ui.dp(this, 10));
            list.addView(row, lp);
        }
    }

    private void open(String ip, String name) {
        Store.putStr("last_tv", ip);
        PadWidget.refreshAll(this);            // the home-screen remote shows the new TV
        startActivity(new Intent(this, NativeRemoteActivity.class)
                .putExtra(NativeRemoteActivity.EXTRA_IP, ip)
                .putExtra(NativeRemoteActivity.EXTRA_NAME, name));
    }

    private java.util.LinkedHashMap<String, View> phoneTiles;
    private android.widget.FrameLayout phoneBoard;

    private void customizePhoneBoard() {
        java.util.LinkedHashMap<String, String> names = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, View> e : phoneTiles.entrySet()) {
            View t = e.getValue().findViewWithTag("title");
            names.put(e.getKey(), t instanceof TextView ? ((TextView) t).getText().toString() : e.getKey());
        }
        BoardLayout.customize(this, names, () -> {
            phoneBoard.removeAllViews();
            phoneBoard.addView(BoardLayout.build(this, phoneTiles, "phone_board"));
        }, "phone_board");
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        WgVpn.onResult(this, req, res, data);
    }

    private void manual() {
        final EditText e = new EditText(this);
        e.setHint("192.168.1.33");
        e.setInputType(InputType.TYPE_CLASS_PHONE);
        e.setText(Store.getStr("last_tv", ""));
        new UiDialog(this).setTitle(T.t("TV address")).setView(e)
                .setPositiveButton(T.t("Connect"), (d, w) -> {
                    final String ip = e.getText().toString().trim().replace("http://", "").replaceAll(":.*", "");
                    if (ip.isEmpty()) return;
                    new Thread(() -> {
                        final Discovery.Device dev = Discovery.ping(ip, 2500);
                        main.post(() -> {
                            if (dev == null) UiKit.makeToast(this, "No ZH Play at " + ip, Toast.LENGTH_LONG).show();
                            else open(dev.ip, dev.name);
                        });
                    }).start();
                }).setNegativeButton(T.t("Cancel"), null).show();
    }
}
