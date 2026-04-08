"""
Auto-Train Pipeline — one command, zero manual data.

  python -m brml.pipeline.auto_train

Does everything:
  1. Generate random Minecraft-like voxel structures
  2. Solve each with real FEM (hex8, CG solver)
  3. Train FNO3D to predict von Mises stress from geometry
  4. Export trained model to ONNX

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

# Material presets mirroring DefaultMaterial.java
MATERIALS = {
    "concrete":  {"E_pa": 30e9,  "nu": 0.20, "density": 2400.0, "rcomp": 30.0},
    "steel":     {"E_pa": 200e9, "nu": 0.30, "density": 7850.0, "rcomp": 250.0},
    "timber":    {"E_pa": 12e9,  "nu": 0.35, "density": 500.0,  "rcomp": 30.0},
    "brick":     {"E_pa": 15e9,  "nu": 0.15, "density": 1800.0, "rcomp": 15.0},
    "stone":     {"E_pa": 40e9,  "nu": 0.25, "density": 2600.0, "rcomp": 50.0},
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

    for i, name in enumerate(MAT_NAMES):
        m = MATERIALS[name]
        mask = (mat_idx == i) & occ
        E_field[mask] = m["E_pa"]
        nu_field[mask] = m["nu"]
        density_field[mask] = m["density"]
        rcomp_field[mask] = m["rcomp"]

    return VoxelStructure(
        occupancy=occ, anchors=anchors,
        E_field=E_field, nu_field=nu_field,
        density_field=density_field, rcomp_field=rcomp_field,
        mat_ids=mat_idx,
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
#  Stage 3: Dataset from FEM results
# ═══════════════════════════════════════════════════════════════

@dataclass
class FEMDataset:
    """Collection of FEM-solved structures ready for training."""
    # Inputs (per sample, all padded to L³)
    occupancy: list[np.ndarray]     # bool
    E_field: list[np.ndarray]       # float32
    nu_field: list[np.ndarray]      # float32
    density_field: list[np.ndarray] # float32
    rcomp_field: list[np.ndarray]   # float32

    # Targets (FEM ground truth)
    von_mises: list[np.ndarray]     # float32 [Lx,Ly,Lz]
    displacement: list[np.ndarray]  # float32 [Lx,Ly,Lz,3]
    stress_tensor: list[np.ndarray] # float32 [Lx,Ly,Lz,6] — Voigt stress

    # Geometry metadata for irregularity classification
    irregularity: list[float]       # 0.0 = regular box, 1.0 = maximally irregular

    def __len__(self):
        return len(self.occupancy)

    def add(self, struct: VoxelStructure, fem) -> None:
        self.occupancy.append(struct.occupancy.astype(np.float32))
        self.E_field.append(struct.E_field.astype(np.float32))
        self.nu_field.append(struct.nu_field.astype(np.float32))
        self.density_field.append(struct.density_field.astype(np.float32))
        self.rcomp_field.append(struct.rcomp_field.astype(np.float32))
        self.von_mises.append(fem.von_mises.astype(np.float32))
        self.displacement.append(fem.displacement.astype(np.float32))
        self.stress_tensor.append(fem.stress.astype(np.float32))
        self.irregularity.append(classify_irregularity(struct.occupancy))

    @staticmethod
    def empty():
        return FEMDataset([], [], [], [], [], [], [], [], [])


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
    """Build FNO input: [occ, E_norm, nu, density_norm, rcomp_norm] = 5 channels."""
    occ = jnp.array(dataset.occupancy[idx])
    E = jnp.array(dataset.E_field[idx]) / 200e9     # normalize by steel E
    nu = jnp.array(dataset.nu_field[idx])
    rho = jnp.array(dataset.density_field[idx]) / 7850.0  # normalize by steel density
    rc = jnp.array(dataset.rcomp_field[idx]) / 250.0      # normalize by steel rcomp

    return jnp.stack([occ, E, nu, rho, rc], axis=-1)  # [Lx, Ly, Lz, 5]


def train_fno(dataset: FEMDataset, grid_size: int, total_steps: int,
              hidden: int = 48, layers: int = 4, modes: int = 6,
              lr: float = 1e-3, seed: int = 42):
    """Train FNO3DMultiField on FEM dataset (10-channel: stress+disp+phi).

    Multi-field loss:
      L = 0.5 * L_stress(σ_pred, σ_fem)   — 6-channel stress tensor
        + 0.3 * L_disp(u_pred, u_fem)      — 3-channel displacement
        + 0.2 * L_phi(φ_pred, vm_fem)      — 1-channel PFSF-compatible

    Returns trained params, model, and normalization scales.
    """
    jax, jnp, optax, nn, train_state = _import_jax()
    from brml.models.pfsf_surrogate import FNO3DMultiField

    model = FNO3DMultiField(
        hidden_channels=hidden,
        num_layers=layers,
        modes=modes,
        in_channels=5,
    )

    rng = jax.random.PRNGKey(seed)
    L = grid_size
    dummy = jnp.zeros((1, L, L, L, 5))
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
    print(f"  FNO3DMultiField: {n_params:,} parameters (10-channel output)")

    # Compute normalization scales from dataset
    all_vm = np.concatenate([vm.flatten() for vm in dataset.von_mises])
    vm_scale = float(np.percentile(all_vm[all_vm > 0], 99)) if np.any(all_vm > 0) else 1.0
    vm_scale = max(vm_scale, 1e-6)

    all_stress = np.concatenate([s.reshape(-1, 6) for s in dataset.stress_tensor])
    stress_scale = float(np.percentile(np.abs(all_stress[all_stress != 0]), 99)) \
        if np.any(all_stress != 0) else 1.0
    stress_scale = max(stress_scale, 1e-6)

    all_disp = np.concatenate([d.reshape(-1, 3) for d in dataset.displacement])
    disp_scale = float(np.percentile(np.abs(all_disp[all_disp != 0]), 99)) \
        if np.any(all_disp != 0) else 1e-6
    disp_scale = max(disp_scale, 1e-10)

    print(f"  Scales: stress={stress_scale:.2e} Pa, disp={disp_scale:.2e} m, vm={vm_scale:.2e} Pa")

    # Multi-field loss
    def loss_fn(params, x, stress_target, disp_target, vm_target, mask):
        pred = model.apply({"params": params}, x)  # [B, L, L, L, 10]
        mask3 = mask[..., None]  # [B, L, L, L, 1]

        # Stress loss (channels 0:6)
        s_pred = pred[..., :6]
        s_diff = (s_pred - stress_target) ** 2 * mask3
        l_stress = jnp.sum(s_diff) / (jnp.sum(mask) * 6 + 1e-8)

        # Displacement loss (channels 6:9)
        d_pred = pred[..., 6:9]
        d_diff = (d_pred - disp_target) ** 2 * mask3
        l_disp = jnp.sum(d_diff) / (jnp.sum(mask) * 3 + 1e-8)

        # Phi/VM loss (channel 9)
        p_pred = pred[..., 9]
        p_diff = (p_pred - vm_target) ** 2 * mask[..., 0] if mask.ndim > 3 else \
                 (p_pred - vm_target) ** 2 * mask
        l_phi = jnp.sum(p_diff) / (jnp.sum(mask) + 1e-8)

        return 0.5 * l_stress + 0.3 * l_disp + 0.2 * l_phi

    @jax.jit
    def train_step(state, x, stress_t, disp_t, vm_t, mask):
        loss, grads = jax.value_and_grad(loss_fn)(
            state.params, x, stress_t, disp_t, vm_t, mask)
        return state.apply_gradients(grads=grads), loss

    # Training loop
    np_rng = np.random.default_rng(seed)
    n_samples = len(dataset)
    t0 = time.time()
    losses = []

    for step in range(1, total_steps + 1):
        idx = int(np_rng.integers(n_samples))

        x = build_input_tensor(dataset, idx, jnp)[None]
        stress_t = jnp.array(dataset.stress_tensor[idx]) / stress_scale
        disp_t = jnp.array(dataset.displacement[idx]) / disp_scale
        vm_t = jnp.array(dataset.von_mises[idx]) / vm_scale
        mask = jnp.array(dataset.occupancy[idx])

        state, loss = train_step(
            state, x, stress_t[None], disp_t[None], vm_t[None], mask[None])
        losses.append(float(loss))

        if step % max(1, total_steps // 20) == 0:
            avg = np.mean(losses[-100:])
            elapsed = time.time() - t0
            print(f"  [step {step:6d}/{total_steps}] loss={avg:.6f}  "
                  f"({elapsed:.0f}s, {step/elapsed:.0f} it/s)")

    return state.params, model, vm_scale


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
        solved = 0
        failed = 0

        for i, struct in enumerate(structures):
            try:
                _, fem_result = solve_structure(struct)
                if fem_result.converged:
                    dataset.add(struct, fem_result)
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

        # ── Stage 3: Train ──
        print(f"\n═══ Stage 3: Training FNO3D ({self.train_steps} steps) ═══")
        t0 = time.time()
        params, model, vm_scale = train_fno(
            dataset, self.grid_size, self.train_steps,
            seed=self.seed,
        )
        print(f"  Training complete in {time.time()-t0:.1f}s")

        # ── Stage 4: Export ──
        print(f"\n═══ Stage 4: Exporting ═══")
        jax, jnp, _, _, _ = _import_jax()

        # Save weights as npz
        npz_path = self.output_dir / "fno3d_fem_aligned.npz"
        flat = {}
        for path, val in _flatten(params):
            flat[path] = np.asarray(val)
        flat["__vm_scale__"] = np.array([vm_scale])
        flat["__grid_size__"] = np.array([self.grid_size])
        np.savez(str(npz_path), **flat)
        size_kb = npz_path.stat().st_size / 1024
        print(f"  Saved: {npz_path} ({size_kb:.0f} KB, {len(flat)-2} tensors)")

        # Try ONNX export
        try:
            from brml.export.onnx_export import export_to_onnx
            L = self.grid_size
            dummy = (jnp.zeros((1, L, L, L, 5)),)
            onnx_path = self.output_dir / "fno3d_fem_aligned.onnx"
            export_to_onnx(model.apply, params, dummy, onnx_path,
                           model_name="brml_fno3d_fem")
        except Exception as e:
            print(f"  ONNX export skipped: {e}")

        # ── Summary ──
        total_time = time.time() - t_total
        print(f"\n{'═'*60}")
        print(f"  Pipeline complete in {total_time:.0f}s ({total_time/60:.1f} min)")
        print(f"  Structures: {len(structures)} generated → {solved} FEM solved")
        print(f"  Grid size:  {self.grid_size}³")
        print(f"  Output:     {self.output_dir}/")
        print(f"{'═'*60}")

        return params, model, vm_scale


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

    print("╔══════════════════════════════════════════════════════╗")
    print("║  Block Reality ML — FEM-Aligned Auto-Train Pipeline ║")
    print("╚══════════════════════════════════════════════════════╝")
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
