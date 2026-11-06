"""Bake contract (spec 9.1): films.bin agrees with films.toml, has the format of spec 4.2,
and its LUTs reproduce what spektrafilm renders directly.

The header and size checks use the numbers written in the spec on purpose: they are the
independent oracle for the format that filmsbin.py implements.
"""
from __future__ import annotations

import struct
from pathlib import Path

import numpy as np
import pytest

import bake_films
import filmsbin

ROOT = Path(__file__).resolve().parents[2]
TOML = ROOT / "films" / "films.toml"
BIN = ROOT / "app" / "src" / "main" / "assets" / "films.bin"
FILMS_PARTITION_SIZE = 0x200000  # spec 10

SPECS = bake_films.load_films_toml(TOML)


def test_entries_match_toml():
    entries = filmsbin.read_films_bin(BIN)
    # Same ids in the same order: catches "edited films.toml, forgot to re-bake".
    assert [e.id for e in entries] == [s.id for s in SPECS]
    for spec, entry in zip(SPECS, entries):
        # Limits of spec 4.2 (the writer enforces the same set).
        assert entry.id.isascii() and 1 <= len(entry.id) <= 23, entry.id
        assert len(entry.name.encode("utf-8")) <= 31, entry.name
        assert entry.grain_size_q4 >= 1, entry.id  # the engine divides by it
        # Parameters are the toml values at the spec's bin scale (0..1 -> 0..255, pixels -> x16), to the nearest step.
        assert entry.name == spec.name, entry.id
        assert entry.halation_radius == spec.halation_radius, entry.id
        assert entry.halation_tint == spec.halation_tint, entry.id
        assert abs(entry.grain_strength / 255 - spec.grain_strength) <= 0.5 / 255, entry.id
        assert abs(entry.halation_strength / 255 - spec.halation_strength) <= 0.5 / 255, entry.id
        assert abs(entry.grain_size_q4 / 16 - spec.grain_size) <= 0.5 / 16, entry.id


def test_header_and_lut_size():
    data = BIN.read_bytes()
    n = len(SPECS)
    assert data[:4] == b"FILM"
    version, film_count, lut_size = struct.unpack_from("<HHB", data, 4)
    assert (version, film_count, lut_size) == (1, n, 33)
    assert len(data) == 16 + 68 * n + 107811 * n
    assert len(data) <= FILMS_PARTITION_SIZE


def _gray_ramp() -> np.ndarray:
    levels = np.linspace(0, 255, 32).round().astype(np.uint8)
    return np.stack([levels] * 3, axis=-1)


def _colorchecker_srgb() -> np.ndarray:
    """The 24 ColorChecker patches as 8-bit sRGB, converted from the xyY data shipped with colour."""
    import colour

    checker = colour.CCS_COLOURCHECKERS["ColorChecker 2005"]
    xyz = colour.xyY_to_XYZ(np.array(list(checker.data.values())))
    rgb = colour.XYZ_to_sRGB(xyz, illuminant=checker.illuminant)
    return (np.clip(rgb, 0.0, 1.0) * 255.0).round().astype(np.uint8)


def _delta_e_2000(a: np.ndarray, b: np.ndarray) -> np.ndarray:
    import colour

    def to_lab(rgb_u8: np.ndarray) -> np.ndarray:
        return colour.XYZ_to_Lab(colour.sRGB_to_XYZ(rgb_u8 / 255.0))

    return colour.delta_E(to_lab(a), to_lab(b), method="CIE 2000")


@pytest.mark.parametrize("spec", SPECS, ids=lambda s: s.id)
def test_bake_matches_spektrafilm(spec: bake_films.FilmSpec):
    pytest.importorskip("spektrafilm")
    entry = {e.id: e for e in filmsbin.read_films_bin(BIN)}[spec.id]

    patches = np.concatenate([_gray_ramp(), _colorchecker_srgb()])  # (56, 3) uint8
    via_lut = filmsbin.apply_lut_tetrahedral(entry.lut, patches)
    direct = bake_films.render_direct(spec, patches)

    de = _delta_e_2000(via_lut, direct)
    assert de.mean() <= 1.0, f"{spec.id}: mean dE2000 {de.mean():.2f}"
    assert de.max() <= 3.0, f"{spec.id}: max dE2000 {de.max():.2f} at patch {de.argmax()}"
