package com.mytv.player;

/**
 * Karaoke engine (plain Java, measured outside Android).
 * Works in the frequency domain (FFT, 2048 samples, 75 % overlap) so nothing is shifted in time:
 * for every frequency it checks how "centred" the sound is (same in left and right, like a lead
 * vocal) and whether it lies in the vocal range. That part becomes the Voice layer;
 * Music = Original − Voice, exactly (the two layers always add back up to the original).
 * Modes can be switched while playing (A/B); EQ / width / levels apply live.
 * Output is delayed by {@link #LATENCY} samples (≈46 ms at 44.1 kHz); {@link #flush} drains the end.
 */
public final class KaraokeDsp {
    public static final int ORIGINAL = 0, MUSIC = 1, VOICE = 2, MIX = 3;
    private static final int N = 2048, H = N / 4;
    public static final int LATENCY = N;

    // settings — change any time
    public volatile float strength = 1f;        // 0…1 how much of the voice is taken out
    public volatile float lowCut = 120f;        // Hz: below stays in the music (bass, kick)
    public volatile float highCut = 8000f;      // Hz: above stays in the music (cymbals, air)
    public volatile float focus = 2f;           // 1 = wide (near-centre sounds too) … 4 = only exactly centred
    public volatile float bassDb = 0f, trebleDb = 0f;   // music EQ
    public volatile float width = 1f;           // music stereo width 0…2
    public volatile float voiceLevel = 0.3f;    // MIX: guide voice left in
    public volatile float gainDb = 0f;          // output
    public volatile float oversub = 1.4f;       // take extra voice out of the music (leftover singer)
    public volatile float formant = 0.7f;       // extra pull in the speech band (300–3400 Hz)
    public volatile float smear = 0.4f;         // catch reverb around the voice (neighbour bins)
    public volatile float residual = 0.55f;     // second pass on leftover centre in the vocal range
    public volatile float harmonics = 0.45f;    // lock onto the singer's pitch and its overtones
    public volatile float smooth = 0.45f;       // time-smooth the mask (fewer artifacts)
    public volatile float drums = 0.35f;        // keep centred snare / clap attacks in the music
    public volatile int mode = MUSIC;

    private final int rate;
    private final float[] win = new float[N];
    private final float[] inL = new float[N], inR = new float[N];
    private int fill;
    private final float[] ola = new float[N];
    private final float[] re = new float[N], im = new float[N], vr = new float[N], vi = new float[N];
    private final float[] cs = new float[N / 2], sn = new float[N / 2];
    private final int[] rev = new int[N];
    private final float[] qL = new float[N * 4], qR = new float[N * 4], qV = new float[N * 4];
    private int qHead, qSize;
    private final Shelf bassL = new Shelf(), bassR = new Shelf(), trebL = new Shelf(), trebR = new Shelf();
    private float sBass = Float.NaN, sTreb = Float.NaN;
    private final float[] prevMask = new float[N / 2 + 1], prevMag = new float[N / 2 + 1];
    private final float[] mask = new float[N / 2 + 1], work = new float[N / 2 + 1];
    private float smoothF0;

    public KaraokeDsp(int rate) {
        this.rate = rate;
        for (int i = 0; i < N; i++) win[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / N));
        for (int i = 0; i < N / 2; i++) {
            cs[i] = (float) Math.cos(2 * Math.PI * i / N);
            sn[i] = (float) Math.sin(2 * Math.PI * i / N);
        }
        int bits = Integer.numberOfTrailingZeros(N);
        for (int i = 0; i < N; i++) rev[i] = Integer.reverse(i) >>> (32 - bits);
        reset();
    }

    /** Clears everything (after a seek). The first LATENCY output samples are silence. */
    public void reset() {
        java.util.Arrays.fill(inL, 0);
        java.util.Arrays.fill(inR, 0);
        java.util.Arrays.fill(ola, 0);
        qHead = 0;
        qSize = 0;
        for (int i = 0; i < H; i++) push(0, 0, 0);
        fill = 0;
        java.util.Arrays.fill(prevMask, 0);
        java.util.Arrays.fill(prevMag, 0);
        smoothF0 = 0;
        for (Shelf s : new Shelf[]{bassL, bassR, trebL, trebR}) s.z1 = s.z2 = 0;
    }

    private void push(float l, float r, float v) {
        int t = (qHead + qSize) % qL.length;
        qL[t] = l;
        qR[t] = r;
        qV[t] = v;
        qSize++;
    }

    private void fft(float[] a, float[] b, boolean inverse) {
        for (int i = 0; i < N; i++) {
            int j = rev[i];
            if (j > i) {
                float t = a[i];
                a[i] = a[j];
                a[j] = t;
                t = b[i];
                b[i] = b[j];
                b[j] = t;
            }
        }
        for (int len = 2; len <= N; len <<= 1) {
            int half = len >> 1, step = N / len;
            for (int i = 0; i < N; i += len) {
                for (int k = 0; k < half; k++) {
                    float wr = cs[k * step], wi = inverse ? sn[k * step] : -sn[k * step];
                    int p = i + k, q = p + half;
                    float xr = a[q] * wr - b[q] * wi, xi = a[q] * wi + b[q] * wr;
                    a[q] = a[p] - xr;
                    b[q] = b[p] - xi;
                    a[p] += xr;
                    b[p] += xi;
                }
            }
        }
    }

    private float band(float hz) {
        float lo = smooth(hz, lowCut / 1.26f, lowCut * 1.26f);
        float hi = 1f - smooth(hz, highCut / 1.26f, highCut * 1.26f);
        return lo * hi;
    }

    private static float smooth(float x, float a, float b) {
        if (x <= a) return 0;
        if (x >= b) return 1;
        float t = (x - a) / (b - a);
        return t * t * (3 - 2 * t);
    }

    /** Extra weight in the speech / singer formant band so leftover lyrics come out of the music. */
    private float speech(float hz) {
        float lo = smooth(hz, 250f, 400f), hi = 1f - smooth(hz, 3200f, 4200f);
        return lo * hi;
    }

    private void analyse() {
        for (int i = 0; i < N; i++) {
            re[i] = inL[i] * win[i];
            im[i] = inR[i] * win[i];
        }
        fft(re, im, false);      // L in the real part, R in the imaginary part → both spectra at once
        float s = strength, f = focus, binHz = rate / (float) N;
        float fo = formant, sm = smear, rs = residual, hm = harmonics, tm = smooth, dr = drums;
        int bins = N / 2;
        float bestF0 = 0, bestScore = 0;
        for (int k = 0; k <= bins; k++) {
            int nk = (N - k) % N;
            float lr = (re[k] + re[nk]) * 0.5f, li = (im[k] - im[nk]) * 0.5f;
            float rr = (im[k] + im[nk]) * 0.5f, ri = (re[nk] - re[k]) * 0.5f;
            float bd = band(k * binHz);
            float ml = (float) Math.sqrt(lr * lr + li * li), mr = (float) Math.sqrt(rr * rr + ri * ri);
            float mag = 0.5f * (ml + mr);
            float dx = lr - rr, dy = li - ri;
            float dl = (float) Math.sqrt(dx * dx + dy * dy);
            float c = ml + mr < 1e-6f ? 0 : Math.max(0, 1f - dl / (ml + mr));
            float pMid = mag * mag, pSide = (dl * 0.5f) * (dl * 0.5f);
            float wiener = pMid / (pMid + (0.6f + f * 0.25f) * pSide + 1e-12f);
            float cp = f == 2f ? c * c : f == 1f ? c : f == 3f ? c * c * c : f == 4f ? (c * c) * (c * c) : (float) Math.pow(c, f);
            float sp = 1f + fo * 1.15f * speech(k * binHz);
            float g = (bd <= 0f || s <= 0f) ? 0f : Math.min(1f, s * bd * cp * (float) Math.sqrt(Math.max(0, wiener)) * sp);
            // centred snare / clap: a sudden jump across a bin is an attack, not a singer
            if (dr > 0 && mag > prevMag[k] * 2.4f + 80f) g *= 1f - dr * 0.85f;
            prevMag[k] = mag;
            mask[k] = g;
            float hz = k * binHz;
            if (hz >= 80 && hz <= 380 && c > 0.55f && mag > bestScore) {
                bestScore = mag;
                bestF0 = hz;
            }
        }
        if (bestF0 > 0) smoothF0 = smoothF0 <= 0 ? bestF0 : smoothF0 * 0.72f + bestF0 * 0.28f;
        if (hm > 0 && smoothF0 >= 70) {
            for (int h = 1; h <= 12; h++) {
                float hz = smoothF0 * h;
                if (hz > highCut) break;
                int k = Math.round(hz / binHz);
                if (k < 1 || k >= bins) continue;
                float boost = hm * (h == 1 ? 0.55f : 0.38f / h) * band(hz);
                mask[k] = Math.min(1f, mask[k] + boost);
                if (k + 1 <= bins) mask[k + 1] = Math.min(1f, mask[k + 1] + boost * 0.45f);
                if (k - 1 >= 0) mask[k - 1] = Math.min(1f, mask[k - 1] + boost * 0.45f);
            }
        }
        // reverb lives in neighbouring bins — smear the mask so the tail comes out of the music too
        if (sm > 0) {
            work[0] = mask[0];
            work[bins] = mask[bins];
            for (int k = 1; k < bins; k++)
                work[k] = mask[k] * (1f - sm) + sm * 0.5f * (mask[k - 1] + mask[k + 1]);
            System.arraycopy(work, 0, mask, 0, bins + 1);
        }
        if (tm > 0) {
            for (int k = 0; k <= bins; k++) {
                mask[k] = prevMask[k] * tm + mask[k] * (1f - tm);
                prevMask[k] = mask[k];
            }
        } else {
            System.arraycopy(mask, 0, prevMask, 0, bins + 1);
        }
        for (int k = 0; k <= bins; k++) {
            int nk = (N - k) % N;
            float lr = (re[k] + re[nk]) * 0.5f, li = (im[k] - im[nk]) * 0.5f;
            float rr = (im[k] + im[nk]) * 0.5f, ri = (re[nk] - re[k]) * 0.5f;
            float g = mask[k];
            float mre = (lr + rr) * 0.5f * g, mim = (li + ri) * 0.5f * g;
            if (rs > 0 && g < 0.92f) {
                float lr2 = lr - mre, li2 = li - mim, rr2 = rr - mre, ri2 = ri - mim;
                float ml = (float) Math.sqrt(lr2 * lr2 + li2 * li2), mr = (float) Math.sqrt(rr2 * rr2 + ri2 * ri2);
                float dx = lr2 - rr2, dy = li2 - ri2;
                float dl = (float) Math.sqrt(dx * dx + dy * dy);
                float c2 = ml + mr < 1e-6f ? 0 : Math.max(0, 1f - dl / (ml + mr));
                float extra = rs * band(k * binHz) * c2 * c2 * (1f + 0.6f * speech(k * binHz));
                if (extra > 0) {
                    mre += (lr2 + rr2) * 0.5f * extra;
                    mim += (li2 + ri2) * 0.5f * extra;
                }
            }
            vr[k] = mre;
            vi[k] = mim;
            if (k > 0 && k < N / 2) {
                vr[N - k] = mre;
                vi[N - k] = -mim;
            }
        }
        fft(vr, vi, true);
        float sc = 1f / (2f * N);   // Hann windows at 75 % overlap sum to 2
        for (int i = 0; i < N; i++) ola[i] += vr[i] * sc;
        for (int i = 0; i < H; i++) push(inL[i], inR[i], ola[i]);
        System.arraycopy(ola, H, ola, 0, N - H);
        java.util.Arrays.fill(ola, N - H, N, 0);
        System.arraycopy(inL, H, inL, 0, N - H);
        System.arraycopy(inR, H, inR, 0, N - H);
        fill = 0;
    }

    private void feed(float l, float r) {
        inL[N - H + fill] = l;
        inR[N - H + fill] = r;
        if (++fill == H) analyse();
    }

    private void out(short[] pcm, int i, float g, int m) {
        float l = qL[qHead], r = qR[qHead], v = qV[qHead];
        qHead = (qHead + 1) % qL.length;
        qSize--;
        float ol, or;
        if (m == ORIGINAL) {
            ol = l;
            or = r;
        } else if (m == VOICE) {
            ol = v;
            or = v;
        } else {
            float os = Math.max(1f, oversub);
            float ml = l - v * os, mr = r - v * os;
            if (width != 1f) {
                float mm = (ml + mr) * 0.5f, ms = (ml - mr) * 0.5f * width;
                ml = mm + ms;
                mr = mm - ms;
            }
            if (bassDb != sBass) {
                sBass = bassDb;
                bassL.lowShelf(rate, 120, bassDb);
                bassR.lowShelf(rate, 120, bassDb);
            }
            if (trebleDb != sTreb) {
                sTreb = trebleDb;
                trebL.highShelf(rate, 6000, trebleDb);
                trebR.highShelf(rate, 6000, trebleDb);
            }
            if (bassDb != 0) {
                ml = (float) bassL.run(ml);
                mr = (float) bassR.run(mr);
            }
            if (trebleDb != 0) {
                ml = (float) trebL.run(ml);
                mr = (float) trebR.run(mr);
            }
            if (m == MIX) {
                ol = ml + v * voiceLevel;
                or = mr + v * voiceLevel;
            } else {
                ol = ml;
                or = mr;
            }
        }
        pcm[2 * i] = clip(ol * g);
        pcm[2 * i + 1] = clip(or * g);
    }

    /** Interleaved stereo 16-bit in → the same number of processed frames out (in place, delayed by LATENCY). */
    public void process(short[] pcm, int frames) {
        float g = (float) Math.pow(10, gainDb / 20.0);
        int m = mode;
        for (int i = 0; i < frames; i++) {
            feed(pcm[2 * i], pcm[2 * i + 1]);
            out(pcm, i, g, m);
        }
    }

    /** Feeds silence and returns the last LATENCY frames (once, at the end of an export). */
    public short[] flush() {
        short[] tail = new short[LATENCY * 2];
        process(tail, LATENCY);
        return tail;
    }

    /** Peaks of the voice and music layers for one chunk (layer waveforms). Leaves pcm unchanged. */
    public void peaks(short[] pcm, int frames, float[] out2) {
        float pv = 0, pm = 0;
        for (int i = 0; i < frames; i++) {
            feed(pcm[2 * i], pcm[2 * i + 1]);
            float l = qL[qHead], r = qR[qHead], v = qV[qHead];
            qHead = (qHead + 1) % qL.length;
            qSize--;
            float os = Math.max(1f, oversub);
            pv = Math.max(pv, Math.abs(v));
            pm = Math.max(pm, Math.max(Math.abs(l - v * os), Math.abs(r - v * os)));
        }
        out2[0] = pv / 32768f;
        out2[1] = pm / 32768f;
    }

    static short clip(float x) {
        if (x > 32767) return 32767;
        if (x < -32768) return -32768;
        return (short) Math.round(x);
    }

    /** Shelf filter (RBJ) for the music bass / treble EQ. */
    static final class Shelf {
        double b0 = 1, b1, b2, a1, a2, z1, z2;

        double run(double x) {
            double y = b0 * x + z1;
            z1 = b1 * x - a1 * y + z2;
            z2 = b2 * x - a2 * y;
            return y;
        }

        private void set(double nb0, double nb1, double nb2, double a0, double na1, double na2) {
            b0 = nb0 / a0;
            b1 = nb1 / a0;
            b2 = nb2 / a0;
            a1 = na1 / a0;
            a2 = na2 / a0;
        }

        void lowShelf(double fs, double f, double db) {
            double A = Math.pow(10, db / 40), w = 2 * Math.PI * f / fs, c = Math.cos(w), s = Math.sin(w);
            double sq = 2 * Math.sqrt(A) * (s / 2 * Math.sqrt(2));
            set(A * ((A + 1) - (A - 1) * c + sq), 2 * A * ((A - 1) - (A + 1) * c), A * ((A + 1) - (A - 1) * c - sq),
                    (A + 1) + (A - 1) * c + sq, -2 * ((A - 1) + (A + 1) * c), (A + 1) + (A - 1) * c - sq);
        }

        void highShelf(double fs, double f, double db) {
            double A = Math.pow(10, db / 40), w = 2 * Math.PI * f / fs, c = Math.cos(w), s = Math.sin(w);
            double sq = 2 * Math.sqrt(A) * (s / 2 * Math.sqrt(2));
            set(A * ((A + 1) + (A - 1) * c + sq), -2 * A * ((A - 1) + (A + 1) * c), A * ((A + 1) + (A - 1) * c - sq),
                    (A + 1) - (A - 1) * c + sq, 2 * ((A - 1) - (A + 1) * c), (A + 1) - (A - 1) * c - sq);
        }
    }
}
