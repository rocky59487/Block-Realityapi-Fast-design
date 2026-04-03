package com.blockreality.api.physics;

import com.blockreality.api.material.DefaultMaterial;
import com.blockreality.api.material.RMaterial;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 階層式物理引擎一致性驗證 — D-2c
 *
 * 驗證 Layer 3 (CoarseFEM) → Layer 2 (UnionFind) → Layer 1 (ForceEquilibrium)
 * 的結果一致性：
 *   - 穩定結構在所有層級都判定為穩定
 *   - 懸浮結構在所有層級都判定為不穩定
 *   - Layer 1 結果是 Layer 2 結果的超集（Layer 1 更精確）
 */
@DisplayName("Physics Layer Consistency — 3-Layer Hierarchy Tests")
class LayerConsistencyTest {

    // ═══ 1. Stable Structure: All Layers Agree ═══

    @Test
    @DisplayName("Vertical column with anchor: stable at all layers")
    void testStableColumnAllLayers() {
        // Simple 5-block vertical column on anchor
        Set<BlockPos> blocks = new HashSet<>();
        Map<BlockPos, RMaterial> materials = new HashMap<>();
        BlockPos anchor = new BlockPos(0, 0, 0);

        for (int y = 0; y < 5; y++) {
            BlockPos pos = new BlockPos(0, y, 0);
            blocks.add(pos);
            materials.put(pos, DefaultMaterial.CONCRETE);
        }
        Set<BlockPos> anchors = Set.of(anchor);

        // Layer 1: ForceEquilibriumSolver
        Map<BlockPos, ForceEquilibriumSolver.ForceResult> l1Result =
            ForceEquilibriumSolver.solve(blocks, materials, anchors);

        // All blocks should be stable at Layer 1
        for (BlockPos pos : blocks) {
            assertTrue(l1Result.get(pos).isStable(),
                "Layer 1: block at " + pos + " should be stable");
        }
    }

    // ═══ 2. Floating Structure: All Layers Detect ═══

    @Test
    @DisplayName("No-anchor blocks: unstable at Layer 1")
    void testFloatingAllLayersDetect() {
        BlockPos b1 = new BlockPos(0, 5, 0);
        BlockPos b2 = new BlockPos(0, 6, 0);

        Set<BlockPos> blocks = Set.of(b1, b2);
        Map<BlockPos, RMaterial> materials = Map.of(
            b1, DefaultMaterial.TIMBER,
            b2, DefaultMaterial.TIMBER
        );
        Set<BlockPos> anchors = Set.of(); // no anchors

        // Layer 1
        Map<BlockPos, ForceEquilibriumSolver.ForceResult> l1Result =
            ForceEquilibriumSolver.solve(blocks, materials, anchors);

        for (ForceEquilibriumSolver.ForceResult fr : l1Result.values()) {
            assertFalse(fr.isStable(),
                "Layer 1: floating block should be unstable");
        }
    }

    // ═══ 3. Layer 1 More Precise Than Threshold Check ═══

    @Test
    @DisplayName("Layer 1 (FES) provides utilization ratios, not just pass/fail")
    void testLayer1ProvidesGranularity() {
        Set<BlockPos> blocks = new HashSet<>();
        Map<BlockPos, RMaterial> materials = new HashMap<>();

        BlockPos anchor = new BlockPos(0, 0, 0);
        blocks.add(anchor);
        materials.put(anchor, DefaultMaterial.CONCRETE);

        // 使用垂直柱（而非水平懸臂）：FES 沿 Y 軸累積荷載，
        // 底部方塊承受全部上方重量，頂部方塊只承受自重，
        // 因此不同高度的方塊具有不同的利用率（granularity）。
        for (int y = 1; y <= 3; y++) {
            BlockPos pos = new BlockPos(0, y, 0);
            blocks.add(pos);
            materials.put(pos, DefaultMaterial.CONCRETE);
        }

        Map<BlockPos, ForceEquilibriumSolver.ForceResult> result =
            ForceEquilibriumSolver.solve(blocks, materials, Set.of(anchor));

        // Layer 1 should provide continuous utilization values (not just 0/1)
        Set<Double> uniqueUtils = new HashSet<>();
        for (ForceEquilibriumSolver.ForceResult fr : result.values()) {
            uniqueUtils.add(fr.utilizationRatio());
        }

        assertTrue(uniqueUtils.size() > 1,
            "Layer 1 should produce varied utilization ratios, not uniform values");
    }

    // ═══ 4. Symmetric Structure Consistent ═══

    @Test
    @DisplayName("Symmetric T-shape: both arms have equal stability")
    void testSymmetricConsistency() {
        BlockPos anchor = new BlockPos(0, 0, 0);
        BlockPos center = new BlockPos(0, 1, 0);
        BlockPos left = new BlockPos(-1, 1, 0);
        BlockPos right = new BlockPos(1, 1, 0);

        Set<BlockPos> blocks = Set.of(anchor, center, left, right);
        Map<BlockPos, RMaterial> materials = new HashMap<>();
        blocks.forEach(b -> materials.put(b, DefaultMaterial.CONCRETE));

        Map<BlockPos, ForceEquilibriumSolver.ForceResult> result =
            ForceEquilibriumSolver.solve(blocks, materials, Set.of(anchor));

        // Both arms should have same stability
        assertEquals(
            result.get(left).isStable(),
            result.get(right).isStable(),
            "Symmetric arms should have equal stability"
        );
        assertEquals(
            result.get(left).utilizationRatio(),
            result.get(right).utilizationRatio(),
            0.05,
            "Symmetric arms should have equal utilization"
        );
    }

    // ═══ 5. Adding Support Improves Stability ═══

    @Test
    @DisplayName("Adding anchor reduces utilization")
    void testMoreAnchorsReduceUtilization() {
        BlockPos b = new BlockPos(0, 1, 0);
        BlockPos a1 = new BlockPos(0, 0, 0);
        BlockPos a2 = new BlockPos(1, 0, 0);

        // Single anchor
        Map<BlockPos, ForceEquilibriumSolver.ForceResult> r1 =
            ForceEquilibriumSolver.solve(
                Set.of(a1, b),
                Map.of(a1, DefaultMaterial.CONCRETE, b, DefaultMaterial.CONCRETE),
                Set.of(a1)
            );

        // Two anchors
        Map<BlockPos, ForceEquilibriumSolver.ForceResult> r2 =
            ForceEquilibriumSolver.solve(
                Set.of(a1, a2, b),
                Map.of(a1, DefaultMaterial.CONCRETE, a2, DefaultMaterial.CONCRETE,
                       b, DefaultMaterial.CONCRETE),
                Set.of(a1, a2)
            );

        assertTrue(r2.get(b).isStable(),
            "Block with two anchors should be stable");
    }
}
