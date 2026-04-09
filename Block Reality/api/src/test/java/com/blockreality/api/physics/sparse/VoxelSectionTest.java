package com.blockreality.api.physics.sparse;

import com.blockreality.api.physics.RBlockState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * VoxelSection boundary and state transition testing - C-2
 *
 * cover:
 *   - EMPTY → HOMOGENEOUS → HETEROGENEOUS type automatic upgrade
 *   - Air filling does not change type
 *   - compact() downgrade
 *   - Cross-border access security
 *   - nonAirCount correct tracking
 *   - Memory estimation consistency
 */
@DisplayName("VoxelSection — Type Transitions & Boundary Tests")
class VoxelSectionTest {

    private static final RBlockState CONCRETE = new RBlockState(
        "blockreality:concrete", 2350f, 30f, 2.5f, false);
    private static final RBlockState STEEL = new RBlockState(
        "blockreality:steel", 7850f, 250f, 250f, false);
    private static final RBlockState ANCHOR = new RBlockState(
        "blockreality:anchor", 2500f, 40f, 3f, true);

    // ═══ 1. Initial State ═══

    @Test
    @DisplayName("New section is EMPTY with zero nonAirCount")
    void testNewSectionIsEmpty() {
        VoxelSection section = new VoxelSection(0, 0, 0);
        assertTrue(section.isEmpty(), "New section should be EMPTY");
        assertEquals(0, section.getNonAirCount(), "nonAirCount should be 0");
    }

    // ═══ 2. Single Block → HOMOGENEOUS ═══

    @Test
    @DisplayName("Setting one block transitions to non-empty")
    void testSingleBlockTransition() {
        VoxelSection section = new VoxelSection(0, 0, 0);
        section.setBlock(0, 0, 0, CONCRETE);

        assertFalse(section.isEmpty(), "Should not be EMPTY after setBlock");
        assertEquals(1, section.getNonAirCount(), "nonAirCount should be 1");

        RBlockState retrieved = section.getBlock(0, 0, 0);
        assertNotNull(retrieved, "Should retrieve the block");
        assertEquals("blockreality:concrete", retrieved.blockId(), "Block ID should match");
    }

    // ═══ 3. Two Different Materials → HETEROGENEOUS ═══

    @Test
    @DisplayName("Two different materials create HETEROGENEOUS section")
    void testHeterogeneousTransition() {
        VoxelSection section = new VoxelSection(0, 0, 0);
        section.setBlock(0, 0, 0, CONCRETE);
        section.setBlock(1, 0, 0, STEEL);

        assertEquals(2, section.getNonAirCount());

        // Both blocks should be retrievable
        assertEquals("blockreality:concrete", section.getBlock(0, 0, 0).blockId());
        assertEquals("blockreality:steel", section.getBlock(1, 0, 0).blockId());
    }

    // ═══ 4. Set Air Removes Block ═══

    @Test
    @DisplayName("Setting AIR removes block and decrements count")
    void testSetAirRemovesBlock() {
        VoxelSection section = new VoxelSection(0, 0, 0);
        section.setBlock(5, 5, 5, CONCRETE);
        assertEquals(1, section.getNonAirCount());

        section.setBlock(5, 5, 5, null);
        assertEquals(0, section.getNonAirCount());
    }

    // ═══ 5. Boundary Coordinates ═══

    @Test
    @DisplayName("Coordinates 0-15 are valid, boundary blocks stored correctly")
    void testBoundaryCoordinates() {
        VoxelSection section = new VoxelSection(0, 0, 0);

        // All 8 corners of the 16³ section
        int[][] corners = {
            {0,0,0}, {15,0,0}, {0,15,0}, {0,0,15},
            {15,15,0}, {15,0,15}, {0,15,15}, {15,15,15}
        };

        for (int i = 0; i < corners.length; i++) {
            section.setBlock(corners[i][0], corners[i][1], corners[i][2], CONCRETE);
        }

        assertEquals(8, section.getNonAirCount(), "All 8 corner blocks should be stored");

        for (int[] corner : corners) {
            RBlockState block = section.getBlock(corner[0], corner[1], corner[2]);
            assertNotNull(block, "Corner block at " + corner[0] + "," + corner[1] + "," + corner[2]);
        }
    }

    // ═══ 6. Full Section ═══

    @Test
    @DisplayName("Full 16³ section has 4096 nonAirCount")
    void testFullSection() {
        VoxelSection section = new VoxelSection(0, 0, 0);

        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    section.setBlock(x, y, z, CONCRETE);
                }
            }
        }

        assertEquals(4096, section.getNonAirCount(), "Full section should have 4096 blocks");
        assertFalse(section.isEmpty());
    }

    // ═══ 7. Compact ═══

    @Test
    @DisplayName("compact() after clearing all blocks returns to EMPTY")
    void testCompactToEmpty() {
        VoxelSection section = new VoxelSection(0, 0, 0);
        section.setBlock(0, 0, 0, CONCRETE);
        section.setBlock(0, 0, 0, null); // remove
        section.compact();

        assertTrue(section.isEmpty(), "Should be EMPTY after compact");
    }

    // ═══ 8. Overwrite Block ═══

    @Test
    @DisplayName("Overwriting block does not change nonAirCount")
    void testOverwriteBlock() {
        VoxelSection section = new VoxelSection(0, 0, 0);
        section.setBlock(3, 3, 3, CONCRETE);
        assertEquals(1, section.getNonAirCount());

        section.setBlock(3, 3, 3, STEEL);
        assertEquals(1, section.getNonAirCount(), "Overwrite should not increase count");
        assertEquals("blockreality:steel", section.getBlock(3, 3, 3).blockId());
    }

    // ═══ 9. Unset Position Returns AIR/null ═══

    @Test
    @DisplayName("Unset position returns AIR or null")
    void testUnsetPositionReturnsAir() {
        VoxelSection section = new VoxelSection(0, 0, 0);
        RBlockState block = section.getBlock(7, 7, 7);

        // Should return AIR or null for empty position
        assertTrue(block == null || block == RBlockState.AIR || block.mass() == 0f,
            "Empty position should return AIR/null/zero-mass");
    }

    // ═══ 10. Memory Estimation ═══

    @Test
    @DisplayName("Memory estimate increases with content")
    void testMemoryEstimation() {
        VoxelSection empty = new VoxelSection(0, 0, 0);
        long emptyMem = empty.estimateMemoryBytes();

        VoxelSection full = new VoxelSection(0, 0, 0);
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    full.setBlock(x, y, z, CONCRETE);
                }
            }
        }
        long fullMem = full.estimateMemoryBytes();

        assertTrue(fullMem > emptyMem,
            "Full section memory (" + fullMem + ") should exceed empty (" + emptyMem + ")");
    }

    // ═══ 11. Anchor Block Properties Preserved ═══

    @Test
    @DisplayName("Anchor flag preserved through set/get")
    void testAnchorFlagPreserved() {
        VoxelSection section = new VoxelSection(0, 0, 0);
        section.setBlock(0, 0, 0, ANCHOR);

        RBlockState retrieved = section.getBlock(0, 0, 0);
        assertTrue(retrieved.isAnchor(), "Anchor flag should be preserved");
    }
}
