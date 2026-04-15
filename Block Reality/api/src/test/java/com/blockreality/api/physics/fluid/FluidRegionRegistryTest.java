package com.blockreality.api.physics.fluid;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for FluidRegionRegistry.
 *
 * Tests cover:
 * - Singleton pattern
 * - getOrCreateRegion — creates on miss, returns same on hit
 * - regionKey alignment/tiling
 * - getRegion — null on miss
 * - getRegionById — found / not found
 * - removeRegion
 * - getActiveRegions / getRegionCount
 * - clear()
 * - negative coordinate handling
 */
@DisplayName("FluidRegionRegistry tests")
class FluidRegionRegistryTest {

    private static final int SIZE = 16;

    private FluidRegionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = FluidRegionRegistry.getInstance();
        registry.clear();  // isolate each test
    }

    @AfterEach
    void tearDown() {
        registry.clear();
    }

    // ─── Singleton ───

    @Test
    @DisplayName("getInstance() returns non-null singleton")
    void testSingleton() {
        assertNotNull(registry);
        assertSame(registry, FluidRegionRegistry.getInstance());
    }

    // ─── getOrCreateRegion ───

    @Test
    @DisplayName("getOrCreateRegion creates a new region when none exists")
    void testGetOrCreateNew() {
        FluidRegion r = registry.getOrCreateRegion(new BlockPos(0, 0, 0), SIZE);
        assertNotNull(r);
        assertEquals(1, registry.getRegionCount());
    }

    @Test
    @DisplayName("getOrCreateRegion returns same region for two positions in the same grid cell")
    void testGetOrCreateSameCell() {
        FluidRegion r1 = registry.getOrCreateRegion(new BlockPos(0, 0, 0), SIZE);
        FluidRegion r2 = registry.getOrCreateRegion(new BlockPos(5, 5, 5), SIZE);
        assertSame(r1, r2, "Both positions are in the same 16³ cell — should be same region");
        assertEquals(1, registry.getRegionCount());
    }

    @Test
    @DisplayName("getOrCreateRegion creates different regions for different grid cells")
    void testGetOrCreateDifferentCells() {
        FluidRegion r1 = registry.getOrCreateRegion(new BlockPos(0, 0, 0), SIZE);
        FluidRegion r2 = registry.getOrCreateRegion(new BlockPos(SIZE, 0, 0), SIZE);
        assertNotSame(r1, r2);
        assertEquals(2, registry.getRegionCount());
    }

    @Test
    @DisplayName("region origin is aligned to grid cell boundary")
    void testRegionOriginAlignment() {
        FluidRegion r = registry.getOrCreateRegion(new BlockPos(7, 13, 20), SIZE);
        // origin = floor(7/16)*16=0, floor(13/16)*16=0, floor(20/16)*16=16
        assertEquals(0,  r.getOriginX());
        assertEquals(0,  r.getOriginY());
        assertEquals(16, r.getOriginZ());
    }

    @Test
    @DisplayName("region has correct size dimensions")
    void testRegionSize() {
        FluidRegion r = registry.getOrCreateRegion(new BlockPos(0, 0, 0), SIZE);
        assertEquals(SIZE, r.getSizeX());
        assertEquals(SIZE, r.getSizeY());
        assertEquals(SIZE, r.getSizeZ());
    }

    @Test
    @DisplayName("region IDs are unique and auto-incrementing")
    void testRegionIdsUnique() {
        FluidRegion r1 = registry.getOrCreateRegion(new BlockPos(0, 0, 0), SIZE);
        FluidRegion r2 = registry.getOrCreateRegion(new BlockPos(SIZE, 0, 0), SIZE);
        assertNotEquals(r1.getRegionId(), r2.getRegionId());
    }

    // ─── getRegion ───

    @Test
    @DisplayName("getRegion returns null when no region exists at position")
    void testGetRegionMiss() {
        assertNull(registry.getRegion(new BlockPos(0, 0, 0), SIZE));
    }

    @Test
    @DisplayName("getRegion returns existing region after creation")
    void testGetRegionHit() {
        FluidRegion created = registry.getOrCreateRegion(new BlockPos(3, 3, 3), SIZE);
        FluidRegion found = registry.getRegion(new BlockPos(0, 0, 0), SIZE);
        assertSame(created, found);
    }

    // ─── getRegionById ───

    @Test
    @DisplayName("getRegionById finds region by ID")
    void testGetRegionById() {
        FluidRegion r = registry.getOrCreateRegion(new BlockPos(0, 0, 0), SIZE);
        FluidRegion found = registry.getRegionById(r.getRegionId());
        assertSame(r, found);
    }

    @Test
    @DisplayName("getRegionById returns null for non-existent ID")
    void testGetRegionByIdMiss() {
        assertNull(registry.getRegionById(99999));
    }

    @Test
    @DisplayName("getRegionById after multiple regions finds correct one")
    void testGetRegionByIdMultiple() {
        FluidRegion r1 = registry.getOrCreateRegion(new BlockPos(0, 0, 0), SIZE);
        FluidRegion r2 = registry.getOrCreateRegion(new BlockPos(SIZE, 0, 0), SIZE);
        FluidRegion r3 = registry.getOrCreateRegion(new BlockPos(0, SIZE, 0), SIZE);

        assertSame(r1, registry.getRegionById(r1.getRegionId()));
        assertSame(r2, registry.getRegionById(r2.getRegionId()));
        assertSame(r3, registry.getRegionById(r3.getRegionId()));
    }

    // ─── removeRegion ───

    @Test
    @DisplayName("removeRegion removes the region and returns true")
    void testRemoveRegion() {
        FluidRegion r = registry.getOrCreateRegion(new BlockPos(0, 0, 0), SIZE);
        assertTrue(registry.removeRegion(r.getRegionId()));
        assertEquals(0, registry.getRegionCount());
    }

    @Test
    @DisplayName("removeRegion returns false for non-existent ID")
    void testRemoveRegionMiss() {
        assertFalse(registry.removeRegion(99999));
    }

    @Test
    @DisplayName("after removeRegion, getRegionById returns null")
    void testRemoveRegionThenLookup() {
        FluidRegion r = registry.getOrCreateRegion(new BlockPos(0, 0, 0), SIZE);
        int id = r.getRegionId();
        registry.removeRegion(id);
        assertNull(registry.getRegionById(id));
    }

    // ─── getActiveRegions / getRegionCount ───

    @Test
    @DisplayName("getActiveRegions returns unmodifiable view")
    void testGetActiveRegionsUnmodifiable() {
        registry.getOrCreateRegion(new BlockPos(0, 0, 0), SIZE);
        assertThrows(UnsupportedOperationException.class,
            () -> registry.getActiveRegions().clear());
    }

    @Test
    @DisplayName("getRegionCount reflects registration/removal")
    void testGetRegionCount() {
        assertEquals(0, registry.getRegionCount());
        FluidRegion r1 = registry.getOrCreateRegion(new BlockPos(0, 0, 0), SIZE);
        assertEquals(1, registry.getRegionCount());
        FluidRegion r2 = registry.getOrCreateRegion(new BlockPos(SIZE, 0, 0), SIZE);
        assertEquals(2, registry.getRegionCount());
        registry.removeRegion(r1.getRegionId());
        assertEquals(1, registry.getRegionCount());
        registry.removeRegion(r2.getRegionId());
        assertEquals(0, registry.getRegionCount());
    }

    // ─── clear ───

    @Test
    @DisplayName("clear() removes all regions")
    void testClear() {
        registry.getOrCreateRegion(new BlockPos(0, 0, 0), SIZE);
        registry.getOrCreateRegion(new BlockPos(SIZE, 0, 0), SIZE);
        registry.getOrCreateRegion(new BlockPos(0, SIZE, 0), SIZE);
        registry.clear();
        assertEquals(0, registry.getRegionCount());
    }

    // ─── Negative coordinates ───

    @Test
    @DisplayName("negative coordinates: getOrCreateRegion does not collide with positive")
    void testNegativeCoordinates() {
        FluidRegion pos = registry.getOrCreateRegion(new BlockPos(0, 0, 0), SIZE);
        FluidRegion neg = registry.getOrCreateRegion(new BlockPos(-SIZE, 0, 0), SIZE);
        assertNotSame(pos, neg, "negative-X region must be distinct from origin region");
        assertEquals(2, registry.getRegionCount());
    }

    @Test
    @DisplayName("positions spanning negative/positive boundary map to distinct regions")
    void testBoundaryNegativePositive() {
        // -1 and 0 are in different grid cells when regionSize=16
        FluidRegion negSide = registry.getOrCreateRegion(new BlockPos(-1, 0, 0), SIZE);
        FluidRegion posSide = registry.getOrCreateRegion(new BlockPos(0, 0, 0), SIZE);
        assertNotSame(negSide, posSide);
    }

    @Test
    @DisplayName("fully negative coordinates work without key collision")
    void testFullyNegativeCoords() {
        FluidRegion r = registry.getOrCreateRegion(new BlockPos(-100, -50, -200), SIZE);
        assertNotNull(r);
        // Confirm we can find it back
        FluidRegion found = registry.getRegionById(r.getRegionId());
        assertSame(r, found);
    }
}
