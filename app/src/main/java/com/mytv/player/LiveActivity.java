package com.mytv.player;

import android.app.Activity;
import android.os.Bundle;

/** Opened from a broadcast notification: plays what is live now (or opens ZH Play if it hasn't started). */
public class LiveActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String link = getIntent().getStringExtra("url");
        if (link != null && !link.trim().isEmpty()) {             // the admin's notification had a link
            link = link.trim();
            if (Formats.isVideo(link) || Formats.isAudio(link) || link.contains(".m3u8")) {
                Remote.play(this, java.util.Collections.singletonList(new Store.Item("ZH Play", link, "")), 0);
            } else if (link.contains("youtube.com") || link.contains("youtu.be")) {
                try {
                    startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link)));
                } catch (Exception e) {
                    startActivity(new android.content.Intent(this, BrowserActivity.class).putExtra(BrowserActivity.EXTRA_URL, link));
                }
            } else {
                startActivity(new android.content.Intent(this, BrowserActivity.class).putExtra(BrowserActivity.EXTRA_URL, link));
            }
        } else if ("live".equals(Broadcast.phase(Broadcast.current()))) Broadcast.watch(this);
        else startActivity(new android.content.Intent(this, MainActivity.class));
        finish();
    }
}
