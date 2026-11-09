package com.mytv.player;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ⏻ Bluetooth power-on: pair this phone with the TV as a Bluetooth keyboard / remote, then switch the TV
 * on over Bluetooth (works where Wi-Fi is off in standby, if the TV wakes for a paired keyboard).
 * Also a small backup remote over Bluetooth.
 */
@SuppressLint("MissingPermission")
public class BtRemoteActivity extends Activity {
    static final String EXTRA_POWER = "power_now";
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout body;
    private TextView status;
    private final Map<String, BluetoothDevice> found = new LinkedHashMap<>();
    private LinearLayout foundList;
    private boolean scanning;

    static void open(Context c, boolean powerNow) {
        Intent i = new Intent(c, BtRemoteActivity.class).putExtra(EXTRA_POWER, powerNow);
        if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
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
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable g = round(Ui.dark() ? 0xFF161D2E : 0xFFFFFFFF, dp(20));
        g.setStroke(dp(1), Ui.dark() ? 0x22FFFFFF : 0x14000000);
        c.setBackground(g);
        return c;
    }

    private LinearLayout.LayoutParams gap(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(top), 0, 0);
        return lp;
    }

    private TextView label(String s) {
        TextView t = text(12, Ui.DIM, true);
        t.setText(s.toUpperCase(java.util.Locale.getDefault()));
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(2), dp(18), 0, dp(6));
        return t;
    }

    private TextView btn(String s, boolean primary, View.OnClickListener l) {
        TextView b = ZhPopup.button(this, s, primary, false);
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setOnClickListener(l);
        return b;
    }

    // ================================================================== life cycle

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
        t.setText("⏻  " + T.t("Bluetooth power-on"));
        t.setPadding(dp(12), 0, 0, 0);
        head.addView(t);
        outer.addView(head);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(4), dp(16), dp(40));
        outer.addView(body);
        sv.addView(outer);
        setContentView(sv);

        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        registerReceiver(rx, f);

        render();
        if (!BtHid.supported()) return;
        if (askPermissions()) return;                   // continues in onRequestPermissionsResult
        afterPermissions();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(rx);
        } catch (Exception ignored) {
        }
        try {
            BluetoothAdapter.getDefaultAdapter().cancelDiscovery();
        } catch (Throwable ignored) {
        }
        main.postDelayed(BtHid::release, 3000);          // let a wake finish, then free the keyboard role
    }

    private void afterPermissions() {
        if (!BtHid.bluetoothOn()) {
            try {
                startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 41);
            } catch (Exception e) {
                setStatus("⚠ " + T.t("Turn Bluetooth on in Settings"), false);
            }
            return;
        }
        BtHid.ensure(this, () -> {
            render();
            if (getIntent().getBooleanExtra(EXTRA_POWER, false) && !BtHid.savedTv().isEmpty()) powerOn();
        });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 41 && BtHid.bluetoothOn()) afterPermissions();
        if (req == 42) setStatus(res > 0 ? "📢 " + T.t("Visible for 2 minutes — now add it on the TV (see the steps)") : "⚠ " + T.t("The phone wasn't made visible"), res > 0);
    }

    // ---- permissions
    private String[] needed() {
        List<String> l = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            l.add(Manifest.permission.BLUETOOTH_CONNECT);
            l.add(Manifest.permission.BLUETOOTH_SCAN);
            l.add(Manifest.permission.BLUETOOTH_ADVERTISE);
        } else if (Build.VERSION.SDK_INT >= 23) {
            l.add(Manifest.permission.ACCESS_FINE_LOCATION);     // finding devices needs it on Android 6–11
        }
        List<String> missing = new ArrayList<>();
        for (String p : l) if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        return missing.toArray(new String[0]);
    }

    private boolean askPermissions() {
        String[] m = needed();
        if (m.length == 0 || Build.VERSION.SDK_INT < 23) return false;
        requestPermissions(m, 40);
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        if (needed().length > 0) setStatus("⚠ " + T.t("Bluetooth permission is needed for this"), false);
        else afterPermissions();
    }

    // ================================================================== screen

    private void setStatus(String s, boolean ok) {
        if (status == null) return;
        status.setText(s);
        status.setTextColor(ok ? Ui.TEXT : 0xFFEF4444);
    }

    private void render() {
        body.removeAllViews();
        if (!BtHid.supported()) {
            LinearLayout c = card();
            TextView m = text(15, Ui.TEXT, false);
            m.setText(T.t("This needs Android 9 or newer and Bluetooth on this phone."));
            c.addView(m);
            body.addView(c);
            return;
        }
        String tv = BtHid.savedTv();

        // ---- status + power
        LinearLayout c = card();
        TextView who = text(16, Ui.TEXT, true);
        who.setText(tv.isEmpty() ? T.t("No TV paired yet") : "📺  " + Store.getStr(BtHid.KEY_TV_NAME, "TV"));
        c.addView(who);
        TextView addr = text(12, Ui.DIM, false);
        addr.setText(tv.isEmpty() ? T.t("Pair below — once.") : tv + (BtHid.isConnected() ? "   ·   🔗 " + T.t("connected") : ""));
        c.addView(addr);
        status = text(14, Ui.TEXT, false);
        status.setPadding(0, dp(12), 0, 0);
        status.setText(BtHid.bluetoothOn() ? "" : "⚠ " + T.t("Bluetooth is off"));
        c.addView(status);
        TextView power = btn("⏻   " + T.t("Power on the TV"), true, v -> powerOn());
        power.setTextSize(18);
        c.addView(power, new LinearLayout.LayoutParams(-1, dp(58)));
        ((LinearLayout.LayoutParams) power.getLayoutParams()).topMargin = dp(14);
        power.setEnabled(!tv.isEmpty());
        power.setAlpha(tv.isEmpty() ? 0.45f : 1f);
        TextView tip = text(12, Ui.DIM, false);
        tip.setText(T.t("Test it: switch the TV off with its own remote, wait a minute, then tap Power on. If it stays off, your TV only wakes for its own remote (see the note below)."));
        tip.setPadding(0, dp(10), 0, 0);
        c.addView(tip);
        body.addView(c, gap(4));

        // ---- backup remote over Bluetooth
        if (!tv.isEmpty()) {
            body.addView(label("🎮  " + T.t("Remote over Bluetooth")));
            LinearLayout r = card();
            row(r, new String[]{"🔉", "🔇", "🔊", "⏯"}, new Runnable[]{() -> BtHid.consumer(BtHid.VOL_DOWN), () -> BtHid.consumer(BtHid.MUTE),
                    () -> BtHid.consumer(BtHid.VOL_UP), () -> BtHid.consumer(BtHid.PLAY_PAUSE)});
            row(r, new String[]{"", "▲", ""}, new Runnable[]{null, () -> BtHid.key(BtHid.K_UP), null});
            row(r, new String[]{"◀", "OK", "▶"}, new Runnable[]{() -> BtHid.key(BtHid.K_LEFT), () -> BtHid.key(BtHid.K_ENTER), () -> BtHid.key(BtHid.K_RIGHT)});
            row(r, new String[]{"", "▼", ""}, new Runnable[]{null, () -> BtHid.key(BtHid.K_DOWN), null});
            row(r, new String[]{"↩ " + T.t("Back"), "⌂ " + T.t("Home"), "☰", "😴"}, new Runnable[]{() -> BtHid.consumer(BtHid.BACK),
                    () -> BtHid.consumer(BtHid.HOME), () -> BtHid.consumer(BtHid.MENU), () -> BtHid.system(BtHid.SYS_SLEEP)});
            body.addView(r);
        }

        // ---- pairing
        body.addView(label("🔗  " + (tv.isEmpty() ? T.t("Pair with the TV (once, TV switched on)") : T.t("Pair again / another TV"))));
        LinearLayout p = card();
        TextView w1 = text(15, Ui.TEXT, true);
        w1.setText("1.  " + T.t("From this phone"));
        p.addView(w1);
        TextView w1h = text(13, Ui.DIM, false);
        w1h.setText(T.t("Find the TV and pair. The TV may ask you to confirm."));
        p.addView(w1h);
        p.addView(btn("🔍  " + (scanning ? T.t("Searching…") : T.t("Find the TV")), true, v -> scan()), new LinearLayout.LayoutParams(-1, dp(48)));
        ((LinearLayout.LayoutParams) p.getChildAt(p.getChildCount() - 1).getLayoutParams()).topMargin = dp(10);
        foundList = new LinearLayout(this);
        foundList.setOrientation(LinearLayout.VERTICAL);
        p.addView(foundList);
        showFound();
        TextView w2 = text(15, Ui.TEXT, true);
        w2.setText("2.  " + T.t("From the TV"));
        w2.setPadding(0, dp(18), 0, 0);
        p.addView(w2);
        TextView w2h = text(13, Ui.DIM, false);
        String me = "";
        try {
            me = BluetoothAdapter.getDefaultAdapter().getName();
        } catch (Throwable ignored) {
        }
        w2h.setText(T.t("Make the phone visible, then on the TV: Settings → Remotes & accessories → Add accessory → choose") + " “" + me + "”.");
        p.addView(w2h);
        p.addView(btn("📢  " + T.t("Make this phone visible (2 min)"), false, v -> visible()), new LinearLayout.LayoutParams(-1, dp(48)));
        ((LinearLayout.LayoutParams) p.getChildAt(p.getChildCount() - 1).getLayoutParams()).topMargin = dp(10);
        if (!tv.isEmpty()) {
            TextView forget = text(14, 0xFFEF4444, true);
            forget.setText(T.t("Forget this TV"));
            forget.setPadding(0, dp(16), 0, 0);
            forget.setOnClickListener(v -> ZhPopup.confirm(this, "🗑", T.t("Forget this TV?"), Store.getStr(BtHid.KEY_TV_NAME, "TV"),
                    T.t("Forget"), true, () -> {
                        Store.putStr(BtHid.KEY_TV, "");
                        Store.putStr(BtHid.KEY_TV_NAME, "");
                        render();
                    }));
            p.addView(forget);
        }
        body.addView(p);

        // ---- honest note
        TextView note = text(12, Ui.DIM, false);
        note.setText(T.t("How it works: the phone acts as a Bluetooth keyboard paired with the TV. Many TV boxes (e.g. Mi Box S) wake when a paired keyboard reconnects. Some TVs (e.g. Xiaomi TVs with a MediaTek chip) only wake for their own remote's signal — then a small ESP32 board is needed (ask in ZH Play updates)."));
        note.setPadding(dp(2), dp(16), dp(2), 0);
        note.setLineSpacing(0, 1.15f);
        body.addView(note);
    }

    private void row(LinearLayout into, String[] labels, Runnable[] acts) {
        LinearLayout r = new LinearLayout(this);
        for (int i = 0; i < labels.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(52), 1);
            lp.setMargins(i == 0 ? 0 : dp(8), dp(4), 0, dp(4));
            if (acts[i] == null) {
                r.addView(new View(this), lp);
                continue;
            }
            final Runnable a = acts[i];
            TextView b = btn(labels[i], false, v -> {
                if (!BtHid.isConnected()) {
                    setStatus("📶 " + T.t("Connecting over Bluetooth…"), true);
                    BtHid.ensure(this, () -> BtHid.connect(BtHid.savedTv(), () -> {
                        setStatus("🔗 " + T.t("Connected"), true);
                        main.postDelayed(a, 800);
                    }));
                    return;
                }
                v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                a.run();
            });
            b.setTextSize(16);
            r.addView(b, lp);
        }
        into.addView(r);
    }

    // ================================================================== actions

    private void powerOn() {
        if (!BtHid.bluetoothOn()) {
            afterPermissions();
            return;
        }
        BtHid.powerOn(this, (s, done, ok) -> {
            setStatus(s, ok);
            if (done && ok) ZhPopup.banner(this, "✅ " + T.t("Wake sent"));
        });
    }

    private void scan() {
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a == null || !a.isEnabled()) {
            afterPermissions();
            return;
        }
        if (needed().length > 0) {
            askPermissions();
            return;
        }
        found.clear();
        for (BluetoothDevice d : a.getBondedDevices()) found.put(d.getAddress(), d);    // already paired ones first
        try {
            a.cancelDiscovery();
            scanning = a.startDiscovery();
        } catch (Throwable e) {
            scanning = false;
        }
        setStatus(scanning ? "🔍 " + T.t("Searching for the TV (keep it switched on)…") : "⚠ " + T.t("Couldn't search — check Bluetooth / location"), scanning);
        showFound();
    }

    private void showFound() {
        if (foundList == null) return;
        foundList.removeAllViews();
        for (final BluetoothDevice d : found.values()) {
            String name;
            try {
                name = d.getName();
            } catch (Throwable e) {
                name = null;
            }
            final String nm = name == null || name.isEmpty() ? d.getAddress() : name;
            boolean bonded = d.getBondState() == BluetoothDevice.BOND_BONDED;
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(12), dp(10), dp(12), dp(10));
            r.setBackground(Ui.focusCard(this, 14));
            r.setFocusable(true);
            r.setClickable(true);
            TextView n = text(15, Ui.TEXT, true);
            n.setText((bonded ? "🔗 " : "📺 ") + nm);
            r.addView(n, new LinearLayout.LayoutParams(0, -2, 1));
            TextView act = text(13, Ui.ACCENT, true);
            act.setText(bonded ? T.t("Use") : T.t("Pair"));
            r.addView(act);
            r.setOnClickListener(v -> choose(d, nm));
            foundList.addView(r, gap(8));
        }
    }

    /** Pair (if needed), then connect as the keyboard and remember the TV. */
    private void choose(final BluetoothDevice d, final String name) {
        try {
            BluetoothAdapter.getDefaultAdapter().cancelDiscovery();
        } catch (Throwable ignored) {
        }
        scanning = false;
        Store.putStr(BtHid.KEY_TV, d.getAddress());
        Store.putStr(BtHid.KEY_TV_NAME, name);
        if (d.getBondState() != BluetoothDevice.BOND_BONDED) {
            setStatus("🔗 " + T.t("Pairing… confirm on the TV if it asks"), true);
            d.createBond();                                    // continues in BOND_STATE_CHANGED
        } else {
            connectNow();
        }
    }

    private void connectNow() {
        BtHid.ensure(this, () -> BtHid.connect(BtHid.savedTv(), () -> {
            setStatus("✅ " + T.t("Paired and connected. Now test Power on (TV off first)."), true);
            render();
        }));
    }

    private void visible() {
        BtHid.ensure(this, () -> {
            try {
                startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                        .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120), 42);
            } catch (Exception e) {
                setStatus("⚠ " + e.getMessage(), false);
            }
        });
    }

    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(a)) {
                BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d != null && !found.containsKey(d.getAddress())) {
                    found.put(d.getAddress(), d);
                    showFound();
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(a)) {
                scanning = false;
                if (found.isEmpty()) setStatus("⚠ " + T.t("No TV found. Is the TV on? Try way 2 (from the TV)."), false);
                else setStatus(T.t("Pick your TV in the list"), true);
            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(a)) {
                BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                int st = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
                if (d != null && d.getAddress().equals(BtHid.savedTv())) {
                    if (st == BluetoothDevice.BOND_BONDED) {
                        setStatus("🔗 " + T.t("Paired — connecting…"), true);
                        connectNow();
                    } else if (st == BluetoothDevice.BOND_NONE) {
                        setStatus("⚠ " + T.t("Pairing was refused. Try way 2: add the phone from the TV's settings."), false);
                    }
                }
            } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(a)) {
                render();
            }
        }
    };
}
