package com.mytv.player;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

/** Shows the admin's broadcast notification while ZH Play is open (Android shows it by itself otherwise). */
public class PushService extends FirebaseMessagingService {
    @Override
    public void onMessageReceived(RemoteMessage m) {
        if (m.getData() != null && "end".equals(m.getData().get("kind"))) {
            // the admin ended the broadcast: just sync (which cancels the stale notification) — show nothing new
            Broadcast.sync(getApplicationContext(), true);
            return;
        }
        String title = "ZH Play", body = "";
        if (m.getNotification() != null) {
            if (m.getNotification().getTitle() != null) title = m.getNotification().getTitle();
            if (m.getNotification().getBody() != null) body = m.getNotification().getBody();
        } else if (m.getData() != null) {
            if (m.getData().get("title") != null) title = m.getData().get("title");
            if (m.getData().get("body") != null) body = m.getData().get("body");
        }
        final String link = m.getData() == null ? "" : m.getData().get("url");
        final String open = m.getData() == null || m.getData().get("open") == null ? "auto" : m.getData().get("open");
        final boolean liveMsg = m.getData() != null && m.getData().get("bid") != null && !m.getData().get("bid").isEmpty();
        if (liveMsg ? !Broadcast.liveAlertsOn() : !Broadcast.newsOn()) {      // switched off by the person: stay quiet
            Broadcast.sync(getApplicationContext(), true);
            return;
        }
        final android.app.Activity top = App.visible > 0 ? App.top() : null;
        if (top != null) {
            // ZH Play is open: a banner slides in at the top (tap = open the link / the live show)
            final String ft = title, fb = body;
            top.runOnUiThread(() -> ZhPopup.banner(top, ft, fb, ZhPopup.PUSH, () -> top.startActivity(
                    new android.content.Intent(top, LiveActivity.class).putExtra("url", link == null ? "" : link).putExtra("open", open))));
        } else {
            Broadcast.notify(this, title, body, link, open, liveMsg);
        }
        Broadcast.sync(getApplicationContext(), true);
    }

    @Override
    public void onNewToken(String t) {
        try {
            Push.applyTopics();                              // keep the person's choice (live / announcements)
        } catch (Throwable ignored) {
        }
    }
}
