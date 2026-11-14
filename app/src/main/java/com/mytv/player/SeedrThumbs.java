package com.mytv.player;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A picture for each Seedr video: a frame from about 20 seconds in (Seedr's file list has no pictures).
 * Made in the background, two at a time, then kept on the phone — so each video is only done once.
 */
final class SeedrThumbs {
    private SeedrThumbs() {
    }

    private static final ExecutorService pool = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> mem = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override
        protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount();
        }
    };

    private static File file(Context c, String id) {
        File d = new File(c.getCacheDir(), "seedr_thumbs");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return new File(d, id.replaceAll("[^A-Za-z0-9_-]", "_") + ".jpg");
    }

    /** Shows the video's picture in the view (keeps whatever placeholder it has until then). */
    static void load(final ImageView iv, final SeedrApi.SeedrFile f) {
        final String key = f.id;
        iv.setTag(key);
        Bitmap hit = mem.get(key);
        if (hit != null) {
            iv.setImageBitmap(hit);
            return;
        }
        final Context c = iv.getContext().getApplicationContext();
        pool.execute(() -> {
            if (!key.equals(iv.getTag())) return;              // scrolled away / screen changed
            Bitmap b = null;
            File cached = file(c, key);
            if (cached.length() > 0) b = BitmapFactory.decodeFile(cached.getPath());
            if (b == null) b = make(c, f, cached);
            if (b == null) return;
            mem.put(key, b);
            final Bitmap fb = b;
            MAIN.post(() -> {
                if (key.equals(iv.getTag())) iv.setImageBitmap(fb);
            });
        });
    }

    /** A frame ~20 s into the video (or its middle, for short ones), small enough for a card. */
    private static Bitmap make(Context c, SeedrApi.SeedrFile f, File out) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            String url = SeedrApi.fetchFileUrl(f.id);
            r.setDataSource(url, new HashMap<String, String>());
            long durMs = 0;
            try {
                durMs = Long.parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            } catch (Exception ignored) {
            }
            long atUs = (durMs > 0 ? Math.min(20_000L, durMs / 2) : 20_000L) * 1000L;
            Bitmap frame = r.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) return null;
            int w = 360, h = Math.max(1, Math.round(frame.getHeight() * (360f / frame.getWidth())));
            Bitmap small = Bitmap.createScaledBitmap(frame, w, h, true);
            if (small != frame) frame.recycle();
            try (FileOutputStream o = new FileOutputStream(out)) {
                small.compress(Bitmap.CompressFormat.JPEG, 82, o);
            } catch (Exception ignored) {
            }
            return small;
        } catch (Throwable e) {
            return null;                                       // not every file gives a picture: the card keeps its icon
        } finally {
            try {
                r.release();
            } catch (Throwable ignored) {
            }
        }
    }
}
