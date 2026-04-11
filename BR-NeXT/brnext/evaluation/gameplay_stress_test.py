"""Gameplay stress test: simulate real player behavior to validate model robustness.

Tests:
  1. Random build phase (unreasonable geometries)
  2. Random destruction phase (5-30% blocks removed)
  3. Dynamic modification (sequential add/remove)
  4. Material mismatch (extreme soft/hard islands)
"""
from __future__ import annotations

import json
import time
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Callable

import numpy as np
import jax.numpy as jnp

from brnext.pipeline.structure_gen import generate_structure, VoxelStructure
from brnext.teachers.pfsf_teacher import solve_pfsf_phi


@dataclass
class StressTestResult:
    n_rounds: int
    pass_rate: float
    nan_rate: float
    mae_phi: float
    max_err_phi: float
    dynamic_jump_rate: float
    extreme_error_rate: float
    details: list


def _random_build_structure(L: int, rng: np.random.Generator) -> VoxelStructure:
    """Simulate a player randomly placing blocks, including floating ones."""
    occ = np.zeros((L, L, L), dtype=bool)
    # Always ground a few blocks
    occ[:, 0, :] = True
    # Randomly place blocks with weak connectivity constraints
    n_placed = int(rng.integers(L, L * 3))
    for _ in range(n_placed):
        x, y, z = [int(rng.integers(0, L)) for _ in range(3)]
        occ[x, y, z] = True
    # Occasionally create a platform
    if rng.random() < 0.3:
        py = int(rng.integers(2, L - 2))
        occ[:, py, :] = occ[:, py, :] | (rng.random((L, L)) < 0.3)
    anchors = np.copy(occ[:, 0, :])
    anchors = np.broadcast_to(anchors[:, None, :], (L, L, L)).copy()
    return _fill_materials(occ, anchors, rng)


def _fill_materials(occ: np.ndarray, anchors: np.ndarray, rng: np.random.Generator) -> VoxelStructure:
    """Assign sensible default materials to a binary occupancy."""
    E = np.where(occ, 30e9, 0.0).astype(np.float64)
    nu = np.where(occ, 0.20, 0.0).astype(np.float64)
    rho = np.where(occ, 2400.0, 0.0).astype(np.float64)
    rc = np.where(occ, 30.0, 0.0).astype(np.float64)
    rt = np.where(occ, 3.0, 0.0).astype(np.float64)
    mat_ids = np.where(occ, 1, 0).astype(np.int32)
    return VoxelStructure(
        occupancy=occ, anchors=anchors,
        E_field=E, nu_field=nu, density_field=rho,
        rcomp_field=rc, rtens_field=rt, mat_ids=mat_ids,
        style="random_build",
    )


def _destroy_random(struct: VoxelStructure, rng: np.random.Generator, ratio: float = 0.2) -> VoxelStructure:
    """Remove a fraction of non-anchor blocks."""
    occ = struct.occupancy.copy()
    removable = occ & ~struct.anchors
    idx = np.argwhere(removable)
    if len(idx) == 0:
        return struct
    n_remove = max(1, int(len(idx) * ratio))
    remove_idx = rng.choice(len(idx), size=n_remove, replace=False)
    for ri in remove_idx:
        occ[tuple(idx[ri])] = False
    out = _fill_materials(occ, struct.anchors.copy(), rng)
    out.style = "destroyed"
    return out


def _material_mismatch(struct: VoxelStructure, rng: np.random.Generator) -> VoxelStructure:
    """Embed an island of extremely hard or soft material."""
    out = VoxelStructure(
        occupancy=struct.occupancy.copy(),
        anchors=struct.anchors.copy(),
        E_field=struct.E_field.copy(),
        nu_field=struct.nu_field.copy(),
        density_field=struct.density_field.copy(),
        rcomp_field=struct.rcomp_field.copy(),
        rtens_field=struct.rtens_field.copy(),
        mat_ids=struct.mat_ids.copy(),
        style="material_mismatch",
    )
    occ = out.occupancy
    solid_idx = np.argwhere(occ)
    if len(solid_idx) < 3:
        return out
    n_islands = int(rng.integers(1, 3))
    for _ in range(n_islands):
        center = solid_idx[rng.integers(len(solid_idx))]
        r = int(rng.integers(1, 3))
        cx, cy, cz = center
        for x in range(max(0, cx - r), min(occ.shape[0], cx + r + 1)):
            for y in range(max(0, cy - r), min(occ.shape[1], cy + r + 1)):
                for z in range(max(0, cz - r), min(occ.shape[2], cz + r + 1)):
                    if occ[x, y, z]:
                        if rng.random() < 0.5:
                            # Ultra-hard (bedrock-like)
                            out.E_field[x, y, z] = 200e9
                            out.density_field[x, y, z] = 3000.0
                            out.rcomp_field[x, y, z] = 1e6
                        else:
                            # Ultra-soft (sand-like)
                            out.E_field[x, y, z] = 0.01e9
                            out.density_field[x, y, z] = 1600.0
                            out.rcomp_field[x, y, z] = 0.1
    return out


def _build_input(struct: VoxelStructure) -> jnp.ndarray:
    occ = jnp.array(struct.occupancy, dtype=jnp.float32)
    E = jnp.array(struct.E_field, dtype=jnp.float32) / 200e9
    nu = jnp.array(struct.nu_field, dtype=jnp.float32)
    rho = jnp.array(struct.density_field, dtype=jnp.float32) / 7850.0
    rc = jnp.array(struct.rcomp_field, dtype=jnp.float32) / 250.0
    rt = jnp.array(struct.rtens_field, dtype=jnp.float32) / 500.0
    anchor = jnp.array(struct.anchors, dtype=jnp.float32)
    return jnp.stack([occ, E, nu, rho, rc, rt, anchor], axis=-1)


def run_gameplay_stress_test(
    model_apply: Callable,
    params: dict,
    grid_size: int = 8,
    n_rounds: int = 1000,
    rng_seed: int = 2024,
    output_dir: str = "brnext_output",
) -> StressTestResult:
    """Run the full gameplay stress test suite."""
    rng = np.random.default_rng(rng_seed)
    print(f"\n═══ Gameplay Stress Test ({n_rounds} rounds) ═══")

    nan_count = 0
    valid_count = 0
    phi_errors = []
    dynamic_jumps = 0
    dynamic_total = 0
    extreme_errors = 0
    details = []

    t0 = time.time()
    for round_idx in range(n_rounds):
        # Phase 1: Random build
        struct = _random_build_structure(grid_size, rng)
        if not struct.occupancy.any():
            continue

        # Phase 2: Material mismatch (50% of rounds)
        if rng.random() < 0.5:
            struct = _material_mismatch(struct, rng)

        # Phase 3: Random destruction (50% of rounds)
        if rng.random() < 0.5:
            struct = _destroy_random(struct, rng, ratio=rng.uniform(0.05, 0.30))

        # Phase 4: Dynamic modification sequence
        prev_phi = None
        seq_jump = False
        for step in range(10):
            dynamic_total += 1
            if rng.random() < 0.5 and struct.occupancy.sum() > 1:
                struct = _destroy_random(struct, rng, ratio=0.05)
            else:
                # Add a random block
                cx, cy, cz = [int(rng.integers(0, grid_size)) for _ in range(3)]
                struct.occupancy[cx, cy, cz] = True
                struct = _fill_materials(struct.occupancy, struct.anchors, rng)

            x = _build_input(struct)[None, ...]
            pred = np.array(model_apply(params, x)[0])
            if not np.isfinite(pred).all():
                nan_count += 1
                continue
            valid_count += 1

            pred_phi = pred[..., 9]
            gt_phi = solve_pfsf_phi(struct.occupancy, struct.E_field, struct.density_field)
            if not np.isfinite(gt_phi).all():
                continue
            err = np.abs(pred_phi - gt_phi)[struct.occupancy].mean()
            phi_errors.append(float(err))

            # Dynamic continuity check
            flat_phi = pred_phi[struct.occupancy].mean()
            if prev_phi is not None and prev_phi > 1e-8:
                rel_change = abs(flat_phi - prev_phi) / prev_phi
                if rel_change > 0.30:
                    seq_jump = True
            prev_phi = flat_phi

        if seq_jump:
            dynamic_jumps += 1

        # Track extreme errors (>3 sigma of running mean)
        if phi_errors:
            recent = phi_errors[-min(100, len(phi_errors)):]
            mu, sigma = np.mean(recent), np.std(recent) + 1e-8
            if recent[-1] > mu + 3 * sigma:
                extreme_errors += 1

        if (round_idx + 1) % 100 == 0:
            print(f"  [{round_idx+1}/{n_rounds}] nan={nan_count} mae_phi={np.mean(phi_errors[-100:]):.4f} jumps={dynamic_jumps}")

    elapsed = time.time() - t0
    nan_rate = nan_count / max(dynamic_total, 1)
    mae_phi = float(np.mean(phi_errors)) if phi_errors else 0.0
    max_err_phi = float(np.max(phi_errors)) if phi_errors else 0.0
    jump_rate = dynamic_jumps / max(n_rounds, 1)
    extreme_rate = extreme_errors / max(len(phi_errors), 1)

    # Pass criteria
    passed = (
        nan_rate == 0.0 and
        mae_phi < 0.15 and
        jump_rate < 0.30 and
        extreme_rate < 0.02
    )

    result = StressTestResult(
        n_rounds=n_rounds,
        pass_rate=1.0 if passed else 0.0,
        nan_rate=nan_rate,
        mae_phi=mae_phi,
        max_err_phi=max_err_phi,
        dynamic_jump_rate=jump_rate,
        extreme_error_rate=extreme_rate,
        details=details,
    )

    out_path = Path(output_dir) / "stress_test.json"
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with open(out_path, "w") as f:
        json.dump(asdict(result), f, indent=2)
    print(f"\nSaved to {out_path}")
    print(f"nan_rate={nan_rate:.3%} mae_phi={mae_phi:.4f} jump_rate={jump_rate:.1%} extreme={extreme_rate:.2%} PASSED={passed} ({elapsed:.0f}s)")

    return result


if __name__ == "__main__":
    from brnext.models.ssgo import SSGO
    import jax

    model = SSGO(hidden=24, modes=4, n_global_layers=2, n_focal_layers=1, n_backbone_layers=1, moe_hidden=16)
    rng = jax.random.PRNGKey(0)
    dummy = jnp.zeros((1, 8, 8, 8, 6))
    params = model.init(rng, dummy, train=False)["params"]

    import jax
    @jax.jit
    def apply_fn(p, x):
        return model.apply({"params": p}, x, train=False)

    run_gameplay_stress_test(apply_fn, params, grid_size=8, n_rounds=50)
