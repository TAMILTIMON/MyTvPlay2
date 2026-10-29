package com.mytv.player;

/**
 * DJ engine (plain Java, measured outside Android): two decks + mixer.
 * A deck plays decoded 16-bit stereo PCM with variable speed (turntable style: pitch follows tempo),
 * 3-band EQ with kill, a one-knob filter (left = low-pass, right = high-pass), beat-synced echo,
 * loops, cue and hot cues. The mixer has a constant-power crossfader and master gain.
 * BPM + first beat are detected from the song itself.
 */
public final class DjCore {
    private DjCore() {
    }

    /** Where a deck reads its sound from (e.g. a memory-mapped PCM file). */
    public interface Source {
        long frames();

        int rate();

        short l(long i);

        short r(long i);
    }

    // ================================================================== deck
    public static final class Deck {
        public volatile Source src;
        public volatile boolean playing;
        public volatile double pos;               // frame position in the song (fractional)
        public volatile float pitch = 1f;         // 1 = normal speed
        public volatile float bend = 1f;          // temporary nudge (hold buttons)
        public volatile float trimDb, lowDb, midDb, highDb;     // EQ −26 (kill) … +6 dB
        public volatile float filter;             // −1 low-pass … 0 off … +1 high-pass
        public volatile float echo;               // 0 … 1 wet
        public volatile float volume = 1f;        // channel fader 0 … 1
        public volatile double bpm, firstBeat;    // detected (source frames)
        public volatile long cue = 0;
        public final long[] hot = {-1, -1, -1, -1};
        public volatile long loopA = -1, loopB = -1;
        public volatile float peak;               // level meter 0 … 1
        /** Scratching (finger on the jog wheel): target speed in song frames per output frame, + / −; NaN = not scratching. */
        public volatile float scratch = Float.NaN;
        private double scrV;                      // the actual scratch speed (follows the finger smoothly — no clicks)

        public int outRate() {
            return outRate;
        }

        private final int outRate;
        private final Bq lsL = new Bq(), lsR = new Bq(), mdL = new Bq(), mdR = new Bq(), hsL = new Bq(), hsR = new Bq(),
                fL = new Bq(), fR = new Bq(), fL2 = new Bq(), fR2 = new Bq();
        private float cLow = Float.NaN, cMid = Float.NaN, cHigh = Float.NaN, cFilter = Float.NaN;
        private final float[] dlL, dlR;
        private int dlPos;

        public Deck(int outRate) {
            this.outRate = outRate;
            dlL = new float[outRate * 2];
            dlR = new float[outRate * 2];
        }

        public long length() {
            Source s = src;
            return s == null ? 0 : s.frames();
        }

        /** Beat length in source frames (0 if unknown). */
        public double beatLen() {
            Source s = src;
            return s == null || bpm <= 0 ? 0 : s.rate() * 60.0 / bpm;
        }

        /** BPM as it sounds now (with the pitch slider). */
        public double liveBpm() {
            return bpm * pitch;
        }

        /** Sets a loop of `beats` beats starting at the beat nearest to the current position. */
        public void loopBeats(double beats) {
            double bl = beatLen();
            if (bl <= 0) {
                Source s = src;
                bl = s == null ? 44100 / 2.0 : s.rate() / 2.0;      // no BPM: half a second per "beat"
            }
            double start = firstBeat + Math.floor((pos - firstBeat) / bl) * bl;
            loopA = Math.max(0, (long) start);
            loopB = (long) (start + beats * bl);
        }

        public void loopOff() {
            loopA = loopB = -1;
        }

        public boolean looping() {
            return loopA >= 0 && loopB > loopA;
        }

        private void updateFilters(int srcRate) {
            if (lowDb != cLow) {
                cLow = lowDb;
                lsL.lowShelf(outRate, 220, lowDb);
                lsR.lowShelf(outRate, 220, lowDb);
            }
            if (midDb != cMid) {
                cMid = midDb;
                mdL.peak(outRate, 1000, 0.7, midDb);
                mdR.peak(outRate, 1000, 0.7, midDb);
            }
            if (highDb != cHigh) {
                cHigh = highDb;
                hsL.highShelf(outRate, 4000, highDb);
                hsR.highShelf(outRate, 4000, highDb);
            }
            if (filter != cFilter) {
                cFilter = filter;
                float f = Math.abs(filter);
                if (filter < -0.02f) {          // low-pass from 20 kHz down to 150 Hz
                    double hz = 20000 * Math.pow(150 / 20000.0, f);
                    fL.lowPass(outRate, hz, 0.9);
                    fR.lowPass(outRate, hz, 0.9);
                    fL2.lowPass(outRate, hz, 0.7);
                    fR2.lowPass(outRate, hz, 0.7);
                } else if (filter > 0.02f) {    // high-pass from 20 Hz up to 4 kHz
                    double hz = 20 * Math.pow(4000 / 20.0, f);
                    fL.highPass(outRate, hz, 0.9);
                    fR.highPass(outRate, hz, 0.9);
                    fL2.highPass(outRate, hz, 0.7);
                    fR2.highPass(outRate, hz, 0.7);
                }
            }
        }

        /** Adds this deck's sound for n output frames into outL / outR (× gain). */
        public void render(float[] outL, float[] outR, int n, float gain) {
            Source s = src;
            float pk = 0;
            if (s == null) {
                peak = 0;
                return;
            }
            updateFilters(s.rate());
            double step = pitch * bend * s.rate() / (double) outRate;
            float trim = (float) Math.pow(10, trimDb / 20.0);
            boolean eq = lowDb != 0 || midDb != 0 || highDb != 0, flt = Math.abs(filter) > 0.02f;
            double bl = beatLen();
            int echoDelay = (int) Math.min(dlL.length - 1, bl > 0 ? bl * 0.75 / pitch * outRate / s.rate() : outRate * 0.375);
            long frames = s.frames();
            float scr = scratch;
            boolean scratching = !Float.isNaN(scr);
            if (!scratching) scrV = playing ? step : 0;            // a scratch starts from the current speed
            for (int k = 0; k < n; k++) {
                float l = 0, r = 0;
                if (scratching) {
                    // the record follows the finger: forwards, backwards, or held still
                    scrV += (scr - scrV) * 0.0035;
                    if (pos < 0) pos = 0;
                    if (pos > frames - 2) pos = frames - 2;
                    long i = (long) pos;
                    float t = (float) (pos - i);
                    l = s.l(i) + (s.l(i + 1) - s.l(i)) * t;
                    r = s.r(i) + (s.r(i + 1) - s.r(i)) * t;
                    pos = Math.max(0, Math.min(frames - 2, pos + scrV));
                } else if (playing) {
                    long i = (long) pos;
                    if (i >= frames - 1) {
                        playing = false;
                    } else {
                        float t = (float) (pos - i);
                        l = s.l(i) + (s.l(i + 1) - s.l(i)) * t;
                        r = s.r(i) + (s.r(i + 1) - s.r(i)) * t;
                        pos += step;
                        if (looping() && pos >= loopB) pos -= (loopB - loopA);
                    }
                }
                l *= trim;
                r *= trim;
                if (eq) {
                    l = (float) hsL.run(mdL.run(lsL.run(l)));
                    r = (float) hsR.run(mdR.run(lsR.run(r)));
                }
                if (flt) {
                    l = (float) fL2.run(fL.run(l));
                    r = (float) fR2.run(fR.run(r));
                }
                if (echo > 0.001f) {
                    int rp = (dlPos - echoDelay + dlL.length) % dlL.length;
                    float el = dlL[rp], er = dlR[rp];
                    dlL[dlPos] = l + el * 0.45f;
                    dlR[dlPos] = r + er * 0.45f;
                    dlPos = (dlPos + 1) % dlL.length;
                    l += el * echo;
                    r += er * echo;
                }
                l *= volume * gain;
                r *= volume * gain;
                pk = Math.max(pk, Math.max(Math.abs(l), Math.abs(r)));
                outL[k] += l;
                outR[k] += r;
            }
            peak = Math.min(1f, pk / 32768f);
        }

        /** Matches this deck's tempo (and beat position) to another playing deck. */
        public void syncTo(Deck master) {
            if (bpm <= 0 || master.bpm <= 0) return;
            double target = master.liveBpm();
            double p = target / bpm;
            while (p > 1.5) p /= 2;          // e.g. 140 vs 70 BPM: lock to double / half time
            while (p < 0.5) p *= 2;
            pitch = (float) p;
            // align the beat: same position inside the beat as the master
            double mbl = master.beatLen(), bl = beatLen();
            if (mbl <= 0 || bl <= 0) return;
            double phase = ((master.pos - master.firstBeat) / mbl) % 1.0;
            if (phase < 0) phase += 1;
            double beatsHere = Math.floor((pos - firstBeat) / bl);
            pos = Math.max(0, firstBeat + (beatsHere + phase) * bl);
        }
    }

    // ================================================================== mixer
    /** Constant-power crossfade: x = −1 (only A) … 0 (both, no dip) … +1 (only B). */
    public static float[] crossfade(float x) {
        double a = (Math.max(-1, Math.min(1, x)) + 1) * Math.PI / 4;
        return new float[]{(float) Math.cos(a), (float) Math.sin(a)};
    }

    /** Mixes both decks into interleaved stereo 16-bit. */
    public static void mix(Deck a, Deck b, float xfade, float masterDb, float[] tl, float[] tr, short[] out, int n) {
        java.util.Arrays.fill(tl, 0, n, 0);
        java.util.Arrays.fill(tr, 0, n, 0);
        float[] g = crossfade(xfade);
        a.render(tl, tr, n, g[0]);
        b.render(tl, tr, n, g[1]);
        float m = (float) Math.pow(10, masterDb / 20.0);
        for (int i = 0; i < n; i++) {
            out[2 * i] = soft(tl[i] * m);
            out[2 * i + 1] = soft(tr[i] * m);
        }
    }

    static short soft(float x) {
        float lim = 28000f;
        if (x > lim) x = lim + (32767 - lim) * (float) Math.tanh((x - lim) / (32767 - lim));
        else if (x < -lim) x = -lim - (32767 - lim) * (float) Math.tanh((-x - lim) / (32767 - lim));
        return (short) Math.max(-32768, Math.min(32767, x));
    }

    // ================================================================== BPM + first beat
    /**
     * Detects the tempo from the bass / kick energy: onset envelope (200 per second) → autocorrelation
     * over 70…180 BPM → strongest, folded into 80…160. Returns {bpm, firstBeatFrame}.
     */
    public static double[] detectBpm(Source s, long maxFrames) {
        int rate = s.rate();
        int hop = rate / 200;
        double fr = rate / (double) hop;          // real analysis steps per second (44100/220 = 200.45, not 200)
        long n = Math.min(s.frames(), maxFrames);
        int m = (int) (n / hop);
        if (m < 400) return new double[]{0, 0};
        float[] env = new float[m];
        double lp = 0, a = 1 - Math.exp(-2 * Math.PI * 180.0 / rate);   // kick / bass band
        for (int i = 0; i < m; i++) {
            double e = 0;
            for (int k = 0; k < hop; k++) {
                long f = (long) i * hop + k;
                double x = (s.l(f) + s.r(f)) * 0.5;
                lp += a * (x - lp);
                e += lp * lp;
            }
            env[i] = (float) Math.sqrt(e / hop);
        }
        float[] on = new float[m];
        for (int i = 1; i < m; i++) on[i] = Math.max(0, env[i] - env[i - 1]);
        // autocorrelation over the tempo range
        double best = 0;
        int bestLag = 0;
        int minLag = (int) (fr * 60 / 180.0), maxLag = (int) (fr * 60 / 70.0);
        double[] score = new double[maxLag + 2];
        for (int lag = minLag; lag <= maxLag; lag++) {
            double sc = 0;
            for (int i = lag; i < m; i++) sc += on[i] * on[i - lag];
            score[lag] = sc;
        }
        for (int lag = minLag; lag <= maxLag; lag++) {
            double sc = score[lag] + 0.5 * (lag * 2 <= maxLag ? score[lag * 2] : 0);   // reward the double too
            if (sc > best) {
                best = sc;
                bestLag = lag;
            }
        }
        if (bestLag == 0) return new double[]{0, 0};
        // parabolic refinement of the peak
        double y0 = score[bestLag - 1], y1 = score[bestLag], y2 = score[bestLag + 1];
        double d = (y0 - 2 * y1 + y2) == 0 ? 0 : 0.5 * (y0 - y2) / (y0 - 2 * y1 + y2);
        double lag = bestLag + Math.max(-0.5, Math.min(0.5, d));
        double bpm = fr * 60 / lag;
        while (bpm < 80) bpm *= 2;
        while (bpm > 160) bpm /= 2;
        // fine tuning in 0.05 BPM steps over the whole analysed part: the grid that lands on the most onsets.
        // (a 0.3 BPM error would make two synced songs drift apart in a long mix)
        double bestBpm = bpm, bestPh = 0, bestSum = -1;
        for (double cand = bpm - 1.5; cand <= bpm + 1.5; cand += 0.05) {
            double period = fr * 60 / cand;
            int steps = (int) Math.ceil(period);
            for (int ph4 = 0; ph4 < steps * 4; ph4++) {          // quarter-sample phase steps
                double ph = ph4 / 4.0, sum = 0;
                for (double t = ph; t < m - 1; t += period) {
                    int ti = (int) t;
                    double w = t - ti;
                    sum += on[ti] * (1 - w) + on[ti + 1] * w;
                }
                if (sum > bestSum) {
                    bestSum = sum;
                    bestBpm = cand;
                    bestPh = ph;
                }
            }
        }
        return new double[]{Math.round(bestBpm * 10) / 10.0, bestPh * hop};
    }

    // ================================================================== RBJ biquad
    static final class Bq {
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

        void lowPass(double fs, double f, double q) {
            double w = 2 * Math.PI * Math.min(f, fs * 0.45) / fs, c = Math.cos(w), al = Math.sin(w) / (2 * q);
            set((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + al, -2 * c, 1 - al);
        }

        void highPass(double fs, double f, double q) {
            double w = 2 * Math.PI * f / fs, c = Math.cos(w), al = Math.sin(w) / (2 * q);
            set((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al);
        }

        void peak(double fs, double f, double q, double db) {
            double A = Math.pow(10, db / 40), w = 2 * Math.PI * f / fs, c = Math.cos(w), al = Math.sin(w) / (2 * q);
            set(1 + al * A, -2 * c, 1 - al * A, 1 + al / A, -2 * c, 1 - al / A);
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
