package com.mytv.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

/**
 * The deck's jog wheel (scratch disc), like on a DJ controller:
 * the platter turns with the music (33⅓ rpm); centre = scratch (the sound follows the finger, also backwards,
 * held still = silence); outer ring = nudge (speed up / slow down a little to line up beats).
 */
public class JogWheel extends View {
    private final DjCore.Deck deck;
    private final int color;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float lastAngle;
    private long lastT;
    private boolean scratching, nudging;
    private float touchGlow;

    public JogWheel(Context c, DjCore.Deck deck, int color) {
        super(c);
        this.deck = deck;
        this.color = color;
        setContentDescription(T.t("Jog wheel: touch the centre to scratch, turn the ring to nudge"));
    }

    /** One turn = 1.8 s of music (33⅓ rpm). */
    private double framesPerTurn() {
        DjCore.Source s = deck.src;
        return s == null ? 44100 * 1.8 : s.rate() * 1.8;
    }

    private float angleOf(MotionEvent e) {
        return (float) Math.toDegrees(Math.atan2(e.getY() - getHeight() / 2f, e.getX() - getWidth() / 2f));
    }

    private final Runnable stillCheck = new Runnable() {
        public void run() {
            // the finger rests on the record: it stops (like holding vinyl)
            if (scratching && SystemClock.uptimeMillis() - lastT > 50) deck.scratch = 0f;
            if (scratching) postDelayed(this, 30);
        }
    };

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (deck.src == null) return false;
        float cx = getWidth() / 2f, cy = getHeight() / 2f, R = Math.min(cx, cy);
        float dist = (float) Math.hypot(e.getX() - cx, e.getY() - cy);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                getParent().requestDisallowInterceptTouchEvent(true);
                lastAngle = angleOf(e);
                lastT = SystemClock.uptimeMillis();
                if (dist < R * 0.74f) {                 // centre: hold the record
                    scratching = true;
                    deck.scratch = 0f;
                    removeCallbacks(stillCheck);
                    postDelayed(stillCheck, 30);
                } else nudging = true;                  // outer ring: nudge
                touchGlow = 1f;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                float a = angleOf(e);
                float d = a - lastAngle;
                if (d > 180) d -= 360;
                if (d < -180) d += 360;
                long now = SystemClock.uptimeMillis();
                float dt = Math.max(1, now - lastT) / 1000f;
                if (scratching) {
                    // song frames per output frame = how fast the finger turns the record
                    double frames = d / 360.0 * framesPerTurn();
                    float v = (float) (frames / dt / deck.outRate());
                    deck.scratch = Math.max(-8f, Math.min(8f, v));
                } else if (nudging) {
                    float turnsPerSec = d / 360f / dt;
                    deck.bend = Math.max(0.75f, Math.min(1.25f, 1f + turnsPerSec * 0.12f));
                }
                lastAngle = a;
                lastT = now;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (scratching) deck.scratch = Float.NaN;   // let go: the record plays on (or stays paused)
                if (nudging) deck.bend = 1f;
                scratching = nudging = false;
                removeCallbacks(stillCheck);
                invalidate();
                return true;
            default:
                return false;
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f, R = Math.min(cx, cy) - 2;
        // outer ring (nudge zone)
        p.setStyle(Paint.Style.FILL);
        p.setShader(null);
        p.setColor(0xFF1C1C24);
        c.drawCircle(cx, cy, R, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(R * 0.05f);
        p.setColor(nudging ? color : (color & 0x00FFFFFF) | 0x66000000);
        c.drawCircle(cx, cy, R * 0.93f, p);
        // ring lights, like the controller
        for (int k = 0; k < 24; k++) {
            double a = Math.toRadians(k * 15);
            p.setStyle(Paint.Style.FILL);
            p.setColor((color & 0x00FFFFFF) | (k % 3 == 0 ? 0xCC000000 : 0x55000000));
            c.drawCircle(cx + (float) Math.cos(a) * R * 0.86f, cy + (float) Math.sin(a) * R * 0.86f, R * 0.018f, p);
        }
        // the record (turns with the music)
        float rec = R * 0.76f;
        double turns = deck.pos / framesPerTurn();
        float ang = (float) ((turns % 1.0) * 360.0);
        c.save();
        c.rotate(ang, cx, cy);
        p.setShader(new RadialGradient(cx, cy, rec, new int[]{0xFF2A2A33, 0xFF0E0E12, 0xFF1A1A20}, new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, cy, rec, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1f);
        p.setColor(0x22FFFFFF);
        for (float g = rec * 0.42f; g < rec; g += rec * 0.06f) c.drawCircle(cx, cy, g, p);       // grooves
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        c.drawCircle(cx, cy, rec * 0.34f, p);                                                      // label
        p.setColor(0xFF0E0E12);
        c.drawCircle(cx, cy, rec * 0.05f, p);
        p.setColor(0xFFFFFFFF);
        c.drawRect(new RectF(cx - rec * 0.025f, cy - rec * 0.98f, cx + rec * 0.025f, cy - rec * 0.40f), p);   // marker
        c.restore();
        if (scratching) {                               // finger on the record
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(R * 0.03f);
            p.setColor(0xFFFFFFFF);
            c.drawCircle(cx, cy, rec, p);
        }
        // keep turning while the music plays
        if (deck.playing || scratching || touchGlow > 0) {
            touchGlow = scratching || nudging ? 1f : 0f;
            postInvalidateOnAnimation();
        }
    }
}
