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
        top.addView(UiKit.roundButton(this, "layout-grid", 42, false, v -> pickView()));
        View g1 = new View(this);
        top.addView(g1, new LinearLayout.LayoutParams(dp(8), 1));
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

        grid = new GridView(this);
        mode = Store.getInt(audio ? "lib_view_a" : "lib_view_v", 1);
        grid.setSelector(new android.graphics.drawable.ColorDrawable(0));
        grid.setClipToPadding(false);
        grid.setPadding(0, 0, 0, dp(audio ? 96 : 20));
        ad = new BaseAdapter() {
            public int getCount() { return shown.size(); }
            public Object getItem(int p) { return shown.get(p); }
            public long getItemId(int p) { return p; }
            public View getView(int p, View cv, ViewGroup parent) { return cell(p, cv); }
        };
        grid.setAdapter(ad);
        root.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        applyMode();

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
        else status.setText(T.t("Permission needed — allow it in phone Settings > Apps > ZH Play > Permissions."));
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
                : shown.size() + " " + T.t(audio ? "songs" : "videos") + "  ·  " + T.t("tap = play here · cast icon = TV · hold = details"));
        ad.notifyDataSetChanged();
    }

    // ------------------------------------------------------------------ view modes (like Windows Explorer)
    private GridView grid;
    private int mode;
    private static final String[] MODE_NAME = {"Extra large icons", "Large icons", "Medium icons", "Small icons", "List", "Details",
            "Tiles", "Content"};
    private static final String[] MODE_ICON = {"square", "grid-2x2", "grid-3x3", "layout-grid", "list", "table", "layout-list", "rows-3"};
    private static final int[] MODE_COLS = {1, 2, 3, 4, 1, 1, 2, 1};

    private void pickView() {
        final android.widget.ListAdapter la = new android.widget.ArrayAdapter<String>(this, android.R.layout.select_dialog_item, MODE_NAME) {
            @Override
            public View getView(int p, View cv, ViewGroup parent) {
                TextView t = (TextView) super.getView(p, cv, parent);
                t.setText(T.t(MODE_NAME[p]) + (p == mode ? "   ✓" : ""));
                t.setCompoundDrawablesWithIntrinsicBounds(new UiKit.Icon(PhoneLibraryActivity.this, MODE_ICON[p], 20,
                        p == mode ? Ui.ACCENT : Ui.TEXT), null, null, null);
                t.setCompoundDrawablePadding(dp(16));
                return t;
            }
        };
        new androidx.appcompat.app.AlertDialog.Builder(this).setTitle(T.t("View")).setAdapter(la, (d, w) -> {
            mode = w;
            Store.putInt(audio ? "lib_view_a" : "lib_view_v", w);
            applyMode();
        }).show();
    }

    private void applyMode() {
        int cols = MODE_COLS[mode];
        grid.setNumColumns(cols);
        boolean cards = mode <= 3 || mode == 6;
        grid.setHorizontalSpacing(dp(cards ? 10 : 0));
        grid.setVerticalSpacing(dp(cards ? (mode == 3 ? 8 : 12) : 2));
        grid.setAdapter(ad);   // rebuild cells for the new layout
    }

    private int cellWidth() {
        int cols = MODE_COLS[mode];
        int w = getResources().getDisplayMetrics().widthPixels - dp(28);
        return (w - dp(mode <= 3 || mode == 6 ? 10 : 0) * (cols - 1)) / cols;
    }

    // ------------------------------------------------------------------ cells
    private void loadArt(final ImageView iv, final PhoneMedia.M m) {
        iv.setTag(m.uri.toString());
        Bitmap c = PhoneMedia.cached(m);
        if (c != null) {
            iv.setImageBitmap(c);
            return;
        }
        iv.setImageDrawable(PhoneMedia.placeholder(this, m, audio));
        ex.execute(() -> {
            final Bitmap b = PhoneMedia.art(this, m, audio);
            if (b != null) main.post(() -> {
                if (m.uri.toString().equals(iv.getTag())) iv.setImageBitmap(b);
            });
        });
    }

    private ImageView cover(int radius) {
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackground(Ui.card(this, 0xFF1F1A2E, 0, radius));
        iv.setClipToOutline(true);
        return iv;
    }

    private TextView line(int sp, int color, boolean bold, int maxLines) {
        TextView t = Ui.text(this, sp, color, bold);
        t.setMaxLines(maxLines);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    private View cell(final int p, View cv) {
        if (cv != null && Integer.valueOf(mode).equals(cv.getTag(R.id.w_title))) {
            bind(p, cv);
            return cv;
        }
        View v;
        if (mode <= 3) v = makeCard();
        else if (mode == 6) v = makeTile();
        else v = makeRow();
        v.setTag(R.id.w_title, mode);
        bind(p, v);
        return v;
    }

    /** Card: big cover (square for songs, 16:9 for videos), duration badge, cast button, title, artist. */
    private View makeCard() {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(0, 0, 0, dp(6));
        FrameLayout pic = new FrameLayout(this);
        int w = cellWidth();
        int h = audio ? w : w * 9 / 16;
        pic.addView(cover(mode == 3 ? 12 : 18), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView d = Ui.text(this, mode >= 2 ? 10 : 11, Color.WHITE, true);
        d.setPadding(dp(6), dp(2), dp(6), dp(2));
        d.setBackground(Ui.card(this, 0xB3000000, 0, 8));
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        dl.setMargins(dp(7), 0, 0, dp(7));
        pic.addView(d, dl);
        int cs = mode >= 2 ? 30 : 36;
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(dp(cs), dp(cs), Gravity.TOP | Gravity.END);
        cl.setMargins(0, dp(6), dp(6), 0);
        pic.addView(new View(this), cl);
        t.addView(pic, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));
        TextView n = line(mode == 0 ? 16 : mode >= 2 ? 12 : 14, Ui.TEXT, true, mode == 3 ? 1 : 2);
        n.setPadding(dp(3), dp(7), dp(3), 0);
        t.addView(n);
        TextView s = line(mode >= 2 ? 10 : 12, Ui.DIM, false, 1);
        s.setPadding(dp(3), 0, dp(3), 0);
        if (mode == 3) s.setVisibility(View.GONE);
        t.addView(s);
        return t;
    }

    /** Tile: cover on the left, text on the right, in a rounded card. */
    private View makeTile() {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(8), dp(8), dp(8), dp(8));
        r.addView(cover(14), new LinearLayout.LayoutParams(dp(64), audio ? dp(64) : dp(40)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(10), 0, dp(4), 0);
        col.addView(line(13, Ui.TEXT, true, 2));
        col.addView(line(11, Ui.DIM, false, 1));
        col.addView(line(11, Ui.DIM, false, 1));
        r.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        r.addView(new View(this), new LinearLayout.LayoutParams(1, 1));   // (no cast button in tiles: long-press menu)
        return r;
    }

    /** List / Details / Content rows. */
    private View makeRow() {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(6), dp(mode == 7 ? 10 : 8), dp(4), dp(mode == 7 ? 10 : 8));
        int as = mode == 7 ? 84 : mode == 5 ? 40 : 52;
        r.addView(cover(mode == 7 ? 14 : 10), new LinearLayout.LayoutParams(dp(as), audio || mode != 7 ? dp(as) : dp(as) * 9 / 16));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), 0, dp(8), 0);
        col.addView(line(mode == 5 ? 14 : 15, Ui.TEXT, true, mode == 7 ? 2 : 1));
        col.addView(line(12, Ui.DIM, false, 1));
        TextView third = line(11, Ui.DIM, false, 1);
        third.setVisibility(mode == 7 ? View.VISIBLE : View.GONE);
        col.addView(third);
        r.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (mode == 5) {
            // Details: right column with duration / size / date
            LinearLayout right = new LinearLayout(this);
            right.setOrientation(LinearLayout.VERTICAL);
            right.setGravity(Gravity.END);
            right.addView(line(12, Ui.TEXT, true, 1));
            right.addView(line(11, Ui.DIM, false, 1));
            right.addView(line(11, Ui.DIM, false, 1));
            r.addView(right);
        }
        r.addView(new View(this), new LinearLayout.LayoutParams(dp(36), dp(36)));
        return r;
    }

    private String date(long ms) {
        return new java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(new java.util.Date(ms));
    }

    private void bind(final int p, View v) {
        final PhoneMedia.M m = shown.get(p);
        boolean cur = audio && MusicService.current() != null && MusicService.current().id == m.id;
        if (mode <= 3) {
            LinearLayout t = (LinearLayout) v;
            FrameLayout pic = (FrameLayout) t.getChildAt(0);
            loadArt((ImageView) pic.getChildAt(0), m);
            ((TextView) pic.getChildAt(1)).setText(Net.fmtTime(m.dur));
            View old = pic.getChildAt(2);
            ImageView cast = UiKit.darkButton(this, "cast", mode >= 2 ? 30 : 36, false, x -> toTv(p));
            pic.removeView(old);
            pic.addView(cast, old.getLayoutParams());
            TextView n = (TextView) t.getChildAt(1);
            n.setText(m.title);
            n.setTextColor(cur ? Ui.ACCENT : Ui.TEXT);
            ((TextView) t.getChildAt(2)).setText(audio ? m.sub : m.folder + "  ·  " + TvInfo.gb(m.size));
        } else if (mode == 6) {
            LinearLayout r = (LinearLayout) v;
            loadArt((ImageView) r.getChildAt(0), m);
            LinearLayout col = (LinearLayout) r.getChildAt(1);
            ((TextView) col.getChildAt(0)).setText(m.title);
            ((TextView) col.getChildAt(0)).setTextColor(cur ? Ui.ACCENT : Ui.TEXT);
            ((TextView) col.getChildAt(1)).setText(audio ? m.sub : m.folder);
            ((TextView) col.getChildAt(2)).setText(Net.fmtTime(m.dur) + "  ·  " + TvInfo.gb(m.size));
            r.setBackground(Ui.card(this, Ui.SOLID, Ui.STROKE, 16));
        } else {
            LinearLayout r = (LinearLayout) v;
            loadArt((ImageView) r.getChildAt(0), m);
            LinearLayout col = (LinearLayout) r.getChildAt(1);
            ((TextView) col.getChildAt(0)).setText(m.title);
            ((TextView) col.getChildAt(0)).setTextColor(cur ? Ui.ACCENT : Ui.TEXT);
            String sub = audio ? m.sub : m.folder;
            ((TextView) col.getChildAt(1)).setText(mode == 5 ? sub : sub + "  ·  " + Net.fmtTime(m.dur));
            ((TextView) col.getChildAt(2)).setText(Net.fmtTime(m.dur) + "  ·  " + TvInfo.gb(m.size) + "  ·  " + date(m.added));
            int castAt = 2;
            if (mode == 5) {
                LinearLayout right = (LinearLayout) r.getChildAt(2);
                ((TextView) right.getChildAt(0)).setText(Net.fmtTime(m.dur));
                ((TextView) right.getChildAt(1)).setText(TvInfo.gb(m.size));
                ((TextView) right.getChildAt(2)).setText(date(m.added));
                castAt = 3;
            }
            View old = r.getChildAt(castAt);
            r.removeView(old);
            r.addView(UiKit.roundButton(this, "cast", 36, false, x -> toTv(p)), new LinearLayout.LayoutParams(dp(36), dp(36)));
        }
        // the cell handles its own taps (a button inside a cell blocks the list's tap)
        v.setOnClickListener(x -> open(p));
        v.setOnLongClickListener(x -> {
            details(m, p);
            return true;
        });
        if (mode != 6) v.setBackground(Ui.focusCard(this, 16));
    }

    // ------------------------------------------------------------------ details sheet
    private void details(final PhoneMedia.M m, final int p) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(4));
        final ImageView big = cover(20);
        int w = getResources().getDisplayMetrics().widthPixels - dp(100);
        box.addView(big, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, audio ? Math.min(w, dp(300)) : w * 9 / 16));
        loadArt(big, m);
        TextView t = line(18, Ui.TEXT, true, 3);
        t.setText(m.title);
        t.setPadding(0, dp(12), 0, dp(6));
        box.addView(t);
        final LinearLayout table = new LinearLayout(this);
        table.setOrientation(LinearLayout.VERTICAL);
        box.addView(table);
        TextView wait = line(12, Ui.DIM, false, 1);
        wait.setText("…");
        table.addView(wait);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(box);
        new UiDialog(this).setView(sv)
                .setPositiveButton(T.t("Play here"), (d, x) -> open(p))
                .setNeutralButton(T.t("Play on the TV"), (d, x) -> toTv(p))
                .setNegativeButton(T.t("Close"), null).show();
        ex.execute(() -> {
            final java.util.LinkedHashMap<String, String> info = PhoneMedia.details(this, m, audio);
            main.post(() -> {
                table.removeAllViews();
                for (java.util.Map.Entry<String, String> e : info.entrySet()) {
                    LinearLayout row = new LinearLayout(this);
                    row.setPadding(0, dp(5), 0, dp(5));
                    TextView k = line(13, Ui.DIM, false, 1);
                    k.setText(e.getKey());
                    row.addView(k, new LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT));
                    TextView val = line(13, Ui.TEXT, false, 3);
                    val.setText(e.getValue());
                    row.addView(val, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                    table.addView(row);
                }
            });
        });
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
