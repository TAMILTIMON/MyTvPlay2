package com.mytv.player;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.HashMap;

/** 🌟 Make a sticker from your own photo and send it to the TV — it pops up there, animated, without a background. */
public class StickerActivity extends Activity {
    static final String EXTRA_IP = "ip", EXTRA_NAME = "name", EXTRA_CLOUD = "cloud";
    private static final int REQ_PICK = 4801, REQ_CAM = 4802, REQ_CAM_PERM = 4803;
    private static final int WORK_SIDE = 800;                       // working size: fast cut-out and smooth brushing
    static final String[] ANIMS = {"bounce", "wiggle", "spin", "float"};
    static final String[] ANIM_NAMES = {"Bounce in", "Wiggle", "Spin", "Float"};

    static void open(Activity a, String ip, String tvName, String cloudId, boolean allowed) {
        if (!allowed) {
            ZhPopup.message(a, "🌟", T.t("Stickers are off on this TV"),
                    T.t("They can be switched on in the TV's ⚙ Settings → Voice messages & stickers from phones."));
            return;
        }
        a.startActivity(new Intent(a, StickerActivity.class).putExtra(EXTRA_IP, ip).putExtra(EXTRA_NAME, tvName).putExtra(EXTRA_CLOUD, cloudId));
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private String ip, tvName, cloudId;
    private Bitmap photo;                 // the photo, at working size
    private int[] mask;                   // alpha per pixel (0 removed … 255 kept)
    private Bitmap shown;                 // photo + mask, for the screen
    private final ArrayDeque<int[]> undo = new ArrayDeque<>();
    private String text = "", anim = "bounce";
    private int strength = 45;
    private int brush = 0;                // 0 = off, 1 = erase, 2 = restore
    private int brushSize = 40;
    private Board board;
    private TextView status, eraseBtn, restoreBtn, animBtn;
    private LinearLayout tools;
    private UiKit.PillSlider strengthSlider;
    private File camFile;

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
        ip = getIntent().getStringExtra(EXTRA_IP);
        tvName = getIntent().getStringExtra(EXTRA_NAME);
        cloudId = getIntent().getStringExtra(EXTRA_CLOUD);
        if (tvName == null) tvName = "TV";

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(12), dp(12), dp(12), dp(6));
        head.addView(UiKit.darkButton(this, "arrow-left", 42, false, v -> finish()), new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView t = text(20, Ui.TEXT, true);
        t.setText("🌟  " + T.t("Make a sticker"));
        t.setPadding(dp(12), 0, 0, 0);
        head.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        TextView saved = pill("📁  " + T.t("Saved"), false);
        saved.setOnClickListener(v -> savedStickers());
        head.addView(saved);
        root.addView(head);

        board = new Board(this);
        root.addView(board, new LinearLayout.LayoutParams(-1, 0, 1));
        status = text(13, Ui.DIM, false);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(12), dp(6), dp(12), dp(4));
        status.setText(T.t("Choose a photo to start"));
        root.addView(status);

        // ---- tools (scrolls sideways on small phones)
        tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.VERTICAL);
        tools.setPadding(dp(10), 0, dp(10), dp(12));
        tools.addView(row(
                pillAct("🖼  " + T.t("Photo"), false, v -> pick()),
                pillAct("📷  " + T.t("Camera"), false, v -> camera()),
                pillAct("✨  " + T.t("AI cut"), false, v -> cut(true)),
                pillAct("🎨  " + T.t("Colour cut"), false, v -> cut(false))));
        strengthSlider = new UiKit.PillSlider(this, "sliders-horizontal", 1, 100, false);
        strengthSlider.setValue(strength);
        strengthSlider.setListener((val, done) -> {
            strength = val;
            if (done && photo != null) cut(false);
        });
        strengthSlider.setVisibility(View.GONE);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, dp(44));
        slp.setMargins(0, dp(6), 0, 0);
        tools.addView(strengthSlider, slp);
        eraseBtn = pillAct("✋  " + T.t("Erase"), false, v -> setBrush(brush == 1 ? 0 : 1));
        restoreBtn = pillAct("🖌  " + T.t("Restore"), false, v -> setBrush(brush == 2 ? 0 : 2));
        tools.addView(row(eraseBtn, restoreBtn,
                pillAct("◐  " + T.t("Brush size"), false, v -> {
                    brushSize = brushSize >= 80 ? 20 : brushSize + 20;
                    toast(T.t("Brush size") + ": " + brushSize);
                }),
                pillAct("↶  " + T.t("Undo"), false, v -> undo())));
        animBtn = pillAct("🎞  " + T.t(ANIM_NAMES[0]), false, v -> chooseAnim());
        tools.addView(row(
                pillAct("Aa  " + T.t("Text"), false, v -> askText()),
                animBtn,
                pillAct("💾  " + T.t("Save"), false, v -> save(false)),
                pillAct("➤  " + T.t("Send to TV"), true, v -> save(true))));
        root.addView(tools);
        setContentView(root);
    }

    private TextView pill(String s, boolean primary) {
        TextView b = text(13, primary ? Color.WHITE : Ui.TEXT, true);
        b.setText(s);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(13), dp(10), dp(13), dp(10));
        b.setBackground(primary ? Ui.gradient(this, 20) : Ui.focusCard(this, 20));
        b.setFocusable(true);
        b.setClickable(true);
        return b;
    }

    private TextView pillAct(String s, boolean primary, View.OnClickListener l) {
        TextView b = pill(s, primary);
        b.setOnClickListener(l);
        return b;
    }

    private View row(View... vs) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout r = new LinearLayout(this);
        r.setPadding(0, dp(6), 0, 0);
        for (View v : vs) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(0, 0, dp(8), 0);
            r.addView(v, lp);
        }
        hs.addView(r);
        return hs;
    }

    private void toast(String s) {
        UiKit.makeToast(this, s, Toast.LENGTH_SHORT).show();
    }

    // ================================================================== the photo

    private void pick() {
        startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), REQ_PICK);
    }

    private void camera() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAM_PERM);
            return;
        }
        try {
            camFile = new File(getCacheDir(), "sticker_cam.jpg");
            Uri u = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".files", camFile);
            startActivityForResult(new Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, u)
                    .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION), REQ_CAM);
        } catch (Exception e) {
            toast("⚠ " + T.t("No camera app found"));
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_CAM_PERM && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) camera();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK) return;
        final Uri u = req == REQ_CAM ? Uri.fromFile(camFile) : data == null ? null : data.getData();
        if (u == null) return;
        status.setText(T.t("Opening the photo…"));
        new Thread(() -> {
            Bitmap b = load(u);
            main.post(() -> {
                if (b == null) {
                    status.setText("⚠ " + T.t("Can't open this photo"));
                    return;
                }
                photo = b;
                mask = null;
                undo.clear();
                cut(true);                                         // AI first; colour cut-out if the AI can't
            });
        }).start();
    }

    /** The photo at working size, the right way up. */
    private Bitmap load(Uri u) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(u)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            int s = 1;
            while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= WORK_SIDE) s *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = s;
            Bitmap b;
            try (InputStream in = getContentResolver().openInputStream(u)) {
                b = BitmapFactory.decodeStream(in, null, o2);
            }
            if (b == null) return null;
            int deg = 0;
            if (Build.VERSION.SDK_INT >= 24) {
                try (InputStream in = getContentResolver().openInputStream(u)) {
                    if (in != null) switch (new android.media.ExifInterface(in).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                        case android.media.ExifInterface.ORIENTATION_ROTATE_90: deg = 90; break;
                        case android.media.ExifInterface.ORIENTATION_ROTATE_180: deg = 180; break;
                        case android.media.ExifInterface.ORIENTATION_ROTATE_270: deg = 270; break;
                    }
                } catch (Throwable ignored) {
                }
            }
            float k = Math.min(1f, WORK_SIDE / (float) Math.max(b.getWidth(), b.getHeight()));
            Matrix m = new Matrix();
            m.postScale(k, k);
            if (deg != 0) m.postRotate(deg);
            Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            return r.copy(Bitmap.Config.ARGB_8888, false);
        } catch (Throwable e) {
            return null;
        }
    }

    // ================================================================== cutting out

    private void cut(final boolean ai) {
        if (photo == null) {
            toast(T.t("Choose a photo first"));
            return;
        }
        strengthSlider.setVisibility(ai ? View.GONE : View.VISIBLE);
        status.setText(ai ? "✨ " + T.t("Finding the person…") : "🎨 " + T.t("Removing the background…"));
        final Bitmap src = photo;
        new Thread(() -> {
            int[] m = ai ? StickerCut.aiMask(src) : null;
            boolean usedAi = m != null;
            if (m == null) {
                int w = src.getWidth(), h = src.getHeight();
                int[] px = new int[w * h];
                src.getPixels(px, 0, w, 0, 0, w, h);
                m = StickerCut.colourMask(px, w, h, strength);
            }
            final int[] fm = m;
            main.post(() -> {
                if (mask != null) pushUndo();
                mask = fm;
                refresh();
                if (ai && !usedAi) {
                    strengthSlider.setVisibility(View.VISIBLE);
                    status.setText("🎨 " + T.t("Used the colour cut-out (no person found by the AI). Adjust with the slider, then touch up with Erase / Restore."));
                } else {
                    status.setText(usedAi ? "✨ " + T.t("Cut out with AI. Touch up with Erase / Restore if needed.")
                            : "🎨 " + T.t("Background removed. Slide to adjust; touch up with Erase / Restore."));
                }
            });
        }).start();
    }

    private void pushUndo() {
        undo.push(mask.clone());
        while (undo.size() > 12) undo.removeLast();
    }

    private void undo() {
        if (undo.isEmpty()) {
            toast(T.t("Nothing to undo"));
            return;
        }
        mask = undo.pop();
        refresh();
    }

    private void setBrush(int b) {
        brush = b;
        eraseBtn.setBackground(b == 1 ? Ui.gradient(this, 20) : Ui.focusCard(this, 20));
        eraseBtn.setTextColor(b == 1 ? Color.WHITE : Ui.TEXT);
        restoreBtn.setBackground(b == 2 ? Ui.gradient(this, 20) : Ui.focusCard(this, 20));
        restoreBtn.setTextColor(b == 2 ? Color.WHITE : Ui.TEXT);
        if (b != 0) status.setText(b == 1 ? "✋ " + T.t("Rub over parts to remove") : "🖌 " + T.t("Rub over parts to bring back"));
    }

    private void refresh() {
        if (photo == null || mask == null) return;
        shown = StickerCut.apply(photo, mask);
        board.invalidate();
    }

    // ================================================================== text / animation

    private void askText() {
        final EditText e = new EditText(this);
        e.setHint(T.t("Happy birthday! · Dinner is ready 🍛"));
        e.setText(text);
        new UiDialog(this).setTitle("Aa  " + T.t("Words on the sticker")).setView(e)
                .setPositiveButton(T.t("OK"), (d, w) -> {
                    text = e.getText().toString().trim();
                    status.setText(text.isEmpty() ? T.t("No words") : "Aa  “" + text + "”");
                }).setNegativeButton(T.t("Cancel"), null).show();
    }

    private void chooseAnim() {
        String[] n = new String[ANIMS.length];
        for (int i = 0; i < n.length; i++) n[i] = T.t(ANIM_NAMES[i]);
        new UiDialog(this).setTitle("🎞  " + T.t("How it appears on the TV")).setItems(n, (d, w) -> {
            anim = ANIMS[w];
            animBtn.setText("🎞  " + T.t(ANIM_NAMES[w]));
        }).show();
    }

    // ================================================================== save / send

    private void save(final boolean send) {
        if (photo == null || mask == null) {
            toast(T.t("Choose a photo first"));
            return;
        }
        Bitmap st = StickerCut.sticker(StickerCut.apply(photo, mask), mask, text);
        if (st == null) {
            toast(T.t("Nothing left — use Restore to bring part of the photo back"));
            return;
        }
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        st.compress(Bitmap.CompressFormat.PNG, 100, bo);
        byte[] png = bo.toByteArray();
        StickerStore.add(this, png, anim);
        if (!send) {
            toast("💾 " + T.t("Saved to your stickers"));
            return;
        }
        sendPng(png, anim);
    }

    private void sendPng(final byte[] png, final String an) {
        status.setText(T.t("Sending to") + " " + tvName + "…");
        final String from = Auth.signedIn() && !Auth.name().isEmpty() ? Auth.name() : T.t("your phone");
        new Thread(() -> {
            String r = deliver(from, android.util.Base64.encodeToString(png, android.util.Base64.NO_WRAP), an);
            main.post(() -> {
                status.setText(r);
                toast(r);
            });
        }).start();
    }

    /** At home straight to the TV; from anywhere (or TV not reachable now) through the cloud. */
    private String deliver(String from, String b64, String an) {
        if (ip != null && ip.startsWith("cloud:")) return viaCloud(ip.substring(6), from, b64, an);
        try {
            HashMap<String, String> p = new HashMap<>();
            p.put("img", b64);
            p.put("anim", an);
            p.put("from", from);
            JSONObject r = TvClient.post(ip, "/sticker", p);
            if (r.optBoolean("ok", true)) return "✓ " + T.t("On the TV now");
            return "⚠ " + r.optString("msg", T.t("The TV couldn't show it"));
        } catch (TvClient.AuthNeeded e) {
            return "⚠ " + T.t("Open the remote and enter the TV PIN first");
        } catch (Exception notReachable) {
            if (cloudId != null && !cloudId.isEmpty() && Cloud.signedIn()) return viaCloud(cloudId, from, b64, an);
            return "⚠ " + T.t("Can't reach the TV — is it on and on the same Wi-Fi?");
        }
    }

    private String viaCloud(String tvId, String from, String b64, String an) {
        if (!Cloud.signedIn()) return "⚠ " + T.t("Sign in on this phone to send from anywhere");
        try {
            Cloud.sendSticker(tvId, from, b64, an);
            return "✓ " + T.t("Sent — it pops up on the TV (or as soon as the TV is back on)");
        } catch (Exception e) {
            return "⚠ " + T.t("Couldn't send — check the internet");
        }
    }

    private void savedStickers() {
        final JSONArray all = StickerStore.list(this);
        if (all.length() == 0) {
            toast(T.t("No saved stickers yet"));
            return;
        }
        android.widget.GridLayout g = new android.widget.GridLayout(this);
        g.setColumnCount(3);
        g.setPadding(dp(10), dp(6), dp(10), dp(6));
        final android.app.Dialog[] dlg = new android.app.Dialog[1];
        for (int i = 0; i < all.length(); i++) {
            final JSONObject s = all.optJSONObject(i);
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(BitmapFactory.decodeFile(s.optString("f")));
            iv.setBackground(Ui.focusCard(this, 14));
            iv.setPadding(dp(6), dp(6), dp(6), dp(6));
            iv.setFocusable(true);
            iv.setOnClickListener(v -> {
                if (dlg[0] != null) dlg[0].dismiss();
                byte[] png = StickerStore.read(s.optString("f"));
                if (png != null) sendPng(png, s.optString("anim", "bounce"));
            });
            iv.setOnLongClickListener(v -> {
                ZhPopup.confirm(this, "🗑", T.t("Delete this sticker?"), "", T.t("Delete"), true, () -> {
                    StickerStore.remove(this, s.optString("f"));
                    if (dlg[0] != null) dlg[0].dismiss();
                    savedStickers();
                });
                return true;
            });
            android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams();
            lp.width = dp(92);
            lp.height = dp(92);
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            g.addView(iv, lp);
        }
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(g);
        dlg[0] = new UiDialog(this).setTitle("📁  " + T.t("Tap one to send it · hold to delete")).setView(sv)
                .setNegativeButton(T.t("Close"), null).show();
    }

    // ================================================================== the picture area (with brushing)

    private final class Board extends View {
        private final Paint pp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint cell = new Paint();
        private final RectF dst = new RectF();

        Board(Context c) {
            super(c);
        }

        @Override
        protected void onDraw(Canvas cv) {
            int w = getWidth(), h = getHeight(), s = dp(12);
            for (int y = 0; y < h; y += s)                         // checkerboard = transparent
                for (int x = 0; x < w; x += s) {
                    cell.setColor(((x / s + y / s) & 1) == 0 ? 0xFF2A2D3A : 0xFF20222C);
                    cv.drawRect(x, y, x + s, y + s, cell);
                }
            Bitmap b = shown != null ? shown : photo;
            if (b == null) {
                Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
                tp.setColor(0x88FFFFFF);
                tp.setTextSize(dp(16));
                tp.setTextAlign(Paint.Align.CENTER);
                cv.drawText(T.t("🖼 Photo  ·  📷 Camera"), w / 2f, h / 2f, tp);
                return;
            }
            float k = Math.min((w - dp(20)) / (float) b.getWidth(), (h - dp(20)) / (float) b.getHeight());
            float dw = b.getWidth() * k, dh = b.getHeight() * k;
            dst.set((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2);
            cv.drawBitmap(b, null, dst, pp);
        }

        private long lastRefresh;

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (brush == 0 || mask == null || photo == null || dst.width() <= 0) return false;
            float sx = (e.getX() - dst.left) * photo.getWidth() / dst.width();
            float sy = (e.getY() - dst.top) * photo.getHeight() / dst.height();
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) pushUndo();
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN || e.getActionMasked() == MotionEvent.ACTION_MOVE) {
                float r = brushSize * photo.getWidth() / Math.max(1f, dst.width());
                StickerCut.brush(mask, photo.getWidth(), photo.getHeight(), sx, sy, r, brush == 2);
                long now = System.currentTimeMillis();
                if (now - lastRefresh > 40) {                         // keep brushing smooth
                    lastRefresh = now;
                    refresh();
                }
            }
            if (e.getActionMasked() == MotionEvent.ACTION_UP) refresh();
            return true;
        }
    }

    // ================================================================== saved stickers

    static final class StickerStore {
        static File dir(Context c) {
            File d = new File(c.getFilesDir(), "stickers");
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
            return d;
        }

        static JSONArray list(Context c) {
            try {
                JSONArray a = new JSONArray(Store.getStr("stickers", "[]")), ok = new JSONArray();
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o != null && new File(o.optString("f")).isFile()) ok.put(o);
                }
                return ok;
            } catch (Exception e) {
                return new JSONArray();
            }
        }

        static void add(Context c, byte[] png, String anim) {
            try {
                File f = new File(dir(c), "s_" + System.currentTimeMillis() + ".png");
                try (FileOutputStream o = new FileOutputStream(f)) {
                    o.write(png);
                }
                JSONArray old = list(c), now = new JSONArray();
                now.put(new JSONObject().put("f", f.getAbsolutePath()).put("anim", anim));
                for (int i = 0; i < old.length(); i++) {
                    if (now.length() < 30) now.put(old.get(i));
                    else //noinspection ResultOfMethodCallIgnored
                        new File(old.optJSONObject(i).optString("f")).delete();
                }
                Store.putStr("stickers", now.toString());
            } catch (Exception ignored) {
            }
        }

        static void remove(Context c, String path) {
            JSONArray old = list(c), now = new JSONArray();
            for (int i = 0; i < old.length(); i++) {
                JSONObject o = old.optJSONObject(i);
                if (o != null && path.equals(o.optString("f"))) {
                    //noinspection ResultOfMethodCallIgnored
                    new File(path).delete();
                } else now.put(o);
            }
            Store.putStr("stickers", now.toString());
        }

        static byte[] read(String path) {
            try {
                File f = new File(path);
                byte[] b = new byte[(int) f.length()];
                try (FileInputStream in = new FileInputStream(f)) {
                    int n = 0;
                    while (n < b.length) {
                        int r = in.read(b, n, b.length - n);
                        if (r < 0) break;
                        n += r;
                    }
                }
                return b;
            } catch (Exception e) {
                return null;
            }
        }
    }
}
