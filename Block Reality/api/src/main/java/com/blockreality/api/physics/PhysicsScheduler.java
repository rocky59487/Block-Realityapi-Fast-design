package com.blockreality.api.physics;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 物理排程器 — 管理哪些 island 需要重新計算，並按優先度排序。
 *
 * ★ Phase 7: 優先佇列排程 + 每 tick 預算控制。
 *
 * 優先度公式：
 *   priority = dirtyEpochDelta × blockCount / (distanceToPlayer² + 1)
 *
 * 高優先度：最近被修改、方塊數多、離玩家近的結構。
 *
 * 每 tick 預算：
 *   - 處理 island 直到累計耗時超過 40ms（留 10ms 給其他 tick 任務）
 *   - 未處理完的 island 保留到下一 tick
 *
 * 結果合併：
 *   - 同一 tick 內多次修改同一 island，只排程一次
 */
@javax.annotation.concurrent.ThreadSafe // ConcurrentHashMap dirty tracking
public class PhysicsScheduler {

    private static final Logger LOGGER = LogManager.getLogger("BR-PhysicsScheduler");

    /** 每 tick 物理預算（ms）— 留 30ms 給其他 tick 任務（方塊更新、網路等） */
    private static final long TICK_BUDGET_MS = 20;

    /** 每 tick 最大處理 island 數 — 支援 500³ 結構的島嶼分析 */
    private static final int MAX_ISLANDS_PER_TICK = 12;

    /** 待處理的 dirty island ID 集合（去重用） */
    private static final Set<Integer> dirtyIslandIds = ConcurrentHashMap.newKeySet();

    /** 每個 dirty island 的 epoch（記錄何時變髒） */
    private static final ConcurrentHashMap<Integer, Long> dirtyEpoch = new ConcurrentHashMap<>();

    /**
     * 排程工作項目
     */
    public record ScheduledWork(
        int islandId,
        double priority,
        PhysicsTier tier
    ) {}

    /**
     * 標記 island 為 dirty（需要重新計算）。
     * 從 BlockPhysicsEventHandler 呼叫。
     */
    public static void markDirty(int islandId, long epoch) {
        if (islandId < 0) return;
        dirtyIslandIds.add(islandId);
        dirtyEpoch.merge(islandId, epoch, Math::max); // 保留最大 epoch，防止並發覆蓋較舊值
    }

    /**
     * 取得本 tick 應該處理的工作列表（按優先度排序）。
     *
     * @param players      線上玩家列表
     * @param currentEpoch 當前結構 epoch
     * @return 按優先度排序的工作列表（最多 MAX_ISLANDS_PER_TICK 個）
     */
    public static List<ScheduledWork> getScheduledWork(List<ServerPlayer> players, long currentEpoch) {
        if (dirtyIslandIds.isEmpty()) return List.of();

        PriorityQueue<ScheduledWork> pq = new PriorityQueue<>(
            Comparator.comparingDouble(ScheduledWork::priority).reversed()
        );

        // ★ audit-fix S-1: 收集無效 island ID，迴圈後批次移除
        //   舊版在 enhanced for-loop 內呼叫 remove()，雖然 ConcurrentHashMap 的
        //   weakly-consistent iterator 不會拋 CME，但移除可能被 iterator 忽略，
        //   導致無效 island 殘留直到下次 tick 才清除。
        java.util.ArrayList<Integer> staleIds = new java.util.ArrayList<>();

        for (int islandId : dirtyIslandIds) {
            StructureIslandRegistry.StructureIsland island =
                StructureIslandRegistry.getIsland(islandId);
            if (island == null || island.getBlockCount() == 0) {
                staleIds.add(islandId);
                continue;
            }

            // 計算優先度
            long markedEpoch = dirtyEpoch.getOrDefault(islandId, currentEpoch);
            long epochDelta = Math.max(1, currentEpoch - markedEpoch + 1);
            int blockCount = island.getBlockCount();

            PhysicsTier tier = PhysicsTier.forIsland(
                island.getMinCorner(), island.getMaxCorner(), players);

            if (tier == PhysicsTier.DORMANT) continue; // 休眠 island 不排程

            // ★ audit-fix M-2: 使用 3D 距離（與 PhysicsTier.forIsland 一致）
            double minDistSq = Double.MAX_VALUE;
            double cx = (island.getMinCorner().getX() + island.getMaxCorner().getX()) / 2.0;
            double cy = (island.getMinCorner().getY() + island.getMaxCorner().getY()) / 2.0;
            double cz = (island.getMinCorner().getZ() + island.getMaxCorner().getZ()) / 2.0;
            for (ServerPlayer player : players) {
                double dx = player.getX() - cx;
                double dy = player.getY() - cy;
                double dz = player.getZ() - cz;
                minDistSq = Math.min(minDistSq, dx * dx + dy * dy + dz * dz);
            }

            double priority = epochDelta * blockCount / (minDistSq + 1.0);
            pq.add(new ScheduledWork(islandId, priority, tier));
        }

        // ★ audit-fix S-1: 批次清除無效 island
        for (int staleId : staleIds) {
            dirtyIslandIds.remove(staleId);
            dirtyEpoch.remove(staleId);
        }

        // 取出最高優先的 MAX_ISLANDS_PER_TICK 個
        java.util.ArrayList<ScheduledWork> result = new java.util.ArrayList<>();
        int count = 0;
        while (!pq.isEmpty() && count < MAX_ISLANDS_PER_TICK) {
            result.add(pq.poll());
            count++;
        }
        return result;
    }

    /**
     * 標記 island 已完成處理（從 dirty 集合移除）。
     */
    public static void markProcessed(int islandId) {
        dirtyIslandIds.remove(islandId);
        dirtyEpoch.remove(islandId);
    }

    /**
     * 取得 tick 預算（ms）。
     */
    public static long getTickBudgetMs() {
        return TICK_BUDGET_MS;
    }

    /** 是否有待處理的 dirty island */
    public static boolean hasPendingWork() {
        return !dirtyIslandIds.isEmpty();
    }

    /** 待處理的 dirty island 數量 */
    public static int getPendingCount() {
        return dirtyIslandIds.size();
    }

    /**
     * 清除所有排程（世界卸載時）。
     * ★ BUG-FIX-2: 完全清除所有追蹤的 island，防止記憶體洩漏
     */
    public static void clear() {
        dirtyIslandIds.clear();
        dirtyEpoch.clear();
    }

    /**
     * ★ BUG-FIX-2: 清除無效的 island ID — 逐記時代比較移除過時條目。
     * 當一個 island 被銷毀/合併後，其 ID 應自 dirtyEpoch 中移除。
     * 此方法由伺服器定期呼叫（例如每 100 tick 一次），清除過時的條目。
     *
     * @param currentEpoch 當前結構的 epoch（由 StructureIslandRegistry 提供）
     * @param maxStaleTicks 超過此 tick 數未更新的 island ID 視為過時（預設 1000）
     */
    public static void cleanupStaleEntries(long currentEpoch, long maxStaleTicks) {
        java.util.ArrayList<Integer> staleIds = new java.util.ArrayList<>();
        for (Map.Entry<Integer, Long> entry : dirtyEpoch.entrySet()) {
            long markedEpoch = entry.getValue();
            long ageTicks = currentEpoch - markedEpoch;
            // 超過 maxStaleTicks 沒有更新，或者 island 已不存在
            if (ageTicks > maxStaleTicks ||
                StructureIslandRegistry.getIsland(entry.getKey()) == null) {
                staleIds.add(entry.getKey());
            }
        }
        for (int staleId : staleIds) {
            dirtyIslandIds.remove(staleId);
            dirtyEpoch.remove(staleId);
        }
    }
}
