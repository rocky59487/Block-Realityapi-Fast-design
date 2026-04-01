package com.blockreality.api.client.rendering.bridge;

import com.blockreality.api.client.render.pipeline.BRRenderTier;
import com.blockreality.api.client.rendering.BRRTCompositor;
import com.blockreality.api.client.rendering.lod.BRVoxelLODManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forge Render Event Bridge — 將 Forge 渲染事件橋接至 LOD 系統與 Vulkan RT 管線。
 *
 * <p>訂閱：
 * <ul>
 *   <li>{@link RenderLevelStageEvent} — 每幀渲染掛點（AFTER_SOLID_BLOCKS 等）</li>
 *   <li>{@link ChunkEvent.Load} / {@link ChunkEvent.Unload} — chunk 生命週期</li>
 *   <li>{@link BlockEvent.NeighborNotifyEvent} — 方塊更新通知</li>
 * </ul>
 *
 * <p>使用 {@code @Mod.EventBusSubscriber(value = Dist.CLIENT)} 自動註冊至 Forge 事件匯流排。
 *
 * @author Block Reality Team
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(value = Dist.CLIENT)
public final class ForgeRenderEventBridge {

    private static final Logger LOG = LoggerFactory.getLogger("BR-ForgeBridge");

    private ForgeRenderEventBridge() {}

    // ─────────────────────────────────────────────────────────────────
    //  RenderLevelStageEvent — 主渲染鉤
    // ─────────────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onRenderStage(RenderLevelStageEvent event) {
        RenderLevelStageEvent.Stage stage = event.getStage();

        if (stage == RenderLevelStageEvent.Stage.AFTER_SKY) {
            // 最早可用的幀開始點：更新 LOD 相機 + 視錐
            updateLODBeginFrame(event);
        }

        if (stage == RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) {
            // 渲染 LOD 不透明地形
            renderLODOpaque(event);

            // TIER_3：更新 Vulkan RT TLAS
            if (BRRenderTier.getCurrentTier() == BRRenderTier.Tier.TIER_3) {
                updateVulkanTLAS();
            }
        }

        if (stage == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            // TIER_3：發射 RT 光線（shadows / reflections / GI）
            if (BRRenderTier.getCurrentTier() == BRRenderTier.Tier.TIER_3) {
                dispatchVulkanRT();
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Chunk 事件
    // ─────────────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getChunk() instanceof LevelChunk chunk)) return;
        LevelAccessor level = event.getLevel();
        ChunkRenderBridge.onChunkLoad(
            chunk.getPos().x, chunk.getPos().z, level);
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (!(event.getChunk() instanceof LevelChunk chunk)) return;
        ChunkRenderBridge.onChunkUnload(chunk.getPos().x, chunk.getPos().z);
    }

    // ─────────────────────────────────────────────────────────────────
    //  方塊更新事件
    // ─────────────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onBlockChange(BlockEvent.NeighborNotifyEvent event) {
        BlockPos pos = event.getPos();
        ChunkRenderBridge.onBlockChange(pos.getX(), pos.getY(), pos.getZ());

        // ── OMM 透明快取更新 (TIER_3 client only) ──────────────────────
        // NeighborNotifyEvent.getState() 是觸發通知的方塊本身的 BlockState。
        // 若放置的方塊為透明渲染類型（玻璃/水/冰/樹葉），立即標記所在 section
        // 為含透明，讓 VkAccelStructBuilder.rebuildSectionBLAS() 走非 OMM 路徑。
        // 移除透明方塊時不立即清除標記（保守策略），等待下次 BLAS rebuild 確認。
        if (BRRenderTier.getCurrentTier() == BRRenderTier.Tier.TIER_3
                && event.getLevel().isClientSide()) {
            if (isTransparentBlock(event.getState())) {
                int sectionX = pos.getX() >> 4;
                int sectionZ = pos.getZ() >> 4;
                BRRTCompositor.getInstance().markSectionTransparent(sectionX, sectionZ, true);
            }
        }
    }

    /**
     * 判斷方塊是否使用半透明或鏤空渲染類型（玻璃、水、冰、樹葉、玻璃板等）。
     *
     * <p>渲染類型判斷依據：
     * <ul>
     *   <li>{@link RenderType#translucent()} — 玻璃、染色玻璃、水、冰、蜂蜜塊</li>
     *   <li>{@link RenderType#cutoutMipped()} — 各類樹葉</li>
     *   <li>{@link RenderType#cutout()} — 玻璃板、鐵柵欄、鐵門等鏤空幾何</li>
     * </ul>
     *
     * <p>此方法僅在客戶端可用（{@code ItemBlockRenderTypes} 是 client-only API）。
     *
     * @param state 要判斷的方塊狀態
     * @return {@code true} 若方塊需要非不透明渲染
     */
    private static boolean isTransparentBlock(BlockState state) {
        RenderType rt = ItemBlockRenderTypes.getChunkRenderType(state);
        return rt == RenderType.translucent()
            || rt == RenderType.cutoutMipped()
            || rt == RenderType.cutout();
    }

    // ─────────────────────────────────────────────────────────────────
    //  內部輔助
    // ─────────────────────────────────────────────────────────────────

    private static void updateLODBeginFrame(RenderLevelStageEvent event) {
        try {
            Minecraft mc = Minecraft.getInstance();
            Camera cam = mc.gameRenderer.getMainCamera();

            // 相機位置
            double cx = cam.getPosition().x;
            double cy = cam.getPosition().y;
            double cz = cam.getPosition().z;

            // 矩陣（JOML）
            Matrix4f proj = new Matrix4f(RenderSystem.getProjectionMatrix());
            PoseStack poseStack = event.getPoseStack();
            Matrix4f view = new Matrix4f(poseStack.last().pose());

            // tick 計數（使用部分計時器）
            long tick = mc.level != null ? mc.level.getGameTime() : 0L;

            BRVoxelLODManager.getInstance().beginFrame(proj, view, cx, cy, cz, tick);
            event_projCache = proj;
            event_viewCache = view;

        } catch (Exception e) {
            LOG.error("LOD beginFrame error", e);
        }
    }

    private static void renderLODOpaque(RenderLevelStageEvent event) {
        try {
            BRVoxelLODManager.getInstance().renderOpaque();
        } catch (Exception e) {
            LOG.error("LOD renderOpaque error", e);
        }
    }

    // 每幀快取最近的 proj/view 矩陣，供 dispatchVulkanRT 使用
    private static Matrix4f event_projCache = null;
    private static Matrix4f event_viewCache = null;

    /** ★ UI-3: 首次 TLAS 更新 / RT 發射診斷旗標 */
    private static boolean firstTLASCallLogged  = false;
    private static boolean firstRTDispatchLogged = false;

    private static void updateVulkanTLAS() {
        // ★ UI-3: 首次執行時以 INFO 確認 TIER_3 TLAS 更新路徑有被觸發
        if (!firstTLASCallLogged) {
            firstTLASCallLogged = true;
            LOG.info("[UI-3] TIER_3 updateVulkanTLAS() first call — TLAS update path confirmed active");
        }
        try {
            BRVoxelLODManager.getInstance().updateBLAS();
            // TLAS 更新由 BRVulkanRT 在 Vulkan 執行緒處理
            // 這裡僅觸發 BLAS dirty → TLAS rebuild flag
        } catch (Exception e) {
            LOG.debug("Vulkan TLAS update error (TIER_3 not ready?)", e);
        }
    }

    private static void dispatchVulkanRT() {
        // ★ UI-3: 首次執行時以 INFO 確認 RT 光線發射路徑有被觸發
        if (!firstRTDispatchLogged) {
            firstRTDispatchLogged = true;
            LOG.info("[UI-3] TIER_3 dispatchVulkanRT() first call — RT dispatch path confirmed active");
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            Camera cam = mc.gameRenderer.getMainCamera();
            Matrix4f proj = event_projCache;
            Matrix4f view = event_viewCache;
            if (proj != null && view != null) {
                com.blockreality.api.client.rendering.BRRTCompositor.getInstance()
                    .executeRTPass(proj, view);
            } else {
                LOG.debug("[UI-3] RT dispatch skipped — proj/view matrix not yet cached");
            }
        } catch (Exception e) {
            LOG.debug("Vulkan RT dispatch error", e);
        }
    }
}
