package com.blockreality.api.physics.pfsf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PFSFScheduler Chebyshev 排程與安全機制測試。
 */
class PFSFSchedulerTest {

    @Test
    @DisplayName("Chebyshev omega 序列：iter=0 → 1.0")
    void testOmegaIter0() {
        assertEquals(1.0f, PFSFScheduler.computeOmega(0, 0.95f));
    }

    @Test
    @DisplayName("Chebyshev omega 序列收斂到理論極限")
    void testOmegaConvergesToLimit() {
        float rhoSpec = 0.95f;

        // Chebyshev omega 序列從 1.0 開始，最終收斂到
        // omega_opt = 2 / (1 + sqrt(1 - rho^2))
        float theoretical = (float) (2.0 / (1.0 + Math.sqrt(1.0 - rhoSpec * rhoSpec)));

        // 序列的所有值應在 [1.0, 2.0) 範圍內（A6-fix: clamp ≤ 1.98）
        for (int i = 0; i < 30; i++) {
            float omega = PFSFScheduler.computeOmega(i, rhoSpec);
            assertTrue(omega >= 1.0f, "omega 應 ≥ 1.0：iter=" + i + " omega=" + omega);
            assertTrue(omega <= 1.98f, "omega 應 ≤ 1.98：iter=" + i + " omega=" + omega);
            assertFalse(Float.isNaN(omega), "omega 不應為 NaN：iter=" + i);
        }

        // 後期值應接近理論極限
        float omegaLate = PFSFScheduler.computeOmega(25, rhoSpec);
        assertTrue(Math.abs(omegaLate - theoretical) < 0.15,
                "omega 後期應接近理論值 " + theoretical + "，實際=" + omegaLate);
    }

    @Test
    @DisplayName("precomputeOmegaTable 與 computeOmega 一致")
    void testOmegaTableConsistency() {
        float rhoSpec = 0.9f;
        float[] table = PFSFScheduler.precomputeOmegaTable(rhoSpec);

        for (int i = 0; i < 30; i++) {
            float computed = PFSFScheduler.computeOmega(i, rhoSpec);
            assertEquals(computed, table[i], 1e-5,
                    "iter=" + i + " table=" + table[i] + " computed=" + computed);
        }
    }

    @Test
    @DisplayName("頻譜半徑在合理範圍（含 SAFETY_MARGIN）")
    void testSpectralRadius() {
        float rho10 = PFSFScheduler.estimateSpectralRadius(10);
        float rho100 = PFSFScheduler.estimateSpectralRadius(100);
        float rho1000 = PFSFScheduler.estimateSpectralRadius(1000);

        // estimateSpectralRadius = cos(π/Lmax) × SAFETY_MARGIN (0.95)
        // 所以最大值 ≈ 1.0 × 0.95 = 0.95
        assertTrue(rho10 > 0.8 && rho10 < 1.0, "Lmax=10 rhoSpec=" + rho10);
        assertTrue(rho100 > 0.9 && rho100 < 1.0, "Lmax=100 rhoSpec=" + rho100);
        // Lmax=1000: cos(π/1000)*0.95 ≈ 0.9499（因 SAFETY_MARGIN 不會超過 0.95）
        assertTrue(rho1000 > 0.94 && rho1000 < 1.0, "Lmax=1000 rhoSpec=" + rho1000);

        // 更大的網格 → 更接近上限
        assertTrue(rho1000 > rho100);
        assertTrue(rho100 > rho10);
    }

    @Test
    @DisplayName("Lmax=1 的頻譜半徑為 fallback 值")
    void testSpectralRadiusSmall() {
        float rho = PFSFScheduler.estimateSpectralRadius(1);
        assertEquals(0.5f, rho);
    }

    @Test
    @DisplayName("Chebyshev 加速收斂性（CPU 模擬，3-term recurrence）")
    void testChebyshevConvergence() {
        // 模擬 1D Jacobi 在 50 格線性結構上的收斂
        int L = 50;
        float rhoSpec = PFSFScheduler.estimateSpectralRadius(L);

        // 純 Jacobi（omega 固定 = 1.0）
        double residualPlain = simulateJacobi(L, 100);
        // Chebyshev 加速（3-term recurrence）
        double residualCheby = simulateChebyshev(L, 100, rhoSpec);

        // Chebyshev 應收斂更快（殘差更低）
        assertTrue(residualCheby < residualPlain,
                "Chebyshev 殘差 (" + residualCheby + ") 應 < 純 Jacobi (" + residualPlain + ")");
    }

    /**
     * 純 Jacobi 迭代（omega = 1.0，無加速）。
     */
    private double simulateJacobi(int L, int steps) {
        float[] phi = new float[L];
        float[] phiNew = new float[L];
        float[] source = new float[L];
        for (int i = 1; i < L; i++) source[i] = 1.0f;

        for (int step = 0; step < steps; step++) {
            for (int i = 1; i < L - 1; i++) {
                phiNew[i] = (source[i] + phi[i - 1] + phi[i + 1]) / 2.0f;
            }
            phiNew[0] = 0;
            System.arraycopy(phiNew, 0, phi, 0, L);
        }
        return computeResidual(phi, source, L);
    }

    /**
     * Chebyshev 半迭代（正確的 3-term recurrence）。
     *
     * 公式：phi^{k+1} = omega_k * (J(phi^k) - phi^k) + phi^k
     * 其中 J(phi) 是 Jacobi 更新。
     * 關鍵：每步都用 phiPrev（而非原地覆寫），然後 swap。
     */
    private double simulateChebyshev(int L, int steps, float rhoSpec) {
        float[] phi = new float[L];
        float[] phiPrev = new float[L];
        float[] phiNew = new float[L];
        float[] source = new float[L];
        for (int i = 1; i < L; i++) source[i] = 1.0f;

        for (int step = 0; step < steps; step++) {
            float omega = PFSFScheduler.computeOmega(Math.min(step, 31), rhoSpec);

            for (int i = 1; i < L - 1; i++) {
                float jacobi = (source[i] + phiPrev[i - 1] + phiPrev[i + 1]) / 2.0f;
                phiNew[i] = omega * (jacobi - phiPrev[i]) + phiPrev[i];
                // Clamp 防止 NaN（與 shader 一致）
                if (Float.isNaN(phiNew[i]) || phiNew[i] > 1e7f) phiNew[i] = phiPrev[i];
            }
            phiNew[0] = 0;

            // 3-term swap: phiPrev ← phi, phi ← phiNew（非原地覆寫！）
            float[] temp = phiPrev;
            phiPrev = phi;
            phi = phiNew;
            phiNew = temp;
        }
        return computeResidual(phi, source, L);
    }

    private double computeResidual(float[] phi, float[] source, int L) {
        double residual = 0;
        for (int i = 1; i < L - 1; i++) {
            double r = source[i] + phi[i - 1] + phi[i + 1] - 2.0 * phi[i];
            residual += r * r;
        }
        return Math.sqrt(residual);
    }
}
