package com.mytv.player;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether a link is a video / song (play in ZH Play) or a web page (browser), and turns
 * share links into direct ones: Google Drive "…/file/d/ID/view" → direct download, Dropbox "?dl=0" → raw.
 */
final class Links {
    private Links() {
    }

    private static final Pattern DRIVE = Pattern.compile("drive\\.google\\.com/(?:file/d/|open\\?id=|uc\\?(?:.*&)?id=)([A-Za-z0-9_-]{10,})");

    /** A share link → a direct link a player can stream. Others unchanged. */
    static String direct(String url) {
        if (url == null) return "";
        String u = url.trim();
        Matcher m = DRIVE.matcher(u);
        if (m.find()) return "https://drive.usercontent.google.com/download?id=" + m.group(1) + "&export=download&confirm=t";
        if (u.contains("dropbox.com/")) {
            u = u.replace("?dl=0", "?raw=1").replace("&dl=0", "&raw=1");
            if (!u.contains("raw=1") && !u.contains("dl=1")) u += (u.contains("?") ? "&" : "?") + "raw=1";
        }
        return u;
    }

    static boolean isYouTube(String u) {
        String l = u.toLowerCase(Locale.ROOT);
        return l.contains("youtube.com/") || l.contains("youtu.be/");
    }

    /** Clearly a video / song / stream from the link alone. */
    static boolean looksMedia(String url) {
        if (url == null) return false;
        String l = url.toLowerCase(Locale.ROOT);
        return Formats.isVideo(url) || Formats.isAudio(url) || l.contains(".m3u8") || l.contains(".mpd")
                || l.contains("drive.usercontent.google.com/download") || l.contains("/ff_get/")
                || l.startsWith("rtmp://") || l.startsWith("rtsp://") || l.startsWith("magnet:");
    }

    /** Asks the server what the link is (headers only, nothing downloaded). Blocking, ~1–3 s. */
    static boolean probeMedia(String url) {
        if (!url.startsWith("http")) return false;
        for (String method : new String[]{"HEAD", "GET"}) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestMethod(method);
                c.setInstanceFollowRedirects(true);
                c.setConnectTimeout(6000);
                c.setReadTimeout(6000);
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) ZHPlay");
                if ("GET".equals(method)) c.setRequestProperty("Range", "bytes=0-0");     // just the headers
                int code = c.getResponseCode();
                if (code >= 400 && "HEAD".equals(method)) continue;                         // some servers refuse HEAD
                String type = String.valueOf(c.getContentType()).toLowerCase(Locale.ROOT);
                String disp = String.valueOf(c.getHeaderField("Content-Disposition")).toLowerCase(Locale.ROOT);
                String fin = String.valueOf(c.getURL());
                if (type.startsWith("video/") || type.startsWith("audio/") || type.contains("mpegurl") || type.contains("dash+xml")
                        || type.contains("matroska") || type.contains("x-flv")) return true;
                if (type.contains("octet-stream") || type.contains("binary")) {
                    // a file download: a video if its name says so
                    Matcher m = Pattern.compile("filename\\*?=(?:utf-8'')?\"?([^\";]+)").matcher(disp);
                    String name = m.find() ? m.group(1) : fin;
                    return Formats.isVideo(name) || Formats.isAudio(name) || Formats.isVideo(fin) || Formats.isAudio(fin);
                }
                return false;                                                                  // text/html etc. → a page
            } catch (Exception e) {
                if ("GET".equals(method)) return false;
            } finally {
                if (c != null) c.disconnect();
            }
        }
        return false;
    }
}
