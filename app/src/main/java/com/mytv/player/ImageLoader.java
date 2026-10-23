package com.mytv.player;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small cached loader for channel logos. */
public final class ImageLoader {
    private ImageLoader() {
    }

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount();
        }
    };
    private static final ExecutorService EX = Executors.newFixedThreadPool(4);

    public static void load(final ImageView iv, final String url) {
        iv.setTag(url);
        Bitmap b = url == null || url.isEmpty() ? null : CACHE.get(url);
        if (b != null) {
            iv.setImageBitmap(b);
            return;
        }
        iv.setImageDrawable(null);
        if (url == null || url.isEmpty() || !url.startsWith("http")) return;
        EX.execute(() -> {
            try {
                byte[] d = Net.fetch(url, 1_500_000).data;
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(d, 0, d.length, o);
                int s = 1;
                while (o.outWidth / (s * 2) >= 96 && o.outHeight / (s * 2) >= 96) s *= 2;
                o = new BitmapFactory.Options();
                o.inSampleSize = s;
                final Bitmap bm = BitmapFactory.decodeByteArray(d, 0, d.length, o);
                if (bm == null) return;
                CACHE.put(url, bm);
                iv.post(() -> {
                    if (url.equals(iv.getTag())) iv.setImageBitmap(bm);
                });
            } catch (Exception ignored) {
            }
        });
    }
}
