package com.mytv.player;

import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.interfaces.IMedia;
import org.videolan.libvlc.util.MediaBrowser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 🖧 Network folders: browse a PC / NAS (SMB, also UPnP / DLNA servers) and play without copying. */
public class NetworkActivity extends AppCompatActivity {
    private static final class Entry {
        String title, uri;
        boolean dir;
        int kind; // 0 entry, 1 action
        Runnable action;
    }

    private LibVLC vlc;
    private MediaBrowser browser;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Entry> entries = new ArrayList<>();
    private final List<String> rows = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private TextView header, info;
    private final List<String> stack = new ArrayList<>();   // folder history
    private String cur = null;                              // null = start page
    private final List<Entry> pending = new ArrayList<>();

    @Override
    protected void onCreate(Bundle b) {
        Ui.apply(this);
        super.onCreate(b);
        int pad = Ui.dp(this, Ui.isTv(this) ? 40 : 14);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Ui.background());
        root.setPadding(pad, Ui.dp(this, 24), pad, Ui.dp(this, 12));
        header = Ui.text(this, 22, Ui.TEXT, true);
        root.addView(header);
        info = Ui.text(this, 13, Ui.DIM, false);
        info.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 10));
        root.addView(info);
        ListView lv = new ListView(this);
        lv.setSelector(Ui.listSelector(this));
        lv.setDrawSelectorOnTop(true);
        lv.setDivider(new android.graphics.drawable.ColorDrawable(0));
        lv.setDividerHeight(Ui.dp(this, 6));
        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, rows) {
            @Override
            public View getView(int p, View cv, ViewGroup parent) {
                TextView t = (TextView) super.getView(p, cv, parent);
                UiKit.iconize(t, Ui.ACCENT);
                t.setTextColor(Ui.TEXT);
                t.setTextSize(18);
                t.setPadding(Ui.dp(NetworkActivity.this, 16), Ui.dp(NetworkActivity.this, 12), Ui.dp(NetworkActivity.this, 16),
                        Ui.dp(NetworkActivity.this, 12));
                if (cv == null) t.setBackground(Ui.card(NetworkActivity.this, Ui.CARD, Ui.STROKE, 14));
                return t;
            }
        };
        lv.setAdapter(adapter);
        lv.setOnItemClickListener((pa, v, pos, id) -> open(entries.get(pos)));
        lv.setOnItemLongClickListener((pa, v, pos, id) -> {
            Entry e = entries.get(pos);
            if (cur == null && e.kind == 0) {
                editServer(e);
                return true;
            }
            return false;
        });
        root.addView(lv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        ArrayList<String> opts = new ArrayList<>();
        opts.add("--network-caching=3000");
        vlc = new LibVLC(this, opts);
        browser = new MediaBrowser(vlc, listener, main);
        start();
    }

    private final MediaBrowser.EventListener listener = new MediaBrowser.EventListener() {
        public void onMediaAdded(int index, IMedia m) {
            try {
                Uri u = m.getUri();
                if (u == null) return;
                Entry e = new Entry();
                e.uri = u.toString();
                String t = m.getMeta(IMedia.Meta.Title);
                e.title = t != null && !t.isEmpty() ? t : Uri.decode(u.getLastPathSegment() == null ? u.getHost() : u.getLastPathSegment());
                e.dir = m.getType() == IMedia.Type.Directory;
                if (!e.dir && !Formats.isMedia(e.title) && !Formats.isMedia(e.uri)) return;
                pending.add(e);
                if (cur == null) render(true);   // discovered shares appear one by one
            } catch (Throwable ignored) {
            }
        }

        public void onMediaRemoved(int index, IMedia m) {
        }

        public void onBrowseEnd() {
            render(false);
        }
    };

    // ------------------------------------------------------------ start page
    private JSONArray servers() {
        try {
            return new JSONArray(Store.getStr("smb_servers", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private void start() {
        cur = null;
        stack.clear();
        pending.clear();
        header.setText(UiKit.noEmoji("🖧 " + T.t("Network folders")));
        info.setText(T.t("PCs, NAS and phones on this Wi-Fi — SMB, FTP, FTPS, SFTP, NFS, WebDAV, DLNA. Long-press a saved server to edit or remove it."));
        browser.discoverNetworkShares();
        phones.clear();
        Discovery.scan(new Discovery.Listener() {
            public void onFound(Discovery.Device d) {
                new Thread(() -> {
                    try {
                        org.json.JSONObject o = new org.json.JSONObject(new String(Net.fetch("http://" + d.ip + ":"
                                + RemoteService.PORT + "/ping", 4096).data, "UTF-8"));
                        if (!o.optBoolean("share")) return;
                        main.post(() -> {
                            Entry e = new Entry();
                            e.title = "📱  " + d.name + "   ·   " + T.t("phone / device storage");
                            e.uri = "phone://" + d.ip + "/";
                            e.dir = true;
                            phones.add(e);
                            if (cur == null) render(true);
                        });
                    } catch (Exception ignored) {
                    }
                }).start();
            }

            public void onDone() {
            }
        });
        render(true);
    }

    private final List<Entry> phones = new ArrayList<>();

    /** Lists a folder of a phone that shares its storage (ZH Play on the phone). */
    private void browsePhone(final String uri) {
        cur = uri;
        pending.clear();
        final Uri u = Uri.parse(uri);
        final String ip = u.getHost();
        final String path = u.getQueryParameter("p") == null ? "" : u.getQueryParameter("p");
        header.setText(UiKit.noEmoji("📱 " + (path.isEmpty() ? ip : new java.io.File(path).getName())));
        info.setText("…");
        render(true);
        new Thread(() -> {
            try {
                org.json.JSONObject o = new org.json.JSONObject(new String(Net.fetch("http://" + ip + ":" + RemoteService.PORT
                        + "/fs/list?p=" + Uri.encode(path), 2_000_000).data, "UTF-8"));
                final List<Entry> l = new ArrayList<>();
                org.json.JSONArray ds = o.optJSONArray("dirs"), fs = o.optJSONArray("files");
                if (ds != null) for (int i = 0; i < ds.length(); i++) {
                    Entry e = new Entry();
                    e.title = ds.getJSONObject(i).optString("n");
                    e.uri = "phone://" + ip + "/?p=" + Uri.encode(ds.getJSONObject(i).optString("p"));
                    e.dir = true;
                    l.add(e);
                }
                if (fs != null) for (int i = 0; i < fs.length(); i++) {
                    String n = fs.getJSONObject(i).optString("n");
                    if (Formats.isSub(n)) continue;
                    Entry e = new Entry();
                    e.title = n;
                    e.uri = "http://" + ip + ":" + RemoteService.PORT + "/fs/file/" + Uri.encode(n) + "?p="
                            + Uri.encode(fs.getJSONObject(i).optString("p"));
                    l.add(e);
                }
                main.post(() -> {
                    if (!uri.equals(cur)) return;
                    pending.clear();
                    pending.addAll(l);
                    render(false);
                });
            } catch (final Exception e) {
                main.post(() -> info.setText(UiKit.noEmoji("⚠ " + e.getMessage())));
            }
        }).start();
    }

    private Entry action(String title, Runnable r) {
        Entry e = new Entry();
        e.title = title;
        e.kind = 1;
        e.action = r;
        return e;
    }

    private void render(boolean partial) {
        entries.clear();
        rows.clear();
        if (cur == null) {
            entries.add(action("＋  " + T.t("Add a server (PC / NAS address)"), () -> editServer(null)));
            JSONArray s = servers();
            for (int i = 0; i < s.length(); i++) {
                JSONObject o = s.optJSONObject(i);
                Entry e = new Entry();
                e.title = "💾  " + o.optString("name");
                e.uri = o.optString("url");
                e.dir = true;
                entries.add(e);
            }
            entries.addAll(phones);
            for (Entry e : pending) {
                if (!e.dir) continue;
                Entry c = new Entry();
                c.title = "🖥  " + e.title + "   ·   " + T.t("found on the network");
                c.uri = e.uri;
                c.dir = true;
                entries.add(c);
            }
        } else {
            entries.add(action("⬆  ..", this::up));
            List<Entry> dirs = new ArrayList<>(), files = new ArrayList<>();
            for (Entry e : pending) (e.dir ? dirs : files).add(e);
            Collections.sort(dirs, (a, b) -> a.title.compareToIgnoreCase(b.title));
            Collections.sort(files, (a, b) -> a.title.compareToIgnoreCase(b.title));
            for (Entry e : dirs) {
                e.title = e.title.startsWith("📁") ? e.title : "📁  " + e.title;
                entries.add(e);
            }
            for (Entry e : files) {
                e.title = e.title.startsWith("🎬") ? e.title : "🎬  " + e.title;
                entries.add(e);
            }
            if (!partial && pending.isEmpty()) {
                info.setText(T.t("Nothing here, or a username / password is needed: go back and long-press the server to add them."));
            } else info.setText(Uri.decode(cur.replaceAll("//[^@/]*@", "//")));
        }
        for (Entry e : entries) rows.add(e.title);
        adapter.notifyDataSetChanged();
    }

    private void browse(String uri) {
        cur = uri;
        pending.clear();
        header.setText(UiKit.noEmoji("🖧 " + Uri.decode(Uri.parse(uri.replaceAll("//[^@/]*@", "//")).getLastPathSegment() == null
                ? Uri.parse(uri).getHost() : Uri.parse(uri).getLastPathSegment())));
        info.setText("…");
        render(true);
        browser.browse(Uri.parse(uri), MediaBrowser.Flag.NoSlavesAutodetect);
    }

    private void open(Entry e) {
        if (e.kind == 1) {
            e.action.run();
            return;
        }
        if (e.dir) {
            if (cur != null) stack.add(cur);
            if (e.uri.startsWith("phone://")) browsePhone(e.uri);
            else browse(withLogin(e.uri));
            return;
        }
        // play this file and the rest of the folder after it
        List<Store.Item> items = new ArrayList<>();
        int start = 0;
        for (Entry x : entries) {
            if (x.kind == 1 || x.dir) continue;
            if (x == e) start = items.size();
            items.add(new Store.Item(x.title.replace("🎬  ", ""), x.uri, ""));
        }
        Store.addRecent(new Store.Item(e.title.replace("🎬  ", ""), e.uri, ""));
        Remote.play(this, items, start);
    }

    private void up() {
        if (stack.isEmpty()) start();
        else {
            String u = stack.remove(stack.size() - 1);
            if (u.startsWith("phone://")) browsePhone(u);
            else browse(u);
        }
    }

    /** Found shares get the saved login of the same host. */
    private String withLogin(String uri) {
        if (uri.contains("@")) return uri;
        Uri u = Uri.parse(uri);
        JSONArray s = servers();
        for (int i = 0; i < s.length(); i++) {
            Uri su = Uri.parse(s.optJSONObject(i).optString("url"));
            if (u.getHost() != null && u.getHost().equalsIgnoreCase(su.getHost()) && su.getUserInfo() != null) {
                return uri.replaceFirst("://", "://" + su.getEncodedUserInfo() + "@");
            }
        }
        return uri;
    }

    private static final String[] PROTO = {"smb", "ftp", "ftps", "sftp", "nfs", "http", "https"};
    private static final String[] PROTO_NAME = {"SMB (Windows sharing / NAS)", "FTP", "FTPS (secure FTP)", "SFTP (SSH)",
            "NFS", "HTTP / WebDAV", "HTTPS / WebDAV"};
    private static final int[] PORT = {445, 21, 990, 22, 2049, 80, 443};

    private void editServer(final Entry existing) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), 0);
        final int[] proto = {0};
        final android.widget.Button protoBtn = Ui.button(this, PROTO_NAME[0], true);
        final EditText host = new EditText(this), port = new EditText(this), path = new EditText(this),
                user = new EditText(this), pass = new EditText(this), name = new EditText(this);
        host.setHint(T.t("Address — e.g. 192.168.1.10 or nas.local"));
        port.setHint(T.t("Port (empty = default)"));
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        path.setHint(T.t("Folder — e.g. Movies (optional)"));
        user.setHint(T.t("Username (optional)"));
        pass.setHint(T.t("Password (optional)"));
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        name.setHint(T.t("Name (e.g. Living-room PC)"));
        protoBtn.setOnClickListener(v -> new UiDialog(this).setTitle(T.t("Type"))
                .setItems(PROTO_NAME, (d, w) -> {
                    proto[0] = w;
                    protoBtn.setText(PROTO_NAME[w]);
                    port.setHint(T.t("Port (empty = default)") + " · " + PORT[w]);
                }).show());
        if (existing != null) {
            Uri u = Uri.parse(existing.uri);
            for (int i = 0; i < PROTO.length; i++) if (PROTO[i].equals(u.getScheme())) proto[0] = i;
            protoBtn.setText(PROTO_NAME[proto[0]]);
            host.setText(u.getHost());
            if (u.getPort() > 0) port.setText(String.valueOf(u.getPort()));
            path.setText(u.getPath() == null ? "" : u.getPath().replaceAll("^/|/$", ""));
            String ui = u.getUserInfo();
            if (ui != null) {
                String[] up = ui.split(":", 2);
                user.setText(up[0]);
                if (up.length > 1) pass.setText(up[1]);
            }
            name.setText(UiKit.noEmoji(existing.title.replace("💾  ", "")));
        }
        box.addView(protoBtn);
        box.addView(host);
        box.addView(port);
        box.addView(path);
        box.addView(user);
        box.addView(pass);
        box.addView(name);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(box);
        AlertDialog.Builder bd = new UiDialog(this).setTitle("🖧 " + T.t("Network server")).setView(sv)
                .setPositiveButton(T.t("Save"), (d, w) -> {
                    String h = host.getText().toString().trim().replace("\\", "/").replaceAll("^[a-z]+:/+", "").replaceAll("^/+", "");
                    if (h.isEmpty()) return;
                    String folder = path.getText().toString().trim().replace("\\", "/").replaceAll("^/+|/+$", "");
                    if (h.contains("/")) {          // "PC/Movies" typed in the address field
                        folder = h.substring(h.indexOf('/') + 1) + (folder.isEmpty() ? "" : "/" + folder);
                        h = h.substring(0, h.indexOf('/'));
                    }
                    String login = user.getText().toString().trim().isEmpty() ? ""
                            : Uri.encode(user.getText().toString().trim()) + (pass.getText().toString().isEmpty() ? ""
                            : ":" + Uri.encode(pass.getText().toString())) + "@";
                    String pt = port.getText().toString().trim();
                    String url = PROTO[proto[0]] + "://" + login + h + (pt.isEmpty() ? "" : ":" + pt) + "/" + (folder.isEmpty() ? "" : folder + "/");
                    JSONArray srv = servers(), out = new JSONArray();
                    for (int i = 0; i < srv.length(); i++) {
                        if (existing != null && srv.optJSONObject(i).optString("url").equals(existing.uri)) continue;
                        out.put(srv.optJSONObject(i));
                    }
                    try {
                        out.put(new JSONObject().put("name", name.getText().toString().trim().isEmpty() ? h + (folder.isEmpty() ? "" : "/" + folder)
                                : name.getText().toString().trim()).put("url", url));
                    } catch (Exception ignored) {
                    }
                    Store.putStr("smb_servers", out.toString());
                    render(false);
                })
                .setNegativeButton(T.t("Cancel"), null);
        if (existing != null && existing.title.startsWith("💾")) bd.setNeutralButton(T.t("Remove"), (d, w) -> {
            JSONArray srv = servers(), out = new JSONArray();
            for (int i = 0; i < srv.length(); i++) if (!srv.optJSONObject(i).optString("url").equals(existing.uri)) out.put(srv.optJSONObject(i));
            Store.putStr("smb_servers", out.toString());
            render(false);
        });
        bd.show();
    }

    @Override
    public void onBackPressed() {
        if (cur != null) up();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        try {
            browser.release();
            vlc.release();
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }
}
