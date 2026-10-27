package com.mytv.player;

/**
 * Song editor effects on 44.1 kHz stereo 16-bit (plain Java, measured outside Android).
 * Each takes the selected sound and returns the processed sound (speed changes its length).
 */
public final class EditFx {
    private EditFx() {
    }

    public static final int RATE = 44100;
    public static final String[] IDS = {"echo", "reverb", "bass", "treble", "lowcut", "highcut", "gate", "speed", "vocals", "mono"};

    public static short[] apply(String id, short[] in, int frames, float amount) {
        switch (id) {
            case "echo": return echo(in, frames, 0.32f, 0.45f, 0.5f);
            case "reverb": return reverb(in, frames, amount <= 0 ? 0.35f : amount);
            case "bass": return shelf(in, frames, true, 120, amount == 0 ? 6 : amount);
            case "treble": return shelf(in, frames, false, 6000, amount == 0 ? 6 : amount);
            case "lowcut": return pass(in, frames, true, amount <= 0 ? 90 : amount);
            case "highcut": return pass(in, frames, false, amount <= 0 ? 8000 : amount);
            case "gate": return gate(in, frames, amount == 0 ? -42 : amount);
            case "speed": return speed(in, frames, amount <= 0 ? 1.25f : amount);
            case "vocals": return vocals(in, frames);
            case "mono": return mono(in, frames);
            default: return in.clone();
        }
    }

    static short clip(float x) {
        return (short) Math.max(-32768, Math.min(32767, Math.round(x)));
    }

    // ------------------------------------------------------------------ echo
    static short[] echo(short[] in, int n, float delaySec, float feedback, float mix) {
        int d = Math.max(1, (int) (delaySec * RATE));
        float[] bl = new float[d], br = new float[d];
        short[] out = new short[n * 2];
        for (int i = 0, k = 0; i < n; i++, k = (k + 1) % d) {
            float l = in[2 * i], r = in[2 * i + 1];
            float el = bl[k], er = br[k];
            bl[k] = l + el * feedback;
            br[k] = r + er * feedback;
            out[2 * i] = clip(l + el * mix);
            out[2 * i + 1] = clip(r + er * mix);
        }
        return out;
    }

    // ------------------------------------------------------------------ reverb (Freeverb-style: 8 combs + 4 all-passes per side)
    static short[] reverb(short[] in, int n, float wet) {
        int[] combs = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617}, alls = {556, 441, 341, 225};
        float[][][] cb = new float[2][combs.length][], ab = new float[2][alls.length][];
        int[][] cp = new int[2][combs.length], ap = new int[2][alls.length];
        float[][] filt = new float[2][combs.length];
        for (int c = 0; c < 2; c++) {
            for (int j = 0; j < combs.length; j++) cb[c][j] = new float[combs[j] + c * 23];
            for (int j = 0; j < alls.length; j++) ab[c][j] = new float[alls[j] + c * 23];
        }
        float room = 0.84f, damp = 0.2f;
        short[] out = new short[n * 2];
        for (int i = 0; i < n; i++) {
            float input = (in[2 * i] + in[2 * i + 1]) * 0.015f;
            for (int c = 0; c < 2; c++) {
                float acc = 0;
                for (int j = 0; j < combs.length; j++) {
                    float[] b = cb[c][j];
                    float y = b[cp[c][j]];
                    filt[c][j] = y * (1 - damp) + filt[c][j] * damp;
                    b[cp[c][j]] = input + filt[c][j] * room;
                    cp[c][j] = (cp[c][j] + 1) % b.length;
                    acc += y;
                }
                for (int j = 0; j < alls.length; j++) {
                    float[] b = ab[c][j];
                    float bo = b[ap[c][j]];
                    b[ap[c][j]] = acc + bo * 0.5f;
                    acc = bo - acc;
                    ap[c][j] = (ap[c][j] + 1) % b.length;
                }
                float dry = in[2 * i + c];
                out[2 * i + c] = clip(dry * (1 - wet * 0.4f) + acc * wet * 3f);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ EQ / filters (RBJ biquads)
    private static double[] coef(String type, double f, double db) {
        double A = Math.pow(10, db / 40), w = 2 * Math.PI * f / RATE, c = Math.cos(w), s = Math.sin(w), q = 0.7071, al = s / (2 * q);
        double b0, b1, b2, a0, a1, a2;
        switch (type) {
            case "low": {
                double sq = 2 * Math.sqrt(A) * al;
                b0 = A * ((A + 1) - (A - 1) * c + sq); b1 = 2 * A * ((A - 1) - (A + 1) * c); b2 = A * ((A + 1) - (A - 1) * c - sq);
                a0 = (A + 1) + (A - 1) * c + sq; a1 = -2 * ((A - 1) + (A + 1) * c); a2 = (A + 1) + (A - 1) * c - sq;
                break;
            }
            case "high": {
                double sq = 2 * Math.sqrt(A) * al;
                b0 = A * ((A + 1) + (A - 1) * c + sq); b1 = -2 * A * ((A - 1) + (A + 1) * c); b2 = A * ((A + 1) + (A - 1) * c - sq);
                a0 = (A + 1) - (A - 1) * c + sq; a1 = 2 * ((A - 1) - (A + 1) * c); a2 = (A + 1) - (A - 1) * c - sq;
                break;
            }
            case "hp":
                b0 = (1 + c) / 2; b1 = -(1 + c); b2 = (1 + c) / 2; a0 = 1 + al; a1 = -2 * c; a2 = 1 - al;
                break;
            default:   // lp
                b0 = (1 - c) / 2; b1 = 1 - c; b2 = (1 - c) / 2; a0 = 1 + al; a1 = -2 * c; a2 = 1 - al;
        }
        return new double[]{b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0};
    }

    private static short[] biquad(short[] in, int n, double[] k, int stages) {
        float[] cur = new float[n * 2];
        for (int i = 0; i < n * 2; i++) cur[i] = in[i];
        for (int st = 0; st < stages; st++) {
            for (int ch = 0; ch < 2; ch++) {
                double z1 = 0, z2 = 0;
                for (int i = 0; i < n; i++) {
                    double x = cur[2 * i + ch], y = k[0] * x + z1;
                    z1 = k[1] * x - k[3] * y + z2;
                    z2 = k[2] * x - k[4] * y;
                    cur[2 * i + ch] = (float) y;
                }
            }
        }
        short[] out = new short[n * 2];
        for (int i = 0; i < n * 2; i++) out[i] = clip(cur[i]);
        return out;
    }

    static short[] shelf(short[] in, int n, boolean low, double hz, double db) {
        return biquad(in, n, coef(low ? "low" : "high", hz, db), 1);
    }

    /** low = high-pass at hz (removes rumble below); else low-pass at hz (removes hiss above). 24 dB/oct. */
    static short[] pass(short[] in, int n, boolean lowCut, double hz) {
        return biquad(in, n, coef(lowCut ? "hp" : "lp", hz, 0), 2);
    }

    // ------------------------------------------------------------------ noise gate (quiet parts → silent, smooth)
    static short[] gate(short[] in, int n, float thresholdDb) {
        float th = (float) (32768 * Math.pow(10, thresholdDb / 20.0));
        float env = 0, g = 0;
        // opens in 2 ms, closes in ~15 ms after a 40 ms hold; the level follower falls in ~10 ms
        float att = (float) Math.exp(-1.0 / (0.002 * RATE)), rel = (float) Math.exp(-1.0 / (0.015 * RATE));
        float fall = (float) Math.exp(-1.0 / (0.01 * RATE));
        int hold = (int) (0.04 * RATE), held = 0;
        short[] out = new short[n * 2];
        for (int i = 0; i < n; i++) {
            float lvl = Math.max(Math.abs(in[2 * i]), Math.abs(in[2 * i + 1]));
            env = Math.max(lvl, env * fall);
            boolean open = env > th;
            if (open) held = hold;
            else if (held > 0) held--;
            float target = open || held > 0 ? 1f : 0f;
            g = target > g ? target + (g - target) * att : target + (g - target) * rel;
            out[2 * i] = clip(in[2 * i] * g);
            out[2 * i + 1] = clip(in[2 * i + 1] * g);
        }
        return out;
    }

    // ------------------------------------------------------------------ speed (tape style: pitch follows)
    static short[] speed(short[] in, int n, float factor) {
        int m = Math.max(1, (int) (n / factor));
        short[] out = new short[m * 2];
        for (int j = 0; j < m; j++) {
            double x = j * factor;
            int i = (int) x;
            float t = (float) (x - i);
            int i1 = Math.min(n - 1, i + 1);
            i = Math.min(n - 1, i);
            out[2 * j] = clip(in[2 * i] + (in[2 * i1] - in[2 * i]) * t);
            out[2 * j + 1] = clip(in[2 * i + 1] + (in[2 * i1 + 1] - in[2 * i + 1]) * t);
        }
        return out;
    }

    // ------------------------------------------------------------------ voice removal (the Karaoke engine) and mono
    static short[] vocals(short[] in, int n) {
        KaraokeDsp k = new KaraokeDsp(RATE);
        k.mode = KaraokeDsp.MUSIC;
        short[] buf = in.clone();
        k.process(buf, n);
        short[] tail = k.flush();
        // remove the engine's start delay so the result lines up with the original
        short[] out = new short[n * 2];
        int lat = KaraokeDsp.LATENCY;
        for (int i = 0; i < n; i++) {
            int src = i + lat;
            if (src < n) {
                out[2 * i] = buf[2 * src];
                out[2 * i + 1] = buf[2 * src + 1];
            } else {
                out[2 * i] = tail[2 * (src - n)];
                out[2 * i + 1] = tail[2 * (src - n) + 1];
            }
        }
        return out;
    }

    static short[] mono(short[] in, int n) {
        short[] out = new short[n * 2];
        for (int i = 0; i < n; i++) {
            short m = clip((in[2 * i] + in[2 * i + 1]) * 0.5f);
            out[2 * i] = m;
            out[2 * i + 1] = m;
        }
        return out;
    }
}
