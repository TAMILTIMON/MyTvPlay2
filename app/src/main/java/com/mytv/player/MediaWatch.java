package com.mytv.player;

import android.service.notification.NotificationListenerService;

/**
 * Needed by Android so TamilCastPlay may see "what's playing" in other apps (YouTube, Netflix…).
 * Switched on once: TV ⚙ Settings > "Show other apps' videos on the remote".
 */
public class MediaWatch extends NotificationListenerService {
}
