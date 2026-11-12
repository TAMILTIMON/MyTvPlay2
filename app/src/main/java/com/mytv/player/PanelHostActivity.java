package com.mytv.player;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * A see-through screen that shows a panel feature (like VPN) on its own, for widgets / home icons.
 * It passes the VPN permission answer on to the panel, and closes itself once the panel is closed.
 */
public class PanelHostActivity extends Activity {
    static final String EXTRA_PANEL = "panel";
    private boolean panelWasShown;

    @Override
    protected void onCreate(Bundle b) {
        Ui.apply(this);
        super.onCreate(b);
        if ("vpn".equals(getIntent().getStringExtra(EXTRA_PANEL))) WgVpn.panel(this);
        else finish();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) panelWasShown = true;             // the panel (or Android's VPN prompt) is in front
        else if (panelWasShown) finish();                // back in front with nothing over it: the panel was closed
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        WgVpn.onResult(this, req, res, data);            // VPN permission / config file picked
    }
}
