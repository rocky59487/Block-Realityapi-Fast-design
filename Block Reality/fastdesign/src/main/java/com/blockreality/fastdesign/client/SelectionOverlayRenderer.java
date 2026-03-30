package com.blockreality.fastdesign.client;

import com.blockreality.fastdesign.FastDesignMod;
import com.blockreality.fastdesign.config.FastDesignConfig;
import com.blockreality.fastdesign.item.FdWandItem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * 選取區域尺寸標註渲染器 — Level 2
 *
 * 在選取框的三條邊上顯示尺寸文字 (billboard 效果面向玩家)
 * 在頂面中央顯示體積信息
 */
@Mod.EventBusSubscriber(
    modid = FastDesignMod.MOD_ID,
    bus = Mod.EventBusSubscriber.Bus.FORGE,
    value = Dist.CLIENT
)
public class SelectionOverlayRenderer {

    private static final Logger LOGGER = LogManager.getLogger("SelectionOverlayRenderer");

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        // 在粒子之後渲染（確保在線框之後）
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        ClientSelectionHolder.SelectionData sel = ClientSelectionHolder.get();
        if (sel == null) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        // 和 Preview3DRenderer 相同的手持判斷
        if (!shouldRender(mc.player)) return;

        PoseStack poseStack = event.getPoseStack();
        Vec3 camPos = mc.getEntityRenderDispatcher().camera.getPosition();
        Font font = mc.font;

        int sizeX = sel.sizeX();
        int sizeY = sel.sizeY();
        int sizeZ = sel.sizeZ();
        int volume = sel.volume();

        float x0 = sel.min().getX();
        float y0 = sel.min().getY();
        float z0 = sel.min().getZ();
        float x1 = sel.max().getX() + 1.0f;
        float y1 = sel.max().getY() + 1.0f;
        float z1 = sel.max().getZ() + 1.0f;

        // 尺寸標註文字
        String labelX = sizeX + "m";
        String labelY = sizeY + "m";
        String labelZ = sizeZ + "m";
        String labelVol = volume + " blocks";

        MultiBufferSource.BufferSource bufferSource =
            mc.renderBuffers().bufferSource();

        float textScale = 0.03f;

        // X 邊 (前方底邊中點, 偏下)
        renderBillboardText(poseStack, font, bufferSource, camPos,
            (x0 + x1) / 2, y0 - 0.3f, z0 - 0.3f,
            labelX, 0xFFFF6600, textScale);

        // Y 邊 (左側豎直邊中點, 偏外)
        renderBillboardText(poseStack, font, bufferSource, camPos,
            x0 - 0.3f, (y0 + y1) / 2, z0 - 0.3f,
            labelY, 0xFF00CC00, textScale);

        // Z 邊 (底面左邊中點, 偏下)
        renderBillboardText(poseStack, font, bufferSource, camPos,
            x0 - 0.3f, y0 - 0.3f, (z0 + z1) / 2,
            labelZ, 0xFF3399FF, textScale);

        // 體積 (頂面中央)
        renderBillboardText(poseStack, font, bufferSource, camPos,
            (x0 + x1) / 2, y1 + 0.5f, (z0 + z1) / 2,
            labelVol, 0xFFFFFFFF, textScale);

        // 尺寸總結 (頂面中央上方)
        String dimSummary = sizeX + "×" + sizeY + "×" + sizeZ;
        renderBillboardText(poseStack, font, bufferSource, camPos,
            (x0 + x1) / 2, y1 + 1.0f, (z0 + z1) / 2,
            dimSummary, 0xFFFFAA00, textScale * 1.2f);

        // ── 8 角落標記（L 形短線段） ──
        renderCornerMarks(poseStack, bufferSource, camPos, x0, y0, z0, x1, y1, z1);

        bufferSource.endBatch();
    }

    /**
     * 在世界空間中渲染 billboard 文字（始終面向攝影機）
     */
    private static void renderBillboardText(PoseStack poseStack, Font font,
                                             MultiBufferSource.BufferSource bufferSource,
                                             Vec3 camPos,
                                             float worldX, float worldY, float worldZ,
                                             String text, int color, float scale) {
        poseStack.pushPose();

        // 移動到世界座標
        poseStack.translate(worldX - camPos.x, worldY - camPos.y, worldZ - camPos.z);

        // Billboard: 面向攝影機
        Quaternionf rotation = Minecraft.getInstance().getEntityRenderDispatcher()
            .cameraOrientation();
        poseStack.mulPose(rotation);

        // 縮放
        poseStack.scale(-scale, -scale, scale);

        // 居中繪製
        float textWidth = font.width(text);
        Matrix4f matrix = poseStack.last().pose();

        font.drawInBatch(
            text,
            -textWidth / 2, 0,
            color,
            false,
            matrix,
            bufferSource,
            Font.DisplayMode.SEE_THROUGH,
            0x40000000,   // 背景色 (半透明黑)
            15728880      // 全亮度
        );

        poseStack.popPose();
    }

    /**
     * 在 8 個角落繪製 L 形標記（每角 3 條短線段沿 XYZ 軸延伸）
     */
    private static void renderCornerMarks(PoseStack poseStack,
                                           MultiBufferSource.BufferSource bufferSource,
                                           Vec3 camPos,
                                           float x0, float y0, float z0,
                                           float x1, float y1, float z1) {
        float armLen = 0.4f; // 短線段長度
        int r = 255, g = 200, b = 0, a = 255; // 橘黃色

        VertexConsumer vc = bufferSource.getBuffer(RenderType.LINES);
        Matrix4f matrix = poseStack.last().pose();

        float cx = (float) camPos.x;
        float cy = (float) camPos.y;
        float cz = (float) camPos.z;

        // 8 個角落座標
        float[][] corners = {
            {x0, y0, z0}, {x1, y0, z0}, {x0, y1, z0}, {x1, y1, z0},
            {x0, y0, z1}, {x1, y0, z1}, {x0, y1, z1}, {x1, y1, z1}
        };
        // 每個角落的延伸方向（朝 AABB 內部）
        float[][] dirs = {
            { 1,  1,  1}, {-1,  1,  1}, { 1, -1,  1}, {-1, -1,  1},
            { 1,  1, -1}, {-1,  1, -1}, { 1, -1, -1}, {-1, -1, -1}
        };

        for (int i = 0; i < 8; i++) {
            float px = corners[i][0] - cx;
            float py = corners[i][1] - cy;
            float pz = corners[i][2] - cz;
            float dx = dirs[i][0], dy = dirs[i][1], dz = dirs[i][2];

            // X 軸短線
            cornerLine(vc, matrix, px, py, pz, px + dx * armLen, py, pz,
                r, g, b, a, dx, 0, 0);
            // Y 軸短線
            cornerLine(vc, matrix, px, py, pz, px, py + dy * armLen, pz,
                r, g, b, a, 0, dy, 0);
            // Z 軸短線
            cornerLine(vc, matrix, px, py, pz, px, py, pz + dz * armLen,
                r, g, b, a, 0, 0, dz);
        }
    }

    private static void cornerLine(VertexConsumer vc, Matrix4f matrix,
                                    float ax, float ay, float az,
                                    float bx, float by, float bz,
                                    int r, int g, int b, int a,
                                    float nx, float ny, float nz) {
        vc.vertex(matrix, ax, ay, az).color(r, g, b, a)
            .normal(nx, ny, nz).endVertex();
        vc.vertex(matrix, bx, by, bz).color(r, g, b, a)
            .normal(nx, ny, nz).endVertex();
    }

    private static boolean shouldRender(Player player) {
        try {
            if (FastDesignConfig.isAlwaysShowSelection()) return true;
        } catch (Exception e) {
            LOGGER.error("Error checking FastDesignConfig.isAlwaysShowSelection()", e);
        }

        ItemStack mainHand = player.getMainHandItem();
        return mainHand.getItem() instanceof FdWandItem;
    }
}
