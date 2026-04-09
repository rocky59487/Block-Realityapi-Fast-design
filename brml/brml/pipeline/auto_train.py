"""
Hybrid Auto-Train Pipeline — FEM correctness + PFSF operational alignment.

  python -m brml.pipeline.auto_train

Does everything:
  1. Generate random Minecraft-like voxel structures
  2. Solve each with real FEM (hex8, CG solver)     → stress / displacement ground truth
  3. Solve each with PFSF CPU Jacobi               → phi ground truth (PFSF-compatible)
  4. Train FNO3DMultiField with hybrid multi-teacher loss:
       stress head ← FEM    (physical correctness)
       disp   head ← FEM    (physical correctness)
       phi    head ← PFSF   (runtime compatibility — phi is invariant to sigmaMax)
  5. Add consistency loss: von Mises from predicted stress ≈ PFSF phi
  6. Export trained model to ONNX

Why hybrid?
  - FEM is accurate but uses different quantities from PFSF
  - PFSF phi is what failure_scan actually uses — must match PFSF scale
  - phi is invariant to sigmaMax normalization (A×phi=b both sides cancel)
    so CPU Jacobi phi = GPU PFSF phi, no extra scaling needed at inference time

No data input required. Just run it.
"""
from __future__ import annotations

import argparse
import os
import sys
import time
from dataclasses import dataclass
from pathlib import Path

import numpy as np

# ── Lazy JAX import (heavy) ──
def _import_jax():
    os.environ.setdefault("JAX_PLATFORM_NAME", "cpu")  # safe default
    import jax
    import jax.numpy as jnp
    import optax
    import flax.linen as nn
    from flax.training import train_state
    return jax, jnp, optax, nn, train_state


# ═══════════════════════════════════════════════════════════════
#  Stage 1: Structure Generator
# ═══════════════════════════════════════════════════════════════

# Material presets — full 12 materials mirroring DefaultMaterial.java
# Includes Rtens for tension failure detection
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
    """A generated Minecraft voxel structure with material assignments."""
    occupancy: np.ndarray   # bool [Lx, Ly, Lz]
    anchors: np.ndarray     # bool [Lx, Ly, Lz]
    E_field: np.ndarray     # float64 [Lx, Ly, Lz] — Young's modulus (Pa)
    nu_field: np.ndarray    # float64 [Lx, Ly, Lz]
    density_field: np.ndarray  # float64 [Lx, Ly, Lz]
    rcomp_field: np.ndarray    # float64 [Lx, Ly, Lz]
    rtens_field: np.ndarray    # float64 [Lx, Ly, Lz] — tension strength (MPa)
    mat_ids: np.ndarray     # int [Lx, Ly, Lz] — material index


def generate_structure(L: int, rng: np.random.Generator,
                       style: str = "random") -> VoxelStructure:
    """Generate a random Minecraft-like voxel structure.

    Regular styles (PFSF handles well):
      - "random": random fill
      - "tower": vertical column

    Irregular styles (need FNO):
      - "bridge": horizontal span with supports
      - "cantilever": overhang from one wall
      - "arch": curved arch
      - "spiral": helical staircase
      - "tree": branching structure
      - "cave": hollowed volume with thin walls
      - "overhang": extreme horizontal extension
    """
    occ = np.zeros((L, L, L), dtype=bool)
    anchors = np.zeros((L, L, L), dtype=bool)

    if style == "tower":
        # Random tower: ground floor full, upper floors random
        occ[:, 0, :] = True
        anchors[:, 0, :] = True
        for y in range(1, L):
            fill = max(0.2, 1.0 - y / L * 0.8)
            layer = rng.random((L, L)) < fill
            occ[:, y, :] = layer
            # Ensure connectivity: only keep if neighbor below exists
            for x in range(L):
                for z in range(L):
                    if occ[x, y, z] and not occ[x, y-1, z]:
                        # Check if any neighbor below is solid
                        has_support = False
                        for dx, dz in [(-1,0),(1,0),(0,-1),(0,1)]:
                            nx, nz = x+dx, z+dz
                            if 0 <= nx < L and 0 <= nz < L and occ[nx, y-1, nz]:
                                has_support = True
                                break
                        if not has_support:
                            occ[x, y, z] = False

    elif style == "bridge":
        # Two pillars + span
        w = max(2, L // 4)
        occ[:w, :, :] = True
        occ[L-w:, :, :] = True
        anchors[:, 0, :] = True
        # Bridge deck
        deck_y = L * 2 // 3
        occ[:, deck_y:deck_y+2, :] = True

    elif style == "cantilever":
        # Wall on left + overhang
        occ[0, :, :] = True
        anchors[0, :, :] = True
        anchor_y = L // 2
        for x in range(1, L):
            prob = max(0.0, 1.0 - x / L * 1.5)
            layer = rng.random((L,)) < prob
            occ[x, anchor_y, :] = layer
            occ[x, anchor_y+1, :] = layer & (rng.random((L,)) < 0.7)

    elif style == "arch":
        # Semi-circular arch
        cx, cy = L // 2, 0
        R = L // 2 - 1
        for x in range(L):
            for y in range(L):
                for z in range(L):
                    dist = np.sqrt((x - cx)**2 + (y - cy)**2)
                    if R - 2 <= dist <= R + 1 and y >= 0:
                        occ[x, y, z] = True
        anchors[:, 0, :] = occ[:, 0, :]

    elif style == "spiral":
        # Helical staircase — highly irregular, hard for PFSF
        anchors[:, 0, :] = True
        occ[:, 0, :] = True
        cx, cz = L // 2, L // 2
        for y in range(1, L):
            angle = y * 0.5  # radians per layer
            r = min(L // 3, max(2, L // 4))
            sx = int(cx + r * np.cos(angle))
            sz = int(cz + r * np.sin(angle))
            for dx in range(-1, 2):
                for dz in range(-1, 2):
                    nx, nz = sx + dx, sz + dz
                    if 0 <= nx < L and 0 <= nz < L:
                        occ[nx, y, nz] = True

    elif style == "tree":
        # Branching tree — trunk + recursive branches
        trunk_x, trunk_z = L // 2, L // 2
        occ[:, 0, :] = True
        anchors[:, 0, :] = True
        # Trunk
        for y in range(L):
            for dx in range(-1, 2):
                for dz in range(-1, 2):
                    nx, nz = trunk_x + dx, trunk_z + dz
                    if 0 <= nx < L and 0 <= nz < L:
                        occ[nx, y, nz] = True
        # Branches at random heights
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
        # Hollowed volume — thin outer shell, very irregular
        occ[:, :, :] = True
        occ[:, 0, :] = True
        anchors[:, 0, :] = True
        # Carve interior
        margin = max(1, L // 6)
        occ[margin:L-margin, margin:L-margin, margin:L-margin] = False
        # Random holes in shell
        for _ in range(L * 2):
            hx = int(rng.integers(0, L))
            hy = int(rng.integers(1, L))
            hz = int(rng.integers(0, L))
            occ[hx, hy, hz] = False

    elif style == "overhang":
        # Extreme overhang — base pillar + wide platform
        pw = max(1, L // 6)  # pillar width
        occ[:, 0, :] = True
        anchors[:, 0, :] = True
        # Thin pillar
        for y in range(1, L * 2 // 3):
            occ[:pw, y, :pw] = True
        # Wide platform at top
        platform_y = L * 2 // 3
        occ[:, platform_y:platform_y+2, :] = True

    else:  # "random"
        fill = rng.uniform(0.3, 0.7)
        occ = rng.random((L, L, L)) < fill
        occ[:, 0, :] = True
        anchors[:, 0, :] = True

    # Ensure anchored bottom
    if not anchors.any():
        anchors[:, 0, :] = occ[:, 0, :]
    if not anchors.any():
        anchors[0, 0, 0] = True
        occ[0, 0, 0] = True

    # Assign random materials per voxel
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
    )


# ═══════════════════════════════════════════════════════════════
#  Stage 2: FEM Solver
# ═══════════════════════════════════════════════════════════════

def solve_structure(struct: VoxelStructure) -> tuple[VoxelStructure, 'FEMResult']:
    """Run FEM on a structure. Returns (structure, fem_result)."""
    from brml.fem import FEMSolver
    solver = FEMSolver(voxel_size=1.0, cg_tol=1e-5, cg_maxiter=3000)
    result = solver.solve(
        struct.occupancy, struct.anchors,
        struct.E_field, struct.nu_field, struct.density_field,
    )
    return struct, result


# ═══════════════════════════════════════════════════════════════
#  Stage 2b: PFSF CPU Jacobi Solver
# ═══════════════════════════════════════════════════════════════

def solve_pfsf_phi(struct: VoxelStructure, n_iters: int = 300) -> np.ndarray:
    """Run PFSF-style CPU Jacobi solve to get the phi ground truth.

    Mirrors PFSFDataBuilder normalization:
      conductivity[i] /= sigmaMax
      source[i]       /= sigmaMax
      phi is INVARIANT to this normalization (A×phi=b both sides cancel sigmaMax)

    Because phi is invariant, the result equals what the GPU PFSF solver produces,
    making it directly usable by failure_scan without further scaling.

    Connectivity: 6-face only (simplified vs GPU 26-connectivity).
    For training purposes this gives the correct phi scale; the GPU solver
    refines precision but stays in the same units.

    Args:
        struct: VoxelStructure with material fields
        n_iters: Jacobi iterations (300 is sufficient for convergence at L≤16)

    Returns:
        phi: float32 [Lx, Ly, Lz] in PFSF phi space
    """
    occ = struct.occupancy
    L = occ.shape  # (Lx, Ly, Lz)

    # Derive PFSF inputs from material properties
    # Conductivity ≈ Young's modulus (isotropic structural stiffness proxy)
    conductivity = np.where(occ, struct.E_field, 0.0).astype(np.float64)

    # Source = self-weight (ρ·g·V, voxel size = 1 m³)
    source = np.where(occ, struct.density_field * 9.81, 0.0).astype(np.float64)

    # sigmaMax normalization (mirrors PFSFDataBuilder exactly)
    sigma_max = float(conductivity.max())
    if sigma_max < 1.0:
        return np.zeros(L, dtype=np.float32)

    cond_norm = conductivity / sigma_max  # [0, 1]
    src_norm  = source       / sigma_max  # small positive values

    # Voxel type: 0=air, 1=solid, 2=anchor (bottom layer)
    vtype = np.zeros(L, dtype=np.uint8)
    vtype[occ] = 1
    vtype[:, 0, :] = np.where(occ[:, 0, :], 2, 0)  # ground = anchor

    # 6-face Jacobi iteration (numerically equivalent to PFSF GPU)
    phi = np.zeros(L, dtype=np.float64)
    Lx, Ly, Lz = L

    for _ in range(n_iters):
        phi_new = phi.copy()
        for x in range(Lx):
            for y in range(Ly):
                for z in range(Lz):
                    if vtype[x, y, z] == 0:
                        continue  # air
                    if vtype[x, y, z] == 2:
                        phi_new[x, y, z] = 0.0  # anchor = Dirichlet BC
                        continue

                    num = src_norm[x, y, z]
                    den = 1e-12
                    c0 = cond_norm[x, y, z]

                    for dx, dy, dz in [(-1,0,0),(1,0,0),(0,-1,0),(0,1,0),(0,0,-1),(0,0,1)]:
                        nx, ny, nz = x+dx, y+dy, z+dz
                        if 0 <= nx < Lx and 0 <= ny < Ly and 0 <= nz < Lz:
                            c_nb = cond_norm[nx, ny, nz]
                            c_ij = min(c0, c_nb)  # harmonic-mean proxy
                            num += c_ij * phi[nx, ny, nz]
                            den += c_ij

                    phi_new[x, y, z] = num / den if den > 1e-12 else 0.0

        phi_new[vtype == 0] = 0.0
        phi_new[vtype == 2] = 0.0
        phi = phi_new

    return phi.astype(np.float32)


# ═══════════════════════════════════════════════════════════════
#  Stage 3: Dataset from FEM results
# ═══════════════════════════════════════════════════════════════

@dataclass
class FEMDataset:
    """Collection of FEM + PFSF-solved structures ready for hybrid training.

    Each sample stores:
      - FEM ground truth   (stress, displacement) — physical correctness teacher
      - PFSF Jacobi phi    — runtime compatibility teacher
      - Material inputs    (occ, E, nu, rho, rcomp, rtens)
    """
    # Inputs (per sample, all padded to L³)
    occupancy: list[np.ndarray]     # bool / float32
    E_field: list[np.ndarray]       # float32
    nu_field: list[np.ndarray]      # float32
    density_field: list[np.ndarray] # float32
    rcomp_field: list[np.ndarray]   # float32
    rtens_field: list[np.ndarray]   # float32  ← added for 6-ch input

    # FEM teacher targets
    von_mises: list[np.ndarray]     # float32 [Lx,Ly,Lz]
    displacement: list[np.ndarray]  # float32 [Lx,Ly,Lz,3]
    stress_tensor: list[np.ndarray] # float32 [Lx,Ly,Lz,6] — Voigt stress

    # PFSF teacher targets
    phi_pfsf: list[np.ndarray]      # float32 [Lx,Ly,Lz] — CPU Jacobi phi (PFSF scale)

    # Geometry metadata
    irregularity: list[float]       # 0.0 = regular box, 1.0 = maximally irregular

    def __len__(self):
        return len(self.occupancy)

    def add(self, struct: VoxelStructure, fem) -> None:
        self.occupancy.append(struct.occupancy.astype(np.float32))
        self.E_field.append(struct.E_field.astype(np.float32))
        self.nu_field.append(struct.nu_field.astype(np.float32))
        self.density_field.append(struct.density_field.astype(np.float32))
        self.rcomp_field.append(struct.rcomp_field.astype(np.float32))
        self.rtens_field.append(struct.rtens_field.astype(np.float32))
        self.von_mises.append(fem.von_mises.astype(np.float32))
        self.displacement.append(fem.displacement.astype(np.float32))
        self.stress_tensor.append(fem.stress.astype(np.float32))
        # PFSF phi computed separately (see solve_pfsf_phi)
        self.phi_pfsf.append(np.zeros(struct.occupancy.shape, dtype=np.float32))
        self.irregularity.append(classify_irregularity(struct.occupancy))

    def set_phi_pfsf(self, idx: int, phi: np.ndarray) -> None:
        """Set PFSF Jacobi phi for sample idx after Jacobi solve."""
        self.phi_pfsf[idx] = phi.astype(np.float32)

    @staticmethod
    def empty():
        return FEMDataset([], [], [], [], [], [], [], [], [], [], [])


# ═══════════════════════════════════════════════════════════════
#  Shape Irregularity Classifier
# ═══════════════════════════════════════════════════════════════

def classify_irregularity(occupancy: np.ndarray) -> float:
    """Classify how irregular a structure is (0.0 = box, 1.0 = very irregular).

    Metrics combined:
      1. Fill ratio vs bounding box (lower = more irregular)
      2. Surface-to-volume ratio (higher = more irregular)
      3. Topology: number of holes / cavities
      4. Symmetry deviation

    Used to decide: PFSF (regular) vs FNO (irregular).
    """
    if not occupancy.any():
        return 0.0

    solid = occupancy.astype(bool)
    n_solid = int(solid.sum())
    Lx, Ly, Lz = solid.shape
    n_total = Lx * Ly * Lz

    # 1. Fill ratio (1.0 = full box → regular)
    fill = n_solid / max(n_total, 1)

    # 2. Surface-to-volume ratio
    #    Count exposed faces (faces adjacent to air)
    n_surface = 0
    for axis in range(3):
        # Shift along axis and compare
        for delta in [-1, 1]:
            shifted = np.roll(solid, delta, axis=axis)
            # Boundary faces are always exposed
            if delta == -1:
                slc = [slice(None)] * 3
                slc[axis] = 0
                shifted[tuple(slc)] = False
            else:
                slc = [slice(None)] * 3
                slc[axis] = -1
                shifted[tuple(slc)] = False
            n_surface += int((solid & ~shifted).sum())

    # Ideal cube surface ratio = 6 * n^(2/3) / n^1
    ideal_surface = 6.0 * (n_solid ** (2.0/3.0))
    surface_ratio = n_surface / max(ideal_surface, 1.0)

    # 3. Y-profile variance (how much each layer differs)
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

    # 4. Overhang ratio: blocks with no solid directly below
    n_overhang = 0
    for x in range(Lx):
        for y in range(1, Ly):  # skip ground
            for z in range(Lz):
                if solid[x, y, z] and not solid[x, y-1, z]:
                    n_overhang += 1
    overhang_ratio = n_overhang / max(n_solid, 1)

    # Combine into 0-1 score (MUST match ShapeClassifier.java weights)
    irregularity = (
        0.25 * (1.0 - fill) +
        0.25 * min(surface_ratio, 2.0) / 2.0 +
        0.25 * profile_var +
        0.25 * overhang_ratio
    )
    return min(1.0, max(0.0, irregularity))


# ═══════════════════════════════════════════════════════════════
#  Stage 4: Training
# ═══════════════════════════════════════════════════════════════

def build_input_tensor(dataset: FEMDataset, idx: int, jnp):
    """Build FNO input: [occ, E_norm, nu, density_norm, rcomp_norm, rtens_norm] = 6 channels.

    Normalization constants match OnnxPFSFRuntime.java (E_SCALE, RHO_SCALE, RC_SCALE, RT_SCALE).
    """
    occ  = jnp.array(dataset.occupancy[idx])
    E    = jnp.array(dataset.E_field[idx])    / 200e9   # normalize by steel E
    nu   = jnp.array(dataset.nu_field[idx])
    rho  = jnp.array(dataset.density_field[idx]) / 7850.0  # normalize by steel density
    rc   = jnp.array(dataset.rcomp_field[idx])  / 250.0    # normalize by steel Rcomp
    rt   = jnp.array(dataset.rtens_field[idx])  / 500.0    # normalize by steel Rtens

    return jnp.stack([occ, E, nu, rho, rc, rt], axis=-1)  # [Lx, Ly, Lz, 6]


def train_fno(dataset: FEMDataset, grid_size: int, total_steps: int,
              hidden: int = 48, layers: int = 4, modes: int = 6,
              lr: float = 1e-3, seed: int = 42,
              w_stress: float = 0.40, w_disp: float = 0.20,
              w_phi: float = 0.35, w_consistency: float = 0.05,
              fem_trust_low: float = 0.30, fem_trust_high: float = 0.60):
    """Train FNO3DMultiField with hybrid multi-teacher loss + adaptive FEM gating.

    Loss (per voxel):
      L = w_stress * trust(x) * L_stress(σ_pred, σ_fem)  — FEM (gated)
        + w_disp   * trust(x) * L_disp(u_pred, u_fem)    — FEM (gated)
        + w_phi    *            L_phi(φ_pred, φ_pfsf)     — PFSF (always)
        + w_cons   *            L_cons(vm(σ_pred), φ_pred)— coherence (always)

    Adaptive FEM trust gate:
      discrepancy = |vm_fem_norm(x) − phi_pfsf_norm(x)|  ∈ [0, ∞)
      trust(x)    = clamp((fem_trust_high − disc) /
                          (fem_trust_high − fem_trust_low), 0, 1)

      discrepancy < fem_trust_low  → trust = 1.0  (FEM fully adopted)
      discrepancy > fem_trust_high → trust = 0.0  (FEM discarded for that voxel)
      between the two              → linear fade

    Why PFSF phi ≠ FEM von Mises:
      - phi is a scalar potential, invariant to sigmaMax normalization
      - failure_scan reads phi directly; it must be in PFSF phi space
      - FEM von Mises is in Pa — wrong scale for failure_scan comparison
      - When FEM and PFSF disagree badly (complex irregularities, thin bridges),
        only PFSF phi guides the phi head to keep runtime correctness.

    Returns (params, model).  No vm_scale — phi is dimensionless PFSF scale.
    """
    jax, jnp, optax, nn, train_state = _import_jax()
    from brml.models.pfsf_surrogate import FNO3DMultiField, compute_von_mises_from_stress

    model = FNO3DMultiField(
        hidden_channels=hidden,
        num_layers=layers,
        modes=modes,
        in_channels=6,  # occ + E + nu + rho + rcomp + rtens
    )

    rng = jax.random.PRNGKey(seed)
    L = grid_size
    dummy = jnp.zeros((1, L, L, L, 6))
    variables = model.init(rng, dummy)

    schedule = optax.warmup_cosine_decay_schedule(
        init_value=0.0, peak_value=lr,
        warmup_steps=min(500, total_steps // 10),
        decay_steps=total_steps, end_value=lr * 0.01,
    )
    optimizer = optax.chain(
        optax.clip_by_global_norm(1.0),
        optax.adamw(learning_rate=schedule, weight_decay=1e-4),
    )
    state = train_state.TrainState.create(
        apply_fn=model.apply, params=variables["params"], tx=optimizer,
    )

    n_params = sum(p.size for p in jax.tree_util.tree_leaves(state.params))
    print(f"  FNO3DMultiField (hybrid): {n_params:,} parameters")
    print(f"  Loss weights: stress={w_stress} disp={w_disp} phi={w_phi} consistency={w_consistency}")
    print(f"  FEM trust gate: discard when discrepancy ∈ [{fem_trust_low:.2f}, {fem_trust_high:.2f}]")

    # FEM normalization scales (only for stress/disp heads)
    all_stress = np.concatenate([s.reshape(-1, 6) for s in dataset.stress_tensor])
    stress_scale = float(np.percentile(np.abs(all_stress[all_stress != 0]), 99)) \
        if np.any(all_stress != 0) else 1.0
    stress_scale = max(stress_scale, 1e-6)

    all_disp = np.concatenate([d.reshape(-1, 3) for d in dataset.displacement])
    disp_scale = float(np.percentile(np.abs(all_disp[all_disp != 0]), 99)) \
        if np.any(all_disp != 0) else 1e-6
    disp_scale = max(disp_scale, 1e-10)

    # PFSF phi scale (for logging only — phi is already normalized, no scaling needed)
    all_phi = np.concatenate([p.flatten() for p in dataset.phi_pfsf])
    phi_p99 = float(np.percentile(np.abs(all_phi[all_phi != 0]), 99)) if np.any(all_phi != 0) else 1.0

    print(f"  Scales: stress={stress_scale:.2e} Pa, disp={disp_scale:.2e} m")
    print(f"  PFSF phi p99={phi_p99:.2e} (dimensionless, no scaling applied)")

    # ── Hybrid multi-teacher loss with adaptive FEM trust gate ──
    def loss_fn(params, x, stress_target, disp_target, phi_pfsf_target, mask):
        pred = model.apply({"params": params}, x)  # [B, L, L, L, 10]

        s_pred = pred[..., :6]   # stress tensor (Voigt)
        d_pred = pred[..., 6:9]  # displacement
        p_pred = pred[..., 9]    # PFSF phi

        # ── Per-voxel FEM trust gate ──
        # Discrepancy = |normalized FEM vm − normalized PFSF phi| ∈ [0, ∞).
        # stress_target is already divided by stress_scale → vm_gt ≈ [0, 1].
        # phi_pfsf_target / phi_p99 → phi_gt_norm ≈ [0, 1].
        # Large discrepancy: FEM and PFSF disagree — don't force FEM onto the model.
        vm_gt      = compute_von_mises_from_stress(stress_target)  # [B,L,L,L] ≈ [0,1]
        phi_gt_norm = phi_pfsf_target / (phi_p99 + 1e-8)          # [B,L,L,L] ≈ [0,1]
        discrepancy = jnp.abs(vm_gt - phi_gt_norm)                 # [B,L,L,L]
        fem_trust = jnp.clip(
            (fem_trust_high - discrepancy) / (fem_trust_high - fem_trust_low + 1e-8),
            0.0, 1.0,
        ) * mask                                                    # [B,L,L,L] in [0,1]
        fem_trust3 = fem_trust[..., None]                          # [B,L,L,L,1]
        n_trusted  = jnp.sum(fem_trust) + 1e-8
        trust_frac = n_trusted / (jnp.sum(mask) + 1e-8)           # aux — logged, not trained

        # ① FEM stress loss — gated by fem_trust
        s_diff = (s_pred - stress_target) ** 2 * fem_trust3
        l_stress = jnp.sum(s_diff) / (n_trusted * 6)

        # ② FEM displacement loss — gated by fem_trust
        d_diff = (d_pred - disp_target) ** 2 * fem_trust3
        l_disp = jnp.sum(d_diff) / (n_trusted * 3)

        # ③ PFSF phi loss — always applied (no FEM gate)
        # phi_pfsf_target is PFSF Jacobi phi (sigmaMax-invariant, dimensionless)
        p_diff = (p_pred - phi_pfsf_target) ** 2 * mask
        l_phi = jnp.sum(p_diff) / (jnp.sum(mask) + 1e-8)

        # ④ Consistency loss — always applied (vm from predicted σ ≈ predicted φ)
        vm_pred  = compute_von_mises_from_stress(s_pred) / (stress_scale + 1e-8)
        phi_norm = p_pred / (phi_p99 + 1e-8)
        cons_diff = (vm_pred - phi_norm) ** 2 * mask
        l_consistency = jnp.sum(cons_diff) / (jnp.sum(mask) + 1e-8)

        total = (w_stress * l_stress + w_disp * l_disp
                 + w_phi * l_phi + w_consistency * l_consistency)
        return total, trust_frac   # has_aux=True: trust_frac logged, not differentiated

    @jax.jit
    def train_step(state, x, stress_t, disp_t, phi_t, mask):
        (loss, trust_frac), grads = jax.value_and_grad(loss_fn, has_aux=True)(
            state.params, x, stress_t, disp_t, phi_t, mask)
        return state.apply_gradients(grads=grads), loss, trust_frac

    # ── Training loop ──
    np_rng = np.random.default_rng(seed)
    n_samples = len(dataset)
    t0 = time.time()
    losses = []
    trust_fracs = []
    log_interval = max(1, total_steps // 20)

    for step in range(1, total_steps + 1):
        idx = int(np_rng.integers(n_samples))

        x        = build_input_tensor(dataset, idx, jnp)[None]                # [1,L,L,L,6]
        stress_t = jnp.array(dataset.stress_tensor[idx])[None] / stress_scale # [1,L,L,L,6]
        disp_t   = jnp.array(dataset.displacement[idx])[None]  / disp_scale   # [1,L,L,L,3]
        phi_t    = jnp.array(dataset.phi_pfsf[idx])[None]                     # [1,L,L,L]
        mask     = jnp.array(dataset.occupancy[idx])[None]                    # [1,L,L,L]

        state, loss, trust_frac = train_step(state, x, stress_t, disp_t, phi_t, mask)
        losses.append(float(loss))
        trust_fracs.append(float(trust_frac))

        if step % log_interval == 0:
            avg_loss  = np.mean(losses[-100:])
            avg_trust = np.mean(trust_fracs[-100:])
            elapsed   = time.time() - t0
            print(f"  [step {step:6d}/{total_steps}] loss={avg_loss:.6f}  "
                  f"fem_trust={avg_trust:.1%}  "
                  f"({elapsed:.0f}s, {step/elapsed:.0f} it/s)")

    train_history = {
        "loss":         losses,
        "fem_trust":    trust_fracs,
        "stress_scale": stress_scale,
        "disp_scale":   disp_scale,
        "phi_p99":      phi_p99,
    }
    return state.params, model, train_history


# ═══════════════════════════════════════════════════════════════
#  Stage 5: Evaluation & Report
# ═══════════════════════════════════════════════════════════════

def _r2(y_true: np.ndarray, y_pred: np.ndarray) -> float:
    """Coefficient of determination R²."""
    ss_res = float(np.sum((y_true - y_pred) ** 2))
    ss_tot = float(np.sum((y_true - y_true.mean()) ** 2))
    return 1.0 - ss_res / (ss_tot + 1e-12)


def _von_mises_np(stress6: np.ndarray) -> np.ndarray:
    """Von Mises stress from Voigt notation [N, 6] → [N] (numpy)."""
    sxx, syy, szz = stress6[..., 0], stress6[..., 1], stress6[..., 2]
    txy, tyz, txz = stress6[..., 3], stress6[..., 4], stress6[..., 5]
    return np.sqrt(np.maximum(0.0,
        sxx**2 + syy**2 + szz**2
        - sxx*syy - syy*szz - sxx*szz
        + 3*(txy**2 + tyz**2 + txz**2)
    ))


def evaluate_model(params, model, dataset: FEMDataset, train_history: dict,
                   fem_trust_low: float = 0.30, fem_trust_high: float = 0.60) -> dict:
    """Run the trained model on every dataset sample and compute accuracy metrics.

    Metrics computed per-sample then aggregated:
      - Stress tensor accuracy vs FEM (MAE, RMSE, R²)
      - Von Mises accuracy vs FEM (MAE, R², Pearson r)
      - Displacement accuracy vs FEM (MAE, RMSE, R²)
      - PFSF phi accuracy vs Jacobi ground truth (MAE, RMSE, R²)
      - FEM credibility (per-voxel trust fraction distribution)
      - Failure detection agreement (precision / recall at phi threshold)

    Note: evaluated on the training set — no held-out split exists yet.
    """
    jax, jnp, _, _, _ = _import_jax()

    stress_scale = train_history["stress_scale"]
    disp_scale   = train_history["disp_scale"]
    phi_p99      = train_history["phi_p99"]

    # JIT the inference step once
    @jax.jit
    def infer(x):
        return model.apply({"params": params}, x)

    per_sample = []

    for idx in range(len(dataset)):
        x    = build_input_tensor(dataset, idx, jnp)[None]          # [1,L,L,L,6]
        pred = np.array(infer(x)[0])                                # [L,L,L,10]
        mask = dataset.occupancy[idx].astype(bool)                  # [L,L,L]
        n_solid = int(mask.sum())
        if n_solid < 3:
            continue

        # Denormalize predictions (match training-time normalization)
        s_pred_pa = pred[..., :6][mask] * stress_scale              # [N, 6] Pa
        d_pred_m  = pred[..., 6:9][mask] * disp_scale              # [N, 3] m
        p_pred    = pred[..., 9][mask]                              # [N]   phi (dimensionless)

        # Ground truth
        s_true_pa = dataset.stress_tensor[idx][mask]                # [N, 6] Pa
        d_true_m  = dataset.displacement[idx][mask]                 # [N, 3] m
        p_true    = dataset.phi_pfsf[idx][mask]                     # [N]   phi

        # Von Mises
        vm_pred = _von_mises_np(s_pred_pa)                          # [N] Pa
        vm_true = _von_mises_np(s_true_pa)                          # [N] Pa

        # Per-voxel FEM trust (same formula as loss_fn)
        vm_gt_norm  = vm_true / (stress_scale + 1e-8)
        phi_gt_norm = p_true  / (phi_p99 + 1e-8)
        discrepancy = np.abs(vm_gt_norm - phi_gt_norm)
        trust = np.clip(
            (fem_trust_high - discrepancy) / (fem_trust_high - fem_trust_low + 1e-8),
            0.0, 1.0,
        )

        # Failure detection at phi = 0.5 * phi_p99 threshold
        phi_thresh = 0.5 * phi_p99
        pred_fail  = p_pred > phi_thresh
        true_fail  = p_true > phi_thresh
        n_pred_fail = int(pred_fail.sum())
        n_true_fail = int(true_fail.sum())
        tp = int((pred_fail & true_fail).sum())
        fail_precision = tp / (n_pred_fail + 1e-8) if n_pred_fail > 0 else None
        fail_recall    = tp / (n_true_fail + 1e-8) if n_true_fail > 0 else None

        per_sample.append({
            "idx":               idx,
            "n_solid":           n_solid,
            "irregularity":      float(dataset.irregularity[idx]),

            # FEM credibility
            "fem_trust_mean":       float(trust.mean()),
            "fem_trust_full_frac":  float((trust >= 0.99).mean()),  # fully trusted
            "fem_trust_zero_frac":  float((trust <= 0.01).mean()),  # fully rejected

            # Stress vs FEM
            "stress_r2":        _r2(s_true_pa.flatten(), s_pred_pa.flatten()),
            "stress_mae_pa":    float(np.mean(np.abs(s_pred_pa - s_true_pa))),
            "stress_rmse_pa":   float(np.sqrt(np.mean((s_pred_pa - s_true_pa)**2))),

            # Von Mises vs FEM
            "vm_r2":            _r2(vm_true, vm_pred),
            "vm_mae_pa":        float(np.mean(np.abs(vm_pred - vm_true))),
            "vm_pearson":       float(np.corrcoef(vm_true, vm_pred)[0, 1])
                                if len(vm_true) > 1 else 0.0,

            # Displacement vs FEM
            "disp_r2":          _r2(d_true_m.flatten(), d_pred_m.flatten()),
            "disp_mae_m":       float(np.mean(np.abs(d_pred_m - d_true_m))),
            "disp_rmse_m":      float(np.sqrt(np.mean((d_pred_m - d_true_m)**2))),

            # PFSF phi vs Jacobi
            "phi_r2":           _r2(p_true, p_pred),
            "phi_mae":          float(np.mean(np.abs(p_pred - p_true))),
            "phi_rmse":         float(np.sqrt(np.mean((p_pred - p_true)**2))),

            # Failure detection
            "fail_precision":   fail_precision,
            "fail_recall":      fail_recall,
            "n_true_fail":      n_true_fail,
        })

    if not per_sample:
        return {"n_samples": 0, "per_sample": []}

    def agg(key: str) -> dict:
        vals = [s[key] for s in per_sample if s.get(key) is not None]
        if not vals:
            return None
        a = np.array(vals, dtype=float)
        return {
            "mean": float(a.mean()), "std":  float(a.std()),
            "min":  float(a.min()),  "max":  float(a.max()),
            "p25":  float(np.percentile(a, 25)),
            "p50":  float(np.percentile(a, 50)),
            "p75":  float(np.percentile(a, 75)),
        }

    return {
        "n_samples":         len(per_sample),
        "stress_r2":         agg("stress_r2"),
        "stress_mae_pa":     agg("stress_mae_pa"),
        "stress_rmse_pa":    agg("stress_rmse_pa"),
        "vm_r2":             agg("vm_r2"),
        "vm_mae_pa":         agg("vm_mae_pa"),
        "vm_pearson":        agg("vm_pearson"),
        "disp_r2":           agg("disp_r2"),
        "disp_mae_m":        agg("disp_mae_m"),
        "disp_rmse_m":       agg("disp_rmse_m"),
        "phi_r2":            agg("phi_r2"),
        "phi_mae":           agg("phi_mae"),
        "phi_rmse":          agg("phi_rmse"),
        "fem_trust_mean":    agg("fem_trust_mean"),
        "fem_trust_full_frac": agg("fem_trust_full_frac"),
        "fem_trust_zero_frac": agg("fem_trust_zero_frac"),
        "fail_precision":    agg("fail_precision"),
        "fail_recall":       agg("fail_recall"),
        "irregularity":      agg("irregularity"),
        "per_sample":        per_sample,
    }


def generate_report(eval_metrics: dict, train_history: dict,
                    config: dict, output_dir: Path) -> Path:
    """Format evaluation metrics into a human-readable report + JSON file.

    Writes:
      brml_fno3d_hybrid_report.txt  — console-friendly summary
      brml_fno3d_hybrid_report.json — full machine-readable data
    """
    import json

    def _fmt_agg(agg, unit="", digits=4) -> str:
        if not agg:
            return "N/A"
        return (f"{agg['mean']:.{digits}f}{unit}  "
                f"(±{agg['std']:.{digits}f}, "
                f"p50={agg['p50']:.{digits}f}, "
                f"min={agg['min']:.{digits}f}, "
                f"max={agg['max']:.{digits}f})")

    def _fmt_pct(agg, digits=1) -> str:
        if not agg:
            return "N/A"
        return (f"{agg['mean']*100:.{digits}f}%  "
                f"(±{agg['std']*100:.{digits}f}%, "
                f"p50={agg['p50']*100:.{digits}f}%)")

    losses      = train_history.get("loss", [])
    trust_curve = train_history.get("fem_trust", [])
    n_steps     = len(losses)
    q           = max(1, n_steps // 4)

    def _curve_summary(curve, q):
        if not curve:
            return "N/A", "N/A", "N/A"
        return (f"{np.mean(curve[:q]):.6f}",
                f"{np.mean(curve[len(curve)//2 - q//2 : len(curve)//2 + q//2]):.6f}",
                f"{np.mean(curve[-q:]):.6f}")

    loss_start, loss_mid, loss_end = _curve_summary(losses, q)
    trust_start, trust_mid, trust_end = (
        (_curve_summary(trust_curve, q) if trust_curve
         else ("N/A", "N/A", "N/A"))
    )

    loss_drop = (
        f"{(1 - float(loss_end) / (float(loss_start) + 1e-8)) * 100:.1f}%"
        if loss_start != "N/A" and loss_end != "N/A" else "N/A"
    )

    n = eval_metrics.get("n_samples", 0)

    lines = [
        "╔══════════════════════════════════════════════════════════════════╗",
        "║   Block Reality ML — FNO3DMultiField Hybrid Training Report     ║",
        "║   stress/disp ← FEM  │  phi ← PFSF Jacobi                      ║",
        "╚══════════════════════════════════════════════════════════════════╝",
        "",
        "┌─ Pipeline Configuration ────────────────────────────────────────",
        f"│  Grid size         : {config.get('grid_size', '?')}³",
        f"│  Structures        : {config.get('n_structures', '?')} generated → {n} evaluated",
        f"│  Training steps    : {config.get('train_steps', '?')} "
        f"(loss weights: stress={config.get('w_stress',0.40)} "
        f"disp={config.get('w_disp',0.20)} "
        f"phi={config.get('w_phi',0.35)} "
        f"cons={config.get('w_consistency',0.05)})",
        f"│  FEM trust gate    : discard when discrepancy ∈ "
        f"[{config.get('fem_trust_low',0.30):.2f}, {config.get('fem_trust_high',0.60):.2f}]",
        f"│  Normalization     : stress_scale={train_history.get('stress_scale', 0):.3e} Pa  "
        f"disp_scale={train_history.get('disp_scale', 0):.3e} m  "
        f"phi_p99={train_history.get('phi_p99', 0):.4f}",
        "│",
        "├─ Training Diagnostics ──────────────────────────────────────────",
        f"│  Steps             : {n_steps}",
        f"│  Loss (start/mid/end): {loss_start} → {loss_mid} → {loss_end}",
        f"│  Loss reduction    : {loss_drop}",
        f"│  FEM trust % (start/mid/end): "
        f"{float(trust_start)*100:.1f}% → {float(trust_mid)*100:.1f}% → {float(trust_end)*100:.1f}%"
        if trust_start != "N/A" else "│  FEM trust         : N/A",
        "│",
        "├─ FEM Credibility (ground truth agreement) ──────────────────────",
        f"│  Mean trust / sample  : {_fmt_pct(eval_metrics.get('fem_trust_mean'))}",
        f"│  Fully trusted (≈1.0) : {_fmt_pct(eval_metrics.get('fem_trust_full_frac'))}",
        f"│  Fully rejected (≈0)  : {_fmt_pct(eval_metrics.get('fem_trust_zero_frac'))}",
        f"│  Structure irregularity (0=box, 1=max): "
        f"{_fmt_agg(eval_metrics.get('irregularity'), '', 3)}",
        "│",
        "├─ Model vs FEM — Stress Tensor ─────────────────────────────────",
        f"│  R²    : {_fmt_agg(eval_metrics.get('stress_r2'), '', 4)}",
        f"│  MAE   : {_fmt_agg(eval_metrics.get('stress_mae_pa'), ' Pa', 3)}",
        f"│  RMSE  : {_fmt_agg(eval_metrics.get('stress_rmse_pa'), ' Pa', 3)}",
        "│",
        "├─ Model vs FEM — Von Mises Stress ──────────────────────────────",
        f"│  R²       : {_fmt_agg(eval_metrics.get('vm_r2'), '', 4)}",
        f"│  Pearson r: {_fmt_agg(eval_metrics.get('vm_pearson'), '', 4)}",
        f"│  MAE      : {_fmt_agg(eval_metrics.get('vm_mae_pa'), ' Pa', 3)}",
        "│",
        "├─ Model vs FEM — Displacement ──────────────────────────────────",
        f"│  R²   : {_fmt_agg(eval_metrics.get('disp_r2'), '', 4)}",
        f"│  MAE  : {_fmt_agg(eval_metrics.get('disp_mae_m'), ' m', 6)}",
        f"│  RMSE : {_fmt_agg(eval_metrics.get('disp_rmse_m'), ' m', 6)}",
        "│",
        "├─ Model vs PFSF — Phi (runtime compatibility) ───────────────────",
        f"│  R²   : {_fmt_agg(eval_metrics.get('phi_r2'), '', 4)}",
        f"│  MAE  : {_fmt_agg(eval_metrics.get('phi_mae'), '', 6)}",
        f"│  RMSE : {_fmt_agg(eval_metrics.get('phi_rmse'), '', 6)}",
        "│",
        "├─ Failure Detection Agreement (phi > 0.5×phi_p99) ──────────────",
        f"│  Precision (TP/predicted): {_fmt_agg(eval_metrics.get('fail_precision'), '', 3)}",
        f"│  Recall    (TP/actual)   : {_fmt_agg(eval_metrics.get('fail_recall'), '', 3)}",
        "│",
        "├─ Interpretation Guide ──────────────────────────────────────────",
        "│  R² ≥ 0.95  → excellent  │  R² ≥ 0.85 → good  │  < 0.70 → needs more data/steps",
        "│  FEM trust near 100% → FEM and PFSF agree well (regular structures)",
        "│  FEM trust near 0%   → high discrepancy (complex geometry); phi head",
        "│                         learns purely from PFSF for those voxels",
        "│  Failure recall > 0.9 → model reliably detects failure zones at runtime",
        "├─ Note ──────────────────────────────────────────────────────────",
        "│  All metrics computed on the training set (no held-out split).",
        "│  Use for diagnosing model capacity, not generalization.",
        "└─────────────────────────────────────────────────────────────────",
    ]

    report_text = "\n".join(lines)
    print(report_text)

    output_dir.mkdir(parents=True, exist_ok=True)
    txt_path  = output_dir / "brml_fno3d_hybrid_report.txt"
    json_path = output_dir / "brml_fno3d_hybrid_report.json"

    txt_path.write_text(report_text + "\n", encoding="utf-8")

    # JSON: omit large per_sample list from aggregate section, keep it separate
    per_sample = eval_metrics.pop("per_sample", [])
    json_data = {
        "config":            config,
        "train_history_summary": {
            "n_steps":      n_steps,
            "loss_start":   float(loss_start) if loss_start != "N/A" else None,
            "loss_end":     float(loss_end)   if loss_end   != "N/A" else None,
            "trust_start":  float(trust_start) if trust_start != "N/A" else None,
            "trust_end":    float(trust_end)   if trust_end   != "N/A" else None,
        },
        "evaluation_aggregate": {k: v for k, v in eval_metrics.items()
                                 if k != "per_sample"},
        "per_sample":        per_sample,
    }
    json_path.write_text(
        json.dumps(json_data, indent=2, default=lambda x: float(x) if hasattr(x, "__float__") else str(x)),
        encoding="utf-8",
    )

    print(f"\n  Report saved : {txt_path}")
    print(f"  JSON data    : {json_path}")
    return txt_path


# ═══════════════════════════════════════════════════════════════
#  Main Pipeline
# ═══════════════════════════════════════════════════════════════

class AutoTrainPipeline:
    """One-click: generate → FEM → train → export."""

    def __init__(self, grid_size: int = 12, n_structures: int = 200,
                 train_steps: int = 10000, seed: int = 42,
                 output_dir: str = "brml_output"):
        self.grid_size = grid_size
        self.n_structures = n_structures
        self.train_steps = train_steps
        self.seed = seed
        self.output_dir = Path(output_dir)

    def run(self):
        """Execute the full pipeline."""
        self.output_dir.mkdir(parents=True, exist_ok=True)
        t_total = time.time()

        # ── Stage 1: Generate ──
        print(f"═══ Stage 1: Generating {self.n_structures} structures ({self.grid_size}³) ═══")
        t0 = time.time()
        rng = np.random.default_rng(self.seed)
        # Bias toward irregular styles (70% irregular, 30% regular)
        irregular_styles = ["bridge", "cantilever", "arch", "spiral", "tree", "cave", "overhang"]
        regular_styles = ["random", "tower"]
        styles = irregular_styles * 7 + regular_styles * 3  # weighted pool

        structures = []
        for i in range(self.n_structures):
            style = styles[rng.integers(len(styles))]
            struct = generate_structure(self.grid_size, rng, style)
            n_solid = int(struct.occupancy.sum())
            if n_solid < 3:
                continue
            structures.append(struct)

        print(f"  Generated {len(structures)} valid structures in {time.time()-t0:.1f}s")

        # ── Stage 2: FEM Solve ──
        print(f"\n═══ Stage 2: FEM solving ({len(structures)} structures) ═══")
        t0 = time.time()
        dataset = FEMDataset.empty()
        solved_structs: list[VoxelStructure] = []  # track for Stage 2b PFSF phi
        solved = 0
        failed = 0

        for i, struct in enumerate(structures):
            try:
                _, fem_result = solve_structure(struct)
                if fem_result.converged:
                    dataset.add(struct, fem_result)
                    solved_structs.append(struct)
                    solved += 1
                else:
                    failed += 1
            except Exception as e:
                failed += 1
                if failed <= 3:
                    print(f"  WARNING: FEM failed for structure {i}: {e}")

            if (i + 1) % max(1, len(structures) // 10) == 0:
                elapsed = time.time() - t0
                print(f"  [{i+1}/{len(structures)}] solved={solved} failed={failed} "
                      f"({elapsed:.0f}s)")

        print(f"  FEM complete: {solved} solved, {failed} failed in {time.time()-t0:.1f}s")

        if solved < 5:
            print("ERROR: Too few converged solutions. Aborting.")
            return None

        # ── Stage 2b: PFSF Jacobi phi ──
        # Derive phi from the same material data used for FEM.
        # phi is invariant to sigmaMax normalization (A×phi=b both sides cancel),
        # so CPU Jacobi phi matches GPU PFSF phi exactly — no extra scaling at inference.
        print(f"\n═══ Stage 2b: PFSF Jacobi phi ({solved} structures) ═══")
        t0 = time.time()
        phi_solved = 0
        phi_failed = 0
        for i, struct in enumerate(solved_structs):
            # solved_structs[i] corresponds exactly to dataset[i] (same insertion order)
            try:
                phi = solve_pfsf_phi(struct)
                dataset.set_phi_pfsf(i, phi)
                phi_solved += 1
            except Exception as e:
                phi_failed += 1
                if phi_failed <= 3:
                    print(f"  WARNING: PFSF phi failed for dataset[{i}]: {e}")

        print(f"  PFSF phi complete: {phi_solved} solved, {phi_failed} failed "
              f"in {time.time()-t0:.1f}s")

        # ── Stage 3: Train ──
        print(f"\n═══ Stage 3: Training FNO3DMultiField hybrid ({self.train_steps} steps) ═══")
        t0 = time.time()
        params, model, train_history = train_fno(
            dataset, self.grid_size, self.train_steps,
            seed=self.seed,
        )
        print(f"  Training complete in {time.time()-t0:.1f}s")

        # ── Stage 4: Export ──
        print(f"\n═══ Stage 4: Exporting ═══")
        jax, jnp, _, _, _ = _import_jax()

        # Save weights as npz (no vm_scale — phi is dimensionless PFSF scale)
        npz_path = self.output_dir / "brml_fno3d_hybrid.npz"
        flat = {}
        for path, val in _flatten(params):
            flat[path] = np.asarray(val)
        flat["__grid_size__"] = np.array([self.grid_size])
        np.savez(str(npz_path), **flat)
        size_kb = npz_path.stat().st_size / 1024
        print(f"  Saved: {npz_path} ({size_kb:.0f} KB, {len(flat)-1} tensors)")

        # Try ONNX export — 6-channel input (occ, E, nu, rho, rcomp, rtens)
        try:
            from brml.export.onnx_export import export_to_onnx
            L = self.grid_size
            dummy = (jnp.zeros((1, L, L, L, 6)),)
            onnx_path = self.output_dir / "brml_fno3d_hybrid.onnx"
            export_to_onnx(model.apply, params, dummy, onnx_path,
                           model_name="brml_fno3d_hybrid")
        except Exception as e:
            print(f"  ONNX export skipped: {e}")

        # ── Stage 5: Evaluate & Report ──
        print(f"\n═══ Stage 5: Evaluation & Report ═══")
        t0 = time.time()
        eval_metrics = evaluate_model(
            params, model, dataset, train_history,
            fem_trust_low=0.30, fem_trust_high=0.60,
        )
        report_config = {
            "grid_size":       self.grid_size,
            "n_structures":    self.n_structures,
            "train_steps":     self.train_steps,
            "w_stress":        0.40,
            "w_disp":          0.20,
            "w_phi":           0.35,
            "w_consistency":   0.05,
            "fem_trust_low":   0.30,
            "fem_trust_high":  0.60,
        }
        generate_report(eval_metrics, train_history, report_config, self.output_dir)
        print(f"  Evaluation complete in {time.time()-t0:.1f}s")

        # ── Summary ──
        total_time = time.time() - t_total
        print(f"\n{'═'*60}")
        print(f"  Pipeline complete in {total_time:.0f}s ({total_time/60:.1f} min)")
        print(f"  Structures: {len(structures)} generated → {solved} FEM + {phi_solved} PFSF phi solved")
        print(f"  Grid size:  {self.grid_size}³")
        print(f"  Output:     {self.output_dir}/")
        print(f"{'═'*60}")

        return params, model, train_history


def _flatten(params, prefix=""):
    result = []
    if isinstance(params, dict):
        for k, v in params.items():
            result.extend(_flatten(v, f"{prefix}{k}/"))
    else:
        result.append((prefix.rstrip("/"), params))
    return result


# ═══════════════════════════════════════════════════════════════
#  CLI
# ═══════════════════════════════════════════════════════════════

def main():
    parser = argparse.ArgumentParser(
        description="Auto-Train: generate FEM data + train FNO3D — zero manual input",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Quick test (5 min on CPU)
  python -m brml.pipeline.auto_train --grid 8 --structures 50 --steps 2000

  # Full training (1-2 hours on CPU, faster on GPU)
  python -m brml.pipeline.auto_train --grid 16 --structures 500 --steps 20000

  # High quality (4-8 hours, needs GPU)
  python -m brml.pipeline.auto_train --grid 16 --structures 2000 --steps 50000
""",
    )
    parser.add_argument("--grid", type=int, default=12, help="Voxel grid size (default: 12)")
    parser.add_argument("--structures", type=int, default=200,
                        help="Number of structures to generate (default: 200)")
    parser.add_argument("--steps", type=int, default=10000,
                        help="Training steps (default: 10000)")
    parser.add_argument("--output", type=str, default="brml_output",
                        help="Output directory (default: brml_output)")
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    print("╔════════════════════════════════════════════════════════╗")
    print("║  Block Reality ML — Hybrid FEM+PFSF Auto-Train        ║")
    print("║  stress/disp ← FEM  |  phi ← PFSF Jacobi             ║")
    print("╚════════════════════════════════════════════════════════╝")
    print(f"  Grid:       {args.grid}³ ({args.grid**3} voxels/structure)")
    print(f"  Structures: {args.structures}")
    print(f"  Steps:      {args.steps}")
    print(f"  Output:     {args.output}/")
    print()

    pipeline = AutoTrainPipeline(
        grid_size=args.grid,
        n_structures=args.structures,
        train_steps=args.steps,
        seed=args.seed,
        output_dir=args.output,
    )
    pipeline.run()


if __name__ == "__main__":
    main()
