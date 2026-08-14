package com.replayx.sender.security;

/**
 * Credenciais do Firestore protegidas com XOR + fragmentação
 * (mesmo padrão usado no Combo Replay).
 */
public final class C {
    private C() {}

    private static final byte[] XK = {0x5A};
    private static final byte[] P = {42,40,51,52,57,51,42,59,54,119,108,56,60,108,60};
    private static final byte[] K1 = {27,19,32,59,9,35,27,106,61,57,40,57,13,45};
    private static final byte[] K2 = {109,3,44,10,107,17,3,98,13,56,49,62,32,46};
    private static final byte[] K3 = {10,22,109,18,2,10,62,119,107,41,49};

    private static volatile String _p = null;
    private static volatile String _k = null;

    public static String p() {
        if (_p == null) {
            synchronized (C.class) { if (_p == null) _p = xd(P); }
        }
        return _p;
    }

    public static String k() {
        if (_k == null) {
            synchronized (C.class) {
                if (_k == null) {
                    byte[] all = new byte[K1.length + K2.length + K3.length];
                    System.arraycopy(K1, 0, all, 0, K1.length);
                    System.arraycopy(K2, 0, all, K1.length, K2.length);
                    System.arraycopy(K3, 0, all, K1.length + K2.length, K3.length);
                    _k = xd(all);
                }
            }
        }
        return _k;
    }

    private static String xd(byte[] b) {
        byte key = XK[0];
        char[] c = new char[b.length];
        for (int i = 0; i < b.length; i++) c[i] = (char)((b[i] ^ key) & 0xFF);
        return new String(c);
    }
}
