package com.blockreality.api.client.render.rt;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.vulkan.VK10.*;

/**
 * BRFSRManager — AMD FidelityFX Super Resolution（FSR）跨廠商升頻管理器（P1-B）。
 *
 * <h3>適用場景</h3>
 * <ul>
 *   <li>AMD GPU（RX 6000 / 7000 / 9000 系列）— 無 DLSS 硬體</li>
 *   <li>NVIDIA RTX 20/30xx（Legacy 路徑）— DLSS 可用但此為通用 fallback</li>
 *   <li>Intel Arc GPU — 無 DLSS / XeSS 時的替代方案</li>
 * </ul>
 *
 * <h3>實作說明</h3>
 * <p>本實作基於 FSR 1.0 EASU（Edge-Adaptive Spatial Upsampling）+ RCAS
 *（Robust Contrast Adaptive Sharpening）的核心概念，以標準 GLSL 計算著色器
 *（{@code fsr_upscale.comp.glsl}）實作，不依賴 AMD FidelityFX SDK 二進位。
 *
 * <h3>品質等級（解析度縮放比例）</h3>
 * <pre>
 * QUALITY（品質）       : 67% 輸入解析度 → ~1.5× 升頻（最佳畫質）
 * BALANCED（平衡）      : 59% 輸入解析度 → ~1.7× 升頻
 * PERFORMANCE（效能）   : 50% 輸入解析度 → 2× 升頻
 * ULTRA_PERFORMANCE（極速）: 33% 輸入解析度 → 3× 升頻
 * </pre>
 *
 * <h3>整合架構</h3>
 * <pre>
 * [RT Output @ 720p / 1080p] → FSR EASU（升頻）→ [Display @ 1080p / 4K]
 *                           ↘ FSR RCAS（銳利化）↗
 * </pre>
 *
 * <p>在 {@link com.blockreality.api.client.render.pipeline.BRRTPipelineOrdering}
 * 的 Legacy 路徑中，此 manager 替代 DLSS 執行升頻。
 *
 * @see BRDLSS4Manager
 * @see BRRTSettings
 */
@OnlyIn(Dist.CLIENT)
public final class BRFSRManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("BR-FSR");

    // ════════════════════════════════════════════════════════════════════════
    //  品質模式常數
    // ════════════════════════════════════════════════════════════════════════

    /** FSR Quality：67% 解析度縮放（最佳畫質，推薦 4K 輸出）。 */
    public static final int MODE_QUALITY          = 0;
    /** FSR Balanced：59% 解析度縮放（畫質與效能平衡）。 */
    public static final int MODE_BALANCED         = 1;
    /** FSR Performance：50% 解析度縮放（推薦 1080p RT + 4K 輸出）。 */
    public static final int MODE_PERFORMANCE      = 2;
    /** FSR Ultra Performance：33% 解析度縮放（最大幀率，較明顯品質損失）。 */
    public static final int MODE_ULTRA_PERFORMANCE = 3;

    /** RCAS 銳利化強度（0=關閉 ... 1=最強，建議 0.2-0.4）。 */
    public static final float DEFAULT_SHARPNESS = 0.3f;

    /** 解析度縮放係數（對應品質模式，index = mode）。 */
    private static final float[] SCALE_FACTORS = { 0.67f, 0.59f, 0.50f, 0.33f };

    // ════════════════════════════════════════════════════════════════════════
    //  Singleton
    // ════════════════════════════════════════════════════════════════════════

    private static final BRFSRManager INSTANCE = new BRFSRManager();

    public static BRFSRManager getInstance() { return INSTANCE; }

    private BRFSRManager() {}

    // ════════════════════════════════════════════════════════════════════════
    //  狀態
    // ════════════════════════════════════════════════════════════════════════

    private boolean initialized     = false;
    private int     qualityMode     = MODE_PERFORMANCE;
    private float   sharpness       = DEFAULT_SHARPNESS;
    private int     displayWidth    = 1920;
    private int     displayHeight   = 1080;
    private int     renderWidth     = 960;   // 50% by default
    private int     renderHeight    = 540;

    // Vulkan handles
    private long    dsLayout        = 0L;
    private long    pipelineLayout  = 0L;
    private long    pipeline        = 0L;
    private long    descriptorPool  = 0L;
    private long    descriptorSet   = 0L;
    private long    sampler         = 0L;

    /** 幀統計 */
    private long    framesUpscaled  = 0L;
    private boolean fsrActive       = false;

    // ════════════════════════════════════════════════════════════════════════
    //  生命週期
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 初始化 FSR 系統。
     *
     * @param displayW  目標顯示解析度 W（像素）
     * @param displayH  目標顯示解析度 H（像素）
     * @param mode      品質模式（{@link #MODE_QUALITY} 等）
     * @return true = 成功；false = Vulkan 不可用或 shader 編譯失敗
     */
    public boolean init(int displayW, int displayH, int mode) {
        if (initialized) {
            LOGGER.warn("[FSR] Already initialized; call setMode() to change quality");
            return true;
        }
        if (displayW <= 0 || displayH <= 0) {
            LOGGER.error("[FSR] Invalid display resolution: {}×{}", displayW, displayH);
            return false;
        }

        this.qualityMode   = Math.max(0, Math.min(3, mode));
        this.displayWidth  = displayW;
        this.displayHeight = displayH;
        recomputeRenderResolution();

        long device = BRVulkanDevice.getVkDevice();
        if (device == 0L) {
            LOGGER.warn("[FSR] Vulkan device not available; FSR disabled");
            return false;
        }

        try {
            // Descriptor set layout：b0=COMBINED_IMAGE_SAMPLER（input）, b1=STORAGE_IMAGE（output）
            dsLayout = createDSLayout(device);
            if (dsLayout == 0L) throw new RuntimeException("createDSLayout failed");

            // Pipeline layout：push constants 20 bytes
            pipelineLayout = createPipelineLayout(device, dsLayout);
            if (pipelineLayout == 0L) throw new RuntimeException("createPipelineLayout failed");

            // Compute pipeline from fsr_upscale.comp.glsl
            pipeline = createComputePipeline(device, pipelineLayout);
            if (pipeline == 0L) throw new RuntimeException("createComputePipeline failed");

            // Descriptor pool + set
            descriptorPool = createDescriptorPool(device);
            if (descriptorPool == 0L) throw new RuntimeException("createDescriptorPool failed");
            descriptorSet = allocateDescriptorSet(device, descriptorPool, dsLayout);
            if (descriptorSet == 0L) throw new RuntimeException("allocateDescriptorSet failed");

            // NEAREST sampler for input（FSR performs its own filtering）
            sampler = BRVulkanDevice.createNearestSampler(device);
            if (sampler == 0L) throw new RuntimeException("createNearestSampler failed");

            initialized = true;
            fsrActive   = true;
            LOGGER.info("[FSR] Initialized: display={}×{} render={}×{} mode={} sharpness={}",
                displayWidth, displayHeight, renderWidth, renderHeight,
                modeName(qualityMode), sharpness);
            return true;

        } catch (Exception e) {
            LOGGER.error("[FSR] init() failed", e);
            cleanup();
            return false;
        }
    }

    /**
     * 釋放所有 GPU 資源。
     */
    public void cleanup() {
        long device = BRVulkanDevice.getVkDevice();
        if (device != 0L) {
            if (sampler        != 0L) { BRVulkanDevice.destroySampler(device, sampler);                   sampler        = 0L; }
            if (descriptorPool != 0L) { BRVulkanDevice.destroyDescriptorPool(device, descriptorPool);     descriptorPool = 0L; }
            if (pipeline       != 0L) { BRVulkanDevice.destroyPipeline(device, pipeline);                 pipeline       = 0L; }
            if (pipelineLayout != 0L) { BRVulkanDevice.destroyPipelineLayout(device, pipelineLayout);     pipelineLayout = 0L; }
            if (dsLayout       != 0L) { BRVulkanDevice.destroyDescriptorSetLayout(device, dsLayout);      dsLayout       = 0L; }
        }
        descriptorSet = 0L;
        initialized   = false;
        fsrActive     = false;
        LOGGER.info("[FSR] Cleanup complete ({} frames upscaled)", framesUpscaled);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  升頻 Dispatch
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 執行 FSR 升頻（EASU + RCAS）。
     *
     * <p>從 {@code inputImageView}（低解析度 RT 輸出）升頻至
     * {@code outputImageView}（目標解析度）。
     *
     * <p>呼叫端負責：
     * <ol>
     *   <li>確保 inputImageView 已轉換至 {@code VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL}</li>
     *   <li>確保 outputImageView 已轉換至 {@code VK_IMAGE_LAYOUT_GENERAL}</li>
     * </ol>
     *
     * @param inputImageView  低解析度 RT 輸出的 VkImageView（renderWidth×renderHeight）
     * @param outputImageView 目標解析度的 VkImageView（displayWidth×displayHeight）
     * @return true = dispatch 成功
     */
    public boolean dispatch(long inputImageView, long outputImageView) {
        if (!initialized || !fsrActive) {
            LOGGER.debug("[FSR] dispatch skipped — not initialized");
            return false;
        }
        if (inputImageView == 0L || outputImageView == 0L) {
            LOGGER.warn("[FSR] dispatch: null image view (input={} output={})", inputImageView, outputImageView);
            return false;
        }

        long device = BRVulkanDevice.getVkDevice();
        if (device == 0L) return false;

        // 更新 descriptor set bindings
        updateDescriptorSet(device, descriptorSet, inputImageView, outputImageView, sampler);

        long cmd = BRVulkanDevice.beginSingleTimeCommands(device);
        if (cmd == 0L) { LOGGER.error("[FSR] beginSingleTimeCommands failed"); return false; }

        VkDevice vkDev = BRVulkanDevice.getVkDeviceObj();
        if (vkDev == null) { BRVulkanDevice.endSingleTimeCommands(device, cmd); return false; }

        VkCommandBuffer cb = new VkCommandBuffer(cmd, vkDev);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE,
                pipelineLayout, 0, stack.longs(descriptorSet), null);

            // Push constants（20 bytes）
            java.nio.ByteBuffer pc = stack.malloc(20);
            pc.putInt(0,  renderWidth);
            pc.putInt(4,  renderHeight);
            pc.putInt(8,  displayWidth);
            pc.putInt(12, displayHeight);
            pc.putFloat(16, sharpness);
            vkCmdPushConstants(cb, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);

            int groupsX = (displayWidth  + 7) / 8;
            int groupsY = (displayHeight + 7) / 8;
            vkCmdDispatch(cb, groupsX, groupsY, 1);
        }

        BRVulkanDevice.endSingleTimeCommands(device, cmd);

        framesUpscaled++;
        LOGGER.debug("[FSR] Upscaled frame #{}: {}×{} → {}×{} (mode={} sharp={:.2f})",
            framesUpscaled, renderWidth, renderHeight, displayWidth, displayHeight,
            modeName(qualityMode), sharpness);
        return true;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  設定 API
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 設定 FSR 品質模式並重新計算渲染解析度。
     *
     * @param mode {@link #MODE_QUALITY} … {@link #MODE_ULTRA_PERFORMANCE}
     */
    public void setMode(int mode) {
        this.qualityMode = Math.max(0, Math.min(3, mode));
        recomputeRenderResolution();
        LOGGER.info("[FSR] Mode changed: {} → render={}×{}",
            modeName(qualityMode), renderWidth, renderHeight);
    }

    /**
     * 更新目標顯示解析度（視窗 resize 時呼叫）。
     *
     * @param newDisplayW 新顯示寬度
     * @param newDisplayH 新顯示高度
     */
    public void onDisplayResize(int newDisplayW, int newDisplayH) {
        if (newDisplayW == displayWidth && newDisplayH == displayHeight) return;
        this.displayWidth  = newDisplayW;
        this.displayHeight = newDisplayH;
        recomputeRenderResolution();
        LOGGER.info("[FSR] Display resized: {}×{} → render={}×{}",
            displayWidth, displayHeight, renderWidth, renderHeight);
    }

    /** 設定 RCAS 銳利化強度（0=關閉，1=最強；建議 0.2-0.4）。 */
    public void setSharpness(float s) { this.sharpness = Math.max(0.0f, Math.min(1.0f, s)); }

    /** 啟用或停用 FSR（停用時 dispatch() 靜默返回 false）。 */
    public void setActive(boolean active) { this.fsrActive = active && initialized; }

    // ════════════════════════════════════════════════════════════════════════
    //  查詢 / 統計
    // ════════════════════════════════════════════════════════════════════════

    public boolean isInitialized()    { return initialized; }
    public boolean isFsrActive()      { return fsrActive; }
    public int     getRenderWidth()   { return renderWidth; }
    public int     getRenderHeight()  { return renderHeight; }
    public int     getDisplayWidth()  { return displayWidth; }
    public int     getDisplayHeight() { return displayHeight; }
    public int     getQualityMode()   { return qualityMode; }
    public float   getSharpness()     { return sharpness; }
    public long    getFramesUpscaled(){ return framesUpscaled; }

    /**
     * 升頻比率（顯示解析度 / 渲染解析度）。
     * 例如 Performance 模式：2.0×（1080p → 2160p = 4K）。
     */
    public float getUpscaleRatio() {
        return (float) displayWidth / Math.max(renderWidth, 1);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  私有工具
    // ════════════════════════════════════════════════════════════════════════

    private void recomputeRenderResolution() {
        float scale = SCALE_FACTORS[qualityMode];
        renderWidth  = Math.max(1, Math.round(displayWidth  * scale));
        renderHeight = Math.max(1, Math.round(displayHeight * scale));
    }

    private static String modeName(int mode) {
        return switch (mode) {
            case MODE_QUALITY          -> "Quality(0.67×)";
            case MODE_BALANCED         -> "Balanced(0.59×)";
            case MODE_PERFORMANCE      -> "Performance(0.50×)";
            case MODE_ULTRA_PERFORMANCE-> "UltraPerf(0.33×)";
            default                    -> "Unknown";
        };
    }

    // ─── Vulkan 資源建立 ────────────────────────────────────────────────────

    private static long createDSLayout(long device) {
        VkDevice vkDev = BRVulkanDevice.getVkDeviceObj();
        if (vkDev == null) return 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(2, stack);
            // b0: input sampler（COMBINED_IMAGE_SAMPLER）
            b.get(0).binding(0)
                .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            // b1: output image（STORAGE_IMAGE）
            b.get(1).binding(1)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);

            LongBuffer pLayout = stack.mallocLong(1);
            int r = vkCreateDescriptorSetLayout(vkDev,
                VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .pBindings(b),
                null, pLayout);
            if (r != VK_SUCCESS) { LOGGER.error("[FSR] vkCreateDescriptorSetLayout failed: {}", r); return 0L; }
            return pLayout.get(0);
        }
    }

    private static long createPipelineLayout(long device, long dsLayout) {
        VkDevice vkDev = BRVulkanDevice.getVkDeviceObj();
        if (vkDev == null) return 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPushConstantRange.Buffer pcRange = VkPushConstantRange.calloc(1, stack);
            pcRange.get(0).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(20);

            LongBuffer pLayout = stack.mallocLong(1);
            int r = vkCreatePipelineLayout(vkDev,
                VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(stack.longs(dsLayout))
                    .pPushConstantRanges(pcRange),
                null, pLayout);
            if (r != VK_SUCCESS) { LOGGER.error("[FSR] vkCreatePipelineLayout failed: {}", r); return 0L; }
            return pLayout.get(0);
        }
    }

    private static long createComputePipeline(long device, long layout) {
        String glsl = loadShaderSource("compute/fsr_upscale.comp.glsl");
        if (glsl == null) {
            LOGGER.warn("[FSR] fsr_upscale.comp.glsl not found; FSR disabled");
            return 0L;
        }
        byte[] spv = BRVulkanDevice.compileGLSLtoSPIRV(glsl, "fsr_upscale.comp");
        if (spv.length == 0) {
            LOGGER.error("[FSR] Shader compile failed");
            return 0L;
        }
        long shaderModule = BRVulkanDevice.createShaderModule(device, spv);
        if (shaderModule == 0L) return 0L;

        VkDevice vkDev = BRVulkanDevice.getVkDeviceObj();
        if (vkDev == null) { BRVulkanDevice.destroyShaderModule(device, shaderModule); return 0L; }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPipelineShaderStageCreateInfo.Buffer stage =
                VkPipelineShaderStageCreateInfo.calloc(1, stack);
            stage.get(0)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                .stage(VK_SHADER_STAGE_COMPUTE_BIT)
                .module(shaderModule)
                .pName(stack.UTF8("main"));

            VkComputePipelineCreateInfo.Buffer ci =
                VkComputePipelineCreateInfo.calloc(1, stack);
            ci.get(0)
                .sType(VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO)
                .stage(stage.get(0))
                .layout(layout);

            LongBuffer pPipeline = stack.mallocLong(1);
            int r = vkCreateComputePipelines(vkDev, VK_NULL_HANDLE, ci, null, pPipeline);
            BRVulkanDevice.destroyShaderModule(device, shaderModule);
            if (r != VK_SUCCESS) {
                LOGGER.error("[FSR] vkCreateComputePipelines failed: {}", r);
                return 0L;
            }
            LOGGER.info("[FSR] Compute pipeline created: {}", pPipeline.get(0));
            return pPipeline.get(0);
        }
    }

    private static long createDescriptorPool(long device) {
        VkDevice vkDev = BRVulkanDevice.getVkDeviceObj();
        if (vkDev == null) return 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1);

            LongBuffer pPool = stack.mallocLong(1);
            int r = vkCreateDescriptorPool(vkDev,
                VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                    .maxSets(1)
                    .pPoolSizes(sizes),
                null, pPool);
            if (r != VK_SUCCESS) { LOGGER.error("[FSR] vkCreateDescriptorPool failed: {}", r); return 0L; }
            return pPool.get(0);
        }
    }

    private static long allocateDescriptorSet(long device, long pool, long layout) {
        VkDevice vkDev = BRVulkanDevice.getVkDeviceObj();
        if (vkDev == null) return 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pSet = stack.mallocLong(1);
            int r = vkAllocateDescriptorSets(vkDev,
                VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(pool)
                    .pSetLayouts(stack.longs(layout)),
                pSet);
            if (r != VK_SUCCESS) { LOGGER.error("[FSR] vkAllocateDescriptorSets failed: {}", r); return 0L; }
            return pSet.get(0);
        }
    }

    private static void updateDescriptorSet(long device, long set,
                                             long inputView, long outputView, long sampler) {
        VkDevice vkDev = BRVulkanDevice.getVkDeviceObj();
        if (vkDev == null || set == 0L) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // b0: COMBINED_IMAGE_SAMPLER（input at SHADER_READ_ONLY_OPTIMAL）
            VkDescriptorImageInfo.Buffer inputInfo = VkDescriptorImageInfo.calloc(1, stack);
            inputInfo.get(0).sampler(sampler).imageView(inputView)
                .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

            // b1: STORAGE_IMAGE（output at GENERAL）
            VkDescriptorImageInfo.Buffer outputInfo = VkDescriptorImageInfo.calloc(1, stack);
            outputInfo.get(0).sampler(VK_NULL_HANDLE).imageView(outputView)
                .imageLayout(VK_IMAGE_LAYOUT_GENERAL);

            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
            writes.get(0)
                .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .dstSet(set).dstBinding(0).descriptorCount(1)
                .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .pImageInfo(inputInfo);
            writes.get(1)
                .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .dstSet(set).dstBinding(1).descriptorCount(1)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .pImageInfo(outputInfo);

            vkUpdateDescriptorSets(vkDev, writes, null);
        }
    }

    private static String loadShaderSource(String resourcePath) {
        try (InputStream is = BRFSRManager.class.getResourceAsStream(
                "/assets/blockreality/shaders/" + resourcePath)) {
            if (is == null) return null;
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.error("[FSR] Failed to load shader: {}", resourcePath, e);
            return null;
        }
    }
}
