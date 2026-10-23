package com.mytv.player;

import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

/** 📊 My TV: storage, memory, processor, screen, video formats, network + speed tests and cleaning. */
public class TvInfoActivity extends AppCompatActivity {
    private TextView report, line;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean resumed;
    private final Runnable tick = new Runnable() {
        public void run() {
            if (!resumed) return;
            refresh();
            main.postDelayed(this, 2500);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        Ui.apply(this);
        super.onCreate(b);
        boolean tv = Ui.isTv(this);
        int pad = Ui.dp(this, tv ? 40 : 14);
        ScrollView sv = new ScrollView(this);
        sv.setBackground(Ui.background());
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, Ui.dp(this, 24), pad, Ui.dp(this, 24));
        sv.addView(root);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        Button home = Ui.button(this, "🏠", false);
        home.setOnClickListener(v -> Ui.goHome(this, true));
        top.addView(home);
        TextView h = Ui.text(this, 24, Ui.TEXT, true);
        h.setText("  📊 " + T.t("My TV"));
        top.addView(h);
        root.addView(top);

        line = Ui.text(this, 15, Ui.ACCENT, true);
        line.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 4));
        root.addView(line);

        // action buttons (2 rows so they fit on phones too)
        LinearLayout r1 = new LinearLayout(this), r2 = new LinearLayout(this);
        add(r1, "🌐 " + T.t("Internet speed test"), true, v -> run(() -> TvInfo.speedTest(this::show)));
        add(r1, "🧹 " + T.t("Free up memory"), true, v -> run(() -> TvInfo.clean(this)));
        add(r2, "💾 " + T.t("Storage speed"), false, v -> run(() -> TvInfo.diskTest(this, this::show)));
        add(r2, "⚙ " + T.t("CPU & RAM speed"), false, v -> run(() -> TvInfo.benchmark(this::show)));
        add(r2, "📋 " + T.t("Running apps"), false, v -> runningApps());
        root.addView(r1);
        root.addView(r2);

        report = Ui.text(this, tv ? 17 : 14, Ui.TEXT, false);
        report.setTypeface(Typeface.MONOSPACE);
        report.setLineSpacing(0, 1.15f);
        report.setPadding(Ui.dp(this, 18), Ui.dp(this, 16), Ui.dp(this, 18), Ui.dp(this, 16));
        report.setBackground(Ui.card(this, Ui.SOLID, Ui.STROKE, 18));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, Ui.dp(this, 12), 0, 0);
        root.addView(report, lp);
        TextView note = Ui.text(this, 12, Ui.DIM, false);
        note.setPadding(0, Ui.dp(this, 10), 0, 0);
        note.setText(T.t("RAM speed and CPU speed are measured by a short test (Android does not report them). "
                + "“Free up memory” closes background apps; with the 🔌 ADB full remote it can stop any app."));
        root.addView(note);
        setContentView(sv);
        refresh();
        r1.getChildAt(0).requestFocus();
    }

    private void add(LinearLayout row, String label, boolean primary, View.OnClickListener l) {
        Button b = Ui.button(this, label, primary);
        b.setTextSize(15);
        b.setSingleLine(true);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1);
        lp.setMargins(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
        row.addView(b, lp);
    }

    private void show(final String s) {
        main.post(() -> line.setText(s));
    }

    private interface Job {
        String run();
    }

    private void run(final Job j) {
        if (TvInfo.busy()) {
            Toast.makeText(this, T.t("A test is already running"), Toast.LENGTH_SHORT).show();
            return;
        }
        line.setText("…");
        new Thread(() -> {
            final String r = j.run();
            main.post(() -> {
                line.setText(r);
                refresh();
            });
        }).start();
    }

    private void refresh() {
        new Thread(() -> {
            final String r = TvInfo.report(this);
            main.post(() -> report.setText(r));
        }).start();
    }

    private void runningApps() {
        if (!AdbClient.enabled()) {
            new AlertDialog.Builder(this).setTitle("📋 " + T.t("Running apps"))
                    .setMessage(T.t("Android only lets an app see other running apps through ADB. Turn on the 🔌 Full remote (ADB) first."))
                    .setPositiveButton("🔌 " + T.t("Full remote for every app (ADB)"), (d, w) -> SettingsDialog.adbSetup(this))
                    .setNegativeButton(T.t("Close"), null).show();
            return;
        }
        line.setText("…");
        new Thread(() -> {
            try {
                final List<TvInfo.Proc> l = TvInfo.running(this);
                main.post(() -> {
                    line.setText("");
                    final String[] names = new String[l.size()];
                    for (int i = 0; i < names.length; i++) {
                        String pkg = l.get(i).name.split(":")[0];
                        names[i] = TvInfo.gb(l.get(i).rss) + "   " + AppsHub.label(this, pkg)
                                + (l.get(i).name.contains(":") ? " (" + l.get(i).name.split(":")[1] + ")" : "");
                    }
                    new AlertDialog.Builder(this).setTitle("📋 " + T.t("Running apps") + " · " + T.t("tap to stop"))
                            .setItems(names, (d, w) -> run(() -> TvInfo.forceStop(this, l.get(w).name)))
                            .setNeutralButton("🧹 " + T.t("Free up memory"), (d, w) -> run(() -> TvInfo.clean(this)))
                            .setNegativeButton(T.t("Close"), null).show();
                });
            } catch (final Exception e) {
                main.post(() -> line.setText(e.getMessage()));
            }
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        main.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
    }
}
