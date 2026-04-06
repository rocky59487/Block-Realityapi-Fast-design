package com.blockreality.api.physics.fluid;

import com.blockreality.api.physics.PhysicsConstants;

/**
 * 流體物理常數 — PFSF-Fluid 全域統一。
 *
 * <p>所有常數使用國際單位制（SI），與 Block Reality 結構引擎一致。
 * GPU compute shader 中的 push constants 從此處取值。
 *
 * <h3>核心方程</h3>
 * <pre>
 * 總勢能: φ_total = φ_fluid + ρ·g·h
 * 擴散:   φ_new(i) = Σ_j(outflow_ij · φ_j) / Σ_j(outflow_ij)
 * 靜水壓: P = ρ · g · h_fluid
 * </pre>
 */
public final class FluidConstants {

    private FluidConstants() {} // 不可實例化

    // ─── 基礎物理常數 ───

    /** 重力加速度 (m/s²)，與 PhysicsConstants.GRAVITY 一致 */
    public static final double GRAVITY = PhysicsConstants.GRAVITY;

    /** 方塊體積 (m³) — Minecraft 方塊 1m × 1m × 1m */
    public static final double BLOCK_VOLUME = 1.0;

    /** 方塊面積 (m²) — 一面 1m × 1m */
    public static final double BLOCK_FACE_AREA = 1.0;

    /** 物理時間步長 (s/tick)，與結構引擎同步 */
    public static final double TICK_DT = PhysicsConstants.TICK_DT;

    // ─── 流體擴散參數 ───

    /** 預設擴散率 — 控制每次 Jacobi 迭代的流量比例 */
    public static final float DEFAULT_DIFFUSION_RATE = 0.25f;

    /** 最大擴散率上限（防止數值不穩定） */
    public static final float MAX_DIFFUSION_RATE = 0.45f;

    /** 阻尼因子 — 每步微量衰減，防止振盪 */
    public static final float DAMPING_FACTOR = 0.998f;

    /** 收斂閾值 — 勢場最大變化量低於此值視為穩態 */
    public static final float CONVERGENCE_THRESHOLD = 1e-4f;

    // ─── Jacobi 迭代控制 ───

    /** 每 tick 預設 Jacobi 迭代次數 */
    public static final int DEFAULT_ITERATIONS_PER_TICK = 4;

    /** 每 tick 最大 Jacobi 迭代次數（預算充足時） */
    public static final int MAX_ITERATIONS_PER_TICK = 8;

    /** 每 tick 最小 Jacobi 迭代次數（預算緊張時降級） */
    public static final int MIN_ITERATIONS_PER_TICK = 1;

    // ─── 區域管理 ───

    /** 預設流體區域尺寸（每軸方塊數） */
    public static final int DEFAULT_REGION_SIZE = 64;

    /** 最小流體區域尺寸 */
    public static final int MIN_REGION_SIZE = 16;

    /** 最大流體區域尺寸（VRAM 限制） */
    public static final int MAX_REGION_SIZE = 128;

    /** 每區域最大體素數（128³） */
    public static final int MAX_VOXELS_PER_REGION = MAX_REGION_SIZE * MAX_REGION_SIZE * MAX_REGION_SIZE;

    /** 流體區域啟動距離（格）— 距離玩家多遠時啟動流體模擬 */
    public static final int REGION_ACTIVATION_DISTANCE = 96;

    /** 流體區域休眠距離（格）— 超過此距離停止模擬 */
    public static final int REGION_DORMANT_DISTANCE = 128;

    // ─── GPU 管線控制 ───

    /** GPU Workgroup 尺寸（每軸，8×8×8 = 512 threads） */
    public static final int WORKGROUP_SIZE = 8;

    /** 共用記憶體 tile 尺寸（含 halo，(8+2)³ = 1000 floats） */
    public static final int SHARED_TILE_SIZE = WORKGROUP_SIZE + 2;

    /** 流體同步封包間隔（ticks，10 = 0.5 秒） */
    public static final int SYNC_INTERVAL_TICKS = 10;

    // ─── 結構耦合 ───

    /** 壓力耦合係數 — 流體壓力轉換為 PFSF source term 的比例 */
    public static final float PRESSURE_COUPLING_FACTOR = 1.0f;

    /** 最小耦合壓力閾值（Pa）— 低於此值不注入 PFSF */
    public static final float MIN_COUPLING_PRESSURE = 100.0f;

    // ─── 數值安全 ───

    /** 最小體積分率 — 低於此值視為空氣 */
    public static final float MIN_VOLUME_FRACTION = 1e-6f;

    /** 最大勢能值 — 超過此值觸發 NaN 保護 */
    public static final float MAX_PHI_VALUE = 1e8f;

    /** 負體積保護 — outflow 不可使體積低於 0 */
    public static final float VOLUME_FLOOR = 0.0f;
}
