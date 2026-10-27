package com.mytv.player;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.net.VpnService;
import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;
import android.widget.Toast;

import com.wireguard.android.backend.Backend;
import com.wireguard.android.backend.GoBackend;
import com.wireguard.android.backend.Statistics;
import com.wireguard.android.backend.Tunnel;
import com.wireguard.config.Config;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Locale;

/**
 * Built-in VPN (WireGuard). Uses a profile you import — e.g. a free one from Proton VPN — so no
 * unknown "free VPN" server sees your traffic. Scope: only ZH Play, or the whole phone.
 */
public final class WgVpn {
    private WgVpn() {
    }

    public static final int REQ_PREPARE = 75, REQ_FILE = 76;
    private static Backend backend;
    private static volatile Tunnel.State state = Tunnel.State.DOWN;
    private static final Tunnel TUNNEL = new Tunnel() {
        public String getName() {
            return "zhplay";
        }

        public void onStateChange(Tunnel.State s) {
            state = s;
        }
    };

    private static synchronized Backend backend(Context c) {
        if (backend == null) backend = new GoBackend(c.getApplicationContext());
        return backend;
    }

    public static boolean on() {
        return state == Tunnel.State.UP;
    }

    static boolean hasProfile() {
        return !Store.getStr("wg_conf", "").isEmpty();
    }

    private static String profile() {
        return SecretBox.open(Store.getStr("wg_conf", ""));
    }

    /** "Only ZH Play" → the profile's [Interface] gets IncludedApplications = this app. */
    private static String withScope(Context c, String conf) {
        StringBuilder out = new StringBuilder();
        boolean inIface = false, added = false;
        boolean onlyApp = Store.getBool("wg_only_app", true);
        for (String line : conf.replace("\r", "").split("\n")) {
            String t = line.trim();
            String low = t.toLowerCase(Locale.ROOT);
            if (low.startsWith("includedapplications") || low.startsWith("excludedapplications")) continue;   // our choice decides
            if (t.startsWith("[")) {
                if (inIface && onlyApp && !added) {
                    out.append("IncludedApplications = ").append(c.getPackageName()).append('\n');
                    added = true;
                }
                inIface = t.equalsIgnoreCase("[Interface]");
            }
            out.append(line).append('\n');
        }
        if (inIface && onlyApp && !added) out.append("IncludedApplications = ").append(c.getPackageName()).append('\n');
        return out.toString();
    }

    /** Server address from the profile (shown in the status line). */
    static String server() {
        for (String line : profile().split("\n")) {
            String t = line.trim();
            if (t.toLowerCase(Locale.ROOT).startsWith("endpoint")) {
                String v = t.substring(t.indexOf('=') + 1).trim();
                return v.contains(":") ? v.substring(0, v.lastIndexOf(':')) : v;
            }
        }
        return "?";
    }

    // ------------------------------------------------------------------ connect / disconnect
    public static void connect(final Activity a) {
        if (!hasProfile()) {
            UiKit.makeToast(a, T.t("Import a VPN profile first"), Toast.LENGTH_LONG).show();
            return;
        }
        Intent prep = VpnService.prepare(a);              // Android's one-time "allow VPN" prompt
        if (prep != null) {
            a.startActivityForResult(prep, REQ_PREPARE);
            return;
        }
        final Handler h = new Handler(Looper.getMainLooper());
        UiKit.makeToast(a, T.t("Connecting the VPN…"), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String msg;
            try {
                InputStream in = new ByteArrayInputStream(withScope(a, profile()).getBytes("UTF-8"));
                Config cfg = Config.parse(in);
                state = backend(a).setState(TUNNEL, Tunnel.State.UP, cfg);
                msg = "🔐 " + T.t("VPN on") + " · " + server();
            } catch (Throwable e) {
                state = Tunnel.State.DOWN;
                msg = T.t("VPN didn't connect") + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
            final String m = msg;
            h.post(() -> UiKit.makeToast(a, m, Toast.LENGTH_LONG).show());
        }).start();
    }

    public static void disconnect(final Context c) {
        new Thread(() -> {
            try {
                state = backend(c).setState(TUNNEL, Tunnel.State.DOWN, null);
            } catch (Throwable ignored) {
                state = Tunnel.State.DOWN;
            }
        }).start();
    }

    static String status(Context c) {
        if (!hasProfile()) return T.t("No profile yet");
        if (!on()) return T.t("Off") + " · " + server();
        try {
            Statistics s = backend(c).getStatistics(TUNNEL);
            return "🔐 " + T.t("On") + " · " + server() + " · ↓ " + TvInfo.gb(s.totalRx()) + "  ↑ " + TvInfo.gb(s.totalTx());
        } catch (Throwable e) {
            return "🔐 " + T.t("On") + " · " + server();
        }
    }

    // ------------------------------------------------------------------ profile
    static boolean saveProfile(Activity a, String text) {
        if (text == null || !text.contains("[Interface]") || !text.contains("[Peer]")) {
            UiKit.makeToast(a, T.t("That is not a WireGuard profile (it needs [Interface] and [Peer])"), Toast.LENGTH_LONG).show();
            return false;
        }
        try {
            Config.parse(new ByteArrayInputStream(text.getBytes("UTF-8")));     // check it before keeping it
        } catch (Throwable e) {
            UiKit.makeToast(a, T.t("The profile has an error") + ": " + e.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
        Store.putStr("wg_conf", SecretBox.seal(text));
        UiKit.makeToast(a, "✓ " + T.t("Profile saved") + " · " + server(), Toast.LENGTH_SHORT).show();
        return true;
    }

    /** onActivityResult of the browser / remote: VPN permission or a picked .conf file. */
    public static boolean onResult(Activity a, int req, int res, Intent data) {
        if (req == REQ_PREPARE) {
            if (res == Activity.RESULT_OK) connect(a);
            else UiKit.makeToast(a, T.t("The VPN needs Android's permission"), Toast.LENGTH_SHORT).show();
            return true;
        }
        if (req == REQ_FILE) {
            if (res == Activity.RESULT_OK && data != null && data.getData() != null) {
                try (InputStream in = a.getContentResolver().openInputStream(data.getData())) {
                    java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
                    byte[] b = new byte[8192];
                    int n;
                    while ((n = in.read(b)) > 0 && o.size() < 64 * 1024) o.write(b, 0, n);
                    if (saveProfile(a, o.toString("UTF-8"))) panel(a);
                } catch (Exception e) {
                    UiKit.makeToast(a, T.t("Can't read this file"), Toast.LENGTH_SHORT).show();
                }
            }
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ the VPN panel
    public static void panel(final Activity a) {
        boolean only = Store.getBool("wg_only_app", true);
        String[] items = {
                (on() ? "⏹  " + T.t("Disconnect") : "▶  " + T.t("Connect")),
                "📄  " + T.t("Import profile file (.conf)"),
                "📋  " + T.t("Paste profile text"),
                "🎯  " + T.t("Use VPN for") + ": " + T.t(only ? "only ZH Play" : "the whole phone"),
                "❓  " + T.t("How to get a free profile (Proton VPN)"),
                "🗑  " + T.t("Remove profile")};
        new UiDialog(a).setTitle("VPN — " + status(a)).setItems(items, (d, w) -> {
            switch (w) {
                case 0:
                    if (on()) {
                        disconnect(a);
                        UiKit.makeToast(a, T.t("VPN off"), Toast.LENGTH_SHORT).show();
                    } else connect(a);
                    break;
                case 1: {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                    a.startActivityForResult(i, REQ_FILE);
                    break;
                }
                case 2: {
                    final EditText e = new EditText(a);
                    e.setHint("[Interface]\nPrivateKey = …\nAddress = …\n\n[Peer]\nPublicKey = …\nEndpoint = …");
                    e.setMinLines(6);
                    new UiDialog(a).setTitle(T.t("Paste profile text")).setView(e)
                            .setPositiveButton(T.t("Save"), (d2, w2) -> {
                                if (saveProfile(a, e.getText().toString())) panel(a);
                            })
                            .setNegativeButton(T.t("Cancel"), null).show();
                    break;
                }
                case 3:
                    Store.putBool("wg_only_app", !Store.getBool("wg_only_app", true));
                    if (on()) {                     // apply at once
                        disconnect(a);
                        new Handler(Looper.getMainLooper()).postDelayed(() -> connect(a), 1200);
                    }
                    panel(a);
                    break;
                case 4:
                    new UiDialog(a).setTitle(T.t("Free VPN profile (Proton VPN)"))
                            .setMessage(T.t("1. Make a free account at protonvpn.com (no payment).\n"
                                    + "2. Open account.protonvpn.com → Downloads → WireGuard configuration.\n"
                                    + "3. Platform: Android · choose a free server · Create → Download the .conf file.\n"
                                    + "4. Here: Import profile file → pick that .conf.\n\n"
                                    + "Any WireGuard profile works (your own server, another provider). Free 'VPN' apps without an account often sell your data — a profile from a provider you trust is safer."))
                            .setPositiveButton(T.t("Open Proton VPN"), (d2, w2) -> {
                                try {
                                    a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://account.protonvpn.com/downloads")));
                                } catch (Exception ignored) {
                                }
                            })
                            .setNegativeButton(T.t("Close"), null).show();
                    break;
                default:
                    if (on()) disconnect(a);
                    Store.putStr("wg_conf", "");
                    UiKit.makeToast(a, T.t("Profile removed"), Toast.LENGTH_SHORT).show();
            }
        }).show();
    }
}
