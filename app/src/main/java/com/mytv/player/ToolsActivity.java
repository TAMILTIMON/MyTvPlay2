package com.mytv.player;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Features menu: Equalizer · Video → Audio · Cutter · Splitter · Merger · Karaoke. */
public class ToolsActivity extends AppCompatActivity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout page;
    private TextView title;
    private int screen = -1;     // -1 = menu
    private MediaPlayer preview;

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    @Override
    protected void onCreate(Bundle b) {
        Ui.apply(this);
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Ui.background());
        root.setPadding(dp(14), dp(16), dp(14), 0);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(UiKit.roundButton(this, "arrow-left", 42, false, v -> onBackPressed()));
        title = Ui.text(this, 21, Ui.TEXT, true);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(top);
        ScrollView sv = new ScrollView(this);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(0, dp(12), 0, dp(30));
        sv.addView(page);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        menu();
    }

    @Override
    public void onBackPressed() {
        if (screen >= 0) menu();
        else super.onBackPressed();
    }

    private void setTitle(String t) {
        title.setText("  " + t);
    }

    // ------------------------------------------------------------------ menu
    private void menu() {
        stopPreview();
        screen = -1;
        setTitle(T.t("Features"));
        page.removeAllViews();
        page.addView(UiKit.grid(this, 2,
                UiKit.tile(this, "sliders-vertical", T.t("Equalizer"), T.t("10 bands, tone, effects"), 0xFFEF4444,
                        v -> startActivity(new Intent(this, PowerEqActivity.class))),
                UiKit.tile(this, "film", T.t("Video → Audio"), T.t("MP4 / MKV to M4A"), 0xFF3B82F6, v -> pick(false, this::convert)),
                UiKit.tile(this, "scissors", T.t("Cutter"), T.t("trim with waveform"), 0xFF22C55E, v -> pick(true, this::cutter)),
                UiKit.tile(this, "split", T.t("Splitter"), T.t("into parts"), 0xFFEAB308, v -> pick(true, this::splitter)),
                UiKit.tile(this, "combine", T.t("Merger"), T.t("join songs"), 0xFF8B5CF6, v -> merger(new ArrayList<>())),
                UiKit.tile(this, "mic-vocal", T.t("Karaoke master"), T.t("voice / music layers"), 0xFFEC4899, v -> pick(true, m -> startActivity(new Intent(this, KaraokeActivity.class)
                        .putExtra(KaraokeActivity.EXTRA_URI, m.uri.toString()).putExtra(KaraokeActivity.EXTRA_TITLE, m.title))))));
        TextView n = Ui.text(this, 12, Ui.DIM, false);
        n.setText(T.t("Results are saved in Music / ZH Play and appear in Music → Folders. MP3 stays MP3 when cutting, splitting or merging (no quality loss); other results are M4A (AAC) — Android has no MP3 encoder."));
        n.setPadding(dp(4), dp(14), dp(4), 0);
        page.addView(n);
    }

    // ------------------------------------------------------------------ picking a file
    private interface Picked {
        void on(PhoneMedia.M m);
    }

    private Picked pending;

    private void pick(final boolean audio, final Picked cb) {
        final List<PhoneMedia.M> all = new ArrayList<>(PhoneMedia.query(this, audio));
        if (!audio) all.addAll(PhoneMedia.query(this, true));   // video → audio also converts audio files
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(4), dp(16), 0);
        final EditText q = new EditText(this);
        q.setHint(T.t("Search"));
        q.setSingleLine(true);
        box.addView(q);
        final android.widget.ListView lv = new android.widget.ListView(this);
        final List<PhoneMedia.M> shown = new ArrayList<>(all);
        final android.widget.ArrayAdapter<String> ad = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<String>());
        Runnable fill = () -> {
            ad.clear();
            shown.clear();
            String s = q.getText().toString().toLowerCase(Locale.ROOT);
            for (PhoneMedia.M m : all) {
                if (!s.isEmpty() && !m.title.toLowerCase(Locale.ROOT).contains(s)) continue;
                shown.add(m);
                ad.add(m.title + "  ·  " + Net.fmtTime(m.dur));
            }
        };
        fill.run();
        q.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence c, int a, int b, int d) { }
            public void onTextChanged(CharSequence c, int a, int b, int d) { }
            public void afterTextChanged(android.text.Editable e) {
                fill.run();
            }
        });
        lv.setAdapter(ad);
        box.addView(lv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(380)));
        final AlertDialog d = new UiDialog(this).setTitle(T.t(audio ? "Choose a song" : "Choose a video"))
                .setView(box)
                .setNeutralButton(T.t("Browse files…"), (x, w) -> {
                    pending = cb;
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType(audio ? "audio/*" : "*/*");
                    if (!audio) i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"video/*", "audio/*"});
                    startActivityForResult(i, 61);
                })
                .setNegativeButton(T.t("Cancel"), null).create();
        lv.setOnItemClickListener((pa, v, pos, id) -> {
            d.dismiss();
            cb.on(shown.get(pos));
        });
        d.show();
        if (all.isEmpty()) UiKit.makeToast(this, T.t("Allow access in Music / Videos first, or use Browse files…"), android.widget.Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != 61 || res != RESULT_OK || data == null || data.getData() == null || pending == null) return;
        Uri u = data.getData();
        PhoneMedia.M m = new PhoneMedia.M();
        m.uri = u;
        String n = LocalShare.displayName(this, u);
        m.title = n == null ? "audio" : n;
        m.sub = "";
        m.folder = "";
        m.dur = AudioTools.durationUs(this, u) / 1000;
        pending.on(m);
    }

    // ------------------------------------------------------------------ running a tool with progress
    private interface Job {
        String run(AudioTools.Progress p) throws Exception;
    }

    private void run(String what, final Job job) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(8), dp(22), 0);
        final TextView info = Ui.text(this, 13, Ui.TEXT, false);
        info.setText(T.t("Working…"));
        box.addView(info);
        final ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        box.addView(bar);
        AudioTools.cancel = false;
        final AlertDialog d = new UiDialog(this).setTitle(what).setView(box).setCancelable(false)
                .setNegativeButton(T.t("Cancel"), (x, w) -> AudioTools.cancel = true).show();
        new Thread(() -> {
            String r;
            try {
                r = "✓ " + T.t("Saved") + ": " + job.run((f, w) -> main.post(() -> {
                    bar.setProgress((int) (f * 1000));
                    info.setText((w == null || w.isEmpty() ? T.t("Working…") : w) + "  " + Math.round(f * 100) + "%");
                }));
            } catch (Throwable e) {
                r = "⚠ " + (e.getMessage() == null ? String.valueOf(e) : e.getMessage());
            }
            final String msg = r;
            main.post(() -> {
                try {
                    d.dismiss();
                } catch (Exception ignored) {
                }
                new UiDialog(this).setTitle(what).setMessage(msg + (msg.startsWith("✓")
                        ? "\n\n" + T.t("Find it in Music → Folders → ZH Play.") : "")).setPositiveButton(T.t("OK"), null).show();
            });
        }).start();
    }

    // ------------------------------------------------------------------ Video → Audio
    private void convert(final PhoneMedia.M m) {
        run(T.t("Video → Audio"), p -> AudioTools.toAudio(this, m.uri, m.title, p));
    }

    private WaveView newWave(final PhoneMedia.M m, boolean markers) {
        final WaveView w = new WaveView(this);
        w.markers = markers;
        w.selection = !markers;
        final WaveView.Lane lane = w.lane("", 0xE6FFFFFF);
        w.durMs = Math.max(1, m.dur > 0 ? m.dur : AudioTools.durationUs(this, m.uri) / 1000);
        w.selA = 0;
        w.selB = w.durMs;
        w.message = T.t("Reading the sound…");
        w.onSeek = ms -> {
            if (preview != null && preview.isPlaying()) preview.seekTo((int) ms);   // tap while playing = jump there
        };
        new Thread(() -> {
            try {
                final float[] pk = AudioTools.waveform(this, m.uri, 4000, null);
                main.post(() -> {
                    lane.peaks = pk;
                    w.message = null;
                    w.invalidate();
                });
            } catch (Exception e) {
                main.post(() -> {
                    w.message = T.t("Can't read this song");
                    w.invalidate();
                });
            }
        }).start();
        return w;
    }

    /** Zoom slider row under a waveform. */
    private void zoomRow(WaveView w) {
        LinearLayout zr = new LinearLayout(this);
        zr.setGravity(Gravity.CENTER_VERTICAL);
        zr.addView(UiKit.icon(this, "search", 18, Ui.DIM));
        TextView zl = Ui.text(this, 12, Ui.DIM, false);
        zl.setText(" " + T.t("Zoom") + "  ");
        zr.addView(zl);
        zr.addView(w.zoomSlider(), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        page.addView(zr);
    }

    // ------------------------------------------------------------------ preview playback
    private final Runnable playTick = new Runnable() {
        public void run() {
            if (preview == null || wave == null) return;
            try {
                wave.setPlay(preview.getCurrentPosition());
                if (!wave.markers && wave.play >= wave.selB) {
                    preview.pause();
                    playBtn.setText(T.t("Play"));
                    return;
                }
                wave.invalidate();
            } catch (Exception ignored) {
            }
            main.postDelayed(this, 60);
        }
    };
    private WaveView wave;
    private Button playBtn;

    private void togglePreview(PhoneMedia.M m) {
        try {
            if (preview == null) {
                preview = MediaPlayer.create(this, m.uri);
                if (preview == null) return;
            }
            if (preview.isPlaying()) {
                preview.pause();
                playBtn.setText(T.t("Play"));
            } else {
                long from = wave.play >= 0 ? wave.play : wave.markers ? 0 : wave.selA;
                if (!wave.markers && (from < wave.selA || from >= wave.selB)) from = wave.selA;
                preview.seekTo((int) from);
                preview.start();
                playBtn.setText(T.t("Pause"));
                main.post(playTick);
            }
        } catch (Exception ignored) {
        }
    }

    private void stopPreview() {
        main.removeCallbacks(playTick);
        if (preview != null) {
            try {
                preview.release();
            } catch (Exception ignored) {
            }
        }
        preview = null;
    }

    private Button btn(String label, boolean primary, View.OnClickListener l) {
        Button b = Ui.button(this, label, primary);
        b.setOnClickListener(l);
        b.setTextSize(13);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1);
        lp.setMargins(dp(3), dp(4), dp(3), dp(4));
        b.setLayoutParams(lp);
        return b;
    }

    private LinearLayout row(View... v) {
        LinearLayout r = new LinearLayout(this);
        for (View x : v) r.addView(x);
        page.addView(r);
        return r;
    }

    private TextView label(String s) {
        TextView t = Ui.text(this, 13, Ui.DIM, false);
        t.setText(s);
        t.setPadding(dp(4), dp(10), 0, dp(4));
        page.addView(t);
        return t;
    }

    private void head(PhoneMedia.M m, String what, int scr) {
        stopPreview();
        screen = scr;
        setTitle(what);
        page.removeAllViews();
        TextView n = Ui.text(this, 16, Ui.TEXT, true);
        n.setText(m.title);
        n.setMaxLines(2);
        page.addView(n);
    }

    // ------------------------------------------------------------------ Cutter
    private int fadeIn, fadeOut;

    private void cutter(final PhoneMedia.M m) {
        head(m, T.t("Cutter"), 1);
        wave = newWave(m, false);
        page.addView(wave, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(180)));
        zoomRow(wave);
        final TextView sel = label("");
        wave.onChange = () -> sel.setText(T.t("Start") + " " + fmt(wave.selA) + "   ·   " + T.t("End") + " " + fmt(wave.selB)
                + "   ·   " + T.t("Length") + " " + fmt(wave.selB - wave.selA));
        wave.onChange.run();
        row(btn("−0.1 s", false, v -> nudge(true, -100)), btn("+0.1 s", false, v -> nudge(true, 100)),
                btn("−0.1 s", false, v -> nudge(false, -100)), btn("+0.1 s", false, v -> nudge(false, 100)));
        label(T.t("Left pair moves the start, right pair the end. Drag the handles on the waveform; tap it to play from there."));
        playBtn = btn(T.t("Play"), false, v -> togglePreview(m));
        row(playBtn, btn(T.t("Start = here"), false, v -> {
            if (wave.play >= 0 && wave.play < wave.selB) wave.selA = wave.play;
            wave.onChange.run();
            wave.invalidate();
        }), btn(T.t("End = here"), false, v -> {
            if (wave.play > wave.selA) wave.selB = wave.play;
            wave.onChange.run();
            wave.invalidate();
        }));
        label(T.t("Fade in / out (re-encodes to M4A)"));
        fadeIn = fadeOut = 0;
        final String[] fs = {"0 s", "1 s", "2 s", "3 s"};
        final Button fi = btn(T.t("Fade in") + ": 0 s", false, null), fo = btn(T.t("Fade out") + ": 0 s", false, null);
        fi.setOnClickListener(v -> {
            fadeIn = (fadeIn + 1) % 4;
            fi.setText(T.t("Fade in") + ": " + fs[fadeIn]);
        });
        fo.setOnClickListener(v -> {
            fadeOut = (fadeOut + 1) % 4;
            fo.setText(T.t("Fade out") + ": " + fs[fadeOut]);
        });
        row(fi, fo);
        row(btn("💾 " + T.t("Save the selected part"), true, v -> {
            stopPreview();
            final long a = wave.selA * 1000, b = wave.selB * 1000;
            run(T.t("Cutter"), p -> AudioTools.cut(this, m.uri, m.title, a, b, fadeIn * 1_000_000L, fadeOut * 1_000_000L, " (cut)", p));
        }));
    }

    private void nudge(boolean start, long ms) {
        if (wave == null) return;
        if (start) wave.selA = Math.max(0, Math.min(wave.selB - 200, wave.selA + ms));
        else wave.selB = Math.min(wave.durMs, Math.max(wave.selA + 200, wave.selB + ms));
        wave.onChange.run();
        wave.invalidate();
    }

    private static String fmt(long ms) {
        return String.format(Locale.US, "%d:%02d.%d", ms / 60000, ms / 1000 % 60, ms / 100 % 10);
    }

    // ------------------------------------------------------------------ Splitter
    private void splitter(final PhoneMedia.M m) {
        head(m, T.t("Splitter"), 2);
        wave = newWave(m, true);
        page.addView(wave, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(180)));
        zoomRow(wave);
        final TextView info = label("");
        final Runnable upd = () -> {
            java.util.Collections.sort(wave.marks);
            info.setText((wave.marks.size() + 1) + " " + T.t("parts") + "  ·  " + T.t("drag a marker to move it"));
            wave.invalidate();
        };
        wave.onChange = upd;
        upd.run();
        playBtn = btn(T.t("Play"), false, v -> togglePreview(m));
        row(playBtn, btn(T.t("Split here"), true, v -> {
            if (wave.play > 0) wave.marks.add(wave.play);
            upd.run();
        }), btn(T.t("Undo"), false, v -> {
            if (!wave.marks.isEmpty()) wave.marks.remove(wave.marks.size() - 1);
            upd.run();
        }));
        row(btn(T.t("Every 1 min"), false, v -> every(60_000, upd)), btn(T.t("Every 5 min"), false, v -> every(300_000, upd)),
                btn(T.t("Into 2"), false, v -> parts(2, upd)), btn(T.t("Into 3"), false, v -> parts(3, upd)));
        row(btn("💾 " + T.t("Save all parts"), true, v -> {
            stopPreview();
            final long[] pts = new long[wave.marks.size()];
            for (int i = 0; i < pts.length; i++) pts[i] = wave.marks.get(i) * 1000;
            run(T.t("Splitter"), p -> {
                List<String> r = AudioTools.split(this, m.uri, m.title, pts, p);
                return r.size() + " " + T.t("parts") + "\n" + android.text.TextUtils.join("\n", r);
            });
        }));
    }

    private void every(long ms, Runnable upd) {
        wave.marks.clear();
        for (long t = ms; t < wave.durMs - 1000; t += ms) wave.marks.add(t);
        upd.run();
    }

    private void parts(int n, Runnable upd) {
        wave.marks.clear();
        for (int i = 1; i < n; i++) wave.marks.add(wave.durMs * i / n);
        upd.run();
    }

    // ------------------------------------------------------------------ Merger
    private void merger(final List<PhoneMedia.M> list) {
        stopPreview();
        screen = 3;
        setTitle(T.t("Merger"));
        page.removeAllViews();
        label(list.isEmpty() ? T.t("Add the songs to join, in order.") : list.size() + " " + T.t("songs") + " · " + fmt(total(list)));
        for (int i = 0; i < list.size(); i++) {
            final int k = i;
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(10), dp(6), dp(6), dp(6));
            r.setBackground(Ui.card(this, Ui.CARD, Ui.STROKE, 14));
            TextView t = Ui.text(this, 14, Ui.TEXT, true);
            t.setText((i + 1) + ".  " + list.get(i).title);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            r.addView(UiKit.roundButton(this, "chevron-up", 36, false, v -> {
                if (k > 0) list.add(k - 1, list.remove(k));
                merger(list);
            }));
            r.addView(UiKit.roundButton(this, "chevron-down", 36, false, v -> {
                if (k < list.size() - 1) list.add(k + 1, list.remove(k));
                merger(list);
            }));
            r.addView(UiKit.roundButton(this, "x", 36, false, v -> {
                list.remove(k);
                merger(list);
            }));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(4), 0, dp(4));
            page.addView(r, lp);
        }
        row(btn("＋ " + T.t("Add a song"), false, v -> pick(true, m -> {
            list.add(m);
            merger(list);
        })));
        if (list.size() >= 2) {
            final EditText name = new EditText(this);
            name.setText(T.t("Merged") + " " + new java.text.SimpleDateFormat("d MMM HH.mm", Locale.getDefault()).format(new java.util.Date()));
            name.setSingleLine(true);
            page.addView(name);
            row(btn("💾 " + T.t("Merge"), true, v -> {
                final List<Uri> us = new ArrayList<>();
                final List<String> ns = new ArrayList<>();
                for (PhoneMedia.M m : list) {
                    us.add(m.uri);
                    ns.add(m.title);
                }
                final String out = name.getText().toString().trim();
                run(T.t("Merger"), p -> AudioTools.merge(this, us, ns, out.isEmpty() ? "Merged" : out, p));
            }));
        }
    }

    private static long total(List<PhoneMedia.M> l) {
        long t = 0;
        for (PhoneMedia.M m : l) t += m.dur;
        return t;
    }

    @Override
    protected void onDestroy() {
        stopPreview();
        AudioTools.cancel = true;
        super.onDestroy();
    }
}
