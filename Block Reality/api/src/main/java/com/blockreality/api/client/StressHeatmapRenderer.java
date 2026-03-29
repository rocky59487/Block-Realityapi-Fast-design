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

    /** ★ review-fix ICReM: 擴大渲染距離支援大型結構 */
    private static final int RENDER_DISTANCE = 48;

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
     * ★ review-fix ICReM: 增強 4 段應力色彩梯度。
     *
     * 新梯度（比原版更直覺、更容易區分危險等級）：
     *   [0.0, 0.2] → 青藍色 (0, 180, 255) — 安全
     *   [0.2, 0.5] → 綠黃色 (100, 255, 0) → (255, 220, 0) — 注意
     *   [0.5, 0.8] → 橙色 (255, 140, 0) → (255, 50, 0) — 警告
     *   [0.8, 1.0+] → 深紅 + 脈衝閃爍 — 危險
     *
     * 改進：
     *   - 4 段代替 3 段，更細膩的風險感知
     *   - 高應力區 alpha 更高（更醒目）
     *   - 超載 (>1.0) 時脈衝閃爍效果
     */
    private static int[] stressToColor(float stress) {
        stress = Math.max(0.0f, Math.min(stress, 1.5f));

        int r, g, b, a;

        if (stress <= 0.2f) {
            // 青藍色（安全）
            float t = stress / 0.2f;
            r = lerp(0, 100, t);
            g = lerp(180, 255, t);
            b = lerp(255, 0, t);
            a = lerp(60, 80, t);
        } else if (stress <= 0.5f) {
            // 綠黃 → 橙（注意）
            float t = (stress - 0.2f) / 0.3f;
            r = lerp(100, 255, t);
            g = lerp(255, 180, t);
            b = 0;
            a = lerp(80, 110, t);
        } else if (stress <= 0.8f) {
            // 橙 → 紅（警告）
            float t = (stress - 0.5f) / 0.3f;
            r = 255;
            g = lerp(180, 30, t);
            b = 0;
            a = lerp(110, 150, t);
        } else {
            // 深紅 + 脈衝效果（危險）
            float pulse = 0.8f + 0.2f * (float) Math.sin(System.nanoTime() * 1e-8);
            r = (int) (255 * pulse);
            g = (int) (20 * (1.0f - pulse));
            b = 0;
            a = (int) (150 + 50 * pulse); // 150~200 高透明度
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
