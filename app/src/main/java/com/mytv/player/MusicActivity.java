package com.mytv.player;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

/** Now playing (phone music): round album art in a glass ring, controls, up next, "play on TV". */
public class MusicActivity extends AppCompatActivity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView title, artist, tNow, tLen, ringTime;
    private ImageView art, playBtn, shufBtn, repBtn;
    private SeekBar seek;
    private ObjectAnimator spin;
    private LinearLayout queueBox;
    private long seekTouch;
    private int shownIndex = -2;
    private ScrollView sv;
    private View queueTitle;

    private final Runnable update = this::render;
    private final Runnable tick = new Runnable() {
        public void run() {
            renderTime();
            main.postDelayed(this, 500);
        }
    };

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    @Override
    protected void onCreate(Bundle b) {
        Ui.apply(this);
        super.onCreate(b);
        FrameLayout root = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{(Ui.ACCENT & 0x00FFFFFF) | 0xCC000000, 0xFF120A1E, 0xFF07050C});
        root.setBackground(bg);
        sv = new ScrollView(this);
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(20), dp(18), dp(20), dp(30));
        sv.addView(p);
        root.addView(sv);

        // top: close · play on TV · up next
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(UiKit.darkButton(this, "chevron-down", 44, false, v -> finish()));
        View sp = new View(this);
        top.addView(sp, new LinearLayout.LayoutParams(0, 1, 1));
        heartBtn = topBtn(top, "heart", v -> {
            PhoneMedia.M m = MusicService.current();
            if (m == null) return;
            MusicLib.setFav(m.uri.toString(), !MusicLib.isFav(m.uri.toString()));
            render();
        });
        topBtn(top, "sliders-horizontal", v -> MusicDialogs.equalizer(this));
        lyrBtn = topBtn(top, "mic-vocal", v -> toggleLyrics());
        topBtn(top, "cast", v -> toTv());
        topBtn(top, "ellipsis-vertical", v -> more());
        p.addView(top);

        title = Ui.text(this, 28, Color.WHITE, true);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 26);
        title.setPadding(0, dp(22), 0, 0);
        title.setMaxLines(2);
        artist = Ui.text(this, 15, 0xB3FFFFFF, false);
        p.addView(title);
        p.addView(artist);
        extra = Ui.text(this, 12, 0xFFF0ABFC, true);
        extra.setPadding(0, dp(4), 0, 0);
        p.addView(extra);

        // round art inside a glass ring
        int ringSize = Math.min((int) (getResources().getDisplayMetrics().widthPixels * 0.78f), dp(330));
        FrameLayout disc = new FrameLayout(this);
        View ring = new View(this);
        GradientDrawable rg = new GradientDrawable();
        rg.setShape(GradientDrawable.OVAL);
        rg.setColor(0x14FFFFFF);
        rg.setStroke(dp(1), 0x40FFFFFF);
        ring.setBackground(rg);
        ring.setElevation(dp(10));
        disc.addView(ring, new FrameLayout.LayoutParams(ringSize, ringSize, Gravity.CENTER));
        art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable ab = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{Ui.ACCENT, Ui.ACCENT2});
        ab.setShape(GradientDrawable.OVAL);
        art.setBackground(ab);
        art.setClipToOutline(true);
        art.setElevation(dp(14));
        int artSize = (int) (ringSize * 0.66f);
        disc.addView(art, new FrameLayout.LayoutParams(artSize, artSize, Gravity.CENTER));
        ringTime = Ui.text(this, 13, Color.WHITE, true);
        ringTime.setElevation(dp(16));
        FrameLayout.LayoutParams rtl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        rtl.setMargins(0, 0, 0, dp(18));
        disc.addView(ringTime, rtl);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ringSize);
        dl.setMargins(0, dp(26), 0, dp(20));
        FrameLayout stage = new FrameLayout(this);
        stage.addView(disc, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ringSize));
        lyrScroll = new ScrollView(this);
        lyrScroll.setVerticalScrollBarEnabled(false);
        lyrBox = new LinearLayout(this);
        lyrBox.setOrientation(LinearLayout.VERTICAL);
        lyrBox.setGravity(Gravity.CENTER_HORIZONTAL);
        lyrBox.setPadding(dp(8), ringSize / 3, dp(8), ringSize / 3);
        lyrScroll.addView(lyrBox);
        lyrScroll.setVisibility(View.GONE);
        lyrScroll.setBackground(Ui.card(this, 0x33000000, 0x22FFFFFF, 22));
        stage.addView(lyrScroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ringSize));
        p.addView(stage, dl);
        discView = disc;
        spin = ObjectAnimator.ofFloat(art, "rotation", 0f, 360f);
        spin.setDuration(24000);
        spin.setRepeatCount(ValueAnimator.INFINITE);
        spin.setInterpolator(new LinearInterpolator());

        seek = new SeekBar(this);
        seek.setMax(1000);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int v, boolean user) {
                if (user) {
                    seekTouch = System.currentTimeMillis();
                    tNow.setText(Net.fmtTime(MusicService.len() * v / 1000));
                }
            }

            public void onStartTrackingTouch(SeekBar s) {
                seekTouch = System.currentTimeMillis();
            }

            public void onStopTrackingTouch(SeekBar s) {
                MusicService.seek(MusicService.len() * s.getProgress() / 1000);
                seekTouch = 0;
            }
        });
        p.addView(seek);
        LinearLayout times = new LinearLayout(this);
        tNow = Ui.text(this, 12, 0xB3FFFFFF, false);
        tLen = Ui.text(this, 12, 0xB3FFFFFF, false);
        times.addView(tNow, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        times.addView(tLen);
        times.setPadding(dp(14), 0, dp(14), 0);
        p.addView(times);

        LinearLayout ctl = new LinearLayout(this);
        ctl.setGravity(Gravity.CENTER);
        ctl.setPadding(0, dp(16), 0, dp(8));
        shufBtn = add(ctl, "shuffle", 48, false, v -> {
            MusicService.shuffle = !MusicService.shuffle;
            Store.putBool("m_shuffle", MusicService.shuffle);
            render();
        });
        add(ctl, "skip-back", 56, false, v -> MusicService.action(this, MusicService.A_PREV));
        playBtn = add(ctl, "pause", 76, true, v -> MusicService.action(this, MusicService.A_TOGGLE));
        add(ctl, "skip-forward", 56, false, v -> MusicService.action(this, MusicService.A_NEXT));
        repBtn = add(ctl, "repeat", 48, false, v -> {
            MusicService.repeat = (MusicService.repeat + 1) % 3;
            Store.putInt("m_repeat", MusicService.repeat);
            render();
        });
        p.addView(ctl);

        TextView qt = Ui.text(this, 12, 0xB3FFFFFF, true);
        qt.setText(T.t("Up next").toUpperCase(java.util.Locale.ROOT));
        qt.setLetterSpacing(0.14f);
        qt.setPadding(0, dp(24), 0, dp(8));
        queueTitle = qt;
        p.addView(qt);
        queueBox = new LinearLayout(this);
        queueBox.setOrientation(LinearLayout.VERTICAL);
        p.addView(queueBox);
        setContentView(root);
    }

    private ImageView heartBtn, lyrBtn;
    private TextView extra;
    private ScrollView lyrScroll;
    private LinearLayout lyrBox;
    private View discView;
    private boolean lyricsOn;
    private List<MusicLib.Line> lines = new ArrayList<>();
    private long lyrFor = -1;
    private int lyrLine = -2;

    private ImageView topBtn(LinearLayout top, String ic, View.OnClickListener l) {
        ImageView b = UiKit.darkButton(this, ic, 42, false, l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(42), dp(42));
        lp.setMargins(dp(6), 0, 0, 0);
        top.addView(b, lp);
        return b;
    }

    private void toggleLyrics() {
        lyricsOn = !lyricsOn;
        lyrScroll.setVisibility(lyricsOn ? View.VISIBLE : View.GONE);
        discView.setVisibility(lyricsOn ? View.INVISIBLE : View.VISIBLE);
        lyrBtn.setAlpha(lyricsOn ? 1f : 0.7f);
        if (lyricsOn) loadLyrics();
    }

    private void loadLyrics() {
        final PhoneMedia.M m = MusicService.current();
        if (m == null || m.id == lyrFor) return;
        lyrFor = m.id;
        lyrLine = -2;
        lines = new ArrayList<>();
        lyrBox.removeAllViews();
        lyrBox.addView(lyrText(T.t("Looking for lyrics…"), false));
        new Thread(() -> {
            final List<MusicLib.Line> l = MusicLib.lyrics(m);
            main.post(() -> {
                if (MusicService.current() == null || MusicService.current().id != m.id) return;
                lines = l;
                lyrBox.removeAllViews();
                if (l.isEmpty()) {
                    lyrBox.addView(lyrText(T.t("No lyrics found for this song."), false));
                    lyrBox.addView(lyrText(T.t("Tip: correct the title / artist with Edit song info, then try again."), false));
                    return;
                }
                for (MusicLib.Line x : l) lyrBox.addView(lyrText(x.text.isEmpty() ? "♪" : x.text, false));
            });
        }).start();
    }

    private TextView lyrText(String t, boolean big) {
        TextView v = Ui.text(this, big ? 20 : 16, big ? Color.WHITE : 0x99FFFFFF, big);
        v.setText(t);
        v.setGravity(Gravity.CENTER);
        v.setPadding(0, dp(7), 0, dp(7));
        return v;
    }

    /** Highlight the line being sung and keep it in the middle. */
    private void syncLyrics() {
        if (!lyricsOn || lines.isEmpty()) return;
        int i = MusicLib.lineAt(lines, MusicService.pos());
        if (i == lyrLine || i < 0 || i >= lyrBox.getChildCount()) return;
        if (lyrLine >= 0 && lyrLine < lyrBox.getChildCount()) {
            TextView o = (TextView) lyrBox.getChildAt(lyrLine);
            o.setTextColor(0x99FFFFFF);
            o.setTextSize(16);
            o.setTypeface(null, android.graphics.Typeface.NORMAL);
        }
        lyrLine = i;
        TextView c = (TextView) lyrBox.getChildAt(i);
        c.setTextColor(Color.WHITE);
        c.setTextSize(20);
        c.setTypeface(null, android.graphics.Typeface.BOLD);
        lyrScroll.smoothScrollTo(0, Math.max(0, c.getTop() - lyrScroll.getHeight() / 2 + c.getHeight() / 2));
    }

    private void more() {
        final PhoneMedia.M m = MusicService.current();
        if (m == null) return;
        String sl = MusicDialogs.sleepLabel();
        String[] o = {"⏱  " + T.t("Playback speed") + ": " + MusicService.speed + "×",
                "🌙  " + T.t("Sleep timer") + (sl.isEmpty() ? "" : ": " + sl),
                "🎚  " + T.t("Equalizer"),
                "🎵  " + T.t("Add to playlist…"),
                "✏  " + T.t("Edit song info"),
                "🗣  " + T.t("Lyrics"),
                "📺  " + T.t("Play on the TV"),
                "🚀  " + T.t("Features") + " — " + T.t("cutter, karaoke, merger…")};
        new UiDialog(this).setTitle(m.title).setItems(o, (d, w) -> {
            switch (w) {
                case 0: MusicDialogs.speed(this, this::render); break;
                case 1: MusicDialogs.sleep(this, this::render); break;
                case 2: MusicDialogs.equalizer(this); break;
                case 7: startActivity(new Intent(this, ToolsActivity.class)); break;
                case 3: {
                    List<String> u = new ArrayList<>();
                    u.add(m.uri.toString());
                    MusicDialogs.addToPlaylist(this, u, this::render);
                    break;
                }
                case 4: MusicDialogs.editInfo(this, m, () -> {
                    lyrFor = -1;
                    shownIndex = -2;
                    render();
                }); break;
                case 5: toggleLyrics(); break;
                default: toTv();
            }
        }).show();
    }

    private void queueMenu(final int i) {
        final PhoneMedia.M m = MusicService.queue.get(i);
        String[] o = {"▶  " + T.t("Play now"), "⬆  " + T.t("Move up"), "⬇  " + T.t("Move down"), "🗑  " + T.t("Remove from queue"),
                "🎵  " + T.t("Add to playlist…"), (MusicLib.isFav(m.uri.toString()) ? "☆  " + T.t("Remove from favourites") : "★  " + T.t("Add to favourites"))};
        new UiDialog(this).setTitle(m.title).setItems(o, (d, w) -> {
            switch (w) {
                case 0: MusicService.play(this, MusicService.queue, i); break;
                case 1: MusicService.moveInQueue(i, i - 1); break;
                case 2: MusicService.moveInQueue(i, i + 1); break;
                case 3: MusicService.removeFromQueue(i); break;
                case 4: {
                    List<String> u = new ArrayList<>();
                    u.add(m.uri.toString());
                    MusicDialogs.addToPlaylist(this, u, null);
                    break;
                }
                default: MusicLib.setFav(m.uri.toString(), !MusicLib.isFav(m.uri.toString()));
            }
            shownIndex = -2;
            render();
        }).show();
    }

    private ImageView add(LinearLayout row, String ic, int size, boolean primary, View.OnClickListener l) {
        ImageView b = UiKit.darkButton(this, ic, size, primary, l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(size), dp(size));
        lp.setMargins(dp(8), 0, dp(8), 0);
        row.addView(b, lp);
        return b;
    }

    private void render() {
        PhoneMedia.M m = MusicService.current();
        if (m == null) {
            finish();
            return;
        }
        title.setText(m.title);
        artist.setText(m.albumName.isEmpty() || m.albumName.equals(m.sub) ? m.sub : m.sub + "  ·  " + m.albumName);
        boolean fav = MusicLib.isFav(m.uri.toString());
        heartBtn.setImageDrawable(new UiKit.Icon(this, "heart", 19, fav ? 0xFFFF4D6D : Color.WHITE));
        heartBtn.setContentDescription(T.t(fav ? "Remove from favourites" : "Add to favourites"));
        String sl = MusicDialogs.sleepLabel();
        extra.setText((MusicService.speed != 1f ? MusicService.speed + "×   " : "") + (sl.isEmpty() ? "" : T.t("Sleep") + " " + sl));
        extra.setVisibility(extra.getText().length() > 0 ? View.VISIBLE : View.GONE);
        if (lyricsOn && m.id != lyrFor) loadLyrics();
        Bitmap b = PhoneMedia.cached(m);
        art.setImageBitmap(b);
        if (b == null) art.setImageDrawable(new UiKit.Icon(this, "list-music", 64, Color.WHITE));
        boolean pl = MusicService.playing();
        UiKit.setIcon(this, playBtn, pl ? "pause" : "play", 32, Color.WHITE);
        if (pl) {
            if (!spin.isStarted()) spin.start();
            else spin.resume();
        } else if (spin.isRunning()) spin.pause();
        shufBtn.setAlpha(MusicService.shuffle ? 1f : 0.45f);
        UiKit.setIcon(this, repBtn, MusicService.repeat == 2 ? "repeat-1" : "repeat", 22, Color.WHITE);
        repBtn.setAlpha(MusicService.repeat == 0 ? 0.45f : 1f);
        if (shownIndex != MusicService.index) renderQueue();
        renderTime();
    }

    private void renderTime() {
        long len = MusicService.len(), pos = MusicService.pos();
        if (System.currentTimeMillis() - seekTouch > 1500) {
            seek.setProgress(len > 0 ? (int) (pos * 1000 / len) : 0);
            tNow.setText(Net.fmtTime(pos));
        }
        tLen.setText(Net.fmtTime(len));
        ringTime.setText(Net.fmtTime(Math.max(0, len - pos)));
        syncLyrics();
        if (MusicService.sleepAt > 0 && extra != null) {
            String sl = MusicDialogs.sleepLabel();
            extra.setText((MusicService.speed != 1f ? MusicService.speed + "×   " : "") + T.t("Sleep") + " " + sl);
        }
    }

    private void renderQueue() {
        shownIndex = MusicService.index;
        queueBox.removeAllViews();
        final List<PhoneMedia.M> q = MusicService.queue;
        int from = Math.max(0, MusicService.index), to = Math.min(q.size(), from + 300);
        for (int i = from; i < to; i++) {
            final int k = i;
            final PhoneMedia.M m = q.get(i);
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(8), dp(8), dp(8), dp(8));
            if (i == MusicService.index) r.setBackground(Ui.card(this, 0x26FFFFFF, 0x33FFFFFF, 16));
            final ImageView a = new ImageView(this);
            a.setScaleType(ImageView.ScaleType.CENTER_CROP);
            a.setBackground(Ui.card(this, 0x26FFFFFF, 0, 12));
            a.setClipToOutline(true);
            r.addView(a, new LinearLayout.LayoutParams(dp(50), dp(50)));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(12), 0, dp(8), 0);
            TextView t = Ui.text(this, 15, Color.WHITE, true);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setText(m.title);
            TextView s = Ui.text(this, 12, 0x99FFFFFF, false);
            s.setSingleLine(true);
            s.setText(m.sub);
            col.addView(t);
            col.addView(s);
            r.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView d = Ui.text(this, 12, 0x99FFFFFF, false);
            d.setText(Net.fmtTime(m.dur));
            r.addView(d);
            r.setOnClickListener(v -> MusicService.play(this, q, k));
            r.setOnLongClickListener(v -> {
                queueMenu(k);
                return true;
            });
            queueBox.addView(r);
            Bitmap c = PhoneMedia.cached(m);
            if (c != null) a.setImageBitmap(c);
            else new Thread(() -> {
                Bitmap bm = PhoneMedia.art(this, m, true);
                if (bm != null) main.post(() -> a.setImageBitmap(bm));
            }).start();
        }
    }

    /** Continue this song (and the rest of the list) on the TV at the same second. */
    private void toTv() {
        final List<Store.Item> items = new ArrayList<>();
        for (PhoneMedia.M m : MusicService.queue) items.add(m.item());
        final int i = Math.max(0, MusicService.index);
        final long at = MusicService.pos();
        if (MusicService.playing()) MusicService.action(this, MusicService.A_TOGGLE);
        UiKit.makeToast(this, T.t("Sending to the TV…"), android.widget.Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final String r = PhoneMedia.sendToTv(this, items, i, at);
            main.post(() -> UiKit.makeToast(this, r, android.widget.Toast.LENGTH_LONG).show());
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        MusicService.listen(update);
        render();
        main.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        MusicService.unlisten(update);
        main.removeCallbacks(tick);
    }
}
