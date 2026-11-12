package com.mytv.player;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Every ZH Play feature that can live on your home screen as its own icon, one tap each. */
public class ShortcutsActivity extends Activity {
    static void open(Context c) {
        Intent i = new Intent(c, ShortcutsActivity.class);
        if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }

    private int dp(float v) {
        return Ui.dp(this, v);
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
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(14), dp(14), dp(14), dp(6));
        head.addView(UiKit.darkButton(this, "arrow-left", 42, false, v -> finish()), new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView t = text(21, Ui.TEXT, true);
        t.setText("📌  " + T.t("Add to home screen"));
        t.setPadding(dp(12), 0, 0, 0);
        head.addView(t);
        outer.addView(head);

        TextView sub = text(13, Ui.DIM, false);
        sub.setText(T.t("Each one becomes its own icon, separate from ZH Play itself — tap Add, then confirm on your home screen."));
        sub.setPadding(dp(16), 0, dp(16), dp(10));
        outer.addView(sub);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), 0, dp(16), dp(32));

        if (!Shortcuts.supported()) {
            TextView warn = text(14, Ui.DIM, false);
            warn.setText("⚠ " + T.t("This needs Android 8 or newer."));
            warn.setPadding(0, dp(20), 0, 0);
            body.addView(warn);
        }

        for (final Shortcuts.Item it : Shortcuts.all(this)) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(12), dp(14), dp(12));
            GradientDrawable g = new GradientDrawable();
            g.setColor(Ui.dark() ? 0xFF161D2E : 0xFFFFFFFF);
            g.setCornerRadius(dp(16));
            g.setStroke(dp(1), Ui.dark() ? 0x22FFFFFF : 0x14000000);
            row.setBackground(g);

            ImageView icon = new ImageView(this);
            Bitmap bmp = Shortcuts.icon(this, it.icon, it.color);
            icon.setImageDrawable(new BitmapDrawable(getResources(), bmp));
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(48), dp(48));
            row.addView(icon, ilp);

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(14), 0, dp(10), 0);
            TextView n = text(15, Ui.TEXT, true);
            n.setText(it.label);
            TextView s2 = text(12, Ui.DIM, false);
            s2.setText(it.sub);
            col.addView(n);
            col.addView(s2);
            row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));

            TextView add = text(13, Color.WHITE, true);
            add.setText("+ " + T.t("Add"));
            add.setGravity(Gravity.CENTER);
            add.setPadding(dp(14), 0, dp(14), 0);
            GradientDrawable ag = new GradientDrawable();
            ag.setColor(Ui.ACCENT);
            ag.setCornerRadius(dp(16));
            add.setBackground(ag);
            add.setOnClickListener(v -> Shortcuts.pin(this, it));
            row.addView(add, new LinearLayout.LayoutParams(-2, dp(36)));

            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
            rlp.setMargins(0, dp(8), 0, 0);
            body.addView(row, rlp);
        }
        outer.addView(body);
        sv.addView(outer);
        setContentView(sv);
    }
}
