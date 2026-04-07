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
    public static boolean isPFSFEnabled() { return pfsfEnabled; }
    public static void setPFSFEnabled(boolean enabled) { pfsfEnabled = enabled; }

    /** PFSF 每 tick 最大 GPU 計算時間（毫秒） */
    public static int getPFSFTickBudgetMs() { return pfsfTickBudgetMs; }
    // ★ 1M-fix: 上限從 30ms 提高到 45ms（50ms tick 的 90%，留餘裕給其他任務）
    public static void setPFSFTickBudgetMs(int ms) { pfsfTickBudgetMs = Math.max(1, Math.min(ms, 45)); }

    /** PFSF 最大 island 方塊數（超過此數標記為 DORMANT） */
    public static int getPFSFMaxIslandSize() { return pfsfMaxIslandSize; }
    // ★ 1M-fix: 加入上限 clamp 防止極端值，支援最大 2M 方塊
    public static void setPFSFMaxIslandSize(int size) { pfsfMaxIslandSize = Math.max(100, Math.min(size, 2_000_000)); }

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
    public static boolean isFluidEnabled() { return fluidEnabled; }
    public static void setFluidEnabled(boolean enabled) { fluidEnabled = enabled; }

    /** 流體每 tick 最大 GPU 計算時間（毫秒） */
    public static int getFluidTickBudgetMs() { return fluidTickBudgetMs; }
    public static void setFluidTickBudgetMs(int ms) { fluidTickBudgetMs = Math.max(1, Math.min(ms, 15)); }

    /** 流體區域每軸最大方塊數 */
    public static int getFluidMaxRegionSize() { return fluidMaxRegionSize; }
    public static void setFluidMaxRegionSize(int size) { fluidMaxRegionSize = Math.max(16, Math.min(size, 128)); }

    // ═══ PFSF-Thermal 熱傳導 ═══

    private static volatile boolean thermalEnabled = false;
    private static volatile int thermalTickBudgetMs = 3;

    public static boolean isThermalEnabled() { return thermalEnabled; }
    public static void setThermalEnabled(boolean enabled) { thermalEnabled = enabled; }
    public static int getThermalTickBudgetMs() { return thermalTickBudgetMs; }
    public static void setThermalTickBudgetMs(int ms) { thermalTickBudgetMs = Math.max(1, Math.min(ms, 10)); }

    // ═══ PFSF-Wind 風場 ═══

    private static volatile boolean windEnabled = false;
    private static volatile int windTickBudgetMs = 3;

    public static boolean isWindEnabled() { return windEnabled; }
    public static void setWindEnabled(boolean enabled) { windEnabled = enabled; }
    public static int getWindTickBudgetMs() { return windTickBudgetMs; }
    public static void setWindTickBudgetMs(int ms) { windTickBudgetMs = Math.max(1, Math.min(ms, 10)); }

    // ═══ PFSF-EM 電磁場 ═══

    private static volatile boolean emEnabled = false;
    private static volatile int emTickBudgetMs = 2;

    public static boolean isEmEnabled() { return emEnabled; }
    public static void setEmEnabled(boolean enabled) { emEnabled = enabled; }
    public static int getEmTickBudgetMs() { return emTickBudgetMs; }
    public static void setEmTickBudgetMs(int ms) { emTickBudgetMs = Math.max(1, Math.min(ms, 10)); }

    // ═══ VRAM 預算配置（v3: 自動偵測 + 使用者比例） ═══

    /** VRAM 使用比例 (30-80%)，預設 60%。VramBudgetManager 根據此值分配預算。 */
    private static volatile int vramUsagePercent = 60;

    /** 取得 VRAM 使用比例 (%) */
    public static int getVramUsagePercent() { return vramUsagePercent; }

    /** 設定 VRAM 使用比例 (30-80%) */
    public static void setVramUsagePercent(int percent) {
        vramUsagePercent = Math.max(30, Math.min(percent, 80));
    }

    /**
     * @deprecated 由 VramBudgetManager 自動偵測，此方法讀取實際值。
     */
    @Deprecated
    public static int getVramBudgetMB() {
        try {
            return (int) (com.blockreality.api.physics.pfsf.VulkanComputeContext
                    .getVramBudgetManager().getTotalBudget() / (1024 * 1024));
        } catch (Throwable e) {
            return 768; // fallback
        }
    }
}
