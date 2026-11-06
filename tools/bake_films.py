"""Bake films/films.toml into films/films.bin with spektrafilm (spec 4.1, 4.2, 12).

    python tools/bake_films.py --toml films/films.toml --out films/films.bin

PC only. spektrafilm is GPLv3, so it is imported lazily inside the functions that need it:
this module (and therefore the bake tests that do not need spektrafilm) imports without it.

Why this drives spektrafilm's Python API instead of the `spektrafilm-lut` CLI (task 1 findings):
  * the CLI always runs film -> print, which turns a slide film into an inverted image,
    and has no way to scan the film itself, so slides cannot be baked through it;
  * its --stops-above-gray flag is never read (cli.py looks for `stops_above_midgray`).
So the pipeline is configured in _pipeline: slides are scanned directly (io.scan_film), and no
input exposure gain is applied.
"""
from __future__ import annotations

import argparse
import tomllib
from dataclasses import dataclass
from os import PathLike
from pathlib import Path

import numpy as np

import filmsbin

_KINDS = ("negative", "cine", "slide")


@dataclass(frozen=True)
class FilmSpec:
    """One [[film]] table of films.toml, flattened. Units as in the toml (strength 0..1, size in pixels)."""

    id: str
    name: str
    kind: str                                  # one of _KINDS
    stock: str                                 # spektrafilm film profile slug
    print: str | None                          # spektrafilm print profile slug; None exactly when kind == "slide"
    grain_strength: float
    grain_size: float
    halation_strength: float
    halation_radius: int
    halation_tint: tuple[int, int, int]


def load_films_toml(path: str | PathLike[str]) -> list[FilmSpec]:
    """Parse films.toml, in file order. Raises ValueError for what only the toml can get wrong:
    unknown kind, `print` present on a slide or missing on any other kind, duplicate ids."""
    with open(path, "rb") as f:
        tables = tomllib.load(f)["film"]
    specs = [
        FilmSpec(
            id=t["id"],
            name=t["name"],
            kind=t["kind"],
            stock=t["stock"],
            print=t.get("print"),
            grain_strength=t["grain"]["strength"],
            grain_size=t["grain"]["size"],
            halation_strength=t["halation"]["strength"],
            halation_radius=t["halation"]["radius"],
            halation_tint=tuple(t["halation"]["tint"]),
        )
        for t in tables
    ]
    for spec in specs:
        if spec.kind not in _KINDS:
            raise ValueError(f"{spec.id}: kind must be one of {_KINDS}, got {spec.kind!r}")
        if (spec.print is None) != (spec.kind == "slide"):
            raise ValueError(f"{spec.id}: `print` is required for negative/cine and forbidden for slide")
    ids = [s.id for s in specs]
    if len(set(ids)) != len(ids):
        raise ValueError(f"duplicate film ids in {path}")
    return specs


def entry_from_spec(spec: FilmSpec, lut: np.ndarray) -> filmsbin.FilmEntry:
    """Quantise a FilmSpec into a bin entry (strength * 255, size * 16, ...). The only place that
    knows the toml-unit to bin-unit conversion; the writer rejects what does not fit."""
    return filmsbin.FilmEntry(
        id=spec.id,
        name=spec.name,
        grain_strength=round(spec.grain_strength * 255),
        grain_size_q4=round(spec.grain_size * 16),
        halation_strength=round(spec.halation_strength * 255),
        halation_radius=spec.halation_radius,
        halation_tint=spec.halation_tint,
        lut=lut,
    )


def bake_lut(spec: FilmSpec) -> np.ndarray:
    """Bake the film's 33^3 LUT: an sRGB code grid is decoded to linear, run through the spektrafilm
    pipeline (lut_mode: deterministic, no halation, no grain), and encoded back to sRGB.
    Returns (33, 33, 33, 3) uint8, indexed [r][g][b]."""
    from spektrafilm_lut_creator.color_spaces import decode_cctf, encode_cctf
    from spektrafilm_lut_creator.grid import cube_grid, grid_as_image

    n = filmsbin.LUT_SIZE
    linear = decode_cctf(grid_as_image(cube_grid(n), n), "sRGB").astype(np.float32)
    srgb = encode_cctf(_pipeline(spec, runtime_srgb_io=False).process(linear), "sRGB")
    # cube_grid runs red fastest, so the flat result reshapes to [b][g][r]; the entry wants [r][g][b].
    return _to_uint8(srgb).reshape(n, n, n, 3).transpose(2, 1, 0, 3)


def render_direct(spec: FilmSpec, rgb: np.ndarray) -> np.ndarray:
    """Oracle for the bake contract: render uint8 sRGB pixels of shape (..., 3) through the same
    film simulation with no LUT in between, letting spektrafilm's runtime do the sRGB decode and
    encode itself. Returns uint8 of the same shape."""
    pixels = (rgb.reshape(1, -1, 3) / 255.0).astype(np.float32)
    out = _pipeline(spec, runtime_srgb_io=True).process(pixels)
    return _to_uint8(out).reshape(rgb.shape)


def _pipeline(spec: FilmSpec, *, runtime_srgb_io: bool):
    """Build the SimulationPipeline shared by bake_lut and render_direct (the only place it is
    configured): lut_mode, sRGB primaries in and out, scan_film iff kind == "slide".
    runtime_srgb_io=True makes spektrafilm decode/encode the sRGB curve itself (render_direct);
    False leaves linear in and out for bake_lut, which owns the curve. Raises ValueError if the
    profile's negative/positive type disagrees with spec.kind.

    Exposure: no input gain is applied, so sRGB mid-gray (118) lands at about 115. digest_params
    forces auto_exposure off and exposure_compensation_ev to 0 under lut_mode; that is the whole
    decision. The library's own sRGB default for bundles would be +4 stops (mid-gray to ~190).
    Gamut compression stays at the library defaults, which equal what spektrafilm-lut bakes with.
    """
    from spektrafilm.runtime.params_builder import digest_params, init_params
    from spektrafilm.runtime.pipeline import SimulationPipeline

    # A slide is scanned directly, so its print is never used; init_params' default print fills the slot.
    params = init_params(spec.stock) if spec.print is None else init_params(spec.stock, spec.print)
    is_slide = spec.kind == "slide"
    if params.film.is_positive != is_slide:
        raise ValueError(f"{spec.id}: kind {spec.kind!r} disagrees with profile {spec.stock!r}")
    params.debug.lut_mode = True
    params.io.scan_film = is_slide
    params.io.input_color_space = "sRGB"
    params.io.output_color_space = "sRGB"
    params.io.input_cctf_decoding = runtime_srgb_io
    params.io.output_cctf_encoding = runtime_srgb_io
    return SimulationPipeline(digest_params(params))


def _to_uint8(x: np.ndarray) -> np.ndarray:
    return np.rint(np.clip(x, 0.0, 1.0) * 255.0).astype(np.uint8)


def main() -> None:
    """CLI: --toml PATH --out PATH. Bakes every film in toml order and writes films.bin."""
    parser = argparse.ArgumentParser(description="Bake films.toml into films.bin with spektrafilm.")
    parser.add_argument("--toml", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()

    entries = []
    for spec in load_films_toml(args.toml):
        print(f"baking {spec.id} ({spec.stock})", flush=True)
        entries.append(entry_from_spec(spec, bake_lut(spec)))
    filmsbin.write_films_bin(args.out, entries)
    print(f"wrote {args.out}: {len(entries)} films, {args.out.stat().st_size} bytes")


if __name__ == "__main__":
    main()
