package com.mytv.player;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Lossless MP3 editing: an MP3 is a chain of small frames, so cutting / splitting / merging
 * whole frames keeps the original quality (no re-encoding) and is instant.
 * Plain Java (tested outside Android).
 */
public final class Mp3Frames {
    private Mp3Frames() {
    }

    public static final class Frame {
        public int offset, length;
        public long startUs, durUs;
    }

    private static final int[][] BITRATE = {   // kbps: [MPEG1 L3], [MPEG2/2.5 L3]
            {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, -1},
            {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, -1}};
    private static final int[][] RATE = {{44100, 48000, 32000}, {22050, 24000, 16000}, {11025, 12000, 8000}};

    /** Frame length in bytes at `i`, or -1 when it isn't a valid MPEG-1/2/2.5 Layer III header. */
    static int frameAt(byte[] b, int i, long[] outSamplesRate) {
        if (i + 4 > b.length) return -1;
        int h = ((b[i] & 0xFF) << 24) | ((b[i + 1] & 0xFF) << 16) | ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
        if ((h & 0xFFE00000) != 0xFFE00000) return -1;
        int ver = (h >> 19) & 3;          // 3 = MPEG1, 2 = MPEG2, 0 = MPEG2.5, 1 = reserved
        int layer = (h >> 17) & 3;        // 1 = Layer III
        int bri = (h >> 12) & 15, sri = (h >> 10) & 3, pad = (h >> 9) & 1;
        if (ver == 1 || layer != 1 || bri == 0 || bri == 15 || sri == 3) return -1;
        boolean v1 = ver == 3;
        int kbps = BITRATE[v1 ? 0 : 1][bri];
        int rate = RATE[v1 ? 0 : ver == 2 ? 1 : 2][sri];
        int spf = v1 ? 1152 : 576;
        int len = (v1 ? 144 : 72) * kbps * 1000 / rate + pad;
        if (len < 24) return -1;
        if (outSamplesRate != null) {
            outSamplesRate[0] = spf;
            outSamplesRate[1] = rate;
        }
        return len;
    }

    /** Size of an ID3v2 tag at the start (0 when there is none). */
    static int id3Size(byte[] b) {
        if (b.length >= 10 && b[0] == 'I' && b[1] == 'D' && b[2] == '3') {
            int sz = ((b[6] & 0x7F) << 21) | ((b[7] & 0x7F) << 14) | ((b[8] & 0x7F) << 7) | (b[9] & 0x7F);
            boolean footer = (b[5] & 0x10) != 0;
            return 10 + sz + (footer ? 10 : 0);
        }
        return 0;
    }

    private static boolean isInfoFrame(byte[] b, int off, int len) {
        // Xing / Info / VBRI header frame (holds the old file's length — wrong after editing, so it's dropped)
        int end = Math.min(off + Math.min(len, 64), b.length - 4);
        for (int i = off + 4; i < end; i++) {
            if ((b[i] == 'X' && b[i + 1] == 'i' && b[i + 2] == 'n' && b[i + 3] == 'g')
                    || (b[i] == 'I' && b[i + 1] == 'n' && b[i + 2] == 'f' && b[i + 3] == 'o')
                    || (b[i] == 'V' && b[i + 1] == 'B' && b[i + 2] == 'R' && b[i + 3] == 'I')) return true;
        }
        return false;
    }

    /** All audio frames of a file (tags and the Xing/Info frame skipped). */
    public static List<Frame> frames(byte[] b) {
        List<Frame> out = new ArrayList<>();
        int i = id3Size(b);
        int end = b.length;
        if (end >= 128 && b[end - 128] == 'T' && b[end - 127] == 'A' && b[end - 126] == 'G') end -= 128;   // ID3v1
        long t = 0;
        long[] sr = new long[2];
        boolean first = true;
        while (i < end - 4) {
            int len = frameAt(b, i, sr);
            // accept a frame only if the next one also starts correctly (avoids false sync bytes)
            if (len > 0 && (i + len >= end - 4 || frameAt(b, i + len, null) > 0)) {
                if (first && isInfoFrame(b, i, len)) {
                    first = false;
                    i += len;
                    continue;
                }
                first = false;
                Frame f = new Frame();
                f.offset = i;
                f.length = Math.min(len, end - i);
                f.startUs = t;
                f.durUs = sr[0] * 1_000_000L / sr[1];
                t += f.durUs;
                out.add(f);
                i += len;
            } else i++;
        }
        return out;
    }

    public static long durationUs(List<Frame> f) {
        return f.isEmpty() ? 0 : f.get(f.size() - 1).startUs + f.get(f.size() - 1).durUs;
    }

    /** Frames from startUs to endUs (whole frames, ~26 ms precision). */
    public static byte[] cut(byte[] b, long startUs, long endUs) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (Frame f : frames(b)) {
            if (f.startUs + f.durUs / 2 < startUs) continue;
            if (f.startUs >= endUs) break;
            o.write(b, f.offset, f.length);
        }
        return withXing(o.toByteArray());
    }

    /** Several MP3s one after another. */
    public static byte[] merge(List<byte[]> files) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] b : files) for (Frame f : frames(b)) o.write(b, f.offset, f.length);
        return withXing(o.toByteArray());
    }

    /**
     * Puts a fresh Xing/Info header frame in front, holding the real frame count and size,
     * so players show the right length and seek correctly (essential for VBR / merged files).
     */
    static byte[] withXing(byte[] audio) {
        List<Frame> fr = frames(audio);
        if (fr.isEmpty()) return audio;
        int h = ((audio[0] & 0xFF) << 24) | ((audio[1] & 0xFF) << 16) | ((audio[2] & 0xFF) << 8) | (audio[3] & 0xFF);
        int ver = (h >> 19) & 3, mode = (h >> 6) & 3;
        boolean v1 = ver == 3, mono = mode == 3;
        int side = v1 ? (mono ? 17 : 32) : (mono ? 9 : 17);
        // same header as the first audio frame, at 128 kbps (MPEG1) / 64 kbps (MPEG2) so the tag fits; no padding, no CRC
        int bri = v1 ? 9 : 8;
        int hdr = (h & ~(0xF << 12) & ~(1 << 9)) | (bri << 12) | (1 << 16);
        byte[] hb = {(byte) (hdr >>> 24), (byte) (hdr >>> 16), (byte) (hdr >>> 8), (byte) hdr};
        int len = frameAt(hb.length >= 4 ? java.util.Arrays.copyOf(hb, 4) : hb, 0, null);
        if (len < 4 + side + 16) return audio;
        boolean vbr = false;
        int firstLen = fr.get(0).length;
        for (Frame f : fr) if (Math.abs(f.length - firstLen) > 1) {
            vbr = true;
            break;
        }
        byte[] x = new byte[len];
        System.arraycopy(hb, 0, x, 0, 4);
        int p = 4 + side;
        byte[] tag = (vbr ? "Xing" : "Info").getBytes();
        System.arraycopy(tag, 0, x, p, 4);
        p += 4;
        int flags = 0x3;                              // frames + bytes present
        int total = audio.length + len;
        int[] vals = {flags, fr.size(), total};
        for (int v : vals) {
            x[p++] = (byte) (v >>> 24);
            x[p++] = (byte) (v >>> 16);
            x[p++] = (byte) (v >>> 8);
            x[p++] = (byte) v;
        }
        byte[] out = new byte[total];
        System.arraycopy(x, 0, out, 0, len);
        System.arraycopy(audio, 0, out, len, audio.length);
        return out;
    }

    public static boolean looksLikeMp3(byte[] b) {
        List<Frame> f = frames(b.length > 200_000 ? java.util.Arrays.copyOf(b, 200_000) : b);
        return f.size() > 5;
    }
}
