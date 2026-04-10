package com.blockreality.api.physics;

import com.blockreality.api.material.DefaultMaterial;
import com.blockreality.api.material.RMaterial;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 性能優化模組的整合測試。
 *
 * 測試涵蓋：
 *   1. Delta fingerprint 正確性
 *   2. PhysicsTier 層級判定
 *   3. PhysicsScheduler 排程邏輯
 *   4. RWorldSnapshot 可配置上限
 *   5. PhysicsExecutor 啟動/關閉
 */
@DisplayName("Physics Performance Optimization Tests")
class PhysicsPerformanceTest {

    @BeforeEach
    void setUp() {
        StructureIslandRegistry.clear();
        PhysicsScheduler.clear();
    }

    @AfterEach
    void tearDown() {
        StructureIslandRegistry.clear();
        PhysicsScheduler.clear();
    }

    // ═══════════════════════════════════════════════════
    // 1. Delta Fingerprint
    // ═══════════════════════════════════════════════════

    @Nested
    @DisplayName("Delta Fingerprint")
    class DeltaFingerprint {

        @Test
        @DisplayName("blockFingerprint is deterministic")
        void blockFingerprintIsDeterministic() {
            BlockPos pos = new BlockPos(10, 20, 30);
            RMaterial mat = DefaultMaterial.CONCRETE;
            long fp1 = ForceEquilibriumSolver.blockFingerprint(pos, mat);
            long fp2 = ForceEquilibriumSolver.blockFingerprint(pos, mat);
            assertEquals(fp1, fp2, "Same pos + material should produce same fingerprint");
        }

        @Test
        @DisplayName("Different positions produce different fingerprints")
        void differentPositionsDifferentFingerprints() {
            RMaterial mat = DefaultMaterial.CONCRETE;
            long fp1 = ForceEquilibriumSolver.blockFingerprint(new BlockPos(0, 0, 0), mat);
            long fp2 = ForceEquilibriumSolver.blockFingerprint(new BlockPos(1, 0, 0), mat);
            assertNotEquals(fp1, fp2, "Different positions should produce different fingerprints");
        }

        @Test
        @DisplayName("Different materials produce different fingerprints")
        void differentMaterialsDifferentFingerprints() {
            BlockPos pos = new BlockPos(0, 0, 0);
            long fp1 = ForceEquilibriumSolver.blockFingerprint(pos, DefaultMaterial.CONCRETE);
            long fp2 = ForceEquilibriumSolver.blockFingerprint(pos, DefaultMaterial.STEEL);
            assertNotEquals(fp1, fp2, "Different materials should produce different fingerprints");
        }

        @Test
        @DisplayName("Null material produces consistent fingerprint")
        void nullMaterialProducesConsistentFingerprint() {
            BlockPos pos = new BlockPos(5, 5, 5);
            long fp1 = ForceEquilibriumSolver.blockFingerprint(pos, null);
            long fp2 = ForceEquilibriumSolver.blockFingerprint(pos, null);
            assertEquals(fp1, fp2);
        }

        // ★ audit-fix C-2: deltaFingerprint tests removed — method was removed
        // because XOR delta is not equivalent to FNV-1a chain fingerprint.
    }

    // ═══════════════════════════════════════════════════
    // 2. PhysicsTier
    // ═══════════════════════════════════════════════════

    @Nested
    @DisplayName("PhysicsTier")
    class PhysicsTierTests {

        @Test
        @DisplayName("FULL has lowest level (0)")
        void fullHasLowestLevel() {
            assertEquals(0, PhysicsTier.FULL.getLevel());
        }

        @Test
        @DisplayName("DORMANT has highest level (3)")
        void dormantHasHighestLevel() {
            assertEquals(3, PhysicsTier.DORMANT.getLevel());
        }

        @Test
        @DisplayName("Tier ordering: FULL < STANDARD < COARSE < DORMANT")
        void tierOrdering() {
            assertTrue(PhysicsTier.FULL.getLevel() < PhysicsTier.STANDARD.getLevel());
            assertTrue(PhysicsTier.STANDARD.getLevel() < PhysicsTier.COARSE.getLevel());
            assertTrue(PhysicsTier.COARSE.getLevel() < PhysicsTier.DORMANT.getLevel());
        }

        @Test
        @DisplayName("Empty player list returns DORMANT")
        void emptyPlayerListReturnsDormant() {
            PhysicsTier tier = PhysicsTier.forIsland(
                new BlockPos(0, 0, 0), new BlockPos(10, 10, 10),
                java.util.List.of()
            );
            assertEquals(PhysicsTier.DORMANT, tier);
        }
    }

    // ═══════════════════════════════════════════════════
    // 3. PhysicsScheduler
    // ═══════════════════════════════════════════════════

    @Nested
    @DisplayName("PhysicsScheduler")
    class SchedulerTests {

        @Test
        @DisplayName("Initially no pending work")
        void initiallyNoPendingWork() {
            assertFalse(PhysicsScheduler.hasPendingWork());
            assertEquals(0, PhysicsScheduler.getPendingCount());
        }

        @Test
        @DisplayName("markDirty adds to pending")
        void markDirtyAddsToPending() {
            PhysicsScheduler.markDirty(1, 100L);
            assertTrue(PhysicsScheduler.hasPendingWork());
            assertEquals(1, PhysicsScheduler.getPendingCount());
        }

        @Test
        @DisplayName("markProcessed removes from pending")
        void markProcessedRemovesFromPending() {
            PhysicsScheduler.markDirty(1, 100L);
            PhysicsScheduler.markProcessed(1);
            assertFalse(PhysicsScheduler.hasPendingWork());
        }

        @Test
        @DisplayName("Duplicate markDirty is idempotent")
        void duplicateMarkDirtyIsIdempotent() {
            PhysicsScheduler.markDirty(1, 100L);
            PhysicsScheduler.markDirty(1, 101L);
            assertEquals(1, PhysicsScheduler.getPendingCount());
        }

        @Test
        @DisplayName("clear removes all pending work")
        void clearRemovesAllPending() {
            PhysicsScheduler.markDirty(1, 100L);
            PhysicsScheduler.markDirty(2, 101L);
            PhysicsScheduler.markDirty(3, 102L);
            PhysicsScheduler.clear();
            assertFalse(PhysicsScheduler.hasPendingWork());
        }

        @Test
        @DisplayName("Negative island ID is ignored")
        void negativeIslandIdIsIgnored() {
            PhysicsScheduler.markDirty(-1, 100L);
            assertFalse(PhysicsScheduler.hasPendingWork());
        }

        @Test
        @DisplayName("getScheduledWork with no dirty islands returns empty")
        void getScheduledWorkWithNoDirtyReturnsEmpty() {
            var work = PhysicsScheduler.getScheduledWork(java.util.List.of(), 100L);
            assertTrue(work.isEmpty());
        }

        @Test
        @DisplayName("getTickBudgetMs returns positive value")
        void getTickBudgetMsReturnsPositive() {
            assertTrue(PhysicsScheduler.getTickBudgetMs() > 0);
        }
    }

    // ═══════════════════════════════════════════════════
    // 4. RWorldSnapshot configurable max
    // ═══════════════════════════════════════════════════

    @Nested
    @DisplayName("RWorldSnapshot Configurable Max")
    class SnapshotMaxTests {

        @Test
        @DisplayName("Default max is 65536")
        void defaultMaxIs65536() {
            assertEquals(65536, RWorldSnapshot.DEFAULT_MAX_SNAPSHOT_BLOCKS);
        }

        @Test
        @DisplayName("setMaxSnapshotBlocks raises limit")
        void setMaxSnapshotBlocksRaisesLimit() {
            int original = RWorldSnapshot.getMaxSnapshotBlocks();
            try {
                RWorldSnapshot.setMaxSnapshotBlocks(262144);
                assertEquals(262144, RWorldSnapshot.getMaxSnapshotBlocks());
            } finally {
                RWorldSnapshot.setMaxSnapshotBlocks(original);
            }
        }

        @Test
        @DisplayName("setMaxSnapshotBlocks cannot go below default")
        void cannotGoBelowDefault() {
            int original = RWorldSnapshot.getMaxSnapshotBlocks();
            try {
                RWorldSnapshot.setMaxSnapshotBlocks(100); // below default
                assertTrue(RWorldSnapshot.getMaxSnapshotBlocks() >= RWorldSnapshot.DEFAULT_MAX_SNAPSHOT_BLOCKS,
                    "Max should not go below default");
            } finally {
                RWorldSnapshot.setMaxSnapshotBlocks(original);
            }
        }
    }

    // ═══════════════════════════════════════════════════
    // 5. PhysicsExecutor
    // ═══════════════════════════════════════════════════

    @Nested
    @DisplayName("PhysicsExecutor")
    class ExecutorTests {

        @Test
        @DisplayName("start and shutdown lifecycle")
        void startAndShutdownLifecycle() {
            PhysicsExecutor.start(1);
            assertTrue(PhysicsExecutor.isRunning());
            assertEquals(1, PhysicsExecutor.getConfiguredThreadCount());
            PhysicsExecutor.shutdown();
            // After shutdown, isRunning may still be true briefly,
            // but subsequent submit should fail
        }

        @Test
        @DisplayName("start with auto thread count")
        void startWithAutoThreadCount() {
            PhysicsExecutor.start(0); // auto
            assertTrue(PhysicsExecutor.isRunning());
            assertTrue(PhysicsExecutor.getConfiguredThreadCount() >= 1,
                "Should have at least 1 thread");
            PhysicsExecutor.shutdown();
        }

        @Test
        @DisplayName("submitTask returns CompletableFuture")
        void submitTaskReturnsCompletableFuture() {
            PhysicsExecutor.start(1);
            try {
                var future = PhysicsExecutor.submitTask(() -> 42);
                assertNotNull(future);
                assertEquals(42, future.join());
            } finally {
                PhysicsExecutor.shutdown();
            }
        }
    }

    // ═══════════════════════════════════════════════════
    // 6. Island + Scheduler Integration
    // ═══════════════════════════════════════════════════

    @Test
    @DisplayName("Register block and mark dirty: full workflow")
    void registerBlockAndMarkDirtyWorkflow() {
        BlockPos pos = new BlockPos(0, 0, 0);
        long epoch = 1;

        int islandId = StructureIslandRegistry.registerBlock(pos, epoch);
        PhysicsScheduler.markDirty(islandId, epoch);

        assertTrue(islandId > 0);
        assertTrue(PhysicsScheduler.hasPendingWork());
        assertEquals(1, StructureIslandRegistry.getIslandCount());

        PhysicsScheduler.markProcessed(islandId);
        assertFalse(PhysicsScheduler.hasPendingWork());
    }
}
