package com.mytv.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * Settings-style rows used by every menu (like Brave's settings): one rounded card per row, title + grey value
 * underneath, a switch on the right for on / off settings, red text for destructive actions, small headings.
 */
public final class UiRows {
    private UiRows() {
    }

    static final int RED = 0xFFF87171;

    /** toggle: -1 = no switch, 0 = off, 1 = on. */
    public static LinearLayout row(Context c, String icon, String title, String sub, int toggle, boolean danger, View.OnClickListener l) {
        LinearLayout r = new LinearLayout(c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(c, 16), Ui.dp(c, 12), Ui.dp(c, 14), Ui.dp(c, 12));
        r.setBackground(Ui.focusCard(c, 16));
        r.setMinimumHeight(Ui.dp(c, 56));
        if (icon != null) {
            android.widget.ImageView iv = new android.widget.ImageView(c);
            iv.setImageDrawable(new UiKit.Icon(c, icon, 20, danger ? RED : Ui.ACCENT));
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(Ui.dp(c, 22), Ui.dp(c, 22));
            ip.setMargins(0, 0, Ui.dp(c, 14), 0);
            r.addView(iv, ip);
        }
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = Ui.text(c, 15, danger ? RED : Ui.TEXT, false);
        t.setText(title);
        col.addView(t);
        if (sub != null && !sub.isEmpty()) {
            TextView s = Ui.text(c, 12, Ui.DIM, false);
            s.setText(sub);
            s.setPadding(0, Ui.dp(c, 2), 0, 0);
            col.addView(s);
        }
        r.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (toggle >= 0) {
            Switch sw = new Switch(c, toggle == 1);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(Ui.dp(c, 52), Ui.dp(c, 30));
            sp.setMargins(Ui.dp(c, 10), 0, 0, 0);
            r.addView(sw, sp);
        }
        if (l != null) {
            r.setClickable(true);
            r.setFocusable(true);
            r.setOnClickListener(l);
        }
        r.setContentDescription(title + (sub == null ? "" : ", " + sub));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, Ui.dp(c, 4), 0, Ui.dp(c, 4));
        r.setLayoutParams(lp);
        return r;
    }

    public static TextView header(Context c, String text) {
        TextView h = Ui.text(c, 12, Ui.DIM, true);
        h.setText(text.toUpperCase(Locale.getDefault()));
        h.setLetterSpacing(0.1f);
        h.setPadding(Ui.dp(c, 6), Ui.dp(c, 16), 0, Ui.dp(c, 4));
        return h;
    }

    /** A small on / off switch (drawn — the row itself is what you tap). */
    static final class Switch extends View {
        private final boolean on;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Switch(Context c, boolean on) {
            super(c);
            this.on = on;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), r = h / 2;
            RectF track = new RectF(1, 1, w - 1, h - 1);
            if (on) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(Ui.ACCENT);
                cv.drawRoundRect(track, r, r, p);
                p.setColor(0xFF10101A);
                cv.drawCircle(w - r, h / 2, r - Ui.dp(getContext(), 5), p);
            } else {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Ui.dp(getContext(), 2));
                p.setColor(Ui.DIM);
                cv.drawRoundRect(track, r, r, p);
                p.setStyle(Paint.Style.FILL);
                cv.drawCircle(r, h / 2, r - Ui.dp(getContext(), 8), p);
            }
        }
    }

    // ------------------------------------------------------------------ reading a menu line written the old way
    /** "🎨  Accent colour: Violet" → {icon, "Accent colour", "Violet", toggle, danger}; "§Name" = heading. */
    static Object[] parse(Context c, CharSequence item) {
        String raw = item == null ? "" : item.toString();
        if (raw.startsWith("§")) return new Object[]{null, raw.substring(1), null, -2, false};
        String icon = UiKit.iconFor(raw);
        String text = UiKit.noEmoji(raw).toString().replaceAll("^[ℹ↺⬆⬇⏹▶✎✏★☆•·\\s]+", "").trim();
        String title = text, sub = null;
        int k = text.lastIndexOf(": ");
        if (k > 0 && k < text.length() - 2 && text.length() - k < 48) {
            title = text.substring(0, k).trim();
            sub = text.substring(k + 2).trim();
        } else if (text.endsWith(":")) {
            title = text.substring(0, text.length() - 1);
        }
        int toggle = -1;
        if (sub != null) {
            String sv = sub.toLowerCase(Locale.ROOT);
            if (sub.equals(T.t("On")) || sv.equals("on") || sv.startsWith("on ")) toggle = 1;
            else if (sub.equals(T.t("Off")) || sv.equals("off") || sv.startsWith("off ")) toggle = 0;
        }
        String low = title.toLowerCase(Locale.ROOT);
        boolean danger = low.startsWith("delete") || low.startsWith("remove") || low.startsWith("reset") || low.startsWith("clear")
                || low.startsWith("forget") || low.startsWith("uninstall") || low.startsWith("end ")
                || "trash".equals(icon);
        return new Object[]{icon, title, sub, toggle, danger};
    }
}
