package com.blockreality.api.client.rendering.lod;

import com.blockreality.api.client.render.optimization.BRSparseVoxelDAG;
import com.blockreality.api.client.render.rt.BRVulkanDevice;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * SVDAG LOD3 追蹤器 — 遠場 (2048+ blocks) 的 Sparse Voxel DAG 計算著色器渲染。
 *
 * <p>流程：
 * <ul>
 *   <li>BRSparseVoxelDAG 序列化為 GPU SSBO 格式</li>
 *   <li>上傳至 Vulkan device buffer</li>
 *   <li>建立計算管線（lod3_svdag_trace.comp.glsl）</li>
 *   <li>每幀在相機距離 > 128 chunks 時調度計算著色器</li>
 * </ul>
 *
 * <p>輸出：
 * <ul>
 *   <li>Color image (rgba16f) — 最終著色</li>
 *   <li>Depth image (r32f) — 線性深度（block 單位）</li>
 * </ul>
 *
 * @author Block Reality Team
 */
@OnlyIn(Dist.CLIENT)
public final class SVDAGLOD3Tracer {

    private static final Logger LOGGER = LoggerFactory.getLogger("BR-SVDAGLOD3");

    // ── VK format 常數 ────────────────────────────────────────────────────────
    /** VK_FORMAT_R16G16B16A16_SFLOAT */
    private static final int FMT_RGBA16F = 97;
    /** VK_FORMAT_R32_SFLOAT */
    private static final int FMT_R32F    = 100;

    // ── 狀態 ──────────────────────────────────────────────────────────────────
    private static boolean initialized = false;
    private static int displayWidth, displayHeight;

    // DAG SSBO buffer（host 更新，device 讀）
    private static long dagBuffer = 0L;
    private static long dagBufferMemory = 0L;
    private static int dagBufferSize = 0;

    // Camera UBO
    private static long cameraUBO = 0L;
    private static long cameraUBOMemory = 0L;
    private static final int CAMERA_UBO_SIZE = 80;  // mat4(64) + vec3(12) + pad(4)

    // 輸出圖像
    private static final long[] colorImg  = new long[3];  // [image, memory, view]
    private static final long[] depthImg  = new long[3];

    // 描述符集佈局與集
    private static long dsLayout = 0L;
    private static long descriptorPool = 0L;
    private static long descriptorSet = 0L;

    // 管線
    private static long pipelineLayout = 0L;
    private static long computePipeline = 0L;

    private SVDAGLOD3Tracer() {}

    // ═══════════════════════════════════════════════════════════════════════════
    // 生命週期
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * 初始化 SVDAG LOD3 追蹤器。建立 Vulkan 資源與計算管線。
     *
     * @param w 輸出寬度（像素）
     * @param h 輸出高度（像素）
     */
    public static void init(int w, int h) {
        if (initialized) {
            LOGGER.warn("[SVDAG] init() called while already initialized — skipping");
            return;
        }

        long device = BRVulkanDevice.getVkDevice();
        if (device == 0L) {
            LOGGER.warn("[SVDAG] Vulkan device not available — SVDAG LOD3 disabled");
            return;
        }

        LOGGER.info("[SVDAG] Initializing SVDAG LOD3 tracer ({}×{})...", w, h);
        displayWidth  = w;
        displayHeight = h;

        try {
            // 1. DAG SSBO（初始化為空，待 updateDAG 更新）
            if (!initializeDAGBuffer(device)) throw new RuntimeException("DAG SSBO");

            // 2. Camera UBO
            if (!initializeCameraUBO(device)) throw new RuntimeException("Camera UBO");

            // 3. 輸出圖像
            if (!allocateImages(device, w, h)) throw new RuntimeException("output images");

            // 4. 描述符集佈局與池
            if (!createDescriptorSetLayout(device)) throw new RuntimeException("DS layout");
            if (!createDescriptorPool(device)) throw new RuntimeException("descriptor pool");
            if (!allocateDescriptorSet(device)) throw new RuntimeException("descriptor set");

            // 5. 管線佈局 & 計算管線
            if (!createPipelineLayout(device)) throw new RuntimeException("pipeline layout");
            if (!createComputePipeline(device)) throw new RuntimeException("compute pipeline");

            initialized = true;
            LOGGER.info("[SVDAG] Initialized successfully");
        } catch (Exception e) {
            LOGGER.error("[SVDAG] Initialization failed: {}", e.getMessage(), e);
            shutdown();
        }
    }

    /** 清理所有資源。 */
    public static void shutdown() {
        if (!initialized) return;

        long device = BRVulkanDevice.getVkDevice();
        if (device == 0L) {
            initialized = false;
            return;
        }

        try {
            if (computePipeline != 0L) {
                vkDestroyPipeline(BRVulkanDevice.getVkDeviceObj(), computePipeline, null);
                computePipeline = 0L;
            }
            if (pipelineLayout != 0L) {
                vkDestroyPipelineLayout(BRVulkanDevice.getVkDeviceObj(), pipelineLayout, null);
                pipelineLayout = 0L;
            }
            if (descriptorSet != 0L) {
                // descriptor set 由 pool 自動清理，不需手動 free
                descriptorSet = 0L;
            }
            if (descriptorPool != 0L) {
                vkDestroyDescriptorPool(BRVulkanDevice.getVkDeviceObj(), descriptorPool, null);
                descriptorPool = 0L;
            }
            if (dsLayout != 0L) {
                BRVulkanDevice.destroyDescriptorSetLayout(device, dsLayout);
                dsLayout = 0L;
            }
            if (colorImg[0] != 0L) {
                BRVulkanDevice.destroyImage2D(device, colorImg[0], colorImg[1], colorImg[2]);
                colorImg[0] = colorImg[1] = colorImg[2] = 0L;
            }
            if (depthImg[0] != 0L) {
                BRVulkanDevice.destroyImage2D(device, depthImg[0], depthImg[1], depthImg[2]);
                depthImg[0] = depthImg[1] = depthImg[2] = 0L;
            }
            if (cameraUBO != 0L) {
                vkDestroyBuffer(BRVulkanDevice.getVkDeviceObj(), cameraUBO, null);
                cameraUBO = 0L;
            }
            if (cameraUBOMemory != 0L) {
                vkFreeMemory(BRVulkanDevice.getVkDeviceObj(), cameraUBOMemory, null);
                cameraUBOMemory = 0L;
            }
            if (dagBuffer != 0L) {
                vkDestroyBuffer(BRVulkanDevice.getVkDeviceObj(), dagBuffer, null);
                dagBuffer = 0L;
            }
            if (dagBufferMemory != 0L) {
                vkFreeMemory(BRVulkanDevice.getVkDeviceObj(), dagBufferMemory, null);
                dagBufferMemory = 0L;
            }

            LOGGER.info("[SVDAG] Shutdown complete");
        } catch (Exception e) {
            LOGGER.error("[SVDAG] Shutdown error", e);
        }

        initialized = false;
    }

    public static boolean isInitialized() {
        return initialized;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DAG 與 camera 更新
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * 從 BRSparseVoxelDAG 更新 GPU 緩衝區。
     * 必須在 BRSparseVoxelDAG.buildFromVoxelGrid() 後呼叫。
     */
    public static void updateDAGBuffer() {
        if (!initialized) return;

        long device = BRVulkanDevice.getVkDevice();
        if (device == 0L) return;

        ByteBuffer gpuData = BRSparseVoxelDAG.serializeForGPU();
        if (gpuData == null || gpuData.remaining() <= 0) {
            LOGGER.warn("[SVDAG] DAG serialization returned null or empty buffer");
            return;
        }

        int newSize = gpuData.remaining();
        if (newSize > dagBufferSize) {
            // 需要重新分配更大的 buffer
            LOGGER.info("[SVDAG] Reallocating DAG buffer: {} → {} bytes", dagBufferSize, newSize);
            if (dagBuffer != 0L) {
                vkDestroyBuffer(BRVulkanDevice.getVkDeviceObj(), dagBuffer, null);
                dagBuffer = 0L;
            }
            if (dagBufferMemory != 0L) {
                vkFreeMemory(BRVulkanDevice.getVkDeviceObj(), dagBufferMemory, null);
                dagBufferMemory = 0L;
            }

            dagBuffer = BRVulkanDevice.createBuffer(device, newSize,
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
            if (dagBuffer == 0L) {
                LOGGER.error("[SVDAG] Failed to create DAG buffer");
                return;
            }
            dagBufferMemory = BRVulkanDevice.allocateAndBindBuffer(device, dagBuffer,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            if (dagBufferMemory == 0L) {
                LOGGER.error("[SVDAG] Failed to allocate DAG buffer memory");
                vkDestroyBuffer(BRVulkanDevice.getVkDeviceObj(), dagBuffer, null);
                dagBuffer = 0L;
                return;
            }
            dagBufferSize = newSize;
        }

        // 上傳資料（staging + copy）
        BRVulkanDevice.uploadBufferData(device, dagBuffer, gpuData, newSize);
        LOGGER.debug("[SVDAG] Updated DAG buffer: {} bytes", newSize);
    }

    /**
     * 每幀更新 camera UBO。
     *
     * @param invViewProj 逆視圖投影矩陣（轉置為列優先列佈局）
     * @param cameraPos   相機世界位置
     */
    public static void updateCameraUBO(Matrix4f invViewProj, float[] cameraPos) {
        if (!initialized || cameraUBO == 0L) return;

        long device = BRVulkanDevice.getVkDevice();
        if (device == 0L) return;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer buffer = stack.malloc(CAMERA_UBO_SIZE);

            // mat4 (列優先，64 bytes)
            float[] mat4Data = new float[16];
            invViewProj.get(mat4Data);
            for (int i = 0; i < 16; i++) {
                buffer.putFloat(i * 4, mat4Data[i]);
            }

            // vec3 (12 bytes)
            buffer.putFloat(64, cameraPos[0]);
            buffer.putFloat(68, cameraPos[1]);
            buffer.putFloat(72, cameraPos[2]);
            // pad (4 bytes) = 0

            BRVulkanDevice.uploadBufferData(device, cameraUBO, buffer, CAMERA_UBO_SIZE);
        }
    }

    /**
     * 調度計算著色器，執行 SVDAG LOD3 光線追蹤。
     *
     * @param commandBuf Vulkan 命令緩衝區（已在錄製狀態）
     */
    public static void dispatchLOD3(long commandBuf) {
        if (!initialized || computePipeline == 0L) return;

        VkCommandBuffer vkCmd = new VkCommandBuffer(commandBuf, BRVulkanDevice.getVkDeviceObj());

        // 綁定計算管線
        vkCmdBindPipeline(vkCmd, VK_PIPELINE_BIND_POINT_COMPUTE, computePipeline);

        // 綁定描述符集
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pSets = stack.longs(descriptorSet);
            vkCmdBindDescriptorSets(vkCmd, VK_PIPELINE_BIND_POINT_COMPUTE,
                    pipelineLayout, 0, pSets, null);

            // 調度計算工作組（8×8 local size）
            int gx = (displayWidth  + 7) / 8;
            int gy = (displayHeight + 7) / 8;
            vkCmdDispatch(vkCmd, gx, gy, 1);
        }
    }

    /**
     * 取得最終著色輸出的 VkImageView。
     */
    public static long getColorImageView() {
        return initialized ? colorImg[2] : 0L;
    }

    /**
     * 取得最終深度輸出的 VkImageView。
     */
    public static long getDepthImageView() {
        return initialized ? depthImg[2] : 0L;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 內部初始化
    // ═══════════════════════════════════════════════════════════════════════════

    private static boolean initializeDAGBuffer(long device) {
        // 初始大小：32 bytes header + 100 nodes × 36 bytes = 3632 bytes
        dagBufferSize = 32 + 100 * 36;
        dagBuffer = BRVulkanDevice.createBuffer(device, dagBufferSize,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        if (dagBuffer == 0L) {
            LOGGER.error("[SVDAG] Failed to create DAG buffer");
            return false;
        }

        dagBufferMemory = BRVulkanDevice.allocateAndBindBuffer(device, dagBuffer,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        if (dagBufferMemory == 0L) {
            LOGGER.error("[SVDAG] Failed to allocate DAG buffer memory");
            vkDestroyBuffer(BRVulkanDevice.getVkDeviceObj(), dagBuffer, null);
            dagBuffer = 0L;
            return false;
        }

        return true;
    }

    private static boolean initializeCameraUBO(long device) {
        cameraUBO = BRVulkanDevice.createBuffer(device, CAMERA_UBO_SIZE,
                VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        if (cameraUBO == 0L) {
            LOGGER.error("[SVDAG] Failed to create camera UBO");
            return false;
        }

        cameraUBOMemory = BRVulkanDevice.allocateAndBindBuffer(device, cameraUBO,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        if (cameraUBOMemory == 0L) {
            LOGGER.error("[SVDAG] Failed to allocate camera UBO memory");
            vkDestroyBuffer(BRVulkanDevice.getVkDeviceObj(), cameraUBO, null);
            cameraUBO = 0L;
            return false;
        }

        return true;
    }

    private static boolean allocateImages(long device, int w, int h) {
        int imageUsage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        int colorAspect = VK_IMAGE_ASPECT_COLOR_BIT;

        // Color image (rgba16f)
        long[] color = BRVulkanDevice.createImage2D(device, w, h, FMT_RGBA16F, imageUsage, colorAspect);
        if (color == null) {
            LOGGER.error("[SVDAG] Failed to create color image");
            return false;
        }
        colorImg[0] = color[0];
        colorImg[1] = color[1];
        colorImg[2] = color[2];
        BRVulkanDevice.transitionImageLayout(device, color[0],
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL, colorAspect);

        // Depth image (r32f)
        long[] depth = BRVulkanDevice.createImage2D(device, w, h, FMT_R32F, imageUsage, colorAspect);
        if (depth == null) {
            LOGGER.error("[SVDAG] Failed to create depth image");
            BRVulkanDevice.destroyImage2D(device, color[0], color[1], color[2]);
            return false;
        }
        depthImg[0] = depth[0];
        depthImg[1] = depth[1];
        depthImg[2] = depth[2];
        BRVulkanDevice.transitionImageLayout(device, depth[0],
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL, colorAspect);

        return true;
    }

    private static boolean createDescriptorSetLayout(long device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(4, stack);

            // Binding 0: DAG SSBO
            bindings.get(0)
                    .binding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);

            // Binding 1: Camera UBO
            bindings.get(1)
                    .binding(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);

            // Binding 2: Color output image
            bindings.get(2)
                    .binding(2)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);

            // Binding 3: Depth output image
            bindings.get(3)
                    .binding(3)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);

            LongBuffer pLayout = stack.mallocLong(1);
            int r = vkCreateDescriptorSetLayout(BRVulkanDevice.getVkDeviceObj(),
                    VkDescriptorSetLayoutCreateInfo.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                            .pBindings(bindings),
                    null, pLayout);
            if (r != VK_SUCCESS) {
                LOGGER.error("[SVDAG] descriptor set layout creation failed: {}", r);
                return false;
            }
            dsLayout = pLayout.get(0);
            return true;
        }
    }

    private static boolean createDescriptorPool(long device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(3, stack);
            poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1);
            poolSizes.get(1).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1);
            poolSizes.get(2).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(2);

            LongBuffer pPool = stack.mallocLong(1);
            int r = vkCreateDescriptorPool(BRVulkanDevice.getVkDeviceObj(),
                    VkDescriptorPoolCreateInfo.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                            .maxSets(1)
                            .pPoolSizes(poolSizes),
                    null, pPool);
            if (r != VK_SUCCESS) {
                LOGGER.error("[SVDAG] descriptor pool creation failed: {}", r);
                return false;
            }
            descriptorPool = pPool.get(0);
            return true;
        }
    }

    private static boolean allocateDescriptorSet(long device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pLayout = stack.longs(dsLayout);
            LongBuffer pSet = stack.mallocLong(1);

            int r = vkAllocateDescriptorSets(BRVulkanDevice.getVkDeviceObj(),
                    VkDescriptorSetAllocateInfo.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                            .descriptorPool(descriptorPool)
                            .pSetLayouts(pLayout),
                    pSet);
            if (r != VK_SUCCESS) {
                LOGGER.error("[SVDAG] descriptor set allocation failed: {}", r);
                return false;
            }
            descriptorSet = pSet.get(0);

            // 更新描述符寫入
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(4, stack);

            // DAG SSBO
            VkDescriptorBufferInfo.Buffer dagBufInfo = VkDescriptorBufferInfo.calloc(1, stack);
            dagBufInfo.get(0).buffer(dagBuffer).offset(0).range(dagBufferSize);
            writes.get(0)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(descriptorSet)
                    .dstBinding(0)
                    .descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .pBufferInfo(dagBufInfo);

            // Camera UBO
            VkDescriptorBufferInfo.Buffer camBufInfo = VkDescriptorBufferInfo.calloc(1, stack);
            camBufInfo.get(0).buffer(cameraUBO).offset(0).range(CAMERA_UBO_SIZE);
            writes.get(1)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(descriptorSet)
                    .dstBinding(1)
                    .descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .pBufferInfo(camBufInfo);

            // Color output image
            VkDescriptorImageInfo.Buffer colorImgInfo = VkDescriptorImageInfo.calloc(1, stack);
            colorImgInfo.get(0).imageView(colorImg[2]).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
            writes.get(2)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(descriptorSet)
                    .dstBinding(2)
                    .descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .pImageInfo(colorImgInfo);

            // Depth output image
            VkDescriptorImageInfo.Buffer depthImgInfo = VkDescriptorImageInfo.calloc(1, stack);
            depthImgInfo.get(0).imageView(depthImg[2]).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
            writes.get(3)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(descriptorSet)
                    .dstBinding(3)
                    .descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .pImageInfo(depthImgInfo);

            vkUpdateDescriptorSets(BRVulkanDevice.getVkDeviceObj(), writes, null);
            return true;
        }
    }

    private static boolean createPipelineLayout(long device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pLayout = stack.longs(dsLayout);
            LongBuffer pPipeLayout = stack.mallocLong(1);

            int r = vkCreatePipelineLayout(BRVulkanDevice.getVkDeviceObj(),
                    VkPipelineLayoutCreateInfo.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                            .pSetLayouts(pLayout),
                    null, pPipeLayout);
            if (r != VK_SUCCESS) {
                LOGGER.error("[SVDAG] pipeline layout creation failed: {}", r);
                return false;
            }
            pipelineLayout = pPipeLayout.get(0);
            return true;
        }
    }

    private static boolean createComputePipeline(long device) {
        String glsl = loadShaderResource("lod/lod3_svdag_trace.comp.glsl");
        if (glsl == null) return false;

        byte[] spirv = BRVulkanDevice.compileGLSLtoSPIRV(glsl, "lod3_svdag_trace.comp.glsl");
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
                            .layout(pipelineLayout),
                    null, pPipeline);
            BRVulkanDevice.destroyShaderModule(device, shaderModule);

            if (r != VK_SUCCESS) {
                LOGGER.error("[SVDAG] compute pipeline creation failed: {}", r);
                return false;
            }
            computePipeline = pPipeline.get(0);
            return true;
        }
    }

    private static String loadShaderResource(String path) {
        try (InputStream is = SVDAGLOD3Tracer.class.getClassLoader()
                .getResourceAsStream("assets/blockreality/shaders/" + path)) {
            if (is == null) {
                LOGGER.error("[SVDAG] Shader not found: {}", path);
                return null;
            }
            byte[] bytes = is.readAllBytes();
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.error("[SVDAG] Failed to load shader {}: {}", path, e.getMessage());
            return null;
        }
    }
}
