package com.replayx.sender.util;

/**
 * Acha e lê o replay mais recente (.bin + .json) de dentro da pasta privada
 * do Free Fire (Normal ou MAX), usando os mesmos caminhos candidatos do
 * Combo Replay original.
 */
public final class ReplayReader {
    private ReplayReader() {}

    public static final String FFM_PKG = "com.dts.freefiremax";
    public static final String FFN_PKG = "com.dts.freefireth";

    public static class Found {
        public String binPath;
        public String jsonPath;
        public byte[] binData;
        public byte[] jsonData;
    }

    /** Localiza os caminhos do .bin/.json mais recentes, sem ler o conteúdo ainda. */
    private static String locate(String pkg) {
        String cmd =
            "SRC=''; " +
            "for P in " +
            "'/storage/emulated/0/Android/data/" + pkg + "/files/MReplays' " +
            "'/sdcard/Android/data/" + pkg + "/files/MReplays' " +
            "'/data/media/0/Android/data/" + pkg + "/files/MReplays' " +
            "'/mnt/user/0/" + pkg + "/files/MReplays' " +
            "'/data/data/" + pkg + "/files/MReplays' " +
            "; do [ -d \"$P\" ] && SRC=\"$P\" && break; done; " +
            "if [ -z \"$SRC\" ]; then echo PASTA_NAO_ENCONTRADA; exit 0; fi; " +
            "BIN=$(ls -t \"$SRC\"/*.bin 2>/dev/null | head -n 1); " +
            "JSON=$(ls -t \"$SRC\"/*.json 2>/dev/null | head -n 1); " +
            "if [ -z \"$BIN\" ]; then echo NAO_ENCONTRADO; exit 0; fi; " +
            "echo \"$BIN|$JSON\"";
        return RootShell.run(cmd);
    }

    /** Localiza e lê o replay mais recente do pacote indicado. Null se não achar. */
    public static Found readLatest(String pkg) {
        String loc = locate(pkg);
        if (loc == null || loc.contains("PASTA_NAO_ENCONTRADA") || loc.contains("NAO_ENCONTRADO") || !loc.contains("|")) {
            return null;
        }
        String[] parts = loc.split("\\|", 2);
        String binPath = parts[0].trim();
        String jsonPath = parts.length > 1 ? parts[1].trim() : "";
        if (binPath.isEmpty()) return null;

        Found f = new Found();
        f.binPath = binPath;
        f.jsonPath = jsonPath;
        f.binData = RootShell.runRaw("cat \"" + binPath + "\"");
        if (!jsonPath.isEmpty()) {
            f.jsonData = RootShell.runRaw("cat \"" + jsonPath + "\"");
        }
        return (f.binData == null || f.binData.length == 0) ? null : f;
    }

    public static String fileName(String path) {
        if (path == null) return "";
        int i = path.lastIndexOf('/');
        return i >= 0 ? path.substring(i + 1) : path;
    }
}
