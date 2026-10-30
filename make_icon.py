#!/usr/bin/env python3
"""Generate launcher icons for the VU Meter app.

Design: a cream VU dial (arc scale, red zone, needle near 0 VU) on a
dark charcoal background, echoing the in-app meter.
Outputs Android mipmap PNGs (legacy + adaptive foreground) into
app/src/main/res/.
"""
import math
import os
from PIL import Image, ImageDraw, ImageFont

RES = os.path.expanduser("~/workspace/vumeter/app/src/main/res")
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"

CREAM = (247, 239, 219, 255)
DARK = (40, 38, 34, 255)
EDGE = (60, 55, 45, 255)
RED = (192, 39, 45, 255)
NEEDLE = (26, 26, 26, 255)
BG = (23, 23, 28, 255)


def fraction(vu: float) -> float:
    v = 10 ** (vu / 20.0)
    v_min = 10 ** (-40.0 / 20.0)
    v_max = 10 ** (3.0 / 20.0)
    return (v - v_min) / (v_max - v_min)


def draw_dial(size: int, dial_frac: float) -> Image.Image:
    """Draw the dial on a transparent square canvas of `size` px.
    dial_frac: margin fraction (dial spans dial_frac .. 1-dial_frac)."""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    k = size / 1024.0

    def u(x: float) -> float:
        return x * k

    m = size * dial_frac
    x0, y0, x1, y1 = m, m, size - m, size - m
    corner = u(120)
    d.rounded_rectangle([x0, y0, x1, y1], radius=corner, fill=CREAM, outline=EDGE, width=max(2, int(u(5))))

    cx = (x0 + x1) / 2
    dial_h = y1 - y0
    pivot_y = y1 - dial_h * 0.10
    radius = (x1 - x0) * 0.40

    def point(vu: float, r: float):
        theta = math.radians(-46.0 + fraction(vu) * 92.0)
        return (cx + r * math.sin(theta), pivot_y - r * math.cos(theta))

    # main arc
    pts = [point(-40.0 + 43.0 * i / 72, radius) for i in range(73)]
    d.line(pts, fill=DARK, width=max(2, int(u(9))))
    # red zone arc
    pts = [point(3.0 * i / 16, radius) for i in range(17)]
    d.line(pts, fill=RED, width=max(2, int(u(15))))

    majors = [-20, -10, -7, -5, -3, -2, -1, 0, 1, 2, 3]
    minors = [-40, -30, -15, -13, -9, -8, -6, -4]
    for vu in minors:
        p1 = point(vu, radius)
        p2 = point(vu, radius + u(17))
        d.line([p1, p2], fill=DARK, width=max(1, int(u(6))))
    for vu in majors:
        p1 = point(float(vu), radius)
        p2 = point(float(vu), radius + u(27))
        d.line([p1, p2], fill=RED if vu >= 0 else DARK, width=max(2, int(u(9))))

    # "VU" letters
    font = ImageFont.truetype(FONT, int(u(96)))
    d.text((cx, pivot_y - radius * 0.44), "VU", font=font, fill=DARK, anchor="mm")

    # needle at -1 VU
    vu = -1.0
    theta = math.radians(-46.0 + fraction(vu) * 92.0)
    tip = (cx + radius * 0.97 * math.sin(theta), pivot_y - radius * 0.97 * math.cos(theta))
    tail = (cx - radius * 0.13 * math.sin(theta), pivot_y + radius * 0.13 * math.cos(theta))
    px, py = math.cos(theta), math.sin(theta)
    bw = u(11)
    poly = [
        tip,
        (cx + px * bw, pivot_y + py * bw),
        tail,
        (cx - px * bw, pivot_y - py * bw),
    ]
    d.polygon(poly, fill=NEEDLE)
    r = u(27)
    d.ellipse([cx - r, pivot_y - r, cx + r, pivot_y + r], fill=(35, 33, 30, 255))
    r2 = u(9)
    d.ellipse([cx - r2, pivot_y - r2, cx + r2, pivot_y + r2], fill=CREAM)
    return img


def save(img: Image.Image, *path: str):
    full = os.path.join(RES, *path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    img.save(full)
    print("wrote", full, img.size)


def main():
    # Adaptive-icon foreground (108 dp canvas; dial inside the safe zone)
    fg = draw_dial(1024, 0.215)
    save(fg.resize((432, 432), Image.LANCZOS), "mipmap-xxxhdpi", "ic_launcher_foreground.png")

    # Legacy icons: dial composited over the dark background
    legacy = Image.new("RGBA", (1024, 1024), BG)
    legacy.alpha_composite(draw_dial(1024, 0.06))
    for density, px in [("mipmap-mdpi", 48), ("mipmap-hdpi", 72), ("mipmap-xhdpi", 96),
                        ("mipmap-xxhdpi", 144), ("mipmap-xxxhdpi", 192)]:
        save(legacy.resize((px, px), Image.LANCZOS), density, "ic_launcher.png")

    # Preview for a human check
    preview = Image.new("RGBA", (1024, 1024), BG)
    preview.alpha_composite(fg)
    out = os.path.expanduser("~/workspace/vumeter/icon_preview.png")
    preview.resize((512, 512), Image.LANCZOS).save(out)
    legacy.resize((512, 512), Image.LANCZOS).save(
        os.path.expanduser("~/workspace/vumeter/icon_preview_legacy.png"))
    print("preview:", out)


if __name__ == "__main__":
    main()
