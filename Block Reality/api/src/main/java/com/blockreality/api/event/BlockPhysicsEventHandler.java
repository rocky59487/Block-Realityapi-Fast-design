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
import com.blockreality.api.physics.ConnectivityCache;
import com.blockreality.api.physics.RCFusionDetector;
import com.blockreality.api.physics.StructureIslandRegistry;
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
 * Forge 事件監聽器 — 連接 StructureIslandRegistry + RCFusionDetector。
 *
 * 結構完整性由 PFSF GPU 物理引擎在 ServerTickHandler 中處理。
 * 此處僅負責：
 *   1. Island Registry 登錄/註銷
 *   2. RC 融合偵測/降級
 *   3. ConnectivityCache epoch 更新
 */
@Mod.EventBusSubscriber(modid = BlockRealityMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class BlockPhysicsEventHandler {

    private static final Logger LOGGER = LogManager.getLogger("BR-Events");

    // ─── 放置事件 ───────────────────────────────────────────

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!(event.getPlacedBlock().getBlock() instanceof RBlock)) return;

        // H6-fix revised: 創造模式仍執行物理計算，但抑制崩塌
        final boolean creativeMode = event.getEntity() instanceof net.minecraft.world.entity.player.Player player
                && player.isCreative();
        if (creativeMode) {
            CollapseManager.setSuppressCollapse(true);
        }

        final BlockPos pos = event.getPos().immutable();

        // ★ Phase 1: 登錄到 Island Registry（同步）
        long epoch = ConnectivityCache.getStructureEpoch();
        int islandId = StructureIslandRegistry.registerBlock(pos, epoch);

        level.getServer().execute(() -> {
            // 標記快取為髒
            AnchorContinuityChecker.getInstance().markDirty(pos);
            ConnectivityCache.notifyStructureChanged(pos);

            // 驗證方塊確實存在（可能被低優先級事件取消放置）
            BlockState placedState = level.getBlockState(pos);
            if (!(placedState.getBlock() instanceof RBlock)) {
                StructureIslandRegistry.unregisterBlock(level, pos, epoch);
                LOGGER.debug("[BR-Events] Block placement at {} was cancelled, rolled back island registration", pos);
                return;
            }

            // RC 融合偵測
            int fusions = RCFusionDetector.checkAndFuse(level, pos);
            if (fusions > 0) {
                LOGGER.debug("[BR-Events] RC fusion at {}: {} pairs fused", pos, fusions);
                BlockEntity be = level.getBlockEntity(pos);
                if (be instanceof RBlockEntity rbe) {
                    MinecraftForge.EVENT_BUS.post(new FusionCompletedEvent(level, pos,
                        DefaultMaterial.CONCRETE, rbe.getMaterial()));
                }
            }

            // PFSF 透過 onServerTick 自動檢測結構變化
        });
    }

    // ─── 破壞事件 ───────────────────────────────────────────

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        // H6-fix revised: 創造模式仍執行物理，但抑制崩塌
        final boolean creativeMode = event.getPlayer() != null && event.getPlayer().isCreative();
        if (creativeMode) {
            CollapseManager.setSuppressCollapse(true);
        }

        final BlockPos pos = event.getPos().immutable();
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof RBlockEntity rbe)) return;

        // 快取 BlockType 供降級檢查
        final com.blockreality.api.material.BlockType cachedBlockType = rbe.getBlockType();

        // Phase 1: 取得 epoch（在方塊消失前）
        long epoch = ConnectivityCache.getStructureEpoch();

        // 延遲到方塊實際消失後執行
        level.getServer().execute(() -> {
            // 標記快取為髒
            AnchorContinuityChecker.getInstance().markDirty(pos);
            ConnectivityCache.notifyStructureChanged(pos);

            // 從 Island Registry 註銷
            StructureIslandRegistry.unregisterBlock(level, pos, epoch);

            // RC 融合降級檢查
            int downgrades = RCFusionDetector.checkAndDowngrade(level, pos, cachedBlockType);
            if (downgrades > 0) {
                LOGGER.info("[BR-Events] Break at {} caused {} RC_NODE downgrades", pos, downgrades);
            }

            // 結構完整性由 PFSF GPU 引擎在 ServerTickHandler 中自動檢測
        });
    }
}
