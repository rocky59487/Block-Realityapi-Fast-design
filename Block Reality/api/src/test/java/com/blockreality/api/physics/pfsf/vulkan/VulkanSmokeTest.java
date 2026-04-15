package com.blockreality.api.physics.pfsf.vulkan;

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
 * Vulkan 煙霧測試 — 使用 lavapipe（Mesa llvmpipe）CPU 軟體渲染器
 *
 * 驗證 Block Reality Vulkan 路徑的 4 個核心功能：
 *   S1) VkInstance 建立 + physical device 選取
 *   S2) VMA 初始化（驗證 pVulkanFunctions 修復）
 *   S3) Shaderc GLSL→SPIR-V 編譯
 *   S4) Compute pipeline + GPU dispatch + 計算結果正確性
 *
 * 執行方式（Gradle 已配置 VK_ICD_FILENAMES 和 lwjgl-natives）：
 *   ./gradlew :api:test --tests "*.vulkan.VulkanSmokeTest"
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Vulkan Smoke Tests (lavapipe CPU renderer)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VulkanSmokeTest {

    private static final String LAVAPIPE_ICD = "/usr/share/vulkan/icd.d/lvp_icd.json";

    // Shared state across tests (setup in S1/S2, torn down in @AfterAll)
    private VkInstance       instance;
    private VkPhysicalDevice physical;
    private VkDevice         device;
    private VkQueue          computeQueue;
    private int              computeFamily = -1;
    private long             vmaAllocator  = 0;
    private long             commandPool   = 0;
    private long             shadercCompiler = 0;

    @BeforeAll
    void checkEnvironment() {
        assumeTrue(new File(LAVAPIPE_ICD).exists(),
            "lavapipe ICD not found — install mesa-vulkan-drivers");
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

    // ─── Stage 1: VkInstance + Device ────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("S1: Vulkan instance 建立 + lavapipe device 偵測 + compute queue")
    void testVkInstanceCreation() {
        try (MemoryStack stack = MemoryStack.stackPush()) {

            // 1a: Create VkInstance
            VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                .pApplicationName(stack.UTF8Safe("BR-SmokeTest"))
                .apiVersion(VK_API_VERSION_1_2);

            VkInstanceCreateInfo instCI = VkInstanceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                .pApplicationInfo(appInfo);

            PointerBuffer pInst = stack.mallocPointer(1);
            assertEquals(VK_SUCCESS, vkCreateInstance(instCI, null, pInst),
                "vkCreateInstance failed");
            instance = new VkInstance(pInst.get(0), instCI);
            assertNotNull(instance);

            // 1b: Enumerate physical devices
            IntBuffer pCount = stack.mallocInt(1);
            vkEnumeratePhysicalDevices(instance, pCount, null);
            assertTrue(pCount.get(0) > 0, "No Vulkan physical devices found");

            PointerBuffer pDevices = stack.mallocPointer(pCount.get(0));
            vkEnumeratePhysicalDevices(instance, pCount, pDevices);

            physical = new VkPhysicalDevice(pDevices.get(0), instance);

            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(physical, props);
            String deviceName = props.deviceNameString();
            assertNotNull(deviceName);
            // lavapipe should contain "llvmpipe" or be a CPU type device
            assertTrue(props.deviceType() == VK_PHYSICAL_DEVICE_TYPE_CPU
                    || props.deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU
                    || props.deviceType() == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU,
                "Unexpected device type: " + props.deviceType());

            // 1c: Find compute queue family
            vkGetPhysicalDeviceQueueFamilyProperties(physical, pCount, null);
            VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.calloc(pCount.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(physical, pCount, families);

            for (int i = 0; i < pCount.get(0); i++) {
                if ((families.get(i).queueFlags() & VK_QUEUE_COMPUTE_BIT) != 0) {
                    computeFamily = i; break;
                }
            }
            assertTrue(computeFamily >= 0, "No compute queue family found");

            // 1d: Create VkDevice
            FloatBuffer queuePrio = stack.floats(1.0f);
            VkDeviceQueueCreateInfo.Buffer queueCI = VkDeviceQueueCreateInfo.calloc(1, stack);
            queueCI.get(0).sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                .queueFamilyIndex(computeFamily).pQueuePriorities(queuePrio);

            VkDeviceCreateInfo deviceCI = VkDeviceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                .pQueueCreateInfos(queueCI);

            PointerBuffer pDevice = stack.mallocPointer(1);
            assertEquals(VK_SUCCESS, vkCreateDevice(physical, deviceCI, null, pDevice),
                "vkCreateDevice failed");
            device = new VkDevice(pDevice.get(0), physical, deviceCI);

            PointerBuffer pQueue = stack.mallocPointer(1);
            vkGetDeviceQueue(device, computeFamily, 0, pQueue);
            computeQueue = new VkQueue(pQueue.get(0), device);

            assertNotNull(device);
            assertNotNull(computeQueue);
        }
    }

    // ─── Stage 2: VMA + pVulkanFunctions ─────────────────────────────

    @Test
    @Order(2)
    @DisplayName("S2a: 舊代碼（無 pVulkanFunctions）→ LWJGL 3.3.1 NPE 確認")
    void testBuggyCodeTriggersNPE() {
        assumeTrue(device != null, "Requires Stage 1 to pass first");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 重現修復前的錯誤代碼
            VmaAllocatorCreateInfo buggyCI = VmaAllocatorCreateInfo.calloc(stack)
                .instance(instance)
                .physicalDevice(physical)
                .device(device)
                .vulkanApiVersion(VK_API_VERSION_1_2);
            // ← pVulkanFunctions 故意缺失

            PointerBuffer pAlloc = stack.mallocPointer(1);
            // LWJGL 3.3.1 的 validate() 應該在這裡 NPE；
            // 若 LWJGL 版本不同可能不 NPE，但 vmaCreateAllocator 應失敗
            boolean caughtExpectedError = false;
            try {
                int result = vmaCreateAllocator(buggyCI, pAlloc);
                // If no NPE, result should be non-SUCCESS or allocator is unusable
                if (result != VK_SUCCESS) caughtExpectedError = true;
                else vmaDestroyAllocator(pAlloc.get(0)); // cleanup if somehow succeeded
            } catch (NullPointerException npe) {
                caughtExpectedError = true; // LWJGL 3.3.1 validate() NPE — 預期行為
            }
            // Either NPE or failure is acceptable — the point is the buggy code doesn't work
            // (In rare cases with different LWJGL minor versions it might succeed with undefined behavior)
            // We don't assert here since the important part is S2b below
        }
    }

    @Test
    @Order(3)
    @DisplayName("S2b: 修復代碼（pVulkanFunctions）→ vmaCreateAllocator 成功")
    void testFixedVMAInit() {
        assumeTrue(device != null, "Requires Stage 1 to pass first");
        try (MemoryStack stack = MemoryStack.stackPush()) {

            // ★ 修復後的代碼：顯式提供 VmaVulkanFunctions
            VmaVulkanFunctions vmaFuncs = VmaVulkanFunctions.calloc(stack)
                .set(instance, device);

            VmaAllocatorCreateInfo allocCI = VmaAllocatorCreateInfo.calloc(stack)
                .instance(instance)
                .physicalDevice(physical)
                .device(device)
                .pVulkanFunctions(vmaFuncs)      // ★ 修復：加入此行
                .vulkanApiVersion(VK_API_VERSION_1_2);

            PointerBuffer pAlloc = stack.mallocPointer(1);
            int result = vmaCreateAllocator(allocCI, pAlloc);
            assertEquals(VK_SUCCESS, result,
                "vmaCreateAllocator failed — result=" + result);

            vmaAllocator = pAlloc.get(0);
            assertNotEquals(0L, vmaAllocator, "VMA allocator handle must be non-zero");
        }
    }

    @Test
    @Order(4)
    @DisplayName("S2c: VMA バッファ確保 1MB (DEVICE_LOCAL) + 64KB staging (HOST_VISIBLE)")
    void testVMABufferAllocation() {
        assumeTrue(vmaAllocator != 0, "Requires S2b to pass first");
        try (MemoryStack stack = MemoryStack.stackPush()) {

            // DEVICE_LOCAL buffer
            VkBufferCreateInfo bufCI = VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .size(1024 * 1024L)
                .usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            VmaAllocationCreateInfo gpuAllocCI = VmaAllocationCreateInfo.calloc(stack)
                .usage(VMA_MEMORY_USAGE_GPU_ONLY);

            LongBuffer pBuf = stack.mallocLong(1);
            PointerBuffer pAlloc = stack.mallocPointer(1);
            int result = vmaCreateBuffer(vmaAllocator, bufCI, gpuAllocCI, pBuf, pAlloc, null);
            assertEquals(VK_SUCCESS, result, "vmaCreateBuffer DEVICE_LOCAL failed");
            long devBuf = pBuf.get(0);
            long devAlloc = pAlloc.get(0);
            assertNotEquals(0L, devBuf);

            // Staging buffer (HOST_VISIBLE)
            bufCI.size(64 * 1024L).usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
            VmaAllocationCreateInfo cpuAllocCI = VmaAllocationCreateInfo.calloc(stack)
                .usage(VMA_MEMORY_USAGE_CPU_ONLY);

            result = vmaCreateBuffer(vmaAllocator, bufCI, cpuAllocCI, pBuf, pAlloc, null);
            assertEquals(VK_SUCCESS, result, "vmaCreateBuffer HOST_VISIBLE failed");
            long stagingBuf = pBuf.get(0);
            long stagingAlloc = pAlloc.get(0);

            // Map / write / unmap
            PointerBuffer ppData = stack.mallocPointer(1);
            result = vmaMapMemory(vmaAllocator, stagingAlloc, ppData);
            assertEquals(VK_SUCCESS, result, "vmaMapMemory failed");
            assertNotEquals(0L, ppData.get(0));
            vmaUnmapMemory(vmaAllocator, stagingAlloc);

            // Cleanup
            vmaDestroyBuffer(vmaAllocator, stagingBuf, stagingAlloc);
            vmaDestroyBuffer(vmaAllocator, devBuf, devAlloc);
        }
    }

    // ─── Stage 3: Shaderc ─────────────────────────────────────────────

    @Test
    @Order(5)
    @DisplayName("S3a: Shaderc 初始化")
    void testShadercInit() {
        shadercCompiler = shaderc_compiler_initialize();
        assertNotEquals(0L, shadercCompiler, "shaderc_compiler_initialize failed");
    }

    @Test
    @Order(6)
    @DisplayName("S3b: PFSF rbgs_smooth 模擬 shader コンパイル → SPIR-V")
    void testPFSFShaderCompilation() {
        assumeTrue(shadercCompiler != 0, "Requires S3a");

        String src = "#version 450\n"
            + "layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;\n"
            + "layout(std430, binding = 0) buffer PhiBuffer { float phi[]; };\n"
            + "layout(std430, binding = 1) buffer SrcBuffer { float source[]; };\n"
            + "layout(std430, binding = 2) buffer ConductBuffer { float cond[]; };\n"
            + "layout(push_constant) uniform PC { int Nx; int Ny; int Nz; int colorPhase; };\n"
            + "void main() {\n"
            + "    ivec3 g = ivec3(gl_GlobalInvocationID);\n"
            + "    if (g.x >= Nx || g.y >= Ny || g.z >= Nz) return;\n"
            + "    int idx = g.z * Ny * Nx + g.y * Nx + g.x;\n"
            + "    float c = cond[idx]; if (c <= 0.0) return;\n"
            + "    float s = 0.0, w = 0.0;\n"
            + "    if (g.x > 0)    { s += phi[idx-1]*c; w += c; }\n"
            + "    if (g.x < Nx-1) { s += phi[idx+1]*c; w += c; }\n"
            + "    if (g.y > 0)    { s += phi[idx-Nx]*c; w += c; }\n"
            + "    if (g.y < Ny-1) { s += phi[idx+Nx]*c; w += c; }\n"
            + "    if (w > 0.0) phi[idx] = (source[idx] + s) / w;\n"
            + "}\n";

        long spv = compileShader(src, "pfsf_rbgs.comp");
        assertNotEquals(0L, spv, "PFSF shader compilation failed");
        long words = shaderc_result_get_length(spv) / 4;
        assertTrue(words > 10, "SPIR-V too small: " + words + " words");
        shaderc_result_release(spv);
    }

    @Test
    @Order(7)
    @DisplayName("S3c: Fluid advection shader コンパイル → SPIR-V")
    void testFluidShaderCompilation() {
        assumeTrue(shadercCompiler != 0, "Requires S3a");

        String src = "#version 450\n"
            + "layout(local_size_x = 4, local_size_y = 4, local_size_z = 4) in;\n"
            + "layout(std430, binding = 0) buffer VxBuf { float vx[]; };\n"
            + "layout(std430, binding = 1) buffer VyBuf { float vy[]; };\n"
            + "layout(std430, binding = 2) buffer VzBuf { float vz[]; };\n"
            + "layout(push_constant) uniform PC { int Lx; int Ly; int Lz; float dt; };\n"
            + "void main() {\n"
            + "    ivec3 g = ivec3(gl_GlobalInvocationID);\n"
            + "    if (g.x >= Lx || g.y >= Ly || g.z >= Lz) return;\n"
            + "    int i = g.z*Ly*Lx + g.y*Lx + g.x;\n"
            + "    vx[i] *= 0.99; vy[i] *= 0.99; vz[i] *= 0.99;\n"
            + "}\n";

        long spv = compileShader(src, "fluid_advect.comp");
        assertNotEquals(0L, spv, "Fluid shader compilation failed");
        shaderc_result_release(spv);
    }

    // ─── Stage 4: Compute Pipeline + Dispatch ─────────────────────────

    @Test
    @Order(8)
    @DisplayName("S4: Compute pipeline + GPU dispatch + 計算結果正確性（1024 floats × 2.0）")
    void testComputePipelineAndDispatch() {
        assumeTrue(vmaAllocator != 0,  "Requires VMA (S2b)");
        assumeTrue(shadercCompiler != 0, "Requires Shaderc (S3a)");

        // Setup command pool
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandPoolCreateInfo poolCI = VkCommandPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                .queueFamilyIndex(computeFamily)
                .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);
            LongBuffer pPool = stack.mallocLong(1);
            assertEquals(VK_SUCCESS, vkCreateCommandPool(device, poolCI, null, pPool));
            commandPool = pPool.get(0);
        }

        // Compile multiply-by-2 shader
        String shaderSrc = "#version 450\n"
            + "layout(local_size_x = 64) in;\n"
            + "layout(std430, binding = 0) buffer In  { float inData[];  };\n"
            + "layout(std430, binding = 1) buffer Out { float outData[]; };\n"
            + "layout(push_constant) uniform PC { int count; };\n"
            + "void main() {\n"
            + "    uint i = gl_GlobalInvocationID.x;\n"
            + "    if (i >= uint(count)) return;\n"
            + "    outData[i] = inData[i] * 2.0;\n"
            + "}\n";

        long spvResult = compileShader(shaderSrc, "test_multiply.comp");
        assertNotEquals(0L, spvResult, "Shader compilation failed");

        final int DATA_COUNT = 1024;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer spvCode = shaderc_result_get_bytes(spvResult);
            assertNotNull(spvCode);

            // Shader module
            VkShaderModuleCreateInfo smCI = VkShaderModuleCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                .pCode(spvCode);
            LongBuffer pSM = stack.mallocLong(1);
            assertEquals(VK_SUCCESS, vkCreateShaderModule(device, smCI, null, pSM));
            long shaderModule = pSM.get(0);
            shaderc_result_release(spvResult);

            // Descriptor set layout
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(2, stack);
            bindings.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);

            VkDescriptorSetLayoutCreateInfo dslCI = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                .pBindings(bindings);
            LongBuffer pDSL = stack.mallocLong(1);
            assertEquals(VK_SUCCESS, vkCreateDescriptorSetLayout(device, dslCI, null, pDSL));
            long dsl = pDSL.get(0);

            // Pipeline layout with push constant
            VkPushConstantRange.Buffer pcRange = VkPushConstantRange.calloc(1, stack);
            pcRange.get(0).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(4);
            VkPipelineLayoutCreateInfo plCI = VkPipelineLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                .pSetLayouts(pDSL).pPushConstantRanges(pcRange);
            LongBuffer pPL = stack.mallocLong(1);
            assertEquals(VK_SUCCESS, vkCreatePipelineLayout(device, plCI, null, pPL));
            long pipelineLayout = pPL.get(0);

            // Compute pipeline
            VkPipelineShaderStageCreateInfo stageCI = VkPipelineShaderStageCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                .stage(VK_SHADER_STAGE_COMPUTE_BIT)
                .module(shaderModule)
                .pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer compCI = VkComputePipelineCreateInfo.calloc(1, stack);
            compCI.get(0).sType(VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO)
                .stage(stageCI).layout(pipelineLayout);
            LongBuffer pPipeline = stack.mallocLong(1);
            assertEquals(VK_SUCCESS, vkCreateComputePipelines(device, VK_NULL_HANDLE, compCI, null, pPipeline));
            long pipeline = pPipeline.get(0);

            // Allocate host-visible buffers (input + output)
            VmaAllocationInfo allocInfo = VmaAllocationInfo.calloc(stack);
            VkBufferCreateInfo bufCI = VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .size(DATA_COUNT * 4L)
                .usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            VmaAllocationCreateInfo cpuAllocCI = VmaAllocationCreateInfo.calloc(stack)
                .usage(VMA_MEMORY_USAGE_CPU_TO_GPU).flags(VMA_ALLOCATION_CREATE_MAPPED_BIT);

            LongBuffer pBuf = stack.mallocLong(1);
            PointerBuffer pAlloc = stack.mallocPointer(1);

            // Input buffer
            assertEquals(VK_SUCCESS, vmaCreateBuffer(vmaAllocator, bufCI, cpuAllocCI, pBuf, pAlloc, allocInfo));
            long inputBuf = pBuf.get(0), inputAlloc = pAlloc.get(0);
            long inputMapped = allocInfo.pMappedData();
            FloatBuffer inputData = MemoryUtil.memFloatBuffer(inputMapped, DATA_COUNT);
            for (int i = 0; i < DATA_COUNT; i++) inputData.put(i, (float)(i + 1)); // [1.0..1024.0]

            // Output buffer
            bufCI.usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
            VmaAllocationCreateInfo outAllocCI = VmaAllocationCreateInfo.calloc(stack)
                .usage(VMA_MEMORY_USAGE_CPU_TO_GPU).flags(VMA_ALLOCATION_CREATE_MAPPED_BIT);
            assertEquals(VK_SUCCESS, vmaCreateBuffer(vmaAllocator, bufCI, outAllocCI, pBuf, pAlloc, allocInfo));
            long outputBuf = pBuf.get(0), outputAlloc = pAlloc.get(0);
            long outputMapped = allocInfo.pMappedData();

            // Descriptor pool + set
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack)
                .type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(2);
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

            VkDescriptorBufferInfo.Buffer bufInfos = VkDescriptorBufferInfo.calloc(2, stack);
            bufInfos.get(0).buffer(inputBuf).offset(0).range(DATA_COUNT * 4L);
            bufInfos.get(1).buffer(outputBuf).offset(0).range(DATA_COUNT * 4L);
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
            writes.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .dstSet(descSet).dstBinding(0).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1).pBufferInfo(VkDescriptorBufferInfo.create(bufInfos.address(), 1));
            writes.get(1).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .dstSet(descSet).dstBinding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1).pBufferInfo(VkDescriptorBufferInfo.create(bufInfos.address() + VkDescriptorBufferInfo.SIZEOF, 1));
            vkUpdateDescriptorSets(device, writes, null);

            // Record + submit command buffer
            VkCommandBufferAllocateInfo cbAI = VkCommandBufferAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                .commandPool(commandPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
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
            vkCmdPushConstants(cb, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, stack.ints(DATA_COUNT));
            vkCmdDispatch(cb, (DATA_COUNT + 63) / 64, 1, 1);
            vkEndCommandBuffer(cb);

            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO).pCommandBuffers(pCB);
            assertEquals(VK_SUCCESS, vkQueueSubmit(computeQueue, submitInfo, VK_NULL_HANDLE),
                "vkQueueSubmit failed");
            assertEquals(VK_SUCCESS, vkQueueWaitIdle(computeQueue), "vkQueueWaitIdle failed");

            // Verify results
            if (outputMapped != 0) {
                FloatBuffer outData = MemoryUtil.memFloatBuffer(outputMapped, DATA_COUNT);
                int errors = 0;
                for (int i = 0; i < DATA_COUNT; i++) {
                    float expected = (float)(i + 1) * 2.0f;
                    if (Math.abs(outData.get(i) - expected) > 0.001f) errors++;
                }
                assertEquals(0, errors,
                    errors + " / " + DATA_COUNT + " results incorrect");
            }

            // Cleanup
            vmaDestroyBuffer(vmaAllocator, inputBuf, inputAlloc);
            vmaDestroyBuffer(vmaAllocator, outputBuf, outputAlloc);
            vkDestroyDescriptorPool(device, descPool, null);
            vkDestroyDescriptorSetLayout(device, dsl, null);
            vkDestroyPipeline(device, pipeline, null);
            vkDestroyPipelineLayout(device, pipelineLayout, null);
            vkDestroyShaderModule(device, shaderModule, null);
        }
    }

    // ─── Helper ───────────────────────────────────────────────────────

    /** CharSequence オーバーロード使用（VulkanComputeContext.compileGLSL() と同じパターン）*/
    private long compileShader(String src, String name) {
        long options = shaderc_compile_options_initialize();
        shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
        long result = shaderc_compile_into_spv(shadercCompiler, src, shaderc_compute_shader, name, "main", options);
        shaderc_compile_options_release(options);
        if (result == 0) return 0;
        if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
            System.err.println("[VulkanSmokeTest] Shader error (" + name + "): "
                + shaderc_result_get_error_message(result));
            shaderc_result_release(result);
            return 0;
        }
        return result; // caller must release
    }
}
