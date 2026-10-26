package com.mytv.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Phone music player: keeps playing in the background, with notification + lock-screen controls. */
public class MusicService extends Service implements MediaPlayer.OnCompletionListener {
    static final String A_PLAY = "m.play", A_TOGGLE = "m.toggle", A_NEXT = "m.next", A_PREV = "m.prev", A_STOP = "m.stop";
    private static final String CH = "music";
    private static final int ID = 9;

    public static volatile MusicService inst;
    static List<PhoneMedia.M> queue = new ArrayList<>();
    static int index = -1;
    public static boolean shuffle;
    public static int repeat;       // 0 off, 1 all, 2 one
    private static final List<Runnable> listeners = new ArrayList<>();

    private MediaPlayer mp;
    private boolean prepared;
    private MediaSession session;
    private AudioManager am;
    private AudioFocusRequest focusReq;
    private boolean resumeOnFocus;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private Bitmap art;

    // ------------------------------------------------------------------ API for the screens
    public static void play(Context c, List<PhoneMedia.M> q, int i) {
        queue = new ArrayList<>(q);
        index = i;
        Intent s = new Intent(c, MusicService.class).setAction(A_PLAY);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(s);
        else c.startService(s);
    }

    public static void action(Context c, String a) {
        if (inst == null && !A_PLAY.equals(a)) return;
        Intent s = new Intent(c, MusicService.class).setAction(a);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(s);
        else c.startService(s);
    }

    public static PhoneMedia.M current() {
        return index >= 0 && index < queue.size() ? queue.get(index) : null;
    }

    public static boolean playing() {
        MusicService s = inst;
        return s != null && s.mp != null && s.prepared && s.mp.isPlaying();
    }

    public static long pos() {
        MusicService s = inst;
        try {
            return s != null && s.prepared ? s.mp.getCurrentPosition() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public static long len() {
        MusicService s = inst;
        try {
            return s != null && s.prepared ? s.mp.getDuration() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public static void seek(long ms) {
        MusicService s = inst;
        if (s != null && s.prepared) s.mp.seekTo((int) ms);
    }

    public static void listen(Runnable r) {
        synchronized (listeners) {
            if (!listeners.contains(r)) listeners.add(r);
        }
    }

    public static void unlisten(Runnable r) {
        synchronized (listeners) {
            listeners.remove(r);
        }
    }

    private void changed() {
        List<Runnable> l;
        synchronized (listeners) {
            l = new ArrayList<>(listeners);
        }
        for (Runnable r : l) main.post(r);
        updateSession();
        notifyNow();
    }

    // ------------------------------------------------------------------ service
    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        Store.init(this);
        shuffle = Store.getBool("m_shuffle", false);
        repeat = Store.getInt("m_repeat", 0);
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        session = new MediaSession(this, "TamilCastPlayMusic");
        session.setCallback(new MediaSession.Callback() {
            public void onPlay() { resume(); }
            public void onPause() { pause(); }
            public void onSkipToNext() { next(true); }
            public void onSkipToPrevious() { prev(); }
            public void onStop() { stopAll(); }
            public void onSeekTo(long p) { seek(p); }
        });
        session.setActive(true);
        registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
    }

    /** Headphones / Bluetooth unplugged → pause (don't blast the speaker). */
    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) {
            pause();
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        notifyNow();
        String a = intent == null ? null : intent.getAction();
        if (A_PLAY.equals(a)) playIndex(index);
        else if (A_TOGGLE.equals(a)) {
            if (playing()) pause();
            else resume();
        } else if (A_NEXT.equals(a)) next(true);
        else if (A_PREV.equals(a)) prev();
        else if (A_STOP.equals(a)) stopAll();
        return START_NOT_STICKY;
    }

    private void playIndex(int i) {
        PhoneMedia.M m = i >= 0 && i < queue.size() ? queue.get(i) : null;
        if (m == null) return;
        index = i;
        prepared = false;
        try {
            if (mp == null) {
                mp = new MediaPlayer();
                mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
                mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
                mp.setOnCompletionListener(this);
                mp.setOnErrorListener((p, w, e) -> {
                    main.postDelayed(() -> next(false), 600);
                    return true;
                });
            }
            mp.reset();
            mp.setDataSource(this, m.uri);
            mp.setOnPreparedListener(p -> {
                prepared = true;
                if (focus()) p.start();
                changed();
            });
            mp.prepareAsync();
        } catch (Exception e) {
            main.postDelayed(() -> next(false), 600);
        }
        art = null;
        new Thread(() -> {
            art = PhoneMedia.art(this, m, true);
            main.post(this::changed);
        }).start();
        changed();
    }

    private boolean focus() {
        int r;
        if (Build.VERSION.SDK_INT >= 26) {
            focusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setOnAudioFocusChangeListener(focusL).build();
            r = am.requestAudioFocus(focusReq);
        } else {
            r = am.requestAudioFocus(focusL, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
        return r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    /** Calls / other apps: pause, and continue afterwards when it was only for a moment. */
    private final AudioManager.OnAudioFocusChangeListener focusL = ch -> {
        if (ch == AudioManager.AUDIOFOCUS_LOSS) {
            resumeOnFocus = false;
            pause();
        } else if (ch == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            resumeOnFocus = playing();
            pause();
        } else if (ch == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            if (mp != null) mp.setVolume(0.3f, 0.3f);
        } else if (ch == AudioManager.AUDIOFOCUS_GAIN) {
            if (mp != null) mp.setVolume(1f, 1f);
            if (resumeOnFocus) resume();
            resumeOnFocus = false;
        }
    };

    void pause() {
        if (mp != null && prepared && mp.isPlaying()) mp.pause();
        changed();
    }

    void resume() {
        if (mp == null || !prepared) {
            playIndex(index);
            return;
        }
        if (focus()) mp.start();
        changed();
    }

    void next(boolean user) {
        if (queue.isEmpty()) return;
        int n;
        if (shuffle && queue.size() > 1) {
            do {
                n = rnd.nextInt(queue.size());
            } while (n == index);
        } else n = index + 1;
        if (n >= queue.size()) {
            if (repeat == 1 || user) n = 0;
            else {
                pause();
                return;
            }
        }
        playIndex(n);
    }

    void prev() {
        if (pos() > 4000) {
            seek(0);
            return;
        }
        playIndex(index - 1 < 0 ? queue.size() - 1 : index - 1);
    }

    @Override
    public void onCompletion(MediaPlayer p) {
        if (repeat == 2) playIndex(index);
        else next(false);
    }

    void stopAll() {
        if (mp != null) {
            try {
                mp.stop();
            } catch (Exception ignored) {
            }
        }
        prepared = false;
        changed();
        stopForeground(true);
        stopSelf();
    }

    // ------------------------------------------------------------------ media session + notification
    private void updateSession() {
        PhoneMedia.M m = current();
        if (session == null) return;
        session.setPlaybackState(new PlaybackState.Builder()
                .setState(playing() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, pos(), 1)
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO
                        | PlaybackState.ACTION_STOP).build());
        if (m != null) {
            MediaMetadata.Builder b = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, m.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, m.sub)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, len());
            if (art != null) b.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
            session.setMetadata(b.build());
        }
    }

    private PendingIntent pi(String a, int req) {
        Intent i = new Intent(this, MusicService.class).setAction(a);
        int f = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return Build.VERSION.SDK_INT >= 26 ? PendingIntent.getForegroundService(this, req, i, f) : PendingIntent.getService(this, req, i, f);
    }

    @SuppressWarnings("deprecation")
    private void notifyNow() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CH, "Music", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, CH);
        } else b = new Notification.Builder(this);
        PhoneMedia.M m = current();
        int f = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        b.setSmallIcon(R.drawable.lc_play)
                .setContentTitle(m == null ? "TamilCastPlay" : m.title)
                .setContentText(m == null ? "" : m.sub)
                .setLargeIcon(art)
                .setOngoing(playing())
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(PendingIntent.getActivity(this, 2, new Intent(this, MusicActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), f))
                .setDeleteIntent(pi(A_STOP, 25))
                .addAction(android.R.drawable.ic_media_previous, "Previous", pi(A_PREV, 21))
                .addAction(playing() ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        playing() ? "Pause" : "Play", pi(A_TOGGLE, 22))
                .addAction(android.R.drawable.ic_media_next, "Next", pi(A_NEXT, 23))
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2));
        Notification n = b.build();
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(ID, n);
            if (!playing() && prepared) stopForeground(false);   // paused: notification stays, can be swiped away
        } catch (Exception e) {
            nm.notify(ID, n);
        }
    }

    @Override
    public void onDestroy() {
        inst = null;
        try {
            unregisterReceiver(noisy);
        } catch (Exception ignored) {
        }
        if (mp != null) mp.release();
        if (session != null) session.release();
        if (Build.VERSION.SDK_INT >= 26 && focusReq != null) am.abandonAudioFocusRequest(focusReq);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
