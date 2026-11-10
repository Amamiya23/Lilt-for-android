"""Import the small, attributed Tabler subset used by the native UI."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "app/src/main/res/drawable"
ICONS = [
    "home", "search", "user", "settings", "chevron-left", "chevron-right",
    "chevron-down", "dots", "heart", "playlist", "lyrics", "shuffle", "repeat",
    "repeat-once", "radio", "microphone-2", "disc", "arrow-down", "download",
    "plus", "x", "refresh", "check", "clock", "music", "headphones", "volume",
    "moon", "sun", "device-mobile", "trash", "share", "folder", "brand-qq",
    "brand-wechat", "qrcode", "arrows-sort", "arrow-up", "alert-circle", "clover",
    "arrow-up-right", "external-link", "info-circle", "logout", "adjustments",
]
NS = "{http://www.w3.org/2000/svg}"


def path_of(e):
    a = e.attrib
    tag = e.tag.replace(NS, "")
    if tag == "path":
        return a.get("d", "")
    if tag == "line":
        return f"M{a['x1']},{a['y1']}L{a['x2']},{a['y2']}"
    if tag in ("polyline", "polygon"):
        return "M" + a["points"].strip().replace(" ", " L") + ("Z" if tag == "polygon" else "")
    if tag == "circle":
        x, y, r = (float(a[k]) for k in ("cx", "cy", "r"))
        return f"M{x-r},{y}a{r},{r} 0 1,0 {r*2},0a{r},{r} 0 1,0 {-r*2},0"
    if tag == "rect":
        x, y = float(a.get("x", 0)), float(a.get("y", 0))
        w, h, r = float(a["width"]), float(a["height"]), float(a.get("rx", 0))
        return (f"M{x+r},{y}h{w-2*r}q{r},0 {r},{r}v{h-2*r}q0,{r} {-r},{r}"
                f"h{-w+2*r}q{-r},0 {-r},{-r}v{-h+2*r}q0,{-r} {r},{-r}Z")
    return ""


def fetch(name):
    source = {"lyrics": "message-2", "shuffle": "arrows-shuffle", "brand-qq": "message-circle"}.get(name, name)
    url = f"https://raw.githubusercontent.com/tabler/tabler-icons/v3.35.0/icons/outline/{source}.svg"
    with urllib.request.urlopen(url, timeout=40) as response:
        raw = response.read()
    root = ET.fromstring(raw)
    paths = []
    for e in root.iter():
        if e.attrib.get("stroke") == "none":
            continue
        d = path_of(e)
        if d:
            paths.append(f'    <path android:fillColor="@android:color/transparent" android:strokeColor="#FF000000" android:strokeWidth="1.75" android:strokeLineCap="round" android:strokeLineJoin="round" android:pathData="{d}" />')
    content = '<!-- Tabler Icons v3.35.0, MIT license. https://tabler.io/icons -->\n'
    content += '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">\n'
    content += "\n".join(paths) + "\n</vector>\n"
    (OUT / ("ic_" + name.replace("-", "_") + ".xml")).write_text(content, encoding="utf-8")
    return name


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    with ThreadPoolExecutor(max_workers=8) as executor:
        results = list(executor.map(fetch, ICONS))
    print(f"Imported {len(results)} Tabler icons.")
