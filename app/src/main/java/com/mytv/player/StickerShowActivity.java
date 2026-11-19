package com.mytv.player;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayDeque;

/** 🌟 A sticker floating over whatever is on the TV — no background — animated; gone after ~6 s or any key. */
public class StickerShowActivity extends Activity {
    static final String EXTRA_FILE = "file", EXTRA_ANIM = "anim", EXTRA_FROM = "from";
    private static final long SHOW_MS = 6000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Intent> queue = new ArrayDeque<>();
    private LinearLayout box;
    private ImageView img;
    private TextView from;
    private AnimatorSet running;
    private boolean leaving;

    private final Runnable close = this::leave;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        FrameLayout root = new FrameLayout(this);                 // fully see-through: only the sticker shows
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int side = (int) (Math.min(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels) * 0.55f);
        box.addView(img, new LinearLayout.LayoutParams(side, side));
        from = new TextView(this);
        from.setTextColor(Color.WHITE);
        from.setTextSize(15);
        from.setTypeface(Typeface.DEFAULT_BOLD);
        from.setPadding(Ui.dp(this, 14), Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 6));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x99000000);
        g.setCornerRadius(Ui.dp(this, 16));
        from.setBackground(g);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-2, -2);
        flp.setMargins(0, Ui.dp(this, 6), 0, 0);
        box.addView(from, flp);
        root.addView(box, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        setContentView(root);
        show(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        queue.add(i);                                             // after the one on screen
    }

    private void show(Intent i) {
        leaving = false;
        main.removeCallbacks(close);
        img.setImageBitmap(BitmapFactory.decodeFile(i.getStringExtra(EXTRA_FILE)));
        from.setText("🌟 " + T.t("from") + " " + i.getStringExtra(EXTRA_FROM));
        box.setAlpha(1f);
        animate(i.getStringExtra(EXTRA_ANIM));
        main.postDelayed(close, SHOW_MS);
    }

    /** The entrance, then a gentle movement until it leaves. */
    private void animate(String kind) {
        if (running != null) running.cancel();
        box.setRotation(0);
        box.setTranslationY(0);
        box.setScaleX(1);
        box.setScaleY(1);
        float up = Ui.dp(this, 16);
        Animator in, idle;
        switch (kind == null ? "bounce" : kind) {
            case "wiggle": {
                AnimatorSet s = new AnimatorSet();
                s.playTogether(ObjectAnimator.ofFloat(box, "scaleX", 0f, 1f), ObjectAnimator.ofFloat(box, "scaleY", 0f, 1f));
                s.setDuration(350);
                in = s;
                ObjectAnimator w = ObjectAnimator.ofFloat(box, "rotation", -9f, 9f);
                w.setDuration(260);
                w.setRepeatMode(ValueAnimator.REVERSE);
                w.setRepeatCount(ValueAnimator.INFINITE);
                idle = w;
                break;
            }
            case "spin": {
                AnimatorSet s = new AnimatorSet();
                s.playTogether(ObjectAnimator.ofFloat(box, "rotation", -360f, 0f),
                        ObjectAnimator.ofFloat(box, "scaleX", 0.2f, 1f), ObjectAnimator.ofFloat(box, "scaleY", 0.2f, 1f));
                s.setDuration(800);
                s.setInterpolator(new OvershootInterpolator(1.2f));
                in = s;
                idle = floatLoop(up);
                break;
            }
            case "float": {
                AnimatorSet s = new AnimatorSet();
                s.playTogether(ObjectAnimator.ofFloat(box, "alpha", 0f, 1f), ObjectAnimator.ofFloat(box, "translationY", up * 6, 0f));
                s.setDuration(700);
                s.setInterpolator(new AccelerateDecelerateInterpolator());
                in = s;
                idle = floatLoop(up);
                break;
            }
            default: {                                            // bounce in
                AnimatorSet s = new AnimatorSet();
                s.playTogether(ObjectAnimator.ofFloat(box, "scaleX", 0f, 1f), ObjectAnimator.ofFloat(box, "scaleY", 0f, 1f));
                s.setDuration(650);
                s.setInterpolator(new OvershootInterpolator(2.6f));
                in = s;
                AnimatorSet pulse = new AnimatorSet();
                ObjectAnimator px = ObjectAnimator.ofFloat(box, "scaleX", 1f, 1.06f), py = ObjectAnimator.ofFloat(box, "scaleY", 1f, 1.06f);
                for (ObjectAnimator o : new ObjectAnimator[]{px, py}) {
                    o.setRepeatMode(ValueAnimator.REVERSE);
                    o.setRepeatCount(ValueAnimator.INFINITE);
                    o.setDuration(520);
                }
                pulse.playTogether(px, py);
                idle = pulse;
            }
        }
        running = new AnimatorSet();
        running.playSequentially(in, idle);
        running.start();
    }

    private Animator floatLoop(float up) {
        ObjectAnimator f = ObjectAnimator.ofFloat(box, "translationY", 0f, -up, 0f);
        f.setDuration(1600);
        f.setInterpolator(new LinearInterpolator());
        f.setRepeatCount(ValueAnimator.INFINITE);
        return f;
    }

    /** Fades away; the next sticker (if any) comes in, otherwise the screen behind is back. */
    private void leave() {
        if (leaving) return;
        leaving = true;
        main.removeCallbacks(close);
        box.animate().alpha(0f).scaleX(0.7f).scaleY(0.7f).setDuration(250).withEndAction(() -> {
            if (running != null) running.cancel();
            Intent next = queue.poll();
            if (next != null) show(next);
            else finish();
        }).start();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {                  // any remote button: close it
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            queue.clear();
            leave();
        }
        return true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) leave();
        return true;
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
        if (running != null) running.cancel();
    }
}
