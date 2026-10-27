package com.mytv.player;

import android.app.Activity;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Menus of the phone music player (used by the library and the now-playing screen). */
public final class MusicDialogs {
    private MusicDialogs() {
    }

    public interface Details {
        void show(PhoneMedia.M m);
    }

    /** Hold a song: everything you can do with it. `playlist` = the playlist it is shown in (or null). */
    public static void songActions(final Activity a, final PhoneMedia.M m, final String playlist, final int posInPlaylist,
                                   final Runnable refresh, final Details details) {
        final List<String> labels = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();
        final List<PhoneMedia.M> one = new ArrayList<>();
        one.add(m);
        labels.add("⏭  " + T.t("Play next"));
        acts.add(() -> {
            MusicService.playNext(a, one);
            toast(a, T.t("Plays next") + ": " + m.title);
        });
        labels.add("＋  " + T.t("Add to queue"));
        acts.add(() -> {
            MusicService.addToQueue(a, one);
            toast(a, T.t("Added to queue"));
        });
        labels.add("🎵  " + T.t("Add to playlist…"));
        acts.add(() -> addToPlaylist(a, uris(one), refresh));
        final boolean fav = MusicLib.isFav(m.uri.toString());
        labels.add((fav ? "☆  " + T.t("Remove from favourites") : "★  " + T.t("Add to favourites")));
        acts.add(() -> {
            MusicLib.setFav(m.uri.toString(), !fav);
            if (refresh != null) refresh.run();
        });
        if (playlist != null && !MusicLib.RECENT.equals(playlist) && !MusicLib.MOST.equals(playlist)) {
            labels.add("🗑  " + T.t("Remove from this playlist"));
            acts.add(() -> {
                MusicLib.remove(playlist, m.uri.toString());
                if (refresh != null) refresh.run();
            });
            if (!MusicLib.FAV.equals(playlist)) {
                labels.add("⬆  " + T.t("Move up"));
                acts.add(() -> {
                    MusicLib.move(playlist, posInPlaylist, posInPlaylist - 1);
                    if (refresh != null) refresh.run();
                });
                labels.add("⬇  " + T.t("Move down"));
                acts.add(() -> {
                    MusicLib.move(playlist, posInPlaylist, posInPlaylist + 1);
                    if (refresh != null) refresh.run();
                });
            }
        }
        labels.add("✏  " + T.t("Edit song info"));
        acts.add(() -> editInfo(a, m, refresh));
        labels.add("ℹ  " + T.t("Details"));
        acts.add(() -> {
            if (details != null) details.show(m);
        });
        labels.add("📺  " + T.t("Play on the TV"));
        acts.add(() -> new Thread(() -> {
            List<Store.Item> it = new ArrayList<>();
            it.add(m.item());
            final String r = PhoneMedia.sendToTv(a, it, 0, 0);
            a.runOnUiThread(() -> toast(a, r));
        }).start());
        new UiDialog(a).setTitle(m.title).setItems(labels.toArray(new String[0]), (d, w) -> acts.get(w).run()).show();
    }

    static List<String> uris(List<PhoneMedia.M> l) {
        List<String> u = new ArrayList<>();
        for (PhoneMedia.M m : l) u.add(m.uri.toString());
        return u;
    }

    private static void toast(Activity a, String s) {
        UiKit.makeToast(a, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ playlists
    public static void addToPlaylist(final Activity a, final List<String> songs, final Runnable refresh) {
        final List<String> names = MusicLib.playlistNames();
        List<String> rows = new ArrayList<>();
        rows.add("＋  " + T.t("New playlist…"));
        rows.add("★  " + MusicLib.FAV);
        for (String n : names) rows.add("🎵  " + n);
        new UiDialog(a).setTitle(T.t("Add to playlist…")).setItems(rows.toArray(new String[0]), (d, w) -> {
            if (w == 0) newPlaylist(a, name -> {
                int n = MusicLib.add(name, songs);
                toast(a, n + " " + T.t("added to") + " " + name);
                if (refresh != null) refresh.run();
            });
            else if (w == 1) {
                for (String u : songs) MusicLib.setFav(u, true);
                toast(a, T.t("Added to favourites"));
                if (refresh != null) refresh.run();
            } else {
                String name = names.get(w - 2);
                int n = MusicLib.add(name, songs);
                toast(a, n + " " + T.t("added to") + " " + name);
                if (refresh != null) refresh.run();
            }
        }).show();
    }

    public interface Named {
        void on(String name);
    }

    public static void newPlaylist(final Activity a, final Named done) {
        final EditText e = new EditText(a);
        e.setHint(T.t("Playlist name"));
        e.setSingleLine(true);
        new UiDialog(a).setTitle(T.t("New playlist")).setView(pad(a, e))
                .setPositiveButton(T.t("Create"), (d, w) -> {
                    String n = e.getText().toString().trim();
                    if (MusicLib.create(n)) done.on(n);
                    else toast(a, T.t("That name is empty or already used"));
                }).setNegativeButton(T.t("Cancel"), null).show();
    }

    /** "Import M3U link": downloads the playlist, makes a new playlist of it and reports its name. */
    public static void importM3u(final Activity a, final Runnable pickFile, final Named done) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(a, 22), Ui.dp(a, 6), Ui.dp(a, 22), 0);
        final EditText link = field(a, box, T.t("M3U / M3U8 link"), "");
        link.setSingleLine(true);
        link.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        final EditText nm = field(a, box, T.t("Playlist name (optional)"), "");
        try {   // a copied link is filled in for you
            android.content.ClipboardManager cm = (android.content.ClipboardManager) a.getSystemService(Activity.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence c = cm.getPrimaryClip().getItemAt(0).getText();
                if (c != null && c.toString().trim().toLowerCase().startsWith("http")) link.setText(c.toString().trim());
            }
        } catch (Exception ignored) {
        }
        new UiDialog(a).setTitle(T.t("Import M3U link")).setView(box)
                .setPositiveButton(T.t("Import"), (d, w) -> {
                    String u = link.getText().toString().trim();
                    if (!u.toLowerCase().startsWith("http://") && !u.toLowerCase().startsWith("https://")) {
                        toast(a, T.t("Paste a web link that starts with http:// or https://"));
                        return;
                    }
                    runImport(a, u, nm.getText().toString().trim(), done);
                })
                .setNeutralButton(T.t("Choose file…"), (d, w) -> pickFile.run())
                .setNegativeButton(T.t("Cancel"), null).show();
    }

    private static void runImport(final Activity a, final String url, final String wanted, final Named done) {
        toast(a, T.t("Loading the playlist…"));
        new Thread(() -> {
            try {
                String link = M3u.rawLink(url);
                String text = Net.fetchText(link);
                if (text.trim().toLowerCase().startsWith("<!doctype html") || text.trim().toLowerCase().startsWith("<html"))
                    throw new Exception(T.t("This link is a web page, not a playlist file"));
                finish(a, M3u.parse(text, link), wanted, nameFromUrl(link), done);
            } catch (Throwable e) {
                failed(a, e);
            }
        }).start();
    }

    /** Import from a .m3u / .m3u8 / .pls file chosen on the phone (`fileName` is used for the playlist name). */
    public static void importM3uFile(final Activity a, final android.net.Uri file, final String fileName, final Named done) {
        toast(a, T.t("Loading the playlist…"));
        new Thread(() -> {
            try {
                String text = new String(AudioTools.readAll(a, file), "UTF-8");
                String base = fileName == null ? "" : fileName.replaceAll("(?i)\\.(m3u8?|pls)$", "").replace('_', ' ').trim();
                finish(a, M3u.parse(text, null), "", base.isEmpty() ? T.t("M3U playlist") : base, done);
            } catch (Throwable e) {
                failed(a, e);
            }
        }).start();
    }

    private static void failed(Activity a, Throwable e) {
        final String msg = "⚠ " + (e.getMessage() == null ? String.valueOf(e) : e.getMessage());
        a.runOnUiThread(() -> toast(a, msg));
    }

    private static void finish(final Activity a, M3u.Result r, String wanted, String fallbackName, final Named done) throws Exception {
        if (r.items.isEmpty()) throw new Exception(T.t("No songs found in this link"));
        String base = !wanted.isEmpty() ? wanted : r.name != null && !r.name.isEmpty() ? r.name : fallbackName;
        String name = base;
        for (int n = 2; !MusicLib.create(name); n++) {
            if (n > 99) throw new Exception(T.t("That name is empty or already used"));
            name = base + " (" + n + ")";
        }
        MusicLib.saveRemote(r.items);
        List<String> uris = new ArrayList<>();
        for (M3u.Entry e : r.items) uris.add(e.url);
        MusicLib.add(name, uris);
        final String made = name;
        final String msg = T.t("Playlist created") + ": " + name + " (" + r.items.size() + ")"
                + (r.skipped > 0 ? " · " + r.skipped + " " + T.t("skipped (not web links)") : "");
        a.runOnUiThread(() -> {
            toast(a, msg);
            done.on(made);
        });
    }

    private static String nameFromUrl(String url) {
        try {
            Uri u = Uri.parse(url);
            String seg = u.getLastPathSegment();
            if (seg != null) {
                seg = seg.replaceAll("(?i)\\.(m3u8?|pls)$", "").replace('_', ' ').replace('-', ' ').trim();
                if (!seg.isEmpty() && !seg.equalsIgnoreCase("playlist") && !seg.equalsIgnoreCase("index")) return seg;
            }
            if (u.getHost() != null) return u.getHost();
        } catch (Exception ignored) {
        }
        return T.t("M3U playlist");
    }

    private static View pad(Activity a, View v) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(Ui.dp(a, 22), Ui.dp(a, 6), Ui.dp(a, 22), 0);
        l.addView(v);
        return l;
    }

    // ------------------------------------------------------------------ edit song info
    public static void editInfo(final Activity a, final PhoneMedia.M m, final Runnable refresh) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(a, 22), Ui.dp(a, 6), Ui.dp(a, 22), 0);
        final EditText t = field(a, box, T.t("Title"), m.title);
        final EditText ar = field(a, box, T.t("Artist"), m.artist);
        final EditText al = field(a, box, T.t("Album"), m.albumName);
        final EditText g = field(a, box, T.t("Genre"), m.genre);
        TextView note = Ui.text(a, 12, Ui.DIM, false);
        note.setText(T.t("Saved in ZH Play (the music file itself is not changed)."));
        note.setPadding(0, Ui.dp(a, 8), 0, 0);
        box.addView(note);
        new UiDialog(a).setTitle(T.t("Edit song info")).setView(box)
                .setPositiveButton(T.t("Save"), (d, w) -> {
                    MusicLib.saveEdits(m.uri.toString(), t.getText().toString(), ar.getText().toString(), al.getText().toString(),
                            g.getText().toString());
                    MusicLib.applyEdits(m);
                    MusicService.refresh();
                    if (refresh != null) refresh.run();
                })
                .setNeutralButton(T.t("Reset"), (d, w) -> {
                    MusicLib.clearEdits(m.uri.toString());
                    toast(a, T.t("Original info is shown after the list reloads"));
                    if (refresh != null) refresh.run();
                })
                .setNegativeButton(T.t("Cancel"), null).show();
    }

    private static EditText field(Activity a, LinearLayout box, String hint, String value) {
        TextView l = Ui.text(a, 12, Ui.DIM, false);
        l.setText(hint);
        l.setPadding(0, Ui.dp(a, 8), 0, 0);
        box.addView(l);
        EditText e = new EditText(a);
        e.setSingleLine(true);
        e.setText(value);
        box.addView(e);
        return e;
    }

    // ------------------------------------------------------------------ speed / sleep timer
    public static final float[] SPEEDS = {0.5f, 0.75f, 0.9f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f};

    public static void speed(final Activity a, final Runnable done) {
        String[] n = new String[SPEEDS.length];
        int cur = 3;
        for (int i = 0; i < n.length; i++) {
            n[i] = SPEEDS[i] + "×" + (SPEEDS[i] == 1f ? "  (" + T.t("normal") + ")" : SPEEDS[i] < 1 ? "  " + T.t("slower") : "");
            if (Math.abs(SPEEDS[i] - MusicService.speed) < 0.01f) cur = i;
        }
        new UiDialog(a).setTitle(T.t("Playback speed")).setSingleChoiceItems(n, cur, (d, w) -> {
            MusicService.setSpeed(SPEEDS[w]);
            Store.putFloat("m_speed", SPEEDS[w]);
            d.dismiss();
            if (done != null) done.run();
        }).show();
    }

    public static void sleep(final Activity a, final Runnable done) {
        final int[] v = {0, 5, 10, 15, 30, 45, 60, 90, -1};
        String[] n = new String[v.length];
        for (int i = 0; i < v.length; i++) n[i] = v[i] == 0 ? T.t("Off") : v[i] < 0 ? T.t("At the end of this song") : v[i] + " " + T.t("minutes");
        new UiDialog(a).setTitle(T.t("Sleep timer")).setItems(n, (d, w) -> {
            MusicService.sleep(v[w]);
            toast(a, v[w] == 0 ? T.t("Sleep timer off") : T.t("Music stops") + " " + (v[w] < 0 ? T.t("at the end of this song") : v[w] + " min"));
            if (done != null) done.run();
        }).show();
    }

    public static String sleepLabel() {
        if (MusicService.sleepAfterSong) return T.t("end of song");
        if (MusicService.sleepAt <= 0) return "";
        long left = Math.max(0, MusicService.sleepAt - System.currentTimeMillis());
        return Net.fmtTime(left);
    }

    // ------------------------------------------------------------------ equalizer
    /** Opens the Poweramp-style equalizer. */
    public static void equalizer(final Activity a) {
        a.startActivity(new android.content.Intent(a, PowerEqActivity.class));
    }

    interface Fmt {
        String f(int v);
    }

    interface Val {
        void on(int v);
    }

    private static SeekBar slider(Activity a, LinearLayout box, String name, final int min, int max, int value, final Fmt fmt, final Val cb) {
        LinearLayout head = new LinearLayout(a);
        head.setPadding(0, Ui.dp(a, 12), 0, 0);
        TextView l = Ui.text(a, 13, Ui.TEXT, true);
        l.setText(name);
        head.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final TextView val = Ui.text(a, 13, Ui.ACCENT, true);
        val.setText(fmt.f(value));
        head.addView(val);
        box.addView(head);
        SeekBar sb = new SeekBar(a);
        sb.setMax(max - min);
        sb.setProgress(value - min);
        sb.setContentDescription(name);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                val.setText(fmt.f(p + min));
                if (user) cb.on(p + min);
            }

            public void onStartTrackingTouch(SeekBar s) {
            }

            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        box.addView(sb);
        return sb;
    }
}
