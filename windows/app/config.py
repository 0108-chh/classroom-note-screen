"""全局配置：应用信息、公网中转节点、路径。"""
from __future__ import annotations

import os
import sys
from pathlib import Path

APP_NAME = "班级便签屏"
APP_ID = "NoteScreen"
VERSION = "1.0.0"

# 公共 MQTT 中转节点（免注册、无广告）。三端使用同一份列表并全部连接，
# 任一条可用即可通信，因此不存在"两端连到不同节点就收不到"的问题。
BROKERS: list[tuple[str, int]] = [
    ("broker-cn.emqx.io", 1883),      # 国内直连优先
    ("broker.emqx.io", 1883),
    ("broker.hivemq.com", 1883),
    ("test.mosquitto.org", 1883),
]

TOPIC_ROOT = "ns1"
NOTE_COLORS = ["amber", "rose", "sky", "green", "violet"]


def app_root() -> Path:
    """源码运行 / PyInstaller 打包后都能定位到资源目录。"""
    if getattr(sys, "frozen", False):
        return Path(getattr(sys, "_MEIPASS"))
    return Path(__file__).resolve().parent


def data_dir() -> Path:
    base = os.environ.get("APPDATA") or os.path.expanduser("~")
    d = Path(base) / APP_ID
    d.mkdir(parents=True, exist_ok=True)
    return d


def static_dir() -> Path:
    return app_root() / "static"
