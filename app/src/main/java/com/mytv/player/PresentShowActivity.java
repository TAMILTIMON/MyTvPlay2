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
    private Pointer pointer;
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
                .put("blank", blank).put("status", status);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        inst = this;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pointer = new Pointer(this);
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

    /** Draws one page at the TV's resolution, on a white page, keeping its shape. */
    private Bitmap render(int i) {
        Bitmap c = cache.get(i);
        if (c != null) return c;
        synchronized (pdfLock) {
            if (renderer == null || i < 0 || i >= count) return null;
            PdfRenderer.Page p = renderer.openPage(i);
            int W = Math.max(1280, root.getWidth()), H = Math.max(720, root.getHeight());
            float s = Math.min(W / (float) p.getWidth(), H / (float) p.getHeight());
            Bitmap bm = Bitmap.createBitmap(Math.max(1, Math.round(p.getWidth() * s)), Math.max(1, Math.round(p.getHeight() * s)),
                    Bitmap.Config.ARGB_8888);
            bm.eraseColor(Color.WHITE);
            p.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            p.close();
            cache.put(i, bm);
            return bm;
        }
    }

    private void show(final int i) {
        if (i < 0 || i >= count) return;
        page = i;
        final int tok = loadToken;
        new Thread(() -> {
            final Bitmap bm = render(i);
            main.post(() -> {
                if (tok != loadToken || page != i || bm == null) return;
                img.setAlpha(0.35f);
                img.setImageBitmap(bm);
                img.animate().alpha(1f).setDuration(160).start();
                badge.setText((i + 1) + " / " + count);
                badge.animate().alpha(1f).setDuration(150).start();
                main.removeCallbacks(hideBadge);
                main.postDelayed(hideBadge, 2500);
            });
            render(i + 1);          // the next slide ready in advance
        }).start();
    }

    private final Runnable hideBadge = () -> badge.animate().alpha(0f).setDuration(400).start();

    private void apply(String c, String v, String x, String y) {
        switch (c) {
            case "next": show(Math.min(count - 1, page + 1)); break;
            case "prev": show(Math.max(0, page - 1)); break;
            case "goto":
                try {
                    show(Integer.parseInt(v));
                } catch (Exception ignored) {
                }
                break;
            case "blank":
                blank = v.isEmpty() ? !blank : "1".equals(v);
                black.setVisibility(blank ? View.VISIBLE : View.GONE);
                break;
            case "pointer":
                try {
                    pointer.at(Float.parseFloat(x), Float.parseFloat(y), imageRect());
                } catch (Exception ignored) {
                }
                break;
            case "pointer_off": pointer.off(); break;
            case "end": finish(); break;
            default: break;
        }
    }

    /** Where the slide is drawn on the screen (the pointer uses slide coordinates 0…1). */
    private RectF imageRect() {
        if (img.getDrawable() == null) return new RectF(0, 0, root.getWidth(), root.getHeight());
        float dw = img.getDrawable().getIntrinsicWidth(), dh = img.getDrawable().getIntrinsicHeight();
        float s = Math.min(img.getWidth() / dw, img.getHeight() / dh);
        float w = dw * s, h = dh * s;
        float l = (img.getWidth() - w) / 2, t = (img.getHeight() - h) / 2;
        return new RectF(l, t, l + w, t + h);
    }

    /** Red laser dot with a soft glow. */
    static final class Pointer extends View {
        private float px = -1, py = -1;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Pointer(Context c) {
            super(c);
        }

        void at(float fx, float fy, RectF r) {
            px = r.left + fx * r.width();
            py = r.top + fy * r.height();
            invalidate();
        }

        void off() {
            px = py = -1;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (px < 0) return;
            float rad = Ui.dp(getContext(), 26);
            p.setShader(new RadialGradient(px, py, rad, new int[]{0xFFFF1A1A, 0x88FF2020, 0x00FF0000}, new float[]{0.18f, 0.45f, 1f},
                    Shader.TileMode.CLAMP));
            c.drawCircle(px, py, rad, p);
            p.setShader(null);
            p.setColor(0xFFFFE4E4);
            c.drawCircle(px, py, Ui.dp(getContext(), 3), p);
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
