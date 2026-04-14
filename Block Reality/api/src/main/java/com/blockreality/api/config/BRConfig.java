package com.blockreality.api.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Block Reality 配置系統 (ForgeConfigSpec)。
 *
 * <p>配置檔會自動生成在 {@code config/blockreality-common.toml}，
 * 支援遊戲內 {@code /config} 指令查看。
 *
 * <h3>參數分類</h3>
 * <ol>
 *   <li>RC Fusion — 鋼筋混凝土融合相關（6 個參數）</li>
 *   <li>Physics Engine — BFS/SPH 引擎限制（4 個參數）</li>
 *   <li>Structure Engine — 結構分析限制（6 個參數）</li>
 *   <li>Performance — 並行/快照（2 個參數）</li>
 *   <li>LOD Physics — 距離分級（3 個參數）</li>
 *   <li>SVO Optimization — 稀疏體素八叉樹（6 個參數）</li>
 *   <li>PFSF — GPU 物理求解器（5 個參數）</li>
 *   <li>Fluid — 流體模擬（3 個參數）</li>
 *   <li>Multi-Domain — 熱/風/EM（6 個參數）</li>
 *   <li>Collapse — 崩塌佇列與排程（5 個參數）</li>
 *   <li>Stability — 傾覆/死區（2 個參數）</li>
 * </ol>
 *
 * <h3>命名慣例</h3>
 * <ul>
 *   <li>縮寫開頭使用小寫：{@code rcFusion*}、{@code sph*}、{@code lod*}、{@code svo*}</li>
 *   <li>縮寫在詞中首字母大寫：{@code renderGpu*}、{@code anchorBfs*}</li>
 *   <li>單位後綴使用大寫：{@code *MB}（Megabytes）、{@code *Ms}（Milliseconds）</li>
 * </ul>
 */
public class BRConfig {

    public static final ForgeConfigSpec SPEC;
    public static final BRConfig INSTANCE;

    // ─── Runtime Physics Control ───
    /** Global physics enabled flag (volatile for thread-safe runtime toggle) */
    private static volatile boolean physicsEnabled = true;

    // ─── RC Fusion 參數 ───

    /** RC 融合：抗拉強度增幅係數 (φ_tens) */
    public final ForgeConfigSpec.DoubleValue rcFusionPhiTens;

    /** RC 融合：抗剪強度增幅係數 (φ_shear) */
    public final ForgeConfigSpec.DoubleValue rcFusionPhiShear;

    /** RC 融合：抗壓強度增幅比例 */
    public final ForgeConfigSpec.DoubleValue rcFusionCompBoost;

    /** RC 融合：鋼筋最大間距 (格數) */
    public final ForgeConfigSpec.IntValue rcFusionRebarSpacingMax;

    /** RC 融合：蜂窩空洞機率 (品質控制) */
    public final ForgeConfigSpec.DoubleValue rcFusionHoneycombProb;

    /** RC 融合：養護時間 (ticks, 2400 = 2分鐘) */
    public final ForgeConfigSpec.IntValue rcFusionCuringTicks;

    // ─── Physics Engine 參數 ───

    /** SPH 異步觸發半徑 (格數) */
    public final ForgeConfigSpec.IntValue sphAsyncTriggerRadius;

    /** SPH 最大粒子數 */
    public final ForgeConfigSpec.IntValue sphMaxParticles;

    /** SPH 爆炸基礎壓力常數 */
    public final ForgeConfigSpec.DoubleValue sphBasePressure;

    /** SPH 平滑長度 h（核心支撐半徑 = 2h，單位：方塊） */
    public final ForgeConfigSpec.DoubleValue sphSmoothingLength;

    /** SPH 靜止密度 ρ₀（粒子均勻分佈時的參考密度） */
    public final ForgeConfigSpec.DoubleValue sphRestDensity;

    /** Anchor BFS 最大搜索深度 */
    public final ForgeConfigSpec.IntValue anchorBfsMaxDepth;

    // ─── Structure Engine 參數 ───

    /** 結構 BFS 最大方塊數 */
    public final ForgeConfigSpec.IntValue structureBfsMaxBlocks;

    /** 結構 BFS 最大執行時間 (ms) */
    public final ForgeConfigSpec.IntValue structureBfsMaxMs;

    /** 快照最大半徑 (格數) */
    public final ForgeConfigSpec.IntValue snapshotMaxRadius;

    /** 掃描邊距 (Scan Margin) 預設格數 */
    public final ForgeConfigSpec.IntValue scanMarginDefault;

    /** ★ T-3: 環路偵測最大追溯深度（LoadPathEngine.wouldCreateCycle） */
    public final ForgeConfigSpec.IntValue cycleDetectMaxDepth;

    // ─── Phase 2: 並行物理引擎參數 ───

    /** ★ Phase 2: 物理執行緒數（0 = 自動，使用 availableProcessors - 2） */
    public final ForgeConfigSpec.IntValue physicsThreadCount;

    /** ★ Phase 1: 快照最大方塊數上限（突破 40³ 限制） */
    public final ForgeConfigSpec.IntValue maxSnapshotBlocks;

    // ─── Phase 4: LOD 物理參數 ───

    /** ★ Phase 4: 完整精度物理的最大距離（格） */
    public final ForgeConfigSpec.IntValue lodFullPrecisionDistance;

    /** ★ Phase 4: 標準精度物理的最大距離（格） */
    public final ForgeConfigSpec.IntValue lodStandardDistance;

    /** ★ Phase 4: 粗略精度物理的最大距離（格） */
    public final ForgeConfigSpec.IntValue lodCoarseDistance;

    // ─── v3.0 SVO 優化參數 ───

    /** ★ v3.0: SVO Section 壓縮閾值（nonAirCount 低於此值時嘗試壓縮） */
    public final ForgeConfigSpec.IntValue svoCompactThreshold;

    /** ★ v3.0: Region 連通性分析間隔（ticks，100 = 5 秒） */
    public final ForgeConfigSpec.IntValue regionAnalysisInterval;

    /** ★ v3.0: Coarse FEM 分析間隔（ticks，20 = 1 秒） */
    public final ForgeConfigSpec.IntValue coarseFEMInterval;

    /** ★ v3.0: 持久化渲染管線 GPU 記憶體上限（MB） */
    public final ForgeConfigSpec.IntValue renderGpuMemoryLimitMB;

    /** ★ v3.0: Greedy Meshing 啟用 */
    public final ForgeConfigSpec.BooleanValue enableGreedyMeshing;

    /** ★ v3.0: Section VBO 渲染距離（格） */
    public final ForgeConfigSpec.IntValue sectionRenderDistance;

    // ─── PFSF GPU 引擎 TOML 參數 ───
    public final ForgeConfigSpec.BooleanValue pfsfEnabledConfig;
    public final ForgeConfigSpec.BooleanValue pfsfPCGEnabledConfig;
    public final ForgeConfigSpec.IntValue     pfsfTickBudgetMsConfig;
    public final ForgeConfigSpec.IntValue     pfsfMaxIslandSizeConfig;
    public final ForgeConfigSpec.IntValue     vramUsagePercentConfig;
    /** PFSF 求解器最大迭代次數（ForceEquilibriumNode 可調） */
    public final ForgeConfigSpec.IntValue     pfsfMaxIterationsConfig;
    /** PFSF 鬆弛因子 ω（ForceEquilibriumNode 可調，1.0–1.95） */
    public final ForgeConfigSpec.DoubleValue  pfsfOmegaConfig;
    /** PFSF 收斂閾值（ForceEquilibriumNode 可調） */
    public final ForgeConfigSpec.DoubleValue  pfsfConvergenceThresholdConfig;

    // ─── 流體引擎 TOML 參數 ───
    public final ForgeConfigSpec.BooleanValue fluidEnabledConfig;
    public final ForgeConfigSpec.IntValue     fluidTickBudgetMsConfig;
    public final ForgeConfigSpec.IntValue     fluidMaxRegionSizeConfig;

    // ─── 多域物理引擎 TOML 參數 ───
    public final ForgeConfigSpec.BooleanValue thermalEnabledConfig;
    public final ForgeConfigSpec.IntValue     thermalTickBudgetMsConfig;
    public final ForgeConfigSpec.BooleanValue windEnabledConfig;
    public final ForgeConfigSpec.IntValue     windTickBudgetMsConfig;
    public final ForgeConfigSpec.BooleanValue emEnabledConfig;
    public final ForgeConfigSpec.IntValue     emTickBudgetMsConfig;

    // ─── 崩塌系統 TOML 參數 ───
    public final ForgeConfigSpec.IntValue     collapseCascadeMaxDepth;
    public final ForgeConfigSpec.IntValue     collapseQueueMaxSize;
    public final ForgeConfigSpec.IntValue     collapseMaxPerTickConfig;
    public final ForgeConfigSpec.IntValue     maxIslandsPerTickConfig;
    public final ForgeConfigSpec.IntValue     evictorMinAgeTicksConfig;
    /** 是否啟用連鎖崩塌（CollapseConfigNode 可調） */
    public final ForgeConfigSpec.BooleanValue cascadeEnabledConfig;

    // ─── 穩定性 TOML 參數 ───
    public final ForgeConfigSpec.BooleanValue overturningEnabledConfig;
    public final ForgeConfigSpec.DoubleValue  stabilityDeadbandConfig;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        INSTANCE = new BRConfig(builder);
        SPEC = builder.build();
    }

    private BRConfig(ForgeConfigSpec.Builder builder) {
        builder.comment("Block Reality API Configuration")
               .push("rc_fusion");

        rcFusionPhiTens = builder
            .comment("RC fusion tensile strength coefficient (φ_tens)")
            .defineInRange("phi_tens", 0.8, 0.0, 2.0);

        rcFusionPhiShear = builder
            .comment("RC fusion shear strength coefficient (φ_shear)")
            .defineInRange("phi_shear", 0.6, 0.0, 2.0);

        rcFusionCompBoost = builder
            .comment("RC fusion compressive strength boost ratio")
            .defineInRange("comp_boost", 1.1, 1.0, 3.0);

        rcFusionRebarSpacingMax = builder
            .comment("Maximum rebar spacing for RC fusion (blocks)")
            .defineInRange("rebar_spacing_max", 3, 1, 8);

        rcFusionHoneycombProb = builder
            .comment("Probability of honeycomb void in RC fusion (quality control)")
            .defineInRange("honeycomb_prob", 0.15, 0.0, 1.0);

        rcFusionCuringTicks = builder
            .comment("RC curing time in ticks (2400 = 2 minutes)")
            .defineInRange("curing_ticks", 2400, 0, 72000);

        builder.pop().push("physics_engine");

        sphAsyncTriggerRadius = builder
            .comment("SPH async trigger radius (blocks)")
            .defineInRange("sph_trigger_radius", 5, 1, 32);

        sphMaxParticles = builder
            .comment("SPH maximum particle count")
            .defineInRange("sph_max_particles", 200, 10, 2000);

        sphBasePressure = builder
            .comment("SPH base explosion pressure constant (higher = stronger blast force on blocks)")
            .defineInRange("sph_base_pressure", 10.0, 0.1, 100.0);

        sphSmoothingLength = builder
            .comment("SPH smoothing length h (kernel support radius = 2h, in blocks). "
                + "Controls how far pressure waves propagate between particles.")
            .defineInRange("sph_smoothing_length", 2.5, 1.0, 5.0);

        sphRestDensity = builder
            .comment("SPH rest density rho_0 (reference density when particles are uniformly distributed). "
                + "Lower values make structures more sensitive to density variations.")
            .defineInRange("sph_rest_density", 1.0, 0.1, 5.0);

        anchorBfsMaxDepth = builder
            .comment("Anchor BFS maximum search depth")
            .defineInRange("anchor_bfs_max_depth", 64, 8, 512);

        builder.pop().push("structure_engine");

        structureBfsMaxBlocks = builder
            .comment("Structure BFS maximum block count. Supports large-scale structures up to 500x500x500. Default 2000000 balances coverage with server performance.")
            .defineInRange("bfs_max_blocks", 2000000, 64, 72000000);

        structureBfsMaxMs = builder
            .comment("Structure BFS maximum execution time in ms. Large structures (500x500x500) may need 300-800ms. Analysis is distributed across ticks.")
            .defineInRange("bfs_max_ms", 400, 5, 2000);

        snapshotMaxRadius = builder
            .comment("Snapshot maximum radius (blocks). Set to 250+ to cover 500x500x500 structures.")
            .defineInRange("snapshot_max_radius", 250, 4, 500);

        scanMarginDefault = builder
            .comment("Default scan margin for physics analysis (blocks)")
            .defineInRange("scan_margin_default", 4, 0, 16);

        cycleDetectMaxDepth = builder
            .comment("T-3: Max parent chain depth for cycle detection in support tree (default 8)")
            .defineInRange("cycle_detect_max_depth", 8, 2, 64);

        builder.pop().push("performance");

        physicsThreadCount = builder
            .comment("Phase 2: Physics thread count. 0 = auto (availableProcessors - 2). Range: 0-8.")
            .defineInRange("physics_thread_count", 0, 0, 8);

        maxSnapshotBlocks = builder
            .comment("Phase 1: Maximum snapshot blocks. Raised to 1M to support 500x500x500 structures via SVO extraction.")
            .defineInRange("max_snapshot_blocks", 1048576, 65536, 8388608);

        lodFullPrecisionDistance = builder
            .comment("Phase 4: Maximum distance (blocks) for full precision physics (BeamStress + ForceEquilibrium)")
            .defineInRange("lod_full_precision_distance", 32, 8, 128);

        lodStandardDistance = builder
            .comment("Phase 4: Maximum distance (blocks) for standard precision physics (SupportPathAnalyzer)")
            .defineInRange("lod_standard_distance", 96, 32, 256);

        lodCoarseDistance = builder
            .comment("Phase 4: Maximum distance (blocks) for coarse physics (LoadPathEngine only)")
            .defineInRange("lod_coarse_distance", 256, 96, 512);

        builder.pop().push("svo_optimization");

        svoCompactThreshold = builder
            .comment("v3.0: SVO section compact threshold. Sections with nonAirCount below this try compression.")
            .defineInRange("svo_compact_threshold", 2048, 1, 4096);

        regionAnalysisInterval = builder
            .comment("v3.0: Region connectivity analysis interval (ticks). 100 = every 5 seconds.")
            .defineInRange("region_analysis_interval", 100, 20, 600);

        coarseFEMInterval = builder
            .comment("v3.0: Coarse FEM stress analysis interval (ticks). 20 = every 1 second.")
            .defineInRange("coarse_fem_interval", 20, 5, 200);

        renderGpuMemoryLimitMB = builder
            .comment("v3.0: Persistent render pipeline GPU memory limit (MB).")
            .defineInRange("render_gpu_memory_limit_mb", 512, 64, 2048);

        enableGreedyMeshing = builder
            .comment("v3.0: Enable greedy meshing for section VBO compilation. Reduces vertex count 60-95%.")
            .define("enable_greedy_meshing", true);

        sectionRenderDistance = builder
            .comment("v3.0: Maximum render distance for section VBOs (blocks).")
            .defineInRange("section_render_distance", 256, 32, 1024);

        builder.pop().push("pfsf");

        pfsfEnabledConfig = builder
            .comment("Enable PFSF GPU physics solver. Set false to fall back to CPU engine.")
            .define("enabled", true);

        pfsfPCGEnabledConfig = builder
            .comment("Enable hybrid RBGS+PCG solver (reduces iterations ~50%). Requires extra VRAM (3×N×4 bytes per island).")
            .define("pcg_enabled", true);

        pfsfTickBudgetMsConfig = builder
            .comment("Maximum GPU compute time per tick (ms). Must stay below 45 to avoid TPS lag.")
            .defineInRange("tick_budget_ms", 15, 1, 45);

        pfsfMaxIslandSizeConfig = builder
            .comment("Maximum island block count before marking DORMANT. Supports up to 2M blocks.")
            .defineInRange("max_island_size", 1000000, 100, 2000000);

        vramUsagePercentConfig = builder
            .comment("Percentage of detected VRAM to allocate for physics buffers (30-80%).")
            .defineInRange("vram_usage_percent", 60, 30, 80);

        pfsfMaxIterationsConfig = builder
            .comment("PFSF solver maximum iterations per solve step (ForceEquilibriumNode). Higher = more accurate, more GPU time.")
            .defineInRange("max_iterations", 100, 10, 500);

        pfsfOmegaConfig = builder
            .comment("PFSF RBGS relaxation factor ω (1.0=Gauss-Seidel, 1.25=default, 1.95=max SOR). Affects convergence speed.")
            .defineInRange("omega", 1.25, 1.0, 1.95);

        pfsfConvergenceThresholdConfig = builder
            .comment("PFSF convergence threshold. Solver stops when residual falls below this value.")
            .defineInRange("convergence_threshold", 0.001, 0.0001, 0.1);

        builder.pop().push("fluid");

        fluidEnabledConfig = builder
            .comment("Enable PFSF-Fluid simulation sub-system (opt-in, disabled by default).")
            .define("enabled", false);

        fluidTickBudgetMsConfig = builder
            .comment("Maximum GPU compute time per tick for fluid simulation (ms).")
            .defineInRange("tick_budget_ms", 4, 1, 15);

        fluidMaxRegionSizeConfig = builder
            .comment("Maximum fluid region size per axis (blocks). Larger = more accurate, more VRAM.")
            .defineInRange("max_region_size", 64, 16, 128);

        builder.pop().push("multi_domain");

        thermalEnabledConfig = builder
            .comment("Enable thermal conduction simulation (opt-in).")
            .define("thermal_enabled", false);

        thermalTickBudgetMsConfig = builder
            .comment("Maximum GPU compute time per tick for thermal simulation (ms).")
            .defineInRange("thermal_tick_budget_ms", 3, 1, 10);

        windEnabledConfig = builder
            .comment("Enable wind field simulation (opt-in).")
            .define("wind_enabled", false);

        windTickBudgetMsConfig = builder
            .comment("Maximum GPU compute time per tick for wind simulation (ms).")
            .defineInRange("wind_tick_budget_ms", 3, 1, 10);

        emEnabledConfig = builder
            .comment("Enable electromagnetic field simulation (opt-in).")
            .define("em_enabled", false);

        emTickBudgetMsConfig = builder
            .comment("Maximum GPU compute time per tick for EM simulation (ms).")
            .defineInRange("em_tick_budget_ms", 2, 1, 10);

        builder.pop().push("collapse");

        collapseCascadeMaxDepth = builder
            .comment("Maximum cascade depth for chain collapse propagation. Prevents infinite recursion.")
            .defineInRange("cascade_max_depth", 64, 8, 512);

        collapseQueueMaxSize = builder
            .comment("Maximum collapse queue size. Excess blocks go to overflow buffer and retry next tick.")
            .defineInRange("queue_max_size", 100000, 1000, 1000000);

        collapseMaxPerTickConfig = builder
            .comment("Maximum blocks collapsed per tick. Increase for large explosion events.")
            .defineInRange("max_per_tick", 500, 1, 10000);

        maxIslandsPerTickConfig = builder
            .comment("Maximum island physics calculations per tick (PhysicsScheduler budget).")
            .defineInRange("max_islands_per_tick", 12, 1, 256);

        evictorMinAgeTicksConfig = builder
            .comment("Minimum ticks an island buffer must exist before VRAM eviction eligibility.")
            .defineInRange("evictor_min_age_ticks", 100, 1, 1000);

        cascadeEnabledConfig = builder
            .comment("Enable cascade collapse propagation. When false, collapse stops at first failed block (no chain reaction).")
            .define("cascade_enabled", true);

        builder.pop().push("stability");

        overturningEnabledConfig = builder
            .comment("Enable centre-of-mass overturning physics. When CoM projection leaves support polygon, structure topples.")
            .define("overturning_enabled", true);

        stabilityDeadbandConfig = builder
            .comment("CoM stability deadband ratio (0.0–0.5). CoM must exceed support edge by this fraction to trigger overturning.")
            .defineInRange("stability_deadband", 0.15, 0.0, 0.5);

        builder.pop();
    }

    /**
     * Check if physics engine is enabled at runtime.
     * Can be toggled by /br_physics_toggle command.
     */
    public static boolean isPhysicsEnabled() {
        return physicsEnabled;
    }

    /**
     * Set physics engine enabled state at runtime.
     * Thread-safe for command/event handler usage.
     */
    public static void setPhysicsEnabled(boolean enabled) {
        physicsEnabled = enabled;
    }

    // ═══════════════════════════════════════════════════════════════
    //  M8: PFSF GPU 物理引擎配置
    // ═══════════════════════════════════════════════════════════════

    private static volatile boolean pfsfEnabled = true;
    // ★ 1M-fix: 提高預設值以支援百萬方塊級結構
    private static volatile int pfsfTickBudgetMs = 15;
    private static volatile int pfsfMaxIslandSize = 1_000_000;

    /** PFSF GPU 引擎是否啟用（false 時強制使用 CPU 引擎） */
    public static boolean isPFSFEnabled() {
        return INSTANCE != null ? INSTANCE.pfsfEnabledConfig.get() : pfsfEnabled;
    }
    public static void setPFSFEnabled(boolean enabled) {
        pfsfEnabled = enabled;
        if (INSTANCE != null) INSTANCE.pfsfEnabledConfig.set(enabled);
    }

    /** PFSF 每 tick 最大 GPU 計算時間（毫秒） */
    public static int getPFSFTickBudgetMs() {
        return INSTANCE != null ? INSTANCE.pfsfTickBudgetMsConfig.get() : pfsfTickBudgetMs;
    }
    // ★ 1M-fix: 上限從 30ms 提高到 45ms（50ms tick 的 90%，留餘裕給其他任務）
    public static void setPFSFTickBudgetMs(int ms) {
        pfsfTickBudgetMs = Math.max(1, Math.min(ms, 45));
        if (INSTANCE != null) INSTANCE.pfsfTickBudgetMsConfig.set(pfsfTickBudgetMs);
    }

    /** PFSF 最大 island 方塊數（超過此數標記為 DORMANT） */
    public static int getPFSFMaxIslandSize() {
        return INSTANCE != null ? INSTANCE.pfsfMaxIslandSizeConfig.get() : pfsfMaxIslandSize;
    }
    // ★ 1M-fix: 加入上限 clamp 防止極端值，支援最大 2M 方塊
    public static void setPFSFMaxIslandSize(int size) {
        pfsfMaxIslandSize = Math.max(100, Math.min(size, 2_000_000));
        if (INSTANCE != null) INSTANCE.pfsfMaxIslandSizeConfig.set(pfsfMaxIslandSize);
    }

    // ─── Hybrid RBGS+PCG solver ───
    private static volatile boolean pfsfPCGEnabled = true;

    /**
     * PFSF hybrid RBGS+PCG solver 是否啟用。
     *
     * <p>啟用時，每次求解步驟的前半使用 RBGS（高頻平滑），
     * 後半使用 PCG（低頻收斂），總迭代數減少 ~50%。</p>
     *
     * <p>預設為 true — hybrid solver 在所有情況下都優於純 RBGS。
     * 額外 VRAM 開銷為每 island 3*N*4 bytes（r, p, Ap 向量）。</p>
     */
    public static boolean isPFSFPCGEnabled() {
        return INSTANCE != null ? INSTANCE.pfsfPCGEnabledConfig.get() : pfsfPCGEnabled;
    }
    public static void setPFSFPCGEnabled(boolean enabled) {
        pfsfPCGEnabled = enabled;
        if (INSTANCE != null) INSTANCE.pfsfPCGEnabledConfig.set(enabled);
    }

    // ─── PFSF 求解器精細參數 ───

    private static volatile int    pfsfMaxIterations        = 100;
    private static volatile double pfsfOmega                = 1.25;
    private static volatile double pfsfConvergenceThreshold = 0.001;

    /** PFSF 求解器最大迭代次數（10–500） */
    public static int getPFSFMaxIterations() {
        return INSTANCE != null ? INSTANCE.pfsfMaxIterationsConfig.get() : pfsfMaxIterations;
    }
    public static void setPFSFMaxIterations(int n) {
        pfsfMaxIterations = Math.max(10, Math.min(n, 500));
        if (INSTANCE != null) INSTANCE.pfsfMaxIterationsConfig.set(pfsfMaxIterations);
    }

    /** PFSF RBGS 鬆弛因子 ω（1.0–1.95） */
    public static double getPFSFOmega() {
        return INSTANCE != null ? INSTANCE.pfsfOmegaConfig.get() : pfsfOmega;
    }
    public static void setPFSFOmega(double omega) {
        pfsfOmega = Math.max(1.0, Math.min(omega, 1.95));
        if (INSTANCE != null) INSTANCE.pfsfOmegaConfig.set(pfsfOmega);
    }

    /** PFSF 收斂閾值（0.0001–0.1） */
    public static double getPFSFConvergenceThreshold() {
        return INSTANCE != null ? INSTANCE.pfsfConvergenceThresholdConfig.get() : pfsfConvergenceThreshold;
    }
    public static void setPFSFConvergenceThreshold(double threshold) {
        pfsfConvergenceThreshold = Math.max(0.0001, Math.min(threshold, 0.1));
        if (INSTANCE != null) INSTANCE.pfsfConvergenceThresholdConfig.set(pfsfConvergenceThreshold);
    }

    // ═══════════════════════════════════════════════════════════════
    //  v2: 風壓動態配置
    // ═══════════════════════════════════════════════════════════════

    private static volatile float windSpeed = 0.0f;      // m/s（0 = 無風）
    private static volatile float windDirX = 1.0f;       // 風向 X 分量（正規化）
    private static volatile float windDirZ = 0.0f;       // 風向 Z 分量（正規化）

    public static float getWindSpeed() { return windSpeed; }
    public static float getWindDirX() { return windDirX; }
    public static float getWindDirZ() { return windDirZ; }

    public static void setWindSpeed(float speed) { windSpeed = Math.max(0, Math.min(speed, 100.0f)); }
    public static void setWindDirection(float dirX, float dirZ) {
        float len = (float) Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (len > 1e-6f) { windDirX = dirX / len; windDirZ = dirZ / len; }
    }

    // ═══════════════════════════════════════════════════════════════
    //  PFSF-Fluid 流體模擬配置
    // ═══════════════════════════════════════════════════════════════

    private static volatile boolean fluidEnabled = false;         // 預設關閉（opt-in）
    private static volatile int fluidTickBudgetMs = 4;            // 流體每 tick 預算（ms）
    private static volatile int fluidMaxRegionSize = 64;          // 每軸最大方塊數

    /** 流體模擬是否啟用（預設關閉） */
    public static boolean isFluidEnabled() {
        return INSTANCE != null ? INSTANCE.fluidEnabledConfig.get() : fluidEnabled;
    }
    public static void setFluidEnabled(boolean enabled) {
        fluidEnabled = enabled;
        if (INSTANCE != null) INSTANCE.fluidEnabledConfig.set(enabled);
    }

    /** 流體每 tick 最大 GPU 計算時間（毫秒） */
    public static int getFluidTickBudgetMs() {
        return INSTANCE != null ? INSTANCE.fluidTickBudgetMsConfig.get() : fluidTickBudgetMs;
    }
    public static void setFluidTickBudgetMs(int ms) {
        fluidTickBudgetMs = Math.max(1, Math.min(ms, 15));
        if (INSTANCE != null) INSTANCE.fluidTickBudgetMsConfig.set(fluidTickBudgetMs);
    }

    /** 流體區域每軸最大方塊數 */
    public static int getFluidMaxRegionSize() {
        return INSTANCE != null ? INSTANCE.fluidMaxRegionSizeConfig.get() : fluidMaxRegionSize;
    }
    public static void setFluidMaxRegionSize(int size) {
        fluidMaxRegionSize = Math.max(16, Math.min(size, 128));
        if (INSTANCE != null) INSTANCE.fluidMaxRegionSizeConfig.set(fluidMaxRegionSize);
    }

    // ═══ PFSF-Thermal 熱傳導 ═══

    private static volatile boolean thermalEnabled = false;
    private static volatile int thermalTickBudgetMs = 3;

    public static boolean isThermalEnabled() {
        return INSTANCE != null ? INSTANCE.thermalEnabledConfig.get() : thermalEnabled;
    }
    public static void setThermalEnabled(boolean enabled) {
        thermalEnabled = enabled;
        if (INSTANCE != null) INSTANCE.thermalEnabledConfig.set(enabled);
    }
    public static int getThermalTickBudgetMs() {
        return INSTANCE != null ? INSTANCE.thermalTickBudgetMsConfig.get() : thermalTickBudgetMs;
    }
    public static void setThermalTickBudgetMs(int ms) {
        thermalTickBudgetMs = Math.max(1, Math.min(ms, 10));
        if (INSTANCE != null) INSTANCE.thermalTickBudgetMsConfig.set(thermalTickBudgetMs);
    }

    // ═══ PFSF-Wind 風場 ═══

    private static volatile boolean windEnabled = false;
    private static volatile int windTickBudgetMs = 3;

    public static boolean isWindEnabled() {
        return INSTANCE != null ? INSTANCE.windEnabledConfig.get() : windEnabled;
    }
    public static void setWindEnabled(boolean enabled) {
        windEnabled = enabled;
        if (INSTANCE != null) INSTANCE.windEnabledConfig.set(enabled);
    }
    public static int getWindTickBudgetMs() {
        return INSTANCE != null ? INSTANCE.windTickBudgetMsConfig.get() : windTickBudgetMs;
    }
    public static void setWindTickBudgetMs(int ms) {
        windTickBudgetMs = Math.max(1, Math.min(ms, 10));
        if (INSTANCE != null) INSTANCE.windTickBudgetMsConfig.set(windTickBudgetMs);
    }

    // ═══ PFSF-EM 電磁場 ═══

    private static volatile boolean emEnabled = false;
    private static volatile int emTickBudgetMs = 2;

    public static boolean isEmEnabled() {
        return INSTANCE != null ? INSTANCE.emEnabledConfig.get() : emEnabled;
    }
    public static void setEmEnabled(boolean enabled) {
        emEnabled = enabled;
        if (INSTANCE != null) INSTANCE.emEnabledConfig.set(enabled);
    }
    public static int getEmTickBudgetMs() {
        return INSTANCE != null ? INSTANCE.emTickBudgetMsConfig.get() : emTickBudgetMs;
    }
    public static void setEmTickBudgetMs(int ms) {
        emTickBudgetMs = Math.max(1, Math.min(ms, 10));
        if (INSTANCE != null) INSTANCE.emTickBudgetMsConfig.set(emTickBudgetMs);
    }

    // ═══ 自重傾覆物理（蹺蹺板） ═══

    /**
     * 啟用自重重心傾覆物理。
     * 當 CoM 投影超出支撐多邊形邊緣（含死區）時，結構會自然傾倒。
     * 預設啟用（opt-out）。
     */
    private static volatile boolean overturningEnabled = true;

    /**
     * 傾覆穩定性死區（0.0–0.5）。
     * CoM 必須超出支撐邊緣此比例才觸發傾覆，防止靈敏度過高。
     * 預設 0.15（15%），對應 {@link com.blockreality.api.physics.OverturningStabilityChecker#DEFAULT_DEADBAND}。
     */
    private static volatile double stabilityDeadband = 0.15;

    /** 自重傾覆物理是否啟用 */
    public static boolean isOverturningEnabled() {
        return INSTANCE != null ? INSTANCE.overturningEnabledConfig.get() : overturningEnabled;
    }
    public static void setOverturningEnabled(boolean enabled) {
        overturningEnabled = enabled;
        if (INSTANCE != null) INSTANCE.overturningEnabledConfig.set(enabled);
    }

    /** 傾覆死區比例（0.0–0.5），預設 0.15 */
    public static double getStabilityDeadband() {
        return INSTANCE != null ? INSTANCE.stabilityDeadbandConfig.get() : stabilityDeadband;
    }
    public static void setStabilityDeadband(double deadband) {
        stabilityDeadband = Math.max(0.0, Math.min(deadband, 0.5));
        if (INSTANCE != null) INSTANCE.stabilityDeadbandConfig.set(stabilityDeadband);
    }

    // ═══ VRAM 預算配置（v3: 自動偵測 + 使用者比例） ═══

    // ═══ LOD 物理距離靜態存取器 ═══

    public static int getLodFullPrecisionDistance() { return INSTANCE != null ? INSTANCE.lodFullPrecisionDistance.get() : 32; }
    public static int getLodStandardDistance() { return INSTANCE != null ? INSTANCE.lodStandardDistance.get() : 96; }
    public static int getLodCoarseDistance() { return INSTANCE != null ? INSTANCE.lodCoarseDistance.get() : 256; }

    /** VRAM 使用比例 (30-80%)，預設 60%。VramBudgetManager 根據此值分配預算。 */
    private static volatile int vramUsagePercent = 60;

    /** 取得 VRAM 使用比例 (%) */
    public static int getVramUsagePercent() {
        return INSTANCE != null ? INSTANCE.vramUsagePercentConfig.get() : vramUsagePercent;
    }

    /** 設定 VRAM 使用比例 (30-80%) */
    public static void setVramUsagePercent(int percent) {
        vramUsagePercent = Math.max(30, Math.min(percent, 80));
        if (INSTANCE != null) INSTANCE.vramUsagePercentConfig.set(vramUsagePercent);
    }

    // ═══════════════════════════════════════════════════════════════
    //  P2-A: 排程器 / 崩塌管理器可調整常數
    //  （原先散佈在各類別中的 private static final）
    // ═══════════════════════════════════════════════════════════════

    /** 每 tick 最多處理的 island 數，預設 12（PhysicsScheduler）。 */
    private static volatile int maxIslandsPerTick = 12;

    /** 每 tick 最多實際觸發崩塌的方塊數，預設 500（CollapseManager）。 */
    private static volatile int maxCollapsePerTick = 500;

    /** Island buffer 最小存活 tick 數才會被驅逐，預設 100（IslandBufferEvictor）。 */
    private static volatile long evictorMinAgeTicks = 100;

    /** 每 tick 最多處理的 island 數（12–256） */
    public static int getMaxIslandsPerTick() {
        return INSTANCE != null ? INSTANCE.maxIslandsPerTickConfig.get() : maxIslandsPerTick;
    }
    public static void setMaxIslandsPerTick(int n) {
        maxIslandsPerTick = Math.max(1, Math.min(n, 256));
        if (INSTANCE != null) INSTANCE.maxIslandsPerTickConfig.set(maxIslandsPerTick);
    }

    /** 每 tick 最多觸發崩塌的方塊數（1–10000） */
    public static int getMaxCollapsePerTick() {
        return INSTANCE != null ? INSTANCE.collapseMaxPerTickConfig.get() : maxCollapsePerTick;
    }
    public static void setMaxCollapsePerTick(int n) {
        maxCollapsePerTick = Math.max(1, Math.min(n, 10000));
        if (INSTANCE != null) INSTANCE.collapseMaxPerTickConfig.set(maxCollapsePerTick);
    }

    /** Island buffer 最小存活 tick 數（1–1000） */
    public static long getEvictorMinAgeTicks() {
        return INSTANCE != null ? INSTANCE.evictorMinAgeTicksConfig.get() : evictorMinAgeTicks;
    }
    public static void setEvictorMinAgeTicks(long ticks) {
        evictorMinAgeTicks = Math.max(1, Math.min(ticks, 1000));
        if (INSTANCE != null) INSTANCE.evictorMinAgeTicksConfig.set((int) evictorMinAgeTicks);
    }

    /** 崩塌串聯最大深度（防止無限遞迴），預設 64 */
    public static int getCollapseCascadeMaxDepth() {
        return INSTANCE != null ? INSTANCE.collapseCascadeMaxDepth.get() : 64;
    }

    /** 崩塌佇列最大尺寸，預設 100000 */
    public static int getCollapseQueueMaxSize() {
        return INSTANCE != null ? INSTANCE.collapseQueueMaxSize.get() : 100_000;
    }

    // ─── 連鎖崩塌開關 ───

    private static volatile boolean cascadeEnabled = true;

    /** 連鎖崩塌是否啟用（false 時崩塌不向相鄰方塊傳播） */
    public static boolean isCascadeEnabled() {
        return INSTANCE != null ? INSTANCE.cascadeEnabledConfig.get() : cascadeEnabled;
    }
    public static void setCascadeEnabled(boolean enabled) {
        cascadeEnabled = enabled;
        if (INSTANCE != null) INSTANCE.cascadeEnabledConfig.set(enabled);
    }

    /**
     * @deprecated 由 VramBudgetManager 自動偵測，此方法讀取實際值。
     *
     * <p>★ EIIE-fix: 使用防禦性反射包裝，避免 VulkanComputeContext 初始化失敗
     *    時透過靜態方法呼叫將 ExceptionInInitializerError 傳播到 BRConfig，
     *    導致配置系統也無法載入。
     */
    @Deprecated
    public static int getVramBudgetMB() {
        try {
            // 透過反射呼叫，避免直接觸發 VulkanComputeContext 類別載入
            Class<?> vkCtxClass = Class.forName(
                "com.blockreality.api.physics.pfsf.VulkanComputeContext");
            Object mgr = vkCtxClass.getMethod("getVramBudgetManager").invoke(null);
            if (mgr == null) return 768;
            long budget = (long) mgr.getClass().getMethod("getTotalBudget").invoke(mgr);
            return (int) (budget / (1024 * 1024));
        } catch (Throwable e) {
            return 768; // fallback — Vulkan 不可用或尚未初始化
        }
    }
}
