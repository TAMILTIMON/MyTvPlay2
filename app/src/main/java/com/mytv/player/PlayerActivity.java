package com.mytv.player;

import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IMedia;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class PlayerActivity extends AppCompatActivity {
    public static final String EXTRA_HANDOFF = "handoff";
    public static final String EXTRA_PLAYLIST = "playlist";
    public static final String EXTRA_INDEX = "index";

    // ---------- choices (shared with the phone page) ----------
    static final String[] ASPECT_VALUES = {"", "16:9", "4:3", "21:9", "235:100", "239:100", "185:100", "2:1",
            "16:10", "5:4", "1:1", "3:2", "32:9"};
    static final String[] ASPECT_NAMES = {T.t("Auto (from video)"), "16:9", "4:3", "21:9 (ultrawide)",
            "2.35:1 (CinemaScope)", "2.39:1 (anamorphic)", "1.85:1 (widescreen film)", "2:1 (18:9)", "16:10",
            "5:4", "1:1 (square)", "3:2", "32:9"};
    static final String[] FIT_NAMES = {T.t("Best fit (whole picture)"), T.t("Fill screen (zoom, no black bars)"),
            T.t("Stretch to full screen"), T.t("Original size")};

    static final int[] ZOOM_VALUES = {100, 104, 132, 134, 133, 110, 120, 125, 150, 175, 200};
    static final String[] ZOOM_NAMES = {"100% (no zoom)", "104% – crop bars of 1.85:1 film",
            "132% – crop bars of 21:9 / 2.35:1 film", "134% – crop bars of 2.39:1 film",
            "133% – crop side bars of 4:3 video", "110%", "120%", "125%", "150%", "175%", "200%"};
    static final int[] SUB_SIZES = {24, 20, 16, 12, 9};
    static final String[] SUB_SIZE_NAMES = {T.t("Tiny"), T.t("Small"), T.t("Normal"), T.t("Large"), T.t("Huge")};
    static final String[] ENC_VALUES = {"", "UTF-8", "Windows-1252", "Windows-1256", "Windows-1251",
            "Windows-1250", "ISO-8859-9", "ISO-8859-7", "Windows-1255", "GB18030", "Big5", "Shift_JIS", "EUC-KR"};
    static final String[] ENC_NAMES = {T.t("Automatic"), "UTF-8", "Western (Windows-1252)", "Arabic (Windows-1256)",
            "Cyrillic (Windows-1251)", "Central European (Windows-1250)", "Turkish (ISO-8859-9)",
            "Greek (ISO-8859-7)", "Hebrew (Windows-1255)", "Chinese simplified (GB18030)",
            "Chinese traditional (Big5)", "Japanese (Shift_JIS)", "Korean (EUC-KR)"};
    static final float[] SPEEDS = {0.25f, 0.5f, 0.75f, 0.9f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f, 3f, 4f};
    static final int[] CACHES = {500, 1000, 2000, 5000, 10000, 20000};
    static final int[] SLEEPS = {0, 15, 30, 45, 60, 90, 120, -1};
    static final int[] VOLS = {25, 50, 75, 100, 125, 150, 175, 200};
    static final String[] DEFAULT_PRESETS = {"Flat", "Classical", "Club", "Dance", "Full bass",
            "Full bass and treble", "Full treble", "Headphones", "Large Hall", "Live", "Party", "Pop", "Reggae",
            "Rock", "Ska", "Soft", "Soft rock", "Techno"};
    static final float[] DEFAULT_FREQS = {60, 170, 310, 600, 1000, 3000, 6000, 12000, 14000, 16000};

    static volatile String[] PRESETS;
    static volatile float[] BAND_FREQS;

    // ---------- engine ----------
    private LibVLC libVLC;
    private MediaPlayer mp;
    private boolean viewsAttached;
    private ParcelFileDescriptor pfd;

    // ---------- UI ----------
    private FrameLayout root;
    private VLCVideoLayout videoLayout;
    private LinearLayout panel;
    private TextView titleView, timeView, infoView, osdView, loadingView;
    private ProgressBar progress;
    private boolean overlayVisible;

    // ---------- state ----------
    private ArrayList<Store.Item> queue = new ArrayList<>();
    private int index = -1;
    private final ArrayList<String> addedSubs = new ArrayList<>();
    private int volume = 100;
    private boolean muted;
    private float rate = 1f;
    private long subDelayMs = 0;
    private long sleepAt = 0;
    private boolean sleepAfterItem;
    private boolean torrentUsed;
    private int resolveToken = 0;
    private int pendingAudio = Integer.MIN_VALUE, pendingSpu = Integer.MIN_VALUE;
    private long seekTarget = -1, seekAt = 0;
    private long lastSave = 0;
    private boolean ended;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable hideOverlay = new Runnable() {
        @Override
        public void run() {
            if (mp != null && !mp.isPlaying()) return; // keep it visible while paused
            hideOverlayNow();
        }
    };
    private final Runnable hideOsd = new Runnable() {
        @Override
        public void run() {
            osdView.setVisibility(View.GONE);
        }
    };
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            onTick();
            handler.postDelayed(this, 500);
        }
    };

    // =====================================================================
    // Lifecycle
    // =====================================================================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Ui.apply(this);
        setTheme(R.style.PlayerTheme);
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();
        buildUi();
        applyRotation();
        volume = Store.getInt("vol", 100);
        try {
            createEngine();
        } catch (Throwable t) {
            Toast.makeText(this, "Could not start the video engine: " + t, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        Remote.player = this;
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        savePosition();
        handleIntent(intent);
    }

    private void handleIntent(Intent in) {
        ArrayList<Store.Item> list = null;
        int start = in.getIntExtra(EXTRA_INDEX, 0);
        if (in.getBooleanExtra(EXTRA_HANDOFF, false)) {
            list = Remote.takeHandoff();
        } else if (in.getStringExtra(EXTRA_PLAYLIST) != null) {
            list = new ArrayList<>(Store.items(in.getStringExtra(EXTRA_PLAYLIST)));
        } else if (in.getData() != null) {
            Uri u = in.getData();
            String title = in.getStringExtra("title");
            if ("content".equals(u.getScheme()) && ("application/x-bittorrent".equals(in.getType())
                    || LocalShare.displayName(this, u).toLowerCase(Locale.US).endsWith(".torrent"))) {
                File tf = copyToCache(u, LocalShare.displayName(this, u));
                if (tf != null) u = Uri.fromFile(tf);
            }
            String url = "file".equals(u.getScheme()) ? u.getPath() : u.toString();
            Store.Item it = new Store.Item(title, url, "");
            if (!"content".equals(u.getScheme())) Store.addRecent(it);
            list = new ArrayList<>();
            list.add(it);
            start = 0;
        }
        if (list == null || list.isEmpty()) {
            if (queue.isEmpty()) finish();
            return;
        }
        queue = list;
        if (start < 0 || start >= queue.size()) start = 0;
        playIndex(start, true);
    }

    private File copyToCache(Uri u, String name) {
        try (java.io.InputStream in = getContentResolver().openInputStream(u)) {
            File dir = new File(getCacheDir(), "torrent_files");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            if (!name.toLowerCase(Locale.US).endsWith(".torrent")) name = name + ".torrent";
            File f = new File(dir, name.replaceAll("[^A-Za-z0-9._ -]", "_"));
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) {
                byte[] b = new byte[16384];
                int n;
                while (in != null && (n = in.read(b)) > 0) o.write(b, 0, n);
            }
            return f;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (mp != null && !viewsAttached) {
            mp.attachViews(videoLayout, null, true, false);
            viewsAttached = true;
            applyFit();
        }
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacks(tick);
        savePosition();
        if (mp != null) {
            mp.pause();
            if (viewsAttached) {
                mp.detachViews();
                viewsAttached = false;
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (Remote.player == this) Remote.player = null;
        resolveToken++;
        releaseEngine();
        if (torrentUsed && isFinishing() && Remote.player == null) TorrentEngine.get(this).stopAsync();
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    // =====================================================================
    // UI
    // =====================================================================
    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(float sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setShadowLayer(4, 0, 0, Color.BLACK);
        return t;
    }

    private LinearLayout topBar;
    private Button playBtn;
    private Button gearBtn;

    private Button repeatBtn, shuffleBtn, unlockBtn;
    private boolean locked;
    private final java.util.Random rng = new java.util.Random();
    private final Runnable hideUnlock = new Runnable() {
        public void run() {
            if (unlockBtn != null) unlockBtn.setVisibility(View.GONE);
        }
    };

    private void flashUnlock() {
        unlockBtn.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideUnlock);
        handler.postDelayed(hideUnlock, 3000);
        osd(T.t("Screen locked — tap 🔓 to unlock"));
    }

    private int repeatMode() {
        return Store.getInt("repeat", 0);
    }

    private boolean shuffleOn() {
        return Store.getBool("shuffle", false);
    }

    private void updateModeButtons() {
        if (repeatBtn == null) return;
        int rm = repeatMode();
        repeatBtn.setText(rm == 1 ? "🔂" : "🔁");
        repeatBtn.setAlpha(rm == 0 ? 0.45f : 1f);
        shuffleBtn.setAlpha(shuffleOn() ? 1f : 0.45f);
    }

    private String repeatName() {
        int rm = repeatMode();
        return T.t(rm == 1 ? "One" : rm == 2 ? "All" : "Off");
    }

    /** Phones: 0 = auto rotate, 1 = landscape (default), 2 = portrait. */
    private void applyRotation() {
        if (Ui.isTv(this)) return;
        int m = Store.getInt("rotate", 1);
        setRequestedOrientation(m == 0 ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                : m == 2 ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    private int randomOther() {
        if (queue.size() < 2) return index;
        int n;
        do n = rng.nextInt(queue.size()); while (n == index);
        return n;
    }

    private Button ctl(LinearLayout row, String label, float size, boolean primary, View.OnClickListener l) {
        Button b = Ui.iconButton(this, label, size, primary);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(size), dp(size));
        lp.setMargins(dp(7), 0, dp(7), 0);
        row.addView(b, lp);
        return b;
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setClipChildren(true);

        videoLayout = new VLCVideoLayout(this);
        root.addView(videoLayout, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        // tap anywhere (phones) = show / hide controls
        View touch = new View(this);
        touch.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (locked) {
                    flashUnlock();
                    return;
                }
                if (overlayVisible) hideOverlayNow();
                else showOverlay();
            }
        });
        touch.setFocusable(false);
        root.addView(touch, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        loadingView = text(20, Color.WHITE);
        loadingView.setGravity(Gravity.CENTER);
        loadingView.setPadding(dp(28), dp(16), dp(28), dp(16));
        GradientDrawable lbg = new GradientDrawable();
        lbg.setColor(0xB30E0A1C);
        lbg.setStroke(dp(1), 0x40A78BFA);
        lbg.setCornerRadius(dp(18));
        loadingView.setBackground(lbg);
        loadingView.setVisibility(View.GONE);
        root.addView(loadingView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        // ---- top bar: title + info + ⚙ in the corner
        topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(36), dp(22), dp(28), dp(36));
        topBar.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xCC000000, 0x00000000}));
        LinearLayout tcol = new LinearLayout(this);
        tcol.setOrientation(LinearLayout.VERTICAL);
        titleView = text(22, Color.WHITE);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tcol.addView(titleView);
        infoView = text(14, 0xFFC9C2E0);
        infoView.setPadding(0, dp(4), 0, 0);
        tcol.addView(infoView);
        topBar.addView(tcol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (!Ui.isTv(this)) {
            ctl(topBar, "🔄", 52, false, v -> command("rotate", "", ""));
            ctl(topBar, "🔒", 52, false, v -> command("lock", "1", ""));
        }
        ctl(topBar, "⌂", 52, false, new View.OnClickListener() {
            public void onClick(View v) {
                Ui.goHome(PlayerActivity.this, true);
            }
        });
        gearBtn = ctl(topBar, "⚙", 52, false, new View.OnClickListener() {
            public void onClick(View v) {
                showMenu();
            }
        });
        root.addView(topBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        osdView = text(19, Color.WHITE);
        osdView.setPadding(dp(20), dp(10), dp(20), dp(10));
        GradientDrawable obg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xE68B5CF6, 0xE6EC4899});
        obg.setCornerRadius(dp(22));
        osdView.setBackground(obg);
        osdView.setVisibility(View.GONE);
        FrameLayout.LayoutParams olp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        olp.setMargins(dp(32), dp(96), dp(32), 0);
        root.addView(osdView, olp);

        unlockBtn = Ui.iconButton(this, "🔓", 64, true);
        unlockBtn.setOnClickListener(v -> command("lock", "0", ""));
        unlockBtn.setVisibility(View.GONE);
        FrameLayout.LayoutParams ulp = new FrameLayout.LayoutParams(dp(64), dp(64), Gravity.START | Gravity.CENTER_VERTICAL);
        ulp.setMargins(dp(28), 0, 0, 0);
        root.addView(unlockBtn, ulp);

        // ---- bottom glass panel
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(28), dp(18), dp(28), dp(16));
        GradientDrawable pbg = new GradientDrawable();
        pbg.setColor(0xB3120D22);
        pbg.setStroke(dp(1), 0x33FFFFFF);
        pbg.setCornerRadius(dp(26));
        panel.setBackground(pbg);

        LinearLayout trow = new LinearLayout(this);
        trow.setGravity(Gravity.CENTER_VERTICAL);
        timeView = text(15, Color.WHITE);
        trow.addView(timeView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView hint = text(12, 0xFF9C94B8);
        hint.setText(Ui.isTv(this) ? T.t("◀ ▶ seek · ▼ buttons · ⚙ settings") : T.t("Tap video to hide"));
        trow.addView(hint);
        panel.addView(trow);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setFocusable(false);
        progress.setProgressDrawable(progressDrawable());
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
        plp.setMargins(0, dp(10), 0, dp(14));
        panel.addView(progress, plp);

        LinearLayout btns = new LinearLayout(this);
        btns.setGravity(Gravity.CENTER);
        ctl(btns, "⏮", 46, false, v -> { prev(); showOverlay(); });
        ctl(btns, "↺", 46, false, v -> seekBy(-10000));
        playBtn = ctl(btns, "⏯", 62, true, v -> togglePause());
        ctl(btns, "↻", 46, false, v -> seekBy(10000));
        ctl(btns, "⏭", 46, false, v -> { next(); showOverlay(); });
        View gap = new View(this);
        btns.addView(gap, new LinearLayout.LayoutParams(dp(18), 1));
        ctl(btns, "📺", 46, false, v -> menuQueue());
        ctl(btns, "🔊", 46, false, v -> menuAudio());
        ctl(btns, "💬", 46, false, v -> menuSubs());
        ctl(btns, "⛶", 46, false, v -> menuFit());
        ctl(btns, "🎚", 46, false, v -> menuEq());
        repeatBtn = ctl(btns, "🔁", 46, false, v -> command("repeat", "", ""));
        shuffleBtn = ctl(btns, "🔀", 46, false, v -> command("shuffle", "", ""));
        ctl(btns, "☰", 46, false, v -> showMenu());
        updateModeButtons();
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(btns);
        panel.addView(hs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        ((LinearLayout.LayoutParams) hs.getLayoutParams()).gravity = Gravity.CENTER_HORIZONTAL;

        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        int side = Ui.isTv(this) ? dp(48) : dp(10);
        panelLp.setMargins(side, 0, side, dp(Ui.isTv(this) ? 28 : 12));
        root.addView(panel, panelLp);
        setContentView(root);
        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                if (r - l != or - ol || b - t != ob - ot) applyPicture();
            }
        });
    }

    private android.graphics.drawable.Drawable progressDrawable() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x33FFFFFF);
        bg.setCornerRadius(dp(3));
        GradientDrawable fg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Ui.ACCENT, Ui.ACCENT2});
        fg.setCornerRadius(dp(3));
        android.graphics.drawable.ClipDrawable clip = new android.graphics.drawable.ClipDrawable(fg,
                Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL);
        android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{bg, clip});
        ld.setId(0, android.R.id.background);
        ld.setId(1, android.R.id.progress);
        return ld;
    }

    private boolean controlsFocused() {
        View f = getCurrentFocus();
        return overlayVisible && f instanceof Button;
    }

    private void hideOverlayNow() {
        handler.removeCallbacks(hideOverlay);
        panel.setVisibility(View.GONE);
        topBar.setVisibility(View.GONE);
        overlayVisible = false;
        View f = getCurrentFocus();
        if (f != null) f.clearFocus();
        root.requestFocus();
    }

    private void showOverlay() {
        if (locked) return;
        panel.setVisibility(View.VISIBLE);
        topBar.setVisibility(View.VISIBLE);
        overlayVisible = true;
        updateOverlay();
        handler.removeCallbacks(hideOverlay);
        handler.postDelayed(hideOverlay, controlsFocused() ? 9000 : 5000);
    }

    private void osd(String msg) {
        osdView.setText(msg);
        osdView.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideOsd);
        handler.postDelayed(hideOsd, 2500);
    }

    private void showLoading(String msg) {
        loadingView.setText(msg);
        loadingView.setVisibility(View.VISIBLE);
    }

    private void hideLoading() {
        loadingView.setVisibility(View.GONE);
    }

    private int trackCacheAge = 0;
    private String audioName = "", subName = "";

    private void updateOverlay() {
        if (mp == null) return;
        long t = mp.getTime(), len = mp.getLength();
        timeView.setText(len > 0 ? Net.fmtTime(t) + " / " + Net.fmtTime(len) + (mp.isPlaying() ? "" : T.t("   ❚❚ Paused"))
                : (mp.isPlaying() ? T.t("● Live  ") + Net.fmtTime(t) : T.t("❚❚ Paused")));
        progress.setProgress(len > 0 ? (int) (1000 * Math.max(0, t) / len) : 0);
        playBtn.setText(mp.isPlaying() ? "❚❚" : "▶");
        StringBuilder sb = new StringBuilder();
        if (queue.size() > 1) sb.append(index + 1).append('/').append(queue.size()).append("   ");
        if (trackCacheAge++ % 4 == 0) {
            audioName = trackName(mp.getAudioTracks(), mp.getAudioTrack());
            subName = trackName(mp.getSpuTracks(), mp.getSpuTrack());
        }
        sb.append("🔊 ").append(audioName);
        sb.append("   💬 ").append(subName);
        sb.append(T.t("   Vol ")).append(muted ? "muted" : volume + "%");
        if (rate != 1f) sb.append("   ").append(fmtRate(rate));
        if (sleepAt > 0 || sleepAfterItem) sb.append(T.t("   Sleep ")).append(sleepLabel());
        String ts = torrentUsed ? TorrentEngine.get(this).shortStatus() : "";
        if (!ts.isEmpty()) sb.append("\n").append(ts);
        infoView.setText(sb.toString());
    }

    private int tickN = 0;

    private void onTick() {
        tickN++;
        if (overlayVisible && tickN % 2 == 0) updateOverlay();
        if (loadingView.getVisibility() == View.VISIBLE && torrentUsed && mp != null && !mp.isPlaying()) {
            String ts = TorrentEngine.get(this).shortStatus();
            CharSequence cur = loadingView.getText();
            String base = cur.toString().split("\n")[0];
            if (!ts.isEmpty()) loadingView.setText(base + "\n" + ts);
        }
        if (sleepAt > 0 && System.currentTimeMillis() >= sleepAt) {
            sleepAt = 0;
            Toast.makeText(this, T.t("Sleep timer: stopped"), Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (System.currentTimeMillis() - lastSave > 15000) savePosition();
    }

    // =====================================================================
    // Engine
    // =====================================================================
    private void createEngine() {
        ArrayList<String> o = new ArrayList<>();
        o.add("--network-caching=" + Store.getInt("cache", 2000));
        o.add("--http-reconnect");
        o.add("--audio-time-stretch");
        o.add("--freetype-rel-fontsize=" + Store.getInt("subsize", 16));
        o.add("--freetype-color=16777215");
        if (Store.getBool("subbg", false)) {
            o.add("--freetype-background-color=0");
            o.add("--freetype-background-opacity=170");
        } else {
            o.add("--freetype-background-opacity=0");
        }
        String enc = Store.getStr("subenc", "");
        if (!enc.isEmpty()) o.add("--subsdec-encoding=" + enc);
        libVLC = new LibVLC(this, o);
        mp = new MediaPlayer(libVLC);
        mp.setEventListener(events);
        mp.attachViews(videoLayout, null, true, false);
        viewsAttached = true;
        loadEqInfo();
        if (Store.getBool("eq_pending", false)) {
            Store.putBool("eq_pending", false);
            int p = Store.getInt("eq_preset", -1);
            if (p >= 0) loadPreset(p);
        }
        applyFit();
        applyZoom();
    }

    private void releaseEngine() {
        if (mp != null) {
            try {
                mp.setEventListener(null);
                mp.stop();
                if (viewsAttached) mp.detachViews();
                mp.release();
            } catch (Throwable ignored) {
            }
            mp = null;
            viewsAttached = false;
        }
        if (libVLC != null) {
            try {
                libVLC.release();
            } catch (Throwable ignored) {
            }
            libVLC = null;
        }
        closePfd();
    }

    /** Re-creates the engine (needed for subtitle style / buffer changes) and continues where it was. */
    private void restartEngine() {
        if (mp == null) return;
        long t = mp.getTime();
        int a = mp.getAudioTrack(), s = mp.getSpuTrack();
        ArrayList<String> subs = new ArrayList<>(addedSubs);
        long sd = subDelayMs;
        releaseEngine();
        createEngine();
        if (current() == null) return;
        startMedia(current(), Math.max(0, t), subs);
        subDelayMs = sd;
        pendingAudio = a;
        pendingSpu = s;
    }

    private void reloadCurrent() {
        if (mp == null || current() == null) return;
        long t = mp.getTime();
        int a = mp.getAudioTrack(), s = mp.getSpuTrack();
        ArrayList<String> subs = new ArrayList<>(addedSubs);
        long sd = subDelayMs;
        startMedia(current(), Math.max(0, t), subs);
        subDelayMs = sd;
        pendingAudio = a;
        pendingSpu = s;
    }

    private static void loadEqInfo() {
        if (PRESETS != null) return;
        try {
            int n = MediaPlayer.Equalizer.getPresetCount();
            String[] p = new String[n];
            for (int i = 0; i < n; i++) p[i] = MediaPlayer.Equalizer.getPresetName(i);
            int b = MediaPlayer.Equalizer.getBandCount();
            float[] f = new float[b];
            for (int i = 0; i < b; i++) f[i] = MediaPlayer.Equalizer.getBandFrequency(i);
            BAND_FREQS = f;
            PRESETS = p;
        } catch (Throwable ignored) {
        }
    }

    private Store.Item current() {
        return index >= 0 && index < queue.size() ? queue.get(index) : null;
    }

    private void playIndex(int i, boolean resume) {
        if (i < 0 || i >= queue.size() || mp == null) return;
        savePosition();
        index = i;
        Store.Item it = queue.get(i);
        if (Formats.isTorrent(it.u)) {
            resolveTorrent(i);
            return;
        }
        if (Formats.maybeList(it.u) && !checkedStreams.contains(it.u)) {
            expandList(i);
            return;
        }
        String th = torrentHash(it.u);
        if (th != null && !TorrentEngine.get(this).isActiveHash(th)) {
            String src = Store.getStr("torsrc_" + th, "");
            if (src.isEmpty()) {
                onPlayError(T.t("This torrent has ended. Play its magnet link again."));
                return;
            }
            reopenTorrent(i, src, it.u);
            return;
        }
        long start = resume ? Store.pos(it.u) : 0;
        if (start < 5000) start = 0;
        startMedia(it, start, null);
    }

    private void startMedia(Store.Item it, long startMs, List<String> keepSubs) {
        resolveToken++;
        ended = false;
        closePfd();
        addedSubs.clear();
        subDelayMs = 0;
        pendingAudio = Integer.MIN_VALUE;
        pendingSpu = Integer.MIN_VALUE;
        if (it.u.contains("127.0.0.1:" + RemoteService.PORT + "/torrent/")) torrentUsed = true;
        Media m;
        try {
            m = buildMedia(it.u);
        } catch (Exception e) {
            onPlayError(T.t("Cannot open: ") + e.getMessage());
            return;
        }
        if (startMs > 0) m.addOption(String.format(Locale.US, ":start-time=%.2f", startMs / 1000.0));
        if (Store.getInt("hw", 0) == 1) m.setHWDecoderEnabled(false, false);
        if (it.u.startsWith("http")) {
            String ua = Net.UA, ref = null;
            int bar = it.u.indexOf('|');
            if (bar > 0) {
                for (String kv : it.u.substring(bar + 1).split("&")) {
                    int eq = kv.indexOf('=');
                    if (eq < 0) continue;
                    String k = kv.substring(0, eq).trim().toLowerCase(Locale.US), v = Uri.decode(kv.substring(eq + 1));
                    if (k.equals("referer") || k.equals("referrer")) ref = v;
                    if (k.equals("user-agent")) ua = v;
                }
            }
            m.addOption(":http-user-agent=" + ua);
            if (ref != null) m.addOption(":http-referrer=" + ref);
        }
        mp.setMedia(m);
        m.release();
        if (keepSubs != null) for (String s : keepSubs) addSubtitle(s, false);
        if (!it.s.isEmpty()) loadSubtitleAsync(it.s);
        mp.play();
        applyAudio();
        titleView.setText(it.t);
        showLoading(startMs > 0 ? T.t("Resuming at ") + Net.fmtTime(startMs) + "…" : T.t("Opening…"));
        showOverlay();
    }

    private Media buildMedia(String url) throws Exception {
        if (url.startsWith("http") && url.indexOf('|') > 0) url = url.substring(0, url.indexOf('|'));
        if (url.startsWith("/")) return new Media(libVLC, url);
        Uri u = Uri.parse(url);
        if ("content".equals(u.getScheme())) {
            pfd = getContentResolver().openFileDescriptor(u, "r");
            if (pfd == null) throw new Exception("file not available");
            return new Media(libVLC, Uri.parse("fd://" + pfd.getFd()));
        }
        return new Media(libVLC, u);
    }

    private void closePfd() {
        if (pfd != null) {
            try {
                pfd.close();
            } catch (Exception ignored) {
            }
            pfd = null;
        }
    }

    private final java.util.HashSet<String> checkedStreams = new java.util.HashSet<>();

    /** Many channels in the list = IPTV: don't auto-skip or close when a channel fails. */
    private boolean channelMode() {
        return queue.size() > 20;
    }

    /**
     * An .m3u/.m3u8/.pls link: if it is a normal HLS video stream, play it; if it is a
     * channel list (like iptv-org), open all its channels as the playlist.
     */
    private void expandList(final int i) {
        final Store.Item it = queue.get(i);
        final int token = ++resolveToken;
        if (mp != null) mp.stop();
        titleView.setText(it.t);
        showLoading(T.t("Reading the list…"));
        showOverlay();
        new Thread(new Runnable() {
            public void run() {
                List<Store.Item> items = null;
                try {
                    String url = it.u.split("\\|")[0];
                    String text;
                    if (url.startsWith("/")) {
                        java.io.File f = new java.io.File(url);
                        byte[] b = new byte[(int) Math.min(f.length(), 40_000_000)];
                        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                            int off = 0, r;
                            while (off < b.length && (r = in.read(b, off, b.length - off)) > 0) off += r;
                        }
                        text = new String(b, "UTF-8");
                    } else {
                        text = Net.fetchText(url);
                    }
                    boolean hls = text.contains("#EXT-X-");
                    if (!hls) {
                        items = Formats.ext(url).equals("pls") ? Store.parsePls(text) : Store.parseM3u(text);
                    }
                } catch (Exception ignored) {
                    // could not read it ourselves: let the engine try
                }
                final List<Store.Item> found = items;
                handler.post(new Runnable() {
                    public void run() {
                        if (token != resolveToken || isFinishing() || i >= queue.size()) return;
                        if (found == null || found.isEmpty()) {
                            checkedStreams.add(it.u);
                            playIndex(i, true);
                            return;
                        }
                        for (Store.Item x : found) checkedStreams.add(x.u);
                        queue.remove(i);
                        queue.addAll(i, found);
                        osd(found.size() + T.t(" channels · OK or 📺 = channel list"));
                        playIndex(i, true);
                    }
                });
            }
        }).start();
    }

    private static String torrentHash(String u) {
        String mark = "127.0.0.1:" + RemoteService.PORT + "/torrent/";
        int k = u.indexOf(mark);
        if (k < 0) return null;
        String rest = u.substring(k + mark.length());
        int s = rest.indexOf('/');
        return s > 0 ? rest.substring(0, s) : null;
    }

    /** Restarts a finished torrent and jumps to the same file (used by "Continue watching"). */
    private void reopenTorrent(final int i, final String src, final String streamUrl) {
        final int token = ++resolveToken;
        torrentUsed = true;
        if (mp != null) mp.stop();
        titleView.setText(queue.get(i).t);
        showLoading(T.t("Restarting torrent…"));
        showOverlay();
        new Thread(new Runnable() {
            public void run() {
                try {
                    final List<Store.Item> files = TorrentEngine.get(PlayerActivity.this).open(src);
                    handler.post(new Runnable() {
                        public void run() {
                            if (token != resolveToken || isFinishing() || i >= queue.size()) return;
                            int at = 0;
                            for (int k = 0; k < files.size(); k++) if (files.get(k).u.equals(streamUrl)) at = k;
                            queue.remove(i);
                            queue.addAll(i, files);
                            playIndex(i + at, true);
                        }
                    });
                } catch (final Exception e) {
                    handler.post(new Runnable() {
                        public void run() {
                            if (token == resolveToken) onPlayError("Torrent: " + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void resolveTorrent(final int i) {
        final Store.Item it = queue.get(i);
        final int token = ++resolveToken;
        torrentUsed = true;
        if (mp != null) mp.stop();
        titleView.setText(it.t);
        showLoading("Getting torrent info…\n(this can take up to a minute)");
        showOverlay();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<Store.Item> files = TorrentEngine.get(PlayerActivity.this).open(it.u);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (token != resolveToken || isFinishing() || i >= queue.size()) return;
                            queue.remove(i);
                            queue.addAll(i, files);
                            if (files.size() > 1) osd(files.size() + T.t(" videos in this torrent"));
                            playIndex(i, true);
                        }
                    });
                } catch (final Exception e) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (token != resolveToken) return;
                            onPlayError("Torrent: " + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void onPlayError(String msg) {
        if (channelMode()) {
            showLoading(msg + "\n" + T.t("OK = retry · CH+ / CH− = another channel · 📺 = list"));
            showOverlay();
            return;
        }
        showLoading(msg);
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        final int token = resolveToken;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (token != resolveToken) return;
                if (index + 1 < queue.size()) playIndex(index + 1, true);
            }
        }, 4000);
    }

    private final MediaPlayer.EventListener events = new MediaPlayer.EventListener() {
        @Override
        public void onEvent(MediaPlayer.Event e) {
            switch (e.type) {
                case MediaPlayer.Event.Playing:
                    hideLoading();
                    applyAudio();
                    applyPicture();
                    handler.postDelayed(new Runnable() {
                        public void run() {
                            applyPicture();
                        }
                    }, 1200);
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            restoreTracks();
                        }
                    }, 800);
                    break;
                case MediaPlayer.Event.Paused:
                    showOverlay();
                    break;
                case MediaPlayer.Event.Buffering:
                    float b = e.getBuffering();
                    if (b < 100f) showLoading(T.t("Buffering ") + (int) b + "%");
                    else hideLoading();
                    break;
                case MediaPlayer.Event.Vout:
                    applyAspect();
                    break;
                case MediaPlayer.Event.EndReached:
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            onEnded();
                        }
                    });
                    break;
                case MediaPlayer.Event.EncounteredError:
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            Store.Item it = current();
                            onPlayError(T.t("Cannot play: ") + (it == null ? "" : it.t));
                        }
                    });
                    break;
                default:
                    break;
            }
        }
    };

    private void onEnded() {
        if (ended) return;
        ended = true;
        Store.Item it = current();
        if (it != null) {
            Store.setPos(it.u, 0);
            Store.removeHistory(it.u);
        }
        if (sleepAfterItem) {
            Toast.makeText(this, T.t("Sleep timer: stopped"), Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (channelMode()) {
            showLoading(T.t("Channel stopped — OK = retry, CH+ / CH− = another channel"));
            showOverlay();
            return;
        }
        if (repeatMode() == 1) {
            playIndex(index, false);
            return;
        }
        if (shuffleOn() && queue.size() > 1) {
            playIndex(randomOther(), false);
            return;
        }
        if (index + 1 < queue.size()) playIndex(index + 1, true);
        else if (repeatMode() == 2) playIndex(0, false);
        else finish();
    }

    private void restoreTracks() {
        if (mp == null) return;
        if (pendingAudio != Integer.MIN_VALUE) mp.setAudioTrack(pendingAudio);
        if (pendingSpu != Integer.MIN_VALUE) mp.setSpuTrack(pendingSpu);
        pendingAudio = Integer.MIN_VALUE;
        pendingSpu = Integer.MIN_VALUE;
        mp.setSpuDelay(subDelayMs * 1000);
    }

    private void savePosition() {
        lastSave = System.currentTimeMillis();
        Store.Item it = current();
        if (mp == null || it == null || ended) return;
        long pos = mp.getTime(), len = mp.getLength();
        if (len <= 0 || pos <= 0) return;
        boolean nearEnd = pos > len - Math.max(15000, len / 50);
        Store.setPos(it.u, nearEnd ? 0 : pos);
        boolean resumable = !it.u.startsWith("content:") && !it.u.contains("/local/") && len > 60000;
        if (nearEnd) Store.removeHistory(it.u);
        else if (resumable && pos > 5000) {
            Store.putHistory(it.t, it.u, pos, len);
            Thumbs.request(this, it.u, pos);
        }
    }

    // =====================================================================
    // Applying settings
    // =====================================================================
    private void applyAudio() {
        if (mp == null) return;
        mp.setVolume(muted ? 0 : volume);
        mp.setRate(rate);
        mp.setAudioDelay(Store.getInt("adelay", 0) * 1000L);
        applyEq();
    }

    private void applyEq() {
        if (mp == null) return;
        if (!Store.getBool("eq_on", false)) {
            mp.setEqualizer(null);
            return;
        }
        try {
            MediaPlayer.Equalizer eq = MediaPlayer.Equalizer.create();
            eq.setPreAmp(Store.getFloat("eq_preamp", 0f));
            float[] b = Store.getBands();
            int n = Math.min(b.length, MediaPlayer.Equalizer.getBandCount());
            for (int i = 0; i < n; i++) eq.setAmp(i, b[i]);
            mp.setEqualizer(eq);
        } catch (Throwable ignored) {
        }
    }

    private void loadPreset(int p) {
        try {
            MediaPlayer.Equalizer e = MediaPlayer.Equalizer.createFromPreset(p);
            float[] b = new float[Store.EQ_BANDS];
            int n = Math.min(b.length, MediaPlayer.Equalizer.getBandCount());
            for (int i = 0; i < n; i++) b[i] = e.getAmp(i);
            Store.putBands(b);
            Store.putFloat("eq_preamp", e.getPreAmp());
            Store.putInt("eq_preset", p);
            Store.putBool("eq_on", true);
        } catch (Throwable ignored) {
        }
        applyEq();
    }

    private void applyAspect() {
        applyPicture();
    }

    private void applyFit() {
        applyPicture();
        handler.postDelayed(new Runnable() {
            public void run() {
                applyPicture();
            }
        }, 400);
    }

    private void applyZoom() {
        applyPicture();
    }

    /** Display aspect of the current video (width/height incl. pixel shape), or 0 if unknown yet. */
    private float videoRatio() {
        try {
            IMedia.Track t = mp.getCurrentVideoTrack();
            if (t instanceof IMedia.VideoTrack) {
                IMedia.VideoTrack v = (IMedia.VideoTrack) t;
                if (v.width > 0 && v.height > 0) {
                    float sar = (v.sarNum > 0 && v.sarDen > 0) ? (float) v.sarNum / v.sarDen : 1f;
                    return v.width * sar / v.height;
                }
            }
        } catch (Throwable ignored) {
        }
        return 0f;
    }

    private static float parseRatio(String a) {
        try {
            String[] p = a.split(":");
            return Float.parseFloat(p[0]) / Float.parseFloat(p[1]);
        } catch (Exception e) {
            return 0f;
        }
    }

    /**
     * Picture fit, worked out by the app itself so it always covers the TV:
     * Best fit = whole picture · Fill = zoom until no black bars (edges cut)
     * Stretch = force the TV's own shape · Original = 1:1 pixels.
     */
    private void applyPicture() {
        if (mp == null || root == null) return;
        int w = root.getWidth(), h = root.getHeight();
        int fit = Store.getInt("fit", 0);
        String a = Store.getStr("aspect", "");
        float zoom = Store.getInt("zoom", 100) / 100f;
        if (fit == 3) {
            mp.setVideoScale(MediaPlayer.ScaleType.SURFACE_ORIGINAL);
            mp.setAspectRatio(a.isEmpty() ? null : a);
        } else {
            mp.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT);
            if (fit == 2 && w > 0 && h > 0) {
                mp.setAspectRatio(w + ":" + h);       // stretch: picture takes the screen's shape
            } else {
                mp.setAspectRatio(a.isEmpty() ? null : a);
                if (fit == 1 && w > 0 && h > 0) {
                    float vr = a.isEmpty() ? videoRatio() : parseRatio(a);
                    float sr = (float) w / h;
                    if (vr > 0) zoom *= vr > sr ? vr / sr : sr / vr;
                }
            }
        }
        FrameLayout.LayoutParams lp;
        if (Math.abs(zoom - 1f) < 0.01f || w == 0 || h == 0) {
            lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER);
        } else {
            lp = new FrameLayout.LayoutParams(Math.round(w * zoom), Math.round(h * zoom), Gravity.CENTER);
        }
        FrameLayout.LayoutParams old = (FrameLayout.LayoutParams) videoLayout.getLayoutParams();
        if (old == null || old.width != lp.width || old.height != lp.height) videoLayout.setLayoutParams(lp);
    }

    // =====================================================================
    // Subtitles
    // =====================================================================
    private void addSubtitle(String pathOrUrl, boolean select) {
        if (mp == null) return;
        Uri u = pathOrUrl.startsWith("/") ? Uri.fromFile(new File(pathOrUrl)) : Uri.parse(pathOrUrl);
        mp.addSlave(IMedia.Slave.Type.Subtitle, u, select);
        if (!addedSubs.contains(pathOrUrl)) addedSubs.add(pathOrUrl);
    }

    private void loadSubtitleAsync(final String url) {
        if (url.startsWith("/") || url.startsWith("file:")) {
            addSubtitle(url, true);
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final File f = Net.downloadSubtitle(PlayerActivity.this, url);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            addSubtitle(f.getAbsolutePath(), true);
                            osd(T.t("Subtitle loaded"));
                        }
                    });
                } catch (final Exception e) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            osd(T.t("Subtitle failed: ") + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    // =====================================================================
    // Keys (TV remote)
    // =====================================================================
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (locked && code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN
                && code != KeyEvent.KEYCODE_VOLUME_MUTE) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) flashUnlock();
            return true;
        }
        if (controlsFocused()) {
            boolean nav = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                    || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                    || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
            if (code == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) hideOverlayNow();
                return true;
            }
            if (nav) {
                handler.removeCallbacks(hideOverlay);
                handler.postDelayed(hideOverlay, 9000);
                return super.dispatchKeyEvent(event);
            }
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && handleKey(code, event.getRepeatCount())) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    public boolean handleKey(int code, int repeat) {
        if (mp == null) return false;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                if (repeat == 0) {
                    boolean okKey = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                            || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
                    if (okKey && channelMode() && mp.isPlaying()) menuQueue();
                    else togglePause();
                }
                return true;
            case KeyEvent.KEYCODE_GUIDE:
            case KeyEvent.KEYCODE_TV:
            case KeyEvent.KEYCODE_BOOKMARK:
            case KeyEvent.KEYCODE_LAST_CHANNEL:
                if (repeat == 0) menuQueue();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                mp.play();
                showOverlay();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                mp.pause();
                showOverlay();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                seekBy(repeat >= 3 ? -30000 : -10000);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                seekBy(repeat >= 3 ? 30000 : 10000);
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                seekBy(-30000);
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                seekBy(30000);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (repeat == 0) {
                    if (overlayVisible) hideOverlayNow();
                    else {
                        showOverlay();
                        gearBtn.requestFocus();
                    }
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (repeat == 0) {
                    showOverlay();
                    playBtn.requestFocus();
                    handler.removeCallbacks(hideOverlay);
                    handler.postDelayed(hideOverlay, 9000);
                }
                return true;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_SETTINGS:
            case KeyEvent.KEYCODE_INFO:
                if (repeat == 0) showMenu();
                return true;
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_PAGE_UP:
                if (repeat == 0) next();
                return true;
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                if (repeat == 0) prev();
                return true;
            case KeyEvent.KEYCODE_CAPTIONS:
            case KeyEvent.KEYCODE_PROG_YELLOW:
                if (repeat == 0) cycleSubs();
                return true;
            case KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK:
            case KeyEvent.KEYCODE_PROG_GREEN:
                if (repeat == 0) cycleAudio();
                return true;
            case KeyEvent.KEYCODE_PROG_RED:
                if (repeat == 0) {
                    int f = (Store.getInt("fit", 0) + 1) % FIT_NAMES.length;
                    command("fit", String.valueOf(f), "");
                }
                return true;
            case KeyEvent.KEYCODE_PROG_BLUE:
                if (repeat == 0) {
                    int z = Store.getInt("zoom", 100), k = 0;
                    for (int i = 0; i < ZOOM_VALUES.length; i++) if (ZOOM_VALUES[i] == z) k = i;
                    command("zoom", String.valueOf(ZOOM_VALUES[(k + 1) % ZOOM_VALUES.length]), "");
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_STOP:
            case KeyEvent.KEYCODE_BACK:
                if (repeat == 0) finish();
                return true;
            default:
                if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9 && repeat == 0 && channelMode()) {
                    numBuf.append(code - KeyEvent.KEYCODE_0);
                    osd(T.t("Channel ") + numBuf + "_");
                    handler.removeCallbacks(numGo);
                    handler.postDelayed(numGo, numBuf.length() >= 5 ? 0 : 1600);
                    return true;
                }
                if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9 && repeat == 0) {
                    long len = mp.getLength();
                    if (len > 0 && mp.isSeekable()) {
                        int pct = (code - KeyEvent.KEYCODE_0) * 10;
                        seekTo(len * pct / 100);
                        osd(T.t("Jump to ") + pct + "%");
                    }
                    return true;
                }
                return false;
        }
    }

    private void togglePause() {
        if (channelMode() && !mp.isPlaying() && loadingView.getVisibility() == View.VISIBLE && current() != null) {
            playIndex(index, false);
            return;
        }
        if (mp.isPlaying()) mp.pause();
        else if (ended && current() != null) playIndex(index, false);
        else mp.play();
        showOverlay();
    }

    private void seekBy(long delta) {
        long now = System.currentTimeMillis();
        long base = (seekTarget >= 0 && now - seekAt < 1500) ? seekTarget : mp.getTime();
        seekTo(base + delta);
        osd((delta > 0 ? "+" : "−") + (Math.abs(delta) / 1000) + " s");
    }

    private void seekTo(long t) {
        if (!mp.isSeekable()) {
            osd(T.t("This stream cannot be seeked"));
            return;
        }
        long len = mp.getLength();
        if (len > 0) t = Math.min(t, len - 1000);
        t = Math.max(0, t);
        seekTarget = t;
        seekAt = System.currentTimeMillis();
        mp.setTime(t);
        showOverlay();
    }

    private void next() {
        if (shuffleOn() && queue.size() > 1 && !channelMode()) playIndex(randomOther(), true);
        else if (index + 1 < queue.size()) playIndex(index + 1, true);
        else if (repeatMode() == 2) playIndex(0, true);
        else osd(T.t("This is the last item"));
    }

    private void prev() {
        if (mp.getTime() > 10000 && mp.isSeekable()) {
            seekTo(0);
            return;
        }
        if (index > 0) playIndex(index - 1, true);
        else seekTo(0);
    }

    private void cycleSubs() {
        MediaPlayer.TrackDescription[] t = mp.getSpuTracks();
        if (t == null || t.length == 0) {
            osd(T.t("No subtitles in this video"));
            return;
        }
        int cur = mp.getSpuTrack(), k = -1;
        for (int i = 0; i < t.length; i++) if (t[i].id == cur) k = i;
        MediaPlayer.TrackDescription n = t[(k + 1) % t.length];
        mp.setSpuTrack(n.id);
        osd(T.t("Subtitles: ") + niceName(n));
    }

    private void cycleAudio() {
        MediaPlayer.TrackDescription[] t = mp.getAudioTracks();
        if (t == null || t.length == 0) return;
        int cur = mp.getAudioTrack(), k = -1;
        for (int i = 0; i < t.length; i++) if (t[i].id == cur) k = i;
        for (int step = 1; step <= t.length; step++) {
            MediaPlayer.TrackDescription n = t[(k + step) % t.length];
            if (n.id < 0) continue; // skip "Disable"
            mp.setAudioTrack(n.id);
            osd(T.t("Audio: ") + niceName(n));
            return;
        }
    }

    // =====================================================================
    // Menus on the TV
    // =====================================================================
    private interface IntCb {
        void on(int i);
    }

    private interface FloatCb {
        void on(float v);
    }

    private interface Fmt {
        String f(float v);
    }

    private void choose(String title, String[] items, int checked, final IntCb cb) {
        new AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(items, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        d.dismiss();
                        cb.on(w);
                    }
                }).show();
    }

    /** A value you change with ◀ ▶ on the remote. */
    private void stepper(String title, float value, final float step, final float min, final float max,
                         final float reset, final Fmt fmt, final FloatCb cb) {
        final float[] cur = {value};
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(16), dp(24), dp(8));
        final TextView tv = new TextView(this);
        tv.setTextSize(32);
        tv.setGravity(Gravity.CENTER);
        tv.setText("◀  " + fmt.f(value) + "  ▶");
        box.addView(tv);
        TextView hint = new TextView(this);
        hint.setGravity(Gravity.CENTER);
        hint.setText(T.t("Use ◀ ▶ on the remote to change"));
        box.addView(hint);
        AlertDialog d = new AlertDialog.Builder(this).setTitle(title).setView(box)
                .setPositiveButton(T.t("Done"), null)
                .setNeutralButton(T.t("Reset"), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        cb.on(reset);
                    }
                }).create();
        d.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override
            public boolean onKey(DialogInterface di, int code, KeyEvent ev) {
                if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                if (ev.getAction() != KeyEvent.ACTION_DOWN) return true;
                float n = cur[0] + (code == KeyEvent.KEYCODE_DPAD_RIGHT ? step : -step);
                n = Math.max(min, Math.min(max, n));
                n = Math.round(n * 1000f) / 1000f;
                cur[0] = n;
                tv.setText("◀  " + fmt.f(n) + "  ▶");
                cb.on(n);
                return true;
            }
        });
        d.show();
    }

    private void showMenu() {
        if (mp == null) return;
        final List<String> labels = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();
        labels.add(T.t("Audio track: ") + trackName(mp.getAudioTracks(), mp.getAudioTrack()));
        acts.add(new Runnable() { public void run() { menuAudio(); } });
        labels.add(T.t("Subtitles: ") + trackName(mp.getSpuTracks(), mp.getSpuTrack()));
        acts.add(new Runnable() { public void run() { menuSubs(); } });
        labels.add(T.t("Aspect ratio: ") + ASPECT_NAMES[aspectIndex()]);
        acts.add(new Runnable() { public void run() { menuAspect(); } });
        labels.add(T.t("Picture fit: ") + FIT_NAMES[Math.max(0, Math.min(3, Store.getInt("fit", 0)))]);
        acts.add(new Runnable() { public void run() { menuFit(); } });
        labels.add(T.t("Zoom / crop: ") + Store.getInt("zoom", 100) + "%");
        acts.add(new Runnable() { public void run() { menuZoom(); } });
        labels.add(T.t("Equalizer: ") + eqLabel());
        acts.add(new Runnable() { public void run() { menuEq(); } });
        labels.add(T.t("Volume / boost: ") + (muted ? "muted" : volume + "%"));
        acts.add(new Runnable() { public void run() { menuVolume(); } });
        labels.add(T.t("Playback speed: ") + fmtRate(rate));
        acts.add(new Runnable() { public void run() { menuSpeed(); } });
        labels.add(T.t("Audio delay: ") + fmtMs(Store.getInt("adelay", 0)));
        acts.add(new Runnable() { public void run() { menuAudioDelay(); } });
        labels.add(T.t("Subtitle delay: ") + fmtMs(subDelayMs));
        acts.add(new Runnable() { public void run() { menuSubDelay(); } });
        labels.add(T.t("Subtitle style (size, background, language encoding)"));
        acts.add(new Runnable() { public void run() { menuSubStyle(); } });
        labels.add(T.t("Sleep timer: ") + sleepLabel());
        acts.add(new Runnable() { public void run() { menuSleep(); } });
        labels.add(T.t("Decoder & buffer"));
        acts.add(new Runnable() { public void run() { menuEngine(); } });
        if (queue.size() > 1) {
            labels.add(T.t("Playlist (") + (index + 1) + "/" + queue.size() + ")…");
            acts.add(new Runnable() { public void run() { menuQueue(); } });
            labels.add(T.t("Next item"));
            acts.add(new Runnable() { public void run() { next(); } });
            labels.add(T.t("Previous item"));
            acts.add(new Runnable() { public void run() { prev(); } });
        }
        if (torrentUsed && TorrentEngine.get(this).isActive()) {
            labels.add(T.t("Stop torrent and delete its data"));
            acts.add(new Runnable() { public void run() { TorrentEngine.get(PlayerActivity.this).stopAsync(); finish(); } });
        }
        labels.add((repeatMode() == 1 ? "🔂  " : "🔁  ") + T.t("Repeat: ") + repeatName());
        acts.add(new Runnable() { public void run() { command("repeat", "", ""); } });
        labels.add("🔀  " + T.t("Shuffle: ") + T.t(shuffleOn() ? "On" : "Off"));
        acts.add(new Runnable() { public void run() { command("shuffle", "", ""); } });
        if (!Ui.isTv(this)) {
            int rot = Store.getInt("rotate", 1);
            labels.add("🔄  " + T.t("Rotation: ") + T.t(rot == 0 ? "Auto" : rot == 2 ? "Portrait" : "Landscape"));
            acts.add(new Runnable() { public void run() { command("rotate", "", ""); } });
            labels.add("🔒  " + T.t("Lock screen"));
            acts.add(new Runnable() { public void run() { command("lock", "1", ""); } });
        }
        labels.add("🏠  " + T.t("Go to Home"));
        acts.add(new Runnable() { public void run() { Ui.goHome(PlayerActivity.this, true); } });
        labels.add(T.t("Phone remote: http://") + Net.ip() + ":" + RemoteService.PORT);
        acts.add(new Runnable() { public void run() { osd("Open http://" + Net.ip() + ":" + RemoteService.PORT + " on your phone"); } });

        new AlertDialog.Builder(this).setTitle(T.t("Options"))
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        acts.get(w).run();
                    }
                }).show();
    }

    private void menuAudio() {
        final MediaPlayer.TrackDescription[] t = mp.getAudioTracks();
        if (t == null || t.length == 0) {
            osd(T.t("No audio tracks"));
            return;
        }
        String[] names = new String[t.length];
        int sel = -1;
        for (int i = 0; i < t.length; i++) {
            names[i] = niceName(t[i]);
            if (t[i].id == mp.getAudioTrack()) sel = i;
        }
        choose(T.t("Audio track"), names, sel, new IntCb() {
            public void on(int i) {
                command("audio", String.valueOf(t[i].id), "");
            }
        });
    }

    private void menuSubs() {
        final List<Integer> ids = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        MediaPlayer.TrackDescription[] t = mp.getSpuTracks();
        boolean hasOff = false;
        if (t != null) for (MediaPlayer.TrackDescription d : t) if (d.id < 0) hasOff = true;
        if (!hasOff) {
            ids.add(-1);
            names.add(T.t("Off"));
        }
        int sel = -1;
        if (t != null) {
            for (MediaPlayer.TrackDescription d : t) {
                if (d.id == mp.getSpuTrack()) sel = ids.size();
                ids.add(d.id);
                names.add(niceName(d));
            }
        }
        if (sel < 0 && mp.getSpuTrack() < 0) sel = 0;
        final int extra = ids.size();
        names.add(T.t("➕ Load a subtitle file from your phone…"));
        names.add(T.t("Subtitle delay…"));
        names.add(T.t("Subtitle style…"));
        choose(T.t("Subtitles"), names.toArray(new String[0]), sel, new IntCb() {
            public void on(int i) {
                if (i < extra) command("sub", String.valueOf(ids.get(i)), "");
                else if (i == extra) new AlertDialog.Builder(PlayerActivity.this).setTitle(T.t("Load subtitles"))
                        .setMessage("On your phone open\n\nhttp://" + Net.ip() + ":" + RemoteService.PORT
                                + "\n\nthen go to “Audio & Subs” and upload a .srt/.ass file or paste a subtitle link.")
                        .setPositiveButton(T.t("OK"), null).show();
                else if (i == extra + 1) menuSubDelay();
                else menuSubStyle();
            }
        });
    }

    private void menuAspect() {
        choose(T.t("Aspect ratio"), ASPECT_NAMES, aspectIndex(), new IntCb() {
            public void on(int i) {
                command("aspect", ASPECT_VALUES[i], "");
            }
        });
    }

    private void menuFit() {
        choose(T.t("Picture fit"), FIT_NAMES, Store.getInt("fit", 0), new IntCb() {
            public void on(int i) {
                command("fit", String.valueOf(i), "");
            }
        });
    }

    private void menuZoom() {
        String[] names = new String[ZOOM_NAMES.length + 1];
        System.arraycopy(ZOOM_NAMES, 0, names, 0, ZOOM_NAMES.length);
        names[ZOOM_NAMES.length] = T.t("Custom…");
        int z = Store.getInt("zoom", 100), sel = -1;
        for (int i = 0; i < ZOOM_VALUES.length; i++) if (ZOOM_VALUES[i] == z) sel = i;
        choose(T.t("Zoom / crop to fill the screen"), names, sel, new IntCb() {
            public void on(int i) {
                if (i < ZOOM_VALUES.length) {
                    command("zoom", String.valueOf(ZOOM_VALUES[i]), "");
                    return;
                }
                stepper(T.t("Zoom"), Store.getInt("zoom", 100), 5, 50, 300, 100, new Fmt() {
                    public String f(float v) {
                        return Math.round(v) + "%";
                    }
                }, new FloatCb() {
                    public void on(float v) {
                        command("zoom", String.valueOf(Math.round(v)), "");
                    }
                });
            }
        });
    }

    private void menuVolume() {
        String[] names = new String[VOLS.length + 2];
        int sel = -1;
        for (int i = 0; i < VOLS.length; i++) {
            names[i] = VOLS[i] + "%" + (VOLS[i] > 100 ? T.t("  (boost)") : "");
            if (VOLS[i] == volume) sel = i;
        }
        names[VOLS.length] = muted ? T.t("Unmute") : T.t("Mute");
        names[VOLS.length + 1] = T.t("Custom…");
        choose("Volume / boost (up to 200%)", names, sel, new IntCb() {
            public void on(int i) {
                if (i < VOLS.length) command("vol", String.valueOf(VOLS[i]), "");
                else if (i == VOLS.length) command("mute", "", "");
                else stepper(T.t("Volume"), volume, 5, 0, 200, 100, new Fmt() {
                    public String f(float v) {
                        return Math.round(v) + "%";
                    }
                }, new FloatCb() {
                    public void on(float v) {
                        command("vol", String.valueOf(Math.round(v)), "");
                    }
                });
            }
        });
    }

    private void menuSpeed() {
        String[] names = new String[SPEEDS.length];
        int sel = -1;
        for (int i = 0; i < SPEEDS.length; i++) {
            names[i] = fmtRate(SPEEDS[i]);
            if (Math.abs(SPEEDS[i] - rate) < 0.01f) sel = i;
        }
        choose(T.t("Playback speed"), names, sel, new IntCb() {
            public void on(int i) {
                command("rate", String.valueOf(SPEEDS[i]), "");
            }
        });
    }

    private void menuAudioDelay() {
        stepper(T.t("Audio delay (fix lip-sync)"), Store.getInt("adelay", 0), 50, -10000, 10000, 0, new Fmt() {
            public String f(float v) {
                return fmtMs((long) v);
            }
        }, new FloatCb() {
            public void on(float v) {
                command("adelay", String.valueOf(Math.round(v)), "");
            }
        });
    }

    private void menuSubDelay() {
        stepper(T.t("Subtitle delay"), subDelayMs, 100, -60000, 60000, 0, new Fmt() {
            public String f(float v) {
                return fmtMs((long) v);
            }
        }, new FloatCb() {
            public void on(float v) {
                command("sdelay", String.valueOf(Math.round(v)), "");
            }
        });
    }

    private void menuSubStyle() {
        String[] items = {T.t("Size: ") + SUB_SIZE_NAMES[subSizeIndex()],
                T.t("Background box: ") + (Store.getBool("subbg", false) ? "On" : T.t("Off")),
                T.t("Text encoding: ") + ENC_NAMES[encIndex()]};
        new AlertDialog.Builder(this).setTitle(T.t("Subtitle style"))
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        if (w == 0) choose(T.t("Subtitle size"), SUB_SIZE_NAMES, subSizeIndex(), new IntCb() {
                            public void on(int i) {
                                command("subsize", String.valueOf(SUB_SIZES[i]), "");
                            }
                        });
                        else if (w == 1) command("subbg", Store.getBool("subbg", false) ? "0" : "1", "");
                        else choose(T.t("Subtitle text encoding"), ENC_NAMES, encIndex(), new IntCb() {
                            public void on(int i) {
                                command("subenc", ENC_VALUES[i], "");
                            }
                        });
                    }
                }).show();
    }

    private void menuSleep() {
        String[] names = new String[SLEEPS.length];
        for (int i = 0; i < SLEEPS.length; i++) {
            names[i] = SLEEPS[i] == 0 ? T.t("Off") : SLEEPS[i] < 0 ? T.t("At the end of this video") : SLEEPS[i] + T.t(" minutes");
        }
        choose(T.t("Sleep timer"), names, -1, new IntCb() {
            public void on(int i) {
                command("sleep", String.valueOf(SLEEPS[i]), "");
            }
        });
    }

    private void menuEngine() {
        String[] items = {T.t("Decoder: ") + (Store.getInt("hw", 0) == 1 ? T.t("Software (slower, most compatible)") : T.t("Hardware (auto)")),
                T.t("Network buffer: ") + Store.getInt("cache", 2000) / 1000f + " s"};
        new AlertDialog.Builder(this).setTitle(T.t("Decoder & buffer"))
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        if (w == 0) {
                            command("hw", Store.getInt("hw", 0) == 1 ? "0" : "1", "");
                        } else {
                            String[] n = new String[CACHES.length];
                            int sel = -1;
                            for (int i = 0; i < CACHES.length; i++) {
                                n[i] = (CACHES[i] / 1000f) + T.t(" seconds");
                                if (CACHES[i] == Store.getInt("cache", 2000)) sel = i;
                            }
                            choose(T.t("Network buffer (bigger = fewer stalls)"), n, sel, new IntCb() {
                                public void on(int i) {
                                    command("cache", String.valueOf(CACHES[i]), "");
                                }
                            });
                        }
                    }
                }).show();
    }

    /**
     * Channel / playlist list with live search. Works with the TV remote:
     * type in the box (on-screen keyboard), press ▼ to go into the list, OK to play.
     */
    private void menuQueue() {
        if (queue.isEmpty()) return;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(4), dp(16), 0);
        final android.widget.EditText q = new android.widget.EditText(this);
        q.setHint("🔍 " + T.t("Type a channel name…"));
        q.setSingleLine(true);
        q.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        box.addView(q);
        final android.widget.ListView lv = new android.widget.ListView(this);
        final List<Integer> idx = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        final android.widget.ArrayAdapter<String> ad = new android.widget.ArrayAdapter<>(this,
                android.R.layout.simple_list_item_activated_1, names);
        lv.setAdapter(ad);
        lv.setChoiceMode(android.widget.ListView.CHOICE_MODE_SINGLE);
        box.addView(lv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.min(dp(420), (int) (getResources().getDisplayMetrics().heightPixels * 0.62f))));
        final Runnable[] rebuild = new Runnable[1];
        rebuild[0] = new Runnable() {
            public void run() {
                String ql = q.getText().toString().trim().toLowerCase(Locale.ROOT);
                idx.clear();
                names.clear();
                int sel = -1;
                for (int i = 0; i < queue.size() && idx.size() < 3000; i++) {
                    if (!ql.isEmpty() && !queue.get(i).t.toLowerCase(Locale.ROOT).contains(ql)) continue;
                    if (i == index) sel = idx.size();
                    idx.add(i);
                    names.add((i == index ? "▶ " : "") + (i + 1) + ".  " + queue.get(i).t);
                }
                ad.notifyDataSetChanged();
                if (sel >= 0) {
                    lv.setItemChecked(sel, true);
                    lv.setSelection(Math.max(0, sel - 3));
                }
            }
        };
        q.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence c, int a, int b, int d) { }
            public void onTextChanged(CharSequence c, int a, int b, int d) { }
            public void afterTextChanged(android.text.Editable e) {
                rebuild[0].run();
            }
        });
        rebuild[0].run();
        final AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(T.t("📺 Channels") + "  ·  " + queue.size())
                .setView(box).setNegativeButton(T.t("Close"), null).create();
        lv.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            public void onItemClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                d.dismiss();
                playIndex(idx.get(pos), true);
            }
        });
        q.setOnEditorActionListener((v, act, ev) -> {
            lv.requestFocus();
            return false;
        });
        d.show();
        lv.post(new Runnable() {
            public void run() {
                lv.requestFocus();
            }
        });
    }

    // ---- type a channel number on the remote (channel lists) ----
    private final StringBuilder numBuf = new StringBuilder();
    private final Runnable numGo = new Runnable() {
        public void run() {
            try {
                int n = Integer.parseInt(numBuf.toString());
                if (n >= 1 && n <= queue.size()) playIndex(n - 1, true);
                else osd(T.t("No channel found"));
            } catch (Exception ignored) {
            }
            numBuf.setLength(0);
        }
    };

    static String[] eqLabels() {
        float[] f = BAND_FREQS != null ? BAND_FREQS : DEFAULT_FREQS;
        int n = Math.min(Store.EQ_BANDS, f.length);
        String[] l = new String[n + 1];
        l[0] = "Pre";
        for (int i = 0; i < n; i++) l[i + 1] = freqLabel(f[i]).replace(" Hz", "").replace(" kHz", "k");
        return l;
    }

    static float[] eqValues() {
        float[] b = Store.getBands();
        float[] f = BAND_FREQS != null ? BAND_FREQS : DEFAULT_FREQS;
        int n = Math.min(Store.EQ_BANDS, f.length);
        float[] v = new float[n + 1];
        v[0] = Store.getFloat("eq_preamp", 0f);
        System.arraycopy(b, 0, v, 1, n);
        return v;
    }

    /** Equalizer: vertical bands (◀ ▶ choose, ▲ ▼ change; drag up/down on touch), presets, on/off. */
    private void menuEq() {
        loadEqInfo();
        final String[] presets = PRESETS != null ? PRESETS : DEFAULT_PRESETS;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(6), dp(20), 0);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        final CheckBox on = new CheckBox(this);
        on.setText("On");
        on.setTextSize(17);
        on.setChecked(Store.getBool("eq_on", false));
        on.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean c) {
                command("eq_on", c ? "1" : "0", "");
            }
        });
        top.addView(on, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final Button preset = Ui.button(this, T.t("Preset: ") + eqPresetName(), true);
        top.addView(preset);
        final Button reset = Ui.button(this, T.t("Reset"), false);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.setMargins(dp(8), 0, 0, 0);
        top.addView(reset, rlp);
        box.addView(top);

        final EqView eq = new EqView(this);
        eq.setBands(eqLabels(), eqValues());
        eq.setListener(new EqView.Listener() {
            public void onBand(int i, float db) {
                if (i == 0) command("eq_preamp", String.valueOf(db), "");
                else command("eq_band", String.valueOf(db), String.valueOf(i - 1));
                on.setChecked(true);
                preset.setText(T.t("Preset: ") + eqPresetName());
            }
        });
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                dp(250));
        elp.setMargins(0, dp(12), 0, 0);
        box.addView(eq, elp);
        TextView hint = new TextView(this);
        hint.setText(T.t("◀ ▶ choose band   ·   ▲ ▼ change level   ·   touch: drag up / down"));
        hint.setTextSize(12);
        hint.setGravity(Gravity.CENTER);
        box.addView(hint);

        preset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                choose(T.t("Equalizer preset"), presets, Store.getInt("eq_preset", -1), new IntCb() {
                    public void on(int i) {
                        loadPreset(i);
                        on.setChecked(true);
                        preset.setText(T.t("Preset: ") + eqPresetName());
                        eq.setValues(eqValues());
                    }
                });
            }
        });
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                command("eq_reset", "", "");
                preset.setText(T.t("Preset: ") + eqPresetName());
                eq.setValues(eqValues());
            }
        });
        new AlertDialog.Builder(this).setTitle(T.t("🎚  Equalizer")).setView(box).setPositiveButton(T.t("Done"), null).show();
        eq.post(new Runnable() {
            public void run() {
                eq.requestFocus();
            }
        });
    }

    // =====================================================================
    // Commands (from the TV menus and the phone remote)
    // =====================================================================

    /** Stores a setting. Works even when nothing is playing. Returns a message, or null if not a setting. */
    public static String applySetting(String c, String v, String b) {
        try {
            switch (c) {
                case "vol": {
                    int x = Math.max(0, Math.min(200, Math.round(Float.parseFloat(v))));
                    Store.putInt("vol", x);
                    return "Volume " + x + "%";
                }
                case "aspect":
                    for (int i = 0; i < ASPECT_VALUES.length; i++) {
                        if (ASPECT_VALUES[i].equals(v)) {
                            Store.putStr("aspect", v);
                            return T.t("Aspect ratio: ") + ASPECT_NAMES[i];
                        }
                    }
                    return "Unknown aspect ratio";
                case "fit": {
                    int f = Math.max(0, Math.min(FIT_NAMES.length - 1, Integer.parseInt(v)));
                    Store.putInt("fit", f);
                    return FIT_NAMES[f];
                }
                case "zoom": {
                    int z = Math.max(50, Math.min(300, Math.round(Float.parseFloat(v))));
                    Store.putInt("zoom", z);
                    return "Zoom " + z + "%";
                }
                case "eq_on":
                    Store.putBool("eq_on", "1".equals(v) || "true".equals(v));
                    return "Equalizer " + (Store.getBool("eq_on", false) ? "on" : "off");
                case "eq_preset": {
                    int p = Integer.parseInt(v);
                    Store.putInt("eq_preset", p);
                    Store.putBool("eq_on", true);
                    Store.putBool("eq_pending", true);
                    return "Equalizer preset set";
                }
                case "eq_band": {
                    int band = Integer.parseInt(b);
                    float[] bands = Store.getBands();
                    if (band < 0 || band >= bands.length) return "Bad band";
                    bands[band] = Math.max(-20f, Math.min(20f, Float.parseFloat(v)));
                    Store.putBands(bands);
                    Store.putInt("eq_preset", -1);
                    Store.putBool("eq_on", true);
                    Store.putBool("eq_pending", false);
                    return "Band " + (band + 1) + ": " + fmtDb(bands[band]);
                }
                case "eq_preamp": {
                    float x = Math.max(-20f, Math.min(20f, Float.parseFloat(v)));
                    Store.putFloat("eq_preamp", x);
                    Store.putInt("eq_preset", -1);
                    Store.putBool("eq_on", true);
                    Store.putBool("eq_pending", false);
                    return "Preamp " + fmtDb(x);
                }
                case "eq_reset":
                    Store.putBands(new float[Store.EQ_BANDS]);
                    Store.putFloat("eq_preamp", 0f);
                    Store.putInt("eq_preset", 0);
                    Store.putBool("eq_pending", false);
                    return "Equalizer reset";
                case "subsize":
                    Store.putInt("subsize", Integer.parseInt(v));
                    return "Subtitle size changed";
                case "subbg":
                    Store.putBool("subbg", "1".equals(v) || "true".equals(v));
                    return "Subtitle background " + (Store.getBool("subbg", false) ? "on" : "off");
                case "subenc":
                    Store.putStr("subenc", v);
                    return "Subtitle encoding: " + (v.isEmpty() ? "automatic" : v);
                case "hw":
                    Store.putInt("hw", "1".equals(v) ? 1 : 0);
                    return T.t("Decoder: ") + ("1".equals(v) ? "software" : "hardware");
                case "cache":
                    Store.putInt("cache", Math.max(300, Math.min(60000, Integer.parseInt(v))));
                    return "Network buffer " + Store.getInt("cache", 2000) / 1000f + " s";
                case "adelay":
                    Store.putInt("adelay", Math.max(-30000, Math.min(30000, Math.round(Float.parseFloat(v)))));
                    return "Audio delay " + fmtMs(Store.getInt("adelay", 0));
                default:
                    return null;
            }
        } catch (Exception e) {
            return "Bad value";
        }
    }

    /** Runs a command on the TV (main thread). */
    public String command(String c, String v, String b) {
        if (mp == null) return "Player is not ready";
        String setting = applySetting(c, v, b);
        String msg = setting;
        switch (c) {
            // ---- settings that need applying ----
            case "vol":
                volume = Store.getInt("vol", 100);
                muted = false;
                applyAudio();
                break;
            case "aspect":
                applyAspect();
                break;
            case "fit":
                applyFit();
                break;
            case "zoom":
                applyZoom();
                break;
            case "eq_on": case "eq_band": case "eq_preamp": case "eq_reset":
                applyEq();
                break;
            case "eq_preset":
                Store.putBool("eq_pending", false);
                loadPreset(Store.getInt("eq_preset", 0));
                msg = T.t("Equalizer: ") + eqPresetName();
                break;
            case "subsize": case "subbg": case "subenc": case "cache":
                restartEngine();
                break;
            case "hw":
                reloadCurrent();
                break;
            case "adelay":
                mp.setAudioDelay(Store.getInt("adelay", 0) * 1000L);
                break;

            // ---- player actions ----
            case "toggle": {
                boolean was = mp.isPlaying();
                togglePause();
                msg = was ? "Paused" : "Playing";
                break;
            }
            case "play":
                mp.play();
                break;
            case "pause":
                mp.pause();
                break;
            case "stop":
                finish();
                msg = "Stopped";
                break;
            case "next":
                next();
                break;
            case "prev":
                prev();
                break;
            case "item":
                playIndex(Integer.parseInt(v), true);
                break;
            case "seek":
                seekTo(Long.parseLong(v));
                break;
            case "jump":
                seekBy(Long.parseLong(v));
                break;
            case "mute":
                muted = !muted;
                applyAudio();
                msg = muted ? "Muted" : "Volume " + volume + "%";
                break;
            case "audio":
                mp.setAudioTrack(Integer.parseInt(v));
                msg = T.t("Audio: ") + trackName(mp.getAudioTracks(), Integer.parseInt(v));
                break;
            case "sub":
                mp.setSpuTrack(Integer.parseInt(v));
                msg = T.t("Subtitles: ") + (Integer.parseInt(v) < 0 ? T.t("Off") : trackName(mp.getSpuTracks(), Integer.parseInt(v)));
                break;
            case "sdelay":
                subDelayMs = Math.max(-600000, Math.min(600000, Math.round(Float.parseFloat(v))));
                mp.setSpuDelay(subDelayMs * 1000);
                msg = "Subtitle delay " + fmtMs(subDelayMs);
                break;
            case "rate":
                rate = Math.max(0.25f, Math.min(4f, Float.parseFloat(v)));
                mp.setRate(rate);
                msg = "Speed " + fmtRate(rate);
                break;
            case "sleep": {
                int m = Integer.parseInt(v);
                sleepAfterItem = m < 0;
                sleepAt = m > 0 ? System.currentTimeMillis() + m * 60000L : 0;
                msg = T.t("Sleep timer: ") + sleepLabel();
                break;
            }
            case "subfile":
                addSubtitle(v, true);
                msg = T.t("Subtitle loaded");
                break;
            case "menu":
                showMenu();
                msg = "";
                break;
            case "repeat": {
                int rm = v.isEmpty() ? (repeatMode() + 1) % 3 : Math.max(0, Math.min(2, Integer.parseInt(v)));
                Store.putInt("repeat", rm);
                updateModeButtons();
                msg = T.t("Repeat: ") + repeatName();
                break;
            }
            case "shuffle":
                Store.putBool("shuffle", v.isEmpty() ? !shuffleOn() : "1".equals(v));
                updateModeButtons();
                msg = T.t("Shuffle: ") + T.t(shuffleOn() ? "On" : "Off");
                break;
            case "rotate": {
                int m = v.isEmpty() ? (Store.getInt("rotate", 1) + 1) % 3 : Integer.parseInt(v);
                Store.putInt("rotate", m);
                applyRotation();
                msg = T.t("Rotation: ") + T.t(m == 0 ? "Auto" : m == 2 ? "Portrait" : "Landscape");
                break;
            }
            case "lock":
                locked = "1".equals(v);
                if (locked) {
                    hideOverlayNow();
                    flashUnlock();
                    msg = "";
                } else {
                    unlockBtn.setVisibility(View.GONE);
                    showOverlay();
                    msg = T.t("Unlocked");
                }
                break;
            case "osd":
                showOverlay();
                msg = "";
                break;
            default:
                if (setting == null) return "Unknown command";
        }
        if (msg != null && !msg.isEmpty()) osd(msg);
        return msg == null ? "" : msg;
    }

    public JSONArray searchQueue(String query) throws Exception {
        String ql = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        JSONArray a = new JSONArray();
        for (int i = 0; i < queue.size() && a.length() < 200; i++) {
            if (!ql.isEmpty() && !queue.get(i).t.toLowerCase(Locale.ROOT).contains(ql)) continue;
            a.put(new JSONObject().put("i", i).put("t", queue.get(i).t).put("cur", i == index));
        }
        return a;
    }

    public List<Store.Item> queueCopy() {
        return new ArrayList<>(queue);
    }

    public void enqueue(Store.Item it) {
        queue.add(it);
        osd("Added to queue: " + it.t);
    }

    // =====================================================================
    // State for the phone page
    // =====================================================================
    public JSONObject state() throws Exception {
        JSONObject o = new JSONObject();
        o.put("player", mp != null);
        if (mp == null) return o;
        Store.Item it = current();
        o.put("title", it == null ? "" : it.t);
        o.put("idx", index);
        o.put("count", queue.size());
        o.put("pos", Math.max(0, mp.getTime()));
        o.put("len", Math.max(0, mp.getLength()));
        o.put("playing", mp.isPlaying());
        o.put("seekable", mp.isSeekable());
        o.put("vol", volume);
        o.put("muted", muted);
        o.put("rate", Math.round(rate * 100) / 100.0);
        o.put("sdelay", subDelayMs);
        o.put("audio", tracksJson(mp.getAudioTracks(), false));
        o.put("audioSel", mp.getAudioTrack());
        o.put("subs", tracksJson(mp.getSpuTracks(), true));
        o.put("subSel", mp.getSpuTrack());
        o.put("sleep", sleepLabel());
        o.put("repeat", repeatMode());
        o.put("shuffle", shuffleOn());
        o.put("loading", loadingView.getVisibility() == View.VISIBLE ? loadingView.getText().toString() : "");
        JSONArray q = new JSONArray();
        int qs = Math.max(0, Math.min(index - 50, queue.size() - 200));
        for (int i = qs; i < queue.size() && i < qs + 200; i++) q.put(queue.get(i).t);
        o.put("queue", q);
        o.put("qstart", qs);
        return o;
    }

    /** Settings that the phone page shows even when nothing plays. */
    public static void putSettings(JSONObject o) throws Exception {
        if (!o.has("vol")) o.put("vol", Store.getInt("vol", 100));
        o.put("aspect", Store.getStr("aspect", ""));
        o.put("fit", Store.getInt("fit", 0));
        o.put("zoom", Store.getInt("zoom", 100));
        o.put("subsize", Store.getInt("subsize", 16));
        o.put("subbg", Store.getBool("subbg", false));
        o.put("subenc", Store.getStr("subenc", ""));
        o.put("hw", Store.getInt("hw", 0));
        o.put("cache", Store.getInt("cache", 2000));
        o.put("adelay", Store.getInt("adelay", 0));
        JSONObject eq = new JSONObject();
        eq.put("on", Store.getBool("eq_on", false));
        eq.put("preset", Store.getInt("eq_preset", -1));
        eq.put("preamp", Math.round(Store.getFloat("eq_preamp", 0f) * 10) / 10.0);
        JSONArray bands = new JSONArray();
        float[] b = Store.getBands();
        float[] f = BAND_FREQS != null ? BAND_FREQS : DEFAULT_FREQS;
        for (int i = 0; i < b.length && i < f.length; i++) {
            bands.put(new JSONObject().put("f", freqLabel(f[i])).put("v", Math.round(b[i] * 10) / 10.0));
        }
        eq.put("bands", bands);
        o.put("eq", eq);
    }

    public static JSONObject optionsJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("aspects", pairs(ASPECT_VALUES, ASPECT_NAMES));
        String[] fitVals = new String[FIT_NAMES.length];
        for (int i = 0; i < fitVals.length; i++) fitVals[i] = String.valueOf(i);
        o.put("fits", pairs(fitVals, FIT_NAMES));
        String[] z = new String[ZOOM_VALUES.length];
        for (int i = 0; i < z.length; i++) z[i] = String.valueOf(ZOOM_VALUES[i]);
        o.put("zooms", pairs(z, ZOOM_NAMES));
        String[] ss = new String[SUB_SIZES.length];
        for (int i = 0; i < ss.length; i++) ss[i] = String.valueOf(SUB_SIZES[i]);
        o.put("subsizes", pairs(ss, SUB_SIZE_NAMES));
        o.put("encs", pairs(ENC_VALUES, ENC_NAMES));
        JSONArray sp = new JSONArray();
        for (float s : SPEEDS) sp.put(Math.round(s * 100) / 100.0);
        o.put("speeds", sp);
        JSONArray ca = new JSONArray();
        for (int c : CACHES) ca.put(c);
        o.put("caches", ca);
        JSONArray sl = new JSONArray();
        for (int s : SLEEPS) sl.put(s);
        o.put("sleeps", sl);
        String[] pr = PRESETS != null ? PRESETS : DEFAULT_PRESETS;
        o.put("presets", new JSONArray(java.util.Arrays.asList(pr)));
        return o;
    }

    private static JSONArray pairs(String[] vals, String[] names) {
        JSONArray a = new JSONArray();
        for (int i = 0; i < vals.length; i++) {
            JSONArray p = new JSONArray();
            p.put(vals[i]);
            p.put(names[i]);
            a.put(p);
        }
        return a;
    }

    private static JSONArray tracksJson(MediaPlayer.TrackDescription[] t, boolean addOff) throws Exception {
        JSONArray a = new JSONArray();
        boolean hasOff = false;
        if (t != null) for (MediaPlayer.TrackDescription d : t) if (d.id < 0) hasOff = true;
        if (addOff && !hasOff) a.put(new JSONObject().put("id", -1).put("name", T.t("Off")));
        if (t != null) for (MediaPlayer.TrackDescription d : t) {
            a.put(new JSONObject().put("id", d.id).put("name", niceName(d)));
        }
        return a;
    }

    // =====================================================================
    // Small helpers
    // =====================================================================
    private static String niceName(MediaPlayer.TrackDescription d) {
        if (d.id < 0) return T.t("Off");
        return d.name == null || d.name.isEmpty() ? "Track " + d.id : d.name;
    }

    private static String trackName(MediaPlayer.TrackDescription[] t, int id) {
        if (id < 0) return T.t("Off");
        if (t != null) for (MediaPlayer.TrackDescription d : t) if (d.id == id) return niceName(d);
        return "—";
    }

    private int aspectIndex() {
        String a = Store.getStr("aspect", "");
        for (int i = 0; i < ASPECT_VALUES.length; i++) if (ASPECT_VALUES[i].equals(a)) return i;
        return 0;
    }

    private int subSizeIndex() {
        int s = Store.getInt("subsize", 16);
        for (int i = 0; i < SUB_SIZES.length; i++) if (SUB_SIZES[i] == s) return i;
        return 2;
    }

    private int encIndex() {
        String e = Store.getStr("subenc", "");
        for (int i = 0; i < ENC_VALUES.length; i++) if (ENC_VALUES[i].equals(e)) return i;
        return 0;
    }

    private String eqPresetName() {
        int p = Store.getInt("eq_preset", -1);
        String[] pr = PRESETS != null ? PRESETS : DEFAULT_PRESETS;
        return p >= 0 && p < pr.length ? pr[p] : "Custom";
    }

    private String eqLabel() {
        return Store.getBool("eq_on", false) ? eqPresetName() : T.t("Off");
    }

    private String sleepLabel() {
        if (sleepAfterItem) return "end of video";
        if (sleepAt <= 0) return T.t("Off");
        long left = Math.max(0, sleepAt - System.currentTimeMillis());
        return (left / 60000 + 1) + " min";
    }

    private static String fmtMs(long ms) {
        return (ms > 0 ? "+" : "") + String.format(Locale.US, "%.2f s", ms / 1000f);
    }

    private static String fmtRate(float r) {
        return String.format(Locale.US, "%.2fx", r).replace(".00x", ".0x");
    }

    static String fmtDb(float v) {
        return String.format(Locale.US, "%+.1f dB", v);
    }

    static String freqLabel(float f) {
        return f >= 1000 ? String.format(Locale.US, "%.0f kHz", f / 1000f) : Math.round(f) + " Hz";
    }
}
