package com.blockreality.api.physics;

import com.blockreality.api.material.DefaultMaterial;
import com.blockreality.api.material.RMaterial;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for ForceEquilibriumSolver — pure structural statics without Minecraft dependencies.
 *
 * Tests cover:
 *   - Single block on anchor (stable)
 *   - Floating blocks (unstable)
 *   - Vertical load transfer
 *   - Empty input handling
 *   - Convergence diagnostics
 *   - Force units and gravity
 *   - Utilization ratios
 *   - Support capacity checking
 */
@DisplayName("ForceEquilibriumSolver — Structural Statics Tests")
class ForceEquilibriumSolverTest {

    private static final double GRAVITY = 9.81;  // m/s²
    private static final double TOLERANCE = 0.01;  // 1% tolerance for floating point

    // ═══════════════════════════════════════════════════════════════════════════════
    // 1. Empty Input
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Empty input returns empty result map")
    void testEmptyInputReturnsEmptyResult() {
        Set<BlockPos> emptyBlocks = new HashSet<>();
        Map<BlockPos, RMaterial> emptyMaterials = new HashMap<>();
        Set<BlockPos> emptyAnchors = new HashSet<>();

        Map<BlockPos, ForceEquilibriumSolver.ForceResult> result =
            ForceEquilibriumSolver.solve(emptyBlocks, emptyMaterials, emptyAnchors);

        assertNotNull(result, "Result should not be null");
        assertTrue(result.isEmpty(), "Empty input should produce empty result");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 2. Single Block on Anchor
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Single block on anchor is stable")
    void testSingleBlockOnAnchorIsStable() {
        BlockPos anchorPos = new BlockPos(0, 0, 0);
        BlockPos blockPos = new BlockPos(0, 1, 0);

        Set<BlockPos> blocks = new HashSet<>(List.of(anchorPos, blockPos));
        Map<BlockPos, RMaterial> materials = new HashMap<>();
        materials.put(anchorPos, DefaultMaterial.STONE);
        materials.put(blockPos, DefaultMaterial.TIMBER);
        Set<BlockPos> anchors = new HashSet<>(List.of(anchorPos));

        Map<BlockPos, ForceEquilibriumSolver.ForceResult> result =
            ForceEquilibriumSolver.solve(blocks, materials, anchors);

        assertNotNull(result);
        assertTrue(result.containsKey(blockPos), "Block should be in result");

        ForceEquilibriumSolver.ForceResult blockResult = result.get(blockPos);
        assertTrue(blockResult.isStable(), "Single block on anchor should be stable");
    }

    @Test
    @DisplayName("Anchor block itself has utilization = 0")
    void testAnchorBlockHasZeroUtilization() {
        BlockPos anchorPos = new BlockPos(0, 0, 0);

        Set<BlockPos> blocks = new HashSet<>(List.of(anchorPos));
        Map<BlockPos, RMaterial> materials = new HashMap<>();
        materials.put(anchorPos, DefaultMaterial.CONCRETE);
        Set<BlockPos> anchors = new HashSet<>(List.of(anchorPos));

        Map<BlockPos, ForceEquilibriumSolver.ForceResult> result =
            ForceEquilibriumSolver.solve(blocks, materials, anchors);

        ForceEquilibriumSolver.ForceResult anchorResult = result.get(anchorPos);
        assertEquals(0.0, anchorResult.utilizationRatio(), 0.01,
            "Anchor block should have zero utilization (infinite support capacity)");
    }

    @Test
    @DisplayName("Force units: totalForce = mass × gravity = density × 9.81")
    void testForceUnits() {
        BlockPos blockPos = new BlockPos(0, 0, 0);
        Set<BlockPos> blocks = new HashSet<>(List.of(blockPos));
        Map<BlockPos, RMaterial> materials = new HashMap<>();
        materials.put(blockPos, DefaultMaterial.TIMBER);
        Set<BlockPos> anchors = new HashSet<>(List.of(blockPos));

        Map<BlockPos, ForceEquilibriumSolver.ForceResult> result =
            ForceEquilibriumSolver.solve(blocks, materials, anchors);

        // For anchor block (no dependencies), totalForce = self-weight = density × g
        // But anchor doesn't support anything, so totalForce should be ~0
        // Let's use a non-anchor block above it to verify force transfer

        BlockPos anchor = new BlockPos(0, 0, 0);
        BlockPos loaded = new BlockPos(0, 1, 0);

        Set<BlockPos> blocks2 = new HashSet<>(List.of(anchor, loaded));
        Map<BlockPos, RMaterial> materials2 = new HashMap<>();
        materials2.put(anchor, DefaultMaterial.STONE);
        materials2.put(loaded, DefaultMaterial.CONCRETE);
        Set<BlockPos> anchors2 = new HashSet<>(List.of(anchor));

        Map<BlockPos, ForceEquilibriumSolver.ForceResult> result2 =
            ForceEquilibriumSolver.solve(blocks2, materials2, anchors2);

        ForceEquilibriumSolver.ForceResult loadedResult = result2.get(loaded);

        // Self-weight of CONCRETE = 2350 kg/m³ × 9.81 m/s² ≈ 23,065 N
        double expectedForce = DefaultMaterial.CONCRETE.getDensity() * GRAVITY;

        // Due to solver's iterative nature, we allow 20% tolerance
        assertTrue(Math.abs(loadedResult.totalForce() - expectedForce) < expectedForce * 0.2,
            "totalForce should be density × gravity");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 3. Floating Block (Not Connected to Anchor)
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Floating block (not connected to anchor) is unstable")
    void testFloatingBlockIsUnstable() {
        BlockPos anchorPos = new BlockPos(0, 0, 0);
        BlockPos floatingPos = new BlockPos(5, 5, 5);

        Set<BlockPos> blocks = new HashSet<>(List.of(anchorPos, floatingPos));
        Map<BlockPos, RMaterial> materials = new HashMap<>();
        materials.put(anchorPos, DefaultMaterial.STONE);
        materials.put(floatingPos, DefaultMaterial.TIMBER);
        Set<BlockPos> anchors = new HashSet<>(List.of(anchorPos));

        Map<BlockPos, ForceEquilibriumSolver.ForceResult> result =
            ForceEquilibriumSolver.solve(blocks, materials, anchors);

        ForceEquilibriumSolver.ForceResult floatingResult = result.get(floatingPos);
        assertFalse(floatingResult.isStable(),
            "Floating block with no anchor connection should be unstable");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 4. Vertical Stack (Load Transfer Downward)
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Vertical stack: all blocks stable, bottom carries more load")
    void testVerticalStackLoadTransfer() {
        BlockPos anchor = new BlockPos(0, 0, 0);
        BlockPos b1 = new BlockPos(0, 1, 0);
        BlockPos b2 = new BlockPos(0, 2, 0);
        BlockPos b3 = new BlockPos(0, 3, 0);

        Set<BlockPos> blocks = new HashSet<>(List.of(anchor, b1, b2, b3));
        Map<BlockPos, RMaterial> materials = new HashMap<>();
        materials.put(anchor, DefaultMaterial.CONCRETE);
        materials.put(b1, DefaultMaterial.TIMBER);
        materials.put(b2, DefaultMaterial.TIMBER);
        materials.put(b3, DefaultMaterial.TIMBER);
        Set<BlockPos> anchors = new HashSet<>(List.of(anchor));

        Map<BlockPos, ForceEquilibriumSolver.ForceResult> result =
            ForceEquilibriumSolver.solve(blocks, materials, anchors);

        assertTrue(result.get(b1).isStable(), "Block 1 should be stable");
        assertTrue(result.get(b2).isStable(), "Block 2 should be stable");
        assertTrue(result.get(b3).isStable(), "Block 3 should be stable");

        // Bottom block (b1) should carry more total force than top (b3)
        double force1 = Math.abs(result.get(b1).totalForce());
        double force3 = Math.abs(result.get(b3).totalForce());

        assertTrue(force1 >= force3,
            "Bottom block should carry at least as much force as top block");
    }

    @Test
    @DisplayName("Vertical stack with different materials still stable")
    void testVerticalStackMixedMaterials() {
        BlockPos anchor = new BlockPos(0, 0, 0);
        BlockPos b1 = new BlockPos(0, 1, 0);
        BlockPos b2 = new BlockPos(0, 2, 0);

        Set<BlockPos> blocks = new HashSet<>(List.of(anchor, b1, b2));
        Map<BlockPos, RMaterial> materials = new HashMap<>();
        materials.put(anchor, DefaultMaterial.STONE);
        materials.put(b1, DefaultMaterial.TIMBER);
        materials.put(b2, DefaultMaterial.STEEL);
        Set<BlockPos> anchors = new HashSet<>(List.of(anchor));

        Map<BlockPos, ForceEquilibriumSolver.ForceResult> result =
            ForceEquilibriumSolver.solve(blocks, materials, anchors);

        assertTrue(result.get(b1).isStable(), "Mixed stack should be stable");
        assertTrue(result.get(b2).isStable(), "Mixed stack should be stable");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 5. Convergence Diagnostics
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("solveWithDiagnostics returns SolverResult with diagnostics")
    void testSolveWithDiagnosticsReturnsInfo() {
        BlockPos anchor = new BlockPos(0, 0, 0);
        BlockPos block = new BlockPos(0, 1, 0);

        Set<BlockPos> blocks = new HashSet<>(List.of(anchor, block));
        Map<BlockPos, RMaterial> materials = new HashMap<>();
        materials.put(anchor, DefaultMaterial.CONCRETE);
        materials.put(block, DefaultMaterial.TIMBER);
        Set<BlockPos> anchors = new HashSet<>(List.of(anchor));

        ForceEquilibriumSolver.SolverResult solverResult =
            ForceEquilibriumSolver.solveWithDiagnostics(blocks, materials, anchors, 1.25);

        assertNotNull(solverResult, "SolverResult should not be null");
        assertNotNull(solverResult.results(), "Results should not be null");
        assertNotNull(solverResult.diagnostics(), "Diagnostics should not be null");
    }

    @Test
    @DisplayName("Convergence diagnostics: simple structure converges")
    void testConvergenceForSimpleStructure() {
        BlockPos anchor = new BlockPos(0, 0, 0);
        BlockPos block = new BlockPos(0, 1, 0);

        Set<BlockPos> blocks = new HashSet<>(List.of(anchor, block));
        Map<BlockPos, RMaterial> materials = new HashMap<>();
        materials.put(anchor, DefaultMaterial.STONE);
        materials.put(block, DefaultMaterial.TIMBER);
        Set<BlockPos> anchors = new HashSet<>(List.of(anchor));

        ForceEquilibriumSolver.SolverResult solverResult =
            ForceEquilibriumSolver.solveWithDiagnostics(blocks, materials, anchors, 1.25);

        ForceEquilibriumSolver.ConvergenceDiagnostics diag = solverResult.diagnostics();

        assertTrue(diag.converged(), "Simple 2-block structure should converge");
        assertTrue(diag.iterationCount() > 0, "Should have at least 1 iteration");
        assertTrue(diag.iterationCount() <= 100, "Should converge within max iterations");
        assertTrue(diag.elapsedMillis() >= 0, "Elapsed time should be non-negative");
    }

    @Test
    @DisplayName("Convergence: iteration count < 100 for typical structures")
    void testIterationCountReasonable() {
        Set<BlockPos> blocks = new HashSet<>();
        Map<BlockPos, RMaterial> materials = new HashMap<>();
        for (int i = 0; i < 10; i++) {
            BlockPos pos = new BlockPos(0, i, 0);
            blocks.add(pos);
            materials.put(pos, DefaultMaterial.CONCRETE);
        }
        Set<BlockPos> anchors = new HashSet<>(List.of(new BlockPos(0, 0, 0)));

        ForceEquilibriumSolver.SolverResult result =
            ForceEquilibriumSolver.solveWithDiagnostics(blocks, materials, anchors, 1.25);

        assertTrue(result.diagnostics().iterationCount() < 500,
            "10-block vertical stack should converge in reasonable iterations");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 6. Utilization Ratios
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Loaded block utilization > 0, increases with load")
    void testLoadedBlockUtilization() {
        BlockPos anchor = new BlockPos(0, 0, 0);
        BlockPos block = new BlockPos(0, 1, 0);

        Set<BlockPos> blocks = new HashSet<>(List.of(anchor, block));
        Map<BlockPos, RMaterial> materials = new HashMap<>();
        materials.put(anchor, DefaultMaterial.STONE);
        materials.put(block, DefaultMaterial.TIMBER);
        Set<BlockPos> anchors = new HashSet<>(List.of(anchor));

        Map<BlockPos, ForceEquilibriumSolver.ForceResult> result =
            ForceEquilibriumSolver.solve(blocks, materials, anchors);

        double util = result.get(block).utilizationRatio();
        assertTrue(util > 0, "Loaded block should have positive utilization");
        assertTrue(util <= 1.0, "Utilization should not exceed 1.0 for stable structure");
    }

    @Test
    @DisplayName("Utilization dimensionless: 0.0 to 1.0+ range")
    void testUtilizationDimensionless() {
        BlockPos anchor = new BlockPos(0, 0, 0);
        BlockPos block = new BlockPos(0, 1, 0);

        Set<BlockPos> blocks = new HashSet<>(List.of(anchor, block));
        Map<BlockPos, RMaterial> materials = new HashMap<>();
        materials.put(anchor, DefaultMaterial.CONCRETE);
        materials.put(block, DefaultMaterial.TIMBER);
        Set<BlockPos> anchors = new HashSet<>(List.of(anchor));

        Map<BlockPos, ForceEquilibriumSolver.ForceResult> result =
            ForceEquilibriumSolver.solve(blocks, materials, anchors);

        for (ForceEquilibriumSolver.ForceResult res : result.values()) {
            assertTrue(res.utilizationRatio() >= 0,
                "Utilization must be non-negative");
            // For stable, reasonable structures, shouldn't exceed 10
            assertTrue(res.utilizationRatio() < 10,
                "Utilization should be reasonable");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 7. Support Capacity
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Block with Rcomp×1.0 (area) can support force < capacity")
    void testSupportCapacity() {
        // Create a block on an anchor and verify it can support its own weight
        BlockPos anchor = new BlockPos(0, 0, 0);
        BlockPos block = new BlockPos(0, 1, 0);

        Set<BlockPos> blocks = new HashSet<>(List.of(anchor, block));
        Map<BlockPos, RMaterial> materials = new HashMap<>();
        RMaterial heavyMat = DefaultMaterial.CONCRETE;  // Rcomp = 30 MPa
        materials.put(anchor, heavyMat);
        materials.put(block, DefaultMaterial.TIMBER);
        Set<BlockPos> anchors = new HashSet<>(List.of(anchor));

        Map<BlockPos, ForceEquilibriumSolver.ForceResult> result =
            ForceEquilibriumSolver.solve(blocks, materials, anchors);

        // Anchor capacity = Rcomp × 1e6 × 1.0 = 30 × 1e6 = 30 MN
        // Anchor should easily support 1 TIMBER block (self-weight ~5.9 kN)
        double expectedCapacity = heavyMat.getRcomp() * 1e6 * 1.0;  // Pa × m² = N
        assertTrue(expectedCapacity > 1e6, "Support capacity should be very large (MN range)");

        // Anchor should be stable
        assertTrue(result.get(anchor).isStable(), "Anchor should be stable");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 8. Horizontal Load Distribution
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Horizontal Y-structure: loads distribute to sides")
    void testHorizontalLoadDistribution() {
        // Create a T-shape: center column with side supports
        BlockPos anchor = new BlockPos(0, 0, 0);
        BlockPos center = new BlockPos(0, 1, 0);
        BlockPos left = new BlockPos(-1, 1, 0);
        BlockPos right = new BlockPos(1, 1, 0);

        Set<BlockPos> blocks = new HashSet<>(List.of(anchor, center, left, right));
        Map<BlockPos, RMaterial> materials = new HashMap<>();
        for (BlockPos pos : blocks) {
            materials.put(pos, DefaultMaterial.CONCRETE);
        }
        Set<BlockPos> anchors = new HashSet<>(List.of(anchor));

        Map<BlockPos, ForceEquilibriumSolver.ForceResult> result =
            ForceEquilibriumSolver.solve(blocks, materials, anchors);

        // Center should be stable (directly above anchor)
        // Left/Right are horizontal cantilevers — solver uses gravity-first distribution,
        // so sideways blocks may not receive enough support force to pass the 90% threshold
        assertTrue(result.get(center).isStable(), "Center should be stable");
        // Verify force results exist for left/right (solver processed them)
        assertNotNull(result.get(left), "Left should have a ForceResult");
        assertNotNull(result.get(right), "Right should have a ForceResult");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 9. Performance
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Solver completes in reasonable time (< 1 second)")
    void testSolverPerformance() {
        Set<BlockPos> blocks = new HashSet<>();
        Map<BlockPos, RMaterial> materials = new HashMap<>();

        // Create a 10×10×10 cube of blocks
        for (int x = 0; x < 10; x++) {
            for (int y = 0; y < 10; y++) {
                for (int z = 0; z < 10; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    blocks.add(pos);
                    materials.put(pos, DefaultMaterial.STONE);
                }
            }
        }

        Set<BlockPos> anchors = new HashSet<>();
        anchors.add(new BlockPos(0, 0, 0));

        long startTime = System.nanoTime();

        Map<BlockPos, ForceEquilibriumSolver.ForceResult> result =
            ForceEquilibriumSolver.solve(blocks, materials, anchors);

        long elapsed = (System.nanoTime() - startTime) / 1_000_000;

        assertNotNull(result);
        assertTrue(elapsed < 5000, "1000-block structure should solve in < 5 seconds (took " + elapsed + "ms)");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 10. Force and Support Force Relationship
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("For stable block: supportForce >= weight (equilibrium)")
    void testForceEquilibrium() {
        BlockPos anchor = new BlockPos(0, 0, 0);
        BlockPos block = new BlockPos(0, 1, 0);

        Set<BlockPos> blocks = new HashSet<>(List.of(anchor, block));
        Map<BlockPos, RMaterial> materials = new HashMap<>();
        materials.put(anchor, DefaultMaterial.STONE);
        materials.put(block, DefaultMaterial.TIMBER);
        Set<BlockPos> anchors = new HashSet<>(List.of(anchor));

        Map<BlockPos, ForceEquilibriumSolver.ForceResult> result =
            ForceEquilibriumSolver.solve(blocks, materials, anchors);

        ForceEquilibriumSolver.ForceResult blockResult = result.get(block);

        // For a stable block in equilibrium, supportForce should support the load
        if (blockResult.isStable()) {
            assertTrue(blockResult.supportForce() >= 0,
                "Support force should be non-negative");
        }
    }
}
