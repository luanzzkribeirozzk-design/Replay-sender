package com.replayx.sender.security;

import android.content.Context;
import android.provider.Settings;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

/** Regras da key Firebase do Sender e estado cifrado do login. */
public final class LicenseManager {
    private LicenseManager() {}

    private static final String KEY = "license_key";
    private static final String FIRST = "license_first";
    private static final String DAYS = "license_days";
    private static final String MINUTES = "license_minutes";
    private static final String STATUS = "license_status";
    private static final String PAUSED = "license_paused";
    private static final String USER = "license_user";
    private static final String DEVICE_ID = "device_id";

    public static final class Result {
        public boolean ok;
        public String message = "Falha ao validar key";
        public String key = "";
        public String user = "";
        public String status = "active";
        public long firstUsedSec;
        public long pausedAtSec;
        public int days;
        public int minutes;
        public boolean networkError;
    }

    public static Result validate(Context ctx, String rawKey) {
        Result out = new Result();
        String key = rawKey == null ? "" : rawKey.trim();
        if (key.isEmpty() || key.length() > 160) {
            out.message = "Insira uma key válida";
            return out;
        }
        try {
            String myDevice = stableDeviceId(ctx);
            JSONArray results = com.replayx.sender.util.Fs.query("keys", "keyString", key, 2);
            if (results.length() == 0 || !results.getJSONObject(0).has("document")) {
                if (com.replayx.sender.util.Fs.lastQueryNetworkError()) {
                    out.networkError = true;
                    out.message = "Não foi possível validar a key agora";
                } else {
                    out.message = "Key inválida ou apagada";
                }
                return out;
            }
            JSONObject doc = results.getJSONObject(0).getJSONObject("document");
            String docId = com.replayx.sender.util.Fs.docIdFromName(doc.getString("name"));
            JSONObject fields = doc.optJSONObject("fields");
            if (fields == null) fields = new JSONObject();

            String status = com.replayx.sender.util.Fs.getStr(fields, "status", "");
            if (!"active".equals(status)) {
                out.message = "paused".equals(status) ? "Key pausada" : "Key inativa";
                return out;
            }
            String boundDevice = com.replayx.sender.util.Fs.getStr(fields, "deviceId", "");
            if (!boundDevice.isEmpty() && !boundDevice.equals(myDevice)) {
                out.message = "Key já está vinculada a outro dispositivo";
                return out;
            }

            int days = safeInt(com.replayx.sender.util.Fs.getLong(fields, "days", 0L));
            int minutes = safeInt(com.replayx.sender.util.Fs.getLong(fields, "minutes", 0L));
            long now = System.currentTimeMillis() / 1000L;
            Long first = com.replayx.sender.util.Fs.getTsSec(fields, "firstUsed");
            Long paused = com.replayx.sender.util.Fs.getTsSec(fields, "pausedAt");
            long firstSec = first == null ? now : first;
            long pausedSec = paused == null ? 0L : paused;
            long duration = days * 86400L + minutes * 60L;
            long referenceNow = "paused".equals(status) && pausedSec > 0L ? pausedSec : now;
            if (duration > 0L && referenceNow - firstSec >= duration) {
                out.message = "Key expirada";
                return out;
            }

            boolean needsBind = boundDevice.isEmpty() || first == null;
            if (needsBind) {
                JSONObject patch = new JSONObject();
                patch.put("deviceId", com.replayx.sender.util.Fs.str(myDevice));
                patch.put("deviceModel", com.replayx.sender.util.Fs.str(Build.MANUFACTURER + " " + Build.MODEL));
                if (first == null) patch.put("firstUsed", com.replayx.sender.util.Fs.ts(firstSec));
                String mask = "updateMask.fieldPaths=deviceId&updateMask.fieldPaths=deviceModel"
                        + (first == null ? "&updateMask.fieldPaths=firstUsed" : "");
                if (!com.replayx.sender.util.Fs.patchDoc("keys/" + docId, patch, mask)) {
                    out.message = "Não foi possível vincular esta key ao dispositivo";
                    return out;
                }
            }

            out.ok = true;
            out.message = "Key validada com sucesso";
            out.key = key;
            out.user = com.replayx.sender.util.Fs.getStr(fields, "user", "");
            out.status = status;
            out.firstUsedSec = firstSec;
            out.pausedAtSec = pausedSec;
            out.days = days;
            out.minutes = minutes;
            save(ctx, out);
            return out;
        } catch (Exception e) {
            out.networkError = true;
            out.message = "Não foi possível validar a key agora";
            return out;
        }
    }

    public static void save(Context ctx, Result r) {
        SecureStore.put(ctx, KEY, r.key);
        SecureStore.put(ctx, FIRST, String.valueOf(r.firstUsedSec));
        SecureStore.put(ctx, DAYS, String.valueOf(r.days));
        SecureStore.put(ctx, MINUTES, String.valueOf(r.minutes));
        SecureStore.put(ctx, STATUS, r.status);
        SecureStore.put(ctx, PAUSED, String.valueOf(r.pausedAtSec));
        SecureStore.put(ctx, USER, r.user);
    }

    public static String savedKey(Context ctx) { return SecureStore.get(ctx, KEY, ""); }
    public static String savedUser(Context ctx) { return SecureStore.get(ctx, USER, ""); }

    public static boolean hasLocalLicense(Context ctx) {
        String key = savedKey(ctx);
        if (key.isEmpty()) return false;
        return remainingMs(ctx) != 0L;
    }

    public static long remainingMs(Context ctx) {
        try {
            long first = Long.parseLong(SecureStore.get(ctx, FIRST, "-1"));
            if (first < 0L) return -1L;
            int days = Integer.parseInt(SecureStore.get(ctx, DAYS, "0"));
            int minutes = Integer.parseInt(SecureStore.get(ctx, MINUTES, "0"));
            long total = (days * 86400L + minutes * 60L) * 1000L;
            if (total <= 0L) return Long.MAX_VALUE;
            long paused = Long.parseLong(SecureStore.get(ctx, PAUSED, "0"));
            String status = SecureStore.get(ctx, STATUS, "active");
            long used = "paused".equals(status) && paused > 0L
                    ? (paused - first) * 1000L
                    : System.currentTimeMillis() - first * 1000L;
            return Math.max(0L, total - used);
        } catch (Exception e) {
            return 0L;
        }
    }

    public static void clear(Context ctx) {
        SecureStore.remove(ctx, KEY);
        SecureStore.remove(ctx, FIRST);
        SecureStore.remove(ctx, DAYS);
        SecureStore.remove(ctx, MINUTES);
        SecureStore.remove(ctx, STATUS);
        SecureStore.remove(ctx, PAUSED);
        SecureStore.remove(ctx, USER);
    }

    public static String stableDeviceId(Context ctx) {
        String saved = SecureStore.get(ctx, DEVICE_ID, "");
        if (!saved.isEmpty()) return saved;
        String androidId = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (androidId == null || androidId.trim().isEmpty() || "9774d56d682e549c".equalsIgnoreCase(androidId)) {
            androidId = "";
        }
        if (!androidId.isEmpty()) {
            SecureStore.put(ctx, DEVICE_ID, androidId);
            return androidId;
        }
        String generated = java.util.UUID.randomUUID().toString();
        SecureStore.put(ctx, DEVICE_ID, generated);
        return generated;
    }

    private static int safeInt(long v) {
        if (v <= 0L) return 0;
        return v > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) v;
    }
}
