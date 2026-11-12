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
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.os.Build;

/**
 * Every ZH Play feature that can live on the home screen on its own — as a widget (long-press the home
 * screen → Widgets → ZH Play) or as a pinned icon. One list, used by both, and every launch is exactly
 * the one the app's own feature tiles use.
 */
final class Shortcuts {
    private Shortcuts() {
    }

    static final class Item {
        final String id, label, sub, icon;
        final int color;

        Item(String id, String label, String sub, String icon, int color) {
            this.id = id;
            this.label = label;
            this.sub = sub;
            this.icon = icon;
            this.color = color;
        }
    }

    /** id, home-screen name, description, glyph, colour. */
    static final Item[] ITEMS = {
            new Item("browser", "ZH Browser", "Web browser", "search", 0xFF22C55E),
            new Item("player", "ZH Player", "Play videos & links", "smartphone", 0xFFF97316),
            new Item("videos", "ZH Videos", "Videos on this phone", "film", 0xFF0EA5E9),
            new Item("music", "ZH Music", "Music on this phone", "list-music", 0xFFD946EF),
            new Item("gallery", "ZH Gallery", "Photos & editing", "image", 0xFF6366F1),
            new Item("vpn", "ZH VPN", "WireGuard / Proton free", "lock", 0xFF10B981),
            new Item("seedr", "ZH Seedr", "Cloud videos", "cloud-download", 0xFF3B82F6),
            new Item("remote", "ZH Remote", "Control your TV", "tv", 0xFF8B5CF6),
            new Item("files", "ZH Files", "SD card & USB", "sd-card", 0xFFF59E0B),
            new Item("board", "ZH Whiteboard", "Draw & present", "presentation", 0xFF10B981),
            new Item("dj", "ZH DJ", "DJ mixer", "disc-3", 0xFFEC4899),
            new Item("editor", "ZH Song Editor", "Cut & mix songs", "audio-waveform", 0xFF6366F1),
            new Item("library", "ZH Library", "Posters & watched", "library", 0xFF8B5CF6),
            new Item("eq", "ZH Equalizer", "Sound tuning", "sliders-vertical", 0xFF22D3EE),
    };

    /** Position in ITEMS — unique per feature (used so each widget's tap is kept separate by Android). */
    static int indexOf(String id) {
        for (int i = 0; i < ITEMS.length; i++) if (ITEMS[i].id.equals(id)) return i;
        return ITEMS.length;
    }

    static Item find(String id) {
        for (Item it : ITEMS) if (it.id.equals(id)) return it;
        return null;
    }

    /** Opens the feature — the same way its tile inside ZH Play does. */
    static void launch(Activity a, String id) {
        switch (id) {
            case "browser":
                a.startActivity(new Intent(a, BrowserActivity.class));
                break;
            case "player":
                a.startActivity(new Intent(a, MainActivity.class).putExtra(MainActivity.EXTRA_PLAYER_HOME, true));
                break;
            case "videos":
                a.startActivity(new Intent(a, PhoneLibraryActivity.class));
                break;
            case "music":
                a.startActivity(new Intent(a, PhoneLibraryActivity.class).putExtra(PhoneLibraryActivity.EXTRA_AUDIO, true));
                break;
            case "gallery":
                GalleryActivity.open(a);
                break;
            case "vpn":
                a.startActivity(new Intent(a, PanelHostActivity.class).putExtra(PanelHostActivity.EXTRA_PANEL, "vpn"));
                break;
            case "seedr":
                SeedrActivity.open(a);
                break;
            case "remote": {
                String ip = Store.getStr("last_tv", "");
                if (ip.isEmpty()) {                      // no TV yet: the screen that finds one
                    a.startActivity(new Intent(a, ConnectActivity.class));
                    break;
                }
                String name = "TV";
                for (org.json.JSONObject tv : TvList.all()) if (ip.equals(tv.optString("ip"))) name = tv.optString("name", "TV");
                a.startActivity(new Intent(a, NativeRemoteActivity.class)
                        .putExtra(NativeRemoteActivity.EXTRA_IP, ip).putExtra(NativeRemoteActivity.EXTRA_NAME, name));
                break;
            }
            case "files":
                StorageActivity.open(a);
                break;
            case "board":
                a.startActivity(new Intent(a, PresentActivity.class).putExtra(PresentActivity.EXTRA_BOARD, true));
                break;
            case "dj":
                a.startActivity(new Intent(a, DjActivity.class));
                break;
            case "editor":
                a.startActivity(new Intent(a, EditorActivity.class));
                break;
            case "library":
                a.startActivity(new Intent(a, LibraryActivity.class));
                break;
            case "eq":
                a.startActivity(new Intent(a, PowerEqActivity.class));
                break;
            default:
                a.startActivity(new Intent(a, ConnectActivity.class));
        }
    }

    // ------------------------------------------------------------------ pinned icons (Android 8+)

    static boolean pinSupported(Context c) {
        if (Build.VERSION.SDK_INT < 26) return false;
        ShortcutManager sm = c.getSystemService(ShortcutManager.class);
        return sm != null && sm.isRequestPinShortcutSupported();
    }

    static void pin(Activity a, Item it) {
        if (!pinSupported(a)) {
            UiKit.makeToast(a, T.t("Your home screen doesn't support this — use Widgets instead"), android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        Intent target = new Intent(a, ShortcutLaunchActivity.class).setAction(Intent.ACTION_VIEW)
                .putExtra(ShortcutLaunchActivity.EXTRA_ID, it.id);
        ShortcutInfo info = new ShortcutInfo.Builder(a, "zh_" + it.id)
                .setShortLabel(it.label)
                .setLongLabel(it.label + " — " + T.t(it.sub))
                .setIcon(Icon.createWithBitmap(icon(a, it.icon, it.color)))
                .setIntent(target)
                .build();
        try {
            a.getSystemService(ShortcutManager.class).requestPinShortcut(info, null);
        } catch (Exception e) {
            UiKit.makeToast(a, "⚠ " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ the icon

    /** A square tile: the feature's own symbol on its colour, with a small "ZH" badge in the corner. */
    static Bitmap icon(Context c, String glyph, int color) {
        int s = 192;
        Bitmap bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{color, darker(color)});
        bg.setCornerRadius(s * 0.24f);
        bg.setBounds(0, 0, s, s);
        bg.draw(cv);
        UiKit.Icon fg = new UiKit.Icon(c, glyph, 24, Color.WHITE);     // drawn at setBounds() size
        int f = Math.round(s * 0.5f), o = (s - f) / 2;
        fg.setBounds(o, o - Math.round(s * 0.03f), o + f, o + f - Math.round(s * 0.03f));
        fg.draw(cv);
        float br = s * 0.15f, bx = s - br - s * 0.06f, by = s - br - s * 0.06f;
        Paint badge = new Paint(Paint.ANTI_ALIAS_FLAG);
        badge.setColor(0xFF111827);
        cv.drawCircle(bx, by, br, badge);
        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(s * 0.012f);
        ring.setColor(0x66FFFFFF);
        cv.drawCircle(bx, by, br, ring);
        Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        txt.setColor(Color.WHITE);
        txt.setTextSize(br * 0.95f);
        txt.setTextAlign(Paint.Align.CENTER);
        txt.setFakeBoldText(true);
        cv.drawText("ZH", bx, by + txt.getTextSize() * 0.36f, txt);
        return bmp;
    }

    private static int darker(int c) {
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        hsv[2] *= 0.7f;
        return Color.HSVToColor(hsv) | 0xFF000000;
    }
}
