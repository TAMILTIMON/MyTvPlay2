package com.mytv.player;

import android.app.Activity;
import android.os.Bundle;

/** What a pinned ZH Play shortcut icon actually opens: finds the real feature, launches it, then gets out of the way. */
public class ShortcutLaunchActivity extends Activity {
    static final String EXTRA_ID = "shortcut_id";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String id = getIntent().getStringExtra(EXTRA_ID);
        if (id != null) {
            for (Shortcuts.Item it : Shortcuts.all(this)) {
                if (it.id.equals(id)) {
                    it.launch.run();
                    break;
                }
            }
        }
        finish();
    }
}
