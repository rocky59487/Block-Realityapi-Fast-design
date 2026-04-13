"""OOD benchmark for BR-NeXT SSGO robustness evaluation.

Measures generalization gap between in-distribution (ID) and hold-out (OOD) geometries.
"""
from __future__ import annotations

import json
import time
from pathlib import Path
from dataclasses import dataclass, asdict
from typing import Callable

import numpy as np
import jax
import jax.numpy as jnp

from brnext.pipeline.structure_gen import generate_structure
from brnext.teachers.fem_teacher import solve_fem
from brnext.teachers.pfsf_teacher import solve_pfsf_phi


@dataclass
class BenchmarkResult:
    style: str
    n_samples: int
    mae_stress: float
    mae_disp: float
    mae_phi: float
    max_err_stress: float
    max_err_disp: float
    max_err_phi: float
    nan_rate: float
    failure_accuracy: float
    safety_gap: float


# In-distribution vs OOD split
ID_STYLES = ["tower", "bridge", "cantilever", "arch"]
OOD_STYLES = ["spiral", "tree", "cave", "overhang"]
ALL_STYLES = ID_STYLES + OOD_STYLES


def build_input(struct) -> jnp.ndarray:
    """Convert VoxelStructure to model input [L,L,L,7]."""
    occ = jnp.array(struct.occupancy, dtype=jnp.float32)
    E = jnp.array(struct.E_field, dtype=jnp.float32) / 200e9
    nu = jnp.array(struct.nu_field, dtype=jnp.float32)
    rho = jnp.array(struct.density_field, dtype=jnp.float32) / 7850.0
    rc = jnp.array(struct.rcomp_field, dtype=jnp.float32) / 250.0
    rt = jnp.array(struct.rtens_field, dtype=jnp.float32) / 500.0
    anchor = jnp.array(struct.anchors, dtype=jnp.float32)
    return jnp.stack([occ, E, nu, rho, rc, rt, anchor], axis=-1)


def generate_ground_truth(struct):
    """Return (stress, disp, phi) or None if FEM fails or contains outliers."""
    fem = solve_fem(
        struct.occupancy, struct.anchors,
        struct.E_field, struct.nu_field, struct.density_field,
    )
    if not fem.converged:
        return None
    mask = struct.occupancy.astype(bool)
    grid_size = struct.occupancy.shape[0]
    max_disp = grid_size * 0.5
    if (np.abs(fem.displacement[mask]) > max_disp).any() or (np.abs(fem.stress[mask]) > 1e9).any():
        return None
    phi = solve_pfsf_phi(struct.occupancy, struct.E_field, struct.density_field)
    return fem.stress, fem.displacement, phi


def evaluate_model_on_styles(
    model_apply: Callable,
    params: dict,
    styles: list[str],
    grid_size: int = 8,
    n_samples: int = 50,
    rng_seed: int = 2024,
) -> list[BenchmarkResult]:
    """Evaluate a trained SSGO model on a list of styles."""
    rng = np.random.default_rng(rng_seed)
    results = []

    for style in styles:
        errors_stress, errors_disp, errors_phi = [], [], []
        failure_accs, safety_gaps = [], []
        nan_count = 0
        valid_count = 0

        for _ in range(n_samples):
            struct = generate_structure(grid_size, rng, style)
            gt = generate_ground_truth(struct)
            if gt is None:
                continue
            stress_gt, disp_gt, phi_gt = gt
            valid_count += 1

            x = build_input(struct)[None, ...]  # [1, L, L, L, 6]
            pred = model_apply(params, x)  # [1, L, L, L, 10]
            pred = np.array(pred[0])

            if not np.isfinite(pred).all():
                nan_count += 1
                continue

            mask = struct.occupancy.astype(bool)
            n_mask = max(int(mask.sum()), 1)

            pred_stress = pred[..., :6]
            pred_disp = pred[..., 6:9]
            pred_phi = pred[..., 9]

            # Normalize by 99th percentile for fair comparison
            s_scale = float(np.percentile(np.abs(stress_gt[mask]), 99)) + 1e-8
            d_scale = float(np.percentile(np.abs(disp_gt[mask]), 99)) + 1e-8
            p_scale = float(np.percentile(np.abs(phi_gt[mask]), 99)) + 1e-8
            es = np.abs(pred_stress - stress_gt)[mask].mean() / s_scale
            ed = np.abs(pred_disp - disp_gt)[mask].mean() / d_scale
            ep = np.abs(pred_phi - phi_gt)[mask].mean() / p_scale

            errors_stress.append(float(es))
            errors_disp.append(float(ed))
            errors_phi.append(float(ep))

            # ── Failure accuracy & safety gap (sigmaMax-normalized) ──
            Emax = float(struct.E_field.max()) + 1e-8
            phi_norm_gt = phi_gt / Emax
            phi_norm_pred = pred_phi / Emax
            rcomp_norm = struct.rcomp_field / Emax
            # Failure = phi exceeds compressive strength
            failure_gt = phi_norm_gt > rcomp_norm
            failure_pred = phi_norm_pred > rcomp_norm
            failure_accs.append(float(np.mean(failure_gt[mask] == failure_pred[mask])))
            # Safety gap: for actually safe voxels, how wrong is the margin?
            safe_gt = (~failure_gt) & mask
            if safe_gt.any() and rcomp_norm[safe_gt].min() > 0:
                margin_gt = (rcomp_norm[safe_gt] - phi_norm_gt[safe_gt]) / (rcomp_norm[safe_gt] + 1e-8)
                margin_pred = (rcomp_norm[safe_gt] - phi_norm_pred[safe_gt]) / (rcomp_norm[safe_gt] + 1e-8)
                safety_gaps.append(float(np.abs(margin_gt - margin_pred).mean()))
            else:
                safety_gaps.append(0.0)

        if valid_count == 0 or len(errors_stress) == 0:
            result = BenchmarkResult(style=style, n_samples=0, mae_stress=0.0, mae_disp=0.0, mae_phi=0.0, max_err_stress=0.0, max_err_disp=0.0, max_err_phi=0.0, nan_rate=0.0, failure_accuracy=0.0, safety_gap=0.0)
        else:
            result = BenchmarkResult(
                style=style,
                n_samples=valid_count,
                mae_stress=float(np.mean(errors_stress)),
                mae_disp=float(np.mean(errors_disp)),
                mae_phi=float(np.mean(errors_phi)),
                max_err_stress=float(np.max(errors_stress)),
                max_err_disp=float(np.max(errors_disp)),
                max_err_phi=float(np.max(errors_phi)),
                nan_rate=nan_count / valid_count,
                failure_accuracy=float(np.mean(failure_accs)),
                safety_gap=float(np.mean(safety_gaps)),
            )
        results.append(result)
        print(f"  {style:12s}: stress={result.mae_stress:.4f} disp={result.mae_disp:.4f} phi={result.mae_phi:.4f} fail_acc={result.failure_accuracy:.2%} safe_gap={result.safety_gap:.4f} nan={result.nan_rate:.1%}")

    return results


def run_ood_benchmark(
    model_apply: Callable,
    params: dict,
    grid_size: int = 8,
    n_samples: int = 50,
    output_dir: str = "brnext_output",
) -> dict:
    """Run full ID + OOD benchmark and save results."""
    print("\n═══ OOD Benchmark ═══")
    print(f"Grid={grid_size}, Samples per style={n_samples}")

    t0 = time.time()
    print("\n[ID styles]")
    id_results = evaluate_model_on_styles(model_apply, params, ID_STYLES, grid_size, n_samples)
    print("\n[OOD styles]")
    ood_results = evaluate_model_on_styles(model_apply, params, OOD_STYLES, grid_size, n_samples)

    all_results = id_results + ood_results

    id_mae_phi = np.mean([r.mae_phi for r in id_results])
    ood_mae_phi = np.mean([r.mae_phi for r in ood_results])
    gap = (ood_mae_phi - id_mae_phi) / (id_mae_phi + 1e-8)

    id_fail_acc = np.mean([r.failure_accuracy for r in id_results])
    ood_fail_acc = np.mean([r.failure_accuracy for r in ood_results])
    id_safe_gap = np.mean([r.safety_gap for r in id_results])
    ood_safe_gap = np.mean([r.safety_gap for r in ood_results])

    summary = {
        "grid_size": grid_size,
        "n_samples": n_samples,
        "id_mae_phi": float(id_mae_phi),
        "ood_mae_phi": float(ood_mae_phi),
        "ood_gap_relative": float(gap),
        "id_failure_accuracy": float(id_fail_acc),
        "ood_failure_accuracy": float(ood_fail_acc),
        "id_safety_gap": float(id_safe_gap),
        "ood_safety_gap": float(ood_safe_gap),
        "results": [asdict(r) for r in all_results],
        "elapsed_sec": time.time() - t0,
    }

    out_path = Path(output_dir)
    out_path.mkdir(exist_ok=True)
    json_path = out_path / "ood_benchmark.json"
    with open(json_path, "w") as f:
        json.dump(summary, f, indent=2)
    print(f"\nSaved to {json_path}")
    print(f"ID  MAE(phi)={id_mae_phi:.4f}  OOD MAE(phi)={ood_mae_phi:.4f}  Gap={gap:.1%}")
    print(f"ID  FailAcc={id_fail_acc:.2%}  OOD FailAcc={ood_fail_acc:.2%}")
    print(f"ID  SafetyGap={id_safe_gap:.4f}  OOD SafetyGap={ood_safe_gap:.4f}")

    return summary


if __name__ == "__main__":
    # Quick sanity check with a random-init model
    from brnext.models.ssgo import SSGO

    model = SSGO(hidden=24, modes=4, n_global_layers=2, n_focal_layers=1, n_backbone_layers=1, moe_hidden=16)
    rng = jax.random.PRNGKey(0)
    dummy = jnp.zeros((1, 8, 8, 8, 7))
    params = model.init(rng, dummy)["params"]

    @jax.jit
    def apply_fn(p, x):
        return model.apply({"params": p}, x)

    summary = run_ood_benchmark(apply_fn, params, grid_size=8, n_samples=10)
