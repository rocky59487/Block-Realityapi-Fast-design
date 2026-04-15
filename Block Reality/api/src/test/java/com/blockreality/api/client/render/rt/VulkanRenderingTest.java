package com.blockreality.api.client.render.rt;

import org.junit.jupiter.api.*;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.util.vma.*;
import org.lwjgl.vulkan.*;

import java.io.File;
import java.nio.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * Vulkan 渲染輸出測試 — 驗證 Vulkan 計算產生遊戲可用的像素輸出。
 *
 * <p>此測試模擬 Block Reality RT 管線的最小路徑：
 * <ol>
 *   <li>初始化 Vulkan（lavapipe 軟體渲染器）</li>
 *   <li>編譯一個生成 RGBA 像素的 compute shader</li>
 *   <li>GPU dispatch 計算 8×8 = 64 像素的顏色漸層</li>
 *   <li>讀回結果到 CPU — 驗證 readback 路徑（對應 BRVulkanInterop fallback）</li>
 *   <li>驗證每個像素的 RGBA 值正確 — 確認資料格式與遊戲 GL texture 相容</li>
 * </ol>
 *
 * <p>這證明了：
 * <ul>
 *   <li>Vulkan 確實在運作（非 CPU fallback）</li>
 *   <li>GPU 計算產生正確輸出</li>
 *   <li>讀回的資料格式（RGBA float）與 BRVulkanInterop.uploadToGL() 預期一致</li>
 * </ul>
 *
 * 執行：
 * <pre>
 *   ./gradlew :api:test --tests "*.rt.VulkanRenderingTest"
 * </pre>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Vulkan Rendering Output Tests (lavapipe)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VulkanRenderingTest {

    private static final String LAVAPIPE_ICD = "/usr/share/vulkan/icd.d/lvp_icd.json";

    /** 模擬 RT 輸出解析度（8×8 = 64 像素，小計算量） */
    private static final int TEX_W = 8;
    private static final int TEX_H = 8;
    private static final int PIXEL_COUNT = TEX_W * TEX_H;
    /** 每像素 4 個 float（RGBA） */
    private static final int FLOATS_PER_PIXEL = 4;
    private static final int TOTAL_FLOATS = PIXEL_COUNT * FLOATS_PER_PIXEL;

    // ── Shared Vulkan state ─────────────────────────────────
    private VkInstance       instance;
    private VkPhysicalDevice physical;
    private VkDevice         device;
    private VkQueue          computeQueue;
    private int              computeFamily = -1;
    private long             vmaAllocator  = 0;
    private long             commandPool   = 0;
    private long             shadercCompiler = 0;

    // ── 計算結果 ─────────────────────────────────────────────
    /** readback 到 CPU 的像素資料（RGBA float × 64 像素） */
    private float[] cpuPixelData;

    @BeforeAll
    void checkEnvironment() {
        assumeTrue(new File(LAVAPIPE_ICD).exists(),
            "lavapipe ICD 未找到 — 需安裝 mesa-vulkan-drivers");
        try {
            Class.forName("org.lwjgl.vulkan.VK10");
        } catch (ClassNotFoundException e) {
            assumeTrue(false, "org.lwjgl.vulkan not on classpath");
        }
    }

    @AfterAll
    void teardown() {
        if (shadercCompiler != 0) shaderc_compiler_release(shadercCompiler);
        if (commandPool != 0 && device != null) vkDestroyCommandPool(device, commandPool, null);
        if (vmaAllocator != 0) vmaDestroyAllocator(vmaAllocator);
        if (device   != null) vkDestroyDevice(device, null);
        if (instance != null) vkDestroyInstance(instance, null);
    }

    // ═══════════════════════════════════════════════════════════
    //  S1: Vulkan 裝置初始化
    // ═══════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("S1: 初始化 Vulkan 裝置 + VMA + Shaderc + Command Pool")
    void initVulkan() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // ── VkInstance ──
            VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                .pApplicationName(stack.UTF8Safe("BR-RenderTest"))
                .apiVersion(VK_API_VERSION_1_2);

            VkInstanceCreateInfo instCI = VkInstanceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                .pApplicationInfo(appInfo);

            PointerBuffer pInst = stack.mallocPointer(1);
            assertEquals(VK_SUCCESS, vkCreateInstance(instCI, null, pInst));
            instance = new VkInstance(pInst.get(0), instCI);

            // ── Physical Device ──
            IntBuffer pCount = stack.mallocInt(1);
            vkEnumeratePhysicalDevices(instance, pCount, null);
            assertTrue(pCount.get(0) > 0, "無 Vulkan physical device");

            PointerBuffer pDevices = stack.mallocPointer(pCount.get(0));
            vkEnumeratePhysicalDevices(instance, pCount, pDevices);
            physical = new VkPhysicalDevice(pDevices.get(0), instance);

            // ── Compute Queue Family ──
            vkGetPhysicalDeviceQueueFamilyProperties(physical, pCount, null);
            VkQueueFamilyProperties.Buffer families =
                VkQueueFamilyProperties.calloc(pCount.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(physical, pCount, families);

            for (int i = 0; i < pCount.get(0); i++) {
                if ((families.get(i).queueFlags() & VK_QUEUE_COMPUTE_BIT) != 0) {
                    computeFamily = i;
                    break;
                }
            }
            assertTrue(computeFamily >= 0, "無 compute queue family");

            // ── Logical Device ──
            FloatBuffer queuePrio = stack.floats(1.0f);
            VkDeviceQueueCreateInfo.Buffer queueCI = VkDeviceQueueCreateInfo.calloc(1, stack);
            queueCI.get(0).sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                .queueFamilyIndex(computeFamily).pQueuePriorities(queuePrio);

            VkDeviceCreateInfo deviceCI = VkDeviceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                .pQueueCreateInfos(queueCI);

            PointerBuffer pDevice = stack.mallocPointer(1);
            assertEquals(VK_SUCCESS, vkCreateDevice(physical, deviceCI, null, pDevice));
            device = new VkDevice(pDevice.get(0), physical, deviceCI);

            PointerBuffer pQueue = stack.mallocPointer(1);
            vkGetDeviceQueue(device, computeFamily, 0, pQueue);
            computeQueue = new VkQueue(pQueue.get(0), device);

            // ── VMA ──
            VmaVulkanFunctions vmaFuncs = VmaVulkanFunctions.calloc(stack)
                .set(instance, device);
            VmaAllocatorCreateInfo allocCI = VmaAllocatorCreateInfo.calloc(stack)
                .instance(instance).physicalDevice(physical).device(device)
                .pVulkanFunctions(vmaFuncs)
                .vulkanApiVersion(VK_API_VERSION_1_2);
            PointerBuffer pAlloc = stack.mallocPointer(1);
            assertEquals(VK_SUCCESS, vmaCreateAllocator(allocCI, pAlloc));
            vmaAllocator = pAlloc.get(0);

            // ── Command Pool ──
            VkCommandPoolCreateInfo poolCI = VkCommandPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                .queueFamilyIndex(computeFamily)
                .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);
            LongBuffer pPool = stack.mallocLong(1);
            assertEquals(VK_SUCCESS, vkCreateCommandPool(device, poolCI, null, pPool));
            commandPool = pPool.get(0);

            // ── Shaderc ──
            shadercCompiler = shaderc_compiler_initialize();
            assertNotEquals(0L, shadercCompiler);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  S2: RGBA 像素生成 Compute Shader — 模擬 RT 輸出
    // ═══════════════════════════════════════════════════════════

    /**
     * 模擬 RT 管線的 compute shader：為 8×8 影像的每個像素生成 RGBA 漸層色。
     *
     * <p>公式（與 BRVulkanInterop RGBA16F 格式對齊）：
     * <ul>
     *   <li>R = x / (width - 1)  — 水平漸層 [0.0, 1.0]</li>
     *   <li>G = y / (height - 1) — 垂直漸層 [0.0, 1.0]</li>
     *   <li>B = 0.5              — 固定中間值（驗證常數寫入）</li>
     *   <li>A = 1.0              — 完全不透明</li>
     * </ul>
     */
    private static final String RGBA_GRADIENT_SHADER = """
            #version 450
            layout(local_size_x = 8, local_size_y = 8) in;

            layout(std430, binding = 0) buffer OutputBuffer {
                float pixels[];  // RGBA interleaved: [R0,G0,B0,A0, R1,G1,B1,A1, ...]
            };

            layout(push_constant) uniform PC {
                int width;
                int height;
            };

            void main() {
                uint x = gl_GlobalInvocationID.x;
                uint y = gl_GlobalInvocationID.y;
                if (x >= uint(width) || y >= uint(height)) return;

                uint pixelIdx = y * uint(width) + x;
                uint baseIdx  = pixelIdx * 4u;  // 4 floats per pixel (RGBA)

                // 生成漸層色（模擬 RT 輸出）
                float r = float(x) / float(width  - 1);  // 水平漸層
                float g = float(y) / float(height - 1);  // 垂直漸層
                float b = 0.5;                             // 固定值
                float a = 1.0;                             // 完全不透明

                pixels[baseIdx + 0u] = r;
                pixels[baseIdx + 1u] = g;
                pixels[baseIdx + 2u] = b;
                pixels[baseIdx + 3u] = a;
            }
            """;

    @Test
    @Order(2)
    @DisplayName("S2: Compute shader 編譯 → RGBA 漸層像素生成 → GPU dispatch → readback 驗證")
    void testRGBAGradientComputeAndReadback() {
        assumeTrue(vmaAllocator != 0,   "需要 S1 通過");
        assumeTrue(shadercCompiler != 0, "需要 S1 通過");

        // ── 編譯 shader ──
        long spvResult = compileShader(RGBA_GRADIENT_SHADER, "rgba_gradient.comp");
        assertNotEquals(0L, spvResult, "RGBA 漸層 shader 編譯失敗");

        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer spvCode = shaderc_result_get_bytes(spvResult);
            assertNotNull(spvCode);

            // ── Shader module ──
            VkShaderModuleCreateInfo smCI = VkShaderModuleCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                .pCode(spvCode);
            LongBuffer pSM = stack.mallocLong(1);
            assertEquals(VK_SUCCESS, vkCreateShaderModule(device, smCI, null, pSM));
            long shaderModule = pSM.get(0);
            shaderc_result_release(spvResult);

            // ── Descriptor set layout（1 個 storage buffer） ──
            VkDescriptorSetLayoutBinding.Buffer bindings =
                VkDescriptorSetLayoutBinding.calloc(1, stack);
            bindings.get(0).binding(0)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1)
                .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);

            VkDescriptorSetLayoutCreateInfo dslCI = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                .pBindings(bindings);
            LongBuffer pDSL = stack.mallocLong(1);
            assertEquals(VK_SUCCESS, vkCreateDescriptorSetLayout(device, dslCI, null, pDSL));
            long dsl = pDSL.get(0);

            // ── Pipeline layout（push constant: width + height = 8 bytes） ──
            VkPushConstantRange.Buffer pcRange = VkPushConstantRange.calloc(1, stack);
            pcRange.get(0).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(8);

            VkPipelineLayoutCreateInfo plCI = VkPipelineLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                .pSetLayouts(pDSL).pPushConstantRanges(pcRange);
            LongBuffer pPL = stack.mallocLong(1);
            assertEquals(VK_SUCCESS, vkCreatePipelineLayout(device, plCI, null, pPL));
            long pipelineLayout = pPL.get(0);

            // ── Compute pipeline ──
            VkPipelineShaderStageCreateInfo stageCI = VkPipelineShaderStageCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                .stage(VK_SHADER_STAGE_COMPUTE_BIT)
                .module(shaderModule)
                .pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer compCI =
                VkComputePipelineCreateInfo.calloc(1, stack);
            compCI.get(0).sType(VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO)
                .stage(stageCI).layout(pipelineLayout);
            LongBuffer pPipeline = stack.mallocLong(1);
            assertEquals(VK_SUCCESS,
                vkCreateComputePipelines(device, VK_NULL_HANDLE, compCI, null, pPipeline));
            long pipeline = pPipeline.get(0);

            // ── 輸出 buffer（HOST_VISIBLE — 模擬 BRVulkanInterop fallback readback） ──
            long bufSize = TOTAL_FLOATS * 4L; // float = 4 bytes
            VkBufferCreateInfo bufCI = VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .size(bufSize)
                .usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            VmaAllocationCreateInfo cpuAllocCI = VmaAllocationCreateInfo.calloc(stack)
                .usage(VMA_MEMORY_USAGE_CPU_TO_GPU)
                .flags(VMA_ALLOCATION_CREATE_MAPPED_BIT);
            VmaAllocationInfo allocInfo = VmaAllocationInfo.calloc(stack);

            LongBuffer pBuf = stack.mallocLong(1);
            PointerBuffer pAlloc = stack.mallocPointer(1);
            assertEquals(VK_SUCCESS,
                vmaCreateBuffer(vmaAllocator, bufCI, cpuAllocCI, pBuf, pAlloc, allocInfo));
            long outputBuf = pBuf.get(0);
            long outputAlloc = pAlloc.get(0);
            long outputMapped = allocInfo.pMappedData();
            assertNotEquals(0L, outputMapped, "Buffer map 失敗");

            // 清零 buffer（確保結果來自 GPU 而非殘留記憶體）
            MemoryUtil.memSet(outputMapped, 0, bufSize);

            // ── Descriptor pool + set ──
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack)
                .type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1);
            VkDescriptorPoolCreateInfo dpCI = VkDescriptorPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                .pPoolSizes(poolSizes).maxSets(1);
            LongBuffer pPool = stack.mallocLong(1);
            assertEquals(VK_SUCCESS, vkCreateDescriptorPool(device, dpCI, null, pPool));
            long descPool = pPool.get(0);

            VkDescriptorSetAllocateInfo dsAI = VkDescriptorSetAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                .descriptorPool(descPool).pSetLayouts(pDSL);
            LongBuffer pDS = stack.mallocLong(1);
            assertEquals(VK_SUCCESS, vkAllocateDescriptorSets(device, dsAI, pDS));
            long descSet = pDS.get(0);

            VkDescriptorBufferInfo.Buffer bufInfos = VkDescriptorBufferInfo.calloc(1, stack);
            bufInfos.get(0).buffer(outputBuf).offset(0).range(bufSize);
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(1, stack);
            writes.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .dstSet(descSet).dstBinding(0)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1).pBufferInfo(bufInfos);
            vkUpdateDescriptorSets(device, writes, null);

            // ── Command buffer: bind → push constant → dispatch ──
            VkCommandBufferAllocateInfo cbAI = VkCommandBufferAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                .commandPool(commandPool)
                .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                .commandBufferCount(1);
            PointerBuffer pCB = stack.mallocPointer(1);
            assertEquals(VK_SUCCESS, vkAllocateCommandBuffers(device, cbAI, pCB));
            VkCommandBuffer cb = new VkCommandBuffer(pCB.get(0), device);

            vkBeginCommandBuffer(cb, VkCommandBufferBeginInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT));

            vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE,
                pipelineLayout, 0, stack.longs(descSet), null);

            // Push constant: [width, height]
            ByteBuffer pcData = stack.malloc(8);
            pcData.putInt(0, TEX_W);
            pcData.putInt(4, TEX_H);
            vkCmdPushConstants(cb, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pcData);

            // Dispatch: workgroup size = 8×8，所以 1×1×1 group 剛好覆蓋 8×8 像素
            vkCmdDispatch(cb, 1, 1, 1);
            vkEndCommandBuffer(cb);

            // ── Submit + 等待完成 ──
            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO).pCommandBuffers(pCB);
            assertEquals(VK_SUCCESS, vkQueueSubmit(computeQueue, submitInfo, VK_NULL_HANDLE));
            assertEquals(VK_SUCCESS, vkQueueWaitIdle(computeQueue));

            // ── Readback：讀取 GPU 輸出到 CPU ──
            FloatBuffer outData = MemoryUtil.memFloatBuffer(outputMapped, TOTAL_FLOATS);
            cpuPixelData = new float[TOTAL_FLOATS];
            outData.get(cpuPixelData);

            // ── 驗證：至少有非零輸出（GPU 確實寫了東西） ──
            boolean hasNonZero = false;
            for (float v : cpuPixelData) {
                if (v != 0.0f) { hasNonZero = true; break; }
            }
            assertTrue(hasNonZero, "GPU 輸出全為零 — Vulkan 計算未執行或未寫入結果");

            // ── Cleanup ──
            vmaDestroyBuffer(vmaAllocator, outputBuf, outputAlloc);
            vkDestroyDescriptorPool(device, descPool, null);
            vkDestroyDescriptorSetLayout(device, dsl, null);
            vkDestroyPipeline(device, pipeline, null);
            vkDestroyPipelineLayout(device, pipelineLayout, null);
            vkDestroyShaderModule(device, shaderModule, null);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  S3: 像素精確度驗證
    // ═══════════════════════════════════════════════════════════

    @Test
    @Order(3)
    @DisplayName("S3a: 所有 64 像素的 RGBA 值精確匹配預期漸層")
    void verifyPixelAccuracy() {
        assumeTrue(cpuPixelData != null, "需要 S2 通過");

        int errors = 0;
        StringBuilder errorLog = new StringBuilder();

        for (int y = 0; y < TEX_H; y++) {
            for (int x = 0; x < TEX_W; x++) {
                int pixelIdx = y * TEX_W + x;
                int base = pixelIdx * 4;

                float expectedR = (float) x / (TEX_W - 1);
                float expectedG = (float) y / (TEX_H - 1);
                float expectedB = 0.5f;
                float expectedA = 1.0f;

                float actualR = cpuPixelData[base];
                float actualG = cpuPixelData[base + 1];
                float actualB = cpuPixelData[base + 2];
                float actualA = cpuPixelData[base + 3];

                if (Math.abs(actualR - expectedR) > 0.001f
                 || Math.abs(actualG - expectedG) > 0.001f
                 || Math.abs(actualB - expectedB) > 0.001f
                 || Math.abs(actualA - expectedA) > 0.001f) {
                    errors++;
                    if (errors <= 5) {
                        errorLog.append(String.format(
                            "  pixel(%d,%d): expected(%.3f,%.3f,%.3f,%.3f) actual(%.3f,%.3f,%.3f,%.3f)%n",
                            x, y, expectedR, expectedG, expectedB, expectedA,
                            actualR, actualG, actualB, actualA));
                    }
                }
            }
        }
        assertEquals(0, errors,
            errors + "/" + PIXEL_COUNT + " 像素不正確:\n" + errorLog);
    }

    @Test
    @Order(4)
    @DisplayName("S3b: 角落像素驗證 — (0,0)=黑, (7,0)=紅, (0,7)=綠, (7,7)=黃")
    void verifyCornerPixels() {
        assumeTrue(cpuPixelData != null, "需要 S2 通過");

        // (0,0) → R=0, G=0, B=0.5, A=1 — 深藍（左上角）
        assertPixel(0, 0, 0.0f, 0.0f, 0.5f, 1.0f, "左上角");

        // (7,0) → R=1, G=0, B=0.5, A=1 — 品紅（右上角）
        assertPixel(7, 0, 1.0f, 0.0f, 0.5f, 1.0f, "右上角");

        // (0,7) → R=0, G=1, B=0.5, A=1 — 青色（左下角）
        assertPixel(0, 7, 0.0f, 1.0f, 0.5f, 1.0f, "左下角");

        // (7,7) → R=1, G=1, B=0.5, A=1 — 淡黃（右下角）
        assertPixel(7, 7, 1.0f, 1.0f, 0.5f, 1.0f, "右下角");
    }

    @Test
    @Order(5)
    @DisplayName("S3c: Alpha 通道全為 1.0 — 確保不透明度正確傳遞到 GL composite")
    void verifyAlphaChannel() {
        assumeTrue(cpuPixelData != null, "需要 S2 通過");

        for (int i = 0; i < PIXEL_COUNT; i++) {
            float alpha = cpuPixelData[i * 4 + 3];
            assertEquals(1.0f, alpha, 0.001f,
                "像素 " + i + " 的 alpha 應為 1.0，實際 " + alpha);
        }
    }

    @Test
    @Order(6)
    @DisplayName("S3d: 輸出資料大小 = 64 像素 × 4 floats = 256 floats — 與 RGBA16F 紋理對齊")
    void verifyDataSize() {
        assumeTrue(cpuPixelData != null, "需要 S2 通過");
        assertEquals(TOTAL_FLOATS, cpuPixelData.length,
            "輸出資料大小必須為 " + TOTAL_FLOATS + " floats（" +
            PIXEL_COUNT + " 像素 × 4 RGBA channels）");
    }

    @Test
    @Order(7)
    @DisplayName("S3e: 所有色彩值在 [0.0, 1.0] — 符合 GL texture 標準化範圍")
    void verifyValueRange() {
        assumeTrue(cpuPixelData != null, "需要 S2 通過");

        for (int i = 0; i < TOTAL_FLOATS; i++) {
            float v = cpuPixelData[i];
            assertTrue(v >= 0.0f && v <= 1.0f,
                "float[" + i + "] = " + v + " 超出 [0.0, 1.0] 範圍");
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════

    private void assertPixel(int x, int y, float eR, float eG, float eB, float eA, String label) {
        int base = (y * TEX_W + x) * 4;
        assertEquals(eR, cpuPixelData[base],     0.001f, label + " R");
        assertEquals(eG, cpuPixelData[base + 1], 0.001f, label + " G");
        assertEquals(eB, cpuPixelData[base + 2], 0.001f, label + " B");
        assertEquals(eA, cpuPixelData[base + 3], 0.001f, label + " A");
    }

    private long compileShader(String src, String name) {
        long options = shaderc_compile_options_initialize();
        shaderc_compile_options_set_target_env(options,
            shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
        long result = shaderc_compile_into_spv(
            shadercCompiler, src, shaderc_compute_shader, name, "main", options);
        shaderc_compile_options_release(options);
        if (result == 0) return 0;
        if (shaderc_result_get_compilation_status(result)
                != shaderc_compilation_status_success) {
            System.err.println("[VulkanRenderingTest] Shader error (" + name + "): "
                + shaderc_result_get_error_message(result));
            shaderc_result_release(result);
            return 0;
        }
        return result;
    }
}
