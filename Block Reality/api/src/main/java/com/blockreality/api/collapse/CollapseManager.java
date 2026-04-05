package com.blockreality.api.collapse;

import com.blockreality.api.block.RBlockEntity;
import com.blockreality.api.event.RStructureCollapseEvent;
import com.blockreality.api.network.CollapseEffectPacket;
import com.blockreality.api.physics.SupportPathAnalyzer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
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

    /**
     * H6-fix revised: 創造模式崩塌抑制旗標。
     * 當為 true 時，物理分析照常執行但不觸發實際方塊崩塌。
     * 每次 ServerTick 結束時自動重置為 false。
     */
    private static volatile boolean suppressCollapse = false;

    public static void setSuppressCollapse(boolean suppress) {
        suppressCollapse = suppress;
    }

    public static boolean isSuppressCollapse() {
        return suppressCollapse;
    }

    /** 每 tick 最多坍方的方塊數 — 大型結構 (500×500×500) 需要較高值 */
    private static final int MAX_COLLAPSE_PER_TICK = 500;

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

    private record CollapseEntry(ServerLevel level, BlockPos pos, SupportPathAnalyzer.FailureType type) {}

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

        Map<BlockPos, SupportPathAnalyzer.FailureReason> failures = result.failures();
        Set<BlockPos> collapsingBlocks = new HashSet<>(failures.keySet());

        // Post 事件（讓外部模組可以掛接）
        RStructureCollapseEvent event = new RStructureCollapseEvent(level, center, collapsingBlocks);
        MinecraftForge.EVENT_BUS.post(event);

        // ★ review-fix ICReM-5: 收集失敗類型，發送效果封包到客戶端
        Map<BlockPos, com.blockreality.api.network.CollapseEffectPacket.CollapseInfo> effectData =
            new java.util.HashMap<>();

        // 觸發坍方（分批），按失敗類型區分行為
        int immediate = 0;
        for (Map.Entry<BlockPos, SupportPathAnalyzer.FailureReason> entry : failures.entrySet()) {
            BlockPos pos = entry.getKey();
            SupportPathAnalyzer.FailureReason reason = entry.getValue();

            // 收集效果資料
            int materialId = getMaterialId(level, pos);
            effectData.put(pos, new com.blockreality.api.network.CollapseEffectPacket.CollapseInfo(
                reason.type(), materialId));

            if (immediate < MAX_COLLAPSE_PER_TICK) {
                triggerCollapseAt(level, pos, reason.type());
                immediate++;
            } else {
                if (collapseQueue.size() >= MAX_QUEUE_SIZE) {
                    triggerCollapseAt(level, pos, reason.type());
                    immediate++;
                } else {
                    collapseQueue.add(new CollapseEntry(level, pos, reason.type()));
                }
            }
        }

        // ★ review-fix ICReM-5: 廣播崩塌效果封包到附近客戶端
        if (!effectData.isEmpty()) {
            broadcastCollapseEffects(level, center, effectData);
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
            triggerCollapseAt(entry.level, entry.pos, entry.type);
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
     * ★ review-fix ICReM-5: 觸發單一方塊坍方（含失敗類型區分）。
     *
     * 不同失敗類型的行為差異：
     *   CANTILEVER_BREAK: 生成 FallingBlockEntity（整段掉落），較少粒子
     *   CRUSHING:         生成大量碎裂粒子（漸進式壓碎），方塊緩慢消失
     *   NO_SUPPORT:       標準 FallingBlockEntity 掉落
     */
    private static void triggerCollapseAt(ServerLevel level, BlockPos pos,
                                           SupportPathAnalyzer.FailureType type) {
        // H6-fix revised: 創造模式下只記錄但不實際崩塌
        if (suppressCollapse) {
            LOGGER.debug("[Collapse] Suppressed collapse at {} (type={}, creative mode)", pos, type);
            return;
        }

        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return;

        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof RBlockEntity)) return;

        level.removeBlockEntity(pos);

        switch (type) {
            case CANTILEVER_BREAK -> {
                FallingBlockEntity.fall(level, pos, state);
                level.sendParticles(
                    new BlockParticleOption(ParticleTypes.BLOCK, state),
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    8, 0.2, 0.2, 0.2, 0.02
                );
                // Fix 1: 低沉斷裂聲
                level.playSound(null,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    SoundEvents.WOOD_BREAK, SoundSource.BLOCKS, 1.2f, 0.7f);
            }
            case CRUSHING -> {
                level.destroyBlock(pos, false);
                level.sendParticles(
                    new BlockParticleOption(ParticleTypes.BLOCK, state),
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    30, 0.3, 0.3, 0.3, 0.08
                );
                level.sendParticles(
                    new BlockParticleOption(ParticleTypes.BLOCK, state),
                    pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
                    15, 0.4, 0.1, 0.4, 0.03
                );
                // Fix 1: 沉重壓碎雙音效
                level.playSound(null,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    SoundEvents.STONE_BREAK, SoundSource.BLOCKS, 1.5f, 0.5f);
                level.playSound(null,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    SoundEvents.ANVIL_LAND, SoundSource.BLOCKS, 0.6f, 0.4f);
            }
            case TENSION_BREAK -> {
                // Fix 3: 拉力撕裂 — FallingBlockEntity + 水平噴射粒子
                FallingBlockEntity.fall(level, pos, state);
                // 水平噴射粒子（模擬拉扯撕裂）
                level.sendParticles(
                    new BlockParticleOption(ParticleTypes.BLOCK, state),
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    12, 0.5, 0.05, 0.5, 0.06  // Y 擴散極小，X/Z 大 → 水平噴射
                );
                // 高音調拉斷聲
                level.playSound(null,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    SoundEvents.CHAIN_BREAK, SoundSource.BLOCKS, 1.0f, 1.3f);
            }
            case NO_SUPPORT -> {
                FallingBlockEntity.fall(level, pos, state);
                level.sendParticles(
                    new BlockParticleOption(ParticleTypes.BLOCK, state),
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    15, 0.4, 0.4, 0.4, 0.05
                );
                // Fix 1: 掉落聲
                level.playSound(null,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    SoundEvents.STONE_FALL, SoundSource.BLOCKS, 1.0f, 0.8f);
            }
        }
    }

    /** 向後相容：無失敗類型時使用 NO_SUPPORT 預設行為 */
    private static void triggerCollapseAt(ServerLevel level, BlockPos pos) {
        triggerCollapseAt(level, pos, SupportPathAnalyzer.FailureType.NO_SUPPORT);
    }

    /**
     * PFSF 引擎專用入口 — 由 PFSFFailureApplicator 呼叫。
     * 公開方法，委派至內部 triggerCollapseAt。
     *
     * @param level 世界
     * @param pos   斷裂位置
     * @param type  斷裂類型
     */
    public static void triggerPFSFCollapse(ServerLevel level, BlockPos pos,
                                            SupportPathAnalyzer.FailureType type) {
        triggerCollapseAt(level, pos, type);

        // M10-fix: 廣播崩塌效果到附近客戶端（多人同步）
        int matId = getMaterialId(level, pos);
        Map<BlockPos, CollapseEffectPacket.CollapseInfo> data = new java.util.HashMap<>();
        data.put(pos, new CollapseEffectPacket.CollapseInfo(type, matId));
        broadcastCollapseEffects(level, pos, data);
    }

    /**
     * ★ review-fix ICReM-5: 取得方塊的材質 ID（用於客戶端效果顏色）。
     */
    private static int getMaterialId(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof RBlockEntity rbe) {
            return rbe.getBlockType().ordinal();
        }
        return 0;
    }

    /**
     * ★ review-fix ICReM-5: 廣播崩塌效果封包到附近客戶端。
     */
    private static void broadcastCollapseEffects(ServerLevel level, BlockPos center,
                                                  Map<BlockPos, com.blockreality.api.network.CollapseEffectPacket.CollapseInfo> data) {
        com.blockreality.api.network.CollapseEffectPacket packet =
            new com.blockreality.api.network.CollapseEffectPacket(data);
        com.blockreality.api.network.BRNetwork.CHANNEL.send(
            net.minecraftforge.network.PacketDistributor.NEAR.with(
                () -> new net.minecraftforge.network.PacketDistributor.TargetPoint(
                    center.getX(), center.getY(), center.getZ(), 64.0, level.dimension())),
            packet
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
                collapseQueue.add(new CollapseEntry(level, pos, SupportPathAnalyzer.FailureType.NO_SUPPORT));
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