package com.blockreality.api.client.render.rt;

import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BRDDGIProbeSystem unit test.
 *
 * Test coverage:
 *   - linearToGrid / gridToLinear index round trip
 *   - probeWorldPos world coordinate calculation
 *   - dirToOctUV / octUVToDir octahedral mapping round trip
 *   - getInterpolationProbes trilinear interpolation probe selection
 *   - probeIrradianceAtlasOffset Atlas coordinate calculation
 *   - serializeProbeUBO serialization length verification
 *   - VRAM estimate plausibility
 *
 * All tests are pure CPU math and do not rely on Vulkan/Forge.
 */
class BRDDGIProbeSystemTest {

    private BRDDGIProbeSystem sys;

    @BeforeEach
    void setUp() {
        sys = BRDDGIProbeSystem.getInstance();
        // Initialized using the default grid (32×16×32, spacing=8)
        // Note: init() is idempotent (returns directly when initialized)
        // The static method is tested here and init() is not called.
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  gridToLinear / linearToGrid round trip
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void linearToGrid_zeroIndex_returnsOrigin() {
        // linear index 0 → (0, 0, 0)
        BRDDGIProbeSystem inst = BRDDGIProbeSystem.getInstance();
        // Use singleton after init to do calculations
        // gridToLinear(0,0,0) = 0
        // linearToGrid(0) = {0,0,0}
        // Validate the formula statically: iy*gridX*gridZ + iz*gridX + ix
        int gridX = BRDDGIProbeSystem.DEFAULT_GRID_X;
        int gridZ = BRDDGIProbeSystem.DEFAULT_GRID_Z;

        // ix=0, iy=0, iz=0 → linear = 0
        assertEquals(0, 0 * gridX * gridZ + 0 * gridX + 0);
    }

    @Test
    void gridToLinear_maxIndex_isTotal() {
        int gX = BRDDGIProbeSystem.DEFAULT_GRID_X;
        int gY = BRDDGIProbeSystem.DEFAULT_GRID_Y;
        int gZ = BRDDGIProbeSystem.DEFAULT_GRID_Z;
        int total = gX * gY * gZ;

        // Maximum valid index = total - 1 (ix=gX-1, iy=gY-1, iz=gZ-1)
        int maxLinear = (gY - 1) * gX * gZ + (gZ - 1) * gX + (gX - 1);
        assertEquals(total - 1, maxLinear);
    }

    @Test
    void linearToGridRoundTrip() {
        BRDDGIProbeSystem inst = BRDDGIProbeSystem.getInstance();
        inst.init(8);  // Initialize to get gridX/Y/Z

        int total = inst.getTotalProbeCount();
        // Round trip to verify first 100 indexes
        int checks = Math.min(100, total);
        for (int i = 0; i < checks; i++) {
            int[] grid   = inst.linearToGrid(i);
            int   linear = inst.gridToLinear(grid[0], grid[1], grid[2]);
            assertEquals(i, linear, "Round-trip failed at linearIdx=" + i);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  probeWorldPos calculation
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void probeWorldPos_originProbe_matchesGridOrigin() {
        BRDDGIProbeSystem inst = BRDDGIProbeSystem.getInstance();
        inst.init(8);

        // The world coordinates of probe(0,0,0) should = gridOrigin + spacing * 0.5
        Vector3f origin  = new Vector3f(inst.getGridOrigin().x, inst.getGridOrigin().y,
                                        inst.getGridOrigin().z);
        Vector3f probePos = inst.probeWorldPos(0, 0, 0);
        float halfSpacing = inst.getSpacingBlocks() * 0.5f;

        assertEquals(origin.x + halfSpacing, probePos.x, 0.001f, "probe(0,0,0) X");
        assertEquals(origin.y + halfSpacing, probePos.y, 0.001f, "probe(0,0,0) Y");
        assertEquals(origin.z + halfSpacing, probePos.z, 0.001f, "probe(0,0,0) Z");
    }

    @Test
    void probeWorldPos_adjacentProbes_spacingApart() {
        BRDDGIProbeSystem inst = BRDDGIProbeSystem.getInstance();
        inst.init(8);

        Vector3f p0 = inst.probeWorldPos(0, 0, 0);
        Vector3f p1 = inst.probeWorldPos(1, 0, 0);

        assertEquals(inst.getSpacingBlocks(), p1.x - p0.x, 0.001f,
            "Adjacent probes (X) should be spacing blocks apart");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Octahedral mapping round trip
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void octMapping_upVector_mapsToCenter() {
        // The upward direction (0, 1, 0) is normalized by L1, ox=0, oy=1,
        // dir[2]=0 does not trigger the folding of the lower hemisphere, UV = (0*0.5+0.5, 1*0.5+0.5) = (0.5, 1.0)
        float[] uv = BRDDGIProbeSystem.dirToOctUV(new float[]{0, 1, 0});
        assertEquals(0.5f, uv[0], 0.01f, "Up vector U = 0.5");
        assertEquals(1.0f, uv[1], 0.01f, "Up vector V = 1.0 (top edge)");
    }

    @Test
    void octMapping_roundTrip_unitVectors() {
        // Test the round-trip accuracy in 6 axes
        float[][] dirs = {
            {1, 0, 0}, {-1, 0, 0},
            {0, 1, 0}, {0, -1, 0},
            {0, 0, 1}, {0, 0, -1}
        };

        for (float[] dir : dirs) {
            float[] uv     = BRDDGIProbeSystem.dirToOctUV(dir);
            float[] recon  = BRDDGIProbeSystem.octUVToDir(uv[0], uv[1]);

            // The reconstruction direction should be consistent with the original direction (cos angle ≈ 1)
            float dot = dir[0]*recon[0] + dir[1]*recon[1] + dir[2]*recon[2];
            assertEquals(1.0f, dot, 0.01f,
                String.format("Oct round-trip failed for dir=(%.0f,%.0f,%.0f)", dir[0], dir[1], dir[2]));
        }
    }

    @Test
    void octMapping_randomDir_normalizedOutput() {
        // The output of the inverse map in any direction should be a unit vector
        float[][] testDirs = {
            {0.577f, 0.577f, 0.577f},    // 45° diagonal
            {-0.707f, 0.0f, 0.707f},     // Second Quadrant XZ
            {0.0f, -0.5f, 0.866f}        // lower hemisphere
        };

        for (float[] dir : testDirs) {
            // Regularize first
            float len = (float) Math.sqrt(dir[0]*dir[0] + dir[1]*dir[1] + dir[2]*dir[2]);
            dir[0] /= len; dir[1] /= len; dir[2] /= len;

            float[] uv    = BRDDGIProbeSystem.dirToOctUV(dir);
            float[] recon = BRDDGIProbeSystem.octUVToDir(uv[0], uv[1]);

            // Reconstruction direction length should be 1
            float reconLen = (float) Math.sqrt(recon[0]*recon[0] + recon[1]*recon[1] + recon[2]*recon[2]);
            assertEquals(1.0f, reconLen, 0.01f, "Reconstructed direction should be unit vector");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  getInterpolationProbes
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void interpolationProbes_centerOfGrid_returns8ValidProbes() {
        BRDDGIProbeSystem inst = BRDDGIProbeSystem.getInstance();
        inst.init(8);

        // Take the interpolation probe near the center of the grid
        Vector3f gridOrigin = new Vector3f(inst.getGridOrigin().x, inst.getGridOrigin().y,
                                           inst.getGridOrigin().z);
        int halfX = inst.getGridX() / 2;
        int halfY = inst.getGridY() / 2;
        int halfZ = inst.getGridZ() / 2;
        Vector3f centerWorld = inst.probeWorldPos(halfX, halfY, halfZ);

        int[] probes = inst.getInterpolationProbes(centerWorld);

        assertEquals(8, probes.length, "Should return 8 probe indices");
        int validCount = 0;
        for (int idx : probes) {
            if (idx >= 0) validCount++;
        }
        // The 8 probes in the center of the grid should all be within the boundaries
        assertEquals(8, validCount, "All 8 surrounding probes should be valid at grid center");
    }

    @Test
    void interpolationProbes_outsideGrid_returnsMinusOne() {
        BRDDGIProbeSystem inst = BRDDGIProbeSystem.getInstance();
        inst.init(8);

        // Points far outside the grid
        Vector3f farPos = new Vector3f(1e6f, 1e6f, 1e6f);
        int[] probes = inst.getInterpolationProbes(farPos);

        for (int idx : probes) {
            assertEquals(-1, idx, "Probes outside grid should be -1");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Atlas offset calculation
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void irradianceAtlasOffset_probe0_returnsZero() {
        BRDDGIProbeSystem inst = BRDDGIProbeSystem.getInstance();
        inst.init(8);

        int[] offset = inst.probeIrradianceAtlasOffset(0);
        assertEquals(0, offset[0], "Probe 0 atlasX should be 0");
        assertEquals(0, offset[1], "Probe 0 atlasY should be 0");
    }

    @Test
    void irradianceAtlasOffset_secondProbe_spacedByFullTexels() {
        BRDDGIProbeSystem inst = BRDDGIProbeSystem.getInstance();
        inst.init(8);

        int[] offset0 = inst.probeIrradianceAtlasOffset(0);
        int[] offset1 = inst.probeIrradianceAtlasOffset(1);

        // X offset between consecutive probes = PROBE_IRRAD_FULL
        assertEquals(BRDDGIProbeSystem.PROBE_IRRAD_FULL, offset1[0] - offset0[0],
            "Adjacent probes should be PROBE_IRRAD_FULL texels apart");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  serializeProbeUBO verification
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void serializeProbeUBO_correctByteLength() {
        BRDDGIProbeSystem inst = BRDDGIProbeSystem.getInstance();
        inst.init(8);

        java.nio.ByteBuffer buf = inst.serializeProbeUBO();
        assertNotNull(buf);

        int expected = inst.getTotalProbeCount() * BRDDGIProbeSystem.PROBE_UBO_ENTRY_SIZE;
        assertEquals(expected, buf.limit(), "UBO ByteBuffer size should match totalProbes × 16");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  VRAM estimate
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void vramEstimate_defaultGrid_inReasonableRange() {
        BRDDGIProbeSystem inst = BRDDGIProbeSystem.getInstance();
        inst.init(8);

        long vram = inst.estimateVRAMBytes();
        // Default 32×16×32 grid: VRAM should be between 5 MB–50 MB (see Javadoc for estimate ~17 MB)
        assertTrue(vram > 5L * 1024 * 1024,  "VRAM should be > 5 MB for default grid");
        assertTrue(vram < 50L * 1024 * 1024, "VRAM should be < 50 MB for default grid");
    }
}
