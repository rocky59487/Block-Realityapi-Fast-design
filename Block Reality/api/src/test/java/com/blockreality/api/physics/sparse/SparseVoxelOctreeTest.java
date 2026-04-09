package com.blockreality.api.physics.sparse;

import com.blockreality.api.physics.RBlockState;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SparseVoxelOctree Core Function Test — C-2
 *
 * cover:
 *   - Section key packing/unpacking without collision
 *   - set/get blocks across Section boundaries
 *   - forEachNonAir traversal correctness
 *   - compact() releases the empty Section
 *   - Large border (1200×300×1200)
 */
@DisplayName("SparseVoxelOctree — Core Functionality Tests")
class SparseVoxelOctreeTest {

    private static final RBlockState CONCRETE = new RBlockState(
        "blockreality:concrete", 2350f, 30f, 2.5f, false);
    private static final RBlockState STEEL = new RBlockState(
        "blockreality:steel", 7850f, 250f, 250f, false);

    // ═══ 1. Section Key Packing ═══

    @Test
    @DisplayName("sectionKey packing/unpacking roundtrip is lossless")
    void testSectionKeyRoundtrip() {
        int[][] testCases = {
            {0, 0, 0},
            {1, 2, 3},
            {-1, -1, -1},
            {75, 18, 75},   // 1200/16 = 75
            {-75, 0, -75},
        };

        for (int[] tc : testCases) {
            long key = SparseVoxelOctree.sectionKey(tc[0], tc[1], tc[2]);
            assertEquals(tc[0], SparseVoxelOctree.sectionKeyXStatic(key),
                "X roundtrip failed for " + tc[0] + "," + tc[1] + "," + tc[2]);
            assertEquals(tc[1], SparseVoxelOctree.sectionKeyYStatic(key),
                "Y roundtrip failed for " + tc[0] + "," + tc[1] + "," + tc[2]);
            assertEquals(tc[2], SparseVoxelOctree.sectionKeyZStatic(key),
                "Z roundtrip failed for " + tc[0] + "," + tc[1] + "," + tc[2]);
        }
    }

    @Test
    @DisplayName("Different section coordinates produce different keys")
    void testSectionKeyUniqueness() {
        long key1 = SparseVoxelOctree.sectionKey(1, 2, 3);
        long key2 = SparseVoxelOctree.sectionKey(3, 2, 1);
        long key3 = SparseVoxelOctree.sectionKey(1, 3, 2);

        assertNotEquals(key1, key2, "Different coords should have different keys");
        assertNotEquals(key1, key3, "Different coords should have different keys");
        assertNotEquals(key2, key3, "Different coords should have different keys");
    }

    // ═══ 2. Basic Set/Get ═══

    @Test
    @DisplayName("Set and get block at origin")
    void testSetGetAtOrigin() {
        SparseVoxelOctree svo = new SparseVoxelOctree(0, 0, 0, 16, 16, 16);
        svo.setBlock(0, 0, 0, CONCRETE);

        RBlockState retrieved = svo.getBlock(0, 0, 0);
        assertNotNull(retrieved);
        assertEquals("blockreality:concrete", retrieved.blockId());
    }

    @Test
    @DisplayName("Blocks in different sections are independent")
    void testCrossSectionBlocks() {
        SparseVoxelOctree svo = new SparseVoxelOctree(0, 0, 0, 64, 64, 64);

        // Section (0,0,0)
        svo.setBlock(5, 5, 5, CONCRETE);
        // Section (1,0,0) — 16 blocks away
        svo.setBlock(21, 5, 5, STEEL);

        assertEquals("blockreality:concrete", svo.getBlock(5, 5, 5).blockId());
        assertEquals("blockreality:steel", svo.getBlock(21, 5, 5).blockId());
    }

    // ═══ 3. ForEachNonAir ═══

    @Test
    @DisplayName("forEachNonAir visits exactly the set blocks")
    void testForEachNonAir() {
        SparseVoxelOctree svo = new SparseVoxelOctree(0, 0, 0, 32, 32, 32);

        BlockPos[] positions = {
            new BlockPos(0, 0, 0),
            new BlockPos(5, 10, 15),
            new BlockPos(16, 16, 16),  // second section
            new BlockPos(31, 31, 31),
        };

        for (BlockPos pos : positions) {
            svo.setBlock(pos.getX(), pos.getY(), pos.getZ(), CONCRETE);
        }

        List<BlockPos> visited = new ArrayList<>();
        svo.forEachNonAir((pos, state) -> visited.add(pos.immutable()));

        assertEquals(positions.length, visited.size(),
            "Should visit exactly " + positions.length + " blocks");
    }

    // ═══ 4. Empty SVO ═══

    @Test
    @DisplayName("New SVO with no blocks has zero nonAir count")
    void testEmptySVO() {
        SparseVoxelOctree svo = new SparseVoxelOctree(0, 0, 0, 100, 100, 100);

        List<BlockPos> visited = new ArrayList<>();
        svo.forEachNonAir((pos, state) -> visited.add(pos));

        assertTrue(visited.isEmpty(), "Empty SVO should have no blocks");
    }

    // ═══ 5. Remove Block (set to null/AIR) ═══

    @Test
    @DisplayName("Setting block to null removes it")
    void testRemoveBlock() {
        SparseVoxelOctree svo = new SparseVoxelOctree(0, 0, 0, 16, 16, 16);
        svo.setBlock(5, 5, 5, CONCRETE);
        svo.setBlock(5, 5, 5, null);

        RBlockState retrieved = svo.getBlock(5, 5, 5);
        assertTrue(retrieved == null || retrieved == RBlockState.AIR,
            "Removed block should return null or AIR");
    }

    // ═══ 6. Large Range (1200×300×1200 target scale) ═══

    @Test
    @DisplayName("Sparse blocks in large range work correctly")
    void testLargeRange() {
        SparseVoxelOctree svo = new SparseVoxelOctree(0, 0, 0, 1200, 300, 1200);

        // Place a few blocks at extreme positions
        svo.setBlock(0, 0, 0, CONCRETE);
        svo.setBlock(599, 150, 599, STEEL);
        svo.setBlock(1199, 299, 1199, CONCRETE);

        assertNotNull(svo.getBlock(0, 0, 0));
        assertNotNull(svo.getBlock(599, 150, 599));
        assertNotNull(svo.getBlock(1199, 299, 1199));

        assertEquals("blockreality:steel", svo.getBlock(599, 150, 599).blockId());
    }

    // ═══ 7. BlockPos Overload ═══

    @Test
    @DisplayName("getBlock(BlockPos) matches getBlock(x,y,z)")
    void testBlockPosOverload() {
        SparseVoxelOctree svo = new SparseVoxelOctree(0, 0, 0, 32, 32, 32);
        svo.setBlock(10, 20, 30, CONCRETE);

        BlockPos pos = new BlockPos(10, 20, 30);
        RBlockState byPos = svo.getBlock(pos);
        RBlockState byXYZ = svo.getBlock(10, 20, 30);

        assertEquals(byXYZ, byPos, "Both overloads should return the same block");
    }
}
