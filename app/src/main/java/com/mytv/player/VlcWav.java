package com.mytv.player;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Turns the sound of ANY video / audio file (AVI, FLV, WMV, MPG, MOV, TS, VOB, 3GP, WebM, MKV, AC3, DTS, WMA, APE …)
 * into a temporary 16-bit stereo WAV using the VLC engine that is already inside the app.
 * Used when Android's own MediaExtractor can't open the file.
 */
final class VlcWav {
    private VlcWav() {
    }

    static boolean available() {
        return true;
    }

    static File toWav(Context c, Uri u, final AudioTools.Progress p) throws Exception {
        final File out = AudioTools.temp(c, ".wav");
        ArrayList<String> opts = new ArrayList<>();
        opts.add("--no-video");
        opts.add("--no-sub-autodetect-file");
        opts.add("--no-osd");
        LibVLC vlc = null;
        MediaPlayer mp = null;
        ParcelFileDescriptor pfd = null;
        final CountDownLatch done = new CountDownLatch(1);
        final boolean[] failed = {false};
        try {
            vlc = new LibVLC(c, opts);
            mp = new MediaPlayer(vlc);
            Media m;
            if ("content".equals(u.getScheme())) {
                pfd = c.getContentResolver().openFileDescriptor(u, "r");
                if (pfd == null) throw new Exception("Can't open the file");
                m = new Media(vlc, Uri.parse("fd://" + pfd.getFd()));
            } else if ("file".equals(u.getScheme())) {
                m = new Media(vlc, u.getPath());
            } else {
                m = new Media(vlc, u);
            }
            m.addOption(":no-video");
            m.addOption(":sout=#transcode{acodec=s16l,channels=2,samplerate=44100}:std{access=file,mux=wav,dst=" + out.getAbsolutePath() + "}");
            m.addOption(":no-sout-all");
            m.addOption(":sout-keep");
            mp.setMedia(m);
            m.release();
            mp.setEventListener(new MediaPlayer.EventListener() {
                @Override
                public void onEvent(MediaPlayer.Event e) {
                    int t = e.type;
                    if (t == MediaPlayer.Event.EncounteredError) {
                        failed[0] = true;
                        done.countDown();
                    } else if (t == MediaPlayer.Event.EndReached || t == MediaPlayer.Event.Stopped) {
                        done.countDown();
                    }
                }
            });
            mp.play();
            if (p != null) p.on(0f, T.t("Preparing the sound…"));
            long last = System.currentTimeMillis(), lastSize = 0;
            while (!done.await(250, TimeUnit.MILLISECONDS)) {
                if (AudioTools.cancel) throw new Exception(T.t("Cancelled"));
                float pos = mp.getPosition();
                if (p != null && pos > 0) p.on(Math.min(0.99f, pos) * 0.5f, T.t("Preparing the sound…"));
                long sz = out.length();
                long now = System.currentTimeMillis();
                if (sz != lastSize) {
                    lastSize = sz;
                    last = now;
                } else if (now - last > 45_000) {   // nothing written for 45 s → give up
                    throw new Exception(T.t("This file has no sound track"));
                }
            }
            if (failed[0] || out.length() < 100) throw new Exception(T.t("Can't read this song"));
            return out;
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            throw e;
        } finally {
            try {
                if (mp != null) {
                    mp.setEventListener(null);
                    mp.stop();
                    mp.release();
                }
            } catch (Throwable ignored) {
            }
            try {
                if (vlc != null) vlc.release();
            } catch (Throwable ignored) {
            }
            try {
                if (pfd != null) pfd.close();
            } catch (Exception ignored) {
            }
        }
    }
}
