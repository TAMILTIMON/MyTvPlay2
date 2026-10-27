package com.mytv.player;

import android.graphics.Color;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;

/** TV: shows a phone's screen (H.264 from {@link CastScreenService}) full screen, with its sound. Back = stop. */
public class MirrorActivity extends AppCompatActivity implements SurfaceHolder.Callback {
    public static volatile boolean open;
    private final Handler main = new Handler(Looper.getMainLooper());
    private SurfaceView view;
    private FrameLayout root;
    private TextView status;
    private volatile boolean alive = true;
    private volatile android.view.Surface surface;
    private ServerSocket server;
    private Socket sock;
    private MediaCodec dec;
    private int vw, vh;
    private final LinkedBlockingQueue<byte[]> audioQ = new LinkedBlockingQueue<>(50);
    private Thread audioThread;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        open = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        view = new SurfaceView(this);
        view.getHolder().addCallback(this);
        root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        status = Ui.text(this, 22, Color.WHITE, true);
        status.setText(T.t("Waiting for the phone's screen…"));
        status.setGravity(Gravity.CENTER);
        root.addView(status, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        new Thread(this::serve, "mirror").start();
    }

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        surface = h.getSurface();
    }

    @Override
    public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
        surface = null;
    }

    /** Keep the phone's shape (portrait phone = black bars at the sides). */
    private void fit() {
        if (vw <= 0 || vh <= 0) return;
        int W = root.getWidth(), H = root.getHeight();
        if (W == 0 || H == 0) return;
        float s = Math.min(W / (float) vw, H / (float) vh);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round(vw * s), Math.round(vh * s), Gravity.CENTER);
        view.setLayoutParams(lp);
    }

    private void serve() {
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(MirrorProto.PORT));
            server.setSoTimeout(45000);
            sock = server.accept();
            sock.setTcpNoDelay(true);
            sock.setReceiveBufferSize(1 << 20);
            DataInputStream in = new DataInputStream(new BufferedInputStream(sock.getInputStream(), 1 << 16));
            MirrorProto.Msg hello = MirrorProto.read(in);
            String tok = new String(hello.data, "UTF-8").trim();
            boolean local = sock.getInetAddress().isLoopbackAddress();
            if (!(local || !Pair.enabled() || Pair.valid(tok))) throw new Exception(T.t("This phone is not paired with the TV"));
            main.post(() -> status.setText(T.t("Connected — starting…")));
            while (alive) {
                MirrorProto.Msg m = MirrorProto.read(in);
                if (m.type == MirrorProto.BYE) break;
                if (m.type == MirrorProto.VIDEO_CONFIG) configure(m.data);
                else if (m.type == MirrorProto.VIDEO) frame(m);
                else if (m.type == MirrorProto.AUDIO_CONFIG) startAudio(m.data);
                else if (m.type == MirrorProto.AUDIO) audioQ.offer(m.data);   // full queue: drop (stay live)
            }
        } catch (final Exception e) {
            if (alive) main.post(() -> {
                status.setText("⚠ " + (e.getMessage() == null ? T.t("Casting stopped") : e.getMessage()));
                status.setVisibility(android.view.View.VISIBLE);
            });
        } finally {
            main.postDelayed(() -> {
                if (!isFinishing()) finish();
            }, 1500);
        }
    }

    private void configure(byte[] cfg) throws Exception {
        ByteBuffer b = ByteBuffer.wrap(cfg);
        vw = b.getInt();
        vh = b.getInt();
        byte[] csd = new byte[cfg.length - 8];
        b.get(csd);
        if (dec != null) {
            try {
                dec.stop();
                dec.release();
            } catch (Exception ignored) {
            }
        }
        for (int i = 0; i < 50 && surface == null; i++) Thread.sleep(40);
        MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, vw, vh);
        f.setByteBuffer("csd-0", ByteBuffer.wrap(csd));
        if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
        dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        dec.configure(f, surface, null, 0);
        dec.start();
        main.post(() -> {
            status.setVisibility(android.view.View.GONE);
            fit();
        });
    }

    private final MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();

    private void frame(MirrorProto.Msg m) {
        if (dec == null) return;
        try {
            int ii = dec.dequeueInputBuffer(20000);
            if (ii >= 0) {
                ByteBuffer ib = dec.getInputBuffer(ii);
                ib.clear();
                ib.put(m.data);
                dec.queueInputBuffer(ii, 0, m.data.length, m.pts, m.key ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
            }
            int oi;
            while ((oi = dec.dequeueOutputBuffer(bi, 0)) >= 0) dec.releaseOutputBuffer(oi, true);   // show at once
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    private void startAudio(byte[] cfg) {
        ByteBuffer b = ByteBuffer.wrap(cfg);
        final int rate = b.getInt(), ch = b.getInt();
        if (audioThread != null) return;
        audioThread = new Thread(() -> {
            int mask = ch == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
            int min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT);
            AudioTrack at = new AudioTrack(AudioManager.STREAM_MUSIC, rate, mask, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 3840 * 4),
                    AudioTrack.MODE_STREAM);
            at.play();
            try {
                while (alive) {
                    byte[] d = audioQ.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (d == null) continue;
                    if (audioQ.size() > 10) audioQ.clear();     // fell behind: jump back to live
                    at.write(d, 0, d.length);
                }
            } catch (Exception ignored) {
            } finally {
                at.stop();
                at.release();
            }
        }, "mirror-audio");
        audioThread.start();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override
    protected void onDestroy() {
        alive = false;
        open = false;
        try {
            if (sock != null) sock.close();
        } catch (Exception ignored) {
        }
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {
        }
        if (dec != null) {
            try {
                dec.stop();
                dec.release();
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }
}
