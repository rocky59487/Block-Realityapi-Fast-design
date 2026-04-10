package com.blockreality.api.physics.em;

import com.blockreality.api.physics.solver.DiffusionRegion;
import net.minecraft.core.BlockPos;

/**
 * Zienkiewicz-Zhu (ZZ) Superconvergent Patch Recovery for EM gradient.
 *
 * <p>Recovers ∇φ at a voxel center via a 3×3×3 sampling patch using the
 * superconvergent patch recovery method (Zienkiewicz & Zhu, 1992).
 *
 * <p>On a Cartesian voxel grid, the ZZ system matrix M = Σ P(x_s)^T P(x_s)
 * with basis P = [1, x, y, z] is exactly diagonal due to the symmetric
 * arrangement of sample points. M⁻¹ is precomputed at class-load time.
 *
 * <p>Reference: O.C. Zienkiewicz and J.Z. Zhu, "The superconvergent patch
 * recovery and a posteriori error estimates", IJNME 33, 1331–1364 (1992).
 */
public final class EmZZPatch {

    private EmZZPatch() {}

    /**
     * M⁻¹ coefficients for the 4×4 diagonal ZZ system matrix.
     * Basis: P = [1, x, y, z], sample points at {-1,0,+1}³ (27 points).
     *
     * <p>M[0,0] = Σ 1² = 27 → M_inv[0] = 1/27
     * <p>M[1,1] = Σ x_s² = 9×((-1)² + 0² + (+1)²) / 3 = 18 → M_inv[1] = 1/18
     * <p>M[2,2] = M[3,3] = 1/18 (symmetric in y, z)
     */
    static final float[] M_INV = precomputeZZMatrix();

    private static float[] precomputeZZMatrix() {
        // 27 sample points at {-1,0,+1}³
        double m00 = 0, m11 = 0, m22 = 0, m33 = 0;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    m00 += 1.0;
                    m11 += dx * dx;
                    m22 += dy * dy;
                    m33 += dz * dz;
                }
            }
        }
        // Diagonal: M = diag(m00, m11, m22, m33) → M⁻¹ = diag(1/m00, ...)
        return new float[]{
            (float) (1.0 / m00),  // 1/27
            (float) (1.0 / m11),  // 1/18
            (float) (1.0 / m22),  // 1/18
            (float) (1.0 / m33),  // 1/18
        };
    }

    /**
     * Recovers the gradient of φ at {@code pos} via ZZ patch recovery.
     *
     * @param pos    center voxel position (world coordinates)
     * @param phi    flat phi array from DiffusionRegion
     * @param region the DiffusionRegion containing pos
     * @return float[4] = {a0, ax, ay, az} — polynomial coefficients;
     *         [ax, ay, az] approximates ∇φ at pos center
     */
    public static float[] recoverGradient(BlockPos pos, float[] phi, DiffusionRegion region) {
        // a = M⁻¹ × (Σ_s φ_s × P(x_s)^T)
        // RHS accumulation: b[k] = Σ_s φ_s × P_k(x_s)
        double b0 = 0, b1 = 0, b2 = 0, b3 = 0;

        for (int dz = -1; dz <= 1; dz++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    BlockPos nb = pos.offset(dx, dy, dz);
                    int idx = region.flatIndex(nb);
                    float phi_s = (idx >= 0) ? phi[idx] : 0f;  // Dirichlet 0 outside domain
                    b0 += phi_s;            // P_0 = 1
                    b1 += phi_s * dx;       // P_1 = x
                    b2 += phi_s * dy;       // P_2 = y
                    b3 += phi_s * dz;       // P_3 = z
                }
            }
        }

        // a = M⁻¹ × b  (diagonal multiply)
        return new float[]{
            (float) (M_INV[0] * b0),  // a0 (mean value)
            (float) (M_INV[1] * b1),  // ax ≈ ∂φ/∂x
            (float) (M_INV[2] * b2),  // ay ≈ ∂φ/∂y
            (float) (M_INV[3] * b3),  // az ≈ ∂φ/∂z
        };
    }
}
