package com.mytv.player;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Share a link, video, song or .torrent from any app -> "Play on TV". */
public class ShareActivity extends AppCompatActivity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<String> urls = new ArrayList<>(), titles = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Ui.apply(this);
        setTheme(Ui.dark() ? R.style.ShareTheme : R.style.ShareTheme_Light);
        super.onCreate(savedInstanceState);
        RemoteService.start(this);
        if (!collect(getIntent())) {
            Toast.makeText(this, T.t("Nothing to play in what was shared"), Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        Ui.isTv(this);
        findTv();
    }

    private static final Pattern LINK = Pattern.compile("(magnet:\\?[^\\s]+|https?://[^\\s]+|rtsp://[^\\s]+)");

    private boolean collect(Intent in) {
        String action = in.getAction();
        ArrayList<Uri> streams = new ArrayList<>();
        if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> l = in.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (l != null) streams.addAll(l);
        } else {
            Uri u = in.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) streams.add(u);
        }
        for (Uri u : streams) {
            String name = LocalShare.displayName(this, u);
            if ("application/x-bittorrent".equals(in.getType()) && !name.toLowerCase().endsWith(".torrent")) name += ".torrent";
            urls.add(LocalShare.share(this, u, name));
            titles.add(name);
        }
        String text = in.getStringExtra(Intent.EXTRA_TEXT);
        if (streams.isEmpty() && text != null) {
            Matcher m = LINK.matcher(text);
            if (m.find()) {
                urls.add(m.group(1));
                String subj = in.getStringExtra(Intent.EXTRA_SUBJECT);
                titles.add(subj != null ? subj : Formats.titleFromUrl(m.group(1)));
            }
        }
        return !urls.isEmpty();
    }

    private void findTv() {
        final String last = Store.getStr("last_tv", "");
        final Map<String, Discovery.Device> found = new LinkedHashMap<>();
        Toast.makeText(this, T.t("Looking for your TV…"), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            Discovery.Device d = last.isEmpty() ? null : Discovery.ping(last, 1500);
            if (d != null) {
                main.post(() -> ask(d, found));
                return;
            }
            for (Discovery.Device x : Discovery.scanBlocking(6000)) found.put(x.ip, x);
            main.post(() -> choose(found));
        }).start();
    }

    private void choose(final Map<String, Discovery.Device> found) {
        if (isFinishing()) return;
        if (found.isEmpty()) {
            new AlertDialog.Builder(this).setTitle(T.t("No TV found"))
                    .setMessage(T.t("Turn on the TV, open TamilCastPlay once, and make sure the phone is on the same Wi-Fi."))
                    .setPositiveButton(T.t("Play on this phone"), (d, w) -> playHere())
                    .setNegativeButton(T.t("Close"), null)
                    .setOnDismissListener(d -> finish()).show();
            return;
        }
        final List<Discovery.Device> list = new ArrayList<>(found.values());
        String[] names = new String[list.size()];
        for (int i = 0; i < names.length; i++) names[i] = "📺  " + list.get(i).name + "   (" + list.get(i).ip + ")";
        new AlertDialog.Builder(this).setTitle(T.t("Play on which TV?"))
                .setItems(names, (d, w) -> ask(list.get(w), found))
                .setOnCancelListener(d -> finish()).show();
    }

    private void ask(final Discovery.Device tv, final Map<String, Discovery.Device> found) {
        if (isFinishing()) return;
        String what = titles.size() == 1 ? titles.get(0) : titles.size() + " " + T.t("files");
        new AlertDialog.Builder(this).setTitle("📺  " + tv.name)
                .setMessage(what)
                .setPositiveButton(T.t("▶ Play now"), (d, w) -> send(tv, "now"))
                .setNeutralButton(T.t("+ Queue"), (d, w) -> send(tv, "queue"))
                .setNegativeButton(T.t("Other TV…"), (d, w) -> {
                    if (found.isEmpty()) {
                        new Thread(() -> {
                            for (Discovery.Device x : Discovery.scanBlocking(6000)) found.put(x.ip, x);
                            main.post(() -> choose(found));
                        }).start();
                    } else choose(found);
                })
                .setOnCancelListener(d -> finish()).show();
    }

    private void send(final Discovery.Device tv, final String mode) {
        new Thread(() -> {
            StringBuilder u = new StringBuilder(), t = new StringBuilder();
            for (int i = 0; i < urls.size(); i++) {
                u.append(urls.get(i)).append('\n');
                t.append(titles.get(i)).append('\n');
            }
            HashMap<String, String> p = new HashMap<>();
            p.put("urls", u.toString());
            p.put("titles", t.toString());
            p.put("mode", mode);
            try {
                JSONObject r = TvClient.post(tv.ip, "/playmany", p);
                Store.putStr("last_tv", tv.ip);
                final String msg = r.optString("msg", r.optBoolean("ok") ? "Sent" : "Failed");
                main.post(() -> {
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                    finish();
                });
            } catch (TvClient.AuthNeeded e) {
                main.post(() -> askPin(tv, mode));
            } catch (Exception e) {
                main.post(() -> {
                    Toast.makeText(this, T.t("Cannot reach the TV"), Toast.LENGTH_LONG).show();
                    finish();
                });
            }
        }).start();
    }

    private void askPin(final Discovery.Device tv, final String mode) {
        final EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setHint("1234");
        new AlertDialog.Builder(this).setTitle(T.t("Enter the PIN shown on the TV"))
                .setView(e)
                .setPositiveButton(T.t("OK"), (d, w) -> new Thread(() -> {
                    String err = TvClient.pair(tv.ip, e.getText().toString());
                    main.post(() -> {
                        if (err == null) send(tv, mode);
                        else {
                            Toast.makeText(this, err, Toast.LENGTH_LONG).show();
                            askPin(tv, mode);
                        }
                    });
                }).start())
                .setOnCancelListener(d -> finish()).show();
    }

    private void playHere() {
        List<Store.Item> items = new ArrayList<>();
        for (int i = 0; i < urls.size(); i++) items.add(new Store.Item(titles.get(i), urls.get(i), ""));
        Remote.play(this, items, 0);
        finish();
    }
}
