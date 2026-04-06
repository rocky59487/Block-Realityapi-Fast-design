package com.blockreality.api.physics.pfsf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v2 新功能接線測試：風壓、Timoshenko、自適應迭代、L2 降採樣。
 */
class PFSFv2WiringTest {

    // ═══════ 風壓 (Eurocode 1) ═══════

    @Test
    @DisplayName("風壓：零風速 → 零源項")
    void testWindPressureZeroWind() {
        assertEquals(0.0f, PFSFSourceBuilder.computeWindPressure(0, 2400, true), 1e-6f);
    }

    @Test
    @DisplayName("風壓：未暴露面 → 零源項")
    void testWindPressureNotExposed() {
        assertEquals(0.0f, PFSFSourceBuilder.computeWindPressure(20, 2400, false), 1e-6f);
    }

    @Test
    @DisplayName("風壓：與風速平方成正比")
    void testWindPressureScalesWithSpeedSquared() {
        float p10 = PFSFSourceBuilder.computeWindPressure(10, 2400, true);
        float p20 = PFSFSourceBuilder.computeWindPressure(20, 2400, true);
        assertEquals(4.0f, p20 / p10, 0.01f, "Wind pressure should scale with v²");
    }

    @Test
    @DisplayName("風壓：正常值為正數")
    void testWindPressurePositive() {
        float p = PFSFSourceBuilder.computeWindPressure(15, 2400, true);
        assertTrue(p > 0, "Wind pressure should be positive for exposed face with wind");
    }

    // ═══════ Timoshenko 力矩 ═══════

    @Test
    @DisplayName("Timoshenko：arm=0 → factor=1.0")
    void testTimoshenkoZeroArm() {
        float factor = PFSFSourceBuilder.computeTimoshenkoMomentFactor(1, 5, 0, 30, 0.2f);
        assertEquals(1.0f, factor, 1e-5f);
    }

    @Test
    @DisplayName("Timoshenko：factor 隨 arm 增大")
    void testTimoshenkoIncreaseWithArm() {
        float f5 = PFSFSourceBuilder.computeTimoshenkoMomentFactor(1, 3, 5, 30, 0.2f);
        float f10 = PFSFSourceBuilder.computeTimoshenkoMomentFactor(1, 3, 10, 30, 0.2f);
        assertTrue(f10 > f5, "Factor should increase with arm: f5=" + f5 + " f10=" + f10);
    }

    @Test
    @DisplayName("Timoshenko：factor 上限 ≤ 11")
    void testTimoshenkoFactorCapped() {
        float f = PFSFSourceBuilder.computeTimoshenkoMomentFactor(1, 1, 1000, 30, 0.2f);
        assertTrue(f <= 11.0f, "Factor should be capped: " + f);
    }

    @Test
    @DisplayName("Timoshenko：sectionHeight=0 → factor=1.0")
    void testTimoshenkoZeroHeight() {
        float f = PFSFSourceBuilder.computeTimoshenkoMomentFactor(1, 0, 10, 30, 0.2f);
        assertEquals(1.0f, f, 1e-5f);
    }

    // ═══════ 自適應迭代 Macro-block ═══════

    @Test
    @DisplayName("Macro-block：高殘差 → active")
    void testMacroBlockActiveHighResidual() {
        float[] residuals = {0.1f, 0.001f, 0.5f};
        assertTrue(PFSFScheduler.isMacroBlockActive(residuals, 0));
        assertTrue(PFSFScheduler.isMacroBlockActive(residuals, 2));
    }

    @Test
    @DisplayName("Macro-block：低殘差 → inactive")
    void testMacroBlockInactiveLowResidual() {
        float[] residuals = {1e-5f, 1e-6f};
        assertFalse(PFSFScheduler.isMacroBlockActive(residuals, 0));
        assertFalse(PFSFScheduler.isMacroBlockActive(residuals, 1));
    }

    @Test
    @DisplayName("Macro-block：null 殘差 → 保守 active")
    void testMacroBlockActiveWhenNull() {
        assertTrue(PFSFScheduler.isMacroBlockActive(null, 0));
    }

    @Test
    @DisplayName("ActiveRatio：全部活躍 → 1.0")
    void testActiveRatioAllActive() {
        float[] residuals = {1.0f, 0.5f, 0.2f};
        assertEquals(1.0f, PFSFScheduler.getActiveRatio(residuals), 1e-5f);
    }

    @Test
    @DisplayName("ActiveRatio：部分收斂")
    void testActiveRatioPartial() {
        float[] residuals = {1.0f, 1e-5f, 0.5f, 1e-6f};
        assertEquals(0.5f, PFSFScheduler.getActiveRatio(residuals), 1e-5f);
    }

    // ═══════ L2 降採樣 ═══════

    @Test
    @DisplayName("L2 降採樣：均勻 σ 不變")
    void testL2DownsampleUniform() {
        int fLx = 4, fLy = 4, fLz = 4;
        int cLx = 2, cLy = 2, cLz = 2;
        int fN = fLx * fLy * fLz;
        int cN = cLx * cLy * cLz;

        float[] fineCond = new float[fN * 6];
        byte[] fineType = new byte[fN];
        for (int i = 0; i < fN; i++) {
            fineType[i] = PFSFConstants.VOXEL_SOLID;
            for (int d = 0; d < 6; d++) fineCond[d * fN + i] = 3.0f;
        }

        float[] outCond = new float[cN * 6];
        byte[] outType = new byte[cN];
        PFSFDataBuilder.downsample(fineCond, fineType, fLx, fLy, fLz, cLx, cLy, cLz, outCond, outType);

        for (int ci = 0; ci < cN; ci++) {
            assertEquals(PFSFConstants.VOXEL_SOLID, outType[ci]);
            for (int d = 0; d < 6; d++) {
                assertEquals(3.0f, outCond[d * cN + ci], 1e-5f,
                        "Uniform conductivity should average to same: ci=" + ci + " d=" + d);
            }
        }
    }

    @Test
    @DisplayName("L2 降採樣：anchor 優先")
    void testL2DownsampleAnchorPriority() {
        int fLx = 2, fLy = 2, fLz = 2;
        int cLx = 1, cLy = 1, cLz = 1;
        int fN = 8;

        float[] fineCond = new float[fN * 6];
        byte[] fineType = new byte[fN];
        fineType[0] = PFSFConstants.VOXEL_ANCHOR;
        for (int i = 1; i < fN; i++) fineType[i] = PFSFConstants.VOXEL_SOLID;

        float[] outCond = new float[6];
        byte[] outType = new byte[1];
        PFSFDataBuilder.downsample(fineCond, fineType, fLx, fLy, fLz, cLx, cLy, cLz, outCond, outType);

        assertEquals(PFSFConstants.VOXEL_ANCHOR, outType[0], "Anchor should propagate to coarse");
    }
}
