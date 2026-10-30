package com.mytv.player;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Saved karaoke sessions: decoded song + settings, so you can close and finish later. */
final class KaraokeProjects {
    private KaraokeProjects() {
    }

    static File root(Context c) {
        File d = new File(c.getFilesDir(), "karaoke_projects");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    static final class P {
        String id, title, uri;
        int rate = 44100;
        long frames;
        boolean mono;
        long savedAt;
        int mode;
        long playFrame;
        float strength = 1f, low = 120, high = 8000, focus = 2, bass, treble, width = 1, voice = 0.3f, gain;
        float oversub = 1.4f, formant = 0.7f, smear = 0.4f, residual = 0.55f, harmonics = 0.45f, smooth = 0.45f, drums = 0.35f;
        File dir;

        File pcm() {
            return new File(dir, "song.pcm");
        }

        File meta() {
            return new File(dir, "meta.json");
        }
    }

    static List<P> list(Context c) {
        File[] dirs = root(c).listFiles();
        List<P> out = new ArrayList<>();
        if (dirs == null) return out;
        for (File d : dirs) {
            if (!d.isDirectory()) continue;
            P p = read(d);
            if (p != null && p.pcm().exists()) out.add(p);
        }
        Collections.sort(out, new Comparator<P>() {
            public int compare(P a, P b) {
                return Long.compare(b.savedAt, a.savedAt);
            }
        });
        return out;
    }

    static P read(File dir) {
        try {
            byte[] raw = readAll(new File(dir, "meta.json"));
            if (raw == null) return null;
            JSONObject j = new JSONObject(new String(raw, "UTF-8"));
            P p = new P();
            p.dir = dir;
            p.id = dir.getName();
            p.title = j.optString("title", dir.getName());
            p.uri = j.optString("uri", "");
            p.rate = j.optInt("rate", 44100);
            p.frames = j.optLong("frames", 0);
            p.mono = j.optBoolean("mono", false);
            p.savedAt = j.optLong("savedAt", dir.lastModified());
            p.mode = j.optInt("mode", KaraokeDsp.MUSIC);
            p.playFrame = j.optLong("playFrame", 0);
            p.strength = (float) j.optDouble("strength", 1);
            p.low = (float) j.optDouble("low", 120);
            p.high = (float) j.optDouble("high", 8000);
            p.focus = (float) j.optDouble("focus", 2);
            p.bass = (float) j.optDouble("bass", 0);
            p.treble = (float) j.optDouble("treble", 0);
            p.width = (float) j.optDouble("width", 1);
            p.voice = (float) j.optDouble("voice", 0.3);
            p.gain = (float) j.optDouble("gain", 0);
            p.oversub = (float) j.optDouble("oversub", 1.4);
            p.formant = (float) j.optDouble("formant", 0.7);
            p.smear = (float) j.optDouble("smear", 0.4);
            p.residual = (float) j.optDouble("residual", 0.55);
            p.harmonics = (float) j.optDouble("harmonics", 0.45);
            p.smooth = (float) j.optDouble("smooth", 0.45);
            p.drums = (float) j.optDouble("drums", 0.35);
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    static P create(Context c, String title) {
        String id = "k" + Long.toString(System.currentTimeMillis(), 36);
        File dir = new File(root(c), id);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        P p = new P();
        p.id = id;
        p.dir = dir;
        p.title = title == null ? "" : title;
        p.savedAt = System.currentTimeMillis();
        return p;
    }

    static void write(P p) throws Exception {
        JSONObject j = new JSONObject();
        j.put("title", p.title);
        j.put("uri", p.uri);
        j.put("rate", p.rate);
        j.put("frames", p.frames);
        j.put("mono", p.mono);
        j.put("savedAt", p.savedAt = System.currentTimeMillis());
        j.put("mode", p.mode);
        j.put("playFrame", p.playFrame);
        j.put("strength", p.strength);
        j.put("low", p.low);
        j.put("high", p.high);
        j.put("focus", p.focus);
        j.put("bass", p.bass);
        j.put("treble", p.treble);
        j.put("width", p.width);
        j.put("voice", p.voice);
        j.put("gain", p.gain);
        j.put("oversub", p.oversub);
        j.put("formant", p.formant);
        j.put("smear", p.smear);
        j.put("residual", p.residual);
        j.put("harmonics", p.harmonics);
        j.put("smooth", p.smooth);
        j.put("drums", p.drums);
        FileOutputStream o = new FileOutputStream(p.meta());
        try {
            o.write(j.toString().getBytes("UTF-8"));
        } finally {
            o.close();
        }
    }

    static void copyPcm(File from, File to) throws Exception {
        if (from.getAbsolutePath().equals(to.getAbsolutePath())) return;
        FileChannel in = new FileInputStream(from).getChannel();
        try {
            FileChannel out = new FileOutputStream(to).getChannel();
            try {
                long n = in.size(), pos = 0;
                while (pos < n) pos += in.transferTo(pos, n - pos, out);
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    static boolean delete(P p) {
        if (p == null || p.dir == null) return false;
        File[] all = p.dir.listFiles();
        if (all != null) for (File f : all) //noinspection ResultOfMethodCallIgnored
            f.delete();
        return p.dir.delete();
    }

    static String when(P p) {
        long ago = Math.max(0, System.currentTimeMillis() - p.savedAt) / 1000;
        if (ago < 60) return T.t("just now");
        if (ago < 3600) return (ago / 60) + " " + T.t("min ago");
        if (ago < 86400) return (ago / 3600) + " " + T.t("h ago");
        return (ago / 86400) + " " + T.t("d ago");
    }

    static String size(P p) {
        long n = p.pcm().length();
        if (n < 1024 * 1024) return String.format(Locale.US, "%.0f KB", n / 1024f);
        return String.format(Locale.US, "%.1f MB", n / (1024f * 1024f));
    }

    private static byte[] readAll(File f) {
        try {
            if (!f.exists()) return null;
            int n = (int) f.length();
            byte[] b = new byte[n];
            FileInputStream in = new FileInputStream(f);
            try {
                int o = 0;
                while (o < n) {
                    int r = in.read(b, o, n - o);
                    if (r < 0) break;
                    o += r;
                }
            } finally {
                in.close();
            }
            return b;
        } catch (Exception e) {
            return null;
        }
    }
}
