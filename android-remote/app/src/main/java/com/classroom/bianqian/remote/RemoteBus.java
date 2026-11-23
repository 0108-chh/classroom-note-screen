package com.classroom.bianqian.remote;

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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 控制端的公网总线：同时连多个公共节点，向被绑定设备发便签，
 * 并订阅各设备的在线状态与送达回执。
 */
public final class RemoteBus {

    private static final String TAG = "RemoteBus";

    public interface Listener {
        void onLinkCount(int online, int total);

        void onPresence(String channel, String name, boolean online);

        void onAck(String channel, String noteId);
    }

    public static final String[][] BROKERS = {
            {"broker-cn.emqx.io", "1883"},
            {"broker.emqx.io", "1883"},
            {"broker.hivemq.com", "1883"},
            {"test.mosquitto.org", "1883"},
    };

    private final Listener listener;
    private final Map<String, MqttClient> clients = new LinkedHashMap<>();
    private final Map<String, String> linkState = new LinkedHashMap<>();
    private final Set<String> subscribed = new CopyOnWriteArraySet<>();
    private volatile boolean started;

    public RemoteBus(Listener l) {
        this.listener = l;
        for (String[] b : BROKERS) {
            linkState.put(b[0], "connecting");
        }
    }

    public int onlineLinks() {
        int n = 0;
        synchronized (linkState) {
            for (String s : linkState.values()) {
                if ("online".equals(s)) {
                    n++;
                }
            }
        }
        return n;
    }

    public int totalLinks() {
        return BROKERS.length;
    }

    public void start() {
        if (started) {
            return;
        }
        started = true;
        for (String[] b : BROKERS) {
            new Thread(() -> connect(b[0], b[1]), "rbus-" + b[0]).start();
        }
    }

    private void connect(String host, String port) {
        try {
            String cid = "rn-" + Pairing.randomCode(3) + System.currentTimeMillis() % 100000;
            MqttClient c = new MqttClient("tcp://" + host + ":" + port, cid, new MemoryPersistence());
            MqttConnectOptions o = new MqttConnectOptions();
            o.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
            o.setCleanSession(true);
            o.setAutomaticReconnect(true);
            o.setKeepAliveInterval(45);
            o.setConnectionTimeout(12);
            c.setCallback(new MqttCallbackExtended() {
                @Override
                public void connectComplete(boolean reconnect, String serverURI) {
                    mark(host, "online");
                    for (String ch : subscribed) {
                        safeSubscribe(c, ch);
                    }
                }

                @Override
                public void connectionLost(Throwable cause) {
                    mark(host, "offline");
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    handle(topic, message);
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                }
            });
            synchronized (clients) {
                clients.put(host, c);
            }
            c.connect(o);
        } catch (Exception e) {
            Log.w(TAG, "connect fail " + host + " " + e);
            mark(host, "error");
        }
    }

    private void safeSubscribe(MqttClient c, String channel) {
        try {
            c.subscribe("ns1/" + channel + "/st", 1);
            c.subscribe("ns1/" + channel + "/ack", 1);
        } catch (Exception ignored) {
        }
    }

    /** 为这些设备的信道订阅在线状态。 */
    public void watch(List<Device> devices) {
        for (Device d : devices) {
            String ch = d.channel();
            if (ch.isEmpty() || !subscribed.add(ch)) {
                continue;
            }
            synchronized (clients) {
                for (MqttClient c : clients.values()) {
                    safeSubscribe(c, ch);
                }
            }
        }
    }

    public void unwatch(String channel) {
        subscribed.remove(channel);
    }

    private void handle(String topic, MqttMessage msg) {
        try {
            String ch = topic.startsWith("ns1/") ? topic.substring(4).split("/")[0] : "";
            JSONObject o = new JSONObject(new String(msg.getPayload(), StandardCharsets.UTF_8));
            if (topic.endsWith("/st")) {
                boolean on = o.optBoolean("on", false);
                listener.onPresence(ch, o.optString("name", ""), on);
            } else if (topic.endsWith("/ack")) {
                listener.onAck(ch, o.optString("id", ""));
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 广播一条便签给多台设备，返回真正投递出去的链路数。
     */
    public int send(List<Device> devices, String noteJson) {
        int sent = 0;
        byte[] body = noteJson.getBytes(StandardCharsets.UTF_8);
        for (Device d : devices) {
            String topic = "ns1/" + d.channel() + "/in";
            synchronized (clients) {
                for (Map.Entry<String, MqttClient> e : clients.entrySet()) {
                    if (!"online".equals(linkState.get(e.getKey()))) {
                        continue;
                    }
                    try {
                        MqttMessage m = new MqttMessage(body);
                        m.setQos(1);
                        e.getValue().publish(topic, m);
                        sent++;
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return sent;
    }

    private void mark(String host, String state) {
        boolean changed;
        synchronized (linkState) {
            changed = !state.equals(linkState.get(host));
            linkState.put(host, state);
        }
        if (changed) {
            listener.onLinkCount(onlineLinks(), totalLinks());
        }
    }

    public List<String> hostsStatus() {
        List<String> out = new ArrayList<>();
        synchronized (linkState) {
            for (Map.Entry<String, String> e : linkState.entrySet()) {
                out.add(e.getKey() + " " + e.getValue());
            }
        }
        return out;
    }

    public void stop() {
        synchronized (clients) {
            for (MqttClient c : clients.values()) {
                try {
                    c.disconnectForcibly(200, 200, false);
                } catch (Exception ignored) {
                }
            }
            clients.clear();
        }
        started = false;
    }
}
