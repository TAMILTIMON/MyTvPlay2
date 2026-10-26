package com.mytv.player;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** "Videos" / "Music" on this phone: everything found on the phone, plays here, small cast icon → TV. */
public class PhoneLibraryActivity extends AppCompatActivity {
    public static final String EXTRA_AUDIO = "audio";

    private boolean audio;
    private final List<PhoneMedia.M> all = new ArrayList<>(), shown = new ArrayList<>();
    private BaseAdapter ad;
    private TextView status;
    private String folder = "", query = "";
    private int sort = 0;   // 0 recent, 1 name, 2 size / artist
    private LinearLayout chips;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService ex = Executors.newFixedThreadPool(3);
    private LinearLayout mini;
    private TextView miniTitle;
    private ImageView miniPlay, miniArt;

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    @Override
    protected void onCreate(Bundle b) {
        Ui.apply(this);
        super.onCreate(b);
        audio = getIntent().getBooleanExtra(EXTRA_AUDIO, false);
        FrameLayout frame = new FrameLayout(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Ui.background());
        root.setPadding(dp(14), dp(16), dp(14), 0);
        frame.addView(root);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(UiKit.roundButton(this, "arrow-left", 42, false, v -> finish()));
        TextView h = Ui.text(this, 21, Ui.TEXT, true);
        h.setText("  " + T.t(audio ? "Music on this phone" : "Videos on this phone"));
        top.addView(h, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        top.addView(UiKit.roundButton(this, "sliders-horizontal", 42, false, v -> pickSort()));
        root.addView(top);

        EditText search = new EditText(this);
        search.setHint(T.t(audio ? "Search songs or artists" : "Search videos"));
        search.setSingleLine(true);
        search.setCompoundDrawablesWithIntrinsicBounds(new UiKit.Icon(this, "search", 18, Ui.DIM), null, null, null);
        search.setCompoundDrawablePadding(dp(8));
        search.setBackground(Ui.card(this, Ui.FIELD, Ui.STROKE, 18));
        search.setPadding(dp(14), dp(10), dp(14), dp(10));
        search.setTextColor(Ui.TEXT);
        search.setHintTextColor(Ui.DIM);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence c, int a, int b2, int d) { }
            public void onTextChanged(CharSequence c, int a, int b2, int d) { }
            public void afterTextChanged(Editable e) {
                query = e.toString().trim().toLowerCase(Locale.ROOT);
                apply();
            }
        });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.setMargins(0, dp(12), 0, dp(8));
        root.addView(search, sl);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        chips = new LinearLayout(this);
        hs.addView(chips);
        root.addView(hs);
        status = Ui.text(this, 12, Ui.DIM, false);
        status.setPadding(dp(4), dp(6), 0, dp(6));
        root.addView(status);

        final GridView grid = new GridView(this);
        grid.setNumColumns(audio ? 1 : 2);
        grid.setHorizontalSpacing(dp(10));
        grid.setVerticalSpacing(dp(audio ? 4 : 12));
        grid.setSelector(new android.graphics.drawable.ColorDrawable(0));
        grid.setClipToPadding(false);
        grid.setPadding(0, 0, 0, dp(audio ? 96 : 20));
        ad = new BaseAdapter() {
            public int getCount() { return shown.size(); }
            public Object getItem(int p) { return shown.get(p); }
            public long getItemId(int p) { return p; }
            public View getView(int p, View cv, ViewGroup parent) { return audio ? row(p, cv) : tile(p, cv, grid); }
        };
        grid.setAdapter(ad);
        grid.setOnItemClickListener((pa, v, pos, id) -> open(pos));
        root.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        if (audio) buildMini(frame);
        setContentView(frame);
        askPermissionThenLoad();
    }

    // ------------------------------------------------------------------ permission + loading
    private void askPermissionThenLoad() {
        String perm = Build.VERSION.SDK_INT >= 33 ? (audio ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_MEDIA_VIDEO)
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            status.setText(T.t("Allow access to find the videos and music on this phone."));
            requestPermissions(new String[]{perm}, 41);
            return;
        }
        load();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        super.onRequestPermissionsResult(req, p, r);
        if (r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) load();
        else status.setText(T.t("Permission needed — allow it in phone Settings > Apps > TamilCastPlay > Permissions."));
    }

    private void load() {
        status.setText(T.t("Searching this phone…"));
        ex.execute(() -> {
            final List<PhoneMedia.M> l = PhoneMedia.query(this, audio);
            main.post(() -> {
                all.clear();
                all.addAll(l);
                buildChips();
                apply();
            });
        });
    }

    private void buildChips() {
        chips.removeAllViews();
        Map<String, Integer> f = new LinkedHashMap<>();
        for (PhoneMedia.M m : all) if (!m.folder.isEmpty()) f.put(m.folder, (f.containsKey(m.folder) ? f.get(m.folder) : 0) + 1);
        List<Map.Entry<String, Integer>> es = new ArrayList<>(f.entrySet());
        Collections.sort(es, (a, b) -> b.getValue() - a.getValue());
        chip(T.t("All") + " · " + all.size(), "");
        for (int i = 0; i < es.size() && i < 14; i++) chip(es.get(i).getKey() + " · " + es.get(i).getValue(), es.get(i).getKey());
    }

    private void chip(String label, final String key) {
        Button c = Ui.button(this, label, key.equals(folder));
        c.setTextSize(13);
        c.setAllCaps(false);
        c.setOnClickListener(v -> {
            folder = key;
            buildChips();
            apply();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
        lp.setMargins(0, 0, dp(8), 0);
        chips.addView(c, lp);
    }

    private void pickSort() {
        String[] n = audio ? new String[]{T.t("Recently added"), T.t("Name"), T.t("Artist")}
                : new String[]{T.t("Recently added"), T.t("Name"), T.t("Biggest first")};
        new UiDialog(this).setTitle(T.t("Sort")).setSingleChoiceItems(n, sort, (d, w) -> {
            sort = w;
            apply();
            d.dismiss();
        }).show();
    }

    private void apply() {
        shown.clear();
        for (PhoneMedia.M m : all) {
            if (!folder.isEmpty() && !folder.equals(m.folder)) continue;
            if (!query.isEmpty() && !m.title.toLowerCase(Locale.ROOT).contains(query) && !m.sub.toLowerCase(Locale.ROOT).contains(query)) continue;
            shown.add(m);
        }
        if (sort == 1) Collections.sort(shown, (a, b) -> a.title.compareToIgnoreCase(b.title));
        else if (sort == 2) Collections.sort(shown, audio ? (a, b) -> a.sub.compareToIgnoreCase(b.sub) : (a, b) -> Long.compare(b.size, a.size));
        status.setText(all.isEmpty() ? T.t(audio ? "No music found on this phone." : "No videos found on this phone.")
                : shown.size() + " " + T.t(audio ? "songs" : "videos") + "  ·  " + T.t("tap to play here · cast icon = play on TV"));
        ad.notifyDataSetChanged();
    }

    // ------------------------------------------------------------------ cells
    private void loadArt(final ImageView iv, final PhoneMedia.M m) {
        iv.setTag(m.uri.toString());
        Bitmap c = PhoneMedia.cached(m);
        iv.setImageBitmap(c);
        if (c == null) {
            iv.setImageDrawable(new UiKit.Icon(this, audio ? "list-music" : "film", 26, 0xCCFFFFFF));
            ex.execute(() -> {
                final Bitmap b = PhoneMedia.art(this, m, audio);
                if (b != null) main.post(() -> {
                    if (m.uri.toString().equals(iv.getTag())) iv.setImageBitmap(b);
                });
            });
        }
    }

    private ImageView castIcon(final int pos) {
        ImageView c = UiKit.darkButton(this, "cast", 36, false, v -> toTv(pos));
        return c;
    }

    private View tile(int p, View cv, GridView grid) {
        LinearLayout t = (LinearLayout) cv;
        if (t == null) {
            t = new LinearLayout(this);
            t.setOrientation(LinearLayout.VERTICAL);
            FrameLayout pic = new FrameLayout(this);
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackground(Ui.gradient(this, 16));
            iv.setClipToOutline(true);
            pic.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            TextView d = Ui.text(this, 11, Color.WHITE, true);
            d.setPadding(dp(6), dp(2), dp(6), dp(2));
            d.setBackground(Ui.card(this, 0xB3000000, 0, 8));
            FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
            dl.setMargins(dp(8), 0, 0, dp(8));
            pic.addView(d, dl);
            FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(dp(36), dp(36), Gravity.TOP | Gravity.END);
            cl.setMargins(0, dp(6), dp(6), 0);
            pic.addView(new View(this), cl);   // cast icon placeholder (set per position)
            int w = (getResources().getDisplayMetrics().widthPixels - dp(38)) / 2;
            t.addView(pic, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, w * 9 / 16));
            TextView n = Ui.text(this, 13, Ui.TEXT, true);
            n.setMaxLines(2);
            n.setEllipsize(android.text.TextUtils.TruncateAt.END);
            n.setPadding(dp(2), dp(6), dp(2), 0);
            t.addView(n);
            TextView f = Ui.text(this, 11, Ui.DIM, false);
            f.setSingleLine(true);
            t.addView(f);
        }
        PhoneMedia.M m = shown.get(p);
        FrameLayout pic = (FrameLayout) t.getChildAt(0);
        loadArt((ImageView) pic.getChildAt(0), m);
        ((TextView) pic.getChildAt(1)).setText(Net.fmtTime(m.dur));
        View old = pic.getChildAt(2);
        ImageView cast = castIcon(p);
        pic.removeView(old);
        pic.addView(cast, old.getLayoutParams());
        ((TextView) t.getChildAt(1)).setText(m.title);
        ((TextView) t.getChildAt(2)).setText(m.folder + "  ·  " + TvInfo.gb(m.size));
        // the tile handles its own tap (a button inside a list cell blocks the list's item tap)
        t.setOnClickListener(v -> open(p));
        t.setBackground(Ui.focusCard(this, 16));
        return t;
    }

    private View row(int p, View cv) {
        LinearLayout r = (LinearLayout) cv;
        if (r == null) {
            r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(4), dp(8), dp(4), dp(8));
            ImageView a = new ImageView(this);
            a.setScaleType(ImageView.ScaleType.CENTER_CROP);
            a.setBackground(Ui.gradient(this, 12));
            a.setClipToOutline(true);
            r.addView(a, new LinearLayout.LayoutParams(dp(52), dp(52)));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(12), 0, dp(8), 0);
            TextView t = Ui.text(this, 15, Ui.TEXT, true);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            TextView s = Ui.text(this, 12, Ui.DIM, false);
            s.setSingleLine(true);
            s.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(t);
            col.addView(s);
            r.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            r.addView(new View(this), new LinearLayout.LayoutParams(dp(36), dp(36)));
        }
        PhoneMedia.M m = shown.get(p);
        loadArt((ImageView) r.getChildAt(0), m);
        LinearLayout col = (LinearLayout) r.getChildAt(1);
        boolean cur = MusicService.current() != null && MusicService.current().id == m.id;
        ((TextView) col.getChildAt(0)).setText(m.title);
        ((TextView) col.getChildAt(0)).setTextColor(cur ? Ui.ACCENT : Ui.TEXT);
        ((TextView) col.getChildAt(1)).setText(m.sub + "  ·  " + Net.fmtTime(m.dur));
        View old = r.getChildAt(2);
        ImageView cast = UiKit.roundButton(this, "cast", 36, false, v -> toTv(p));
        r.removeView(old);
        r.addView(cast, new LinearLayout.LayoutParams(dp(36), dp(36)));
        // tap the row = play here on the phone (the cast icon alone sends to the TV)
        r.setOnClickListener(v -> open(p));
        r.setBackground(Ui.focusCard(this, 14));
        return r;
    }

    // ------------------------------------------------------------------ actions
    private void open(int pos) {
        if (audio) {
            MusicService.play(this, shown, pos);
            startActivity(new android.content.Intent(this, MusicActivity.class));
            return;
        }
        List<Store.Item> items = new ArrayList<>();
        for (PhoneMedia.M m : shown) items.add(m.item());
        Remote.play(this, items, pos);   // plays here on the phone
    }

    private void toTv(final int pos) {
        final List<Store.Item> items = new ArrayList<>();
        for (PhoneMedia.M m : shown) items.add(m.item());
        UiKit.makeToast(this, T.t("Sending to the TV…"), android.widget.Toast.LENGTH_SHORT).show();
        ex.execute(() -> {
            final String r = PhoneMedia.sendToTv(this, items, pos, 0);
            main.post(() -> UiKit.makeToast(this, r, android.widget.Toast.LENGTH_LONG).show());
        });
    }

    // ------------------------------------------------------------------ mini player (music)
    private final Runnable miniUpdate = this::renderMini;

    private void buildMini(FrameLayout frame) {
        mini = new LinearLayout(this);
        mini.setGravity(Gravity.CENTER_VERTICAL);
        mini.setPadding(dp(10), dp(8), dp(10), dp(8));
        mini.setBackground(Ui.card(this, 0xF01C1236, 0x40FFFFFF, 22));
        mini.setElevation(dp(10));
        miniArt = new ImageView(this);
        miniArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        miniArt.setBackground(Ui.gradient(this, 12));
        miniArt.setClipToOutline(true);
        mini.addView(miniArt, new LinearLayout.LayoutParams(dp(46), dp(46)));
        miniTitle = Ui.text(this, 14, Color.WHITE, true);
        miniTitle.setSingleLine(true);
        miniTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        miniTitle.setPadding(dp(12), 0, dp(8), 0);
        mini.addView(miniTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        miniPlay = UiKit.darkButton(this, "pause", 44, true, v -> MusicService.action(this, MusicService.A_TOGGLE));
        mini.addView(miniPlay);
        View g = new View(this);
        mini.addView(g, new LinearLayout.LayoutParams(dp(8), 1));
        mini.addView(UiKit.darkButton(this, "skip-forward", 40, false, v -> MusicService.action(this, MusicService.A_NEXT)));
        mini.setOnClickListener(v -> startActivity(new android.content.Intent(this, MusicActivity.class)));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        lp.setMargins(dp(12), 0, dp(12), dp(14));
        frame.addView(mini, lp);
        mini.setVisibility(View.GONE);
    }

    private void renderMini() {
        if (mini == null) return;
        PhoneMedia.M m = MusicService.current();
        if (m == null || MusicService.inst == null) {
            mini.setVisibility(View.GONE);
            return;
        }
        mini.setVisibility(View.VISIBLE);
        miniTitle.setText(m.title + "  ·  " + m.sub);
        Bitmap b = PhoneMedia.cached(m);
        if (b != null) miniArt.setImageBitmap(b);
        else miniArt.setImageDrawable(new UiKit.Icon(this, "list-music", 22, Color.WHITE));
        UiKit.setIcon(this, miniPlay, MusicService.playing() ? "pause" : "play", 20, Color.WHITE);
        ad.notifyDataSetChanged();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (audio) {
            MusicService.listen(miniUpdate);
            renderMini();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        MusicService.unlisten(miniUpdate);
    }

    @Override
    protected void onDestroy() {
        ex.shutdownNow();
        super.onDestroy();
    }
}
