package com.mytv.player;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Screen cast to several TVs: everything goes to the main TV exactly as before, and each finished message
 * (MirrorProto flushes once per message) is copied to every other TV through its own queue and thread —
 * a slow TV never slows the others; if it falls behind it skips to the next full picture (key frame).
 */
final class GroupOut extends OutputStream {
    private final OutputStream primary;
    private final List<Peer> peers;
    private final ByteArrayOutputStream cur = new ByteArrayOutputStream(1 << 16);

    GroupOut(OutputStream primary, List<Peer> peers) {
        this.primary = primary;
        this.peers = peers;
    }

    @Override
    public void write(int b) throws IOException {
        primary.write(b);
        if (!peers.isEmpty()) cur.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        primary.write(b, off, len);
        if (!peers.isEmpty()) cur.write(b, off, len);
    }

    @Override
    public void flush() throws IOException {
        primary.flush();
        if (cur.size() > 0) {
            byte[] m = cur.toByteArray();
            cur.reset();
            for (Peer p : peers) p.offer(m);
        }
    }

    /** One extra TV. */
    static final class Peer {
        final String ip;
        final Socket sock;
        final DataOutputStream out;
        private final ArrayBlockingQueue<byte[]> q = new ArrayBlockingQueue<>(90);
        private volatile boolean waitKey, dead;

        Peer(String ip, Socket sock) throws IOException {
            this.ip = ip;
            this.sock = sock;
            this.out = new DataOutputStream(new java.io.BufferedOutputStream(sock.getOutputStream(), 1 << 16));
            Thread t = new Thread(this::send, "cast-to-" + ip);
            t.setDaemon(true);
            t.start();
        }

        void offer(byte[] m) {
            if (dead || m.length == 0) return;
            int type = m[0] & 0x7F;
            boolean key = (m[0] & 0x80) != 0;
            if (type == MirrorProto.VIDEO) {
                if (waitKey && !key) return;                     // behind: wait for the next full picture
                if (key) waitKey = false;
            }
            if (!q.offer(m)) {                                   // queue full: drop the backlog, restart at a key frame
                q.clear();
                waitKey = type != MirrorProto.VIDEO || !key;
                if (type != MirrorProto.VIDEO || key) q.offer(m);
            }
        }

        private void send() {
            try {
                while (!dead) {
                    byte[] m = q.take();
                    out.write(m);
                    out.flush();
                }
            } catch (Exception e) {
                dead = true;
            }
            close();
        }

        void close() {
            dead = true;
            try {
                sock.close();
            } catch (Exception ignored) {
            }
        }
    }
}
