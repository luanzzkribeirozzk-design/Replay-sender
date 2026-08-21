package com.replayx.sender.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

/** Armazena a licença cifrada pelo Android Keystore e mantém um cache cifrado da key lembrada. */
public final class SecureStore {
    private SecureStore() {}

    private static final String PREFS = "replayx_secure_license";
    private static final String FALLBACK_PREFS = PREFS + "_remembered";
    private static final String ALIAS = "ReplayXSenderLicenseKey";
    private static final String REMEMBERED_KEY = "remembered_key";
    private static final String FALLBACK_KEY = "value";
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
        KeyStore.Entry entry = ks.getEntry(ALIAS, null);
        if (!(entry instanceof KeyStore.SecretKeyEntry)) throw new IllegalStateException("invalid keystore entry");
        return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
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

    /** Persiste a key lembrada também em um cache cifrado vinculado ao package e ao aparelho. */
    public static boolean putRemembered(Context ctx, String value) {
        boolean primary = put(ctx, REMEMBERED_KEY, value);
        boolean secondary = false;
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, fallbackKey(ctx), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            String payload = Base64.encodeToString(iv, Base64.NO_WRAP) + SEP
                    + Base64.encodeToString(encrypted, Base64.NO_WRAP);
            secondary = fallbackPrefs(ctx).edit().putString(FALLBACK_KEY, payload).commit();
        } catch (Exception ignored) {}
        return primary || secondary;
    }

    public static String getRemembered(Context ctx, String fallback) {
        String primary = get(ctx, REMEMBERED_KEY, "");
        if (primary != null && !primary.isEmpty()) return primary;
        try {
            String payload = fallbackPrefs(ctx).getString(FALLBACK_KEY, "");
            if (payload == null || payload.isEmpty()) return fallback;
            String[] parts = payload.split(SEP, 2);
            if (parts.length != 2) return fallback;
            byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(parts[1], Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, fallbackKey(ctx), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    public static void removeRemembered(Context ctx) {
        remove(ctx, REMEMBERED_KEY);
        fallbackPrefs(ctx).edit().remove(FALLBACK_KEY).commit();
    }

    public static void remove(Context ctx, String name) {
        prefs(ctx).edit().remove(name).commit();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SharedPreferences fallbackPrefs(Context ctx) {
        return ctx.getSharedPreferences(FALLBACK_PREFS, Context.MODE_PRIVATE);
    }

    private static SecretKey fallbackKey(Context ctx) throws Exception {
        String androidId = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (androidId == null || androidId.trim().isEmpty()) androidId = "unknown-device";
        String material = ctx.getPackageName() + ":" + androidId + ":ReplayX-remembered-key-v2";
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(material.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(digest, "AES");
    }
}
