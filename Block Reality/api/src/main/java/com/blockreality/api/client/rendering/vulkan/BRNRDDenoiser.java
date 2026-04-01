package com.blockreality.api.client.rendering.vulkan;

import com.blockreality.api.client.render.rt.BRNRDNative;
import com.blockreality.api.client.render.rt.BRSVGFDenoiser;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BR NRD Denoiser — NVIDIA Real-time Denoisers (NRD) 整合骨架。
 *
 * <p>NRD 提供 ReLAX（反射/GI）和 ReBLUR（高斯模糊降噪）兩個演算法，
 * 效果優於 SVGF，但需要 NRD SDK 原生 library。
 *
 * <h3>目前狀態（Phase 2）</h3>
 * <p>NRD SDK 整合尚未完成，此類別作為介面定義骨架，
 * 並在 NRD 不可用時自動回退至現有的 {@link BRSVGFDenoiser}。
 *
 * <h3>完整整合計劃（Phase 3）</h3>
 * <ul>
 *   <li>NRD SDK JAR/JNI 包裝（或直接使用 LWJGL Vulkan compute shader 複現）</li>
 *   <li>ReLAX：適合反射和漫反射 GI（時空重投影 + 歷史積累）</li>
 *   <li>ReBLUR：適合環境遮蔽（RTAO）</li>
 *   <li>輸入：noisy RT output + motion vectors + GBuffer（depth, normal, roughness）</li>
 *   <li>輸出：denoised radiance texture → GL/VK interop → OpenGL composite</li>
 * </ul>
 *
 * <h3>目前行為</h3>
 * <p>委託給 {@link BRSVGFDenoiser} 提供降噪服務，
 * 直到 NRD 完整整合後替換。
 *
 * @author Block Reality Team
 * @see BRSVGFDenoiser
 */
@OnlyIn(Dist.CLIENT)
@SuppressWarnings("deprecation") // Phase 4-F: BRVulkanInterop/BRSVGFDenoiser pending replacement
public final class BRNRDDenoiser {

    private static final Logger LOG = LoggerFactory.getLogger("BR-NRDDenoiser");

    /** NRD 演算法選擇 */
    public enum Algorithm {
        /** SVGF — 現有實作，立即可用 */
        SVGF,
        /** ReLAX — NRD SDK，適合 diffuse/specular GI（Phase 3） */
        RELAX,
        /** ReBLUR — NRD SDK，適合 RTAO（Phase 3） */
        REBLUR,
        /** 無降噪（debug 用） */
        NONE
    }

    // ── 狀態 ──────────────────────────────────────────────────────────
    private Algorithm activeAlgorithm = Algorithm.SVGF;
    private boolean nrdAvailable      = false; // NRD SDK 是否已載入
    private boolean initialized       = false;

    // ── 依賴 ──────────────────────────────────────────────────────────
    private final VkContext context;
    private long nrdHandle = 0L;

    public BRNRDDenoiser(VkContext context) {
        this.context = context;
    }

    // ─────────────────────────────────────────────────────────────────
    //  生命週期
    // ─────────────────────────────────────────────────────────────────

    /**
     * 初始化降噪器。
     * 嘗試載入 NRD SDK，失敗時回退至 SVGF。
     *
     * @param width  降噪輸入寬度
     * @param height 降噪輸入高度
     */
    public void init(int width, int height) {
        // 嘗試 NRD（Phase 3 實作 JNI 後解開）
        nrdAvailable = tryInitNRD(width, height);

        if (nrdAvailable) {
            LOG.info("NRD SDK initialized — using ReLAX+ReBLUR");
            activeAlgorithm = Algorithm.RELAX;
        } else {
            // 回退至 SVGF
            LOG.info("NRD not available, falling back to SVGF denoiser");
            activeAlgorithm = Algorithm.SVGF;
            initSVGF(width, height);
        }

        initialized = true;
    }

    public void cleanup() {
        if (!initialized) return;

        if (nrdAvailable) {
            cleanupNRD();
        } else {
            BRSVGFDenoiser.cleanup();
        }

        initialized    = false;
        nrdAvailable   = false;
    }

    // ─────────────────────────────────────────────────────────────────
    //  每幀 API
    // ─────────────────────────────────────────────────────────────────

    /**
     * 執行降噪 pass。
     *
     * @param noisyTexture    噪聲 RT 輸出紋理 ID（OpenGL）
     * @param depthTexture    GBuffer depth 紋理 ID
     * @param normalTexture   GBuffer normal 紋理 ID
     * @param motionTexture   motion vector 紋理 ID（可為 0）
     * @param outputTexture   降噪輸出紋理 ID
     * @param frameIndex      當前幀索引（時域累積使用）
     */
    public int denoise(int noisyTexture, int depthTexture, int normalTexture,
                        int motionTexture, int outputTexture, long frameIndex) {
        if (!initialized) return noisyTexture;

        // Synchronize with RT settings
        int requestedAlgo = com.blockreality.api.client.render.rt.BRRTSettings.getInstance().getDenoiserAlgo();
        Algorithm mapped = switch (requestedAlgo) {
            case 0 -> Algorithm.NONE;
            case 1 -> Algorithm.SVGF;
            case 2 -> Algorithm.RELAX; // We default to RELAX for NRD
            default -> Algorithm.SVGF;
        };

        if (this.activeAlgorithm != mapped) {
            setAlgorithm(mapped);
        }

        return switch (activeAlgorithm) {
            case SVGF -> denoiseWithSVGF(noisyTexture, depthTexture, normalTexture, outputTexture, frameIndex);
            case RELAX, REBLUR -> denoiseWithNRD(noisyTexture, depthTexture, normalTexture,
                motionTexture, outputTexture, frameIndex);
            case NONE -> noisyTexture; // 直接使用噪聲輸出（debug）
        };
    }

    /**
     * 強制切換降噪演算法。
     * 若請求 NRD 但 SDK 不可用，自動回退至 SVGF。
     */
    public void setAlgorithm(Algorithm algo) {
        if ((algo == Algorithm.RELAX || algo == Algorithm.REBLUR) && !nrdAvailable) {
            LOG.warn("NRD SDK not available, cannot switch to {}; staying on SVGF", algo);
            return;
        }
        this.activeAlgorithm = algo;
        LOG.info("Denoiser algorithm switched to: {}", algo);
    }

    // ─────────────────────────────────────────────────────────────────
    //  統計
    // ─────────────────────────────────────────────────────────────────

    public Algorithm getActiveAlgorithm() { return activeAlgorithm; }
    public boolean   isNRDAvailable()     { return nrdAvailable; }
    public boolean   isInitialized()      { return initialized; }

    // ─────────────────────────────────────────────────────────────────
    //  內部實作
    // ─────────────────────────────────────────────────────────────────

    /** 嘗試初始化 NRD SDK（Phase 3 JNI 整合） */
    private boolean tryInitNRD(int width, int height) {
        if (!BRNRDNative.isNrdAvailable()) return false;
        
        try {
            // maxFramesToAccumulate = 30
            nrdHandle = BRNRDNative.createDenoiser(width, height, 30);
            return nrdHandle != 0L;
        } catch (Exception e) {
            LOG.error("Failed to initialize NRD via JNI", e);
            return false;
        }
    }

    private void cleanupNRD() {
        if (nrdHandle != 0L) {
            try {
                BRNRDNative.destroyDenoiser(nrdHandle);
                nrdHandle = 0L;
            } catch (Exception e) {
                LOG.error("Failed to destroy NRD via JNI", e);
            }
        }
    }

    private void initSVGF(int width, int height) {
        try {
            BRSVGFDenoiser.init(width, height);
        } catch (Exception e) {
            LOG.warn("SVGF init error: {}", e.getMessage());
        }
    }

    /** SVGF 降噪：使用 passthrough（invViewProj 填零矩陣，Phase 3 完整傳入） */
    private int denoiseWithSVGF(int noisy, int depth, int normal, int output, long frame) {
        try {
            // BRSVGFDenoiser.denoise 返回 denoised texture ID（output 由內部管理）
            // 此橋接使用 identity matrices 作為 Phase 2 暫時替代
            org.joml.Matrix4f identity = new org.joml.Matrix4f();
            return com.blockreality.api.client.render.rt.BRSVGFDenoiser.denoise(noisy, normal, depth, depth, identity, identity);
        } catch (Exception e) {
            LOG.debug("SVGF denoise error: {}", e.getMessage());
            return noisy;
        }
    }

    private int denoiseWithNRD(int noisy, int depth, int normal, int motion, int output, long frame) {
        if (nrdHandle != 0L && BRNRDNative.isNrdAvailable()) {
            boolean success = false;
            try {
                // Here we usually need to convert GL texture IDs to long Vulkan handles/addresses.
                // For layout architecture purposes, we pass the integers cast to long.
                success = BRNRDNative.denoise(nrdHandle, noisy, normal, motion, depth, output);
            } catch (Exception e) {
                LOG.error("Native NRD dispatch failed: {}", e.getMessage());
            }
            
            if (success) return output; // NRD API 直接寫入至 output 紋理
        }

        // Fallback to SVGF if NRD dispatch fails or is unavailable
        return denoiseWithSVGF(noisy, depth, normal, output, frame);
    }
}
