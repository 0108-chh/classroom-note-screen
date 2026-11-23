package com.classroom.bianqian.device;

import android.util.Log;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 公网消息总线：并行连接多个免费 MQTT 节点，任意一条通就能收便签。
 */
public final class Bus {

    private static final String TAG = "NoteBus";

    public interface Listener {
        void onNote(JSONObject note);

        void onStateChanged();
    }

    public static final String[][] BROKERS = {
            {"broker-cn.emqx.io", "1883"},
            {"broker.emqx.io", "1883"},
            {"broker.hivemq.com", "1883"},
            {"test.mosquitto.org", "1883"},
    };

    private final String base;
    private final Listener listener;
    private final Map<String, MqttClient> clients = new LinkedHashMap<>();
    private final Map<String, String> states = new LinkedHashMap<>();
    private final Set<String> seen = new LinkedHashSet<>();
    private String deviceName = "";
    private volatile boolean started;

    public Bus(String deviceId, String deviceKey, Listener listener) {
        this.base = Pairing.base(deviceId, deviceKey);
        this.listener = listener;
        for (String[] b : BROKERS) {
            states.put(b[0], "connecting");
        }
    }

    public Map<String, String> states() {
        synchronized (states) {
            return new LinkedHashMap<>(states);
        }
    }

    public int onlineCount() {
        int n = 0;
        synchronized (states) {
            for (String s : states.values()) {
                if ("online".equals(s)) {
                    n++;
                }
            }
        }
        return n;
    }

    public void start(String name) {
        deviceName = name;
        if (started) {
            publishState();
            return;
        }
        started = true;
        for (String[] b : BROKERS) {
            final String host = b[0];
            final String port = b[1];
            new Thread(() -> connect(host, port), "mqtt-" + host).start();
        }
    }

    private void connect(String host, String port) {
        try {
            String cid = "ns-" + base.substring(4, 12) + "-" + Pairing.randomCode(4);
            MqttClient c = new MqttClient("tcp://" + host + ":" + port, cid, new MemoryPersistence());
            MqttConnectOptions o = new MqttConnectOptions();
            o.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
            o.setCleanSession(true);
            o.setAutomaticReconnect(true);
            o.setKeepAliveInterval(45);
            o.setConnectionTimeout(12);
            o.setWill(base + "/st", statePayload(false).getBytes(StandardCharsets.UTF_8), 1, true);
            c.setCallback(new MqttCallbackExtended() {
                @Override
                public void connectComplete(boolean reconnect, String serverURI) {
                    try {
                        c.subscribe(base + "/in", 1);
                        MqttMessage m = new MqttMessage(statePayload(true).getBytes(StandardCharsets.UTF_8));
                        m.setQos(1);
                        m.setRetained(true);
                        c.publish(base + "/st", m);
                    } catch (Exception e) {
                        Log.w(TAG, "subscribe fail " + host, e);
                    }
                    setState(host, "online");
                }

                @Override
                public void connectionLost(Throwable cause) {
                    setState(host, "offline");
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    handle(host, c, topic, message);
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                }
            });
            clients.put(host, c);
            c.connect(o);
        } catch (Exception e) {
            Log.w(TAG, "connect fail " + host + " " + e);
            setState(host, "error");
        }
    }

    private void handle(String host, MqttClient c, String topic, MqttMessage msg) {
        if (!topic.endsWith("/in")) {
            return;
        }
        try {
            String body = new String(msg.getPayload(), StandardCharsets.UTF_8);
            JSONObject o = new JSONObject(body);
            if (!"note".equals(o.optString("t"))) {
                return;
            }
            String id = o.optString("id");
            if (id.isEmpty()) {
                return;
            }
            synchronized (seen) {
                if (seen.contains(id)) {
                    return;
                }
                seen.add(id);
                if (seen.size() > 300) {
                    List<String> list = new ArrayList<>(seen);
                    for (int i = 0; i < 100; i++) {
                        seen.remove(list.get(i));
                    }
                }
            }
            listener.onNote(o);
            try {
                JSONObject ack = new JSONObject();
                ack.put("v", 1);
                ack.put("t", "ack");
                ack.put("id", id);
                ack.put("ok", true);
                ack.put("name", deviceName);
                ack.put("ts", System.currentTimeMillis() / 1000);
                MqttMessage m = new MqttMessage(ack.toString().getBytes(StandardCharsets.UTF_8));
                m.setQos(0);
                c.publish(base + "/ack", m);
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            Log.w(TAG, "bad message", e);
        }
    }

    public void publishState() {
        for (Map.Entry<String, MqttClient> e : clients.entrySet()) {
            if (!"online".equals(states.get(e.getKey()))) {
                continue;
            }
            try {
                MqttMessage m = new MqttMessage(statePayload(true).getBytes(StandardCharsets.UTF_8));
                m.setQos(1);
                m.setRetained(true);
                e.getValue().publish(base + "/st", m);
            } catch (Exception ignored) {
            }
        }
    }

    private String statePayload(boolean online) {
        try {
            JSONObject o = new JSONObject();
            o.put("v", 1);
            o.put("t", "state");
            o.put("on", online);
            o.put("name", deviceName);
            o.put("app", "android-device");
            o.put("ts", System.currentTimeMillis() / 1000);
            return o.toString();
        } catch (Exception e) {
            return "{\"v\":1,\"t\":\"state\",\"on\":" + online + "}";
        }
    }

    private void setState(String host, String state) {
        boolean changed;
        synchronized (states) {
            changed = !state.equals(states.get(host));
            states.put(host, state);
        }
        if (changed) {
            listener.onStateChanged();
        }
    }

    public void stop() {
        for (Map.Entry<String, MqttClient> e : clients.entrySet()) {
            try {
                e.getValue().disconnectForcibly(300, 300, false);
            } catch (Exception ignored) {
            }
        }
        clients.clear();
        started = false;
    }
}
