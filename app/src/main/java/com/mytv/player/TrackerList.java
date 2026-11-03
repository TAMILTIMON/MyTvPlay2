package com.mytv.player;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Auto-updating torrent tracker list for ZH Play.
 *
 * Sources (fetched in parallel, merged, deduplicated):
 *   1. ngosang/trackerslist  — best_ip trackers, updated daily by the community
 *   2. XIU2/TrackersListCollection — another well-maintained list
 *
 * After fetching, each tracker is probed with a 1.5 s UDP/TCP ping so only reachable
 * trackers are used. The ranked list is cached in SharedPreferences and refreshed every
 * 24 hours in the background — no user action needed.
 *
 * If the network is offline the last good list is used; if there is no cached list yet
 * the hardcoded fallback list is used so torrents always have trackers.
 */
public final class TrackerList {
    private TrackerList() {
    }

    private static final String KEY_TRACKERS = "tl_trackers";    // cached |\n-joined list
    private static final String KEY_FETCHED   = "tl_fetched_ms"; // last successful fetch
    private static final long   REFRESH_MS    = 24 * 3600 * 1000L;
    private static final int    MAX_TRACKERS  = 50;              // keep the best N
    private static final int    PROBE_MS      = 1500;
    private static volatile boolean fetching  = false;

    /** The two sources that publish fresh tracker lists. */
    private static final String[] SOURCES = {
        "https://raw.githubusercontent.com/ngosang/trackerslist/master/trackers_best_ip.txt",
        "https://raw.githubusercontent.com/ngosang/trackerslist/master/trackers_best.txt",
        "https://raw.githubusercontent.com/XIU2/TrackersListCollection/master/best.txt"
    };

    /** Hardcoded fallback — used only when there is no cached list and the network is offline. */
    private static final String[] FALLBACK = {
        "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://open.tracker.cl:1337/announce",
        "udp://tracker.qu.ax:6969/announce",
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://tracker.openbittorrent.com:6969/announce",
        "udp://explodie.org:6969/announce",
        "udp://tracker.empire-js.us:1337/announce",
        "udp://movies.zsw.ca:6969/announce",
        "udp://tracker.birkenwald.de:6969/announce",
        "https://tracker.gbitt.info:443/announce",
        "https://tracker.tamersunion.org:443/announce",
        "https://opentracker.i2p.rocks:443/announce",
        "udp://tracker.dler.org:6969/announce",
        "udp://bt.ktrackers.com:6666/announce",
        "udp://tracker.theoks.net:6969/announce",
        "udp://tracker.corpscorp.online:80/announce",
        "udp://tracker.nyaa.vc:6969/announce",
        "udp://retracker01-msk-virt.corbina.net:80/announce"
    };

    // ------------------------------------------------------------------ public API

    /**
     * Returns the current best tracker list. Triggers a background refresh if the list
     * is stale (>24 h) or missing. Never blocks — returns cached / fallback immediately.
     */
    public static List<String> get(Context c) {
        maybeRefresh(c);
        String cached = Store.getStr(KEY_TRACKERS, "");
        if (!cached.isEmpty()) {
            List<String> list = new ArrayList<>();
            for (String t : cached.split("\n")) {
                String tr = t.trim();
                if (!tr.isEmpty()) list.add(tr);
            }
            if (!list.isEmpty()) return list;
        }
        // no cached list yet — return fallback while background fetch runs
        List<String> fb = new ArrayList<>();
        Collections.addAll(fb, FALLBACK);
        return fb;
    }

    /**
     * Boosts a magnet URI by appending all current best trackers (no duplicates).
     * Call this before handing a magnet to the player.
     */
    public static String boostMagnet(Context c, String magnet) {
        if (magnet == null || !magnet.startsWith("magnet:")) return magnet;
        List<String> trackers = get(c);
        StringBuilder sb = new StringBuilder(magnet);
        for (String tr : trackers) {
            String enc = android.net.Uri.encode(tr);
            if (!magnet.contains(enc) && !magnet.contains(tr)) {
                sb.append("&tr=").append(enc);
            }
        }
        return sb.toString();
    }

    /** Call at app start (e.g. in App.onCreate) to warm up the tracker list early. */
    public static void warmUp(Context c) {
        maybeRefresh(c);
    }

    // ------------------------------------------------------------------ refresh logic

    private static void maybeRefresh(Context c) {
        if (fetching) return;
        long last = Store.getLong(KEY_FETCHED, 0);
        boolean stale = System.currentTimeMillis() - last > REFRESH_MS;
        boolean empty = Store.getStr(KEY_TRACKERS, "").trim().isEmpty();
        if (!stale && !empty) return;
        fetching = true;
        new Thread(() -> {
            try {
                doRefresh(c);
            } finally {
                fetching = false;
            }
        }, "tracker-refresh").start();
    }

    private static void doRefresh(Context c) {
        // 1. Fetch all sources in parallel
        ExecutorService pool = Executors.newFixedThreadPool(SOURCES.length);
        List<Future<List<String>>> futures = new ArrayList<>();
        for (String src : SOURCES) {
            futures.add(pool.submit(() -> fetchSource(src)));
        }
        List<String> raw = new ArrayList<>();
        for (Future<List<String>> f : futures) {
            try {
                List<String> r = f.get(10, TimeUnit.SECONDS);
                if (r != null) raw.addAll(r);
            } catch (Exception ignored) {
            }
        }
        pool.shutdownNow();

        // deduplicate
        List<String> unique = new ArrayList<>();
        for (String t : raw) {
            if (!unique.contains(t)) unique.add(t);
        }

        if (unique.isEmpty()) return;   // network offline — keep the old list

        // 2. Probe reachability in parallel (1.5 s timeout per tracker)
        ExecutorService probePool = Executors.newFixedThreadPool(Math.min(20, unique.size()));
        List<Future<long[]>> probes = new ArrayList<>();
        for (String tr : unique) {
            probes.add(probePool.submit(() -> new long[]{probe(tr), 0}));
        }
        List<long[]> scored = new ArrayList<>();
        for (int i = 0; i < unique.size(); i++) {
            try {
                long ms = probes.get(i).get(PROBE_MS + 500L, TimeUnit.MILLISECONDS)[0];
                scored.add(new long[]{ms, i});
            } catch (Exception e) {
                scored.add(new long[]{Long.MAX_VALUE, i});  // unreachable
            }
        }
        probePool.shutdownNow();

        // 3. Sort by latency — fastest first
        scored.sort((a2, b2) -> Long.compare(a2[0], b2[0]));

        // 4. Keep the best MAX_TRACKERS reachable ones
        List<String> best = new ArrayList<>();
        for (long[] s : scored) {
            if (s[0] == Long.MAX_VALUE) break;   // unreachable — stop here (sorted, rest are all unreachable)
            best.add(unique.get((int) s[1]));
            if (best.size() >= MAX_TRACKERS) break;
        }
        // if every tracker timed out (captive portal, airplane mode) keep the cached list
        if (best.isEmpty()) return;

        // 5. Save
        StringBuilder sb = new StringBuilder();
        for (String t : best) sb.append(t).append("\n");
        Store.putStr(KEY_TRACKERS, sb.toString().trim());
        Store.putLong(KEY_FETCHED, System.currentTimeMillis());

        android.util.Log.d("TrackerList", "Updated: " + best.size() + " trackers, fastest=" + best.get(0));
    }

    // ------------------------------------------------------------------ fetching

    private static List<String> fetchSource(String url) {
        List<String> out = new ArrayList<>();
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", "ZHPlay/1.0");
            if (c.getResponseCode() != 200) return out;
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if ((line.startsWith("udp://") || line.startsWith("http://") || line.startsWith("https://"))
                        && line.contains("/announce")) {
                    out.add(line);
                }
            }
            r.close();
        } catch (Exception ignored) {
        }
        return out;
    }

    // ------------------------------------------------------------------ probing

    /**
     * Returns the RTT in ms, or Long.MAX_VALUE if unreachable.
     * UDP trackers: connect datagram to the announce port (connection-less but kernel checks routing).
     * HTTP/S trackers: TCP connect to port 80/443.
     */
    private static long probe(String tracker) {
        try {
            java.net.URI uri = new java.net.URI(tracker);
            String host = uri.getHost();
            int port = uri.getPort();
            if (host == null || host.isEmpty()) return Long.MAX_VALUE;
            if (port <= 0) port = tracker.startsWith("https") ? 443 : 80;

            // resolve DNS first (shared cost)
            long t0 = System.currentTimeMillis();
            InetAddress addr;
            try {
                addr = InetAddress.getByName(host);
            } catch (Exception e) {
                return Long.MAX_VALUE;   // DNS failure
            }

            if (tracker.startsWith("udp://")) {
                // UDP: send a minimal connection request (8 bytes: protocol_id + action + transaction_id)
                DatagramSocket sock = new DatagramSocket();
                sock.setSoTimeout(PROBE_MS);
                sock.connect(addr, port);
                byte[] req = {0, 0, 4, 23, 39, 16, 0, 0,  // connection_id (fixed magic)
                              0, 0, 0, 0,                   // action = connect
                              0, 0, 0, 1};                  // transaction_id
                sock.send(new DatagramPacket(req, req.length));
                DatagramPacket resp = new DatagramPacket(new byte[16], 16);
                sock.receive(resp);   // throws SocketTimeoutException if no reply
                sock.close();
                return System.currentTimeMillis() - t0;
            } else {
                // HTTP/HTTPS: TCP connect only (don't send an HTTP request)
                java.net.Socket sock = new java.net.Socket();
                sock.connect(new java.net.InetSocketAddress(addr, port), PROBE_MS);
                sock.close();
                return System.currentTimeMillis() - t0;
            }
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }

}
