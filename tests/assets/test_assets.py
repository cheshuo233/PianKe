"""Asset consistency (spec 11.2): films.toml, films.json, films.bin and the label images agree."""
from __future__ import annotations

import hashlib
import json
import tomllib
from pathlib import Path

import filmsbin

ROOT = Path(__file__).resolve().parents[2]
TOML = ROOT / "films" / "films.toml"
ASSETS = ROOT / "app" / "src" / "main" / "assets"


def _json() -> dict:
    return json.loads((ASSETS / "films.json").read_text(encoding="utf-8"))


def test_json_hash_matches_toml():
    assert _json()["toml_sha256"] == hashlib.sha256(TOML.read_bytes()).hexdigest()


def test_toml_json_bin_agree():
    toml = tomllib.loads(TOML.read_text(encoding="utf-8"))["film"]
    listed = _json()["films"]
    binned = filmsbin.read_films_bin(ASSETS / "films.bin")
    assert [f["id"] for f in listed] == [t["id"] for t in toml] == [e.id for e in binned]
    for f, t in zip(listed, toml):
        assert (f["name"], f["iso"], f["process"]) == (t["display"], t["iso"], t["process"]), f["id"]


def test_every_film_has_a_label():
    for film in _json()["films"]:
        assert (ASSETS / "labels" / f"{film['id']}.png").is_file(), film["id"]
