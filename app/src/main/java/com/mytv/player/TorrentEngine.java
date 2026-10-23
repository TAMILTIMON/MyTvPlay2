package com.mytv.player;

import android.content.Context;
import android.net.Uri;

import org.json.JSONObject;
import org.libtorrent4j.FileStorage;
import org.libtorrent4j.Priority;
import org.libtorrent4j.SessionHandle;
import org.libtorrent4j.SessionManager;
import org.libtorrent4j.SessionParams;
import org.libtorrent4j.SettingsPack;
import org.libtorrent4j.TorrentFlags;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentInfo;
import org.libtorrent4j.TorrentStatus;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Streams magnet links / .torrent files: downloads pieces in order and serves the
 * video to the player through http://127.0.0.1:8080/torrent/... while it downloads.
 * Only one torrent is active at a time; its data is deleted when you stop it.
 */
public final class TorrentEngine {
    private static TorrentEngine inst;

    public static synchronized TorrentEngine get(Context c) {
        if (inst == null) inst = new TorrentEngine(c.getApplicationContext());
        return inst;
    }

    private final Context ctx;
    private SessionManager session;
    private volatile TorrentHandle handle;
    private volatile TorrentInfo info;
    private volatile String hash = "";
    private volatile int activeFile = -1;
    private volatile String state = "idle";
    private volatile String name = "";
    private volatile int openToken = 0;

    private TorrentEngine(Context c) {
        ctx = c;
    }

    SessionManager sessionForDownloads() {
        return session();
    }

    File folder() {
        return baseDir();
    }

    private synchronized SessionManager session() {
        if (session == null) {
            SettingsPack sp = new SettingsPack();
            sp.connectionsLimit(200);
            sp.activeDownloads(3);
            SessionManager s = new SessionManager(false);
            s.start(new SessionParams(sp));
            session = s;
        }
        return session;
    }

    /** Folder with the most free space (USB drives / SD cards are used when available). */
    private File baseDir() {
        File best = ctx.getCacheDir();
        long free = best.getUsableSpace();
        File[] dirs = ctx.getExternalCacheDirs();
        if (dirs != null) {
            for (File d : dirs) {
                if (d == null) continue;
                long f = d.getUsableSpace();
                if (f > free) {
                    free = f;
                    best = d;
                }
            }
        }
        File out = new File(best, "torrents");
        //noinspection ResultOfMethodCallIgnored
        out.mkdirs();
        return out;
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /** Removes leftovers from an earlier run. */
    /** Keeps the cache: deletes data not marked "keep" after 7 days, or oldest first when space is low. */
    public static void cleanupAsync(final Context c) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                Store.init(c);
                for (File t : roots(c)) {
                    File[] kids = t.listFiles();
                    if (kids == null) continue;
                    List<File> dirs = new ArrayList<>();
                    for (File k : kids) {
                        if (!k.isDirectory()) continue;
                        if (inst != null && k.getName().equals(inst.hash)) continue;
                        if (Store.getBool("tkeep_" + k.getName(), false)) continue;
                        dirs.add(k);
                    }
                    Collections.sort(dirs, new Comparator<File>() {
                        public int compare(File a, File b) {
                            return Long.compare(a.lastModified(), b.lastModified());
                        }
                    });
                    long week = System.currentTimeMillis() - 7L * 24 * 3600 * 1000;
                    for (File d : dirs) {
                        boolean old = d.lastModified() < week;
                        boolean lowSpace = t.getUsableSpace() < 2L * 1024 * 1024 * 1024;
                        if (old || lowSpace) {
                            deleteTree(d);
                            //noinspection ResultOfMethodCallIgnored
                            new File(t, d.getName() + ".torrent").delete();
                        }
                    }
                }
            }
        }).start();
    }

    private static List<File> roots(Context c) {
        List<File> out = new ArrayList<>();
        List<File> base = new ArrayList<>();
        base.add(c.getCacheDir());
        File[] ext = c.getExternalCacheDirs();
        if (ext != null) for (File e : ext) if (e != null) base.add(e);
        for (File r : base) {
            File t = new File(r, "torrents");
            if (t.exists()) out.add(t);
        }
        return out;
    }

    private static long size(File f) {
        if (f.isFile()) return f.length();
        long n = 0;
        File[] k = f.listFiles();
        if (k != null) for (File x : k) n += size(x);
        return n;
    }

    /** Bytes used by saved torrents. */
    public static long cacheBytes(Context c) {
        long n = 0;
        for (File t : roots(c)) n += size(t);
        return n;
    }

    /** Deletes saved torrent data (optionally also the movies you chose to keep). */
    public void clearCache(boolean alsoKept) {
        stop();
        for (File t : roots(ctx)) {
            File[] kids = t.listFiles();
            if (kids == null) continue;
            for (File k : kids) {
                String h = k.getName().replace(".torrent", "");
                if (!alsoKept && Store.getBool("tkeep_" + h, false)) continue;
                deleteTree(k);
                if (alsoKept) Store.putBool("tkeep_" + h, false);
            }
        }
    }

    /** Deletes one torrent's saved data. */
    public void deleteData(String h) {
        synchronized (this) {
            if (h.equals(hash)) {
                openToken++;
                stopInternal(true);
            }
        }
        for (File t : roots(ctx)) {
            deleteTree(new File(t, h));
            //noinspection ResultOfMethodCallIgnored
            new File(t, h + ".torrent").delete();
        }
        Store.putBool("tkeep_" + h, false);
    }

    /** Marks a torrent as "keep" and adds its file to the Downloads playlist (plays offline). */
    public void keep(String h, int file, String title, String streamUrl) {
        Store.putBool("tkeep_" + h, true);
        String path = null;
        TorrentHandle th = handle;
        TorrentInfo ti = info;
        if (th != null && ti != null && h.equals(hash)) {
            try {
                long[] prog = th.fileProgress();
                if (file < prog.length && prog[file] >= ti.files().fileSize(file)) {
                    path = ti.files().filePath(file, th.savePath());
                }
            } catch (Throwable ignored) {
            }
        }
        Store.add("Downloads", new Store.Item(title, path != null ? path : streamUrl, ""));
    }

    /**
     * Gets the torrent info and starts it. Blocks (up to ~2 minutes for magnets), so call it
     * from a background thread. Returns one playable item per video/audio file.
     */
    public List<Store.Item> open(String uri) throws Exception {
        final int token;
        synchronized (this) {
            if (handle != null && uri.trim().equals(activeUri) && activeItems != null) {
                return new ArrayList<>(activeItems);   // already running: no restart, no re-download
            }
            stopInternal(false);
            token = ++openToken;
            state = "Getting torrent info…";
            name = Formats.titleFromUrl(uri);
        }
        SessionManager s = session();
        File dir = baseDir();
        TorrentInfo ti;
        String key = "tmeta_" + Integer.toHexString(uri.trim().hashCode());
        File saved = new File(Store.getStr(key, "/nonexistent"));
        byte[] meta = null;
        if (saved.exists()) {
            state = "Opening saved torrent…";
            ti = new TorrentInfo(saved);                 // cached: no internet needed for the info
        } else if (uri.trim().toLowerCase(Locale.US).startsWith("magnet:")) {
            byte[] data = s.fetchMagnet(uri.trim(), 120, dir);
            if (data == null) throw new Exception("Could not get the torrent info (no peers found). Try again later or use another link.");
            ti = TorrentInfo.bdecode(data);
            meta = data;
        } else if (uri.trim().startsWith("/")) {
            ti = new TorrentInfo(new File(uri.trim()));
        } else {
            meta = Net.fetch(uri.trim(), 20_000_000).data;
            ti = TorrentInfo.bdecode(meta);
        }
        final String hx = ti.infoHash().toHex();
        if (meta != null) {
            try {
                File mf = new File(dir, hx + ".torrent");
                try (java.io.FileOutputStream o = new java.io.FileOutputStream(mf)) {
                    o.write(meta);
                }
                Store.putStr(key, mf.getAbsolutePath());
            } catch (Exception ignored) {
            }
        }
        File tdir = new File(dir, hx);
        //noinspection ResultOfMethodCallIgnored
        tdir.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        tdir.setLastModified(System.currentTimeMillis());
        if (token != openToken) throw new Exception("Cancelled");

        final FileStorage fs = ti.files();
        List<Integer> media = new ArrayList<>();
        int biggest = -1;
        long biggestSize = -1;
        for (int i = 0; i < fs.numFiles(); i++) {
            if (fs.padFileAt(i)) continue;
            String n = fs.fileName(i);
            if (Formats.isVideo(n) || Formats.isAudio(n)) media.add(i);
            if (fs.fileSize(i) > biggestSize) {
                biggestSize = fs.fileSize(i);
                biggest = i;
            }
        }
        if (media.isEmpty() && biggest >= 0) media.add(biggest);
        if (media.isEmpty()) throw new Exception("This torrent has no files");
        Collections.sort(media, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return fs.filePath(a).compareToIgnoreCase(fs.filePath(b));
            }
        });
        final int want = Store.getInt("quality", 0);   // 0 = best, else 1080/720/480/360
        if (want > 0 && media.size() > 1) {
            int best = -1, bestScore = Integer.MAX_VALUE;
            for (int i = 0; i < media.size(); i++) {
                int q = qualityOf(fs.fileName(media.get(i)));
                if (q <= 0) continue;
                int score = q <= want ? want - q : 10000 + q;   // closest at or below the limit
                if (score < bestScore) {
                    bestScore = score;
                    best = i;
                }
            }
            if (best > 0) media.add(0, media.remove(best));
        }

        Priority[] pr = Priority.array(Priority.IGNORE, fs.numFiles());
        pr[media.get(0)] = Priority.DEFAULT;
        state = "Checking saved data…";
        s.download(ti, tdir, null, pr, null, TorrentFlags.SEQUENTIAL_DOWNLOAD);

        TorrentHandle th = null;
        for (int k = 0; k < 150 && th == null; k++) {
            TorrentHandle h = s.find(ti.infoHash());
            if (h != null && h.isValid()) th = h;
            else Thread.sleep(100);
        }
        if (th == null) throw new Exception("Could not start the torrent");
        th.unsetFlags(TorrentFlags.AUTO_MANAGED);
        th.resume();

        String h = hx;
        if (!uri.trim().startsWith("/") || !uri.contains("/cache/")) Store.putStr("torsrc_" + h, uri.trim());
        else {
            // keep a copy of uploaded .torrent files so "Continue watching" can restart them
            try {
                File keep = new File(ctx.getFilesDir(), "torrent_files");
                //noinspection ResultOfMethodCallIgnored
                keep.mkdirs();
                File dst = new File(keep, h + ".torrent");
                java.nio.channels.FileChannel in = new java.io.FileInputStream(uri.trim()).getChannel();
                java.nio.channels.FileChannel out = new java.io.FileOutputStream(dst).getChannel();
                out.transferFrom(in, 0, in.size());
                in.close();
                out.close();
                Store.putStr("torsrc_" + h, dst.getAbsolutePath());
            } catch (Exception ignored) {
            }
        }
        synchronized (this) {
            if (token != openToken) {
                s.remove(th);
                throw new Exception("Cancelled");
            }
            handle = th;
            info = ti;
            hash = h;
            activeFile = media.get(0);
            name = ti.name();
            state = "Downloading";
        }

        List<Store.Item> out = new ArrayList<>();
        for (int idx : media) {
            String fn = fs.fileName(idx);
            String sz = String.format(Locale.US, "  · %.1f GB", fs.fileSize(idx) / 1073741824.0);
            out.add(new Store.Item(fn + (media.size() > 1 ? sz : ""), "http://127.0.0.1:" + RemoteService.PORT + "/torrent/" + h + "/" + idx + "/"
                    + Uri.encode(fn), ""));
        }
        synchronized (this) {
            activeUri = uri.trim();
            activeItems = new ArrayList<>(out);
        }
        return out;
    }

    private volatile String activeUri = null;
    private volatile List<Store.Item> activeItems = null;

    /** 2160/1080/720/480/360 from a file name, or 0. */
    static int qualityOf(String n) {
        String l = n.toLowerCase(Locale.US);
        if (l.contains("2160") || l.contains("4k")) return 2160;
        if (l.contains("1080")) return 1080;
        if (l.contains("720")) return 720;
        if (l.contains("576") || l.contains("540")) return 576;
        if (l.contains("480")) return 480;
        if (l.contains("360")) return 360;
        return 0;
    }

    /** Stops downloading but KEEPS the data (so playing again needs no internet). */
    public synchronized void stop() {
        openToken++;
        stopInternal(false);
    }

    public void stopAsync() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                stop();
            }
        }).start();
    }

    private void stopInternal(boolean deleteFiles) {
        TorrentHandle th = handle;
        if (th != null && TorrentDownloads.has(hash) && !deleteFiles) th = null;   // keep downloading for later
        activeUri = null;
        activeItems = null;
        handle = null;
        info = null;
        hash = "";
        activeFile = -1;
        state = "idle";
        if (th != null && session != null) {
            try {
                if (deleteFiles) session.remove(th, SessionHandle.DELETE_FILES);
                else session.remove(th);
            } catch (Throwable ignored) {
            }
        }
    }

    public String currentHash() {
        return hash == null ? "" : hash;
    }

    public boolean isActiveHash(String h) {
        return handle != null && hash.equals(h);
    }

    public boolean isActive() {
        return handle != null;
    }

    /** Makes the requested file the one being downloaded. Returns its size, or -1. */
    public synchronized long prepare(String h, int file) {
        TorrentHandle th = handle;
        TorrentInfo ti = info;
        if (th == null || ti == null || !hash.equals(h)) return -1;
        FileStorage fs = ti.files();
        if (file < 0 || file >= fs.numFiles()) return -1;
        if (file != activeFile) {
            Priority[] pr = Priority.array(Priority.IGNORE, fs.numFiles());
            pr[file] = Priority.DEFAULT;
            th.prioritizeFiles(pr);
            th.clearPieceDeadlines();
            activeFile = file;
        }
        return fs.fileSize(file);
    }

    public InputStream stream(String h, int file, long from, long to) throws IOException {
        TorrentHandle th = handle;
        TorrentInfo ti = info;
        if (th == null || ti == null || !hash.equals(h)) throw new IOException("Torrent is not active");
        FileStorage fs = ti.files();
        String path = fs.filePath(file, th.savePath());
        return new PieceStream(th, path, fs.fileOffset(file), fs.fileSize(file), ti.pieceLength(),
                ti.numPieces(), from, to);
    }

    private final class PieceStream extends InputStream {
        private final TorrentHandle th;
        private final String path;
        private final long fileOffset, fileSize;
        private final int pieceLen, lastPiece;
        private final long end;
        private long pos;
        private RandomAccessFile raf;
        private volatile boolean closed;
        private int lastPrioritized = -1;

        PieceStream(TorrentHandle th, String path, long fileOffset, long fileSize, int pieceLen, int numPieces,
                    long from, long to) {
            this.th = th;
            this.path = path;
            this.fileOffset = fileOffset;
            this.fileSize = fileSize;
            this.pieceLen = pieceLen;
            this.lastPiece = (int) Math.min(numPieces - 1, (fileOffset + Math.max(0, fileSize - 1)) / pieceLen);
            this.pos = from;
            this.end = to;
        }

        private void prioritize(int piece) {
            // read-ahead window of ~16 MB (at least 6 pieces) with rising deadlines
            int window = Math.max(8, (32 * 1024 * 1024) / Math.max(1, pieceLen));
            for (int k = 0; k < window; k++) {
                int p = piece + k;
                if (p > lastPiece) break;
                if (!th.havePiece(p)) th.setPieceDeadline(p, 300 + k * 250);
            }
            lastPrioritized = piece;
        }

        private void waitFor(int piece) throws IOException {
            if (piece != lastPrioritized) prioritize(piece);
            long lastPush = System.currentTimeMillis();
            while (!th.havePiece(piece)) {
                if (closed || handle != th) throw new IOException("Torrent stopped");
                if (System.currentTimeMillis() - lastPush > 3000) {
                    prioritize(piece);
                    lastPush = System.currentTimeMillis();
                }
                try {
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    throw new IOException("Interrupted");
                }
            }
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n <= 0 ? -1 : (one[0] & 0xff);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (closed) throw new IOException("closed");
            if (pos > end || pos >= fileSize) return -1;
            if (len == 0) return 0;
            long abs = fileOffset + pos;
            int piece = (int) (abs / pieceLen);
            waitFor(piece);
            long pieceEnd = (long) (piece + 1) * pieceLen;
            int n = (int) Math.min(len, Math.min(pieceEnd - abs, end - pos + 1));
            for (int tries = 0; ; tries++) {
                try {
                    if (raf == null) raf = new RandomAccessFile(path, "r");
                    raf.seek(pos);
                    int r = raf.read(b, off, n);
                    if (r > 0) {
                        pos += r;
                        return r;
                    }
                } catch (IOException e) {
                    if (tries > 40) throw e;
                    if (raf != null) {
                        try {
                            raf.close();
                        } catch (IOException ignored) {
                        }
                        raf = null;
                    }
                }
                if (tries > 40) throw new IOException("Could not read torrent data");
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    throw new IOException("Interrupted");
                }
            }
        }

        @Override
        public void close() throws IOException {
            closed = true;
            if (raf != null) raf.close();
        }
    }

    public JSONObject status() {
        JSONObject o = new JSONObject();
        try {
            TorrentHandle th = handle;
            o.put("active", th != null);
            o.put("name", name);
            o.put("state", state);
            if (th != null && th.isValid()) {
                TorrentStatus st = th.status();
                if (st.state() == TorrentStatus.State.CHECKING_FILES) o.put("state", "Checking saved data…");
                o.put("peers", st.numPeers());
                o.put("seeds", st.numSeeds());
                o.put("speed", st.downloadPayloadRate());
                o.put("progress", Math.round(st.progress() * 1000) / 10.0);
            }
        } catch (Exception ignored) {
        }
        return o;
    }

    public String shortStatus() {
        TorrentHandle th = handle;
        if (th == null) return state.equals("idle") ? "" : state;
        try {
            TorrentStatus st = th.status();
            if (st.state() == TorrentStatus.State.CHECKING_FILES) {
                return String.format(Locale.US, "Checking saved data… %.0f%%", st.progress() * 100f);
            }
            return String.format(Locale.US, "Torrent: %d peers · %.1f MB/s · %.1f%%",
                    st.numPeers(), st.downloadPayloadRate() / 1048576f, st.progress() * 100f);
        } catch (Exception e) {
            return "Torrent";
        }
    }
}
