package com.mytv.player;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;

/** A full colour picker (every shade, not just a preset list): hue strip + saturation/value square + hex entry. */
final class ColorPick {
    private ColorPick() {
    }

    private static int dp(Activity a, float v) {
        return Ui.dp(a, v);
    }

    /** A horizontal strip across every hue; tap/drag picks one. */
    private static final class HueBar extends View {
        float hue;
        java.util.function.Consumer<Float> onHue;

        HueBar(Context c) {
            super(c);
        }

        @Override
        protected void onDraw(Canvas cv) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            int n = 13;
            int[] stops = new int[n];
            float[] pos = new float[n];
            for (int i = 0; i < n; i++) {
                stops[i] = Color.HSVToColor(new float[]{360f * i / (n - 1), 1f, 1f});
                pos[i] = (float) i / (n - 1);
            }
            Paint p = new Paint();
            p.setShader(new LinearGradient(0, 0, w, 0, stops, pos, Shader.TileMode.CLAMP));
            cv.drawRoundRect(0, 0, w, h, h / 2f, h / 2f, p);
            float x = hue / 360f * w;
            Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(Ui.dp(getContext(), 2));
            ring.setColor(Color.WHITE);
            cv.drawCircle(x, h / 2f, h / 2f - Ui.dp(getContext(), 1), ring);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            hue = Math.max(0, Math.min(360, e.getX() / Math.max(1, getWidth()) * 360f));
            invalidate();
            if (onHue != null) onHue.accept(hue);
            return true;
        }
    }

    /** White→hue left-to-right, transparent→black top-to-bottom; tap/drag picks saturation + value. */
    private static final class SatValBox extends View {
        float hue, sat = 1, val = 1;
        java.util.function.BiConsumer<Float, Float> onSatVal;

        SatValBox(Context c) {
            super(c);
        }

        @Override
        protected void onDraw(Canvas cv) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            int hueColor = Color.HSVToColor(new float[]{hue, 1f, 1f});
            Paint p = new Paint();
            p.setShader(new LinearGradient(0, 0, w, 0, Color.WHITE, hueColor, Shader.TileMode.CLAMP));
            cv.drawRoundRect(0, 0, w, h, Ui.dp(getContext(), 10), Ui.dp(getContext(), 10), p);
            Paint black = new Paint();
            black.setShader(new LinearGradient(0, 0, 0, h, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
            cv.drawRoundRect(0, 0, w, h, Ui.dp(getContext(), 10), Ui.dp(getContext(), 10), black);
            float px = sat * w, py = (1 - val) * h;
            Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(Ui.dp(getContext(), 2));
            ring.setColor(val > 0.6f && sat < 0.6f ? Color.BLACK : Color.WHITE);
            cv.drawCircle(px, py, Ui.dp(getContext(), 7), ring);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int w = getWidth(), h = getHeight();
            sat = Math.max(0, Math.min(1, e.getX() / Math.max(1, w)));
            val = Math.max(0, Math.min(1, 1 - e.getY() / Math.max(1, h)));
            invalidate();
            if (onSatVal != null) onSatVal.accept(sat, val);
            return true;
        }
    }

    /** Shows the full picker; onPick gets the chosen ARGB colour (opaque) if "Use this colour" is tapped. */
    static void pick(final Activity a, final int current, final java.util.function.IntConsumer onPick) {
        float[] hsv = new float[3];
        Color.colorToHSV(current | 0xFF000000, hsv);
        final int[] live = {current | 0xFF000000};

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(a, 18), dp(a, 6), dp(a, 18), 0);

        final View preview = new View(a);
        preview.setBackground(Ui.card(a, live[0], 0xFF888888, dp(a, 10)));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, dp(a, 44));
        plp.setMargins(0, 0, 0, dp(a, 10));
        box.addView(preview, plp);

        final SatValBox sv = new SatValBox(a);
        sv.hue = hsv[0];
        sv.sat = hsv[1];
        sv.val = hsv[2];
        box.addView(sv, new LinearLayout.LayoutParams(-1, dp(a, 170)));

        final HueBar hb = new HueBar(a);
        hb.hue = hsv[0];
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, dp(a, 28));
        hlp.setMargins(0, dp(a, 14), 0, dp(a, 14));
        box.addView(hb, hlp);

        final EditText hex = new EditText(a);
        hex.setSingleLine(true);
        hex.setHint("#RRGGBB");
        hex.setText(String.format("#%06X", live[0] & 0xFFFFFF));
        box.addView(hex);

        box.addView(label(a, T.t("Suggestions")), top(a, 14));
        final LinearLayout harmony = new LinearLayout(a);
        box.addView(harmony);

        final boolean[] fromHex = {false};
        final Runnable[] refreshBox = new Runnable[1];
        refreshBox[0] = () -> {
            live[0] = Color.HSVToColor(new float[]{hb.hue, sv.sat, sv.val});
            preview.setBackground(Ui.card(a, live[0], 0xFF888888, dp(a, 10)));
            if (!fromHex[0]) hex.setText(String.format("#%06X", live[0] & 0xFFFFFF));
            harmony.removeAllViews();
            float[] offsets = {180, 120, 240, 30, -30};        // complementary, triadic ×2, analogous ×2
            for (float off : offsets) {
                int c = Color.HSVToColor(new float[]{((hb.hue + off) % 360 + 360) % 360, Math.max(0.35f, sv.sat), Math.max(0.5f, sv.val)});
                View sw = new View(a);
                sw.setBackground(Ui.card(a, c, 0xFF888888, dp(a, 8)));
                sw.setFocusable(true);
                sw.setOnClickListener(v -> {
                    float[] out = new float[3];
                    Color.colorToHSV(c, out);
                    hb.hue = out[0];
                    sv.hue = out[0];
                    sv.sat = out[1];
                    sv.val = out[2];
                    hb.invalidate();
                    sv.invalidate();
                    fromHex[0] = false;
                    refreshBox[0].run();
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(a, 34), 1);
                lp.setMargins(dp(a, 3), 0, dp(a, 3), 0);
                harmony.addView(sw, lp);
            }
        };
        final Runnable refresh = refreshBox[0];
        hb.onHue = h -> {
            sv.hue = h;
            sv.invalidate();
            fromHex[0] = false;
            refresh.run();
        };
        sv.onSatVal = (s, v) -> {
            fromHex[0] = false;
            refresh.run();
        };
        hex.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a1, int b, int c) {
            }

            public void onTextChanged(CharSequence s, int a1, int b, int c) {
            }

            public void afterTextChanged(android.text.Editable ed) {
                try {
                    int c = Color.parseColor(ed.toString().trim());
                    fromHex[0] = true;
                    live[0] = c | 0xFF000000;
                    float[] out = new float[3];
                    Color.colorToHSV(live[0], out);
                    hb.hue = out[0];
                    sv.hue = out[0];
                    sv.sat = out[1];
                    sv.val = out[2];
                    hb.invalidate();
                    sv.invalidate();
                    preview.setBackground(Ui.card(a, live[0], 0xFF888888, dp(a, 10)));
                } catch (Exception ignored) {
                    // still typing a valid code
                }
            }
        });

        refresh.run();      // first fill-in of the preview, hex and the harmony row
        new UiDialog(a).setTitle("🎨 " + T.t("Colour")).setView(box)
                .setPositiveButton(T.t("Use this colour"), (d, w) -> onPick.accept(live[0]))
                .setNegativeButton(T.t("Cancel"), null).show();
    }

    private static android.widget.TextView label(Activity a, String s) {
        android.widget.TextView t = new android.widget.TextView(a);
        t.setText(s.toUpperCase(java.util.Locale.getDefault()));
        t.setTextSize(11);
        t.setTextColor(Ui.DIM);
        t.setLetterSpacing(0.06f);
        return t;
    }

    private static LinearLayout.LayoutParams top(Activity a, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(a, topDp), 0, dp(a, 4));
        return lp;
    }
}
