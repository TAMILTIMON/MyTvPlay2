package com.mytv.player;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.text.InputType;
import android.util.LruCache;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Phone: presenter for a PDF on the TV / Linux PC. Pinch to zoom (sharp on the big screen), two-finger pan,
 * double-tap = whole slide; tools: Laser · Pen (colours) · Spotlight · Move; black / white screen,
 * slide overview, go to slide, timer, volume keys = next / previous, rotation.
 */
public class PresentActivity extends AppCompatActivity {
    public static final String EXTRA_IP = "ip";
    private static final int LASER = 0, PEN = 1, SPOT = 2, MOVE = 3;
    private static final int[] PEN_COLORS = {0xFFEF4444, 0xFFFACC15, 0xFF22C55E, 0xFF3B82F6, 0xFFFFFFFF};

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final ExecutorService draw = Executors.newSingleThreadExecutor();
    private String ip, title = "";
    private PdfRenderer renderer;
    private ParcelFileDescriptor pfd;
    private int page, count;
    private final LruCache<String, Bitmap> thumbs = new LruCache<>(40);

    // zoom (slide coordinates 0…1; zoom 1 = whole slide)
    private float zoom = 1f, zcx = 0.5f, zcy = 0.5f;
    private float pageW = 960, pageH = 540;
    private int tool = LASER, penColor = PEN_COLORS[0], blankMode, strokeN;
    private String strokeId;
    private final List<Float> penX = new ArrayList<>(), penY = new ArrayList<>();
    private long lastPen, lastPointer, lastZoom;
    private final LinkedHashMap<String, Object[]> ink = new LinkedHashMap<>();     // id → {colour, List<float[]>}

    private LinearLayout root, slideBox, controls;
    private FrameLayout stage;
    private ImageView cur, next;
    private Ink overlay;
    private TextView counter, timer, status, titleT, zoomT;
    private LinearLayout strip, penRow;
    private Button blankBtn;
    private final Button[] toolBtns = new Button[4];
    private long started, pausedAt, pausedTotal;
    private boolean timerPaused;
    private ScaleGestureDetector pinch;
    private GestureDetector taps;
    private boolean multi;
    private float lastMidX, lastMidY;

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    @Override
    protected void onCreate(Bundle b) {
        Ui.apply(this);
        super.onCreate(b);
        ip = getIntent().getStringExtra(EXTRA_IP);
        if (ip == null || ip.isEmpty()) ip = Store.getStr("last_tv", "");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B0B0F);
        root.setPadding(dp(10), dp(10), dp(10), dp(8));

        // top: back · title / status · timer · rotation · End
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(UiKit.darkButton(this, "arrow-left", 38, false, v -> finish()));
        LinearLayout tc = new LinearLayout(this);
        tc.setOrientation(LinearLayout.VERTICAL);
        tc.setPadding(dp(8), 0, 0, 0);
        titleT = Ui.text(this, 15, Color.WHITE, true);
        titleT.setText(T.t("Present"));
        titleT.setSingleLine(true);
        titleT.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status = Ui.text(this, 11, 0xB3FFFFFF, false);
        status.setSingleLine(true);
        tc.addView(titleT);
        tc.addView(status);
        top.addView(tc, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        timer = Ui.text(this, 17, 0xFFFBBF24, true);
        timer.setPadding(dp(6), 0, dp(6), 0);
        timer.setContentDescription(T.t("Timer — tap to pause, hold to reset"));
        timer.setOnClickListener(v -> {
            if (timerPaused) {
                pausedTotal += SystemClock.elapsedRealtime() - pausedAt;
                timerPaused = false;
            } else {
                pausedAt = SystemClock.elapsedRealtime();
                timerPaused = true;
            }
        });
        timer.setOnLongClickListener(v -> {
            started = SystemClock.elapsedRealtime();
            pausedTotal = 0;
            timerPaused = false;
            return true;
        });
        top.addView(timer);
        top.addView(Orient.button(this, "present_orient"));
        Button end = small(T.t("End"), v -> {
            send("end", "");
            finish();
        });
        end.setBackground(Ui.card(this, 0xFFDC2626, 0, 14));
        top.addView(end, new LinearLayout.LayoutParams(dp(64), dp(38)));
        root.addView(top);

        // the slide (gestures) with ink overlay
        slideBox = new LinearLayout(this);
        slideBox.setOrientation(LinearLayout.VERTICAL);
        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(Ui.card(this, 0xFF16161C, 0x33FFFFFF, 14));
        cur = new ImageView(this);
        cur.setScaleType(ImageView.ScaleType.FIT_XY);
        frame.addView(cur, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay = new Ink(this);
        frame.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        frame.setContentDescription(T.t("Current slide — pinch to zoom, use the tool below with one finger"));
        frame.setOnTouchListener(this::touch);
        frame.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> {
            if (r - l != or - ol || bt - t != ob - ot) refresh();
        });
        slideBox.addView(frame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        zoomT = Ui.text(this, 11, 0x99FFFFFF, false);
        zoomT.setGravity(Gravity.CENTER);
        zoomT.setPadding(0, dp(3), 0, 0);
        slideBox.addView(zoomT);

        // controls
        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        LinearLayout tools = new LinearLayout(this);
        String[][] tn = {{"🔴", "Laser"}, {"✏", "Pen"}, {"🔦", "Spotlight"}, {"✋", "Move"}};
        for (int i = 0; i < 4; i++) {
            final int k = i;
            toolBtns[i] = small(tn[i][0] + " " + T.t(tn[i][1]), v -> setTool(k));
            tools.addView(toolBtns[i], new LinearLayout.LayoutParams(0, dp(40), 1));
        }
        controls.addView(tools);
        penRow = new LinearLayout(this);
        penRow.setGravity(Gravity.CENTER_VERTICAL);
        for (final int col : PEN_COLORS) {
            View dot = new View(this);
            dot.setBackground(Ui.card(this, col, 0x66FFFFFF, 14));
            dot.setOnClickListener(v -> {
                penColor = col;
                paintPen();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(30), dp(30));
            lp.setMargins(dp(6), dp(4), dp(6), dp(4));
            penRow.addView(dot, lp);
        }
        penRow.addView(small("🧹 " + T.t("Clear ink"), v -> {
            ink.clear();
            overlay.invalidate();
            send("pen_clear", "");
        }), new LinearLayout.LayoutParams(0, dp(36), 1));
        controls.addView(penRow);

        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setPadding(0, dp(4), 0, dp(4));
        nav.addView(small("◀  " + T.t("Previous"), v -> go(page - 1)), new LinearLayout.LayoutParams(0, dp(58), 1));
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setGravity(Gravity.CENTER);
        next = new ImageView(this);
        next.setScaleType(ImageView.ScaleType.FIT_CENTER);
        next.setBackground(Ui.card(this, 0xFF16161C, 0, 8));
        next.setContentDescription(T.t("Next slide"));
        mid.addView(next, new LinearLayout.LayoutParams(dp(88), dp(50)));
        counter = Ui.text(this, 12, Color.WHITE, true);
        mid.addView(counter);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ml.setMargins(dp(6), 0, dp(6), 0);
        nav.addView(mid, ml);
        Button nx = small(T.t("Next") + "  ▶", v -> go(page + 1));
        nx.setBackground(Ui.gradient(this, 14));
        nav.addView(nx, new LinearLayout.LayoutParams(0, dp(58), 1));
        controls.addView(nav);

        LinearLayout row2 = new LinearLayout(this);
        row2.addView(small("−", v -> setZoom(zoom / 1.5f, zcx, zcy)), new LinearLayout.LayoutParams(0, dp(40), 0.6f));
        row2.addView(small(T.t("Fit"), v -> setZoom(1f, 0.5f, 0.5f)), new LinearLayout.LayoutParams(0, dp(40), 0.8f));
        row2.addView(small("＋", v -> setZoom(zoom * 1.5f, zcx, zcy)), new LinearLayout.LayoutParams(0, dp(40), 0.6f));
        blankBtn = small("", v -> {
            blankMode = (blankMode + 1) % 3;
            send("blank", String.valueOf(blankMode));
            paintBlank();
        });
        row2.addView(blankBtn, new LinearLayout.LayoutParams(0, dp(40), 1.2f));
        row2.addView(small("▦ " + T.t("All slides"), v -> overview()), new LinearLayout.LayoutParams(0, dp(40), 1.2f));
        row2.addView(small("#", v -> goToNumber()), new LinearLayout.LayoutParams(0, dp(40), 0.5f));
        controls.addView(row2);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        strip = new LinearLayout(this);
        hs.addView(strip);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62));
        sl.setMargins(0, dp(4), 0, 0);
        controls.addView(hs, sl);

        stage = new FrameLayout(this);
        root.addView(stage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        arrange();
        setContentView(root);
        Orient.apply(this, "present_orient");
        setTool(LASER);
        paintBlank();

        pinch = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                float[] f = toSlide(d.getFocusX(), d.getFocusY());
                float nz = Math.max(1f, Math.min(8f, zoom * d.getScaleFactor()));
                float k = 1f - zoom / nz;              // keep the point under the fingers in place
                setZoom(nz, zcx + (f[0] - zcx) * k, zcy + (f[1] - zcy) * k);
                return true;
            }
        });
        taps = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (zoom > 1.01f) setZoom(1f, 0.5f, 0.5f);
                else {
                    float[] f = toSlide(e.getX(), e.getY());
                    setZoom(2.5f, f[0], f[1]);
                }
                return true;
            }
        });
        pick();
    }

    /** Portrait: slide over controls. Landscape: slide left, controls right. */
    private void arrange() {
        for (View v : new View[]{slideBox, controls}) if (v.getParent() != null) ((ViewGroup) v.getParent()).removeView(v);
        stage.removeAllViews();
        ScrollView cs = new ScrollView(this);
        cs.addView(controls);
        if (Orient.landscape(this)) {
            LinearLayout row = new LinearLayout(this);
            row.addView(slideBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.6f));
            cs.setPadding(dp(8), 0, 0, 0);
            row.addView(cs, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            stage.addView(row);
        } else {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
            sp.setMargins(0, dp(8), 0, dp(4));
            col.addView(slideBox, sp);
            col.addView(cs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            stage.addView(col);
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        arrange();
        main.postDelayed(this::refresh, 150);
    }

    private Button small(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(label);
        b.setTextSize(13);
        b.setTextColor(Color.WHITE);
        b.setBackground(Ui.card(this, 0xFF26262E, 0, 14));
        b.setOnClickListener(l);
        b.setPadding(dp(4), 0, dp(4), 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        lp.setMargins(dp(2), dp(2), dp(2), dp(2));
        b.setLayoutParams(lp);
        return b;
    }

    private void setTool(int t) {
        tool = t;
        for (int i = 0; i < 4; i++) toolBtns[i].setBackground(i == t ? Ui.card(this, 0xFF0E7490, 0, 14) : Ui.card(this, 0xFF26262E, 0, 14));
        penRow.setVisibility(t == PEN ? View.VISIBLE : View.GONE);
        paintPen();
    }

    private void paintPen() {
        for (int i = 0; i < PEN_COLORS.length; i++) {
            View d = penRow.getChildAt(i);
            d.setScaleX(PEN_COLORS[i] == penColor ? 1.25f : 1f);
            d.setScaleY(PEN_COLORS[i] == penColor ? 1.25f : 1f);
        }
    }

    private void paintBlank() {
        String[] n = {"⬛ " + T.t("Blank"), "⬛ " + T.t("Black"), "⬜ " + T.t("White")};
        blankBtn.setText(n[blankMode]);
        blankBtn.setBackground(blankMode == 0 ? Ui.card(this, 0xFF26262E, 0, 14) : Ui.card(this, blankMode == 2 ? 0xFFE5E7EB : 0xFF000000, 0xFFF59E0B, 14));
        blankBtn.setTextColor(blankMode == 2 ? Color.BLACK : Color.WHITE);
    }

    // ------------------------------------------------------------------ choosing and sending the PDF
    private void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/pdf");
        startActivityForResult(i, 91);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != 91) return;
        if (res != RESULT_OK || data == null || data.getData() == null) {
            if (renderer == null) {
                UiKit.makeToast(this, T.t("Choose a PDF. PowerPoint / Google Slides: Share or Save as PDF first."),
                        android.widget.Toast.LENGTH_LONG).show();
                finish();
            }
            return;
        }
        final Uri u = data.getData();
        try {
            closePdf();
            pfd = getContentResolver().openFileDescriptor(u, "r");
            renderer = new PdfRenderer(pfd);
            count = renderer.getPageCount();
        } catch (Exception e) {
            UiKit.makeToast(this, T.t("Can't open this PDF") + ": " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        String n = LocalShare.displayName(this, u);
        title = n == null ? "Presentation" : n.replaceAll("(?i)\\.pdf$", "");
        titleT.setText(title);
        thumbs.evictAll();
        page = 0;
        zoom = 1f;
        zcx = zcy = 0.5f;
        ink.clear();
        started = SystemClock.elapsedRealtime();
        pausedTotal = 0;
        buildStrip();
        refresh();
        main.removeCallbacks(tick);
        main.post(tick);
        status.setText(T.t("Opening on the TV…"));
        final String shareUrl = LocalShare.share(this, u, title + ".pdf");
        net.execute(() -> {
            try {
                HashMap<String, String> p = new HashMap<>();
                p.put("url", shareUrl);
                p.put("title", title);
                org.json.JSONObject r = TvClient.post(ip, "/present", p);
                final String m = r.optBoolean("ok", true) ? T.t("On the TV — ") + count + " " + T.t("slides") : r.optString("msg");
                main.post(() -> status.setText(m));
            } catch (Exception e) {
                main.post(() -> status.setText("⚠ " + T.t("Cannot reach the TV")));
            }
        });
    }

    // ------------------------------------------------------------------ the slide on the phone (same zoom as the TV)
    /** page→preview transform: {scale, offsetX, offsetY}. */
    private float[] transform(int W, int H) {
        float S = Math.min(W / pageW, H / pageH) * zoom;
        float cx = zcx * pageW * S, cy = zcy * pageH * S;
        float ox = pageW * S <= W ? (W - pageW * S) / 2 : Math.max(W - pageW * S, Math.min(0, W / 2f - cx));
        float oy = pageH * S <= H ? (H - pageH * S) / 2 : Math.max(H - pageH * S, Math.min(0, H / 2f - cy));
        return new float[]{S, ox, oy};
    }

    float[] toScreen(float fx, float fy) {
        float[] t = transform(cur.getWidth(), cur.getHeight());
        return new float[]{t[1] + fx * pageW * t[0], t[2] + fy * pageH * t[0]};
    }

    float[] toSlide(float x, float y) {
        float[] t = transform(cur.getWidth(), cur.getHeight());
        return new float[]{Math.max(0, Math.min(1, (x - t[1]) / (pageW * t[0]))), Math.max(0, Math.min(1, (y - t[2]) / (pageH * t[0])))};
    }

    private volatile int drawGen;

    /** Draws the current view of the slide (zoomed part sharp) and the next-slide preview. */
    private void refresh() {
        if (renderer == null) return;
        final int W = cur.getWidth(), H = cur.getHeight();
        if (W <= 0 || H <= 0) {
            main.postDelayed(this::refresh, 100);
            return;
        }
        final int i = page, gen = ++drawGen;
        final float z = zoom, cx = zcx, cy = zcy;
        draw.execute(() -> {
            if (gen != drawGen) return;
            Bitmap bm;
            synchronized (this) {
                if (renderer == null) return;
                PdfRenderer.Page p = renderer.openPage(i);
                pageW = p.getWidth();
                pageH = p.getHeight();
                float[] t = transform(W, H);
                bm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
                bm.eraseColor(0xFF16161C);
                Canvas c = new Canvas(bm);
                Paint white = new Paint();
                white.setColor(Color.WHITE);
                c.drawRect(t[1], t[2], t[1] + pageW * t[0], t[2] + pageH * t[0], white);
                Matrix m = new Matrix();
                m.setScale(t[0], t[0]);
                m.postTranslate(t[1], t[2]);
                p.render(bm, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                p.close();
            }
            final Bitmap nb = i + 1 < count ? thumb(i + 1) : null;
            main.post(() -> {
                if (gen != drawGen) return;
                cur.setImageBitmap(bm);
                next.setImageBitmap(nb);
                overlay.invalidate();
                counter.setText((i + 1) + " / " + count);
                zoomT.setText(z > 1.01f ? String.format(Locale.US, "%.1f×  ·  %s", z, T.t("double-tap = whole slide"))
                        : T.t("Pinch to zoom · double-tap to zoom in"));
                for (int k = 0; k < strip.getChildCount(); k++) {
                    strip.getChildAt(k).setAlpha(k == i ? 1f : 0.5f);
                }
            });
        });
    }

    private Bitmap thumb(int i) {
        String k = String.valueOf(i);
        Bitmap c = thumbs.get(k);
        if (c != null || renderer == null || i < 0 || i >= count) return c;
        synchronized (this) {
            if (renderer == null) return null;
            PdfRenderer.Page p = renderer.openPage(i);
            float s = 200f / p.getWidth();
            Bitmap bm = Bitmap.createBitmap(200, Math.max(1, Math.round(p.getHeight() * s)), Bitmap.Config.ARGB_8888);
            bm.eraseColor(Color.WHITE);
            p.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            p.close();
            thumbs.put(k, bm);
            return bm;
        }
    }

    private void buildStrip() {
        strip.removeAllViews();
        for (int i = 0; i < count && i < 300; i++) {
            final int k = i;
            ImageView t = new ImageView(this);
            t.setScaleType(ImageView.ScaleType.FIT_CENTER);
            t.setBackground(Ui.card(this, 0xFF26262E, 0, 6));
            t.setContentDescription(T.t("Slide") + " " + (i + 1));
            t.setOnClickListener(v -> go(k));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(96), dp(56));
            lp.setMargins(dp(3), 0, dp(3), 0);
            strip.addView(t, lp);
            draw.execute(() -> {
                final Bitmap bm = thumb(k);
                main.post(() -> t.setImageBitmap(bm));
            });
        }
    }

    private void go(int i) {
        if (count == 0) return;
        i = Math.max(0, Math.min(count - 1, i));
        if (i == page && i != 0) return;
        page = i;
        zoom = 1f;                     // a new slide starts whole and without ink (same on the TV)
        zcx = zcy = 0.5f;
        ink.clear();
        refresh();
        send("goto", String.valueOf(i));
        if (strip.getChildCount() > i) {
            View c = strip.getChildAt(i);
            ((HorizontalScrollView) strip.getParent()).smoothScrollTo(Math.max(0, c.getLeft() - dp(100)), 0);
        }
    }

    private void setZoom(float z, float cx, float cy) {
        zoom = Math.max(1f, Math.min(8f, z));
        zcx = Math.max(0f, Math.min(1f, cx));
        zcy = Math.max(0f, Math.min(1f, cy));
        if (zoom <= 1.01f) {
            zoom = 1f;
            zcx = zcy = 0.5f;
        }
        refresh();
        long now = SystemClock.uptimeMillis();
        if (now - lastZoom > 60) {                      // the TV re-draws the zoomed part sharp
            lastZoom = now;
            sendZoom();
        } else {
            main.removeCallbacks(zoomLater);
            main.postDelayed(zoomLater, 80);
        }
    }

    private final Runnable zoomLater = this::sendZoom;

    private void sendZoom() {
        send("zoom", String.format(Locale.US, "%.3f,%.4f,%.4f", zoom, zcx, zcy));
    }

    // ------------------------------------------------------------------ gestures on the slide
    private boolean touch(View v, MotionEvent e) {
        pinch.onTouchEvent(e);
        taps.onTouchEvent(e);
        int a = e.getActionMasked();
        if (e.getPointerCount() >= 2) {                  // two fingers: zoom (pinch) and pan
            float mx = (e.getX(0) + e.getX(1)) / 2, my = (e.getY(0) + e.getY(1)) / 2;
            if (!multi || a == MotionEvent.ACTION_POINTER_DOWN) {
                endOneFinger();
                multi = true;
            } else if (a == MotionEvent.ACTION_MOVE && zoom > 1f) {
                float[] t = transform(cur.getWidth(), cur.getHeight());
                setZoom(zoom, zcx - (mx - lastMidX) / (pageW * t[0]), zcy - (my - lastMidY) / (pageH * t[0]));
            }
            lastMidX = mx;
            lastMidY = my;
            return true;
        }
        if (multi) {
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) multi = false;
            return true;
        }
        float[] f = toSlide(e.getX(), e.getY());
        switch (tool) {
            case PEN: pen(a, f); break;
            case SPOT: pointer("spot", a, f); break;
            case MOVE:
                if (a == MotionEvent.ACTION_DOWN) {
                    lastMidX = e.getX();
                    lastMidY = e.getY();
                } else if (a == MotionEvent.ACTION_MOVE && zoom > 1f) {
                    float[] t = transform(cur.getWidth(), cur.getHeight());
                    setZoom(zoom, zcx - (e.getX() - lastMidX) / (pageW * t[0]), zcy - (e.getY() - lastMidY) / (pageH * t[0]));
                    lastMidX = e.getX();
                    lastMidY = e.getY();
                }
                break;
            default: pointer("pointer", a, f);
        }
        return true;
    }

    private void endOneFinger() {
        if (tool == PEN) flushPen();
        else if (tool == SPOT) send("spot_off", "");
        else if (tool == LASER) send("pointer_off", "");
        overlay.laser = null;
        overlay.spot = null;
        overlay.invalidate();
    }

    /** Laser / spotlight: follows the finger (~25 updates a second), hidden on release. */
    private void pointer(final String cmd, int a, float[] f) {
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            send(cmd + "_off", "");
            if ("spot".equals(cmd)) overlay.spot = null;
            else overlay.laser = null;
            overlay.invalidate();
            return;
        }
        if ("spot".equals(cmd)) overlay.spot = f;
        else overlay.laser = f;
        overlay.invalidate();
        long now = SystemClock.uptimeMillis();
        if (now - lastPointer < 40 && a == MotionEvent.ACTION_MOVE) return;
        lastPointer = now;
        final float fx = f[0], fy = f[1];
        net.execute(() -> post(cmd, "", String.format(Locale.US, "%.4f", fx), String.format(Locale.US, "%.4f", fy)));
    }

    /** Pen: points are sent in small batches while drawing. */
    private void pen(int a, float[] f) {
        if (a == MotionEvent.ACTION_DOWN) {
            strokeId = "s" + (++strokeN);
            List<float[]> pts = new ArrayList<>();
            ink.put(strokeId, new Object[]{penColor, pts});
            penX.clear();
            penY.clear();
        }
        if (strokeId == null) return;
        Object[] st = ink.get(strokeId);
        if (st == null) return;
        @SuppressWarnings("unchecked") List<float[]> pts = (List<float[]>) st[1];
        pts.add(f);
        penX.add(f[0]);
        penY.add(f[1]);
        overlay.invalidate();
        long now = SystemClock.uptimeMillis();
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL || now - lastPen > 50) {
            lastPen = now;
            flushPen();
        }
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) strokeId = null;
    }

    private void flushPen() {
        if (strokeId == null || penX.isEmpty()) return;
        StringBuilder xs = new StringBuilder(), ys = new StringBuilder();
        for (int k = 0; k < penX.size(); k++) {
            xs.append(k > 0 ? "," : "").append(String.format(Locale.US, "%.4f", penX.get(k)));
            ys.append(k > 0 ? "," : "").append(String.format(Locale.US, "%.4f", penY.get(k)));
        }
        penX.clear();
        penY.clear();
        final String id = strokeId + "|" + String.format("#%06X", penColor & 0xFFFFFF), x = xs.toString(), y = ys.toString();
        net.execute(() -> post("pen", id, x, y));
    }

    /** Ink, laser and spotlight on the phone's preview (same places as on the big screen). */
    final class Ink extends View {
        float[] laser, spot;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), dark = new Paint();

        Ink(Context c) {
            super(c);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            dark.setColor(0xB3000000);
        }

        @Override
        protected void onDraw(Canvas c) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2.5f) * (float) Math.sqrt(zoom));
            for (Object[] st : ink.values()) {
                @SuppressWarnings("unchecked") List<float[]> pts = (List<float[]>) st[1];
                if (pts.isEmpty()) continue;
                p.setColor((Integer) st[0]);
                Path path = new Path();
                float[] s0 = toScreen(pts.get(0)[0], pts.get(0)[1]);
                path.moveTo(s0[0], s0[1]);
                for (int k = 1; k < pts.size(); k++) {
                    float[] sp = toScreen(pts.get(k)[0], pts.get(k)[1]);
                    path.lineTo(sp[0], sp[1]);
                }
                if (pts.size() == 1) path.lineTo(s0[0] + 0.1f, s0[1]);
                c.drawPath(path, p);
            }
            if (spot != null) {
                float[] sp = toScreen(spot[0], spot[1]);
                Path hole = new Path();
                hole.setFillType(Path.FillType.EVEN_ODD);
                hole.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
                hole.addCircle(sp[0], sp[1], Math.min(getWidth(), getHeight()) * 0.16f, Path.Direction.CW);
                c.drawPath(hole, dark);
            }
            if (laser != null) {
                float[] sp = toScreen(laser[0], laser[1]);
                p.setStyle(Paint.Style.FILL);
                p.setColor(0x88FF2020);
                c.drawCircle(sp[0], sp[1], dp(12), p);
                p.setColor(0xFFFF1A1A);
                c.drawCircle(sp[0], sp[1], dp(5), p);
            }
        }
    }

    // ------------------------------------------------------------------ overview / go to
    private void overview() {
        if (count == 0) return;
        ScrollView sv = new ScrollView(this);
        GridLayout g = new GridLayout(this);
        g.setColumnCount(Orient.landscape(this) ? 4 : 3);
        g.setPadding(dp(8), dp(8), dp(8), dp(8));
        sv.addView(g);
        final AlertDialog d = new UiDialog(this).setTitle(count + " " + T.t("slides")).setView(sv).setNegativeButton(T.t("Close"), null).show();
        for (int i = 0; i < count && i < 300; i++) {
            final int k = i;
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(3), dp(3), dp(3), dp(3));
            cell.setBackground(k == page ? Ui.card(this, 0x330E7490, 0xFF0E7490, 8) : null);
            ImageView t = new ImageView(this);
            t.setScaleType(ImageView.ScaleType.FIT_CENTER);
            cell.addView(t, new LinearLayout.LayoutParams(dp(92), dp(54)));
            TextView n = Ui.text(this, 11, Ui.TEXT, false);
            n.setText(String.valueOf(i + 1));
            cell.addView(n);
            cell.setOnClickListener(v -> {
                d.dismiss();
                go(k);
            });
            g.addView(cell);
            draw.execute(() -> {
                final Bitmap bm = thumb(k);
                main.post(() -> t.setImageBitmap(bm));
            });
        }
    }

    private void goToNumber() {
        final EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setHint("1 – " + count);
        new UiDialog(this).setTitle(T.t("Go to slide")).setView(e).setPositiveButton(T.t("Go"), (d, w) -> {
            try {
                go(Integer.parseInt(e.getText().toString().trim()) - 1);
            } catch (Exception ignored) {
            }
        }).setNegativeButton(T.t("Cancel"), null).show();
    }

    // ------------------------------------------------------------------ talking to the TV / PC
    private void send(final String c, final String v) {
        net.execute(() -> post(c, v, null, null));
    }

    private void post(String c, String v, String x, String y) {
        try {
            HashMap<String, String> p = new HashMap<>();
            p.put("c", c);
            p.put("v", v);
            if (x != null) p.put("x", x);
            if (y != null) p.put("y", y);
            TvClient.post(ip, "/presentcmd", p);
        } catch (Exception ignored) {
        }
    }

    private final Runnable tick = new Runnable() {
        public void run() {
            long now = timerPaused ? pausedAt : SystemClock.elapsedRealtime();
            long s = Math.max(0, (now - started - pausedTotal) / 1000);
            timer.setText(String.format(Locale.US, "%d:%02d%s", s / 60, s % 60, timerPaused ? " ⏸" : ""));
            main.postDelayed(this, 500);
        }
    };

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_VOLUME_DOWN) {
            go(page + 1);
            return true;
        }
        if (code == KeyEvent.KEYCODE_VOLUME_UP) {
            go(page - 1);
            return true;
        }
        return super.onKeyDown(code, e);
    }

    private void closePdf() {
        synchronized (this) {
            try {
                if (renderer != null) renderer.close();
                if (pfd != null) pfd.close();
            } catch (Exception ignored) {
            }
            renderer = null;
            pfd = null;
        }
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacks(tick);
        closePdf();
        net.shutdown();
        draw.shutdown();
        super.onDestroy();
    }
}
