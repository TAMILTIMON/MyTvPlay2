package com.mytv.player;

import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Encrypts the AI key with a key that never leaves the phone's secure hardware (Android Keystore). */
public final class SecretBox {
    private SecretBox() {
    }

    private static final String ALIAS = "tamilcastplay_ai";

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) return ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return g.generateKey();
    }

    public static String seal(String plain) {
        if (plain == null || plain.isEmpty()) return "";
        if (Build.VERSION.SDK_INT < 23) return "p:" + plain;
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = c.getIV(), ct = c.doFinal(plain.getBytes("UTF-8"));
            byte[] all = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, all, 0, iv.length);
            System.arraycopy(ct, 0, all, iv.length, ct.length);
            return "k:" + iv.length + ":" + Base64.encodeToString(all, Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    public static String open(String sealed) {
        if (sealed == null || sealed.isEmpty()) return "";
        if (sealed.startsWith("p:")) return sealed.substring(2);
        try {
            String[] p = sealed.split(":", 3);
            int ivLen = Integer.parseInt(p[1]);
            byte[] all = Base64.decode(p[2], Base64.NO_WRAP);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, all, 0, ivLen));
            return new String(c.doFinal(all, ivLen, all.length - ivLen), "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
