package com.mytv.player;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "Ask TamilCastPlay": a sentence (Tamil / English / Tanglish) → actions on the TV.
 * The AI only chooses from the app's existing actions; a keyword backup works without AI.
 */
public final class AiBrain {
    private AiBrain() {
    }

    public interface Tv {
        JSONObject post(String path, Map<String, String> p) throws Exception;
    }

    public interface Progress {
        void on(String s);
    }

    static Map<String, String> m(String... kv) {
        Map<String, String> r = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) r.put(kv[i], kv[i + 1]);
        return r;
    }

    private static JSONArray appsCache;
    private static long appsAt;

    /** Installed TV apps (name + pkg), cached 5 min — the full reply carries icons and is heavy. */
    static synchronized JSONArray apps(Tv tv) throws Exception {
        if (appsCache != null && System.currentTimeMillis() - appsAt < 300000) return appsCache;
        JSONArray a = tv.post("/apps", m()).optJSONArray("apps"), out = new JSONArray();
        if (a != null) for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.getJSONObject(i);
            if (x.optBoolean("installed", true)) out.put(new JSONObject().put("name", x.optString("name")).put("pkg", x.optString("pkg")));
        }
        appsCache = out;
        appsAt = System.currentTimeMillis();
        return out;
    }

    static String langName() {
        String l = Store.getStr("lang", "en");
        return l.equals("ta") ? "Tamil" : l.equals("hi") ? "Hindi" : "English";
    }

    private static String system(JSONObject state, List<String> apps, List<String> channels) {
        return "You control a TV app named TamilCastPlay. The user may write Tamil, English, Hindi or Tanglish "
                + "(Tamil written in English letters, e.g. 'Sun TV podu' = play Sun TV, 'volume kammi pannu' = lower volume, "
                + "'niruthu' = pause, 'adutha' = next).\n"
                + "Reply with ONLY one JSON object, no other text: {\"say\":\"short friendly reply in the user's language\",\"actions\":[...]}\n"
                + "Allowed actions (use only these):\n"
                + "{\"do\":\"play_channel\",\"q\":\"channel or video name\"}\n"
                + "{\"do\":\"youtube\",\"q\":\"search words\"}\n"
                + "{\"do\":\"search_tv\",\"q\":\"search words\"}\n"
                + "{\"do\":\"open_app\",\"name\":\"app name\"}\n"
                + "{\"do\":\"volume\",\"value\":0-200}  (player volume, 100 = normal)\n"
                + "{\"do\":\"volume_step\",\"by\":-40..40}\n"
                + "{\"do\":\"tv_volume\",\"dir\":\"up|down|mute\"}\n"
                + "{\"do\":\"play\"} {\"do\":\"pause\"} {\"do\":\"next\"} {\"do\":\"prev\"} {\"do\":\"stop\"}\n"
                + "{\"do\":\"seek\",\"seconds\":-3600..3600}  (relative jump)\n"
                + "{\"do\":\"sleep\",\"minutes\":0-240}  (turn off after; 0 = cancel)\n"
                + "{\"do\":\"key\",\"k\":\"up|down|left|right|ok|back|home\"}\n"
                + "{\"do\":\"type\",\"text\":\"text to type into the TV\"}\n"
                + "{\"do\":\"recap\"}  (summary of the last 10 minutes)\n"
                + "{\"do\":\"translate\",\"lang\":\"Tamil|Hindi|English|Malayalam|Telugu|Kannada\"}  (subtitles)\n"
                + "{\"do\":\"power\",\"what\":\"sleep|restart_app\"}\n"
                + "If the request is unclear, ask a short question in \"say\" and give no actions.\n"
                + "Now: " + (state.optBoolean("player") ? "playing '" + state.optString("title") + "' ("
                + (state.optBoolean("playing") ? "playing" : "paused") + "), volume " + state.optInt("vol", 100) + "%" : "nothing playing") + ".\n"
                + "Apps on the TV: " + apps + "\n"
                + "Some channels / videos in the playlists: " + channels;
    }

    /** Runs a request; returns what to show to the user. Background thread. */
    public static String ask(String said, Tv tv, Progress p) {
        JSONObject state;
        try {
            state = tv.post("/state", m());
        } catch (Exception e) {
            return "Cannot reach the TV";
        }
        JSONObject plan = null;
        String err = null;
        if (AiClient.ready()) {
            try {
                List<String> apps = new ArrayList<>(), ch = new ArrayList<>();
                try {
                    JSONArray a = apps(tv);
                    for (int i = 0; i < a.length(); i++) apps.add(a.getJSONObject(i).optString("name"));
                    JSONArray q = state.optJSONArray("queue");
                    if (q != null) for (int i = 0; i < q.length() && ch.size() < 40; i++) ch.add(q.optJSONObject(i).optString("t"));
                } catch (Exception ignored) {
                }
                if (p != null) p.on("…");
                plan = AiClient.json(AiClient.chat(system(state, apps, ch), said, 400));
            } catch (Exception e) {
                err = e.getMessage();
            }
        }
        if (plan == null) plan = keywords(said);      // no AI / AI failed / unclear answer
        if (plan == null) return err != null ? err : "Sorry, I didn't understand. Try: \"Sun TV podu\", \"volume kammi pannu\", \"pause\".";
        StringBuilder done = new StringBuilder(plan.optString("say", ""));
        JSONArray acts = plan.optJSONArray("actions");
        if (acts != null) for (int i = 0; i < acts.length() && i < 6; i++) {
            String r = run(acts.optJSONObject(i), tv, state, p);
            if (r != null && !r.isEmpty()) done.append(done.length() > 0 ? "\n" : "").append("• ").append(r);
        }
        return done.length() == 0 ? "OK" : done.toString();
    }

    private static String run(JSONObject a, Tv tv, JSONObject state, Progress p) {
        if (a == null) return null;
        try {
            String d = a.optString("do");
            switch (d) {
                case "play_channel": return msg(tv.post("/search", m("q", a.optString("q"), "where", "tcp")));
                case "youtube": return msg(tv.post("/search", m("q", a.optString("q"), "where", "youtube")));
                case "search_tv": return msg(tv.post("/search", m("q", a.optString("q"), "where", "tv")));
                case "open_app": {
                    JSONArray apps = apps(tv);
                    String want = a.optString("name").toLowerCase(Locale.ROOT);
                    if (apps != null) for (int i = 0; i < apps.length(); i++) {
                        JSONObject x = apps.getJSONObject(i);
                        if (x.optString("name").toLowerCase(Locale.ROOT).contains(want) || want.contains(x.optString("name").toLowerCase(Locale.ROOT))) {
                            return msg(tv.post("/launch", m("pkg", x.optString("pkg"))));
                        }
                    }
                    return "App not found: " + a.optString("name");
                }
                case "volume": return msg(tv.post("/cmd", m("c", "vol", "v", String.valueOf(clamp(a.optInt("value", 100), 0, 200)))));
                case "volume_step": {
                    int v = clamp(state.optInt("vol", 100) + a.optInt("by", 0), 0, 200);
                    return msg(tv.post("/cmd", m("c", "vol", "v", String.valueOf(v))));
                }
                case "tv_volume": {
                    String dir = a.optString("dir");
                    tv.post("/key", m("k", dir.equals("mute") ? "vol_mute" : dir.equals("down") ? "vol_down" : "vol_up"));
                    return "TV volume " + dir;
                }
                case "play": return msg(tv.post("/cmd", m("c", "play", "v", "")));
                case "pause": return msg(tv.post("/cmd", m("c", "pause", "v", "")));
                case "next": return msg(tv.post("/cmd", m("c", "next", "v", "")));
                case "prev": return msg(tv.post("/cmd", m("c", "prev", "v", "")));
                case "stop": return msg(tv.post("/cmd", m("c", "stop", "v", "")));
                case "seek": return msg(tv.post("/cmd", m("c", "jump", "v", String.valueOf(a.optLong("seconds") * 1000))));
                case "sleep": return msg(tv.post("/cmd", m("c", "sleep", "v", String.valueOf(clamp(a.optInt("minutes"), 0, 240)))));
                case "key": tv.post("/key", m("k", a.optString("k"))); return null;
                case "type": return msg(tv.post("/type", m("text", a.optString("text"))));
                case "recap": return recap(tv, p);
                case "translate": return translate(tv, a.optString("lang", langName()), p);
                case "power": return msg(tv.post("/power", m("do", "restart_app".equals(a.optString("what")) ? "restartapp" : "sleep")));
                default: return null;
            }
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    private static int clamp(int v, int a, int b) {
        return Math.max(a, Math.min(b, v));
    }

    private static String msg(JSONObject r) {
        return r == null ? "" : r.optString("msg", r.optBoolean("ok", true) ? "OK" : "");
    }

    // ------------------------------------------------------------------ no-AI backup (Tamil / Tanglish / English)
    static JSONObject keywords(String s) {
        String t = s.toLowerCase(Locale.ROOT).trim();
        try {
            JSONArray acts = new JSONArray();
            if (has(t, "volume", "சத்தம்", "sattham", "saththam", "sound", "ஒலி")) {
                boolean down = has(t, "kammi", "kami", "குறை", "korai", "kurai", "down", "less", "low", "decrease");
                acts.put(new JSONObject().put("do", "volume_step").put("by", down ? -20 : 20));
            } else if (has(t, "pause", "niruthu", "நிறுத்து", "நிறுத்", "wait")) acts.put(new JSONObject().put("do", "pause"));
            else if (has(t, "next", "adutha", "அடுத்த")) acts.put(new JSONObject().put("do", "next"));
            else if (has(t, "previous", "munnadi", "pinnadi", "முந்தைய", "back channel")) acts.put(new JSONObject().put("do", "prev"));
            else if (has(t, "what did i miss", "recap", "enna aachu", "என்ன ஆச்சு", "summary")) acts.put(new JSONObject().put("do", "recap"));
            else if (has(t, "resume", "continue", "ஓட்டு", "play")) {
                String q = t.replaceAll("\\b(play|podu|pannu)\\b", "").trim();
                if (q.length() > 2 && !q.equals("resume")) acts.put(new JSONObject().put("do", "play_channel").put("q", q));
                else acts.put(new JSONObject().put("do", "play"));
            } else if (t.startsWith("youtube ") || t.endsWith(" youtube")) {
                acts.put(new JSONObject().put("do", "youtube").put("q", t.replace("youtube", "").trim()));
            } else if (has(t, "podu", "போடு", "vai", "வை")) {
                acts.put(new JSONObject().put("do", "play_channel").put("q", t.replaceAll("(podu|போடு|vai|வை|pannu|பண்ணு)", "").trim()));
            } else return null;
            return new JSONObject().put("say", "").put("actions", acts);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean has(String t, String... w) {
        for (String x : w) if (t.contains(x)) return true;
        return false;
    }

    // ------------------------------------------------------------------ "What did I miss?"
    public static String recap(Tv tv, Progress p) {
        try {
            JSONObject r = tv.post("/subtext", m("ms", "600000"));
            if (!r.optBoolean("ok")) return r.optString("msg");
            String text = r.optString("text").trim();
            if (text.isEmpty()) return "No dialogue in the last 10 minutes.";
            if (text.length() > 7000) text = text.substring(text.length() - 7000);
            if (p != null) p.on("…");
            return AiClient.chat("You summarise what just happened in a movie or show, from its subtitles. Answer in "
                    + langName() + ", in 3 short simple sentences. Only describe what is in the subtitles.",
                    "Title: " + r.optString("title") + "\nSubtitles of the last 10 minutes:\n" + text, 300);
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    // ------------------------------------------------------------------ subtitle translation
    public static String translate(Tv tv, String lang, Progress p) {
        try {
            JSONObject r = tv.post("/subfile", m());
            if (!r.optBoolean("ok")) return r.optString("msg");
            List<Subs.Cue> cues = Subs.parse(r.optString("text"));
            if (cues.isEmpty()) return "This subtitle format can't be read";
            int batch = 25;
            for (int i = 0; i < cues.size(); i += batch) {
                if (p != null) p.on(lang + ": " + Math.min(100, i * 100 / cues.size()) + "%");
                StringBuilder q = new StringBuilder();
                int end = Math.min(cues.size(), i + batch);
                for (int k = i; k < end; k++) q.append(k + 1).append('|').append(cues.get(k).text.replace('\n', ' ')).append('\n');
                String out = AiClient.chat("You translate movie subtitles to " + lang + ". Keep each line's number. Reply ONLY with lines "
                        + "in the form N|translation, one per input line, nothing else. Keep names as they are. Natural, short spoken "
                        + lang + ".", q.toString(), 1800);
                for (String line : out.split("\n")) {
                    java.util.regex.Matcher mt = java.util.regex.Pattern.compile("^\\s*(\\d+)\\s*\\|\\s*(.+)$").matcher(line);
                    if (!mt.find()) continue;
                    int n = Integer.parseInt(mt.group(1)) - 1;
                    if (n >= i && n < end) cues.get(n).text = mt.group(2).trim();
                }
            }
            String name = r.optString("name").replaceAll("\\.[^.]+$", "") + "." + lang.substring(0, 2).toLowerCase(Locale.ROOT);
            JSONObject put = tv.post("/subput", m("text", Subs.toSrt(cues), "name", name));
            return lang + " " + T.t("Subtitles").toLowerCase(Locale.ROOT) + ": " + put.optString("msg", "OK");
        } catch (Exception e) {
            return e.getMessage();
        }
    }
}
