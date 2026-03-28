package com.blockreality.api.physics.sparse;

import net.minecraft.core.BlockPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.concurrent.ThreadSafe;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * 空間分割並行執行器 — D-2b
 *
 * 將大型結構劃分為獨立的空間分區（Partition），
 * 使用 ForkJoinPool（Work-Stealing）並行執行物理計算。
 *
 * 設計原則：
 *   - 分區大小 = SVO Section（16³），天然無資料依賴
 *   - 邊界 Section 需要相鄰數據 → 用 margin overlap 處理
 *   - 負載平衡：ForkJoinPool 自動 work-stealing
 *
 * 使用方式：
 *   SpatialPartitionExecutor.execute(svo, section -> {
 *       // 在此 section 上執行物理計算
 *   });
 */
@ThreadSafe
public class SpatialPartitionExecutor {

    private static final Logger LOGGER = LogManager.getLogger("BR/SpatialExec");

    /** 並行執行緒數 — 使用可用核心數，保留 1 核給主執行緒 */
    private static final int PARALLELISM = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);

    /** 共享 ForkJoinPool — Work-Stealing 調度 */
    private static final ForkJoinPool POOL = new ForkJoinPool(
        PARALLELISM,
        ForkJoinPool.defaultForkJoinWorkerThreadFactory,
        (t, e) -> LOGGER.error("[SpatialExec] Uncaught exception in worker thread {}", t.getName(), e),
        true // asyncMode = true → 適合不回傳結果的任務
    );

    /**
     * 分區描述 — 一個 Section 的座標 + 其中的方塊集合
     */
    public record Partition(
        int sectionX, int sectionY, int sectionZ,
        long sectionKey,
        Set<BlockPos> blocks
    ) {}

    /**
     * 對 SVO 中所有非空 Section 並行執行任務。
     *
     * @param svo      稀疏體素資料
     * @param task     對每個 Partition 執行的任務（必須為線程安全）
     * @param timeoutMs 超時毫秒數（0 = 無限等待）
     * @return 執行的分區數量
     */
    public static int execute(SparseVoxelOctree svo, Consumer<Partition> task, long timeoutMs) {
        // Phase 1: 收集所有非空 Section 為 Partition
        List<Partition> partitions = collectPartitions(svo);
        if (partitions.isEmpty()) return 0;

        LOGGER.debug("[SpatialExec] Executing {} partitions on {} threads",
            partitions.size(), PARALLELISM);

        // Phase 2: 提交到 ForkJoinPool
        long t0 = System.nanoTime();
        List<ForkJoinTask<?>> futures = new ArrayList<>(partitions.size());

        for (Partition partition : partitions) {
            futures.add(POOL.submit(() -> {
                try {
                    task.accept(partition);
                } catch (Exception e) {
                    LOGGER.error("[SpatialExec] Error processing section ({},{},{})",
                        partition.sectionX, partition.sectionY, partition.sectionZ, e);
                }
            }));
        }

        // Phase 3: 等待完成
        boolean allDone = true;
        for (ForkJoinTask<?> future : futures) {
            try {
                if (timeoutMs > 0) {
                    future.get(timeoutMs, TimeUnit.MILLISECONDS);
                } else {
                    future.get();
                }
            } catch (TimeoutException e) {
                LOGGER.warn("[SpatialExec] Partition timed out after {}ms", timeoutMs);
                future.cancel(true);
                allDone = false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                allDone = false;
            } catch (ExecutionException e) {
                LOGGER.error("[SpatialExec] Partition execution failed", e.getCause());
            }
        }

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        LOGGER.debug("[SpatialExec] Completed {} partitions in {}ms (success={})",
            partitions.size(), elapsedMs, allDone);

        return partitions.size();
    }

    /**
     * 無超時版本 — 等待所有分區完成。
     */
    public static int execute(SparseVoxelOctree svo, Consumer<Partition> task) {
        return execute(svo, task, 0);
    }

    /**
     * 收集 SVO 中所有非空 Section 為 Partition 列表。
     */
    private static List<Partition> collectPartitions(SparseVoxelOctree svo) {
        List<Partition> partitions = new ArrayList<>();

        svo.forEachSection((sectionKey, section) -> {
            if (section.isEmpty()) return;

            int sx = SparseVoxelOctree.sectionKeyX(sectionKey);
            int sy = SparseVoxelOctree.sectionKeyY(sectionKey);
            int sz = SparseVoxelOctree.sectionKeyZ(sectionKey);

            Set<BlockPos> blocks = new HashSet<>();
            section.forEachNonAir((localX, localY, localZ, state) -> {
                int worldX = (sx << 4) + localX;
                int worldY = (sy << 4) + localY;
                int worldZ = (sz << 4) + localZ;
                blocks.add(new BlockPos(worldX, worldY, worldZ));
            });

            if (!blocks.isEmpty()) {
                partitions.add(new Partition(sx, sy, sz, sectionKey, blocks));
            }
        });

        return partitions;
    }

    /**
     * 取得執行器統計資訊。
     */
    public static String getStats() {
        return String.format("SpatialPartitionExecutor: parallelism=%d, poolSize=%d, active=%d, queued=%d",
            POOL.getParallelism(), POOL.getPoolSize(),
            POOL.getActiveThreadCount(), POOL.getQueuedSubmissionCount());
    }

    /**
     * 關閉執行器（伺服器關閉時呼叫）。
     */
    public static void shutdown() {
        POOL.shutdown();
        try {
            if (!POOL.awaitTermination(5, TimeUnit.SECONDS)) {
                POOL.shutdownNow();
            }
        } catch (InterruptedException e) {
            POOL.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
