package com.mytv.player;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
import android.widget.TextView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
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
    private Button videosBtn;
    private ProgressBar loading;
    private CursorView cursor;
    private FrameLayout root, webBox;
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
        Ui.apply(this);
        super.onCreate(savedInstanceState);
        tv = Ui.isTv(this);
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        Shield.init(this);
        final boolean narrow = getResources().getConfiguration().screenWidthDp < 600 && !tv;

        // ---- top: address bar (Chrome / Brave / Edge style)
        LinearLayout tb = new LinearLayout(this);
        tb.setGravity(Gravity.CENTER_VERTICAL);
        int p = Ui.dp(this, 8);
        tb.setPadding(p, p + Ui.dp(this, tv ? 4 : 14), p, p);
        tb.setBackgroundColor(Ui.BAR);
        if (!narrow) {
            tb.addView(navBtn("arrow-left", v -> { if (web.canGoBack()) web.goBack(); }));
            tb.addView(navBtn("arrow-right", v -> { if (web.canGoForward()) web.goForward(); }));
            tb.addView(navBtn("house", v -> home()));
        }
        LinearLayout omni = new LinearLayout(this);
        omni.setGravity(Gravity.CENTER_VERTICAL);
        omni.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 4), 0);
        omni.setBackground(Ui.card(this, Ui.FIELD, Ui.STROKE, 24));
        siteIcon = new ImageView(this);
        siteIcon.setScaleType(ImageView.ScaleType.CENTER);
        omni.addView(siteIcon, new LinearLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 26)));
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
            go(bar.getText().toString());
            bar.clearFocus();
            return true;
        });
        bar.setOnFocusChangeListener((v, f) -> {
            if (f) {
                String u = web.getUrl();
                bar.setText(u == null || u.startsWith("data:") || u.startsWith("about:") || u.contains("tamilcastplay.local") ? "" : u);
                bar.selectAll();
            } else updateOmni();
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
        tb.addView(omni, olp);
        // Brave-style shield with the number of blocked ads / trackers on this page
        FrameLayout sh = new FrameLayout(this);
        shieldIcon = UiKit.roundButton(this, "shield-check", 42, false, v -> shieldPanel());
        sh.addView(shieldIcon, new FrameLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));
        shieldBadge = Ui.text(this, 10, Color.WHITE, true);
        shieldBadge.setGravity(Gravity.CENTER);
        shieldBadge.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        shieldBadge.setBackground(Ui.card(this, SHIELD_ORANGE, 0, 9));
        sh.addView(shieldBadge, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 18), Gravity.TOP | Gravity.END));
        tb.addView(sh);
        videosBtn = Ui.button(this, "🎬", true);
        videosBtn.setOnClickListener(v -> showVideos());
        videosBtn.setTextSize(13);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 40));
        vlp.setMargins(Ui.dp(this, 6), 0, 0, 0);
        tb.addView(videosBtn, vlp);
        tabsBtn = tabCounter();
        if (!narrow) {
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40));
            tl.setMargins(Ui.dp(this, 6), 0, 0, 0);
            tb.addView(tabsBtn, tl);
            tb.addView(navBtn("ellipsis-vertical", v -> menu()));
        }
        col.addView(tb);

        loading = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        loading.setMax(100);
        loading.setProgressDrawable(new android.graphics.drawable.ClipDrawable(Ui.gradient(this, 0), Gravity.START,
                android.graphics.drawable.ClipDrawable.HORIZONTAL));
        loading.setBackgroundColor(0x00000000);
        col.addView(loading, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 3)));
        bottomBar = narrow ? buildBottomBar() : null;
        webBox = new FrameLayout(this);
        web = new WebView(this);
        tabs.add(web);
        webBox.addView(web, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (tv) {
            cursor = new CursorView(this);
            webBox.addView(cursor, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        col.addView(webBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        findBar = buildFindBar();
        col.addView(findBar);
        if (bottomBar != null) col.addView(bottomBar);
        setContentView(root);

        configure(web);

        String start = getIntent().getStringExtra(EXTRA_URL);
        if (start != null && !start.isEmpty()) go(start);
        else home();
        if (tv) web.requestFocus();
    }

    /** All the WebView settings and listeners, for every tab. */
    @SuppressLint("SetJavaScriptEnabled")
    private void configure(final WebView web) {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
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
                if (url != null && url.startsWith("http") && !Store.getBool("b_private", false)) addHistory(v.getTitle(), url);
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
                loading.setProgress(pr);
                loading.setVisibility(pr >= 100 ? View.INVISIBLE : View.VISIBLE);
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback cb) {
                customView = view;
                customCb = cb;
                root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }

            @Override
            public void onHideCustomView() {
                hideCustom();
            }
        });
        web.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void ad() {
                Shield.counted();   // a YouTube ad was skipped
                Integer n = pageBlocked.get(web);
                pageBlocked.put(web, n == null ? 1 : n + 1);
                main.post(BrowserActivity.this::updateShield);
            }
        }, "TamilShield");
        web.setFindListener((active, count, done) -> {
            if (findCount != null) findCount.setText(count == 0 ? "0/0" : (active + 1) + "/" + count);
        });
        web.setDownloadListener((url, ua, cd, mime, len) -> {
            if (isPlayable(url, mime)) offer(url, android.webkit.URLUtil.guessFileName(url, cd, mime));
            else UiKit.makeToast(this, T.t("Downloads are not supported — only videos, songs and torrents"), Toast.LENGTH_LONG).show();
        });
        web.setOnLongClickListener(v -> {
            WebView.HitTestResult h = web.getHitTestResult();
            String u = h.getExtra();
            int t = h.getType();
            if (u != null && (t == WebView.HitTestResult.SRC_ANCHOR_TYPE || t == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE)) {
                offer(u, null);
                return true;
            }
            return false;
        });

    }

    // ---------------------------------------------------------------- tabs
    private final List<WebView> tabs = new ArrayList<>();
    private Button tabsBtn;

    private void switchTo(WebView w) {
        if (w == web) return;
        snap(web);
        webBox.removeView(web);
        web = w;
        if (w.getParent() == null) webBox.addView(w, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        found.clear();
        updateVideos();
        tabsBtn.setText(String.valueOf(tabs.size()));
        updateOmni();
        updateShield();
        w.requestFocus();
    }

    private void newTab(String url) {
        WebView w = new WebView(this);
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
        if (w == web) switchTo(tabs.get(Math.max(0, i - 1)));
        else webBox.removeView(w);
        w.destroy();
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
    private ImageView siteIcon, reloadBtn, shieldIcon;
    private TextView shieldBadge, findCount;
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

    /** The square with the number of tabs, like Chrome. */
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

    /** Phone: back · forward · home · tabs · menu at the bottom (like Chrome / Brave on phones). */
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

    /** Address bar shows just the site (with a lock for https) unless you are typing. */
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

    /** Brave-style shield panel. */
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

    /** Menu sheet with an icon grid (like Chrome / Edge). */
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
                {"bookmark", T.t("Bookmarks"), (Runnable) this::bookmarks},
                {"star", T.t("Add bookmark"), (Runnable) () -> {
                    if (cur == null || isStartPage(cur)) return;
                    List<String[]> l = bookmarkList();
                    l.add(0, new String[]{web.getTitle() == null ? cur : web.getTitle(), cur});
                    saveBookmarks(l);
                    UiKit.makeToast(this, T.t("Saved"), Toast.LENGTH_SHORT).show();
                }},
                {"clock", T.t("History"), (Runnable) this::showHistory},
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
                {"house", T.t("Exit browser"), (Runnable) () -> Ui.goHome(this, tv)}};
        LinearLayout row = null;
        for (int i = 0; i < it.length; i++) {
            if (i % 4 == 0) {
                row = new LinearLayout(this);
                box.addView(row);
            }
            final Runnable run = (Runnable) it[i][2];
            LinearLayout c = new LinearLayout(this);
            c.setOrientation(LinearLayout.VERTICAL);
            c.setGravity(Gravity.CENTER_HORIZONTAL);
            c.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
            c.setBackground(Ui.focusCard(this, 16));
            c.setFocusable(true);
            c.setClickable(true);
            c.addView(UiKit.icon(this, (String) it[i][0], 22, Ui.TEXT), new LinearLayout.LayoutParams(Ui.dp(this, 30), Ui.dp(this, 30)));
            TextView t = Ui.text(this, 11, Ui.TEXT, false);
            t.setText((String) it[i][1]);
            t.setGravity(Gravity.CENTER);
            t.setMaxLines(2);
            t.setPadding(Ui.dp(this, 2), Ui.dp(this, 4), Ui.dp(this, 2), 0);
            c.addView(t);
            c.setOnClickListener(v -> {
                d.dismiss();
                run.run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            lp.setMargins(Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3));
            row.addView(c, lp);
        }
        while (row != null && row.getChildCount() < 4) row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        d.setContentView(box);
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
        }
        d.show();
    }

    // ---- tab switcher: cards with a picture of each tab
    private void snap(WebView w) {
        try {
            if (w.getWidth() <= 0) return;
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
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(this, 12), Ui.dp(this, tv ? 16 : 22), Ui.dp(this, 12), Ui.dp(this, 8));
        tabsView.addView(col);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(navBtn("arrow-left", v -> root.removeView(tabsView)));
        TextView h = Ui.text(this, 19, Ui.TEXT, true);
        h.setText("  " + T.t("Tabs") + " · " + tabs.size());
        top.addView(h, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button closeAll = Ui.button(this, T.t("Close all"), false);
        closeAll.setOnClickListener(v -> {
            while (tabs.size() > 1) closeTab(tabs.get(tabs.size() - 1));
            home();
            root.removeView(tabsView);
        });
        top.addView(closeAll);
        Button add = Ui.button(this, "＋ " + T.t("New tab"), true);
        add.setOnClickListener(v -> {
            root.removeView(tabsView);
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
        g.setAdapter(new android.widget.BaseAdapter() {
            public int getCount() { return tabs.size(); }
            public Object getItem(int p) { return tabs.get(p); }
            public long getItemId(int p) { return p; }
            public View getView(int p, View cv, ViewGroup parent) {
                final WebView w = tabs.get(p);
                LinearLayout card = new LinearLayout(BrowserActivity.this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setBackground(Ui.card(BrowserActivity.this, Ui.SOLID, w == web ? Ui.ACCENT : Ui.STROKE, 16));
                card.setPadding(Ui.dp(BrowserActivity.this, 6), Ui.dp(BrowserActivity.this, 6), Ui.dp(BrowserActivity.this, 6), Ui.dp(BrowserActivity.this, 6));
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
                Bitmap b = thumbs.get(w);
                if (b != null) pic.setImageBitmap(b);
                else pic.setImageDrawable(new UiKit.Icon(BrowserActivity.this, "globe", 32, Ui.DIM));
                LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(BrowserActivity.this, tv ? 120 : 170));
                pl.setMargins(0, Ui.dp(BrowserActivity.this, 6), 0, 0);
                card.addView(pic, pl);
                return card;
            }
        });
        g.setOnItemClickListener((pa, v, pos, id) -> {
            root.removeView(tabsView);
            switchTo(tabs.get(pos));
        });
        col.addView(g, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(tabsView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        g.requestFocus();
    }

    // ---- new-tab page: search, shortcuts, recent, shield stats
    private static String esc(String s) {
        return android.text.Html.escapeHtml(s == null ? "" : s);
    }

    private void home() {
        boolean dk = Ui.dark();
        String bg = dk ? "#0b0913" : "#f6f5fb", fg = dk ? "#f3f0ff" : "#1b1530", dim = dk ? "#a59fbd" : "#6b6482",
                card = dk ? "rgba(255,255,255,.06)" : "#ffffff", line = dk ? "rgba(255,255,255,.10)" : "rgba(27,21,48,.10)";
        String acc = String.format("#%06X", Ui.ACCENT & 0xFFFFFF), acc2 = String.format("#%06X", Ui.ACCENT2 & 0xFFFFFF);
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
                .append("</style></head><body><div class=wrap>")
                .append("<div class=logo><i></i><b>ZH Play</b></div>")
                .append("<form onsubmit=\"location.href='https://www.google.com/search?q='+encodeURIComponent(q.value);return false\">")
                .append("<input id=q placeholder='").append(esc(T.t("Search Google or type a web address"))).append("'><button>")
                .append(esc(T.t("Search"))).append("</button></form>");
        if (Shield.enabled()) {
            h.append("<div class=stats><div><b>").append(tot).append("</b><span>").append(esc(T.t("Ads & trackers blocked"))).append("</span></div>")
                    .append("<div><b>").append(Math.max(0, tot * 2 / 60)).append(" min</b><span>").append(esc(T.t("Time saved"))).append("</span></div>")
                    .append("<div><b>").append(String.format(java.util.Locale.US, "%.1f MB", tot * 0.05)).append("</b><span>").append(esc(T.t("Data saved")))
                    .append("</span></div></div>");
        }
        String[] colors = {"#ef4444", "#f97316", "#eab308", "#22c55e", "#14b8a6", "#3b82f6", "#8b5cf6", "#ec4899"};
        List<String[]> bm = bookmarkList();
        if (!bm.isEmpty()) {
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
        if (!rec.isEmpty()) {
            h.append("<h2>").append(esc(T.t("Recent"))).append("</h2><div class=r>");
            for (String[] b : rec) {
                h.append("<a href='").append(b[1].replace("'", "%27")).append("'>").append(esc(b[0])).append("<small>")
                        .append(esc(android.net.Uri.parse(b[1]).getHost())).append("</small></a>");
            }
            h.append("</div>");
        }
        h.append("<p>").append(esc(T.t("Videos found on a page appear under the film button. Long-press any link to play it."))).append("</p></div></body></html>");
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

    // ---------- navigation ----------
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


    /** Return true when we handle the link ourselves. */
    private boolean handleNav(String u) {
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

    /** Collects streams the page loads (m3u8, mp4, mpd…) for the 🎬 button. */
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
        videosBtn.setText(String.valueOf(found.size()));
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
                .setItems(names, (d, w) -> offer(us.get(w), found.get(us.get(w)))).show();
    }

    // ---------- play / send ----------
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
            // magnet / .torrent found: play now or keep for later
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
        labels.add(T.t("💾 Save to playlist “From browser”"));
        acts.add(() -> {
            Store.add(T.t("From browser"), new Store.Item(t, withHeaders(url), ""));
            UiKit.makeToast(this, T.t("Saved"), Toast.LENGTH_SHORT).show();
        });
        labels.add(T.t("Open link here"));
        acts.add(() -> web.loadUrl(url));
        new UiDialog(this).setTitle((Formats.isTorrent(url) ? "🧲 " + T.t("Torrent found — play or download?") + "\n" : "") + t)
                .setItems(labels.toArray(new String[0]), (d, w) -> acts.get(w).run()).show();
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

    // ---------- bookmarks ----------
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

    // ---------- keys / back ----------
    private void hideCustom() {
        if (customView != null) {
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
        if (tv && cursor != null && customView == null && web.hasFocus()) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            int step = Ui.dp(this, Math.min(48, 10 + e.getRepeatCount() * 4));
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (down) {
                        if (cursor.y <= Ui.dp(this, 4) && web.getScrollY() == 0) bar.requestFocus();
                        else if (cursor.y <= Ui.dp(this, 40)) web.scrollBy(0, -step * 3);
                        else cursor.move(0, -step);
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

    /** Simulates a tap (or long-press) at the cursor. */
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
        if (customView != null) hideCustom();
        else if (bar.hasFocus() && tv) web.requestFocus();
        else if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        for (WebView w : tabs) w.destroy();
        super.onDestroy();
    }

    /** Mouse pointer drawn over the page for the TV remote. */
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
