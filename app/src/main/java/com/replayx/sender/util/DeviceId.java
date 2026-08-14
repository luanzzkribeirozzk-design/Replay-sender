package com.replayx.sender.util;

import android.content.Context;
import android.provider.Settings;

public final class DeviceId {
    private DeviceId() {}

    public static String get(Context ctx) {
        try {
            String id = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
            return (id == null || id.isEmpty()) ? "unknown-device" : id;
        } catch (Exception e) {
            return "unknown-device";
        }
    }

    public static String model() {
        return android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL;
    }
}
