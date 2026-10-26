package com.mytv.player;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/** Instant key presses in every app through {@link KeyInjector} (started once via the ADB full remote). */
public final class FastKeys {
    private FastKeys() {
    }

    private static Socket sock;
    private static BufferedReader in;
    private static OutputStream out;
    private static long lastStart;

    private static synchronized String ask(String cmd) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (sock == null || sock.isClosed()) {
                    sock = new Socket();
                    sock.connect(new InetSocketAddress("127.0.0.1", KeyInjector.PORT), 250);
                    sock.setTcpNoDelay(true);
                    sock.setSoTimeout(3000);
                    in = new BufferedReader(new InputStreamReader(sock.getInputStream(), "UTF-8"));
                    out = sock.getOutputStream();
                }
                out.write((cmd + "\n").getBytes("UTF-8"));
                out.flush();
                return in.readLine();
            } catch (Exception e) {
                try {
                    if (sock != null) sock.close();
                } catch (Exception ignored) {
                }
                sock = null;
            }
        }
        return null;
    }

    /** Starts the key server on the TV (once) through ADB. */
    public static void ensure(final Context c) {
        if (!AdbClient.enabled() || System.currentTimeMillis() - lastStart < 15000) return;
        lastStart = System.currentTimeMillis();
        new Thread(() -> {
            if ("pong".equals(ask("ping"))) return;
            try {
                String apk = c.getApplicationInfo().sourceDir;
                AdbClient.shell(c, "(CLASSPATH=" + apk + " nohup app_process / " + KeyInjector.class.getName()
                        + " </dev/null >/dev/null 2>&1 &)");
            } catch (Exception ignored) {
            }
        }).start();
    }

    public static boolean ready() {
        return "pong".equals(ask("ping"));
    }

    /** true when pressed instantly; false = use the slower way this time. */
    public static boolean key(Context c, int code) {
        if (!AdbClient.enabled()) return false;
        String r = ask("key " + code);
        if ("ok".equals(r)) return true;
        ensure(c);
        return false;
    }

    public static boolean text(Context c, String t) {
        if (!AdbClient.enabled()) return false;
        try {
            String r = ask("text " + java.net.URLEncoder.encode(t, "UTF-8"));
            if ("ok".equals(r)) return true;
        } catch (Exception ignored) {
        }
        ensure(c);
        return false;
    }

    private static boolean lastIme;
    private static long lastImeAt;

    /** Is the TV's on-screen keyboard open? (checked at most every 1.2 s) */
    public static boolean imeShown(Context c) {
        if (!AdbClient.enabled()) return false;
        if (System.currentTimeMillis() - lastImeAt < 1200) return lastIme;
        lastImeAt = System.currentTimeMillis();
        String r = ask("ime");
        if (r == null) {
            ensure(c);
            return lastIme = false;
        }
        return lastIme = "1".equals(r);
    }
}
