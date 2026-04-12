"""
NS Reference Solver — high-quality Navier-Stokes reference data for FNOFluid3D training.

Generates (u, p, boundary) → (u_next, p_next) trajectories using a stabilized
Stable Fluids (Stam 1999) solver in JAX.

Output format: numpy .npz, shape [N_traj, T, Nx, Ny, Nz, C]
  Nx = Ny = Nz = 40 (4 blocks × 10 sub-cells, matching blockL=4 in OnnxFluidRuntime)
  C_in  = 8: (vx, vy, vz, p, rho, vof, typeOcc, phi)
  C_out = 4: (vx_next, vy_next, vz_next, p_next)

Normalization (must match OnnxFluidRuntime constants):
  vx/vy/vz  / V_SCALE   = 10.0 m/s
  p         / P_SCALE   = 101325 Pa
  rho       / RHO_SCALE = 1000 kg/m³
  phi       / PHI_SCALE = 101325 Pa
  vof       unchanged   [0, 1]
  typeOcc   unchanged   {0, 1}

Usage:
  python -m brml.data.ns_reference_solver --out ./data/fluid --n-traj 2000 --grid-size 40
  python -m brml.data.ns_reference_solver --out ./data/fluid --n-traj 500 --grid-size 40 --t-steps 8
"""
from __future__ import annotations

import argparse
import os
from pathlib import Path

import numpy as np

# JAX is optional; fall back to NumPy if not available
try:
    import jax
    import jax.numpy as jnp
    _JAX = True
except ImportError:
    _JAX = False
    import numpy as jnp

# ─── Physical & Normalization Constants ───────────────────────────────────────
V_SCALE   = 10.0      # m/s
P_SCALE   = 101325.0  # Pa
RHO_SCALE = 1000.0    # kg/m³ (water)
PHI_SCALE = 101325.0  # Pa (potential ≈ pressure scale)
GRAVITY   = 9.81      # m/s²
CELL_SIZE = 0.1       # m (sub-cell resolution: 1 block / 10)
DT        = 0.05      # s (simulation time step)
VISCOSITY = 1e-3      # Pa·s (water ~1 cP)


# ─── Solver ──────────────────────────────────────────────────────────────────

def _flat(x: int, y: int, z: int, N: int) -> int:
    """3D → 1D flat index (NumPy fallback, vectorized via broadcasting)."""
    return x + y * N + z * N * N


def advect(q: np.ndarray, vx: np.ndarray, vy: np.ndarray, vz: np.ndarray,
           N: int, dt: float, h: float) -> np.ndarray:
    """Semi-Lagrangian back-trace (trilinear) for scalar field q.

    Args:
        q:  Field to advect, shape [N, N, N]
        vx/vy/vz: Velocity components, each [N, N, N]
        N:  Grid resolution
        dt: Time step (s)
        h:  Cell size (m)
    """
    gx, gy, gz = np.meshgrid(
        np.arange(N), np.arange(N), np.arange(N), indexing='ij')

    # Back-trace positions
    px = np.clip(gx - vx * dt / h, 0, N - 1 - 1e-6)
    py = np.clip(gy - vy * dt / h, 0, N - 1 - 1e-6)
    pz = np.clip(gz - vz * dt / h, 0, N - 1 - 1e-6)

    # Trilinear interpolation
    x0, y0, z0 = px.astype(int), py.astype(int), pz.astype(int)
    x1, y1, z1 = np.minimum(x0 + 1, N - 1), np.minimum(y0 + 1, N - 1), np.minimum(z0 + 1, N - 1)
    tx, ty, tz = px - x0, py - y0, pz - z0

    c000 = q[x0, y0, z0]; c100 = q[x1, y0, z0]
    c010 = q[x0, y1, z0]; c110 = q[x1, y1, z0]
    c001 = q[x0, y0, z1]; c101 = q[x1, y0, z1]
    c011 = q[x0, y1, z1]; c111 = q[x1, y1, z1]

    return (c000*(1-tx)*(1-ty)*(1-tz) + c100*tx*(1-ty)*(1-tz) +
            c010*(1-tx)*ty*(1-tz)     + c110*tx*ty*(1-tz)     +
            c001*(1-tx)*(1-ty)*tz     + c101*tx*(1-ty)*tz     +
            c011*(1-tx)*ty*tz         + c111*tx*ty*tz)


def divergence(vx: np.ndarray, vy: np.ndarray, vz: np.ndarray, h: float) -> np.ndarray:
    """Central-difference divergence ∇·u."""
    dvx = (np.roll(vx, -1, axis=0) - np.roll(vx, 1, axis=0)) / (2 * h)
    dvy = (np.roll(vy, -1, axis=1) - np.roll(vy, 1, axis=1)) / (2 * h)
    dvz = (np.roll(vz, -1, axis=2) - np.roll(vz, 1, axis=2)) / (2 * h)
    return dvx + dvy + dvz


def poisson_jacobi(p: np.ndarray, div: np.ndarray, h: float,
                   dt: float, iterations: int = 50) -> np.ndarray:
    """Jacobi Poisson solver: ∇²p = ∇·u / dt."""
    rhs = div / dt
    for _ in range(iterations):
        p_new = (
            np.roll(p, 1, 0) + np.roll(p, -1, 0) +
            np.roll(p, 1, 1) + np.roll(p, -1, 1) +
            np.roll(p, 1, 2) + np.roll(p, -1, 2) -
            rhs * h * h
        ) / 6.0
        p = p_new
    return p


def project(vx: np.ndarray, vy: np.ndarray, vz: np.ndarray,
            p: np.ndarray, rho: float, h: float):
    """Pressure projection: u -= ∇p / rho."""
    vx = vx - (np.roll(p, -1, 0) - np.roll(p, 1, 0)) / (2 * h * rho)
    vy = vy - (np.roll(p, -1, 1) - np.roll(p, 1, 1)) / (2 * h * rho)
    vz = vz - (np.roll(p, -1, 2) - np.roll(p, 1, 2)) / (2 * h * rho)
    return vx, vy, vz


def stable_fluids_step(vx: np.ndarray, vy: np.ndarray, vz: np.ndarray,
                       p: np.ndarray, phi: np.ndarray, vof: np.ndarray,
                       rho: float, N: int,
                       dt: float = DT, h: float = CELL_SIZE,
                       n_jacobi: int = 50):
    """One complete Stable Fluids time step."""
    # 1. Semi-Lagrangian advection
    vx = advect(vx, vx, vy, vz, N, dt, h)
    vy = advect(vy, vx, vy, vz, N, dt, h)
    vz = advect(vz, vx, vy, vz, N, dt, h)
    vof = advect(vof, vx, vy, vz, N, dt, h)
    vof = np.clip(vof, 0.0, 1.0)

    # 2. Body forces (gravity on Y)
    vy = vy - GRAVITY * dt

    # 3. Diffusion (simple explicit, viscosity term)
    laplacian_vx = (np.roll(vx,1,0)+np.roll(vx,-1,0)+
                    np.roll(vx,1,1)+np.roll(vx,-1,1)+
                    np.roll(vx,1,2)+np.roll(vx,-1,2) - 6*vx) / (h*h)
    laplacian_vy = (np.roll(vy,1,0)+np.roll(vy,-1,0)+
                    np.roll(vy,1,1)+np.roll(vy,-1,1)+
                    np.roll(vy,1,2)+np.roll(vy,-1,2) - 6*vy) / (h*h)
    laplacian_vz = (np.roll(vz,1,0)+np.roll(vz,-1,0)+
                    np.roll(vz,1,1)+np.roll(vz,-1,1)+
                    np.roll(vz,1,2)+np.roll(vz,-1,2) - 6*vz) / (h*h)
    vx = vx + VISCOSITY / rho * laplacian_vx * dt
    vy = vy + VISCOSITY / rho * laplacian_vy * dt
    vz = vz + VISCOSITY / rho * laplacian_vz * dt

    # 4. Pressure Poisson
    div = divergence(vx, vy, vz, h)
    p = poisson_jacobi(p, div, h, dt, iterations=n_jacobi)

    # 5. Projection
    vx, vy, vz = project(vx, vy, vz, p, rho, h)

    # 6. Update phi (hydrostatic-like potential)
    gy_grid = np.arange(N)[None, :, None] * h
    phi = rho * GRAVITY * gy_grid * vof

    return vx, vy, vz, p, phi, vof


# ─── Trajectory generation ────────────────────────────────────────────────────

def generate_trajectory(N: int, T: int, rng: np.random.Generator
                         ) -> dict[str, np.ndarray]:
    """Generate one random-IC fluid trajectory.

    Returns dict with:
      'inputs':  float32 [T, N, N, N, 8]  — normalized
      'outputs': float32 [T, N, N, N, 4]  — normalized (next-step targets)
    """
    # Random initial condition
    rho = RHO_SCALE * (0.9 + 0.2 * rng.random())  # 900–1100 kg/m³

    vx = rng.standard_normal((N, N, N)).astype(np.float32) * 0.2  # ~0.2 m/s noise
    vy = np.zeros((N, N, N), dtype=np.float32)
    vz = rng.standard_normal((N, N, N)).astype(np.float32) * 0.2

    # Column of water in the lower half
    vof = np.zeros((N, N, N), dtype=np.float32)
    fill_h = int(N * (0.3 + 0.4 * rng.random()))
    vof[:, :fill_h, :] = 1.0

    p = rho * GRAVITY * np.arange(N)[None, :, None] * CELL_SIZE * vof
    p = p.astype(np.float32)
    phi = p.copy()

    # Solid wall boundary (random box obstruction, optional)
    type_occ = np.zeros((N, N, N), dtype=np.float32)
    if rng.random() < 0.4:
        wx = rng.integers(N//4, 3*N//4)
        wy = rng.integers(N//4, 3*N//4)
        wz = rng.integers(N//4, 3*N//4)
        ws = rng.integers(2, N//4)
        type_occ[wx:wx+ws, wy:wy+ws, wz:wz+ws] = 1.0

    inputs_list = []
    outputs_list = []

    for t in range(T):
        # Pack input (normalized)
        inp = np.stack([
            vx  / V_SCALE,
            vy  / V_SCALE,
            vz  / V_SCALE,
            p   / P_SCALE,
            np.full((N, N, N), rho / RHO_SCALE, dtype=np.float32),
            vof,
            type_occ,
            phi / PHI_SCALE,
        ], axis=-1).astype(np.float32)  # [N, N, N, 8]
        inputs_list.append(inp)

        # Simulate one step
        vx_n, vy_n, vz_n, p_n, phi_n, vof_n = stable_fluids_step(
            vx, vy, vz, p, phi, vof, rho, N)

        # Pack output (normalized)
        out = np.stack([
            vx_n / V_SCALE,
            vy_n / V_SCALE,
            vz_n / V_SCALE,
            p_n  / P_SCALE,
        ], axis=-1).astype(np.float32)  # [N, N, N, 4]
        outputs_list.append(out)

        vx, vy, vz, p, phi, vof = vx_n, vy_n, vz_n, p_n, phi_n, vof_n

    return {
        'inputs':  np.stack(inputs_list,  axis=0),  # [T, N, N, N, 8]
        'outputs': np.stack(outputs_list, axis=0),  # [T, N, N, N, 4]
    }


def generate_dataset(n_traj: int, N: int, T: int, out_dir: str,
                     seed: int = 42) -> None:
    """Generate full dataset and save as .npz shards."""
    out_path = Path(out_dir)
    out_path.mkdir(parents=True, exist_ok=True)

    rng = np.random.default_rng(seed)
    shard_size = 50  # trajectories per .npz shard

    n_shards = (n_traj + shard_size - 1) // shard_size
    print(f"Generating {n_traj} trajectories × {T} steps @ {N}³ grid → {n_shards} shards in {out_dir}")

    traj_idx = 0
    for shard_i in range(n_shards):
        batch_inputs  = []
        batch_outputs = []
        n_in_shard = min(shard_size, n_traj - traj_idx)
        for _ in range(n_in_shard):
            traj = generate_trajectory(N, T, rng)
            batch_inputs.append(traj['inputs'])
            batch_outputs.append(traj['outputs'])
            traj_idx += 1

        shard_file = out_path / f"fluid_traj_shard_{shard_i:04d}.npz"
        np.savez_compressed(
            shard_file,
            inputs  = np.stack(batch_inputs,  axis=0),  # [B, T, N, N, N, 8]
            outputs = np.stack(batch_outputs, axis=0),  # [B, T, N, N, N, 4]
            grid_size = np.array([N]),
            t_steps   = np.array([T]),
        )
        print(f"  Shard {shard_i+1}/{n_shards}: {shard_file}")

    print(f"Done. Dataset: {traj_idx} trajectories in {out_dir}")


# ─── CLI ─────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description="Generate NS reference trajectories for FNOFluid3D training")
    parser.add_argument("--out",        default="./data/fluid",
                        help="Output directory for .npz shards")
    parser.add_argument("--n-traj",     type=int, default=2000,
                        help="Total number of trajectories to generate")
    parser.add_argument("--grid-size",  type=int, default=40,
                        help="Spatial grid resolution N (sub-cells per side, must = blockL×10)")
    parser.add_argument("--t-steps",    type=int, default=8,
                        help="Time steps per trajectory")
    parser.add_argument("--seed",       type=int, default=42,
                        help="Random seed for reproducibility")
    args = parser.parse_args()

    generate_dataset(args.n_traj, args.grid_size, args.t_steps, args.out, args.seed)


if __name__ == "__main__":
    main()
