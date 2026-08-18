package com.replayx.sender.security;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import android.os.Debug;

import java.io.BufferedReader;
import java.io.FileReader;
import java.security.MessageDigest;
import java.util.Locale;

public final class IntegrityCheck {
    private IntegrityCheck() {}

    private static final String PACKAGE = "com.replayx.sender";
    private static final String EXPECTED_CERT_SHA256 =
        "7edee2adda56d4fb55ed3bd9d572c41483dd50b6fe9c40a58dca0e788aadee92";

    public static boolean isValid(Context ctx) {
        try {
            if (!ctx.getPackageName().equals(PACKAGE)) return false;
            ApplicationInfo info = ctx.getApplicationInfo();
            if ((info.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) return false;
            if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) return false;
            if (hasTracer()) return false;
            if (hasSuspiciousInstrumentation()) return false;
            return hasExpectedSignature(ctx);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean hasExpectedSignature(Context ctx) throws Exception {
        PackageManager pm = ctx.getPackageManager();
        Signature[] sigs;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageInfo pi = pm.getPackageInfo(ctx.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            SigningInfo si = pi.signingInfo;
            if (si == null) return false;
            sigs = si.hasMultipleSigners() ? si.getApkContentsSigners() : si.getSigningCertificateHistory();
        } else {
            PackageInfo pi = pm.getPackageInfo(ctx.getPackageName(), PackageManager.GET_SIGNATURES);
            sigs = pi.signatures;
        }
        if (sigs == null || sigs.length == 0) return false;
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (Signature sig : sigs) {
            String hex = toHex(md.digest(sig.toByteArray()));
            if (constantTimeEquals(hex, EXPECTED_CERT_SHA256)) return true;
        }
        return false;
    }

    private static boolean hasTracer() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/status"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("TracerPid:")) {
                    String value = line.substring("TracerPid:".length()).trim();
                    return !"0".equals(value);
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static boolean hasSuspiciousInstrumentation() {
        String[] classes = {
            "de.robv.android.xposed.XposedBridge",
            "de.robv.android.xposed.XposedHelpers",
            "com.saurik.substrate.MS$2",
            "re.frida.server.FridaGadget"
        };
        for (String name : classes) {
            try {
                Class.forName(name, false, IntegrityCheck.class.getClassLoader());
                return true;
            } catch (Throwable ignored) {}
        }
        try {
            String stack = LogStack.capture().toLowerCase(Locale.ROOT);
            return stack.contains("frida") || stack.contains("xposed") || stack.contains("substrate");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x & 0xFF));
        return sb.toString();
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) diff |= (a.charAt(i) ^ b.charAt(i));
        return diff == 0;
    }

    private static final class LogStack {
        static String capture() {
            StringBuilder out = new StringBuilder();
            for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
                out.append(e.getClassName()).append('.').append(e.getMethodName()).append('\n');
            }
            return out.toString();
        }
    }
}
