package com.mytv.player;

import android.app.Activity;
import android.os.Bundle;

/** Opened from a broadcast notification: plays what is live now (or opens ZH Play if it hasn't started). */
public class LiveActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String link = getIntent().getStringExtra("url");
        String open = getIntent().getStringExtra("open");      // auto | player | browser (chosen by the admin)
        if (link != null && !link.trim().isEmpty()) {
            final String raw = link.trim(), mode = open == null ? "auto" : open;
            final String direct = Links.direct(raw);
            if ("browser".equals(mode)) {
                web(raw);
            } else if ("player".equals(mode) || Links.looksMedia(direct)) {
                play(direct);
            } else if (Links.isYouTube(raw)) {
                youtube(raw);
            } else {
                // no file extension (download pages, short links…): ask the server what it is
                ZhPopup.banner(this, null, T.t("Opening…"), ZhPopup.INFO, null);
                new Thread(() -> {
                    final boolean media = Links.probeMedia(direct);
                    runOnUiThread(() -> {
                        if (media) play(direct);
                        else web(raw);
                        finish();
                    });
                }).start();
                return;                                        // finish() after the check
            }
        } else if ("live".equals(Broadcast.phase(Broadcast.current()))) Broadcast.watch(this);
        else startActivity(new android.content.Intent(this, MainActivity.class));
        finish();
    }

    private void play(String url) {
        Remote.play(this, java.util.Collections.singletonList(new Store.Item("ZH Play", url, "")), 0);
    }

    private void web(String url) {
        startActivity(new android.content.Intent(this, BrowserActivity.class).putExtra(BrowserActivity.EXTRA_URL, url));
    }

    private void youtube(String url) {
        try {
            startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)));
        } catch (Exception e) {
            web(url);
        }
    }
}
