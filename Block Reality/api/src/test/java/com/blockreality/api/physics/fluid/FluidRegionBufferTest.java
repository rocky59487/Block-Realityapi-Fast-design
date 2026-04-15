package com.blockreality.api.physics.fluid;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for FluidRegionBuffer — CPU-only mode (Vulkan unavailable).
 *
 * Since Vulkan is not available in CI/sandbox, these tests exercise the
 * CPU-only fallback path:
 *   - allocate() sets allocated=true without VMA calls
 *   - free() is safe to call on CPU-only buffer
 *   - refCount behavior
 *   - dirty flag
 *   - regionId is preserved
 */
@DisplayName("FluidRegionBuffer — CPU-only mode tests")
class FluidRegionBufferTest {

    // ─── Construction ───

    @Test
    @DisplayName("constructor sets regionId correctly")
    void testConstructorRegionId() {
        FluidRegionBuffer buf = new FluidRegionBuffer(42);
        assertEquals(42, buf.getRegionId());
    }

    @Test
    @DisplayName("fresh buffer is not allocated")
    void testFreshNotAllocated() {
        FluidRegionBuffer buf = new FluidRegionBuffer(1);
        assertFalse(buf.isAllocated());
    }

    // ─── allocate / CPU-only path ───

    @Test
    @DisplayName("allocate() in CPU-only mode (no Vulkan) sets allocated=true")
    void testAllocateCpuMode() {
        // VulkanComputeContext.isAvailable() returns false in test environment
        FluidRegionBuffer buf = new FluidRegionBuffer(1);
        buf.allocate(4, 4, 4, new BlockPos(0, 0, 0));
        assertTrue(buf.isAllocated(), "CPU-only allocate should set allocated=true");
    }

    @Test
    @DisplayName("allocate() stores dimensions correctly (N = Lx*Ly*Lz)")
    void testAllocateDimensions() {
        FluidRegionBuffer buf = new FluidRegionBuffer(1);
        buf.allocate(2, 3, 4, BlockPos.ZERO);
        assertEquals(2 * 3 * 4, buf.getN());
    }

    @Test
    @DisplayName("allocate() stores sub-cell count (subN = Lx*10 * Ly*10 * Lz*10)")
    void testAllocateSubN() {
        FluidRegionBuffer buf = new FluidRegionBuffer(1);
        int lx = 2, ly = 2, lz = 2;
        buf.allocate(lx, ly, lz, BlockPos.ZERO);
        int expected = (lx * FluidRegion.SUB) * (ly * FluidRegion.SUB) * (lz * FluidRegion.SUB);
        assertEquals(expected, buf.getSubN());
    }

    @Test
    @DisplayName("allocate() stores origin BlockPos")
    void testAllocateOrigin() {
        FluidRegionBuffer buf = new FluidRegionBuffer(1);
        BlockPos origin = new BlockPos(10, 20, 30);
        buf.allocate(4, 4, 4, origin);
        assertEquals(origin, buf.getOrigin());
    }

    // ─── free() ───

    @Test
    @DisplayName("free() on unallocated buffer is safe (no throw)")
    void testFreeUnallocatedSafe() {
        FluidRegionBuffer buf = new FluidRegionBuffer(1);
        assertDoesNotThrow(buf::free);
    }

    @Test
    @DisplayName("free() after allocate sets allocated=false")
    void testFreeAfterAllocate() {
        FluidRegionBuffer buf = new FluidRegionBuffer(1);
        buf.allocate(4, 4, 4, BlockPos.ZERO);
        assertTrue(buf.isAllocated());
        buf.free();
        assertFalse(buf.isAllocated());
    }

    @Test
    @DisplayName("free() twice is safe (idempotent)")
    void testFreeIdempotent() {
        FluidRegionBuffer buf = new FluidRegionBuffer(1);
        buf.allocate(4, 4, 4, BlockPos.ZERO);
        buf.free();
        assertDoesNotThrow(buf::free);
    }

    // ─── dirty flag ───

    @Test
    @DisplayName("fresh buffer is dirty (needs initial upload)")
    void testInitiallyDirty() {
        FluidRegionBuffer buf = new FluidRegionBuffer(1);
        assertTrue(buf.isDirty(), "new buffer should be dirty by default");
    }

    @Test
    @DisplayName("markClean() clears dirty flag")
    void testMarkClean() {
        FluidRegionBuffer buf = new FluidRegionBuffer(1);
        buf.markClean();
        assertFalse(buf.isDirty());
    }

    @Test
    @DisplayName("markDirty() sets dirty flag")
    void testMarkDirty() {
        FluidRegionBuffer buf = new FluidRegionBuffer(1);
        buf.markClean();
        buf.markDirty();
        assertTrue(buf.isDirty());
    }

    // ─── multiple allocate calls ───

    @Test
    @DisplayName("allocate() on already-allocated buffer reallocates (free+allocate)")
    void testReallocate() {
        FluidRegionBuffer buf = new FluidRegionBuffer(1);
        buf.allocate(4, 4, 4, BlockPos.ZERO);
        // Reallocate with different size
        buf.allocate(8, 8, 8, BlockPos.ZERO);
        assertTrue(buf.isAllocated());
        assertEquals(8 * 8 * 8, buf.getN());
    }
}
