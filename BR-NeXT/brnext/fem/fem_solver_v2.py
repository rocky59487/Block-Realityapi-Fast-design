"""
Voxel FEM solver v2 — direct DOF elimination + corner artifact filtering.

Key improvements over v1:
  1. Dirichlet BC via direct DOF elimination instead of penalty method.
     This removes the numerical singularities at anchor-adjacent corners.
  2. Post-process stress with median-filter + Winsorization.
  3. Keep everything else identical to brml.fem.fem_solver for compatibility.
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
from .corner_filter import filter_corner_stress


@dataclass
class FEMResult:
    """FEM solution for a voxel structure."""
    shape: tuple[int, int, int]
    displacement: np.ndarray      # [Lx, Ly, Lz, 3]
    stress: np.ndarray            # [Lx, Ly, Lz, 6] Voigt
    von_mises: np.ndarray         # [Lx, Ly, Lz]
    converged: bool
    iterations: int
    residual: float


class FEMSolverV2:
    """Voxel-based FEM solver with direct DOF elimination.

    Usage:
        solver = FEMSolverV2()
        result = solver.solve(occupancy, anchors, E_field, nu_field, density_field)
    """

    def __init__(self, voxel_size: float = 1.0, cg_tol: float = 1e-6,
                 cg_maxiter: int = 5000):
        self.voxel_size = voxel_size
        self.cg_tol = cg_tol
        self.cg_maxiter = cg_maxiter
        self._ke_cache: dict[tuple[float, float], np.ndarray] = {}

    def solve(
        self,
        occupancy: np.ndarray,
        anchors: np.ndarray,
        E_field: np.ndarray,
        nu_field: np.ndarray,
        density_field: np.ndarray,
    ) -> FEMResult:
        Lx, Ly, Lz = occupancy.shape
        Nx, Ny, Nz = Lx + 1, Ly + 1, Lz + 1
        n_nodes = Nx * Ny * Nz
        n_dof = n_nodes * 3

        def node_id(ix, iy, iz):
            return ix + Nx * (iy + Ny * iz)

        def elem_nodes(ex, ey, ez):
            return [
                node_id(ex, ey, ez),
                node_id(ex + 1, ey, ez),
                node_id(ex + 1, ey + 1, ez),
                node_id(ex, ey + 1, ez),
                node_id(ex, ey, ez + 1),
                node_id(ex + 1, ey, ez + 1),
                node_id(ex + 1, ey + 1, ez + 1),
                node_id(ex, ey + 1, ez + 1),
            ]

        # ── Assembly ──
        rows, cols, vals = [], [], []
        F = np.zeros(n_dof, dtype=np.float64)
        solid_voxels = np.argwhere(occupancy)

        for (ex, ey, ez) in solid_voxels:
            E = float(E_field[ex, ey, ez])
            nu = float(nu_field[ex, ey, ez])
            rho = float(density_field[ex, ey, ez])

            key = (round(E, 1), round(nu, 4))
            if key not in self._ke_cache:
                self._ke_cache[key] = hex8_stiffness(E, nu, self.voxel_size)
            Ke = self._ke_cache[key]
            fe = hex8_body_force(rho, 9.81, self.voxel_size)

            nodes = elem_nodes(ex, ey, ez)
            dofs = []
            for n in nodes:
                dofs.extend([n * 3, n * 3 + 1, n * 3 + 2])

            for i in range(24):
                F[dofs[i]] += fe[i]
                for j in range(24):
                    if abs(Ke[i, j]) > 1e-15:
                        rows.append(dofs[i])
                        cols.append(dofs[j])
                        vals.append(Ke[i, j])

        K = sparse.coo_matrix((vals, (rows, cols)), shape=(n_dof, n_dof)).tocsr()

        # ── Dirichlet BC: direct DOF elimination ──
        fixed_dofs = set()
        for (ex, ey, ez) in np.argwhere(anchors):
            for n in elem_nodes(ex, ey, ez):
                for d in range(3):
                    fixed_dofs.add(n * 3 + d)

        fixed_dofs = np.array(sorted(fixed_dofs), dtype=np.int32)
        all_dofs = np.arange(n_dof, dtype=np.int32)
        free_dofs = np.setdiff1d(all_dofs, fixed_dofs, assume_unique=True)

        # Reduce system
        K_reduced = K[free_dofs][:, free_dofs]
        F_reduced = F[free_dofs]
        # Since u_fixed = 0, no extra RHS term needed.

        # ── Solve reduced system ──
        diag = K_reduced.diagonal()
        diag_inv = np.where(np.abs(diag) > 1e-30, 1.0 / diag, 0.0)
        M = LinearOperator(K_reduced.shape, matvec=lambda x: diag_inv * x)

        u_free = np.zeros(len(free_dofs), dtype=np.float64)
        info_holder = {"iters": 0}

        def callback(xk):
            info_holder["iters"] += 1

        try:
            u_free, info = cg(K_reduced, F_reduced, x0=u_free,
                              rtol=self.cg_tol, maxiter=self.cg_maxiter,
                              M=M, callback=callback)
        except TypeError:
            u_free, info = cg(K_reduced, F_reduced, x0=u_free,
                              tol=self.cg_tol, maxiter=self.cg_maxiter,
                              M=M, callback=callback)

        converged = (info == 0)
        residual = float(np.linalg.norm(K_reduced @ u_free - F_reduced)
                         / (np.linalg.norm(F_reduced) + 1e-30))

        # Expand to full DOF vector
        u = np.zeros(n_dof, dtype=np.float64)
        u[free_dofs] = u_free
        # fixed DOFs remain 0

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
            u_e = u[dofs]

            disp_field[ex, ey, ez, 0] = np.mean(u_e[0::3])
            disp_field[ex, ey, ez, 1] = np.mean(u_e[1::3])
            disp_field[ex, ey, ez, 2] = np.mean(u_e[2::3])

            coords = NODE_COORDS * self.voxel_size
            dN_dnat = _shape_derivatives(0.0, 0.0, 0.0)
            J = dN_dnat @ coords
            dN_dx = np.linalg.solve(J, dN_dnat)
            stress_voigt = compute_element_stress(D, dN_dx, u_e)
            stress_field[ex, ey, ez] = stress_voigt.astype(np.float32)
            vm_field[ex, ey, ez] = float(von_mises(stress_voigt))

        # ── Corner artifact filtering ──
        stress_field, vm_field = filter_corner_stress(
            stress_field, vm_field, occupancy
        )

        return FEMResult(
            shape=(Lx, Ly, Lz),
            displacement=disp_field,
            stress=stress_field,
            von_mises=vm_field,
            converged=converged,
            iterations=info_holder["iters"],
            residual=residual,
        )
