package com.mytv.player;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.text.InputType;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.appcompat.app.AlertDialog;

/** The ⚙ settings menu on the home screen (works with the TV remote and touch). */
public final class SettingsDialog {
    private SettingsDialog() {
    }

    private interface IntCb {
        void on(int i);
    }

    private static void choose(Activity a, String title, String[] items, int sel, final IntCb cb) {
        new AlertDialog.Builder(a).setTitle(title).setSingleChoiceItems(items, sel, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int w) {
                d.dismiss();
                cb.on(w);
            }
        }).show();
    }

    private static int idx(String[] vals, String v) {
        for (int i = 0; i < vals.length; i++) if (vals[i].equals(v)) return i;
        return 0;
    }

    private static int idx(int[] vals, int v) {
        for (int i = 0; i < vals.length; i++) if (vals[i] == v) return i;
        return -1;
    }

    public static void show(final Activity a) {
        final String[] themes = {"dark", "light", "system"};
        final String[] themeNames = {"Dark", "Light", "Follow system"};
        String[] items = {
                "🎨  Theme: " + themeNames[idx(themes, Store.getStr("theme", "dark"))],
                "📺  Device name: " + Discovery.deviceName(a),
                "🖼  Picture fit: " + PlayerActivity.FIT_NAMES[Math.max(0, Math.min(3, Store.getInt("fit", 0)))],
                "📐  Aspect ratio: " + PlayerActivity.ASPECT_NAMES[idx(PlayerActivity.ASPECT_VALUES, Store.getStr("aspect", ""))],
                "🎚  Equalizer…",
                "🔊  Default volume: " + Store.getInt("vol", 100) + "%",
                "💬  Subtitle size: " + PlayerActivity.SUB_SIZE_NAMES[Math.max(0, idx(PlayerActivity.SUB_SIZES, Store.getInt("subsize", 16)))],
                "💬  Subtitle background: " + (Store.getBool("subbg", false) ? "On" : "Off"),
                "🔤  Subtitle encoding: " + PlayerActivity.ENC_NAMES[idx(PlayerActivity.ENC_VALUES, Store.getStr("subenc", ""))],
                "⚙  Decoder: " + (Store.getInt("hw", 0) == 1 ? "Software" : "Hardware (auto)"),
                "🌐  Network buffer: " + Store.getInt("cache", 2000) / 1000f + " s",
                "🔌  Start phone remote when device boots: " + (Store.getBool("boot", Ui.isTv(a)) ? "On" : "Off"),
                "🧹  Clear “Recently played”",
                "🔓  Allow phone to start videos (display over apps): " + (Perms.overlayOk(a) ? "Allowed" : "Not allowed"),
                "🔓  Allow access to all files (USB, subtitles): " + (Perms.filesOk() ? "Allowed" : "Not allowed"),
                "📱  Use as remote for another TV…",
                "ℹ  Phone remote address: http://" + Net.ip() + ":" + RemoteService.PORT};
        new AlertDialog.Builder(a).setTitle("⚙  Settings").setItems(items, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int w) {
                switch (w) {
                    case 0:
                        choose(a, "Theme", themeNames, idx(themes, Store.getStr("theme", "dark")), new IntCb() {
                            public void on(int i) {
                                Store.putStr("theme", themes[i]);
                                a.recreate();
                            }
                        });
                        break;
                    case 1: {
                        final EditText e = new EditText(a);
                        e.setText(Discovery.deviceName(a));
                        e.setInputType(InputType.TYPE_CLASS_TEXT);
                        new AlertDialog.Builder(a).setTitle("Device name (shown in the phone app)").setView(e)
                                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d2, int w2) {
                                        Store.putStr("devname", e.getText().toString().trim());
                                        Discovery.unregister(a);
                                        Discovery.register(a);
                                        show(a);
                                    }
                                }).setNegativeButton("Cancel", null).show();
                        break;
                    }
                    case 2:
                        choose(a, "Picture fit", PlayerActivity.FIT_NAMES, Store.getInt("fit", 0), new IntCb() {
                            public void on(int i) {
                                PlayerActivity.applySetting("fit", String.valueOf(i), "");
                                show(a);
                            }
                        });
                        break;
                    case 3:
                        choose(a, "Aspect ratio", PlayerActivity.ASPECT_NAMES,
                                idx(PlayerActivity.ASPECT_VALUES, Store.getStr("aspect", "")), new IntCb() {
                                    public void on(int i) {
                                        PlayerActivity.applySetting("aspect", PlayerActivity.ASPECT_VALUES[i], "");
                                        show(a);
                                    }
                                });
                        break;
                    case 4:
                        eq(a);
                        break;
                    case 5: {
                        String[] v = new String[PlayerActivity.VOLS.length];
                        for (int i = 0; i < v.length; i++) v[i] = PlayerActivity.VOLS[i] + "%" + (PlayerActivity.VOLS[i] > 100 ? " (boost)" : "");
                        choose(a, "Default volume", v, idx(PlayerActivity.VOLS, Store.getInt("vol", 100)), new IntCb() {
                            public void on(int i) {
                                PlayerActivity.applySetting("vol", String.valueOf(PlayerActivity.VOLS[i]), "");
                                show(a);
                            }
                        });
                        break;
                    }
                    case 6:
                        choose(a, "Subtitle size", PlayerActivity.SUB_SIZE_NAMES,
                                idx(PlayerActivity.SUB_SIZES, Store.getInt("subsize", 16)), new IntCb() {
                                    public void on(int i) {
                                        PlayerActivity.applySetting("subsize", String.valueOf(PlayerActivity.SUB_SIZES[i]), "");
                                        show(a);
                                    }
                                });
                        break;
                    case 7:
                        PlayerActivity.applySetting("subbg", Store.getBool("subbg", false) ? "0" : "1", "");
                        show(a);
                        break;
                    case 8:
                        choose(a, "Subtitle encoding", PlayerActivity.ENC_NAMES,
                                idx(PlayerActivity.ENC_VALUES, Store.getStr("subenc", "")), new IntCb() {
                                    public void on(int i) {
                                        PlayerActivity.applySetting("subenc", PlayerActivity.ENC_VALUES[i], "");
                                        show(a);
                                    }
                                });
                        break;
                    case 9:
                        PlayerActivity.applySetting("hw", Store.getInt("hw", 0) == 1 ? "0" : "1", "");
                        show(a);
                        break;
                    case 10: {
                        String[] v = new String[PlayerActivity.CACHES.length];
                        for (int i = 0; i < v.length; i++) v[i] = PlayerActivity.CACHES[i] / 1000f + " seconds";
                        choose(a, "Network buffer", v, idx(PlayerActivity.CACHES, Store.getInt("cache", 2000)), new IntCb() {
                            public void on(int i) {
                                PlayerActivity.applySetting("cache", String.valueOf(PlayerActivity.CACHES[i]), "");
                                show(a);
                            }
                        });
                        break;
                    }
                    case 11:
                        Store.putBool("boot", !Store.getBool("boot", Ui.isTv(a)));
                        show(a);
                        break;
                    case 12:
                        Store.delPlaylist(Store.RECENT);
                        break;
                    case 13:
                        Perms.openOverlaySettings(a);
                        break;
                    case 14:
                        Perms.openFilesSettings(a);
                        break;
                    case 15:
                        a.startActivity(new Intent(a, ConnectActivity.class));
                        break;
                    default:
                        break;
                }
            }
        }).show();
    }

    /** Equalizer without a video playing: vertical bands, on/off, reset. */
    public static void eq(Activity a) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(a, 20);
        box.setPadding(p, p / 2, p, 0);
        final CheckBox on = new CheckBox(a);
        on.setText("Equalizer on");
        on.setChecked(Store.getBool("eq_on", false));
        on.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean c) {
                PlayerActivity.applySetting("eq_on", c ? "1" : "0", "");
            }
        });
        box.addView(on);
        final EqView eq = new EqView(a);
        eq.setBands(PlayerActivity.eqLabels(), PlayerActivity.eqValues());
        eq.setListener(new EqView.Listener() {
            public void onBand(int i, float db) {
                if (i == 0) PlayerActivity.applySetting("eq_preamp", String.valueOf(db), "");
                else PlayerActivity.applySetting("eq_band", String.valueOf(db), String.valueOf(i - 1));
                on.setChecked(true);
            }
        });
        box.addView(eq);
        new AlertDialog.Builder(a).setTitle("🎚  Equalizer  (◀ ▶ band · ▲ ▼ level)").setView(box)
                .setPositiveButton("Done", null)
                .setNeutralButton("Reset", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        PlayerActivity.applySetting("eq_reset", "", "");
                    }
                }).show();
    }
}
