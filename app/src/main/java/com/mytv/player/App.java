package com.mytv.player;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

public class App extends Application {
    /** Number of visible activities of this app (0 = app is in the background). */
    public static volatile int visible = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        Store.init(this);
        Net.cleanSubtitleCache(this);
        TorrentEngine.cleanupAsync(this);
        RemoteService.start(this);
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            public void onActivityStarted(Activity a) { visible++; }
            public void onActivityStopped(Activity a) { visible = Math.max(0, visible - 1); }
            public void onActivityCreated(Activity a, Bundle b) { }
            public void onActivityResumed(Activity a) { }
            public void onActivityPaused(Activity a) { }
            public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            public void onActivityDestroyed(Activity a) { }
        });
    }
}
