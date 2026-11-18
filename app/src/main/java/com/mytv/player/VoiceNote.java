package com.mytv.player;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.util.HashMap;
import java.util.Locale;

/** 🎙 Phone: record a short voice message (≤ 60 s) and send it to the TV — at home directly, or from anywhere. */
final class VoiceNote {
    private VoiceNote() {
    }

    static final int REQ_MIC = 4702;
    private static final int MAX_MS = 60_000;

    /**
     * @param ip      the TV (its address at home, or "cloud:<id>" from anywhere)
     * @param cloudId the TV's cloud id when known — used if the TV can't be reached at home (it plays when back)
     * @param allowed false when the TV said voice messages are switched off
     */
    static void open(final Activity a, final String ip, final String tvName, final String cloudId, boolean allowed) {
        if (!allowed) {
            ZhPopup.message(a, "🎙", T.t("Voice messages are off on this TV"),
                    T.t("They can be switched on in the TV's ⚙ Settings → Voice messages from phones."));
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && a.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            a.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            UiKit.makeToast(a, T.t("Allow the microphone, then tap Voice message again"), Toast.LENGTH_LONG).show();
            return;
        }
        new Session(a, ip, tvName, cloudId).show();
    }

    private static final class Session {
        final Activity a;
        final String ip, tvName, cloudId;
        final Handler main = new Handler(Looper.getMainLooper());
        final File file;
        MediaRecorder rec;
        MediaPlayer player;
        long startedAt;
        boolean hasRecording;
        TextView mic, status, timer, listen, again, send;
        android.app.Dialog dlg;

        Session(Activity a, String ip, String tvName, String cloudId) {
            this.a = a;
            this.ip = ip;
            this.tvName = tvName == null || tvName.isEmpty() ? "TV" : tvName;
            this.cloudId = cloudId == null ? "" : cloudId;
            this.file = new File(a.getCacheDir(), "voice_note.m4a");
        }

        int dp(float v) {
            return Ui.dp(a, v);
        }

        TextView text(float sp, int color, boolean bold) {
            TextView t = new TextView(a);
            t.setTextSize(sp);
            t.setTextColor(color);
            t.setGravity(Gravity.CENTER);
            if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
            return t;
        }

        TextView pill(String s, boolean primary) {
            TextView t = text(14, primary ? Color.WHITE : Ui.TEXT, true);
            t.setText(s);
            t.setPadding(dp(14), dp(10), dp(14), dp(10));
            t.setBackground(primary ? Ui.gradient(a, 20) : Ui.focusCard(a, 20));
            t.setFocusable(true);
            t.setClickable(true);
            return t;
        }

        void show() {
            LinearLayout box = new LinearLayout(a);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER_HORIZONTAL);
            box.setPadding(dp(20), dp(10), dp(20), dp(6));
            mic = text(40, Color.WHITE, false);
            mic.setText("🎙");
            setMicColor(false);
            mic.setOnClickListener(v -> {
                if (rec != null) stop();
                else start();
            });
            box.addView(mic, new LinearLayout.LayoutParams(dp(110), dp(110)));
            status = text(15, Ui.TEXT, true);
            status.setText(T.t("Tap the microphone and speak"));
            status.setPadding(0, dp(14), 0, dp(2));
            box.addView(status);
            timer = text(13, Ui.DIM, false);
            timer.setText(T.t("up to 1 minute") + "  ·  " + T.t("to") + " " + tvName);
            box.addView(timer);
            LinearLayout row = new LinearLayout(a);
            row.setGravity(Gravity.CENTER);
            row.setPadding(0, dp(16), 0, 0);
            listen = pill("▶  " + T.t("Listen"), false);
            again = pill("↺  " + T.t("Again"), false);
            send = pill("➤  " + T.t("Send to TV"), true);
            listen.setOnClickListener(v -> listen());
            again.setOnClickListener(v -> start());
            send.setOnClickListener(v -> send());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(dp(4), 0, dp(4), 0);
            row.addView(listen, lp);
            row.addView(again, lp);
            row.addView(send, lp);
            box.addView(row);
            showActions(false);
            dlg = new UiDialog(a).setTitle("🎙  " + T.t("Voice message to the TV")).setView(box)
                    .setNegativeButton(T.t("Close"), null).show();
            dlg.setOnDismissListener(d -> cleanUp());
        }

        void setMicColor(boolean recording) {
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    recording ? new int[]{0xFFEF4444, 0xFFB91C1C} : new int[]{Ui.ACCENT, Ui.ACCENT2});
            g.setShape(GradientDrawable.OVAL);
            mic.setBackground(g);
        }

        void showActions(boolean on) {
            int v = on ? View.VISIBLE : View.GONE;
            listen.setVisibility(v);
            again.setVisibility(v);
            send.setVisibility(v);
        }

        final Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (rec == null) return;
                long ms = System.currentTimeMillis() - startedAt;
                timer.setText(String.format(Locale.ROOT, "%d:%02d / 1:00", ms / 60000, (ms / 1000) % 60));
                mic.animate().scaleX(mic.getScaleX() > 1.04f ? 1f : 1.08f).scaleY(mic.getScaleY() > 1.04f ? 1f : 1.08f).setDuration(450).start();
                main.postDelayed(this, 500);
            }
        };

        void start() {
            stopListening();
            try {
                //noinspection deprecation
                rec = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(a) : new MediaRecorder();
                rec.setAudioSource(MediaRecorder.AudioSource.MIC);
                rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                rec.setAudioChannels(1);
                rec.setAudioSamplingRate(22050);
                rec.setAudioEncodingBitRate(32000);              // clear speech, ~240 KB a minute
                rec.setMaxDuration(MAX_MS);
                rec.setOnInfoListener((r, what, extra) -> {
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) main.post(this::stop);
                });
                rec.setOutputFile(file.getPath());
                rec.prepare();
                rec.start();
                startedAt = System.currentTimeMillis();
                hasRecording = false;
                setMicColor(true);
                status.setText("● " + T.t("Recording… tap to stop"));
                showActions(false);
                main.post(tick);
            } catch (Exception e) {
                rec = null;
                status.setText("⚠ " + T.t("The microphone isn't available"));
            }
        }

        void stop() {
            if (rec == null) return;
            long ms = System.currentTimeMillis() - startedAt;
            try {
                rec.stop();
                hasRecording = ms >= 700;
            } catch (RuntimeException tooShort) {
                hasRecording = false;
            }
            try {
                rec.release();
            } catch (Exception ignored) {
            }
            rec = null;
            main.removeCallbacks(tick);
            mic.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
            setMicColor(false);
            if (hasRecording) {
                status.setText("✓ " + T.t("Ready to send"));
                timer.setText(String.format(Locale.ROOT, "%d:%02d", ms / 60000, (ms / 1000) % 60) + "  ·  " + T.t("to") + " " + tvName);
                showActions(true);
            } else {
                status.setText(T.t("Too short — tap the microphone and speak"));
            }
        }

        void listen() {
            stopListening();
            try {
                player = new MediaPlayer();
                player.setDataSource(file.getPath());
                player.setOnCompletionListener(m -> stopListening());
                player.prepare();
                player.start();
            } catch (Exception e) {
                UiKit.makeToast(a, "⚠ " + T.t("Couldn't play it"), Toast.LENGTH_SHORT).show();
            }
        }

        void stopListening() {
            if (player != null) {
                try {
                    player.release();
                } catch (Exception ignored) {
                }
                player = null;
            }
        }

        void send() {
            if (!hasRecording) return;
            stopListening();
            send.setEnabled(false);
            status.setText(T.t("Sending…"));
            final String from = Auth.signedIn() && !Auth.name().isEmpty() ? Auth.name() : T.t("your phone");
            new Thread(() -> {
                String result;
                try {
                    byte[] b = new byte[(int) file.length()];
                    try (FileInputStream in = new FileInputStream(file)) {
                        int n = 0;
                        while (n < b.length) {
                            int r = in.read(b, n, b.length - n);
                            if (r < 0) break;
                            n += r;
                        }
                    }
                    String b64 = android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP);
                    result = deliver(from, b64);
                } catch (Exception e) {
                    result = "⚠ " + T.t("Couldn't read the recording");
                }
                final String fr = result;
                main.post(() -> {
                    send.setEnabled(true);
                    status.setText(fr);
                    if (fr.startsWith("✓")) main.postDelayed(() -> {
                        try {
                            dlg.dismiss();
                        } catch (Exception ignored) {
                        }
                    }, 1600);
                });
            }).start();
        }

        /** At home straight to the TV; from anywhere (or TV not reachable now) through the cloud. */
        String deliver(String from, String b64) {
            if (ip != null && ip.startsWith("cloud:")) return viaCloud(ip.substring(6), from, b64);
            try {
                HashMap<String, String> p = new HashMap<>();
                p.put("audio", b64);
                p.put("from", from);
                org.json.JSONObject r = TvClient.post(ip, "/voicemsg", p);
                if (r.optBoolean("ok", true)) return "✓ " + T.t("Playing on the TV");
                return "⚠ " + r.optString("msg", T.t("The TV couldn't play it"));
            } catch (TvClient.AuthNeeded e) {
                return "⚠ " + T.t("Open the remote and enter the TV PIN first");
            } catch (Exception notReachable) {
                if (!cloudId.isEmpty() && Cloud.signedIn()) return viaCloud(cloudId, from, b64);
                return "⚠ " + T.t("Can't reach the TV — is it on and on the same Wi-Fi?");
            }
        }

        String viaCloud(String tvId, String from, String b64) {
            if (!Cloud.signedIn()) return "⚠ " + T.t("Sign in on this phone to send from anywhere");
            try {
                Cloud.sendVoice(tvId, from, b64);
                return "✓ " + T.t("Sent — it plays on the TV (or as soon as the TV is back on)");
            } catch (Exception e) {
                return "⚠ " + T.t("Couldn't send — check the internet") ;
            }
        }

        void cleanUp() {
            if (rec != null) {
                try {
                    rec.stop();
                } catch (Exception ignored) {
                }
                try {
                    rec.release();
                } catch (Exception ignored) {
                }
                rec = null;
            }
            main.removeCallbacksAndMessages(null);
            stopListening();
        }
    }
}
