package com.mytv.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Surface;
import android.view.WindowManager;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.HashMap;

/**
 * Casts the whole phone screen (and, on Android 10+, its sound) to the TV:
 * Android's screen capture → H.264 encoder → Wi-Fi → ZH Play on the TV decodes and shows it full screen.
 */
public class CastScreenService extends Service {
    public static final String EXTRA_CODE = "code", EXTRA_DATA = "data", EXTRA_TV = "tv", EXTRA_HD = "hd", EXTRA_SOUND = "sound";
    private static final String CH = "cast_screen";
    private static final int ID = 14;

    public static volatile boolean running;
    public static volatile String status = "";

    private MediaProjection projection;
    private VirtualDisplay display;
    private MediaCodec enc;
    private Surface surface;
    private Socket sock;
    private DataOutputStream out;
    private volatile boolean alive;
    private AudioRecord rec;
    private boolean hd, sound;
    private String tv;
    private int w, h;
    private final Handler main = new Handler(Looper.getMainLooper());

    public static void start(Context c, int code, Intent data, String tv, boolean hd, boolean sound) {
        Intent i = new Intent(c, CastScreenService.class).putExtra(EXTRA_CODE, code).putExtra(EXTRA_DATA, data)
                .putExtra(EXTRA_TV, tv).putExtra(EXTRA_HD, hd).putExtra(EXTRA_SOUND, sound);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, CastScreenService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || "stop".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        // Android requires the foreground notification BEFORE getting the screen capture
        notifyNow(T.t("Connecting to the TV…"));
        tv = intent.getStringExtra(EXTRA_TV);
        hd = intent.getBooleanExtra(EXTRA_HD, false);
        sound = intent.getBooleanExtra(EXTRA_SOUND, true);
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        try {
            projection = mpm.getMediaProjection(intent.getIntExtra(EXTRA_CODE, 0), intent.getParcelableExtra(EXTRA_DATA));
        } catch (Exception e) {
            projection = null;
        }
        if (projection == null) {
            status = T.t("Screen capture was not allowed");
            stopSelf();
            return START_NOT_STICKY;
        }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                main.post(CastScreenService.this::stopSelf);
            }
        }, main);
        running = true;
        alive = true;
        new Thread(this::run, "cast-screen").start();
        return START_NOT_STICKY;
    }

    private void run() {
        try {
            // 1. the TV opens its screen-cast page and waits for us
            HashMap<String, String> p = new HashMap<>();
            p.put("token", "");
            TvClient.post(tv, "/mirror", p);
            for (int i = 0; i < 25 && sock == null && alive; i++) {
                try {
                    Socket s = new Socket();
                    s.connect(new InetSocketAddress(tv, MirrorProto.PORT), 800);
                    s.setTcpNoDelay(true);
                    s.setSendBufferSize(1 << 20);
                    sock = s;
                } catch (Exception e) {
                    Thread.sleep(300);
                }
            }
            if (sock == null) throw new Exception(T.t("The TV didn't answer"));
            out = new DataOutputStream(new BufferedOutputStream(sock.getOutputStream(), 1 << 16));
            // first line: the pairing token, so only paired phones can show their screen
            String token = TvClient.token(tv);
            byte[] t = ((token == null ? "" : token) + "\n").getBytes("UTF-8");
            MirrorProto.write(out, MirrorProto.BYE + 1, false, 0, t, 0, t.length);   // type 10 = hello
            main.post(() -> notifyNow(T.t("Casting your screen to the TV")));
            status = T.t("Casting your screen to the TV");
            if (sound && Build.VERSION.SDK_INT >= 29
                    && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                new Thread(this::audioLoop, "cast-audio").start();
            }
            videoLoop();
        } catch (Exception e) {
            status = "⚠ " + e.getMessage();
        } finally {
            main.post(this::stopSelf);
        }
    }

    // ------------------------------------------------------------------ video
    private int[] size() {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        int sw = dm.widthPixels, sh = dm.heightPixels, max = hd ? 1920 : 1280;
        float sc = Math.min(1f, max / (float) Math.max(sw, sh));
        int ow = Math.round(sw * sc) / 16 * 16, oh = Math.round(sh * sc) / 16 * 16;
        return new int[]{ow, oh, dm.densityDpi};
    }

    private void startEncoder(int[] sz) throws Exception {
        w = sz[0];
        h = sz[1];
        MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        f.setInteger(MediaFormat.KEY_BIT_RATE, hd ? 10_000_000 : 6_000_000);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        f.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000);   // still screen: keep sending
        if (Build.VERSION.SDK_INT >= 23) f.setInteger(MediaFormat.KEY_PRIORITY, 0);
        if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LATENCY, 1);
        enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        surface = enc.createInputSurface();
        enc.start();
        if (display == null) {
            display = projection.createVirtualDisplay("ZHPlayCast", w, h, sz[2], DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null);
        } else {
            display.resize(w, h, sz[2]);
            display.setSurface(surface);
        }
    }

    private void stopEncoder() {
        try {
            if (enc != null) {
                enc.stop();
                enc.release();
            }
        } catch (Exception ignored) {
        }
        enc = null;
        if (surface != null) surface.release();
        surface = null;
    }

    private void videoLoop() throws Exception {
        int[] sz = size();
        startEncoder(sz);
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        long lastCheck = 0;
        byte[] buf = new byte[1 << 18];
        while (alive) {
            // phone turned (portrait ↔ landscape): restart the encoder with the new shape
            if (System.currentTimeMillis() - lastCheck > 700) {
                lastCheck = System.currentTimeMillis();
                int[] ns = size();
                if (ns[0] != w || ns[1] != h) {
                    stopEncoder();
                    startEncoder(ns);
                }
            }
            int oi = enc.dequeueOutputBuffer(bi, 20000);
            if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat of = enc.getOutputFormat();
                ByteBuffer s0 = of.getByteBuffer("csd-0"), s1 = of.getByteBuffer("csd-1");
                byte[] csd = new byte[(s0 == null ? 0 : s0.remaining()) + (s1 == null ? 0 : s1.remaining())];
                int o = 0;
                if (s0 != null) {
                    int n = s0.remaining();
                    s0.get(csd, 0, n);
                    o = n;
                }
                if (s1 != null) s1.get(csd, o, s1.remaining());
                byte[] cfg = MirrorProto.videoConfig(w, h, csd);
                MirrorProto.write(out, MirrorProto.VIDEO_CONFIG, false, 0, cfg, 0, cfg.length);
            } else if (oi >= 0) {
                if ((bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && bi.size > 0) {
                    ByteBuffer ob = enc.getOutputBuffer(oi);
                    if (buf.length < bi.size) buf = new byte[bi.size * 2];
                    ob.position(bi.offset);
                    ob.get(buf, 0, bi.size);
                    MirrorProto.write(out, MirrorProto.VIDEO, (bi.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0, bi.presentationTimeUs, buf, 0, bi.size);
                }
                enc.releaseOutputBuffer(oi, false);
            }
        }
    }

    // ------------------------------------------------------------------ sound (Android 10+: what apps are playing)
    private void audioLoop() {
        if (Build.VERSION.SDK_INT < 29) return;
        try {
            AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build();
            AudioFormat af = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(48000)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build();
            int min = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord.Builder().setAudioFormat(af).setBufferSizeInBytes(Math.max(min, 3840 * 4))
                    .setAudioPlaybackCaptureConfig(cfg).build();
            rec.startRecording();
            byte[] cfgMsg = MirrorProto.audioConfig(48000, 2);
            MirrorProto.write(out, MirrorProto.AUDIO_CONFIG, false, 0, cfgMsg, 0, cfgMsg.length);
            byte[] b = new byte[3840];      // 20 ms
            while (alive) {
                int n = rec.read(b, 0, b.length);
                if (n > 0) MirrorProto.write(out, MirrorProto.AUDIO, false, System.nanoTime() / 1000, b, 0, n);
            }
        } catch (Exception ignored) {
        } finally {
            if (rec != null) {
                try {
                    rec.stop();
                } catch (Exception ignored) {
                }
                rec.release();
            }
        }
    }

    // ------------------------------------------------------------------ notification
    @SuppressWarnings("deprecation")
    private void notifyNow(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CH, "Screen cast", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, CH);
        } else b = new Notification.Builder(this);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent stop = PendingIntent.getService(this, 41, new Intent(this, CastScreenService.class).setAction("stop"), fl);
        b.setSmallIcon(R.drawable.lc_tv).setContentTitle("ZH Play · " + T.t("Screen cast")).setContentText(text)
                .setOngoing(true).addAction(android.R.drawable.ic_menu_close_clear_cancel, T.t("Stop"), stop);
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        else startForeground(ID, n);
    }

    @Override
    public void onDestroy() {
        alive = false;
        running = false;
        try {
            if (out != null) MirrorProto.write(out, MirrorProto.BYE, false, 0, new byte[0], 0, 0);
        } catch (Exception ignored) {
        }
        try {
            if (sock != null) sock.close();
        } catch (Exception ignored) {
        }
        stopEncoder();
        if (display != null) display.release();
        if (projection != null) projection.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
