package com.replayx.sender.util;

/**
 * Diagnóstico temporário: vasculha as pastas internas e externas do Free
 * Fire (Normal e MAX) via root/Shizuku, procurando qualquer coisa que possa
 * ser um índice/banco local de replays (databases, shared_prefs, etc).
 * Não mexe em nada, só lê e loga. Ativado por toque longo no título do app.
 */
public final class DiagDump {
    private DiagDump() {}

    public interface Log {
        void onLog(String msg);
    }

    private static final String FFM = "com.dts.freefiremax";
    private static final String FFN = "com.dts.freefireth";

    private static final String[] BASES = {
        "/storage/emulated/0",
        "/sdcard",
        "/data/media/0"
    };

    public static void run(Log log) {
        log.onLog("========== DIAGNÓSTICO ==========");
        log.onLog("root=" + RootShell.hasRoot() + " shizuku=" + RootShell.hasShizuku());

        for (String pkg : new String[]{FFN, FFM}) {
            log.onLog("---- " + pkg + " ----");
            dumpExternal(pkg, log);
            dumpInternal(pkg, log);
        }
        log.onLog("========== FIM DIAGNÓSTICO ==========");
    }

    private static void dumpExternal(String pkg, Log log) {
        for (String base : BASES) {
            String dir = base + "/Android/data/" + pkg;
            String exists = RootShell.run("[ -d \"" + dir + "\" ] && echo EXISTE || echo NAO_EXISTE");
            if (exists == null || !exists.contains("EXISTE")) continue;
            log.onLog("[EXT] " + dir + " existe. Conteúdo (3 níveis):");
            String tree = RootShell.run("find \"" + dir + "\" -maxdepth 3 2>/dev/null");
            printLines(tree, log, "[EXT] ");

            log.onLog("[EXT] Arquivos de replay/histórico em qualquer subpasta:");
            String hits = RootShell.run("find \"" + dir + "\" -iname '*replay*' -o -iname '*histor*' -o -iname '*record*' 2>/dev/null");
            printLines(hits, log, "[EXT-HIT] ");
            return;
        }
    }

    private static void dumpInternal(String pkg, Log log) {
        String[] internalBases = {"/data/data/" + pkg, "/data/user/0/" + pkg};
        for (String dir : internalBases) {
            String exists = RootShell.run("[ -d \"" + dir + "\" ] && echo EXISTE || echo NAO_EXISTE");
            log.onLog("[INT] " + dir + " -> " + (exists == null ? "SEM_RESPOSTA" : exists.trim()));
            if (exists == null || !exists.contains("EXISTE")) continue;

            log.onLog("[INT] databases/:");
            printLines(RootShell.run("ls -la \"" + dir + "/databases\" 2>/dev/null"), log, "[INT-DB] ");

            log.onLog("[INT] shared_prefs/:");
            printLines(RootShell.run("ls -la \"" + dir + "/shared_prefs\" 2>/dev/null"), log, "[INT-PREF] ");

            log.onLog("[INT] busca ampla por replay/histórico (2 níveis abaixo de files/):");
            printLines(RootShell.run("find \"" + dir + "/files\" -maxdepth 2 -iname '*replay*' -o -iname '*histor*' -o -iname '*record*' 2>/dev/null"), log, "[INT-HIT] ");

            log.onLog("[INT] listagem geral de files/ (2 níveis):");
            printLines(RootShell.run("find \"" + dir + "/files\" -maxdepth 2 2>/dev/null"), log, "[INT] ");
        }
    }

    private static void printLines(String block, Log log, String prefix) {
        if (block == null || block.trim().isEmpty()) {
            log.onLog(prefix + "(vazio ou sem acesso)");
            return;
        }
        String[] lines = block.split("\n");
        int max = Math.min(lines.length, 60);
        for (int i = 0; i < max; i++) {
            if (!lines[i].trim().isEmpty()) log.onLog(prefix + lines[i].trim());
        }
        if (lines.length > max) log.onLog(prefix + "... (+" + (lines.length - max) + " linhas a mais, cortado)");
    }
}
