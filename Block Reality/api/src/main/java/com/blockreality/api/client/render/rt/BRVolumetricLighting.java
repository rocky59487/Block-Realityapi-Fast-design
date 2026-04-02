package com.blockreality.api.client.render.rt;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * BRVolumetricLighting — 體積光照（God Ray）Compute Pass（P2-C）。
 *
 * <h3>功能</h3>
 * <p>使用 Ray Marching 光線步進技術，計算每個像素的體積光照散射強度，
 * 產生逼真的「光柱」（God Ray）和大氣霧效果。輸出紋理供後續
 * {@code COMPOSITE_TONEMAP} pass 疊加到最終畫面。</p>
 *
 * <h3>演算法</h3>
 * <ol>
 *   <li>在每個螢幕像素，從相機沿觀察方向步進至深度緩衝終點</li>
 *   <li>在每個步進點，採樣陰影圖判斷陽光照射情況</li>
 *   <li>以 <b>Henyey-Greenstein</b> 相位函數計算 Mie 散射（前向散射光柱效果）</li>
 *   <li>以 <b>Beer-Lambert</b> 指數衰減計算透射率（霧遠處變濃）</li>
 *   <li>Temporal 抖動（4-frame Halton 序列）消除步進條紋偽影</li>
 * </ol>
 *
 * <h3>Composite 整合</h3>
 * <pre>
 * finalColor = sceneColor × transmittance + volumetricScatter
 * </pre>
 *
 * <h3>Phase 1（本類）vs Phase 2（後續強化）</h3>
 * <ul>
 *   <li>Phase 1：基本大氣霧 + 方向散射，無陰影圖（全幀均勻散射）</li>
 *   <li>Phase 2（後續）：整合 shadow map + lightSpaceMat UBO → 真實光柱/光束</li>
 * </ul>
 *
 * <h3>Binding 佈局</h3>
 * <pre>
 * b0: COMBINED_IMAGE_SAMPLER — depthTex（本幀線性深度）
 * b1: COMBINED_IMAGE_SAMPLER — shadowMap（平行光陰影圖，Phase 2 接入）
 * b2: STORAGE_IMAGE rgba16f  — outputVolume（體積光照輸出）
 * </pre>
 *
 * <h3>Push Constants（48 bytes）</h3>
 * <pre>
 * uint  width, height
 * float sunDirX, sunDirY, sunDirZ, sunIntensity
 * float fogDensity, henyeyG
 * uint  numSteps, frame
 * float nearPlane, farPlane
 * </pre>
 */
@OnlyIn(Dist.CLIENT)
public final class BRVolumetricLighting {

    private static final Logger LOGGER = LoggerFactory.getLogger("BR-VolLight");

    // ── 預設超參數 ────────────────────────────────────────────────────────────

    /** 預設霧密度（0.004 = 輕霧，適合 Minecraft 建築場景）。 */
    public static final float DEFAULT_FOG_DENSITY   = 0.004f;
    /** 預設 Henyey-Greenstein 各向異性（0.7 = 適度前向散射，Mie 散射典型值）。 */
    public static final float DEFAULT_HENYEY_G      = 0.70f;
    /** 預設步進次數（性能/質量平衡）。 */
    public static final int   DEFAULT_NUM_STEPS     = 32;
    /** 預設體積光照最大距離（Minecraft 方塊單位）。 */
    public static final float DEFAULT_FAR_PLANE     = 64.0f;
    /** 相機近裁切平面（Minecraft 預設）。 */
    public static final float DEFAULT_NEAR_PLANE    = 0.05f;

    // ── VK 格式常數 ────────────────────────────────────────────────────────────

    /** VK_FORMAT_R16G16B16A16_SFLOAT */
    private static final int FMT_RGBA16F = 97;

    // ── 狀態 ─────────────────────────────────────────────────────────────────

    private static final BRVolumetricLighting INSTANCE = new BRVolumetricLighting();

    public static BRVolumetricLighting getInstance() { return INSTANCE; }

    private BRVolumetricLighting() {}

    private boolean  initialized    = false;
    private int      displayWidth   = 0;
    private int      displayHeight  = 0;
    private int      frameIndex     = 0;
    private boolean  active         = true;

    // Vulkan 資源
    private long dsLayout          = 0L;
    private long pipelineLayout    = 0L;
    private long pipeline          = 0L;
    private long descriptorPool    = 0L;
    private long descriptorSet     = 0L;
    private long sampler           = 0L;

    // 輸出圖像 {VkImage, VkDeviceMemory, VkImageView}
    private final long[] outputImage = new long[3];

    // 當前參數
    private float fogDensity   = DEFAULT_FOG_DENSITY;
    private float henyeyG      = DEFAULT_HENYEY_G;
    private int   numSteps     = DEFAULT_NUM_STEPS;
    private float farPlane     = DEFAULT_FAR_PLANE;
    private final Vector3f sunDirection = new Vector3f(0.5f, 1.0f, 0.3f).normalize();
    private float sunIntensity = 1.0f;

    // ═══════════════════════════════════════════════════════════════════════════
    // 生命週期
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * 初始化體積光照管線。
     *
     * @param w 輸出寬度（像素）
     * @param h 輸出高度（像素）
     */
    public void init(int w, int h) {
        if (initialized) {
            LOGGER.warn("[VolLight] init() called while already initialized");
            return;
        }

        long device = BRVulkanDevice.getVkDevice();
        if (device == 0L) {
            LOGGER.warn("[VolLight] Vulkan device not ready — volumetric lighting disabled");
            return;
        }

        LOGGER.info("[VolLight] Initializing ({}×{})...", w, h);
        displayWidth  = w;
        displayHeight = h;
        frameIndex    = 0;

        try {
            if (!createDsLayout(device))          throw new RuntimeException("DS layout");
            if (!createPipelineLayout(device))    throw new RuntimeException("pipeline layout");
            if (!createPipeline(device))          throw new RuntimeException("pipeline");

            sampler = BRVulkanDevice.createNearestSampler(device);
            if (sampler == 0L)                    throw new RuntimeException("sampler");

            if (!allocateOutputImage(device, w, h)) throw new RuntimeException("output image");
            if (!createDescriptorPool(device))    throw new RuntimeException("descriptor pool");
            if (!allocateDescriptorSet(device))   throw new RuntimeException("descriptor set");

            initialized = true;
            LOGGER.info("[VolLight] Initialized — fogDensity={}, henyeyG={}, numSteps={}",
                    fogDensity, henyeyG, numSteps);

        } catch (Exception e) {
            LOGGER.error("[VolLight] Init failed at step: {}", e.getMessage());
            cleanup();
        }
    }

    /**
     * 解析度變更時重新建立輸出圖像。
     *
     * @param w 新寬度
     * @param h 新高度
     */
    public void onResize(int w, int h) {
        if (!initialized || (w == displayWidth && h == displayHeight)) return;

        long device = BRVulkanDevice.getVkDevice();
        if (device == 0L) return;

        LOGGER.info("[VolLight] Resize: {}×{} → {}×{}", displayWidth, displayHeight, w, h);
        freeOutputImage(device);
        displayWidth  = w;
        displayHeight = h;
        frameIndex    = 0;

        if (!allocateOutputImage(device, w, h)) {
            LOGGER.error("[VolLight] Failed to reallocate output image — disabling");
            initialized = false;
        }
    }

    /** 釋放所有 Vulkan 資源。 */
    public void cleanup() {
        long device = BRVulkanDevice.getVkDevice();
        if (device != 0L) {
            freeOutputImage(device);
            BRVulkanDevice.destroyDescriptorPool(device, descriptorPool);
            descriptorPool = 0L; descriptorSet = 0L;
            if (sampler != 0L)        { BRVulkanDevice.destroySampler(device, sampler);               sampler = 0L; }
            if (pipeline != 0L)       { BRVulkanDevice.destroyPipeline(device, pipeline);             pipeline = 0L; }
            if (pipelineLayout != 0L) { BRVulkanDevice.destroyPipelineLayout(device, pipelineLayout); pipelineLayout = 0L; }
            if (dsLayout != 0L)       { BRVulkanDevice.destroyDescriptorSetLayout(device, dsLayout);  dsLayout = 0L; }
        }
        initialized = false;
        frameIndex  = 0;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 調度主入口
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * 執行體積光照 compute pass。
     *
     * <p>呼叫端責任：
     * <ul>
     *   <li>{@code depthView}：layout SHADER_READ_ONLY_OPTIMAL</li>
     *   <li>{@code shadowMapView}：layout SHADER_READ_ONLY_OPTIMAL（可傳 0L 跳過陰影採樣）</li>
     * </ul>
     *
     * @param depthView     深度緩衝 VkImageView
     * @param shadowMapView 陰影圖 VkImageView（Phase 2 整合；傳 0L = 使用空白陰影圖）
     * @return 輸出體積光照 VkImageView（rgba16f，layout GENERAL），或 0L 若未初始化
     */
    public long dispatch(long depthView, long shadowMapView) {
        if (!initialized || !active) return 0L;

        long device = BRVulkanDevice.getVkDevice();
        if (device == 0L) return 0L;

        // 更新描述符集（每幀：depthView 可能隨 G-Buffer ping-pong 變化）
        updateDescriptorSet(device, depthView,
                            (shadowMapView != 0L) ? shadowMapView : depthView); // fallback to depth

        long cmd = BRVulkanDevice.beginSingleTimeCommands(device);
        if (cmd == 0L) { LOGGER.error("[VolLight] beginSingleTimeCommands failed"); return 0L; }

        VkCommandBuffer vkCmd = new VkCommandBuffer(cmd, BRVulkanDevice.getVkDeviceObj());

        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindPipeline(vkCmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);

            LongBuffer dsBuffer = stack.longs(descriptorSet);
            vkCmdBindDescriptorSets(vkCmd, VK_PIPELINE_BIND_POINT_COMPUTE,
                    pipelineLayout, 0, dsBuffer, null);

            // Push constants（48 bytes）
            java.nio.ByteBuffer pc = stack.malloc(48);
            pc.putInt(0,  displayWidth);
            pc.putInt(4,  displayHeight);
            pc.putFloat(8,  sunDirection.x);
            pc.putFloat(12, sunDirection.y);
            pc.putFloat(16, sunDirection.z);
            pc.putFloat(20, sunIntensity);
            pc.putFloat(24, fogDensity);
            pc.putFloat(28, henyeyG);
            pc.putInt(32,  numSteps);
            pc.putInt(36,  frameIndex);
            pc.putFloat(40, DEFAULT_NEAR_PLANE);
            pc.putFloat(44, farPlane);

            vkCmdPushConstants(vkCmd, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);

            int gx = (displayWidth  + 7) / 8;
            int gy = (displayHeight + 7) / 8;
            vkCmdDispatch(vkCmd, gx, gy, 1);
        }

        BRVulkanDevice.endSingleTimeCommands(device, cmd);
        frameIndex++;

        return outputImage[2]; // VkImageView
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 參數設定
    // ═══════════════════════════════════════════════════════════════════════════

    /** 更新太陽方向（世界空間，從場景向太陽的方向，normalized）。 */
    public void setSunDirection(float x, float y, float z) {
        sunDirection.set(x, y, z).normalize();
    }

    /** 設定太陽光強度（0.0 = 夜晚，1.0 = 正午）。 */
    public void setSunIntensity(float intensity) {
        this.sunIntensity = Math.max(0.0f, intensity);
    }

    /** 設定霧密度（0.001 = 無霧，0.05 = 濃霧）。 */
    public void setFogDensity(float density) {
        this.fogDensity = Math.max(0.0f, density);
    }

    /** 設定 Henyey-Greenstein 各向異性參數（0.0-0.99）。 */
    public void setHenyeyG(float g) {
        this.henyeyG = Math.clamp(g, 0.0f, 0.99f);
    }

    /** 設定步進次數（越高越精確，影響性能）。 */
    public void setNumSteps(int steps) {
        this.numSteps = Math.max(4, Math.min(steps, 128));
    }

    /** 設定體積光照最大距離（Minecraft 方塊單位）。 */
    public void setFarPlane(float far) {
        this.farPlane = Math.max(8.0f, far);
    }

    /** 啟用/停用體積光照。 */
    public void setActive(boolean active) { this.active = active; }

    public boolean isInitialized() { return initialized; }
    public boolean isActive()      { return active; }
    public long    getOutputView() { return outputImage[2]; }

    // ═══════════════════════════════════════════════════════════════════════════
    // 管線建立
    // ═══════════════════════════════════════════════════════════════════════════

    private boolean createDsLayout(long device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // b0, b1: COMBINED_IMAGE_SAMPLER; b2: STORAGE_IMAGE
            VkDescriptorSetLayoutBinding.Buffer bindings =
                    VkDescriptorSetLayoutBinding.calloc(3, stack);
            bindings.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(2).binding(2).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);

            LongBuffer pLayout = stack.mallocLong(1);
            int r = vkCreateDescriptorSetLayout(BRVulkanDevice.getVkDeviceObj(),
                    VkDescriptorSetLayoutCreateInfo.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                            .pBindings(bindings),
                    null, pLayout);
            if (r != VK_SUCCESS) { LOGGER.error("[VolLight] DS layout failed: {}", r); return false; }
            dsLayout = pLayout.get(0);
            return true;
        }
    }

    private boolean createPipelineLayout(long device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Push constants: 48 bytes
            VkPushConstantRange.Buffer pcRange = VkPushConstantRange.calloc(1, stack);
            pcRange.get(0).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(48);

            LongBuffer pPLayout = stack.mallocLong(1);
            int r = vkCreatePipelineLayout(BRVulkanDevice.getVkDeviceObj(),
                    VkPipelineLayoutCreateInfo.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                            .pSetLayouts(stack.longs(dsLayout))
                            .pPushConstantRanges(pcRange),
                    null, pPLayout);
            if (r != VK_SUCCESS) { LOGGER.error("[VolLight] pipeline layout failed: {}", r); return false; }
            pipelineLayout = pPLayout.get(0);
            return true;
        }
    }

    private boolean createPipeline(long device) {
        String glsl = loadShaderResource("volumetric_lighting.comp.glsl");
        if (glsl == null) return false;
        byte[] spirv = BRVulkanDevice.compileGLSLtoSPIRV(glsl, "volumetric_lighting.comp.glsl");
        if (spirv == null) return false;
        long shaderModule = BRVulkanDevice.createShaderModule(device, spirv);
        if (shaderModule == 0L) return false;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pPipeline = stack.mallocLong(1);
            int r = vkCreateComputePipelines(BRVulkanDevice.getVkDeviceObj(), VK_NULL_HANDLE,
                    VkComputePipelineCreateInfo.calloc(1, stack)
                            .sType(VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO)
                            .stage(VkPipelineShaderStageCreateInfo.calloc(stack)
                                    .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                                    .stage(VK_SHADER_STAGE_COMPUTE_BIT)
                                    .module(shaderModule)
                                    .pName(stack.UTF8("main")))
                            .layout(pipelineLayout),
                    null, pPipeline);
            BRVulkanDevice.destroyShaderModule(device, shaderModule);
            if (r != VK_SUCCESS) { LOGGER.error("[VolLight] pipeline creation failed: {}", r); return false; }
            pipeline = pPipeline.get(0);
            return true;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 圖像與描述符管理
    // ═══════════════════════════════════════════════════════════════════════════

    private boolean allocateOutputImage(long device, int w, int h) {
        int usage  = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
        long[] img = BRVulkanDevice.createImage2D(device, w, h, FMT_RGBA16F, usage,
                                                   VK_IMAGE_ASPECT_COLOR_BIT);
        if (img == null) { LOGGER.error("[VolLight] Failed to create output image"); return false; }
        outputImage[0] = img[0]; outputImage[1] = img[1]; outputImage[2] = img[2];
        BRVulkanDevice.transitionImageLayout(device, img[0],
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
                VK_IMAGE_ASPECT_COLOR_BIT);
        return true;
    }

    private void freeOutputImage(long device) {
        if (outputImage[0] != 0L) {
            BRVulkanDevice.destroyImage2D(device, outputImage[0], outputImage[1], outputImage[2]);
            outputImage[0] = 0L; outputImage[1] = 0L; outputImage[2] = 0L;
        }
    }

    private boolean createDescriptorPool(long device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(2, stack);
            poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(2);
            poolSizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1);
            LongBuffer pPool = stack.mallocLong(1);
            int r = vkCreateDescriptorPool(BRVulkanDevice.getVkDeviceObj(),
                    VkDescriptorPoolCreateInfo.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                            .maxSets(1)
                            .pPoolSizes(poolSizes),
                    null, pPool);
            if (r != VK_SUCCESS) { LOGGER.error("[VolLight] descriptor pool failed: {}", r); return false; }
            descriptorPool = pPool.get(0);
            return true;
        }
    }

    private boolean allocateDescriptorSet(long device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pSet = stack.mallocLong(1);
            int r = vkAllocateDescriptorSets(BRVulkanDevice.getVkDeviceObj(),
                    VkDescriptorSetAllocateInfo.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                            .descriptorPool(descriptorPool)
                            .pSetLayouts(stack.longs(dsLayout)),
                    pSet);
            if (r != VK_SUCCESS) { LOGGER.error("[VolLight] descriptor set alloc failed: {}", r); return false; }
            descriptorSet = pSet.get(0);
            return true;
        }
    }

    private void updateDescriptorSet(long device, long depthView, long shadowView) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(3, stack);

            // b0 — depth texture（COMBINED_IMAGE_SAMPLER）
            VkDescriptorImageInfo.Buffer b0 = VkDescriptorImageInfo.calloc(1, stack);
            b0.get(0).imageView(depthView).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL).sampler(sampler);
            writes.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(descriptorSet).dstBinding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
                    .pImageInfo(b0);

            // b1 — shadow map（COMBINED_IMAGE_SAMPLER）
            VkDescriptorImageInfo.Buffer b1 = VkDescriptorImageInfo.calloc(1, stack);
            b1.get(0).imageView(shadowView).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL).sampler(sampler);
            writes.get(1).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(descriptorSet).dstBinding(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
                    .pImageInfo(b1);

            // b2 — output volume（STORAGE_IMAGE）
            VkDescriptorImageInfo.Buffer b2 = VkDescriptorImageInfo.calloc(1, stack);
            b2.get(0).imageView(outputImage[2]).imageLayout(VK_IMAGE_LAYOUT_GENERAL).sampler(VK_NULL_HANDLE);
            writes.get(2).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(descriptorSet).dstBinding(2)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1)
                    .pImageInfo(b2);

            vkUpdateDescriptorSets(BRVulkanDevice.getVkDeviceObj(), writes, null);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 資源載入
    // ═══════════════════════════════════════════════════════════════════════════

    private static String loadShaderResource(String filename) {
        String path = "/assets/blockreality/shaders/compute/" + filename;
        try (InputStream is = BRVolumetricLighting.class.getResourceAsStream(path)) {
            if (is == null) { LOGGER.error("[VolLight] Shader not found: {}", path); return null; }
            return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.error("[VolLight] Failed to load shader: {}", path, e);
            return null;
        }
    }
}
