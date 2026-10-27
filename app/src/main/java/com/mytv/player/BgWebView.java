package com.mytv.player;

import android.content.Context;
import android.view.View;
import android.webkit.WebView;

/**
 * WebView that keeps playing when it can't be seen (other app opened, screen locked, another screen on top).
 * Android's WebView pauses video as soon as its window becomes invisible; with "Background play" on,
 * this one keeps telling the engine it is visible (the usual technique of background-play browsers).
 */
public class BgWebView extends WebView {
    public BgWebView(Context c) {
        super(c);
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        if (visibility != View.VISIBLE && Store.getBool("b_bgplay", true)) {
            super.onWindowVisibilityChanged(View.VISIBLE);
            return;
        }
        super.onWindowVisibilityChanged(visibility);
    }
}
