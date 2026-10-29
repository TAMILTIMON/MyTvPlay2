package com.mytv.player;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;

/**
 * On the TV, invisible: asks Android for screen capture so the phone can watch this TV live.
 * With the ADB full remote on, ZH Play first allows itself permanently (appops PROJECT_MEDIA) — then the
 * question never appears; otherwise "Start now" shows once on the TV.
 */
public class LiveConsentActivity extends Activity {
    static final String IP = "ip", PORT = "port", KEY = "key";

    static void open(Context c, String phoneIp, int port, String key) {
        c.startActivity(new Intent(c, LiveConsentActivity.class).putExtra(IP, phoneIp).putExtra(PORT, port).putExtra(KEY, key)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        new Thread(() -> {
            if (AdbClient.enabled()) {
                try {
                    AdbClient.shell(this, "appops set " + getPackageName() + " PROJECT_MEDIA allow");   // never ask again
                } catch (Exception ignored) {
                }
            }
            runOnUiThread(() -> {
                try {
                    MediaProjectionManager m = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
                    startActivityForResult(m.createScreenCaptureIntent(), 7);
                } catch (Exception e) {
                    finish();
                }
            });
        }).start();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        Intent in = getIntent();
        if (req == 7 && res == RESULT_OK && data != null) {
            CastScreenService.startLive(this, res, data, in.getStringExtra(IP), in.getIntExtra(PORT, 0), in.getStringExtra(KEY));
        } else {
            CastScreenService.status = T.t("Screen capture was not allowed");
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
