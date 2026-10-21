package com.mytv.player;

import android.app.Activity;
import android.app.UiModeManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

/** Theme colours and glass-style drawables shared by every screen. */
public final class Ui {
    private Ui() {
    }

    public static final int ACCENT = 0xFF8B5CF6, ACCENT2 = 0xFFEC4899, ACCENT3 = 0xFF3B82F6;
    public static int BG, BG2, CARD, STROKE, TEXT, DIM, FIELD;
    private static boolean dark = true;
    /** Focus "grow" effects only on TVs (D-pad). On phones they look like random zooming. */
    public static boolean tvMode = false;

    /** Back to the start screen: TV home on TVs / when used as a player, TV list in the phone remote. */
    public static void goHome(Activity a, boolean playerHome) {
        android.content.Intent i;
        if (isTv(a) || playerHome) {
            i = new android.content.Intent(a, MainActivity.class).putExtra(MainActivity.EXTRA_PLAYER_HOME, true);
        } else {
            i = new android.content.Intent(a, ConnectActivity.class).putExtra("no_auto", true);
        }
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
        a.startActivity(i);
        if (!(a instanceof MainActivity) && !(a instanceof ConnectActivity)) a.finish();
    }

    public static boolean isTv(Context c) {
        UiModeManager m = (UiModeManager) c.getSystemService(Context.UI_MODE_SERVICE);
        if (m != null && m.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) return true;
        PackageManager pm = c.getPackageManager();
        return pm.hasSystemFeature("android.software.leanback")
                || !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
    }

    /** "dark", "light" or "system". */
    public static boolean isDark(Context c) {
        String t = Store.getStr("theme", "dark");
        if ("light".equals(t)) return false;
        if ("system".equals(t)) {
            int n = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            return n != Configuration.UI_MODE_NIGHT_NO;
        }
        return true;
    }

    /** Call before super.onCreate() in every activity. */
    public static void apply(Activity a) {
        Store.init(a);
        dark = isDark(a);
        tvMode = isTv(a);
        a.setTheme(dark ? R.style.AppTheme : R.style.AppTheme_Light);
        if (dark) {
            BG = 0xFF0B0913; BG2 = 0xFF1C1236; CARD = 0x1FFFFFFF; STROKE = 0x29FFFFFF;
            TEXT = 0xFFF3F0FF; DIM = 0xFFA59FBD; FIELD = 0x26000000;
        } else {
            BG = 0xFFF5F3FB; BG2 = 0xFFE7DFFC; CARD = 0xE6FFFFFF; STROKE = 0x1F2A1B5C;
            TEXT = 0xFF1B1530; DIM = 0xFF6B6482; FIELD = 0x0D000000;
        }
    }

    public static boolean dark() {
        return dark;
    }

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static Drawable background() {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{BG2, BG, BG});
        return g;
    }

    public static GradientDrawable card(Context c, int fill, int stroke, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radius));
        if (stroke != 0) g.setStroke(dp(c, 1), stroke);
        return g;
    }

    public static GradientDrawable gradient(Context c, float radius) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{ACCENT, ACCENT2});
        g.setCornerRadius(dp(c, radius));
        return g;
    }

    /** Card that lights up (purple glow border) when focused or pressed on TV. */
    public static Drawable focusCard(Context c, float radius) {
        StateListDrawable s = new StateListDrawable();
        GradientDrawable on = card(c, dark ? 0x40A78BFA : 0x268B5CF6, 0, radius);
        on.setStroke(dp(c, 2), ACCENT);
        s.addState(new int[]{android.R.attr.state_focused}, on);
        s.addState(new int[]{android.R.attr.state_pressed}, on);
        s.addState(new int[]{android.R.attr.state_selected}, on);
        s.addState(new int[]{}, card(c, CARD, STROKE, radius));
        return s;
    }

    public static Drawable listSelector(Context c) {
        StateListDrawable s = new StateListDrawable();
        GradientDrawable on = card(c, dark ? 0x40A78BFA : 0x268B5CF6, 0, 16);
        on.setStroke(dp(c, 2), ACCENT);
        s.addState(new int[]{android.R.attr.state_focused}, on);
        s.addState(new int[]{android.R.attr.state_pressed}, on);
        s.addState(new int[]{android.R.attr.state_selected}, on);
        s.addState(new int[]{}, new GradientDrawable());
        return s;
    }

    public static TextView text(Context c, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** Rounded pill button; primary = purple gradient, otherwise glass. */
    public static Button button(Context c, String label, boolean primary) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTextColor(primary ? Color.WHITE : TEXT);
        b.setPadding(dp(c, 18), dp(c, 8), dp(c, 18), dp(c, 8));
        b.setMinHeight(dp(c, 44));
        b.setMinimumHeight(dp(c, 44));
        b.setGravity(Gravity.CENTER);
        StateListDrawable s = new StateListDrawable();
        GradientDrawable on = gradient(c, 24);
        on.setStroke(dp(c, 2), Color.WHITE);
        s.addState(new int[]{android.R.attr.state_focused}, on);
        s.addState(new int[]{android.R.attr.state_pressed}, on);
        s.addState(new int[]{}, primary ? gradient(c, 24) : card(c, CARD, STROKE, 24));
        b.setBackground(s);
        b.setFocusable(true);
        b.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean f) {
                ((Button) v).setTextColor(f || ((Button) v).isPressed() ? Color.WHITE
                        : (v.getTag() != null ? Color.WHITE : TEXT));
                if (tvMode) v.animate().scaleX(f ? 1.06f : 1f).scaleY(f ? 1.06f : 1f).setDuration(120).start();
            }
        });
        if (primary) b.setTag("primary");
        return b;
    }

    /** Round icon button (overlay controls, corner settings). Always light text: sits on video / glass. */
    public static Button iconButton(Context c, String label, float sizeDp, boolean primary) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(sizeDp * 0.36f);
        b.setTextColor(Color.WHITE);
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setGravity(Gravity.CENTER);
        StateListDrawable s = new StateListDrawable();
        GradientDrawable on = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{ACCENT, ACCENT2});
        on.setShape(GradientDrawable.OVAL);
        on.setStroke(dp(c, 2), Color.WHITE);
        GradientDrawable off;
        if (primary) {
            off = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{ACCENT, ACCENT3});
        } else {
            off = new GradientDrawable();
            off.setColor(0x33FFFFFF);
            off.setStroke(dp(c, 1), 0x33FFFFFF);
        }
        off.setShape(GradientDrawable.OVAL);
        s.addState(new int[]{android.R.attr.state_focused}, on);
        s.addState(new int[]{android.R.attr.state_pressed}, on);
        s.addState(new int[]{}, off);
        b.setBackground(s);
        b.setFocusable(true);
        b.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean f) {
                if (tvMode) v.animate().scaleX(f ? 1.15f : 1f).scaleY(f ? 1.15f : 1f).setDuration(120).start();
            }
        });
        return b;
    }
}
