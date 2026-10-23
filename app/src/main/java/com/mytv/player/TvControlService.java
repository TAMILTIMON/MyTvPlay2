package com.mytv.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Phone: media-style notification (also on the lock screen) that controls the connected TV. */
public class TvControlService extends Service {
    private static final String CH = "tvcontrol";
    private static final int ID = 7;
    static final String A_TOGGLE = "tcp.toggle", A_STOP = "tcp.stop", A_REW = "tcp.rew", A_FF = "tcp.ff",
            A_NEXT = "tcp.next", A_CLOSE = "tcp.close";

    private String ip = "", name = "TV", title = "", sub = "";
    private boolean playing, running;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    public static void start(Context c, String ip, String name) {
        if (Ui.isTv(c) || !Store.getBool("notif_remote", true)) return;
        try {
            Intent i = new Intent(c, TvControlService.class).putExtra("ip", ip).putExtra("name", name);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getStringExtra("ip") != null) {
            ip = intent.getStringExtra("ip");
            name = intent.getStringExtra("name") == null ? "TV" : intent.getStringExtra("name");
        }
        if (ip.isEmpty()) ip = Store.getStr("last_tv", "");
        showNotification();
        String a = intent == null ? null : intent.getAction();
        if (A_CLOSE.equals(a)) {
            running = false;
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (a != null) {
            String c = null, v = "";
            switch (a) {
                case A_TOGGLE: c = "toggle"; playing = !playing; break;
                case A_STOP: c = "stop"; break;
                case A_REW: c = "jump"; v = "-10000"; break;
                case A_FF: c = "jump"; v = "10000"; break;
                case A_NEXT: c = "next"; break;
                default: break;
            }
            if (c != null) send(c, v);
            showNotification();
        }
        if (!running) {
            running = true;
            main.post(poll);
        }
        return START_STICKY;
    }

    private void send(final String c, final String v) {
        final String target = ip;
        io.execute(() -> {
            try {
                HashMap<String, String> p = new HashMap<>();
                p.put("c", c);
                p.put("v", v);
                TvClient.post(target, "/cmd", p);
            } catch (Exception ignored) {
            }
            main.postDelayed(poll, 400);
        });
    }

    private final Runnable poll = new Runnable() {
        public void run() {
            main.removeCallbacks(this);
            if (!running) return;
            final String target = ip;
            io.execute(() -> {
                try {
                    JSONObject s = TvClient.post(target, "/state", new HashMap<String, String>());
                    boolean p = s.optBoolean("player");
                    title = p ? s.optString("title") : T.t("Nothing playing");
                    playing = p && s.optBoolean("playing");
                    long pos = s.optLong("pos"), len = s.optLong("len");
                    sub = s.optString("device", name) + (p && len > 0 ? "  ·  " + Net.fmtTime(pos) + " / " + Net.fmtTime(len) : "");
                } catch (Exception e) {
                    title = T.t("Cannot reach the TV");
                    sub = name + " · " + target;
                }
                main.post(TvControlService.this::showNotification);
            });
            main.postDelayed(this, 3000);
        }
    };

    private PendingIntent pi(String action, int req) {
        Intent i = new Intent(this, TvControlService.class).setAction(action);
        int f = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getService(this, req, i, f);
    }

    @SuppressWarnings("deprecation")
    private void showNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            if (nm != null) nm.createNotificationChannel(new NotificationChannel(CH, "TV remote", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, CH);
        } else {
            b = new Notification.Builder(this);
        }
        Intent open = new Intent(this, NativeRemoteActivity.class)
                .putExtra(NativeRemoteActivity.EXTRA_IP, ip).putExtra(NativeRemoteActivity.EXTRA_NAME, name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int f = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        b.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title.isEmpty() ? "📺 " + name : title)
                .setContentText(sub.isEmpty() ? "TamilCastPlay" : sub)
                .setContentIntent(PendingIntent.getActivity(this, 1, open, f))
                .setOngoing(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(android.R.drawable.ic_media_rew, "-10s", pi(A_REW, 11))
                .addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        playing ? "Pause" : "Play", pi(A_TOGGLE, 12))
                .addAction(android.R.drawable.ic_media_ff, "+10s", pi(A_FF, 13))
                .addAction(android.R.drawable.ic_media_next, "Next", pi(A_NEXT, 14))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", pi(A_STOP, 15))
                .setStyle(new Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2))
                .setDeleteIntent(pi(A_CLOSE, 16));
        Notification n = b.build();
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(ID, n);
        } catch (Exception e) {
            if (nm != null) nm.notify(ID, n);
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
