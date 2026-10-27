package com.mytv.player;

import android.media.audiofx.BassBoost;
import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.Equalizer;
import android.media.audiofx.LoudnessEnhancer;
import android.media.audiofx.PresetReverb;
import android.media.audiofx.Virtualizer;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Poweramp-style sound engine for the phone music player.
 * Android 9+: 10-band EQ + preamp + limiter (DynamicsProcessing). Older phones: the basic equalizer.
 * Plus tone (bass / treble), bass boost, stereo width, loudness, reverb and balance.
 * Gains are in millibels (100 mB = 1 dB).
 */
public final class MusicFx {
    private MusicFx() {
    }

    public static final int[] FREQS = {31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
    public static final String[] LABELS = {"31", "62", "125", "250", "500", "1K", "2K", "4K", "8K", "16K"};
    /** Upper edge of each band (half-way between centres on a log scale). */
    private static final float[] CUTOFF = {44, 88, 177, 354, 707, 1414, 2828, 5657, 11314, 20000};
    public static final int MIN = -1500, MAX = 1500;   // ±15 dB per band

    public static final String[] PRESETS = {"Flat", "Bass boost", "Bass extreme", "Treble boost", "Vocal / Dialogue", "Rock",
            "Pop", "Jazz", "Classical", "Dance / EDM", "Hip-hop", "Tamil melody", "Kuthu / Folk", "Acoustic", "Electronic",
            "Headphones", "Car", "Small speaker", "Loudness (V)", "Podcast / Speech"};
    private static final int[][] P = {
            {0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
            {600, 550, 450, 250, 50, 0, 0, 0, 0, 0},
            {1000, 900, 700, 400, 100, 0, -100, 0, 0, 0},
            {0, 0, 0, 0, 0, 100, 250, 450, 600, 650},
            {-300, -250, -100, 100, 350, 450, 400, 250, 100, 0},
            {500, 400, 250, -50, -200, -100, 150, 350, 450, 500},
            {-150, -50, 150, 350, 450, 350, 150, 0, -50, -100},
            {350, 250, 100, 200, -150, -150, 0, 150, 300, 400},
            {450, 350, 250, 150, -100, -100, 0, 200, 300, 400},
            {650, 550, 200, 0, -100, 100, 300, 400, 450, 400},
            {550, 450, 150, 300, -100, -100, 150, 0, 200, 300},
            {150, 150, 100, 200, 350, 400, 300, 200, 200, 150},
            {700, 600, 450, 200, 0, 150, 300, 350, 300, 250},
            {300, 300, 200, 50, 150, 200, 300, 350, 250, 150},
            {450, 400, 100, 0, -200, 200, 50, 100, 400, 500},
            {400, 250, 0, -100, -50, 50, 200, 350, 250, 100},
            {500, 400, 250, 0, -100, 0, 150, 250, 300, 300},
            {800, 700, 500, 250, 0, 0, 100, 200, 250, 200},
            {600, 450, 200, -50, -250, -250, -50, 200, 450, 600},
            {-600, -400, -150, 100, 300, 450, 450, 300, 0, -300}};

    // ------------------------------------------------------------------ settings
    public static boolean on() {
        return Store.getBool("mfx_on", true);
    }

    public static void setOn(boolean v) {
        Store.putBool("mfx_on", v);
        apply();
    }

    public static int[] bands() {
        int[] b = new int[10];
        try {
            JSONArray a = new JSONArray(Store.getStr("mfx_bands", "[0,0,0,0,0,0,0,0,0,0]"));
            if (a.length() == 5) {
                // settings from the old 5-band equalizer → spread over 10 bands
                int[] map = {0, 0, 1, 1, 2, 2, 3, 3, 4, 4};
                for (int i = 0; i < 10; i++) b[i] = a.getInt(map[i]);
            } else for (int i = 0; i < 10 && i < a.length(); i++) b[i] = a.getInt(i);
        } catch (Exception ignored) {
        }
        return b;
    }

    public static void setBands(int[] b) {
        JSONArray a = new JSONArray();
        for (int v : b) a.put(Math.max(MIN, Math.min(MAX, v)));
        Store.putStr("mfx_bands", a.toString());
    }

    public static void preset(int i) {
        setBands(P[i].clone());
        Store.putInt("mfx_preset", i);
        Store.putStr("mfx_user_cur", "");
    }

    public static int presetIndex() {
        return Store.getInt("mfx_preset", 0);
    }

    /** Preamp −12 … +12 dB (mB). */
    public static int preamp() {
        return Store.getInt("mfx_pre", 0);
    }

    public static void setPreamp(int mb) {
        Store.putInt("mfx_pre", Math.max(-1200, Math.min(1200, mb)));
    }

    /** Tone knobs, −100 … +100 % (of ±12 dB shelves). */
    public static int bassTone() {
        return Store.getInt("mfx_tbass", 0);
    }

    public static int trebleTone() {
        return Store.getInt("mfx_ttreble", 0);
    }

    public static void setTone(int bass, int treble) {
        Store.putInt("mfx_tbass", Math.max(-100, Math.min(100, bass)));
        Store.putInt("mfx_ttreble", Math.max(-100, Math.min(100, treble)));
    }

    public static boolean limiterOn() {
        return Store.getBool("mfx_lim", true);
    }

    /** Limiter ceiling in tenths of a dB (−120 … 0). */
    public static int limiterTh() {
        return Store.getInt("mfx_lim_th", -10);
    }

    public static boolean advanced() {
        return Build.VERSION.SDK_INT >= 28;
    }

    private static final float[] W_BASS = {1f, 0.95f, 0.75f, 0.45f, 0.15f, 0, 0, 0, 0, 0};
    private static final float[] W_TREB = {0, 0, 0, 0, 0, 0.1f, 0.35f, 0.7f, 0.92f, 1f};

    /** What each band really does (band + tone), in mB. */
    public static int[] effective() {
        int[] b = bands();
        int tb = bassTone(), tt = trebleTone();
        for (int i = 0; i < 10; i++) b[i] = Math.round(b[i] + tb * 12f * W_BASS[i] + tt * 12f * W_TREB[i]);
        return b;
    }

    /** Smooth response at a frequency (for the curve on screen), dB, incl. preamp. */
    public static float responseDb(float hz, int[] eff) {
        double lx = Math.log(Math.max(20, Math.min(20000, hz)));
        double[] lf = new double[10];
        for (int i = 0; i < 10; i++) lf[i] = Math.log(FREQS[i]);
        double g;
        if (lx <= lf[0]) g = eff[0];
        else if (lx >= lf[9]) g = eff[9];
        else {
            int i = 0;
            while (lx > lf[i + 1]) i++;
            double t = (lx - lf[i]) / (lf[i + 1] - lf[i]);
            double s = t * t * (3 - 2 * t);   // smooth step between bands
            g = eff[i] + (eff[i + 1] - eff[i]) * s;
        }
        return (float) ((g + preamp()) / 100.0);
    }

    // ------------------------------------------------------------------ own presets
    public static List<String> userPresets() {
        List<String> l = new ArrayList<>();
        try {
            Iterator<String> k = new JSONObject(Store.getStr("mfx_user", "{}")).keys();
            while (k.hasNext()) l.add(k.next());
        } catch (Exception ignored) {
        }
        return l;
    }

    public static void saveUser(String name) {
        try {
            JSONObject all = new JSONObject(Store.getStr("mfx_user", "{}"));
            JSONArray b = new JSONArray();
            for (int v : bands()) b.put(v);
            all.put(name, new JSONObject().put("b", b).put("pre", preamp()).put("tb", bassTone()).put("tt", trebleTone()));
            Store.putStr("mfx_user", all.toString());
            Store.putStr("mfx_user_cur", name);
        } catch (Exception ignored) {
        }
    }

    public static void loadUser(String name) {
        try {
            JSONObject p = new JSONObject(Store.getStr("mfx_user", "{}")).getJSONObject(name);
            JSONArray b = p.getJSONArray("b");
            int[] v = new int[10];
            for (int i = 0; i < 10; i++) v[i] = b.getInt(i);
            setBands(v);
            setPreamp(p.optInt("pre"));
            setTone(p.optInt("tb"), p.optInt("tt"));
            Store.putStr("mfx_user_cur", name);
        } catch (Exception ignored) {
        }
    }

    public static void deleteUser(String name) {
        try {
            JSONObject all = new JSONObject(Store.getStr("mfx_user", "{}"));
            all.remove(name);
            Store.putStr("mfx_user", all.toString());
        } catch (Exception ignored) {
        }
    }

    public static String presetLabel() {
        String u = Store.getStr("mfx_user_cur", "");
        if (!u.isEmpty()) return u;
        int i = presetIndex();
        return i < 0 || i >= PRESETS.length ? T.t("Custom") : T.t(PRESETS[i]);
    }

    // ------------------------------------------------------------------ engine
    private static DynamicsProcessing dp;
    private static Equalizer eq;
    private static BassBoost bass;
    private static Virtualizer virt;
    private static LoudnessEnhancer loud;
    private static PresetReverb rev;
    private static int session;

    public static int session() {
        return session;
    }

    /** Called by the music service for each new song (audio session). */
    public static synchronized void attach(int audioSession) {
        if (audioSession == session && (dp != null || eq != null)) {
            apply();
            return;
        }
        release();
        session = audioSession;
        if (advanced()) {
            try {
                DynamicsProcessing.Config cfg = new DynamicsProcessing.Config.Builder(
                        DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                        true, 10, false, 0, false, 0, true).build();
                dp = new DynamicsProcessing(0, audioSession, cfg);
            } catch (Throwable t) {
                dp = null;
            }
        }
        if (dp == null) {
            try {
                eq = new Equalizer(0, audioSession);
            } catch (Throwable ignored) {
            }
        }
        try {
            bass = new BassBoost(0, audioSession);
        } catch (Throwable ignored) {
        }
        try {
            virt = new Virtualizer(0, audioSession);
        } catch (Throwable ignored) {
        }
        try {
            loud = new LoudnessEnhancer(audioSession);
        } catch (Throwable ignored) {
        }
        try {
            rev = new PresetReverb(0, audioSession);
        } catch (Throwable ignored) {
        }
        apply();
    }

    /** Told about every change (the browser applies the same EQ to its pages). */
    public static volatile Runnable onChange;

    /** Applies the saved settings. Cheap enough to call while a slider moves. */
    public static synchronized void apply() {
        Runnable oc = onChange;
        if (oc != null) oc.run();
        boolean on = on();
        int[] g = effective();
        try {
            if (dp != null) {
                for (int i = 0; i < 10; i++) {
                    dp.setPreEqBandAllChannelsTo(i, new DynamicsProcessing.EqBand(true, CUTOFF[i], on ? g[i] / 100f : 0f));
                }
                dp.setInputGainAllChannelsTo(on ? preamp() / 100f : 0f);
                dp.setLimiterAllChannelsTo(new DynamicsProcessing.Limiter(true, on && limiterOn(), 0,
                        1f, 60f, 10f, limiterTh() / 10f, 0f));
                dp.setEnabled(true);
            }
            if (eq != null) {
                eq.setEnabled(on);
                short n = eq.getNumberOfBands();
                short[] range = eq.getBandLevelRange();
                for (short i = 0; i < n; i++) {
                    float hz = eq.getCenterFreq(i) / 1000f;
                    int v = Math.round(responseDb(hz, g) * 100);
                    eq.setBandLevel(i, (short) Math.max(range[0], Math.min(range[1], v)));
                }
            }
            if (bass != null) {
                int s = Store.getInt("mfx_bass", 0);
                bass.setEnabled(on && s > 0);
                if (bass.getStrengthSupported()) bass.setStrength((short) s);
            }
            if (virt != null) {
                int s = Store.getInt("mfx_virt", 0);
                virt.setEnabled(on && s > 0);
                if (virt.getStrengthSupported()) virt.setStrength((short) s);
            }
            if (loud != null) {
                int lg = Store.getInt("mfx_loud", 0);
                loud.setEnabled(on && lg > 0);
                loud.setTargetGain(lg);
            }
            if (rev != null) {
                int r = Store.getInt("mfx_rev", 0);
                rev.setEnabled(on && r > 0);
                rev.setPreset((short) Math.max(0, Math.min(6, r)));
            }
        } catch (Throwable ignored) {
        }
    }

    /** Left / right balance: -100 (left) … 0 … 100 (right) → player volume per side. */
    public static float[] balance() {
        int b = Store.getInt("mfx_balance", 0);
        return new float[]{b > 0 ? 1f - b / 100f : 1f, b < 0 ? 1f + b / 100f : 1f};
    }

    public static synchronized void release() {
        try {
            if (dp != null) dp.release();
            if (eq != null) eq.release();
            if (bass != null) bass.release();
            if (virt != null) virt.release();
            if (loud != null) loud.release();
            if (rev != null) rev.release();
        } catch (Throwable ignored) {
        }
        dp = null;
        eq = null;
        bass = null;
        virt = null;
        loud = null;
        rev = null;
        session = 0;
    }
}
