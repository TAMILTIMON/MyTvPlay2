package com.mytv.player;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.audiofx.Visualizer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

/** Equalizer like Poweramp: curve + live spectrum, glowing band sliders, tone knobs, effects. */
public class PowerEqActivity extends AppCompatActivity {
    private static final int BG = 0xFF0B0B0F, PANEL = 0xFF16161C;
    private static final int[] BAND_COL = {0xFFEF4444, 0xFFF97316, 0xFFF59E0B, 0xFFEAB308, 0xFF84CC16, 0xFF22C55E, 0xFF14B8A6,
            0xFF06B6D4, 0xFF3B82F6, 0xFF8B5CF6};

    private final Handler main = new Handler(Looper.getMainLooper());
    private PowerViews.EqCurveView curve;
    private FrameLayout body;
    private int tab;
    private Button presetBtn, onBtn;
    private final View[] tabBtns = new View[3];
    private Visualizer viz;
    private TextView spectrumHint;

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    private TextView txt(int sp, int color, boolean bold) {
        TextView t = Ui.text(this, sp, color, bold);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private GradientDrawable round(int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(r));
        return g;
    }

    @Override
    protected void onCreate(Bundle b) {
        Ui.apply(this);
        super.onCreate(b);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        ScrollView sv = new ScrollView(this);
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(12), dp(14), dp(12), dp(110));
        sv.addView(p);
        root.addView(sv);

        // top: back · title · on/off · preset
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(UiKit.darkButton(this, "arrow-left", 42, false, v -> finish()));
        TextView title = Ui.text(this, 20, Color.WHITE, true);
        title.setText("  " + T.t("Equalizer"));
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        onBtn = new Button(this);
        onBtn.setAllCaps(false);
        onBtn.setTextColor(Color.WHITE);
        onBtn.setTextSize(13);
        onBtn.setOnClickListener(v -> {
            MusicFx.setOn(!MusicFx.on());
            refreshTop();
        });
        top.addView(onBtn, new LinearLayout.LayoutParams(dp(70), dp(38)));
        p.addView(top);

        // tabs: Equalizer · Tone · Effects (icons like Poweramp)
        LinearLayout tabs = new LinearLayout(this);
        tabs.setBackground(round(PANEL, 24));
        tabs.setPadding(dp(6), dp(6), dp(6), dp(6));
        String[][] t = {{"sliders-vertical", "Equalizer"}, {"disc-3", "Tone"}, {"audio-waveform", "Effects"}};
        for (int i = 0; i < 3; i++) {
            final int k = i;
            LinearLayout tb = new LinearLayout(this);
            tb.setGravity(Gravity.CENTER);
            tb.addView(UiKit.icon(this, t[i][0], 22, Color.WHITE));
            TextView tl = Ui.text(this, 13, Color.WHITE, true);
            tl.setText("  " + T.t(t[i][1]));
            tb.addView(tl);
            tb.setContentDescription(T.t(t[i][1]));
            tb.setClickable(true);
            tb.setFocusable(true);
            tb.setOnClickListener(v -> showTab(k));
            tabs.addView(tb, new LinearLayout.LayoutParams(0, dp(44), 1));
            tabBtns[i] = tb;
        }
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.setMargins(0, dp(12), 0, dp(10));
        p.addView(tabs, tlp);

        // curve + live spectrum
        curve = new PowerViews.EqCurveView(this);
        p.addView(curve, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)));
        LinearLayout pr = new LinearLayout(this);
        pr.setGravity(Gravity.CENTER_VERTICAL);
        pr.setPadding(0, dp(8), 0, dp(4));
        spectrumHint = Ui.text(this, 11, 0x99FFFFFF, false);
        spectrumHint.setOnClickListener(v -> askSpectrum());
        pr.addView(spectrumHint, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        presetBtn = new Button(this);
        presetBtn.setAllCaps(false);
        presetBtn.setTextColor(Color.WHITE);
        presetBtn.setTextSize(13);
        presetBtn.setBackground(round(0xFF24242C, 20));
        presetBtn.setCompoundDrawablesWithIntrinsicBounds(new UiKit.Icon(this, "list", 16, Color.WHITE), null,
                new UiKit.Icon(this, "chevron-down", 16, Color.WHITE), null);
        presetBtn.setCompoundDrawablePadding(dp(8));
        presetBtn.setPadding(dp(14), 0, dp(10), 0);
        presetBtn.setOnClickListener(v -> pickPreset());
        pr.addView(presetBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
        p.addView(pr);

        body = new FrameLayout(this);
        p.addView(body);
        buildMini(root);
        setContentView(root);
        refreshTop();
        showTab(Store.getInt("peq_tab", 0));
        updateSpectrumHint();
    }

    private void refreshTop() {
        boolean on = MusicFx.on();
        onBtn.setText(on ? "ON" : "OFF");
        onBtn.setBackground(on ? Ui.gradient(this, 19) : round(0xFF2A2A33, 19));
        presetBtn.setText(MusicFx.presetLabel());
        curve.invalidate();
    }

    private void changed() {
        MusicFx.apply();
        curve.invalidate();
        presetBtn.setText(MusicFx.presetLabel());
    }

    private void showTab(int k) {
        tab = k;
        Store.putInt("peq_tab", k);
        for (int i = 0; i < 3; i++) tabBtns[i].setBackground(i == k ? round(0xFF2E2E38, 18) : null);
        body.removeAllViews();
        body.addView(k == 0 ? buildEq() : k == 1 ? buildTone() : buildFx());
    }

    // ------------------------------------------------------------------ Equalizer tab: preamp + 10 glowing sliders
    private View column(String name, final int color, int value, int min, int max, final PowerViews.OnValue cb, final boolean preamp) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(4), dp(10), dp(4), dp(10));
        int tint = preamp ? 0xFF1C1C22 : (color & 0x00FFFFFF) | 0x2E000000;
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{tint, 0x10000000 | (tint & 0xFFFFFF)});
        g.setCornerRadius(dp(20));
        col.setBackground(g);
        final TextView val = txt(11, Color.WHITE, true);
        final PowerViews.GlowSlider s = new PowerViews.GlowSlider(this, min, max, preamp ? 0xFFE5E7EB : color);
        s.setValue(value);
        val.setText(String.format(java.util.Locale.US, "%+.1f", value / 100f));
        s.setListener((v, done) -> {
            val.setText(String.format(java.util.Locale.US, "%+.1f", v / 100f));
            cb.on(v, done);
        });
        s.setContentDescription(name);
        col.addView(s, new LinearLayout.LayoutParams(dp(46), dp(250)));
        TextView n = txt(13, Color.WHITE, true);
        n.setText(name);
        n.setPadding(0, dp(6), 0, dp(2));
        col.addView(n);
        val.setBackground(round(0x33000000, 8));
        val.setPadding(dp(6), dp(2), dp(6), dp(2));
        col.addView(val);
        return col;
    }

    private View buildEq() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        hs.addView(row);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(62), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(6), 0);
        row.addView(column(T.t("Preamp"), 0xFFE5E7EB, MusicFx.preamp(), -1200, 1200, (v, done) -> {
            MusicFx.setPreamp(v);
            changed();
        }, true), lp);
        final int[] bands = MusicFx.bands();
        for (int i = 0; i < 10; i++) {
            final int k = i;
            row.addView(column(MusicFx.LABELS[i], BAND_COL[i], bands[i], MusicFx.MIN, MusicFx.MAX, (v, done) -> {
                int[] nb = MusicFx.bands();
                nb[k] = v;
                MusicFx.setBands(nb);
                Store.putStr("mfx_user_cur", "");
                Store.putInt("mfx_preset", -1);   // own setting → "Custom"
                changed();
            }, false), lp);
        }
        box.addView(hs);
        LinearLayout acts = new LinearLayout(this);
        acts.setPadding(0, dp(12), 0, 0);
        acts.addView(chip("rotate-ccw", T.t("Reset"), v -> {
            MusicFx.preset(0);
            MusicFx.setPreamp(0);
            changed();
            showTab(0);
        }));
        acts.addView(chip("save", T.t("Save preset"), v -> savePreset()));
        TextView hint = Ui.text(this, 11, 0x80FFFFFF, false);
        hint.setText(MusicFx.advanced() ? "  " + T.t("Double-tap a slider = 0 dB") : "  " + T.t("Basic equalizer on this Android version"));
        acts.addView(hint);
        box.addView(acts);
        return box;
    }

    private Button chip(String icon, String label, View.OnClickListener l) {
        Button c = new Button(this);
        c.setAllCaps(false);
        c.setText(label);
        c.setTextSize(13);
        c.setTextColor(Color.WHITE);
        c.setBackground(round(0xFF24242C, 18));
        c.setCompoundDrawablesWithIntrinsicBounds(new UiKit.Icon(this, icon, 16, Color.WHITE), null, null, null);
        c.setCompoundDrawablePadding(dp(6));
        c.setPadding(dp(12), 0, dp(14), 0);
        c.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
        lp.setMargins(0, 0, dp(8), 0);
        c.setLayoutParams(lp);
        return c;
    }

    // ------------------------------------------------------------------ Tone tab: Bass & Treble knobs, bass boost, balance
    private View knobBox(String name, int color, int min, int max, int def, int value, final boolean bipolar, final String unit,
                         final PowerViews.OnValue cb, int sizeDp) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView n = txt(14, Color.WHITE, true);
        n.setText(name);
        col.addView(n);
        final TextView val = txt(12, 0xB3FFFFFF, false);
        col.addView(val);
        PowerViews.Knob k = new PowerViews.Knob(this, min, max, def, color, bipolar);
        k.setValue(value);
        k.setContentDescription(name);
        final java.util.Locale L = java.util.Locale.US;
        val.setText(unit.isEmpty() ? String.valueOf(value) : String.format(L, "%d%s", value, unit));
        k.setListener((v, done) -> {
            val.setText(unit.isEmpty() ? String.valueOf(v) : String.format(L, "%d%s", v, unit));
            cb.on(v, done);
        });
        col.addView(k, new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return col;
    }

    private View buildTone() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(round(PANEL, 22));
        box.setPadding(dp(10), dp(16), dp(10), dp(16));
        LinearLayout r1 = new LinearLayout(this);
        r1.setGravity(Gravity.CENTER);
        r1.addView(knobBox(T.t("Bass"), 0xFFEF4444, -100, 100, 0, MusicFx.bassTone(), true, "%", (v, d) -> {
            MusicFx.setTone(v, MusicFx.trebleTone());
            Store.putStr("mfx_user_cur", "");
            changed();
        }, 140), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        r1.addView(knobBox(T.t("Treble"), 0xFF22C55E, -100, 100, 0, MusicFx.trebleTone(), true, "%", (v, d) -> {
            MusicFx.setTone(MusicFx.bassTone(), v);
            Store.putStr("mfx_user_cur", "");
            changed();
        }, 140), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        box.addView(r1);
        LinearLayout r2 = new LinearLayout(this);
        r2.setGravity(Gravity.CENTER);
        r2.setPadding(0, dp(18), 0, 0);
        r2.addView(knobBox(T.t("Bass boost"), 0xFF3B82F6, 0, 100, 0, Store.getInt("mfx_bass", 0) / 10, false, "%", (v, d) -> {
            Store.putInt("mfx_bass", v * 10);
            MusicFx.apply();
        }, 96), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        r2.addView(knobBox(T.t("Balance"), 0xFFEAB308, -100, 100, 0, Store.getInt("mfx_balance", 0), true, "", (v, d) -> {
            Store.putInt("mfx_balance", v);
            MusicService.refreshBalance();
        }, 96), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        box.addView(r2);
        TextView h = Ui.text(this, 11, 0x80FFFFFF, false);
        h.setText(T.t("Drag a knob up / down. Double-tap = reset. Balance: left − / right +"));
        h.setGravity(Gravity.CENTER);
        h.setPadding(0, dp(14), 0, 0);
        box.addView(h);
        return box;
    }

    // ------------------------------------------------------------------ Effects tab
    private View buildFx() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(round(PANEL, 22));
        box.setPadding(dp(12), dp(14), dp(12), dp(16));
        LinearLayout r1 = new LinearLayout(this);
        r1.setGravity(Gravity.CENTER);
        r1.addView(knobBox(T.t("Limiter"), 0xFFF97316, -120, 0, -10, MusicFx.limiterTh(), false, "", (v, d) -> {
            Store.putInt("mfx_lim_th", v);
            Store.putBool("mfx_lim", true);
            MusicFx.apply();
        }, 96), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        r1.addView(knobBox(T.t("Stereo width"), 0xFF06B6D4, 0, 100, 0, Store.getInt("mfx_virt", 0) / 10, false, "%", (v, d) -> {
            Store.putInt("mfx_virt", v * 10);
            MusicFx.apply();
        }, 96), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        r1.addView(knobBox(T.t("Loudness"), 0xFFD946EF, 0, 100, 0, Store.getInt("mfx_loud", 0) / 10, false, "%", (v, d) -> {
            Store.putInt("mfx_loud", v * 10);
            MusicFx.apply();
        }, 96), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        box.addView(r1);
        Button lim = chip(MusicFx.limiterOn() ? "check" : "x", T.t("Limiter") + ": " + T.t(MusicFx.limiterOn() ? "On" : "Off"), null);
        lim.setOnClickListener(v -> {
            Store.putBool("mfx_lim", !MusicFx.limiterOn());
            MusicFx.apply();
            showTab(2);
        });
        LinearLayout lr = new LinearLayout(this);
        lr.setGravity(Gravity.CENTER);
        lr.setPadding(0, dp(10), 0, dp(4));
        lr.addView(lim);
        box.addView(lr);
        TextView lh = Ui.text(this, 11, 0x80FFFFFF, false);
        lh.setText(T.t("Limiter stops loud songs and strong boosts from distorting (ceiling in tenths of a dB)."));
        lh.setGravity(Gravity.CENTER);
        box.addView(lh);

        box.addView(section(T.t("Reverb")));
        final String[] rv = {"Off", "Small room", "Medium room", "Large room", "Medium hall", "Large hall", "Plate"};
        box.addView(chipRow(rv, Store.getInt("mfx_rev", 0), i -> {
            Store.putInt("mfx_rev", i);
            MusicFx.apply();
            showTab(2);
        }));
        box.addView(section(T.t("Tempo")));
        final float[] sp = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};
        String[] sn = {"0.5×", "0.75×", "1×", "1.25×", "1.5×", "2×"};
        int cur = 2;
        for (int i = 0; i < sp.length; i++) if (Math.abs(sp[i] - MusicService.speed) < 0.01f) cur = i;
        box.addView(chipRow(sn, cur, i -> {
            MusicService.setSpeed(sp[i]);
            showTab(2);
        }));
        return box;
    }

    private TextView section(String s) {
        TextView t = Ui.text(this, 12, 0xB3FFFFFF, true);
        t.setText(s.toUpperCase(java.util.Locale.ROOT));
        t.setLetterSpacing(0.12f);
        t.setPadding(dp(4), dp(18), 0, dp(8));
        return t;
    }

    private interface Pick {
        void on(int i);
    }

    private View chipRow(String[] names, int sel, final Pick cb) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout r = new LinearLayout(this);
        hs.addView(r);
        for (int i = 0; i < names.length; i++) {
            final int k = i;
            Button c = chip(i == sel ? "check" : "activity", T.t(names[i]), v -> cb.on(k));
            if (i == sel) c.setBackground(Ui.gradient(this, 18));
            r.addView(c);
        }
        return hs;
    }

    // ------------------------------------------------------------------ presets
    private void pickPreset() {
        final List<String> mine = MusicFx.userPresets();
        String[] n = new String[MusicFx.PRESETS.length + mine.size()];
        for (int i = 0; i < mine.size(); i++) n[i] = "★ " + mine.get(i);
        for (int i = 0; i < MusicFx.PRESETS.length; i++) n[mine.size() + i] = T.t(MusicFx.PRESETS[i]);
        new UiDialog(this).setTitle(T.t("Presets")).setItems(n, (d, w) -> {
            if (w < mine.size()) {
                final String name = mine.get(w);
                new UiDialog(this).setTitle(name).setItems(new String[]{T.t("Use"), T.t("Delete")}, (d2, w2) -> {
                    if (w2 == 0) MusicFx.loadUser(name);
                    else MusicFx.deleteUser(name);
                    changed();
                    showTab(tab);
                }).show();
            } else {
                MusicFx.preset(w - mine.size());
                changed();
                showTab(tab);
            }
        }).show();
    }

    private void savePreset() {
        final EditText e = new EditText(this);
        e.setHint(T.t("Preset name"));
        e.setSingleLine(true);
        new UiDialog(this).setTitle(T.t("Save preset")).setView(e).setPositiveButton(T.t("Save"), (d, w) -> {
            String n = e.getText().toString().trim();
            if (n.isEmpty()) return;
            MusicFx.saveUser(n);
            refreshTop();
        }).setNegativeButton(T.t("Cancel"), null).show();
    }

    // ------------------------------------------------------------------ live spectrum (Android's audio visualizer)
    private void updateSpectrumHint() {
        boolean ok = Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        spectrumHint.setText(ok ? "  " + T.t("Live spectrum") : "  " + T.t("Tap to show the live spectrum"));
        if (ok) startSpectrum();
    }

    private void askSpectrum() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            new UiDialog(this).setTitle(T.t("Live spectrum"))
                    .setMessage(T.t("Android only lets apps draw the moving music bars with the \"microphone\" permission. ZH Play reads only the song's own sound — nothing is recorded or sent."))
                    .setPositiveButton(T.t("Allow"), (d, w) -> requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 51))
                    .setNegativeButton(T.t("Later"), null).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        super.onRequestPermissionsResult(req, p, r);
        updateSpectrumHint();
    }

    private void startSpectrum() {
        stopSpectrum();
        int s = MusicFx.session();
        if (s == 0) return;
        try {
            viz = new Visualizer(s);
            viz.setCaptureSize(Visualizer.getCaptureSizeRange()[1] >= 1024 ? 1024 : Visualizer.getCaptureSizeRange()[1]);
            viz.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                public void onWaveFormDataCapture(Visualizer v, byte[] w, int rate) {
                }

                public void onFftDataCapture(Visualizer v, byte[] fft, int rate) {
                    int n = fft.length / 2;
                    float[] out = new float[48];
                    for (int i = 0; i < 48; i++) {
                        // log-spaced bins from ~30 Hz to ~16 kHz
                        int a = (int) Math.max(1, Math.round(Math.pow(n, i / 48.0)));
                        int bb = (int) Math.max(a + 1, Math.round(Math.pow(n, (i + 1) / 48.0)));
                        float m = 0;
                        for (int k = a; k < bb && k < n; k++) {
                            float re = fft[2 * k], im = fft[2 * k + 1];
                            m = Math.max(m, (float) Math.hypot(re, im));
                        }
                        out[i] = Math.min(1f, (float) (Math.log10(1 + m) / 2.2));
                    }
                    curve.setSpectrum(out);
                }
            }, Visualizer.getMaxCaptureRate() / 2, false, true);
            viz.setEnabled(true);
        } catch (Throwable t) {
            viz = null;
        }
    }

    private void stopSpectrum() {
        if (viz != null) {
            try {
                viz.setEnabled(false);
                viz.release();
            } catch (Throwable ignored) {
            }
        }
        viz = null;
        curve.setSpectrum(null);
    }

    // ------------------------------------------------------------------ mini player
    private LinearLayout mini;
    private TextView miniT;
    private ImageView miniP, miniA;
    private final Runnable miniUpd = this::renderMini;

    private void buildMini(FrameLayout root) {
        mini = new LinearLayout(this);
        mini.setGravity(Gravity.CENTER_VERTICAL);
        mini.setPadding(dp(10), dp(8), dp(10), dp(8));
        mini.setBackground(round(0xF01A1A22, 22));
        mini.setElevation(dp(8));
        miniA = new ImageView(this);
        miniA.setScaleType(ImageView.ScaleType.CENTER_CROP);
        miniA.setBackground(round(0xFF2A2A33, 10));
        miniA.setClipToOutline(true);
        mini.addView(miniA, new LinearLayout.LayoutParams(dp(44), dp(44)));
        miniT = Ui.text(this, 14, Color.WHITE, true);
        miniT.setSingleLine(true);
        miniT.setEllipsize(android.text.TextUtils.TruncateAt.END);
        miniT.setPadding(dp(12), 0, dp(8), 0);
        mini.addView(miniT, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        miniP = UiKit.darkButton(this, "pause", 44, true, v -> MusicService.action(this, MusicService.A_TOGGLE));
        mini.addView(miniP);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        lp.setMargins(dp(12), 0, dp(12), dp(14));
        root.addView(mini, lp);
    }

    private void renderMini() {
        PhoneMedia.M m = MusicService.current();
        mini.setVisibility(m == null ? View.GONE : View.VISIBLE);
        if (m == null) return;
        miniT.setText(m.title + "  ·  " + m.sub);
        Bitmap b = PhoneMedia.cached(m);
        if (b != null) miniA.setImageBitmap(b);
        else miniA.setImageDrawable(new UiKit.Icon(this, "list-music", 20, Color.WHITE));
        UiKit.setIcon(this, miniP, MusicService.playing() ? "pause" : "play", 20, Color.WHITE);
        if (viz == null && MusicFx.session() != 0) updateSpectrumHint();
    }

    @Override
    protected void onResume() {
        super.onResume();
        MusicService.listen(miniUpd);
        renderMini();
        updateSpectrumHint();
    }

    @Override
    protected void onPause() {
        super.onPause();
        MusicService.unlisten(miniUpd);
        stopSpectrum();
    }
}
