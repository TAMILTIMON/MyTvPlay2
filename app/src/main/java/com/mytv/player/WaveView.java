package com.mytv.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Waveform with zoom (1×–40×) and scrolling, one or more lanes (e.g. Original / Voice / Music),
 * a time ruler, optional selection handles (cutter), split markers (splitter) and a playhead.
 * Drag the empty area to scroll when zoomed; tap to set the play position.
 */
public final class WaveView extends View {
    public static final class Lane {
        public final String name;
        public final int color;
        public float[] peaks;

        public Lane(String name, int color) {
            this.name = name;
            this.color = color;
        }
    }

    public final List<Lane> lanes = new ArrayList<>();
    public long durMs = 1, selA, selB, play = -1;
    public boolean selection, markers;
    public final List<Long> marks = new ArrayList<>();
    public Runnable onChange;
    public interface OnSeek {
        void seek(long ms);
    }
    public OnSeek onSeek;
    public String message;            // shown while the sound is being read

    private float zoom = 1f;
    private long offset;              // left edge, ms
    private final Paint pk = new Paint(Paint.ANTI_ALIAS_FLAG), sel = new Paint(Paint.ANTI_ALIAS_FLAG),
            hd = new Paint(Paint.ANTI_ALIAS_FLAG), ph = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG),
            grid = new Paint(Paint.ANTI_ALIAS_FLAG), lab = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int drag = -1;
    private float downX, lastX;
    private boolean panning;
    private final int slop;

    public WaveView(Context c) {
        super(c);
        sel.setColor((Ui.ACCENT & 0x00FFFFFF) | 0x3A000000);
        hd.setColor(Ui.ACCENT);
        ph.setColor(0xFFEAB308);
        txt.setColor(0xB3FFFFFF);
        txt.setTextSize(Ui.dp(c, 9));
        lab.setTextSize(Ui.dp(c, 10));
        lab.setFakeBoldText(true);
        grid.setColor(0x1AFFFFFF);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        setBackground(Ui.card(c, 0xFF121218, 0x33FFFFFF, 16));
    }

    public Lane lane(String name, int color) {
        Lane l = new Lane(name, color);
        lanes.add(l);
        return l;
    }

    // ------------------------------------------------------------------ zoom / scroll
    public float getZoom() {
        return zoom;
    }

    /** Zoom around the play position (or the middle). */
    public void setZoom(float z) {
        long center = play >= 0 ? play : offset + window() / 2;
        zoom = Math.max(1f, Math.min(40f, z));
        offset = center - window() / 2;
        clamp();
        invalidate();
    }

    private long window() {
        return Math.max(1, (long) (durMs / zoom));
    }

    private void clamp() {
        offset = Math.max(0, Math.min(durMs - window(), offset));
    }

    /** Moves the play position; scrolls along when it leaves the view. */
    public void setPlay(long ms) {
        play = ms;
        if (zoom > 1f && (ms < offset || ms > offset + window())) {
            offset = ms - window() / 8;
            clamp();
        }
        invalidate();
    }

    float x(long ms) {
        return (ms - offset) * (float) getWidth() / window();
    }

    long ms(float x) {
        return Math.max(0, Math.min(durMs, offset + (long) (x / Math.max(1, getWidth()) * window())));
    }

    // ------------------------------------------------------------------ drawing
    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float ruler = Ui.dp(getContext(), 14);
        // time ruler
        long win = window();
        long step = niceStep(win / 6);
        for (long t = (offset / step) * step; t <= offset + win; t += step) {
            float xx = x(t);
            if (xx < 0) continue;
            c.drawLine(xx, ruler, xx, h, grid);
            c.drawText(time(t, step < 1000), xx + 3, ruler - 3, txt);
        }
        if (selection) c.drawRect(Math.max(0, x(selA)), ruler, Math.min(w, x(selB)), h, sel);
        int n = Math.max(1, lanes.size());
        float laneH = (h - ruler) / n;
        for (int li = 0; li < lanes.size(); li++) {
            Lane L = lanes.get(li);
            float top = ruler + li * laneH, mid = top + laneH / 2;
            if (li > 0) c.drawLine(0, top, w, top, grid);
            if (L.peaks != null && L.peaks.length > 0) {
                int cols = (int) w;
                for (int px = 0; px < cols; px++) {
                    long t0 = ms(px), t1 = ms(px + 1);
                    int a = (int) (t0 * (long) L.peaks.length / Math.max(1, durMs)), b = (int) (t1 * (long) L.peaks.length / Math.max(1, durMs));
                    float v = 0;
                    for (int k = Math.max(0, a); k <= Math.min(L.peaks.length - 1, Math.max(a, b)); k++) v = Math.max(v, L.peaks[k]);
                    float amp = Math.max(0.8f, v * (laneH / 2 - 4));
                    boolean dim = selection && (t0 < selA || t0 > selB);
                    pk.setColor(dim ? (L.color & 0x00FFFFFF) | 0x50000000 : L.color);
                    c.drawRect(px, mid - amp, px + 1, mid + amp, pk);
                }
            }
            if (n > 1) {
                lab.setColor(L.color);
                c.drawText(L.name, Ui.dp(getContext(), 6), top + Ui.dp(getContext(), 12), lab);
            }
        }
        if (message != null) {
            txt.setTextSize(Ui.dp(getContext(), 12));
            c.drawText(message, Ui.dp(getContext(), 12), h / 2, txt);
            txt.setTextSize(Ui.dp(getContext(), 9));
        }
        float hw = Ui.dp(getContext(), 3);
        if (selection) {
            for (long t : new long[]{selA, selB}) {
                float xx = x(t);
                c.drawRect(xx - hw / 2, ruler, xx + hw / 2, h, hd);
                c.drawRoundRect(new RectF(xx - hw * 3, h - hw * 7, xx + hw * 3, h), hw * 2, hw * 2, hd);
            }
        }
        if (markers) {
            for (int i = 0; i < marks.size(); i++) {
                float mx = x(marks.get(i));
                c.drawRect(mx - hw / 2, ruler, mx + hw / 2, h, hd);
                c.drawText(String.valueOf(i + 1), mx + hw * 2, ruler + Ui.dp(getContext(), 11), txt);
            }
        }
        if (play >= 0) {
            float px = x(play);
            c.drawRect(px - 1, 0, px + 1, h, ph);
        }
        // scroll indicator when zoomed
        if (zoom > 1f) {
            float bw = w / zoom, bx = offset * w / durMs;
            pk.setColor(0x66FFFFFF);
            c.drawRoundRect(new RectF(bx, h - hw, bx + bw, h), hw, hw, pk);
        }
    }

    private static long niceStep(long ms) {
        long[] s = {100, 250, 500, 1000, 2000, 5000, 10000, 15000, 30000, 60000, 120000, 300000, 600000};
        for (long v : s) if (v >= ms) return v;
        return 600000;
    }

    static String time(long ms, boolean tenths) {
        return tenths ? String.format(Locale.US, "%d:%02d.%d", ms / 60000, ms / 1000 % 60, ms / 100 % 10)
                : String.format(Locale.US, "%d:%02d", ms / 60000, ms / 1000 % 60);
    }

    // ------------------------------------------------------------------ touch
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float xx = e.getX(), grab = Ui.dp(getContext(), 22);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                downX = lastX = xx;
                panning = false;
                drag = -1;
                if (selection) {
                    float da = Math.abs(xx - x(selA)), db = Math.abs(xx - x(selB));
                    if (Math.min(da, db) < grab * 1.6f) drag = da < db ? 0 : 1;
                } else if (markers) {
                    for (int i = 0; i < marks.size(); i++) if (Math.abs(xx - x(marks.get(i))) < grab) drag = 10 + i;
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (drag == 0) selA = Math.min(ms(xx), selB - 100);
                else if (drag == 1) selB = Math.max(ms(xx), selA + 100);
                else if (drag >= 10 && drag - 10 < marks.size()) marks.set(drag - 10, ms(xx));
                else if (Math.abs(xx - downX) > slop || panning) {
                    panning = true;
                    if (zoom > 1f) {
                        offset -= (long) ((xx - lastX) / Math.max(1, getWidth()) * window());
                        clamp();
                    }
                }
                lastX = xx;
                if (drag >= 0 && onChange != null) onChange.run();
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                if (drag < 0 && !panning) {
                    play = ms(xx);
                    if (onSeek != null) onSeek.seek(play);
                    if (onChange != null) onChange.run();
                }
                if (drag >= 0 && onChange != null) onChange.run();
                drag = -1;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    /** A zoom slider for this view (1× … 40×). */
    public android.widget.SeekBar zoomSlider() {
        android.widget.SeekBar z = new android.widget.SeekBar(getContext());
        z.setMax(100);
        z.setContentDescription("Zoom");
        z.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(android.widget.SeekBar s, int v, boolean user) {
                if (user) setZoom((float) Math.pow(40, v / 100.0));
            }

            public void onStartTrackingTouch(android.widget.SeekBar s) { }

            public void onStopTrackingTouch(android.widget.SeekBar s) { }
        });
        return z;
    }
}
