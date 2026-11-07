package com.mytv.player;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

/** Shows the admin's broadcast notification while ZH Play is open (Android shows it by itself otherwise). */
public class PushService extends FirebaseMessagingService {
    @Override
    public void onMessageReceived(RemoteMessage m) {
        String title = "ZH Play", body = "";
        if (m.getNotification() != null) {
            if (m.getNotification().getTitle() != null) title = m.getNotification().getTitle();
            if (m.getNotification().getBody() != null) body = m.getNotification().getBody();
        } else if (m.getData() != null) {
            if (m.getData().get("title") != null) title = m.getData().get("title");
            if (m.getData().get("body") != null) body = m.getData().get("body");
        }
        final String link = m.getData() == null ? "" : m.getData().get("url");
        final android.app.Activity top = App.visible > 0 ? App.top() : null;
        if (top != null) {
            // ZH Play is open: a banner slides in at the top (tap = open the link / the live show)
            final String ft = title, fb = body;
            top.runOnUiThread(() -> ZhPopup.banner(top, ft, fb, ZhPopup.PUSH, () -> top.startActivity(
                    new android.content.Intent(top, LiveActivity.class).putExtra("url", link == null ? "" : link))));
        } else {
            Broadcast.notify(this, title, body, link);
        }
        Broadcast.sync(getApplicationContext(), true);
    }

    @Override
    public void onNewToken(String t) {
        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().subscribeToTopic("all");
        } catch (Throwable ignored) {
        }
    }
}
