"""Zarr storage backend for FEM/PFSF cached samples."""
from __future__ import annotations

from pathlib import Path
from typing import Any

import numpy as np


class ZarrDatasetStore:
    """Store voxel structures + FEM results + phi in a Zarr hierarchy."""

    def __init__(self, store_path: str | Path):
        self.store_path = Path(store_path)
        self.store_path.parent.mkdir(parents=True, exist_ok=True)
        self._root = None

    def _root_group(self):
        import zarr
        if self._root is None:
            self._root = zarr.group(store=str(self.store_path), overwrite=False)
        return self._root

    def _ds_group(self, config_hash: str, grid_size: int):
        name = f"ds_{config_hash}_g{grid_size}"
        root = self._root_group()
        if name not in root:
            root.create_group(name, overwrite=False)
        return root[name]

    def _sample_group(self, config_hash: str, grid_size: int, sample_id: str):
        ds = self._ds_group(config_hash, grid_size)
        if sample_id not in ds:
            ds.create_group(sample_id, overwrite=False)
        return ds[sample_id]

    def write_sample(
        self,
        config_hash: str,
        grid_size: int,
        sample_id: str,
        struct: Any,
        fem: Any | None,
        phi: np.ndarray,
    ) -> str:
        """Write a solved sample to Zarr. Returns the internal zarr path."""
        g = self._sample_group(config_hash, grid_size, sample_id)

        # VoxelStructure fields
        for key in [
            "occupancy",
            "anchors",
            "E_field",
            "nu_field",
            "density_field",
            "rcomp_field",
            "rtens_field",
            "mat_ids",
        ]:
            arr = getattr(struct, key)
            if key in g:
                del g[key]
            g.create_dataset(key, shape=arr.shape, data=arr, chunks=arr.shape)
        g.attrs["style"] = getattr(struct, "style", "unknown")

        # FEM result
        if fem is not None:
            for key in ["stress", "displacement", "von_mises"]:
                arr = getattr(fem, key)
                dkey = f"fem_{key}"
                if dkey in g:
                    del g[dkey]
                g.create_dataset(dkey, shape=arr.shape, data=arr, chunks=arr.shape)
            g.attrs["fem_converged"] = bool(fem.converged)
            g.attrs["fem_iterations"] = int(fem.iterations)
            g.attrs["fem_residual"] = float(fem.residual)
        else:
            g.attrs["fem_converged"] = None

        # PFSF phi
        if "phi" in g:
            del g["phi"]
        g.create_dataset("phi", shape=phi.shape, data=phi, chunks=phi.shape)

        return f"{config_hash}/{grid_size}/{sample_id}"

    def read_sample(
        self, config_hash: str, grid_size: int, sample_id: str
    ) -> tuple[Any, Any | None, np.ndarray] | None:
        """Read a sample from Zarr and reconstruct dataclasses."""
        from brnext.pipeline.structure_gen import VoxelStructure
        from brnext.fem import FEMResult

        ds = self._ds_group(config_hash, grid_size)
        if sample_id not in ds:
            return None
        g = ds[sample_id]

        # Reconstruct VoxelStructure
        struct = VoxelStructure(
            occupancy=np.array(g["occupancy"]),
            anchors=np.array(g["anchors"]),
            E_field=np.array(g["E_field"]),
            nu_field=np.array(g["nu_field"]),
            density_field=np.array(g["density_field"]),
            rcomp_field=np.array(g["rcomp_field"]),
            rtens_field=np.array(g["rtens_field"]),
            mat_ids=np.array(g["mat_ids"]),
            style=g.attrs.get("style", "unknown"),
        )

        # Reconstruct FEM result if present
        fem = None
        if "fem_stress" in g:
            fem = FEMResult(
                shape=tuple(struct.occupancy.shape),
                displacement=np.array(g["fem_displacement"]),
                stress=np.array(g["fem_stress"]),
                von_mises=np.array(g["fem_von_mises"]),
                converged=g.attrs.get("fem_converged", False),
                iterations=g.attrs.get("fem_iterations", 0),
                residual=g.attrs.get("fem_residual", 0.0),
            )

        phi = np.array(g["phi"])
        return struct, fem, phi

    def has_sample(self, config_hash: str, grid_size: int, sample_id: str) -> bool:
        ds = self._ds_group(config_hash, grid_size)
        return sample_id in ds
