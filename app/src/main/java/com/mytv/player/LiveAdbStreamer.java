package com.mytv.player;

import android.content.Context;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * On the TV, with the ADB full remote on: live screen through Android's own "screenrecord" (H.264, no
 * "Start now?" question) on a separate ADB connection → split into frames → the phone (same stream as Cast screen).
 * screenrecord stops after 3 minutes (Android's limit): it is started again at once.
 */
public final class LiveAdbStreamer {
    private LiveAdbStreamer() {
    }

    private static final AtomicBoolean alive = new AtomicBoolean(false);
    private static volatile Socket sock;

    public static boolean running() {
        return alive.get();
    }

    /** Starts streaming to the phone; onFail runs (once) if ADB streaming doesn't work, so the caller can fall back. */
    public static void start(final Context c, final String phoneIp, final int port, final String key, final Runnable onFail) {
        stop();
        alive.set(true);
        new Thread(() -> {
            boolean gotFrame = false;
            try {
                // the phone is connected only when the first picture is ready: if screenrecord can't stream here,
                // the phone's one connection stays free for the screen-capture fallback
                final DataOutputStream[] o = {null};
                final java.util.concurrent.Callable<DataOutputStream> phone = () -> {
                    if (o[0] != null) return o[0];
                    Socket s = new Socket();
                    s.connect(new InetSocketAddress(phoneIp, port), 3000);
                    s.setTcpNoDelay(true);
                    s.setSendBufferSize(1 << 20);
                    sock = s;
                    o[0] = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 1 << 16));
                    byte[] hello = (key + "\n").getBytes("UTF-8");
                    MirrorProto.write(o[0], MirrorProto.BYE + 1, false, 0, hello, 0, hello.length);
                    return o[0];
                };
                final int[] wh = size(c);
                final boolean[] any = {false};
                H264Split split = new H264Split(new H264Split.Out() {
                    public void config(byte[] spsPps) throws Exception {
                        ByteBuffer b = ByteBuffer.allocate(8 + spsPps.length);
                        b.putInt(wh[0]).putInt(wh[1]).put(spsPps);
                        byte[] cfg = b.array();
                        MirrorProto.write(phone.call(), MirrorProto.VIDEO_CONFIG, false, 0, cfg, 0, cfg.length);
                    }

                    public void frame(byte[] d, boolean k) throws Exception {
                        any[0] = true;
                        MirrorProto.write(phone.call(), MirrorProto.VIDEO, k, System.nanoTime() / 1000, d, 0, d.length);
                    }
                });
                String cmd = "screenrecord --output-format=h264 --size " + wh[0] + "x" + wh[1] + " --bit-rate 4000000 --time-limit 180 -";
                long started;
                do {                                                 // 3-minute limit: start again straight away
                    started = System.currentTimeMillis();
                    split.reset();
                    AdbClient.stream(c, cmd, split::feed, alive);
                    gotFrame |= any[0];
                    if (!any[0] && System.currentTimeMillis() - started < 4000) break;   // screenrecord can't stream here
                } while (alive.get());
                if (alive.get() && !gotFrame) throw new Exception("screenrecord gave no picture");
                if (alive.get() && o[0] != null) MirrorProto.write(o[0], MirrorProto.BYE, false, 0, new byte[0], 0, 0);
            } catch (Exception e) {
                if (alive.get() && !gotFrame && onFail != null) onFail.run();      // e.g. ADB off / not allowed
            } finally {
                alive.set(false);
                try {
                    if (sock != null) sock.close();
                } catch (Exception ignored) {
                }
                sock = null;
            }
        }, "live-adb").start();
    }

    public static void stop() {
        alive.set(false);
        try {
            if (sock != null) sock.close();
        } catch (Exception ignored) {
        }
    }

    /** 1280-wide picture in the TV's shape (encoder-friendly multiples of 16). */
    private static int[] size(Context c) {
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        ((android.view.WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
        int sw = Math.max(dm.widthPixels, dm.heightPixels), sh = Math.min(dm.widthPixels, dm.heightPixels);
        float k = Math.min(1f, 1280f / sw);
        return new int[]{Math.round(sw * k) / 16 * 16, Math.round(sh * k) / 16 * 16};
    }
}
