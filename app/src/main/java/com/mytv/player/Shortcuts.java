package com.mytv.player;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.os.Build;

/**
 * One real, separate home-screen icon per ZH Play feature (Android's own "pinned shortcuts" —
 * the same system any app's long-press shortcuts use), each a plain square icon: that feature's
 * own symbol, in ZH Play's colours, with a small "ZH" corner badge.
 */
final class Shortcuts {
    private Shortcuts() {
    }

    /** One offerable shortcut: id, label, sub-label, icon glyph, accent colour, and how to launch it. */
    static final class Item {
        final String id, label, sub, icon;
        final int color;
        final Runnable launch;

        Item(String id, String label, String sub, String icon, int color, Runnable launch) {
            this.id = id;
            this.label = label;
            this.sub = sub;
            this.icon = icon;
            this.color = color;
            this.launch = launch;
        }
    }

    /** Every feature worth its own home-screen icon. (Karaoke isn't included — it needs a song chosen
     *  first and has no sensible "last played" to fall back to, unlike Remote / Whiteboard.) */
    static java.util.List<Item> all(final Activity a) {
        java.util.List<Item> l = new java.util.ArrayList<>();
        l.add(new Item("browser", "ZH Browser", T.t("Web browser"), "globe", 0xFF3B82F6,
                () -> a.startActivity(new Intent(a, BrowserActivity.class))));
        l.add(new Item("gallery", "ZH Gallery", T.t("Photos & editing"), "image", 0xFF6366F1,
                () -> GalleryActivity.open(a)));
        l.add(new Item("seedr", "ZH Seedr", T.t("Your cloud videos"), "cloud-download", 0xFF0EA5E9,
                () -> a.startActivity(new Intent(a, SeedrActivity.class))));
        l.add(new Item("board", "ZH Whiteboard", T.t("Draw & present"), "pencil", 0xFFEC4899,
                () -> a.startActivity(new Intent(a, PresentActivity.class))));
        l.add(new Item("dj", "ZH DJ", T.t("DJ mixer"), "disc-3", 0xFFF97316,
                () -> a.startActivity(new Intent(a, DjActivity.class))));
        l.add(new Item("music", "ZH Music", T.t("Music player"), "headphones", 0xFF22C55E,
                () -> a.startActivity(new Intent(a, MusicActivity.class))));
        l.add(new Item("remote", "ZH Remote", T.t("Control your TV"), "smartphone", Ui.ACCENT,
                () -> {
                    Intent i = new Intent(a, NativeRemoteActivity.class)
                            .putExtra(NativeRemoteActivity.EXTRA_IP, Store.getStr("last_tv", ""));
                    String name = "TV";
                    for (org.json.JSONObject tv : TvList.all())
                        if (tv.optString("ip").equals(Store.getStr("last_tv", ""))) name = tv.optString("name", "TV");
                    a.startActivity(i.putExtra(NativeRemoteActivity.EXTRA_NAME, name));
                }));
        l.add(new Item("files", "ZH Files", T.t("SD Card & USB"), "hard-drive", 0xFF64748B,
                () -> a.startActivity(new Intent(a, StorageActivity.class))));
        l.add(new Item("eq", "ZH Equalizer", T.t("Sound tuning"), "sliders-horizontal", 0xFFA855F7,
                () -> a.startActivity(new Intent(a, PowerEqActivity.class))));
        l.add(new Item("library", "ZH Library", T.t("Your videos"), "clapperboard", 0xFFDB2777,
                () -> a.startActivity(new Intent(a, LibraryActivity.class))));
        l.add(new Item("vpn", "ZH VPN", T.t("WireGuard / Proton free"), "lock", 0xFF10B981,
                () -> a.startActivity(new Intent(a, PanelHostActivity.class).putExtra(PanelHostActivity.EXTRA_PANEL, "vpn"))));
        return l;
    }

    static boolean supported() {
        return Build.VERSION.SDK_INT >= 26;
    }

    /** Asks the launcher to place this one icon on the home screen (shows the system's own confirmation). */
    static void pin(Activity a, Item it) {
        if (!supported()) {
            UiKit.makeToast(a, T.t("Needs Android 8 or newer"), android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        ShortcutManager sm = a.getSystemService(ShortcutManager.class);
        if (sm == null || !sm.isRequestPinShortcutSupported()) {
            UiKit.makeToast(a, T.t("Your home screen doesn't support adding icons this way"), android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        Intent target = new Intent(a, ShortcutLaunchActivity.class).putExtra(ShortcutLaunchActivity.EXTRA_ID, it.id)
                .setAction(Intent.ACTION_VIEW);
        ShortcutInfo info = new ShortcutInfo.Builder(a, "zh_" + it.id)
                .setShortLabel(it.label)
                .setLongLabel(it.label + " — " + it.sub)
                .setIcon(Icon.createWithBitmap(icon(a, it.icon, it.color)))
                .setIntent(target)
                .build();
        try {
            sm.requestPinShortcut(info, null);
        } catch (Exception e) {
            UiKit.makeToast(a, "⚠ " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
        }
    }

    /** A square icon: the feature's own symbol, in ZH Play's colours, with a small "ZH" corner badge. */
    static Bitmap icon(Context c, String glyph, int color) {
        int s = 192;
        Bitmap bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{color, darker(color)});
        bg.setCornerRadius(s * 0.22f);
        bg.setBounds(0, 0, s, s);
        bg.draw(cv);
        UiKit.Icon fg = new UiKit.Icon(c, glyph, 24, Color.WHITE);     // sizeDp is unused once setBounds() is called below
        int fsz = Math.round(s * 0.52f);
        int left = (s - fsz) / 2, top = (s - fsz) / 2;
        fg.setBounds(left, top, left + fsz, top + fsz);
        fg.draw(cv);
        float br = s * 0.145f, bx = s - br - s * 0.07f, by = s - br - s * 0.07f;
        Paint badgeBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        badgeBg.setColor(0xFF111827);
        cv.drawCircle(bx, by, br, badgeBg);
        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(s * 0.01f);
        ring.setColor(0x55FFFFFF);
        cv.drawCircle(bx, by, br, ring);
        Paint badgeTxt = new Paint(Paint.ANTI_ALIAS_FLAG);
        badgeTxt.setColor(Color.WHITE);
        badgeTxt.setTextSize(br * 1.05f);
        badgeTxt.setTextAlign(Paint.Align.CENTER);
        badgeTxt.setFakeBoldText(true);
        cv.drawText("ZH", bx, by + badgeTxt.getTextSize() * 0.36f, badgeTxt);
        return bmp;
    }

    private static int darker(int c) {
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        hsv[2] *= 0.7f;
        return Color.HSVToColor(hsv) | 0xFF000000;
    }
}
