package com.blockreality.api.material;

/**
 * 材料熱學屬性 — 用於 PFSF-Thermal 熱傳導引擎。
 *
 * <p>所有值使用國際單位制（SI）。
 *
 * @param conductivity    熱傳導率 (W/m·K)：鋼 50, 混凝土 1.7, 木材 0.15, 空氣 0.026
 * @param heatCapacity    比熱容 (J/kg·K)：鋼 500, 混凝土 880, 木材 1700, 水 4186
 * @param expansionCoeff  線膨脹係數 (1/K)：鋼 12e-6, 混凝土 10e-6, 木材 5e-6
 */
public record ThermalProfile(
    double conductivity,
    double heatCapacity,
    double expansionCoeff
) {
    // ─── 常用材料的預設值 ───

    public static final ThermalProfile STEEL    = new ThermalProfile(50.0,  500.0,  12e-6);
    public static final ThermalProfile CONCRETE = new ThermalProfile(1.7,   880.0,  10e-6);
    public static final ThermalProfile BRICK    = new ThermalProfile(0.72,  840.0,  6e-6);
    public static final ThermalProfile TIMBER   = new ThermalProfile(0.15,  1700.0, 5e-6);
    public static final ThermalProfile STONE    = new ThermalProfile(2.3,   840.0,  8e-6);
    public static final ThermalProfile GLASS    = new ThermalProfile(1.0,   840.0,  9e-6);
    public static final ThermalProfile SAND     = new ThermalProfile(0.27,  830.0,  12e-6);
    public static final ThermalProfile OBSIDIAN = new ThermalProfile(1.5,   900.0,  7e-6);
    public static final ThermalProfile BEDROCK  = new ThermalProfile(3.5,   1000.0, 1e-8);
    public static final ThermalProfile AIR      = new ThermalProfile(0.026, 1005.0, 3.43e-3);

    /**
     * 計算熱擴散率 α = k / (ρ × c)
     *
     * @param density 材料密度 (kg/m³)
     * @return 熱擴散率 (m²/s)
     */
    public double diffusivity(double density) {
        return conductivity / (density * heatCapacity);
    }

    /**
     * 計算熱應力 σ_th = α_expansion × E × ΔT
     *
     * @param youngsModulus 楊氏模量 (Pa)
     * @param deltaT       溫差 (K)
     * @return 熱應力 (Pa)
     */
    public double thermalStress(double youngsModulus, double deltaT) {
        return expansionCoeff * youngsModulus * deltaT;
    }
}
