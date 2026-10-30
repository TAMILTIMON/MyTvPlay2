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
            fire();
        }
    };
    /** Browser / remote refresh the VPN line when the tunnel goes up or down. */
    static volatile Runnable onChange;

    private static void fire() {
        Runnable r = onChange;
        if (r != null) r.run();
    }

    private static synchronized Backend backend(Context c) {
        if (backend == null) backend = new GoBackend(c.getApplicationContext());
        return backend;
    }

    public static boolean on() {
        return state == Tunnel.State.UP;
    }

    // ------------------------------------------------------------------ several profiles
    /** [ {name, conf(sealed)} … ] — the old single profile is moved in once. */
    static org.json.JSONArray profiles() {
        try {
            org.json.JSONArray a = new org.json.JSONArray(Store.getStr("wg_profiles", "[]"));
            String old = Store.getStr("wg_conf", "");
            if (!old.isEmpty()) {
                a.put(new org.json.JSONObject().put("name", "Profile 1").put("conf", old));
                Store.putStr("wg_profiles", a.toString());
                Store.putStr("wg_conf", "");
            }
            return a;
        } catch (Exception e) {
            return new org.json.JSONArray();
        }
    }

    static int active() {
        int n = profiles().length();
        return n == 0 ? -1 : Math.max(0, Math.min(n - 1, Store.getInt("wg_active", 0)));
    }

    static String activeName() {
        int i = active();
        return i < 0 ? "" : profiles().optJSONObject(i).optString("name");
    }

    static boolean hasProfile() {
        return active() >= 0;
    }

    private static String profile() {
        int i = active();
        return i < 0 ? "" : SecretBox.open(profiles().optJSONObject(i).optString("conf"));
    }

    private static void saveAll(org.json.JSONArray a) {
        Store.putStr("wg_profiles", a.toString());
    }

    /** Everything except the home network (10.x, 172.16–31.x, 192.168.x, link-local, multicast) — like the WireGuard
     * app's "Exclude private IPs": with the VPN on, the phone still reaches the TV / PC at home. */
    static final String LAN_FREE = "0.0.0.0/5, 8.0.0.0/7, 11.0.0.0/8, 12.0.0.0/6, 16.0.0.0/4, 32.0.0.0/3, 64.0.0.0/3, 96.0.0.0/4, 112.0.0.0/5, 120.0.0.0/6, 124.0.0.0/7, 126.0.0.0/8, 128.0.0.0/3, 160.0.0.0/5, 168.0.0.0/8, 169.0.0.0/9, 169.128.0.0/10, 169.192.0.0/11, 169.224.0.0/12, 169.240.0.0/13, 169.248.0.0/14, 169.252.0.0/15, 169.255.0.0/16, 170.0.0.0/7, 172.0.0.0/12, 172.32.0.0/11, 172.64.0.0/10, 172.128.0.0/9, 173.0.0.0/8, 174.0.0.0/7, 176.0.0.0/4, 192.0.0.0/9, 192.128.0.0/11, 192.160.0.0/13, 192.169.0.0/16, 192.170.0.0/15, 192.172.0.0/14, 192.176.0.0/12, 192.192.0.0/10, 193.0.0.0/8, 194.0.0.0/7, 196.0.0.0/6, 200.0.0.0/5, 208.0.0.0/4, 240.0.0.0/4";

    /**
     * The profile as used: "Only ZH Play" → IncludedApplications = this app; a route for everything (0.0.0.0/0) →
     * everything except the home network, plus the profile's DNS servers (so names still resolve through the VPN).
     */
    static String withScope(String pkg, String conf, boolean onlyApp) {
        java.util.List<String> dns = new java.util.ArrayList<>();
        for (String line : conf.replace("\r", "").split("\n")) {
            String t = line.trim();
            if (t.toLowerCase(Locale.ROOT).startsWith("dns") && t.contains("=")) {
                for (String d : t.substring(t.indexOf('=') + 1).split(",")) {
                    d = d.trim();
                    if (d.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) dns.add(d + "/32");
                }
            }
        }
        StringBuilder out = new StringBuilder();
        boolean inIface = false, added = false;
        for (String line : conf.replace("\r", "").split("\n")) {
            String t = line.trim();
            String low = t.toLowerCase(Locale.ROOT);
            if (low.startsWith("includedapplications") || low.startsWith("excludedapplications")) continue;   // our choice decides
            if (low.startsWith("allowedips") && t.contains("=")) {
                java.util.List<String> parts = new java.util.ArrayList<>();
                boolean all = false;
                for (String r : t.substring(t.indexOf('=') + 1).split(",")) {
                    r = r.trim();
                    if (r.equals("0.0.0.0/0")) all = true;
                    else if (!r.isEmpty()) parts.add(r);
                }
                if (all) {
                    StringBuilder nl = new StringBuilder("AllowedIPs = ").append(LAN_FREE);
                    for (String d : dns) nl.append(", ").append(d);
                    for (String r : parts) nl.append(", ").append(r);
                    out.append(nl).append('\n');
                    continue;
                }
            }
            if (t.startsWith("[")) {
                if (inIface && onlyApp && !added) {
                    out.append("IncludedApplications = ").append(pkg).append('\n');
                    added = true;
                }
                inIface = t.equalsIgnoreCase("[Interface]");
            }
            out.append(line).append('\n');
        }
        if (inIface && onlyApp && !added) out.append("IncludedApplications = ").append(pkg).append('\n');
        return out.toString();
    }

    private static String withScope(Context c, String conf) {
        return withScope(c.getPackageName(), conf, onlyApp());
    }

    /** Default: the whole phone (like any VPN app). "Only ZH Play" is a choice. */
    static boolean onlyApp() {
        return Store.getBool("wg_only_app2", false);
    }

    static String scopeName() {
        return T.t(onlyApp() ? "only ZH Play" : "the whole phone");
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
                msg = "🔐 " + T.t("VPN on") + " · " + activeName() + " · " + server() + "\n" + T.t("Use VPN for") + ": " + scopeName();
            } catch (Throwable e) {
                state = Tunnel.State.DOWN;
                msg = T.t("VPN didn't connect") + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
            final String m = msg;
            h.post(() -> {
                UiKit.makeToast(a, m, Toast.LENGTH_LONG).show();
                fire();
            });
        }).start();
    }

    public static void disconnect(final Context c) {
        new Thread(() -> {
            try {
                state = backend(c).setState(TUNNEL, Tunnel.State.DOWN, null);
            } catch (Throwable ignored) {
                state = Tunnel.State.DOWN;
            }
            fire();
        }).start();
    }

    static String status(Context c) {
        if (!hasProfile()) return T.t("No profile yet");
        String base = activeName() + " · " + server() + " · " + scopeName();
        if (!on()) return T.t("Off") + " · " + base;
        try {
            Statistics s = backend(c).getStatistics(TUNNEL);
            return "🔐 " + T.t("On") + " · " + base + " · ↓ " + TvInfo.gb(s.totalRx()) + "  ↑ " + TvInfo.gb(s.totalTx());
        } catch (Throwable e) {
            return "🔐 " + T.t("On") + " · " + base;
        }
    }

    // ------------------------------------------------------------------ profile
    static boolean saveProfile(Activity a, String text) {
        return saveProfile(a, text, null);
    }

    static boolean saveProfile(Activity a, String text, String name) {
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
        try {
            org.json.JSONArray all = profiles();
            String n = name == null || name.trim().isEmpty() ? "Profile " + (all.length() + 1) : name.trim();
            all.put(new org.json.JSONObject().put("name", n).put("conf", SecretBox.seal(text)));
            saveAll(all);
            Store.putInt("wg_active", all.length() - 1);
        } catch (Exception e) {
            return false;
        }
        UiKit.makeToast(a, "✓ " + T.t("Profile saved") + " · " + activeName() + " · " + server(), Toast.LENGTH_SHORT).show();
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
            if (res == Activity.RESULT_OK && data != null) {
                java.util.List<Uri> uris = new java.util.ArrayList<>();
                if (data.getClipData() != null) {
                    for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
                } else if (data.getData() != null) uris.add(data.getData());
                int ok = 0;
                for (Uri u : uris) {
                    try (InputStream in = a.getContentResolver().openInputStream(u)) {
                        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
                        byte[] b = new byte[8192];
                        int n;
                        while ((n = in.read(b)) > 0 && o.size() < 64 * 1024) o.write(b, 0, n);
                        String fn = LocalShare.displayName(a, u);
                        String nm = fn == null ? null : fn.replaceAll("(?i)\\.conf$", "");
                        if (saveProfile(a, o.toString("UTF-8"), nm)) ok++;
                    } catch (Exception e) {
                        UiKit.makeToast(a, T.t("Can't read this file"), Toast.LENGTH_SHORT).show();
                    }
                }
                if (uris.size() > 1) UiKit.makeToast(a, ok + " / " + uris.size() + " " + T.t("profiles imported"), Toast.LENGTH_LONG).show();
                if (ok > 0) panel(a);
            }
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ the VPN panel
    public static void panel(final Activity a) {
        final org.json.JSONArray all = profiles();
        String[] items = {
                (on() ? "⏹  " + T.t("Disconnect") : "▶  " + T.t("Connect")),
                "🌍  " + T.t("Profile") + ": " + (hasProfile() ? activeName() + "  (" + all.length() + ")" : T.t("none")),
                "📄  " + T.t("Import profile files (.conf) — several at once"),
                "📋  " + T.t("Paste profile text"),
                "🎯  " + T.t("Use VPN for") + ": " + scopeName(),
                "🔎  " + T.t("Check my IP"),
                "✏  " + T.t("Rename / delete profiles"),
                "❓  " + T.t("How to get a free profile (Proton VPN)")};
        new UiDialog(a).setTitle("VPN — " + status(a)).setItems(items, (d, w) -> {
            switch (w) {
                case 0:
                    if (on()) {
                        disconnect(a);
                        UiKit.makeToast(a, T.t("VPN off"), Toast.LENGTH_SHORT).show();
                    } else connect(a);
                    break;
                case 1: {
                    if (all.length() == 0) {
                        UiKit.makeToast(a, T.t("Import a VPN profile first"), Toast.LENGTH_SHORT).show();
                        break;
                    }
                    String[] names = new String[all.length()];
                    for (int i = 0; i < names.length; i++) names[i] = all.optJSONObject(i).optString("name");
                    new UiDialog(a).setTitle(T.t("Profile")).setSingleChoiceItems(names, active(), (d2, k) -> {
                        Store.putInt("wg_active", k);
                        d2.dismiss();
                        if (on()) reconnect(a);
                        panel(a);
                    }).show();
                    break;
                }
                case 2: {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    a.startActivityForResult(i, REQ_FILE);
                    break;
                }
                case 3: {
                    final EditText e = new EditText(a);
                    e.setHint("[Interface]\nPrivateKey = …\nAddress = …\n\n[Peer]\nPublicKey = …\nEndpoint = …");
                    e.setMinLines(6);
                    final EditText nm = new EditText(a);
                    nm.setHint(T.t("Name (e.g. Netherlands free)"));
                    nm.setSingleLine(true);
                    android.widget.LinearLayout box = new android.widget.LinearLayout(a);
                    box.setOrientation(android.widget.LinearLayout.VERTICAL);
                    box.addView(nm);
                    box.addView(e);
                    new UiDialog(a).setTitle(T.t("Paste profile text")).setView(box)
                            .setPositiveButton(T.t("Save"), (d2, w2) -> {
                                if (saveProfile(a, e.getText().toString(), nm.getText().toString())) panel(a);
                            })
                            .setNegativeButton(T.t("Cancel"), null).show();
                    break;
                }
                case 4:
                    new UiDialog(a).setTitle(T.t("Use VPN for")).setSingleChoiceItems(
                            new String[]{T.t("the whole phone") + " — " + T.t("every app, like a normal VPN"),
                                    T.t("only ZH Play") + " — " + T.t("browser and streaming in this app")},
                            onlyApp() ? 1 : 0, (d2, k) -> {
                                Store.putBool("wg_only_app2", k == 1);
                                d2.dismiss();
                                if (on()) reconnect(a);
                                panel(a);
                            }).show();
                    break;
                case 5:
                    checkIp(a);
                    break;
                case 6:
                    manage(a);
                    break;
                default:
                    new UiDialog(a).setTitle(T.t("Free VPN profile (Proton VPN)"))
                            .setMessage(T.t("1. Make a free account at protonvpn.com (no payment).\n"
                                    + "2. Open account.protonvpn.com → Downloads → WireGuard configuration.\n"
                                    + "3. Platform: Android · choose a free server · Create → Download the .conf file.\n"
                                    + "   Repeat for other free countries — import all the files at once.\n"
                                    + "4. Here: Import profile files → pick the .conf files.\n\n"
                                    + "Any WireGuard profile works (your own server, another provider). Free 'VPN' apps without an account often sell your data — a profile from a provider you trust is safer."))
                            .setPositiveButton(T.t("Open Proton VPN"), (d2, w2) -> {
                                try {
                                    a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://account.protonvpn.com/downloads")));
                                } catch (Exception ignored) {
                                }
                            })
                            .setNegativeButton(T.t("Close"), null).show();
            }
        }).show();
    }

    private static void reconnect(final Activity a) {
        disconnect(a);
        new Handler(Looper.getMainLooper()).postDelayed(() -> connect(a), 1200);
    }

    /** What the internet sees (through the VPN when it covers this app). */
    static void checkIp(final Activity a) {
        UiKit.makeToast(a, T.t("Checking…"), Toast.LENGTH_SHORT).show();
        final Handler h = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String msg;
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL("https://ipinfo.io/json").openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                java.io.InputStream in = c.getInputStream();
                java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[4096];
                int n;
                while ((n = in.read(b)) > 0) o.write(b, 0, n);
                org.json.JSONObject j = new org.json.JSONObject(o.toString("UTF-8"));
                msg = T.t("Your IP") + ": " + j.optString("ip") + "\n" + j.optString("city") + ", " + j.optString("country")
                        + "\n" + j.optString("org") + "\n\n" + (on() ? "🔐 VPN " + T.t("On") + " · " + scopeName() : T.t("VPN off"));
            } catch (Exception e) {
                msg = T.t("Can't check right now") + ": " + e.getMessage();
            }
            final String m = msg;
            h.post(() -> new UiDialog(a).setTitle(T.t("Check my IP")).setMessage(m).setPositiveButton(T.t("OK"), null).show());
        }).start();
    }

    private static void manage(final Activity a) {
        final org.json.JSONArray all = profiles();
        if (all.length() == 0) {
            UiKit.makeToast(a, T.t("No profile yet"), Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[all.length()];
        for (int i = 0; i < names.length; i++) names[i] = all.optJSONObject(i).optString("name");
        new UiDialog(a).setTitle(T.t("Rename / delete profiles")).setItems(names, (d, k) ->
                new UiDialog(a).setTitle(names[k]).setItems(new String[]{T.t("Rename"), T.t("Delete")}, (d2, w) -> {
                    try {
                        if (w == 0) {
                            final EditText e = new EditText(a);
                            e.setText(names[k]);
                            new UiDialog(a).setTitle(T.t("Rename")).setView(e).setPositiveButton(T.t("Save"), (d3, w3) -> {
                                try {
                                    all.optJSONObject(k).put("name", e.getText().toString().trim());
                                    saveAll(all);
                                } catch (Exception ignored) {
                                }
                                manage(a);
                            }).show();
                        } else {
                            boolean wasActive = k == active();
                            if (wasActive && on()) disconnect(a);
                            org.json.JSONArray keep = new org.json.JSONArray();
                            for (int i = 0; i < all.length(); i++) if (i != k) keep.put(all.get(i));
                            saveAll(keep);
                            Store.putInt("wg_active", 0);
                            UiKit.makeToast(a, T.t("Profile removed"), Toast.LENGTH_SHORT).show();
                            manage(a);
                        }
                    } catch (Exception ignored) {
                    }
                }).show()).show();
    }
}
