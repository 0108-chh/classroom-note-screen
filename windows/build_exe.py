#!/usr/bin/env python
"""一键打包 Windows 被控端为单文件 exe。"""
from __future__ import annotations

import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
APP = ROOT / "app"
NAME = "班级便签屏"


def main() -> None:
    for d in (ROOT / "build", ROOT / "dist"):
        if d.exists():
            shutil.rmtree(d)
    cmd = [
        sys.executable, "-m", "PyInstaller",
        "--noconfirm", "--clean",
        "--onefile",
        "--windowed",
        "--name", NAME,
        "--icon", str(APP / "static" / "app.ico"),
        "--add-data", f"{APP / 'static'};static",
        "--collect-submodules", "webview",
        "--collect-submodules", "paho",
        "--collect-submodules", "pystray",
        "--hidden-import", "clr",
        "--hidden-import", "qrcode.image.pil",
        "--hidden-import", "PIL.Image",
        "--hidden-import", "PIL.ImageDraw",
        "--distpath", str(ROOT / "dist"),
        "--workpath", str(ROOT / "build"),
        "--specpath", str(ROOT / "build"),
        str(APP / "main.py"),
    ]
    print(" ".join(cmd))
    subprocess.check_call(cmd, cwd=APP)
    exe = ROOT / "dist" / f"{NAME}.exe"
    if exe.exists():
        print(f"\n生成成功: {exe}  {exe.stat().st_size / 1048576:.1f} MB")
    else:
        print("\n未找到 exe")


if __name__ == "__main__":
    main()
