package com.mytv.player;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Phone: presenter / clicker for a PDF shown on the TV (or the Linux PC).
 * Prev / next (also the volume keys), slide strip, laser pointer (drag on the slide), blank screen, timer.
 */
public class PresentActivity extends AppCompatActivity {
    public static final String EXTRA_IP = "ip";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private String ip, title = "";
    private PdfRenderer renderer;
    private ParcelFileDescriptor pfd;
    private int page, count;
    private final LruCache<String, Bitmap> cache = new LruCache<>(30);
    private ImageView cur, next;
    private TextView counter, timer, status, titleT;
    private LinearLayout strip;
    private Button blankBtn;
    private boolean blank;
    private long started;
    private volatile long lastPointer;

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    @Override
    protected void onCreate(Bundle b) {
        Ui.apply(this);
        super.onCreate(b);
        ip = getIntent().getStringExtra(EXTRA_IP);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B0B0F);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(UiKit.darkButton(this, "arrow-left", 40, false, v -> finish()));
        LinearLayout tc = new LinearLayout(this);
        tc.setOrientation(LinearLayout.VERTICAL);
        tc.setPadding(dp(10), 0, 0, 0);
        titleT = Ui.text(this, 16, Color.WHITE, true);
        titleT.setText(T.t("Present"));
        titleT.setSingleLine(true);
        titleT.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status = Ui.text(this, 11, 0xB3FFFFFF, false);
        tc.addView(titleT);
        tc.addView(status);
        top.addView(tc, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        timer = Ui.text(this, 18, 0xFFFBBF24, true);
        top.addView(timer);
        View g = new View(this);
        top.addView(g, new LinearLayout.LayoutParams(dp(10), 1));
        Button end = small(T.t("End"), v -> {
            send("end", "");
            finish();
        });
        end.setBackground(Ui.card(this, 0xFFDC2626, 0, 14));
        top.addView(end, new LinearLayout.LayoutParams(dp(72), dp(40)));
        root.addView(top);

        // current slide (drag = laser pointer) + next slide
        cur = new ImageView(this);
        cur.setScaleType(ImageView.ScaleType.FIT_CENTER);
        cur.setBackground(Ui.card(this, 0xFF16161C, 0x33FFFFFF, 14));
        cur.setContentDescription(T.t("Current slide — drag for the laser pointer"));
        cur.setOnTouchListener(this::laser);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        cl.setMargins(0, dp(10), 0, dp(6));
        root.addView(cur, cl);
        TextView hint = Ui.text(this, 11, 0x99FFFFFF, false);
        hint.setText(T.t("Drag on the slide = red laser dot on the TV · volume keys = next / previous"));
        hint.setGravity(Gravity.CENTER);
        root.addView(hint);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(6));
        Button prev = small("◀  " + T.t("Previous"), v -> go(page - 1));
        row.addView(prev, new LinearLayout.LayoutParams(0, dp(64), 1));
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setGravity(Gravity.CENTER);
        next = new ImageView(this);
        next.setScaleType(ImageView.ScaleType.FIT_CENTER);
        next.setBackground(Ui.card(this, 0xFF16161C, 0, 8));
        next.setContentDescription(T.t("Next slide"));
        mid.addView(next, new LinearLayout.LayoutParams(dp(96), dp(54)));
        counter = Ui.text(this, 13, Color.WHITE, true);
        mid.addView(counter);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ml.setMargins(dp(8), 0, dp(8), 0);
        row.addView(mid, ml);
        Button nx = small(T.t("Next") + "  ▶", v -> go(page + 1));
        nx.setBackground(Ui.gradient(this, 14));
        row.addView(nx, new LinearLayout.LayoutParams(0, dp(64), 1));
        root.addView(row);

        LinearLayout tools = new LinearLayout(this);
        blankBtn = small(T.t("Blank screen"), v -> {
            blank = !blank;
            send("blank", blank ? "1" : "0");
            paintBlank();
        });
        tools.addView(blankBtn, new LinearLayout.LayoutParams(0, dp(42), 1));
        tools.addView(small(T.t("First slide"), v -> go(0)), new LinearLayout.LayoutParams(0, dp(42), 1));
        tools.addView(small(T.t("Other PDF"), v -> pick()), new LinearLayout.LayoutParams(0, dp(42), 1));
        root.addView(tools);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        strip = new LinearLayout(this);
        hs.addView(strip);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(70));
        sl.setMargins(0, dp(6), 0, 0);
        root.addView(hs, sl);
        setContentView(root);
        paintBlank();
        if (ip == null || ip.isEmpty()) ip = Store.getStr("last_tv", "");
        pick();
    }

    private Button small(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(label);
        b.setTextSize(14);
        b.setTextColor(Color.WHITE);
        b.setBackground(Ui.card(this, 0xFF26262E, 0, 14));
        b.setOnClickListener(l);
        b.setPadding(dp(6), 0, dp(6), 0);
        return b;
    }

    private void paintBlank() {
        blankBtn.setBackground(blank ? Ui.card(this, 0xFFF59E0B, 0, 14) : Ui.card(this, 0xFF26262E, 0, 14));
        blankBtn.setTextColor(blank ? Color.BLACK : Color.WHITE);
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
        cache.evictAll();
        page = 0;
        started = SystemClock.elapsedRealtime();
        buildStrip();
        showLocal();
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

    // ------------------------------------------------------------------ slides on the phone
    private Bitmap render(int i, int w) {
        String k = i + "_" + w;
        Bitmap c = cache.get(k);
        if (c != null || renderer == null || i < 0 || i >= count) return c;
        synchronized (this) {
            PdfRenderer.Page p = renderer.openPage(i);
            float s = w / (float) p.getWidth();
            Bitmap bm = Bitmap.createBitmap(w, Math.max(1, Math.round(p.getHeight() * s)), Bitmap.Config.ARGB_8888);
            bm.eraseColor(Color.WHITE);
            p.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            p.close();
            cache.put(k, bm);
            return bm;
        }
    }

    private void showLocal() {
        int w = Math.max(480, getResources().getDisplayMetrics().widthPixels);
        cur.setImageBitmap(render(page, w));
        next.setImageBitmap(page + 1 < count ? render(page + 1, 240) : null);
        counter.setText((page + 1) + " / " + count);
        for (int i = 0; i < strip.getChildCount(); i++) {
            strip.getChildAt(i).setAlpha(i == page ? 1f : 0.55f);
            strip.getChildAt(i).setScaleY(i == page ? 1f : 0.9f);
        }
        if (page < strip.getChildCount()) {
            View c = strip.getChildAt(page);
            ((HorizontalScrollView) strip.getParent()).smoothScrollTo(Math.max(0, c.getLeft() - dp(100)), 0);
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
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(104), dp(62));
            lp.setMargins(dp(3), 0, dp(3), 0);
            strip.addView(t, lp);
            new Thread(() -> {
                final Bitmap bm = render(k, 200);
                main.post(() -> t.setImageBitmap(bm));
            }).start();
        }
    }

    private void go(int i) {
        if (count == 0) return;
        i = Math.max(0, Math.min(count - 1, i));
        if (i == page && i != 0) return;
        page = i;
        showLocal();
        send("goto", String.valueOf(i));
    }

    // ------------------------------------------------------------------ talking to the TV
    private void send(final String c, final String v) {
        net.execute(() -> {
            try {
                HashMap<String, String> p = new HashMap<>();
                p.put("c", c);
                p.put("v", v);
                TvClient.post(ip, "/presentcmd", p);
            } catch (Exception ignored) {
            }
        });
    }

    /** Drag on the slide → the red dot moves on the TV at the same place (sent ~25 times a second). */
    private boolean laser(View v, MotionEvent e) {
        if (cur.getDrawable() == null) return false;
        float dw = cur.getDrawable().getIntrinsicWidth(), dh = cur.getDrawable().getIntrinsicHeight();
        float s = Math.min(cur.getWidth() / dw, cur.getHeight() / dh);
        float l = (cur.getWidth() - dw * s) / 2, t = (cur.getHeight() - dh * s) / 2;
        final float fx = Math.max(0, Math.min(1, (e.getX() - l) / (dw * s))), fy = Math.max(0, Math.min(1, (e.getY() - t) / (dh * s)));
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            send("pointer_off", "");
            return true;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastPointer < 40 && a == MotionEvent.ACTION_MOVE) return true;
        lastPointer = now;
        net.execute(() -> {
            try {
                HashMap<String, String> p = new HashMap<>();
                p.put("c", "pointer");
                p.put("x", String.format(Locale.US, "%.4f", fx));
                p.put("y", String.format(Locale.US, "%.4f", fy));
                TvClient.post(ip, "/presentcmd", p);
            } catch (Exception ignored) {
            }
        });
        return true;
    }

    private final Runnable tick = new Runnable() {
        public void run() {
            long s = (SystemClock.elapsedRealtime() - started) / 1000;
            timer.setText(String.format(Locale.US, "%d:%02d", s / 60, s % 60));
            main.postDelayed(this, 1000);
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
        main.removeCallbacks(tick);
        closePdf();
        net.shutdown();
        super.onDestroy();
    }
}
