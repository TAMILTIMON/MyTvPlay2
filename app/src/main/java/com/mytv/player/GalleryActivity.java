package com.mytv.player;

import android.Manifest;
import android.app.Activity;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.LruCache;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Your phone's photos: a grid, a full-screen swipe viewer, and a way into the editor. */
public class GalleryActivity extends Activity {
    private final List<Uri> photos = new ArrayList<>();
    private GridLayout grid;
    private TextView emptyLabel;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final LruCache<String, Bitmap> thumbCache = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount();
        }
    };

    static void open(android.content.Context c) {
        Intent i = new Intent(c, GalleryActivity.class);
        if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
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

    @Override
    protected void onCreate(Bundle b) {
        Ui.apply(this);
        super.onCreate(b);
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Ui.BG);
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(14), dp(14), dp(14), dp(8));
        head.addView(UiKit.darkButton(this, "arrow-left", 42, false, v -> finish()), new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView t = text(21, Ui.TEXT, true);
        t.setText("🖼  " + T.t("Gallery"));
        t.setPadding(dp(12), 0, 0, 0);
        head.addView(t);
        outer.addView(head);

        ScrollView sv = new ScrollView(this);
        grid = new GridLayout(this);
        grid.setColumnCount(3);
        grid.setPadding(dp(4), dp(4), dp(4), dp(24));
        sv.addView(grid);
        outer.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        frame.addView(outer);

        emptyLabel = text(14, Ui.DIM, false);
        emptyLabel.setGravity(Gravity.CENTER);
        emptyLabel.setText(T.t("Checking your photos…"));
        FrameLayout.LayoutParams elp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        frame.addView(emptyLabel, elp);

        setContentView(frame);

        if (needsPermission()) requestPermissions(new String[]{permissionName()}, 70);
        else load();
    }

    private String permissionName() {
        return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private boolean needsPermission() {
        return checkSelfPermission(permissionName()) != PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (needsPermission()) {
            emptyLabel.setText(T.t("Photo access is needed to show your gallery"));
        } else {
            load();
        }
    }

    private void load() {
        new Thread(() -> {
            final List<Uri> found = new ArrayList<>();
            String[] proj = {MediaStore.Images.Media._ID};
            try (Cursor c = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, null, null,
                    MediaStore.Images.Media.DATE_ADDED + " DESC")) {
                if (c != null) {
                    int idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                    while (c.moveToNext() && found.size() < 600) {
                        found.add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(idCol)));
                    }
                }
            } catch (Exception ignored) {
            }
            runOnUiThread(() -> {
                photos.clear();
                photos.addAll(found);
                fillGrid();
                emptyLabel.setVisibility(photos.isEmpty() ? View.VISIBLE : View.GONE);
                if (photos.isEmpty()) emptyLabel.setText(T.t("No photos found on this phone yet."));
            });
        }).start();
    }

    private void fillGrid() {
        grid.removeAllViews();
        int cell = (getResources().getDisplayMetrics().widthPixels - dp(8)) / 3;
        for (int i = 0; i < photos.size(); i++) {
            final Uri u = photos.get(i);
            final int idx = i;
            FrameLayout cellBox = new FrameLayout(this);
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundColor(Ui.dark() ? 0xFF1A1F2E : 0xFFE5E7EB);
            cellBox.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            cellBox.setClickable(true);
            cellBox.setFocusable(true);
            cellBox.setOnClickListener(v -> viewPhoto(idx));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = cell;
            lp.height = cell;
            lp.setMargins(dp(2), dp(2), dp(2), dp(2));
            grid.addView(cellBox, lp);
            loadThumb(u, iv);
        }
    }

    private void loadThumb(final Uri u, final ImageView iv) {
        iv.setTag(u.toString());
        Bitmap hit = thumbCache.get(u.toString());
        if (hit != null) {
            iv.setImageBitmap(hit);
            return;
        }
        pool.execute(() -> {
            Bitmap b = decodeThumb(u);
            if (b == null) return;
            thumbCache.put(u.toString(), b);
            runOnUiThread(() -> {
                if (u.toString().equals(iv.getTag())) iv.setImageBitmap(b);
            });
        });
    }

    private Bitmap decodeThumb(Uri u) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                return getContentResolver().loadThumbnail(u, new android.util.Size(200, 200), null);
            }
            try (java.io.InputStream in = getContentResolver().openInputStream(u)) {
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inSampleSize = 4;
                return android.graphics.BitmapFactory.decodeStream(in, null, o);
            }
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ full-screen viewer

    private int viewIndex;
    private ImageView bigView;
    private FrameLayout viewerRoot;

    private void viewPhoto(int index) {
        viewIndex = index;
        viewerRoot = new FrameLayout(this);
        viewerRoot.setBackgroundColor(0xFF000000);
        bigView = new ImageView(this);
        bigView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        viewerRoot.addView(bigView, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(10), dp(10), dp(10), dp(10));
        bar.addView(UiKit.darkButton(this, "arrow-left", 42, false, v -> closeViewer()), new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView edit = text(15, Color.WHITE, true);
        edit.setText("✏  " + T.t("Edit"));
        edit.setGravity(Gravity.CENTER);
        edit.setPadding(dp(18), 0, dp(18), 0);
        GradientDrawable eg = new GradientDrawable();
        eg.setColor(Ui.ACCENT);
        eg.setCornerRadius(dp(20));
        edit.setBackground(eg);
        edit.setOnClickListener(v -> PhotoEditActivity.open(this, photos.get(viewIndex)));
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-2, dp(42));
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(edit, elp);
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        viewerRoot.addView(bar, barLp);

        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null) return false;
                float dx = e2.getX() - e1.getX();
                if (Math.abs(dx) > dp(60) && Math.abs(vx) > 400) {
                    if (dx < 0 && viewIndex < photos.size() - 1) showAt(viewIndex + 1);
                    else if (dx > 0 && viewIndex > 0) showAt(viewIndex - 1);
                    return true;
                }
                return false;
            }
        });
        viewerRoot.setOnTouchListener((v, e) -> gd.onTouchEvent(e));

        ((FrameLayout) getWindow().getDecorView()).addView(viewerRoot, new FrameLayout.LayoutParams(-1, -1));
        showAt(index);
    }

    private void showAt(int i) {
        viewIndex = i;
        bigView.setImageDrawable(null);
        new Thread(() -> {
            Bitmap b = null;
            try (java.io.InputStream in = getContentResolver().openInputStream(photos.get(i))) {
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inSampleSize = 2;
                b = android.graphics.BitmapFactory.decodeStream(in, null, o);
            } catch (Exception ignored) {
            }
            final Bitmap fb = b;
            runOnUiThread(() -> {
                if (viewIndex == i && fb != null) bigView.setImageBitmap(fb);
            });
        }).start();
    }

    private void closeViewer() {
        if (viewerRoot != null) ((FrameLayout) getWindow().getDecorView()).removeView(viewerRoot);
        viewerRoot = null;
    }

    @Override
    public void onBackPressed() {
        if (viewerRoot != null) closeViewer();
        else super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!photos.isEmpty()) fillGrid();      // pick up a just-edited / newly saved photo
    }
}
