package com.mytv.player;

import android.content.Context;
import android.content.Intent;

import java.io.File;
import java.io.FileOutputStream;

/** 🌟 Stickers on the TV: a phone sends one; it pops up by itself, animated, without a background. */
final class StickerMsg {
    private StickerMsg() {
    }

    /** Shares the TV's switch with voice messages (⚙ Settings → Voice messages & stickers from phones). */
    static String receive(Context c, String from, long at, byte[] png, String anim) {
        if (VoiceMsg.off()) return "Stickers are switched off on this TV";
        if (png == null || png.length < 100) return "The sticker didn't arrive";
        if (at > 0 && System.currentTimeMillis() - at > VoiceMsg.MAX_AGE) return "Too old";
        try {
            File d = new File(c.getCacheDir(), "stickers");
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
            File[] old = d.listFiles();                         // keep the folder small
            if (old != null) for (File f : old) if (System.currentTimeMillis() - f.lastModified() > 3600_000L) //noinspection ResultOfMethodCallIgnored
                f.delete();
            File f = new File(d, "st_" + System.currentTimeMillis() + ".png");
            try (FileOutputStream o = new FileOutputStream(f)) {
                o.write(png);
            }
            c.startActivity(new Intent(c, StickerShowActivity.class)
                    .putExtra(StickerShowActivity.EXTRA_FILE, f.getAbsolutePath())
                    .putExtra(StickerShowActivity.EXTRA_ANIM, anim == null ? "bounce" : anim)
                    .putExtra(StickerShowActivity.EXTRA_FROM, from == null || from.isEmpty() ? "your phone" : from)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            return null;
        } catch (Exception e) {
            return "Couldn't show it: " + e.getMessage();
        }
    }

    /** TV: stickers that waited in the cloud (sent while the TV was off / offline). */
    static void collectWaiting(final Context app) {
        new Thread(() -> {
            try {
                for (Object[] m : Cloud.takeStickers()) {
                    receive(app, (String) m[0], (Long) m[1], (byte[]) m[2], (String) m[3]);
                    Thread.sleep(500);
                }
            } catch (Exception ignored) {
            }
        }).start();
    }
}
