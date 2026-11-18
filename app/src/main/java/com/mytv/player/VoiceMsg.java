package com.mytv.player;

import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 🎙 Voice messages on the TV: a phone (at home or anywhere) sends a short recording; the TV opens it by itself,
 * pauses what's playing, plays it out loud, then carries on. The family can switch this off in TV ⚙ Settings.
 */
final class VoiceMsg {
    private VoiceMsg() {
    }

    static final long MAX_AGE = 24 * 3600_000L;      // a message waiting longer than this (TV was off) is dropped

    static boolean off() {
        return Store.getBool("voice_off", false);
    }

    static void setOff(boolean off) {
        Store.putBool("voice_off", off);
    }

    /** A message arrived. Returns null when shown, or why not. */
    static String receive(Context c, String from, long at, byte[] audio) {
        if (off()) return "Voice messages are switched off on this TV";
        if (audio == null || audio.length < 200) return "The recording is empty";
        if (at > 0 && System.currentTimeMillis() - at > MAX_AGE) return "Too old";
        try {
            File d = new File(c.getCacheDir(), "voice");
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
            File f = new File(d, "v_" + System.currentTimeMillis() + ".m4a");
            try (FileOutputStream o = new FileOutputStream(f)) {
                o.write(audio);
            }
            remember(c, f, from, at > 0 ? at : System.currentTimeMillis());
            Intent i = new Intent(c, VoiceMsgActivity.class)
                    .putExtra(VoiceMsgActivity.EXTRA_FILE, f.getAbsolutePath())
                    .putExtra(VoiceMsgActivity.EXTRA_FROM, from == null || from.isEmpty() ? "your phone" : from)
                    .putExtra(VoiceMsgActivity.EXTRA_AT, at > 0 ? at : System.currentTimeMillis())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            c.startActivity(i);
            return null;
        } catch (Exception e) {
            return "Couldn't play it: " + e.getMessage();
        }
    }

    /** The last 10 messages (newest first), so they can be heard again. Older recordings are removed. */
    private static synchronized void remember(Context c, File f, String from, long at) {
        try {
            JSONArray old = new JSONArray(Store.getStr("voice_hist", "[]"));
            JSONArray now = new JSONArray();
            now.put(new JSONObject().put("f", f.getAbsolutePath()).put("from", from).put("at", at));
            for (int i = 0; i < old.length(); i++) {
                JSONObject o = old.optJSONObject(i);
                if (o == null) continue;
                if (now.length() < 10) now.put(o);
                else //noinspection ResultOfMethodCallIgnored
                    new File(o.optString("f")).delete();
            }
            Store.putStr("voice_hist", now.toString());
        } catch (Exception ignored) {
        }
    }

    static JSONArray history() {
        try {
            JSONArray a = new JSONArray(Store.getStr("voice_hist", "[]")), ok = new JSONArray();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && new File(o.optString("f")).isFile()) ok.put(o);
            }
            return ok;
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    /** TV: messages that waited in the cloud (sent while the TV was off / offline) — play them, oldest first. */
    static void collectWaiting(final Context app) {
        new Thread(() -> {
            try {
                for (Object[] m : Cloud.takeVoice()) {
                    receive(app, (String) m[0], (Long) m[1], (byte[]) m[2]);
                    Thread.sleep(400);
                }
            } catch (Exception ignored) {
            }
        }).start();
    }
}
