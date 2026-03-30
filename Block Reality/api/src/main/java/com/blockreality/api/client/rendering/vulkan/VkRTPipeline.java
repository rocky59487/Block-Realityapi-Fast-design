package com.blockreality.api.client.rendering.vulkan;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * Vulkan Ray Tracing Pipeline 管理器（Phase 2-C）。
 *
 * 移植來源：Radiance MCVR RayTracingPipeline + Sascha Willems raytracingbasic
 *
 * 管理：
 *   - VkPipeline (ray tracing type)
 *   - VkPipelineLayout
 *   - Descriptor set layout（binding 0=TLAS, 1=storageImage, 2=UBO）
 *   - Shader Binding Table (SBT)：raygen | miss_primary | miss_shadow | hit_opaque
 *
 * SBT 對齊規則（Sascha Willems 公式）：
 *   stride = align(handleSize, shaderGroupHandleAlignment)
 *   region size = align(stride, shaderGroupBaseAlignment)
 *
 * @see VkContext
 * @see VkAccelStructBuilder
 * @see VkRTShaderPack
 */
@OnlyIn(Dist.CLIENT)
public class VkRTPipeline {

    private static final Logger LOG = LoggerFactory.getLogger("BR-VkRTPipeline");

    // SBT shader group indices（對應 create() 中的 groups 順序）
    public static final int GROUP_RAYGEN       = 0;
    public static final int GROUP_MISS_PRIMARY = 1;
    public static final int GROUP_MISS_SHADOW  = 2;
    public static final int GROUP_HIT_OPAQUE   = 3;
    public static final int SHADER_GROUP_COUNT = 4;

    /** 最大 RT 遞歸深度（raygen→chit→shadow ray = 2） */
    private static final int MAX_RAY_RECURSION = 2;

    private final VkContext      context;
    private final VkMemoryAllocator allocator;
    private final VkRTShaderPack shaderPack;

    // ─── Vulkan handles ───
    private long pipeline              = VK_NULL_HANDLE;
    private long pipelineLayout        = VK_NULL_HANDLE;
    private long descriptorSetLayout   = VK_NULL_HANDLE;
    private long descriptorPool        = VK_NULL_HANDLE;
    private long descriptorSet         = VK_NULL_HANDLE;

    // ─── SBT ───
    private long sbtBuffer             = VK_NULL_HANDLE;
    private long sbtAllocation         = 0;
    private long sbtRaygenAddr         = 0;
    private long sbtMissAddr           = 0;
    private long sbtHitAddr            = 0;
    private int  sbtHandleSize         = 0;
    private int  sbtStride             = 0;

    public VkRTPipeline(VkContext context, VkMemoryAllocator allocator,
                        VkRTShaderPack shaderPack) {
        this.context    = context;
        this.allocator  = allocator;
        this.shaderPack = shaderPack;
    }

    // ═══ 建立 ═══

    /**
     * 建立 RT pipeline、descriptor layout、SBT。
     *
     * 前提：VkRTShaderPack.loadAll() 必須先成功執行。
     *
     * @return true 若建立成功
     */
    public boolean create() {
        if (pipeline != VK_NULL_HANDLE) {
            LOG.warn("VkRTPipeline already created");
            return true;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (!createDescriptorSetLayout(stack)) return false;
            if (!createPipelineLayout(stack))      return false;
            if (!createRTPipeline(stack))           return false;
            if (!createDescriptorPool(stack))       return false;
            if (!allocateDescriptorSet(stack))      return false;
            if (!buildSBT(stack))                   return false;
        } catch (Exception e) {
            LOG.error("VkRTPipeline.create() failed: {}", e.getMessage(), e);
            cleanup();
            return false;
        }

        LOG.info("VkRTPipeline created — {} shader groups", SHADER_GROUP_COUNT);
        return true;
    }

    // ─── Step 1: Descriptor Set Layout ───

    private boolean createDescriptorSetLayout(MemoryStack stack) {
        // binding 0: TLAS（raygen + chit）
        // binding 1: storage image 輸出（raygen）
        // binding 2: camera UBO（raygen + chit）
        // binding 3: vertex SSBO（chit：頂點資料 for 插值）
        // binding 4: stress SSBO（chit：應力熱圖資料）
        VkDescriptorSetLayoutBinding.Buffer bindings =
            VkDescriptorSetLayoutBinding.calloc(5, stack);

        bindings.get(0)
            .binding(0)
            .descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
            .descriptorCount(1)
            .stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_KHR | VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR);

        bindings.get(1)
            .binding(1)
            .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
            .descriptorCount(1)
            .stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_KHR);

        bindings.get(2)
            .binding(2)
            .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
            .descriptorCount(1)
            .stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_KHR | VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR);

        bindings.get(3)
            .binding(3)
            .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
            .descriptorCount(1)
            .stageFlags(VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR);

        bindings.get(4)
            .binding(4)
            .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
            .descriptorCount(1)
            .stageFlags(VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR);

        VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
            .pBindings(bindings);

        LongBuffer pLayout = stack.mallocLong(1);
        int result = vkCreateDescriptorSetLayout(context.getDevice(), layoutInfo, null, pLayout);
        if (result != VK_SUCCESS) {
            LOG.error("vkCreateDescriptorSetLayout failed: {}", result);
            return false;
        }
        descriptorSetLayout = pLayout.get(0);
        return true;
    }

    // ─── Step 2: Pipeline Layout ───

    private boolean createPipelineLayout(MemoryStack stack) {
        VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
            .pSetLayouts(stack.longs(descriptorSetLayout));

        LongBuffer pLayout = stack.mallocLong(1);
        int result = vkCreatePipelineLayout(context.getDevice(), layoutInfo, null, pLayout);
        if (result != VK_SUCCESS) {
            LOG.error("vkCreatePipelineLayout failed: {}", result);
            return false;
        }
        pipelineLayout = pLayout.get(0);
        return true;
    }

    // ─── Step 3: RT Pipeline ───

    private boolean createRTPipeline(MemoryStack stack) {
        VkDevice device = context.getDevice();

        // 取得 shader modules
        long raygenMod = shaderPack.getModule("raygen");
        long missMod   = shaderPack.getModule("miss");
        long shadowMod = shaderPack.getModule("shadow");
        long chitMod   = shaderPack.getModule("closesthit");

        if (raygenMod == 0 || missMod == 0 || chitMod == 0) {
            LOG.error("Missing required shader modules (raygen={}, miss={}, chit={})",
                raygenMod, missMod, chitMod);
            return false;
        }

        // Shader stages（順序對應 group index）
        int stageCount = (shadowMod != 0) ? 4 : 3;
        VkPipelineShaderStageCreateInfo.Buffer stages =
            VkPipelineShaderStageCreateInfo.calloc(stageCount, stack);

        stages.get(0)  // 0 = raygen
            .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
            .stage(VK_SHADER_STAGE_RAYGEN_BIT_KHR)
            .module(raygenMod)
            .pName(stack.UTF8("main"));

        stages.get(1)  // 1 = miss_primary
            .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
            .stage(VK_SHADER_STAGE_MISS_BIT_KHR)
            .module(missMod)
            .pName(stack.UTF8("main"));

        if (shadowMod != 0) {
            stages.get(2)  // 2 = miss_shadow
                .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                .stage(VK_SHADER_STAGE_MISS_BIT_KHR)
                .module(shadowMod)
                .pName(stack.UTF8("main"));

            stages.get(3)  // 3 = closest_hit
                .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                .stage(VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR)
                .module(chitMod)
                .pName(stack.UTF8("main"));
        } else {
            stages.get(2)  // 2 = closest_hit（fallback: shadow not present）
                .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                .stage(VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR)
                .module(chitMod)
                .pName(stack.UTF8("main"));
        }

        // Shader groups
        // GROUP_RAYGEN(0)       → GENERAL group（raygen stage 0）
        // GROUP_MISS_PRIMARY(1) → GENERAL group（miss stage 1）
        // GROUP_MISS_SHADOW(2)  → GENERAL group（miss/shadow stage 2）
        // GROUP_HIT_OPAQUE(3)   → TRIANGLES_HIT group（chit stage 3）
        VkRayTracingShaderGroupCreateInfoKHR.Buffer groups =
            VkRayTracingShaderGroupCreateInfoKHR.calloc(SHADER_GROUP_COUNT, stack);

        for (int i = 0; i < SHADER_GROUP_COUNT; i++) {
            groups.get(i)
                .sType(VK_STRUCTURE_TYPE_RAY_TRACING_SHADER_GROUP_CREATE_INFO_KHR)
                .generalShader(VK_SHADER_UNUSED_KHR)
                .closestHitShader(VK_SHADER_UNUSED_KHR)
                .anyHitShader(VK_SHADER_UNUSED_KHR)
                .intersectionShader(VK_SHADER_UNUSED_KHR);
        }

        // raygen: GENERAL, generalShader = stage 0
        groups.get(GROUP_RAYGEN)
            .type(VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR)
            .generalShader(0);

        // miss primary: GENERAL, generalShader = stage 1
        groups.get(GROUP_MISS_PRIMARY)
            .type(VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR)
            .generalShader(1);

        // miss shadow: GENERAL, generalShader = stage 2
        groups.get(GROUP_MISS_SHADOW)
            .type(VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR)
            .generalShader(shadowMod != 0 ? 2 : 1); // fallback to miss if no shadow shader

        // hit opaque: TRIANGLES, closestHitShader = stage 3 (or 2 without shadow)
        groups.get(GROUP_HIT_OPAQUE)
            .type(VK_RAY_TRACING_SHADER_GROUP_TYPE_TRIANGLES_HIT_GROUP_KHR)
            .closestHitShader(shadowMod != 0 ? 3 : 2);

        // Create RT pipeline
        VkRayTracingPipelineCreateInfoKHR.Buffer pipelineInfo =
            VkRayTracingPipelineCreateInfoKHR.calloc(1, stack);
        pipelineInfo.get(0)
            .sType(VK_STRUCTURE_TYPE_RAY_TRACING_PIPELINE_CREATE_INFO_KHR)
            .pStages(stages)
            .pGroups(groups)
            .maxPipelineRayRecursionDepth(MAX_RAY_RECURSION)
            .layout(pipelineLayout);

        LongBuffer pPipeline = stack.mallocLong(1);
        int result = vkCreateRayTracingPipelinesKHR(
            device, VK_NULL_HANDLE, VK_NULL_HANDLE, pipelineInfo, null, pPipeline);
        if (result != VK_SUCCESS) {
            LOG.error("vkCreateRayTracingPipelinesKHR failed: {}", result);
            return false;
        }
        pipeline = pPipeline.get(0);
        return true;
    }

    // ─── Step 4: Descriptor Pool ───

    private boolean createDescriptorPool(MemoryStack stack) {
        VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(4, stack);
        poolSizes.get(0)
            .type(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
            .descriptorCount(1);
        poolSizes.get(1)
            .type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
            .descriptorCount(1);
        poolSizes.get(2)
            .type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
            .descriptorCount(1);
        poolSizes.get(3)
            .type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
            .descriptorCount(2);  // vertex SSBO + stress SSBO

        VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
            .pPoolSizes(poolSizes)
            .maxSets(1);

        LongBuffer pPool = stack.mallocLong(1);
        int result = vkCreateDescriptorPool(context.getDevice(), poolInfo, null, pPool);
        if (result != VK_SUCCESS) {
            LOG.error("vkCreateDescriptorPool failed: {}", result);
            return false;
        }
        descriptorPool = pPool.get(0);
        return true;
    }

    // ─── Step 5: Descriptor Set ───

    private boolean allocateDescriptorSet(MemoryStack stack) {
        LongBuffer pLayouts = stack.longs(descriptorSetLayout);
        VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
            .descriptorPool(descriptorPool)
            .descriptorSetCount(1)
            .pSetLayouts(pLayouts);

        LongBuffer pSet = stack.mallocLong(1);
        int result = vkAllocateDescriptorSets(context.getDevice(), allocInfo, pSet);
        if (result != VK_SUCCESS) {
            LOG.error("vkAllocateDescriptorSets failed: {}", result);
            return false;
        }
        descriptorSet = pSet.get(0);
        return true;
    }

    // ─── Step 6: SBT 組裝（Sascha Willems 公式） ───

    private boolean buildSBT(MemoryStack stack) {
        VkDevice device = context.getDevice();

        // 查詢 RT pipeline properties（取得 handleSize 和 alignment）
        VkPhysicalDeviceRayTracingPipelinePropertiesKHR rtProps =
            VkPhysicalDeviceRayTracingPipelinePropertiesKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_PROPERTIES_KHR);

        VkPhysicalDeviceProperties2 props2 = VkPhysicalDeviceProperties2.calloc(stack)
            .sType(VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2)
            .pNext(rtProps.address());
        VK11.vkGetPhysicalDeviceProperties2(context.getPhysicalDevice(), props2);

        int handleSize      = rtProps.shaderGroupHandleSize();        // 通常 32 bytes
        int handleAlignment = rtProps.shaderGroupHandleAlignment();   // 通常 32 bytes
        int baseAlignment   = rtProps.shaderGroupBaseAlignment();     // 通常 64 bytes

        // SBT stride = align(handleSize, handleAlignment)
        sbtHandleSize = handleSize;
        sbtStride     = alignedSize(handleSize, handleAlignment);

        // 每個 region 大小 = align(stride, baseAlignment)（每個 region 只有 1 handle）
        int regionSize = alignedSize(sbtStride, baseAlignment);

        // 總 SBT buffer 大小 = 4 regions（raygen + miss_primary + miss_shadow + hit）
        long totalSize = (long) regionSize * SHADER_GROUP_COUNT;

        // 取得所有 group handles
        ByteBuffer handles = org.lwjgl.system.MemoryUtil.memAlloc(handleSize * SHADER_GROUP_COUNT);
        try {
            int result = vkGetRayTracingShaderGroupHandlesKHR(
                device, pipeline, 0, SHADER_GROUP_COUNT, handles);
            if (result != VK_SUCCESS) {
                LOG.error("vkGetRayTracingShaderGroupHandlesKHR failed: {}", result);
                return false;
            }

            // 分配 host-visible SBT buffer（需要 shader device address）
            long[] sbtBuf = allocator.allocateDeviceBuffer(totalSize,
                VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR |
                VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT |
                VK_BUFFER_USAGE_TRANSFER_DST_BIT);
            if (sbtBuf == null) {
                LOG.error("SBT buffer allocation failed");
                return false;
            }
            sbtBuffer     = sbtBuf[0];
            sbtAllocation = sbtBuf[1];

            // 用 staging 寫入 SBT（handles 按對齊排列）
            ByteBuffer sbtData = org.lwjgl.system.MemoryUtil.memAlloc((int) totalSize);
            try {
                sbtData.clear();
                for (int g = 0; g < SHADER_GROUP_COUNT; g++) {
                    // handles 中第 g 個 handle 的起始位置
                    handles.position(g * handleSize);
                    handles.limit(g * handleSize + handleSize);

                    // 在 sbtData 中按 regionSize 對齊寫入
                    sbtData.position(g * regionSize);
                    sbtData.put(handles.slice());
                }
                sbtData.rewind();

                byte[] bytes = new byte[(int) totalSize];
                sbtData.get(bytes);
                long[] staging = allocator.allocateStagingBuffer(totalSize);
                if (staging != null) {
                    allocator.writeToBuffer(staging[1], bytes);
                    // copy via one-time cmd（重用 stack）
                    try (MemoryStack s2 = MemoryStack.stackPush()) {
                        VkDevice dev2 = device;
                        long stagingHandle = staging[0];
                        long dstHandle     = sbtBuffer;
                        long copySize      = totalSize;
                        // inline copy: allocate+submit+free
                        submitStagingCopy(s2, dev2, stagingHandle, dstHandle, copySize);
                    }
                    allocator.freeBuffer(staging[0]);
                }
            } finally {
                org.lwjgl.system.MemoryUtil.memFree(sbtData);
            }

        } finally {
            org.lwjgl.system.MemoryUtil.memFree(handles);
        }

        // 計算各 region 的 device address
        VkBufferDeviceAddressInfo addrInfo = VkBufferDeviceAddressInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_BUFFER_DEVICE_ADDRESS_INFO)
            .buffer(sbtBuffer);
        long sbtBaseAddr = vkGetBufferDeviceAddress(device, addrInfo);

        sbtRaygenAddr = sbtBaseAddr + (long) GROUP_RAYGEN       * regionSize;
        sbtMissAddr   = sbtBaseAddr + (long) GROUP_MISS_PRIMARY * regionSize;
        sbtHitAddr    = sbtBaseAddr + (long) GROUP_HIT_OPAQUE   * regionSize;

        LOG.info("SBT built: handleSize={}, stride={}, regionSize={}, totalSize={} bytes",
            handleSize, sbtStride, regionSize, totalSize);
        return true;
    }

    private void submitStagingCopy(MemoryStack stack, VkDevice device,
                                    long src, long dst, long size) {
        long pool = context.getCommandPool();

        VkCommandBufferAllocateInfo ai = VkCommandBufferAllocateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
            .commandPool(pool)
            .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
            .commandBufferCount(1);

        org.lwjgl.PointerBuffer pCmd = stack.mallocPointer(1);
        vkAllocateCommandBuffers(device, ai, pCmd);
        VkCommandBuffer cmd = new VkCommandBuffer(pCmd.get(0), device);

        VkCommandBufferBeginInfo bi = VkCommandBufferBeginInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
            .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
        vkBeginCommandBuffer(cmd, bi);

        VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack);
        region.get(0).size(size);
        vkCmdCopyBuffer(cmd, src, dst, region);

        vkEndCommandBuffer(cmd);

        LongBuffer pFence = stack.mallocLong(1);
        vkCreateFence(device,
            VkFenceCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO),
            null, pFence);
        vkQueueSubmit(context.getGraphicsQueue(),
            VkSubmitInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                .pCommandBuffers(pCmd), pFence.get(0));
        vkWaitForFences(device, pFence, true, Long.MAX_VALUE);
        vkDestroyFence(device, pFence.get(0), null);
        vkFreeCommandBuffers(device, pool, pCmd);
    }

    // ═══ 錄製 trace rays ═══

    /**
     * 更新 descriptor set 並錄製 vkCmdTraceRaysKHR。
     *
     * @param commandBuffer   VkCommandBuffer handle
     * @param tlasHandle      TLAS handle（VkAccelerationStructureKHR）
     * @param outputImageView VkImageView（storage image, GENERAL layout）
     * @param uboBuffer       VkBuffer（camera UBO）
     * @param vertexBuffer    VkBuffer（vertex SSBO for chit interpolation）
     * @param stressBuffer    VkBuffer（stress SSBO for heatmap, 0 = disabled）
     * @param width           輸出影像寬度
     * @param height          輸出影像高度
     */
    public void recordTraceRays(long commandBuffer, long tlasHandle,
                                 long outputImageView, long uboBuffer,
                                 long vertexBuffer, long stressBuffer,
                                 int width, int height) {
        if (!isCreated()) return;

        VkCommandBuffer cmd = new VkCommandBuffer(commandBuffer, context.getDevice());

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 更新 descriptor set
            updateDescriptors(stack, tlasHandle, outputImageView, uboBuffer,
                              vertexBuffer, stressBuffer);

            // Bind pipeline
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR, pipeline);

            // Bind descriptor sets
            vkCmdBindDescriptorSets(cmd,
                VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR,
                pipelineLayout, 0,
                stack.longs(descriptorSet), null);

            // SBT 各 region（stride 只需包含 1 個 handle 的 regionSize）
            int regionSize = alignedSize(sbtStride,
                rtProps_baseAlignment(stack)); // lazy query

            VkStridedDeviceAddressRegionKHR raygenRegion = VkStridedDeviceAddressRegionKHR.calloc(stack)
                .deviceAddress(sbtRaygenAddr)
                .stride(regionSize)
                .size(regionSize);

            // miss region 包含 2 entries（primary + shadow）
            VkStridedDeviceAddressRegionKHR missRegion = VkStridedDeviceAddressRegionKHR.calloc(stack)
                .deviceAddress(sbtMissAddr)
                .stride(sbtStride)
                .size((long) sbtStride * 2); // 2 miss shaders

            VkStridedDeviceAddressRegionKHR hitRegion = VkStridedDeviceAddressRegionKHR.calloc(stack)
                .deviceAddress(sbtHitAddr)
                .stride(sbtStride)
                .size(sbtStride);

            VkStridedDeviceAddressRegionKHR callableRegion =
                VkStridedDeviceAddressRegionKHR.calloc(stack); // empty

            vkCmdTraceRaysKHR(cmd,
                raygenRegion, missRegion, hitRegion, callableRegion,
                width, height, 1);
        }
    }

    /** 更新 descriptor set bindings（5 bindings：TLAS, image, UBO, vertSSBO, stressSSBO） */
    private void updateDescriptors(MemoryStack stack, long tlasHandle,
                                    long outputImageView, long uboBuffer,
                                    long vertexBuffer, long stressBuffer) {
        VkDevice device = context.getDevice();

        // binding 0: TLAS
        VkWriteDescriptorSetAccelerationStructureKHR tlasWrite =
            VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_ACCELERATION_STRUCTURE_KHR)
                .pAccelerationStructures(stack.longs(tlasHandle));

        // binding 1: storage image
        VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack);
        imageInfo.get(0).imageView(outputImageView).imageLayout(VK_IMAGE_LAYOUT_GENERAL);

        // binding 2: camera UBO
        VkDescriptorBufferInfo.Buffer uboInfo = VkDescriptorBufferInfo.calloc(1, stack);
        uboInfo.get(0).buffer(uboBuffer).offset(0).range(VK_WHOLE_SIZE);

        // binding 3: vertex SSBO
        VkDescriptorBufferInfo.Buffer vertInfo = VkDescriptorBufferInfo.calloc(1, stack);
        long safeVert = vertexBuffer != 0 ? vertexBuffer : uboBuffer; // fallback if not provided
        vertInfo.get(0).buffer(safeVert).offset(0).range(VK_WHOLE_SIZE);

        // binding 4: stress SSBO
        VkDescriptorBufferInfo.Buffer stressInfo = VkDescriptorBufferInfo.calloc(1, stack);
        long safeStress = stressBuffer != 0 ? stressBuffer : uboBuffer; // fallback
        stressInfo.get(0).buffer(safeStress).offset(0).range(VK_WHOLE_SIZE);

        VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(5, stack);

        writes.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
            .dstSet(descriptorSet).dstBinding(0)
            .descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
            .descriptorCount(1).pNext(tlasWrite.address());

        writes.get(1).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
            .dstSet(descriptorSet).dstBinding(1)
            .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(imageInfo);

        writes.get(2).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
            .dstSet(descriptorSet).dstBinding(2)
            .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).pBufferInfo(uboInfo);

        writes.get(3).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
            .dstSet(descriptorSet).dstBinding(3)
            .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(vertInfo);

        writes.get(4).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
            .dstSet(descriptorSet).dstBinding(4)
            .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(stressInfo);

        vkUpdateDescriptorSets(device, writes, null);
    }

    /** 查詢 RT pipeline properties 的 baseAlignment（用於 traceRays SBT region） */
    private int rtProps_baseAlignment(MemoryStack stack) {
        VkPhysicalDeviceRayTracingPipelinePropertiesKHR props =
            VkPhysicalDeviceRayTracingPipelinePropertiesKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_PROPERTIES_KHR);
        VkPhysicalDeviceProperties2 p2 = VkPhysicalDeviceProperties2.calloc(stack)
            .sType(VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2)
            .pNext(props.address());
        VK11.vkGetPhysicalDeviceProperties2(context.getPhysicalDevice(), p2);
        return props.shaderGroupBaseAlignment();
    }

    // ═══ 銷毀 ═══

    /**
     * 銷毀 pipeline 和所有相關資源。
     */
    public void cleanup() {
        VkDevice device = context.getDevice();
        if (device == null) return;

        if (sbtBuffer != VK_NULL_HANDLE) {
            allocator.freeBuffer(sbtBuffer);
            sbtBuffer = VK_NULL_HANDLE;
        }
        if (pipeline != VK_NULL_HANDLE) {
            vkDestroyPipeline(device, pipeline, null);
            pipeline = VK_NULL_HANDLE;
        }
        if (pipelineLayout != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(device, pipelineLayout, null);
            pipelineLayout = VK_NULL_HANDLE;
        }
        if (descriptorPool != VK_NULL_HANDLE) {
            vkDestroyDescriptorPool(device, descriptorPool, null);
            descriptorPool = VK_NULL_HANDLE;
        }
        if (descriptorSetLayout != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(device, descriptorSetLayout, null);
            descriptorSetLayout = VK_NULL_HANDLE;
        }
        descriptorSet  = VK_NULL_HANDLE;
        sbtRaygenAddr  = 0;
        sbtMissAddr    = 0;
        sbtHitAddr     = 0;

        LOG.info("VkRTPipeline cleanup complete");
    }

    // ═══ 工具 ═══

    /** 對齊工具（Sascha Willems 公式） */
    public static int alignedSize(int value, int alignment) {
        return (value + alignment - 1) & ~(alignment - 1);
    }

    // ═══ Accessors ═══

    public long getPipeline()           { return pipeline; }
    public long getPipelineLayout()     { return pipelineLayout; }
    public long getDescriptorSet()      { return descriptorSet; }
    public long getDescriptorSetLayout(){ return descriptorSetLayout; }
    public boolean isCreated()          { return pipeline != VK_NULL_HANDLE; }
}
