package com.blockreality.api.physics.pfsf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.blockreality.api.physics.pfsf.PFSFConstants.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * CPU 參考 Jacobi 求解器 — 作為 GPU shader 的 ground truth 比對。
 * 同時驗證 PFSF 的基本物理正確性。
 */
class PFSFJacobiCPUTest {

    // ═══════════════════════════════════════════════════════════════
    //  CPU Reference Jacobi
    // ═══════════════════════════════════════════════════════════════

    /**
     * CPU 端 Jacobi 一步迭代（與 jacobi_smooth.comp.glsl 邏輯一致）。
     */
    static float[] jacobiStepCPU(float[] phiPrev, float[] source,
                                  float[] conductivity, byte[] type,
                                  int Lx, int Ly, int Lz, float omega) {
        int N = Lx * Ly * Lz;
        float[] phi = new float[N];

        for (int z = 0; z < Lz; z++) {
            for (int y = 0; y < Ly; y++) {
                for (int x = 0; x < Lx; x++) {
                    int i = x + Lx * (y + Ly * z);

                    // Anchor → 0
                    if (type[i] == VOXEL_ANCHOR) { phi[i] = 0; continue; }
                    // Air → 0
                    if (type[i] == VOXEL_AIR) { phi[i] = 0; continue; }

                    // 6 neighbors
                    int[][] nbOff = {
                            {x > 0 ? x - 1 : x, y, z},        // -X
                            {x < Lx - 1 ? x + 1 : x, y, z},   // +X
                            {x, y > 0 ? y - 1 : y, z},         // -Y
                            {x, y < Ly - 1 ? y + 1 : y, z},    // +Y
                            {x, y, z > 0 ? z - 1 : z},         // -Z
                            {x, y, z < Lz - 1 ? z + 1 : z},    // +Z
                    };

                    float sumSigma = 0;
                    float sumNeighbor = 0;

                    for (int d = 0; d < 6; d++) {
                        float s = conductivity[i * 6 + d];
                        if (s > 0) {
                            int j = nbOff[d][0] + Lx * (nbOff[d][1] + Ly * nbOff[d][2]);
                            sumSigma += s;
                            sumNeighbor += s * phiPrev[j];
                        }
                    }

                    float phiJacobi;
                    if (sumSigma > 0) {
                        phiJacobi = (source[i] + sumNeighbor) / sumSigma;
                    } else {
                        phiJacobi = phiPrev[i] + source[i];
                    }

                    // Chebyshev extrapolation
                    phi[i] = omega * (phiJacobi - phiPrev[i]) + phiPrev[i];
                }
            }
        }
        return phi;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("3×1×1 線性：左端 anchor → phi 向右遞增")
    void testLinear3x1x1() {
        int Lx = 3, Ly = 1, Lz = 1;
        int N = Lx * Ly * Lz;

        float[] source = {0, 1000, 1000}; // 自重
        byte[] type = {VOXEL_ANCHOR, VOXEL_SOLID, VOXEL_SOLID};
        float[] cond = new float[N * 6];

        // 水平連通：sigma=10 for +X/-X between adjacent voxels
        // idx 0 (+X to 1): cond[0*6+1] = 10
        cond[0 * 6 + 1] = 10; // 0 → 1
        cond[1 * 6 + 0] = 10; // 1 → 0
        cond[1 * 6 + 1] = 10; // 1 → 2
        cond[2 * 6 + 0] = 10; // 2 → 1

        float[] phi = new float[N];
        for (int step = 0; step < 100; step++) {
            phi = jacobiStepCPU(phi, source, cond, type, Lx, Ly, Lz, 1.0f);
        }

        assertEquals(0, phi[0], 1e-3, "Anchor phi=0");
        assertTrue(phi[1] > 0, "phi[1] > 0");
        assertTrue(phi[2] > phi[1], "phi 向右遞增");
    }

    @Test
    @DisplayName("5×1×1 懸臂：phi 由外而內遞增")
    void testCantilever5() {
        int Lx = 5, Ly = 1, Lz = 1;
        int N = Lx * Ly * Lz;

        float[] source = new float[N];
        byte[] type = new byte[N];
        float[] cond = new float[N * 6];

        type[0] = VOXEL_ANCHOR;
        for (int i = 1; i < N; i++) {
            type[i] = VOXEL_SOLID;
            source[i] = 1000; // 自重
        }

        // 相鄰水平傳導率
        for (int i = 0; i < N - 1; i++) {
            cond[i * 6 + 1] = 10; // +X
            cond[(i + 1) * 6 + 0] = 10; // -X
        }

        float[] phi = new float[N];
        for (int step = 0; step < 200; step++) {
            phi = jacobiStepCPU(phi, source, cond, type, Lx, Ly, Lz, 1.0f);
        }

        // 驗證 phi 單調遞增
        assertEquals(0, phi[0], 1e-3);
        for (int i = 1; i < N - 1; i++) {
            assertTrue(phi[i + 1] >= phi[i] - 1e-3,
                    "phi 應遞增：phi[" + i + "]=" + phi[i] + " phi[" + (i + 1) + "]=" + phi[i + 1]);
        }
        assertTrue(phi[4] > phi[0], "末端 phi 應 > 錨點");
    }

    @Test
    @DisplayName("5×5×1 平台：底部 anchor → phi 向上遞增")
    void testPlatform5x5() {
        int Lx = 5, Ly = 5, Lz = 1;
        int N = Lx * Ly * Lz;

        float[] source = new float[N];
        byte[] type = new byte[N];
        float[] cond = new float[N * 6];

        // 底行為 anchor
        for (int x = 0; x < Lx; x++) {
            type[x + Lx * (0 + Ly * 0)] = VOXEL_ANCHOR;
        }

        // 其他為 solid
        for (int y = 1; y < Ly; y++) {
            for (int x = 0; x < Lx; x++) {
                int i = x + Lx * (y + Ly * 0);
                type[i] = VOXEL_SOLID;
                source[i] = 500;
            }
        }

        // 垂直傳導率
        for (int y = 0; y < Ly - 1; y++) {
            for (int x = 0; x < Lx; x++) {
                int i = x + Lx * (y + Ly * 0);
                int j = x + Lx * ((y + 1) + Ly * 0);
                cond[i * 6 + 3] = 30; // +Y
                cond[j * 6 + 2] = 30; // -Y
            }
        }

        float[] phi = new float[N];
        for (int step = 0; step < 200; step++) {
            phi = jacobiStepCPU(phi, source, cond, type, Lx, Ly, Lz, 1.0f);
        }

        // 底部 anchor phi=0
        for (int x = 0; x < Lx; x++) {
            assertEquals(0, phi[x], 1e-3);
        }
        // 頂部 phi > 0
        for (int x = 0; x < Lx; x++) {
            int topIdx = x + Lx * (4);
            assertTrue(phi[topIdx] > 0, "頂部 phi > 0");
        }
    }

    @Test
    @DisplayName("迭代收斂性：50 步後變化量 < epsilon")
    void testConvergence() {
        int Lx = 3, Ly = 1, Lz = 1;
        int N = Lx * Ly * Lz;

        float[] source = {0, 100, 100};
        byte[] type = {VOXEL_ANCHOR, VOXEL_SOLID, VOXEL_SOLID};
        float[] cond = new float[N * 6];
        cond[0 * 6 + 1] = 10; cond[1 * 6 + 0] = 10;
        cond[1 * 6 + 1] = 10; cond[2 * 6 + 0] = 10;

        float[] phi = new float[N];
        for (int step = 0; step < 50; step++) {
            phi = jacobiStepCPU(phi, source, cond, type, Lx, Ly, Lz, 1.0f);
        }

        float[] phiPrev = phi.clone();
        phi = jacobiStepCPU(phi, source, cond, type, Lx, Ly, Lz, 1.0f);

        double maxDiff = 0;
        for (int i = 0; i < N; i++) {
            maxDiff = Math.max(maxDiff, Math.abs(phi[i] - phiPrev[i]));
        }
        assertTrue(maxDiff < 1.0, "50 步後應接近收斂，maxDiff=" + maxDiff);
    }

    @Test
    @DisplayName("雙柱平台：荷載均分")
    void testDualColumnLoadSharing() {
        // 3×3×1 平台：左下(0,0,0)和右下(2,0,0)為 anchor
        // 上方 3 格(0,2,0)~(2,2,0) 有荷載
        int Lx = 3, Ly = 3, Lz = 1;
        int N = Lx * Ly * Lz;

        float[] source = new float[N];
        byte[] type = new byte[N];
        float[] cond = new float[N * 6];

        // Anchors
        type[0] = VOXEL_ANCHOR; // (0,0,0)
        type[2] = VOXEL_ANCHOR; // (2,0,0)

        // All other solid
        for (int y = 0; y < Ly; y++) {
            for (int x = 0; x < Lx; x++) {
                int i = x + Lx * y;
                if (type[i] == 0) type[i] = VOXEL_SOLID;
                if (y > 0) source[i] = 1000;
            }
        }

        // Conductivity: vertical and horizontal
        for (int y = 0; y < Ly; y++) {
            for (int x = 0; x < Lx; x++) {
                int i = x + Lx * y;
                if (x < Lx - 1) { cond[i * 6 + 1] = 30; cond[(i + 1) * 6 + 0] = 30; }
                if (y < Ly - 1) { int j = x + Lx * (y + 1); cond[i * 6 + 3] = 30; cond[j * 6 + 2] = 30; }
            }
        }

        float[] phi = new float[N];
        for (int step = 0; step < 500; step++) {
            phi = jacobiStepCPU(phi, source, cond, type, Lx, Ly, Lz, 1.0f);
        }

        // 左柱上方和右柱上方的 phi 應相近（對稱結構）
        float phiLeft = phi[0 + Lx * 1]; // (0,1,0)
        float phiRight = phi[2 + Lx * 1]; // (2,1,0)
        assertEquals(phiLeft, phiRight, phiLeft * 0.2,
                "對稱結構左右柱 phi 應相近：left=" + phiLeft + " right=" + phiRight);
    }
}
