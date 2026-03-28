package com.blockreality.api.client.render.ui;

import com.blockreality.api.blueprint.Blueprint;
import com.blockreality.api.client.render.BRRenderConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 客戶端藍圖預覽系統 — Axiom 風格即時預覽 + SimpleBuilding 風格快速操作。
 *
 * 功能：
 * - 載入藍圖為半透明「幽靈方塊」預覽
 * - 即時旋轉 (Y 軸 90° 步進) / 鏡像 (X/Y/Z)
 * - 預覽隨玩家準星移動（吸附到方塊網格）
 * - 碰撞檢測（可選：顯示重疊方塊為紅色）
 * - 快速放置確認（Enter / 右鍵）
 * - 多藍圖快速切換（滾輪或快捷鍵）
 *
 * 渲染部分僅提供資料，實際 GL 繪製由 BREffectRenderer / pipeline 負責。
 *
 * @author Block Reality Team
 * @version 1.0
 */
@OnlyIn(Dist.CLIENT)
public class BRBlueprintPreview {

    private static final Logger LOGGER = LoggerFactory.getLogger(BRBlueprintPreview.class);

    // ========================= 列舉 =========================

    /** 預覽對齊模式 */
    public enum SnapMode {
        /** 對齊到方塊面 */
        SURFACE,
        /** 對齊到方塊頂部 */
        TOP,
        /** 自由放置（準星位置） */
        FREE,
        /** 相對於上次放置位置偏移 */
        RELATIVE
    }

    /** 預覽旋轉狀態 */
    public enum Rotation {
        NONE(0), CW_90(1), CW_180(2), CW_270(3);

        public final int steps;
        Rotation(int steps) { this.steps = steps; }

        public Rotation next() {
            return values()[(ordinal() + 1) % 4];
        }
        public Rotation prev() {
            return values()[(ordinal() + 3) % 4];
        }
    }

    // ========================= 幽靈方塊資料 =========================

    /** 單個幽靈方塊 — 預覽渲染用 */
    public static class GhostBlock {
        public final int relX, relY, relZ;
        public final BlockState blockState;
        public final String rMaterialId;
        public boolean hasCollision; // 與世界方塊重疊

        public GhostBlock(int relX, int relY, int relZ, BlockState blockState, String rMaterialId) {
            this.relX = relX;
            this.relY = relY;
            this.relZ = relZ;
            this.blockState = blockState;
            this.rMaterialId = rMaterialId;
            this.hasCollision = false;
        }
    }

    // ========================= 狀態 =========================

    private static BRBlueprintPreview INSTANCE;

    /** 原始藍圖 */
    private Blueprint sourceBlueprint = null;

    /** 經過旋轉/鏡像後的工作藍圖 */
    private Blueprint workingBlueprint = null;

    /** 幽靈方塊快取（從 workingBlueprint 產生） */
    private final List<GhostBlock> ghostBlocks = new ArrayList<>();

    /** 預覽錨點（世界座標） */
    private BlockPos anchorPos = BlockPos.ZERO;

    /** 預覽是否啟用 */
    private boolean active = false;

    /** 目前旋轉 */
    private Rotation rotation = Rotation.NONE;

    /** 鏡像標記 */
    private boolean mirrorX = false;
    private boolean mirrorY = false;
    private boolean mirrorZ = false;

    /** 對齊模式 */
    private SnapMode snapMode = SnapMode.SURFACE;

    /** 上次放置位置（用於 RELATIVE 模式） */
    private BlockPos lastPlacePos = null;

    /** 藍圖列表（快速切換用） */
    private final List<Blueprint> blueprintSlots = new ArrayList<>();
    private int activeSlotIndex = -1;

    /** 預覽透明度 */
    private float ghostAlpha = 0.5f;

    /** 顯示碰撞標記 */
    private boolean showCollisions = true;

    /** 世界查詢介面 */
    private WorldAirQuery worldAirQuery = null;

    // ========================= 介面 =========================

    /** 簡單的世界空氣查詢 */
    public interface WorldAirQuery {
        boolean isAir(int x, int y, int z);
    }

    /** 放置確認回呼 */
    public interface PlaceCallback {
        /**
         * 當玩家確認放置時觸發。
         * @param blueprint 要放置的藍圖（已旋轉/鏡像）
         * @param origin 世界放置原點
         */
        void onPlaceConfirmed(Blueprint blueprint, BlockPos origin);
    }

    private PlaceCallback placeCallback = null;

    // ========================= 初始化 =========================

    public static void init() {
        INSTANCE = new BRBlueprintPreview();
        LOGGER.info("BRBlueprintPreview 初始化完成");
    }

    public static BRBlueprintPreview getInstance() {
        return INSTANCE;
    }

    public static void cleanup() {
        if (INSTANCE != null) {
            INSTANCE.deactivate();
            INSTANCE.blueprintSlots.clear();
            INSTANCE = null;
            LOGGER.info("BRBlueprintPreview 已清理");
        }
    }

    // ========================= 藍圖載入 =========================

    /** 啟動預覽 — 載入藍圖 */
    public void activate(Blueprint bp) {
        this.sourceBlueprint = bp;
        this.rotation = Rotation.NONE;
        this.mirrorX = false;
        this.mirrorY = false;
        this.mirrorZ = false;
        rebuildWorkingBlueprint();
        this.active = true;
        LOGGER.info("藍圖預覽啟動: '{}' ({} blocks)", bp.getName(), bp.getBlockCount());
    }

    /** 關閉預覽 */
    public void deactivate() {
        this.active = false;
        this.sourceBlueprint = null;
        this.workingBlueprint = null;
        this.ghostBlocks.clear();
    }

    public boolean isActive() { return active; }

    // ========================= 旋轉 / 鏡像 =========================

    /** 順時針旋轉 90° (Y 軸) */
    public void rotateCW() {
        rotation = rotation.next();
        rebuildWorkingBlueprint();
    }

    /** 逆時針旋轉 90° */
    public void rotateCCW() {
        rotation = rotation.prev();
        rebuildWorkingBlueprint();
    }

    /** 切換 X 鏡像 */
    public void toggleMirrorX() {
        mirrorX = !mirrorX;
        rebuildWorkingBlueprint();
    }

    /** 切換 Y 鏡像 */
    public void toggleMirrorY() {
        mirrorY = !mirrorY;
        rebuildWorkingBlueprint();
    }

    /** 切換 Z 鏡像 */
    public void toggleMirrorZ() {
        mirrorZ = !mirrorZ;
        rebuildWorkingBlueprint();
    }

    public Rotation getRotation() { return rotation; }
    public boolean isMirrorX() { return mirrorX; }
    public boolean isMirrorY() { return mirrorY; }
    public boolean isMirrorZ() { return mirrorZ; }

    /** 重建工作藍圖（應用旋轉 + 鏡像） */
    private void rebuildWorkingBlueprint() {
        if (sourceBlueprint == null) return;

        Blueprint bp = sourceBlueprint;

        // 先旋轉
        if (rotation.steps > 0) {
            bp = bp.rotateY(rotation.steps);
        }

        // 再鏡像
        if (mirrorX) bp = bp.mirror('x');
        if (mirrorY) bp = bp.mirror('y');
        if (mirrorZ) bp = bp.mirror('z');

        this.workingBlueprint = bp;
        rebuildGhostBlocks();
    }

    /** 從 workingBlueprint 產生 ghostBlocks 快取 */
    private void rebuildGhostBlocks() {
        ghostBlocks.clear();
        if (workingBlueprint == null) return;

        for (Blueprint.BlueprintBlock bb : workingBlueprint.getBlocks()) {
            BlockState state = bb.getBlockState();
            if (state == null || state.isAir()) continue;
            ghostBlocks.add(new GhostBlock(
                bb.getRelX(), bb.getRelY(), bb.getRelZ(),
                state, bb.getRMaterialId()
            ));
        }
    }

    // ========================= 錨點更新 =========================

    /** 更新預覽位置（每 tick 由輸入系統調用） */
    public void updateAnchor(BlockPos newAnchor) {
        if (!active) return;
        this.anchorPos = newAnchor;

        // 更新碰撞資訊
        if (showCollisions && worldAirQuery != null) {
            for (GhostBlock g : ghostBlocks) {
                int wx = anchorPos.getX() + g.relX;
                int wy = anchorPos.getY() + g.relY;
                int wz = anchorPos.getZ() + g.relZ;
                g.hasCollision = !worldAirQuery.isAir(wx, wy, wz);
            }
        }
    }

    public BlockPos getAnchorPos() { return anchorPos; }

    public void setSnapMode(SnapMode mode) { this.snapMode = mode; }
    public SnapMode getSnapMode() { return snapMode; }

    // ========================= 放置 =========================

    /** 確認放置 */
    public void confirmPlace() {
        if (!active || workingBlueprint == null) return;

        if (placeCallback != null) {
            placeCallback.onPlaceConfirmed(workingBlueprint, anchorPos);
        }

        lastPlacePos = anchorPos;
        LOGGER.info("藍圖放置確認: '{}' at {}", workingBlueprint.getName(), anchorPos);

        // 放置後不自動關閉（允許連續放置）
    }

    /** 確認放置並關閉預覽 */
    public void confirmPlaceAndClose() {
        confirmPlace();
        deactivate();
    }

    public void setPlaceCallback(PlaceCallback cb) { this.placeCallback = cb; }
    public void setWorldAirQuery(WorldAirQuery query) { this.worldAirQuery = query; }

    // ========================= 藍圖槽位 =========================

    /** 添加藍圖到快速切換列表 */
    public void addToSlots(Blueprint bp) {
        blueprintSlots.add(bp);
        if (activeSlotIndex < 0) activeSlotIndex = 0;
    }

    /** 切換到下一個藍圖 */
    public void nextSlot() {
        if (blueprintSlots.isEmpty()) return;
        activeSlotIndex = (activeSlotIndex + 1) % blueprintSlots.size();
        activate(blueprintSlots.get(activeSlotIndex));
    }

    /** 切換到上一個藍圖 */
    public void prevSlot() {
        if (blueprintSlots.isEmpty()) return;
        activeSlotIndex = (activeSlotIndex - 1 + blueprintSlots.size()) % blueprintSlots.size();
        activate(blueprintSlots.get(activeSlotIndex));
    }

    public int getSlotCount() { return blueprintSlots.size(); }
    public int getActiveSlotIndex() { return activeSlotIndex; }

    // ========================= 渲染資料 =========================

    /** 取得渲染用資料快照 */
    public PreviewRenderData getRenderData() {
        if (!active) return null;

        return new PreviewRenderData(
            Collections.unmodifiableList(ghostBlocks),
            anchorPos,
            ghostAlpha,
            showCollisions,
            workingBlueprint != null ? workingBlueprint.getName() : "",
            workingBlueprint != null ? workingBlueprint.getBlockCount() : 0,
            rotation,
            mirrorX, mirrorY, mirrorZ,
            getPreviewBounds()
        );
    }

    /** 取得預覽的世界空間 AABB */
    public AABB getPreviewBounds() {
        if (!active || workingBlueprint == null) return null;
        return new AABB(
            anchorPos.getX(),
            anchorPos.getY(),
            anchorPos.getZ(),
            anchorPos.getX() + workingBlueprint.getSizeX(),
            anchorPos.getY() + workingBlueprint.getSizeY(),
            anchorPos.getZ() + workingBlueprint.getSizeZ()
        );
    }

    /** 渲染用不可變資料 */
    public static class PreviewRenderData {
        public final List<GhostBlock> ghostBlocks;
        public final BlockPos anchor;
        public final float alpha;
        public final boolean showCollisions;
        public final String blueprintName;
        public final int blockCount;
        public final Rotation rotation;
        public final boolean mirrorX, mirrorY, mirrorZ;
        public final AABB bounds;

        public PreviewRenderData(List<GhostBlock> ghostBlocks, BlockPos anchor, float alpha,
                                  boolean showCollisions, String blueprintName, int blockCount,
                                  Rotation rotation, boolean mirrorX, boolean mirrorY, boolean mirrorZ,
                                  AABB bounds) {
            this.ghostBlocks = ghostBlocks;
            this.anchor = anchor;
            this.alpha = alpha;
            this.showCollisions = showCollisions;
            this.blueprintName = blueprintName;
            this.blockCount = blockCount;
            this.rotation = rotation;
            this.mirrorX = mirrorX;
            this.mirrorY = mirrorY;
            this.mirrorZ = mirrorZ;
            this.bounds = bounds;
        }
    }

    // ========================= 設定 =========================

    public void setGhostAlpha(float alpha) { this.ghostAlpha = Math.max(0.1f, Math.min(alpha, 1.0f)); }
    public float getGhostAlpha() { return ghostAlpha; }

    public void setShowCollisions(boolean show) { this.showCollisions = show; }
    public boolean isShowCollisions() { return showCollisions; }

    public BlockPos getLastPlacePos() { return lastPlacePos; }
}
