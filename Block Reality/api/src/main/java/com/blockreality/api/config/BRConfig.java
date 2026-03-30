package com.blockreality.api.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Block Reality 配置系統 (ForgeConfigSpec)。
 *
 * 配置檔會自動生成在 config/blockreality-common.toml
 * 支援遊戲內 /config 指令查看。
 *
 * 參數分類：
 *   1. RC Fusion — 鋼筋混凝土融合相關
 *   2. Physics Engine — BFS/SPH 引擎限制
 *   3. Snapshot — 快照範圍限制
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

    /** ★ v3fix: 啟用 ForceEquilibriumSolver 作為備選分析方法（預設關閉） */
    public final ForgeConfigSpec.BooleanValue useForceEquilibrium;

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

        useForceEquilibrium = builder
            .comment("v3fix: Enable ForceEquilibriumSolver as alternative physics analysis (experimental, default false)")
            .define("use_force_equilibrium", false);

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
}
