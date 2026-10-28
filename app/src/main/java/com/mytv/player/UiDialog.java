package com.mytv.player;

import android.content.Context;
import android.content.DialogInterface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

/** Every dialog / menu of the app: emojis become Lucide icons, no emoji text anywhere. */
public class UiDialog extends AlertDialog.Builder {
    private final Context ctx;

    public UiDialog(Context c) {
        super(c);
        ctx = c;
    }

    private static CharSequence[] clean(CharSequence[] items) {
        if (items == null) return null;
        CharSequence[] out = new CharSequence[items.length];
        for (int i = 0; i < items.length; i++) out[i] = UiKit.noEmoji(items[i]);
        return out;
    }

    @Override
    public AlertDialog.Builder setTitle(CharSequence t) {
        String ic = t == null ? null : UiKit.iconFor(t.toString());
        if (ic != null) setIcon(new UiKit.Icon(ctx, ic, 24, Ui.ACCENT));
        return super.setTitle(t == null ? null : UiKit.noEmoji(t));
    }

    @Override
    public AlertDialog.Builder setMessage(CharSequence m) {
        return super.setMessage(m == null ? null : UiKit.noEmoji(m));
    }

    /**
     * Menu lists in the settings style (Brave-like): each line a rounded card — icon, title, grey value underneath,
     * a switch for On / Off values, red for delete / reset / clear. Lines starting with "§" are headings
     * (not counted: the listener still gets the same numbers as before).
     */
    @Override
    public AlertDialog.Builder setItems(final CharSequence[] items, final DialogInterface.OnClickListener l) {
        final java.util.List<Object[]> rows = new java.util.ArrayList<>();
        final java.util.List<Integer> realIndex = new java.util.ArrayList<>();
        int n = 0;
        for (CharSequence it : items) {
            Object[] r = UiRows.parse(ctx, it);
            rows.add(r);
            realIndex.add((Integer) r[3] == -2 ? -1 : n++);
        }
        android.widget.BaseAdapter ad = new android.widget.BaseAdapter() {
            public int getCount() {
                return rows.size();
            }

            public Object getItem(int p) {
                return rows.get(p);
            }

            public long getItemId(int p) {
                return p;
            }

            @Override
            public boolean isEnabled(int p) {
                return realIndex.get(p) >= 0;
            }

            @Override
            public boolean areAllItemsEnabled() {
                return false;
            }

            public View getView(int p, View cv, ViewGroup parent) {
                Object[] r = rows.get(p);
                android.widget.FrameLayout wrap = new android.widget.FrameLayout(ctx);
                wrap.setPadding(Ui.dp(ctx, 14), 0, Ui.dp(ctx, 14), 0);
                if ((Integer) r[3] == -2) {
                    wrap.addView(UiRows.header(ctx, (String) r[1]));
                    return wrap;
                }
                View row = UiRows.row(ctx, (String) r[0], (String) r[1], (String) r[2], (Integer) r[3], (Boolean) r[4], null);
                row.setClickable(false);          // the list handles the tap
                row.setFocusable(false);
                wrap.addView(row);
                return wrap;
            }
        };
        return super.setAdapter(ad, (d, w) -> {
            int real = w >= 0 && w < realIndex.size() ? realIndex.get(w) : -1;
            if (real >= 0 && l != null) l.onClick(d, real);
        });
    }

    /** No grey lines between the card rows. */
    @Override
    public AlertDialog create() {
        final AlertDialog d = super.create();
        d.setOnShowListener(x -> {
            android.widget.ListView lv = d.getListView();
            if (lv != null) {
                lv.setDivider(null);
                lv.setDividerHeight(0);
                lv.setSelector(new android.graphics.drawable.ColorDrawable(0));
                lv.setPadding(0, Ui.dp(ctx, 4), 0, Ui.dp(ctx, 8));
                lv.setClipToPadding(false);
            }
        });
        return d;
    }

    @Override
    public AlertDialog.Builder setSingleChoiceItems(CharSequence[] items, int checked, DialogInterface.OnClickListener l) {
        return super.setSingleChoiceItems(clean(items), checked, l);
    }

    @Override
    public AlertDialog.Builder setMultiChoiceItems(CharSequence[] items, boolean[] checked, DialogInterface.OnMultiChoiceClickListener l) {
        return super.setMultiChoiceItems(clean(items), checked, l);
    }

    @Override
    public AlertDialog.Builder setPositiveButton(CharSequence t, DialogInterface.OnClickListener l) {
        return super.setPositiveButton(UiKit.noEmoji(t), l);
    }

    @Override
    public AlertDialog.Builder setNegativeButton(CharSequence t, DialogInterface.OnClickListener l) {
        return super.setNegativeButton(UiKit.noEmoji(t), l);
    }

    @Override
    public AlertDialog.Builder setNeutralButton(CharSequence t, DialogInterface.OnClickListener l) {
        return super.setNeutralButton(UiKit.noEmoji(t), l);
    }
}
