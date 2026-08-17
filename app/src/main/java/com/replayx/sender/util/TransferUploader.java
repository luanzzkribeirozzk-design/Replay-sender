package com.replayx.sender.util;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Base64;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Sobe o replay (bin + json) pro Firestore em pedaços, já que cada
 * documento aguenta no máximo ~1MB. O arquivo vira texto base64 e é
 * repartido em pedaços de até 700 mil caracteres por documento.
 */
public final class TransferUploader {
    private TransferUploader() {}

    private static final int CHUNK_CHARS = 700_000;

    public interface Progress {
        void onLog(String msg);
    }

    /**
     * @param targetPkg pacote de origem no PC (com.dts.freefiremax ou com.dts.freefireth)
     *                   — serve só de rótulo informativo pro Receptor saber de onde veio.
     */
    public static boolean upload(Context ctx, ReplayReader.Found found, String targetPkg, Progress p) {
        try {
            if (found == null || found.binData == null || found.binData.length == 0) {
                p.onLog("[ERR] REPLAY_SEM_BIN_VALIDO");
                return false;
            }
            if (found.jsonData == null || found.jsonData.length == 0) {
                p.onLog("[ERR] REPLAY_SEM_JSON_VALIDO — o Free Fire precisa do par .bin + .json");
                return false;
            }
            String binName = ReplayReader.fileName(found.binPath);
            String jsonName = ReplayReader.fileName(found.jsonPath);
            if (!sameStem(binName, jsonName)) {
                p.onLog("[ERR] REPLAY_INCOMPATIVEL — .bin e .json não têm o mesmo nome-base");
                return false;
            }

            String receiverId = getPairedReceiverId(ctx);
            if (receiverId == null || receiverId.isEmpty()) {
                p.onLog("[ERR] NENHUM_DISPOSITIVO_PAREADO");
                return false;
            }

            String transferId = "tr_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 6);
            String senderId = DeviceId.get(ctx);

            String binB64 = Base64.encodeToString(found.binData, Base64.NO_WRAP);
            String jsonB64 = found.jsonData != null ? Base64.encodeToString(found.jsonData, Base64.NO_WRAP) : "";

            int binChunks = uploadChunks(transferId, "chunks_bin", binB64, p);
            int jsonChunks = jsonB64.isEmpty() ? 0 : uploadChunks(transferId, "chunks_json", jsonB64, p);

            JSONObject fields = new JSONObject();
            fields.put("senderId", Fs.str(senderId));
            fields.put("receiverId", Fs.str(receiverId));
            fields.put("sourcePkg", Fs.str(targetPkg));
            fields.put("sourceVersion", Fs.str(installedVersion(ctx, targetPkg)));
            fields.put("replayVersion", Fs.str(extractJsonVersion(found.jsonData)));
            fields.put("binName", Fs.str(binName));
            fields.put("jsonName", Fs.str(jsonName));
            fields.put("totalChunksBin", Fs.num(binChunks));
            fields.put("totalChunksJson", Fs.num(jsonChunks));
            fields.put("status", Fs.str("pending"));
            fields.put("createdAt", Fs.ts(System.currentTimeMillis() / 1000L));

            boolean ok = Fs.patchDoc("transfers/" + transferId, fields);
            if (!ok) {
                p.onLog("[ERR] FALHA_AO_REGISTRAR_TRANSFERENCIA");
                return false;
            }
            p.onLog("[OK] Replay enviado (" + binChunks + " pedaço(s))");
            return true;
        } catch (Exception e) {
            p.onLog("[ERR] " + e.getMessage());
            return false;
        }
    }

    private static int uploadChunks(String transferId, String subcol, String b64, Progress p) throws Exception {
        int total = (int) Math.ceil(b64.length() / (double) CHUNK_CHARS);
        for (int i = 0; i < total; i++) {
            int start = i * CHUNK_CHARS;
            int end = Math.min(start + CHUNK_CHARS, b64.length());
            String piece = b64.substring(start, end);
            JSONObject f = new JSONObject().put("data", Fs.str(piece));
            boolean ok = Fs.patchDoc("transfers/" + transferId + "/" + subcol + "/c" + i, f);
            if (!ok) throw new Exception("FALHA_UPLOAD_PEDACO_" + i);
            p.onLog("[..] enviando " + subcol + " " + (i + 1) + "/" + total);
        }
        return total;
    }

    private static String installedVersion(Context ctx, String pkg) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(pkg, 0);
            return pi.versionName == null ? "" : pi.versionName.trim();
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String extractJsonVersion(byte[] jsonData) {
        try {
            JSONObject metadata = new JSONObject(new String(jsonData, StandardCharsets.UTF_8).trim());
            String version = metadata.optString("GameVersion", "");
            if (version.isEmpty()) version = metadata.optString("Version", "");
            return version.trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean sameStem(String binName, String jsonName) {
        if (binName == null || jsonName == null) return false;
        String binStem = binName.toLowerCase(java.util.Locale.US).endsWith(".bin")
                ? binName.substring(0, binName.length() - 4) : binName;
        String jsonStem = jsonName.toLowerCase(java.util.Locale.US).endsWith(".json")
                ? jsonName.substring(0, jsonName.length() - 5) : jsonName;
        return !binStem.isEmpty() && binStem.equals(jsonStem);
    }

    private static String getPairedReceiverId(Context ctx) {
        try {
            String myId = DeviceId.get(ctx);
            JSONObject fields = Fs.getDoc("pairings/" + myId);
            if (fields == null) return null;
            String status = Fs.getStr(fields, "status", "none");
            if (!"connected".equals(status)) return null;
            return Fs.getStr(fields, "receiverId", "");
        } catch (Exception e) {
            return null;
        }
    }
}
