package com.blockreality.fastdesign.client;

import com.blockreality.fastdesign.FastDesignMod;
import com.blockreality.fastdesign.network.FdNetwork;
import com.blockreality.fastdesign.network.PastePlacePacket;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 體素級全息預覽渲染器 — Fast Design v2.0
 *
 * ★ v4 spec §6.2 (Axiom) + §7.2 (SimpleBuilding) 交互模式：
 *   - 預覽跟隨玩家準心（raycast 到方塊面 → 在該面偏移放置）
 *   - 右鍵直接放置（發送 PastePlacePacket），不需面板確認
 *   - 按 ESC 或切換工具取消預覽
 *
 * 渲染流程：
 *   1. 每 tick 更新預覽位置（raycast → 面偏移）
 *   2. 在 AFTER_TRANSLUCENT_BLOCKS 渲染幽靈方塊
 *   3. 監聽右鍵發送放置封包
 *
 * 效能策略：
 * - 使用預編譯的方塊頂點資料快取 (Map<BlockPos, BlockState>)
 * - 超過 MAX_PREVIEW_BLOCKS 時自動降級為線框模式
 */
@Mod.EventBusSubscriber(
    modid = FastDesignMod.MOD_ID,
    bus = Mod.EventBusSubscriber.Bus.FORGE,
    value = Dist.CLIENT
)
public class GhostPreviewRenderer {

    private static final int MAX_PREVIEW_BLOCKS = 100_000;
    private static final float GHOST_ALPHA = 0.4f;

    // 全息預覽資料快取（由指令系統或 Wand 邏輯填入）
    // blocks 的 key 是相對座標（相對於藍圖原點 0,0,0）
    private static volatile Map<BlockPos, BlockState> previewData = null;
    private static volatile boolean previewActive = false;

    // ★ 準心追蹤的動態放置位置（每 tick 更新）
    private static volatile BlockPos currentPlaceOrigin = new BlockPos(0, 0, 0);
    private static long lastPlaceTimeMs = 0L;

    // ─── 公開 API ───

    /** 設定預覽資料（由 PastePreviewSyncPacket 呼叫） */
    public static void setPreview(Map<BlockPos, BlockState> blocks, BlockPos origin) {
        if (blocks == null || blocks.isEmpty()) {
            clearPreview();
            return;
        }
        previewData = new ConcurrentHashMap<>(blocks);
        currentPlaceOrigin = origin;
        previewActive = true;
    }

    /** 清除預覽 */
    public static void clearPreview() {
        previewActive = false;
        previewData = null;
    }

    public static boolean hasPreview() {
        return previewActive && previewData != null;
    }

    /** 取得當前放置位置（供 UI 顯示或其他系統查詢） */
    public static BlockPos getCurrentPlaceOrigin() {
        return currentPlaceOrigin;
    }

    // ─── 每 tick 更新預覽位置 + 右鍵放置 ───

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        // 快照揮發性字段以防止 TOCTOU 競態條件
        boolean active = previewActive;
        Map<BlockPos, BlockState> data = previewData;

        if (!active || data == null) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        // ★ 預覽啟用時不限制手持物品，允許任何狀態下右鍵放置
        // （原先要求手持 FdWandItem，導致從面板複製後右鍵無反應）

        // ★ 準心追蹤：raycast 到方塊面，在該面的鄰接位置放置
        HitResult hitResult = mc.hitResult;
        if (hitResult instanceof BlockHitResult blockHit
                && blockHit.getType() == HitResult.Type.BLOCK) {
            BlockPos hitPos = blockHit.getBlockPos();
            Direction hitFace = blockHit.getDirection();

            // 預覽原點 = 被指向方塊的鄰接面（與正常放置方塊相同的邏輯）
            currentPlaceOrigin = hitPos.relative(hitFace);
        }

        // ★ 右鍵放置：consumeClick 消耗 Minecraft 的 use 按鍵
        //   當預覽啟用時，攔截右鍵並發送放置封包
        while (mc.options.keyUse.consumeClick()) {
            if (active && data != null) {
                long now = System.currentTimeMillis();
                if (now - lastPlaceTimeMs < 250L) return;
                lastPlaceTimeMs = now;
                // 發送 C→S 放置封包
                FdNetwork.CHANNEL.sendToServer(new PastePlacePacket(currentPlaceOrigin));
                // 暫時清除客戶端預覽（等待伺服器確認）
                clearPreview();
                break;
            }
        }
    }

    // ─── 渲染 ───

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        // 快照揮發性字段以防止 TOCTOU 競態條件
        boolean active = previewActive;
        Map<BlockPos, BlockState> data = previewData;

        if (!active || data == null) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        // ★ 預覽啟用時不限制手持物品（允許從面板觸發的貼上操作）

        if (data.isEmpty()) return;

        // 取得當前放置原點（由 tick 更新的準心位置）
        BlockPos origin = currentPlaceOrigin;

        // 超出上限時降級（只畫外框，不畫每個方塊）
        if (data.size() > MAX_PREVIEW_BLOCKS) {
            renderBoundingBoxOnly(event, data, origin);
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        Vec3 camPos = mc.getEntityRenderDispatcher().camera.getPosition();

        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);
        Matrix4f mat = poseStack.last().pose();

        Tesselator tess = Tesselator.getInstance();
        BufferBuilder buf = tess.getBuilder();

        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);

        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        for (var entry : data.entrySet()) {
            BlockPos relPos = entry.getKey();
            BlockState state = entry.getValue();
            if (state.isAir()) continue;

            // ★ 世界座標 = 準心追蹤的動態原點 + 相對座標
            float wx = origin.getX() + relPos.getX();
            float wy = origin.getY() + relPos.getY();
            float wz = origin.getZ() + relPos.getZ();

            // 從方塊的 MapColor 取色
            int mapColor = state.getMapColor(mc.level, relPos).col;
            int r = (mapColor >> 16) & 0xFF;
            int g = (mapColor >> 8) & 0xFF;
            int b = mapColor & 0xFF;
            int a = (int)(GHOST_ALPHA * 255);

            // 簡化的 6 面方塊渲染
            renderGhostCube(buf, mat, wx, wy, wz, wx + 1, wy + 1, wz + 1, r, g, b, a);
        }

        tess.end();

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        poseStack.popPose();
    }

    private static void renderBoundingBoxOnly(RenderLevelStageEvent event,
                                               Map<BlockPos, BlockState> data,
                                               BlockPos origin) {
        // 計算 AABB 並只渲染外框
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos pos : data.keySet()) {
            minX = Math.min(minX, pos.getX()); maxX = Math.max(maxX, pos.getX());
            minY = Math.min(minY, pos.getY()); maxY = Math.max(maxY, pos.getY());
            minZ = Math.min(minZ, pos.getZ()); maxZ = Math.max(maxZ, pos.getZ());
        }

        Minecraft mc = Minecraft.getInstance();
        PoseStack poseStack = event.getPoseStack();
        Vec3 camPos = mc.getEntityRenderDispatcher().camera.getPosition();

        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);
        Matrix4f mat = poseStack.last().pose();

        float x0 = origin.getX() + minX;
        float y0 = origin.getY() + minY;
        float z0 = origin.getZ() + minZ;
        float x1 = origin.getX() + maxX + 1;
        float y1 = origin.getY() + maxY + 1;
        float z1 = origin.getZ() + maxZ + 1;

        Tesselator tess = Tesselator.getInstance();
        BufferBuilder buf = tess.getBuilder();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.lineWidth(3.0f);

        buf.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
        int r = 0, g = 200, b = 255, a = 200;

        // 12 edges
        line(buf, mat, x0,y0,z0, x1,y0,z0, r,g,b,a); line(buf, mat, x1,y0,z0, x1,y0,z1, r,g,b,a);
        line(buf, mat, x1,y0,z1, x0,y0,z1, r,g,b,a); line(buf, mat, x0,y0,z1, x0,y0,z0, r,g,b,a);
        line(buf, mat, x0,y1,z0, x1,y1,z0, r,g,b,a); line(buf, mat, x1,y1,z0, x1,y1,z1, r,g,b,a);
        line(buf, mat, x1,y1,z1, x0,y1,z1, r,g,b,a); line(buf, mat, x0,y1,z1, x0,y1,z0, r,g,b,a);
        line(buf, mat, x0,y0,z0, x0,y1,z0, r,g,b,a); line(buf, mat, x1,y0,z0, x1,y1,z0, r,g,b,a);
        line(buf, mat, x1,y0,z1, x1,y1,z1, r,g,b,a); line(buf, mat, x0,y0,z1, x0,y1,z1, r,g,b,a);

        tess.end();
        RenderSystem.disableBlend();
        poseStack.popPose();
    }

    // ─── 工具方法 ───

    private static void renderGhostCube(BufferBuilder buf, Matrix4f mat,
                                         float x0, float y0, float z0,
                                         float x1, float y1, float z1,
                                         int r, int g, int b, int a) {
        // Bottom (Y-)
        quad(buf, mat, x0,y0,z0, x1,y0,z0, x1,y0,z1, x0,y0,z1, r,g,b,a);
        // Top (Y+)
        quad(buf, mat, x0,y1,z1, x1,y1,z1, x1,y1,z0, x0,y1,z0, r,g,b,a);
        // North (Z-)
        quad(buf, mat, x0,y0,z0, x0,y1,z0, x1,y1,z0, x1,y0,z0, r,g,b,a);
        // South (Z+)
        quad(buf, mat, x1,y0,z1, x1,y1,z1, x0,y1,z1, x0,y0,z1, r,g,b,a);
        // West (X-)
        quad(buf, mat, x0,y0,z1, x0,y1,z1, x0,y1,z0, x0,y0,z0, r,g,b,a);
        // East (X+)
        quad(buf, mat, x1,y0,z0, x1,y1,z0, x1,y1,z1, x1,y0,z1, r,g,b,a);
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

    private static void line(BufferBuilder buf, Matrix4f mat,
                              float x0, float y0, float z0,
                              float x1, float y1, float z1,
                              int r, int g, int b, int a) {
        buf.vertex(mat, x0, y0, z0).color(r, g, b, a).endVertex();
        buf.vertex(mat, x1, y1, z1).color(r, g, b, a).endVertex();
    }
}
