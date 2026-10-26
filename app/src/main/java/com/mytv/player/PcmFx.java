package com.mytv.player;

/**
 * Sound processing on raw 16-bit PCM (plain Java, tested outside Android):
 * karaoke (centre vocal removal), fade in / out, resampling + channel conversion.
 */
public final class PcmFx {
    private PcmFx() {
    }

    // ------------------------------------------------------------------ karaoke
    /**
     * Removes what is in the centre of a stereo mix (usually the lead vocal) while keeping instruments
     * that are panned left / right, and (optionally) the centre bass + kick below ~150 Hz.
     * strength 0…1: how much of the centre is removed.
     */
    public static final class Karaoke {
        private final float strength, a;
        private final boolean keepBass;
        private float lpL1, lpL2;     // 2-pole low-pass of the centre (bass to keep)

        public Karaoke(int sampleRate, float strength, boolean keepBass) {
            this.strength = Math.max(0, Math.min(1, strength));
            this.keepBass = keepBass;
            this.a = (float) (1 - Math.exp(-2 * Math.PI * 150.0 / sampleRate));
        }

        /** Processes interleaved stereo in place. */
        public void process(short[] pcm, int frames) {
            for (int i = 0; i < frames; i++) {
                float l = pcm[2 * i], r = pcm[2 * i + 1];
                float mid = (l + r) * 0.5f, side = (l - r) * 0.5f;
                lpL1 += a * (mid - lpL1);
                lpL2 += a * (lpL1 - lpL2);
                float bass = keepBass ? lpL2 : 0f;
                float centre = bass + (1f - strength) * (mid - lpL2) + (keepBass ? 0f : (1f - strength) * lpL2);
                // the side signal is quieter than the full mix: lift it a little, softly limited
                float sl = soft((centre + side * 1.35f));
                float sr = soft((centre - side * 1.35f));
                pcm[2 * i] = (short) sl;
                pcm[2 * i + 1] = (short) sr;
            }
        }
    }

    /** Soft limiter so lifted signals never clip harshly. */
    static float soft(float x) {
        float lim = 30000f;
        if (x > lim) return lim + (32767 - lim) * (float) Math.tanh((x - lim) / (32767 - lim));
        if (x < -lim) return -lim - (32767 - lim) * (float) Math.tanh((-x - lim) / (32767 - lim));
        return x;
    }

    // ------------------------------------------------------------------ fades
    /**
     * Fade in over the first fadeInFrames and out over the last fadeOutFrames of a total length.
     * posFrames = where this chunk starts in the output.
     */
    public static void fades(short[] pcm, int frames, int ch, long posFrames, long totalFrames, long fadeInFrames, long fadeOutFrames) {
        for (int i = 0; i < frames; i++) {
            long p = posFrames + i;
            float g = 1f;
            if (fadeInFrames > 0 && p < fadeInFrames) g = p / (float) fadeInFrames;
            if (fadeOutFrames > 0 && p > totalFrames - fadeOutFrames) g = Math.min(g, Math.max(0, (totalFrames - p) / (float) fadeOutFrames));
            if (g < 1f) for (int c = 0; c < ch; c++) pcm[i * ch + c] = (short) (pcm[i * ch + c] * g * g);
        }
    }

    // ------------------------------------------------------------------ resample + channels (for merging different files)
    /** Streams any rate / channel count to a fixed output (e.g. 44100 Hz stereo), linear interpolation. */
    public static final class Resampler {
        private final int inRate, inCh, outRate, outCh;
        private double pos;           // position in input frames (relative to the held previous frame)
        private float[] prev;         // last input frame of the previous chunk

        public Resampler(int inRate, int inCh, int outRate, int outCh) {
            this.inRate = inRate;
            this.inCh = inCh;
            this.outRate = outRate;
            this.outCh = outCh;
        }

        private float sample(short[] in, int frame, int c) {
            int cc = Math.min(c, inCh - 1);                 // mono → both sides; >2 ch → first two
            if (frame < 0) return prev == null ? 0 : prev[cc];
            return in[frame * inCh + cc];
        }

        /** Returns interleaved output frames for one input chunk. */
        public short[] process(short[] in, int frames) {
            if (inRate == outRate && inCh == outCh) {
                short[] o = new short[frames * outCh];
                System.arraycopy(in, 0, o, 0, o.length);
                return o;
            }
            double step = inRate / (double) outRate;
            int n = (int) Math.max(0, Math.floor((frames - 1 - pos) / step) + 1);
            short[] o = new short[n * outCh];
            for (int k = 0; k < n; k++) {
                double x = pos + k * step;               // input position; -1..0 uses the held frame
                int i0 = (int) Math.floor(x);
                float t = (float) (x - i0);
                for (int c = 0; c < outCh; c++) {
                    float s0 = sample(in, i0, c), s1 = sample(in, Math.min(frames - 1, i0 + 1), c);
                    o[k * outCh + c] = (short) Math.max(-32768, Math.min(32767, Math.round(s0 + (s1 - s0) * t)));
                }
            }
            pos = pos + n * step - frames;              // carry the remainder into the next chunk
            prev = new float[inCh];
            for (int c = 0; c < inCh; c++) prev[c] = in[(frames - 1) * inCh + c];
            return o;
        }
    }
}
