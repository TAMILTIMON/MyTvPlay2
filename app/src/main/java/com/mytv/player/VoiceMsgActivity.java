package com.mytv.player;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayDeque;

/** 🎙 A voice message on the TV: opens by itself, pauses what's playing, plays it, then everything carries on. */
public class VoiceMsgActivity extends Activity {
    static final String EXTRA_FILE = "file", EXTRA_FROM = "from", EXTRA_AT = "at";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Intent> queue = new ArrayDeque<>();
    private MediaPlayer mp;
    private String file, from;
    private long at;
    private TextView title, sub, mic;
    private ProgressBar bar;
    private AudioManager am;
    private AudioFocusRequest focusReq;
    private boolean pausedOurPlayer;

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    private TextView text(float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    @Override
    protected void onCreate(Bundle b) {
        Ui.apply(this, true);
        super.onCreate(b);
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xCC000000);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(36), dp(30), dp(36), dp(28));
        GradientDrawable cg = new GradientDrawable();
        cg.setColor(0xFF14161F);
        cg.setCornerRadius(dp(26));
        cg.setStroke(dp(1), 0x33FFFFFF);
        card.setBackground(cg);

        mic = text(46, Color.WHITE, false);
        mic.setText("🎙");
        GradientDrawable mg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{Ui.ACCENT, Ui.ACCENT2});
        mg.setShape(GradientDrawable.OVAL);
        mic.setBackground(mg);
        card.addView(mic, new LinearLayout.LayoutParams(dp(110), dp(110)));

        title = text(26, Color.WHITE, true);
        title.setPadding(0, dp(18), 0, dp(4));
        card.addView(title);
        sub = text(15, 0xFFB8BCC8, false);
        card.addView(sub);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(360), dp(10));
        blp.setMargins(0, dp(22), 0, dp(22));
        card.addView(bar, blp);

        LinearLayout btns = new LinearLayout(this);
        btns.setGravity(Gravity.CENTER);
        TextView again = button("▶  " + T.t("Play again"), true);
        again.setOnClickListener(v -> play());
        TextView close = button("✕  " + T.t("Close"), false);
        close.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(-2, dp(54));
        l1.setMargins(0, 0, dp(14), 0);
        btns.addView(again, l1);
        btns.addView(close, new LinearLayout.LayoutParams(-2, dp(54)));
        card.addView(btns);

        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        root.addView(card, clp);
        setContentView(root);
        again.requestFocus();
        take(getIntent());
    }

    private TextView button(String s, boolean primary) {
        TextView t = text(18, Color.WHITE, true);
        t.setText(s);
        t.setPadding(dp(26), 0, dp(26), 0);
        t.setFocusable(true);
        t.setClickable(true);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(27));
        if (primary) g.setColor(Ui.ACCENT);
        else g.setColor(0x22FFFFFF);
        t.setBackground(g);
        t.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).setDuration(120).start();
            ((GradientDrawable) v.getBackground()).setStroke(has ? dp(3) : 0, Color.WHITE);
        });
        return t;
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (mp != null && mp.isPlaying()) queue.add(i);       // another message: right after this one
        else take(i);
    }

    private void take(Intent i) {
        file = i.getStringExtra(EXTRA_FILE);
        from = i.getStringExtra(EXTRA_FROM);
        at = i.getLongExtra(EXTRA_AT, System.currentTimeMillis());
        title.setText(T.t("Voice message from") + " " + from);
        sub.setText(DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));
        main.removeCallbacks(autoClose);
        play();
    }

    // ------------------------------------------------------------------ playing

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (mp == null) return;
            try {
                int d = mp.getDuration();
                if (d > 0) bar.setProgress((int) (1000L * mp.getCurrentPosition() / d));
            } catch (Exception ignored) {
            }
            main.postDelayed(this, 100);
        }
    };

    private final Runnable autoClose = this::finish;

    private void play() {
        release();
        main.removeCallbacks(autoClose);
        quietOthers();
        try {
            mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            mp.setDataSource(file);
            mp.setOnCompletionListener(m -> {
                bar.setProgress(1000);
                mic.animate().scaleX(1f).scaleY(1f).setDuration(200).start();
                if (!queue.isEmpty()) {
                    main.postDelayed(() -> take(queue.poll()), 700);
                } else {
                    giveBackSound();
                    main.postDelayed(autoClose, 15000);   // closes by itself unless someone presses a button
                }
            });
            mp.prepare();
            mp.start();
            mic.animate().scaleX(1.08f).scaleY(1.08f).setDuration(300).start();
            main.post(tick);
        } catch (Exception e) {
            sub.setText("⚠ " + T.t("Couldn't play this message"));
            giveBackSound();
        }
    }

    /** Pause whatever is playing: ZH Play's own player directly, other apps (YouTube…) through audio focus. */
    private void quietOthers() {
        final PlayerActivity p = Remote.player;
        if (p != null && !pausedOurPlayer) {
            p.runOnUiThread(() -> {
                try {
                    if (p.state().optBoolean("playing")) {
                        p.command("pause", "", "");
                        pausedOurPlayer = true;
                    }
                } catch (Exception ignored) {
                }
            });
        }
        if (am == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusReq == null) focusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).build();
            am.requestAudioFocus(focusReq);
        } else {
            //noinspection deprecation
            am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        }
    }

    /** The message is over: other apps get their sound back, ZH Play's video carries on. */
    private void giveBackSound() {
        if (am != null) {
            if (Build.VERSION.SDK_INT >= 26 && focusReq != null) am.abandonAudioFocusRequest(focusReq);
            //noinspection deprecation
            else if (Build.VERSION.SDK_INT < 26) am.abandonAudioFocus(null);
        }
        final PlayerActivity p = Remote.player;
        if (pausedOurPlayer && p != null) {
            pausedOurPlayer = false;
            p.runOnUiThread(() -> {
                try {
                    p.command("play", "", "");
                } catch (Exception ignored) {
                }
            });
        }
    }

    private void release() {
        main.removeCallbacks(tick);
        if (mp != null) {
            try {
                mp.release();
            } catch (Exception ignored) {
            }
            mp = null;
        }
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        main.removeCallbacks(autoClose);                      // someone is here: don't close by itself
        return super.onKeyDown(code, e);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
        release();
        giveBackSound();
    }
}
