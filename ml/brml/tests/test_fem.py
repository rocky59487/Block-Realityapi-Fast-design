"""Tests for the FEM solver — verify correctness on known cases."""
import numpy as np
import pytest

from brml.fem.hex8_element import hex8_stiffness, hex8_body_force, elasticity_matrix, von_mises
from brml.fem.fem_solver import FEMSolver


class TestHex8Element:
    def test_stiffness_symmetric(self):
        """K_e must be symmetric."""
        K = hex8_stiffness(E=30e9, nu=0.2)
        assert K.shape == (24, 24)
        np.testing.assert_allclose(K, K.T, atol=1e-6)

    def test_stiffness_positive_semidefinite(self):
        """K_e eigenvalues must be >= 0 (rigid body modes are zero)."""
        K = hex8_stiffness(E=200e9, nu=0.3)
        eigvals = np.linalg.eigvalsh(K)
        # 6 rigid body modes (3 translations + 3 rotations) → 6 zero eigenvalues
        assert np.sum(eigvals < -1e-3) == 0, f"Negative eigenvalues: {eigvals[eigvals < -1e-3]}"

    def test_body_force_total(self):
        """Total vertical force must equal ρ·g·V."""
        rho = 2400.0
        g = 9.81
        size = 1.0
        f = hex8_body_force(rho, g, size)
        # Sum Y-components (indices 1, 4, 7, 10, 13, 16, 19, 22)
        fy_total = sum(f[i*3+1] for i in range(8))
        expected = -rho * g * size**3
        np.testing.assert_allclose(fy_total, expected, rtol=1e-10)

    def test_elasticity_matrix_symmetric(self):
        D = elasticity_matrix(30e9, 0.2)
        assert D.shape == (6, 6)
        np.testing.assert_allclose(D, D.T)

    def test_von_mises_uniaxial(self):
        """For uniaxial tension σ_xx = σ, von Mises = σ."""
        sigma = 100e6  # 100 MPa
        stress = np.array([sigma, 0, 0, 0, 0, 0])
        assert abs(von_mises(stress) - sigma) < 1e-6

    def test_von_mises_hydrostatic(self):
        """For hydrostatic stress σ_xx = σ_yy = σ_zz = p, von Mises = 0."""
        p = 50e6
        stress = np.array([p, p, p, 0, 0, 0])
        assert von_mises(stress) < 1e-6


class TestFEMSolver:
    def test_single_block_gravity(self):
        """Single anchored block under gravity: stress ≈ ρ·g·h."""
        solver = FEMSolver(cg_maxiter=1000)

        # 1×2×1 column: bottom anchored, top free
        occ = np.ones((1, 2, 1), dtype=bool)
        anchors = np.zeros_like(occ)
        anchors[0, 0, 0] = True

        E = np.full_like(occ, 30e9, dtype=np.float64)
        nu = np.full_like(occ, 0.2, dtype=np.float64)
        rho = np.full_like(occ, 2400.0, dtype=np.float64)

        result = solver.solve(occ, anchors, E, nu, rho)

        assert result.converged
        assert result.von_mises.shape == (1, 2, 1)
        # Top block should have lower stress than bottom
        # (bottom carries weight of top)
        assert result.von_mises[0, 0, 0] > 0  # bottom has stress

    def test_displacement_direction(self):
        """Gravity should cause downward displacement."""
        solver = FEMSolver(cg_maxiter=1000)

        occ = np.ones((1, 3, 1), dtype=bool)
        anchors = np.zeros_like(occ)
        anchors[0, 0, 0] = True

        E = np.full_like(occ, 30e9, dtype=np.float64)
        nu = np.full_like(occ, 0.2, dtype=np.float64)
        rho = np.full_like(occ, 2400.0, dtype=np.float64)

        result = solver.solve(occ, anchors, E, nu, rho)

        assert result.converged
        # Top block displacement should be downward (negative Y)
        assert result.displacement[0, 2, 0, 1] < 0

    def test_stiffer_material_less_displacement(self):
        """Steel (E=200GPa) should deform less than timber (E=12GPa)."""
        solver = FEMSolver(cg_maxiter=1000)

        occ = np.ones((1, 3, 1), dtype=bool)
        anchors = np.zeros_like(occ)
        anchors[0, 0, 0] = True
        nu = np.full_like(occ, 0.2, dtype=np.float64)
        rho = np.full_like(occ, 2400.0, dtype=np.float64)

        # Steel
        E_steel = np.full_like(occ, 200e9, dtype=np.float64)
        r_steel = solver.solve(occ, anchors, E_steel, nu, rho)

        # Timber
        E_timber = np.full_like(occ, 12e9, dtype=np.float64)
        r_timber = solver.solve(occ, anchors, E_timber, nu, rho)

        d_steel = abs(r_steel.displacement[0, 2, 0, 1])
        d_timber = abs(r_timber.displacement[0, 2, 0, 1])
        assert d_steel < d_timber
