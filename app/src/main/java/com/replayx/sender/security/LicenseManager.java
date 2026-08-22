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
    private static final String REMEMBER = "license_remember";

    // Mantém a sessão recém-validada durante o processo caso o Android Keystore
    // esteja temporariamente indisponível para uma leitura imediata.
    private static volatile String processKey = "";
    private static volatile String processUser = "";
    private static volatile String processStatus = "active";
    private static volatile long processFirstUsedSec = -1L;
    private static volatile long processPausedAtSec = 0L;
    private static volatile int processDays = 0;
    private static volatile int processMinutes = 0;
    private static volatile int processDeviceCount = 0;

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
        return validate(ctx, rawKey, true);
    }

    public static Result validate(Context ctx, String rawKey, boolean remember) {
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
                    out.message = com.replayx.sender.util.Fs.lastQueryDiagnostic("consulta da key");
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
            // O painel cria devicesUsed=0. O contador é a fonte primária:
            // primeiro vínculo 0->1, segundo vínculo 1->2. Os slots guardam
            // apenas quais dispositivos correspondem a cada uso.
            int storedCount = safeInt(com.replayx.sender.util.Fs.getLong(fields, "devicesUsed", 0L));
            int count = Math.min(2, Math.max(storedCount, (!slot1.isEmpty() ? 1 : 0) + (!slot2.isEmpty() ? 1 : 0)));

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
                if (!persistSlotRegistration(docId, migration, migrationMask.toString(), slot1, 1)) {
                    out.message = com.replayx.sender.util.Fs.lastPatchDiagnostic("migração do dispositivo");
                    return out;
                }
                count = 1;
            }

            if (!slot1.isEmpty() && slot1.equals(myDevice) || !slot2.isEmpty() && slot2.equals(myDevice)) {
                // Dispositivo já autorizado; apenas atualiza o estado local.
            } else {
                JSONObject patch = new JSONObject();
                StringBuilder mask = new StringBuilder();
                if (count >= 2) {
                    out.message = "Esta key já está vinculada a 2 dispositivos";
                    return out;
                } else if (slot1.isEmpty()) {
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
                    out.message = "Não foi possível localizar um slot livre";
                    return out;
                }
                putPatch(patch, mask, "devicesUsed", com.replayx.sender.util.Fs.num(count));
                if (!persistSlotRegistration(docId, patch, mask.toString(), myDevice, count)) {
                    out.message = com.replayx.sender.util.Fs.lastPatchDiagnostic("registro do dispositivo");
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
            if (remember) {
                save(ctx, out);
            } else {
                clearPersistedSession(ctx);
            }
            cacheProcessSession(out);
            return out;
        } catch (Exception e) {
            out.networkError = true;
            out.message = "Não foi possível validar a key agora";
            return out;
        }
    }

    private static boolean persistSlotRegistration(String docId, JSONObject patch, String mask,
                                                    String expectedDevice, int expectedCount) {
        String path = "keys/" + docId;
        // Fs.patchDoc já tenta Commit e PATCH mantendo a máscara; não usamos
        // uma escrita sem máscara para não correr o risco de substituir o doc.
        boolean ok = com.replayx.sender.util.Fs.patchDoc(path, patch, mask, "");
        if (!ok) return false;

        JSONObject after = com.replayx.sender.util.Fs.getDoc(path);
        // O Commit/PATCH já confirmou a gravação. Se a leitura de confirmação
        // falhar temporariamente, não bloqueie um login válido recém-registrado.
        if (after == null) return true;
        boolean visible = (expectedDevice.equals(com.replayx.sender.util.Fs.getStr(after, "slot1DeviceId", ""))
                    || expectedDevice.equals(com.replayx.sender.util.Fs.getStr(after, "slot2DeviceId", "")))
                && com.replayx.sender.util.Fs.getLong(after, "devicesUsed", -1L) >= expectedCount;
        if (!visible) {
            ok = com.replayx.sender.util.Fs.patchDoc(path, patch, mask, "");
            if (!ok) return false;
            after = com.replayx.sender.util.Fs.getDoc(path);
            if (after == null) return true;
            visible = (expectedDevice.equals(com.replayx.sender.util.Fs.getStr(after, "slot1DeviceId", ""))
                        || expectedDevice.equals(com.replayx.sender.util.Fs.getStr(after, "slot2DeviceId", "")))
                    && com.replayx.sender.util.Fs.getLong(after, "devicesUsed", -1L) >= expectedCount;
        }
        return visible;
    }

    private static void putPatch(JSONObject patch, StringBuilder mask, String path, Object value) throws Exception {
        // Firestore REST exige que cada campo seja um Value tipado, nunca uma
        // String/Number Java crua. O erro HTTP 400 vinha dos campos textuais
        // do slot enviados sem o wrapper stringValue.
        JSONObject firestoreValue;
        if (value instanceof JSONObject) {
            firestoreValue = (JSONObject) value;
        } else if (value == null) {
            firestoreValue = com.replayx.sender.util.Fs.nul();
        } else if (value instanceof Boolean) {
            firestoreValue = com.replayx.sender.util.Fs.bool((Boolean) value);
        } else if (value instanceof Number) {
            firestoreValue = com.replayx.sender.util.Fs.num(((Number) value).longValue());
        } else {
            firestoreValue = com.replayx.sender.util.Fs.str(String.valueOf(value));
        }
        patch.put(path, firestoreValue);
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
        SecureStore.putRemembered(ctx, r.key);
        SecureStore.put(ctx, REMEMBER, "true");
    }

    private static void clearPersistedSession(Context ctx) {
        SecureStore.remove(ctx, KEY);
        SecureStore.remove(ctx, FIRST);
        SecureStore.remove(ctx, DAYS);
        SecureStore.remove(ctx, MINUTES);
        SecureStore.remove(ctx, STATUS);
        SecureStore.remove(ctx, PAUSED);
        SecureStore.remove(ctx, USER);
        SecureStore.remove(ctx, DEVICE_COUNT);
        SecureStore.removeRemembered(ctx);
    }

    public static boolean shouldRemember(Context ctx) {
        return "true".equalsIgnoreCase(SecureStore.get(ctx, REMEMBER, "true"));
    }

    public static void setRemember(Context ctx, boolean remember) {
        SecureStore.put(ctx, REMEMBER, remember ? "true" : "false");
        if (!remember) clearPersistedSession(ctx);
    }

    private static void cacheProcessSession(Result r) {
        processKey = r.key == null ? "" : r.key;
        processUser = r.user == null ? "" : r.user;
        processStatus = r.status == null ? "active" : r.status;
        processFirstUsedSec = r.firstUsedSec;
        processPausedAtSec = r.pausedAtSec;
        processDays = r.days;
        processMinutes = r.minutes;
        processDeviceCount = r.deviceCount;
    }

    public static String savedKey(Context ctx) {
        String value = SecureStore.get(ctx, KEY, "");
        if (value.isEmpty()) value = SecureStore.getRemembered(ctx, "");
        return value.isEmpty() ? processKey : value;
    }
    public static String savedUser(Context ctx) {
        String value = SecureStore.get(ctx, USER, "");
        return value.isEmpty() ? processUser : value;
    }
    public static int savedDeviceCount(Context ctx) {
        try { return Integer.parseInt(SecureStore.get(ctx, DEVICE_COUNT, String.valueOf(processDeviceCount))); }
        catch (Exception e) { return processDeviceCount; }
    }

    public static boolean hasLocalLicense(Context ctx) {
        String key = savedKey(ctx);
        if (key.isEmpty()) return false;
        return remainingMs(ctx) == Long.MAX_VALUE || remainingMs(ctx) > 0L;
    }

    public static long remainingMs(Context ctx) {
        try {
            long first = Long.parseLong(SecureStore.get(ctx, FIRST, String.valueOf(processFirstUsedSec)));
            int days = Integer.parseInt(SecureStore.get(ctx, DAYS, String.valueOf(processDays)));
            int minutes = Integer.parseInt(SecureStore.get(ctx, MINUTES, String.valueOf(processMinutes)));
            String status = SecureStore.get(ctx, STATUS, processStatus);
            long paused = Long.parseLong(SecureStore.get(ctx, PAUSED, String.valueOf(processPausedAtSec)));
            if (first < 0L) first = processFirstUsedSec;
            if (first < 0L) return 0L;
            long total = (days * 86400L + minutes * 60L) * 1000L;
            if (total <= 0L) return Long.MAX_VALUE;
            long used = "paused".equals(status) && paused > 0L
                    ? (paused - first) * 1000L
                    : System.currentTimeMillis() - first * 1000L;
            return Math.max(0L, total - used);
        } catch (Exception e) {
            if (!processKey.isEmpty() && processFirstUsedSec >= 0L) {
                long total = (processDays * 86400L + processMinutes * 60L) * 1000L;
                if (total <= 0L) return Long.MAX_VALUE;
                return Math.max(0L, total - (System.currentTimeMillis() - processFirstUsedSec * 1000L));
            }
            return 0L;
        }
    }

    public static void clear(Context ctx) {
        processKey = "";
        processUser = "";
        processStatus = "active";
        processFirstUsedSec = -1L;
        processPausedAtSec = 0L;
        processDays = 0;
        processMinutes = 0;
        processDeviceCount = 0;
        clearPersistedSession(ctx);
        SecureStore.remove(ctx, REMEMBER);
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
