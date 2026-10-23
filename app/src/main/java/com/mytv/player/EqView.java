package com.mytv.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

/**
 * Vertical equalizer: one column per band, drag UP/DOWN to change.
 * TV remote: ◀ ▶ choose band, ▲ ▼ change value.
 */
public class EqView extends View {
    public interface Listener {
        void onBand(int index, float db);
    }

    private float[] values = new float[0];
    private String[] labels = new String[0];
    private int sel = 0;
    private Listener listener;
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
            knob = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG),
            ring = new Paint(Paint.ANTI_ALIAS_FLAG), mid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private static final float MIN = -20f, MAX = 20f;

    public EqView(Context c) {
        super(c);
        setFocusable(true);
        setFocusableInTouchMode(false);
        track.setColor(Ui.dark() ? 0x26FFFFFF : 0x1A2A1B5C);
        knob.setColor(0xFFFFFFFF);
        knob.setShadowLayer(dp(3), 0, 0, 0x66000000);
        txt.setColor(Ui.DIM);
        txt.setTextAlign(Paint.Align.CENTER);
        txt.setTextSize(dp(11));
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(dp(2));
        ring.setColor(Ui.ACCENT2);
        mid.setColor(Ui.dark() ? 0x40FFFFFF : 0x332A1B5C);
        mid.setStrokeWidth(dp(1));
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    public void setBands(String[] labels, float[] values) {
        this.labels = labels;
        this.values = values.clone();
        if (sel >= values.length) sel = 0;
        invalidate();
    }

    public void setValues(float[] v) {
        this.values = v.clone();
        invalidate();
    }

    public void setListener(Listener l) {
        listener = l;
    }

    @Override
    protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w);
        int height = (int) dp(240);
        setMeasuredDimension(width, resolveSize(height, h));
    }

    private float colW() {
        return values.length == 0 ? 1 : (float) getWidth() / values.length;
    }

    private float top() {
        return dp(28);
    }

    private float bottom() {
        return getHeight() - dp(30);
    }

    private float yFor(float v) {
        return bottom() - (v - MIN) / (MAX - MIN) * (bottom() - top());
    }

    @Override
    protected void onDraw(Canvas c) {
        float cw = colW(), barW = Math.min(dp(14), cw * 0.4f);
        float zero = yFor(0);
        c.drawLine(0, zero, getWidth(), zero, mid);
        fill.setShader(new LinearGradient(0, top(), 0, bottom(), Ui.ACCENT2, Ui.ACCENT, Shader.TileMode.CLAMP));
        for (int i = 0; i < values.length; i++) {
            float cx = cw * i + cw / 2f;
            r.set(cx - barW / 2, top(), cx + barW / 2, bottom());
            c.drawRoundRect(r, barW / 2, barW / 2, track);
            float y = yFor(values[i]);
            r.set(cx - barW / 2, Math.min(y, zero), cx + barW / 2, Math.max(y, zero));
            c.drawRoundRect(r, barW / 2, barW / 2, fill);
            boolean on = i == sel && hasFocus();
            c.drawCircle(cx, y, on ? dp(11) : dp(9), knob);
            if (on) c.drawCircle(cx, y, dp(15), ring);
            txt.setColor(on ? Ui.TEXT : Ui.DIM);
            txt.setFakeBoldText(on);
            c.drawText(String.format(java.util.Locale.US, "%+.0f", values[i]), cx, top() - dp(10), txt);
            c.drawText(i < labels.length ? labels[i] : "", cx, getHeight() - dp(8), txt);
        }
    }

    private void change(int i, float v) {
        v = Math.max(MIN, Math.min(MAX, Math.round(v * 2f) / 2f));
        if (i < 0 || i >= values.length || values[i] == v) return;
        values[i] = v;
        invalidate();
        if (listener != null) listener.onBand(i, v);
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (sel == 0) return false;
                sel--;
                invalidate();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (sel >= values.length - 1) return false;
                sel++;
                invalidate();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (values.length == 0) return false;
                if (values[sel] >= MAX) return false;
                change(sel, values[sel] + 1f);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (values.length == 0) return false;
                if (values[sel] <= MIN) return false;
                change(sel, values[sel] - 1f);
                return true;
            default:
                return super.onKeyDown(code, e);
        }
    }

    @Override
    protected void onFocusChanged(boolean f, int dir, android.graphics.Rect prev) {
        super.onFocusChanged(f, dir, prev);
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (values.length == 0) return false;
        int i = (int) (e.getX() / colW());
        i = Math.max(0, Math.min(values.length - 1, i));
        if (e.getAction() == MotionEvent.ACTION_DOWN) {
            getParent().requestDisallowInterceptTouchEvent(true);
            sel = i;
        }
        float v = MIN + (bottom() - e.getY()) / (bottom() - top()) * (MAX - MIN);
        change(sel, v);
        invalidate();
        return true;
    }
}
