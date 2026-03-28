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
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.List;

/**
 * 錨定路徑渲染器 — 想法.docx AnchorPathVisualizer
 *
 * 在 RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS 階段，
 * 以半透明線段渲染從 RBlock 到錨定點的 BFS 路徑。
 *
 * ★ R6-10 fix: 想法.docx 規格色彩區分：
 *   - 有效錨定 = 綠色 RGBA(0.2, 0.85, 0.2, 0.6)
 *   - 未錨定   = 紅色 RGBA(1.0, 0.2, 0.2, 0.6)
 *
 * 設計：
 *   - 線段寬度 2px
 *   - 從方塊中心到方塊中心畫線
 *   - 自動隨相機偏移（使用 PoseStack camera offset）
 *   - 超出 64 格渲染距離不繪製（效能保護）
 */
public class AnchorPathRenderer {

    /** ★ D-3d: 準備升級到 GL_LINES VBO + instancing。
     *  目前仍使用 BufferBuilder，新增 dirty 標記支援增量更新。 */
    private static volatile boolean pathsDirty = true;

    /** 標記路徑需要重建（支撐關係變更時呼叫） */
    public static void markDirty() {
        pathsDirty = true;
    }

    /**
     * 在 RenderLevelStageEvent 中呼叫。
     * 應在 ClientSetup 中註冊到 AFTER_TRANSLUCENT_BLOCKS 階段。
     */
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (!AnchorPathCache.hasActivePaths()) return;

        // ★ R6-10: 使用 PathEntry 取得錨定狀態
        List<AnchorPathCache.PathEntry> entries = AnchorPathCache.getPathEntries();
        if (entries.isEmpty()) return;

        // ★ D-3d: dirty check — 如果路徑資料未變更，跳過重建
        if (!pathsDirty) return;
        pathsDirty = false;

        Camera camera = event.getCamera();
        double camX = camera.getPosition().x;
        double camY = camera.getPosition().y;
        double camZ = camera.getPosition().z;

        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.lineWidth(2.0f);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();
        buffer.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);

        Matrix4f matrix = poseStack.last().pose();

        for (AnchorPathCache.PathEntry entry : entries) {
            // ★ R6-10: 根據錨定狀態選擇顏色
            float cr, cg, cb, ca;
            if (entry.isAnchored()) {
                cr = RenderUtils.ANCHOR_PATH_VALID_R;
                cg = RenderUtils.ANCHOR_PATH_VALID_G;
                cb = RenderUtils.ANCHOR_PATH_VALID_B;
                ca = RenderUtils.ANCHOR_PATH_VALID_A;
            } else {
                cr = RenderUtils.ANCHOR_PATH_INVALID_R;
                cg = RenderUtils.ANCHOR_PATH_INVALID_G;
                cb = RenderUtils.ANCHOR_PATH_INVALID_B;
                ca = RenderUtils.ANCHOR_PATH_INVALID_A;
            }

            List<BlockPos> path = entry.nodes();
            for (int i = 0; i < path.size() - 1; i++) {
                BlockPos from = path.get(i);
                BlockPos to = path.get(i + 1);

                // 渲染距離檢查
                double dx = from.getX() + 0.5 - camX;
                double dy = from.getY() + 0.5 - camY;
                double dz = from.getZ() + 0.5 - camZ;
                if (dx * dx + dy * dy + dz * dz > RenderUtils.MAX_RENDER_DIST_SQ) continue;

                // 從方塊中心到方塊中心畫線
                float x1 = (float) (from.getX() + 0.5 - camX);
                float y1 = (float) (from.getY() + 0.5 - camY);
                float z1 = (float) (from.getZ() + 0.5 - camZ);
                float x2 = (float) (to.getX() + 0.5 - camX);
                float y2 = (float) (to.getY() + 0.5 - camY);
                float z2 = (float) (to.getZ() + 0.5 - camZ);

                buffer.vertex(matrix, x1, y1, z1).color(cr, cg, cb, ca).endVertex();
                buffer.vertex(matrix, x2, y2, z2).color(cr, cg, cb, ca).endVertex();
            }
        }

        // Forge 1.20.1: 使用 BufferUploader.drawWithShader(buffer.end())
        BufferUploader.drawWithShader(buffer.end());

        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        RenderSystem.lineWidth(1.0f);

        poseStack.popPose();
    }
}
