package com.replayx.sender.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

/** Armazena dados de licença cifrados por uma chave não exportável do Android Keystore. */
public final class SecureStore {
    private SecureStore() {}

    private static final String PREFS = "replayx_secure_license";
    private static final String ALIAS = "ReplayXSenderLicenseKey";
    private static final String SEP = ":";

    private static SecretKey getKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(false)
                    .build());
            kg.generateKey();
        }
        return ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
    }

    public static boolean put(Context ctx, String name, String value) {
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getKey(), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            String payload = Base64.encodeToString(iv, Base64.NO_WRAP) + SEP
                    + Base64.encodeToString(encrypted, Base64.NO_WRAP);
            return prefs(ctx).edit().putString(name, payload).commit();
        } catch (Exception ignored) {
            return false;
        }
    }

    public static String get(Context ctx, String name, String fallback) {
        try {
            String payload = prefs(ctx).getString(name, "");
            if (payload == null || payload.isEmpty()) return fallback;
            String[] parts = payload.split(SEP, 2);
            if (parts.length != 2) return fallback;
            byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(parts[1], Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    public static void remove(Context ctx, String name) {
        prefs(ctx).edit().remove(name).commit();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
