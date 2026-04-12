"""
Post-process FEM stress to remove numerical artifacts at sharp corners.

Uses two strategies:
  1. Global Winsorization based on p99 * clip_factor
  2. Local neighbor averaging for voxels with >5 exposed faces (sharp corners)
"""
from __future__ import annotations

import numpy as np


def _neighbor_average(field: np.ndarray, mask: np.ndarray) -> np.ndarray:
    """Compute mean of 26 neighbors for each voxel, ignoring air."""
    acc = np.zeros_like(field)
    denom = np.zeros_like(field)
    for dx in (-1, 0, 1):
        for dy in (-1, 0, 1):
            for dz in (-1, 0, 1):
                if dx == 0 and dy == 0 and dz == 0:
                    continue
                shifted = np.roll(field, (dx, dy, dz), axis=(0, 1, 2))
                shifted_mask = np.roll(mask, (dx, dy, dz), axis=(0, 1, 2))
                acc += shifted * shifted_mask
                denom += shifted_mask
    return acc / (denom + 1e-8)


def filter_corner_stress(
    stress_field: np.ndarray,
    vm_field: np.ndarray,
    occupancy: np.ndarray,
    clip_factor: float = 3.0,
    corner_exposure_threshold: int = 5,
) -> tuple[np.ndarray, np.ndarray]:
    """Remove corner artifacts from FEM stress output.

    Args:
        stress_field: [Lx, Ly, Lz, 6] float32 Voigt stress
        vm_field: [Lx, Ly, Lz] float32 von Mises
        occupancy: bool[Lx, Ly, Lz]
        clip_factor: Winsorize at p99 * clip_factor
        corner_exposure_threshold: voxels with more exposed faces get neighbor-smoothed

    Returns:
        filtered_stress, filtered_vm
    """
    occ = occupancy.astype(bool)
    if not occ.any():
        return stress_field.copy(), vm_field.copy()

    filtered_stress = stress_field.copy()
    filtered_vm = vm_field.copy()

    # ── 1. Global Winsorization per channel ──
    n_ch = stress_field.shape[-1]
    for c in range(n_ch):
        vals = stress_field[..., c][occ]
        p99 = float(np.percentile(np.abs(vals), 99))
        if p99 < 1e-8:
            continue
        thr = p99 * clip_factor
        outlier = occ & (np.abs(stress_field[..., c]) > thr)
        filtered_stress[..., c] = np.where(
            outlier, np.clip(stress_field[..., c], -thr, thr), stress_field[..., c]
        )

    # ── 1b. Global Winsorization for von Mises ──
    vm_vals = vm_field[occ]
    vm_p99 = float(np.percentile(np.abs(vm_vals), 99))
    if vm_p99 >= 1e-8:
        vm_thr = vm_p99 * clip_factor
        vm_outlier = occ & (vm_field > vm_thr)
        filtered_vm = np.where(
            vm_outlier, np.clip(vm_field, 0.0, vm_thr), vm_field
        )

    # ── 2. Sharp-corner smoothing ──
    # Count exposed faces (26-neighbor air count)
    exposure = np.zeros(occ.shape, dtype=np.int32)
    for ax in range(3):
        for d in (-1, 1):
            shifted = np.roll(occ, d, axis=ax)
            exposure += (occ & ~shifted).astype(np.int32)

    corner_mask = occ & (exposure > corner_exposure_threshold)
    if corner_mask.any():
        for c in range(n_ch):
            smooth = _neighbor_average(filtered_stress[..., c], occ)
            filtered_stress[..., c] = np.where(
                corner_mask, smooth, filtered_stress[..., c]
            )
        smooth_vm = _neighbor_average(filtered_vm, occ)
        filtered_vm = np.where(corner_mask, smooth_vm, filtered_vm)

    return filtered_stress.astype(np.float32), filtered_vm.astype(np.float32)
