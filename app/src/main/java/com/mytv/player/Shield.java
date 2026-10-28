package com.mytv.player;

import android.content.Context;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Brave-style shield for the built-in browser: blocks ad / tracker requests by domain (built-in list +
 * EasyList, EasyPrivacy and Peter Lowe's list, refreshed weekly), hides empty ad boxes, skips YouTube ads.
 */
public final class Shield {
    private Shield() {
    }

    private static final Set<String> DOMAINS = Collections.synchronizedSet(new HashSet<String>());
    private static volatile boolean loaded;
    public static final AtomicLong total = new AtomicLong();

    // always blocked, even before the lists are downloaded
    private static final String[] BUILTIN = {
            "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com", "pagead2.googlesyndication.com",
            "googletagservices.com", "google-analytics.com", "googletagmanager.com", "adnxs.com", "adsrvr.org", "advertising.com",
            "amazon-adsystem.com", "taboola.com", "outbrain.com", "criteo.com", "criteo.net", "pubmatic.com", "rubiconproject.com",
            "openx.net", "casalemedia.com", "scorecardresearch.com", "quantserve.com", "moatads.com", "adform.net", "smartadserver.com",
            "media.net", "yieldmo.com", "sharethrough.com", "33across.com", "teads.tv", "zedo.com", "popads.net", "popcash.net",
            "propellerads.com", "adsterra.com", "exoclick.com", "juicyads.com", "trafficjunky.net", "hotjar.com", "mixpanel.com",
            "branch.io", "appsflyer.com", "adjust.com", "inmobi.com", "applovin.com", "unityads.unity3d.com", "chartboost.com",
            "vungle.com", "ironsrc.com", "mopub.com", "startapp.com", "adcolony.com", "bidswitch.net", "contextweb.com",
            "lijit.com", "sovrn.com", "indexww.com", "spotxchange.com", "springserve.com", "tremorhub.com", "yahoo-ads.com",
            "ads.yahoo.com", "adtech.de", "serving-sys.com", "flashtalking.com", "imasdk.googleapis.com", "fwmrm.net",
            "adsafeprotected.com", "doubleverify.com", "omtrdc.net", "demdex.net", "everesttech.net", "krxd.net", "bluekai.com",
            "facebook.net", "connect.facebook.net", "ads-twitter.com", "analytics.twitter.com", "ads.linkedin.com", "snap.licdn.com",
            "clarity.ms", "bat.bing.com", "adsymptotic.com", "revcontent.com", "mgid.com", "adskeeper.co.uk", "colombiaonline.com",
            "vdo.ai", "izooto.com", "onesignal.com", "pushcrew.com", "truepush.com", "notix.io", "hilltopads.net", "a-ads.com",
            "adcash.com", "clickadu.com", "galaksion.com", "monetag.com", "admaven.com", "ad-maven.com", "richpush.co"};

    // ad paths on otherwise normal sites (YouTube, news sites)
    private static final String[] PATHS = {"/pagead/", "/ptracking", "/api/stats/ads", "/get_midroll_", "/ad_break", "doubleclick.net",
            "/adserver/", "/ads/ads.js", "/prebid", "/adsbygoogle"};

    public static boolean enabled() {
        return Store.getBool("shield_on", true);
    }

    public static boolean ytSkip() {
        return Store.getBool("shield_yt", true);
    }

    public static boolean siteOff(String host) {
        return host != null && Store.getStr("shield_off", "").contains("|" + host + "|");
    }

    public static void setSite(String host, boolean on) {
        String s = Store.getStr("shield_off", "|");
        if (!s.startsWith("|")) s = "|" + s;
        s = s.replace("|" + host + "|", "|");
        if (!on) s += host + "|";
        Store.putStr("shield_off", s);
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), "shield-domains.txt");
    }

    /** Loads the saved lists (and refreshes them in the background once a week). */
    public static void init(final Context c) {
        if (loaded) return;
        loaded = true;
        total.set(Store.getLong("shield_total", 0));
        Collections.addAll(DOMAINS, BUILTIN);
        new Thread(() -> {
            File f = file(c);
            if (f.isFile()) {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"))) {
                    String l;
                    while ((l = r.readLine()) != null) if (!l.isEmpty()) DOMAINS.add(l);
                } catch (Exception ignored) {
                }
            }
            if (!f.isFile() || System.currentTimeMillis() - f.lastModified() > 7L * 24 * 3600 * 1000) update(c);
        }, "shield").start();
    }

    /** Downloads the filter lists. Returns how many domains. */
    public static int update(Context c) {
        Set<String> s = new HashSet<>();
        String[] lists = {"https://easylist.to/easylist/easylist.txt", "https://easylist.to/easylist/easyprivacy.txt",
                "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=nohtml&showintro=0&mimetype=plaintext"};
        for (String u : lists) {
            try {
                String text = Net.fetchText(u);
                for (String line : text.split("\n")) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("!") || line.startsWith("[") || line.contains("##") || line.startsWith("@@")) continue;
                    if (line.startsWith("||")) {
                        // ||ads.example.com^  (only plain domain rules — never page-breaking ones)
                        String d = line.substring(2);
                        int end = 0;
                        while (end < d.length() && (Character.isLetterOrDigit(d.charAt(end)) || d.charAt(end) == '.' || d.charAt(end) == '-')) end++;
                        String rest = d.substring(end);
                        if (end > 3 && (rest.isEmpty() || rest.equals("^") || rest.startsWith("^$third-party"))) s.add(d.substring(0, end).toLowerCase(Locale.ROOT));
                    } else if (line.matches("[a-z0-9.-]+\\.[a-z]{2,}")) s.add(line.toLowerCase(Locale.ROOT));
                }
            } catch (Exception ignored) {
            }
        }
        if (s.size() > 1000) {
            DOMAINS.addAll(s);
            try (FileOutputStream o = new FileOutputStream(file(c))) {
                StringBuilder b = new StringBuilder();
                for (String d : s) b.append(d).append('\n');
                o.write(b.toString().getBytes("UTF-8"));
            } catch (Exception ignored) {
            }
            Store.putLong("shield_updated", System.currentTimeMillis());
        }
        return DOMAINS.size();
    }

    public static int size() {
        return DOMAINS.size();
    }

    /** Should this sub-request of a page be blocked? (never the page itself) */
    public static boolean blocked(String url, String pageHost) {
        if (!enabled() || siteOff(pageHost)) return false;
        String host;
        try {
            host = Uri.parse(url).getHost();
        } catch (Exception e) {
            return false;
        }
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        // a site's own files are allowed (only third-party ad / tracker servers are blocked)
        if (pageHost != null && !pageHost.isEmpty() && (host.equals(pageHost) || host.endsWith("." + base(pageHost)) || host.equals(base(pageHost)))) {
            if (host.contains("youtube.com") || host.contains("youtube-nocookie.com")) {
                String lu = url.toLowerCase(Locale.ROOT);
                for (String p : PATHS) if (lu.contains(p)) return true;
            }
            return false;
        }
        String h = host;
        while (true) {
            if (DOMAINS.contains(h)) return true;
            int dot = h.indexOf('.');
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) break;
            h = h.substring(dot + 1);
        }
        String lu = url.toLowerCase(Locale.ROOT);
        for (String p : PATHS) if (lu.contains(p)) return true;
        return false;
    }

    private static String base(String host) {
        String[] p = host.split("\\.");
        if (p.length <= 2) return host;
        String last2 = p[p.length - 2] + "." + p[p.length - 1];
        // co.in, co.uk, com.au …
        if (p[p.length - 2].length() <= 3 && p.length >= 3 && p[p.length - 1].length() == 2) return p[p.length - 3] + "." + last2;
        return last2;
    }

    public static void counted() {
        long t = total.incrementAndGet();
        if (t % 20 == 0) Store.putLong("shield_total", t);
    }

    public static void save() {
        Store.putLong("shield_total", total.get());
    }

    /** Hides empty ad boxes (only well-known ad containers, so pages don't break). */
    public static String cosmeticJs() {
        return "(function(){if(document.getElementById('tcp-shield'))return;var s=document.createElement('style');s.id='tcp-shield';"
                + "s.textContent='ins.adsbygoogle,[id^=\"google_ads_iframe\"],[id^=\"div-gpt-ad\"],.adsbygoogle,.ad-slot,.adslot,"
                + "[data-ad-slot],iframe[src*=\"doubleclick\"],iframe[src*=\"googlesyndication\"],.taboola,#taboola-below-article,"
                + ".OUTBRAIN,[id^=\"taboola-\"],.trc_rbox_container{display:none!important}';"
                + "(document.head||document.documentElement).appendChild(s);})();";
    }

    /** YouTube (in this browser): skips video ads and hides ad boxes. Best effort — YouTube changes often. */
    /**
     * YouTube in the browser, 3 layers:
     *  1. remove the ad schedule from the video's data (adPlacements / playerAds / adSlots) — the ads are never planned
     *     (the uBlock Origin / Brave method: works for normal and "stitched" ads)
     *  2. if an ad still shows: press Skip, or mute + fast-forward it, with a "Skipping ad…" spinner
     *  3. close YouTube's "ad blockers are not allowed" pop-up and resume the video
     */
    public static String youtubeJs() {
        return "(function(){if(window.__tcpYt)return;window.__tcpYt=1;"
                // ---- 1. prune the ad schedule
                + "var AD=['adPlacements','playerAds','adSlots','adBreakHeartbeatParams'];"
                + "function prune(o){try{if(!o||typeof o!=='object')return o;var hit=0;"
                + "AD.forEach(function(k){if(k in o){delete o[k];hit=1;}});"
                + "if(o.playerResponse)prune(o.playerResponse);if(o.response)prune(o.response);"
                + "if(Array.isArray(o))o.forEach(prune);"
                + "if(hit&&window.TamilShield)TamilShield.ad();}catch(e){}return o;}"
                + "var jp=JSON.parse;JSON.parse=function(){var r=jp.apply(this,arguments);"
                + "if(r&&typeof r==='object'&&(r.playabilityStatus||r.streamingData||r.playerResponse||r.adPlacements))prune(r);return r;};"
                + "var _pr=window.ytInitialPlayerResponse;"
                + "try{Object.defineProperty(window,'ytInitialPlayerResponse',{configurable:true,get:function(){return _pr;},set:function(v){_pr=prune(v);}});"
                + "if(_pr)prune(_pr);}catch(e){}"
                + "var of=window.fetch;if(of)window.fetch=function(i,o){var u=(typeof i==='string')?i:(i&&i.url)||'';"
                + "var pr=of.apply(this,arguments);if(u.indexOf('/youtubei/v1/player')<0&&u.indexOf('/youtubei/v1/next')<0)return pr;"
                + "return pr.then(function(res){return res.clone().json().then(function(j){prune(j);"
                + "return new Response(JSON.stringify(j),{status:res.status,statusText:res.statusText,headers:res.headers});})"
                + ".catch(function(){return res;});});};"
                // ---- banners / promoted cards / the "Skipping ad…" spinner
                + "var css=document.createElement('style');css.textContent='ytm-promoted-sparkles-web-renderer,ytm-companion-ad-renderer,"
                + "ytd-ad-slot-renderer,ytd-promoted-video-renderer,ytm-promoted-video-renderer,#player-ads,.ytp-ad-overlay-container,"
                + "ytd-in-feed-ad-layout-renderer,ytm-ad-slot-renderer,ad-slot-renderer,.ytd-banner-promo-renderer,"
                + "ytd-statement-banner-renderer,ytm-statement-banner-renderer,ytd-enforcement-message-view-model,"
                + "ytm-companion-slot,.ytp-ad-image-overlay{display:none!important}"
                + "#zh-adskip{position:absolute;inset:0;z-index:99999;display:flex;flex-direction:column;align-items:center;"
                + "justify-content:center;background:rgba(0,0,0,.72);color:#fff;font:600 15px/1.4 sans-serif;pointer-events:none}"
                + "#zh-adskip i{width:38px;height:38px;border:4px solid rgba(255,255,255,.25);border-top-color:#E83C30;"
                + "border-radius:50%;animation:zhspin .8s linear infinite;margin-bottom:12px}"
                + "@keyframes zhspin{to{transform:rotate(360deg)}}';"
                + "(document.head||document.documentElement).appendChild(css);"
                // ---- 2. an ad that still shows: skip / fast-forward
                + "var wasAd=false;"
                + "function tick(){try{var p=document.querySelector('.ad-showing,.ad-interrupting');var v=document.querySelector('video');"
                + "var b=document.querySelector('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button,"
                + "button.ytp-ad-skip-button-container,.ytp-ad-skip-button-slot button,[id^=skip-button] button,.ytm-skip-ad-button');"
                + "if(b){b.click();}"
                + "var o=document.getElementById('zh-adskip');"
                + "if(p){if(!o){o=document.createElement('div');o.id='zh-adskip';o.innerHTML='<i></i>Skipping ad…';"
                + "if(getComputedStyle(p).position==='static')p.style.position='relative';p.appendChild(o);}}"
                + "else if(o){o.remove();}"
                + "if(p&&v){wasAd=true;v.muted=true;try{v.playbackRate=16;}catch(e){}"
                // a short separate ad: jump to its end; a long one (ad stitched into the video) is only fast-forwarded
                + "if(isFinite(v.duration)&&v.duration>0&&v.duration<=180)v.currentTime=Math.max(v.currentTime,v.duration-0.1);"
                + "window.TamilShield&&TamilShield.ad();}"
                + "else if(v&&wasAd){wasAd=false;v.playbackRate=1;v.muted=false;}"
                // ---- 3. the "ad blockers are not allowed" pop-up
                + "var en=document.querySelector('ytd-enforcement-message-view-model,tp-yt-paper-dialog:has(ytd-enforcement-message-view-model)');"
                + "if(en){en.remove();var bd=document.querySelector('tp-yt-iron-overlay-backdrop');if(bd)bd.remove();if(v&&v.paused)v.play();}"
                + "}catch(e){}}setInterval(tick,250);})();";
    }
}
