"""
Load Block Reality blueprints (.brblp = GZIP NBT) into NumPy arrays.

Blueprint format (NBT v2):
  blocks[]: pos(x,y,z), blockState, rMaterial, structureId, isAnchored,
            stressLevel, isDynamic, dynRcomp/Rtens/Rshear/Density
  structures[]: id, compositeRcomp, compositeRtens, anchorPoints[]
  metadata: name, author, version, dimensions(x,y,z)
"""
from __future__ import annotations

import gzip
import json
from dataclasses import dataclass
from pathlib import Path

import numpy as np

# Material property table mirroring DefaultMaterial.java
MATERIAL_PROPS: dict[str, dict[str, float]] = {
    "plain_concrete": {"rcomp": 25.0, "rtens": 2.5, "density": 2400.0, "youngs": 30.0},
    "concrete":       {"rcomp": 30.0, "rtens": 3.0, "density": 2400.0, "youngs": 30.0},
    "rebar":          {"rcomp": 400.0, "rtens": 400.0, "density": 7850.0, "youngs": 200.0},
    "brick":          {"rcomp": 15.0, "rtens": 0.4, "density": 1800.0, "youngs": 15.0},
    "timber":         {"rcomp": 30.0, "rtens": 20.0, "density": 500.0, "youngs": 12.0},
    "steel":          {"rcomp": 250.0, "rtens": 400.0, "density": 7850.0, "youngs": 200.0},
    "stone":          {"rcomp": 50.0, "rtens": 5.0, "density": 2600.0, "youngs": 40.0},
    "glass":          {"rcomp": 100.0, "rtens": 40.0, "density": 2500.0, "youngs": 70.0},
    "sand":           {"rcomp": 0.1, "rtens": 0.0, "density": 1600.0, "youngs": 0.05},
    "obsidian":       {"rcomp": 200.0, "rtens": 50.0, "density": 2600.0, "youngs": 70.0},
}

GRAVITY = 9.81
BLOCK_VOLUME = 1.0


@dataclass
class BlueprintSample:
    """Single blueprint converted to volumetric arrays for training."""

    # Grid dimensions
    shape: tuple[int, int, int]  # (Lx, Ly, Lz)

    # Input fields (what the solver receives)
    source: np.ndarray       # float32[Lx,Ly,Lz] — self-weight
    conductivity: np.ndarray # float32[6,Lx,Ly,Lz] — 6-direction SoA
    voxel_type: np.ndarray   # uint8[Lx,Ly,Lz] — 0=air, 1=solid, 2=anchor
    rcomp: np.ndarray        # float32[Lx,Ly,Lz] — compression strength (MPa)
    rtens: np.ndarray        # float32[Lx,Ly,Lz] — tension strength (MPa)

    # Labels (solver output — if available from simulation)
    stress: np.ndarray | None       # float32[Lx,Ly,Lz] — stress utilization
    phi_steady: np.ndarray | None   # float32[Lx,Ly,Lz] — converged potential


class BlueprintLoader:
    """Load .brblp files and convert to volumetric training samples."""

    def __init__(self, blueprint_dir: str | Path):
        self.blueprint_dir = Path(blueprint_dir)

    def list_files(self) -> list[Path]:
        return sorted(self.blueprint_dir.glob("*.brblp"))

    def load(self, path: Path) -> BlueprintSample | None:
        """Load a single blueprint and voxelize it."""
        try:
            nbt = self._read_nbt(path)
        except Exception:
            return None

        blocks = nbt.get("blocks", [])
        if not blocks:
            return None

        # Compute AABB
        positions = [(b["x"], b["y"], b["z"]) for b in blocks]
        xs, ys, zs = zip(*positions)
        min_x, min_y, min_z = min(xs), min(ys), min(zs)
        max_x, max_y, max_z = max(xs), max(ys), max(zs)
        lx = max_x - min_x + 1
        ly = max_y - min_y + 1
        lz = max_z - min_z + 1

        # Allocate volumes
        source = np.zeros((lx, ly, lz), dtype=np.float32)
        conductivity = np.zeros((6, lx, ly, lz), dtype=np.float32)
        voxel_type = np.zeros((lx, ly, lz), dtype=np.uint8)
        rcomp = np.zeros((lx, ly, lz), dtype=np.float32)
        rtens = np.zeros((lx, ly, lz), dtype=np.float32)

        for b in blocks:
            ix = b["x"] - min_x
            iy = b["y"] - min_y
            iz = b["z"] - min_z

            mat_id = b.get("rMaterial", "stone")
            props = MATERIAL_PROPS.get(mat_id, MATERIAL_PROPS["stone"])

            # Dynamic material override
            if b.get("isDynamic", False):
                density = b.get("dynDensity", props["density"])
                rc = b.get("dynRcomp", props["rcomp"])
                rt = b.get("dynRtens", props.get("rtens", 0.0))
            else:
                density = props["density"]
                rc = props["rcomp"]
                rt = props.get("rtens", 0.0)

            fill = 1.0
            source[ix, iy, iz] = density * fill * GRAVITY * BLOCK_VOLUME
            voxel_type[ix, iy, iz] = 2 if b.get("isAnchored", False) else 1
            rcomp[ix, iy, iz] = rc
            rtens[ix, iy, iz] = rt

            # Isotropic conductivity from Young's modulus
            e_gpa = props["youngs"]
            cond_val = e_gpa * 1e3  # scaled conductivity
            for d in range(6):
                conductivity[d, ix, iy, iz] = cond_val

        # Stress labels (from blueprint if stored)
        stress = None
        if any("stressLevel" in b for b in blocks):
            stress = np.zeros((lx, ly, lz), dtype=np.float32)
            for b in blocks:
                if "stressLevel" in b:
                    ix = b["x"] - min_x
                    iy = b["y"] - min_y
                    iz = b["z"] - min_z
                    stress[ix, iy, iz] = b["stressLevel"]

        return BlueprintSample(
            shape=(lx, ly, lz),
            source=source,
            conductivity=conductivity,
            voxel_type=voxel_type,
            rcomp=rcomp,
            rtens=rtens,
            stress=stress,
            phi_steady=None,
        )

    @staticmethod
    def _read_nbt(path: Path) -> dict:
        """Read GZIP-compressed NBT as a dict.

        Uses nbtlib if available, falls back to a minimal parser.
        """
        try:
            import nbtlib
            with gzip.open(path, "rb") as f:
                nbt_file = nbtlib.File.parse(f)
            return _nbt_to_dict(nbt_file)
        except ImportError:
            # Fallback: read as raw JSON export (for synthetic data)
            with gzip.open(path, "rt", encoding="utf-8") as f:
                return json.load(f)


def _nbt_to_dict(tag) -> dict | list | int | float | str:
    """Recursively convert nbtlib tags to plain Python types."""
    import nbtlib
    if isinstance(tag, nbtlib.Compound):
        return {k: _nbt_to_dict(v) for k, v in tag.items()}
    if isinstance(tag, nbtlib.List):
        return [_nbt_to_dict(v) for v in tag]
    if isinstance(tag, (nbtlib.Int, nbtlib.Short, nbtlib.Byte, nbtlib.Long)):
        return int(tag)
    if isinstance(tag, (nbtlib.Float, nbtlib.Double)):
        return float(tag)
    if isinstance(tag, nbtlib.String):
        return str(tag)
    return tag
