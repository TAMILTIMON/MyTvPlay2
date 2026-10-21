package com.mytv.player;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Finds other TamilCastPlay devices on the Wi-Fi (network service + quick IP scan). */
public final class Discovery {
    public static final String TYPE = "_mytvplayer._tcp.";

    public static final class Device {
        public final String name, ip;
        public final boolean tv;

        Device(String name, String ip, boolean tv) {
            this.name = name;
            this.ip = ip;
            this.tv = tv;
        }
    }

    public interface Listener {
        void onFound(Device d);

        void onDone();
    }

    private Discovery() {
    }

    public static String deviceName(Context c) {
        String n = Store.getStr("devname", "");
        if (!n.isEmpty()) return n;
        try {
            String s = Settings.Global.getString(c.getContentResolver(), "device_name");
            if (s != null && !s.isEmpty()) return s;
        } catch (Exception ignored) {
        }
        return Build.MODEL;
    }

    private static NsdManager.RegistrationListener reg;

    public static void register(Context c) {
        try {
            NsdManager nsd = (NsdManager) c.getSystemService(Context.NSD_SERVICE);
            if (nsd == null) return;
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName(deviceName(c));
            info.setServiceType(TYPE);
            info.setPort(RemoteService.PORT);
            reg = new NsdManager.RegistrationListener() {
                public void onRegistrationFailed(NsdServiceInfo s, int e) { }
                public void onUnregistrationFailed(NsdServiceInfo s, int e) { }
                public void onServiceRegistered(NsdServiceInfo s) { }
                public void onServiceUnregistered(NsdServiceInfo s) { }
            };
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, reg);
        } catch (Throwable ignored) {
        }
    }

    public static void unregister(Context c) {
        try {
            NsdManager nsd = (NsdManager) c.getSystemService(Context.NSD_SERVICE);
            if (nsd != null && reg != null) nsd.unregisterService(reg);
        } catch (Throwable ignored) {
        }
        reg = null;
    }

    /** Asks one address whether it runs TamilCastPlay. */
    public static Device ping(String ip, int timeout) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://" + ip + ":" + RemoteService.PORT + "/ping").openConnection();
            c.setConnectTimeout(timeout);
            c.setReadTimeout(timeout + 500);
            if (c.getResponseCode() != 200) return null;
            byte[] b = new byte[2048];
            int n;
            StringBuilder sb = new StringBuilder();
            try (InputStream in = c.getInputStream()) {
                while ((n = in.read(b)) > 0 && sb.length() < 4096) sb.append(new String(b, 0, n, "UTF-8"));
            }
            JSONObject o = new JSONObject(sb.toString());
            if (!"mytvplayer".equals(o.optString("app"))) return null;
            return new Device(o.optString("name", ip), ip, o.optBoolean("tv", true));
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Scans the local /24 network. Calls the listener from background threads. */
    public static void scan(final Listener l) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String me = Net.ip();
                int dot = me.lastIndexOf('.');
                if (dot < 0) {
                    l.onDone();
                    return;
                }
                final String prefix = me.substring(0, dot + 1);
                ExecutorService ex = Executors.newFixedThreadPool(48);
                for (int i = 1; i < 255; i++) {
                    final String ip = prefix + i;
                    if (ip.equals(me)) continue;
                    ex.execute(new Runnable() {
                        @Override
                        public void run() {
                            Device d = ping(ip, 700);
                            if (d != null) l.onFound(d);
                        }
                    });
                }
                ex.shutdown();
                try {
                    ex.awaitTermination(15, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
                l.onDone();
            }
        }).start();
    }

    /** Network-service discovery (fast when the router allows it). Returns a stopper. */
    public static Runnable discover(Context ctx, final Listener l) {
        final NsdManager nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
        if (nsd == null) return new Runnable() { public void run() { } };
        final String me = Net.ip();
        final NsdManager.DiscoveryListener dl = new NsdManager.DiscoveryListener() {
            public void onStartDiscoveryFailed(String t, int e) { }
            public void onStopDiscoveryFailed(String t, int e) { }
            public void onDiscoveryStarted(String t) { }
            public void onDiscoveryStopped(String t) { }
            public void onServiceLost(NsdServiceInfo s) { }

            public void onServiceFound(NsdServiceInfo s) {
                try {
                    nsd.resolveService(s, new NsdManager.ResolveListener() {
                        public void onResolveFailed(NsdServiceInfo s2, int e) { }

                        public void onServiceResolved(NsdServiceInfo s2) {
                            if (s2.getHost() == null) return;
                            String ip = s2.getHost().getHostAddress();
                            if (ip == null || ip.equals(me) || ip.contains(":")) return;
                            l.onFound(new Device(s2.getServiceName(), ip, true));
                        }
                    });
                } catch (Throwable ignored) {
                }
            }
        };
        try {
            nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, dl);
        } catch (Throwable ignored) {
        }
        return new Runnable() {
            public void run() {
                try {
                    nsd.stopServiceDiscovery(dl);
                } catch (Throwable ignored) {
                }
            }
        };
    }

    /** Blocking scan used by the web page's "switch TV" list. */
    public static List<Device> scanBlocking(long maxMs) {
        final Map<String, Device> found = new LinkedHashMap<>();
        final Object lock = new Object();
        final boolean[] done = {false};
        scan(new Listener() {
            public void onFound(Device d) {
                synchronized (lock) {
                    found.put(d.ip, d);
                }
            }

            public void onDone() {
                synchronized (lock) {
                    done[0] = true;
                    lock.notifyAll();
                }
            }
        });
        long end = System.currentTimeMillis() + maxMs;
        synchronized (lock) {
            while (!done[0] && System.currentTimeMillis() < end) {
                try {
                    lock.wait(Math.max(1, end - System.currentTimeMillis()));
                } catch (InterruptedException e) {
                    break;
                }
            }
            return new ArrayList<>(found.values());
        }
    }
}
