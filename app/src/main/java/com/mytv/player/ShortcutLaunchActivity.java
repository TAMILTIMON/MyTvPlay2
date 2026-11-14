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
        if (id == null && getIntent().getComponent() != null)
            id = Shortcuts.idForAppIcon(getIntent().getComponent().getClassName());   // tapped "ZH Browser" etc. in the app list
        try {
            Shortcuts.launch(this, id == null ? "" : id);
        } catch (Exception e) {
            UiKit.makeToast(this, "⚠ " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
        }
        finish();
    }
}
