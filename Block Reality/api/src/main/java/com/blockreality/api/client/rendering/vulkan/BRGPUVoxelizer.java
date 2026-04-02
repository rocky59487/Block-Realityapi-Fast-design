package com.blockreality.api.client.rendering.vulkan;

import com.blockreality.api.client.render.rt.BRVulkanDevice;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.vulkan.VK10.*;

/**
 * GPU 計算著色器體素化（P1-A）。
 *
 * <h3>設計目標</h3>
 * <p>將 {@link com.blockreality.api.client.render.optimization.GreedyMesher} 輸出的
 * AABB 列表在 GPU compute shader 上並行體素化為
 * {@link com.blockreality.api.client.render.optimization.BRSparseVoxelDAG} 相容的
 * uint[] SSBO，取代先前的 CPU 體素化路徑。預期加速比：~10× 以上（RTX 3060 基準）。
 *
 * <h3>管線（2 binding compute shader）</h3>
 * <pre>
 * set 0, b0: AABB 輸入 SSBO（readonly，7 floats/AABB）
 * set 0, b1: 體素輸出 SSBO（uint[]，線性索引 z*gDim^2+y*gDim+x）
 * push constants: aabbCount, gridResolution, originXYZ, voxelSize
 * dispatch: ceil(aabbCount / 64) workgroups × 1 × 1
 * </pre>
 *
 * <h3>執行緒安全</h3>
 * <p>所有靜態方法非執行緒安全；應在 client thread 呼叫（{@code @OnlyIn(Dist.CLIENT)}）。
 *
 * @see com.blockreality.api.client.render.optimization.GreedyMesher
 * @see com.blockreality.api.client.render.optimization.BRSparseVoxelDAG
 * @see VkAccelStructBuilder
 */
@OnlyIn(Dist.CLIENT)
public final class BRGPUVoxelizer {

    private static final Logger LOG = LoggerFactory.getLogger("BR-GPUVoxelizer");

    // ─── 常數 ─────────────────────────────────────────────────────────────────

    /** Compute shader workgroup 大小（local_size_x，與 voxelize.comp.glsl 一致）。 */
    public static final int WORKGROUP_SIZE = 64;

    /** 每個 AABB 的 float 數（minXYZ + maxXYZ + matId = 7）。 */
    public static final int FLOATS_PER_AABB = 7;

    /** 支援的最大體素格點解析度（每軸），對應 resolution=4 × 16 block = 64 voxels/axis。 */
    public static final int MAX_GRID_DIM = 64;

    /** 最大 AABB 數量（staging buffer 上限）。 */
    public static final int MAX_AABB_COUNT = 65536;

    /** Push constants 的 VkShaderStageFlags。 */
    private static final int PUSH_STAGE = VK_SHADER_STAGE_COMPUTE_BIT;

    // ─── Vulkan 資源 handles ──────────────────────────────────────────────────

    private static boolean initialized     = false;
    private static long    dsLayout        = 0L;   // descriptor set layout
    private static long    pipelineLayout  = 0L;
    private static long    pipeline        = 0L;
    private static long    descriptorPool  = 0L;
    private static long    descriptorSet   = 0L;

    /** AABB Staging buffer（HOST_VISIBLE | HOST_COHERENT，從 CPU 寫入）。 */
    private static long    aabbStagingBuf  = 0L;
    private static long    aabbStagingMem  = 0L;
    private static int     aabbStagingCap  = 0;    // 目前容量（AABB 數量）

    /** 最近一次 voxelizeSection 的非空體素計數（近似，即 gridDim³）。 */
    private static int     lastVoxelCount  = 0;

    private BRGPUVoxelizer() {}

    // ─────────────────────────────────────────────────────────────────────────
    //  生命週期
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 初始化 GPU Voxelizer。
     *
     * <p>建立 compute pipeline（從 {@code voxelize.comp.glsl} 編譯）、
     * descriptor set layout、descriptor pool，以及可容納 {@link #MAX_AABB_COUNT}
     * 個 AABB 的 staging buffer。
     *
     * @param vkDevice Vulkan 邏輯裝置 handle（{@link BRVulkanDevice#getVkDevice()}）
     * @return {@code true} 若初始化成功
     */
    public static boolean init(long vkDevice) {
        if (initialized) {
            LOG.warn("[GPUVox] Already initialized");
            return true;
        }
        if (vkDevice == 0L) {
            LOG.warn("[GPUVox] Vulkan device not ready; GPU voxelization disabled");
            return false;
        }

        LOG.info("[GPUVox] Initializing GPU voxelizer…");

        try {
            // 1. Descriptor set layout（2 STORAGE_BUFFER bindings）
            dsLayout = createDSLayout(vkDevice);
            if (dsLayout == 0L) throw new RuntimeException("createDSLayout failed");

            // 2. Pipeline layout（dsLayout + push constants 32 bytes）
            pipelineLayout = createPipelineLayout(vkDevice, dsLayout);
            if (pipelineLayout == 0L) throw new RuntimeException("createPipelineLayout failed");

            // 3. Compute pipeline（voxelize.comp.glsl → SPIR-V）
            pipeline = createComputePipeline(vkDevice, pipelineLayout);
            if (pipeline == 0L) throw new RuntimeException("createComputePipeline failed");

            // 4. Descriptor pool + descriptor set
            descriptorPool = createDescriptorPool(vkDevice);
            if (descriptorPool == 0L) throw new RuntimeException("createDescriptorPool failed");
            descriptorSet = allocateDescriptorSet(vkDevice, descriptorPool, dsLayout);
            if (descriptorSet == 0L) throw new RuntimeException("allocateDescriptorSet failed");

            // 5. AABB Staging buffer（HOST_VISIBLE | HOST_COHERENT）
            allocateAabbStaging(vkDevice, MAX_AABB_COUNT);

            initialized = true;
            LOG.info("[GPUVox] Initialized: pipeline={} dsLayout={}", pipeline, dsLayout);
            return true;

        } catch (Exception e) {
            LOG.error("[GPUVox] init() failed", e);
            cleanup();
            return false;
        }
    }

    /**
     * 釋放所有 GPU 資源。
     */
    public static void cleanup() {
        long dev = BRVulkanDevice.getVkDevice();
        if (dev != 0L) {
            if (aabbStagingBuf != 0L) { BRVulkanDevice.destroyBuffer(dev, aabbStagingBuf); aabbStagingBuf = 0L; }
            if (aabbStagingMem != 0L) { BRVulkanDevice.freeMemory(dev, aabbStagingMem);    aabbStagingMem = 0L; }
            if (descriptorPool != 0L) { BRVulkanDevice.destroyDescriptorPool(dev, descriptorPool); descriptorPool = 0L; }
            if (pipeline       != 0L) { BRVulkanDevice.destroyPipeline(dev, pipeline);     pipeline = 0L; }
            if (pipelineLayout != 0L) { BRVulkanDevice.destroyPipelineLayout(dev, pipelineLayout); pipelineLayout = 0L; }
            if (dsLayout       != 0L) { BRVulkanDevice.destroyDescriptorSetLayout(dev, dsLayout); dsLayout = 0L; }
        }
        descriptorSet  = 0L;
        aabbStagingCap = 0;
        lastVoxelCount = 0;
        initialized    = false;
        LOG.info("[GPUVox] Cleanup complete");
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  體素化 API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 將一個 16×16×16 區段的 AABB 資料在 GPU 上體素化為 3D 格點 SSBO。
     *
     * <p>輸出格式：{@code uint[gridDim³]} 線性陣列，
     * 索引 {@code z*gDim^2 + y*gDim + x}，值為 materialId（0 = 空）。
     *
     * @param sectionX         區段 X 座標（= blockX >> 4）
     * @param sectionZ         區段 Z 座標（= blockZ >> 4）
     * @param aabbData         AABB float 陣列（每個 AABB 佔 7 floats：minXYZ, maxXYZ, matId_float）
     * @param aabbCount        有效 AABB 數量
     * @param resolution       體素解析度（1–4，對應格點 16–64 voxels/axis）
     * @param outputSsboHandle 目標 GPU buffer（VkBuffer，呼叫端負責分配）
     * @return {@code true} 若 compute dispatch 成功提交
     */
    public static boolean voxelizeSection(int sectionX, int sectionZ,
                                          float[] aabbData, int aabbCount,
                                          int resolution, long outputSsboHandle) {
        if (!initialized) {
            LOG.debug("[GPUVox] voxelizeSection skipped — not initialized");
            return false;
        }
        if (aabbData == null || aabbCount <= 0 || outputSsboHandle == 0L) {
            LOG.warn("[GPUVox] voxelizeSection: invalid args (aabbCount={}, output={})",
                aabbCount, outputSsboHandle);
            return false;
        }
        resolution = Math.max(1, Math.min(4, resolution));
        int gridDim = resolution * 16;  // 16, 32, 48, 或 64

        long device = BRVulkanDevice.getVkDevice();
        if (device == 0L) return false;

        try {
            // ── 1. 確保 AABB Staging buffer 容量足夠 ─────────────────────────
            if (aabbCount > aabbStagingCap) {
                freeAabbStaging(device);
                int newCap = Math.min(Math.max(aabbCount, aabbStagingCap * 2), MAX_AABB_COUNT);
                allocateAabbStaging(device, newCap);
                if (aabbStagingBuf == 0L) {
                    LOG.error("[GPUVox] AABB staging realloc failed");
                    return false;
                }
            }

            // ── 2. 上傳 AABB 資料到 staging buffer ───────────────────────────
            int floatCount = Math.min(aabbCount * FLOATS_PER_AABB, aabbData.length);
            uploadAabbData(device, aabbData, floatCount);

            // ── 3. 更新 Descriptor Set（binding 0=AABB staging, binding 1=output SSBO）──
            updateDescriptorSet(device, descriptorSet,
                aabbStagingBuf, (long) floatCount * Float.BYTES,
                outputSsboHandle, (long) gridDim * gridDim * gridDim * Integer.BYTES);

            // ── 4. 計算區段世界原點（block 座標）──────────────────────────────
            float originX = (float)(sectionX << 4);
            float originY = 0.0f;  // Y origin（全區段從底部開始）
            float originZ = (float)(sectionZ << 4);
            float voxelSize = 1.0f / resolution;

            // ── 5. dispatch ───────────────────────────────────────────────────
            long cmd = BRVulkanDevice.beginSingleTimeCommands(device);
            if (cmd == 0L) { LOG.error("[GPUVox] beginSingleTimeCommands failed"); return false; }

            VkDevice vkDev = BRVulkanDevice.getVkDeviceObj();
            if (vkDev == null) {
                BRVulkanDevice.endSingleTimeCommands(device, cmd);
                return false;
            }
            VkCommandBuffer cb = new VkCommandBuffer(cmd, vkDev);

            // 5a. 清零輸出 SSBO（呼叫端 outputSsboHandle 必須含 TRANSFER_DST_BIT）
            long outputBytes = (long) gridDim * gridDim * gridDim * Integer.BYTES;
            vkCmdFillBuffer(cb, outputSsboHandle, 0L, outputBytes, 0);

            // 5b. Barrier: TRANSFER_WRITE → SHADER_WRITE（voxelization）
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkBufferMemoryBarrier.Buffer preBarrier = VkBufferMemoryBarrier.calloc(1, stack);
                preBarrier.get(0)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                    .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .buffer(outputSsboHandle).offset(0L).size(outputBytes);
                vkCmdPipelineBarrier(cb,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    0, null, preBarrier, null);

                // 5c. Bind pipeline + descriptors
                vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
                vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE,
                    pipelineLayout, 0, stack.longs(descriptorSet), null);

                // 5d. Push constants（32 bytes）
                java.nio.ByteBuffer pc = stack.malloc(32);
                pc.putInt(0,  aabbCount);
                pc.putInt(4,  gridDim);
                pc.putFloat(8,  originX);
                pc.putFloat(12, originY);
                pc.putFloat(16, originZ);
                pc.putFloat(20, voxelSize);
                pc.putInt(24, 0);  // _pad0
                pc.putInt(28, 0);  // _pad1
                vkCmdPushConstants(cb, pipelineLayout, PUSH_STAGE, 0, pc);

                // 5e. Dispatch
                int groupsX = (aabbCount + WORKGROUP_SIZE - 1) / WORKGROUP_SIZE;
                vkCmdDispatch(cb, groupsX, 1, 1);

                // 5f. Barrier: SHADER_WRITE → SHADER_READ（後續 DAG builder）
                VkBufferMemoryBarrier.Buffer postBarrier = VkBufferMemoryBarrier.calloc(1, stack);
                postBarrier.get(0)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .buffer(outputSsboHandle).offset(0L).size(outputBytes);
                vkCmdPipelineBarrier(cb,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    0, null, postBarrier, null);
            }

            BRVulkanDevice.endSingleTimeCommands(device, cmd);

            lastVoxelCount = gridDim * gridDim * gridDim;
            LOG.debug("[GPUVox] voxelizeSection({},{}) resolution={} gridDim={} aabb={} groups={}",
                sectionX, sectionZ, resolution, gridDim, aabbCount,
                (aabbCount + WORKGROUP_SIZE - 1) / WORKGROUP_SIZE);
            return true;

        } catch (Exception e) {
            LOG.error("[GPUVox] voxelizeSection failed", e);
            return false;
        }
    }

    /**
     * 查詢上一次 {@link #voxelizeSection} 的格點總體素數（= gridDim³，含空氣）。
     * 可用於 LOD 品質評估。
     *
     * @return 格點體素總數；0 表示尚未執行或初始化失敗
     */
    public static int getLastVoxelCount() { return lastVoxelCount; }

    /** @return {@code true} 若 GPU Voxelizer 已成功初始化 */
    public static boolean isInitialized() { return initialized; }

    // ─────────────────────────────────────────────────────────────────────────
    //  Vulkan 資源建立（私有）
    // ─────────────────────────────────────────────────────────────────────────

    /** 建立 2-binding descriptor set layout（SSBO AABB in, SSBO voxel out）。 */
    private static long createDSLayout(long device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(2, stack);
            // b0: AABB input SSBO
            b.get(0).binding(0)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1)
                .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            // b1: voxel output SSBO
            b.get(1).binding(1)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1)
                .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);

            VkDevice vkDev = BRVulkanDevice.getVkDeviceObj();
            if (vkDev == null) return 0L;
            LongBuffer pLayout = stack.mallocLong(1);
            int r = vkCreateDescriptorSetLayout(vkDev,
                VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .pBindings(b),
                null, pLayout);
            if (r != VK_SUCCESS) { LOG.error("[GPUVox] vkCreateDescriptorSetLayout failed: {}", r); return 0L; }
            return pLayout.get(0);
        }
    }

    /** 建立 pipeline layout（1 descriptor set + 32-byte push constants）。 */
    private static long createPipelineLayout(long device, long dsLayout) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDevice vkDev = BRVulkanDevice.getVkDeviceObj();
            if (vkDev == null) return 0L;

            // Push constant range: 32 bytes for the 8-field PushConstants struct
            VkPushConstantRange.Buffer pcRange = VkPushConstantRange.calloc(1, stack);
            pcRange.get(0).stageFlags(PUSH_STAGE).offset(0).size(32);

            LongBuffer pLayout = stack.mallocLong(1);
            int r = vkCreatePipelineLayout(vkDev,
                VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(stack.longs(dsLayout))
                    .pPushConstantRanges(pcRange),
                null, pLayout);
            if (r != VK_SUCCESS) { LOG.error("[GPUVox] vkCreatePipelineLayout failed: {}", r); return 0L; }
            return pLayout.get(0);
        }
    }

    /**
     * 編譯 {@code voxelize.comp.glsl} 並建立 VkPipeline。
     * 與 {@link VkRTAO} 的 createComputePipeline 同一模式。
     */
    private static long createComputePipeline(long device, long layout) {
        String glsl = loadShaderSource("compute/voxelize.comp.glsl");
        if (glsl == null) {
            LOG.warn("[GPUVox] voxelize.comp.glsl not found; GPU voxelization disabled");
            return 0L;
        }
        byte[] spv = BRVulkanDevice.compileGLSLtoSPIRV(glsl, "voxelize.comp");
        if (spv.length == 0) {
            LOG.error("[GPUVox] voxelize.comp.glsl compile failed");
            return 0L;
        }
        long shaderModule = BRVulkanDevice.createShaderModule(device, spv);
        if (shaderModule == 0L) return 0L;

        VkDevice vkDev = BRVulkanDevice.getVkDeviceObj();
        if (vkDev == null) { BRVulkanDevice.destroyShaderModule(device, shaderModule); return 0L; }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pPipeline = stack.mallocLong(1);

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

            int r = vkCreateComputePipelines(vkDev, VK_NULL_HANDLE, ci, null, pPipeline);
            BRVulkanDevice.destroyShaderModule(device, shaderModule);
            if (r != VK_SUCCESS) {
                LOG.error("[GPUVox] vkCreateComputePipelines failed: {}", r);
                return 0L;
            }
            LOG.info("[GPUVox] compute pipeline created: {}", pPipeline.get(0));
            return pPipeline.get(0);
        }
    }

    /** 建立 descriptor pool（2 STORAGE_BUFFER，maxSets=1）。 */
    private static long createDescriptorPool(long device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDevice vkDev = BRVulkanDevice.getVkDeviceObj();
            if (vkDev == null) return 0L;

            VkDescriptorPoolSize.Buffer poolSize = VkDescriptorPoolSize.calloc(1, stack);
            poolSize.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(2);

            LongBuffer pPool = stack.mallocLong(1);
            int r = vkCreateDescriptorPool(vkDev,
                VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                    .maxSets(1)
                    .pPoolSizes(poolSize),
                null, pPool);
            if (r != VK_SUCCESS) { LOG.error("[GPUVox] vkCreateDescriptorPool failed: {}", r); return 0L; }
            return pPool.get(0);
        }
    }

    /** 從 pool 分配一個 descriptor set。 */
    private static long allocateDescriptorSet(long device, long pool, long layout) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDevice vkDev = BRVulkanDevice.getVkDeviceObj();
            if (vkDev == null) return 0L;
            LongBuffer pSet = stack.mallocLong(1);
            int r = vkAllocateDescriptorSets(vkDev,
                VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(pool)
                    .pSetLayouts(stack.longs(layout)),
                pSet);
            if (r != VK_SUCCESS) { LOG.error("[GPUVox] vkAllocateDescriptorSets failed: {}", r); return 0L; }
            return pSet.get(0);
        }
    }

    /** 更新 descriptor set 的兩個 SSBO binding。 */
    private static void updateDescriptorSet(long device, long set,
                                             long aabbBuf, long aabbBytes,
                                             long voxelBuf, long voxelBytes) {
        VkDevice vkDev = BRVulkanDevice.getVkDeviceObj();
        if (vkDev == null || set == 0L) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Binding 0: AABB SSBO
            VkDescriptorBufferInfo.Buffer aabbInfo = VkDescriptorBufferInfo.calloc(1, stack);
            aabbInfo.get(0).buffer(aabbBuf).offset(0L).range(aabbBytes);

            // Binding 1: Voxel output SSBO
            VkDescriptorBufferInfo.Buffer voxelInfo = VkDescriptorBufferInfo.calloc(1, stack);
            voxelInfo.get(0).buffer(voxelBuf).offset(0L).range(voxelBytes);

            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
            writes.get(0)
                .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .dstSet(set).dstBinding(0).descriptorCount(1)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .pBufferInfo(aabbInfo);
            writes.get(1)
                .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .dstSet(set).dstBinding(1).descriptorCount(1)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .pBufferInfo(voxelInfo);

            vkUpdateDescriptorSets(vkDev, writes, null);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  AABB Staging buffer 工具
    // ─────────────────────────────────────────────────────────────────────────

    private static void allocateAabbStaging(long device, int aabbCap) {
        long bytes = (long) aabbCap * FLOATS_PER_AABB * Float.BYTES;
        aabbStagingBuf = BRVulkanDevice.createBuffer(device, bytes,
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
        if (aabbStagingBuf == 0L) return;
        aabbStagingMem = BRVulkanDevice.allocateAndBindBuffer(device, aabbStagingBuf,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        if (aabbStagingMem == 0L) {
            BRVulkanDevice.destroyBuffer(device, aabbStagingBuf);
            aabbStagingBuf = 0L;
        } else {
            aabbStagingCap = aabbCap;
            LOG.debug("[GPUVox] AABB staging: cap={} bytes={}", aabbCap, bytes);
        }
    }

    private static void freeAabbStaging(long device) {
        if (aabbStagingBuf != 0L) { BRVulkanDevice.destroyBuffer(device, aabbStagingBuf); aabbStagingBuf = 0L; }
        if (aabbStagingMem != 0L) { BRVulkanDevice.freeMemory(device, aabbStagingMem);    aabbStagingMem = 0L; }
        aabbStagingCap = 0;
    }

    /** 將 CPU float[] 映射並寫入 AABB staging buffer。 */
    private static void uploadAabbData(long device, float[] data, int floatCount) {
        VkDevice vkDev = BRVulkanDevice.getVkDeviceObj();
        if (vkDev == null || aabbStagingMem == 0L) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.PointerBuffer pPtr = stack.mallocPointer(1);
            long bytes = (long) floatCount * Float.BYTES;
            int r = vkMapMemory(vkDev, aabbStagingMem, 0L, bytes, 0, pPtr);
            if (r != VK_SUCCESS) {
                LOG.error("[GPUVox] vkMapMemory for AABB staging failed: {}", r);
                return;
            }
            long addr = pPtr.get(0);
            for (int i = 0; i < floatCount; i++) {
                MemoryUtil.memPutFloat(addr + (long) i * Float.BYTES, data[i]);
            }
            vkUnmapMemory(vkDev, aabbStagingMem);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Shader 資源載入
    // ─────────────────────────────────────────────────────────────────────────

    private static String loadShaderSource(String resourcePath) {
        try (InputStream is = BRGPUVoxelizer.class.getResourceAsStream(
                "/assets/blockreality/shaders/" + resourcePath)) {
            if (is == null) return null;
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.error("[GPUVox] Failed to load shader: {}", resourcePath, e);
            return null;
        }
    }
}
