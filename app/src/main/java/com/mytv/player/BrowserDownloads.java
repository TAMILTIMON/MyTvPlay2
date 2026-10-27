package com.mytv.player;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import org.json.JSONArray;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Browser downloads: normal links (Android's download manager), blob: / data: files, and the Downloads page. */
public final class BrowserDownloads {
    private BrowserDownloads() {
    }

    static final String FOLDER = "ZH Play";

    // ------------------------------------------------------------------ normal links
    /** Starts a download with the page's cookies / user agent / referrer (sites that need a login keep working). */
    public static void start(Activity a, String url, String userAgent, String contentDisposition, String mime, String referer) {
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            UiKit.makeToast(a, T.t("Saving…"), Toast.LENGTH_SHORT).show();
            return;     // handled by the page script (saveBlob) — see BrowserActivity
        }
        try {
            String name = URLUtil.guessFileName(url, contentDisposition, mime);
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) r.addRequestHeader("Cookie", cookie);
            if (userAgent != null) r.addRequestHeader("User-Agent", userAgent);
            if (referer != null && referer.startsWith("http")) r.addRequestHeader("Referer", referer);
            if (mime != null && !mime.isEmpty()) r.setMimeType(mime);
            r.setTitle(name);
            r.setDescription("ZH Play");
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, FOLDER + "/" + name);
            DownloadManager dm = (DownloadManager) a.getSystemService(Context.DOWNLOAD_SERVICE);
            long id = dm.enqueue(r);
            remember(id);
            UiKit.makeToast(a, T.t("Downloading") + ": " + name, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            UiKit.makeToast(a, T.t("Can't download this") + ": " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static void remember(long id) {
        try {
            JSONArray a = new JSONArray(Store.getStr("b_dl_ids", "[]"));
            a.put(id);
            JSONArray keep = new JSONArray();
            for (int i = Math.max(0, a.length() - 200); i < a.length(); i++) keep.put(a.getLong(i));
            Store.putStr("b_dl_ids", keep.toString());
        } catch (Exception ignored) {
        }
    }

    private static List<Long> ids() {
        List<Long> l = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Store.getStr("b_dl_ids", "[]"));
            for (int i = a.length() - 1; i >= 0; i--) l.add(a.getLong(i));
        } catch (Exception ignored) {
        }
        return l;
    }

    // ------------------------------------------------------------------ blob: / data: files (made by the page itself)
    /** Page script that reads a blob: / data: link and hands it to the app (ZHDl.save). */
    public static String blobJs(String url, String name) {
        String u = org.json.JSONObject.quote(url), n = org.json.JSONObject.quote(name == null ? "" : name);
        return "(function(){fetch(" + u + ").then(function(r){return r.blob();}).then(function(b){var f=new FileReader();"
                + "f.onloadend=function(){ZHDl.save(f.result," + n + ",b.type||'');};f.readAsDataURL(b);})"
                + ".catch(function(e){ZHDl.fail(String(e));});})();";
    }

    /** Saves "data:mime;base64,…" into Downloads/ZH Play. Returns the file name. */
    public static String saveData(Context c, String dataUrl, String name, String mime) throws Exception {
        int comma = dataUrl.indexOf(',');
        String head = dataUrl.substring(0, Math.max(0, comma));
        byte[] bytes = head.contains(";base64") ? Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
                : Uri.decode(dataUrl.substring(comma + 1)).getBytes("UTF-8");
        if ((mime == null || mime.isEmpty()) && head.startsWith("data:")) mime = head.substring(5).split(";")[0];
        if (name == null || name.isEmpty()) {
            String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
            name = "download_" + System.currentTimeMillis() + (ext == null ? "" : "." + ext);
        }
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, name);
            if (mime != null && !mime.isEmpty()) v.put(MediaStore.Downloads.MIME_TYPE, mime);
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER);
            Uri dest = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (dest == null) throw new Exception("can't create the file");
            try (OutputStream o = c.getContentResolver().openOutputStream(dest)) {
                o.write(bytes);
            }
        } else {
            File d = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER);
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
            try (FileOutputStream o = new FileOutputStream(new File(d, name))) {
                o.write(bytes);
            }
        }
        return name;
    }

    // ------------------------------------------------------------------ Downloads page
    public static void show(final Activity a) {
        final DownloadManager dm = (DownloadManager) a.getSystemService(Context.DOWNLOAD_SERVICE);
        final LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(Ui.dp(a, 16), Ui.dp(a, 4), Ui.dp(a, 16), 0);
        ScrollView sv = new ScrollView(a);
        sv.addView(list);
        final AlertDialog d = new UiDialog(a).setTitle(T.t("Downloads")).setView(sv)
                .setPositiveButton(T.t("Open folder"), (x, w) -> {
                    try {
                        a.startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS));
                    } catch (Exception ignored) {
                    }
                })
                .setNegativeButton(T.t("Close"), null).show();
        final Handler h = new Handler(Looper.getMainLooper());
        h.post(new Runnable() {
            public void run() {
                if (!d.isShowing()) return;
                fill(a, dm, list);
                h.postDelayed(this, 1000);
            }
        });
    }

    private static void fill(final Activity a, final DownloadManager dm, LinearLayout list) {
        list.removeAllViews();
        List<Long> ids = ids();
        if (ids.isEmpty()) {
            TextView t = Ui.text(a, 14, Ui.DIM, false);
            t.setText(T.t("Nothing downloaded yet. Long-press a link (or tap a download button on a page) → Download."));
            list.addView(t);
            return;
        }
        long[] arr = new long[ids.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = ids.get(i);
        Cursor c = dm.query(new DownloadManager.Query().setFilterById(arr));
        if (c == null) return;
        try {
            List<Object[]> rows = new ArrayList<>();
            while (c.moveToNext()) {
                rows.add(new Object[]{c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)),
                        c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)),
                        c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                        c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                        c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))});
            }
            java.util.Collections.sort(rows, (x, y) -> Long.compare((Long) y[0], (Long) x[0]));
            for (Object[] r : rows) list.addView(row(a, dm, r));
        } finally {
            c.close();
        }
    }

    private static android.view.View row(final Activity a, final DownloadManager dm, Object[] r) {
        final long id = (Long) r[0];
        int st = (Integer) r[2];
        long got = (Long) r[3], total = (Long) r[4];
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(a, 10), Ui.dp(a, 8), Ui.dp(a, 10), Ui.dp(a, 8));
        box.setBackground(Ui.focusCard(a, 12));
        TextView t = Ui.text(a, 14, Ui.TEXT, true);
        t.setText((String) r[1]);
        t.setMaxLines(2);
        box.addView(t);
        String state;
        switch (st) {
            case DownloadManager.STATUS_SUCCESSFUL: state = "✓ " + T.t("Done") + " · " + TvInfo.gb(total); break;
            case DownloadManager.STATUS_FAILED: state = "⚠ " + T.t("Failed") + " — " + T.t("tap to retry"); break;
            case DownloadManager.STATUS_PAUSED: state = T.t("Waiting for the network…"); break;
            case DownloadManager.STATUS_PENDING: state = T.t("Starting…"); break;
            default:
                state = total > 0 ? String.format(Locale.US, "%d %% · %s / %s", got * 100 / total, TvInfo.gb(got), TvInfo.gb(total))
                        : TvInfo.gb(got);
        }
        TextView s = Ui.text(a, 12, Ui.DIM, false);
        s.setText(state);
        box.addView(s);
        if (st == DownloadManager.STATUS_RUNNING && total > 0) {
            ProgressBar pb = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
            pb.setMax(1000);
            pb.setProgress((int) (got * 1000 / total));
            box.addView(pb);
        }
        final boolean done = st == DownloadManager.STATUS_SUCCESSFUL, failed = st == DownloadManager.STATUS_FAILED;
        box.setOnClickListener(v -> {
            if (done) {
                try {
                    Uri u = dm.getUriForDownloadedFile(id);
                    String mime = dm.getMimeTypeForDownloadedFile(id);
                    a.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(u, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                } catch (Exception e) {
                    UiKit.makeToast(a, T.t("No app can open this file"), Toast.LENGTH_SHORT).show();
                }
            } else if (failed) {
                retry(a, dm, id);
            }
        });
        box.setOnLongClickListener(v -> {
            new UiDialog(a).setTitle((String) r[1]).setItems(new String[]{T.t(done ? "Delete file" : "Cancel download"), T.t("Remove from list")},
                    (x, w) -> {
                        if (w == 0) dm.remove(id);
                        forget(id);
                    }).show();
            return true;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, Ui.dp(a, 3), 0, Ui.dp(a, 3));
        box.setLayoutParams(lp);
        box.setGravity(Gravity.START);
        return box;
    }

    private static void retry(Activity a, DownloadManager dm, long id) {
        Cursor c = dm.query(new DownloadManager.Query().setFilterById(id));
        try {
            if (c != null && c.moveToFirst()) {
                String uri = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_URI));
                dm.remove(id);
                forget(id);
                start(a, uri, null, null, null, null);
            }
        } finally {
            if (c != null) c.close();
        }
    }

    private static void forget(long id) {
        try {
            JSONArray a = new JSONArray(Store.getStr("b_dl_ids", "[]")), keep = new JSONArray();
            for (int i = 0; i < a.length(); i++) if (a.getLong(i) != id) keep.put(a.getLong(i));
            Store.putStr("b_dl_ids", keep.toString());
        } catch (Exception ignored) {
        }
    }
}
