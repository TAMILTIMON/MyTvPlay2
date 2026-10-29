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
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
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
import java.nio.MappedByteBuffer;
import java.nio.ShortBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;

/** DJ mixer: two decks (waveform, BPM, cue, hot cues, loops, pitch, sync), 3-band EQ + filter + echo, crossfader, recording. */
public class DjActivity extends AppCompatActivity {
    private static final int OUT = 44100, BLOCK = 512;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final DjCore.Deck[] deck = {new DjCore.Deck(OUT), new DjCore.Deck(OUT)};
    private final DeckUi[] ui = new DeckUi[2];
    private volatile float xfade = 0f, masterDb = 0f;
    private volatile boolean running = true;
    private Thread audio;
    private SeekBar xf;

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    // ------------------------------------------------------------------ PCM straight from storage (no big memory use)
    static final class Mapped implements DjCore.Source {
        final ShortBuffer sb;
        final long n;
        final int rate;

        Mapped(File f, int rate) throws Exception {
            try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
                MappedByteBuffer m = r.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, r.length());
                m.order(ByteOrder.LITTLE_ENDIAN);
                sb = m.asShortBuffer();
                n = r.length() / 4;
            }
            this.rate = rate;
        }

        public long frames() {
            return n;
        }

        public int rate() {
            return rate;
        }

        public short l(long i) {
            return sb.get((int) (i * 2));
        }

        public short r(long i) {
            return sb.get((int) (i * 2 + 1));
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        Ui.apply(this);
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0A0A0E);
        root.setPadding(dp(10), dp(12), dp(10), 0);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(UiKit.darkButton(this, "arrow-left", 40, false, v -> finish()));
        TextView t = Ui.text(this, 19, Color.WHITE, true);
        t.setText("  " + T.t("DJ mixer"));
        top.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        recBtn = new Button(this);
        recBtn.setAllCaps(false);
        recBtn.setTextColor(Color.WHITE);
        recBtn.setOnClickListener(v -> toggleRec());
        top.addView(Orient.button(this, "dj_orient"));
        top.addView(recBtn, new LinearLayout.LayoutParams(dp(110), dp(40)));
        root.addView(top);
        stage = new android.widget.FrameLayout(this);
        root.addView(stage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        ui[0] = new DeckUi(0, 0xFF22D3EE);
        ui[1] = new DeckUi(1, 0xFFF472B6);
        mixerView = mixer();
        arrange();
        restoreLists();                      // each deck's song list from last time
        setContentView(root);
        Orient.apply(this, "dj_orient");
        refreshRec();
        if (MusicService.playing()) MusicService.action(this, MusicService.A_TOGGLE);
        audio = new Thread(this::audioLoop, "dj-audio");
        audio.setPriority(Thread.MAX_PRIORITY);
        audio.start();
        main.post(tick);
    }

    // ------------------------------------------------------------------ portrait (stacked) / landscape (side by side)
    private android.widget.FrameLayout stage;
    private View mixerView;

    /** Portrait: Deck A, mixer, Deck B under each other. Landscape: Deck A | Mixer | Deck B. Music keeps playing. */
    private void arrange() {
        View[] parts = {ui[0].card, mixerView, ui[1].card};
        for (View v : parts) {
            if (v.getParent() != null) ((ViewGroup) v.getParent()).removeView(v);
        }
        stage.removeAllViews();
        if (Orient.landscape(this)) {
            LinearLayout row = new LinearLayout(this);
            row.setPadding(0, dp(6), 0, 0);
            float[] weights = {1f, 0.9f, 1f};
            for (int i = 0; i < 3; i++) {
                ScrollView sc = new ScrollView(this);
                LinearLayout col = new LinearLayout(this);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setPadding(dp(3), 0, dp(3), dp(20));
                col.addView(parts[i]);
                sc.addView(col);
                row.addView(sc, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weights[i]));
            }
            stage.addView(row);
        } else {
            ScrollView sv = new ScrollView(this);
            LinearLayout p = new LinearLayout(this);
            p.setOrientation(LinearLayout.VERTICAL);
            p.setPadding(0, dp(8), 0, dp(30));
            for (View v : parts) p.addView(v);
            sv.addView(p);
            stage.addView(sv);
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        arrange();                         // same screen, re-arranged: decks keep playing
    }

    // ------------------------------------------------------------------ one deck
    final class DeckUi {
        final int i;
        final int color;
        final LinearLayout card;
        final WaveView wave;
        final WaveView.Lane lane;
        final TextView name, bpm, time, pitchT;
        final Button play;
        final Button[] hot = new Button[4];
        SeekBar pitch;
        float range = 0.08f;
        TextView listTitle;
        LinearLayout listRows;
        JogWheel jog;

        DeckUi(final int i, int color) {
            this.i = i;
            this.color = color;
            card = new LinearLayout(DjActivity.this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(10), dp(10), dp(10), dp(10));
            card.setBackground(Ui.card(DjActivity.this, 0xFF15151C, (color & 0x00FFFFFF) | 0x55000000, 18));
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cl.setMargins(0, dp(6), 0, dp(6));
            card.setLayoutParams(cl);
            LinearLayout h = new LinearLayout(DjActivity.this);
            h.setGravity(Gravity.CENTER_VERTICAL);
            TextView tag = Ui.text(DjActivity.this, 15, color, true);
            tag.setText(i == 0 ? "A" : "B");
            tag.setPadding(dp(4), 0, dp(10), 0);
            h.addView(tag);
            LinearLayout col = new LinearLayout(DjActivity.this);
            col.setOrientation(LinearLayout.VERTICAL);
            name = Ui.text(DjActivity.this, 14, Color.WHITE, true);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setText(T.t("Tap Load to choose a song"));
            time = Ui.text(DjActivity.this, 11, 0xB3FFFFFF, false);
            col.addView(name);
            col.addView(time);
            h.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            bpm = Ui.text(DjActivity.this, 18, color, true);
            bpm.setText("— BPM");
            h.addView(bpm);
            h.addView(small("Load", v -> load(i)));
            card.addView(h);

            wave = new WaveView(DjActivity.this);
            lane = wave.lane("", color);
            wave.onSeek = ms -> {
                DjCore.Deck d = deck[i];
                if (d.src != null) d.pos = ms * d.src.rate() / 1000.0;
            };
            LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(70));
            wl.setMargins(0, dp(8), 0, dp(4));
            card.addView(wave, wl);
            // the jog wheel / scratch disc
            jog = new JogWheel(DjActivity.this, deck[i], color);
            LinearLayout.LayoutParams jl = new LinearLayout.LayoutParams(dp(170), dp(170));
            jl.gravity = Gravity.CENTER_HORIZONTAL;
            jl.setMargins(0, dp(4), 0, dp(2));
            card.addView(jog, jl);
            TextView jh = Ui.text(DjActivity.this, 10, 0x88FFFFFF, false);
            jh.setText(T.t("centre = scratch · ring = nudge"));
            jh.setGravity(Gravity.CENTER);
            card.addView(jh);

            LinearLayout r1 = new LinearLayout(DjActivity.this);
            play = small("Play", v -> {
                DjCore.Deck d = deck[i];
                if (d.src == null) return;
                d.playing = !d.playing;
            });
            r1.addView(play);
            r1.addView(small("Cue", v -> cue(i)));
            r1.addView(small("Sync", v -> {
                DjCore.Deck d = deck[i], o = deck[1 - i];
                if (d.bpm <= 0 || o.bpm <= 0) {
                    toast(T.t("BPM not found yet"));
                    return;
                }
                d.syncTo(o);
                pitch.setProgress(toProgress(d.pitch));
            }));
            for (int k = 0; k < 4; k++) {
                final int kk = k;
                Button hc = small(String.valueOf(k + 1), v -> hotCue(i, kk));
                hc.setOnLongClickListener(v -> {
                    deck[i].hot[kk] = -1;
                    paintHot();
                    return true;
                });
                hot[k] = hc;
                r1.addView(hc);
            }
            card.addView(r1);

            LinearLayout r2 = new LinearLayout(DjActivity.this);
            TextView lt = Ui.text(DjActivity.this, 11, 0xB3FFFFFF, true);
            lt.setText(T.t("LOOP") + " ");
            r2.addView(lt);
            for (final double beats : new double[]{1, 2, 4, 8}) {
                r2.addView(small(beats == 1 ? "1" : String.valueOf((int) beats), v -> {
                    DjCore.Deck d = deck[i];
                    if (d.looping() && Math.abs(d.loopB - d.loopA - beats * d.beatLen()) < 2) d.loopOff();
                    else d.loopBeats(beats);
                }));
            }
            r2.addView(small("Off", v -> deck[i].loopOff()));
            card.addView(r2);

            LinearLayout r3 = new LinearLayout(DjActivity.this);
            r3.setGravity(Gravity.CENTER_VERTICAL);
            Button nm = small("−", null);
            Button np = small("+", null);
            hold(nm, i, 0.96f);
            hold(np, i, 1.04f);
            r3.addView(nm);
            pitch = new SeekBar(DjActivity.this);
            pitch.setMax(1000);
            pitch.setProgress(500);
            pitch.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar s, int v, boolean u) {
                    if (u) deck[i].pitch = 1f + (v - 500) / 500f * range;
                }

                public void onStartTrackingTouch(SeekBar s) { }

                public void onStopTrackingTouch(SeekBar s) { }
            });
            r3.addView(pitch, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            r3.addView(np);
            pitchT = Ui.text(DjActivity.this, 12, Color.WHITE, true);
            pitchT.setOnClickListener(v -> {     // tap = reset · long press = ±8 % / ±16 %
                deck[i].pitch = 1f;
                pitch.setProgress(500);
            });
            pitchT.setOnLongClickListener(v -> {
                range = range < 0.1f ? 0.16f : 0.08f;
                pitch.setProgress(toProgress(deck[i].pitch));
                toast(T.t("Pitch range") + " ±" + Math.round(range * 100) + " %");
                return true;
            });
            r3.addView(pitchT, new LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT));
            card.addView(r3);
            // ---- this deck's song list (scrolls on its own)
            LinearLayout lh = new LinearLayout(DjActivity.this);
            lh.setGravity(Gravity.CENTER_VERTICAL);
            lh.setPadding(0, dp(8), 0, dp(2));
            listTitle = Ui.text(DjActivity.this, 12, 0xB3FFFFFF, true);
            lh.addView(listTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            lh.addView(small("＋ " + T.t("Add songs"), v -> load(i)));
            card.addView(lh);
            final ScrollView ls = new ScrollView(DjActivity.this);
            ls.setOnTouchListener((v, e) -> {                     // the list scrolls, not the whole screen
                v.getParent().requestDisallowInterceptTouchEvent(true);
                return false;
            });
            listRows = new LinearLayout(DjActivity.this);
            listRows.setOrientation(LinearLayout.VERTICAL);
            ls.addView(listRows);
            card.addView(ls, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170)));
        }

        int toProgress(float p) {
            return Math.max(0, Math.min(1000, Math.round(500 + (p - 1f) / range * 500)));
        }

        void paintHot() {
            for (int k = 0; k < 4; k++) {
                hot[k].setBackground(deck[i].hot[k] >= 0 ? Ui.card(DjActivity.this, color, 0, 12) : Ui.card(DjActivity.this, 0xFF26262E, 0, 12));
                hot[k].setTextColor(deck[i].hot[k] >= 0 ? Color.BLACK : Color.WHITE);
            }
        }
    }

    private Button small(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(T.t(label));
        b.setTextSize(12);
        b.setTextColor(Color.WHITE);
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setBackground(Ui.card(this, 0xFF26262E, 0, 12));
        if (l != null) b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(38), 1);
        lp.setMargins(dp(2), dp(2), dp(2), dp(2));
        b.setLayoutParams(lp);
        return b;
    }

    /** Hold to push / pull the speed a little (beat matching by ear). */
    private void hold(Button b, final int i, final float factor) {
        b.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) deck[i].bend = factor;
            else if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) deck[i].bend = 1f;
            return false;
        });
    }

    private void cue(int i) {
        DjCore.Deck d = deck[i];
        if (d.src == null) return;
        if (d.playing) {          // playing: back to the cue point and stop (CDJ style)
            d.playing = false;
            d.pos = d.cue;
        } else {
            if (Math.abs(d.pos - d.cue) < 50) d.playing = true;   // at the cue: play
            else d.cue = (long) d.pos;                             // stopped elsewhere: set the cue here
        }
    }

    private void hotCue(int i, int k) {
        DjCore.Deck d = deck[i];
        if (d.src == null) return;
        if (d.hot[k] < 0) d.hot[k] = (long) d.pos;
        else {
            d.pos = d.hot[k];
            d.playing = true;
        }
        ui[i].paintHot();
    }

    // ------------------------------------------------------------------ mixer
    private View mixer() {
        LinearLayout m = new LinearLayout(this);
        m.setOrientation(LinearLayout.VERTICAL);
        m.setBackground(Ui.card(this, 0xFF121218, 0x33FFFFFF, 18));
        m.setPadding(dp(8), dp(10), dp(8), dp(12));
        LinearLayout row = new LinearLayout(this);
        for (int i = 0; i < 2; i++) {
            final int k = i;
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            int c = k == 0 ? 0xFF22D3EE : 0xFFF472B6;
            LinearLayout k1 = new LinearLayout(this), k2 = new LinearLayout(this);
            k1.addView(knob("Trim", c, -12, 12, 0, false, v -> deck[k].trimDb = v));
            k1.addView(knob("High", c, -40, 6, 0, true, v -> deck[k].highDb = v));
            k1.addView(knob("Mid", c, -40, 6, 0, true, v -> deck[k].midDb = v));
            k2.addView(knob("Low", c, -40, 6, 0, true, v -> deck[k].lowDb = v));
            k2.addView(knob("Filter", 0xFFEAB308, -100, 100, 0, true, v -> deck[k].filter = v / 100f));
            k2.addView(knob("Echo", 0xFFA78BFA, 0, 100, 0, false, v -> deck[k].echo = v / 100f));
            col.addView(k1);
            col.addView(k2);
            PowerViews.GlowSlider vol = new PowerViews.GlowSlider(this, 0, 100, c);
            vol.setValue(100);
            vol.setContentDescription(T.t("Volume") + " " + (k == 0 ? "A" : "B"));
            vol.setListener((v, d) -> deck[k].volume = v / 100f);
            col.addView(vol, new LinearLayout.LayoutParams(dp(46), dp(150)));
            row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        m.addView(row);
        TextView xt = Ui.text(this, 11, 0xB3FFFFFF, true);
        xt.setText("A        " + T.t("CROSSFADER") + "        B");
        xt.setGravity(Gravity.CENTER);
        xt.setPadding(0, dp(10), 0, 0);
        m.addView(xt);
        xf = new SeekBar(this);
        xf.setMax(1000);
        xf.setProgress(500);
        xf.setContentDescription(T.t("Crossfader"));
        xf.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int v, boolean u) {
                xfade = (v - 500) / 500f;
            }

            public void onStartTrackingTouch(SeekBar s) {
                autoFading = false;
            }

            public void onStopTrackingTouch(SeekBar s) { }
        });
        m.addView(xf);
        LinearLayout r = new LinearLayout(this);
        r.addView(small("Centre", v -> xf.setProgress(500)));
        r.addView(small("Auto-fade", v -> autoFade()));
        autoDjBtn = small("Auto DJ: " + T.t("Off"), v -> setAutoDj(!autoDj));
        r.addView(autoDjBtn);
        r.addView(knob("Master", 0xFFE5E7EB, -12, 6, 0, false, v -> masterDb = v));
        m.addView(r);
        return m;
    }

    private interface IntSet {
        void set(int v);
    }

    private View knob(String name, int color, int min, int max, int def, boolean bipolar, final IntSet set) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        final TextView v = Ui.text(this, 10, 0xB3FFFFFF, false);
        v.setGravity(Gravity.CENTER);
        PowerViews.Knob k = new PowerViews.Knob(this, min, max, def, color, bipolar);
        k.setContentDescription(T.t(name));
        k.setListener((val, d) -> {
            set.set(val);
            v.setText(T.t(name) + (min <= -40 && val <= -39 ? " KILL" : ""));
        });
        v.setText(T.t(name));
        c.addView(k, new LinearLayout.LayoutParams(dp(52), dp(52)));
        c.addView(v);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        c.setLayoutParams(lp);
        return c;
    }

    private volatile boolean autoFading;

    /** Moves the crossfader to the other side over 8 bars of the playing tempo (or 10 s). */
    private void autoFade() {
        final float from = xfade, to = from <= 0 ? 1f : -1f;
        DjCore.Deck d = deck[from <= 0 ? 0 : 1];
        final long ms = d.bpm > 0 ? (long) (32 * 60000 / d.liveBpm()) : 10000;
        final long t0 = System.currentTimeMillis();
        autoFading = true;
        DjCore.Deck next = deck[from <= 0 ? 1 : 0];
        if (next.src != null && !next.playing) next.playing = true;
        main.post(new Runnable() {
            public void run() {
                if (!autoFading) return;
                float f = Math.min(1f, (System.currentTimeMillis() - t0) / (float) ms);
                float x = from + (to - from) * f;
                xf.setProgress(Math.round(500 + x * 500));
                if (f < 1f) main.postDelayed(this, 40);
                else autoFading = false;
            }
        });
        toast(T.t("Auto-fade") + ": " + (ms / 1000) + " s");
    }

    // ------------------------------------------------------------------ each deck's song list
    @SuppressWarnings("unchecked")
    private final List<PhoneMedia.M>[] lists = new List[]{new ArrayList<PhoneMedia.M>(), new ArrayList<PhoneMedia.M>()};
    private final int[] loadedIdx = {-1, -1};            // which list song is on the deck
    private final boolean[] preparing = new boolean[2], ready = new boolean[2];
    private boolean autoDj, autoStart;
    private Button autoDjBtn;

    /** Full-screen picker: tick several songs (search, select all) → "Add N songs"; or Files… / Folder… from storage. */
    private void load(final int i) {
        final List<PhoneMedia.M> all = PhoneMedia.query(this, true);
        final android.app.Dialog dlg = new android.app.Dialog(this);
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0F0F14);
        root.setPadding(dp(12), dp(10), dp(12), dp(10));
        // top: title + search + select all / clear
        TextView tt = Ui.text(this, 17, Color.WHITE, true);
        tt.setText(T.t("Add songs to deck") + " " + (i == 0 ? "A" : "B"));
        root.addView(tt);
        LinearLayout sr = new LinearLayout(this);
        sr.setGravity(Gravity.CENTER_VERTICAL);
        final EditText q = new EditText(this);
        q.setHint("🔍 " + T.t("Search"));
        q.setSingleLine(true);
        q.setTextColor(Color.WHITE);
        q.setHintTextColor(0x88FFFFFF);
        sr.addView(q, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final List<PhoneMedia.M> shown = new ArrayList<>();
        final java.util.LinkedHashSet<PhoneMedia.M> picked = new java.util.LinkedHashSet<>();
        final android.widget.ListView lv = new android.widget.ListView(this);
        lv.setChoiceMode(android.widget.ListView.CHOICE_MODE_MULTIPLE);
        final Button addBtn = small("", null);
        final Runnable paintAdd = () -> {
            addBtn.setText("＋ " + T.t("Add") + " " + picked.size() + " " + T.t("songs to deck") + " " + (i == 0 ? "A" : "B"));
            addBtn.setEnabled(!picked.isEmpty());
            addBtn.setAlpha(picked.isEmpty() ? 0.5f : 1f);
        };
        sr.addView(small(T.t("Select all"), v -> {
            for (int k = 0; k < shown.size(); k++) {
                lv.setItemChecked(k, true);
                picked.add(shown.get(k));
            }
            paintAdd.run();
        }));
        sr.addView(small(T.t("Clear"), v -> {
            picked.clear();
            lv.clearChoices();
            lv.invalidateViews();
            paintAdd.run();
        }));
        root.addView(sr);
        // the songs, with tick boxes
        final android.widget.ArrayAdapter<String> ad = new android.widget.ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_multiple_choice, new ArrayList<String>()) {
            @Override
            public View getView(int pos, View cv, ViewGroup parent) {
                View v = super.getView(pos, cv, parent);
                if (v instanceof TextView) ((TextView) v).setTextColor(Color.WHITE);
                return v;
            }
        };
        lv.setAdapter(ad);
        final Runnable fill = () -> {
            ad.clear();
            shown.clear();
            String f = q.getText().toString().toLowerCase(Locale.ROOT);
            for (PhoneMedia.M m : all) {
                if (!f.isEmpty() && !m.title.toLowerCase(Locale.ROOT).contains(f) && !m.sub.toLowerCase(Locale.ROOT).contains(f)) continue;
                shown.add(m);
                ad.add(m.title + "  ·  " + m.sub);
            }
            for (int k = 0; k < shown.size(); k++) lv.setItemChecked(k, picked.contains(shown.get(k)));
        };
        fill.run();
        q.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence c, int a, int b, int d) { }

            public void onTextChanged(CharSequence c, int a, int b, int d) { }

            public void afterTextChanged(android.text.Editable e) {
                fill.run();
            }
        });
        lv.setOnItemClickListener((pa, v, pos, id) -> {
            PhoneMedia.M m = shown.get(pos);
            if (lv.isItemChecked(pos)) picked.add(m);
            else picked.remove(m);
            paintAdd.run();
        });
        if (all.isEmpty()) {
            TextView none = Ui.text(this, 13, 0x99FFFFFF, false);
            none.setText(T.t("No music found in the phone's music library — use Files… or Folder… below."));
            none.setPadding(dp(6), dp(20), dp(6), dp(20));
            root.addView(none);
        }
        root.addView(lv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        // bottom bar — always on screen
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dp(6), 0, 0);
        bar.addView(small("📄 " + T.t("Files…"), v -> {
            dlg.dismiss();
            pickFor = i;
            startActivityForResult(new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("audio/*")
                    .putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true), 81);
        }));
        bar.addView(small("📁 " + T.t("Folder…"), v -> {
            dlg.dismiss();
            pickFor = i;
            startActivityForResult(new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE), 82);
        }));
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        bar.addView(small(T.t("Cancel"), v -> dlg.dismiss()));
        root.addView(bar);
        addBtn.setBackground(Ui.gradient(this, 14));
        addBtn.setOnClickListener(v -> {
            if (picked.isEmpty()) return;
            dlg.dismiss();
            addToList(i, new ArrayList<>(picked));
        });
        root.addView(addBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        paintAdd.run();
        dlg.setContentView(root);
        if (dlg.getWindow() != null) dlg.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        dlg.show();
    }

    private int pickFor;

    private void addToList(int i, List<PhoneMedia.M> songs) {
        if (songs.isEmpty()) {
            toast(T.t("No songs found there"));
            return;
        }
        boolean empty = deck[i].src == null && lists[i].isEmpty();
        lists[i].addAll(songs);
        saveLists();
        paintList(i);
        toast(songs.size() + " " + T.t("songs added to deck") + " " + (i == 0 ? "A" : "B"));
        if (empty) decode(i, lists[i].get(0), 0, false);          // the first one straight onto the deck
    }

    /** Files… (several) or Folder… (all its songs, sub-folders too) from the phone's storage. */
    @Override
    protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        if ((req != 81 && req != 82) || res != RESULT_OK || data == null) return;
        final int i = pickFor;
        final int flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION;
        if (req == 81) {
            List<android.net.Uri> us = new ArrayList<>();
            if (data.getClipData() != null) for (int k = 0; k < data.getClipData().getItemCount(); k++) us.add(data.getClipData().getItemAt(k).getUri());
            else if (data.getData() != null) us.add(data.getData());
            List<PhoneMedia.M> songs = new ArrayList<>();
            for (android.net.Uri u : us) {
                try {
                    getContentResolver().takePersistableUriPermission(u, flags);    // still works next time
                } catch (Exception ignored) {
                }
                songs.add(song(u, LocalShare.displayName(this, u), ""));
            }
            addToList(i, songs);
            return;
        }
        final android.net.Uri tree = data.getData();
        if (tree == null) return;
        try {
            getContentResolver().takePersistableUriPermission(tree, flags);
        } catch (Exception ignored) {
        }
        toast(T.t("Reading the folder…"));
        new Thread(() -> {
            final List<PhoneMedia.M> songs = new ArrayList<>();
            String rootId = android.provider.DocumentsContract.getTreeDocumentId(tree);
            walk(tree, rootId, "", songs, 0);
            java.util.Collections.sort(songs, (x, y) -> (x.sub + "/" + x.title).compareToIgnoreCase(y.sub + "/" + y.title));
            main.post(() -> addToList(i, songs));
        }).start();
    }

    private static final java.util.Set<String> AUDIO_EXT = new java.util.HashSet<>(java.util.Arrays.asList(
            "mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "wma", "amr", "mka", "aiff", "aif", "ape", "alac"));

    /** All songs in a folder and its sub-folders (up to 6 levels, 3000 songs). */
    private void walk(android.net.Uri tree, String docId, String folder, List<PhoneMedia.M> out, int depth) {
        if (depth > 6 || out.size() >= 3000) return;
        android.net.Uri kids = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        String[] cols = {android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME, android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE};
        try (android.database.Cursor c = getContentResolver().query(kids, cols, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
                if (android.provider.DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    walk(tree, id, name, out, depth + 1);
                } else {
                    String ext = name == null || !name.contains(".") ? "" : name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
                    if ((mime != null && mime.startsWith("audio/")) || AUDIO_EXT.contains(ext)) {
                        out.add(song(android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id), name, folder));
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static PhoneMedia.M song(android.net.Uri u, String name, String folder) {
        PhoneMedia.M m = new PhoneMedia.M();
        m.uri = u;
        String n = name == null ? "Song" : name;
        m.title = n.contains(".") ? n.substring(0, n.lastIndexOf('.')) : n;
        m.sub = folder == null || folder.isEmpty() ? T.t("from storage") : folder;
        return m;
    }

    /** The rows under a deck: ▶ for the song on the deck; tap = load it; hold = options. */
    private void paintList(final int i) {
        DeckUi u = ui[i];
        if (u == null || u.listRows == null) return;
        List<PhoneMedia.M> l = lists[i];
        u.listTitle.setText(T.t("Songs") + " (" + l.size() + ")" + (autoDj ? "  ·  Auto DJ" : ""));
        u.listRows.removeAllViews();
        if (l.isEmpty()) {
            TextView t = Ui.text(this, 12, 0x88FFFFFF, false);
            t.setText(T.t("Add several songs — Auto DJ plays them one after another, crossfading A ⇄ B."));
            t.setPadding(dp(4), dp(6), dp(4), dp(6));
            u.listRows.addView(t);
            return;
        }
        for (int k = 0; k < l.size(); k++) {
            final int idx = k;
            PhoneMedia.M m = l.get(k);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(6), dp(6), dp(6), dp(6));
            boolean on = k == loadedIdx[i];
            row.setBackground(on ? Ui.card(this, (u.color & 0x00FFFFFF) | 0x33000000, u.color, 10) : Ui.focusCard(this, 10));
            TextView n = Ui.text(this, 12, on ? u.color : 0x88FFFFFF, true);
            n.setText(on ? "▶" : String.valueOf(k + 1));
            n.setGravity(Gravity.CENTER);
            row.addView(n, new LinearLayout.LayoutParams(dp(26), ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView t = Ui.text(this, 13, Color.WHITE, on);
            t.setText(m.title);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            TextView sb = Ui.text(this, 11, 0x88FFFFFF, false);
            sb.setText(m.sub);
            sb.setSingleLine(true);
            sb.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(t);
            col.addView(sb);
            row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.setOnClickListener(v -> decode(i, lists[i].get(idx), idx, false));
            row.setOnLongClickListener(v -> {
                songMenu(i, idx);
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(2), 0, dp(2));
            u.listRows.addView(row, lp);
        }
    }

    private void songMenu(final int i, final int idx) {
        final List<PhoneMedia.M> l = lists[i];
        String other = i == 0 ? "B" : "A";
        new UiDialog(this).setTitle(l.get(idx).title).setItems(new String[]{"▶  " + T.t("Load on this deck"), "⏭  " + T.t("Play next"),
                "↑  " + T.t("Move up"), "↓  " + T.t("Move down"), "⇄  " + T.t("Move to deck") + " " + other, "🗑  " + T.t("Remove")}, (d, w) -> {
            PhoneMedia.M m = l.get(idx);
            int cur = loadedIdx[i];
            switch (w) {
                case 0: decode(i, m, idx, false); return;
                case 1: {
                    l.remove(idx);
                    if (idx < cur) cur--;
                    l.add(Math.min(l.size(), cur + 1), m);
                    loadedIdx[i] = cur;
                    break;
                }
                case 2:
                    if (idx > 0) {
                        l.add(idx - 1, l.remove(idx));
                        if (cur == idx) loadedIdx[i] = idx - 1;
                        else if (cur == idx - 1) loadedIdx[i] = idx;
                    }
                    break;
                case 3:
                    if (idx < l.size() - 1) {
                        l.add(idx + 1, l.remove(idx));
                        if (cur == idx) loadedIdx[i] = idx + 1;
                        else if (cur == idx + 1) loadedIdx[i] = idx;
                    }
                    break;
                case 4:
                    l.remove(idx);
                    if (idx < cur) loadedIdx[i] = cur - 1;
                    else if (idx == cur) loadedIdx[i] = -1;
                    lists[1 - i].add(m);
                    paintList(1 - i);
                    break;
                default:
                    l.remove(idx);
                    if (idx < cur) loadedIdx[i] = cur - 1;
                    else if (idx == cur) loadedIdx[i] = -1;
            }
            saveLists();
            paintList(i);
        }).show();
    }

    private void saveLists() {
        try {
            for (int i = 0; i < 2; i++) {
                org.json.JSONArray a = new org.json.JSONArray();
                for (PhoneMedia.M m : lists[i]) a.put(new org.json.JSONObject().put("u", m.uri.toString()).put("t", m.title).put("s", m.sub));
                Store.putStr("dj_list_" + i, a.toString());
            }
        } catch (Exception ignored) {
        }
    }

    private void restoreLists() {
        for (int i = 0; i < 2; i++) {
            lists[i].clear();
            try {
                org.json.JSONArray a = new org.json.JSONArray(Store.getStr("dj_list_" + i, "[]"));
                for (int k = 0; k < a.length(); k++) {
                    PhoneMedia.M m = new PhoneMedia.M();
                    m.uri = android.net.Uri.parse(a.getJSONObject(k).getString("u"));
                    m.title = a.getJSONObject(k).optString("t");
                    m.sub = a.getJSONObject(k).optString("s");
                    lists[i].add(m);
                }
            } catch (Exception ignored) {
            }
            paintList(i);
        }
    }

    // ------------------------------------------------------------------ Auto DJ: A ⇄ B, one song after another
    private void setAutoDj(boolean on) {
        autoDj = on;
        autoDjBtn.setText("Auto DJ: " + T.t(on ? "On" : "Off"));
        autoDjBtn.setBackground(on ? Ui.gradient(this, 14) : Ui.card(this, 0xFF26262E, 0, 14));
        paintList(0);
        paintList(1);
        main.removeCallbacks(autoTick);
        if (!on) return;
        int live = xfade <= 0 ? 0 : 1;
        if (!deck[0].playing && !deck[1].playing) {             // nothing playing yet: start the side you hear
            if (deck[live].src != null) {
                deck[live].playing = true;
                ui[live].play.setText("❚❚");
            } else if (!lists[live].isEmpty()) {
                autoStart = true;
                decode(live, lists[live].get(0), 0, false);
            } else {
                toast(T.t("Add songs to a deck first"));
            }
        }
        main.post(autoTick);
        toast("Auto DJ: " + T.t("the next song comes in with a crossfade near the end of each song"));
    }

    /** Fade length: 8 bars (32 beats) when the tempo is known, else 10 s — the same as Auto-fade. */
    private long fadeMs(DjCore.Deck d) {
        return d.bpm > 0 ? (long) (32 * 60000 / d.liveBpm()) : 10000;
    }

    /** The next song for deck `to`: its own list first, else the other deck's list. {list, index} or null. */
    private int[] nextFor(int to) {
        if (loadedIdx[to] + 1 < lists[to].size()) return new int[]{to, loadedIdx[to] + 1};
        int from = 1 - to;
        if (loadedIdx[from] + 1 < lists[from].size()) return new int[]{from, loadedIdx[from] + 1};
        return null;
    }

    private final Runnable autoTick = new Runnable() {
        public void run() {
            if (!autoDj) return;
            main.postDelayed(this, 400);
            if (autoFading) return;
            int live = xfade <= 0 ? 0 : 1, other = 1 - live;
            DjCore.Deck L = deck[live];
            if (L.src == null || !L.playing) return;
            long remain = (long) ((L.length() - L.pos) * 1000.0 / Math.max(1, L.src.rate() * L.pitch));
            long fade = Math.min(30000, fadeMs(L));
            // 1. about half a minute before: get the next song ready on the other deck
            if (remain < fade + 30000 && !ready[other] && !preparing[other]) {
                int[] nx = nextFor(other);
                if (nx != null) {
                    PhoneMedia.M m = lists[nx[0]].get(nx[1]);
                    if (nx[0] != other) {                       // taken from the other side's list: moves to this deck
                        lists[nx[0]].remove(nx[1]);
                        lists[other].add(Math.max(0, loadedIdx[other] + 1), m);
                        paintList(nx[0]);
                        saveLists();
                        decode(other, m, Math.max(0, loadedIdx[other] + 1), true);
                    } else decode(other, m, nx[1], true);
                }
            }
            // 2. at the end: the other deck comes in on its first beat, in tempo, with the crossfade
            if (remain <= fade + 300 && ready[other]) {
                DjCore.Deck O = deck[other];
                if (O.bpm > 0 && L.bpm > 0) O.syncTo(L);
                O.pos = O.cue;
                O.playing = true;
                ui[other].play.setText("❚❚");
                if (ui[other].pitch != null) ui[other].pitch.setProgress(ui[other].toProgress(O.pitch));
                ready[other] = false;
                autoFade();
            }
        }
    };

    private void decode(final int i, final PhoneMedia.M m) {
        decode(i, m, -1, false);
    }

    /** idx: its place in deck i's list (−1 = not from the list); auto: prepared by Auto DJ (don't stop the deck). */
    private void decode(final int i, final PhoneMedia.M m, final int idx, final boolean auto) {
        final DjCore.Deck dk = deck[i];
        dk.playing = false;
        ui[i].play.setText("▶");
        preparing[i] = auto;
        ready[i] = false;
        final DeckUi u = ui[i];
        u.name.setText(m.title);
        u.bpm.setText("… BPM");
        u.wave.message = T.t("Reading the song…");
        u.wave.invalidate();
        new Thread(() -> {
            try {
                File f = new File(getCacheDir(), "dj_deck" + i + "_" + System.nanoTime() + ".pcm");
                final float[] peaks = new float[3000];
                final long dur = Math.max(1, AudioTools.durationUs(this, m.uri));
                final int[] fmt = {44100, 2};
                final long[] count = {0};
                try (BufferedOutputStream o = new BufferedOutputStream(new FileOutputStream(f), 1 << 16)) {
                    AudioTools.decode(this, m.uri, 0, 0, new AudioTools.PcmSink() {
                        public void format(int r, int ch) {
                            fmt[0] = r;
                            fmt[1] = ch;
                        }

                        public void pcm(short[] data, int n, long pts) throws Exception {
                            int ch = fmt[1];
                            ByteBuffer bb = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN);
                            for (int k = 0; k < n; k++) {
                                short l = data[k * ch], r = ch > 1 ? data[k * ch + 1] : l;
                                bb.putShort(l);
                                bb.putShort(r);
                                long t = (count[0] + k) * 1_000_000L / fmt[0];
                                int col = (int) Math.min(peaks.length - 1, t * peaks.length / dur);
                                float v = Math.max(Math.abs(l), Math.abs(r)) / 32768f;
                                if (v > peaks[col]) peaks[col] = v;
                            }
                            o.write(bb.array());
                            count[0] += n;
                        }
                    }, (fr, w) -> main.post(() -> u.wave.message = T.t("Reading the song…") + " " + Math.round(fr * 100) + " %"));
                }
                final Mapped src = new Mapped(f, fmt[0]);
                f.deleteOnExit();
                main.post(() -> {
                    dk.src = src;
                    dk.pos = 0;
                    dk.cue = 0;
                    dk.loopOff();
                    java.util.Arrays.fill(dk.hot, -1);
                    dk.bpm = 0;
                    u.lane.peaks = peaks;
                    u.wave.durMs = Math.max(1, src.frames() * 1000 / src.rate());
                    u.wave.message = null;
                    u.wave.setZoom(Math.max(1f, u.wave.durMs / 20000f));    // about 20 s visible
                    u.paintHot();
                    u.wave.invalidate();
                });
                final double[] bpm = DjCore.detectBpm(src, src.rate() * 120L);   // first 2 minutes
                main.post(() -> {
                    dk.bpm = bpm[0];
                    dk.firstBeat = bpm[1];
                    dk.cue = (long) bpm[1];
                    if (idx >= 0) {
                        loadedIdx[i] = idx;
                        paintList(i);
                    }
                    if (preparing[i]) {
                        preparing[i] = false;
                        ready[i] = true;                // Auto DJ starts it at the right moment
                    }
                    if (autoStart) {                    // Auto DJ was switched on with nothing playing
                        autoStart = false;
                        dk.pos = dk.cue;
                        dk.playing = true;
                        ui[i].play.setText("❚❚");
                    }
                });
            } catch (final Exception e) {
                main.post(() -> {
                    u.wave.message = "⚠ " + e.getMessage();
                    u.wave.invalidate();
                    preparing[i] = false;
                    if (idx >= 0 && autoDj) loadedIdx[i] = idx;      // Auto DJ skips a song that can't be read
                });
            }
        }).start();
    }

    // ------------------------------------------------------------------ audio
    private final LinkedBlockingQueue<short[]> recQ = new LinkedBlockingQueue<>();
    private volatile boolean recording;
    private long recStart;
    private Button recBtn;
    private Thread recThread;

    @SuppressWarnings("deprecation")
    private void audioLoop() {
        int min = AudioTrack.getMinBufferSize(OUT, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack at = new AudioTrack(AudioManager.STREAM_MUSIC, OUT, AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(min, BLOCK * 4 * 4), AudioTrack.MODE_STREAM);
        float[] tl = new float[BLOCK], tr = new float[BLOCK];
        short[] out = new short[BLOCK * 2];
        try {
            at.play();
            while (running) {
                DjCore.mix(deck[0], deck[1], xfade, masterDb, tl, tr, out, BLOCK);
                at.write(out, 0, out.length);
                if (recording) recQ.offer(out.clone());
            }
        } catch (Exception ignored) {
        } finally {
            try {
                at.stop();
            } catch (Exception ignored) {
            }
            at.release();
        }
    }

    private void toggleRec() {
        if (!recording) {
            recQ.clear();
            recording = true;
            recStart = System.currentTimeMillis();
            final File out = AudioTools.temp(this, ".m4a");
            recThread = new Thread(() -> {
                AudioTools.AacWriter w = null;
                try {
                    w = new AudioTools.AacWriter(out, OUT, 2, 192_000);
                    while (recording || !recQ.isEmpty()) {
                        short[] s = recQ.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                        if (s != null) w.write(s, s.length / 2);
                    }
                    w.finish();
                    w = null;
                    String name = "DJ mix " + new java.text.SimpleDateFormat("d MMM yyyy HH.mm", Locale.getDefault()).format(new java.util.Date());
                    final String saved = AudioTools.publish(this, out, AudioTools.safe(name) + ".m4a");
                    main.post(() -> new UiDialog(this).setTitle(T.t("Mix saved"))
                            .setMessage("✓ " + saved + "\n\n" + T.t("Find it in Music → Folders → ZH Play.")).setPositiveButton(T.t("OK"), null).show());
                } catch (final Exception e) {
                    if (w != null) w.close();
                    main.post(() -> toast("⚠ " + e.getMessage()));
                }
            }, "dj-rec");
            recThread.start();
        } else recording = false;
        refreshRec();
    }

    private void refreshRec() {
        if (recording) {
            long s = (System.currentTimeMillis() - recStart) / 1000;
            recBtn.setText(String.format(Locale.US, "● %d:%02d", s / 60, s % 60));
            recBtn.setBackground(Ui.card(this, 0xFFDC2626, 0, 20));
        } else {
            recBtn.setText("● " + T.t("REC"));
            recBtn.setBackground(Ui.card(this, 0xFF26262E, 0, 20));
        }
    }

    // ------------------------------------------------------------------ screen updates
    private final Runnable tick = new Runnable() {
        public void run() {
            if (!running) return;
            for (int i = 0; i < 2; i++) {
                DjCore.Deck d = deck[i];
                DeckUi u = ui[i];
                if (d.src != null) {
                    long ms = (long) (d.pos * 1000 / d.src.rate());
                    u.wave.setPlay(ms);
                    long rem = Math.max(0, u.wave.durMs - ms);
                    u.time.setText(WaveView.time(ms, false) + "  ·  −" + WaveView.time(rem, false) + (d.looping() ? "  ·  LOOP" : ""));
                    if (u.jog != null) u.jog.invalidate();          // the platter turns with the music
                    u.bpm.setText(d.bpm > 0 ? String.format(Locale.US, "%.1f BPM", d.liveBpm()) : "… BPM");
                }
                u.play.setText(T.t(d.playing ? "Pause" : "Play"));
                u.play.setBackground(Ui.card(DjActivity.this, d.playing ? u.color : 0xFF26262E, 0, 12));
                u.play.setTextColor(d.playing ? Color.BLACK : Color.WHITE);
                u.pitchT.setText(String.format(Locale.US, "%+.1f%%", (d.pitch - 1f) * 100));
                u.card.setElevation(dp(2) + d.peak * dp(10));
            }
            if (recording) refreshRec();
            main.postDelayed(this, 60);
        }
    };

    private void toast(String s) {
        UiKit.makeToast(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        running = false;
        recording = false;
        super.onDestroy();
    }
}
