package com.mytv.player;

import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Understands a spoken sentence before acting on it:
 * "play aura 10/10 song in YouTube"  → play "aura 10/10 song" on YouTube (directly, no search screen)
 * "play Leo songs on Spotify"        → start playing in Spotify / YouTube Music
 * "play Kanguva in Netflix"          → Netflix's search with "Kanguva" already typed
 * "open Hotstar" / "Netflix open pannu" → open the app
 * "play Sun TV" (no app)             → your ZH Play playlists first, else YouTube
 * English, Tamil and Tanglish words. parse() runs anywhere; run() runs on the TV.
 */
final class VoiceCmd {
    String action = "play";        // play | search | open
    String app;                    // key from APPS, or null
    String appLabel = "";
    String title = "";
    String other;                  // "open <name>" for an app that isn't in APPS
    boolean verb, named;

    /** key, name as in AppsHub, then the words people say for it (longer first). */
    static final String[][] APPS = {
            {"ytmusic", "YouTube Music", "youtube music", "you tube music", "yt music", "யூடியூப் மியூசிக்"},
            {"youtube", "YouTube", "youtube", "you tube", "யூடியூப்"},
            {"spotify", "Spotify", "spotify", "ஸ்பாட்டிஃபை"},
            {"netflix", "Netflix", "netflix", "net flix", "நெட்ஃபிளிக்ஸ்"},
            {"prime", "Prime Video", "amazon prime video", "prime video", "amazon prime", "amazon video", "prime"},
            {"hotstar", "JioHotstar", "jio hotstar", "jiohotstar", "disney hotstar", "hotstar", "ஹாட்ஸ்டார்"},
            {"sonyliv", "SonyLIV", "sony liv", "sonyliv"},
            {"zee5", "ZEE5", "zee 5", "zee5", "zee five"},
            {"zhplay", "ZH Play", "zh play", "zhplay", "zh player"},
    };

    private static final String PREP = "(?:\\s(?:in|on|using|with|via|from|la|le|il|lla|ல|இல்))?";
    private static final Pattern SEARCH = Pattern.compile("\\s(search for|search|find|look for|thedu|thedi|தேடு)(?=\\s)");
    private static final Pattern OPEN = Pattern.compile("\\s(open|launch|thira|thirai|திற|திறந்து)(?=\\s)");
    private static final Pattern PLAY = Pattern.compile("\\s(play|watch|listen to|listen|put on|start|podu|poduga|pottu|போடு|ஓட்டு|paadu|kelu)(?=\\s)");
    private static final Pattern FILLER = Pattern.compile("\\s(please|pls|plz|can you|could you|for me|now|the app|app|pannu|panna|pannunga)(?=\\s)");
    private static final Pattern MOVIE = Pattern.compile("\\s(movie|film|series|web series|show|padam|படம்)(?=\\s)");

    static VoiceCmd parse(String said) {
        VoiceCmd v = new VoiceCmd();
        String t = " " + (said == null ? "" : said).toLowerCase(Locale.ROOT)
                .replaceAll("[,.!?\"“”]", " ").replaceAll("\\s+", " ") + " ";
        t = t.replaceAll(" (hey|ok|okay) (google|zh play) ", " ");
        outer:
        for (String[] a : APPS) {
            for (int i = 2; i < a.length; i++) {
                Matcher m = Pattern.compile(PREP + "\\s" + Pattern.quote(a[i]) + "(?:\\s(?:app|la|le|il|lla))?(?=\\s)").matcher(t);
                if (m.find()) {
                    v.app = a[0];
                    v.appLabel = a[1];
                    v.named = true;
                    t = t.substring(0, m.start()) + " " + t.substring(m.end());
                    break outer;
                }
            }
        }
        if (SEARCH.matcher(t).find()) {
            v.action = "search";
            v.verb = true;
        } else if (OPEN.matcher(t).find()) {
            v.action = "open";
            v.verb = true;
        } else if (PLAY.matcher(t).find()) {
            v.verb = true;
        }
        t = SEARCH.matcher(t).replaceAll(" ");
        t = OPEN.matcher(t).replaceAll(" ");
        t = PLAY.matcher(t).replaceAll(" ");
        t = FILLER.matcher(t).replaceAll(" ");
        if (v.app != null && v.app.matches("netflix|prime|hotstar|sonyliv|zee5")) t = MOVIE.matcher(" " + t + " ").replaceAll(" ");
        v.title = t.replaceAll("\\s+", " ").trim();
        if (v.title.isEmpty() && v.named) v.action = "open";
        if ("open".equals(v.action) && !v.named && !v.title.isEmpty()) {
            v.other = v.title;                       // "open MX Player": an app by its name on the TV
            v.title = "";
        }
        if (!v.named && !"open".equals(v.action)) {
            v.app = "youtube";                       // nothing named: ZH Play playlists first, else YouTube
            v.appLabel = "YouTube";
        }
        return v;
    }

    /** Clear enough to act on at once (an app was named, or play / search / open plus what). */
    boolean explicit() {
        return named || (verb && !title.isEmpty()) || other != null;
    }

    String label() {
        if ("open".equals(action)) return "📱 " + T.t("Open") + " " + (named ? appLabel : other);
        if ("search".equals(action)) return "🔍 " + T.t("Search") + " “" + title + "” " + T.t("in") + " " + appLabel;
        return "▶ " + T.t("Play") + " “" + title + "”" + (named ? " " + T.t("on") + " " + appLabel : "");
    }

    // ------------------------------------------------------------------ on the TV

    /** Carries out the sentence on the TV. A message; failures start with "✗ ". Blocking (network). */
    static String run(Context c, String said) {
        VoiceCmd v = parse(said);
        if ("open".equals(v.action)) {
            if ("zhplay".equals(v.app)) {
                Remote.openHome(c);
                return "Opening ZH Play";
            }
            String pkg = v.named ? AppsHub.pkgFor(c, v.appLabel) : findApp(c, v.other);
            if (pkg == null) return "✗ " + T.t("No app called") + " “" + (v.named ? v.appLabel : v.other) + "” " + T.t("on the TV");
            if (TvSearch.openAppAdb(c, pkg)) return "Opening " + AppsHub.label(c, pkg);
            return AppsHub.launch(c, pkg);
        }
        if (v.title.isEmpty()) return "✗ " + T.t("Say what to play — e.g. “play Aura song on YouTube”");
        switch (v.app) {
            case "zhplay": {
                String r = TvSearch.channel(c, v.title);
                return r != null ? r : "✗ " + T.t("Nothing in your playlists matches") + " “" + v.title + "”";
            }
            case "youtube": {
                if (!v.named && "play".equals(v.action)) {           // "play Sun TV": your channels first
                    String r = TvSearch.channel(c, v.title);
                    if (r != null) return r;
                }
                return "search".equals(v.action) ? TvSearch.youtube(c, v.title) : TvSearch.youtubePlay(c, v.title);
            }
            default: {
                String pkg = AppsHub.pkgFor(c, v.appLabel);
                if (pkg == null) return "✗ " + v.appLabel + " " + T.t("isn't installed on the TV");
                if ("play".equals(v.action) && v.app.matches("ytmusic|spotify") && playFromSearch(c, pkg, v.title)) {
                    return "Playing on " + v.appLabel + ": " + v.title;
                }
                return appSearch(c, pkg, v.appLabel, v.title);
            }
        }
    }

    /** Music apps start playing the best match themselves (Android's "play from search"). */
    private static boolean playFromSearch(Context c, String pkg, String q) {
        String focus = "vnd.android.cursor.item/*";
        return TvSearch.launch(c, "-a android.media.action.MEDIA_PLAY_FROM_SEARCH -p " + pkg
                        + " --es query " + TvSearch.sh(q) + " --es android.intent.extra.focus " + TvSearch.sh(focus),
                new Intent("android.media.action.MEDIA_PLAY_FROM_SEARCH").setPackage(pkg)
                        .putExtra(SearchManager.QUERY, q).putExtra("android.intent.extra.focus", focus));
    }

    /** Video apps (Netflix, Prime…) don't let other apps start a title: open their search with it typed. */
    private static String appSearch(Context c, String pkg, String label, String q) {
        if (TvSearch.launch(c, "-a android.intent.action.SEARCH -p " + pkg + " --es query " + TvSearch.sh(q),
                new Intent(Intent.ACTION_SEARCH).setPackage(pkg).putExtra(SearchManager.QUERY, q).putExtra("query", q))) {
            return label + ": " + T.t("searching") + " “" + q + "” — " + T.t("pick it to play");
        }
        if (TvSearch.openAppAdb(c, pkg)) return T.t("Opened") + " " + label + " — " + T.t("search for") + " “" + q + "” " + T.t("there");
        String r = AppsHub.launch(c, pkg);
        return r.startsWith("Opening") ? T.t("Opened") + " " + label + " — " + T.t("search for") + " “" + q + "” " + T.t("there") : r;
    }

    /** An installed app whose name matches what was said ("open MX Player"). */
    private static String findApp(Context c, String name) {
        if (name == null || name.isEmpty()) return null;
        String n = name.toLowerCase(Locale.ROOT);
        String best = null;
        for (AppsHub.App a : AppsHub.choices(c)) {
            if (!a.installed) continue;
            String l = a.name.toLowerCase(Locale.ROOT);
            if (l.equals(n)) return a.pkg;
            if (best == null && (l.startsWith(n) || l.contains(n) || n.contains(l))) best = a.pkg;
        }
        return best;
    }
}
