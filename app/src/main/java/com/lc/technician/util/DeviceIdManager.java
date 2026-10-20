package com.lc.technician.util;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.UUID;

public class DeviceIdManager {
    private static final String PREFS = "lc_device";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_DEVICE_NAME = "device_name";

    public static String getDeviceId(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String id = prefs.getString(KEY_DEVICE_ID, null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString(KEY_DEVICE_ID, id).apply();
        }
        return id;
    }

    public static String getDeviceName(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String name = prefs.getString(KEY_DEVICE_NAME, null);
        if (name == null) {
            name = android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL;
            prefs.edit().putString(KEY_DEVICE_NAME, name).apply();
        }
        return name;
    }
}
