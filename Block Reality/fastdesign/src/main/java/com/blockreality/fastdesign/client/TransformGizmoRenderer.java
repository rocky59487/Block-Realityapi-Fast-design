package com.blockreality.fastdesign.client;

import com.blockreality.fastdesign.FastDesignMod;
import com.blockreality.fastdesign.item.FdWandItem;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

/**
 * 3D 變換操縱桿渲染器 — Fast Design v2.0 Feature 2
 *
 * 在選取框中心渲染 X(紅)/Y(綠)/Z(藍) 三軸箭頭 Gizmo。
 * 讓玩家拖曳箭頭來移動選取區域或藍圖，取代在 ControlPanelScreen 中手動輸入偏移數值。
 *
 * 設計參考：Blender / Unity 的 Transform Gizmo + Axiom Mod 的世界內操縱桿。
 *
 * 操作方式：
 * - 準心懸停在軸箭頭上 → 高亮顯示
 * - 左鍵拖曳 → 沿該軸移動選取框
 * - 滑鼠滾輪 → 沿該軸旋轉 90° (結合 Shift)
 */
@Mod.EventBusSubscriber(
    modid = FastDesignMod.MOD_ID,
    bus = Mod.EventBusSubscriber.Bus.FORGE,
    value = Dist.CLIENT
)
public class TransformGizmoRenderer {

    /** Gizmo 軸長度（方塊為單位） */
    private static final float AXIS_LENGTH = 3.0f;
    /** 箭頭底面半徑 */
    private static final float ARROW_RADIUS = 0.15f;
    /** 箭頭長度 */
    private static final float ARROW_HEAD_LEN = 0.5f;
    /** 軸體寬度 */
    private static final float SHAFT_WIDTH = 0.05f;

    /** 目前高亮的軸 (null = 無) */
    private static volatile String highlightedAxis = null;

    /** 當前拖曳狀態 */
    private static volatile boolean isDragging = false;
    private static volatile String dragAxis = null;

    // ─── 公開 API ───

    public static void setHighlightedAxis(String axis) { highlightedAxis = axis; }
    public static String getHighlightedAxis() { return highlightedAxis; }
    public static boolean isDragging() { return isDragging; }
    public static void startDrag(String axis) { isDragging = true; dragAxis = axis; }
    public static void endDrag() { isDragging = false; dragAxis = null; }
    public static String getDragAxis() { return dragAxis; }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        ClientSelectionHolder.SelectionData sel = ClientSelectionHolder.get();
        if (sel == null) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        // 只在手持 FdWand 時渲染 Gizmo
        ItemStack mainHand = mc.player.getMainHandItem();
        if (!(mainHand.getItem() instanceof FdWandItem)) return;

        PoseStack poseStack = event.getPoseStack();
        Vec3 camPos = mc.getEntityRenderDispatcher().camera.getPosition();

        // Gizmo 中心 = 選取框中心
        float cx = (sel.min().getX() + sel.max().getX() + 1) / 2.0f;
        float cy = (sel.min().getY() + sel.max().getY() + 1) / 2.0f;
        float cz = (sel.min().getZ() + sel.max().getZ() + 1) / 2.0f;

        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);
        Matrix4f mat = poseStack.last().pose();

        Tesselator tess = Tesselator.getInstance();
        BufferBuilder buf = tess.getBuilder();

        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();

        // ─── X 軸 (紅色) ───
        boolean xHi = "X".equals(highlightedAxis);
        renderAxisShaft(buf, tess, mat, cx, cy, cz, cx + AXIS_LENGTH, cy, cz,
                xHi ? 255 : 220, xHi ? 100 : 60, xHi ? 100 : 60, xHi ? 255 : 200);
        renderArrowHead(buf, tess, mat, cx + AXIS_LENGTH, cy, cz, 0,
                xHi ? 255 : 220, xHi ? 100 : 60, xHi ? 100 : 60, xHi ? 255 : 200);

        // ─── Y 軸 (綠色) ───
        boolean yHi = "Y".equals(highlightedAxis);
        renderAxisShaft(buf, tess, mat, cx, cy, cz, cx, cy + AXIS_LENGTH, cz,
                yHi ? 100 : 60, yHi ? 255 : 220, yHi ? 100 : 60, yHi ? 255 : 200);
        renderArrowHead(buf, tess, mat, cx, cy + AXIS_LENGTH, cz, 1,
                yHi ? 100 : 60, yHi ? 255 : 220, yHi ? 100 : 60, yHi ? 255 : 200);

        // ─── Z 軸 (藍色) ───
        boolean zHi = "Z".equals(highlightedAxis);
        renderAxisShaft(buf, tess, mat, cx, cy, cz, cx, cy, cz + AXIS_LENGTH,
                zHi ? 100 : 60, zHi ? 100 : 60, zHi ? 255 : 220, zHi ? 255 : 200);
        renderArrowHead(buf, tess, mat, cx, cy, cz + AXIS_LENGTH, 2,
                zHi ? 100 : 60, zHi ? 100 : 60, zHi ? 255 : 220, zHi ? 255 : 200);

        // ─── 中心方塊 (白色小立方體) ───
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        float hs = 0.1f;
        renderCube(buf, mat, cx - hs, cy - hs, cz - hs, cx + hs, cy + hs, cz + hs, 255, 255, 255, 220);
        tess.end();

        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        poseStack.popPose();
    }

    // ─── 軸桿渲染 ───

    private static void renderAxisShaft(BufferBuilder buf, Tesselator tess, Matrix4f mat,
                                         float x0, float y0, float z0,
                                         float x1, float y1, float z1,
                                         int r, int g, int b, int a) {
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        float w = SHAFT_WIDTH;

        // 根據軸方向判斷偏移方向
        if (y0 == y1 && z0 == z1) { // X 軸
            renderCube(buf, mat, x0, y0 - w, z0 - w, x1, y0 + w, z0 + w, r, g, b, a);
        } else if (x0 == x1 && z0 == z1) { // Y 軸
            renderCube(buf, mat, x0 - w, y0, z0 - w, x0 + w, y1, z0 + w, r, g, b, a);
        } else { // Z 軸
            renderCube(buf, mat, x0 - w, y0 - w, z0, x0 + w, y0 + w, z1, r, g, b, a);
        }

        tess.end();
    }

    // ─── 箭頭渲染（簡化為金字塔形） ───

    private static void renderArrowHead(BufferBuilder buf, Tesselator tess, Matrix4f mat,
                                         float tipX, float tipY, float tipZ,
                                         int axis, int r, int g, int b, int a) {
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        float ar = ARROW_RADIUS;
        float al = ARROW_HEAD_LEN;

        // 箭頭底面中心 & 方向
        if (axis == 0) { // X
            float bx = tipX; float tx = tipX + al;
            // 底面 4 個頂點
            renderCube(buf, mat, bx, tipY - ar, tipZ - ar, tx, tipY + ar, tipZ + ar, r, g, b, a);
        } else if (axis == 1) { // Y
            float by = tipY; float ty = tipY + al;
            renderCube(buf, mat, tipX - ar, by, tipZ - ar, tipX + ar, ty, tipZ + ar, r, g, b, a);
        } else { // Z
            float bz = tipZ; float tz = tipZ + al;
            renderCube(buf, mat, tipX - ar, tipY - ar, bz, tipX + ar, tipY + ar, tz, r, g, b, a);
        }

        tess.end();
    }

    // ─── 通用立方體 ───

    private static void renderCube(BufferBuilder buf, Matrix4f mat,
                                    float x0, float y0, float z0,
                                    float x1, float y1, float z1,
                                    int r, int g, int b, int a) {
        quad(buf, mat, x0,y0,z0, x1,y0,z0, x1,y0,z1, x0,y0,z1, r,g,b,a); // bottom
        quad(buf, mat, x0,y1,z1, x1,y1,z1, x1,y1,z0, x0,y1,z0, r,g,b,a); // top
        quad(buf, mat, x0,y0,z0, x0,y1,z0, x1,y1,z0, x1,y0,z0, r,g,b,a); // north
        quad(buf, mat, x1,y0,z1, x1,y1,z1, x0,y1,z1, x0,y0,z1, r,g,b,a); // south
        quad(buf, mat, x0,y0,z1, x0,y1,z1, x0,y1,z0, x0,y0,z0, r,g,b,a); // west
        quad(buf, mat, x1,y0,z0, x1,y1,z0, x1,y1,z1, x1,y0,z1, r,g,b,a); // east
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
