package com.blockreality.api.collapse;

import com.blockreality.api.config.BRConfig;
import com.blockreality.api.physics.FailureType;

import com.blockreality.api.block.RBlockEntity;
import com.blockreality.api.event.RStructureCollapseEvent;
import com.blockreality.api.network.CollapseEffectPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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
    private static final java.util.concurrent.atomic.AtomicBoolean suppressCollapse =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    public static void setSuppressCollapse(boolean suppress) {
        suppressCollapse.set(suppress);
    }

    public static boolean isSuppressCollapse() {
        return suppressCollapse.get();
    }

    /** 每 tick 最多坍方的方塊數 — 由 BRConfig.getMaxCollapsePerTick() 動態讀取 (P2-A) */
    // 原本是 private static final int MAX_COLLAPSE_PER_TICK = 500;

    // 連鎖崩塌最大深度 — 由 BRConfig.getCollapseCascadeMaxDepth() 動態讀取
    // 佇列大小上限 — 由 BRConfig.getCollapseQueueMaxSize() 動態讀取

    /**
     * 坍方佇列 — 超過每 tick 上限的方塊排入此佇列。
     * ★ Round 5 fix: 改用 ConcurrentLinkedDeque 以保證跨 tick/event 的線程安全。
     * ArrayDeque 非線程安全，若 checkAndCollapse 從事件線程呼叫而 processQueue 從 tick 線程呼叫，
     * 會有資料競爭風險。
     */
    private static final java.util.concurrent.ConcurrentLinkedDeque<CollapseEntry> collapseQueue =
        new java.util.concurrent.ConcurrentLinkedDeque<>();

    /** ★ Audit fix: 溢出暫存 — 佇列滿時暫存方塊，processQueue 消化後回填，避免永久遺失。 */
    private static final java.util.concurrent.ConcurrentLinkedDeque<CollapseEntry> overflowBuffer =
        new java.util.concurrent.ConcurrentLinkedDeque<>();

    private record CollapseEntry(ServerLevel level, BlockPos pos, FailureType type) {}

    // ═══════════════════════════════════════════════════════
    //  主入口：PFSF GPU 引擎觸發坍方（見 triggerPFSFCollapse）
    // ═══════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════
    //  佇列消費（由 ServerTickEvent 驅動）
    // ═══════════════════════════════════════════════════════

    /**
     * 每 tick 處理佇列中的坍方方塊。
     * 應在 ServerTickEvent.Post 中呼叫。
     */
    public static void processQueue() {
        // ★ Audit fix: 回填溢出暫存，確保無方塊永久遺失
        if (!overflowBuffer.isEmpty() && collapseQueue.size() < BRConfig.getCollapseQueueMaxSize()) {
            int refilled = 0;
            while (!overflowBuffer.isEmpty() && collapseQueue.size() < BRConfig.getCollapseQueueMaxSize()) {
                collapseQueue.add(overflowBuffer.poll());
                refilled++;
            }
            if (refilled > 0) {
                LOGGER.debug("[Collapse] Refilled {} entries from overflow, {} still pending",
                    refilled, overflowBuffer.size());
            }
        }

        if (collapseQueue.isEmpty()) return;

        int processed = 0;
        while (!collapseQueue.isEmpty() && processed < BRConfig.getMaxCollapsePerTick()) {
            CollapseEntry entry = collapseQueue.poll();
            triggerCollapseAt(entry.level, entry.pos, entry.type);
            processed++;
        }

        if (processed > 0) {
            LOGGER.debug("[Collapse] Processed {} queued collapses, {} remaining, {} overflow",
                processed, collapseQueue.size(), overflowBuffer.size());
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
                                           FailureType type) {
        // H6-fix revised: 創造模式下只記錄但不實際崩塌
        if (suppressCollapse.get()) {
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
            case HYDROSTATIC_PRESSURE -> {
                // ★ PFSF-Fluid: 靜水壓突破 — 方塊被水壓沖垮
                level.destroyBlock(pos, false);
                // 水花粒子效果
                level.sendParticles(
                    new BlockParticleOption(ParticleTypes.BLOCK, state),
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    20, 0.3, 0.3, 0.3, 0.06
                );
                level.sendParticles(
                    ParticleTypes.SPLASH,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    10, 0.5, 0.5, 0.5, 0.1
                );
                // 水壓突破音效
                level.playSound(null,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    SoundEvents.GENERIC_SPLASH, SoundSource.BLOCKS, 1.5f, 0.6f);
                level.playSound(null,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    SoundEvents.STONE_BREAK, SoundSource.BLOCKS, 1.0f, 0.5f);

                // Post FluidBarrierBreachEvent 讓流體引擎開放此體素
                MinecraftForge.EVENT_BUS.post(
                    new com.blockreality.api.event.FluidBarrierBreachEvent(
                        level, java.util.Set.of(pos)));
            }
            case TORSION_BREAK -> {
                // 扭轉斷裂 — FallingBlockEntity + 螺旋粒子 + 鏈斷音效
                FallingBlockEntity.fall(level, pos, state);
                // 螺旋粒子效果（模擬扭轉撕裂）
                for (int i = 0; i < 12; i++) {
                    double angle = i * Math.PI / 6.0;
                    double dx = Math.cos(angle) * 0.4;
                    double dz = Math.sin(angle) * 0.4;
                    level.sendParticles(
                        new BlockParticleOption(ParticleTypes.BLOCK, state),
                        pos.getX() + 0.5 + dx, pos.getY() + 0.5 + (i * 0.05),
                        pos.getZ() + 0.5 + dz,
                        2, 0.1, 0.1, 0.1, 0.03
                    );
                }
                level.playSound(null,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    SoundEvents.CHAIN_BREAK, SoundSource.BLOCKS, 1.3f, 0.6f);
            }
            case FATIGUE_CRACK -> {
                // 疲勞裂紋 — 方塊破壞 + 少量裂紋粒子 + 安靜石頭斷裂聲
                level.destroyBlock(pos, false);
                level.sendParticles(
                    new BlockParticleOption(ParticleTypes.BLOCK, state),
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    6, 0.15, 0.15, 0.15, 0.01
                );
                // 安靜的石頭斷裂聲（低音量，不如壓碎那樣戲劇化）
                level.playSound(null,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    SoundEvents.STONE_BREAK, SoundSource.BLOCKS, 0.5f, 1.2f);
            }
        }

        // ★ 連鎖崩塌偵測：檢查相鄰方塊是否失去支撐
        checkCascade(level, pos, 0);
    }

    // ═══════════════════════════════════════════════════════
    //  連鎖崩塌偵測
    // ═══════════════════════════════════════════════════════

    /**
     * 連鎖崩塌偵測 — 方塊崩塌後檢查 6 個相鄰方塊是否失去支撐。
     *
     * 失去所有支撐的鄰居以 1-tick 延遲排入佇列（視覺效果較佳）。
     * 最大深度限制 BRConfig.getCollapseCascadeMaxDepth() 防止無限遞迴。
     *
     * @param level       世界
     * @param collapsedPos 剛崩塌的方塊位置
     * @param depth       目前遞迴深度
     */
    private static void checkCascade(ServerLevel level, BlockPos collapsedPos, int depth) {
        if (depth >= BRConfig.getCollapseCascadeMaxDepth()) {
            LOGGER.debug("[Collapse] Cascade depth limit reached at {}", collapsedPos);
            return;
        }
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = collapsedPos.relative(dir);
            BlockState neighborState = level.getBlockState(neighbor);
            if (neighborState.isAir()) continue;

            // 只對 RBlockEntity 方塊進行連鎖檢查
            BlockEntity be = level.getBlockEntity(neighbor);
            if (!(be instanceof RBlockEntity)) continue;

            if (!hasAnySupport(level, neighbor)) {
                // 排入佇列（1-tick 延遲由 processQueue 自然提供）
                enqueueCollapse(level, neighbor, FailureType.NO_SUPPORT);
            }
        }
    }

    /**
     * 判斷方塊是否仍有任何支撐。
     *
     * 支撐定義：下方或水平方向有至少一個非空氣方塊。
     * 上方不計為支撐（重力方向）。
     *
     * @param level 世界
     * @param pos   待檢查位置
     * @return true 若仍有支撐
     */
    private static boolean hasAnySupport(ServerLevel level, BlockPos pos) {
        // 下方方塊是最主要的支撐
        BlockPos below = pos.below();
        if (!level.getBlockState(below).isAir()) return true;
        // 水平鄰居提供側向支撐
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos side = pos.relative(dir);
            if (!level.getBlockState(side).isAir()) return true;
        }
        return false;
    }

    /**
     * 排入單一方塊崩塌（供連鎖偵測使用）。
     *
     * @param level 世界
     * @param pos   崩塌位置
     * @param type  失效類型
     */
    private static void enqueueCollapse(ServerLevel level, BlockPos pos, FailureType type) {
        if (collapseQueue.size() >= BRConfig.getCollapseQueueMaxSize()) {
            overflowBuffer.add(new CollapseEntry(level, pos, type));
        } else {
            collapseQueue.add(new CollapseEntry(level, pos, type));
        }
    }

    /** 向後相容：無失敗類型時使用 NO_SUPPORT 預設行為 */
    private static void triggerCollapseAt(ServerLevel level, BlockPos pos) {
        triggerCollapseAt(level, pos, FailureType.NO_SUPPORT);
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
                                            FailureType type) {
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
        // ★ P2-fix: 佇列滿時不再同步觸發 triggerCollapseAt（會瞬間生成大量 FallingBlockEntity
        //   導致 TPS 崩潰），改為丟棄溢出部分並延遲到下一 tick 批次處理。
        int enqueued = 0;
        int deferred = 0;
        for (BlockPos pos : blocks) {
            if (collapseQueue.size() >= BRConfig.getCollapseQueueMaxSize()) {
                // ★ Audit fix: 放入溢出暫存而非丟棄
                overflowBuffer.add(new CollapseEntry(level, pos, FailureType.NO_SUPPORT));
                deferred++;
            } else {
                collapseQueue.add(new CollapseEntry(level, pos, FailureType.NO_SUPPORT));
                enqueued++;
            }
        }

        if (deferred > 0) {
            LOGGER.warn("[Collapse] Queue full ({}), {} blocks to overflow buffer (will retry)",
                BRConfig.getCollapseQueueMaxSize(), deferred);
        }
        LOGGER.info("[Collapse] Batch enqueue: {} queued, {} deferred", enqueued, deferred);
    }

    /**
     * Batch-enqueue collapse with an explicit {@link FailureType}.
     * Used by PFSFEngineInstance for overturning collapses so the failure
     * type is preserved through the queue and can be read by downstream
     * systems (e.g. statistics, journal).
     *
     * @param level  server level
     * @param blocks blocks to collapse
     * @param type   explicit failure type (e.g. {@link FailureType#OVERTURNING})
     */
    public static void enqueueCollapse(ServerLevel level, Set<BlockPos> blocks, FailureType type) {
        if (blocks.isEmpty()) return;

        BlockPos center = blocks.iterator().next();
        RStructureCollapseEvent event = new RStructureCollapseEvent(level, center, new HashSet<>(blocks));
        MinecraftForge.EVENT_BUS.post(event);

        int enqueued = 0, deferred = 0;
        for (BlockPos pos : blocks) {
            if (collapseQueue.size() >= BRConfig.getCollapseQueueMaxSize()) {
                overflowBuffer.add(new CollapseEntry(level, pos, type));
                deferred++;
            } else {
                collapseQueue.add(new CollapseEntry(level, pos, type));
                enqueued++;
            }
        }

        if (deferred > 0) {
            LOGGER.warn("[Collapse] Queue full ({}), {} blocks ({}) to overflow buffer",
                BRConfig.getCollapseQueueMaxSize(), deferred, type);
        }
        LOGGER.info("[Collapse] Batch enqueue ({}): {} queued, {} deferred", type, enqueued, deferred);
    }

    /**
     * 清空坍方佇列 — 世界卸載或伺服器關閉時呼叫。
     */
    public static void clearQueue() {
        collapseQueue.clear();
        overflowBuffer.clear(); // ★ Audit fix: prevent overflow leak on world unload
    }
}