"""FEM teacher wrapper around FEMSolverV2."""
from __future__ import annotations

from brnext.fem import FEMSolverV2


def solve_fem(occupancy, anchors, E_field, nu_field, density_field, *, cg_tol=1e-6, cg_maxiter=5000):
    """Run FEMSolverV2 and return result."""
    solver = FEMSolverV2(voxel_size=1.0, cg_tol=cg_tol, cg_maxiter=cg_maxiter)
    return solver.solve(occupancy, anchors, E_field, nu_field, density_field)
