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

    /** May TamilCastPlay open another app right now? (Android blocks this from the background.) */
    private static boolean canStartDirectly(Context c) {
        if (App.visible > 0) return true;
        return android.os.Build.VERSION.SDK_INT < 29
                || (android.os.Build.VERSION.SDK_INT >= 23 && android.provider.Settings.canDrawOverlays(c));
    }

    /** Opens something on the TV: through ADB if on (always allowed, and tells us if it worked), else directly. */
    private static boolean launch(Context c, String amArgs, Intent direct) {
        if (AdbClient.enabled()) {
            try {
                String out = AdbClient.shell(c, "am start " + amArgs + " 2>&1");
                if (out != null && !out.contains("Error") && !out.contains("Exception") && !out.contains("unable to resolve")) return true;
            } catch (Exception ignored) {
            }
        }
        return canStartDirectly(c) && start(c, direct);
    }

    private static final String CANT = "Couldn't open it on the TV. Turn on the Full remote (ADB) in TV Settings, "
            + "or allow \"Display over other apps\" for TamilCastPlay.";

    private static String ytPkg(Context c) {
        for (String p : YT) if (AppsHub.installed(c, p)) return p;
        return null;
    }

    /** YouTube's search results screen. */
    public static String youtube(Context c, String q) {
        String p = ytPkg(c);
        if (p == null) return "YouTube is not installed on the TV";
        String url = "https://www.youtube.com/results?search_query=" + Uri.encode(q);
        if (launch(c, "-a android.intent.action.SEARCH -p " + p + " --es query " + sh(q),
                new Intent(Intent.ACTION_SEARCH).setPackage(p).putExtra(SearchManager.QUERY, q).putExtra("query", q))) {
            return "YouTube search: " + q;
        }
        if (launch(c, "-a android.intent.action.VIEW -d " + sh(url) + " -p " + p,
                new Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(p))) {
            return "YouTube search: " + q;
        }
        return CANT;
    }

    /** Finds the first matching video and plays it in YouTube ("YouTube Ilaiyaraaja songs podu"). */
    public static String youtubePlay(Context c, String q) {
        String p = ytPkg(c);
        if (p == null) return "YouTube is not installed on the TV";
        String[] v = firstVideo(q);
        if (v == null) return youtube(c, q);
        String url = "https://www.youtube.com/watch?v=" + v[0];
        if (launch(c, "-a android.intent.action.VIEW -d " + sh(url) + " -p " + p,
                new Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(p))) {
            return "Playing on YouTube: " + (v[1].isEmpty() ? q : v[1]);
        }
        return CANT;
    }

    /** {videoId, title} of YouTube's first result, or null. */
    static String[] firstVideo(String q) {
        try {
            java.net.HttpURLConnection con = (java.net.HttpURLConnection) new java.net.URL(
                    "https://www.youtube.com/results?search_query=" + Uri.encode(q) + "&hl=en").openConnection();
            con.setConnectTimeout(8000);
            con.setReadTimeout(12000);
            con.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Safari/537.36");
            con.setRequestProperty("Accept-Language", "en");
            StringBuilder sb = new StringBuilder();
            try (java.io.InputStream in = con.getInputStream()) {
                byte[] b = new byte[32768];
                int n;
                while ((n = in.read(b)) > 0 && sb.length() < 2_500_000) sb.append(new String(b, 0, n, "UTF-8"));
            }
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "\"videoRenderer\":\\{\"videoId\":\"([A-Za-z0-9_-]{11})\".{0,1500}?\"title\":\\{\"runs\":\\[\\{\"text\":\"([^\"]{1,200})\"")
                    .matcher(sb);
            if (m.find()) return new String[]{m.group(1), m.group(2)};
            java.util.regex.Matcher m2 = java.util.regex.Pattern.compile("\"videoId\":\"([A-Za-z0-9_-]{11})\"").matcher(sb);
            if (m2.find()) return new String[]{m2.group(1), ""};
        } catch (Exception ignored) {
        }
        return null;
    }

    /** The TV's own search (Google TV / Android TV / Assistant results). */
    public static String everywhere(Context c, String q) {
        if (launch(c, "-a android.search.action.GLOBAL_SEARCH --es query " + sh(q),
                new Intent("android.search.action.GLOBAL_SEARCH").putExtra(SearchManager.QUERY, q))) {
            return "Search: " + q;
        }
        return youtube(c, q);
    }

    /** Opens an app even when TamilCastPlay is in the background (through ADB). */
    public static boolean openAppAdb(Context c, String pkg) {
        if (!AdbClient.enabled()) return false;
        try {
            String out = AdbClient.shell(c, "monkey -p " + pkg + " -c android.intent.category.LEANBACK_LAUNCHER 1 2>&1");
            if (out != null && out.contains("Events injected")) return true;
            out = AdbClient.shell(c, "monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1 2>&1");
            return out != null && out.contains("Events injected");
        } catch (Exception e) {
            return false;
        }
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
