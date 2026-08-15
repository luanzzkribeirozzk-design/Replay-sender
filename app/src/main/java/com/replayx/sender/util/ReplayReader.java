package com.replayx.sender.util;

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

        String binPath = RootShell.run("ls -t \"" + foundDir + "\"/*.bin 2>/dev/null | head -n 1");
        if (binPath == null) binPath = "";
        binPath = binPath.trim();
        if (binPath.isEmpty() || !binPath.endsWith(".bin")) {
            log.onLog("[ERR] Pasta achada (" + foundDir + ") mas sem nenhum arquivo .bin dentro");
            return null;
        }

        String baseNoExt = binPath.substring(0, binPath.length() - 4);
        String jsonPath = baseNoExt + ".json";
        String jsonExists = RootShell.run("[ -f \"" + jsonPath + "\" ] && echo EXISTE || echo NAO_EXISTE");
        if (jsonExists == null || !jsonExists.contains("EXISTE")) jsonPath = "";

        log.onLog("[OK] replay localizado: " + fileName(binPath));

        Found f = new Found();
        f.binPath = binPath;
        f.jsonPath = jsonPath;
        f.binData = RootShell.runRaw("cat \"" + binPath + "\"");
        if (!jsonPath.isEmpty()) {
            f.jsonData = RootShell.runRaw("cat \"" + jsonPath + "\"");
        }

        if (f.binData == null || f.binData.length == 0) {
            log.onLog("[ERR] Achou o arquivo mas não conseguiu ler o conteúdo (permissão negada?)");
            return null;
        }
        log.onLog("[OK] arquivo lido: " + f.binData.length + " bytes");
        return f;
    }

    public static String fileName(String path) {
        if (path == null) return "";
        int i = path.lastIndexOf('/');
        return i >= 0 ? path.substring(i + 1) : path;
    }
}
