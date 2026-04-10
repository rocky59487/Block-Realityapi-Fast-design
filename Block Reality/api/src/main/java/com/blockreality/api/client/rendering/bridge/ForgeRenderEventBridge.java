package com.blockreality.api.client.rendering.bridge;

import com.blockreality.api.client.rendering.BRRTCompositor;
import com.blockreality.api.client.rendering.lod.BRVoxelLODManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
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
 * Forge Render Event Bridge — 將 Forge 渲染事件橋接至 LOD 系統。
 *
 * <p>訂閱：
 * <ul>
 *   <li>{@link RenderLevelStageEvent} — 每幀渲染掛點（AFTER_SOLID_BLOCKS 等）</li>
 *   <li>{@link ChunkEvent.Load} / {@link ChunkEvent.Unload} — chunk 生命週期</li>
 *   <li>{@link BlockEvent.NeighborNotifyEvent} — 方塊更新通知</li>
 * </ul>
 *
 * <p>Vulkan RT 管線已移除（Phase 4-F），渲染改為單一開/關切換。
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
        }

        if (stage == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            // 所有不透明+半透明幾何已入 GBuffer — 執行 Vulkan RT 合成
            renderRTComposite(event);
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

    private static void renderRTComposite(RenderLevelStageEvent event) {
        try {
            PoseStack poseStack = event.getPoseStack();
            Matrix4f proj = new Matrix4f(RenderSystem.getProjectionMatrix());
            Matrix4f view = new Matrix4f(poseStack.last().pose());
            BRRTCompositor.getInstance().executeRTPass(proj, view);
        } catch (Exception e) {
            LOG.error("RT composite error", e);
        }
    }

}
