package com.mytv.player;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Edit a photo: crop, rotate/straighten, brightness/contrast/saturation, filters, draw and text — then save. */
public class PhotoEditActivity extends Activity {
    private static final String EXTRA_URI = "uri";
    private static final int MAX_SIDE = 1600;     // working resolution — keeps edits fast and safe on memory

    static void open(Context c, Uri photo) {
        c.startActivity(new Intent(c, PhotoEditActivity.class).putExtra(EXTRA_URI, photo.toString())
                .addFlags(c instanceof Activity ? 0 : Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    private TextView text(float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    // ------------------------------------------------------------------ state

    private Bitmap base;                              // the committed image so far
    private final Deque<Bitmap> undo = new ArrayDeque<>();
    private ImageView preview;
    private FrameLayout stage, panelHost;
    private LinearLayout tools;
    private int tool = -1;
    private static final int T_CROP = 0, T_ROTATE = 1, T_ADJUST = 2, T_FILTER = 3, T_DRAW = 4, T_TEXT = 5;

    @Override
    protected void onCreate(Bundle b) {
        Ui.apply(this);
        super.onCreate(b);
        Uri uri = Uri.parse(getIntent().getStringExtra(EXTRA_URI));
        base = loadScaled(uri);
        if (base == null) {
            UiKit.makeToast(this, "⚠ " + T.t("Can't open this picture"), Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(0xFF000000);
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(10), dp(10), dp(10), dp(6));
        head.addView(UiKit.darkButton(this, "arrow-left", 40, false, v -> confirmExit()), new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView undoBtn = iconText("↩", v -> onUndo());
        head.addView(undoBtn, new LinearLayout.LayoutParams(dp(40), dp(40)));
        head.addView(new View(this), new LinearLayout.LayoutParams(0, -2, 1));
        TextView save = text(15, Color.WHITE, true);
        save.setText("💾  " + T.t("Save"));
        save.setGravity(Gravity.CENTER);
        save.setPadding(dp(18), 0, dp(18), 0);
        GradientDrawable sg = new GradientDrawable();
        sg.setColor(Ui.ACCENT);
        sg.setCornerRadius(dp(20));
        save.setBackground(sg);
        save.setOnClickListener(v -> save());
        head.addView(save, new LinearLayout.LayoutParams(-2, dp(40)));
        outer.addView(head);

        stage = new FrameLayout(this);
        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        stage.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        outer.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));

        panelHost = new FrameLayout(this);
        outer.addView(panelHost, new LinearLayout.LayoutParams(-1, -2));

        tools = new LinearLayout(this);
        tools.setGravity(Gravity.CENTER);
        tools.setPadding(0, dp(6), 0, dp(10));
        String[][] t = {{"crop", "Crop"}, {"rotate-cw", "Rotate"}, {"sliders-horizontal", "Adjust"},
                {"sparkles", "Filters"}, {"pencil", "Draw"}, {"T", "Text"}};
        for (int i = 0; i < t.length; i++) {
            final int k = i;
            tools.addView(toolButton(t[i][0], t[i][1], v -> selectTool(k)), new LinearLayout.LayoutParams(0, -2, 1));
        }
        outer.addView(tools);
        frame.addView(outer);
        setContentView(frame);
        showPreview();
    }

    private TextView iconText(String s, View.OnClickListener l) {
        TextView t = text(18, Color.WHITE, true);
        t.setText(s);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.focusCard(this, dp(20)));
        t.setOnClickListener(l);
        return t;
    }

    private View toolButton(String icon, String label, View.OnClickListener l) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setPadding(0, dp(8), 0, dp(8));
        col.setFocusable(true);
        col.setClickable(true);
        col.setOnClickListener(l);
        if ("T".equals(icon)) {
            TextView tt = text(18, Color.WHITE, true);
            tt.setText("T");
            tt.setGravity(Gravity.CENTER);
            col.addView(tt, new LinearLayout.LayoutParams(dp(26), dp(26)));
        } else {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(new UiKit.Icon(this, icon, 22, Color.WHITE));
            col.addView(iv, new LinearLayout.LayoutParams(dp(26), dp(26)));
        }
        TextView lb = text(11, 0xCCFFFFFF, false);
        lb.setText(T.t(label));
        lb.setPadding(0, dp(4), 0, 0);
        col.addView(lb);
        return col;
    }

    private void showPreview() {
        preview.setColorFilter(null);
        preview.setImageBitmap(base);
    }

    private void pushUndo() {
        undo.push(base.copy(base.getConfig(), true));
        if (undo.size() > 15) undo.removeLast();
    }

    private void onUndo() {
        if (undo.isEmpty()) {
            UiKit.makeToast(this, T.t("Nothing to undo"), Toast.LENGTH_SHORT).show();
            return;
        }
        base = undo.pop();
        showPreview();
    }

    private void confirmExit() {
        ZhPopup.confirm(this, "✖", T.t("Discard this edit?"), T.t("Your changes won't be saved."), T.t("Discard"), true, this::finish);
    }

    @Override
    public void onBackPressed() {
        confirmExit();
    }

    // ------------------------------------------------------------------ tool switching

    private void selectTool(int k) {
        tool = k;
        panelHost.removeAllViews();
        removeCropOverlay();
        removeDrawOverlay();
        switch (k) {
            case T_CROP: showCropPanel(); break;
            case T_ROTATE: showRotatePanel(); break;
            case T_ADJUST: showAdjustPanel(); break;
            case T_FILTER: showFilterPanel(); break;
            case T_DRAW: showDrawPanel(); break;
            case T_TEXT: showTextPanel(); break;
        }
    }

    private LinearLayout panel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(16), dp(10), dp(16), 0);
        panelHost.addView(p, new FrameLayout.LayoutParams(-1, -2));
        return p;
    }

    private TextView pillBtn(String s, boolean primary, View.OnClickListener l) {
        TextView b = ZhPopup.button(this, s, primary, false);
        b.setOnClickListener(l);
        return b;
    }

    private void row(LinearLayout into, View... vs) {
        LinearLayout r = new LinearLayout(this);
        for (int i = 0; i < vs.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1);
            lp.setMargins(i == 0 ? 0 : dp(8), dp(8), 0, dp(4));
            r.addView(vs[i], lp);
        }
        into.addView(r);
    }

    // ------------------------------------------------------------------ crop

    private View cropOverlay;
    private final RectF cropRect = new RectF();
    private int cropTouch = -1;

    private void showCropPanel() {
        LinearLayout p = panel();
        addCropOverlay();
        row(p, pillBtn(T.t("Cancel"), false, v -> selectTool(T_CROP)),
                pillBtn("✓ " + T.t("Apply crop"), true, v -> applyCrop()));
    }

    private void addCropOverlay() {
        final View ov = new View(this) {
            @Override
            protected void onDraw(Canvas c) {
                Paint dim = new Paint();
                dim.setColor(0xAA000000);
                c.drawRect(0, 0, getWidth(), cropRect.top, dim);
                c.drawRect(0, cropRect.bottom, getWidth(), getHeight(), dim);
                c.drawRect(0, cropRect.top, cropRect.left, cropRect.bottom, dim);
                c.drawRect(cropRect.right, cropRect.top, getWidth(), cropRect.bottom, dim);
                Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
                line.setStyle(Paint.Style.STROKE);
                line.setStrokeWidth(dp(2));
                line.setColor(Color.WHITE);
                c.drawRect(cropRect, line);
                Paint handle = new Paint(Paint.ANTI_ALIAS_FLAG);
                handle.setColor(Ui.ACCENT);
                float hr = dp(7);
                for (float[] pt : new float[][]{{cropRect.left, cropRect.top}, {cropRect.right, cropRect.top},
                        {cropRect.left, cropRect.bottom}, {cropRect.right, cropRect.bottom}})
                    c.drawCircle(pt[0], pt[1], hr, handle);
            }
        };
        ov.post(() -> {
            float m = dp(24);
            cropRect.set(m, m, ov.getWidth() - m, ov.getHeight() - m);
            ov.invalidate();
        });
        ov.setOnTouchListener((v, e) -> {
            float x = e.getX(), y = e.getY(), near = dp(28);
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    if (close(x, cropRect.left) && close(y, cropRect.top)) cropTouch = 0;
                    else if (close(x, cropRect.right) && close(y, cropRect.top)) cropTouch = 1;
                    else if (close(x, cropRect.left) && close(y, cropRect.bottom)) cropTouch = 2;
                    else if (close(x, cropRect.right) && close(y, cropRect.bottom)) cropTouch = 3;
                    else if (cropRect.contains(x, y)) cropTouch = 4;
                    else cropTouch = -1;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (cropTouch == 4) {
                        // recentre the rect on the finger, clamped to stay inside the view
                        float w = cropRect.width(), h = cropRect.height();
                        float nx = Math.max(0, Math.min(v.getWidth() - w, x - w / 2));
                        float ny = Math.max(0, Math.min(v.getHeight() - h, y - h / 2));
                        cropRect.set(nx, ny, nx + w, ny + h);
                    } else if (cropTouch == 0) {
                        cropRect.left = Math.min(x, cropRect.right - dp(40));
                        cropRect.top = Math.min(y, cropRect.bottom - dp(40));
                    } else if (cropTouch == 1) {
                        cropRect.right = Math.max(x, cropRect.left + dp(40));
                        cropRect.top = Math.min(y, cropRect.bottom - dp(40));
                    } else if (cropTouch == 2) {
                        cropRect.left = Math.min(x, cropRect.right - dp(40));
                        cropRect.bottom = Math.max(y, cropRect.top + dp(40));
                    } else if (cropTouch == 3) {
                        cropRect.right = Math.max(x, cropRect.left + dp(40));
                        cropRect.bottom = Math.max(y, cropRect.top + dp(40));
                    }
                    v.invalidate();
                    return true;
            }
            return false;
        });
        cropOverlay = ov;
        stage.addView(ov, new FrameLayout.LayoutParams(-1, -1));
    }

    private boolean close(float a, float b) {
        return Math.abs(a - b) < dp(30);
    }

    private void removeCropOverlay() {
        if (cropOverlay != null) stage.removeView(cropOverlay);
        cropOverlay = null;
    }

    private void applyCrop() {
        // map the overlay rect (which is drawn over the FIT_CENTER-scaled preview) back onto the real bitmap
        int ow = cropOverlay.getWidth(), oh = cropOverlay.getHeight();
        float scale = Math.min((float) ow / base.getWidth(), (float) oh / base.getHeight());
        float dispW = base.getWidth() * scale, dispH = base.getHeight() * scale;
        float offX = (ow - dispW) / 2f, offY = (oh - dispH) / 2f;
        int x = Math.round((cropRect.left - offX) / scale), y = Math.round((cropRect.top - offY) / scale);
        int w = Math.round(cropRect.width() / scale), h = Math.round(cropRect.height() / scale);
        x = Math.max(0, Math.min(x, base.getWidth() - 1));
        y = Math.max(0, Math.min(y, base.getHeight() - 1));
        w = Math.max(10, Math.min(w, base.getWidth() - x));
        h = Math.max(10, Math.min(h, base.getHeight() - y));
        pushUndo();
        base = Bitmap.createBitmap(base, x, y, w, h);
        showPreview();
        selectTool(T_CROP);
    }

    // ------------------------------------------------------------------ rotate / straighten

    private void showRotatePanel() {
        LinearLayout p = panel();
        row(p, pillBtn("↺ 90°", false, v -> rotate(-90)), pillBtn("↻ 90°", false, v -> rotate(90)));
        p.addView(label(T.t("Straighten")));
        final UiKit.PillSlider s = new UiKit.PillSlider(this, "sliders-horizontal", -450, 450, false);
        s.setValue(0);
        s.setListener((val, done) -> previewStraighten(val / 10f));
        p.addView(s, new LinearLayout.LayoutParams(-1, dp(50)));
        row(p, pillBtn(T.t("Cancel"), false, v -> {
            showPreview();
            selectTool(T_ROTATE);
        }), pillBtn("✓ " + T.t("Apply"), true, v -> applyStraighten(s.getValue() / 10f)));
    }

    private void rotate(int deg) {
        pushUndo();
        Matrix m = new Matrix();
        m.postRotate(deg);
        base = Bitmap.createBitmap(base, 0, 0, base.getWidth(), base.getHeight(), m, true);
        showPreview();
    }

    private void previewStraighten(float deg) {
        Matrix m = new Matrix();
        m.postRotate(deg);
        try {
            Bitmap b = Bitmap.createBitmap(base, 0, 0, base.getWidth(), base.getHeight(), m, true);
            preview.setImageBitmap(b);
        } catch (Exception ignored) {
        }
    }

    private void applyStraighten(float deg) {
        if (Math.abs(deg) < 0.05f) {
            showPreview();
            return;
        }
        pushUndo();
        Matrix m = new Matrix();
        m.postRotate(deg);
        Bitmap rotated = Bitmap.createBitmap(base, 0, 0, base.getWidth(), base.getHeight(), m, true);
        // crop back to the original aspect so corners don't show empty space
        double rad = Math.toRadians(Math.abs(deg));
        double wCrop = base.getWidth() * Math.cos(rad) - base.getHeight() * Math.sin(rad);
        double hCrop = base.getHeight() * Math.cos(rad) - base.getWidth() * Math.sin(rad);
        int cw = (int) Math.max(20, Math.min(rotated.getWidth(), wCrop));
        int ch = (int) Math.max(20, Math.min(rotated.getHeight(), hCrop));
        int ox = (rotated.getWidth() - cw) / 2, oy = (rotated.getHeight() - ch) / 2;
        base = Bitmap.createBitmap(rotated, Math.max(0, ox), Math.max(0, oy), cw, ch);
        showPreview();
    }

    private TextView label(String s) {
        TextView t = text(12, Ui.DIM, true);
        t.setText(s.toUpperCase(java.util.Locale.getDefault()));
        t.setPadding(0, dp(12), 0, dp(6));
        return t;
    }

    // ------------------------------------------------------------------ adjust (brightness / contrast / saturation)

    private int adjBr, adjCo, adjSa;

    private void showAdjustPanel() {
        LinearLayout p = panel();
        adjBr = adjCo = adjSa = 0;
        p.addView(label(T.t("Brightness")));
        UiKit.PillSlider br = new UiKit.PillSlider(this, "sun", -100, 100, false);
        br.setValue(0);
        br.setListener((v, d) -> {
            adjBr = v;
            previewAdjust();
        });
        p.addView(br, new LinearLayout.LayoutParams(-1, dp(48)));
        p.addView(label(T.t("Contrast")));
        UiKit.PillSlider co = new UiKit.PillSlider(this, "sliders-horizontal", -100, 100, false);
        co.setValue(0);
        co.setListener((v, d) -> {
            adjCo = v;
            previewAdjust();
        });
        p.addView(co, new LinearLayout.LayoutParams(-1, dp(48)));
        p.addView(label(T.t("Saturation")));
        UiKit.PillSlider sa = new UiKit.PillSlider(this, "palette", -100, 100, false);
        sa.setValue(0);
        sa.setListener((v, d) -> {
            adjSa = v;
            previewAdjust();
        });
        p.addView(sa, new LinearLayout.LayoutParams(-1, dp(48)));
        row(p, pillBtn(T.t("Cancel"), false, v -> {
            showPreview();
            selectTool(T_ADJUST);
        }), pillBtn("✓ " + T.t("Apply"), true, v -> applyAdjust()));
    }

    private ColorMatrix adjustMatrix() {
        ColorMatrix cm = new ColorMatrix();
        float sat = 1f + adjSa / 100f;
        ColorMatrix satM = new ColorMatrix();
        satM.setSaturation(Math.max(0, sat));
        cm.postConcat(satM);
        float contrast = 1f + adjCo / 100f;
        float brightness = adjBr * 1.5f;
        float t = (1f - contrast) * 128f + brightness;
        ColorMatrix coM = new ColorMatrix(new float[]{
                contrast, 0, 0, 0, t,
                0, contrast, 0, 0, t,
                0, 0, contrast, 0, t,
                0, 0, 0, 1, 0});
        cm.postConcat(coM);
        return cm;
    }

    private void previewAdjust() {
        preview.setColorFilter(new ColorMatrixColorFilter(adjustMatrix()));
    }

    private void applyAdjust() {
        pushUndo();
        Bitmap out = Bitmap.createBitmap(base.getWidth(), base.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(new ColorMatrixColorFilter(adjustMatrix()));
        c.drawBitmap(base, 0, 0, p);
        base = out;
        preview.setColorFilter(null);
        showPreview();
    }

    // ------------------------------------------------------------------ filters

    private void showFilterPanel() {
        LinearLayout p = panel();
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        String[] names = {"None", "B&W", "Sepia", "Warm", "Cool", "Vintage", "Invert"};
        for (String n : names) {
            TextView b = pillBtn(T.t(n), false, null);
            final String fn = n;
            b.setOnClickListener(v -> applyFilter(fn));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(42));
            lp.setMargins(0, 0, dp(8), 0);
            row.addView(b, lp);
        }
        hs.addView(row);
        p.addView(hs, new LinearLayout.LayoutParams(-1, dp(56)));
    }

    private ColorMatrix filterMatrix(String name) {
        switch (name) {
            case "B&W":
                ColorMatrix g = new ColorMatrix();
                g.setSaturation(0);
                return g;
            case "Sepia":
                return new ColorMatrix(new float[]{
                        0.393f, 0.769f, 0.189f, 0, 0,
                        0.349f, 0.686f, 0.168f, 0, 0,
                        0.272f, 0.534f, 0.131f, 0, 0,
                        0, 0, 0, 1, 0});
            case "Warm":
                return new ColorMatrix(new float[]{
                        1.12f, 0, 0, 0, 8,
                        0, 1.02f, 0, 0, 0,
                        0, 0, 0.88f, 0, -6,
                        0, 0, 0, 1, 0});
            case "Cool":
                return new ColorMatrix(new float[]{
                        0.9f, 0, 0, 0, -4,
                        0, 1.0f, 0, 0, 0,
                        0, 0, 1.15f, 0, 10,
                        0, 0, 0, 1, 0});
            case "Vintage": {
                ColorMatrix m = new ColorMatrix(new float[]{
                        0.9f, 0.1f, 0, 0, 10,
                        0.05f, 0.9f, 0.05f, 0, 5,
                        0.1f, 0.1f, 0.75f, 0, -5,
                        0, 0, 0, 1, 0});
                ColorMatrix desat = new ColorMatrix();
                desat.setSaturation(0.75f);
                m.postConcat(desat);
                return m;
            }
            case "Invert":
                return new ColorMatrix(new float[]{
                        -1, 0, 0, 0, 255,
                        0, -1, 0, 0, 255,
                        0, 0, -1, 0, 255,
                        0, 0, 0, 1, 0});
            default:
                return new ColorMatrix();
        }
    }

    private void applyFilter(String name) {
        pushUndo();
        Bitmap out = Bitmap.createBitmap(base.getWidth(), base.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(new ColorMatrixColorFilter(filterMatrix(name)));
        c.drawBitmap(base, 0, 0, p);
        base = out;
        showPreview();
    }

    // ------------------------------------------------------------------ draw

    private View drawOverlay;
    private Bitmap drawLayer;
    private Canvas drawCanvas;
    private int drawColor = 0xFFEF4444, drawWidth = 10;
    private float lastX, lastY;

    private void showDrawPanel() {
        LinearLayout p = panel();
        addDrawOverlay();
        row(p, swatch(), pillBtn(T.t("Undo stroke"), false, v -> {
            undoDraw();
        }));
        UiKit.PillSlider sz = new UiKit.PillSlider(this, "pencil", 2, 40, false);
        sz.setValue(drawWidth);
        sz.setListener((v, d) -> drawWidth = v);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(48));
        lp.topMargin = dp(8);
        p.addView(sz, lp);
        row(p, pillBtn(T.t("Cancel"), false, v -> {
            removeDrawOverlay();
            showPreview();
            selectTool(T_DRAW);
        }), pillBtn("✓ " + T.t("Done"), true, v -> applyDraw()));
    }

    private View swatch() {
        View sw = new View(this);
        sw.setBackground(Ui.card(this, drawColor, 0xFFAAAAAA, dp(20)));
        sw.setOnClickListener(v -> ColorPick.pick(this, drawColor, c -> {
            drawColor = c;
            sw.setBackground(Ui.card(this, drawColor, 0xFFAAAAAA, dp(20)));
        }));
        return sw;
    }

    private final List<Bitmap> drawUndo = new ArrayList<>();

    private void addDrawOverlay() {
        final View ov = new View(this) {
            @Override
            protected void onDraw(Canvas c) {
                if (drawLayer != null) c.drawBitmap(drawLayer, 0, 0, null);
            }
        };
        ov.post(() -> {
            if (drawLayer == null || drawLayer.getWidth() != ov.getWidth() || drawLayer.getHeight() != ov.getHeight()) {
                drawLayer = Bitmap.createBitmap(Math.max(1, ov.getWidth()), Math.max(1, ov.getHeight()), Bitmap.Config.ARGB_8888);
                drawCanvas = new Canvas(drawLayer);
            }
        });
        ov.setOnTouchListener((v, e) -> {
            float x = e.getX(), y = e.getY();
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    if (drawLayer != null) drawUndo.add(drawLayer.copy(drawLayer.getConfig(), true));
                    if (drawUndo.size() > 10) drawUndo.remove(0);
                    lastX = x;
                    lastY = y;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (drawCanvas != null) {
                        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                        p.setColor(drawColor);
                        p.setStrokeWidth(drawWidth);
                        p.setStyle(Paint.Style.STROKE);
                        p.setStrokeCap(Paint.Cap.ROUND);
                        p.setStrokeJoin(Paint.Join.ROUND);
                        drawCanvas.drawLine(lastX, lastY, x, y, p);
                        lastX = x;
                        lastY = y;
                        v.invalidate();
                    }
                    return true;
            }
            return false;
        });
        drawOverlay = ov;
        stage.addView(ov, new FrameLayout.LayoutParams(-1, -1));
    }

    private void undoDraw() {
        if (drawUndo.isEmpty() || drawLayer == null) return;
        drawLayer = drawUndo.remove(drawUndo.size() - 1);
        drawCanvas = new Canvas(drawLayer);
        drawOverlay.invalidate();
    }

    private void removeDrawOverlay() {
        if (drawOverlay != null) stage.removeView(drawOverlay);
        drawOverlay = null;
        drawLayer = null;
        drawCanvas = null;
        drawUndo.clear();
    }

    private void applyDraw() {
        if (drawLayer == null) {
            removeDrawOverlay();
            return;
        }
        pushUndo();
        // the overlay is the same size as the FIT_CENTER preview box — scale it onto the real bitmap
        int ow = drawOverlay.getWidth(), oh = drawOverlay.getHeight();
        Bitmap out = Bitmap.createBitmap(base.getWidth(), base.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawBitmap(base, 0, 0, null);
        float scale = Math.min((float) ow / base.getWidth(), (float) oh / base.getHeight());
        float dispW = base.getWidth() * scale, dispH = base.getHeight() * scale;
        float offX = (ow - dispW) / 2f, offY = (oh - dispH) / 2f;
        Matrix m = new Matrix();
        m.postTranslate(-offX, -offY);
        m.postScale(1f / scale, 1f / scale);
        c.drawBitmap(drawLayer, m, new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
        base = out;
        removeDrawOverlay();
        showPreview();
        selectTool(T_DRAW);
    }

    // ------------------------------------------------------------------ text

    private final List<TextView> liveTexts = new ArrayList<>();

    private void showTextPanel() {
        LinearLayout p = panel();
        row(p, pillBtn("+ " + T.t("Add text"), true, v -> addTextDialog()));
        if (!liveTexts.isEmpty()) row(p, pillBtn("✓ " + T.t("Done — place on photo"), false, v -> bakeTexts()));
    }

    private void addTextDialog() {
        final EditText e = new EditText(this);
        e.setHint(T.t("Type something…"));
        new UiDialog(this).setTitle(T.t("Add text")).setView(e)
                .setPositiveButton(T.t("Add"), (d, w) -> {
                    String s = e.getText().toString().trim();
                    if (s.isEmpty()) return;
                    addDraggableText(s, 0xFFFFFFFF);
                    selectTool(T_TEXT);
                }).setNegativeButton(T.t("Cancel"), null).show();
    }

    private void addDraggableText(String s, int color) {
        TextView tv = text(26, color, true);
        tv.setText(s);
        tv.setShadowLayer(dp(3), 0, 0, 0x99000000);
        tv.setPadding(dp(6), dp(6), dp(6), dp(6));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        final float[] start = new float[2];
        tv.setOnTouchListener((v, e) -> {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    start[0] = e.getRawX();
                    start[1] = e.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - start[0], dy = e.getRawY() - start[1];
                    start[0] = e.getRawX();
                    start[1] = e.getRawY();
                    FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) v.getLayoutParams();
                    p.leftMargin += dx;
                    p.topMargin += dy;
                    v.setLayoutParams(p);
                    return true;
            }
            return false;
        });
        tv.setOnLongClickListener(v -> {
            stage.removeView(tv);
            liveTexts.remove(tv);
            return true;
        });
        stage.addView(tv, lp);
        liveTexts.add(tv);
    }

    private void bakeTexts() {
        if (liveTexts.isEmpty()) return;
        pushUndo();
        Bitmap out = Bitmap.createBitmap(base.getWidth(), base.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawBitmap(base, 0, 0, null);
        float scale = Math.min((float) stage.getWidth() / base.getWidth(), (float) stage.getHeight() / base.getHeight());
        float dispW = base.getWidth() * scale, dispH = base.getHeight() * scale;
        float offX = (stage.getWidth() - dispW) / 2f, offY = (stage.getHeight() - dispH) / 2f;
        for (TextView tv : new ArrayList<>(liveTexts)) {
            float x = (tv.getLeft() - offX) / scale, y = (tv.getTop() - offY) / scale;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(tv.getCurrentTextColor());
            p.setTextSize(tv.getTextSize() / scale);
            p.setTypeface(tv.getTypeface());
            p.setShadowLayer(dp(3) / scale, 0, 0, 0x99000000);
            c.drawText(tv.getText().toString(), x + dp(6) / scale, y + p.getTextSize(), p);
            stage.removeView(tv);
        }
        liveTexts.clear();
        base = out;
        showPreview();
        selectTool(T_TEXT);
    }

    // ------------------------------------------------------------------ load / save

    private Bitmap loadScaled(Uri uri) {
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                android.graphics.BitmapFactory.decodeStream(in, null, o);
            }
            int s = 1;
            while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= MAX_SIDE) s *= 2;
            android.graphics.BitmapFactory.Options o2 = new android.graphics.BitmapFactory.Options();
            o2.inSampleSize = s;
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                Bitmap b = android.graphics.BitmapFactory.decodeStream(in, null, o2);
                return b == null ? null : b.copy(Bitmap.Config.ARGB_8888, true);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private void save() {
        if (!liveTexts.isEmpty()) bakeTexts();
        try {
            String name = "ZHPlay_edit_" + System.currentTimeMillis() + ".jpg";
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            if (Build.VERSION.SDK_INT >= 29) {
                cv.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ZH Play");
                cv.put(MediaStore.Images.Media.IS_PENDING, 1);
            }
            Uri out = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (out == null) throw new Exception("insert failed");
            try (java.io.OutputStream os = getContentResolver().openOutputStream(out)) {
                base.compress(Bitmap.CompressFormat.JPEG, 92, os);
            }
            if (Build.VERSION.SDK_INT >= 29) {
                cv.clear();
                cv.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(out, cv, null, null);
            }
            UiKit.makeToast(this, "✓ " + T.t("Saved to Pictures / ZH Play"), Toast.LENGTH_LONG).show();
            finish();
        } catch (Exception e) {
            UiKit.makeToast(this, "⚠ " + T.t("Couldn't save") + ": " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

}
