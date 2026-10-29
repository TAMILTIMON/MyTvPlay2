package com.mytv.player;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Phone: presenter for PDF slides — or a whiteboard (no PDF) — on the TV / PC / a TV group.
 * Pinch to zoom (sharp on the big screen), two-finger pan, double-tap; tools: Laser · Pen · Highlighter · Eraser ·
 * Shapes (line, arrow, rectangle, circle, triangle) · Ruler (cm + angle) · Text (math symbols, formulas) · Spotlight · Move;
 * colours, sizes, undo / redo, clear page; each page keeps its own drawing; whiteboard papers and pages.
 */
public class PresentActivity extends AppCompatActivity {
    public static final String EXTRA_IP = "ip", EXTRA_BOARD = "board";
    private static final int LASER = 0, PEN = 1, HL = 2, ERASE = 3, SHAPE = 4, RULER = 5, TEXT = 6, SPOT = 7, MOVE = 8;
    private static final String[][] TOOLS = {{"🔴", "Laser"}, {"✏", "Pen"}, {"🖍", "Highlighter"}, {"🧽", "Eraser"}, {"⬜", "Shapes"},
            {"📏", "Ruler"}, {"T", "Text"}, {"🔦", "Spotlight"}, {"✋", "Move"}};
    private static final int[] COLORS = {0xFFEF4444, 0xFFFACC15, 0xFF22C55E, 0xFF3B82F6, 0xFFFFFFFF, 0xFF111827};
    private static final String[] SHAPES = {"line", "arrow", "rect", "oval", "tri"};
    private static final String[] SHAPE_NAMES = {"Line", "Arrow", "Rectangle", "Circle", "Triangle"};
    private static final String[] SYMBOLS = {"²", "³", "√", "π", "θ", "α", "β", "γ", "Δ", "Σ", "∫", "∞", "±", "×", "÷", "≠", "≈", "≤", "≥",
            "°", "½", "→", "⇒", "∠", "⊥", "∥", "µ", "Ω", "λ"};
    private static final String[] FORMULAS = {"a² + b² = c²", "x = (−b ± √(b² − 4ac)) / 2a", "(a + b)² = a² + 2ab + b²",
            "(a − b)² = a² − 2ab + b²", "a² − b² = (a + b)(a − b)", "A = πr²", "C = 2πr", "Area of triangle = ½ × b × h",
            "Volume of cylinder = πr²h", "y = mx + c", "sin²θ + cos²θ = 1", "tan θ = sin θ / cos θ", "v = u + at", "s = ut + ½at²",
            "v² = u² + 2as", "F = ma", "E = mc²", "P = VI", "V = IR", "ρ = m / V", "mean = Σx / n"};

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final ExecutorService draw = Executors.newSingleThreadExecutor();
    private String ip, title = "";
    private boolean board;
    private String paper = "white";
    private PdfRenderer renderer;
    private ParcelFileDescriptor pfd;
    private int page, count;
    private final LruCache<String, Bitmap> thumbs = new LruCache<>(40);

    private float zoom = 1f, zcx = 0.5f, zcy = 0.5f;
    private float pageW = 960, pageH = 540;
    private int tool = LASER, color = COLORS[0], size = 1, blankMode, idN;
    private String shape = "arrow";

    // ink: page → id → element; undo / redo of {op "add"/"del"/"clear", page, element or list}
    private final HashMap<Integer, LinkedHashMap<String, JSONObject>> ink = new HashMap<>();
    private final List<Object[]> undo = new ArrayList<>(), redo = new ArrayList<>();
    private JSONObject drawing;              // the element under the finger
    private final JSONArray pendingPts = new JSONArray();
    private long lastSend, lastPointer, lastZoom;
    private float downX, downY;

    private LinearLayout root, slideBox, controls, optRow, boardRow;
    private FrameLayout stage;
    private ImageView cur, next;
    private Ink overlay;
    private TextView counter, timer, status, titleT, zoomT;
    private LinearLayout strip;
    private Button blankBtn, sizeBtn, paperBtn;
    private final Button[] toolBtns = new Button[TOOLS.length];
    private long started, pausedAt, pausedTotal;
    private boolean timerPaused, multi;
    private ScaleGestureDetector pinch;
    private GestureDetector taps;
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
        board = getIntent().getBooleanExtra(EXTRA_BOARD, false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B0B0F);
        root.setPadding(dp(10), dp(10), dp(10), dp(8));
        buildTop();
        buildSlide();
        buildControls();
        stage = new FrameLayout(this);
        root.addView(stage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        arrange();
        setContentView(root);
        Orient.apply(this, "present_orient");
        setTool(board ? PEN : LASER);
        paintBlank();
        pinch = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                float[] f = toSlide(d.getFocusX(), d.getFocusY());
                float nz = Math.max(1f, Math.min(8f, zoom * d.getScaleFactor()));
                float k = 1f - zoom / nz;
                setZoom(nz, zcx + (f[0] - zcx) * k, zcy + (f[1] - zcy) * k);
                return true;
            }
        });
        taps = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (tool != LASER && tool != MOVE && tool != SPOT) return false;      // drawing tools: no zoom jumps
                if (zoom > 1.01f) setZoom(1f, 0.5f, 0.5f);
                else {
                    float[] f = toSlide(e.getX(), e.getY());
                    setZoom(2.5f, f[0], f[1]);
                }
                return true;
            }
        });
        if (board) startBoard();
        else pick();
    }

    // ------------------------------------------------------------------ building the screen
    private void buildTop() {
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(UiKit.darkButton(this, "arrow-left", 38, false, v -> finish()));
        LinearLayout tc = new LinearLayout(this);
        tc.setOrientation(LinearLayout.VERTICAL);
        tc.setPadding(dp(8), 0, 0, 0);
        titleT = Ui.text(this, 15, Color.WHITE, true);
        titleT.setText(T.t(board ? "Whiteboard" : "Present"));
        titleT.setSingleLine(true);
        titleT.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status = Ui.text(this, 11, 0xB3FFFFFF, false);
        status.setSingleLine(true);
        tc.addView(titleT);
        tc.addView(status);
        top.addView(tc, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        timer = Ui.text(this, 16, 0xFFFBBF24, true);
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
        top.addView(end, new LinearLayout.LayoutParams(dp(60), dp(38)));
        root.addView(top);
    }

    private void buildSlide() {
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
    }

    private void buildControls() {
        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        // tools (scrolls sideways)
        HorizontalScrollView ts = new HorizontalScrollView(this);
        ts.setHorizontalScrollBarEnabled(false);
        LinearLayout tools = new LinearLayout(this);
        for (int i = 0; i < TOOLS.length; i++) {
            final int k = i;
            toolBtns[i] = small(TOOLS[i][0] + " " + T.t(TOOLS[i][1]), v -> {
                if (k == SHAPE && tool == SHAPE) pickShape();
                setTool(k);
            });
            toolBtns[i].setPadding(dp(10), 0, dp(10), 0);
            tools.addView(toolBtns[i]);
        }
        ts.addView(tools);
        controls.addView(ts);
        // colours · size · undo · redo · clear
        optRow = new LinearLayout(this);
        optRow.setGravity(Gravity.CENTER_VERTICAL);
        for (final int col : COLORS) {
            View dot = new View(this);
            dot.setBackground(Ui.card(this, col, 0x66FFFFFF, 13));
            dot.setContentDescription(T.t("Colour"));
            dot.setOnClickListener(v -> {
                color = col;
                paintOpts();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(26), dp(26));
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            optRow.addView(dot, lp);
        }
        sizeBtn = small("", v -> {
            size = (size + 1) % 3;
            paintOpts();
        });
        optRow.addView(sizeBtn, new LinearLayout.LayoutParams(dp(54), dp(36)));
        optRow.addView(small("↶", v -> undo()), new LinearLayout.LayoutParams(dp(40), dp(36)));
        optRow.addView(small("↷", v -> redo()), new LinearLayout.LayoutParams(dp(40), dp(36)));
        optRow.addView(small("🧹", v -> clearPage()), new LinearLayout.LayoutParams(dp(40), dp(36)));
        HorizontalScrollView os = new HorizontalScrollView(this);
        os.setHorizontalScrollBarEnabled(false);
        os.addView(optRow);
        controls.addView(os);
        // previous · next slide / page
        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setPadding(0, dp(2), 0, dp(2));
        nav.addView(small("◀  " + T.t("Previous"), v -> go(page - 1)), new LinearLayout.LayoutParams(0, dp(52), 1));
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setGravity(Gravity.CENTER);
        next = new ImageView(this);
        next.setScaleType(ImageView.ScaleType.FIT_CENTER);
        next.setBackground(Ui.card(this, 0xFF16161C, 0, 8));
        next.setContentDescription(T.t("Next slide"));
        mid.addView(next, new LinearLayout.LayoutParams(dp(80), dp(45)));
        counter = Ui.text(this, 12, Color.WHITE, true);
        mid.addView(counter);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ml.setMargins(dp(6), 0, dp(6), 0);
        nav.addView(mid, ml);
        Button nx = small(T.t("Next") + "  ▶", v -> {
            if (board && page == count - 1) addPage();          // whiteboard: "next" on the last page = a new page
            else go(page + 1);
        });
        nx.setBackground(Ui.gradient(this, 14));
        nav.addView(nx, new LinearLayout.LayoutParams(0, dp(52), 1));
        controls.addView(nav);
        // zoom · blank · overview · go to
        LinearLayout row2 = new LinearLayout(this);
        row2.addView(small("−", v -> setZoom(zoom / 1.5f, zcx, zcy)), new LinearLayout.LayoutParams(0, dp(38), 0.6f));
        row2.addView(small(T.t("Fit"), v -> setZoom(1f, 0.5f, 0.5f)), new LinearLayout.LayoutParams(0, dp(38), 0.8f));
        row2.addView(small("＋", v -> setZoom(zoom * 1.5f, zcx, zcy)), new LinearLayout.LayoutParams(0, dp(38), 0.6f));
        blankBtn = small("", v -> {
            blankMode = (blankMode + 1) % 3;
            send("blank", String.valueOf(blankMode));
            paintBlank();
        });
        row2.addView(blankBtn, new LinearLayout.LayoutParams(0, dp(38), 1.2f));
        row2.addView(small("▦ " + T.t("All"), v -> overview()), new LinearLayout.LayoutParams(0, dp(38), 0.9f));
        row2.addView(small("#", v -> goToNumber()), new LinearLayout.LayoutParams(0, dp(38), 0.5f));
        controls.addView(row2);
        // whiteboard: paper · add page · delete page
        boardRow = new LinearLayout(this);
        paperBtn = small("", v -> pickPaper());
        boardRow.addView(paperBtn, new LinearLayout.LayoutParams(0, dp(38), 1.4f));
        boardRow.addView(small("＋ " + T.t("Page"), v -> addPage()), new LinearLayout.LayoutParams(0, dp(38), 1f));
        boardRow.addView(small("🗑 " + T.t("Page"), v -> deletePage()), new LinearLayout.LayoutParams(0, dp(38), 1f));
        boardRow.setVisibility(board ? View.VISIBLE : View.GONE);
        controls.addView(boardRow);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        strip = new LinearLayout(this);
        hs.addView(strip);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        sl.setMargins(0, dp(4), 0, 0);
        controls.addView(hs, sl);
    }

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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
        lp.setMargins(dp(2), dp(2), dp(2), dp(2));
        b.setLayoutParams(lp);
        return b;
    }

    private void setTool(int t) {
        tool = t;
        for (int i = 0; i < toolBtns.length; i++)
            toolBtns[i].setBackground(i == t ? Ui.card(this, 0xFF0E7490, 0, 14) : Ui.card(this, 0xFF26262E, 0, 14));
        toolBtns[SHAPE].setText(t == SHAPE ? "⬜ " + T.t(SHAPE_NAMES[java.util.Arrays.asList(SHAPES).indexOf(shape)]) + " ▾" : "⬜ " + T.t("Shapes"));
        boolean drawing = t == PEN || t == HL || t == SHAPE || t == RULER || t == TEXT || t == ERASE;
        ((View) optRow.getParent()).setVisibility(drawing || board ? View.VISIBLE : View.GONE);
        paintOpts();
    }

    private void pickShape() {
        new UiDialog(this).setTitle(T.t("Shapes")).setItems(tr(SHAPE_NAMES), (d, w) -> {
            shape = SHAPES[w];
            setTool(SHAPE);
        }).show();
    }

    private void paintOpts() {
        for (int i = 0; i < COLORS.length; i++) {
            View d = optRow.getChildAt(i);
            d.setScaleX(COLORS[i] == color ? 1.3f : 1f);
            d.setScaleY(COLORS[i] == color ? 1.3f : 1f);
        }
        sizeBtn.setText(new String[]{"• S", "● M", "⬤ L"}[size]);
        if (paperBtn != null) paperBtn.setText("📄 " + T.t(InkDraw.PAPER_NAMES[Math.max(0, java.util.Arrays.asList(InkDraw.PAPERS).indexOf(paper))]));
    }

    private void paintBlank() {
        String[] n = {"⬛ " + T.t("Blank"), "⬛ " + T.t("Black"), "⬜ " + T.t("White")};
        blankBtn.setText(n[blankMode]);
        blankBtn.setBackground(blankMode == 0 ? Ui.card(this, 0xFF26262E, 0, 14) : Ui.card(this, blankMode == 2 ? 0xFFE5E7EB : 0xFF000000, 0xFFF59E0B, 14));
        blankBtn.setTextColor(blankMode == 2 ? Color.BLACK : Color.WHITE);
    }

    private static String[] tr(String[] a) {
        String[] o = new String[a.length];
        for (int i = 0; i < a.length; i++) o[i] = T.t(a[i]);
        return o;
    }

    // ------------------------------------------------------------------ whiteboard
    private void startBoard() {
        paper = Store.getStr("wb_paper", "white");
        if ("chalk".equals(paper)) color = COLORS[4];
        else color = COLORS[5];
        pageW = 960;
        pageH = 540;
        count = 1;
        page = 0;
        title = T.t("Whiteboard");
        started = SystemClock.elapsedRealtime();
        buildStrip();
        refresh();
        main.post(tick);
        paintOpts();
        if (ip == null || ip.isEmpty()) {
            status.setText(T.t("Not connected — drawing on this phone only"));
            return;
        }
        status.setText(T.t("Opening on the TV…"));
        net.execute(() -> {
            try {
                HashMap<String, String> p = new HashMap<>();
                p.put("url", "zhboard:" + paper);
                p.put("title", title);
                JSONObject r = TvClient.post(ip, "/present", p);
                String m = r.optBoolean("ok", true) ? T.t("Whiteboard on the TV") + (TvGroup.on() ? " · " + TvGroup.label() : "") : r.optString("msg");
                try {
                    Thread.sleep(1200);
                    JSONObject st = TvClient.post(ip, "/presentstate", new HashMap<String, String>());
                    if (!st.has("ink")) m = "⚠ " + T.t("Update ZH Play on the TV for the whiteboard");
                } catch (Exception ignored) {
                }
                final String m2 = m;
                main.post(() -> status.setText(m2));
            } catch (Exception e) {
                main.post(() -> status.setText("⚠ " + T.t("Cannot reach the TV") + " — " + T.t("drawing on this phone only")));
            }
        });
    }

    private void pickPaper() {
        new UiDialog(this).setTitle(T.t("Paper")).setItems(tr(InkDraw.PAPER_NAMES), (d, w) -> {
            paper = InkDraw.PAPERS[w];
            Store.putStr("wb_paper", paper);
            if ("chalk".equals(paper) && color == COLORS[5]) color = COLORS[4];
            if (!"chalk".equals(paper) && color == COLORS[4]) color = COLORS[5];
            send("paper", paper);
            thumbs.evictAll();
            buildStrip();
            paintOpts();
            refresh();
        }).show();
    }

    private void addPage() {
        count++;
        send("pages", String.valueOf(count));
        buildStrip();
        go(count - 1);
    }

    private void deletePage() {
        if (count <= 1) {
            clearPage();
            return;
        }
        new UiDialog(this).setTitle(T.t("Delete this page?")).setPositiveButton(T.t("Delete"), (d, w) -> {
            // move the later pages' drawings one page up, on the phone and on the TV
            for (int pg = page; pg < count; pg++) {
                send("clearpg", String.valueOf(pg));
                LinkedHashMap<String, JSONObject> nextInk = ink.remove(pg + 1);
                if (nextInk != null) {
                    ink.put(pg, nextInk);
                    for (JSONObject e : nextInk.values()) {
                        try {
                            e.put("pg", pg);
                        } catch (Exception ignored) {
                        }
                        sendEl(e, false);
                    }
                } else ink.remove(pg);
            }
            count--;
            undo.clear();
            redo.clear();
            send("pages", String.valueOf(count));
            buildStrip();
            go(Math.min(page, count - 1), true);
        }).setNegativeButton(T.t("Cancel"), null).show();
    }

    // ------------------------------------------------------------------ PDF
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
        undo.clear();
        redo.clear();
        started = SystemClock.elapsedRealtime();
        pausedTotal = 0;
        buildStrip();
        refresh();
        main.removeCallbacks(tick);
        main.post(tick);
        if (ip == null || ip.isEmpty()) {
            status.setText(T.t("Not connected — on this phone only"));
            return;
        }
        status.setText(T.t("Opening on the TV…"));
        final String shareUrl = LocalShare.share(this, u, title + ".pdf");
        net.execute(() -> {
            try {
                HashMap<String, String> p = new HashMap<>();
                p.put("url", shareUrl);
                p.put("title", title);
                JSONObject r = TvClient.post(ip, "/present", p);
                String m = r.optBoolean("ok", true) ? T.t("On the TV — ") + count + " " + T.t("slides") + (TvGroup.on() ? " · " + TvGroup.label() : "")
                        : r.optString("msg");
                try {
                    Thread.sleep(1500);
                    JSONObject st = TvClient.post(ip, "/presentstate", new HashMap<String, String>());
                    if (!st.has("zoom")) m = "⚠ " + T.t("Update ZH Play on the TV for zoom, pen and spotlight");
                } catch (Exception ignored) {
                }
                final String m2 = m;
                main.post(() -> status.setText(m2));
            } catch (Exception e) {
                main.post(() -> status.setText("⚠ " + T.t("Cannot reach the TV")));
            }
        });
    }

    // ------------------------------------------------------------------ the page on the phone (same zoom as the TV)
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

    private void refresh() {
        if (renderer == null && !board) return;
        final int W = cur.getWidth(), H = cur.getHeight();
        if (W <= 0 || H <= 0) {
            main.postDelayed(this::refresh, 100);
            return;
        }
        final int i = page, gen = ++drawGen;
        final float z = zoom;
        draw.execute(() -> {
            if (gen != drawGen) return;
            Bitmap bm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
            bm.eraseColor(0xFF16161C);
            Canvas c = new Canvas(bm);
            if (board) {
                float[] t = transform(W, H);
                InkDraw.paper(c, paper, new RectF(t[1], t[2], t[1] + pageW * t[0], t[2] + pageH * t[0]), pageH * t[0]);
            } else {
                synchronized (this) {
                    if (renderer == null) return;
                    PdfRenderer.Page p = renderer.openPage(i);
                    pageW = p.getWidth();
                    pageH = p.getHeight();
                    float[] t = transform(W, H);
                    Paint white = new Paint();
                    white.setColor(Color.WHITE);
                    c.drawRect(t[1], t[2], t[1] + pageW * t[0], t[2] + pageH * t[0], white);
                    Matrix m = new Matrix();
                    m.setScale(t[0], t[0]);
                    m.postTranslate(t[1], t[2]);
                    p.render(bm, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    p.close();
                }
            }
            final Bitmap nb = i + 1 < count ? thumb(i + 1) : null;
            main.post(() -> {
                if (gen != drawGen) return;
                cur.setImageBitmap(bm);
                next.setImageBitmap(nb);
                overlay.invalidate();
                counter.setText((i + 1) + " / " + count);
                zoomT.setText(z > 1.01f ? String.format(Locale.US, "%.1f×  ·  %s", z, T.t("Fit = whole page"))
                        : T.t("Pinch to zoom · two fingers move the page"));
                for (int k = 0; k < strip.getChildCount(); k++) strip.getChildAt(k).setAlpha(k == i ? 1f : 0.5f);
            });
        });
    }

    private Bitmap thumb(int i) {
        String k = i + "|" + paper;
        Bitmap c = thumbs.get(k);
        if (c != null || i < 0 || i >= count) return c;
        if (board) {
            Bitmap bm = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(bm);
            InkDraw.paper(cv, paper, new RectF(0, 0, 160, 90), 90);
            LinkedHashMap<String, JSONObject> m = ink.get(i);
            if (m != null) for (JSONObject e : new ArrayList<>(m.values())) InkDraw.draw(cv, e, (fx, fy) -> new float[]{fx * 160, fy * 90}, 90);
            return bm;                                   // drawings change: not cached
        }
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
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(88), dp(50));
            lp.setMargins(dp(3), 0, dp(3), 0);
            strip.addView(t, lp);
            draw.execute(() -> {
                final Bitmap bm = thumb(k);
                main.post(() -> t.setImageBitmap(bm));
            });
        }
    }

    private void go(int i) {
        go(i, false);
    }

    private void go(int i, boolean force) {
        if (count == 0) return;
        i = Math.max(0, Math.min(count - 1, i));
        if (i == page && !force && i != 0) return;
        if (board && i != page && page >= 0 && page < strip.getChildCount()) {    // the page we leave: fresh thumbnail
            final int old = page;
            draw.execute(() -> {
                final Bitmap bm = thumb(old);
                main.post(() -> {
                    if (old < strip.getChildCount()) ((ImageView) strip.getChildAt(old)).setImageBitmap(bm);
                });
            });
        }
        page = i;
        zoom = 1f;                             // each page keeps its drawing; the view starts whole
        zcx = zcy = 0.5f;
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
        if (now - lastZoom > 60) {
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

    // ------------------------------------------------------------------ gestures
    private boolean touch(View v, MotionEvent e) {
        pinch.onTouchEvent(e);
        taps.onTouchEvent(e);
        int a = e.getActionMasked();
        if (e.getPointerCount() >= 2) {                       // two fingers: zoom and move the page (any tool)
            float mx = (e.getX(0) + e.getX(1)) / 2, my = (e.getY(0) + e.getY(1)) / 2;
            if (!multi || a == MotionEvent.ACTION_POINTER_DOWN) {
                cancelOneFinger();
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
            case PEN:
            case HL: stroke(a, f); break;
            case SHAPE:
            case RULER: shape(a, f); break;
            case ERASE: erase(f); break;
            case TEXT:
                if (a == MotionEvent.ACTION_DOWN) {
                    downX = e.getX();
                    downY = e.getY();
                } else if (a == MotionEvent.ACTION_UP && Math.hypot(e.getX() - downX, e.getY() - downY) < dp(12)) textAt(f);
                break;
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

    /** A second finger came down: the one-finger action stops (a stroke in progress is removed). */
    private void cancelOneFinger() {
        if (drawing != null) {
            removeEl(page, drawing.optString("id"), false);
            drawing = null;
        }
        if (tool == SPOT) send("spot_off", "");
        if (tool == LASER) send("pointer_off", "");
        overlay.laser = null;
        overlay.spot = null;
        overlay.invalidate();
    }

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

    // ------------------------------------------------------------------ ink elements
    private int width() {
        if (tool == HL) return new int[]{18, 28, 40}[size];
        return new int[]{3, 6, 11}[size];
    }

    private JSONObject newEl(String t) {
        JSONObject e = new JSONObject();
        try {
            e.put("id", "e" + (++idN) + "_" + (SystemClock.uptimeMillis() % 100000));
            e.put("pg", page);
            e.put("t", t);
            e.put("c", String.format("#%06X", color & 0xFFFFFF));
            e.put("w", width());
            e.put("p", new JSONArray());
        } catch (Exception ignored) {
        }
        return e;
    }

    private LinkedHashMap<String, JSONObject> pageInk(int pg) {
        LinkedHashMap<String, JSONObject> m = ink.get(pg);
        if (m == null) {
            m = new LinkedHashMap<>();
            ink.put(pg, m);
        }
        return m;
    }

    private void stroke(int a, float[] f) {
        try {
            if (a == MotionEvent.ACTION_DOWN) {
                drawing = newEl(tool == HL ? "hl" : "pen");
                drawing.getJSONArray("p").put(r4(f[0])).put(r4(f[1]));
                pageInk(page).put(drawing.getString("id"), drawing);
                sendEl(drawing, false);
                lastSend = SystemClock.uptimeMillis();
            } else if (drawing != null && (a == MotionEvent.ACTION_MOVE || a == MotionEvent.ACTION_UP)) {
                drawing.getJSONArray("p").put(r4(f[0])).put(r4(f[1]));
                pendingPts.put(r4(f[0])).put(r4(f[1]));
                long now = SystemClock.uptimeMillis();
                if (now - lastSend > 50 || a == MotionEvent.ACTION_UP) {     // points in small batches
                    lastSend = now;
                    JSONObject app = new JSONObject().put("id", drawing.getString("id")).put("pg", page).put("app", true)
                            .put("p", new JSONArray(pendingPts.toString()));
                    while (pendingPts.length() > 0) pendingPts.remove(0);
                    sendEl(app, false);
                }
                if (a == MotionEvent.ACTION_UP) {
                    pushUndo("add", page, drawing);
                    drawing = null;
                }
            }
        } catch (Exception ignored) {
        }
        overlay.invalidate();
    }

    private void shape(int a, float[] f) {
        try {
            if (a == MotionEvent.ACTION_DOWN) {
                drawing = newEl(tool == RULER ? "ruler" : shape);
                drawing.put("p", new JSONArray().put(r4(f[0])).put(r4(f[1])).put(r4(f[0])).put(r4(f[1])));
                pageInk(page).put(drawing.getString("id"), drawing);
            } else if (drawing != null) {
                JSONArray p = drawing.getJSONArray("p");
                p.put(2, r4(f[0]));
                p.put(3, r4(f[1]));
                long now = SystemClock.uptimeMillis();
                if (now - lastSend > 60 || a == MotionEvent.ACTION_UP) {     // the shape follows the finger on the TV
                    lastSend = now;
                    sendEl(drawing, false);
                }
                if (a == MotionEvent.ACTION_UP) {
                    boolean tiny = Math.hypot(p.getDouble(2) - p.getDouble(0), p.getDouble(3) - p.getDouble(1)) < 0.01;
                    if (tiny) removeEl(page, drawing.getString("id"), false);
                    else pushUndo("add", page, drawing);
                    drawing = null;
                }
            }
        } catch (Exception ignored) {
        }
        overlay.invalidate();
    }

    private void erase(float[] f) {
        LinkedHashMap<String, JSONObject> m = ink.get(page);
        if (m == null) return;
        String hit = null;
        for (JSONObject e : m.values()) if (InkDraw.distance(e, f[0], f[1]) < 0.018) hit = e.optString("id");
        if (hit != null) {
            JSONObject e = m.get(hit);
            removeEl(page, hit, true);
            pushUndo("del", page, e);
        }
    }

    private void textAt(final float[] f) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(6), dp(18), 0);
        final EditText e = new EditText(this);
        e.setHint(T.t("Text or a formula"));
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        box.addView(e);
        GridLayout keys = new GridLayout(this);
        keys.setColumnCount(8);
        for (final String sy : SYMBOLS) {
            TextView k = Ui.text(this, 18, Ui.TEXT, false);
            k.setText(sy);
            k.setGravity(Gravity.CENTER);
            k.setBackground(Ui.focusCard(this, 8));
            k.setOnClickListener(v -> e.getText().insert(Math.max(0, e.getSelectionStart()), sy));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = dp(36);
            lp.height = dp(38);
            keys.addView(k, lp);
        }
        box.addView(keys);
        Button fm = small("∑ " + T.t("Formulas"), v -> new UiDialog(this).setTitle(T.t("Formulas")).setItems(FORMULAS, (d, w) ->
                e.getText().insert(Math.max(0, e.getSelectionStart()), FORMULAS[w])).show());
        box.addView(fm);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new UiDialog(this).setTitle(T.t("Text")).setView(sv).setPositiveButton(T.t("Place"), (d, w) -> {
            String s = e.getText().toString().trim();
            if (s.isEmpty()) return;
            try {
                JSONObject el = newEl("text");
                el.put("p", new JSONArray().put(r4(f[0])).put(r4(f[1])));
                el.put("s", s);
                el.put("z", new int[]{40, 56, 80}[size]);
                pageInk(page).put(el.getString("id"), el);
                sendEl(el, false);
                pushUndo("add", page, el);
                overlay.invalidate();
            } catch (Exception ignored) {
            }
        }).setNegativeButton(T.t("Cancel"), null).show();
    }

    private void removeEl(int pg, String id, boolean tell) {
        LinkedHashMap<String, JSONObject> m = ink.get(pg);
        if (m != null) m.remove(id);
        send("del", id + "|" + pg);
        overlay.invalidate();
    }

    private void pushUndo(String op, int pg, Object what) {
        undo.add(new Object[]{op, pg, what});
        if (undo.size() > 200) undo.remove(0);
        redo.clear();
    }

    @SuppressWarnings("unchecked")
    private void undo() {
        if (undo.isEmpty()) return;
        Object[] u = undo.remove(undo.size() - 1);
        apply(u, true);
        redo.add(u);
    }

    private void redo() {
        if (redo.isEmpty()) return;
        Object[] u = redo.remove(redo.size() - 1);
        apply(u, false);
        undo.add(u);
    }

    /** reverse = undo. */
    @SuppressWarnings("unchecked")
    private void apply(Object[] u, boolean reverse) {
        String op = (String) u[0];
        int pg = (Integer) u[1];
        if ("clear".equals(op)) {
            List<JSONObject> all = (List<JSONObject>) u[2];
            if (reverse) for (JSONObject e : all) {
                pageInk(pg).put(e.optString("id"), e);
                sendEl(e, false);
            }
            else {
                pageInk(pg).clear();
                send("clearpg", String.valueOf(pg));
            }
        } else {
            JSONObject e = (JSONObject) u[2];
            boolean add = "add".equals(op) != reverse;
            if (add) {
                pageInk(pg).put(e.optString("id"), e);
                sendEl(e, false);
            } else removeEl(pg, e.optString("id"), true);
        }
        if (pg != page) go(pg);
        overlay.invalidate();
    }

    private void clearPage() {
        LinkedHashMap<String, JSONObject> m = ink.get(page);
        if (m == null || m.isEmpty()) {
            send("pen_clear", "");
            return;
        }
        pushUndo("clear", page, new ArrayList<>(m.values()));
        m.clear();
        send("clearpg", String.valueOf(page));
        send("pen_clear", "");
        overlay.invalidate();
    }

    private static double r4(float v) {
        return Math.round(v * 10000) / 10000.0;
    }

    private void sendEl(JSONObject e, boolean unused) {
        final String js = e.toString();
        net.execute(() -> post("el", js, null, null));
    }

    /** Ink, laser and spotlight on the phone (same places as on the big screen). */
    final class Ink extends View {
        float[] laser, spot;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), dark = new Paint();

        Ink(Context c) {
            super(c);
            dark.setColor(0xB3000000);
        }

        @Override
        protected void onDraw(Canvas c) {
            float[] t = transform(getWidth(), getHeight());
            LinkedHashMap<String, JSONObject> m = ink.get(page);
            if (m != null) for (JSONObject e : new ArrayList<>(m.values())) InkDraw.draw(c, e, PresentActivity.this::toScreen, pageH * t[0]);
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
        final AlertDialog d = new UiDialog(this).setTitle(count + " " + T.t(board ? "pages" : "slides")).setView(sv)
                .setNegativeButton(T.t("Close"), null).show();
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

    // ------------------------------------------------------------------ talking to the TV / PC (and the TV group)
    private void send(final String c, final String v) {
        net.execute(() -> post(c, v, null, null));
    }

    private void post(String c, String v, String x, String y) {
        if (ip == null || ip.isEmpty()) return;
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
