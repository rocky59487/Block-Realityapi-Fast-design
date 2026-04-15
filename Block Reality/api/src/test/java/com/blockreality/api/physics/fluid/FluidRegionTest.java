package com.blockreality.api.physics.fluid;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for FluidRegion — SoA fluid data container.
 *
 * Tests cover:
 * - Construction and dimension correctness
 * - flatIndex / subFlat coordinate math
 * - contains() boundary checks
 * - setVoxelType / setFluidState / getFluidState round-trips
 * - blockPressure() averaging
 * - hasBoundaryWall()
 * - FluidState factory methods (EMPTY, SOLID, fullWater)
 */
@DisplayName("FluidRegion — SoA data container tests")
class FluidRegionTest {

    private static final int SZ = 4;  // small 4×4×4 region for fast tests

    private FluidRegion region;

    @BeforeEach
    void setUp() {
        region = new FluidRegion(1, 0, 0, 0, SZ, SZ, SZ);
    }

    // ─── Construction ───

    @Test
    @DisplayName("freshly constructed region: all voxels are AIR with zero pressure")
    void testFreshRegionAllAir() {
        for (int z = 0; z < SZ; z++) {
            for (int y = 0; y < SZ; y++) {
                for (int x = 0; x < SZ; x++) {
                    int idx = region.flatIndex(x, y, z);
                    FluidState state = region.getFluidState(idx);
                    assertEquals(FluidType.AIR, state.type(),
                        "fresh voxel should be AIR at (" + x + "," + y + "," + z + ")");
                    assertEquals(0f, state.pressure(), 1e-6f);
                    assertFalse(state.hasFluid());
                }
            }
        }
    }

    @Test
    @DisplayName("totalVoxels == sizeX * sizeY * sizeZ")
    void testTotalVoxelCount() {
        assertEquals(SZ * SZ * SZ, region.getTotalVoxels());
    }

    @Test
    @DisplayName("sub-cell total == SZ^3 * SUB^3")
    void testSubTotalVoxels() {
        int sub = FluidRegion.SUB;
        assertEquals(SZ * sub * SZ * sub * SZ * sub, region.getSubTotalVoxels());
    }

    // ─── flatIndex ───

    @Test
    @DisplayName("flatIndex(0,0,0) == 0")
    void testFlatIndexOrigin() {
        assertEquals(0, region.flatIndex(0, 0, 0));
    }

    @Test
    @DisplayName("flatIndex increases correctly along x-axis")
    void testFlatIndexXIncrement() {
        assertEquals(1, region.flatIndex(1, 0, 0));
        assertEquals(2, region.flatIndex(2, 0, 0));
    }

    @Test
    @DisplayName("flatIndex increases correctly along y-axis (stride = sizeX)")
    void testFlatIndexYStride() {
        assertEquals(SZ, region.flatIndex(0, 1, 0));
    }

    @Test
    @DisplayName("flatIndex increases correctly along z-axis (stride = sizeX*sizeY)")
    void testFlatIndexZStride() {
        assertEquals(SZ * SZ, region.flatIndex(0, 0, 1));
    }

    @Test
    @DisplayName("flatIndex(BlockPos) returns -1 for out-of-bounds position")
    void testFlatIndexOutOfBounds() {
        assertEquals(-1, region.flatIndex(new BlockPos(SZ, 0, 0)));
        assertEquals(-1, region.flatIndex(new BlockPos(-1, 0, 0)));
        assertEquals(-1, region.flatIndex(new BlockPos(0, SZ, 0)));
        assertEquals(-1, region.flatIndex(new BlockPos(0, 0, SZ)));
    }

    @Test
    @DisplayName("flatIndex(BlockPos) with world origin offset")
    void testFlatIndexWithOffset() {
        FluidRegion offset = new FluidRegion(2, 10, 20, 30, SZ, SZ, SZ);
        assertEquals(0, offset.flatIndex(new BlockPos(10, 20, 30)));
        assertEquals(1, offset.flatIndex(new BlockPos(11, 20, 30)));
        assertEquals(-1, offset.flatIndex(new BlockPos(9, 20, 30)));
    }

    // ─── subFlat ───

    @Test
    @DisplayName("subFlat(0,0,0, 0,0,0) == 0")
    void testSubFlatOrigin() {
        assertEquals(0, region.subFlat(0, 0, 0, 0, 0, 0));
    }

    @Test
    @DisplayName("subFlat increments correctly for sub-cell x offset")
    void testSubFlatSubX() {
        assertEquals(1, region.subFlat(0, 0, 0, 1, 0, 0));
    }

    @Test
    @DisplayName("subFlat block x offset == SUB sub-cells")
    void testSubFlatBlockX() {
        assertEquals(FluidRegion.SUB, region.subFlat(1, 0, 0, 0, 0, 0));
    }

    // ─── contains ───

    @Test
    @DisplayName("contains() true for corners of the region")
    void testContainsCorners() {
        assertTrue(region.contains(new BlockPos(0, 0, 0)));
        assertTrue(region.contains(new BlockPos(SZ - 1, SZ - 1, SZ - 1)));
    }

    @Test
    @DisplayName("contains() false outside region")
    void testContainsOutside() {
        assertFalse(region.contains(new BlockPos(-1, 0, 0)));
        assertFalse(region.contains(new BlockPos(SZ, 0, 0)));
        assertFalse(region.contains(new BlockPos(0, -1, 0)));
        assertFalse(region.contains(new BlockPos(0, SZ, 0)));
    }

    // ─── setVoxelType / getFluidState ───

    @Test
    @DisplayName("setVoxelType(WATER) updates type and density, marks dirty")
    void testSetVoxelTypeWater() {
        int idx = region.flatIndex(1, 1, 1);
        region.setVoxelType(idx, FluidType.WATER);
        FluidState state = region.getFluidState(idx);
        assertEquals(FluidType.WATER, state.type());
        assertTrue(region.isDirty());
    }

    @Test
    @DisplayName("setVoxelType(AIR) zeroes out volume/phi/pressure")
    void testSetVoxelTypeAir() {
        int idx = region.flatIndex(1, 1, 1);
        region.setFluidState(idx, FluidType.WATER, 1.0f, 500f, 9810f);
        region.setVoxelType(idx, FluidType.AIR);
        FluidState state = region.getFluidState(idx);
        assertEquals(FluidType.AIR, state.type());
        assertEquals(0f, state.volume(), 1e-6f);
        assertEquals(0f, state.potential(), 1e-6f);
        assertEquals(0f, state.pressure(), 1e-6f);
    }

    @Test
    @DisplayName("setVoxelType(SOLID_WALL) registers as solid")
    void testSetVoxelTypeSolid() {
        int idx = region.flatIndex(2, 2, 2);
        region.setVoxelType(idx, FluidType.SOLID_WALL);
        FluidState state = region.getFluidState(idx);
        assertTrue(state.isSolid());
        assertFalse(state.hasFluid());
    }

    // ─── setFluidState round-trip ───

    @Test
    @DisplayName("setFluidState / getFluidState round-trip preserves all fields")
    void testSetFluidStateRoundTrip() {
        int idx = region.flatIndex(0, 2, 0);
        region.setFluidState(idx, FluidType.WATER, 0.75f, 123.0f, 98100f);
        FluidState state = region.getFluidState(idx);
        assertEquals(FluidType.WATER, state.type());
        assertEquals(0.75f, state.volume(), 1e-6f);
        assertEquals(123.0f, state.potential(), 1e-6f);
        assertEquals(98100f, state.pressure(), 0.1f);
    }

    @Test
    @DisplayName("getFluidState out-of-bounds index returns EMPTY")
    void testGetFluidStateOOB() {
        FluidState state = region.getFluidState(-1);
        assertEquals(FluidState.EMPTY, state);
        FluidState state2 = region.getFluidState(SZ * SZ * SZ);
        assertEquals(FluidState.EMPTY, state2);
    }

    // ─── hasBoundaryWall ───

    @Test
    @DisplayName("hasBoundaryWall() false when no SOLID_WALL voxels")
    void testNoBoundaryWall() {
        assertFalse(region.hasBoundaryWall());
    }

    @Test
    @DisplayName("hasBoundaryWall() true after setting one SOLID_WALL voxel")
    void testHasBoundaryWall() {
        region.setVoxelType(region.flatIndex(0, 0, 0), FluidType.SOLID_WALL);
        assertTrue(region.hasBoundaryWall());
    }

    // ─── blockPressure ───

    @Test
    @DisplayName("blockPressure() returns 0 for fresh (all-zero sub-pressure) voxel")
    void testBlockPressureZeroForFresh() {
        assertEquals(0f, region.blockPressure(0, 0, 0), 1e-6f);
    }

    // ─── FluidState factory methods ───

    @Test
    @DisplayName("FluidState.EMPTY has AIR type and zero fields")
    void testFluidStateEmpty() {
        assertEquals(FluidType.AIR, FluidState.EMPTY.type());
        assertEquals(0f, FluidState.EMPTY.volume(), 1e-6f);
        assertEquals(0f, FluidState.EMPTY.pressure(), 1e-6f);
        assertFalse(FluidState.EMPTY.hasFluid());
    }

    @Test
    @DisplayName("FluidState.SOLID has SOLID_WALL type")
    void testFluidStateSolid() {
        assertEquals(FluidType.SOLID_WALL, FluidState.SOLID.type());
        assertTrue(FluidState.SOLID.isSolid());
    }

    @Test
    @DisplayName("FluidState.fullWater(h) computes pressure = ρ·g·h")
    void testFluidStateFullWater() {
        float h = 5.0f;
        FluidState s = FluidState.fullWater(h);
        assertEquals(FluidType.WATER, s.type());
        assertEquals(1.0f, s.volume(), 1e-6f);
        assertTrue(s.hasFluid());
        // pressure = 1000 * 9.81 * 5 = 49050 Pa
        assertEquals(49050f, s.pressure(), 10f);
    }

    // ─── dirty flag ───

    @Test
    @DisplayName("isDirty() false for fresh region")
    void testNotDirtyInitially() {
        assertFalse(region.isDirty());
    }

    @Test
    @DisplayName("isDirty() true after setFluidState")
    void testDirtyAfterSetFluidState() {
        region.setFluidState(0, FluidType.WATER, 1f, 1f, 1f);
        assertTrue(region.isDirty());
    }

    @Test
    @DisplayName("markDirty() / clearDirty() cycle works")
    void testMarkAndClearDirty() {
        region.markDirty();
        assertTrue(region.isDirty());
        region.clearDirty();
        assertFalse(region.isDirty());
    }
}
