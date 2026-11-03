package com.mytv.player;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Stack;

/**
 * Seedr file manager inside ZH Play:
 *  • Paste your Personal Access Token once (from seedr.cc/api/v0.1/console/tokens)
 *  • Browse your Seedr cloud folders
 *  • Add a magnet / URL straight to Seedr with live progress
 *  • Play a Seedr file directly in ZH Play (streams from Seedr's CDN)
 *  • Delete, rename files and folders
 *  • Search across your whole account
 */
public class SeedrActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout list;
    private TextView titleView, statusView;
    private ImageView backBtn, searchBtn;
    private final Stack<String[]> folderStack = new Stack<>(); // {id, name}
    private boolean loading;

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private TextView tv(float sp, int color, boolean bold) {
        TextView t = new TextView(this); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD); return t;
    }

    public static void open(Context c) {
        c.startActivity(new Intent(c, SeedrActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @Override
    protected void onCreate(android.os.Bundle b) {
        super.onCreate(b);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        getWindow().setStatusBarColor(0xFF0A0A0F);
        buildUi();
        if (!SeedrApi.hasToken()) showSetupToken();
        else loadFolder("0", "Seedr");
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0A0A0F);

        // ---- top bar ----
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(0xFF12121A);
        top.setPadding(dp(8), dp(32), dp(8), dp(8));

        backBtn = UiKit.darkButton(this, "arrow-left", 44, false, v -> {
            if (folderStack.size() > 1) { folderStack.pop(); String[] f = folderStack.peek(); folderStack.pop(); loadFolder(f[0], f[1]); }
            else finish();
        });
        top.addView(backBtn, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tlp.setMargins(dp(8), 0, dp(8), 0);
        titleView = tv(17, Color.WHITE, true); titleView.setSingleLine(true); titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.addView(titleView, tlp);

        searchBtn = UiKit.darkButton(this, "search", 44, false, v -> showSearch());
        top.addView(searchBtn, new LinearLayout.LayoutParams(dp(44), dp(44)));
        ImageView addBtn = UiKit.darkButton(this, "plus", 44, false, v -> showAddMagnet());
        top.addView(addBtn, new LinearLayout.LayoutParams(dp(44), dp(44)));
        ImageView settingsBtn = UiKit.darkButton(this, "settings", 44, false, v -> showSettings());
        top.addView(settingsBtn, new LinearLayout.LayoutParams(dp(44), dp(44)));

        root.addView(top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        // ---- status bar (quota, messages) ----
        statusView = tv(12, 0x88FFFFFF, false);
        statusView.setPadding(dp(20), dp(4), dp(20), dp(4));
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        slp.topMargin = dp(84);
        root.addView(statusView, slp);

        // ---- file list ----
        ScrollView scroll = new ScrollView(this);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), dp(4), dp(12), dp(80));
        scroll.addView(list);
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        llp.topMargin = dp(100);
        root.addView(scroll, llp);

        setContentView(root);
    }

    // ------------------------------------------------------------------ folder loading

    private void loadFolder(String id, String name) {
        if (loading) return;
        loading = true;
        folderStack.push(new String[]{id, name});
        titleView.setText("☁  " + name);
        list.removeAllViews();
        showLoading("Loading…");
        new Thread(() -> {
            try {
                SeedrApi.AccountInfo acc = id.equals("0") ? SeedrApi.getAccount() : null;
                List<SeedrApi.SeedrFile> files = SeedrApi.listFolder(id);
                main.post(() -> {
                    loading = false;
                    hideLoading();
                    if (acc != null) statusView.setText("👤 " + acc.username + "   💾 " + acc.quota());
                    renderFiles(files, id);
                });
            } catch (Exception e) {
                main.post(() -> { loading = false; hideLoading(); showError(e.getMessage()); });
            }
        }).start();
    }

    private void renderFiles(List<SeedrApi.SeedrFile> files, String parentId) {
        list.removeAllViews();
        if (files.isEmpty()) {
            TextView empty = tv(14, 0x66FFFFFF, false);
            empty.setText("This folder is empty.\nTap ＋ to add a magnet or torrent.");
            empty.setGravity(Gravity.CENTER); empty.setPadding(dp(32), dp(48), dp(32), dp(48));
            list.addView(empty); return;
        }
        for (SeedrApi.SeedrFile f : files) addFileRow(f);
    }

    private void addFileRow(SeedrApi.SeedrFile f) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(8), dp(12));
        row.setBackground(Ui.focusCard(this, 14));

        // icon
        String icon = f.isFolder ? "folder" : f.isTorrent ? "download" : iconFor(f.ext);
        ImageView ic = UiKit.icon(this, icon, 22, f.isFolder ? 0xFFFFAA00 : f.isTorrent ? Ui.ACCENT2 : 0xFFCCCCCC);
        ic.setPadding(0, 0, dp(12), 0);
        row.addView(ic, new LinearLayout.LayoutParams(dp(36), dp(36)));

        // name + size
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView name = tv(14, Color.WHITE, f.isFolder); name.setText(f.name);
        name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(name);
        if (!f.displaySize().isEmpty() || !f.ext.isEmpty()) {
            TextView sub = tv(11, 0x88FFFFFF, false);
            sub.setText((f.ext.isEmpty() ? "" : f.ext.toUpperCase(Locale.US) + "  ") + f.displaySize());
            col.addView(sub);
        }
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        // tap = open/play
        row.setOnClickListener(v -> onFileTap(f));
        // long-press = options
        row.setOnLongClickListener(v -> { fileMenu(f); return true; });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(3), 0, dp(3));
        list.addView(row, lp);
    }

    private void onFileTap(SeedrApi.SeedrFile f) {
        if (f.isFolder) { loadFolder(f.id, f.name); return; }
        if (f.isTorrent) { pollProgress(f); return; }
        // fetch the streaming URL then play
        showLoading("Getting stream URL…");
        new Thread(() -> {
            try {
                String url = SeedrApi.fetchFileUrl(f.id);
                main.post(() -> {
                    hideLoading();
                    playOrOffer(url, f.name);
                });
            } catch (Exception e) {
                main.post(() -> { hideLoading(); showError(e.getMessage()); });
            }
        }).start();
    }

    private void playOrOffer(String url, String name) {
        // is it a video/audio? play directly. otherwise offer download.
        String low = name.toLowerCase(Locale.US);
        boolean video = low.matches(".*\\.(mp4|mkv|avi|mov|m4v|webm|ts|m2ts|flv|3gp|wmv)$");
        boolean audio = low.matches(".*\\.(mp3|m4a|aac|flac|ogg|opus|wav)$");
        if (video || audio) {
            Intent i = new Intent(this, PlayerActivity.class)
                    .putExtra("url", url).putExtra("title", name);
            startActivity(i);
        } else {
            // open in the browser (streams via Seedr CDN)
            startActivity(new Intent(this, BrowserActivity.class).putExtra(BrowserActivity.EXTRA_URL, url));
        }
    }

    // ------------------------------------------------------------------ context menu

    private void fileMenu(final SeedrApi.SeedrFile f) {
        List<String> items = new ArrayList<>();
        if (!f.isFolder && !f.isTorrent) items.add("▶  " + T.t("Play / Stream"));
        if (f.isFolder) items.add("📂  " + T.t("Open folder"));
        items.add("✏  " + T.t("Rename"));
        items.add("🗑  " + T.t("Delete"));
        new UiDialog(this).setTitle(f.name).setItems(items.toArray(new String[0]), (d, w) -> {
            String choice = items.get(w);
            if (choice.contains("Play") || choice.contains("Stream")) onFileTap(f);
            else if (choice.contains("Open")) onFileTap(f);
            else if (choice.contains("Rename")) showRename(f);
            else if (choice.contains("Delete")) confirmDelete(f);
        }).show();
    }

    private void showRename(SeedrApi.SeedrFile f) {
        EditText et = new EditText(this); et.setText(f.name); et.setSingleLine(true);
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), 0); box.addView(et);
        new UiDialog(this).setTitle(T.t("Rename")).setView(box)
            .setPositiveButton(T.t("Save"), (d, w) -> {
                String newName = et.getText().toString().trim();
                if (newName.isEmpty()) return;
                new Thread(() -> {
                    try {
                        if (f.isFolder) SeedrApi.renameFolder(f.id, newName);
                        else SeedrApi.renameFile(f.id, newName);
                        main.post(() -> refreshCurrent());
                    } catch (Exception e) { main.post(() -> showError(e.getMessage())); }
                }).start();
            }).setNegativeButton(T.t("Cancel"), null).show();
    }

    private void confirmDelete(SeedrApi.SeedrFile f) {
        new UiDialog(this).setTitle(T.t("Delete") + "?").setMessage(f.name)
            .setPositiveButton(T.t("Delete"), (d, w) -> new Thread(() -> {
                try {
                    if (f.isTorrent) SeedrApi.deleteTorrent(f.id);
                    else if (f.isFolder) SeedrApi.deleteFolder(f.id);
                    else SeedrApi.deleteFile(f.id);
                    main.post(() -> refreshCurrent());
                } catch (Exception e) { main.post(() -> showError(e.getMessage())); }
            }).start())
            .setNegativeButton(T.t("Cancel"), null).show();
    }

    // ------------------------------------------------------------------ add magnet

    private void showAddMagnet() {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), 0);
        EditText et = new EditText(this);
        et.setHint(T.t("Magnet link or torrent URL"));
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        // paste from clipboard
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip()) {
                CharSequence clip = cm.getPrimaryClip().getItemAt(0).getText();
                if (clip != null && (clip.toString().startsWith("magnet:") || clip.toString().endsWith(".torrent")))
                    et.setText(clip.toString().trim());
            }
        } catch (Exception ignored) {}
        box.addView(et);
        new UiDialog(this).setTitle("☁  " + T.t("Add to Seedr")).setView(box)
            .setPositiveButton(T.t("Add"), (d, w) -> {
                String link = et.getText().toString().trim();
                if (link.isEmpty()) return;
                sendToSeedrApi(link);
            })
            .setNegativeButton(T.t("Cancel"), null).show();
    }

    /** Send a magnet to Seedr via the API and show a live progress card. */
    public void sendToSeedrApi(final String magnet) {
        showLoading(T.t("Sending to Seedr…"));
        new Thread(() -> {
            try {
                SeedrApi.AddResult res = SeedrApi.addMagnet(magnet);
                main.post(() -> {
                    hideLoading();
                    if (!res.ok) { showError(res.msg); return; }
                    Toast.makeText(this, "☁ " + T.t("Added to Seedr — downloading in the cloud"), Toast.LENGTH_LONG).show();
                    if (!res.progressUrl.isEmpty()) addProgressCard(res.progressUrl);
                    refreshCurrent();
                });
            } catch (Exception e) {
                main.post(() -> { hideLoading(); showError(e.getMessage()); });
            }
        }).start();
    }

    /** Live progress card at the top of the list — polls every 3 s. */
    private void addProgressCard(final String progressUrl) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        card.setBackground(Ui.card(this, 0xFF1A1A28, Ui.ACCENT, 14));
        TextView name = tv(13, Color.WHITE, true); name.setText("⬇ " + T.t("Downloading in Seedr…"));
        card.addView(name);
        android.widget.ProgressBar bar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100); bar.setProgress(0);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
        blp.setMargins(0, dp(6), 0, dp(4));
        card.addView(bar, blp);
        TextView sub = tv(11, 0xAAFFFFFF, false); sub.setText(T.t("Starting…"));
        card.addView(sub);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(0, 0, 0, dp(8));
        list.addView(card, 0, clp);

        // poll every 3 seconds
        Runnable[] poll = {null};
        poll[0] = new Runnable() {
            public void run() {
                new Thread(() -> {
                    try {
                        SeedrApi.TorrentProgress p = SeedrApi.getProgress(progressUrl);
                        main.post(() -> {
                            bar.setProgress(p.progress);
                            name.setText("⬇ " + (p.name.isEmpty() ? T.t("Downloading in Seedr…") : p.name));
                            sub.setText(p.progress + "%  •  ↓ " + fmtSpeed(p.dlRate)
                                    + "  •  " + p.seeders + " seeds / " + p.peers + " peers");
                            if (!p.done) main.postDelayed(poll[0], 3000);
                            else {
                                card.setBackgroundColor(0xFF0D2818);
                                name.setText("✅ " + T.t("Download complete!"));
                                sub.setText(T.t("Open the folder to play"));
                                main.postDelayed(() -> refreshCurrent(), 1500);
                            }
                        });
                    } catch (Exception ignored) {}
                }).start();
            }
        };
        main.postDelayed(poll[0], 3000);
    }

    private void pollProgress(SeedrApi.SeedrFile f) {
        // called when tapping an active torrent row; just show a dialog with live progress
        if (f.url.isEmpty()) { Toast.makeText(this, T.t("No progress info available"), Toast.LENGTH_SHORT).show(); return; }
        addProgressCard(f.url);
    }

    // ------------------------------------------------------------------ search

    private void showSearch() {
        EditText et = new EditText(this); et.setHint(T.t("Search your Seedr files…")); et.setSingleLine(true);
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), 0); box.addView(et);
        new UiDialog(this).setTitle("🔍 " + T.t("Search Seedr")).setView(box)
            .setPositiveButton(T.t("Search"), (d, w) -> {
                String q = et.getText().toString().trim();
                if (q.isEmpty()) return;
                showLoading(T.t("Searching…"));
                new Thread(() -> {
                    try {
                        List<SeedrApi.SeedrFile> results = SeedrApi.search(q);
                        main.post(() -> {
                            hideLoading();
                            titleView.setText("🔍 " + q);
                            list.removeAllViews();
                            if (results.isEmpty()) {
                                TextView empty = tv(14, 0x66FFFFFF, false);
                                empty.setText(T.t("No results found"));
                                empty.setPadding(dp(32), dp(48), dp(32), dp(48));
                                list.addView(empty);
                            } else for (SeedrApi.SeedrFile f : results) addFileRow(f);
                        });
                    } catch (Exception e) { main.post(() -> { hideLoading(); showError(e.getMessage()); }); }
                }).start();
            }).setNegativeButton(T.t("Cancel"), null).show();
    }

    // ------------------------------------------------------------------ settings

    private void showSettings() {
        List<String> items = new ArrayList<>();
        items.add("🔑  " + T.t("Change API Token"));
        items.add("🔄  " + T.t("Refresh"));
        items.add("🚪  " + T.t("Sign out"));
        new UiDialog(this).setTitle("Seedr").setItems(items.toArray(new String[0]), (d, w) -> {
            if (w == 0) showSetupToken();
            else if (w == 1) refreshCurrent();
            else { SeedrApi.clearToken(); finish(); }
        }).show();
    }

    private void showSetupToken() {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), 0);
        TextView msg = tv(13, 0xCCFFFFFF, false);
        msg.setText("1. Go to seedr.cc/api/v0.1/console/tokens\n2. Create a token → choose \"Read & Add Torrents\"\n3. Paste the token below");
        msg.setPadding(0, 0, 0, dp(12));
        box.addView(msg);
        EditText et = new EditText(this); et.setHint(T.t("Paste your Seedr API token here")); et.setSingleLine(true);
        if (SeedrApi.hasToken()) et.setText(SeedrApi.token());
        box.addView(et);
        new UiDialog(this).setTitle("☁  " + T.t("Connect to Seedr")).setView(box)
            .setPositiveButton(T.t("Connect"), (d, w) -> {
                String tok = et.getText().toString().trim();
                if (tok.isEmpty()) return;
                SeedrApi.saveToken(tok);
                showLoading(T.t("Verifying…"));
                new Thread(() -> {
                    try {
                        SeedrApi.AccountInfo acc = SeedrApi.getAccount();
                        main.post(() -> {
                            hideLoading();
                            Toast.makeText(this, "✅ " + T.t("Connected as") + " " + acc.username, Toast.LENGTH_LONG).show();
                            folderStack.clear();
                            loadFolder("0", "Seedr");
                        });
                    } catch (Exception e) {
                        main.post(() -> { hideLoading(); SeedrApi.clearToken(); showError(T.t("Invalid token") + ": " + e.getMessage()); showSetupToken(); });
                    }
                }).start();
            })
            .setNeutralButton(T.t("Open Seedr API page"), (d, w) -> startActivity(new Intent(this, BrowserActivity.class).putExtra(BrowserActivity.EXTRA_URL, "https://seedr.cc/api/v0.1/console/tokens").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)))
            .setNegativeButton(T.t("Cancel"), null).show();
    }

    // ------------------------------------------------------------------ helpers

    private void refreshCurrent() {
        if (!folderStack.isEmpty()) {
            String[] f = folderStack.pop();
            loadFolder(f[0], f[1]);
        }
    }

    private void showLoading(String msg) {
        statusView.setText("⏳ " + msg);
    }
    private void hideLoading() {
        statusView.setText("");
    }
    private void showError(String msg) {
        statusView.setText("⚠ " + msg);
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private static String fmtSpeed(long bps) {
        if (bps > 1_048_576) return String.format(Locale.US, "%.1f MB/s", bps / 1_048_576.0);
        if (bps > 1024)      return String.format(Locale.US, "%.0f KB/s", bps / 1024.0);
        return bps + " B/s";
    }

    private static String iconFor(String ext) {
        if (ext == null) return "file";
        switch (ext.toLowerCase(Locale.US)) {
            case "mp4": case "mkv": case "avi": case "mov": case "m4v": case "webm":
            case "ts": case "m2ts": case "flv": return "video";
            case "mp3": case "m4a": case "aac": case "flac": case "ogg": case "opus": case "wav":
                return "music";
            case "pdf": return "file-text";
            case "zip": case "rar": case "7z": return "archive";
            default: return "file";
        }
    }

    @Override
    public void onBackPressed() {
        if (folderStack.size() > 1) { folderStack.pop(); String[] f = folderStack.peek(); folderStack.pop(); loadFolder(f[0], f[1]); }
        else super.onBackPressed();
    }
}
