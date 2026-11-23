"""本地状态：设备身份、名称、便签历史。身份一经生成即固定，重启不变。"""
from __future__ import annotations

import json
import threading
from typing import Any

from config import data_dir
from pairing import new_identity


class Store:
    def __init__(self) -> None:
        self.path = data_dir() / "state.json"
        self.lock = threading.RLock()
        self.data: dict[str, Any] = self._load()

    def _load(self) -> dict[str, Any]:
        data: dict[str, Any] = {}
        try:
            data = json.loads(self.path.read_text("utf-8"))
        except Exception:
            data = {}
        if not data.get("device_id") or not data.get("device_key"):
            device_id, device_key = new_identity()
            data["device_id"] = device_id
            data["device_key"] = device_key
        if not data.get("name"):
            data["name"] = "教室大屏"
        data.setdefault("notes", [])
        data.setdefault("window", None)
        self._data = data
        self.save()
        return data

    def save(self) -> None:
        with self.lock:
            try:
                self.path.write_text(json.dumps(self._data, ensure_ascii=False, indent=1), "utf-8")
            except OSError:
                pass

    # ---------- 便捷访问 ----------
    @property
    def device_id(self) -> str:
        return self._data["device_id"]

    @property
    def device_key(self) -> str:
        return self._data["device_key"]

    @property
    def name(self) -> str:
        return self._data["name"]

    def set_name(self, name: str) -> None:
        with self.lock:
            self._data["name"] = (name or "").strip()[:24] or "教室大屏"
            self.save()

    def add_note(self, note: dict) -> None:
        with self.lock:
            notes = self._data.setdefault("notes", [])
            notes.insert(0, note)
            del notes[60:]
            self.save()

    def clear_notes(self) -> None:
        with self.lock:
            self._data["notes"] = []
            self.save()

    @property
    def notes(self) -> list[dict]:
        return self._data.get("notes", [])

    def set_window(self, pos: dict | None) -> None:
        with self.lock:
            self._data["window"] = pos
            self.save()

    @property
    def window(self) -> dict | None:
        return self._data.get("window")
