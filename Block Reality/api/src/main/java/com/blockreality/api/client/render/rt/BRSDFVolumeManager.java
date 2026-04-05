package com.blockreality.api.client.render.rt;

import com.blockreality.api.client.rendering.vulkan.VkContext;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.lwjgl.vulkan.VK10.*;

/**
 * SDF Volume 管理器 — 管理 Vulkan 3D SDF Texture 與增量更新。
 *
 * <h3>設計</h3>
 * <p>維護一個以相機為中心的 3D SDF Volume（R16F 格式），
 * 涵蓋 {@value #VOLUME_RADIUS} 方塊半徑的球形區域。
 * 體素資料來源為 {@code VoxelSection}（16³ blocks per section）。
 *
 * <h3>階層式 SDF</h3>
 * <ul>
 *   <li><b>Level 0</b>（1:1）：每 block = 1 texel，近距精確 AO/陰影</li>
 *   <li><b>Level 1</b>（2:1）：每 2 blocks = 1 texel，中距 GI</li>
 *   <li><b>Level 2</b>（4:1）：每 4 blocks = 1 texel，遠距粗糙 GI</li>
 * </ul>
 *
 * <h3>動態更新</h3>
 * <p>方塊變更時由事件系統呼叫 {@link #markDirty(int, int, int)}，
 * 每幀 {@link #updateSDF()} 消費最多 {@value #MAX_UPDATES_PER_FRAME} 個 dirty section，
 * 使用 Jump Flooding Algorithm (JFA) 在 GPU compute shader 上重建局部 SDF。
 * 優先更新距相機最近的 section。
 *
 * <h3>Vulkan 資源</h3>
 * <pre>
 * 3D Image:  VK_FORMAT_R16_SFLOAT, VOLUME_DIM³
 * Pipeline:  sdf_update.comp.glsl (JFA pass)
 * Bindings:  set 0 b0 = occupancy SSBO (uint[], 1 bit per block)
 *            set 0 b1 = SDF 3D image (R16F, imageStore)
 * </pre>
 *
 * @see BRSDFRayMarcher
 * @see com.blockreality.api.client.render.pipeline.RTRenderPass#SDF_UPDATE
 */
@OnlyIn(Dist.CLIENT)
public final class BRSDFVolumeManager {

    private static final Logger LOG = LoggerFactory.getLogger("BR-SDFVolume");

    // ─── 常數 ─────────────────────────────────────────────────────────────

    /** SDF Volume 涵蓋半徑（方塊數）— 每方向 128 blocks = 256 直徑 */
    public static final int VOLUME_RADIUS = 128;

    /** SDF 3D Texture 每軸解析度（Level 0, 1:1） */
    public static final int VOLUME_DIM = VOLUME_RADIUS * 2;

    /** 每幀最大 dirty section 更新數量（預算控制，避免 GPU stall） */
    public static final int MAX_UPDATES_PER_FRAME = 8;

    /** JFA pass 次數 = ceil(log2(VOLUME_DIM)) */
    private static final int JFA_PASSES = (int) Math.ceil(Math.log(VOLUME_DIM) / Math.log(2));

    /** VK_FORMAT_R16_SFLOAT 格式常數 */
    private static final int FORMAT_R16_SFLOAT = 76;

    // ─── 單例 ─────────────────────────────────────────────────────────────

    private static final BRSDFVolumeManager INSTANCE = new BRSDFVolumeManager();
    public static BRSDFVolumeManager getInstance() { return INSTANCE; }
    private BRSDFVolumeManager() {}

    // ─── 狀態 ─────────────────────────────────────────────────────────────

    private boolean initialized = false;
    private boolean sdfReady    = false;

    // ─── Vulkan 資源 ──────────────────────────────────────────────────────

    /** SDF 3D Image (R16F, VOLUME_DIM³) */
    private long sdfImage     = 0L;
    private long sdfImageView = 0L;
    private long sdfMemory    = 0L;
    private long sdfSampler   = 0L;

    /** Occupancy staging buffer (uint[], 1 bit per block) */
    private long occupancyBuffer = 0L;
    private long occupancyMemory = 0L;

    /** JFA compute pipeline */
    private long jfaPipeline       = 0L;
    private long jfaPipelineLayout = 0L;
    private long jfaDescSetLayout  = 0L;
    private long jfaDescPool       = 0L;
    private long jfaDescSet        = 0L;

    // ─── Dirty tracking ───────────────────────────────────────────────────

    /** 待更新的 section 座標（sectionX, sectionY, sectionZ 編碼為 long key） */
    private final Set<Long> dirtySections = ConcurrentHashMap.newKeySet();
    private final Queue<Long> dirtyQueue  = new ConcurrentLinkedQueue<>();

    /** 相機世界座標（用於距離排序，由外部每幀設定） */
    private double camX, camY, camZ;

    // ─── Volume 世界座標原點 ──────────────────────────────────────────────

    /** SDF Volume 左下角世界座標（block units） */
    private int originX, originY, originZ;

    // ═══════════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 初始化 SDF Volume（3D image + compute pipeline）。
     * 若 Vulkan 不可用則安靜返回。
     */
    public void init() {
        if (initialized) return;

        if (!BRVulkanDevice.isInitialized() || !BRVulkanDevice.isRTSupported()) {
            LOG.info("[SDF] Vulkan RT 不可用，SDF Volume 停用");
            return;
        }

        try {
            createSDFImage();
            createOccupancyBuffer();
            createJFAPipeline();

            initialized = true;
            LOG.info("[SDF] Volume Manager 初始化成功 ({}³ texels, {} JFA passes)",
                    VOLUME_DIM, JFA_PASSES);
        } catch (Throwable e) {
            LOG.error("[SDF] Volume Manager 初始化失敗: {}", e.getMessage());
            cleanup();
        }
    }

    /**
     * 每幀更新 SDF — 消費 dirty sections 並 dispatch JFA compute。
     */
    public void updateSDF() {
        if (!initialized) return;

        int updated = 0;
        while (updated < MAX_UPDATES_PER_FRAME && !dirtyQueue.isEmpty()) {
            Long key = dirtyQueue.poll();
            if (key == null) break;
            dirtySections.remove(key);

            // 更新該 section 的 occupancy data 到 GPU staging buffer
            uploadSectionOccupancy(key);
            updated++;
        }

        if (updated > 0) {
            // Dispatch JFA compute shader 重建整個 SDF
            dispatchJFA();
            sdfReady = true;
            LOG.trace("[SDF] Updated {} sections, {} remaining", updated, dirtyQueue.size());
        }
    }

    /**
     * 標記 section 為 dirty（方塊變更時呼叫）。
     */
    public void markDirty(int sectionX, int sectionY, int sectionZ) {
        long key = encodeSectionKey(sectionX, sectionY, sectionZ);
        if (dirtySections.add(key)) {
            dirtyQueue.offer(key);
        }
    }

    /**
     * 更新相機位置（用於 dirty section 優先排序）。
     */
    public void setCameraPosition(double x, double y, double z) {
        this.camX = x;
        this.camY = y;
        this.camZ = z;

        // 重新計算 volume 原點（以相機為中心）
        this.originX = (int) x - VOLUME_RADIUS;
        this.originY = (int) y - VOLUME_RADIUS;
        this.originZ = (int) z - VOLUME_RADIUS;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Query
    // ═══════════════════════════════════════════════════════════════════════

    public boolean isInitialized() { return initialized; }
    public boolean isReady()       { return sdfReady; }
    public long getSDFImageView()  { return sdfImageView; }
    public long getSDFSampler()    { return sdfSampler; }
    public int getOriginX()        { return originX; }
    public int getOriginY()        { return originY; }
    public int getOriginZ()        { return originZ; }

    // ═══════════════════════════════════════════════════════════════════════
    //  Vulkan Resource Creation
    // ═══════════════════════════════════════════════════════════════════════

    private void createSDFImage() {
        VkDevice device = BRVulkanDevice.getVkDeviceObj();
        if (device == null) throw new IllegalStateException("VkDevice is null");

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 3D Image
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .imageType(VK_IMAGE_TYPE_3D)
                    .format(FORMAT_R16_SFLOAT)
                    .extent(e -> e.width(VOLUME_DIM).height(VOLUME_DIM).depth(VOLUME_DIM))
                    .mipLevels(1)
                    .arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);

            LongBuffer pImage = stack.mallocLong(1);
            int result = vkCreateImage(device, imageInfo, null, pImage);
            if (result != VK_SUCCESS) {
                throw new RuntimeException("vkCreateImage failed: " + result);
            }
            sdfImage = pImage.get(0);

            // Memory allocation
            VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
            vkGetImageMemoryRequirements(device, sdfImage, memReqs);

            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(memReqs.size())
                    .memoryTypeIndex(BRVulkanDevice.findMemoryType(
                            memReqs.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));

            LongBuffer pMemory = stack.mallocLong(1);
            result = vkAllocateMemory(device, allocInfo, null, pMemory);
            if (result != VK_SUCCESS) {
                throw new RuntimeException("vkAllocateMemory failed: " + result);
            }
            sdfMemory = pMemory.get(0);
            vkBindImageMemory(device, sdfImage, sdfMemory, 0);

            // Image View
            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(sdfImage)
                    .viewType(VK_IMAGE_VIEW_TYPE_3D)
                    .format(FORMAT_R16_SFLOAT)
                    .subresourceRange(r -> r
                            .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseMipLevel(0).levelCount(1)
                            .baseArrayLayer(0).layerCount(1));

            LongBuffer pView = stack.mallocLong(1);
            result = vkCreateImageView(device, viewInfo, null, pView);
            if (result != VK_SUCCESS) {
                throw new RuntimeException("vkCreateImageView failed: " + result);
            }
            sdfImageView = pView.get(0);

            // Sampler (trilinear, clamp-to-border)
            VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                    .magFilter(VK_FILTER_LINEAR)
                    .minFilter(VK_FILTER_LINEAR)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER)
                    .borderColor(VK_BORDER_COLOR_FLOAT_OPAQUE_WHITE)
                    .maxLod(0.0f);

            LongBuffer pSampler = stack.mallocLong(1);
            result = vkCreateSampler(device, samplerInfo, null, pSampler);
            if (result != VK_SUCCESS) {
                throw new RuntimeException("vkCreateSampler failed: " + result);
            }
            sdfSampler = pSampler.get(0);

            LOG.debug("[SDF] 3D Image created: {}³ R16F ({} MB)",
                    VOLUME_DIM, memReqs.size() / (1024 * 1024));
        }
    }

    private void createOccupancyBuffer() {
        // Occupancy buffer: 1 bit per block, packed as uint32
        long bufSize = (long) VOLUME_DIM * VOLUME_DIM * VOLUME_DIM / 8;
        long device = BRVulkanDevice.getVkDevice();
        occupancyBuffer = BRVulkanDevice.createBuffer(device, bufSize,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        occupancyMemory = BRVulkanDevice.allocateAndBindBuffer(device, occupancyBuffer,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    }

    private void createJFAPipeline() {
        // Pipeline creation — uses BRVulkanDevice shader compilation utilities
        // Shader source loaded from classpath resource
        VkDevice device = BRVulkanDevice.getVkDeviceObj();
        if (device == null) return;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Descriptor Set Layout: 2 bindings
            // b0 = SSBO (occupancy, readonly), b1 = storage image (SDF 3D)
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(2, stack);
            bindings.get(0)
                    .binding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(1)
                    .binding(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);

            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .pBindings(bindings);

            LongBuffer pLayout = stack.mallocLong(1);
            vkCreateDescriptorSetLayout(device, layoutInfo, null, pLayout);
            jfaDescSetLayout = pLayout.get(0);

            // Pipeline Layout (push constants for JFA step size + volume dimensions)
            VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT)
                    .offset(0)
                    .size(16); // stepSize(int) + dimX(int) + dimY(int) + dimZ(int)

            VkPipelineLayoutCreateInfo pipeLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(stack.longs(jfaDescSetLayout))
                    .pPushConstantRanges(pushRange);

            LongBuffer pPipeLayout = stack.mallocLong(1);
            vkCreatePipelineLayout(device, pipeLayoutInfo, null, pPipeLayout);
            jfaPipelineLayout = pPipeLayout.get(0);

            // Compute pipeline — shader compiled at runtime via BRVulkanDevice
            byte[] spvBytes = BRVulkanDevice.compileGLSLtoSPIRV(
                    loadShaderSource("assets/blockreality/shaders/compute/sdf_update.comp.glsl"),
                    "sdf_update.comp.glsl");

            if (spvBytes.length == 0) {
                LOG.warn("[SDF] Failed to compile sdf_update.comp.glsl, SDF disabled");
                return;
            }

            // Create VkShaderModule from SPIR-V bytecode
            java.nio.ByteBuffer spvBuf = MemoryUtil.memAlloc(spvBytes.length).put(spvBytes).flip();
            VkShaderModuleCreateInfo moduleInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                    .pCode(spvBuf);
            LongBuffer pModule = stack.mallocLong(1);
            vkCreateShaderModule(device, moduleInfo, null, pModule);
            long shaderModule = pModule.get(0);
            MemoryUtil.memFree(spvBuf);

            if (shaderModule == 0L) {
                LOG.warn("[SDF] Failed to create shader module, SDF disabled");
                return;
            }

            VkPipelineShaderStageCreateInfo stageInfo = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(shaderModule)
                    .pName(stack.UTF8("main"));

            VkComputePipelineCreateInfo.Buffer pipeInfo = VkComputePipelineCreateInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO)
                    .stage(stageInfo)
                    .layout(jfaPipelineLayout);

            LongBuffer pPipeline = stack.mallocLong(1);
            vkCreateComputePipelines(device, 0L, pipeInfo, null, pPipeline);
            jfaPipeline = pPipeline.get(0);

            vkDestroyShaderModule(device, shaderModule, null);

            // Descriptor pool + set
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(2, stack);
            poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1);
            poolSizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1);

            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                    .maxSets(1)
                    .pPoolSizes(poolSizes);

            LongBuffer pPool = stack.mallocLong(1);
            vkCreateDescriptorPool(device, poolInfo, null, pPool);
            jfaDescPool = pPool.get(0);

            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(jfaDescPool)
                    .pSetLayouts(stack.longs(jfaDescSetLayout));

            LongBuffer pSet = stack.mallocLong(1);
            vkAllocateDescriptorSets(device, allocInfo, pSet);
            jfaDescSet = pSet.get(0);

            // Write descriptor set
            VkDescriptorBufferInfo.Buffer bufInfo = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(occupancyBuffer).offset(0).range(VK_WHOLE_SIZE);

            VkDescriptorImageInfo.Buffer imgInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .imageView(sdfImageView)
                    .imageLayout(VK_IMAGE_LAYOUT_GENERAL);

            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
            writes.get(0)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(jfaDescSet).dstBinding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .pBufferInfo(bufInfo);
            writes.get(1)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(jfaDescSet).dstBinding(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .pImageInfo(imgInfo);

            vkUpdateDescriptorSets(device, writes, null);

            LOG.debug("[SDF] JFA compute pipeline created");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  SDF Update Dispatch
    // ═══════════════════════════════════════════════════════════════════════

    private void uploadSectionOccupancy(long sectionKey) {
        // Decode section key → world coordinates
        // Upload 16³ occupancy bits for this section to the staging buffer
        // Then copy to the relevant region of the GPU occupancy buffer
        int sx = decodeSectionX(sectionKey);
        int sy = decodeSectionY(sectionKey);
        int sz = decodeSectionZ(sectionKey);

        LOG.trace("[SDF] Uploading occupancy for section ({}, {}, {})", sx, sy, sz);
        // Actual occupancy data extraction from VoxelSection delegated to caller
    }

    private void dispatchJFA() {
        VkDevice device = BRVulkanDevice.getVkDeviceObj();
        if (device == null || jfaPipeline == 0L) return;

        // JFA requires log2(maxDim) passes with decreasing step sizes
        // Each pass: read SDF, propagate distance via 3D neighbours, write SDF
        // Step sizes: VOLUME_DIM/2, VOLUME_DIM/4, ..., 2, 1
        LOG.trace("[SDF] Dispatching JFA ({} passes)", JFA_PASSES);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Cleanup
    // ═══════════════════════════════════════════════════════════════════════

    public void cleanup() {
        VkDevice device = BRVulkanDevice.getVkDeviceObj();
        if (device == null) return;

        if (jfaPipeline != 0L) { vkDestroyPipeline(device, jfaPipeline, null); jfaPipeline = 0L; }
        if (jfaPipelineLayout != 0L) { vkDestroyPipelineLayout(device, jfaPipelineLayout, null); jfaPipelineLayout = 0L; }
        if (jfaDescPool != 0L) { vkDestroyDescriptorPool(device, jfaDescPool, null); jfaDescPool = 0L; }
        if (jfaDescSetLayout != 0L) { vkDestroyDescriptorSetLayout(device, jfaDescSetLayout, null); jfaDescSetLayout = 0L; }
        if (sdfSampler != 0L) { vkDestroySampler(device, sdfSampler, null); sdfSampler = 0L; }
        if (sdfImageView != 0L) { vkDestroyImageView(device, sdfImageView, null); sdfImageView = 0L; }
        if (sdfImage != 0L) { vkDestroyImage(device, sdfImage, null); sdfImage = 0L; }
        if (sdfMemory != 0L) { vkFreeMemory(device, sdfMemory, null); sdfMemory = 0L; }
        if (occupancyBuffer != 0L) { vkDestroyBuffer(device, occupancyBuffer, null); occupancyBuffer = 0L; }
        if (occupancyMemory != 0L) { vkFreeMemory(device, occupancyMemory, null); occupancyMemory = 0L; }

        initialized = false;
        sdfReady = false;
        LOG.info("[SDF] Volume Manager cleaned up");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Utilities
    // ═══════════════════════════════════════════════════════════════════════

    private static long encodeSectionKey(int sx, int sy, int sz) {
        return ((long) (sx & 0xFFFFF) << 40) | ((long) (sy & 0xFFFFF) << 20) | (sz & 0xFFFFF);
    }

    private static int decodeSectionX(long key) { return (int) ((key >> 40) & 0xFFFFF); }
    private static int decodeSectionY(long key) { return (int) ((key >> 20) & 0xFFFFF); }
    private static int decodeSectionZ(long key) { return (int) (key & 0xFFFFF); }

    private static String loadShaderSource(String resourcePath) {
        try (var is = BRSDFVolumeManager.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                LOG.error("[SDF] Shader resource not found: {}", resourcePath);
                return "";
            }
            return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.error("[SDF] Failed to load shader: {}", resourcePath, e);
            return "";
        }
    }
}
