package com.classroom.bianqian.remote;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 已绑定设备列表 + 最近发送的便签草稿。 */
public final class DeviceStore {

    private static final String FILE = "remote_store";
    private static final String K_DEVICES = "devices";
    private static final String K_DRAFT = "draft";

    private final SharedPreferences sp;

    public DeviceStore(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public List<Device> devices() {
        List<Device> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(sp.getString(K_DEVICES, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                Device d = Device.fromJson(arr.getJSONObject(i));
                if (d.valid()) {
                    list.add(d);
                }
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    public void save(List<Device> list) {
        JSONArray arr = new JSONArray();
        for (Device d : list) {
            arr.put(d.toJson());
        }
        sp.edit().putString(K_DEVICES, arr.toString()).apply();
    }

    public boolean add(Device d) {
        List<Device> list = devices();
        for (Device x : list) {
            if (x.id.equalsIgnoreCase(d.id)) {
                x.key = d.key;
                x.name = d.name;
                save(list);
                return false;
            }
        }
        list.add(d);
        save(list);
        return true;
    }

    public void remove(String deviceId) {
        List<Device> list = devices();
        List<Device> keep = new ArrayList<>();
        for (Device x : list) {
            if (!x.id.equalsIgnoreCase(deviceId)) {
                keep.add(x);
            }
        }
        save(keep);
    }

    public String draft() {
        return sp.getString(K_DRAFT, "");
    }

    public void setDraft(String s) {
        sp.edit().putString(K_DRAFT, s).apply();
    }
}
