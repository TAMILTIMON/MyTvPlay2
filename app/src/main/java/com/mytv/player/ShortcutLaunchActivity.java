package com.mytv.player;

import android.app.Activity;
import android.os.Bundle;

/** What a ZH Play widget / home-screen icon opens: launches that feature, then gets out of the way. */
public class ShortcutLaunchActivity extends Activity {
    static final String EXTRA_ID = "shortcut_id";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String id = getIntent().getStringExtra(EXTRA_ID);
        try {
            Shortcuts.launch(this, id == null ? "" : id);
        } catch (Exception e) {
            UiKit.makeToast(this, "⚠ " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
        }
        finish();
    }
}
