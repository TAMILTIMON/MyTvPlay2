package com.mytv.player;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encryption between the phone and the TV on the local Wi-Fi.
 *   1. /hello: phone and TV swap fresh EC P-256 public keys (ECDH) → both compute the same
 *      AES-256 key = SHA-256("ZHPLAY-E2E-1" ‖ shared secret ‖ phone key ‖ TV key). Never sent.
 *   2. Every message is AES-256-GCM (random 12-byte nonce, 128-bit tag): readable and changeable
 *      only by that phone and that TV. Someone on the same Wi-Fi sees only scrambled bytes.
 *   3. A message can't be replayed: the TV remembers recent nonces.
 * Who may control the TV is still decided by the pairing token / same Google account — now sent
 * inside the encryption. Video streams are not encrypted here (not secret, and speed matters).
 */
final class ZhCrypt {
    private ZhCrypt() {
    }

    static final int VERSION = 1;
    /** Phone ↔ TV encryption is OFF (it slowed the remote on TV boxes): everything goes direct, as before 9.0. */
    static final boolean ENABLED = false;
    private static final SecureRandom RNG = new SecureRandom();

    static final class Session {
        final String sid;
        final byte[] key;
        volatile long used;

        Session(String sid, byte[] key) {
            this.sid = sid;
            this.key = key;
            this.used = System.currentTimeMillis();
        }
    }

    /** The other side doesn't speak encryption (an older ZH Play). */
    static final class Unsupported extends Exception {
        Unsupported() {
            super("old version");
        }
    }

    // ------------------------------------------------------------------ primitives

    static KeyPair newPair() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"), RNG);
        return g.generateKeyPair();
    }

    static String pub(KeyPair kp) {
        return Fcm.b64url(kp.getPublic().getEncoded());
    }

    static byte[] derive(KeyPair mine, String theirPubB64, String phonePub, String tvPub) throws Exception {
        PublicKey theirs = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Fcm.b64decode(theirPubB64)));
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(mine.getPrivate());
        ka.doPhase(theirs, true);
        byte[] shared = ka.generateSecret();
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update("ZHPLAY-E2E-1".getBytes(StandardCharsets.UTF_8));
        md.update(shared);
        md.update(phonePub.getBytes(StandardCharsets.UTF_8));
        md.update(tvPub.getBytes(StandardCharsets.UTF_8));
        return md.digest();
    }

    /** nonce ‖ ciphertext+tag, base64url. */
    static String seal(byte[] key, String plain) throws Exception {
        byte[] iv = new byte[12];
        RNG.nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        byte[] all = new byte[12 + ct.length];
        System.arraycopy(iv, 0, all, 0, 12);
        System.arraycopy(ct, 0, all, 12, ct.length);
        return Fcm.b64url(all);
    }

    /** Opens a sealed message (throws if it was changed or made with another key). */
    static String open(byte[] key, String sealed) throws Exception {
        byte[] all = Fcm.b64decode(sealed);
        if (all.length < 12 + 16) throw new Exception("too short");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, all, 0, 12));
        return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ TV side

    private static final Map<String, Session> tvSessions = new HashMap<>();
    private static final Map<String, Boolean> seenNonces = new LinkedHashMap<String, Boolean>(2048, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) {
            return size() > 4000;
        }
    };

    /** TV: answers a phone's /hello → {v, pub, sid}; keeps the session key. */
    static org.json.JSONObject hello(String phonePub) throws Exception {
        KeyPair kp = newPair();
        String tvPub = pub(kp);
        byte[] key = derive(kp, phonePub, phonePub, tvPub);
        byte[] sidb = new byte[16];
        RNG.nextBytes(sidb);
        String sid = Fcm.b64url(sidb);
        synchronized (tvSessions) {
            long now = System.currentTimeMillis();
            java.util.Iterator<Session> it = tvSessions.values().iterator();   // forget idle ones (Android 5+ safe)
            while (it.hasNext()) if (now - it.next().used > 12 * 3600 * 1000L) it.remove();
            if (tvSessions.size() > 200) tvSessions.clear();
            tvSessions.put(sid, new Session(sid, key));
        }
        return new org.json.JSONObject().put("ok", true).put("v", VERSION).put("pub", tvPub).put("sid", sid);
    }

    /** TV: the key of a session, or null (unknown / expired → the phone says hello again). */
    static byte[] tvKey(String sid) {
        if (sid == null) return null;
        synchronized (tvSessions) {
            Session s = tvSessions.get(sid);
            if (s == null) return null;
            s.used = System.currentTimeMillis();
            return s.key;
        }
    }

    /** TV: opens a phone's message once; a replayed copy is refused. */
    static String tvOpen(byte[] key, String sealed) throws Exception {
        String nonce = sealed.length() >= 16 ? sealed.substring(0, 16) : sealed;
        synchronized (seenNonces) {
            if (seenNonces.containsKey(nonce)) throw new Exception("replayed");
        }
        String plain = open(key, sealed);
        synchronized (seenNonces) {
            seenNonces.put(nonce, Boolean.TRUE);
        }
        return plain;
    }

    // ------------------------------------------------------------------ phone side

    private static final Map<String, Session> phoneSessions = new HashMap<>();
    private static final Map<String, Long> oldTvs = new HashMap<>();

    /** Encryption is used for TVs on the network (not for this device talking to itself). */
    static boolean usable(String ip) {
        if (!ENABLED) return false;
        if (ip == null || ip.startsWith("127.") || ip.equals("localhost") || ip.equals("::1") || ip.startsWith("cloud:")) return false;
        synchronized (oldTvs) {
            Long t = oldTvs.get(ip);
            return t == null || System.currentTimeMillis() - t > 10 * 60 * 1000L;     // retry an old TV after 10 min
        }
    }

    static void markOld(String ip) {
        synchronized (oldTvs) {
            oldTvs.put(ip, System.currentTimeMillis());
        }
    }

    static boolean isOld(String ip) {
        synchronized (oldTvs) {
            return oldTvs.containsKey(ip);
        }
    }

    static void drop(String ip) {
        synchronized (phoneSessions) {
            phoneSessions.remove(ip);
        }
    }

    /** Phone: the session with this TV (says /hello when needed). Blocking. */
    static Session session(String ip, int port) throws Exception {
        synchronized (phoneSessions) {
            Session s = phoneSessions.get(ip);
            if (s != null && System.currentTimeMillis() - s.used < 6 * 3600 * 1000L) {
                s.used = System.currentTimeMillis();
                return s;
            }
        }
        KeyPair kp = newPair();
        String mine = pub(kp);
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL("http://" + ip + ":" + port + "/hello").openConnection();
        org.json.JSONObject r;
        try {
            c.setConnectTimeout(5000);
            c.setReadTimeout(8000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (java.io.OutputStream o = c.getOutputStream()) {
                o.write(("pub=" + mine).getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            java.io.InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            StringBuilder sb = new StringBuilder();
            if (in != null) {
                byte[] b = new byte[4096];
                int n;
                while ((n = in.read(b)) > 0) sb.append(new String(b, 0, n, StandardCharsets.UTF_8));
                in.close();
            }
            String body = sb.toString().trim();
            if (!body.startsWith("{")) throw new Unsupported();
            r = new org.json.JSONObject(body);
        } finally {
            c.disconnect();
        }
        if (r.optString("pub").isEmpty() || r.optString("sid").isEmpty()) throw new Unsupported();
        Session s = new Session(r.getString("sid"), derive(kp, r.getString("pub"), mine, r.getString("pub")));
        synchronized (phoneSessions) {
            phoneSessions.put(ip, s);
        }
        return s;
    }

    // ------------------------------------------------------------------ tickets (TV)
    // Short-lived stand-ins for the pairing token where a plain connection is kept for speed
    // (file upload, screen cast, the web-page remote). A captured ticket soon stops working.

    private static final Map<String, long[]> tickets = new HashMap<>();     // ticket → {expires, multi}

    static String issueTicket(long ttlMs, boolean multi) {
        byte[] b = new byte[18];
        RNG.nextBytes(b);
        String t = "tk_" + Fcm.b64url(b);
        synchronized (tickets) {
            long now = System.currentTimeMillis();
            java.util.Iterator<long[]> it = tickets.values().iterator();
            while (it.hasNext()) if (it.next()[0] < now) it.remove();
            if (tickets.size() > 500) tickets.clear();
            tickets.put(t, new long[]{now + Math.max(5000, Math.min(ttlMs, 12 * 3600 * 1000L)), multi ? 1 : 0});
        }
        return t;
    }

    /** True for a valid ticket; a one-time ticket is used up. */
    static boolean ticketOk(String t) {
        if (t == null || !t.startsWith("tk_")) return false;
        synchronized (tickets) {
            long[] v = tickets.get(t);
            if (v == null) return false;
            if (v[0] < System.currentTimeMillis()) {
                tickets.remove(t);
                return false;
            }
            if (v[1] == 0) tickets.remove(t);
            return true;
        }
    }
}
