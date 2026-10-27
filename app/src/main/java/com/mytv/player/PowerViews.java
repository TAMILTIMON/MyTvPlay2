package com.mytv.player;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/** Controls for the Poweramp-style equalizer: glowing knob, glowing slider, response curve + spectrum. */
public final class PowerViews {
    private PowerViews() {
    }

    public interface OnValue {
        void on(int value, boolean done);
    }

    private static float dp(Context c, float v) {
        return v * c.getResources().getDisplayMetrics().density;
    }

    // ================================================================== rotary knob
    /** Drag up / down (or around) to turn; double-tap resets. Glowing arc shows the value. */
    public static final class Knob extends View {
        private final int min, max, def, color;
        private final boolean bipolar;
        private int value;
        private OnValue listener;
        private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG), track = new Paint(Paint.ANTI_ALIAS_FLAG),
                arc = new Paint(Paint.ANTI_ALIAS_FLAG), glow = new Paint(Paint.ANTI_ALIAS_FLAG), mark = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private float downY, downX;
        private int downVal;
        private final GestureDetector gd;

        public Knob(Context c, int min, int max, int def, int color, boolean bipolar) {
            super(c);
            this.min = min;
            this.max = max;
            this.def = def;
            this.value = def;
            this.color = color;
            this.bipolar = bipolar;
            setLayerType(LAYER_TYPE_SOFTWARE, null);    // needed for the glow
            track.setStyle(Paint.Style.STROKE);
            track.setStrokeCap(Paint.Cap.ROUND);
            track.setColor(0x26FFFFFF);
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeCap(Paint.Cap.ROUND);
            arc.setColor(color);
            glow.setStyle(Paint.Style.STROKE);
            glow.setStrokeCap(Paint.Cap.ROUND);
            glow.setColor(color);
            mark.setColor(0xE6FFFFFF);
            mark.setStrokeCap(Paint.Cap.ROUND);
            setFocusable(true);
            setContentDescription("knob");
            gd = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    set(def, true);
                    return true;
                }
            });
        }

        public void setListener(OnValue l) {
            listener = l;
        }

        public int get() {
            return value;
        }

        public void setValue(int v) {
            value = Math.max(min, Math.min(max, v));
            invalidate();
        }

        private void set(int v, boolean done) {
            v = Math.max(min, Math.min(max, v));
            if (v != value) {
                value = v;
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                invalidate();
            }
            if (listener != null) listener.on(value, done);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), s = Math.min(w, h);
            float stroke = s * 0.07f, pad = stroke * 1.6f;
            r.set((w - s) / 2 + pad, (h - s) / 2 + pad, (w + s) / 2 - pad, (h + s) / 2 - pad);
            track.setStrokeWidth(stroke);
            arc.setStrokeWidth(stroke);
            glow.setStrokeWidth(stroke * 2.2f);
            glow.setMaskFilter(new BlurMaskFilter(stroke * 1.4f, BlurMaskFilter.Blur.NORMAL));
            glow.setAlpha(150);
            // track 270°: from 135° to 405°
            c.drawArc(r, 135, 270, false, track);
            float f = (value - min) / (float) (max - min);
            float start, sweep;
            if (bipolar) {
                float mid = (def - min) / (float) (max - min);
                start = 135 + 270 * Math.min(mid, f);
                sweep = 270 * Math.abs(f - mid);
            } else {
                start = 135;
                sweep = 270 * f;
            }
            if (sweep > 0.5f) {
                c.drawArc(r, start, sweep, false, glow);
                c.drawArc(r, start, sweep, false, arc);
            }
            // knob body
            float cx = w / 2, cy = h / 2, kr = (r.width() / 2) - stroke * 1.8f;
            body.setShader(new RadialGradient(cx - kr * 0.3f, cy - kr * 0.35f, kr * 1.4f, 0xFF3A3A44, 0xFF121216, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, kr, body);
            body.setShader(null);
            body.setStyle(Paint.Style.STROKE);
            body.setStrokeWidth(dp(getContext(), 1));
            body.setColor(0x33FFFFFF);
            c.drawCircle(cx, cy, kr, body);
            body.setStyle(Paint.Style.FILL);
            // pointer
            double ang = Math.toRadians(135 + 270 * f);
            mark.setStrokeWidth(stroke * 0.8f);
            c.drawLine(cx + (float) Math.cos(ang) * kr * 0.45f, cy + (float) Math.sin(ang) * kr * 0.45f,
                    cx + (float) Math.cos(ang) * kr * 0.8f, cy + (float) Math.sin(ang) * kr * 0.8f, mark);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (gd.onTouchEvent(e)) return true;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    downY = e.getY();
                    downX = e.getX();
                    downVal = value;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float d = (downY - e.getY()) + (e.getX() - downX) * 0.5f;
                    set(Math.round(downVal + d / (getHeight() * 1.6f) * (max - min)), false);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (listener != null) listener.on(value, true);
                    return true;
                default:
                    return true;
            }
        }
    }

    // ================================================================== vertical glowing slider
    /** Band slider: bipolar (−/+) fill from the centre with a glow, Poweramp-like thumb. Double-tap = 0. */
    public static final class GlowSlider extends View {
        private final int min, max, color;
        private int value;
        private OnValue listener;
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
                glow = new Paint(Paint.ANTI_ALIAS_FLAG), thumb = new Paint(Paint.ANTI_ALIAS_FLAG), tick = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF tr = new RectF();
        private final GestureDetector gd;

        public GlowSlider(Context c, int min, int max, int color) {
            super(c);
            this.min = min;
            this.max = max;
            this.color = color;
            setLayerType(LAYER_TYPE_SOFTWARE, null);
            line.setColor(0x33FFFFFF);
            fill.setColor(color);
            glow.setColor(color);
            tick.setColor(0x40FFFFFF);
            setFocusable(true);
            gd = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    set(0, true);
                    return true;
                }
            });
        }

        public void setListener(OnValue l) {
            listener = l;
        }

        public void setValue(int v) {
            value = Math.max(min, Math.min(max, v));
            invalidate();
        }

        private float yOf(int v) {
            float top = getWidth() * 0.5f, bottom = getHeight() - getWidth() * 0.5f;
            return bottom - (v - min) / (float) (max - min) * (bottom - top);
        }

        private void set(int v, boolean done) {
            v = Math.max(min, Math.min(max, v));
            if (Math.abs(v) < (max - min) / 60) v = 0;   // gentle snap at 0 dB
            if (v != value) {
                value = v;
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                invalidate();
            }
            if (listener != null) listener.on(value, done);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), cx = w / 2;
            float lw = dp(getContext(), 2.5f);
            float top = yOf(max), bottom = yOf(min), zero = yOf(0), y = yOf(value);
            // dB ticks
            for (int k = 0; k <= 4; k++) {
                float ty = top + (bottom - top) * k / 4f;
                c.drawLine(cx - w * 0.28f, ty, cx - w * 0.18f, ty, tick);
                c.drawLine(cx + w * 0.18f, ty, cx + w * 0.28f, ty, tick);
            }
            line.setStrokeWidth(lw);
            c.drawLine(cx, top, cx, bottom, line);
            // fill + glow from 0 dB to the value
            glow.setStrokeWidth(lw * 3.5f);
            glow.setMaskFilter(new BlurMaskFilter(lw * 2.2f, BlurMaskFilter.Blur.NORMAL));
            glow.setAlpha(170);
            fill.setStrokeWidth(lw);
            if (Math.abs(y - zero) > 1) {
                c.drawLine(cx, zero, cx, y, glow);
                c.drawLine(cx, zero, cx, y, fill);
            }
            // thumb: rounded dark block with a light line (like Poweramp)
            float tw = w * 0.62f, th = dp(getContext(), 34);
            tr.set(cx - tw / 2, y - th / 2, cx + tw / 2, y + th / 2);
            thumb.setShader(new LinearGradient(0, tr.top, 0, tr.bottom, 0xFF4A4A54, 0xFF1C1C22, Shader.TileMode.CLAMP));
            c.drawRoundRect(tr, tw / 2.6f, tw / 2.6f, thumb);
            thumb.setShader(null);
            thumb.setColor(0xCCFFFFFF);
            thumb.setStrokeWidth(dp(getContext(), 2));
            c.drawLine(cx - tw * 0.25f, y, cx + tw * 0.25f, y, thumb);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (gd.onTouchEvent(e)) return true;
            float top = yOf(max), bottom = yOf(min);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                case MotionEvent.ACTION_UP: {
                    float f = (bottom - e.getY()) / (bottom - top);
                    int v = Math.round(min + f * (max - min));
                    set(v / 10 * 10, e.getActionMasked() == MotionEvent.ACTION_UP);
                    return true;
                }
                default:
                    return true;
            }
        }
    }

    // ================================================================== response curve + live spectrum
    public static final class EqCurveView extends View {
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), grid = new Paint(Paint.ANTI_ALIAS_FLAG),
                bar = new Paint(Paint.ANTI_ALIAS_FLAG), curve = new Paint(Paint.ANTI_ALIAS_FLAG),
                curveGlow = new Paint(Paint.ANTI_ALIAS_FLAG), under = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG),
                txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Path p = new Path(), fillPath = new Path();
        private float[] spec;       // 0..1 per bar
        private final float[] smooth = new float[48];

        public EqCurveView(Context c) {
            super(c);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
            bg.setColor(0xFF15151B);
            grid.setColor(0x1FFFFFFF);
            grid.setStrokeWidth(dp(c, 1));
            bar.setColor(0x38FFFFFF);
            curve.setStyle(Paint.Style.STROKE);
            curve.setStrokeWidth(dp(c, 2.5f));
            curve.setStrokeCap(Paint.Cap.ROUND);
            curveGlow.setStyle(Paint.Style.STROKE);
            curveGlow.setStrokeWidth(dp(c, 7));
            curveGlow.setMaskFilter(new BlurMaskFilter(dp(c, 5), BlurMaskFilter.Blur.NORMAL));
            txt.setColor(0x66FFFFFF);
            txt.setTextSize(dp(c, 9));
            dot.setColor(Color.WHITE);
        }

        /** Live spectrum (magnitudes 0..1, low → high frequency). */
        public void setSpectrum(float[] s) {
            spec = s;
            postInvalidateOnAnimation();
        }

        private float xOf(float hz, float w) {
            double a = Math.log(20), b = Math.log(20000);
            return (float) ((Math.log(hz) - a) / (b - a)) * w;
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), rad = dp(getContext(), 18);
            r.set(0, 0, w, h);
            c.save();
            Path clip = new Path();
            clip.addRoundRect(r, rad, rad, Path.Direction.CW);
            c.clipPath(clip);
            c.drawRect(r, bg);
            float mid = h / 2;
            c.drawLine(0, mid, w, mid, grid);
            for (int hz : new int[]{100, 1000, 10000}) {
                float x = xOf(hz, w);
                c.drawLine(x, 0, x, h, grid);
            }
            c.drawText("+15", dp(getContext(), 6), dp(getContext(), 12), txt);
            c.drawText("−15", dp(getContext(), 6), h - dp(getContext(), 5), txt);
            // spectrum bars (white, rounded)
            int n = smooth.length;
            float bw = w / n;
            for (int i = 0; i < n; i++) {
                float target = spec == null ? 0 : spec[Math.min(spec.length - 1, i * spec.length / n)];
                smooth[i] = smooth[i] * 0.6f + target * 0.4f;
                float bh = Math.max(dp(getContext(), 3), smooth[i] * h * 0.9f);
                c.drawRoundRect(i * bw + bw * 0.22f, mid - bh / 2, (i + 1) * bw - bw * 0.22f, mid + bh / 2, bw * 0.3f, bw * 0.3f, bar);
            }
            // response curve
            int[] eff = MusicFx.effective();
            boolean on = MusicFx.on();
            p.reset();
            fillPath.reset();
            float maxDb = 15f;
            for (int i = 0; i <= 100; i++) {
                float hz = (float) (20 * Math.pow(1000, i / 100.0));
                float db = on ? MusicFx.responseDb(hz, eff) : 0;
                float x = xOf(hz, w), y = mid - Math.max(-maxDb, Math.min(maxDb, db)) / maxDb * (h / 2 - dp(getContext(), 8));
                if (i == 0) {
                    p.moveTo(x, y);
                    fillPath.moveTo(x, mid);
                    fillPath.lineTo(x, y);
                } else {
                    p.lineTo(x, y);
                    fillPath.lineTo(x, y);
                }
            }
            fillPath.lineTo(w, mid);
            fillPath.close();
            Shader sh = new LinearGradient(0, 0, w, 0, new int[]{0xFFEF4444, 0xFFF97316, 0xFFEAB308, 0xFF22C55E, 0xFF06B6D4, 0xFF8B5CF6},
                    null, Shader.TileMode.CLAMP);
            under.setShader(new LinearGradient(0, 0, w, 0, new int[]{0x33EF4444, 0x33EAB308, 0x3322C55E, 0x338B5CF6}, null, Shader.TileMode.CLAMP));
            c.drawPath(fillPath, under);
            curveGlow.setShader(sh);
            curveGlow.setAlpha(on ? 160 : 60);
            c.drawPath(p, curveGlow);
            curve.setShader(sh);
            curve.setAlpha(on ? 255 : 90);
            c.drawPath(p, curve);
            // band points
            for (int i = 0; i < 10; i++) {
                float x = xOf(MusicFx.FREQS[i], w);
                float db = on ? MusicFx.responseDb(MusicFx.FREQS[i], eff) : 0;
                float y = mid - Math.max(-maxDb, Math.min(maxDb, db)) / maxDb * (h / 2 - dp(getContext(), 8));
                c.drawCircle(x, y, dp(getContext(), 3), dot);
            }
            c.restore();
            if (spec != null) postInvalidateOnAnimation();
        }
    }
}
