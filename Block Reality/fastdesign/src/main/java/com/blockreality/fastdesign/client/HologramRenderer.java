package com.blockreality.fastdesign.client;

import com.blockreality.api.blueprint.Blueprint;
import com.blockreality.fastdesign.config.FastDesignConfig;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import org.joml.Matrix4f;

/**
 * 藍圖全息投影渲染器 — v3fix §3.1
 */
@OnlyIn(Dist.CLIENT)
public class HologramRenderer {

    private static final int GHOST_R = 80;
    private static final int GHOST_G = 160;
    private static final int GHOST_B = 255;
    private static final float INSET = 0.002f;

    // ── VBO cache — rebuilt only when HologramState changes ──
    private static VertexBuffer hologramVbo = null;
    private static int cachedVersion = -1;

    private static int getGhostAlpha() {
        return (int) (FastDesignConfig.getHologramGhostAlpha() * 255);
    }

    private static double getCullDistanceSq() {
        double d = FastDesignConfig.getHologramCullDistance();
        return d * d;
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (!HologramState.isActive()) return;
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        Blueprint bp = HologramState.getBlueprint();
        if (bp == null || bp.getBlocks().isEmpty()) return;

        Camera camera = event.getCamera();
        Vec3 camPos = camera.getPosition();

        int currentVersion = HologramState.getVersion();
        if (hologramVbo == null || cachedVersion != currentVersion) {
            // Blueprint or position changed — rebuild VBO
            if (hologramVbo != null) {
                hologramVbo.close();
            }
            hologramVbo = buildVbo(bp, camPos);
            cachedVersion = currentVersion;
        }

        if (hologramVbo == null) return;

        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();

        ShaderInstance shader = RenderSystem.getShader();
        hologramVbo.drawWithShader(poseStack.last().pose(), event.getProjectionMatrix(), GameRenderer.getPositionColorShader());

        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();

        poseStack.popPose();
    }

    private static VertexBuffer buildVbo(Blueprint bp, Vec3 camPos) {
        BufferBuilder buffer = new BufferBuilder(DefaultVertexFormat.POSITION_COLOR.getVertexSize() * 24 * bp.getBlocks().size());
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        // Use identity matrix; world translation is done via PoseStack in draw call
        Matrix4f identity = new Matrix4f();
        double cullSq = getCullDistanceSq();
        boolean any = false;

        for (Blueprint.BlueprintBlock block : bp.getBlocks()) {
            if (block.getBlockState() == null || block.getBlockState().isAir()) continue;
            BlockPos worldPos = HologramState.getWorldPos(block.getRelX(), block.getRelY(), block.getRelZ());
            double dx = worldPos.getX() + 0.5 - camPos.x;
            double dy = worldPos.getY() + 0.5 - camPos.y;
            double dz = worldPos.getZ() + 0.5 - camPos.z;
            if (dx * dx + dy * dy + dz * dz > cullSq) continue;
            renderGhostBlock(buffer, identity, worldPos);
            any = true;
        }

        BufferBuilder.RenderedBuffer rendered = buffer.end();
        if (!any) {
            rendered.release();
            return null;
        }

        VertexBuffer vbo = new VertexBuffer(VertexBuffer.Usage.STATIC);
        vbo.bind();
        vbo.upload(rendered);
        VertexBuffer.unbind();
        return vbo;
    }

    private static void renderGhostBlock(BufferBuilder buffer, Matrix4f matrix, BlockPos pos) {
        float x0 = pos.getX() + INSET;
        float y0 = pos.getY() + INSET;
        float z0 = pos.getZ() + INSET;
        float x1 = pos.getX() + 1 - INSET;
        float y1 = pos.getY() + 1 - INSET;
        float z1 = pos.getZ() + 1 - INSET;

        addQuad(buffer, matrix, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
        addQuad(buffer, matrix, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0);
        addQuad(buffer, matrix, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
        addQuad(buffer, matrix, x1, y0, z1, x1, y1, z1, x0, y1, z1, x0, y0, z1);
        addQuad(buffer, matrix, x0, y0, z1, x0, y1, z1, x0, y1, z0, x0, y0, z0);
        addQuad(buffer, matrix, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
    }

    private static void addQuad(BufferBuilder buffer, Matrix4f matrix,
                                 float x0, float y0, float z0,
                                 float x1, float y1, float z1,
                                 float x2, float y2, float z2,
                                 float x3, float y3, float z3) {
        buffer.vertex(matrix, x0, y0, z0).color(GHOST_R, GHOST_G, GHOST_B, getGhostAlpha()).endVertex();
        buffer.vertex(matrix, x1, y1, z1).color(GHOST_R, GHOST_G, GHOST_B, getGhostAlpha()).endVertex();
        buffer.vertex(matrix, x2, y2, z2).color(GHOST_R, GHOST_G, GHOST_B, getGhostAlpha()).endVertex();
        buffer.vertex(matrix, x3, y3, z3).color(GHOST_R, GHOST_G, GHOST_B, getGhostAlpha()).endVertex();
    }
}
