"""
8-node hexahedral (brick) finite element for unit-cube voxels.

Each Minecraft voxel maps to one hex8 element with:
  - 8 nodes at corners
  - 3 DOF per node (u_x, u_y, u_z) → 24 DOF per element
  - Trilinear shape functions
  - 2×2×2 Gauss quadrature

Reference: Bathe, "Finite Element Procedures", Chapter 5.
"""
from __future__ import annotations

import numpy as np

# 2×2×2 Gauss quadrature points and weights
_GP = 1.0 / np.sqrt(3.0)
GAUSS_POINTS = np.array([
    [-_GP, -_GP, -_GP], [+_GP, -_GP, -_GP],
    [+_GP, +_GP, -_GP], [-_GP, +_GP, -_GP],
    [-_GP, -_GP, +_GP], [+_GP, -_GP, +_GP],
    [+_GP, +_GP, +_GP], [-_GP, +_GP, +_GP],
], dtype=np.float64)
GAUSS_WEIGHTS = np.ones(8, dtype=np.float64)  # all weights = 1.0 for 2×2×2

# Node local coordinates for unit cube [0,1]³ mapped to [-1,1]³
NODE_COORDS = np.array([
    [0, 0, 0], [1, 0, 0], [1, 1, 0], [0, 1, 0],
    [0, 0, 1], [1, 0, 1], [1, 1, 1], [0, 1, 1],
], dtype=np.float64)


def elasticity_matrix(E: float, nu: float) -> np.ndarray:
    """6×6 isotropic elasticity matrix D (Voigt notation).

    σ = D · ε where ε = [ε_xx, ε_yy, ε_zz, γ_xy, γ_yz, γ_xz]

    Args:
        E:  Young's modulus (Pa)
        nu: Poisson's ratio
    Returns:
        D: [6, 6] elasticity matrix
    """
    c = E / ((1 + nu) * (1 - 2 * nu))
    D = np.zeros((6, 6), dtype=np.float64)

    D[0, 0] = D[1, 1] = D[2, 2] = c * (1 - nu)
    D[0, 1] = D[0, 2] = D[1, 0] = D[1, 2] = D[2, 0] = D[2, 1] = c * nu
    D[3, 3] = D[4, 4] = D[5, 5] = c * (1 - 2 * nu) / 2

    return D


def _shape_functions(xi: float, eta: float, zeta: float) -> np.ndarray:
    """Trilinear shape functions N_i at natural coordinate (ξ, η, ζ) ∈ [-1,1]³.

    Returns: [8] shape function values
    """
    return 0.125 * np.array([
        (1 - xi) * (1 - eta) * (1 - zeta),
        (1 + xi) * (1 - eta) * (1 - zeta),
        (1 + xi) * (1 + eta) * (1 - zeta),
        (1 - xi) * (1 + eta) * (1 - zeta),
        (1 - xi) * (1 - eta) * (1 + zeta),
        (1 + xi) * (1 - eta) * (1 + zeta),
        (1 + xi) * (1 + eta) * (1 + zeta),
        (1 - xi) * (1 + eta) * (1 + zeta),
    ], dtype=np.float64)


def _shape_derivatives(xi: float, eta: float, zeta: float) -> np.ndarray:
    """Derivatives of shape functions w.r.t. natural coordinates.

    Returns: [3, 8] matrix  dN_i/d(ξ,η,ζ)
    """
    return 0.125 * np.array([
        # dN/dξ
        [-(1-eta)*(1-zeta), (1-eta)*(1-zeta), (1+eta)*(1-zeta), -(1+eta)*(1-zeta),
         -(1-eta)*(1+zeta), (1-eta)*(1+zeta), (1+eta)*(1+zeta), -(1+eta)*(1+zeta)],
        # dN/dη
        [-(1-xi)*(1-zeta), -(1+xi)*(1-zeta), (1+xi)*(1-zeta), (1-xi)*(1-zeta),
         -(1-xi)*(1+zeta), -(1+xi)*(1+zeta), (1+xi)*(1+zeta), (1-xi)*(1+zeta)],
        # dN/dζ
        [-(1-xi)*(1-eta), -(1+xi)*(1-eta), -(1+xi)*(1+eta), -(1-xi)*(1+eta),
         (1-xi)*(1-eta), (1+xi)*(1-eta), (1+xi)*(1+eta), (1-xi)*(1+eta)],
    ], dtype=np.float64)


def _B_matrix(dN_dx: np.ndarray) -> np.ndarray:
    """Strain-displacement matrix B (6×24) from shape function derivatives.

    ε = B · u_e  where u_e = [u1x,u1y,u1z, u2x,u2y,u2z, ..., u8x,u8y,u8z]

    Args:
        dN_dx: [3, 8] derivatives of shape functions w.r.t. physical coords
    Returns:
        B: [6, 24]
    """
    B = np.zeros((6, 24), dtype=np.float64)
    for i in range(8):
        col = i * 3
        B[0, col]     = dN_dx[0, i]  # ε_xx = du/dx
        B[1, col + 1] = dN_dx[1, i]  # ε_yy = dv/dy
        B[2, col + 2] = dN_dx[2, i]  # ε_zz = dw/dz
        B[3, col]     = dN_dx[1, i]  # γ_xy = du/dy + dv/dx
        B[3, col + 1] = dN_dx[0, i]
        B[4, col + 1] = dN_dx[2, i]  # γ_yz = dv/dz + dw/dy
        B[4, col + 2] = dN_dx[1, i]
        B[5, col]     = dN_dx[2, i]  # γ_xz = du/dz + dw/dx
        B[5, col + 2] = dN_dx[0, i]
    return B


def hex8_stiffness(E: float, nu: float, size: float = 1.0) -> np.ndarray:
    """Compute 24×24 element stiffness matrix for a unit-cube hex8 element.

    K_e = ∫ B^T · D · B dV  (2×2×2 Gauss quadrature)

    Args:
        E:    Young's modulus (Pa)
        nu:   Poisson's ratio
        size: Edge length of voxel (m), default 1.0
    Returns:
        K_e: [24, 24] symmetric positive semi-definite stiffness matrix
    """
    D = elasticity_matrix(E, nu)

    # Physical node coordinates for cube [0, size]³
    coords = NODE_COORDS * size  # [8, 3]

    K = np.zeros((24, 24), dtype=np.float64)

    for gp, w in zip(GAUSS_POINTS, GAUSS_WEIGHTS):
        xi, eta, zeta = gp

        # Shape function derivatives in natural coords
        dN_dnat = _shape_derivatives(xi, eta, zeta)  # [3, 8]

        # Jacobian: J = dN/dnat · coords → [3, 3]
        J = dN_dnat @ coords
        detJ = np.linalg.det(J)

        # dN/dx = J^{-1} · dN/dnat
        dN_dx = np.linalg.solve(J, dN_dnat)  # [3, 8]

        B = _B_matrix(dN_dx)  # [6, 24]

        K += (B.T @ D @ B) * detJ * w

    return K


def hex8_body_force(density: float, g: float = 9.81, size: float = 1.0) -> np.ndarray:
    """Consistent body force vector for gravity load (downward Y).

    f_e = ∫ N^T · b dV  where b = [0, -ρg, 0]

    Args:
        density: Material density (kg/m³)
        g:       Gravitational acceleration (m/s²)
        size:    Voxel edge length (m)
    Returns:
        f_e: [24] force vector (consistent, not lumped)
    """
    body = np.array([0.0, -density * g, 0.0], dtype=np.float64)
    coords = NODE_COORDS * size

    f = np.zeros(24, dtype=np.float64)

    for gp, w in zip(GAUSS_POINTS, GAUSS_WEIGHTS):
        xi, eta, zeta = gp
        N = _shape_functions(xi, eta, zeta)  # [8]
        dN_dnat = _shape_derivatives(xi, eta, zeta)
        J = dN_dnat @ coords
        detJ = np.linalg.det(J)

        # f += N^T · b · detJ · w
        for i in range(8):
            f[i * 3:i * 3 + 3] += N[i] * body * detJ * w

    return f


def compute_element_stress(D: np.ndarray, dN_dx: np.ndarray,
                           u_e: np.ndarray) -> np.ndarray:
    """Compute stress at a point given element displacements.

    σ = D · B · u_e

    Args:
        D:     [6, 6] elasticity matrix
        dN_dx: [3, 8] shape function derivatives at evaluation point
        u_e:   [24] element nodal displacements
    Returns:
        stress: [6] Voigt stress [σ_xx, σ_yy, σ_zz, τ_xy, τ_yz, τ_xz]
    """
    B = _B_matrix(dN_dx)
    strain = B @ u_e
    return D @ strain


def von_mises(stress: np.ndarray) -> float:
    """Compute von Mises equivalent stress from Voigt stress vector.

    σ_vm = √(σ_xx² + σ_yy² + σ_zz² - σ_xx·σ_yy - σ_yy·σ_zz - σ_xx·σ_zz
             + 3(τ_xy² + τ_yz² + τ_xz²))
    """
    s = stress
    return np.sqrt(
        s[0]**2 + s[1]**2 + s[2]**2
        - s[0]*s[1] - s[1]*s[2] - s[0]*s[2]
        + 3.0 * (s[3]**2 + s[4]**2 + s[5]**2)
    )
