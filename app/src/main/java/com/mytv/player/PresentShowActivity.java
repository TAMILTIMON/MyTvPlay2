package com.mytv.player;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.pdf.PdfRenderer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import org.json.JSONObject;

/**
 * TV: shows a PDF presentation sent from the phone, slide by slide, drawn at the TV's own resolution
 * (sharp text). The phone is the clicker: next / previous / go to, laser pointer, blank screen.
 */
public class PresentShowActivity extends AppCompatActivity {
    public static final String EXTRA_URL = "url", EXTRA_TITLE = "title";
    static volatile PresentShowActivity inst;
    static volatile int page, count;
    static volatile String title = "";
    static volatile String status = "";
    static volatile boolean blank;

    private final Handler main = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private ImageView img;
    private Overlay pointer;
    private View black;
    private TextView badge, msg;
    private PdfRenderer renderer;
    private ParcelFileDescriptor pfd;
    private int loadToken;
    private final LruCache<Integer, Bitmap> cache = new LruCache<Integer, Bitmap>(5);
    private final Object pdfLock = new Object();

    /** Opens (or replaces) the presentation on the TV. */
    public static void open(Context c, String url, String t) {
        Intent i = new Intent(c, PresentShowActivity.class).putExtra(EXTRA_URL, url).putExtra(EXTRA_TITLE, t)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        c.startActivity(i);
    }

    /** Commands from the phone (any thread). Returns the state. */
    public static JSONObject cmd(String c, String v, String x, String y) throws Exception {
        final PresentShowActivity a = inst;
        if (a != null) a.main.post(() -> a.apply(c, v, x, y));
        return state();
    }

    public static JSONObject state() throws Exception {
        return new JSONObject().put("open", inst != null).put("page", page).put("count", count).put("title", title)
                .put("blank", blank).put("status", status).put("zoom", zoom);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        inst = this;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.FIT_XY);        // the bitmap is exactly the screen (zoom is drawn into it)
        root.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pointer = new Overlay(this);
        root.addView(pointer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        black = new View(this);
        black.setBackgroundColor(Color.BLACK);
        black.setVisibility(View.GONE);
        root.addView(black, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        badge = Ui.text(this, 16, Color.WHITE, true);
        badge.setBackground(Ui.card(this, 0x99000000, 0, 14));
        badge.setPadding(Ui.dp(this, 14), Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 6));
        badge.setAlpha(0f);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        bl.setMargins(0, 0, Ui.dp(this, 24), Ui.dp(this, 20));
        root.addView(badge, bl);
        msg = Ui.text(this, 22, Color.WHITE, true);
        msg.setGravity(Gravity.CENTER);
        root.addView(msg, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        load(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        load(i);
    }

    private void load(Intent i) {
        final String url = i.getStringExtra(EXTRA_URL);
        if (url == null) return;
        title = i.getStringExtra(EXTRA_TITLE) == null ? "" : i.getStringExtra(EXTRA_TITLE);
        final int tok = ++loadToken;
        status = "loading";
        msg.setText(T.t("Opening the presentation…") + "\n" + title);
        msg.setVisibility(View.VISIBLE);
        img.setImageBitmap(null);
        new Thread(() -> {
            try {
                File f = new File(getCacheDir(), "present_" + tok + ".pdf");
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(30000);
                try (InputStream in = c.getInputStream(); FileOutputStream o = new FileOutputStream(f)) {
                    byte[] b = new byte[65536];
                    int n;
                    while ((n = in.read(b)) > 0) o.write(b, 0, n);
                }
                synchronized (pdfLock) {
                    closePdf();
                    pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
                    renderer = new PdfRenderer(pfd);
                    count = renderer.getPageCount();
                }
                f.deleteOnExit();
                main.post(() -> {
                    if (tok != loadToken) return;
                    cache.evictAll();
                    status = "ready";
                    msg.setVisibility(View.GONE);
                    show(0);
                });
            } catch (final Exception e) {
                status = "error";
                main.post(() -> msg.setText("⚠ " + T.t("Can't open this PDF") + "\n" + e.getMessage()));
            }
        }).start();
    }

    // ---- zoom (slide coordinates: 0…1 of the page; z = 1 fits the whole page)
    static volatile float zoom = 1f, zcx = 0.5f, zcy = 0.5f;
    private final float[] tf = new float[4];          // current page→screen: scale, offsetX, offsetY, page width (pt)

    /** The page→screen transform for the current zoom (W×H screen). */
    private float[] transform(PdfRenderer.Page p, int W, int H) {
        float pw = p.getWidth(), ph = p.getHeight();
        float S = Math.min(W / pw, H / ph) * zoom;
        float cx = zcx * pw * S, cy = zcy * ph * S;
        float ox = pw * S <= W ? (W - pw * S) / 2 : Math.max(W - pw * S, Math.min(0, W / 2f - cx));
        float oy = ph * S <= H ? (H - ph * S) / 2 : Math.max(H - ph * S, Math.min(0, H / 2f - cy));
        return new float[]{S, ox, oy, pw, ph};
    }

    /** One screen-sized picture of page i at the current zoom (only the visible part is drawn, sharp). */
    private Bitmap render(int i) {
        String key = i + "@" + zoom + "," + zcx + "," + zcy;
        synchronized (pdfLock) {
            if (renderer == null || i < 0 || i >= count) return null;
            if (key.equals(lastKey) && lastBmp != null) return lastBmp;
            Bitmap c = zoom == 1f ? cache.get(i) : null;
            if (c != null) return c;
            int W = Math.max(1280, root.getWidth()), H = Math.max(720, root.getHeight());
            PdfRenderer.Page p = renderer.openPage(i);
            float[] t = transform(p, W, H);
            Bitmap bm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
            bm.eraseColor(Color.BLACK);
            Canvas cv = new Canvas(bm);
            Paint white = new Paint();
            white.setColor(Color.WHITE);
            cv.drawRect(t[1], t[2], t[1] + t[3] * t[0], t[2] + t[4] * t[0], white);
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setScale(t[0], t[0]);
            m.postTranslate(t[1], t[2]);
            p.render(bm, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            p.close();
            if (zoom == 1f) cache.put(i, bm);
            lastKey = key;
            lastBmp = bm;
            synchronized (tf) {
                tf[0] = t[0];
                tf[1] = t[1];
                tf[2] = t[2];
                tf[3] = t[3];
            }
            pageH = t[4];
            return bm;
        }
    }

    private String lastKey;
    private Bitmap lastBmp;
    private float pageH;
    private volatile int renderGen;

    private void show(final int i) {
        if (i < 0 || i >= count) return;
        boolean newPage = i != page;
        page = i;
        if (newPage) {
            zoom = 1f;
            zcx = zcy = 0.5f;
            pointer.clearInk();
        }
        final int tok = loadToken, gen = ++renderGen;
        new Thread(() -> {
            if (gen != renderGen) return;                 // a newer zoom / page is already asked for
            final Bitmap bm = render(i);
            main.post(() -> {
                if (tok != loadToken || page != i || bm == null || gen != renderGen) return;
                if (newPage) {
                    img.setAlpha(0.35f);
                    img.animate().alpha(1f).setDuration(160).start();
                }
                img.setImageBitmap(bm);
                pointer.invalidate();
                if (newPage) {
                    badge.setText((i + 1) + " / " + count);
                    badge.animate().alpha(1f).setDuration(150).start();
                    main.removeCallbacks(hideBadge);
                    main.postDelayed(hideBadge, 2500);
                }
            });
            if (newPage && zoom == 1f) {
                synchronized (pdfLock) {
                    if (cache.get(i + 1) == null && renderer != null && i + 1 < count) {
                        float z = zoom;
                        String lk = lastKey;
                        Bitmap lb = lastBmp;
                        render(i + 1);                    // next slide ready in advance (keep this one current)
                        lastKey = lk;
                        lastBmp = lb;
                        zoom = z;
                    }
                }
            }
        }).start();
    }

    /** Slide coordinates (0…1) → screen pixels with the current zoom. */
    float[] toScreen(float fx, float fy) {
        synchronized (tf) {
            return new float[]{tf[1] + fx * tf[3] * tf[0], tf[2] + fy * pageH * tf[0]};
        }
    }

    private final Runnable hideBadge = () -> badge.animate().alpha(0f).setDuration(400).start();

    private void apply(String c, String v, String x, String y) {
        try {
            switch (c) {
                case "next": show(Math.min(count - 1, page + 1)); break;
                case "prev": show(Math.max(0, page - 1)); break;
                case "goto": show(Integer.parseInt(v)); break;
                case "blank": {
                    // "" toggles black · 1 black · 2 white · 0 off
                    int mode = v.isEmpty() ? (blank ? 0 : 1) : Integer.parseInt(v);
                    blank = mode != 0;
                    black.setBackgroundColor(mode == 2 ? Color.WHITE : Color.BLACK);
                    black.setVisibility(blank ? View.VISIBLE : View.GONE);
                    break;
                }
                case "zoom": {                     // v = "z,cx,cy"
                    String[] z = v.split(",");
                    zoom = Math.max(1f, Math.min(8f, Float.parseFloat(z[0])));
                    zcx = Math.max(0f, Math.min(1f, Float.parseFloat(z[1])));
                    zcy = Math.max(0f, Math.min(1f, Float.parseFloat(z[2])));
                    final int i = page;
                    final int tok = loadToken, gen = ++renderGen;
                    new Thread(() -> {
                        if (gen != renderGen) return;
                        final Bitmap bm = render(i);
                        main.post(() -> {
                            if (tok != loadToken || page != i || bm == null || gen != renderGen) return;
                            img.setImageBitmap(bm);
                            pointer.invalidate();
                        });
                    }).start();
                    break;
                }
                case "pointer": pointer.laser(Float.parseFloat(x), Float.parseFloat(y)); break;
                case "pointer_off": pointer.laserOff(); break;
                case "spot": pointer.spot(Float.parseFloat(x), Float.parseFloat(y)); break;
                case "spot_off": pointer.spotOff(); break;
                case "pen": pointer.pen(v, x, y); break;              // v = "id|#color", x / y = comma lists
                case "pen_clear": pointer.clearInk(); break;
                case "end": finish(); break;
                default: break;
            }
        } catch (Exception ignored) {
        }
    }

    /** Laser dot, pen drawing and spotlight, all in slide coordinates (so they follow the zoom). */
    final class Overlay extends View {
        private float lx = -1, ly = -1, sx = -1, sy = -1;
        private final java.util.LinkedHashMap<String, java.util.List<float[]>> strokes = new java.util.LinkedHashMap<>();
        private final java.util.HashMap<String, Integer> colors = new java.util.HashMap<>();
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), ink = new Paint(Paint.ANTI_ALIAS_FLAG), dark = new Paint();

        Overlay(Context c) {
            super(c);
            ink.setStyle(Paint.Style.STROKE);
            ink.setStrokeCap(Paint.Cap.ROUND);
            ink.setStrokeJoin(Paint.Join.ROUND);
            dark.setColor(0xD9000000);
        }

        void laser(float fx, float fy) {
            lx = fx;
            ly = fy;
            invalidate();
        }

        void laserOff() {
            lx = ly = -1;
            invalidate();
        }

        void spot(float fx, float fy) {
            sx = fx;
            sy = fy;
            invalidate();
        }

        void spotOff() {
            sx = sy = -1;
            invalidate();
        }

        void pen(String idColor, String xs, String ys) {
            String[] ic = idColor.split("\\|");
            String id = ic[0];
            if (ic.length > 1) {
                try {
                    colors.put(id, Color.parseColor(ic[1]));
                } catch (Exception ignored) {
                }
            }
            java.util.List<float[]> pts = strokes.get(id);
            if (pts == null) {
                pts = new java.util.ArrayList<>();
                strokes.put(id, pts);
            }
            String[] a = xs.split(","), b = ys.split(",");
            for (int k = 0; k < Math.min(a.length, b.length); k++) {
                if (!a[k].isEmpty()) pts.add(new float[]{Float.parseFloat(a[k]), Float.parseFloat(b[k])});
            }
            invalidate();
        }

        void clearInk() {
            strokes.clear();
            colors.clear();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float width = Math.max(4f, Ui.dp(getContext(), 3) * (float) Math.sqrt(zoom));
            ink.setStrokeWidth(width);
            for (java.util.Map.Entry<String, java.util.List<float[]>> e : strokes.entrySet()) {
                java.util.List<float[]> pts = e.getValue();
                if (pts.isEmpty()) continue;
                Integer col = colors.get(e.getKey());
                ink.setColor(col == null ? 0xFFEF4444 : col);
                android.graphics.Path path = new android.graphics.Path();
                float[] s0 = toScreen(pts.get(0)[0], pts.get(0)[1]);
                path.moveTo(s0[0], s0[1]);
                for (int k = 1; k < pts.size(); k++) {
                    float[] sp = toScreen(pts.get(k)[0], pts.get(k)[1]);
                    path.lineTo(sp[0], sp[1]);
                }
                if (pts.size() == 1) path.lineTo(s0[0] + 0.1f, s0[1]);
                c.drawPath(path, ink);
            }
            if (sx >= 0) {                                    // spotlight: dark everywhere except a circle
                float[] sp = toScreen(sx, sy);
                float rad = Math.min(getWidth(), getHeight()) * 0.16f;
                android.graphics.Path hole = new android.graphics.Path();
                hole.setFillType(android.graphics.Path.FillType.EVEN_ODD);
                hole.addRect(0, 0, getWidth(), getHeight(), android.graphics.Path.Direction.CW);
                hole.addCircle(sp[0], sp[1], rad, android.graphics.Path.Direction.CW);
                c.drawPath(hole, dark);
            }
            if (lx >= 0) {
                float[] sp = toScreen(lx, ly);
                float rad = Ui.dp(getContext(), 26);
                p.setShader(new RadialGradient(sp[0], sp[1], rad, new int[]{0xFFFF1A1A, 0x88FF2020, 0x00FF0000}, new float[]{0.18f, 0.45f, 1f},
                        Shader.TileMode.CLAMP));
                c.drawCircle(sp[0], sp[1], rad, p);
                p.setShader(null);
                p.setColor(0xFFFFE4E4);
                c.drawCircle(sp[0], sp[1], Ui.dp(getContext(), 3), p);
            }
        }
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        // the TV remote works too
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_PAGE_DOWN || code == KeyEvent.KEYCODE_MEDIA_NEXT) {
            show(Math.min(count - 1, page + 1));
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_PAGE_UP || code == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
            show(Math.max(0, page - 1));
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(code, e);
    }

    private void closePdf() {
        try {
            if (renderer != null) renderer.close();
            if (pfd != null) pfd.close();
        } catch (Exception ignored) {
        }
        renderer = null;
        pfd = null;
    }

    @Override
    protected void onDestroy() {
        if (inst == this) inst = null;
        synchronized (pdfLock) {
            closePdf();
        }
        status = "";
        super.onDestroy();
    }
}
