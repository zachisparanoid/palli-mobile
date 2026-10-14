#!/usr/bin/env python3
"""Tap a node on the connected Android device by its text or content-description (uiautomator dump).

    python3 scripts/ui.py tap "Message PaLLi"
    python3 scripts/ui.py hold "Hold to record a voice note" 2500
    python3 scripts/ui.py has "She couldn't hear this one."
    python3 scripts/ui.py has-part "heard as:"
    python3 scripts/ui.py labels

A label matches a node whose text or content-description is exactly the label. `tap` falls back to a node whose text
contains it when nothing matches exactly (so "Pair" finds the dialog button, not the "Pair with PaLLi" title, and
"connected" is not found inside "not connected"). `has` never falls back; `has-part` is the substring form.
`labels` prints every labelled node, for finding the exact wording of something on screen.
"""
import re
import subprocess
import sys
import xml.etree.ElementTree as ET


def dump() -> ET.Element:
    subprocess.run(["adb", "shell", "uiautomator", "dump", "/sdcard/ui.xml"], check=True, capture_output=True)
    xml = subprocess.run(["adb", "exec-out", "cat", "/sdcard/ui.xml"], check=True, capture_output=True).stdout
    return ET.fromstring(xml)


def centre(node: ET.Element):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds", "")))
    return (x1 + x2) // 2, (y1 + y2) // 2


def find(label: str, contains: bool = False):
    """The centre of the first node labelled exactly `label`; with `contains`, else the first whose text contains it."""
    nodes = list(dump().iter("node"))
    for node in nodes:
        if label in (node.get("text", ""), node.get("content-desc", "")):
            return centre(node)
    if contains:
        for node in nodes:
            if label in node.get("text", "") or label in node.get("content-desc", ""):
                return centre(node)
    return None


def main() -> int:
    if len(sys.argv) < 2 or sys.argv[1] not in ("tap", "hold", "has", "has-part", "labels"):
        print(__doc__, file=sys.stderr)
        return 2
    action = sys.argv[1]
    if action == "labels":
        for node in dump().iter("node"):
            for what in ("text", "content-desc"):
                if node.get(what):
                    print(f"{what}: {node.get(what)!r}  {node.get('bounds')}")
        return 0
    if len(sys.argv) < 3:
        print(__doc__, file=sys.stderr)
        return 2
    label = sys.argv[2]
    point = find(label, contains=action in ("tap", "hold", "has-part"))
    if action in ("has", "has-part"):
        print("found" if point else "missing")
        return 0 if point else 1
    if point is None:
        print(f"no node labelled {label!r}", file=sys.stderr)
        return 1
    x, y = point
    if action == "tap":
        subprocess.run(["adb", "shell", "input", "tap", str(x), str(y)], check=True)
    elif action == "hold":
        ms = sys.argv[3] if len(sys.argv) > 3 else "2000"
        subprocess.run(["adb", "shell", "input", "swipe", str(x), str(y), str(x), str(y), ms], check=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
