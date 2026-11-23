"""班级便签屏 · Windows 被控端

常驻后台：关闭面板只收进托盘，MQTT 一直在收便签；
收到便签 → 置顶弹出一张便签纸，内容与老师在手机端输入的一致。
"""
from __future__ import annotations

import ctypes
import json
import os
import sys
import threading
import time
from ctypes import wintypes
from pathlib import Path

import webview

from bus import Bus
from config import APP_ID, APP_NAME, VERSION, static_dir
from pairing import pairing_string, qr_data_url
from store import Store

NOTE_W = 356
NOTE_MAX_Q = 12


# ---------------------------------------------------------------- Win32 辅助
class RECT(ctypes.Structure):
    _fields_ = [("left", ctypes.c_long), ("top", ctypes.c_long),
                ("right", ctypes.c_long), ("bottom", ctypes.c_long)]


class POINT(ctypes.Structure):
    _fields_ = [("x", ctypes.c_long), ("y", ctypes.c_long)]


class MONITORINFO(ctypes.Structure):
    _fields_ = [("cbSize", wintypes.DWORD), ("rcMonitor", RECT),
                ("rcWork", RECT), ("dwFlags", wintypes.DWORD)]


NOTE_TITLE = "班级便签"


def _log(msg: str) -> None:
    try:
        from config import data_dir
        with open(data_dir() / "place.log", "a", encoding="utf-8") as f:
            f.write(time.strftime("%H:%M:%S ") + msg + "\n")
    except Exception:
        pass


def _hwnd_of(title: str):
    """按标题找窗口。必须声明原型，否则 ctypes 会把 str 按 ANSI 传，拿到错误句柄。"""
    u = ctypes.WinDLL("user32", use_last_error=True)
    u.FindWindowW.argtypes = [wintypes.LPCWSTR, wintypes.LPCWSTR]
    u.FindWindowW.restype = wintypes.HWND
    return u.FindWindowW(None, title)


def _u32():
    return ctypes.WinDLL("user32", use_last_error=True)


def monitor_work(hwnd) -> RECT:
    """取窗口所在显示器的可用区域（物理像素，与 SetWindowPos 同一坐标系）。"""
    u = _u32()
    u.MonitorFromWindow.argtypes = [wintypes.HWND, wintypes.DWORD]
    u.MonitorFromWindow.restype = wintypes.HMONITOR
    u.GetMonitorInfoW.argtypes = [wintypes.HMONITOR, ctypes.POINTER(MONITORINFO)]
    u.GetMonitorInfoW.restype = wintypes.BOOL

    mi = MONITORINFO()
    mi.cbSize = ctypes.sizeof(MONITORINFO)
    hmon = u.MonitorFromWindow(hwnd, 2) if hwnd else None
    if not hmon:
        # 拿不到就退回主屏
        hmon = u.MonitorFromPoint(POINT(0, 0), 2)
    if not hmon or not u.GetMonitorInfoW(hmon, ctypes.byref(mi)):
        return RECT(0, 0, 1920, 1080)
    return mi.rcWork


def win_rect(hwnd) -> RECT | None:
    if not hwnd:
        return None
    u = _u32()
    u.GetWindowRect.argtypes = [wintypes.HWND, ctypes.POINTER(RECT)]
    u.GetWindowRect.restype = wintypes.BOOL
    r = RECT()
    return r if u.GetWindowRect(hwnd, ctypes.byref(r)) else None


def dpi_scale(hwnd) -> float:
    """窗口当前所在显示器的缩放比例（150% → 1.5）。"""
    try:
        u = _u32()
        u.GetDpiForWindow.argtypes = [wintypes.HWND]
        u.GetDpiForWindow.restype = wintypes.UINT
        dpi = u.GetDpiForWindow(hwnd) or 96
        return max(1.0, dpi / 96.0)
    except Exception:
        return 1.0


def set_window_box(hwnd, x: int, y: int, w: int, h: int, topmost: bool = True) -> bool:
    """用物理像素直接摆放窗口，绕开 pywebview 的 DIP 坐标。"""
    if not hwnd:
        return False
    u = _u32()
    u.SetWindowPos.argtypes = [wintypes.HWND, wintypes.HWND, ctypes.c_int, ctypes.c_int,
                               ctypes.c_int, ctypes.c_int, wintypes.UINT]
    u.SetWindowPos.restype = wintypes.BOOL
    SWP_NOACTIVATE = 0x0010
    after = wintypes.HWND(-1 if topmost else 0)     # HWND_TOPMOST / HWND_TOP
    return bool(u.SetWindowPos(hwnd, after, int(x), int(y), int(w), int(h), SWP_NOACTIVATE))


def single_instance() -> bool:
    """同一台机器只允许跑一个被控端，避免两个实例重复弹便签。"""
    try:
        k32 = ctypes.windll.kernel32
        h = k32.CreateMutexW(None, False, "Local\\NoteScreen_SingleInstance")
        if k32.GetLastError() == 183:      # ERROR_ALREADY_EXISTS
            return False
        globals()["_MUTEX"] = h
    except Exception:
        pass
    return True


def message_box(text: str, title: str, flags: int = 0x40) -> int:
    try:
        return ctypes.windll.user32.MessageBoxW(None, text, title, flags)
    except Exception:
        return 1


# ---------------------------------------------------------------- 应用主体
class App:
    def __init__(self) -> None:
        self.store = Store()
        self.bus = Bus(self.store.device_id, self.store.device_key,
                       self._on_note, self._on_status)
        self.bridge = Bridge(self)
        self.queue: list[dict] = []
        self.idx = 0
        self.notes_seq = 1
        self.win_dash: webview.Window | None = None
        self.win_note: webview.Window | None = None
        self.tray = None
        self._note_visible = False
        self._note_visible_before = False
        self._note_pos = (0, 0)
        self._note_size = (NOTE_W, 220)
        self._quit = False
        self._lock = threading.Lock()

    # ---------- 收便签 ----------
    def _on_note(self, data: dict) -> None:
        text = (data.get("text") or "").strip()
        if not text:
            return
        color = data.get("color") if data.get("color") in ("amber", "rose", "sky", "green", "violet") else "amber"
        note = {
            "id": str(data.get("id") or int(time.time() * 1000)),
            "text": text[:2000],
            "color": color,
            "ts": int(data.get("ts") or time.time()),
            "from": str(data.get("from") or "")[:24],
        }
        with self._lock:
            self.queue.append(note)
            if len(self.queue) > NOTE_MAX_Q:
                self.queue = self.queue[-NOTE_MAX_Q:]
            self.idx = len(self.queue) - 1
        self.store.add_note(note)
        self.notes_seq += 1
        self.show_note()

    def _on_status(self, _states: dict) -> None:
        pass

    # ---------- 便签窗 ----------
    def note_hwnd(self):
        return _hwnd_of(NOTE_TITLE)

    def _place_note(self, force: bool = False) -> tuple[int, int]:
        """把便签窗摆到右上角（或上次拖到的位置）。

        pywebview 的 width/height 是 DIP，SetWindowPos 要物理像素，这里统一换算。
        """
        hwnd = self.note_hwnd()
        if not hwnd:
            return 0, 0
        scale = dpi_scale(hwnd)
        w = int(round(NOTE_W * scale))
        h = int(round(self._note_size[1] * scale))
        mg = int(round(28 * scale))
        work = monitor_work(hwnd)
        cur = win_rect(hwnd)
        saved = self.store.window
        x = y = None
        if saved and isinstance(saved, dict) and saved.get("x") is not None and not force:
            x, y = int(saved["x"]), int(saved["y"])
        elif cur and not force:
            x, y = cur.left, cur.top
        if x is None:
            x = work.right - w - mg
            y = work.top + int(round(76 * scale))
        x = max(work.left + 6, min(x, work.right - w - 6))
        y = max(work.top + 6, min(y, work.bottom - int(round(60 * scale))))
        ok = set_window_box(hwnd, x, y, w, h)
        _log("place ok=%s scale=%.2f work=%s -> (%s,%s,%s,%s)" % (
            ok, scale, (work.left, work.top, work.right, work.bottom), x, y, w, h))
        self._note_pos = (x, y)
        return x, y

    def show_note(self) -> None:
        if not self.win_note:
            return
        if not self._note_visible:
            try:
                self.win_note.show()
            except Exception:
                pass
            self._note_visible = True
        self._place_note(force=not self._note_visible_before)
        self._note_visible_before = True
        self._render_note()

    def _payload(self) -> dict:
        with self._lock:
            n = self.queue[self.idx] if self.queue else None
            total = len(self.queue)
            idx = self.idx
        if not n:
            return {}
        return {**n, "index": idx, "total": total}

    def _render_note(self, tries: int = 0) -> None:
        if not self.win_note:
            return
        payload = self._payload()
        if not payload:
            return

        def push():
            for i in range(30):
                try:
                    ok = self.win_note.evaluate_js("window.__alive === true")
                    if ok:
                        break
                except Exception:
                    pass
                time.sleep(0.05)
            try:
                self.win_note.evaluate_js(
                    "window.renderNote && window.renderNote(%s)" % json.dumps(payload, ensure_ascii=False))
            except Exception:
                pass

        threading.Thread(target=push, daemon=True).start()

    def hide_note(self) -> None:
        if self.win_note:
            try:
                self.win_note.hide()
            except Exception:
                pass
        self._note_visible = False

    def note_size(self, h: int) -> None:
        h = int(max(128, min(600, h)))
        if abs(h - self._note_size[1]) < 2:
            return
        self._note_size = (NOTE_W, h)
        hwnd = self.note_hwnd()
        if not hwnd:
            return
        scale = dpi_scale(hwnd)
        cur = win_rect(hwnd)
        work = monitor_work(hwnd)
        pw, ph = int(round(NOTE_W * scale)), int(round(h * scale))
        if cur and cur.right > cur.left:
            # 右边缘固定，向上/下长
            set_window_box(hwnd, cur.right - pw, cur.top, pw, ph)
        else:
            set_window_box(hwnd, work.right - pw - int(round(28 * scale)),
                           work.top + int(round(76 * scale)), pw, ph)

    def nav(self, step: int) -> None:
        with self._lock:
            if not self.queue:
                return
            self.idx = max(0, min(len(self.queue) - 1, self.idx + step))
        self._render_note()

    def save_note_pos(self) -> None:
        r = win_rect(self.note_hwnd())
        if r and r.right > r.left:
            self.store.set_window({"x": r.left, "y": r.top})

    def close_note(self) -> None:
        self.save_note_pos()
        with self._lock:
            if self.queue:
                self.queue.pop(self.idx)
                self.idx = min(self.idx, len(self.queue) - 1)
            has_more = bool(self.queue)
        if has_more:
            self._render_note()
        else:
            self.hide_note()

    # ---------- 关窗/退出 ----------
    def hide_dashboard(self) -> None:
        if self.win_dash:
            try:
                self.win_dash.hide()
            except Exception:
                pass

    def show_dashboard(self) -> None:
        if self.win_dash:
            try:
                self.win_dash.show()
                self.win_dash.restore() if hasattr(self.win_dash, "restore") else None
            except Exception:
                pass

    def quit_app(self) -> None:
        self._quit = True
        try:
            if self.tray:
                self.tray.stop()
        except Exception:
            pass
        try:
            self.bus.stop()
        except Exception:
            pass
        for w in (self.win_note, self.win_dash):
            try:
                if w:
                    w.destroy()
            except Exception:
                pass

    def state(self) -> dict:
        return {
            "device_id": self.store.device_id,
            "device_key": self.store.device_key,
            "name": self.store.name,
            "qr": qr_data_url(pairing_string(self.store.device_id,
                                             self.store.device_key, self.store.name)),
            "brokers": dict(self.bus.states),
            "notes": self.store.notes[:30],
            "notes_seq": self.notes_seq,
            "version": VERSION,
        }


# ---------------------------------------------------------------- JS 桥
class Bridge:
    """暴露给页面的原生能力。

    注意：所有属性必须以 _ 开头。pywebview 会把 js_api 的公开属性递归
    展开成 JS 函数，一旦暴露 App 就会顺着 WinForms 控件树无限递归。
    """

    def __init__(self, app: App) -> None:
        self._app = app

    # 面板
    def get_state(self) -> dict:
        return self._app.state()

    def get_status(self) -> dict:
        return {
            "device_id": self._app.store.device_id,
            "brokers": dict(self._app.bus.states),
            "notes": self._app.store.notes[:30],
            "notes_seq": self._app.notes_seq,
        }

    def set_name(self, name: str) -> dict:
        self._app.store.set_name(name)
        self._app.bus.set_name(self._app.store.name)
        return {
            "ok": True,
            "name": self._app.store.name,
            "qr": qr_data_url(pairing_string(self._app.store.device_id,
                                             self._app.store.device_key, self._app.store.name)),
        }

    def test_note(self) -> dict:
        self._app._on_note({
            "id": "local-%d" % int(time.time() * 1000),
            "text": "这是一条测试便签。\n老师在手机 App 里输入的内容，会以这样的便签形式弹在屏幕上。",
            "color": "amber",
            "ts": int(time.time()),
            "from": "本机测试",
        })
        return {"ok": True}

    def clear_notes(self) -> dict:
        self._app.store.clear_notes()
        self._app.notes_seq += 1
        return {"ok": True}

    def hide_dashboard(self) -> dict:
        self._app.hide_dashboard()
        return {"ok": True}

    def confirm_quit(self) -> bool:
        return message_box("退出后，这台大屏将不再接收老师的便签。确定退出吗？",
                           APP_NAME, 0x04 | 0x20 | 0x100) == 6   # MB_YESNO|ICONQUESTION|SETFOREGROUND

    def quit_app(self) -> dict:
        threading.Thread(target=self._app.quit_app, daemon=True).start()
        return {"ok": True}

    # 便签窗
    def note_size(self, h: int) -> dict:
        self._app.note_size(h)
        return {"ok": True}

    def close_note(self) -> dict:
        self._app.close_note()
        return {"ok": True}

    def nav(self, step: int) -> dict:
        self._app.nav(int(step))
        return {"ok": True}

    def note_booted(self) -> dict:
        return {"ok": True}

    def begin_drag(self) -> dict:
        try:
            u = ctypes.windll.user32
            u.WindowFromPoint.argtypes = [POINT]
            u.WindowFromPoint.restype = wintypes.HWND
            pt = POINT()
            u.GetCursorPos(ctypes.byref(pt))
            hwnd = u.WindowFromPoint(pt)
            root = u.GetAncestor(hwnd, 2)      # GA_ROOT
            if root:
                u.ReleaseCapture()
                u.SendMessageW(root, 0x00A1, 2, 0)   # WM_NCLBUTTONDOWN / HTCAPTION
        except Exception:
            pass
        return {"ok": True}


# ---------------------------------------------------------------- 托盘
def build_tray(app: App):
    try:
        import pystray
        from PIL import Image
    except Exception:
        return None

    icon_path = static_dir() / "icon.png"
    try:
        image = Image.open(icon_path)
    except Exception:
        image = Image.new("RGB", (64, 64), "#2f6bff")

    def on_open(_i=None, _it=None):
        app.show_dashboard()

    def on_toggle(_i=None, _it=None):
        if app._note_visible:
            app.hide_note()
        else:
            app.show_note()

    def on_quit(_i=None, _it=None):
        app.quit_app()

    menu = pystray.Menu(
        pystray.MenuItem("打开控制面板", on_open, default=True),
        pystray.MenuItem("显示 / 隐藏便签", lambda i, it: (
            app.show_note() if not it.checked else app.hide_note()),
            checked=lambda it: app._note_visible),
        pystray.Menu.SEPARATOR,
        pystray.MenuItem("退出", on_quit),
    )
    icon = pystray.Icon(APP_ID, image, f"{APP_NAME} · 正在后台接收便签", menu)
    return icon


# ---------------------------------------------------------------- 启动
def main() -> None:
    if not single_instance():
        message_box(f"{APP_NAME} 已经在运行了。\n请到屏幕右下角的托盘图标里打开它。", APP_NAME, 0x40)
        return

    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(1)
    except Exception:
        pass

    app = App()
    s = static_dir()

    app.win_dash = webview.create_window(
        f"{APP_NAME} · 本机",
        str(s / "index.html"),
        js_api=app.bridge,
        width=660,
        height=790,
        min_size=(600, 680),
        background_color="#eef1f7",
        text_select=True,
        easy_drag=False,
    )

    app.win_note = webview.create_window(
        NOTE_TITLE,
        str(s / "note.html"),
        js_api=app.bridge,
        width=NOTE_W,
        height=220,
        x=1200,
        y=90,
        frameless=True,
        easy_drag=False,
        on_top=True,
        hidden=True,
        resizable=False,
        focus=False,
        shadow=True,
        background_color="#fff6cf",
        text_select=True,
    )

    def on_dash_close():
        if app._quit:
            return True
        app.hide_dashboard()
        return False

    try:
        app.win_dash.events.closing += on_dash_close
    except Exception:
        pass

    def on_note_closing():
        # 便签窗只能隐藏，不能真的销毁，否则之后再也弹不出来
        if app._quit:
            return True
        app.save_note_pos()
        app.hide_note()
        return False

    try:
        app.win_note.events.closing += on_note_closing
    except Exception:
        pass

    app.bus.start(app.store.name)

    app.tray = build_tray(app)
    if app.tray:
        try:
            app.tray.run_detached()
        except Exception:
            app.tray = None

    def on_start():
        time.sleep(0.2)

    webview.start(on_start, gui="edgechromium",
                  private_mode=False, storage_path=str(Path(os.environ.get("APPDATA", ".")) / APP_ID / "webview"),
                  debug=False)

    # 窗口全部关闭后收尾
    try:
        app.bus.stop()
    except Exception:
        pass


if __name__ == "__main__":
    main()
