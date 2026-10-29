package com.mytv.player;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Whiteboards kept on the phone (for later) and exports: PDF, Word (.docx), PNG pictures — shared or saved to Downloads. */
public final class BoardFiles {
    private BoardFiles() {
    }

    // ------------------------------------------------------------------ saved boards
    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "boards");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** doc: {id, title, paper, modified, pages: [[element, …], …]} */
    public static void save(Context c, JSONObject doc) {
        try {
            doc.put("modified", System.currentTimeMillis());
            File tmp = new File(dir(c), doc.getString("id") + ".tmp");
            try (FileOutputStream o = new FileOutputStream(tmp)) {
                o.write(doc.toString().getBytes("UTF-8"));
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(new File(dir(c), doc.getString("id") + ".json"));       // never a half-written board
        } catch (Exception ignored) {
        }
    }

    public static JSONObject load(Context c, String id) {
        try (InputStream in = new FileInputStream(new File(dir(c), id + ".json"))) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return new JSONObject(o.toString("UTF-8"));
        } catch (Exception e) {
            return null;
        }
    }

    /** Newest first. */
    public static List<JSONObject> list(Context c) {
        List<JSONObject> out = new ArrayList<>();
        File[] fs = dir(c).listFiles((d, n) -> n.endsWith(".json"));
        if (fs != null) for (File f : fs) {
            JSONObject j = load(c, f.getName().replace(".json", ""));
            if (j != null) out.add(j);
        }
        Collections.sort(out, (a, b) -> Long.compare(b.optLong("modified"), a.optLong("modified")));
        return out;
    }

    public static void delete(Context c, String id) {
        //noinspection ResultOfMethodCallIgnored
        new File(dir(c), id + ".json").delete();
    }

    public static int countEls(JSONObject doc) {
        int n = 0;
        JSONArray pages = doc.optJSONArray("pages");
        if (pages != null) for (int i = 0; i < pages.length(); i++) n += pages.optJSONArray(i) == null ? 0 : pages.optJSONArray(i).length();
        return n;
    }

    // ------------------------------------------------------------------ exports
    /** Draws page i (paper or slide + drawing) into a w×h picture. */
    public interface Pages {
        int count();

        Bitmap page(int i, int w, int h);
    }

    private static File outDir(Context c) {
        File d = new File(c.getCacheDir(), "export");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    static String safe(String s) {
        String t = (s == null ? "" : s).replaceAll("[\\\\/:*?\"<>|]+", " ").trim();
        return t.isEmpty() ? "Whiteboard" : t;
    }

    public static File pdf(Context c, Pages p, String title) throws Exception {
        PdfDocument doc = new PdfDocument();
        int W = 1600, H = 900;
        for (int i = 0; i < p.count(); i++) {
            Bitmap b = p.page(i, W, H);
            PdfDocument.Page pg = doc.startPage(new PdfDocument.PageInfo.Builder(842, 474, i + 1).create());   // 16:9, A4 width
            Canvas cv = pg.getCanvas();
            cv.drawBitmap(b, new Rect(0, 0, b.getWidth(), b.getHeight()), new Rect(0, 0, 842, 474), null);
            doc.finishPage(pg);
            b.recycle();
        }
        File f = new File(outDir(c), safe(title) + ".pdf");
        try (FileOutputStream o = new FileOutputStream(f)) {
            doc.writeTo(o);
        }
        doc.close();
        return f;
    }

    public static File docx(Context c, Pages p, String title) throws Exception {
        List<byte[]> pngs = new ArrayList<>();
        for (int i = 0; i < p.count(); i++) {
            Bitmap b = p.page(i, 1600, 900);
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, o);
            b.recycle();
            pngs.add(o.toByteArray());
        }
        File f = new File(outDir(c), safe(title) + ".docx");
        DocxWriter.write(f.getAbsolutePath(), title, pngs, 1600, 900);
        return f;
    }

    public static List<File> pngs(Context c, Pages p, String title) throws Exception {
        List<File> out = new ArrayList<>();
        for (int i = 0; i < p.count(); i++) {
            Bitmap b = p.page(i, 1920, 1080);
            File f = new File(outDir(c), safe(title) + (p.count() > 1 ? " - " + (i + 1) : "") + ".png");
            try (FileOutputStream o = new FileOutputStream(f)) {
                b.compress(Bitmap.CompressFormat.PNG, 100, o);
            }
            b.recycle();
            out.add(f);
        }
        return out;
    }

    static String mime(File f) {
        String n = f.getName().toLowerCase(java.util.Locale.ROOT);
        return n.endsWith(".pdf") ? "application/pdf" : n.endsWith(".docx")
                ? "application/vnd.openxmlformats-officedocument.wordprocessingml.document" : "image/png";
    }

    /** Share to other apps (WhatsApp, Gmail, Drive …). */
    public static void share(Activity a, List<File> files) {
        ArrayList<Uri> uris = new ArrayList<>();
        for (File f : files) uris.add(androidx.core.content.FileProvider.getUriForFile(a, a.getPackageName() + ".files", f));
        Intent i;
        if (uris.size() == 1) i = new Intent(Intent.ACTION_SEND).setType(mime(files.get(0))).putExtra(Intent.EXTRA_STREAM, uris.get(0));
        else i = new Intent(Intent.ACTION_SEND_MULTIPLE).setType(mime(files.get(0))).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        a.startActivity(Intent.createChooser(i, T.t("Share")));
    }

    /** A copy in Downloads/ZH Play. */
    public static void saveToDownloads(Context c, List<File> files) throws Exception {
        for (File f : files) {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, f.getName());
                v.put(MediaStore.Downloads.MIME_TYPE, mime(f));
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ZH Play");
                Uri dest = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (dest == null) throw new Exception("can't create the file");
                try (OutputStream o = c.getContentResolver().openOutputStream(dest); InputStream in = new FileInputStream(f)) {
                    copy(in, o);
                }
            } else {
                File d = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ZH Play");
                //noinspection ResultOfMethodCallIgnored
                d.mkdirs();
                try (OutputStream o = new FileOutputStream(new File(d, f.getName())); InputStream in = new FileInputStream(f)) {
                    copy(in, o);
                }
            }
        }
    }

    private static void copy(InputStream in, OutputStream o) throws Exception {
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
    }
}
