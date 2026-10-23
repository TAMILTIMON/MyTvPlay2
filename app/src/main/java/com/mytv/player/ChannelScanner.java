package com.mytv.player;

import android.content.Context;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Checks every channel of a playlist in the background and finds the ones that no longer play. */
public final class ChannelScanner {
    private ChannelScanner() {
    }

    public static volatile boolean running;
    public static volatile String playlist = "";
    public static final AtomicInteger done = new AtomicInteger(), dead = new AtomicInteger();
    public static volatile int total;
    private static final Set<String> deadUrls = Collections.synchronizedSet(new HashSet<String>());

    public static String status() {
        if (running) return "🩺 " + playlist + ": " + done.get() + " / " + total + " checked · " + dead.get() + " not working";
        if (total > 0) return "🩺 " + playlist + ": done · " + dead.get() + " of " + total + " not working";
        return "";
    }

    public static synchronized String start(final Context ctx, final String name) {
        if (running) return "A check is already running: " + status();
        final List<Store.Item> items = Store.items(name);
        if (items.isEmpty()) return "Playlist is empty";
        running = true;
        playlist = name;
        total = items.size();
        done.set(0);
        dead.set(0);
        deadUrls.clear();
        new Thread(() -> {
            ExecutorService ex = Executors.newFixedThreadPool(24);
            for (final Store.Item it : items) {
                ex.execute(() -> {
                    if (!alive(it.u)) {
                        dead.incrementAndGet();
                        deadUrls.add(it.u);
                    }
                    done.incrementAndGet();
                });
            }
            ex.shutdown();
            try {
                ex.awaitTermination(3, TimeUnit.HOURS);
            } catch (InterruptedException ignored) {
            }
            running = false;
            Remote.MAIN.post(() -> android.widget.Toast.makeText(ctx, status() + " — open the playlist to remove them",
                    android.widget.Toast.LENGTH_LONG).show());
        }).start();
        return "Checking " + total + " channels in the background…";
    }

    /** Removes the channels found not working. Returns how many. */
    public static int removeDead(String name) {
        if (!name.equals(playlist) || deadUrls.isEmpty()) return 0;
        List<Store.Item> items = Store.items(name);
        Set<Integer> idx = new HashSet<>();
        for (int i = 0; i < items.size(); i++) if (deadUrls.contains(items.get(i).u)) idx.add(i);
        int n = Store.delMany(name, idx);
        deadUrls.clear();
        total = 0;
        return n;
    }

    public static boolean hasResult(String name) {
        return !running && name.equals(playlist) && !deadUrls.isEmpty();
    }

    static boolean alive(String u) {
        if (!u.startsWith("http")) return true;   // rtsp / udp / magnet: can't check cheaply
        String url = u.split("\\|")[0];
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(6000);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", Net.UA);
                c.setRequestProperty("Range", "bytes=0-2047");
                int code = c.getResponseCode();
                if (code < 400) {
                    byte[] b = new byte[512];
                    c.getInputStream().read(b);
                    return true;
                }
                if (code == 403 || code == 404 || code == 410) return false;
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.disconnect();
            }
        }
        return false;
    }

    public static List<String> deadList() {
        return new ArrayList<>(deadUrls);
    }
}
