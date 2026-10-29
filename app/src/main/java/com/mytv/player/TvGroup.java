package com.mytv.player;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Several TVs at once: a group of paired TVs. While the group is on, every action sent to the main TV
 * (play, keys, volume, present, search …) is also sent to the other TVs of the group — one phone, many TVs.
 * Questions (state, options …) go to the main TV only. Screen cast streams to every TV of the group.
 */
public final class TvGroup {
    private TvGroup() {
    }

    /** Actions copied to the whole group (not questions like /state). */
    private static final Set<String> ACTIONS = new HashSet<>(Arrays.asList(
            "/cmd", "/key", "/play", "/playmany", "/present", "/presentcmd", "/search", "/launch", "/power", "/browse", "/type", "/tvstop"));
    private static final ExecutorService pool = Executors.newFixedThreadPool(4);

    // ------------------------------------------------------------------ TVs this phone has paired with
    static void remember(String ip) {
        if (ip == null || ip.isEmpty()) return;
        List<String> k = known();
        if (!k.contains(ip)) {
            k.add(ip);
            Store.putStr("tv_known", new JSONArray(k).toString());
        }
    }

    static List<String> known() {
        List<String> l = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Store.getStr("tv_known", "[]"));
            for (int i = 0; i < a.length(); i++) l.add(a.getString(i));
        } catch (Exception ignored) {
        }
        String last = Store.getStr("last_tv", "");
        if (!last.isEmpty() && !l.contains(last)) l.add(0, last);
        return l;
    }

    static List<String> members() {
        List<String> l = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Store.getStr("tv_group", "[]"));
            for (int i = 0; i < a.length(); i++) l.add(a.getString(i));
        } catch (Exception ignored) {
        }
        return l;
    }

    public static boolean on() {
        return Store.getBool("tv_group_on", false) && members().size() > 1;
    }

    /** Other TVs that should also receive what goes to `ip`. */
    static List<String> others(String ip) {
        List<String> out = new ArrayList<>();
        if (!on()) return out;
        List<String> m = members();
        if (!m.contains(ip)) return out;
        for (String x : m) if (!x.equals(ip)) out.add(x);
        return out;
    }

    /** Called by TvClient.post: copies an action to the other TVs (in the background, results ignored). */
    static void fanOut(String ip, String path, final Map<String, String> params) {
        if (!ACTIONS.contains(path)) return;
        for (final String other : others(ip)) {
            final java.util.HashMap<String, String> p = new java.util.HashMap<>(params);
            pool.execute(() -> {
                try {
                    TvClient.postDirect(other, path, p);
                } catch (Exception ignored) {
                }
            });
        }
    }

    public static String label() {
        return on() ? members().size() + " " + T.t("TVs together") : T.t("Off");
    }

    // ------------------------------------------------------------------ choosing the group
    public static void manage(final Activity a, final Runnable changed) {
        final List<String> tvs = known();
        if (tvs.isEmpty()) {
            new UiDialog(a).setTitle(T.t("TV group"))
                    .setMessage(T.t("Pair each TV once (Scan → choose the TV → PIN). Paired TVs appear here to be grouped."))
                    .setPositiveButton(T.t("OK"), null).show();
            return;
        }
        final String[] names = new String[tvs.size()];
        for (int i = 0; i < names.length; i++) names[i] = tvs.get(i) + (TvClient.token(tvs.get(i)).isEmpty() ? "  · " + T.t("not paired") : "");
        final boolean[] chosen = new boolean[tvs.size()];
        List<String> m = members();
        for (int i = 0; i < chosen.length; i++) chosen[i] = m.contains(tvs.get(i)) || (m.isEmpty() && i == 0);
        final android.app.Dialog[] dlg = new android.app.Dialog[1];
        dlg[0] = new UiDialog(a).setTitle(T.t("TV group") + " · " + label())
                .setMultiChoiceItems(names, chosen, (d, w, c) -> chosen[w] = c)
                .setPositiveButton(T.t("Control together"), (d, w) -> {
                    JSONArray g = new JSONArray();
                    for (int i = 0; i < chosen.length; i++) if (chosen[i]) g.put(tvs.get(i));
                    Store.putStr("tv_group", g.toString());
                    Store.putBool("tv_group_on", g.length() > 1);
                    // the main TV must be in the group
                    if (g.length() > 1 && !members().contains(Store.getStr("last_tv", ""))) Store.putStr("last_tv", g.optString(0));
                    UiKit.makeToast(a, g.length() > 1 ? "📺 " + g.length() + " " + T.t("TVs together") : T.t("Choose at least 2 TVs"),
                            Toast.LENGTH_LONG).show();
                    if (changed != null) changed.run();
                })
                .setNeutralButton(T.t("Add by address"), (d, w) -> {
                    final EditText e = new EditText(a);
                    e.setHint("192.168.1.20");
                    e.setInputType(InputType.TYPE_CLASS_TEXT);
                    new UiDialog(a).setTitle(T.t("TV address")).setView(e).setPositiveButton(T.t("Add"), (d2, w2) -> {
                        String ip = e.getText().toString().trim();
                        if (!ip.isEmpty()) remember(ip);
                        manage(a, changed);
                    }).show();
                })
                .setNegativeButton(T.t("Group off"), (d, w) -> {
                    Store.putBool("tv_group_on", false);
                    UiKit.makeToast(a, T.t("Only the main TV is controlled"), Toast.LENGTH_SHORT).show();
                    if (changed != null) changed.run();
                }).show();
        // fill in the TV names / online state
        final Handler h = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            for (int i = 0; i < tvs.size(); i++) {
                Discovery.Device dev = Discovery.ping(tvs.get(i), 1200);
                final int k = i;
                final String n = (dev == null ? "⚫ " + tvs.get(i) + "  · " + T.t("offline") : "🟢 " + dev.name + "  · " + tvs.get(i))
                        + (TvClient.token(tvs.get(i)).isEmpty() ? "  · " + T.t("not paired") : "");
                h.post(() -> {
                    if (dlg[0] instanceof androidx.appcompat.app.AlertDialog && dlg[0].isShowing()) {
                        android.widget.ListView lv = ((androidx.appcompat.app.AlertDialog) dlg[0]).getListView();
                        if (lv != null && lv.getChildAt(k) instanceof android.widget.TextView) {
                            ((android.widget.TextView) lv.getChildAt(k)).setText(UiKit.noEmoji(n));
                        }
                    }
                });
            }
        }).start();
    }
}
