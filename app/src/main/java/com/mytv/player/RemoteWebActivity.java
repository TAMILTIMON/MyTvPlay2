package com.mytv.player;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/** The remote control inside the phone app. Adds native extras: stream phone files, pick .torrent files. */
public class RemoteWebActivity extends AppCompatActivity {
    public static final String EXTRA_IP = "ip", EXTRA_NAME = "name";
    private static final int PICK_MEDIA = 11, PICK_TORRENT = 12, PICK_FILE = 13;

    private WebView web;
    private String ip;
    private ValueCallback<Uri[]> fileCb;
    private String pendingMode = "now";

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Ui.apply(this);
        super.onCreate(savedInstanceState);
        ip = getIntent().getStringExtra(EXTRA_IP);
        web = new WebView(this);
        web.setBackgroundColor(Ui.BG);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);              // ignore phone font scaling (was making pages look zoomed)
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        web.addJavascriptInterface(new Bridge(), "MyTV");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) showOffline();
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try {
                    startActivityForResult(p.createIntent(), PICK_FILE);
                } catch (Exception e) {
                    fileCb = null;
                    return false;
                }
                return true;
            }
        });
        setContentView(web);
        load();
    }

    private void load() {
        web.loadUrl("http://" + ip + ":" + RemoteService.PORT + "/?app=1&theme=" + (Ui.dark() ? "dark" : "light"));
    }

    private void showOffline() {
        String bg = Ui.dark() ? "#0b0913" : "#f5f3fb", fg = Ui.dark() ? "#f3f0ff" : "#1b1530";
        web.loadDataWithBaseURL(null, "<html><body style='background:" + bg + ";color:" + fg
                + ";font-family:sans-serif;padding:40px;text-align:center'><h2>📺 Cannot reach the TV</h2>"
                + "<p>Is the TV on and TamilCastPlay started once? Same Wi-Fi?</p>"
                + "<p><button style='padding:14px 24px;border:0;border-radius:24px;background:#8b5cf6;color:#fff;font-size:16px' "
                + "onclick='MyTV.retry()'>Try again</button></p><p><button style='padding:12px 22px;border:0;border-radius:24px;"
                + "background:#ec4899;color:#fff' onclick='MyTV.switchTv()'>Choose another TV</button></p></body></html>",
                "text/html", "utf-8", null);
    }

    @Override
    public void onBackPressed() {
        // first go back to the Remote tab, then leave
        web.evaluateJavascript("window.backTab?backTab():false", v -> {
            if (!"true".equals(v)) {
                if (web.canGoBack()) web.goBack();
                else finish();
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }

    private void notifyPage(final String msg) {
        runOnUiThread(() -> {
            try {
                web.evaluateJavascript("window.toast&&toast(" + JSONObject.quote(msg) + ")", null);
            } catch (Exception ignored) {
            }
        });
    }

    private final class Bridge {
        @JavascriptInterface
        public boolean isApp() {
            return true;
        }

        @JavascriptInterface
        public void pickMedia(String mode) {
            pendingMode = "queue".equals(mode) ? "queue" : "now";
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"video/*", "audio/*"});
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(i, PICK_MEDIA);
        }

        @JavascriptInterface
        public void pickTorrent() {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, PICK_TORRENT);
        }

        @JavascriptInterface
        public void switchTv() {
            runOnUiThread(() -> {
                startActivity(new Intent(RemoteWebActivity.this, ConnectActivity.class)
                        .putExtra("no_auto", true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
                finish();
            });
        }

        @JavascriptInterface
        public void openTv(final String newIp) {
            runOnUiThread(() -> {
                ip = newIp;
                Store.putStr("last_tv", newIp);
                load();
            });
        }

        @JavascriptInterface
        public void setToken(String t) {
            TvClient.setToken(ip, t);
        }

        @JavascriptInterface
        public String getToken() {
            return TvClient.token(ip);
        }

        @JavascriptInterface
        public void openBrowser() {
            runOnUiThread(() -> startActivity(new Intent(RemoteWebActivity.this, BrowserActivity.class)));
        }

        @JavascriptInterface
        public void home() {
            runOnUiThread(() -> Ui.goHome(RemoteWebActivity.this, false));
        }

        @JavascriptInterface
        public void retry() {
            runOnUiThread(RemoteWebActivity.this::load);
        }

        @JavascriptInterface
        public void setTheme(String t) {
            Store.putStr("theme", "light".equals(t) ? "light" : "dark");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == PICK_FILE) {
            if (fileCb != null) fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            fileCb = null;
            return;
        }
        if (res != RESULT_OK || data == null) return;
        final List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) uris.add(data.getData());
        if (uris.isEmpty()) return;
        for (Uri u : uris) {
            try {
                getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
        }
        final boolean torrent = req == PICK_TORRENT;
        final String mode = pendingMode;
        new Thread(() -> {
            StringBuilder urls = new StringBuilder(), titles = new StringBuilder();
            for (Uri u : uris) {
                String name = LocalShare.displayName(this, u);
                if (torrent && !name.toLowerCase().endsWith(".torrent")) name = name + ".torrent";
                urls.append(LocalShare.share(this, u, name)).append('\n');
                titles.append(name).append('\n');
            }
            try {
                java.util.HashMap<String, String> prm = new java.util.HashMap<>();
                prm.put("urls", urls.toString());
                prm.put("titles", titles.toString());
                prm.put("mode", mode);
                JSONObject r = TvClient.post(ip, "/playmany", prm);
                notifyPage(r.optBoolean("ok") ? (torrent ? "Torrent sent to the TV 🧲"
                        : uris.size() + " file(s) streaming to the TV — keep this app open") : r.optString("msg", "TV refused"));
            } catch (TvClient.AuthNeeded e) {
                notifyPage("Enter the TV PIN first (shown on the TV)");
            } catch (Exception e) {
                notifyPage("Could not reach the TV: " + e.getMessage());
            }
        }).start();
    }
}
