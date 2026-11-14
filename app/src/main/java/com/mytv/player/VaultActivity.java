package com.mytv.player;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.MimeTypeMap;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 🔒 Private folder: locked until unlocked; locks again as soon as you leave it. */
public class VaultActivity extends Activity {
    static void open(Context c) {
        Intent i = new Intent(c, VaultActivity.class);
        if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> thumbs = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount();
        }
    };
    private FrameLayout root;
    private LinearLayout body;
    private boolean unlocked, childOpen;

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
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);   // no screenshots, blank in Recent apps
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        setContentView(root);
        lockedView();
        askUnlock();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!childOpen) finish();                       // left the Private folder: lock it
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        childOpen = false;                              // back from one of its own files
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pool.shutdownNow();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        Vault.onResult(req, res);
    }

    private void askUnlock() {
        Vault.unlock(this, ok -> {
            if (ok) {
                unlocked = true;
                render();
            } else {
                finish();
            }
        });
    }

    private void lockedView() {
        root.removeAllViews();
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER);
        c.addView(UiKit.icon(this, "lock", 44, Ui.ACCENT), new LinearLayout.LayoutParams(dp(56), dp(56)));
        TextView t = text(18, Ui.TEXT, true);
        t.setText(T.t("Private folder"));
        t.setPadding(0, dp(14), 0, dp(6));
        c.addView(t);
        TextView u = text(14, Ui.ACCENT, true);
        u.setText(T.t("Unlock"));
        u.setPadding(dp(18), dp(10), dp(18), dp(10));
        u.setBackground(Ui.focusCard(this, 20));
        u.setOnClickListener(v -> askUnlock());
        c.addView(u);
        root.addView(c, new FrameLayout.LayoutParams(-1, -1));
    }

    // ================================================================== the list

    private void render() {
        if (!unlocked) return;
        root.removeAllViews();
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(12), dp(12), dp(12), dp(6));
        head.addView(UiKit.darkButton(this, "arrow-left", 42, false, v -> finish()), new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView t = text(21, Ui.TEXT, true);
        t.setText("🔒  " + T.t("Private folder"));
        t.setPadding(dp(12), 0, 0, 0);
        head.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(UiKit.darkButton(this, "settings", 42, false, v -> settings()), new LinearLayout.LayoutParams(dp(42), dp(42)));
        outer.addView(head);
        ScrollView sv = new ScrollView(this);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(14), dp(4), dp(14), dp(30));
        sv.addView(body);
        outer.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(outer);

        TextView note = text(12, Ui.DIM, false);
        note.setText(T.t("Hidden from every other app. Uninstalling ZH Play or clearing its data deletes these files — restore anything you need first."));
        note.setPadding(dp(4), 0, dp(4), dp(10));
        body.addView(note);

        final List<Vault.Item> items = Vault.list(this);
        if (items.isEmpty()) {
            TextView e = text(15, Ui.DIM, false);
            e.setText(T.t("Nothing here yet.") + "\n\n" + T.t("In Files, open a file's menu and tap “Move to Private folder”."));
            e.setGravity(Gravity.CENTER);
            e.setPadding(dp(20), dp(50), dp(20), dp(20));
            body.addView(e);
            TextView go = text(15, Ui.ACCENT, true);
            go.setText("📂  " + T.t("Open Files"));
            go.setGravity(Gravity.CENTER);
            go.setPadding(dp(20), dp(12), dp(20), dp(12));
            go.setBackground(Ui.focusCard(this, 20));
            go.setOnClickListener(v -> StorageActivity.open(this));
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-2, -2);
            glp.gravity = Gravity.CENTER_HORIZONTAL;
            body.addView(go, glp);
            return;
        }
        for (final Vault.Item it : items) body.addView(row(it, items));
    }

    private static String ext(String name) {
        int d = name.lastIndexOf('.');
        return d < 0 ? "" : name.substring(d + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean isImage(String n) {
        return ext(n).matches("jpg|jpeg|png|gif|webp|bmp|heic|heif");
    }

    private View row(final Vault.Item it, final List<Vault.Item> all) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(10), dp(10), dp(10), dp(10));
        r.setBackground(Ui.focusCard(this, 14));
        r.setFocusable(true);
        r.setClickable(true);
        FrameLayout pic = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.dark() ? 0xFF1A1F2E : 0xFFE5E7EB);
        bg.setCornerRadius(dp(10));
        pic.setBackground(bg);
        pic.setClipToOutline(true);
        String icon = isImage(it.name) ? "image" : Formats.isVideo(it.name) ? "film" : Formats.isAudio(it.name) ? "file-music" : "file";
        pic.addView(UiKit.icon(this, icon, 22, Ui.ACCENT), new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER));
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        pic.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        r.addView(pic, new LinearLayout.LayoutParams(dp(64), dp(64)));
        if (isImage(it.name) || Formats.isVideo(it.name)) thumb(iv, it);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), 0, dp(6), 0);
        TextView n = text(15, Ui.TEXT, true);
        n.setText(it.name);
        n.setMaxLines(2);
        n.setEllipsize(TextUtils.TruncateAt.END);
        TextView s = text(12, Ui.DIM, false);
        s.setText(human(it.size));
        col.addView(n);
        col.addView(s);
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        TextView more = text(20, Ui.DIM, true);
        more.setText("⋮");
        more.setPadding(dp(10), dp(4), dp(6), dp(4));
        more.setOnClickListener(v -> menu(it));
        r.addView(more);
        r.setOnClickListener(v -> openItem(it, all));
        r.setOnLongClickListener(v -> {
            menu(it);
            return true;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(4), 0, dp(4));
        r.setLayoutParams(lp);
        return r;
    }

    private void thumb(final ImageView iv, final Vault.Item it) {
        final String key = it.stored;
        iv.setTag(key);
        Bitmap hit = thumbs.get(key);
        if (hit != null) {
            iv.setImageBitmap(hit);
            return;
        }
        final File f = Vault.fileOf(this, it);
        pool.execute(() -> {
            Bitmap b = null;
            try {
                if (isImage(it.name)) {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(f.getPath(), o);
                    int sc = 1;
                    while (Math.max(o.outWidth, o.outHeight) / (sc * 2) >= 200) sc *= 2;
                    BitmapFactory.Options o2 = new BitmapFactory.Options();
                    o2.inSampleSize = sc;
                    b = BitmapFactory.decodeFile(f.getPath(), o2);
                } else {
                    //noinspection deprecation
                    b = android.media.ThumbnailUtils.createVideoThumbnail(f.getPath(), android.provider.MediaStore.Images.Thumbnails.MINI_KIND);
                }
            } catch (Throwable ignored) {
            }
            if (b == null) return;
            thumbs.put(key, b);
            final Bitmap fb = b;
            main.post(() -> {
                if (key.equals(iv.getTag())) iv.setImageBitmap(fb);
            });
        });
    }

    private static String human(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(Locale.ROOT, "%.0f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", b / (1024.0 * 1024));
        return String.format(Locale.ROOT, "%.2f GB", b / (1024.0 * 1024 * 1024));
    }

    // ================================================================== open / restore / delete

    private void openItem(Vault.Item it, List<Vault.Item> all) {
        File f = Vault.fileOf(this, it);
        if (Formats.isVideo(it.name) || Formats.isAudio(it.name)) {
            // this one, then the next private videos / songs
            List<Store.Item> q = new ArrayList<>();
            boolean from = false;
            for (Vault.Item x : all) {
                if (x.stored.equals(it.stored)) from = true;
                if (from && (Formats.isVideo(x.name) || Formats.isAudio(x.name)))
                    q.add(new Store.Item(x.name, Vault.fileOf(this, x).getAbsolutePath(), ""));
            }
            childOpen = true;
            Remote.play(this, q, 0);
        } else if (isImage(it.name)) {
            viewImages(it, all);
        } else {
            openWith(it);
        }
    }

    /** Opens a private file in another app (that app gets temporary access to just this file). */
    private void openWith(Vault.Item it) {
        try {
            File f = Vault.fileOf(this, it);
            Uri u = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".files", f);
            String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext(it.name));
            Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(u, type == null ? "*/*" : type)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            childOpen = true;
            startActivity(Intent.createChooser(i, T.t("Open with")));
        } catch (Exception e) {
            childOpen = false;
            UiKit.makeToast(this, "⚠ " + T.t("No app can open this file"), Toast.LENGTH_LONG).show();
        }
    }

    private void menu(final Vault.Item it) {
        String[] items = {"↩  " + T.t("Restore to its folder"), "🧩  " + T.t("Open with…"), "🗑  " + T.t("Delete forever")};
        new UiDialog(this).setTitle(it.name).setItems(items, (d, w) -> {
            if (w == 0) restore(it);
            else if (w == 1) openWith(it);
            else ZhPopup.confirm(this, "🗑", T.t("Delete forever?"), it.name + "\n" + T.t("This can't be undone."), T.t("Delete"), true, () -> {
                Vault.deleteForever(this, it);
                render();
            });
        }).show();
    }

    private void restore(final Vault.Item it) {
        UiKit.makeToast(this, T.t("Restoring…"), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            File back = Vault.restore(getApplicationContext(), it, null);
            main.post(() -> {
                UiKit.makeToast(this, back != null ? "✓ " + T.t("Back in") + " " + back.getParent()
                        : "⚠ " + T.t("Couldn't restore — not enough space?"), Toast.LENGTH_LONG).show();
                render();
            });
        }).start();
    }

    // ================================================================== photo viewer (swipe / zoom)

    private FrameLayout viewer;

    private void viewImages(Vault.Item start, List<Vault.Item> all) {
        final List<File> imgs = new ArrayList<>();
        int at = 0;
        for (Vault.Item x : all) {
            if (!isImage(x.name)) continue;
            if (x.stored.equals(start.stored)) at = imgs.size();
            imgs.add(Vault.fileOf(this, x));
        }
        final LruCache<String, Bitmap> big = new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 6)) {
            @Override
            protected int sizeOf(String k, Bitmap b) {
                return b.getByteCount();
            }
        };
        final java.util.Set<String> loading = new java.util.HashSet<>();
        viewer = new FrameLayout(this);
        viewer.setBackgroundColor(Color.BLACK);
        final PhotoPager pager = new PhotoPager(this);
        viewer.addView(pager, new FrameLayout.LayoutParams(-1, -1));
        View close = UiKit.darkButton(this, "arrow-left", 42, false, v -> closeViewer());
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(dp(42), dp(42), Gravity.TOP | Gravity.START);
        clp.setMargins(dp(12), dp(12), 0, 0);
        viewer.addView(close, clp);
        final int side = Math.max(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels);
        pager.setSource(new PhotoPager.Source() {
            @Override
            public int count() {
                return imgs.size();
            }

            @Override
            public Bitmap get(int i) {
                if (i < 0 || i >= imgs.size()) return null;
                final File f = imgs.get(i);
                Bitmap hit = big.get(f.getPath());
                if (hit != null) return hit;
                synchronized (loading) {
                    if (loading.contains(f.getPath())) return null;
                    loading.add(f.getPath());
                }
                pool.execute(() -> {
                    Bitmap b = null;
                    try {
                        BitmapFactory.Options o = new BitmapFactory.Options();
                        o.inJustDecodeBounds = true;
                        BitmapFactory.decodeFile(f.getPath(), o);
                        int sc = 1;
                        while (Math.max(o.outWidth, o.outHeight) / (sc * 2) >= side) sc *= 2;
                        BitmapFactory.Options o2 = new BitmapFactory.Options();
                        o2.inSampleSize = sc;
                        b = BitmapFactory.decodeFile(f.getPath(), o2);
                    } catch (Throwable ignored) {
                    }
                    synchronized (loading) {
                        loading.remove(f.getPath());
                    }
                    if (b != null) {
                        big.put(f.getPath(), b);
                        main.post(pager::invalidate);
                    }
                });
                return null;
            }
        }, new PhotoPager.Listener() {
            @Override
            public void onPage(int index) {
            }

            @Override
            public void onTap() {
                close.setVisibility(close.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            }
        });
        root.addView(viewer, new FrameLayout.LayoutParams(-1, -1));
        final int first = at;
        pager.post(() -> pager.show(first));
        pager.requestFocus();
    }

    private void closeViewer() {
        if (viewer != null) root.removeView(viewer);
        viewer = null;
    }

    @Override
    public void onBackPressed() {
        if (viewer != null) closeViewer();
        else super.onBackPressed();
    }

    // ================================================================== lock settings

    private void settings() {
        boolean pw = "pass".equals(Store.getStr("vault_lock", "device"));
        List<String> l = new ArrayList<>();
        l.add((pw ? "○ " : "● ") + T.t("Unlock with the phone's fingerprint / face / screen lock"));
        l.add((pw ? "● " : "○ ") + T.t("Unlock with a ZH Play password"));
        if (Vault.hasPassword()) l.add("🔑  " + T.t("Change the ZH Play password"));
        new UiDialog(this).setTitle("🔒 " + T.t("Private folder lock")).setItems(l.toArray(new String[0]), (d, w) -> {
            if (w == 0) {
                Store.putStr("vault_lock", "device");
                if (Vault.usesPassword(this))
                    UiKit.makeToast(this, T.t("This phone has no screen lock, so the ZH Play password is used"), Toast.LENGTH_LONG).show();
            } else if (w == 1) {
                if (Vault.hasPassword()) Store.putStr("vault_lock", "pass");
                else Vault.createPassword(this, ok -> {
                    if (ok) Store.putStr("vault_lock", "pass");
                });
            } else {
                Vault.createPassword(this, ok -> {
                    if (ok) UiKit.makeToast(this, "✓ " + T.t("Password changed"), Toast.LENGTH_SHORT).show();
                });
            }
        }).show();
    }
}
