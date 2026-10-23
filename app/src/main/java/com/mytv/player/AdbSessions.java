package com.mytv.player;

import android.content.Context;
import android.os.SystemClock;

import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "What's playing in other apps" for low-RAM TVs, where Android blocks notification access:
 * reads `dumpsys media_session` through the ADB full remote and controls with media keys.
 */
public final class AdbSessions {
    private AdbSessions() {
    }

    private static JSONObject last;
    private static long lastAt;
    private static final Pattern STATE = Pattern.compile("state=PlaybackState \\{state=(\\d+), position=(-?\\d+)[^}]*?speed=([\\d.]+)[^}]*?updated=(\\d+)");
    private static final Pattern DESC = Pattern.compile("description=([^\\n]*)");

    public static boolean usable() {
        return AdbClient.enabled();
    }

    /** Cached for 2 s so several phones polling don't overload the TV. */
    public static synchronized JSONObject info(Context c) {
        if (!usable()) return null;
        if (System.currentTimeMillis() - lastAt < 2000) return fresh(last);
        lastAt = System.currentTimeMillis();
        last = null;
        try {
            String out = AdbClient.shell(c, "dumpsys media_session");
            JSONObject paused = null;
            // one block per session, each has a "package=" line
            String[] blocks = out.split("\\n\\s+package=");
            for (int i = 1; i < blocks.length; i++) {
                String b = blocks[i];
                String pkg = b.split("\\s")[0].trim();
                if (pkg.equals(c.getPackageName()) || pkg.isEmpty()) continue;
                Matcher m = STATE.matcher(b);
                if (!m.find()) continue;
                int st = Integer.parseInt(m.group(1));
                if (st != 3 && st != 2 && st != 6) continue;   // playing / paused / buffering
                long pos = Long.parseLong(m.group(2));
                float speed = Float.parseFloat(m.group(3));
                long upd = Long.parseLong(m.group(4));
                String title = "";
                Matcher d = DESC.matcher(b);
                if (d.find()) {
                    String[] parts = d.group(1).split(", ");
                    title = parts.length > 0 && !"null".equals(parts[0]) ? parts[0] : "";
                    if (parts.length > 1 && !"null".equals(parts[1])) title += " — " + parts[1];
                }
                JSONObject o = new JSONObject();
                o.put("app", AppsHub.label(c, pkg));
                o.put("pkg", pkg);
                o.put("title", title);
                o.put("artist", "");
                o.put("len", 0);
                o.put("pos0", Math.max(0, pos));
                o.put("upd", upd);
                o.put("speed", speed);
                o.put("playing", st == 3 || st == 6);
                o.put("seekable", false);
                if (st == 3 || st == 6) {
                    last = o;
                    break;
                }
                if (paused == null) paused = o;
            }
            if (last == null) last = paused;
        } catch (Exception ignored) {
        }
        return fresh(last);
    }

    /** Adds the current position (time moves on while playing). */
    private static JSONObject fresh(JSONObject o) {
        if (o == null) return null;
        try {
            JSONObject c = new JSONObject(o.toString());
            long pos = o.optLong("pos0");
            if (o.optBoolean("playing")) pos += (long) ((SystemClock.elapsedRealtime() - o.optLong("upd")) * o.optDouble("speed", 1));
            c.put("pos", Math.max(0, pos));
            return c;
        } catch (Exception e) {
            return null;
        }
    }

    /** Play / pause / next … for the app that is playing. Returns a message or null. */
    public static String control(Context c, String cmd, String v) {
        if (!usable()) return null;
        JSONObject o = info(c);
        if (o == null) return null;
        int key;
        switch (cmd) {
            case "toggle": key = 85; break;          // play / pause
            case "play": key = 126; break;
            case "pause": key = 127; break;
            case "stop": key = 86; break;
            case "next": key = 87; break;
            case "prev": key = 88; break;
            case "jump": key = Long.parseLong(v) >= 0 ? 90 : 89; break;   // fast-forward / rewind
            case "seek": return "This app does not allow jumping to a time from the remote — use ⏪ ⏩";
            default: return null;
        }
        String err = AdbClient.key(c, key);
        synchronized (AdbSessions.class) {
            lastAt = 0;   // refresh on the next poll
        }
        if (err != null) return err;
        String app = o.optString("app");
        switch (cmd) {
            case "toggle": return (o.optBoolean("playing") ? "❚❚ " : "▶ ") + app;
            case "next": return "⏭ " + app;
            case "prev": return "⏮ " + app;
            case "jump": return (Long.parseLong(v) >= 0 ? "⏩ " : "⏪ ") + app;
            default: return app;
        }
    }
}
