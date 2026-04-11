package com.blockreality.api.physics.fluid;

/**
 * 每體素流體狀態 — PFSF-Fluid 的最小資料單元。
 *
 * <p>此為不可變記錄，代表單一體素在某時刻的流體快照。
 * GPU 端使用 SoA (Structure of Arrays) 布局以利合併存取，
 * 此 Java record 僅供 CPU 端查詢和測試使用。
 *
 * <h3>欄位說明</h3>
 * <ul>
 *   <li>{@code type} — 流體種類（AIR/WATER/SOLID_WALL 等）</li>
 *   <li>{@code volume} — 體積分率 [0, 1]，0 = 空氣，1 = 完全充滿（block-level alias）</li>
 *   <li>{@code pressure} — 靜水壓 (Pa) = ρ·g·h</li>
 *   <li>{@code potential} — 流體勢能（Jacobi 求解的主變量，legacy）</li>
 *   <li>{@code vx, vy, vz} — block-averaged 速度 (m/s)（NS / ML 路徑填入）</li>
 * </ul>
 */
public record FluidState(
    FluidType type,
    float volume,
    float pressure,
    float potential,
    float vx,
    float vy,
    float vz
) {

    /** 空氣（無流體）的預設狀態 */
    public static final FluidState EMPTY = new FluidState(FluidType.AIR, 0f, 0f, 0f, 0f, 0f, 0f);

    /** 固體牆面的預設狀態 */
    public static final FluidState SOLID = new FluidState(FluidType.SOLID_WALL, 0f, 0f, 0f, 0f, 0f, 0f);

    /**
     * 建立完全充滿水的狀態。
     *
     * @param height 水面高度（用於計算勢能 = ρ·g·h）
     * @return 充滿水的 FluidState
     */
    public static FluidState fullWater(float height) {
        float density = (float) FluidType.WATER.getDensity();
        float g = (float) FluidConstants.GRAVITY;
        float pressure = density * g * height;
        float potential = pressure; // 初始勢能 = 靜水壓
        return new FluidState(FluidType.WATER, 1.0f, pressure, potential, 0f, 0f, 0f);
    }

    /** 此體素是否含有可流動的流體 */
    public boolean hasFluid() {
        return type.isFlowable() && volume > FluidConstants.MIN_VOLUME_FRACTION;
    }

    /** 此體素是否為固體牆面 */
    public boolean isSolid() {
        return type == FluidType.SOLID_WALL;
    }
}
