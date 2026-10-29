package com.mytv.player;

import android.content.Context;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.net.URLDecoder;
import java.util.Collections;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class Net {
    public static final String UA = "Mozilla/5.0 (Linux; Android 11; ZHPlay) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36";

    private Net() {
    }

    /**
     * This phone's address as the TV / PC sees it. Before, the first private address of ANY connection was used —
     * with mobile data or the VPN on that could be a 10.x address the TV / PC can't reach (PDF slides, phone videos
     * didn't open while the whiteboard, which downloads nothing, worked).
     */
    public static String ip() {
        String tv = Store.getStr("last_tv", "");
        String r = ipFor(tv);
        return r != null ? r : wifiIp();
    }

    /** The local address Android would use to reach `target` (a routing question only — nothing is sent). */
    public static String ipFor(String target) {
        if (target == null || target.isEmpty() || !target.matches("[0-9.]+")) return null;
        try (java.net.DatagramSocket s = new java.net.DatagramSocket()) {
            s.connect(InetAddress.getByName(target), 9);
            InetAddress a = s.getLocalAddress();
            if (a != null && !a.isAnyLocalAddress() && !a.isLoopbackAddress() && a instanceof Inet4Address) return a.getHostAddress();
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Wi-Fi / Ethernet first; mobile data, VPN and other virtual connections last. */
    static String wifiIp() {
        String best = null, fallback = null;
        int bestRank = 99;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName() == null ? "" : ni.getName().toLowerCase(java.util.Locale.ROOT);
                int rank = n.startsWith("wlan") || n.startsWith("eth") || n.startsWith("swlan") || n.startsWith("ap") ? 0
                        : n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("tun") || n.startsWith("wg")
                        || n.startsWith("ppp") || n.startsWith("v4-") || n.startsWith("dummy") || n.startsWith("clat") ? 5 : 2;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a.isLoopbackAddress() || !(a instanceof Inet4Address)) continue;
                    if (a.isSiteLocalAddress() && rank < bestRank) {
                        best = a.getHostAddress();
                        bestRank = rank;
                    }
                    if (fallback == null) fallback = a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return best != null ? best : fallback != null ? fallback : "TV-IP";
    }

    public static final class Fetched {
        public byte[] data;
        public String fileName;
    }

    public static Fetched fetch(String u, int maxBytes) throws Exception {
        HttpURLConnection c = null;
        URL url = new URL(u);
        // follow redirects manually so http <-> https hops work too
        for (int hop = 0; hop < 6; hop++) {
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", UA);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                url = new URL(url, c.getHeaderField("Location"));
                c.disconnect();
                continue;
            }
            if (code >= 400) throw new Exception("The server answered " + code);
            break;
        }
        Fetched f = new Fetched();
        String cd = c.getHeaderField("Content-Disposition");
        if (cd != null) {
            int i = cd.toLowerCase().indexOf("filename=");
            if (i >= 0) {
                String n = cd.substring(i + 9).replace("\"", "").trim();
                int semi = n.indexOf(';');
                if (semi >= 0) n = n.substring(0, semi);
                f.fileName = n;
            }
        }
        if (f.fileName == null) {
            String p = url.getPath();
            int s = p.lastIndexOf('/');
            f.fileName = URLDecoder.decode(s >= 0 ? p.substring(s + 1) : p, "UTF-8");
        }
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream o = new ByteArrayOutputStream()) {
            byte[] b = new byte[16384];
            int n, total = 0;
            while ((n = in.read(b)) > 0) {
                o.write(b, 0, n);
                total += n;
                if (total > maxBytes) throw new Exception("File is too big");
            }
            f.data = o.toByteArray();
        } finally {
            c.disconnect();
        }
        return f;
    }

    public static String fetchText(String u) throws Exception {
        return new String(fetch(u, 40_000_000).data, "UTF-8");
    }

    public static File subDir(Context c) {
        File d = new File(c.getCacheDir(), "subs");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    public static void cleanSubtitleCache(Context c) {
        File[] fs = subDir(c).listFiles();
        if (fs == null) return;
        long old = System.currentTimeMillis() - 3L * 24 * 3600 * 1000;
        for (File f : fs) if (f.lastModified() < old) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    public static File downloadSubtitle(Context c, String url) throws Exception {
        Fetched f = fetch(url, 30_000_000);
        return saveSubtitle(c, f.data, f.fileName);
    }

    /** Saves subtitle bytes; unpacks .gz and .zip (takes the first subtitle inside). */
    public static File saveSubtitle(Context c, byte[] data, String name) throws Exception {
        if (name == null || name.trim().isEmpty()) name = "subtitle.srt";
        if (data.length > 2 && (data[0] & 0xff) == 0x1f && (data[1] & 0xff) == 0x8b) {
            data = readAll(new GZIPInputStream(new ByteArrayInputStream(data)));
            if (name.toLowerCase().endsWith(".gz")) name = name.substring(0, name.length() - 3);
        } else if (data.length > 2 && data[0] == 'P' && data[1] == 'K') {
            try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(data))) {
                ZipEntry e;
                byte[] found = null;
                while ((e = z.getNextEntry()) != null) {
                    if (!e.isDirectory() && Formats.isSub(e.getName())) {
                        found = readAll(z);
                        name = e.getName();
                        break;
                    }
                }
                if (found == null) throw new Exception("No subtitle file inside the zip");
                data = found;
            }
        }
        name = name.replaceAll(".*[/\\\\]", "").replaceAll("[^A-Za-z0-9._ -]", "_");
        if (!Formats.isSub(name)) name = name + ".srt";
        File out = new File(subDir(c), System.currentTimeMillis() + "_" + name);
        try (FileOutputStream o = new FileOutputStream(out)) {
            o.write(data);
        }
        return out;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[16384];
        int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        return o.toByteArray();
    }

    public static String fmtTime(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000, h = s / 3600, m = (s / 60) % 60;
        s = s % 60;
        return h > 0 ? String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
                : String.format(java.util.Locale.US, "%d:%02d", m, s);
    }
}
