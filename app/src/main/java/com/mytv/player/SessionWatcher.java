package com.mytv.player;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.provider.Settings;

import org.json.JSONObject;

import java.util.List;

/** Reads and controls what other apps are playing (title, time, play/pause/seek). */
public final class SessionWatcher {
    private SessionWatcher() {
    }

    public static boolean allowed(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(c.getPackageName());
    }

    /** Uses ADB (if on) to allow it without going into Settings. */
    public static String enableViaAdb(Context c) {
        try {
            AdbClient.shell(c, "cmd notification allow_listener " + c.getPackageName() + "/" + MediaWatch.class.getName());
            return allowed(c) ? null : "Could not switch it on";
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    public static MediaController active(Context c) {
        if (!allowed(c)) return null;
        try {
            MediaSessionManager m = (MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
            List<MediaController> l = m.getActiveSessions(new ComponentName(c, MediaWatch.class));
            MediaController paused = null;
            for (MediaController mc : l) {
                if (mc.getPackageName().equals(c.getPackageName())) continue;
                PlaybackState st = mc.getPlaybackState();
                if (st == null) continue;
                if (st.getState() == PlaybackState.STATE_PLAYING || st.getState() == PlaybackState.STATE_BUFFERING) return mc;
                if (paused == null && st.getState() == PlaybackState.STATE_PAUSED) paused = mc;
            }
            return paused;
        } catch (Exception e) {
            return null;
        }
    }

    private static long position(PlaybackState st) {
        long p = st.getPosition();
        if (st.getState() == PlaybackState.STATE_PLAYING) {
            p += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
        }
        return Math.max(0, p);
    }

    public static JSONObject info(Context c) {
        MediaController mc = active(c);
        if (mc == null) return null;
        try {
            JSONObject o = new JSONObject();
            MediaMetadata md = mc.getMetadata();
            PlaybackState st = mc.getPlaybackState();
            String title = md == null ? null : md.getString(MediaMetadata.METADATA_KEY_TITLE);
            if (title == null && md != null) title = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            String artist = md == null ? null : md.getString(MediaMetadata.METADATA_KEY_ARTIST);
            o.put("app", AppsHub.label(c, mc.getPackageName()));
            o.put("pkg", mc.getPackageName());
            o.put("title", title == null ? "" : title);
            o.put("artist", artist == null ? "" : artist);
            o.put("len", md == null ? 0 : Math.max(0, md.getLong(MediaMetadata.METADATA_KEY_DURATION)));
            o.put("pos", st == null ? 0 : position(st));
            o.put("playing", st != null && st.getState() == PlaybackState.STATE_PLAYING);
            o.put("seekable", st != null && (st.getActions() & PlaybackState.ACTION_SEEK_TO) != 0);
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    /** Returns a message, or null when there is no other app playing. */
    public static String control(Context c, String cmd, String v) {
        MediaController mc = active(c);
        if (mc == null) return null;
        MediaController.TransportControls t = mc.getTransportControls();
        PlaybackState st = mc.getPlaybackState();
        boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
        String app = AppsHub.label(c, mc.getPackageName());
        try {
            switch (cmd) {
                case "toggle":
                    if (playing) t.pause();
                    else t.play();
                    return (playing ? "❚❚ " : "▶ ") + app;
                case "play": t.play(); return "▶ " + app;
                case "pause": t.pause(); return "❚❚ " + app;
                case "stop": t.stop(); return "⏹ " + app;
                case "next": t.skipToNext(); return "⏭ " + app;
                case "prev": t.skipToPrevious(); return "⏮ " + app;
                case "seek": t.seekTo(Long.parseLong(v)); return "⏩ " + Net.fmtTime(Long.parseLong(v));
                case "jump": {
                    long to = Math.max(0, (st == null ? 0 : position(st)) + Long.parseLong(v));
                    t.seekTo(to);
                    return (Long.parseLong(v) > 0 ? "⏩ " : "⏪ ") + Net.fmtTime(to);
                }
                default: return null;
            }
        } catch (Exception e) {
            return e.getMessage();
        }
    }
}
