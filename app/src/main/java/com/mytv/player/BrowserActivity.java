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

        // ---- toolbar
        LinearLayout tb = new LinearLayout(this);
        tb.setGravity(Gravity.CENTER_VERTICAL);
        int p = Ui.dp(this, 8);
        tb.setPadding(p, p + Ui.dp(this, tv ? 4 : 18), p, p);
        tb.setBackground(Ui.background());
        tb.addView(small("←", v -> { if (web.canGoBack()) web.goBack(); }));
        final boolean narrow = getResources().getConfiguration().screenWidthDp < 600;
        if (!narrow) tb.addView(small("→", v -> { if (web.canGoForward()) web.goForward(); }));
        tb.addView(small("🏠", v -> Ui.goHome(this, tv)));
        if (!narrow) tb.addView(small("⌂", v -> home()));
        bar = new EditText(this);
        bar.setSingleLine(true);
        bar.setHint(T.t("Search or type a link"));
        bar.setTextColor(Ui.TEXT);
        bar.setHintTextColor(Ui.DIM);
        bar.setTextSize(15);
        bar.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));
        bar.setBackground(Ui.focusCard(this, 22));
        bar.setImeOptions(EditorInfo.IME_ACTION_GO);
        bar.setSelectAllOnFocus(true);
        bar.setOnEditorActionListener((v, a, e) -> {
            go(bar.getText().toString());
            return true;
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1);
        blp.setMargins(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0);
        tb.addView(bar, blp);
        videosBtn = Ui.button(this, "🎬 0", true);
        videosBtn.setOnClickListener(v -> showVideos());
        tb.addView(videosBtn);
        tb.addView(small("☆", v -> bookmarks()));
        col.addView(tb);

        loading = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        loading.setMax(100);
        col.addView(loading, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 3)));

        webBox = new FrameLayout(this);
        web = new WebView(this);
        webBox.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (tv) {
            cursor = new CursorView(this);
            webBox.addView(cursor, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        col.addView(webBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        if (tv) s.setUserAgentString(s.getUserAgentString().replace("; wv", ""));
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return handleNav(r.getUrl().toString());
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap f) {
                found.clear();
                updateVideos();
                if (!bar.hasFocus() && !url.startsWith("data:") && !url.startsWith("about:")) bar.setText(url);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                sniff(r.getUrl().toString(), null);
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
        web.setDownloadListener((url, ua, cd, mime, len) -> {
            if (isPlayable(url, mime)) offer(url, android.webkit.URLUtil.guessFileName(url, cd, mime));
            else Toast.makeText(this, T.t("Downloads are not supported — only videos, songs and torrents"), Toast.LENGTH_LONG).show();
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

        String start = getIntent().getStringExtra(EXTRA_URL);
        if (start != null && !start.isEmpty()) go(start);
        else home();
        if (tv) web.requestFocus();
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

    private void home() {
        StringBuilder h = new StringBuilder();
        String bg = Ui.dark() ? "#0b0913" : "#f5f3fb", fg = Ui.dark() ? "#f3f0ff" : "#1b1530",
                card = Ui.dark() ? "rgba(255,255,255,.08)" : "#fff";
        h.append("<html><head><meta name=viewport content='width=device-width,initial-scale=1'><style>")
                .append("body{margin:0;padding:28px;font-family:sans-serif;color:").append(fg)
                .append(";background:linear-gradient(160deg,").append(Ui.dark() ? "#1c1236" : "#e7dffc").append(",").append(bg).append(")}")
                .append("h1{font-size:22px}form{display:flex;gap:8px;margin:18px 0 26px}")
                .append("input{flex:1;padding:14px 18px;border-radius:26px;border:1px solid #8b5cf6;font-size:17px;background:").append(card).append(";color:").append(fg).append("}")
                .append("button{padding:0 22px;border:0;border-radius:26px;background:linear-gradient(135deg,#8b5cf6,#ec4899);color:#fff;font-size:16px}")
                .append(".g{display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:12px}")
                .append("a{display:block;padding:18px;border-radius:18px;background:").append(card)
                .append(";color:").append(fg).append(";text-decoration:none;font-weight:600;border:1px solid rgba(139,92,246,.35)}")
                .append("a:focus{outline:3px solid #ec4899}p{opacity:.7;font-size:14px}</style></head><body>")
                .append("<h1>🌐 ").append(T.t("Search & play")).append("</h1>")
                .append("<form onsubmit=\"location.href='https://www.google.com/search?q='+encodeURIComponent(q.value);return false\">")
                .append("<input id=q placeholder='").append(T.t("Search videos, movies, songs…")).append("'><button>")
                .append(T.t("Search")).append("</button></form><div class=g>");
        for (String[] b : bookmarkList()) {
            h.append("<a href='").append(b[1].replace("'", "%27")).append("'>").append(android.text.Html.escapeHtml(b[0])).append("</a>");
        }
        h.append("</div><p>").append(T.t("Videos found on a page appear under 🎬. Long-press any link to play it."))
                .append(tv ? " " + T.t("Remote: arrows move the pointer, OK clicks, Menu opens the address bar.") : "")
                .append("</p></body></html>");
        web.loadDataWithBaseURL("https://tamilcastplay.local/", h.toString(), "text/html", "utf-8", null);
        bar.setText("");
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
        videosBtn.setText("🎬 " + found.size());
    }

    private void showVideos() {
        if (found.isEmpty()) {
            Toast.makeText(this, T.t("No videos found on this page yet. Start the video on the page first."), Toast.LENGTH_LONG).show();
            return;
        }
        final List<String> us = new ArrayList<>(found.keySet());
        String[] names = new String[us.size()];
        for (int i = 0; i < names.length; i++) names[i] = found.get(us.get(i));
        new AlertDialog.Builder(this).setTitle(T.t("Videos on this page"))
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
            Toast.makeText(this, T.t("Saved"), Toast.LENGTH_SHORT).show();
        });
        labels.add(T.t("Open link here"));
        acts.add(() -> web.loadUrl(url));
        new AlertDialog.Builder(this).setTitle(t)
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
            main.post(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
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
        new AlertDialog.Builder(this).setTitle(T.t("Bookmarks"))
                .setItems(names.toArray(new String[0]), (d, w) -> {
                    if (canAdd && w == 0) {
                        l.add(0, new String[]{web.getTitle() == null ? cur : web.getTitle(), cur});
                        saveBookmarks(l);
                        Toast.makeText(this, T.t("Saved"), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    final String[] b = l.get(w - (canAdd ? 1 : 0));
                    new AlertDialog.Builder(this).setTitle(b[0])
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
        if (web != null) web.destroy();
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
