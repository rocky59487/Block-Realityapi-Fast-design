package com.blockreality.api.physics;

/**
 * 共用物理常數 — Block Reality 全域統一。
 *
 * ★ review-fix #12: 將散落在 CableNode、ForceEquilibriumSolver 等處的
 * 硬編碼常數提取到單一位置，避免日後調整時遺漏。
 */
public final class PhysicsConstants {

    private PhysicsConstants() {} // 不可實例化

    /** 重力加速度 (m/s²) — Minecraft 的 1 格 = 1 米 */
    public static final double GRAVITY = 9.81;

    /** 方塊截面積 (m²) — Minecraft 方塊 1m × 1m */
    public static final double BLOCK_AREA = 1.0;

    /** 完整方塊截面慣性矩 (m⁴) — 1m × 1m 正方形: I = b⁴/12 */
    public static final double FULL_MOMENT_OF_INERTIA = 1.0 / 12.0;

    /** 完整方塊截面模數 (m³) — 1m × 1m 正方形: W = bh²/6 = 1/6 */
    public static final double FULL_SECTION_MODULUS = 1.0 / 6.0;

    /** 子體素網格每軸解析度 */
    public static final int VOXEL_RESOLUTION = 10;

    /** 子體素邊長 (m) */
    public static final double VOXEL_SIZE = 1.0 / VOXEL_RESOLUTION;

    /** 物理時間步長 (seconds per game tick, 20 TPS → 0.05s) */
    public static final double TICK_DT = 0.05;
}
