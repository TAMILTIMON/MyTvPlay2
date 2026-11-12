package com.mytv.player;

import android.app.Activity;
import android.os.Bundle;

/** A thin, see-through screen whose only job is to show a panel feature (like VPN) that normally lives
 *  inside another screen — so it can still be its own home-screen shortcut. */
public class PanelHostActivity extends Activity {
    static final String EXTRA_PANEL = "panel";

    @Override
    protected void onCreate(Bundle b) {
        Ui.apply(this);
        super.onCreate(b);
        String panel = getIntent().getStringExtra(EXTRA_PANEL);
        if ("vpn".equals(panel)) {
            WgVpn.panel(this);
        } else {
            finish();
        }
    }
}
