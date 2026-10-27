package com.mytv.player;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The phone remote as a real Android screen (fits every phone, normal keyboard, no zooming).
 * Talks to the TV through the same API the browser page uses.
 */
public class NativeRemoteActivity extends AppCompatActivity {
    public static final String EXTRA_IP = "ip", EXTRA_NAME = "name";
    private static final int PICK_MEDIA = 21, PICK_TORRENT = 22, PICK_SUB = 23;

    private String ip, tvName;
    private boolean castHd, castSound = true;

    /** Cast the whole phone screen (and sound on Android 10+) to this TV. */
    private void castScreen() {
        if (CastScreenService.running) {
            new UiDialog(this).setTitle(T.t("Cast screen")).setMessage(CastScreenService.status)
                    .setPositiveButton(T.t("Stop casting"), (d, w) -> CastScreenService.stop(this))
                    .setNegativeButton(T.t("Close"), null).show();
            return;
        }
        final boolean[] sel = {Store.getBool("cast_hd", false), Store.getBool("cast_sound", true)};
        String[] o = {T.t("Full HD (1080p) — needs strong Wi-Fi"),
                T.t("Send the phone's sound") + (android.os.Build.VERSION.SDK_INT < 29 ? " (" + T.t("Android 10+ only") + ")" : "")};
        new UiDialog(this).setTitle(T.t("Cast screen"))
                .setMultiChoiceItems(o, sel, (d, w, c) -> sel[w] = c)
                .setPositiveButton(T.t("Start"), (d, w) -> {
                    castHd = sel[0];
                    castSound = sel[1];
                    Store.putBool("cast_hd", castHd);
                    Store.putBool("cast_sound", castSound);
                    if (castSound && android.os.Build.VERSION.SDK_INT >= 29
                            && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 89);   // then the capture prompt
                        return;
                    }
                    askCapture();
                })
                .setNegativeButton(T.t("Cancel"), null).show();
    }

    private void askCapture() {
        android.media.projection.MediaProjectionManager mpm =
                (android.media.projection.MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), 88);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        super.onRequestPermissionsResult(req, p, r);
        if (req == 89) askCapture();       // with or without sound permission (no sound if refused)
    }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private JSONObject S = new JSONObject(), opts = null;
    private boolean polling, resumed, askingPin;
    private String pendingMode = "now";

    private FrameLayout content;
    private final View[] pages = new View[5];
    private final Button[] navBtns = new Button[5];
    private int curTab = 0;

    // live views
    private Button otherBtn, nightBtn, levelBtn, afrBtn;
    private TextView accessHint, brLabel;
    private SeekBar bright;
    private Button adbBtn, qualBtn;
    private long brTouch;
    private TextView npTitle, npSub, tLeft, tRight, volLabel, torLine, adLabel, sdLabel, zoomLabel, devLine;
    private SeekBar seek, vol, zoom;
    private ImageView playBtn;
    private Button repBtn, shufBtn, audioBtn, subBtn, fitBtn, aspBtn, sizeBtn, encBtn, presetBtn,
            rateBtn, sleepBtn, hwBtn, cacheBtn;
    private CheckBox subBg, eqOn;
    private EqView eq;
    private LinearLayout queueBox, appsBox, cwBox, listsBox;
    private EditText qSearch;
    private long seekTouch, volTouch, zoomTouch, eqTouch;
    private String queueSig = "", cwSig = "";

    // =====================================================================
    @Override
    protected void onCreate(Bundle b) {
        Ui.apply(this);
        super.onCreate(b);
        ip = getIntent().getStringExtra(EXTRA_IP);
        tvName = getIntent().getStringExtra(EXTRA_NAME);
        if (tvName == null) tvName = "TV";

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Ui.background());

        // ---- top bar
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(10), dp(10), dp(10), dp(8));
        top.addView(UiKit.roundButton(this, "house", 42, false, v -> Ui.goHome(this, false)));
        LinearLayout tt = new LinearLayout(this);
        tt.setOrientation(LinearLayout.VERTICAL);
        tt.setPadding(dp(10), 0, dp(6), 0);
        TextView h = Ui.text(this, 17, Ui.TEXT, true);
        h.setText("ZH Play");
        h.setSingleLine(true);
        devLine = Ui.text(this, 12, Ui.DIM, false);
        devLine.setSingleLine(true);
        devLine.setEllipsize(TextUtils.TruncateAt.END);
        devLine.setText(tvName + " · " + ip);
        tt.addView(h);
        tt.addView(devLine);
        top.addView(tt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        top.addView(UiKit.roundButton(this, "tv", 42, false, v -> {
            startActivity(new Intent(this, ConnectActivity.class).putExtra("no_auto", true)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
            finish();
        }));
        top.addView(UiKit.roundButton(this, Ui.dark() ? "sun" : "moon", 42, false, v -> {
            Store.putStr("theme", Ui.dark() ? "light" : "dark");
            recreate();
        }));
        root.addView(top);

        top.setBackground(Ui.card(this, Ui.BAR, 0, 0));
        FrameLayout stage = new FrameLayout(this);
        root.addView(stage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        stage.addView(scroll(buildMain()), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        // panels that open from the board
        pages[0] = scroll(buildQueuePage());
        pages[1] = scroll(buildTracks());
        pages[2] = scroll(buildPicture());
        pages[3] = scroll(buildCast());
        pages[4] = scroll(buildLibrary());
        appsPage = scroll(buildAppsPage());
        screenPage = buildScreenPage();
        buildSheet(stage);
        setContentView(root);
        UiKit.splash(this);
        loadApps();
        loadHistory();
        poll();
        RemoteService.start(this);
        TvControlService.start(this, ip, tvName);
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    private Button icon(String t, View.OnClickListener l) {
        Button b = Ui.iconButton(this, t, 42, false);
        b.setTextColor(Ui.TEXT);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(Ui.CARD);
        g.setStroke(dp(1), Ui.STROKE);
        b.setBackground(g);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(42), dp(42));
        lp.setMargins(dp(3), 0, dp(3), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private View scroll(View inner) {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setClipToPadding(false);
        sv.addView(inner);
        return sv;
    }

    private void showTab(int k) {
        if (k == 0) {
            closeSheet();
            return;
        }
        String[] titles = {"", T.t("Audio & subtitles"), T.t("Picture & sound"), T.t("Cast"), T.t("Playlists")};
        String[] icons = {"", "captions", "sliders-horizontal", "cast", "list-video"};
        openSheet(pages[k], titles[k], icons[k]);
        if (k == 4) loadLists();
    }

    private void hideKeyboard() {
        View f = getCurrentFocus();
        if (f != null) {
            InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (im != null) im.hideSoftInputFromWindow(f.getWindowToken(), 0);
        }
    }

    // =====================================================================
    // small builders
    // =====================================================================
    private LinearLayout page() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(12), dp(4), dp(12), dp(16));
        return l;
    }

    private LinearLayout card(LinearLayout parent, String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(14));
        c.setBackground(Ui.card(this, Ui.SOLID, Ui.STROKE, 18));
        if (title != null && !UiKit.noEmoji(title).isEmpty()) {
            TextView t = Ui.text(this, 11, Ui.DIM, true);
            t.setText(UiKit.noEmoji(title).toUpperCase(Locale.ROOT));
            t.setLetterSpacing(0.12f);
            t.setPadding(0, 0, 0, dp(8));
            c.addView(t);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, dp(6));
        parent.addView(c, lp);
        return c;
    }

    private Button btn(String label, boolean primary, View.OnClickListener l) {
        String clean = UiKit.noEmoji(label);
        Button b = Ui.button(this, clean.isEmpty() ? label : clean, primary);
        b.setTextSize(14);
        b.setPadding(dp(8), dp(6), dp(8), dp(6));
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);
        b.setOnClickListener(l);
        return b;
    }

    /** Equal-width row of buttons (always fits the screen). */
    private LinearLayout row(LinearLayout parent, Button... bs) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER);
        for (Button b : bs) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1);
            lp.setMargins(dp(3), dp(4), dp(3), dp(4));
            r.addView(b, lp);
        }
        parent.addView(r, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return r;
    }

    private Button full(LinearLayout parent, Button b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        lp.setMargins(0, dp(4), 0, dp(4));
        parent.addView(b, lp);
        return b;
    }

    private EditText field(LinearLayout parent, String hint, boolean multi) {
        EditText e = new EditText(this);
        e.setHint(UiKit.noEmoji(hint));
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.DIM);
        e.setTextSize(15);
        e.setPadding(dp(14), dp(10), dp(14), dp(10));
        e.setBackground(Ui.card(this, Ui.FIELD, Ui.STROKE, 14));
        if (multi) {
            e.setMinLines(2);
            e.setMaxLines(4);
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_VARIATION_URI);
        } else {
            e.setSingleLine(true);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(4));
        parent.addView(e, lp);
        return e;
    }

    private TextView label(LinearLayout parent, String text) {
        TextView t = Ui.text(this, 13, Ui.DIM, false);
        t.setText(UiKit.noEmoji(text));
        t.setPadding(dp(2), dp(6), 0, dp(2));
        parent.addView(t);
        return t;
    }

    private SeekBar bar(LinearLayout parent, int max) {
        SeekBar s = new SeekBar(this);
        s.setMax(max);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36));
        parent.addView(s, lp);
        return s;
    }

    private void toast(String m) {
        if (m != null && !m.isEmpty()) UiKit.makeToast(this, m, Toast.LENGTH_SHORT).show();
    }

    // =====================================================================
    // talking to the TV
    // =====================================================================
    private interface Done {
        void on(JSONObject r);
    }

    private void call(final String path, final Map<String, String> p, final Done done) {
        io.execute(() -> {
            try {
                final JSONObject r = TvClient.post(ip, path, p == null ? new HashMap<String, String>() : p);
                main.post(() -> {
                    if (done != null) done.on(r);
                    else if (r.has("msg")) toast(r.optString("msg"));
                });
            } catch (TvClient.AuthNeeded e) {
                main.post(this::askPin);
            } catch (Exception e) {
                main.post(() -> devLine.setText(UiKit.noEmoji("⚠ " + T.t("Cannot reach the TV") + " · " + ip)));
            }
        });
    }

    private static Map<String, String> m(String... kv) {
        Map<String, String> p = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) p.put(kv[i], kv[i + 1]);
        return p;
    }

    private void cmd(String c, String v) {
        cmd(c, v, "");
    }

    private void cmd(String c, String v, String b) {
        call("/cmd", m("c", c, "v", v, "b", b), r -> {
            toast(r.optString("msg"));
            poll();
        });
    }

    private KeyChannel keys;

    /** Keys go over the always-open key line (instant); web request only if the TV is too old for it. */
    private void key(String k) {
        line().key(k);
    }

    private KeyChannel line() {
        if (keys == null) keys = new KeyChannel(ip, line -> {
            try {
                if (line.startsWith("k ")) TvClient.post(ip, "/key", m("k", line.substring(2)));
                else if (line.startsWith("t ")) TvClient.post(ip, "/type", m("text", java.net.URLDecoder.decode(line.substring(2), "UTF-8")));
            } catch (Exception ignored) {
            }
        });
        return keys;
    }

    private void typeLive(String t) {
        line().text(t);
    }

    private void askPin() {
        if (askingPin || isFinishing()) return;
        askingPin = true;
        final EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setHint("1234");
        e.setGravity(Gravity.CENTER);
        e.setTextSize(26);
        new UiDialog(this).setTitle("🔐  " + T.t("Enter the PIN shown on the TV"))
                .setView(e)
                .setCancelable(false)
                .setPositiveButton(T.t("Connect"), (d, w) -> io.execute(() -> {
                    String err = TvClient.pair(ip, e.getText().toString().trim());
                    main.post(() -> {
                        askingPin = false;
                        if (err != null) {
                            toast(err);
                            askPin();
                        } else {
                            toast("✅");
                            showTab(curTab);
                        }
                    });
                }))
                .setNegativeButton(T.t("Cancel"), (d, w) -> {
                    askingPin = false;
                    finish();
                }).show();
        e.requestFocus();
    }

    private final Runnable tick = new Runnable() {
        public void run() {
            if (!resumed) return;
            if (curTab != 4) poll();
            main.postDelayed(this, 1500);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        main.removeCallbacks(tick);
        main.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (keys != null) keys.close();
        io.shutdownNow();
    }

    @Override
    public void onBackPressed() {
        if (sheetOpen) closeSheet();
        else super.onBackPressed();
    }

    private void poll() {
        if (polling) return;
        polling = true;
        io.execute(() -> {
            try {
                if (opts == null) {
                    final JSONObject o = TvClient.post(ip, "/options", new HashMap<String, String>());
                    main.post(() -> opts = o);
                }
                final JSONObject st = TvClient.post(ip, "/state", new HashMap<String, String>());
                main.post(() -> {
                    polling = false;
                    S = st;
                    render();
                });
            } catch (TvClient.AuthNeeded e) {
                main.post(() -> {
                    polling = false;
                    askPin();
                });
            } catch (Exception e) {
                main.post(() -> {
                    polling = false;
                    devLine.setText(UiKit.noEmoji("⚠ " + T.t("Cannot reach the TV") + " · " + ip));
                });
            }
        });
    }

    // =====================================================================
    // TAB 1: remote
    // =====================================================================
    // =====================================================================
    // MAIN SCREEN: mini player · remote · board
    // =====================================================================
    private LinearLayout expandBox;
    private ImageView expandBtn, artIcon;
    private UiKit.PillSlider volSlider, brSlider;
    private FrameLayout padBox;
    private View dpadView, touchView;
    private Button padD, padT;
    private TextView queueSub, shareSub;

    private View buildMain() {
        LinearLayout p = page();

        // ---- live typing: appears when a text box is open on the TV (like the Google TV remote)
        kbdCard = card(p, null);
        LinearLayout kh = new LinearLayout(this);
        kh.setGravity(Gravity.CENTER_VERTICAL);
        kh.addView(UiKit.icon(this, "keyboard", 20, Ui.ACCENT), new LinearLayout.LayoutParams(dp(28), dp(28)));
        TextView kt = Ui.text(this, 14, Ui.TEXT, true);
        kt.setText(T.t("Type on the TV"));
        kt.setPadding(dp(8), 0, 0, 0);
        kh.addView(kt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        kh.addView(UiKit.roundButton(this, "check", 40, true, v -> sendKey("enter")));
        ImageView kx = UiKit.closeButton(this, v -> {
            kbdDismissed = System.currentTimeMillis();
            showKbd(false);
            sendKey("back");
        });
        ((LinearLayout.LayoutParams) kx.getLayoutParams()).setMargins(dp(8), 0, 0, 0);
        kh.addView(kx);
        kbdCard.addView(kh);
        liveBox = field(kbdCard, T.t("Letters appear on the TV as you type"), false);
        liveBox.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
        liveBox.setOnEditorActionListener((v, a, e) -> {
            sendKey("enter");
            return true;
        });
        liveBox.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence c, int a1, int b1, int d) { }
            public void onTextChanged(CharSequence c, int a1, int b1, int d) { }
            public void afterTextChanged(Editable e) {
                if (liveMute) return;
                final String now = e.toString(), was = liveSent;
                liveSent = now;
                kbdTyped = System.currentTimeMillis();
                int same = 0;
                while (same < was.length() && same < now.length() && was.charAt(same) == now.charAt(same)) same++;
                for (int i = 0; i < was.length() - same; i++) key("del");
                if (now.length() > same) typeLive(now.substring(same));
            }
        });
        kbdCard.setVisibility(View.GONE);

        // ---- mini player (tap the arrow to open / close the timeline)
        LinearLayout np = card(p, null);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout art = new FrameLayout(this);
        art.setBackground(Ui.gradient(this, 16));
        artIcon = UiKit.icon(this, "clapperboard", 24, Color.WHITE);
        art.addView(artIcon, new FrameLayout.LayoutParams(dp(52), dp(52), Gravity.CENTER));
        head.addView(art, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout tcol = new LinearLayout(this);
        tcol.setOrientation(LinearLayout.VERTICAL);
        tcol.setPadding(dp(12), 0, dp(8), 0);
        npTitle = Ui.text(this, 16, Ui.TEXT, true);
        npTitle.setSingleLine(true);
        npTitle.setEllipsize(TextUtils.TruncateAt.END);
        npSub = Ui.text(this, 12, Ui.DIM, false);
        npSub.setSingleLine(true);
        npSub.setEllipsize(TextUtils.TruncateAt.END);
        tcol.addView(npTitle);
        tcol.addView(npSub);
        head.addView(tcol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        playBtn = UiKit.roundButton(this, "play", 52, true, v -> cmd("toggle", ""));
        head.addView(playBtn);
        expandBtn = UiKit.roundButton(this, "chevron-down", 40, false, v -> toggleExpand());
        ((LinearLayout.LayoutParams) expandBtn.getLayoutParams()).setMargins(dp(8), 0, 0, 0);
        head.addView(expandBtn);
        np.addView(head);
        head.setOnClickListener(v -> toggleExpand());

        expandBox = new LinearLayout(this);
        expandBox.setOrientation(LinearLayout.VERTICAL);
        expandBox.setPadding(0, dp(8), 0, 0);
        seek = bar(expandBox, 1000);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int v, boolean user) {
                if (user) {
                    seekTouch = System.currentTimeMillis();
                    long len = S.optLong("len");
                    if (len > 0) tLeft.setText(Net.fmtTime(len * v / 1000));
                }
            }

            public void onStartTrackingTouch(SeekBar sb) {
                seekTouch = System.currentTimeMillis();
            }

            public void onStopTrackingTouch(SeekBar sb) {
                seekTouch = System.currentTimeMillis();
                long len = S.optLong("len");
                if (len > 0) cmd("seek", String.valueOf(len * sb.getProgress() / 1000));
            }
        });
        LinearLayout times = new LinearLayout(this);
        tLeft = Ui.text(this, 12, Ui.DIM, false);
        tRight = Ui.text(this, 12, Ui.DIM, false);
        times.addView(tLeft, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        times.addView(tRight);
        expandBox.addView(times);
        expandBox.addView(iconRow(new String[]{"skip-back", "rotate-ccw", "rotate-cw", "skip-forward", "circle-stop"},
                new Runnable[]{() -> cmd("prev", ""), () -> cmd("jump", "-10000"), () -> cmd("jump", "10000"),
                        () -> cmd("next", ""), () -> cmd("stop", "")}, 48));
        expandBox.addView(iconRow(new String[]{"bookmark-plus", "bookmark", "camera", "audio-lines", "rotate-ccw"},
                new Runnable[]{() -> cmd("bmadd", ""), this::bookmarkList, () -> cmd("screenshot", ""),
                        () -> cmd("audioonly", ""), () -> cmd("jump", "-30000")}, 42));
        repBtn = btn("", false, v -> cmd("repeat", ""));
        shufBtn = btn("", false, v -> cmd("shuffle", ""));
        repBtn.setCompoundDrawablesWithIntrinsicBounds(new UiKit.Icon(this, "repeat", 16, Ui.TEXT), null, null, null);
        shufBtn.setCompoundDrawablesWithIntrinsicBounds(new UiKit.Icon(this, "shuffle", 16, Ui.TEXT), null, null, null);
        row(expandBox, repBtn, shufBtn);
        expandBox.setVisibility(Store.getBool("np_open", false) ? View.VISIBLE : View.GONE);
        np.addView(expandBox);
        otherBtn = full(np, btn(T.t("Show other apps' videos on the remote"), false,
                v -> call("/otherallow", null, r -> toast(r.optString("msg")))));
        otherBtn.setCompoundDrawablesWithIntrinsicBounds(new UiKit.Icon(this, "monitor-play", 18, Ui.TEXT), null, null, null);
        otherBtn.setVisibility(View.GONE);
        torLine = Ui.text(this, 12, Ui.DIM, false);
        torLine.setVisibility(View.GONE);
        np.addView(torLine);

        // ---- remote: D-pad / touchpad, volume + brightness sliders, keys
        LinearLayout rc = card(p, null);
        LinearLayout rh = new LinearLayout(this);
        rh.setGravity(Gravity.CENTER_VERTICAL);
        TextView rt = Ui.text(this, 11, Ui.DIM, true);
        rt.setText(T.t("Remote").toUpperCase(Locale.ROOT));
        rt.setLetterSpacing(0.12f);
        rh.addView(rt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        padD = btn("D-pad", true, v -> setPadMode(0));
        padT = btn("Touchpad", false, v -> setPadMode(1));
        rh.addView(padD, new LinearLayout.LayoutParams(dp(96), dp(36)));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(dp(110), dp(36));
        tlp.setMargins(dp(6), 0, 0, 0);
        rh.addView(padT, tlp);
        rc.addView(rh);

        LinearLayout body = new LinearLayout(this);
        body.setGravity(Gravity.CENTER);
        body.setPadding(0, dp(12), 0, dp(6));
        volSlider = new UiKit.PillSlider(this, "volume-2", 0, 200, true);
        volSlider.setListener((v, done) -> {
            volTouch = System.currentTimeMillis();
            if (done) cmd("vol", String.valueOf(v));
        });
        body.addView(volSlider, new LinearLayout.LayoutParams(dp(54), dp(236)));
        padBox = new FrameLayout(this);
        int pw = Math.min(dp(236), (int) (getResources().getDisplayMetrics().widthPixels * 0.56f));
        dpadView = buildDpad(pw);
        touchView = new UiKit.TouchPad(this, this::key);
        padBox.addView(dpadView, new FrameLayout.LayoutParams(pw, pw, Gravity.CENTER));
        padBox.addView(touchView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, pw, Gravity.CENTER));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, pw, 1);
        plp.setMargins(dp(10), 0, dp(10), 0);
        body.addView(padBox, plp);
        brSlider = new UiKit.PillSlider(this, "sun", 10, 150, true);
        brSlider.setListener((v, done) -> {
            brTouch = System.currentTimeMillis();
            if (done) cmd("bright", String.valueOf(v));
        });
        body.addView(brSlider, new LinearLayout.LayoutParams(dp(54), dp(236)));
        rc.addView(body);
        setPadMode(Store.getInt("padmode", 0));

        rc.addView(iconRow(new String[]{"arrow-left", "house", "layers", "menu", "power"},
                new Runnable[]{() -> key("back"), () -> key("home"), () -> key("recents"), () -> cmd("menu", ""), this::powerMenu}, 50));
        rc.addView(iconRow(new String[]{"volume-1", "volume-x", "volume-2", "circle-play", "keyboard"},
                new Runnable[]{() -> key("vol_down"), () -> key("vol_mute"), () -> key("vol_up"),
                        () -> key("media_playpause"), this::typeDialog}, 44));
        Button kbBtn = btn(T.t("Keyboard"), false, v -> {
            kbdDismissed = 0;
            showKbd(true);
        });
        kbBtn.setCompoundDrawablesWithIntrinsicBounds(new UiKit.Icon(this, "keyboard", 20, Ui.TEXT), null, null, null);
        voiceBtn = btn("", true, v -> voice());
        voiceBtn.setOnLongClickListener(v -> {
            voiceLangPick();
            return true;
        });
        updateVoiceBtn();
        row(rc, kbBtn, voiceBtn);
        volKeysBtn = btn("", false, v -> {
            Store.putInt("phone_vol", (Store.getInt("phone_vol", 0) + 1) % 3);
            updateVolKeysBtn();
        });
        volKeysBtn.setTextSize(13);
        full(rc, volKeysBtn);
        updateVolKeysBtn();
        accessHint = Ui.text(this, 12, Ui.DIM, false);
        accessHint.setText(T.t("To control other apps, turn on TV Settings > Accessibility > ZH Play"));
        accessHint.setVisibility(View.GONE);
        rc.addView(accessHint);
        adbBtn = full(rc, btn(T.t("Full remote for every app (ADB)"), true, v -> new UiDialog(this)
                .setTitle(T.t("Full remote for every app (ADB)"))
                .setMessage(T.t("Makes the phone remote work in EVERY app (YouTube, Netflix, settings) by pressing real keys.")
                        + "\n\n1. TV: Settings → Device Preferences → About → press “Build” 7 times."
                        + "\n2. Developer options → USB / Network debugging ON."
                        + "\n3. Press Connect, then tick “Always allow” on the TV.")
                .setPositiveButton(T.t("Connect"), (dd, ww) -> cmd("adbsetup", ""))
                .setNegativeButton(T.t("Cancel"), null).show()));
        adbBtn.setVisibility(View.GONE);

        // ---- board: one tile per feature, each opens its own panel
        TextView bl = Ui.text(this, 11, Ui.DIM, true);
        bl.setText(T.t("Board").toUpperCase(Locale.ROOT));
        bl.setLetterSpacing(0.12f);
        bl.setPadding(dp(6), dp(14), 0, dp(4));
        p.addView(bl);
        View tQueue = UiKit.tile(this, "list-video", T.t("Channels & queue"), "—", 0xFF8B5CF6, v -> openSheet(pages[0], T.t("Channels & queue"), "list-video"));
        queueSub = (TextView) tQueue.findViewWithTag("sub");
        View tShare = UiKit.tile(this, "smartphone", T.t("Share this phone"), "", 0xFF14B8A6, v -> shareDialog());
        shareSub = (TextView) tShare.findViewWithTag("sub");
        p.addView(UiKit.grid(this, 3,
                UiKit.tile(this, "sparkles", T.t("AI assistant"), T.t("Tamil · English · Tanglish"), 0xFF8B5CF6, v -> openAi()),
                UiKit.tile(this, "monitor", T.t("Cast screen"), T.t("show this phone on the TV"), 0xFF0EA5E9, v -> castScreen()),
                UiKit.tile(this, "disc-3", T.t("DJ mixer"), T.t("two decks, sync, EQ"), 0xFFF59E0B,
                        v -> startActivity(new Intent(this, DjActivity.class))),
                UiKit.tile(this, "scissors", T.t("Song editor"), T.t("tracks, cut, fades"), 0xFF10B981,
                        v -> startActivity(new Intent(this, EditorActivity.class))),
                UiKit.tile(this, "wand-sparkles", T.t("Features"), T.t("EQ, cutter, karaoke…"), 0xFFF43F5E,
                        v -> startActivity(new Intent(this, ToolsActivity.class))),
                UiKit.tile(this, "film", T.t("Videos"), T.t("on this phone"), 0xFF0EA5E9,
                        v -> startActivity(new Intent(this, PhoneLibraryActivity.class))),
                UiKit.tile(this, "list-music", T.t("Music"), T.t("on this phone"), 0xFFD946EF,
                        v -> startActivity(new Intent(this, PhoneLibraryActivity.class).putExtra(PhoneLibraryActivity.EXTRA_AUDIO, true))),
                UiKit.tile(this, "monitor-play", T.t("Live TV screen"), T.t("see the TV here"), 0xFFEC4899, v -> openScreen()),
                tQueue,
                UiKit.tile(this, "layout-grid", T.t("Apps"), "Netflix, YouTube…", 0xFFF97316, v -> openSheet(appsPage, T.t("Apps"), "layout-grid")),
                UiKit.tile(this, "cast", T.t("Cast"), T.t("phone videos, links"), 0xFF3B82F6, v -> showTab(3)),
                UiKit.tile(this, "captions", T.t("Audio & subtitles"), T.t("tracks, delay, online"), 0xFF22C55E, v -> showTab(1)),
                UiKit.tile(this, "sliders-horizontal", T.t("Picture & sound"), T.t("fit, EQ, dialogue"), 0xFFEAB308, v -> showTab(2)),
                UiKit.tile(this, "library", T.t("Playlists"), T.t("IPTV, saved links"), 0xFF6366F1, v -> showTab(4)),
                UiKit.tile(this, "gauge", T.t("My TV"), T.t("storage, speed"), 0xFF06B6D4, v -> myTv()),
                tShare,
                UiKit.tile(this, "power", T.t("Power"), T.t("sleep, restart"), 0xFFEF4444, v -> powerMenu()),
                UiKit.tile(this, "palette", T.t("Colours"), T.t("theme & palette"), 0xFFD946EF, v -> Palette.show(this)),
                UiKit.tile(this, "tv", T.t("Switch TV"), tvName, 0xFF64748B, v -> {
                    startActivity(new Intent(this, ConnectActivity.class).putExtra("no_auto", true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
                    finish();
                })));
        updateShareSub();
        return p;
    }

    /** Row of round Lucide buttons, evenly spread. */
    private LinearLayout iconRow(String[] icons, final Runnable[] acts, int size) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER);
        r.setPadding(0, dp(6), 0, dp(4));
        for (int i = 0; i < icons.length; i++) {
            final int k = i;
            ImageView b = UiKit.roundButton(this, icons[i], size, false, v -> acts[k].run());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(size), dp(size));
            lp.setMargins(dp(6), 0, dp(6), 0);
            r.addView(b, lp);
        }
        return r;
    }

    private View buildDpad(int w) {
        FrameLayout pad = new FrameLayout(this);
        GradientDrawable circ = new GradientDrawable();
        circ.setShape(GradientDrawable.OVAL);
        circ.setColor(Ui.dark() ? 0x14FFFFFF : 0x0A1B1530);
        circ.setStroke(dp(1), Ui.STROKE);
        pad.setBackground(circ);
        int a = (int) (w / 4.2f);
        String[][] arrows = {{"chevron-up", "up"}, {"chevron-down", "down"}, {"chevron-left", "left"}, {"chevron-right", "right"}};
        int[] grav = {Gravity.TOP | Gravity.CENTER_HORIZONTAL, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL,
                Gravity.START | Gravity.CENTER_VERTICAL, Gravity.END | Gravity.CENTER_VERTICAL};
        for (int i = 0; i < 4; i++) {
            final String k = arrows[i][1];
            ImageView b = UiKit.roundButton(this, arrows[i][0], Math.round(a / getResources().getDisplayMetrics().density), false, v -> key(k));
            b.setBackground(new GradientDrawable());
            holdRepeat(b, k);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(a, a, grav[i]);
            lp.setMargins(dp(6), dp(6), dp(6), dp(6));
            pad.addView(b, lp);
        }
        int ok = (int) (w * 0.38f);
        ImageView okb = UiKit.roundButton(this, "circle-play", Math.round(ok / getResources().getDisplayMetrics().density), true, v -> key("ok"));
        okb.setImageDrawable(null);
        pad.addView(okb, new FrameLayout.LayoutParams(ok, ok, Gravity.CENTER));
        TextView okt = Ui.text(this, 18, Color.WHITE, true);
        okt.setText("OK");
        okt.setGravity(Gravity.CENTER);
        okt.setClickable(false);
        pad.addView(okt, new FrameLayout.LayoutParams(ok, ok, Gravity.CENTER));
        return pad;
    }

    private Button volKeysBtn;

    private void updateVolKeysBtn() {
        int m = Store.getInt("phone_vol", 0);
        volKeysBtn.setText(T.t("Phone volume buttons") + ": " + T.t(m == 0 ? "TV volume" : m == 1 ? "Player volume" : "Off"));
        volKeysBtn.setCompoundDrawablesWithIntrinsicBounds(new UiKit.Icon(this, m == 2 ? "volume-x" : "volume-2", 16, Ui.TEXT), null, null, null);
    }

    /** The phone's own volume buttons control the TV while the remote is open. */
    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        int code = e.getKeyCode(), m = Store.getInt("phone_vol", 0);
        if (m != 2 && (code == android.view.KeyEvent.KEYCODE_VOLUME_UP || code == android.view.KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (e.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                boolean up = code == android.view.KeyEvent.KEYCODE_VOLUME_UP;
                if (m == 0) key(up ? "vol_up" : "vol_down");
                else {
                    int v = Math.max(0, Math.min(200, S.optInt("vol", 100) + (up ? 10 : -10)));
                    try {
                        S.put("vol", v);
                    } catch (Exception ignored) {
                    }
                    volSlider.setValue(v);
                    cmd("vol", String.valueOf(v));
                }
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    // ---- voice search: phone speech recognition (English / Tamil / Hindi)
    private Button voiceBtn;
    private static final String[] VOICE_LANG = {"en-IN", "ta-IN", "hi-IN"};
    private static final String[] VOICE_NAME = {"English", "தமிழ்", "हिन्दी"};

    private int voiceIdx() {
        String l = Store.getStr("voice_lang", "en-IN");
        for (int i = 0; i < VOICE_LANG.length; i++) if (VOICE_LANG[i].equals(l)) return i;
        return 0;
    }

    private void updateVoiceBtn() {
        voiceBtn.setText(T.t("Voice") + " · " + VOICE_NAME[voiceIdx()]);
        voiceBtn.setCompoundDrawablesWithIntrinsicBounds(new UiKit.Icon(this, "mic", 20, Color.WHITE), null, null, null);
    }

    private void voiceLangPick() {
        new UiDialog(this).setTitle(T.t("Voice language"))
                .setSingleChoiceItems(VOICE_NAME, voiceIdx(), (d, w) -> {
                    Store.putStr("voice_lang", VOICE_LANG[w]);
                    updateVoiceBtn();
                    d.dismiss();
                }).show();
    }

    private void voice() {
        Intent i = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, VOICE_LANG[voiceIdx()])
                .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, T.t("Say what to search or type on the TV"));
        try {
            startActivityForResult(i, 77);
        } catch (Exception e) {
            toast(T.t("Voice typing needs the Google app on this phone"));
        }
    }

    /** What was said: into the TV's text box if one is open, else choose where to search. */
    private void onVoice(final String said) {
        if (said == null || said.trim().isEmpty()) return;
        if (kbdShown || S.optBoolean("kbd")) {
            if (!kbdShown) showKbd(true);
            liveBox.append(said);        // live typing sends it to the TV
            return;
        }
        String[] opts = {"🚀 " + T.t("Ask the AI assistant"), "▶ " + T.t("Search on YouTube"), "🔍 " + T.t("Search the whole TV"),
                "📺 " + T.t("Play in ZH Play"), "⌨ " + T.t("Type into the TV")};
        final String[] where = {"ai", "youtube", "tv", "tcp", "type"};
        new UiDialog(this).setTitle("“" + said + "”").setItems(opts, (d, w) -> {
            if (w == 0) {
                openAi();
                aiAsk(said);
            } else call("/search", m("q", said, "where", where[w]), r -> toast(r.optString("msg")));
        }).show();
    }

    // =====================================================================
    // AI assistant panel
    // =====================================================================
    private View aiPage;
    private LinearLayout aiLog;
    private EditText aiIn;
    private TextView aiStatus;
    private final java.util.concurrent.ExecutorService aiExec = java.util.concurrent.Executors.newSingleThreadExecutor();

    private final AiBrain.Tv tvLink = (path, p) -> TvClient.post(ip, path, new java.util.HashMap<>(p));

    private View buildAiPage() {
        LinearLayout p = page();
        LinearLayout top = card(p, null);
        aiStatus = Ui.text(this, 12, Ui.DIM, false);
        top.addView(aiStatus);
        Button set = btn(T.t("AI settings"), false, v -> aiSettings());
        set.setCompoundDrawablesWithIntrinsicBounds(new UiKit.Icon(this, "settings", 16, Ui.TEXT), null, null, null);
        full(top, set);
        updateAiStatus();

        LinearLayout chips = card(p, T.t("Try"));
        String[][] ex = {{"sparkles", T.t("What did I miss?")}, {"captions", T.t("Translate subtitles to Tamil")},
                {"tv", "Sun TV podu"}, {"volume-1", "volume kammi pannu"}, {"search", "YouTube Ilaiyaraaja songs"},
                {"clock", T.t("Turn off in 30 minutes")}};
        LinearLayout row = null;
        for (int i = 0; i < ex.length; i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                chips.addView(row);
            }
            final String q = ex[i][1];
            Button b = btn(q, false, v -> aiAsk(q));
            b.setTextSize(13);
            b.setCompoundDrawablesWithIntrinsicBounds(new UiKit.Icon(this, ex[i][0], 16, Ui.ACCENT), null, null, null);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1);
            lp.setMargins(dp(3), dp(3), dp(3), dp(3));
            row.addView(b, lp);
        }

        LinearLayout box = card(p, null);
        LinearLayout in = new LinearLayout(this);
        in.setGravity(Gravity.CENTER_VERTICAL);
        aiIn = new EditText(this);
        aiIn.setHint(T.t("Ask in Tamil, English or Tanglish…"));
        aiIn.setSingleLine(true);
        aiIn.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
        aiIn.setOnEditorActionListener((v, a, e) -> {
            send();
            return true;
        });
        in.addView(aiIn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        in.addView(UiKit.roundButton(this, "mic", 44, false, v -> voice()));
        ImageView go = UiKit.roundButton(this, "chevron-right", 44, true, v -> send());
        ((LinearLayout.LayoutParams) go.getLayoutParams()).setMargins(dp(6), 0, 0, 0);
        in.addView(go);
        box.addView(in);
        aiLog = new LinearLayout(this);
        aiLog.setOrientation(LinearLayout.VERTICAL);
        box.addView(aiLog);
        return scroll(p);
    }

    private void send() {
        String q = aiIn.getText().toString().trim();
        if (q.isEmpty()) return;
        aiIn.setText("");
        hideKeyboard();
        aiAsk(q);
    }

    private void openAi() {
        if (aiPage == null) aiPage = buildAiPage();
        updateAiStatus();
        openSheet(aiPage, T.t("AI assistant"), "sparkles");
    }

    private void updateAiStatus() {
        if (aiStatus == null) return;
        aiStatus.setText(AiClient.ready() ? T.t("Using") + ": " + AiClient.model() + "  ·  " + android.net.Uri.parse(AiClient.base()).getHost()
                : T.t("No AI set up yet — simple commands (pause, next, volume, “Sun TV podu”) still work. Tap AI settings to connect your AI."));
    }

    private TextView bubble(String text, boolean me) {
        TextView t = Ui.text(this, 14, me ? Color.WHITE : Ui.TEXT, false);
        t.setText(UiKit.noEmoji(text));
        t.setPadding(dp(14), dp(10), dp(14), dp(10));
        t.setBackground(me ? Ui.gradient(this, 16) : Ui.card(this, Ui.CARD, Ui.STROKE, 16));
        t.setTextIsSelectable(!me);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = me ? Gravity.END : Gravity.START;
        lp.setMargins(me ? dp(40) : 0, dp(8), me ? 0 : dp(40), 0);
        aiLog.addView(t, 0, lp);    // newest on top, right under the input
        return t;
    }

    /** Sends a request to the brain; translation / recap show live progress. */
    private void aiAsk(final String q) {
        if (aiPage == null) aiPage = buildAiPage();
        final TextView answer = bubble("…", false);
        bubble(q, true);
        aiExec.execute(() -> {
            final String r = AiBrain.ask(q, tvLink, s -> main.post(() -> answer.setText(UiKit.noEmoji(s))));
            main.post(() -> {
                answer.setText(UiKit.noEmoji(r));
                poll();
            });
        });
    }

    private void aiSettings() {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setPadding(dp(20), dp(4), dp(20), 0);
        final Button preset = Ui.button(this, T.t("Choose a provider…"), true);
        final EditText base = new EditText(this), key = new EditText(this), model = new EditText(this);
        base.setHint("https://…/v1");
        base.setText(Store.getStr("ai_base", ""));
        key.setHint(T.t("API key (empty for Ollama / LM Studio)"));
        key.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if (!AiClient.key().isEmpty()) key.setHint(T.t("API key saved — type to replace"));
        model.setHint(T.t("Model name, e.g. gemma-3-12b-it"));
        model.setText(Store.getStr("ai_model", ""));
        preset.setOnClickListener(v -> {
            String[] n = new String[AiClient.PRESETS.length];
            for (int i = 0; i < n.length; i++) n[i] = AiClient.PRESETS[i][0];
            new UiDialog(this).setTitle(T.t("Provider")).setItems(n, (d, w) -> {
                preset.setText(AiClient.PRESETS[w][0]);
                if (!AiClient.PRESETS[w][1].isEmpty()) base.setText(AiClient.PRESETS[w][1]);
                if (!AiClient.PRESETS[w][2].isEmpty() && model.getText().toString().isEmpty()) model.setText(AiClient.PRESETS[w][2]);
            }).show();
        });
        TextView note = Ui.text(this, 12, Ui.DIM, false);
        note.setText(T.t("Works with any OpenAI-compatible service. The key is stored encrypted on this phone only and sent only to the address above. Only your request text, the current title and channel names (or subtitle lines for recap / translation) are sent."));
        b.addView(preset);
        b.addView(base);
        b.addView(key);
        b.addView(model);
        b.addView(note);
        ScrollView sv = new ScrollView(this);
        sv.addView(b);
        final androidx.appcompat.app.AlertDialog dlg = new UiDialog(this).setTitle(T.t("AI settings")).setView(sv)
                .setPositiveButton(T.t("Save"), null)
                .setNeutralButton(T.t("Test"), null)
                .setNegativeButton(T.t("Cancel"), null).create();
        Runnable save = () -> {
            Store.putStr("ai_base", base.getText().toString().trim());
            Store.putStr("ai_model", model.getText().toString().trim());
            if (!key.getText().toString().trim().isEmpty()) Store.putStr("ai_key", SecretBox.seal(key.getText().toString().trim()));
            updateAiStatus();
        };
        dlg.setOnShowListener(x -> {
            dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                save.run();
                dlg.dismiss();
            });
            dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                save.run();
                toast(T.t("Testing…"));
                aiExec.execute(() -> {
                    long t0 = System.currentTimeMillis();
                    String r;
                    try {
                        r = "✓ " + AiClient.chat("Reply with one short sentence.", "Say hello in Tamil.", 60) + "  (" + (System.currentTimeMillis() - t0) + " ms)";
                    } catch (Exception e) {
                        r = e.getMessage();
                    }
                    final String r2 = r;
                    main.post(() -> new UiDialog(this).setTitle(T.t("Test")).setMessage(r2).setPositiveButton(T.t("OK"), null).show());
                });
            });
        });
        dlg.show();
    }

    private LinearLayout kbdCard;
    private EditText liveBox;
    private String liveSent = "";
    private boolean liveMute, kbdShown;
    private long kbdTyped, kbdDismissed;
    private final java.util.concurrent.ExecutorService typeExec = java.util.concurrent.Executors.newSingleThreadExecutor();

    private void sendKey(final String k) {
        key(k);
    }

    private void showKbd(boolean on) {
        if (on == kbdShown) return;
        kbdShown = on;
        kbdCard.setVisibility(on ? View.VISIBLE : View.GONE);
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (on) {
            liveMute = true;
            liveBox.setText("");
            liveSent = "";
            liveMute = false;
            liveBox.requestFocus();
            if (imm != null) imm.showSoftInput(liveBox, 0);
            liveBox.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
        } else if (imm != null) imm.hideSoftInputFromWindow(liveBox.getWindowToken(), 0);
    }

    /** Press = one key; keep holding = repeats (fast scrolling through lists). */
    private void holdRepeat(final View b, final String k) {
        final Runnable[] rep = new Runnable[1];
        rep[0] = () -> {
            key(k);
            b.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
            main.postDelayed(rep[0], 110);
        };
        b.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    key(k);
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                    main.postDelayed(rep[0], 420);
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    main.removeCallbacks(rep[0]);
                    return true;
                default:
                    return true;
            }
        });
    }

    private void setPadMode(int m) {
        Store.putInt("padmode", m);
        dpadView.setVisibility(m == 0 ? View.VISIBLE : View.GONE);
        touchView.setVisibility(m == 1 ? View.VISIBLE : View.GONE);
        padD.setBackground(m == 0 ? Ui.gradient(this, 18) : Ui.card(this, Ui.CARD, Ui.STROKE, 18));
        padT.setBackground(m == 1 ? Ui.gradient(this, 18) : Ui.card(this, Ui.CARD, Ui.STROKE, 18));
        padD.setTextColor(m == 0 ? Color.WHITE : Ui.TEXT);
        padT.setTextColor(m == 1 ? Color.WHITE : Ui.TEXT);
    }

    private void toggleExpand() {
        boolean open = expandBox.getVisibility() != View.VISIBLE;
        Store.putBool("np_open", open);
        expandBox.setVisibility(open ? View.VISIBLE : View.GONE);
        if (open) {
            expandBox.setAlpha(0f);
            expandBox.animate().alpha(1f).setDuration(200).start();
        }
        expandBtn.animate().rotation(open ? 180 : 0).setDuration(200).start();
    }

    private void bookmarkList() {
        JSONArray bm = S.optJSONArray("bookmarks");
        if (bm == null || bm.length() == 0) {
            toast(T.t("No bookmarks yet"));
            return;
        }
        String[] n = new String[bm.length()];
        for (int i = 0; i < n.length; i++) n[i] = "⏱ " + Net.fmtTime(bm.optJSONObject(i).optLong("t"));
        new UiDialog(this).setTitle(T.t("Bookmarks")).setItems(n, (d, w) -> cmd("bmjump", String.valueOf(w))).show();
    }

    private void typeDialog() {
        final EditText e = new EditText(this);
        e.setHint(T.t("Type text on the TV (search boxes)"));
        e.setSingleLine(true);
        new UiDialog(this).setTitle(T.t("Type text on the TV (search boxes)")).setView(e)
                .setPositiveButton(T.t("Send text"), (d, w) -> {
                    if (!e.getText().toString().isEmpty()) call("/type", m("text", e.getText().toString()), r -> toast(r.optString("msg")));
                }).setNegativeButton(T.t("Cancel"), null).show();
        e.requestFocus();
    }

    private void powerMenu() {
        String[] items = {T.t("Sleep / screen off"), T.t("Wake up"), T.t("Power key"), T.t("Restart the TV"), T.t("Restart ZH Play")};
        final String[] acts = {"sleep", "wake", "power", "reboot", "restartapp"};
        new UiDialog(this).setTitle(T.t("Power")).setItems(items, (d, w) -> {
            if (acts[w].equals("reboot")) {
                new UiDialog(this).setTitle(T.t("Restart the TV") + "?")
                        .setPositiveButton(T.t("OK"), (d2, w2) -> call("/power", m("do", "reboot"), null))
                        .setNegativeButton(T.t("Cancel"), null).show();
            } else call("/power", m("do", acts[w]), null);
        }).show();
    }

    private void updateShareSub() {
        shareSub.setText(Store.getBool("share_fs", false) ? "✓ " + T.t("TV can see its videos") : T.t("let the TV play phone videos"));
    }

    /** Lets TVs on the Wi-Fi browse this phone's videos and music (Network folders on the TV). */
    private void shareDialog() {
        final boolean on = Store.getBool("share_fs", false);
        new UiDialog(this).setTitle(T.t("Share this phone"))
                .setMessage(T.t("The TV can open this phone's videos and music in “Network folders” and play them — no copying. Read-only; turn it off any time."))
                .setPositiveButton(on ? T.t("Turn off") : T.t("Turn on"), (d, w) -> {
                    if (!on) {
                        if (android.os.Build.VERSION.SDK_INT >= 33) {
                            requestPermissions(new String[]{android.Manifest.permission.READ_MEDIA_VIDEO,
                                    android.Manifest.permission.READ_MEDIA_AUDIO}, 31);
                        } else if (android.os.Build.VERSION.SDK_INT >= 23) {
                            requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, 31);
                        }
                    }
                    Store.putBool("share_fs", !on);
                    updateShareSub();
                    toast(Store.getBool("share_fs", false) ? "✓" : T.t("Sharing off"));
                }).setNegativeButton(T.t("Close"), null).show();
    }

    // =====================================================================
    // panels (bottom sheet)
    // =====================================================================
    private FrameLayout sheet, sheetBody;
    private LinearLayout sheetPanel;
    private TextView sheetTitle;
    private ImageView sheetIcon;
    private boolean sheetOpen;
    private View appsPage, screenPage, sheetView;

    private void buildSheet(FrameLayout stage) {
        sheet = new FrameLayout(this);
        sheet.setBackgroundColor(0x99000000);
        sheet.setVisibility(View.GONE);
        sheet.setOnClickListener(v -> closeSheet());
        sheetPanel = new LinearLayout(this);
        sheetPanel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.BG);
        float r = dp(26);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        sheetPanel.setBackground(bg);
        sheetPanel.setClickable(true);
        sheetPanel.setElevation(dp(16));
        View handle = new View(this);
        handle.setBackground(Ui.card(this, Ui.STROKE, 0, 3));
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(dp(44), dp(5));
        hl.gravity = Gravity.CENTER_HORIZONTAL;
        hl.setMargins(0, dp(10), 0, dp(4));
        sheetPanel.addView(handle, hl);
        LinearLayout th = new LinearLayout(this);
        th.setGravity(Gravity.CENTER_VERTICAL);
        th.setPadding(dp(16), dp(4), dp(10), dp(4));
        sheetIcon = UiKit.icon(this, "layout-grid", 22, Ui.ACCENT);
        th.addView(sheetIcon, new LinearLayout.LayoutParams(dp(32), dp(32)));
        sheetTitle = Ui.text(this, 18, Ui.TEXT, true);
        sheetTitle.setPadding(dp(8), 0, 0, 0);
        th.addView(sheetTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        th.addView(UiKit.closeButton(this, v -> closeSheet()));
        sheetPanel.addView(th);
        sheetBody = new FrameLayout(this);
        sheetPanel.addView(sheetBody, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        int h = (int) (getResources().getDisplayMetrics().heightPixels * 0.86f);
        sheet.addView(sheetPanel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h, Gravity.BOTTOM));
        stage.addView(sheet, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void openSheet(View page, String title, String icon) {
        hideKeyboard();
        if (page.getParent() != null) ((ViewGroup) page.getParent()).removeView(page);
        sheetBody.removeAllViews();
        sheetBody.addView(page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        sheetView = page;
        sheetTitle.setText(title);
        sheetIcon.setImageDrawable(new UiKit.Icon(this, icon, 22, Ui.ACCENT));
        sheet.setVisibility(View.VISIBLE);
        sheet.setAlpha(0f);
        sheet.animate().alpha(1f).setDuration(180).start();
        sheetPanel.setTranslationY(getResources().getDisplayMetrics().heightPixels);
        sheetPanel.animate().translationY(0).setDuration(280).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        sheetOpen = true;
        if (page == pages[0]) loadHistory();
        if (page == appsPage) loadApps();
        poll();
    }

    private void closeSheet() {
        if (!sheetOpen) return;
        sheetOpen = false;
        hideKeyboard();
        sheetPanel.animate().translationY(sheetPanel.getHeight()).setDuration(220).start();
        sheet.animate().alpha(0f).setDuration(220).withEndAction(() -> {
            sheet.setVisibility(View.GONE);
            sheetBody.removeAllViews();
            sheetView = null;
        }).start();
    }

    private View buildQueuePage() {
        LinearLayout p = page();
        LinearLayout cw = card(p, T.t("Continue watching"));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        cwBox = new LinearLayout(this);
        hs.addView(cwBox);
        cw.addView(hs);
        cw.setVisibility(View.GONE);
        LinearLayout qc = card(p, T.t("Channels & queue"));
        qSearch = field(qc, T.t("Search channel / name"), false);
        qSearch.setCompoundDrawablesWithIntrinsicBounds(new UiKit.Icon(this, "search", 18, Ui.DIM), null, null, null);
        qSearch.setCompoundDrawablePadding(dp(8));
        qSearch.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence c, int a1, int b1, int d) { }
            public void onTextChanged(CharSequence c, int a1, int b1, int d) { }
            public void afterTextChanged(Editable e) {
                main.removeCallbacks(qRun);
                main.postDelayed(qRun, 350);
            }
        });
        Button sv = full(qc, btn(T.t("Save") + " → " + T.t("Playlist"), false, v -> saveQueue()));
        sv.setCompoundDrawablesWithIntrinsicBounds(new UiKit.Icon(this, "download", 16, Ui.TEXT), null, null, null);
        queueBox = new LinearLayout(this);
        queueBox.setOrientation(LinearLayout.VERTICAL);
        qc.addView(queueBox);
        return p;
    }

    private View buildAppsPage() {
        LinearLayout p = page();
        LinearLayout ac = card(p, null);
        appsBox = new LinearLayout(this);
        appsBox.setOrientation(LinearLayout.VERTICAL);
        ac.addView(appsBox);
        return p;
    }

    // ---- live TV screen + touchpad under it
    private ImageView screenImg;
    private TextView screenInfo;
    private boolean screenBusy;

    private View buildScreenPage() {
        LinearLayout p = page();
        screenImg = new ImageView(this);
        screenImg.setScaleType(ImageView.ScaleType.FIT_CENTER);
        screenImg.setBackground(Ui.card(this, 0xFF000000, Ui.STROKE, 16));
        screenImg.setAdjustViewBounds(true);
        int w = getResources().getDisplayMetrics().widthPixels - dp(24);
        p.addView(screenImg, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, w * 9 / 16));
        screenInfo = Ui.text(this, 12, Ui.DIM, false);
        screenInfo.setPadding(dp(4), dp(6), 0, dp(6));
        screenInfo.setText(T.t("Updates about once a second. Netflix / Prime may show black (protected)."));
        p.addView(screenInfo);
        UiKit.TouchPad tp = new UiKit.TouchPad(this, k -> {
            key(k);
            main.postDelayed(this::screenTick, 350);
        });
        p.addView(tp, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(200)));
        p.addView(iconRow(new String[]{"arrow-left", "house", "circle-play", "volume-1", "volume-2"},
                new Runnable[]{() -> key("back"), () -> key("home"), () -> key("media_playpause"), () -> key("vol_down"),
                        () -> key("vol_up")}, 46));
        return scroll(p);
    }

    private void openScreen() {
        openSheet(screenPage, T.t("Live TV screen"), "monitor-play");
        screenTick();
    }

    private void screenTick() {
        if (!sheetOpen || sheetView != screenPage || screenBusy) return;
        screenBusy = true;
        io.execute(() -> {
            try {
                byte[] b = TvClient.bytes(ip, "/screen.jpg?w=720");
                final android.graphics.Bitmap bm = b == null ? null : BitmapFactory.decodeByteArray(b, 0, b.length);
                main.post(() -> {
                    screenBusy = false;
                    if (bm != null) screenImg.setImageBitmap(bm);
                    else screenInfo.setText(UiKit.noEmoji(T.t("To see the TV screen, turn on the 🔌 Full remote (ADB) on the TV.")));
                    main.postDelayed(this::screenTick, 800);
                });
            } catch (Exception e) {
                main.post(() -> {
                    screenBusy = false;
                    main.postDelayed(this::screenTick, 1500);
                });
            }
        });
    }

    private void addPad(FrameLayout pad, String t, int size, int gravity, final String k, boolean primary) {
        Button b = Ui.iconButton(this, t, size / getResources().getDisplayMetrics().density, primary);
        if (!primary) {
            b.setBackground(new GradientDrawable());
            b.setTextColor(Ui.TEXT);
        }
        b.setTextSize(primary ? 18 : 22);
        b.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            key(k);
        });
        pad.addView(b, new FrameLayout.LayoutParams(size, size, gravity));
    }

    private final Runnable qRun = new Runnable() {
        public void run() {
            String q = qSearch.getText().toString().trim();
            if (q.isEmpty()) {
                queueSig = "";
                renderQueue();
                return;
            }
            call("/qsearch", m("q", q), r -> {
                queueBox.removeAllViews();
                JSONArray a = r.optJSONArray("items");
                if (a == null || a.length() == 0) {
                    label(queueBox, T.t("No channel found"));
                    return;
                }
                for (int i = 0; i < a.length(); i++) {
                    JSONObject it = a.optJSONObject(i);
                    queueRow(it.optInt("i"), it.optString("t"), it.optBoolean("cur"));
                }
            });
        }
    };

    private void queueRow(final int i, String title, boolean cur) {
        TextView t = Ui.text(this, 14, cur ? Ui.ACCENT : Ui.TEXT, cur);
        t.setText((cur ? "▶ " : (i + 1) + ".  ") + title);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(dp(4), dp(10), dp(4), dp(10));
        t.setOnClickListener(v -> cmd("item", String.valueOf(i)));
        queueBox.addView(t);
    }

    private void renderQueue() {
        JSONArray q = S.optJSONArray("queue");
        String sig = (q == null ? "" : q.length() + ":" + S.optInt("qstart")) + ":" + S.optInt("idx");
        if (sig.equals(queueSig) || !qSearch.getText().toString().trim().isEmpty()) return;
        queueSig = sig;
        queueBox.removeAllViews();
        if (q == null || q.length() == 0) {
            label(queueBox, "—");
            return;
        }
        int q0 = S.optInt("qstart"), idx = S.optInt("idx");
        int from = Math.max(0, idx - q0 - 3);
        for (int k = from; k < q.length() && k < from + 25; k++) queueRow(q0 + k, q.optString(k), q0 + k == idx);
        if (S.optInt("count") > 25) label(queueBox, S.optInt("count") + " · 🔍");
    }

    private void saveQueue() {
        final EditText e = new EditText(this);
        e.setText("IPTV");
        e.setSingleLine(true);
        new UiDialog(this).setTitle(T.t("Playlist name")).setView(e)
                .setPositiveButton(T.t("Save"), (d, w) -> call("/savequeue", m("playlist", e.getText().toString()), null))
                .setNegativeButton(T.t("Cancel"), null).show();
    }

    private void loadApps() {
        call("/apps", null, r -> {
            appsBox.removeAllViews();
            JSONArray a = r.optJSONArray("apps");
            if (a == null || a.length() == 0) {
                label(appsBox, "—");
                return;
            }
            LinearLayout line = null;
            for (int i = 0; i < a.length(); i++) {
                if (i % 4 == 0) {
                    line = new LinearLayout(this);
                    appsBox.addView(line);
                }
                final JSONObject ap = a.optJSONObject(i);
                LinearLayout tile = new LinearLayout(this);
                tile.setOrientation(LinearLayout.VERTICAL);
                tile.setGravity(Gravity.CENTER);
                tile.setPadding(dp(4), dp(8), dp(4), dp(8));
                tile.setBackground(Ui.focusCard(this, 14));
                ImageView iv = new ImageView(this);
                String ic = ap.optString("icon");
                if (ic.startsWith("data:")) {
                    try {
                        byte[] bb = Base64.decode(ic.substring(ic.indexOf(',') + 1), Base64.DEFAULT);
                        iv.setImageBitmap(BitmapFactory.decodeByteArray(bb, 0, bb.length));
                    } catch (Exception ignored) {
                    }
                } else {
                    iv.setImageDrawable(Ui.gradient(this, 10));
                }
                tile.addView(iv, new LinearLayout.LayoutParams(dp(40), dp(40)));
                TextView n = Ui.text(this, 11, Ui.TEXT, false);
                n.setText(ap.optString("name"));
                n.setSingleLine(true);
                n.setEllipsize(TextUtils.TruncateAt.END);
                n.setGravity(Gravity.CENTER);
                tile.addView(n, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                tile.setOnClickListener(v -> call("/launch", m("pkg", ap.optString("pkg")), null));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                lp.setMargins(dp(3), dp(3), dp(3), dp(3));
                line.addView(tile, lp);
            }
            if (line != null) while (line.getChildCount() < 4) {
                line.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
            }
        });
    }

    private void loadHistory() {
        call("/history", null, r -> {
            JSONArray a = r.optJSONArray("items");
            String sig = a == null ? "" : a.toString();
            View card = (View) cwBox.getParent().getParent();
            card.setVisibility(a == null || a.length() == 0 ? View.GONE : View.VISIBLE);
            if (sig.equals(cwSig)) return;
            cwSig = sig;
            cwBox.removeAllViews();
            if (a == null) return;
            for (int i = 0; i < a.length(); i++) {
                final JSONObject w = a.optJSONObject(i);
                LinearLayout tile = new LinearLayout(this);
                tile.setOrientation(LinearLayout.VERTICAL);
                tile.setPadding(0, 0, dp(10), 0);
                final ImageView iv = new ImageView(this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackground(Ui.gradient(this, 12));
                tile.addView(iv, new LinearLayout.LayoutParams(dp(140), dp(80)));
                TextView n = Ui.text(this, 12, Ui.TEXT, true);
                n.setText(w.optString("t"));
                n.setSingleLine(true);
                n.setEllipsize(TextUtils.TruncateAt.END);
                tile.addView(n, new LinearLayout.LayoutParams(dp(140), ViewGroup.LayoutParams.WRAP_CONTENT));
                TextView l = Ui.text(this, 11, Ui.DIM, false);
                l.setText(Math.max(1, (w.optLong("l") - w.optLong("p")) / 60000) + " " + T.t("min left"));
                tile.addView(l);
                tile.setOnClickListener(v -> call("/play", m("url", w.optString("u"), "title", w.optString("t")), null));
                cwBox.addView(tile);
                final String k = w.optString("thumb");
                if (!k.isEmpty()) io.execute(() -> {
                    try {
                        byte[] b = TvClient.bytes(ip, "/thumb?k=" + k);
                        if (b == null) return;
                        final Bitmap bm = BitmapFactory.decodeByteArray(b, 0, b.length);
                        main.post(() -> iv.setImageBitmap(bm));
                    } catch (Exception ignored) {
                    }
                });
            }
        });
    }

    // =====================================================================
    // TAB 2: audio & subtitles
    // =====================================================================
    private View buildTracks() {
        LinearLayout p = page();
        LinearLayout a = card(p, T.t("Audio track"));
        audioBtn = full(a, btn("🔊", false, v -> pickTrack("audio", "audioSel", "audio")));
        adLabel = label(a, "");
        row(a, btn("−0.5", false, v -> delay("adelay", -500)), btn("−.05", false, v -> delay("adelay", -50)),
                btn("0", false, v -> cmd("adelay", "0")), btn("+.05", false, v -> delay("adelay", 50)),
                btn("+0.5", false, v -> delay("adelay", 500)));

        LinearLayout s = card(p, T.t("Subtitles"));
        subBtn = full(s, btn("💬", false, v -> pickTrack("subs", "subSel", "sub")));
        full(s, btn("🚫 " + T.t("Off"), false, v -> cmd("sub", "-1")));
        sdLabel = label(s, "");
        row(s, btn("−1", false, v -> delay("sdelay", -1000)), btn("−0.1", false, v -> delay("sdelay", -100)),
                btn("0", false, v -> cmd("sdelay", "0")), btn("+0.1", false, v -> delay("sdelay", 100)),
                btn("+1", false, v -> delay("sdelay", 1000)));

        LinearLayout add = card(p, T.t("Load subtitles"));
        full(add, btn("🔎 " + T.t("Find subtitles online…"), true, v -> {
            final EditText q = new EditText(this);
            q.setSingleLine(true);
            q.setText(SubSearch.cleanTitle(S.optString("title")));
            new UiDialog(this).setTitle("🔎 " + T.t("Find subtitles online…")).setView(q)
                    .setPositiveButton(T.t("Search"), (d, w) -> {
                        toast("🔎 …");
                        call("/subsearch", m("q", q.getText().toString().trim()), r -> {
                            final JSONArray hits = r.optJSONArray("items");
                            if (hits == null || hits.length() == 0) {
                                toast(r.optString("msg", T.t("No subtitles found")));
                                return;
                            }
                            String[] n = new String[hits.length()];
                            for (int i = 0; i < n.length; i++) n[i] = hits.optString(i);
                            new UiDialog(this).setTitle(T.t("Subtitles")).setItems(n,
                                    (d2, w2) -> call("/subpick", m("i", String.valueOf(w2)), null)).show();
                        });
                    }).setNegativeButton(T.t("Cancel"), null).show();
        }));
        full(add, btn("📁 " + T.t("Load subtitles") + " (.srt .ass .vtt .zip)", true, v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            startActivityForResult(i, PICK_SUB);
        }));
        final EditText link = field(add, "https://…/subtitle.srt", false);
        full(add, btn("⬇ " + T.t("Open"), false, v -> {
            String u = link.getText().toString().trim();
            if (!u.isEmpty()) {
                toast("…");
                cmd("subadd", u);
                link.setText("");
            }
        }));

        LinearLayout st = card(p, T.t("Subtitle style"));
        sizeBtn = full(st, btn("", false, v -> pickOpt("subsizes", "subsize", T.t("Subtitle size"))));
        encBtn = full(st, btn("", false, v -> pickOpt("encs", "subenc", T.t("Subtitle encoding"))));
        subBg = new CheckBox(this);
        subBg.setText(T.t("Background box: "));
        subBg.setTextColor(Ui.TEXT);
        subBg.setOnClickListener(v -> cmd("subbg", subBg.isChecked() ? "1" : "0"));
        st.addView(subBg);
        return p;
    }

    private void delay(String k, long d) {
        long cur = "adelay".equals(k) ? S.optLong("adelay") : S.optLong("sdelay");
        cmd(k, String.valueOf(cur + d));
    }

    private void pickTrack(String listKey, String selKey, final String command) {
        final JSONArray t = S.optJSONArray(listKey);
        if (t == null || t.length() == 0) {
            toast("—");
            return;
        }
        String[] names = new String[t.length()];
        int sel = -1;
        for (int i = 0; i < t.length(); i++) {
            names[i] = t.optJSONObject(i).optString("name");
            if (t.optJSONObject(i).optInt("id") == S.optInt(selKey)) sel = i;
        }
        new UiDialog(this).setSingleChoiceItems(names, sel, (d, w) -> {
            d.dismiss();
            cmd(command, String.valueOf(t.optJSONObject(w).optInt("id")));
        }).show();
    }

    /** Choice from an options list of [value, name] pairs. */
    private void pickOpt(String optKey, final String command, String title) {
        if (opts == null) return;
        final JSONArray a = opts.optJSONArray(optKey);
        if (a == null) return;
        String[] names = new String[a.length()];
        int sel = -1;
        String cur = S.optString(command);
        for (int i = 0; i < a.length(); i++) {
            names[i] = a.optJSONArray(i).optString(1);
            if (a.optJSONArray(i).optString(0).equals(cur)) sel = i;
        }
        new UiDialog(this).setTitle(title).setSingleChoiceItems(names, sel, (d, w) -> {
            d.dismiss();
            cmd(command, a.optJSONArray(w).optString(0));
        }).show();
    }

    private String optName(String optKey, String value) {
        if (opts == null) return value;
        JSONArray a = opts.optJSONArray(optKey);
        if (a != null) for (int i = 0; i < a.length(); i++) {
            if (a.optJSONArray(i).optString(0).equals(value)) return a.optJSONArray(i).optString(1);
        }
        return value;
    }

    // =====================================================================
    // TAB 3: picture & sound
    // =====================================================================
    private View buildPicture() {
        LinearLayout p = page();
        LinearLayout pic = card(p, T.t("Picture fit"));
        fitBtn = full(pic, btn("", false, v -> pickOpt("fits", "fit", T.t("Picture fit"))));
        aspBtn = full(pic, btn("", false, v -> pickOpt("aspects", "aspect", T.t("Aspect ratio"))));
        zoomLabel = label(pic, "");
        zoom = bar(pic, 250);
        zoom.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int v, boolean user) {
                if (user) {
                    zoomTouch = System.currentTimeMillis();
                    zoomLabel.setText(T.t("Zoom / crop: ") + (v + 50) + "%");
                }
            }

            public void onStartTrackingTouch(SeekBar s) {
                zoomTouch = System.currentTimeMillis();
            }

            public void onStopTrackingTouch(SeekBar s) {
                zoomTouch = System.currentTimeMillis();
                cmd("zoom", String.valueOf(s.getProgress() + 50));
            }
        });
        full(pic, btn(T.t("Zoom / crop to fill the screen") + "…", false, v -> pickOpt("zooms", "zoom", T.t("Zoom"))));

        LinearLayout e = card(p, T.t("Equalizer"));
        LinearLayout er = new LinearLayout(this);
        er.setGravity(Gravity.CENTER_VERTICAL);
        eqOn = new CheckBox(this);
        eqOn.setText(T.t("Equalizer on"));
        eqOn.setTextColor(Ui.TEXT);
        eqOn.setOnClickListener(v -> cmd("eq_on", eqOn.isChecked() ? "1" : "0"));
        er.addView(eqOn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        presetBtn = btn("Preset", true, v -> pickPreset());
        er.addView(presetBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)));
        e.addView(er);
        eq = new EqView(this);
        eq.setBands(PlayerActivity.eqLabels(), new float[11]);
        eq.setListener((i, db) -> {
            eqTouch = System.currentTimeMillis();
            final int band = i;
            final float val = db;
            main.removeCallbacks(eqSend);
            eqSend = () -> {
                if (band == 0) cmd("eq_preamp", String.valueOf(val));
                else cmd("eq_band", String.valueOf(val), String.valueOf(band - 1));
            };
            main.postDelayed(eqSend, 250);
        });
        e.addView(eq, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(240)));
        full(e, btn(T.t("Reset"), false, v -> cmd("eq_reset", "")));

        LinearLayout pb = card(p, T.t("Playback speed"));
        rateBtn = full(pb, btn("", false, v -> pickRate()));
        sleepBtn = full(pb, btn("", false, v -> pickSleep()));
        hwBtn = full(pb, btn("", false, v -> cmd("hw", S.optInt("hw") == 1 ? "0" : "1")));
        cacheBtn = full(pb, btn("", false, v -> pickCache()));
        nightBtn = full(pb, btn("🗣", true, v -> {
            String[] n = {PlayerActivity.dialogName(0), PlayerActivity.dialogName(1), PlayerActivity.dialogName(2), PlayerActivity.dialogName(3)};
            new UiDialog(this).setTitle("🗣 " + T.t("Clear dialogue (quiet voices, loud music)"))
                    .setSingleChoiceItems(n, S.optInt("dialog"), (d, w) -> {
                        d.dismiss();
                        cmd("dialog", String.valueOf(w));
                    }).show();
        }));
        levelBtn = full(pb, btn("📊", false, v -> cmd("level", S.optBoolean("level") ? "0" : "1")));
        afrBtn = full(pb, btn("🎞", false, v -> cmd("afr", S.optBoolean("afr") ? "0" : "1")));
        qualBtn = full(pb, btn("📶", false, v -> {
            final int[] qs = {0, 1080, 720, 480, 360};
            String[] names = {T.t("Best"), "1080p", "720p", "480p", "360p (" + T.t("saves most data") + ")"};
            new UiDialog(this).setTitle(T.t("Streaming quality: "))
                    .setItems(names, (d, w) -> cmd("quality", String.valueOf(qs[w]))).show();
        }));
        label(pb, "ℹ IPTV / HLS / DASH streams use the lower quality. Torrents: picks the matching file when a torrent has several.");
        return p;
    }

    private Runnable eqSend = () -> { };

    private void pickPreset() {
        if (opts == null) return;
        final JSONArray a = opts.optJSONArray("presets");
        if (a == null) return;
        String[] n = new String[a.length()];
        for (int i = 0; i < n.length; i++) n[i] = a.optString(i);
        JSONObject e = S.optJSONObject("eq");
        new UiDialog(this).setTitle(T.t("Equalizer preset"))
                .setSingleChoiceItems(n, e == null ? -1 : e.optInt("preset", -1), (d, w) -> {
                    d.dismiss();
                    cmd("eq_preset", String.valueOf(w));
                }).show();
    }

    private void pickRate() {
        if (opts == null) return;
        final JSONArray a = opts.optJSONArray("speeds");
        String[] n = new String[a.length()];
        for (int i = 0; i < n.length; i++) n[i] = a.optDouble(i) + "x";
        new UiDialog(this).setTitle(T.t("Playback speed")).setItems(n, (d, w) -> cmd("rate", String.valueOf(a.optDouble(w)))).show();
    }

    private void pickSleep() {
        if (opts == null) return;
        final JSONArray a = opts.optJSONArray("sleeps");
        String[] n = new String[a.length()];
        for (int i = 0; i < n.length; i++) {
            int mm = a.optInt(i);
            n[i] = mm == 0 ? T.t("Off") : mm < 0 ? T.t("At the end of this video") : mm + T.t(" minutes");
        }
        new UiDialog(this).setTitle(T.t("Sleep timer")).setItems(n, (d, w) -> cmd("sleep", String.valueOf(a.optInt(w)))).show();
    }

    private void pickCache() {
        if (opts == null) return;
        final JSONArray a = opts.optJSONArray("caches");
        String[] n = new String[a.length()];
        for (int i = 0; i < n.length; i++) n[i] = (a.optInt(i) / 1000f) + T.t(" seconds");
        new UiDialog(this).setTitle(T.t("Network buffer")).setItems(n, (d, w) -> cmd("cache", String.valueOf(a.optInt(w)))).show();
    }

    // =====================================================================
    // TAB 4: cast
    // =====================================================================
    private View buildCast() {
        LinearLayout p = page();
        LinearLayout ph = card(p, "📱");
        full(ph, btn("📱 " + T.t("▶ Play now") + " · videos / songs", true, v -> pickMedia("now")));
        full(ph, btn(T.t("+ Add to TV queue"), false, v -> pickMedia("queue")));
        full(ph, btn("🧲 .torrent", false, v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            startActivityForResult(i, PICK_TORRENT);
        }));

        LinearLayout br = card(p, "🌐");
        full(br, btn("🌐 " + T.t("Browser · search & send videos to TV"), false,
                v -> startActivity(new Intent(this, BrowserActivity.class))));
        final EditText bu = field(br, T.t("Search or type a link"), false);
        full(br, btn("📺 " + T.t("Open"), false, v -> call("/browse", m("url", bu.getText().toString().trim()), null)));

        LinearLayout pl = card(p, "▶ Link");
        final EditText url = field(pl, "https://… · magnet:… · .m3u8 · .torrent", true);
        final EditText title = field(pl, "Title", false);
        final EditText sub = field(pl, "Subtitle link", false);
        row(pl, btn(T.t("▶ Play now"), true, v -> {
            call("/play", m("url", url.getText().toString().trim(), "title", title.getText().toString(),
                    "sub", sub.getText().toString().trim(), "mode", "now"), null);
            url.setText("");
            title.setText("");
            sub.setText("");
            hideKeyboard();
        }), btn(T.t("+ Queue"), false, v -> {
            call("/play", m("url", url.getText().toString().trim(), "title", title.getText().toString(), "mode", "queue"), null);
            hideKeyboard();
        }));
        full(pl, btn("⬇ " + T.t("Download for later") + " (magnet / .torrent)", false, v -> {
            call("/dl", m("url", url.getText().toString().trim()), null);
            hideKeyboard();
        }));
        full(pl, btn("⬇ " + T.t("Downloads") + "…", false, v -> call("/dllist", null, r -> {
            final JSONArray a = r.optJSONArray("items");
            String[] n = new String[a == null ? 0 : a.length()];
            for (int i = 0; i < n.length; i++) n[i] = (a.optJSONObject(i).optBoolean("done") ? "✅ " : "⬇ ")
                    + a.optJSONObject(i).optString("name") + "\n" + a.optJSONObject(i).optString("state");
            new UiDialog(this).setTitle("⬇ " + T.t("Downloads"))
                    .setItems(n, (d, w) -> call("/dlcancel", m("src", a.optJSONObject(w).optString("src"), "del", "0"), null))
                    .setNeutralButton(T.t("night only") + ": " + T.t(r.optBoolean("night") ? "On" : "Off"),
                            (d, w) -> call("/dlnight", m("v", r.optBoolean("night") ? "0" : "1"), null))
                    .setPositiveButton(T.t("Close"), null).show();
        })));
        final EditText pln = field(pl, T.t("Playlist name"), false);
        full(pl, btn("💾 " + T.t("Save"), false, v -> {
            toast("…");
            call("/add", m("playlist", pln.getText().toString(), "title", title.getText().toString(),
                    "url", url.getText().toString().trim(), "sub", sub.getText().toString().trim()), r -> {
                toast(r.optBoolean("ok") ? r.optString("msg", T.t("Saved")) : r.optString("msg"));
                loadLists();
            });
            hideKeyboard();
        }));

        LinearLayout tc = card(p, "🧲 Torrent");
        final TextView ts = label(tc, "—");
        ts.setTag("torstat");
        full(tc, btn("⏹ " + T.t("Stop torrent and delete its data"), false, v -> call("/torrentstop", null, null)));
        final TextView cacheInfo = label(tc, "");
        full(tc, btn("🧲 " + T.t("Torrent storage: ") + "…", false, v -> call("/torrentcache", null, r -> {
            final double gb = r.optLong("bytes") / 1073741824.0;
            cacheInfo.setText(UiKit.noEmoji(String.format(Locale.US, "💾 %.2f GB", gb)));
            new UiDialog(this).setTitle(String.format(Locale.US, "🧲 %s%.2f GB", T.t("Torrent storage: "), gb))
                    .setItems(new String[]{"🗑 Delete saved torrents (keep my kept movies)", "🗑 Delete everything (also kept movies)"},
                            (d, w) -> call("/torrentcache", m("do", w == 0 ? "clear" : "all"), null)).show();
        })));

        LinearLayout im = card(p, "M3U");
        final EditText ipl = field(im, T.t("Playlist name"), false);
        final EditText lk = field(im, "https://…/list.m3u", false);
        full(im, btn("⬇ M3U → " + T.t("Playlist"), true, v -> {
            toast("…");
            call("/import", m("playlist", ipl.getText().toString(), "link", lk.getText().toString().trim(), "text", ""), r -> {
                toast(r.optBoolean("ok") ? r.optInt("added") + " ✅" : r.optString("msg"));
                lk.setText("");
                loadLists();
            });
            hideKeyboard();
        }));
        return p;
    }

    private void pickMedia(String mode) {
        pendingMode = mode;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"video/*", "audio/*"});
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, PICK_MEDIA);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == 88) {
            if (res == RESULT_OK && data != null) {
                CastScreenService.start(this, res, data, ip, castHd, castSound);
                UiKit.makeToast(this, T.t("Casting your screen to the TV"), android.widget.Toast.LENGTH_SHORT).show();
            } else UiKit.makeToast(this, T.t("Screen capture was not allowed"), android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        if (req == 77) {
            ArrayList<String> said = data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
            onVoice(said == null || said.isEmpty() ? null : said.get(0));
            return;
        }
        final List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) uris.add(data.getData());
        if (uris.isEmpty()) return;
        for (Uri u : uris) {
            try {
                getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
        }
        if (req == PICK_SUB) {
            final Uri u = uris.get(0);
            io.execute(() -> {
                try (InputStream in = getContentResolver().openInputStream(u)) {
                    ByteArrayOutputStream o = new ByteArrayOutputStream();
                    byte[] b = new byte[16384];
                    int n;
                    while (in != null && (n = in.read(b)) > 0) o.write(b, 0, n);
                    final JSONObject r = TvClient.upload(ip, "/subupload", LocalShare.displayName(this, u), o.toByteArray());
                    main.post(() -> toast(r.optString("msg")));
                } catch (TvClient.AuthNeeded e) {
                    main.post(this::askPin);
                } catch (Exception e) {
                    main.post(() -> toast(T.t("Cannot reach the TV")));
                }
            });
            return;
        }
        final boolean torrent = req == PICK_TORRENT;
        final String mode = pendingMode;
        io.execute(() -> {
            StringBuilder us = new StringBuilder(), ts = new StringBuilder();
            for (Uri u : uris) {
                String name = LocalShare.displayName(this, u);
                if (torrent && !name.toLowerCase(Locale.ROOT).endsWith(".torrent")) name += ".torrent";
                us.append(LocalShare.share(this, u, name)).append('\n');
                ts.append(name).append('\n');
            }
            main.post(() -> call("/playmany", m("urls", us.toString(), "titles", ts.toString(), "mode", mode), null));
        });
    }

    // =====================================================================
    // TAB 5: library
    // =====================================================================
    private View buildLibrary() {
        LinearLayout p = page();
        LinearLayout add = card(p, T.t("Playlist"));
        final EditText n = field(add, T.t("Playlist name"), false);
        final EditText t = field(add, "Title", false);
        final EditText u = field(add, "https://… · magnet:… · .m3u", false);
        full(add, btn("＋ " + T.t("Save"), true, v -> {
            toast("…");
            call("/add", m("playlist", n.getText().toString(), "title", t.getText().toString(), "url", u.getText().toString().trim()), r -> {
                toast(r.optBoolean("ok") ? r.optString("msg", T.t("Saved")) : r.optString("msg"));
                t.setText("");
                u.setText("");
                loadLists();
            });
            hideKeyboard();
        }));
        LinearLayout lc = card(p, null);
        listsBox = new LinearLayout(this);
        listsBox.setOrientation(LinearLayout.VERTICAL);
        lc.addView(listsBox);

        LinearLayout tvs = card(p, "TV");
        full(tvs, btn("🎨 " + T.t("Theme"), false, v -> {
            final String[] codes = {"dark", "light", "system"};
            String[] names = {T.t("Dark"), T.t("Light"), T.t("Follow system")};
            new UiDialog(this).setTitle(T.t("Theme")).setItems(names, (d, w) -> cmd("apptheme", codes[w])).show();
        }));
        final EditText dn = field(tvs, T.t("Device name (shown in the phone app)"), false);
        full(tvs, btn(T.t("Save"), false, v -> {
            cmd("devname", dn.getText().toString().trim());
            hideKeyboard();
        }));
        full(tvs, btn("📊 " + T.t("My TV") + " · " + T.t("storage, memory, speed test"), true, v -> myTv()));
        full(tvs, btn("🎨 " + T.t("TV colours (palette)"), false, v -> {
            String[] names = Palette.NAMES;
            new UiDialog(this).setTitle(T.t("Palette")).setItems(names, (d, w) -> cmd("palette", String.valueOf(w))).show();
        }));
        full(tvs, btn("🎨 " + T.t("This phone app's colours"), false, v -> Palette.show(this)));
        full(tvs, btn("🌐 Web version", false, v -> startActivity(new Intent(this, RemoteWebActivity.class)
                .putExtra(RemoteWebActivity.EXTRA_IP, ip).putExtra(RemoteWebActivity.EXTRA_NAME, tvName))));
        return p;
    }

    /** Report from the TV + tests and cleaning, run on the TV. */
    private void myTv() {
        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(8));
        sv.addView(box);
        final TextView ln = Ui.text(this, 14, Ui.ACCENT, true);
        box.addView(ln);
        final TextView rep = Ui.text(this, 12, Ui.TEXT, false);
        rep.setTypeface(android.graphics.Typeface.MONOSPACE);
        box.addView(rep);
        row(box, btn("🌐 Speed", true, v -> call("/tvtest", m("t", "speed"), r -> toast(r.optString("msg")))),
                btn("🧹 " + T.t("Free up memory"), true, v -> call("/tvclean", null, r -> ln.setText(r.optString("msg")))));
        row(box, btn("💾 Disk", false, v -> call("/tvtest", m("t", "disk"), r -> toast(r.optString("msg")))),
                btn("⚙ CPU/RAM", false, v -> call("/tvtest", m("t", "bench"), r -> toast(r.optString("msg")))),
                btn("📋 Apps", false, v -> call("/tvapps", null, r -> {
                    if (!r.optBoolean("ok")) {
                        toast(r.optString("msg"));
                        return;
                    }
                    final JSONArray a = r.optJSONArray("apps");
                    String[] n = new String[a.length()];
                    for (int i = 0; i < n.length; i++) n[i] = a.optJSONObject(i).optString("mem") + "   " + a.optJSONObject(i).optString("label");
                    new UiDialog(this).setTitle("📋 " + T.t("Running apps") + " · " + T.t("tap to stop"))
                            .setItems(n, (d, w) -> call("/tvstop", m("name", a.optJSONObject(w).optString("name")), null)).show();
                })));
        final AlertDialog dlg = new UiDialog(this).setTitle("📊 " + T.t("My TV")).setView(sv)
                .setPositiveButton(T.t("Close"), null).create();
        final Runnable[] upd = new Runnable[1];
        upd[0] = () -> {
            if (!dlg.isShowing()) return;
            call("/tvinfo", null, r -> {
                rep.setText(r.optString("report"));
                String l = r.optString("line");
                if (!l.isEmpty()) ln.setText(l);
            });
            main.postDelayed(upd[0], 2000);
        };
        dlg.setOnShowListener(x -> upd[0].run());
        dlg.show();
    }

    private void loadLists() {
        call("/data", null, r -> {
            listsBox.removeAllViews();
            final JSONArray a = r.optJSONArray("playlists");
            if (a == null || a.length() == 0) {
                label(listsBox, T.t("This playlist is empty."));
                return;
            }
            full(listsBox, btn("🗑 " + T.t("Select & delete playlists…"), false, v -> deletePlaylists(a)));
            for (int i = 0; i < a.length(); i++) {
                final JSONObject pl = a.optJSONObject(i);
                final String name = pl.optString("name");
                LinearLayout rw = new LinearLayout(this);
                rw.setGravity(Gravity.CENTER_VERTICAL);
                rw.setPadding(dp(4), dp(8), 0, dp(8));
                TextView tv = Ui.text(this, 15, Ui.TEXT, true);
                tv.setText(name + "  ·  " + pl.optInt("count"));
                tv.setSingleLine(true);
                tv.setEllipsize(TextUtils.TruncateAt.END);
                rw.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                Button open = btn("📂", false, v -> openPlaylist(name));
                Button play = btn("▶", true, v -> call("/playlist", m("playlist", name, "idx", "0"), null));
                rw.addView(open, new LinearLayout.LayoutParams(dp(52), dp(40)));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(52), dp(40));
                lp.setMargins(dp(6), 0, 0, 0);
                rw.addView(play, lp);
                rw.setOnClickListener(v -> openPlaylist(name));
                rw.setOnLongClickListener(v -> {
                    String[] acts = {"✏ " + T.t("Rename playlist"), "🔄 " + T.t("Update channels from link"),
                            "🩺 " + T.t("Check channels (find ones that don't work)"), "🗑 " + T.t("Remove channels that don't work")};
                    new UiDialog(this).setTitle(name).setItems(acts, (d, w) -> {
                        if (w == 0) {
                            final EditText e = new EditText(this);
                            e.setText(name);
                            new UiDialog(this).setTitle(T.t("Rename playlist")).setView(e)
                                    .setPositiveButton(T.t("Save"), (d2, w2) -> call("/rename", m("playlist", name, "name", e.getText().toString()), r2 -> {
                                        toast(r2.optString("msg"));
                                        loadLists();
                                    })).setNegativeButton(T.t("Cancel"), null).show();
                        } else if (w == 1) {
                            toast("…");
                            call("/refreshlist", m("playlist", name), r2 -> {
                                toast(r2.optString("msg"));
                                loadLists();
                            });
                        } else if (w == 2) call("/scan", m("playlist", name), null);
                        else call("/scan", m("playlist", name, "do", "remove"), r2 -> {
                            toast(r2.optString("msg"));
                            loadLists();
                        });
                    }).show();
                    return true;
                });
                listsBox.addView(rw);
            }
        });
    }

    private void deletePlaylists(JSONArray a) {
        final String[] names = new String[a.length()];
        for (int i = 0; i < names.length; i++) names[i] = a.optJSONObject(i).optString("name");
        multiDelete(T.t("Delete playlists"), names, checked -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < checked.length; i++) if (checked[i]) sb.append(names[i]).append('\n');
            call("/delpls", m("names", sb.toString()), r -> {
                toast(r.optString("msg"));
                loadLists();
            });
        });
    }

    private interface OnPick {
        void run(boolean[] checked);
    }

    /** Multi-choice with "Select all" (doesn't close) and a confirm step. */
    private void multiDelete(String title, String[] names, final OnPick onDelete) {
        final boolean[] checked = new boolean[names.length];
        final AlertDialog d = new UiDialog(this).setTitle(title)
                .setMultiChoiceItems(names, checked, (dd, w, on) -> checked[w] = on)
                .setNeutralButton(T.t("Select all"), null)
                .setPositiveButton(T.t("Delete"), null)
                .setNegativeButton(T.t("Cancel"), null).create();
        d.setOnShowListener(x -> {
            d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                boolean all = true;
                for (boolean c : checked) all &= c;
                for (int i = 0; i < checked.length; i++) {
                    checked[i] = !all;
                    d.getListView().setItemChecked(i, !all);
                }
                ((Button) v).setText(all ? T.t("Select all") : T.t("Select none"));
            });
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                int n = 0;
                for (boolean c : checked) if (c) n++;
                if (n == 0) {
                    toast(T.t("Nothing selected"));
                    return;
                }
                new UiDialog(this).setTitle(T.t("Delete ") + n + T.t("?"))
                        .setMessage(T.t("This cannot be undone."))
                        .setPositiveButton(T.t("Delete"), (a1, b1) -> {
                            d.dismiss();
                            onDelete.run(checked);
                        }).setNegativeButton(T.t("Cancel"), null).show();
            });
        });
        d.show();
    }

    /** Playlist viewer: live search, tap = play, long-press = menu, select & delete (with select all). */
    private void openPlaylist(final String name) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(4), dp(12), 0);
        final EditText q = new EditText(this);
        q.setHint("🔍 " + T.t("Search channel / name"));
        q.setSingleLine(true);
        box.addView(q);
        final TextView info = Ui.text(this, 12, Ui.DIM, false);
        box.addView(info);
        final ListView lv = new ListView(this);
        final List<String> titles = new ArrayList<>();
        final List<Integer> idx = new ArrayList<>();
        final List<String> urls = new ArrayList<>();
        final ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, titles);
        lv.setAdapter(ad);
        box.addView(lv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (getResources().getDisplayMetrics().heightPixels * 0.55f)));
        final int[] total = {0};
        final Runnable[] load = new Runnable[1];
        final boolean[] more = {false};
        load[0] = () -> {
            final int from = idx.isEmpty() ? 0 : idx.get(idx.size() - 1) + 1;
            call("/page", m("playlist", name, "from", String.valueOf(from), "q", q.getText().toString().trim()), r -> {
                if (from == 0) {
                    titles.clear();
                    idx.clear();
                    urls.clear();
                }
                total[0] = r.optInt("count");
                JSONArray a = r.optJSONArray("items");
                if (a != null) for (int i = 0; i < a.length(); i++) {
                    JSONObject it = a.optJSONObject(i);
                    idx.add(it.optInt("i"));
                    titles.add((it.optInt("i") + 1) + ".  " + it.optString("t"));
                    urls.add(it.optString("u"));
                }
                more[0] = a != null && a.length() == 200;
                ad.notifyDataSetChanged();
                info.setText(titles.size() + (more[0] ? "+" : "") + " / " + total[0]);
            });
        };
        q.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence c, int a1, int b1, int d) { }
            public void onTextChanged(CharSequence c, int a1, int b1, int d) { }
            public void afterTextChanged(Editable e) {
                idx.clear();
                main.removeCallbacks(load[0]);
                main.postDelayed(load[0], 350);
            }
        });
        lv.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
            public void onScrollStateChanged(android.widget.AbsListView v, int s) { }
            public void onScroll(android.widget.AbsListView v, int first, int vis, int tot) {
                if (more[0] && tot > 0 && first + vis >= tot - 5) {
                    more[0] = false;
                    load[0].run();
                }
            }
        });
        final AlertDialog dlg = new UiDialog(this).setTitle("📂 " + name).setView(box)
                .setNeutralButton("🗑 " + T.t("Select & delete…"), null)
                .setPositiveButton(T.t("Close"), null).create();
        lv.setOnItemClickListener((pa, v, pos, id) -> {
            call("/playlist", m("playlist", name, "idx", String.valueOf(idx.get(pos))), null);
            dlg.dismiss();
        });
        lv.setOnItemLongClickListener((pa, v, pos, id) -> {
            final int i = idx.get(pos);
            final String u = urls.get(pos);
            List<String> acts = new ArrayList<>();
            acts.add(T.t("▶ Play now"));
            acts.add("★ " + T.t("Favourite"));
            acts.add("✏ " + T.t("Edit name / link"));
            boolean list = Formats.maybeList(u);
            if (list) acts.add("📥 " + T.t("Load all channels"));
            acts.add("🗑 " + T.t("Delete"));
            final boolean isList = list;
            new UiDialog(this).setTitle(titles.get(pos)).setItems(acts.toArray(new String[0]), (d, w) -> {
                if (w == 0) {
                    call("/playlist", m("playlist", name, "idx", String.valueOf(i)), null);
                    dlg.dismiss();
                } else if (w == 1) {
                    call("/fav", m("url", u), null);
                } else if (w == 2) {
                    LinearLayout eb = new LinearLayout(this);
                    eb.setOrientation(LinearLayout.VERTICAL);
                    final EditText et = new EditText(this), eu = new EditText(this);
                    et.setText(titles.get(pos).replaceFirst("^\\d+\\.\\s+", ""));
                    eu.setText(u);
                    eb.addView(et);
                    eb.addView(eu);
                    new UiDialog(this).setTitle(T.t("Edit name / link")).setView(eb)
                            .setPositiveButton(T.t("Save"), (d2, w2) -> call("/edit", m("playlist", name, "idx", String.valueOf(i),
                                    "title", et.getText().toString(), "url", eu.getText().toString()), r2 -> {
                                idx.clear();
                                load[0].run();
                            })).setNegativeButton(T.t("Cancel"), null).show();
                } else if (isList && w == 3) {
                    toast("…");
                    call("/expand", m("playlist", name, "idx", String.valueOf(i)), r -> {
                        toast(r.optString("msg"));
                        idx.clear();
                        load[0].run();
                        loadLists();
                    });
                } else {
                    call("/del", m("playlist", name, "idx", String.valueOf(i)), r -> {
                        idx.clear();
                        load[0].run();
                        loadLists();
                    });
                }
            }).show();
            return true;
        });
        dlg.setOnShowListener(x -> dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            final String query = q.getText().toString().trim();
            final List<Integer> shown = new ArrayList<>(idx);
            String[] names = titles.toArray(new String[0]);
            final int all = query.isEmpty() ? total[0] : -1;
            multiDelete(T.t("Select to delete · ") + names.length, names, checked -> {
                boolean every = true;
                StringBuilder ids = new StringBuilder();
                for (int i = 0; i < checked.length; i++) {
                    if (checked[i]) ids.append(shown.get(i)).append(',');
                    else every = false;
                }
                Map<String, String> pr;
                // everything ticked = delete every match on the TV (also the ones not loaded yet)
                if (every && (more[0] || all < 0 || shown.size() < total[0])) {
                    pr = m("playlist", name, "all", "1", "q", query, "except", "");
                } else {
                    pr = m("playlist", name, "idx", ids.toString());
                }
                call("/delmany", pr, r -> {
                    toast(r.optString("msg"));
                    idx.clear();
                    load[0].run();
                    loadLists();
                });
            });
        }));
        dlg.show();
        load[0].run();
    }

    // =====================================================================
    // updating the screen from /state
    // =====================================================================
    private void setIf(TextView v, String s) {
        s = UiKit.noEmoji(s);
        if (v != null && !s.contentEquals(v.getText())) v.setText(s);
    }

    private void render() {
        JSONObject oth = S.optJSONObject("other");
        if (!S.optBoolean("player") && oth != null) {
            // another app (YouTube, Netflix…) is playing: show it like our own player
            try {
                S.put("player", true).put("title", "📺 " + oth.optString("app") + " · " + oth.optString("title")
                        + (oth.optString("artist").isEmpty() ? "" : " — " + oth.optString("artist")))
                        .put("pos", oth.optLong("pos")).put("len", oth.optLong("len")).put("playing", oth.optBoolean("playing"));
            } catch (Exception ignored) {
            }
        }
        otherBtn.setVisibility(!S.optBoolean("otherAllowed", true) ? View.VISIBLE : View.GONE);
        boolean p = S.optBoolean("player");
        String dev = S.optString("device", tvName);
        setIf(devLine, dev + " · " + ip);
        setIf(npTitle, p ? S.optString("title") : T.t("Nothing playing"));
        String sub = "";
        if (p) {
            sub = (S.optInt("count") > 1 ? (S.optInt("idx") + 1) + " / " + S.optInt("count") + "  ·  " : "")
                    + (S.optBoolean("playing") ? "▶" : "❚❚");
            String ld = S.optString("loading");
            if (!ld.isEmpty()) sub += "  ·  " + ld.split("\n")[0];
        }
        setIf(npSub, sub);
        UiKit.setIcon(this, playBtn, p && S.optBoolean("playing") ? "pause" : "play", 24, Color.WHITE);
        UiKit.setIcon(this, artIcon, S.optJSONObject("other") != null ? "tv" : p ? "clapperboard" : "tv-minimal", 24, Color.WHITE);
        if (queueSub != null) setIf(queueSub, S.optInt("count") > 0 ? S.optInt("count") + " · " + T.t("tap to see") : "—");
        long pos = S.optLong("pos"), len = S.optLong("len");
        if (System.currentTimeMillis() - seekTouch > 2500) {
            seek.setProgress(len > 0 ? (int) (1000 * pos / len) : 0);
            setIf(tLeft, len > 0 ? Net.fmtTime(pos) : (p ? "● Live " + Net.fmtTime(pos) : ""));
        }
        setIf(tRight, len > 0 ? Net.fmtTime(len) : "");
        int v = S.optInt("vol", 100);
        if (System.currentTimeMillis() - volTouch > 2500) volSlider.setValue(v);
        int rm = S.optInt("repeat");
        setIf(repBtn, "  " + T.t("Repeat: ") + T.t(rm == 1 ? "One" : rm == 2 ? "All" : "Off"));
        setIf(shufBtn, "  " + T.t("Shuffle: ") + T.t(S.optBoolean("shuffle") ? "On" : "Off"));
        JSONObject t = S.optJSONObject("torrent");
        String ts = "";
        if (t != null && t.optBoolean("active")) {
            ts = String.format(Locale.US, "🧲 %s · %d peers · %.1f MB/s · %.1f%%", t.optString("name"), t.optInt("peers"),
                    t.optLong("speed") / 1048576f, t.optDouble("progress"));
        } else if (t != null && !"idle".equals(t.optString("state", "idle"))) ts = "🧲 " + t.optString("state");
        setIf(torLine, ts);
        torLine.setVisibility(ts.isEmpty() ? View.GONE : View.VISIBLE);
        View tstat = pages[3].findViewWithTag("torstat");
        if (tstat instanceof TextView) setIf((TextView) tstat, ts.isEmpty() ? "—" : ts);
        renderQueue();
        accessHint.setVisibility(S.optBoolean("access", true) ? View.GONE : View.VISIBLE);
        boolean kb = S.optBoolean("kbd");
        if (kb && !kbdShown && System.currentTimeMillis() - kbdDismissed > 4000 && !sheetOpen) showKbd(true);
        else if (!kb && kbdShown && System.currentTimeMillis() - kbdTyped > 2500) showKbd(false);
        adbBtn.setVisibility(S.optBoolean("adb", true) ? View.GONE : View.VISIBLE);
        if (System.currentTimeMillis() - brTouch > 2500) brSlider.setValue(S.optInt("bright", 100));
        int qv = S.optInt("quality", 0);
        setIf(qualBtn, "📶  " + T.t("Streaming quality: ") + (qv == 0 ? T.t("Best") : qv + "p"));

        // tracks
        setIf(audioBtn, "🔊  " + trackName("audio", "audioSel"));
        setIf(subBtn, "💬  " + trackName("subs", "subSel"));
        setIf(adLabel, T.t("Audio delay: ") + String.format(Locale.US, "%.2f s", S.optLong("adelay") / 1000f));
        setIf(sdLabel, T.t("Subtitle delay: ") + String.format(Locale.US, "%.2f s", S.optLong("sdelay") / 1000f));
        setIf(sizeBtn, T.t("Size: ") + optName("subsizes", String.valueOf(S.optInt("subsize", 16))));
        setIf(encBtn, T.t("Text encoding: ") + optName("encs", S.optString("subenc")));
        if (subBg.isChecked() != S.optBoolean("subbg")) subBg.setChecked(S.optBoolean("subbg"));

        // picture & sound
        setIf(fitBtn, "🖼  " + optName("fits", String.valueOf(S.optInt("fit"))));
        setIf(aspBtn, "📐  " + optName("aspects", S.optString("aspect")));
        if (System.currentTimeMillis() - zoomTouch > 2500) {
            zoom.setProgress(S.optInt("zoom", 100) - 50);
            setIf(zoomLabel, T.t("Zoom / crop: ") + S.optInt("zoom", 100) + "%");
        }
        JSONObject e = S.optJSONObject("eq");
        if (e != null) {
            if (eqOn.isChecked() != e.optBoolean("on")) eqOn.setChecked(e.optBoolean("on"));
            int pr = e.optInt("preset", -1);
            JSONArray names = opts == null ? null : opts.optJSONArray("presets");
            setIf(presetBtn, pr >= 0 && names != null && pr < names.length() ? names.optString(pr) : "Preset");
            if (System.currentTimeMillis() - eqTouch > 3000) {
                JSONArray bands = e.optJSONArray("bands");
                float[] vals = new float[(bands == null ? 0 : bands.length()) + 1];
                vals[0] = (float) e.optDouble("preamp");
                for (int i = 1; i < vals.length; i++) vals[i] = (float) bands.optJSONObject(i - 1).optDouble("v");
                eq.setValues(vals);
            }
        }
        setIf(rateBtn, "⏩  " + T.t("Playback speed: ") + S.optDouble("rate", 1) + "x");
        setIf(sleepBtn, "💤  " + T.t("Sleep timer: ") + S.optString("sleep", T.t("Off")));
        setIf(hwBtn, "⚙  " + T.t("Decoder: ") + (S.optInt("hw") == 1 ? T.t("Software") : T.t("Hardware (auto)")));
        setIf(cacheBtn, "🌐  " + T.t("Network buffer: ") + (S.optInt("cache", 2000) / 1000f) + " s");
        setIf(nightBtn, "🗣  " + T.t("Clear dialogue (quiet voices, loud music)") + ": " + PlayerActivity.dialogName(S.optInt("dialog")));
        setIf(levelBtn, "📊  " + T.t("Volume levelling") + ": " + T.t(S.optBoolean("level") ? "On" : "Off"));
        setIf(afrBtn, "🎞  " + T.t("Auto frame-rate (smooth 24p)") + ": " + T.t(S.optBoolean("afr") ? "On" : "Off"));
        setIf(devLine, dev + " · " + ip + (S.optString("profile").isEmpty() ? "" : " · " + S.optString("profile")));
    }

    private String trackName(String listKey, String selKey) {
        JSONArray t = S.optJSONArray(listKey);
        int sel = S.optInt(selKey, -1);
        if (t != null) for (int i = 0; i < t.length(); i++) {
            if (t.optJSONObject(i).optInt("id") == sel) return t.optJSONObject(i).optString("name");
        }
        return S.optBoolean("player") ? T.t("Off") : "—";
    }
}
