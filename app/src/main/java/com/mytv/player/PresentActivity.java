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

    private LinearLayout root;
    private ImageView cur, next;
    private Ink overlay;
    private TextView counter, timer, status, titleT, zoomT;
    private LinearLayout strip;
    private Button blankBtn, paperBtn;
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
        buildUi();
        Orient.apply(this, "present_orient");
        setTool(board ? PEN : LASER);
        paintBlank();
        paintOpts();
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

    // ------------------------------------------------------------------ the screen: the board fills it
    // floating round button → circle menu of tools (EZ Write style); hidden side panel → pages, paper, zoom, save, share
    private FrameLayout full, radial;
    private LinearLayout drawer, topBar, navPill;
    private TextView fab, handle;
    private boolean drawerOpen;
    private final Runnable hideDrawer = () -> showDrawer(false);

    private void buildUi() {
        full = new FrameLayout(this);
        full.setBackgroundColor(0xFF0B0B0F);
        // the slide / board
        FrameLayout frame = new FrameLayout(this);
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
        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        fl.setMargins(dp(4), dp(48), dp(4), dp(4));
        full.addView(frame, fl);

        // small top bar: back · title / status · timer · End
        topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(6), dp(4), dp(6), dp(4));
        topBar.addView(UiKit.darkButton(this, "arrow-left", 36, false, v -> finish()));
        LinearLayout tc = new LinearLayout(this);
        tc.setOrientation(LinearLayout.VERTICAL);
        tc.setPadding(dp(8), 0, 0, 0);
        titleT = Ui.text(this, 14, Color.WHITE, true);
        titleT.setText(T.t(board ? "Whiteboard" : "Present"));
        titleT.setSingleLine(true);
        titleT.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status = Ui.text(this, 10, 0xB3FFFFFF, false);
        status.setSingleLine(true);
        tc.addView(titleT);
        tc.addView(status);
        topBar.addView(tc, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        timer = Ui.text(this, 15, 0xFFFBBF24, true);
        timer.setPadding(dp(8), 0, dp(8), 0);
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
        topBar.addView(timer);
        Button end = small(T.t("End"), v -> {
            send("end", "");
            finish();
        });
        end.setBackground(Ui.card(this, 0xFFDC2626, 0, 14));
        topBar.addView(end, new LinearLayout.LayoutParams(dp(58), dp(34)));
        full.addView(topBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46), Gravity.TOP));

        // ◀ 1 / 3 ▶ — slides / pages without opening anything
        navPill = new LinearLayout(this);
        navPill.setGravity(Gravity.CENTER_VERTICAL);
        navPill.setBackground(Ui.card(this, 0xCC16161C, 0x33FFFFFF, 22));
        navPill.setPadding(dp(4), 0, dp(4), 0);
        TextView pv = Ui.text(this, 20, Color.WHITE, true);
        pv.setText("‹");
        pv.setGravity(Gravity.CENTER);
        pv.setContentDescription(T.t("Previous"));
        pv.setOnClickListener(v -> go(page - 1));
        navPill.addView(pv, new LinearLayout.LayoutParams(dp(40), dp(40)));
        counter = Ui.text(this, 12, Color.WHITE, true);
        counter.setPadding(dp(4), 0, dp(4), 0);
        navPill.addView(counter);
        TextView nv = Ui.text(this, 20, Color.WHITE, true);
        nv.setText("›");
        nv.setGravity(Gravity.CENTER);
        nv.setContentDescription(T.t("Next"));
        nv.setOnClickListener(v -> {
            if (board && page == count - 1) addPage();
            else go(page + 1);
        });
        navPill.addView(nv, new LinearLayout.LayoutParams(dp(40), dp(40)));
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42), Gravity.BOTTOM | Gravity.START);
        np.setMargins(dp(12), 0, 0, dp(12));
        full.addView(navPill, np);

        // the floating round button (drag it anywhere; tap = circle menu)
        fab = Ui.text(this, 22, Color.WHITE, true);
        fab.setGravity(Gravity.CENTER);
        fab.setElevation(dp(10));
        fab.setContentDescription(T.t("Tools"));
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(dp(58), dp(58), Gravity.BOTTOM | Gravity.END);
        fp.setMargins(0, 0, dp(18), dp(18));
        full.addView(fab, fp);
        fab.setOnTouchListener(new View.OnTouchListener() {
            float dx, dy, sx, sy;
            boolean moved;

            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dx = v.getX() - e.getRawX();
                        dy = v.getY() - e.getRawY();
                        sx = e.getRawX();
                        sy = e.getRawY();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.hypot(e.getRawX() - sx, e.getRawY() - sy) > dp(8)) moved = true;
                        if (moved) {
                            v.setX(Math.max(0, Math.min(full.getWidth() - v.getWidth(), e.getRawX() + dx)));
                            v.setY(Math.max(0, Math.min(full.getHeight() - v.getHeight(), e.getRawY() + dy)));
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) toggleRadial();
                        return true;
                    default:
                        return false;
                }
            }
        });

        // the side panel (pages · paper · zoom · save · share …), hidden at the right edge
        handle = Ui.text(this, 18, Color.WHITE, true);
        handle.setText("‹");
        handle.setGravity(Gravity.CENTER);
        handle.setBackground(Ui.card(this, 0xAA26262E, 0x33FFFFFF, 12));
        handle.setContentDescription(T.t("More: pages, paper, save, share"));
        handle.setOnClickListener(v -> showDrawer(true));
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(dp(26), dp(72), Gravity.END | Gravity.CENTER_VERTICAL);
        full.addView(handle, hp);
        buildDrawer();
        setContentView(full);
    }

    private void buildDrawer() {
        drawer = new LinearLayout(this);
        drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setBackground(Ui.card(this, 0xF2121218, 0x33FFFFFF, 18));
        drawer.setPadding(dp(10), dp(10), dp(10), dp(10));
        drawer.setElevation(dp(14));
        ScrollView sv = new ScrollView(this);
        LinearLayout in = new LinearLayout(this);
        in.setOrientation(LinearLayout.VERTICAL);
        sv.addView(in);
        drawer.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        // pages
        in.addView(UiRows.header(this, T.t(board ? "Pages" : "Slides")));
        strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.VERTICAL);
        in.addView(strip);
        LinearLayout pr = new LinearLayout(this);
        pr.addView(small("＋ " + T.t("Page"), v -> addPage()), new LinearLayout.LayoutParams(0, dp(38), 1));
        pr.addView(small("🗑 " + T.t("Page"), v -> deletePage()), new LinearLayout.LayoutParams(0, dp(38), 1));
        pr.setVisibility(board ? View.VISIBLE : View.GONE);
        in.addView(pr);
        paperBtn = small("", v -> pickPaper());
        paperBtn.setVisibility(board ? View.VISIBLE : View.GONE);
        in.addView(paperBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));
        in.addView(small("▦ " + T.t("All") + "  ·  # " + T.t("Go to slide"), v -> {
            showDrawer(false);
            new UiDialog(this).setItems(new String[]{"▦ " + T.t("All"), "# " + T.t("Go to slide")}, (d, w) -> {
                if (w == 0) overview();
                else goToNumber();
            }).show();
        }), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));
        // view
        in.addView(UiRows.header(this, T.t("View")));
        LinearLayout zr = new LinearLayout(this);
        zr.addView(small("−", v -> setZoom(zoom / 1.5f, zcx, zcy)), new LinearLayout.LayoutParams(0, dp(38), 1));
        zr.addView(small(T.t("Fit"), v -> setZoom(1f, 0.5f, 0.5f)), new LinearLayout.LayoutParams(0, dp(38), 1.2f));
        zr.addView(small("＋", v -> setZoom(zoom * 1.5f, zcx, zcy)), new LinearLayout.LayoutParams(0, dp(38), 1));
        in.addView(zr);
        blankBtn = small("", v -> {
            blankMode = (blankMode + 1) % 3;
            send("blank", String.valueOf(blankMode));
            paintBlank();
        });
        in.addView(blankBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));
        Button rot = Orient.button(this, "present_orient");
        in.addView(rot, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));
        next = new ImageView(this);                     // the next slide, small
        next.setScaleType(ImageView.ScaleType.FIT_CENTER);
        next.setVisibility(View.GONE);
        zoomT = Ui.text(this, 11, 0x99FFFFFF, false);
        zoomT.setPadding(dp(4), dp(6), dp(4), dp(2));
        in.addView(zoomT);
        // keep & share
        in.addView(UiRows.header(this, T.t("Keep & share")));
        if (board) {
            in.addView(small("💾 " + T.t("Save now"), v -> {
                saveBoard(true);
            }), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));
            in.addView(small("📂 " + T.t("Open saved board"), v -> openSaved()), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));
            in.addView(small("✨ " + T.t("New board"), v -> newBoard()), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));
        }
        in.addView(small("📤 " + T.t("Share / export (PDF, Word, pictures)"), v -> exportMenu()),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));
        drawer.setOnTouchListener((v, e) -> {
            keepDrawer();
            return false;
        });
        sv.setOnTouchListener((v, e) -> {
            keepDrawer();
            return false;
        });
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(dp(250), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        dl.setMargins(0, dp(48), dp(4), dp(4));
        full.addView(drawer, dl);
        drawer.setTranslationX(dp(270));
        drawer.setVisibility(View.INVISIBLE);
    }

    private void showDrawer(boolean open) {
        drawerOpen = open;
        main.removeCallbacks(hideDrawer);
        if (open) {
            if (radial != null) toggleRadial();
            drawer.setVisibility(View.VISIBLE);
            drawer.animate().translationX(0).setDuration(180).start();
            handle.animate().alpha(0f).setDuration(120).start();
            keepDrawer();
        } else {
            drawer.animate().translationX(dp(270)).setDuration(180).withEndAction(() -> {
                if (!drawerOpen) drawer.setVisibility(View.INVISIBLE);
            }).start();
            handle.animate().alpha(1f).setDuration(180).start();
        }
    }

    /** Auto-hide: 6 s after the last touch in the panel. */
    private void keepDrawer() {
        main.removeCallbacks(hideDrawer);
        if (drawerOpen) main.postDelayed(hideDrawer, 6000);
    }

    // ---- the circle menu
    private static final String[][] RING = {{"🔴", "Laser"}, {"✏", "Pen"}, {"🖍", "Highlighter"}, {"🧽", "Eraser"}, {"⬜", "Shapes"},
            {"📏", "Ruler"}, {"T", "Text"}, {"🔦", "Spotlight"}, {"✋", "Move"}, {"↶", "Undo"}, {"↷", "Redo"}, {"🧹", "Clear"}};

    private void toggleRadial() {
        if (radial != null) {
            final FrameLayout r = radial;
            radial = null;
            r.animate().alpha(0f).setDuration(120).withEndAction(() -> full.removeView(r)).start();
            return;
        }
        if (drawerOpen) showDrawer(false);
        radial = new FrameLayout(this);
        radial.setBackgroundColor(0x66000000);
        radial.setOnClickListener(v -> toggleRadial());
        full.addView(radial, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // the circle fits the screen (smaller on narrow phones); labels need more room sideways than up / down
        float item = dp(50), labelW = dp(36), labelH = dp(10);
        float room = Math.min(full.getWidth() / 2f - labelW, full.getHeight() / 2f - labelH) - item * 0.78f;
        if (room < dp(115)) item = dp(44);
        float R1 = Math.max(dp(88), Math.min(dp(122), room)), R2 = R1 * 0.56f, small = item * 0.72f;
        // centre on the button, but keep the whole circle and its labels on screen
        float cx = fab.getX() + fab.getWidth() / 2f, cy = fab.getY() + fab.getHeight() / 2f;
        float mx = R1 + item * 0.78f + labelW, my = R1 + item * 0.78f + labelH;
        cx = Math.max(mx, Math.min(full.getWidth() - mx, cx));
        cy = Math.max(my, Math.min(full.getHeight() - my, cy));
        for (int i = 0; i < RING.length; i++) {
            final int k = i;
            double ang = -Math.PI / 2 + 2 * Math.PI * i / RING.length;
            boolean isTool = i < TOOLS.length;
            TextView b = Ui.text(this, i == TEXT ? 20 : 19, Color.WHITE, true);
            b.setText(RING[i][0]);
            b.setGravity(Gravity.CENTER);
            b.setContentDescription(T.t(RING[i][1]));
            b.setBackground(Ui.card(this, isTool && tool == i ? 0xFF0E7490 : 0xF226262E, isTool && tool == i ? 0xFF22D3EE : 0x55FFFFFF, (int) (item / 2)));
            b.setElevation(dp(8));
            b.setOnClickListener(v -> {
                if (k < TOOLS.length) {
                    if (k == SHAPE) {
                        toggleRadial();
                        pickShape();
                        return;
                    }
                    setTool(k);
                    toggleRadial();
                } else if (k == TOOLS.length) undo();
                else if (k == TOOLS.length + 1) redo();
                else clearPage();
            });
            place(b, cx + (float) (R1 * Math.cos(ang)), cy + (float) (R1 * Math.sin(ang)), item);
            TextView lab = Ui.text(this, 9, 0xDDFFFFFF, false);
            lab.setText(T.t(RING[i][1]));
            lab.setGravity(Gravity.CENTER);
            lab.setShadowLayer(4, 0, 1, Color.BLACK);
            place(lab, cx + (float) ((R1 + item * 0.78f) * Math.cos(ang)), cy + (float) ((R1 + item * 0.78f) * Math.sin(ang)), dp(70), dp(16));
        }
        // inner ring: colours + size
        for (int i = 0; i <= COLORS.length; i++) {
            final int k = i;
            double ang = -Math.PI / 2 + 2 * Math.PI * i / (COLORS.length + 1);
            TextView b = Ui.text(this, 12, Color.WHITE, true);
            b.setGravity(Gravity.CENTER);
            b.setElevation(dp(8));
            if (i < COLORS.length) {
                b.setBackground(Ui.card(this, COLORS[i], COLORS[i] == color ? 0xFFFFFFFF : 0x66FFFFFF, (int) (small / 2)));
                if (COLORS[i] == color) b.setText("✓");
                b.setTextColor(COLORS[i] == 0xFFFFFFFF || COLORS[i] == 0xFFFACC15 ? Color.BLACK : Color.WHITE);
                b.setContentDescription(T.t("Colour"));
                b.setOnClickListener(v -> {
                    color = COLORS[k];
                    paintOpts();
                    toggleRadial();
                    toggleRadial();                       // re-open: the tick moves
                });
            } else {
                b.setText(new String[]{"S", "M", "L"}[size]);
                b.setBackground(Ui.card(this, 0xF226262E, 0x55FFFFFF, (int) (small / 2)));
                b.setContentDescription(T.t("Size"));
                b.setOnClickListener(v -> {
                    size = (size + 1) % 3;
                    paintOpts();
                    ((TextView) v).setText(new String[]{"S", "M", "L"}[size]);
                });
            }
            place(b, cx + (float) (R2 * Math.cos(ang)), cy + (float) (R2 * Math.sin(ang)), small);
        }
        TextView close = Ui.text(this, 18, Color.WHITE, true);
        close.setText("✕");
        close.setGravity(Gravity.CENTER);
        close.setBackground(Ui.gradient(this, (int) (dp(40) / 2)));
        close.setOnClickListener(v -> toggleRadial());
        place(close, cx, cy, dp(40));
        radial.setAlpha(0f);
        radial.animate().alpha(1f).setDuration(140).start();
    }

    private void place(View v, float cx, float cy, float size) {
        place(v, cx, cy, size, size);
    }

    private void place(View v, float cx, float cy, float w, float h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int) w, (int) h);
        radial.addView(v, lp);
        v.setX(cx - w / 2);
        v.setY(cy - h / 2);
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        if (radial != null) toggleRadial();
        main.postDelayed(this::refresh, 150);
    }

    private Button small(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(label);
        b.setTextSize(13);
        b.setTextColor(Color.WHITE);
        b.setBackground(Ui.card(this, 0xFF26262E, 0, 14));
        b.setOnClickListener(v -> {
            keepDrawer();
            l.onClick(v);
        });
        b.setPadding(dp(6), 0, dp(6), 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
        lp.setMargins(dp(2), dp(3), dp(2), dp(3));
        b.setLayoutParams(lp);
        return b;
    }

    private void setTool(int t) {
        tool = t;
        paintOpts();
    }

    private void pickShape() {
        new UiDialog(this).setTitle(T.t("Shapes")).setItems(tr(SHAPE_NAMES), (d, w) -> {
            shape = SHAPES[w];
            setTool(SHAPE);
        }).show();
    }

    /** The round button shows the tool, in the pen colour. */
    private void paintOpts() {
        if (fab == null) return;
        fab.setText(TOOLS[tool][0]);
        boolean ink = tool == PEN || tool == HL || tool == SHAPE || tool == RULER || tool == TEXT;
        fab.setBackground(Ui.card(this, 0xF226262E, ink ? color : 0xFF22D3EE, dp(29)));
        if (paperBtn != null) paperBtn.setText("📄 " + T.t("Paper") + ": "
                + T.t(InkDraw.PAPER_NAMES[Math.max(0, java.util.Arrays.asList(InkDraw.PAPERS).indexOf(paper))]));
    }

    private void paintBlank() {
        String[] n = {"⬛ " + T.t("Blank screen") + ": " + T.t("Off"), "⬛ " + T.t("Black"), "⬜ " + T.t("White")};
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
                if (board) scheduleSave();
                zoomT.setText(z > 1.01f ? String.format(Locale.US, "%.1f×  ·  %s", z, T.t("Fit = whole page"))
                        : T.t("Pinch to zoom · two fingers move the page"));
                for (int k = 0; k < strip.getChildCount(); k++) strip.getChildAt(k).setAlpha(k == i ? 1f : 0.6f);
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
            t.setPadding(dp(2), dp(2), dp(2), dp(2));
            LinearLayout cell = new LinearLayout(this);
            cell.setGravity(Gravity.CENTER_VERTICAL);
            TextView num = Ui.text(this, 11, Ui.DIM, true);
            num.setText(String.valueOf(i + 1));
            num.setGravity(Gravity.CENTER);
            cell.addView(num, new LinearLayout.LayoutParams(dp(22), ViewGroup.LayoutParams.WRAP_CONTENT));
            cell.addView(t, new LinearLayout.LayoutParams(0, dp(110) * 9 / 16, 1));
            cell.setOnClickListener(v -> go(k));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(3), 0, dp(3));
            strip.addView(cell, lp);
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
                    if (old < strip.getChildCount()) ((ImageView) ((ViewGroup) strip.getChildAt(old)).getChildAt(1)).setImageBitmap(bm);
                });
            });
        }
        page = i;
        zoom = 1f;                             // each page keeps its drawing; the view starts whole
        zcx = zcy = 0.5f;
        refresh();
        send("goto", String.valueOf(i));
        for (int k = 0; k < strip.getChildCount(); k++)
            strip.getChildAt(k).setBackground(k == i ? Ui.card(this, 0x330E7490, 0xFF22D3EE, 8) : null);
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
        scheduleSave();
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
        scheduleSave();
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

    // ------------------------------------------------------------------ saved boards (local) and exports
    private String boardId;
    private final Runnable autoSave = () -> saveBoard(false);

    private void scheduleSave() {
        if (!board) return;
        main.removeCallbacks(autoSave);
        main.postDelayed(autoSave, 1500);
    }

    private JSONObject boardDoc() throws Exception {
        if (boardId == null) {
            boardId = "b" + System.currentTimeMillis();
            title = T.t("Whiteboard") + " " + new java.text.SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(new java.util.Date());
        }
        JSONArray pages = new JSONArray();
        for (int pg = 0; pg < count; pg++) {
            JSONArray els = new JSONArray();
            LinkedHashMap<String, JSONObject> m = ink.get(pg);
            if (m != null) for (JSONObject e : m.values()) els.put(e);
            pages.put(els);
        }
        return new JSONObject().put("id", boardId).put("title", title).put("paper", paper).put("pages", pages);
    }

    private void saveBoard(boolean say) {
        if (!board) return;
        try {
            final JSONObject d = boardDoc();
            boolean empty = BoardFiles.countEls(d) == 0;
            if (empty && !say) return;                       // nothing drawn yet: nothing to keep
            draw.execute(() -> BoardFiles.save(this, d));
            if (say) UiKit.makeToast(this, "💾 " + T.t("Saved") + " · " + title, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
        }
    }

    private void openSaved() {
        showDrawer(false);
        saveBoard(false);
        draw.execute(() -> {
            final List<JSONObject> all = BoardFiles.list(this);
            main.post(() -> {
                if (all.isEmpty()) {
                    UiKit.makeToast(this, T.t("No saved boards yet — they are kept automatically while you draw"),
                            android.widget.Toast.LENGTH_LONG).show();
                    return;
                }
                String[] rows = new String[all.size()];
                java.text.DateFormat df = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT);
                for (int i = 0; i < rows.length; i++) {
                    JSONObject j = all.get(i);
                    rows[i] = "🗂  " + j.optString("title") + ": " + df.format(new java.util.Date(j.optLong("modified"))) + " · "
                            + j.optJSONArray("pages").length() + " " + T.t("pages") + " · " + BoardFiles.countEls(j) + " " + T.t("drawings");
                }
                AlertDialog dl = new UiDialog(this).setTitle(T.t("Saved boards")).setItems(rows, (d, w) -> loadBoard(all.get(w)))
                        .setNegativeButton(T.t("Close"), null).create();
                dl.setOnShowListener(x -> dl.getListView().setOnItemLongClickListener((p, v, pos, id) -> {
                    dl.dismiss();
                    boardActions(all.get(pos));
                    return true;
                }));
                dl.show();
                UiKit.makeToast(this, T.t("Hold a board to rename or delete it"), android.widget.Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void boardActions(final JSONObject j) {
        new UiDialog(this).setTitle(j.optString("title")).setItems(new String[]{"✎  " + T.t("Rename"), "🗑  " + T.t("Delete")}, (d, w) -> {
            if (w == 0) {
                final EditText e = new EditText(this);
                e.setText(j.optString("title"));
                new UiDialog(this).setTitle(T.t("Rename")).setView(e).setPositiveButton(T.t("Save"), (d2, w2) -> {
                    try {
                        j.put("title", e.getText().toString().trim());
                        if (j.optString("id").equals(boardId)) title = j.optString("title");
                        draw.execute(() -> BoardFiles.save(this, j));
                    } catch (Exception ignored) {
                    }
                }).show();
            } else {
                draw.execute(() -> BoardFiles.delete(this, j.optString("id")));
                UiKit.makeToast(this, T.t("Deleted"), android.widget.Toast.LENGTH_SHORT).show();
            }
        }).show();
    }

    /** Opens a saved board here and on the TV (paper, pages, every drawing). */
    private void loadBoard(JSONObject j) {
        try {
            boardId = j.getString("id");
            title = j.optString("title");
            paper = j.optString("paper", "white");
            JSONArray pages = j.getJSONArray("pages");
            count = Math.max(1, pages.length());
            ink.clear();
            undo.clear();
            redo.clear();
            for (int pg = 0; pg < pages.length(); pg++) {
                JSONArray els = pages.getJSONArray(pg);
                for (int k = 0; k < els.length(); k++) pageInk(pg).put(els.getJSONObject(k).optString("id"), els.getJSONObject(k));
            }
            titleT.setText(title);
            page = 0;
            zoom = 1f;
            zcx = zcy = 0.5f;
            thumbs.evictAll();
            buildStrip();
            paintOpts();
            refresh();
            final List<JSONObject> all = new ArrayList<>();
            for (LinkedHashMap<String, JSONObject> m : ink.values()) all.addAll(m.values());
            net.execute(() -> {
                try {
                    HashMap<String, String> p = new HashMap<>();
                    p.put("url", "zhboard:" + paper);
                    p.put("title", title);
                    TvClient.post(ip, "/present", p);
                    Thread.sleep(800);
                } catch (Exception ignored) {
                }
                post("pages", String.valueOf(count), null, null);
                for (JSONObject e : all) post("el", e.toString(), null, null);
            });
            UiKit.makeToast(this, "📂 " + title, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            UiKit.makeToast(this, T.t("Can't open this board"), android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void newBoard() {
        showDrawer(false);
        saveBoard(false);
        boardId = null;
        ink.clear();
        undo.clear();
        redo.clear();
        count = 1;
        page = 0;
        titleT.setText(T.t("Whiteboard"));
        send("clearpg", "0");
        send("pages", "1");
        send("goto", "0");
        buildStrip();
        refresh();
    }

    /** One page as a picture: paper / slide + the drawing (for PDF, Word, pictures). */
    private Bitmap pagePicture(int i, int W, int H) {
        Bitmap bm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        float pw = board ? 960 : pageW, ph = board ? 540 : pageH;
        if (!board) {
            synchronized (this) {
                if (renderer != null) {
                    PdfRenderer.Page p = renderer.openPage(i);
                    pw = p.getWidth();
                    ph = p.getHeight();
                    float S = Math.min(W / pw, H / ph);
                    float ox = (W - pw * S) / 2, oy = (H - ph * S) / 2;
                    bm.eraseColor(Color.WHITE);
                    Matrix m = new Matrix();
                    m.setScale(S, S);
                    m.postTranslate(ox, oy);
                    p.render(bm, null, m, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                    p.close();
                }
            }
        }
        final float S = Math.min(W / pw, H / ph), ox = (W - pw * S) / 2, oy = (H - ph * S) / 2, fpw = pw, fph = ph;
        if (board) InkDraw.paper(c, paper, new RectF(ox, oy, ox + pw * S, oy + ph * S), ph * S);
        LinkedHashMap<String, JSONObject> m = ink.get(i);
        if (m != null) for (JSONObject e : new ArrayList<>(m.values()))
            InkDraw.draw(c, e, (fx, fy) -> new float[]{ox + fx * fpw * S, oy + fy * fph * S}, fph * S);
        return bm;
    }

    private void exportMenu() {
        showDrawer(false);
        final String[] kinds = {"📄  PDF", "📝  Word (.docx)", "🖼  " + T.t("Pictures (PNG)")};
        new UiDialog(this).setTitle(T.t("Share / export")).setItems(kinds, (d, k) ->
                new UiDialog(this).setTitle(kinds[k].replaceAll("^\\S+\\s+", "")).setItems(new String[]{"📤  " + T.t("Share to an app"),
                        "⬇  " + T.t("Save to Downloads/ZH Play")}, (d2, how) -> export(k, how == 0)).show()).show();
    }

    private void export(final int kind, final boolean share) {
        if (count == 0) return;
        UiKit.makeToast(this, T.t("Preparing…"), android.widget.Toast.LENGTH_SHORT).show();
        final BoardFiles.Pages pages = new BoardFiles.Pages() {
            public int count() {
                return count;
            }

            public Bitmap page(int i, int w, int h) {
                return pagePicture(i, w, h);
            }
        };
        final String name = title == null || title.isEmpty() ? T.t("Whiteboard") : title;
        draw.execute(() -> {
            try {
                final List<java.io.File> files = new ArrayList<>();
                if (kind == 0) files.add(BoardFiles.pdf(this, pages, name));
                else if (kind == 1) files.add(BoardFiles.docx(this, pages, name));
                else files.addAll(BoardFiles.pngs(this, pages, name));
                if (!share) BoardFiles.saveToDownloads(this, files);
                main.post(() -> {
                    if (share) BoardFiles.share(this, files);
                    else UiKit.makeToast(this, "✓ " + T.t("Saved in Downloads/ZH Play") + ": " + files.get(0).getName()
                            + (files.size() > 1 ? " +" + (files.size() - 1) : ""), android.widget.Toast.LENGTH_LONG).show();
                });
            } catch (final Exception e) {
                main.post(() -> UiKit.makeToast(this, T.t("Can't export") + ": " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show());
            }
        });
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
        main.removeCallbacks(autoSave);
        if (board) {
            try {
                JSONObject d = boardDoc();
                if (BoardFiles.countEls(d) > 0) BoardFiles.save(this, d);   // keep it (directly: the app is closing)
            } catch (Exception ignored) {
            }
        }
        main.removeCallbacks(tick);
        closePdf();
        net.shutdown();
        draw.shutdown();
        super.onDestroy();
    }
}
