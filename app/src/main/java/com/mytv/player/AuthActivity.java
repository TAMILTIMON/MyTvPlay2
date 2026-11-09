package com.mytv.player;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.credentials.ClearCredentialStateRequest;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.ClearCredentialException;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;

import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;

import java.util.HashMap;
import java.util.concurrent.Executor;

/**
 * Sign-in gate: "Sign in with Google" (phone), QR code to sign in from a phone (TV), and the screens for
 * blocked / maintenance / update required. Also opens from the TV's QR link on a phone (zhplay://tvlogin).
 */
public class AuthActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Executor onMain = r -> main.post(r);
    private LinearLayout box;
    private String pendingTv;           // "ip|port|code" from a TV's QR, done after signing in here
    private String shownState = "";

    static void open(Context c) {
        Intent i = new Intent(c, AuthActivity.class);
        if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        UiKit.makeToast(this, s, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onCreate(Bundle b) {
        Ui.apply(this);
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setBackgroundColor(Ui.BG);
        FrameLayout center = new FrameLayout(this);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(28), dp(36), dp(28), dp(36));
        center.addView(box, new FrameLayout.LayoutParams(Math.min(dp(560), getResources().getDisplayMetrics().widthPixels), -2, Gravity.CENTER));
        sv.addView(center);
        setContentView(sv);
        handleLink(getIntent());
        render();
        if (getIntent().getBooleanExtra("cloud", false) && Auth.signedIn() && Cloud.enabled() && !Cloud.signedIn()) {
            main.postDelayed(this::googleSignIn, 400);       // from "My TVs anywhere": connect the cloud at once
        }
        Auth.refresh(this, true, this::render);
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        handleLink(i);
        render();
    }

    private final Runnable poll = new Runnable() {          // TV: closes by itself once a phone signed it in
        public void run() {
            if (isFinishing()) return;
            String st = Auth.state(AuthActivity.this);
            if (pendingTv == null && Auth.OK.equals(st)) {     // signed in (e.g. from the phone by QR) → done
                finish();
                return;
            }
            if (!st.equals(shownState)) render();
            main.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        main.removeCallbacks(poll);
        main.postDelayed(poll, 2000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(poll);
    }

    @Override
    public void onBackPressed() {
        if (Auth.locked(this) && pendingTv == null) moveTaskToBack(true);      // can't skip past the gate
        else super.onBackPressed();
    }

    // ------------------------------------------------------------------ screens

    private TextView text(float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView button(String label, boolean primary, View.OnClickListener l) {
        TextView t = text(16, primary ? Color.WHITE : Ui.TEXT, true);
        t.setText(label);
        t.setPadding(dp(20), dp(14), dp(20), dp(14));
        t.setBackground(primary ? Ui.gradient(this, 26) : Ui.focusCard(this, 26));
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(12), 0, 0);
        t.setLayoutParams(lp);
        return t;
    }

    private void header(String title, String sub) {
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.zh_logo);
        box.addView(logo, new LinearLayout.LayoutParams(dp(88), dp(88)));
        TextView t = text(24, Ui.TEXT, true);
        t.setText(title);
        t.setPadding(0, dp(16), 0, dp(8));
        box.addView(t);
        if (sub != null && !sub.isEmpty()) {
            TextView s = text(15, Ui.DIM, false);
            s.setText(sub);
            s.setPadding(0, 0, 0, dp(10));
            box.addView(s);
        }
    }

    private void render() {
        if (box == null) return;
        box.removeAllViews();
        boolean tv = Ui.isTv(this);
        String st = Auth.state(this);
        shownState = st;

        if (pendingTv != null) {                          // phone, opened from a TV's QR
            if (!Auth.signedIn()) {
                header(T.t("Sign in the TV"), T.t("First sign in on this phone; the TV then uses the same Google account."));
                box.addView(button("G  " + T.t("Sign in with Google"), true, v -> googleSignIn()));
            } else {
                header(T.t("Sign in the TV"), T.t("Signing the TV in as") + "\n" + Auth.email() + " …");
                signInTv();
            }
            return;
        }
        if (Auth.MAINTENANCE.equals(st)) {
            String m = Auth.cfg().optString("maintenance_message", "");
            header("🛠  " + T.t("Back soon"), m.isEmpty() ? T.t("ZH Play is being updated by the admin. Please try again later.") : m);
            box.addView(button(T.t("Try again"), true, v -> Auth.refresh(this, true, this::render)));
            box.addView(button(T.t("Exit"), false, v -> finishAffinity()));
            return;
        }
        if (Auth.UPDATE.equals(st)) {
            header("⬆  " + T.t("Update required"), T.t("This version of ZH Play is too old. Please install the latest version."));
            box.addView(button(T.t("Update now"), true, v -> Updater.check(this, false)));
            box.addView(button(T.t("Exit"), false, v -> finishAffinity()));
            return;
        }
        if (Auth.BLOCKED.equals(st)) {
            header("🚫  " + T.t("No access"), Auth.email() + "\n" + T.t("This account can't use ZH Play. Contact the admin."));
            box.addView(button(T.t("Use another account"), true, v -> signOut(this, this::render)));
            box.addView(button(T.t("Exit"), false, v -> finishAffinity()));
            return;
        }
        if (Auth.signedIn()) {                            // opened by hand while signed in
            header("✅  " + T.t("Signed in"), Auth.name() + "\n" + Auth.email());
            if (Cloud.enabled() && !Cloud.signedIn()) {
                // signed in before ZH Play cloud was set up: one more tap links this account to the cloud
                String err = Store.getStr("fb_err", "");
                TextView w = text(14, Ui.DIM, false);
                w.setText(T.t("ZH Play cloud (TVs from anywhere) is not connected yet.") + (err.isEmpty() ? "" : "\n" + err));
                box.addView(w);
                box.addView(button("☁  " + T.t("Connect ZH Play cloud"), true, v -> googleSignIn()));
                box.addView(button(T.t("Continue"), false, v -> finish()));
                return;
            }
            box.addView(button(T.t("Continue"), true, v -> finish()));
            return;
        }
        if (tv) {
            header(T.t("Sign in to ZH Play"), T.t("Scan this code with your phone's camera and open the link. ZH Play on your phone signs this TV in with your Google account."));
            ImageView qr = new ImageView(this);
            Bitmap bm = QrCode.make(Auth.tvLoginUrl(), dp(260));
            if (bm != null) qr.setImageBitmap(bm);
            qr.setBackgroundColor(Color.WHITE);
            qr.setPadding(dp(12), dp(12), dp(12), dp(12));
            LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(dp(260), dp(260));
            qlp.setMargins(0, dp(10), 0, dp(10));
            box.addView(qr, qlp);
            TextView code = text(28, Ui.ACCENT, true);
            code.setText(Auth.tvCode());
            code.setLetterSpacing(0.15f);
            box.addView(code);
            TextView how = text(14, Ui.DIM, false);
            how.setText(T.t("Or on the phone: ZH Play remote → it offers “Sign in this TV”."));
            how.setPadding(0, dp(8), 0, 0);
            box.addView(how);
            box.addView(button(T.t("Sign in with Google on this TV"), false, v -> googleSignIn()));
            box.addView(qrLink());
            if (Auth.NOTICE.equals(st)) box.addView(button(T.t("Later"), false, v -> finish()));
            return;
        }
        String when = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                .format(new java.util.Date(Auth.deadline()));
        header(T.t("Sign in to ZH Play"), Auth.NOTICE.equals(st)
                ? T.t("Sign-in with Google is required from") + " " + when + "."
                : T.t("Sign in with your Google account to use ZH Play."));
        box.addView(button("G  " + T.t("Sign in with Google"), true, v -> googleSignIn()));
        box.addView(qrLink());                                 // phones where Google sign-in fails
        if (Auth.NOTICE.equals(st)) box.addView(button(T.t("Later"), false, v -> finish()));
        TextView note = text(12, Ui.DIM, false);
        note.setText(T.t("Only your name and e-mail are used, to let you in. Nothing is stored on any server."));
        note.setPadding(0, dp(18), 0, 0);
        box.addView(note);
    }

    // ------------------------------------------------------------------ Google sign-in

    private void googleSignIn() {
        googleSignIn(false);
    }

    private long signInStarted;

    /** alt = Google's second sign-in method (bottom sheet); used when the first one fails on a phone. */
    private void googleSignIn(final boolean alt) {
        signInStarted = System.currentTimeMillis();
        String cid = Auth.webClientId(this);
        if (cid.isEmpty()) {
            toast(T.t("Not set up yet: the admin must put web_client_id in admin/access.json"));
            return;
        }
        GetCredentialRequest req = new GetCredentialRequest.Builder()
                .addCredentialOption(alt
                        ? new com.google.android.libraries.identity.googleid.GetGoogleIdOption.Builder()
                        .setFilterByAuthorizedAccounts(false).setServerClientId(cid).setAutoSelectEnabled(false).build()
                        : new GetSignInWithGoogleOption.Builder(cid).build())
                .build();
        try {
            CredentialManager.create(this).getCredentialAsync(this, req, null, onMain,
                    new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                        @Override
                        public void onResult(GetCredentialResponse r) {
                            Credential c = r.getCredential();
                            if (c instanceof CustomCredential && GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(c.getType())) {
                                try {
                                    GoogleIdTokenCredential g = GoogleIdTokenCredential.createFrom(c.getData());
                                    String mail = emailFrom(g.getIdToken());
                                    if (mail.isEmpty()) mail = g.getId();
                                    Auth.signIn(mail, g.getDisplayName(), g.getProfilePictureUri() == null ? "" : g.getProfilePictureUri().toString());
                                    final String gid = g.getIdToken();
                                    new Thread(() -> {                  // same account in ZH Play cloud (TVs from anywhere)
                                        String res;
                                        try {
                                            Cloud.signInWithGoogle(gid);
                                            res = Cloud.signedIn() ? "☁ " + T.t("ZH Play cloud connected") : null;
                                            Store.putStr("fb_err", "");
                                        } catch (Exception e) {
                                            Store.putStr("fb_err", String.valueOf(e.getMessage()));
                                            res = "⚠ " + T.t("Cloud") + ": " + e.getMessage();
                                        }
                                        final String fr = res;
                                        if (fr != null) main.post(() -> {
                                            toast(fr);
                                            render();
                                        });
                                    }).start();
                                    toast("✅ " + T.t("Signed in as") + " " + Auth.email());
                                    afterSignIn();
                                    return;
                                } catch (Exception e) {
                                    toast("⚠ " + e.getMessage());
                                    return;
                                }
                            }
                            toast("⚠ " + T.t("Unexpected sign-in answer"));
                        }

                        @Override
                        public void onError(GetCredentialException e) {
                            long took = System.currentTimeMillis() - signInStarted;
                            // a real "cancel" takes the person a few seconds; an instant one is Google refusing
                            if (e instanceof GetCredentialCancellationException && took > 4000) return;
                            if (!alt) {
                                googleSignIn(true);                 // try Google's other sign-in method once
                                return;
                            }
                            explainSignInFailure(e.getType(), e.getErrorMessage());
                        }
                    });
        } catch (Throwable t) {
            toast("⚠ " + T.t("Google sign-in isn't available on this device") + ": " + t.getMessage());
        }
    }

    // ------------------------------------------------------------------ sign in with a QR code / browser

    /** Small link under the Google button. */
    private View qrLink() {
        TextView t = text(14, Ui.ACCENT, true);
        t.setText("▦  " + T.t("Sign in with a QR code or browser"));
        t.setPadding(dp(8), dp(16), dp(8), dp(8));
        t.setFocusable(true);
        t.setClickable(true);
        t.setBackground(Ui.focusCard(this, 18));
        t.setOnClickListener(v -> qrSignIn());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.topMargin = dp(10);
        t.setLayoutParams(lp);
        return t;
    }

    private volatile String qrCode;

    /**
     * Shows a QR for the ZH Play sign-in web page: sign in there on any phone / computer, or in this
     * phone's own browser (Chrome's Google sign-in works where the phone's built-in one doesn't).
     * The page hands the Google sign-in to this device through Firebase (handoff/{random code}).
     */
    private void qrSignIn() {
        if (Auth.webClientId(this).isEmpty() || !Cloud.enabled()) {
            ZhPopup.message(this, "▦", T.t("Not available yet"), T.t("The admin hasn't set up sign-in (access.json)."));
            return;
        }
        byte[] r = new byte[24];
        new java.security.SecureRandom().nextBytes(r);
        final String code = Fcm.b64url(r);
        qrCode = code;
        final String page = Auth.cfg().optString("signin_page", "https://tamiltimon.github.io/MyTvPlay2/signin.html") + "?c=" + code;

        box.removeAllViews();
        header("▦  " + T.t("Sign in with a QR code"), T.t("Scan with another phone's camera (or open it on a computer) and sign in with Google there. Or open the page in this phone's browser."));
        ImageView qr = new ImageView(this);
        android.graphics.Bitmap bm = QrCode.make(page, dp(240));
        if (bm != null) qr.setImageBitmap(bm);
        qr.setBackgroundColor(Color.WHITE);
        qr.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams ql = new LinearLayout.LayoutParams(dp(240), dp(240));
        ql.setMargins(0, dp(10), 0, dp(8));
        box.addView(qr, ql);
        final TextView status = text(14, Ui.DIM, false);
        status.setText("⏳  " + T.t("Waiting for you to sign in…"));
        box.addView(status);
        if (!Ui.isTv(this)) {
            box.addView(button("🌐  " + T.t("Open in this phone's browser"), true, v -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(page)).addCategory(Intent.CATEGORY_BROWSABLE));
                } catch (Exception e) {
                    toast("⚠ " + T.t("No browser found"));
                }
            }));
        }
        box.addView(button(T.t("Back"), false, v -> {
            qrCode = null;
            render();
        }));

        // wait for the page to hand the sign-in over (up to 10 minutes)
        final long until = System.currentTimeMillis() + 10 * 60 * 1000L;
        new Thread(() -> {
            while (code.equals(qrCode) && System.currentTimeMillis() < until && !isFinishing()) {
                try {
                    Thread.sleep(2000);
                    String s = Cloud.http("GET", Cloud.dbUrl() + "/handoff/" + code + ".json", null);
                    if (s == null || s.trim().equals("null")) continue;
                    String tok = new org.json.JSONObject(s).optString("t");
                    if (tok.isEmpty()) continue;
                    try {
                        Cloud.http("DELETE", Cloud.dbUrl() + "/handoff/" + code + ".json", null);   // used once
                    } catch (Exception ignored) {
                    }
                    finishQrSignIn(tok);
                    return;
                } catch (Exception ignored) {
                }
            }
            if (code.equals(qrCode)) main.post(() -> status.setText("⌛  " + T.t("The code expired — tap Back and try again")));
        }).start();
    }

    /** The Google sign-in arrived from the web page: same as signing in here. */
    private void finishQrSignIn(final String idToken) {
        String mail = emailFrom(idToken), name = "";
        try {
            String[] p = idToken.split("\\.");
            org.json.JSONObject c = new org.json.JSONObject(new String(Fcm.b64decode(p[1]), "UTF-8"));
            name = c.optString("name", "");
            if (mail.isEmpty()) mail = c.optString("email", "");
        } catch (Exception ignored) {
        }
        if (mail.isEmpty()) {
            main.post(() -> toast("⚠ " + T.t("Sign-in didn't include an e-mail — try again")));
            return;
        }
        try {
            Cloud.signInWithGoogle(idToken);                  // ZH Play cloud, same account
            Store.putStr("fb_err", "");
        } catch (Exception e) {
            Store.putStr("fb_err", String.valueOf(e.getMessage()));
        }
        final String fm = mail, fn = name;
        main.post(() -> {
            qrCode = null;
            Auth.signIn(fm, fn, "");
            ZhPopup.banner(this, "✅ " + T.t("Signed in as") + " " + fm);
            afterSignIn();
        });
    }

    /** Why Google sign-in failed on this phone, with this install's fingerprint for the admin. */
    private void explainSignInFailure(String type, CharSequence msg) {
        String installer = "";
        try {
            installer = getPackageManager().getInstallerPackageName(getPackageName());
        } catch (Exception ignored) {
        }
        boolean amazon = installer != null && installer.contains("amazon");
        String sha = sha1();
        StringBuilder sb = new StringBuilder();
        sb.append(T.t("Google refused the sign-in on this phone.")).append("\n\n");
        if (amazon) {
            sb.append("• ").append(T.t("This ZH Play came from the Amazon Appstore, which signs it with Amazon's key. The admin must add this fingerprint to the Android sign-in client in Google Cloud (or install ZH Play from GitHub):")).append("\n");
        } else {
            sb.append("• ").append(T.t("Update “Google Play services” in the Play Store, and make sure a Google account is added in the phone's Settings → Accounts.")).append("\n");
            sb.append("• ").append(T.t("Check the phone's date and time are automatic.")).append("\n");
            sb.append("• ").append(T.t("If it still fails, the admin can add this fingerprint to the Android sign-in client in Google Cloud:")).append("\n");
        }
        sb.append("SHA-1: ").append(sha).append("\n\n");
        sb.append(T.t("Installed from")).append(": ").append(installer == null || installer.isEmpty() ? T.t("file (APK)") : installer).append("\n");
        sb.append(T.t("Details")).append(": ").append(type == null ? "" : type.replace("android.credentials.GetCredentialException.", ""))
                .append(msg == null ? "" : " — " + msg);
        final String shaF = sha;
        ZhPopup.confirm(this, "⚠", T.t("Sign-in didn't work"), sb.toString(), T.t("Copy SHA-1"), false, () -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("SHA-1", shaF));
            ZhPopup.banner(this, "✓ " + T.t("SHA-1 copied"));
        });
    }

    /** SHA-1 fingerprint of the key this installed ZH Play is signed with. */
    @SuppressWarnings("deprecation")
    private String sha1() {
        try {
            android.content.pm.Signature[] sig;
            if (Build.VERSION.SDK_INT >= 28) {
                sig = getPackageManager().getPackageInfo(getPackageName(), android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
                        .signingInfo.getApkContentsSigners();
            } else {
                sig = getPackageManager().getPackageInfo(getPackageName(), android.content.pm.PackageManager.GET_SIGNATURES).signatures;
            }
            byte[] d = java.security.MessageDigest.getInstance("SHA-1").digest(sig[0].toByteArray());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < d.length; i++) sb.append(i == 0 ? "" : ":").append(String.format(java.util.Locale.ROOT, "%02X", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return "?";
        }
    }

    /** The e-mail inside Google's ID token (its middle part is base64 JSON). */
    private static String emailFrom(String idToken) {
        try {
            String[] p = idToken.split("\\.");
            String json = new String(android.util.Base64.decode(p[1], android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING), "UTF-8");
            return new org.json.JSONObject(json).optString("email", "");
        } catch (Exception e) {
            return "";
        }
    }

    private void afterSignIn() {
        Auth.refresh(this, true, () -> {
            if (pendingTv != null) render();          // now sign the TV in
            else if (!Auth.locked(this)) finish();
            else render();
        });
    }

    static void signOut(Activity a, Runnable then) {
        Auth.signOut();
        Cloud.signOut();
        try {
            CredentialManager.create(a).clearCredentialStateAsync(new ClearCredentialStateRequest(), null, r -> new Handler(Looper.getMainLooper()).post(r),
                    new CredentialManagerCallback<Void, ClearCredentialException>() {
                        public void onResult(Void v) {
                        }

                        public void onError(ClearCredentialException e) {
                        }
                    });
        } catch (Throwable ignored) {
        }
        if (then != null) then.run();
    }

    // ------------------------------------------------------------------ phone → TV (QR link)

    /** zhplay://tvlogin?ip=…&port=…&c=… (from the page the TV's QR opens). */
    private void handleLink(Intent i) {
        Uri u = i == null ? null : i.getData();
        if (u == null || !"zhplay".equals(u.getScheme()) || !"tvlogin".equals(u.getHost())) return;
        String ip = u.getQueryParameter("ip"), port = u.getQueryParameter("port"), c = u.getQueryParameter("c");
        if (ip == null || c == null) return;
        pendingTv = ip + "|" + (port == null ? String.valueOf(RemoteService.PORT) : port) + "|" + c;
    }

    private void signInTv() {
        final String[] p = pendingTv.split("\\|");
        new Thread(() -> {
            String msg;
            boolean ok = false;
            try {
                HashMap<String, String> m = new HashMap<>();
                m.put("c", p[2]);
                m.put("email", Auth.email());
                m.put("name", Auth.name());
                m.put("photo", Store.getStr("au_photo", ""));
                m.put("fb_uid", Cloud.uid());                         // the TV joins the same cloud account
                m.put("fb_refresh", Cloud.refreshToken());
                org.json.JSONObject r = TvClient.post(p[0], "/tvlogin", m);
                ok = r.optBoolean("ok", false);
                msg = r.optString("msg", ok ? T.t("The TV is signed in") : T.t("The TV refused it — scan the new code"));
            } catch (Exception e) {
                msg = T.t("Can't reach the TV — is the phone on the same Wi-Fi?");
            }
            final String fm = msg;
            final boolean fok = ok;
            main.post(() -> {
                toast((fok ? "✅ " : "⚠ ") + fm);
                pendingTv = null;
                finish();
            });
        }).start();
    }

    // ------------------------------------------------------------------ account / admin menu

    /** Account tile: who is signed in, sign out, and the admin tools. */
    static void accountMenu(final Activity a) {
        if (!Auth.signedIn()) {
            open(a);
            return;
        }
        final boolean admin = Auth.isAdmin();
        java.util.List<String> l = new java.util.ArrayList<>();
        l.add("🔑  " + T.t("My scrambled e-mail (for the admin)"));
        if (admin) {
            l.add("📡  " + T.t("Admin panel: users, broadcast, scroller"));
            l.add("🛡  " + T.t("Admin: scramble an e-mail"));
            l.add("✏  " + T.t("Admin: edit access.json on GitHub"));
        }
        l.add("☁  " + T.t("ZH Play cloud status"));
        l.add("🔄  " + T.t("Reload admin settings"));
        l.add("🚪  " + T.t("Sign out"));
        new UiDialog(a).setTitle("👤  " + Auth.name() + (admin ? "  · admin" : ""))
                .setItems(l.toArray(new String[0]), (d, w) -> {
                    String c = l.get(w);
                    if (c.startsWith("🔑")) showHash(a, Auth.email());
                    else if (c.startsWith("📡")) AdminActivity.open(a);
                    else if (c.startsWith("🛡")) {
                        final EditText e = new EditText(a);
                        e.setHint("name@gmail.com");
                        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
                        new UiDialog(a).setTitle(T.t("Scramble an e-mail")).setView(e)
                                .setPositiveButton(T.t("Scramble"), (d2, w2) -> showHash(a, e.getText().toString()))
                                .setNegativeButton(T.t("Cancel"), null).show();
                    } else if (c.startsWith("✏")) {
                        try {
                            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(Auth.EDIT_URL)));
                        } catch (Exception e) {
                            a.startActivity(new Intent(a, BrowserActivity.class).putExtra(BrowserActivity.EXTRA_URL, Auth.EDIT_URL));
                        }
                    } else if (c.startsWith("☁")) {
                        String st = (Cloud.enabled() ? "✓ " + T.t("Set up by the admin") : "✗ " + T.t("Not set up (Firebase keys missing in access.json)"))
                                + "\n" + (Cloud.signedIn() ? "✓ " + T.t("Connected") + " (" + Cloud.uid().substring(0, Math.min(6, Cloud.uid().length())) + "…)" : "✗ " + T.t("Not connected"))
                                + "\n" + T.t("This device") + ": " + Cloud.deviceId()
                                + (Ui.isTv(a) ? "\n" + T.t("Online since") + ": " + (Store.getLong("fb_beat", 0) > 0
                                ? java.text.DateFormat.getTimeInstance().format(new java.util.Date(Store.getLong("fb_beat", 0))) : "—") : "")
                                + (Store.getStr("fb_err", "").isEmpty() ? "" : "\n⚠ " + Store.getStr("fb_err", ""));
                        UiDialog cd = new UiDialog(a);
                        cd.setTitle("☁  " + T.t("ZH Play cloud")).setMessage(st).setPositiveButton(T.t("OK"), null);
                        if (Cloud.enabled() && !Cloud.signedIn() && !Ui.isTv(a))
                            cd.setNeutralButton(T.t("Connect"), (d4, w4) -> a.startActivity(new Intent(a, AuthActivity.class).putExtra("cloud", true)));
                        cd.show();
                    } else if (c.startsWith("🔄")) {
                        Auth.refresh(a, true, () -> UiKit.makeToast(a, T.t("Admin settings reloaded"), Toast.LENGTH_SHORT).show());
                    } else {
                        new UiDialog(a).setTitle(T.t("Sign out?")).setMessage(Auth.email())
                                .setPositiveButton(T.t("Sign out"), (d3, w3) -> signOut(a, () -> open(a)))
                                .setNegativeButton(T.t("Cancel"), null).show();
                    }
                }).show();
    }

    private static void showHash(Activity a, String email) {
        if (email == null || !email.contains("@")) return;
        final String h = Auth.hash(email);
        new UiDialog(a).setTitle(email.trim().toLowerCase(java.util.Locale.ROOT))
                .setMessage(h + "\n\n" + T.t("Put this (with quotes) in admins, allowed or blocked in access.json."))
                .setPositiveButton(T.t("Copy"), (d, w) -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("hash", h));
                    UiKit.makeToast(a, T.t("Copied"), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(T.t("Close"), null).show();
    }
}
