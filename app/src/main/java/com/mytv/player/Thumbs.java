package com.mytv.player;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small preview pictures for "Continue watching" (local files and normal web links). */
public final class Thumbs {
    private Thumbs() {
    }

    private static final ExecutorService EX = Executors.newSingleThreadExecutor();

    public static String key(String url) {
        return Integer.toHexString(url.hashCode()) + Integer.toHexString(url.length());
    }

    public static File file(Context c, String url) {
        File d = new File(c.getFilesDir(), "thumbs");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return new File(d, key(url) + ".jpg");
    }

    public static File byKey(Context c, String k) {
        return new File(new File(c.getFilesDir(), "thumbs"), k.replaceAll("[^0-9a-f]", "") + ".jpg");
    }

    public static void request(final Context ctx, final String url, final long posMs) {
        final Context c = ctx.getApplicationContext();
        if (url.contains("/torrent/") || url.contains("/local/") || url.startsWith("content:")) return;
        if (!(url.startsWith("/") || url.startsWith("http"))) return;
        final File out = file(c, url);
        if (out.exists() && System.currentTimeMillis() - out.lastModified() < 10 * 60 * 1000) return;
        EX.execute(new Runnable() {
            public void run() {
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                try {
                    if (url.startsWith("/")) r.setDataSource(url);
                    else {
                        HashMap<String, String> h = new HashMap<>();
                        h.put("User-Agent", Net.UA);
                        r.setDataSource(url.split("\\|")[0], h);
                    }
                    Bitmap b = r.getFrameAtTime(Math.max(0, posMs) * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    if (b == null) return;
                    int w = 320, h = Math.max(1, b.getHeight() * w / Math.max(1, b.getWidth()));
                    Bitmap s = Bitmap.createScaledBitmap(b, w, h, true);
                    try (FileOutputStream o = new FileOutputStream(out)) {
                        s.compress(Bitmap.CompressFormat.JPEG, 80, o);
                    }
                } catch (Throwable ignored) {
                } finally {
                    try {
                        r.release();
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }
}
