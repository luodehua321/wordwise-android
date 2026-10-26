#!/usr/bin/env python3
"""Rebuild the three original CC0 UI effects using Python's standard library."""
from pathlib import Path
import math
import struct
import wave

OUTPUT = Path(__file__).resolve().parents[1] / "app/src/main/res/raw"
RATE = 44_100
NOTES = {
    "tap": [(980, .045)],
    "known": [(784, .070), (1047, .110)],
    "unknown": [(523, .075), (440, .090)],
}


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    for name, notes in NOTES.items():
        frames = []
        for frequency, duration in notes:
            length = int(RATE * duration)
            for index in range(length):
                time = index / RATE
                envelope = min(1, time / .006) * math.exp(-time / (duration * .3))
                envelope *= min(1, (length - index) / (RATE * .012))
                signal = math.sin(2 * math.pi * frequency * time)
                signal += .2 * math.sin(2 * math.pi * 2 * frequency * time)
                frames.append(struct.pack("<h", round(32767 * .23 * envelope * signal)))
            frames.extend([b"\x00\x00"] * int(RATE * .015))
        with wave.open(str(OUTPUT / f"feedback_{name}.wav"), "wb") as file:
            file.setnchannels(1)
            file.setsampwidth(2)
            file.setframerate(RATE)
            file.writeframes(b"".join(frames))


if __name__ == "__main__":
    main()
