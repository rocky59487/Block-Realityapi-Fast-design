package com.blockreality.api.client.render.pipeline;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Phase 8 渲染 Pass 枚舉 — RTX 光追路徑專屬 Pass 識別符。
 *
 * <h3>三條 Pass 路徑（對應 TASK_MANUAL § Phase 8）</h3>
 *
 * <b>Blackwell（RTX 50xx，SM 10.x）：</b>
 * <pre>
 * GBUFFER → CLUSTER_BVH_UPDATE → RESTIR_DI → RESTIR_GI
 *         → NRD → DLSS_SR → DLSS_MFG → TONEMAP → UI
 * </pre>
 *
 * <b>Ada（RTX 40xx，SM 8.9）：</b>
 * <pre>
 * GBUFFER → BLAS_TLAS_UPDATE → RT_SHADOW_AO
 *         → DDGI_UPDATE → DDGI_SAMPLE
 *         → NRD → DLSS_SR → DLSS_FG → TONEMAP → UI
 * </pre>
 *
 * <b>Legacy（RTX 20/30xx 及以下）：</b>
 * <p>沿用 {@link RenderPass} 既有順序，不進入此枚舉的管線。
 *
 * <h3>設計說明</h3>
 * <p>此枚舉定義 RT 路徑專屬 pass；GBuffer 填充與最終合成等共用階段
 * 與 {@link RenderPass} 同名定義但不重複實作 — 兩者由
 * {@link BRRTPipelineOrdering} 組合為完整序列。
 *
 * @see BRRTPipelineOrdering
 * @see RenderPass
 * @author Block Reality Team
 */
@OnlyIn(Dist.CLIENT)
public enum RTRenderPass {

    // ─────────────────────────────────────────────────────────────────────
    //  共用 Pass（Blackwell + Ada 均包含）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * GBuffer 幾何填充（position / normal / albedo / material）。
     * <p>對應原 {@link RenderPass#GBUFFER_TERRAIN} 等的 RT 版本；
     * 深度/法線需額外輸出用於後續 ReSTIR / DDGI sampling。
     * <p>適用層級：ALL
     */
    GBUFFER("gbuffer", GpuTierMask.ALL,
            "GBuffer 幾何填充 (position/normal/albedo/material/depth)"),

    /**
     * NRD（NVIDIA Real-time Denoisers）降噪 Pass。
     * <p>將 ReSTIR/DDGI 輸出的含噪照明進行時域 + 空域降噪。
     * ReBLUR（Blackwell）/ ReLAX（Ada）/ SIGMA（影子）根據 {@link BRRTPipelineOrdering}
     * 選擇的路徑自動切換。
     * <p>適用層級：BLACKWELL | ADA
     */
    NRD("nrd", GpuTierMask.BLACKWELL | GpuTierMask.ADA,
        "NRD 降噪 (ReBLUR/ReLAX/SIGMA)"),

    /**
     * DLSS Super Resolution（放大）Pass。
     * <p>接收降噪後的低解析度 RT 輸出，放大至目標解析度。
     * DLSS 4 於 Blackwell 路徑；DLSS 3.5 於 Ada 路徑。
     * <p>適用層級：BLACKWELL | ADA
     */
    DLSS_SR("dlss_sr", GpuTierMask.BLACKWELL | GpuTierMask.ADA,
            "DLSS 4/3.5 Super Resolution (低→目標解析度放大)"),

    /**
     * Tone Mapping Pass — HDR → LDR 轉換（ACES filmic）。
     * <p>適用層級：ALL（Legacy 路徑使用 {@link RenderPass#COMPOSITE_TONEMAP}）
     */
    TONEMAP("tonemap", GpuTierMask.BLACKWELL | GpuTierMask.ADA,
            "Tone Mapping (ACES HDR→LDR)"),

    /**
     * UI 覆蓋層 — HUD、工具提示、除錯資訊疊加。
     * <p>適用層級：ALL（Legacy 路徑使用 {@link RenderPass#OVERLAY_UI}）
     */
    UI("ui", GpuTierMask.BLACKWELL | GpuTierMask.ADA,
       "UI 覆蓋 (HUD/工具提示/除錯資訊)"),

    // ─────────────────────────────────────────────────────────────────────
    //  Blackwell 專屬 Pass（RTX 50xx）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Cluster BVH 更新 Pass（Blackwell 專屬）。
     * <p>使用 {@code VK_NV_cluster_acceleration_structure} 建構 / 增量更新
     * Cluster TLAS，將鄰近 LOD Section 打包成 cluster，
     * 相比逐 Instance TLAS 減少 8-16× 遍歷成本。
     * <p>適用層級：BLACKWELL only
     */
    CLUSTER_BVH_UPDATE("cluster_bvh_update", GpuTierMask.BLACKWELL,
            "Cluster BVH 建構/更新 (VK_NV_cluster_acceleration_structure)"),

    /**
     * ReSTIR DI（Direct Illumination）Pass（Blackwell 專屬）。
     * <p>對直接光照進行 Resampled Importance Sampling，
     * 支援時域 reservoir + 空域 reservoir 複用。
     * 由 {@link com.blockreality.api.client.render.rt.BRReSTIRDI} 驅動。
     * <p>適用層級：BLACKWELL only
     */
    RESTIR_DI("restir_di", GpuTierMask.BLACKWELL,
              "ReSTIR DI 直接光 (BRReSTIRDI)"),

    /**
     * ReSTIR GI（Global Illumination）Pass（Blackwell 專屬）。
     * <p>對間接光照進行 Resampled Importance Sampling，
     * 每像素多 GI ray，時域 reuse 減少 noise。
     * 由 {@link com.blockreality.api.client.render.rt.BRReSTIRGI} 驅動。
     * <p>適用層級：BLACKWELL only
     */
    RESTIR_GI("restir_gi", GpuTierMask.BLACKWELL,
              "ReSTIR GI 間接光 (BRReSTIRGI)"),

    /**
     * DLSS Multi-Frame Generation（幀生成×3）Pass（Blackwell 專屬）。
     * <p>DLSS 4 MFG 從 1 個渲染幀生成 3 個額外幀，等效 4× 幀率提升。
     * 由 {@link com.blockreality.api.client.render.rt.BRDLSS4Manager} 驅動。
     * <p>適用層級：BLACKWELL only
     */
    DLSS_MFG("dlss_mfg", GpuTierMask.BLACKWELL,
             "DLSS 4 Multi-Frame Generation (×3 幀生成)"),

    // ─────────────────────────────────────────────────────────────────────
    //  Ada 專屬 Pass（RTX 40xx）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * BLAS/TLAS 增量更新 Pass（Ada 路徑）。
     * <p>對上一幀變更的 Section 進行 BLAS refitting，
     * 然後重建 TLAS instance 列表，確保動態方塊（機械、崩塌）正確反映。
     * 由 {@link com.blockreality.api.client.render.rt.BRVulkanBVH} 驅動。
     * <p>適用層級：ADA only
     */
    BLAS_TLAS_UPDATE("blas_tlas_update", GpuTierMask.ADA,
                     "BLAS/TLAS 增量更新 (BRVulkanBVH)"),

    /**
     * RT Shadow + AO 合併 Pass（Ada 路徑）。
     * <p>在單一 RT dispatch 中同時計算陰影可見性與環境遮蔽，
     * 使用 Ray Query Compute Shader + SER 優化 warp 效率。
     * <p>適用層級：ADA only
     */
    RT_SHADOW_AO("rt_shadow_ao", GpuTierMask.ADA,
                 "RT Shadow + AO 合併 (SER + Ray Query Compute)"),

    /**
     * DDGI Probe 更新 Pass（Ada 路徑）。
     * <p>發送 probe ray，更新 Irradiance / Visibility 貼圖。
     * 由 {@link com.blockreality.api.client.render.rt.BRDDGIProbeSystem} 驅動。
     * <p>適用層級：ADA only
     */
    DDGI_UPDATE("ddgi_update", GpuTierMask.ADA,
                "DDGI Probe 更新 (BRDDGIProbeSystem)"),

    /**
     * DDGI Sampling Pass — 幾何表面採樣 DDGI Probe 計算間接光（Ada 路徑）。
     * <p>使用 bilinear + octahedral 映射讀取 Irradiance Volume，
     * 輸出 GI diffuse radiance 至 NRD 輸入 buffer。
     * <p>適用層級：ADA only
     */
    DDGI_SAMPLE("ddgi_sample", GpuTierMask.ADA,
                "DDGI Sampling (幾何表面→Irradiance Volume 採樣)"),

    /**
     * DLSS Frame Generation（幀生成×1）Pass（Ada 路徑）。
     * <p>DLSS 3.5 FG 從 2 個渲染幀生成 1 個插值幀，等效 2× 幀率提升。
     * <p>適用層級：ADA only
     */
    DLSS_FG("dlss_fg", GpuTierMask.ADA,
            "DLSS 3.5 Frame Generation (×1 幀生成)");

    // ─────────────────────────────────────────────────────────────────────
    //  欄位與建構子
    // ─────────────────────────────────────────────────────────────────────

    /** Pass 識別符（用於日誌、Profiler 標籤） */
    private final String id;

    /** GPU 層級遮罩（{@link GpuTierMask} 位元組合） */
    private final int tierMask;

    /** 人類可讀描述（用於除錯 HUD 顯示） */
    private final String description;

    RTRenderPass(String id, int tierMask, String description) {
        this.id = id;
        this.tierMask = tierMask;
        this.description = description;
    }

    public String getId() { return id; }
    public String getDescription() { return description; }

    /** 此 Pass 是否適用於指定 GPU 層級（{@link GpuTierMask}）。 */
    public boolean supportsGpuTier(int tier) {
        return (tierMask & tier) != 0;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  GPU Tier 位元遮罩常數
    // ─────────────────────────────────────────────────────────────────────

    /**
     * GPU 層級位元遮罩 — 對應 {@link com.blockreality.api.client.rendering.vulkan.BRAdaRTConfig}
     * 的 TIER_* 常數（LEGACY_RT=0, ADA=1, BLACKWELL=2）。
     *
     * <p>此處使用位元旗標以支援「多層共用」的 Pass（例如 NRD 同時在 Ada/Blackwell 路徑）。
     */
    public static final class GpuTierMask {
        private GpuTierMask() {}
        /** 舊世代 RT GPU（RTX 20/30xx） */
        public static final int LEGACY    = 1;
        /** Ada Lovelace（RTX 40xx，SM 8.9） */
        public static final int ADA       = 2;
        /** Blackwell（RTX 50xx，SM 10.x） */
        public static final int BLACKWELL = 4;
        /** 所有 RT 層級 */
        public static final int ALL       = LEGACY | ADA | BLACKWELL;
    }
}
