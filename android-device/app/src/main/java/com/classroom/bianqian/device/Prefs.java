package com.classroom.bianqian.device;

import android.content.Context;
import android.content.SharedPreferences;

/** 设备身份存本地，重装前不会变，绑定关系因此稳定。 */
public final class Prefs {

    private static final String FILE = "note_screen";
    private static final String K_ID = "device_id";
    private static final String K_KEY = "device_key";
    private static final String K_NAME = "device_name";
    private static final String K_RUN = "service_enabled";
    private static final String K_BOOT = "boot_enabled";

    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
        if (!sp.contains(K_ID) || !sp.contains(K_KEY)) {
            sp.edit()
                    .putString(K_ID, Pairing.randomCode(6))
                    .putString(K_KEY, Pairing.randomCode(10))
                    .apply();
        }
    }

    public String deviceId() {
        return sp.getString(K_ID, "");
    }

    public String deviceKey() {
        return sp.getString(K_KEY, "");
    }

    public String name() {
        String n = sp.getString(K_NAME, "");
        return n == null || n.isEmpty() ? "教室大屏" : n;
    }

    public void setName(String n) {
        String v = n == null ? "" : n.trim();
        sp.edit().putString(K_NAME, v.isEmpty() ? "教室大屏" : v).apply();
    }

    public boolean serviceEnabled() {
        return sp.getBoolean(K_RUN, false);
    }

    public void setServiceEnabled(boolean on) {
        sp.edit().putBoolean(K_RUN, on).apply();
    }

    public boolean bootEnabled() {
        return sp.getBoolean(K_BOOT, true);
    }

    public void setBootEnabled(boolean on) {
        sp.edit().putBoolean(K_BOOT, on).apply();
    }
}
