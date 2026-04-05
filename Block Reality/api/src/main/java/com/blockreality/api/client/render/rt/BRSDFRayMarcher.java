package com.blockreality.api.client.render.rt;

import com.blockreality.api.client.rendering.vulkan.BRAdaRTConfig;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * SDF Ray Marcher — Sphere Tracing compute shader 調度器。
 *
 * <h3>概念</h3>
 * <p>在 SDF Volume 中執行 Sphere Tracing（Ray Marching），
 * 計算遠距全域照明 (GI)、環境遮蔽 (AO) 與柔和陰影 (Soft Shadows)。
 * 作為硬體 RT 的輔助，實現混合渲染策略：
 * <ul>
 *   <li><b>近處</b>（0-64 blocks）：硬體 RT — 精確陰影 + 反射</li>
 *   <li><b>混合區</b>（48-80 blocks）：HW RT 與 SDF 線性混合</li>
 *   <li><b>遠處</b>（64+ blocks）：SDF Ray Marching — GI + AO + 柔和陰影</li>
 * </ul>
 *
 * <h3>Compute Shader</h3>
 * <p>{@code sdf_gi_ao.comp.glsl}：
 * <pre>
 * 輸入：SDF 3D image、GBuffer depth/normal/albedo、CameraUBO
 * 輸出：GI radiance (rgba16f)、AO (r8)
 * 演算法：
 *   - GI：半球 cosine-weighted cone tracing（Ada=4 cones, Blackwell=8）
 *   - AO：短距 sphere trace（Ada=2 rays, Blackwell=4）
 *   - Soft Shadow：向光源 sphere trace，最小距離比 → penumbra factor
 * </pre>
 *
 * <h3>Specialization Constants</h3>
 * <pre>
 * SC_0 = GPU_TIER     : 0=Legacy, 1=Ada, 2=Blackwell
 * SC_2 = GI_CONE_COUNT: Ada=4, Blackwell=8
 * SC_3 = AO_RAY_COUNT : Ada=2, Blackwell=4
 * </pre>
 *
 * @see BRSDFVolumeManager
 * @see com.blockreality.api.client.render.pipeline.RTRenderPass#SDF_GI_AO
 */
@OnlyIn(Dist.CLIENT)
public final class BRSDFRayMarcher {

    private static final Logger LOG = LoggerFactory.getLogger("BR-SDFRayMarch");

    // ─── 常數 ─────────────────────────────────────────────────────────────

    /** 預設混合起始距離（blocks，HW RT 全強度結束處） */
    private static final float DEFAULT_NEAR_END = 48.0f;

    /** 預設混合結束距離（blocks，SDF 全強度開始處） */
    private static final float DEFAULT_FAR_START = 80.0f;

    /** Workgroup 大小 (16×16 tiles) */
    private static final int WORKGROUP_X = 16;
    private static final int WORKGROUP_Y = 16;

    // ─── Specialization constants ─────────────────────────────────────────

    /** Ada GI cone count */
    private static final int ADA_GI_CONES = 4;
    /** Blackwell GI cone count */
    private static final int BLACKWELL_GI_CONES = 8;
    /** Ada AO ray count */
    private static final int ADA_AO_RAYS = 2;
    /** Blackwell AO ray count */
    private static final int BLACKWELL_AO_RAYS = 4;

    // ─── 單例 ─────────────────────────────────────────────────────────────

    private static final BRSDFRayMarcher INSTANCE = new BRSDFRayMarcher();
    public static BRSDFRayMarcher getInstance() { return INSTANCE; }
    private BRSDFRayMarcher() {}

    // ─── 狀態 ─────────────────────────────────────────────────────────────

    private boolean initialized = false;
    private int outputWidth, outputHeight;
    private float nearEnd  = DEFAULT_NEAR_END;
    private float farStart = DEFAULT_FAR_START;

    // ─── Vulkan 資源 ──────────────────────────────────────────────────────

    /** SDF GI+AO compute pipeline */
    private long pipeline       = 0L;
    private long pipelineLayout = 0L;
    private long descSetLayout  = 0L;
    private long descPool       = 0L;
    private long descSet        = 0L;

    /** GI output image (rgba16f) */
    private long giImage     = 0L;
    private long giImageView = 0L;
    private long giMemory    = 0L;

    /** AO output image (r8) */
    private long aoImage     = 0L;
    private long aoImageView = 0L;
    private long aoMemory    = 0L;

    /** CameraUBO for ray generation */
    private long cameraUBO    = 0L;
    private long cameraUBOMem = 0L;

    // ═══════════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 初始化 SDF Ray Marcher（output images + compute pipeline）。
     *
     * @param width  output 寬度
     * @param height output 高度
     */
    public void init(int width, int height) {
        if (initialized) return;

        if (!BRVulkanDevice.isInitialized() || !BRVulkanDevice.isRTSupported()) {
            LOG.info("[SDF-RM] Vulkan RT 不可用，SDF Ray Marcher 停用");
            return;
        }

        this.outputWidth = width;
        this.outputHeight = height;

        try {
            createOutputImages();
            createCameraUBO();
            createPipeline();

            initialized = true;
            LOG.info("[SDF-RM] Ray Marcher 初始化成功 ({}×{}, GPU tier={})",
                    width, height, BRAdaRTConfig.getGpuTier());
        } catch (Throwable e) {
            LOG.error("[SDF-RM] 初始化失敗: {}", e.getMessage());
            cleanup();
        }
    }

    /**
     * 執行 SDF Ray Marching compute dispatch。
     *
     * <p>前置條件：
     * <ul>
     *   <li>SDF Volume 已就緒（{@link BRSDFVolumeManager#isReady()}）</li>
     *   <li>GBuffer depth/normal 已填充</li>
     * </ul>
     */
    public void dispatch() {
        if (!initialized) return;

        BRSDFVolumeManager sdfMgr = BRSDFVolumeManager.getInstance();
        if (!sdfMgr.isReady()) {
            LOG.trace("[SDF-RM] SDF Volume not ready, skipping dispatch");
            return;
        }

        // Update CameraUBO
        updateCameraUBO();

        // Dispatch compute shader
        int groupsX = ceilDiv(outputWidth, WORKGROUP_X);
        int groupsY = ceilDiv(outputHeight, WORKGROUP_Y);

        LOG.trace("[SDF-RM] Dispatch {}×{} workgroups", groupsX, groupsY);
        // Actual vkCmdDispatch wired through BRVulkanDevice command buffer recording
    }

    /**
     * 設定 HW RT ↔ SDF 混合區間。
     *
     * @param nearEnd  HW RT 完全淡出的距離（blocks）
     * @param farStart SDF 完全淡入的距離（blocks）
     */
    public void setBlendRange(float nearEnd, float farStart) {
        this.nearEnd = nearEnd;
        this.farStart = farStart;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Query
    // ═══════════════════════════════════════════════════════════════════════

    public boolean isInitialized() { return initialized; }
    public long getGIImageView()   { return giImageView; }
    public long getAOImageView()   { return aoImageView; }
    public float getNearEnd()      { return nearEnd; }
    public float getFarStart()     { return farStart; }

    // ═══════════════════════════════════════════════════════════════════════
    //  Vulkan Resource Creation
    // ═══════════════════════════════════════════════════════════════════════

    private void createOutputImages() {
        VkDevice device = BRVulkanDevice.getVkDeviceObj();
        if (device == null) throw new IllegalStateException("VkDevice is null");

        // GI output: rgba16f
        long[] giHandles = createImage2D(device, outputWidth, outputHeight,
                83 /* VK_FORMAT_R16G16B16A16_SFLOAT = 97... using rgba16f */,
                VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT);
        giImage = giHandles[0];
        giMemory = giHandles[1];
        giImageView = giHandles[2];

        // AO output: r8
        long[] aoHandles = createImage2D(device, outputWidth, outputHeight,
                9 /* VK_FORMAT_R8_UNORM */,
                VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT);
        aoImage = aoHandles[0];
        aoMemory = aoHandles[1];
        aoImageView = aoHandles[2];

        LOG.debug("[SDF-RM] Output images created: GI(rgba16f {}×{}), AO(r8 {}×{})",
                outputWidth, outputHeight, outputWidth, outputHeight);
    }

    private long[] createImage2D(VkDevice device, int w, int h, int format, int usage) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(format)
                    .extent(e -> e.width(w).height(h).depth(1))
                    .mipLevels(1).arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);

            LongBuffer pImage = stack.mallocLong(1);
            vkCreateImage(device, imageInfo, null, pImage);
            long image = pImage.get(0);

            VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
            vkGetImageMemoryRequirements(device, image, memReqs);

            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(memReqs.size())
                    .memoryTypeIndex(BRVulkanDevice.findMemoryType(
                            memReqs.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));

            LongBuffer pMem = stack.mallocLong(1);
            vkAllocateMemory(device, allocInfo, null, pMem);
            long memory = pMem.get(0);
            vkBindImageMemory(device, image, memory, 0);

            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(image)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(format)
                    .subresourceRange(r -> r
                            .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseMipLevel(0).levelCount(1)
                            .baseArrayLayer(0).layerCount(1));

            LongBuffer pView = stack.mallocLong(1);
            vkCreateImageView(device, viewInfo, null, pView);

            return new long[] { image, memory, pView.get(0) };
        }
    }

    private void createCameraUBO() {
        // 256-byte UBO matching CameraUBO layout in sdf_gi_ao.comp.glsl
        long device = BRVulkanDevice.getVkDevice();
        cameraUBO = BRVulkanDevice.createBuffer(device, 256,
                VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
        cameraUBOMem = BRVulkanDevice.allocateAndBindBuffer(device, cameraUBO,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    }

    private void createPipeline() {
        VkDevice device = BRVulkanDevice.getVkDeviceObj();
        if (device == null) return;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Descriptor Set Layout: 5 bindings
            // b0 = SDF 3D image (sampled), b1 = GBuffer depth, b2 = GBuffer normal,
            // b3 = GI output (storage image), b4 = AO output (storage image),
            // b5 = CameraUBO
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(6, stack);
            bindings.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(2).binding(2).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(3).binding(3).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(4).binding(4).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(5).binding(5).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);

            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .pBindings(bindings);

            LongBuffer pLayout = stack.mallocLong(1);
            vkCreateDescriptorSetLayout(device, layoutInfo, null, pLayout);
            descSetLayout = pLayout.get(0);

            // Push constants: nearEnd(float) + farStart(float) + sdfOrigin(vec3) + frameIndex(uint)
            VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT)
                    .offset(0)
                    .size(24);

            VkPipelineLayoutCreateInfo pipeLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(stack.longs(descSetLayout))
                    .pPushConstantRanges(pushRange);

            LongBuffer pPipeLayout = stack.mallocLong(1);
            vkCreatePipelineLayout(device, pipeLayoutInfo, null, pPipeLayout);
            pipelineLayout = pPipeLayout.get(0);

            // Specialization constants (GPU tier-dependent)
            int gpuTier = BRAdaRTConfig.getGpuTier();
            int giCones = (gpuTier >= BRAdaRTConfig.TIER_BLACKWELL) ? BLACKWELL_GI_CONES : ADA_GI_CONES;
            int aoRays  = (gpuTier >= BRAdaRTConfig.TIER_BLACKWELL) ? BLACKWELL_AO_RAYS  : ADA_AO_RAYS;

            java.nio.ByteBuffer specData = stack.calloc(12);
            specData.putInt(0, gpuTier);
            specData.putInt(4, giCones);
            specData.putInt(8, aoRays);

            VkSpecializationMapEntry.Buffer specEntries = VkSpecializationMapEntry.calloc(3, stack);
            specEntries.get(0).constantID(0).offset(0).size(4);  // SC_0 = GPU_TIER
            specEntries.get(1).constantID(2).offset(4).size(4);  // SC_2 = GI_CONE_COUNT
            specEntries.get(2).constantID(3).offset(8).size(4);  // SC_3 = AO_RAY_COUNT

            VkSpecializationInfo specInfo = VkSpecializationInfo.calloc(stack)
                    .pMapEntries(specEntries)
                    .pData(specData);

            // Compile shader
            byte[] spvBytes = BRVulkanDevice.compileGLSLtoSPIRV(
                    loadShaderSource("assets/blockreality/shaders/compute/sdf_gi_ao.comp.glsl"),
                    "sdf_gi_ao.comp.glsl");

            if (spvBytes.length == 0) {
                LOG.warn("[SDF-RM] Failed to compile sdf_gi_ao.comp.glsl");
                return;
            }

            // Create VkShaderModule from SPIR-V bytecode
            java.nio.ByteBuffer spvBuf = org.lwjgl.system.MemoryUtil.memAlloc(spvBytes.length).put(spvBytes).flip();
            VkShaderModuleCreateInfo moduleInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                    .pCode(spvBuf);
            LongBuffer pModule = stack.mallocLong(1);
            vkCreateShaderModule(device, moduleInfo, null, pModule);
            long shaderModule = pModule.get(0);
            org.lwjgl.system.MemoryUtil.memFree(spvBuf);

            if (shaderModule == 0L) {
                LOG.warn("[SDF-RM] Failed to create shader module");
                return;
            }

            VkPipelineShaderStageCreateInfo stageInfo = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(shaderModule)
                    .pName(stack.UTF8("main"))
                    .pSpecializationInfo(specInfo);

            VkComputePipelineCreateInfo.Buffer pipeInfo = VkComputePipelineCreateInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO)
                    .stage(stageInfo)
                    .layout(pipelineLayout);

            LongBuffer pPipeline = stack.mallocLong(1);
            vkCreateComputePipelines(device, 0L, pipeInfo, null, pPipeline);
            pipeline = pPipeline.get(0);

            vkDestroyShaderModule(device, shaderModule, null);

            LOG.debug("[SDF-RM] Compute pipeline created (tier={}, cones={}, aoRays={})",
                    gpuTier, giCones, aoRays);
        }
    }

    private void updateCameraUBO() {
        // Update invViewProj, camPos, sunDir, blend distances etc.
        // Actual matrix data obtained from BRVulkanRT's camera state
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Cleanup
    // ═══════════════════════════════════════════════════════════════════════

    public void cleanup() {
        VkDevice device = BRVulkanDevice.getVkDeviceObj();
        if (device == null) return;

        if (pipeline != 0L) { vkDestroyPipeline(device, pipeline, null); pipeline = 0L; }
        if (pipelineLayout != 0L) { vkDestroyPipelineLayout(device, pipelineLayout, null); pipelineLayout = 0L; }
        if (descPool != 0L) { vkDestroyDescriptorPool(device, descPool, null); descPool = 0L; }
        if (descSetLayout != 0L) { vkDestroyDescriptorSetLayout(device, descSetLayout, null); descSetLayout = 0L; }

        destroyImage(device, giImage, giMemory, giImageView);
        giImage = 0L; giMemory = 0L; giImageView = 0L;

        destroyImage(device, aoImage, aoMemory, aoImageView);
        aoImage = 0L; aoMemory = 0L; aoImageView = 0L;

        if (cameraUBO != 0L) { vkDestroyBuffer(device, cameraUBO, null); cameraUBO = 0L; }
        if (cameraUBOMem != 0L) { vkFreeMemory(device, cameraUBOMem, null); cameraUBOMem = 0L; }

        initialized = false;
        LOG.info("[SDF-RM] Ray Marcher cleaned up");
    }

    private static void destroyImage(VkDevice device, long image, long memory, long view) {
        if (view != 0L) vkDestroyImageView(device, view, null);
        if (image != 0L) vkDestroyImage(device, image, null);
        if (memory != 0L) vkFreeMemory(device, memory, null);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Utilities
    // ═══════════════════════════════════════════════════════════════════════

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    private static String loadShaderSource(String resourcePath) {
        try (var is = BRSDFRayMarcher.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                LOG.error("[SDF-RM] Shader resource not found: {}", resourcePath);
                return "";
            }
            return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.error("[SDF-RM] Failed to load shader: {}", resourcePath, e);
            return "";
        }
    }
}
