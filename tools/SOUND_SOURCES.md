# Sound sources

(This file is not in `res/raw/` because Android rejects non-lowercase file names there.)

`app/src/main/res/raw/notch.wav`, `shutter.wav`, `advance.wav` and `rewind.wav` are original works, synthesized by
`tools/gen_sounds.py` (filtered noise bursts; fixed seed, so re-running gives byte-identical files).
They are released under GPL-3.0 with the rest of the project.

They can be replaced by recordings or CC0 samples. Keep the file names, 16-bit mono WAV, each at most 0.6 s,
and set the loudness in the file itself: the app plays every sound at volume 1.0.
