package com.mytv.player;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Screen-cast stream between phone and TV (plain Java, tested outside Android).
 * Each message: [type:1][length:4][time µs:8][data].
 *   VIDEO_CONFIG: width:4, height:4, then the H.264 setup (SPS + PPS)
 *   VIDEO: one encoded H.264 frame   (flag in the type's high bit = key frame)
 *   AUDIO_CONFIG: rate:4, channels:4
 *   AUDIO: 16-bit PCM
 */
public final class MirrorProto {
    private MirrorProto() {
    }

    public static final int PORT = 8091;
    public static final byte VIDEO_CONFIG = 1, VIDEO = 2, AUDIO_CONFIG = 3, AUDIO = 4, BYE = 9;
    public static final int KEY = 0x80;
    private static final int MAX = 8 * 1024 * 1024;

    public static final class Msg {
        public int type;
        public boolean key;
        public long pts;
        public byte[] data;
    }

    public static synchronized void write(DataOutputStream o, int type, boolean key, long pts, byte[] data, int off, int len) throws IOException {
        o.writeByte(type | (key ? KEY : 0));
        o.writeInt(len);
        o.writeLong(pts);
        o.write(data, off, len);
        o.flush();
    }

    public static Msg read(DataInputStream in) throws IOException {
        Msg m = new Msg();
        int t = in.readUnsignedByte();
        m.key = (t & KEY) != 0;
        m.type = t & 0x7F;
        int len = in.readInt();
        if (len < 0 || len > MAX) throw new IOException("bad message size " + len);
        m.pts = in.readLong();
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }

    public static byte[] videoConfig(int w, int h, byte[] csd) {
        byte[] b = new byte[8 + csd.length];
        java.nio.ByteBuffer.wrap(b).putInt(w).putInt(h).put(csd);
        return b;
    }

    public static byte[] audioConfig(int rate, int ch) {
        byte[] b = new byte[8];
        java.nio.ByteBuffer.wrap(b).putInt(rate).putInt(ch);
        return b;
    }
}
