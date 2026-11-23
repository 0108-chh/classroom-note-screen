import ctypes, sys, time
ctypes.windll.shcore.SetProcessDpiAwareness(1)
from ctypes import wintypes
u = ctypes.windll.user32
EnumProc = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
found = []
def cb(h, l):
    n = u.GetWindowTextLengthW(h)
    if not n or not u.IsWindowVisible(h):
        return True
    b = ctypes.create_unicode_buffer(n + 1)
    u.GetWindowTextW(h, b, n + 1)
    r = wintypes.RECT()
    u.GetWindowRect(h, ctypes.byref(r))
    found.append((h, b.value, (r.left, r.top, r.right, r.bottom)))
    return True
u.EnumWindows(EnumProc(cb), 0)
for h, t, r in found:
    print(hex(h), repr(t)[:50], r)
target = [f for f in found if "便签" in f[1] or "NoteScreen" in f[1]]
if target:
    h = target[0][0]
    u.ShowWindow(h, 9)
    time.sleep(1.0)
from PIL import ImageGrab
ImageGrab.grab().save(sys.argv[1] if len(sys.argv) > 1 else "shot.png")
print("shot ok", target[0][1])
