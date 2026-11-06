"""Synthesizes the four feedback sounds into app/src/main/res/raw/*.wav.

Deterministic: fixed parameters and a fixed seed, so a re-run gives byte-identical files.
Usage: tools/.venv/Scripts/python tools/gen_sounds.py
"""
from pathlib import Path

import numpy as np

SAMPLE_RATE = 44100
MAX_SECONDS = 0.6
SEED = 20261106
OUT_DIR = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "res" / "raw"


def click(rng: np.random.Generator, seconds: float, decay: float, cutoff_hz: float) -> np.ndarray:
    """A filtered, exponentially decaying noise burst."""
    raise NotImplementedError


def notch(rng: np.random.Generator) -> np.ndarray:
    raise NotImplementedError


def shutter(rng: np.random.Generator) -> np.ndarray:
    raise NotImplementedError


def advance(rng: np.random.Generator) -> np.ndarray:
    raise NotImplementedError


def rewind(rng: np.random.Generator) -> np.ndarray:
    raise NotImplementedError


def write_wav(path: Path, samples: np.ndarray) -> None:
    """16-bit mono WAV at SAMPLE_RATE; peak-normalizes, asserts the length is within MAX_SECONDS."""
    raise NotImplementedError


def main() -> None:
    raise NotImplementedError


if __name__ == "__main__":
    main()
