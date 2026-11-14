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
        t.setText("📌  " + T.t("Home-screen icons"));
        t.setPadding(dp(12), 0, 0, 0);
        head.addView(t);
        outer.addView(head);

        // ---- the usual way: home-screen widgets
        LinearLayout how = new LinearLayout(this);
        how.setOrientation(LinearLayout.VERTICAL);
        how.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable hg = new GradientDrawable();
        hg.setColor(Ui.dark() ? 0xFF161D2E : 0xFFFFFFFF);
        hg.setCornerRadius(dp(16));
        hg.setStroke(dp(1), Ui.ACCENT);
        how.setBackground(hg);
        TextView ht = text(15, Ui.TEXT, true);
        ht.setText("🧩  " + T.t("Add as widgets"));
        TextView hs = text(13, Ui.DIM, false);
        hs.setText(T.t("1. Long-press an empty spot on your home screen") + "\n"
                + T.t("2. Tap Widgets") + "\n"
                + T.t("3. Find ZH Play — pick ZH Browser, ZH VPN, ZH Music, … (each is its own 1×1 icon)"));
        hs.setLineSpacing(0, 1.2f);
        hs.setPadding(0, dp(6), 0, 0);
        how.addView(ht);
        how.addView(hs);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.setMargins(dp(16), 0, dp(16), dp(6));
        outer.addView(how, hlp);

        TextView sub = text(13, Ui.DIM, false);
        sub.setText(T.t("Or switch a feature ON below: it becomes its own app in your phone's app list — its own icon, no ZH Play badge. Long-press it there and drag it to your home screen (some phones put it on the home screen by themselves)."));
        sub.setLineSpacing(0, 1.15f);
        sub.setPadding(dp(16), dp(10), dp(16), dp(6));
        outer.addView(sub);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), 0, dp(16), dp(32));

        for (final Shortcuts.Item it : Shortcuts.ITEMS) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(12), dp(14), dp(12));
            GradientDrawable g = new GradientDrawable();
            g.setColor(Ui.dark() ? 0xFF161D2E : 0xFFFFFFFF);
            g.setCornerRadius(dp(16));
            g.setStroke(dp(1), Ui.dark() ? 0x22FFFFFF : 0x14000000);
            row.setBackground(g);

            ImageView icon = new ImageView(this);
            icon.setImageDrawable(new BitmapDrawable(getResources(), Shortcuts.iconFor(this, it)));
            row.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(14), 0, dp(10), 0);
            TextView n = text(15, Ui.TEXT, true);
            n.setText(it.label);
            TextView s2 = text(12, Ui.DIM, false);
            s2.setText(T.t(it.sub));
            col.addView(n);
            col.addView(s2);
            row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));

            final ZhPopup.Toggle sw = new ZhPopup.Toggle(this, Shortcuts.appIconOn(this, it.id));
            sw.setContentDescription(it.label);
            sw.onChange(() -> {
                try {
                    Shortcuts.setAppIcon(this, it.id, sw.on());
                    UiKit.makeToast(this, sw.on() ? "✓ " + it.label + " " + T.t("is now in your app list")
                            : it.label + " " + T.t("removed from your app list"), android.widget.Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    UiKit.makeToast(this, "⚠ " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
                }
            });
            row.addView(sw, new LinearLayout.LayoutParams(dp(48), dp(28)));
            row.setOnClickListener(v -> sw.performClick());
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
            rlp.setMargins(0, dp(8), 0, 0);
            body.addView(row, rlp);
        }
        outer.addView(body);
        sv.addView(outer);
        setContentView(sv);
    }
}
