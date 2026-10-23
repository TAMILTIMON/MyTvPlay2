package com.mytv.player;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 🎬 Movies as a poster grid: Downloads + USB + local storage, watched ✓, sort and filter. */
public class LibraryActivity extends AppCompatActivity {
    static final class Movie {
        String name, url, title, year;
        long size, added;
    }

    private final List<Movie> all = new ArrayList<>(), shown = new ArrayList<>();
    private BaseAdapter adapter;
    private TextView status;
    private Button sortBtn, filterBtn;
    private int sort = 0, filter = 0;   // sort: recent, name, size; filter: all, unwatched, watched, last 30 days
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService ex = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount();
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        Ui.apply(this);
        super.onCreate(b);
        final boolean tv = Ui.isTv(this);
        int pad = Ui.dp(this, tv ? 32 : 10);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Ui.background());
        root.setPadding(pad, Ui.dp(this, 20), pad, 0);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(btn("🏠", false, v -> Ui.goHome(this, true)));
        TextView h = Ui.text(this, 22, Ui.TEXT, true);
        h.setText("  🎬 " + T.t("Library"));
        top.addView(h, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        sortBtn = btn("", false, v -> {
            String[] n = {T.t("Recently added"), T.t("Name"), T.t("Biggest first")};
            new AlertDialog.Builder(this).setTitle(T.t("Sort")).setItems(n, (d, w) -> {
                sort = w;
                apply();
            }).show();
        });
        filterBtn = btn("", false, v -> {
            String[] n = {T.t("All"), T.t("Not watched"), T.t("Watched"), T.t("Added in the last 30 days")};
            new AlertDialog.Builder(this).setTitle(T.t("Show")).setItems(n, (d, w) -> {
                filter = w;
                apply();
            }).show();
        });
        top.addView(sortBtn);
        top.addView(filterBtn);
        top.addView(btn("🔑", false, v -> keyDialog()));
        root.addView(top);
        status = Ui.text(this, 13, Ui.DIM, false);
        status.setPadding(Ui.dp(this, 4), Ui.dp(this, 8), 0, Ui.dp(this, 8));
        root.addView(status);

        final GridView grid = new GridView(this);
        grid.setColumnWidth(Ui.dp(this, tv ? 150 : 110));
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(Ui.dp(this, 12));
        grid.setVerticalSpacing(Ui.dp(this, 14));
        grid.setSelector(Ui.listSelector(this));
        grid.setDrawSelectorOnTop(true);
        grid.setClipToPadding(false);
        grid.setPadding(0, 0, 0, Ui.dp(this, 24));
        adapter = new BaseAdapter() {
            public int getCount() { return shown.size(); }
            public Object getItem(int p) { return shown.get(p); }
            public long getItemId(int p) { return p; }
            public View getView(int p, View cv, ViewGroup parent) { return tile(p, cv, grid); }
        };
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((pa, v, pos, id) -> details(shown.get(pos)));
        root.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        scan();
    }

    private Button btn(String t, boolean primary, View.OnClickListener l) {
        Button b = Ui.button(this, t, primary);
        b.setTextSize(14);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 42));
        lp.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        b.setLayoutParams(lp);
        return b;
    }

    // ------------------------------------------------------------ finding movies
    private void scan() {
        status.setText("🔎 " + T.t("Looking for movies…"));
        ex.execute(() -> {
            LinkedHashMap<String, Movie> found = new LinkedHashMap<>();
            for (Store.Item it : Store.items("Downloads")) if (it.u.startsWith("/")) add(found, new File(it.u));
            List<File> roots = new ArrayList<>();
            roots.add(Environment.getExternalStorageDirectory());
            File[] st = new File("/storage").listFiles();
            if (st != null) for (File f : st) if (!f.getName().equals("self") && !f.getName().equals("emulated")) roots.add(f);
            for (File t : TorrentEngineRoots.list(this)) roots.add(t);
            for (File r : roots) walk(found, r, 0);
            final List<Movie> l = new ArrayList<>(found.values());
            main.post(() -> {
                all.clear();
                all.addAll(l);
                apply();
            });
        });
    }

    private void walk(LinkedHashMap<String, Movie> found, File d, int depth) {
        if (depth > 5 || found.size() > 3000) return;
        File[] k = d.listFiles();
        if (k == null) return;
        for (File f : k) {
            if (f.getName().startsWith(".") || f.getName().equals("Android")) continue;
            if (f.isDirectory()) walk(found, f, depth + 1);
            else add(found, f);
        }
    }

    private void add(LinkedHashMap<String, Movie> found, File f) {
        if (!f.isFile() || !Formats.isVideo(f.getName()) || f.length() < 40L * 1024 * 1024) return;
        String p = f.getAbsolutePath();
        if (found.containsKey(p)) return;
        Movie m = new Movie();
        m.name = f.getName();
        m.url = p;
        m.title = MovieInfo.title(m.name);
        m.year = MovieInfo.year(m.name);
        m.size = f.length();
        m.added = f.lastModified();
        found.put(p, m);
    }

    private void apply() {
        shown.clear();
        long month = System.currentTimeMillis() - 30L * 24 * 3600 * 1000;
        for (Movie m : all) {
            boolean w = Store.isWatched(m.url);
            if (filter == 1 && w) continue;
            if (filter == 2 && !w) continue;
            if (filter == 3 && m.added < month) continue;
            shown.add(m);
        }
        if (sort == 0) Collections.sort(shown, (a, b) -> Long.compare(b.added, a.added));
        else if (sort == 1) Collections.sort(shown, (a, b) -> a.title.compareToIgnoreCase(b.title));
        else Collections.sort(shown, (a, b) -> Long.compare(b.size, a.size));
        String[] sn = {T.t("Recently added"), T.t("Name"), T.t("Biggest first")};
        String[] fn = {T.t("All"), T.t("Not watched"), T.t("Watched"), T.t("Added in the last 30 days")};
        sortBtn.setText("↕ " + sn[sort]);
        filterBtn.setText("⏷ " + fn[filter]);
        status.setText(all.isEmpty() ? T.t("No movies found. Downloads, USB drives and storage are searched (videos over 40 MB).")
                : shown.size() + " / " + all.size() + " " + T.t("movies") + "  ·  " + T.t("OK = details & play"));
        adapter.notifyDataSetChanged();
    }

    // ------------------------------------------------------------ tiles
    private View tile(int p, View cv, GridView grid) {
        LinearLayout t = (LinearLayout) cv;
        int w = grid.getColumnWidth() > 0 ? grid.getColumnWidth() : Ui.dp(this, 120);
        if (t == null) {
            t = new LinearLayout(this);
            t.setOrientation(LinearLayout.VERTICAL);
            FrameLayout pic = new FrameLayout(this);
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackground(Ui.gradient(this, 12));
            pic.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            TextView badge = Ui.text(this, 13, Color.WHITE, true);
            badge.setPadding(Ui.dp(this, 6), Ui.dp(this, 2), Ui.dp(this, 6), Ui.dp(this, 2));
            badge.setBackground(Ui.card(this, 0xCC10B981, 0, 10));
            pic.addView(badge, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.END));
            View bar = new View(this);
            bar.setBackground(Ui.gradient(this, 2));
            pic.addView(bar, new FrameLayout.LayoutParams(0, Ui.dp(this, 4), Gravity.BOTTOM));
            t.addView(pic, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, w * 3 / 2));
            TextView n = Ui.text(this, 13, Ui.TEXT, true);
            n.setMaxLines(2);
            n.setEllipsize(android.text.TextUtils.TruncateAt.END);
            n.setPadding(Ui.dp(this, 2), Ui.dp(this, 4), Ui.dp(this, 2), 0);
            t.addView(n);
        }
        final Movie m = shown.get(p);
        FrameLayout pic = (FrameLayout) t.getChildAt(0);
        final ImageView iv = (ImageView) pic.getChildAt(0);
        TextView badge = (TextView) pic.getChildAt(1);
        View bar = pic.getChildAt(2);
        ((TextView) t.getChildAt(1)).setText(m.title + (m.year.isEmpty() ? "" : " (" + m.year + ")"));
        badge.setText("✓");
        badge.setVisibility(Store.isWatched(m.url) ? View.VISIBLE : View.GONE);
        long pos = Store.pos(m.url);
        long len = 0;
        for (Store.Watch x : Store.history()) if (x.u.equals(m.url)) len = x.len;
        FrameLayout.LayoutParams blp = (FrameLayout.LayoutParams) bar.getLayoutParams();
        blp.width = len > 0 && pos > 0 ? (int) (w * Math.min(1, (double) pos / len)) : 0;
        bar.setLayoutParams(blp);
        iv.setTag(m.url);
        Bitmap c = cache.get(m.url);
        iv.setImageBitmap(c);
        if (c == null) ex.execute(() -> {
            File f = MovieInfo.poster(this, m.url, m.name);
            if (f == null) return;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 1;
            final Bitmap bm = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (bm == null) return;
            cache.put(m.url, bm);
            main.post(() -> {
                if (m.url.equals(iv.getTag())) iv.setImageBitmap(bm);
            });
        });
        return t;
    }

    // ------------------------------------------------------------ details
    private void details(final Movie m) {
        ex.execute(() -> {
            final JSONObject info = MovieInfo.get(m.url, m.name);
            main.post(() -> {
                boolean w = Store.isWatched(m.url);
                long pos = Store.pos(m.url);
                String msg = (info.optDouble("rating", 0) > 0 ? String.format(Locale.US, "★ %.1f   ", info.optDouble("rating")) : "")
                        + TvInfo.gb(m.size) + "   ·   " + new java.text.SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(new java.util.Date(m.added))
                        + (w ? "   ·   ✓ " + T.t("Watched") : "") + "\n\n"
                        + (info.optString("overview").isEmpty() ? m.name : info.optString("overview"));
                new AlertDialog.Builder(this)
                        .setTitle("🎬 " + info.optString("title", m.title) + (info.optString("year").isEmpty() ? "" : " (" + info.optString("year") + ")"))
                        .setMessage(msg)
                        .setPositiveButton(pos > 5000 ? "▶ " + T.t("Resume") + " " + Net.fmtTime(pos) : "▶ " + T.t("Play"), (d, x) -> {
                            List<Store.Item> one = new ArrayList<>();
                            one.add(new Store.Item(info.optString("title", m.title), m.url, ""));
                            Remote.play(this, one, 0);
                        })
                        .setNeutralButton(w ? "↺ " + T.t("Mark as not watched") : "✓ " + T.t("Mark as watched"), (d, x) -> {
                            Store.setWatched(m.url, !Store.isWatched(m.url));
                            apply();
                        })
                        .setNegativeButton(T.t("Close"), null).show();
            });
        });
    }

    private void keyDialog() {
        final EditText e = new EditText(this);
        e.setText(Store.getStr("tmdb_key", ""));
        e.setHint("TMDB API key");
        new AlertDialog.Builder(this).setTitle("🔑 " + T.t("Movie posters & info"))
                .setMessage(T.t("Without a key, posters are pictures from the video. For real posters, ratings and stories add a free key from themoviedb.org → Settings → API."))
                .setView(e)
                .setPositiveButton(T.t("Save"), (d, w) -> {
                    Store.putStr("tmdb_key", e.getText().toString().trim());
                    // forget old lookups so posters are fetched again
                    for (Movie m : all) {
                        Store.putStr("mi_" + Thumbs.key(m.url), "");
                        //noinspection ResultOfMethodCallIgnored
                        new File(new File(getFilesDir(), "posters"), Thumbs.key(m.url) + ".jpg").delete();
                    }
                    cache.evictAll();
                    adapter.notifyDataSetChanged();
                })
                .setNegativeButton(T.t("Cancel"), null).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adapter != null) apply();
    }

    @Override
    protected void onDestroy() {
        ex.shutdownNow();
        super.onDestroy();
    }
}
