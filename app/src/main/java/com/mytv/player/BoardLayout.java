package com.mytv.player;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * The remote's home board, arranged your way (Kanban-style): tile order (drag), hide / show,
 * tile size (small 4 / medium 3 / large 2 / list), your own sections, reset.
 * Entries: tile ids, and "§Name" for a section heading.
 */
public final class BoardLayout {
    private BoardLayout() {
    }

    private static final String KEY = "board_v2";
    static final String[] SIZES = {"Small (4 per row)", "Medium (3 per row)", "Large (2 per row)", "List"};
    private static final int[] COLS = {4, 3, 2, 1};

    static final class Layout {
        final List<String> order = new ArrayList<>();
        final List<String> hidden = new ArrayList<>();
        int size = 1;
    }

    static Layout load(List<String> allIds) {
        Layout l = new Layout();
        try {
            JSONObject o = new JSONObject(Store.getStr(KEY, "{}"));
            JSONArray a = o.optJSONArray("order"), h = o.optJSONArray("hidden");
            if (a != null) for (int i = 0; i < a.length(); i++) {
                String id = a.getString(i);
                if (id.startsWith("§") || allIds.contains(id)) l.order.add(id);
            }
            if (h != null) for (int i = 0; i < h.length(); i++) l.hidden.add(h.getString(i));
            l.size = Math.max(0, Math.min(3, o.optInt("size", 1)));
        } catch (Exception ignored) {
        }
        for (String id : allIds) if (!l.order.contains(id)) l.order.add(id);   // tiles added in new versions
        return l;
    }

    static void save(Layout l) {
        try {
            JSONArray a = new JSONArray(), h = new JSONArray();
            for (String s : l.order) a.put(s);
            for (String s : l.hidden) h.put(s);
            Store.putStr(KEY, new JSONObject().put("order", a).put("hidden", h).put("size", l.size).toString());
        } catch (Exception ignored) {
        }
    }

    /** The board for these tiles (id → tile view), in the saved order, with sections. */
    public static View build(Activity a, LinkedHashMap<String, View> tiles) {
        Layout l = load(new ArrayList<>(tiles.keySet()));
        int cols = COLS[l.size];
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        List<View> group = new ArrayList<>();
        for (String id : l.order) {
            if (id.startsWith("§")) {
                flush(a, box, group, cols);
                TextView h = Ui.text(a, 11, Ui.DIM, true);
                h.setText(id.substring(1).toUpperCase(Locale.getDefault()));
                h.setLetterSpacing(0.12f);
                h.setPadding(Ui.dp(a, 6), Ui.dp(a, 12), 0, Ui.dp(a, 2));
                box.addView(h);
                continue;
            }
            if (l.hidden.contains(id)) continue;
            View t = tiles.get(id);
            if (t == null) continue;
            if (t.getParent() != null) ((ViewGroup) t.getParent()).removeView(t);
            TextView sub = t.findViewWithTag("sub");
            if (sub != null) sub.setVisibility(cols == 4 ? View.GONE : View.VISIBLE);   // small tiles: title only
            group.add(t);
        }
        flush(a, box, group, cols);
        return box;
    }

    private static void flush(Activity a, LinearLayout box, List<View> group, int cols) {
        if (group.isEmpty()) return;
        box.addView(UiKit.grid(a, cols, group.toArray(new View[0])));
        group.clear();
    }

    // ------------------------------------------------------------------ the Customize screen
    /** names: id → title (for the list). onSave runs after "Save" (rebuild the board). */
    public static void customize(final Activity a, final LinkedHashMap<String, String> names, final Runnable onSave) {
        final Layout l = load(new ArrayList<>(names.keySet()));
        final Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Ui.background());
        root.setPadding(Ui.dp(a, 14), Ui.dp(a, 14), Ui.dp(a, 14), Ui.dp(a, 10));
        TextView t = Ui.text(a, 19, Ui.TEXT, true);
        t.setText(T.t("Customize the board"));
        root.addView(t);
        TextView hint = Ui.text(a, 12, Ui.DIM, false);
        hint.setText(T.t("Drag ≡ to move · 👁 show / hide · sections group your tiles"));
        hint.setPadding(0, Ui.dp(a, 4), 0, Ui.dp(a, 8));
        root.addView(hint);
        LinearLayout tools = new LinearLayout(a);
        final Button sizeBtn = btn(a, T.t("Size") + ": " + T.t(SIZES[l.size]));
        sizeBtn.setOnClickListener(v -> new UiDialog(a).setTitle(T.t("Tile size")).setSingleChoiceItems(tr(SIZES), l.size, (dd, w) -> {
            l.size = w;
            sizeBtn.setText(T.t("Size") + ": " + T.t(SIZES[w]));
            dd.dismiss();
        }).show());
        tools.addView(sizeBtn, new LinearLayout.LayoutParams(0, Ui.dp(a, 40), 1.3f));
        final LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        final Runnable[] fill = new Runnable[1];
        Button sec = btn(a, "＋ " + T.t("Section"));
        sec.setOnClickListener(v -> askName(a, "", n -> {
            l.order.add(0, "§" + n);
            fill[0].run();
        }));
        tools.addView(sec, new LinearLayout.LayoutParams(0, Ui.dp(a, 40), 1f));
        Button reset = btn(a, "↺ " + T.t("Reset"));
        reset.setOnClickListener(v -> {
            l.order.clear();
            l.order.addAll(names.keySet());
            l.hidden.clear();
            l.size = 1;
            sizeBtn.setText(T.t("Size") + ": " + T.t(SIZES[1]));
            fill[0].run();
        });
        tools.addView(reset, new LinearLayout.LayoutParams(0, Ui.dp(a, 40), 1f));
        root.addView(tools);
        final ScrollView sv = new ScrollView(a);
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        fill[0] = () -> {
            list.removeAllViews();
            for (int i = 0; i < l.order.size(); i++) list.addView(row(a, l, names, i, list, sv, fill));
        };
        fill[0].run();

        LinearLayout bottom = new LinearLayout(a);
        bottom.setPadding(0, Ui.dp(a, 8), 0, 0);
        Button cancel = btn(a, T.t("Cancel"));
        cancel.setOnClickListener(v -> d.dismiss());
        Button save = Ui.button(a, T.t("Save"), true);
        save.setOnClickListener(v -> {
            save(l);
            d.dismiss();
            if (onSave != null) onSave.run();
        });
        bottom.addView(cancel, new LinearLayout.LayoutParams(0, Ui.dp(a, 46), 1));
        bottom.addView(save, new LinearLayout.LayoutParams(0, Ui.dp(a, 46), 1));
        root.addView(bottom);
        d.setContentView(root);
        if (d.getWindow() != null) d.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        d.show();
    }

    private static View row(final Activity a, final Layout l, final LinkedHashMap<String, String> names, final int i,
                            final LinearLayout list, final ScrollView sv, final Runnable[] fill) {
        final String id = l.order.get(i);
        final boolean section = id.startsWith("§");
        LinearLayout r = new LinearLayout(a);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(a, 6), Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 4));
        r.setBackground(Ui.card(a, section ? (Ui.ACCENT & 0x00FFFFFF) | 0x33000000 : Ui.CARD, Ui.STROKE, 12));
        // drag handle
        TextView handle = Ui.text(a, 22, Ui.DIM, true);
        handle.setText("≡");
        handle.setPadding(Ui.dp(a, 6), 0, Ui.dp(a, 10), 0);
        handle.setContentDescription(T.t("Drag to move"));
        handle.setOnTouchListener(new View.OnTouchListener() {
            int at = i;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    sv.requestDisallowInterceptTouchEvent(true);
                    r.setAlpha(0.6f);
                    at = l.order.indexOf(id);
                    return true;
                }
                if (e.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    float y = e.getRawY();
                    int[] loc = new int[2];
                    for (int k = 0; k < list.getChildCount(); k++) {
                        View c = list.getChildAt(k);
                        c.getLocationOnScreen(loc);
                        if (y >= loc[1] && y < loc[1] + c.getHeight() && k != at) {
                            l.order.add(k, l.order.remove(at));         // swap places as the finger passes
                            View moved = list.getChildAt(at);
                            list.removeViewAt(at);
                            list.addView(moved, k);
                            at = k;
                            break;
                        }
                    }
                    return true;
                }
                if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    sv.requestDisallowInterceptTouchEvent(false);
                    fill[0].run();                                   // refresh positions / buttons
                    return true;
                }
                return false;
            }
        });
        r.addView(handle);
        TextView name = Ui.text(a, 15, Ui.TEXT, section);
        String title = section ? "— " + id.substring(1) + " —" : names.get(id);
        name.setText(title);
        boolean hidden = l.hidden.contains(id);
        if (hidden) name.setAlpha(0.4f);
        r.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        r.addView(small(a, "↑", v -> {
            int k = l.order.indexOf(id);
            if (k > 0) {
                l.order.add(k - 1, l.order.remove(k));
                fill[0].run();
            }
        }));
        r.addView(small(a, "↓", v -> {
            int k = l.order.indexOf(id);
            if (k < l.order.size() - 1) {
                l.order.add(k + 1, l.order.remove(k));
                fill[0].run();
            }
        }));
        if (section) {
            r.addView(small(a, "✎", v -> askName(a, id.substring(1), n -> {
                l.order.set(l.order.indexOf(id), "§" + n);
                fill[0].run();
            })));
            r.addView(small(a, "✕", v -> {
                l.order.remove(id);
                fill[0].run();
            }));
        } else {
            r.addView(small(a, hidden ? "🚫" : "👁", v -> {
                if (l.hidden.contains(id)) l.hidden.remove(id);
                else l.hidden.add(id);
                fill[0].run();
            }));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, Ui.dp(a, 3), 0, Ui.dp(a, 3));
        r.setLayoutParams(lp);
        return r;
    }

    private interface Named {
        void on(String n);
    }

    private static void askName(Activity a, String cur, final Named cb) {
        final EditText e = new EditText(a);
        e.setText(cur);
        e.setHint(T.t("Section name (e.g. Music, TV, Tools)"));
        e.setSingleLine(true);
        new UiDialog(a).setTitle(T.t("Section")).setView(e).setPositiveButton(T.t("Save"), (d, w) -> {
            String n = e.getText().toString().trim();
            if (!n.isEmpty()) cb.on(n);
        }).setNegativeButton(T.t("Cancel"), null).show();
    }

    private static Button btn(Activity a, String label) {
        Button b = Ui.button(a, label, false);
        b.setTextSize(12);
        b.setAllCaps(false);
        return b;
    }

    private static TextView small(Activity a, String s, View.OnClickListener l) {
        TextView t = Ui.text(a, 17, Ui.TEXT, true);
        t.setText(s);
        t.setGravity(Gravity.CENTER);
        t.setOnClickListener(l);
        t.setBackground(Ui.focusCard(a, 10));
        t.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(a, 40), Ui.dp(a, 40)));
        return t;
    }

    private static String[] tr(String[] s) {
        String[] o = new String[s.length];
        for (int i = 0; i < s.length; i++) o[i] = T.t(s[i]);
        return o;
    }
}
