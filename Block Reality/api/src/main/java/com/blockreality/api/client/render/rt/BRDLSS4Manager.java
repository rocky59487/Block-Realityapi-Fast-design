package com.blockreality.api.client.render.rt;

import com.blockreality.api.client.rendering.vulkan.BRAdaRTConfig;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BRDLSS4Manager — DLSS 4 超解析度 + Multi-Frame Generation 管理器。
 *
 * <h3>DLSS 4 概述</h3>
 * <p>DLSS 4（Deep Learning Super Sampling 4）是 NVIDIA 於 2025 年針對 Blackwell GPU
 * 推出的第四代 AI 升頻技術，相比 DLSS 3 有以下改進：
 *
 * <ul>
 *   <li><b>Multi-Frame Generation（MFG）</b>：Blackwell 架構專屬，
 *       每幀渲染產生最多 3 個 AI 生成幀（1 渲染幀→4 輸出幀），
 *       有效幀率提升至 4×（實際受 CPU/GPU 瓶頸限制）</li>
 *   <li><b>Transformer 架構</b>：DLSS 4 SR 改用 Transformer 模型（vs DLSS 3 的 CNN），
 *       在細節還原和時域穩定性上更優</li>
 *   <li><b>Ray Reconstruction</b>：整合到 RT denoising pipeline，
 *       替換傳統降噪器（NRD）的部分功能</li>
 * </ul>
 *
 * <h3>硬體分支</h3>
 * <ul>
 *   <li><b>Blackwell（SM 10.x）</b>：DLSS 4 MFG（1→4 幀）+ SR + RR</li>
 *   <li><b>Ada（SM 8.9）</b>：DLSS 3 FG（1→2 幀）+ SR（DLSS 4 Transformer 模型可選）</li>
 *   <li><b>Ampere 以下</b>：僅 DLSS SR（降至 DLSS 2/3 品質），無 FG</li>
 * </ul>
 *
 * <h3>整合架構</h3>
 * <pre>
 * [Rendered Frame @ 1080p]
 *   → DLSS SR（1080p → 4K，AI 升頻）
 *   → DLSS MFG（4K × 1 → 4K × 4，AI 生成幀）
 *   → Tonemap + UI overlay（每幀 UI 重新合成）
 *   → Swapchain Present
 * </pre>
 *
 * <h3>Java 端職責</h3>
 * <p>由於 DLSS SDK 僅提供 C++ API（NVIDIA Streamline SDK），
 * 此 Java 管理器負責：
 * <ul>
 *   <li>管理 DLSS 的 JNI 橋接狀態（{@link #isDlssAvailable()}）</li>
 *   <li>維護 SR / MFG 的輸入參數（解析度、模式、曝光補正）</li>
 *   <li>在 Vulkan pipeline 中正確插入 DLSS pass 的 Semaphore 信號</li>
 *   <li>提供 fallback 邏輯（DLSS 不可用時退化為雙線性升頻）</li>
 * </ul>
 *
 * <h3>MFG 曝光補正</h3>
 * <p>啟用 MFG 時，渲染幀的曝光需要根據生成幀數量做補正，
 * 以避免動態模糊過度疊加。補正係數由 {@link #getMfgExposureScale()} 提供，
 * 注入到 {@code CameraFrame.mfgExposureScale} UBO 欄位（見 {@code primary.rgen.glsl}）。
 *
 * @see BRRTSettings#isEnableDLSS()
 * @see BRRTSettings#isEnableFrameGeneration()
 * @see BRAdaRTConfig
 */
@OnlyIn(Dist.CLIENT)
public final class BRDLSS4Manager {

    private static final Logger LOGGER = LoggerFactory.getLogger("BR-DLSS4");

    // ════════════════════════════════════════════════════════════════════════
    //  DLSS 模式常數（對應 BRRTSettings.getDlssMode()）
    // ════════════════════════════════════════════════════════════════════════

    /** DLSS 品質模式：Native AA（DLSS SR 關閉，僅 MFG）。 */
    public static final int MODE_NATIVE_AA   = 0;
    /** DLSS 品質模式：Quality（33% 縮放，0.58× 內部解析度）。 */
    public static final int MODE_QUALITY     = 1;
    /** DLSS 品質模式：Balanced（50% 縮放，0.67× 內部解析度）。 */
    public static final int MODE_BALANCED    = 2;
    /** DLSS 品質模式：Performance（75% 縮放，0.50× 內部解析度）。 */
    public static final int MODE_PERFORMANCE = 3;
    /** DLSS 品質模式：Ultra Performance（3× 縮放，DLSS 3+）。 */
    public static final int MODE_ULTRA_PERF  = 4;

    /**
     * MFG 生成幀數量對應的曝光補正係數。
     * <pre>
     *   1 幀（無 MFG）    : scale = 1.0
     *   2 幀（DLSS 3 FG） : scale = 1.0（FG 不影響曝光）
     *   4 幀（DLSS 4 MFG）: scale = 0.97（微幅降低過曝風險）
     * </pre>
     */
    private static final float[] MFG_EXPOSURE_SCALE = {1.0f, 1.0f, 1.0f, 0.97f};

    // ════════════════════════════════════════════════════════════════════════
    //  Singleton
    // ════════════════════════════════════════════════════════════════════════

    private static final BRDLSS4Manager INSTANCE = new BRDLSS4Manager();

    public static BRDLSS4Manager getInstance() { return INSTANCE; }

    private BRDLSS4Manager() {}

    // ════════════════════════════════════════════════════════════════════════
    //  狀態
    // ════════════════════════════════════════════════════════════════════════

    private boolean dlssAvailable       = false;  // DLSS SDK JNI 是否載入成功
    private boolean mfgAvailable        = false;  // Blackwell MFG 是否可用
    private boolean initialized         = false;

    // 當前渲染解析度（內部，輸入至 DLSS SR）
    private int inputWidth  = 1920;
    private int inputHeight = 1080;

    // 當前輸出解析度（display，DLSS SR 輸出）
    private int outputWidth  = 3840;
    private int outputHeight = 2160;

    // 當前模式
    private int dlssMode           = MODE_BALANCED;
    private boolean frameGenEnabled = false;
    private int     mfgFrameCount  = 1;   // 1 = 無 MFG，2 = FG，4 = MFG

    // JNI 狀態 handle（Stub；生產中為 DLSS/Streamline feature handle）
    private long dlssSRHandle  = 0L;
    private long dlssMFGHandle = 0L;

    // 統計
    private long  framesRendered   = 0L;
    private long  framesPresented  = 0L;  // 包含生成幀
    private float lastMeasuredFps  = 0.0f;

    // ════════════════════════════════════════════════════════════════════════
    //  生命週期
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 初始化 DLSS 4 系統。
     *
     * @param inputW    渲染解析度寬（DLSS SR 輸入）
     * @param inputH    渲染解析度高
     * @param outputW   顯示解析度寬（DLSS SR 輸出）
     * @param outputH   顯示解析度高
     * @param mode      DLSS 模式（{@link #MODE_QUALITY} 等）
     * @param enableFG  是否啟用 Frame Generation
     */
    public boolean init(int inputW, int inputH, int outputW, int outputH,
                        int mode, boolean enableFG) {
        if (initialized) {
            LOGGER.warn("[DLSS4] Already initialized; call reconfigure() to change settings");
            return dlssAvailable;
        }

        this.inputWidth     = inputW;
        this.inputHeight    = inputH;
        this.outputWidth    = outputW;
        this.outputHeight   = outputH;
        this.dlssMode       = mode;
        this.frameGenEnabled = enableFG;

        // ── JNI 載入嘗試（Stub）────────────────────────────────────────────
        // 生產：dlssAvailable = BRNativeLibrary.loadDLSS();
        // 生產：mfgAvailable  = BRNativeLibrary.loadDLSSMFG() && BRAdaRTConfig.isBlackwellOrNewer();
        try {
            // Stub: 假設 DLSS 可用（生產時由 JNI 回傳真實狀態）
            dlssAvailable = attemptDLSSInit();
            mfgAvailable  = dlssAvailable && BRAdaRTConfig.isBlackwellOrNewer();

            if (dlssAvailable) {
                dlssSRHandle  = 100L;  // Stub handle
                dlssMFGHandle = mfgAvailable && enableFG ? 101L : 0L;
                mfgFrameCount = mfgAvailable && enableFG ? 4
                              : (BRAdaRTConfig.isAdaOrNewer() && enableFG ? 2 : 1);
            } else {
                dlssSRHandle  = 0L;
                dlssMFGHandle = 0L;
                mfgFrameCount = 1;
            }

            initialized = true;
            LOGGER.info("[DLSS4] Init: SR={}, MFG={}, mode={}, input={}×{}, output={}×{}, " +
                "fgEnabled={}, mfgFrames={}",
                dlssAvailable, mfgAvailable, modeName(mode),
                inputW, inputH, outputW, outputH,
                enableFG, mfgFrameCount);
            return dlssAvailable;

        } catch (Exception e) {
            LOGGER.error("[DLSS4] Initialization failed", e);
            dlssAvailable = false;
            mfgAvailable  = false;
            initialized   = true;  // 已初始化（失敗狀態）
            return false;
        }
    }

    /**
     * 嘗試載入 DLSS SDK（Stub 實作）。
     * 生產環境中呼叫 {@code com.blockreality.api.native.BRNativeLibrary.loadStreamline()}。
     */
    private boolean attemptDLSSInit() {
        // Stub：永遠回傳 false（安全 fallback）
        // 生產：return System.getProperty("os.name").toLowerCase().contains("win")
        //           && BRNativeLibrary.loadStreamline("sl.interposer.dll");
        LOGGER.debug("[DLSS4] Stub: DLSS SDK not loaded (production: load sl.interposer)");
        return false;
    }

    /**
     * 重新設定 DLSS 參數（解析度改變或模式切換）。
     */
    public boolean reconfigure(int inputW, int inputH, int outputW, int outputH,
                                int mode, boolean enableFG) {
        if (!initialized) return init(inputW, inputH, outputW, outputH, mode, enableFG);

        boolean changed = (inputW != this.inputWidth || inputH != this.inputHeight
                        || outputW != this.outputWidth || outputH != this.outputHeight
                        || mode != this.dlssMode || enableFG != this.frameGenEnabled);
        if (!changed) return dlssAvailable;

        LOGGER.info("[DLSS4] Reconfigure: {}×{}→{}×{}, mode={}, FG={}",
            inputW, inputH, outputW, outputH, modeName(mode), enableFG);

        this.inputWidth     = inputW;
        this.inputHeight    = inputH;
        this.outputWidth    = outputW;
        this.outputHeight   = outputH;
        this.dlssMode       = mode;
        this.frameGenEnabled = enableFG;
        this.mfgFrameCount  = dlssAvailable && mfgAvailable && enableFG ? 4
                            : (dlssAvailable && BRAdaRTConfig.isAdaOrNewer() && enableFG ? 2 : 1);

        return dlssAvailable;
    }

    /**
     * 釋放 DLSS 資源。
     */
    public void cleanup() {
        if (!initialized) return;
        // 生產：BRNativeLibrary.destroyDLSSFeature(dlssSRHandle);
        //       BRNativeLibrary.destroyDLSSFeature(dlssMFGHandle);
        dlssSRHandle  = 0L;
        dlssMFGHandle = 0L;
        initialized   = false;
        dlssAvailable = false;
        mfgAvailable  = false;
        LOGGER.info("[DLSS4] Cleanup complete");
    }

    // ════════════════════════════════════════════════════════════════════════
    //  每幀操作
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 幀開始：通知 DLSS MFG 新的渲染幀開始（用於 MFG 時序同步）。
     */
    public void onFrameStart() {
        framesRendered++;
    }

    /**
     * 幀結束：提交 DLSS 計算（SR + MFG）。
     *
     * <p>生產環境中此方法透過 JNI 呼叫 Streamline 的
     * {@code slDLSSGetOptimalSettings()} 和 {@code slDLSSEvaluate()}。
     *
     * @param colorBuffer   渲染幀 Vulkan image handle（HDR，pre-tonemap）
     * @param depthBuffer   深度 buffer handle（用於 MFG temporal reprojection）
     * @param motionVectors Motion vector buffer handle（DLSS temporal stabilization）
     * @param outputBuffer  輸出 buffer handle（SR 升頻後的 4K buffer）
     * @param exposureScale 曝光補正係數（由 {@link #getMfgExposureScale()} 提供）
     */
    public void evaluate(long colorBuffer, long depthBuffer,
                         long motionVectors, long outputBuffer,
                         float exposureScale) {
        if (!dlssAvailable) {
            // Fallback：bilinear upscale（由 pipeline 處理，此方法無需額外操作）
            framesPresented += mfgFrameCount;
            return;
        }

        // 生產：BRNativeLibrary.dlssEvaluateSR(dlssSRHandle, colorBuffer, depthBuffer,
        //           motionVectors, outputBuffer, inputWidth, inputHeight,
        //           outputWidth, outputHeight, exposureScale, dlssMode);
        // 生產（MFG）：BRNativeLibrary.dlssEvaluateMFG(dlssMFGHandle, outputBuffer,
        //           depthBuffer, motionVectors, mfgFrameCount);

        framesPresented += mfgFrameCount;
        LOGGER.trace("[DLSS4] evaluate(): SR+MFG, frames presented = {}", framesPresented);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  解析度計算工具
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 根據 DLSS 模式計算推薦的渲染（輸入）解析度比例。
     * DLSS SR 在此比例下最佳化。
     *
     * @param outputW    目標顯示寬度（pixels）
     * @param outputH    目標顯示高度（pixels）
     * @param mode       DLSS 模式
     * @return int[2]：{推薦渲染寬, 推薦渲染高}
     */
    public static int[] recommendedInputResolution(int outputW, int outputH, int mode) {
        float scale = switch (mode) {
            case MODE_NATIVE_AA   -> 1.0f;
            case MODE_QUALITY     -> 0.667f;  // 2/3
            case MODE_BALANCED    -> 0.579f;  // ~0.58
            case MODE_PERFORMANCE -> 0.500f;  // 1/2
            case MODE_ULTRA_PERF  -> 0.333f;  // 1/3
            default               -> 0.579f;
        };
        return new int[]{
            Math.max(1, Math.round(outputW * scale)),
            Math.max(1, Math.round(outputH * scale))
        };
    }

    /**
     * 計算 MFG 曝光補正係數。
     *
     * <p>當 DLSS 4 MFG 生成多個幀時，每個生成幀的運動模糊強度會累積；
     * 降低曝光可補正此過曝現象，確保 HDR 亮度一致性。
     *
     * @return 曝光補正係數（注入到 CameraFrame.mfgExposureScale UBO 欄位）
     */
    public float getMfgExposureScale() {
        if (!dlssAvailable || !frameGenEnabled) return 1.0f;
        int idx = Math.min(mfgFrameCount, MFG_EXPOSURE_SCALE.length - 1);
        return MFG_EXPOSURE_SCALE[idx];
    }

    // ════════════════════════════════════════════════════════════════════════
    //  查詢 / 統計
    // ════════════════════════════════════════════════════════════════════════

    public boolean isInitialized()       { return initialized; }
    public boolean isDlssAvailable()     { return dlssAvailable; }
    public boolean isMfgAvailable()      { return mfgAvailable; }
    public boolean isFrameGenEnabled()   { return frameGenEnabled && dlssAvailable; }
    public int     getMfgFrameCount()    { return mfgFrameCount; }
    public int     getDlssMode()         { return dlssMode; }
    public int     getInputWidth()       { return inputWidth; }
    public int     getInputHeight()      { return inputHeight; }
    public int     getOutputWidth()      { return outputWidth; }
    public int     getOutputHeight()     { return outputHeight; }
    public long    getFramesRendered()   { return framesRendered; }
    public long    getFramesPresented()  { return framesPresented; }
    public long    getDlssSRHandle()     { return dlssSRHandle; }
    public long    getDlssMFGHandle()    { return dlssMFGHandle; }

    /**
     * 估算 DLSS 4 的有效幀率倍率（純計算，不實際測量）。
     * @return 理論倍率（1.0 = 無 FG，2.0 = DLSS 3 FG，4.0 = DLSS 4 MFG）
     */
    public float getEffectiveFrameMultiplier() {
        if (!dlssAvailable || !frameGenEnabled) return 1.0f;
        return (float) mfgFrameCount;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  內部工具
    // ════════════════════════════════════════════════════════════════════════

    private static String modeName(int mode) {
        return switch (mode) {
            case MODE_NATIVE_AA   -> "NativeAA";
            case MODE_QUALITY     -> "Quality";
            case MODE_BALANCED    -> "Balanced";
            case MODE_PERFORMANCE -> "Performance";
            case MODE_ULTRA_PERF  -> "UltraPerf";
            default               -> "Unknown(" + mode + ")";
        };
    }
}
