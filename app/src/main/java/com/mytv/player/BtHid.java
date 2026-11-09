package com.mytv.player;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * The phone as a Bluetooth keyboard / remote for the TV (Android 9+, BluetoothHidDevice — classic Bluetooth).
 * Once paired, connecting to the TV can wake it from standby (a bonded keyboard reconnecting "pages" the TV,
 * the method proven on the Mi Box S), then "Wake Up" is sent. Also a backup remote when Wi-Fi is down.
 *
 * Reports: 1 = keyboard (arrows, Enter), 2 = consumer (volume, mute, play/pause, Home, Back, Menu),
 *          3 = system control (bit0 Power Down, bit1 Sleep, bit2 Wake Up).
 * "Wake Up" is used to switch on — never "Power", which toggles and would switch a running TV off.
 */
@SuppressLint("MissingPermission")
final class BtHid {
    private BtHid() {
    }

    static final String KEY_TV = "bt_tv_addr", KEY_TV_NAME = "bt_tv_name";

    // ---- HID report descriptor: keyboard + consumer control + system control
    private static final byte[] DESCRIPTOR = bytes(
            // keyboard, report 1
            0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, 0x01,
            0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
            0x95, 0x01, 0x75, 0x08, 0x81, 0x01,
            0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65, 0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00,
            0xC0,
            // consumer control, report 2 (one 16-bit usage)
            0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, 0x85, 0x02,
            0x15, 0x00, 0x26, 0xFF, 0x03, 0x19, 0x00, 0x2A, 0xFF, 0x03, 0x75, 0x10, 0x95, 0x01, 0x81, 0x00,
            0xC0,
            // system control, report 3 (power down / sleep / wake up)
            0x05, 0x01, 0x09, 0x80, 0xA1, 0x01, 0x85, 0x03,
            0x19, 0x81, 0x29, 0x83, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x03, 0x81, 0x02,
            0x95, 0x05, 0x81, 0x01,
            0xC0);

    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }

    static byte[] descriptor() {
        return DESCRIPTOR.clone();
    }

    // consumer usages
    static final int VOL_UP = 0xE9, VOL_DOWN = 0xEA, MUTE = 0xE2, PLAY_PAUSE = 0xCD, HOME = 0x223, BACK = 0x224, MENU = 0x40;
    // keyboard usages
    static final int K_RIGHT = 0x4F, K_LEFT = 0x50, K_DOWN = 0x51, K_UP = 0x52, K_ENTER = 0x28;
    // system control bits
    static final int SYS_POWER = 1, SYS_SLEEP = 2, SYS_WAKE = 4;

    interface Listener {
        void status(String text, boolean done, boolean ok);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Executor EXEC = Executors.newSingleThreadExecutor();
    static volatile BluetoothHidDevice hid;
    static volatile boolean registered;
    static volatile BluetoothDevice connected;
    private static volatile Runnable onRegistered, onConnected;
    private static volatile Listener listener;
    static volatile BluetoothDevice lastConnecting;

    static boolean supported() {
        return Build.VERSION.SDK_INT >= 28 && BluetoothAdapter.getDefaultAdapter() != null;
    }

    static boolean bluetoothOn() {
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        return a != null && a.isEnabled();
    }

    static String savedTv() {
        return Store.getStr(KEY_TV, "");
    }

    static void say(final String t, final boolean done, final boolean ok) {
        final Listener l = listener;
        if (l != null) MAIN.post(() -> l.status(t, done, ok));
    }

    // ------------------------------------------------------------------ the HID "app"

    private static final BluetoothHidDevice.Callback CALLBACK = new BluetoothHidDevice.Callback() {
        @Override
        public void onAppStatusChanged(BluetoothDevice plugged, boolean reg) {
            registered = reg;
            if (reg) {
                Runnable r = onRegistered;
                onRegistered = null;
                if (r != null) MAIN.post(r);
            }
        }

        @Override
        public void onConnectionStateChanged(BluetoothDevice d, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                connected = d;
                if (savedTv().isEmpty() || (lastConnecting == null && !d.getAddress().equals(savedTv()))) {
                    // paired from the TV's side ("Add accessory"): remember this TV
                    Store.putStr(KEY_TV, d.getAddress());
                    Store.putStr(KEY_TV_NAME, d.getName() == null ? "TV" : d.getName());
                }
                Runnable r = onConnected;
                onConnected = null;
                if (r != null) MAIN.post(r);
                say("🔗 " + T.t("Connected to") + " " + (d.getName() == null ? d.getAddress() : d.getName()), false, true);
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                if (connected != null && connected.equals(d)) connected = null;
            }
        }

        @Override
        public void onGetReport(BluetoothDevice d, byte type, byte id, int bufferSize) {
            BluetoothHidDevice h = hid;
            if (h == null) return;
            byte[] empty = id == 1 ? new byte[8] : id == 2 ? new byte[2] : new byte[1];
            h.replyReport(d, type, id, empty);
        }
    };

    /** Gets the HID profile and registers ZH Play as a keyboard / remote; runs then() when ready. */
    static void ensure(final Context c, final Runnable then) {
        if (!supported()) {
            say(T.t("Needs Android 9 or newer"), true, false);
            return;
        }
        if (hid != null && registered) {
            MAIN.post(then);
            return;
        }
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (hid == null) {
            a.getProfileProxy(c.getApplicationContext(), new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    hid = (BluetoothHidDevice) proxy;
                    register(then);
                }

                @Override
                public void onServiceDisconnected(int profile) {
                    hid = null;
                    registered = false;
                }
            }, BluetoothProfile.HID_DEVICE);
        } else {
            register(then);
        }
    }

    private static void register(Runnable then) {
        BluetoothHidDevice h = hid;
        if (h == null) return;
        onRegistered = then;
        BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                "ZH Play Remote", "ZH Play TV remote and keyboard", "ZH Play", BluetoothHidDevice.SUBCLASS1_COMBO, descriptor());
        boolean ok = h.registerApp(sdp, null, null, EXEC, CALLBACK);
        if (!ok) {
            // another app (e.g. a Bluetooth keyboard app) holds the keyboard role, or Bluetooth is busy
            onRegistered = null;
            say(T.t("Bluetooth keyboard mode is busy — close other Bluetooth keyboard apps and try again"), true, false);
        }
    }

    /** Releases the keyboard role (when the screen is left). */
    static void release() {
        BluetoothHidDevice h = hid;
        try {
            if (h != null && connected != null) h.disconnect(connected);
            if (h != null && registered) h.unregisterApp();
        } catch (Throwable ignored) {
        }
        registered = false;
        connected = null;
    }

    // ------------------------------------------------------------------ connect + wake

    /** Connects to the TV (repeats while the TV's Bluetooth wakes up). */
    static void connect(final String addr, final Runnable then) {
        final BluetoothHidDevice h = hid;
        if (h == null || addr.isEmpty()) return;
        final BluetoothDevice d = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(addr);
        if (connected != null && connected.getAddress().equals(addr)
                && h.getConnectionState(d) == BluetoothProfile.STATE_CONNECTED) {
            MAIN.post(then);
            return;
        }
        lastConnecting = d;
        onConnected = then;
        final int[] tries = {0};
        Runnable attempt = new Runnable() {
            public void run() {
                if (onConnected == null) return;                 // done
                if (tries[0]++ >= 4) {
                    onConnected = null;
                    lastConnecting = null;
                    say(T.t("The TV didn't answer over Bluetooth. Is it paired with this phone, and is its Bluetooth on in standby?"), true, false);
                    return;
                }
                say("📶 " + T.t("Calling the TV over Bluetooth…") + (tries[0] > 1 ? " (" + tries[0] + ")" : ""), false, true);
                try {
                    h.connect(d);
                } catch (Throwable ignored) {
                }
                MAIN.postDelayed(this, 6000);
            }
        };
        MAIN.post(attempt);
    }

    /** Power on: register → connect (pages the TV awake) → wait → "Wake Up" (twice, to be sure). */
    static void powerOn(final Context c, Listener l) {
        listener = l;
        final String tv = savedTv();
        if (tv.isEmpty()) {
            say(T.t("Pair this phone with the TV first"), true, false);
            return;
        }
        say("⏻ " + T.t("Waking the TV…"), false, true);
        ensure(c, () -> connect(tv, () -> {
            // a waking TV drops keys sent instantly (~1.5 s)
            MAIN.postDelayed(() -> {
                system(SYS_WAKE);
                MAIN.postDelayed(() -> {
                    system(SYS_WAKE);
                    lastConnecting = null;
                    say("✅ " + T.t("Wake sent — the TV should be turning on"), true, true);
                }, 1200);
            }, 1600);
        }));
    }

    // ------------------------------------------------------------------ keys

    private static boolean send(int id, byte[] data) {
        BluetoothHidDevice h = hid;
        BluetoothDevice d = connected;
        if (h == null || d == null) return false;
        try {
            return h.sendReport(d, id, data);
        } catch (Throwable e) {
            return false;
        }
    }

    /** System key (press + release). */
    static boolean system(int bit) {
        boolean ok = send(3, new byte[]{(byte) bit});
        MAIN.postDelayed(() -> send(3, new byte[]{0}), 90);
        return ok;
    }

    /** Media / TV key (press + release). */
    static boolean consumer(int usage) {
        boolean ok = send(2, new byte[]{(byte) (usage & 0xFF), (byte) ((usage >> 8) & 0xFF)});
        MAIN.postDelayed(() -> send(2, new byte[]{0, 0}), 70);
        return ok;
    }

    /** Keyboard key (press + release). */
    static boolean key(int usage) {
        byte[] down = new byte[8];
        down[2] = (byte) usage;
        boolean ok = send(1, down);
        MAIN.postDelayed(() -> send(1, new byte[8]), 60);
        return ok;
    }

    static boolean isConnected() {
        return connected != null;
    }
}
