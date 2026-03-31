package com.blockreality.api.client.rendering.vulkan;

import com.blockreality.api.client.render.optimization.BRSparseVoxelDAG;
import com.blockreality.api.client.render.rt.BRVulkanRT;
import com.blockreality.api.client.render.rt.BRVulkanInterop;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * VkRTPipeline — Ada/Blackwell RT pipeline 橋接層。
 *
 * <h3>Ada 優化路徑（RTX 40xx SM 8.9+）</h3>
 * <ul>
 *   <li><b>SER</b>：由 {@code primary.rgen.glsl} 的 {@code reorderThreadNV()} 處理，
 *       按材料/LOD 重排 wave 消除 warp divergence</li>
 *   <li><b>RTAO</b>：{@code rtao.comp.glsl}（compute，8 samples + bilateral blur）</li>
 *   <li><b>DAG SSBO</b>：{@link BRAdaRTConfig#uploadDAGToGPU()} 上傳遠距 GI 資料</li>
 *   <li><b>specialization constant SC_0=GPU_TIER(0), SC_1=AO_SAMPLES(8)</b></li>
 * </ul>
 *
 * <h3>Blackwell 優化路徑（RTX 50xx SM 10.x+）</h3>
 * <ul>
 *   <li><b>Cluster AS</b>：由 {@link VkAccelStructBuilder} 打包 4×4 section cluster，
 *       TLAS instance 縮減 16×</li>
 *   <li><b>RTAO</b>：16 samples（SC_1=16），更穩定的時域累積（alpha=0.04）</li>
 *   <li><b>2nd GI bounce</b>：SC_1=MAX_BOUNCES(2) 用於 closesthit</li>
 *   <li><b>specialization constant SC_0=GPU_TIER(1), SC_1=AO_SAMPLES(16)/MAX_BOUNCES(2)</b></li>
 * </ul>
 *
 * <h3>每幀流程</h3>
 * <pre>
 * dispatchRays(proj, view, tlas)
 *   1. 從 viewMatrix 逆矩陣提取相機世界座標
 *   2. 計算 invViewProj，上傳相機 UBO
 *   3. 若有 TLAS 更新：updateDescriptors(tlas, 0)
 *   4. 若 DAG 有更新：uploadDAGToGPU()
 *   5. traceRays(width, height)
 * </pre>
 *
 * @author Block Reality Team
 */
@OnlyIn(Dist.CLIENT)
public final class VkRTPipeline {

    private static final Logger LOG = LoggerFactory.getLogger("BR-VkRTPipeline");

    // DAG 上傳節流：每 N 幀最多上傳一次（避免每幀 map/unmap）
    private static final int DAG_UPLOAD_INTERVAL = 20; // ~1 秒（20 TPS）

    private final VkContext            context;
    private final VkAccelStructBuilder accelBuilder;

    private boolean initialized  = false;
    private int     outputWidth  = 0;
    private int     outputHeight = 0;

    // 幀計數器（用於 DAG 上傳節流）
    private long frameIndex = 0L;

    // 上次 DAG 上傳時的 BRSparseVoxelDAG 版本（避免無變化時重複上傳）
    private long lastDagVersion = -1L;

    // GPU tier 快取（init 後設定）
    private int gpuTier = -1;

    public VkRTPipeline(VkContext context, VkAccelStructBuilder accelBuilder) {
        this.context      = context;
        this.accelBuilder = accelBuilder;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  生命週期
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 初始化 RT pipeline。
     *
     * <p>執行順序：
     * <ol>
     *   <li>{@link BRVulkanRT#init()} — 建立基礎 RT pipeline（raygen/miss/hit SBT）</li>
     *   <li>{@link BRAdaRTConfig#detect()} — 偵測 Ada/Blackwell GPU 世代</li>
     *   <li>根據 GPU tier 記錄 Ada/Blackwell 功能支援情況</li>
     *   <li>初始 DAG SSBO 上傳</li>
     * </ol>
     *
     * @param width  輸出寬度（像素）
     * @param height 輸出高度（像素）
     */
    public void init(int width, int height) {
        if (!context.isAvailable()) {
            LOG.debug("VkContext not available, RT pipeline disabled");
            return;
        }
        if (!accelBuilder.isInitialized()) {
            LOG.warn("VkAccelStructBuilder not ready, RT pipeline aborted");
            return;
        }
        try {
            // 基礎 RT pipeline（raygen/miss/closesthit SBT）
            BRVulkanRT.init();
            if (!BRVulkanRT.isInitialized()) {
                LOG.warn("BRVulkanRT.init() failed");
                return;
            }

            this.outputWidth  = width;
            this.outputHeight = height;

            // Ada/Blackwell 世代偵測
            BRAdaRTConfig.detect();
            this.gpuTier = BRAdaRTConfig.getGpuTier();

            logAdaBlackwellCapabilities();

            // 初始 DAG SSBO 上傳（供 primary.rgen 遠距 GI 使用）
            tryUploadDAG(/*force=*/true);

            this.initialized = true;
            LOG.info("VkRTPipeline initialized ({}×{}, tier={})",
                width, height,
                gpuTier == BRAdaRTConfig.TIER_BLACKWELL ? "Blackwell SM10+"
                : gpuTier == BRAdaRTConfig.TIER_ADA     ? "Ada SM8.9"
                : "Legacy");

        } catch (Exception e) {
            LOG.error("VkRTPipeline init error", e);
            initialized = false;
        }
    }

    public void cleanup() {
        if (initialized) {
            try {
                BRVulkanRT.cleanup();
                BRAdaRTConfig.cleanup();
            } catch (Exception e) {
                LOG.debug("VkRTPipeline cleanup error: {}", e.getMessage());
            }
            initialized = false;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  每幀 RT dispatch
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 更新相機 UBO 並發射光線。
     *
     * <p>相機座標從 viewMatrix 逆矩陣的 column 3 提取，避免硬編碼。
     * 太陽方向取自固定正午方向（Phase 3 可接入 Minecraft 時間系統）。
     *
     * <p>Ada/Blackwell 路徑差異由 GPU 端 specialization constant（SC_0=GPU_TIER）處理；
     * 此橋接層不需要在 CPU 端分支。
     *
     * @param projMatrix  投影矩陣
     * @param viewMatrix  視圖矩陣（world→camera）
     * @param tlas        當前 TLAS handle；0 = 使用現有 TLAS
     */
    public void dispatchRays(Matrix4f projMatrix, Matrix4f viewMatrix, long tlas) {
        if (!initialized) return;
        frameIndex++;

        try {
            // ── 1. 從 viewMatrix 逆矩陣提取相機世界座標 ──────────────────────
            // invView.col3 = 相機在世界空間的位置（JOML column-major：m30/m31/m32）
            Matrix4f invView = viewMatrix.invert(new Matrix4f());
            float camX = invView.m30();
            float camY = invView.m31();
            float camZ = invView.m32();

            // ── 2. 計算 invViewProj（RT shader 重建世界空間光線需要） ─────────
            Matrix4f vp    = new Matrix4f(projMatrix).mul(viewMatrix);
            Matrix4f invVP = vp.invert(new Matrix4f());

            // ── 3. 真實太陽方向（連接 BRAtmosphereEngine 日夜循環）────────────
            // Phase 2: 從大氣引擎取真實太陽方向，與 CSM/天空渲染完全一致。
            float sunDirX, sunDirY, sunDirZ;
            try {
                org.joml.Vector3f sunDir =
                    com.blockreality.api.client.render.effect.BRAtmosphereEngine.getSunDirection();
                sunDirX = sunDir.x;
                sunDirY = sunDir.y;
                sunDirZ = sunDir.z;
            } catch (Exception ignored) {
                // 降級：正午固定方向
                sunDirX = 0.57f; sunDirY = 0.57f; sunDirZ = 0.57f;
            }

            // ── 4. 上傳相機 UBO（invViewProj + camPos + sunDir）───────────────
            BRVulkanRT.setCameraData(invVP, camX, camY, camZ, sunDirX, sunDirY, sunDirZ);

            // ── 5. 天氣 PBR uniform（水份/積雪 → BRDF 修改）──────────────────
            // 連接 BRWeatherEngine（即便在 GL fallback 路徑仍有狀態）
            float wetness     = 0.0f;
            float snowCoverage = 0.0f;
            try {
                wetness      = com.blockreality.api.client.render.effect.BRWeatherEngine.getGlobalWetness();
                snowCoverage = com.blockreality.api.client.render.effect.BRWeatherEngine.getSnowCoverage();
            } catch (Exception ignored) { /* weather engine not ready */ }
            BRVulkanRT.setWeatherUniforms(wetness, snowCoverage);

            // ── 6. Frame index（Halton 序列 + 時域累積）──────────────────────
            BRVulkanRT.updateFrameIndex(frameIndex);

            // ── 7. 若 TLAS 有更新，同步 descriptor set ──────────────────────
            if (tlas != 0L) {
                // outputImageView = 0：BRVulkanRT 內部持有 output image view
                BRVulkanRT.updateDescriptors(tlas, 0L);
            }

            // ── 8. DAG SSBO 節流上傳（遠距 GI，128+ chunk 軟追蹤，Ada 專用）──
            if (frameIndex % DAG_UPLOAD_INTERVAL == 0) {
                tryUploadDAG(/*force=*/false);
            }

            // ── 9. 發射光線（vkCmdTraceRaysKHR）────────────────────────────
            BRVulkanRT.traceRays(outputWidth, outputHeight);

        } catch (Exception e) {
            LOG.debug("RT dispatch error: {}", e.getMessage());
        }
    }

    /**
     * 透過 GL/VK interop 將 RT 輸出紋理匯出為 OpenGL texture ID。
     *
     * @return OpenGL texture ID，若失敗返回 0
     */
    public int exportToGL() {
        if (!initialized) return 0;
        try {
            return BRVulkanInterop.getGLRTOutputTexture();
        } catch (Exception e) {
            LOG.debug("RT exportToGL error: {}", e.getMessage());
            return 0;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  螢幕尺寸變更
    // ═══════════════════════════════════════════════════════════════════════

    public void resize(int newWidth, int newHeight) {
        if (!initialized) return;
        if (newWidth == outputWidth && newHeight == outputHeight) return;
        this.outputWidth  = newWidth;
        this.outputHeight = newHeight;
        LOG.debug("VkRTPipeline resize: {}×{}", newWidth, newHeight);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  DAG SSBO 管理
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 嘗試上傳 BRSparseVoxelDAG 到 GPU SSBO（供 primary.rgen 遠距 GI 使用）。
     *
     * <p>版本號比較：若 DAG 自上次上傳以來未改變，跳過上傳。
     *
     * @param force 是否強制上傳（忽略版本比較）
     */
    private void tryUploadDAG(boolean force) {
        if (!BRAdaRTConfig.isAdaOrNewer()) return;           // 僅 Ada+ 使用 DAG SSBO
        if (!BRSparseVoxelDAG.isInitialized()) return;

        long currentVersion = BRSparseVoxelDAG.getTotalNodes(); // 以節點數作為版本代理
        if (!force && currentVersion == lastDagVersion) return;

        try {
            BRAdaRTConfig.uploadDAGToGPU();
            lastDagVersion = currentVersion;
            LOG.debug("DAG SSBO uploaded: {} nodes", currentVersion);
        } catch (Exception e) {
            LOG.debug("DAG upload error: {}", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Ada/Blackwell 功能日誌
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 輸出 Ada/Blackwell 功能支援報告（init 後呼叫一次）。
     *
     * <h4>Shader specialization constant 對應</h4>
     * <pre>
     * SC_0 GPU_TIER:    0 (Ada) / 1 (Blackwell) — 由 BRAdaRTConfig.buildSpecializationInfo()
     * SC_1 AO_SAMPLES:  8 (Ada) / 16 (Blackwell)
     * SC_1 MAX_BOUNCES: 1 (Ada) /  2 (Blackwell) — 用於 closesthit variant
     * </pre>
     *
     * <h4>SER（VK_NV_ray_tracing_invocation_reorder）</h4>
     * 在 primary.rgen.glsl 以 hitObjectTraceRayNV→reorderThreadNV→hitObjectExecuteShaderNV 實現。
     * 對 Minecraft 異質材料場景可獲得 2-4× warp throughput 提升。
     *
     * <h4>Cluster AS（Blackwell VK_NV_cluster_acceleration_structure）</h4>
     * 由 VkAccelStructBuilder 以邏輯 4×4 cluster 實現，
     * TLAS instance 縮減 16×，硬體 Cluster AS 加速路徑在 Blackwell 透明啟用。
     */
    private void logAdaBlackwellCapabilities() {
        if (!BRAdaRTConfig.isDetected()) {
            LOG.info("VkRTPipeline: GPU detection not complete");
            return;
        }

        if (BRAdaRTConfig.isBlackwellOrNewer()) {
            LOG.info("VkRTPipeline Ada/Blackwell capabilities:");
            LOG.info("  GPU tier: Blackwell (SM 10.x, RTX 50xx)");
            LOG.info("  SER (invocation reorder): {}", BRAdaRTConfig.hasSER());
            LOG.info("  OMM (opacity micromap):   {}", BRAdaRTConfig.hasOMM());
            LOG.info("  Ray Query (RTAO compute): {}", BRAdaRTConfig.hasRayQuery());
            LOG.info("  Cluster AS (TLAS 16× reduction): {}", BRAdaRTConfig.hasClusterAS());
            LOG.info("  Cooperative Vector:       {}", BRAdaRTConfig.hasCoopVector());
            LOG.info("  AO samples: {} (SC_1)", BRAdaRTConfig.AO_SAMPLES_BLACKWELL);
            LOG.info("  GI bounces: {} (SC_1 closesthit)", BRAdaRTConfig.BOUNCES_BLACKWELL);
        } else if (BRAdaRTConfig.isAdaOrNewer()) {
            LOG.info("VkRTPipeline Ada capabilities:");
            LOG.info("  GPU tier: Ada Lovelace (SM 8.9, RTX 40xx)");
            LOG.info("  SER (invocation reorder): {}", BRAdaRTConfig.hasSER());
            LOG.info("  OMM (opacity micromap):   {}", BRAdaRTConfig.hasOMM());
            LOG.info("  Ray Query (RTAO compute): {}", BRAdaRTConfig.hasRayQuery());
            LOG.info("  AO samples: {} (SC_1)", BRAdaRTConfig.AO_SAMPLES_ADA);
            LOG.info("  GI bounces: {} (SC_1 closesthit)", BRAdaRTConfig.BOUNCES_ADA);
        } else {
            LOG.info("VkRTPipeline: Legacy GPU (pre-Ada), Ada optimizations disabled");
        }

        LOG.info("  DAG buffer handle: 0x{}", Long.toHexString(BRAdaRTConfig.getDagBufferHandle()));
        LOG.info("  effectiveGpuTier (for SC_0): {}", BRAdaRTConfig.effectiveGpuTier());
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  統計
    // ═══════════════════════════════════════════════════════════════════════

    public boolean isInitialized()       { return initialized; }
    public int     getOutputWidth()      { return outputWidth; }
    public int     getOutputHeight()     { return outputHeight; }
    public int     getGpuTier()          { return gpuTier; }
    public long    getFrameIndex()       { return frameIndex; }

    /** Ada 或更新 GPU：SER + RTAO compute 路徑啟用 */
    public boolean isAdaPath()           { return initialized && BRAdaRTConfig.isAdaOrNewer(); }
    /** Blackwell：Cluster AS + 16 AO samples + 2nd bounce 啟用 */
    public boolean isBlackwellPath()     { return initialized && BRAdaRTConfig.isBlackwellOrNewer(); }

    /** 上次 traceRays() 耗時（毫秒） */
    public float getLastTraceTimeMs()    { return initialized ? BRVulkanRT.getLastTraceTimeMs() : 0f; }
    /** 累計發射光線總數 */
    public long getTotalRaysTraced()     { return initialized ? BRVulkanRT.getTotalRaysTraced() : 0L; }
}
