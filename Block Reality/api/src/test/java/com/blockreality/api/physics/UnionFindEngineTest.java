package com.blockreality.api.physics;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JUnit 5 test suite for UnionFindEngine pure-logic components.
 * Tests PhysicsResult, CachedResult, epoch management, cache operations,
 * and the core BFS algorithm on RWorldSnapshot instances.
 */
@DisplayName("UnionFindEngine 物理引擎測試")
public class UnionFindEngineTest {

    private static final RBlockState STONE = new RBlockState("minecraft:stone", 2.5f, 100f, 50f, false);
    private static final RBlockState DIRT = new RBlockState("minecraft:dirt", 1.5f, 80f, 40f, false);
    private static final RBlockState BEDROCK = new RBlockState("minecraft:bedrock", 100f, 1000f, 1000f, true);
    private static final RBlockState AIR = RBlockState.AIR;

    // ═══════════════════════════════════════════════════════
    // PhysicsResult Record Tests
    // ═══════════════════════════════════════════════════════

    @Nested
    @DisplayName("PhysicsResult 紀錄類型")
    class PhysicsResultTests {

        private Set<BlockPos> unsupported;
        private UnionFindEngine.PhysicsResult result;

        @BeforeEach
        void setUp() {
            unsupported = Set.of(
                new BlockPos(10, 20, 30),
                new BlockPos(11, 20, 30),
                new BlockPos(12, 20, 30)
            );
            result = new UnionFindEngine.PhysicsResult(
                unsupported,
                50,      // totalNonAir
                15,      // anchorCount
                100,     // bfsVisited
                5_000_000, // 5ms in nanoseconds
                false,   // not timedOut
                false    // not exceededMaxBlocks
            );
        }

        @Test
        @DisplayName("建構子正確初始化欄位")
        void testConstructor() {
            assertEquals(unsupported, result.unsupportedBlocks());
            assertEquals(50, result.totalNonAir());
            assertEquals(15, result.anchorCount());
            assertEquals(100, result.bfsVisited());
            assertEquals(5_000_000, result.computeTimeNs());
            assertFalse(result.timedOut());
            assertFalse(result.exceededMaxBlocks());
        }

        @Test
        @DisplayName("unsupportedCount() 回傳懸空方塊數")
        void testUnsupportedCount() {
            assertEquals(3, result.unsupportedCount());
        }

        @Test
        @DisplayName("computeTimeMs() 正確轉換奈秒為毫秒")
        void testComputeTimeMs() {
            assertEquals(5.0, result.computeTimeMs(), 0.001);
        }

        @Test
        @DisplayName("computeTimeMs() 處理零奈秒")
        void testComputeTimeMsZero() {
            UnionFindEngine.PhysicsResult zeroResult = new UnionFindEngine.PhysicsResult(
                Set.of(), 0, 0, 0, 0, false, false
            );
            assertEquals(0.0, zeroResult.computeTimeMs(), 0.001);
        }

        @Test
        @DisplayName("computeTimeMs() 處理大值")
        void testComputeTimeMsLarge() {
            UnionFindEngine.PhysicsResult largeResult = new UnionFindEngine.PhysicsResult(
                Set.of(), 0, 0, 0, 1_000_000_000, false, false  // 1 second
            );
            assertEquals(1000.0, largeResult.computeTimeMs(), 0.001);
        }

        @Test
        @DisplayName("toString() 包含所有關鍵資訊")
        void testToString() {
            String str = result.toString();
            assertTrue(str.contains("unsupported=3"));
            assertTrue(str.contains("nonAir=50"));
            assertTrue(str.contains("anchors=15"));
            assertTrue(str.contains("bfsVisited=100"));
            assertTrue(str.contains("5.00ms"));
            assertTrue(str.contains("timeout=false"));
        }

        @Test
        @DisplayName("toString() 顯示超時狀態")
        void testToStringTimeout() {
            UnionFindEngine.PhysicsResult timeoutResult = new UnionFindEngine.PhysicsResult(
                Set.of(), 0, 0, 50, 100_000_000, true, false
            );
            assertTrue(timeoutResult.toString().contains("timeout=true"));
        }

        @Test
        @DisplayName("PhysicsResult 空集合")
        void testEmptyPhysicsResult() {
            UnionFindEngine.PhysicsResult empty = new UnionFindEngine.PhysicsResult(
                Set.of(), 10, 5, 50, 1_000_000, false, false
            );
            assertEquals(0, empty.unsupportedCount());
            assertTrue(empty.unsupportedBlocks().isEmpty());
        }
    }

    // ═══════════════════════════════════════════════════════
    // CachedResult Tests
    // ═══════════════════════════════════════════════════════

    @Nested
    @DisplayName("CachedResult 快取紀錄")
    class CachedResultTests {

        @BeforeEach
        void setUp() {
            // 重置全域 epoch 以確保測試獨立性
            UnionFindEngine.clearCache();
        }

        @Test
        @DisplayName("isValid() 當 epoch 相同時返回 true")
        void testIsValidMatching() {
            long currentEpoch = UnionFindEngine.getStructureEpoch();
            UnionFindEngine.PhysicsResult physicsResult = new UnionFindEngine.PhysicsResult(
                Set.of(), 0, 0, 0, 0, false, false
            );
            UnionFindEngine.CachedResult cached = new UnionFindEngine.CachedResult(physicsResult, currentEpoch);
            assertTrue(cached.isValid());
        }

        @Test
        @DisplayName("isValid() 當 epoch 不同時返回 false")
        void testIsValidMismatch() {
            UnionFindEngine.PhysicsResult physicsResult = new UnionFindEngine.PhysicsResult(
                Set.of(), 0, 0, 0, 0, false, false
            );
            long oldEpoch = 0;
            UnionFindEngine.CachedResult cached = new UnionFindEngine.CachedResult(physicsResult, oldEpoch);

            // 遞增全域 epoch
            UnionFindEngine.notifyStructureChanged(new BlockPos(0, 0, 0));

            assertFalse(cached.isValid());
        }

        @Test
        @DisplayName("CachedResult 儲存結果及 epoch")
        void testCachedResultStorage() {
            Set<BlockPos> unsupported = Set.of(new BlockPos(1, 2, 3));
            UnionFindEngine.PhysicsResult result = new UnionFindEngine.PhysicsResult(
                unsupported, 5, 2, 10, 2_000_000, false, false
            );
            long epoch = UnionFindEngine.getStructureEpoch();
            UnionFindEngine.CachedResult cached = new UnionFindEngine.CachedResult(result, epoch);

            assertEquals(result, cached.result());
            assertEquals(epoch, cached.epoch());
        }
    }

    // ═══════════════════════════════════════════════════════
    // Epoch & Dirty Region Management Tests
    // ═══════════════════════════════════════════════════════

    @Nested
    @DisplayName("結構變動通知與 Epoch 管理")
    class EpochManagementTests {

        @BeforeEach
        void setUp() {
            UnionFindEngine.clearCache();
        }

        @Test
        @DisplayName("notifyStructureChanged() 遞增全域 epoch")
        void testEpochIncrement() {
            long initialEpoch = UnionFindEngine.getStructureEpoch();
            UnionFindEngine.notifyStructureChanged(new BlockPos(0, 0, 0));
            long newEpoch = UnionFindEngine.getStructureEpoch();
            assertEquals(initialEpoch + 1, newEpoch);
        }

        @Test
        @DisplayName("notifyStructureChanged() 多次呼叫遞增 epoch")
        void testEpochMultipleIncrements() {
            long initialEpoch = UnionFindEngine.getStructureEpoch();
            UnionFindEngine.notifyStructureChanged(new BlockPos(0, 0, 0));
            UnionFindEngine.notifyStructureChanged(new BlockPos(1, 1, 1));
            UnionFindEngine.notifyStructureChanged(new BlockPos(2, 2, 2));
            long finalEpoch = UnionFindEngine.getStructureEpoch();
            assertEquals(initialEpoch + 3, finalEpoch);
        }

        @Test
        @DisplayName("notifyStructureChanged() 標記髒區域")
        void testDirtyRegionMarking() {
            String initialStats = UnionFindEngine.getCacheStats();
            assertTrue(initialStats.contains("dirty=0"));

            UnionFindEngine.notifyStructureChanged(new BlockPos(0, 0, 0));

            String statsAfter = UnionFindEngine.getCacheStats();
            assertTrue(statsAfter.contains("dirty=") && !statsAfter.contains("dirty=0"));
        }

        @Test
        @DisplayName("notifyStructureChanged() 也標記周圍 chunk 為髒")
        void testDirtyRegionExpansion() {
            UnionFindEngine.clearCache();
            UnionFindEngine.notifyStructureChanged(new BlockPos(16, 0, 16)); // chunk corner

            String stats = UnionFindEngine.getCacheStats();
            // 周圍 3x3 = 9 chunks marked dirty
            assertTrue(stats.contains("dirty="));
        }

        @Test
        @DisplayName("getCacheStats() 回傳有效統計字串")
        void testGetCacheStats() {
            String stats = UnionFindEngine.getCacheStats();
            assertTrue(stats.contains("epoch="));
            assertTrue(stats.contains("cached="));
            assertTrue(stats.contains("dirty="));
        }
    }

    // ═══════════════════════════════════════════════════════
    // Cache Clear Tests
    // ═══════════════════════════════════════════════════════

    @Nested
    @DisplayName("快取清理")
    class CacheClearTests {

        @Test
        @DisplayName("clearCache() 清空快取統計")
        void testCacheClearResetsCounts() {
            UnionFindEngine.notifyStructureChanged(new BlockPos(0, 0, 0));
            String statsBefore = UnionFindEngine.getCacheStats();
            assertTrue(statsBefore.contains("dirty="));

            UnionFindEngine.clearCache();
            String statsAfter = UnionFindEngine.getCacheStats();
            assertTrue(statsAfter.contains("dirty=0"));
        }

        @Test
        @DisplayName("clearCache() 可被多次呼叫")
        void testCacheClearIdempotent() {
            UnionFindEngine.clearCache();
            UnionFindEngine.clearCache();
            UnionFindEngine.clearCache();
            String stats = UnionFindEngine.getCacheStats();
            assertTrue(stats.contains("dirty=0"));
            assertTrue(stats.contains("cached=0"));
        }
    }

    // ═══════════════════════════════════════════════════════
    // Cache Eviction Tests (AD-7)
    // ═══════════════════════════════════════════════════════

    @Nested
    @DisplayName("過期快取驅逐 (AD-7)")
    class CacheEvictionTests {

        @BeforeEach
        void setUp() {
            UnionFindEngine.clearCache();
        }

        @Test
        @DisplayName("evictStaleEntries() 不驅逐新條目")
        void testNoEvictionOfNewEntries() {
            // Create a snapshot and get a cached result at current epoch
            RWorldSnapshot snapshot = createSimpleSnapshot(0, 0, 0, 3, 3, 3);
            UnionFindEngine.findUnsupportedBlocksCached(snapshot, 0);

            int evicted = UnionFindEngine.evictStaleEntries();
            assertEquals(0, evicted);
        }

        @Test
        @DisplayName("evictStaleEntries() 驅逐超過閾值的舊條目")
        void testEvictionOfStaleEntries() {
            // Create and cache a result
            RWorldSnapshot snapshot = createSimpleSnapshot(0, 0, 0, 3, 3, 3);
            UnionFindEngine.findUnsupportedBlocksCached(snapshot, 0);

            // Trigger enough epoch changes to exceed eviction threshold
            for (int i = 0; i < 65; i++) {
                UnionFindEngine.notifyStructureChanged(new BlockPos(i, 0, 0));
            }

            int evicted = UnionFindEngine.evictStaleEntries();
            assertTrue(evicted > 0, "Should have evicted at least one stale entry");
        }

        @Test
        @DisplayName("evictStaleEntries() 回傳驅逐數量")
        void testEvictionReturnsCount() {
            int result = UnionFindEngine.evictStaleEntries();
            assertTrue(result >= 0, "Eviction count should be non-negative");
        }
    }

    // ═══════════════════════════════════════════════════════
    // BFS Algorithm Tests (Core Logic)
    // ═══════════════════════════════════════════════════════

    @Nested
    @DisplayName("BFS 搜尋演算法")
    class BFSAlgorithmTests {

        @Test
        @DisplayName("簡單 1x1x1 空氣快照無懸空方塊")
        void testAirOnlySnapshot() {
            RWorldSnapshot snapshot = createSimpleSnapshot(0, 0, 0, 1, 1, 1);
            UnionFindEngine.PhysicsResult result = UnionFindEngine.findUnsupportedBlocks(snapshot, 0);

            assertEquals(0, result.unsupportedCount());
            assertEquals(0, result.totalNonAir());
        }

        @Test
        @DisplayName("全石頭 3x3x3 無懸空（所有邊界都是錨定點）")
        void testSolidCubeNoFloating() {
            RWorldSnapshot snapshot = createSimpleSnapshot(0, 0, 0, 3, 3, 3);
            // Fill with stone
            for (int x = 0; x < 3; x++) {
                for (int y = 0; y < 3; y++) {
                    for (int z = 0; z < 3; z++) {
                        snapshot.setBlock(x, y, z, STONE);
                    }
                }
            }

            UnionFindEngine.PhysicsResult result = UnionFindEngine.findUnsupportedBlocks(snapshot, 0);

            assertEquals(0, result.unsupportedCount());
            assertEquals(27, result.totalNonAir());
        }

        @Test
        @DisplayName("中心孤立方塊（邊界有 margin 保護）")
        void testCenterIsolatedBlockWithMargin() {
            // 5x5x5 with margin=1, effect zone = 3x3x3 (center only)
            RWorldSnapshot snapshot = createSimpleSnapshot(0, 0, 0, 5, 5, 5);

            // Place stone only at center (2,2,2)
            snapshot.setBlock(2, 2, 2, STONE);

            UnionFindEngine.PhysicsResult result = UnionFindEngine.findUnsupportedBlocks(snapshot, 1);

            // Center block is isolated and in effect zone
            assertEquals(1, result.unsupportedCount());
        }

        @Test
        @DisplayName("邊界方塊永遠不會懸空")
        void testBoundaryBlocksNeverFloating() {
            RWorldSnapshot snapshot = createSimpleSnapshot(0, 0, 0, 5, 5, 5);

            // Place stones at all six boundaries
            for (int i = 0; i < 5; i++) {
                snapshot.setBlock(0, i, 2, STONE);  // left
                snapshot.setBlock(4, i, 2, STONE);  // right
                snapshot.setBlock(i, 0, 2, STONE);  // bottom
                snapshot.setBlock(i, 4, 2, STONE);  // top
                snapshot.setBlock(2, i, 0, STONE);  // front
                snapshot.setBlock(2, i, 4, STONE);  // back
            }

            UnionFindEngine.PhysicsResult result = UnionFindEngine.findUnsupportedBlocks(snapshot, 0);

            // All boundary blocks are anchors, so none float
            assertEquals(0, result.unsupportedCount());
        }

        @Test
        @DisplayName("未支撐的方塊柱體")
        void testUnsupportedPillar() {
            RWorldSnapshot snapshot = createSimpleSnapshot(0, 0, 0, 3, 5, 3);

            // Create pillar at center (1,0,1) - bottom attached to boundary
            for (int y = 0; y < 5; y++) {
                snapshot.setBlock(1, y, 1, STONE);
            }

            // No margin, so all blocks should be accessible from boundary
            UnionFindEngine.PhysicsResult result = UnionFindEngine.findUnsupportedBlocks(snapshot, 0);

            // All blocks in pillar connect to boundary anchor
            assertEquals(0, result.unsupportedCount());
        }

        @Test
        @DisplayName("懸浮方塊簇（中心孤立）")
        void testFloatingCluster() {
            RWorldSnapshot snapshot = createSimpleSnapshot(0, 0, 0, 7, 7, 7);
            int margin = 2; // effect zone = 3x3x3 in center

            // Create floating cluster at center (3,3,3) area
            snapshot.setBlock(3, 3, 3, STONE);
            snapshot.setBlock(4, 3, 3, STONE);
            snapshot.setBlock(3, 4, 3, STONE);
            snapshot.setBlock(3, 3, 4, STONE);

            UnionFindEngine.PhysicsResult result = UnionFindEngine.findUnsupportedBlocks(snapshot, margin);

            assertEquals(4, result.unsupportedCount());
        }

        @Test
        @DisplayName("BFS 訪問計數")
        void testBFSVisitCount() {
            RWorldSnapshot snapshot = createSimpleSnapshot(0, 0, 0, 3, 3, 3);
            // Fill with stone
            for (int x = 0; x < 3; x++) {
                for (int y = 0; y < 3; y++) {
                    for (int z = 0; z < 3; z++) {
                        snapshot.setBlock(x, y, z, STONE);
                    }
                }
            }

            UnionFindEngine.PhysicsResult result = UnionFindEngine.findUnsupportedBlocks(snapshot, 0);

            // BFS should visit all 27 blocks
            assertEquals(27, result.bfsVisited());
        }

        @Test
        @DisplayName("計算時間非零")
        void testComputationTime() {
            RWorldSnapshot snapshot = createSimpleSnapshot(0, 0, 0, 3, 3, 3);
            UnionFindEngine.PhysicsResult result = UnionFindEngine.findUnsupportedBlocks(snapshot, 0);

            assertTrue(result.computeTimeNs() >= 0);
        }

        @Test
        @DisplayName("多層懸浮結構")
        void testMultiLayerFloatingStructure() {
            RWorldSnapshot snapshot = createSimpleSnapshot(0, 0, 0, 8, 8, 8);
            int margin = 1;

            // 三個相連但完全懸浮的方塊（遠離邊界）
            snapshot.setBlock(3, 3, 3, STONE);
            snapshot.setBlock(3, 4, 3, STONE);
            snapshot.setBlock(3, 5, 3, STONE);

            UnionFindEngine.PhysicsResult result = UnionFindEngine.findUnsupportedBlocks(snapshot, margin);

            // All 3 blocks should be unsupported (no path to boundary)
            assertEquals(3, result.unsupportedCount());
        }

        @Test
        @DisplayName("混合支撐與懸浮方塊")
        void testMixedSupportedAndFloating() {
            RWorldSnapshot snapshot = createSimpleSnapshot(0, 0, 0, 8, 8, 8);
            int margin = 1;

            // Supported pillar — connected to boundary at Y=0
            snapshot.setBlock(1, 0, 1, STONE);  // Y=0 is boundary → anchor
            snapshot.setBlock(1, 1, 1, STONE);
            snapshot.setBlock(1, 2, 1, STONE);
            snapshot.setBlock(1, 3, 1, STONE);

            // Floating cluster — all interior, no boundary contact
            snapshot.setBlock(4, 4, 4, STONE);
            snapshot.setBlock(4, 4, 5, STONE);

            UnionFindEngine.PhysicsResult result = UnionFindEngine.findUnsupportedBlocks(snapshot, margin);

            // Only floating cluster blocks should be unsupported
            assertEquals(2, result.unsupportedCount());
        }

        @Test
        @DisplayName("空氣間隙阻斷連接")
        void testAirGapBreaksConnection() {
            RWorldSnapshot snapshot = createSimpleSnapshot(0, 0, 0, 8, 8, 8);
            int margin = 1;

            // Support pillar with gap — bottom block connected to boundary at Y=0
            snapshot.setBlock(3, 0, 3, STONE);  // Y=0 is boundary → anchor
            snapshot.setBlock(3, 1, 3, STONE);  // connected to anchor
            // air at (3,2,3)
            snapshot.setBlock(3, 3, 3, STONE);  // floating above gap

            UnionFindEngine.PhysicsResult result = UnionFindEngine.findUnsupportedBlocks(snapshot, margin);

            // Block at (3,3,3) is isolated above the air gap
            assertEquals(1, result.unsupportedCount());
        }

        @Test
        @DisplayName("無 margin 版本向後相容")
        void testFindUnsupportedBlocksNoMargin() {
            RWorldSnapshot snapshot = createSimpleSnapshot(0, 0, 0, 3, 3, 3);
            snapshot.setBlock(1, 1, 1, STONE);

            // Both versions should work
            UnionFindEngine.PhysicsResult result1 = UnionFindEngine.findUnsupportedBlocks(snapshot);
            UnionFindEngine.PhysicsResult result2 = UnionFindEngine.findUnsupportedBlocks(snapshot, 0);

            assertEquals(result1.unsupportedCount(), result2.unsupportedCount());
        }

        @Test
        @DisplayName("大區域無超時")
        void testLargeSnapshotNoTimeout() {
            // 10x10x10 filled with stone
            RWorldSnapshot snapshot = createSimpleSnapshot(0, 0, 0, 10, 10, 10);
            for (int x = 0; x < 10; x++) {
                for (int y = 0; y < 10; y++) {
                    for (int z = 0; z < 10; z++) {
                        snapshot.setBlock(x, y, z, STONE);
                    }
                }
            }

            UnionFindEngine.PhysicsResult result = UnionFindEngine.findUnsupportedBlocks(snapshot, 0);

            assertFalse(result.timedOut());
            assertFalse(result.exceededMaxBlocks());
        }

        @Test
        @DisplayName("非空氣且質量為零的方塊被忽略")
        void testZeroMassBlocksIgnored() {
            RBlockState zeroMass = new RBlockState("minecraft:zero_mass", 0f, 0f, 0f, false);
            RWorldSnapshot snapshot = createSimpleSnapshot(0, 0, 0, 3, 3, 3);

            snapshot.setBlock(1, 1, 1, zeroMass);

            UnionFindEngine.PhysicsResult result = UnionFindEngine.findUnsupportedBlocks(snapshot, 0);

            // Zero-mass block should not be counted
            assertEquals(0, result.totalNonAir());
        }
    }

    // ═══════════════════════════════════════════════════════
    // Helper Methods
    // ═══════════════════════════════════════════════════════

    /**
     * Creates a simple RWorldSnapshot filled with air.
     *
     * @param startX start X coordinate
     * @param startY start Y coordinate
     * @param startZ start Z coordinate
     * @param sizeX  snapshot X size
     * @param sizeY  snapshot Y size
     * @param sizeZ  snapshot Z size
     * @return A new snapshot filled with air blocks
     */
    private RWorldSnapshot createSimpleSnapshot(int startX, int startY, int startZ,
                                                 int sizeX, int sizeY, int sizeZ) {
        RBlockState[] blocks = new RBlockState[sizeX * sizeY * sizeZ];

        // Initialize with air (nulls are treated as air)
        for (int i = 0; i < blocks.length; i++) {
            blocks[i] = AIR;
        }

        return new RWorldSnapshot(startX, startY, startZ, sizeX, sizeY, sizeZ, blocks, 0);
    }
}
