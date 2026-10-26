package com.mytv.player;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.LinkedBlockingQueue;

/** Phone side of {@link KeyLine}: keys leave instantly, in order, over one open connection. */
public final class KeyChannel {
    private final String ip;
    private final LinkedBlockingQueue<String> q = new LinkedBlockingQueue<>();
    private Socket sock;
    private OutputStream out;
    private volatile boolean alive = true;

    public interface Fallback {
        void send(String line);
    }

    private final Fallback fallback;

    public KeyChannel(String ip, Fallback fb) {
        this.ip = ip;
        this.fallback = fb;
        Thread t = new Thread(this::loop, "keys");
        t.setDaemon(true);
        t.start();
        q.offer("");   // connect right away so the first key is quick
    }

    public void key(String k) {
        q.offer("k " + k);
    }

    public void text(String t) {
        try {
            q.offer("t " + java.net.URLEncoder.encode(t, "UTF-8"));
        } catch (Exception ignored) {
        }
    }

    public void close() {
        alive = false;
        q.offer("");
        try {
            if (sock != null) sock.close();
        } catch (Exception ignored) {
        }
    }

    private boolean connect() {
        try {
            sock = new Socket();
            sock.connect(new InetSocketAddress(ip, KeyLine.PORT), 1500);
            sock.setTcpNoDelay(true);
            sock.setKeepAlive(true);
            sock.setSoTimeout(3000);
            out = sock.getOutputStream();
            out.write(("auth " + TvClient.token(ip) + "\n").getBytes("UTF-8"));
            out.flush();
            String r = new BufferedReader(new InputStreamReader(sock.getInputStream(), "UTF-8")).readLine();
            if (!"ok".equals(r)) throw new Exception("refused");
            sock.setSoTimeout(0);
            return true;
        } catch (Exception e) {
            try {
                if (sock != null) sock.close();
            } catch (Exception ignored) {
            }
            sock = null;
            return false;
        }
    }

    private void loop() {
        long failedAt = 0;
        while (alive) {
            String line;
            try {
                line = q.take();
            } catch (InterruptedException e) {
                return;
            }
            if (!alive) return;
            boolean sent = false;
            if (sock == null && System.currentTimeMillis() - failedAt > 5000 && !connect()) failedAt = System.currentTimeMillis();
            if (sock != null) {
                if (line.isEmpty()) continue;
                try {
                    out.write((line + "\n").getBytes("UTF-8"));
                    out.flush();
                    sent = true;
                } catch (Exception e) {
                    try {
                        sock.close();
                    } catch (Exception ignored) {
                    }
                    sock = null;
                    if (connect()) {
                        try {
                            out.write((line + "\n").getBytes("UTF-8"));
                            out.flush();
                            sent = true;
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
            if (!sent && !line.isEmpty() && fallback != null) fallback.send(line);   // old TVs: web request
        }
    }
}
