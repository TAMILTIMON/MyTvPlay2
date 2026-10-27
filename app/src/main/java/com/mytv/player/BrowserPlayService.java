package com.mytv.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Keeps a video / song from the browser playing with the screen off or another app open (like Brave),
 * with notification + lock-screen controls. Runs only while the browser plays something.
 */
public class BrowserPlayService extends Service {
    static final String A_UPDATE = "b.update", A_TOGGLE = "b.toggle", A_BACK = "b.back", A_FWD = "b.fwd", A_STOP = "b.stop";
    private static final String CH = "browser_media";
    private static final int ID = 12;

    static volatile String title = "";
    static volatile boolean playing;
    static volatile boolean video;

    /** The browser sends commands to its page through this. */
    interface Control {
        void cmd(String c);
    }

    static volatile Control control;

    private MediaSession session;
    private PowerManager.WakeLock wake;

    public static void update(Context c, String t, boolean isPlaying, boolean isVideo) {
        title = t == null ? "" : t;
        playing = isPlaying;
        video = isVideo;
        try {
            Intent i = new Intent(c, BrowserPlayService.class).setAction(A_UPDATE);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception ignored) {
        }
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, BrowserPlayService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        session = new MediaSession(this, "ZHPlayBrowser");
        session.setCallback(new MediaSession.Callback() {
            public void onPlay() { send("play"); }
            public void onPause() { send("pause"); }
            public void onFastForward() { send("fwd"); }
            public void onRewind() { send("back"); }
            public void onSkipToNext() { send("fwd"); }
            public void onSkipToPrevious() { send("back"); }
            public void onStop() { stopSelf(); }
        });
        session.setActive(true);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ZHPlay:browser");
    }

    private void send(String c) {
        Control k = control;
        if (k != null) k.cmd(c);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (A_TOGGLE.equals(a)) send("toggle");
        else if (A_BACK.equals(a)) send("back");
        else if (A_FWD.equals(a)) send("fwd");
        else if (A_STOP.equals(a)) {
            send("pause");
            stopSelf();
            return START_NOT_STICKY;
        }
        show();
        if (playing && !wake.isHeld()) wake.acquire(4 * 3600 * 1000L);
        else if (!playing && wake.isHeld()) wake.release();
        return START_NOT_STICKY;
    }

    private PendingIntent pi(String a, int req) {
        Intent i = new Intent(this, BrowserPlayService.class).setAction(a);
        int f = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return Build.VERSION.SDK_INT >= 26 ? PendingIntent.getForegroundService(this, req, i, f) : PendingIntent.getService(this, req, i, f);
    }

    @SuppressWarnings("deprecation")
    private void show() {
        session.setPlaybackState(new PlaybackState.Builder()
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1)
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_FAST_FORWARD | PlaybackState.ACTION_REWIND | PlaybackState.ACTION_STOP).build());
        session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "ZH Play · " + T.t("Browser")).build());
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CH, "Browser media", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, CH);
        } else b = new Notification.Builder(this);
        int f = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        b.setSmallIcon(R.drawable.lc_play)
                .setLargeIcon(android.graphics.BitmapFactory.decodeResource(getResources(), R.drawable.zh_logo))
                .setContentTitle(title.isEmpty() ? T.t("Browser") : title)
                .setContentText(T.t(playing ? "Playing in the background" : "Paused"))
                .setOngoing(playing)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(PendingIntent.getActivity(this, 3, new Intent(this, BrowserActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), f))
                .setDeleteIntent(pi(A_STOP, 35))
                .addAction(android.R.drawable.ic_media_rew, "−10 s", pi(A_BACK, 31))
                .addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, playing ? "Pause" : "Play", pi(A_TOGGLE, 32))
                .addAction(android.R.drawable.ic_media_ff, "+10 s", pi(A_FWD, 33))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Close", pi(A_STOP, 34))
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2));
        Notification n = b.build();
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(ID, n);
            if (!playing) stopForeground(false);
        } catch (Exception e) {
            nm.notify(ID, n);
        }
    }

    @Override
    public void onDestroy() {
        if (wake != null && wake.isHeld()) wake.release();
        if (session != null) session.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
