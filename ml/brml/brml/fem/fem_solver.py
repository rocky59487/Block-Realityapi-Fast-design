"""
Voxel FEM solver — global assembly + sparse CG solve + stress recovery.

Takes a 3D boolean voxel grid + material properties, computes:
  - Displacement field u(x,y,z) in 3 components
  - Stress tensor σ(x,y,z) in 6 Voigt components
  - Von Mises equivalent stress per voxel

Uses scipy.sparse for assembly and conjugate gradient with ILU preconditioning.
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np
from scipy import sparse
from scipy.sparse.linalg import cg, LinearOperator

from .hex8_element import (
    hex8_stiffness, hex8_body_force, elasticity_matrix,
    compute_element_stress, von_mises,
    NODE_COORDS, GAUSS_POINTS, _shape_derivatives,
)


@dataclass
class FEMResult:
    """FEM solution for a voxel structure."""

    # Grid dimensions
    shape: tuple[int, int, int]

    # Displacement field: [Lx, Ly, Lz, 3]
    displacement: np.ndarray

    # Stress tensor (Voigt): [Lx, Ly, Lz, 6]
    #   [σ_xx, σ_yy, σ_zz, τ_xy, τ_yz, τ_xz]
    stress: np.ndarray

    # Von Mises equivalent stress: [Lx, Ly, Lz]
    von_mises: np.ndarray

    # Solver metadata
    converged: bool
    iterations: int
    residual: float


class FEMSolver:
    """Voxel-based FEM solver for Minecraft structures.

    Solves linear elastic: [K]{u} = {F}
      - K: global stiffness (assembled from hex8 elements)
      - F: gravity body forces
      - BC: fixed displacement at anchor voxels

    Usage:
        solver = FEMSolver()
        result = solver.solve(occupancy, anchors, E_field, nu_field, density_field)
    """

    def __init__(self, voxel_size: float = 1.0, cg_tol: float = 1e-6,
                 cg_maxiter: int = 5000):
        self.voxel_size = voxel_size
        self.cg_tol = cg_tol
        self.cg_maxiter = cg_maxiter

        # Cache element stiffness matrices per (E, nu) pair
        self._ke_cache: dict[tuple[float, float], np.ndarray] = {}

    def solve(
        self,
        occupancy: np.ndarray,   # bool[Lx, Ly, Lz]
        anchors: np.ndarray,     # bool[Lx, Ly, Lz]
        E_field: np.ndarray,     # float[Lx, Ly, Lz] Young's modulus (Pa)
        nu_field: np.ndarray,    # float[Lx, Ly, Lz] Poisson's ratio
        density_field: np.ndarray,  # float[Lx, Ly, Lz] density (kg/m³)
    ) -> FEMResult:
        """Solve the FEM problem.

        Args:
            occupancy: True where solid voxel exists
            anchors: True where voxel is fixed (boundary condition)
            E_field: Young's modulus per voxel (Pa)
            nu_field: Poisson's ratio per voxel
            density_field: Density per voxel (kg/m³)
        Returns:
            FEMResult with displacement, stress, von Mises fields
        """
        Lx, Ly, Lz = occupancy.shape

        # ── Node numbering ──
        # Nodes are at voxel corners: (Lx+1) × (Ly+1) × (Lz+1)
        Nx, Ny, Nz = Lx + 1, Ly + 1, Lz + 1
        n_nodes = Nx * Ny * Nz
        n_dof = n_nodes * 3

        def node_id(ix, iy, iz):
            return ix + Nx * (iy + Ny * iz)

        # Element → 8 node IDs
        def elem_nodes(ex, ey, ez):
            return [
                node_id(ex,   ey,   ez),
                node_id(ex+1, ey,   ez),
                node_id(ex+1, ey+1, ez),
                node_id(ex,   ey+1, ez),
                node_id(ex,   ey,   ez+1),
                node_id(ex+1, ey,   ez+1),
                node_id(ex+1, ey+1, ez+1),
                node_id(ex,   ey+1, ez+1),
            ]

        # ── Assembly (COO format) ──
        rows, cols, vals = [], [], []
        F = np.zeros(n_dof, dtype=np.float64)

        solid_voxels = np.argwhere(occupancy)

        for (ex, ey, ez) in solid_voxels:
            E = float(E_field[ex, ey, ez])
            nu = float(nu_field[ex, ey, ez])
            rho = float(density_field[ex, ey, ez])

            # Get or compute element stiffness
            key = (round(E, 1), round(nu, 4))
            if key not in self._ke_cache:
                self._ke_cache[key] = hex8_stiffness(E, nu, self.voxel_size)
            Ke = self._ke_cache[key]

            # Body force
            fe = hex8_body_force(rho, 9.81, self.voxel_size)

            # DOF mapping
            nodes = elem_nodes(ex, ey, ez)
            dofs = []
            for n in nodes:
                dofs.extend([n * 3, n * 3 + 1, n * 3 + 2])

            # Add to global
            for i in range(24):
                F[dofs[i]] += fe[i]
                for j in range(24):
                    if abs(Ke[i, j]) > 1e-15:
                        rows.append(dofs[i])
                        cols.append(dofs[j])
                        vals.append(Ke[i, j])

        # Build sparse K
        K = sparse.coo_matrix((vals, (rows, cols)), shape=(n_dof, n_dof))
        K = K.tocsr()

        # ── Boundary conditions (penalty method) ──
        # Fix all DOFs of nodes touching anchor voxels
        penalty = 1e20
        anchor_nodes = set()
        for (ex, ey, ez) in np.argwhere(anchors):
            for n in elem_nodes(ex, ey, ez):
                anchor_nodes.add(n)

        for n in anchor_nodes:
            for d in range(3):
                dof = n * 3 + d
                K[dof, dof] += penalty
                F[dof] = 0.0  # prescribed displacement = 0

        # ── Solve [K]{u} = {F} via CG ──
        # Diagonal preconditioner
        diag = K.diagonal()
        diag_inv = np.where(np.abs(diag) > 1e-30, 1.0 / diag, 0.0)
        M = LinearOperator((n_dof, n_dof), matvec=lambda x: diag_inv * x)

        u = np.zeros(n_dof, dtype=np.float64)
        info_holder = {"iters": 0}

        def callback(xk):
            info_holder["iters"] += 1

        try:
            u, info = cg(K, F, x0=u, rtol=self.cg_tol, maxiter=self.cg_maxiter,
                          M=M, callback=callback)
        except TypeError:
            # scipy < 1.14 uses 'tol' instead of 'rtol'
            u, info = cg(K, F, x0=u, tol=self.cg_tol, maxiter=self.cg_maxiter,
                          M=M, callback=callback)

        converged = (info == 0)

        # Compute residual
        residual = float(np.linalg.norm(K @ u - F) / (np.linalg.norm(F) + 1e-30))

        # ── Stress recovery ──
        disp_field = np.zeros((Lx, Ly, Lz, 3), dtype=np.float32)
        stress_field = np.zeros((Lx, Ly, Lz, 6), dtype=np.float32)
        vm_field = np.zeros((Lx, Ly, Lz), dtype=np.float32)

        for (ex, ey, ez) in solid_voxels:
            E = float(E_field[ex, ey, ez])
            nu = float(nu_field[ex, ey, ez])
            D = elasticity_matrix(E, nu)

            nodes = elem_nodes(ex, ey, ez)
            dofs = []
            for n in nodes:
                dofs.extend([n * 3, n * 3 + 1, n * 3 + 2])

            u_e = u[dofs]  # [24]

            # Average displacement at element center
            disp_field[ex, ey, ez, 0] = np.mean(u_e[0::3])
            disp_field[ex, ey, ez, 1] = np.mean(u_e[1::3])
            disp_field[ex, ey, ez, 2] = np.mean(u_e[2::3])

            # Stress at element center (ξ=η=ζ=0)
            coords = NODE_COORDS * self.voxel_size
            dN_dnat = _shape_derivatives(0.0, 0.0, 0.0)
            J = dN_dnat @ coords
            dN_dx = np.linalg.solve(J, dN_dnat)

            stress_voigt = compute_element_stress(D, dN_dx, u_e)
            stress_field[ex, ey, ez] = stress_voigt.astype(np.float32)
            vm_field[ex, ey, ez] = float(von_mises(stress_voigt))

        return FEMResult(
            shape=(Lx, Ly, Lz),
            displacement=disp_field,
            stress=stress_field,
            von_mises=vm_field,
            converged=converged,
            iterations=info_holder["iters"],
            residual=residual,
        )
