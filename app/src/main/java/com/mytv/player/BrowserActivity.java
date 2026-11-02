package com.mytv.player;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.app.SearchManager;
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
    /** Opened from Music to LISTEN: audio only; the browser steps back once the sound starts. */
    public static final String EXTRA_LISTEN = "listen";
    private int videoFitMode = 0;                   // 0=contain, 1=fill, 2=cover, 3=zoom 1.2x
    /**
     * Safe fit CSS for YouTube: never force width/height (that hides the video).
     * Only object-fit + transform on the video element.
     */
    private static final String[] FIT_JS = {
            // 0 Fit (contain)
            "(function(){var s=document.getElementById('zh-fit')||document.createElement('style');s.id='zh-fit';"
            + "s.textContent='video{object-fit:contain!important;transform:none!important}';"
            + "(document.head||document.documentElement).appendChild(s);})();",
            // 1 Fill / Stretch
            "(function(){var s=document.getElementById('zh-fit')||document.createElement('style');s.id='zh-fit';"
            + "s.textContent='video{object-fit:fill!important;transform:none!important}';"
            + "(document.head||document.documentElement).appendChild(s);})();",
            // 2 Crop (cover)
            "(function(){var s=document.getElementById('zh-fit')||document.createElement('style');s.id='zh-fit';"
            + "s.textContent='video{object-fit:cover!important;transform:none!important}';"
            + "(document.head||document.documentElement).appendChild(s);})();",
            // 3 Zoom 1.2x (keeps contain, scales up)
            "(function(){var s=document.getElementById('zh-fit')||document.createElement('style');s.id='zh-fit';"
            + "s.textContent='video{object-fit:contain!important;transform:scale(1.2)!important;transform-origin:center center!important}';"
            + "(document.head||document.documentElement).appendChild(s);})();"
    };
    private static final String[] FIT_NAMES = {"Fit", "Fill / Stretch", "Crop", "Zoom 1.2×"};
    private static final String[] FIT_ICONS = {"ratio", "move-horizontal", "crop", "zoom-in"};

    private void cycleVideoFit() {
        videoFitMode = (videoFitMode + 1) % FIT_JS.length;
        applyVideoFit();
    }

    private void applyVideoFit() {
        // Prefer Android-side scale on the fullscreen host (reliable on YouTube)
        if (customZoomHost instanceof ZoomLayout) {
            ((ZoomLayout) customZoomHost).setFitMode(videoFitMode);
        }
        // Gentle CSS only — never width/height 100%
        if (web != null) web.evaluateJavascript(FIT_JS[videoFitMode], null);
        UiKit.makeToast(this, T.t("Video fit") + ": " + T.t(FIT_NAMES[videoFitMode]), Toast.LENGTH_SHORT).show();
        View btn = root.findViewWithTag("fs_fit");
        if (btn instanceof ImageView) UiKit.setIcon(this, (ImageView) btn, FIT_ICONS[videoFitMode], 22, android.graphics.Color.WHITE);
    }

    private boolean listenBack;

    private WebView web;
    private EditText bar;
    private ProgressBar loading;
    private CursorView cursor;
    private FrameLayout root, webBox;
    private LinearLayout mainColumn, topBar;
    private View customView;
