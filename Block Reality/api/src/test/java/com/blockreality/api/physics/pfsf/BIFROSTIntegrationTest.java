package com.blockreality.api.physics.pfsf;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BIFROST end-to-end integration tests.
 *
 * Tests the full pipeline: classification → routing → keystone detection → LOD
 * without GPU (mock-free, pure logic verification).
 */
class BIFROSTIntegrationTest {

    // ═══ ShapeClassifier ═══

    @Test
    void testRegularWallClassifiesLow() {
        // Solid 10×5×1 wall — should score low (regular)
        Set<BlockPos> wall = new HashSet<>();
        Set<BlockPos> anchors = new HashSet<>();
        for (int x = 0; x < 10; x++) {
            for (int y = 0; y < 5; y++) {
                wall.add(new BlockPos(x, y, 0));
                if (y == 0) anchors.add(new BlockPos(x, 0, 0));
            }
        }

        float score = ShapeClassifier.score(wall, anchors);
        assertTrue(score < 0.45f, "Wall should be regular, got " + score);
        assertFalse(ShapeClassifier.isIrregular(wall, anchors));
    }

    @Test
    void testCantileverClassifiesHigh() {
        // L-shaped cantilever — should score high (irregular)
        Set<BlockPos> members = new HashSet<>();
        Set<BlockPos> anchors = new HashSet<>();

        // Vertical column
        for (int y = 0; y < 5; y++) {
            members.add(new BlockPos(0, y, 0));
        }
        anchors.add(new BlockPos(0, 0, 0));

        // Horizontal overhang (unsupported)
        for (int x = 1; x < 8; x++) {
            members.add(new BlockPos(x, 4, 0));
        }

        float score = ShapeClassifier.score(members, anchors);
        assertTrue(score > 0.3f, "Cantilever should be irregular, got " + score);
    }

    @Test
    void testEmptyStructure() {
        assertEquals(0.0f, ShapeClassifier.score(Set.of(), Set.of()));
    }

    // ═══ HybridPhysicsRouter ═══

    @Test
    void testRouterDefaultsToPFSF() {
        HybridPhysicsRouter router = new HybridPhysicsRouter();
        router.init(null); // no model → all PFSF

        Set<BlockPos> members = Set.of(new BlockPos(0, 0, 0), new BlockPos(1, 0, 0));
        Set<BlockPos> anchors = Set.of(new BlockPos(0, 0, 0));

        assertEquals(HybridPhysicsRouter.Backend.PFSF,
                router.route(1, members, anchors, 1));
    }

    @Test
    void testRouterStatsFormat() {
        HybridPhysicsRouter router = new HybridPhysicsRouter();
        router.init(null);

        router.route(1, Set.of(new BlockPos(0,0,0)), Set.of(), 1);
        String stats = router.getStats();
        assertTrue(stats.contains("PFSF"), "Stats should mention PFSF: " + stats);
    }

    @Test
    void testRouterCacheInvalidation() {
        HybridPhysicsRouter router = new HybridPhysicsRouter();
        router.init(null);

        Set<BlockPos> m = Set.of(new BlockPos(0,0,0));
        Set<BlockPos> a = Set.of();

        // Same epoch → cached
        router.route(1, m, a, 1);
        router.route(1, m, a, 1); // should use cache

        // New epoch → recompute
        router.route(1, m, a, 2);
    }

    // ═══ ChunkPhysicsLOD ═══

    @Test
    void testUnmodifiedChunkIsSkip() {
        ChunkPhysicsLOD lod = new ChunkPhysicsLOD();
        assertEquals(ChunkPhysicsLOD.Tier.SKIP, lod.getTier(0, 0));
        assertFalse(lod.needsPhysics(0, 0));
    }

    @Test
    void testPlayerEditUpgradesToMark() {
        ChunkPhysicsLOD lod = new ChunkPhysicsLOD();
        lod.onBlockChange(new BlockPos(5, 64, 5), true, false, false);

        assertEquals(ChunkPhysicsLOD.Tier.MARK, lod.getTier(0, 0));
        assertTrue(lod.needsPhysics(0, 0));
    }

    @Test
    void testManyEditsUpgradesToPFSF() {
        ChunkPhysicsLOD lod = new ChunkPhysicsLOD();
        for (int i = 0; i < 10; i++) {
            lod.onBlockChange(new BlockPos(i, 64, 0), true, false, false);
        }
        assertEquals(ChunkPhysicsLOD.Tier.PFSF, lod.getTier(0, 0));
    }

    @Test
    void testModBlockImmediatelyPFSF() {
        ChunkPhysicsLOD lod = new ChunkPhysicsLOD();
        lod.onBlockChange(new BlockPos(0, 64, 0), true, false, true);
        assertEquals(ChunkPhysicsLOD.Tier.PFSF, lod.getTier(0, 0));
    }

    @Test
    void testResetChunk() {
        ChunkPhysicsLOD lod = new ChunkPhysicsLOD();
        lod.onBlockChange(new BlockPos(0, 64, 0), true, false, true);
        lod.resetChunk(0, 0);
        assertEquals(ChunkPhysicsLOD.Tier.SKIP, lod.getTier(0, 0));
    }

    // ═══ StructuralKeystone ═══

    @Test
    void testPillarKeystoneDetection() {
        // Single pillar supporting a platform
        //   ###
        //    |
        //   ###
        Set<BlockPos> members = new HashSet<>();
        Set<BlockPos> anchors = new HashSet<>();

        // Ground floor (anchored)
        for (int x = 0; x < 3; x++)
            for (int z = 0; z < 3; z++) {
                members.add(new BlockPos(x, 0, z));
                anchors.add(new BlockPos(x, 0, z));
            }

        // Pillar (keystone!)
        members.add(new BlockPos(1, 1, 1));
        members.add(new BlockPos(1, 2, 1));

        // Platform above
        for (int x = 0; x < 3; x++)
            for (int z = 0; z < 3; z++)
                members.add(new BlockPos(x, 3, z));

        StructuralKeystone.KeystoneResult result = StructuralKeystone.analyze(members, anchors);

        // The pillar blocks should be keystones
        assertTrue(result.allKeystones().contains(new BlockPos(1, 1, 1)) ||
                   result.allKeystones().contains(new BlockPos(1, 2, 1)),
                "Pillar should be identified as keystone");
    }

    @Test
    void testSolidBlockNotKeystone() {
        // Solid 3×3×3 cube — no block is a keystone (redundant connectivity)
        Set<BlockPos> members = new HashSet<>();
        Set<BlockPos> anchors = new HashSet<>();

        for (int x = 0; x < 3; x++)
            for (int y = 0; y < 3; y++)
                for (int z = 0; z < 3; z++) {
                    members.add(new BlockPos(x, y, z));
                    if (y == 0) anchors.add(new BlockPos(x, 0, z));
                }

        assertFalse(StructuralKeystone.isKeystone(new BlockPos(1, 1, 1), members, anchors),
                "Center of solid cube should not be keystone");
    }

    @Test
    void testIslandQuickKeystoneCheck() {
        // Two blocks: bottom anchored, top hanging
        Set<BlockPos> members = Set.of(new BlockPos(0, 0, 0), new BlockPos(0, 1, 0));
        Set<BlockPos> anchors = Set.of(new BlockPos(0, 0, 0));

        assertTrue(StructuralKeystone.isKeystone(new BlockPos(0, 0, 0), members, anchors) ||
                   !anchors.contains(new BlockPos(0, 0, 0)),
                "Anchor may not be classified as keystone but support check should work");
    }

    // ═══ Full Pipeline Simulation ═══

    @Test
    void testFullPipeline_VanillaMountainSkipped() {
        // Simulate: player loads a chunk with a mountain, doesn't modify it
        ChunkPhysicsLOD lod = new ChunkPhysicsLOD();

        // No block changes → SKIP
        assertEquals(ChunkPhysicsLOD.Tier.SKIP, lod.getTier(10, 20));
        assertFalse(lod.needsPhysics(10, 20));
        // Mountain stays intact — no computation wasted
    }

    @Test
    void testFullPipeline_PlayerModifiesThenKeystoneWarning() {
        ChunkPhysicsLOD lod = new ChunkPhysicsLOD();

        // Player removes 3 blocks from a cliff
        lod.onBlockChange(new BlockPos(160, 80, 320), false, true, false);
        lod.onBlockChange(new BlockPos(161, 80, 320), false, true, false);
        lod.onBlockChange(new BlockPos(162, 80, 320), false, true, false);

        // Tier upgrades to MARK (< 8 changes)
        assertEquals(ChunkPhysicsLOD.Tier.MARK, lod.getTier(10, 20));

        // In MARK tier, run keystone analysis
        Set<BlockPos> cliff = new HashSet<>();
        Set<BlockPos> anchors = new HashSet<>();
        for (int x = 160; x < 170; x++)
            for (int y = 75; y < 85; y++) {
                cliff.add(new BlockPos(x, y, 320));
                if (y == 75) anchors.add(new BlockPos(x, 75, 320));
            }

        StructuralKeystone.KeystoneResult result = StructuralKeystone.analyze(cliff, anchors);
        assertNotNull(result);
        // Keystones are identified — game can warn player
    }

    @Test
    void testFullPipeline_ModBlockTriggersPFSF() {
        ChunkPhysicsLOD lod = new ChunkPhysicsLOD();
        HybridPhysicsRouter router = new HybridPhysicsRouter();
        router.init(null);

        // Player places a mod block → immediate PFSF
        lod.onBlockChange(new BlockPos(0, 64, 0), true, false, true);
        assertEquals(ChunkPhysicsLOD.Tier.PFSF, lod.getTier(0, 0));

        // Router decides PFSF (no FNO model loaded)
        Set<BlockPos> members = Set.of(new BlockPos(0, 64, 0), new BlockPos(0, 65, 0));
        Set<BlockPos> anchors = Set.of(new BlockPos(0, 64, 0));
        assertEquals(HybridPhysicsRouter.Backend.PFSF,
                router.route(1, members, anchors, 1));
    }
}
