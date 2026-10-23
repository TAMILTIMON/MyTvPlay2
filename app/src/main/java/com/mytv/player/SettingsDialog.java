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
        final String[] themeNames = {T.t("Dark"), T.t("Light"), T.t("Follow system")};
        String[] items = {
                "🌐  Language / மொழி / भाषा: " + T.langName(),
                "🔐  " + T.t("Remote PIN: ") + (Pair.enabled() ? Pair.pin() : T.t("Off")),
                "🎨  " + T.t("Colours & style") + ": " + Palette.NAMES[Palette.index()] + (Palette.hasCustom() ? " +" : ""),
                T.t("🎨  Theme: ") + themeNames[idx(themes, Store.getStr("theme", "dark"))],
                T.t("📺  Device name: ") + Discovery.deviceName(a),
                T.t("🖼  Picture fit: ") + PlayerActivity.FIT_NAMES[Math.max(0, Math.min(3, Store.getInt("fit", 0)))],
                T.t("📐  Aspect ratio: ") + PlayerActivity.ASPECT_NAMES[idx(PlayerActivity.ASPECT_VALUES, Store.getStr("aspect", ""))],
                T.t("🎚  Equalizer…"),
                T.t("🔊  Default volume: ") + Store.getInt("vol", 100) + "%",
                T.t("💬  Subtitle size: ") + PlayerActivity.SUB_SIZE_NAMES[Math.max(0, idx(PlayerActivity.SUB_SIZES, Store.getInt("subsize", 16)))],
                T.t("💬  Subtitle background: ") + (Store.getBool("subbg", false) ? "On" : T.t("Off")),
                T.t("🔤  Subtitle encoding: ") + PlayerActivity.ENC_NAMES[idx(PlayerActivity.ENC_VALUES, Store.getStr("subenc", ""))],
                T.t("⚙  Decoder: ") + (Store.getInt("hw", 0) == 1 ? T.t("Software") : T.t("Hardware (auto)")),
                T.t("🌐  Network buffer: ") + Store.getInt("cache", 2000) / 1000f + " s",
                T.t("🔌  Start phone remote when device boots: ") + (Store.getBool("boot", Ui.isTv(a)) ? "On" : T.t("Off")),
                T.t("🧹  Clear “Recently played”"),
                T.t("🔓  Allow phone to start videos (display over apps): ") + (Perms.overlayOk(a) ? T.t("Allowed") : T.t("Not allowed")),
                T.t("🔓  Allow access to all files (USB, subtitles): ") + (Perms.filesOk() ? T.t("Allowed") : T.t("Not allowed")),
                "🎮  " + T.t("Control the whole TV from the phone") + ": " + T.t(Perms.accessOk(a) ? "Allowed" : "Not allowed"),
                "🔌  " + T.t("Full remote for every app (ADB)") + ": " + T.t(AdbClient.enabled() ? "On" : "Off"),
                "📺  " + T.t("Show other apps' videos on the remote") + ": " + T.t(SessionWatcher.allowed(a) ? "On" : "Off"),
                "⬇  " + T.t("Downloads") + " · " + T.t("night only") + ": " + T.t(TorrentDownloads.nightOnly() ? "On" : "Off"),
                "⬆  " + T.t("Check for updates") + " (" + Updater.installedName(a) + ")",
                "💾  " + T.t("Backup & restore"),
                "🧲  " + T.t("Torrent storage: ") + String.format(java.util.Locale.US, "%.2f GB", TorrentEngine.cacheBytes(a) / 1073741824.0),
                T.t("📱  Use as remote for another TV…"),
                "👨‍👩‍👧  " + T.t("Profiles & kids lock") + ": " + Family.label(),
                T.t("ℹ  Phone remote address: http://") + Net.ip() + ":" + RemoteService.PORT};
        new AlertDialog.Builder(a).setTitle(T.t("⚙  Settings")).setItems(items, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int w) {
                if (w == 0) {
                    final String[] codes = {"en", "ta", "hi"};
                    choose(a, "Language / மொழி / भाषा", new String[]{"English", "தமிழ் (Tamil)", "हिन्दी (Hindi)"},
                            java.util.Arrays.asList(codes).indexOf(T.lang()), new IntCb() {
                        public void on(int i) {
                            Store.putStr("lang", codes[i]);
                            restart(a);
                        }
                    });
                    return;
                }
                if (w == 1) {
                    pinMenu(a);
                    return;
                }
                if (w == 2) {
                    Palette.show(a);
                    return;
                }
                switch (w - 3) {
                    case 0:
                        choose(a, T.t("Theme"), themeNames, idx(themes, Store.getStr("theme", "dark")), new IntCb() {
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
                        new AlertDialog.Builder(a).setTitle(T.t("Device name (shown in the phone app)")).setView(e)
                                .setPositiveButton(T.t("Save"), new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d2, int w2) {
                                        Store.putStr("devname", e.getText().toString().trim());
                                        Discovery.unregister(a);
                                        Discovery.register(a);
                                        show(a);
                                    }
                                }).setNegativeButton(T.t("Cancel"), null).show();
                        break;
                    }
                    case 2:
                        choose(a, T.t("Picture fit"), PlayerActivity.FIT_NAMES, Store.getInt("fit", 0), new IntCb() {
                            public void on(int i) {
                                PlayerActivity.applySetting("fit", String.valueOf(i), "");
                                show(a);
                            }
                        });
                        break;
                    case 3:
                        choose(a, T.t("Aspect ratio"), PlayerActivity.ASPECT_NAMES,
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
                        for (int i = 0; i < v.length; i++) v[i] = PlayerActivity.VOLS[i] + "%" + (PlayerActivity.VOLS[i] > 100 ? T.t(" (boost)") : "");
                        choose(a, T.t("Default volume"), v, idx(PlayerActivity.VOLS, Store.getInt("vol", 100)), new IntCb() {
                            public void on(int i) {
                                PlayerActivity.applySetting("vol", String.valueOf(PlayerActivity.VOLS[i]), "");
                                show(a);
                            }
                        });
                        break;
                    }
                    case 6:
                        choose(a, T.t("Subtitle size"), PlayerActivity.SUB_SIZE_NAMES,
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
                        choose(a, T.t("Subtitle encoding"), PlayerActivity.ENC_NAMES,
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
                        for (int i = 0; i < v.length; i++) v[i] = PlayerActivity.CACHES[i] / 1000f + T.t(" seconds");
                        choose(a, T.t("Network buffer"), v, idx(PlayerActivity.CACHES, Store.getInt("cache", 2000)), new IntCb() {
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
                        Perms.openAccessSettings(a);
                        break;
                    case 16:
                        adbSetup(a);
                        break;
                    case 17:
                        if (SessionWatcher.allowed(a)) break;
                        if (AdbClient.enabled()) {
                            new Thread(() -> {
                                final String e = SessionWatcher.enableViaAdb(a);
                                a.runOnUiThread(() -> android.widget.Toast.makeText(a, e == null ? "✅" : e, android.widget.Toast.LENGTH_LONG).show());
                            }).start();
                        } else if (!Perms.launchFirst(a, new Intent[]{new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")})) {
                            android.widget.Toast.makeText(a, T.t("Turn on the 🔌 Full remote (ADB) first — then this switches on by itself"),
                                    android.widget.Toast.LENGTH_LONG).show();
                        }
                        break;
                    case 18:
                        Store.putBool("dl_night", !TorrentDownloads.nightOnly());
                        show(a);
                        break;
                    case 19:
                        Updater.check(a, false);
                        break;
                    case 20:
                        backupMenu(a);
                        break;
                    case 21:
                        new AlertDialog.Builder(a).setTitle("🧲  " + T.t("Torrent storage: "))
                                .setItems(new String[]{"🗑 Delete saved torrents (keep my kept movies)", "🗑 Delete everything (also kept movies)"},
                                        (d2, w2) -> new Thread(() -> TorrentEngine.get(a).clearCache(w2 == 1)).start()).show();
                        break;
                    case 22:
                        a.startActivity(new Intent(a, ConnectActivity.class));
                        break;
                    case 23:
                        if (Family.isKids()) Family.askPin(a, "", () -> Family.manage(a, a::recreate));
                        else Family.manage(a, a::recreate);
                        break;
                    default:
                        break;
                }
            }
        }).show();
    }

    /** ADB full remote: steps, then connect (TV asks "Allow debugging?"). */
    static void adbSetup(final Activity a) {
        String steps = T.t("Makes the phone remote work in EVERY app (YouTube, Netflix, settings) by pressing real keys.")
                + "\n\n1. TV Settings → Device Preferences → About → press “Build” 7 times (Developer options on)."
                + "\n2. Settings → Device Preferences → Developer options → turn on USB debugging / Network debugging."
                + "\n3. Press Connect. When the TV asks “Allow debugging?”, tick “Always allow” and press OK.";
        new AlertDialog.Builder(a).setTitle("🔌  " + T.t("Full remote for every app (ADB)")).setMessage(steps)
                .setPositiveButton(T.t("Connect"), (d, w) -> {
                    android.widget.Toast.makeText(a, "…", android.widget.Toast.LENGTH_SHORT).show();
                    new Thread(() -> {
                        final String e = AdbClient.connect(a, true);
                        Store.putBool("adb_on", e == null);
                        a.runOnUiThread(() -> android.widget.Toast.makeText(a, e == null ? "✅ " + T.t("Full remote for every app (ADB)") + ": ON" : e,
                                android.widget.Toast.LENGTH_LONG).show());
                    }).start();
                })
                .setNeutralButton(T.t("Off"), (d, w) -> Store.putBool("adb_on", false))
                .setNegativeButton(T.t("Cancel"), null).show();
    }

    static void backupMenu(final Activity a) {
        String[] items = {"💾 " + T.t("Save backup to the TV's Download folder"), "♻ " + T.t("Restore from a backup file"),
                "📱 " + T.t("Tip: the phone app can save / restore backups too (Playlist tab)")};
        new AlertDialog.Builder(a).setTitle("💾  " + T.t("Backup & restore")).setItems(items, (d, w) -> {
            if (w == 0) {
                new Thread(() -> {
                    String m;
                    try {
                        m = "✅ " + Backup.saveToDownloads(a);
                    } catch (Exception e) {
                        m = e.getMessage();
                    }
                    final String m2 = m;
                    a.runOnUiThread(() -> android.widget.Toast.makeText(a, m2, android.widget.Toast.LENGTH_LONG).show());
                }).start();
            } else if (w == 1) {
                final java.util.List<java.io.File> fs = Backup.findBackups(a);
                if (fs.isEmpty()) {
                    android.widget.Toast.makeText(a, T.t("No backup file found (Download folder or USB). Allow all-files access if it's on USB."),
                            android.widget.Toast.LENGTH_LONG).show();
                    return;
                }
                String[] n = new String[fs.size()];
                for (int i = 0; i < n.length; i++) n[i] = fs.get(i).getName() + "  ·  " + fs.get(i).getParent();
                new AlertDialog.Builder(a).setTitle(T.t("Restore from a backup file")).setItems(n, (d2, w2) -> {
                    String m = Backup.restoreFile(fs.get(w2));
                    android.widget.Toast.makeText(a, m, android.widget.Toast.LENGTH_LONG).show();
                    a.recreate();
                }).show();
            }
        }).show();
    }

    /** Restarts the app so every screen uses the new language. */
    static void restart(Activity a) {
        Intent i = new Intent(a, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        if (!Ui.isTv(a)) i.putExtra(MainActivity.EXTRA_PLAYER_HOME, true);
        a.startActivity(i);
        a.finishAffinity();
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
            public void run() {
                System.exit(0);
            }
        }, 300);
    }

    static void pinMenu(final Activity a) {
        String[] items = {
                T.t("Ask for PIN: ") + (Pair.enabled() ? "ON" : "OFF"),T.t("New PIN (forget all paired phones)")};
        new AlertDialog.Builder(a).setTitle("🔐  PIN: " + Pair.pin())
                .setMessage(T.t("Phones must enter this PIN once before they can control this device."))
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if (w == 0) Store.putBool("pin_on", !Pair.enabled());
                        else Pair.reset();
                        pinMenu(a);
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
        on.setText(T.t("Equalizer on"));
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
        new AlertDialog.Builder(a).setTitle(T.t("🎚  Equalizer  (◀ ▶ band · ▲ ▼ level)")).setView(box)
                .setPositiveButton(T.t("Done"), null)
                .setNeutralButton(T.t("Reset"), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        PlayerActivity.applySetting("eq_reset", "", "");
                    }
                }).show();
    }
}
