package com.mytv.player;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Full-screen photo pager: the photo follows your finger, the neighbours slide in beside it,
 * a flick or a half-swipe changes page (otherwise it snaps back). Pinch / double-tap to zoom,
 * drag to pan while zoomed; dragging past the edge of a zoomed photo moves to the next one.
 */
final class PhotoPager extends View {
    interface Source {
        int count();

        /** The bitmap for this page if it's loaded, else null (and start loading it). */
        Bitmap get(int index);
    }

    interface Listener {
        void onPage(int index);

        void onTap();
    }

    private Source source;
    private Listener listener;
    private int index;
    private float dragX;                       // horizontal page offset while swiping
    private float scale = 1f, tx, ty;          // zoom of the current photo
    private final float gap;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix m = new Matrix();
    private final ScaleGestureDetector scaleDet;
    private final GestureDetector gest;
    private VelocityTracker vt;
    private float lastX, lastY;
    private boolean resync;
    private ValueAnimator anim;

    PhotoPager(Context c) {
        super(c);
        gap = Ui.dp(c, 16);
        hint.setColor(0x88FFFFFF);
        hint.setTextAlign(Paint.Align.CENTER);
        hint.setTextSize(Ui.dp(c, 14));
        setBackgroundColor(Color.BLACK);
        setFocusable(true);
        scaleDet = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                zoomTo(scale * d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                return true;
            }
        });
        gest = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;                    // keep receiving the rest of the gesture
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (listener != null) listener.onTap();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (scale > 1.05f) animateZoom(1f, getWidth() / 2f, getHeight() / 2f);
                else animateZoom(2.5f, e.getX(), e.getY());
                return true;
            }
        });
    }

    void setSource(Source s, Listener l) {
        source = s;
        listener = l;
    }

    void show(int i) {
        cancelAnim();
        index = Math.max(0, Math.min(i, Math.max(0, source.count() - 1)));
        dragX = 0;
        resetZoom();
        invalidate();
        if (listener != null) listener.onPage(index);
    }

    int index() {
        return index;
    }

    /** D-pad / buttons: go one page left (-1) or right (+1), animated. */
    void step(int dir) {
        if (source == null) return;
        int target = index + dir;
        if (target < 0 || target >= source.count()) return;
        resetZoom();
        settle(dir > 0 ? -(getWidth() + gap) : (getWidth() + gap));
    }

    private void resetZoom() {
        scale = 1f;
        tx = ty = 0;
    }

    // ------------------------------------------------------------------ drawing

    private float[] fit(Bitmap b) {
        int w = getWidth(), h = getHeight();
        float s = Math.min((float) w / b.getWidth(), (float) h / b.getHeight());
        return new float[]{s, (w - b.getWidth() * s) / 2f, (h - b.getHeight() * s) / 2f};
    }

    @Override
    protected void onDraw(Canvas c) {
        if (source == null || source.count() == 0) return;
        int w = getWidth();
        for (int k = -1; k <= 1; k++) {
            int i = index + k;
            if (i < 0 || i >= source.count()) continue;
            float off = dragX + k * (w + gap);
            if (off > w || off < -w) continue;
            Bitmap b = source.get(i);
            if (b == null) {
                c.drawText("…", off + w / 2f, getHeight() / 2f, hint);
                continue;
            }
            float[] f = fit(b);
            m.reset();
            m.postScale(f[0], f[0]);
            m.postTranslate(f[1], f[2]);
            if (k == 0) {
                m.postScale(scale, scale, w / 2f, getHeight() / 2f);
                m.postTranslate(tx, ty);
            }
            m.postTranslate(off, 0);
            c.drawBitmap(b, m, paint);
        }
    }

    // ------------------------------------------------------------------ zoom

    private float[] fitSize() {
        Bitmap b = source == null ? null : source.get(index);
        if (b == null) return new float[]{getWidth(), getHeight()};
        float[] f = fit(b);
        return new float[]{b.getWidth() * f[0], b.getHeight() * f[0]};
    }

    private void clampPan() {
        float[] fs = fitSize();
        float maxX = Math.max(0, (fs[0] * scale - getWidth()) / 2f);
        float maxY = Math.max(0, (fs[1] * scale - getHeight()) / 2f);
        tx = Math.max(-maxX, Math.min(maxX, tx));
        ty = Math.max(-maxY, Math.min(maxY, ty));
    }

    private void zoomTo(float target, float fx, float fy) {
        float s2 = Math.max(1f, Math.min(5f, target));
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        // keep the point under the fingers where it is
        tx = fx - cx - (s2 / scale) * (fx - cx - tx);
        ty = fy - cy - (s2 / scale) * (fy - cy - ty);
        scale = s2;
        if (scale <= 1.001f) {
            scale = 1f;
            tx = ty = 0;
        }
        clampPan();
        invalidate();
    }

    private void animateZoom(final float target, final float fx, final float fy) {
        cancelAnim();
        final float from = scale;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(220);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> zoomTo(from + (target - from) * (float) a.getAnimatedValue(), fx, fy));
        anim.start();
    }

    // ------------------------------------------------------------------ touch

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (source == null || source.count() == 0) return false;
        scaleDet.onTouchEvent(e);
        gest.onTouchEvent(e);
        if (vt == null) vt = VelocityTracker.obtain();
        vt.addMovement(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                cancelAnim();
                lastX = e.getX();
                lastY = e.getY();
                resync = false;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP:
                resync = true;                 // finger count changed: don't jump
                return true;
            case MotionEvent.ACTION_MOVE: {
                float x = 0, y = 0;
                int n = e.getPointerCount();
                for (int p = 0; p < n; p++) {
                    x += e.getX(p);
                    y += e.getY(p);
                }
                x /= n;
                y /= n;
                if (resync) {
                    lastX = x;
                    lastY = y;
                    resync = false;
                    return true;
                }
                float dx = x - lastX, dy = y - lastY;
                lastX = x;
                lastY = y;
                if (scaleDet.isInProgress()) return true;
                moveBy(dx, dy);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                vt.computeCurrentVelocity(1000);
                float vx = vt.getXVelocity();
                vt.recycle();
                vt = null;
                release(vx);
                return true;
            }
        }
        return true;
    }

    private void moveBy(float dx, float dy) {
        // a page swipe that's already started gets the movement first
        if (dragX != 0) {
            float nd = dragX + dx;
            if (Math.signum(nd) != Math.signum(dragX)) {
                dx = nd;
                dragX = 0;
            } else {
                dragX = nd;
                dx = 0;
            }
        }
        if (scale > 1.001f) {
            float want = tx + dx;
            tx = want;
            ty += dy;
            clampPan();
            float overflow = want - tx;           // dragged past the photo's edge → start turning the page
            if (overflow != 0) addDrag(overflow);
        } else if (dx != 0) {
            addDrag(dx);
        }
    }

    private void addDrag(float dx) {
        boolean atStart = index == 0 && dragX + dx > 0;
        boolean atEnd = index == source.count() - 1 && dragX + dx < 0;
        dragX += (atStart || atEnd) ? dx * 0.35f : dx;    // rubber-band at the first / last photo
    }

    private void release(float vx) {
        int w = getWidth();
        float flick = Ui.dp(getContext(), 900);
        if ((dragX < -w * 0.25f || (vx < -flick && dragX < 0)) && index < source.count() - 1) settle(-(w + gap));
        else if ((dragX > w * 0.25f || (vx > flick && dragX > 0)) && index > 0) settle(w + gap);
        else if (dragX != 0) settle(0);
    }

    /** Animate the page offset to target; ±(width+gap) turns the page, 0 snaps back. */
    private void settle(final float target) {
        cancelAnim();
        final float from = dragX;
        anim = ValueAnimator.ofFloat(from, target);
        anim.setDuration(Math.max(140, Math.min(300, (long) (Math.abs(target - from) / Math.max(1, getWidth()) * 320))));
        anim.setInterpolator(new DecelerateInterpolator(1.6f));
        anim.addUpdateListener(a -> {
            dragX = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            boolean cancelled;

            @Override
            public void onAnimationCancel(Animator a) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator a) {
                if (cancelled) return;
                if (target != 0) {
                    index += target < 0 ? 1 : -1;
                    resetZoom();
                    if (listener != null) listener.onPage(index);
                }
                dragX = 0;
                invalidate();
            }
        });
        anim.start();
    }

    private void cancelAnim() {
        if (anim != null) anim.cancel();
        anim = null;
    }
}
