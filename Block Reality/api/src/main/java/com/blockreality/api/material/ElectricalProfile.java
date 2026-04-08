package com.blockreality.api.material;

/**
 * 材料電學屬性 — 用於 PFSF-EM 電磁場引擎。
 *
 * @param conductivity     電導率 (S/m)：銅 5.96e7, 鋼 6.99e6, 混凝土 0.01, 木材 1e-14
 * @param dielectricStrength 擊穿電場強度 (V/m)：空氣 3e6, 混凝土 5e6
 * @param resistivity      電阻率 (Ω·m) = 1/conductivity
 */
public record ElectricalProfile(
    double conductivity,
    double dielectricStrength,
    double resistivity
) {
    public static final ElectricalProfile STEEL    = new ElectricalProfile(6.99e6, 1e7,   1.43e-7);
    public static final ElectricalProfile CONCRETE = new ElectricalProfile(0.01,   5e6,   100.0);
    public static final ElectricalProfile TIMBER   = new ElectricalProfile(1e-14,  1e6,   1e14);
    public static final ElectricalProfile GLASS    = new ElectricalProfile(1e-12,  1e7,   1e12);
    public static final ElectricalProfile STONE    = new ElectricalProfile(0.001,  4e6,   1000.0);
    public static final ElectricalProfile SAND     = new ElectricalProfile(0.01,   3e6,   100.0);
    public static final ElectricalProfile OBSIDIAN = new ElectricalProfile(1e-10,  8e6,   1e10);
    public static final ElectricalProfile BEDROCK  = new ElectricalProfile(0.001,  1e8,   1000.0);
    public static final ElectricalProfile WATER    = new ElectricalProfile(0.05,   2e5,   20.0);
    public static final ElectricalProfile COPPER   = new ElectricalProfile(5.96e7, 1.5e7, 1.68e-8);
    public static final ElectricalProfile AIR      = new ElectricalProfile(1e-15,  3e6,   1e15);

    /** Joule 加熱功率密度 P = J²/σ = J² × ρ (W/m³) */
    public double jouleHeatingPower(double currentDensity) {
        return currentDensity * currentDensity * resistivity;
    }
}
