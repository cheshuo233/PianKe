"""片刻 app icon, take 6 (from the user's reference): a silver camera body with a glass film window,
the user's label art behind the glass, and a large machined dial on the right edge whose single red
notch marks the selected film.

Renders the launcher icon masters (1024 px, the full 108 dp adaptive-icon canvas) from the Gold 200 label art.
Regenerate (labels_dir is app/src/main/assets/labels):
    python -I tools/icon/render_icon.py app/src/main/assets/labels <out_dir> gold200
Writes body_bg.png (background layer), front_fg.png (foreground layer), front_mono.png and front_preview.png.
"""
import math
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

SS = 2048
OUT = 1024
V0 = SS * 18 / 108
VS = SS * 72 / 108
LIGHT = math.radians(-120)

# window (in the visible square, 0..1)
WX0, WY0, WX1, WY1 = 0.10, 0.28, 0.62, 0.72
# dial: big, centred just beyond the right edge
RD = 0.47 * VS
CX, CY = V0 + 1.04 * VS, SS / 2

yy, xx = np.mgrid[0:SS, 0:SS].astype(np.float32)


def px(u):  # visible-square units -> canvas px
    return V0 + u * VS


def rgba(a):
    return Image.fromarray(np.clip(a, 0, 255).astype(np.uint8), "RGBA")


def rounded_mask(x0, y0, x1, y1, r, blur=0.0):
    m = Image.new("L", (SS, SS), 0)
    ImageDraw.Draw(m).rounded_rectangle([x0, y0, x1, y1], int(r), fill=255)
    if blur:
        m = m.filter(ImageFilter.GaussianBlur(blur))
    return np.asarray(m, np.float32) / 255


def body():
    """Satin, brushed silver: darker and warmer than the dial, lit from above."""
    rng = np.random.default_rng(11)
    v = 182 - 26 * (yy / SS)
    streak = np.repeat(rng.normal(0, 1, (SS, 1)).astype(np.float32), SS, axis=1)
    fine = rng.normal(0, 1, (SS, SS)).astype(np.float32)
    fine = np.asarray(Image.fromarray(((fine * 20) + 128).clip(0, 255).astype(np.uint8)).filter(ImageFilter.BoxBlur(1)), np.float32) - 128
    v = v + streak * 3.2 + fine * 0.10
    rgb = np.stack([v * 1.0, v * 0.99, v * 0.965], -1)
    return Image.fromarray(rgb.clip(0, 255).astype(np.uint8), "RGB")


def window_and_card(label: Path, frost: float = 0.0):
    out = np.zeros((SS, SS, 4), np.float32)
    x0, y0, x1, y1 = px(WX0), px(WY0), px(WX1), px(WY1)
    r = 0.07 * VS
    # recess bevel: dark above/left, light below/right (the window is cut into the body)
    outer = rounded_mask(x0 - 0.018 * VS, y0 - 0.018 * VS, x1 + 0.018 * VS, y1 + 0.018 * VS, r + 0.018 * VS)
    lip = outer - rounded_mask(x0, y0, x1, y1, r)
    tone = np.clip((yy - (y0 + y1) / 2) / ((y1 - y0) / 2), -1, 1)
    lip_v = 150 + 70 * tone
    out[..., :3] += (lip * lip_v)[..., None]
    out[..., 3] += lip * 255
    # dark frame inside the window
    inner_r = 0.055 * VS
    m_in = rounded_mask(x0, y0, x1, y1, r)
    out[..., :3] = out[..., :3] * (1 - m_in[..., None]) + 34 * m_in[..., None]
    out[..., 3] = np.maximum(out[..., 3], m_in * 255)
    # the film label, cover-fitted, inset a little
    ins = 0.022 * VS
    cx0, cy0, cx1, cy1 = x0 + ins, y0 + ins, x1 - ins, y1 - ins
    lab = Image.open(label).convert("RGB")
    tw, th = int(cx1 - cx0), int(cy1 - cy0)
    scale = max(tw / lab.width, th / lab.height)
    lab = lab.resize((math.ceil(lab.width * scale), math.ceil(lab.height * scale)), Image.LANCZOS)
    ox, oy = (lab.width - tw) // 2, (lab.height - th) // 2
    lab = lab.crop((ox, oy, ox + tw, oy + th))
    if frost:
        lab = lab.filter(ImageFilter.GaussianBlur(frost * VS))   # frosted glass: colours and bands, no legible text
    card = np.zeros((SS, SS, 3), np.float32)
    card[int(cy0):int(cy0) + th, int(cx0):int(cx0) + tw] = np.asarray(lab, np.float32)
    m_card = rounded_mask(cx0, cy0, cx1, cy1, inner_r)
    out[..., :3] = out[..., :3] * (1 - m_card[..., None]) + card * m_card[..., None]
    # glass: inner shadow from the top-left edges, a diagonal reflection, a bright top lip, a cool tint
    depth = 1 - rounded_mask(x0 + 0.03 * VS, y0 + 0.035 * VS, x1 + 0.01 * VS, y1 + 0.02 * VS, r, blur=0.03 * VS)
    shade = depth * m_in * 0.55
    out[..., :3] *= (1 - shade)[..., None]
    u = (xx - x0) / (x1 - x0)
    w = (yy - y0) / (y1 - y0)
    band = np.exp(-(((u * 0.8 + w * 0.55) - 0.40) / 0.12) ** 2) * 0.30 + np.exp(-(((u * 0.8 + w * 0.55) - 0.66) / 0.03) ** 2) * 0.16
    band *= np.clip(1.2 - w, 0, 1)
    out[..., :3] = out[..., :3] * (1 - (band * m_in)[..., None]) + 255 * (band * m_in)[..., None]
    toplip = np.exp(-((yy - (y0 + 0.012 * VS)) / (0.004 * VS)) ** 2) * m_in * 0.35
    out[..., :3] = out[..., :3] * (1 - toplip[..., None]) + 255 * toplip[..., None]
    tint = (0.05 + (0.10 if frost else 0.0)) * m_in
    out[..., :3] = out[..., :3] * (1 - tint[..., None]) + np.array([150, 175, 200], np.float32) * tint[..., None]
    return rgba(out)


def dial():
    dx, dy = xx - CX + 0.5, yy - CY + 0.5
    r = np.hypot(dx, dy)
    th = np.arctan2(dy, dx)
    lit = 0.5 + 0.5 * np.cos(th - LIGHT)
    out = np.zeros((SS, SS, 4), np.float32)
    face = 214 + 26 * np.cos(2 * (th - LIGHT)) + 8 * np.cos(4 * th + 0.4) + 3 * np.sin(r * 0.8) - 10 * (r / RD) ** 6
    cham = 0.05 * RD
    v = np.where(r > RD - cham, 160 + 90 * lit, face)
    seam = np.exp(-((r - (RD - cham)) / 3.0) ** 2)
    v = v + 40 * seam * lit - 50 * seam * (1 - lit)
    rgb = np.stack([v, v * 0.997, v * 0.99], -1)
    # a ring of recessed notches; the one facing the window is red
    n = 44
    k = np.round(th / (2 * math.pi / n))
    da = th - k * (2 * math.pi / n)
    r0, r1 = 0.74 * RD, 0.88 * RD
    half = 0.018 * RD
    slot = (np.abs(da * r) < half) & (r > r0) & (r < r1)
    lower = (np.abs(da * r) < half) & (r > r0) & (r < r0 + 0.012 * RD)
    rgb[slot] = np.array([128, 127, 123], np.float32)
    rgb[lower] = np.array([96, 95, 92], np.float32)
    left = np.abs(np.abs(th) - math.pi) < (math.pi / n)
    red = slot & left
    rgb[red] = np.array([214, 44, 36], np.float32)
    out[..., :3] = rgb
    out[..., 3] = np.clip(RD - r + 0.5, 0, 1) * 255
    return rgba(out)


def shadow_of(img, blur, off, strength):
    a = np.asarray(img.getchannel("A").filter(ImageFilter.GaussianBlur(blur)), np.float32) * strength
    sh = np.zeros((SS, SS, 4), np.float32)
    sh[..., :3] = 20
    sh[..., 3] = a
    canvas = Image.new("RGBA", (SS, SS), (0, 0, 0, 0))
    canvas.alpha_composite(rgba(sh), off)
    return canvas


def main():
    labels_dir, out_dir = Path(sys.argv[1]), Path(sys.argv[2])
    label_id = sys.argv[3] if len(sys.argv) > 3 else "portra160"
    frost = float(sys.argv[4]) if len(sys.argv) > 4 else 0.0
    tag = f"{label_id}_{'frost' if frost else 'clear'}"
    out_dir.mkdir(parents=True, exist_ok=True)
    fg = Image.new("RGBA", (SS, SS), (0, 0, 0, 0))
    fg.alpha_composite(window_and_card(labels_dir / f"{label_id}.png", frost))
    d = dial()
    fg.alpha_composite(shadow_of(d, 34, (-30, 30), 0.55))
    fg.alpha_composite(d)
    fg_out = fg.resize((OUT, OUT), Image.LANCZOS)
    fg_out.save(out_dir / "front_fg.png")
    bg_out = body().resize((OUT, OUT), Image.LANCZOS)
    bg_out.save(out_dir / "body_bg.png")
    mono = Image.new("RGBA", (OUT, OUT), (255, 255, 255, 0))
    mono.putalpha(fg_out.getchannel("A").point(lambda v: 255 if v > 40 else 0))
    mono.save(out_dir / "front_mono.png")
    icon = bg_out.convert("RGBA")
    icon.alpha_composite(fg_out)
    crop = int(OUT * 18 / 108)
    shown = icon.crop((crop, crop, OUT - crop, OUT - crop)).resize((OUT, OUT), Image.LANCZOS)
    sq, ci = shown.copy(), shown.copy()
    m = Image.new("L", (OUT, OUT), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, OUT - 1, OUT - 1], radius=int(OUT * 0.23), fill=255)
    sq.putalpha(m)
    m2 = Image.new("L", (OUT, OUT), 0)
    ImageDraw.Draw(m2).ellipse([0, 0, OUT - 1, OUT - 1], fill=255)
    ci.putalpha(m2)
    sheet = Image.new("RGB", (OUT * 2 + 60 + 40 + 192 + 40 + 96 + 20, OUT + 40), (246, 245, 242))
    sheet.paste(sq, (20, 20), sq)
    sheet.paste(ci, (OUT + 40, 20), ci)
    x = 2 * OUT + 80
    for size in (192, 96):
        s = sq.resize((size, size), Image.LANCZOS)
        sheet.paste(s, (x, 20), s)
        x += size + 40
    sheet.save(out_dir / "front_preview.png")
    sheet.resize((sheet.width * 45 // 100, sheet.height * 45 // 100)).save(out_dir / f"front_{tag}_s.png")
    fg_out.save(out_dir / f"front_{tag}_fg.png")
    print("ok")


if __name__ == "__main__":
    main()
