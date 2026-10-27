package com.mytv.player;

import android.graphics.Color;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

/**
 * Karaoke master: the song is decoded once; you see Original / Voice / Music waveforms (Voice + Music = Original),
 * listen live to any of them (instant A/B), adjust filters and EQ while it plays, then save the full song.
 */
public class KaraokeActivity extends AppCompatActivity {
    public static final String EXTRA_URI = "uri", EXTRA_TITLE = "title";
    private static final int PEAKS = 4000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Uri uri;
    private String songTitle;
    private File pcm;                 // the decoded song: 16-bit stereo
    private int rate = 44100;
    private long frames;              // length in frames
    private boolean mono;

    private WaveView wave;
    private WaveView.Lane laneO, laneV, laneM;
    private TextView status, timeT;
    private ProgressBar prog;
    private Button playBtn, abBtn;
    private final Button[] modeBtns = new Button[4];
    private final KaraokeDsp live = new KaraokeDsp(44100);   // replaced after decoding with the real rate
    private KaraokeDsp dsp;
    private volatile boolean playing, ready;
    private volatile long playFrame;
    private volatile boolean seekPending;
    private Thread player;
    private int layerGen;

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    @Override
    protected void onCreate(Bundle b) {
        Ui.apply(this);
        super.onCreate(b);
        uri = Uri.parse(getIntent().getStringExtra(EXTRA_URI));
        songTitle = getIntent().getStringExtra(EXTRA_TITLE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B0B0F);
        root.setPadding(dp(12), dp(14), dp(12), 0);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(UiKit.darkButton(this, "arrow-left", 42, false, v -> finish()));
        LinearLayout tt = new LinearLayout(this);
        tt.setOrientation(LinearLayout.VERTICAL);
        tt.setPadding(dp(10), 0, 0, 0);
        TextView t1 = Ui.text(this, 19, Color.WHITE, true);
        t1.setText(T.t("Karaoke master"));
        TextView t2 = Ui.text(this, 12, 0xB3FFFFFF, false);
        t2.setText(songTitle);
        t2.setSingleLine(true);
        t2.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tt.addView(t1);
        tt.addView(t2);
        top.addView(tt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(top);

        ScrollView sv = new ScrollView(this);
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(0, dp(10), 0, dp(30));
        sv.addView(p);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        status = Ui.text(this, 12, 0xB3FFFFFF, false);
        p.addView(status);
        prog = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        prog.setMax(1000);
        p.addView(prog);

        // three layers
        wave = new WaveView(this);
        laneO = wave.lane(T.t("ORIGINAL"), 0xE6FFFFFF);
        laneV = wave.lane(T.t("VOICE (taken out)"), 0xFFF472B6);
        laneM = wave.lane(T.t("MUSIC (karaoke)"), 0xFF22D3EE);
        wave.message = T.t("Reading the song…");
        wave.onSeek = ms -> seekTo(ms * rate / 1000);
        p.addView(wave, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(240)));
        LinearLayout zr = new LinearLayout(this);
        zr.setGravity(Gravity.CENTER_VERTICAL);
        zr.addView(UiKit.icon(this, "search", 18, 0xB3FFFFFF));
        TextView zl = Ui.text(this, 12, 0xB3FFFFFF, false);
        zl.setText(" " + T.t("Zoom"));
        zr.addView(zl);
        zr.addView(wave.zoomSlider(), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        timeT = Ui.text(this, 13, Color.WHITE, true);
        zr.addView(timeT);
        p.addView(zr);

        // transport + A/B
        LinearLayout tr = new LinearLayout(this);
        tr.setGravity(Gravity.CENTER);
        tr.setPadding(0, dp(6), 0, dp(6));
        tr.addView(UiKit.darkButton(this, "rotate-ccw", 48, false, v -> seekTo(Math.max(0, cur() - 5L * rate))));
        View g = new View(this);
        tr.addView(g, new LinearLayout.LayoutParams(dp(10), 1));
        playBtn = new Button(this);
        playBtn.setAllCaps(false);
        playBtn.setTextColor(Color.WHITE);
        playBtn.setBackground(Ui.gradient(this, 26));
        playBtn.setOnClickListener(v -> togglePlay());
        tr.addView(playBtn, new LinearLayout.LayoutParams(dp(110), dp(52)));
        View g2 = new View(this);
        tr.addView(g2, new LinearLayout.LayoutParams(dp(10), 1));
        tr.addView(UiKit.darkButton(this, "rotate-cw", 48, false, v -> seekTo(Math.min(frames - 1, cur() + 5L * rate))));
        p.addView(tr);
        abBtn = new Button(this);
        abBtn.setAllCaps(false);
        abBtn.setTextSize(16);
        abBtn.setTextColor(Color.WHITE);
        abBtn.setOnClickListener(v -> setMode(live().mode == KaraokeDsp.ORIGINAL ? KaraokeDsp.MUSIC : KaraokeDsp.ORIGINAL));
        p.addView(abBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));

        // listen to a layer
        p.addView(section(T.t("Listen to")));
        LinearLayout mr = new LinearLayout(this);
        String[] mn = {T.t("Original"), T.t("Karaoke"), T.t("Voice only"), T.t("Mix")};
        for (int i = 0; i < 4; i++) {
            final int k = i;
            Button bb = new Button(this);
            bb.setAllCaps(false);
            bb.setText(mn[i]);
            bb.setTextSize(13);
            bb.setTextColor(Color.WHITE);
            bb.setOnClickListener(v -> setMode(k));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(42), 1);
            lp.setMargins(dp(2), 0, dp(2), 0);
            mr.addView(bb, lp);
            modeBtns[i] = bb;
        }
        p.addView(mr);

        // presets
        p.addView(section(T.t("Presets")));
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout pr = new LinearLayout(this);
        hs.addView(pr);
        String[] pn = {"Standard", "Strong", "Gentle", "Male voice", "Female voice", "Keep backing vocals", "Keep bass & drums"};
        for (int i = 0; i < pn.length; i++) {
            final int k = i;
            Button c = new Button(this);
            c.setAllCaps(false);
            c.setText(T.t(pn[i]));
            c.setTextSize(13);
            c.setTextColor(Color.WHITE);
            c.setBackground(Ui.card(this, 0xFF24242C, 0, 18));
            c.setOnClickListener(v -> preset(k));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
            lp.setMargins(0, 0, dp(8), 0);
            pr.addView(c, lp);
        }
        p.addView(hs);

        // adjustments (live while playing)
        p.addView(section(T.t("Filters & adjustments")));
        adj = new LinearLayout(this);
        adj.setOrientation(LinearLayout.VERTICAL);
        adj.setBackground(Ui.card(this, 0xFF16161C, 0, 18));
        adj.setPadding(dp(14), dp(8), dp(14), dp(10));
        p.addView(adj);
        buildSliders();

        // export
        p.addView(section(T.t("Save (whole song)")));
        LinearLayout ex = new LinearLayout(this);
        ex.addView(exportBtn(T.t("Karaoke"), KaraokeDsp.MUSIC, true));
        ex.addView(exportBtn(T.t("Voice only"), KaraokeDsp.VOICE, false));
        ex.addView(exportBtn(T.t("Mix"), KaraokeDsp.MIX, false));
        p.addView(ex);
        TextView note = Ui.text(this, 11, 0x80FFFFFF, false);
        note.setText(T.t("How it works: for every frequency the song is checked for sound that is the same in the left and right speaker (the lead singer usually is) and inside the vocal range. That part is the Voice layer; Music = Original − Voice. Instruments panned to one side, bass below the low cut and cymbals above the high cut stay in the music. Live or mono recordings, and songs with reverb on the voice, leave some voice behind — listen with Voice only to check."));
        note.setPadding(dp(2), dp(12), dp(2), 0);
        p.addView(note);

        setContentView(root);
        loadSettings();
        refreshButtons();
        prepare();
    }

    private TextView section(String s) {
        TextView t = Ui.text(this, 12, 0xB3FFFFFF, true);
        t.setText(s.toUpperCase(Locale.ROOT));
        t.setLetterSpacing(0.12f);
        t.setPadding(dp(4), dp(16), 0, dp(8));
        return t;
    }

    private KaraokeDsp live() {
        return dsp != null ? dsp : live;
    }

    private long cur() {
        return Math.max(0, playFrame - KaraokeDsp.LATENCY);
    }

    // ------------------------------------------------------------------ settings + sliders
    private LinearLayout adj;
    private final java.util.List<Runnable> sliderRefresh = new java.util.ArrayList<>();

    private interface FloatSet {
        void set(float v);
    }

    private interface FloatGet {
        float get();
    }

    private void slider(final String name, final float min, final float max, final String unit, final FloatGet get, final FloatSet set,
                        final boolean log) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        final TextView lab = Ui.text(this, 13, Color.WHITE, false);
        row.addView(lab, new LinearLayout.LayoutParams(dp(150), ViewGroup.LayoutParams.WRAP_CONTENT));
        final SeekBar s = new SeekBar(this);
        s.setMax(1000);
        s.setContentDescription(name);
        row.addView(s, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final Runnable refresh = () -> {
            float v = get.get();
            float f = log ? (float) (Math.log(v / min) / Math.log(max / min)) : (v - min) / (max - min);
            s.setProgress(Math.round(f * 1000));
            lab.setText(name + "  " + fmt(v, unit));
        };
        refresh.run();
        sliderRefresh.add(refresh);
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int pr, boolean user) {
                if (!user) return;
                float f = pr / 1000f;
                float v = log ? (float) (min * Math.pow(max / min, f)) : min + f * (max - min);
                set.set(v);
                lab.setText(name + "  " + fmt(v, unit));
                applySettings();
            }

            public void onStartTrackingTouch(SeekBar sb) { }

            public void onStopTrackingTouch(SeekBar sb) {
                saveSettings();
                recomputeLayers();
            }
        });
        adj.addView(row);
    }

    private static String fmt(float v, String unit) {
        switch (unit) {
            case "%": return Math.round(v * 100) + " %";
            case "Hz": return v >= 1000 ? String.format(Locale.US, "%.1f kHz", v / 1000) : Math.round(v) + " Hz";
            case "dB": return String.format(Locale.US, "%+.1f dB", v);
            default: return String.format(Locale.US, "%.1f", v);
        }
    }

    // current values (copied into the live engine)
    private float sStrength = 1f, sLow = 120f, sHigh = 8000f, sFocus = 2f, sBass = 0f, sTreble = 0f, sWidth = 1f, sVoice = 0.3f, sGain = 0f;

    private void buildSliders() {
        slider(T.t("Vocal removal"), 0f, 1f, "%", () -> sStrength, v -> sStrength = v, false);
        slider(T.t("Vocal range from"), 60f, 500f, "Hz", () -> sLow, v -> sLow = v, true);
        slider(T.t("Vocal range to"), 2500f, 16000f, "Hz", () -> sHigh, v -> sHigh = v, true);
        slider(T.t("Centre focus"), 1f, 4f, "x", () -> sFocus, v -> sFocus = v, false);
        slider(T.t("Music bass"), -12f, 12f, "dB", () -> sBass, v -> sBass = v, false);
        slider(T.t("Music treble"), -12f, 12f, "dB", () -> sTreble, v -> sTreble = v, false);
        slider(T.t("Stereo width"), 0f, 2f, "%", () -> sWidth, v -> sWidth = v, false);
        slider(T.t("Guide voice (Mix)"), 0f, 1f, "%", () -> sVoice, v -> sVoice = v, false);
        slider(T.t("Volume"), -12f, 6f, "dB", () -> sGain, v -> sGain = v, false);
    }

    private void apply(KaraokeDsp d) {
        d.strength = sStrength;
        d.lowCut = sLow;
        d.highCut = sHigh;
        d.focus = sFocus;
        d.bassDb = sBass;
        d.trebleDb = sTreble;
        d.width = sWidth;
        d.voiceLevel = sVoice;
        d.gainDb = sGain;
    }

    private void applySettings() {
        apply(live());
    }

    private void loadSettings() {
        sStrength = Store.getInt("k_str", 100) / 100f;
        sLow = Store.getInt("k_low", 120);
        sHigh = Store.getInt("k_high", 8000);
        sFocus = Store.getInt("k_focus", 20) / 10f;
        sBass = Store.getInt("k_bass", 0) / 10f;
        sTreble = Store.getInt("k_treb", 0) / 10f;
        sWidth = Store.getInt("k_width", 100) / 100f;
        sVoice = Store.getInt("k_voice", 30) / 100f;
        sGain = Store.getInt("k_gain", 0) / 10f;
        for (Runnable r : sliderRefresh) r.run();
        applySettings();
    }

    private void saveSettings() {
        Store.putInt("k_str", Math.round(sStrength * 100));
        Store.putInt("k_low", Math.round(sLow));
        Store.putInt("k_high", Math.round(sHigh));
        Store.putInt("k_focus", Math.round(sFocus * 10));
        Store.putInt("k_bass", Math.round(sBass * 10));
        Store.putInt("k_treb", Math.round(sTreble * 10));
        Store.putInt("k_width", Math.round(sWidth * 100));
        Store.putInt("k_voice", Math.round(sVoice * 100));
        Store.putInt("k_gain", Math.round(sGain * 10));
    }

    private void preset(int k) {
        sStrength = 1f; sLow = 120f; sHigh = 8000f; sFocus = 2f; sBass = 0f; sTreble = 0f; sWidth = 1f; sGain = 0f;
        switch (k) {
            case 1: sFocus = 1.2f; sLow = 90f; sHigh = 11000f; break;              // Strong
            case 2: sStrength = 0.75f; sFocus = 3f; break;                         // Gentle
            case 3: sLow = 80f; sHigh = 6000f; break;                              // Male voice
            case 4: sLow = 170f; sHigh = 10000f; break;                            // Female voice
            case 5: sFocus = 4f; sStrength = 0.9f; break;                          // keep backing vocals (wider)
            case 6: sLow = 220f; sHigh = 6500f; sBass = 3f; break;                 // keep bass & drums
            default: break;
        }
        for (Runnable r : sliderRefresh) r.run();
        applySettings();
        saveSettings();
        recomputeLayers();
    }

    // ------------------------------------------------------------------ decoding once
    private void prepare() {
        status.setText(T.t("Reading the song…"));
        new Thread(() -> {
            try {
                File d = new File(getCacheDir(), "karaoke");
                //noinspection ResultOfMethodCallIgnored
                d.mkdirs();
                pcm = new File(d, "song.pcm");
                final float[] peaks = new float[PEAKS];
                final long dur = Math.max(1, AudioTools.durationUs(this, uri));
                final BufferedOutputStream o = new BufferedOutputStream(new FileOutputStream(pcm), 1 << 16);
                final int[] fmt = {44100, 2};
                final long[] count = {0};
                AudioTools.cancel = false;   // a Cancel / close elsewhere must not abort this load
                AudioTools.decode(this, uri, 0, 0, new AudioTools.PcmSink() {
                    public void format(int r, int ch) {
                        fmt[0] = r;
                        fmt[1] = ch;
                        if (ch < 2) mono = true;
                    }

                    public void pcm(short[] data, int n, long pts) throws Exception {
                        int ch = fmt[1];
                        ByteBuffer bb = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN);
                        for (int i = 0; i < n; i++) {
                            short l = data[i * ch], r = ch > 1 ? data[i * ch + 1] : l;
                            bb.putShort(l);
                            bb.putShort(r);
                            long t = (count[0] + i) * 1_000_000L / fmt[0];
                            int col = (int) Math.min(PEAKS - 1, t * PEAKS / dur);
                            float v = Math.max(Math.abs(l), Math.abs(r)) / 32768f;
                            if (v > peaks[col]) peaks[col] = v;
                        }
                        o.write(bb.array());
                        count[0] += n;
                    }
                }, (f, w) -> main.post(() -> prog.setProgress((int) (f * 500))));
                o.close();
                rate = fmt[0];
                frames = count[0];
                main.post(() -> {
                    dsp = new KaraokeDsp(rate);
                    apply(dsp);
                    dsp.mode = live.mode;
                    laneO.peaks = peaks;
                    wave.durMs = Math.max(1, frames * 1000 / rate);
                    wave.message = null;
                    wave.invalidate();
                    ready = true;
                    status.setText(mono ? T.t("This song is mono (one channel): the voice can't be separated from the music.")
                            : T.t("Working out the voice and music layers…"));
                    refreshButtons();
                    recomputeLayers();
                });
            } catch (final Exception e) {
                main.post(() -> {
                    status.setText("⚠ " + e.getMessage());
                    wave.message = T.t("Can't read this song");
                    wave.invalidate();
                });
            }
        }).start();
    }

    /** Voice / Music waveforms for the current settings (background; restarts when settings change). */
    private void recomputeLayers() {
        if (!ready || mono) return;
        final int gen = ++layerGen;
        final KaraokeDsp a = new KaraokeDsp(rate);
        apply(a);
        new Thread(() -> {
            float[] pv = new float[PEAKS], pm = new float[PEAKS];
            try (RandomAccessFile f = new RandomAccessFile(pcm, "r")) {
                int chunk = 4096;
                byte[] buf = new byte[chunk * 4];
                short[] s = new short[chunk * 2];
                float[] two = new float[2];
                long pos = 0;
                while (pos < frames) {
                    if (gen != layerGen || isFinishing()) return;
                    int n = (int) Math.min(chunk, frames - pos);
                    f.readFully(buf, 0, n * 4);
                    ByteBuffer.wrap(buf, 0, n * 4).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s, 0, n * 2);
                    a.peaks(s, n, two);
                    long outPos = Math.max(0, pos - KaraokeDsp.LATENCY);      // align with the original
                    int col = (int) Math.min(PEAKS - 1, outPos * PEAKS / Math.max(1, frames));
                    pv[col] = Math.max(pv[col], two[0]);
                    pm[col] = Math.max(pm[col], two[1]);
                    pos += n;
                    if ((pos / chunk) % 40 == 0) {
                        final float pr = pos / (float) frames;
                        main.post(() -> prog.setProgress(500 + (int) (pr * 500)));
                    }
                }
            } catch (Exception e) {
                return;
            }
            main.post(() -> {
                if (gen != layerGen) return;
                laneV.peaks = pv;
                laneM.peaks = pm;
                wave.invalidate();
                prog.setProgress(1000);
                status.setText(T.t("Ready — play and switch Karaoke ON / OFF to compare. Tap the waveform to jump."));
            });
        }).start();
    }

    // ------------------------------------------------------------------ live playback
    private void setMode(int m) {
        live().mode = m;
        refreshButtons();
    }

    private void refreshButtons() {
        int m = live().mode;
        for (int i = 0; i < 4; i++) modeBtns[i].setBackground(i == m ? Ui.gradient(this, 18) : Ui.card(this, 0xFF24242C, 0, 18));
        boolean on = m != KaraokeDsp.ORIGINAL;
        abBtn.setText(T.t("Karaoke") + ":  " + T.t(on ? "ON" : "OFF") + "   ·   " + T.t("tap to compare"));
        abBtn.setBackground(on ? Ui.card(this, 0xFF0E7490, 0, 26) : Ui.card(this, 0xFF2A2A33, 0, 26));
        playBtn.setText(playing ? T.t("Pause") : T.t("Play"));
        playBtn.setCompoundDrawablesWithIntrinsicBounds(new UiKit.Icon(this, playing ? "pause" : "play", 20, Color.WHITE), null, null, null);
        playBtn.setEnabled(ready);
    }

    private void seekTo(long frame) {
        playFrame = Math.max(0, Math.min(frames, frame));
        seekPending = true;
        wave.setPlay(cur() * 1000 / Math.max(1, rate));
        updateTime();
    }

    private void updateTime() {
        timeT.setText(WaveView.time(cur() * 1000 / Math.max(1, rate), false) + " / " + WaveView.time(frames * 1000 / Math.max(1, rate), false));
    }

    private void togglePlay() {
        if (!ready) return;
        if (playing) {
            playing = false;
            refreshButtons();
            return;
        }
        if (MusicService.playing()) MusicService.action(this, MusicService.A_TOGGLE);   // don't play two things at once
        playing = true;
        refreshButtons();
        player = new Thread(this::playLoop, "karaoke-play");
        player.start();
        main.post(tick);
    }

    private final Runnable tick = new Runnable() {
        public void run() {
            if (!playing) return;
            wave.setPlay(cur() * 1000 / Math.max(1, rate));
            updateTime();
            main.postDelayed(this, 50);
        }
    };

    @SuppressWarnings("deprecation")
    private void playLoop() {
        int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack at = new AudioTrack(AudioManager.STREAM_MUSIC, rate, AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 8192 * 4), AudioTrack.MODE_STREAM);
        try (RandomAccessFile f = new RandomAccessFile(pcm, "r")) {
            at.play();
            int chunk = 2048;
            byte[] buf = new byte[chunk * 4];
            short[] s = new short[chunk * 2];
            dsp.reset();
            f.seek(playFrame * 4);
            seekPending = false;
            while (playing) {
                if (seekPending) {
                    seekPending = false;
                    at.pause();
                    at.flush();
                    dsp.reset();
                    f.seek(playFrame * 4);
                    at.play();
                }
                if (playFrame >= frames) {
                    playing = false;
                    break;
                }
                int n = (int) Math.min(chunk, frames - playFrame);
                f.readFully(buf, 0, n * 4);
                ByteBuffer.wrap(buf, 0, n * 4).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s, 0, n * 2);
                dsp.process(s, n);
                at.write(s, 0, n * 2);
                playFrame += n;
            }
        } catch (Exception ignored) {
            playing = false;
        } finally {
            try {
                at.stop();
            } catch (Exception ignored) {
            }
            at.release();
            main.post(this::refreshButtons);
        }
    }

    // ------------------------------------------------------------------ export the whole song
    private Button exportBtn(String label, final int mode, boolean primary) {
        Button b = Ui.button(this, "💾 " + label, primary);
        b.setTextSize(13);
        b.setOnClickListener(v -> export(mode, label));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1);
        lp.setMargins(dp(3), 0, dp(3), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void export(final int mode, final String label) {
        if (!ready) return;
        playing = false;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(8), dp(22), 0);
        final ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        box.addView(bar);
        AudioTools.cancel = false;
        final AlertDialog d = new UiDialog(this).setTitle(T.t("Saving") + ": " + label).setView(box).setCancelable(false)
                .setNegativeButton(T.t("Cancel"), (x, w) -> AudioTools.cancel = true).show();
        final KaraokeDsp e = new KaraokeDsp(rate);
        apply(e);
        e.mode = mode;
        new Thread(() -> {
            String msg;
            File out = AudioTools.temp(this, ".m4a");
            AudioTools.AacWriter w = null;
            try (RandomAccessFile f = new RandomAccessFile(pcm, "r")) {
                w = new AudioTools.AacWriter(out, rate, 2, 192_000);
                int chunk = 4096;
                byte[] buf = new byte[chunk * 4];
                short[] s = new short[chunk * 2];
                long pos = 0, skip = KaraokeDsp.LATENCY;      // drop the engine's start delay so it lines up
                while (pos < frames) {
                    if (AudioTools.cancel) throw new Exception(T.t("Cancelled"));
                    int n = (int) Math.min(chunk, frames - pos);
                    f.readFully(buf, 0, n * 4);
                    ByteBuffer.wrap(buf, 0, n * 4).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s, 0, n * 2);
                    e.process(s, n);
                    int from = (int) Math.min(n, skip);
                    skip -= from;
                    if (n - from > 0) {
                        short[] part = new short[(n - from) * 2];
                        System.arraycopy(s, from * 2, part, 0, part.length);
                        w.write(part, n - from);
                    }
                    pos += n;
                    final float pr = pos / (float) frames;
                    if ((pos / chunk) % 20 == 0) main.post(() -> bar.setProgress((int) (pr * 1000)));
                }
                short[] tail = e.flush();
                w.write(tail, tail.length / 2);
                w.finish();
                w = null;
                String suffix = mode == KaraokeDsp.MUSIC ? " (karaoke)" : mode == KaraokeDsp.VOICE ? " (voice)" : " (karaoke + guide voice)";
                msg = "✓ " + T.t("Saved") + ": " + AudioTools.publish(this, out, AudioTools.safe(songTitle) + suffix + ".m4a")
                        + "\n\n" + T.t("Find it in Music → Folders → ZH Play.");
            } catch (Exception ex) {
                if (w != null) w.close();
                //noinspection ResultOfMethodCallIgnored
                out.delete();
                msg = "⚠ " + ex.getMessage();
            }
            final String m2 = msg;
            main.post(() -> {
                try {
                    d.dismiss();
                } catch (Exception ignored) {
                }
                new UiDialog(this).setTitle(label).setMessage(m2).setPositiveButton(T.t("OK"), null).show();
            });
        }).start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        playing = false;
    }

    @Override
    protected void onDestroy() {
        playing = false;
        layerGen++;
        AudioTools.cancel = true;
        if (pcm != null) {
            //noinspection ResultOfMethodCallIgnored
            pcm.delete();
        }
        super.onDestroy();
    }
}
