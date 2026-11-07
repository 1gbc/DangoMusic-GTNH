"""Headless regression: WinExe startup must read UTF-8 without an attached console.

Usage: python check_bootstrap.py <published DangoApplePlayer.exe>
Only synthetic invalid bootstrap data is sent. No login/window/playback is attempted.
"""
import json
from pathlib import Path
import subprocess
import sys

exe = str(Path(sys.argv[1]).resolve())
for value, expected in [
    ({"link": "https://invalid.example/", "profile": "relative"}, 7),
    ({"link": "http://127.0.0.1:1234/#" + "a" * 64, "profile": "测试路径"}, 7),
]:
    p = subprocess.run([exe], input=(json.dumps(value, ensure_ascii=False) + "\n").encode("utf-8"),
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5)
    assert p.returncode == expected, f"WinExe pipe startup: expected {expected}, got {p.returncode}"
print("Headless UTF-8 bootstrap regression passed (2 cases)")
