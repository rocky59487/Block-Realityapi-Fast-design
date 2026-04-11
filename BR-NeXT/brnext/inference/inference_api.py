"""Clean inference API for trained SSGO models.

Example:
    from brnext.inference.inference_api import SSGOPredictor
    predictor = SSGOPredictor.from_checkpoint("brnext_output_m1_final/ssgo_robust_medium.msgpack")
    result = predictor.predict(voxel_structure)
"""
from __future__ import annotations

from pathlib import Path
from dataclasses import dataclass

import numpy as np
import jax
import jax.numpy as jnp
from flax import serialization

from brnext.models.ssgo import SSGO
from brnext.pipeline.structure_gen import VoxelStructure


@dataclass
class SSGOPrediction:
    """Structured prediction result."""
    stress: np.ndarray       # [L, L, L, 6] Voigt (Pa)
    displacement: np.ndarray # [L, L, L, 3] (m)
    phi: np.ndarray          # [L, L, L] PFSF-compatible
    uncertainty: np.ndarray | None = None  # [L, L, L] log-var if enabled


def build_input(struct: VoxelStructure) -> jnp.ndarray:
    """Convert VoxelStructure to model input [L,L,L,7]."""
    occ = jnp.array(struct.occupancy, dtype=jnp.float32)
    E = jnp.array(struct.E_field, dtype=jnp.float32) / 200e9
    nu = jnp.array(struct.nu_field, dtype=jnp.float32)
    rho = jnp.array(struct.density_field, dtype=jnp.float32) / 7850.0
    rc = jnp.array(struct.rcomp_field, dtype=jnp.float32) / 250.0
    rt = jnp.array(struct.rtens_field, dtype=jnp.float32) / 500.0
    anchor = jnp.array(struct.anchors, dtype=jnp.float32)
    return jnp.stack([occ, E, nu, rho, rc, rt, anchor], axis=-1)


class SSGOPredictor:
    """High-level predictor wrapping a trained SSGO."""

    def __init__(self, model: SSGO, params, grid_size: int = 8):
        self.model = model
        self.params = params
        self.grid_size = grid_size
        self._apply = jax.jit(lambda p, x: model.apply({"params": p}, x, train=False))

    @classmethod
    def from_checkpoint(cls, checkpoint_path: str,
                        grid_size: int = 8,
                        hidden: int = 32,
                        modes: int = 6,
                        dropout_rate: float = 0.0) -> "SSGOPredictor":
        """Load predictor from a msgpack checkpoint."""
        path = Path(checkpoint_path)
        if not path.exists():
            raise FileNotFoundError(f"Checkpoint not found: {path}")

        model = SSGO(
            hidden=hidden,
            modes=modes,
            n_global_layers=3,
            n_focal_layers=2,
            n_backbone_layers=2,
            moe_hidden=32,
            dropout_rate=dropout_rate,
        )
        with open(path, "rb") as f:
            params = serialization.from_bytes(None, f.read())
        return cls(model, params, grid_size=grid_size)

    def predict(self, struct: VoxelStructure) -> SSGOPrediction:
        """Run inference on a single VoxelStructure."""
        x = build_input(struct)
        if x.shape[:3] != (self.grid_size, self.grid_size, self.grid_size):
            raise ValueError(
                f"Input grid size {x.shape[:3]} does not match model grid size {self.grid_size}"
            )
        pred = np.array(self._apply(self.params, x[None, ...])[0])
        # Convert log-displacement back to physical meters
        import jax.numpy as jnp
        disp_log = jnp.array(pred[..., 6:9])
        disp_phy = jnp.sign(disp_log) * (jnp.expm1(jnp.abs(disp_log)) * 1e-6)
        out = SSGOPrediction(
            stress=pred[..., :6],
            displacement=np.array(disp_phy),
            phi=pred[..., 9],
        )
        if pred.shape[-1] > 10:
            out.uncertainty = pred[..., 10]
        return out

    def predict_batch(self, structs: list[VoxelStructure]) -> list[SSGOPrediction]:
        """Run batched inference on multiple structures."""
        xs = jnp.stack([build_input(s) for s in structs])
        preds = np.array(self._apply(self.params, xs))
        results = []
        import jax.numpy as jnp
        for pred in preds:
            disp_log = jnp.array(pred[..., 6:9])
            disp_phy = jnp.sign(disp_log) * (jnp.expm1(jnp.abs(disp_log)) * 1e-6)
            out = SSGOPrediction(
                stress=pred[..., :6],
                displacement=np.array(disp_phy),
                phi=pred[..., 9],
            )
            if pred.shape[-1] > 10:
                out.uncertainty = pred[..., 10]
            results.append(out)
        return results


def predict_from_checkpoint(checkpoint_path: str, struct: VoxelStructure,
                             grid_size: int = 8, hidden: int = 32, modes: int = 6) -> SSGOPrediction:
    """One-shot prediction from checkpoint."""
    predictor = SSGOPredictor.from_checkpoint(checkpoint_path, grid_size=grid_size,
                                               hidden=hidden, modes=modes)
    return predictor.predict(struct)
