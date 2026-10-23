package com.mytv.player;

import android.content.Context;
import android.util.Base64;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

/**
 * Talks to the TV's own ADB (Developer options > network debugging, port 5555) so the phone
 * remote can press REAL keys in every app — YouTube, Netflix, settings, launcher.
 * The first time, the TV asks "Allow debugging?" — tick "Always allow".
 */
public final class AdbClient {
    private AdbClient() {
    }

    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f, OKAY = 0x59414b4f,
            CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final int VERSION = 0x01000000, MAXDATA = 4096;
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a,
            0x05, 0x00, 0x04, 0x14};

    private static Socket sock;
    private static DataInputStream in;
    private static OutputStream out;
    private static int nextId = 1;
    public static volatile String status = "off";

    public static boolean enabled() {
        return Store.getBool("adb_on", false);
    }

    // ------------------------------------------------------------------ keys
    private static KeyPair keys(Context c) throws Exception {
        File dir = new File(c.getFilesDir(), "adbkey");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File pri = new File(dir, "key"), pub = new File(dir, "key.pub");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (pri.exists() && pub.exists()) {
            return new KeyPair(kf.generatePublic(new X509EncodedKeySpec(read(pub))),
                    kf.generatePrivate(new PKCS8EncodedKeySpec(read(pri))));
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        write(pri, kp.getPrivate().getEncoded());
        write(pub, kp.getPublic().getEncoded());
        return kp;
    }

    private static byte[] read(File f) throws Exception {
        byte[] b = new byte[(int) f.length()];
        try (FileInputStream i = new FileInputStream(f)) {
            int off = 0, n;
            while (off < b.length && (n = i.read(b, off, b.length - off)) > 0) off += n;
        }
        return b;
    }

    private static void write(File f, byte[] b) throws Exception {
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(b);
        }
    }

    /** Android's own public key format for adb. */
    private static byte[] adbPublicKey(RSAPublicKey pk) throws Exception {
        final int words = 64;
        BigInteger r32 = BigInteger.ZERO.setBit(32);
        BigInteger n = pk.getModulus();
        BigInteger r = BigInteger.ZERO.setBit(words * 32);
        BigInteger rr = r.modPow(BigInteger.valueOf(2), n);
        BigInteger n0inv = n.remainder(r32).modInverse(r32);
        int[] myN = new int[words], myRr = new int[words];
        for (int i = 0; i < words; i++) {
            BigInteger[] res = rr.divideAndRemainder(r32);
            rr = res[0];
            myRr[i] = res[1].intValue();
            res = n.divideAndRemainder(r32);
            n = res[0];
            myN[i] = res[1].intValue();
        }
        ByteBuffer b = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words);
        b.putInt(n0inv.negate().intValue());
        for (int v : myN) b.putInt(v);
        for (int v : myRr) b.putInt(v);
        b.putInt(pk.getPublicExponent().intValue());
        String s = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " tamilcastplay@tv\u0000";
        return s.getBytes("UTF-8");
    }

    private static byte[] sign(PrivateKey key, byte[] token) throws Exception {
        Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        c.init(Cipher.ENCRYPT_MODE, key);
        c.update(SHA1_PREFIX);
        return c.doFinal(token);
    }

    // ------------------------------------------------------------------ wire
    private static void send(int cmd, int a0, int a1, byte[] data) throws Exception {
        int len = data == null ? 0 : data.length, sum = 0;
        if (data != null) for (byte x : data) sum += x & 0xff;
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(a0).putInt(a1).putInt(len).putInt(sum).putInt(~cmd);
        out.write(h.array());
        if (len > 0) out.write(data);
        out.flush();
    }

    private static final class Msg {
        int cmd, a0, a1;
        byte[] data;
    }

    private static Msg recv() throws Exception {
        byte[] hb = new byte[24];
        in.readFully(hb);
        ByteBuffer h = ByteBuffer.wrap(hb).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = h.getInt();
        m.a0 = h.getInt();
        m.a1 = h.getInt();
        int len = h.getInt();
        if (len < 0 || len > 1024 * 1024) throw new Exception("bad adb message");
        m.data = new byte[len];
        if (len > 0) in.readFully(m.data);
        return m;
    }

    private static void close() {
        try {
            if (sock != null) sock.close();
        } catch (Exception ignored) {
        }
        sock = null;
    }

    /**
     * Connects to the TV's adb. The first time it waits (up to a minute) for "Allow" on the TV.
     * Returns null when connected, else an error message.
     */
    public static synchronized String connect(Context c, boolean waitForAllow) {
        if (sock != null && sock.isConnected() && !sock.isClosed()) return null;
        try {
            KeyPair kp = keys(c.getApplicationContext());
            sock = new Socket();
            sock.connect(new InetSocketAddress("127.0.0.1", 5555), 2000);
            sock.setTcpNoDelay(true);
            sock.setSoTimeout(4000);
            in = new DataInputStream(sock.getInputStream());
            out = sock.getOutputStream();
            send(CNXN, VERSION, MAXDATA, "host::\u0000".getBytes("UTF-8"));
            boolean sentSig = false, sentKey = false;
            long until = System.currentTimeMillis() + (waitForAllow ? 60000 : 4000);
            while (System.currentTimeMillis() < until) {
                Msg m;
                try {
                    m = recv();
                } catch (java.net.SocketTimeoutException t) {
                    if (sentKey) continue;   // waiting for the user to press Allow on the TV
                    throw t;
                }
                if (m.cmd == CNXN) {
                    status = "connected";
                    sock.setSoTimeout(6000);
                    return null;
                }
                if (m.cmd == AUTH && m.a0 == 1) {
                    if (!sentSig) {
                        send(AUTH, 2, 0, sign(kp.getPrivate(), m.data));
                        sentSig = true;
                    } else if (!sentKey) {
                        status = "waiting for Allow on the TV";
                        send(AUTH, 3, 0, adbPublicKey((RSAPublicKey) kp.getPublic()));
                        sentKey = true;
                        if (!waitForAllow) {
                            close();
                            return "Press “Allow” on the TV (tick “Always allow”), then try again";
                        }
                    }
                }
            }
            close();
            status = "not allowed";
            return "The TV did not allow the connection";
        } catch (java.net.ConnectException e) {
            close();
            status = "adb off";
            return "ADB is off on the TV: turn on Developer options > USB/Network debugging";
        } catch (Exception e) {
            close();
            status = "error";
            return "ADB: " + e.getMessage();
        }
    }

    /** Runs one shell command on the TV. */
    public static synchronized String shell(Context c, String cmd) throws Exception {
        String err = connect(c, false);
        if (err != null) throw new Exception(err);
        int local = nextId++;
        try {
            send(OPEN, local, 0, ("shell:" + cmd + "\u0000").getBytes("UTF-8"));
            StringBuilder outp = new StringBuilder();
            int remote = 0;
            long until = System.currentTimeMillis() + 6000;
            while (System.currentTimeMillis() < until) {
                Msg m = recv();
                if (m.a1 != local && m.cmd != CNXN) continue;
                if (m.cmd == OKAY) remote = m.a0;
                else if (m.cmd == WRTE) {
                    outp.append(new String(m.data, "UTF-8"));
                    send(OKAY, local, m.a0, null);
                } else if (m.cmd == CLSE) {
                    send(CLSE, local, m.a0 != 0 ? m.a0 : remote, null);
                    return outp.toString();
                }
            }
            return outp.toString();
        } catch (Exception e) {
            close();
            throw e;
        }
    }

    /** Presses a key anywhere on the TV. Returns null when done. */
    public static String key(Context c, int keyCode) {
        try {
            shell(c, "input keyevent " + keyCode);
            return null;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    public static String text(Context c, String t) {
        try {
            String esc = t.replace("'", "'\\''").replace(" ", "%s");
            shell(c, "input text '" + esc + "'");
            return null;
        } catch (Exception e) {
            return e.getMessage();
        }
    }
}
