package com.blockreality.api.physics.fluid;

/**
 * 流體類型列舉 — PFSF-Fluid 支援的流體種類。
 *
 * <p>每種流體具有不同的密度和黏度，影響擴散速率和靜水壓計算。
 * 當前版本僅完整實作 WATER，OIL/LAVA 預留供後續多相流體擴展。
 *
 * <p>GPU shader 中使用 {@link #getId()} 作為 uint8 辨識碼。
 */
public enum FluidType {

    /** 空氣（無流體） */
    AIR(0, 0.0, 0.0),

    /** 水 — 密度 1000 kg/m³，動力黏度 1.0×10⁻³ Pa·s */
    WATER(1, 1000.0, 1.0e-3),

    /** 油 — 密度 800 kg/m³，動力黏度 0.1 Pa·s（預留） */
    OIL(2, 800.0, 0.1),

    /** 熔岩 — 密度 2500 kg/m³，動力黏度 100.0 Pa·s（預留） */
    LAVA(3, 2500.0, 100.0),

    /** 固體牆面標記（GPU shader 用，非流體） */
    SOLID_WALL(4, 0.0, 0.0);

    private final int id;
    private final double density;     // kg/m³
    private final double viscosity;   // Pa·s (dynamic viscosity)

    FluidType(int id, double density, double viscosity) {
        this.id = id;
        this.density = density;
        this.viscosity = viscosity;
    }

    /** GPU shader 中的 uint8 辨識碼 */
    public int getId() { return id; }

    /** 流體密度 (kg/m³) */
    public double getDensity() { return density; }

    /** 動力黏度 (Pa·s) */
    public double getViscosity() { return viscosity; }

    /** 是否為可流動的流體（排除 AIR 和 SOLID_WALL） */
    public boolean isFlowable() { return id >= 1 && id <= 3; }

    /**
     * 從 GPU shader 的 uint8 值反查 FluidType。
     *
     * @param id shader 中的類型碼
     * @return 對應的 FluidType，未知值返回 AIR
     */
    public static FluidType fromId(int id) {
        for (FluidType type : values()) {
            if (type.id == id) return type;
        }
        return AIR;
    }
}
