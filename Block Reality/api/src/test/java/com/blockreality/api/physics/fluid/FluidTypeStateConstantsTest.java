package com.blockreality.api.physics.fluid;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for FluidType, FluidState, and FluidConstants.
 *
 * All are pure-Java (no Minecraft server required).
 */
@DisplayName("FluidType + FluidState + FluidConstants tests")
class FluidTypeStateConstantsTest {

    // ═══ FluidType ═══

    @Nested
    @DisplayName("FluidType enum")
    class FluidTypeTests {

        @Test
        @DisplayName("AIR has id=0, density=0, viscosity=0")
        void testAir() {
            assertEquals(0, FluidType.AIR.getId());
            assertEquals(0.0, FluidType.AIR.getDensity(), 1e-9);
            assertEquals(0.0, FluidType.AIR.getViscosity(), 1e-9);
        }

        @Test
        @DisplayName("WATER has id=1, density=1000, viscosity=1e-3")
        void testWater() {
            assertEquals(1, FluidType.WATER.getId());
            assertEquals(1000.0, FluidType.WATER.getDensity(), 1e-9);
            assertEquals(1e-3, FluidType.WATER.getViscosity(), 1e-9);
        }

        @Test
        @DisplayName("OIL has id=2, density=800")
        void testOil() {
            assertEquals(2, FluidType.OIL.getId());
            assertEquals(800.0, FluidType.OIL.getDensity(), 1e-9);
        }

        @Test
        @DisplayName("LAVA has id=3, density=2500")
        void testLava() {
            assertEquals(3, FluidType.LAVA.getId());
            assertEquals(2500.0, FluidType.LAVA.getDensity(), 1e-9);
        }

        @Test
        @DisplayName("SOLID_WALL has id=4, density=0")
        void testSolidWall() {
            assertEquals(4, FluidType.SOLID_WALL.getId());
            assertEquals(0.0, FluidType.SOLID_WALL.getDensity(), 1e-9);
        }

        @Test
        @DisplayName("isFlowable() is true only for WATER, OIL, LAVA")
        void testIsFlowable() {
            assertFalse(FluidType.AIR.isFlowable());
            assertTrue(FluidType.WATER.isFlowable());
            assertTrue(FluidType.OIL.isFlowable());
            assertTrue(FluidType.LAVA.isFlowable());
            assertFalse(FluidType.SOLID_WALL.isFlowable());
        }

        @Test
        @DisplayName("fromId() returns correct type for valid ids")
        void testFromIdValid() {
            assertEquals(FluidType.AIR,        FluidType.fromId(0));
            assertEquals(FluidType.WATER,      FluidType.fromId(1));
            assertEquals(FluidType.OIL,        FluidType.fromId(2));
            assertEquals(FluidType.LAVA,       FluidType.fromId(3));
            assertEquals(FluidType.SOLID_WALL, FluidType.fromId(4));
        }

        @Test
        @DisplayName("fromId() returns AIR for unknown id")
        void testFromIdUnknown() {
            assertEquals(FluidType.AIR, FluidType.fromId(99));
            assertEquals(FluidType.AIR, FluidType.fromId(-1));
            assertEquals(FluidType.AIR, FluidType.fromId(255));
        }

        @Test
        @DisplayName("fromId(type.getId()) is identity for all types")
        void testFromIdIdentity() {
            for (FluidType t : FluidType.values()) {
                assertEquals(t, FluidType.fromId(t.getId()));
            }
        }

        @Test
        @DisplayName("all IDs are unique")
        void testAllIdsUnique() {
            java.util.Set<Integer> ids = new java.util.HashSet<>();
            for (FluidType t : FluidType.values()) {
                assertTrue(ids.add(t.getId()), "Duplicate id: " + t.getId());
            }
        }
    }

    // ═══ FluidState ═══

    @Nested
    @DisplayName("FluidState record")
    class FluidStateTests {

        @Test
        @DisplayName("EMPTY constant: AIR, volume=0, all zeros")
        void testEmpty() {
            assertEquals(FluidType.AIR, FluidState.EMPTY.type());
            assertEquals(0f, FluidState.EMPTY.volume(), 1e-7f);
            assertEquals(0f, FluidState.EMPTY.pressure(), 1e-7f);
            assertEquals(0f, FluidState.EMPTY.potential(), 1e-7f);
            assertEquals(0f, FluidState.EMPTY.vx(), 1e-7f);
            assertEquals(0f, FluidState.EMPTY.vy(), 1e-7f);
            assertEquals(0f, FluidState.EMPTY.vz(), 1e-7f);
        }

        @Test
        @DisplayName("SOLID constant: SOLID_WALL type")
        void testSolid() {
            assertEquals(FluidType.SOLID_WALL, FluidState.SOLID.type());
            assertTrue(FluidState.SOLID.isSolid());
        }

        @Test
        @DisplayName("fullWater(h) computes P = ρ·g·h")
        void testFullWater() {
            float h = 10.0f;
            FluidState s = FluidState.fullWater(h);
            assertEquals(FluidType.WATER, s.type());
            assertEquals(1.0f, s.volume(), 1e-7f);

            float expected = (float)(FluidType.WATER.getDensity()
                * FluidConstants.GRAVITY * h);
            assertEquals(expected, s.pressure(), expected * 0.001f);
        }

        @Test
        @DisplayName("fullWater(0) gives pressure=0")
        void testFullWaterZeroHeight() {
            FluidState s = FluidState.fullWater(0f);
            assertEquals(0f, s.pressure(), 1e-7f);
        }

        @Test
        @DisplayName("hasFluid() false for EMPTY")
        void testHasFluidEmpty() {
            assertFalse(FluidState.EMPTY.hasFluid());
        }

        @Test
        @DisplayName("hasFluid() false for SOLID")
        void testHasFluidSolid() {
            assertFalse(FluidState.SOLID.hasFluid());
        }

        @Test
        @DisplayName("hasFluid() true for fullWater")
        void testHasFluidWater() {
            assertTrue(FluidState.fullWater(5f).hasFluid());
        }

        @Test
        @DisplayName("hasFluid() false for AIR with volume > 0 (volume > MIN not flowable)")
        void testHasFluidAirNotFlowable() {
            FluidState s = new FluidState(FluidType.AIR, 1.0f, 0f, 0f, 0f, 0f, 0f);
            assertFalse(s.hasFluid(), "AIR is not flowable, so hasFluid should be false");
        }

        @Test
        @DisplayName("isSolid() false for non-SOLID_WALL types")
        void testIsSolidFalse() {
            assertFalse(FluidState.EMPTY.isSolid());
            assertFalse(FluidState.fullWater(1f).isSolid());
        }

        @Test
        @DisplayName("FluidState is a value record — equal instances are equal")
        void testRecordEquality() {
            FluidState s1 = new FluidState(FluidType.WATER, 1.0f, 9810f, 9810f, 0f, 0f, 0f);
            FluidState s2 = new FluidState(FluidType.WATER, 1.0f, 9810f, 9810f, 0f, 0f, 0f);
            assertEquals(s1, s2);
        }
    }

    // ═══ FluidConstants ═══

    @Nested
    @DisplayName("FluidConstants values")
    class FluidConstantsTests {

        @Test
        @DisplayName("GRAVITY ≈ 9.81 m/s²")
        void testGravity() {
            assertEquals(9.81, FluidConstants.GRAVITY, 0.01);
        }

        @Test
        @DisplayName("BLOCK_VOLUME = 1.0 m³")
        void testBlockVolume() {
            assertEquals(1.0, FluidConstants.BLOCK_VOLUME, 1e-9);
        }

        @Test
        @DisplayName("DEFAULT_DIFFUSION_RATE is within [0, MAX_DIFFUSION_RATE]")
        void testDiffusionRateRange() {
            assertTrue(FluidConstants.DEFAULT_DIFFUSION_RATE > 0f);
            assertTrue(FluidConstants.DEFAULT_DIFFUSION_RATE <= FluidConstants.MAX_DIFFUSION_RATE);
        }

        @Test
        @DisplayName("DEFAULT_ITERATIONS_PER_TICK is within [MIN, MAX]")
        void testIterationsRange() {
            assertTrue(FluidConstants.DEFAULT_ITERATIONS_PER_TICK
                >= FluidConstants.MIN_ITERATIONS_PER_TICK);
            assertTrue(FluidConstants.DEFAULT_ITERATIONS_PER_TICK
                <= FluidConstants.MAX_ITERATIONS_PER_TICK);
        }

        @Test
        @DisplayName("DAMPING_FACTOR is < 1.0 (provides stability)")
        void testDampingFactor() {
            assertTrue(FluidConstants.DAMPING_FACTOR < 1.0f);
            assertTrue(FluidConstants.DAMPING_FACTOR > 0.9f);
        }

        @Test
        @DisplayName("MIN_VOLUME_FRACTION > 0 (threshold above zero)")
        void testMinVolumeFraction() {
            assertTrue(FluidConstants.MIN_VOLUME_FRACTION > 0f);
        }

        @Test
        @DisplayName("CONVERGENCE_THRESHOLD > 0")
        void testConvergenceThreshold() {
            assertTrue(FluidConstants.CONVERGENCE_THRESHOLD > 0f);
        }

        @Test
        @DisplayName("REGION_DORMANT_DISTANCE > REGION_ACTIVATION_DISTANCE")
        void testRegionDistances() {
            assertTrue(FluidConstants.REGION_DORMANT_DISTANCE
                > FluidConstants.REGION_ACTIVATION_DISTANCE);
        }

        @Test
        @DisplayName("WORKGROUP_SIZE = 8 (GPU standard)")
        void testWorkgroupSize() {
            assertEquals(8, FluidConstants.WORKGROUP_SIZE);
        }

        @Test
        @DisplayName("SHARED_TILE_SIZE = WORKGROUP_SIZE + 2 (halo)")
        void testSharedTileSize() {
            assertEquals(FluidConstants.WORKGROUP_SIZE + 2, FluidConstants.SHARED_TILE_SIZE);
        }

        @Test
        @DisplayName("MAX_VOXELS_PER_REGION == MAX_REGION_SIZE³")
        void testMaxVoxelsPerRegion() {
            int expected = FluidConstants.MAX_REGION_SIZE
                * FluidConstants.MAX_REGION_SIZE
                * FluidConstants.MAX_REGION_SIZE;
            assertEquals(expected, FluidConstants.MAX_VOXELS_PER_REGION);
        }
    }
}
