package com.replayx.sender.util;

import android.content.Context;
import org.json.JSONObject;
import java.util.Random;

/**
 * Lado do Enviador: gera código de pareamento (uso único, expira em 10 min),
 * espera o Receptor conectar, e permite desparear.
 */
public final class PairingManager {
    private PairingManager() {}

    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    public static String genCode() {
        Random r = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) sb.append(CHARS.charAt(r.nextInt(CHARS.length())));
        return sb.toString();
    }

    /** Cria o código no Firestore e prepara o doc de pairing (status none). */
    public static boolean createPairing(Context ctx, String code) {
        try {
            String myId = DeviceId.get(ctx);
            long now = System.currentTimeMillis() / 1000L;
            JSONObject codeFields = new JSONObject();
            codeFields.put("senderId", Fs.str(myId));
            codeFields.put("createdAt", Fs.ts(now));
            codeFields.put("expiresAt", Fs.ts(now + 600));
            codeFields.put("used", Fs.bool(false));
            boolean ok1 = Fs.patchDoc("pair_codes/" + code, codeFields);

            JSONObject pairFields = new JSONObject();
            pairFields.put("status", Fs.str("none"));
            pairFields.put("receiverId", Fs.str(""));
            pairFields.put("receiverModel", Fs.str(""));
            pairFields.put("receiverBattery", Fs.num(0));
            boolean ok2 = Fs.patchDoc("pairings/" + myId, pairFields);
            return ok1 && ok2;
        } catch (Exception e) {
            return false;
        }
    }

    /** Consulta o estado atual do pareamento. */
    public static JSONObject getStatus(Context ctx) {
        String myId = DeviceId.get(ctx);
        return Fs.getDoc("pairings/" + myId);
    }

    public static boolean unpair(Context ctx) {
        try {
            String myId = DeviceId.get(ctx);
            JSONObject f = new JSONObject();
            f.put("status", Fs.str("none"));
            f.put("receiverId", Fs.str(""));
            f.put("receiverModel", Fs.str(""));
            f.put("receiverBattery", Fs.num(0));
            return Fs.patchDoc("pairings/" + myId, f);
        } catch (Exception e) {
            return false;
        }
    }
}
