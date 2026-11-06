"""Mockup of the lens-style zoom strip: a light, raised brushed-silver frame with a recessed glass window, a dark
scale drum behind the glass (log-spaced ticks, numbers at the marked focal lengths), a fixed index below the
window and the focal range printed on the frame. Rendered at 3x for a 390 dp wide board. The spec
(2026-11-06-pianke-interactions-design.md) is the authority on values; this script only produced zoom-strip.png.

Usage: python -I render_zoom_window.py <font_ttf> <out_png> [focal_mm]
"""
import math
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

S = 3                       # px per dp
W, H = 390 * S, 64 * S      # the strip
MARKED = [18, 23, 28, 35, 50, 115, 135, 200]
FMIN, FMAX = 18, 200


def ticks():
    out = list(range(18, 35)) + list(range(35, 100, 5)) + list(range(100, 201, 10))
    return sorted(set(out))


def main():
    font_path, out_path = sys.argv[1], sys.argv[2]
    focal = float(sys.argv[3]) if len(sys.argv) > 3 else 35.0
    # page background behind the strip (the app's light silver), then a raised light frame with a shadow
    page = (199, 199, 195)
    im = Image.new("RGB", (W, H), page)
    fx0, fy0, fx1, fy1 = int(8 * S), int(8 * S), W - int(8 * S), H - int(8 * S)
    sh = Image.new("L", (W, H), 0)
    ImageDraw.Draw(sh).rounded_rectangle([fx0, fy0 + 3 * S, fx1, fy1 + 3 * S], 10 * S, fill=110)
    sh = sh.filter(ImageFilter.GaussianBlur(4 * S))
    im.paste((120, 119, 114), (0, 0), sh)
    frame = Image.new("RGB", (W, H), (0, 0, 0))
    farr = np.zeros((H, W, 3), np.float32)
    yy = np.arange(H)[:, None] / H
    xx = np.arange(W)[None, :]
    rng = np.random.default_rng(3)
    brush = np.repeat(rng.normal(0, 1, (H, 1)).astype(np.float32), W, axis=1)
    v = 226 - 34 * yy + brush * 2.5
    farr[..., :] = v[..., None] * np.array([1.0, 0.995, 0.98])
    frame = Image.fromarray(farr.clip(0, 255).astype(np.uint8))
    fmask = Image.new("L", (W, H), 0)
    ImageDraw.Draw(fmask).rounded_rectangle([fx0, fy0, fx1, fy1], 10 * S, fill=255)
    im.paste(frame, (0, 0), fmask)
    d = ImageDraw.Draw(im)
    d.rounded_rectangle([fx0, fy0, fx1, fy1], 10 * S, outline=(250, 249, 245), width=max(1, S // 2))
    # window
    wx0, wx1 = int(70 * S), int(290 * S)
    wy0, wy1 = int(17 * S), int(47 * S)
    r = 5 * S
    # recess bevel: dark lip on top, light lip below
    d.rounded_rectangle([wx0 - 3 * S, wy0 - 3 * S, wx1 + 3 * S, wy1 + 3 * S], r + 3 * S, fill=(150, 149, 144))
    d.rounded_rectangle([wx0 - 3 * S, wy0 - 3 * S + 2 * S, wx1 + 3 * S, wy1 + 3 * S], r + 3 * S, fill=(244, 243, 238))
    d.rounded_rectangle([wx0 - 1 * S, wy0 - 1 * S, wx1 + 1 * S, wy1 + 1 * S], r + 1 * S, fill=(30, 30, 29))
    # scale drum (light), drawn on its own layer then masked to the window
    drum = Image.new("RGB", (wx1 - wx0, wy1 - wy0), (44, 43, 41))
    dd = ImageDraw.Draw(drum)
    num_font = ImageFont.truetype(font_path, 10 * S)
    span = 100 * S                          # px per decade-ish: position = span * log(f/focal)
    cx = (wx1 - wx0) / 2
    def pos(f):
        return cx + span * math.log(f / focal) / math.log(2.2)
    for f in ticks():
        x = pos(f)
        if -20 < x < drum.width + 20:
            major = f in MARKED
            y1 = 9 * S if major else 6 * S
            dd.line([(x, 0), (x, y1)], fill=(214, 211, 202), width=max(1, int(0.6 * S)) if not major else int(0.9 * S))
    for f in MARKED:
        x = pos(f)
        if -40 < x < drum.width + 40:
            col = (236, 72, 58) if abs(f - focal) < 0.5 else ((214, 160, 84) if f in (50, 200) else (232, 228, 218))
            t = str(f)
            tw = dd.textlength(t, font=num_font)
            dd.text((x - tw / 2, 11 * S), t, font=num_font, fill=col)
    # glass on the drum: inner shadow at the top, a soft reflection, curvature darkening at the ends
    g = np.asarray(drum, np.float32).copy()
    h, w = g.shape[:2]
    gy = np.arange(h)[:, None] / h
    gx = np.arange(w)[None, :] / w
    curve = 1 - 0.55 * (np.abs(gx - 0.5) * 2) ** 2.2
    top = 1 - 0.55 * np.exp(-gy / 0.14)
    g *= (curve * top)[..., None]
    refl = 0.10 * np.exp(-((gy - 0.28) / 0.10) ** 2) * (0.6 + 0.4 * np.cos((gx - 0.3) * 3))
    g = g * (1 - refl[..., None]) + 255 * refl[..., None]
    drum = Image.fromarray(g.clip(0, 255).astype(np.uint8))
    mask = Image.new("L", drum.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, w - 1, h - 1], r, fill=255)
    im.paste(drum, (wx0, wy0), mask)
    # fixed index below the window, and the focal range printed on the barrel
    ix = (wx0 + wx1) / 2
    d.polygon([(ix, wy1 + 4 * S), (ix - 3 * S, wy1 + 9 * S), (ix + 3 * S, wy1 + 9 * S)], fill=(60, 59, 56))
    label_font = ImageFont.truetype(font_path, 9 * S)
    d.text((wx1 + 12 * S, 22 * S), "18-200mm", font=label_font, fill=(64, 63, 60))
    small = ImageFont.truetype(font_path, 6 * S)
    d.text((wx1 + 12 * S, 34 * S), "PIANKE ZOOM", font=small, fill=(120, 118, 112))
    d.text((16 * S, 22 * S), f"{focal:.0f}", font=label_font, fill=(206, 50, 40))
    d.text((14 * S, 34 * S), "mm", font=small, fill=(120, 118, 112))
    im.save(out_path)
    im.resize((W * 2 // 3, H * 2 // 3), Image.LANCZOS).save(out_path.replace(".png", "_s.png"))
    print("ok")


if __name__ == "__main__":
    main()
