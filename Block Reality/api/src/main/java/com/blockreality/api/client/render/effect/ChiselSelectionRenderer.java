package com.blockreality.api.client.render.effect;

import com.blockreality.api.item.ChiselItem;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 鑿刀工具選取區域線框的客戶端渲染器。
 * 當玩家使用鑿刀時，預覽哪些子體素將受到影響。
 *
 * 渲染器行為：
 * - 訂閱 RenderLevelStageEvent（AFTER_TRANSLUCENT_BLOCKS 階段）
 * - 僅在玩家手持 ChiselItem 時渲染
 * - 透過射線偵測玩家注視的方塊面
 * - 在該面上繪製彩色線框疊加層
 * - 使用脈衝透明度以提升可見性
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = "blockreality", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class ChiselSelectionRenderer {

    // 線框顏色：青色/藍綠色
    private static final float COLOR_R = 0.3f;
    private static final float COLOR_G = 0.8f;
    private static final float COLOR_B = 1.0f;

    // 脈衝效果的透明度振盪範圍
    private static final float ALPHA_MIN = 0.4f;
    private static final float ALPHA_MAX = 0.8f;

    // 脈衝頻率（每秒週期數）
    private static final float PULSE_FREQUENCY = 1.5f;

    // 子體素單位大小（每個子體素 = 0.1 方塊）
    private static final float VOXEL_UNIT = 0.1f;

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }

        // 檢查玩家是否手持 ChiselItem
        ItemStack mainHand = mc.player.getMainHandItem();
        ItemStack offHand = mc.player.getOffhandItem();

        ItemStack chiselStack = null;
        if (mainHand.getItem() instanceof ChiselItem) {
            chiselStack = mainHand;
        } else if (offHand.getItem() instanceof ChiselItem) {
            chiselStack = offHand;
        }

        if (chiselStack == null) {
            return;
        }

        // 取得當前命中結果
        HitResult hitResult = mc.hitResult;
        if (!(hitResult instanceof BlockHitResult blockHit)) {
            return;
        }

        BlockPos hitPos = blockHit.getBlockPos();
        Direction hitFace = blockHit.getDirection();

        // 取得攝影機偏移量
        Vec3 camPos = event.getCamera().getPosition();

        // 計算方塊內的精確命中位置
        Vec3 hitLocation = blockHit.getLocation();
        Vec3 relativeHit = hitLocation.subtract(hitPos.getX(), hitPos.getY(), hitPos.getZ());

        // 從 NBT 取得選取範圍尺寸
        int selWidth = ChiselItem.getSelectionWidth(chiselStack);
        int selHeight = ChiselItem.getSelectionHeight(chiselStack);

        // 渲染選取線框
        renderSelectionWireframe(event.getPoseStack(), camPos, hitPos, hitFace,
                relativeHit, selWidth, selHeight);
    }

    /**
     * 在命中面上渲染選取線框。
     *
     * @param poseStack 用於變換的姿態堆疊
     * @param camPos 攝影機位置
     * @param blockPos 注視中方塊的位置
     * @param face 注視中的面
     * @param relativeHit 相對於方塊角落的命中位置（各軸 0-1）
     * @param selWidth 選取寬度（子體素單位）
     * @param selHeight 選取高度（子體素單位）
     */
    private static void renderSelectionWireframe(PoseStack poseStack, Vec3 camPos,
                                                   BlockPos blockPos, Direction face,
                                                   Vec3 relativeHit, int selWidth, int selHeight) {
        // 計算命中體素座標（0-9 範圍）
        int hitVx = (int) (relativeHit.x * 10);
        int hitVy = (int) (relativeHit.y * 10);
        int hitVz = (int) (relativeHit.z * 10);

        // 限制在有效範圍內
        hitVx = Math.max(0, Math.min(9, hitVx));
        hitVy = Math.max(0, Math.min(9, hitVy));
        hitVz = Math.max(0, Math.min(9, hitVz));

        // 計算從命中中心的偏移量
        int halfW = (selWidth - 1) / 2;
        int halfH = (selHeight - 1) / 2;

        // 根據面的方向計算最小/最大體素座標
        int minX, maxX, minY, maxY, minZ, maxZ;

        switch (face.getAxis()) {
            case Y -> {
                // 頂面/底面：選取在 XZ 平面展開，Y 固定
                minX = Math.max(0, hitVx - halfW);
                maxX = Math.min(9, hitVx - halfW + selWidth - 1);
                minZ = Math.max(0, hitVz - halfH);
                maxZ = Math.min(9, hitVz - halfH + selHeight - 1);
                minY = maxY = hitVy;
            }
            case X -> {
                // 東面/西面：選取在 ZY 平面展開，X 固定
                minZ = Math.max(0, hitVz - halfW);
                maxZ = Math.min(9, hitVz - halfW + selWidth - 1);
                minY = Math.max(0, hitVy - halfH);
                maxY = Math.min(9, hitVy - halfH + selHeight - 1);
                minX = maxX = hitVx;
            }
            case Z -> {
                // 北面/南面：選取在 XY 平面展開，Z 固定
                minX = Math.max(0, hitVx - halfW);
                maxX = Math.min(9, hitVx - halfW + selWidth - 1);
                minY = Math.max(0, hitVy - halfH);
                maxY = Math.min(9, hitVy - halfH + selHeight - 1);
                minZ = maxZ = hitVz;
            }
            default -> {
                return;
            }
        }

        // 將體素座標轉換為世界座標
        float blockX = blockPos.getX();
        float blockY = blockPos.getY();
        float blockZ = blockPos.getZ();

        float minWorldX = blockX + minX * VOXEL_UNIT;
        float maxWorldX = blockX + (maxX + 1) * VOXEL_UNIT;
        float minWorldY = blockY + minY * VOXEL_UNIT;
        float maxWorldY = blockY + (maxY + 1) * VOXEL_UNIT;
        float minWorldZ = blockZ + minZ * VOXEL_UNIT;
        float maxWorldZ = blockZ + (maxZ + 1) * VOXEL_UNIT;

        // 套用攝影機偏移
        minWorldX -= camPos.x;
        maxWorldX -= camPos.x;
        minWorldY -= camPos.y;
        maxWorldY -= camPos.y;
        minWorldZ -= camPos.z;
        maxWorldZ -= camPos.z;

        // 計算脈衝透明度
        long gameTime = System.currentTimeMillis();
        float pulse = (float) Math.sin((gameTime / 1000.0f) * 2 * Math.PI * PULSE_FREQUENCY);
        float alpha = ALPHA_MIN + (pulse + 1) / 2 * (ALPHA_MAX - ALPHA_MIN);

        // 準備渲染狀態
        poseStack.pushPose();
        RenderSystem.enableBlend();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.lineWidth(2.0f);
        RenderSystem.disableDepthTest();

        var tesselator = com.mojang.blaze3d.vertex.Tesselator.getInstance();
        var buffer = tesselator.getBuilder();

        // 在面上繪製線框矩形
        buffer.begin(com.mojang.blaze3d.vertex.VertexFormat.Mode.DEBUG_LINES,
                com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR);

        int color = ((int) (alpha * 255) << 24)
                | ((int) (COLOR_B * 255) << 16)
                | ((int) (COLOR_G * 255) << 8)
                | (int) (COLOR_R * 255);

        // 根據面方向繪製矩形的四條邊
        switch (face.getAxis()) {
            case Y -> {
                // 頂面/底面：XZ 平面上的矩形
                drawLineWithColor(buffer, minWorldX, minWorldY, minWorldZ, maxWorldX, minWorldY, minWorldZ, color);
                drawLineWithColor(buffer, maxWorldX, minWorldY, minWorldZ, maxWorldX, minWorldY, maxWorldZ, color);
                drawLineWithColor(buffer, maxWorldX, minWorldY, maxWorldZ, minWorldX, minWorldY, maxWorldZ, color);
                drawLineWithColor(buffer, minWorldX, minWorldY, maxWorldZ, minWorldX, minWorldY, minWorldZ, color);
            }
            case X -> {
                // 東面/西面：ZY 平面上的矩形
                drawLineWithColor(buffer, minWorldX, minWorldY, minWorldZ, minWorldX, maxWorldY, minWorldZ, color);
                drawLineWithColor(buffer, minWorldX, maxWorldY, minWorldZ, minWorldX, maxWorldY, maxWorldZ, color);
                drawLineWithColor(buffer, minWorldX, maxWorldY, maxWorldZ, minWorldX, minWorldY, maxWorldZ, color);
                drawLineWithColor(buffer, minWorldX, minWorldY, maxWorldZ, minWorldX, minWorldY, minWorldZ, color);
            }
            case Z -> {
                // 北面/南面：XY 平面上的矩形
                drawLineWithColor(buffer, minWorldX, minWorldY, minWorldZ, maxWorldX, minWorldY, minWorldZ, color);
                drawLineWithColor(buffer, maxWorldX, minWorldY, minWorldZ, maxWorldX, maxWorldY, minWorldZ, color);
                drawLineWithColor(buffer, maxWorldX, maxWorldY, minWorldZ, minWorldX, maxWorldY, minWorldZ, color);
                drawLineWithColor(buffer, minWorldX, maxWorldY, minWorldZ, minWorldX, minWorldY, minWorldZ, color);
            }
        }

        tesselator.end();

        // 恢復渲染狀態
        RenderSystem.enableDepthTest();
        RenderSystem.lineWidth(1.0f);
        RenderSystem.disableBlend();
        poseStack.popPose();
    }

    /**
     * 使用 BufferBuilder 繪製線段。
     *
     * @param buffer BufferBuilder 實例
     * @param x1 起點 X
     * @param y1 起點 Y
     * @param z1 起點 Z
     * @param x2 終點 X
     * @param y2 終點 Y
     * @param z2 終點 Z
     * @param color ARGB 顏色值
     */
    private static void drawLineWithColor(com.mojang.blaze3d.vertex.VertexConsumer buffer,
                                          float x1, float y1, float z1,
                                          float x2, float y2, float z2,
                                          int color) {
        buffer.vertex(x1, y1, z1).color(color).endVertex();
        buffer.vertex(x2, y2, z2).color(color).endVertex();
    }
}
