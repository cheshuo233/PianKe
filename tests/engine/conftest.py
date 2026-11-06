"""Host build of film_engine and its ctypes binding, for the engine contract tests (spec 11.1).

The engine sources (default <repo>/engine, or the directory in FE_SRC, which holds include/ and src/)
are compiled with MSVC into tests/engine/_build/<impl>-<hash of source path>/film_engine.dll once per
pytest session and loaded with ctypes. FE_IMPL=new (default) or legacy picks the ctypes layout of
fe_image_t; every other piece of layout knowledge stays in this file. Tests talk to the engine through
the `fe` fixture (an `Engine`), never to ctypes directly.
"""
from __future__ import annotations

import ctypes
import hashlib
import os
import shutil
import subprocess
from ctypes import (CFUNCTYPE, POINTER, Structure, byref, c_char_p, c_float, c_int, c_size_t,
                    c_uint8, c_uint16, c_uint32, c_void_p, pointer)
from pathlib import Path

import numpy as np
import pytest

import filmsbin

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
IMPL = os.environ.get("FE_IMPL", "new")
if IMPL not in ("new", "legacy"):
    raise RuntimeError(f"FE_IMPL must be 'new' or 'legacy', got {IMPL!r}")
COMPONENT = Path(os.environ.get("FE_SRC") or ROOT / "engine").resolve()
# One build directory per (impl, source path): switching FE_SRC or FE_IMPL never loads a stale DLL.
BUILD_DIR = HERE / "_build" / f"{IMPL}-{hashlib.sha1(str(COMPONENT).lower().encode()).hexdigest()[:10]}"
DLL_PATH = BUILD_DIR / "film_engine.dll"
FILMS_BIN = ROOT / "app" / "src" / "main" / "assets" / "films.bin"
VCVARS = Path(r"C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat")


# ---------------------------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------------------------

def _msvc_environment() -> dict[str, str]:
    """The process environment after vcvars64.bat: cl, link, INCLUDE and LIB for x64."""
    if not VCVARS.exists():
        pytest.fail(f"MSVC 2022 Build Tools not found: {VCVARS}", pytrace=False)
    proc = subprocess.run(f'cmd /s /c ""{VCVARS}" >nul 2>nul & set"', capture_output=True,
                          encoding="oem", errors="replace")
    env = dict(line.split("=", 1) for line in proc.stdout.splitlines() if "=" in line)
    if "INCLUDE" not in env:
        pytest.fail(f"vcvars64.bat did not set up the compiler environment:\n{proc.stdout}{proc.stderr}",
                    pytrace=False)
    env["VSLANG"] = "1033"  # English compiler messages
    return env


def build_dll() -> Path:
    """Compile every src/*.c of the component into BUILD_DIR/film_engine.dll. /WX so a warning fails the
    build. /MD links the shared UCRT, the one python.exe already uses, instead of a second CRT."""
    BUILD_DIR.mkdir(parents=True, exist_ok=True)
    env = _msvc_environment()
    cl = shutil.which("cl", path=env.get("PATH") or env.get("Path"))
    if cl is None:
        pytest.fail("cl.exe not on PATH after vcvars64.bat", pytrace=False)
    cmd = [cl, "/nologo", "/std:c11", "/W4", "/WX", "/O2", "/MD", "/LD",
           "/DFE_API=__declspec(dllexport)", f"/I{COMPONENT / 'include'}",
           "/Fo.\\", "/Fefilm_engine.dll", *map(str, sorted((COMPONENT / "src").glob("*.c")))]
    proc = subprocess.run(cmd, cwd=BUILD_DIR, env=env, capture_output=True, encoding="oem", errors="replace")
    if proc.returncode != 0:
        pytest.fail(f"film_engine failed to build:\n{proc.stdout}{proc.stderr}", pytrace=False)
    return DLL_PATH


# ---------------------------------------------------------------------------------------------
# ctypes mirrors of film_engine.h
# ---------------------------------------------------------------------------------------------

class FeFilm(Structure):
    _fields_ = [("id", c_char_p), ("name", c_char_p),
                ("grain_strength", c_float), ("grain_size", c_float),
                ("halation_strength", c_float), ("halation_radius", c_uint8),
                ("halation_tint", c_uint8 * 3), ("lut_size", c_uint8),
                ("lut", POINTER(c_uint8))]


class FeDate(Structure):
    _fields_ = [("year", c_uint16), ("month", c_uint8), ("day", c_uint8)]


if IMPL == "legacy":
    class FeImage(Structure):
        _fields_ = [("rgb", POINTER(c_uint8)), ("width", c_uint16), ("height", c_uint16), ("stride", c_uint32)]
else:
    class FeImage(Structure):
        _fields_ = [("pixels", POINTER(c_uint8)), ("width", c_uint16), ("height", c_uint16),
                    ("stride", c_uint32), ("channels", c_uint8)]


ALLOC_FN = CFUNCTYPE(c_void_p, c_size_t)
RELEASE_FN = CFUNCTYPE(None, c_void_p)


class FeAllocator(Structure):
    _fields_ = [("alloc", ALLOC_FN), ("release", RELEASE_FN)]


class FeDevelopParams(Structure):
    _fields_ = [("film", POINTER(FeFilm)), ("date", POINTER(FeDate)), ("seed", c_uint32)]


# ---------------------------------------------------------------------------------------------
# A blob that faults on any read past its end
# ---------------------------------------------------------------------------------------------

_PAGE = 4096
_MEM_COMMIT, _MEM_RESERVE, _MEM_RELEASE = 0x1000, 0x2000, 0x8000
_PAGE_NOACCESS, _PAGE_READWRITE = 0x01, 0x04

_k32 = ctypes.WinDLL("kernel32", use_last_error=True)
_k32.VirtualAlloc.argtypes = [c_void_p, c_size_t, c_uint32, c_uint32]
_k32.VirtualAlloc.restype = c_void_p
_k32.VirtualProtect.argtypes = [c_void_p, c_size_t, c_uint32, POINTER(c_uint32)]
_k32.VirtualProtect.restype = c_int
_k32.VirtualFree.argtypes = [c_void_p, c_size_t, c_uint32]
_k32.VirtualFree.restype = c_int


class _GuardedBlob:
    """A copy of `data` whose last byte is the last byte before an inaccessible page: the catalog
    reading past `size` (Review Focus 1) crashes the test instead of reading neighbouring memory."""

    def __init__(self, data: bytes):
        pages = max(1, -(-len(data) // _PAGE))
        self._base = _k32.VirtualAlloc(None, (pages + 1) * _PAGE, _MEM_COMMIT | _MEM_RESERVE, _PAGE_READWRITE)
        if not self._base:
            raise ctypes.WinError(ctypes.get_last_error())
        if not _k32.VirtualProtect(self._base + pages * _PAGE, _PAGE, _PAGE_NOACCESS, byref(c_uint32())):
            raise ctypes.WinError(ctypes.get_last_error())
        self.address = self._base + pages * _PAGE - len(data)
        ctypes.memmove(self.address, data, len(data))

    def free(self) -> None:
        if self._base:
            _k32.VirtualFree(self._base, 0, _MEM_RELEASE)
            self._base = None


# ---------------------------------------------------------------------------------------------
# The binding tests use
# ---------------------------------------------------------------------------------------------

class Catalog:
    """An open fe_catalog_t together with the blob it points into (films' `lut` pointers stay valid
    until close)."""

    def __init__(self, engine: "Engine", handle: c_void_p, blob: _GuardedBlob):
        self._engine, self.handle, self._blob = engine, handle, blob

    def count(self) -> int:
        return self._engine.dll.fe_catalog_count(self.handle)

    def film(self, index: int) -> FeFilm:
        """The film structure inside the catalog (shared memory, not a copy)."""
        return self._engine.dll.fe_catalog_film(self.handle, index).contents

    def find(self, film_id: str) -> int:
        return self._engine.dll.fe_catalog_find(self.handle, film_id.encode("ascii"))

    def close(self) -> None:
        self._engine.dll.fe_catalog_close(self.handle)
        self._blob.free()


class Engine:
    OK, INVALID_ARG, NO_MEM, BAD_FILMS = range(4)
    impl = IMPL
    Film, Date = FeFilm, FeDate

    def __init__(self, dll_path: Path):
        dll = ctypes.CDLL(str(dll_path))
        dll.fe_catalog_open.argtypes = [c_void_p, c_size_t, POINTER(FeAllocator), POINTER(c_void_p)]
        dll.fe_catalog_open.restype = c_int
        dll.fe_catalog_count.argtypes = [c_void_p]
        dll.fe_catalog_count.restype = c_size_t
        dll.fe_catalog_film.argtypes = [c_void_p, c_size_t]
        dll.fe_catalog_film.restype = POINTER(FeFilm)
        dll.fe_catalog_find.argtypes = [c_void_p, c_char_p]
        dll.fe_catalog_find.restype = c_int
        dll.fe_catalog_close.argtypes = [c_void_p]
        dll.fe_catalog_close.restype = None
        dll.fe_develop.argtypes = [POINTER(FeDevelopParams), POINTER(FeImage), POINTER(FeAllocator)]
        dll.fe_develop.restype = c_int
        self.dll = dll
        # alloc and release are a matching pair because both are ucrtbase's. They are function pointers
        # made from its exports, so the engine calls the C functions directly, with no Python in between.
        ucrt = ctypes.CDLL("ucrtbase")
        self._allocator = FeAllocator(ALLOC_FN(("malloc", ucrt)), RELEASE_FN(("free", ucrt)))
        self._ucrt = ucrt

    @staticmethod
    def film_with(film: FeFilm, **fields) -> FeFilm:
        """A copy of `film` (its lut pointer shared) with the given fields replaced, e.g. grain_strength=0.0."""
        copy = FeFilm.from_buffer_copy(film)
        for name, value in fields.items():
            setattr(copy, name, value)
        return copy

    def open_catalog(self, data: bytes) -> tuple[int, Catalog | None]:
        """fe_catalog_open on a guarded copy of data. Returns (error, catalog); catalog is None on error."""
        blob = _GuardedBlob(data)
        handle = c_void_p()
        err = self.dll.fe_catalog_open(blob.address, len(data), byref(self._allocator), byref(handle))
        if err != self.OK:
            blob.free()
            return err, None
        return err, Catalog(self, handle, blob)

    def develop(self, rgb: np.ndarray, film: FeFilm | None = None, date: FeDate | None = None,
                seed: int = 0) -> np.ndarray:
        """fe_develop on a copy of rgb (H x W x C uint8, C = 3, or 4 with `new`); returns the developed copy."""
        img = np.array(rgb, dtype=np.uint8, order="C")
        height, width, channels = img.shape
        pixels = img.ctypes.data_as(POINTER(c_uint8))
        if IMPL == "legacy":
            assert channels == 3, "the legacy engine has RGB888 only"
            image = FeImage(pixels, width, height, width * 3)
        else:
            image = FeImage(pixels, width, height, width * channels, channels)
        params = FeDevelopParams(pointer(film) if film is not None else None,
                                 pointer(date) if date is not None else None, seed)
        err = self.dll.fe_develop(byref(params), byref(image), byref(self._allocator))
        assert err == self.OK, f"fe_develop returned {err}"
        return img


# ---------------------------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------------------------

@pytest.fixture(scope="session")
def fe() -> Engine:
    return Engine(build_dll())


@pytest.fixture(scope="session")
def films_blob() -> bytes:
    return FILMS_BIN.read_bytes()


@pytest.fixture(scope="session")
def reference_films() -> list[filmsbin.FilmEntry]:
    """The same file as Task 1's reader sees it: the oracle for what the C catalog must report."""
    return filmsbin.read_films_bin(FILMS_BIN)


@pytest.fixture(scope="session")
def catalog(fe: Engine, films_blob: bytes):
    err, cat = fe.open_catalog(films_blob)
    assert err == fe.OK
    yield cat
    cat.close()


@pytest.fixture(scope="session")
def identity_lut() -> np.ndarray:
    """An identity LUT in films.bin order (index (b*N + g)*N + r, RGB888): node k holds
    round(k * 255 / (N - 1)). A quantized identity is within one level of the identity, not exactly it,
    so tests compare a "unit film" against another unit film, not against the input."""
    n = filmsbin.LUT_SIZE
    ramp = np.rint(np.arange(n) * 255 / (n - 1)).astype(np.uint8)
    b, g, r = np.meshgrid(ramp, ramp, ramp, indexing="ij")
    return np.ascontiguousarray(np.stack([r, g, b], axis=-1))


@pytest.fixture(scope="session")
def unit_film(identity_lut: np.ndarray):
    """Factory for a "unit film": identity LUT, so the one effect under test is isolated.
    unit_film(grain=0..1, halation=0..1); grain size, halation radius and tint are fixed."""
    lut_pointer = identity_lut.ctypes.data_as(POINTER(c_uint8))  # identity_lut outlives the session

    def make(grain: float = 0.0, halation: float = 0.0) -> FeFilm:
        return FeFilm(id=b"unit", name=b"Unit", grain_strength=grain, grain_size=1.0,
                      halation_strength=halation, halation_radius=6,
                      halation_tint=(c_uint8 * 3)(255, 90, 40), lut_size=filmsbin.LUT_SIZE, lut=lut_pointer)

    return make
