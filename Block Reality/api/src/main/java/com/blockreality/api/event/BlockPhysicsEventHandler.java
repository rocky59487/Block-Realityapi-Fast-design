package com.blockreality.api.event;

import com.blockreality.api.BlockRealityMod;
import com.blockreality.api.block.RBlock;
import com.blockreality.api.block.RBlockEntity;
import com.blockreality.api.collapse.CollapseManager;
import com.blockreality.api.config.BRConfig;
import com.blockreality.api.material.DefaultMaterial;
import com.blockreality.api.material.RMaterial;
import com.blockreality.api.material.VanillaMaterialMap;
import com.blockreality.api.physics.AnchorContinuityChecker;
import com.blockreality.api.physics.ForceEquilibriumSolver;
import com.blockreality.api.physics.LoadPathEngine;
import com.blockreality.api.physics.PhysicsScheduler;
import com.blockreality.api.physics.RCFusionDetector;
import com.blockreality.api.physics.StructureIslandRegistry;
import com.blockreality.api.physics.UnionFindEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Forge 事件監聽器 — 連接 LoadPathEngine + RCFusionDetector。
 *
 * BUG-2 修復說明：
 *   原本 onBlockBreak 用 server.execute() 延遲，但到下個 tick 時
 *   方塊已消失，level.getBlockEntity(pos) 回傳 null。
 *
 *   修復方式：在 event 觸發時（方塊還存在）立即讀取 BE 的關鍵資料
 *   (supportParent, currentLoad)，將這兩個值直接傳給 lambda，
 *   不再從 level 重讀 BE。
 */
@Mod.EventBusSubscriber(modid = BlockRealityMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class BlockPhysicsEventHandler {

    private static final Logger LOGGER = LogManager.getLogger("BR-Events");

    // ─── 放置事件 ───────────────────────────────────────────

    /**
     * RBlock 被放置 → RC 融合偵測 + 建立支撐鏈。
     * 延遲 1 tick 確保 BlockEntity 初始化完成後再操作。
     *
     * v3fix §2.5: 支援 ForceEquilibriumSolver 備選分析。
     */
    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!(event.getPlacedBlock().getBlock() instanceof RBlock)) return;

        final BlockPos pos = event.getPos().immutable();

        // 立即標記快取為髒（事件執行緒即可，ConcurrentHashMap 安全）
        AnchorContinuityChecker.getInstance().markDirty(pos);
        UnionFindEngine.notifyStructureChanged(pos);

        // ★ Phase 1: 登錄到 Island Registry（同步，確保 island 關係立即建立）
        long epoch = UnionFindEngine.getStructureEpoch();
        int islandId = StructureIslandRegistry.registerBlock(pos, epoch);

        // ★ BUG-FIX: markDirty 移入 server.execute()，確保 LoadPathEngine
        // 先建立支撐鏈，再排程物理重算。
        // 舊版在此處同步呼叫 markDirty，導致 ServerTickHandler 在同一 tick-end
        // 處理 dirty island，但 LoadPathEngine 尚未執行（延遲到下一 tick），
        // 新放置的方塊因沒有支撐鏈而被判定為不穩定坍塌。

        level.getServer().execute(() -> {
            // RC 融合偵測（在 BE 初始化後）
            int fusions = RCFusionDetector.checkAndFuse(level, pos);
            if (fusions > 0) {
                LOGGER.debug("[BR-Events] RC fusion at {}: {} pairs fused", pos, fusions);
                // Post FusionCompletedEvent for each fusion
                BlockEntity be = level.getBlockEntity(pos);
                if (be instanceof RBlockEntity rbe) {
                    MinecraftForge.EVENT_BUS.post(new FusionCompletedEvent(level, pos,
                        DefaultMaterial.CONCRETE, rbe.getMaterial()));
                }
            }

            // 建立支撐鏈（必須在 markDirty 之前）
            boolean hasSupport = LoadPathEngine.onBlockPlaced(level, pos);
            if (!hasSupport) {
                LOGGER.debug("[BR-Events] RBlock at {} has no support", pos);
            }

            // ★ Phase 7: 排程物理重算 — 移到支撐鏈建立之後
            // 讀取最新 epoch（可能在延遲期間有變化）
            long currentEpoch = UnionFindEngine.getStructureEpoch();
            // ★ 重新取得 islandId（因為合併可能在延遲期間改變了 island 歸屬）
            int currentIslandId = StructureIslandRegistry.getIslandId(pos);
            if (currentIslandId >= 0) {
                PhysicsScheduler.markDirty(currentIslandId, currentEpoch);
            }

            // ★ v3fix §2.5: Optional ForceEquilibriumSolver analysis (if enabled)
            if (BRConfig.INSTANCE.useForceEquilibrium.get()) {
                performAlternativePhysicsAnalysis(level, pos);
            }
        });
    }

    // ─── 破壞事件 ───────────────────────────────────────────

    /**
     * RBlock 即將被破壞 → 在方塊消失前讀取 BE 資料，延遲觸發級聯崩塌。
     *
     * BUG-2 fix: 在 event 觸發時（BE 仍存在）讀取 supportParent 和 currentLoad，
     * 然後將這些值傳入 execute() 的 lambda，不依賴 BE 在下一 tick 仍存在。
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        final BlockPos pos = event.getPos().immutable();
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof RBlockEntity rbe)) return;

        // ★ 在 BE 還存在時，立即讀取關鍵資料（原子化讀取避免競態）
        BlockPos tmpParent = rbe.getSupportParent();
        final BlockPos cachedParent = tmpParent != null ? tmpParent.immutable() : null;
        final float cachedLoad = rbe.getCurrentLoad();
        // ★ W-5: 快取 BlockType 供降級檢查
        final com.blockreality.api.material.BlockType cachedBlockType = rbe.getBlockType();

        // 立即標記快取為髒
        AnchorContinuityChecker.getInstance().markDirty(pos);
        UnionFindEngine.notifyStructureChanged(pos);

        // ★ Phase 1: 從 Island Registry 註銷（在方塊消失前處理）
        long epoch = UnionFindEngine.getStructureEpoch();
        int islandId = StructureIslandRegistry.getIslandId(pos);

        // 延遲到方塊實際消失後執行崩塌（用快取資料，不讀 BE）
        level.getServer().execute(() -> {
            // ★ Phase 1: 延遲執行 island 分裂檢查（方塊已消失）
            StructureIslandRegistry.unregisterBlock(level, pos, epoch);
            // ★ Phase 7: 排程物理重算（原 island 及可能的分裂 island）
            PhysicsScheduler.markDirty(islandId, epoch);
            // ★ W-5: RC 融合降級檢查（破壞鋼筋/混凝土時，鄰居 RC_NODE 降級）
            int downgrades = RCFusionDetector.checkAndDowngrade(level, pos, cachedBlockType);
            if (downgrades > 0) {
                LOGGER.info("[BR-Events] Break at {} caused {} RC_NODE downgrades", pos, downgrades);
            }

            // ★ W-2 fix: 只用 LoadPathEngine 做即時級聯崩塌。
            int collapsed = LoadPathEngine.onBlockBrokenCached(level, pos, cachedParent, cachedLoad);
            if (collapsed > 0) {
                LOGGER.info("[BR-Events] Break at {} caused {} blocks to collapse", pos, collapsed);
            }

            // ★ Teardown 式增量完整性檢查：
            // LoadPathEngine 只處理 support tree 的直接子節點，
            // Teardown BFS 捕捉任何失去錨定連接的連通分量。
            java.util.Set<BlockPos> floatingBlocks = UnionFindEngine.validateLocalIntegrity(level, pos);
            if (!floatingBlocks.isEmpty()) {
                LOGGER.info("[BR-Events] Teardown check at {}: {} additional floating blocks detected",
                    pos, floatingBlocks.size());
                CollapseManager.enqueueCollapse(level, floatingBlocks);
            }

            // ★ v3fix §2.5: Optional ForceEquilibriumSolver analysis (if enabled)
            if (BRConfig.INSTANCE.useForceEquilibrium.get()) {
                performAlternativePhysicsAnalysis(level, pos);
            }
        });
    }

    // ═══════════════════════════════════════════════════════
    //  Alternative Physics Analysis (ForceEquilibriumSolver)
    // ═══════════════════════════════════════════════════════

    /**
     * Perform alternative physics analysis using ForceEquilibriumSolver.
     * v3fix §2.5: Run as supplement to LoadPathEngine when enabled.
     *
     * Captures a neighborhood snapshot and analyzes force equilibrium
     * to validate or supplement the primary load path analysis.
     *
     * @param level The server level
     * @param center The center block position
     */
    private static void performAlternativePhysicsAnalysis(ServerLevel level, BlockPos center) {
        try {
            final int radius = 2;
            java.util.Set<BlockPos> blockPositions = new java.util.HashSet<>();
            java.util.Map<BlockPos, RMaterial> materials = new java.util.HashMap<>();
            java.util.Set<BlockPos> anchors = new java.util.HashSet<>();
            // ★ audit-fix C-4: 收集雕刻形狀的截面積
            java.util.Map<BlockPos, Float> effectiveAreas = new java.util.HashMap<>();

            BlockPos start = center.offset(-radius, -radius, -radius);
            BlockPos end = center.offset(radius, radius, radius);

            // ★ 直接遍歷鄰域，從 RBlockEntity 或 VanillaMaterialMap 取得材料
            for (int x = start.getX(); x <= end.getX(); x++) {
                for (int y = start.getY(); y <= end.getY(); y++) {
                    for (int z = start.getZ(); z <= end.getZ(); z++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        BlockState state = level.getBlockState(pos);
                        if (state.isAir()) continue;

                        blockPositions.add(pos);

                        // 材料查找：RBlockEntity 優先，fallback 到 VanillaMaterialMap
                        BlockEntity be = level.getBlockEntity(pos);
                        if (be instanceof RBlockEntity rbe) {
                            materials.put(pos, rbe.getMaterial());
                            if (rbe.isAnchored()) {
                                anchors.add(pos);
                            }
                            // ★ audit-fix C-4: 傳遞雕刻截面積到求解器
                            com.blockreality.api.chisel.ChiselState cs = rbe.getChiselState();
                            if (!cs.isFull()) {
                                effectiveAreas.put(pos, (float) cs.crossSectionArea());
                            }
                        } else {
                            // 原版方塊 → VanillaMaterialMap
                            String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                            materials.put(pos, VanillaMaterialMap.getInstance().getMaterial(blockId));
                            // 錨定判定：基岩、屏障
                            if (state.is(Blocks.BEDROCK) || state.is(Blocks.BARRIER)) {
                                anchors.add(pos);
                            }
                        }
                    }
                }
            }

            // 執行力平衡求解
            if (!blockPositions.isEmpty()) {
                // ★ audit-fix C-4: 傳入截面積數據
                java.util.Map<BlockPos, ForceEquilibriumSolver.ForceResult> results =
                    ForceEquilibriumSolver.solve(blockPositions, materials, anchors, effectiveAreas);

                // 統計不穩定方塊並記錄 + 觸發 StressUpdateEvent
                long unstableCount = 0;
                for (java.util.Map.Entry<BlockPos, ForceEquilibriumSolver.ForceResult> re : results.entrySet()) {
                    ForceEquilibriumSolver.ForceResult fr = re.getValue();
                    if (!fr.isStable()) {
                        unstableCount++;
                        // ★ v4-fix: ForceEquilibrium 路徑也觸發 StressUpdateEvent
                        MinecraftForge.EVENT_BUS.post(
                            new StressUpdateEvent(level, re.getKey(), 0.0f, (float) fr.utilizationRatio()));
                    }
                }
                if (unstableCount > 0) {
                    LOGGER.info("[BR-Events] ForceEquilibrium: {} unstable of {} blocks near {}",
                        unstableCount, results.size(), center);
                } else {
                    LOGGER.debug("[BR-Events] ForceEquilibrium: all {} blocks stable near {}",
                        results.size(), center);
                }
            }
        } catch (RuntimeException e) {
            LOGGER.warn("[BR-Events] Error in alternative physics analysis at {}: {}",
                center, e.getMessage());
        }
    }
}
