package com.mytv.player;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Folders where kept / downloaded torrents live (searched by the Library). */
final class TorrentEngineRoots {
    private TorrentEngineRoots() {
    }

    static List<File> list(Context c) {
        List<File> out = new ArrayList<>();
        List<File> base = new ArrayList<>();
        base.add(c.getCacheDir());
        File[] ext = c.getExternalCacheDirs();
        if (ext != null) for (File e : ext) if (e != null) base.add(e);
        for (File b : base) {
            File t = new File(b, "torrents");
            if (!t.exists()) continue;
            File[] k = t.listFiles();
            if (k != null) for (File d : k) if (d.isDirectory() && Store.getBool("tkeep_" + d.getName(), false)) out.add(d);
        }
        return out;
    }
}
