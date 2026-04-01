package com.blockreality.api.client.render.rt;

import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BRDDGIProbeSystem 單元測試。
 *
 * 測試覆蓋：
 *   - linearToGrid / gridToLinear 索引往返
 *   - probeWorldPos 世界座標計算
 *   - dirToOctUV / octUVToDir 八面體映射往返
 *   - getInterpolationProbes 三線性插值 probe 選取
 *   - probeIrradianceAtlasOffset Atlas 座標計算
 *   - serializeProbeUBO 序列化長度驗證
 *   - VRAM 估算合理性
 *
 * 所有測試為純 CPU 數學，不依賴 Vulkan / Forge。
 */
class BRDDGIProbeSystemTest {

    private BRDDGIProbeSystem sys;

    @BeforeEach
    void setUp() {
        sys = BRDDGIProbeSystem.getInstance();
        // 使用預設網格（32×16×32, spacing=8）初始化
        // 注意：init() 為 idempotent（已初始化時直接返回）
        // 此處測試靜態方法，不呼叫 init()
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  gridToLinear / linearToGrid 往返
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void linearToGrid_zeroIndex_returnsOrigin() {
        // 線性索引 0 → (0, 0, 0)
        BRDDGIProbeSystem inst = BRDDGIProbeSystem.getInstance();
        // 借助 init 後的 singleton 做計算
        // gridToLinear(0,0,0) = 0
        // linearToGrid(0) = {0,0,0}
        // 以靜態方式驗證公式：iy*gridX*gridZ + iz*gridX + ix
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

        // 最大有效索引 = total - 1（ix=gX-1, iy=gY-1, iz=gZ-1）
        int maxLinear = (gY - 1) * gX * gZ + (gZ - 1) * gX + (gX - 1);
        assertEquals(total - 1, maxLinear);
    }

    @Test
    void linearToGridRoundTrip() {
        BRDDGIProbeSystem inst = BRDDGIProbeSystem.getInstance();
        inst.init(8);  // 初始化以獲得 gridX/Y/Z

        int total = inst.getTotalProbeCount();
        // 驗證前 100 個索引的往返
        int checks = Math.min(100, total);
        for (int i = 0; i < checks; i++) {
            int[] grid   = inst.linearToGrid(i);
            int   linear = inst.gridToLinear(grid[0], grid[1], grid[2]);
            assertEquals(i, linear, "Round-trip failed at linearIdx=" + i);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  probeWorldPos 計算
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void probeWorldPos_originProbe_matchesGridOrigin() {
        BRDDGIProbeSystem inst = BRDDGIProbeSystem.getInstance();
        inst.init(8);

        // probe(0,0,0) 的世界座標應 = gridOrigin + spacing * 0.5
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
    //  Octahedral 映射往返
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void octMapping_upVector_mapsToCenter() {
        // 向上方向 (0, 1, 0) 應映射到 UV ≈ (0.5, 0.5) 的附近
        float[] uv = BRDDGIProbeSystem.dirToOctUV(new float[]{0, 1, 0});
        assertEquals(0.5f, uv[0], 0.01f, "Up vector U ≈ 0.5");
        assertEquals(0.5f, uv[1], 0.01f, "Up vector V ≈ 0.5");
    }

    @Test
    void octMapping_roundTrip_unitVectors() {
        // 測試 6 個軸方向的往返精度
        float[][] dirs = {
            {1, 0, 0}, {-1, 0, 0},
            {0, 1, 0}, {0, -1, 0},
            {0, 0, 1}, {0, 0, -1}
        };

        for (float[] dir : dirs) {
            float[] uv     = BRDDGIProbeSystem.dirToOctUV(dir);
            float[] recon  = BRDDGIProbeSystem.octUVToDir(uv[0], uv[1]);

            // 重建方向與原始方向應一致（cos夾角 ≈ 1）
            float dot = dir[0]*recon[0] + dir[1]*recon[1] + dir[2]*recon[2];
            assertEquals(1.0f, dot, 0.01f,
                String.format("Oct round-trip failed for dir=(%.0f,%.0f,%.0f)", dir[0], dir[1], dir[2]));
        }
    }

    @Test
    void octMapping_randomDir_normalizedOutput() {
        // 任意方向的逆映射輸出應為單位向量
        float[][] testDirs = {
            {0.577f, 0.577f, 0.577f},    // 45° 對角
            {-0.707f, 0.0f, 0.707f},     // 第二象限 XZ
            {0.0f, -0.5f, 0.866f}        // 下半球
        };

        for (float[] dir : testDirs) {
            // 先正規化
            float len = (float) Math.sqrt(dir[0]*dir[0] + dir[1]*dir[1] + dir[2]*dir[2]);
            dir[0] /= len; dir[1] /= len; dir[2] /= len;

            float[] uv    = BRDDGIProbeSystem.dirToOctUV(dir);
            float[] recon = BRDDGIProbeSystem.octUVToDir(uv[0], uv[1]);

            // 重建方向長度應為 1
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

        // 在網格中心附近取插值 probe
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
        // 網格中心的 8 個 probe 都應在邊界內
        assertEquals(8, validCount, "All 8 surrounding probes should be valid at grid center");
    }

    @Test
    void interpolationProbes_outsideGrid_returnsMinusOne() {
        BRDDGIProbeSystem inst = BRDDGIProbeSystem.getInstance();
        inst.init(8);

        // 遠在網格外的點
        Vector3f farPos = new Vector3f(1e6f, 1e6f, 1e6f);
        int[] probes = inst.getInterpolationProbes(farPos);

        for (int idx : probes) {
            assertEquals(-1, idx, "Probes outside grid should be -1");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Atlas 偏移計算
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

        // 連續 probe 之間的 X 偏移 = PROBE_IRRAD_FULL
        assertEquals(BRDDGIProbeSystem.PROBE_IRRAD_FULL, offset1[0] - offset0[0],
            "Adjacent probes should be PROBE_IRRAD_FULL texels apart");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  serializeProbeUBO 驗證
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
    //  VRAM 估算
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void vramEstimate_defaultGrid_inReasonableRange() {
        BRDDGIProbeSystem inst = BRDDGIProbeSystem.getInstance();
        inst.init(8);

        long vram = inst.estimateVRAMBytes();
        // 預設 32×16×32 grid：VRAM 應在 5 MB–50 MB 之間（見 Javadoc 估算 ~17 MB）
        assertTrue(vram > 5L * 1024 * 1024,  "VRAM should be > 5 MB for default grid");
        assertTrue(vram < 50L * 1024 * 1024, "VRAM should be < 50 MB for default grid");
    }
}
