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
        TextView logo = Ui.text(this, 22, 0xFFFFFFFF, true);
        logo.setText("▶");
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(Ui.gradient(this, 14));
        top.addView(logo, new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46)));
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(Ui.dp(this, 12), 0, 0, 0);
        TextView h = Ui.text(this, 24, Ui.TEXT, true);
        h.setText("TamilCastPlay");
        TextView s = Ui.text(this, 13, Ui.DIM, false);
        s.setText(T.t("Remote · cast phone videos & songs"));
        t.addView(h);
        t.addView(s);
        top.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button theme = Ui.button(this, Ui.dark() ? "☀" : "🌙", false);
        theme.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Store.putStr("theme", Ui.dark() ? "light" : "dark");
                getIntent().putExtra("no_auto", true);
                recreate();
            }
        });
        top.addView(theme);
        root.addView(top);

        LinearLayout sr = new LinearLayout(this);
        sr.setGravity(Gravity.CENTER_VERTICAL);
        sr.setPadding(0, Ui.dp(this, 24), 0, Ui.dp(this, 10));
        TextView label = Ui.text(this, 13, Ui.DIM, true);
        label.setText(T.t("TVS ON YOUR WI-FI"));
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

        Button rescan = Ui.button(this, T.t("🔄  Scan again"), true);
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
        TextView tip = Ui.text(this, 13, Ui.DIM, false);
        tip.setText("\nPhone and TV must be on the same Wi-Fi, and TamilCastPlay must be installed on the TV "
                + T.t("(it runs in the background after the first start)."));
        root.addView(tip);
        setContentView(sv);
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
                                ? T.t("No TV found. Open TamilCastPlay once on the TV, check both are on the same Wi-Fi, then scan again.")
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
            TextView ic = Ui.text(this, 20, 0xFFFFFFFF, false);
            ic.setText(d.tv ? "📺" : "📱");
            ic.setGravity(Gravity.CENTER);
            ic.setBackground(Ui.gradient(this, 12));
            row.addView(ic, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
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
            TextView go = Ui.text(this, 22, Ui.ACCENT, true);
            go.setText("›");
            row.addView(go);
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
        startActivity(new Intent(this, NativeRemoteActivity.class)
                .putExtra(NativeRemoteActivity.EXTRA_IP, ip)
                .putExtra(NativeRemoteActivity.EXTRA_NAME, name));
    }

    private void manual() {
        final EditText e = new EditText(this);
        e.setHint("192.168.1.33");
        e.setInputType(InputType.TYPE_CLASS_PHONE);
        e.setText(Store.getStr("last_tv", ""));
        new AlertDialog.Builder(this).setTitle(T.t("TV address")).setView(e)
                .setPositiveButton(T.t("Connect"), (d, w) -> {
                    final String ip = e.getText().toString().trim().replace("http://", "").replaceAll(":.*", "");
                    if (ip.isEmpty()) return;
                    new Thread(() -> {
                        final Discovery.Device dev = Discovery.ping(ip, 2500);
                        main.post(() -> {
                            if (dev == null) Toast.makeText(this, "No TamilCastPlay at " + ip, Toast.LENGTH_LONG).show();
                            else open(dev.ip, dev.name);
                        });
                    }).start();
                }).setNegativeButton(T.t("Cancel"), null).show();
    }
}
