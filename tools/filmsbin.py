"""films.bin: the only Python implementation of the format (spec K1).

Writer: tools/bake_films.py. Reader on the device: film_engine's catalog (C).
Everything that knows byte offsets, struct layouts or the LUT index order lives here;
nothing else in Python re-derives them.

Layout (little endian): 16-byte header, film_count * 68-byte entries, then one LUT per
entry stored back to back. In the file a LUT is LUT_SIZE^3 RGB888 triples, red fastest:
index = (b * N + g) * N + r. In memory (FilmEntry.lut, apply_lut_tetrahedral) the same LUT
is an (N, N, N, 3) uint8 array indexed [r][g][b]; read and write convert between the two.
"""
from __future__ import annotations

import struct
from dataclasses import dataclass
from os import PathLike
from pathlib import Path

import numpy as np

LUT_SIZE = 33

# magic, version, film_count, lut_size, 7 reserved bytes.
HEADER_STRUCT = struct.Struct("<4sHHB7x")
# id, name, grain_strength, grain_size_q4, halation_strength, halation_radius,
# halation_tint r, g, b, 1 reserved byte, lut_offset.
ENTRY_STRUCT = struct.Struct("<24s32s7BxI")

_MAGIC = b"FILM"
_VERSION = 1
_LUT_BYTES = LUT_SIZE**3 * 3


@dataclass
class FilmEntry:
    """One film: the films_bin_entry_t fields that carry data (the reserved byte and lut_offset are
    layout bookkeeping that read and write handle), plus its LUT."""

    id: str                                   # ASCII, <= 23 chars
    name: str                                 # UTF-8, <= 31 bytes
    grain_strength: int                       # 0..255 <-> 0..1
    grain_size_q4: int                        # grain diameter in pixels at a 768 px short side, * 16
    halation_strength: int                    # 0..255 <-> 0..1
    halation_radius: int                      # in cells of the highlight map (long side 96 cells)
    halation_tint: tuple[int, int, int]
    lut: np.ndarray                           # (33, 33, 33, 3) uint8, indexed [r][g][b]


def read_films_bin(path: str | PathLike[str]) -> list[FilmEntry]:
    """Parse a films.bin."""
    data = Path(path).read_bytes()
    film_count = HEADER_STRUCT.unpack_from(data)[2]
    entries = []
    for i in range(film_count):
        (id_, name, grain_strength, grain_size_q4, halation_strength, halation_radius,
         tint_r, tint_g, tint_b, lut_offset) = ENTRY_STRUCT.unpack_from(
            data, HEADER_STRUCT.size + i * ENTRY_STRUCT.size)
        file_order = np.frombuffer(data, dtype=np.uint8, count=_LUT_BYTES, offset=lut_offset)
        entries.append(FilmEntry(
            id=id_.split(b"\0")[0].decode("ascii"),
            name=name.split(b"\0")[0].decode("utf-8"),
            grain_strength=grain_strength,
            grain_size_q4=grain_size_q4,
            halation_strength=halation_strength,
            halation_radius=halation_radius,
            halation_tint=(tint_r, tint_g, tint_b),
            lut=file_order.reshape(LUT_SIZE, LUT_SIZE, LUT_SIZE, 3).transpose(2, 1, 0, 3),  # [b][g][r] -> [r][g][b]
        ))
    return entries


def write_films_bin(path: str | PathLike[str], entries: list[FilmEntry]) -> None:
    """Write entries in order and compute every lut_offset. Raises ValueError for what struct would
    silently get wrong: an id empty or longer than 23 bytes, a name longer than 31 UTF-8 bytes. Byte fields outside 0..255 raise struct.error."""
    lut_start = HEADER_STRUCT.size + len(entries) * ENTRY_STRUCT.size
    chunks = [HEADER_STRUCT.pack(_MAGIC, _VERSION, len(entries), LUT_SIZE)]
    luts = []
    for i, e in enumerate(entries):
        id_, name = e.id.encode("ascii"), e.name.encode("utf-8")
        if not 1 <= len(id_) <= 23:
            raise ValueError(f"film id must be 1..23 ASCII bytes: {e.id!r}")
        if len(name) > 31:
            raise ValueError(f"film name must be <= 31 UTF-8 bytes: {e.name!r}")
        chunks.append(ENTRY_STRUCT.pack(
            id_, name, e.grain_strength, e.grain_size_q4, e.halation_strength, e.halation_radius,
            *e.halation_tint, lut_start + i * _LUT_BYTES))
        luts.append(np.ascontiguousarray(e.lut.transpose(2, 1, 0, 3)).tobytes())  # [r][g][b] -> [b][g][r]
    Path(path).write_bytes(b"".join(chunks + luts))


def apply_lut_tetrahedral(lut: np.ndarray, rgb: np.ndarray) -> np.ndarray:
    """Reference tetrahedral interpolation (colour-science) of an (N, N, N, 3) uint8 LUT indexed
    [r][g][b] over uint8 rgb of shape (..., 3). Returns uint8 of the same shape.

    The C engine must agree with this to within one level per channel.
    """
    from colour.algebra import table_interpolation_tetrahedral

    return np.rint(table_interpolation_tetrahedral(rgb / 255.0, lut)).astype(np.uint8)
