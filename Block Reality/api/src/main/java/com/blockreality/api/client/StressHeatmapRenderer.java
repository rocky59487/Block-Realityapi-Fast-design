package com.blockreality.api.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.joml.Matrix4f;

import java.util.Map;

/**
 * 應力熱圖渲染器 — v3fix §1.8
 *
 * 在方塊表面疊加半透明色彩，即時顯示應力分佈：
 *   - 0.0–0.3: 藍色（安全）
 *   - 0.3–0.7: 黃色（警告）
 *   - 0.7–1.0+: 紅色（危險）
 *
 * 渲染管線：
 *   RenderLevelStageEvent (AFTER_TRANSLUCENT_BLOCKS)
 *   → BufferBuilder + POSITION_COLOR
 *   → GameRenderer.getPositionColorShader()
 *
 * 效能保護：
 *   - 32 格距離剔除
 *   - 僅渲染應力 > 0.05 的方塊（跳過零值）
 *   - 0.001 內縮防 Z-fighting
 */
@OnlyIn(Dist.CLIENT)
public class StressHeatmapRenderer {

    /** ★ D-3c: 標記為需要從 immediate mode 升級到 per-section VBO 渲染。
     *  目前仍使用 BufferBuilder，但新增 dirty 標記支援未來增量更新。 */
    private static volatile boolean meshDirty = true;
    private static volatile long lastRebuildTick = -1;

    /** 標記熱力圖需要重建（應力值變更時呼叫） */
    public static void markDirty() {
        meshDirty = true;
    }

    /** 覆蓋層開關（R 鍵切換） */
    private static boolean overlayEnabled = false;

    /** 渲染距離（格） */
    private static final int RENDER_DISTANCE = 32;

    /** 最小顯示應力閾值 */
    private static final float MIN_DISPLAY_STRESS = 0.05f;

    /** Z-fighting 防止用內縮量 */
    private static final float INSET = 0.001f;

    // ═══════════════════════════════════════════════════════
    //  開關控制
    // ═══════════════════════════════════════════════════════

    public static boolean isOverlayEnabled() {
        return overlayEnabled;
    }

    public static void toggleOverlay() {
        overlayEnabled = !overlayEnabled;
    }

    public static void setOverlayEnabled(boolean enabled) {
        overlayEnabled = enabled;
    }

    // ═══════════════════════════════════════════════════════
    //  主渲染入口
    // ═══════════════════════════════════════════════════════

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (!overlayEnabled) return;
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        Map<BlockPos, Float> stressCache = ClientStressCache.getCache();
        if (stressCache.isEmpty()) return;

        // ★ D-3c: dirty check — 如果應力資料未變更，跳過重建
        long worldTick = event.getPartialTick() == 0 ? 0 : System.nanoTime(); // 粗略 tick 代替
        if (!meshDirty && lastRebuildTick == worldTick) return;
        meshDirty = false;
        lastRebuildTick = worldTick;

        Camera camera = event.getCamera();
        Vec3 camPos = camera.getPosition();

        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);

        Matrix4f matrix = poseStack.last().pose();

        // 設定渲染狀態
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        int rendered = 0;
        for (Map.Entry<BlockPos, Float> entry : stressCache.entrySet()) {
            BlockPos pos = entry.getKey();
            float stress = entry.getValue();

            // 跳過低應力方塊
            if (stress < MIN_DISPLAY_STRESS) continue;

            // 距離剔除
            double dx = pos.getX() + 0.5 - camPos.x;
            double dy = pos.getY() + 0.5 - camPos.y;
            double dz = pos.getZ() + 0.5 - camPos.z;
            if (dx * dx + dy * dy + dz * dz > RENDER_DISTANCE * RENDER_DISTANCE) continue;

            // 計算顏色
            int[] rgba = stressToColor(stress);

            // 渲染 6 面
            renderStressOverlay(buffer, matrix, pos, rgba[0], rgba[1], rgba[2], rgba[3]);
            rendered++;
        }

        BufferUploader.drawWithShader(buffer.end());

        // 恢復渲染狀態
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();

        poseStack.popPose();
    }

    // ═══════════════════════════════════════════════════════
    //  色彩映射
    // ═══════════════════════════════════════════════════════

    /**
     * 應力值 → RGBA 色彩。
     *
     * 梯度：
     *   [0.0, 0.3] → 藍色 (0, 80, 255, 80)
     *   [0.3, 0.7] → 黃色 (255, 200, 0, 100)
     *   [0.7, 1.0+] → 紅色 (255, 30, 0, 130)
     *
     * 區間內做線性插值，確保視覺平滑過渡。
     */
    private static int[] stressToColor(float stress) {
        stress = Math.max(0.0f, Math.min(stress, 1.5f));

        int r, g, b, a;

        if (stress <= 0.3f) {
            // 藍 → 黃 (0.0–0.3)
            float t = stress / 0.3f;
            r = lerp(0, 255, t);
            g = lerp(80, 200, t);
            b = lerp(255, 0, t);
            a = lerp(80, 100, t);
        } else if (stress <= 0.7f) {
            // 黃 → 紅 (0.3–0.7)
            float t = (stress - 0.3f) / 0.4f;
            r = 255;
            g = lerp(200, 30, t);
            b = 0;
            a = lerp(100, 130, t);
        } else {
            // 紅 (0.7+)
            r = 255;
            g = 30;
            b = 0;
            a = 130;
        }

        return new int[]{r, g, b, a};
    }

    private static int lerp(int a, int b, float t) {
        return (int) (a + (b - a) * t);
    }

    // ═══════════════════════════════════════════════════════
    //  方塊覆蓋層渲染（6 面 quad）
    // ═══════════════════════════════════════════════════════

    /**
     * 渲染單一方塊的 6 面半透明覆蓋層。
     * 內縮 INSET 防止 Z-fighting。
     */
    private static void renderStressOverlay(BufferBuilder buffer, Matrix4f matrix,
                                             BlockPos pos, int r, int g, int b, int a) {
        float x0 = pos.getX() + INSET;
        float y0 = pos.getY() + INSET;
        float z0 = pos.getZ() + INSET;
        float x1 = pos.getX() + 1 - INSET;
        float y1 = pos.getY() + 1 - INSET;
        float z1 = pos.getZ() + 1 - INSET;

        // Bottom (Y-)
        addQuad(buffer, matrix, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, r, g, b, a);
        // Top (Y+)
        addQuad(buffer, matrix, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, r, g, b, a);
        // North (Z-)
        addQuad(buffer, matrix, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, r, g, b, a);
        // South (Z+)
        addQuad(buffer, matrix, x1, y0, z1, x1, y1, z1, x0, y1, z1, x0, y0, z1, r, g, b, a);
        // West (X-)
        addQuad(buffer, matrix, x0, y0, z1, x0, y1, z1, x0, y1, z0, x0, y0, z0, r, g, b, a);
        // East (X+)
        addQuad(buffer, matrix, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, r, g, b, a);
    }

    /**
     * 加入單個 quad（4 頂點）。
     */
    private static void addQuad(BufferBuilder buffer, Matrix4f matrix,
                                 float x0, float y0, float z0,
                                 float x1, float y1, float z1,
                                 float x2, float y2, float z2,
                                 float x3, float y3, float z3,
                                 int r, int g, int b, int a) {
        buffer.vertex(matrix, x0, y0, z0).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x1, y1, z1).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x2, y2, z2).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x3, y3, z3).color(r, g, b, a).endVertex();
    }
}
