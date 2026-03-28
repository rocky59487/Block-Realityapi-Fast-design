package com.blockreality.api.collapse;

import com.blockreality.api.block.RBlockEntity;
import com.blockreality.api.event.RStructureCollapseEvent;
import com.blockreality.api.physics.SupportPathAnalyzer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.MinecraftForge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

/**
 * 坍方觸發管理器 — v3fix §3.4
 *
 * 呼叫 SupportPathAnalyzer 判定結構穩定性，
 * 對不穩定方塊觸發坍方（FallingBlockEntity + 粒子效果）。
 *
 * 效能保護：
 *   - 每 tick 最多坍方 MAX_COLLAPSE_PER_TICK 個方塊
 *   - 超過的排入 collapseQueue，下個 tick 繼續處理
 *   - 由 ServerTickEvent 驅動佇列消費（需在外部掛接）
 */
@javax.annotation.concurrent.ThreadSafe  // ★ B-4: ConcurrentLinkedDeque 保證跨 tick 線程安全
public class CollapseManager {

    private static final Logger LOGGER = LogManager.getLogger("BR-Collapse");

    /** 每 tick 最多坍方的方塊數 — 大型結構 (600×600×200) 需要較高值 */
    private static final int MAX_COLLAPSE_PER_TICK = 200;

    /** 佇列大小上限 — 支援超大結構的連鎖坍方 */
    private static final int MAX_QUEUE_SIZE = 100000;

    /**
     * 坍方佇列 — 超過每 tick 上限的方塊排入此佇列。
     * ★ Round 5 fix: 改用 ConcurrentLinkedDeque 以保證跨 tick/event 的線程安全。
     * ArrayDeque 非線程安全，若 checkAndCollapse 從事件線程呼叫而 processQueue 從 tick 線程呼叫，
     * 會有資料競爭風險。
     */
    private static final java.util.concurrent.ConcurrentLinkedDeque<CollapseEntry> collapseQueue =
        new java.util.concurrent.ConcurrentLinkedDeque<>();

    private record CollapseEntry(ServerLevel level, BlockPos pos) {}

    // ═══════════════════════════════════════════════════════
    //  主入口：檢查並觸發坍方
    // ═══════════════════════════════════════════════════════

    /**
     * 以 center 為中心、radius 為半徑，做 Weighted Stress BFS 分析，
     * 將失敗方塊觸發坍方。
     *
     * @param level  世界
     * @param center 分析中心（通常是剛破壞的方塊位置）
     * @param radius 分析半徑
     * @return 觸發坍方的方塊數量
     */
    public static int checkAndCollapse(ServerLevel level, BlockPos center, int radius) {
        SupportPathAnalyzer.AnalysisResult result = SupportPathAnalyzer.analyze(level, center, radius);
        return processCollapseResult(level, center, result);
    }

    /**
     * ★ 島嶼感知版本 — 只分析指定的方塊集合，避免掃描無關方塊。
     *
     * 修復 BUG: 放置方塊時 AABB radius 掃描會涵蓋不屬於該島嶼的 RBlock，
     * 導致無關方塊被誤判為不穩定而坍塌。
     *
     * @param level        世界
     * @param islandBlocks 要分析的方塊位置集合
     * @return 觸發坍方的方塊數量
     */
    public static int checkAndCollapse(ServerLevel level, Set<BlockPos> islandBlocks) {
        if (islandBlocks.isEmpty()) return 0;
        BlockPos center = islandBlocks.iterator().next();
        SupportPathAnalyzer.AnalysisResult result = SupportPathAnalyzer.analyze(level, islandBlocks);
        return processCollapseResult(level, center, result);
    }

    /**
     * 處理分析結果，觸發坍方。（內部共用邏輯）
     */
    private static int processCollapseResult(ServerLevel level, BlockPos center,
                                              SupportPathAnalyzer.AnalysisResult result) {

        if (result.failureCount() == 0) return 0;

        LOGGER.info("[Collapse] Detected {} unstable blocks near {}", result.failureCount(), center);

        // 收集坍方方塊
        Set<BlockPos> collapsingBlocks = new HashSet<>(result.failures().keySet());

        // Post 事件（讓外部模組可以掛接）
        RStructureCollapseEvent event = new RStructureCollapseEvent(level, center, collapsingBlocks);
        MinecraftForge.EVENT_BUS.post(event);

        // 觸發坍方（分批）
        int immediate = 0;
        for (BlockPos pos : collapsingBlocks) {
            if (immediate < MAX_COLLAPSE_PER_TICK) {
                triggerCollapseAt(level, pos);
                immediate++;
            } else {
                if (collapseQueue.size() >= MAX_QUEUE_SIZE) {
                    // ★ #14 fix: 佇列滿時仍執行崩塌（不靜默丟棄），否則方塊永遠懸浮
                    triggerCollapseAt(level, pos);
                    immediate++; // 計入已處理數，讓統計正確
                } else {
                    collapseQueue.add(new CollapseEntry(level, pos));
                }
            }
        }

        if (!collapseQueue.isEmpty()) {
            LOGGER.debug("[Collapse] {} blocks queued for next tick(s)", collapseQueue.size());
        }

        return collapsingBlocks.size();
    }

    // ═══════════════════════════════════════════════════════
    //  佇列消費（由 ServerTickEvent 驅動）
    // ═══════════════════════════════════════════════════════

    /**
     * 每 tick 處理佇列中的坍方方塊。
     * 應在 ServerTickEvent.Post 中呼叫。
     */
    public static void processQueue() {
        if (collapseQueue.isEmpty()) return;

        int processed = 0;
        while (!collapseQueue.isEmpty() && processed < MAX_COLLAPSE_PER_TICK) {
            CollapseEntry entry = collapseQueue.poll();
            triggerCollapseAt(entry.level, entry.pos);
            processed++;
        }

        if (processed > 0) {
            LOGGER.debug("[Collapse] Processed {} queued collapses, {} remaining",
                processed, collapseQueue.size());
        }
    }

    /**
     * 佇列是否有待處理的坍方。
     */
    public static boolean hasPending() {
        return !collapseQueue.isEmpty();
    }

    // ═══════════════════════════════════════════════════════
    //  單一方塊坍方
    // ═══════════════════════════════════════════════════════

    /**
     * 觸發單一方塊坍方：
     *   1. 讀取方塊狀態
     *   2. 清除 BlockEntity 數據
     *   3. 生成 FallingBlockEntity（帶重力掉落）
     *   4. 播放破碎粒子效果
     */
    private static void triggerCollapseAt(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return;

        // ★ 安全閥：只有 RBlock 才會被坍方系統影響
        // 原版方塊（泥土、石頭等）是地形，不應被結構物理坍塌
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof RBlockEntity)) return;

        // 清除 RBlockEntity 數據（避免幽靈 BE）
        level.removeBlockEntity(pos);

        // 生成掉落方塊實體
        FallingBlockEntity.fall(level, pos, state);

        // 破碎粒子效果
        level.sendParticles(
            new BlockParticleOption(ParticleTypes.BLOCK, state),
            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
            15,       // 粒子數量
            0.4, 0.4, 0.4, // 擴散範圍
            0.05      // 速度
        );
    }

    /**
     * 批量排入坍方佇列 — 供 Teardown 式增量檢查使用。
     *
     * 將一組懸浮方塊加入坍方佇列，分批處理（每 tick MAX_COLLAPSE_PER_TICK 個）。
     * 會先 Post RStructureCollapseEvent 讓外部模組掛接。
     *
     * @param level  世界
     * @param blocks 需要坍方的方塊位置集合
     */
    public static void enqueueCollapse(ServerLevel level, Set<BlockPos> blocks) {
        if (blocks.isEmpty()) return;

        // Post 事件
        BlockPos center = blocks.iterator().next();
        RStructureCollapseEvent event = new RStructureCollapseEvent(level, center, new HashSet<>(blocks));
        MinecraftForge.EVENT_BUS.post(event);

        // 排入佇列（檢查佇列大小上限）
        int enqueued = 0;
        int overflowCollapsed = 0;
        for (BlockPos pos : blocks) {
            if (collapseQueue.size() >= MAX_QUEUE_SIZE) {
                // ★ #14 fix: 佇列滿時直接觸發崩塌，避免方塊懸浮
                triggerCollapseAt(level, pos);
                overflowCollapsed++;
            } else {
                collapseQueue.add(new CollapseEntry(level, pos));
                enqueued++;
            }
        }

        LOGGER.info("[Collapse] Batch enqueue: {} queued, {} overflow-collapsed", enqueued, overflowCollapsed);
    }

    /**
     * 清空坍方佇列 — 世界卸載或伺服器關閉時呼叫。
     */
    public static void clearQueue() {
        collapseQueue.clear();
    }
}