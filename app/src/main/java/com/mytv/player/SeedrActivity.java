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
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Stack;

/**
 * Full Seedr file manager — same layout as the Seedr app:
 *  Header: username · quota bar
 *  Tabs: Files | Transfers | Search
 *  File list: icon · name · size/progress · ⋮ menu
 *  Actions: play, stream, delete, rename, add magnet
 */
public class SeedrActivity extends Activity {

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Stack<String[]> folderStack = new Stack<>(); // {id, name}

    // views
    private TextView titleView, usernameView, quotaLabel;
    private ProgressBar quotaBar;
    private LinearLayout fileList;
    private ScrollView scroll;
    private View tabFiles, tabTransfers, tabSearch;
    private LinearLayout loadingOverlay;
    private int currentTab = 0; // 0=Files 1=Transfers 2=Search

    // polling
    private final List<Runnable> pollers = new ArrayList<>();

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private TextView tv(float sp, int color, boolean bold) {
        TextView t = new TextView(this); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD); return t;
    }

    public static void open(Context c) {
        c.startActivity(new Intent(c, SeedrActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @Override protected void onCreate(android.os.Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(0xFF0C1120);
        buildUi();
        if (!SeedrApi.hasToken() && !SeedrApi.hasPat()) showSetup();
        else loadAccount();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        for (Runnable r : pollers) main.removeCallbacks(r);
    }

    // ================================================================== UI BUILD

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0C1120);

        // ---- header ----
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundColor(0xFF111827);
        header.setPadding(dp(16), dp(36), dp(16), dp(12));

        LinearLayout topRow = new LinearLayout(this);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        ImageView backBtn = UiKit.darkButton(this,"arrow-left",40,false,v->onBackPressed());
        topRow.addView(backBtn, lp(dp(40),dp(40)));

        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT,1);
        tlp.setMargins(dp(10),0,0,0);
        LinearLayout titles = new LinearLayout(this); titles.setOrientation(LinearLayout.VERTICAL);
        titleView    = tv(17, Color.WHITE, true);  titleView.setText("☁  Seedr");
        usernameView = tv(12, 0x88FFFFFF, false);
        titles.addView(titleView); titles.addView(usernameView);
        topRow.addView(titles, tlp);

        ImageView searchBtn = UiKit.darkButton(this,"search",40,false,v->{currentTab=2;refreshTab();});
        ImageView addBtn    = UiKit.darkButton(this,"plus",  40,false,v->showAddMagnet());
        ImageView settBtn   = UiKit.darkButton(this,"settings",40,false,v->showSettings());
        topRow.addView(searchBtn, lp(dp(40),dp(40)));
        topRow.addView(addBtn,    lp(dp(40),dp(40)));
        topRow.addView(settBtn,   lp(dp(40),dp(40)));
        header.addView(topRow);

        // quota bar
        LinearLayout qRow = new LinearLayout(this);
        qRow.setOrientation(LinearLayout.VERTICAL);
        qRow.setPadding(0,dp(10),0,0);
        quotaLabel = tv(11, 0x88FFFFFF, false);
        quotaBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        quotaBar.setMax(1000); quotaBar.setProgress(0);
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
        qlp.setMargins(0,dp(4),0,0);
        qRow.addView(quotaLabel);
        qRow.addView(quotaBar, qlp);
        header.addView(qRow);

        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        root.addView(header, hlp);

        // ---- tabs ----
        LinearLayout tabs = new LinearLayout(this);
        tabs.setBackgroundColor(0xFF111827);

        tabFiles      = makeTab("Files",     0);
        tabTransfers  = makeTab("Transfers", 1);
        tabSearch     = makeTab("Search",    2);
        tabs.addView(tabFiles,     new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT,1));
        tabs.addView(tabTransfers, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT,1));
        tabs.addView(tabSearch,    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT,1));

        FrameLayout.LayoutParams tblp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        tblp.topMargin = dp(116);
        root.addView(tabs, tblp);

        // ---- file list ----
        scroll = new ScrollView(this);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        fileList = new LinearLayout(this);
        fileList.setOrientation(LinearLayout.VERTICAL);
        fileList.setPadding(dp(12), dp(8), dp(12), dp(80));
        scroll.addView(fileList);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        flp.topMargin = dp(158);
        root.addView(scroll, flp);

        // ---- loading overlay ----
        loadingOverlay = new LinearLayout(this);
        loadingOverlay.setGravity(Gravity.CENTER);
        loadingOverlay.setBackgroundColor(0xAA0C1120);
        loadingOverlay.setVisibility(View.GONE);
        ProgressBar spinner = new ProgressBar(this);
        loadingOverlay.addView(spinner);
        root.addView(loadingOverlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
        updateTabHighlight();
    }

    private View makeTab(String label, int idx) {
        TextView t = tv(13, 0xAAFFFFFF, false);
        t.setText(label); t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(10), 0, dp(10));
        t.setOnClickListener(v -> { currentTab = idx; folderStack.clear(); refreshTab(); });
        return t;
    }

    private void updateTabHighlight() {
        View[] tabs = {tabFiles, tabTransfers, tabSearch};
        for (int i = 0; i < tabs.length; i++) {
            if (tabs[i] instanceof TextView) {
                ((TextView) tabs[i]).setTextColor(i == currentTab ? Ui.ACCENT : 0xAAFFFFFF);
                ((TextView) tabs[i]).setTypeface(i == currentTab ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            }
        }
    }

    // ================================================================== DATA LOADING

    private void loadAccount() {
        new Thread(() -> {
            try {
                SeedrApi.AccountInfo acc = SeedrApi.getAccount();
                main.post(() -> {
                    usernameView.setText(acc.username + (acc.email.isEmpty() ? "" : "  ·  " + acc.email));
                    quotaLabel.setText("Storage: " + acc.quota());
                    if (acc.spaceTotal > 0)
                        quotaBar.setProgress((int)(1000L * acc.spaceUsed / acc.spaceTotal));
                    refreshTab();
                });
            } catch (Exception e) {
                main.post(() -> { showError(e.getMessage()); showSetup(); });
            }
        }).start();
    }

    private void refreshTab() {
        updateTabHighlight();
        if (currentTab == 0) loadFolder(folderStack.isEmpty() ? "0" : folderStack.peek()[0],
                                        folderStack.isEmpty() ? "My Files" : folderStack.peek()[1]);
        else if (currentTab == 1) loadTransfers();
        else showSearchBar();
    }

    private void loadFolder(String id, String name) {
        if (folderStack.isEmpty() || !folderStack.peek()[0].equals(id))
            folderStack.push(new String[]{id, name});
        titleView.setText("☁  " + name);
        showLoading(true);
        new Thread(() -> {
            try {
                List<SeedrApi.SeedrFile> files = SeedrApi.listFolder(id);
                main.post(() -> { showLoading(false); renderFiles(files, id); });
            } catch (Exception e) {
                main.post(() -> { showLoading(false); showError(e.getMessage()); });
            }
        }).start();
    }

    private void loadTransfers() {
        titleView.setText("☁  Transfers");
        showLoading(true);
        new Thread(() -> {
            try {
                // list_contents on root returns active torrents too
                List<SeedrApi.SeedrFile> all = SeedrApi.listFolder("0");
                List<SeedrApi.SeedrFile> active = new ArrayList<>();
                for (SeedrApi.SeedrFile f : all) if (f.isTorrent) active.add(f);
                main.post(() -> {
                    showLoading(false);
                    fileList.removeAllViews();
                    if (active.isEmpty()) {
                        addEmptyLabel("No active transfers.\nTap ＋ to add a magnet link.");
                    } else {
                        for (SeedrApi.SeedrFile f : active) addTransferRow(f);
                    }
                });
            } catch (Exception e) {
                main.post(() -> { showLoading(false); showError(e.getMessage()); });
            }
        }).start();
    }

    // ================================================================== RENDER

    private void renderFiles(List<SeedrApi.SeedrFile> files, String parentId) {
        fileList.removeAllViews();
        // breadcrumb back row
        if (!folderStack.isEmpty() && folderStack.size() > 1) {
            addNavRow("← " + (folderStack.size() > 2 ? folderStack.get(folderStack.size()-2)[1] : "My Files"),
                    () -> { folderStack.pop(); String[] p = folderStack.pop(); loadFolder(p[0], p[1]); });
        }
        if (files.isEmpty()) { addEmptyLabel("This folder is empty.\nTap ＋ to add a magnet."); return; }
        for (SeedrApi.SeedrFile f : files) {
            if (f.isTorrent) addTransferRow(f);
            else addFileRow(f);
        }
    }

    private void addNavRow(String label, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(10), dp(14), dp(10));
        row.setBackground(Ui.focusCard(this, 12));
        TextView t = tv(13, Ui.ACCENT, false); t.setText(label);
        row.addView(t);
        row.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = row(4);
        fileList.addView(row, lp);
    }

    private void addFileRow(SeedrApi.SeedrFile f) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(8), dp(12));
        row.setBackground(Ui.focusCard(this, 12));

        // icon
        String icon = f.isFolder ? "folder" : iconFor(f.ext);
        int iconColor = f.isFolder ? 0xFFFFAA00 : 0xFFCCCCCC;
        ImageView ic = UiKit.icon(this, icon, 22, iconColor);
        LinearLayout.LayoutParams ilp = lp(dp(36), dp(36));
        ilp.setMargins(0,0,dp(12),0);
        row.addView(ic, ilp);

        // name + subtitle
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        TextView name = tv(14, Color.WHITE, f.isFolder);
        name.setText(f.name); name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(name);
        String sub = f.subtitle();
        if (!sub.isEmpty()) { TextView sv = tv(11, 0x88FFFFFF, false); sv.setText(sub); col.addView(sv); }
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT,1));

        // ⋮ menu button
        ImageView more = UiKit.darkButton(this,"more-vertical",36,false,v->fileMenu(f));
        row.addView(more, lp(dp(36),dp(36)));

        row.setOnClickListener(v->onFileTap(f));
        fileList.addView(row, row(4));
    }

    private void addTransferRow(SeedrApi.SeedrFile f) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackground(Ui.card(this, 0xFF111827, Ui.ACCENT, 12));

        // name row
        LinearLayout topRow = new LinearLayout(this); topRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView ic = UiKit.icon(this,"download",18,Ui.ACCENT2);
        LinearLayout.LayoutParams ilp = lp(dp(24),dp(24)); ilp.setMargins(0,0,dp(8),0);
        topRow.addView(ic,ilp);
        TextView name = tv(13, Color.WHITE, true);
        name.setText(f.name); name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        topRow.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT,1));
        ImageView del = UiKit.darkButton(this,"x",32,false,v->confirmDeleteTorrent(f));
        topRow.addView(del, lp(dp(32),dp(32)));
        row.addView(topRow);

        // progress bar
        ProgressBar pb = new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        try { pb.setProgress(Integer.parseInt(f.progress==null?"0":f.progress.replace("%","").trim())); }
        catch(Exception ignored){}
        LinearLayout.LayoutParams pblp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(6));
        pblp.setMargins(0,dp(6),0,dp(4));
        row.addView(pb, pblp);

        // stats
        TextView stats = tv(11, 0x88FFFFFF, false);
        stats.setText(buildStats(f));
        row.addView(stats);

        fileList.addView(row, row(6));

        // live poll
        if (!f.progressUrl.isEmpty()) {
            Runnable[] poll = {null};
            poll[0] = new Runnable() { public void run() {
                new Thread(() -> {
                    try {
                        SeedrApi.SeedrFile updated = pollProgress(f.progressUrl);
                        main.post(() -> {
                            try { pb.setProgress(Integer.parseInt(updated.progress==null?"0":updated.progress.replace("%","").trim())); }
                            catch(Exception ignored){}
                            stats.setText(buildStats(updated));
                            name.setText(updated.name.isEmpty() ? f.name : updated.name);
                            if (!"100".equals(updated.progress)) main.postDelayed(poll[0],4000);
                            else { stats.setText("✅ Complete — go to Files tab"); loadAccount(); }
                        });
                    } catch(Exception ignored){}
                }).start();
            }};
            pollers.add(poll[0]);
            main.postDelayed(poll[0], 4000);
        }
    }

    private String buildStats(SeedrApi.SeedrFile f) {
        StringBuilder sb = new StringBuilder();
        sb.append(f.progress==null?"0":f.progress).append("%");
        if (f.dlRate > 0) sb.append("  ↓ ").append(fmtSpeed(f.dlRate));
        if (f.seeders > 0) sb.append("  ").append(f.seeders).append(" seeds");
        if (f.peers   > 0) sb.append("  ").append(f.peers).append(" peers");
        if (f.size    > 0) sb.append("  ").append(f.displaySize());
        return sb.toString();
    }

    private void addEmptyLabel(String msg) {
        TextView t = tv(14, 0x66FFFFFF, false);
        t.setText(msg); t.setGravity(Gravity.CENTER);
        t.setPadding(dp(32),dp(56),dp(32),dp(56));
        fileList.addView(t);
    }

    // ================================================================== ACTIONS

    private void onFileTap(SeedrApi.SeedrFile f) {
        if (f.isFolder) { loadFolder(f.id, f.name); return; }
        // fetch streaming URL then offer play options
        showLoading(true);
        new Thread(() -> {
            try {
                String url = SeedrApi.fetchFileUrl(f.id);
                main.post(() -> { showLoading(false); offerPlay(url, f.name); });
            } catch (Exception e) {
                main.post(() -> { showLoading(false); showError(e.getMessage()); });
            }
        }).start();
    }

    private void offerPlay(String url, String name) {
        String low = name.toLowerCase(Locale.US);
        boolean video = low.matches(".*\\.(mp4|mkv|avi|mov|m4v|webm|ts|flv|3gp|wmv|m2ts)$");
        boolean audio = low.matches(".*\\.(mp3|m4a|aac|flac|ogg|opus|wav)$");
        new UiDialog(this).setTitle(name)
                .setItems(new String[]{
                        "▶  " + T.t(video||audio ? "Play / Stream" : "Open in Browser"),
                        "📋  " + T.t("Copy streaming link")
                }, (d,w) -> {
                    if (w == 0) {
                        if (video || audio) startActivity(new Intent(this, PlayerActivity.class)
                                .putExtra("url",url).putExtra("title",name));
                        else startActivity(new Intent(this, BrowserActivity.class)
                                .putExtra(BrowserActivity.EXTRA_URL, url));
                    } else {
                        android.content.ClipboardManager cm = (android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                        if (cm!=null) cm.setPrimaryClip(android.content.ClipData.newPlainText("url",url));
                        Toast.makeText(this,"📋 "+T.t("Copied"),Toast.LENGTH_SHORT).show();
                    }
                }).show();
    }

    private void fileMenu(SeedrApi.SeedrFile f) {
        List<String> items = new ArrayList<>();
        if (!f.isFolder) items.add("▶  " + T.t("Play / Stream"));
        items.add("✏  " + T.t("Rename"));
        items.add("🗑  " + T.t("Delete"));
        new UiDialog(this).setTitle(f.name).setItems(items.toArray(new String[0]),(d,w)->{
            String ch = items.get(w);
            if (ch.contains("Play")) onFileTap(f);
            else if (ch.contains("Rename")) showRename(f);
            else confirmDelete(f);
        }).show();
    }

    private void showRename(SeedrApi.SeedrFile f) {
        EditText et = new EditText(this); et.setText(f.name); et.setSingleLine(true);
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20),dp(4),dp(20),0); box.addView(et);
        new UiDialog(this).setTitle(T.t("Rename")).setView(box)
                .setPositiveButton(T.t("Save"),(d,w)->{
                    String nm = et.getText().toString().trim(); if(nm.isEmpty())return;
                    new Thread(()->{
                        try {
                            if(f.isFolder) SeedrApi.renameFolder(f.id,nm);
                            else SeedrApi.renameFile(f.id,nm);
                            main.post(()->loadFolder(folderStack.peek()[0],folderStack.peek()[1]));
                        } catch(Exception e){ main.post(()->showError(e.getMessage())); }
                    }).start();
                }).setNegativeButton(T.t("Cancel"),null).show();
    }

    private void confirmDelete(SeedrApi.SeedrFile f) {
        new UiDialog(this).setTitle(T.t("Delete")+": "+f.name)
                .setMessage(T.t("This cannot be undone."))
                .setPositiveButton(T.t("Delete"),(d,w)->new Thread(()->{
                    try {
                        if(f.isFolder) SeedrApi.deleteFolder(f.id);
                        else SeedrApi.deleteFile(f.id);
                        main.post(()->{
                            Toast.makeText(this,"🗑 "+T.t("Deleted"),Toast.LENGTH_SHORT).show();
                            loadFolder(folderStack.peek()[0],folderStack.peek()[1]);
                        });
                    } catch(Exception e){ main.post(()->showError(e.getMessage())); }
                }).start())
                .setNegativeButton(T.t("Cancel"),null).show();
    }

    private void confirmDeleteTorrent(SeedrApi.SeedrFile f) {
        new UiDialog(this).setTitle(T.t("Stop & delete")+"?").setMessage(f.name)
                .setPositiveButton(T.t("Delete"),(d,w)->new Thread(()->{
                    try {
                        SeedrApi.deleteTorrent(f.id);
                        main.post(()->{
                            Toast.makeText(this,"🗑 "+T.t("Deleted"),Toast.LENGTH_SHORT).show();
                            loadTransfers();
                        });
                    } catch(Exception e){ main.post(()->showError(e.getMessage())); }
                }).start())
                .setNegativeButton(T.t("Cancel"),null).show();
    }

    // ================================================================== ADD MAGNET

    public void showAddMagnet() {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20),dp(4),dp(20),0);
        EditText et = new EditText(this);
        et.setHint(T.t("Paste magnet link or torrent URL"));
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);
        // auto-paste magnet from clipboard
        try {
            android.content.ClipboardManager cm=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            if(cm!=null&&cm.hasPrimaryClip()){
                CharSequence clip=cm.getPrimaryClip().getItemAt(0).getText();
                if(clip!=null&&clip.toString().startsWith("magnet:")) et.setText(clip.toString().trim());
            }
        } catch(Exception ignored){}
        box.addView(et);
        new UiDialog(this).setTitle("☁  "+T.t("Add to Seedr")).setView(box)
                .setPositiveButton(T.t("Add"),(d,w)->{
                    String link=et.getText().toString().trim();
                    if(!link.isEmpty()) sendToSeedrApi(link);
                })
                .setNegativeButton(T.t("Cancel"),null).show();
    }

    public void sendToSeedrApi(final String magnet) {
        showLoading(true);
        new Thread(()->{
            try {
                SeedrApi.AddResult res = SeedrApi.addMagnet(magnet);
                main.post(()->{
                    showLoading(false);
                    if (res.ok) {
                        Toast.makeText(this,"☁ "+T.t("Added to Seedr — downloading in the cloud"),Toast.LENGTH_LONG).show();
                        currentTab = 1; refreshTab();   // switch to Transfers
                    } else {
                        showError(res.msg);
                    }
                });
            } catch(Exception e){
                main.post(()->{ showLoading(false); showError(e.getMessage()); });
            }
        }).start();
    }

    // ================================================================== SEARCH

    private void showSearchBar() {
        titleView.setText("☁  Search");
        fileList.removeAllViews();
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0,dp(8),0,0);
        EditText et = new EditText(this); et.setHint(T.t("Search your Seedr files…")); et.setSingleLine(true);
        et.setTextColor(Color.WHITE); et.setHintTextColor(0x66FFFFFF);
        et.setBackgroundColor(0xFF1A2035);
        et.setPadding(dp(14),dp(12),dp(14),dp(12));
        box.addView(et);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMargins(0,0,0,dp(8));
        fileList.addView(box,blp);
        et.setOnEditorActionListener((v,id,ev)->{
            String q=et.getText().toString().trim();
            if(!q.isEmpty()){
                showLoading(true);
                new Thread(()->{
                    try {
                        List<SeedrApi.SeedrFile> r=SeedrApi.search(q);
                        main.post(()->{
                            showLoading(false);
                            // remove everything below the search bar
                            while(fileList.getChildCount()>1) fileList.removeViewAt(1);
                            if(r.isEmpty()) {
                                TextView empty=tv(14,0x66FFFFFF,false);
                                empty.setText(T.t("No results for")+" \""+q+"\"");
                                empty.setPadding(0,dp(24),0,0);
                                fileList.addView(empty);
                            } else {
                                for(SeedrApi.SeedrFile f:r) {
                                    View row=buildSearchRow(f);
                                    fileList.addView(row,row(4));
                                }
                            }
                        });
                    } catch(Exception e){ main.post(()->{showLoading(false);showError(e.getMessage());}); }
                }).start();
            }
            return false;
        });
    }

    private View buildSearchRow(SeedrApi.SeedrFile f) {
        LinearLayout row=new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14),dp(12),dp(8),dp(12));
        row.setBackground(Ui.focusCard(this,12));
        ImageView ic=UiKit.icon(this,iconFor(f.ext),20,0xFFCCCCCC);
        LinearLayout.LayoutParams ilp=lp(dp(32),dp(32)); ilp.setMargins(0,0,dp(10),0);
        row.addView(ic,ilp);
        LinearLayout col=new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        TextView name=tv(13,Color.WHITE,false);
        name.setText(f.name); name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(name);
        TextView sub=tv(11,0x88FFFFFF,false); sub.setText(f.displaySize()); col.addView(sub);
        row.addView(col,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1));
        row.setOnClickListener(v->onFileTap(f));
        return row;
    }

    // ================================================================== SETUP / SETTINGS

    private void showSetup() {
        LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20),dp(4),dp(20),0);
        TextView msg=tv(13,0xCCFFFFFF,false);
        msg.setText(
            "1. Go to seedr.cc/api/v0.1/console/tokens\n"+
            "2. Create token → choose \"Read & Add Torrents\"\n"+
            "3. Paste it below\n\n"+
            "Or use the 6-Hour Token from the Documentation page."
        );
        msg.setPadding(0,0,0,dp(12));
        box.addView(msg);
        EditText et=new EditText(this);
        et.setHint(T.t("Paste your Seedr token here"));
        et.setSingleLine(true);
        if(SeedrApi.hasPat()) et.setText(SeedrApi.pat());
        box.addView(et);
        new UiDialog(this).setTitle("☁  "+T.t("Connect to Seedr")).setView(box)
                .setPositiveButton(T.t("Connect"),(d,w)->{
                    String tok=et.getText().toString().trim();
                    if(tok.isEmpty())return;
                    // save as PAT; the API layer will exchange it for OAuth
                    SeedrApi.clearAll();
                    SeedrApi.savePat(tok);
                    showLoading(true);
                    new Thread(()->{
                        try {
                            // first: try using PAT directly as access_token (some Seedr tokens work this way)
                            SeedrApi.saveOAuth(tok,"");
                            SeedrApi.AccountInfo acc=SeedrApi.getAccount();
                            main.post(()->{
                                showLoading(false);
                                Toast.makeText(this,"✅ "+T.t("Connected as")+" "+acc.username,Toast.LENGTH_LONG).show();
                                usernameView.setText(acc.username);
                                if(acc.spaceTotal>0){
                                    quotaLabel.setText("Storage: "+acc.quota());
                                    quotaBar.setProgress((int)(1000L*acc.spaceUsed/acc.spaceTotal));
                                }
                                folderStack.clear(); currentTab=0; refreshTab();
                            });
                        } catch(Exception e){
                            // try exchange via OAuth
                            try {
                                SeedrApi.clearAll();
                                SeedrApi.savePat(tok);
                                String oauth=SeedrApi.exchangePat();
                                SeedrApi.AccountInfo acc2=SeedrApi.getAccount();
                                main.post(()->{
                                    showLoading(false);
                                    Toast.makeText(this,"✅ "+T.t("Connected as")+" "+acc2.username,Toast.LENGTH_LONG).show();
                                    usernameView.setText(acc2.username);
                                    folderStack.clear(); currentTab=0; refreshTab();
                                });
                            } catch(Exception e2){
                                main.post(()->{
                                    showLoading(false);
                                    showError(T.t("Token rejected")+": "+e2.getMessage()+"\n\nTry the 6-Hour Token from the Docs page.");
                                    SeedrApi.clearAll();
                                });
                            }
                        }
                    }).start();
                })
                .setNeutralButton(T.t("Open Seedr console"),(d,w)->
                    startActivity(new Intent(this,BrowserActivity.class)
                        .putExtra(BrowserActivity.EXTRA_URL,"https://www.seedr.cc/api/v0.1/console/tokens")))
                .setNegativeButton(T.t("Cancel"),null).show();
    }

    private void showSettings() {
        new UiDialog(this).setTitle("Seedr  ·  "+SeedrApi.pat().substring(0,Math.min(12,SeedrApi.pat().length()))+"…")
                .setItems(new String[]{
                        "🔄  "+T.t("Refresh"),
                        "🔑  "+T.t("Change token"),
                        "🚪  "+T.t("Sign out")
                },(d,w)->{
                    if(w==0) loadAccount();
                    else if(w==1) showSetup();
                    else { SeedrApi.clearAll(); finish(); }
                }).show();
    }

    // ================================================================== PROGRESS POLL

    private SeedrApi.SeedrFile pollProgress(String url) {
        SeedrApi.SeedrFile f = new SeedrApi.SeedrFile();
        try {
            // progress_url is a direct JSON endpoint
            java.net.HttpURLConnection c=(java.net.HttpURLConnection)new java.net.URL(url).openConnection();
            c.setConnectTimeout(8000); c.setReadTimeout(8000);
            java.io.BufferedReader br=new java.io.BufferedReader(new java.io.InputStreamReader(c.getInputStream()));
            StringBuilder sb=new StringBuilder(); String ln;
            while((ln=br.readLine())!=null) sb.append(ln);
            br.close();
            org.json.JSONObject r=new org.json.JSONObject(sb.toString());
            f.name     = r.optString("name","");
            f.progress = String.valueOf((int)(r.optDouble("progress",0)*100));
            f.dlRate   = r.optLong("download_rate",0);
            f.seeders  = r.optInt("seeders",0);
            f.peers    = r.optInt("downloading_from",0);
            f.size     = r.optLong("size",0);
        } catch(Exception ignored){}
        return f;
    }

    // ================================================================== HELPERS

    private void showLoading(boolean show) {
        loadingOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void showError(String msg) {
        Toast.makeText(this, "⚠ "+msg, Toast.LENGTH_LONG).show();
    }

    private static String fmtSpeed(long bps){
        if(bps>1_048_576) return String.format(Locale.US,"%.1f MB/s",bps/1_048_576.0);
        if(bps>1024)      return String.format(Locale.US,"%.0f KB/s",bps/1024.0);
        return bps+" B/s";
    }

    private static String iconFor(String ext){
        if(ext==null) return "file";
        switch(ext.toLowerCase(Locale.US)){
            case "mp4": case "mkv": case "avi": case "mov": case "m4v":
            case "webm": case "ts": case "flv": return "video";
            case "mp3": case "m4a": case "aac": case "flac":
            case "ogg": case "opus": case "wav": return "music";
            case "pdf": return "file-text";
            case "zip": case "rar": case "7z": return "archive";
            case "jpg": case "jpeg": case "png": case "gif": case "webp": return "image";
            default: return "file";
        }
    }

    private LinearLayout.LayoutParams lp(int w,int h){return new LinearLayout.LayoutParams(w,h);}
    private LinearLayout.LayoutParams row(int marginDp){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0,dp(marginDp/2),0,dp(marginDp/2)); return p;
    }

    @Override public void onBackPressed(){
        if(currentTab!=0){ currentTab=0; folderStack.clear(); refreshTab(); return; }
        if(folderStack.size()>1){ folderStack.pop(); String[]p=folderStack.pop(); loadFolder(p[0],p[1]); return; }
        super.onBackPressed();
    }
}
