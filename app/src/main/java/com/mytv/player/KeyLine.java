package com.mytv.player;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Fast key line from the phone remote: one open connection, one line per key, no waiting for answers.
 * First line must be "auth <token>" (same pairing token as the web remote).
 *   k <key name>      e.g. "k up", "k ok", "k vol_up", "k del", "k enter"
 *   t <url-encoded>   type text
 */
public final class KeyLine {
    public static final int PORT = 8081;
    private static boolean started;

    public static synchronized void start(final Context ctx) {
        if (started) return;
        started = true;
        new Thread(() -> {
            try {
                ServerSocket ss = new ServerSocket(PORT);
                while (true) {
                    final Socket s = ss.accept();
                    new Thread(() -> serve(ctx.getApplicationContext(), s)).start();
                }
            } catch (Exception e) {
                started = false;
            }
        }).start();
    }

    private static void serve(Context ctx, Socket s) {
        try {
            s.setTcpNoDelay(true);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
            String first = in.readLine();
            String ip = s.getInetAddress().getHostAddress();
            boolean local = s.getInetAddress().isLoopbackAddress();
            byte[] key = null;                                   // set when the channel is encrypted
            if (first != null && first.startsWith("eauth ")) {
                // "eauth <session> <sealed {t, z}>": who it is, inside the encryption
                String[] f = first.split(" ", 3);
                key = f.length == 3 ? ZhCrypt.tvKey(f[1]) : null;
                boolean ok = false;
                if (key != null) {
                    try {
                        org.json.JSONObject a = new org.json.JSONObject(ZhCrypt.tvOpen(key, f[2]));
                        String t = a.optString("t"), z = a.optString("z");
                        ok = local || !Pair.enabled() || Pair.valid(t) || ZhCrypt.ticketOk(t)
                                || (!z.isEmpty() && Cloud.signedIn() && Cloud.uid().equals(Cloud.verify(z)));
                    } catch (Exception ignored) {
                    }
                }
                if (!ok) {
                    s.close();
                    return;
                }
            } else if (first == null || !first.startsWith("auth ")
                    || !(local || Pair.valid(first.substring(5).trim()) || ZhCrypt.ticketOk(first.substring(5).trim()))) {
                s.close();
                return;
            }
            s.getOutputStream().write("ok\n".getBytes("UTF-8"));
            s.getOutputStream().flush();
            String line;
            while ((line = in.readLine()) != null) {
                try {
                    if (key != null) {                           // encrypted channel: only sealed lines count
                        if (!line.startsWith("e ")) continue;
                        line = ZhCrypt.tvOpen(key, line.substring(2).trim());
                    }
                    if (line.startsWith("k ")) Remote.press(ctx, line.substring(2).trim());
                    else if (line.startsWith("t ")) Remote.type(ctx, java.net.URLDecoder.decode(line.substring(2), "UTF-8"));
                } catch (Throwable ignored) {
                }
            }
        } catch (Exception ignored) {
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }
}
