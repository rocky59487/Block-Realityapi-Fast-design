package com.blockreality.api.client.render.rt;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.vulkan.VK10.*;

/**
 * BRReSTIRGI — ReSTIR GI（Global Illumination）Reservoir Buffer 管理器。
 *
 * <h3>ReSTIR GI 概述</h3>
 * <p>ReSTIR GI 是 ReSTIR DI 的間接照明延伸（Ouyang et al. 2021），
 * 透過對次級散射路徑（secondary bounce）進行時域/空間重用，
 * 在每像素 4-8 條次射線的預算下達到接近路徑追蹤的 GI 品質。
 *
 * <h3>GI Reservoir 格式（GPU SSBO，32 bytes/pixel）</h3>
 * <pre>
 * uvec4[0]：
 *   [x] = floatBitsToUint(rayDir.x)   — 次射線方向 x
 *   [y] = floatBitsToUint(rayDir.y)   — 次射線方向 y
 *   [z] = floatBitsToUint(rayDir.z)   — 次射線方向 z
 *   [w] = floatBitsToUint(hitDist)    — 次射線擊中距離
 * uvec4[1]：
 *   [x] = floatBitsToUint(irrad.r)    — 擊中點間接輻射量 R（HDR）
 *   [y] = floatBitsToUint(irrad.g)    — 擊中點間接輻射量 G（HDR）
 *   [z] = floatBitsToUint(irrad.b)    — 擊中點間接輻射量 B（HDR）
 *   [w] = M                           — reservoir 累積樣本數（uint，上限 GI_MAX_M）
 * </pre>
 * <p>注意：W（RIS weight）由主 shader 從 irradiance 與 wSum 即時計算，
 * 不直接儲存於 reservoir，以節省 VRAM（無需第三個 uvec4）。
 *
 * <h3>雙緩衝設計</h3>
 * <ul>
 *   <li>current buffer：當前幀 {@code restir_gi.comp.glsl} 輸出</li>
 *   <li>previous buffer：時域重用讀取（前幀的 current）</li>
 *   <li>每幀 {@link #swap()} 交換角色</li>
 * </ul>
 *
 * <h3>VRAM 估算（full-res）</h3>
 * <pre>
 * 1080p：1920 × 1080 × 32 bytes × 2 buffers = ~126 MB
 * 4K   ：3840 × 2160 × 32 bytes × 2 buffers = ~503 MB
 * 建議 VRAM < 8 GB 時降至半解析度（VRAM ≈ ~32 MB）
 * </pre>
 *
 * @see BRReSTIRDI
 * @see BRRTEmissiveManager
 */
@OnlyIn(Dist.CLIENT)
public final class BRReSTIRGI {

    private static final Logger LOGGER = LoggerFactory.getLogger("BR-ReSTIRGI");

    // ════════════════════════════════════════════════════════════════════════
    //  常數
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 每個 GI Reservoir 的 GPU 大小（bytes）。
     * 2 × uvec4 = 2 × 16 bytes = 32 bytes。
     */
    public static final int GI_RESERVOIR_SIZE = 32;

    /**
     * 時域 GI Reservoir M-cap（最大歷史路徑樣本數）。
     * 較高值提供更低噪點，但可能引入 stale path 偏差。
     * GI 建議值比 DI 低（DI=20，GI=10），因間接路徑的時域相關性較弱。
     */
    public static final int GI_DEFAULT_MAX_M = 10;

    /**
     * 每像素次射線數量（Specialization Constant 的 Java 端對應值）。
     * 4 = RTX 50xx 預設；RTX 40xx 可降至 2。
     */
    public static final int GI_RAYS_PER_PIXEL = 4;

    /**
     * 空間重用搜尋半徑（pixels）。
     * GI 使用比 DI 更大的半徑（DI=16，GI=24），
     * 因間接照明的空間相關性在光滑場景中更強。
     */
    public static final int GI_SPATIAL_RADIUS = 24;

    // ════════════════════════════════════════════════════════════════════════
    //  Singleton
    // ════════════════════════════════════════════════════════════════════════

    private static final BRReSTIRGI INSTANCE = new BRReSTIRGI();

    public static BRReSTIRGI getInstance() { return INSTANCE; }

    private BRReSTIRGI() {}

    // ════════════════════════════════════════════════════════════════════════
    //  狀態
    // ════════════════════════════════════════════════════════════════════════

    private boolean initialized      = false;
    private int     renderWidth      = 0;
    private int     renderHeight     = 0;
    private int     pixelCount       = 0;

    // 雙緩衝 GPU handle（Stub；生產為 VkBuffer handle）
    private long    currentGIBuffer  = 0L;
    private long    previousGIBuffer = 0L;
    private long    currentGIMem     = 0L;
    private long    previousGIMem    = 0L;

    // DAG SSBO handle（由 BRSparseVoxelDAG 填充）
    private long    dagSsbo          = 0L;

    // 幀統計
    private long    frameCount       = 0L;

    // ════════════════════════════════════════════════════════════════════════
    //  生命週期
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 初始化 ReSTIR GI 系統。
     *
     * @param width  渲染寬度（pixels）
     * @param height 渲染高度（pixels）
     * @return true = 成功；false = 失敗
     */
    public boolean init(int width, int height) {
        if (initialized) {
            LOGGER.warn("[ReSTIRGI] Already initialized; call resize() to change resolution");
            return true;
        }
        if (width <= 0 || height <= 0) {
            LOGGER.error("[ReSTIRGI] Invalid resolution: {}×{}", width, height);
            return false;
        }

        renderWidth  = width;
        renderHeight = height;
        pixelCount   = width * height;

        long bufBytes = (long) pixelCount * GI_RESERVOIR_SIZE;

        try {
            LOGGER.info("[ReSTIRGI] Allocating GI reservoir buffers: {}×{} = {} pixels, {} MB × 2 double-buf",
                width, height, pixelCount, bufBytes / (1024 * 1024));

            long device = BRVulkanDevice.getVkDevice();
            if (device == 0L) {
                LOGGER.warn("[ReSTIRGI] Vulkan device not ready — using stub handles");
                currentGIBuffer  = 10L;
                previousGIBuffer = 11L;
                currentGIMem     = 12L;
                previousGIMem    = 13L;
            } else {
                // VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT = 0x00020000（VK 1.2 core）
                final int DEVICE_ADDRESS_BIT = 0x00020000;
                int usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                          | VK_BUFFER_USAGE_TRANSFER_DST_BIT
                          | DEVICE_ADDRESS_BIT;

                // ── Current GI Reservoir buffer（2×uvec4 = 32 bytes/pixel）────────
                currentGIBuffer = BRVulkanDevice.createBuffer(device, bufBytes, usage);
                if (currentGIBuffer == 0L)
                    throw new RuntimeException("createBuffer failed for currentGIBuffer");
                currentGIMem = BRVulkanDevice.allocateAndBindBuffer(
                    device, currentGIBuffer, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
                if (currentGIMem == 0L)
                    throw new RuntimeException("allocateAndBindBuffer failed for currentGIMem");

                // ── Previous GI Reservoir buffer（時域重用讀取）──────────────────
                previousGIBuffer = BRVulkanDevice.createBuffer(device, bufBytes, usage);
                if (previousGIBuffer == 0L)
                    throw new RuntimeException("createBuffer failed for previousGIBuffer");
                previousGIMem = BRVulkanDevice.allocateAndBindBuffer(
                    device, previousGIBuffer, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
                if (previousGIMem == 0L)
                    throw new RuntimeException("allocateAndBindBuffer failed for previousGIMem");

                // ── GPU 端清零（空 GI reservoir：rayDir=(0,0,1), hit=0, irrad=(0,0,0), M=0）
                BRVulkanDevice.cmdFillBuffer(device, currentGIBuffer,  0L, VK_WHOLE_SIZE, 0);
                BRVulkanDevice.cmdFillBuffer(device, previousGIBuffer, 0L, VK_WHOLE_SIZE, 0);

                LOGGER.info("[ReSTIRGI] GPU buffers allocated: cur={} prev={}, {} MB each",
                    currentGIBuffer, previousGIBuffer, bufBytes / (1024 * 1024));
            }

            initialized = true;
            LOGGER.info("[ReSTIRGI] Initialized: {}×{} = {} pixels, {} MB × 2 double-buf",
                width, height, pixelCount, bufBytes / (1024 * 1024));
            return true;

        } catch (Exception e) {
            LOGGER.error("[ReSTIRGI] Initialization failed", e);
            cleanup();
            return false;
        }
    }

    /**
     * 調整 GI Reservoir buffer 大小（視窗 resize 時呼叫）。
     */
    public void resize(int newWidth, int newHeight) {
        if (newWidth == renderWidth && newHeight == renderHeight) return;
        cleanup();
        init(newWidth, newHeight);
    }

    /**
     * 釋放所有 GPU 資源。
     */
    public void cleanup() {
        if (!initialized) return;
        long device = BRVulkanDevice.getVkDevice();
        // 跳過 stub handles（10L–13L）避免對假 handle 呼叫 Vulkan destroy
        if (device != 0L && currentGIBuffer > 13L) {
            BRVulkanDevice.destroyBuffer(device, currentGIBuffer);
            BRVulkanDevice.freeMemory(device, currentGIMem);
            BRVulkanDevice.destroyBuffer(device, previousGIBuffer);
            BRVulkanDevice.freeMemory(device, previousGIMem);
        }
        currentGIBuffer  = 0L;
        previousGIBuffer = 0L;
        currentGIMem     = 0L;
        previousGIMem    = 0L;
        dagSsbo          = 0L;
        initialized      = false;
        frameCount       = 0L;
        LOGGER.info("[ReSTIRGI] Cleanup complete");
    }

    // ════════════════════════════════════════════════════════════════════════
    //  每幀操作
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 幀開始時交換 current/previous GI Reservoir buffer。
     *
     * <p>呼叫順序（每幀）：
     * <ol>
     *   <li>{@link BRReSTIRDI#swap()} — DI 先 swap</li>
     *   <li>dispatch restir_di.comp.glsl（DI pass）</li>
     *   <li>{@code swap()} — GI swap</li>
     *   <li>dispatch restir_gi.comp.glsl（GI pass，讀取 DI 結果 + GI prev）</li>
     *   <li>{@link #onFrameEnd()}</li>
     * </ol>
     */
    public void swap() {
        if (!initialized) return;
        long tmpBuf = currentGIBuffer;
        long tmpMem = currentGIMem;
        currentGIBuffer  = previousGIBuffer;
        currentGIMem     = previousGIMem;
        previousGIBuffer = tmpBuf;
        previousGIMem    = tmpMem;
    }

    /** 幀結束統計。 */
    public void onFrameEnd() { frameCount++; }

    // ════════════════════════════════════════════════════════════════════════
    //  GI Reservoir 數學（CPU 側測試用）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * GI Reservoir update：將候選次射線路徑合併到 reservoir。
     *
     * <p>GI RIS 的目標函式 p_hat 是候選路徑的輻射量（irradiance luminance）；
     * 與 DI 的 "光源功率 × 幾何項" 類似，但針對的是整條路徑的貢獻。
     *
     * @param r              現有 GI reservoir（in-out）
     * @param candIrradiance 候選路徑輻射量（cd/m²，scalar luminance）
     * @param candPdf        候選路徑的採樣 PDF
     * @param rand           [0, 1) 均勻隨機數
     * @return true = 接受候選（reservoir 的路徑資訊應更新）
     */
    public static boolean giReservoirUpdate(GIReservoir r,
                                             float candIrradiance,
                                             float candPdf,
                                             float rand) {
        if (candPdf <= 0.0f) return false;
        float weight = candIrradiance / candPdf;
        r.wSum += weight;
        r.M    += 1;
        boolean accepted = rand < (weight / r.wSum);
        return accepted;
    }

    /**
     * 計算 GI reservoir 的最終 RIS weight。
     * W_gi = (1 / p_hat(selected)) × (wSum / M)
     *
     * @param r        GI reservoir
     * @param pHatSelected p_hat 在選中路徑的值（selected path 的 luminance）
     */
    public static float computeGIRISWeight(GIReservoir r, float pHatSelected) {
        if (pHatSelected <= 1e-6f || r.M == 0) return 0.0f;
        return (1.0f / pHatSelected) * (r.wSum / r.M);
    }

    /**
     * 合併兩個 GI reservoir（時域或空間重用）。
     *
     * @param dst      目標 reservoir
     * @param src      來源 reservoir（前幀或鄰居）
     * @param pHatSrc  p_hat 在 src 選中路徑的值（從 dst shading point 評估）
     * @param rand     [0, 1) 均勻隨機數
     * @param maxM     M-cap
     * @return true = 選中路徑切換為 src 的路徑
     */
    public static boolean giReservoirMerge(GIReservoir dst, GIReservoir src,
                                            float pHatSrc, float rand, int maxM) {
        int   srcMCapped   = Math.min(src.M, maxM);
        float srcContrib   = pHatSrc * src.W * srcMCapped;
        dst.wSum          += srcContrib;
        dst.M             += srcMCapped;
        if (rand < (srcContrib / dst.wSum)) {
            // 呼叫端負責複製 src 的路徑資訊到 dst
            return true;
        }
        return false;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  GI Reservoir 資料類
    // ════════════════════════════════════════════════════════════════════════

    /**
     * CPU 側 GI Reservoir（對應 GPU 2×uvec4 格式）。
     * 用於單元測試與 CPU 端初始化。
     */
    public static final class GIReservoir {
        /** 次射線方向（world space，normalized） */
        public float rayDirX = 0, rayDirY = 0, rayDirZ = 1;
        /** 擊中距離（meters / block，FP32） */
        public float hitDist = 0;
        /** 擊中點接收的間接輻射量（HDR RGB） */
        public float irradR = 0, irradG = 0, irradB = 0;
        /** 累積樣本數 */
        public int   M      = 0;
        /** 累積權重（供合併計算，不直接儲存 GPU） */
        public float wSum   = 0;
        /** 最終 RIS weight（主 shader 讀取此值縮放 irradiance） */
        public float W      = 0;

        public GIReservoir() {}

        /** 重置為空 */
        public void reset() {
            rayDirX = 0; rayDirY = 0; rayDirZ = 1;
            hitDist = 0;
            irradR  = 0; irradG  = 0; irradB  = 0;
            M = 0; wSum = 0; W = 0;
        }

        /** ITU-R BT.709 luminance */
        public float luminance() {
            return 0.2126f * irradR + 0.7152f * irradG + 0.0722f * irradB;
        }

        @Override
        public String toString() {
            return String.format("GIReservoir{dir=(%.2f,%.2f,%.2f), hit=%.2f, " +
                "irrad=(%.3f,%.3f,%.3f), W=%.4f, M=%d}",
                rayDirX, rayDirY, rayDirZ, hitDist, irradR, irradG, irradB, W, M);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  查詢 / 統計
    // ════════════════════════════════════════════════════════════════════════

    public boolean isInitialized()           { return initialized; }
    public int     getRenderWidth()          { return renderWidth; }
    public int     getRenderHeight()         { return renderHeight; }
    public long    getCurrentGIBuffer()      { return currentGIBuffer; }
    public long    getPreviousGIBuffer()     { return previousGIBuffer; }
    public long    getDagSsbo()              { return dagSsbo; }
    public long    getFrameCount()           { return frameCount; }

    /** Alias for GI_RESERVOIR_SIZE — used by BRReSTIRComputeDispatcher. */
    public static final int RESERVOIR_SIZE = GI_RESERVOIR_SIZE;

    /** Alias for getCurrentGIBuffer() — used by BRReSTIRComputeDispatcher. */
    public long getCurrentReservoirBuffer()  { return currentGIBuffer; }

    /** Alias for getPreviousGIBuffer() — used by BRReSTIRComputeDispatcher. */
    public long getPreviousReservoirBuffer() { return previousGIBuffer; }

    /** 設定 DAG SSBO handle（由 VkAccelStructBuilder 更新 DAG 後呼叫） */
    public void setDagSsbo(long handle)      { this.dagSsbo = handle; }

    /**
     * 兩個 GI Reservoir buffer 的總 VRAM 佔用（bytes）。
     * @return double-buffered VRAM = pixelCount × 32 × 2
     */
    public long getGIReservoirVRAMBytes() {
        return (long) pixelCount * GI_RESERVOIR_SIZE * 2L;
    }
}
