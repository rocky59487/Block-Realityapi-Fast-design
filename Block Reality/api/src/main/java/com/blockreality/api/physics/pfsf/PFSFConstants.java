package com.blockreality.api.physics.pfsf;

/**
 * PFSF 勢場導流物理引擎 — 全域常數。
 *
 * 所有物理值使用工程單位：MPa（強度）、kg/m³（密度）、N（力）。
 * 調參建議值來自 PFSF 工程手冊 v1.2。
 */
public final class PFSFConstants {

    private PFSFConstants() {}

    // ═══════════════════════════════════════════════════════════════
    //  物理常數
    // ═══════════════════════════════════════════════════════════════

    /** 重力加速度 (m/s²) */
    public static final double GRAVITY = 9.81;

    /** 每格方塊體積 (m³)，Minecraft 每格 = 1m³ */
    public static final double BLOCK_VOLUME = 1.0;

    /** 每格方塊截面積 (m²) */
    public static final double BLOCK_AREA = 1.0;

    // ═══════════════════════════════════════════════════════════════
    //  力矩修正（§2.4 距離加壓）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 力臂加壓係數 α。
     * 控制水平距離對源項的放大效果：ρ' = ρ × (1 + α × arm × (1 - archFactor))
     * 推薦初始值 0.20；過大會導致拱橋誤判。
     */
    public static final double MOMENT_ALPHA = 0.20;

    /**
     * 水平傳導率距離衰減係數 β。
     * 每增加 1 格力臂，水平傳導率降低約 β×10%：σ_h' = σ_h / (1 + β × avgArm)
     * 推薦初始值 0.10。
     */
    public static final double MOMENT_BETA = 0.10;

    // ═══════════════════════════════════════════════════════════════
    //  斷裂判定
    // ═══════════════════════════════════════════════════════════════

    /** 無錨孤島的勢能閾值。φ > 此值 → NO_SUPPORT（無接地路徑） */
    public static final float PHI_ORPHAN_THRESHOLD = 1e6f;

    /** 每 tick 最大斷裂數（防無限連鎖） */
    public static final int MAX_FAILURE_PER_TICK = 2000;

    /** 單次破壞事件最大蔓延半徑（格數），超出延遲到下個 tick */
    public static final int MAX_CASCADE_RADIUS = 64;

    // ═══════════════════════════════════════════════════════════════
    //  排程與效能
    // ═══════════════════════════════════════════════════════════════

    /** 每 tick 物理計算預算 (ms) */
    // H7-fix: 從 15ms 降到 8ms（50ms tick 的 16%），避免 SMP 伺服器延遲
    public static final int TICK_BUDGET_MS = 8;

    /** 多重網格 V-Cycle 間隔：每 MG_INTERVAL 個 Jacobi 步跑一次 V-Cycle */
    public static final int MG_INTERVAL = 4;

    /** Chebyshev 暖機步數：前 N 步使用 omega=1（純 Jacobi） */
    public static final int WARMUP_STEPS = 8;

    /** 殘差發散熔斷比率：maxPhi 成長超過此比率觸發 Chebyshev 重啟 */
    public static final float DIVERGENCE_RATIO = 1.5f;

    /** 頻譜半徑安全裕度：rhoSpec = cos(π/Lmax) × SAFETY_MARGIN */
    public static final float SAFETY_MARGIN = 0.95f;

    /** 斷裂掃描間隔：每 N 個 Jacobi 步執行一次 failure_scan */
    public static final int SCAN_INTERVAL = 8;

    /** Island 大小上限：超過此值自動 DORMANT，需手動 /br analyze 觸發 */
    public static final int MAX_ISLAND_SIZE = 50_000;

    // ═══════════════════════════════════════════════════════════════
    //  GPU Compute
    // ═══════════════════════════════════════════════════════════════

    /** Jacobi shader work group 尺寸 X */
    public static final int WG_X = 8;
    /** Jacobi shader work group 尺寸 Y */
    public static final int WG_Y = 8;
    /** Jacobi shader work group 尺寸 Z */
    public static final int WG_Z = 4;

    /** failure_scan shader work group 尺寸 */
    public static final int WG_SCAN = 256;

    // ═══════════════════════════════════════════════════════════════
    //  迭代步數推薦值
    // ═══════════════════════════════════════════════════════════════

    /** 小擾動（單方塊放置/破壞）推薦迭代步數 */
    public static final int STEPS_MINOR = 4;

    /** 新破壞（結構性破壞）推薦迭代步數 */
    public static final int STEPS_MAJOR = 16;

    /** 大規模崩塌推薦迭代步數 */
    public static final int STEPS_COLLAPSE = 32;

    // ═══════════════════════════════════════════════════════════════
    //  體素類型標記（與 GPU type[] buffer 對應）
    // ═══════════════════════════════════════════════════════════════

    /** 空氣：不參與計算 */
    public static final byte VOXEL_AIR = 0;
    /** 固體：正常求解 */
    public static final byte VOXEL_SOLID = 1;
    /** 錨點：Dirichlet BC，φ=0 */
    public static final byte VOXEL_ANCHOR = 2;

    // ═══════════════════════════════════════════════════════════════
    //  斷裂標記（與 GPU fail_flags[] 對應）
    // ═══════════════════════════════════════════════════════════════

    /** 無斷裂 */
    public static final byte FAIL_OK = 0;
    /** 懸臂斷裂：φ > maxPhi */
    public static final byte FAIL_CANTILEVER = 1;
    /** 壓碎：inward flux 超過抗壓強度 */
    public static final byte FAIL_CRUSHING = 2;
    /** 無支撐：φ > PHI_ORPHAN_THRESHOLD */
    public static final byte FAIL_NO_SUPPORT = 3;
    /** 拉力斷裂：outward flux 超過抗拉強度（各向異性 capacity） */
    public static final byte FAIL_TENSION = 4;

    /** 能量衰減因子：每 Jacobi 步 φ 乘以此值（0.5% 衰減） */
    public static final float DAMPING_FACTOR = 0.995f;

    // ═══════════════════════════════════════════════════════════════
    //  6 方向索引（與 conductivity[i*6+dir] 對應）
    // ═══════════════════════════════════════════════════════════════

    /** -X 方向 */
    public static final int DIR_NEG_X = 0;
    /** +X 方向 */
    public static final int DIR_POS_X = 1;
    /** -Y 方向 */
    public static final int DIR_NEG_Y = 2;
    /** +Y 方向 */
    public static final int DIR_POS_Y = 3;
    /** -Z 方向 */
    public static final int DIR_NEG_Z = 4;
    /** +Z 方向 */
    public static final int DIR_POS_Z = 5;
}
