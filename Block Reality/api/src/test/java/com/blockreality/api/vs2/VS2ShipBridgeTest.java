package com.blockreality.api.vs2;

import com.blockreality.api.fragment.StructureFragment;
import com.blockreality.api.material.DefaultMaterial;
import com.blockreality.api.material.RMaterial;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link VS2ShipBridge}.
 *
 * <p>These tests verify the non-VS2-dependent logic (anchor selection, findMethod,
 * circuit breaker). VS2-specific reflection calls are not tested here because
 * VS2 is not available in the test environment.
 */
class VS2ShipBridgeTest {

    // ═══════════════════════════════════════════════════════════════
    //  nearestBlockToCoM tests
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("nearestBlockToCoM")
    class NearestBlockToCoMTests {

        @Test
        @DisplayName("selects block closest to CoM from multiple candidates")
        void selectsClosestBlock() {
            Map<BlockPos, BlockState> blocks = new HashMap<>();
            blocks.put(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState());
            blocks.put(new BlockPos(5, 0, 0), Blocks.STONE.defaultBlockState());
            blocks.put(new BlockPos(10, 0, 0), Blocks.STONE.defaultBlockState());

            // CoM at (5.5, 0.5, 0.5) — block (5,0,0) centre is at (5.5, 0.5, 0.5)
            StructureFragment frag = makeFragment(blocks, 5.5, 0.5, 0.5);

            BlockPos result = VS2ShipBridge.nearestBlockToCoM(blocks, frag);
            assertEquals(new BlockPos(5, 0, 0), result,
                "Should select block whose centre (5.5, 0.5, 0.5) matches CoM exactly");
        }

        @Test
        @DisplayName("selects block closest to CoM when CoM is between blocks")
        void selectsClosestWhenBetween() {
            Map<BlockPos, BlockState> blocks = new HashMap<>();
            blocks.put(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState());
            blocks.put(new BlockPos(3, 0, 0), Blocks.STONE.defaultBlockState());

            // CoM at (2.0, 0.5, 0.5) — block (3,0,0) centre at (3.5, 0.5, 0.5) is 1.5 away,
            // block (0,0,0) centre at (0.5, 0.5, 0.5) is 1.5 away — tie goes to iteration order
            StructureFragment frag = makeFragment(blocks, 2.5, 0.5, 0.5);
            BlockPos result = VS2ShipBridge.nearestBlockToCoM(blocks, frag);
            // Block (3,0,0) centre = (3.5, 0.5, 0.5), dist = 1.0
            // Block (0,0,0) centre = (0.5, 0.5, 0.5), dist = 2.0
            assertEquals(new BlockPos(3, 0, 0), result);
        }

        @Test
        @DisplayName("returns sole block when only one block exists")
        void singleBlock() {
            Map<BlockPos, BlockState> blocks = new HashMap<>();
            blocks.put(new BlockPos(7, 64, -3), Blocks.STONE.defaultBlockState());

            StructureFragment frag = makeFragment(blocks, 7.5, 64.5, -2.5);
            BlockPos result = VS2ShipBridge.nearestBlockToCoM(blocks, frag);
            assertEquals(new BlockPos(7, 64, -3), result);
        }

        @Test
        @DisplayName("handles 3D distance correctly (Y axis matters)")
        void handlesYAxis() {
            Map<BlockPos, BlockState> blocks = new HashMap<>();
            blocks.put(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState());
            blocks.put(new BlockPos(0, 10, 0), Blocks.STONE.defaultBlockState());

            // CoM near (0.5, 9.5, 0.5) — closer to block (0,10,0) which has centre (0.5, 10.5, 0.5)
            StructureFragment frag = makeFragment(blocks, 0.5, 9.5, 0.5);
            BlockPos result = VS2ShipBridge.nearestBlockToCoM(blocks, frag);
            assertEquals(new BlockPos(0, 10, 0), result,
                "Should select block closest in 3D, including Y axis");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  findMethod tests
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("findMethod")
    class FindMethodTests {

        @Test
        @DisplayName("finds public method on target class")
        void findsPublicMethod() {
            Method m = VS2ShipBridge.findMethod(String.class, "length");
            assertNotNull(m, "Should find String.length()");
            assertEquals("length", m.getName());
        }

        @Test
        @DisplayName("finds inherited public method")
        void findsInheritedMethod() {
            Method m = VS2ShipBridge.findMethod(HashMap.class, "size");
            assertNotNull(m, "Should find Map.size() via HashMap");
            assertEquals("size", m.getName());
        }

        @Test
        @DisplayName("returns null for non-existent method")
        void returnsNullForMissing() {
            Method m = VS2ShipBridge.findMethod(String.class, "nonExistentMethod12345");
            assertNull(m, "Should return null for non-existent method");
        }

        @Test
        @DisplayName("finds method with specific parameter types")
        void findsMethodWithParams() {
            Method m = VS2ShipBridge.findMethod(String.class, "substring", int.class);
            assertNotNull(m, "Should find String.substring(int)");
            assertEquals("substring", m.getName());
            assertEquals(1, m.getParameterCount());
        }

        @Test
        @DisplayName("returns null when parameter types don't match")
        void returnsNullWhenParamsMismatch() {
            Method m = VS2ShipBridge.findMethod(String.class, "substring", double.class);
            assertNull(m, "Should return null when param types don't match");
        }

        @Test
        @DisplayName("finds interface default method")
        void findsInterfaceMethod() {
            Method m = VS2ShipBridge.findMethod(Map.class, "size");
            assertNotNull(m, "Should find size() on Map interface");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Helper
    // ═══════════════════════════════════════════════════════════════

    private static StructureFragment makeFragment(Map<BlockPos, BlockState> blocks,
            double comX, double comY, double comZ) {
        Map<BlockPos, RMaterial> mats = new HashMap<>();
        for (BlockPos p : blocks.keySet()) {
            mats.put(p, DefaultMaterial.CONCRETE);
        }
        return new StructureFragment(
            UUID.randomUUID(),
            blocks,
            mats,
            comX, comY, comZ,
            1000.0, // totalMass
            0f, -1.5f, 0f, // vel
            0f, 0f, 0f,     // angVel
            0L               // creationTick
        );
    }
}
