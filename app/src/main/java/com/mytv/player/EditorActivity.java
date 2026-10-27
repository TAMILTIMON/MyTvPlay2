package com.mytv.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.ShortBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Multi-track song editor: import songs as clips, select / move / scroll, split, cut, copy, paste, fades, … export. */
public class EditorActivity extends AppCompatActivity {
    private static final int R44 = EditModel.RATE;
    private static final int[] COLORS = {0xFF22D3EE, 0xFFF472B6, 0xFFA3E635, 0xFFFBBF24, 0xFFA78BFA, 0xFFFB7185};
    private final Handler main = new Handler(Looper.getMainLooper());
    final EditModel model = new EditModel();
    private Timeline tl;
    private TextView info, timeT;
    private Button playBtn, undoBtn, redoBtn;
    private final Button[] modeBtns = new Button[3];
    private volatile boolean playing, alive = true;
    volatile long playhead;
    int mode;                     // 0 select, 1 move, 2 scroll
    int selTrack = 0;
    long selA = -1, selB = -1;
    EditModel.Clip selClip;

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    @Override
    protected void onCreate(Bundle b) {
        Ui.apply(this);
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B0B0F);
        root.setPadding(dp(10), dp(12), dp(10), dp(8));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(UiKit.darkButton(this, "arrow-left", 40, false, v -> finish()));
        TextView t = Ui.text(this, 19, Color.WHITE, true);
        t.setText("  " + T.t("Song editor"));
        top.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        undoBtn = tool("Undo", v -> {
            synchronized (model) {
                model.undo();
            }
            changed();
        });
        redoBtn = tool("Redo", v -> {
            synchronized (model) {
                model.redo();
            }
            changed();
        });
        top.addView(undoBtn);
        top.addView(redoBtn);
        Button ex = tool("Export", v -> export());
        ex.setBackground(Ui.gradient(this, 14));
        top.addView(ex);
        root.addView(top);

        tl = new Timeline(this);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        tlp.setMargins(0, dp(8), 0, dp(4));
        root.addView(tl, tlp);

        LinearLayout zr = new LinearLayout(this);
        zr.setGravity(Gravity.CENTER_VERTICAL);
        zr.addView(UiKit.icon(this, "search", 18, 0xB3FFFFFF));
        SeekBar z = new SeekBar(this);
        z.setMax(100);
        z.setContentDescription(T.t("Zoom"));
        z.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int v, boolean u) {
                if (u) tl.setZoom((float) Math.pow(200, v / 100.0));
            }

            public void onStartTrackingTouch(SeekBar s) { }

            public void onStopTrackingTouch(SeekBar s) { }
        });
        zr.addView(z, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        timeT = Ui.text(this, 13, Color.WHITE, true);
        zr.addView(timeT);
        root.addView(zr);

        LinearLayout tr = new LinearLayout(this);
        tr.setGravity(Gravity.CENTER_VERTICAL);
        tr.addView(UiKit.darkButton(this, "skip-back", 42, false, v -> {
            playhead = 0;
            tl.invalidate();
        }));
        playBtn = tool("Play", v -> togglePlay());
        playBtn.setBackground(Ui.gradient(this, 14));
        tr.addView(playBtn);
        String[] mn = {"Select", "Move", "Scroll"};
        for (int i = 0; i < 3; i++) {
            final int k = i;
            modeBtns[i] = tool(mn[i], v -> {
                mode = k;
                paintModes();
            });
            tr.addView(modeBtns[i]);
        }
        root.addView(tr);
        info = Ui.text(this, 11, 0xB3FFFFFF, false);
        info.setPadding(dp(4), dp(4), 0, dp(4));
        root.addView(info);

        String[][] tools = {{"Import song", "import"}, {"New track", "track"}, {"Split", "split"}, {"Cut", "cut"}, {"Copy", "copy"},
                {"Paste", "paste"}, {"Delete", "delete"}, {"Trim", "trim"}, {"Silence", "silence"}, {"Fade in", "fadein"},
                {"Fade out", "fadeout"}, {"Amplify", "amp"}, {"Normalize", "norm"}, {"Reverse", "rev"}};
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        hs.addView(row);
        for (final String[] x : tools) row.addView(tool(x[0], v -> action(x[1])));
        root.addView(hs);
        setContentView(root);
        paintModes();
        changed();
        main.post(tick);
    }

    private Button tool(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(T.t(label));
        b.setTextSize(12);
        b.setTextColor(Color.WHITE);
        b.setBackground(Ui.card(this, 0xFF26262E, 0, 14));
        b.setOnClickListener(l);
        b.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        b.setLayoutParams(lp);
        return b;
    }

    private void paintModes() {
        for (int i = 0; i < 3; i++) modeBtns[i].setBackground(i == mode ? Ui.card(this, 0xFF0E7490, 0, 14) : Ui.card(this, 0xFF26262E, 0, 14));
        info.setText(mode == 0 ? T.t("Select: drag across a track to select a part · tap = play position")
                : mode == 1 ? T.t("Move: drag a clip to a new time") : T.t("Scroll: drag to move along the timeline"));
    }

    void changed() {
        undoBtn.setAlpha(model.canUndo() ? 1f : 0.4f);
        redoBtn.setAlpha(model.canRedo() ? 1f : 0.4f);
        tl.invalidate();
    }

    private boolean hasSel() {
        if (model.tracks.isEmpty() || selA < 0 || selB <= selA + 10) {
            toast(T.t("Select a part first (Select mode, drag on a track)"));
            return false;
        }
        return true;
    }

    private void action(String a) {
        if (a.equals("import")) {
            pickSong();
            return;
        }
        if (a.equals("track")) {
            synchronized (model) {
                model.mark();
                model.addTrack(T.t("Track") + " " + (model.tracks.size() + 1));
            }
            selTrack = model.tracks.size() - 1;
            changed();
            return;
        }
        if (model.tracks.isEmpty()) {
            toast(T.t("Import a song first"));
            return;
        }
        synchronized (model) {
            switch (a) {
                case "split":
                    model.mark();
                    model.split(selTrack, playhead);
                    break;
                case "paste":
                    if (model.clipboard.isEmpty()) {
                        toast(T.t("Copy or cut a part first"));
                        return;
                    }
                    model.mark();
                    model.paste(selTrack, playhead);
                    break;
                case "amp":
                    if (!hasSel()) return;
                    final String[] o = {"+6 dB", "+3 dB", "−3 dB", "−6 dB"};
                    final float[] fs = {2f, 1.413f, 0.708f, 0.5f};
                    new UiDialog(this).setTitle(T.t("Amplify")).setItems(o, (d, w) -> {
                        synchronized (model) {
                            model.mark();
                            model.amplify(selTrack, selA, selB, fs[w]);
                        }
                        changed();
                    }).show();
                    return;
                default:
                    if (!hasSel()) return;
                    model.mark();
                    switch (a) {
                        case "cut": model.cut(selTrack, selA, selB); selB = selA; break;
                        case "copy": model.copy(selTrack, selA, selB); toast(T.t("Copied")); break;
                        case "delete": model.delete(selTrack, selA, selB, true); selB = selA; break;
                        case "trim": model.trim(selTrack, selA, selB); selA = 0; selB = model.length(); playhead = 0; break;
                        case "silence": model.silence(selTrack, selA, selB); break;
                        case "fadein": model.fadeIn(selTrack, selA, selB); break;
                        case "fadeout": model.fadeOut(selTrack, selA, selB); break;
                        case "norm": model.normalize(selTrack, selA, selB); break;
                        case "rev": model.reverse(selTrack, selA, selB); break;
                        default: break;
                    }
            }
        }
        changed();
    }

    // ------------------------------------------------------------------ importing a song (decoded to 44.1 kHz stereo)
    private void pickSong() {
        final List<PhoneMedia.M> all = PhoneMedia.query(this, true);
        if (all.isEmpty()) {
            toast(T.t("No music found on this phone."));
            return;
        }
        String[] n = new String[all.size()];
        for (int i = 0; i < n.length; i++) n[i] = all.get(i).title + "  ·  " + Net.fmtTime(all.get(i).dur);
        new UiDialog(this).setTitle(T.t("Import song")).setItems(n, (d, w) -> importSong(all.get(w))).show();
    }

    private void importSong(final PhoneMedia.M m) {
        final AlertDialog d = progress(T.t("Import song"));
        final ProgressBar bar = (ProgressBar) d.findViewById(android.R.id.progress);
        new Thread(() -> {
            try {
                File f = new File(getCacheDir(), "edit_" + System.nanoTime() + ".pcm");
                final List<Float> peaks = new ArrayList<>();
                final float[] pk = {0};
                final long[] count = {0};
                try (BufferedOutputStream o = new BufferedOutputStream(new FileOutputStream(f), 1 << 16)) {
                    final PcmFx.Resampler[] rs = new PcmFx.Resampler[1];
                    AudioTools.decode(this, m.uri, 0, 0, new AudioTools.PcmSink() {
                        public void format(int r, int ch) {
                            rs[0] = new PcmFx.Resampler(r, ch, R44, 2);
                        }

                        public void pcm(short[] data, int nf, long pts) throws Exception {
                            short[] s = rs[0].process(data, nf);
                            ByteBuffer bb = ByteBuffer.allocate(s.length * 2).order(ByteOrder.LITTLE_ENDIAN);
                            for (int i = 0; i < s.length / 2; i++) {
                                bb.putShort(s[2 * i]);
                                bb.putShort(s[2 * i + 1]);
                                pk[0] = Math.max(pk[0], Math.max(Math.abs(s[2 * i]), Math.abs(s[2 * i + 1])) / 32768f);
                                if (++count[0] % 1024 == 0) {
                                    peaks.add(pk[0]);
                                    pk[0] = 0;
                                }
                            }
                            o.write(bb.array());
                        }
                    }, (fr, w) -> main.post(() -> {
                        if (bar != null) bar.setProgress((int) (fr * 1000));
                    }));
                }
                f.deleteOnExit();
                final EditModel.Source src = new EditModel.Source(mapped(f), m.title);
                src.peaks = new float[peaks.size()];
                for (int i = 0; i < peaks.size(); i++) src.peaks[i] = peaks.get(i);
                main.post(() -> {
                    d.dismiss();
                    synchronized (model) {
                        model.mark();
                        if (model.tracks.isEmpty()) model.addTrack(T.t("Track") + " 1");
                        selTrack = Math.min(selTrack, model.tracks.size() - 1);
                        // empty selected track: at the start; otherwise at the play position
                        long at = model.tracks.get(selTrack).clips.isEmpty() ? 0 : playhead;
                        model.addClip(selTrack, src, at);
                    }
                    tl.fit();
                    changed();
                });
            } catch (final Exception e) {
                main.post(() -> {
                    d.dismiss();
                    toast("⚠ " + e.getMessage());
                });
            }
        }).start();
    }

    private static EditModel.Pcm mapped(File f) throws Exception {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            MappedByteBuffer m = r.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, r.length());
            m.order(ByteOrder.LITTLE_ENDIAN);
            final ShortBuffer sb = m.asShortBuffer();
            final long n = r.length() / 4;
            return new EditModel.Pcm() {
                public long frames() {
                    return n;
                }

                public short l(long i) {
                    return sb.get((int) (i * 2));
                }

                public short r(long i) {
                    return sb.get((int) (i * 2 + 1));
                }
            };
        }
    }

    private AlertDialog progress(String title) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(8), dp(22), 0);
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setId(android.R.id.progress);
        bar.setMax(1000);
        box.addView(bar);
        return new UiDialog(this).setTitle(title).setView(box).setCancelable(false).show();
    }

    // ------------------------------------------------------------------ playback
    private void togglePlay() {
        if (playing) {
            playing = false;
            return;
        }
        if (model.length() == 0) {
            toast(T.t("Import a song first"));
            return;
        }
        if (MusicService.playing()) MusicService.action(this, MusicService.A_TOGGLE);
        playing = true;
        new Thread(this::playLoop, "editor-play").start();
    }

    @SuppressWarnings("deprecation")
    private void playLoop() {
        int min = AudioTrack.getMinBufferSize(R44, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack at = new AudioTrack(AudioManager.STREAM_MUSIC, R44, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(min, 4096 * 4), AudioTrack.MODE_STREAM);
        short[] out = new short[2048 * 2];
        try {
            at.play();
            while (playing && alive) {
                long len;
                synchronized (model) {
                    len = model.length();
                    if (playhead >= len) break;
                    model.render(playhead, 2048, out);
                }
                at.write(out, 0, out.length);
                playhead += 2048;
            }
        } catch (Exception ignored) {
        } finally {
            playing = false;
            try {
                at.stop();
            } catch (Exception ignored) {
            }
            at.release();
        }
    }

    private final Runnable tick = new Runnable() {
        public void run() {
            if (!alive) return;
            playBtn.setText(T.t(playing ? "Pause" : "Play"));
            timeT.setText(WaveView.time(playhead * 1000 / R44, true) + " / " + WaveView.time(model.length() * 1000 / R44, false));
            if (playing) tl.follow();
            tl.invalidate();
            main.postDelayed(this, 50);
        }
    };

    // ------------------------------------------------------------------ export
    private void export() {
        if (model.length() == 0) {
            toast(T.t("Import a song first"));
            return;
        }
        playing = false;
        final EditText name = new EditText(this);
        name.setText(T.t("Edit") + " " + new java.text.SimpleDateFormat("d MMM HH.mm", Locale.getDefault()).format(new java.util.Date()));
        new UiDialog(this).setTitle(T.t("Export")).setView(name).setPositiveButton(T.t("Save"), (dd, ww) -> {
            final String nm = name.getText().toString().trim();
            final AlertDialog d = progress(T.t("Export"));
            final ProgressBar bar = (ProgressBar) d.findViewById(android.R.id.progress);
            new Thread(() -> {
                String msg;
                File out = AudioTools.temp(this, ".m4a");
                AudioTools.AacWriter w = null;
                try {
                    w = new AudioTools.AacWriter(out, R44, 2, 192_000);
                    long len = model.length();
                    short[] buf = new short[4096 * 2];
                    for (long p = 0; p < len; p += 4096) {
                        int n = (int) Math.min(4096, len - p);
                        synchronized (model) {
                            model.render(p, n, buf);
                        }
                        w.write(buf, n);
                        final float f = p / (float) len;
                        if ((p / 4096) % 25 == 0) main.post(() -> {
                            if (bar != null) bar.setProgress((int) (f * 1000));
                        });
                    }
                    w.finish();
                    w = null;
                    msg = "✓ " + AudioTools.publish(this, out, AudioTools.safe(nm.isEmpty() ? "Edit" : nm) + ".m4a")
                            + "\n\n" + T.t("Find it in Music → Folders → ZH Play.");
                } catch (Exception e) {
                    if (w != null) w.close();
                    msg = "⚠ " + e.getMessage();
                }
                final String m2 = msg;
                main.post(() -> {
                    d.dismiss();
                    new UiDialog(this).setTitle(T.t("Export")).setMessage(m2).setPositiveButton(T.t("OK"), null).show();
                });
            }).start();
        }).setNegativeButton(T.t("Cancel"), null).show();
    }

    private void toast(String s) {
        UiKit.makeToast(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        alive = false;
        playing = false;
        super.onDestroy();
    }

    // ================================================================== timeline view
    final class Timeline extends View {
        float zoom = 1f;          // pixels per 1024 frames × base
        long offset;              // first visible frame
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG), grid = new Paint();
        private float downX, downY, lastX;
        private boolean dragged;
        private long moveStartAt;

        Timeline(Context c) {
            super(c);
            txt.setColor(0xB3FFFFFF);
            txt.setTextSize(Ui.dp(c, 10));
            grid.setColor(0x1AFFFFFF);
            setBackground(Ui.card(c, 0xFF121218, 0x33FFFFFF, 16));
        }

        long total() {
            return Math.max(R44 * 30L, model.length() + R44 * 5L);
        }

        double fpp() {             // frames per pixel
            return total() / (double) Math.max(1, getWidth()) / zoom;
        }

        float x(long f) {
            return (float) ((f - offset) / fpp());
        }

        long f(float x) {
            return Math.max(0, offset + (long) (x * fpp()));
        }

        void setZoom(float z) {
            long c = playhead;
            zoom = Math.max(1f, Math.min(200f, z));
            offset = Math.max(0, c - (long) (getWidth() / 2f * fpp()));
            invalidate();
        }

        void fit() {
            zoom = 1f;
            offset = 0;
        }

        void follow() {
            if (x(playhead) > getWidth() * 0.9f) offset = playhead - (long) (getWidth() * 0.1f * fpp());
        }

        int laneH() {
            int n = Math.max(1, model.tracks.size());
            return (int) Math.min(Ui.dp(getContext(), 110), (getHeight() - Ui.dp(getContext(), 16)) / (float) n);
        }

        int trackAt(float y) {
            int i = (int) ((y - Ui.dp(getContext(), 16)) / laneH());
            return Math.max(0, Math.min(model.tracks.size() - 1, i));
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), ruler = Ui.dp(getContext(), 16);
            // ruler
            double secPx = R44 / fpp();
            double step = secPx > 120 ? 1 : secPx > 40 ? 5 : secPx > 15 ? 10 : 30;
            for (double s = Math.floor(offset / (double) R44 / step) * step; ; s += step) {
                float xx = x((long) (s * R44));
                if (xx > w) break;
                if (xx >= 0) {
                    c.drawLine(xx, ruler, xx, getHeight(), grid);
                    c.drawText(WaveView.time((long) (s * 1000), false), xx + 3, ruler - 4, txt);
                }
            }
            int lh = laneH();
            synchronized (model) {
                for (int ti = 0; ti < model.tracks.size(); ti++) {
                    EditModel.Track t = model.tracks.get(ti);
                    float top = ruler + ti * lh;
                    int col = COLORS[ti % COLORS.length];
                    if (ti == selTrack) {
                        p.setColor(0x14FFFFFF);
                        c.drawRect(0, top, w, top + lh, p);
                    }
                    for (EditModel.Clip cl : t.clips) {
                        float x0 = x(cl.at), x1 = x(cl.end());
                        if (x1 < 0 || x0 > w) continue;
                        RectF r = new RectF(Math.max(-10, x0), top + 4, Math.min(w + 10, x1), top + lh - 4);
                        p.setColor((col & 0x00FFFFFF) | (t.mute ? 0x22000000 : 0x40000000));
                        c.drawRoundRect(r, 10, 10, p);
                        // waveform inside the clip
                        if (cl.src.peaks != null) {
                            p.setColor(t.mute ? 0x55FFFFFF : col);
                            float mid = top + lh / 2f, half = lh / 2f - 8;
                            for (float px = Math.max(0, x0); px < Math.min(w, x1); px += 1.5f) {
                                long fr = f(px) - cl.at;
                                long si = cl.rev ? cl.srcB - 1 - fr : cl.srcA + fr;
                                int k = (int) Math.max(0, Math.min(cl.src.peaks.length - 1, si / 1024));
                                float amp = Math.min(1f, cl.src.peaks[k] * Math.abs(cl.gainAt(fr))) * half;
                                c.drawRect(px, mid - amp, px + 1f, mid + amp, p);
                            }
                        }
                        if (cl == selClip) {
                            p.setStyle(Paint.Style.STROKE);
                            p.setStrokeWidth(3);
                            p.setColor(Color.WHITE);
                            c.drawRoundRect(r, 10, 10, p);
                            p.setStyle(Paint.Style.FILL);
                        }
                    }
                    txt.setColor(col);
                    c.drawText((t.mute ? "[M] " : t.solo ? "[S] " : "") + t.name + (t.vol != 1f ? "  " + Math.round(t.vol * 100) + "%" : ""),
                            8, top + Ui.dp(getContext(), 13), txt);
                    txt.setColor(0xB3FFFFFF);
                }
            }
            if (model.tracks.isEmpty()) c.drawText(T.t("Tap “Import song” to start"), 20, getHeight() / 2f, txt);
            // selection
            if (selA >= 0 && selB > selA && !model.tracks.isEmpty()) {
                float top = ruler + selTrack * lh;
                p.setColor((Ui.ACCENT & 0x00FFFFFF) | 0x55000000);
                c.drawRect(x(selA), top, x(selB), top + lh, p);
            }
            p.setColor(0xFFEAB308);
            float ph = x(playhead);
            c.drawRect(ph - 1, 0, ph + 1, getHeight(), p);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float xx = e.getX();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    downX = lastX = xx;
                    downY = e.getY();
                    dragged = false;
                    if (!model.tracks.isEmpty()) selTrack = trackAt(downY);
                    if (xx < Ui.dp(getContext(), 90) && downY - Ui.dp(getContext(), 16) - selTrack * laneH() < Ui.dp(getContext(), 18)) {
                        trackMenu(selTrack);   // tap on a track name = its options
                        return true;
                    }
                    if (mode == 1) {
                        selClip = model.clipAt(selTrack, f(xx));
                        if (selClip != null) {
                            model.mark();
                            moveStartAt = selClip.at;
                        }
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(xx - downX) > 12) dragged = true;
                    if (!dragged) return true;
                    if (mode == 0) {
                        long a = f(downX), b = f(xx);
                        selA = Math.min(a, b);
                        selB = Math.max(a, b);
                    } else if (mode == 1 && selClip != null) {
                        synchronized (model) {
                            model.moveClip(selTrack, selClip, moveStartAt + (long) ((xx - downX) * fpp()));
                        }
                    } else if (mode == 2) {
                        offset = Math.max(0, offset - (long) ((xx - lastX) * fpp()));
                    }
                    lastX = xx;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!dragged) {
                        playhead = f(xx);
                        if (mode == 0) {
                            selA = selB = -1;
                        }
                    }
                    changed();
                    return true;
                default:
                    return true;
            }
        }
    }

    private void trackMenu(final int ti) {
        if (ti < 0 || ti >= model.tracks.size()) return;
        final EditModel.Track t = model.tracks.get(ti);
        String[] o = {T.t(t.mute ? "Unmute" : "Mute"), T.t(t.solo ? "Solo off" : "Solo"), T.t("Volume"), T.t("Rename"), T.t("Delete track")};
        new UiDialog(this).setTitle(t.name).setItems(o, (d, w) -> {
            synchronized (model) {
                switch (w) {
                    case 0: t.mute = !t.mute; break;
                    case 1: t.solo = !t.solo; break;
                    case 2: {
                        final String[] v = {"150 %", "125 %", "100 %", "75 %", "50 %", "25 %"};
                        final float[] fv = {1.5f, 1.25f, 1f, 0.75f, 0.5f, 0.25f};
                        new UiDialog(this).setTitle(T.t("Volume")).setItems(v, (d2, w2) -> {
                            t.vol = fv[w2];
                            changed();
                        }).show();
                        break;
                    }
                    case 3: {
                        final EditText e = new EditText(this);
                        e.setText(t.name);
                        new UiDialog(this).setTitle(T.t("Rename")).setView(e).setPositiveButton(T.t("Save"), (d2, w2) -> {
                            t.name = e.getText().toString().trim().isEmpty() ? t.name : e.getText().toString().trim();
                            changed();
                        }).show();
                        break;
                    }
                    default:
                        model.mark();
                        model.tracks.remove(ti);
                        selTrack = 0;
                }
            }
            changed();
        }).show();
    }
}
