"""Tests for FEMSolverV2 and corner filtering."""
import numpy as np

from brnext.fem.fem_solver_v2 import FEMSolverV2
from brnext.fem.corner_filter import filter_corner_stress


def _make_tower(L: int = 8):
    occ = np.ones((L, L, L), dtype=bool)
    anchors = np.zeros((L, L, L), dtype=bool)
    anchors[:, 0, :] = True
    E = np.full((L, L, L), 30e9, dtype=np.float64)
    nu = np.full((L, L, L), 0.2, dtype=np.float64)
    rho = np.full((L, L, L), 2400.0, dtype=np.float64)
    return occ, anchors, E, nu, rho


def test_fem_solver_v2_converges():
    occ, anchors, E, nu, rho = _make_tower(6)
    solver = FEMSolverV2()
    result = solver.solve(occ, anchors, E, nu, rho)
    assert result.converged
    assert result.stress.shape == (6, 6, 6, 6)
    assert result.von_mises.shape == (6, 6, 6)


def test_corner_filter_clips_extremes():
    L = 8
    occ = np.ones((L, L, L), dtype=bool)
    stress = np.ones((L, L, L, 6), dtype=np.float32) * 1e5
    vm = np.ones((L, L, L), dtype=np.float32) * 1e5
    # Inject an obvious outlier at a corner
    stress[1, 1, 1, 0] = 1e12
    vm[1, 1, 1] = 1e12

    fs, fvm = filter_corner_stress(stress, vm, occ, clip_factor=3.0)
    assert fs[1, 1, 1, 0] < 1e11  # should be clipped down by global Winsorization
    assert fvm[1, 1, 1] < 1e11
    # Non-outliers unchanged
    assert np.allclose(fs[2, 2, 2], stress[2, 2, 2])
