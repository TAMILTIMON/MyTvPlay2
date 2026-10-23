package com.mytv.player;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Backup / restore of playlists, favourites, history and settings. */
public final class Backup {
    private Backup() {
    }

    public static String build(Context c) throws Exception {
        JSONObject o = new JSONObject();
        o.put("app", "tamilcastplay");
        o.put("v", 1);
        o.put("time", System.currentTimeMillis());
        o.put("playlists", new JSONObject(Store.toJson(Integer.MAX_VALUE)).getJSONArray("playlists"));
        o.put("settings", Store.exportSettings());
        return o.toString();
    }

    /** Returns a message. */
    public static String restore(String json) {
        try {
            JSONObject o = new JSONObject(json);
            if (!"tamilcastplay".equals(o.optString("app"))) return "This is not a TamilCastPlay backup";
            JSONArray pls = o.getJSONArray("playlists");
            int n = 0;
            for (int i = 0; i < pls.length(); i++) {
                JSONObject p = pls.getJSONObject(i);
                JSONArray items = p.getJSONArray("items");
                List<Store.Item> l = new ArrayList<>();
                for (int k = 0; k < items.length(); k++) {
                    JSONObject x = items.getJSONObject(k);
                    l.add(new Store.Item(x.optString("t"), x.optString("u"), x.optString("s"), x.optString("g"), x.optString("l")));
                }
                Store.replaceAll(p.getString("name"), l);
                n++;
            }
            if (o.has("settings")) Store.importSettings(o.getJSONObject("settings"));
            return "♻ Restored " + n + " playlists and settings";
        } catch (Exception e) {
            return "Restore failed: " + e.getMessage();
        }
    }

    public static String fileName() {
        return "TamilCastPlay-backup-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new java.util.Date()) + ".json";
    }

    /** Saves into the TV's Download folder. Returns where. */
    public static String saveToDownloads(Context c) throws Exception {
        byte[] data = build(c).getBytes("UTF-8");
        String name = fileName();
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, name);
            v.put(MediaStore.Downloads.MIME_TYPE, "application/json");
            Uri u = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (u == null) throw new Exception("Cannot write to Downloads");
            try (OutputStream o = c.getContentResolver().openOutputStream(u)) {
                if (o == null) throw new Exception("Cannot write to Downloads");
                o.write(data);
            }
            return "Download/" + name;
        }
        File d = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        File f = new File(d, name);
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(data);
        }
        return f.getAbsolutePath();
    }

    /** Backup files found in Download folders and on USB drives. */
    public static List<File> findBackups(Context c) {
        List<File> out = new ArrayList<>();
        List<File> dirs = new ArrayList<>();
        dirs.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS));
        File[] st = new File("/storage").listFiles();
        if (st != null) for (File f : st) {
            dirs.add(f);
            dirs.add(new File(f, "Download"));
        }
        for (File d : dirs) {
            File[] k = d.listFiles();
            if (k == null) continue;
            for (File f : k) if (f.getName().startsWith("TamilCastPlay-backup") && f.getName().endsWith(".json")) out.add(f);
        }
        return out;
    }

    public static String restoreFile(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            return restore(new String(b, "UTF-8"));
        } catch (Exception e) {
            return "Restore failed: " + e.getMessage();
        }
    }
}
