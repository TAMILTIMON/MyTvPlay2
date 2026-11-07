package com.mytv.player;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * ZH Play's own popups (instead of Android's plain dialogs / toasts):
 * banner()  — slides in at the top, icon + coloured edge, hides by itself (success / error / info / push)
 * confirm() — dimmed background + rounded card with icon, title, message and two buttons
 * message() — the same card with one button (announcements)
 * Toggle    — a rounded on / off switch
 */
final class ZhPopup {
    private ZhPopup() {
    }

    static final int OK = 0, ERR = 1, INFO = 2, PUSH = 3;

    private static int dp(Activity a, float v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    private static GradientDrawable round(int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(r);
        return g;
    }

    private static FrameLayout root(Activity a) {
        return (FrameLayout) a.getWindow().getDecorView();
    }

    // ------------------------------------------------------------------ banner

    /** A message that slides in at the top and hides itself. Text starting with ⚠ / ✗ is shown as an error. */
    static void banner(Activity a, String text) {
        if (text == null) return;
        int type = text.startsWith("⚠") || text.startsWith("✗") ? ERR : INFO;
        if (text.startsWith("✓") || text.startsWith("✅") || text.startsWith("💾") || text.startsWith("📣") || text.startsWith("🔴")) type = OK;
        banner(a, null, text.replaceFirst("^[⚠✗✓✅]\\s*", ""), type, null);
    }

    static void banner(final Activity a, String title, String body, int type, final Runnable onTap) {
        if (a == null || a.isFinishing()) return;
        a.runOnUiThread(() -> {
            final FrameLayout root = root(a);
            int accent = type == ERR ? 0xFFEF4444 : type == OK ? 0xFF22C55E : type == PUSH ? Ui.ACCENT : 0xFF3B82F6;
            String icon = type == ERR ? "⚠" : type == OK ? "✓" : type == PUSH ? "🔔" : "ℹ";
            LinearLayout card = new LinearLayout(a);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(a, 14), dp(a, 12), dp(a, 16), dp(a, 12));
            GradientDrawable bg = round(Ui.dark() ? 0xF21A2133 : 0xF7FFFFFF, dp(a, 18));
            bg.setStroke(dp(a, 1), (accent & 0x00FFFFFF) | 0x66000000);
            card.setBackground(bg);
            card.setElevation(dp(a, 10));
            TextView ic = new TextView(a);
            ic.setText(icon);
            ic.setTextSize(16);
            ic.setTextColor(Color.WHITE);
            ic.setGravity(Gravity.CENTER);
            ic.setBackground(round(accent, dp(a, 14)));
            card.addView(ic, new LinearLayout.LayoutParams(dp(a, 32), dp(a, 32)));
            LinearLayout col = new LinearLayout(a);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(a, 12), 0, 0, 0);
            if (title != null && !title.isEmpty()) {
                TextView t = new TextView(a);
                t.setText(title);
                t.setTextSize(15);
                t.setTypeface(Typeface.DEFAULT_BOLD);
                t.setTextColor(Ui.TEXT);
                col.addView(t);
            }
            TextView b = new TextView(a);
            b.setText(body);
            b.setTextSize(14);
            b.setTextColor(title == null || title.isEmpty() ? Ui.TEXT : Ui.DIM);
            b.setMaxLines(4);
            col.addView(b);
            card.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.min(dp(a, 520), a.getResources().getDisplayMetrics().widthPixels - dp(a, 24)),
                    -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            lp.topMargin = dp(a, 36);
            card.setTranslationY(-dp(a, 140));
            card.setAlpha(0f);
            root.addView(card, lp);
            card.animate().translationY(0).alpha(1f).setDuration(260).start();
            final Runnable hide = () -> card.animate().translationY(-dp(a, 140)).alpha(0f).setDuration(220)
                    .withEndAction(() -> root.removeView(card)).start();
            card.setOnClickListener(v -> {
                hide.run();
                if (onTap != null) onTap.run();
            });
            card.postDelayed(hide, type == ERR ? 5500 : 3500);
        });
    }

    // ------------------------------------------------------------------ cards

    /** Dimmed screen + a rounded card. Returns the close action. */
    private static Runnable card(final Activity a, String icon, int iconColor, String title, String message,
                                 String okLabel, boolean danger, final Runnable onOk, String cancelLabel) {
        final FrameLayout root = root(a);
        final FrameLayout dim = new FrameLayout(a);
        dim.setBackgroundColor(0x99000000);
        dim.setClickable(true);
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(dp(a, 22), dp(a, 22), dp(a, 22), dp(a, 18));
        c.setBackground(round(Ui.dark() ? 0xFF161D2E : 0xFFFFFFFF, dp(a, 26)));
        c.setElevation(dp(a, 16));
        TextView ic = new TextView(a);
        ic.setText(icon);
        ic.setTextSize(26);
        ic.setGravity(Gravity.CENTER);
        ic.setBackground(round((iconColor & 0x00FFFFFF) | 0x33000000, dp(a, 30)));
        c.addView(ic, new LinearLayout.LayoutParams(dp(a, 60), dp(a, 60)));
        TextView t = new TextView(a);
        t.setText(title);
        t.setTextSize(19);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(Ui.TEXT);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(a, 14), 0, dp(a, 6));
        c.addView(t);
        if (message != null && !message.isEmpty()) {
            TextView m = new TextView(a);
            m.setText(message);
            m.setTextSize(15);
            m.setTextColor(Ui.DIM);
            m.setGravity(Gravity.CENTER);
            m.setLineSpacing(0, 1.15f);
            c.addView(m);
        }
        final Runnable close = () -> dim.animate().alpha(0f).setDuration(160).withEndAction(() -> root.removeView(dim)).start();
        LinearLayout btns = new LinearLayout(a);
        btns.setPadding(0, dp(a, 18), 0, 0);
        if (cancelLabel != null) {
            TextView cb = button(a, cancelLabel, false, false);
            cb.setOnClickListener(v -> close.run());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(a, 48), 1);
            lp.setMargins(0, 0, dp(a, 8), 0);
            btns.addView(cb, lp);
        }
        TextView ob = button(a, okLabel, true, danger);
        ob.setOnClickListener(v -> {
            close.run();
            if (onOk != null) onOk.run();
        });
        btns.addView(ob, new LinearLayout.LayoutParams(0, dp(a, 48), 1));
        c.addView(btns, new LinearLayout.LayoutParams(-1, -2));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.min(dp(a, 420), a.getResources().getDisplayMetrics().widthPixels - dp(a, 40)),
                -2, Gravity.CENTER);
        dim.addView(c, lp);
        dim.setAlpha(0f);
        c.setScaleX(0.92f);
        c.setScaleY(0.92f);
        root.addView(dim, new FrameLayout.LayoutParams(-1, -1));
        dim.animate().alpha(1f).setDuration(180).start();
        c.animate().scaleX(1f).scaleY(1f).setDuration(200).start();
        ob.requestFocus();
        return close;
    }

    static TextView button(Activity a, String label, boolean primary, boolean danger) {
        TextView b = new TextView(a);
        b.setText(label);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setTextColor(primary ? Color.WHITE : Ui.TEXT);
        b.setBackground(danger ? round(0xFFEF4444, dp(a, 24)) : primary ? Ui.gradient(a, 24) : Ui.focusCard(a, 24));
        b.setFocusable(true);
        b.setClickable(true);
        return b;
    }

    static void confirm(Activity a, String icon, String title, String message, String okLabel, boolean danger, Runnable onOk) {
        if (a == null || a.isFinishing()) return;
        card(a, icon, danger ? 0xFFEF4444 : Ui.ACCENT, title, message, okLabel, danger, onOk, T.t("Cancel"));
    }

    static void message(Activity a, String icon, String title, String message) {
        if (a == null || a.isFinishing()) return;
        card(a, icon, Ui.ACCENT, title, message, T.t("OK"), false, null, null);
    }

    // ------------------------------------------------------------------ toggle

    /** Rounded on / off switch. */
    static final class Toggle extends FrameLayout {
        private final View knob;
        private final GradientDrawable track;
        private boolean on;
        private Runnable changed;

        Toggle(Activity a, boolean initial) {
            super(a);
            track = round(0, dp(a, 14));
            setBackground(track);
            knob = new View(a);
            knob.setBackground(round(Color.WHITE, dp(a, 11)));
            knob.setElevation(dp(a, 2));
            LayoutParams lp = new LayoutParams(dp(a, 22), dp(a, 22), Gravity.CENTER_VERTICAL);
            lp.leftMargin = dp(a, 3);
            addView(knob, lp);
            setFocusable(true);
            setClickable(true);
            setOnClickListener(v -> set(!on, true));
            set(initial, false);
        }

        void onChange(Runnable r) {
            changed = r;
        }

        boolean on() {
            return on;
        }

        void set(boolean v, boolean anim) {
            on = v;
            track.setColor(v ? Ui.ACCENT : 0x55808080);
            float x = v ? getResources().getDisplayMetrics().density * 20 : 0;
            if (anim) knob.animate().translationX(x).setDuration(150).start();
            else knob.setTranslationX(x);
            if (anim && changed != null) changed.run();
        }
    }
}
