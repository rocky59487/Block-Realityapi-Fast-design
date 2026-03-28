package com.blockreality.api.client.render.ui;

import com.blockreality.api.client.render.BRRenderConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Predicate;

/**
 * Axiom 風格高階選取引擎 — 支援 Box / Magic Wand / Lasso / Brush 模式。
 *
 * 主要特性：
 * - 多種選取工具，各有獨立的輸入處理邏輯
 * - Boolean 運算（Union / Intersect / Subtract）允許組合多次選取
 * - Tool Mask 限制操作只影響指定方塊類型
 * - Undo / Redo 歷史堆疊
 * - 選取資料以 BitSet 壓縮儲存（記憶體友善）
 * - 全部座標為世界座標（int），不用浮點
 *
 * 線程安全：僅在客戶端主線程使用，無需同步。
 *
 * @author Block Reality Team
 * @version 1.0
 */
@OnlyIn(Dist.CLIENT)
public class BRSelectionEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(BRSelectionEngine.class);

    // ========================= 列舉 =========================

    /** 選取工具類型 */
    public enum SelectionTool {
        /** 軸對齊方框選取 */
        BOX,
        /** 魔術棒：flood-fill 相同方塊 */
        MAGIC_WAND,
        /** 套索：任意多邊形 2D 投影 */
        LASSO,
        /** 筆刷：球形 / 圓柱形範圍 */
        BRUSH,
        /** 面選取：選取一整個平面上同方塊 */
        FACE
    }

    /** Boolean 運算模式 */
    public enum BooleanMode {
        /** 取代現有選取 */
        REPLACE,
        /** 聯集（加入） */
        UNION,
        /** 交集（只保留重疊） */
        INTERSECT,
        /** 減去（移除重疊） */
        SUBTRACT
    }

    /** 筆刷形狀 */
    public enum BrushShape {
        SPHERE,
        CYLINDER_Y,
        CUBE
    }

    // ========================= 選取資料結構 =========================

    /**
     * 壓縮選取集合 — 使用 HashSet<Long> 儲存被選方塊。
     * Long = BlockPos.asLong()，比 Set<BlockPos> 節省大量記憶體。
     */
    public static class SelectionSet {
        private final Set<Long> selected = new HashSet<>();
        private int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        private int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        public void add(int x, int y, int z) {
            long packed = BlockPos.asLong(x, y, z);
            if (selected.add(packed)) {
                minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
            }
        }

        public void add(BlockPos pos) {
            add(pos.getX(), pos.getY(), pos.getZ());
        }

        public boolean contains(int x, int y, int z) {
            return selected.contains(BlockPos.asLong(x, y, z));
        }

        public boolean contains(BlockPos pos) {
            return contains(pos.getX(), pos.getY(), pos.getZ());
        }

        public void remove(int x, int y, int z) {
            selected.remove(BlockPos.asLong(x, y, z));
            // 注意：移除後 bounds 可能不再精確，但重算太昂貴
            // 只在 clear / recomputeBounds 時重算
        }

        public void clear() {
            selected.clear();
            minX = Integer.MAX_VALUE; minY = Integer.MAX_VALUE; minZ = Integer.MAX_VALUE;
            maxX = Integer.MIN_VALUE; maxY = Integer.MIN_VALUE; maxZ = Integer.MIN_VALUE;
        }

        public int size() { return selected.size(); }
        public boolean isEmpty() { return selected.isEmpty(); }

        /** 取得所有被選位置的迭代器（解包回 BlockPos） */
        public Iterable<BlockPos> positions() {
            return () -> selected.stream().map(BlockPos::of).iterator();
        }

        /** 取得 AABB 包圍盒 */
        public AABB getBounds() {
            if (isEmpty()) return new AABB(0, 0, 0, 0, 0, 0);
            return new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
        }

        /** 深拷貝 */
        public SelectionSet copy() {
            SelectionSet copy = new SelectionSet();
            copy.selected.addAll(this.selected);
            copy.minX = this.minX; copy.minY = this.minY; copy.minZ = this.minZ;
            copy.maxX = this.maxX; copy.maxY = this.maxY; copy.maxZ = this.maxZ;
            return copy;
        }

        /** 重新計算邊界（在大量 remove 後調用） */
        public void recomputeBounds() {
            minX = Integer.MAX_VALUE; minY = Integer.MAX_VALUE; minZ = Integer.MAX_VALUE;
            maxX = Integer.MIN_VALUE; maxY = Integer.MIN_VALUE; maxZ = Integer.MIN_VALUE;
            for (long packed : selected) {
                int x = BlockPos.getX(packed);
                int y = BlockPos.getY(packed);
                int z = BlockPos.getZ(packed);
                minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
            }
        }

        // Boolean 運算
        public void union(SelectionSet other) {
            selected.addAll(other.selected);
            minX = Math.min(minX, other.minX); minY = Math.min(minY, other.minY); minZ = Math.min(minZ, other.minZ);
            maxX = Math.max(maxX, other.maxX); maxY = Math.max(maxY, other.maxY); maxZ = Math.max(maxZ, other.maxZ);
        }

        public void intersect(SelectionSet other) {
            selected.retainAll(other.selected);
            recomputeBounds();
        }

        public void subtract(SelectionSet other) {
            selected.removeAll(other.selected);
            recomputeBounds();
        }
    }

    // ========================= 狀態 =========================

    private static BRSelectionEngine INSTANCE;

    private SelectionTool currentTool = SelectionTool.BOX;
    private BooleanMode booleanMode = BooleanMode.REPLACE;
    private BrushShape brushShape = BrushShape.SPHERE;
    private int brushRadius = 3;

    /** 目前選取結果 */
    private SelectionSet currentSelection = new SelectionSet();

    /** Tool Mask：若非 null，只有通過 predicate 的方塊才會被選中 */
    private Predicate<BlockPos> toolMask = null;

    /** Undo / Redo 歷史 */
    private final Deque<SelectionSet> undoStack = new ArrayDeque<>();
    private final Deque<SelectionSet> redoStack = new ArrayDeque<>();
    private static final int MAX_UNDO_DEPTH = 32;

    /** Box 選取的兩個角落（拖曳中） */
    private BlockPos boxCornerA = null;
    private BlockPos boxCornerB = null;
    private boolean isDragging = false;

    /** Lasso 頂點列表（螢幕座標，2D 投影） */
    private final List<double[]> lassoPoints = new ArrayList<>();

    /** 選取變更監聽器 */
    private SelectionChangeListener changeListener = null;

    // ========================= 介面 =========================

    /** 選取變更回呼 */
    public interface SelectionChangeListener {
        void onSelectionChanged(SelectionSet newSelection, int blockCount);
    }

    /** 世界方塊查詢介面（由外部注入） */
    public interface WorldBlockQuery {
        /** 取得指定位置的方塊 ID（如 "minecraft:stone"） */
        String getBlockId(int x, int y, int z);
        /** 檢查位置是否為空氣 */
        boolean isAir(int x, int y, int z);
    }

    private WorldBlockQuery worldQuery = null;

    // ========================= 初始化 =========================

    public static void init() {
        INSTANCE = new BRSelectionEngine();
        LOGGER.info("BRSelectionEngine 初始化完成");
    }

    public static BRSelectionEngine getInstance() {
        return INSTANCE;
    }

    public static void cleanup() {
        if (INSTANCE != null) {
            INSTANCE.currentSelection.clear();
            INSTANCE.undoStack.clear();
            INSTANCE.redoStack.clear();
            INSTANCE.lassoPoints.clear();
            INSTANCE = null;
            LOGGER.info("BRSelectionEngine 已清理");
        }
    }

    // ========================= 工具切換 =========================

    public void setTool(SelectionTool tool) {
        cancelDrag();
        this.currentTool = tool;
    }

    public SelectionTool getTool() { return currentTool; }

    public void setBooleanMode(BooleanMode mode) { this.booleanMode = mode; }
    public BooleanMode getBooleanMode() { return booleanMode; }

    public void setBrushShape(BrushShape shape) { this.brushShape = shape; }
    public void setBrushRadius(int radius) { this.brushRadius = Math.max(1, Math.min(radius, 64)); }
    public int getBrushRadius() { return brushRadius; }

    public void setWorldQuery(WorldBlockQuery query) { this.worldQuery = query; }
    public void setChangeListener(SelectionChangeListener listener) { this.changeListener = listener; }

    // ========================= Tool Mask =========================

    /** 設置 Tool Mask — 只有通過 predicate 的方塊才會被選中 */
    public void setToolMask(Predicate<BlockPos> mask) { this.toolMask = mask; }

    /** 清除 Tool Mask */
    public void clearToolMask() { this.toolMask = null; }

    /** 檢查位置是否通過 mask */
    private boolean passesMask(int x, int y, int z) {
        if (toolMask == null) return true;
        return toolMask.test(new BlockPos(x, y, z));
    }

    // ========================= Undo / Redo =========================

    /** 儲存目前狀態到 undo 堆疊 */
    private void pushUndo() {
        undoStack.push(currentSelection.copy());
        if (undoStack.size() > MAX_UNDO_DEPTH) {
            // 移除最舊的（底部）
            ((ArrayDeque<SelectionSet>) undoStack).removeLast();
        }
        redoStack.clear();
    }

    public void undo() {
        if (undoStack.isEmpty()) return;
        redoStack.push(currentSelection.copy());
        currentSelection = undoStack.pop();
        notifyChange();
    }

    public void redo() {
        if (redoStack.isEmpty()) return;
        undoStack.push(currentSelection.copy());
        currentSelection = redoStack.pop();
        notifyChange();
    }

    public boolean canUndo() { return !undoStack.isEmpty(); }
    public boolean canRedo() { return !redoStack.isEmpty(); }

    // ========================= 選取操作 =========================

    /** 取得目前選取 */
    public SelectionSet getSelection() { return currentSelection; }

    /** 清除選取 */
    public void clearSelection() {
        pushUndo();
        currentSelection.clear();
        notifyChange();
    }

    /** 反轉選取（在指定範圍內） */
    public void invertSelection(BlockPos from, BlockPos to) {
        pushUndo();
        SelectionSet inverted = new SelectionSet();
        int x0 = Math.min(from.getX(), to.getX());
        int y0 = Math.min(from.getY(), to.getY());
        int z0 = Math.min(from.getZ(), to.getZ());
        int x1 = Math.max(from.getX(), to.getX());
        int y1 = Math.max(from.getY(), to.getY());
        int z1 = Math.max(from.getZ(), to.getZ());
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    if (!currentSelection.contains(x, y, z) && passesMask(x, y, z)) {
                        inverted.add(x, y, z);
                    }
                }
            }
        }
        currentSelection = inverted;
        notifyChange();
    }

    /** 選取全部（指定範圍） */
    public void selectAll(BlockPos from, BlockPos to) {
        pushUndo();
        SelectionSet all = new SelectionSet();
        int x0 = Math.min(from.getX(), to.getX());
        int y0 = Math.min(from.getY(), to.getY());
        int z0 = Math.min(from.getZ(), to.getZ());
        int x1 = Math.max(from.getX(), to.getX());
        int y1 = Math.max(from.getY(), to.getY());
        int z1 = Math.max(from.getZ(), to.getZ());
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    if (passesMask(x, y, z)) {
                        all.add(x, y, z);
                    }
                }
            }
        }
        applyBoolean(all);
        notifyChange();
    }

    // ========================= Box 選取 =========================

    /** 開始 Box 拖曳 */
    public void beginBoxSelect(BlockPos cornerA) {
        this.boxCornerA = cornerA;
        this.boxCornerB = cornerA;
        this.isDragging = true;
    }

    /** 更新 Box 拖曳（滑鼠移動中） */
    public void updateBoxSelect(BlockPos cornerB) {
        if (!isDragging) return;
        this.boxCornerB = cornerB;
    }

    /** 完成 Box 選取 */
    public void finishBoxSelect() {
        if (!isDragging || boxCornerA == null || boxCornerB == null) return;
        isDragging = false;

        pushUndo();
        SelectionSet boxSel = new SelectionSet();
        int x0 = Math.min(boxCornerA.getX(), boxCornerB.getX());
        int y0 = Math.min(boxCornerA.getY(), boxCornerB.getY());
        int z0 = Math.min(boxCornerA.getZ(), boxCornerB.getZ());
        int x1 = Math.max(boxCornerA.getX(), boxCornerB.getX());
        int y1 = Math.max(boxCornerA.getY(), boxCornerB.getY());
        int z1 = Math.max(boxCornerA.getZ(), boxCornerB.getZ());

        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    if (passesMask(x, y, z)) {
                        boxSel.add(x, y, z);
                    }
                }
            }
        }

        applyBoolean(boxSel);
        boxCornerA = null;
        boxCornerB = null;
        notifyChange();
    }

    /** 取消拖曳 */
    public void cancelDrag() {
        isDragging = false;
        boxCornerA = null;
        boxCornerB = null;
        lassoPoints.clear();
    }

    /** 取得拖曳中的預覽邊界（用於渲染） */
    public AABB getDragPreviewBounds() {
        if (!isDragging || boxCornerA == null || boxCornerB == null) return null;
        int x0 = Math.min(boxCornerA.getX(), boxCornerB.getX());
        int y0 = Math.min(boxCornerA.getY(), boxCornerB.getY());
        int z0 = Math.min(boxCornerA.getZ(), boxCornerB.getZ());
        int x1 = Math.max(boxCornerA.getX(), boxCornerB.getX());
        int y1 = Math.max(boxCornerA.getY(), boxCornerB.getY());
        int z1 = Math.max(boxCornerA.getZ(), boxCornerB.getZ());
        return new AABB(x0, y0, z0, x1 + 1, y1 + 1, z1 + 1);
    }

    public boolean isDragging() { return isDragging; }

    // ========================= Magic Wand =========================

    /**
     * 魔術棒選取 — 從指定位置開始 flood-fill 同類方塊。
     *
     * @param origin 起始位置
     * @param maxSpread 最大擴散距離
     */
    public void magicWandSelect(BlockPos origin, int maxSpread) {
        if (worldQuery == null) {
            LOGGER.warn("Magic Wand: worldQuery 未設定");
            return;
        }
        String targetId = worldQuery.getBlockId(origin.getX(), origin.getY(), origin.getZ());
        if (targetId == null) return;

        pushUndo();
        SelectionSet wanded = new SelectionSet();

        // BFS flood-fill
        Queue<BlockPos> frontier = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        frontier.add(origin);
        visited.add(origin.asLong());

        int maxBlocks = maxSpread * maxSpread * maxSpread; // 安全上限
        int count = 0;

        while (!frontier.isEmpty() && count < maxBlocks) {
            BlockPos pos = frontier.poll();
            int px = pos.getX(), py = pos.getY(), pz = pos.getZ();

            if (!passesMask(px, py, pz)) continue;

            String blockId = worldQuery.getBlockId(px, py, pz);
            if (!targetId.equals(blockId)) continue;

            // 距離檢查
            int dx = px - origin.getX();
            int dy = py - origin.getY();
            int dz = pz - origin.getZ();
            if (Math.abs(dx) > maxSpread || Math.abs(dy) > maxSpread || Math.abs(dz) > maxSpread) continue;

            wanded.add(px, py, pz);
            count++;

            // 6-connected 鄰居
            for (int[] d : NEIGHBORS_6) {
                int nx = px + d[0], ny = py + d[1], nz = pz + d[2];
                long packed = BlockPos.asLong(nx, ny, nz);
                if (visited.add(packed)) {
                    frontier.add(new BlockPos(nx, ny, nz));
                }
            }
        }

        applyBoolean(wanded);
        notifyChange();
    }

    private static final int[][] NEIGHBORS_6 = {
        {1,0,0}, {-1,0,0}, {0,1,0}, {0,-1,0}, {0,0,1}, {0,0,-1}
    };

    // ========================= Brush =========================

    /**
     * 筆刷選取 — 以指定中心為原點，依照 brushShape 和 brushRadius 選取。
     */
    public void brushSelect(BlockPos center) {
        pushUndo();
        SelectionSet brushed = new SelectionSet();
        int r = brushRadius;
        int cx = center.getX(), cy = center.getY(), cz = center.getZ();

        for (int x = cx - r; x <= cx + r; x++) {
            for (int y = cy - r; y <= cy + r; y++) {
                for (int z = cz - r; z <= cz + r; z++) {
                    if (!passesMask(x, y, z)) continue;

                    boolean inShape;
                    switch (brushShape) {
                        case SPHERE:
                            int dx = x - cx, dy = y - cy, dz = z - cz;
                            inShape = (dx * dx + dy * dy + dz * dz) <= (r * r);
                            break;
                        case CYLINDER_Y:
                            int dxc = x - cx, dzc = z - cz;
                            inShape = (dxc * dxc + dzc * dzc) <= (r * r);
                            break;
                        case CUBE:
                        default:
                            inShape = true;
                            break;
                    }

                    if (inShape) {
                        brushed.add(x, y, z);
                    }
                }
            }
        }

        applyBoolean(brushed);
        notifyChange();
    }

    // ========================= Lasso =========================

    /** 添加 Lasso 頂點（螢幕座標） */
    public void addLassoPoint(double screenX, double screenY) {
        lassoPoints.add(new double[]{screenX, screenY});
    }

    /**
     * 完成 Lasso 選取 — 需要外部提供投影查詢。
     * 外部將 lassoPoints 投影到世界空間，產生 SelectionSet，傳入此方法。
     */
    public void finishLassoSelect(SelectionSet projected) {
        pushUndo();
        applyBoolean(projected);
        lassoPoints.clear();
        notifyChange();
    }

    public List<double[]> getLassoPoints() {
        return Collections.unmodifiableList(lassoPoints);
    }

    // ========================= Face Select =========================

    /**
     * 面選取 — 選取一整個平面上的同類方塊。
     *
     * @param origin 起始位置
     * @param axis   平面法線方向 ('x', 'y', 'z')
     * @param maxSpread 最大擴散距離
     */
    public void faceSelect(BlockPos origin, char axis, int maxSpread) {
        if (worldQuery == null) return;
        String targetId = worldQuery.getBlockId(origin.getX(), origin.getY(), origin.getZ());
        if (targetId == null) return;

        pushUndo();
        SelectionSet faced = new SelectionSet();
        Queue<long[]> frontier = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();

        int ox = origin.getX(), oy = origin.getY(), oz = origin.getZ();
        frontier.add(new long[]{ox, oy, oz});
        visited.add(BlockPos.asLong(ox, oy, oz));

        // 根據 axis 決定 2D 鄰居方向
        int[][] dirs;
        switch (axis) {
            case 'x': dirs = new int[][]{{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}}; break;
            case 'z': dirs = new int[][]{{1,0,0},{-1,0,0},{0,1,0},{0,-1,0}}; break;
            case 'y':
            default:  dirs = new int[][]{{1,0,0},{-1,0,0},{0,0,1},{0,0,-1}}; break;
        }

        int maxBlocks = maxSpread * maxSpread * 4;
        int count = 0;

        while (!frontier.isEmpty() && count < maxBlocks) {
            long[] pos = frontier.poll();
            int px = (int) pos[0], py = (int) pos[1], pz = (int) pos[2];

            if (!passesMask(px, py, pz)) continue;
            String bid = worldQuery.getBlockId(px, py, pz);
            if (!targetId.equals(bid)) continue;
            if (Math.abs(px - ox) > maxSpread || Math.abs(py - oy) > maxSpread || Math.abs(pz - oz) > maxSpread) continue;

            faced.add(px, py, pz);
            count++;

            for (int[] d : dirs) {
                int nx = px + d[0], ny = py + d[1], nz = pz + d[2];
                // 保持在同平面上
                switch (axis) {
                    case 'x': nx = ox; break;
                    case 'y': ny = oy; break;
                    case 'z': nz = oz; break;
                }
                long packed = BlockPos.asLong(nx, ny, nz);
                if (visited.add(packed)) {
                    frontier.add(new long[]{nx, ny, nz});
                }
            }
        }

        applyBoolean(faced);
        notifyChange();
    }

    // ========================= Boolean 運算 =========================

    /** 根據目前 booleanMode，將 newSel 合併到 currentSelection */
    private void applyBoolean(SelectionSet newSel) {
        switch (booleanMode) {
            case REPLACE:
                currentSelection = newSel;
                break;
            case UNION:
                currentSelection.union(newSel);
                break;
            case INTERSECT:
                currentSelection.intersect(newSel);
                break;
            case SUBTRACT:
                currentSelection.subtract(newSel);
                break;
        }
    }

    // ========================= 擴張 / 收縮 =========================

    /** 擴張選取（向外 N 格） */
    public void expandSelection(int amount) {
        if (currentSelection.isEmpty()) return;
        pushUndo();
        SelectionSet expanded = currentSelection.copy();
        // 迭代 N 次，每次向外擴 1
        for (int i = 0; i < amount; i++) {
            SelectionSet layer = new SelectionSet();
            for (BlockPos pos : expanded.positions()) {
                for (int[] d : NEIGHBORS_6) {
                    int nx = pos.getX() + d[0], ny = pos.getY() + d[1], nz = pos.getZ() + d[2];
                    if (!expanded.contains(nx, ny, nz) && passesMask(nx, ny, nz)) {
                        layer.add(nx, ny, nz);
                    }
                }
            }
            expanded.union(layer);
        }
        currentSelection = expanded;
        notifyChange();
    }

    /** 收縮選取（向內 N 格） */
    public void shrinkSelection(int amount) {
        if (currentSelection.isEmpty()) return;
        pushUndo();
        for (int i = 0; i < amount; i++) {
            SelectionSet toRemove = new SelectionSet();
            for (BlockPos pos : currentSelection.positions()) {
                // 如果任一鄰居不在選取中 → 這是邊界 → 移除
                for (int[] d : NEIGHBORS_6) {
                    int nx = pos.getX() + d[0], ny = pos.getY() + d[1], nz = pos.getZ() + d[2];
                    if (!currentSelection.contains(nx, ny, nz)) {
                        toRemove.add(pos);
                        break;
                    }
                }
            }
            currentSelection.subtract(toRemove);
        }
        notifyChange();
    }

    // ========================= 渲染資料 =========================

    /**
     * 取得渲染需要的選取資料快照。
     * 供 SelectionBoxRenderer 或其他渲染器使用。
     */
    public SelectionRenderData getRenderData() {
        return new SelectionRenderData(
            currentSelection.size(),
            currentSelection.getBounds(),
            isDragging,
            getDragPreviewBounds(),
            currentTool,
            booleanMode,
            brushRadius,
            brushShape
        );
    }

    /** 渲染用資料快照（不可變） */
    public static class SelectionRenderData {
        public final int blockCount;
        public final AABB selectionBounds;
        public final boolean isDragging;
        public final AABB dragPreview;
        public final SelectionTool tool;
        public final BooleanMode booleanMode;
        public final int brushRadius;
        public final BrushShape brushShape;

        public SelectionRenderData(int blockCount, AABB selectionBounds, boolean isDragging,
                                   AABB dragPreview, SelectionTool tool, BooleanMode booleanMode,
                                   int brushRadius, BrushShape brushShape) {
            this.blockCount = blockCount;
            this.selectionBounds = selectionBounds;
            this.isDragging = isDragging;
            this.dragPreview = dragPreview;
            this.tool = tool;
            this.booleanMode = booleanMode;
            this.brushRadius = brushRadius;
            this.brushShape = brushShape;
        }
    }

    // ========================= 統計 =========================

    /** 取得目前選取方塊數 */
    public int getSelectedCount() { return currentSelection.size(); }

    /** 取得 undo 堆疊深度 */
    public int getUndoDepth() { return undoStack.size(); }

    /** 取得 redo 堆疊深度 */
    public int getRedoDepth() { return redoStack.size(); }

    // ========================= 內部工具 =========================

    private void notifyChange() {
        if (changeListener != null) {
            changeListener.onSelectionChanged(currentSelection, currentSelection.size());
        }
    }

    // ========================= 選取資料持久化 =========================

    /**
     * 將目前選取匯出為 NBT CompoundTag（可儲存到檔案或剪貼簿）。
     *
     * @return NBT 標籤，包含所有選取方塊的座標
     */
    public net.minecraft.nbt.CompoundTag saveToNBT() {
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        net.minecraft.nbt.ListTag posList = new net.minecraft.nbt.ListTag();

        for (long packed : currentSelection.getPackedPositions()) {
            net.minecraft.nbt.CompoundTag posTag = new net.minecraft.nbt.CompoundTag();
            net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.of(packed);
            posTag.putInt("x", pos.getX());
            posTag.putInt("y", pos.getY());
            posTag.putInt("z", pos.getZ());
            posList.add(posTag);
        }

        tag.put("positions", posList);
        tag.putString("tool", currentTool.name());
        tag.putString("booleanMode", booleanMode.name());
        tag.putInt("version", 1);
        return tag;
    }

    /**
     * 從 NBT CompoundTag 載入選取。
     *
     * @param tag 之前由 saveToNBT() 產生的標籤
     */
    public void loadFromNBT(net.minecraft.nbt.CompoundTag tag) {
        if (tag == null || !tag.contains("positions")) return;

        pushUndo();
        currentSelection.clear();

        net.minecraft.nbt.ListTag posList = tag.getList("positions", 10); // 10 = CompoundTag
        for (int i = 0; i < posList.size(); i++) {
            net.minecraft.nbt.CompoundTag posTag = posList.getCompound(i);
            net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(
                posTag.getInt("x"), posTag.getInt("y"), posTag.getInt("z"));
            currentSelection.add(pos);
        }

        if (tag.contains("tool")) {
            try { currentTool = SelectionTool.valueOf(tag.getString("tool")); } catch (Exception ignored) {}
        }
        if (tag.contains("booleanMode")) {
            try { booleanMode = BooleanMode.valueOf(tag.getString("booleanMode")); } catch (Exception ignored) {}
        }

        notifyChange();
    }

    // ========================= 複合選取謂詞 =========================

    /**
     * 複合選取謂詞 — 支援遞迴 AND/OR/NOT 組合。
     * 參考 Axiom 的 Boolean selection logic。
     */
    public static class CompoundPredicate implements java.util.function.Predicate<net.minecraft.core.BlockPos> {
        public enum Op { AND, OR, NOT }

        private final Op op;
        private final List<java.util.function.Predicate<net.minecraft.core.BlockPos>> children;

        private CompoundPredicate(Op op, List<java.util.function.Predicate<net.minecraft.core.BlockPos>> children) {
            this.op = op;
            this.children = children;
        }

        /** 建立 AND 組合（所有子條件都必須成立） */
        @SafeVarargs
        public static CompoundPredicate and(java.util.function.Predicate<net.minecraft.core.BlockPos>... predicates) {
            return new CompoundPredicate(Op.AND, List.of(predicates));
        }

        /** 建立 OR 組合（任一子條件成立即可） */
        @SafeVarargs
        public static CompoundPredicate or(java.util.function.Predicate<net.minecraft.core.BlockPos>... predicates) {
            return new CompoundPredicate(Op.OR, List.of(predicates));
        }

        /** 建立 NOT（反轉單一條件） */
        public static CompoundPredicate not(java.util.function.Predicate<net.minecraft.core.BlockPos> predicate) {
            return new CompoundPredicate(Op.NOT, List.of(predicate));
        }

        @Override
        public boolean test(net.minecraft.core.BlockPos pos) {
            return switch (op) {
                case AND -> children.stream().allMatch(p -> p.test(pos));
                case OR -> children.stream().anyMatch(p -> p.test(pos));
                case NOT -> !children.getFirst().test(pos);
            };
        }

        /** 序列化為可讀字串（除錯用） */
        @Override
        public String toString() {
            return op + "(" + children.size() + " 子條件)";
        }
    }

    /**
     * 使用複合謂詞過濾目前選取。
     * 保留通過謂詞的方塊，移除不通過的。
     *
     * @param predicate 複合謂詞
     */
    public void filterByPredicate(java.util.function.Predicate<net.minecraft.core.BlockPos> predicate) {
        if (predicate == null) return;
        pushUndo();

        List<net.minecraft.core.BlockPos> toRemove = new java.util.ArrayList<>();
        for (long packed : currentSelection.getPackedPositions()) {
            net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.of(packed);
            if (!predicate.test(pos)) {
                toRemove.add(pos);
            }
        }
        for (net.minecraft.core.BlockPos pos : toRemove) {
            currentSelection.remove(pos);
        }

        notifyChange();
    }
}
