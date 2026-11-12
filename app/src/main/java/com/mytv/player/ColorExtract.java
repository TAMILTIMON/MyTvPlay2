package com.mytv.player;

import android.graphics.Bitmap;
import android.graphics.Color;

/** Picks a real, vibrant colour out of a picture (not just "average grey mush"), for theme-matching. */
final class ColorExtract {
    private ColorExtract() {
    }

    /** The picture's main colour, or fallback if the picture is too grey/dark/bright to have one. */
    static int dominant(Bitmap src, int fallback) {
        if (src == null) return fallback;
        final int W = 48, H = 48;
        Bitmap small;
        try {
            small = Bitmap.createScaledBitmap(src, W, H, true);
        } catch (Throwable e) {
            return fallback;
        }
        int buckets = 24;                      // 15° hue slices
        double[] score = new double[buckets];
        double[] sumSat = new double[buckets];
        double[] sumVal = new double[buckets];
        int[] count = new int[buckets];
        float[] hsv = new float[3];
        int[] px = new int[W * H];
        small.getPixels(px, 0, W, 0, 0, W, H);
        for (int p : px) {
            if (Color.alpha(p) < 128) continue;
            Color.colorToHSV(p, hsv);
            float h = hsv[0], s = hsv[1], v = hsv[2];
            if (s < 0.18f || v < 0.12f || v > 0.97f) continue;    // skip near-grey, near-black, near-white
            int b = Math.min(buckets - 1, (int) (h / 360f * buckets));
            double weight = s * (0.4 + 0.6 * v);                  // favour strong, not-too-dark colours
            score[b] += weight;
            sumSat[b] += s;
            sumVal[b] += v;
            count[b]++;
        }
        int best = -1;
        double bestScore = 0;
        for (int i = 0; i < buckets; i++) if (score[i] > bestScore) {
            bestScore = score[i];
            best = i;
        }
        if (best < 0 || count[best] < (W * H) / 60) return fallback;   // too few real-colour pixels to trust
        float hue = (best + 0.5f) / buckets * 360f;
        float sat = Math.min(0.85f, Math.max(0.55f, (float) (sumSat[best] / count[best]) * 1.15f));
        float val = Math.min(0.9f, Math.max(0.55f, (float) (sumVal[best] / count[best])));
        return Color.HSVToColor(new float[]{hue, sat, val}) | 0xFF000000;
    }

    /** A second colour that pairs with the first (same technique the colour picker's "harmony" suggestions use). */
    static int pair(int main) {
        float[] hsv = new float[3];
        Color.colorToHSV(main, hsv);
        return Color.HSVToColor(new float[]{(hsv[0] + 35) % 360, Math.min(0.85f, hsv[1] + 0.1f), hsv[2]}) | 0xFF000000;
    }
}
