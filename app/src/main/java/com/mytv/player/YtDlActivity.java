package com.mytv.player;

import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

/** Simple download manager UI: queue list, cancel, clear finished, open folder hint. */
public class YtDlActivity extends AppCompatActivity {
    private LinearLayout list;
    private TextView empty;
    private final Runnable refresh = this::paint;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        YtDlQueue.init(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF121218);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(this, 18, Color.WHITE, true);
        title.setText("⬇ " + T.t("Downloads"));
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button clear = small(T.t("Clear done"), v -> {
            YtDlQueue.clearFinished();
            paint();
        });
        top.addView(clear);
        root.addView(top);

        TextView path = Ui.text(this, 12, 0x99FFFFFF, false);
        path.setText(T.t("Folder") + ": Download/ZHPlay");
        path.setPadding(0, dp(6), 0, dp(8));
        root.addView(path);

        empty = Ui.text(this, 14, 0x88FFFFFF, false);
        empty.setText(T.t("No downloads yet. Long-press a link in the browser or use ☰ in the player."));
        empty.setPadding(dp(8), dp(24), dp(8), dp(8));
        root.addView(empty);

        ScrollView sv = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        YtDlQueue.addListener(refresh);
        paint();
    }

    @Override
    protected void onPause() {
        YtDlQueue.removeListener(refresh);
        super.onPause();
    }

    private void paint() {
        list.removeAllViews();
        List<YtDlQueue.Job> jobs = YtDlQueue.snapshot();
        empty.setVisibility(jobs.isEmpty() ? View.VISIBLE : View.GONE);
        for (YtDlQueue.Job j : jobs) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(10), dp(10), dp(10), dp(10));
            row.setBackground(Ui.card(this, 0xFF1E1E28, 0x33FFFFFF, 12));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(8);
            row.setLayoutParams(lp);

            TextView name = Ui.text(this, 14, Color.WHITE, true);
            name.setText((j.audio ? "🎵 " : "🎬 ") + j.title);
            name.setMaxLines(2);
            row.addView(name);

            TextView st = Ui.text(this, 12, statusColor(j.status), false);
            String line = statusLabel(j.status);
            if (j.message != null && !j.message.isEmpty()) line += " · " + j.message;
            st.setText(line);
            row.addView(st);

            if (j.status == YtDlQueue.Status.QUEUED || j.status == YtDlQueue.Status.RUNNING) {
                Button cancel = small(T.t("Cancel"), v -> YtDlQueue.cancel(j.id));
                row.addView(cancel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));
            }
            list.addView(row);
        }
    }

    private static int statusColor(YtDlQueue.Status s) {
        switch (s) {
            case RUNNING: return 0xFFFBBF24;
            case DONE: return 0xFF4ADE80;
            case FAILED: return 0xFFF87171;
            case CANCELLED: return 0xFF94A3B8;
            default: return 0xFF93C5FD;
        }
    }

    private String statusLabel(YtDlQueue.Status s) {
        switch (s) {
            case QUEUED: return T.t("Queued");
            case RUNNING: return T.t("Downloading…");
            case DONE: return T.t("Done");
            case FAILED: return T.t("Failed");
            case CANCELLED: return T.t("Cancelled");
            default: return "";
        }
    }

    private Button small(String t, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackground(Ui.card(this, 0xFF26262E, 0x44FFFFFF, 10));
        b.setOnClickListener(l);
        b.setMinHeight(dp(36));
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
