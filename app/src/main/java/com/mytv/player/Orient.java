package com.mytv.player;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.widget.Button;
import android.widget.LinearLayout;

/** Screen-turning choice for the DJ mixer and the song editor: Auto ↔ Portrait ↔ Landscape (remembered per screen). */
final class Orient {
    private Orient() {
    }

    static final String[] NAMES = {"Auto-rotate", "Portrait", "Landscape"};

    static void apply(Activity a, String key) {
        int m = Store.getInt(key, 0);
        a.setRequestedOrientation(m == 1 ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                : m == 2 ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_FULL_USER);
    }

    static boolean landscape(Activity a) {
        return a.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    /** A small button that cycles the choice (the icon shows the current one). */
    static Button button(final Activity a, final String key) {
        final Button b = new Button(a);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setTextColor(Color.WHITE);
        b.setBackground(Ui.card(a, 0xFF26262E, 0, 14));
        b.setPadding(Ui.dp(a, 10), 0, Ui.dp(a, 10), 0);
        final Runnable paint = () -> {
            int m = Store.getInt(key, 0);
            b.setText((m == 0 ? "⟳ " : "🔒 ") + T.t(NAMES[m]));
            b.setContentDescription(T.t("Screen rotation") + ": " + T.t(NAMES[m]));
        };
        paint.run();
        b.setOnClickListener(v -> {
            Store.putInt(key, (Store.getInt(key, 0) + 1) % 3);
            apply(a, key);
            paint.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(a, 40));
        lp.setMargins(Ui.dp(a, 6), 0, Ui.dp(a, 6), 0);
        b.setLayoutParams(lp);
        return b;
    }
}
