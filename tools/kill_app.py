"""按窗口标题找到本程序的进程并结束（开发调试用）。"""
import ctypes
import subprocess
import sys
from ctypes import wintypes

u = ctypes.windll.user32
EnumProc = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
KEEP = ("python.exe", "班级便签屏.exe", "班级便签屏")

pids = set()


def cb(h, l):
    n = u.GetWindowTextLengthW(h)
    if not n:
        return True
    b = ctypes.create_unicode_buffer(n + 1)
    u.GetWindowTextW(h, b, n + 1)
    if b.value.startswith("班级便签屏"):
        pid = wintypes.DWORD()
        u.GetWindowThreadProcessId(h, ctypes.byref(pid))
        pids.add(pid.value)
    return True


u.EnumWindows(EnumProc(cb), 0)
for pid in pids:
    print("kill", pid)
    subprocess.run(["taskkill", "/F", "/PID", str(pid)],
                   capture_output=True, text=True)
if not pids:
    print("no window found")
