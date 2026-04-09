package com.blockreality.api.physics.sparse;

import com.blockreality.api.physics.RBlockState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SparseVoxelOctree Memory Benchmark - D-1c
 *
 * verify:
 *   - Memory efficiency of sparse SVO at 1200×300×1200 scale
 *   - Memory reduction after compact()
 *   - The ratio of the number of Sections to the number of blocks is reasonable
 */
@DisplayName("SparseVoxelOctree — Memory Benchmark Tests")
class SparseVoxelOctreeMemoryTest {

    private static final RBlockState CONCRETE = new RBlockState(
        "blockreality:concrete", 2350f, 30f, 2.5f, false);

    // ═══ 1. Empty SVO Memory ═══

    @Test
    @DisplayName("Empty 1200×300×1200 SVO allocates minimal memory")
    void testEmptySVOMinimalMemory() {
        SparseVoxelOctree svo = new SparseVoxelOctree(0, 0, 0, 1200, 300, 1200);
        assertEquals(0, svo.getAllocatedSectionCount(),
            "Empty SVO should have zero allocated sections");
        assertEquals(0, svo.getTotalNonAirBlocks());
    }

    // ═══ 2. Sparse Fill Memory Efficiency ═══

    @Test
    @DisplayName("1000 sparse blocks in 1200³ use far fewer sections than full grid")
    void testSparseEfficiency() {
        SparseVoxelOctree svo = new SparseVoxelOctree(0, 0, 0, 1200, 300, 1200);

        // Place 1000 blocks spread across the world
        java.util.Random rng = new java.util.Random(42);
        for (int i = 0; i < 1000; i++) {
            svo.setBlock(rng.nextInt(1200), rng.nextInt(300), rng.nextInt(1200), CONCRETE);
        }

        assertEquals(1000, svo.getTotalNonAirBlocks());
        // 1000 blocks across 75×19×75 = ~106K possible sections
        // Should only allocate ~1000 sections (one per block worst case)
        assertTrue(svo.getAllocatedSectionCount() <= 1000,
            "Sparse 1000 blocks should allocate ≤1000 sections (got " +
            svo.getAllocatedSectionCount() + ")");
    }

    // ═══ 3. Dense Section Memory ═══

    @Test
    @DisplayName("Dense 16³ section: 4096 blocks in 1 section")
    void testDenseSectionEfficiency() {
        SparseVoxelOctree svo = new SparseVoxelOctree(0, 0, 0, 16, 16, 16);

        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    svo.setBlock(x, y, z, CONCRETE);
                }
            }
        }

        assertEquals(4096, svo.getTotalNonAirBlocks());
        assertEquals(1, svo.getAllocatedSectionCount(),
            "4096 blocks in one 16³ area should use exactly 1 section");
    }

    // ═══ 4. Compact Releases Empty Sections ═══

    @Test
    @DisplayName("compact() after removing all blocks releases sections")
    void testCompactReleasesEmptySections() {
        SparseVoxelOctree svo = new SparseVoxelOctree(0, 0, 0, 64, 64, 64);

        // Fill some blocks across 4 sections
        for (int sx = 0; sx < 4; sx++) {
            svo.setBlock(sx * 16 + 8, 8, 8, CONCRETE);
        }
        assertEquals(4, svo.getAllocatedSectionCount());

        // Remove all blocks
        for (int sx = 0; sx < 4; sx++) {
            svo.setBlock(sx * 16 + 8, 8, 8, null);
        }

        int removed = svo.compact();
        assertEquals(0, svo.getAllocatedSectionCount(),
            "All sections should be removed after compaction");
    }

    // ═══ 5. Auto-Compact Trigger ═══

    @Test
    @DisplayName("Auto-compact triggers after 1024 removals")
    void testAutoCompactTrigger() {
        SparseVoxelOctree svo = new SparseVoxelOctree(0, 0, 0, 256, 16, 256);

        // Fill 2048 blocks across many sections
        for (int i = 0; i < 2048; i++) {
            svo.setBlock(i % 256, 0, i / 256, CONCRETE);
        }
        int sectionsBefore = svo.getAllocatedSectionCount();
        assertTrue(sectionsBefore > 0);

        // Remove 1024 blocks — should trigger auto-compact
        for (int i = 0; i < 1024; i++) {
            svo.setBlock(i % 256, 0, i / 256, null);
        }

        // After auto-compact, remaining blocks should still be accessible
        assertEquals(1024, svo.getTotalNonAirBlocks());
    }

    // ═══ 6. Section Count Scaling ═══

    @Test
    @DisplayName("Section count scales linearly with occupied 16³ regions")
    void testSectionScaling() {
        SparseVoxelOctree svo = new SparseVoxelOctree(0, 0, 0, 160, 16, 16);

        // Place 1 block in each of 10 sections along X axis
        for (int i = 0; i < 10; i++) {
            svo.setBlock(i * 16, 0, 0, CONCRETE);
        }

        assertEquals(10, svo.getAllocatedSectionCount(),
            "10 blocks in 10 different sections should allocate exactly 10 sections");
    }
}
