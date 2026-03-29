package com.blockreality.api.client.render.ui;

import com.blockreality.api.placement.BuildMode;
import com.blockreality.api.placement.MultiBlockCalculator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Predicate;

/**
 * SimpleBuilding 風格快速放置引擎 — 整合選取、遮罩、藍圖預覽。
 *
 * 特性：
 * - 快速切換 BuildMode（LINE / WALL / CUBE / MIRROR）
 * - 即時幽靈預覽（每 tick 重算 ghost 位置）
 * - 整合 BRSelectionEngine 的選區作為操作範圍
 * - 整合 BRToolMask 限制放置 / 移除目標
 * - 支援批次操作：fill / replace / hollow / walls / outline
 * - 操作歷史（Undo/Redo，與 BRSelectionEngine 共用概念）
 * - 即時統計（方塊數、預計記憶體、操作耗時）
 *
 * 線程安全：僅在客戶端主線程使用。
 *
 * @author Block Reality Team
 * @version 1.0
 */
@OnlyIn(Dist.CLIENT)
public class BRQuickPlacer {

    private static final Logger LOGGER = LoggerFactory.getLogger(BRQuickPlacer.class);

    // ========================= 批次操作類型 =========================

    /** 批次操作模式 */
    public enum BatchOp {
        /** 填充（所有位置） */
        FILL,
        /** 替換（只替換指定方塊） */
        REPLACE,
        /** 中空（只保留外殼） */
        HOLLOW,
        /** 牆壁（只保留四面，頂底開放） */
        WALLS,
        /** 外框（只保留 12 條邊） */
        OUTLINE,
        /** 地板（只保留 Y 最小面） */
        FLOOR,
        /** 天花板（只保留 Y 最大面） */
        CEILING
    }

    // ========================= 幽靈預覽方塊 =========================

    /** 預覽用方塊資料 */
    public static class PlacePreview {
        public final int x, y, z;
        public final boolean isRemoval; // true = 移除預覽（紅色）

        public PlacePreview(int x, int y, int z, boolean isRemoval) {
            this.x = x; this.y = y; this.z = z;
            this.isRemoval = isRemoval;
        }
    }

    // ========================= 操作記錄 =========================

    /** 單筆操作記錄（用於 Undo） */
    public static class PlaceRecord {
        public final BlockPos pos;
        public final BlockState previousState;
        public final BlockState newState;

        public PlaceRecord(BlockPos pos, BlockState previousState, BlockState newState) {
            this.pos = pos;
            this.previousState = previousState;
            this.newState = newState;
        }
    }

    /** 一次操作的完整記錄 */
    public static class OperationRecord {
        public final String description;
        public final List<PlaceRecord> records;
        public final long timestampMs;

        public OperationRecord(String description, List<PlaceRecord> records) {
            this.description = description;
            this.records = Collections.unmodifiableList(records);
            this.timestampMs = System.currentTimeMillis();
        }

        public int getBlockCount() { return records.size(); }
    }

    // ========================= 世界操作介面 =========================

    /** 世界查詢 + 放置介面 */
    public interface WorldOperator {
        BlockState getBlockState(int x, int y, int z);
        boolean isAir(int x, int y, int z);
        void setBlock(int x, int y, int z, BlockState state);
    }

    // ========================= 狀態 =========================

    private static BRQuickPlacer INSTANCE;

    /** 目前 BuildMode */
    private BuildMode buildMode = BuildMode.NORMAL;

    /** 錨點 */
    private BlockPos pos1 = null;
    private BlockPos pos2 = null;
    private BlockPos mirrorAnchor = null;

    /** 放置的方塊狀態（null = 移除模式） */
    private BlockState placeState = null;

    /** 替換模式的目標方塊 */
    private BlockState replaceTarget = null;

    /** 批次操作模式 */
    private BatchOp batchOp = BatchOp.FILL;

    /** 幽靈預覽快取 */
    private final List<PlacePreview> previewCache = new ArrayList<>();
    private boolean previewDirty = true;

    /** 操作歷史 */
    private final Deque<OperationRecord> undoStack = new ArrayDeque<>();
    private final Deque<OperationRecord> redoStack = new ArrayDeque<>();
    private static final int MAX_UNDO_DEPTH = 32;

    /** Tool Mask（可選） */
    private Predicate<BlockPos> toolMask = null;

    /** 世界操作 */
    private WorldOperator worldOperator = null;

    /** 即時統計 */
    private int lastPreviewCount = 0;
    private long lastOperationTimeMs = 0;

    /** 快速放置限制（防止卡頓） */
    private static final int MAX_BLOCKS_PER_OPERATION = 262_144; // 256K

    // ========================= 初始化 =========================

    public static void init() {
        INSTANCE = new BRQuickPlacer();
        LOGGER.info("BRQuickPlacer 初始化完成");
    }

    public static BRQuickPlacer getInstance() {
        return INSTANCE;
    }

    public static void cleanup() {
        if (INSTANCE != null) {
            INSTANCE.undoStack.clear();
            INSTANCE.redoStack.clear();
            INSTANCE.previewCache.clear();
            INSTANCE = null;
            LOGGER.info("BRQuickPlacer 已清理");
        }
    }

    // ========================= 設定 =========================

    public void setWorldOperator(WorldOperator op) { this.worldOperator = op; }

    public void setBuildMode(BuildMode mode) {
        this.buildMode = mode;
        this.previewDirty = true;
    }
    public BuildMode getBuildMode() { return buildMode; }

    public void cycleBuildModeForward() {
        setBuildMode(buildMode.next());
    }
    public void cycleBuildModeBackward() {
        setBuildMode(buildMode.prev());
    }

    public void setBatchOp(BatchOp op) {
        this.batchOp = op;
        this.previewDirty = true;
    }
    public BatchOp getBatchOp() { return batchOp; }

    public void setPlaceState(BlockState state) {
        this.placeState = state;
        this.previewDirty = true;
    }
    public BlockState getPlaceState() { return placeState; }

    public void setReplaceTarget(BlockState target) { this.replaceTarget = target; }
    public BlockState getReplaceTarget() { return replaceTarget; }

    public void setToolMask(Predicate<BlockPos> mask) { this.toolMask = mask; }
    public void clearToolMask() { this.toolMask = null; }

    // ========================= 錨點管理 =========================

    /** 設定第一個錨點 */
    public void setPos1(BlockPos pos) {
        this.pos1 = pos;
        this.previewDirty = true;
    }

    /** 設定第二個錨點 */
    public void setPos2(BlockPos pos) {
        this.pos2 = pos;
        this.previewDirty = true;
    }

    /** 設定鏡像錨點 */
    public void setMirrorAnchor(BlockPos pos) {
        this.mirrorAnchor = pos;
        this.previewDirty = true;
    }

    /** 清除錨點 */
    public void clearAnchors() {
        this.pos1 = null;
        this.pos2 = null;
        this.mirrorAnchor = null;
        this.previewDirty = true;
    }

    public BlockPos getPos1() { return pos1; }
    public BlockPos getPos2() { return pos2; }

    // ========================= 預覽計算 =========================

    /**
     * 每 tick 調用 — 更新幽靈預覽（若有變更）。
     */
    public void tick() {
        if (!previewDirty) return;
        previewDirty = false;
        rebuildPreview();
    }

    /** 重建預覽 */
    private void rebuildPreview() {
        previewCache.clear();

        if (pos1 == null || pos2 == null) {
            lastPreviewCount = 0;
            return;
        }

        // 使用 MultiBlockCalculator 取得位置列表
        List<BlockPos> positions = MultiBlockCalculator.calculate(buildMode, pos1, pos2, mirrorAnchor);

        boolean isRemoval = (placeState == null);
        int count = 0;

        for (BlockPos pos : positions) {
            if (count >= MAX_BLOCKS_PER_OPERATION) break;

            // Tool Mask 過濾
            if (toolMask != null && !toolMask.test(pos)) continue;

            // 批次操作過濾
            if (!passesBatchFilter(pos)) continue;

            previewCache.add(new PlacePreview(pos.getX(), pos.getY(), pos.getZ(), isRemoval));
            count++;
        }

        lastPreviewCount = count;
    }

    /** 根據 batchOp 和位置判定是否通過過濾 */
    private boolean passesBatchFilter(BlockPos pos) {
        if (pos1 == null || pos2 == null) return true;

        int x0 = Math.min(pos1.getX(), pos2.getX());
        int y0 = Math.min(pos1.getY(), pos2.getY());
        int z0 = Math.min(pos1.getZ(), pos2.getZ());
        int x1 = Math.max(pos1.getX(), pos2.getX());
        int y1 = Math.max(pos1.getY(), pos2.getY());
        int z1 = Math.max(pos1.getZ(), pos2.getZ());

        int x = pos.getX(), y = pos.getY(), z = pos.getZ();

        switch (batchOp) {
            case FILL:
                return true;

            case REPLACE:
                // 替換模式：只操作目標方塊
                if (worldOperator != null && replaceTarget != null) {
                    return worldOperator.getBlockState(x, y, z) == replaceTarget;
                }
                return true;

            case HOLLOW:
                // 中空：只保留外殼（任一座標在邊界上）
                return x == x0 || x == x1 || y == y0 || y == y1 || z == z0 || z == z1;

            case WALLS:
                // 牆壁：只保留四面（X 或 Z 在邊界上）
                return x == x0 || x == x1 || z == z0 || z == z1;

            case OUTLINE:
                // 外框：只保留邊（至少兩個座標在邊界上）
                int onEdge = 0;
                if (x == x0 || x == x1) onEdge++;
                if (y == y0 || y == y1) onEdge++;
                if (z == z0 || z == z1) onEdge++;
                return onEdge >= 2;

            case FLOOR:
                return y == y0;

            case CEILING:
                return y == y1;

            default:
                return true;
        }
    }

    // ========================= 執行操作 =========================

    /**
     * 執行放置操作（實際修改世界）。
     *
     * @return 受影響的方塊數
     */
    public int execute() {
        if (worldOperator == null) {
            LOGGER.warn("BRQuickPlacer: worldOperator 未設定，無法執行");
            return 0;
        }

        if (previewCache.isEmpty()) {
            LOGGER.warn("BRQuickPlacer: 預覽為空，無操作");
            return 0;
        }

        long startTime = System.nanoTime();
        List<PlaceRecord> records = new ArrayList<>();

        for (PlacePreview preview : previewCache) {
            BlockState prev = worldOperator.getBlockState(preview.x, preview.y, preview.z);

            if (placeState != null) {
                // 放置模式
                worldOperator.setBlock(preview.x, preview.y, preview.z, placeState);
                records.add(new PlaceRecord(new BlockPos(preview.x, preview.y, preview.z), prev, placeState));
            } else {
                // 移除模式（設為空氣）— 傳 null 表示空氣，由 WorldOperator 實現處理
                worldOperator.setBlock(preview.x, preview.y, preview.z, null);
                records.add(new PlaceRecord(new BlockPos(preview.x, preview.y, preview.z), prev, null));
            }
        }

        long elapsed = (System.nanoTime() - startTime) / 1_000_000;
        lastOperationTimeMs = elapsed;

        // 記錄到 undo 堆疊
        String desc = buildMode.getDisplayName() + " " + batchOp.name() + " (" + records.size() + " blocks)";
        OperationRecord record = new OperationRecord(desc, records);
        undoStack.push(record);
        if (undoStack.size() > MAX_UNDO_DEPTH) {
            ((ArrayDeque<OperationRecord>) undoStack).removeLast();
        }
        redoStack.clear();

        LOGGER.info("BRQuickPlacer 執行: {} — {}ms", desc, elapsed);
        return records.size();
    }

    /**
     * 從選區執行批次操作。
     * 使用 BRSelectionEngine 的選取結果作為操作範圍。
     *
     * @param selection 選取集合
     * @return 受影響的方塊數
     */
    public int executeOnSelection(BRSelectionEngine.SelectionSet selection) {
        if (worldOperator == null || selection == null || selection.isEmpty()) return 0;

        long startTime = System.nanoTime();
        List<PlaceRecord> records = new ArrayList<>();
        int count = 0;

        for (BlockPos pos : selection.positions()) {
            if (count >= MAX_BLOCKS_PER_OPERATION) break;
            if (toolMask != null && !toolMask.test(pos)) continue;

            BlockState prev = worldOperator.getBlockState(pos.getX(), pos.getY(), pos.getZ());

            if (batchOp == BatchOp.REPLACE && replaceTarget != null) {
                if (prev != replaceTarget) continue;
            }

            if (placeState != null) {
                worldOperator.setBlock(pos.getX(), pos.getY(), pos.getZ(), placeState);
            } else {
                worldOperator.setBlock(pos.getX(), pos.getY(), pos.getZ(), null);
            }

            records.add(new PlaceRecord(pos, prev, placeState));
            count++;
        }

        long elapsed = (System.nanoTime() - startTime) / 1_000_000;
        lastOperationTimeMs = elapsed;

        String desc = "Selection " + batchOp.name() + " (" + records.size() + " blocks)";
        OperationRecord record = new OperationRecord(desc, records);
        undoStack.push(record);
        if (undoStack.size() > MAX_UNDO_DEPTH) {
            ((ArrayDeque<OperationRecord>) undoStack).removeLast();
        }
        redoStack.clear();

        LOGGER.info("BRQuickPlacer 選區操作: {} — {}ms", desc, elapsed);
        return records.size();
    }

    // ========================= Undo / Redo =========================

    public void undo() {
        if (undoStack.isEmpty() || worldOperator == null) return;
        OperationRecord record = undoStack.pop();

        // 反向回放
        for (int i = record.records.size() - 1; i >= 0; i--) {
            PlaceRecord pr = record.records.get(i);
            worldOperator.setBlock(pr.pos.getX(), pr.pos.getY(), pr.pos.getZ(), pr.previousState);
        }

        redoStack.push(record);
        LOGGER.info("BRQuickPlacer Undo: {}", record.description);
    }

    public void redo() {
        if (redoStack.isEmpty() || worldOperator == null) return;
        OperationRecord record = redoStack.pop();

        for (PlaceRecord pr : record.records) {
            worldOperator.setBlock(pr.pos.getX(), pr.pos.getY(), pr.pos.getZ(), pr.newState);
        }

        undoStack.push(record);
        LOGGER.info("BRQuickPlacer Redo: {}", record.description);
    }

    public boolean canUndo() { return !undoStack.isEmpty(); }
    public boolean canRedo() { return !redoStack.isEmpty(); }

    // ========================= 渲染資料 =========================

    /** 取得幽靈預覽列表（唯讀） */
    public List<PlacePreview> getPreviewBlocks() {
        return Collections.unmodifiableList(previewCache);
    }

    /** 取得預覽 AABB */
    public AABB getPreviewBounds() {
        if (pos1 == null || pos2 == null) return null;
        int x0 = Math.min(pos1.getX(), pos2.getX());
        int y0 = Math.min(pos1.getY(), pos2.getY());
        int z0 = Math.min(pos1.getZ(), pos2.getZ());
        int x1 = Math.max(pos1.getX(), pos2.getX());
        int y1 = Math.max(pos1.getY(), pos2.getY());
        int z1 = Math.max(pos1.getZ(), pos2.getZ());
        return new AABB(x0, y0, z0, x1 + 1, y1 + 1, z1 + 1);
    }

    /** 取得渲染資料快照 */
    public PlacerRenderData getRenderData() {
        return new PlacerRenderData(
            buildMode,
            batchOp,
            pos1, pos2, mirrorAnchor,
            lastPreviewCount,
            placeState != null,
            getPreviewBounds(),
            lastOperationTimeMs,
            undoStack.size(),
            redoStack.size()
        );
    }

    /** 渲染用不可變資料 */
    public static class PlacerRenderData {
        public final BuildMode buildMode;
        public final BatchOp batchOp;
        public final BlockPos pos1, pos2, mirrorAnchor;
        public final int previewBlockCount;
        public final boolean isPlaceMode;
        public final AABB bounds;
        public final long lastOpTimeMs;
        public final int undoDepth, redoDepth;

        public PlacerRenderData(BuildMode buildMode, BatchOp batchOp,
                                 BlockPos pos1, BlockPos pos2, BlockPos mirrorAnchor,
                                 int previewBlockCount, boolean isPlaceMode, AABB bounds,
                                 long lastOpTimeMs, int undoDepth, int redoDepth) {
            this.buildMode = buildMode;
            this.batchOp = batchOp;
            this.pos1 = pos1;
            this.pos2 = pos2;
            this.mirrorAnchor = mirrorAnchor;
            this.previewBlockCount = previewBlockCount;
            this.isPlaceMode = isPlaceMode;
            this.bounds = bounds;
            this.lastOpTimeMs = lastOpTimeMs;
            this.undoDepth = undoDepth;
            this.redoDepth = redoDepth;
        }
    }

    // ========================= 統計 =========================

    public int getPreviewCount() { return lastPreviewCount; }
    public long getLastOperationTimeMs() { return lastOperationTimeMs; }
    public int getUndoDepth() { return undoStack.size(); }
    public int getRedoDepth() { return redoStack.size(); }

    // ========================= 滾輪半徑調整 =========================

    /** 筆刷半徑（動態，滾輪控制） */
    private int brushRadius = 1;

    /** 最小筆刷半徑 */
    private static final int MIN_BRUSH_RADIUS = 1;

    /** 最大筆刷半徑 */
    private static final int MAX_BRUSH_RADIUS = 32;

    /**
     * 滾輪事件處理 — 動態調整筆刷半徑。
     * 參考 SimpleBuilding 的 tier-based area (3×3 → 9×9)，
     * 但使用連續滾輪取代固定 tier，更靈活。
     *
     * @param delta 滾輪增量（正 = 放大，負 = 縮小）
     * @return true 如果半徑已變更
     */
    public boolean onScrollWheel(int delta) {
        int oldRadius = brushRadius;
        brushRadius = Math.max(MIN_BRUSH_RADIUS, Math.min(MAX_BRUSH_RADIUS, brushRadius + delta));

        if (brushRadius != oldRadius) {
            previewDirty = true;
            return true;
        }
        return false;
    }

    /** 取得目前筆刷半徑 */
    public int getBrushRadius() { return brushRadius; }

    /** 設定筆刷半徑 */
    public void setBrushRadius(int radius) {
        this.brushRadius = Math.max(MIN_BRUSH_RADIUS, Math.min(MAX_BRUSH_RADIUS, radius));
        previewDirty = true;
    }

    // ========================= 右鍵面延伸放置 =========================

    /**
     * 右鍵面延伸 — 點擊方塊面自動沿法線方向延伸放置。
     * 參考 SimpleBuilding 的 right-click face extension。
     *
     * @param hitPos 被點擊的方塊座標
     * @param face 被點擊的面（UP, DOWN, NORTH, SOUTH, EAST, WEST）
     * @param material 放置的方塊材料 ID
     * @param operator 世界操作器
     * @return 成功放置的方塊數
     */
    public int faceExtend(net.minecraft.core.BlockPos hitPos, net.minecraft.core.Direction face,
                           String material, WorldOperator operator) {
        if (hitPos == null || face == null || operator == null) return 0;

        // Note: faceExtend doesn't integrate with the main undo stack
        int placed = 0;

        // 面法線方向
        int dx = face.getStepX();
        int dy = face.getStepY();
        int dz = face.getStepZ();

        // 沿法線放置 brushRadius 層
        for (int layer = 1; layer <= brushRadius; layer++) {
            net.minecraft.core.BlockPos placePos = hitPos.offset(dx * layer, dy * layer, dz * layer);

            // 在放置面的平面上擴展（根據面方向決定擴展軸）
            for (int u = -brushRadius + 1; u < brushRadius; u++) {
                for (int v = -brushRadius + 1; v < brushRadius; v++) {
                    net.minecraft.core.BlockPos expandedPos;
                    if (face.getAxis() == net.minecraft.core.Direction.Axis.Y) {
                        // 水平面 — 在 XZ 平面擴展
                        expandedPos = placePos.offset(u, 0, v);
                    } else if (face.getAxis() == net.minecraft.core.Direction.Axis.X) {
                        // YZ 面擴展
                        expandedPos = placePos.offset(0, u, v);
                    } else {
                        // XY 面擴展
                        expandedPos = placePos.offset(u, v, 0);
                    }

                    // 檢查遮罩
                    if (toolMask != null && !toolMask.test(expandedPos)) continue;

                    // Use setBlock — respect the currently selected placeState
                    try {
                        BlockState stateToPlace = (placeState != null) ? placeState
                            : net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
                        operator.setBlock(expandedPos.getX(), expandedPos.getY(), expandedPos.getZ(),
                                         stateToPlace);
                        placed++;
                    } catch (Exception e) {
                        LOGGER.warn("Failed to place block at {}", expandedPos, e);
                    }
                }
            }
        }

        lastOperationTimeMs = System.currentTimeMillis();
        return placed;
    }
}
