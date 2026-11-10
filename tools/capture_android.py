"""Capture native emulator screens for visual comparison with the Pencil design."""
import subprocess
import time
from pathlib import Path
import argparse

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / ".impeccable/review"
ADB = "C:/PortableApps/platform-tools/adb.exe"
DEVICE = "emulator-5554"


def adb(*args):
    return subprocess.run([ADB, "-s", DEVICE, *args], capture_output=True, check=True)


def capture(screen, prefix="phone", preview=True):
    adb("shell", "am", "force-stop", "com.lilt.app")
    args = ["shell", "am", "start", "-W", "-n", "com.lilt.app/.MainActivity"]
    if preview:
        args += ["--ez", "design_preview", "true", "--es", "preview_screen", screen]
    adb(*args)
    expected = {"home": "推荐", "player": "同步歌词", "lyrics": "同步歌词", "search": "周杰伦",
                "library": "我的歌单", "settings": "设置", "playlist": "播放全部", "live": "推荐"}[screen]
    for attempt in range(6):
        time.sleep(1)
        adb("shell", "uiautomator", "dump", "/sdcard/lilt-ui.xml")
        ui = adb("shell", "cat", "/sdcard/lilt-ui.xml").stdout.decode("utf-8")
        if expected in ui:
            break
    else:
        raise RuntimeError(f"Screen did not render: {screen}")
    # Compose semantics can be available while Android's starting window still covers the app.
    for attempt in range(16):
        windows = adb("shell", "dumpsys", "window", "windows").stdout.decode("utf-8")
        if "Splash Screen com.lilt.app" not in windows:
            break
        time.sleep(.5)
    else:
        raise RuntimeError(f"Starting window did not close: {screen}")
    time.sleep(1 if preview else 10)
    (OUT / f"{prefix}-{screen}.png").write_bytes(adb("exec-out", "screencap", "-p").stdout)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("screens", nargs="*", default=["home", "player", "lyrics", "search", "library", "settings", "playlist"])
    parser.add_argument("--prefix", default="phone")
    options = parser.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    for screen in options.screens:
        capture(screen, prefix=options.prefix, preview=screen != "live")
    print("Saved native emulator captures.")
