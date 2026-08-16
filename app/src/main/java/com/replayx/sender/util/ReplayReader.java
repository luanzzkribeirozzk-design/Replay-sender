package com.replayx.sender.util;

import java.util.Locale;

/**
 * Acha e lê o replay mais recente (.bin + .json) de dentro da pasta privada
 * do Free Fire (Normal ou MAX). Testa cada caminho candidato separadamente
 * (mais robusto em shells de emulador) e loga cada tentativa, em vez de um
 * script gigante de uma vez só que falhava silenciosamente.
 */
public final class ReplayReader {
    private ReplayReader() {}

    public static final String FFM_PKG = "com.dts.freefiremax";
    public static final String FFN_PKG = "com.dts.freefireth";

    public interface Log {
        void onLog(String msg);
    }

    public static class Found {
        public String binPath;
        public String jsonPath;
        public byte[] binData;
        public byte[] jsonData;
    }

    private static final String[] BASES = {
        "/storage/emulated/0",
        "/sdcard",
        "/data/media/0",
        "/mnt/user/0",
        "/storage/self/primary"
    };

    private static final String[] SUBDIRS = {
        "files/MReplays",
        "files/Replays",
        "files"
    };

    /** Localiza e lê o replay mais recente do pacote indicado. Null se não achar. */
    public static Found readLatest(String pkg, Log log) {
        if (!RootShell.hasRoot() && !RootShell.hasShizuku()) {
            log.onLog("[ERR] SEM_ACESSO_ROOT_NEM_SHIZUKU — conceda uma das duas permissões primeiro");
            return null;
        }

        String foundDir = null;
        for (String base : BASES) {
            for (String sub : SUBDIRS) {
                String dir = base + "/Android/data/" + pkg + "/" + sub;
                String r = RootShell.run("[ -d \"" + dir + "\" ] && echo EXISTE || echo NAO_EXISTE");
                log.onLog("[..] checando " + dir + " -> " + (r == null ? "SEM_RESPOSTA" : r.trim()));
                if (r != null && r.contains("EXISTE")) {
                    foundDir = dir;
                    break;
                }
            }
            if (foundDir != null) break;
        }

        if (foundDir == null) {
            log.onLog("[..] pasta MReplays não achada nos caminhos comuns, tentando busca ampla (find)...");
            for (String base : BASES) {
                String dir = base + "/Android/data/" + pkg;
                String r = RootShell.run("find \"" + dir + "\" -iname '*.bin' 2>/dev/null | head -n 1");
                if (r != null && r.trim().endsWith(".bin")) {
                    String path = r.trim();
                    int lastSlash = path.lastIndexOf('/');
                    foundDir = lastSlash > 0 ? path.substring(0, lastSlash) : null;
                    log.onLog("[OK] achado via busca ampla: " + foundDir);
                    break;
                }
            }
        }

        if (foundDir == null) {
            log.onLog("[ERR] REPLAY_NAO_ENCONTRADO — Free Fire (" + pkg + ") não instalado, ou nunca salvou um replay, ou a pasta tem outro nome/local nessa instalação");
            return null;
        }

        String listedBins = RootShell.run("ls -t \"" + foundDir + "\"/*.bin 2>/dev/null");
        if (listedBins == null || listedBins.trim().isEmpty()) {
            log.onLog("[ERR] Pasta achada (" + foundDir + ") mas sem nenhum arquivo .bin dentro");
            return null;
        }

        String binPath = null;
        String jsonPath = null;
        for (String candidate : listedBins.split("\\n")) {
            candidate = candidate.trim();
            if (!candidate.endsWith(".bin")) continue;
            String candidateJson = candidate.substring(0, candidate.length() - 4) + ".json";
            String jsonExists = RootShell.run("[ -f \"" + candidateJson + "\" ] && echo EXISTE || echo NAO_EXISTE");
            if (jsonExists != null && jsonExists.contains("EXISTE")) {
                binPath = candidate;
                jsonPath = candidateJson;
                break;
            }
        }
        if (binPath == null || jsonPath == null) {
            log.onLog("[ERR] REPLAY_INCOMPLETO — não existe um par .bin + .json válido em " + foundDir);
            return null;
        }

        String binName = fileName(binPath);
        String jsonName = fileName(jsonPath);
        if (!sameStem(binName, jsonName)) {
            log.onLog("[ERR] REPLAY_INCOMPATIVEL — .bin e .json não têm o mesmo nome-base");
            return null;
        }

        log.onLog("[OK] replay localizado: " + binName + " + " + jsonName);

        Found f = new Found();
        f.binPath = binPath;
        f.jsonPath = jsonPath;
        f.binData = RootShell.runRaw("cat \"" + binPath + "\"");
        f.jsonData = RootShell.runRaw("cat \"" + jsonPath + "\"");

        if (f.binData == null || f.binData.length == 0) {
            log.onLog("[ERR] O .bin foi encontrado, mas está vazio ou não pôde ser lido");
            return null;
        }
        if (f.jsonData == null || f.jsonData.length == 0) {
            log.onLog("[ERR] O .json foi encontrado, mas está vazio ou não pôde ser lido");
            return null;
        }
        log.onLog("[OK] par validado: .bin=" + f.binData.length + " bytes, .json=" + f.jsonData.length + " bytes");
        return f;
    }

    private static boolean sameStem(String binName, String jsonName) {
        String binStem = binName.toLowerCase(Locale.US).endsWith(".bin")
                ? binName.substring(0, binName.length() - 4) : binName;
        String jsonStem = jsonName.toLowerCase(Locale.US).endsWith(".json")
                ? jsonName.substring(0, jsonName.length() - 5) : jsonName;
        return !binStem.isEmpty() && binStem.equals(jsonStem);
    }

    public static String fileName(String path) {
        if (path == null) return "";
        int i = path.lastIndexOf('/');
        return i >= 0 ? path.substring(i + 1) : path;
    }
}
