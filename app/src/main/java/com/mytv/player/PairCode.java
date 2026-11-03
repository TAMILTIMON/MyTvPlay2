package com.mytv.player;

import android.content.Context;
import android.net.nsd.NsdServiceInfo;

import java.util.Locale;

/**
 * 6-character alphanumeric pairing code for ZH Play TV↔Phone connection.
 *
 * The TV generates a code from its local IP + a random salt.
 * The code is embedded in:
 *   1. The NSD service TXT record (fast discovery — no IP scan needed)
 *   2. The /ping HTTP response (fallback for IP scan)
 *
 * The phone resolves a typed code to an IP via:
 *   1. NSD: browse all _mytvplayer._tcp services → match TXT code field
 *   2. IP scan fallback: scan /24 subnet → /ping each → match code field
 *
 * Code is regenerated every 30 minutes or on TV app start.
 * Format: 6 uppercase alphanumeric chars (A-Z 0-9), vowels removed to avoid
 * confusing words. Example: ZH7K2M
 */
public final class PairCode {
    private PairCode() {}

    private static final String KEY_CODE    = "pair_code";
    private static final String KEY_EXPIRY  = "pair_code_exp";
    private static final long   TTL_MS      = 30 * 60 * 1000L;  // 30 minutes
    private static final char[] CHARS       = "BCDFGHJKLMNPQRSTVWXYZ23456789".toCharArray();

    /** Get the current pairing code for this TV — generates a new one if expired. */
    public static String get() {
        long now = System.currentTimeMillis();
        long exp = Store.getLong(KEY_EXPIRY, 0);
        String code = Store.getStr(KEY_CODE, "");
        if (code.length() == 6 && now < exp) return code;
        return generate();
    }

    /** Force-generate a new code (called on TV app start). */
    public static String generate() {
        String code = randomCode();
        Store.putStr(KEY_CODE, code);
        Store.putLong(KEY_EXPIRY, System.currentTimeMillis() + TTL_MS);
        return code;
    }

    /** Returns minutes until the code expires (for display countdown). */
    public static int minutesLeft() {
        long ms = Store.getLong(KEY_EXPIRY, 0) - System.currentTimeMillis();
        return (int) Math.max(0, ms / 60_000);
    }

    private static String randomCode() {
        java.security.SecureRandom rng = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) sb.append(CHARS[rng.nextInt(CHARS.length)]);
        return sb.toString();
    }

    /** Embed the pairing code in an NSD service info TXT record (Android 13+). */
    public static void setAttributes(NsdServiceInfo info) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            try {
                info.setAttribute("code", get());
                info.setAttribute("app",  "zhplay");
            } catch (Exception ignored) {}
        }
    }

    /**
     * Check whether a typed string matches the current code.
     * Case-insensitive, ignores spaces and hyphens (so ZH-7K2M == ZH7K2M).
     */
    public static boolean matches(String typed) {
        if (typed == null) return false;
        return get().equalsIgnoreCase(typed.replaceAll("[^A-Za-z0-9]", ""));
    }

    /** Format the code with a hyphen in the middle for readability: ZH7K2M → ZH7-K2M */
    public static String formatted(String code) {
        if (code == null || code.length() != 6) return code;
        return code.substring(0, 3) + "-" + code.substring(3);
    }
}
