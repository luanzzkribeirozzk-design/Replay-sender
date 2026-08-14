package com.replayx.sender.security;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import java.security.MessageDigest;

public final class IntegrityCheck {
    private IntegrityCheck() {}

    private static final String PACKAGE = "com.replayx.sender";
    private static final String EXPECTED_CERT_SHA256 =
        "7edee2adda56d4fb55ed3bd9d572c41483dd50b6fe9c40a58dca0e788aadee92";

    public static boolean isValid(Context ctx) {
        try {
            if (!ctx.getPackageName().equals(PACKAGE)) return false;
            int flags = ctx.getApplicationInfo().flags;
            boolean debuggable = (flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            if (debuggable) return false;
            return hasExpectedSignature(ctx);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean hasExpectedSignature(Context ctx) throws Exception {
        PackageInfo pi = ctx.getPackageManager().getPackageInfo(
            ctx.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        SigningInfo si = pi.signingInfo;
        if (si == null) return false;
        Signature[] sigs = si.hasMultipleSigners() ? si.getApkContentsSigners() : si.getSigningCertificateHistory();
        if (sigs == null || sigs.length == 0) return false;
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (Signature sig : sigs) {
            String hex = toHex(md.digest(sig.toByteArray()));
            if (constantTimeEquals(hex, EXPECTED_CERT_SHA256)) return true;
        }
        return false;
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x & 0xFF));
        return sb.toString();
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) diff |= (a.charAt(i) ^ b.charAt(i));
        return diff == 0;
    }
}
