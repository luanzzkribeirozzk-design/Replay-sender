package com.replayx.sender.security;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

/** Licença compartilhada: uma key, no máximo dois dispositivos, sem reset no app. */
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
    private static final String DEVICE_COUNT = "license_device_count";

    public static final class Result {
        public boolean ok;
        public boolean networkError;
        public String message = "Falha ao validar key";
        public String key = "";
        public String user = "";
        public String status = "active";
        public long firstUsedSec;
        public long pausedAtSec;
        public int days;
        public int minutes;
        public int deviceCount;
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

            String legacyDevice = com.replayx.sender.util.Fs.getStr(fields, "deviceId", "");
            String slot1 = com.replayx.sender.util.Fs.getStr(fields, "slot1DeviceId", "");
            String slot2 = com.replayx.sender.util.Fs.getStr(fields, "slot2DeviceId", "");
            String slot1Type = com.replayx.sender.util.Fs.getStr(fields, "slot1Type", "");
            String slot1Model = com.replayx.sender.util.Fs.getStr(fields, "slot1Model", "");
            int count = 0;
            if (!slot1.isEmpty()) count++;
            if (!slot2.isEmpty()) count++;

            if (slot1.isEmpty() && !legacyDevice.isEmpty()) {
                JSONObject migration = new JSONObject();
                StringBuilder migrationMask = new StringBuilder();
                slot1 = legacyDevice;
                slot1Type = slot1Type.isEmpty() ? "legacy" : slot1Type;
                slot1Model = slot1Model.isEmpty()
                        ? com.replayx.sender.util.Fs.getStr(fields, "deviceModel", "") : slot1Model;
                putPatch(migration, migrationMask, "slot1DeviceId", slot1);
                putPatch(migration, migrationMask, "slot1Type", slot1Type);
                putPatch(migration, migrationMask, "slot1Model", slot1Model);
                putPatch(migration, migrationMask, "slot1UsedAt", com.replayx.sender.util.Fs.ts(firstSec));
                if (first == null) putPatch(migration, migrationMask, "firstUsed", com.replayx.sender.util.Fs.ts(firstSec));
                putPatch(migration, migrationMask, "devicesUsed", com.replayx.sender.util.Fs.num(1));
                if (!com.replayx.sender.util.Fs.patchDoc("keys/" + docId, migration, migrationMask.toString(), doc.optString("updateTime", ""))) {
                    int code = com.replayx.sender.util.Fs.lastPatchHttpCode();
                    out.message = code == 403
                            ? "Firebase recusou a migração; publique as Rules de dois dispositivos"
                            : "Não foi possível migrar o vínculo desta key (HTTP " + code + ")";
                    return out;
                }
                count = 1;
            }

            if (!slot1.isEmpty() && slot1.equals(myDevice) || !slot2.isEmpty() && slot2.equals(myDevice)) {
                // Dispositivo já autorizado; apenas atualiza o estado local.
            } else {
                JSONObject patch = new JSONObject();
                StringBuilder mask = new StringBuilder();
                if (slot1.isEmpty()) {
                    putPatch(patch, mask, "slot1DeviceId", myDevice);
                    putPatch(patch, mask, "slot1Type", "sender");
                    putPatch(patch, mask, "slot1Model", Build.MANUFACTURER + " " + Build.MODEL);
                    putPatch(patch, mask, "slot1UsedAt", com.replayx.sender.util.Fs.ts(now));
                    if (first == null) putPatch(patch, mask, "firstUsed", com.replayx.sender.util.Fs.ts(now));
                    slot1 = myDevice;
                    count = Math.max(count, 1);
                } else if (slot2.isEmpty()) {
                    putPatch(patch, mask, "slot2DeviceId", myDevice);
                    putPatch(patch, mask, "slot2Type", "sender");
                    putPatch(patch, mask, "slot2Model", Build.MANUFACTURER + " " + Build.MODEL);
                    putPatch(patch, mask, "slot2UsedAt", com.replayx.sender.util.Fs.ts(now));
                    if (first == null) putPatch(patch, mask, "firstUsed", com.replayx.sender.util.Fs.ts(now));
                    slot2 = myDevice;
                    count = 2;
                } else {
                    out.message = "Esta key já está vinculada a 2 dispositivos";
                    return out;
                }
                putPatch(patch, mask, "devicesUsed", com.replayx.sender.util.Fs.num(count));
                if (!com.replayx.sender.util.Fs.patchDoc("keys/" + docId, patch, mask.toString(), doc.optString("updateTime", ""))) {
                    int code = com.replayx.sender.util.Fs.lastPatchHttpCode();
                    out.message = code == 403
                            ? "Firebase recusou o registro; publique as Rules de dois dispositivos"
                            : code == 412 || code == 409
                                ? "A key foi alterada; tente entrar novamente"
                                : "Não foi possível registrar este dispositivo (HTTP " + code + ")";
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
            out.deviceCount = Math.min(2, count > 0 ? count : (slot1.isEmpty() ? 0 : 1) + (slot2.isEmpty() ? 0 : 1));
            save(ctx, out);
            return out;
        } catch (Exception e) {
            out.networkError = true;
            out.message = "Não foi possível validar a key agora";
            return out;
        }
    }

    private static void putPatch(JSONObject patch, StringBuilder mask, String path, Object value) throws Exception {
        patch.put(path, value);
        if (mask.length() > 0) mask.append('&');
        mask.append("updateMask.fieldPaths=").append(path);
    }

    public static void save(Context ctx, Result r) {
        SecureStore.put(ctx, KEY, r.key);
        SecureStore.put(ctx, FIRST, String.valueOf(r.firstUsedSec));
        SecureStore.put(ctx, DAYS, String.valueOf(r.days));
        SecureStore.put(ctx, MINUTES, String.valueOf(r.minutes));
        SecureStore.put(ctx, STATUS, r.status);
        SecureStore.put(ctx, PAUSED, String.valueOf(r.pausedAtSec));
        SecureStore.put(ctx, USER, r.user);
        SecureStore.put(ctx, DEVICE_COUNT, String.valueOf(r.deviceCount));
    }

    public static String savedKey(Context ctx) { return SecureStore.get(ctx, KEY, ""); }
    public static String savedUser(Context ctx) { return SecureStore.get(ctx, USER, ""); }
    public static int savedDeviceCount(Context ctx) {
        try { return Integer.parseInt(SecureStore.get(ctx, DEVICE_COUNT, "1")); }
        catch (Exception e) { return 1; }
    }

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
        SecureStore.remove(ctx, DEVICE_COUNT);
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
