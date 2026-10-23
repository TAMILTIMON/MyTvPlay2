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
        header.setText("🖧 " + T.t("Network folders"));
        info.setText(T.t("PCs and NAS on this Wi-Fi (Windows file sharing / SMB, DLNA). Long-press a saved server to edit or remove it."));
        browser.discoverNetworkShares();
        render(true);
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
        header.setText("🖧 " + Uri.decode(Uri.parse(uri.replaceAll("//[^@/]*@", "//")).getLastPathSegment() == null
                ? Uri.parse(uri).getHost() : Uri.parse(uri).getLastPathSegment()));
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
            browse(withLogin(e.uri));
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
        else browse(stack.remove(stack.size() - 1));
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

    private void editServer(final Entry existing) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), 0);
        final EditText addr = new EditText(this), user = new EditText(this), pass = new EditText(this), name = new EditText(this);
        addr.setHint("192.168.1.10/Movies   or   \\\\PC\\Movies");
        user.setHint(T.t("Username (optional)"));
        pass.setHint(T.t("Password (optional)"));
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        name.setHint(T.t("Name (e.g. Living-room PC)"));
        if (existing != null) {
            Uri u = Uri.parse(existing.uri);
            addr.setText((u.getHost() == null ? "" : u.getHost()) + (u.getPath() == null ? "" : u.getPath()));
            String ui = u.getUserInfo();
            if (ui != null) {
                String[] up = ui.split(":", 2);
                user.setText(up[0]);
                if (up.length > 1) pass.setText(up[1]);
            }
            name.setText(existing.title.replace("💾  ", ""));
        }
        box.addView(addr);
        box.addView(user);
        box.addView(pass);
        box.addView(name);
        AlertDialog.Builder bd = new AlertDialog.Builder(this).setTitle("🖧 " + T.t("Network server")).setView(box)
                .setPositiveButton(T.t("Save"), (d, w) -> {
                    String a = addr.getText().toString().trim().replace("\\", "/").replaceAll("^(smb:)?/+", "");
                    if (a.isEmpty()) return;
                    String login = user.getText().toString().trim().isEmpty() ? ""
                            : Uri.encode(user.getText().toString().trim()) + (pass.getText().toString().isEmpty() ? ""
                            : ":" + Uri.encode(pass.getText().toString())) + "@";
                    String url = "smb://" + login + a + (a.endsWith("/") ? "" : "/");
                    JSONArray s = servers(), out = new JSONArray();
                    for (int i = 0; i < s.length(); i++) {
                        if (existing != null && s.optJSONObject(i).optString("url").equals(existing.uri)) continue;
                        out.put(s.optJSONObject(i));
                    }
                    try {
                        out.put(new JSONObject().put("name", name.getText().toString().trim().isEmpty() ? a : name.getText().toString().trim())
                                .put("url", url));
                    } catch (Exception ignored) {
                    }
                    Store.putStr("smb_servers", out.toString());
                    render(false);
                })
                .setNegativeButton(T.t("Cancel"), null);
        if (existing != null && existing.title.startsWith("💾")) bd.setNeutralButton(T.t("Remove"), (d, w) -> {
            JSONArray s = servers(), out = new JSONArray();
            for (int i = 0; i < s.length(); i++) if (!s.optJSONObject(i).optString("url").equals(existing.uri)) out.put(s.optJSONObject(i));
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
