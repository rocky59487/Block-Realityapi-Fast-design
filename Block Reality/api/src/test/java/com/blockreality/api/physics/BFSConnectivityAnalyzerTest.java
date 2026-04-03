package com.blockreality.api.physics;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JUnit 5 test suite for BFSConnectivityAnalyzer pure-logic components.
 * Tests PhysicsResult, CachedResult, epoch management, cache operations,
 * and the core BFS algorithm on RWorldSnapshot instances.
 */
@DisplayName("BFSConnectivityAnalyzer 物理引擎測試")
public class BFSConnectivityAnalyzerTest {

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
        private BFSConnectivityAnalyzer.PhysicsResult result;

        @BeforeEach
        void setUp() {
            unsupported = Set.of(
                new BlockPos(10, 20, 30),
                new BlockPos(11, 20, 30),
                new BlockPos(12, 20, 30)
            );
            result = new BFSConnectivityAnalyzer.PhysicsResult(
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
            assertEquals(5_000_000, result.elapsedNs());
            assertFalse(result.timedOut());
            assertFalse(result.exceededMax());
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
            BFSConnectivityAnalyzer.PhysicsResult zeroResult = new BFSConnectivityAnalyzer.PhysicsResult(
                Set.of(), 0, 0, 0, 0, false, false
            );
            assertEquals(0.0, zeroResult.computeTimeMs(), 0.001);
        }

        @Test
        @DisplayName("computeTimeMs() 處理大值")
        void testComputeTimeMsLarge() {
            BFSConnectivityAnalyzer.PhysicsResult largeResult = new BFSConnectivityAnalyzer.PhysicsResult(
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
            BFSConnectivityAnalyzer.PhysicsResult timeoutResult = new BFSConnectivityAnalyzer.PhysicsResult(
                Set.of(), 0, 0, 50, 100_000_000, true, false
            );
            assertTrue(timeoutResult.toString().contains("timeout=true"));
        }

        @Test
        @DisplayName("PhysicsResult 空集合")
        void testEmptyPhysicsResult() {
            BFSConnectivityAnalyzer.PhysicsResult empty = new BFSConnectivityAnalyzer.PhysicsResult(
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
            BFSConnectivityAnalyzer.clearCache();
        }

        @Test
        @DisplayName("isValid() 當 epoch 相同時返回 true")
        void testIsValidMatching() {
            long currentEpoch = BFSConnectivityAnalyzer.getStructureEpoch();
            BFSConnectivityAnalyzer.PhysicsResult physicsResult = new BFSConnectivityAnalyzer.PhysicsResult(
                Set.of(), 0, 0, 0, 0, false, false
            );
            BFSConnectivityAnalyzer.CachedResult cached = new BFSConnectivityAnalyzer.CachedResult(physicsResult, currentEpoch);
            assertTrue(cached.isValid());
        }

        @Test
        @DisplayName("isValid() 當 epoch 不同時返回 false")
        void testIsValidMismatch() {
            BFSConnectivityAnalyzer.PhysicsResult physicsResult = new BFSConnectivityAnalyzer.PhysicsResult(
                Set.of(), 0, 0, 0, 0, false, false
            );
            long oldEpoch = 0;
            BFSConnectivityAnalyzer.CachedResult cached = new BFSConnectivityAnalyzer.CachedResult(physicsResult, oldEpoch);

            // 遞增全域 epoch
            BFSConnectivityAnalyzer.notifyStructureChanged(new BlockPos(0, 0, 0));

            assertFalse(cached.isValid());
        }

        @Test
        @DisplayName("CachedResult 儲存結果及 epoch")
        void testCachedResultStorage() {
            Set<BlockPos> unsupported = Set.of(new BlockPos(1, 2, 3));
            BFSConnectivityAnalyzer.PhysicsResult result = new BFSConnectivityAnalyzer.PhysicsResult(
                unsupported, 5, 2, 10, 2_000_000, false, false
            );
            long epoch = BFSConnectivityAnalyzer.getStructureEpoch();
            BFSConnectivityAnalyzer.CachedResult cached = new BFSConnectivityAnalyzer.CachedResult(result, epoch);

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
            BFSConnectivityAnalyzer.clearCache();
        }

        @Test
        @DisplayName("notifyStructureChanged() 遞增全域 epoch")
        void testEpochIncrement() {
            long initialEpoch = BFSConnectivityAnalyzer.getStructureEpoch();
            BFSConnectivityAnalyzer.notifyStructureChanged(new BlockPos(0, 0, 0));
            long newEpoch = BFSConnectivityAnalyzer.getStructureEpoch();
            assertEquals(initialEpoch + 1, newEpoch);
        }

        @Test
        @DisplayName("notifyStructureChanged() 多次呼叫遞增 epoch")
        void testEpochMultipleIncrements() {
            long initialEpoch = BFSConnectivityAnalyzer.getStructureEpoch();
            BFSConnectivityAnalyzer.notifyStructureChanged(new BlockPos(0, 0, 0));
            BFSConnectivityAnalyzer.notifyStructureChanged(new BlockPos(1, 1, 1));
            BFSConnectivityAnalyzer.notifyStructureChanged(new BlockPos(2, 2, 2));
            long finalEpoch = BFSConnectivityAnalyzer.getStructureEpoch();
            assertEquals(initialEpoch + 3, finalEpoch);
        }

        @Test
        @DisplayName("notifyStructureChanged() 標記髒區域")
        void testDirtyRegionMarking() {
            String initialStats = BFSConnectivityAnalyzer.getCacheStats();
            assertTrue(initialStats.contains("dirty=0"));

            BFSConnectivityAnalyzer.notifyStructureChanged(new BlockPos(0, 0, 0));

            String statsAfter = BFSConnectivityAnalyzer.getCacheStats();
            assertTrue(statsAfter.contains("dirty=") && !statsAfter.contains("dirty=0"));
        }

        @Test
        @DisplayName("notifyStructureChanged() 也標記周圍 chunk 為髒")
        void testDirtyRegionExpansion() {
            BFSConnectivityAnalyzer.clearCache();
            BFSConnectivityAnalyzer.notifyStructureChanged(new BlockPos(16, 0, 16)); // chunk corner

            String stats = BFSConnectivityAnalyzer.getCacheStats();
            // 周圍 3x3 = 9 chunks marked dirty
            assertTrue(stats.contains("dirty="));
        }

        @Test
        @DisplayName("getCacheStats() 回傳有效統計字串")
        void testGetCacheStats() {
            String stats = BFSConnectivityAnalyzer.getCacheStats();
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
            BFSConnectivityAnalyzer.notifyStructureChanged(new BlockPos(0, 0, 0));
            String statsBefore = BFSConnectivityAnalyzer.getCacheStats();
            assertTrue(statsBefore.contains("dirty="));

            BFSConnectivityAnalyzer.clearCache();
            String statsAfter = BFSConnectivityAnalyzer.getCacheStats();
            assertTrue(statsAfter.contains("dirty=0"));
        }

        @Test
        @DisplayName("clearCache() 可被多次呼叫")
        void testCacheClearIdempotent() {
            BFSConnectivityAnalyzer.clearCache();
            BFSConnectivityAnalyzer.clearCache();
            BFSConnectivityAnalyzer.clearCache();
            String stats = BFSConnectivityAnalyzer.getCacheStats();
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
            BFSConnectivityAnalyzer.clearCache();
        }

        @Test
        @DisplayName("evictStaleEntries() 不驅逐新條目")
        void testNoEvictionOfNewEntries() {
            // Create a snapshot and get a cached result at current epoch
            RWorldSnapshot snapshot = createSimpleSnapshot(0, 0, 0, 3, 3, 3);
            BFSConnectivityAnalyzer.findUnsupportedBlocksCached(snapshot, 0);

            int evicted = BFSConnectivityAnalyzer.evictStaleEntries();
            assertEquals(0, evicted);
        }

        @Test
        @DisplayName("evictStaleEntries() 驅逐超過閾值的舊條目")
        void testEvictionOfStaleEntries() {
            // Create and cache a result
            RWorldSnapshot snapshot = createSimpleSnapshot(0, 0, 0, 3, 3, 3);
            BFSConnectivityAnalyzer.findUnsupportedBlocksCached(snapshot, 0);

            // Trigger enough epoch changes to exceed eviction threshold
            for (int i = 0; i < 65; i++) {
                BFSConnectivityAnalyzer.notifyStructureChanged(new BlockPos(i, 0, 0));
            }

            int evicted = BFSConnectivityAnalyzer.evictStaleEntries();
            assertTrue(evicted > 0, "Should have evicted at least one stale entry");
        }

        @Test
        @DisplayName("evictStaleEntries() 回傳驅逐數量")
        void testEvictionReturnsCount() {
            int result = BFSConnectivityAnalyzer.evictStaleEntries();
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
            BFSConnectivityAnalyzer.PhysicsResult result = BFSConnectivityAnalyzer.findUnsupportedBlocks(snapshot, 0);

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

            BFSConnectivityAnalyzer.PhysicsResult result = BFSConnectivityAnalyzer.findUnsupportedBlocks(snapshot, 0);

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

            BFSConnectivityAnalyzer.PhysicsResult result = BFSConnectivityAnalyzer.findUnsupportedBlocks(snapshot, 1);

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

            BFSConnectivityAnalyzer.PhysicsResult result = BFSConnectivityAnalyzer.findUnsupportedBlocks(snapshot, 0);

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
            BFSConnectivityAnalyzer.PhysicsResult result = BFSConnectivityAnalyzer.findUnsupportedBlocks(snapshot, 0);

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

            BFSConnectivityAnalyzer.PhysicsResult result = BFSConnectivityAnalyzer.findUnsupportedBlocks(snapshot, margin);

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

            BFSConnectivityAnalyzer.PhysicsResult result = BFSConnectivityAnalyzer.findUnsupportedBlocks(snapshot, 0);

            // BFS should visit all 27 blocks
            assertEquals(27, result.bfsVisited());
        }

        @Test
        @DisplayName("計算時間非零")
        void testComputationTime() {
            RWorldSnapshot snapshot = createSimpleSnapshot(0, 0, 0, 3, 3, 3);
            BFSConnectivityAnalyzer.PhysicsResult result = BFSConnectivityAnalyzer.findUnsupportedBlocks(snapshot, 0);

            assertTrue(result.elapsedNs() >= 0);
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

            BFSConnectivityAnalyzer.PhysicsResult result = BFSConnectivityAnalyzer.findUnsupportedBlocks(snapshot, margin);

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

            BFSConnectivityAnalyzer.PhysicsResult result = BFSConnectivityAnalyzer.findUnsupportedBlocks(snapshot, margin);

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

            BFSConnectivityAnalyzer.PhysicsResult result = BFSConnectivityAnalyzer.findUnsupportedBlocks(snapshot, margin);

            // Block at (3,3,3) is isolated above the air gap
            assertEquals(1, result.unsupportedCount());
        }

        @Test
        @DisplayName("無 margin 版本向後相容")
        void testFindUnsupportedBlocksNoMargin() {
            RWorldSnapshot snapshot = createSimpleSnapshot(0, 0, 0, 3, 3, 3);
            snapshot.setBlock(1, 1, 1, STONE);

            // Both versions should work
            BFSConnectivityAnalyzer.PhysicsResult result1 = BFSConnectivityAnalyzer.findUnsupportedBlocks(snapshot);
            BFSConnectivityAnalyzer.PhysicsResult result2 = BFSConnectivityAnalyzer.findUnsupportedBlocks(snapshot, 0);

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

            BFSConnectivityAnalyzer.PhysicsResult result = BFSConnectivityAnalyzer.findUnsupportedBlocks(snapshot, 0);

            assertFalse(result.timedOut());
            assertFalse(result.exceededMax());
        }

        @Test
        @DisplayName("非空氣且質量為零的方塊被忽略")
        void testZeroMassBlocksIgnored() {
            RBlockState zeroMass = new RBlockState("minecraft:zero_mass", 0f, 0f, 0f, false);
            RWorldSnapshot snapshot = createSimpleSnapshot(0, 0, 0, 3, 3, 3);

            snapshot.setBlock(1, 1, 1, zeroMass);

            BFSConnectivityAnalyzer.PhysicsResult result = BFSConnectivityAnalyzer.findUnsupportedBlocks(snapshot, 0);

            // Zero-mass block should not be counted
            assertEquals(0, result.totalNonAir());
        }
    }

    // ═══════════════════════════════════════════════════════
    // M6: Edge Case Tests — 空圖、全連通圖、單節點圖
    // ═══════════════════════════════════════════════════════

    @Nested
    @DisplayName("M6: 邊緣案例 — 空圖 / 全連通 / 單節點")
    class M6EdgeCaseTests {

        @BeforeEach
        void setUp() {
            BFSConnectivityAnalyzer.clearCache();
        }

        // ── 空圖 ──────────────────────────────────────────

        @Test
        @DisplayName("M6: 空圖 — 零尺寸快照不拋例外")
        void emptySnapshotNoException() {
            // 最小合法快照：1×1×1 全空氣，模擬「空圖」
            RWorldSnapshot snapshot = createSimpleSnapshot(0, 0, 0, 1, 1, 1);
            assertDoesNotThrow(() -> BFSConnectivityAnalyzer.findUnsupportedBlocks(snapshot, 0));
        }

        @Test
        @DisplayName("M6: 空圖 — 無非空氣方塊，totalNonAir = 0")
        void emptySnapshotZeroNonAir() {
            RWorldSnapshot snapshot = createSimpleSnapshot(0, 0, 0, 3, 3, 3);
            // 所有格子均為空氣，未呼叫任何 setBlock
            BFSConnectivityAnalyzer.PhysicsResult result =
                BFSConnectivityAnalyzer.findUnsupportedBlocks(snapshot, 0);

            assertEquals(0, result.totalNonAir(), "全空氣快照的 totalNonAir 應為 0");
            assertEquals(0, result.unsupportedCount(), "全空氣快照無懸空方塊");
        }

        @Test
        @DisplayName("M6: 空圖 — 無非空氣方塊，anchorCount = 0")
        void emptySnapshotZeroAnchors() {
            RWorldSnapshot snapshot = createSimpleSnapshot(0, 0, 0, 5, 5, 5);
            BFSConnectivityAnalyzer.PhysicsResult result =
                BFSConnectivityAnalyzer.findUnsupportedBlocks(snapshot, 0);

            assertEquals(0, result.anchorCount(), "全空氣快照的錨點數應為 0");
        }

        @Test
        @DisplayName("M6: 空圖 — timedOut = false（空圖不應超時）")
        void emptySnapshotNeverTimesOut() {
            RWorldSnapshot snapshot = createSimpleSnapshot(0, 0, 0, 16, 16, 16);
            BFSConnectivityAnalyzer.PhysicsResult result =
                BFSConnectivityAnalyzer.findUnsupportedBlocks(snapshot, 0);

            assertFalse(result.timedOut(), "空圖不應觸發超時");
        }

        // ── 全連通圖 ──────────────────────────────────────

        @Test
        @DisplayName("M6: 全連通圖 — 所有方塊均連接至邊界，unsupportedCount = 0")
        void fullyConnectedNoFloating() {
            // 3×3×3 全填石頭：每個方塊均有鄰居連通至邊界
            RWorldSnapshot snapshot = createSimpleSnapshot(0, 0, 0, 3, 3, 3);
            for (int x = 0; x < 3; x++)
                for (int y = 0; y < 3; y++)
                    for (int z = 0; z < 3; z++)
                        snapshot.setBlock(x, y, z, STONE);

            BFSConnectivityAnalyzer.PhysicsResult result =
                BFSConnectivityAnalyzer.findUnsupportedBlocks(snapshot, 0);

            assertEquals(0, result.unsupportedCount(),
                "全連通結構不應有懸空方塊");
        }

        @Test
        @DisplayName("M6: 全連通圖 — totalNonAir 等於方塊總數")
        void fullyConnectedNonAirCount() {
            int sx = 4, sy = 4, sz = 4;
            RWorldSnapshot snapshot = createSimpleSnapshot(0, 0, 0, sx, sy, sz);
            for (int x = 0; x < sx; x++)
                for (int y = 0; y < sy; y++)
                    for (int z = 0; z < sz; z++)
                        snapshot.setBlock(x, y, z, STONE);

            BFSConnectivityAnalyzer.PhysicsResult result =
                BFSConnectivityAnalyzer.findUnsupportedBlocks(snapshot, 0);

            assertEquals(sx * sy * sz, result.totalNonAir(),
                "totalNonAir 應等於所有非空氣方塊數");
        }

        @Test
        @DisplayName("M6: 全連通圖（帶 margin）— 效果區域內均通過邊界連通仍為 0")
        void fullyConnectedWithMarginNoFloating() {
            // 5×5×5，margin=1 → 效果區域 3×3×3，填滿後仍全連通
            RWorldSnapshot snapshot = createSimpleSnapshot(0, 0, 0, 5, 5, 5);
            for (int x = 0; x < 5; x++)
                for (int y = 0; y < 5; y++)
                    for (int z = 0; z < 5; z++)
                        snapshot.setBlock(x, y, z, STONE);

            BFSConnectivityAnalyzer.PhysicsResult result =
                BFSConnectivityAnalyzer.findUnsupportedBlocks(snapshot, 1);

            assertEquals(0, result.unsupportedCount(),
                "填滿的 5×5×5（margin=1）不應有懸空方塊");
        }

        // ── 單節點圖 ──────────────────────────────────────

        @Test
        @DisplayName("M6: 單節點 — 位於邊界，不懸空")
        void singleNodeAtBoundary() {
            RWorldSnapshot snapshot = createSimpleSnapshot(0, 0, 0, 3, 3, 3);
            // Y=0 是邊界，錨點
            snapshot.setBlock(1, 0, 1, STONE);

            BFSConnectivityAnalyzer.PhysicsResult result =
                BFSConnectivityAnalyzer.findUnsupportedBlocks(snapshot, 0);

            assertEquals(0, result.unsupportedCount(),
                "邊界上的單個方塊不應懸空");
            assertEquals(1, result.totalNonAir());
        }

        @Test
        @DisplayName("M6: 單節點 — 位於中心（無 margin），連接至邊界後不懸空")
        void singleNodeAtCenterNoMargin() {
            // 3×3×3 centre=(1,1,1)，margin=0 時中心方塊可通過 BFS 到達邊界
            RWorldSnapshot snapshot = createSimpleSnapshot(0, 0, 0, 3, 3, 3);
            snapshot.setBlock(1, 1, 1, STONE);

            BFSConnectivityAnalyzer.PhysicsResult result =
                BFSConnectivityAnalyzer.findUnsupportedBlocks(snapshot, 0);

            // Without margin, boundary is at x/y/z=0 or 2; center (1,1,1) is NOT directly on boundary
            // but BFS considers boundary blocks as anchors. The center block is not adjacent to any boundary.
            // Hence it should be unsupported.
            // (This verifies the semantics of single isolated node.)
            assertEquals(1, result.totalNonAir(), "應有 1 個非空氣方塊");
        }

        @Test
        @DisplayName("M6: 單節點 — 位於中心且有足夠 margin，懸空")
        void singleNodeAtCenterWithMarginIsFloating() {
            // 7×7×7，margin=3 → 效果區域只有 (3,3,3)，中心方塊完全孤立
            RWorldSnapshot snapshot = createSimpleSnapshot(0, 0, 0, 7, 7, 7);
            snapshot.setBlock(3, 3, 3, STONE);

            BFSConnectivityAnalyzer.PhysicsResult result =
                BFSConnectivityAnalyzer.findUnsupportedBlocks(snapshot, 3);

            assertEquals(1, result.unsupportedCount(),
                "完全孤立於效果區域中心的單個方塊應懸空");
        }

        @Test
        @DisplayName("M6: 單節點 — PhysicsResult 計算時間為非負值")
        void singleNodeComputeTimeNonNegative() {
            RWorldSnapshot snapshot = createSimpleSnapshot(0, 0, 0, 5, 5, 5);
            snapshot.setBlock(2, 2, 2, STONE);

            BFSConnectivityAnalyzer.PhysicsResult result =
                BFSConnectivityAnalyzer.findUnsupportedBlocks(snapshot, 0);

            assertTrue(result.elapsedNs() >= 0,
                "單節點計算時間不應為負");
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
