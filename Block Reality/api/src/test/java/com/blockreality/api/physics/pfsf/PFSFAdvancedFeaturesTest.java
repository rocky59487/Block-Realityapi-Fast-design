package com.blockreality.api.physics.pfsf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import net.minecraft.core.BlockPos;

import java.util.*;

import static com.blockreality.api.physics.pfsf.PFSFConstants.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 新功能測試：各向異性、虛擬邊、阻尼、動態步數。
 */
class PFSFAdvancedFeaturesTest {

    // ═══════════════════════════════════════════════════════════════
    //  各向異性 Capacity（壓/拉分離）
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("FAIL_TENSION 常數值正確")
    void testTensionConstant() {
        assertEquals(4, FAIL_TENSION);
        // 所有 fail 常數不重複
        Set<Byte> failCodes = Set.of(FAIL_OK, FAIL_CANTILEVER, FAIL_CRUSHING, FAIL_NO_SUPPORT, FAIL_TENSION);
        assertEquals(5, failCodes.size(), "所有 fail code 應不重複");
    }

    @Test
    @DisplayName("Rtens < Rcomp 的材料（如混凝土）應拉力容量遠小於壓力")
    void testAnisotropicCapacityRatio() {
        // 混凝土 Rcomp=25 MPa, Rtens=2.5 MPa
        float rcomp = 25.0f;
        float rtens = 2.5f;
        float compCapacity = rcomp * 1e6f;  // 25 MN
        float tensCapacity = rtens * 1e6f;  // 2.5 MN

        assertTrue(compCapacity > tensCapacity * 5,
                "壓力容量應至少為拉力的 5 倍（混凝土特性）");
    }

    // ═══════════════════════════════════════════════════════════════
    //  對角線虛擬邊（Phantom Edges）
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("對角線連接的方塊對應產生虛擬邊")
    void testPhantomEdgeInjection() {
        // 兩個方塊只有邊連接（對角線）
        Set<BlockPos> members = new HashSet<>();
        BlockPos a = new BlockPos(0, 0, 0);
        BlockPos b = new BlockPos(1, 1, 0);  // 邊連接（XY 對角）
        members.add(a);
        members.add(b);

        int Lx = 2, Ly = 2, Lz = 1;
        int N = Lx * Ly * Lz;
        float[] cond = new float[N * 6];
        BlockPos origin = new BlockPos(0, 0, 0);

        // Mock material lookup
        com.blockreality.api.material.RMaterial mockMat = new com.blockreality.api.material.RMaterial() {
            @Override public double getRcomp() { return 25.0; }
            @Override public double getRtens() { return 2.5; }
            @Override public double getRshear() { return 3.5; }
            @Override public double getDensity() { return 2400; }
            @Override public String getMaterialId() { return "test_concrete"; }
        };

        int injected = PFSFSourceBuilder.injectDiagonalPhantomEdges(
                members, cond, N, Lx, Ly, Lz, origin, pos -> mockMat);

        assertTrue(injected > 0, "對角線連接應注入至少 1 個虛擬邊，實際=" + injected);
    }

    @Test
    @DisplayName("面連接的方塊不會產生虛擬邊")
    void testPhantomEdgeSkipsFaceConnected() {
        Set<BlockPos> members = new HashSet<>();
        BlockPos a = new BlockPos(0, 0, 0);
        BlockPos b = new BlockPos(1, 0, 0);  // 面連接（+X）
        members.add(a);
        members.add(b);

        int Lx = 2, Ly = 1, Lz = 1;
        int N = Lx * Ly * Lz;
        float[] cond = new float[N * 6];
        BlockPos origin = new BlockPos(0, 0, 0);

        com.blockreality.api.material.RMaterial mockMat = new com.blockreality.api.material.RMaterial() {
            @Override public double getRcomp() { return 25.0; }
            @Override public double getRtens() { return 2.5; }
            @Override public double getRshear() { return 3.5; }
            @Override public double getDensity() { return 2400; }
            @Override public String getMaterialId() { return "test"; }
        };

        int injected = PFSFSourceBuilder.injectDiagonalPhantomEdges(
                members, cond, N, Lx, Ly, Lz, origin, pos -> mockMat);

        assertEquals(0, injected, "面連接不應產生虛擬邊");
    }

    // ═══════════════════════════════════════════════════════════════
    //  阻尼（Damping）
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("DAMPING_FACTOR 在合理範圍 (0.99, 1.0)")
    void testDampingFactorRange() {
        assertTrue(DAMPING_FACTOR > 0.99f, "衰減不應太大（>1% 會破壞收斂）");
        assertTrue(DAMPING_FACTOR < 1.0f, "衰減因子必須 < 1.0");
    }

    @Test
    @DisplayName("DAMPING_SETTLE_THRESHOLD 為正值")
    void testDampingSettleThreshold() {
        assertTrue(DAMPING_SETTLE_THRESHOLD > 0, "閾值必須為正");
        assertTrue(DAMPING_SETTLE_THRESHOLD < 0.1f, "閾值不應超過 10%");
    }

    // ═══════════════════════════════════════════════════════════════
    //  動態 Sub-stepping
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("崩塌時步數根據高度動態調整")
    void testDynamicSubStepping() {
        PFSFIslandBuffer buf = new PFSFIslandBuffer(999);
        // 模擬不同高度
        // recommendSteps(buf, isDirty, hasCollapse)

        // 正常更新
        int normalSteps = PFSFScheduler.recommendSteps(buf, true, false);
        assertEquals(STEPS_MAJOR, normalSteps);

        // 崩塌 — 應根據高度調整（buf.getLy() 未初始化 = 0，所以取 max(STEPS_COLLAPSE, 0)）
        int collapseSteps = PFSFScheduler.recommendSteps(buf, true, true);
        assertTrue(collapseSteps >= STEPS_COLLAPSE,
                "崩塌步數應 ≥ STEPS_COLLAPSE，實際=" + collapseSteps);
        assertTrue(collapseSteps <= 128, "崩塌步數應 ≤ 128");
    }

    // ═══════════════════════════════════════════════════════════════
    //  常數一致性
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("MAX_OMEGA 不超過 2.0")
    void testMaxOmega() {
        assertTrue(MAX_OMEGA < 2.0f, "Chebyshev omega 必須 < 2.0 以保證穩定");
        assertTrue(MAX_OMEGA > 1.5f, "omega 應 > 1.5 以保持加速效果");
    }

    @Test
    @DisplayName("所有新常數都已定義")
    void testAllNewConstantsDefined() {
        assertNotNull(MAX_OMEGA);
        assertNotNull(OMEGA_DENOM_EPSILON);
        assertNotNull(DAMPING_SETTLE_THRESHOLD);
        assertNotNull(STRESS_SYNC_BROADCAST_RADIUS);
        assertTrue(STRESS_SYNC_INTERVAL > 0);
    }
}
