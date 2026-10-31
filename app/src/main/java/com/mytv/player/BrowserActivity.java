package com.mytv.player;

import android.annotation.SuppressLint;
import android.app.PictureInPictureParams;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Rational;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Browse the web, search, and play what you find: video/stream links found on a page show up
 * under "🎬 Videos"; long-press (or OK on) a link to play it. TV remote uses an on-screen cursor.
 */
public class BrowserActivity extends AppCompatActivity {
    public static final String EXTRA_URL = "url";

    private WebView web;
    private EditText bar;
    private ProgressBar loading;
    private CursorView cursor;
    private FrameLayout root, webBox;
    private LinearLayout mainColumn, topBar;
    private View customView;
    private WebChromeClient.CustomViewCallback customCb;
    private final Map<String, String> found = new LinkedHashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean tv;

    static final String[][] DEFAULT_BOOKMARKS = {
            {"Google", "https://www.google.com"},
            {"YouTube", "https://m.youtube.com"},
            {"Internet Archive – free movies", "https://archive.org/details/movies"},
            {"Dailymotion", "https://www.dailymotion.com"},
            {"Vimeo", "https://vimeo.com/watch"},
            {"Wikipedia", "https://www.wikipedia.org"},
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Store.init(this);
        Ui.apply(this, darkChrome());
        super.onCreate(savedInstanceState);
        systemBars();
        if (sleepAt > 0) {
            if (sleepAt < SystemClock.elapsedRealtime()) sleepAt = 0;
            else main.post(sleepFire);
        }
        tv = Ui.isTv(this);
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        mainColumn = new LinearLayout(this);
        mainColumn.setOrientation(LinearLayout.VERTICAL);
        root.addView(mainColumn, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        Shield.init(this);
        final boolean narrow = getResources().getConfiguration().screenWidthDp < 600 && !tv;

        // ---- top: toolbar (address bar, compact VPN button, Shield, Videos tracker, Tabs)
        topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        int p = Ui.dp(this, 8);
        topBar.setPadding(p, Ui.dp(this, 4), p, p);
        topBar.setBackgroundColor(Ui.BAR);
        if (!narrow) {
            topBar.addView(navBtn("arrow-left", v -> { if (web.canGoBack()) web.goBack(); }));
            topBar.addView(navBtn("arrow-right", v -> { if (web.canGoForward()) web.goForward(); }));
            topBar.addView(navBtn("house", v -> home()));
        }
        LinearLayout omni = new LinearLayout(this);
        omni.setGravity(Gravity.CENTER_VERTICAL);
        omni.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 4), 0);
        omni.setBackground(Ui.card(this, Ui.FIELD, Ui.STROKE, 24));
        siteIcon = new ImageView(this);
        siteIcon.setScaleType(ImageView.ScaleType.CENTER);
        omni.addView(siteIcon, new LinearLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 26)));
        siteIcon.setContentDescription(T.t("Site settings"));
        siteIcon.setOnClickListener(v -> SiteSettings.show(this, hosts.get(web), () -> web.reload()));
        bar = new EditText(this);
        bar.setSingleLine(true);
        bar.setHint(T.t("Search or type a web address"));
        bar.setTextColor(Ui.TEXT);
        bar.setHintTextColor(Ui.DIM);
        bar.setTextSize(15);
        bar.setBackground(null);
        bar.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8));
        bar.setImeOptions(EditorInfo.IME_ACTION_GO);
        bar.setSelectAllOnFocus(true);
        bar.setOnEditorActionListener((v, a1, e) -> {
            hideSuggest();
            go(bar.getText().toString());
            bar.clearFocus();
            return true;
        });
        bar.setOnFocusChangeListener((v, f) -> {
            if (f) {
                String u = web.getUrl();
                bar.setText(u == null || u.startsWith("data:") || u.startsWith("about:") || u.contains("tamilcastplay.local") ? "" : u);
                bar.selectAll();
            } else {
                hideSuggest();
                updateOmni();
            }
        });
        bar.addTextChangedListener(new android.text.TextWatcher() {
            int before;

            public void beforeTextChanged(CharSequence c, int st, int cnt, int after) {
                before = c.length();
            }

            public void onTextChanged(CharSequence c, int st, int b, int cnt) {
            }

            public void afterTextChanged(android.text.Editable e) {
                if (completing || !bar.hasFocus()) return;
                boolean typedMore = e.length() > before;
                suggest(e.toString(), typedMore);
            }
        });
        omni.addView(bar, new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1));
        reloadBtn = new ImageView(this);
        reloadBtn.setScaleType(ImageView.ScaleType.CENTER);
        reloadBtn.setImageDrawable(new UiKit.Icon(this, "refresh-cw", 18, Ui.DIM));
        reloadBtn.setFocusable(true);
        reloadBtn.setOnClickListener(v -> {
            if (pageLoading) web.stopLoading();
            else web.reload();
        });
        omni.addView(reloadBtn, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(0, Ui.dp(this, 46), 1);
        olp.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 6), 0);
        topBar.addView(omni, olp);

        // Compact VPN Logo Button
        vpnIcon = UiKit.roundButton(this, "lock", 42, false, v -> WgVpn.panel(this));
        vpnIcon.setContentDescription("VPN");
        LinearLayout.LayoutParams vpnLp = new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42));
        vpnLp.setMargins(0, 0, Ui.dp(this, 4), 0);
        topBar.addView(vpnIcon, vpnLp);

        // Brave-style shield with blocked trackers count
        FrameLayout sh = new FrameLayout(this);
        shieldIcon = UiKit.roundButton(this, "shield-check", 42, false, v -> shieldPanel());
        sh.addView(shieldIcon, new FrameLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));
        shieldBadge = Ui.text(this, 10, Color.WHITE, true);
        shieldBadge.setGravity(Gravity.CENTER);
        shieldBadge.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        shieldBadge.setBackground(Ui.card(this, SHIELD_ORANGE, 0, 9));
        sh.addView(shieldBadge, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 18), Gravity.TOP | Gravity.END));
        LinearLayout.LayoutParams shLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        shLp.setMargins(0, 0, Ui.dp(this, 4), 0);
        topBar.addView(sh, shLp);

        // Compact Videos Button with Ad-Tracker Badge Style
        FrameLayout vidBox = new FrameLayout(this);
        videosIcon = UiKit.roundButton(this, "film", 42, false, v -> showVideos());
        videosIcon.setContentDescription(T.t("Videos on page"));
        vidBox.addView(videosIcon, new FrameLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));
        videosBadge = Ui.text(this, 10, Color.WHITE, true);
        videosBadge.setGravity(Gravity.CENTER);
        videosBadge.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        videosBadge.setBackground(Ui.card(this, Ui.ACCENT, 0, 9));
        videosBadge.setVisibility(View.GONE);
        vidBox.addView(videosBadge, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 18), Gravity.TOP | Gravity.END));
        topBar.addView(vidBox);

        tabsBtn = tabCounter();
        if (!narrow) {
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40));
            tl.setMargins(Ui.dp(this, 6), 0, 0, 0);
            topBar.addView(tabsBtn, tl);
            topBar.addView(navBtn("ellipsis-vertical", v -> menu()));
        }
        mainColumn.addView(topBar);

        loading = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        loading.setMax(100);
        loading.setProgressDrawable(new android.graphics.drawable.ClipDrawable(Ui.gradient(this, 0), Gravity.START,
                android.graphics.drawable.ClipDrawable.HORIZONTAL));
        loading.setBackgroundColor(0x00000000);
        mainColumn.addView(loading, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 3)));
        bottomBar = narrow ? buildBottomBar() : null;
        webBox = new FrameLayout(this);
        web = new BgWebView(this);
        tabs.add(web);
        webBox.addView(web, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (tv) {
            cursor = new CursorView(this);
            webBox.addView(cursor, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        mainColumn.addView(webBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        findBar = buildFindBar();
        mainColumn.addView(findBar);
        if (bottomBar != null) mainColumn.addView(bottomBar);
        setContentView(root);

        configure(web);

        String start = getIntent().getStringExtra(EXTRA_URL);
        if (start != null && !start.isEmpty()) go(start);
        else if ((Store.getBool("b_restore", true) || Store.getBool("b_restore_once", false)) && !BrowserData.savedTabs().isEmpty()) {
            Store.putBool("b_restore_once", false);
            restoreTabs();
        }
        else home();
        if (tv) web.requestFocus();
        WgVpn.onChange = () -> main.post(this::updateVpnBar);
        updateVpnBar();
        buildEqFab();
        MusicFx.onChange = () -> main.post(() -> {
            String js = BrowserMedia.eqCall();
            for (WebView t : tabs) t.evaluateJavascript(js, null);
        });
        BrowserPlayService.control = c -> main.post(() -> {
            for (WebView t : tabs) t.evaluateJavascript("window.__zhCmd&&window.__zhCmd('" + c + "')", null);
        });
    }

    // ---------------------------------------------------------------- Picture-in-Picture (PiP)
    // Script overrides the Page Visibility API and intercepts programmatic pause attempts
    private static final String RESUME_VIDEO_JS =
            "(function() {"
            + "  try {"
            + "    Object.defineProperty(document, 'hidden', { value: false, writable: true, configurable: true });"
            + "    Object.defineProperty(document, 'visibilityState', { value: 'visible', writable: true, configurable: true });"
            + "    document.dispatchEvent(new Event('visibilitychange'));"
            + "  } catch (e) {}"
            + "  try {"
            + "    var mp = document.getElementById('movie_player');"
            + "    if (mp && mp.playVideo) { mp.playVideo(); }"
            + "  } catch (e) {}"
            + "  document.querySelectorAll('video').forEach(function(v) {"
            + "    if (v.paused) { v.play().catch(function() {}); }"
            + "  });"
            + "})();";

    public void resumeMediaForPip() {
        if (web != null) {
            web.onResume();
            web.evaluateJavascript(RESUME_VIDEO_JS, null);
        }
    }

    public boolean enterPipMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;
        try {
            PictureInPictureParams.Builder b = new PictureInPictureParams.Builder();
            View target = (customView != null && customView.getWidth() > 0) ? customView : webBox;
            int w = target != null ? target.getWidth() : 0;
            int h = target != null ? target.getHeight() : 0;
            if (w > 0 && h > 0) {
                float r = (float) w / (float) h;
                // Ratio must be within Android's strict bounds [1:2.39, 2.39:1]
                if (r >= 0.42f && r <= 2.38f) {
                    b.setAspectRatio(new Rational(Math.round(r * 1000), 1000));
                } else {
                    b.setAspectRatio(new Rational(16, 9));
                }
                Rect rect = new Rect();
                target.getGlobalVisibleRect(rect);
                b.setSourceRectHint(rect);
            } else {
                b.setAspectRatio(new Rational(16, 9));
            }
            return enterPictureInPictureMode(b.build());
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if ((pagePlaying || customView != null) && !tv) {
            enterPipMode();
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        handlePipChange(isInPictureInPictureMode);
    }

    private void handlePipChange(boolean inPip) {
        if (inPip) {
            if (topBar != null) topBar.setVisibility(View.GONE);
            if (bottomBar != null) bottomBar.setVisibility(View.GONE);
            if (findBar != null) findBar.setVisibility(View.GONE);
            if (loading != null) loading.setVisibility(View.GONE);
            if (eqFab != null) eqFab.setVisibility(View.GONE);
            if (lockBtn != null) lockBtn.setVisibility(View.GONE);
            if (lockLayer != null) lockLayer.setVisibility(View.GONE);
            View eq = root.findViewWithTag("fs_eq");
            if (eq != null) eq.setVisibility(View.GONE);
            View pipBtn = root.findViewWithTag("fs_pip");
            if (pipBtn != null) pipBtn.setVisibility(View.GONE);
            hideSuggest();

            resumeMediaForPip();
            main.postDelayed(this::resumeMediaForPip, 300);
            main.postDelayed(this::resumeMediaForPip, 800);
            main.postDelayed(this::resumeMediaForPip, 1500);
        } else {
            if (topBar != null) topBar.setVisibility(View.VISIBLE);
            if (bottomBar != null) bottomBar.setVisibility(View.VISIBLE);
            if (eqFab != null) eqFab.setVisibility(pageMedia && Store.getBool("b_eq", true) && customView == null ? View.VISIBLE : View.GONE);
            if (lockBtn != null) lockBtn.setVisibility(customView != null ? View.VISIBLE : View.GONE);
            if (lockLayer != null) lockLayer.setVisibility(View.VISIBLE);
            View eq = root.findViewWithTag("fs_eq");
            if (eq != null) eq.setVisibility(Store.getBool("b_eq", true) ? View.VISIBLE : View.GONE);
            View pipBtn = root.findViewWithTag("fs_pip");
            if (pipBtn != null) pipBtn.setVisibility(View.VISIBLE);

            resumeMediaForPip();
        }
    }

    // ---------------------------------------------------------------- open tabs kept between starts
    private void restoreTabs() {
        List<String[]> st = BrowserData.savedTabs();
        int cur = 0;
        try {
            cur = Integer.parseInt(st.get(st.size() - 1)[2]);
        } catch (Exception ignored) {
        }
        go(st.get(0)[1]);
        for (int i = 1; i < st.size(); i++) newTab(st.get(i)[1]);
        if (cur >= 0 && cur < tabs.size()) switchTo(tabs.get(cur));
    }

    private void saveTabs() {
        List<String[]> l = new ArrayList<>();
        for (WebView t : tabs) {
            String u = t.getUrl();
            if (u != null && u.startsWith("http")) l.add(new String[]{t.getTitle() == null ? "" : t.getTitle(), u});
        }
        if (Store.getBool("b_private", false)) l.clear();
        BrowserData.saveTabs(l, Math.max(0, tabs.indexOf(web)));
    }

    // ---------------------------------------------------------------- media on pages: background play, EQ button
    private ImageView eqFab;
    private volatile boolean pageMedia, pagePlaying;
    private String lastEqWarn = "";

    private void buildEqFab() {
        eqFab = UiKit.roundButton(this, "sliders-vertical", 50, true, v -> eqSheet());
        eqFab.setContentDescription(T.t("Equalizer"));
        eqFab.setElevation(Ui.dp(this, 8));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.dp(this, 50), Ui.dp(this, 50), Gravity.BOTTOM | Gravity.END);
        lp.setMargins(0, 0, Ui.dp(this, 16), Ui.dp(this, 16));
        webBox.addView(eqFab, lp);
        eqFab.setVisibility(View.GONE);
    }

    private volatile String pageSong = "";

    private void mediaState(boolean playing, String title, boolean video, boolean has) {
        mediaState(playing, title, video, has, -1, -1);
    }

    private void mediaState(boolean playing, String title, boolean video, boolean has, long pos, long dur) {
        if (!pageSong.isEmpty()) title = pageSong;
        else if (title != null) title = title.replaceAll("\\s*-\\s*YouTube$", "");
        pageMedia = has;
        pagePlaying = playing;
        if (eqFab != null) eqFab.setVisibility(has && Store.getBool("b_eq", true) && customView == null ? View.VISIBLE : View.GONE);
        if (lockBtn != null) lockBtn.setVisibility(customView != null ? View.VISIBLE : View.GONE);
        if (!Store.getBool("b_bgplay", true)) return;
        if (playing) {
            askNotifications();
            BrowserPlayService.update(this, title, true, video, pos, dur);
        } else if (has && BrowserPlayService.title.length() > 0) BrowserPlayService.update(this, title, false, video, pos, dur);
        else if (!has) BrowserPlayService.stop(this);
    }

    private boolean askedNotif;

    private void askNotifications() {
        if (askedNotif || Build.VERSION.SDK_INT < 33 || tv) return;
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        askedNotif = true;
        if (shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS) || !Store.getBool("asked_notif", false)) {
            Store.putBool("asked_notif", true);
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 76);
            return;
        }
        new UiDialog(this).setTitle(T.t("Play / pause from the notification"))
                .setMessage(T.t("Notifications are off for ZH Play, so the player controls (play / pause, timeline) can't be shown. Turn them on?"))
                .setPositiveButton(T.t("Open settings"), (d, w) -> {
                    try {
                        startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName()));
                    } catch (Exception ignored) {
                    }
                })
                .setNegativeButton(T.t("Not now"), null).show();
    }

    private void injectMedia(WebView v) {
        v.evaluateJavascript(BrowserMedia.js(Store.getBool("b_bgplay", true)), null);
        v.evaluateJavascript(BrowserMedia.eqCall(), null);
    }

    // ---------------------------------------------------------------- equalizer panel over the page
    private void eqSheet() {
        final android.app.Dialog d = new android.app.Dialog(this);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 14));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xF20B0B0F);
        float rr = Ui.dp(this, 22);
        bg.setCornerRadii(new float[]{rr, rr, rr, rr, 0, 0, 0, 0});
        box.setBackground(bg);
        LinearLayout h = new LinearLayout(this);
        h.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = Ui.text(this, 16, android.graphics.Color.WHITE, true);
        t.setText(T.t("Equalizer"));
        h.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final android.widget.Button on = new android.widget.Button(this);
        on.setAllCaps(false);
        on.setTextColor(android.graphics.Color.WHITE);
        final Runnable paintOn = () -> {
            on.setText(MusicFx.on() ? "ON" : "OFF");
            on.setBackground(MusicFx.on() ? Ui.gradient(this, 16) : Ui.card(this, 0xFF2A2A33, 0, 16));
        };
        paintOn.run();
        on.setOnClickListener(v -> {
            MusicFx.setOn(!MusicFx.on());
            paintOn.run();
        });
        h.addView(on, new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 36)));
        final android.widget.Button pr = new android.widget.Button(this);
        pr.setAllCaps(false);
        pr.setTextColor(android.graphics.Color.WHITE);
        pr.setText(MusicFx.presetLabel());
        pr.setBackground(Ui.card(this, 0xFF24242C, 0, 16));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 36));
        pl.setMargins(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0);
        h.addView(pr, pl);
        h.addView(UiKit.darkButton(this, "maximize", 36, false, v -> {
            d.dismiss();
            startActivity(new android.content.Intent(this, PowerEqActivity.class));
        }));
        box.addView(h);
        final PowerViews.EqCurveView curve = new PowerViews.EqCurveView(this);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 80));
        cl.setMargins(0, Ui.dp(this, 8), 0, Ui.dp(this, 6));
        box.addView(curve, cl);
        final int[] cols = {0xFFEF4444, 0xFFF97316, 0xFFF59E0B, 0xFFEAB308, 0xFF84CC16, 0xFF22C55E, 0xFF14B8A6, 0xFF06B6D4, 0xFF3B82F6, 0xFF8B5CF6};
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        hs.addView(row);
        final List<PowerViews.GlowSlider> sliders = new ArrayList<>();
        for (int i = -1; i < 10; i++) {
            final int k = i;
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            PowerViews.GlowSlider s = new PowerViews.GlowSlider(this, k < 0 ? -1200 : MusicFx.MIN, k < 0 ? 1200 : MusicFx.MAX, k < 0 ? 0xFFE5E7EB : cols[k]);
            s.setValue(k < 0 ? MusicFx.preamp() : MusicFx.bands()[k]);
            s.setContentDescription(k < 0 ? T.t("Preamp") : MusicFx.LABELS[k]);
            s.setListener((v, done) -> {
                if (k < 0) MusicFx.setPreamp(v);
                else {
                    int[] b = MusicFx.bands();
                    b[k] = v;
                    MusicFx.setBands(b);
                    Store.putStr("mfx_user_cur", "");
                    Store.putInt("mfx_preset", -1);
                }
                MusicFx.apply();
                curve.invalidate();
                pr.setText(MusicFx.presetLabel());
            });
            sliders.add(s);
            col.addView(s, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 160)));
            TextView n = Ui.text(this, 11, android.graphics.Color.WHITE, true);
            n.setText(k < 0 ? T.t("Pre") : MusicFx.LABELS[k]);
            col.addView(n);
            row.addView(col, new LinearLayout.LayoutParams(Ui.dp(this, 48), ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        box.addView(hs);
        LinearLayout tone = new LinearLayout(this);
        tone.setGravity(Gravity.CENTER);
        tone.setPadding(0, Ui.dp(this, 8), 0, 0);
        final PowerViews.Knob kb = new PowerViews.Knob(this, -100, 100, 0, 0xFFEF4444, true);
        final PowerViews.Knob kt = new PowerViews.Knob(this, -100, 100, 0, 0xFF22C55E, true);
        kb.setValue(MusicFx.bassTone());
        kt.setValue(MusicFx.trebleTone());
        kb.setContentDescription(T.t("Bass"));
        kt.setContentDescription(T.t("Treble"));
        PowerViews.OnValue tl = (v, done) -> {
            MusicFx.setTone(kb.get(), kt.get());
            MusicFx.apply();
            curve.invalidate();
        };
        kb.setListener(tl);
        kt.setListener(tl);
        for (Object[] o : new Object[][]{{kb, T.t("Bass")}, {kt, T.t("Treble")}}) {
            LinearLayout c = new LinearLayout(this);
            c.setOrientation(LinearLayout.VERTICAL);
            c.setGravity(Gravity.CENTER_HORIZONTAL);
            c.addView((View) o[0], new LinearLayout.LayoutParams(Ui.dp(this, 76), Ui.dp(this, 76)));
            TextView n = Ui.text(this, 12, android.graphics.Color.WHITE, true);
            n.setText((String) o[1]);
            c.addView(n);
            tone.addView(c, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        box.addView(tone);
        pr.setOnClickListener(v -> {
            String[] names = new String[MusicFx.PRESETS.length];
            for (int i = 0; i < names.length; i++) names[i] = T.t(MusicFx.PRESETS[i]);
            new UiDialog(this).setTitle(T.t("Presets")).setItems(names, (dd, w) -> {
                MusicFx.preset(w);
                MusicFx.apply();
                int[] b = MusicFx.bands();
                for (int i = 0; i < 10; i++) sliders.get(i + 1).setValue(b[i]);
                curve.invalidate();
                pr.setText(MusicFx.presetLabel());
            }).show();
        });
        if (lastEqWarn.equals(hosts.get(web))) {
            TextView warn = Ui.text(this, 11, 0xFFFBBF24, false);
            warn.setText(T.t("This site doesn't allow the equalizer on its video — it plays normally"));
            warn.setPadding(0, Ui.dp(this, 8), 0, 0);
            box.addView(warn);
        }
        d.setContentView(box);
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        d.show();
    }

    // ---------------------------------------------------------------- fullscreen video: lock against mis-touches
    private FrameLayout lockLayer;
    private ImageView lockBtn;
    private boolean locked;
    private int oldOrientation = -1;
    private final Runnable hideLock = () -> {
        if (lockBtn != null && customView != null) lockBtn.animate().alpha(locked ? 0f : 0.35f).setDuration(300).start();
    };

    private void showLockUi() {
        lockLayer = new FrameLayout(this);
        lockLayer.setOnTouchListener((v, e) -> {
            if (!locked) return false;
            if (e.getAction() == android.view.MotionEvent.ACTION_DOWN) flashLock();
            return true;
        });
        root.addView(lockLayer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        lockBtn = UiKit.darkButton(this, "lock", 48, false, v -> {
            locked = !locked;
            lockLayer.setClickable(locked);
            UiKit.setIcon(this, lockBtn, "lock", 22, locked ? 0xFFEAB308 : android.graphics.Color.WHITE);
            UiKit.makeToast(this, T.t(locked ? "Screen locked — tap the lock to unlock" : "Unlocked"), Toast.LENGTH_SHORT).show();
            flashLock();
        });
        lockBtn.setContentDescription(T.t("Lock"));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48), Gravity.TOP | Gravity.START);
        lp.setMargins(Ui.dp(this, 20), Ui.dp(this, 24), 0, 0);
        root.addView(lockBtn, lp);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        if (!tv && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ImageView pip = UiKit.darkButton(this, "maximize", 48, false, v -> {
                if (!locked) enterPipMode();
            });
            pip.setContentDescription(T.t("Picture in Picture"));
            pip.setTag("fs_pip");
            actions.addView(pip, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        }

        ImageView eq = UiKit.darkButton(this, "sliders-vertical", 48, false, v -> {
            if (!locked) eqSheet();
        });
        eq.setContentDescription(T.t("Equalizer"));
        eq.setTag("fs_eq");
        LinearLayout.LayoutParams eqLp = new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48));
        eqLp.setMargins(Ui.dp(this, 8), 0, 0, 0);
        if (Store.getBool("b_eq", true)) actions.addView(eq, eqLp);

        FrameLayout.LayoutParams el = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 48), Gravity.TOP | Gravity.END);
        el.setMargins(0, Ui.dp(this, 24), Ui.dp(this, 20), 0);
        root.addView(actions, el);

        flashLock();
        if (!tv) {
            oldOrientation = getRequestedOrientation();
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        }
    }

    private void flashLock() {
        if (lockBtn == null) return;
        lockBtn.animate().alpha(1f).setDuration(150).start();
        main.removeCallbacks(hideLock);
        main.postDelayed(hideLock, 3000);
    }

    private void removeLockUi() {
        locked = false;
        if (lockLayer != null) root.removeView(lockLayer);
        if (lockBtn != null) root.removeView(lockBtn);
        View eq = root.findViewWithTag("fs_eq");
        if (eq != null && eq.getParent() instanceof ViewGroup) ((ViewGroup) eq.getParent()).removeView(eq);
        View pip = root.findViewWithTag("fs_pip");
        if (pip != null && pip.getParent() instanceof ViewGroup) ((ViewGroup) pip.getParent()).removeView(pip);
        lockLayer = null;
        lockBtn = null;
        if (!tv && oldOrientation != -2) {
            setRequestedOrientation(oldOrientation == -1 ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED : oldOrientation);
        }
    }

    static int darkMode() {
        return Store.getInt("b_dark", 0);
    }

    static final String AUDIO_JS = "(function(){if(window.__zhAudio)return;window.__zhAudio=1;"
            + "var st=document.createElement('style');st.id='zh-audio';"
            + "st.textContent='video{opacity:0!important}.zh-am{position:absolute;inset:0;z-index:2147483646;display:flex;"
            + "flex-direction:column;align-items:center;justify-content:center;background:linear-gradient(135deg,#2a1f4d,#0b0913);"
            + "color:#fff;font:600 15px/1.4 sans-serif;pointer-events:none;text-align:center}.zh-am b{font-size:44px;margin-bottom:6px}"
            + ".zh-am small{opacity:.7;font-weight:400;font-size:12px}';"
            + "(document.head||document.documentElement).appendChild(st);"
            + "function tick(){document.querySelectorAll('video').forEach(function(v){var p=v.parentElement;if(!p)return;"
            + "for(var i=0;i<p.children.length;i++)if(p.children[i].className==='zh-am')return;"
            + "var d=document.createElement('div');d.className='zh-am';d.innerHTML='<b>🎧</b>Audio mode<small>video hidden · sound playing</small>';"
            + "if(getComputedStyle(p).position==='static')p.style.position='relative';p.appendChild(d);});"
            + "var mp=document.getElementById('movie_player');"
            + "if(mp&&mp.setPlaybackQualityRange){try{mp.setPlaybackQualityRange('tiny','tiny');}catch(e){}}}"
            + "tick();window.__zhAudioT=setInterval(tick,1500);})();";
    static final String VIDEO_JS = "(function(){if(!window.__zhAudio)return;window.__zhAudio=0;clearInterval(window.__zhAudioT);"
            + "var s=document.getElementById('zh-audio');if(s)s.remove();document.querySelectorAll('.zh-am').forEach(function(d){d.remove();});"
            + "var mp=document.getElementById('movie_player');if(mp&&mp.setPlaybackQualityRange){try{mp.setPlaybackQualityRange('auto','auto');}catch(e){}}})();";

    static boolean audioMode() {
        return Store.getBool("b_audio", false);
    }

    private void setAudioMode(boolean on) {
        Store.putBool("b_audio", on);
        for (WebView t : tabs) t.evaluateJavascript(on ? AUDIO_JS : VIDEO_JS, null);
        UiKit.makeToast(this, on ? "🎧 " + T.t("Audio mode on — video hidden, sound keeps playing") : T.t("Audio mode off"),
                Toast.LENGTH_SHORT).show();
    }

    private static long sleepAt;

    private final Runnable sleepFire = new Runnable() {
        public void run() {
            long left = sleepAt - SystemClock.elapsedRealtime();
            if (sleepAt == 0) return;
            if (left > 10_000) {
                main.postDelayed(this, Math.min(left - 10_000, 60_000));
                return;
            }
            for (WebView t : tabs) t.evaluateJavascript(FADE_JS, null);
            main.postDelayed(() -> {
                if (sleepAt == 0) return;
                sleepAt = 0;
                for (WebView t : tabs) t.evaluateJavascript("document.querySelectorAll('video,audio').forEach(function(m){m.pause();});", null);
                BrowserPlayService.stop(BrowserActivity.this);
                UiKit.makeToast(BrowserActivity.this, "⏲ " + T.t("Sleep timer — browser closed"), Toast.LENGTH_LONG).show();
                saveTabs();
                if (Build.VERSION.SDK_INT >= 21) finishAndRemoveTask();
                else finish();
            }, Math.max(0, left));
        }
    };
    private static final String FADE_JS = "(function(){var k=0;var t=setInterval(function(){k++;"
            + "document.querySelectorAll('video,audio').forEach(function(m){if(m.__zv===undefined)m.__zv=m.volume;"
            + "m.volume=Math.max(0,m.__zv*(1-k/10));});if(k>=10)clearInterval(t);},1000);})();";

    private String sleepLabel() {
        if (sleepAt == 0) return T.t("Off");
        long m = Math.max(0, (sleepAt - SystemClock.elapsedRealtime() + 59_999) / 60_000);
        return m + " " + T.t("min left");
    }

    private void sleepTimer() {
        final int[] mins = {0, 10, 15, 30, 45, 60, 90, 120, -1};
        String[] names = new String[mins.length];
        for (int i = 0; i < mins.length; i++)
            names[i] = mins[i] == 0 ? T.t("Off") : mins[i] < 0 ? T.t("Custom…") : mins[i] + " " + T.t("minutes");
        new UiDialog(this).setTitle("⏲ " + T.t("Sleep timer") + " · " + sleepLabel()).setItems(names, (d, w) -> {
            if (mins[w] < 0) {
                final EditText e = new EditText(this);
                e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
                e.setHint(T.t("minutes"));
                new UiDialog(this).setTitle(T.t("Sleep timer")).setView(e).setPositiveButton(T.t("Start"), (d2, w2) -> {
                    try {
                        startSleep(Math.max(1, Math.min(600, Integer.parseInt(e.getText().toString().trim()))));
                    } catch (Exception ignored) {
                    }
                }).setNegativeButton(T.t("Cancel"), null).show();
                return;
            }
            startSleep(mins[w]);
        }).show();
    }

    private void startSleep(int minutes) {
        main.removeCallbacks(sleepFire);
        if (minutes <= 0) {
            sleepAt = 0;
            UiKit.makeToast(this, T.t("Sleep timer off"), Toast.LENGTH_SHORT).show();
            return;
        }
        sleepAt = SystemClock.elapsedRealtime() + minutes * 60_000L;
        main.post(sleepFire);
        UiKit.makeToast(this, "⏲ " + T.t("The browser closes in") + " " + minutes + " " + T.t("minutes")
                + " (" + T.t("sound fades out first") + ")", Toast.LENGTH_LONG).show();
    }

    private boolean darkChrome() {
        int m = darkMode();
        if (m == 1) return true;
        if (m == 2) return false;
        int ui = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return ui == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    @SuppressWarnings("deprecation")
    private void systemBars() {
        android.view.Window w = getWindow();
        int barColor = Ui.dark() ? 0xFF0F0D16 : 0xFFF6F5FB;
        w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        w.setStatusBarColor(barColor);
        w.setNavigationBarColor(barColor);
        if (Build.VERSION.SDK_INT >= 23) {
            View d = w.getDecorView();
            int f = d.getSystemUiVisibility();
            f = Ui.dark() ? f & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : f | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26)
                f = Ui.dark() ? f & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : f | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            d.setSystemUiVisibility(f);
        }
    }

    private void redrawKeepingTabs() {
        saveTabs();
        Store.putBool("b_restore_once", true);
        recreate();
    }

    private boolean wantDark() {
        int m = darkMode();
        if (m == 2) return false;
        if (m == 1) return true;
        int ui = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return ui == android.content.res.Configuration.UI_MODE_NIGHT_YES || Ui.dark();
    }

    private boolean cssDark() {
        if (!wantDark()) return false;
        if (Build.VERSION.SDK_INT >= 33) return !Ui.dark();
        return Build.VERSION.SDK_INT < 29;
    }

    @SuppressWarnings("deprecation")
    private void applyDark(WebView w) {
        boolean on = wantDark();
        WebSettings st = w.getSettings();
        try {
            if (Build.VERSION.SDK_INT >= 33) st.setAlgorithmicDarkeningAllowed(on);
            else if (Build.VERSION.SDK_INT >= 29) st.setForceDark(on ? WebSettings.FORCE_DARK_ON : WebSettings.FORCE_DARK_OFF);
        } catch (Throwable ignored) {
        }
    }

    private static final String DARK_JS = "(function(){if(document.getElementById('zh-dark'))return;"
            + "if(window.matchMedia&&matchMedia('(prefers-color-scheme: dark)').matches){var bg=getComputedStyle(document.body||document.documentElement).backgroundColor;"
            + "var m=bg.match(/\\d+/g);if(m&&(+m[0]+ +m[1]+ +m[2])<200)return;}"
            + "var s=document.createElement('style');s.id='zh-dark';"
            + "s.textContent='html{filter:invert(1) hue-rotate(180deg)!important;background:#fff!important}"
            + "img,video,picture,canvas,iframe,svg image,[style*=\"background-image\"]{filter:invert(1) hue-rotate(180deg)!important}';"
            + "(document.head||document.documentElement).appendChild(s);})();";
    private static final String LIGHT_JS = "(function(){var s=document.getElementById('zh-dark');if(s)s.remove();})();";

    private void configure(final WebView web) {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        applyDark(web);
        s.setGeolocationEnabled(true);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setDomStorageEnabled(true);
        // Do not require user tap to resume or play videos (crucial for PiP playback)
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        applyUa(web);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return handleNav(r.getUrl().toString());
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap f) {
                String h = android.net.Uri.parse(url).getHost();
                hosts.put(v, h == null ? "" : h.toLowerCase(java.util.Locale.ROOT));
                applySite(v, hosts.get(v));
                pageBlocked.put(v, 0);
                if (v != web) return;
                pageLoading = true;
                found.clear();
                updateVideos();
                updateOmni();
                updateShield();
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                if (audioMode() && url != null && !url.contains("tamilcastplay.local")) v.evaluateJavascript(AUDIO_JS, null);
                if (cssDark() && url != null && !url.contains("tamilcastplay.local")) v.evaluateJavascript(DARK_JS, null);
                if (url != null && url.startsWith("http") && !Store.getBool("b_private", false)) BrowserData.visit(v.getTitle(), url);
                injectMedia(v);
                saveTabs();
                String h = hosts.get(v);
                if (Shield.enabled() && !Shield.siteOff(h)) {
                    v.evaluateJavascript(Shield.cosmeticJs(), null);
                    if (h != null && h.contains("youtube.com") && Shield.ytSkip()) v.evaluateJavascript(Shield.youtubeJs(), null);
                }
                if (v == web) {
                    pageLoading = false;
                    updateOmni();
                    updateShield();
                }
                Shield.save();
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                if (!r.isForMainFrame() && Shield.blocked(u, hosts.get(v))) {
                    Shield.counted();
                    Integer n = pageBlocked.get(v);
                    pageBlocked.put(v, n == null ? 1 : n + 1);
                    if (v == web) main.post(BrowserActivity.this::updateShield);
                    return new WebResourceResponse("text/plain", "utf-8", new java.io.ByteArrayInputStream(new byte[0]));
                }
                sniff(u, null);
                return null;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int pr) {
                if (pr >= 10) v.evaluateJavascript(SiteSettings.pageJs(hosts.get(v)), null);
                String yh = hosts.get(v);
                if (pr >= 5 && yh != null && yh.contains("youtube.com") && Shield.enabled() && !Shield.siteOff(yh) && Shield.ytSkip()) {
                    v.evaluateJavascript(Shield.youtubeJs(), null);
                }
                if (pr >= 70) injectMedia(v);
                loading.setProgress(pr);
                loading.setVisibility(pr >= 100 ? View.INVISIBLE : View.VISIBLE);
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(final String origin, final android.webkit.GeolocationPermissions.Callback cb) {
                final String h = hostOf(origin);
                ask(h, "location", new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION},
                        ok -> cb.invoke(origin, ok, false));
            }

            @Override
            public void onPermissionRequest(final android.webkit.PermissionRequest req) {
                final String h = hostOf(req.getOrigin().toString());
                final List<String> grant = new ArrayList<>();
                final List<String> android_ = new ArrayList<>();
                final List<String> keys = new ArrayList<>();
                for (String res : req.getResources()) {
                    if (android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res)) {
                        keys.add("camera");
                        android_.add(android.Manifest.permission.CAMERA);
                    } else if (android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)) {
                        keys.add("mic");
                        android_.add(android.Manifest.permission.RECORD_AUDIO);
                    } else if (android.webkit.PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(res)) {
                        grant.add(res);
                    }
                }
                if (keys.isEmpty()) {
                    if (grant.isEmpty()) req.deny();
                    else req.grant(grant.toArray(new String[0]));
                    return;
                }
                askMany(h, keys, android_, ok -> {
                    if (ok) {
                        for (String res : req.getResources()) if (!grant.contains(res)) grant.add(res);
                        req.grant(grant.toArray(new String[0]));
                    } else if (!grant.isEmpty()) req.grant(grant.toArray(new String[0]));
                    else req.deny();
                });
            }

            @Override
            public boolean onShowFileChooser(WebView v, android.webkit.ValueCallback<Uri[]> cb, FileChooserParams params) {
                String h = hosts.get(v);
                if (!SiteSettings.allowed(h, "files")) {
                    UiKit.makeToast(BrowserActivity.this, T.t("File uploads are blocked for this site — tap the lock to allow"), Toast.LENGTH_LONG).show();
                    cb.onReceiveValue(null);
                    return true;
                }
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try {
                    Intent i = params.createIntent();
                    if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    startActivityForResult(Intent.createChooser(i, T.t("Choose a file")), 72);
                } catch (Exception e) {
                    fileCb = null;
                    cb.onReceiveValue(null);
                }
                return true;
            }

            @Override
            public boolean onCreateWindow(WebView v, boolean dialog, boolean userGesture, android.os.Message msg) {
                String h = hosts.get(v);
                if (!SiteSettings.allowed(h, "popups")) {
                    UiKit.makeToast(BrowserActivity.this, "🪟 " + T.t("Pop-up blocked — tap the lock to allow pop-ups here"), Toast.LENGTH_SHORT).show();
                    return false;
                }
                WebView w = new BgWebView(BrowserActivity.this);
                configure(w);
                tabs.add(w);
                switchTo(w);
                tabsBtn.setText(String.valueOf(tabs.size()));
                ((WebView.WebViewTransport) msg.obj).setWebView(w);
                msg.sendToTarget();
                return true;
            }

            @Override
            public void onReceivedTitle(WebView v, String title) {
                BrowserData.title(v.getUrl(), title);
            }

            @Override
            public void onReceivedIcon(WebView v, Bitmap icon) {
                BrowserData.saveIcon(BrowserActivity.this, v.getUrl(), icon);
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback cb) {
                customView = view;
                customCb = cb;
                root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                showLockUi();
                if (eqFab != null) eqFab.setVisibility(View.GONE);
            }

            @Override
            public void onHideCustomView() {
                hideCustom();
            }
        });
        web.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void ad() {
                Shield.counted();
                Integer n = pageBlocked.get(web);
                pageBlocked.put(web, n == null ? 1 : n + 1);
                main.post(BrowserActivity.this::updateShield);
            }
        }, "TamilShield");
        web.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void state(final boolean playing, final String title, final boolean video, final boolean has, final double pos, final double dur) {
                main.post(() -> mediaState(playing, title, video, has, (long) (pos * 1000), (long) (dur * 1000)));
            }

            @android.webkit.JavascriptInterface
            public void meta(final String t, final String artist, final String art) {
                pageSong = t == null ? "" : t;
                BrowserPlayService.artist = artist == null ? "" : artist;
                BrowserPlayService.artwork(art);
            }

            @android.webkit.JavascriptInterface
            public void eq(final String status) {
                if (!"blocked".equals(status)) return;
                main.post(() -> {
                    String h = hosts.get(web);
                    if (h != null && !h.equals(lastEqWarn)) {
                        lastEqWarn = h;
                        UiKit.makeToast(BrowserActivity.this, T.t("This site doesn't allow the equalizer on its video — it plays normally"),
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "ZHMedia");
        web.setFindListener((active, count, done) -> {
            if (findCount != null) findCount.setText(count == 0 ? "0/0" : (active + 1) + "/" + count);
        });
        web.setDownloadListener((url, ua, cd, mime, len) -> {
            final String name = android.webkit.URLUtil.guessFileName(url, cd, mime);
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                web.evaluateJavascript(BrowserDownloads.blobJs(url, name), null);
                UiKit.makeToast(this, T.t("Saving…") + " " + name, Toast.LENGTH_SHORT).show();
                return;
            }
            if (isPlayable(url, mime)) {
                pendingDl = new String[]{ua, cd, mime};
                offer(url, name);
                return;
            }
            new UiDialog(this).setTitle(T.t("Download this file?"))
                    .setMessage(name + (len > 0 ? "\n" + TvInfo.gb(len) : "") + "\n\n" + T.t("Saved in Downloads/ZH Play"))
                    .setPositiveButton(T.t("Download"), (d, w) -> BrowserDownloads.start(this, url, ua, cd, mime, web.getUrl()))
                    .setNegativeButton(T.t("Cancel"), null).show();
        });
        web.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void save(final String dataUrl, final String name, final String mime) {
                new Thread(() -> {
                    String m;
                    try {
                        m = "✓ " + T.t("Saved in Downloads/ZH Play") + ": " + BrowserDownloads.saveData(BrowserActivity.this, dataUrl, name, mime);
                    } catch (Exception e) {
                        m = T.t("Can't download this") + ": " + e.getMessage();
                    }
                    final String m2 = m;
                    main.post(() -> UiKit.makeToast(BrowserActivity.this, m2, Toast.LENGTH_LONG).show());
                }).start();
            }

            @android.webkit.JavascriptInterface
            public void fail(final String why) {
                main.post(() -> UiKit.makeToast(BrowserActivity.this, T.t("Can't download this") + ": " + why, Toast.LENGTH_LONG).show());
            }
        }, "ZHDl");
        web.setOnLongClickListener(v -> {
            WebView.HitTestResult h = web.getHitTestResult();
            String u = h.getExtra();
            int t = h.getType();
            if (u != null && (t == WebView.HitTestResult.SRC_ANCHOR_TYPE || t == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE)) {
                pendingDl = null;
                offer(u, null);
                return true;
            }
            if (u != null && t == WebView.HitTestResult.IMAGE_TYPE) {
                final String img = u;
                new UiDialog(this).setTitle(T.t("Image"))
                        .setItems(new String[]{"⬇  " + T.t("Download image"), T.t("Open image in a new tab"), T.t("Copy image link")}, (d, w) -> {
                            if (w == 0) {
                                if (img.startsWith("data:") || img.startsWith("blob:")) web.evaluateJavascript(BrowserDownloads.blobJs(img, ""), null);
                                else BrowserDownloads.start(this, img, web.getSettings().getUserAgentString(), null, null, web.getUrl());
                            } else if (w == 1) newTab(img);
                            else copy(img);
                        }).show();
                return true;
            }
            return false;
        });
    }

    // ---------------------------------------------------------------- tabs
    private final List<WebView> tabs = new ArrayList<>();
    private Button tabsBtn;

    private void switchTo(WebView w) {
        if (w == null) return;
        if (w == web && w.getParent() != null) return;

        snap(web);

        if (web != null && web != w) {
            if (!pagePlaying) {
                try {
                    web.onPause();
                } catch (Throwable ignored) {
                }
            }
            webBox.removeView(web);
        }

        if (w.getParent() instanceof ViewGroup) {
            ((ViewGroup) w.getParent()).removeView(w);
        }

        web = w;
        try {
            web.onResume();
        } catch (Throwable ignored) {
        }

        webBox.addView(web, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        found.clear();
        updateVideos();
        tabsBtn.setText(String.valueOf(tabs.size()));
        updateOmni();
        updateShield();
        w.requestFocus();
    }

    private void newTab(String url) {
        WebView w = new BgWebView(this);
        configure(w);
        tabs.add(w);
        switchTo(w);
        if (url == null) home();
        else go(url);
        tabsBtn.setText(String.valueOf(tabs.size()));
    }

    private void closeTab(WebView w) {
        if (tabs.size() <= 1) {
            w.loadUrl("about:blank");
            home();
            return;
        }
        int i = tabs.indexOf(w);
        tabs.remove(w);

        Bitmap thumb = thumbs.remove(w);
        if (thumb != null && !thumb.isRecycled()) {
            thumb.recycle();
        }

        if (w == web) {
            switchTo(tabs.get(Math.max(0, i - 1)));
        } else {
            webBox.removeView(w);
        }

        try {
            w.stopLoading();
            w.loadUrl("about:blank");
            w.clearHistory();
            w.removeAllViews();
            w.destroy();
        } catch (Throwable ignored) {
        }

        tabsBtn.setText(String.valueOf(tabs.size()));
    }

    // ---------------------------------------------------------------- history
    private void addHistory(String title, String url) {
        try {
            org.json.JSONArray a = new org.json.JSONArray(Store.getStr("bhist", "[]"));
            org.json.JSONArray out = new org.json.JSONArray();
            out.put(new org.json.JSONObject().put("t", title == null ? url : title).put("u", url).put("w", System.currentTimeMillis()));
            for (int i = 0; i < a.length() && out.length() < 300; i++) {
                if (!a.getJSONObject(i).optString("u").equals(url)) out.put(a.getJSONObject(i));
            }
            Store.putStr("bhist", out.toString());
        } catch (Exception ignored) {
        }
    }

    private List<String[]> history(int max) {
        List<String[]> l = new ArrayList<>();
        try {
            org.json.JSONArray a = new org.json.JSONArray(Store.getStr("bhist", "[]"));
            for (int i = 0; i < a.length() && l.size() < max; i++) {
                l.add(new String[]{a.getJSONObject(i).optString("t"), a.getJSONObject(i).optString("u")});
            }
        } catch (Exception ignored) {
        }
        return l;
    }

    private void showHistory() {
        final List<String[]> h = history(300);
        if (h.isEmpty()) {
            UiKit.makeToast(this, T.t("No history yet"), Toast.LENGTH_SHORT).show();
            return;
        }
        String[] n = new String[h.size()];
        for (int i = 0; i < n.length; i++) n[i] = h.get(i)[0] + "\n" + h.get(i)[1];
        new UiDialog(this).setTitle("🕘 " + T.t("History"))
                .setItems(n, (d, w) -> web.loadUrl(h.get(w)[1]))
                .setNeutralButton("🗑 " + T.t("Clear history"), (d, w) -> Store.putStr("bhist", "[]"))
                .setPositiveButton(T.t("Close"), null).show();
    }

    private void clearData() {
        final String[] items = {T.t("History"), T.t("Cached pictures and files"), T.t("Cookies and site data (logs you out of sites)"),
                T.t("Close all tabs")};
        final boolean[] on = {true, true, false, false};
        new UiDialog(this).setTitle("🧹 " + T.t("Clear browsing data"))
                .setMultiChoiceItems(items, on, (d, w, c) -> on[w] = c)
                .setPositiveButton(T.t("Clear"), (d, w) -> {
                    if (on[0]) {
                        Store.putStr("bhist", "[]");
                        for (WebView t : tabs) t.clearHistory();
                    }
                    if (on[1]) {
                        web.clearCache(true);
                        android.webkit.WebStorage.getInstance().deleteAllData();
                    }
                    if (on[2]) {
                        CookieManager.getInstance().removeAllCookies(null);
                        CookieManager.getInstance().flush();
                        android.webkit.WebStorage.getInstance().deleteAllData();
                    }
                    if (on[3]) {
                        while (tabs.size() > 1) closeTab(tabs.get(tabs.size() - 1));
                        home();
                    }
                    UiKit.makeToast(this, "🧹 ✅", Toast.LENGTH_SHORT).show();
                }).setNegativeButton(T.t("Cancel"), null).show();
    }

    private void applyUa(WebView w) {
        WebSettings s = w.getSettings();
        String ua = WebSettings.getDefaultUserAgent(this).replace("; wv", "");
        if (Store.getBool("b_desktop", false)) ua = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36";
        s.setUserAgentString(ua);
    }

    // =====================================================================
    // modern browser chrome
    // =====================================================================
    private static final int SHIELD_ORANGE = 0xFFFB542B;
    private static final int VPN_GREEN = 0xFF10B981;

    private ImageView siteIcon, reloadBtn, shieldIcon, vpnIcon, videosIcon;
    private TextView shieldBadge, videosBadge, findCount;
    private volatile boolean pageLoading;
    private final java.util.Map<WebView, String> hosts = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<WebView, String>());
    private final java.util.Map<WebView, Integer> pageBlocked = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<WebView, Integer>());
    private final java.util.Map<WebView, Bitmap> thumbs = new java.util.WeakHashMap<>();
    private LinearLayout bottomBar, findBar;
    private EditText findIn;

    private ImageView navBtn(String icon, View.OnClickListener l) {
        ImageView b = UiKit.roundButton(this, icon, 40, false, l);
        b.setBackground(Ui.focusCard(this, 20));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40));
        lp.setMargins(Ui.dp(this, 2), 0, Ui.dp(this, 2), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private Button tabCounter() {
        Button b = new Button(this);
        b.setText("1");
        b.setTextSize(13);
        b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        b.setTextColor(Ui.TEXT);
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(0);
        b.setMinHeight(0);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(Ui.dp(this, 6));
        g.setStroke(Ui.dp(this, 2), Ui.TEXT);
        android.graphics.drawable.InsetDrawable in = new android.graphics.drawable.InsetDrawable(g, Ui.dp(this, 9));
        b.setBackground(in);
        b.setOnClickListener(v -> showTabs());
        return b;
    }

    private void updateVpnBar() {
        if (vpnIcon == null) return;
        boolean on = WgVpn.on();
        int tint = on ? VPN_GREEN : Ui.DIM;
        vpnIcon.setImageDrawable(new UiKit.Icon(this, "lock", 20, tint));
    }

    private LinearLayout buildBottomBar() {
        LinearLayout bb = new LinearLayout(this);
        bb.setGravity(Gravity.CENTER);
        bb.setBackgroundColor(Ui.BAR);
        bb.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 6));
        View[] items = {navBtn("arrow-left", v -> { if (web.canGoBack()) web.goBack(); }),
                navBtn("arrow-right", v -> { if (web.canGoForward()) web.goForward(); }),
                navBtn("house", v -> home()), tabsBtn, navBtn("ellipsis-vertical", v -> menu())};
        for (View v : items) {
            if (v.getParent() != null) ((ViewGroup) v.getParent()).removeView(v);
            bb.addView(v, new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1));
        }
        return bb;
    }

    private LinearLayout buildFindBar() {
        LinearLayout f = new LinearLayout(this);
        f.setGravity(Gravity.CENTER_VERTICAL);
        f.setBackgroundColor(Ui.BAR);
        f.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4));
        findIn = new EditText(this);
        findIn.setSingleLine(true);
        findIn.setHint(T.t("Find in page"));
        findIn.setTextColor(Ui.TEXT);
        findIn.setHintTextColor(Ui.DIM);
        findIn.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence c, int a, int b, int d) { }
            public void onTextChanged(CharSequence c, int a, int b, int d) { }
            public void afterTextChanged(android.text.Editable e) {
                web.findAllAsync(e.toString());
            }
        });
        f.addView(findIn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        findCount = Ui.text(this, 13, Ui.DIM, false);
        findCount.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
        f.addView(findCount);
        f.addView(navBtn("chevron-up", v -> web.findNext(false)));
        f.addView(navBtn("chevron-down", v -> web.findNext(true)));
        f.addView(navBtn("x", v -> {
            web.clearMatches();
            f.setVisibility(View.GONE);
            hideKeyboard();
        }));
        f.setVisibility(View.GONE);
        return f;
    }

    private void hideKeyboard() {
        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && getCurrentFocus() != null) imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
    }

    private static boolean isStartPage(String u) {
        return u == null || u.startsWith("data:") || u.startsWith("about:") || u.contains("tamilcastplay.local");
    }

    private void updateOmni() {
        if (bar == null || web == null) return;
        String u = web.getUrl();
        boolean start = isStartPage(u);
        String ic = start ? "search" : u.startsWith("https") ? "lock" : "globe";
        siteIcon.setImageDrawable(new UiKit.Icon(this, ic, 16, start ? Ui.DIM : u.startsWith("https") ? 0xFF22C55E : Ui.DIM));
        if (!bar.hasFocus()) {
            String h = start ? "" : android.net.Uri.parse(u).getHost();
            bar.setText(h == null ? u : h.replaceFirst("^www\\.", ""));
        }
        reloadBtn.setImageDrawable(new UiKit.Icon(this, pageLoading ? "x" : "refresh-cw", 18, Ui.DIM));
        reloadBtn.setVisibility(start ? View.GONE : View.VISIBLE);
    }

    private void updateShield() {
        if (shieldBadge == null) return;
        Integer n = pageBlocked.get(web);
        String h = hosts.get(web);
        boolean on = Shield.enabled() && !Shield.siteOff(h);
        shieldBadge.setText(n == null || n == 0 ? "" : n > 99 ? "99+" : String.valueOf(n));
        shieldBadge.setVisibility(n == null || n == 0 || !on ? View.GONE : View.VISIBLE);
        shieldIcon.setImageDrawable(new UiKit.Icon(this, on ? "shield-check" : "shield-off", 20, on ? SHIELD_ORANGE : Ui.DIM));
    }

    private void shieldPanel() {
        final String h = hosts.get(web) == null ? "" : hosts.get(web);
        Integer n = pageBlocked.get(web);
        long tot = Shield.total.get();
        boolean siteOn = !Shield.siteOff(h);
        String upd = Store.getLong("shield_updated", 0) == 0 ? T.t("built-in list") :
                new java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(new java.util.Date(Store.getLong("shield_updated", 0)));
        String msg = (h.isEmpty() ? "" : h + "\n\n") + T.t("Blocked on this page") + ": " + (n == null ? 0 : n)
                + "\n" + T.t("Blocked in total") + ": " + tot + "  (≈ " + Math.max(1, tot * 2 / 60) + " min " + T.t("saved") + ")"
                + "\n" + T.t("Filter list") + ": " + Shield.size() + " " + T.t("ad / tracker servers") + " · " + upd;
        String[] items = {(siteOn ? "🛡 " : "🔓 ") + T.t("Shield for this site") + ": " + T.t(siteOn ? "On" : "Off"),
                "▶ " + T.t("Skip YouTube ads") + ": " + T.t(Shield.ytSkip() ? "On" : "Off"),
                "🛡 " + T.t("Shield everywhere") + ": " + T.t(Shield.enabled() ? "On" : "Off"),
                "🔄 " + T.t("Update filter lists now")};
        new UiDialog(this).setTitle("🛡 " + T.t("Shield") + "\n" + msg).setItems(items, (d, w) -> {
            if (w == 0 && !h.isEmpty()) {
                Shield.setSite(h, Shield.siteOff(h));
                web.reload();
            } else if (w == 1) {
                Store.putBool("shield_yt", !Shield.ytSkip());
                web.reload();
            } else if (w == 2) {
                Store.putBool("shield_on", !Shield.enabled());
                web.reload();
            } else if (w == 3) {
                UiKit.makeToast(this, "…", Toast.LENGTH_SHORT).show();
                new Thread(() -> {
                    int c = Shield.update(this);
                    main.post(() -> UiKit.makeToast(this, c + " " + T.t("ad / tracker servers"), Toast.LENGTH_LONG).show());
                }).start();
            }
            updateShield();
        }).show();
    }

    private void menu() {
        final android.app.Dialog d = new android.app.Dialog(this);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 18));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Ui.SOLID);
        float rr = Ui.dp(this, 24);
        bg.setCornerRadii(new float[]{rr, rr, rr, rr, 0, 0, 0, 0});
        box.setBackground(bg);
        View handle = new View(this);
        handle.setBackground(Ui.card(this, Ui.STROKE, 0, 3));
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 5));
        hl.gravity = Gravity.CENTER_HORIZONTAL;
        hl.setMargins(0, 0, 0, Ui.dp(this, 12));
        box.addView(handle, hl);
        final boolean priv = Store.getBool("b_private", false), desk = Store.getBool("b_desktop", false);
        final String cur = web.getUrl();
        Object[][] it = {
                {"plus", T.t("New tab"), (Runnable) () -> newTab(null)},
                {"eye-off", T.t(priv ? "Private: On" : "Private tab"), (Runnable) () -> {
                    Store.putBool("b_private", !priv);
                    UiKit.makeToast(this, T.t("Private (no history)") + ": " + T.t(!priv ? "On" : "Off"), Toast.LENGTH_SHORT).show();
                }},
                {"download", T.t("Downloads"), (Runnable) () -> BrowserDownloads.show(this)},
                {"palette", T.t("Appearance"), (Runnable) this::appearance},
                {"lock", "VPN" + (WgVpn.on() ? " · " + T.t("On") : ""), (Runnable) () -> WgVpn.panel(this)},
                {"shield", T.t("Site settings"), (Runnable) () -> {
                    String h = hosts.get(web);
                    if (h != null && !h.isEmpty() && !h.equals("tamilcastplay.local")) SiteSettings.show(this, h, () -> web.reload());
                    else SiteSettings.showAll(this, null);
                }},
                {"bookmark", T.t("Bookmarks"), (Runnable) () -> library(1)},
                {"star", T.t(cur != null && BrowserData.isBookmarked(cur) ? "Remove bookmark" : "Add bookmark"), (Runnable) () -> {
                    if (cur == null || isStartPage(cur)) return;
                    boolean on = BrowserData.toggleBookmark(web.getTitle(), cur);
                    UiKit.makeToast(this, T.t(on ? "Added to bookmarks" : "Bookmark removed"), Toast.LENGTH_SHORT).show();
                }},
                {"clock", T.t("History"), (Runnable) () -> library(0)},
                {"audio-lines", T.t("Background play") + ": " + T.t(Store.getBool("b_bgplay", true) ? "On" : "Off"), (Runnable) () -> {
                    Store.putBool("b_bgplay", !Store.getBool("b_bgplay", true));
                    UiKit.makeToast(this, T.t("Background play") + ": " + T.t(Store.getBool("b_bgplay", true) ? "On" : "Off")
                            + " — " + T.t("reload the page"), Toast.LENGTH_LONG).show();
                    if (!Store.getBool("b_bgplay", true)) BrowserPlayService.stop(this);
                }},
                {"sliders-vertical", T.t("Page equalizer") + ": " + T.t(Store.getBool("b_eq", true) ? "On" : "Off"), (Runnable) () -> {
                    Store.putBool("b_eq", !Store.getBool("b_eq", true));
                    String js = BrowserMedia.eqCall();
                    for (WebView t : tabs) t.evaluateJavascript(js, null);
                    mediaState(pagePlaying, web.getTitle(), false, pageMedia);
                }},
                {"layers", T.t("Restore tabs") + ": " + T.t(Store.getBool("b_restore", true) ? "On" : "Off"), (Runnable) () ->
                        Store.putBool("b_restore", !Store.getBool("b_restore", true))},
                {"text-search", T.t("Find in page"), (Runnable) () -> {
                    findBar.setVisibility(View.VISIBLE);
                    findIn.requestFocus();
                }},
                {"monitor", T.t(desk ? "Mobile site" : "Desktop site"), (Runnable) () -> {
                    Store.putBool("b_desktop", !desk);
                    for (WebView t : tabs) applyUa(t);
                    web.reload();
                }},
                {"share-2", T.t("Share"), (Runnable) () -> {
                    if (cur == null || isStartPage(cur)) return;
                    startActivity(android.content.Intent.createChooser(new android.content.Intent(android.content.Intent.ACTION_SEND)
                            .setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, cur), T.t("Share")));
                }},
                {"cast", T.t("Open on TV"), (Runnable) () -> {
                    if (cur == null || isStartPage(cur)) return;
                    final String tvIp = Store.getStr("last_tv", "");
                    if (tvIp.isEmpty() || tv) return;
                    new Thread(() -> {
                        try {
                            HashMap<String, String> pm = new HashMap<>();
                            pm.put("url", cur);
                            TvClient.post(tvIp, "/browse", pm);
                        } catch (Exception ignored) {
                        }
                    }).start();
                    UiKit.makeToast(this, T.t("Opening on the TV…"), Toast.LENGTH_SHORT).show();
                }},
                {"film", T.t("Videos on page"), (Runnable) this::showVideos},
                {"shield-check", T.t("Shield"), (Runnable) this::shieldPanel},
                {"trash", T.t("Clear data"), (Runnable) this::clearData},
                {"refresh-cw", T.t("Reload"), (Runnable) () -> web.reload()},
                {"layers", T.t("Tabs"), (Runnable) this::showTabs},
                {"copy", T.t("Copy link"), (Runnable) () -> {
                    if (cur == null) return;
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("url", cur));
                    UiKit.makeToast(this, T.t("Copied"), Toast.LENGTH_SHORT).show();
                }},
                {"house", T.t("Exit browser"), (Runnable) () -> Ui.goHome(this, tv)},
                {"maximize", T.t("Picture in Picture"), (Runnable) () -> {
                    if (!enterPipMode()) {
                        UiKit.makeToast(this, T.t("PiP is not supported or no video is playing"), Toast.LENGTH_SHORT).show();
                    }
                }}
        };

        LinearLayout quick = new LinearLayout(this);
        for (int i : new int[]{0, 1, 20, 6, 8}) {
            final Runnable run = (Runnable) it[i][2];
            LinearLayout c = new LinearLayout(this);
            c.setOrientation(LinearLayout.VERTICAL);
            c.setGravity(Gravity.CENTER_HORIZONTAL);
            c.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 8));
            c.setBackground(Ui.focusCard(this, 16));
            c.setFocusable(true);
            c.setClickable(true);
            int ic = (i == 1 && priv) ? SHIELD_ORANGE : Ui.ACCENT;
            c.addView(UiKit.icon(this, (String) it[i][0], 22, ic), new LinearLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28)));
            TextView t = Ui.text(this, 11, Ui.TEXT, false);
            String label = (String) it[i][1];
            if (i == 20) label = T.t("Tabs") + " (" + tabs.size() + ")";
            else if (i == 1) label = T.t(priv ? "Private: On" : "Private tab");
            t.setText(label);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            t.setPadding(Ui.dp(this, 2), Ui.dp(this, 4), Ui.dp(this, 2), 0);
            c.addView(t);
            c.setOnClickListener(v -> {
                d.dismiss();
                run.run();
                if (i == 1) main.postDelayed(this::menu, 120);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            lp.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
            quick.addView(c, lp);
        }
        box.addView(quick);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        String host = hosts.get(web);
        boolean site = host != null && !host.isEmpty() && !host.equals("tamilcastplay.local");
        boolean bm = cur != null && BrowserData.isBookmarked(cur);
        Object[][] sections = {
                {"§", T.t("Listening & Viewing")},
                {23, T.t("Picture in Picture"), T.t("Floating video window"), -1, false},
                {-2, T.t("Audio only"), T.t(audioMode() ? "On — video hidden, sound playing" : "Off"), audioMode() ? 1 : 0, false},
                {-3, T.t("Sleep timer"), sleepLabel(), -1, false},
                {"§", T.t("This page")},
                {19, T.t("Reload"), null, -1, false},
                {12, T.t("Find in page"), null, -1, false},
                {16, T.t("Videos on page"), found.isEmpty() ? null : found.size() + " " + T.t("found"), -1, false},
                {7, T.t(bm ? "Remove bookmark" : "Add bookmark"), null, -1, false},
                {14, T.t("Share"), null, -1, false},
                {21, T.t("Copy link"), null, -1, false},
                {15, T.t("Open on TV"), tv ? null : Store.getStr("last_tv", ""), -1, false},
                {13, T.t("Desktop site"), T.t(desk ? "On" : "Off"), desk ? 1 : 0, false},
                {"§", T.t("Privacy & security")},
                {1, T.t("Private tab"), T.t("No history is saved"), priv ? 1 : 0, false},
                {17, T.t("Shield"), T.t(Shield.enabled() ? "Ads & trackers blocked" : "Off"), -1, false},
                {5, T.t("Site settings"), site ? host : T.t("All sites"), -1, false},
                {4, "VPN", WgVpn.status(this), -1, false},
                {18, T.t("Clear data"), T.t("History, cookies, cache"), -1, true},
                {"§", T.t("Customize")},
                {-1, T.t("Dark mode for websites"), T.t(new String[]{"Follow the phone", "On", "Off"}[darkMode()]), -1, false},
                {3, T.t("Appearance"), T.t(BrowserLook.BG_NAMES[java.util.Arrays.asList(BrowserLook.BG_IDS).indexOf(BrowserLook.bg()) < 0 ? 0
                        : java.util.Arrays.asList(BrowserLook.BG_IDS).indexOf(BrowserLook.bg())]), -1, false},
                {9, T.t("Background play"), T.t("Keep playing with the screen off"), Store.getBool("b_bgplay", true) ? 1 : 0, false},
                {10, T.t("Page equalizer"), T.t("EQ button on pages with sound"), Store.getBool("b_eq", true) ? 1 : 0, false},
                {11, T.t("Restore tabs"), T.t("Open last tabs next time"), Store.getBool("b_restore", true) ? 1 : 0, false},
                {"§", ""},
                {22, T.t("Exit browser"), null, -1, false}};
        for (Object[] sct : sections) {
            if ("§".equals(sct[0])) {
                if (!((String) sct[1]).isEmpty()) list.addView(UiRows.header(this, (String) sct[1]));
                continue;
            }
            int i = (Integer) sct[0];
            if (i == 23 && tv) continue;
            if (i == -2) {
                list.addView(UiRows.row(this, "headphones", (String) sct[1], (String) sct[2], (Integer) sct[3], false, v -> {
                    d.dismiss();
                    setAudioMode(!audioMode());
                    main.postDelayed(this::menu, 120);
                }));
                continue;
            }
            if (i == -3) {
                list.addView(UiRows.row(this, "timer", (String) sct[1], (String) sct[2], -1, false, v -> {
                    d.dismiss();
                    sleepTimer();
                }));
                continue;
            }
            if (i == -1) {
                list.addView(UiRows.row(this, "moon", (String) sct[1], (String) sct[2], -1, false, v -> {
                    d.dismiss();
                    new UiDialog(this).setTitle(T.t("Dark mode for websites")).setSingleChoiceItems(
                            new String[]{T.t("Follow the phone"), T.t("On"), T.t("Off")}, darkMode(), (d2, k) -> {
                                Store.putInt("b_dark", k);
                                d2.dismiss();
                                for (WebView t : tabs) {
                                    applyDark(t);
                                    t.evaluateJavascript(cssDark() ? DARK_JS : LIGHT_JS, null);
                                }
                                if (darkChrome() != Ui.dark()) redrawKeepingTabs();
                                UiKit.makeToast(this, T.t("Dark mode for websites") + ": " + T.t(new String[]{"Follow the phone", "On", "Off"}[k]),
                                        Toast.LENGTH_SHORT).show();
                            }).show();
                }));
                continue;
            }
            if (i == 15 && tv) continue;
            final Runnable run = (Runnable) it[i][2];
            final boolean isToggle = (Integer) sct[3] >= 0;
            list.addView(UiRows.row(this, (String) it[i][0], (String) sct[1], (String) sct[2], (Integer) sct[3], (Boolean) sct[4], v -> {
                d.dismiss();
                run.run();
                if (isToggle) main.postDelayed(this::menu, 120);
            }));
        }
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(list);
        sv.setVerticalScrollBarEnabled(false);
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.62f);
        list.measure(View.MeasureSpec.makeMeasureSpec(getResources().getDisplayMetrics().widthPixels, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        box.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.min(maxH, list.getMeasuredHeight())));
        d.setContentView(box);
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
        }
        d.show();
    }

    private void snap(WebView w) {
        try {
            if (w == null || w.getWidth() <= 0 || w.getHeight() <= 0) return;
            Bitmap old = thumbs.get(w);
            if (old != null && !old.isRecycled()) {
                old.recycle();
            }
            Bitmap b = Bitmap.createBitmap(w.getWidth() / 3, w.getHeight() / 3, Bitmap.Config.RGB_565);
            android.graphics.Canvas c = new android.graphics.Canvas(b);
            c.scale(1 / 3f, 1 / 3f);
            c.translate(-w.getScrollX(), -w.getScrollY());
            w.draw(c);
            thumbs.put(w, b);
        } catch (Throwable ignored) {
        }
    }

    private FrameLayout tabsView;

    private void showTabs() {
        snap(web);
        if (tabsView != null) root.removeView(tabsView);
        tabsView = new FrameLayout(this);
        tabsView.setBackgroundColor(Ui.BG);
        tabsView.setClickable(true);
        tabsView.setFocusable(true);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(this, 12), Ui.dp(this, tv ? 16 : 22), Ui.dp(this, 12), Ui.dp(this, 8));
        tabsView.addView(col);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(navBtn("arrow-left", v -> {
            root.removeView(tabsView);
            tabsView = null;
        }));
        TextView h = Ui.text(this, 19, Ui.TEXT, true);
        h.setText("  " + T.t("Tabs") + " · " + tabs.size());
        top.addView(h, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Button closeAll = Ui.button(this, T.t("Close all"), false);
        closeAll.setOnClickListener(v -> {
            while (tabs.size() > 1) closeTab(tabs.get(tabs.size() - 1));
            home();
            root.removeView(tabsView);
            tabsView = null;
        });
        top.addView(closeAll);

        Button add = Ui.button(this, "＋ " + T.t("New tab"), true);
        add.setOnClickListener(v -> {
            root.removeView(tabsView);
            tabsView = null;
            newTab(null);
        });
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 40));
        al.setMargins(Ui.dp(this, 8), 0, 0, 0);
        top.addView(add, al);
        col.addView(top);

        android.widget.GridView g = new android.widget.GridView(this);
        g.setNumColumns(tv ? 4 : 2);
        g.setHorizontalSpacing(Ui.dp(this, 10));
        g.setVerticalSpacing(Ui.dp(this, 12));
        g.setPadding(0, Ui.dp(this, 12), 0, 0);
        g.setSelector(Ui.listSelector(this));
        g.setDrawSelectorOnTop(true);
        g.setFocusable(true);
        g.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);

        g.setAdapter(new android.widget.BaseAdapter() {
            public int getCount() { return tabs.size(); }
            public Object getItem(int p) { return tabs.get(p); }
            public long getItemId(int p) { return p; }
            public View getView(int p, View cv, ViewGroup parent) {
                final int pos = p;
                final WebView w = tabs.get(pos);

                LinearLayout card = new LinearLayout(BrowserActivity.this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setBackground(Ui.card(BrowserActivity.this, Ui.SOLID, w == web ? Ui.ACCENT : Ui.STROKE, 16));
                card.setPadding(Ui.dp(BrowserActivity.this, 6), Ui.dp(BrowserActivity.this, 6), Ui.dp(BrowserActivity.this, 6), Ui.dp(BrowserActivity.this, 6));
                card.setFocusable(false);

                card.setOnClickListener(v -> {
                    root.removeView(tabsView);
                    tabsView = null;
                    switchTo(w);
                });

                LinearLayout head = new LinearLayout(BrowserActivity.this);
                head.setGravity(Gravity.CENTER_VERTICAL);
                TextView t = Ui.text(BrowserActivity.this, 12, Ui.TEXT, true);
                String title = w.getTitle();
                t.setText(title == null || title.isEmpty() || isStartPage(w.getUrl()) ? T.t("New tab") : title);
                t.setSingleLine(true);
                t.setEllipsize(android.text.TextUtils.TruncateAt.END);
                t.setPadding(Ui.dp(BrowserActivity.this, 4), 0, 0, 0);
                head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

                ImageView x = UiKit.closeButton(BrowserActivity.this, v -> {
                    closeTab(w);
                    showTabs();
                });
                head.addView(x, new LinearLayout.LayoutParams(Ui.dp(BrowserActivity.this, 28), Ui.dp(BrowserActivity.this, 28)));
                card.addView(head);

                ImageView pic = new ImageView(BrowserActivity.this);
                pic.setScaleType(ImageView.ScaleType.CENTER_CROP);
                pic.setBackground(Ui.card(BrowserActivity.this, Ui.FIELD, 0, 10));
                pic.setClipToOutline(true);
                pic.setFocusable(false);
                pic.setClickable(false);

                Bitmap b = thumbs.get(w);
                if (b != null && !b.isRecycled()) pic.setImageBitmap(b);
                else pic.setImageDrawable(new UiKit.Icon(BrowserActivity.this, "globe", 32, Ui.DIM));

                LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(BrowserActivity.this, tv ? 120 : 170));
                pl.setMargins(0, Ui.dp(BrowserActivity.this, 6), 0, 0);
                card.addView(pic, pl);

                return card;
            }
        });

        g.setOnItemClickListener((pa, v, pos, id) -> {
            root.removeView(tabsView);
            tabsView = null;
            switchTo(tabs.get(pos));
        });

        col.addView(g, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(tabsView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        g.requestFocus();
    }

    private static String esc(String s) {
        return android.text.Html.escapeHtml(s == null ? "" : s);
    }

    private void home() {
        boolean dk = Ui.dark();
        String bg = dk ? "#0b0913" : "#f6f5fb", fg = dk ? "#f3f0ff" : "#1b1530", dim = dk ? "#a59fbd" : "#6b6482",
                card = dk ? "rgba(255,255,255,.06)" : "#ffffff", line = dk ? "rgba(255,255,255,.10)" : "rgba(27,21,48,.10)";
        String acc = BrowserLook.accent(), acc2 = String.format("#%06X", Ui.ACCENT2 & 0xFFFFFF);
        boolean pic = BrowserLook.hasBackground();
        if (pic) {
            fg = "#ffffff";
            dim = "rgba(255,255,255,.75)";
            card = BrowserLook.glass() ? "rgba(20,16,32,.38)" : "rgba(20,16,32,.82)";
            line = "rgba(255,255,255,.16)";
        } else if (!BrowserLook.glass()) card = dk ? "#17141f" : "#ffffff";
        long tot = Shield.total.get();
        StringBuilder h = new StringBuilder();
        h.append("<html><head><meta name=viewport content='width=device-width,initial-scale=1'><style>")
                .append("*{box-sizing:border-box}body{margin:0;font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;color:").append(fg)
                .append(";background:").append(bg).append(";padding:28px 18px 40px}")
                .append(".wrap{max-width:760px;margin:0 auto}")
                .append(".logo{display:flex;align-items:center;justify-content:center;gap:12px;margin:26px 0 22px}")
                .append(".logo i{width:46px;height:46px;border-radius:15px;background:linear-gradient(135deg,").append(acc).append(",").append(acc2)
                .append(");display:grid;place-items:center;box-shadow:0 10px 28px rgba(0,0,0,.25)}")
                .append(".logo i:after{content:'';border-left:15px solid #fff;border-top:9px solid transparent;border-bottom:9px solid transparent;margin-left:4px}")
                .append(".logo b{font-size:24px;letter-spacing:.3px}")
                .append("form{display:flex;align-items:center;background:").append(card).append(";border:1px solid ").append(line)
                .append(";border-radius:28px;padding:4px 6px 4px 18px;box-shadow:0 6px 22px rgba(0,0,0,.12)}")
                .append("input{flex:1;border:0;outline:0;background:transparent;color:").append(fg).append(";font-size:17px;padding:12px 4px}")
                .append("button{border:0;border-radius:22px;padding:11px 18px;font-size:15px;font-weight:600;color:#fff;background:linear-gradient(135deg,")
                .append(acc).append(",").append(acc2).append(")}")
                .append(".stats{display:flex;justify-content:space-around;gap:10px;margin:18px 0 6px;padding:14px;border-radius:20px;background:").append(card)
                .append(";border:1px solid ").append(line).append("}.stats div{text-align:center}.stats b{display:block;font-size:22px;color:#FB542B}")
                .append(".stats span{font-size:12px;color:").append(dim).append("}")
                .append("h2{font-size:13px;letter-spacing:.12em;text-transform:uppercase;color:").append(dim).append(";margin:26px 4px 12px}")
                .append(".g{display:grid;grid-template-columns:repeat(4,1fr);gap:12px}@media(min-width:600px){.g{grid-template-columns:repeat(6,1fr)}}")
                .append(".s{display:flex;flex-direction:column;align-items:center;gap:8px;text-decoration:none;color:").append(fg)
                .append(";font-size:12px;padding:10px 4px;border-radius:16px}.s:focus{outline:3px solid ").append(acc).append("}")
                .append(".s em{width:52px;height:52px;border-radius:16px;display:grid;place-items:center;font-style:normal;font-weight:700;font-size:20px;color:#fff}")
                .append(".s small{max-width:100%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}")
                .append(".r{display:flex;flex-direction:column;gap:2px}.r a{display:block;padding:12px 14px;border-radius:14px;text-decoration:none;color:").append(fg)
                .append(";background:").append(card).append(";border:1px solid ").append(line).append(";margin-bottom:8px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}")
                .append(".r a small{display:block;color:").append(dim).append(";font-size:12px}p{color:").append(dim).append(";font-size:13px;text-align:center;margin-top:26px}")
                .append(BrowserLook.glass() ? "form,.stats,.r a,.clock{backdrop-filter:blur(14px);-webkit-backdrop-filter:blur(14px)}" : "")
                .append(".clock{text-align:center;margin:8px 0 4px;padding:6px}.clock b{display:block;font-size:52px;font-weight:300;letter-spacing:1px}")
                .append(".clock span{color:").append(dim).append(";font-size:15px}")
                .append(".cz{position:fixed;right:14px;bottom:14px;padding:9px 14px;border-radius:20px;font-size:13px;text-decoration:none;color:#fff;")
                .append("background:rgba(0,0,0,.45);border:1px solid rgba(255,255,255,.2);backdrop-filter:blur(8px)}")
                .append(BrowserLook.backgroundCss(this, dk)).append(BrowserLook.animCss())
                .append("</style></head><body><div class=wrap>")
                .append("<div class=logo><i></i><b>ZH Play</b></div>");
        if (BrowserLook.show("clock")) {
            h.append("<div class=clock><b id=zt></b><span id=zg></span></div><script>(function(){function t(){var d=new Date();")
                    .append("document.getElementById('zt').textContent=d.toLocaleTimeString([], {hour:'2-digit',minute:'2-digit'});var hr=d.getHours();")
                    .append("document.getElementById('zg').textContent=hr<12?'").append(esc(T.t("Good morning"))).append("':hr<17?'")
                    .append(esc(T.t("Good afternoon"))).append("':'").append(esc(T.t("Good evening"))).append("';}t();setInterval(t,10000);})();</script>");
        }
        h.append("<form onsubmit=\"location.href='").append(BrowserLook.searchUrl()).append("'+encodeURIComponent(q.value);return false\">")
                .append("<input id=q placeholder='").append(esc(T.t("Search Google or type a web address"))).append("'><button>")
                .append(esc(T.t("Search"))).append("</button></form>");
        if (Shield.enabled() && BrowserLook.show("stats")) {
            h.append("<div class=stats><div><b>").append(tot).append("</b><span>").append(esc(T.t("Ads & trackers blocked"))).append("</span></div>")
                    .append("<div><b>").append(Math.max(0, tot * 2 / 60)).append(" min</b><span>").append(esc(T.t("Time saved"))).append("</span></div>")
                    .append("<div><b>").append(String.format(java.util.Locale.US, "%.1f MB", tot * 0.05)).append("</b><span>").append(esc(T.t("Data saved")))
                    .append("</span></div></div>");
        }
        String[] colors = {"#ef4444", "#f97316", "#eab308", "#22c55e", "#14b8a6", "#3b82f6", "#8b5cf6", "#ec4899"};
        List<String[]> bm = bookmarkList();
        if (!bm.isEmpty() && BrowserLook.show("shortcuts")) {
            h.append("<h2>").append(esc(T.t("Shortcuts"))).append("</h2><div class=g>");
            int i = 0;
            for (String[] b : bm) {
                if (i >= 12) break;
                String host = android.net.Uri.parse(b[1]).getHost();
                String letter = b[0].isEmpty() ? "?" : b[0].substring(0, 1).toUpperCase(java.util.Locale.ROOT);
                h.append("<a class=s href='").append(b[1].replace("'", "%27")).append("'><em style='background:").append(colors[i % colors.length])
                        .append("'>").append(esc(letter)).append("</em><small>").append(esc(b[0])).append("</small></a>");
                i++;
            }
            h.append("</div>");
        }
        List<String[]> rec = Store.getBool("b_private", false) ? new ArrayList<String[]>() : history(6);
        if (!rec.isEmpty() && BrowserLook.show("recent")) {
            h.append("<h2>").append(esc(T.t("Recent"))).append("</h2><div class=r>");
            for (String[] b : rec) {
                h.append("<a href='").append(b[1].replace("'", "%27")).append("'>").append(esc(b[0])).append("<small>")
                        .append(esc(android.net.Uri.parse(b[1]).getHost())).append("</small></a>");
            }
            h.append("</div>");
        }
        h.append("<p>").append(esc(T.t("Videos found on a page appear under the film button. Long-press any link to play it."))).append("</p></div><a class=cz href='https://tamilcastplay.local/appearance'>✎ ").append(esc(T.t("Customize")))
                .append("</a></body></html>");
        web.loadDataWithBaseURL("https://tamilcastplay.local/", h.toString(), "text/html", "utf-8", null);
        hosts.put(web, "");
        bar.setText("");
        updateOmni();
        updateShield();
    }

    private Button small(String label, View.OnClickListener l) {
        Button b = Ui.button(this, label, false);
        b.setOnClickListener(l);
        b.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        b.setMinWidth(Ui.dp(this, 44));
        b.setMinimumWidth(Ui.dp(this, 44));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 44));
        lp.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void go(String text) {
        text = text.trim();
        if (text.isEmpty()) return;
        if (text.startsWith("magnet:") || Formats.isTorrent(text)) {
            offer(text, null);
            return;
        }
        String url;
        if (text.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) url = text;
        else if (!text.contains(" ") && text.contains(".")) url = "https://" + text;
        else url = "https://www.google.com/search?q=" + Uri.encode(text);
        web.loadUrl(url);
        web.requestFocus();
    }

    private boolean handleNav(String u) {
        if (u.startsWith("https://tamilcastplay.local/appearance")) {
            main.post(this::appearance);
            return true;
        }
        String l = u.toLowerCase(Locale.US);
        if (l.startsWith("magnet:") || Formats.isTorrent(u) || isPlayable(u, null)) {
            offer(u, null);
            return true;
        }
        if (l.startsWith("http") || l.startsWith("data:") || l.startsWith("about:")) return false;
        try {
            startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(u)));
        } catch (Exception ignored) {
        }
        return true;
    }

    private static boolean isPlayable(String url, String mime) {
        if (mime != null && (mime.startsWith("video/") || mime.startsWith("audio/") || mime.contains("bittorrent")
                || mime.contains("mpegurl") || mime.contains("dash+xml"))) return true;
        String e = Formats.ext(url);
        if (e.equals("ts") || e.equals("m4s") || e.equals("aac") && url.contains("seg")) return false;
        return Formats.isVideo(url) || Formats.isAudio(url) || e.equals("m3u8") || e.equals("mpd") || e.equals("torrent");
    }

    private void sniff(String url, String mime) {
        if (!url.startsWith("http")) return;
        String l = url.toLowerCase(Locale.US);
        boolean hit = isPlayable(url, mime) || l.contains(".m3u8") || l.contains(".mpd") || l.contains("mime=video");
        if (!hit) return;
        final String clean = url;
        main.post(() -> {
            if (found.containsKey(clean) || found.size() > 60) return;
            String name = Formats.titleFromUrl(clean);
            if (clean.toLowerCase(Locale.US).contains(".m3u8")) name = "📡 " + T.t("Stream") + " · " + name;
            found.put(clean, name);
            updateVideos();
        });
    }

    private void updateVideos() {
        if (videosBadge == null) return;
        int count = found.size();
        if (count > 0) {
            videosBadge.setText(count > 99 ? "99+" : String.valueOf(count));
            videosBadge.setVisibility(View.VISIBLE);
        } else {
            videosBadge.setVisibility(View.GONE);
        }
    }

    private void showVideos() {
        if (found.isEmpty()) {
            UiKit.makeToast(this, T.t("No videos found on this page yet. Start the video on the page first."), Toast.LENGTH_LONG).show();
            return;
        }
        final List<String> us = new ArrayList<>(found.keySet());
        String[] names = new String[us.size()];
        for (int i = 0; i < names.length; i++) names[i] = found.get(us.get(i));
        new UiDialog(this).setTitle(T.t("Videos on this page"))
                .setItems(names, (d, w) -> offer(us.get(w), found.get(us.get(us.indexOf(names[w])) != null ? us.get(w) : us.get(0)))).show();
    }

    private String withHeaders(String u) {
        if (!u.startsWith("http")) return u;
        String page = web.getUrl();
        String ua = web.getSettings().getUserAgentString();
        return u + "|Referer=" + Uri.encode(page == null ? "" : page) + "&User-Agent=" + Uri.encode(ua);
    }

    private void offer(final String url, String title) {
        final String t = title != null ? title : (web.getTitle() != null && !Formats.isTorrent(url) ? web.getTitle() : Formats.titleFromUrl(url));
        final List<String> labels = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();
        if (Formats.isTorrent(url)) {
            labels.add("⬇ " + T.t("Download for later") + (tv ? "" : " (" + T.t("on the TV") + ")"));
            acts.add(() -> {
                if (tv) UiKit.makeToast(this, TorrentDownloads.add(this, url), Toast.LENGTH_LONG).show();
                else {
                    final String tvIp = Store.getStr("last_tv", "");
                    if (tvIp.isEmpty()) {
                        UiKit.makeToast(this, TorrentDownloads.add(this, url), Toast.LENGTH_LONG).show();
                        return;
                    }
                    new Thread(() -> {
                        String m;
                        try {
                            HashMap<String, String> p = new HashMap<>();
                            p.put("url", url);
                            m = TvClient.post(tvIp, "/dl", p).optString("msg", "Sent");
                        } catch (Exception e) {
                            m = T.t("Cannot reach the TV");
                        }
                        final String m2 = m;
                        main.post(() -> UiKit.makeToast(this, m2, Toast.LENGTH_LONG).show());
                    }).start();
                }
            });
        }
        labels.add(T.t(tv ? "▶ Play on this TV" : "▶ Play on this phone"));
        acts.add(() -> {
            List<Store.Item> one = new ArrayList<>();
            one.add(new Store.Item(t, withHeaders(url), ""));
            Store.addRecent(one.get(0));
            Remote.play(this, one, 0);
        });
        final String tvIp = Store.getStr("last_tv", "");
        if (!tv && !tvIp.isEmpty()) {
            labels.add(T.t("📺 Send to TV"));
            acts.add(() -> sendToTv(tvIp, withHeaders(url), t, "now"));
            labels.add(T.t("+ Add to TV queue"));
            acts.add(() -> sendToTv(tvIp, withHeaders(url), t, "queue"));
        }
        if (Remote.player != null) {
            labels.add(T.t("+ Add to queue"));
            acts.add(() -> {
                Remote.player.enqueue(new Store.Item(t, withHeaders(url), ""));
            });
        }
        if (Formats.isTorrent(url)) {
            labels.add("📂 " + T.t("Choose files (torrents with several videos)"));
            acts.add(() -> torrentFiles(url, t));
        }
        if (!tv && !tvIp.isEmpty()) {
            labels.add("💾 " + T.t("Add to TV playlist “From browser”"));
            acts.add(() -> addToTvPlaylist(tvIp, java.util.Collections.singletonList(new Store.Item(t, Formats.isTorrent(url) ? url : withHeaders(url), ""))));
        }
        labels.add("💾 " + T.t(tv ? "Save to playlist “From browser”" : "Save to this phone's playlist “From browser”"));
        acts.add(() -> {
            Store.add(T.t("From browser"), new Store.Item(t, Formats.isTorrent(url) ? url : withHeaders(url), ""));
            UiKit.makeToast(this, T.t("Saved"), Toast.LENGTH_SHORT).show();
        });
        if (url.startsWith("http")) {
            final String[] dl = pendingDl;
            labels.add("⬇ " + T.t(tv ? "Download to this TV" : "Download to this phone"));
            acts.add(() -> BrowserDownloads.start(this, url, dl != null ? dl[0] : web.getSettings().getUserAgentString(),
                    dl != null ? dl[1] : null, dl != null ? dl[2] : null, web.getUrl()));
        }
        labels.add(T.t("Open link here"));
        acts.add(() -> web.loadUrl(url));
        labels.add(T.t("Open in a new tab"));
        acts.add(() -> newTab(url));
        labels.add(T.t("Copy link"));
        acts.add(() -> copy(url));
        new UiDialog(this).setTitle((Formats.isTorrent(url) ? "🧲 " + T.t("Torrent found — play or download?") + "\n" : "") + t)
                .setItems(labels.toArray(new String[0]), (d, w) -> acts.get(w).run()).show();
    }

    private android.webkit.ValueCallback<Uri[]> fileCb;
    private String[] pendingDl;

    private void copy(String text) {
        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("link", text));
        UiKit.makeToast(this, T.t("Copied"), Toast.LENGTH_SHORT).show();
    }

    private void addToTvPlaylist(final String ip, final List<Store.Item> items) {
        new Thread(() -> {
            String msg;
            try {
                int n = 0;
                for (Store.Item it : items) {
                    HashMap<String, String> p = new HashMap<>();
                    p.put("url", it.u);
                    p.put("title", it.t);
                    p.put("playlist", T.t("From browser"));
                    if (TvClient.post(ip, "/add", p).optBoolean("ok", true)) n++;
                }
                msg = n + " " + T.t("added to the TV playlist “From browser”");
            } catch (TvClient.AuthNeeded e) {
                msg = T.t("Open the remote and enter the TV PIN first");
            } catch (Exception e) {
                msg = T.t("Cannot reach the TV");
            }
            final String m = msg;
            main.post(() -> UiKit.makeToast(this, m, Toast.LENGTH_LONG).show());
        }).start();
    }

    private void torrentFiles(final String url, final String title) {
        final AlertDialog wait = new UiDialog(this).setTitle(T.t("Getting the file list…"))
                .setMessage(T.t("This can take up to a minute (the torrent's info comes from other people).")).setNegativeButton(T.t("Cancel"), null).show();
        new Thread(() -> {
            try {
                final List<Object[]> files = TorrentEngine.get(this).listFiles(url);
                main.post(() -> {
                    if (!wait.isShowing()) return;
                    wait.dismiss();
                    pickTorrentFiles(url, title, files);
                });
            } catch (final Exception e) {
                main.post(() -> {
                    wait.dismiss();
                    UiKit.makeToast(this, e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void pickTorrentFiles(final String url, final String title, List<Object[]> files) {
        final List<Object[]> media = new ArrayList<>();
        for (Object[] f : files) if ((Boolean) f[3]) media.add(f);
        if (media.isEmpty()) {
            UiKit.makeToast(this, T.t("This torrent has no videos or songs"), Toast.LENGTH_LONG).show();
            return;
        }
        final String[] names = new String[media.size()];
        final boolean[] on = new boolean[media.size()];
        for (int i = 0; i < names.length; i++) {
            String path = (String) media.get(i)[1];
            names[i] = path.substring(path.lastIndexOf('/') + 1) + "  ·  " + TvInfo.gb((Long) media.get(i)[2]);
            on[i] = true;
        }
        new UiDialog(this).setTitle(media.size() + " " + T.t("files in this torrent"))
                .setMultiChoiceItems(names, on, (d, w, c) -> on[w] = c)
                .setPositiveButton(T.t("Next"), (d, w) -> {
                    final List<Store.Item> chosen = new ArrayList<>();
                    for (int i = 0; i < on.length; i++) {
                        if (!on[i]) continue;
                        String path = (String) media.get(i)[1];
                        String nm = path.substring(path.lastIndexOf('/') + 1).replaceAll("\\.[A-Za-z0-9]{2,4}$", "");
                        chosen.add(new Store.Item(nm, TorrentEngine.withFile(url, (Integer) media.get(i)[0]), ""));
                    }
                    if (chosen.isEmpty()) return;
                    torrentActions(chosen);
                })
                .setNegativeButton(T.t("Cancel"), null).show();
    }

    private void torrentActions(final List<Store.Item> chosen) {
        final String tvIp = Store.getStr("last_tv", "");
        final List<String> labels = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();
        labels.add("▶ " + T.t(tv ? "Play on this TV" : "Play on this phone") + " (" + chosen.size() + ")");
        acts.add(() -> Remote.play(this, chosen, 0));
        if (!tv && !tvIp.isEmpty()) {
            labels.add("📺 " + T.t("Send to TV") + " (" + chosen.size() + ")");
            acts.add(() -> sendManyToTv(tvIp, chosen, "now"));
            labels.add("+ " + T.t("Add to TV queue"));
            acts.add(() -> sendManyToTv(tvIp, chosen, "queue"));
            labels.add("💾 " + T.t("Add to TV playlist “From browser”"));
            acts.add(() -> addToTvPlaylist(tvIp, chosen));
        }
        labels.add("💾 " + T.t(tv ? "Save to playlist “From browser”" : "Save to this phone's playlist “From browser”"));
        acts.add(() -> {
            for (Store.Item it : chosen) Store.add(T.t("From browser"), it);
            UiKit.makeToast(this, chosen.size() + " " + T.t("saved"), Toast.LENGTH_SHORT).show();
        });
        new UiDialog(this).setTitle(chosen.size() + " " + T.t("files"))
                .setItems(labels.toArray(new String[0]), (d, w) -> acts.get(w).run()).show();
    }

    private void sendManyToTv(final String ip, final List<Store.Item> items, final String mode) {
        new Thread(() -> {
            String msg;
            try {
                StringBuilder us = new StringBuilder(), ts = new StringBuilder();
                for (Store.Item it : items) {
                    us.append(it.u).append('\n');
                    ts.append(it.t).append('\n');
                }
                HashMap<String, String> p = new HashMap<>();
                p.put("urls", us.toString());
                p.put("titles", ts.toString());
                p.put("mode", mode);
                msg = TvClient.post(ip, "/playmany", p).optString("msg", T.t("Sent"));
            } catch (TvClient.AuthNeeded e) {
                msg = T.t("Open the remote and enter the TV PIN first");
            } catch (Exception e) {
                msg = T.t("Cannot reach the TV");
            }
            final String m = msg;
            main.post(() -> UiKit.makeToast(this, m, Toast.LENGTH_LONG).show());
        }).start();
    }

    private void sendToTv(final String ip, final String url, final String title, final String mode) {
        new Thread(() -> {
            String msg;
            try {
                HashMap<String, String> p = new HashMap<>();
                p.put("url", url);
                p.put("title", title);
                p.put("mode", mode);
                JSONObject r = TvClient.post(ip, "/play", p);
                msg = r.optString("msg", r.optBoolean("ok") ? "Sent" : "Failed");
            } catch (TvClient.AuthNeeded e) {
                msg = T.t("Open the remote and enter the TV PIN first");
            } catch (Exception e) {
                msg = T.t("Cannot reach the TV");
            }
            final String m = msg;
            main.post(() -> UiKit.makeToast(this, m, Toast.LENGTH_LONG).show());
        }).start();
    }

    private List<String[]> bookmarkList() {
        List<String[]> l = new ArrayList<>();
        String saved = Store.getStr("bookmarks", "");
        if (saved.isEmpty() && !Store.getBool("bm_set", false)) {
            for (String[] b : DEFAULT_BOOKMARKS) l.add(b);
            return l;
        }
        for (String line : saved.split("\n")) {
            int tab = line.indexOf('\t');
            if (tab > 0) l.add(new String[]{line.substring(0, tab), line.substring(tab + 1)});
        }
        return l;
    }

    private void saveBookmarks(List<String[]> l) {
        StringBuilder sb = new StringBuilder();
        for (String[] b : l) sb.append(b[0].replace("\t", " ").replace("\n", " ")).append('\t').append(b[1]).append('\n');
        Store.putStr("bookmarks", sb.toString());
        Store.putBool("bm_set", true);
    }

    private void bookmarks() {
        final List<String[]> l = bookmarkList();
        final String cur = web.getUrl();
        List<String> names = new ArrayList<>();
        final boolean canAdd = cur != null && cur.startsWith("http");
        if (canAdd) names.add("⭐ " + T.t("Add this page"));
        for (String[] b : l) names.add(b[0]);
        new UiDialog(this).setTitle(T.t("Bookmarks"))
                .setItems(names.toArray(new String[0]), (d, w) -> {
                    if (canAdd && w == 0) {
                        l.add(0, new String[]{web.getTitle() == null ? cur : web.getTitle(), cur});
                        saveBookmarks(l);
                        UiKit.makeToast(this, T.t("Saved"), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    final String[] b = l.get(w - (canAdd ? 1 : 0));
                    new UiDialog(this).setTitle(b[0])
                            .setItems(new String[]{T.t("Open"), T.t("Remove")}, (d2, w2) -> {
                                if (w2 == 0) web.loadUrl(b[1]);
                                else {
                                    l.remove(b);
                                    saveBookmarks(l);
                                }
                            }).show();
                }).show();
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String u = intent.getStringExtra(EXTRA_URL);
        if (u != null && !u.isEmpty()) newTab(u);
    }

    private LinearLayout suggestBox;
    private boolean completing;

    private void hideSuggest() {
        if (suggestBox != null) suggestBox.setVisibility(View.GONE);
    }

    private void suggest(String typed, boolean inlineComplete) {
        String q = typed.trim();
        if (bar.getSelectionStart() != bar.getSelectionEnd() && bar.getSelectionEnd() == typed.length()) {
            q = typed.substring(0, bar.getSelectionStart()).trim();
        }
        if (q.isEmpty()) {
            hideSuggest();
            return;
        }
        if (suggestBox == null) {
            suggestBox = new LinearLayout(this);
            suggestBox.setOrientation(LinearLayout.VERTICAL);
            suggestBox.setBackground(Ui.card(this, Ui.dark() ? 0xF21A1722 : 0xF2FFFFFF, Ui.STROKE, 16));
            suggestBox.setElevation(Ui.dp(this, 10));
            suggestBox.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
            lp.setMargins(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), 0);
            webBox.addView(suggestBox, lp);
        }
        final String ql = q.toLowerCase(java.util.Locale.ROOT);
        List<BrowserData.Entry> hits = new ArrayList<>();
        List<Integer> score = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (BrowserData.Entry e : BrowserData.bookmarks()) addHit(e, ql, true, hits, score, seen);
        for (BrowserData.Entry e : BrowserData.history()) addHit(e, ql, false, hits, score, seen);
        Integer[] order = new Integer[hits.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (x, y) -> score.get(y) - score.get(x));
        suggestBox.removeAllViews();
        boolean looksUrl = q.contains(".") && !q.contains(" ");
        if (looksUrl) suggestRow("globe", q, T.t("Open this address"), q, null);
        int n = 0;
        String completion = null;
        for (Integer k : order) {
            if (n >= 6) break;
            BrowserData.Entry e = hits.get(k);
            String host = BrowserData.host(e.url);
            if (completion == null && host.toLowerCase(java.util.Locale.ROOT).startsWith(ql)) completion = host;
            suggestRow(null, e.title == null || e.title.isEmpty() ? host : e.title, host + (score.get(k) >= 1000 ? "  ★" : ""), e.url, e.url);
            n++;
        }
        suggestRow("search", q, T.t("Search") + " · " + BrowserLook.ENGINES[Store.getInt("bl_engine", 0)], q, null);
        suggestBox.setVisibility(View.VISIBLE);
        if (inlineComplete && completion != null && completion.length() > q.length() && typed.equals(q)) {
            completing = true;
            bar.setText(completion);
            bar.setSelection(q.length(), completion.length());
            completing = false;
        }
    }

    private void addHit(BrowserData.Entry e, String ql, boolean bookmark, List<BrowserData.Entry> hits, List<Integer> score,
                        java.util.Set<String> seen) {
        if (e.url == null || seen.contains(e.url)) return;
        String host = BrowserData.host(e.url).toLowerCase(java.util.Locale.ROOT);
        String t = e.title == null ? "" : e.title.toLowerCase(java.util.Locale.ROOT), u = e.url.toLowerCase(java.util.Locale.ROOT);
        int sc;
        if (host.startsWith(ql) || host.startsWith("m." + ql)) sc = 500;
        else if (t.contains(ql)) sc = 300;
        else if (u.contains(ql)) sc = 200;
        else return;
        sc += Math.min(200, e.visits * 10) + (bookmark ? 1000 : 0);
        seen.add(e.url);
        hits.add(e);
        score.add(sc);
    }

    private void suggestRow(String icon, String title, String sub, final String go, final String fill) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8));
        r.setBackground(Ui.focusCard(this, 10));
        ImageView ic = new ImageView(this);
        Bitmap fav = icon == null ? BrowserData.icon(this, go) : null;
        if (fav != null) ic.setImageBitmap(fav);
        else ic.setImageDrawable(new UiKit.Icon(this, icon == null ? "globe" : icon, 18, Ui.DIM));
        r.addView(ic, new LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 6), 0);
        TextView t = Ui.text(this, 14, Ui.TEXT, false);
        t.setText(title);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView sv = Ui.text(this, 11, Ui.DIM, false);
        sv.setText(sub);
        sv.setSingleLine(true);
        sv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(t);
        col.addView(sv);
        r.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (fill != null) {
            TextView up = Ui.text(this, 18, Ui.DIM, true);
            up.setText("↖");
            up.setGravity(Gravity.CENTER);
            up.setContentDescription(T.t("Edit this address"));
            up.setOnClickListener(v -> {
                completing = true;
                bar.setText(fill);
                bar.setSelection(fill.length());
                completing = false;
                suggest(fill, false);
            });
            r.addView(up, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        }
        r.setOnClickListener(v -> {
            hideSuggest();
            go(go);
            bar.clearFocus();
        });
        suggestBox.addView(r);
    }

    private static String hostOf(String origin) {
        String h = Uri.parse(origin).getHost();
        return h == null ? "" : h.toLowerCase(java.util.Locale.ROOT);
    }

    private void applySite(WebView v, String h) {
        WebSettings st = v.getSettings();
        boolean start = h == null || h.isEmpty() || h.equals("tamilcastplay.local");
        st.setJavaScriptEnabled(start || SiteSettings.allowed(h, "js"));
        st.setMediaPlaybackRequiresUserGesture(!start && !SiteSettings.allowed(h, "autoplay"));
        CookieManager.getInstance().setAcceptCookie(start || SiteSettings.allowed(h, "cookies"));
    }

    private interface Answer {
        void on(boolean ok);
    }

    private Answer pendingAnswer;
    private String[] pendingPerms;

    private void ask(final String host, final String key, final String[] androidPerms, final Answer ans) {
        java.util.List<String> keys = new ArrayList<>();
        keys.add(key);
        askMany(host, keys, java.util.Arrays.asList(androidPerms), ans);
    }

    private void askMany(final String host, final List<String> keys, final List<String> androidPerms, final Answer ans) {
        main.post(() -> {
            boolean anyBlock = false, allAllow = true;
            for (String k : keys) {
                String v = SiteSettings.get(host, k);
                if ("block".equals(v)) anyBlock = true;
                if (!"allow".equals(v)) allAllow = false;
            }
            if (anyBlock) {
                ans.on(false);
                return;
            }
            final Runnable thenAndroid = () -> {
                List<String> need = new ArrayList<>();
                for (String p : androidPerms) if (checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED) need.add(p);
                if (need.isEmpty()) ans.on(true);
                else {
                    pendingAnswer = ans;
                    pendingPerms = need.toArray(new String[0]);
                    requestPermissions(pendingPerms, 74);
                }
            };
            if (allAllow) {
                thenAndroid.run();
                return;
            }
            StringBuilder what = new StringBuilder();
            for (String k : keys) {
                if (what.length() > 0) what.append(" + ");
                for (int i = 0; i < SiteSettings.KEYS.length; i++)
                    if (SiteSettings.KEYS[i].equals(k)) what.append(SiteSettings.ICONS[i]).append(" ").append(T.t(SiteSettings.NAMES[i]));
            }
            final boolean[] remember = {true};
            new UiDialog(this).setTitle(host)
                    .setMultiChoiceItems(new String[]{T.t("Remember for this site")}, remember, (d, w, c) -> remember[0] = c)
                    .setMessage(null)
                    .setPositiveButton(T.t("Allow") + " " + what, (d, w) -> {
                        if (remember[0]) for (String k : keys) SiteSettings.set(host, k, "allow");
                        thenAndroid.run();
                    })
                    .setNegativeButton(T.t("Block"), (d, w) -> {
                        if (remember[0]) for (String k : keys) SiteSettings.set(host, k, "block");
                        ans.on(false);
                    })
                    .setOnCancelListener(d -> ans.on(false)).show();
        });
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        super.onRequestPermissionsResult(req, p, r);
        if (req != 74 || pendingAnswer == null) return;
        boolean ok = r.length > 0;
        for (int x : r) if (x != android.content.pm.PackageManager.PERMISSION_GRANTED) ok = false;
        Answer a = pendingAnswer;
        pendingAnswer = null;
        a.on(ok);
    }

    private void appearance() {
        BrowserLook.settings(this, () -> {
            if (isStartPage(web.getUrl())) home();
        }, () -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
            startActivityForResult(i, 73);
        });
    }

    private void library(int tab) {
        startActivityForResult(new android.content.Intent(this, BrowserLibraryActivity.class)
                .putExtra(BrowserLibraryActivity.EXTRA_TAB, tab), 71);
    }

    @Override
    protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        if (WgVpn.onResult(this, req, res, data)) {
            updateVpnBar();
            return;
        }
        if (req == 73) {
            if (res == RESULT_OK && data != null && data.getData() != null) {
                try {
                    BrowserLook.setPhoto(this, data.getData());
                    if (isStartPage(web.getUrl())) home();
                    appearance();
                } catch (Exception e) {
                    UiKit.makeToast(this, T.t("Can't use this picture") + ": " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
            return;
        }
        if (req == 72) {
            if (fileCb != null) fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            fileCb = null;
            return;
        }
        if (req != 71 || res != RESULT_OK || data == null) return;
        String u = data.getStringExtra(BrowserLibraryActivity.RESULT_URL);
        if (u == null) return;
        if (data.getBooleanExtra(BrowserLibraryActivity.RESULT_NEW_TAB, false)) newTab(u);
        else go(u);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateVpnBar();
        resumeMediaForPip();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveTabs();

        // Prevent web engine from suspending while in PiP
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode()) {
            if (web != null) {
                web.onResume();
            }
        }
    }

    private void hideCustom() {
        if (customView != null) {
            removeLockUi();
            root.removeView(customView);
            customView = null;
            if (customCb != null) customCb.onCustomViewHidden();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_DOWN) {
            bar.requestFocus();
            return true;
        }
        if (tv && cursor != null && customView == null && web != null && web.hasFocus()) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            int step = Ui.dp(this, Math.min(48, 10 + e.getRepeatCount() * 4));
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (down) {
                        if (cursor.y <= Ui.dp(this, 12)) {
                            bar.requestFocus();
                            return true;
                        } else if (cursor.y <= Ui.dp(this, 40)) {
                            web.scrollBy(0, -step * 3);
                        } else {
                            cursor.move(0, -step);
                        }
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down) {
                        if (cursor.y >= webBox.getHeight() - Ui.dp(this, 40)) web.scrollBy(0, step * 3);
                        else cursor.move(0, step);
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (down) cursor.move(-step, 0);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down) cursor.move(step, 0);
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (down && e.getRepeatCount() == 0) clickAt(cursor.x, cursor.y, false);
                    else if (down && e.getRepeatCount() == 10) clickAt(cursor.x, cursor.y, true);
                    return true;
                default:
                    break;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    private void clickAt(float x, float y, boolean longPress) {
        long t = SystemClock.uptimeMillis();
        MotionEvent d = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
        web.dispatchTouchEvent(d);
        d.recycle();
        long up = longPress ? 700 : 60;
        main.postDelayed(() -> {
            long t2 = SystemClock.uptimeMillis();
            MotionEvent u = MotionEvent.obtain(t, t2, MotionEvent.ACTION_UP, x, y, 0);
            web.dispatchTouchEvent(u);
            u.recycle();
        }, up);
    }

    @Override
    public void onBackPressed() {
        if (tabsView != null) {
            root.removeView(tabsView);
            tabsView = null;
            return;
        }
        if (locked && customView != null) {
            flashLock();
            UiKit.makeToast(this, T.t("Screen locked — tap the lock to unlock"), Toast.LENGTH_SHORT).show();
            return;
        }
        if (customView != null) hideCustom();
        else if (bar.hasFocus() && tv) web.requestFocus();
        else if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacks(sleepFire);
        if (isFinishing()) sleepAt = 0;
        saveTabs();
        MusicFx.onChange = null;
        WgVpn.onChange = null;
        BrowserPlayService.control = null;
        BrowserPlayService.stop(this);

        for (Bitmap b : thumbs.values()) {
            if (b != null && !b.isRecycled()) {
                b.recycle();
            }
        }
        thumbs.clear();

        for (WebView w : tabs) {
            try {
                w.stopLoading();
                w.loadUrl("about:blank");
                w.clearHistory();
                w.removeAllViews();
                w.destroy();
            } catch (Throwable ignored) {
            }
        }
        tabs.clear();
        super.onDestroy();
    }

    static final class CursorView extends View {
        float x = -1, y = -1;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrow = new Path();

        CursorView(android.content.Context c) {
            super(c);
            setFocusable(false);
            setClickable(false);
            fill.setColor(Color.WHITE);
            edge.setColor(Ui.ACCENT);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(3 * getResources().getDisplayMetrics().density);
        }

        void move(float dx, float dy) {
            if (x < 0) {
                x = getWidth() / 2f;
                y = getHeight() / 2f;
            }
            x = Math.max(0, Math.min(getWidth() - 1, x + dx));
            y = Math.max(0, Math.min(getHeight() - 1, y + dy));
            invalidate();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            return false;
        }

        @Override
        protected void onDraw(Canvas c) {
            if (x < 0) {
                x = getWidth() / 2f;
                y = getHeight() / 2f;
            }
            float s = getResources().getDisplayMetrics().density * 1.6f;
            arrow.reset();
            arrow.moveTo(x, y);
            arrow.lineTo(x, y + 18 * s);
            arrow.lineTo(x + 5 * s, y + 13.5f * s);
            arrow.lineTo(x + 8.5f * s, y + 21 * s);
            arrow.lineTo(x + 11.5f * s, y + 19.5f * s);
            arrow.lineTo(x + 8 * s, y + 12 * s);
            arrow.lineTo(x + 14 * s, y + 12 * s);
            arrow.close();
            c.drawPath(arrow, fill);
            c.drawPath(arrow, edge);
        }
    }
}