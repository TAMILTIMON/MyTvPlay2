package com.mytv.player;

import android.app.Activity;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

/** Colour palettes + custom colours for accents, background, cards and bars. */
public final class Palette {
    private Palette() {
    }

    public static final String[] NAMES = {"Purple Night (default)", "Ocean Blue", "Emerald", "Sunset Orange",
            "Ruby Red", "Royal Gold", "Pink Rose", "Mono Grey", "Teal & Amber", "Midnight Blue"};

    /** accent, accent2, accent3, dark bg, dark bg2, light bg, light bg2 */
    private static final int[][] P = {
            {0xFF8B5CF6, 0xFFEC4899, 0xFF3B82F6, 0xFF0B0913, 0xFF1C1236, 0xFFF5F3FB, 0xFFE7DFFC},
            {0xFF0EA5E9, 0xFF6366F1, 0xFF06B6D4, 0xFF06121F, 0xFF0B2540, 0xFFF0F7FF, 0xFFDCEBFF},
            {0xFF10B981, 0xFF14B8A6, 0xFF84CC16, 0xFF05140F, 0xFF0B2E22, 0xFFF0FDF6, 0xFFD8F5E6},
            {0xFFF97316, 0xFFEF4444, 0xFFEAB308, 0xFF140A05, 0xFF3A1608, 0xFFFFF7ED, 0xFFFFE4CC},
            {0xFFEF4444, 0xFFF43F5E, 0xFFF97316, 0xFF140507, 0xFF3B0A14, 0xFFFFF1F2, 0xFFFFE0E4},
            {0xFFEAB308, 0xFFF59E0B, 0xFF84CC16, 0xFF121005, 0xFF2E2608, 0xFFFEFCE8, 0xFFFBF0C0},
            {0xFFEC4899, 0xFFD946EF, 0xFFF43F5E, 0xFF14060F, 0xFF3A0F2B, 0xFFFDF2F8, 0xFFFBDDEE},
            {0xFF9CA3AF, 0xFF6B7280, 0xFFD1D5DB, 0xFF0A0A0A, 0xFF1F1F1F, 0xFFF5F5F5, 0xFFE5E5E5},
            {0xFF14B8A6, 0xFFF59E0B, 0xFF06B6D4, 0xFF041414, 0xFF0B2E2E, 0xFFF0FDFA, 0xFFD5F5EF},
            {0xFF3B82F6, 0xFF8B5CF6, 0xFF06B6D4, 0xFF050816, 0xFF0F172A, 0xFFEEF2FF, 0xFFDCE3FB},
    };

    static final int[] SWATCHES = {
            0xFF8B5CF6, 0xFF7C3AED, 0xFF6366F1, 0xFF3B82F6, 0xFF0EA5E9, 0xFF06B6D4,
            0xFF14B8A6, 0xFF10B981, 0xFF22C55E, 0xFF84CC16, 0xFFEAB308, 0xFFF59E0B,
            0xFFF97316, 0xFFEF4444, 0xFFF43F5E, 0xFFEC4899, 0xFFD946EF, 0xFF92400E,
            0xFF000000, 0xFF111111, 0xFF0F172A, 0xFF1C1236, 0xFF052E16, 0xFF3B0A14,
            0xFF1F2937, 0xFF374151, 0xFF64748B, 0xFF9CA3AF, 0xFFE5E7EB, 0xFFFFFFFF,
            0xFFFFF7ED, 0xFFFEF3C7, 0xFFDCFCE7, 0xFFDBEAFE, 0xFFF3E8FF, 0xFFFCE7F3};

    public static int index() {
        int i = Store.getInt("pal", 0);
        return i >= 0 && i < P.length ? i : 0;
    }

    static float lum(int c) {
        return (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f;
    }

    static int mix(int a, int b, float t) {
        return Color.rgb(Math.round(Color.red(a) * (1 - t) + Color.red(b) * t),
                Math.round(Color.green(a) * (1 - t) + Color.green(b) * t),
                Math.round(Color.blue(a) * (1 - t) + Color.blue(b) * t));
    }

    private static int custom(String k, int def) {
        int v = Store.getInt(k, 0);
        return v == 0 ? def : v;
    }

    public static boolean hasCustom() {
        return Store.getInt("c_bg", 0) != 0 || Store.getInt("c_card", 0) != 0 || Store.getInt("c_bar", 0) != 0;
    }

    /** Resolved colours: acc, acc2, acc3, bg, bg2, card(solid), bar, text, dim. */
    public static int[] resolve(boolean dark) {
        int[] p = P[index()];
        int acc = custom("c_acc", p[0]), acc2 = custom("c_acc2", p[1]), acc3 = p[2];
        int bg = dark ? p[3] : p[5], bg2 = dark ? p[4] : p[6];
        int cbg = Store.getInt("c_bg", 0);
        if (cbg != 0) {
            bg = cbg;
            bg2 = mix(cbg, acc, 0.18f);
        }
        boolean darkBg = lum(bg) < 0.5f;
        int card = custom("c_card", darkBg ? mix(bg, Color.WHITE, 0.07f) : Color.WHITE);
        int bar = custom("c_bar", darkBg ? mix(bg, acc, 0.14f) : Color.WHITE);
        int text = darkBg ? 0xFFF3F0FF : 0xFF1B1530, dim = darkBg ? 0xFFA59FBD : 0xFF6B6482;
        return new int[]{acc, acc2, acc3, bg, bg2, card, bar, text, dim};
    }

    public static String hex(int c) {
        return String.format("#%06X", c & 0xFFFFFF);
    }

    // ------------------------------------------------------------------ UI
    public static void show(final Activity a) {
        String[] items = {
                "🎨  " + T.t("Palette") + ": " + NAMES[index()],
                "●  " + T.t("Main colour (buttons, highlights)"),
                "●  " + T.t("Second colour (gradients)"),
                "▭  " + T.t("Background"),
                "▭  " + T.t("Cards"),
                "▬  " + T.t("Top / bottom bars"),
                "↺  " + T.t("Reset custom colours")};
        new AlertDialog.Builder(a).setTitle("🎨  " + T.t("Colours & style")).setItems(items, (d, w) -> {
            switch (w) {
                case 0: pickPalette(a); break;
                case 1: pickColor(a, "c_acc", T.t("Main colour (buttons, highlights)")); break;
                case 2: pickColor(a, "c_acc2", T.t("Second colour (gradients)")); break;
                case 3: pickColor(a, "c_bg", T.t("Background")); break;
                case 4: pickColor(a, "c_card", T.t("Cards")); break;
                case 5: pickColor(a, "c_bar", T.t("Top / bottom bars")); break;
                default:
                    for (String k : new String[]{"c_acc", "c_acc2", "c_bg", "c_card", "c_bar"}) Store.putInt(k, 0);
                    a.recreate();
            }
        }).show();
    }

    private static View dot(Activity a, int c1, int c2, int size) {
        View v = new View(a);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{c1, c2});
        g.setShape(GradientDrawable.OVAL);
        g.setStroke(Ui.dp(a, 2), 0x55FFFFFF);
        v.setBackground(g);
        v.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        return v;
    }

    private static void pickPalette(final Activity a) {
        ScrollView sv = new ScrollView(a);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(a, 16), Ui.dp(a, 8), Ui.dp(a, 16), Ui.dp(a, 8));
        sv.addView(box);
        final AlertDialog[] dlg = new AlertDialog[1];
        for (int i = 0; i < P.length; i++) {
            final int k = i;
            LinearLayout row = new LinearLayout(a);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(a, 12), Ui.dp(a, 10), Ui.dp(a, 12), Ui.dp(a, 10));
            row.setFocusable(true);
            row.setClickable(true);
            row.setBackground(Ui.focusCard(a, 14));
            int s = Ui.dp(a, 30);
            row.addView(dot(a, P[i][0], P[i][1], s));
            View bg = dot(a, Ui.dark() ? P[i][3] : P[i][5], Ui.dark() ? P[i][4] : P[i][6], s);
            ((LinearLayout.LayoutParams) bg.getLayoutParams()).setMargins(Ui.dp(a, 6), 0, 0, 0);
            row.addView(bg);
            TextView t = Ui.text(a, 16, Ui.TEXT, i == index());
            t.setText((i == index() ? "✓ " : "") + NAMES[i]);
            t.setPadding(Ui.dp(a, 14), 0, 0, 0);
            row.addView(t);
            row.setOnClickListener(v -> {
                Store.putInt("pal", k);
                for (String c : new String[]{"c_acc", "c_acc2"}) Store.putInt(c, 0);
                if (dlg[0] != null) dlg[0].dismiss();
                a.recreate();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, Ui.dp(a, 4), 0, Ui.dp(a, 4));
            box.addView(row, lp);
        }
        dlg[0] = new AlertDialog.Builder(a).setTitle("🎨  " + T.t("Palette")).setView(sv)
                .setNegativeButton(T.t("Close"), null).show();
    }

    /** Colour swatch grid (works with the TV remote and touch). */
    private static void pickColor(final Activity a, final String key, String title) {
        ScrollView sv = new ScrollView(a);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(a, 14), Ui.dp(a, 8), Ui.dp(a, 14), Ui.dp(a, 8));
        sv.addView(box);
        final AlertDialog[] dlg = new AlertDialog[1];
        int per = 6, cur = Store.getInt(key, 0);
        LinearLayout row = null;
        for (int i = 0; i < SWATCHES.length; i++) {
            if (i % per == 0) {
                row = new LinearLayout(a);
                row.setGravity(Gravity.CENTER);
                box.addView(row);
            }
            final int c = SWATCHES[i];
            View v = new View(a);
            GradientDrawable on = new GradientDrawable();
            on.setShape(GradientDrawable.OVAL);
            on.setColor(c);
            on.setStroke(Ui.dp(a, 4), Ui.ACCENT2);
            GradientDrawable off = new GradientDrawable();
            off.setShape(GradientDrawable.OVAL);
            off.setColor(c);
            off.setStroke(Ui.dp(a, c == cur ? 4 : 1), c == cur ? Ui.TEXT : 0x55888888);
            android.graphics.drawable.StateListDrawable sl = new android.graphics.drawable.StateListDrawable();
            sl.addState(new int[]{android.R.attr.state_focused}, on);
            sl.addState(new int[]{android.R.attr.state_pressed}, on);
            sl.addState(new int[]{}, off);
            v.setBackground(sl);
            v.setFocusable(true);
            v.setClickable(true);
            v.setOnClickListener(x -> {
                Store.putInt(key, c);
                if (dlg[0] != null) dlg[0].dismiss();
                a.recreate();
            });
            int s = Ui.dp(a, 40);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
            lp.setMargins(Ui.dp(a, 6), Ui.dp(a, 6), Ui.dp(a, 6), Ui.dp(a, 6));
            row.addView(v, lp);
        }
        dlg[0] = new AlertDialog.Builder(a).setTitle(title).setView(sv)
                .setNeutralButton(T.t("Default"), (d, w) -> {
                    Store.putInt(key, 0);
                    a.recreate();
                })
                .setNegativeButton(T.t("Close"), null).show();
    }
}
