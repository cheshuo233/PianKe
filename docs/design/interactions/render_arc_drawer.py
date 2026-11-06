"""Mockup of the one-handed arc drawer for choosing a film, over a real device screenshot.

The drawer slides in from the right edge; its left edge is a large arc. The 15 labels ride a wheel inside
it (centre off-screen right), turned like the dial; the selected one sits at the arc's middle, raised with
an orange outline and its name. Usage: python -I render_arc_drawer.py <screenshot> <labels_dir> <font> <out>
"""
import math
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

K = 2                         # px per dp
W, H = 390 * K, 866 * K
CX, CY = (390 + 300) * K, 433 * K
R = 500 * K                   # drawer edge radius
RL = 416 * K                  # label wheel radius
STEP = math.radians(15)
LW, LH = int(84 * K), int(77 * K)
FILMS = ["portra160", "portra400", "portra800", "gold200", "ultramax400", "ektar100", "superia400", "c200",
         "pro400h", "vision3_500t", "vision3_250d", "kodachrome64", "velvia100", "provia100f", "ektachrome100"]
NAMES = ["PORTRA 160", "PORTRA 400", "PORTRA 800", "GOLD 200", "ULTRAMAX 400", "EKTAR 100", "SUPERIA X-TRA 400",
         "C200", "PRO 400H", "VISION3 500T", "VISION3 250D", "KODACHROME 64", "VELVIA 100", "PROVIA 100F", "EKTACHROME 100"]
CURRENT = 3


def main():
    shot, labels, font_path, out = sys.argv[1], Path(sys.argv[2]), sys.argv[3], sys.argv[4]
    base = Image.open(shot).convert("RGB").resize((W, int(W * 2608 / 1200)), Image.LANCZOS).crop((0, 0, W, H))
    base = Image.blend(base, Image.new("RGB", (W, H), (20, 20, 19)), 0.45)
    img = base.convert("RGBA")
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    r = np.hypot(xx - CX, yy - CY)
    # drawer shadow on the page, then the drawer: light brushed silver, a raised rim along the arc
    inside = (r <= R).astype(np.float32)
    sh = Image.fromarray((inside * 150).astype(np.uint8)).filter(ImageFilter.GaussianBlur(14 * K))
    img.alpha_composite(Image.merge("RGBA", (Image.new("L", (W, H), 10),) * 3 + (sh,)), (-6 * K, 6 * K))
    rng = np.random.default_rng(5)
    brush = np.repeat(rng.normal(0, 1, (H, 1)).astype(np.float32), W, axis=1)
    v = 214 - 26 * (yy / H) + brush * 2.2
    rim = np.exp(-((r - (R - 5 * K)) / (2.5 * K)) ** 2)
    v = v + 30 * rim
    panel = np.stack([v, v * 0.995, v * 0.98, inside * 255], -1)
    img.alpha_composite(Image.fromarray(panel.clip(0, 255).astype(np.uint8), "RGBA"))
    # a recessed track for the labels
    track = ((r > RL - 64 * K) & (r < RL + 64 * K)).astype(np.float32)
    tv = 70 + 25 * np.clip((r - (RL - 64 * K)) / (128 * K), 0, 1)
    tr = np.stack([tv, tv * 0.98, tv * 0.96, track * 255], -1)
    img.alpha_composite(Image.fromarray(tr.clip(0, 255).astype(np.uint8), "RGBA"))
    font = ImageFont.truetype(font_path, 9 * K)
    big = ImageFont.truetype(font_path, 11 * K)
    # labels on the wheel, the current one last (on top)
    order = sorted(range(-4, 5), key=lambda k: -abs(k))
    for k in order:
        if abs(k) > 3:
            continue
        idx = (CURRENT + k) % len(FILMS)
        a = math.pi + k * STEP
        scale = 1.12 if k == 0 else 1.0
        lab = Image.open(labels / f"{FILMS[idx]}.png").convert("RGBA").resize((int(LW * scale), int(LH * scale)), Image.LANCZOS)
        card = Image.new("RGBA", (lab.width + 12 * K, lab.height + 28 * K), (0, 0, 0, 0))
        cd = ImageDraw.Draw(card)
        if k == 0:
            cd.rounded_rectangle([0, 0, lab.width + 12 * K - 1, lab.height + 12 * K - 1], 8 * K, outline=(232, 131, 58, 255), width=3 * K)
        card.alpha_composite(lab, (6 * K, 6 * K))
        if k == 0:
            name = NAMES[idx]
            tw = cd.textlength(name, font=big)
            cd.text(((card.width - tw) / 2, lab.height + 13 * K), name, font=big, fill=(246, 240, 226, 255))
        rot = card.rotate(-math.degrees(a - math.pi), resample=Image.BICUBIC, expand=True)
        cx, cy = CX + RL * math.cos(a), CY + RL * math.sin(a)
        if k == 0:
            cx -= 14 * K
        shadow = Image.new("RGBA", rot.size, (0, 0, 0, 0))
        shadow.putalpha(rot.getchannel("A").filter(ImageFilter.GaussianBlur(5 * K)).point(lambda p: int(p * 0.5)))
        img.alpha_composite(shadow, (round(cx - rot.width / 2) + 2 * K, round(cy - rot.height / 2) + 5 * K))
        img.alpha_composite(rot, (round(cx - rot.width / 2), round(cy - rot.height / 2)))
    # drawer hint: a small grip on the arc and a fixed orange index at the middle
    d = ImageDraw.Draw(img)
    gx = CX - R + 9 * K
    d.rounded_rectangle([gx - 2 * K, CY - 210 * K, gx + 2 * K, CY - 170 * K], 2 * K, fill=(150, 148, 142, 255))
    d.polygon([(CX - RL + 70 * K, CY), (CX - RL + 80 * K, CY - 7 * K), (CX - RL + 80 * K, CY + 7 * K)], fill=(232, 131, 58, 255))
    img.convert("RGB").save(out)
    img.convert("RGB").resize((W // 2, H // 2), Image.LANCZOS).save(out.replace(".png", "_s.png"))
    print("ok")


if __name__ == "__main__":
    main()
