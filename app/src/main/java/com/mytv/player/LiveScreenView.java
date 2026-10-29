package com.mytv.player;

import android.content.Context;
import android.graphics.Color;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.HashMap;

/**
 * Phone: the TV's screen, live and smooth (the TV streams H.264 like Cast screen, the other way round).
 * The phone waits with a one-time key; frames are shown the moment they arrive (no buffering → low delay).
 * If no stream comes (older TV app, Linux PC, capture not allowed), onFail runs (the picture-by-picture view).
 */
public class LiveScreenView extends FrameLayout implements SurfaceHolder.Callback {
    public interface Fail {
        void on(String why);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final SurfaceView view;
    private final TextView status;
    private volatile Surface surface;
    private volatile boolean alive;
    private ServerSocket server;
    private Socket sock;
    private MediaCodec dec;
    private String tvIp;
    private int vw, vh;
    private Fail onFail;
    public volatile boolean showing;

    public LiveScreenView(Context c) {
        super(c);
        setBackgroundColor(Color.BLACK);
        view = new SurfaceView(c);
        view.getHolder().addCallback(this);
        addView(view, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER));
        status = Ui.text(c, 13, 0xCCFFFFFF, false);
        status.setGravity(Gravity.CENTER);
        status.setPadding(Ui.dp(c, 16), 0, Ui.dp(c, 16), 0);
        addView(status, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    public void surfaceCreated(SurfaceHolder h) {
        surface = h.getSurface();
    }

    public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {
        surface = h.getSurface();
    }

    public void surfaceDestroyed(SurfaceHolder h) {
        surface = null;
    }

    /** Asks the TV to stream its screen here. */
    public void start(final String ip, Fail fail) {
        stop();
        tvIp = ip;
        onFail = fail;
        alive = true;
        showing = false;
        status.setText(T.t("Starting the live picture…"));
        status.setVisibility(VISIBLE);
        final String key = Long.toString(Math.abs(new SecureRandom().nextLong()), 36);
        new Thread(() -> {
            try {
                server = new ServerSocket();
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(0));                 // any free port
                server.setSoTimeout(15000);
                HashMap<String, String> p = new HashMap<>();
                p.put("port", String.valueOf(server.getLocalPort()));
                p.put("key", key);
                org.json.JSONObject r = TvClient.post(ip, "/livescreen", p);
                if (!r.optBoolean("live")) throw new Exception("old");     // TV without live streaming
                final String m = r.optString("msg");
                main.post(() -> status.setText(m));
                sock = server.accept();
                sock.setTcpNoDelay(true);
                sock.setReceiveBufferSize(1 << 20);
                DataInputStream in = new DataInputStream(new BufferedInputStream(sock.getInputStream(), 1 << 16));
                MirrorProto.Msg hello = MirrorProto.read(in);
                if (!key.equals(new String(hello.data, "UTF-8").trim())) throw new Exception("wrong key");
                while (alive) {
                    MirrorProto.Msg msg = MirrorProto.read(in);
                    if (msg.type == MirrorProto.BYE) break;
                    if (msg.type == MirrorProto.VIDEO_CONFIG) configure(msg.data);
                    else if (msg.type == MirrorProto.VIDEO) frame(msg);
                }
                if (alive) fail(T.t("The TV stopped the live picture"));
            } catch (final Exception e) {
                if (alive) fail(e.getMessage());
            }
        }, "live-screen").start();
    }

    private void fail(final String why) {
        final boolean wasShowing = showing;
        stop();
        main.post(() -> {
            if (onFail != null) onFail.on(wasShowing ? why : null);
        });
    }

    private void configure(byte[] cfg) throws Exception {
        ByteBuffer b = ByteBuffer.wrap(cfg);
        vw = b.getInt();
        vh = b.getInt();
        byte[] csd = new byte[cfg.length - 8];
        b.get(csd);
        releaseDecoder();
        for (int i = 0; i < 50 && surface == null; i++) Thread.sleep(40);
        MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, vw, vh);
        f.setByteBuffer("csd-0", ByteBuffer.wrap(csd));
        if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
        dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        dec.configure(f, surface, null, 0);
        dec.start();
        showing = true;
        main.post(() -> {
            status.setVisibility(GONE);
            fit();
        });
    }

    /** Keep the TV's shape inside the box (black bars, no stretching). */
    private void fit() {
        if (vw <= 0 || vh <= 0 || getWidth() == 0) return;
        float s = Math.min(getWidth() / (float) vw, getHeight() / (float) vh);
        LayoutParams lp = new LayoutParams(Math.round(vw * s), Math.round(vh * s), Gravity.CENTER);
        view.setLayoutParams(lp);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        main.post(this::fit);
    }

    private final MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();

    private void frame(MirrorProto.Msg m) {
        MediaCodec d = dec;
        if (d == null) return;
        try {
            int ii = d.dequeueInputBuffer(20000);
            if (ii >= 0) {
                ByteBuffer ib = d.getInputBuffer(ii);
                ib.clear();
                ib.put(m.data);
                d.queueInputBuffer(ii, 0, m.data.length, m.pts, m.key ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
            }
            int oi;
            while ((oi = d.dequeueOutputBuffer(bi, 0)) >= 0) d.releaseOutputBuffer(oi, true);     // show at once
        } catch (Exception ignored) {
        }
    }

    private void releaseDecoder() {
        if (dec != null) {
            try {
                dec.stop();
            } catch (Exception ignored) {
            }
            try {
                dec.release();
            } catch (Exception ignored) {
            }
            dec = null;
        }
    }

    /** Stop watching (also tells the TV to stop capturing). */
    public void stop() {
        boolean was = alive;
        alive = false;
        showing = false;
        try {
            if (sock != null) sock.close();
        } catch (Exception ignored) {
        }
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {
        }
        sock = null;
        server = null;
        releaseDecoder();
        final String ip = tvIp;
        if (was && ip != null) new Thread(() -> {
            try {
                TvClient.post(ip, "/livestop", new HashMap<String, String>());
            } catch (Exception ignored) {
            }
        }).start();
    }
}
