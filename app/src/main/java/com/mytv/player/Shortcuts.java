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

    static final String EXTRA_FEATURE = "zh_feature";

    /** Features with a named entry of their own in the manifest ("FeatureTask…"). */
    static final java.util.Set<String> TASK_FEATURES = new java.util.HashSet<>(java.util.Arrays.asList(
            "videos", "music", "gallery", "vpn", "seedr", "remote", "files", "board", "dj", "editor", "library", "eq"));

    /** Where the app's screens live (com.mytv.player) — the manifest's short names are completed with this. */
    static String codePackage() {
        String n = ShortcutLaunchActivity.class.getName();
        return n.substring(0, n.lastIndexOf('.'));
    }

    /**
     * Opens the feature from a home-screen icon / widget — the same screen its tile inside ZH Play opens,
     * but in its OWN task: ZH Music, ZH Videos, the remote… each get their own entry in Recent apps, and
     * tapping ZH Music always comes back to ZH Music (not to whatever ZH Play screen was open last).
     */
    static void launch(Activity a, String id) {
        Intent i;
        switch (id) {
            case "browser":
                i = new Intent(a, BrowserActivity.class);          // the browser already keeps its own task
                break;
            case "player":
                i = new Intent(a, MainActivity.class).putExtra(MainActivity.EXTRA_PLAYER_HOME, true);
                break;
            case "videos":
                i = new Intent(a, PhoneLibraryActivity.class);
                break;
            case "music":
                i = new Intent(a, PhoneLibraryActivity.class).putExtra(PhoneLibraryActivity.EXTRA_AUDIO, true);
                break;
            case "gallery":
                i = new Intent(a, GalleryActivity.class);
                break;
            case "vpn":
                i = new Intent(a, PanelHostActivity.class).putExtra(PanelHostActivity.EXTRA_PANEL, "vpn");
                break;
            case "seedr":
                if (!Auth.featureOn("seedr")) {
                    UiKit.makeToast(a, T.t("Switched off by the admin"), android.widget.Toast.LENGTH_LONG).show();
                    return;
                }
                i = new Intent(a, SeedrActivity.class);
                break;
            case "remote": {
                String ip = Store.getStr("last_tv", "");
                if (ip.isEmpty()) {                                  // no TV yet: the screen that finds one
                    i = new Intent(a, ConnectActivity.class);
                    break;
                }
                String name = "TV";
                for (org.json.JSONObject tv : TvList.all()) if (ip.equals(tv.optString("ip"))) name = tv.optString("name", "TV");
                i = new Intent(a, NativeRemoteActivity.class)
                        .putExtra(NativeRemoteActivity.EXTRA_IP, ip).putExtra(NativeRemoteActivity.EXTRA_NAME, name);
                break;
            }
            case "files":
                i = new Intent(a, StorageActivity.class);
                break;
            case "board":
                i = new Intent(a, PresentActivity.class).putExtra(PresentActivity.EXTRA_BOARD, true);
                break;
            case "dj":
                i = new Intent(a, DjActivity.class);
                break;
            case "editor":
                i = new Intent(a, EditorActivity.class);
                break;
            case "library":
                i = new Intent(a, LibraryActivity.class);
                break;
            case "eq":
                i = new Intent(a, PowerEqActivity.class);
                break;
            default:
                i = new Intent(a, ConnectActivity.class);
        }
        // start through the feature's own named entry (manifest "FeatureTask…"): the task's first screen then carries
        // the feature's icon and name, which is what Recent apps shows (Browser / Player stay in ZH Play's own windows)
        String cls = i.getComponent() == null ? "" : i.getComponent().getClassName();
        if (TASK_FEATURES.contains(id) && !cls.endsWith(".ConnectActivity")) {
            i.setComponent(new android.content.ComponentName(a.getPackageName(),
                    codePackage() + ".FeatureTask" + id.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + id.substring(1)));
        }
        // a "document" task per feature: same feature → back to its task; another feature → its own task
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT | Intent.FLAG_ACTIVITY_NEW_TASK)
                .addCategory("com.tamil.castplay.feature." + id)
                .putExtra(EXTRA_FEATURE, id);
        a.startActivity(i);
    }

    /** Recent apps: a feature's task shows the feature's own name and icon (your logo if you added one). */
    static void labelTask(Activity act) {
        try {
            String id = act.getIntent() == null ? null : act.getIntent().getStringExtra(EXTRA_FEATURE);
            Item it = id == null ? null : find(id);
            if (it == null || Build.VERSION.SDK_INT < 21) return;
            act.setTaskDescription(new android.app.ActivityManager.TaskDescription(it.label, iconFor(act, it), it.color));
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ own app icons (no badge)

    /** The launcher entry for a feature, e.g. "browser" → ".FeatureAppBrowser" (declared in the manifest). */
    static android.content.ComponentName appIcon(Context c, String id) {
        String suffix = id.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + id.substring(1);
        // the app's id (com.tamil.castplay) + the full name of the manifest entry, which lives in the code's
        // package (com.mytv.player) — the manifest's ".FeatureApp…" names are completed with that, not the app id
        String codePackage = ShortcutLaunchActivity.class.getName().substring(0, ShortcutLaunchActivity.class.getName().lastIndexOf('.'));
        return new android.content.ComponentName(c.getPackageName(), codePackage + ".FeatureApp" + suffix);
    }

    /** The feature for a launcher entry (the reverse of appIcon), or null. */
    static String idForAppIcon(String className) {
        if (className == null) return null;
        int i = className.lastIndexOf(".FeatureApp");
        if (i < 0) return null;
        String suffix = className.substring(i + ".FeatureApp".length());
        for (Item it : ITEMS) if (it.id.equalsIgnoreCase(suffix)) return it.id;
        return null;
    }

    static boolean appIconOn(Context c, String id) {
        try {
            return c.getPackageManager().getComponentEnabledSetting(appIcon(c, id))
                    == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        } catch (Exception e) {
            return false;
        }
    }

    /** Shows / hides the feature as its own app in the phone's app list (and home screen on some phones). */
    static void setAppIcon(Context c, String id, boolean on) {
        c.getPackageManager().setComponentEnabledSetting(appIcon(c, id),
                on ? android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        : android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                android.content.pm.PackageManager.DONT_KILL_APP);
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
                .setIcon(Icon.createWithAdaptiveBitmap(fullBleed(a, it)))
                .setIntent(target)
                .build();
        try {
            a.getSystemService(ShortcutManager.class).requestPinShortcut(info, null);
        } catch (Exception e) {
            UiKit.makeToast(a, "⚠ " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ the icon

    /**
     * Your own logo for a feature, if you added one to the project:
     *   app/src/main/res/drawable-nodpi/zh_widget_<id>.png   (e.g. zh_widget_browser.png, zh_widget_vpn.png)
     * Square PNG, about 192×192 or bigger. Features without a file keep the generated tile.
     */
    static int customLogo(Context c, String id) {
        return c.getResources().getIdentifier("zh_widget_" + id, "drawable", c.getPackageName());
    }

    /**
     * The feature's artwork as a full-bleed square (like an app icon's layers): edge-to-edge background,
     * the logo / symbol centred in the part every icon shape shows. The launcher (or the widget below)
     * then cuts it to the phone's own icon shape — so it looks like every other app icon, no white border.
     */
    static Bitmap fullBleed(Context c, Item it) {
        int S = 216;                                     // 108dp canvas; the middle 2/3 is what an icon shape shows
        Bitmap out = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        int res = customLogo(c, it.id);
        Bitmap logo = null;
        if (res != 0) {
            try {
                logo = android.graphics.BitmapFactory.decodeResource(c.getResources(), res);
            } catch (Throwable ignored) {
            }
        }
        if (logo != null) {
            // your logo: fill the edges with its own border colour, then the logo over the visible area
            cv.drawColor(borderColor(logo));
            float target = S * 0.72f;                    // a little more than the visible 2/3, so it reaches the edges
            float k = Math.max(target / logo.getWidth(), target / logo.getHeight());
            float w = logo.getWidth() * k, h = logo.getHeight() * k;
            cv.drawBitmap(logo, null, new android.graphics.RectF((S - w) / 2f, (S - h) / 2f, (S + w) / 2f, (S + h) / 2f), p);
            logo.recycle();
        } else {
            // generated: the feature's colour edge to edge, its symbol in the middle
            GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{it.color, darker(it.color)});
            bg.setBounds(0, 0, S, S);
            bg.draw(cv);
            UiKit.Icon fg = new UiKit.Icon(c, it.icon, 24, Color.WHITE);     // drawn at setBounds() size
            int f = Math.round(S * 0.34f), o = (S - f) / 2;
            fg.setBounds(o, o, o + f, o + f);
            fg.draw(cv);
        }
        return out;
    }

    /** The finished icon (cut to the phone's own icon shape) — for widgets and the list in the app. */
    static Bitmap iconFor(Context c, Item it) {
        Bitmap full = fullBleed(c, it);
        int s = 192;
        Bitmap out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        if (Build.VERSION.SDK_INT >= 26) {
            // the very same shape the phone gives every app icon (circle, squircle, rounded square…)
            android.graphics.drawable.AdaptiveIconDrawable ad = new android.graphics.drawable.AdaptiveIconDrawable(
                    new android.graphics.drawable.ColorDrawable(0x00000000),
                    new android.graphics.drawable.BitmapDrawable(c.getResources(), full));
            ad.setBounds(0, 0, s, s);
            ad.draw(cv);
        } else {
            // older phones: the visible middle part, as a rounded square
            android.graphics.Path clip = new android.graphics.Path();
            clip.addRoundRect(new android.graphics.RectF(0, 0, s, s), s * 0.22f, s * 0.22f, android.graphics.Path.Direction.CW);
            cv.clipPath(clip);
            int cut = full.getWidth() / 6;
            cv.drawBitmap(full, new android.graphics.Rect(cut, cut, full.getWidth() - cut, full.getHeight() - cut),
                    new android.graphics.RectF(0, 0, s, s), new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
        }
        full.recycle();
        return out;
    }

    /** The average colour around a logo's edge (its background), so the icon's edges match it. */
    private static int borderColor(Bitmap b) {
        long r = 0, g = 0, bl = 0;
        int n = 0, w = b.getWidth(), h = b.getHeight();
        int step = Math.max(1, Math.min(w, h) / 40);
        for (int i = 0; i < w; i += step) {
            for (int y : new int[]{0, h - 1}) {
                int px = b.getPixel(i, y);
                if (Color.alpha(px) > 200) { r += Color.red(px); g += Color.green(px); bl += Color.blue(px); n++; }
            }
        }
        for (int j = 0; j < h; j += step) {
            for (int x : new int[]{0, w - 1}) {
                int px = b.getPixel(x, j);
                if (Color.alpha(px) > 200) { r += Color.red(px); g += Color.green(px); bl += Color.blue(px); n++; }
            }
        }
        if (n == 0) return ColorExtract.dominant(b, Color.WHITE);   // see-through edges: use the logo's main colour
        return Color.rgb((int) (r / n), (int) (g / n), (int) (bl / n));
    }

    private static int darker(int c) {
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        hsv[2] *= 0.7f;
        return Color.HSVToColor(hsv) | 0xFF000000;
    }
}
