"""Engine contract (spec 9.2, Review Focus 1 and 2): film_engine built for the PC and driven through ctypes.

One contract rule per test. Numbers that come from the spec (565 packing, the stamp's position)
are written out here on purpose, as an oracle independent of the C code.
"""
from __future__ import annotations

import os
from pathlib import Path

import numpy as np
import pytest
from PIL import Image

import filmsbin

LUT_BYTES = filmsbin.LUT_SIZE ** 3 * 3
LEGACY = os.environ.get("FE_IMPL") == "legacy"  # the defects below (D1-D4) are fixed only by the new engine
GOLDEN = Path(__file__).resolve().parent / "golden"
HEADER = filmsbin.HEADER_STRUCT
ENTRY = filmsbin.ENTRY_STRUCT
ENTRY_ID, ENTRY_LUT_OFFSET = 0, -1  # field indexes of an unpacked ENTRY (id is first, lut_offset is last)


def lut_only(fe, film):
    """A copy of `film` with grain and halation off: develop then applies the LUT alone."""
    plain = fe.Film.from_buffer_copy(film)
    plain.grain_strength = 0.0
    plain.halation_strength = 0.0
    return plain


def max_abs_diff(a: np.ndarray, b: np.ndarray) -> int:
    return int(np.abs(a.astype(np.int32) - b.astype(np.int32)).max())


# --- catalog (Review Focus 1) ------------------------------------------------------------------

def test_catalog_reads_all_films(fe, catalog, reference_films):
    assert catalog.count() == len(reference_films)
    for i, ref in enumerate(reference_films):
        film = catalog.film(i)
        assert film.id == ref.id.encode("ascii")
        assert film.name == ref.name.encode("utf-8")
        # The catalog is the one place the file's quantized fields become the engine's floats.
        assert film.grain_strength == pytest.approx(ref.grain_strength / 255)
        assert film.grain_size == pytest.approx(ref.grain_size_q4 / 16)
        assert film.halation_strength == pytest.approx(ref.halation_strength / 255)
        assert film.halation_radius == ref.halation_radius
        assert tuple(film.halation_tint) == ref.halation_tint
        assert film.lut_size == filmsbin.LUT_SIZE
        assert catalog.find(ref.id) == i
    assert catalog.find("no_such_film") == -1


def _with_header_field(data: bytes, index: int, value) -> bytes:
    """data with one header field replaced (index into magic, version, film_count, lut_size)."""
    fields = list(HEADER.unpack_from(data))
    fields[index] = value
    out = bytearray(data)
    HEADER.pack_into(out, 0, *fields)
    return bytes(out)


def _with_entry_field(data: bytes, entry: int, field: int, value) -> bytes:
    """data with one field of one entry replaced (field indexes into the unpacked ENTRY)."""
    entry_at = HEADER.size + entry * ENTRY.size
    fields = list(ENTRY.unpack_from(data, entry_at))
    fields[field] = value
    out = bytearray(data)
    ENTRY.pack_into(out, entry_at, *fields)
    return bytes(out)


def _with_last_lut_offset(data: bytes, offset: int) -> bytes:
    """data with the last entry's lut_offset replaced (the earlier entries stay valid)."""
    return _with_entry_field(data, HEADER.unpack_from(data)[2] - 1, ENTRY_LUT_OFFSET, offset)


CORRUPTIONS = {
    "bad_magic": lambda d: _with_header_field(d, 0, b"FILX"),
    "version_2": lambda d: _with_header_field(d, 1, 2),
    "zero_films": lambda d: _with_header_field(d, 2, 0),
    "truncated_header": lambda d: d[:HEADER.size - 1],
    "truncated_entry_table": lambda d: d[:HEADER.size + 3 * ENTRY.size + ENTRY.size // 2],
    # fe_catalog_find runs strcmp on id: 24 bytes with no NUL would run into the next field.
    "id_unterminated": lambda d: _with_entry_field(d, 0, ENTRY_ID, b"A" * 24),
    # One byte too far: the bounds check is "<=" not "<".
    "lut_offset_one_byte_past_end": lambda d: _with_last_lut_offset(d, len(d) - LUT_BYTES + 1),
    # A 32-bit offset + size wraps. This host has a 64-bit size_t and only sees the wrap if the check
    # sums in 32 bits; what rules out the wrap on the target is the subtraction form in catalog.c
    # (size >= lut_bytes && offset <= size - lut_bytes), not this case. The case keeps that form honest.
    "lut_offset_wraps": lambda d: _with_last_lut_offset(d, 0xFFFFFFF0),
}


@pytest.mark.parametrize("corruption", CORRUPTIONS)
def test_catalog_rejects_corrupt(fe, films_blob, corruption):
    # open_catalog hands the engine a blob that ends at an inaccessible page: a read past `size` crashes.
    err, _ = fe.open_catalog(CORRUPTIONS[corruption](films_blob))
    assert err == fe.BAD_FILMS


# --- LUT ---------------------------------------------------------------------

def test_lut_matches_reference(fe, catalog, reference_films):
    rng = np.random.default_rng(1)
    colors = rng.integers(0, 256, size=(4096, 3), dtype=np.uint8)
    grays = np.repeat(np.arange(256, dtype=np.uint8)[:, None], 3, axis=1)
    pixels = np.concatenate([colors, grays]).reshape(16, 272, 3)
    for i, ref in enumerate(reference_films):
        got = fe.develop(pixels, film=lut_only(fe, catalog.film(i)))
        want = filmsbin.apply_lut_tetrahedral(ref.lut, pixels)
        assert max_abs_diff(got, want) <= 1, ref.id


# --- develop stages (Review Focus 2) -----------------------------------------------------------

def test_halation_noop_without_highlights(fe, unit_film):
    rng = np.random.default_rng(2)
    # Every channel <= 100: below the engine's highlight threshold, which is >= 191 (ruling R4).
    dim = rng.integers(0, 101, size=(768, 768, 3), dtype=np.uint8)
    # "Unchanged" is measured against the same film without halation: the 33-node uint8 identity
    # LUT is within one level of the identity, not bit-exact, so the input itself is not the baseline.
    assert np.array_equal(fe.develop(dim, film=unit_film(halation=1.0)),
                          fe.develop(dim, film=unit_film(halation=0.0)))
    # Control: with a highlight the same film does glow, so the check above is not vacuous.
    lit = np.zeros((768, 768, 3), dtype=np.uint8)
    lit[352:416, 352:416] = 255
    assert not np.array_equal(fe.develop(lit, film=unit_film(halation=1.0)),
                              fe.develop(lit, film=unit_film(halation=0.0)))


def test_grain_zero_mean_midtone_peak(fe, unit_film):
    grained, plain = unit_film(grain=1.0), unit_film(grain=0.0)
    spread = {}
    for gray in (16, 128, 240):
        flat = np.full((768, 768, 3), gray, dtype=np.uint8)
        # Grain as an offset from the same film without grain (cancels the identity LUT's rounding).
        offset = fe.develop(flat, film=grained, seed=7).astype(np.float64) - fe.develop(flat, film=plain, seed=7)
        assert abs(offset.mean()) <= 0.5, f"gray {gray}: mean offset {offset.mean():.3f}"
        spread[gray] = offset.std()
    assert spread[128] > spread[16] and spread[128] > spread[240], spread


def test_date_stamp_only_in_rect(fe):
    height = width = 768
    base = np.full((height, width, 3), 40, dtype=np.uint8)
    stamped = fe.develop(base, date=fe.Date(2026, 11, 3))
    changed = np.any(stamped != base, axis=-1)
    ys, xs = np.nonzero(changed)
    assert ys.size > 0, "nothing was drawn"
    # Bottom-right: the right half, the bottom eighth.
    assert xs.min() >= width // 2 and ys.min() >= height - height // 8
    # No date, no film: nothing to do.
    assert np.array_equal(fe.develop(base), base)


def test_develop_saturates_without_wraparound(fe, unit_film):
    white = np.full((768, 768, 3), 255, dtype=np.uint8)
    black = np.zeros((768, 768, 3), dtype=np.uint8)
    # The strongest halation on an all-white image adds on top of 255: it must stay 255, not wrap.
    assert (fe.develop(white, film=unit_film(halation=1.0)) == 255).all()
    # Grain pushing past 0 or 255 saturates; a wrap would show as a near-black pixel on white or near-white on black.
    grained = unit_film(grain=1.0)
    grained_black = fe.develop(black, film=grained, seed=3)
    grained_white = fe.develop(white, film=grained, seed=3)
    # Control: grain does act on pure black and white (its midtone weight has a floor), so the
    # checks below are not vacuous.
    assert grained_black.any() and (grained_white < 255).any()
    assert not (grained_black > 128).any()
    assert not (grained_white < 128).any()


# --- new in the pianke suite (spec 11.1) -----------------------------------------------------------

def _film_index(reference_films, key) -> int:
    """Index of the first film with the largest key(film)."""
    return max(range(len(reference_films)), key=lambda i: (key(reference_films[i]), -i))


@pytest.mark.skipif(LEGACY, reason="the legacy engine has RGB888 only")
def test_rgba_matches_rgb(fe, catalog, reference_films):
    rng = np.random.default_rng(4)
    rgba = rng.integers(0, 256, size=(768, 768, 4), dtype=np.uint8)
    film = catalog.film(_film_index(reference_films, lambda f: f.halation_strength))
    date = fe.Date(2026, 11, 6)
    out3 = fe.develop(rgba[..., :3], film=film, date=date, seed=5)
    out4 = fe.develop(rgba, film=film, date=date, seed=5)
    assert np.array_equal(out4[..., :3], out3)
    assert np.array_equal(out4[..., 3], rgba[..., 3])


@pytest.mark.parametrize("name", ["sweep", "discs", "patches"])
def test_golden_square(fe, catalog, reference_films, name):
    rgb = np.asarray(Image.open(GOLDEN / "inputs" / f"{name}.png").convert("RGB"))
    for i, ref in enumerate(reference_films):
        film = fe.film_with(catalog.film(i), grain_strength=0.0)
        want = np.asarray(Image.open(GOLDEN / "expected" / f"{ref.id}_{name}.png").convert("RGB"))
        assert np.array_equal(fe.develop(rgb, film=film, seed=1), want), ref.id


def _grain_offset(fe, film, gray_image) -> np.ndarray:
    """Grain alone: develop with the film's grain minus develop with grain_strength 0, summed over channels."""
    plain = fe.film_with(film, grain_strength=0.0)
    return (fe.develop(gray_image, film=film, seed=9).astype(np.float64)
            - fe.develop(gray_image, film=plain, seed=9)).sum(axis=-1)


def _half_correlation_lag(offset: np.ndarray) -> float:
    """Fractional x-lag at which the autocorrelation of `offset` falls to 0.5 (linear interpolation)."""
    d = offset - offset.mean()
    n = d.shape[1]
    power = (np.abs(np.fft.rfft(d, n=2 * n, axis=1)) ** 2).mean(axis=0)
    ac = np.fft.irfft(power)[:n]
    ac = ac / ac[0]
    k = int(np.argmax(ac < 0.5))
    assert k > 0, "autocorrelation never falls to 0.5"
    return k - 1 + (ac[k - 1] - 0.5) / (ac[k - 1] - ac[k])


def _radius_at_tenth(profile: np.ndarray) -> float:
    """Distance from the centre (index 0 of `profile`) at which it falls to 10% of its peak, searched
    outward from the peak (inside the disc the glow saturates to no change), linearly interpolated."""
    peak = int(np.argmax(profile))
    level = 0.1 * profile[peak]
    k = peak + int(np.argmax(profile[peak:] < level))
    assert k > peak, "profile never falls to 10% of its peak"
    return k - 1 + (profile[k - 1] - level) / (profile[k - 1] - profile[k])


@pytest.mark.xfail(LEGACY, strict=True, reason="D1: legacy halation grid is 96x96 whatever the aspect")
def test_d1_halation_round_on_4x3(fe, catalog, reference_films, unit_film):
    # The strongest film's halation parameters on an identity LUT: its own LUT crushes the dim glow to
    # black (the glow is added before the LUT), which would leave nothing to measure.
    strongest = catalog.film(_film_index(reference_films, lambda f: f.halation_strength))
    film = fe.film_with(unit_film(), halation_strength=strongest.halation_strength,
                        halation_radius=strongest.halation_radius, halation_tint=strongest.halation_tint)
    off = fe.film_with(film, halation_strength=0.0)
    width, height, cx, cy, radius = 1024, 768, 512, 384, 40
    image = np.zeros((height, width, 3), dtype=np.uint8)
    yy, xx = np.mgrid[0:height, 0:width]
    image[(xx - cx) ** 2 + (yy - cy) ** 2 <= radius ** 2] = 255
    glow = (fe.develop(image, film=film).astype(np.float64) - fe.develop(image, film=off)).sum(axis=-1)
    # Profiles through the centre, folded onto one half-axis (mean of both sides).
    horizontal = (glow[cy, cx:cx + 300] + glow[cy, cx - 299:cx + 1][::-1]) / 2
    vertical = (glow[cy:cy + 300, cx] + glow[cy - 299:cy + 1, cx][::-1]) / 2
    ratio = _radius_at_tenth(horizontal) / _radius_at_tenth(vertical)
    assert 0.9 <= ratio <= 1.1, f"horizontal/vertical 10% radius ratio {ratio:.3f}"


@pytest.mark.xfail(LEGACY, strict=True, reason="D2: legacy grain size is in image pixels, not relative to the short side")
def test_d2_grain_scales_with_short_side(fe, catalog, reference_films):
    film = catalog.film(_film_index(reference_films, lambda f: f.grain_size_q4))
    lags = {}
    for side in (768, 3072):
        lags[side] = _half_correlation_lag(_grain_offset(fe, film, np.full((side, side, 3), 128, dtype=np.uint8)))
    ratio = lags[3072] / lags[768]
    assert 3.0 <= ratio <= 5.0, f"half-correlation lag ratio {ratio:.2f} ({lags})"


@pytest.mark.xfail(LEGACY, strict=True, reason="D3: legacy grain texture tiles every 256 px")
def test_d3_grain_no_256_period(fe, catalog, reference_films):
    film = catalog.film(_film_index(reference_films, lambda f: f.grain_size_q4))
    offset = _grain_offset(fe, film, np.full((512, 2048, 3), 128, dtype=np.uint8))
    a, b = offset[:, :-256].ravel(), offset[:, 256:].ravel()
    corr = float(np.corrcoef(a, b)[0, 1])
    assert corr < 0.2, f"correlation at a 256 px shift {corr:.3f}"


@pytest.mark.xfail(LEGACY, strict=True, reason="D4: legacy date height follows the image height, not the short side")
def test_d4_date_height_from_short_side(fe):
    rows = {}
    for width, height in ((768, 1024), (1024, 768)):
        base = np.full((height, width, 3), 40, dtype=np.uint8)
        stamped = fe.develop(base, date=fe.Date(2026, 11, 3))
        rows[(width, height)] = int(np.any(stamped != base, axis=(1, 2)).sum())
    assert rows[(768, 1024)] == rows[(1024, 768)], rows
