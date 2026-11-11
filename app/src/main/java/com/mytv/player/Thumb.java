package com.mytv.player;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pictures for notifications and broadcasts:
 *   - automatic: YouTube → the video's thumbnail; Google Drive → Drive's preview image
 *   - a web image link pasted by the admin
 *   - a gallery picture: shrunk to ~640×360 JPEG (~30–50 KB) and kept in Firebase at pub/thumbs/{key}
 *     (referenced as "thumb:{key}") — no extra service, no rule changes
 * Every picture is downloaded once and cached on the device (memory + disk).
 */
final class Thumb {
    private Thumb() {
    }

    private static final Pattern YT = Pattern.compile("(?:youtu\\.be/|youtube\\.com/(?:watch\\?(?:.*&)?v=|live/|shorts/|embed/))([A-Za-z0-9_-]{11})");
    private static final Pattern DRIVE = Pattern.compile("drive\\.google\\.com/(?:file/d/|open\\?id=|uc\\?(?:.*&)?id=)([A-Za-z0-9_-]{10,})|[?&]id=([A-Za-z0-9_-]{20,})");

    /** A picture found from the link itself, or "". */
    static String auto(String link) {
        if (link == null) return "";
        Matcher m = YT.matcher(link);
        if (m.find()) return "https://img.youtube.com/vi/" + m.group(1) + "/hqdefault.jpg";
        if (link.contains("drive.google.com") || link.contains("drive.usercontent.google.com")) {
            Matcher d = DRIVE.matcher(link);
            if (d.find()) return "https://drive.google.com/thumbnail?id=" + (d.group(1) != null ? d.group(1) : d.group(2)) + "&sz=w640";
        }
        return "";
    }

    /** A gallery picture → small JPEG as base64 (~640×360). */
    static String encode(Context c, Uri uri) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, o);
        }
        int s = 1;
        while (o.outWidth / (s * 2) >= 640 || o.outHeight / (s * 2) >= 640) s *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = s;
        Bitmap b;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            b = BitmapFactory.decodeStream(in, null, o2);
        }
        if (b == null) throw new Exception("not a picture");
        float k = Math.min(640f / b.getWidth(), 640f / b.getHeight());
        if (k < 1f) b = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * k), Math.round(b.getHeight() * k), true);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 75, bo);
        return android.util.Base64.encodeToString(bo.toByteArray(), android.util.Base64.NO_WRAP);
    }

    /** Admin: stores a gallery picture in Firebase → "thumb:{key}". Blocking. */
    static String upload(String base64) throws Exception {
        String key = Broadcast.newId();
        Cloud.http("PUT", Broadcast.url("/pub/thumbs/" + key), org.json.JSONObject.quote(base64));
        return "thumb:" + key;
    }

    /** A web image (usable in system notifications and on the TV home screen), or "". */
    static String web(String img) {
        return img != null && img.startsWith("https://") ? img : "";
    }

    // ------------------------------------------------------------------ loading

    private static final LruCache<String, Bitmap> mem = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override
        protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount();
        }
    };
    private static final ExecutorService pool = Executors.newFixedThreadPool(3);

    /** Shows the picture in the view (placeholder stays if there's none / it fails). */
    /** A cached picture if it's already loaded, else null (never triggers a fetch — pure peek). */
    static Bitmap cached(String img) {
        return img == null || img.isEmpty() ? null : mem.get(img);
    }

    /** Starts loading a picture into the shared cache if it isn't there yet (no ImageView needed). */
    static void prefetch(final Context c, final String img) {
        if (img == null || img.isEmpty() || mem.get(img) != null) return;
        final Context app = c.getApplicationContext();
        pool.execute(() -> {
            Bitmap b = fetch(app, img);
            if (b != null) mem.put(img, b);
        });
    }

    static void load(final ImageView iv, final String img) {
        if (img == null || img.isEmpty()) return;
        iv.setTag(img);
        Bitmap hit = mem.get(img);
        if (hit != null) {
            iv.setImageBitmap(hit);
            return;
        }
        final Context c = iv.getContext().getApplicationContext();
        pool.execute(() -> {
            Bitmap b = fetch(c, img);
            if (b == null) return;
            mem.put(img, b);
            iv.post(() -> {
                if (img.equals(iv.getTag())) iv.setImageBitmap(b);
            });
        });
    }

    private static Bitmap fetch(Context c, String img) {
        File f = new File(c.getCacheDir(), "zhimg/" + Integer.toHexString(img.hashCode()) + ".jpg");
        try {
            if (f.length() > 0) return BitmapFactory.decodeFile(f.getPath());
            byte[] data;
            if (img.startsWith("thumb:")) {
                String s = Cloud.http("GET", Broadcast.url("/pub/thumbs/" + img.substring(6)), null);
                if (s == null || s.trim().equals("null")) return null;
                String b64 = new org.json.JSONTokener(s).nextValue().toString();
                data = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
            } else if (img.startsWith("http")) {
                HttpURLConnection h = (HttpURLConnection) new URL(img).openConnection();
                h.setConnectTimeout(10000);
                h.setReadTimeout(15000);
                h.setInstanceFollowRedirects(true);
                h.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) ZHPlay");
                try (InputStream in = h.getInputStream()) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0 && bo.size() < 4 * 1024 * 1024) bo.write(buf, 0, n);
                    data = bo.toByteArray();
                }
            } else return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int s = 1;
            while (o.outWidth / (s * 2) >= 720) s *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = s;
            Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (b == null) return null;
            //noinspection ResultOfMethodCallIgnored
            f.getParentFile().mkdirs();
            try (FileOutputStream out = new FileOutputStream(f)) {
                b.compress(Bitmap.CompressFormat.JPEG, 85, out);
            }
            return b;
        } catch (Throwable e) {
            return null;
        }
    }
}
