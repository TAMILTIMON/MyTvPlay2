package com.mytv.player;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.app.RecoverableSecurityException;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 🖼 Gallery: every photo on the phone in a fast grid (only visible cells load), albums along the top,
 * and a full-screen viewer — swipe between photos, pinch / double-tap to zoom — with Edit, Share,
 * Info and Delete. Camera photos are shown the right way up.
 */
public class GalleryActivity extends Activity {
    private static final int REQ_PERM = 70, REQ_DELETE = 71, REQ_EDIT = 72;

    static void open(Context c) {
        Intent i = new Intent(c, GalleryActivity.class);
        if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }

    /** One photo, with what the gallery shows about it. */
    static final class Photo {
        final long id;
        final Uri uri;
        final String name, album;
        final int orientation, width, height;
        final long size, date;

        Photo(long id, Uri uri, String name, String album, int orientation, int width, int height, long size, long date) {
            this.id = id;
            this.uri = uri;
            this.name = name == null ? "" : name;
            this.album = album == null || album.isEmpty() ? "Other" : album;
            this.orientation = orientation;
            this.width = width;
            this.height = height;
            this.size = size;
            this.date = date;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService thumbs = Executors.newFixedThreadPool(3);
    private final ExecutorService fulls = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> thumbCache = new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 8)) {
        @Override
        protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount();
        }
    };
    private final LruCache<String, Bitmap> fullCache = new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 6)) {
        @Override
        protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount();
        }
    };
    private final Set<String> loadingFull = new HashSet<>();

    private final List<Photo> all = new ArrayList<>();
    private final List<Photo> shown = new ArrayList<>();
    private String album = null;                    // null = all photos

    private FrameLayout root;
    private GridView grid;
    private GridAdapter adapter;
    private LinearLayout chips;
    private TextView subtitle, emptyLabel;
    private boolean needsReload;

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

    // ================================================================== screen

    @Override
    protected void onCreate(Bundle b) {
        Ui.apply(this);
        super.onCreate(b);
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(12), dp(12), dp(12), dp(6));
        head.addView(UiKit.darkButton(this, "arrow-left", 42, false, v -> finish()), new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(dp(12), 0, 0, 0);
        TextView title = text(21, Ui.TEXT, true);
        title.setText("🖼  " + T.t("Gallery"));
        subtitle = text(12, Ui.DIM, false);
        names.addView(title);
        names.addView(subtitle);
        head.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        col.addView(head);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        chips = new LinearLayout(this);
        chips.setPadding(dp(12), dp(2), dp(12), dp(8));
        hs.addView(chips);
        col.addView(hs);

        grid = new GridView(this);
        grid.setNumColumns(columns());
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(dp(2));
        grid.setVerticalSpacing(dp(2));
        grid.setPadding(dp(2), 0, dp(2), dp(16));
        grid.setClipToPadding(false);
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        adapter = new GridAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> openViewer(pos));
        col.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(col);

        emptyLabel = text(15, Ui.DIM, false);
        emptyLabel.setGravity(Gravity.CENTER);
        emptyLabel.setPadding(dp(30), 0, dp(30), 0);
        emptyLabel.setText(T.t("Loading your photos…"));
        root.addView(emptyLabel, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

        setContentView(root);
        if (hasPermission()) load();
        else askPermission();
    }

    private int columns() {
        int w = getResources().getDisplayMetrics().widthPixels;
        return Math.max(3, w / dp(118));          // 3 on a phone, more on a tablet / in landscape
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        if (grid != null) grid.setNumColumns(columns());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (needsReload && hasPermission()) {
            needsReload = false;
            load();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        thumbs.shutdownNow();
        fulls.shutdownNow();
    }

    // ================================================================== permission

    private String permission() {
        return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private boolean hasPermission() {
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(permission()) == PackageManager.PERMISSION_GRANTED;
    }

    private void askPermission() {
        if (Build.VERSION.SDK_INT >= 23) requestPermissions(new String[]{permission()}, REQ_PERM);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != REQ_PERM) return;
        if (hasPermission()) {
            load();
        } else {
            emptyLabel.setVisibility(View.VISIBLE);
            emptyLabel.setText(T.t("ZH Play needs permission to show your photos.") + "\n\n" + T.t("Tap here to allow it in Settings."));
            emptyLabel.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
                    needsReload = true;
                } catch (Exception ignored) {
                }
            });
        }
    }

    // ================================================================== loading the list

    private void load() {
        new Thread(() -> {
            final List<Photo> found = new ArrayList<>();
            String[] proj = {MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.BUCKET_DISPLAY_NAME, MediaStore.Images.Media.ORIENTATION,
                    MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT,
                    MediaStore.Images.Media.SIZE, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED};
            try (Cursor c = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, null, null,
                    MediaStore.Images.Media.DATE_ADDED + " DESC")) {
                if (c != null) {
                    int iId = c.getColumnIndex(MediaStore.Images.Media._ID), iName = c.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME),
                            iAlb = c.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME), iOr = c.getColumnIndex(MediaStore.Images.Media.ORIENTATION),
                            iW = c.getColumnIndex(MediaStore.Images.Media.WIDTH), iH = c.getColumnIndex(MediaStore.Images.Media.HEIGHT),
                            iSz = c.getColumnIndex(MediaStore.Images.Media.SIZE), iTk = c.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN),
                            iAd = c.getColumnIndex(MediaStore.Images.Media.DATE_ADDED);
                    while (c.moveToNext()) {
                        long id = c.getLong(iId);
                        long taken = iTk >= 0 ? c.getLong(iTk) : 0;
                        long added = iAd >= 0 ? c.getLong(iAd) * 1000L : 0;
                        found.add(new Photo(id, ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                                iName >= 0 ? c.getString(iName) : "", iAlb >= 0 ? c.getString(iAlb) : "",
                                iOr >= 0 ? c.getInt(iOr) : 0, iW >= 0 ? c.getInt(iW) : 0, iH >= 0 ? c.getInt(iH) : 0,
                                iSz >= 0 ? c.getLong(iSz) : 0, taken > 0 ? taken : added));
                    }
                }
            } catch (Exception ignored) {
            }
            main.post(() -> {
                all.clear();
                all.addAll(found);
                buildChips();
                applyAlbum(album);
            });
        }).start();
    }

    private void buildChips() {
        chips.removeAllViews();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Photo p : all) counts.put(p.album, counts.containsKey(p.album) ? counts.get(p.album) + 1 : 1);
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        java.util.Collections.sort(sorted, (a, b) -> b.getValue() - a.getValue());
        addChip(T.t("All photos") + "  " + all.size(), null);
        for (Map.Entry<String, Integer> e : sorted) addChip(e.getKey() + "  " + e.getValue(), e.getKey());
    }

    private void addChip(String label, final String albumName) {
        boolean on = albumName == null ? album == null : albumName.equals(album);
        TextView c = text(13, on ? Color.WHITE : Ui.TEXT, true);
        c.setText(label);
        c.setPadding(dp(14), dp(8), dp(14), dp(8));
        c.setBackground(on ? Ui.gradient(this, 18) : Ui.focusCard(this, 18));
        c.setFocusable(true);
        c.setOnClickListener(v -> {
            applyAlbum(albumName);
            buildChips();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(0, 0, dp(8), 0);
        chips.addView(c, lp);
    }

    private void applyAlbum(String albumName) {
        album = albumName;
        shown.clear();
        for (Photo p : all) if (album == null || album.equals(p.album)) shown.add(p);
        adapter.notifyDataSetChanged();
        subtitle.setText(shown.size() + " " + T.t("photos") + (album == null ? "" : "  ·  " + album));
        emptyLabel.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        if (shown.isEmpty()) emptyLabel.setText(T.t("No photos here yet."));
        emptyLabel.setOnClickListener(null);
    }

    // ================================================================== decoding (right way up, sized to fit)

    private static Bitmap rotate(Bitmap b, int degrees) {
        if (b == null || degrees % 360 == 0) return b;
        Matrix m = new Matrix();
        m.postRotate(degrees);
        Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        if (r != b) b.recycle();
        return r;
    }

    private Bitmap decode(Photo p, int maxSide) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(p.uri)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            int s = 1;
            while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = s;
            try (InputStream in = getContentResolver().openInputStream(p.uri)) {
                return rotate(BitmapFactory.decodeStream(in, null, o2), p.orientation);
            }
        } catch (Throwable e) {
            return null;
        }
    }

    private Bitmap decodeThumb(Photo p) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                // the system thumbnail is already the right way up
                return getContentResolver().loadThumbnail(p.uri, new android.util.Size(320, 320), null);
            } catch (Throwable ignored) {
            }
        }
        return decode(p, 320);
    }

    // ================================================================== grid

    /** A square cell. */
    private static final class SquareImage extends ImageView {
        SquareImage(Context c) {
            super(c);
        }

        @Override
        protected void onMeasure(int w, int h) {
            super.onMeasure(w, w);
        }
    }

    private final class GridAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int i) {
            return shown.get(i);
        }

        @Override
        public long getItemId(int i) {
            return shown.get(i).id;
        }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            final SquareImage iv = convert instanceof SquareImage ? (SquareImage) convert : new SquareImage(GalleryActivity.this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundColor(Ui.dark() ? 0xFF1A1F2E : 0xFFE5E7EB);
            final Photo p = shown.get(pos);
            final String key = p.uri.toString();
            iv.setTag(key);
            Bitmap hit = thumbCache.get(key);
            if (hit != null) {
                iv.setImageBitmap(hit);
            } else {
                iv.setImageDrawable(null);
                thumbs.execute(() -> {
                    if (!key.equals(iv.getTag())) return;            // scrolled away before we started
                    Bitmap b = decodeThumb(p);
                    if (b == null) return;
                    thumbCache.put(key, b);
                    main.post(() -> {
                        if (key.equals(iv.getTag())) iv.setImageBitmap(b);
                    });
                });
            }
            return iv;
        }
    }

    // ================================================================== viewer

    private FrameLayout viewer;
    private PhotoPager pager;
    private LinearLayout topBar, bottomBar;
    private TextView counter;
    private boolean barsShown = true;

    private void openViewer(int pos) {
        if (shown.isEmpty()) return;
        viewer = new FrameLayout(this);
        viewer.setBackgroundColor(Color.BLACK);
        pager = new PhotoPager(this);
        viewer.addView(pager, new FrameLayout.LayoutParams(-1, -1));

        topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(10), dp(10), dp(10), dp(10));
        topBar.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xAA000000, 0x00000000}));
        topBar.addView(UiKit.darkButton(this, "arrow-left", 42, false, v -> closeViewer()), new LinearLayout.LayoutParams(dp(42), dp(42)));
        counter = text(15, Color.WHITE, true);
        counter.setPadding(dp(12), 0, 0, 0);
        topBar.addView(counter, new LinearLayout.LayoutParams(0, -2, 1));
        viewer.addView(topBar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        bottomBar = new LinearLayout(this);
        bottomBar.setGravity(Gravity.CENTER);
        bottomBar.setPadding(dp(8), dp(12), dp(8), dp(16));
        bottomBar.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xCC000000, 0x00000000}));
        bottomBar.addView(action("pencil", T.t("Edit"), v -> editCurrent()), new LinearLayout.LayoutParams(0, -2, 1));
        bottomBar.addView(action("share-2", T.t("Share"), v -> shareCurrent()), new LinearLayout.LayoutParams(0, -2, 1));
        bottomBar.addView(action("info", T.t("Info"), v -> infoCurrent()), new LinearLayout.LayoutParams(0, -2, 1));
        bottomBar.addView(action("trash", T.t("Delete"), v -> deleteCurrent()), new LinearLayout.LayoutParams(0, -2, 1));
        viewer.addView(bottomBar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        pager.setSource(new PhotoPager.Source() {
            @Override
            public int count() {
                return shown.size();
            }

            @Override
            public Bitmap get(int i) {
                return full(i);
            }
        }, new PhotoPager.Listener() {
            @Override
            public void onPage(int i) {
                counter.setText((i + 1) + " / " + shown.size());
                full(i - 1);           // load the neighbours ahead of the swipe
                full(i + 1);
            }

            @Override
            public void onTap() {
                setBars(!barsShown);
            }
        });
        root.addView(viewer, new FrameLayout.LayoutParams(-1, -1));
        barsShown = true;
        pager.post(() -> pager.show(pos));
        pager.requestFocus();
    }

    private View action(String icon, String label, View.OnClickListener l) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER);
        c.setPadding(0, dp(6), 0, dp(6));
        c.setFocusable(true);
        c.setClickable(true);
        c.setOnClickListener(l);
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(new UiKit.Icon(this, icon, 22, Color.WHITE));
        c.addView(iv, new LinearLayout.LayoutParams(dp(26), dp(26)));
        TextView t = text(11, 0xDDFFFFFF, false);
        t.setText(label);
        t.setPadding(0, dp(4), 0, 0);
        c.addView(t);
        return c;
    }

    private void setBars(boolean on) {
        barsShown = on;
        float a = on ? 1f : 0f;
        topBar.animate().alpha(a).setDuration(160).start();
        bottomBar.animate().alpha(a).setDuration(160).start();
        topBar.setVisibility(View.VISIBLE);
        bottomBar.setVisibility(View.VISIBLE);
        if (!on) {
            topBar.postDelayed(() -> {
                if (!barsShown) {
                    topBar.setVisibility(View.GONE);
                    bottomBar.setVisibility(View.GONE);
                }
            }, 170);
        }
    }

    /** The viewer-sized bitmap for page i (cached), or null while it loads. */
    private Bitmap full(final int i) {
        if (i < 0 || i >= shown.size()) return null;
        final Photo p = shown.get(i);
        final String key = p.uri.toString();
        Bitmap hit = fullCache.get(key);
        if (hit != null) return hit;
        synchronized (loadingFull) {
            if (loadingFull.contains(key)) return thumbCache.get(key);
            loadingFull.add(key);
        }
        final int side = Math.max(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels);
        fulls.execute(() -> {
            Bitmap b = decode(p, side);
            synchronized (loadingFull) {
                loadingFull.remove(key);
            }
            if (b == null) return;
            fullCache.put(key, b);
            main.post(() -> {
                if (pager != null) pager.invalidate();
            });
        });
        return thumbCache.get(key);                // show the blurry thumbnail until the sharp one is ready
    }

    private void closeViewer() {
        if (viewer != null) root.removeView(viewer);
        viewer = null;
        pager = null;
    }

    private Photo current() {
        if (pager == null || shown.isEmpty()) return null;
        int i = pager.index();
        return i >= 0 && i < shown.size() ? shown.get(i) : null;
    }

    @Override
    public void onBackPressed() {
        if (viewer != null) closeViewer();
        else super.onBackPressed();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (pager != null) {
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                pager.step(-1);
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                pager.step(1);
                return true;
            }
        }
        return super.onKeyDown(code, e);
    }

    // ================================================================== actions

    private void editCurrent() {
        Photo p = current();
        if (p == null) return;
        needsReload = true;                         // a saved edit shows up when we come back
        PhotoEditActivity.open(this, p.uri);
    }

    private void shareCurrent() {
        Photo p = current();
        if (p == null) return;
        try {
            Intent s = new Intent(Intent.ACTION_SEND).setType("image/*").putExtra(Intent.EXTRA_STREAM, p.uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(s, T.t("Share photo")));
        } catch (Exception e) {
            UiKit.makeToast(this, "⚠ " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void infoCurrent() {
        Photo p = current();
        if (p == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append(T.t("Name")).append(": ").append(p.name).append("\n");
        sb.append(T.t("Album")).append(": ").append(p.album).append("\n");
        if (p.width > 0 && p.height > 0) {
            boolean turned = p.orientation % 180 != 0;
            sb.append(T.t("Size")).append(": ").append(turned ? p.height : p.width).append(" × ").append(turned ? p.width : p.height)
                    .append(String.format(Locale.ROOT, "  (%.1f MP)", p.width * (double) p.height / 1e6)).append("\n");
        }
        if (p.size > 0) sb.append(T.t("File size")).append(": ").append(human(p.size)).append("\n");
        if (p.date > 0) sb.append(T.t("Date")).append(": ").append(DateFormat.getDateTimeInstance().format(new Date(p.date)));
        ZhPopup.message(this, "ℹ", T.t("Photo details"), sb.toString().trim());
    }

    private static String human(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(Locale.ROOT, "%.0f KB", b / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", b / (1024.0 * 1024));
    }

    private Photo pendingDelete;

    private void deleteCurrent() {
        final Photo p = current();
        if (p == null) return;
        ZhPopup.confirm(this, "🗑", T.t("Delete this photo?"), p.name, T.t("Delete"), true, () -> {
            pendingDelete = p;
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    // Android asks the person to confirm (it's their photo, not ZH Play's)
                    PendingIntent pi = MediaStore.createDeleteRequest(getContentResolver(), java.util.Collections.singletonList(p.uri));
                    startIntentSenderForResult(pi.getIntentSender(), REQ_DELETE, null, 0, 0, 0);
                } else {
                    getContentResolver().delete(p.uri, null, null);
                    removeDeleted();
                }
            } catch (Exception e) {
                if (Build.VERSION.SDK_INT == 29 && e instanceof RecoverableSecurityException) {
                    try {
                        startIntentSenderForResult(((RecoverableSecurityException) e).getUserAction().getActionIntent().getIntentSender(),
                                REQ_DELETE, null, 0, 0, 0);
                        return;
                    } catch (Exception ignored) {
                    }
                }
                UiKit.makeToast(this, "⚠ " + T.t("Couldn't delete this photo"), Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_DELETE || pendingDelete == null) return;
        if (res != RESULT_OK) {
            pendingDelete = null;
            return;
        }
        if (Build.VERSION.SDK_INT == 29) {
            // Android 10: permission granted for this one photo — now actually delete it
            try {
                getContentResolver().delete(pendingDelete.uri, null, null);
            } catch (Exception e) {
                UiKit.makeToast(this, "⚠ " + T.t("Couldn't delete this photo"), Toast.LENGTH_LONG).show();
                pendingDelete = null;
                return;
            }
        }
        removeDeleted();
    }

    private void removeDeleted() {
        Photo p = pendingDelete;
        pendingDelete = null;
        if (p == null) return;
        int at = pager != null ? pager.index() : 0;
        all.remove(p);
        shown.remove(p);
        thumbCache.remove(p.uri.toString());
        fullCache.remove(p.uri.toString());
        adapter.notifyDataSetChanged();
        buildChips();
        subtitle.setText(shown.size() + " " + T.t("photos") + (album == null ? "" : "  ·  " + album));
        UiKit.makeToast(this, "🗑 " + T.t("Deleted"), Toast.LENGTH_SHORT).show();
        if (shown.isEmpty()) {
            closeViewer();
            applyAlbum(album);
        } else if (pager != null) {
            pager.show(Math.min(at, shown.size() - 1));
        }
    }
}
