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

    /** Menu lists: a Lucide icon in front of each row. */
    @Override
    public AlertDialog.Builder setItems(final CharSequence[] items, final DialogInterface.OnClickListener l) {
        ArrayAdapter<CharSequence> ad = new ArrayAdapter<CharSequence>(ctx, android.R.layout.select_dialog_item, items) {
            @Override
            public View getView(int p, View cv, ViewGroup parent) {
                TextView t = (TextView) super.getView(p, cv, parent);
                UiKit.iconize(t, Ui.ACCENT);
                return t;
            }
        };
        return super.setAdapter(ad, l);
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
