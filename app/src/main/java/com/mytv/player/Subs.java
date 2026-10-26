package com.mytv.player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads SRT / VTT / ASS subtitles into cues and writes SRT (for AI recap and translation). */
public final class Subs {
    private Subs() {
    }

    public static final class Cue {
        public long start, end;
        public String text;

        public Cue(long s, long e, String t) {
            start = s;
            end = e;
            text = t;
        }
    }

    private static final Pattern TIME = Pattern.compile(
            "(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})\\s*-->\\s*(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})");
    private static final Pattern TIME_SHORT = Pattern.compile(
            "(\\d{2}):(\\d{2})[.](\\d{3})\\s*-->\\s*(\\d{2}):(\\d{2})[.](\\d{3})");

    private static long ms(String h, String m, String s, String f) {
        while (f.length() < 3) f += "0";
        return ((Long.parseLong(h) * 60 + Long.parseLong(m)) * 60 + Long.parseLong(s)) * 1000 + Long.parseLong(f.substring(0, 3));
    }

    private static String clean(String t) {
        return t.replaceAll("<[^>]+>", "").replaceAll("\\{[^}]*\\}", "").replace("\\N", "\n").replace("\\n", "\n").trim();
    }

    public static List<Cue> parse(String raw) {
        List<Cue> out = new ArrayList<>();
        if (raw == null) return out;
        String s = raw.replace("\r", "").replace("\uFEFF", "");
        if (s.contains("[Events]") || s.contains("Dialogue:")) {
            for (String l : s.split("\n")) {
                if (!l.startsWith("Dialogue:")) continue;
                String[] p = l.substring(9).split(",", 10);
                if (p.length < 10) continue;
                long a = ass(p[1].trim()), b = ass(p[2].trim());
                String t = clean(p[9]);
                if (!t.isEmpty()) out.add(new Cue(a, b, t));
            }
            return out;
        }
        String[] blocks = s.split("\n\\s*\n");
        for (String b : blocks) {
            String[] lines = b.trim().split("\n");
            for (int i = 0; i < lines.length; i++) {
                Matcher m = TIME.matcher(lines[i]);
                long a, e;
                if (m.find()) {
                    a = ms(m.group(1), m.group(2), m.group(3), m.group(4));
                    e = ms(m.group(5), m.group(6), m.group(7), m.group(8));
                } else {
                    Matcher m2 = TIME_SHORT.matcher(lines[i]);
                    if (!m2.find()) continue;
                    a = ms("0", m2.group(1), m2.group(2), m2.group(3));
                    e = ms("0", m2.group(4), m2.group(5), m2.group(6));
                }
                StringBuilder t = new StringBuilder();
                for (int k = i + 1; k < lines.length; k++) t.append(k > i + 1 ? "\n" : "").append(lines[k]);
                String tx = clean(t.toString());
                if (!tx.isEmpty()) out.add(new Cue(a, e, tx));
                break;
            }
        }
        return out;
    }

    private static long ass(String t) {
        try {
            String[] p = t.split("[:.]");
            return ms(p[0], p[1], p[2], p.length > 3 ? p[3] + "0" : "0");
        } catch (Exception e) {
            return 0;
        }
    }

    private static String ts(long ms) {
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000);
    }

    public static String toSrt(List<Cue> cues) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < cues.size(); i++) {
            Cue c = cues.get(i);
            b.append(i + 1).append('\n').append(ts(c.start)).append(" --> ").append(ts(c.end)).append('\n').append(c.text).append("\n\n");
        }
        return b.toString();
    }

    /** Text spoken between two times (for "What did I miss?"). */
    public static String between(List<Cue> cues, long from, long to) {
        StringBuilder b = new StringBuilder();
        for (Cue c : cues) if (c.end >= from && c.start <= to) b.append(c.text.replace('\n', ' ')).append('\n');
        return b.toString();
    }
}
