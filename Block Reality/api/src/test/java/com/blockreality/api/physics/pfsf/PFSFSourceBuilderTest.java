package com.blockreality.api.physics.pfsf;

import com.blockreality.api.material.RMaterial;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PFSFSourceBuilder 源項建構器測試 — 力臂、ArchFactor、源項計算。
 */
class PFSFSourceBuilderTest {

    private static final RMaterial CONCRETE = createMaterial("concrete", 30.0, 3.0, 2400);

    // ═══════════════════════════════════════════════════════════════
    //  §2.4.1 水平力臂 BFS
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("直線懸臂：arm 從錨點往外遞增")
    void testArmLinearCantilever() {
        // X 方向 5 格：[anchor, solid, solid, solid, solid]
        Set<BlockPos> members = new HashSet<>();
        for (int x = 0; x < 5; x++) members.add(new BlockPos(x, 0, 0));

        Set<BlockPos> anchors = Set.of(new BlockPos(0, 0, 0));

        Map<BlockPos, Integer> armMap = PFSFSourceBuilder.computeHorizontalArmMap(members, anchors);

        assertEquals(0, armMap.get(new BlockPos(0, 0, 0)));
        assertEquals(1, armMap.get(new BlockPos(1, 0, 0)));
        assertEquals(2, armMap.get(new BlockPos(2, 0, 0)));
        assertEquals(3, armMap.get(new BlockPos(3, 0, 0)));
        assertEquals(4, armMap.get(new BlockPos(4, 0, 0)));
    }

    @Test
    @DisplayName("純垂直柱：arm = 0（忽略 Y）")
    void testArmVerticalColumn() {
        Set<BlockPos> members = new HashSet<>();
        for (int y = 0; y < 5; y++) members.add(new BlockPos(0, y, 0));

        Set<BlockPos> anchors = Set.of(new BlockPos(0, 0, 0));

        Map<BlockPos, Integer> armMap = PFSFSourceBuilder.computeHorizontalArmMap(members, anchors);

        // 垂直柱中只有錨點有 arm=0，其他不在水平 BFS 路徑上
        assertEquals(0, armMap.getOrDefault(new BlockPos(0, 0, 0), 0));
        // 上方方塊無水平路徑到錨點（BFS 僅走水平）
        // 預設 arm=0（不在 armMap 中）
        assertFalse(armMap.containsKey(new BlockPos(0, 4, 0)));
    }

    @Test
    @DisplayName("L 型結構：水平距離正確")
    void testArmLShape() {
        // L 型：(0,0,0)-(1,0,0)-(2,0,0)-(2,0,1)-(2,0,2)
        Set<BlockPos> members = Set.of(
                new BlockPos(0, 0, 0), new BlockPos(1, 0, 0),
                new BlockPos(2, 0, 0), new BlockPos(2, 0, 1),
                new BlockPos(2, 0, 2));
        Set<BlockPos> anchors = Set.of(new BlockPos(0, 0, 0));

        Map<BlockPos, Integer> armMap = PFSFSourceBuilder.computeHorizontalArmMap(members, anchors);

        assertEquals(0, armMap.get(new BlockPos(0, 0, 0)));
        assertEquals(2, armMap.get(new BlockPos(2, 0, 0)));
        assertEquals(3, armMap.get(new BlockPos(2, 0, 1)));
        assertEquals(4, armMap.get(new BlockPos(2, 0, 2)));
    }

    // ═══════════════════════════════════════════════════════════════
    //  §2.5.2 ArchFactor
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("單錨點群：ArchFactor 全為 0")
    void testArchFactorSingleGroup() {
        Set<BlockPos> members = new HashSet<>();
        for (int x = 0; x < 5; x++) members.add(new BlockPos(x, 0, 0));

        // 兩個錨點但水平相鄰 → 同一群
        Set<BlockPos> anchors = Set.of(new BlockPos(0, 0, 0), new BlockPos(1, 0, 0));

        Map<BlockPos, Double> archMap = PFSFSourceBuilder.computeArchFactorMap(members, anchors);
        assertTrue(archMap.isEmpty(), "單群錨點應無拱效應");
    }

    @Test
    @DisplayName("對稱拱橋：中央 ArchFactor ≥ 0.85")
    void testArchFactorSymmetricArch() {
        // 10 格拱橋，左右各有獨立錨點
        // 0 --- 4 --- 9
        // A           B
        Set<BlockPos> members = new HashSet<>();
        for (int x = 0; x < 10; x++) members.add(new BlockPos(x, 0, 0));

        Set<BlockPos> anchors = Set.of(new BlockPos(0, 0, 0), new BlockPos(9, 0, 0));

        Map<BlockPos, Double> archMap = PFSFSourceBuilder.computeArchFactorMap(members, anchors);

        // 中央 (4,0,0) 和 (5,0,0) 距離兩錨點相當 → ArchFactor ≈ 0.8~1.0
        double archMid = archMap.getOrDefault(new BlockPos(4, 0, 0), 0.0);
        assertTrue(archMid >= 0.7, "對稱拱中央 ArchFactor=" + archMid + " 應 ≥ 0.7");

        double archMid2 = archMap.getOrDefault(new BlockPos(5, 0, 0), 0.0);
        assertTrue(archMid2 >= 0.7, "對稱拱中央 ArchFactor=" + archMid2 + " 應 ≥ 0.7");
    }

    @Test
    @DisplayName("非對稱拱：ArchFactor 介於 0 和 1 之間")
    void testArchFactorAsymmetric() {
        // 12 格，錨A 在 x=0，錨B 在 x=11（跨距 4 vs 8）
        Set<BlockPos> members = new HashSet<>();
        for (int x = 0; x < 12; x++) members.add(new BlockPos(x, 0, 0));

        Set<BlockPos> anchors = Set.of(new BlockPos(0, 0, 0), new BlockPos(11, 0, 0));

        Map<BlockPos, Double> archMap = PFSFSourceBuilder.computeArchFactorMap(members, anchors);

        // x=4: distA=4, distB=7 → AF = 4/7 ≈ 0.57
        double af4 = archMap.getOrDefault(new BlockPos(4, 0, 0), 0.0);
        assertTrue(af4 > 0.3 && af4 < 0.8, "非對稱 x=4 ArchFactor=" + af4 + " 應在 0.3~0.8");
    }

    @Test
    @DisplayName("少於 2 個錨點：無拱效應")
    void testArchFactorSingleAnchor() {
        Set<BlockPos> members = Set.of(new BlockPos(0, 0, 0), new BlockPos(1, 0, 0));
        Set<BlockPos> anchors = Set.of(new BlockPos(0, 0, 0));

        Map<BlockPos, Double> archMap = PFSFSourceBuilder.computeArchFactorMap(members, anchors);
        assertTrue(archMap.isEmpty());
    }

    // ═══════════════════════════════════════════════════════════════
    //  源項計算
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("源項基本計算：density × gravity × volume")
    void testComputeSourceBasic() {
        float source = PFSFSourceBuilder.computeSource(CONCRETE, 1.0f, 0, 0.0);
        double expected = 2400 * 9.81 * 1.0; // ≈ 23544 N
        assertEquals(expected, source, 1.0);
    }

    @Test
    @DisplayName("力臂加壓：arm 越大 source 越高")
    void testComputeSourceMomentArm() {
        float s0 = PFSFSourceBuilder.computeSource(CONCRETE, 1.0f, 0, 0.0);
        float s5 = PFSFSourceBuilder.computeSource(CONCRETE, 1.0f, 5, 0.0);
        float s10 = PFSFSourceBuilder.computeSource(CONCRETE, 1.0f, 10, 0.0);

        assertTrue(s5 > s0, "arm=5 source 應 > arm=0");
        assertTrue(s10 > s5, "arm=10 source 應 > arm=5");

        // 驗算：s5 = base * (1 + 0.2*5) = base * 2.0
        assertEquals(s0 * 2.0, s5, 1.0);
    }

    @Test
    @DisplayName("ArchFactor 消除力矩加壓")
    void testComputeSourceArchFactor() {
        float sNoArch = PFSFSourceBuilder.computeSource(CONCRETE, 1.0f, 10, 0.0);
        float sFullArch = PFSFSourceBuilder.computeSource(CONCRETE, 1.0f, 10, 1.0);
        float sHalfArch = PFSFSourceBuilder.computeSource(CONCRETE, 1.0f, 10, 0.5);

        // ArchFactor=1.0 → momentFactor = 1.0（完全消除距離加壓）
        float base = PFSFSourceBuilder.computeSource(CONCRETE, 1.0f, 0, 0.0);
        assertEquals(base, sFullArch, 1.0, "ArchFactor=1.0 應消除力矩加壓");

        assertTrue(sHalfArch < sNoArch, "ArchFactor=0.5 應減少加壓");
        assertTrue(sHalfArch > sFullArch, "ArchFactor=0.5 仍應有部分加壓");
    }

    @Test
    @DisplayName("maxPhi 計算正確")
    void testComputeMaxPhi() {
        float maxPhi = PFSFSourceBuilder.computeMaxPhi(CONCRETE);
        // Rtens=3, maxSpan=floor(sqrt(3)*2)=floor(3.46)=3
        // avgWeight = 2400 * 9.81 * 1.0 = 23544
        // maxPhi = 3 * 23544 = 70632
        double expected = 3 * 2400 * 9.81;
        assertEquals(expected, maxPhi, 100.0);
    }

    // ─── Helper ───
    private static RMaterial createMaterial(String id, double rcomp, double rtens, double density) {
        return new RMaterial() {
            @Override public double getRcomp() { return rcomp; }
            @Override public double getRtens() { return rtens; }
            @Override public double getRshear() { return rtens * 0.6; }
            @Override public double getDensity() { return density; }
            @Override public String getMaterialId() { return id; }
        };
    }
}
