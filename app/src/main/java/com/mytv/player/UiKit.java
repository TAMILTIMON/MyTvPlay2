package com.mytv.player;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

/** Modern UI pieces: Lucide icons, round icon buttons, board tiles, colour sliders, touchpad, splash. */
public final class UiKit {
    private UiKit() {
    }

    // ================================================================== Lucide icon drawable
    public static final class Icon extends Drawable {
        private static final Map<String, Path> CACHE = new HashMap<>();
        private final Path src;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path draw = new Path();
        private final Matrix m = new Matrix();
        private final int size;

        public Icon(Context c, String name, int sizeDp, int color) {
            src = path(name);
            size = Ui.dp(c, sizeDp);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor(color);
        }

        static synchronized Path path(String name) {
            Path c = CACHE.get(name);
            if (c != null) return c;
            Path out = new Path();
            String d = LucideIcons.P.get(name);
            if (d != null) {
                try {
                    out = androidx.core.graphics.PathParser.createPathFromPathData(d);
                } catch (Throwable ignored) {
                }
            }
            CACHE.put(name, out);
            return out;
        }

        public Icon color(int c) {
            p.setColor(c);
            invalidateSelf();
            return this;
        }

        @Override
        public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            float s = Math.min(b.width(), b.height()) / 24f;
            m.reset();
            m.setScale(s, s);
            m.postTranslate(b.left + (b.width() - 24 * s) / 2f, b.top + (b.height() - 24 * s) / 2f);
            src.transform(m, draw);
            p.setStrokeWidth(2f * s);
            c.drawPath(draw, p);
        }

        @Override
        public int getIntrinsicWidth() {
            return size;
        }

        @Override
        public int getIntrinsicHeight() {
            return size;
        }

        @Override
        public void setAlpha(int a) {
            p.setAlpha(a);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            p.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    public static ImageView icon(Context c, String name, int sizeDp, int color) {
        ImageView iv = new ImageView(c);
        iv.setImageDrawable(new Icon(c, name, sizeDp, color));
        iv.setScaleType(ImageView.ScaleType.CENTER);
        return iv;
    }

    // ================================================================== round icon button
    // spoken names of icon buttons (TalkBack / screen readers)
    private static final String[][] SPOKEN = {{"play", "Play"}, {"pause", "Pause"}, {"skip-back", "Previous"}, {"skip-forward", "Next"},
            {"shuffle", "Shuffle"}, {"repeat", "Repeat"}, {"repeat-1", "Repeat one"}, {"rotate-ccw", "Back 10 seconds"},
            {"rotate-cw", "Forward 10 seconds"}, {"undo-2", "Back 30 seconds"}, {"cast", "Play on the TV"}, {"heart", "Favourite"},
            {"mic-vocal", "Lyrics"}, {"sliders-horizontal", "Equalizer"}, {"ellipsis-vertical", "More options"}, {"chevron-down", "Close"},
            {"x", "Close"}, {"arrow-left", "Back"}, {"house", "Home"}, {"layers", "Recent apps"}, {"menu", "Menu"}, {"power", "Power"},
            {"volume-1", "TV volume down"}, {"volume-2", "TV volume up"}, {"volume-x", "Mute"}, {"keyboard", "Keyboard"}, {"mic", "Voice"},
            {"search", "Search"}, {"settings", "Settings"}, {"tv", "TV"}, {"list-music", "Queue"}, {"circle-stop", "Stop"},
            {"camera", "Screenshot"}, {"bookmark-plus", "Bookmark this scene"}, {"bookmark", "Bookmarks"}, {"audio-lines", "Audio only"},
            {"circle-play", "Play or pause"}, {"layout-grid", "View"}, {"check", "Done"}, {"sun", "Light theme"}, {"moon", "Dark theme"},
            {"chevron-up", "Up"}, {"chevron-left", "Left"}, {"chevron-right", "Right"}, {"lock", "Lock"}, {"monitor", "Picture"},
            {"captions", "Subtitles"}, {"list-video", "Channels"}};

    public static String spoken(String icon) {
        for (String[] p : SPOKEN) if (p[0].equals(icon)) return T.t(p[1]);
        return icon.replace('-', ' ');
    }

    /** Circle button with a Lucide icon. primary = accent gradient. */
    public static ImageView roundButton(final Context c, String iconName, int sizeDp, boolean primary, View.OnClickListener l) {
        final ImageView b = new ImageView(c);
        int ic = primary ? Color.WHITE : Ui.TEXT;
        b.setImageDrawable(new Icon(c, iconName, Math.round(sizeDp * 0.46f), ic));
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setContentDescription(spoken(iconName));
        StateListDrawable sl = new StateListDrawable();
        GradientDrawable on = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{Ui.ACCENT, Ui.ACCENT2});
        on.setShape(GradientDrawable.OVAL);
        on.setStroke(Ui.dp(c, 2), Color.WHITE);
        GradientDrawable off;
        if (primary) off = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{Ui.ACCENT, Ui.ACCENT2});
        else {
            off = new GradientDrawable();
            off.setColor(Ui.dark() ? 0x14FFFFFF : 0x0D1B1530);
        }
        off.setShape(GradientDrawable.OVAL);
        sl.addState(new int[]{android.R.attr.state_focused}, on);
        sl.addState(new int[]{android.R.attr.state_pressed}, on);
        sl.addState(new int[]{}, off);
        b.setBackground(sl);
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(70).withEndAction(() ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()).start();
            if (l != null) l.onClick(v);
        });
        b.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(c, sizeDp), Ui.dp(c, sizeDp)));
        return b;
    }

    /** The ZH Play logo on a white badge (the black "H" stays visible on dark screens too). */
    public static FrameLayout logoBadge(Context c, int sizeDp, boolean round) {
        FrameLayout f = new FrameLayout(c);
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.WHITE);
        if (round) g.setShape(GradientDrawable.OVAL);
        else g.setCornerRadius(Ui.dp(c, sizeDp * 0.24f));
        f.setBackground(g);
        f.setElevation(Ui.dp(c, 3));
        ImageView iv = new ImageView(c);
        iv.setImageResource(R.drawable.zh_logo);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = Ui.dp(c, sizeDp * (round ? 0.10f : 0.07f));
        iv.setPadding(pad, pad, pad, pad);
        f.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return f;
    }

    /** Red round ✕ for closing panels. */
    public static ImageView closeButton(Context c, View.OnClickListener l) {
        ImageView b = roundButton(c, "x", 40, false, l);
        b.setImageDrawable(new Icon(c, "x", 20, Color.WHITE));
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0xFFEF4444);
        b.setBackground(g);
        b.setElevation(Ui.dp(c, 2));
        return b;
    }

    public static void setIcon(Context c, ImageView b, String name, int sizeDp, int color) {
        b.setImageDrawable(new Icon(c, name, sizeDp, color));
    }

    // ================================================================== board tile ("kanban" card)
    public static View tile(Context c, String iconName, String title, String sub, int tint, View.OnClickListener l) {
        LinearLayout t = new LinearLayout(c);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(Ui.dp(c, 14), Ui.dp(c, 14), Ui.dp(c, 14), Ui.dp(c, 12));
        StateListDrawable sl = new StateListDrawable();
        GradientDrawable on = new GradientDrawable();
        on.setColor(Ui.SOLID);
        on.setCornerRadius(Ui.dp(c, 22));
        on.setStroke(Ui.dp(c, 2), Ui.ACCENT);
        GradientDrawable off = new GradientDrawable();
        off.setColor(Ui.SOLID);
        off.setCornerRadius(Ui.dp(c, 22));
        off.setStroke(Ui.dp(c, 1), Ui.STROKE);
        sl.addState(new int[]{android.R.attr.state_focused}, on);
        sl.addState(new int[]{android.R.attr.state_pressed}, on);
        sl.addState(new int[]{}, off);
        t.setBackground(sl);
        t.setElevation(Ui.dp(c, 2));
        // tinted icon bubble
        FrameLayout bub = new FrameLayout(c);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Ui.dp(c, 14));
        bg.setColor((tint & 0x00FFFFFF) | 0x26000000);
        bub.setBackground(bg);
        ImageView iv = icon(c, iconName, 22, tint);
        bub.addView(iv, new FrameLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 44), Gravity.CENTER));
        t.addView(bub, new LinearLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 44)));
        TextView tt = Ui.text(c, 15, Ui.TEXT, true);
        tt.setText(title);
        tt.setSingleLine(true);
        tt.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tt.setPadding(0, Ui.dp(c, 10), 0, 0);
        t.addView(tt);
        TextView ts = Ui.text(c, 12, Ui.DIM, false);
        ts.setText(sub);
        ts.setSingleLine(true);
        ts.setEllipsize(android.text.TextUtils.TruncateAt.END);
        ts.setTag("sub");
        t.addView(ts);
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(70).withEndAction(() ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()).start();
            if (l != null) l.onClick(v);
        });
        return t;
    }

    /** Grid of tiles, `cols` per row, equal widths. */
    public static LinearLayout grid(Context c, int cols, View... tiles) {
        LinearLayout g = new LinearLayout(c);
        g.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = null;
        for (int i = 0; i < tiles.length; i++) {
            if (i % cols == 0) {
                row = new LinearLayout(c);
                g.addView(row);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            lp.setMargins(Ui.dp(c, 5), Ui.dp(c, 5), Ui.dp(c, 5), Ui.dp(c, 5));
            row.addView(tiles[i], lp);
        }
        if (row != null) while (row.getChildCount() < cols) {
            row.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1));
        }
        return g;
    }

    // ================================================================== emoji → Lucide icon
    private static final String[][] EMOJI_ICON = {
            {"👨", "users"}, {"👶", "users"}, {"🧑", "users"}, {"📁", "folder"}, {"📂", "folder-open"}, {"📊", "gauge"},
            {"🎬", "film"}, {"🖧", "network"}, {"🌐", "languages"}, {"🕘", "clock"}, {"🎵", "list-music"}, {"★", "star"},
            {"⭐", "star"}, {"☆", "star"}, {"✓", "check"}, {"✅", "check"}, {"🧲", "magnet"}, {"🔍", "search"},
            {"🔎", "search"}, {"🗑", "trash"}, {"🔄", "refresh-cw"}, {"🩺", "gauge"}, {"🔓", "lock"}, {"🔒", "lock"},
            {"🔐", "lock"}, {"🎮", "gamepad-2"}, {"📱", "smartphone"}, {"⏰", "clock"}, {"⏱", "clock"}, {"⬇", "download"},
            {"📥", "download"}, {"💾", "hard-drive"}, {"🏠", "house"}, {"⌂", "house"}, {"⬆", "cloud-download"},
            {"🖥", "monitor"}, {"✏", "settings"}, {"＋", "plus"}, {"🎞", "film"}, {"🎨", "palette"}, {"📺", "tv"},
            {"🖼", "image"}, {"📐", "layout-grid"}, {"🎚", "sliders-horizontal"}, {"🔊", "volume-2"}, {"🔉", "volume-1"},
            {"🔇", "volume-x"}, {"💬", "captions"}, {"🔤", "languages"}, {"⚙", "settings"}, {"🔌", "server"},
            {"🧹", "trash"}, {"ℹ", "info"}, {"🔖", "bookmark"}, {"📸", "camera"}, {"🌙", "moon"}, {"🕶", "moon"},
            {"☀", "sun"}, {"📶", "wifi"}, {"⏩", "skip-forward"}, {"⏭", "skip-forward"}, {"⏪", "skip-back"},
            {"⏮", "skip-back"}, {"🔁", "repeat"}, {"🔂", "repeat-1"}, {"🔀", "shuffle"}, {"🗣", "mic"}, {"📋", "list"},
            {"📡", "cast"}, {"🚀", "sparkles"}, {"⧉", "layers"}, {"⋮", "menu"}, {"☰", "menu"}, {"▶", "play"},
            {"⏯", "circle-play"}, {"⏹", "circle-stop"}, {"✕", "x"}, {"❌", "x"}, {"⚠", "info"}, {"♻", "refresh-cw"},
            {"⌨", "keyboard"}, {"🎧", "audio-lines"}, {"🔔", "info"}, {"📲", "smartphone"}, {"⛶", "monitor"}, {"⬅", "arrow-left"}, {"🔑", "lock"}, {"🧠", "cpu"}, {"🌡", "gauge"}, {"➡", "chevron-right"}};

    /** Lucide icon for a text that starts with an emoji, or null. */
    public static String iconFor(String t) {
        if (t == null) return null;
        t = t.trim();
        for (String[] e : EMOJI_ICON) if (t.startsWith(e[0])) return e[1];
        return null;
    }

    /** Shows a row's leading emoji as a Lucide icon and removes any other emoji. */
    public static void iconize(TextView tv, int color) {
        String t = tv.getText().toString();
        String ic = iconFor(t);
        tv.setText(noEmoji(t));
        if (ic != null) {
            tv.setCompoundDrawablesWithIntrinsicBounds(new Icon(tv.getContext(), ic, 22, color), null, null, null);
            tv.setCompoundDrawablePadding(Ui.dp(tv.getContext(), 14));
        } else {
            tv.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);
        }
    }

    // typographic symbols that stay: arrows, ticks, stars, play marks, bullets
    private static final String KEEP = "→←↑↓✓✔✕★▶◀▲▼❚●○•·×–—…";

    /** Removes emoji / pictographs ("🔊 Volume" → "Volume"), keeps normal symbols like → ✓ ★. */
    public static String noEmoji(CharSequence cs) {
        if (cs == null) return "";
        String s = cs.toString();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            boolean keep = KEEP.indexOf(cp) >= 0;
            int type = Character.getType(cp);
            boolean emoji = !keep && (cp > 0xFFFF || type == Character.OTHER_SYMBOL || cp == 0xFE0F || cp == 0x200D
                    || cp == 0x20E3 || (cp >= 0x2190 && cp <= 0x21FF && cp > 0x2199) || cp == 0x2328 || cp == 0x23CF
                    || (cp >= 0x23E9 && cp <= 0x23FA));
            if (!emoji) b.appendCodePoint(cp);
            i += Character.charCount(cp);
        }
        return b.toString().replaceAll("[ \\t]{2,}", " ").replaceAll("(?m)^ +", "").trim();
    }

    public static android.widget.Toast makeToast(Context c, CharSequence text, int dur) {
        return android.widget.Toast.makeText(c, noEmoji(text), dur);   // the real Android toast (never call makeToast here)
    }

    /** Round Lucide button for dark overlays (TV player): white icon, glass circle. */
    public static ImageView darkButton(final Context c, String iconName, int sizeDp, boolean primary, View.OnClickListener l) {
        ImageView b = roundButton(c, iconName, sizeDp, primary, l);
        b.setImageDrawable(new Icon(c, iconName, Math.round(sizeDp * 0.46f), Color.WHITE));
        StateListDrawable sl = new StateListDrawable();
        GradientDrawable on = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{Ui.ACCENT, Ui.ACCENT2});
        on.setShape(GradientDrawable.OVAL);
        on.setStroke(Ui.dp(c, 2), Color.WHITE);
        GradientDrawable off;
        if (primary) off = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{Ui.ACCENT, Ui.ACCENT3});
        else {
            off = new GradientDrawable();
            off.setColor(0x33FFFFFF);
            off.setStroke(Ui.dp(c, 1), 0x33FFFFFF);
        }
        off.setShape(GradientDrawable.OVAL);
        sl.addState(new int[]{android.R.attr.state_focused}, on);
        sl.addState(new int[]{android.R.attr.state_pressed}, on);
        sl.addState(new int[]{}, off);
        b.setBackground(sl);
        b.setOnFocusChangeListener((v, f) -> {
            if (Ui.tvMode) v.animate().scaleX(f ? 1.15f : 1f).scaleY(f ? 1.15f : 1f).setDuration(120).start();
        });
        return b;
    }

    // ================================================================== level colour (green → yellow → red)
    public static int levelColor(float f) {
        f = Math.max(0, Math.min(1, f));
        int g = 0xFF22C55E, y = 0xFFEAB308, r = 0xFFEF4444;
        return f < 0.6f ? Palette.mix(g, y, f / 0.6f) : Palette.mix(y, r, (f - 0.6f) / 0.4f);
    }

    // ================================================================== vertical swipe slider
    public static final class PillSlider extends View {
        public interface Listener {
            void onChange(int value, boolean done);
        }

        private int min, max, value;
        private final String iconName;
        private Listener listener;
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
                txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private Icon icon;
        private float downY, downVal;
        private long touchAt;
        private final boolean colorByLevel;

        public PillSlider(Context c, String iconName, int min, int max, boolean colorByLevel) {
            super(c);
            this.iconName = iconName;
            this.min = min;
            this.max = max;
            this.value = min;
            this.colorByLevel = colorByLevel;
            track.setColor(Ui.dark() ? 0x1FFFFFFF : 0x141B1530);
            txt.setColor(Ui.TEXT);
            txt.setTextAlign(Paint.Align.CENTER);
            txt.setTextSize(Ui.dp(c, 12));
            txt.setFakeBoldText(true);
            icon = new Icon(c, iconName, 20, Color.WHITE);
            setFocusable(true);
        }

        public void setListener(Listener l) {
            listener = l;
        }

        public boolean touching() {
            return System.currentTimeMillis() - touchAt < 2500;
        }

        public void setValue(int v) {
            if (touching()) return;
            value = Math.max(min, Math.min(max, v));
            invalidate();
        }

        public int getValue() {
            return value;
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight() - Ui.dp(getContext(), 22);
            float rad = w / 2f;
            r.set(0, 0, w, h);
            c.drawRoundRect(r, rad, rad, track);
            float f = (value - min) / (float) (max - min);
            int col = colorByLevel ? levelColor(f) : Ui.ACCENT;
            fill.setShader(new LinearGradient(0, h, 0, h - h * f, col, (col & 0x00FFFFFF) | 0xCC000000, Shader.TileMode.CLAMP));
            float top = Math.min(h - w, h - h * f);
            r.set(0, top, w, h);
            c.save();
            Path clip = new Path();
            clip.addRoundRect(new RectF(0, 0, w, h), rad, rad, Path.Direction.CW);
            c.clipPath(clip);
            c.drawRect(0, h - h * f, w, h, fill);
            c.restore();
            icon.color(f > 0.12f ? Color.WHITE : Ui.DIM);
            int is = (int) Math.min(w * 0.55f, Ui.dp(getContext(), 22));
            icon.setBounds((int) (w / 2 - is / 2f), (int) (h - is - w * 0.25f), (int) (w / 2 + is / 2f), (int) (h - w * 0.25f));
            icon.draw(c);
            c.drawText(value + "%", w / 2f, getHeight() - Ui.dp(getContext(), 5), txt);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float h = getHeight() - Ui.dp(getContext(), 22);
            touchAt = System.currentTimeMillis();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    downY = e.getY();
                    downVal = value;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    int nv = Math.round(downVal + (downY - e.getY()) / h * (max - min));
                    nv = Math.max(min, Math.min(max, nv / 5 * 5));
                    if (nv != value) {
                        value = nv;
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        invalidate();
                        if (listener != null) listener.onChange(value, false);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (Math.abs(e.getY() - downY) < Ui.dp(getContext(), 6)) {
                        // tap: jump to the touched level
                        value = Math.max(min, Math.min(max, Math.round(min + (h - e.getY()) / h * (max - min)) / 5 * 5));
                        invalidate();
                    }
                    if (listener != null) listener.onChange(value, true);
                    return true;
                default:
                    return true;
            }
        }
    }

    // ================================================================== touchpad (Google TV style)
    public static final class TouchPad extends View {
        public interface Keys {
            void key(String k);
        }

        private final Keys keys;
        private float sx, sy, lx, ly;
        private long downAt;
        private boolean moved;
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG), hint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float rx = -1, ry = -1, rr = 0;

        public TouchPad(Context c, Keys k) {
            super(c);
            keys = k;
            GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{(Ui.ACCENT & 0x00FFFFFF) | 0x22000000, (Ui.ACCENT2 & 0x00FFFFFF) | 0x18000000});
            bg.setCornerRadius(Ui.dp(c, 28));
            bg.setStroke(Ui.dp(c, 1), Ui.STROKE);
            setBackground(bg);
            dot.setColor((Ui.ACCENT & 0x00FFFFFF) | 0x55000000);
            hint.setColor(Ui.DIM);
            hint.setTextAlign(Paint.Align.CENTER);
            hint.setTextSize(Ui.dp(c, 12));
        }

        @Override
        protected void onDraw(Canvas c) {
            c.drawText(T.t("Swipe to move · tap = OK · hold = back"), getWidth() / 2f, getHeight() - Ui.dp(getContext(), 14), hint);
            if (rr > 0) c.drawCircle(rx, ry, rr, dot);
        }

        private void ripple(float x, float y) {
            rx = x;
            ry = y;
            ValueAnimator a = ValueAnimator.ofFloat(Ui.dp(getContext(), 10), Ui.dp(getContext(), 60));
            a.setDuration(260);
            a.addUpdateListener(an -> {
                rr = (float) an.getAnimatedValue();
                dot.setAlpha((int) (120 * (1 - an.getAnimatedFraction())));
                invalidate();
            });
            a.start();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float step = Ui.dp(getContext(), 42);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    sx = lx = e.getX();
                    sy = ly = e.getY();
                    downAt = System.currentTimeMillis();
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getX() - lx, dy = e.getY() - ly;
                    // every `step` of finger movement = one arrow press (hold-and-drag moves continuously)
                    if (Math.abs(dx) > step || Math.abs(dy) > step) {
                        moved = true;
                        if (Math.abs(dx) > Math.abs(dy)) keys.key(dx > 0 ? "right" : "left");
                        else keys.key(dy > 0 ? "down" : "up");
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        ripple(e.getX(), e.getY());
                        lx = e.getX();
                        ly = e.getY();
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (!moved) {
                        long held = System.currentTimeMillis() - downAt;
                        float dx = e.getX() - sx, dy = e.getY() - sy;
                        if (Math.abs(dx) > step / 2 || Math.abs(dy) > step / 2) {
                            // quick flick
                            if (Math.abs(dx) > Math.abs(dy)) keys.key(dx > 0 ? "right" : "left");
                            else keys.key(dy > 0 ? "down" : "up");
                        } else if (held > 550) keys.key("back");
                        else keys.key("ok");
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        ripple(e.getX(), e.getY());
                    }
                    return true;
                default:
                    return true;
            }
        }
    }

    // ================================================================== opening animation
    private static boolean splashShown;

    /** Animated logo the first time the app opens. */
    public static void splash(final Activity a) {
        if (splashShown) return;
        splashShown = true;
        final FrameLayout root = a.findViewById(android.R.id.content);
        final FrameLayout s = new FrameLayout(a);
        s.setBackground(Ui.background());
        s.setClickable(true);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout logo = new FrameLayout(a);
        final View ring = new View(a);
        GradientDrawable rg = new GradientDrawable();
        rg.setShape(GradientDrawable.OVAL);
        rg.setStroke(Ui.dp(a, 3), (Ui.ACCENT & 0x00FFFFFF) | 0x88000000);
        ring.setBackground(rg);
        logo.addView(ring, new FrameLayout.LayoutParams(Ui.dp(a, 160), Ui.dp(a, 160), Gravity.CENTER));
        final FrameLayout bubble = logoBadge(a, 124, true);
        bubble.setElevation(Ui.dp(a, 12));
        logo.addView(bubble, new FrameLayout.LayoutParams(Ui.dp(a, 124), Ui.dp(a, 124), Gravity.CENTER));
        col.addView(logo, new LinearLayout.LayoutParams(Ui.dp(a, 160), Ui.dp(a, 160)));
        final TextView name = Ui.text(a, 26, Ui.TEXT, true);
        name.setText("ZH Play");
        name.setLetterSpacing(0.04f);
        col.addView(name);
        final TextView tag = Ui.text(a, 13, Ui.DIM, false);
        tag.setText("Tamizh Player  ·  " + T.t("Play · Cast · Control"));
        col.addView(tag);
        s.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        root.addView(s, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        bubble.setScaleX(0.3f);
        bubble.setScaleY(0.3f);
        bubble.setAlpha(0f);
        bubble.setRotation(-25);
        bubble.animate().scaleX(1).scaleY(1).alpha(1).rotation(0).setDuration(650).setInterpolator(new OvershootInterpolator(1.6f)).start();
        ring.setScaleX(0.6f);
        ring.setScaleY(0.6f);
        ring.setAlpha(0f);
        ring.animate().scaleX(1.25f).scaleY(1.25f).alpha(1).setStartDelay(250).setDuration(700)
                .withEndAction(() -> ring.animate().alpha(0).setDuration(300).start()).start();
        name.setAlpha(0f);
        name.setTranslationY(Ui.dp(a, 20));
        name.animate().alpha(1).translationY(0).setStartDelay(380).setDuration(450).setInterpolator(new DecelerateInterpolator()).start();
        tag.setAlpha(0f);
        tag.animate().alpha(1).setStartDelay(600).setDuration(400).start();
        s.postDelayed(() -> s.animate().alpha(0).scaleX(1.06f).scaleY(1.06f).setDuration(320)
                .withEndAction(() -> root.removeView(s)).start(), 1450);
    }
}
