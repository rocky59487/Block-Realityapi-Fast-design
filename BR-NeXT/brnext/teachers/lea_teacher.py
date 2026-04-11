"""Linear Elastic Approximation (LEA) teacher.

Provides fast, physics-structured pre-training signals for each
of the 9 Block Reality structure styles.
"""
from __future__ import annotations

import numpy as np


class LEATeacher:
    """Analytic low-fidelity teacher for structural stress patterns."""

    STYLES = [
        "bridge", "cantilever", "arch", "spiral", "tree",
        "cave", "overhang", "random", "tower",
    ]

    def compute(self, style: str, occupancy: np.ndarray,
                E_field: np.ndarray, density_field: np.ndarray) -> np.ndarray:
        """Return approximate Voigt stress field for the given structure.

        Args:
            style: one of STYLES
            occupancy: bool[Lx, Ly, Lz]
            E_field: float[Lx, Ly, Lz] Pa
            density_field: float[Lx, Ly, Lz] kg/m³
        Returns:
            stress: float[Lx, Ly, Lz, 6] Voigt (Pa), rough approximation
        """
        Lx, Ly, Lz = occupancy.shape
        stress = np.zeros((Lx, Ly, Lz, 6), dtype=np.float32)
        solid = occupancy.astype(bool)
        if not solid.any():
            return stress

        # Average density for body-force term
        rho_avg = float(density_field[solid].mean())
        g = 9.81

        if style in ("tower", "random"):
            # Axial compression approximation: sigma_yy = -rho * g * height_from_top
            for y in range(Ly):
                height_above = Ly - 1 - y
                sigma_yy = -rho_avg * g * height_above
                mask_y = solid[:, y, :]
                stress[:, y, :, 1] = np.where(mask_y, sigma_yy, 0.0)

        elif style in ("cantilever", "overhang"):
            # Cantilever beam: root moment causes sigma_xx varying with y
            # Simple proxy: maximum at root, linear decrease toward tip
            # Find root (minimum x with solid) and tip (maximum x)
            xs = np.where(solid.any(axis=(1, 2)))[0]
            if len(xs) > 0:
                root_x, tip_x = xs[0], xs[-1]
                L_span = max(1, tip_x - root_x)
                for x in range(Lx):
                    frac = 1.0 - abs(x - root_x) / L_span
                    frac = max(0.0, min(1.0, frac))
                    # Bending stress sign: tension on top, compression on bottom
                    for y in range(Ly):
                        y_rel = (y - Ly / 2.0) / max(Ly / 2.0, 1.0)
                        sigma_bend = 50e3 * frac * y_rel * rho_avg * g
                        mask = solid[x, y, :]
                        stress[x, y, :, 0] = np.where(mask, sigma_bend, 0.0)

        elif style in ("bridge", "arch"):
            # Arch / bridge: mostly compression along the span
            xs = np.where(solid.any(axis=(1, 2)))[0]
            if len(xs) > 0:
                mid_x = (xs[0] + xs[-1]) // 2
                for x in range(Lx):
                    dist = abs(x - mid_x) / max(1, xs[-1] - xs[0])
                    sigma_comp = -30e3 * (1.0 - dist) * rho_avg * g
                    mask = solid[x, :, :]
                    stress[x, :, :, 1] = np.where(mask, sigma_comp, 0.0)

        elif style in ("spiral", "tree", "cave"):
            # Complex geometry: use a crude hydrostatic-like pressure
            # plus torsion proxy based on distance from centroid
            coords = np.argwhere(solid)
            centroid = coords.mean(axis=0) if len(coords) > 0 else np.array([Lx / 2, Ly / 2, Lz / 2])
            for x in range(Lx):
                for y in range(Ly):
                    for z in range(Lz):
                        if not solid[x, y, z]:
                            continue
                        h = Ly - 1 - y
                        r = np.sqrt((x - centroid[0]) ** 2 + (z - centroid[2]) ** 2)
                        sigma_hydro = -rho_avg * g * h * 0.5
                        sigma_torsion = 10e3 * r * rho_avg * g / max(Lx, Lz)
                        stress[x, y, z, 1] = sigma_hydro
                        stress[x, y, z, 0] = sigma_torsion * 0.3
                        stress[x, y, z, 2] = sigma_torsion * 0.1

        # Ensure no NaN/Inf
        stress = np.nan_to_num(stress, nan=0.0, posinf=0.0, neginf=0.0)
        return stress
