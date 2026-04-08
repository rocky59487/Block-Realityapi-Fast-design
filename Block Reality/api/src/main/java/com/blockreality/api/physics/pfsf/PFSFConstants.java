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

    /**
     * 每 tick 物理計算預算 (ms)。
     * @deprecated 1M-fix: 運行時請使用 {@link com.blockreality.api.config.BRConfig#getPFSFTickBudgetMs()}。
     *             此常數保留作為編譯期預設值文檔。
     */
    @Deprecated
    public static final int TICK_BUDGET_MS = 8;

    /** 多重網格 V-Cycle 間隔：每 MG_INTERVAL 個 Jacobi 步跑一次 V-Cycle */
    public static final int MG_INTERVAL = 4;

    /** Chebyshev 暖機步數：前 N 步使用 omega=1（純 Jacobi） */
    public static final int WARMUP_STEPS = 2;  // v3: 降低（8→2），第 0 步初始猜測 + 第 1 步殘差基線後即加速

    /** 殘差發散熔斷比率：maxPhi 成長超過此比率觸發 Chebyshev 重啟 */
    public static final float DIVERGENCE_RATIO = 1.5f;

    /** 頻譜半徑安全裕度：rhoSpec = cos(π/Lmax) × SAFETY_MARGIN */
    public static final float SAFETY_MARGIN = 0.95f;

    /** 斷裂掃描間隔：每 N 個 Jacobi 步執行一次 failure_scan */
    public static final int SCAN_INTERVAL = 8;

    /**
     * Island 大小上限：超過此值自動 DORMANT。
     * @deprecated 1M-fix: 運行時請使用 {@link com.blockreality.api.config.BRConfig#getPFSFMaxIslandSize()}。
     *             此常數保留作為編譯期預設值文檔。
     */
    @Deprecated
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

    /** Chebyshev omega 上限（防止數值不穩定） */
    public static final float MAX_OMEGA = 1.98f;

    /** Chebyshev omega 遞推分母最小值（防止除零） */
    public static final float OMEGA_DENOM_EPSILON = 0.01f;

    /** Damping 穩定判定閾值（maxPhi 變化 < 此值則關閉 damping） */
    public static final float DAMPING_SETTLE_THRESHOLD = 0.01f;

    // ═════════════════════════════════════════════════════════════��═
    //  v2: 風壓動態源項 (Eurocode 1)
    // ══════��═══════════════════════════��════════════════════════════

    /**
     * 風壓基礎係數：q = 0.5 * rho_air * Cp。
     * 空氣密度 1.225 kg/m³，Cp=1.2（迎風面）。
     * 風壓 = WIND_BASE_PRESSURE * windSpeed^2 (MPa)
     * 1 Pa = 1e-6 MPa
     */
    public static final float WIND_BASE_PRESSURE = 0.5f * 1.225f * 1.2f * 1e-6f;

    /** 迎風面傳導率衰減因子（模擬二極體效應） */
    public static final float WIND_CONDUCTIVITY_DECAY = 0.05f;

    // ═══════════════════════��══════════════════════════════��════════
    //  v2: 隱式 26-connectivity 剪力懲罰
    // ═══��═══════════════════════════════════════════════════════════

    /** 邊鄰居（12 個）剪力懲罰係數 */
    public static final float SHEAR_EDGE_PENALTY = 0.35f;

    /** 角鄰居（8 個）剪力懲罰係數 */
    public static final float SHEAR_CORNER_PENALTY = 0.15f;

    // ═══════════════════════════════════════════════════════════════
    //  v2: Timoshenko 力矩
    // ═══��════════════════════════════════════════��══════════════════

    /** 預設泊松比（混凝土 ~0.2） */
    public static final float DEFAULT_POISSON_RATIO = 0.2f;

    /** 應力同步封包���播半徑（格） */
    public static final double STRESS_SYNC_BROADCAST_RADIUS = 64.0;

    /** 應力同步間隔（每 N tick 同步一次） */
    public static final int STRESS_SYNC_INTERVAL = 10;

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

    // ═══════════════════════════════════════════════════════════════
    //  v2.1: 相場斷裂（Ambati 2015 Hybrid Phase-Field）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 相場正則化長度尺度 l₀（格數）。
     * 控制裂縫帶寬度；依 Kristensen 2020 建議全局固定為 1.5~2.0 blocks，
     * 不依材料設定獨立值（避免相鄰體素通量不連續）。
     */
    public static final float PHASE_FIELD_L0 = 1.5f;

    /**
     * 混凝土臨界能量釋放率 G_c (J/m²)。
     * 依 Ambati 2015 Table 1：plain concrete ≈ 100 J/m²。
     */
    public static final float G_C_CONCRETE = 100.0f;

    /**
     * 鋼材臨界能量釋放率 G_c (J/m²)。
     * 鋼材斷裂韌性 K_Ic ≈ 50 MPa√m → G_c ≈ 50,000 J/m²。
     */
    public static final float G_C_STEEL = 50_000.0f;

    /**
     * 木材臨界能量釋放率 G_c (J/m²)。
     * 木材 ≈ 300 J/m²（介於混凝土與鋼材之間）。
     */
    public static final float G_C_WOOD = 300.0f;

    /**
     * 相場更新鬆弛因子。
     * d_field[i] = mix(d_old, d_new, RELAX_FACTOR)，防止過衝。
     */
    public static final float PHASE_FIELD_RELAX = 0.3f;

    /**
     * 相場斷裂觸發閾值。
     * d_field[i] > 此值 → 寫入 fail_flags 觸發現有崩塌機制。
     */
    public static final float PHASE_FIELD_FRACTURE_THRESHOLD = 0.95f;

    /**
     * 退化函數指數 p（混凝土，脆性）：g(d) = (1-d)^p。
     * p=2 → 脆性快速剛度喪失。
     */
    public static final int PHASE_FIELD_P_CONCRETE = 2;

    /**
     * 退化函數指數 p（鋼材，延性）：g(d) = (1-d)^p。
     * p=4 → 延遲剛度喪失，模擬延展性。
     */
    public static final int PHASE_FIELD_P_STEEL = 4;

    // ═══════════════════════════════════════════════════════════════
    //  v2.1: 上風向傳導率（Upwind Wind Conductivity）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 上風向傳導率偏置係數 k_wind。
     * 上風向：σ' = σ × (1 + k_wind)
     * 下風向：σ' = σ / (1 + k_wind)
     * 取代舊 WIND_CONDUCTIVITY_DECAY 的硬截斷，更符合一階迎風格式。
     */
    public static final float WIND_UPWIND_FACTOR = 0.30f;

    // ═══════════════════════════════════════════════════════════════
    //  v2.1: RBGS 8 色就地迭代
    // ═══════════════════════════════════════════════════════════════

    /**
     * RBGS shader work group 大小（1D flat dispatch）。
     * 與 WG_SCAN 對齊，利用 GPU warp 對齊特性。
     */
    public static final int WG_RBGS = 256;

    /**
     * RBGS 每步迭代的顏色 pass 數。
     * 8-color octree coloring：color = (x%2) | (y%2)<<1 | (z%2)<<2
     * 確保 26-connectivity 下所有鄰居在同一 pass 內絕對獨立。
     */
    public static final int RBGS_COLORS = 8;

    // ═══════════════════════════════════════════════════════════════
    //  v2.1: Morton Tiled 記憶體佈局
    // ═══════════════════════════════════════════════════════════════

    /**
     * Morton micro-block 邊長（格數）。
     * 8×8×8 = 512 體素，對應 9-bit Morton code。
     * 確保整個 micro-block 數據可裝入 GPU L1/L2 Cache。
     */
    public static final int MORTON_BLOCK_SIZE = 8;

    // ═══════════════════════════════════════════════════════════════
    //  v3: 收斂跳過 + 步數縮減 + 相場條件更新
    // ═══════════════════════════════════════════════════════════════

    /** 收斂跳過：phi 相對變化 < 此值時 stableTickCount++ */
    public static final float CONVERGENCE_SKIP_THRESHOLD = 0.01f;

    /** 步數減半：phi 相對變化 < 此值時步數 /= 2 */
    public static final float CONVERGENCE_REDUCE_THRESHOLD = 0.05f;

    /** 穩定 tick 數達此值後完全跳過 island 計算 */
    public static final int STABLE_TICK_SKIP_COUNT = 3;

    /** 穩定 tick 數達此值後跳過 phase-field 演化 */
    public static final int STABLE_TICK_PHASE_FIELD_SKIP = 2;

    /** Early termination：phi 相對變化 < 此值時步數縮至 25% */
    public static final float EARLY_TERM_TIGHT = 0.001f;

    /** Early termination：phi 相對變化 < 此值時步數縮至 50% */
    public static final float EARLY_TERM_LOOSE = 0.01f;

    // ═══════════════════════════════════════════════════════════════
    //  v3: LOD 物理
    // ═══════════════════════════════════════════════════════════════

    public static final int LOD_FULL = 0;
    public static final int LOD_STANDARD = 1;
    public static final int LOD_COARSE = 2;
    public static final int LOD_DORMANT = 3;

    /** DORMANT island 被事件喚醒後維持全精度的 tick 數 */
    public static final int LOD_WAKE_TICKS = 5;
}
