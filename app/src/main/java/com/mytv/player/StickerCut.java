package com.mytv.player;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.util.ArrayDeque;

/**
 * Cutting a sticker out of a photo:
 *  • AI cut-out — Google ML Kit's on-device person segmentation (bundled, works offline);
 *  • colour cut-out — no library: learns the background's colours from the photo's edges and removes what's
 *    connected to the edges in those colours (a white shirt in the middle stays, even on a white wall);
 *  • then: crop to the subject, a white outline like WhatsApp, optional text.
 * The mask is an int[] of alpha values (0 = removed … 255 = kept), one per pixel.
 */
final class StickerCut {
    private StickerCut() {
    }

    // ================================================================== AI cut-out (ML Kit)

    /** The person's mask, or null when the AI isn't available / found nobody. Runs on a background thread. */
    static int[] aiMask(Bitmap src) {
        com.google.mlkit.vision.segmentation.Segmenter seg = null;
        try {
            seg = com.google.mlkit.vision.segmentation.Segmentation.getClient(
                    new com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions.Builder()
                            .setDetectorMode(com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                            .build());
            com.google.mlkit.vision.segmentation.SegmentationMask m = com.google.android.gms.tasks.Tasks.await(
                    seg.process(com.google.mlkit.vision.common.InputImage.fromBitmap(src, 0)), 25, java.util.concurrent.TimeUnit.SECONDS);
            if (m == null) return null;
            int mw = m.getWidth(), mh = m.getHeight();
            java.nio.FloatBuffer conf = m.getBuffer().order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
            float[] f = new float[mw * mh];
            conf.get(f);
            int[] a = confidenceToAlpha(f);
            if (mw != src.getWidth() || mh != src.getHeight()) a = resize(a, mw, mh, src.getWidth(), src.getHeight());
            return kept(a) < 0.02f ? null : a;                     // found nobody
        } catch (Throwable unavailable) {
            return null;                                          // no AI on this phone / it failed → colour cut-out
        } finally {
            if (seg != null) try {
                seg.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** Model confidence → alpha with a soft edge (below 0.35 gone, above 0.65 fully kept). */
    static int[] confidenceToAlpha(float[] f) {
        int[] a = new int[f.length];
        for (int i = 0; i < f.length; i++) {
            float v = (f[i] - 0.35f) / 0.30f;
            a[i] = Math.round(255 * Math.max(0f, Math.min(1f, v)));
        }
        return a;
    }

    // ================================================================== colour cut-out (no library)

    /**
     * px: ARGB pixels (w×h). strength 1..100: how different a colour may be and still count as background.
     * Pure Java (no Android), so it can be tested anywhere.
     */
    static int[] colourMask(int[] px, int w, int h, int strength) {
        // 1) the background's colours: the photo's outer edge, grouped into up to 4 colours (k-means)
        int edgeN = 2 * w + 2 * Math.max(0, h - 2);
        int[] edge = new int[edgeN];
        int k = 0;
        for (int x = 0; x < w; x++) {
            edge[k++] = px[x];
            edge[k++] = px[(h - 1) * w + x];
        }
        for (int y = 1; y < h - 1; y++) {
            edge[k++] = px[y * w];
            edge[k++] = px[y * w + w - 1];
        }
        float[][] c = new float[4][3];
        for (int i = 0; i < 4; i++) {
            int p = edge[(int) ((long) i * (edgeN - 1) / 3)];
            c[i][0] = (p >> 16) & 255;
            c[i][1] = (p >> 8) & 255;
            c[i][2] = p & 255;
        }
        for (int it = 0; it < 8; it++) {
            float[][] sum = new float[4][3];
            int[] cnt = new int[4];
            for (int p : edge) {
                int j = nearest(c, p);
                sum[j][0] += (p >> 16) & 255;
                sum[j][1] += (p >> 8) & 255;
                sum[j][2] += p & 255;
                cnt[j]++;
            }
            for (int j = 0; j < 4; j++)
                if (cnt[j] > 0) for (int q = 0; q < 3; q++) c[j][q] = sum[j][q] / cnt[j];
        }
        // 2) from every edge pixel, flood across neighbours that look like the background
        float limit = 18 + strength * 0.9f;                       // colour distance that still counts as background
        float limit2 = limit * limit;
        boolean[] bg = new boolean[w * h];
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (int x = 0; x < w; x++) {
            seed(px, c, limit2, bg, q, x);
            seed(px, c, limit2, bg, q, (h - 1) * w + x);
        }
        for (int y = 0; y < h; y++) {
            seed(px, c, limit2, bg, q, y * w);
            seed(px, c, limit2, bg, q, y * w + w - 1);
        }
        while (!q.isEmpty()) {
            int i = q.poll(), x = i % w, y = i / w;
            if (x > 0) seed(px, c, limit2, bg, q, i - 1);
            if (x < w - 1) seed(px, c, limit2, bg, q, i + 1);
            if (y > 0) seed(px, c, limit2, bg, q, i - w);
            if (y < h - 1) seed(px, c, limit2, bg, q, i + w);
        }
        // 3) mask with a 1-pixel soft edge
        int[] a = new int[w * h];
        for (int i = 0; i < a.length; i++) a[i] = bg[i] ? 0 : 255;
        return soften(a, w, h);
    }

    private static void seed(int[] px, float[][] c, float limit2, boolean[] bg, ArrayDeque<Integer> q, int i) {
        if (bg[i]) return;
        if (dist2(c[nearest(c, px[i])], px[i]) <= limit2) {
            bg[i] = true;
            q.add(i);
        }
    }

    private static int nearest(float[][] c, int p) {
        int best = 0;
        float bd = Float.MAX_VALUE;
        for (int j = 0; j < c.length; j++) {
            float d = dist2(c[j], p);
            if (d < bd) {
                bd = d;
                best = j;
            }
        }
        return best;
    }

    private static float dist2(float[] c, int p) {
        float r = ((p >> 16) & 255) - c[0], g = ((p >> 8) & 255) - c[1], b = (p & 255) - c[2];
        return r * r + g * g + b * b;
    }

    /** 3×3 average: smooth, slightly soft edges instead of jagged pixels. */
    static int[] soften(int[] a, int w, int h) {
        int[] o = new int[a.length];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int s = 0, n = 0;
                for (int dy = -1; dy <= 1; dy++)
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx, yy = y + dy;
                        if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue;
                        s += a[yy * w + xx];
                        n++;
                    }
                o[y * w + x] = s / n;
            }
        return o;
    }

    /** Share of the picture that is kept (0..1). */
    static float kept(int[] a) {
        long s = 0;
        for (int v : a) s += v;
        return s / (255f * Math.max(1, a.length));
    }

    static int[] resize(int[] a, int w, int h, int nw, int nh) {
        int[] o = new int[nw * nh];
        for (int y = 0; y < nh; y++)
            for (int x = 0; x < nw; x++) o[y * nw + x] = a[Math.min(h - 1, y * h / nh) * w + Math.min(w - 1, x * w / nw)];
        return o;
    }

    // ================================================================== touch-up

    /** Paints the mask with a round brush (keep = true restores the photo, false erases it). */
    static void brush(int[] a, int w, int h, float cx, float cy, float r, boolean keep) {
        int x0 = Math.max(0, (int) (cx - r)), x1 = Math.min(w - 1, (int) (cx + r));
        int y0 = Math.max(0, (int) (cy - r)), y1 = Math.min(h - 1, (int) (cy + r));
        float r2 = r * r;
        for (int y = y0; y <= y1; y++)
            for (int x = x0; x <= x1; x++) {
                float dx = x - cx, dy = y - cy, d2 = dx * dx + dy * dy;
                if (d2 > r2) continue;
                float soft = Math.min(1f, (r2 - d2) / (r2 * 0.25f));     // soft rim
                int i = y * w + x;
                a[i] = keep ? Math.max(a[i], Math.round(255 * soft)) : Math.min(a[i], Math.round(255 * (1 - soft)));
            }
    }

    // ================================================================== the finished sticker

    /** The photo with the mask applied (transparent where removed). */
    static Bitmap apply(Bitmap src, int[] a) {
        int w = src.getWidth(), h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        for (int i = 0; i < px.length; i++) px[i] = (px[i] & 0x00FFFFFF) | (a[i] << 24);
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, w, 0, 0, w, h);
        return out;
    }

    /** Cropped to the subject, white outline, optional text underneath — fits in 512×512, transparent background. */
    static Bitmap sticker(Bitmap cut, int[] a, String text) {
        int w = cut.getWidth(), h = cut.getHeight();
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (a[y * w + x] > 40) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
        if (maxX < 0) return null;                                    // nothing left
        Rect box = new Rect(minX, minY, maxX + 1, maxY + 1);
        boolean hasText = text != null && !text.trim().isEmpty();
        int size = 512, pad = 26, textH = hasText ? 96 : 0;
        float k = Math.min((size - 2f * pad) / box.width(), (size - 2f * pad - textH) / box.height());
        float dw = box.width() * k, dh = box.height() * k;
        RectF dst = new RectF((size - dw) / 2f, pad + (size - 2f * pad - textH - dh) / 2f, 0, 0);
        dst.right = dst.left + dw;
        dst.bottom = dst.top + dh;
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        // the white outline: the shape drawn in white, nudged all around
        Paint white = new Paint(p);
        white.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        float ring = 7f;
        for (int i = 0; i < 16; i++) {
            double ang = Math.PI * 2 * i / 16;
            RectF o = new RectF(dst);
            o.offset((float) Math.cos(ang) * ring, (float) Math.sin(ang) * ring);
            cv.drawBitmap(cut, box, o, white);
        }
        cv.drawBitmap(cut, box, dst, p);
        if (hasText) {
            Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
            tp.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            tp.setTextAlign(Paint.Align.CENTER);
            tp.setTextSize(54);
            String t = text.trim();
            while (tp.measureText(t) > size - 30 && tp.getTextSize() > 26) tp.setTextSize(tp.getTextSize() - 2);
            float ty = size - pad - 20;
            tp.setStyle(Paint.Style.STROKE);
            tp.setStrokeWidth(10);
            tp.setStrokeJoin(Paint.Join.ROUND);
            tp.setColor(Color.WHITE);
            cv.drawText(t, size / 2f, ty, tp);
            tp.setStyle(Paint.Style.FILL);
            tp.setColor(0xFF1F1F2E);
            cv.drawText(t, size / 2f, ty, tp);
        }
        return out;
    }
}
