package com.blockreality.api.client.render.rt;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;

/**
 * DDGI Probe Irradiance Update — Vulkan Compute Dispatcher（RT-6-1）。
 *
 * <h3>負責範圍</h3>
 * <ul>
 *   <li>為 {@code ddgi_update.comp.glsl} 建立並管理 Vulkan compute pipeline</li>
 *   <li>每幀以 {@code probesThisFrame} 個 workgroup 執行 probe irradiance 更新</li>
 *   <li>Descriptor Set 0：irradianceAtlas（storage image）、visibilityAtlas（storage image）、
 *       ProbeUBO（SSBO）、DIReservoir（SSBO）、u_TLAS（AS）</li>
 *   <li>Descriptor Set 1：SceneUBO（sunDir / sunColor / skyColor / frameIndex）</li>
 *   <li>Push Constants：firstProbeIdx、probeCount、grid 參數</li>
 * </ul>
 *
 * <h3>DDGI 更新頻率</h3>
 * <p>每幀更新 {@code probesThisFrame}（= ceil(totalProbes × updateRatio)）個 probe，
 * {@code updateCursor} 輪轉確保每個 probe 在 {@code 1/updateRatio} 幀內更新一次。
 * 預設 20% 更新率 → 每 5 幀完整更新一次 16 384 個 probe。</p>
 *
 * <h3>Shader Binding 佈局（ddgi_update.comp.glsl）</h3>
 * <pre>
 * Set 0:
 *   b=0  STORAGE_IMAGE            irradianceAtlas（rgba16f，read-write）
 *   b=1  STORAGE_IMAGE            visibilityAtlas（rg16f，read-write）
 *   b=2  STORAGE_BUFFER           ProbeUBO（probe world positions）
 *   b=3  STORAGE_BUFFER           DIReservoir（來自 ReSTIR DI，可選）
 *   b=4  ACCELERATION_STRUCTURE   u_TLAS
 * Set 1:
 *   b=0  UNIFORM_BUFFER           SceneUBO（sunDir/color/sky/frameIndex）
 * Push Constants（40 bytes）:
 *   uint firstProbeIdx, probeCount, gridX, gridY, gridZ
 *   int  spacingBlocks, gridOriginX, gridOriginY, gridOriginZ, atlasProbesPerRow
 * </pre>
 *
 * <h3>Specialization Constants</h3>
 * <pre>
 *   SC 0: NUM_RAYS         = 64（Ada）
 *   SC 1: IRRAD_TEXELS     = 8
 *   SC 2: VIS_TEXELS       = 8
 *   SC 3: HYSTERESIS_IRRAD = 0x3F7851EC（0.97f）
 *   SC 4: HYSTERESIS_VIS   = 0x3F7851EC
 * </pre>
 *
 * @see BRDDGIProbeSystem
 * @see BRReSTIRDI
 */
@OnlyIn(Dist.CLIENT)
public final class BRDDGIComputeDispatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger("BR-DDGIDispatch");

    // ════════════════════════════════════════════════════════════════════════
    //  Singleton
    // ════════════════════════════════════════════════════════════════════════

    private static final BRDDGIComputeDispatcher INSTANCE = new BRDDGIComputeDispatcher();

    public static BRDDGIComputeDispatcher getInstance() { return INSTANCE; }

    private BRDDGIComputeDispatcher() {}

    // ════════════════════════════════════════════════════════════════════════
    //  Vulkan 資源
    // ════════════════════════════════════════════════════════════════════════

    /** Compute pipeline（ddgi_update.comp.glsl）。 */
    private long pipeline       = 0L;
    private long pipeLayout     = 0L;

    /** Descriptor Pool + Sets。 */
    private long descPool       = 0L;
    private long set0           = 0L;  // irradAtlas + visAtlas + ProbeUBO + DIResv + TLAS
    private long set0Layout     = 0L;
    private long set1           = 0L;  // SceneUBO
    private long set1Layout     = 0L;

    /** Scene UBO（sunDir / sunColor / skyColor / frameIndex） — HOST_COHERENT。 */
    private long sceneUbo       = 0L;
    private long sceneUboMem    = 0L;
    /** SceneUBO 結構大小（scalar layout）：3×vec3 + float + 1×uint + 3×float = 64 bytes。 */
    private static final int SCENE_UBO_SIZE = 64;

    private boolean initialized = false;

    // ════════════════════════════════════════════════════════════════════════
    //  Init
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 初始化 DDGI compute dispatcher。
     * 須在 {@link BRDDGIProbeSystem#init} 成功後呼叫。
     *
     * @return true = 初始化成功
     */
    public boolean init() {
        if (initialized) return true;
        long device = BRVulkanDevice.getVkDevice();
        if (device == 0L) {
            LOGGER.warn("[DDGIDispatch] Vulkan device not ready");
            return false;
        }
        BRDDGIProbeSystem ddgi = BRDDGIProbeSystem.getInstance();
        if (!ddgi.isInitialized()) {
            LOGGER.warn("[DDGIDispatch] BRDDGIProbeSystem not initialized; call probe system init first");
            return false;
        }

        try {
            // ── 1. Descriptor Set Layouts ────────────────────────────────────
            set0Layout = createSet0Layout(device);
            set1Layout = createSet1Layout(device);
            if (set0Layout == 0L || set1Layout == 0L) {
                LOGGER.error("[DDGIDispatch] descriptor layout creation failed");
                return false;
            }

            // ── 2. Pipeline Layout + Push Constant range (40 bytes = 10 × int) ──
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkPushConstantRange.Buffer pcRange = VkPushConstantRange.calloc(1, stack)
                        .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT)
                        .offset(0)
                        .size(40);  // 10 × int32
                LongBuffer layouts = stack.longs(set0Layout, set1Layout);
                LongBuffer pLayout = stack.mallocLong(1);
                int r = vkCreatePipelineLayout(BRVulkanDevice.getVkDeviceObj(),
                        VkPipelineLayoutCreateInfo.calloc(stack)
                                .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                                .pSetLayouts(layouts)
                                .pPushConstantRanges(pcRange),
                        null, pLayout);
                if (r != VK_SUCCESS) {
                    LOGGER.error("[DDGIDispatch] vkCreatePipelineLayout failed: {}", r);
                    return false;
                }
                pipeLayout = pLayout.get(0);
            }

            // ── 3. Compute Pipeline（ddgi_update.comp.glsl + specialization consts） ──
            pipeline = createComputePipeline(device, pipeLayout);
            if (pipeline == 0L) {
                LOGGER.warn("[DDGIDispatch] shader compile failed — DDGI GPU update disabled");
                return false;
            }

            // ── 4. Descriptor Pool（1 set × 2 types） ────────────────────────
            descPool = createDescriptorPool(device);
            if (descPool == 0L) return false;
            if (!allocateDescriptorSets(device)) return false;

            // ── 5. Scene UBO ─────────────────────────────────────────────────
            sceneUbo = BRVulkanDevice.createBuffer(device, SCENE_UBO_SIZE,
                    VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
            if (sceneUbo != 0L) {
                sceneUboMem = BRVulkanDevice.allocateAndBindBuffer(device, sceneUbo,
                        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            }

            // ── 6. Initial descriptor write ───────────────────────────────────
            updateDescriptors(device);

            initialized = true;
            LOGGER.info("[DDGIDispatch] Initialized — DDGI compute pipeline ready");
            return true;
        } catch (Exception e) {
            LOGGER.error("[DDGIDispatch] init failed", e);
            cleanup();
            return false;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Dispatch
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 每幀更新 {@code updateProbeIndices} 中的 probe irradiance。
     *
     * @param updateProbeIndices 本幀需要更新的 probe 線性索引陣列（來自 BRDDGIProbeSystem.onFrameStart()）
     * @param frameIndex         Halton seed（供 Fibonacci spiral 旋轉）
     */
    public void dispatch(int[] updateProbeIndices, long frameIndex) {
        if (!initialized || updateProbeIndices == null || updateProbeIndices.length == 0) return;
        long device = BRVulkanDevice.getVkDevice();
        if (device == 0L) return;

        long tlas = BRVulkanBVH.getTLAS();
        if (tlas == 0L) {
            LOGGER.trace("[DDGIDispatch] TLAS not ready — skipping probe update");
            return;
        }

        BRDDGIProbeSystem ddgi = BRDDGIProbeSystem.getInstance();

        // 更新 Scene UBO（sunDir、sunColor、skyColor、frameIndex）
        uploadSceneUbo(frameIndex);

        // 更新 descriptor sets（TLAS 每幀可能重建）
        updateDescriptors(device);

        // 批次 dispatch：一個 workgroup 一個 probe，每 workgroup 64 threads（NUM_RAYS）
        // 若 probe 數量 > MAX_DISPATCH，拆分為多次 dispatch
        final int MAX_DISPATCH = 4096;  // 防止 driver timeout
        int firstProbe = updateProbeIndices[0];
        int probeCount = updateProbeIndices.length;

        for (int batchStart = 0; batchStart < probeCount; batchStart += MAX_DISPATCH) {
            int batchCount = Math.min(MAX_DISPATCH, probeCount - batchStart);
            int batchFirstIdx = updateProbeIndices[batchStart];
            dispatchBatch(device, ddgi, batchFirstIdx, batchCount);
        }

        LOGGER.trace("[DDGIDispatch] {} probes updated (frame={})", probeCount, frameIndex);
    }

    private void dispatchBatch(long device, BRDDGIProbeSystem ddgi,
                                int firstProbeIdx, int probeCount) {
        long cmd = BRVulkanDevice.beginSingleTimeCommands(device);
        if (cmd == 0L) return;

        VkDevice vkDev = BRVulkanDevice.getVkDeviceObj();
        if (vkDev == null) { BRVulkanDevice.endSingleTimeCommands(device, cmd); return; }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer cb = new VkCommandBuffer(cmd, vkDev);

            vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);

            LongBuffer sets = stack.longs(set0, set1);
            vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE,
                    pipeLayout, 0, sets, null);

            // Push constants（10 × int32 = 40 bytes）
            IntBuffer pc = stack.mallocInt(10);
            pc.put(0,  firstProbeIdx);
            pc.put(1,  probeCount);
            pc.put(2,  ddgi.getGridX());
            pc.put(3,  ddgi.getGridY());
            pc.put(4,  ddgi.getGridZ());
            pc.put(5,  ddgi.getSpacingBlocks());
            org.joml.Vector3i origin = ddgi.getGridOrigin();
            pc.put(6,  origin.x);
            pc.put(7,  origin.y);
            pc.put(8,  origin.z);
            pc.put(9,  ddgi.getGridX() * ddgi.getGridZ());  // atlasProbesPerRow
            vkCmdPushConstants(cb, pipeLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);

            // 一個 workgroup = 一個 probe（local_size_x = 64 = NUM_RAYS）
            vkCmdDispatch(cb, probeCount, 1, 1);

            // Memory barrier：irradiance/visibility atlas 寫入對 fragment shader 可見
            insertImageBarrier(stack, cb, ddgi.getIrradianceAtlas());
            insertImageBarrier(stack, cb, ddgi.getVisibilityAtlas());
        }

        BRVulkanDevice.endSingleTimeCommands(device, cmd);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Descriptor 更新
    // ════════════════════════════════════════════════════════════════════════

    private void updateDescriptors(long device) {
        if (set0 == 0L || set1 == 0L) return;
        BRDDGIProbeSystem ddgi = BRDDGIProbeSystem.getInstance();
        long tlas = BRVulkanBVH.getTLAS();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Set 0 には 5 bindings
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(6, stack);

            // b=0 irradianceAtlas（STORAGE_IMAGE）
            VkDescriptorImageInfo.Buffer irrInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .imageView(ddgi.getIrradianceAtlasView())
                    .imageLayout(VK_IMAGE_LAYOUT_GENERAL);
            writes.get(0)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(set0).dstBinding(0).descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .pImageInfo(irrInfo);

            // b=1 visibilityAtlas（STORAGE_IMAGE）
            VkDescriptorImageInfo.Buffer visInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .imageView(ddgi.getVisibilityAtlasView())
                    .imageLayout(VK_IMAGE_LAYOUT_GENERAL);
            writes.get(1)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(set0).dstBinding(1).descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .pImageInfo(visInfo);

            // b=2 ProbeUBO（STORAGE_BUFFER）
            VkDescriptorBufferInfo.Buffer probeInfo = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(ddgi.getProbeUboBuffer()).offset(0).range(VK_WHOLE_SIZE);
            writes.get(2)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(set0).dstBinding(2).descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .pBufferInfo(probeInfo);

            // b=3 DIReservoir（STORAGE_BUFFER）— 來自 ReSTIR DI（可選；0 = sky fallback）
            long diResvBuf = 0L;
            if (BRReSTIRDI.getInstance().isInitialized()) {
                diResvBuf = BRReSTIRDI.getInstance().getCurrentReservoirBuffer();
            }
            int writeCount = 5;
            if (diResvBuf != 0L) {
                VkDescriptorBufferInfo.Buffer diInfo = VkDescriptorBufferInfo.calloc(1, stack)
                        .buffer(diResvBuf).offset(0).range(VK_WHOLE_SIZE);
                writes.get(3)
                        .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                        .dstSet(set0).dstBinding(3).descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .pBufferInfo(diInfo);
            } else {
                writeCount = 4; // DIReservoir binding 省略（shader 以 sky 近似）
            }

            // b=4 TLAS（ACCELERATION_STRUCTURE）
            if (tlas != 0L) {
                LongBuffer pTlas = stack.longs(tlas);
                VkWriteDescriptorSetAccelerationStructureKHR asWrite =
                        VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack)
                                .sType(KHRAccelerationStructure.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_ACCELERATION_STRUCTURE_KHR)
                                .pAccelerationStructures(pTlas);
                writes.get(writeCount - 1)   // last slot
                        .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                        .pNext(asWrite.address())
                        .dstSet(set0).dstBinding(4).descriptorCount(1)
                        .descriptorType(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR);
            }

            // Set 1 b=0 SceneUBO（UNIFORM_BUFFER）
            if (sceneUbo != 0L) {
                VkDescriptorBufferInfo.Buffer sceneInfo = VkDescriptorBufferInfo.calloc(1, stack)
                        .buffer(sceneUbo).offset(0).range(SCENE_UBO_SIZE);
                writes.get(5)
                        .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                        .dstSet(set1).dstBinding(0).descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                        .pBufferInfo(sceneInfo);
            }

            vkUpdateDescriptorSets(BRVulkanDevice.getVkDeviceObj(), writes, null);
        } catch (Exception e) {
            LOGGER.warn("[DDGIDispatch] updateDescriptors failed: {}", e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Scene UBO
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 寫入 Scene UBO（sunDir + sunColor + skyColor + frameIndex）。
     * 使用 HOST_COHERENT 記憶體，無需 flush。
     */
    private void uploadSceneUbo(long frameIndex) {
        if (sceneUboMem == 0L) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            java.nio.LongBuffer pData = stack.mallocLong(1);
            if (vkMapMemory(BRVulkanDevice.getVkDeviceObj(), sceneUboMem,
                    0, SCENE_UBO_SIZE, 0, pData) != VK_SUCCESS) return;
            java.nio.ByteBuffer buf = org.lwjgl.system.MemoryUtil.memByteBuffer(
                    pData.get(0), SCENE_UBO_SIZE);
            buf.order(java.nio.ByteOrder.LITTLE_ENDIAN);
            // sunDir（vec3 + pad）— 使用靜態值；可由 BRWeatherManager 更新
            buf.putFloat(0,  -0.408f); buf.putFloat(4,  0.816f); buf.putFloat(8,  -0.408f);
            buf.putFloat(12, 0.0f);    // _p0
            // sunColor（vec3 + pad） — 正午白光
            buf.putFloat(16, 1.5f); buf.putFloat(20, 1.4f); buf.putFloat(24, 1.2f);
            buf.putFloat(28, 0.0f);
            // skyColor（vec3 + pad） — 天際散射藍
            buf.putFloat(32, 0.4f); buf.putFloat(36, 0.6f); buf.putFloat(40, 1.0f);
            buf.putFloat(44, 0.0f);
            // frameIndex（uint）+ pad[3]
            buf.putInt(48, (int)(frameIndex & 0xFFFFFFFFL));
            buf.putInt(52, 0); buf.putInt(56, 0); buf.putInt(60, 0);
            vkUnmapMemory(BRVulkanDevice.getVkDeviceObj(), sceneUboMem);
        } catch (Exception e) {
            LOGGER.trace("[DDGIDispatch] uploadSceneUbo failed: {}", e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Vulkan 物件建立 helpers
    // ════════════════════════════════════════════════════════════════════════

    /** Set 0 layout：2×STORAGE_IMAGE + 2×STORAGE_BUFFER + 1×AS。 */
    private long createSet0Layout(long device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings =
                    VkDescriptorSetLayoutBinding.calloc(5, stack);
            // b=0 irradianceAtlas（STORAGE_IMAGE, COMPUTE）
            bindings.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            // b=1 visibilityAtlas（STORAGE_IMAGE, COMPUTE）
            bindings.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            // b=2 ProbeUBO（STORAGE_BUFFER, COMPUTE）
            bindings.get(2).binding(2).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            // b=3 DIReservoir（STORAGE_BUFFER, COMPUTE）
            bindings.get(3).binding(3).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            // b=4 TLAS（ACCELERATION_STRUCTURE, COMPUTE）
            bindings.get(4).binding(4)
                    .descriptorType(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);

            LongBuffer pLayout = stack.mallocLong(1);
            int r = vkCreateDescriptorSetLayout(BRVulkanDevice.getVkDeviceObj(),
                    VkDescriptorSetLayoutCreateInfo.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                            .pBindings(bindings),
                    null, pLayout);
            return r == VK_SUCCESS ? pLayout.get(0) : 0L;
        }
    }

    /** Set 1 layout：1×UNIFORM_BUFFER（SceneUBO）。 */
    private long createSet1Layout(long device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings =
                    VkDescriptorSetLayoutBinding.calloc(1, stack);
            bindings.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            LongBuffer pLayout = stack.mallocLong(1);
            int r = vkCreateDescriptorSetLayout(BRVulkanDevice.getVkDeviceObj(),
                    VkDescriptorSetLayoutCreateInfo.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                            .pBindings(bindings),
                    null, pLayout);
            return r == VK_SUCCESS ? pLayout.get(0) : 0L;
        }
    }

    private long createDescriptorPool(long device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(4, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(2);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(2);
            sizes.get(2).type(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                    .descriptorCount(1);
            sizes.get(3).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1);

            LongBuffer pPool = stack.mallocLong(1);
            int r = vkCreateDescriptorPool(BRVulkanDevice.getVkDeviceObj(),
                    VkDescriptorPoolCreateInfo.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                            .maxSets(2)
                            .pPoolSizes(sizes),
                    null, pPool);
            if (r != VK_SUCCESS) {
                LOGGER.error("[DDGIDispatch] vkCreateDescriptorPool failed: {}", r);
                return 0L;
            }
            return pPool.get(0);
        }
    }

    private boolean allocateDescriptorSets(long device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer layouts = stack.longs(set0Layout, set1Layout);
            LongBuffer pSets   = stack.mallocLong(2);
            int r = vkAllocateDescriptorSets(BRVulkanDevice.getVkDeviceObj(),
                    VkDescriptorSetAllocateInfo.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                            .descriptorPool(descPool)
                            .pSetLayouts(layouts),
                    pSets);
            if (r != VK_SUCCESS) {
                LOGGER.error("[DDGIDispatch] vkAllocateDescriptorSets failed: {}", r);
                return false;
            }
            set0 = pSets.get(0);
            set1 = pSets.get(1);
            return true;
        }
    }

    /**
     * 從 classpath 載入 ddgi_update.comp.glsl 並建立 compute pipeline。
     * 使用 Specialization Constants 配置 NUM_RAYS=64、IRRAD_TEXELS=8、VIS_TEXELS=8。
     */
    private long createComputePipeline(long device, long pLayout) {
        final String shaderPath =
                "/assets/blockreality/shaders/rt/ada/ddgi_update.comp.glsl";
        byte[] spirv;
        try (InputStream is = getClass().getResourceAsStream(shaderPath)) {
            if (is == null) {
                LOGGER.error("[DDGIDispatch] Shader not found: {}", shaderPath);
                return 0L;
            }
            spirv = is.readAllBytes();
        } catch (Exception e) {
            LOGGER.error("[DDGIDispatch] Failed to load shader: {}", e.getMessage());
            return 0L;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Shader module
            java.nio.ByteBuffer spirvBuf = stack.malloc(spirv.length);
            spirvBuf.put(spirv).flip();
            LongBuffer pShader = stack.mallocLong(1);
            int r = vkCreateShaderModule(BRVulkanDevice.getVkDeviceObj(),
                    VkShaderModuleCreateInfo.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                            .pCode(spirvBuf),
                    null, pShader);
            if (r != VK_SUCCESS) {
                LOGGER.error("[DDGIDispatch] vkCreateShaderModule failed: {}", r);
                return 0L;
            }
            long shaderModule = pShader.get(0);

            // Specialization constants（5 entries）
            // SC 0: NUM_RAYS=64, SC 1: IRRAD_TEXELS=8, SC 2: VIS_TEXELS=8
            // SC 3: HYSTERESIS_IRRAD=0x3F7851EC（0.97f）, SC 4: HYSTERESIS_VIS=0x3F7851EC
            VkSpecializationMapEntry.Buffer mapEntries =
                    VkSpecializationMapEntry.calloc(5, stack);
            mapEntries.get(0).constantID(0).offset(0).size(4);
            mapEntries.get(1).constantID(1).offset(4).size(4);
            mapEntries.get(2).constantID(2).offset(8).size(4);
            mapEntries.get(3).constantID(3).offset(12).size(4);
            mapEntries.get(4).constantID(4).offset(16).size(4);

            java.nio.ByteBuffer scData = stack.malloc(20);
            scData.putInt(0,  64);           // NUM_RAYS
            scData.putInt(4,  8);            // IRRAD_TEXELS
            scData.putInt(8,  8);            // VIS_TEXELS
            scData.putInt(12, 0x3F7851EC);   // HYSTERESIS_IRRAD = 0.97f (bits)
            scData.putInt(16, 0x3F7851EC);   // HYSTERESIS_VIS   = 0.97f (bits)

            VkSpecializationInfo scInfo = VkSpecializationInfo.calloc(stack)
                    .pMapEntries(mapEntries)
                    .pData(scData);

            VkPipelineShaderStageCreateInfo stageInfo =
                    VkPipelineShaderStageCreateInfo.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                            .stage(VK_SHADER_STAGE_COMPUTE_BIT)
                            .module(shaderModule)
                            .pSpecializationInfo(scInfo)
                            .pName(stack.UTF8("main"));

            LongBuffer pPipeline = stack.mallocLong(1);
            r = vkCreateComputePipelines(BRVulkanDevice.getVkDeviceObj(),
                    VK_NULL_HANDLE, // no pipeline cache
                    VkComputePipelineCreateInfo.calloc(1, stack)
                            .sType(VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO)
                            .stage(stageInfo)
                            .layout(pLayout),
                    null, pPipeline);

            vkDestroyShaderModule(BRVulkanDevice.getVkDeviceObj(), shaderModule, null);

            if (r != VK_SUCCESS) {
                LOGGER.error("[DDGIDispatch] vkCreateComputePipelines failed: {}", r);
                return 0L;
            }
            return pPipeline.get(0);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Memory / Image barriers
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Full pipeline barrier：確保 compute store 寫入對後續 fragment shader 可見。
     */
    private void insertImageBarrier(MemoryStack stack, VkCommandBuffer cb, long image) {
        if (image == 0L) return;
        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                .dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
                .oldLayout(VK_IMAGE_LAYOUT_GENERAL)
                .newLayout(VK_IMAGE_LAYOUT_GENERAL)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image);
        barrier.get(0).subresourceRange()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1)
                .baseArrayLayer(0).layerCount(1);
        vkCmdPipelineBarrier(cb,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                0, null, null, barrier);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Cleanup
    // ════════════════════════════════════════════════════════════════════════

    public void cleanup() {
        if (!initialized && pipeline == 0L) return;
        VkDevice vkDev = BRVulkanDevice.getVkDeviceObj();
        if (vkDev != null) {
            long device = BRVulkanDevice.getVkDevice();
            if (pipeline    != 0L) { vkDestroyPipeline(vkDev, pipeline, null);         pipeline    = 0L; }
            if (pipeLayout  != 0L) { vkDestroyPipelineLayout(vkDev, pipeLayout, null); pipeLayout  = 0L; }
            if (descPool    != 0L) { vkDestroyDescriptorPool(vkDev, descPool, null);   descPool    = 0L; }
            if (set0Layout  != 0L) { vkDestroyDescriptorSetLayout(vkDev, set0Layout, null); set0Layout = 0L; }
            if (set1Layout  != 0L) { vkDestroyDescriptorSetLayout(vkDev, set1Layout, null); set1Layout = 0L; }
            if (sceneUbo    != 0L) { vkDestroyBuffer(vkDev, sceneUbo, null);           sceneUbo    = 0L; }
            if (sceneUboMem != 0L) { vkFreeMemory(vkDev, sceneUboMem, null);           sceneUboMem = 0L; }
        }
        set0 = 0L; set1 = 0L;
        initialized = false;
        LOGGER.info("[DDGIDispatch] Cleanup complete");
    }

    public boolean isInitialized() { return initialized; }
}
