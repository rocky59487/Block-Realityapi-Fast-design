package com.blockreality.api.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.Collections;
import java.util.List;

/**
 * Ghost Block Renderer — draws semi-transparent preview cubes at proposed placement positions.
 *
 * Usage:
 *   1. Call {@link #setPreview(List)} from client tick to update the preview list
 *   2. Call {@link #onRenderLevel(RenderLevelStageEvent)} from RenderLevelStageEvent
 *   3. Call {@link #clearPreview()} when the player cancels or confirms placement
 *
 * Rendering uses POSITION_COLOR with alpha blending, no textures.
 * Preview cubes are drawn as slightly-inset wireframe + translucent fill.
 */
@OnlyIn(Dist.CLIENT)
public final class GhostBlockRenderer {

    private GhostBlockRenderer() {}

    /** Current preview positions (thread-safe: only accessed on render thread) */
    private static volatile List<BlockPos> previewPositions = Collections.emptyList();

    /** Preview color: light blue with 40% opacity */
    private static final int R = 80, G = 160, B = 255, A = 100;

    /** Wireframe color: brighter blue, more opaque */
    private static final int WR = 120, WG = 200, WB = 255, WA = 200;

    /** Inset from block edges to avoid Z-fighting */
    private static final float INSET = 0.005f;

    /** Maximum preview blocks (prevent lag on huge selections) */
    private static final int MAX_PREVIEW = 10000;

    // ─── Public API ──────────────────────────────────────────

    /**
     * Set the list of positions to preview.
     * Pass empty list or null to clear.
     */
    public static void setPreview(List<BlockPos> positions) {
        if (positions == null || positions.isEmpty()) {
            previewPositions = Collections.emptyList();
        } else if (positions.size() > MAX_PREVIEW) {
            previewPositions = List.copyOf(positions.subList(0, MAX_PREVIEW));
        } else {
            previewPositions = List.copyOf(positions);
        }
    }

    /** Clear all preview positions. */
    public static void clearPreview() {
        previewPositions = Collections.emptyList();
    }

    /** @return true if there are active preview positions */
    public static boolean hasPreview() {
        return !previewPositions.isEmpty();
    }

    // ─── Render Hook ─────────────────────────────────────────

    /**
     * Called from {@link RenderLevelStageEvent} at AFTER_TRANSLUCENT_BLOCKS stage.
     */
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        List<BlockPos> positions = previewPositions;
        if (positions.isEmpty()) return;

        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        PoseStack poseStack = event.getPoseStack();

        poseStack.pushPose();
        poseStack.translate(-cam.x, -cam.y, -cam.z);

        Matrix4f matrix = poseStack.last().pose();

        // Setup render state — depth test ON 讓幽靈方塊被實體方塊遮擋
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false); // 不寫入 depth buffer (半透明物件)
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();

        // ─── Pass 1: Translucent fill ────────────────────────
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (BlockPos pos : positions) {
            renderFilledCube(buffer, matrix, pos, R, G, B, A);
        }
        tesselator.end();

        // ─── Pass 2: Wireframe edges ─────────────────────────
        RenderSystem.lineWidth(2.0f);
        buffer.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
        for (BlockPos pos : positions) {
            renderWireframeCube(buffer, matrix, pos, WR, WG, WB, WA);
        }
        tesselator.end();

        // Restore
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        RenderSystem.lineWidth(1.0f);

        poseStack.popPose();
    }

    // ─── Cube Rendering ──────────────────────────────────────

    private static void renderFilledCube(BufferBuilder buf, Matrix4f mat, BlockPos pos,
                                          int r, int g, int b, int a) {
        float x0 = pos.getX() + INSET;
        float y0 = pos.getY() + INSET;
        float z0 = pos.getZ() + INSET;
        float x1 = pos.getX() + 1 - INSET;
        float y1 = pos.getY() + 1 - INSET;
        float z1 = pos.getZ() + 1 - INSET;

        // Bottom face (Y-)
        buf.vertex(mat, x0, y0, z0).color(r, g, b, a).endVertex();
        buf.vertex(mat, x1, y0, z0).color(r, g, b, a).endVertex();
        buf.vertex(mat, x1, y0, z1).color(r, g, b, a).endVertex();
        buf.vertex(mat, x0, y0, z1).color(r, g, b, a).endVertex();

        // Top face (Y+)
        buf.vertex(mat, x0, y1, z0).color(r, g, b, a).endVertex();
        buf.vertex(mat, x0, y1, z1).color(r, g, b, a).endVertex();
        buf.vertex(mat, x1, y1, z1).color(r, g, b, a).endVertex();
        buf.vertex(mat, x1, y1, z0).color(r, g, b, a).endVertex();

        // North face (Z-)
        buf.vertex(mat, x0, y0, z0).color(r, g, b, a).endVertex();
        buf.vertex(mat, x0, y1, z0).color(r, g, b, a).endVertex();
        buf.vertex(mat, x1, y1, z0).color(r, g, b, a).endVertex();
        buf.vertex(mat, x1, y0, z0).color(r, g, b, a).endVertex();

        // South face (Z+)
        buf.vertex(mat, x0, y0, z1).color(r, g, b, a).endVertex();
        buf.vertex(mat, x1, y0, z1).color(r, g, b, a).endVertex();
        buf.vertex(mat, x1, y1, z1).color(r, g, b, a).endVertex();
        buf.vertex(mat, x0, y1, z1).color(r, g, b, a).endVertex();

        // West face (X-)
        buf.vertex(mat, x0, y0, z0).color(r, g, b, a).endVertex();
        buf.vertex(mat, x0, y0, z1).color(r, g, b, a).endVertex();
        buf.vertex(mat, x0, y1, z1).color(r, g, b, a).endVertex();
        buf.vertex(mat, x0, y1, z0).color(r, g, b, a).endVertex();

        // East face (X+)
        buf.vertex(mat, x1, y0, z0).color(r, g, b, a).endVertex();
        buf.vertex(mat, x1, y1, z0).color(r, g, b, a).endVertex();
        buf.vertex(mat, x1, y1, z1).color(r, g, b, a).endVertex();
        buf.vertex(mat, x1, y0, z1).color(r, g, b, a).endVertex();
    }

    private static void renderWireframeCube(BufferBuilder buf, Matrix4f mat, BlockPos pos,
                                             int r, int g, int b, int a) {
        float x0 = pos.getX() + INSET;
        float y0 = pos.getY() + INSET;
        float z0 = pos.getZ() + INSET;
        float x1 = pos.getX() + 1 - INSET;
        float y1 = pos.getY() + 1 - INSET;
        float z1 = pos.getZ() + 1 - INSET;

        // Bottom 4 edges
        line(buf, mat, x0, y0, z0, x1, y0, z0, r, g, b, a);
        line(buf, mat, x1, y0, z0, x1, y0, z1, r, g, b, a);
        line(buf, mat, x1, y0, z1, x0, y0, z1, r, g, b, a);
        line(buf, mat, x0, y0, z1, x0, y0, z0, r, g, b, a);

        // Top 4 edges
        line(buf, mat, x0, y1, z0, x1, y1, z0, r, g, b, a);
        line(buf, mat, x1, y1, z0, x1, y1, z1, r, g, b, a);
        line(buf, mat, x1, y1, z1, x0, y1, z1, r, g, b, a);
        line(buf, mat, x0, y1, z1, x0, y1, z0, r, g, b, a);

        // 4 vertical edges
        line(buf, mat, x0, y0, z0, x0, y1, z0, r, g, b, a);
        line(buf, mat, x1, y0, z0, x1, y1, z0, r, g, b, a);
        line(buf, mat, x1, y0, z1, x1, y1, z1, r, g, b, a);
        line(buf, mat, x0, y0, z1, x0, y1, z1, r, g, b, a);
    }

    private static void line(BufferBuilder buf, Matrix4f mat,
                              float x0, float y0, float z0,
                              float x1, float y1, float z1,
                              int r, int g, int b, int a) {
        buf.vertex(mat, x0, y0, z0).color(r, g, b, a).endVertex();
        buf.vertex(mat, x1, y1, z1).color(r, g, b, a).endVertex();
    }
}
