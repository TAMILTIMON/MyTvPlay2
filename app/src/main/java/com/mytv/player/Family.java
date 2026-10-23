package com.mytv.player;

import android.app.Activity;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Profiles ("Who's watching?") and kids lock (PIN, locked playlists / apps, daily screen time). */
public final class Family {
    private Family() {
    }

    // ------------------------------------------------------------------ profiles
    public static List<JSONObject> profiles() {
        List<JSONObject> l = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Store.getStr("profiles", "[]"));
            for (int i = 0; i < a.length(); i++) l.add(a.getJSONObject(i));
        } catch (Exception ignored) {
        }
        if (l.isEmpty()) {
            try {
                l.add(new JSONObject().put("id", "0").put("name", "Family").put("emoji", "🏠").put("kids", false));
            } catch (Exception ignored) {
            }
        }
        return l;
    }

    private static void save(List<JSONObject> l) {
        Store.putStr("profiles", new JSONArray(l).toString());
    }

    public static JSONObject current() {
        String id = Store.profile();
        for (JSONObject p : profiles()) if (p.optString("id").equals(id)) return p;
        return profiles().get(0);
    }

    public static String label() {
        JSONObject p = current();
        return p.optString("emoji", "🙂") + " " + p.optString("name");
    }

    public static boolean isKids() {
        return current().optBoolean("kids", false);
    }

    // ------------------------------------------------------------------ locks
    private static Set<String> set(String k) {
        Set<String> s = new HashSet<>();
        try {
            JSONArray a = new JSONArray(Store.getStr(k, "[]"));
            for (int i = 0; i < a.length(); i++) s.add(a.getString(i));
        } catch (Exception ignored) {
        }
        return s;
    }

    public static boolean playlistAllowed(String name) {
        return !isKids() || !set("locked_pl").contains(name);
    }

    public static boolean appAllowed(String pkg) {
        return !isKids() || !set("locked_apps").contains(pkg);
    }

    private static String today() {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
    }

    /** Minutes left today for the kids profile, or -1 = no limit. */
    public static int minutesLeft() {
        if (!isKids()) return -1;
        int lim = Store.getInt("kids_min", 0);
        if (lim <= 0) return -1;
        int used = Store.getInt("kids_used_" + today(), 0), bonus = Store.getInt("kids_bonus_" + today(), 0);
        return Math.max(0, lim + bonus - used);
    }

    private static float acc = 0;

    /** Called by the player while something plays. */
    public static void tick(float seconds) {
        if (!isKids() || Store.getInt("kids_min", 0) <= 0) return;
        acc += seconds;
        if (acc >= 60) {
            acc -= 60;
            String k = "kids_used_" + today();
            Store.putInt(k, Store.getInt(k, 0) + 1);
        }
    }

    public static void addBonus(int min) {
        String k = "kids_bonus_" + today();
        Store.putInt(k, Store.getInt(k, 0) + min);
    }

    public interface Ok {
        void run();
    }

    /** Asks for the parents' PIN (sets one the first time). */
    public static void askPin(final Activity a, String why, final Ok ok) {
        final String pin = Store.getStr("kids_pin", "");
        final EditText e = new EditText(a);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        e.setHint("••••");
        new AlertDialog.Builder(a).setTitle("🔒 " + (pin.isEmpty() ? T.t("Choose a parents' PIN (4 digits)") : T.t("Parents' PIN")))
                .setMessage(why)
                .setView(e)
                .setPositiveButton(T.t("OK"), (d, w) -> {
                    String v = e.getText().toString().trim();
                    if (pin.isEmpty()) {
                        if (v.length() < 4) {
                            Toast.makeText(a, T.t("Use at least 4 digits"), Toast.LENGTH_LONG).show();
                            return;
                        }
                        Store.putStr("kids_pin", v);
                        ok.run();
                    } else if (v.equals(pin)) ok.run();
                    else Toast.makeText(a, T.t("Wrong PIN"), Toast.LENGTH_LONG).show();
                })
                .setNegativeButton(T.t("Cancel"), null).show();
    }

    // ------------------------------------------------------------------ UI
    /** "Who's watching?" */
    public static void choose(final Activity a, final Ok after) {
        final List<JSONObject> l = profiles();
        String[] n = new String[l.size() + 2];
        for (int i = 0; i < l.size(); i++) {
            JSONObject p = l.get(i);
            n[i] = p.optString("emoji", "🙂") + "  " + p.optString("name") + (p.optBoolean("kids") ? "  (" + T.t("kids") + ")" : "")
                    + (p.optString("id").equals(Store.profile()) ? "  ✓" : "");
        }
        n[l.size()] = "＋  " + T.t("Add profile");
        n[l.size() + 1] = "⚙  " + T.t("Manage profiles & kids lock");
        new AlertDialog.Builder(a).setTitle("👨‍👩‍👧  " + T.t("Who's watching?")).setItems(n, (d, w) -> {
            if (w < l.size()) {
                final JSONObject target = l.get(w);
                Ok sw = () -> {
                    Store.putStr("profile", target.optString("id"));
                    Store.version++;
                    if (after != null) after.run();
                };
                if (isKids() && !target.optBoolean("kids")) askPin(a, T.t("Leaving the kids profile needs the parents' PIN."), sw);
                else sw.run();
            } else if (w == l.size()) {
                if (isKids()) askPin(a, "", () -> edit(a, null, after));
                else edit(a, null, after);
            } else {
                if (isKids()) askPin(a, "", () -> manage(a, after));
                else manage(a, after);
            }
        }).show();
    }

    private static final String[] EMOJI = {"🙂", "👨", "👩", "👦", "👧", "👴", "👵", "🧑", "🐯", "🦁", "🐼", "🚀", "⚽", "🎮", "🎵", "🏠"};

    private static void edit(final Activity a, final JSONObject existing, final Ok after) {
        final EditText name = new EditText(a);
        name.setHint(T.t("Name"));
        name.setText(existing == null ? "" : existing.optString("name"));
        new AlertDialog.Builder(a).setTitle(existing == null ? T.t("Add profile") : T.t("Edit profile")).setView(name)
                .setPositiveButton(T.t("Next"), (d, w) -> new AlertDialog.Builder(a).setTitle(T.t("Picture"))
                        .setItems(EMOJI, (d2, w2) -> new AlertDialog.Builder(a).setTitle(T.t("Is this a kids profile?"))
                                .setItems(new String[]{"👶 " + T.t("Yes — kids lock applies"), "🧑 " + T.t("No")}, (d3, w3) -> {
                                    List<JSONObject> l = profiles();
                                    try {
                                        JSONObject p = existing;
                                        if (p == null) {
                                            p = new JSONObject().put("id", String.valueOf(System.currentTimeMillis() % 1000000));
                                            l.add(p);
                                        } else {
                                            for (JSONObject x : l) if (x.optString("id").equals(p.optString("id"))) p = x;
                                        }
                                        p.put("name", name.getText().toString().trim().isEmpty() ? "Profile" : name.getText().toString().trim());
                                        p.put("emoji", EMOJI[w2]);
                                        p.put("kids", w3 == 0);
                                    } catch (Exception ignored) {
                                    }
                                    save(l);
                                    if (w3 == 0 && Store.getStr("kids_pin", "").isEmpty()) askPin(a, "", () -> { });
                                    if (after != null) after.run();
                                }).show()).show())
                .setNegativeButton(T.t("Cancel"), null).show();
    }

    public static void manage(final Activity a, final Ok after) {
        final List<JSONObject> l = profiles();
        List<String> n = new ArrayList<>();
        for (JSONObject p : l) n.add("✏  " + p.optString("emoji") + " " + p.optString("name"));
        n.add("🔒  " + T.t("Locked playlists (kids)"));
        n.add("🔒  " + T.t("Locked apps (kids)"));
        int lim = Store.getInt("kids_min", 0);
        n.add("⏰  " + T.t("Daily screen time (kids)") + ": " + (lim <= 0 ? T.t("No limit") : lim + " min"));
        n.add("🔑  " + T.t("Change parents' PIN"));
        new AlertDialog.Builder(a).setTitle("⚙ " + T.t("Manage profiles & kids lock"))
                .setItems(n.toArray(new String[0]), (d, w) -> {
                    if (w < l.size()) {
                        final JSONObject p = l.get(w);
                        new AlertDialog.Builder(a).setTitle(p.optString("name"))
                                .setItems(new String[]{"✏ " + T.t("Edit profile"), "🗑 " + T.t("Delete")}, (d2, w2) -> {
                                    if (w2 == 0) edit(a, p, after);
                                    else if (!"0".equals(p.optString("id"))) {
                                        List<JSONObject> k = profiles();
                                        List<JSONObject> keep = new ArrayList<>();
                                        for (JSONObject x : k) if (!x.optString("id").equals(p.optString("id"))) keep.add(x);
                                        save(keep);
                                        if (Store.profile().equals(p.optString("id"))) Store.putStr("profile", "0");
                                        if (after != null) after.run();
                                    } else Toast.makeText(a, T.t("The first profile can't be deleted"), Toast.LENGTH_LONG).show();
                                }).show();
                    } else if (w == l.size()) pickSet(a, "locked_pl", Store.names(), Store.names());
                    else if (w == l.size() + 1) {
                        List<String> pk = new ArrayList<>(), nm = new ArrayList<>();
                        for (AppsHub.App ap : AppsHub.choices(a)) {
                            if (!ap.installed) continue;
                            pk.add(ap.pkg);
                            nm.add(ap.name);
                        }
                        pickSet(a, "locked_apps", pk, nm);
                    } else if (w == l.size() + 2) {
                        final int[] v = {0, 30, 60, 90, 120, 180, 240};
                        String[] vn = new String[v.length];
                        for (int i = 0; i < v.length; i++) vn[i] = v[i] == 0 ? T.t("No limit") : v[i] + " " + T.t(" minutes").trim();
                        new AlertDialog.Builder(a).setTitle("⏰ " + T.t("Daily screen time (kids)"))
                                .setItems(vn, (d2, w2) -> Store.putInt("kids_min", v[w2])).show();
                    } else {
                        askPin(a, T.t("Enter the current PIN"), () -> {
                            Store.putStr("kids_pin", "");
                            askPin(a, T.t("Choose a parents' PIN (4 digits)"), () -> { });
                        });
                    }
                }).show();
    }

    private static void pickSet(Activity a, final String key, final List<String> values, List<String> names) {
        final Set<String> cur = set(key);
        final boolean[] on = new boolean[values.size()];
        for (int i = 0; i < on.length; i++) on[i] = cur.contains(values.get(i));
        new AlertDialog.Builder(a).setTitle(T.t("Locked for the kids profile"))
                .setMultiChoiceItems(names.toArray(new String[0]), on, (d, w, c) -> on[w] = c)
                .setPositiveButton(T.t("Save"), (d, w) -> {
                    JSONArray out = new JSONArray();
                    for (int i = 0; i < on.length; i++) if (on[i]) out.put(values.get(i));
                    Store.putStr(key, out.toString());
                })
                .setNegativeButton(T.t("Cancel"), null).show();
    }
}
