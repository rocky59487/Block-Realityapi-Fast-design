package com.blockreality.api.blueprint;

import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * 藍圖資料結構 — v3fix §2.3
 *
 * 一個 Blueprint 代表玩家儲存的一份建築藍圖。
 * 包含：尺寸、方塊列表、Union-Find 結構體、元數據。
 *
 * 設計：
 *   - 全部使用相對座標（relX/Y/Z）以支援跨世界載入
 *   - version 欄位支援未來格式遷移
 *   - RMaterial 以 ID 字串儲存，載入時由 DefaultMaterial/DynamicMaterial 重建
 *
 * @since 1.0.0
 */
public class Blueprint {

    public static final int CURRENT_VERSION = 1;

    /** 副檔名 */
    public static final String FILE_EXTENSION = ".brblp";

    // ── 元數據 ──────────────────────────────────────
    private String name;
    private String author;
    private long timestamp;
    private int version = CURRENT_VERSION;

    // ── 尺寸（從原點到最遠角的相對向量） ───────────
    private int sizeX, sizeY, sizeZ;

    // ── 方塊列表 ─────────────────────────────────────
    private final List<BlueprintBlock> blocks = new ArrayList<>();

    // ── 結構體 Union-Find 數據 ───────────────────────
    private final List<BlueprintStructure> structures = new ArrayList<>();

    // ═══════════════════════════════════════════════════════
    //  方塊記錄
    // ═══════════════════════════════════════════════════════

    /**
     * 單一方塊記錄 — 儲存方塊狀態和 R-unit 物理數據。
     */
    public static class BlueprintBlock {
        private int relX, relY, relZ;
        private BlockState blockState;
        private String rMaterialId;
        private int structureId;
        private boolean anchored;
        private float stressLevel;
        private boolean isDynamic;
        private double dynRcomp, dynRtens, dynRshear, dynDensity;

        public int getRelX() { return relX; }
        public int getRelY() { return relY; }
        public int getRelZ() { return relZ; }
        public BlockState getBlockState() { return blockState; }
        public String getRMaterialId() { return rMaterialId; }
        public int getStructureId() { return structureId; }
        public boolean isAnchored() { return anchored; }
        public float getStressLevel() { return stressLevel; }
        public boolean isDynamic() { return isDynamic; }
        public double getDynRcomp() { return dynRcomp; }
        public double getDynRtens() { return dynRtens; }
        public double getDynRshear() { return dynRshear; }
        public double getDynDensity() { return dynDensity; }

        public void setRelPos(int x, int y, int z) { relX = x; relY = y; relZ = z; }
        public void setBlockState(BlockState state) { blockState = state; }
        public void setRMaterialId(String id) { rMaterialId = id; }
        public void setStructureId(int id) { structureId = id; }
        public void setAnchored(boolean v) { anchored = v; }
        public void setStressLevel(float v) { stressLevel = v; }
        public void setDynamic(boolean v) { isDynamic = v; }
        public void setDynRcomp(double v) { dynRcomp = v; }
        public void setDynRtens(double v) { dynRtens = v; }
        public void setDynRshear(double v) { dynRshear = v; }
        public void setDynDensity(double v) { dynDensity = v; }
    }

    // ═══════════════════════════════════════════════════════
    //  結構體記錄
    // ═══════════════════════════════════════════════════════

    public static class BlueprintStructure {
        private int id;
        private float compositeRcomp;
        private float compositeRtens;
        private final List<int[]> anchorPoints = new ArrayList<>();

        public int getId() { return id; }
        public float getCompositeRcomp() { return compositeRcomp; }
        public float getCompositeRtens() { return compositeRtens; }
        public List<int[]> getAnchorPoints() { return anchorPoints; }

        public void setId(int v) { id = v; }
        public void setCompositeRcomp(float v) { compositeRcomp = v; }
        public void setCompositeRtens(float v) { compositeRtens = v; }
    }

    // ═══════════════════════════════════════════════════════
    //  Getter / Setter
    // ═══════════════════════════════════════════════════════

    public String getName() { return name; }
    public String getAuthor() { return author; }
    public long getTimestamp() { return timestamp; }
    public int getVersion() { return version; }
    public int getSizeX() { return sizeX; }
    public int getSizeY() { return sizeY; }
    public int getSizeZ() { return sizeZ; }
    public List<BlueprintBlock> getBlocks() { return blocks; }
    public List<BlueprintStructure> getStructures() { return structures; }

    public void setName(String v) { name = v; }
    public void setAuthor(String v) { author = v; }
    public void setTimestamp(long v) { timestamp = v; }
    public void setVersion(int v) { version = v; }
    public void setSizeX(int v) { sizeX = v; }
    public void setSizeY(int v) { sizeY = v; }
    public void setSizeZ(int v) { sizeZ = v; }

    public int getBlockCount() {
        return blocks.size();
    }

    /**
     * Mirror this blueprint along the given axis and return a new Blueprint.
     * Coordinates are reflected and shifted to remain non-negative.
     *
     * @param axis 'x', 'y', or 'z'
     * @return new mirrored Blueprint
     */
    public Blueprint mirror(char axis) {
        Blueprint result = new Blueprint();
        result.setName(name);
        result.setAuthor(author);
        result.setTimestamp(timestamp);
        result.setSizeX(sizeX);
        result.setSizeY(sizeY);
        result.setSizeZ(sizeZ);

        for (BlueprintBlock src : blocks) {
            BlueprintBlock b = new BlueprintBlock();
            int nx = src.getRelX();
            int ny = src.getRelY();
            int nz = src.getRelZ();
            switch (axis) {
                case 'x' -> nx = (sizeX - 1) - src.getRelX();
                case 'y' -> ny = (sizeY - 1) - src.getRelY();
                case 'z' -> nz = (sizeZ - 1) - src.getRelZ();
            }
            b.setRelPos(nx, ny, nz);
            b.setBlockState(src.getBlockState());
            b.setRMaterialId(src.getRMaterialId());
            b.setStructureId(src.getStructureId());
            b.setAnchored(src.isAnchored());
            b.setStressLevel(src.getStressLevel());
            result.blocks.add(b);
        }
        return result;
    }

    /**
     * Rotate this blueprint around the Y axis and return a new Blueprint.
     * Each rotation is 90° clockwise when viewed from above.
     *
     * @param rotations number of 90° CW rotations (1=90°, 2=180°, 3=270°)
     * @return new rotated Blueprint
     */
    public Blueprint rotateY(int rotations) {
        int r = ((rotations % 4) + 4) % 4;
        Blueprint result = new Blueprint();
        result.setName(name);
        result.setAuthor(author);
        result.setTimestamp(timestamp);

        int newSizeX = (r % 2 == 0) ? sizeX : sizeZ;
        int newSizeZ = (r % 2 == 0) ? sizeZ : sizeX;
        result.setSizeX(newSizeX);
        result.setSizeY(sizeY);
        result.setSizeZ(newSizeZ);

        for (BlueprintBlock src : blocks) {
            BlueprintBlock b = new BlueprintBlock();
            int ox = src.getRelX();
            int oy = src.getRelY();
            int oz = src.getRelZ();
            int nx, nz;
            switch (r) {
                case 1 -> { nx = (sizeZ - 1) - oz; nz = ox; }          // 90° CW
                case 2 -> { nx = (sizeX - 1) - ox; nz = (sizeZ - 1) - oz; } // 180°
                case 3 -> { nx = oz; nz = (sizeX - 1) - ox; }          // 270° CW
                default -> { nx = ox; nz = oz; }                        // 0°
            }
            b.setRelPos(nx, oy, nz);
            b.setBlockState(src.getBlockState());
            b.setRMaterialId(src.getRMaterialId());
            b.setStructureId(src.getStructureId());
            b.setAnchored(src.isAnchored());
            b.setStressLevel(src.getStressLevel());
            result.blocks.add(b);
        }
        return result;
    }
}
