package com.mytv.player;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.Map;

/** A QR code picture (ZXing). */
final class QrCode {
    private QrCode() {
    }

    static Bitmap make(String text, int size) {
        try {
            Map<EncodeHintType, Object> h = new EnumMap<>(EncodeHintType.class);
            h.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            h.put(EncodeHintType.MARGIN, 1);
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, h);
            int w = m.getWidth(), ht = m.getHeight();
            int[] px = new int[w * ht];
            for (int y = 0; y < ht; y++) for (int x = 0; x < w; x++) px[y * w + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
            Bitmap b = Bitmap.createBitmap(w, ht, Bitmap.Config.RGB_565);
            b.setPixels(px, 0, w, 0, 0, w, ht);
            return b;
        } catch (Exception e) {
            return null;
        }
    }
}
