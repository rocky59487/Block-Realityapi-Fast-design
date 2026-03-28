package com.blockreality.api.chisel;

import com.blockreality.api.physics.PhysicsConstants;

/**
 * 雕刻狀態 — 組合形狀模板與體素網格。
 *
 * 物理屬性分兩種策略：
 *   1. 模板形狀（isTemplate=true）：使用預計算的精確工程截面屬性
 *   2. 自訂雕刻（CUSTOM）：以 1×1 全塊屬性納入計算（保守近似）
 *      僅 fillRatio 反映實際填充率（影響質量計算）
 *
 * @param shape     形狀模板
 * @param voxelGrid 體素網格（模板形狀自動從 shape 生成，自訂形狀由玩家編輯）
 */
public record ChiselState(SubBlockShape shape, VoxelGrid voxelGrid) {

    /** 預設完整方塊（與未雕刻的方塊行為完全一致） */
    public static final ChiselState FULL = new ChiselState(SubBlockShape.FULL, VoxelGrid.full());

    /**
     * 是否為模板形狀（非自訂）。
     */
    public boolean isTemplate() {
        return shape != SubBlockShape.CUSTOM;
    }

    /**
     * 是否為自訂雕刻。
     */
    public boolean isCustom() {
        return shape == SubBlockShape.CUSTOM;
    }

    /**
     * 是否為完整方塊（未雕刻）。
     */
    public boolean isFull() {
        return shape == SubBlockShape.FULL;
    }

    // ─── 物理屬性 ───

    /**
     * 填充率 — 模板取枚舉值，自訂取實際體素計算。
     * 影響質量：mass = density × fillRatio
     */
    public double fillRatio() {
        return isTemplate() ? shape.getFillRatio() : voxelGrid.fillRatio();
    }

    /**
     * 有效截面積 (m²) — 模板取精確值，自訂回傳全塊。
     */
    public double crossSectionArea() {
        return isTemplate() ? shape.getCrossSectionArea() : PhysicsConstants.BLOCK_AREA;
    }

    /**
     * X 軸慣性矩 (m⁴) — 模板取精確值，自訂回傳全塊。
     */
    public double momentOfInertiaX() {
        return isTemplate() ? shape.getMomentOfInertiaX() : PhysicsConstants.FULL_MOMENT_OF_INERTIA;
    }

    /**
     * Y 軸慣性矩 (m⁴) — 模板取精確值，自訂回傳全塊。
     */
    public double momentOfInertiaY() {
        return isTemplate() ? shape.getMomentOfInertiaY() : PhysicsConstants.FULL_MOMENT_OF_INERTIA;
    }

    /**
     * X 軸截面模數 (m³) — 模板取精確值，自訂回傳全塊。
     */
    public double sectionModulusX() {
        return isTemplate() ? shape.getSectionModulusX() : PhysicsConstants.FULL_SECTION_MODULUS;
    }

    /**
     * Y 軸截面模數 (m³) — 模板取精確值，自訂回傳全塊。
     */
    public double sectionModulusY() {
        return isTemplate() ? shape.getSectionModulusY() : PhysicsConstants.FULL_SECTION_MODULUS;
    }

    // ─── 工廠方法 ───

    /**
     * 從模板形狀建立（體素自動生成）。
     */
    public static ChiselState ofShape(SubBlockShape shape) {
        if (shape == SubBlockShape.FULL) return FULL;
        return new ChiselState(shape, VoxelGrid.fromShape(shape));
    }

    /**
     * 從自訂體素建立。
     */
    public static ChiselState ofCustom(VoxelGrid grid) {
        return new ChiselState(SubBlockShape.CUSTOM, grid);
    }
}
