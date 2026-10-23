package com.mytv.player;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.libtorrent4j.FileStorage;
import org.libtorrent4j.Priority;
import org.libtorrent4j.SessionManager;
import org.libtorrent4j.TorrentFlags;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentInfo;
import org.libtorrent4j.TorrentStatus;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** "Download for later": full torrent downloads in the background (optionally only at night). */
public final class TorrentDownloads {
    private TorrentDownloads() {
    }

    public static final class Dl {
        public String src, name = "Torrent", hash = "", state = "Waiting";
        public float progress;
        public long rate;
        public boolean done;
        TorrentHandle th;
    }

    private static final Map<String, Dl> list = new LinkedHashMap<>();   // by source link
    private static Context app;
    private static boolean started;

    public static synchronized boolean has(String hash) {
        if (hash == null || hash.isEmpty()) return false;
        for (Dl d : list.values()) if (hash.equals(d.hash) && !d.done) return true;
        return false;
    }

    public static boolean nightOnly() {
        return Store.getBool("dl_night", false);
    }

    private static void save() {
        JSONArray a = new JSONArray();
        synchronized (TorrentDownloads.class) {
            for (Dl d : list.values()) {
                try {
                    a.put(new JSONObject().put("src", d.src).put("name", d.name).put("hash", d.hash).put("done", d.done));
                } catch (Exception ignored) {
                }
            }
        }
        Store.putStr("dl_list", a.toString());
    }

    /** Called when the TV app starts: re-adds unfinished downloads and starts the checker. */
    public static synchronized void resumeAll(Context c) {
        if (started) return;
        started = true;
        app = c.getApplicationContext();
        try {
            JSONArray a = new JSONArray(Store.getStr("dl_list", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Dl d = new Dl();
                d.src = o.optString("src");
                d.name = o.optString("name");
                d.hash = o.optString("hash");
                d.done = o.optBoolean("done");
                d.state = d.done ? "Done" : "Waiting";
                list.put(d.src, d);
            }
        } catch (Exception ignored) {
        }
        new Thread(TorrentDownloads::loop).start();
    }

    public static String add(Context c, String src) {
        resumeAll(c);
        synchronized (TorrentDownloads.class) {
            if (list.containsKey(src) && !list.get(src).done) return "Already in Downloads";
            Dl d = new Dl();
            d.src = src;
            d.name = Formats.titleFromUrl(src);
            list.put(src, d);
        }
        save();
        return "⬇ Added to Downloads" + (nightOnly() ? " (downloads at night, 1–6 AM)" : "");
    }

    public static synchronized void cancel(String src, boolean deleteData) {
        Dl d = list.remove(src);
        if (d != null && d.th != null) {
            try {
                if (deleteData) TorrentEngine.get(app).deleteData(d.hash);
                else TorrentEngine.get(app).sessionForDownloads().remove(d.th);
            } catch (Exception ignored) {
            }
        }
        save();
    }

    public static synchronized List<Dl> all() {
        return new ArrayList<>(list.values());
    }

    private static boolean allowedNow() {
        if (!nightOnly()) return true;
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return h >= 1 && h < 6;
    }

    private static void loop() {
        while (true) {
            try {
                Thread.sleep(5000);
                boolean ok = allowedNow();
                for (Dl d : all()) {
                    if (d.done) continue;
                    if (d.th == null) {
                        if (ok) startOne(d);
                        else d.state = "Waiting for night (1–6 AM)";
                        continue;
                    }
                    if (!d.th.isValid()) {
                        d.th = null;
                        continue;
                    }
                    if (!ok) {
                        d.th.pause();
                        d.state = "Paused until night";
                        continue;
                    }
                    d.th.resume();
                    TorrentStatus st = d.th.status();
                    d.progress = st.progress();
                    d.rate = st.downloadPayloadRate();
                    d.state = st.state() == TorrentStatus.State.CHECKING_FILES ? "Checking saved data…"
                            : String.format(Locale.US, "%.1f%% · %.1f MB/s · %d peers", d.progress * 100, d.rate / 1048576f, st.numPeers());
                    if (st.isFinished() || d.progress >= 0.999f) finish(d);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static void startOne(Dl d) {
        try {
            d.state = "Getting torrent info…";
            TorrentEngine te = TorrentEngine.get(app);
            SessionManager s = te.sessionForDownloads();
            File dir = te.folder();
            String key = "tmeta_" + Integer.toHexString(d.src.trim().hashCode());
            File saved = new File(Store.getStr(key, "/nonexistent"));
            TorrentInfo ti;
            if (saved.exists()) ti = new TorrentInfo(saved);
            else if (d.src.startsWith("magnet:")) {
                byte[] data = s.fetchMagnet(d.src, 120, dir);
                if (data == null) {
                    d.state = "No peers yet — will retry";
                    return;
                }
                ti = TorrentInfo.bdecode(data);
                File mf = new File(dir, ti.infoHash().toHex() + ".torrent");
                try (java.io.FileOutputStream o = new java.io.FileOutputStream(mf)) {
                    o.write(data);
                }
                Store.putStr(key, mf.getAbsolutePath());
            } else if (d.src.startsWith("/")) ti = new TorrentInfo(new File(d.src));
            else ti = TorrentInfo.bdecode(Net.fetch(d.src, 20_000_000).data);
            d.hash = ti.infoHash().toHex();
            d.name = ti.name();
            Store.putBool("tkeep_" + d.hash, true);      // never auto-delete downloads
            FileStorage fs = ti.files();
            Priority[] pr = Priority.array(Priority.IGNORE, fs.numFiles());
            for (int i = 0; i < fs.numFiles(); i++) {
                String n = fs.fileName(i);
                if (Formats.isVideo(n) || Formats.isAudio(n) || Formats.isSub(n)) pr[i] = Priority.DEFAULT;
            }
            File tdir = new File(dir, d.hash);
            //noinspection ResultOfMethodCallIgnored
            tdir.mkdirs();
            TorrentHandle th = s.find(ti.infoHash());
            if (th == null || !th.isValid()) {
                s.download(ti, tdir, null, pr, null, new org.libtorrent4j.swig.torrent_flags_t());
                for (int k = 0; k < 100 && (th == null || !th.isValid()); k++) {
                    Thread.sleep(100);
                    th = s.find(ti.infoHash());
                }
            } else th.prioritizeFiles(pr);
            if (th == null) return;
            th.unsetFlags(TorrentFlags.AUTO_MANAGED);
            th.resume();
            d.th = th;
            save();
        } catch (Exception e) {
            d.state = "Error: " + e.getMessage() + " — will retry";
        }
    }

    private static void finish(Dl d) {
        d.done = true;
        d.state = "Done";
        try {
            TorrentInfo ti = d.th.torrentFile();
            FileStorage fs = ti.files();
            for (int i = 0; i < fs.numFiles(); i++) {
                String n = fs.fileName(i);
                if (Formats.isVideo(n) || Formats.isAudio(n)) {
                    Store.add("Downloads", new Store.Item(n, fs.filePath(i, d.th.savePath()), ""));
                }
            }
            TorrentEngine.get(app).sessionForDownloads().remove(d.th);   // stop sharing, keep the files
        } catch (Exception ignored) {
        }
        d.th = null;
        save();
        Remote.MAIN.post(() -> android.widget.Toast.makeText(app, "✅ Downloaded: " + d.name + " — see the Downloads playlist",
                android.widget.Toast.LENGTH_LONG).show());
    }

    public static JSONArray json() {
        JSONArray a = new JSONArray();
        for (Dl d : all()) {
            try {
                a.put(new JSONObject().put("src", d.src).put("name", d.name).put("state", d.state)
                        .put("progress", Math.round(d.progress * 1000) / 10.0).put("done", d.done));
            } catch (Exception ignored) {
            }
        }
        return a;
    }
}
