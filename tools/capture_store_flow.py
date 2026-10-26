#!/usr/bin/env python3
"""Drive the emulator through a scripted v2.0 session and capture store screenshots.

Usage:
    python3 tools/capture_store_flow.py

Requires an unlocked, booted emulator/device with the debug APK installed and
`adb` on PATH (or ADB=/path/to/adb). It clears app data, completes the 20-word
calibration, creates a few mistakes, and writes 1080x2400 PNGs into
store/screenshots/raw/.
"""
from __future__ import annotations

import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RAW = ROOT / "store/screenshots/raw"
ADB = os.environ.get("ADB", "adb")
PACKAGE = "com.yuxuan.lingo"


def run(*args, binary=False):
    result = subprocess.run([ADB, *args], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode != 0:
        raise RuntimeError(result.stderr.decode(errors="replace"))
    return result.stdout if binary else result.stdout.decode(errors="replace")


def tap(x, y):
    run("shell", "input", "tap", str(int(x)), str(int(y)))


def swipe(x1, y1, x2, y2, duration=400):
    run("shell", "input", "swipe", str(x1), str(y1), str(x2), str(y2), str(duration))


def dump():
    for _ in range(3):
        try:
            run("shell", "uiautomator", "dump", "/sdcard/ui.xml")
            xml = run("shell", "cat", "/sdcard/ui.xml")
            return ET.fromstring(xml)
        except Exception:
            time.sleep(0.4)
    return None


def nodes(root):
    if root is None:
        return []
    for node in root.iter("node"):
        text = node.get("text", "").strip()
        bounds = node.get("bounds", "")
        match = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
        if text and match:
            l, t, r, b = map(int, match.groups())
            yield text, node, (l, t, r, b)


def find(text, root=None, contains=False):
    candidates = []
    for value, node, bounds in nodes(root or dump()):
        if (text in value) if contains else (value == text):
            candidates.append((value, node, bounds))
    return candidates


def wait_for(text, timeout=60, contains=False):
    deadline = time.time() + timeout
    while time.time() < deadline:
        found = find(text, contains=contains)
        if found:
            return found[0]
        time.sleep(0.5)
    return None


def tap_text(text, timeout=15, contains=False):
    deadline = time.time() + timeout
    while time.time() < deadline:
        found = find(text, contains=contains)
        if found:
            # Prefer the clickable node with the largest area, else the text node.
            best = max(found, key=lambda item: (item[1].get("clickable") == "true",
                                                 (item[2][2] - item[2][0]) * (item[2][3] - item[2][1])))
            l, t, r, b = best[2]
            tap((l + r) / 2, (t + b) / 2)
            return True
        time.sleep(0.3)
    return False






def tap_desc(description):
    root = dump()
    if root is None:
        return False
    for node in root.iter("node"):
        if node.get("content-desc") == description:
            match = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
            if match:
                l, t, r, b = map(int, match.groups())
                tap((l + r) / 2, (t + b) / 2)
                return True
    return False


def tap_first_card():
    """Tap the first large clickable card below the filter row."""
    root = dump()
    candidates = []
    for text, node, bounds in nodes(root):
        l, t, r, b = bounds
        if node.get("clickable") == "true" and (r - l) > 700 and (b - t) > 140 and t > 600:
            candidates.append((t, bounds))
    if not candidates:
        return False
    _, (l, t, r, b) = min(candidates)
    tap((l + r) / 2, (t + b) / 2)
    return True


def screenshot(name):
    RAW.mkdir(parents=True, exist_ok=True)
    data = run("exec-out", "screencap", "-p", binary=True)
    path = RAW / name
    path.write_bytes(data)
    print("captured", path)


def progress(root):
    for text, _, _ in nodes(root):
        match = re.fullmatch(r"(\d+)\s*/\s*20", text)
        if match:
            return int(match.group(1))
    return None


def card_difficulty(root):
    for text, _, _ in nodes(root):
        match = re.fullmatch(r"难度\s*(\d+)\s*/\s*10", text)
        if match:
            return int(match.group(1))
    return None


def launch():
    for attempt in range(3):
        run("shell", "am", "force-stop", PACKAGE)
        time.sleep(0.8)
        run("shell", "am", "start", "-n", f"{PACKAGE}/.MainActivity")
        if wait_for("认识", timeout=45):
            return True
        print("retry launch", attempt + 1)
        time.sleep(2)
    return False


def calibrate():
    print("waiting for calibration")
    answered = 0
    attempts = 0
    while answered < 20 and attempts < 70:
        root = dump()
        before = progress(root)
        if before is None:
            print("calibration finished")
            return
        difficulty = card_difficulty(root)
        known = (difficulty <= 3) if difficulty is not None else (answered % 3 != 2)
        if not tap_text("认识" if known else "不认识"):
            print("answer button missing at", answered)
            break
        time.sleep(1.0)
        if not tap_text("下一个单词"):
            print("next button missing at", answered)
            break
        time.sleep(1.0)
        after = progress(dump())
        if after is None or after > before:
            answered += 1
        attempts += 1
    print("calibration loop done, answered", answered)


def main():
    run("shell", "am", "force-stop", PACKAGE)
    run("shell", "pm", "clear", PACKAGE)
    time.sleep(1.5)
    if not launch():
        raise SystemExit("app did not reach the learning card")
    calibrate()
    # Create a few normal answers: 4 known, 5 unknown.
    for index in range(9):
        difficulty = card_difficulty(dump())
        # Keep a few genuine mistakes for the mistake-book screenshot.
        known = (difficulty is not None and difficulty <= 3) and index % 3 != 2
        tap_text("认识" if known else "不认识")
        time.sleep(1.0)
        tap_text("下一个单词")
        time.sleep(1.0)
    screenshot("01-learn.png")
    # Reveal a word and capture the positive meaning + example state.
    tap_text("认识")
    time.sleep(1.8)
    screenshot("02-reveal.png")
    tap_text("下一个单词")
    time.sleep(1.0)
    # Mistake book.
    tap_text("易错本")
    time.sleep(1.6)
    screenshot("03-mistakes.png")
    # First mistake card opens the detail dialog.
    if not tap_first_card():
        tap(540, 920)
    time.sleep(1.6)
    screenshot("05-detail.png")
    # Close the detail dialog explicitly; tapping outside is not reliable
    # because the dialog uses a full-width custom surface.
    if not tap_desc("关闭单词详情"):
        tap(540, 920)
        time.sleep(0.8)
        tap_desc("关闭单词详情")
    time.sleep(1.2)
    # Profile and the offline section.
    tap_text("我的")
    time.sleep(1.0)
    screenshot("04-profile.png")
    swipe(540, 1900, 540, 500, 500)
    time.sleep(0.8)
    swipe(540, 1900, 540, 500, 500)
    time.sleep(0.8)
    screenshot("06-privacy.png")
    print("done")


if __name__ == "__main__":
    main()
