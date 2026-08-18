package com.replayx.sender.security;

import android.content.Context;

/** Barreira comum para impedir que apenas pular a LoginActivity libere o Sender. */
public final class SecurityGate {
    private SecurityGate() {}

    public static boolean allow(Context context) {
        try {
            return IntegrityCheck.isValid(context)
                    && LicenseManager.hasLocalLicense(context)
                    && !LicenseManager.savedKey(context).trim().isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }
}
