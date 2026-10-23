package com.mytv.player;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import android.view.Display;
import android.view.WindowManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** "My TV": storage, memory, processor, screen, video support, network, speed tests, cleaning. */
public final class TvInfo {
    private TvInfo() {
    }

    // ================================================================== formatting
    public static String gb(long b) {
        if (b >= 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f GB", b / 1073741824.0);
        return String.format(Locale.US, "%.0f MB", b / 1048576.0);
    }

    public static String bar(double frac) {
        int n = (int) Math.round(Math.max(0, Math.min(1, frac)) * 20);
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 20; i++) s.append(i < n ? '█' : '░');
        return s.toString();
    }

    // ================================================================== storage
    public static final class Vol {
        public String name, path;
        public long total, free;
    }

    public static List<Vol> volumes(Context c) {
        List<Vol> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        add(out, seen, "Internal storage", Environment.getDataDirectory());
        File[] ext = c.getExternalFilesDirs(null);
        if (ext != null) {
            for (int i = 0; i < ext.length; i++) {
                if (ext[i] == null) continue;
                String p = ext[i].getAbsolutePath();
                int k = p.indexOf("/Android/");
                File root = k > 0 ? new File(p.substring(0, k)) : ext[i];
                add(out, seen, i == 0 ? "Shared storage" : "USB / SD card " + i, i == 0 ? ext[i] : root);
            }
        }
        return out;
    }

    private static void add(List<Vol> out, Set<String> seen, String name, File f) {
        try {
            StatFs s = new StatFs(f.getAbsolutePath());
            Vol v = new Vol();
            v.name = name;
            v.path = f.getAbsolutePath();
            v.total = s.getTotalBytes();
            v.free = s.getAvailableBytes();
            String key = v.total + ":" + (v.free / (64L * 1024 * 1024));
            if (v.total <= 0 || seen.contains(key)) return;   // same disk listed twice
            seen.add(key);
            out.add(v);
        } catch (Exception ignored) {
        }
    }

    // ================================================================== memory
    /** {total, available, threshold, low(0/1)} */
    public static long[] ram(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return new long[]{mi.totalMem, mi.availMem, mi.threshold, mi.lowMemory ? 1 : 0};
    }

    public static long appMemory() {
        android.os.Debug.MemoryInfo m = new android.os.Debug.MemoryInfo();
        android.os.Debug.getMemoryInfo(m);
        return m.getTotalPss() * 1024L;
    }

    // ================================================================== processor
    public static int cores() {
        return Runtime.getRuntime().availableProcessors();
    }

    private static long readLong(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            return Long.parseLong(r.readLine().trim());
        } catch (Exception e) {
            return -1;
        }
    }

    /** Current speed of each core in MHz (-1 = not reported). */
    public static long[] coreMhz() {
        int n = cores();
        long[] f = new long[n];
        for (int i = 0; i < n; i++) {
            long k = readLong("/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_cur_freq");
            f[i] = k > 0 ? k / 1000 : -1;
        }
        return f;
    }

    public static long maxMhz() {
        long best = -1;
        for (int i = 0; i < cores(); i++) {
            long k = readLong("/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq");
            if (k > best) best = k;
        }
        return best > 0 ? best / 1000 : -1;
    }

    /** Hottest sensor in °C, or NaN. */
    public static float temperature() {
        float best = Float.NaN;
        for (int i = 0; i < 20; i++) {
            long t = readLong("/sys/class/thermal/thermal_zone" + i + "/temp");
            if (t <= 0) continue;
            float c = t > 1000 ? t / 1000f : t;
            if (c < 15 || c > 120) continue;
            if (Float.isNaN(best) || c > best) best = c;
        }
        return best;
    }

    public static String chip() {
        String s = "";
        if (Build.VERSION.SDK_INT >= 31) s = (Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL).trim();
        if (s.isEmpty() || s.contains("unknown")) {
            try (BufferedReader r = new BufferedReader(new FileReader("/proc/cpuinfo"))) {
                String l;
                while ((l = r.readLine()) != null) {
                    if (l.toLowerCase(Locale.US).startsWith("hardware")) {
                        s = l.substring(l.indexOf(':') + 1).trim();
                        break;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (s.isEmpty()) s = Build.HARDWARE;
        return s;
    }

    // ================================================================== screen / video
    public static String display(Context c) {
        try {
            WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
            Display d = wm.getDefaultDisplay();
            android.util.DisplayMetrics m = new android.util.DisplayMetrics();
            d.getRealMetrics(m);
            int w = m.widthPixels, h = m.heightPixels;
            String max = "";
            if (Build.VERSION.SDK_INT >= 23) {
                int mw = 0, mh = 0;
                for (Display.Mode md : d.getSupportedModes()) {
                    if (md.getPhysicalWidth() * md.getPhysicalHeight() > mw * mh) {
                        mw = md.getPhysicalWidth();
                        mh = md.getPhysicalHeight();
                    }
                }
                Display.Mode cur = d.getMode();
                w = cur.getPhysicalWidth();
                h = cur.getPhysicalHeight();
                if (mw * mh > w * h) max = "  (panel up to " + mw + "×" + mh + ")";
            }
            return w + "×" + h + " @ " + Math.round(d.getRefreshRate()) + " Hz" + max;
        } catch (Exception e) {
            return "?";
        }
    }

    public static String hdr(Context c) {
        if (Build.VERSION.SDK_INT < 24) return "—";
        try {
            WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
            Display.HdrCapabilities hc = wm.getDefaultDisplay().getHdrCapabilities();
            if (hc == null) return "No";
            List<String> t = new ArrayList<>();
            for (int x : hc.getSupportedHdrTypes()) {
                if (x == Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION) t.add("Dolby Vision");
                else if (x == Display.HdrCapabilities.HDR_TYPE_HDR10) t.add("HDR10");
                else if (x == Display.HdrCapabilities.HDR_TYPE_HLG) t.add("HLG");
                else if (Build.VERSION.SDK_INT >= 29 && x == Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS) t.add("HDR10+");
            }
            return t.isEmpty() ? "No" : android.text.TextUtils.join(", ", t);
        } catch (Exception e) {
            return "—";
        }
    }

    /** Hardware decoders for common formats. */
    public static List<String> codecs() {
        String[][] want = {{"video/avc", "H.264"}, {"video/hevc", "HEVC / H.265"}, {"video/x-vnd.on2.vp9", "VP9"},
                {"video/av01", "AV1"}, {"video/dolby-vision", "Dolby Vision"}, {"audio/ac3", "Dolby Digital"},
                {"audio/eac3", "Dolby Digital+"}, {"audio/vnd.dts", "DTS"}};
        List<String> out = new ArrayList<>();
        try {
            MediaCodecInfo[] all = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
            for (String[] w : want) {
                String found = "✗";
                for (MediaCodecInfo ci : all) {
                    if (ci.isEncoder()) continue;
                    for (String t : ci.getSupportedTypes()) {
                        if (!t.equalsIgnoreCase(w[0])) continue;
                        String n = ci.getName().toLowerCase(Locale.US);
                        boolean hw = Build.VERSION.SDK_INT >= 29 ? ci.isHardwareAccelerated()
                                : !(n.startsWith("omx.google") || n.startsWith("c2.android"));
                        if (hw || w[0].startsWith("audio")) found = "✓";
                        else if (!found.equals("✓")) found = "software";
                    }
                }
                out.add(w[1] + ": " + found);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    // ================================================================== network
    public static String network(Context c) {
        StringBuilder s = new StringBuilder();
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            @SuppressWarnings("deprecation") NetworkInfo ni = cm.getActiveNetworkInfo();
            if (ni == null || !ni.isConnected()) return "Offline";
            @SuppressWarnings("deprecation") int type = ni.getType();
            if (type == ConnectivityManager.TYPE_ETHERNET) s.append("Ethernet (cable)");
            else if (type == ConnectivityManager.TYPE_WIFI) {
                WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                @SuppressWarnings("deprecation") WifiInfo wi = wm.getConnectionInfo();
                int rssi = wi.getRssi(), q = WifiManager.calculateSignalLevel(rssi, 5);
                String[] lv = {"very weak", "weak", "fair", "good", "excellent"};
                s.append("Wi-Fi ").append(wi.getFrequency() > 4900 ? "5 GHz" : "2.4 GHz")
                        .append(" · signal ").append(lv[Math.max(0, Math.min(4, q))]).append(" (").append(rssi).append(" dBm)")
                        .append(" · link ").append(wi.getLinkSpeed()).append(" Mbps");
            } else s.append(ni.getTypeName());
        } catch (Exception e) {
            s.append("?");
        }
        return s + " · IP " + Net.ip();
    }

    public static String uptime() {
        long m = SystemClock.elapsedRealtime() / 60000;
        return (m / 1440 > 0 ? m / 1440 + "d " : "") + (m / 60 % 24) + "h " + (m % 60) + "m";
    }

    // ================================================================== tests
    public interface Progress {
        void on(String line);
    }

    public static volatile String testLine = "";
    private static volatile boolean testing;

    public static boolean busy() {
        return testing;
    }

    private static final String DL = "https://speed.cloudflare.com/__down?bytes=";
    private static final String UL = "https://speed.cloudflare.com/__up";

    /** Ping, download and upload. Returns a result line. */
    public static String speedTest(Progress p) {
        if (testing) return "A test is already running";
        testing = true;
        try {
            // ping
            long best = Long.MAX_VALUE;
            for (int i = 0; i < 5; i++) {
                long t0 = System.nanoTime();
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(DL + "0").openConnection();
                    c.setConnectTimeout(4000);
                    c.setReadTimeout(4000);
                    c.getResponseCode();
                    c.disconnect();
                    best = Math.min(best, (System.nanoTime() - t0) / 1000000);
                } catch (Exception ignored) {
                }
            }
            if (best == Long.MAX_VALUE) return status(p, "❌ No internet (speed test server not reachable)");
            final long ping = best;
            status(p, "Ping " + ping + " ms · testing download…");
            // download: 4 connections, 10 s
            final AtomicLong bytes = new AtomicLong();
            final long end = System.currentTimeMillis() + 10000;
            List<Thread> th = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                Thread t = new Thread(() -> {
                    while (System.currentTimeMillis() < end) {
                        try {
                            HttpURLConnection c = (HttpURLConnection) new URL(DL + 50000000).openConnection();
                            c.setConnectTimeout(5000);
                            c.setReadTimeout(5000);
                            try (InputStream in = c.getInputStream()) {
                                byte[] b = new byte[65536];
                                int n;
                                while (System.currentTimeMillis() < end && (n = in.read(b)) > 0) bytes.addAndGet(n);
                            }
                            c.disconnect();
                        } catch (Exception e) {
                            return;
                        }
                    }
                });
                t.start();
                th.add(t);
            }
            long t0 = System.currentTimeMillis();
            while (System.currentTimeMillis() < end) {
                sleep(500);
                double s = (System.currentTimeMillis() - t0) / 1000.0;
                status(p, String.format(Locale.US, "Ping %d ms · download %.1f Mbps…", ping, bytes.get() * 8 / s / 1e6));
            }
            for (Thread t : th) join(t);
            final double down = bytes.get() * 8 / ((System.currentTimeMillis() - t0) / 1000.0) / 1e6;
            // upload: 2 connections, 8 s
            final AtomicLong up = new AtomicLong();
            final long end2 = System.currentTimeMillis() + 8000;
            final byte[] chunk = new byte[1024 * 1024];
            new Random().nextBytes(chunk);
            th.clear();
            for (int i = 0; i < 2; i++) {
                Thread t = new Thread(() -> {
                    while (System.currentTimeMillis() < end2) {
                        try {
                            HttpURLConnection c = (HttpURLConnection) new URL(UL).openConnection();
                            c.setDoOutput(true);
                            c.setRequestMethod("POST");
                            c.setFixedLengthStreamingMode(chunk.length * 8);
                            c.setConnectTimeout(5000);
                            c.setReadTimeout(8000);
                            try (OutputStream o = c.getOutputStream()) {
                                for (int k = 0; k < 8 && System.currentTimeMillis() < end2; k++) {
                                    o.write(chunk);
                                    up.addAndGet(chunk.length);
                                }
                            }
                            c.getResponseCode();
                            c.disconnect();
                        } catch (Exception e) {
                            return;
                        }
                    }
                });
                t.start();
                th.add(t);
            }
            long t1 = System.currentTimeMillis();
            while (System.currentTimeMillis() < end2) {
                sleep(500);
                double s = (System.currentTimeMillis() - t1) / 1000.0;
                status(p, String.format(Locale.US, "Ping %d ms · ↓ %.1f Mbps · upload %.1f Mbps…", ping, down, up.get() * 8 / s / 1e6));
            }
            for (Thread t : th) join(t);
            double upl = up.get() * 8 / ((System.currentTimeMillis() - t1) / 1000.0) / 1e6;
            String verdict = down >= 25 ? "great for 4K" : down >= 10 ? "good for 1080p" : down >= 5 ? "OK for 720p" : "slow — use 480p";
            String r = String.format(Locale.US, "🌐 Ping %d ms · ↓ %.1f Mbps · ↑ %.1f Mbps  (%s)", ping, down, upl, verdict);
            Store.putStr("t_speed", r);
            return status(p, r);
        } finally {
            testing = false;
        }
    }

    /** Write + read speed of the TV's storage. */
    public static String diskTest(Context c, Progress p) {
        if (testing) return "A test is already running";
        testing = true;
        File f = new File(c.getCacheDir(), "speedtest.bin");
        try {
            byte[] b = new byte[1024 * 1024];
            new Random().nextBytes(b);
            int mb = 64;
            status(p, "Writing " + mb + " MB…");
            long t0 = System.nanoTime();
            try (FileOutputStream o = new FileOutputStream(f)) {
                for (int i = 0; i < mb; i++) o.write(b);
                o.getFD().sync();
            }
            double w = mb / ((System.nanoTime() - t0) / 1e9);
            status(p, String.format(Locale.US, "Write %.0f MB/s · reading…", w));
            long t1 = System.nanoTime();
            try (FileInputStream in = new FileInputStream(f)) {
                while (in.read(b) > 0) {
                    // just read
                }
            }
            double rd = mb / ((System.nanoTime() - t1) / 1e9);
            String r = String.format(Locale.US, "💾 Storage: write %.0f MB/s · read %.0f MB/s  (%s)", w, rd,
                    w >= 60 ? "fast" : w >= 20 ? "normal" : "slow — torrents may stutter");
            Store.putStr("t_disk", r);
            return status(p, r);
        } catch (Exception e) {
            return status(p, "Storage test failed: " + e.getMessage());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            testing = false;
        }
    }

    /** CPU (single + all cores) and memory copy speed. */
    public static String benchmark(Progress p) {
        if (testing) return "A test is already running";
        testing = true;
        try {
            status(p, "Testing processor…");
            final long dur = 1500;
            long single = work(dur);
            final int n = cores();
            final long[] res = new long[n];
            List<Thread> th = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final int k = i;
                Thread t = new Thread(() -> res[k] = work(dur));
                t.start();
                th.add(t);
            }
            for (Thread t : th) join(t);
            long multi = 0;
            for (long r : res) multi += r;
            status(p, "Testing memory speed…");
            int size = 16 * 1024 * 1024;
            byte[] a = new byte[size], b = new byte[size];
            long t0 = System.nanoTime();
            int reps = 20;
            for (int i = 0; i < reps; i++) System.arraycopy(i % 2 == 0 ? a : b, 0, i % 2 == 0 ? b : a, 0, size);
            double mbps = (double) size * reps * 2 / 1048576 / ((System.nanoTime() - t0) / 1e9);
            // scores: 1000 ≈ a typical 2020 quad-core TV
            long s1 = single / 2000, sm = multi / 2000;
            String r = String.format(Locale.US, "⚙ CPU score %d (1 core) · %d (%d cores) · 🧠 RAM speed %.0f MB/s  (%s)",
                    s1, sm, n, mbps, sm >= 4000 ? "fast" : sm >= 1500 ? "normal" : "basic");
            Store.putStr("t_bench", r);
            return status(p, r);
        } catch (Throwable e) {
            return status(p, "Test failed: " + e.getMessage());
        } finally {
            testing = false;
        }
    }

    /** Counts prime checks for `ms` milliseconds. */
    private static long work(long ms) {
        long end = System.currentTimeMillis() + ms, count = 0, x = 3;
        while (System.currentTimeMillis() < end) {
            for (int k = 0; k < 2000; k++) {
                boolean prime = true;
                for (long d = 3; d * d <= x; d += 2) {
                    if (x % d == 0) {
                        prime = false;
                        break;
                    }
                }
                if (prime) count++;
                x += 2;
                if (x > 200000) x = 3;
            }
        }
        return count;
    }

    private static String status(Progress p, String s) {
        testLine = s;
        if (p != null) p.on(s);
        return s;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private static void join(Thread t) {
        try {
            t.join(15000);
        } catch (InterruptedException ignored) {
        }
    }

    // ================================================================== cleaning
    /** Closes background apps. With ADB it also stops them fully and trims caches. Returns a message. */
    public static String clean(Context c) {
        long before = ram(c)[1];
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        int n = 0;
        for (ApplicationInfo ai : c.getPackageManager().getInstalledApplications(0)) {
            if (ai.packageName.equals(c.getPackageName())) continue;
            try {
                am.killBackgroundProcesses(ai.packageName);
                n++;
            } catch (Exception ignored) {
            }
        }
        String extra = "";
        if (AdbClient.enabled()) {
            try {
                AdbClient.shell(c, "am kill-all");
                AdbClient.shell(c, "pm trim-caches 999G");
                extra = " + caches cleared";
            } catch (Exception ignored) {
            }
        }
        sleep(1500);
        long after = ram(c)[1];
        long freed = Math.max(0, after - before);
        return "🧹 Freed " + gb(freed) + " of memory" + extra + " · now free: " + gb(after);
    }

    public static final class Proc {
        public String name;
        public long rss;
    }

    /** Running apps with memory use (needs the ADB full remote). */
    public static List<Proc> running(Context c) throws Exception {
        String out = AdbClient.shell(c, "ps -A -o RSS,NAME");
        List<Proc> l = new ArrayList<>();
        for (String line : out.split("\n")) {
            String[] p = line.trim().split("\\s+");
            if (p.length < 2) continue;
            long kb;
            try {
                kb = Long.parseLong(p[0]);
            } catch (Exception e) {
                continue;
            }
            String name = p[p.length - 1];
            if (!name.contains(".") || name.startsWith("/") || name.startsWith("android.")
                    || name.equals("com.android.systemui") || name.startsWith("com.android.phone")) continue;
            Proc pr = new Proc();
            pr.name = name;
            pr.rss = kb * 1024;
            l.add(pr);
        }
        Collections.sort(l, new Comparator<Proc>() {
            public int compare(Proc a, Proc b) {
                return Long.compare(b.rss, a.rss);
            }
        });
        return l.size() > 25 ? l.subList(0, 25) : l;
    }

    public static String forceStop(Context c, String process) {
        String pkg = process.split(":")[0];
        if (pkg.equals(c.getPackageName())) return "That's TamilCastPlay itself";
        try {
            AdbClient.shell(c, "am force-stop " + pkg);
            return "⏹ Stopped " + AppsHub.label(c, pkg);
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    // ================================================================== text summary (phone / browser)
    public static String report(Context c) {
        StringBuilder s = new StringBuilder();
        s.append("📺 ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" · Android ").append(Build.VERSION.RELEASE).append(" · on for ").append(uptime()).append('\n');
        s.append("🖥 ").append(display(c)).append(" · HDR: ").append(hdr(c)).append('\n');
        s.append("🌐 ").append(network(c)).append("\n\n");
        for (Vol v : volumes(c)) {
            long used = v.total - v.free;
            s.append("💾 ").append(v.name).append(": ").append(gb(used)).append(" used / ").append(gb(v.total))
                    .append(" · ").append(gb(v.free)).append(" free\n   ").append(bar((double) used / v.total)).append('\n');
        }
        s.append("   TamilCastPlay torrents: ").append(gb(TorrentEngine.cacheBytes(c))).append("\n\n");
        long[] r = ram(c);
        s.append("🧠 RAM: ").append(gb(r[0] - r[1])).append(" used / ").append(gb(r[0])).append(" · ")
                .append(gb(r[1])).append(" free").append(r[3] == 1 ? "  ⚠ LOW" : "").append("\n   ")
                .append(bar((double) (r[0] - r[1]) / r[0])).append('\n');
        s.append("   TamilCastPlay uses ").append(gb(appMemory())).append("\n\n");
        s.append("⚙ ").append(chip()).append(" · ").append(cores()).append(" cores · ").append(Build.SUPPORTED_ABIS[0]);
        long mx = maxMhz();
        if (mx > 0) s.append(" · up to ").append(mx).append(" MHz");
        s.append('\n');
        long[] f = coreMhz();
        StringBuilder cs = new StringBuilder();
        for (long x : f) if (x > 0) cs.append(x).append(' ');
        if (cs.length() > 0) s.append("   now: ").append(cs.toString().trim()).append(" MHz\n");
        float t = temperature();
        if (!Float.isNaN(t)) s.append("🌡 ").append(Math.round(t)).append(" °C").append(t > 80 ? "  ⚠ hot" : "").append('\n');
        s.append("\n🎞 ");
        for (String cd : codecs()) s.append(cd).append(" · ");
        s.append("\n\n");
        for (String k : new String[]{"t_speed", "t_disk", "t_bench"}) {
            String v = Store.getStr(k, "");
            if (!v.isEmpty()) s.append(v).append('\n');
        }
        return s.toString().trim();
    }

    public static JSONObject json(Context c) {
        JSONObject o = new JSONObject();
        try {
            o.put("report", report(c));
            o.put("testing", testing);
            o.put("line", testLine);
            o.put("adb", AdbClient.enabled());
        } catch (Exception ignored) {
        }
        return o;
    }
}
