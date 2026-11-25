#!/usr/bin/env python3
"""Regenerate the WiFi Scanner launcher icons (navy background, white WiFi symbol).

Run from the project root:  python3 tools-generate-icons.py
Writes PNGs into app/src/main/res/mipmap-*/ (all densities) and the
adaptive-icon XMLs into mipmap-anydpi-v26/. Requires Pillow (pip install pillow).
"""
from PIL import Image, ImageDraw
import os

def draw_wifi(d, cx, cy, s, color, width):
    for r, w in [(0.62 * s, width), (0.42 * s, width), (0.22 * s, width)]:
        d.arc([cx - r, cy - r * 0.55, cx + r, cy + r * 1.45],
              start=215, end=325, fill=color, width=w)
    d.ellipse([cx - 0.055 * s, cy + 0.28 * s, cx + 0.055 * s, cy + 0.39 * s], fill=color)

NAVY = (29, 78, 216, 255)
WHITE = (255, 255, 255, 255)
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

for dens, px in SIZES.items():
    os.makedirs(f"app/src/main/res/mipmap-{dens}", exist_ok=True)
    img = Image.new("RGBA", (px, px), NAVY)
    d = ImageDraw.Draw(img)
    draw_wifi(d, px / 2, px * 0.42, px * 0.62, WHITE, max(3, px // 16))
    img.save(f"app/src/main/res/mipmap-{dens}/ic_launcher.png")
    img.save(f"app/src/main/res/mipmap-{dens}/ic_launcher_round.png")
    fg = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    d2 = ImageDraw.Draw(fg)
    draw_wifi(d2, px / 2, px * 0.42, px * 0.62, WHITE, max(3, px // 16))
    fg.save(f"app/src/main/res/mipmap-{dens}/ic_launcher_foreground.png")

os.makedirs("app/src/main/res/mipmap-anydpi-v26", exist_ok=True)
ADAPTIVE = ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <background android:drawable="@color/ic_launcher_background"/>\n'
            '    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>\n'
            '</adaptive-icon>\n')
open("app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml", "w").write(ADAPTIVE)
open("app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml", "w").write(ADAPTIVE)
print("icons done")
