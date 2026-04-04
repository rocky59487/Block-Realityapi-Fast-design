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
    @DisplayName("Chebyshev omega 遞增並收斂")
    void testOmegaIncreasing() {
        float rhoSpec = 0.95f;
        float prev = 1.0f;
        for (int i = 1; i < 20; i++) {
            float omega = PFSFScheduler.computeOmega(i, rhoSpec);
            assertTrue(omega >= prev, "omega 應遞增：iter=" + i +
                    " omega=" + omega + " prev=" + prev);
            prev = omega;
        }
        // 最終值應接近 2/(1+sqrt(1-rho²))
        float theoretical = (float) (2.0 / (1.0 + Math.sqrt(1.0 - rhoSpec * rhoSpec)));
        assertTrue(prev < theoretical + 0.1,
                "omega 應收斂到接近 " + theoretical + "，實際=" + prev);
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
    @DisplayName("頻譜半徑在合理範圍")
    void testSpectralRadius() {
        float rho10 = PFSFScheduler.estimateSpectralRadius(10);
        float rho100 = PFSFScheduler.estimateSpectralRadius(100);
        float rho1000 = PFSFScheduler.estimateSpectralRadius(1000);

        assertTrue(rho10 > 0.8 && rho10 < 1.0, "Lmax=10 rhoSpec=" + rho10);
        assertTrue(rho100 > 0.9 && rho100 < 1.0, "Lmax=100 rhoSpec=" + rho100);
        assertTrue(rho1000 > 0.99 && rho1000 < 1.0, "Lmax=1000 rhoSpec=" + rho1000);

        // 更大的網格 → 更接近 1.0
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
    @DisplayName("Chebyshev 加速收斂性（CPU 模擬）")
    void testChebyshevConvergence() {
        // 模擬 1D Jacobi 在 100 格線性結構上的收斂
        int L = 100;
        float rhoSpec = PFSFScheduler.estimateSpectralRadius(L);

        // 初始殘差
        double residualPlain = simulateConvergence(L, 200, 1.0f);
        double residualCheby = simulateConvergence(L, 200, rhoSpec);

        // Chebyshev 應收斂更快（殘差更低）
        assertTrue(residualCheby < residualPlain,
                "Chebyshev 殘差 (" + residualCheby + ") 應 < 純 Jacobi (" + residualPlain + ")");
    }

    /**
     * 簡化 1D Jacobi 收斂模擬。
     */
    private double simulateConvergence(int L, int steps, float rhoSpec) {
        float[] phi = new float[L];
        float[] phiPrev = new float[L];
        float[] source = new float[L];

        // 源項：每格 1.0，邊界 phi[0] = 0 (anchor)
        for (int i = 1; i < L; i++) source[i] = 1.0f;

        float omega = 1.0f;
        for (int step = 0; step < steps; step++) {
            System.arraycopy(phi, 0, phiPrev, 0, L);

            if (rhoSpec > 0.5f) {
                omega = PFSFScheduler.computeOmega(
                        Math.min(step, 63), rhoSpec);
            }

            for (int i = 1; i < L - 1; i++) {
                float jacobi = (source[i] + phiPrev[i - 1] + phiPrev[i + 1]) / 2.0f;
                phi[i] = omega * (jacobi - phiPrev[i]) + phiPrev[i];
            }
            phi[0] = 0; // anchor
        }

        // 計算殘差
        double residual = 0;
        for (int i = 1; i < L - 1; i++) {
            double r = source[i] + phi[i - 1] + phi[i + 1] - 2.0 * phi[i];
            residual += r * r;
        }
        return Math.sqrt(residual);
    }
}
