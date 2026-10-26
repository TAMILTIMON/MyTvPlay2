package com.mytv.player;

import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.util.List;
import java.util.Locale;

/** Voice / text search sent from the phone: YouTube, the TV's own search, or a channel in TamilCastPlay. */
public final class TvSearch {
    private TvSearch() {
    }

    private static final String[] YT = {"com.google.android.youtube.tv", "com.google.android.youtube.googletv", "com.google.android.youtube"};

    private static boolean start(Context c, Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            c.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String sh(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    public static String youtube(Context c, String q) {
        for (String p : YT) {
            if (!AppsHub.installed(c, p)) continue;
            Intent i = new Intent(Intent.ACTION_SEARCH).setPackage(p).putExtra(SearchManager.QUERY, q).putExtra("query", q);
            if (start(c, i)) return "YouTube: " + q;
            if (AdbClient.enabled()) {
                try {
                    AdbClient.shell(c, "am start -a android.intent.action.SEARCH -p " + p + " -e query " + sh(q));
                    return "YouTube: " + q;
                } catch (Exception ignored) {
                }
            }
            Intent v = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))).setPackage(p);
            if (start(c, v)) return "YouTube: " + q;
        }
        return "YouTube is not installed on the TV";
    }

    /** The TV's own search (Google TV / Android TV / Assistant results). */
    public static String everywhere(Context c, String q) {
        Intent i = new Intent("android.search.action.GLOBAL_SEARCH").putExtra(SearchManager.QUERY, q);
        if (start(c, i)) return "Search: " + q;
        if (AdbClient.enabled()) {
            try {
                AdbClient.shell(c, "am start -a android.search.action.GLOBAL_SEARCH -e query " + sh(q));
                return "Search: " + q;
            } catch (Exception ignored) {
            }
        }
        return youtube(c, q);
    }

    /** Best matching channel / video in the playlists (favourites first). */
    public static String channel(Context c, String q) {
        String ql = q.toLowerCase(Locale.ROOT).replaceAll("\\b(channel|tv|play|on|the)\\b", " ").replaceAll("\\s+", " ").trim();
        if (ql.isEmpty()) ql = q.toLowerCase(Locale.ROOT);
        String bestPl = null;
        int bestIdx = -1, best = 0;
        java.util.Set<String> favs = Store.favs();
        for (String pl : Store.names()) {
            if (!Family.playlistAllowed(pl)) continue;
            List<Store.Item> items = Store.items(pl);
            for (int i = 0; i < items.size(); i++) {
                String t = items.get(i).t.toLowerCase(Locale.ROOT);
                int sc = t.equals(ql) ? 100 : t.startsWith(ql) ? 80 : t.contains(" " + ql) ? 70 : t.contains(ql) ? 60 : 0;
                if (sc == 0) {
                    boolean all = true;
                    for (String w : ql.split(" ")) if (!w.isEmpty() && !t.contains(w)) all = false;
                    if (all) sc = 50;
                }
                if (sc == 0) continue;
                if (favs.contains(items.get(i).u)) sc += 15;
                if (sc > best) {
                    best = sc;
                    bestPl = pl;
                    bestIdx = i;
                }
            }
        }
        if (bestPl == null) return null;
        Remote.play(c, Store.items(bestPl), bestIdx);
        return "Playing " + Store.items(bestPl).get(bestIdx).t;
    }
}
