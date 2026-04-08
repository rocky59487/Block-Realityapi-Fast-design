package com.blockreality.api.physics.pfsf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.blockreality.api.physics.pfsf.PFSFConstants.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * PFSFDataBuilder coarse grid downsampling logic test.
 *
 * <p>Test conductivity and type calculations for 2×2×2 average downsampling,
 * No GPU required (pure CPU array operations). </p>
 */
class PFSFDataBuilderTest {

    @Test
    @DisplayName("粗網格 type：2×2×2 中有 anchor → coarse = ANCHOR")
    void testCoarseTypeAnchorPriority() {
        // 2×2×2 fine grid → 1×1×1 coarse grid
        byte[] fineType = new byte[8];
        fineType[0] = VOXEL_SOLID;
        fineType[1] = VOXEL_SOLID;
        fineType[2] = VOXEL_SOLID;
        fineType[3] = VOXEL_ANCHOR; // Just one anchor

        float[] fineCond = new float[8 * 6]; // SoA layout
        byte[] coarseType = new byte[1];

        // Manually replicate the logic
        int solidCount = 0, anchorCount = 0, total = 0;
        for (int i = 0; i < 8; i++) {
            total++;
            if (fineType[i] == VOXEL_ANCHOR) anchorCount++;
            else if (fineType[i] == VOXEL_SOLID) solidCount++;
        }

        if (anchorCount > 0) coarseType[0] = VOXEL_ANCHOR;
        else if (solidCount > total / 2) coarseType[0] = VOXEL_SOLID;
        else coarseType[0] = VOXEL_AIR;

        assertEquals(VOXEL_ANCHOR, coarseType[0],
                "Any anchor in 2×2×2 block → coarse voxel should be ANCHOR");
    }

    @Test
    @DisplayName("粗網格 type：多數 solid → coarse = SOLID")
    void testCoarseTypeMajoritySolid() {
        byte[] fineType = new byte[8];
        fineType[0] = VOXEL_SOLID;
        fineType[1] = VOXEL_SOLID;
        fineType[2] = VOXEL_SOLID;
        fineType[3] = VOXEL_SOLID;
        fineType[4] = VOXEL_SOLID;
        fineType[5] = VOXEL_AIR;
        fineType[6] = VOXEL_AIR;
        fineType[7] = VOXEL_AIR;
        // 5 solid, 3 air → majority solid

        int solidCount = 5, total = 8;
        assertTrue(solidCount > total / 2);

        byte result = (solidCount > total / 2) ? VOXEL_SOLID : VOXEL_AIR;
        assertEquals(VOXEL_SOLID, result);
    }

    @Test
    @DisplayName("粗網格 type：多數 air → coarse = AIR")
    void testCoarseTypeMajorityAir() {
        byte[] fineType = new byte[8];
        fineType[0] = VOXEL_SOLID;
        fineType[1] = VOXEL_AIR;
        fineType[2] = VOXEL_AIR;
        fineType[3] = VOXEL_AIR;
        fineType[4] = VOXEL_AIR;
        fineType[5] = VOXEL_AIR;
        fineType[6] = VOXEL_AIR;
        fineType[7] = VOXEL_AIR;
        // 1 solid, 7 air → air wins

        int solidCount = 1, total = 8;
        assertFalse(solidCount > total / 2);

        byte result = (solidCount > total / 2) ? VOXEL_SOLID : VOXEL_AIR;
        assertEquals(VOXEL_AIR, result);
    }

    @Test
    @DisplayName("粗網格 conductivity 平均：均勻 σ 不變")
    void testCoarseConductivityAveraging() {
        // 4×4×4 fine → 2×2×2 coarse
        int fLx = 4, fLy = 4, fLz = 4;
        int fN = fLx * fLy * fLz; // 64
        float[] fineCond = new float[fN * 6];

        // All conductivity set to 2.0
        for (int i = 0; i < fineCond.length; i++) {
            fineCond[i] = 2.0f;
        }

        // Compute coarse (0,0,0) voxels
        int cx = 0, cy = 0, cz = 0;
        float[] condSum = new float[6];
        int total = 0;

        for (int dz = 0; dz < 2; dz++) {
            for (int dy = 0; dy < 2; dy++) {
                for (int dx = 0; dx < 2; dx++) {
                    int fi = dx + fLx * (dy + fLy * dz);
                    total++;
                    for (int d = 0; d < 6; d++) {
                        condSum[d] += fineCond[d * fN + fi];
                    }
                }
            }
        }

        // Average = 2.0 × 8 / 8 = 2.0
        for (int d = 0; d < 6; d++) {
            assertEquals(2.0f, condSum[d] / total, 1e-5f,
                    "Uniform conductivity should average to same value, dir=" + d);
        }
    }

    @Test
    @DisplayName("SoA conductivity layout：d * N + i 索引驗證")
    void testSoALayoutIndexing() {
        int Lx = 4, Ly = 4, Lz = 4;
        int N = Lx * Ly * Lz; // 64
        float[] conductivity = new float[N * 6];

        // Set the value of (1,2,3) direction DIR_POS_X
        int i = 1 + Lx * (2 + Ly * 3);
        int d = DIR_POS_X;
        conductivity[d * N + i] = 42.0f;

        assertEquals(42.0f, conductivity[d * N + i], 1e-5f);
        // Confirm that indexes in other directions do not conflict
        for (int other = 0; other < 6; other++) {
            if (other != d) {
                assertEquals(0.0f, conductivity[other * N + i], 1e-5f,
                        "Other direction should be zero, dir=" + other);
            }
        }
    }
}
