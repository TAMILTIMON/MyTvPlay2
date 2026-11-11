package com.mytv.player;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.List;

/** TVs this phone has connected to before: tap one for its details, connect, or remove it; add one manually. */
public class PairedTvsActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout body;

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    private static GradientDrawable round(int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(r);
        return g;
    }

    private TextView text(float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable g = round(Ui.dark() ? 0xFF161D2E : 0xFFFFFFFF, dp(18));
        g.setStroke(dp(1), Ui.dark() ? 0x22FFFFFF : 0x14000000);
        c.setBackground(g);
        return c;
    }

    private LinearLayout.LayoutParams gap(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(top), 0, 0);
        return lp;
    }

    private TextView avatar(String name) {
        TextView a = text(16, Color.WHITE, true);
        String n = name == null || name.trim().isEmpty() ? "?" : name.trim();
        a.setText(n.substring(0, 1).toUpperCase(java.util.Locale.getDefault()));
        a.setGravity(Gravity.CENTER);
        int[] colors = {0xFF7C3AED, 0xFF2563EB, 0xFF059669, 0xFFDB2777, 0xFFEA580C};
        a.setBackground(round(colors[Math.abs(n.hashCode()) % colors.length], dp(20)));
        return a;
    }

    @Override
    protected void onCreate(Bundle b) {
        Ui.apply(this);
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(14), dp(14), dp(14), dp(6));
        head.addView(UiKit.darkButton(this, "arrow-left", 42, false, v -> finish()), new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView t = text(21, Ui.TEXT, true);
        t.setText("📺  " + T.t("Paired TVs"));
        t.setPadding(dp(12), 0, 0, 0);
        head.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(UiKit.darkButton(this, "plus", 42, false, v -> addManually()), new LinearLayout.LayoutParams(dp(42), dp(42)));
        outer.addView(head);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(4), dp(16), dp(40));
        outer.addView(body);
        sv.addView(outer);
        setContentView(sv);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        body.removeAllViews();

        LinearLayout ac = card();
        ac.setOrientation(LinearLayout.HORIZONTAL);
        ac.setGravity(Gravity.CENTER_VERTICAL);
        TextView al = text(15, Ui.TEXT, true);
        al.setText(T.t("Auto-connect"));
        TextView ah = text(12, Ui.DIM, false);
        ah.setText(T.t("Open the remote by itself when a known TV is found"));
        LinearLayout acol = new LinearLayout(this);
        acol.setOrientation(LinearLayout.VERTICAL);
        acol.addView(al);
        acol.addView(ah);
        ac.addView(acol, new LinearLayout.LayoutParams(0, -2, 1));
        ZhPopup.Toggle autoT = new ZhPopup.Toggle(this, Store.getBool("auto_connect_tv", true));
        autoT.onChange(() -> Store.putBool("auto_connect_tv", autoT.on()));
        ac.addView(autoT, new LinearLayout.LayoutParams(dp(48), dp(28)));
        body.addView(ac, gap(4));

        List<JSONObject> list = TvList.all();
        body.addView(labelRow(T.t("Your TVs") + (list.isEmpty() ? "" : "  ·  " + list.size())));
        if (list.isEmpty()) {
            LinearLayout empty = card();
            TextView e = text(14, Ui.DIM, false);
            e.setText(T.t("No TVs yet — connect to one from the home screen, or add one by its address."));
            empty.addView(e);
            body.addView(empty);
            return;
        }
        for (final JSONObject tv : list) {
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(14), dp(12), dp(14), dp(12));
            r.setBackground(Ui.focusCard(this, 16));
            r.setFocusable(true);
            r.setClickable(true);
            r.addView(avatar(tv.optString("name")), new LinearLayout.LayoutParams(dp(40), dp(40)));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(12), 0, 0, 0);
            TextView n = text(15, Ui.TEXT, true);
            n.setText(tv.optString("name", "TV"));
            TextView ip = text(12, Ui.DIM, false);
            ip.setText(tv.optString("ip") + (tv.optString("ip").equals(Store.getStr("last_tv", "")) ? "  ·  " + T.t("last used") : ""));
            col.addView(n);
            col.addView(ip);
            r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
            r.setOnClickListener(v -> details(tv));
            body.addView(r, gap(8));
        }
    }

    private TextView labelRow(String s) {
        TextView t = text(12, Ui.DIM, true);
        t.setText(s.toUpperCase(java.util.Locale.getDefault()));
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(2), dp(20), 0, dp(8));
        return t;
    }

    /** All it knows about one TV, plus whether it's online right now — checked live. */
    private void details(final JSONObject tv) {
        final String name = tv.optString("name", "TV"), ip = tv.optString("ip"), mac = tv.optString("mac", "");
        final TextView status = text(14, Ui.DIM, false);
        status.setText("⏳  " + T.t("Checking…"));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(4), dp(6), dp(4), 0);
        TextView ipL = text(13, Ui.TEXT, false);
        ipL.setText(T.t("Address") + ": " + ip);
        box.addView(ipL);
        if (!mac.isEmpty()) {
            TextView macL = text(13, Ui.TEXT, false);
            macL.setText(T.t("Hardware address") + ": " + mac);
            box.addView(macL);
        }
        box.addView(status, gap(8));
        new UiDialog(this).setTitle("📺 " + name).setView(box)
                .setPositiveButton("▶ " + T.t("Connect"), (d, w) -> connect(ip, name, mac))
                .setNeutralButton("🗑 " + T.t("Remove"), (d, w) -> ZhPopup.confirm(this, "🗑", T.t("Remove") + " " + name + "?", ip, T.t("Remove"), true, () -> {
                    TvList.remove(tv.optString("id"));
                    render();
                }))
                .setNegativeButton(T.t("Close"), null).show();
        new Thread(() -> {
            Discovery.Device d = Discovery.ping(ip, 2500);
            main.post(() -> {
                status.setText(d != null ? "🟢  " + T.t("Online now") : "⚪  " + T.t("Not found on this Wi-Fi right now"));
                if (d != null && !d.mac.isEmpty() && !d.mac.equals(mac)) TvList.upsert(name, ip, d.mac);
            });
        }).start();
    }

    private void connect(String ip, String name, String mac) {
        Store.putStr("last_tv", ip);
        if (!mac.isEmpty()) Store.putStr("last_tv_mac", mac);
        TvList.upsert(name, ip, mac);
        startActivity(new Intent(this, NativeRemoteActivity.class)
                .putExtra(NativeRemoteActivity.EXTRA_IP, ip)
                .putExtra(NativeRemoteActivity.EXTRA_NAME, name));
    }

    private void addManually() {
        final EditText e = new EditText(this);
        e.setHint("192.168.1.33");
        e.setInputType(InputType.TYPE_CLASS_PHONE);
        new UiDialog(this).setTitle(T.t("TV address")).setView(e)
                .setPositiveButton(T.t("Add"), (d, w) -> {
                    final String ip = e.getText().toString().trim().replace("http://", "").replaceAll(":.*", "");
                    if (ip.isEmpty()) return;
                    new Thread(() -> {
                        final Discovery.Device dev = Discovery.ping(ip, 2500);
                        main.post(() -> {
                            if (dev == null) {
                                UiKit.makeToast(this, T.t("No ZH Play at") + " " + ip, Toast.LENGTH_LONG).show();
                                return;
                            }
                            TvList.upsert(dev.name, dev.ip, dev.mac);
                            render();
                        });
                    }).start();
                }).setNegativeButton(T.t("Cancel"), null).show();
    }
}
