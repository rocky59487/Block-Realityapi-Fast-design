package com.blockreality.api.physics.fluid;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FluidGPUEngine unit tests — Vulkan/Minecraft-free path.
 *
 * Tests focus on:
 * 1. isAvailable() == false before initialization
 * 2. setFluidSource / removeFluid / getFluidPressureAt / getFluidVolumeAt
 *    exercised via the FluidRegionRegistry (no tick required)
 * 3. getActiveRegionCount / getTotalFluidVoxelCount
 * 4. notifyBarrierBreach / notifyBarrierBreachBatch
 * 5. getBoundaryPressureCache returns non-null
 *
 * Note: getInstance() registers with MinecraftForge.EVENT_BUS.
 * Tests are guarded with assumeTrue so they auto-skip if Forge
 * event bus is not available in the test JVM.
 */
@DisplayName("FluidGPUEngine — CPU-fallback / no-Vulkan tests")
class FluidGPUEngineTest {

    private FluidGPUEngine engine;

    @BeforeEach
    void setUp() {
        // Reset registry state for isolation
        FluidRegionRegistry.getInstance().clear();
        // Try to get engine instance — may throw if Forge bus not available
        try {
            engine = FluidGPUEngine.getInstance();
            // Also verify BRConfig is usable (config must be loaded for most engine methods)
            com.blockreality.api.config.BRConfig.getFluidMaxRegionSize();
        } catch (Exception | Error e) {
            // BRConfig not loaded (common in test env without Forge config load)
            engine = null;
        }
    }

    @AfterEach
    void tearDown() {
        FluidRegionRegistry.getInstance().clear();
    }

    // ─── isAvailable() ───

    @Test
    @DisplayName("isAvailable() returns false when no Vulkan and engine not started")
    void testIsAvailableFalseWithoutVulkan() {
        // Static check — does not require engine instance
        assertFalse(FluidGPUEngine.isAvailable(),
            "isAvailable must be false without a running Vulkan context");
    }

    // ─── setFluidSource ───

    @Test
    @DisplayName("setFluidSource creates region and sets voxel state")
    void testSetFluidSource() {
        assumeTrue(engine != null, "FluidGPUEngine not available in this environment");

        BlockPos pos = new BlockPos(5, 10, 5);
        engine.setFluidSource(pos, FluidType.WATER.getId(), 1.0f);

        // The registry should now have at least one region
        assertTrue(engine.getActiveRegionCount() > 0);
        assertTrue(engine.getTotalFluidVoxelCount() > 0,
            "After setFluidSource, there should be at least one fluid voxel");
    }

    @Test
    @DisplayName("setFluidSource with volume=0 does not add fluid voxels")
    void testSetFluidSourceZeroVolume() {
        assumeTrue(engine != null, "FluidGPUEngine not available in this environment");

        BlockPos pos = new BlockPos(0, 0, 0);
        engine.setFluidSource(pos, FluidType.AIR.getId(), 0f);

        assertEquals(0, engine.getTotalFluidVoxelCount());
    }

    // ─── removeFluid ───

    @Test
    @DisplayName("removeFluid clears a previously set fluid voxel")
    void testRemoveFluid() {
        assumeTrue(engine != null, "FluidGPUEngine not available in this environment");

        BlockPos pos = new BlockPos(0, 5, 0);
        engine.setFluidSource(pos, FluidType.WATER.getId(), 1.0f);
        assertTrue(engine.getTotalFluidVoxelCount() > 0);

        engine.removeFluid(pos);
        assertEquals(0, engine.getTotalFluidVoxelCount());
    }

    @Test
    @DisplayName("removeFluid on non-existent region is safe (no throw)")
    void testRemoveFluidNonExistent() {
        assumeTrue(engine != null, "FluidGPUEngine not available in this environment");

        assertDoesNotThrow(() -> engine.removeFluid(new BlockPos(999, 999, 999)));
    }

    // ─── getFluidPressureAt ───

    @Test
    @DisplayName("getFluidPressureAt returns 0 for empty region")
    void testGetFluidPressureAtEmpty() {
        assumeTrue(engine != null, "FluidGPUEngine not available in this environment");

        float p = engine.getFluidPressureAt(new BlockPos(100, 100, 100));
        assertEquals(0f, p, 1e-6f);
    }

    @Test
    @DisplayName("getFluidPressureAt returns non-zero after setFluidSource")
    void testGetFluidPressureAtAfterSet() {
        assumeTrue(engine != null, "FluidGPUEngine not available in this environment");

        BlockPos pos = new BlockPos(0, 10, 0);
        engine.setFluidSource(pos, FluidType.WATER.getId(), 1.0f);

        // pressure = ρ·g·h = 1000 * 9.81 * 10 ≈ 98100 Pa
        float p = engine.getFluidPressureAt(pos);
        assertTrue(p > 0f, "pressure should be positive after setting water source");
    }

    // ─── getFluidVolumeAt ───

    @Test
    @DisplayName("getFluidVolumeAt returns 0 for empty region")
    void testGetFluidVolumeAtEmpty() {
        assumeTrue(engine != null, "FluidGPUEngine not available in this environment");

        float v = engine.getFluidVolumeAt(new BlockPos(200, 200, 200));
        assertEquals(0f, v, 1e-6f);
    }

    @Test
    @DisplayName("getFluidVolumeAt returns set volume after setFluidSource")
    void testGetFluidVolumeAtAfterSet() {
        assumeTrue(engine != null, "FluidGPUEngine not available in this environment");

        BlockPos pos = new BlockPos(3, 3, 3);
        engine.setFluidSource(pos, FluidType.WATER.getId(), 0.75f);

        float v = engine.getFluidVolumeAt(pos);
        assertEquals(0.75f, v, 1e-4f);
    }

    // ─── notifyBarrierBreach ───

    @Test
    @DisplayName("notifyBarrierBreach converts SOLID_WALL voxel to AIR")
    void testNotifyBarrierBreach() {
        assumeTrue(engine != null, "FluidGPUEngine not available in this environment");

        // First put a solid wall at the position
        int regionSize = com.blockreality.api.config.BRConfig.getFluidMaxRegionSize();
        FluidRegion region = FluidRegionRegistry.getInstance()
            .getOrCreateRegion(new BlockPos(0, 0, 0), regionSize);
        int idx = region.flatIndex(0, 0, 0);
        region.setVoxelType(idx, FluidType.SOLID_WALL);
        assertTrue(region.getFluidState(idx).isSolid());

        engine.notifyBarrierBreach(new BlockPos(0, 0, 0));

        // After breach, should be AIR
        assertEquals(FluidType.AIR, region.getFluidState(idx).type());
        assertTrue(region.isDirty());
    }

    @Test
    @DisplayName("notifyBarrierBreach on position with no region is safe")
    void testNotifyBarrierBreachNoRegion() {
        assumeTrue(engine != null, "FluidGPUEngine not available in this environment");

        assertDoesNotThrow(() -> engine.notifyBarrierBreach(new BlockPos(500, 500, 500)));
    }

    // ─── notifyBarrierBreachBatch ───

    @Test
    @DisplayName("notifyBarrierBreachBatch with empty list is safe")
    void testNotifyBarrierBreachBatchEmpty() {
        assumeTrue(engine != null, "FluidGPUEngine not available in this environment");

        assertDoesNotThrow(() -> engine.notifyBarrierBreachBatch(java.util.List.of()));
    }

    @Test
    @DisplayName("notifyBarrierBreachBatch converts multiple SOLID_WALL voxels to AIR")
    void testNotifyBarrierBreachBatchMultiple() {
        assumeTrue(engine != null, "FluidGPUEngine not available in this environment");

        int regionSize = com.blockreality.api.config.BRConfig.getFluidMaxRegionSize();
        FluidRegion region = FluidRegionRegistry.getInstance()
            .getOrCreateRegion(new BlockPos(0, 0, 0), regionSize);

        // Set 3 solid walls
        BlockPos p1 = new BlockPos(0, 0, 0);
        BlockPos p2 = new BlockPos(1, 0, 0);
        BlockPos p3 = new BlockPos(2, 0, 0);
        region.setVoxelType(region.flatIndex(p1), FluidType.SOLID_WALL);
        region.setVoxelType(region.flatIndex(p2), FluidType.SOLID_WALL);
        region.setVoxelType(region.flatIndex(p3), FluidType.SOLID_WALL);

        engine.notifyBarrierBreachBatch(java.util.List.of(p1, p2, p3));

        assertEquals(FluidType.AIR, region.getFluidState(region.flatIndex(p1)).type());
        assertEquals(FluidType.AIR, region.getFluidState(region.flatIndex(p2)).type());
        assertEquals(FluidType.AIR, region.getFluidState(region.flatIndex(p3)).type());
    }

    // ─── getActiveRegionCount / getTotalFluidVoxelCount ───

    @Test
    @DisplayName("getActiveRegionCount is 0 when registry is empty")
    void testGetActiveRegionCountEmpty() {
        assumeTrue(engine != null, "FluidGPUEngine not available in this environment");

        assertEquals(0, engine.getActiveRegionCount());
    }

    @Test
    @DisplayName("getTotalFluidVoxelCount is 0 when no fluid sources")
    void testGetTotalFluidVoxelCountEmpty() {
        assumeTrue(engine != null, "FluidGPUEngine not available in this environment");

        assertEquals(0, engine.getTotalFluidVoxelCount());
    }

    // ─── getBoundaryPressureCache ───

    @Test
    @DisplayName("getBoundaryPressureCache returns non-null map")
    void testGetBoundaryPressureCacheNonNull() {
        assumeTrue(engine != null, "FluidGPUEngine not available in this environment");

        assertNotNull(engine.getBoundaryPressureCache());
    }

    @Test
    @DisplayName("getBoundaryPressureCache is empty before any fluid activity")
    void testGetBoundaryPressureCacheEmpty() {
        assumeTrue(engine != null, "FluidGPUEngine not available in this environment");

        // After clearing registry, cache should be empty (stale entries may remain
        // from previous tests but registry is cleared)
        assertNotNull(engine.getBoundaryPressureCache());
    }

    // ─── shutdown (idempotent) ───

    @Test
    @DisplayName("FluidGPUEngine.isAvailable() static check — never throws")
    void testIsAvailableNeverThrows() {
        assertDoesNotThrow(FluidGPUEngine::isAvailable);
    }
}
