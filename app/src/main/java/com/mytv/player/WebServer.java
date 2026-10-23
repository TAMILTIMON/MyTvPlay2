package com.mytv.player;

import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import fi.iki.elonen.NanoHTTPD;

/** Phone web page: add links, play links/magnets now, and a full remote control. */
public class WebServer extends NanoHTTPD {
    private final Context ctx;

    public WebServer(Context c, int port) {
        super(port);
        ctx = c.getApplicationContext();
    }

    @Override
    public Response serve(IHTTPSession s) {
        try {
            String uri = s.getUri();
            if (uri.startsWith("/torrent/")) return torrent(s, uri);
            if (uri.startsWith("/local/")) return local(s, uri);   // random ids, needed by the TV player

            Map<String, String> files = new HashMap<>();
            if (Method.POST.equals(s.getMethod())) s.parseBody(files);
            @SuppressWarnings("deprecation") Map<String, String> p = s.getParms();

            // ---- remote PIN ----
            if ("/pair".equals(uri)) {
                if (!Pair.enabled()) return json("{\"ok\":true,\"token\":\"open\"}");
                final String pin = Pair.pin();
                Remote.MAIN.post(new Runnable() {
                    public void run() {
                        android.widget.Toast.makeText(ctx, "📱 A phone is connecting — PIN: " + pin,
                                android.widget.Toast.LENGTH_LONG).show();
                    }
                });
                if (val(p, "pin").isEmpty()) return fail("Enter the PIN shown on the TV");
                String tok = Pair.pair(val(p, "pin"));
                if (tok == null) return fail("Wrong PIN. Look at the TV screen.");
                return json(new JSONObject().put("ok", true).put("token", tok).toString());
            }
            if (!allowed(s, uri, p)) {
                Response r = newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json",
                        "{\"ok\":false,\"auth\":true,\"msg\":\"Enter the PIN shown on the TV\"}");
                r.addHeader("Cache-Control", "no-store");
                return r;
            }

            switch (uri) {
                case "/data":
                    return json(Store.toJson(100));

                case "/page": {
                    int from = 0;
                    try {
                        from = Integer.parseInt(val(p, "from"));
                    } catch (Exception ignored) {
                    }
                    return json(Store.pageJson(val(p, "playlist"), from, 200, val(p, "q")));
                }

                case "/add": {
                    String u = val(p, "url");
                    if (!Formats.looksPlayable(u)) return fail("That does not look like a link (http://, https://, magnet:, rtsp:// …)");
                    // a channel list link (m3u/pls)? add all its channels instead of the single link
                    List<Store.Item> ch = Formats.maybeList(u) ? readChannelList(u) : null;
                    if (ch != null && !ch.isEmpty()) {
                        int n = Store.addAll(val(p, "playlist"), ch);
                        Store.putStr("plsrc_" + (val(p, "playlist").isEmpty() ? "My Playlist" : val(p, "playlist")), u);
                        return json(new JSONObject().put("ok", true).put("msg", n + " channels added").toString());
                    }
                    Store.add(val(p, "playlist"), new Store.Item(val(p, "title"), u, val(p, "sub")));
                    return ok();
                }

                case "/expand": {
                    String pl = val(p, "playlist");
                    int idx = Integer.parseInt(val(p, "idx"));
                    List<Store.Item> items = Store.items(pl);
                    if (idx < 0 || idx >= items.size()) return fail("Not found");
                    List<Store.Item> ch = readChannelList(items.get(idx).u);
                    if (ch == null || ch.isEmpty()) return fail("This link is a single video, not a channel list");
                    java.util.Set<Integer> one = new java.util.HashSet<>();
                    one.add(idx);
                    Store.delMany(pl, one);
                    int n = Store.addAll(pl, ch);
                    return msg(n + " channels added");
                }

                case "/savequeue": {
                    final PlayerActivity pl = Remote.player;
                    if (pl == null) return fail("Nothing is playing on the TV");
                    List<Store.Item> q = Remote.call(new Callable<List<Store.Item>>() {
                        public List<Store.Item> call() {
                            return pl.queueCopy();
                        }
                    });
                    String name = val(p, "playlist").isEmpty() ? "IPTV" : val(p, "playlist");
                    Store.delPlaylist(name);
                    int n = Store.addAll(name, q);
                    return msg(n + " saved to playlist “" + name + "”");
                }

                case "/qsearch": {
                    final PlayerActivity pl = Remote.player;
                    if (pl == null) return json("{\"items\":[]}");
                    final String q = val(p, "q");
                    org.json.JSONArray a = Remote.call(new Callable<org.json.JSONArray>() {
                        public org.json.JSONArray call() throws Exception {
                            return pl.searchQueue(q);
                        }
                    });
                    return json(new JSONObject().put("items", a).toString());
                }

                case "/import": {
                    String text = val(p, "text");
                    String link = val(p, "link");
                    if (text.isEmpty() && !link.isEmpty()) text = Net.fetchText(link);
                    List<Store.Item> l = Store.parseM3u(text);
                    if (l.isEmpty()) return fail("No video links found in that playlist.");
                    int n = Store.addAll(val(p, "playlist"), l);
                    if (!link.isEmpty()) Store.putStr("plsrc_" + (val(p, "playlist").isEmpty() ? "My Playlist" : val(p, "playlist")), link);
                    return json("{\"ok\":true,\"added\":" + n + "}");
                }

                case "/del":
                    Store.delItem(val(p, "playlist"), Integer.parseInt(val(p, "idx")));
                    return ok();

                case "/move":
                    Store.moveItem(val(p, "playlist"), Integer.parseInt(val(p, "idx")), Integer.parseInt(val(p, "dir")));
                    return ok();

                case "/delmany": {
                    String pl = val(p, "playlist");
                    int n;
                    if ("1".equals(val(p, "all"))) {
                        n = Store.delMatching(pl, val(p, "q"), ints(val(p, "except")));
                    } else {
                        n = Store.delMany(pl, ints(val(p, "idx")));
                    }
                    return msg(n + " deleted");
                }

                case "/backup": {
                    Response r = newFixedLengthResponse(Response.Status.OK, "application/json", Backup.build(ctx));
                    r.addHeader("Content-Disposition", "attachment; filename=\"" + Backup.fileName() + "\"");
                    return r;
                }

                case "/restore": {
                    String text = val(p, "text");
                    if (text.isEmpty() && files.get("file") != null) text = new String(readFile(new File(files.get("file"))), "UTF-8");
                    if (text.isEmpty()) return fail("Choose a backup file");
                    return msg(Backup.restore(text));
                }

                case "/subsearch": {
                    org.json.JSONArray a = new org.json.JSONArray();
                    List<SubSearch.Hit> hits = SubSearch.search(val(p, "q"));
                    for (SubSearch.Hit h : hits) a.put(SubSearch.label(h));
                    return json(new JSONObject().put("ok", true).put("items", a).toString());
                }

                case "/subpick": {
                    int si = Integer.parseInt(val(p, "i"));
                    List<SubSearch.Hit> hits = SubSearch.last;
                    if (si < 0 || si >= hits.size()) return fail("Search again");
                    File f = SubSearch.download(ctx, hits.get(si));
                    return msg(command("subfile", f.getAbsolutePath(), ""));
                }

                case "/dl":
                    if (!Formats.looksPlayable(val(p, "url"))) return fail("Paste a magnet or .torrent link");
                    return msg(TorrentDownloads.add(ctx, val(p, "url")));

                case "/dllist":
                    return json(new JSONObject().put("items", TorrentDownloads.json()).put("night", TorrentDownloads.nightOnly()).toString());

                case "/dlcancel":
                    TorrentDownloads.cancel(val(p, "src"), "1".equals(val(p, "del")));
                    return msg("Removed from Downloads");

                case "/dlnight":
                    Store.putBool("dl_night", "1".equals(val(p, "v")));
                    return msg(TorrentDownloads.nightOnly() ? "Downloads only at night (1–6 AM)" : "Downloads any time");

                case "/otherallow": {
                    if (SessionWatcher.allowed(ctx)) return msg("Already on");
                    if (!AdbClient.enabled()) return fail("Turn on the 🔌 Full remote (ADB) first, or TV Settings > Notification access > TamilCastPlay");
                    String e = SessionWatcher.enableViaAdb(ctx);
                    return e == null ? msg("✅ The remote now shows videos from other apps") : fail(e);
                }

                case "/rename":
                    return Store.renamePlaylist(val(p, "playlist"), val(p, "name")) ? msg("Renamed") : fail("Name already used");

                case "/edit": {
                    int ei = Integer.parseInt(val(p, "idx"));
                    List<Store.Item> items = Store.items(val(p, "playlist"));
                    if (ei < 0 || ei >= items.size()) return fail("Not found");
                    Store.Item old = items.get(ei);
                    Store.setItem(val(p, "playlist"), ei, new Store.Item(val(p, "title"), val(p, "url").isEmpty() ? old.u : val(p, "url"),
                            old.s, old.g, old.l));
                    return msg("Saved");
                }

                case "/refreshlist": {
                    String pl = val(p, "playlist");
                    String src = Store.getStr("plsrc_" + pl, "");
                    if (src.isEmpty()) return fail("This playlist has no link to update from");
                    List<Store.Item> ch = readChannelList(src);
                    if (ch == null || ch.isEmpty()) return fail("Could not read the list");
                    Store.replaceAll(pl, ch);
                    return msg(ch.size() + " channels updated");
                }

                case "/scan":
                    if ("remove".equals(val(p, "do"))) return msg(ChannelScanner.removeDead(val(p, "playlist")) + " removed");
                    if ("status".equals(val(p, "do"))) return msg(ChannelScanner.status());
                    return msg(ChannelScanner.start(ctx, val(p, "playlist")));

                case "/fav":
                    return msg(Store.toggleFav(val(p, "url")) ? "★ Favourite" : "☆ Removed");

                case "/delpls": {
                    java.util.List<String> names = new java.util.ArrayList<>();
                    for (String n : val(p, "names").split("\n")) if (!n.trim().isEmpty()) names.add(n.trim());
                    return msg(Store.delPlaylists(names) + " playlists deleted");
                }

                case "/delpl":
                    Store.delPlaylist(val(p, "playlist"));
                    return ok();

                case "/playlist": {
                    if (!Family.playlistAllowed(val(p, "playlist"))) return fail("🔒 Locked for the kids profile");
                    if (Family.minutesLeft() == 0) return fail("⏰ Screen time for today is used up");
                    List<Store.Item> items = Store.items(val(p, "playlist"));
                    if (items.isEmpty()) return fail("That playlist is empty");
                    int idx = 0;
                    try {
                        idx = Integer.parseInt(val(p, "idx"));
                    } catch (Exception ignored) {
                    }
                    Remote.play(ctx, items, Math.max(0, Math.min(idx, items.size() - 1)));
                    return msg("Playing on the TV");
                }

                case "/play": {
                    if (Family.minutesLeft() == 0) return fail("⏰ Screen time for today is used up");
                    String u = val(p, "url");
                    if (!Formats.looksPlayable(u)) return fail("Paste a link first (http://, https://, magnet:, rtsp:// …)");
                    final Store.Item it = new Store.Item(val(p, "title"), u, val(p, "sub"));
                    Store.addRecent(it);
                    final PlayerActivity pl = Remote.player;
                    if ("queue".equals(val(p, "mode")) && pl != null) {
                        Remote.call(new Callable<Object>() {
                            @Override
                            public Object call() {
                                pl.enqueue(it);
                                return null;
                            }
                        });
                        return msg("Added to the TV queue");
                    }
                    Remote.play(ctx, Collections.singletonList(it), 0);
                    if (App.visible == 0 && !Perms.overlayOk(ctx)) {
                        return msg("Sent — if the TV doesn't start, open TamilCastPlay on the TV once, or allow “Display over other apps” in its ⚙ Settings");
                    }
                    return msg(Formats.isTorrent(u) ? "Starting torrent on the TV… (getting info can take a minute)"
                            : "Playing on the TV");
                }

                case "/ping":
                    return json(new JSONObject().put("app", "mytvplayer").put("name", Discovery.deviceName(ctx))
                            .put("tv", Ui.isTv(ctx)).put("ip", Net.ip()).toString());

                case "/devices": {
                    org.json.JSONArray a = new org.json.JSONArray();
                    a.put(new JSONObject().put("name", Discovery.deviceName(ctx)).put("ip", Net.ip()).put("me", true));
                    for (Discovery.Device d : Discovery.scanBlocking(9000)) {
                        a.put(new JSONObject().put("name", d.name).put("ip", d.ip).put("me", false));
                    }
                    return json(new JSONObject().put("devices", a).toString());
                }

                case "/playmany": {
                    String[] us = val(p, "urls").split("\\n");
                    String[] ts = val(p, "titles").split("\\n");
                    List<Store.Item> items = new java.util.ArrayList<>();
                    for (int i = 0; i < us.length; i++) {
                        if (!Formats.looksPlayable(us[i].trim())) continue;
                        items.add(new Store.Item(i < ts.length ? ts[i] : "", us[i].trim(), ""));
                    }
                    if (items.isEmpty()) return fail("Nothing to play");
                    return msg(playItems(items, val(p, "mode")));
                }

                case "/torrentupload":
                case "/mediaupload": {
                    String tmp = files.get("file");
                    if (tmp == null) return fail("Choose a file first");
                    String name = val(p, "file").replaceAll(".*[/\\\\]", "").replaceAll("[^A-Za-z0-9._ ()\\[\\]-]", "_");
                    boolean tor = uri.equals("/torrentupload");
                    if (tor && !name.toLowerCase().endsWith(".torrent")) name = name + ".torrent";
                    File dir = new File(tor ? ctx.getCacheDir() : bigDir(), tor ? "torrent_files" : "uploads");
                    //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                    File out = new File(dir, name);
                    if (!new File(tmp).renameTo(out)) {
                        try (InputStream in = new FileInputStream(tmp);
                             java.io.OutputStream o = new java.io.FileOutputStream(out)) {
                            byte[] b = new byte[65536];
                            int n;
                            while ((n = in.read(b)) > 0) o.write(b, 0, n);
                        }
                    }
                    Store.Item it = new Store.Item(name, out.getAbsolutePath(), "");
                    if (!val(p, "playlist").isEmpty()) {
                        Store.add(val(p, "playlist"), it);
                        return msg("Saved to playlist " + val(p, "playlist"));
                    }
                    return msg(playItems(Collections.singletonList(it), val(p, "mode")));
                }

                case "/apps": {
                    org.json.JSONArray a = new org.json.JSONArray();
                    for (AppsHub.App ap : AppsHub.favorites(ctx)) {
                        a.put(new JSONObject().put("name", ap.name).put("pkg", ap.pkg).put("installed", ap.installed)
                                .put("icon", ap.installed ? AppsHub.iconBase64(ctx, ap.pkg) : ""));
                    }
                    return json(new JSONObject().put("apps", a).toString());
                }

                case "/launch": {
                    final String pkg = val(p, "pkg");
                    if (pkg.isEmpty()) return fail("No app chosen");
                    if (!Family.appAllowed(pkg)) return fail("🔒 Locked for the kids profile");
                    if (App.visible == 0 && !Perms.overlayOk(ctx)) {
                        return fail("Open TamilCastPlay on the TV first, or allow “Display over other apps” in its ⚙ Settings");
                    }
                    String r = Remote.call(new Callable<String>() {
                        public String call() {
                            return AppsHub.launch(ctx, pkg);
                        }
                    });
                    return msg(r);
                }

                case "/history": {
                    org.json.JSONArray a = new org.json.JSONArray();
                    for (Store.Watch w : Store.history()) {
                        a.put(new JSONObject().put("t", w.t).put("u", w.u).put("p", w.pos).put("l", w.len)
                                .put("thumb", Thumbs.file(ctx, w.u).exists() ? Thumbs.key(w.u) : ""));
                    }
                    return json(new JSONObject().put("items", a).toString());
                }

                case "/historydel":
                    Store.removeHistory(val(p, "url"));
                    return ok();

                case "/thumb": {
                    File f = Thumbs.byKey(ctx, val(p, "k"));
                    if (!f.exists()) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "");
                    Response r = newFixedLengthResponse(Response.Status.OK, "image/jpeg", new FileInputStream(f), f.length());
                    r.addHeader("Cache-Control", "max-age=300");
                    return r;
                }

                case "/browse": {
                    final String u = val(p, "url");
                    Intent bi = new Intent(ctx, BrowserActivity.class);
                    if (!u.isEmpty()) bi.putExtra(BrowserActivity.EXTRA_URL, u);
                    bi.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(bi);
                    return msg(App.visible == 0 && !Perms.overlayOk(ctx)
                            ? "Sent — open TamilCastPlay on the TV if the browser doesn't appear" : "Opening the browser on the TV");
                }

                case "/state":
                    return json(state().toString());

                case "/options":
                    return json(PlayerActivity.optionsJson().toString());

                case "/cmd": {
                    String c = val(p, "c");
                    String v = val(p, "v");
                    String b = val(p, "b");
                    if ("adbsetup".equals(c)) {
                        // runs on the TV; the TV shows "Allow debugging?" the first time
                        new Thread(() -> {
                            String e = AdbClient.connect(ctx, true);
                            Store.putBool("adb_on", e == null);
                            final String m2 = e == null ? "✅ Full remote is ON" : e;
                            Remote.MAIN.post(() -> android.widget.Toast.makeText(ctx, m2, android.widget.Toast.LENGTH_LONG).show());
                        }).start();
                        return msg("On the TV: press “Allow” (tick “Always allow”) if it asks");
                    }
                    if ("adboff".equals(c)) {
                        Store.putBool("adb_on", false);
                        return msg("Full remote (ADB) off");
                    }
                    if ("palette".equals(c)) {
                        try {
                            int pi = Integer.parseInt(v);
                            Store.putInt("pal", pi);
                            Store.putInt("c_acc", 0);
                            Store.putInt("c_acc2", 0);
                            return msg("🎨 " + Palette.NAMES[Math.max(0, Math.min(Palette.NAMES.length - 1, pi))]
                                    + " (applies when you reopen a screen)");
                        } catch (Exception e) {
                            return fail("Bad palette");
                        }
                    }
                    if ("apptheme".equals(c)) {
                        Store.putStr("theme", v);
                        return msg("TV app theme: " + v + " (applies when you reopen a screen)");
                    }
                    if ("devname".equals(c)) {
                        Store.putStr("devname", v);
                        Discovery.unregister(ctx);
                        Discovery.register(ctx);
                        return msg("Renamed to " + v);
                    }
                    if ("subadd".equals(c)) {
                        if (!Formats.looksPlayable(v)) return fail("Paste a subtitle link first");
                        File f = Net.downloadSubtitle(ctx, v);
                        c = "subfile";
                        v = f.getAbsolutePath();
                    }
                    return msg(command(c, v, b));
                }

                case "/key": {
                    String k = val(p, "k");
                    if ("home".equals(k)) {
                        Remote.openHome(ctx);
                        return ok();
                    }
                    String err = Remote.press(ctx, k);
                    return err == null ? ok() : fail(err);
                }

                case "/type": {
                    String err = Remote.type(ctx, val(p, "text"));
                    return err == null ? msg("✅") : fail(err);
                }

                case "/subupload": {
                    String tmp = files.get("file");
                    if (tmp == null) return fail("Choose a subtitle file first");
                    byte[] data = readFile(new File(tmp));
                    File f = Net.saveSubtitle(ctx, data, val(p, "file"));
                    return msg(command("subfile", f.getAbsolutePath(), ""));
                }

                case "/torrentstop": {
                    final TorrentEngine te = TorrentEngine.get(ctx);
                    final String h = te.currentHash();
                    new Thread(() -> {
                        if (h.isEmpty()) te.stop();
                        else te.deleteData(h);
                    }).start();
                    return msg("Torrent stopped and its data deleted");
                }

                case "/tvinfo":
                    return json(TvInfo.json(ctx).toString());

                case "/tvtest": {
                    if (TvInfo.busy()) return fail("A test is already running");
                    final String t = val(p, "t");
                    new Thread(() -> {
                        if ("disk".equals(t)) TvInfo.diskTest(ctx, null);
                        else if ("bench".equals(t)) TvInfo.benchmark(null);
                        else TvInfo.speedTest(null);
                    }).start();
                    return msg("Test started on the TV…");
                }

                case "/tvclean":
                    return msg(TvInfo.clean(ctx));

                case "/tvapps": {
                    if (!AdbClient.enabled()) return fail("Turn on the 🔌 Full remote (ADB) first");
                    org.json.JSONArray a = new org.json.JSONArray();
                    for (TvInfo.Proc pr : TvInfo.running(ctx)) {
                        a.put(new JSONObject().put("name", pr.name).put("label", AppsHub.label(ctx, pr.name.split(":")[0]))
                                .put("mem", TvInfo.gb(pr.rss)));
                    }
                    return json(new JSONObject().put("ok", true).put("apps", a).toString());
                }

                case "/tvstop":
                    return msg(TvInfo.forceStop(ctx, val(p, "name")));

                case "/torrentcache": {
                    final TorrentEngine te = TorrentEngine.get(ctx);
                    if ("clear".equals(val(p, "do")) || "all".equals(val(p, "do"))) {
                        final boolean all = "all".equals(val(p, "do"));
                        new Thread(() -> te.clearCache(all)).start();
                        return msg(all ? "All saved torrents deleted" : "Saved torrents deleted (kept movies stay)");
                    }
                    return json(new JSONObject().put("bytes", TorrentEngine.cacheBytes(ctx)).toString());
                }

                case "/":
                case "/index.html":
                    try (InputStream in = ctx.getAssets().open("index.html")) {
                        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8",
                                new String(readStream(in), "UTF-8"));
                    }

                default:
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found");
            }
        } catch (Exception e) {
            return fail(e.getMessage() == null ? String.valueOf(e) : e.getMessage());
        }
    }

    /** Phone page, ping, and streams for the player itself are open; everything else needs the PIN token. */
    private boolean allowed(IHTTPSession s, String uri, Map<String, String> p) {
        if (!Pair.enabled()) return true;
        if (uri.equals("/") || uri.equals("/index.html") || uri.equals("/ping") || uri.equals("/favicon.ico")) return true;
        String ip = s.getRemoteIpAddress();
        if ("127.0.0.1".equals(ip) || "::1".equals(ip) || Net.ip().equals(ip)) return true;
        String t = s.getHeaders().get("x-token");
        if (t == null || t.isEmpty()) t = p.get("t");
        return Pair.valid(t);
    }

    /** Downloads an .m3u/.pls link; returns its channels, or null when it is a single HLS video. */
    private List<Store.Item> readChannelList(String u) {
        try {
            String url = u.split("\\|")[0];
            String text = url.startsWith("/") ? new String(readFile(new File(url)), "UTF-8") : Net.fetchText(url);
            if (text.contains("#EXT-X-")) return null;
            return Formats.ext(url).equals("pls") ? Store.parsePls(text) : Store.parseM3u(text);
        } catch (Exception e) {
            return null;
        }
    }

    private static java.util.Set<Integer> ints(String csv) {
        java.util.Set<Integer> s = new java.util.HashSet<>();
        for (String x : csv.split(",")) {
            try {
                if (!x.trim().isEmpty()) s.add(Integer.parseInt(x.trim()));
            } catch (Exception ignored) {
            }
        }
        return s;
    }

    private File bigDir() {
        File best = ctx.getCacheDir();
        File[] ext = ctx.getExternalCacheDirs();
        if (ext != null) for (File e : ext) if (e != null && e.getUsableSpace() > best.getUsableSpace()) best = e;
        return best;
    }

    private String playItems(final List<Store.Item> items, String mode) throws Exception {
        final PlayerActivity pl = Remote.player;
        if ("queue".equals(mode) && pl != null) {
            Remote.call(new Callable<Object>() {
                @Override
                public Object call() {
                    for (Store.Item it : items) pl.enqueue(it);
                    return null;
                }
            });
            return items.size() + " added to the TV queue";
        }
        if (items.size() == 1) Store.addRecent(items.get(0));
        Remote.play(ctx, items, 0);
        if (App.visible == 0 && !Perms.overlayOk(ctx)) {
            return "Sent — if the TV doesn't start, open TamilCastPlay on the TV once, or allow “Display over other apps” in its ⚙ Settings";
        }
        return Formats.isTorrent(items.get(0).u) ? "Starting torrent on the TV… (can take a minute)" : "Playing on the TV";
    }

    /** Streams a file picked on this phone to the TV (HTTP range requests supported). */
    private Response local(IHTTPSession s, String uri) throws Exception {
        String[] parts = uri.split("/");
        if (parts.length < 3) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Bad link");
        android.net.Uri u = LocalShare.get(parts[2]);
        if (u == null) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File no longer shared");
        String name = parts.length > 3 ? parts[3] : "";
        LocalShare.Opened probe = LocalShare.open(ctx, u, 0);
        long size = probe.size;
        String range = s.getHeaders().get("range");
        if (size <= 0 || range == null || !range.startsWith("bytes=")) {
            Response r = size > 0 ? newFixedLengthResponse(Response.Status.OK, Formats.mime(name), probe.in, size)
                    : newChunkedResponse(Response.Status.OK, Formats.mime(name), probe.in);
            r.addHeader("Accept-Ranges", "bytes");
            return r;
        }
        probe.in.close();
        String rr = range.substring(6);
        int comma = rr.indexOf(',');
        if (comma >= 0) rr = rr.substring(0, comma);
        int dash = rr.indexOf('-');
        long start = 0, end = size - 1;
        String a = rr.substring(0, Math.max(0, dash)).trim(), b = dash >= 0 ? rr.substring(dash + 1).trim() : "";
        if (a.isEmpty()) {
            if (!b.isEmpty()) start = Math.max(0, size - Long.parseLong(b));
        } else {
            start = Long.parseLong(a);
            if (!b.isEmpty()) end = Math.min(Long.parseLong(b), size - 1);
        }
        if (start >= size || start > end) {
            Response r = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "");
            r.addHeader("Content-Range", "bytes */" + size);
            return r;
        }
        LocalShare.Opened o = LocalShare.open(ctx, u, start);
        Response r = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, Formats.mime(name), o.in, end - start + 1);
        r.addHeader("Accept-Ranges", "bytes");
        r.addHeader("Content-Range", "bytes " + start + "-" + end + "/" + size);
        return r;
    }

    private JSONObject state() throws Exception {
        final PlayerActivity pl = Remote.player;
        JSONObject o = null;
        if (pl != null) {
            try {
                o = Remote.call(new Callable<JSONObject>() {
                    @Override
                    public JSONObject call() throws Exception {
                        return pl.state();
                    }
                });
            } catch (Exception ignored) {
            }
        }
        if (o == null) {
            o = new JSONObject().put("player", false);
            JSONObject other = SessionWatcher.info(ctx);
            if (other != null) o.put("other", other);
        }
        o.put("otherAllowed", SessionWatcher.allowed(ctx));
        o.put("device", Discovery.deviceName(ctx));
        o.put("apptheme", Store.getStr("theme", "dark"));
        o.put("lang", Store.getStr("lang", "en"));
        o.put("access", TvAccess.on() || AdbClient.enabled());
        o.put("adb", AdbClient.enabled());
        o.put("scan", ChannelScanner.status());
        o.put("profile", Family.label());
        boolean tvDark = !"light".equals(Store.getStr("theme", "dark"));
        int[] pc = Palette.resolve(tvDark);
        o.put("pal", new JSONObject().put("i", Palette.index()).put("custom", Palette.hasCustom()).put("dark", tvDark)
                .put("acc", Palette.hex(pc[0])).put("acc2", Palette.hex(pc[1])).put("acc3", Palette.hex(pc[2]))
                .put("bg", Palette.hex(pc[3])).put("bg2", Palette.hex(pc[4])).put("card", Palette.hex(pc[5]))
                .put("bar", Palette.hex(pc[6])).put("text", Palette.hex(pc[7])).put("dim", Palette.hex(pc[8])));
        PlayerActivity.putSettings(o);
        o.put("torrent", TorrentEngine.get(ctx).status());
        return o;
    }

    private String command(final String c, final String v, final String b) throws Exception {
        final PlayerActivity pl = Remote.player;
        if (pl == null) {
            String r = SessionWatcher.control(ctx, c, v);
            if (r != null) return r;
        }
        if (pl != null) {
            return Remote.call(new Callable<String>() {
                @Override
                public String call() throws Exception {
                    return pl.command(c, v, b);
                }
            });
        }
        String r = PlayerActivity.applySetting(c, v, b);
        return r != null ? r + " (applies when a video plays)" : "Nothing is playing on the TV";
    }

    // ---------- torrent streaming with HTTP range support ----------
    private Response torrent(IHTTPSession s, String uri) throws Exception {
        String[] parts = uri.split("/");
        if (parts.length < 4) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Bad link");
        String h = parts[2];
        int idx = Integer.parseInt(parts[3]);
        String fname = parts.length > 4 ? parts[4] : "";
        TorrentEngine te = TorrentEngine.get(ctx);
        long size = te.prepare(h, idx);
        if (size < 0) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Torrent is not active");

        String range = s.getHeaders().get("range");
        long start = 0, end = size - 1;
        boolean partial = false;
        if (range != null && range.startsWith("bytes=")) {
            String r = range.substring(6);
            int comma = r.indexOf(',');
            if (comma >= 0) r = r.substring(0, comma);
            int dash = r.indexOf('-');
            if (dash >= 0) {
                String a = r.substring(0, dash).trim(), b = r.substring(dash + 1).trim();
                if (a.isEmpty()) {
                    if (!b.isEmpty()) start = Math.max(0, size - Long.parseLong(b));
                } else {
                    start = Long.parseLong(a);
                    if (!b.isEmpty()) end = Math.min(Long.parseLong(b), size - 1);
                }
                partial = true;
            }
        }
        if (start >= size || start > end) {
            Response r = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "");
            r.addHeader("Content-Range", "bytes */" + size);
            return r;
        }
        long len = end - start + 1;
        InputStream in = te.stream(h, idx, start, end);
        Response r = newFixedLengthResponse(partial ? Response.Status.PARTIAL_CONTENT : Response.Status.OK,
                Formats.mime(fname), in, len);
        r.addHeader("Accept-Ranges", "bytes");
        if (partial) r.addHeader("Content-Range", "bytes " + start + "-" + end + "/" + size);
        return r;
    }

    // ---------- helpers ----------
    private static byte[] readStream(InputStream in) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        return o.toByteArray();
    }

    private static byte[] readFile(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            return readStream(in);
        }
    }

    private static String val(Map<String, String> p, String k) {
        String v = p.get(k);
        return v == null ? "" : v.trim();
    }

    private Response json(String s) {
        Response r = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", s);
        r.addHeader("Cache-Control", "no-store");
        return r;
    }

    private Response ok() {
        return json("{\"ok\":true}");
    }

    private Response msg(String m) {
        try {
            return json(new JSONObject().put("ok", true).put("msg", m == null ? "" : m).toString());
        } catch (Exception e) {
            return ok();
        }
    }

    private Response fail(String m) {
        try {
            return json(new JSONObject().put("ok", false).put("msg", m).toString());
        } catch (Exception e) {
            return json("{\"ok\":false}");
        }
    }
}
