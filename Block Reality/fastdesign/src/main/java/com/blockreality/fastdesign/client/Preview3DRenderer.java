package com.blockreality.fastdesign.client;

import com.blockreality.fastdesign.FastDesignMod;
import com.blockreality.fastdesign.config.FastDesignConfig;
import com.blockreality.fastdesign.item.FdWandItem;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

/**
 * 選取區域 3D 線框預覽 — 開發手冊 §4.4
 *
 * Level 2 增強:
 * - 只在手持 FdWandItem 時顯示（除非 alwaysShowSelection = true）
 * - 半透明橙色填充面
 * - pos1 / pos2 角落標記（綠 / 紅）
 */
@Mod.EventBusSubscriber(
    modid = FastDesignMod.MOD_ID,
    bus = Mod.EventBusSubscriber.Bus.FORGE,
    value = Dist.CLIENT
)
public class Preview3DRenderer {

    private static volatile boolean cadModeActive = false;

    public static void setCadModeActive(boolean active) {
        cadModeActive = active;
    }

    /**
     * 判斷是否應該顯示選取外框
     */
    private static boolean shouldRender(Player player) {
        // Config 強制顯示
        try {
            if (FastDesignConfig.isAlwaysShowSelection()) return true;
        } catch (Exception ignored) {}

        ItemStack mainHand = player.getMainHandItem();
        return mainHand.getItem() instanceof FdWandItem;
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        ClientSelectionHolder.SelectionData sel = ClientSelectionHolder.get();
        if (sel == null) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        // Level 2: 只在手持游標時顯示
        if (!shouldRender(mc.player)) return;

        PoseStack poseStack = event.getPoseStack();
        Vec3 camPos = mc.getEntityRenderDispatcher().camera.getPosition();

        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);

        Matrix4f mat = poseStack.last().pose();

        // 1. 橙色線框
        renderSelectionWireframe(mat, sel);

        // 2. 半透明填充面
        renderTranslucentFill(mat, sel);

        // 3. 角落標記 (pos1 綠色, pos2 紅色)
        renderCornerMarkers(mat, sel);

        // 4. CAD 模式方塊輪廓
        if (cadModeActive) {
            renderBlockOutlines(mat, sel, mc);
        }

        poseStack.popPose();
    }

    // ───────────── 橙色線框 ─────────────

    private static void renderSelectionWireframe(Matrix4f mat,
                                                  ClientSelectionHolder.SelectionData sel) {
        float x0 = sel.min().getX();
        float y0 = sel.min().getY();
        float z0 = sel.min().getZ();
        float x1 = sel.max().getX() + 1.0f;
        float y1 = sel.max().getY() + 1.0f;
        float z1 = sel.max().getZ() + 1.0f;

        Tesselator tess = Tesselator.getInstance();
        BufferBuilder buf = tess.getBuilder();

        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.lineWidth(2.0f);

        buf.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);

        int r = 255, g = 165, b = 0, a = 220;

        // Bottom face
        line(buf, mat, x0, y0, z0, x1, y0, z0, r, g, b, a);
        line(buf, mat, x1, y0, z0, x1, y0, z1, r, g, b, a);
        line(buf, mat, x1, y0, z1, x0, y0, z1, r, g, b, a);
        line(buf, mat, x0, y0, z1, x0, y0, z0, r, g, b, a);

        // Top face
        line(buf, mat, x0, y1, z0, x1, y1, z0, r, g, b, a);
        line(buf, mat, x1, y1, z0, x1, y1, z1, r, g, b, a);
        line(buf, mat, x1, y1, z1, x0, y1, z1, r, g, b, a);
        line(buf, mat, x0, y1, z1, x0, y1, z0, r, g, b, a);

        // Vertical edges
        line(buf, mat, x0, y0, z0, x0, y1, z0, r, g, b, a);
        line(buf, mat, x1, y0, z0, x1, y1, z0, r, g, b, a);
        line(buf, mat, x1, y0, z1, x1, y1, z1, r, g, b, a);
        line(buf, mat, x0, y0, z1, x0, y1, z1, r, g, b, a);

        tess.end();

        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    // ───────────── 半透明填充面 ─────────────

    private static void renderTranslucentFill(Matrix4f mat,
                                               ClientSelectionHolder.SelectionData sel) {
        float x0 = sel.min().getX();
        float y0 = sel.min().getY();
        float z0 = sel.min().getZ();
        float x1 = sel.max().getX() + 1.0f;
        float y1 = sel.max().getY() + 1.0f;
        float z1 = sel.max().getZ() + 1.0f;

        Tesselator tess = Tesselator.getInstance();
        BufferBuilder buf = tess.getBuilder();

        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();

        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        int r = 255, g = 165, b = 0, a = 20; // 非常淡的橙色

        // Bottom (Y-)
        quad(buf, mat, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, r, g, b, a);
        // Top (Y+)
        quad(buf, mat, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, r, g, b, a);
        // North (Z-)
        quad(buf, mat, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, r, g, b, a);
        // South (Z+)
        quad(buf, mat, x1, y0, z1, x1, y1, z1, x0, y1, z1, x0, y0, z1, r, g, b, a);
        // West (X-)
        quad(buf, mat, x0, y0, z1, x0, y1, z1, x0, y1, z0, x0, y0, z0, r, g, b, a);
        // East (X+)
        quad(buf, mat, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, r, g, b, a);

        tess.end();

        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    // ───────────── 角落標記 ─────────────

    private static void renderCornerMarkers(Matrix4f mat,
                                             ClientSelectionHolder.SelectionData sel) {
        Tesselator tess = Tesselator.getInstance();
        BufferBuilder buf = tess.getBuilder();

        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();

        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        float ms = 0.15f; // marker size
        // pos1 = 綠色標記 (在 min 角落)
        float px1 = sel.min().getX();
        float py1 = sel.min().getY();
        float pz1 = sel.min().getZ();
        renderSmallCube(buf, mat, px1 - ms, py1 - ms, pz1 - ms,
                        px1 + ms, py1 + ms, pz1 + ms,
                        0, 255, 80, 200);

        // pos2 = 紅色標記 (在 max 角落 + 1)
        float px2 = sel.max().getX() + 1.0f;
        float py2 = sel.max().getY() + 1.0f;
        float pz2 = sel.max().getZ() + 1.0f;
        renderSmallCube(buf, mat, px2 - ms, py2 - ms, pz2 - ms,
                        px2 + ms, py2 + ms, pz2 + ms,
                        255, 50, 50, 200);

        tess.end();

        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    private static void renderSmallCube(BufferBuilder buf, Matrix4f mat,
                                         float x0, float y0, float z0,
                                         float x1, float y1, float z1,
                                         int r, int g, int b, int a) {
        // 6 faces
        quad(buf, mat, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, r, g, b, a);
        quad(buf, mat, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, r, g, b, a);
        quad(buf, mat, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, r, g, b, a);
        quad(buf, mat, x1, y0, z1, x1, y1, z1, x0, y1, z1, x0, y0, z1, r, g, b, a);
        quad(buf, mat, x0, y0, z1, x0, y1, z1, x0, y1, z0, x0, y0, z0, r, g, b, a);
        quad(buf, mat, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, r, g, b, a);
    }

    // ───────────── CAD 方塊輪廓 ─────────────

    private static void renderBlockOutlines(Matrix4f mat,
                                             ClientSelectionHolder.SelectionData sel,
                                             Minecraft mc) {
        Tesselator tess = Tesselator.getInstance();
        BufferBuilder buf = tess.getBuilder();

        // 大選取區域直接跳過方塊輪廓渲染，避免 getBlockState() 迭代百萬格
        long volume = (long)(sel.max().getX() - sel.min().getX() + 1)
                    * (sel.max().getY() - sel.min().getY() + 1)
                    * (sel.max().getZ() - sel.min().getZ() + 1);
        if (volume > 50000) {
            return;
        }

        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.lineWidth(1.0f);

        buf.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);

        int r = 100, g = 180, b = 255, a = 100;
        int count = 0;
        int maxRender = 2000;

        for (BlockPos pos : BlockPos.betweenClosed(sel.min(), sel.max())) {
            if (count >= maxRender) break;
            if (mc.level.getBlockState(pos).isAir()) continue;

            float bx = pos.getX();
            float by = pos.getY();
            float bz = pos.getZ();

            line(buf, mat, bx, by + 1, bz, bx + 1, by + 1, bz, r, g, b, a);
            line(buf, mat, bx + 1, by + 1, bz, bx + 1, by + 1, bz + 1, r, g, b, a);
            line(buf, mat, bx + 1, by + 1, bz + 1, bx, by + 1, bz + 1, r, g, b, a);
            line(buf, mat, bx, by + 1, bz + 1, bx, by + 1, bz, r, g, b, a);

            count++;
        }

        tess.end();
        RenderSystem.disableBlend();
    }

    // ───────────── 工具方法 ─────────────

    private static void line(BufferBuilder buf, Matrix4f mat,
                              float x0, float y0, float z0,
                              float x1, float y1, float z1,
                              int r, int g, int b, int a) {
        buf.vertex(mat, x0, y0, z0).color(r, g, b, a).endVertex();
        buf.vertex(mat, x1, y1, z1).color(r, g, b, a).endVertex();
    }

    private static void quad(BufferBuilder buf, Matrix4f mat,
                              float x0, float y0, float z0,
                              float x1, float y1, float z1,
                              float x2, float y2, float z2,
                              float x3, float y3, float z3,
                              int r, int g, int b, int a) {
        buf.vertex(mat, x0, y0, z0).color(r, g, b, a).endVertex();
        buf.vertex(mat, x1, y1, z1).color(r, g, b, a).endVertex();
        buf.vertex(mat, x2, y2, z2).color(r, g, b, a).endVertex();
        buf.vertex(mat, x3, y3, z3).color(r, g, b, a).endVertex();
    }
}
