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
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.*;

/**
 * ReSTIR DI + GI Vulkan Compute Dispatcher（Phase 8 整合）。
 *
 * <h3>負責範圍</h3>
 * <ul>
 *   <li>為 {@code restir_di.comp.glsl}（Blackwell）和
 *       {@code restir_gi.comp.glsl}（Blackwell）建立並管理 Vulkan compute pipeline</li>
 *   <li>管理 3-set descriptor 佈局（Set0 = SSBO+TLAS、Set1 = GBuffer 取樣器、Set2 = Camera UBO）</li>
 *   <li>每幀更新 descriptor set（reservoir buffer ping-pong + GBuffer view 輪換）</li>
 *   <li>提供 {@link #dispatchDI} / {@link #dispatchGI} 方法錄製 compute command buffer</li>
 * </ul>
 *
 * <h3>ReSTIR DI 著色器 binding 佈局（restir_di.comp.glsl）</h3>
 * <pre>
 * Set 0:
 *   b=0  ACCELERATION_STRUCTURE  u_TLAS
 *   b=5  STORAGE_BUFFER          ReSTIRDIBuffer（current，寫入）
 *   b=6  STORAGE_BUFFER          ReSTIRDIHistory（previous，讀取）
 *   b=7  STORAGE_BUFFER          LightBVHBuffer
 *   b=8  STORAGE_BUFFER          LightListBuffer
 * Set 1:
 *   b=0  COMBINED_IMAGE_SAMPLER  g_Depth
 *   b=1  COMBINED_IMAGE_SAMPLER  g_Normal
 * Set 2:
 *   b=0  UNIFORM_BUFFER          CameraFrame（256B）
 * </pre>
 *
 * <h3>ReSTIR GI 著色器 binding 佈局（restir_gi.comp.glsl）</h3>
 * <pre>
 * Set 0:
 *   b=0  STORAGE_BUFFER          GIReservoirCurrent（寫入）
 *   b=1  STORAGE_BUFFER          GIReservoirPrevious（讀取）
 *   b=2  STORAGE_BUFFER          DIReservoir（來自 ReSTIR DI）
 *   b=3  STORAGE_BUFFER          SVDAGBuffer
 *   b=4  ACCELERATION_STRUCTURE  u_TLAS
 * Set 1:
 *   b=0  COMBINED_IMAGE_SAMPLER  g_Depth
 *   b=1  COMBINED_IMAGE_SAMPLER  g_Normal
 *   b=2  COMBINED_IMAGE_SAMPLER  g_Albedo
 * Set 2:
 *   b=0  UNIFORM_BUFFER          CameraFrame（256B）
 * </pre>
 *
 * @see BRReSTIRDI
 * @see BRReSTIRGI
 * @see BRGBufferAttachments
 */
@OnlyIn(Dist.CLIENT)
public final class BRReSTIRComputeDispatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger("BR-ReSTIRDispatch");

    // ════════════════════════════════════════════════════════════════════════
    //  Singleton
    // ════════════════════════════════════════════════════════════════════════

    private static final BRReSTIRComputeDispatcher INSTANCE = new BRReSTIRComputeDispatcher();
    public  static BRReSTIRComputeDispatcher getInstance() { return INSTANCE; }
    private BRReSTIRComputeDispatcher() {}

    // ════════════════════════════════════════════════════════════════════════
    //  狀態
    // ════════════════════════════════════════════════════════════════════════

    private boolean initialized = false;
    private int renderWidth  = 0;
    private int renderHeight = 0;

    // ── ReSTIR DI pipeline ─────────────────────────────────────────────────
    private long diSet0Layout    = 0L;  // TLAS + 4×SSBO
    private long diSet1Layout    = 0L;  // 2×sampler
    private long diSet2Layout    = 0L;  // 1×UBO（共用 Camera）
    private long diPipeLayout    = 0L;
    private long diPipeline      = 0L;
    private long diDescPool      = 0L;
    private long diSet0          = 0L;
    private long diSet1          = 0L;
    private long diSet2          = 0L;

    // ── ReSTIR GI pipeline ─────────────────────────────────────────────────
    private long giSet0Layout    = 0L;  // 4×SSBO + TLAS
    private long giSet1Layout    = 0L;  // 3×sampler
    private long giSet2Layout    = 0L;  // 1×UBO
    private long giPipeLayout    = 0L;
    private long giPipeline      = 0L;
    private long giDescPool      = 0L;
    private long giSet0          = 0L;
    private long giSet1          = 0L;
    private long giSet2          = 0L;

    // ── Shared GBuffer sampler ─────────────────────────────────────────────
    private long gbufSampler     = 0L;

    // ════════════════════════════════════════════════════════════════════════
    //  生命週期
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 初始化 ReSTIR DI 和 GI 計算管線。
     *
     * @param width  渲染目標寬度
     * @param height 渲染目標高度
     * @return true = 成功；false = 失敗（ReSTIR compute 停用）
     */
    public boolean init(int width, int height) {
        if (initialized) return true;

        long device = BRVulkanDevice.getVkDevice();
        if (device == 0L) {
            LOGGER.warn("[ReSTIRDispatch] Vulkan device not ready — skipping init");
            return false;
        }

        this.renderWidth  = width;
        this.renderHeight = height;

        LOGGER.info("[ReSTIRDispatch] Initializing ReSTIR DI+GI compute pipelines ({}×{})", width, height);

        try {
            // ── 共用 GBuffer sampler（nearest，用於深度/法線）────────────────
            gbufSampler = BRVulkanDevice.createNearestSampler(device);
            if (gbufSampler == 0L) throw new RuntimeException("GBuffer sampler");

            // ── ReSTIR DI ─────────────────────────────────────────────────
            if (!initDIPipeline(device))  throw new RuntimeException("DI pipeline");
            if (!initDIDescriptors(device)) throw new RuntimeException("DI descriptors");

            // ── ReSTIR GI ─────────────────────────────────────────────────
            if (!initGIPipeline(device))  throw new RuntimeException("GI pipeline");
            if (!initGIDescriptors(device)) throw new RuntimeException("GI descriptors");

            initialized = true;
            LOGGER.info("[ReSTIRDispatch] Initialized successfully");
            return true;

        } catch (Exception e) {
            LOGGER.error("[ReSTIRDispatch] Init failed: {}", e.getMessage());
            cleanup();
            return false;
        }
    }

    /** 釋放所有 Vulkan 資源。 */
    public void cleanup() {
        long device = BRVulkanDevice.getVkDevice();
        if (device == 0L) { initialized = false; return; }

        if (gbufSampler   != 0L) { BRVulkanDevice.destroySampler(device, gbufSampler); gbufSampler = 0L; }

        // DI
        if (diDescPool    != 0L) { BRVulkanDevice.destroyDescriptorPool(device, diDescPool);           diDescPool = 0L; }
        if (diPipeline    != 0L) { BRVulkanDevice.destroyPipeline(device, diPipeline);                 diPipeline = 0L; }
        if (diPipeLayout  != 0L) { BRVulkanDevice.destroyPipelineLayout(device, diPipeLayout);         diPipeLayout = 0L; }
        if (diSet0Layout  != 0L) { BRVulkanDevice.destroyDescriptorSetLayout(device, diSet0Layout);    diSet0Layout = 0L; }
        if (diSet1Layout  != 0L) { BRVulkanDevice.destroyDescriptorSetLayout(device, diSet1Layout);    diSet1Layout = 0L; }
        if (diSet2Layout  != 0L) { BRVulkanDevice.destroyDescriptorSetLayout(device, diSet2Layout);    diSet2Layout = 0L; }

        // GI
        if (giDescPool    != 0L) { BRVulkanDevice.destroyDescriptorPool(device, giDescPool);           giDescPool = 0L; }
        if (giPipeline    != 0L) { BRVulkanDevice.destroyPipeline(device, giPipeline);                 giPipeline = 0L; }
        if (giPipeLayout  != 0L) { BRVulkanDevice.destroyPipelineLayout(device, giPipeLayout);         giPipeLayout = 0L; }
        if (giSet0Layout  != 0L) { BRVulkanDevice.destroyDescriptorSetLayout(device, giSet0Layout);    giSet0Layout = 0L; }
        if (giSet1Layout  != 0L) { BRVulkanDevice.destroyDescriptorSetLayout(device, giSet1Layout);    giSet1Layout = 0L; }
        if (giSet2Layout  != 0L) { BRVulkanDevice.destroyDescriptorSetLayout(device, giSet2Layout);    giSet2Layout = 0L; }

        initialized = false;
    }

    public boolean isInitialized() { return initialized; }

    // ════════════════════════════════════════════════════════════════════════
    //  Dispatch 主入口
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 發射 ReSTIR DI compute pass。
     *
     * <p>呼叫前確保：
     * <ul>
     *   <li>BRReSTIRDI 已 swap()（current/previous 已交換）</li>
     *   <li>BRVulkanBVH.getTLAS() 傳回有效 TLAS handle</li>
     *   <li>BRGBufferAttachments 已初始化</li>
     * </ul>
     */
    public void dispatchDI() {
        if (!initialized) return;

        long device = BRVulkanDevice.getVkDevice();
        if (device == 0L) return;

        BRReSTIRDI di = BRReSTIRDI.getInstance();
        if (!di.isInitialized()) return;

        long tlas       = BRVulkanBVH.getTLAS();
        BRGBufferAttachments gbuf = BRGBufferAttachments.getInstance();
        long depthView  = gbuf.getDepthView();
        long normalView = gbuf.getNormalView();

        // 更新 descriptor sets（每幀，因 ping-pong）
        updateDIDescriptors(device, tlas,
            di.getCurrentReservoirBuffer(), di.getPreviousReservoirBuffer(),
            di.getLightBvhSsbo(),
            depthView, normalView);

        // 錄製 compute dispatch
        long cmd = BRVulkanDevice.beginSingleTimeCommands(device);
        if (cmd == 0L) return;

        VkDevice vkDev = BRVulkanDevice.getVkDeviceObj();
        if (vkDev == null) { BRVulkanDevice.endSingleTimeCommands(device, cmd); return; }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer cb = new VkCommandBuffer(cmd, vkDev);

            // 綁定 ReSTIR DI compute pipeline
            vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, diPipeline);

            // 綁定 3 個 descriptor sets
            LongBuffer sets = stack.longs(diSet0, diSet1, diSet2);
            vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE,
                diPipeLayout, 0, sets, null);

            // 分組：8×8 workgroup（對應 shader local_size_x=8, local_size_y=8）
            int gx = (renderWidth  + 7) / 8;
            int gy = (renderHeight + 7) / 8;
            vkCmdDispatch(cb, gx, gy, 1);

            // Memory barrier：確保 ReSTIR DI 寫入對 GI pass 可見
            insertSSBOBarrier(stack, cb);
        }

        BRVulkanDevice.endSingleTimeCommands(device, cmd);
        di.onFrameEnd();

        LOGGER.trace("[ReSTIRDispatch] DI dispatch: {}×{} groups ({}×{}px)",
            (renderWidth+7)/8, (renderHeight+7)/8, renderWidth, renderHeight);
    }

    /**
     * 發射 ReSTIR GI compute pass。
     *
     * <p>必須在 {@link #dispatchDI()} 之後呼叫，以確保 DI reservoir 可讀。
     */
    public void dispatchGI() {
        if (!initialized) return;

        long device = BRVulkanDevice.getVkDevice();
        if (device == 0L) return;

        BRReSTIRGI gi = BRReSTIRGI.getInstance();
        BRReSTIRDI di = BRReSTIRDI.getInstance();
        if (!gi.isInitialized() || !di.isInitialized()) return;

        long tlas = BRVulkanBVH.getTLAS();
        BRGBufferAttachments gbuf = BRGBufferAttachments.getInstance();
        long depthView  = gbuf.getDepthView();
        long normalView = gbuf.getNormalView();
        long albedoView = gbuf.getAlbedoView();

        // 更新 GI descriptor sets（包含 DI reservoir + GBuffer + TLAS）
        updateGIDescriptors(device, tlas,
            gi.getCurrentReservoirBuffer(), gi.getPreviousReservoirBuffer(),
            di.getCurrentReservoirBuffer(),  // DI reservoir 供 GI 次級光源重用
            depthView, normalView, albedoView);

        long cmd = BRVulkanDevice.beginSingleTimeCommands(device);
        if (cmd == 0L) return;

        VkDevice vkDev = BRVulkanDevice.getVkDeviceObj();
        if (vkDev == null) { BRVulkanDevice.endSingleTimeCommands(device, cmd); return; }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer cb = new VkCommandBuffer(cmd, vkDev);

            vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, giPipeline);

            LongBuffer sets = stack.longs(giSet0, giSet1, giSet2);
            vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE,
                giPipeLayout, 0, sets, null);

            int gx = (renderWidth  + 7) / 8;
            int gy = (renderHeight + 7) / 8;
            vkCmdDispatch(cb, gx, gy, 1);

            insertSSBOBarrier(stack, cb);
        }

        BRVulkanDevice.endSingleTimeCommands(device, cmd);
        gi.onFrameEnd();

        LOGGER.trace("[ReSTIRDispatch] GI dispatch: {}×{} groups", (renderWidth+7)/8, (renderHeight+7)/8);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Pipeline 初始化（DI）
    // ════════════════════════════════════════════════════════════════════════

    private boolean initDIPipeline(long device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDevice vkDev = BRVulkanDevice.getVkDeviceObj();
            if (vkDev == null) return false;

            // Set 0 layout：TLAS(b=0, AS) + 4×SSBO(b=5,6,7,8)
            diSet0Layout = createDISet0Layout(device, stack, vkDev);
            if (diSet0Layout == 0L) return false;

            // Set 1 layout：2×sampler(b=0,1)
            diSet1Layout = createSamplerSetLayout(device, stack, vkDev, 2);
            if (diSet1Layout == 0L) return false;

            // Set 2 layout：1×UBO(b=0)
            diSet2Layout = createUBOSetLayout(device, stack, vkDev);
            if (diSet2Layout == 0L) return false;

            // Pipeline layout（3 sets）
            LongBuffer layouts = stack.longs(diSet0Layout, diSet1Layout, diSet2Layout);
            VkPipelineLayoutCreateInfo plInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                .pSetLayouts(layouts);
            LongBuffer pLayout = stack.mallocLong(1);
            if (vkCreatePipelineLayout(vkDev, plInfo, null, pLayout) != VK_SUCCESS) return false;
            diPipeLayout = pLayout.get(0);

            // Compute pipeline（從資源載入 restir_di.comp.glsl）
            long shaderModule = loadComputeShader(device, vkDev, stack,
                "assets/blockreality/shaders/rt/blackwell/restir_di.comp.glsl");
            if (shaderModule == 0L) return false;

            diPipeline = buildComputePipeline(device, vkDev, stack, diPipeLayout, shaderModule);
            vkDestroyShaderModule(vkDev, shaderModule, null);
            return diPipeline != 0L;

        } catch (Exception e) {
            LOGGER.error("[ReSTIRDispatch] DI pipeline init failed", e);
            return false;
        }
    }

    private boolean initDIDescriptors(long device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDevice vkDev = BRVulkanDevice.getVkDeviceObj();
            if (vkDev == null) return false;

            // 描述符池：AS×1, SSBO×4, Sampler×2, UBO×1
            diDescPool = createDIDescriptorPool(device, vkDev, stack);
            if (diDescPool == 0L) return false;

            LongBuffer pSet = stack.mallocLong(1);

            // Set 0
            LongBuffer l0 = stack.longs(diSet0Layout);
            VkDescriptorSetAllocateInfo a0 = VkDescriptorSetAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                .descriptorPool(diDescPool).pSetLayouts(l0);
            if (vkAllocateDescriptorSets(vkDev, a0, pSet) != VK_SUCCESS) return false;
            diSet0 = pSet.get(0);

            // Set 1
            LongBuffer l1 = stack.longs(diSet1Layout);
            VkDescriptorSetAllocateInfo a1 = VkDescriptorSetAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                .descriptorPool(diDescPool).pSetLayouts(l1);
            if (vkAllocateDescriptorSets(vkDev, a1, pSet) != VK_SUCCESS) return false;
            diSet1 = pSet.get(0);

            // Set 2（Camera UBO，共用 BRVulkanDevice.cameraUboBuffer）
            LongBuffer l2 = stack.longs(diSet2Layout);
            VkDescriptorSetAllocateInfo a2 = VkDescriptorSetAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                .descriptorPool(diDescPool).pSetLayouts(l2);
            if (vkAllocateDescriptorSets(vkDev, a2, pSet) != VK_SUCCESS) return false;
            diSet2 = pSet.get(0);

            // 寫入 Camera UBO 到 set2 binding 0
            if (BRVulkanDevice.cameraUboBuffer != 0L) {
                writeCameraUBOToSet(vkDev, stack, diSet2, BRVulkanDevice.cameraUboBuffer);
            }

            return true;
        } catch (Exception e) {
            LOGGER.error("[ReSTIRDispatch] DI descriptor init failed", e);
            return false;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Pipeline 初始化（GI）
    // ════════════════════════════════════════════════════════════════════════

    private boolean initGIPipeline(long device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDevice vkDev = BRVulkanDevice.getVkDeviceObj();
            if (vkDev == null) return false;

            // Set 0 layout：4×SSBO(b=0,1,2,3) + TLAS(b=4, AS)
            giSet0Layout = createGISet0Layout(device, stack, vkDev);
            if (giSet0Layout == 0L) return false;

            // Set 1 layout：3×sampler(b=0,1,2)
            giSet1Layout = createSamplerSetLayout(device, stack, vkDev, 3);
            if (giSet1Layout == 0L) return false;

            // Set 2 layout：1×UBO(b=0)
            giSet2Layout = createUBOSetLayout(device, stack, vkDev);
            if (giSet2Layout == 0L) return false;

            LongBuffer layouts = stack.longs(giSet0Layout, giSet1Layout, giSet2Layout);
            VkPipelineLayoutCreateInfo plInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                .pSetLayouts(layouts);
            LongBuffer pLayout = stack.mallocLong(1);
            if (vkCreatePipelineLayout(vkDev, plInfo, null, pLayout) != VK_SUCCESS) return false;
            giPipeLayout = pLayout.get(0);

            long shaderModule = loadComputeShader(device, vkDev, stack,
                "assets/blockreality/shaders/rt/blackwell/restir_gi.comp.glsl");
            if (shaderModule == 0L) return false;

            giPipeline = buildComputePipeline(device, vkDev, stack, giPipeLayout, shaderModule);
            vkDestroyShaderModule(vkDev, shaderModule, null);
            return giPipeline != 0L;

        } catch (Exception e) {
            LOGGER.error("[ReSTIRDispatch] GI pipeline init failed", e);
            return false;
        }
    }

    private boolean initGIDescriptors(long device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDevice vkDev = BRVulkanDevice.getVkDeviceObj();
            if (vkDev == null) return false;

            giDescPool = createGIDescriptorPool(device, vkDev, stack);
            if (giDescPool == 0L) return false;

            LongBuffer pSet = stack.mallocLong(1);

            VkDescriptorSetAllocateInfo a0 = VkDescriptorSetAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                .descriptorPool(giDescPool).pSetLayouts(stack.longs(giSet0Layout));
            if (vkAllocateDescriptorSets(vkDev, a0, pSet) != VK_SUCCESS) return false;
            giSet0 = pSet.get(0);

            VkDescriptorSetAllocateInfo a1 = VkDescriptorSetAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                .descriptorPool(giDescPool).pSetLayouts(stack.longs(giSet1Layout));
            if (vkAllocateDescriptorSets(vkDev, a1, pSet) != VK_SUCCESS) return false;
            giSet1 = pSet.get(0);

            VkDescriptorSetAllocateInfo a2 = VkDescriptorSetAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                .descriptorPool(giDescPool).pSetLayouts(stack.longs(giSet2Layout));
            if (vkAllocateDescriptorSets(vkDev, a2, pSet) != VK_SUCCESS) return false;
            giSet2 = pSet.get(0);

            if (BRVulkanDevice.cameraUboBuffer != 0L) {
                writeCameraUBOToSet(vkDev, stack, giSet2, BRVulkanDevice.cameraUboBuffer);
            }

            return true;
        } catch (Exception e) {
            LOGGER.error("[ReSTIRDispatch] GI descriptor init failed", e);
            return false;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Descriptor 更新（每幀）
    // ════════════════════════════════════════════════════════════════════════

    private void updateDIDescriptors(long device, long tlas,
                                     long curReservoir, long prevReservoir, long lightBvh,
                                     long depthView, long normalView) {
        VkDevice vkDev = BRVulkanDevice.getVkDeviceObj();
        if (vkDev == null) return;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Set 0：TLAS(b=0) + 3×SSBO（current b=5, previous b=6, lightBVH b=7）
            // LightList(b=8) 目前暫用 lightBVH SSBO 代替（待 BRRTEmissiveManager 整合）
            int writeCount = (tlas != 0L ? 1 : 0)
                           + (curReservoir  > 4L ? 1 : 0)
                           + (prevReservoir > 4L ? 1 : 0)
                           + (lightBvh      > 4L ? 1 : 0)
                           + ((depthView != 0L && normalView != 0L) ? 2 : 0);

            if (writeCount == 0) return;
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(writeCount, stack);
            int idx = 0;

            // TLAS → set0, binding 0
            if (tlas != 0L) {
                VkWriteDescriptorSetAccelerationStructureKHR tlasInfo =
                    VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_ACCELERATION_STRUCTURE_KHR)
                        .pAccelerationStructures(stack.longs(tlas));
                writes.get(idx++).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .pNext(tlasInfo.address()).dstSet(diSet0).dstBinding(0)
                    .descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR);
            }

            // Current reservoir SSBO → set0, binding 5
            if (curReservoir > 4L) {
                idx = writeSSBO(writes, idx, stack, diSet0, 5, curReservoir,
                    (long) renderWidth * renderHeight * BRReSTIRDI.RESERVOIR_SIZE);
            }

            // Previous reservoir SSBO → set0, binding 6
            if (prevReservoir > 4L) {
                idx = writeSSBO(writes, idx, stack, diSet0, 6, prevReservoir,
                    (long) renderWidth * renderHeight * BRReSTIRDI.RESERVOIR_SIZE);
            }

            // Light BVH SSBO → set0, binding 7（暫以 curReservoir 佔位，待 EmissiveManager）
            if (lightBvh > 4L) {
                idx = writeSSBO(writes, idx, stack, diSet0, 7, lightBvh, VK_WHOLE_SIZE);
            }

            // GBuffer samplers → set1
            if (depthView != 0L && normalView != 0L) {
                idx = writeSampler(writes, idx, stack, diSet1, 0, depthView, gbufSampler,
                    VK_IMAGE_LAYOUT_GENERAL);
                idx = writeSampler(writes, idx, stack, diSet1, 1, normalView, gbufSampler,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            }

            vkUpdateDescriptorSets(vkDev, writes, null);
        }
    }

    private void updateGIDescriptors(long device, long tlas,
                                     long curGI, long prevGI, long diReservoir,
                                     long depthView, long normalView, long albedoView) {
        VkDevice vkDev = BRVulkanDevice.getVkDeviceObj();
        if (vkDev == null) return;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            long pixelBytes = (long) renderWidth * renderHeight * BRReSTIRGI.RESERVOIR_SIZE;
            long diBytes    = (long) renderWidth * renderHeight * BRReSTIRDI.RESERVOIR_SIZE;

            // 最多 8 writes：3×SSBO set0 + TLAS set0 + 3×sampler set1
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(8, stack);
            int idx = 0;

            // GI current SSBO → set0, binding 0
            if (curGI > 4L)
                idx = writeSSBO(writes, idx, stack, giSet0, 0, curGI, pixelBytes);
            // GI previous SSBO → set0, binding 1
            if (prevGI > 4L)
                idx = writeSSBO(writes, idx, stack, giSet0, 1, prevGI, pixelBytes);
            // DI reservoir SSBO → set0, binding 2
            if (diReservoir > 4L)
                idx = writeSSBO(writes, idx, stack, giSet0, 2, diReservoir, diBytes);

            // TLAS → set0, binding 4
            if (tlas != 0L) {
                VkWriteDescriptorSetAccelerationStructureKHR tlasInfo =
                    VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_ACCELERATION_STRUCTURE_KHR)
                        .pAccelerationStructures(stack.longs(tlas));
                writes.get(idx++).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .pNext(tlasInfo.address()).dstSet(giSet0).dstBinding(4)
                    .descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR);
            }

            // GBuffer samplers → set1
            if (depthView != 0L) {
                idx = writeSampler(writes, idx, stack, giSet1, 0, depthView,  gbufSampler,
                    VK_IMAGE_LAYOUT_GENERAL);
            }
            if (normalView != 0L) {
                idx = writeSampler(writes, idx, stack, giSet1, 1, normalView, gbufSampler,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            }
            if (albedoView != 0L) {
                idx = writeSampler(writes, idx, stack, giSet1, 2, albedoView, gbufSampler,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            }

            // 只提交有效的 write 條目
            if (idx > 0) {
                vkUpdateDescriptorSets(vkDev, writes.limit(idx), null);
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Layout 建立工具
    // ════════════════════════════════════════════════════════════════════════

    /** ReSTIR DI Set0：TLAS(b=0,AS) + 4×SSBO(b=5,6,7,8) */
    private long createDISet0Layout(long device, MemoryStack stack, VkDevice vkDev) {
        int computeStage = VK_SHADER_STAGE_COMPUTE_BIT;
        VkDescriptorSetLayoutBinding.Buffer bindings =
            VkDescriptorSetLayoutBinding.calloc(5, stack);

        // b=0: TLAS
        bindings.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
            .descriptorCount(1).stageFlags(computeStage);
        // b=5: current reservoir SSBO
        bindings.get(1).binding(5).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
            .descriptorCount(1).stageFlags(computeStage);
        // b=6: previous reservoir SSBO
        bindings.get(2).binding(6).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
            .descriptorCount(1).stageFlags(computeStage);
        // b=7: light BVH SSBO
        bindings.get(3).binding(7).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
            .descriptorCount(1).stageFlags(computeStage);
        // b=8: light list SSBO
        bindings.get(4).binding(8).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
            .descriptorCount(1).stageFlags(computeStage);

        return createLayout(vkDev, stack, bindings);
    }

    /** ReSTIR GI Set0：4×SSBO(b=0,1,2,3) + TLAS(b=4,AS) */
    private long createGISet0Layout(long device, MemoryStack stack, VkDevice vkDev) {
        int computeStage = VK_SHADER_STAGE_COMPUTE_BIT;
        VkDescriptorSetLayoutBinding.Buffer bindings =
            VkDescriptorSetLayoutBinding.calloc(5, stack);

        // b=0: GI current reservoir
        bindings.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
            .descriptorCount(1).stageFlags(computeStage);
        // b=1: GI previous reservoir
        bindings.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
            .descriptorCount(1).stageFlags(computeStage);
        // b=2: DI reservoir（GI 次級光源重用）
        bindings.get(2).binding(2).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
            .descriptorCount(1).stageFlags(computeStage);
        // b=3: SVDAG SSBO（遠景 GI bouncing）
        bindings.get(3).binding(3).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
            .descriptorCount(1).stageFlags(computeStage);
        // b=4: TLAS（AS）
        bindings.get(4).binding(4).descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
            .descriptorCount(1).stageFlags(computeStage);

        return createLayout(vkDev, stack, bindings);
    }

    /** 通用 sampler set layout（N 個 COMBINED_IMAGE_SAMPLER，連續 binding 0..N-1）*/
    private long createSamplerSetLayout(long device, MemoryStack stack,
                                        VkDevice vkDev, int count) {
        VkDescriptorSetLayoutBinding.Buffer bindings =
            VkDescriptorSetLayoutBinding.calloc(count, stack);
        for (int i = 0; i < count; i++) {
            bindings.get(i).binding(i)
                .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
        }
        return createLayout(vkDev, stack, bindings);
    }

    /** 通用 UBO set layout（1 個 UNIFORM_BUFFER，binding 0）*/
    private long createUBOSetLayout(long device, MemoryStack stack, VkDevice vkDev) {
        VkDescriptorSetLayoutBinding.Buffer bindings =
            VkDescriptorSetLayoutBinding.calloc(1, stack);
        bindings.get(0).binding(0)
            .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
            .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
        return createLayout(vkDev, stack, bindings);
    }

    private long createLayout(VkDevice vkDev, MemoryStack stack,
                              VkDescriptorSetLayoutBinding.Buffer bindings) {
        VkDescriptorSetLayoutCreateInfo info = VkDescriptorSetLayoutCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
            .pBindings(bindings);
        LongBuffer pLayout = stack.mallocLong(1);
        return vkCreateDescriptorSetLayout(vkDev, info, null, pLayout) == VK_SUCCESS
            ? pLayout.get(0) : 0L;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Descriptor Pool 建立
    // ════════════════════════════════════════════════════════════════════════

    private long createDIDescriptorPool(long device, VkDevice vkDev, MemoryStack stack) {
        // DI: AS×1, SSBO×4, Sampler×2, UBO×1（各 set 1 份）
        VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(4, stack);
        sizes.get(0).type(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1);
        sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(4);
        sizes.get(2).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(2);
        sizes.get(3).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1);
        return createPool(vkDev, stack, sizes, 3);
    }

    private long createGIDescriptorPool(long device, VkDevice vkDev, MemoryStack stack) {
        // GI: SSBO×4, AS×1, Sampler×3, UBO×1
        VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(4, stack);
        sizes.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(4);
        sizes.get(1).type(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1);
        sizes.get(2).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(3);
        sizes.get(3).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1);
        return createPool(vkDev, stack, sizes, 3);
    }

    private long createPool(VkDevice vkDev, MemoryStack stack,
                            VkDescriptorPoolSize.Buffer sizes, int maxSets) {
        VkDescriptorPoolCreateInfo info = VkDescriptorPoolCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
            .pPoolSizes(sizes).maxSets(maxSets);
        LongBuffer pPool = stack.mallocLong(1);
        return vkCreateDescriptorPool(vkDev, info, null, pPool) == VK_SUCCESS
            ? pPool.get(0) : 0L;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Compute Pipeline 建立
    // ════════════════════════════════════════════════════════════════════════

    private long loadComputeShader(long device, VkDevice vkDev, MemoryStack stack, String path) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                LOGGER.error("[ReSTIRDispatch] Shader resource not found: {}", path);
                return 0L;
            }
            String glsl = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            byte[] spirv = BRVulkanDevice.compileGLSLtoSPIRV(glsl, path);
            if (spirv == null || spirv.length == 0) {
                LOGGER.error("[ReSTIRDispatch] SPIR-V compilation failed: {}", path);
                return 0L;
            }
            return BRVulkanDevice.createShaderModule(device, spirv);
        } catch (Exception e) {
            LOGGER.error("[ReSTIRDispatch] Failed to load shader: {}", path, e);
            return 0L;
        }
    }

    private long buildComputePipeline(long device, VkDevice vkDev, MemoryStack stack,
                                      long pipelineLayout, long shaderModule) {
        VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
            .stage(VK_SHADER_STAGE_COMPUTE_BIT)
            .module(shaderModule)
            .pName(stack.UTF8("main"));

        VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack);
        info.get(0)
            .sType(VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO)
            .stage(stage)
            .layout(pipelineLayout);

        LongBuffer pPipeline = stack.mallocLong(1);
        int r = vkCreateComputePipelines(vkDev, 0L, info, null, pPipeline);
        if (r != VK_SUCCESS) {
            LOGGER.error("[ReSTIRDispatch] vkCreateComputePipelines failed: {}", r);
            return 0L;
        }
        return pPipeline.get(0);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  WriteDescriptorSet 工具
    // ════════════════════════════════════════════════════════════════════════

    private int writeSSBO(VkWriteDescriptorSet.Buffer writes, int idx, MemoryStack stack,
                          long dstSet, int binding, long buffer, long size) {
        VkDescriptorBufferInfo.Buffer bi = VkDescriptorBufferInfo.calloc(1, stack)
            .buffer(buffer).offset(0).range(size);
        writes.get(idx)
            .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
            .dstSet(dstSet).dstBinding(binding).descriptorCount(1)
            .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
            .pBufferInfo(bi);
        return idx + 1;
    }

    private int writeSampler(VkWriteDescriptorSet.Buffer writes, int idx, MemoryStack stack,
                             long dstSet, int binding, long imageView, long sampler, int layout) {
        VkDescriptorImageInfo.Buffer ii = VkDescriptorImageInfo.calloc(1, stack)
            .sampler(sampler).imageView(imageView).imageLayout(layout);
        writes.get(idx)
            .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
            .dstSet(dstSet).dstBinding(binding).descriptorCount(1)
            .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
            .pImageInfo(ii);
        return idx + 1;
    }

    private void writeCameraUBOToSet(VkDevice vkDev, MemoryStack stack, long dstSet, long uboBuffer) {
        VkDescriptorBufferInfo.Buffer bi = VkDescriptorBufferInfo.calloc(1, stack)
            .buffer(uboBuffer).offset(0).range(256L);
        VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
        write.get(0)
            .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
            .dstSet(dstSet).dstBinding(0).descriptorCount(1)
            .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
            .pBufferInfo(bi);
        vkUpdateDescriptorSets(vkDev, write, null);
    }

    /** SSBO → SSBO memory barrier（確保前一 pass 寫入對下一 pass 可見）*/
    private void insertSSBOBarrier(MemoryStack stack, VkCommandBuffer cb) {
        VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack)
            .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
            .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
            .dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
        vkCmdPipelineBarrier(cb,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            0, barrier, null, null);
    }
}
