"""Building Training Machine — turn downloaded schematics into FEM-labelled datasets.

Pipeline:
  1. Load .litematic / .nbt / .mcstructure files
  2. Map block IDs -> materials -> physics fields
  3. Crop / pad to fixed grid_size
  4. Solve FEM + PFSF via AsyncBuffer (cached)
  5. Export trainable JAX tensors
"""
from __future__ import annotations

import hashlib
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

import numpy as np

from brnext.data.schematic_loader import SchematicLoader
from brnext.data.block_material_map import guess_material, MATERIAL_PROPS
from brnext.pipeline.structure_gen import VoxelStructure
from brnext.pipeline.async_data_loader import AsyncBuffer, compute_sample_id


def _schematic_fem_worker(args):
    """Picklable worker that accepts a VoxelStructure directly and solves FEM+PFSF."""
    struct = args[0]
    from brnext.pipeline.async_data_loader import fem_worker
    return fem_worker(struct)


@dataclass
class BuildingMachineConfig:
    schematic_dir: str | Path = "schematics"
    output_dir: str | Path = "building_datasets"
    grid_size: int = 12
    n_crops_per_building: int = 4  # random crops for large buildings
    min_solid_ratio: float = 0.05  # skip nearly-empty crops
    n_workers: int = 10
    use_cache: bool = True
    cache_dir: str | Path = "building_datasets/cache"
    seed: int = 42


class BuildingTrainingMachine:
    """End-to-end schematic -> training sample machine."""

    def __init__(self, cfg: BuildingMachineConfig,
                 on_log: Callable[[str], None] | None = None):
        self.cfg = cfg
        self.on_log = on_log or print
        self.loader = SchematicLoader(cfg.schematic_dir)
        self.output_dir = Path(cfg.output_dir)
        self.output_dir.mkdir(parents=True, exist_ok=True)
        self.cache_dir = Path(cfg.cache_dir)
        self.cache_dir.mkdir(parents=True, exist_ok=True)

        # Cache infra (same pattern as cmfd_trainer)
        self.registry = None
        self.zarr_store = None
        self.config_hash = hashlib.sha256(
            repr((cfg.grid_size, cfg.seed, "building_machine")).encode()
        ).hexdigest()[:16]
        if cfg.use_cache:
            try:
                from brnext.data import DatasetRegistry, ZarrDatasetStore
                self.registry = DatasetRegistry(self.cache_dir / "dataset_registry.duckdb")
                self.zarr_store = ZarrDatasetStore(self.cache_dir / "zarr_store")
            except Exception as e:
                self._log(f"  Cache init failed ({e}), proceeding without cache.")
                self.registry = None
                self.zarr_store = None

        self.rng = np.random.default_rng(cfg.seed)

    def _log(self, msg: str):
        self.on_log(msg)

    # ═══════════════════════════════════════════════════════════════════════════
    # Schematic -> VoxelStructure conversion
    # ═══════════════════════════════════════════════════════════════════════════

    def _schematic_to_voxel(self, schematic) -> VoxelStructure | None:
        """Convert raw SchematicData into a full-size VoxelStructure."""
        if not schematic.blocks:
            return None

        xs = [b["x"] for b in schematic.blocks]
        ys = [b["y"] for b in schematic.blocks]
        zs = [b["z"] for b in schematic.blocks]
        min_x, min_y, min_z = min(xs), min(ys), min(zs)
        max_x, max_y, max_z = max(xs), max(ys), max(zs)
        lx = max_x - min_x + 1
        ly = max_y - min_y + 1
        lz = max_z - min_z + 1

        occ = np.zeros((lx, ly, lz), dtype=bool)
        anchors = np.zeros((lx, ly, lz), dtype=bool)
        E = np.zeros((lx, ly, lz), dtype=np.float64)
        nu = np.zeros((lx, ly, lz), dtype=np.float64)
        rho = np.zeros((lx, ly, lz), dtype=np.float64)
        rc = np.zeros((lx, ly, lz), dtype=np.float64)
        rt = np.zeros((lx, ly, lz), dtype=np.float64)
        mat_ids = np.zeros((lx, ly, lz), dtype=np.int32)

        # Build material name -> idx mapping on the fly
        mat_name_to_idx: dict[str, int] = {}
        mat_names: list[str] = []

        for b in schematic.blocks:
            ix = b["x"] - min_x
            iy = b["y"] - min_y
            iz = b["z"] - min_z
            mat = guess_material(b["block"])
            if mat is None:
                continue
            props = MATERIAL_PROPS.get(mat, MATERIAL_PROPS["stone"])

            if mat not in mat_name_to_idx:
                mat_name_to_idx[mat] = len(mat_names)
                mat_names.append(mat)
            midx = mat_name_to_idx[mat]

            occ[ix, iy, iz] = True
            mat_ids[ix, iy, iz] = midx
            E[ix, iy, iz] = props["E_pa"]
            nu[ix, iy, iz] = props["nu"]
            rho[ix, iy, iz] = props["density"]
            rc[ix, iy, iz] = props["rcomp"]
            rt[ix, iy, iz] = props["rtens"]

        if not occ.any():
            return None

        # Anchor heuristic: bottom layer (global min_y) touching ground
        anchors[:, 0, :] = occ[:, 0, :]
        if not anchors.any():
            anchors[0, 0, 0] = True
            occ[0, 0, 0] = True

        return VoxelStructure(
            occupancy=occ, anchors=anchors,
            E_field=E, nu_field=nu,
            density_field=rho, rcomp_field=rc,
            rtens_field=rt, mat_ids=mat_ids,
            style=schematic.name,
        )

    def _crop_or_pad(self, struct: VoxelStructure,
                     crop_origin: tuple[int, int, int] | None = None) -> VoxelStructure | None:
        """Extract or pad a VoxelStructure to exactly grid_size³."""
        L = self.cfg.grid_size
        src = struct
        sx, sy, sz = src.occupancy.shape

        # Allocate target
        occ = np.zeros((L, L, L), dtype=bool)
        anchors = np.zeros((L, L, L), dtype=bool)
        E = np.zeros((L, L, L), dtype=np.float64)
        nu = np.zeros((L, L, L), dtype=np.float64)
        rho = np.zeros((L, L, L), dtype=np.float64)
        rc = np.zeros((L, L, L), dtype=np.float64)
        rt = np.zeros((L, L, L), dtype=np.float64)
        mat_ids = np.zeros((L, L, L), dtype=np.int32)

        if crop_origin is None:
            # Center crop
            ox = max(0, (sx - L) // 2)
            oy = max(0, (sy - L) // 2)
            oz = max(0, (sz - L) // 2)
        else:
            ox, oy, oz = crop_origin

        # Source slice bounds
        src_x0 = max(0, ox)
        src_y0 = max(0, oy)
        src_z0 = max(0, oz)
        src_x1 = min(sx, ox + L)
        src_y1 = min(sy, oy + L)
        src_z1 = min(sz, oz + L)

        # Target slice bounds
        tgt_x0 = max(0, -ox)
        tgt_y0 = max(0, -oy)
        tgt_z0 = max(0, -oz)
        tgt_x1 = tgt_x0 + (src_x1 - src_x0)
        tgt_y1 = tgt_y0 + (src_y1 - src_y0)
        tgt_z1 = tgt_z0 + (src_z1 - src_z0)

        if src_x1 <= src_x0 or src_y1 <= src_y0 or src_z1 <= src_z0:
            return None

        occ[tgt_x0:tgt_x1, tgt_y0:tgt_y1, tgt_z0:tgt_z1] = src.occupancy[src_x0:src_x1, src_y0:src_y1, src_z0:src_z1]
        anchors[tgt_x0:tgt_x1, tgt_y0:tgt_y1, tgt_z0:tgt_z1] = src.anchors[src_x0:src_x1, src_y0:src_y1, src_z0:src_z1]
        E[tgt_x0:tgt_x1, tgt_y0:tgt_y1, tgt_z0:tgt_z1] = src.E_field[src_x0:src_x1, src_y0:src_y1, src_z0:src_z1]
        nu[tgt_x0:tgt_x1, tgt_y0:tgt_y1, tgt_z0:tgt_z1] = src.nu_field[src_x0:src_x1, src_y0:src_y1, src_z0:src_z1]
        rho[tgt_x0:tgt_x1, tgt_y0:tgt_y1, tgt_z0:tgt_z1] = src.density_field[src_x0:src_x1, src_y0:src_y1, src_z0:src_z1]
        rc[tgt_x0:tgt_x1, tgt_y0:tgt_y1, tgt_z0:tgt_z1] = src.rcomp_field[src_x0:src_x1, src_y0:src_y1, src_z0:src_z1]
        rt[tgt_x0:tgt_x1, tgt_y0:tgt_y1, tgt_z0:tgt_z1] = src.rtens_field[src_x0:src_x1, src_y0:src_y1, src_z0:src_z1]
        mat_ids[tgt_x0:tgt_x1, tgt_y0:tgt_y1, tgt_z0:tgt_z1] = src.mat_ids[src_x0:src_x1, src_y0:src_y1, src_z0:src_z1]

        # Re-anchor: any solid block at the bottom layer of the *target* volume
        if not anchors.any():
            anchors[:, 0, :] = occ[:, 0, :]
        if not anchors.any():
            anchors[0, 0, 0] = True
            occ[0, 0, 0] = True

        fill_ratio = occ.sum() / (L ** 3)
        if fill_ratio < self.cfg.min_solid_ratio:
            return None

        return VoxelStructure(
            occupancy=occ, anchors=anchors,
            E_field=E, nu_field=nu,
            density_field=rho, rcomp_field=rc,
            rtens_field=rt, mat_ids=mat_ids,
            style=src.style,
        )

    def _slice_structures(self, struct: VoxelStructure) -> list[VoxelStructure]:
        """Generate one or more grid_size³ structures from a raw schematic."""
        sx, sy, sz = struct.occupancy.shape
        L = self.cfg.grid_size
        results = []

        if sx <= L and sy <= L and sz <= L:
            # Small building -> center pad
            cropped = self._crop_or_pad(struct, crop_origin=None)
            if cropped is not None:
                results.append(cropped)
            return results

        # Large building -> center crop + random crops + sliding window
        # 1) Center crop
        center = self._crop_or_pad(struct, crop_origin=None)
        if center is not None:
            results.append(center)

        # 2) Random crops
        for _ in range(self.cfg.n_crops_per_building):
            ox = self.rng.integers(0, max(1, sx - L + 1))
            oy = self.rng.integers(0, max(1, sy - L + 1))
            oz = self.rng.integers(0, max(1, sz - L + 1))
            c = self._crop_or_pad(struct, crop_origin=(int(ox), int(oy), int(oz)))
            if c is not None:
                # Deduplicate by occupancy hash
                h = hashlib.sha256(c.occupancy.tobytes()).hexdigest()[:16]
                if not any(hashlib.sha256(r.occupancy.tobytes()).hexdigest()[:16] == h for r in results):
                    results.append(c)

        return results

    # ═══════════════════════════════════════════════════════════════════════════
    # FEM / PFSF solving via AsyncBuffer
    # ═══════════════════════════════════════════════════════════════════════════

    def _generate_dataset(self, structures: list[VoxelStructure]) -> list:
        """Run FEM+PFSF on all structures and return training items [(struct, fem, phi), ...]."""
        if not structures:
            return []

        self._log(f"  Solving FEM/PFSF for {len(structures)} structures...")
        gen = ((s,) for s in structures)
        dataset = []
        with AsyncBuffer(
            gen, _schematic_fem_worker, n_workers=self.cfg.n_workers, chunksize=2,
            registry=self.registry, zarr_store=self.zarr_store,
            config_hash=self.config_hash, grid_size=self.cfg.grid_size,
            target_samples=len(structures),
        ) as buf:
            buf.prefetch(min_buffer=min(20, len(structures)))
            while len(dataset) < len(structures):
                buf.poll(max_size=len(structures))
                if len(buf) == 0:
                    if buf._exhausted:
                        break
                    buf.prefetch(min_buffer=1, timeout=5.0)
                    if len(buf) == 0:
                        break
                for _ in range(len(buf)):
                    if len(dataset) >= len(structures):
                        break
                    item = buf.sample(self.rng, n=1)[0]
                    dataset.append(item)

        self._log(f"  Dataset built: {len(dataset)} / {len(structures)} solved")
        return dataset

    # ═══════════════════════════════════════════════════════════════════════════
    # Public run method
    # ═══════════════════════════════════════════════════════════════════════════

    def run(self) -> list:
        """Execute the full pipeline and return training samples."""
        self._log("═══ Building Training Machine ═══")
        files = self.loader.list_files()
        self._log(f"  Found {len(files)} schematic files")
        if not files:
            return []

        all_structs = []
        for path in files:
            schematic = self.loader.load(path)
            if schematic is None:
                continue
            raw_struct = self._schematic_to_voxel(schematic)
            if raw_struct is None:
                continue
            slices = self._slice_structures(raw_struct)
            self._log(f"    {path.name}: {len(slices)} crops from {raw_struct.occupancy.shape}")
            all_structs.extend(slices)

        self._log(f"  Total structures to solve: {len(all_structs)}")
        dataset = self._generate_dataset(all_structs)
        self._log(f"═══ Pipeline complete: {len(dataset)} training samples ═══")
        return dataset

    def export_npz(self, dataset: list, filename: str = "building_dataset.npz") -> Path:
        """Export solved dataset to NPZ for inspection or manual training."""
        out_path = self.output_dir / filename
        xs, ts, ms = [], [], []
        for item in dataset:
            if len(item) != 3:
                continue
            struct, fem, phi = item
            # Build input tensor [L, L, L, 6]
            occ = struct.occupancy.astype(np.float32)
            E_norm = struct.E_field.astype(np.float32) / 1e11  # rough norm
            nu = struct.nu_field.astype(np.float32)
            rho_norm = struct.density_field.astype(np.float32) / 8000.0
            rc_norm = struct.rcomp_field.astype(np.float32) / 500.0
            rt_norm = struct.rtens_field.astype(np.float32) / 500.0
            x = np.stack([occ, E_norm, nu, rho_norm, rc_norm, rt_norm], axis=-1)
            xs.append(x)

            # Target tensor [L, L, L, 10]
            t = np.concatenate([
                fem.stress.astype(np.float32),
                fem.displacement.astype(np.float32),
                np.array(phi)[..., None].astype(np.float32),
            ], axis=-1)
            ts.append(t)
            ms.append(occ)

        np.savez(
            str(out_path),
            xs=np.stack(xs),
            ts=np.stack(ts),
            ms=np.stack(ms),
        )
        self._log(f"  Exported NPZ: {out_path}")
        return out_path
