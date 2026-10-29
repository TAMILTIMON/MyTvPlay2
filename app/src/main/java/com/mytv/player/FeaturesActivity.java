package com.mytv.player;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.LinkedHashMap;

/** "All features" — the same board on the TV and the phone: whiteboard, DJ, song editor, music, VPN … (customizable). */
public class FeaturesActivity extends AppCompatActivity {
    private LinkedHashMap<String, View> tiles;
    private FrameLayout box;
    private String key;

    @Override
    protected void onCreate(Bundle b) {
        Ui.apply(this);
        super.onCreate(b);
        boolean tv = Ui.isTv(this);
        key = tv ? "tv_features_board" : "phone_board";
        ScrollView sv = new ScrollView(this);
        sv.setBackground(Ui.background());
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, tv ? 40 : 16);
        root.setPadding(pad, Ui.dp(this, tv ? 28 : 16), pad, Ui.dp(this, 24));
        sv.addView(root);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(UiKit.darkButton(this, "arrow-left", 40, false, v -> finish()));
        TextView t = Ui.text(this, tv ? 26 : 20, Ui.TEXT, true);
        t.setText("  " + T.t("All features"));
        head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView cz = Ui.text(this, 14, Ui.ACCENT, true);
        cz.setText("✎ " + T.t("Customize"));
        cz.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        cz.setFocusable(true);
        cz.setBackground(Ui.focusCard(this, 12));
        cz.setOnClickListener(v -> customize());
        head.addView(cz);
        root.addView(head);
        tiles = new LinkedHashMap<>();
        FeatureBoard.add(this, tiles, tv);
        box = new FrameLayout(this);
        box.addView(BoardLayout.build(this, tiles, key));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.setMargins(0, Ui.dp(this, 14), 0, 0);
        root.addView(box, bl);
        setContentView(sv);
        if (tv) box.post(() -> {
            View first = box.focusSearch(View.FOCUS_DOWN);
            if (first != null) first.requestFocus();
        });
    }

    private void customize() {
        LinkedHashMap<String, String> names = new LinkedHashMap<>();
        for (java.util.Map.Entry<String, View> e : tiles.entrySet()) {
            View t = e.getValue().findViewWithTag("title");
            names.put(e.getKey(), t instanceof TextView ? ((TextView) t).getText().toString() : e.getKey());
        }
        BoardLayout.customize(this, names, () -> {
            box.removeAllViews();
            box.addView(BoardLayout.build(this, tiles, key));
        }, key);
    }

    @Override
    protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        WgVpn.onResult(this, req, res, data);
    }
}
