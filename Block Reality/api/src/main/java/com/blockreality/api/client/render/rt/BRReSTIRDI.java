package com.blockreality.api.client.render.rt;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * BRReSTIRDI — ReSTIR DI（Reservoir-based Spatiotemporal Importance Resampling
 * for Direct Illumination）Reservoir Buffer 管理器。
 *
 * <h3>ReSTIR DI 演算法概述</h3>
 * <p>ReSTIR DI 是 2020 年 Bitterli et al. 提出的直接光照重要性採樣演算法，
 * 透過以下三個步驟大幅降低直接光照的變異數（noise）：
 *
 * <ol>
 *   <li><b>Initial Candidate Sampling</b>：從 Light BVH（{@link BRRTEmissiveManager}）
 *       為每個像素採樣 M 個候選光源（{@link BRRTSettings#getReSTIRDISpatialSamples()} 控制）</li>
 *   <li><b>Temporal Reuse</b>：將前一幀的 reservoir 與當前幀合併
 *       （M-cap = {@link BRRTSettings#getReSTIRDITemporalMaxM()}），
 *       允許跨幀重用重要光源，等效於增加採樣數</li>
 *   <li><b>Spatial Reuse</b>：從 k 個鄰近像素（半徑 {@link #SPATIAL_RADIUS} pixels）
 *       的 reservoir 採樣，進一步降低空間噪點</li>
 * </ol>
 *
 * <h3>Reservoir 結構（GPU SSBO 格式，16 bytes/pixel）</h3>
 * <pre>
 * uvec4 per pixel：
 *   [0] lightIdx    — 當前選中的光源索引（指向 BRRTEmissiveManager 的光源列表）
 *   [1] W_bits      — floatBitsToUint(W)，無偏估計權重（RIS weight）
 *   [2] M           — reservoir 的累積樣本數量（時域重用的歷史深度）
 *   [3] flags       — bit 0 = validity（光源仍在場景中），bit 1-31 = reserved
 * </pre>
 *
 * <h3>雙緩衝（Double Buffering）</h3>
 * <p>使用 current/previous 雙緩衝避免 GPU 讀寫衝突：
 * <ul>
 *   <li>Current buffer：當前幀寫入（compute shader 輸出）</li>
 *   <li>Previous buffer：時域重用讀取（前幀的 current）</li>
 *   <li>每幀 {@link #swap()} 交換兩個 buffer 的角色</li>
 * </ul>
 *
 * <h3>記憶體估算</h3>
 * <pre>
 * 全解析度（1920×1080）：1920 × 1080 × 16 bytes × 2 buffers = ~63 MB
 * 半解析度（VRAM < 8 GB fallback）：~16 MB
 * </pre>
 *
 * @see BRRTEmissiveManager
 * @see BRRTSettings
 */
@OnlyIn(Dist.CLIENT)
public final class BRReSTIRDI {

    private static final Logger LOGGER = LoggerFactory.getLogger("BR-ReSTIRDI");

    // ════════════════════════════════════════════════════════════════════════
    //  常數
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 每個 Reservoir 的 GPU 大小：uvec4 = 4 × uint = 16 bytes。
     */
    public static final int RESERVOIR_SIZE = 16;

    /**
     * 空間重用的搜尋半徑（pixels）。
     * 較大半徑提供更平滑的結果，但可能引入物理不正確的光照洩漏。
     * 建議值：8–32 pixels（場景密度越高應越小）。
     */
    public static final int SPATIAL_RADIUS = 16;

    /**
     * 時域 Reservoir M-cap（最大歷史樣本數）。
     * 從 {@link BRRTSettings#getReSTIRDITemporalMaxM()} 讀取，此為 fallback 預設值。
     */
    private static final int DEFAULT_TEMPORAL_MAX_M = 20;

    /**
     * 初始候選採樣數量（per-pixel 每幀採樣的候選光源數）。
     * 越多越準確，但 GPU 計算成本線性增加。
     */
    public static final int INITIAL_CANDIDATES = 32;

    /**
     * Reservoir validity bit mask（flags uvec4 [3] 的 bit 0）。
     */
    private static final int VALIDITY_BIT = 1;

    // ════════════════════════════════════════════════════════════════════════
    //  Singleton
    // ════════════════════════════════════════════════════════════════════════

    private static final BRReSTIRDI INSTANCE = new BRReSTIRDI();

    public static BRReSTIRDI getInstance() {
        return INSTANCE;
    }

    private BRReSTIRDI() {}

    // ════════════════════════════════════════════════════════════════════════
    //  狀態
    // ════════════════════════════════════════════════════════════════════════

    private boolean initialized     = false;
    private int     renderWidth     = 0;
    private int     renderHeight    = 0;
    private int     pixelCount      = 0;

    // 雙緩衝 GPU handle（Stub：實際 Vulkan 中為 VkBuffer handle）
    private long    currentReservoirBuffer  = 0L;
    private long    previousReservoirBuffer = 0L;
    private long    currentReservoirMemory  = 0L;
    private long    previousReservoirMemory = 0L;

    // Light BVH SSBO handle（由 BRRTEmissiveManager 填充）
    private long    lightBvhSsbo    = 0L;
    private long    lightListSsbo   = 0L;

    // 幀統計
    private long    frameCount      = 0L;
    private int     totalLightCount = 0;

    // ════════════════════════════════════════════════════════════════════════
    //  生命週期
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 初始化 ReSTIR DI 系統，分配雙緩衝 Reservoir SSBO。
     *
     * <p>需在 Vulkan 設備初始化後呼叫，且 RT 管線就緒。
     *
     * @param width   渲染目標寬度（pixels）
     * @param height  渲染目標高度（pixels）
     * @return true = 初始化成功；false = 失敗（ReSTIR DI 被停用）
     */
    public boolean init(int width, int height) {
        if (initialized) {
            LOGGER.warn("[ReSTIRDI] Already initialized, call resize() to change resolution");
            return true;
        }
        if (width <= 0 || height <= 0) {
            LOGGER.error("[ReSTIRDI] Invalid resolution: {}×{}", width, height);
            return false;
        }

        this.renderWidth  = width;
        this.renderHeight = height;
        this.pixelCount   = width * height;

        long bufferSize = (long) pixelCount * RESERVOIR_SIZE;

        try {
            // ── Stub：分配 CPU-side dummy buffer，記錄大小 ──────────────────
            // 生產環境：BRVulkanDevice.createBuffer(device, bufferSize, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)
            LOGGER.info("[ReSTIRDI] Allocating reservoir buffers: {}×{} = {} pixels, " +
                    "{} MB × 2 (double-buffered)",
                width, height, pixelCount, bufferSize / (1024 * 1024));

            // Stub handles（生產時為真實 Vulkan buffer handles）
            currentReservoirBuffer  = 1L; // placeholder
            previousReservoirBuffer = 2L; // placeholder
            currentReservoirMemory  = 3L; // placeholder
            previousReservoirMemory = 4L; // placeholder

            // 清空 Reservoir（所有像素初始化為空 reservoir：W=0, M=0）
            clearReservoirsCPU(width, height);

            initialized = true;
            LOGGER.info("[ReSTIRDI] Initialized: {}×{}, buffers=2×{} MB",
                width, height, bufferSize / (1024 * 1024));
            return true;

        } catch (Exception e) {
            LOGGER.error("[ReSTIRDI] Initialization failed", e);
            cleanup();
            return false;
        }
    }

    /**
     * 調整 Reservoir buffer 大小（視窗大小改變時呼叫）。
     *
     * @param newWidth  新渲染寬度
     * @param newHeight 新渲染高度
     */
    public void resize(int newWidth, int newHeight) {
        if (newWidth == renderWidth && newHeight == renderHeight) return;
        LOGGER.info("[ReSTIRDI] Resizing from {}×{} to {}×{}", renderWidth, renderHeight, newWidth, newHeight);
        cleanup();
        init(newWidth, newHeight);
    }

    /**
     * 清理所有 GPU 資源。
     */
    public void cleanup() {
        if (!initialized) return;
        LOGGER.info("[ReSTIRDI] Cleaning up reservoir buffers");
        // 生產：BRVulkanDevice.destroyBuffer(device, currentReservoirBuffer, currentReservoirMemory);
        // 生產：BRVulkanDevice.destroyBuffer(device, previousReservoirBuffer, previousReservoirMemory);
        currentReservoirBuffer  = 0L;
        previousReservoirBuffer = 0L;
        currentReservoirMemory  = 0L;
        previousReservoirMemory = 0L;
        lightBvhSsbo            = 0L;
        lightListSsbo           = 0L;
        initialized = false;
        frameCount  = 0L;
    }

    /** @return true if ReSTIR DI system has been initialized */
    public boolean isInitialized() {
        return initialized;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  每幀操作
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 幀開始時交換 Current / Previous Reservoir buffer。
     *
     * <p>呼叫順序（每幀）：
     * <ol>
     *   <li>{@code swap()} — 前幀 current 成為本幀 previous（時域重用讀取來源）</li>
     *   <li>發射 {@code restir_di.comp.glsl} compute pass（寫入 current）</li>
     *   <li>{@link #onFrameEnd()} — 更新幀計數統計</li>
     * </ol>
     */
    public void swap() {
        if (!initialized) return;

        long tmpBuf = currentReservoirBuffer;
        long tmpMem = currentReservoirMemory;

        currentReservoirBuffer  = previousReservoirBuffer;
        currentReservoirMemory  = previousReservoirMemory;
        previousReservoirBuffer = tmpBuf;
        previousReservoirMemory = tmpMem;
    }

    /**
     * 幀結束時呼叫，更新幀計數統計。
     */
    public void onFrameEnd() {
        frameCount++;
    }

    /**
     * 通知 ReSTIR DI 系統光源列表已更新（場景變化時呼叫）。
     *
     * <p>觸發下一幀的 reservoir 部分失效：validity bit 清除，
     * 強制重新採樣（防止 stale reservoir 指向已消失的光源）。
     *
     * @param newLightCount 更新後的光源數量
     */
    public void onLightsUpdated(int newLightCount) {
        this.totalLightCount = newLightCount;
        // Phase 2 實作：標記所有 reservoir 為 invalid（validity bit 清除）
        // 此處記錄 log，實際 GPU 端在 compute shader 中檢查 LightBVH version counter
        LOGGER.debug("[ReSTIRDI] Lights updated: {} lights, next frame reservoirs will resample",
            newLightCount);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Reservoir 數學（CPU 側測試用）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Reservoir update：將新光源候選合併到現有 reservoir（RIS 步驟）。
     *
     * <p>這是 ReSTIR DI 的核心數學，GPU 側在 {@code restir_di.comp.glsl} 中實作相同邏輯。
     * 此方法提供 CPU 側測試參考（用於 {@code BRReSTIRDITest}）。
     *
     * <p>RIS（Resampled Importance Sampling）更新規則：
     * <pre>
     * 給定現有 reservoir (y, W, M) 和候選光源 x_i：
     *   weight_i = p_hat(x_i) / p(x_i)   // 目標分佈 / 候選分佈
     *   W_sum += weight_i
     *   M += 1
     *   以機率 weight_i / W_sum 選擇 x_i 替換 y
     *
     * 最終 RIS 估計權重：
     *   W = (1/p_hat(y)) × (W_sum / M)
     * </pre>
     *
     * <p>參考文獻：Bitterli et al. 2020, "Spatiotemporal reservoir resampling
     * for real-time ray tracing with dynamic direct lighting"
     *
     * @param reservoir 現有 reservoir 狀態（in-out 參數）
     * @param candidateLightIdx 候選光源索引
     * @param candidateWeight   候選權重 p_hat(x_i) / p(x_i)
     * @param rand              [0, 1) 均勻隨機數（用於決定是否替換）
     * @return 是否接受候選（true = y 被替換為 candidateLightIdx）
     */
    public static boolean reservoirUpdate(Reservoir reservoir,
                                          int candidateLightIdx,
                                          float candidateWeight,
                                          float rand) {
        reservoir.wSum += candidateWeight;
        reservoir.M    += 1;

        boolean accepted = rand < (candidateWeight / reservoir.wSum);
        if (accepted) {
            reservoir.y = candidateLightIdx;
        }
        return accepted;
    }

    /**
     * 計算 Reservoir 的最終 RIS 估計權重 W。
     *
     * <p>W = (1 / p_hat(y)) × (wSum / M)，其中 p_hat 為目標分佈在 y 處的值。
     * W 是無偏估計器，供 shader 乘以 BRDF × Li 得到最終光照貢獻。
     *
     * @param reservoir    reservoir 狀態
     * @param pHatAtY      目標分佈 p_hat 在選中樣本 y 處的值（通常為 luminance × geometry）
     * @return 無偏估計權重 W（pHatAtY = 0 時回傳 0）
     */
    public static float computeRISWeight(Reservoir reservoir, float pHatAtY) {
        if (pHatAtY <= 0.0f || reservoir.M == 0) return 0.0f;
        return (1.0f / pHatAtY) * (reservoir.wSum / reservoir.M);
    }

    /**
     * 合併兩個 Reservoir（時域或空間重用步驟）。
     *
     * <p>合併規則（無偏版本，Talbot et al. 2005）：
     * <pre>
     * combined.wSum += src.W × p_hat(src.y) × src.M
     * combined.M    += src.M
     * 以機率 src.W × p_hat(src.y) × src.M / combined.wSum 接受 src.y
     * </pre>
     *
     * @param dst           目標 reservoir（接收合併結果）
     * @param src           來源 reservoir（時域前幀或空間鄰居）
     * @param pHatSrcY      p_hat 在 src.y 處的值（從 dst 的 shading point 計算）
     * @param rand          [0, 1) 均勻隨機數
     * @param temporalMaxM  M-cap 上限（防止時域過度偏差）
     */
    public static void reservoirMerge(Reservoir dst, Reservoir src,
                                      float pHatSrcY, float rand,
                                      int temporalMaxM) {
        // M-cap：限制 src.M 防止時域累積過多
        int srcMCapped = Math.min(src.M, temporalMaxM);

        float srcContribution = pHatSrcY * src.W * srcMCapped;
        dst.wSum += srcContribution;
        dst.M    += srcMCapped;

        if (rand < (srcContribution / dst.wSum)) {
            dst.y = src.y;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Reservoir 資料類（CPU 側測試 / 序列化工具）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * CPU 側 Reservoir 表示（對應 GPU {@code uvec4} 格式）。
     *
     * <p>此類別用於：
     * <ul>
     *   <li>單元測試（{@code BRReSTIRDITest}）中驗證 RIS 數學</li>
     *   <li>初始化時 CPU 端 clear（{@link #clearReservoirsCPU(int, int)}）</li>
     * </ul>
     */
    public static final class Reservoir {
        /** 選中的光源索引（GPU uvec4[0]） */
        public int   y     = -1;
        /** RIS 累積權重 wSum（非序列化；W = wSum / (M × p_hat(y)) 序列化） */
        public float wSum  = 0.0f;
        /** 累積樣本數量 M（GPU uvec4[2]） */
        public int   M     = 0;
        /** 最終 RIS 估計權重 W（GPU uvec4[1]，floatBitsToUint）*/
        public float W     = 0.0f;
        /** 有效旗標（GPU uvec4[3] bit 0）*/
        public boolean valid = false;

        public Reservoir() {}

        /** 複製建構子（用於 merge 前的備份） */
        public Reservoir(Reservoir other) {
            this.y     = other.y;
            this.wSum  = other.wSum;
            this.M     = other.M;
            this.W     = other.W;
            this.valid = other.valid;
        }

        /** 重置為空 reservoir */
        public void reset() {
            y = -1; wSum = 0.0f; M = 0; W = 0.0f; valid = false;
        }

        @Override
        public String toString() {
            return String.format("Reservoir{y=%d, W=%.4f, M=%d, wSum=%.4f, valid=%b}",
                y, W, M, wSum, valid);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  內部工具
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 以空 Reservoir 初始化 CPU 端 dummy buffer（初始化確認用）。
     * 生產環境中由 vkCmdFillBuffer 在 GPU 端零填充。
     *
     * @param width  緩衝區寬度
     * @param height 緩衝區高度
     */
    private void clearReservoirsCPU(int width, int height) {
        // 空 reservoir 的 GPU 格式：uvec4(0, 0, 0, 0)
        // 即 lightIdx=0（invalid/不存在光源），W=0.0f，M=0，flags=0（invalid bit 0 = 0）
        // 生產：vkCmdFillBuffer(cmdBuf, currentReservoirBuffer, 0, VK_WHOLE_SIZE, 0);
        //       vkCmdFillBuffer(cmdBuf, previousReservoirBuffer, 0, VK_WHOLE_SIZE, 0);
        LOGGER.debug("[ReSTIRDI] Cleared {} reservoir pixels ({}×{})", width * height, width, height);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  查詢 / 統計
    // ════════════════════════════════════════════════════════════════════════

    /** @return Current Reservoir SSBO handle（供 descriptor set 綁定） */
    public long getCurrentReservoirBuffer() { return currentReservoirBuffer; }

    /** @return Previous Reservoir SSBO handle（時域重用讀取）*/
    public long getPreviousReservoirBuffer() { return previousReservoirBuffer; }

    /** @return Light BVH SSBO handle */
    public long getLightBvhSsbo() { return lightBvhSsbo; }

    /** @return 目前的渲染寬度 */
    public int getRenderWidth() { return renderWidth; }

    /** @return 目前的渲染高度 */
    public int getRenderHeight() { return renderHeight; }

    /** @return 兩個 Reservoir buffer 的總 VRAM 佔用（bytes） */
    public long getReservoirVRAMBytes() {
        return (long) pixelCount * RESERVOIR_SIZE * 2L; // × 2 for double-buffering
    }

    /** @return 累計渲染幀數 */
    public long getFrameCount() { return frameCount; }

    /** @return 場景中的光源總數（最近一次 onLightsUpdated 更新後） */
    public int getTotalLightCount() { return totalLightCount; }
}
