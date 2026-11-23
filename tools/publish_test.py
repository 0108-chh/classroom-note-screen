"""测试用发布器：模拟老师手机往指定设备发便签。
用法： python publish_test.py [文本] [颜色]
设备身份从 %APPDATA%/NoteScreen/state.json 读取。
"""
from __future__ import annotations

import json
import os
import sys
import time
import uuid

import paho.mqtt.client as mqtt

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "windows", "app"))
from pairing import channel_id  # noqa: E402

ROOT = os.path.join(os.environ["APPDATA"], "NoteScreen", "state.json")
state = json.load(open(ROOT, encoding="utf-8"))
chan = channel_id(state["device_id"], state["device_key"])
topic = f"ns1/{chan}/in"
text = sys.argv[1] if len(sys.argv) > 1 else "老师刚刚发来一条便签内容。"
color = sys.argv[2] if len(sys.argv) > 2 else "amber"

payload = json.dumps({
    "v": 1, "t": "note", "id": uuid.uuid4().hex[:12],
    "text": text, "color": color, "ts": int(time.time()), "from": "王老师",
}, ensure_ascii=False)

c = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="test-pub-" + uuid.uuid4().hex[:6])
c.connect("broker-cn.emqx.io", 1883, 30)
c.loop_start()
time.sleep(0.6)
info = c.publish(topic, payload, qos=1)
info.wait_for_publish(timeout=6)
print("published", topic, info.rc)
time.sleep(0.4)
c.loop_stop()
c.disconnect()
