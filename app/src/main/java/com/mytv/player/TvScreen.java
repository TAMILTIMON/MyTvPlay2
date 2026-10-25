package com.mytv.player;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;

/** Live picture of the TV screen for the phone (through the ADB full remote). */
public final class TvScreen {
    private TvScreen() {
    }

    private static byte[] last;
    private static long lastAt;

    /** Small JPEG of the current screen. Protected (DRM) apps show black. */
    public static synchronized byte[] jpeg(Context c, int width) throws Exception {
        if (last != null && System.currentTimeMillis() - lastAt < 500) return last;
        if (!AdbClient.enabled()) throw new Exception("Turn on the 🔌 Full remote (ADB) on the TV to see its screen");
        byte[] png = AdbClient.execBytes(c, "screencap -p");
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(png, 0, png.length, o);
        int s = 1;
        while (o.outWidth / (s * 2) >= width) s *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = s;
        Bitmap b = BitmapFactory.decodeByteArray(png, 0, png.length, o);
        if (b == null) throw new Exception("No picture");
        if (b.getWidth() > width) b = Bitmap.createScaledBitmap(b, width, width * b.getHeight() / b.getWidth(), true);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 60, out);
        last = out.toByteArray();
        lastAt = System.currentTimeMillis();
        return last;
    }
}
