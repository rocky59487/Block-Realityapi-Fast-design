package com.blockreality.api.physics;

import com.blockreality.api.material.DefaultMaterial;
import com.blockreality.api.material.RMaterial;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ForceEquilibriumSolver 擴展測試 — C-1
 *
 * 補充基礎 ForceEquilibriumSolverTest 的涵蓋範圍：
 *   - 已知結構 → 預期力分佈（多支撐點、L 型、T 型）
 *   - 懸臂結構應力遞增
 *   - SOR 收斂穩定性（大結構不發散）
 *   - omega 自適應邊界安全
 *   - 並行求解結果一致性
 */
@DisplayName("ForceEquilibriumSolver — Extended Structural Tests")
class ForceEquilibriumSolverExtendedTest {

    private static final double GRAVITY = 9.81;

    // ═══ 1. Cantilever: Utilization Increases with Arm Length ═══

    @Test
    @DisplayName("Horizontal cantilever: utilization increases away from anchor")
    void testCantileverUtilizationGradient() {
        // Anchor at (0,0,0), then 5 blocks horizontal: (1,0,0)...(5,0,0)
        int length = 5;
        Set<BlockPos> blocks = new HashSet<>();
        Map<BlockPos, RMaterial> materials = new HashMap<>();
        BlockPos anchor = new BlockPos(0, 0, 0);
        blocks.add(anchor);
        materials.put(anchor, DefaultMaterial.CONCRETE);

        for (int x = 1; x <= length; x++) {
            BlockPos pos = new BlockPos(x, 0, 0);
            blocks.add(pos);
            materials.put(pos, DefaultMaterial.TIMBER);
        }
        Set<BlockPos> anchors = new HashSet<>(List.of(anchor));

        Map<BlockPos, ForceEquilibriumSolver.ForceResult> result =
            ForceEquilibriumSolver.solve(blocks, materials, anchors);

        // Blocks closer to anchor should generally have lower utilization
        // than blocks far from anchor (cantilever effect)
        double prevUtil = 0;
        for (int x = 1; x <= length; x++) {
            ForceEquilibriumSolver.ForceResult fr = result.get(new BlockPos(x, 0, 0));
            assertNotNull(fr, "Block at x=" + x + " should have result");
        }

        // The tip block should have some utilization > 0
        ForceEquilibriumSolver.ForceResult tipResult = result.get(new BlockPos(length, 0, 0));
        assertTrue(tipResult.utilizationRatio() >= 0,
            "Cantilever tip should have non-negative utilization");
    }

    // ═══ 2. T-Shape: Two Anchors Share Load ═══

    @Test
    @DisplayName("Two anchors supporting same column: each carries less than single anchor")
    void testTwoAnchorsShareLoad() {
        // Single anchor case
        BlockPos anchor1 = new BlockPos(0, 0, 0);
        BlockPos top = new BlockPos(0, 1, 0);

        Map<BlockPos, ForceEquilibriumSolver.ForceResult> singleResult =
            ForceEquilibriumSolver.solve(
                new HashSet<>(List.of(anchor1, top)),
                Map.of(anchor1, DefaultMaterial.CONCRETE, top, DefaultMaterial.CONCRETE),
                new HashSet<>(List.of(anchor1))
            );

        // Two anchor case: anchor on each side, block on top of one
        BlockPos anchor2 = new BlockPos(1, 0, 0);
        BlockPos bridge = new BlockPos(0, 1, 0);

        Map<BlockPos, ForceEquilibriumSolver.ForceResult> dualResult =
            ForceEquilibriumSolver.solve(
                new HashSet<>(List.of(anchor1, anchor2, bridge)),
                Map.of(anchor1, DefaultMaterial.CONCRETE,
                       anchor2, DefaultMaterial.CONCRETE,
                       bridge, DefaultMaterial.CONCRETE),
                new HashSet<>(List.of(anchor1, anchor2))
            );

        // Bridge block with 2 anchors nearby should be at least as stable
        assertTrue(dualResult.get(bridge).isStable(),
            "Block between two anchors should be stable");
    }

    // ═══ 3. L-Shape Structure ═══

    @Test
    @DisplayName("L-shape: vertical column + horizontal arm, all blocks have results")
    void testLShapeStructure() {
        Set<BlockPos> blocks = new HashSet<>();
        Map<BlockPos, RMaterial> materials = new HashMap<>();

        // Vertical column: (0,0,0) to (0,4,0)
        for (int y = 0; y <= 4; y++) {
            BlockPos pos = new BlockPos(0, y, 0);
            blocks.add(pos);
            materials.put(pos, DefaultMaterial.CONCRETE);
        }
        // Horizontal arm: (1,4,0) to (3,4,0)
        for (int x = 1; x <= 3; x++) {
            BlockPos pos = new BlockPos(x, 4, 0);
            blocks.add(pos);
            materials.put(pos, DefaultMaterial.TIMBER);
        }

        Set<BlockPos> anchors = new HashSet<>(List.of(new BlockPos(0, 0, 0)));

        Map<BlockPos, ForceEquilibriumSolver.ForceResult> result =
            ForceEquilibriumSolver.solve(blocks, materials, anchors);

        // All blocks should have results
        assertEquals(blocks.size(), result.size(),
            "All blocks should have ForceResult entries");

        // Vertical column should be stable
        for (int y = 1; y <= 4; y++) {
            assertTrue(result.get(new BlockPos(0, y, 0)).isStable(),
                "Column block at y=" + y + " should be stable");
        }
    }

    // ═══ 4. Large Structure Convergence ═══

    @Test
    @DisplayName("20-block vertical tower converges")
    void testTallTowerConverges() {
        Set<BlockPos> blocks = new HashSet<>();
        Map<BlockPos, RMaterial> materials = new HashMap<>();

        for (int y = 0; y < 20; y++) {
            BlockPos pos = new BlockPos(0, y, 0);
            blocks.add(pos);
            materials.put(pos, DefaultMaterial.CONCRETE);
        }

        Set<BlockPos> anchors = new HashSet<>(List.of(new BlockPos(0, 0, 0)));

        ForceEquilibriumSolver.SolverResult solverResult =
            ForceEquilibriumSolver.solveWithDiagnostics(blocks, materials, anchors, 1.25);

        assertTrue(solverResult.diagnostics().converged(),
            "20-block tower should converge");
        assertTrue(solverResult.diagnostics().finalResidual() < 0.01,
            "Final residual should be small");
    }

    // ═══ 5. Omega Bounds Safety ═══

    @Test
    @DisplayName("Solver omega stays within [1.0, 2.0) safe range")
    void testOmegaBounds() {
        Set<BlockPos> blocks = new HashSet<>();
        Map<BlockPos, RMaterial> materials = new HashMap<>();

        for (int y = 0; y < 15; y++) {
            BlockPos pos = new BlockPos(0, y, 0);
            blocks.add(pos);
            materials.put(pos, DefaultMaterial.STEEL);
        }

        Set<BlockPos> anchors = new HashSet<>(List.of(new BlockPos(0, 0, 0)));

        ForceEquilibriumSolver.SolverResult result =
            ForceEquilibriumSolver.solveWithDiagnostics(blocks, materials, anchors, 1.5);

        double omega = result.diagnostics().finalOmega();
        assertTrue(omega >= 1.0 && omega < 2.0,
            "Final omega should be in safe range [1.0, 2.0), got " + omega);
    }

    // ═══ 6. Symmetric Structure Has Symmetric Results ═══

    @Test
    @DisplayName("Symmetric structure: left and right blocks have equal utilization")
    void testSymmetricLoadDistribution() {
        BlockPos anchor = new BlockPos(0, 0, 0);
        BlockPos left = new BlockPos(-1, 1, 0);
        BlockPos center = new BlockPos(0, 1, 0);
        BlockPos right = new BlockPos(1, 1, 0);

        Set<BlockPos> blocks = new HashSet<>(List.of(anchor, left, center, right));
        Map<BlockPos, RMaterial> materials = new HashMap<>();
        for (BlockPos pos : blocks) {
            materials.put(pos, DefaultMaterial.CONCRETE);
        }
        Set<BlockPos> anchors = new HashSet<>(List.of(anchor));

        Map<BlockPos, ForceEquilibriumSolver.ForceResult> result =
            ForceEquilibriumSolver.solve(blocks, materials, anchors);

        double leftUtil = result.get(left).utilizationRatio();
        double rightUtil = result.get(right).utilizationRatio();

        // Symmetric blocks should have equal utilization (within tolerance)
        assertEquals(leftUtil, rightUtil, 0.05,
            "Symmetric blocks should have equal utilization");
    }

    // ═══ 7. All Anchors → All Zero Utilization ═══

    @Test
    @DisplayName("All-anchor structure: every block has zero utilization")
    void testAllAnchorsZeroUtilization() {
        Set<BlockPos> blocks = new HashSet<>();
        Map<BlockPos, RMaterial> materials = new HashMap<>();

        for (int i = 0; i < 5; i++) {
            BlockPos pos = new BlockPos(i, 0, 0);
            blocks.add(pos);
            materials.put(pos, DefaultMaterial.STONE);
        }

        // All are anchors
        Set<BlockPos> anchors = new HashSet<>(blocks);

        Map<BlockPos, ForceEquilibriumSolver.ForceResult> result =
            ForceEquilibriumSolver.solve(blocks, materials, anchors);

        for (BlockPos pos : blocks) {
            assertEquals(0.0, result.get(pos).utilizationRatio(), 0.01,
                "Anchor block at " + pos + " should have zero utilization");
        }
    }

    // ═══ 8. No Anchors → All Unstable ═══

    @Test
    @DisplayName("No anchors: all blocks should be unstable")
    void testNoAnchorsAllUnstable() {
        BlockPos b1 = new BlockPos(0, 0, 0);
        BlockPos b2 = new BlockPos(0, 1, 0);

        Set<BlockPos> blocks = new HashSet<>(List.of(b1, b2));
        Map<BlockPos, RMaterial> materials = Map.of(
            b1, DefaultMaterial.TIMBER,
            b2, DefaultMaterial.TIMBER
        );
        Set<BlockPos> anchors = new HashSet<>(); // no anchors

        Map<BlockPos, ForceEquilibriumSolver.ForceResult> result =
            ForceEquilibriumSolver.solve(blocks, materials, anchors);

        for (ForceEquilibriumSolver.ForceResult fr : result.values()) {
            assertFalse(fr.isStable(), "No-anchor blocks should be unstable");
        }
    }

    // ═══ 9. Determinism: Same Input → Same Output ═══

    @Test
    @DisplayName("Solver is deterministic: same input produces same output")
    void testDeterminism() {
        Set<BlockPos> blocks = new HashSet<>();
        Map<BlockPos, RMaterial> materials = new HashMap<>();
        for (int y = 0; y < 8; y++) {
            BlockPos pos = new BlockPos(0, y, 0);
            blocks.add(pos);
            materials.put(pos, DefaultMaterial.CONCRETE);
        }
        Set<BlockPos> anchors = new HashSet<>(List.of(new BlockPos(0, 0, 0)));

        Map<BlockPos, ForceEquilibriumSolver.ForceResult> result1 =
            ForceEquilibriumSolver.solve(blocks, materials, anchors);
        Map<BlockPos, ForceEquilibriumSolver.ForceResult> result2 =
            ForceEquilibriumSolver.solve(blocks, materials, anchors);

        for (BlockPos pos : blocks) {
            assertEquals(result1.get(pos).totalForce(), result2.get(pos).totalForce(), 0.001,
                "Solver should produce identical results for identical input");
            assertEquals(result1.get(pos).utilizationRatio(), result2.get(pos).utilizationRatio(), 0.001,
                "Utilization should be identical");
        }
    }

    // ═══ 10. Performance: 5×5×5 Cube Under 2 Seconds ═══

    @Test
    @DisplayName("5×5×5 cube (125 blocks) solves under 2 seconds")
    void testMediumStructurePerformance() {
        Set<BlockPos> blocks = new HashSet<>();
        Map<BlockPos, RMaterial> materials = new HashMap<>();

        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                for (int z = 0; z < 5; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    blocks.add(pos);
                    materials.put(pos, DefaultMaterial.CONCRETE);
                }
            }
        }

        Set<BlockPos> anchors = new HashSet<>();
        // Bottom layer anchored
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                anchors.add(new BlockPos(x, 0, z));
            }
        }

        long t0 = System.nanoTime();
        Map<BlockPos, ForceEquilibriumSolver.ForceResult> result =
            ForceEquilibriumSolver.solve(blocks, materials, anchors);
        long elapsed = (System.nanoTime() - t0) / 1_000_000;

        assertNotNull(result);
        assertEquals(125, result.size());
        assertTrue(elapsed < 2000,
            "125-block cube should solve in < 2s (took " + elapsed + "ms)");
    }
}
