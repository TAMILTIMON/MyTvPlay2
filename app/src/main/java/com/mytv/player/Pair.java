package com.mytv.player;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Remote PIN: phones must enter the 4-digit PIN shown on the TV once; then they get a saved token. */
public final class Pair {
    private Pair() {
    }

    private static final SecureRandom RNG = new SecureRandom();
    private static int wrong = 0;
    private static long lockedUntil = 0;

    public static boolean enabled() {
        return Store.getBool("pin_on", true);
    }

    public static synchronized String pin() {
        String p = Store.getStr("pin", "");
        if (p.length() != 4) {
            p = String.format(Locale.US, "%04d", RNG.nextInt(10000));
            Store.putStr("pin", p);
        }
        return p;
    }

    /** New PIN and forget every paired phone. */
    public static synchronized void reset() {
        Store.putStr("pin", "");
        Store.putStr("tokens", "");
        pin();
    }

    private static List<String> tokens() {
        String t = Store.getStr("tokens", "");
        return t.isEmpty() ? new ArrayList<String>() : new ArrayList<>(Arrays.asList(t.split(",")));
    }

    public static synchronized boolean valid(String token) {
        return token != null && token.length() >= 16 && tokens().contains(token);
    }

    /** Returns a token, or null when the PIN is wrong. Locks for a minute after 5 wrong tries. */
    public static synchronized String pair(String pinTry) throws Exception {
        if (System.currentTimeMillis() < lockedUntil) throw new Exception("Too many wrong PINs. Wait a minute.");
        if (!pin().equals(pinTry == null ? "" : pinTry.trim())) {
            if (++wrong >= 5) {
                wrong = 0;
                lockedUntil = System.currentTimeMillis() + 60000;
            }
            return null;
        }
        wrong = 0;
        byte[] b = new byte[16];
        RNG.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x));
        List<String> t = tokens();
        t.add(sb.toString());
        while (t.size() > 40) t.remove(0);
        StringBuilder all = new StringBuilder();
        for (String s : t) {
            if (all.length() > 0) all.append(',');
            all.append(s);
        }
        Store.putStr("tokens", all.toString());
        return sb.toString();
    }
}
