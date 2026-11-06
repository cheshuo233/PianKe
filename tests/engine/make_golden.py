r"""Generate tests/engine/golden/: the three smooth 768x768 inputs and, for every film, the legacy
engine's output on each (grain 0, no date, seed 1). Run once under the legacy engine, commit, freeze:

    $env:FE_IMPL='legacy'; tools\.venv\Scripts\python tests/engine/make_golden.py

Re-running reproduces the same bytes. FE_SRC may point at the legacy source (default <repo>/engine,
which is still the legacy code while Task 1 is the latest commit).
"""
from __future__ import annotations

import os
import shutil
import sys
from pathlib import Path

os.environ["FE_IMPL"] = "legacy"
HERE = Path(__file__).resolve().parent
sys.path[:0] = [str(HERE), str(HERE.parents[1] / "tools")]

import numpy as np  # noqa: E402
from PIL import Image  # noqa: E402

import conftest  # noqa: E402
import filmsbin  # noqa: E402

SIZE = 768
GOLDEN = HERE / "golden"
GRID = np.mgrid[0:SIZE, 0:SIZE].astype(np.float64)  # [y, x]
Y, X = GRID


def _smoothstep(edge0: float, edge1: float, x: np.ndarray) -> np.ndarray:
    t = np.clip((x - edge0) / (edge1 - edge0), 0.0, 1.0)
    return t * t * (3 - 2 * t)


def _hsv_to_rgb(h: np.ndarray, s: np.ndarray, v: np.ndarray) -> np.ndarray:
    k = (np.stack([np.full_like(h, 5.0), np.full_like(h, 3.0), np.full_like(h, 1.0)], axis=-1)
         + h[..., None] * 6) % 6
    return v[..., None] * (1 - s[..., None] * np.clip(np.minimum(k, 4 - k), 0, 1))


def sweep() -> np.ndarray:
    """Hue across x, value from 0.08 (top) to 0.95 (bottom), saturation 0.8."""
    rgb = _hsv_to_rgb(X / SIZE, np.full_like(X, 0.8), 0.08 + 0.87 * Y / (SIZE - 1))
    return np.rint(rgb * 255).astype(np.uint8)


def discs() -> np.ndarray:
    """Three white discs of radius 40, 90 and 160 on black, edges soft over 4 px."""
    coverage = np.zeros((SIZE, SIZE))
    for cx, cy, r in ((160, 200, 40), (480, 220, 90), (400, 560, 160)):
        d = np.hypot(X - cx, Y - cy)
        coverage = np.maximum(coverage, 1 - _smoothstep(r - 2, r + 2, d))
    return np.repeat(np.rint(coverage * 255).astype(np.uint8)[..., None], 3, axis=-1)


def patches() -> np.ndarray:
    """6 x 4 midtone patches (128 x 192 px) with 16 px soft borders; hues step by the golden angle."""
    colors = np.empty((4, 6, 3))
    for i in range(24):
        h = (i * 0.61803398875) % 1.0
        colors[i // 6, i % 6] = _hsv_to_rgb(np.array(h), np.array(0.45 + 0.1 * (i % 3)),
                                            np.array(0.4 + 0.2 * ((i * 7) % 5) / 4))
    out = np.empty((SIZE, SIZE, 3))
    # Soft borders: weight of each neighbour cell via a smoothstep across the cell boundary.
    xs, ys = np.arange(SIZE), np.arange(SIZE)

    def axis_weights(coords: np.ndarray, cells: int) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
        size = SIZE / cells
        cell = np.minimum((coords // size).astype(int), cells - 1)
        pos = coords - cell * size - size / 2          # offset from the cell centre
        edge = size / 2
        t = _smoothstep(edge - 8, edge + 8, np.abs(pos)) * 0.5   # 0 inside, 0.5 at the border
        other = np.clip(cell + np.where(pos >= 0, 1, -1), 0, cells - 1)
        return cell, other, np.where(other == cell, 0.0, t)

    cx, ox, wx = axis_weights(xs, 6)
    cy, oy, wy = axis_weights(ys, 4)
    for y in range(SIZE):
        row = ((1 - wy[y]) * ((1 - wx)[:, None] * colors[cy[y], cx] + wx[:, None] * colors[cy[y], ox])
               + wy[y] * ((1 - wx)[:, None] * colors[oy[y], cx] + wx[:, None] * colors[oy[y], ox]))
        out[y] = row
    return np.rint(out * 255).astype(np.uint8)


INPUTS = {"sweep": sweep, "discs": discs, "patches": patches}


def save_png(path: Path, rgb: np.ndarray) -> None:
    Image.fromarray(rgb, "RGB").save(path, format="PNG", optimize=True)


def main() -> None:
    fe = conftest.Engine(conftest.build_dll())
    err, catalog = fe.open_catalog(conftest.FILMS_BIN.read_bytes())
    assert err == fe.OK
    ids = [e.id for e in filmsbin.read_films_bin(conftest.FILMS_BIN)]
    shutil.rmtree(GOLDEN, ignore_errors=True)
    (GOLDEN / "inputs").mkdir(parents=True)
    (GOLDEN / "expected").mkdir()
    for name, make in INPUTS.items():
        rgb = make()
        save_png(GOLDEN / "inputs" / f"{name}.png", rgb)
        for i, film_id in enumerate(ids):
            film = fe.film_with(catalog.film(i), grain_strength=0.0)
            save_png(GOLDEN / "expected" / f"{film_id}_{name}.png", fe.develop(rgb, film=film, seed=1))
    catalog.close()
    print(f"{len(INPUTS)} inputs, {len(ids) * len(INPUTS)} expected images in {GOLDEN}")


if __name__ == "__main__":
    main()
