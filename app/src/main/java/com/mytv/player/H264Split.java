package com.mytv.player;

import java.io.ByteArrayOutputStream;

/**
 * Cuts a raw H.264 byte stream (Android's "screenrecord --output-format=h264", any chunk sizes) into what a
 * decoder needs: the picture settings (SPS + PPS) once, then one frame at a time (key frames marked).
 * Plain Java (checked outside Android).
 */
public final class H264Split {
    public interface Out {
        /** SPS + PPS (with start codes) — when they first appear or change. */
        void config(byte[] spsPps) throws Exception;

        /** One frame (its NAL units with start codes); key = a full picture (IDR). */
        void frame(byte[] data, boolean key) throws Exception;
    }

    private final Out out;
    private final ByteArrayOutputStream buf = new ByteArrayOutputStream(1 << 18);
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream(1 << 17);    // SEI / AUD before a slice
    private byte[] sps, pps, sentConfig;

    public H264Split(Out out) {
        this.out = out;
    }

    /** Feed any amount of stream bytes. */
    public void feed(byte[] b, int off, int len) throws Exception {
        buf.write(b, off, len);
        byte[] a = buf.toByteArray();
        int start = findStart(a, 0);
        if (start < 0) return;
        int next;
        while ((next = findStart(a, start + 3)) >= 0) {
            nal(a, start, next);                 // a complete NAL unit: from one start code to the next
            start = next;
        }
        buf.reset();
        buf.write(a, start, a.length - start);   // the unfinished one waits for more bytes
    }

    /** Start of the next 00 00 01 / 00 00 00 01 at or after `from` (index of the first 00). */
    private static int findStart(byte[] a, int from) {
        for (int i = Math.max(0, from); i + 2 < a.length; i++) {
            if (a[i] == 0 && a[i + 1] == 0) {
                if (a[i + 2] == 1) return i > 0 && a[i - 1] == 0 && i - 1 >= from ? i - 1 : i;
                if (a[i + 2] == 0 && i + 3 < a.length && a[i + 3] == 1) return i;
            }
        }
        return -1;
    }

    private void nal(byte[] a, int s, int e) throws Exception {
        int h = a[s + 2] == 1 ? s + 3 : s + 4;
        if (h >= e) return;
        int type = a[h] & 0x1F;
        byte[] unit = new byte[e - s];
        System.arraycopy(a, s, unit, 0, unit.length);
        switch (type) {
            case 7: sps = unit; sendConfig(); break;
            case 8: pps = unit; sendConfig(); break;
            case 1:
            case 5: {
                pending.write(unit);
                out.frame(pending.toByteArray(), type == 5);
                pending.reset();
                break;
            }
            default:
                pending.write(unit);             // SEI, access unit delimiter: belong to the next frame
        }
    }

    private void sendConfig() throws Exception {
        if (sps == null || pps == null) return;
        byte[] c = new byte[sps.length + pps.length];
        System.arraycopy(sps, 0, c, 0, sps.length);
        System.arraycopy(pps, 0, c, sps.length, pps.length);
        if (sentConfig != null && java.util.Arrays.equals(sentConfig, c)) return;
        sentConfig = c;
        out.config(c);
    }

    /** A new stream begins (screenrecord restarted): forget the half-read bytes. */
    public void reset() {
        buf.reset();
        pending.reset();
    }
}
