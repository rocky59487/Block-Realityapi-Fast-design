"""Enhanced structure generator for BR-NeXT.

Builds on brml.pipeline.auto_train.generate_structure with:
  - Variable grid sizing within max_L
  - Richer material assignment (clusters instead of pure random)
  - Active-selection hooks (uncertainty placeholders)
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np

MATERIALS = {
    "plain_concrete": {"E_pa": 25e9,  "nu": 0.18, "density": 2400.0, "rcomp": 25.0,  "rtens": 2.5},
    "rebar":          {"E_pa": 200e9, "nu": 0.29, "density": 7850.0, "rcomp": 250.0, "rtens": 400.0},
    "concrete":       {"E_pa": 30e9,  "nu": 0.20, "density": 2350.0, "rcomp": 30.0,  "rtens": 3.0},
    "rc_node":        {"E_pa": 32e9,  "nu": 0.20, "density": 2500.0, "rcomp": 33.0,  "rtens": 5.9},
    "brick":          {"E_pa": 5e9,   "nu": 0.15, "density": 1800.0, "rcomp": 10.0,  "rtens": 0.5},
    "timber":         {"E_pa": 11e9,  "nu": 0.35, "density": 600.0,  "rcomp": 5.0,   "rtens": 8.0},
    "steel":          {"E_pa": 200e9, "nu": 0.29, "density": 7850.0, "rcomp": 350.0, "rtens": 500.0},
    "stone":          {"E_pa": 50e9,  "nu": 0.25, "density": 2400.0, "rcomp": 30.0,  "rtens": 3.0},
    "glass":          {"E_pa": 70e9,  "nu": 0.22, "density": 2500.0, "rcomp": 100.0, "rtens": 30.0},
    "sand":           {"E_pa": 0.01e9,"nu": 0.30, "density": 1600.0, "rcomp": 0.1,   "rtens": 0.0},
    "obsidian":       {"E_pa": 70e9,  "nu": 0.20, "density": 2600.0, "rcomp": 200.0, "rtens": 5.0},
    "bedrock":        {"E_pa": 1e12,  "nu": 0.10, "density": 3000.0, "rcomp": 1e6,   "rtens": 1e6},
}
MAT_NAMES = list(MATERIALS.keys())


@dataclass
class VoxelStructure:
    occupancy: np.ndarray
    anchors: np.ndarray
    E_field: np.ndarray
    nu_field: np.ndarray
    density_field: np.ndarray
    rcomp_field: np.ndarray
    rtens_field: np.ndarray
    mat_ids: np.ndarray
    style: str


def generate_structure(L: int, rng: np.random.Generator,
                       style: str = "random",
                       material_cluster: bool = True) -> VoxelStructure:
    """Generate a random voxel structure."""
    occ = np.zeros((L, L, L), dtype=bool)
    anchors = np.zeros((L, L, L), dtype=bool)

    if style == "tower":
        occ[:, 0, :] = True
        anchors[:, 0, :] = True
        for y in range(1, L):
            fill = max(0.2, 1.0 - y / L * 0.8)
            occ[:, y, :] = rng.random((L, L)) < fill
            for x in range(L):
                for z in range(L):
                    if occ[x, y, z] and not occ[x, y - 1, z]:
                        has_support = False
                        for dx, dz in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
                            nx, nz = x + dx, z + dz
                            if 0 <= nx < L and 0 <= nz < L and occ[nx, y - 1, nz]:
                                has_support = True
                                break
                        if not has_support:
                            occ[x, y, z] = False

    elif style == "bridge":
        w = max(2, L // 4)
        occ[:w, :, :] = True
        occ[L - w:, :, :] = True
        anchors[:, 0, :] = True
        deck_y = L * 2 // 3
        occ[:, deck_y:deck_y + 2, :] = True

    elif style == "cantilever":
        occ[0, :, :] = True
        anchors[0, :, :] = True
        anchor_y = L // 2
        for x in range(1, L):
            prob = max(0.0, 1.0 - x / L * 1.5)
            layer = rng.random((L,)) < prob
            occ[x, anchor_y, :] = layer
            occ[x, anchor_y + 1, :] = layer & (rng.random((L,)) < 0.7)

    elif style == "arch":
        cx, cy = L // 2, 0
        R = L // 2 - 1
        for x in range(L):
            for y in range(L):
                for z in range(L):
                    dist = np.sqrt((x - cx) ** 2 + (y - cy) ** 2)
                    if R - 2 <= dist <= R + 1 and y >= 0:
                        occ[x, y, z] = True
        anchors[:, 0, :] = occ[:, 0, :]

    elif style == "spiral":
        anchors[:, 0, :] = True
        occ[:, 0, :] = True
        cx, cz = L // 2, L // 2
        for y in range(1, L):
            angle = y * 0.5
            r = min(L // 3, max(2, L // 4))
            sx = int(cx + r * np.cos(angle))
            sz = int(cz + r * np.sin(angle))
            for dx in range(-1, 2):
                for dz in range(-1, 2):
                    nx, nz = sx + dx, sz + dz
                    if 0 <= nx < L and 0 <= nz < L:
                        occ[nx, y, nz] = True

    elif style == "tree":
        trunk_x, trunk_z = L // 2, L // 2
        occ[:, 0, :] = True
        anchors[:, 0, :] = True
        for y in range(L):
            for dx in range(-1, 2):
                for dz in range(-1, 2):
                    nx, nz = trunk_x + dx, trunk_z + dz
                    if 0 <= nx < L and 0 <= nz < L:
                        occ[nx, y, nz] = True
        for _ in range(3):
            by = int(rng.integers(L // 3, L - 2))
            bdir_x = int(rng.choice([-1, 1]))
            bdir_z = int(rng.choice([-1, 1]))
            bx, bz = trunk_x, trunk_z
            for step in range(L // 3):
                bx = min(L - 1, max(0, bx + bdir_x))
                bz = min(L - 1, max(0, bz + bdir_z))
                by2 = min(L - 1, by + step // 2)
                occ[bx, by2, bz] = True
                if bx + 1 < L: occ[bx + 1, by2, bz] = True
                if bz + 1 < L: occ[bx, by2, bz + 1] = True

    elif style == "cave":
        occ[:, :, :] = True
        occ[:, 0, :] = True
        anchors[:, 0, :] = True
        margin = max(1, L // 6)
        occ[margin:L - margin, margin:L - margin, margin:L - margin] = False
        for _ in range(L * 2):
            hx = int(rng.integers(0, L))
            hy = int(rng.integers(1, L))
            hz = int(rng.integers(0, L))
            occ[hx, hy, hz] = False

    elif style == "overhang":
        pw = max(1, L // 6)
        occ[:, 0, :] = True
        anchors[:, 0, :] = True
        for y in range(1, L * 2 // 3):
            occ[:pw, y, :pw] = True
        platform_y = L * 2 // 3
        occ[:, platform_y:platform_y + 2, :] = True

    else:  # random
        fill = rng.uniform(0.3, 0.7)
        occ = rng.random((L, L, L)) < fill
        occ[:, 0, :] = True
        anchors[:, 0, :] = True

    if not anchors.any():
        anchors[:, 0, :] = occ[:, 0, :]
    if not anchors.any():
        anchors[0, 0, 0] = True
        occ[0, 0, 0] = True

    # Material assignment
    if material_cluster:
        # Clustered: choose 2-4 dominant materials and flood-fill-ish assign
        n_clusters = int(rng.integers(2, 5))
        cluster_mats = rng.choice(len(MAT_NAMES), size=n_clusters, replace=False)
        mat_idx = np.zeros((L, L, L), dtype=np.int32)
        # Simple 3D noise thresholding for clusters
        for cx in range(n_clusters):
            threshold = rng.uniform(-0.3, 0.3)
            noise = rng.standard_normal((L, L, L))
            mat_idx[(noise > threshold) & occ] = cluster_mats[cx]
        # Fill remaining with first cluster material
        mat_idx[(mat_idx == 0) & occ] = cluster_mats[0]
    else:
        mat_idx = rng.integers(0, len(MAT_NAMES), size=(L, L, L))

    E_field = np.zeros((L, L, L), dtype=np.float64)
    nu_field = np.zeros((L, L, L), dtype=np.float64)
    density_field = np.zeros((L, L, L), dtype=np.float64)
    rcomp_field = np.zeros((L, L, L), dtype=np.float64)
    rtens_field = np.zeros((L, L, L), dtype=np.float64)

    for i, name in enumerate(MAT_NAMES):
        m = MATERIALS[name]
        mask = (mat_idx == i) & occ
        E_field[mask] = m["E_pa"]
        nu_field[mask] = m["nu"]
        density_field[mask] = m["density"]
        rcomp_field[mask] = m["rcomp"]
        rtens_field[mask] = m["rtens"]

    return VoxelStructure(
        occupancy=occ, anchors=anchors,
        E_field=E_field, nu_field=nu_field,
        density_field=density_field, rcomp_field=rcomp_field,
        rtens_field=rtens_field, mat_ids=mat_idx,
        style=style,
    )


def classify_irregularity(occupancy: np.ndarray) -> float:
    """Irregularity score 0..1 (same formula as brml)."""
    solid = occupancy.astype(bool)
    if not solid.any():
        return 0.0
    n_solid = int(solid.sum())
    Lx, Ly, Lz = solid.shape
    n_total = Lx * Ly * Lz
    fill = n_solid / max(n_total, 1)

    n_surface = 0
    for axis in range(3):
        for delta in [-1, 1]:
            shifted = np.roll(solid, delta, axis=axis)
            if delta == -1:
                slc = [slice(None)] * 3
                slc[axis] = 0
                shifted[tuple(slc)] = False
            else:
                slc = [slice(None)] * 3
                slc[axis] = -1
                shifted[tuple(slc)] = False
            n_surface += int((solid & ~shifted).sum())

    ideal_surface = 6.0 * (n_solid ** (2.0 / 3.0))
    surface_ratio = n_surface / max(ideal_surface, 1.0)

    layer_fills = []
    for y in range(Ly):
        layer_count = int(solid[:, y, :].sum())
        layer_fills.append(layer_count)
    if len(layer_fills) > 1 and max(layer_fills) > 0:
        layer_fills = np.array(layer_fills, dtype=float)
        layer_fills /= max(layer_fills.max(), 1)
        profile_var = float(np.std(layer_fills))
    else:
        profile_var = 0.0

    n_overhang = 0
    for x in range(Lx):
        for y in range(1, Ly):
            for z in range(Lz):
                if solid[x, y, z] and not solid[x, y - 1, z]:
                    n_overhang += 1
    overhang_ratio = n_overhang / max(n_solid, 1)

    irregularity = (
        0.25 * (1.0 - fill) +
        0.25 * min(surface_ratio, 2.0) / 2.0 +
        0.25 * profile_var +
        0.25 * overhang_ratio
    )
    return min(1.0, max(0.0, irregularity))


def compute_complexity(struct: VoxelStructure) -> float:
    """Geometry complexity score for curriculum learning (0..1)."""
    occ = struct.occupancy.astype(bool)
    if not occ.any():
        return 0.0
    n_solid = int(occ.sum())
    L = occ.shape[0]

    # Surface ratio
    n_surface = 0
    for axis in range(3):
        for d in (-1, 1):
            shifted = np.roll(occ, d, axis=axis)
            n_surface += int((occ & ~shifted).sum())
    surface_ratio = n_surface / max(6.0 * (n_solid ** (2.0 / 3.0)), 1.0)

    # Exposure penalty (voxels with many air neighbors)
    exposure = np.zeros(occ.shape, dtype=np.int32)
    for axis in range(3):
        for d in (-1, 1):
            shifted = np.roll(occ, d, axis=axis)
            exposure += (occ & ~shifted).astype(np.int32)
    exposure_ratio = float((exposure >= 5).sum()) / max(n_solid, 1)

    complexity = (
        0.4 * min(surface_ratio, 2.0) / 2.0 +
        0.4 * exposure_ratio +
        0.2 * classify_irregularity(occ)
    )
    return min(1.0, max(0.0, complexity))


def augment_structure(struct: VoxelStructure, rng: np.random.Generator,
                      erosion_prob: float = 0.2,
                      dilation_prob: float = 0.2,
                      cutout_prob: float = 0.3,
                      rotate_prob: float = 0.5,
                      reflect_prob: float = 0.5,
                      material_noise: float = 0.1) -> VoxelStructure:
    """Apply geometric and material augmentations to a VoxelStructure.

    All augmentations preserve anchors at the bottom layer when possible.
    """
    occ = struct.occupancy.copy()
    E = struct.E_field.copy()
    nu = struct.nu_field.copy()
    rho = struct.density_field.copy()
    rc = struct.rcomp_field.copy()
    rt = struct.rtens_field.copy()
    mat_ids = struct.mat_ids.copy()
    anchors = struct.anchors.copy()
    L = occ.shape[0]

    # ── Rotation ──
    if rng.random() < rotate_prob:
        k = int(rng.integers(1, 4))  # 90, 180, 270
        axis = int(rng.integers(3))
        occ = np.rot90(occ, k=k, axes=[(1, 2), (0, 2), (0, 1)][axis])
        E = np.rot90(E, k=k, axes=[(1, 2), (0, 2), (0, 1)][axis])
        nu = np.rot90(nu, k=k, axes=[(1, 2), (0, 2), (0, 1)][axis])
        rho = np.rot90(rho, k=k, axes=[(1, 2), (0, 2), (0, 1)][axis])
        rc = np.rot90(rc, k=k, axes=[(1, 2), (0, 2), (0, 1)][axis])
        rt = np.rot90(rt, k=k, axes=[(1, 2), (0, 2), (0, 1)][axis])
        mat_ids = np.rot90(mat_ids, k=k, axes=[(1, 2), (0, 2), (0, 1)][axis])
        anchors = np.rot90(anchors, k=k, axes=[(1, 2), (0, 2), (0, 1)][axis])

    # ── Reflection ──
    if rng.random() < reflect_prob:
        axis = int(rng.integers(3))
        occ = np.flip(occ, axis=axis).copy()
        E = np.flip(E, axis=axis).copy()
        nu = np.flip(nu, axis=axis).copy()
        rho = np.flip(rho, axis=axis).copy()
        rc = np.flip(rc, axis=axis).copy()
        rt = np.flip(rt, axis=axis).copy()
        mat_ids = np.flip(mat_ids, axis=axis).copy()
        anchors = np.flip(anchors, axis=axis).copy()

    # ── Erosion / Dilation ──
    if rng.random() < erosion_prob:
        # Remove boundary voxels
        for axis in range(3):
            for d in (-1, 1):
                shifted = np.roll(occ, d, axis=axis)
                occ = occ & shifted
    elif rng.random() < dilation_prob:
        # Add boundary voxels
        for axis in range(3):
            for d in (-1, 1):
                shifted = np.roll(occ, d, axis=axis)
                occ = occ | shifted

    # ── Random Cutout ──
    if rng.random() < cutout_prob:
        n_holes = int(rng.integers(1, 3))
        for _ in range(n_holes):
            cx, cy, cz = [int(rng.integers(0, L)) for _ in range(3)]
            r = int(rng.integers(1, 3))
            occ[max(0, cx - r):min(L, cx + r + 1),
                max(0, cy - r):min(L, cy + r + 1),
                max(0, cz - r):min(L, cz + r + 1)] = False

    # Ensure anchors remain grounded
    if not anchors.any():
        anchors[:, 0, :] = occ[:, 0, :]
    if not anchors.any():
        anchors[0, 0, 0] = True
        occ[0, 0, 0] = True

    # Zero out fields where occ is False
    E[~occ] = 0.0
    nu[~occ] = 0.0
    rho[~occ] = 0.0
    rc[~occ] = 0.0
    rt[~occ] = 0.0
    mat_ids[~occ] = 0

    # ── Material noise ──
    if material_noise > 0.0 and rng.random() < 0.5:
        m_noise = rng.lognormal(0.0, material_noise, size=occ.shape)
        E = E * m_noise
        rho = rho * m_noise
        nu = np.clip(nu + rng.normal(0.0, 0.02, size=occ.shape), 0.01, 0.49)

    return VoxelStructure(
        occupancy=occ, anchors=anchors,
        E_field=E, nu_field=nu,
        density_field=rho, rcomp_field=rc,
        rtens_field=rt, mat_ids=mat_ids,
        style=struct.style + "_aug",
    )


def mixup_structures(struct_a: VoxelStructure, struct_b: VoxelStructure,
                     rng: np.random.Generator, alpha: float = 0.4) -> VoxelStructure:
    """Linearly mix two structures and their fields.

    Returns a new VoxelStructure where occupancy and fields are interpolated.
    Note: this does NOT solve FEM for the mixed structure; it is intended
    to be used as a training-time augmentation where the teacher outputs
    are also linearly mixed.
    """
    lam = rng.beta(alpha, alpha)
    lam = float(np.clip(lam, 0.1, 0.9))

    occ = (lam * struct_a.occupancy + (1.0 - lam) * struct_b.occupancy) > 0.5
    E = lam * struct_a.E_field + (1.0 - lam) * struct_b.E_field
    nu = lam * struct_a.nu_field + (1.0 - lam) * struct_b.nu_field
    rho = lam * struct_a.density_field + (1.0 - lam) * struct_b.density_field
    rc = lam * struct_a.rcomp_field + (1.0 - lam) * struct_b.rcomp_field
    rt = lam * struct_a.rtens_field + (1.0 - lam) * struct_b.rtens_field

    # Reconcile mat_ids: pick from the dominant source per voxel
    mat_ids = np.where(rng.random(occ.shape) < lam, struct_a.mat_ids, struct_b.mat_ids)

    anchors = struct_a.anchors | struct_b.anchors
    if not anchors.any():
        anchors[:, 0, :] = occ[:, 0, :]

    E[~occ] = 0.0
    nu[~occ] = 0.0
    rho[~occ] = 0.0
    rc[~occ] = 0.0
    rt[~occ] = 0.0
    mat_ids[~occ] = 0

    return VoxelStructure(
        occupancy=occ, anchors=anchors,
        E_field=E, nu_field=nu,
        density_field=rho, rcomp_field=rc,
        rtens_field=rt, mat_ids=mat_ids,
        style="mixup",
    )
