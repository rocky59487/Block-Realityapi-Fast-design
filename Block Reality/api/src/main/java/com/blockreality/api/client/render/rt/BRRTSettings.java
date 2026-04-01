package com.blockreality.api.client.render.rt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 儲存 Vulkan 光線追蹤的動態全域設定。
 * 這些設定將由 FastDesign 模組中的 VulkanRTConfigNode 讀寫，
 * 並在每幀由 BRVulkanRT 與各種 Shader 與後處理讀取生效。
 */
public class BRRTSettings {
    private static final Logger LOGGER = LoggerFactory.getLogger(BRRTSettings.class);

    // ── Singleton ───────────────────────────────────────────────────────────
    private static final BRRTSettings INSTANCE = new BRRTSettings();

    public static BRRTSettings getInstance() {
        return INSTANCE;
    }

    private BRRTSettings() {}

    // ── 內部狀態 (執行緒安全) ──────────────────────────────────────────────────
    private volatile int maxBounces = 1;
    private volatile float aoRadius = 2.5f;
    private volatile boolean enableRTAO = true;

    /**
     * ★ P8-fix (2025-04): 補全 RT 效果開關。
     * 原本僅有 enableRTAO，缺少 RT Shadows / Reflections / GI 的個別開關，
     * 導致 VulkanRTConfigNode 無法透過節點編輯器獨立切換各效果。
     *
     * 這些布林值將接入 Shader Specialization Constants（由 BRVulkanRT 推送）。
     * 預設值：
     *   - enableRTShadows:      true  （RT 陰影最基礎，通常需要啟用）
     *   - enableRTReflections:  true  （螢幕空間反射的升級，開啟可見度高）
     *   - enableRTGI:           false （效能負擔最重，預設關閉待 Phase 5 穩定）
     */
    private volatile boolean enableRTShadows     = true;
    private volatile boolean enableRTReflections = true;
    private volatile boolean enableRTGI          = false;

    // 0 = NONE, 1 = SVGF, 2 = NRD (RELAX/REBLUR)
    private volatile int denoiserAlgo = 2;

    // ── Getter / Setter ──────────────────────────────────────────────────────

    public int getMaxBounces() { return maxBounces; }

    public void setMaxBounces(int bounces) {
        if (this.maxBounces != bounces) {
            this.maxBounces = bounces;
            LOGGER.debug("RTSettings: Max Bounces changed to {}", bounces);
        }
    }

    public float getAoRadius() { return aoRadius; }

    public void setAoRadius(float radius) {
        this.aoRadius = radius;
    }

    public boolean isEnableRTAO() { return enableRTAO; }

    public void setEnableRTAO(boolean enable) {
        this.enableRTAO = enable;
    }

    // ── P8: RT 效果個別開關 ──────────────────────────────────────────────────

    /** RT 陰影（Ray-traced shadows）開關。 */
    public boolean isEnableRTShadows() { return enableRTShadows; }

    public void setEnableRTShadows(boolean enable) {
        if (this.enableRTShadows != enable) {
            this.enableRTShadows = enable;
            LOGGER.debug("RTSettings: RT Shadows {}", enable ? "enabled" : "disabled");
        }
    }

    /** RT 反射（Ray-traced reflections）開關。 */
    public boolean isEnableRTReflections() { return enableRTReflections; }

    public void setEnableRTReflections(boolean enable) {
        if (this.enableRTReflections != enable) {
            this.enableRTReflections = enable;
            LOGGER.debug("RTSettings: RT Reflections {}", enable ? "enabled" : "disabled");
        }
    }

    /**
     * RT 全域光照（Ray-traced Global Illumination）開關。
     * 效能負擔最重，預設關閉；Phase 5 ReSTIR GI 穩定後建議預設開啟。
     */
    public boolean isEnableRTGI() { return enableRTGI; }

    public void setEnableRTGI(boolean enable) {
        if (this.enableRTGI != enable) {
            this.enableRTGI = enable;
            LOGGER.debug("RTSettings: RT GI {}", enable ? "enabled" : "disabled");
        }
    }

    public int getDenoiserAlgo() { return denoiserAlgo; }

    public void setDenoiserAlgo(int algo) {
        if (this.denoiserAlgo != algo) {
            this.denoiserAlgo = algo;
            LOGGER.debug("RTSettings: Denoiser Algorithm changed to {}", algo);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  RT-0-3: ReSTIR DI（直接光照採樣重用）設定
    //  適用 Tier：Ada + Blackwell（Phase 2 實作前此開關無效果）
    // ════════════════════════════════════════════════════════════════════════

    /** 是否啟用 ReSTIR DI（Reservoir-based Spatiotemporal Importance Resampling，直接光照）。
     *  預設 false：Phase 2（RT-2-x）完成後建議預設啟用。 */
    private volatile boolean enableReSTIRDI = false;

    /** ReSTIR DI 每幀時域最大 M 值（reservoir 歷史樣本上限）。
     *  較高值提升品質但增加 VRAM。建議範圍：8–32。預設 20。 */
    private volatile int reSTIRDITemporalMaxM = 20;

    /** ReSTIR DI 每幀空間採樣數（每像素向周圍像素採樣的次數）。
     *  建議範圍：1–4。預設 1（Blackwell 可提升至 4）。 */
    private volatile int reSTIRDISpatialSamples = 1;

    public boolean isEnableReSTIRDI()          { return enableReSTIRDI; }
    public void setEnableReSTIRDI(boolean v)   {
        if (this.enableReSTIRDI != v) { this.enableReSTIRDI = v;
            LOGGER.debug("RTSettings: ReSTIR DI {}", v ? "enabled" : "disabled"); }
    }
    public int  getReSTIRDITemporalMaxM()      { return reSTIRDITemporalMaxM; }
    public void setReSTIRDITemporalMaxM(int v) { this.reSTIRDITemporalMaxM = Math.max(1, v); }
    public int  getReSTIRDISpatialSamples()    { return reSTIRDISpatialSamples; }
    public void setReSTIRDISpatialSamples(int v) { this.reSTIRDISpatialSamples = Math.max(1, Math.min(v, 8)); }

    // ════════════════════════════════════════════════════════════════════════
    //  RT-0-3: ReSTIR GI（間接光照採樣重用）設定
    //  適用 Tier：Blackwell 優先，Ada 次之（Phase 3 實作前此開關無效果）
    // ════════════════════════════════════════════════════════════════════════

    /** 是否啟用 ReSTIR GI（間接光照全域光照，效能負擔重）。
     *  預設 false：Phase 3（RT-3-x）完成後考慮 Blackwell 預設啟用。 */
    private volatile boolean enableReSTIRGI = false;

    /** ReSTIR GI 每像素 RT rays 數量。建議範圍：1–8。Blackwell 建議 4，Ada 建議 2。 */
    private volatile int reSTIRGIRaysPerPixel = 2;

    public boolean isEnableReSTIRGI()           { return enableReSTIRGI; }
    public void setEnableReSTIRGI(boolean v)    {
        if (this.enableReSTIRGI != v) { this.enableReSTIRGI = v;
            LOGGER.debug("RTSettings: ReSTIR GI {}", v ? "enabled" : "disabled"); }
    }
    public int  getReSTIRGIRaysPerPixel()       { return reSTIRGIRaysPerPixel; }
    public void setReSTIRGIRaysPerPixel(int v)  { this.reSTIRGIRaysPerPixel = Math.max(1, Math.min(v, 8)); }

    // ════════════════════════════════════════════════════════════════════════
    //  RT-0-3: DDGI（Dynamic Diffuse Global Illumination）設定
    //  適用 Tier：Ada（Phase 4 實作前此開關無效果）
    // ════════════════════════════════════════════════════════════════════════

    /** 是否啟用 DDGI probe-based GI（適用 Ada 硬體 GI 路徑）。
     *  Blackwell 路徑使用 ReSTIR GI，此開關對 Blackwell 無效。
     *  預設 false：Phase 4（RT-4-x）完成後 Ada 預設啟用。 */
    private volatile boolean enableDDGI = false;

    /** DDGI probe 網格間距（方塊單位）。較小值精度高但 VRAM 使用增加。
     *  建議範圍：4–16。預設 8（≈ 8 格 = 8 米）。 */
    private volatile int ddgiProbeSpacingBlocks = 8;

    /** DDGI 每幀更新的 probe 比例（0.0–1.0）。1.0 = 全部更新，0.25 = 每 4 幀輪轉。
     *  較低值節省 GPU 時間但 GI 反應較慢。預設 0.25。 */
    private volatile float ddgiUpdateRatio = 0.25f;

    public boolean isEnableDDGI()              { return enableDDGI; }
    public void setEnableDDGI(boolean v)       {
        if (this.enableDDGI != v) { this.enableDDGI = v;
            LOGGER.debug("RTSettings: DDGI {}", v ? "enabled" : "disabled"); }
    }
    public int  getDdgiProbeSpacingBlocks()    { return ddgiProbeSpacingBlocks; }
    public void setDdgiProbeSpacingBlocks(int v){ this.ddgiProbeSpacingBlocks = Math.max(1, v); }
    public float getDdgiUpdateRatio()          { return ddgiUpdateRatio; }
    public void setDdgiUpdateRatio(float v)    { this.ddgiUpdateRatio = Math.max(0.0f, Math.min(v, 1.0f)); }

    // ════════════════════════════════════════════════════════════════════════
    //  RT-0-3: DLSS 設定（超解析度 + Frame Generation）
    //  適用 Tier：Ada = DLSS 3 FG；Blackwell = DLSS 4 MFG
    //  (Phase 6 實作前此開關無效果)
    // ════════════════════════════════════════════════════════════════════════

    /** 是否啟用 DLSS 超解析度（SR）。預設 false。 */
    private volatile boolean enableDLSS = false;

    /**
     * DLSS 品質模式。
     * <ul>
     *   <li>0 = Off（原生解析度）</li>
     *   <li>1 = Quality（1.5× up-scale）</li>
     *   <li>2 = Balanced（1.7× up-scale）</li>
     *   <li>3 = Performance（2.0× up-scale）</li>
     *   <li>4 = Ultra Performance（3.0× up-scale）</li>
     * </ul>
     * 預設 2（Balanced）。
     */
    private volatile int dlssMode = 2;

    /** 是否啟用 Frame Generation（Ada = DLSS 3 FG；Blackwell = DLSS 4 MFG 1→4 幀）。
     *  啟用後幀率可提升 2-4×，但增加顯示延遲（建議搭配 NVIDIA Reflex）。
     *  預設 false。 */
    private volatile boolean enableFrameGeneration = false;

    /** 是否啟用 NVIDIA Reflex（最小化端對端延遲，建議搭配 Frame Gen）。
     *  預設 true（低延遲模式）。 */
    private volatile boolean enableReflex = true;

    public boolean isEnableDLSS()             { return enableDLSS; }
    public void setEnableDLSS(boolean v)      {
        if (this.enableDLSS != v) { this.enableDLSS = v;
            LOGGER.debug("RTSettings: DLSS SR {}", v ? "enabled" : "disabled"); }
    }
    public int  getDlssMode()                 { return dlssMode; }
    public void setDlssMode(int v)            {
        if (this.dlssMode != v) { this.dlssMode = Math.max(0, Math.min(v, 4));
            LOGGER.debug("RTSettings: DLSS mode = {}", this.dlssMode); }
    }
    public boolean isEnableFrameGeneration()  { return enableFrameGeneration; }
    public void setEnableFrameGeneration(boolean v) {
        if (this.enableFrameGeneration != v) { this.enableFrameGeneration = v;
            LOGGER.debug("RTSettings: Frame Generation {}", v ? "enabled" : "disabled"); }
    }
    public boolean isEnableReflex()           { return enableReflex; }
    public void setEnableReflex(boolean v)    { this.enableReflex = v; }

    // ════════════════════════════════════════════════════════════════════════
    //  RT-0-3: Cluster BVH 設定（Blackwell 專屬）
    //  (Phase 1 RT-1-x 實作前此開關無效果)
    // ════════════════════════════════════════════════════════════════════════

    /** 是否啟用 Cluster BVH（Blackwell VK_NV_cluster_acceleration_structure）。
     *  在非 Blackwell GPU 上此設定被忽略，自動使用標準 BVH。
     *  預設 true（Blackwell 啟動時自動啟用）。 */
    private volatile boolean enableClusterBVH = true;

    /** Cluster 觸發閾值：section 數量超過此值才使用 Cluster BVH 打包。
     *  較低值使更多場景使用 Cluster（品質高，開銷略增）。
     *  建議範圍：8–64。預設 16。 */
    private volatile int clusterSectionThreshold = 16;

    public boolean isEnableClusterBVH()           { return enableClusterBVH; }
    public void setEnableClusterBVH(boolean v)    { this.enableClusterBVH = v; }
    public int  getClusterSectionThreshold()      { return clusterSectionThreshold; }
    public void setClusterSectionThreshold(int v) { this.clusterSectionThreshold = Math.max(1, v); }

    // ════════════════════════════════════════════════════════════════════════
    //  RT-0-3: NRD 降噪器細項設定
    // ════════════════════════════════════════════════════════════════════════

    /**
     * NRD 演算法選擇（當 denoiserAlgo = 2 = NRD 時生效）。
     * <ul>
     *   <li>0 = ReBLUR（Ada 推薦，適合 RTAO / DDGI）</li>
     *   <li>1 = ReLAX（Blackwell 推薦，適合 ReSTIR DI/GI）</li>
     *   <li>2 = SIGMA Shadow（RT 陰影降噪）</li>
     * </ul>
     * 預設 0（ReBLUR）。
     */
    private volatile int nrdAlgorithm = 0;

    public int  getNrdAlgorithm()     { return nrdAlgorithm; }
    public void setNrdAlgorithm(int v){ this.nrdAlgorithm = Math.max(0, Math.min(v, 2)); }
}
