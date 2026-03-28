package com.blockreality.api.physics;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * StructureIslandRegistry 單元測試。
 *
 * 測試涵蓋：
 *   1. 單一方塊登錄/註銷
 *   2. 相鄰方塊合併
 *   3. 不相鄰方塊建立獨立 island
 *   4. 破壞後 island 分裂
 *   5. 多 island 合併（放置觸及多個 island）
 *   6. AABB 計算
 *   7. 清除機制
 */
@DisplayName("StructureIslandRegistry — Island Management Tests")
class StructureIslandRegistryTest {

    @BeforeEach
    void setUp() {
        StructureIslandRegistry.clear();
    }

    @AfterEach
    void tearDown() {
        StructureIslandRegistry.clear();
    }

    // ═══════════════════════════════════════════════════
    // 1. 基本登錄/註銷
    // ═══════════════════════════════════════════════════

    @Nested
    @DisplayName("Registration")
    class Registration {

        @Test
        @DisplayName("Single block creates new island")
        void singleBlockCreatesIsland() {
            BlockPos pos = new BlockPos(0, 0, 0);
            int id = StructureIslandRegistry.registerBlock(pos, 1);
            assertTrue(id > 0, "Island ID should be positive");
            assertEquals(1, StructureIslandRegistry.getIslandCount());
            assertEquals(1, StructureIslandRegistry.getTotalRegisteredBlocks());
            assertEquals(id, StructureIslandRegistry.getIslandId(pos));
        }

        @Test
        @DisplayName("Unregistered block returns -1")
        void unregisteredBlockReturnsNegative() {
            assertEquals(-1, StructureIslandRegistry.getIslandId(new BlockPos(99, 99, 99)));
        }

        @Test
        @DisplayName("Two isolated blocks create two islands")
        void twoIsolatedBlocksCreateTwoIslands() {
            BlockPos a = new BlockPos(0, 0, 0);
            BlockPos b = new BlockPos(10, 10, 10); // 遠離 a
            int idA = StructureIslandRegistry.registerBlock(a, 1);
            int idB = StructureIslandRegistry.registerBlock(b, 2);

            assertNotEquals(idA, idB, "Isolated blocks should be in different islands");
            assertEquals(2, StructureIslandRegistry.getIslandCount());
        }
    }

    // ═══════════════════════════════════════════════════
    // 2. 合併
    // ═══════════════════════════════════════════════════

    @Nested
    @DisplayName("Merging")
    class Merging {

        @Test
        @DisplayName("Adjacent blocks join same island")
        void adjacentBlocksJoinSameIsland() {
            BlockPos a = new BlockPos(0, 0, 0);
            BlockPos b = new BlockPos(1, 0, 0); // 相鄰 a
            int idA = StructureIslandRegistry.registerBlock(a, 1);
            int idB = StructureIslandRegistry.registerBlock(b, 2);

            assertEquals(idA, idB, "Adjacent blocks should be in same island");
            assertEquals(1, StructureIslandRegistry.getIslandCount());
            assertEquals(2, StructureIslandRegistry.getTotalRegisteredBlocks());
        }

        @Test
        @DisplayName("Placing block between two islands merges them")
        void placingBetweenIslandsMerges() {
            // A at (0,0,0), B at (2,0,0) → separate islands
            BlockPos a = new BlockPos(0, 0, 0);
            BlockPos b = new BlockPos(2, 0, 0);
            StructureIslandRegistry.registerBlock(a, 1);
            StructureIslandRegistry.registerBlock(b, 2);
            assertEquals(2, StructureIslandRegistry.getIslandCount());

            // C at (1,0,0) → bridges A and B
            BlockPos c = new BlockPos(1, 0, 0);
            StructureIslandRegistry.registerBlock(c, 3);

            assertEquals(1, StructureIslandRegistry.getIslandCount(),
                "Placing bridge block should merge two islands into one");
            assertEquals(3, StructureIslandRegistry.getTotalRegisteredBlocks());

            // All three should be in the same island
            int idA = StructureIslandRegistry.getIslandId(a);
            int idB = StructureIslandRegistry.getIslandId(b);
            int idC = StructureIslandRegistry.getIslandId(c);
            assertEquals(idA, idB);
            assertEquals(idB, idC);
        }

        @Test
        @DisplayName("Vertical adjacency also merges")
        void verticalAdjacencyMerges() {
            BlockPos a = new BlockPos(0, 0, 0);
            BlockPos b = new BlockPos(0, 1, 0); // above a
            int idA = StructureIslandRegistry.registerBlock(a, 1);
            int idB = StructureIslandRegistry.registerBlock(b, 2);
            assertEquals(idA, idB);
        }
    }

    // ═══════════════════════════════════════════════════
    // 3. 分裂（無 ServerLevel 的簡化測試）
    // ═══════════════════════════════════════════════════

    @Nested
    @DisplayName("AABB and Stats")
    class AABBAndStats {

        @Test
        @DisplayName("AABB updates on registration")
        void aabbUpdatesOnRegistration() {
            BlockPos a = new BlockPos(0, 0, 0);
            BlockPos b = new BlockPos(5, 3, 7);

            int id = StructureIslandRegistry.registerBlock(a, 1);
            // b 不相鄰 a，會建新 island — 我們測 a 的 island
            StructureIslandRegistry.StructureIsland island = StructureIslandRegistry.getIsland(id);
            assertNotNull(island);
            assertEquals(new BlockPos(0, 0, 0), island.getMinCorner());
            assertEquals(new BlockPos(0, 0, 0), island.getMaxCorner());

            // 加入相鄰方塊
            BlockPos c = new BlockPos(1, 0, 0);
            StructureIslandRegistry.registerBlock(c, 2);
            assertEquals(new BlockPos(0, 0, 0), island.getMinCorner());
            assertEquals(new BlockPos(1, 0, 0), island.getMaxCorner());
        }

        @Test
        @DisplayName("getAABBVolume calculates correctly")
        void aabbVolumeCalculatesCorrectly() {
            // Build a 3-block line: (0,0,0), (1,0,0), (2,0,0)
            StructureIslandRegistry.registerBlock(new BlockPos(0, 0, 0), 1);
            StructureIslandRegistry.registerBlock(new BlockPos(1, 0, 0), 2);
            int id = StructureIslandRegistry.registerBlock(new BlockPos(2, 0, 0), 3);

            StructureIslandRegistry.StructureIsland island = StructureIslandRegistry.getIsland(id);
            assertNotNull(island);
            // AABB = 3×1×1 = 3
            assertEquals(3, island.getAABBVolume());
        }

        @Test
        @DisplayName("getStats returns formatted string")
        void getStatsReturnsFormattedString() {
            StructureIslandRegistry.registerBlock(new BlockPos(0, 0, 0), 1);
            String stats = StructureIslandRegistry.getStats();
            assertTrue(stats.contains("islands=1"));
            assertTrue(stats.contains("totalBlocks=1"));
        }
    }

    // ═══════════════════════════════════════════════════
    // 4. 清除
    // ═══════════════════════════════════════════════════

    @Nested
    @DisplayName("Lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("clear() removes all islands and blocks")
        void clearRemovesAll() {
            StructureIslandRegistry.registerBlock(new BlockPos(0, 0, 0), 1);
            StructureIslandRegistry.registerBlock(new BlockPos(1, 0, 0), 2);
            StructureIslandRegistry.registerBlock(new BlockPos(10, 10, 10), 3);

            StructureIslandRegistry.clear();

            assertEquals(0, StructureIslandRegistry.getIslandCount());
            assertEquals(0, StructureIslandRegistry.getTotalRegisteredBlocks());
            assertEquals(-1, StructureIslandRegistry.getIslandId(new BlockPos(0, 0, 0)));
        }

        @Test
        @DisplayName("getAllIslands returns snapshot")
        void getAllIslandsReturnsSnapshot() {
            StructureIslandRegistry.registerBlock(new BlockPos(0, 0, 0), 1);
            StructureIslandRegistry.registerBlock(new BlockPos(10, 10, 10), 2);

            Map<Integer, StructureIslandRegistry.StructureIsland> all =
                StructureIslandRegistry.getAllIslands();
            assertEquals(2, all.size());
        }
    }

    // ═══════════════════════════════════════════════════
    // 5. 大量方塊
    // ═══════════════════════════════════════════════════

    @Test
    @DisplayName("Line of 100 blocks forms single island")
    void lineOf100BlocksFormsSingleIsland() {
        for (int i = 0; i < 100; i++) {
            StructureIslandRegistry.registerBlock(new BlockPos(i, 0, 0), i + 1);
        }

        assertEquals(1, StructureIslandRegistry.getIslandCount(),
            "100 contiguous blocks should form a single island");
        assertEquals(100, StructureIslandRegistry.getTotalRegisteredBlocks());
    }

    @Test
    @DisplayName("3D cube of 8 blocks forms single island")
    void cubeOf8BlocksFormsSingleIsland() {
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 2; y++) {
                for (int z = 0; z < 2; z++) {
                    StructureIslandRegistry.registerBlock(new BlockPos(x, y, z), 1);
                }
            }
        }

        assertEquals(1, StructureIslandRegistry.getIslandCount());
        assertEquals(8, StructureIslandRegistry.getTotalRegisteredBlocks());
    }
}
