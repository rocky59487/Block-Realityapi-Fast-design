package com.blockreality.api.physics.thermal;

import com.blockreality.api.physics.PhysicsConstants;

/**
 * 熱傳導物理常數 — PFSF-Thermal 全域統一。
 *
 * <p>所有常數使用國際單位制（SI）。
 *
 * <h3>核心方程</h3>
 * <pre>
 * 熱方程: ∂T/∂t = α∇²T + Q/(ρc)
 * 熱應力: σ_th = α_exp × E × ΔT
 * 熱擴散率: α = k / (ρ × c)
 * </pre>
 */
public final class ThermalConstants {

    private ThermalConstants() {}

    // ─── 環境參數 ───

    /** 環境溫度 (°C) — Minecraft 世界的基準溫度 */
    public static final float AMBIENT_TEMPERATURE = 20.0f;

    /** 火焰溫度 (°C) */
    public static final float FIRE_TEMPERATURE = 1000.0f;

    /** 熔岩溫度 (°C) */
    public static final float LAVA_TEMPERATURE = 1200.0f;

    /** 冰點 (°C) */
    public static final float FREEZE_POINT = 0.0f;

    /** 沸點 (°C) */
    public static final float BOIL_POINT = 100.0f;

    // ─── 擴散參數 ───

    /** 預設熱擴散率倍率 — 加速遊戲時間尺度（真實值太慢） */
    public static final float DIFFUSION_RATE = 0.35f;

    /** 阻尼因子 */
    public static final float DAMPING_FACTOR = 0.998f;

    /** 收斂閾值 */
    public static final float CONVERGENCE_THRESHOLD = 1e-4f;

    /** 每 tick 預設迭代次數 */
    public static final int DEFAULT_ITERATIONS_PER_TICK = 4;

    // ─── 結構耦合 ───

    /** 最小耦合溫差 (°C) — 低於此值不注入 PFSF 熱應力 */
    public static final float MIN_COUPLING_DELTA_T = 50.0f;

    /** 混凝土剝落溫度閾值 (°C) — 超過此值表面微裂 */
    public static final float SPALLING_THRESHOLD = 300.0f;

    /** 鋼材臨界溫度 (°C) — 超過此值強度降至 60% */
    public static final float STEEL_CRITICAL_TEMP = 550.0f;

    // ─── 區域管理 ───

    /** 預設區域尺寸 */
    public static final int DEFAULT_REGION_SIZE = 64;

    /** 最大溫度值 (°C) — 超過觸發 NaN 保護 */
    public static final float MAX_TEMPERATURE = 5000.0f;
}
