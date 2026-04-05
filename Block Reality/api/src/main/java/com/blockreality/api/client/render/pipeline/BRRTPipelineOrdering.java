package com.blockreality.api.client.render.pipeline;

import com.blockreality.api.client.render.rt.BRDLSS4Manager;
import com.blockreality.api.client.render.rt.BRClusterBVH;
import com.blockreality.api.client.render.rt.BRDDGIProbeSystem;
import com.blockreality.api.client.render.rt.BRFSRManager;
import com.blockreality.api.client.render.rt.BRGBufferAttachments;
import com.blockreality.api.client.render.rt.BRNRDNative;
import com.blockreality.api.client.render.rt.BRReLAXDenoiser;
import com.blockreality.api.client.render.rt.BRSDFRayMarcher;
import com.blockreality.api.client.render.rt.BRSDFVolumeManager;
import com.blockreality.api.client.render.rt.BRVolumetricLighting;
import com.blockreality.api.client.render.rt.BRVulkanBVH;
import com.blockreality.api.client.render.rt.BRVulkanRT;
import com.blockreality.api.client.rendering.vulkan.BRAdaRTConfig;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Phase 8 — RT 渲染 Pass 排序整合。
 *
 * <h3>三條路徑定義（TASK_MANUAL § Phase 8）</h3>
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
 * <p>沿用 {@link RenderPass} 既有順序，透過 {@link BRVulkanRT#renderFrameLegacy} 調用。
 *
 * <h3>執行模型</h3>
 * <p>{@link #dispatchFrame(RenderPassContext)} 根據
 * {@link BRAdaRTConfig#getGpuTier()} 自動選擇路徑並依序調用各子系統。
 * 任一 Pass 拋出未受控的 {@link RuntimeException} 時，
 * 同一幀的後續 Pass 仍繼續執行（以防止整幀黑屏），
 * 錯誤以 WARN 等級記錄。
 *
 * @see RTRenderPass
 * @see RenderPass
 * @see BRAdaRTConfig
 * @see BRVulkanRT
 */
@OnlyIn(Dist.CLIENT)
public final class BRRTPipelineOrdering {

    private static final Logger LOG = LoggerFactory.getLogger("BR-RTPipeline");

    // ─── 固化 Pass 序列（不可變） ──────────────────────────────────────────

    /**
     * Blackwell 路徑 Pass 序列（RTX 50xx，SM 10.x）。
     * <pre>
     * GBUFFER → CLUSTER_BVH_UPDATE → RESTIR_DI → RESTIR_GI
     *         → NRD → DLSS_SR → DLSS_MFG → TONEMAP → UI
     * </pre>
     */
    public static final List<RTRenderPass> BLACKWELL_PASSES = List.of(
        RTRenderPass.GBUFFER,
        RTRenderPass.CLUSTER_BVH_UPDATE,
        RTRenderPass.SDF_UPDATE,
        RTRenderPass.RESTIR_DI,
        RTRenderPass.RESTIR_GI,
        RTRenderPass.SDF_GI_AO,
        RTRenderPass.NRD,
        RTRenderPass.DLSS_SR,
        RTRenderPass.DLSS_MFG,
        RTRenderPass.TONEMAP,
        RTRenderPass.UI
    );

    /**
     * Ada 路徑 Pass 序列（RTX 40xx，SM 8.9）。
     * <pre>
     * GBUFFER → BLAS_TLAS_UPDATE → RT_SHADOW_AO
     *         → DDGI_UPDATE → DDGI_SAMPLE
     *         → NRD → DLSS_SR → DLSS_FG → TONEMAP → UI
     * </pre>
     */
    public static final List<RTRenderPass> ADA_PASSES = List.of(
        RTRenderPass.GBUFFER,
        RTRenderPass.BLAS_TLAS_UPDATE,
        RTRenderPass.SDF_UPDATE,
        RTRenderPass.RT_SHADOW_AO,
        RTRenderPass.DDGI_UPDATE,
        RTRenderPass.DDGI_SAMPLE,
        RTRenderPass.SDF_GI_AO,
        RTRenderPass.NRD,
        RTRenderPass.DLSS_SR,
        RTRenderPass.DLSS_FG,
        RTRenderPass.TONEMAP,
        RTRenderPass.UI
    );

    /**
     * Legacy 路徑 Pass 序列（RTX 20/30xx）。
     * <p>使用原 {@link RenderPass} 枚舉的既有順序，
     * 此列表僅供 UI 展示和測試查詢用。
     */
    public static final List<RenderPass> LEGACY_PASSES = List.of(
        RenderPass.SHADOW,
        RenderPass.GBUFFER_TERRAIN,
        RenderPass.GBUFFER_ENTITIES,
        RenderPass.GBUFFER_BLOCK_ENTITIES,
        RenderPass.GBUFFER_TRANSLUCENT,
        RenderPass.DEFERRED_LIGHTING,
        RenderPass.COMPOSITE_SSAO,
        RenderPass.COMPOSITE_VOLUMETRIC,    // P2-C: God Ray / 體積霧
        RenderPass.COMPOSITE_BLOOM,
        RenderPass.COMPOSITE_TONEMAP,
        RenderPass.FINAL,
        RenderPass.OVERLAY_UI,
        RenderPass.OVERLAY_EFFECT
    );

    // 工具類別，禁止實例化
    private BRRTPipelineOrdering() {}

    // ─── 路徑查詢 API ────────────────────────────────────────────────────

    /**
     * 根據當前 GPU 層級回傳對應的 RT Pass 序列。
     *
     * @return {@link #BLACKWELL_PASSES}、{@link #ADA_PASSES}，
     *         或 Legacy 路徑下返回空列表（呼叫端使用 {@link #LEGACY_PASSES}）
     */
    public static List<RTRenderPass> getActivePasses() {
        return switch (BRAdaRTConfig.getGpuTier()) {
            case BRAdaRTConfig.TIER_BLACKWELL -> BLACKWELL_PASSES;
            case BRAdaRTConfig.TIER_ADA       -> ADA_PASSES;
            default                           -> List.of();  // Legacy 路徑
        };
    }

    /**
     * 回傳當前路徑的人類可讀名稱（用於日誌與 HUD 除錯）。
     */
    public static String getActivePathName() {
        return switch (BRAdaRTConfig.getGpuTier()) {
            case BRAdaRTConfig.TIER_BLACKWELL -> "Blackwell (RTX 50xx)";
            case BRAdaRTConfig.TIER_ADA       -> "Ada (RTX 40xx)";
            default                           -> "Legacy RT (RTX 20/30xx)";
        };
    }

    // ─── 幀調度入口 ────────────────────────────────────────────────────────

    /**
     * 執行當前 GPU 路徑的完整渲染幀。
     *
     * <p>根據 {@link BRAdaRTConfig#getGpuTier()} 分派至對應的私有方法：
     * <ul>
     *   <li>{@link #dispatchBlackwellFrame(RenderPassContext)}</li>
     *   <li>{@link #dispatchAdaFrame(RenderPassContext)}</li>
     *   <li>{@link #dispatchLegacyFrame(RenderPassContext)}</li>
     * </ul>
     *
     * <p>任何非預期 {@link RuntimeException} 均被攔截並以 WARN 記錄，
     * 以確保三條路徑各自獨立運行不 crash。
     *
     * @param ctx 本幀渲染上下文（攝影機、FBO、時間等）
     */
    public static void dispatchFrame(RenderPassContext ctx) {
        try {
            int tier = BRAdaRTConfig.getGpuTier();
            if (tier == BRAdaRTConfig.TIER_BLACKWELL) {
                dispatchBlackwellFrame(ctx);
            } else if (tier == BRAdaRTConfig.TIER_ADA) {
                dispatchAdaFrame(ctx);
            } else {
                dispatchLegacyFrame(ctx);
            }
        } catch (RuntimeException e) {
            LOG.warn("[RTPipeline] Unhandled exception in dispatchFrame — path={} frame will degrade",
                getActivePathName(), e);
        }
    }

    // ─── Blackwell 路徑 ────────────────────────────────────────────────────

    /**
     * Blackwell Pass 執行序列（RTX 50xx）。
     * <pre>
     * GBUFFER → CLUSTER_BVH_UPDATE → RESTIR_DI → RESTIR_GI
     *         → NRD → DLSS_SR → DLSS_MFG → TONEMAP → UI
     * </pre>
     */
    private static void dispatchBlackwellFrame(RenderPassContext ctx) {
        // 1. GBuffer 幾何填充
        runPass(RTRenderPass.GBUFFER, ctx, () ->
            BRVulkanRT.renderGBuffer(ctx));

        // 2. Cluster BVH 更新（VK_NV_cluster_acceleration_structure）
        runPass(RTRenderPass.CLUSTER_BVH_UPDATE, ctx, () ->
            BRClusterBVH.getInstance().rebuildAllDirty());

        // 2.5. SDF Volume 增量更新
        runPass(RTRenderPass.SDF_UPDATE, ctx, () -> {
            BRSDFVolumeManager sdf = BRSDFVolumeManager.getInstance();
            sdf.setCameraPosition(ctx.getCamX(), ctx.getCamY(), ctx.getCamZ());
            sdf.updateSDF();
        });

        // 3. ReSTIR DI（直接光）— 委託 BRVulkanRT Phase 8 dispatch
        runPass(RTRenderPass.RESTIR_DI, ctx, () ->
            BRVulkanRT.dispatchReSTIRDI(ctx));

        // 4. ReSTIR GI（間接光）— 委託 BRVulkanRT Phase 8 dispatch
        runPass(RTRenderPass.RESTIR_GI, ctx, () ->
            BRVulkanRT.dispatchReSTIRGI(ctx));

        // 4.5. SDF Ray Marching GI + AO（遠距補充，結果餵入 NRD 一起降噪）
        runPass(RTRenderPass.SDF_GI_AO, ctx, () ->
            BRSDFRayMarcher.getInstance().dispatch());

        // 5. NRD 降噪（ReBLUR 於 Blackwell 路徑）
        //    降噪器優先順序：NRD JNI → ReLAX（Vulkan compute）
        runPass(RTRenderPass.NRD, ctx, () -> {
            if (BRNRDNative.isNrdAvailable()) {
                BRVulkanRT.dispatchNRD();
            } else if (BRReLAXDenoiser.isInitialized()) {
                BRVulkanRT.dispatchReLAXFallback(ctx);
            }
        });

        // 6. DLSS 4 Super Resolution
        runPass(RTRenderPass.DLSS_SR, ctx, () ->
            BRDLSS4Manager.getInstance().onFrameStart());

        // 7. DLSS 4 Multi-Frame Generation（×3 幀生成，Blackwell 專屬）
        runPass(RTRenderPass.DLSS_MFG, ctx, () ->
            BRVulkanRT.dispatchDLSSMultiFrameGen());

        // 8. Tone Mapping
        runPass(RTRenderPass.TONEMAP, ctx, () ->
            BRVulkanRT.dispatchTonemap(ctx));

        // 9. UI 覆蓋層
        runPass(RTRenderPass.UI, ctx, () ->
            BRVulkanRT.dispatchUI(ctx));

        // RT-5-2: 幀結束 — 交換深度 ping-pong（本幀深度 → 下幀 prevDepth）
        BRGBufferAttachments.getInstance().swapDepthBuffers();
    }

    // ─── Ada 路徑 ──────────────────────────────────────────────────────────

    /**
     * Ada Pass 執行序列（RTX 40xx）。
     * <pre>
     * GBUFFER → BLAS_TLAS_UPDATE → RT_SHADOW_AO
     *         → DDGI_UPDATE → DDGI_SAMPLE
     *         → NRD → DLSS_SR → DLSS_FG → TONEMAP → UI
     * </pre>
     */
    private static void dispatchAdaFrame(RenderPassContext ctx) {
        // 1. GBuffer 幾何填充
        runPass(RTRenderPass.GBUFFER, ctx, () ->
            BRVulkanRT.renderGBuffer(ctx));

        // 2. BLAS/TLAS 增量更新
        runPass(RTRenderPass.BLAS_TLAS_UPDATE, ctx, () ->
            BRVulkanBVH.updateTLAS());

        // 2.5. SDF Volume 增量更新
        runPass(RTRenderPass.SDF_UPDATE, ctx, () -> {
            BRSDFVolumeManager sdf = BRSDFVolumeManager.getInstance();
            sdf.setCameraPosition(ctx.getCamX(), ctx.getCamY(), ctx.getCamZ());
            sdf.updateSDF();
        });

        // 3. RT Shadow + AO 合併（SER + Ray Query Compute）
        runPass(RTRenderPass.RT_SHADOW_AO, ctx, () ->
            BRVulkanRT.dispatchShadowAndAO(ctx));

        // 4. DDGI Probe 更新（發送 probe ray，更新 Irradiance/Visibility 貼圖）
        runPass(RTRenderPass.DDGI_UPDATE, ctx, () -> {
            Vector3f camPos = new Vector3f(
                (float) ctx.getCamX(), (float) ctx.getCamY(), (float) ctx.getCamZ());
            BRDDGIProbeSystem.getInstance().onFrameStart(camPos, /* updateRatio = */ 0.25f);
        });

        // 5. DDGI Sampling（幾何表面採樣 Irradiance Volume）
        runPass(RTRenderPass.DDGI_SAMPLE, ctx, () ->
            BRVulkanRT.dispatchDDGISample(ctx));

        // 5.5. SDF Ray Marching GI + AO（遠距補充）
        runPass(RTRenderPass.SDF_GI_AO, ctx, () ->
            BRSDFRayMarcher.getInstance().dispatch());

        // 6. NRD 降噪（ReLAX + SIGMA 於 Ada 路徑）
        //    降噪器優先順序：NRD JNI → BRReLAXDenoiser（Vulkan compute）
        runPass(RTRenderPass.NRD, ctx, () -> {
            if (BRNRDNative.isNrdAvailable()) {
                BRVulkanRT.dispatchNRD();
            } else if (BRReLAXDenoiser.isInitialized()) {
                BRVulkanRT.dispatchReLAXFallback(ctx);
            }
        });

        // 7. DLSS 3.5 Super Resolution
        runPass(RTRenderPass.DLSS_SR, ctx, () ->
            BRDLSS4Manager.getInstance().onFrameStart());

        // 8. DLSS 3.5 Frame Generation（×1）
        runPass(RTRenderPass.DLSS_FG, ctx, () ->
            BRVulkanRT.dispatchDLSSFrameGen());

        // 9. Tone Mapping
        runPass(RTRenderPass.TONEMAP, ctx, () ->
            BRVulkanRT.dispatchTonemap(ctx));

        // 10. UI 覆蓋層
        runPass(RTRenderPass.UI, ctx, () ->
            BRVulkanRT.dispatchUI(ctx));

        // RT-5-2: 幀結束 — 交換深度 ping-pong（本幀深度 → 下幀 prevDepth）
        BRGBufferAttachments.getInstance().swapDepthBuffers();
    }

    // ─── Legacy 路徑 ───────────────────────────────────────────────────────

    /**
     * Legacy RT Pass 執行序列（RTX 20/30xx 及 AMD/Intel 跨廠商）。
     *
     * <p>沿用既有 {@link BRVulkanRT} 的單幀調度，不進入 Phase 8 Blackwell/Ada 路徑。
     * 但套用以下 P1/P2 跨廠商強化：
     * <ul>
     *   <li>降噪：NRD JNI → BRReLAXDenoiser（Vulkan compute）</li>
     *   <li>升頻：FSR（BRFSRManager，跨廠商 EASU+RCAS）</li>
     * </ul>
     */
    private static void dispatchLegacyFrame(RenderPassContext ctx) {
        LOG.trace("[RTPipeline] Legacy path — delegating to BRVulkanRT.renderFrameLegacy()");
        BRVulkanRT.renderFrameLegacy(ctx);

        // P2-A: 降噪（優先序：NRD → ReLAX）
        if (BRNRDNative.isNrdAvailable()) {
            BRVulkanRT.dispatchNRD();
        } else if (BRReLAXDenoiser.isInitialized()) {
            BRVulkanRT.dispatchReLAXFallback(ctx);
        }

        // P2-C: 體積光照（God Ray + 大氣霧）
        if (BRVolumetricLighting.getInstance().isActive()) {
            // RT-5-2: GBuffer 深度圖作為散射深度；當前幀陰影圖暫無獨立 VkImageView，使用深度圖代替
            BRGBufferAttachments gbuf = BRGBufferAttachments.getInstance();
            long depthView      = gbuf.getDepthView();   // R32F 線性深度（GENERAL）
            long shadowMapView  = gbuf.getDepthView();   // 暫以深度圖作為 shadow map（待 ShadowPass 整合）
            BRVolumetricLighting.getInstance().dispatch(depthView, shadowMapView);
        }

        // P1-B: FSR 升頻（跨廠商；AMD/Intel 路徑取代 DLSS）
        if (BRFSRManager.getInstance().isActive()) {
            // RT-5-2: RT 輸出圖作為 FSR 輸入；RT 輸出圖同時作為目標（in-place upscale）
            long rtView = BRVulkanRT.getRtOutputImageView();
            BRFSRManager.getInstance().dispatch(rtView, rtView);
        }

        // RT-5-2: 幀結束 — 交換深度 ping-pong（本幀深度 → 下幀 prevDepth）
        BRGBufferAttachments.getInstance().swapDepthBuffers();
    }

    // ─── 單 Pass 執行包裝 ──────────────────────────────────────────────────

    /**
     * 執行單一 Pass 並攔截 {@link RuntimeException}。
     *
     * <p>Pass 執行失敗不終止後續 Pass，確保三條路徑各自獨立不 crash。
     * 錯誤以 WARN 記錄並附帶 pass ID，方便 Profiler 追蹤。
     *
     * @param pass 正在執行的 Pass（用於日誌）
     * @param ctx  渲染上下文（目前僅用於日誌）
     * @param task Pass 執行體
     */
    private static void runPass(RTRenderPass pass, RenderPassContext ctx, Runnable task) {
        try {
            LOG.trace("[RTPipeline] Begin pass: {}", pass.getId());
            task.run();
            LOG.trace("[RTPipeline] End pass: {}", pass.getId());
        } catch (RuntimeException e) {
            LOG.warn("[RTPipeline] Pass '{}' failed — skipping rest of pass, frame continues",
                pass.getId(), e);
        }
    }
}
