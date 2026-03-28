package com.blockreality.api.chisel;

import java.util.HashMap;
import java.util.Map;

/**
 * 子方塊形狀模板 — 預定義的雕刻形狀及其工程截面屬性。
 *
 * 每個模板攜帶結構力學所需的截面參數：
 *   - fillRatio:       填充率（影響質量：mass = density × fillRatio）
 *   - crossSectionArea: 有效截面積 A (m²)（影響抗壓容量）
 *   - momentOfInertiaX/Y: 截面慣性矩 Ix/Iy (m⁴)（影響彎矩抗力）
 *   - sectionModulusX/Y: 截面模數 Wx/Wy (m³)（影響懸臂容量）
 *
 * CUSTOM 形狀使用全塊預設值進行物理計算（保守近似），
 * 僅 fillRatio 反映實際填充率以正確計算質量。
 *
 * 工程公式參考：
 *   正方形截面 b×h: I = bh³/12, W = bh²/6, A = bh
 *   圓形截面 r:     I = πr⁴/4, W = πr³/4, A = πr²
 */
public enum SubBlockShape {

    // ─── 完整方塊 (1m × 1m × 1m) ───
    FULL("full", 1.0,
        1.0,       // A = 1×1
        1.0/12.0,  // Ix = 1⁴/12
        1.0/12.0,  // Iy = 1⁴/12
        1.0/6.0,   // Wx = 1³/6
        1.0/6.0),  // Wy = 1³/6

    // ─── 半磚 (1m × 0.5m) ───
    SLAB_BOTTOM("slab_bottom", 0.5,
        0.5,         // A = 1.0 × 0.5
        0.01042,     // Ix = 1.0 × 0.5³/12 = 0.01042
        0.04167,     // Iy = 0.5 × 1.0³/12 = 0.04167
        0.04167,     // Wx = 1.0 × 0.5²/6 = 0.04167
        0.08333),    // Wy = 0.5 × 1.0²/6 = 0.08333

    SLAB_TOP("slab_top", 0.5,
        0.5, 0.01042, 0.04167, 0.04167, 0.08333),

    // ─── 階梯 (0.75 fill, L 形截面複合) ───
    // 近似為兩段矩形的複合截面
    STAIR_NORTH("stair_north", 0.75,
        0.75,        // A ≈ 0.75
        0.05469,     // Ix (複合 L 截面)
        0.05469,     // Iy
        0.10938,     // Wx
        0.10938),    // Wy

    STAIR_SOUTH("stair_south", 0.75,
        0.75, 0.05469, 0.05469, 0.10938, 0.10938),

    STAIR_EAST("stair_east", 0.75,
        0.75, 0.05469, 0.05469, 0.10938, 0.10938),

    STAIR_WEST("stair_west", 0.75,
        0.75, 0.05469, 0.05469, 0.10938, 0.10938),

    // ─── 柱子 / 圓柱 (r = 0.4m 內接圓，保留邊距) ───
    // A = π×0.4² = 0.5027, I = π×0.4⁴/4 = 0.02011
    PILLAR("pillar", 0.503,
        0.5027,      // A = πr²
        0.02011,     // Ix = πr⁴/4
        0.02011,     // Iy = πr⁴/4
        0.05027,     // Wx = πr³/4
        0.05027),    // Wy = πr³/4

    // ─── 四分之一角塊 (0.5m × 0.5m) ───
    QUARTER_NE("quarter_ne", 0.25,
        0.25,        // A = 0.5 × 0.5
        0.00260,     // Ix = 0.5 × 0.5³/12
        0.00260,     // Iy
        0.01042,     // Wx = 0.5 × 0.5²/6
        0.01042),    // Wy

    QUARTER_NW("quarter_nw", 0.25,
        0.25, 0.00260, 0.00260, 0.01042, 0.01042),

    QUARTER_SE("quarter_se", 0.25,
        0.25, 0.00260, 0.00260, 0.01042, 0.01042),

    QUARTER_SW("quarter_sw", 0.25,
        0.25, 0.00260, 0.00260, 0.01042, 0.01042),

    // ─── 拱段 ───
    ARCH_BOTTOM("arch_bottom", 0.85,
        0.70,        // A（拱底去除弧形空間）
        0.06,        // Ix
        0.06,        // Iy
        0.12,        // Wx
        0.12),       // Wy

    ARCH_TOP("arch_top", 0.85,
        0.70, 0.06, 0.06, 0.12, 0.12),

    // ─── I 型梁 (工字梁截面) ───
    // 翼緣 0.8m×0.2m + 腹板 0.2m×0.6m → A = 2×0.16 + 0.12 = 0.44
    // 比實心矩形效率更高的 Ix（翼緣遠離中性軸）
    BEAM_NS("beam_ns", 0.44,
        0.44,        // A
        0.04167,     // Ix (I 型截面優勢)
        0.01389,     // Iy (弱軸)
        0.08333,     // Wx
        0.02778),    // Wy

    BEAM_EW("beam_ew", 0.44,
        0.44, 0.01389, 0.04167, 0.02778, 0.08333),

    // ─── 自訂雕刻 — 以 1×1 全塊屬性納入物理計算 ───
    CUSTOM("custom", 1.0,
        1.0, 1.0/12.0, 1.0/12.0, 1.0/6.0, 1.0/6.0);

    // ─── 欄位 ───

    private final String serializedName;
    private final double fillRatio;
    private final double crossSectionArea;
    private final double momentOfInertiaX;
    private final double momentOfInertiaY;
    private final double sectionModulusX;
    private final double sectionModulusY;

    /** 快取的體素圖案（lazy init） */
    private transient volatile VoxelGrid cachedGrid;

    SubBlockShape(String serializedName, double fillRatio,
                  double crossSectionArea,
                  double momentOfInertiaX, double momentOfInertiaY,
                  double sectionModulusX, double sectionModulusY) {
        this.serializedName = serializedName;
        this.fillRatio = fillRatio;
        this.crossSectionArea = crossSectionArea;
        this.momentOfInertiaX = momentOfInertiaX;
        this.momentOfInertiaY = momentOfInertiaY;
        this.sectionModulusX = sectionModulusX;
        this.sectionModulusY = sectionModulusY;
    }

    // ─── Getters ───

    public String getSerializedName() { return serializedName; }
    public double getFillRatio() { return fillRatio; }
    public double getCrossSectionArea() { return crossSectionArea; }
    public double getMomentOfInertiaX() { return momentOfInertiaX; }
    public double getMomentOfInertiaY() { return momentOfInertiaY; }
    public double getSectionModulusX() { return sectionModulusX; }
    public double getSectionModulusY() { return sectionModulusY; }

    // ─── O(1) 反序列化查找 ───

    private static final Map<String, SubBlockShape> BY_NAME = new HashMap<>();
    static {
        for (SubBlockShape shape : values()) {
            BY_NAME.put(shape.serializedName, shape);
        }
    }

    /**
     * 從字串反序列化，未知名稱回傳 FULL。
     */
    public static SubBlockShape fromString(String name) {
        return BY_NAME.getOrDefault(name, FULL);
    }

    // ─── 體素圖案生成 ───

    /**
     * 生成此形狀的 10×10×10 體素網格。
     * 結果會被快取，後續呼叫直接回傳同一實例。
     */
    VoxelGrid generateVoxelGrid() {
        if (cachedGrid != null) return cachedGrid;
        synchronized (this) {
            if (cachedGrid != null) return cachedGrid;
            cachedGrid = buildVoxelGrid();
            return cachedGrid;
        }
    }

    private VoxelGrid buildVoxelGrid() {
        VoxelGrid.Builder b = new VoxelGrid.Builder();
        int S = VoxelGrid.SIZE; // 10

        switch (this) {
            case FULL -> b.fillAll();

            case SLAB_BOTTOM -> {
                // 下半部 y=0~4
                for (int y = 0; y < S / 2; y++) b.fillLayer(y, true);
            }
            case SLAB_TOP -> {
                // 上半部 y=5~9
                for (int y = S / 2; y < S; y++) b.fillLayer(y, true);
            }

            case STAIR_NORTH -> {
                // 下半部全填，上半部北半 (z=0~4)
                for (int y = 0; y < S / 2; y++) b.fillLayer(y, true);
                for (int y = S / 2; y < S; y++)
                    for (int z = 0; z < S / 2; z++)
                        for (int x = 0; x < S; x++)
                            b.set(x, y, z, true);
            }
            case STAIR_SOUTH -> {
                for (int y = 0; y < S / 2; y++) b.fillLayer(y, true);
                for (int y = S / 2; y < S; y++)
                    for (int z = S / 2; z < S; z++)
                        for (int x = 0; x < S; x++)
                            b.set(x, y, z, true);
            }
            case STAIR_EAST -> {
                for (int y = 0; y < S / 2; y++) b.fillLayer(y, true);
                for (int y = S / 2; y < S; y++)
                    for (int z = 0; z < S; z++)
                        for (int x = S / 2; x < S; x++)
                            b.set(x, y, z, true);
            }
            case STAIR_WEST -> {
                for (int y = 0; y < S / 2; y++) b.fillLayer(y, true);
                for (int y = S / 2; y < S; y++)
                    for (int z = 0; z < S; z++)
                        for (int x = 0; x < S / 2; x++)
                            b.set(x, y, z, true);
            }

            case PILLAR -> {
                // 圓柱 r=4 voxels（中心 4.5, 4.5）
                double cx = 4.5, cz = 4.5;
                double r2 = 4.0 * 4.0; // radius = 4 voxels
                for (int y = 0; y < S; y++)
                    for (int z = 0; z < S; z++)
                        for (int x = 0; x < S; x++) {
                            double dx = (x + 0.5) - cx;
                            double dz = (z + 0.5) - cz;
                            if (dx * dx + dz * dz <= r2) {
                                b.set(x, y, z, true);
                            }
                        }
            }

            case QUARTER_NE -> {
                for (int y = 0; y < S; y++)
                    for (int z = 0; z < S / 2; z++)
                        for (int x = S / 2; x < S; x++)
                            b.set(x, y, z, true);
            }
            case QUARTER_NW -> {
                for (int y = 0; y < S; y++)
                    for (int z = 0; z < S / 2; z++)
                        for (int x = 0; x < S / 2; x++)
                            b.set(x, y, z, true);
            }
            case QUARTER_SE -> {
                for (int y = 0; y < S; y++)
                    for (int z = S / 2; z < S; z++)
                        for (int x = S / 2; x < S; x++)
                            b.set(x, y, z, true);
            }
            case QUARTER_SW -> {
                for (int y = 0; y < S; y++)
                    for (int z = S / 2; z < S; z++)
                        for (int x = 0; x < S / 2; x++)
                            b.set(x, y, z, true);
            }

            case ARCH_BOTTOM -> {
                // 底部拱段：完整方塊減去頂部的半圓弧形空洞
                b.fillAll();
                double cx = 4.5, cy = 9.5; // 弧心在頂部邊緣
                double r2 = 3.0 * 3.0;
                for (int y = 0; y < S; y++)
                    for (int z = 0; z < S; z++)
                        for (int x = 0; x < S; x++) {
                            double dx = (x + 0.5) - cx;
                            double dy = (y + 0.5) - cy;
                            if (dx * dx + dy * dy <= r2 && y >= S / 2) {
                                b.set(x, y, z, false);
                            }
                        }
            }
            case ARCH_TOP -> {
                // 頂部拱段：完整方塊減去底部的半圓弧形空洞
                b.fillAll();
                double cx = 4.5, cy = -0.5;
                double r2 = 3.0 * 3.0;
                for (int y = 0; y < S; y++)
                    for (int z = 0; z < S; z++)
                        for (int x = 0; x < S; x++) {
                            double dx = (x + 0.5) - cx;
                            double dy = (y + 0.5) - cy;
                            if (dx * dx + dy * dy <= r2 && y < S / 2) {
                                b.set(x, y, z, false);
                            }
                        }
            }

            case BEAM_NS -> {
                // I 型梁 (N-S 方向)：上下翼緣 + 中央腹板
                // 翼緣：x=1~8, y=0~1 和 y=8~9 (全 z)
                // 腹板：x=4~5, y=2~7 (全 z)
                for (int z = 0; z < S; z++) {
                    for (int x = 1; x < 9; x++) {
                        for (int y = 0; y < 2; y++) b.set(x, y, z, true);
                        for (int y = 8; y < 10; y++) b.set(x, y, z, true);
                    }
                    for (int x = 4; x < 6; x++)
                        for (int y = 2; y < 8; y++) b.set(x, y, z, true);
                }
            }
            case BEAM_EW -> {
                // I 型梁 (E-W 方向)：上下翼緣 + 中央腹板
                for (int x = 0; x < S; x++) {
                    for (int z = 1; z < 9; z++) {
                        for (int y = 0; y < 2; y++) b.set(x, y, z, true);
                        for (int y = 8; y < 10; y++) b.set(x, y, z, true);
                    }
                    for (int z = 4; z < 6; z++)
                        for (int y = 2; y < 8; y++) b.set(x, y, z, true);
                }
            }

            case CUSTOM -> b.fillAll(); // 預設全填充，由玩家手動編輯
        }

        return b.build();
    }
}
