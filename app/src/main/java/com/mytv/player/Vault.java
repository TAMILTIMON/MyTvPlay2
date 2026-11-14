package com.mytv.player;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Environment;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * 🔒 Private folder: files moved here live in ZH Play's own private storage — no other app (Gallery,
 * WhatsApp, file managers…) can see or open them. Unlocked with the phone's fingerprint / face / screen
 * lock, or a ZH Play password. Uninstalling ZH Play or clearing its data deletes them (Android rule).
 */
final class Vault {
    private Vault() {
    }

    static final int REQ_UNLOCK = 4601;

    /** One private file. */
    static final class Item {
        String stored, name, orig;
        long size, at;
    }

    // ================================================================== storage

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "vault");
        if (!d.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        try {
            //noinspection ResultOfMethodCallIgnored
            new File(d, ".nomedia").createNewFile();
        } catch (Exception ignored) {
        }
        return d;
    }

    private static File indexFile(Context c) {
        return new File(dir(c), ".index.json");
    }

    static synchronized List<Item> list(Context c) {
        List<Item> l = new ArrayList<>();
        try {
            File f = indexFile(c);
            if (!f.isFile()) return l;
            byte[] b = new byte[(int) f.length()];
            try (InputStream in = new FileInputStream(f)) {
                int n = 0;
                while (n < b.length) {
                    int r = in.read(b, n, b.length - n);
                    if (r < 0) break;
                    n += r;
                }
            }
            JSONArray a = new JSONArray(new String(b, "UTF-8"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Item it = new Item();
                it.stored = o.optString("f");
                it.name = o.optString("name");
                it.orig = o.optString("orig");
                it.size = o.optLong("size");
                it.at = o.optLong("at");
                if (new File(dir(c), it.stored).isFile()) l.add(it);
            }
        } catch (Exception ignored) {
        }
        java.util.Collections.sort(l, (x, y) -> Long.compare(y.at, x.at));
        return l;
    }

    private static synchronized void save(Context c, List<Item> l) {
        try {
            JSONArray a = new JSONArray();
            for (Item it : l)
                a.put(new JSONObject().put("f", it.stored).put("name", it.name).put("orig", it.orig).put("size", it.size).put("at", it.at));
            File tmp = new File(dir(c), ".index.tmp");
            try (OutputStream o = new FileOutputStream(tmp)) {
                o.write(a.toString().getBytes("UTF-8"));
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(indexFile(c));
        } catch (Exception ignored) {
        }
    }

    static File fileOf(Context c, Item it) {
        return new File(dir(c), it.stored);
    }

    interface Progress {
        void on(String text);
    }

    private static boolean copy(File from, File to, Progress p, String label) {
        long total = Math.max(1, from.length()), done = 0, lastShown = 0;
        try (InputStream in = new FileInputStream(from); FileOutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if (p != null && System.currentTimeMillis() - lastShown > 300) {
                    lastShown = System.currentTimeMillis();
                    p.on(label + "  " + (done * 100 / total) + "%");
                }
            }
            out.getFD().sync();
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            to.delete();
            return false;
        }
        if (to.length() != from.length()) {
            //noinspection ResultOfMethodCallIgnored
            to.delete();
            return false;
        }
        return true;
    }

    /** Moves files in. Returns {moved, failed}. A file is only removed from its place once its private copy is complete. */
    static int[] moveIn(Context c, List<File> files, Progress p) {
        int ok = 0, bad = 0;
        List<Item> l = list(c);
        int k = 0;
        for (File f : files) {
            k++;
            if (f == null || !f.isFile()) {
                bad++;
                continue;
            }
            String label = "(" + k + "/" + files.size() + ")  " + f.getName();
            if (p != null) p.on(label);
            String stored = System.currentTimeMillis() + "_" + Math.abs(new SecureRandom().nextInt()) + "_" + f.getName().replaceAll("[\\\\/]", "_");
            File dest = new File(dir(c), stored);
            long size = f.length();
            if (!copy(f, dest, p, label)) {
                bad++;
                continue;
            }
            if (!f.delete()) {                       // couldn't remove the original: don't leave two copies
                //noinspection ResultOfMethodCallIgnored
                dest.delete();
                bad++;
                continue;
            }
            Item it = new Item();
            it.stored = stored;
            it.name = f.getName();
            it.orig = f.getAbsolutePath();
            it.size = size;
            it.at = System.currentTimeMillis();
            l.add(it);
            save(c, l);
            try {                                    // tell the Gallery it's gone
                MediaScannerConnection.scanFile(c, new String[]{f.getAbsolutePath()}, null, null);
            } catch (Exception ignored) {
            }
            ok++;
        }
        return new int[]{ok, bad};
    }

    /** Puts a file back where it came from (or Downloads if that folder is gone). Returns where, or null. */
    static File restore(Context c, Item it, Progress p) {
        File from = fileOf(c, it);
        File target = new File(it.orig);
        File parent = target.getParentFile();
        if (parent == null || !parent.isDirectory() || !parent.canWrite()) {
            parent = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
            target = new File(parent, it.name);
        }
        int n = 1;
        String base = it.name, ext = "";
        int dot = it.name.lastIndexOf('.');
        if (dot > 0) {
            base = it.name.substring(0, dot);
            ext = it.name.substring(dot);
        }
        while (target.exists()) target = new File(parent, base + " (" + (n++) + ")" + ext);
        if (!copy(from, target, p, it.name)) return null;
        //noinspection ResultOfMethodCallIgnored
        from.delete();
        List<Item> l = list(c);
        for (int i = l.size() - 1; i >= 0; i--) if (l.get(i).stored.equals(it.stored)) l.remove(i);
        save(c, l);
        try {
            MediaScannerConnection.scanFile(c, new String[]{target.getAbsolutePath()}, null, null);
        } catch (Exception ignored) {
        }
        return target;
    }

    static void deleteForever(Context c, Item it) {
        //noinspection ResultOfMethodCallIgnored
        fileOf(c, it).delete();
        List<Item> l = list(c);
        for (int i = l.size() - 1; i >= 0; i--) if (l.get(i).stored.equals(it.stored)) l.remove(i);
        save(c, l);
    }

    // ================================================================== moving files in (from Files)

    /** From the file manager: warns once, moves with progress, then runs done. */
    static void moveFlow(final Activity a, final List<File> files, final Runnable done) {
        final List<File> only = new ArrayList<>();
        for (File f : files) if (f != null && f.isFile()) only.add(f);
        if (only.isEmpty()) {
            UiKit.makeToast(a, T.t("Only files can be moved — open a folder and pick its files"), android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        Runnable go = () -> {
            final TextView status = new TextView(a);
            status.setTextColor(Ui.TEXT);
            status.setTextSize(15);
            status.setPadding(Ui.dp(a, 22), Ui.dp(a, 14), Ui.dp(a, 22), Ui.dp(a, 8));
            status.setText(T.t("Moving…"));
            final android.app.Dialog dlg = new UiDialog(a).setTitle("🔒 " + T.t("Private folder")).setView(status).setCancelable(false).show();
            new Thread(() -> {
                int[] r = moveIn(a.getApplicationContext(), only, t -> a.runOnUiThread(() -> status.setText(t)));
                a.runOnUiThread(() -> {
                    try {
                        dlg.dismiss();
                    } catch (Exception ignored) {
                    }
                    String m = "🔒 " + r[0] + " " + T.t(r[0] == 1 ? "file moved to the Private folder" : "files moved to the Private folder");
                    if (r[1] > 0) m += "\n⚠ " + r[1] + " " + T.t("couldn't be moved (no space, or the file can't be removed from its place)");
                    UiKit.makeToast(a, m, android.widget.Toast.LENGTH_LONG).show();
                    if (done != null) done.run();
                });
            }).start();
        };
        if (!Store.getBool("vault_warned", false)) {
            ZhPopup.confirm(a, "🔒", T.t("Move to the Private folder?"),
                    T.t("The files go into ZH Play's own private storage, hidden from every other app.") + "\n\n"
                            + T.t("Important: uninstalling ZH Play or clearing its data deletes them for good. Restore anything you need before reinstalling. Updating ZH Play is safe."),
                    T.t("Move"), false, () -> {
                        Store.putBool("vault_warned", true);
                        go.run();
                    });
        } else {
            go.run();
        }
    }

    // ================================================================== unlocking

    interface Result {
        void done(boolean ok);
    }

    private static Result pending;

    /** Locked with the ZH Play password (chosen, or because the phone has no screen lock). */
    static boolean usesPassword(Context c) {
        KeyguardManager km = (KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
        boolean phoneLock = km != null && km.isKeyguardSecure();
        return "pass".equals(Store.getStr("vault_lock", "device")) || !phoneLock;
    }

    static void unlock(final Activity a, final Result r) {
        if (usesPassword(a)) {
            passwordUnlock(a, r);
            return;
        }
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                android.hardware.biometrics.BiometricPrompt.Builder b = new android.hardware.biometrics.BiometricPrompt.Builder(a)
                        .setTitle(T.t("Private folder"))
                        .setSubtitle(T.t("Unlock with your fingerprint, face or screen lock"));
                if (Build.VERSION.SDK_INT >= 30) {
                    b.setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK
                            | android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL);
                } else if (Build.VERSION.SDK_INT == 29) {
                    b.setDeviceCredentialAllowed(true);
                } else {
                    b.setNegativeButton(T.t("Use screen lock"), a.getMainExecutor(), (d, w) -> keyguardUnlock(a, r));
                }
                b.build().authenticate(new CancellationSignal(), a.getMainExecutor(),
                        new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                            @Override
                            public void onAuthenticationSucceeded(android.hardware.biometrics.BiometricPrompt.AuthenticationResult res) {
                                r.done(true);
                            }

                            @Override
                            public void onAuthenticationError(int code, CharSequence msg) {
                                // Android 9 with no fingerprint set up: fall back to the screen lock
                                if (Build.VERSION.SDK_INT == 28
                                        && code != android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED
                                        && code != android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_CANCELED) {
                                    keyguardUnlock(a, r);
                                } else {
                                    r.done(false);
                                }
                            }
                        });
                return;
            } catch (Throwable ignored) {
                // fall through to the screen lock
            }
        }
        keyguardUnlock(a, r);
    }

    /** The phone's own screen-lock check (PIN / pattern / password). The answer arrives in onResult. */
    @SuppressWarnings("deprecation")
    private static void keyguardUnlock(Activity a, Result r) {
        KeyguardManager km = (KeyguardManager) a.getSystemService(Context.KEYGUARD_SERVICE);
        Intent i = km == null ? null : km.createConfirmDeviceCredentialIntent(T.t("Private folder"), T.t("Confirm it's you"));
        if (i == null) {
            passwordUnlock(a, r);
            return;
        }
        pending = r;
        a.startActivityForResult(i, REQ_UNLOCK);
    }

    /** Call from the screen's onActivityResult. Returns true if it was the unlock answer. */
    static boolean onResult(int req, int res) {
        if (req != REQ_UNLOCK || pending == null) return false;
        Result r = pending;
        pending = null;
        r.done(res == Activity.RESULT_OK);
        return true;
    }

    // ---- ZH Play password (stored only as a salted fingerprint, never the password itself)

    static boolean hasPassword() {
        return !Store.getStr("vault_pw", "").isEmpty();
    }

    private static String hash(String salt, String pw) {
        try {
            byte[] h = (salt + pw).getBytes("UTF-8");
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (int i = 0; i < 20000; i++) h = md.digest(h);              // slow on purpose: hard to guess offline
            StringBuilder sb = new StringBuilder();
            for (byte x : h) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    static void setPassword(String pw) {
        byte[] s = new byte[16];
        new SecureRandom().nextBytes(s);
        StringBuilder salt = new StringBuilder();
        for (byte x : s) salt.append(String.format("%02x", x));
        Store.putStr("vault_salt", salt.toString());
        Store.putStr("vault_pw", hash(salt.toString(), pw));
    }

    static boolean checkPassword(String pw) {
        return !pw.isEmpty() && hash(Store.getStr("vault_salt", ""), pw).equals(Store.getStr("vault_pw", ""));
    }

    private static EditText pwField(Activity a, String hint) {
        EditText e = new EditText(a);
        e.setHint(hint);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        e.setSingleLine(true);
        return e;
    }

    static void passwordUnlock(final Activity a, final Result r) {
        if (!hasPassword()) {
            createPassword(a, r);
            return;
        }
        final EditText e = pwField(a, T.t("Password"));
        LinearLayout box = new LinearLayout(a);
        box.setPadding(Ui.dp(a, 20), Ui.dp(a, 6), Ui.dp(a, 20), 0);
        box.addView(e, new LinearLayout.LayoutParams(-1, -2));
        new UiDialog(a).setTitle("🔒 " + T.t("Private folder")).setView(box).setCancelable(false)
                .setPositiveButton(T.t("Unlock"), (d, w) -> {
                    if (checkPassword(e.getText().toString())) r.done(true);
                    else {
                        UiKit.makeToast(a, "⚠ " + T.t("Wrong password"), android.widget.Toast.LENGTH_SHORT).show();
                        r.done(false);
                    }
                })
                .setNegativeButton(T.t("Cancel"), (d, w) -> r.done(false)).show();
    }

    static void createPassword(final Activity a, final Result r) {
        final EditText p1 = pwField(a, T.t("New password (at least 4 characters)")), p2 = pwField(a, T.t("Type it again"));
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(a, 20), Ui.dp(a, 6), Ui.dp(a, 20), 0);
        TextView why = new TextView(a);
        why.setTextColor(Ui.DIM);
        why.setTextSize(13);
        why.setText(T.t("Choose a password for the Private folder. If you forget it, the private files can't be opened."));
        box.addView(why);
        box.addView(p1);
        box.addView(p2);
        new UiDialog(a).setTitle("🔒 " + T.t("Set a password")).setView(box).setCancelable(false)
                .setPositiveButton(T.t("Save"), (d, w) -> {
                    String x = p1.getText().toString(), y = p2.getText().toString();
                    if (x.length() < 4 || !x.equals(y)) {
                        UiKit.makeToast(a, "⚠ " + T.t("The passwords must match and be at least 4 characters"), android.widget.Toast.LENGTH_LONG).show();
                        r.done(false);
                        return;
                    }
                    setPassword(x);
                    r.done(true);
                })
                .setNegativeButton(T.t("Cancel"), (d, w) -> r.done(false)).show();
    }
}
