"""公网消息总线：同时连接多个公共 MQTT 节点，任一条通即可收发。

被控端（本机）职责：
  - 订阅 ns1/<channel>/in   接收老师便签
  - 重发 ns1/<channel>/st   在线状态（retained + 遗嘱，掉线自动变离线）
  - 回发 ns1/<channel>/ack  送达回执
"""
from __future__ import annotations

import json
import secrets
import threading
import time
from collections import OrderedDict
from typing import Callable

import paho.mqtt.client as mqtt

from config import BROKERS, TOPIC_ROOT, VERSION
from pairing import channel_id


class Bus:
    def __init__(self, device_id: str, device_key: str,
                 on_note: Callable[[dict], None],
                 on_status: Callable[[dict[str, str]], None]) -> None:
        self.chan = channel_id(device_id, device_key)
        self.base = f"{TOPIC_ROOT}/{self.chan}"
        self.on_note = on_note
        self.on_status = on_status
        self.clients: dict[str, mqtt.Client] = {}
        self.states: dict[str, str] = {h: "connecting" for h, _ in BROKERS}
        self.name = ""
        self._seen: OrderedDict[str, None] = OrderedDict()
        self._lock = threading.Lock()
        self._started = False

    # ---------- 生命周期 ----------
    def start(self, name: str) -> None:
        self.name = name
        if self._started:
            self.publish_state()
            return
        self._started = True
        for i, (host, port) in enumerate(BROKERS):
            c = mqtt.Client(
                mqtt.CallbackAPIVersion.VERSION2,
                client_id=f"ns-{self.chan[:8]}-{secrets.token_hex(3)}",
                protocol=mqtt.MQTTv311,
                clean_session=True,
            )
            c.reconnect_delay_set(1, 25)
            c.on_connect = lambda cl, u, fl, rc, pr, host=host: self._on_connect(cl, host, rc)
            c.on_disconnect = lambda cl, u, fl, rc, pr, host=host: self._on_disconnect(host, rc)
            c.on_message = self._on_message
            c.will_set(f"{self.base}/st", self._state_payload(False), qos=1, retain=True)
            self.clients[host] = c
            try:
                c.connect_async(host, port, keepalive=45)
                c.loop_start()
            except Exception:
                self._set_state(host, "error")
            time.sleep(0.04)

    def stop(self) -> None:
        for host, c in self.clients.items():
            try:
                c.publish(f"{self.base}/st", self._state_payload(False), qos=1, retain=True)
                c.disconnect()
                c.loop_stop()
            except Exception:
                pass
        self.clients.clear()
        self._started = False

    def set_name(self, name: str) -> None:
        self.name = name
        self.publish_state()

    # ---------- 状态 ----------
    def _state_payload(self, online: bool) -> str:
        return json.dumps({
            "v": 1, "t": "state", "on": online,
            "name": self.name, "app": VERSION, "ts": int(time.time()),
        }, ensure_ascii=False)

    def _set_state(self, host: str, state: str) -> None:
        if self.states.get(host) == state:
            return
        self.states[host] = state
        try:
            self.on_status(dict(self.states))
        except Exception:
            pass

    def publish_state(self) -> None:
        for host, c in self.clients.items():
            if self.states.get(host) != "online":
                continue
            try:
                c.publish(f"{self.base}/st", self._state_payload(True), qos=1, retain=True)
            except Exception:
                pass

    def online_count(self) -> int:
        return sum(1 for s in self.states.values() if s == "online")

    # ---------- 回调 ----------
    def _on_connect(self, client: mqtt.Client, host: str, rc) -> None:
        ok = getattr(rc, "value", rc) == 0
        if not ok:
            self._set_state(host, "error")
            return
        try:
            client.subscribe(f"{self.base}/in", qos=1)
            client.publish(f"{self.base}/st", self._state_payload(True), qos=1, retain=True)
        except Exception:
            pass
        self._set_state(host, "online")

    def _on_disconnect(self, host: str, rc) -> None:
        self._set_state(host, "offline")

    def _on_message(self, client, userdata, msg) -> None:
        if not msg.topic.endswith("/in"):
            return
        try:
            data = json.loads(msg.payload.decode("utf-8"))
        except Exception:
            return
        if not isinstance(data, dict) or data.get("t") != "note":
            return
        nid = str(data.get("id") or "")
        if not nid:
            return
        with self._lock:
            if nid in self._seen:
                return
            self._seen[nid] = None
            while len(self._seen) > 400:
                self._seen.popitem(last=False)
        try:
            self.on_note(data)
        except Exception:
            pass
        # 回执：老师端据此显示"已送达"
        try:
            client.publish(f"{self.base}/ack",
                           json.dumps({"v": 1, "t": "ack", "id": nid,
                                       "ok": True, "name": self.name,
                                       "ts": int(time.time())}, ensure_ascii=False), qos=0)
        except Exception:
            pass
