#!/usr/bin/env python3
"""v0.4 M3e — procedurally emit the canonical PFSF schema-v1 fixtures.

Produces 20 deterministic JSON files under
``Block Reality/api/src/test/resources/pfsf-fixtures/`` that cover the
shape variety plan.md calls out (cantilever, arch, column, beam, …)
plus 10 small smoke fixtures for sub-millisecond parity runs.

The generator is the source of truth — regenerate with
``python3 scripts/generate_canonical_fixtures.py``. Mixing hand-edited
fixtures and generator output will drift silently, so every file carries
an explicit ``generated_by`` tag.

Schema matches ``L1-native/libpfsf/src/example/fixture_loader.h``:
``materials.voxels`` is base64 little-endian int32[N], registry lists
compacted material entries keyed by ``id`` starting at 1 (0 = air).
"""
from __future__ import annotations

import argparse
import base64
import json
import struct
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable


REPO_ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = REPO_ROOT / "Block Reality" / "api" / "src" / "test" / "resources" / "pfsf-fixtures"


# Registry entry tuple. The fields mirror FixtureMaterialEntry.
@dataclass(frozen=True)
class Mat:
    id: int
    name: str
    rcomp: float
    rtens: float
    density: float = 2400.0
    youngs_gpa: float = 30.0
    poisson: float = 0.2
    gc: float = 100.0
    is_anchor: bool = False

    def as_dict(self) -> dict:
        return {
            "id": self.id, "name": self.name,
            "rcomp": self.rcomp, "rtens": self.rtens,
            "density": self.density, "youngs_gpa": self.youngs_gpa,
            "poisson": self.poisson, "gc": self.gc,
            "is_anchor": self.is_anchor,
        }


CONCRETE = Mat(1, "plain_concrete", 30.0, 3.0, 2400.0, 30.0)
CONCRETE_ANCHOR = Mat(2, "plain_concrete_anchor",
                      30.0, 3.0, 2400.0, 30.0, is_anchor=True)
REBAR = Mat(3, "rebar", 500.0, 500.0, 7850.0, 200.0, poisson=0.3)
STEEL_ANCHOR = Mat(4, "steel_anchor", 500.0, 500.0, 7850.0, 200.0,
                   poisson=0.3, is_anchor=True)


@dataclass
class FixtureSpec:
    name: str
    lx: int
    ly: int
    lz: int
    description: str
    # populate(voxels, anchors) — voxels is flat int[lx*ly*lz] init to 0,
    # anchors is [(x,y,z), ...] to append.
    populate: Callable[[list[int], list[tuple[int, int, int]], int, int, int], None]
    registry: list[Mat] = field(default_factory=lambda: [CONCRETE, CONCRETE_ANCHOR])
    wind: tuple[float, float, float] = (0.0, 0.0, 0.0)
    ticks: int = 100


def encode_voxels_b64(voxels: list[int]) -> str:
    """Little-endian int32 → base64."""
    buf = struct.pack(f"<{len(voxels)}i", *voxels)
    return base64.b64encode(buf).decode("ascii")


def idx3(x: int, y: int, z: int, lx: int, ly: int) -> int:
    return (z * ly + y) * lx + x


def emit(spec: FixtureSpec, out_dir: Path) -> Path:
    n = spec.lx * spec.ly * spec.lz
    voxels = [0] * n
    anchors: list[tuple[int, int, int]] = []
    spec.populate(voxels, anchors, spec.lx, spec.ly, spec.lz)

    body = {
        "schema_version": 1,
        "fixture_id": spec.name,
        "description": spec.description,
        "recorded_at": "2026-04-19T00:00:00Z",
        "git_sha": "",
        "generated_by": "scripts/generate_canonical_fixtures.py",
        "dims": {"lx": spec.lx, "ly": spec.ly, "lz": spec.lz},
        "anchors": [list(a) for a in anchors],
        "materials": {
            "voxels": encode_voxels_b64(voxels),
            "registry": [m.as_dict() for m in spec.registry],
        },
        "wind": list(spec.wind),
        "ticks": spec.ticks,
    }
    out_path = out_dir / f"{spec.name}.json"
    with out_path.open("w", encoding="utf-8") as f:
        # ensure_ascii=False: the pfsf_cli JSON parser deliberately lacks
        # \uXXXX escape support (simple auditable subset). Raw UTF-8 bytes
        # pass through unchanged on both sides.
        json.dump(body, f, indent=2, sort_keys=False, ensure_ascii=False)
        f.write("\n")
    return out_path


# ───────────────────── populators ─────────────────────

def cantilever(voxels, anchors, lx, ly, lz):
    """Full solid block, bottom row anchored at y=0."""
    for z in range(lz):
        for y in range(ly):
            for x in range(lx):
                voxels[idx3(x, y, z, lx, ly)] = 1
    for z in range(lz):
        for x in range(lx):
            voxels[idx3(x, 0, z, lx, ly)] = 2
            anchors.append((x, 0, z))


def simple_beam(voxels, anchors, lx, ly, lz):
    """Solid beam, anchors at both z ends of the bottom row."""
    for z in range(lz):
        for y in range(ly):
            for x in range(lx):
                voxels[idx3(x, y, z, lx, ly)] = 1
    for x in range(lx):
        voxels[idx3(x, 0, 0, lx, ly)] = 2
        voxels[idx3(x, 0, lz - 1, lx, ly)] = 2
        anchors.append((x, 0, 0))
        anchors.append((x, 0, lz - 1))


def arch(voxels, anchors, lx, ly, lz):
    """Half-disc arch in the xy plane, depth lz."""
    cx = (lx - 1) / 2.0
    r_out = cx
    r_in = max(0.0, cx - 1.5)
    for z in range(lz):
        for y in range(ly):
            for x in range(lx):
                dx = x - cx
                dy = y
                r = (dx * dx + dy * dy) ** 0.5
                if r_in <= r <= r_out and y >= 0:
                    voxels[idx3(x, y, z, lx, ly)] = 1
    # bases at y=0 left & right
    for z in range(lz):
        voxels[idx3(0, 0, z, lx, ly)] = 2
        voxels[idx3(lx - 1, 0, z, lx, ly)] = 2
        anchors.append((0, 0, z))
        anchors.append((lx - 1, 0, z))


def column(voxels, anchors, lx, ly, lz):
    """Filled column; y=0 footing anchored."""
    for z in range(lz):
        for y in range(ly):
            for x in range(lx):
                voxels[idx3(x, y, z, lx, ly)] = 1
    for z in range(lz):
        for x in range(lx):
            voxels[idx3(x, 0, z, lx, ly)] = 2
            anchors.append((x, 0, z))


def wall(voxels, anchors, lx, ly, lz):
    """Vertical wall (lz=1 slab), footing at y=0."""
    for z in range(lz):
        for y in range(ly):
            for x in range(lx):
                voxels[idx3(x, y, z, lx, ly)] = 1
    for z in range(lz):
        for x in range(lx):
            voxels[idx3(x, 0, z, lx, ly)] = 2
            anchors.append((x, 0, z))


def l_bracket(voxels, anchors, lx, ly, lz):
    """L-shape in xy: vertical column + horizontal ledge."""
    for z in range(lz):
        # vertical limb at x=0
        for y in range(ly):
            voxels[idx3(0, y, z, lx, ly)] = 1
        # horizontal limb along y=0
        for x in range(lx):
            voxels[idx3(x, 0, z, lx, ly)] = 1
    # anchor the vertical limb root
    for z in range(lz):
        voxels[idx3(0, 0, z, lx, ly)] = 2
        anchors.append((0, 0, z))


def t_joint(voxels, anchors, lx, ly, lz):
    """T shape in xy, two feet anchored."""
    cx = lx // 2
    for z in range(lz):
        # vertical stem
        for y in range(ly):
            voxels[idx3(cx, y, z, lx, ly)] = 1
        # horizontal cross at y = ly-1
        for x in range(lx):
            voxels[idx3(x, ly - 1, z, lx, ly)] = 1
    # anchor the stem base
    for z in range(lz):
        voxels[idx3(cx, 0, z, lx, ly)] = 2
        anchors.append((cx, 0, z))


def frame_box(voxels, anchors, lx, ly, lz):
    """Hollow cube frame — 12 edges only."""
    def on_edge(x, y, z):
        on_x = (x == 0 or x == lx - 1)
        on_y = (y == 0 or y == ly - 1)
        on_z = (z == 0 or z == lz - 1)
        return (int(on_x) + int(on_y) + int(on_z)) >= 2

    for z in range(lz):
        for y in range(ly):
            for x in range(lx):
                if on_edge(x, y, z):
                    voxels[idx3(x, y, z, lx, ly)] = 1
    # anchor 4 bottom corners
    for (x, z) in [(0, 0), (lx - 1, 0), (0, lz - 1), (lx - 1, lz - 1)]:
        voxels[idx3(x, 0, z, lx, ly)] = 2
        anchors.append((x, 0, z))


def slab(voxels, anchors, lx, ly, lz):
    """Horizontal slab (ly=1), 4 corners anchored."""
    for z in range(lz):
        for y in range(ly):
            for x in range(lx):
                voxels[idx3(x, y, z, lx, ly)] = 1
    for (x, z) in [(0, 0), (lx - 1, 0), (0, lz - 1), (lx - 1, lz - 1)]:
        voxels[idx3(x, 0, z, lx, ly)] = 2
        anchors.append((x, 0, z))


def solid_smoke(voxels, anchors, lx, ly, lz):
    """Minimal — fill + anchor y=0 row."""
    for z in range(lz):
        for y in range(ly):
            for x in range(lx):
                voxels[idx3(x, y, z, lx, ly)] = 1
    for z in range(lz):
        for x in range(lx):
            voxels[idx3(x, 0, z, lx, ly)] = 2
            anchors.append((x, 0, z))


# ───────────────────── fixture roster ─────────────────────

FIXTURES: list[FixtureSpec] = [
    FixtureSpec("cantilever_5x20x1",   5, 20, 1, "5x20x1 cantilever anchored at y=0.", cantilever, ticks=500),
    FixtureSpec("cantilever_3x10x1",   3, 10, 1, "3x10x1 cantilever.", cantilever),
    FixtureSpec("simple_beam_7x3x10",  7,  3, 10, "7x3x10 simply-supported beam.", simple_beam, ticks=300),
    FixtureSpec("arch_9x9x1",          9,  9, 1, "Semi-circular arch, 9x9 cross-section.", arch, ticks=300),
    FixtureSpec("arch_7x7x3",          7,  7, 3, "Arch extruded along +z (3 deep).", arch, ticks=200),
    FixtureSpec("column_3x12x3",       3, 12, 3, "3x12x3 axial column.", column),
    FixtureSpec("wall_10x5x1",        10,  5, 1, "Wall 10x5x1 with footing.", wall),
    FixtureSpec("l_bracket_5x5x5",     5,  5, 5, "L-bracket, vertical + horizontal limbs.", l_bracket),
    FixtureSpec("t_joint_7x5x1",       7,  5, 1, "T-joint, single stem anchor.", t_joint),
    FixtureSpec("frame_box_5x5x5",     5,  5, 5, "Hollow cube frame, 4 bottom corners anchored.", frame_box),
    FixtureSpec("slab_10x1x10",       10,  1, 10, "Horizontal slab, 4 corner anchors.", slab),

    # Smoke — minimal fixtures for sub-millisecond parity runs.
    FixtureSpec("smoke_2x2x1",         2, 2, 1, "Smoke — 4-cell wall.", solid_smoke, ticks=20),
    FixtureSpec("smoke_2x2x2",         2, 2, 2, "Smoke — 8-cell cube.", solid_smoke, ticks=20),
    FixtureSpec("smoke_3x2x1",         3, 2, 1, "Smoke — 3x2x1 strip.", solid_smoke, ticks=20),
    FixtureSpec("smoke_3x3x1",         3, 3, 1, "Smoke — 3x3x1 panel.", solid_smoke, ticks=20),
    FixtureSpec("smoke_3x3x3",         3, 3, 3, "Smoke — 3x3x3 cube.", solid_smoke, ticks=20),
    FixtureSpec("smoke_4x2x1",         4, 2, 1, "Smoke — 4x2x1 short cantilever.", cantilever, ticks=40),
    FixtureSpec("smoke_4x4x1",         4, 4, 1, "Smoke — 4x4x1 panel.", solid_smoke, ticks=30),
    FixtureSpec("smoke_2x4x2",         2, 4, 2, "Smoke — tall 2x4x2 column.", column, ticks=40),
    FixtureSpec("smoke_5x1x1",         5, 1, 1, "Smoke — 5x1x1 beam.", solid_smoke, ticks=20),
]

assert len(FIXTURES) == 20, f"plan specifies 20 fixtures, have {len(FIXTURES)}"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out-dir", default=str(OUT_DIR))
    ap.add_argument("--only", help="Generate only the named fixture (debug).")
    args = ap.parse_args()
    out = Path(args.out_dir)
    out.mkdir(parents=True, exist_ok=True)
    for spec in FIXTURES:
        if args.only and spec.name != args.only:
            continue
        p = emit(spec, out)
        print(f"wrote {p}")


if __name__ == "__main__":
    main()
