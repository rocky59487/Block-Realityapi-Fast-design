package com.blockreality.api.physics.em;

/**
 * 電磁場物理常數 — PFSF-EM 全域統一。
 */
public final class EmConstants {

    private EmConstants() {}

    /** 真空介電常數 ε₀ (F/m) */
    public static final double PERMITTIVITY_FREE_SPACE = 8.854188e-12;

    /** 閃電典型能量 (J) — 250 MJ */
    public static final double LIGHTNING_ENERGY = 250e6;

    /** 閃電峰值電流 (A) — 30 kA */
    public static final double LIGHTNING_PEAK_CURRENT = 30000.0;

    /** 閃電持續時間 (s) — 200 μs */
    public static final double LIGHTNING_DURATION = 0.0002;

    /** 閃電等離子體溫度 (°C) */
    public static final float LIGHTNING_TEMPERATURE = 30000f;

    /** 擴散率 */
    public static final float DIFFUSION_RATE = 0.4f;

    /** 每 tick 迭代次數 */
    public static final int DEFAULT_ITERATIONS_PER_TICK = 4;

    /** 最小電位差閾值 (V) — 低於此值不觸發閃電 */
    public static final float MIN_LIGHTNING_POTENTIAL = 1000f;

    /** 預設區域尺寸 */
    public static final int DEFAULT_REGION_SIZE = 64;

    /**
     * Joule 熱注入閾值 (W/m³)。
     * P = J²/σ 超過此值時觸發 THERMAL_STRESS 注入 PFSF source[]。
     * 遊戲尺度：1e6 W/m³ ≈ 1 MW/m³（等效鋁線短路功率密度）。
     */
    public static final float JOULE_HEAT_THRESHOLD = 1e6f;
}
