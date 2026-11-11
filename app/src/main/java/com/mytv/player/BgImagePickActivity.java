package com.mytv.player;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Picks a picture from the gallery and saves it as the app's background (behind every screen). */
public class BgImagePickActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), 1);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) {
            finish();
            return;
        }
        final android.net.Uri u = data.getData();
        new Thread(() -> {
            try {
                android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
                int target = Math.max(dm.widthPixels, dm.heightPixels);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                try (InputStream in = getContentResolver().openInputStream(u)) {
                    BitmapFactory.decodeStream(in, null, o);
                }
                int s = 1;
                while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= target) s *= 2;
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = s;
                Bitmap b;
                try (InputStream in = getContentResolver().openInputStream(u)) {
                    b = BitmapFactory.decodeStream(in, null, o2);
                }
                if (b == null) throw new Exception("not a picture");
                File f = new File(getFilesDir(), "app_bg.jpg");
                try (FileOutputStream out = new FileOutputStream(f)) {
                    b.compress(Bitmap.CompressFormat.JPEG, 85, out);
                }
                Store.putBool("bg_image_on", true);
                Store.putInt("bg_image_gen", Store.getInt("bg_image_gen", 0) + 1);
                runOnUiThread(() -> {
                    Ui.invalidateBg();
                    UiKit.makeToast(this, "✓ " + T.t("Background set — it shows from the next screen you open"), Toast.LENGTH_LONG).show();
                    finish();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    UiKit.makeToast(this, "⚠ " + T.t("Can't use this picture") + ": " + e.getMessage(), Toast.LENGTH_LONG).show();
                    finish();
                });
            }
        }).start();
    }
}
