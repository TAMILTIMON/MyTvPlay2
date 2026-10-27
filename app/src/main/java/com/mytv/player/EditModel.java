package com.mytv.player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Song editor model (plain Java, tested outside Android). Non-destructive: clips point into the original
 * sounds, so the songs on the phone never change and every step can be undone.
 * Everything is 44.1 kHz stereo; positions are in frames.
 */
public final class EditModel {
    public static final int RATE = 44100;

    public interface Pcm {
        long frames();

        short l(long i);

        short r(long i);
    }

    public static final class Source {
        public final Pcm pcm;
        public final String name;
        public float[] peaks;      // for drawing (one per 1024 frames)

        public Source(Pcm pcm, String name) {
            this.pcm = pcm;
            this.name = name;
        }
    }

    public static final class Clip {
        public Source src;
        public long srcA, srcB, at;
        public float g0 = 1f, g1 = 1f;    // gain at the start / end of the clip (fades, amplify)
        public boolean rev;

        public long len() {
            return srcB - srcA;
        }

        public long end() {
            return at + len();
        }

        Clip copy() {
            Clip c = new Clip();
            c.src = src;
            c.srcA = srcA;
            c.srcB = srcB;
            c.at = at;
            c.g0 = g0;
            c.g1 = g1;
            c.rev = rev;
            return c;
        }

        float gainAt(long j) {
            long n = len();
            return n <= 1 ? g0 : g0 + (g1 - g0) * (j / (float) (n - 1));
        }
    }

    public static final class Track {
        public String name;
        public final List<Clip> clips = new ArrayList<>();
        public float vol = 1f;
        public boolean mute, solo;

        Track copy() {
            Track t = new Track();
            t.name = name;
            t.vol = vol;
            t.mute = mute;
            t.solo = solo;
            for (Clip c : clips) t.clips.add(c.copy());
            return t;
        }

        void sort() {
            Collections.sort(clips, (a, b) -> Long.compare(a.at, b.at));
        }
    }

    public final List<Track> tracks = new ArrayList<>();
    private final List<List<Track>> undo = new ArrayList<>(), redo = new ArrayList<>();
    public List<Clip> clipboard = new ArrayList<>();

    // ------------------------------------------------------------------ undo / redo
    private List<Track> snap() {
        List<Track> s = new ArrayList<>();
        for (Track t : tracks) s.add(t.copy());
        return s;
    }

    /** Call before every change. */
    public void mark() {
        undo.add(snap());
        if (undo.size() > 40) undo.remove(0);
        redo.clear();
    }

    public boolean undo() {
        if (undo.isEmpty()) return false;
        redo.add(snap());
        restore(undo.remove(undo.size() - 1));
        return true;
    }

    public boolean redo() {
        if (redo.isEmpty()) return false;
        undo.add(snap());
        restore(redo.remove(redo.size() - 1));
        return true;
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    private void restore(List<Track> s) {
        tracks.clear();
        tracks.addAll(s);
    }

    // ------------------------------------------------------------------ building
    public Track addTrack(String name) {
        Track t = new Track();
        t.name = name;
        tracks.add(t);
        return t;
    }

    public void addClip(int ti, Source s, long at) {
        Clip c = new Clip();
        c.src = s;
        c.srcA = 0;
        c.srcB = s.pcm.frames();
        c.at = Math.max(0, at);
        tracks.get(ti).clips.add(c);
        tracks.get(ti).sort();
    }

    public long length() {
        long m = 0;
        for (Track t : tracks) for (Clip c : t.clips) m = Math.max(m, c.end());
        return m;
    }

    // ------------------------------------------------------------------ operations
    /** Cuts the clip under x into two (nothing happens between clips). */
    public void split(int ti, long x) {
        Track t = tracks.get(ti);
        for (int i = 0; i < t.clips.size(); i++) {
            Clip c = t.clips.get(i);
            if (x > c.at && x < c.end()) {
                long j = x - c.at;
                float gx = c.gainAt(j);
                Clip right = c.copy();
                right.at = x;
                if (!c.rev) {
                    right.srcA = c.srcA + j;
                    c.srcB = c.srcA + j;
                } else {           // reversed: the right half plays the earlier source part
                    right.srcB = c.srcB - j;
                    c.srcA = c.srcB - j;
                }
                right.g0 = gx;
                c.g1 = gx;
                t.clips.add(i + 1, right);
                return;
            }
        }
    }

    private List<Clip> inside(Track t, long a, long b) {
        List<Clip> l = new ArrayList<>();
        for (Clip c : t.clips) if (c.at >= a && c.end() <= b) l.add(c);
        return l;
    }

    /** Removes a..b. ripple = later sound moves left to close the gap. */
    public void delete(int ti, long a, long b, boolean ripple) {
        if (b <= a) return;
        split(ti, a);
        split(ti, b);
        Track t = tracks.get(ti);
        t.clips.removeAll(inside(t, a, b));
        if (ripple) for (Clip c : t.clips) if (c.at >= b) c.at -= (b - a);
        t.sort();
    }

    public void copy(int ti, long a, long b) {
        Track t = tracks.get(ti).copy();
        Track tmp = new Track();
        tmp.clips.addAll(t.clips);
        tracks.add(tmp);
        int k = tracks.size() - 1;
        split(k, a);
        split(k, b);
        clipboard = new ArrayList<>();
        for (Clip c : inside(tmp, a, b)) {
            Clip x = c.copy();
            x.at -= a;
            clipboard.add(x);
        }
        tracks.remove(k);
    }

    public void cut(int ti, long a, long b) {
        copy(ti, a, b);
        delete(ti, a, b, true);
    }

    /** Inserts the clipboard at x; later sound moves right to make room. */
    public void paste(int ti, long x) {
        if (clipboard.isEmpty()) return;
        long len = 0;
        for (Clip c : clipboard) len = Math.max(len, c.end());
        split(ti, x);
        Track t = tracks.get(ti);
        for (Clip c : t.clips) if (c.at >= x) c.at += len;
        for (Clip c : clipboard) {
            Clip n = c.copy();
            n.at += x;
            t.clips.add(n);
        }
        t.sort();
    }

    /** Keeps only a..b (moved to the start). */
    public void trim(int ti, long a, long b) {
        delete(ti, b, Math.max(b, length()) + 1, false);
        delete(ti, 0, a, true);
    }

    /** a..b becomes silent; the rest stays where it is. */
    public void silence(int ti, long a, long b) {
        delete(ti, a, b, false);
    }

    private void ramp(int ti, long a, long b, boolean up) {
        split(ti, a);
        split(ti, b);
        for (Clip c : inside(tracks.get(ti), a, b)) {
            float f0 = (c.at - a) / (float) (b - a), f1 = (c.end() - a) / (float) (b - a);
            if (!up) {
                f0 = 1 - f0;
                f1 = 1 - f1;
            }
            c.g0 *= f0;
            c.g1 *= f1;
        }
    }

    public void fadeIn(int ti, long a, long b) {
        ramp(ti, a, b, true);
    }

    public void fadeOut(int ti, long a, long b) {
        ramp(ti, a, b, false);
    }

    public void amplify(int ti, long a, long b, float factor) {
        split(ti, a);
        split(ti, b);
        for (Clip c : inside(tracks.get(ti), a, b)) {
            c.g0 *= factor;
            c.g1 *= factor;
        }
    }

    /** Loudest sample of a track between a and b (0…1). */
    public float peak(int ti, long a, long b) {
        float p = 0;
        Track t = tracks.get(ti);
        for (Clip c : t.clips) {
            long s = Math.max(a, c.at), e = Math.min(b, c.end());
            for (long x = s; x < e; x++) {
                float[] v = sample(c, x);
                p = Math.max(p, Math.max(Math.abs(v[0]), Math.abs(v[1])));
            }
        }
        return p / 32768f;
    }

    /** Makes the loudest moment of a..b reach −0.5 dB. Returns the factor used. */
    public float normalize(int ti, long a, long b) {
        float pk = peak(ti, a, b);
        if (pk <= 0.0001f) return 1f;
        float f = 0.944f / pk;
        amplify(ti, a, b, f);
        return f;
    }

    /** Plays a..b backwards. */
    public void reverse(int ti, long a, long b) {
        split(ti, a);
        split(ti, b);
        for (Clip c : inside(tracks.get(ti), a, b)) {
            c.at = a + (b - c.end());
            c.rev = !c.rev;
            float g = c.g0;
            c.g0 = c.g1;
            c.g1 = g;
        }
        tracks.get(ti).sort();
    }

    public void moveClip(int ti, Clip c, long newAt) {
        c.at = Math.max(0, newAt);
        tracks.get(ti).sort();
    }

    public Clip clipAt(int ti, long x) {
        if (ti < 0 || ti >= tracks.size()) return null;
        for (Clip c : tracks.get(ti).clips) if (x >= c.at && x < c.end()) return c;
        return null;
    }

    // ------------------------------------------------------------------ effects: a range of one track → processed sound
    /** One track only (no other tracks, volume or mute) — the input of an effect. */
    public void renderTrack(int ti, long from, int n, short[] out) {
        float[] L = new float[n], R = new float[n];
        for (Clip c : tracks.get(ti).clips) {
            long s = Math.max(from, c.at), e = Math.min(from + n, c.end());
            for (long x = s; x < e; x++) {
                float[] v = sample(c, x);
                L[(int) (x - from)] += v[0];
                R[(int) (x - from)] += v[1];
            }
        }
        for (int i = 0; i < n; i++) {
            out[2 * i] = DjCore.soft(L[i]);
            out[2 * i + 1] = DjCore.soft(R[i]);
        }
    }

    /** Puts `src` (the processed sound) in place of a..b; later sound moves if the length changed (speed). */
    public void replaceRange(int ti, long a, long b, Source src) {
        delete(ti, a, b, true);
        long len = src.pcm.frames();
        Track t = tracks.get(ti);
        for (Clip c : t.clips) if (c.at >= a) c.at += len;
        Clip n = new Clip();
        n.src = src;
        n.srcA = 0;
        n.srcB = len;
        n.at = a;
        t.clips.add(n);
        t.sort();
    }

    // ------------------------------------------------------------------ rendering (playback + export)
    private final float[] tmp = new float[2];

    private float[] sample(Clip c, long x) {
        long j = x - c.at;
        long i = c.rev ? c.srcB - 1 - j : c.srcA + j;
        float g = c.gainAt(j);
        tmp[0] = c.src.pcm.l(i) * g;
        tmp[1] = c.src.pcm.r(i) * g;
        return tmp;
    }

    /** Mixes all tracks for frames from..from+n into interleaved 16-bit stereo. */
    public void render(long from, int n, short[] out) {
        boolean anySolo = false;
        for (Track t : tracks) if (t.solo) anySolo = true;
        float[] L = new float[n], R = new float[n];
        for (Track t : tracks) {
            if (t.mute || (anySolo && !t.solo)) continue;
            for (Clip c : t.clips) {
                long s = Math.max(from, c.at), e = Math.min(from + n, c.end());
                for (long x = s; x < e; x++) {
                    float[] v = sample(c, x);
                    L[(int) (x - from)] += v[0] * t.vol;
                    R[(int) (x - from)] += v[1] * t.vol;
                }
            }
        }
        for (int i = 0; i < n; i++) {
            out[2 * i] = DjCore.soft(L[i]);
            out[2 * i + 1] = DjCore.soft(R[i]);
        }
    }
}
