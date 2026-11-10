"""Capture phone/tablet, theme and font variants; restore emulator settings afterward."""
from capture_android import adb, capture, OUT


def setting(name):
    return adb("shell", "settings", "get", "system", name).stdout.decode().strip()


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    original = {name: setting(name) for name in ("accelerometer_rotation", "user_rotation", "font_scale")}
    night = adb("shell", "cmd", "uimode", "night").stdout.decode().strip().split(":")[-1].strip()
    try:
        adb("shell", "settings", "put", "system", "accelerometer_rotation", "0")
        adb("shell", "settings", "put", "system", "user_rotation", "0")
        for screen in ("home", "player", "lyrics", "search", "library", "settings", "playlist"):
            capture(screen)
        adb("shell", "cmd", "uimode", "night", "yes")
        for screen in ("home", "settings"):
            capture(screen, "dark")
        adb("shell", "cmd", "uimode", "night", "no")
        adb("shell", "settings", "put", "system", "font_scale", "1.3")
        for screen in ("player", "settings"):
            capture(screen, "large-font")
        adb("shell", "settings", "put", "system", "font_scale", "1.0")
        adb("shell", "settings", "put", "system", "user_rotation", "1")
        for screen in ("player", "lyrics"):
            capture(screen, "phone-landscape")
        adb("shell", "settings", "put", "system", "user_rotation", "0")
        adb("shell", "wm", "size", "1600x2560")
        adb("shell", "wm", "density", "320")
        for screen in ("home", "player"):
            capture(screen, "tablet-portrait")
        adb("shell", "settings", "put", "system", "user_rotation", "1")
        for screen in ("home", "player"):
            capture(screen, "tablet-landscape")
    finally:
        adb("shell", "wm", "size", "reset")
        adb("shell", "wm", "density", "reset")
        for name, value in original.items():
            adb("shell", "settings", "put", "system", name, value)
        adb("shell", "cmd", "uimode", "night", night)
        adb("shell", "am", "force-stop", "com.lilt.app")
        adb("shell", "am", "start", "-W", "-n", "com.lilt.app/.MainActivity")
    print("Saved the native layout matrix and restored emulator settings.")
