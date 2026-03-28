package com.blockreality.api.client.render;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Block Reality 渲染引擎全域配置 — 不可更動的固化常數。
 *
 * 設計原則：
 *   - 所有數值皆為 compile-time 常數，JIT 會內聯
 *   - 不提供外部配置（固化光影，非使用者可調）
 *   - 分類依 Iris 風格 pass 架構 + Sodium 風格優化參數 + GeckoLib 風格動畫參數
 */
@OnlyIn(Dist.CLIENT)
public final class BRRenderConfig {
    private BRRenderConfig() {}

    // ═══════════════════════════════════════════════════════════════════
    //  Pipeline — Iris 風格 multi-pass 管線
    // ═══════════════════════════════════════════════════════════════════

    /** 陰影貼圖解析度（2048² 平衡品質與效能） */
    public static final int SHADOW_MAP_RESOLUTION = 2048;

    /** 陰影最大距離（方塊） */
    public static final float SHADOW_MAX_DISTANCE = 128.0f;

    /** GBuffer 附件數量（position / normal / albedo / material / emission） */
    public static final int GBUFFER_ATTACHMENT_COUNT = 5;

    /** Composite pass 最大數量 */
    public static final int MAX_COMPOSITE_PASSES = 4;

    /** 啟用 HDR color buffer（GL_RGBA16F） */
    public static final boolean HDR_ENABLED = true;

    /** 啟用 SSAO */
    public static final boolean SSAO_ENABLED = true;

    /** SSAO 取樣核心大小 */
    public static final int SSAO_KERNEL_SIZE = 32;

    /** SSAO 取樣半徑 */
    public static final float SSAO_RADIUS = 0.5f;

    // ─── GTAO（Ground Truth Ambient Occlusion）升級 ──────────
    /** GTAO 模式 — 取代基本 SSAO，提供物理正確的環境遮蔽。
     * 參考 Jimenez 2016 "Practical Real-Time Strategies for Accurate Indirect Occlusion"。
     * 當啟用時，SSAO pass 自動切換為 GTAO 演算法。 */
    public static final boolean GTAO_ENABLED = true;

    /** GTAO 切片數（水平方向取樣）— 值越大品質越高，效能越低 */
    public static final int GTAO_SLICES = 3;

    /** GTAO 每切片步進數 */
    public static final int GTAO_STEPS_PER_SLICE = 4;

    /** GTAO 半徑（世界空間） */
    public static final float GTAO_RADIUS = 1.5f;

    /** GTAO 衰減指數 — 控制遮蔽隨距離的衰減速率 */
    public static final float GTAO_FALLOFF_EXPONENT = 2.0f;

    // ═══════════════════════════════════════════════════════════════════
    //  Optimization — Sodium/Embeddium 風格優化
    // ═══════════════════════════════════════════════════════════════════

    /** Greedy Meshing 最大合併面積（面） */
    public static final int GREEDY_MESH_MAX_AREA = 256;

    /** Section VBO 快取大小上限 */
    public static final int MESH_CACHE_MAX_SECTIONS = 512;

    /** 髒標記更新間隔（tick） */
    public static final int MESH_DIRTY_CHECK_INTERVAL = 2;

    /** Frustum Culling 額外膨脹邊距（方塊） */
    public static final float FRUSTUM_PADDING = 2.0f;

    /** 批次渲染最大頂點數 */
    public static final int BATCH_MAX_VERTICES = 262_144;

    /** 批次渲染最大 draw call 合併數 */
    public static final int BATCH_MAX_MERGE = 64;

    /** LOD 最大渲染距離（方塊）— Distant Horizons / Voxy 風格 */
    public static final double LOD_MAX_DISTANCE = 1024.0;

    /** LOD 層級數（FULL / HIGH / MEDIUM / LOW / MINIMAL） */
    public static final int LOD_LEVEL_COUNT = 5;

    /** LOD 各層距離閾值（方塊） */
    public static final double[] LOD_DISTANCES = { 64.0, 192.0, 384.0, 640.0, 1024.0 };

    /** LOD Section 大小（方塊） */
    public static final int LOD_SECTION_SIZE = 16;

    /** LOD 遲滯距離（防止閃爍，方塊） */
    public static final double LOD_HYSTERESIS = 8.0;

    /** LOD 更新間隔（幀） */
    public static final int LOD_UPDATE_INTERVAL = 4;

    /** LOD VRAM 預算（MB）— Voxy 風格記憶體管理 */
    public static final int LOD_VRAM_BUDGET_MB = 512;

    /** LOD 體素聚合倍率（每層級） */
    public static final int[] LOD_VOXEL_SCALE = { 1, 2, 4, 8, 16 };

    /** LOD 裙邊高度（方塊，防止 T-junction 接縫） */
    public static final float LOD_SKIRT_HEIGHT = 4.0f;

    /** 異步 Mesh 編譯執行緒數（Sodium 風格 — 去耦 mesh 與幀率） */
    public static final int MESH_COMPILE_THREADS = 2;

    // ═══════════════════════════════════════════════════════════════════
    //  Animation — GeckoLib 風格動畫
    // ═══════════════════════════════════════════════════════════════════

    /** 動畫最大骨骼數 */
    public static final int MAX_BONES = 128;

    /** 關鍵幀插值每秒取樣數 */
    public static final int KEYFRAME_SAMPLE_RATE = 60;

    /** 動畫混合交叉淡入長度（tick） */
    public static final int ANIMATION_BLEND_TICKS = 5;

    /** 最大同時播放動畫控制器數 */
    public static final int MAX_ANIMATION_CONTROLLERS = 8;

    // ═══════════════════════════════════════════════════════════════════
    //  Effect — UI / 選框 / 特效
    // ═══════════════════════════════════════════════════════════════════

    /** 選框發光脈衝週期（tick） */
    public static final int SELECTION_GLOW_PERIOD = 40;

    /** 選框發光最小/最大 alpha */
    public static final float SELECTION_GLOW_MIN_ALPHA = 0.3f;
    public static final float SELECTION_GLOW_MAX_ALPHA = 0.8f;

    /** 放置特效粒子數 */
    public static final int PLACEMENT_FX_PARTICLE_COUNT = 12;

    /** 放置特效持續時間（tick） */
    public static final int PLACEMENT_FX_DURATION = 10;

    /** HUD 覆蓋層最大渲染元素 */
    public static final int HUD_MAX_ELEMENTS = 64;

    /** 結構破壞特效最大碎片數 */
    public static final int COLLAPSE_FX_MAX_FRAGMENTS = 256;

    // ═══════════════════════════════════════════════════════════════════
    //  Shader — 固化光影參數
    // ═══════════════════════════════════════════════════════════════════

    /** PBR 材質金屬度預設值 */
    public static final float PBR_DEFAULT_METALLIC = 0.0f;

    /** PBR 材質粗糙度預設值 */
    public static final float PBR_DEFAULT_ROUGHNESS = 0.5f;

    /** 環境光遮蔽強度 */
    public static final float AO_STRENGTH = 0.7f;

    /** Bloom 閾值 */
    public static final float BLOOM_THRESHOLD = 1.0f;

    /** Bloom 強度 */
    public static final float BLOOM_INTENSITY = 0.15f;

    /** 色調映射模式（0=Reinhard, 1=ACES, 2=Uncharted2） */
    public static final int TONEMAP_MODE = 1; // ACES

    // ─── 自動曝光（Luminance Histogram）────────────────────
    /** 啟用自動曝光 — 基於亮度直方圖的自適應曝光。
     * 參考 Radiance 的 auto-exposure via luminance histogram compute。 */
    public static final boolean AUTO_EXPOSURE_ENABLED = true;

    /** 曝光適應速度（秒）— 從暗到亮或亮到暗的過渡時間 */
    public static final float AUTO_EXPOSURE_ADAPT_SPEED = 1.5f;

    /** 最小曝光值（EV） */
    public static final float AUTO_EXPOSURE_MIN_EV = -2.0f;

    /** 最大曝光值（EV） */
    public static final float AUTO_EXPOSURE_MAX_EV = 12.0f;

    /** 亮度直方圖 bin 數量（通常 64 或 128） */
    public static final int LUMINANCE_HISTOGRAM_BINS = 64;

    /** 直方圖低截斷比例 — 忽略最暗的 N% 像素（避免陰影主導曝光） */
    public static final float LUMINANCE_LOW_PERCENTILE = 0.05f;

    /** 直方圖高截斷比例 — 忽略最亮的 N% 像素（避免高光主導曝光） */
    public static final float LUMINANCE_HIGH_PERCENTILE = 0.95f;

    // ═══════════════════════════════════════════════════════════════════
    //  TAA — 時序抗鋸齒（Temporal Anti-Aliasing）
    // ═══════════════════════════════════════════════════════════════════

    /** 啟用 TAA */
    public static final boolean TAA_ENABLED = true;

    /** TAA 歷史幀混合係數（0.0=完全當前幀, 1.0=完全歷史幀） */
    public static final float TAA_BLEND_FACTOR = 0.9f;

    /** TAA Halton 序列抖動樣本數（每 N 幀循環） */
    public static final int TAA_JITTER_SAMPLES = 16;

    // ═══════════════════════════════════════════════════════════════════
    //  Memory — FerriteCore 風格記憶體優化
    // ═══════════════════════════════════════════════════════════════════

    /** Matrix4f 物件池大小 */
    public static final int POOL_MATRIX_SIZE = 256;

    /** float[3] 向量池大小 */
    public static final int POOL_VEC3_SIZE = 512;

    /** float[4] 向量池大小 */
    public static final int POOL_VEC4_SIZE = 256;

    /** Intern 快取最大容量 */
    public static final int INTERN_CACHE_MAX = 4096;

    // ═══════════════════════════════════════════════════════════════════
    //  Threading — C2ME 風格多執行緒
    // ═══════════════════════════════════════════════════════════════════

    /** LOD 網格建構執行緒數（0 = 自動 = max(4, cores-2)） */
    public static final int LOD_BUILD_THREADS = 0;

    /** LOD I/O 執行緒數 */
    public static final int LOD_IO_THREADS = 2;

    /** 每幀最大 GPU 上傳量（bytes） */
    public static final int MAX_GPU_UPLOAD_PER_FRAME = 33_554_432; // 32 MB

    /** 網格建構任務超時（ms） */
    public static final int MESH_BUILD_TIMEOUT_MS = 50;

    // ═══════════════════════════════════════════════════════════════════
    //  Viewport — 多視角系統（Rhino / Axiom 風格）
    // ═══════════════════════════════════════════════════════════════════

    /** 最大同時視角數 */
    public static final int MAX_VIEWPORTS = 4;

    /** 正交視角預設縮放倍率 */
    public static final float ORTHO_DEFAULT_ZOOM = 64.0f;

    /** 正交視角最小/最大縮放 */
    public static final float ORTHO_MIN_ZOOM = 8.0f;
    public static final float ORTHO_MAX_ZOOM = 512.0f;

    /** 正交視角遠平面 */
    public static final float ORTHO_FAR_PLANE = 2048.0f;

    // ═══════════════════════════════════════════════════════════════════
    //  Radial UI — 輪盤選單系統
    // ═══════════════════════════════════════════════════════════════════

    /** 輪盤主環扇區數 */
    public static final int RADIAL_PRIMARY_SECTORS = 8;

    /** 輪盤子選單最大扇區數 */
    public static final int RADIAL_MAX_SUB_SECTORS = 6;

    /** 輪盤開啟動畫時長（ms） */
    public static final int RADIAL_OPEN_DURATION_MS = 150;

    /** 輪盤關閉動畫時長（ms） */
    public static final int RADIAL_CLOSE_DURATION_MS = 100;

    /** 子選單展開延遲（ms） */
    public static final int RADIAL_SUB_MENU_DELAY_MS = 200;

    /** 死區比例（內圈半徑比） */
    public static final float RADIAL_DEAD_ZONE_RATIO = 0.2f;

    // ═══════════════════════════════════════════════════════════════════
    //  Selection — Axiom 風格選取引擎
    // ═══════════════════════════════════════════════════════════════════

    /** 最大 Undo 深度 */
    public static final int SELECTION_MAX_UNDO = 32;

    /** Magic Wand 最大擴散距離（方塊） */
    public static final int MAGIC_WAND_MAX_SPREAD = 64;

    /** Brush 最大半徑（方塊） */
    public static final int BRUSH_MAX_RADIUS = 64;

    /** 選取集合最大容量（方塊數） */
    public static final int SELECTION_MAX_BLOCKS = 1_048_576; // 1M

    // ═══════════════════════════════════════════════════════════════════
    //  Blueprint Preview — 幽靈方塊預覽
    // ═══════════════════════════════════════════════════════════════════

    /** 幽靈方塊預設透明度 */
    public static final float GHOST_DEFAULT_ALPHA = 0.5f;

    /** 碰撞標記顏色（紅色通道強度） */
    public static final float GHOST_COLLISION_RED = 1.0f;

    /** 預覽最大方塊數（防止卡頓） */
    public static final int PREVIEW_MAX_BLOCKS = 65_536; // 64K

    // ═══════════════════════════════════════════════════════════════════
    //  Quick Placer — SimpleBuilding 風格快速放置
    // ═══════════════════════════════════════════════════════════════════

    /** 每次操作最大方塊數 */
    public static final int PLACER_MAX_BLOCKS = 262_144; // 256K

    /** 快速放置 Undo 最大深度 */
    public static final int PLACER_MAX_UNDO = 32;

    // ═══════════════════════════════════════════════════════════════════
    //  Volumetric — 體積光 / God Rays
    // ═══════════════════════════════════════════════════════════════════

    /** 啟用體積光 */
    public static final boolean VOLUMETRIC_ENABLED = true;

    /** 光線步進次數（品質 vs 效能） */
    public static final int VOLUMETRIC_RAY_STEPS = 32;

    /** 最大步進距離（方塊） */
    public static final float VOLUMETRIC_MAX_RAY_DIST = 256.0f;

    /** 霧密度係數 */
    public static final float VOLUMETRIC_FOG_DENSITY = 0.02f;

    /** 散射強度 */
    public static final float VOLUMETRIC_SCATTER_STRENGTH = 1.5f;

    // ═══════════════════════════════════════════════════════════════════
    //  Selection Viz — 選取視覺化
    // ═══════════════════════════════════════════════════════════════════

    /** 選取脈衝速度 */
    public static final float SELECTION_VIZ_PULSE_SPEED = 3.0f;

    /** 選取面填充透明度 */
    public static final float SELECTION_VIZ_FILL_ALPHA = 0.15f;

    /** 選取邊緣透明度 */
    public static final float SELECTION_VIZ_EDGE_ALPHA = 0.8f;

    /** 選取最大可見距離 */
    public static final float SELECTION_VIZ_MAX_DIST = 256.0f;

    // ═══════════════════════════════════════════════════════════════════
    //  Ghost Block — 幽靈方塊預覽視覺化
    // ═══════════════════════════════════════════════════════════════════

    /** 幽靈方塊全域透明度 */
    public static final float GHOST_BLOCK_ALPHA = 0.5f;

    /** 幽靈方塊呼吸動畫振幅 */
    public static final float GHOST_BREATHE_AMP = 0.02f;

    /** 幽靈方塊掃描線速度 */
    public static final float GHOST_SCAN_SPEED = 0.3f;

    // ═══════════════════════════════════════════════════════════════════
    //  SSR — Screen-Space Reflections（螢幕空間反射）
    // ═══════════════════════════════════════════════════════════════════

    /** 啟用 SSR */
    public static final boolean SSR_ENABLED = true;

    /** SSR 最大追蹤距離（view space） */
    public static final float SSR_MAX_DISTANCE = 50.0f;

    /** SSR 最大步進次數 */
    public static final int SSR_MAX_STEPS = 64;

    /** SSR Binary Search 精修次數 */
    public static final int SSR_BINARY_STEPS = 8;

    /** SSR 深度容差 */
    public static final float SSR_THICKNESS = 0.1f;

    /** SSR 邊緣淡出距離 */
    public static final float SSR_FADE_EDGE = 0.15f;

    // ═══════════════════════════════════════════════════════════════════
    //  DoF — Depth of Field（景深）
    // ═══════════════════════════════════════════════════════════════════

    /** 啟用景深 */
    public static final boolean DOF_ENABLED = false; // 預設關閉（建築模式不需要）

    /** 對焦距離（方塊） */
    public static final float DOF_FOCUS_DIST = 16.0f;

    /** 對焦清晰範圍 */
    public static final float DOF_FOCUS_RANGE = 8.0f;

    /** 最大模糊半徑（pixel） */
    public static final float DOF_MAX_BLUR_RADIUS = 8.0f;

    /** 光圈大小 */
    public static final float DOF_APERTURE = 2.8f;

    /** 採樣數 */
    public static final int DOF_SAMPLE_COUNT = 32;

    /** Bokeh 形狀（0=圓形, 1=六邊形） */
    public static final int DOF_BOKEH_SHAPE = 0;

    // ═══════════════════════════════════════════════════════════════════
    //  Contact Shadows — 接觸陰影
    // ═══════════════════════════════════════════════════════════════════

    /** 啟用接觸陰影 */
    public static final boolean CONTACT_SHADOW_ENABLED = true;

    /** 接觸陰影最大追蹤距離（view space） */
    public static final float CONTACT_SHADOW_MAX_DIST = 3.0f;

    /** 接觸陰影步進次數 */
    public static final int CONTACT_SHADOW_STEPS = 16;

    /** 接觸陰影深度容差 */
    public static final float CONTACT_SHADOW_THICKNESS = 0.05f;

    /** 接觸陰影強度 */
    public static final float CONTACT_SHADOW_INTENSITY = 0.5f;

    // ═══════════════════════════════════════════════════════════════════
    //  Atmosphere — 大氣散射天空
    // ═══════════════════════════════════════════════════════════════════

    /** 啟用大氣渲染 */
    public static final boolean ATMOSPHERE_ENABLED = true;

    /** 大氣散射取樣數（主射線） */
    public static final int ATMOSPHERE_SAMPLES = 16;

    /** 大氣散射光線取樣數 */
    public static final int ATMOSPHERE_LIGHT_SAMPLES = 8;

    /** 太陽強度乘數 */
    public static final float ATMOSPHERE_SUN_INTENSITY = 20.0f;

    // ═══════════════════════════════════════════════════════════════════
    //  Water — PBR 水體渲染
    // ═══════════════════════════════════════════════════════════════════

    /** 啟用進階水體渲染 */
    public static final boolean WATER_ENABLED = true;

    /** 水面基準 Y 座標（世界海平面） */
    public static final float WATER_LEVEL = 63.0f;

    /** 反射 FBO 解析度比例（0.5 = 半解析度） */
    public static final float WATER_REFLECTION_SCALE = 0.5f;

    /** Gerstner 波浪組數 */
    public static final int WATER_WAVE_COUNT = 4;

    /** 泡沫深度閾值 */
    public static final float WATER_FOAM_THRESHOLD = 0.8f;

    /** 焦散強度 */
    public static final float WATER_CAUSTICS_INTENSITY = 0.3f;

    // ═══════════════════════════════════════════════════════════════════
    //  Particles — GPU 粒子系統
    // ═══════════════════════════════════════════════════════════════════

    /** 啟用 GPU 粒子 */
    public static final boolean PARTICLES_ENABLED = true;

    /** 最大粒子數量 */
    public static final int PARTICLE_MAX_COUNT = 8192;

    /** 每粒子浮點數（SoA 佈局） */
    public static final int PARTICLE_FLOATS_PER = 13;

    /** 預設粒子生命（秒） */
    public static final float PARTICLE_DEFAULT_LIFE = 1.5f;

    /** 粒子重力加速度 */
    public static final float PARTICLE_GRAVITY = -9.8f;

    // ═══════════════════════════════════════════════════════════════════
    //  CSM — 級聯陰影
    // ═══════════════════════════════════════════════════════════════════

    /** 啟用級聯陰影（取代單層 Shadow Map） */
    public static final boolean CSM_ENABLED = true;

    /** CSM 最大陰影距離 */
    public static final float CSM_MAX_DISTANCE = 256.0f;

    /** CSM 級聯數 */
    public static final int CSM_CASCADE_COUNT = 4;

    /** CSM 陰影強度 */
    public static final float CSM_SHADOW_INTENSITY = 0.7f;

    // ═══════════════════════════════════════════════════════════════════
    //  Clouds — 體積雲
    // ═══════════════════════════════════════════════════════════════════

    /** 啟用體積雲 */
    public static final boolean CLOUD_ENABLED = true;

    /** 雲層底部高度（方塊） */
    public static final float CLOUD_BOTTOM_HEIGHT = 192.0f;

    /** 雲層厚度（方塊） */
    public static final float CLOUD_THICKNESS = 96.0f;

    /** 預設雲量覆蓋率 */
    public static final float CLOUD_DEFAULT_COVERAGE = 0.45f;

    // ═══════════════════════════════════════════════════════════════════
    //  Cinematic — 電影後製特效
    // ═══════════════════════════════════════════════════════════════════

    /** 啟用電影後製特效 */
    public static final boolean CINEMATIC_ENABLED = true;

    /** 暈影強度 */
    public static final float CINEMATIC_VIGNETTE_INTENSITY = 0.3f;

    /** 暈影半徑 */
    public static final float CINEMATIC_VIGNETTE_RADIUS = 0.8f;

    /** 色差強度（0=關閉） */
    public static final float CINEMATIC_CHROMATIC_ABERRATION = 0.002f;

    /** 動態模糊強度（0=關閉） */
    public static final float CINEMATIC_MOTION_BLUR = 0.0f;

    /** 動態模糊取樣數 */
    public static final int CINEMATIC_MOTION_BLUR_SAMPLES = 8;

    /** 底片顆粒強度（0=關閉） */
    public static final float CINEMATIC_FILM_GRAIN = 0.03f;

    // ═══════════════════════════════════════════════════════════════════
    //  Color Grading — 色彩分級
    // ═══════════════════════════════════════════════════════════════════

    /** 啟用色彩分級 */
    public static final boolean COLOR_GRADING_ENABLED = true;

    /** LUT 混合強度（0=原始色, 1=完全 LUT） */
    public static final float COLOR_GRADING_INTENSITY = 0.85f;

    /** 3D LUT 解析度 */
    public static final int COLOR_GRADING_LUT_SIZE = 32;

    /** 預設色溫（Kelvin） */
    public static final float COLOR_GRADING_TEMPERATURE = 6500.0f;

    /** 預設飽和度 */
    public static final float COLOR_GRADING_SATURATION = 1.05f;

    /** 預設對比度 */
    public static final float COLOR_GRADING_CONTRAST = 1.05f;

    // ═══════════════════════════════════════════════════════════════════
    //  Velocity Buffer — 運動向量
    // ═══════════════════════════════════════════════════════════════════

    /** 啟用 Velocity Buffer（動態模糊前置需求） */
    public static final boolean VELOCITY_BUFFER_ENABLED = true;

    // ═══════════════════════════════════════════════════════════════════
    //  Debug Overlay — 除錯覆蓋層
    // ═══════════════════════════════════════════════════════════════════

    /** 除錯覆蓋層預設啟動模式（false=關閉） */
    public static final boolean DEBUG_OVERLAY_DEFAULT_ON = false;

    /** 幀時間歷史長度 */
    public static final int DEBUG_FRAME_HISTORY = 120;

    // ═══════════════════════════════════════════════════════════════════
    //  SSGI — 螢幕空間全域光照
    // ═══════════════════════════════════════════════════════════════════

    /** 啟用 SSGI */
    public static final boolean SSGI_ENABLED = true;

    /** SSGI 強度乘數 */
    public static final float SSGI_INTENSITY = 0.6f;

    /** SSGI 取樣半徑（view space） */
    public static final float SSGI_RADIUS = 2.0f;

    /** SSGI 每像素取樣數 */
    public static final int SSGI_SAMPLES = 16;

    // ═══════════════════════════════════════════════════════════════════
    //  Fog — 體積霧
    // ═══════════════════════════════════════════════════════════════════

    /** 啟用體積霧 */
    public static final boolean FOG_ENABLED = true;

    /** 距離霧密度 */
    public static final float FOG_DISTANCE_DENSITY = 0.002f;

    /** 高度霧密度 */
    public static final float FOG_HEIGHT_DENSITY = 0.01f;

    /** 高度霧衰減率 */
    public static final float FOG_HEIGHT_FALLOFF = 0.05f;

    /** 高度霧基準線（Y 座標以下才有效果） */
    public static final float FOG_HEIGHT_BASE = 80.0f;

    // ═══════════════════════════════════════════════════════════════════
    //  Lens Flare — 鏡頭光暈
    // ═══════════════════════════════════════════════════════════════════

    /** 啟用鏡頭光暈 */
    public static final boolean LENS_FLARE_ENABLED = true;

    /** 鏡頭光暈強度 */
    public static final float LENS_FLARE_INTENSITY = 0.8f;

    // ═══════════════════════════════════════════════════════════════════
    //  Weather — 天氣系統
    // ═══════════════════════════════════════════════════════════════════

    /** 啟用天氣系統 */
    public static final boolean WEATHER_ENABLED = true;

    /** 雨滴每 tick 生成數（滿強度時） */
    public static final int RAIN_DROPS_PER_TICK = 64;

    /** 雪花每 tick 生成數（滿強度時） */
    public static final int SNOW_FLAKES_PER_TICK = 32;

    /** 極光高度（方塊） */
    public static final float AURORA_HEIGHT = 200.0f;

    /** 極光厚度（方塊） */
    public static final float AURORA_THICKNESS = 60.0f;

    /** 極光風場速度 */
    public static final float AURORA_WIND_SPEED = 0.5f;

    // ═══════════════════════════════════════════════════════════════════
    //  SSS — 次表面散射
    // ═══════════════════════════════════════════════════════════════════

    /** 啟用次表面散射 */
    public static final boolean SSS_ENABLED = true;

    /** SSS 擴散寬度（像素） */
    public static final float SSS_WIDTH = 0.01f;

    /** SSS 擴散強度 */
    public static final float SSS_STRENGTH = 0.5f;

    // ═══════════════════════════════════════════════════════════════════
    //  Anisotropic — 各向異性反射
    // ═══════════════════════════════════════════════════════════════════

    /** 啟用各向異性反射 */
    public static final boolean ANISOTROPIC_ENABLED = true;

    /** 各向異性強度 */
    public static final float ANISOTROPIC_STRENGTH = 0.6f;

    // ═══════════════════════════════════════════════════════════════════
    //  POM — 視差遮蔽映射
    // ═══════════════════════════════════════════════════════════════════

    /** 啟用視差遮蔽映射 */
    public static final boolean POM_ENABLED = true;

    /** POM 高度比例 */
    public static final float POM_SCALE = 0.04f;

    /** POM 步進次數 */
    public static final int POM_STEPS = 16;

    /** POM 二分精修次數 */
    public static final int POM_REFINEMENT_STEPS = 4;

    /** POM 距離漸隱（方塊） */
    public static final float POM_FADE_DISTANCE = 32.0f;

    // ═══════════════════════════════════════════════════════════════════
    //  Shader LOD — 動態品質降級
    // ═══════════════════════════════════════════════════════════════════

    /** 啟用 Shader LOD 自動品質調節 */
    public static final boolean SHADER_LOD_ENABLED = true;

    // ═══════════════════════════════════════════════════════════════════
    //  Occlusion Query — 硬體遮蔽查詢剔除
    // ═══════════════════════════════════════════════════════════════════

    /** 啟用硬體遮蔽查詢剔除 */
    public static final boolean OCCLUSION_QUERY_ENABLED = true;

    // ═══════════════════════════════════════════════════════════════════
    //  GPU Profiler — GPU 時間線效能分析
    // ═══════════════════════════════════════════════════════════════════

    /** 啟用 GPU Timeline Profiler */
    public static final boolean GPU_PROFILER_ENABLED = true;

    // ═══════════════════════════════════════════════════════════════════
    //  GPU Compute Skinning — Wicked Engine 2017 風格
    // ═══════════════════════════════════════════════════════════════════

    /** 啟用 GPU Compute Skinning（需要 GL 4.3） */
    public static final boolean COMPUTE_SKINNING_ENABLED = true;

    /** Compute skinning 最大頂點數 */
    public static final int COMPUTE_SKINNING_MAX_VERTICES = 65536;

    /** Compute skinning 工作群組大小 */
    public static final int COMPUTE_SKINNING_WORK_GROUP = 64;

    /** 自動啟用閾值（活躍動畫實體數） */
    public static final int COMPUTE_SKINNING_THRESHOLD = 50;

    // ═══════════════════════════════════════════════════════════════════
    //  Meshlet Engine — Nanite 風格虛擬幾何
    // ═══════════════════════════════════════════════════════════════════

    /** 啟用 Meshlet 引擎 */
    public static final boolean MESHLET_ENABLED = true;

    /** 每 Meshlet 最大三角形數 */
    public static final int MESHLET_MAX_TRIANGLES = 128;

    /** 每 Meshlet 最大頂點數 */
    public static final int MESHLET_MAX_VERTICES = 64;

    /** Meshlet LOD 層級數 */
    public static final int MESHLET_LOD_LEVELS = 5;

    // ═══════════════════════════════════════════════════════════════════
    //  GPU Culling — SIGGRAPH 2015 GPU-Driven Rendering
    // ═══════════════════════════════════════════════════════════════════

    /** 啟用 GPU Compute Culling（需要 GL 4.3） */
    public static final boolean GPU_CULLING_ENABLED = true;

    /** GPU culling 最大物件數 */
    public static final int GPU_CULLING_MAX_OBJECTS = 16384;

    /** GPU culling 工作群組大小 */
    public static final int GPU_CULLING_WORK_GROUP = 64;

    // ═══════════════════════════════════════════════════════════════════
    //  Sparse Voxel DAG — SVDAG 壓縮
    // ═══════════════════════════════════════════════════════════════════

    /** 啟用 SVDAG 壓縮 */
    public static final boolean SVDAG_ENABLED = true;

    /** SVDAG 最大深度（10 = 1024³ 體素） */
    public static final int SVDAG_MAX_DEPTH = 10;

    // ═══════════════════════════════════════════════════════════════════
    //  Disk LOD Cache — Bobby 風格磁碟快取
    // ═══════════════════════════════════════════════════════════════════

    /** 啟用磁碟 LOD 快取 */
    public static final boolean DISK_LOD_CACHE_ENABLED = true;

    /** 磁碟快取最大容量（MB） */
    public static final int DISK_LOD_CACHE_MAX_MB = 512;

    // ═══════════════════════════════════════════════════════════════════
    //  Mesh Shader — GL 4.6 / NV_mesh_shader 快速路徑
    // ═══════════════════════════════════════════════════════════════════

    /** 啟用 Mesh Shader 快速路徑（需要 Nvidia Turing+） */
    public static final boolean MESH_SHADER_ENABLED = true;

    /** Mesh Shader 每次 dispatch 最大 meshlet 數 */
    public static final int MESH_SHADER_MAX_MESHLETS = 65536;

    // ═══════════════════════════════════════════════════════════════════
    //  Voxel Cone Tracing — NVIDIA GTC 2012
    // ═══════════════════════════════════════════════════════════════════

    /** 啟用 VCT Compute Shader（需要 GL 4.3） */
    public static final boolean VCT_COMPUTE_ENABLED = true;

    /** VCT 體素化更新間隔（幀數） */
    public static final int VCT_UPDATE_INTERVAL = 4;

    // ═══════════════════════════════════════════════════════════════════
    //  Vulkan Ray Tracing — Option B 混合 GL+VK
    // ═══════════════════════════════════════════════════════════════════

    /** 啟用 Vulkan RT（Tier 3 功能，需要 RTX 2060+） */
    public static final boolean VULKAN_RT_ENABLED = true;

    /** RT Shadow 啟用 */
    public static final boolean RT_SHADOWS_ENABLED = true;

    /** RT Reflections 啟用 */
    public static final boolean RT_REFLECTIONS_ENABLED = false;

    /** RT Ambient Occlusion 啟用 */
    public static final boolean RT_AO_ENABLED = false;

    /** RT Global Illumination 啟用 */
    public static final boolean RT_GI_ENABLED = false;

    /** RT 最大遞迴深度（1=shadow only, 2+=reflections） */
    public static final int RT_MAX_RECURSION = 1;

    /** BVH 每幀最大 BLAS 重建數 */
    public static final int RT_MAX_BLAS_REBUILDS_PER_FRAME = 8;

    /** BVH scratch buffer 大小（MB） */
    public static final int RT_SCRATCH_BUFFER_MB = 16;

    /** SVGF 降噪 à-trous 迭代次數 */
    public static final int SVGF_ATROUS_ITERATIONS = 5;

    /** SVGF 時間累積混合因子（0.1 = 90% 歷史） */
    public static final float SVGF_TEMPORAL_ALPHA = 0.1f;
}
