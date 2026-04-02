package com.blockreality.api.client.render.rt;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * ReLAX-style 純 Vulkan 降噪器（P2-A）。
 *
 * <p>取代已棄用的 {@link BRSVGFDenoiser}（OpenGL compute），改為純 Vulkan compute 管線，
 * 與 RT 管線共享命令緩衝區基礎設施，完全消除 GL ↔ Vulkan 跨 API 同步開銷。</p>
 *
 * <h3>演算法（2 階段）：</h3>
 * <ol>
 *   <li><b>時間累積</b>（relax_temporal.comp.glsl）
 *       — 深度一致性驗證 + 亮度 AABB 鉗制（鬼影抑制）+ Welford EMA 方差追蹤</li>
 *   <li><b>A-trous 空間濾波</b>（relax_atrous.comp.glsl）× 4 次迭代（step 1/2/4/8）
 *       — 方差引導 sigma + 深度/法線/亮度邊緣停止</li>
 * </ol>
 *
 * <h3>優先順序（{@link BRRTPipelineOrdering} 調用方決定）：</h3>
 * <pre>
 *   1. BRNRDNative（若 blockreality_nrd.dll 存在）
 *   2. BRReLAXDenoiser（本類，Vulkan compute，跨廠商）← 主路徑
 *   3. BRSVGFDenoiser（@Deprecated 後備，GL compute）
 * </pre>
 *
 * <h3>內部圖像資源（全部 DEVICE_LOCAL）：</h3>
 * <ul>
 *   <li>accumImg[2]    — rgba16f，時間累積 ping-pong</li>
 *   <li>momentsImg[2]  — rg32f，[mean, variance] ping-pong</li>
 *   <li>atrousImg[2]   — rgba16f，a-trous 空間濾波 ping-pong</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public final class BRReLAXDenoiser {

    private static final Logger LOGGER = LoggerFactory.getLogger("BR-ReLAX");

    // ── 預設超參數 ────────────────────────────────────────────────────────────

    /** 時間混合比例：0.1 = 10% 本幀 + 90% 歷史。 */
    public static final float DEFAULT_ALPHA         = 0.1f;
    /** 深度邊緣停止 sigma。 */
    public static final float DEFAULT_SIGMA_DEPTH   = 0.1f;
    /** 法線邊緣停止冪次。 */
    public static final float DEFAULT_SIGMA_NORMAL  = 128.0f;
    /** 亮度邊緣停止 sigma。 */
    public static final float DEFAULT_SIGMA_LUM     = 4.0f;
    /** A-trous 迭代次數（step = 1, 2, 4, 8）。 */
    private static final int  ATROUS_ITERATIONS     = 4;

    // ── VK format 常數 ────────────────────────────────────────────────────────
    /** VK_FORMAT_R16G16B16A16_SFLOAT */
    private static final int FMT_RGBA16F = 97;
    /** VK_FORMAT_R32G32_SFLOAT */
    private static final int FMT_RG32F   = 103;

    // ── 狀態 ─────────────────────────────────────────────────────────────────

    private static boolean initialized = false;
    private static int displayWidth, displayHeight;
    private static int frameIndex = 0;

    // 時間累積 DS 佈局（8 bindings）
    private static long temporalDsLayout    = 0L;
    // A-trous DS 佈局（5 bindings）
    private static long atrousDsLayout      = 0L;
    // 管線佈局
    private static long temporalPipeLayout  = 0L;
    private static long atrousPipeLayout    = 0L;
    // 計算管線
    private static long temporalPipeline    = 0L;
    private static long atrousPipeline      = 0L;
    // Descriptor pool & sets
    private static long descriptorPool      = 0L;
    private static long temporalDs          = 0L;
    private static long atrousDs            = 0L;
    // Nearest sampler（用於 depth/normal）
    private static long sampler             = 0L;

    // 內部圖像資源：每個 long[3] = { VkImage, VkDeviceMemory, VkImageView }
    // accum[0/1] ping-pong；moments[0/1] ping-pong；atrous[0/1] ping-pong
    private static final long[][] accumImg   = new long[2][3];
    private static final long[][] momentsImg = new long[2][3];
    private static final long[][] atrousImg  = new long[2][3];

    private BRReLAXDenoiser() {}

    // ═══════════════════════════════════════════════════════════════════════════
    // 生命週期
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * 初始化 ReLAX 降噪器。建立 Vulkan 計算管線與所有內部圖像資源。
     *
     * @param w 輸出寬度（像素）
     * @param h 輸出高度（像素）
     */
    public static void init(int w, int h) {
        if (initialized) {
            LOGGER.warn("[ReLAX] init() called while already initialized — skipping");
            return;
        }

        long device = BRVulkanDevice.getVkDevice();
        if (device == 0L) {
            LOGGER.warn("[ReLAX] Vulkan device not available — ReLAX denoiser disabled");
            return;
        }

        LOGGER.info("[ReLAX] Initializing ({}×{})...", w, h);
        displayWidth  = w;
        displayHeight = h;
        frameIndex    = 0;

        try {
            // 1. DS 佈局
            if (!createTemporalDsLayout(device)) throw new RuntimeException("temporal DS layout");
            if (!createAtrousDsLayout(device))   throw new RuntimeException("atrous DS layout");

            // 2. 管線佈局
            if (!createTemporalPipelineLayout(device)) throw new RuntimeException("temporal pipeline layout");
            if (!createAtrousPipelineLayout(device))   throw new RuntimeException("atrous pipeline layout");

            // 3. 著色器 & 管線
            if (!createTemporalPipeline(device)) throw new RuntimeException("temporal pipeline");
            if (!createAtrousPipeline(device))   throw new RuntimeException("atrous pipeline");

            // 4. Sampler
            sampler = BRVulkanDevice.createNearestSampler(device);
            if (sampler == 0L) throw new RuntimeException("sampler");

            // 5. 內部圖像
            if (!allocateImages(device, w, h)) throw new RuntimeException("internal images");

            // 6. Descriptor pool & sets
            if (!createDescriptorPool(device)) throw new RuntimeException("descriptor pool");
            if (!allocateDescriptorSets(device)) throw new RuntimeException("descriptor sets");

            initialized = true;
            LOGGER.info("[ReLAX] Initialized successfully");

        } catch (Exception ex) {
            LOGGER.error("[ReLAX] Init failed at step: {}", ex.getMessage());
            cleanup();
        }
    }

    /**
     * 解析度變更時重新建立所有圖像資源。
     *
     * @param w 新輸出寬度
     * @param h 新輸出高度
     */
    public static void onResize(int w, int h) {
        if (!initialized || (w == displayWidth && h == displayHeight)) return;

        LOGGER.info("[ReLAX] Resize: {}×{} → {}×{}", displayWidth, displayHeight, w, h);

        long device = BRVulkanDevice.getVkDevice();
        if (device == 0L) return;

        // 釋放舊圖像
        freeImages(device);
        displayWidth  = w;
        displayHeight = h;
        frameIndex    = 0;

        if (!allocateImages(device, w, h)) {
            LOGGER.error("[ReLAX] Failed to reallocate images after resize — disabling");
            initialized = false;
        }
    }

    /**
     * 釋放所有 Vulkan 資源。
     */
    public static void cleanup() {
        long device = BRVulkanDevice.getVkDevice();
        LOGGER.info("[ReLAX] Cleaning up resources");

        if (device != 0L) {
            freeImages(device);
            BRVulkanDevice.destroyDescriptorPool(device, descriptorPool);
            descriptorPool = 0L; temporalDs = 0L; atrousDs = 0L;
            if (sampler != 0L)           { BRVulkanDevice.destroySampler(device, sampler);                  sampler = 0L; }
            if (atrousPipeline != 0L)    { BRVulkanDevice.destroyPipeline(device, atrousPipeline);          atrousPipeline = 0L; }
            if (temporalPipeline != 0L)  { BRVulkanDevice.destroyPipeline(device, temporalPipeline);        temporalPipeline = 0L; }
            if (atrousPipeLayout != 0L)  { BRVulkanDevice.destroyPipelineLayout(device, atrousPipeLayout);  atrousPipeLayout = 0L; }
            if (temporalPipeLayout != 0L){ BRVulkanDevice.destroyPipelineLayout(device, temporalPipeLayout);temporalPipeLayout = 0L; }
            if (atrousDsLayout != 0L)    { BRVulkanDevice.destroyDescriptorSetLayout(device, atrousDsLayout);   atrousDsLayout = 0L; }
            if (temporalDsLayout != 0L)  { BRVulkanDevice.destroyDescriptorSetLayout(device, temporalDsLayout); temporalDsLayout = 0L; }
        }
        initialized = false;
        frameIndex  = 0;
    }

    public static boolean isInitialized() { return initialized; }

    // ═══════════════════════════════════════════════════════════════════════════
    // 降噪主入口
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * 執行完整 ReLAX 降噪管線（時間 + a-trous 空間）。
     *
     * <p>呼叫端責任：
     * <ul>
     *   <li>{@code currentRTView}：布局必須為 {@code VK_IMAGE_LAYOUT_GENERAL}（RT 管線寫出）</li>
     *   <li>{@code depthView / prevDepthView / normalView}：布局必須為
     *       {@code VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL}</li>
     * </ul>
     *
     * @param currentRTView  本幀 RT 輸出 VkImageView（rgba16f，layout GENERAL）
     * @param depthView      本幀深度 VkImageView
     * @param prevDepthView  上一幀深度 VkImageView
     * @param normalView     本幀法線 VkImageView
     * @return 最終降噪結果 VkImageView（layout GENERAL），或 0L 若未初始化
     */
    public static long denoise(long currentRTView, long depthView,
                                long prevDepthView, long normalView) {
        if (!initialized) return 0L;

        long device = BRVulkanDevice.getVkDevice();
        if (device == 0L) return 0L;

        // Ping-pong 索引
        int cur  = frameIndex & 1;        // 本幀寫入
        int prev = cur ^ 1;               // 上一幀（歷史）

        // ── 更新描述符集（每幀，因 ping-pong 索引翻轉）──────────────────────
        updateTemporalDescriptors(device, currentRTView, depthView, prevDepthView,
                                   normalView, cur, prev);

        // ── 記錄所有 compute 通道到單一命令緩衝區 ────────────────────────────
        long cmd = BRVulkanDevice.beginSingleTimeCommands(device);
        if (cmd == 0L) {
            LOGGER.error("[ReLAX] beginSingleTimeCommands failed");
            return 0L;
        }

        VkCommandBuffer vkCmd = new VkCommandBuffer(cmd, BRVulkanDevice.getVkDeviceObj());

        try (MemoryStack stack = MemoryStack.stackPush()) {

            // ── Pass 1：時間累積 ───────────────────────────────────────────────
            vkCmdBindPipeline(vkCmd, VK_PIPELINE_BIND_POINT_COMPUTE, temporalPipeline);
            LongBuffer tDs = stack.longs(temporalDs);
            vkCmdBindDescriptorSets(vkCmd, VK_PIPELINE_BIND_POINT_COMPUTE,
                    temporalPipeLayout, 0, tDs, null);

            // Push constants（16 bytes）: width, height, alpha, frameIndex
            java.nio.ByteBuffer tPc = stack.malloc(16);
            tPc.putInt(0,  displayWidth);
            tPc.putInt(4,  displayHeight);
            tPc.putFloat(8, DEFAULT_ALPHA);
            tPc.putInt(12, frameIndex);
            vkCmdPushConstants(vkCmd, temporalPipeLayout,
                    VK_SHADER_STAGE_COMPUTE_BIT, 0, tPc);

            int gx = (displayWidth  + 7) / 8;
            int gy = (displayHeight + 7) / 8;
            vkCmdDispatch(vkCmd, gx, gy, 1);

            // 屏障：時間輸出 WRITE → A-trous 輸入 READ
            pipelineBarrierComputeToCompute(stack, vkCmd,
                    accumImg[cur][0], momentsImg[cur][0]);

            // a-trous 迭代：第一次讀取 accumImg[cur]，後續在 atrousImg[0/1] 間 ping-pong
            // step sizes: 1, 2, 4, 8（4 次迭代）
            for (int i = 0; i < ATROUS_ITERATIONS; i++) {
                int stepSize  = 1 << i;  // 1, 2, 4, 8
                int outIdx    = i & 1;   // ping-pong 輸出索引
                int inIdx     = outIdx ^ 1;

                long inputView  = (i == 0) ? accumImg[cur][2] : atrousImg[inIdx][2];
                long outputView = atrousImg[outIdx][2];

                // 更新 a-trous 描述符（input/output view 每迭代翻轉）
                updateAtrousDescriptors(device, inputView, outputView,
                                         depthView, normalView, momentsImg[cur][2]);

                // 屏障：前一次輸出 WRITE → 本次輸入 READ（第一次迭代用 accum 已有屏障）
                if (i > 0) {
                    pipelineBarrierSingleImage(stack, vkCmd, atrousImg[inIdx][0]);
                }

                vkCmdBindPipeline(vkCmd, VK_PIPELINE_BIND_POINT_COMPUTE, atrousPipeline);
                LongBuffer aDs = stack.longs(atrousDs);
                vkCmdBindDescriptorSets(vkCmd, VK_PIPELINE_BIND_POINT_COMPUTE,
                        atrousPipeLayout, 0, aDs, null);

                // Push constants（32 bytes）: width, height, stepSize, sigmaDepth, sigmaNormal, sigmaLum, pad, pad
                java.nio.ByteBuffer aPc = stack.malloc(32);
                aPc.putInt(0,   displayWidth);
                aPc.putInt(4,   displayHeight);
                aPc.putInt(8,   stepSize);
                aPc.putFloat(12, DEFAULT_SIGMA_DEPTH);
                aPc.putFloat(16, DEFAULT_SIGMA_NORMAL);
                aPc.putFloat(20, DEFAULT_SIGMA_LUM);
                aPc.putInt(24,  0);
                aPc.putInt(28,  0);
                vkCmdPushConstants(vkCmd, atrousPipeLayout,
                        VK_SHADER_STAGE_COMPUTE_BIT, 0, aPc);

                vkCmdDispatch(vkCmd, gx, gy, 1);
            }

        } // MemoryStack.close()

        BRVulkanDevice.endSingleTimeCommands(device, cmd);

        // 更新 ping-pong 歷史（時間累積結果成為下一幀的 historyAccum）
        frameIndex++;

        // 返回最終 a-trous 輸出的 image view
        // ATROUS_ITERATIONS = 4（偶數）→ 最後輸出在 atrousImg[0]
        int finalIdx = (ATROUS_ITERATIONS - 1) & 1;
        return atrousImg[finalIdx][2];
    }

    /** 取得最終輸出 VkImageView（上一次 denoise() 的結果）。 */
    public static long getFinalOutputView() {
        if (!initialized) return 0L;
        int finalIdx = (ATROUS_ITERATIONS - 1) & 1;
        return atrousImg[finalIdx][2];
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 描述符更新
    // ═══════════════════════════════════════════════════════════════════════════

    private static void updateTemporalDescriptors(long device,
                                                   long currentRTView,
                                                   long depthView,
                                                   long prevDepthView,
                                                   long normalView,
                                                   int cur, int prev) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(8, stack);

            // b0 — currentRT（STORAGE_IMAGE，GENERAL，readonly）
            VkDescriptorImageInfo.Buffer b0Info = VkDescriptorImageInfo.calloc(1, stack);
            b0Info.get(0).imageView(currentRTView).imageLayout(VK_IMAGE_LAYOUT_GENERAL).sampler(VK_NULL_HANDLE);
            writes.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(temporalDs).dstBinding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1)
                    .pImageInfo(b0Info);

            // b1 — historyAccum（ping-pong prev）
            VkDescriptorImageInfo.Buffer b1Info = VkDescriptorImageInfo.calloc(1, stack);
            b1Info.get(0).imageView(accumImg[prev][2]).imageLayout(VK_IMAGE_LAYOUT_GENERAL).sampler(VK_NULL_HANDLE);
            writes.get(1).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(temporalDs).dstBinding(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1)
                    .pImageInfo(b1Info);

            // b2 — outputAccum（ping-pong cur）
            VkDescriptorImageInfo.Buffer b2Info = VkDescriptorImageInfo.calloc(1, stack);
            b2Info.get(0).imageView(accumImg[cur][2]).imageLayout(VK_IMAGE_LAYOUT_GENERAL).sampler(VK_NULL_HANDLE);
            writes.get(2).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(temporalDs).dstBinding(2)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1)
                    .pImageInfo(b2Info);

            // b3 — prevMoments（ping-pong prev）
            VkDescriptorImageInfo.Buffer b3Info = VkDescriptorImageInfo.calloc(1, stack);
            b3Info.get(0).imageView(momentsImg[prev][2]).imageLayout(VK_IMAGE_LAYOUT_GENERAL).sampler(VK_NULL_HANDLE);
            writes.get(3).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(temporalDs).dstBinding(3)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1)
                    .pImageInfo(b3Info);

            // b4 — outputMoments（ping-pong cur）
            VkDescriptorImageInfo.Buffer b4Info = VkDescriptorImageInfo.calloc(1, stack);
            b4Info.get(0).imageView(momentsImg[cur][2]).imageLayout(VK_IMAGE_LAYOUT_GENERAL).sampler(VK_NULL_HANDLE);
            writes.get(4).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(temporalDs).dstBinding(4)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1)
                    .pImageInfo(b4Info);

            // b5 — depthTex（COMBINED_IMAGE_SAMPLER）
            VkDescriptorImageInfo.Buffer b5Info = VkDescriptorImageInfo.calloc(1, stack);
            b5Info.get(0).imageView(depthView).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL).sampler(sampler);
            writes.get(5).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(temporalDs).dstBinding(5)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
                    .pImageInfo(b5Info);

            // b6 — prevDepthTex（COMBINED_IMAGE_SAMPLER）
            VkDescriptorImageInfo.Buffer b6Info = VkDescriptorImageInfo.calloc(1, stack);
            b6Info.get(0).imageView(prevDepthView).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL).sampler(sampler);
            writes.get(6).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(temporalDs).dstBinding(6)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
                    .pImageInfo(b6Info);

            // b7 — normalTex（COMBINED_IMAGE_SAMPLER）
            VkDescriptorImageInfo.Buffer b7Info = VkDescriptorImageInfo.calloc(1, stack);
            b7Info.get(0).imageView(normalView).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL).sampler(sampler);
            writes.get(7).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(temporalDs).dstBinding(7)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
                    .pImageInfo(b7Info);

            vkUpdateDescriptorSets(BRVulkanDevice.getVkDeviceObj(), writes, null);
        }
    }

    private static void updateAtrousDescriptors(long device,
                                                 long inputView, long outputView,
                                                 long depthView, long normalView,
                                                 long momentsView) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(5, stack);

            // b0 — inputColor（STORAGE_IMAGE）
            VkDescriptorImageInfo.Buffer b0Info = VkDescriptorImageInfo.calloc(1, stack);
            b0Info.get(0).imageView(inputView).imageLayout(VK_IMAGE_LAYOUT_GENERAL).sampler(VK_NULL_HANDLE);
            writes.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(atrousDs).dstBinding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1)
                    .pImageInfo(b0Info);

            // b1 — outputColor（STORAGE_IMAGE）
            VkDescriptorImageInfo.Buffer b1Info = VkDescriptorImageInfo.calloc(1, stack);
            b1Info.get(0).imageView(outputView).imageLayout(VK_IMAGE_LAYOUT_GENERAL).sampler(VK_NULL_HANDLE);
            writes.get(1).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(atrousDs).dstBinding(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1)
                    .pImageInfo(b1Info);

            // b2 — depthTex（COMBINED_IMAGE_SAMPLER）
            VkDescriptorImageInfo.Buffer b2Info = VkDescriptorImageInfo.calloc(1, stack);
            b2Info.get(0).imageView(depthView).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL).sampler(sampler);
            writes.get(2).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(atrousDs).dstBinding(2)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
                    .pImageInfo(b2Info);

            // b3 — normalTex（COMBINED_IMAGE_SAMPLER）
            VkDescriptorImageInfo.Buffer b3Info = VkDescriptorImageInfo.calloc(1, stack);
            b3Info.get(0).imageView(normalView).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL).sampler(sampler);
            writes.get(3).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(atrousDs).dstBinding(3)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
                    .pImageInfo(b3Info);

            // b4 — moments（STORAGE_IMAGE，rg32f）
            VkDescriptorImageInfo.Buffer b4Info = VkDescriptorImageInfo.calloc(1, stack);
            b4Info.get(0).imageView(momentsView).imageLayout(VK_IMAGE_LAYOUT_GENERAL).sampler(VK_NULL_HANDLE);
            writes.get(4).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(atrousDs).dstBinding(4)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1)
                    .pImageInfo(b4Info);

            vkUpdateDescriptorSets(BRVulkanDevice.getVkDeviceObj(), writes, null);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 管線建立
    // ═══════════════════════════════════════════════════════════════════════════

    private static boolean createTemporalDsLayout(long device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 8 bindings：5 STORAGE_IMAGE + 3 COMBINED_IMAGE_SAMPLER
            VkDescriptorSetLayoutBinding.Buffer bindings =
                    VkDescriptorSetLayoutBinding.calloc(8, stack);

            for (int i = 0; i < 5; i++) {
                bindings.get(i).binding(i)
                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(1)
                        .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            }
            for (int i = 5; i < 8; i++) {
                bindings.get(i).binding(i)
                        .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .descriptorCount(1)
                        .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            }

            LongBuffer pLayout = stack.mallocLong(1);
            int r = vkCreateDescriptorSetLayout(BRVulkanDevice.getVkDeviceObj(),
                    VkDescriptorSetLayoutCreateInfo.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                            .pBindings(bindings),
                    null, pLayout);
            if (r != VK_SUCCESS) { LOGGER.error("[ReLAX] temporal DS layout failed: {}", r); return false; }
            temporalDsLayout = pLayout.get(0);
            return true;
        }
    }

    private static boolean createAtrousDsLayout(long device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 5 bindings：3 STORAGE_IMAGE (b0, b1, b4) + 2 COMBINED_IMAGE_SAMPLER (b2, b3)
            VkDescriptorSetLayoutBinding.Buffer bindings =
                    VkDescriptorSetLayoutBinding.calloc(5, stack);

            // b0, b1: STORAGE_IMAGE
            for (int i = 0; i <= 1; i++) {
                bindings.get(i).binding(i)
                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(1)
                        .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            }
            // b2, b3: COMBINED_IMAGE_SAMPLER
            for (int i = 2; i <= 3; i++) {
                bindings.get(i).binding(i)
                        .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .descriptorCount(1)
                        .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            }
            // b4: STORAGE_IMAGE
            bindings.get(4).binding(4)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);

            LongBuffer pLayout = stack.mallocLong(1);
            int r = vkCreateDescriptorSetLayout(BRVulkanDevice.getVkDeviceObj(),
                    VkDescriptorSetLayoutCreateInfo.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                            .pBindings(bindings),
                    null, pLayout);
            if (r != VK_SUCCESS) { LOGGER.error("[ReLAX] atrous DS layout failed: {}", r); return false; }
            atrousDsLayout = pLayout.get(0);
            return true;
        }
    }

    private static boolean createTemporalPipelineLayout(long device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Push constants: width(4), height(4), alpha(4), frameIndex(4) = 16 bytes
            VkPushConstantRange.Buffer pcRange = VkPushConstantRange.calloc(1, stack);
            pcRange.get(0).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(16);

            LongBuffer pDsLayout = stack.longs(temporalDsLayout);
            LongBuffer pPipeLayout = stack.mallocLong(1);
            int r = vkCreatePipelineLayout(BRVulkanDevice.getVkDeviceObj(),
                    VkPipelineLayoutCreateInfo.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                            .pSetLayouts(pDsLayout)
                            .pPushConstantRanges(pcRange),
                    null, pPipeLayout);
            if (r != VK_SUCCESS) { LOGGER.error("[ReLAX] temporal pipeline layout failed: {}", r); return false; }
            temporalPipeLayout = pPipeLayout.get(0);
            return true;
        }
    }

    private static boolean createAtrousPipelineLayout(long device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Push constants: width(4), height(4), stepSize(4), sigmaDepth(4), sigmaNormal(4), sigmaLum(4), pad(4), pad(4) = 32 bytes
            VkPushConstantRange.Buffer pcRange = VkPushConstantRange.calloc(1, stack);
            pcRange.get(0).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(32);

            LongBuffer pDsLayout = stack.longs(atrousDsLayout);
            LongBuffer pPipeLayout = stack.mallocLong(1);
            int r = vkCreatePipelineLayout(BRVulkanDevice.getVkDeviceObj(),
                    VkPipelineLayoutCreateInfo.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                            .pSetLayouts(pDsLayout)
                            .pPushConstantRanges(pcRange),
                    null, pPipeLayout);
            if (r != VK_SUCCESS) { LOGGER.error("[ReLAX] atrous pipeline layout failed: {}", r); return false; }
            atrousPipeLayout = pPipeLayout.get(0);
            return true;
        }
    }

    private static boolean createTemporalPipeline(long device) {
        String glsl = loadShaderResource("relax_temporal.comp.glsl");
        if (glsl == null) return false;
        byte[] spirv = BRVulkanDevice.compileGLSLtoSPIRV(glsl, "relax_temporal.comp.glsl");
        if (spirv == null) return false;
        long shaderModule = BRVulkanDevice.createShaderModule(device, spirv);
        if (shaderModule == 0L) return false;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pPipeline = stack.mallocLong(1);
            int r = vkCreateComputePipelines(BRVulkanDevice.getVkDeviceObj(),
                    VK_NULL_HANDLE,
                    VkComputePipelineCreateInfo.calloc(1, stack)
                            .sType(VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO)
                            .stage(VkPipelineShaderStageCreateInfo.calloc(stack)
                                    .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                                    .stage(VK_SHADER_STAGE_COMPUTE_BIT)
                                    .module(shaderModule)
                                    .pName(stack.UTF8("main")))
                            .layout(temporalPipeLayout),
                    null, pPipeline);
            BRVulkanDevice.destroyShaderModule(device, shaderModule);
            if (r != VK_SUCCESS) { LOGGER.error("[ReLAX] temporal pipeline creation failed: {}", r); return false; }
            temporalPipeline = pPipeline.get(0);
            return true;
        }
    }

    private static boolean createAtrousPipeline(long device) {
        String glsl = loadShaderResource("relax_atrous.comp.glsl");
        if (glsl == null) return false;
        byte[] spirv = BRVulkanDevice.compileGLSLtoSPIRV(glsl, "relax_atrous.comp.glsl");
        if (spirv == null) return false;
        long shaderModule = BRVulkanDevice.createShaderModule(device, spirv);
        if (shaderModule == 0L) return false;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pPipeline = stack.mallocLong(1);
            int r = vkCreateComputePipelines(BRVulkanDevice.getVkDeviceObj(),
                    VK_NULL_HANDLE,
                    VkComputePipelineCreateInfo.calloc(1, stack)
                            .sType(VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO)
                            .stage(VkPipelineShaderStageCreateInfo.calloc(stack)
                                    .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                                    .stage(VK_SHADER_STAGE_COMPUTE_BIT)
                                    .module(shaderModule)
                                    .pName(stack.UTF8("main")))
                            .layout(atrousPipeLayout),
                    null, pPipeline);
            BRVulkanDevice.destroyShaderModule(device, shaderModule);
            if (r != VK_SUCCESS) { LOGGER.error("[ReLAX] atrous pipeline creation failed: {}", r); return false; }
            atrousPipeline = pPipeline.get(0);
            return true;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 圖像資源管理
    // ═══════════════════════════════════════════════════════════════════════════

    private static boolean allocateImages(long device, int w, int h) {
        int colorUsage  = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
        int colorAspect = VK_IMAGE_ASPECT_COLOR_BIT;

        for (int i = 0; i < 2; i++) {
            // rgba16f 累積
            long[] img = BRVulkanDevice.createImage2D(device, w, h, FMT_RGBA16F, colorUsage, colorAspect);
            if (img == null) { LOGGER.error("[ReLAX] Failed to create accumImg[{}]", i); return false; }
            accumImg[i] = img;
            BRVulkanDevice.transitionImageLayout(device, img[0],
                    VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL, colorAspect);

            // rg32f 方差
            long[] mom = BRVulkanDevice.createImage2D(device, w, h, FMT_RG32F, colorUsage, colorAspect);
            if (mom == null) { LOGGER.error("[ReLAX] Failed to create momentsImg[{}]", i); return false; }
            momentsImg[i] = mom;
            BRVulkanDevice.transitionImageLayout(device, mom[0],
                    VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL, colorAspect);

            // rgba16f a-trous ping-pong
            long[] atr = BRVulkanDevice.createImage2D(device, w, h, FMT_RGBA16F, colorUsage, colorAspect);
            if (atr == null) { LOGGER.error("[ReLAX] Failed to create atrousImg[{}]", i); return false; }
            atrousImg[i] = atr;
            BRVulkanDevice.transitionImageLayout(device, atr[0],
                    VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL, colorAspect);
        }

        // moments 圖像在首幀 temporal pass 中以 blendAlpha=1.0 完整覆寫（不需預先清零）
        return true;
    }

    private static void freeImages(long device) {
        for (int i = 0; i < 2; i++) {
            if (accumImg[i][0] != 0L) {
                BRVulkanDevice.destroyImage2D(device, accumImg[i][0], accumImg[i][1], accumImg[i][2]);
                accumImg[i][0] = 0L; accumImg[i][1] = 0L; accumImg[i][2] = 0L;
            }
            if (momentsImg[i][0] != 0L) {
                BRVulkanDevice.destroyImage2D(device, momentsImg[i][0], momentsImg[i][1], momentsImg[i][2]);
                momentsImg[i][0] = 0L; momentsImg[i][1] = 0L; momentsImg[i][2] = 0L;
            }
            if (atrousImg[i][0] != 0L) {
                BRVulkanDevice.destroyImage2D(device, atrousImg[i][0], atrousImg[i][1], atrousImg[i][2]);
                atrousImg[i][0] = 0L; atrousImg[i][1] = 0L; atrousImg[i][2] = 0L;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 描述符池 & 集
    // ═══════════════════════════════════════════════════════════════════════════

    private static boolean createDescriptorPool(long device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // temporal set: 5 SI + 3 CIS；atrous set: 3 SI + 2 CIS → 合計 8 SI, 5 CIS，2 sets
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(2, stack);
            poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(8);
            poolSizes.get(1).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(5);

            LongBuffer pPool = stack.mallocLong(1);
            int r = vkCreateDescriptorPool(BRVulkanDevice.getVkDeviceObj(),
                    VkDescriptorPoolCreateInfo.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                            .maxSets(2)
                            .pPoolSizes(poolSizes),
                    null, pPool);
            if (r != VK_SUCCESS) { LOGGER.error("[ReLAX] descriptor pool failed: {}", r); return false; }
            descriptorPool = pPool.get(0);
            return true;
        }
    }

    private static boolean allocateDescriptorSets(long device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pLayouts = stack.longs(temporalDsLayout, atrousDsLayout);
            LongBuffer pSets    = stack.mallocLong(2);

            int r = vkAllocateDescriptorSets(BRVulkanDevice.getVkDeviceObj(),
                    VkDescriptorSetAllocateInfo.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                            .descriptorPool(descriptorPool)
                            .pSetLayouts(pLayouts),
                    pSets);
            if (r != VK_SUCCESS) { LOGGER.error("[ReLAX] descriptor set allocation failed: {}", r); return false; }
            temporalDs = pSets.get(0);
            atrousDs   = pSets.get(1);
            return true;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 管線屏障輔助
    // ═══════════════════════════════════════════════════════════════════════════

    /** COMPUTE WRITE → COMPUTE READ 屏障，覆蓋時間通道的兩個輸出圖像。 */
    private static void pipelineBarrierComputeToCompute(MemoryStack stack,
                                                         VkCommandBuffer cmd,
                                                         long accumImage,
                                                         long momentsImage) {
        VkImageMemoryBarrier.Buffer barriers = VkImageMemoryBarrier.calloc(2, stack);
        VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack)
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1)
                .baseArrayLayer(0).layerCount(1);

        for (int i = 0; i < 2; i++) {
            long img = (i == 0) ? accumImage : momentsImage;
            barriers.get(i)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT)
                    .oldLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .newLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(img)
                    .subresourceRange(range);
        }
        vkCmdPipelineBarrier(cmd,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                0, null, null, barriers);
    }

    /** 單圖像 COMPUTE WRITE → COMPUTE READ 屏障（a-trous 迭代間）。 */
    private static void pipelineBarrierSingleImage(MemoryStack stack,
                                                    VkCommandBuffer cmd,
                                                    long image) {
        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
        barrier.get(0)
                .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                .dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
                .oldLayout(VK_IMAGE_LAYOUT_GENERAL)
                .newLayout(VK_IMAGE_LAYOUT_GENERAL)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image)
                .subresourceRange(VkImageSubresourceRange.calloc(stack)
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(1)
                        .baseArrayLayer(0).layerCount(1));
        vkCmdPipelineBarrier(cmd,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                0, null, null, barrier);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 著色器資源載入
    // ═══════════════════════════════════════════════════════════════════════════

    private static String loadShaderResource(String filename) {
        String path = "/assets/blockreality/shaders/compute/" + filename;
        try (InputStream is = BRReLAXDenoiser.class.getResourceAsStream(path)) {
            if (is == null) {
                LOGGER.error("[ReLAX] Shader resource not found: {}", path);
                return null;
            }
            return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.error("[ReLAX] Failed to load shader resource: {}", path, e);
            return null;
        }
    }
}
