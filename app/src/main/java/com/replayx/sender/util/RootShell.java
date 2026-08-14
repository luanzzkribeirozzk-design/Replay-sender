package com.replayx.sender.util;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Executa comandos de shell tentando ROOT primeiro (comum em emuladores tipo
 * BlueStacks/MSI App Player, que costumam ter root liberado nas configs),
 * e cai pra Shizuku se root não estiver disponível — mesmo esquema usado
 * no app de celular (Combo Replay), só que aqui com root como opção extra.
 */
public final class RootShell {
    private RootShell() {}

    private static Boolean rootOk = null;

    public static boolean hasRoot() {
        if (rootOk != null) return rootOk;
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            byte[] out = readAll(p.getInputStream());
            p.waitFor();
            rootOk = new String(out).contains("uid=0");
        } catch (Exception e) {
            rootOk = false;
        }
        return rootOk;
    }

    public static boolean hasShizuku() {
        try {
            Class<?> cls = Class.forName("rikka.shizuku.Shizuku");
            java.lang.reflect.Method ping = cls.getMethod("pingBinder");
            Object r = ping.invoke(null);
            return Boolean.TRUE.equals(r);
        } catch (Exception e) {
            return false;
        }
    }

    /** Roda o comando e devolve a saída como texto (trim), igual antes. */
    public static String run(String cmd) {
        byte[] out = runRaw(cmd);
        return out == null ? "ERR_NO_OUTPUT" : new String(out).trim();
    }

    /** Roda o comando e devolve a saída BRUTA (bytes), pra ler arquivo binário via cat. */
    public static byte[] runRaw(String cmd) {
        if (hasRoot()) {
            byte[] r = runViaRoot(cmd);
            if (r != null) return r;
        }
        if (hasShizuku()) {
            byte[] r = runViaShizuku(cmd);
            if (r != null) return r;
        }
        return null;
    }

    private static byte[] runViaRoot(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            byte[] out = readAll(p.getInputStream());
            byte[] err = readAll(p.getErrorStream());
            p.waitFor();
            return out.length > 0 ? out : err;
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] runViaShizuku(String cmd) {
        try {
            Class<?> cls = Class.forName("rikka.shizuku.Shizuku");
            java.lang.reflect.Method target = null;
            for (java.lang.reflect.Method m : cls.getMethods()) {
                if (m.getName().equals("newProcess")) { target = m; break; }
            }
            if (target == null) {
                for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                    if (m.getName().equals("newProcess")) { m.setAccessible(true); target = m; break; }
                }
            }
            if (target == null) return null;
            String[] args = new String[]{"sh", "-c", cmd};
            Process p = (Process) target.invoke(null, new Object[]{args, null, null});
            byte[] out = readAll(p.getInputStream());
            byte[] err = readAll(p.getErrorStream());
            p.waitFor();
            return out.length > 0 ? out : err;
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        return bos.toByteArray();
    }
}
