package com.mytv.player;

import net.sourceforge.lame.mp3.Lame;
import net.sourceforge.lame.mp3.MPEGMode;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * MP3 encoder (pure-Java port of LAME 3.98.4, LGPL — see net/sourceforge/lame/LICENSE-LGPL.txt).
 * Android has no built-in MP3 encoder, so this is what lets “save as MP3” work on every phone / TV.
 */
public final class Mp3Enc {
    private final Lame lame = new Lame();
    private final OutputStream out;
    private final int channels;
    private final byte[] buf = new byte[1 << 16];
    private float[] l = new float[0], r = new float[0];

    public Mp3Enc(File f, int rate, int channels, int kbps) throws IOException {
        this.channels = channels > 1 ? 2 : 1;
        out = new BufferedOutputStream(new FileOutputStream(f), 1 << 16);
        lame.getFlags().setInNumChannels(this.channels);
        lame.getFlags().setInSampleRate(rate);
        lame.getFlags().setMode(this.channels == 1 ? MPEGMode.MONO : MPEGMode.JOINT_STEREO);
        lame.getFlags().setBitRate(rate < 32000 ? Math.min(kbps, 160) : kbps);
        lame.getFlags().setQuality(3);
        lame.getId3().init(lame.getFlags());
        lame.getFlags().setWriteId3tagAutomatic(false);
        if (lame.initParams() < 0) throw new IOException("MP3 encoder: unsupported settings");
    }

    public int channels() {
        return channels;
    }

    /** `d` = interleaved 16-bit samples (channels as given to the constructor), `frames` = samples per channel. */
    public void write(short[] d, int frames) throws IOException {
        if (frames <= 0) return;
        if (l.length < frames) {
            l = new float[frames];
            r = new float[frames];
        }
        // Lame.encodeBuffer expects 16-bit samples scaled by 65536
        for (int i = 0; i < frames; i++) {
            l[i] = d[i * channels] * 65536f;
            r[i] = channels > 1 ? d[i * channels + 1] * 65536f : l[i];
        }
        int off = 0;
        while (off < frames) {
            int n = Math.min(frames - off, 8192);
            float[] a = off == 0 && n == frames ? l : java.util.Arrays.copyOfRange(l, off, off + n);
            float[] b = off == 0 && n == frames ? r : java.util.Arrays.copyOfRange(r, off, off + n);
            int got = lame.encodeBuffer(a, b, n, buf);
            if (got < 0) throw new IOException("MP3 encoder error " + got);
            if (got > 0) out.write(buf, 0, got);
            off += n;
        }
    }

    public void finish() throws IOException {
        int got = lame.encodeFlush(buf);
        if (got > 0) out.write(buf, 0, got);
        out.close();
        lame.close();
    }

    public void close() {
        try {
            out.close();
        } catch (IOException ignored) {
        }
    }
}
