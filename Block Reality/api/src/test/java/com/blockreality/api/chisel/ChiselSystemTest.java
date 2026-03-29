package com.blockreality.api.chisel;

import com.blockreality.api.physics.PhysicsConstants;
import com.blockreality.api.physics.RBlockState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 雕刻刀子方塊系統測試。
 */
class ChiselSystemTest {

    // ═══════════════════════════════════════════════════
    //  VoxelGrid 測試
    // ═══════════════════════════════════════════════════

    @Nested
    class VoxelGridTests {

        @Test
        void fullGrid_allSet() {
            VoxelGrid grid = VoxelGrid.full();
            assertTrue(grid.isFull());
            assertFalse(grid.isEmpty());
            assertEquals(1000, grid.filledCount());
            assertEquals(1.0, grid.fillRatio(), 1e-9);
            // 驗證每個體素都被填充
            for (int z = 0; z < 10; z++)
                for (int y = 0; y < 10; y++)
                    for (int x = 0; x < 10; x++)
                        assertTrue(grid.get(x, y, z), "Voxel at (" + x + "," + y + "," + z + ") should be set");
        }

        @Test
        void emptyGrid_noneSet() {
            VoxelGrid grid = VoxelGrid.empty();
            assertTrue(grid.isEmpty());
            assertFalse(grid.isFull());
            assertEquals(0, grid.filledCount());
            assertEquals(0.0, grid.fillRatio(), 1e-9);
        }

        @Test
        void builder_setSingle() {
            VoxelGrid grid = new VoxelGrid.Builder()
                .set(3, 5, 7, true)
                .build();
            assertTrue(grid.get(3, 5, 7));
            assertFalse(grid.get(0, 0, 0));
            assertEquals(1, grid.filledCount());
        }

        @Test
        void builder_fillLayer() {
            VoxelGrid grid = new VoxelGrid.Builder()
                .fillLayer(0, true)
                .build();
            assertEquals(100, grid.filledCount()); // 10×10 = 100
            assertTrue(grid.get(0, 0, 0));
            assertTrue(grid.get(9, 0, 9));
            assertFalse(grid.get(0, 1, 0));
        }

        @Test
        void builder_editExisting() {
            VoxelGrid full = VoxelGrid.full();
            VoxelGrid edited = new VoxelGrid.Builder(full)
                .set(5, 5, 5, false) // 移除一個
                .build();
            assertFalse(edited.get(5, 5, 5));
            assertEquals(999, edited.filledCount());
        }

        @Test
        void longArrayRoundtrip() {
            VoxelGrid original = new VoxelGrid.Builder()
                .fillLayer(0, true)
                .fillLayer(5, true)
                .set(9, 9, 9, true)
                .build();

            long[] data = original.toLongArray();
            assertEquals(16, data.length);

            VoxelGrid restored = VoxelGrid.fromLongArray(data);
            assertEquals(original.filledCount(), restored.filledCount());
            assertTrue(restored.get(5, 0, 3));
            assertTrue(restored.get(0, 5, 0));
            assertTrue(restored.get(9, 9, 9));
            assertFalse(restored.get(0, 3, 0));
        }

        @Test
        void boundsCheck() {
            VoxelGrid grid = VoxelGrid.full();
            assertThrows(IndexOutOfBoundsException.class, () -> grid.get(-1, 0, 0));
            assertThrows(IndexOutOfBoundsException.class, () -> grid.get(10, 0, 0));
            assertThrows(IndexOutOfBoundsException.class, () -> grid.get(0, -1, 0));
            assertThrows(IndexOutOfBoundsException.class, () -> grid.get(0, 10, 0));
        }

        @Test
        void equality() {
            VoxelGrid a = VoxelGrid.full();
            VoxelGrid b = VoxelGrid.full();
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());

            VoxelGrid c = VoxelGrid.empty();
            assertNotEquals(a, c);
        }
    }

    // ═══════════════════════════════════════════════════
    //  SubBlockShape 測試
    // ═══════════════════════════════════════════════════

    @Nested
    class SubBlockShapeTests {

        @Test
        void fullShape_defaultProperties() {
            SubBlockShape full = SubBlockShape.FULL;
            assertEquals(1.0, full.getFillRatio(), 1e-9);
            assertEquals(1.0, full.getCrossSectionArea(), 1e-9);
            assertEquals(1.0 / 12.0, full.getMomentOfInertiaX(), 1e-6);
            assertEquals(1.0 / 6.0, full.getSectionModulusX(), 1e-6);
        }

        @Test
        void slabBottom_halfProperties() {
            SubBlockShape slab = SubBlockShape.SLAB_BOTTOM;
            assertEquals(0.5, slab.getFillRatio(), 1e-9);
            assertEquals(0.5, slab.getCrossSectionArea(), 1e-9);
            // Ix = 1.0 × 0.5³/12 = 0.01042
            assertEquals(0.01042, slab.getMomentOfInertiaX(), 1e-4);
        }

        @Test
        void pillar_circularProperties() {
            SubBlockShape pillar = SubBlockShape.PILLAR;
            // A = πr² ≈ 0.503
            assertTrue(pillar.getCrossSectionArea() < 1.0);
            assertTrue(pillar.getCrossSectionArea() > 0.1);
            // I 遠小於全塊
            assertTrue(pillar.getMomentOfInertiaX() < SubBlockShape.FULL.getMomentOfInertiaX());
        }

        @Test
        void fromString_knownShapes() {
            assertEquals(SubBlockShape.FULL, SubBlockShape.fromString("full"));
            assertEquals(SubBlockShape.SLAB_BOTTOM, SubBlockShape.fromString("slab_bottom"));
            assertEquals(SubBlockShape.PILLAR, SubBlockShape.fromString("pillar"));
            assertEquals(SubBlockShape.CUSTOM, SubBlockShape.fromString("custom"));
        }

        @Test
        void fromString_unknownReturnsFulll() {
            assertEquals(SubBlockShape.FULL, SubBlockShape.fromString("nonexistent"));
            assertEquals(SubBlockShape.FULL, SubBlockShape.fromString(""));
        }

        @Test
        void voxelGrid_slabBottomFillsHalf() {
            VoxelGrid grid = VoxelGrid.fromShape(SubBlockShape.SLAB_BOTTOM);
            // 下半部 y=0~4，每層 100 體素 → 500
            assertEquals(500, grid.filledCount());
            assertTrue(grid.get(5, 0, 5));  // 下半部有
            assertTrue(grid.get(5, 4, 5));  // y=4 有
            assertFalse(grid.get(5, 5, 5)); // y=5 無
        }

        @Test
        void voxelGrid_fullFillsAll() {
            VoxelGrid grid = VoxelGrid.fromShape(SubBlockShape.FULL);
            assertEquals(1000, grid.filledCount());
        }

        @Test
        void voxelGrid_quarterFillsQuarter() {
            VoxelGrid grid = VoxelGrid.fromShape(SubBlockShape.QUARTER_NE);
            assertEquals(250, grid.filledCount()); // 5×10×5 = 250
        }

        @Test
        void allShapes_haveValidProperties() {
            for (SubBlockShape shape : SubBlockShape.values()) {
                assertTrue(shape.getFillRatio() > 0 && shape.getFillRatio() <= 1.0,
                    shape.name() + " fillRatio out of range: " + shape.getFillRatio());
                assertTrue(shape.getCrossSectionArea() > 0,
                    shape.name() + " crossSectionArea must be positive");
                assertTrue(shape.getSectionModulusX() > 0,
                    shape.name() + " sectionModulusX must be positive");
            }
        }

        @Test
        void stairShapes_fillRatio75() {
            assertEquals(0.75, SubBlockShape.STAIR_NORTH.getFillRatio(), 1e-9);
            assertEquals(0.75, SubBlockShape.STAIR_SOUTH.getFillRatio(), 1e-9);
            assertEquals(0.75, SubBlockShape.STAIR_EAST.getFillRatio(), 1e-9);
            assertEquals(0.75, SubBlockShape.STAIR_WEST.getFillRatio(), 1e-9);
        }

        @Test
        void stairGrid_fills750Voxels() {
            VoxelGrid grid = VoxelGrid.fromShape(SubBlockShape.STAIR_NORTH);
            assertEquals(750, grid.filledCount()); // 500 (bottom) + 250 (top half)
        }
    }

    // ═══════════════════════════════════════════════════
    //  ChiselState 測試
    // ═══════════════════════════════════════════════════

    @Nested
    class ChiselStateTests {

        @Test
        void fullState_matchesCurrentDefaults() {
            ChiselState full = ChiselState.FULL;
            assertTrue(full.isFull());
            assertTrue(full.isTemplate());
            assertFalse(full.isCustom());
            assertEquals(1.0, full.fillRatio(), 1e-9);
            assertEquals(PhysicsConstants.BLOCK_AREA, full.crossSectionArea(), 1e-9);
            assertEquals(PhysicsConstants.FULL_SECTION_MODULUS, full.sectionModulusX(), 1e-9);
            assertEquals(PhysicsConstants.FULL_MOMENT_OF_INERTIA, full.momentOfInertiaX(), 1e-9);
        }

        @Test
        void templateState_usesShapeProperties() {
            ChiselState slab = ChiselState.ofShape(SubBlockShape.SLAB_BOTTOM);
            assertTrue(slab.isTemplate());
            assertFalse(slab.isCustom());
            assertEquals(0.5, slab.fillRatio(), 1e-9);
            assertEquals(0.5, slab.crossSectionArea(), 1e-9);
        }

        @Test
        void customState_usesFullBlockPhysics() {
            VoxelGrid halfGrid = new VoxelGrid.Builder()
                .fillLayer(0, true).fillLayer(1, true)
                .fillLayer(2, true).fillLayer(3, true)
                .fillLayer(4, true)
                .build();
            ChiselState custom = ChiselState.ofCustom(halfGrid);
            assertTrue(custom.isCustom());
            assertFalse(custom.isTemplate());
            // fillRatio 反映實際填充
            assertEquals(0.5, custom.fillRatio(), 1e-9);
            // 力學屬性回傳全塊預設值
            assertEquals(PhysicsConstants.BLOCK_AREA, custom.crossSectionArea(), 1e-9);
            assertEquals(PhysicsConstants.FULL_SECTION_MODULUS, custom.sectionModulusX(), 1e-9);
        }

        @Test
        void slabHalfCapacity() {
            // 半磚的壓碎容量 = 全塊的 50%
            ChiselState slab = ChiselState.ofShape(SubBlockShape.SLAB_BOTTOM);
            ChiselState full = ChiselState.FULL;
            assertEquals(full.crossSectionArea() * 0.5, slab.crossSectionArea(), 1e-9);
        }

        @Test
        void pillarLowerMoment() {
            // 柱子的懸臂力矩容量 < 全塊
            ChiselState pillar = ChiselState.ofShape(SubBlockShape.PILLAR);
            ChiselState full = ChiselState.FULL;
            assertTrue(pillar.sectionModulusX() < full.sectionModulusX());
            assertTrue(pillar.momentOfInertiaX() < full.momentOfInertiaX());
        }
    }

    // ═══════════════════════════════════════════════════
    //  RBlockState 向後相容測試
    // ═══════════════════════════════════════════════════

    @Nested
    class RBlockStateTests {

        @Test
        void fiveArgConstructor_defaultsToFullBlock() {
            RBlockState state = new RBlockState("test:block", 2400f, 30f, 3f, false);
            assertEquals(1.0f, state.crossSectionArea(), 1e-6);
            assertEquals(1.0f / 12.0f, state.momentOfInertia(), 1e-6);
            assertEquals(1.0f / 6.0f, state.sectionModulus(), 1e-6);
        }

        @Test
        void eightArgConstructor_customValues() {
            RBlockState state = new RBlockState("test:slab", 1200f, 30f, 3f, false,
                0.5f, 0.01042f, 0.04167f);
            assertEquals(0.5f, state.crossSectionArea(), 1e-6);
            assertEquals(0.01042f, state.momentOfInertia(), 1e-4);
            assertEquals(0.04167f, state.sectionModulus(), 1e-4);
        }

        @Test
        void airState_zeroProperties() {
            assertEquals(0f, RBlockState.AIR.mass());
            assertEquals(0f, RBlockState.AIR.crossSectionArea());
        }

        @Test
        void slabMassIsHalf() {
            // 模擬 SnapshotBuilder 的行為：mass = density × fillRatio
            float density = 2400f;
            float slabFill = 0.5f;
            RBlockState fullBlock = new RBlockState("test", density, 30f, 3f, false);
            RBlockState slabBlock = new RBlockState("test", density * slabFill, 30f, 3f, false,
                0.5f, 0.01042f, 0.04167f);
            assertEquals(fullBlock.mass() * 0.5f, slabBlock.mass(), 1e-2);
        }
    }

    // ═══════════════════════════════════════════════════
    //  物理整合測試
    // ═══════════════════════════════════════════════════

    @Nested
    class PhysicsIntegrationTests {

        @Test
        void slabCrushingCapacity_isHalfOfFull() {
            // 完整方塊壓碎容量 = Rcomp × 1e6 × 1.0 m²
            // 半磚壓碎容量 = Rcomp × 1e6 × 0.5 m²
            double rcomp = 30.0; // MPa (concrete)
            double fullCapacity = rcomp * 1e6 * ChiselState.FULL.crossSectionArea();
            double slabCapacity = rcomp * 1e6 * ChiselState.ofShape(SubBlockShape.SLAB_BOTTOM).crossSectionArea();
            assertEquals(fullCapacity * 0.5, slabCapacity, 1e-3);
        }

        @Test
        void pillarMomentCapacity_lessThanFull() {
            double rtens = 3.0; // MPa (concrete)
            double fullMomentCap = rtens * 1e6 * ChiselState.FULL.sectionModulusX();
            double pillarMomentCap = rtens * 1e6 * ChiselState.ofShape(SubBlockShape.PILLAR).sectionModulusX();
            assertTrue(pillarMomentCap < fullMomentCap,
                "Pillar moment capacity should be less than full block");
        }

        @Test
        void customShape_usesFullBlockPhysics() {
            // 即使自訂只填充 30%，力學屬性仍為全塊
            VoxelGrid sparse = new VoxelGrid.Builder()
                .fillLayer(0, true)
                .fillLayer(1, true)
                .fillLayer(2, true)
                .build();
            ChiselState custom = ChiselState.ofCustom(sparse);
            assertEquals(0.3, custom.fillRatio(), 1e-9);
            // 但力學以 1×1 計算
            assertEquals(PhysicsConstants.BLOCK_AREA, custom.crossSectionArea(), 1e-9);
        }

        @Test
        void beamIShape_higherEfficiency() {
            // I 型梁的 Ix 相對於截面積比實心矩形更高效
            SubBlockShape beam = SubBlockShape.BEAM_NS;
            SubBlockShape slab = SubBlockShape.SLAB_BOTTOM;
            // beam A ≈ 0.44, slab A = 0.5
            // beam Ix ≈ 0.042, slab Ix ≈ 0.010
            // beam 的 I/A 比 slab 好得多（工字梁的優勢）
            double beamEfficiency = beam.getMomentOfInertiaX() / beam.getCrossSectionArea();
            double slabEfficiency = slab.getMomentOfInertiaX() / slab.getCrossSectionArea();
            assertTrue(beamEfficiency > slabEfficiency,
                "I-beam should have higher I/A ratio than slab");
        }
    }
}
