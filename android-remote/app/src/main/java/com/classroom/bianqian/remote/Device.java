package com.classroom.bianqian.remote;

import org.json.JSONObject;

/** 一台被绑定的教室大屏。 */
public class Device {
    public String id = "";
    public String key = "";
    public String name = "";
    public boolean online;
    public boolean checked = true;

    public Device() {
    }

    public Device(String id, String key, String name) {
        this.id = id;
        this.key = key;
        this.name = name == null ? "" : name;
    }

    public String channel() {
        return Pairing.channelId(id, key);
    }

    public String displayName() {
        return name == null || name.trim().isEmpty() ? "设备 " + id : name;
    }

    public JSONObject toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("key", key);
            o.put("name", name);
            return o;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public static Device fromJson(JSONObject o) {
        return new Device(o.optString("id"), o.optString("key"), o.optString("name"));
    }

    public boolean valid() {
        return id != null && id.length() >= 4 && key != null && key.length() >= 6;
    }
}
