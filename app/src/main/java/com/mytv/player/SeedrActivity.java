package com.mytv.player;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/**
 * Seedr inside ZH Play: your Seedr cloud with Files · Transfers · Search, storage bar,
 * add magnet / .torrent links, play on the phone, send to the TV, download, rename, delete.
 */
public class SeedrActivity extends Activity {
    private static final String EXTRA_MAGNET = "magnet";
    private static final int BG = 0xFF0B0F1A, PANEL = 0xFF121826, SUB = 0x99FFFFFF;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayList<String[]> path = new ArrayList<>();   // {folderId, name}; empty = top
    private int tab = 0;                                          // 0 Files · 1 Transfers · 2 Search
    private String pendingMagnet;                                 // added right after signing in
    private String lastQuery = "";
    private int loadSeq;                                          // drops answers from older loads

    private TextView title, account;
    private ProgressBar quota, busy;
    private LinearLayout list;
    private final TextView[] tabViews = new TextView[3];

    /** Open the Seedr screen. */
    public static void open(Context c) {
        open(c, null);
    }

    /** Open the Seedr screen and add this magnet / .torrent link (after signing in if needed). */
    public static void open(Context c, String magnet) {
        if (!Auth.featureOn("seedr")) {
            UiKit.makeToast(c, T.t("Switched off by the admin"), Toast.LENGTH_LONG).show();
            return;
        }
        Intent i = new Intent(c, SeedrActivity.class);
        if (magnet != null) i.putExtra(EXTRA_MAGNET, magnet);
        if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private void toast(String s) {
        UiKit.makeToast(this, s, Toast.LENGTH_LONG).show();
    }

    // ================================================================== life cycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(PANEL);
        getWindow().setNavigationBarColor(BG);
        build();
        pendingMagnet = getIntent().getStringExtra(EXTRA_MAGNET);
        if (!SeedrApi.hasToken()) signIn();
        else afterSignIn();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        String m = i.getStringExtra(EXTRA_MAGNET);
        if (m == null) return;
        if (SeedrApi.hasToken()) add(m);
        else {
            pendingMagnet = m;
            signIn();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (backFromWebsite && SeedrApi.hasToken()) {   // added on seedr.cc: show it in Transfers
            backFromWebsite = false;
            showTab(1);
            refreshAccount();
        }
        main.removeCallbacks(transferTick);
        if (tab == 1) main.postDelayed(transferTick, 5000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(transferTick);
    }

    @Override
    public void onBackPressed() {
        if (tab == 0 && !path.isEmpty()) {
            path.remove(path.size() - 1);
            reload();
        } else if (tab != 0) {
            showTab(0);
        } else {
            super.onBackPressed();
        }
    }

    // ================================================================== layout

    private void build() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        frame.addView(root, new FrameLayout.LayoutParams(-1, -1));

        // ---- header
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setBackgroundColor(PANEL);
        head.setPadding(dp(8), dp(10), dp(8), dp(10));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(UiKit.darkButton(this, "arrow-left", 42, false, v -> onBackPressed()), new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(dp(10), 0, dp(6), 0);
        title = text(18, Color.WHITE, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        title.setText("Seedr");
        account = text(12, SUB, false);
        account.setSingleLine(true);
        account.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(title);
        names.addView(account);
        row.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(UiKit.darkButton(this, "plus", 42, true, v -> askMagnet()), new LinearLayout.LayoutParams(dp(42), dp(42)));
        View gap = new View(this);
        row.addView(gap, new LinearLayout.LayoutParams(dp(6), 1));
        row.addView(UiKit.darkButton(this, "settings", 42, false, v -> settings()), new LinearLayout.LayoutParams(dp(42), dp(42)));
        head.addView(row);
        quota = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        quota.setMax(1000);
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(-1, dp(4));
        qlp.setMargins(dp(8), dp(10), dp(8), 0);
        head.addView(quota, qlp);
        root.addView(head);

        // ---- tabs
        LinearLayout tabs = new LinearLayout(this);
        tabs.setBackgroundColor(PANEL);
        String[] names3 = {T.t("Files"), T.t("Transfers"), T.t("Search")};
        for (int i = 0; i < 3; i++) {
            final int k = i;
            TextView t = text(14, SUB, false);
            t.setText(names3[i]);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(12), 0, dp(12));
            t.setFocusable(true);
            t.setBackground(Ui.focusCard(this, 10));
            t.setOnClickListener(v -> showTab(k));
            tabViews[i] = t;
            tabs.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        }
        root.addView(tabs);

        // ---- list
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), dp(10), dp(12), dp(40));
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        busy = new ProgressBar(this);
        busy.setVisibility(View.GONE);
        frame.addView(busy, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));
        setContentView(frame);
        markTabs();
    }

    private void markTabs() {
        for (int i = 0; i < 3; i++) {
            tabViews[i].setTextColor(i == tab ? Ui.ACCENT : SUB);
            tabViews[i].setTypeface(i == tab ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
    }

    private void showTab(int k) {
        tab = k;
        markTabs();
        main.removeCallbacks(transferTick);
        reload();
    }

    private void loading(boolean on) {
        busy.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    private void message(String s) {
        list.removeAllViews();
        TextView t = text(15, SUB, false);
        t.setText(s);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(24), dp(48), dp(24), dp(24));
        list.addView(t);
    }

    // ================================================================== loading

    private void afterSignIn() {
        refreshAccount();
        showTab(tab);
        if (pendingMagnet != null) {
            String m = pendingMagnet;
            pendingMagnet = null;
            add(m);
        }
    }

    private void refreshAccount() {
        new Thread(() -> {
            try {
                SeedrApi.AccountInfo a = SeedrApi.getAccount();
                main.post(() -> {
                    String q = a.quota();
                    account.setText((a.username.isEmpty() ? a.email : a.username)
                            + (q.isEmpty() ? "" : "   ·   " + q) + (a.premium ? "   ·   ⭐" : ""));
                    quota.setProgress(a.spaceTotal > 0 ? (int) (1000L * a.spaceUsed / a.spaceTotal) : 0);
                });
            } catch (Exception e) {
                main.post(() -> account.setText("⚠ " + e.getMessage()));
            }
        }).start();
    }

    private void reload() {
        if (!SeedrApi.hasToken()) {
            message(T.t("Sign in to see your Seedr files."));
            return;
        }
        if (tab == 2) {
            searchView();
            return;
        }
        final int seq = ++loadSeq;
        final String id = tab == 0 && !path.isEmpty() ? path.get(path.size() - 1)[0] : "0";
        title.setText(tab == 1 ? T.t("Transfers") : path.isEmpty() ? "Seedr" : path.get(path.size() - 1)[1]);
        if (list.getChildCount() == 0 || tab == 0) loading(true);
        new Thread(() -> {
            try {
                List<SeedrApi.SeedrFile> items = SeedrApi.listFolder(id);
                main.post(() -> {
                    if (seq != loadSeq) return;
                    loading(false);
                    render(items);
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (seq != loadSeq) return;
                    loading(false);
                    message("⚠ " + e.getMessage());
                });
            }
        }).start();
    }

    private final Runnable transferTick = new Runnable() {
        @Override
        public void run() {
            if (tab != 1 || isFinishing()) return;
            reload();
        }
    };

    private void render(List<SeedrApi.SeedrFile> items) {
        list.removeAllViews();
        if (tab == 1) {
            List<SeedrApi.SeedrFile> t = new ArrayList<>();
            for (SeedrApi.SeedrFile f : items) if (f.isTorrent) t.add(f);
            if (t.isEmpty()) message(T.t("Nothing is downloading.\nTap ＋ to add a magnet link."));
            else for (SeedrApi.SeedrFile f : t) list.addView(transferRow(f), rowLp());
            main.removeCallbacks(transferTick);
            main.postDelayed(transferTick, 5000);           // live progress while this tab is open
            return;
        }
        if (!path.isEmpty()) list.addView(upRow(), rowLp());
        if (items.isEmpty()) {
            TextView t = text(15, SUB, false);
            t.setText(path.isEmpty() ? T.t("Your Seedr is empty.\nTap ＋ to add a magnet link.") : T.t("This folder is empty."));
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(24), dp(40), dp(24), dp(24));
            list.addView(t);
            return;
        }
        for (SeedrApi.SeedrFile f : items) list.addView(f.isTorrent ? transferRow(f) : fileRow(f), rowLp());
    }

    private LinearLayout.LayoutParams rowLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(4), 0, dp(4));
        return lp;
    }

    private View upRow() {
        LinearLayout r = baseRow();
        r.addView(UiKit.icon(this, "arrow-left", 22, Ui.ACCENT), new LinearLayout.LayoutParams(dp(36), dp(36)));
        TextView t = text(15, Ui.ACCENT, true);
        t.setText(path.size() > 1 ? path.get(path.size() - 2)[1] : T.t("All files"));
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(dp(12), 0, 0, 0);
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        r.setOnClickListener(v -> onBackPressed());
        return r;
    }

    private LinearLayout baseRow() {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(14), dp(12), dp(6), dp(12));
        r.setBackground(Ui.focusCard(this, 14));
        r.setFocusable(true);
        return r;
    }

    private static String iconFor(SeedrApi.SeedrFile f) {
        if (f.isFolder) return "folder";
        if (f.playVideo || Formats.isVideo(f.name)) return "film";
        if (f.playAudio || Formats.isAudio(f.name)) return "file-music";
        String n = f.name.toLowerCase(java.util.Locale.US);
        if (n.matches(".*\\.(jpg|jpeg|png|gif|webp|bmp)$")) return "image";
        return "download";
    }

    private View fileRow(final SeedrApi.SeedrFile f) {
        LinearLayout r = baseRow();
        int tint = f.isFolder ? 0xFFF5B942 : f.playable() ? Ui.ACCENT : 0xFFB0B8C8;
        r.addView(UiKit.icon(this, iconFor(f), 24, tint), new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), 0, dp(4), 0);
        TextView n = text(15, Color.WHITE, f.isFolder);
        n.setText(f.name);
        n.setMaxLines(2);
        n.setEllipsize(TextUtils.TruncateAt.END);
        TextView s = text(12, SUB, false);
        s.setText(f.subtitle());
        col.addView(n);
        col.addView(s);
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(UiKit.darkButton(this, "ellipsis-vertical", 38, false, v -> itemMenu(f)), new LinearLayout.LayoutParams(dp(38), dp(38)));
        r.setOnClickListener(v -> openItem(f));
        r.setOnLongClickListener(v -> {
            itemMenu(f);
            return true;
        });
        return r;
    }

    private View transferRow(final SeedrApi.SeedrFile f) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(14), dp(12), dp(8), dp(12));
        r.setBackground(Ui.card(this, PANEL, Ui.ACCENT, dp(14)));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(UiKit.icon(this, "cloud-download", 22, Ui.ACCENT), new LinearLayout.LayoutParams(dp(30), dp(30)));
        TextView n = text(14, Color.WHITE, true);
        n.setText(f.name);
        n.setMaxLines(2);
        n.setEllipsize(TextUtils.TruncateAt.END);
        n.setPadding(dp(10), 0, dp(4), 0);
        top.addView(n, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(UiKit.darkButton(this, "x", 36, false, v -> confirmDelete(f)), new LinearLayout.LayoutParams(dp(36), dp(36)));
        r.addView(top);
        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        try {
            pb.setProgress(Integer.parseInt(f.progress));
        } catch (Exception ignored) {
        }
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, dp(6));
        plp.setMargins(0, dp(8), dp(6), dp(6));
        r.addView(pb, plp);
        TextView s = text(12, SUB, false);
        s.setText(f.subtitle() + (f.size > 0 ? "   ·   " + f.displaySize() : ""));
        r.addView(s);
        return r;
    }

    // ================================================================== opening & playing

    private void openItem(final SeedrApi.SeedrFile f) {
        if (f.isTorrent) {
            showTab(1);
            return;
        }
        if (!f.isFolder) {
            fileActions(f);
            return;
        }
        // A finished torrent with one video inside is a folder in Seedr: go straight to the video.
        loading(true);
        new Thread(() -> {
            try {
                List<SeedrApi.SeedrFile> inside = SeedrApi.listFolder(f.id);
                SeedrApi.SeedrFile only = null;
                int playable = 0, folders = 0;
                for (SeedrApi.SeedrFile x : inside) {
                    if (x.isFolder) folders++;
                    if (x.playable()) {
                        playable++;
                        only = x;
                    }
                }
                final SeedrApi.SeedrFile single = playable == 1 && folders == 0 ? only : null;
                main.post(() -> {
                    loading(false);
                    if (single != null) {
                        fileActions(single);
                    } else {
                        path.add(new String[]{f.id, f.name});
                        title.setText(f.name);
                        render(inside);
                    }
                });
            } catch (Exception e) {
                main.post(() -> {
                    loading(false);
                    toast("⚠ " + e.getMessage());
                });
            }
        }).start();
    }

    /** What to do with a file: play here, send to the TV, download, copy the link. */
    private void fileActions(final SeedrApi.SeedrFile f) {
        final List<String> labels = new ArrayList<>();
        final List<Integer> ids = new ArrayList<>();
        boolean onTv = Ui.isTv(this);
        if (f.playable()) {
            labels.add("▶  " + T.t(onTv ? "Play" : "Play on this phone"));
            ids.add(0);
            if (!onTv) {                                     // on the TV itself "Play" already plays here
                labels.add("📺  " + T.t("Play on the TV"));
                ids.add(1);
            }
        }
        labels.add("⬇  " + T.t("Download to this phone"));
        ids.add(2);
        labels.add("📋  " + T.t("Copy link"));
        ids.add(3);
        labels.add("✏  " + T.t("Rename"));
        ids.add(4);
        labels.add("🗑  " + T.t("Delete"));
        ids.add(5);
        new UiDialog(this).setTitle(f.name).setItems(labels.toArray(new String[0]), (d, w) -> {
            final int act = ids.get(w);
            if (act == 4) rename(f);
            else if (act == 5) confirmDelete(f);
            else withLink(f, url -> useLink(act, f.name, url));
        }).show();
    }

    private interface LinkUser {
        void use(String url);
    }

    /** Seedr links are fetched fresh each time (they are signed and expire). */
    private void withLink(final SeedrApi.SeedrFile f, final LinkUser then) {
        loading(true);
        new Thread(() -> {
            try {
                String url = SeedrApi.fetchFileUrl(f.id);
                main.post(() -> {
                    loading(false);
                    then.use(url);
                });
            } catch (Exception e) {
                main.post(() -> {
                    loading(false);
                    toast("⚠ " + e.getMessage());
                });
            }
        }).start();
    }

    private void useLink(int act, String name, String url) {
        if (act == 0) {
            Store.Item it = new Store.Item(name, url, "");
            Store.addRecent(it);
            Remote.play(this, Collections.singletonList(it), 0);   // the same path every other screen uses
        } else if (act == 1) {
            sendToTv(name, url);
        } else if (act == 2) {
            download(name, url);
        } else {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText(name, url));
            toast("📋 " + T.t("Link copied"));
        }
    }

    private void sendToTv(final String name, final String url) {
        final String ip = Store.getStr("last_tv", "");
        if (ip.isEmpty()) {
            toast(T.t("Connect to your TV first (ZH Play home → pick the TV)"));
            return;
        }
        toast("📺 " + T.t("Sending to the TV…"));
        new Thread(() -> {
            String msg;
            try {
                HashMap<String, String> p = new HashMap<>();
                p.put("url", url);
                p.put("title", name);
                org.json.JSONObject r = TvClient.post(ip, "/play", p);
                msg = r.optString("msg", r.optBoolean("ok", true) ? T.t("Playing on the TV") : T.t("The TV could not play it"));
            } catch (TvClient.AuthNeeded e) {
                msg = T.t("Open the remote and enter the TV PIN first");
            } catch (Exception e) {
                msg = T.t("Cannot reach the TV — same Wi-Fi?");
            }
            final String m = msg;
            main.post(() -> toast(m));
        }).start();
    }

    private void download(String name, String url) {
        try {
            android.app.DownloadManager dm = (android.app.DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            android.app.DownloadManager.Request r = new android.app.DownloadManager.Request(android.net.Uri.parse(url));
            r.setTitle(name);
            r.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, name.replaceAll("[\\\\/:*?\"<>|]", "_"));
            dm.enqueue(r);
            toast("⬇ " + T.t("Downloading to Downloads — see the notification"));
        } catch (Exception e) {
            toast("⚠ " + e.getMessage());
        }
    }

    // ================================================================== manage

    private void itemMenu(final SeedrApi.SeedrFile f) {
        if (f.isTorrent) {
            confirmDelete(f);
            return;
        }
        if (!f.isFolder) {
            fileActions(f);
            return;
        }
        new UiDialog(this).setTitle(f.name).setItems(new String[]{
                "📂  " + T.t("Open"), "✏  " + T.t("Rename"), "🗑  " + T.t("Delete")}, (d, w) -> {
            if (w == 0) openItem(f);
            else if (w == 1) rename(f);
            else confirmDelete(f);
        }).show();
    }

    private void rename(final SeedrApi.SeedrFile f) {
        final EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setText(f.name);
        e.setSelectAllOnFocus(true);
        new UiDialog(this).setTitle(T.t("Rename")).setView(pad(e))
                .setPositiveButton(T.t("Save"), (d, w) -> {
                    final String nn = e.getText().toString().trim();
                    if (nn.isEmpty() || nn.equals(f.name)) return;
                    background(() -> {
                        if (f.isFolder) SeedrApi.renameFolder(f.id, nn);
                        else SeedrApi.renameFile(f.id, nn);
                    }, "✏ " + T.t("Renamed"));
                })
                .setNegativeButton(T.t("Cancel"), null).show();
    }

    private void confirmDelete(final SeedrApi.SeedrFile f) {
        new UiDialog(this).setTitle("🗑  " + T.t("Delete") + "?")
                .setMessage(f.name + "\n\n" + (f.isTorrent ? T.t("This stops the download.") : T.t("This frees its space in Seedr.")))
                .setPositiveButton(T.t("Delete"), (d, w) -> background(() -> {
                    if (f.isTorrent) SeedrApi.deleteTorrent(f.id);
                    else if (f.isFolder) SeedrApi.deleteFolder(f.id);
                    else SeedrApi.deleteFile(f.id);
                }, "🗑 " + T.t("Deleted")))
                .setNegativeButton(T.t("Cancel"), null).show();
    }

    private interface Job {
        void run() throws Exception;
    }

    private void background(final Job job, final String done) {
        loading(true);
        new Thread(() -> {
            String msg = done;
            try {
                job.run();
            } catch (Exception e) {
                msg = "⚠ " + e.getMessage();
            }
            final String m = msg;
            main.post(() -> {
                loading(false);
                toast(m);
                refreshAccount();
                reload();
            });
        }).start();
    }

    // ================================================================== adding

    private void askMagnet() {
        if (!SeedrApi.hasToken()) {
            signIn();
            return;
        }
        final EditText e = new EditText(this);
        e.setHint("magnet:?xt=…   /   https://…/file.torrent");
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence c = cm.getPrimaryClip().getItemAt(0).getText();
                if (c != null && (c.toString().trim().startsWith("magnet:") || Formats.isTorrent(c.toString().trim())))
                    e.setText(c.toString().trim());
            }
        } catch (Exception ignored) {
        }
        new UiDialog(this).setTitle("☁  " + T.t("Add to Seedr")).setView(pad(e))
                .setPositiveButton(T.t("Add"), (d, w) -> {
                    String m = e.getText().toString().trim();
                    if (!m.isEmpty()) add(m);
                })
                .setNegativeButton(T.t("Cancel"), null).show();
    }

    private void add(final String link) {
        if (!link.startsWith("magnet:") && !link.startsWith("http")) {
            toast(T.t("Paste a magnet link or a link to a .torrent file"));
            return;
        }
        loading(true);
        new Thread(() -> {
            SeedrApi.AddResult r;
            try {
                r = SeedrApi.addMagnet(link);
            } catch (Exception e) {
                r = new SeedrApi.AddResult();
                r.msg = "⚠ " + e.getMessage();
            }
            final SeedrApi.AddResult res = r;
            main.post(() -> {
                loading(false);
                if (res.ok) {
                    toast(res.msg);
                    refreshAccount();
                    showTab(1);
                } else if (res.website) {      // Seedr's app API no longer adds: the website does it
                    addOnWebsite(link);
                } else {
                    new UiDialog(this).setTitle("☁ Seedr").setMessage(res.msg)
                            .setPositiveButton(T.t("Add on seedr.cc"), (d, w) -> addOnWebsite(link))
                            .setNegativeButton(T.t("Close"), null).show();
                    reload();
                }
            });
        }).start();
    }

    /** Seedr's website always accepts links: ZH Browser opens seedr.cc and adds it there. */
    private void addOnWebsite(String link) {
        startActivity(new Intent(this, BrowserActivity.class).putExtra(BrowserActivity.EXTRA_SEEDR_ADD, link));
        backFromWebsite = true;
    }

    private boolean backFromWebsite;

    // ================================================================== search

    private void searchView() {
        title.setText(T.t("Search"));
        list.removeAllViews();
        final EditText q = new EditText(this);
        q.setSingleLine(true);
        q.setHint(T.t("Search your Seedr files…"));
        q.setText(lastQuery);
        q.setTextColor(Color.WHITE);
        q.setHintTextColor(SUB);
        q.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        q.setBackground(Ui.card(this, PANEL, 0x33FFFFFF, dp(12)));
        q.setPadding(dp(14), dp(12), dp(14), dp(12));
        list.addView(q, rowLp());
        final LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        list.addView(results);
        q.setOnEditorActionListener((v, id, ev) -> {
            boolean go = id == EditorInfo.IME_ACTION_SEARCH
                    || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN);
            if (!go) return false;
            final String s = q.getText().toString().trim();
            if (s.isEmpty()) return true;
            lastQuery = s;
            loading(true);
            new Thread(() -> {
                try {
                    List<SeedrApi.SeedrFile> found = SeedrApi.search(s);
                    main.post(() -> {
                        loading(false);
                        results.removeAllViews();
                        if (found.isEmpty()) {
                            TextView t = text(14, SUB, false);
                            t.setText(T.t("Nothing found for") + " “" + s + "”");
                            t.setPadding(dp(8), dp(20), dp(8), 0);
                            results.addView(t);
                        }
                        for (SeedrApi.SeedrFile f : found) results.addView(fileRow(f), rowLp());
                    });
                } catch (Exception e) {
                    main.post(() -> {
                        loading(false);
                        toast("⚠ " + e.getMessage());
                    });
                }
            }).start();
            return true;
        });
        q.requestFocus();
    }

    // ================================================================== sign in

    private View pad(View v) {
        FrameLayout f = new FrameLayout(this);
        f.setPadding(dp(20), dp(8), dp(20), 0);
        f.addView(v);
        return f;
    }

    private void signIn() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(6), dp(20), 0);
        TextView info = text(13, SUB, false);
        info.setText(T.t("Sign in with your Seedr account. ZH Play keeps you signed in.")
                + (Ui.isTv(this) ? "\n\n" + T.t("Easier on a TV: on your phone open Seedr → ⚙ → “Sign in the TV with this account”.") : ""));
        box.addView(info);
        final EditText mail = new EditText(this);
        mail.setHint(T.t("E-mail"));
        mail.setSingleLine(true);
        mail.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        mail.setText(SeedrApi.email());
        box.addView(mail);
        final EditText pw = new EditText(this);
        pw.setHint(T.t("Password"));
        pw.setSingleLine(true);
        pw.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(pw);
        new UiDialog(this).setTitle("☁  " + T.t("Sign in to Seedr")).setView(box)
                .setPositiveButton(T.t("Sign in"), (d, w) -> {
                    final String em = mail.getText().toString().trim(), p = pw.getText().toString();
                    if (em.isEmpty() || p.isEmpty()) {
                        toast(T.t("Enter your e-mail and password"));
                        return;
                    }
                    loading(true);
                    new Thread(() -> {
                        try {
                            SeedrApi.login(em, p);
                            main.post(() -> {
                                loading(false);
                                toast("✅ " + T.t("Signed in to Seedr"));
                                afterSignIn();
                            });
                        } catch (Exception e) {
                            main.post(() -> {
                                loading(false);
                                toast("⚠ " + e.getMessage());
                                message(T.t("Not signed in.") + "\n\n" + T.t("Tap ⚙ to sign in."));
                            });
                        }
                    }).start();
                })
                .setNeutralButton(T.t("Sign in with a code"), (d, w) -> deviceSignIn())
                .setNegativeButton(T.t("Cancel"), (d, w) -> {
                    if (!SeedrApi.hasToken()) message(T.t("Not signed in.") + "\n\n" + T.t("Tap ⚙ to sign in."));
                }).show();
    }

    /** For when typing a password is awkward (TV): approve a short code on seedr.cc/devices. */
    private void deviceSignIn() {
        loading(true);
        new Thread(() -> {
            try {
                final SeedrApi.DeviceCode dc = SeedrApi.startDeviceFlow();
                main.post(() -> {
                    loading(false);
                    showDeviceCode(dc);
                });
            } catch (Exception e) {
                main.post(() -> {
                    loading(false);
                    toast("⚠ " + e.getMessage());
                });
            }
        }).start();
    }

    private void showDeviceCode(final SeedrApi.DeviceCode dc) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(6), dp(20), 0);
        TextView a = text(14, SUB, false);
        a.setText(T.t("On any phone or computer open") + "\n" + dc.verifyUrl + "\n" + T.t("and enter this code:"));
        box.addView(a);
        TextView code = text(34, Ui.ACCENT, true);
        code.setText(dc.userCode);
        code.setGravity(Gravity.CENTER);
        code.setLetterSpacing(0.12f);
        code.setPadding(0, dp(14), 0, dp(14));
        code.setTextIsSelectable(true);
        box.addView(code);
        TextView b = text(13, SUB, false);
        b.setText(T.t("Waiting for you to approve it…"));
        box.addView(b);
        final boolean[] done = {false};
        final android.app.Dialog dlg = new UiDialog(this).setTitle("☁  " + T.t("Sign in with a code")).setView(box)
                .setNegativeButton(T.t("Cancel"), (d, w) -> done[0] = true).show();
        final long until = System.currentTimeMillis() + dc.expiresIn * 1000L;
        final Runnable[] poll = new Runnable[1];
        poll[0] = () -> {
            if (done[0] || isFinishing()) return;
            if (System.currentTimeMillis() > until) {
                done[0] = true;
                dlg.dismiss();
                toast(T.t("The code expired — try again"));
                return;
            }
            new Thread(() -> {
                boolean ok = false;
                try {
                    ok = SeedrApi.pollDeviceFlow(dc.deviceCode);
                } catch (Exception ignored) {
                }
                final boolean fOk = ok;
                main.post(() -> {
                    if (done[0]) return;
                    if (fOk) {
                        done[0] = true;
                        dlg.dismiss();
                        toast("✅ " + T.t("Signed in to Seedr"));
                        afterSignIn();
                    } else {
                        main.postDelayed(poll[0], dc.interval * 1000L);
                    }
                });
            }).start();
        };
        main.postDelayed(poll[0], dc.interval * 1000L);
    }

    private void settings() {
        final boolean in = SeedrApi.hasToken();
        List<String> l = new ArrayList<>();
        if (in) {
            l.add("🔄  " + T.t("Refresh"));
            if (!Ui.isTv(this) && !Store.getStr("last_tv", "").isEmpty()) l.add("📺  " + T.t("Sign in the TV with this account"));
            l.add("🌐  " + T.t("Open seedr.cc"));
            l.add("🚪  " + T.t("Sign out"));
        } else {
            l.add("🔑  " + T.t("Sign in"));
            l.add("🌐  " + T.t("Open seedr.cc"));
        }
        new UiDialog(this).setTitle("Seedr" + (SeedrApi.email().isEmpty() ? "" : " · " + SeedrApi.email()))
                .setItems(l.toArray(new String[0]), (d, w) -> {
                    String pick = l.get(w);                          // chosen by label (the list depends on the device)
                    if (pick.startsWith("📺")) {
                        signInTv();
                    } else if (pick.startsWith("🔑")) {
                        signIn();
                    } else if (pick.startsWith("🔄")) {
                        refreshAccount();
                        reload();
                    } else if (pick.startsWith("🌐")) {
                        startActivity(new Intent(this, BrowserActivity.class).putExtra(BrowserActivity.EXTRA_URL, "https://www.seedr.cc/files"));
                    } else {
                        SeedrApi.clearAll();
                        path.clear();
                        account.setText("");
                        quota.setProgress(0);
                        message(T.t("Signed out.") + "\n\n" + T.t("Tap ⚙ to sign in."));
                    }
                }).show();
    }

    /** Phone: signs the connected TV into this Seedr account (no typing on the TV). */
    private void signInTv() {
        final String tv = Store.getStr("last_tv", "");
        toast("📺 " + T.t("Signing the TV in…"));
        new Thread(() -> {
            String msg;
            try {
                org.json.JSONObject r = TvClient.post(tv, "/seedr/login", new HashMap<>(SeedrApi.session()));
                msg = (r.optBoolean("ok", true) ? "✓ " : "⚠ ") + r.optString("msg", T.t("Done"));
            } catch (TvClient.AuthNeeded e) {
                msg = "⚠ " + T.t("Open the remote and enter the TV PIN first");
            } catch (Exception e) {
                msg = "⚠ " + T.t("Cannot reach the TV — same Wi-Fi?");
            }
            final String m = msg;
            main.post(() -> toast(m));
        }).start();
    }
}
