package com.mytv.player;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * For files Android's own decoders can't read (some AVI / WMV / FLV / RM codecs, AC3 / DTS sound…):
 * the VLC engine inside the app turns the sound into a plain WAV file, which every audio tool can then use.
 */
public final class VlcAudio {
    private VlcAudio() {
    }

    private static final Map<String, File> done = new HashMap<>();   // same file again in this session: reuse

    /** Decodes the sound of `u` into a 16-bit stereo 44.1 kHz WAV (cached for this session). */
    public static synchronized File toWav(Context c, Uri u, AudioTools.Progress p) throws Exception {
        File cached = done.get(u.toString());
        if (cached != null && cached.length() > 44) return cached;
        File dir = new File(c.getCacheDir(), "vlc_audio");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        final File out = new File(dir, "a_" + System.nanoTime() + ".wav");
        ArrayList<String> opts = new ArrayList<>();
        opts.add("--no-video");
        opts.add("--no-spu");
        opts.add("--no-stats");
        LibVLC vlc = new LibVLC(c, opts);
        ParcelFileDescriptor pfd = null;
        Media m;
        if ("content".equals(u.getScheme())) {
            pfd = c.getContentResolver().openFileDescriptor(u, "r");
            m = new Media(vlc, Uri.parse("fd://" + pfd.getFd()));
        } else if ("file".equals(u.getScheme()) || u.toString().startsWith("/")) {
            m = new Media(vlc, u.getPath() != null ? u.getPath() : u.toString());
        } else m = new Media(vlc, u);
        m.addOption(":sout=#transcode{vcodec=none,scodec=none,acodec=s16l,channels=2,samplerate=44100}"
                + ":std{access=file,mux=wav,dst='" + out.getAbsolutePath() + "'}");
        m.addOption(":no-sout-video");
        m.addOption(":no-sout-spu");
        m.addOption(":sout-keep");
        final MediaPlayer mp = new MediaPlayer(vlc);
        final Object lock = new Object();
        final int[] state = {0};               // 1 = finished, 2 = error
        mp.setEventListener(e -> {
            if (e.type == MediaPlayer.Event.EndReached) state[0] = 1;
            else if (e.type == MediaPlayer.Event.EncounteredError) state[0] = 2;
            if (state[0] != 0) synchronized (lock) {
                lock.notifyAll();
            }
        });
        mp.setMedia(m);
        m.release();
        try {
            mp.play();
            long t0 = System.currentTimeMillis();
            while (state[0] == 0) {
                if (AudioTools.cancel) throw new Exception(T.t("Cancelled"));
                synchronized (lock) {
                    lock.wait(300);
                }
                if (p != null) p.on(Math.max(0f, Math.min(1f, mp.getPosition())), T.t("Reading with the VLC engine"));
                // nothing written after 60 s: give up instead of waiting forever
                if (System.currentTimeMillis() - t0 > 60000 && out.length() < 1024) throw new Exception(T.t("This file has no sound track"));
            }
        } finally {
            try {
                mp.stop();
            } catch (Exception ignored) {
            }
            mp.release();
            vlc.release();
            if (pfd != null) pfd.close();
        }
        if (state[0] == 2 || out.length() <= 44) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            throw new Exception(T.t("Even the VLC engine can't read the sound of this file"));
        }
        out.deleteOnExit();
        done.put(u.toString(), out);
        return out;
    }
}
