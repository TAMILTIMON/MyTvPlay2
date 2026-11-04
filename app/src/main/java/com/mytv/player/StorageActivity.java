package com.mytv.player;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SD Card & USB — a file manager for the phone's storage, SD cards and USB drives (OTG / TV boxes):
 * storage overview with free space, quick folders, browse with breadcrumbs, list / grid with
 * thumbnails, sort, search, multi-select, copy / move / paste, rename, delete, new folder, share,
 * open with, properties. Videos and songs play in ZH Play. Drives appear / disappear live.
 */
public class StorageActivity extends Activity {
    private static final int BG = 0xFF0B0F1A, PANEL = 0xFF121826, CARD = 0xFF182033, SUB = 0x99FFFFFF;

    // ---- clipboard shared between screens (copy here, paste on another drive)
    private static final List<File> clip = new ArrayList<>();
    private static boolean clipMove;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService thumbs = Executors.newFixedThreadPool(3);
    private final LruCache<String, Bitmap> thumbCache = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount();
        }
    };

    private final List<Vol> vols = new ArrayList<>();
    private File cur;                                // null = the storage overview
    private Vol curVol;
    private final List<Entry> all = new ArrayList<>(), shown = new ArrayList<>();
    private final LinkedHashSet<File> sel = new LinkedHashSet<>();
    private String filter = "";
    private int loadSeq;

    private TextView title, subtitle, empty;
    private LinearLayout actions, crumbs, home, pasteBar, searchBar;
    private HorizontalScrollView crumbScroll;
    private ScrollView homeScroll;
    private ListView list;
    private GridView grid;
    private EditText searchBox;
    private ProgressBar busy;
    private final Adapter adapter = new Adapter();

    static final class Vol {
        String name, kind;                           // kind: phone | sd | usb
        File root;
        long total, free;
    }

    static final class Entry {
        File f;
        String name, ext;
        boolean dir;
        long size, mod;
        int count = -1;
    }

    public static void open(Context c) {
        Intent i = new Intent(c, StorageActivity.class);
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

    private String size(long b) {
        return Formatter.formatShortFileSize(this, Math.max(0, b));
    }

    // ================================================================== life cycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(PANEL);
        getWindow().setNavigationBarColor(BG);
        build();
        askPermission();
        showHome();
    }

    private final BroadcastReceiver mounts = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            main.postDelayed(() -> {
                if (cur != null && !cur.exists()) {
                    toast("⏏ " + T.t("The drive was removed"));
                    showHome();
                } else if (cur == null) {
                    showHome();
                }
            }, 800);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_MEDIA_MOUNTED);
        f.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        f.addAction(Intent.ACTION_MEDIA_REMOVED);
        f.addAction(Intent.ACTION_MEDIA_EJECT);
        f.addAction(Intent.ACTION_MEDIA_BAD_REMOVAL);
        f.addDataScheme("file");
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(mounts, f, Context.RECEIVER_EXPORTED);
            else registerReceiver(mounts, f);
        } catch (Exception ignored) {
        }
        if (cur == null) showHome();                 // e.g. back from granting "all files" access
        else load(cur);
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(mounts);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        thumbs.shutdownNow();
    }

    @Override
    public void onBackPressed() {
        if (!sel.isEmpty()) {
            sel.clear();
            refreshBars();
            adapter.notifyDataSetChanged();
        } else if (searchBar.getVisibility() == View.VISIBLE) {
            closeSearch();
        } else if (cur != null) {
            File p = cur.getParentFile();
            if (curVol == null || cur.equals(curVol.root) || p == null) showHome();
            else open(p);
        } else {
            super.onBackPressed();
        }
    }

    // ================================================================== permission

    private boolean hasAccess() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        if (Build.VERSION.SDK_INT >= 23)
            return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        return true;
    }

    private void askPermission() {
        if (hasAccess()) return;
        if (Build.VERSION.SDK_INT >= 30) return;     // a card on the overview explains and opens Settings
        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, 7);
        }
    }

    private void openAccessSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Exception e2) {
                toast(T.t("Open Settings → Apps → ZH Play → Permissions → Files"));
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        showHome();
    }

    // ================================================================== layout

    private void build() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        frame.addView(root, new FrameLayout.LayoutParams(-1, -1));

        // header: back · title / subtitle · actions
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setBackgroundColor(PANEL);
        head.setPadding(dp(8), dp(10), dp(8), dp(10));
        head.addView(UiKit.darkButton(this, "arrow-left", 42, false, v -> onBackPressed()), new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(dp(10), 0, dp(6), 0);
        title = text(18, Color.WHITE, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        subtitle = text(12, SUB, false);
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(TextUtils.TruncateAt.START);
        names.addView(title);
        names.addView(subtitle);
        head.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(actions);
        root.addView(head);

        // search bar (hidden until 🔍)
        searchBar = new LinearLayout(this);
        searchBar.setBackgroundColor(PANEL);
        searchBar.setPadding(dp(12), 0, dp(12), dp(10));
        searchBar.setVisibility(View.GONE);
        searchBox = new EditText(this);
        searchBox.setSingleLine(true);
        searchBox.setHint(T.t("Search in this folder…"));
        searchBox.setTextColor(Color.WHITE);
        searchBox.setHintTextColor(SUB);
        searchBox.setBackground(Ui.card(this, CARD, 0x33FFFFFF, 12));
        searchBox.setPadding(dp(14), dp(10), dp(14), dp(10));
        searchBox.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void afterTextChanged(Editable e) {
                filter = e.toString().trim().toLowerCase(Locale.ROOT);
                applyFilter();
            }
        });
        searchBar.addView(searchBox, new LinearLayout.LayoutParams(-1, -2));
        root.addView(searchBar);

        // breadcrumbs
        crumbScroll = new HorizontalScrollView(this);
        crumbScroll.setHorizontalScrollBarEnabled(false);
        crumbScroll.setBackgroundColor(PANEL);
        crumbs = new LinearLayout(this);
        crumbs.setPadding(dp(10), 0, dp(10), dp(10));
        crumbScroll.addView(crumbs);
        root.addView(crumbScroll);

        // content: overview, or a folder as list / grid
        FrameLayout content = new FrameLayout(this);
        homeScroll = new ScrollView(this);
        home = new LinearLayout(this);
        home.setOrientation(LinearLayout.VERTICAL);
        home.setPadding(dp(14), dp(12), dp(14), dp(30));
        homeScroll.addView(home);
        content.addView(homeScroll, new FrameLayout.LayoutParams(-1, -1));

        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(dp(6));
        list.setPadding(dp(12), dp(10), dp(12), dp(90));
        list.setClipToPadding(false);
        list.setSelector(new android.graphics.drawable.ColorDrawable(0));
        list.setItemsCanFocus(true);
        content.addView(list, new FrameLayout.LayoutParams(-1, -1));

        grid = new GridView(this);
        grid.setColumnWidth(dp(108));
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(dp(8));
        grid.setVerticalSpacing(dp(8));
        grid.setPadding(dp(12), dp(10), dp(12), dp(90));
        grid.setClipToPadding(false);
        grid.setSelector(new android.graphics.drawable.ColorDrawable(0));
        content.addView(grid, new FrameLayout.LayoutParams(-1, -1));

        for (AbsListView v : new AbsListView[]{list, grid}) {
            v.setAdapter(adapter);
            v.setOnItemClickListener((p, view, pos, id) -> tap(shown.get(pos)));
            v.setOnItemLongClickListener((p, view, pos, id) -> {
                toggle(shown.get(pos).f);
                return true;
            });
            v.setOnKeyListener((view, code, ev) -> {      // TV remote: ☰ / menu key = options for the item
                if (ev.getAction() == KeyEvent.ACTION_UP && code == KeyEvent.KEYCODE_MENU) {
                    int pos = ((AbsListView) view).getSelectedItemPosition();
                    if (pos >= 0 && pos < shown.size()) itemMenu(shown.get(pos));
                    return true;
                }
                return false;
            });
        }

        empty = text(15, SUB, false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(30), dp(60), dp(30), 0);
        empty.setVisibility(View.GONE);
        content.addView(empty, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        busy = new ProgressBar(this);
        busy.setVisibility(View.GONE);
        content.addView(busy, new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER));

        // paste bar (after Copy / Move)
        pasteBar = new LinearLayout(this);
        pasteBar.setGravity(Gravity.CENTER_VERTICAL);
        pasteBar.setPadding(dp(14), dp(10), dp(10), dp(10));
        pasteBar.setBackground(Ui.card(this, PANEL, Ui.ACCENT, 18));
        pasteBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        plp.setMargins(dp(12), 0, dp(12), dp(14));
        content.addView(pasteBar, plp);

        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(frame);
    }

    // ================================================================== header & bars

    private void refreshBars() {
        actions.removeAllViews();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(40));
        lp.setMargins(dp(4), 0, 0, 0);
        if (!sel.isEmpty()) {                                 // selection mode
            title.setText(sel.size() + " " + T.t("selected"));
            subtitle.setText(T.t("Tap more to add · back to cancel"));
            actions.addView(UiKit.darkButton(this, "check-square", 40, false, v -> selectAll()), lp);
            actions.addView(UiKit.darkButton(this, "copy", 40, false, v -> toClip(new ArrayList<>(sel), false)), lp);
            actions.addView(UiKit.darkButton(this, "scissors", 40, false, v -> toClip(new ArrayList<>(sel), true)), lp);
            actions.addView(UiKit.darkButton(this, "trash", 40, false, v -> confirmDelete(new ArrayList<>(sel))), lp);
            actions.addView(UiKit.darkButton(this, "ellipsis-vertical", 40, false, v -> selectionMenu()), lp);
        } else if (cur != null) {
            title.setText(cur.equals(curVol == null ? null : curVol.root) ? curVol.name : cur.getName());
            subtitle.setText(cur.getAbsolutePath());
            actions.addView(UiKit.darkButton(this, "search", 40, false, v -> openSearch()), lp);
            actions.addView(UiKit.darkButton(this, "folder-plus", 40, false, v -> newFolder()), lp);
            actions.addView(UiKit.darkButton(this, "ellipsis-vertical", 40, false, v -> viewMenu()), lp);
        } else {
            title.setText(T.t("SD Card & USB"));
            subtitle.setText(T.t("Phone storage, SD cards and USB drives"));
            actions.addView(UiKit.darkButton(this, "refresh-cw", 40, false, v -> showHome()), lp);
        }

        // paste bar
        pasteBar.removeAllViews();
        boolean showPaste = !clip.isEmpty() && cur != null && sel.isEmpty();
        pasteBar.setVisibility(showPaste ? View.VISIBLE : View.GONE);
        if (showPaste) {
            pasteBar.addView(UiKit.icon(this, clipMove ? "scissors" : "copy", 20, Ui.ACCENT), new LinearLayout.LayoutParams(dp(30), dp(30)));
            TextView t = text(14, Color.WHITE, true);
            t.setText((clipMove ? T.t("Move") : T.t("Copy")) + " " + clip.size() + " " + T.t("item(s) here?"));
            t.setPadding(dp(8), 0, dp(8), 0);
            pasteBar.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
            TextView cancel = pill(T.t("Cancel"), false);
            cancel.setOnClickListener(v -> {
                clip.clear();
                refreshBars();
            });
            pasteBar.addView(cancel);
            TextView paste = pill(T.t("Paste"), true);
            paste.setOnClickListener(v -> paste());
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-2, -2);
            pl.setMargins(dp(8), 0, 0, 0);
            pasteBar.addView(paste, pl);
        }
    }

    private TextView pill(String label, boolean primary) {
        TextView t = text(14, primary ? Color.WHITE : Ui.ACCENT, true);
        t.setText(label);
        t.setPadding(dp(16), dp(9), dp(16), dp(9));
        t.setFocusable(true);
        t.setBackground(primary ? Ui.gradient(this, 20) : Ui.focusCard(this, 20));
        return t;
    }

    private void showCrumbs() {
        crumbs.removeAllViews();
        crumbScroll.setVisibility(cur == null ? View.GONE : View.VISIBLE);
        if (cur == null) return;
        List<File> chain = new ArrayList<>();
        for (File f = cur; f != null; f = f.getParentFile()) {
            chain.add(0, f);
            if (curVol != null && f.equals(curVol.root)) break;
        }
        for (int i = 0; i < chain.size(); i++) {
            final File f = chain.get(i);
            boolean last = i == chain.size() - 1;
            String label = (i == 0 && curVol != null && f.equals(curVol.root)) ? curVol.name : f.getName();
            TextView c = text(13, last ? Color.WHITE : SUB, last);
            c.setText(label.isEmpty() ? "/" : label);
            c.setPadding(dp(12), dp(6), dp(12), dp(6));
            c.setFocusable(true);
            c.setBackground(last ? Ui.card(this, CARD, Ui.ACCENT, 14) : Ui.focusCard(this, 14));
            c.setOnClickListener(v -> open(f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(0, 0, dp(6), 0);
            crumbs.addView(c, lp);
            if (!last) {
                TextView sep = text(13, SUB, false);
                sep.setText("›");
                sep.setPadding(0, 0, dp(6), 0);
                crumbs.addView(sep);
            }
        }
        crumbScroll.post(() -> crumbScroll.fullScroll(View.FOCUS_RIGHT));
    }

    // ================================================================== storage overview

    private void showHome() {
        cur = null;
        curVol = null;
        sel.clear();
        closeSearch();
        list.setVisibility(View.GONE);
        grid.setVisibility(View.GONE);
        empty.setVisibility(View.GONE);
        homeScroll.setVisibility(View.VISIBLE);
        refreshBars();
        showCrumbs();
        home.removeAllViews();
        busy.setVisibility(View.VISIBLE);
        new Thread(() -> {
            List<Vol> v = findVolumes();
            main.post(() -> {
                busy.setVisibility(View.GONE);
                vols.clear();
                vols.addAll(v);
                renderHome();
            });
        }).start();
    }

    private void renderHome() {
        home.removeAllViews();
        if (!hasAccess()) {
            LinearLayout c = card();
            c.addView(UiKit.icon(this, "info", 26, 0xFFFBBF24));
            TextView t = text(14, Color.WHITE, false);
            t.setText(T.t("ZH Play needs “All files access” to show SD cards and USB drives."));
            t.setPadding(0, dp(8), 0, dp(10));
            c.addView(t);
            TextView b = pill(T.t("Allow access"), true);
            b.setOnClickListener(v -> {
                if (Build.VERSION.SDK_INT >= 30) openAccessSettings();
                else askPermission();
            });
            c.addView(b, new LinearLayout.LayoutParams(-2, -2));
            home.addView(c, cardLp());
        }

        section(T.t("Storage"));
        boolean external = false;
        for (Vol v : vols) {
            if (!"phone".equals(v.kind)) external = true;
            home.addView(volumeCard(v), cardLp());
        }
        if (!external) {
            LinearLayout c = card();
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(UiKit.icon(this, "usb", 26, SUB), new LinearLayout.LayoutParams(dp(40), dp(40)));
            TextView t = text(13, SUB, false);
            t.setText(T.t("No SD card or USB drive found. Insert an SD card or connect a USB drive (OTG) — it shows up here by itself."));
            t.setPadding(dp(10), 0, 0, 0);
            row.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
            c.addView(row);
            home.addView(c, cardLp());
        }

        // quick folders on the phone's own storage
        File prim = Environment.getExternalStorageDirectory();
        String[][] quick = {
                {"Downloads", Environment.DIRECTORY_DOWNLOADS, "download", "#60A5FA"},
                {"Movies", Environment.DIRECTORY_MOVIES, "film", "#F472B6"},
                {"Music", Environment.DIRECTORY_MUSIC, "file-music", "#A78BFA"},
                {"Pictures", Environment.DIRECTORY_PICTURES, "image", "#34D399"},
                {"Camera", Environment.DIRECTORY_DCIM + "/Camera", "image", "#FBBF24"},
                {"Documents", "Documents", "file-text", "#CBD5E1"}};
        List<View> tiles = new ArrayList<>();
        for (String[] q : quick) {
            final File d = new File(prim, q[1]);
            if (!d.isDirectory()) continue;
            tiles.add(quickTile(T.t(q[0]), q[2], Color.parseColor(q[3]), v -> open(d)));
        }
        if (!tiles.isEmpty()) {
            section(T.t("Quick access"));
            LinearLayout row = null;
            for (int i = 0; i < tiles.size(); i++) {
                if (i % 3 == 0) {
                    row = new LinearLayout(this);
                    home.addView(row, new LinearLayout.LayoutParams(-1, -2));
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
                lp.setMargins(i % 3 == 0 ? 0 : dp(5), dp(5), i % 3 == 2 ? 0 : dp(5), dp(5));
                row.addView(tiles.get(i), lp);
            }
            for (int pad = tiles.size() % 3; pad != 0 && pad < 3; pad++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 1, 1);
                lp.setMargins(dp(5), 0, 0, 0);
                row.addView(new View(this), lp);
            }
        }
    }

    private void section(String s) {
        TextView t = text(13, SUB, true);
        t.setText(s.toUpperCase(Locale.getDefault()));
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(4), dp(16), 0, dp(6));
        home.addView(t);
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        c.setBackground(Ui.card(this, CARD, 0x22FFFFFF, 16));
        return c;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(5), 0, dp(5));
        return lp;
    }

    private View volumeCard(final Vol v) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        c.setBackground(Ui.focusCard(this, 16));
        c.setFocusable(true);
        c.setOnClickListener(x -> open(v.root));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        String icon = "usb".equals(v.kind) ? "usb" : "sd".equals(v.kind) ? "sd-card" : "smartphone";
        int tint = "usb".equals(v.kind) ? 0xFF22D3EE : "sd".equals(v.kind) ? 0xFFF59E0B : Ui.ACCENT;
        FrameLayout badge = new FrameLayout(this);
        badge.setBackground(Ui.card(this, (tint & 0x00FFFFFF) | 0x26000000, 0, 14));
        badge.addView(UiKit.icon(this, icon, 26, tint), new FrameLayout.LayoutParams(-1, -1));
        row.addView(badge, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), 0, 0, 0);
        TextView n = text(16, Color.WHITE, true);
        n.setText(v.name);
        TextView s = text(12, SUB, false);
        s.setText(v.total > 0 ? size(v.free) + " " + T.t("free of") + " " + size(v.total) : v.root.getAbsolutePath());
        col.addView(n);
        col.addView(s);
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(UiKit.icon(this, "chevron-right", 20, SUB), new LinearLayout.LayoutParams(dp(24), dp(24)));
        c.addView(row);
        if (v.total > 0) {
            ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(1000);
            bar.setProgress((int) (1000L * (v.total - v.free) / v.total));
            if (Build.VERSION.SDK_INT >= 21) bar.setProgressTintList(android.content.res.ColorStateList.valueOf(tint));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(6));
            blp.setMargins(0, dp(14), 0, 0);
            c.addView(bar, blp);
        }
        return c;
    }

    private View quickTile(String label, String icon, int tint, View.OnClickListener l) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(6), dp(14), dp(6), dp(12));
        t.setBackground(Ui.focusCard(this, 14));
        t.setFocusable(true);
        t.setOnClickListener(l);
        t.addView(UiKit.icon(this, icon, 26, tint), new LinearLayout.LayoutParams(dp(34), dp(34)));
        TextView n = text(12, Color.WHITE, false);
        n.setText(label);
        n.setSingleLine(true);
        n.setEllipsize(TextUtils.TruncateAt.END);
        n.setPadding(0, dp(6), 0, 0);
        t.addView(n);
        return t;
    }

    /** Phone storage, SD cards and USB drives that are mounted right now. */
    private List<Vol> findVolumes() {
        Map<String, Vol> m = new LinkedHashMap<>();
        File prim = Environment.getExternalStorageDirectory();
        addVol(m, prim, T.t("Phone storage"), "phone");
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                StorageManager sm = (StorageManager) getSystemService(STORAGE_SERVICE);
                for (StorageVolume v : sm.getStorageVolumes()) {
                    if (v.isPrimary()) continue;
                    String st = v.getState();
                    if (!Environment.MEDIA_MOUNTED.equals(st) && !Environment.MEDIA_MOUNTED_READ_ONLY.equals(st)) continue;
                    File dir = volumeDir(v);
                    if (dir == null) continue;
                    String desc = v.getDescription(this);
                    boolean usb = (desc != null && desc.toLowerCase(Locale.ROOT).contains("usb")) || dir.getPath().toLowerCase(Locale.ROOT).contains("usb");
                    addVol(m, dir, desc != null && !desc.isEmpty() ? desc : T.t(usb ? "USB drive" : "SD card"), usb ? "usb" : "sd");
                }
            } catch (Exception ignored) {
            }
        }
        // drives the system only exposes through app folders ( /storage/XXXX-XXXX/Android/data/… )
        try {
            for (File f : getExternalFilesDirs(null)) {
                if (f == null) continue;
                String p = f.getAbsolutePath();
                int i = p.indexOf("/Android/data");
                if (i > 0) {
                    File r = new File(p.substring(0, i));
                    boolean usb = p.toLowerCase(Locale.ROOT).contains("usb");
                    addVol(m, r, T.t(usb ? "USB drive" : "SD card"), usb ? "usb" : "sd");
                }
            }
        } catch (Exception ignored) {
        }
        // TV boxes / older phones: mount points that aren't reported as volumes
        for (String base : new String[]{"/storage", "/mnt/media_rw", "/mnt/usb", "/mnt/usb_storage", "/mnt/usbhost", "/mnt/external_sd", "/mnt/extSdCard"}) {
            File b = new File(base);
            File[] kids = b.listFiles();
            if (kids == null) {
                if (b.isDirectory() && b.canRead() && !base.equals("/storage") && !base.equals("/mnt/media_rw")) {
                    addVol(m, b, T.t(base.contains("usb") ? "USB drive" : "SD card"), base.contains("usb") ? "usb" : "sd");
                }
                continue;
            }
            for (File k : kids) {
                String n = k.getName();
                if (n.equals("self") || n.equals("emulated") || n.equals("sdcard0") || !k.isDirectory() || k.listFiles() == null) continue;
                boolean usb = k.getPath().toLowerCase(Locale.ROOT).contains("usb");
                addVol(m, k, T.t(usb ? "USB drive" : "SD card"), usb ? "usb" : "sd");
            }
        }
        List<Vol> out = new ArrayList<>(m.values());
        int sd = 0, usb = 0;
        for (Vol v : out) if ("sd".equals(v.kind)) sd++; else if ("usb".equals(v.kind)) usb++;
        // several drives of a kind: number them
        int si = 0, ui = 0;
        for (Vol v : out) {
            if ("sd".equals(v.kind) && sd > 1 && v.name.equals(T.t("SD card"))) v.name += " " + (++si);
            if ("usb".equals(v.kind) && usb > 1 && v.name.equals(T.t("USB drive"))) v.name += " " + (++ui);
        }
        return out;
    }

    private void addVol(Map<String, Vol> m, File root, String name, String kind) {
        if (root == null) return;
        String key;
        try {
            key = root.getCanonicalPath();
        } catch (Exception e) {
            key = root.getAbsolutePath();
        }
        if (m.containsKey(key)) return;
        for (Vol v : m.values()) {                   // same drive seen through another path
            try {
                if (v.total > 0 && sameFs(v.root, root) && !"phone".equals(kind)) return;
            } catch (Exception ignored) {
            }
        }
        Vol v = new Vol();
        v.root = root;
        v.name = name;
        v.kind = kind;
        try {
            StatFs s = new StatFs(root.getPath());
            v.total = s.getTotalBytes();
            v.free = s.getAvailableBytes();
        } catch (Exception ignored) {
        }
        if (!"phone".equals(kind) && v.total <= 0 && root.listFiles() == null) return;
        m.put(key, v);
    }

    private static boolean sameFs(File a, File b) {
        StatFs x = new StatFs(a.getPath()), y = new StatFs(b.getPath());
        return x.getTotalBytes() == y.getTotalBytes() && x.getAvailableBytes() == y.getAvailableBytes() && x.getTotalBytes() > 0;
    }

    private static File volumeDir(StorageVolume v) {
        if (Build.VERSION.SDK_INT >= 30) return v.getDirectory();
        try {
            Object p = StorageVolume.class.getMethod("getPathFile").invoke(v);
            if (p instanceof File) return (File) p;
        } catch (Exception ignored) {
        }
        try {
            Object p = StorageVolume.class.getMethod("getPath").invoke(v);
            if (p instanceof String) return new File((String) p);
        } catch (Exception ignored) {
        }
        return null;
    }

    private Vol volumeOf(File f) {
        Vol best = null;
        String p = f.getAbsolutePath();
        for (Vol v : vols) {
            String r = v.root.getAbsolutePath();
            if ((p.equals(r) || p.startsWith(r + "/")) && (best == null || r.length() > best.root.getAbsolutePath().length())) best = v;
        }
        return best;
    }

    // ================================================================== a folder

    private void open(File dir) {
        if (vols.isEmpty()) vols.addAll(findVolumes());
        cur = dir;
        curVol = volumeOf(dir);
        sel.clear();
        closeSearch();
        homeScroll.setVisibility(View.GONE);
        refreshBars();
        showCrumbs();
        load(dir);
    }

    private void load(final File dir) {
        final int seq = ++loadSeq;
        final boolean hidden = Store.getBool("fm_hidden", false);
        final int sort = Store.getInt("fm_sort", 0);
        final boolean asc = Store.getBool("fm_asc", true);
        busy.setVisibility(View.VISIBLE);
        new Thread(() -> {
            File[] kids = dir.listFiles();
            final List<Entry> out = new ArrayList<>();
            if (kids != null) {
                boolean count = kids.length < 3000;
                for (File k : kids) {
                    if (!hidden && k.getName().startsWith(".")) continue;
                    Entry e = new Entry();
                    e.f = k;
                    e.name = k.getName();
                    e.dir = k.isDirectory();
                    int dot = e.name.lastIndexOf('.');
                    e.ext = !e.dir && dot > 0 ? e.name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
                    e.size = e.dir ? 0 : k.length();
                    e.mod = k.lastModified();
                    if (e.dir && count) {
                        String[] l = k.list();
                        e.count = l == null ? -1 : l.length;
                    }
                    out.add(e);
                }
            }
            Collections.sort(out, comparator(sort, asc));
            final boolean unreadable = kids == null;
            main.post(() -> {
                if (seq != loadSeq) return;
                busy.setVisibility(View.GONE);
                all.clear();
                all.addAll(out);
                sel.retainAll(files(all));
                applyFilter();
                if (unreadable) {
                    empty.setText(hasAccess() ? T.t("This folder can't be opened.") : T.t("Allow “All files access” to open this folder."));
                    empty.setVisibility(View.VISIBLE);
                }
            });
        }).start();
    }

    private static List<File> files(List<Entry> l) {
        List<File> f = new ArrayList<>();
        for (Entry e : l) f.add(e.f);
        return f;
    }

    private static Comparator<Entry> comparator(final int sort, final boolean asc) {
        return (a, b) -> {
            if (a.dir != b.dir) return a.dir ? -1 : 1;          // folders first
            int r;
            if (sort == 1) r = Long.compare(a.mod, b.mod);
            else if (sort == 2) r = a.dir ? Integer.compare(a.count, b.count) : Long.compare(a.size, b.size);
            else if (sort == 3) r = a.ext.compareTo(b.ext);
            else r = 0;
            if (r == 0) r = a.name.compareToIgnoreCase(b.name);
            return asc ? r : -r;
        };
    }

    private void applyFilter() {
        shown.clear();
        for (Entry e : all) if (filter.isEmpty() || e.name.toLowerCase(Locale.ROOT).contains(filter)) shown.add(e);
        boolean g = Store.getBool("fm_grid", false);
        if (cur != null) {
            list.setVisibility(g ? View.GONE : View.VISIBLE);
            grid.setVisibility(g ? View.VISIBLE : View.GONE);
        }
        adapter.notifyDataSetChanged();
        if (cur != null) {
            empty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
            empty.setText(filter.isEmpty() ? T.t("This folder is empty.") : T.t("Nothing matches") + " “" + filter + "”");
        }
    }

    private void openSearch() {
        searchBar.setVisibility(View.VISIBLE);
        searchBox.requestFocus();
        android.view.inputmethod.InputMethodManager im = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (im != null) im.showSoftInput(searchBox, 0);
    }

    private void closeSearch() {
        if (searchBar == null) return;
        searchBar.setVisibility(View.GONE);
        if (!filter.isEmpty()) {
            filter = "";
            searchBox.setText("");
        }
    }

    // ================================================================== list / grid cells

    private static final int TYPE_DIR = 0, TYPE_VIDEO = 1, TYPE_AUDIO = 2, TYPE_IMAGE = 3, TYPE_ARCHIVE = 4, TYPE_APK = 5, TYPE_DOC = 6, TYPE_OTHER = 7;

    private static int typeOf(Entry e) {
        if (e.dir) return TYPE_DIR;
        String x = e.ext;
        if (Formats.isVideo(e.name)) return TYPE_VIDEO;
        if (Formats.isAudio(e.name)) return TYPE_AUDIO;
        if (x.matches("jpg|jpeg|png|gif|webp|bmp|heic|heif")) return TYPE_IMAGE;
        if (x.matches("zip|rar|7z|tar|gz|tgz|bz2|xz")) return TYPE_ARCHIVE;
        if (x.equals("apk") || x.equals("apks")) return TYPE_APK;
        if (x.matches("pdf|txt|doc|docx|xls|xlsx|ppt|pptx|odt|rtf|md|csv|srt|vtt|ass|json|xml|html|log")) return TYPE_DOC;
        return TYPE_OTHER;
    }

    private static final String[] ICONS = {"folder", "film", "file-music", "image", "file-archive", "smartphone", "file-text", "file"};
    private static final int[] TINTS = {0xFFF5B942, 0xFF60A5FA, 0xFFF472B6, 0xFF34D399, 0xFFFBBF24, 0xFFA3E635, 0xFFCBD5E1, 0xFF94A3B8};

    private final class Holder {
        FrameLayout thumbBox;
        ImageView thumb, check;
        TextView name, sub;
        View more;
        boolean gridCell;
    }

    private final class Adapter extends BaseAdapter {
        public int getCount() {
            return shown.size();
        }

        public Object getItem(int p) {
            return shown.get(p);
        }

        public long getItemId(int p) {
            return p;
        }

        public View getView(int pos, View v, ViewGroup parent) {
            boolean g = parent instanceof GridView;
            Holder h = v == null ? null : (Holder) v.getTag();
            if (h == null || h.gridCell != g) v = g ? gridCell() : listRow();
            h = (Holder) v.getTag();
            final Entry e = shown.get(pos);
            int type = typeOf(e);
            boolean picked = sel.contains(e.f);
            v.setBackground(picked ? Ui.card(StorageActivity.this, 0x338B5CF6, Ui.ACCENT, 14) : Ui.focusCard(StorageActivity.this, 14));
            h.name.setText(e.name);
            String date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(e.mod));
            if (e.dir) h.sub.setText((e.count >= 0 ? e.count + " " + T.t("items") + "  ·  " : "") + date);
            else h.sub.setText(size(e.size) + "  ·  " + date);
            h.check.setVisibility(sel.isEmpty() ? View.GONE : View.VISIBLE);
            h.check.setImageDrawable(new UiKit.Icon(StorageActivity.this, picked ? "check-square" : "square", 18, picked ? Ui.ACCENT : SUB));
            setThumb(h.thumb, e, type, g ? 40 : 26);
            if (h.more != null) h.more.setOnClickListener(x -> itemMenu(e));
            return v;
        }
    }

    private View listRow() {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(10), dp(8), dp(4), dp(8));
        Holder h = new Holder();
        h.thumbBox = new FrameLayout(this);
        h.thumbBox.setBackground(Ui.card(this, 0x14FFFFFF, 0, 10));
        h.thumb = new ImageView(this);
        h.thumb.setScaleType(ImageView.ScaleType.CENTER);
        h.thumbBox.addView(h.thumb, new FrameLayout.LayoutParams(-1, -1));
        h.check = new ImageView(this);
        h.thumbBox.addView(h.check, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP | Gravity.START));
        r.addView(h.thumbBox, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), 0, dp(4), 0);
        h.name = text(15, Color.WHITE, false);
        h.name.setMaxLines(2);
        h.name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        h.sub = text(12, SUB, false);
        h.sub.setSingleLine(true);
        col.addView(h.name);
        col.addView(h.sub);
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView more = UiKit.darkButton(this, "ellipsis-vertical", 36, false, null);
        more.setFocusable(false);                     // TV: the row is focused; ☰ opens the menu
        r.addView(more, new LinearLayout.LayoutParams(dp(36), dp(36)));
        h.more = more;
        r.setTag(h);
        return r;
    }

    private View gridCell() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(dp(6), dp(8), dp(6), dp(8));
        Holder h = new Holder();
        h.gridCell = true;
        h.thumbBox = new FrameLayout(this);
        h.thumbBox.setBackground(Ui.card(this, 0x14FFFFFF, 0, 10));
        h.thumb = new ImageView(this);
        h.thumb.setScaleType(ImageView.ScaleType.CENTER);
        h.thumbBox.addView(h.thumb, new FrameLayout.LayoutParams(-1, -1));
        h.check = new ImageView(this);
        h.thumbBox.addView(h.check, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP | Gravity.START));
        c.addView(h.thumbBox, new LinearLayout.LayoutParams(-1, dp(80)));
        h.name = text(12, Color.WHITE, false);
        h.name.setMaxLines(2);
        h.name.setGravity(Gravity.CENTER);
        h.name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        h.name.setPadding(0, dp(6), 0, 0);
        h.sub = text(10, SUB, false);
        h.sub.setSingleLine(true);
        h.sub.setGravity(Gravity.CENTER);
        c.addView(h.name, new LinearLayout.LayoutParams(-1, -2));
        c.addView(h.sub, new LinearLayout.LayoutParams(-1, -2));
        c.setTag(h);
        return c;
    }

    // ---- thumbnails: pictures, video frames, album art (cached, loaded off the main thread)
    private void setThumb(final ImageView iv, final Entry e, int type, int iconDp) {
        final String key = e.f.getAbsolutePath() + ":" + e.mod;
        iv.setTag(key);
        Bitmap b = thumbCache.get(key);
        if (b != null) {
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageBitmap(b);
            return;
        }
        iv.setScaleType(ImageView.ScaleType.CENTER);
        iv.setImageDrawable(new UiKit.Icon(this, ICONS[type], iconDp, TINTS[type]));
        if (type != TYPE_IMAGE && type != TYPE_VIDEO && type != TYPE_AUDIO) return;
        final int t = type;
        try {
            thumbs.execute(() -> {
                Bitmap bm = makeThumb(e.f, t);
                if (bm == null) return;
                thumbCache.put(key, bm);
                main.post(() -> {
                    if (key.equals(iv.getTag())) {
                        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        iv.setImageBitmap(bm);
                    }
                });
            });
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    private static Bitmap makeThumb(File f, int type) {
        try {
            if (type == TYPE_IMAGE) {
                if (Build.VERSION.SDK_INT >= 29) return android.media.ThumbnailUtils.createImageThumbnail(f, new android.util.Size(160, 160), null);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(f.getPath(), o);
                int s = 1;
                while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= 160) s *= 2;
                o = new BitmapFactory.Options();
                o.inSampleSize = s;
                return BitmapFactory.decodeFile(f.getPath(), o);
            }
            if (type == TYPE_VIDEO) {
                if (Build.VERSION.SDK_INT >= 29) return android.media.ThumbnailUtils.createVideoThumbnail(f, new android.util.Size(160, 160), null);
                return android.media.ThumbnailUtils.createVideoThumbnail(f.getPath(), android.provider.MediaStore.Images.Thumbnails.MINI_KIND);
            }
            if (type == TYPE_AUDIO) {
                android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
                try {
                    r.setDataSource(f.getPath());
                    byte[] art = r.getEmbeddedPicture();
                    if (art == null) return null;
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inSampleSize = 4;
                    return BitmapFactory.decodeByteArray(art, 0, art.length, o);
                } finally {
                    try {
                        r.release();
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ================================================================== tapping & opening

    private void tap(Entry e) {
        if (!sel.isEmpty()) {
            toggle(e.f);
            return;
        }
        if (e.dir) {
            open(e.f);
            return;
        }
        int type = typeOf(e);
        if (type == TYPE_VIDEO || type == TYPE_AUDIO) {
            // the whole folder plays as a playlist, starting with this one
            List<Store.Item> items = new ArrayList<>();
            int start = 0;
            for (Entry x : all) {
                int t = typeOf(x);
                if (t != TYPE_VIDEO && t != TYPE_AUDIO) continue;
                if (x.f.equals(e.f)) start = items.size();
                items.add(new Store.Item(x.name, x.f.getAbsolutePath(), ""));
            }
            Store.addRecent(new Store.Item(e.name, e.f.getAbsolutePath(), ""));
            Remote.play(this, items, start);
            return;
        }
        openWith(e.f, false);
    }

    private Uri uri(File f) {
        return androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".files", f);
    }

    private static String mime(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        String ext = dot > 0 ? n.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        if (ext.equals("apk")) return "application/vnd.android.package-archive";
        String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return m == null ? "*/*" : m;
    }

    private void openWith(File f, boolean choose) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(uri(f), mime(f))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(choose ? Intent.createChooser(i, T.t("Open with")) : i);
        } catch (Exception e) {
            if (!choose) openWith(f, true);
            else toast(T.t("No app can open this file"));
        }
    }

    private void share(List<File> files) {
        List<File> ok = new ArrayList<>();
        for (File f : files) if (f.isFile()) ok.add(f);
        if (ok.isEmpty()) {
            toast(T.t("Folders can't be shared — pick files"));
            return;
        }
        try {
            Intent i;
            if (ok.size() == 1) {
                i = new Intent(Intent.ACTION_SEND).setType(mime(ok.get(0))).putExtra(Intent.EXTRA_STREAM, uri(ok.get(0)));
            } else {
                ArrayList<Uri> us = new ArrayList<>();
                for (File f : ok) us.add(uri(f));
                i = new Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, us);
            }
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, T.t("Share")));
        } catch (Exception e) {
            toast("⚠ " + e.getMessage());
        }
    }

    // ================================================================== selection

    private void toggle(File f) {
        if (!sel.remove(f)) sel.add(f);
        refreshBars();
        adapter.notifyDataSetChanged();
    }

    private void selectAll() {
        if (sel.size() == shown.size()) sel.clear();
        else for (Entry e : shown) sel.add(e.f);
        refreshBars();
        adapter.notifyDataSetChanged();
    }

    private void selectionMenu() {
        final List<File> picked = new ArrayList<>(sel);
        List<String> l = new ArrayList<>();
        l.add("📤  " + T.t("Share"));
        if (picked.size() == 1) l.add("✏  " + T.t("Rename"));
        l.add("ℹ  " + T.t("Properties"));
        new UiDialog(this).setTitle(picked.size() + " " + T.t("selected")).setItems(l.toArray(new String[0]), (d, w) -> {
            String c = l.get(w);
            if (c.contains(T.t("Share"))) share(picked);
            else if (c.contains(T.t("Rename"))) rename(picked.get(0));
            else properties(picked);
        }).show();
    }

    private void itemMenu(final Entry e) {
        final List<String> l = new ArrayList<>();
        final List<Runnable> a = new ArrayList<>();
        l.add(e.dir ? "📂  " + T.t("Open") : "▶  " + T.t("Open"));
        a.add(() -> tap(e));
        if (!e.dir) {
            l.add("🧩  " + T.t("Open with…"));
            a.add(() -> openWith(e.f, true));
            l.add("📤  " + T.t("Share"));
            a.add(() -> share(Collections.singletonList(e.f)));
        }
        l.add("☑  " + T.t("Select"));
        a.add(() -> toggle(e.f));
        l.add("📋  " + T.t("Copy"));
        a.add(() -> toClip(Collections.singletonList(e.f), false));
        l.add("✂  " + T.t("Move"));
        a.add(() -> toClip(Collections.singletonList(e.f), true));
        l.add("✏  " + T.t("Rename"));
        a.add(() -> rename(e.f));
        l.add("🗑  " + T.t("Delete"));
        a.add(() -> confirmDelete(Collections.singletonList(e.f)));
        l.add("ℹ  " + T.t("Properties"));
        a.add(() -> properties(Collections.singletonList(e.f)));
        new UiDialog(this).setTitle(e.name).setItems(l.toArray(new String[0]), (d, w) -> a.get(w).run()).show();
    }

    private void viewMenu() {
        final int sort = Store.getInt("fm_sort", 0);
        final boolean asc = Store.getBool("fm_asc", true), g = Store.getBool("fm_grid", false), hid = Store.getBool("fm_hidden", false);
        String[] sorts = {T.t("Name"), T.t("Date"), T.t("Size"), T.t("Type")};
        String[] items = {
                "⇅  " + T.t("Sort by") + ": " + sorts[sort],
                (asc ? "↑  " : "↓  ") + T.t("Order") + ": " + (asc ? T.t("A → Z / oldest first") : T.t("Z → A / newest first")),
                (g ? "☰  " + T.t("List view") : "▦  " + T.t("Grid view")),
                (hid ? "🙈  " + T.t("Hide hidden files") : "👁  " + T.t("Show hidden files")),
                "☑  " + T.t("Select all"),
                "🔄  " + T.t("Refresh")};
        new UiDialog(this).setTitle(T.t("View")).setItems(items, (d, w) -> {
            if (w == 0) {
                new UiDialog(this).setTitle(T.t("Sort by")).setItems(sorts, (d2, k) -> {
                    Store.putInt("fm_sort", k);
                    if (k == 1 || k == 2) Store.putBool("fm_asc", false);   // newest / biggest first
                    load(cur);
                }).show();
            } else if (w == 1) {
                Store.putBool("fm_asc", !asc);
                load(cur);
            } else if (w == 2) {
                Store.putBool("fm_grid", !g);
                applyFilter();
            } else if (w == 3) {
                Store.putBool("fm_hidden", !hid);
                load(cur);
            } else if (w == 4) {
                selectAll();
            } else {
                load(cur);
            }
        }).show();
    }

    // ================================================================== file operations

    private View pad(View v) {
        FrameLayout f = new FrameLayout(this);
        f.setPadding(dp(20), dp(8), dp(20), 0);
        f.addView(v);
        return f;
    }

    private static boolean badName(String n) {
        return n.isEmpty() || n.equals(".") || n.equals("..") || n.contains("/") || n.contains("\u0000");
    }

    private void newFolder() {
        final EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setHint(T.t("Folder name"));
        new UiDialog(this).setTitle("📁  " + T.t("New folder")).setView(pad(e))
                .setPositiveButton(T.t("Create"), (d, w) -> {
                    String n = e.getText().toString().trim();
                    if (badName(n)) {
                        toast(T.t("That name can't be used"));
                        return;
                    }
                    File f = new File(cur, n);
                    if (f.exists()) toast(T.t("Something with that name is already here"));
                    else if (f.mkdirs()) {
                        toast("📁 " + T.t("Folder created"));
                        load(cur);
                    } else toast(T.t("Couldn't create it — the drive may be read-only"));
                })
                .setNegativeButton(T.t("Cancel"), null).show();
    }

    private void rename(final File f) {
        final EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setText(f.getName());
        int dot = f.getName().lastIndexOf('.');
        e.setSelection(0, f.isFile() && dot > 0 ? dot : f.getName().length());   // keep the extension
        new UiDialog(this).setTitle("✏  " + T.t("Rename")).setView(pad(e))
                .setPositiveButton(T.t("Save"), (d, w) -> {
                    String n = e.getText().toString().trim();
                    if (badName(n) || n.equals(f.getName())) return;
                    File to = new File(f.getParentFile(), n);
                    if (to.exists()) toast(T.t("Something with that name is already here"));
                    else if (f.renameTo(to)) {
                        sel.clear();
                        refreshBars();
                        load(cur);
                    } else toast(T.t("Couldn't rename — the drive may be read-only"));
                })
                .setNegativeButton(T.t("Cancel"), null).show();
    }

    private void toClip(List<File> files, boolean move) {
        clip.clear();
        clip.addAll(files);
        clipMove = move;
        sel.clear();
        adapter.notifyDataSetChanged();
        refreshBars();
        toast((move ? "✂ " : "📋 ") + T.t("Open the folder to put them in, then tap Paste"));
    }

    private void confirmDelete(final List<File> files) {
        String what = files.size() == 1 ? files.get(0).getName() : files.size() + " " + T.t("items");
        new UiDialog(this).setTitle("🗑  " + T.t("Delete") + "?")
                .setMessage(what + "\n\n" + T.t("This can't be undone."))
                .setPositiveButton(T.t("Delete"), (d, w) -> run(T.t("Deleting…"), files, null, false, true))
                .setNegativeButton(T.t("Cancel"), null).show();
    }

    private void paste() {
        if (clip.isEmpty() || cur == null) return;
        for (File f : clip) {
            if (f.isDirectory() && (cur.getAbsolutePath() + "/").startsWith(f.getAbsolutePath() + "/")) {
                toast(T.t("A folder can't be put inside itself"));
                return;
            }
        }
        List<File> src = new ArrayList<>(clip);
        boolean move = clipMove;
        clip.clear();
        run(move ? T.t("Moving…") : T.t("Copying…"), src, cur, move, false);
    }

    /** Copy / move / delete in the background with a progress dialog and Cancel. */
    private void run(final String label, final List<File> src, final File dest, final boolean move, final boolean delete) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(10), dp(20), dp(4));
        final TextView now = text(13, SUB, false);
        now.setSingleLine(true);
        now.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        box.addView(now);
        final ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(8));
        blp.setMargins(0, dp(10), 0, dp(6));
        box.addView(bar, blp);
        final TextView amount = text(12, SUB, false);
        box.addView(amount);
        final boolean[] cancel = {false};
        final android.app.Dialog dlg = new UiDialog(this).setTitle(label).setView(box)
                .setNegativeButton(T.t("Cancel"), (d, w) -> cancel[0] = true).show();
        dlg.setCancelable(false);
        new Thread(() -> {
            final long total = delete ? 0 : totalSize(src);
            final long[] done = {0};
            final long[] lastUi = {0};
            String error = null;
            int count = 0;
            try {
                for (File f : src) {
                    if (cancel[0]) break;
                    if (delete) {
                        if (!deleteAll(f, cancel)) error = T.t("Some items couldn't be deleted (read-only drive?)");
                    } else {
                        File to = freeName(new File(dest, f.getName()));
                        if (move && f.renameTo(to)) {                 // same drive: instant
                            count++;
                            continue;
                        }
                        copyAll(f, to, cancel, (name, bytes) -> {
                            done[0] += bytes;
                            long t = System.currentTimeMillis();
                            if (t - lastUi[0] > 150) {
                                lastUi[0] = t;
                                final long d = done[0];
                                main.post(() -> {
                                    now.setText(name);
                                    bar.setProgress(total > 0 ? (int) (1000L * d / total) : 0);
                                    amount.setText(size(d) + " / " + size(total));
                                });
                            }
                        });
                        if (move && !cancel[0]) deleteAll(f, cancel);
                    }
                    count++;
                }
            } catch (Exception e) {
                error = e.getMessage();
            }
            final String err = error;
            final int n = count;
            main.post(() -> {
                try {
                    dlg.dismiss();
                } catch (Exception ignored) {
                }
                if (cancel[0]) toast(T.t("Stopped"));
                else if (err != null) toast("⚠ " + err);
                else toast("✓ " + n + " " + T.t(delete ? "deleted" : move ? "moved" : "copied"));
                sel.clear();
                refreshBars();
                if (cur != null) load(cur);
            });
        }).start();
    }

    private interface Progress {
        void add(String name, long bytes);
    }

    private static long totalSize(List<File> l) {
        long t = 0;
        for (File f : l) t += sizeOf(f, 0);
        return t;
    }

    private static long sizeOf(File f, int depth) {
        if (f.isFile()) return f.length();
        File[] k = f.listFiles();
        if (k == null || depth > 64) return 0;
        long t = 0;
        for (File x : k) t += sizeOf(x, depth + 1);
        return t;
    }

    private static File freeName(File f) {
        if (!f.exists()) return f;
        String n = f.getName(), base = n, ext = "";
        int dot = n.lastIndexOf('.');
        if (f.isFile() && dot > 0) {
            base = n.substring(0, dot);
            ext = n.substring(dot);
        }
        for (int i = 1; ; i++) {
            File c = new File(f.getParentFile(), base + " (" + i + ")" + ext);
            if (!c.exists()) return c;
        }
    }

    private static void copyAll(File from, File to, boolean[] cancel, Progress p) throws Exception {
        if (cancel[0]) return;
        if (from.isDirectory()) {
            if (!to.mkdirs() && !to.isDirectory()) throw new Exception("Can't create " + to.getName() + " (read-only drive?)");
            File[] k = from.listFiles();
            if (k != null) for (File x : k) copyAll(x, new File(to, x.getName()), cancel, p);
            return;
        }
        byte[] buf = new byte[256 * 1024];
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                if (cancel[0]) break;
                out.write(buf, 0, n);
                p.add(from.getName(), n);
            }
        }
        if (cancel[0]) {
            //noinspection ResultOfMethodCallIgnored
            to.delete();                                   // don't leave half a file behind
        } else {
            //noinspection ResultOfMethodCallIgnored
            to.setLastModified(from.lastModified());
        }
    }

    private static boolean deleteAll(File f, boolean[] cancel) {
        if (cancel[0]) return true;
        boolean ok = true;
        if (f.isDirectory()) {
            File[] k = f.listFiles();
            if (k != null) for (File x : k) ok &= deleteAll(x, cancel);
        }
        return f.delete() && ok;
    }

    private void properties(final List<File> files) {
        final TextView t = text(14, Color.WHITE, false);
        t.setPadding(dp(20), dp(10), dp(20), 0);
        t.setTextIsSelectable(true);
        final StringBuilder sb = new StringBuilder();
        if (files.size() == 1) {
            File f = files.get(0);
            sb.append(T.t("Name")).append(": ").append(f.getName()).append("\n");
            sb.append(T.t("Where")).append(": ").append(f.getParent()).append("\n");
            sb.append(T.t("Changed")).append(": ").append(DateFormat.getDateTimeInstance().format(new Date(f.lastModified()))).append("\n");
            if (f.isFile()) sb.append(T.t("Type")).append(": ").append(mime(f)).append("\n");
            sb.append(f.canWrite() ? T.t("Can be changed") : T.t("Read-only")).append("\n");
        } else {
            sb.append(files.size()).append(" ").append(T.t("items")).append("\n");
        }
        t.setText(sb + T.t("Size") + ": " + T.t("counting…"));
        new UiDialog(this).setTitle("ℹ  " + T.t("Properties")).setView(t).setPositiveButton(T.t("OK"), null).show();
        new Thread(() -> {
            long total = 0;
            int filesN = 0, dirs = 0;
            List<File> stack = new ArrayList<>(files);
            while (!stack.isEmpty() && filesN + dirs < 200_000) {
                File f = stack.remove(stack.size() - 1);
                if (f.isDirectory()) {
                    dirs++;
                    File[] k = f.listFiles();
                    if (k != null) Collections.addAll(stack, k);
                } else {
                    filesN++;
                    total += f.length();
                }
            }
            final String s = sb + T.t("Size") + ": " + size(total) + "  (" + total + " bytes)\n"
                    + filesN + " " + T.t("files") + (dirs > 0 ? ", " + dirs + " " + T.t("folders") : "");
            main.post(() -> t.setText(s));
        }).start();
    }
}
