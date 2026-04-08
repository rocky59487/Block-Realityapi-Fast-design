package com.blockreality.api.physics.wind;

/**
 * 風場物理常數 — PFSF-Wind 全域統一。
 */
public final class WindConstants {

    private WindConstants() {}

    /** 海平面空氣密度 (kg/m³) */
    public static final float AIR_DENSITY = 1.225f;

    /** 空氣動力黏度 (Pa·s) */
    public static final float AIR_VISCOSITY = 1.81e-5f;

    /** 預設拖曳係數（鈍體方塊） */
    public static final float DEFAULT_DRAG_COEFF = 1.2f;

    /** 陣風因子（峰值/平均風速比） */
    public static final float GUST_FACTOR = 1.4f;

    /** 壓力投射步擴散率 */
    public static final float PRESSURE_DIFFUSION_RATE = 0.4f;

    /** advection CFL 限制 */
    public static final float CFL_LIMIT = 0.5f;

    /** 每 tick 迭代次數 */
    public static final int DEFAULT_ITERATIONS_PER_TICK = 4;

    /** 風壓公式：q = ½ρv² */
    public static float windPressure(float speed) {
        return 0.5f * AIR_DENSITY * speed * speed;
    }

    /** 風力公式：F = q × Cd × A */
    public static float windForce(float speed, float dragCoeff, float area) {
        return windPressure(speed) * dragCoeff * area;
    }

    /** 預設區域尺寸 */
    public static final int DEFAULT_REGION_SIZE = 64;
}
