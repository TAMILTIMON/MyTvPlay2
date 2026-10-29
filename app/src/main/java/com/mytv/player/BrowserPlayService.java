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

    // what the page says (timeline + YouTube's song info)
    static volatile long posMs, durMs, at;
    static volatile String artist = "", artUrl = "";
    static volatile android.graphics.Bitmap art;
    private static volatile BrowserPlayService self;

    public static void update(Context c, String t, boolean isPlaying, boolean isVideo) {
        update(c, t, isPlaying, isVideo, -1, -1);
    }

    /** pos / dur in ms (−1 = unknown). A running service is updated directly — no new start (Android refuses
     *  starts from the background, which used to make the notification freeze or disappear after leaving the app). */
    public static void update(Context c, String t, boolean isPlaying, boolean isVideo, long pos, long dur) {
        title = t == null ? "" : t;
        playing = isPlaying;
        video = isVideo;
        if (pos >= 0) {
            posMs = pos;
            durMs = Math.max(0, dur);
            at = android.os.SystemClock.elapsedRealtime();
        }
        final BrowserPlayService s = self;
        if (s != null) {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(s::refresh);
            return;
        }
        try {
            Intent i = new Intent(c, BrowserPlayService.class).setAction(A_UPDATE);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception ignored) {
        }
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, BrowserPlayService.class));
        artist = "";
        artUrl = "";
        art = null;
        durMs = 0;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        session = new MediaSession(this, "ZHPlayBrowser");
        self = this;
        session.setCallback(new MediaSession.Callback() {
            public void onSeekTo(long ms) { send("seek:" + ms); }          // dragging the timeline
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
        refresh();
        return START_NOT_STICKY;
    }

    void refresh() {
        show();
        if (playing && !wake.isHeld()) wake.acquire(4 * 3600 * 1000L);
        else if (!playing && wake.isHeld()) wake.release();
    }

    /** The song's cover (from the page) — fetched once per picture. */
    static void artwork(final String url) {
        if (url == null || url.isEmpty() || url.equals(artUrl)) return;
        artUrl = url;
        new Thread(() -> {
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(8000);
                android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeStream(c.getInputStream());
                if (b != null && url.equals(artUrl)) {
                    art = b;
                    BrowserPlayService s = self;
                    if (s != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(s::refresh);
                }
            } catch (Exception ignored) {
            }
        }).start();
    }

    private PendingIntent pi(String a, int req) {
        Intent i = new Intent(this, BrowserPlayService.class).setAction(a);
        int f = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return Build.VERSION.SDK_INT >= 26 ? PendingIntent.getForegroundService(this, req, i, f) : PendingIntent.getService(this, req, i, f);
    }

    @SuppressWarnings("deprecation")
    private void show() {
        // position + speed: Android draws the timeline and moves it by itself between updates
        boolean known = durMs > 0;
        long pos = known ? posMs : PlaybackState.PLAYBACK_POSITION_UNKNOWN;
        session.setPlaybackState(new PlaybackState.Builder()
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, pos, playing ? 1f : 0f, at)
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_FAST_FORWARD | PlaybackState.ACTION_REWIND | PlaybackState.ACTION_STOP
                        | (known ? PlaybackState.ACTION_SEEK_TO : 0)).build());
        MediaMetadata.Builder md = new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist.isEmpty() ? "ZH Play · " + T.t("Browser") : artist);
        if (known) md.putLong(MediaMetadata.METADATA_KEY_DURATION, durMs);
        if (art != null) md.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
        session.setMetadata(md.build());
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CH, "Browser media", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, CH);
        } else b = new Notification.Builder(this);
        int f = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        b.setSmallIcon(R.drawable.lc_play)
                .setLargeIcon(art != null ? art : android.graphics.BitmapFactory.decodeResource(getResources(), R.drawable.zh_logo))
                .setContentTitle(title.isEmpty() ? T.t("Browser") : title)
                .setContentText(!artist.isEmpty() ? artist : T.t(playing ? "Playing in the background" : "Paused"))
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
        self = null;
        if (wake != null && wake.isHeld()) wake.release();
        if (session != null) session.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
