"""Synthesizes the four feedback sounds into app/src/main/res/raw/*.wav.

Deterministic: fixed parameters and a per-sound seed derived from SEED, so a re-run gives byte-identical files.
Loudness lives in the generators; the app scales them by the volume setting.
Usage: tools/.venv/Scripts/python tools/gen_sounds.py
"""
import wave
from pathlib import Path

import numpy as np

SAMPLE_RATE = 44100
MAX_SECONDS = 0.6
SEED = 20261106
OUT_DIR = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "res" / "raw"


def click(rng: np.random.Generator, seconds: float, decay: float, low_hz: float, high_hz: float) -> np.ndarray:
    """A band-limited, exponentially decaying noise burst with a 1 ms attack."""
    n = int(seconds * SAMPLE_RATE)
    spectrum = np.fft.rfft(rng.standard_normal(n))
    freqs = np.fft.rfftfreq(n, 1 / SAMPLE_RATE)
    spectrum[(freqs < low_hz) | (freqs > high_hz)] = 0
    burst = np.fft.irfft(spectrum, n)
    t = np.arange(n) / SAMPLE_RATE
    burst *= np.exp(-t / decay) * np.minimum(1.0, t / 0.001)
    return burst / np.max(np.abs(burst))


def place(length_s: float, events: list[tuple[float, np.ndarray, float]]) -> np.ndarray:
    """Mixes (start second, burst, gain) events into a buffer of length_s seconds."""
    out = np.zeros(int(length_s * SAMPLE_RATE))
    for start, burst, gain in events:
        i = int(start * SAMPLE_RATE)
        out[i:i + len(burst)] += gain * burst[: len(out) - i]
    return out


def notch(rng: np.random.Generator) -> np.ndarray:
    """A light, dry detent click."""
    return place(0.05, [(0.0, click(rng, 0.05, 0.007, 1500, 6000), 0.35)])


def shutter(rng: np.random.Generator) -> np.ndarray:
    """Two-stage mechanical shutter: a sharp open, then a duller close."""
    return place(0.22, [
        (0.0, click(rng, 0.05, 0.008, 1200, 7000), 0.8),
        (0.09, click(rng, 0.12, 0.025, 400, 3500), 0.9),
    ])


def advance(rng: np.random.Generator) -> np.ndarray:
    """Film-advance ratchet: eight uneven clicks."""
    events = [(0.04 * i, click(rng, 0.03, 0.006, 900, 5000), 0.35 + 0.15 * rng.random()) for i in range(8)]
    events.append((0.33, click(rng, 0.08, 0.02, 300, 2500), 0.5))
    return place(0.42, events)


def rewind(rng: np.random.Generator) -> np.ndarray:
    """Rewind crank: a faster, higher ratchet that ends in a soft clunk."""
    events = [(0.032 * i, click(rng, 0.025, 0.005, 1500, 7000), 0.25 + 0.1 * rng.random()) for i in range(14)]
    events.append((0.46, click(rng, 0.09, 0.025, 300, 2500), 0.5))
    return place(0.56, events)


# name -> generator; the position is the per-sound seed stream, so editing one sound never changes another.
SOUNDS = {"notch": notch, "shutter": shutter, "advance": advance, "rewind": rewind}


def write_wav(path: Path, samples: np.ndarray) -> None:
    """16-bit mono WAV at SAMPLE_RATE; samples must be within [-1, 1] and at most MAX_SECONDS long."""
    assert np.max(np.abs(samples)) <= 1.0, path
    assert len(samples) <= MAX_SECONDS * SAMPLE_RATE, path
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SAMPLE_RATE)
        w.writeframes(np.round(samples * 32767).astype("<i2").tobytes())


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    for k, (name, generate) in enumerate(SOUNDS.items()):
        write_wav(OUT_DIR / f"{name}.wav", generate(np.random.default_rng([SEED, k])))


if __name__ == "__main__":
    main()
