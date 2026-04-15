import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.util.vma.*;
import org.lwjgl.vulkan.*;

import java.nio.*;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * Stage 4: Compute Pipeline + Descriptor Set + Command Buffer 実行
 * Block Reality の物理計算パイプラインの完全模擬テスト
 *
 * 検証内容:
 *  D1) VkComputePipeline 作成
 *  D2) VkDescriptorSet バインド
 *  D3) vkCmdDispatch 送信
 *  D4) GPU 結果の CPU 読み取り (lavapipe ソフトウェアレンダラー)
 */
public class Stage4_ComputePipeline {

    static final String PASS = "  [PASS] ";
    static final String FAIL = "  [FAIL] ";
    static final String INFO = "  [INFO] ";

    // シンプルなテスト用 compute shader: 各要素に 2.0 を掛ける
    static final String TEST_SHADER = """
        #version 450
        layout(local_size_x = 64) in;

        layout(std430, binding = 0) buffer InputBuf  { float inData[];  };
        layout(std430, binding = 1) buffer OutputBuf { float outData[]; };

        layout(push_constant) uniform PC { int count; };

        void main() {
            uint i = gl_GlobalInvocationID.x;
            if (i >= uint(count)) return;
            outData[i] = inData[i] * 2.0;
        }
        """;

    static final int DATA_COUNT = 1024;  // 1024 floats

    // Shared state
    static VkInstance       instance;
    static VkPhysicalDevice physical;
    static VkDevice         device;
    static VkQueue          computeQueue;
    static int              computeFamily;
    static long             vmaAllocator;
    static long             commandPool;
    static long             shadercCompiler;

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║  Stage 4: Compute Pipeline + GPU Dispatch + 結果検証     ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();

        System.setProperty("org.lwjgl.librarypath", "/tmp/vk_smoke_test/natives");

        boolean ok = false;
        try {
            ok = run();
        } catch (Throwable t) {
            System.out.println(FAIL + "Uncaught exception: " + t);
            t.printStackTrace();
        } finally {
            cleanup();
        }

        System.out.println();
        System.out.println(ok ? "Stage 4: PASSED" : "Stage 4: FAILED");
        System.exit(ok ? 0 : 1);
    }

    static boolean run() {
        if (!setupVulkan()) return false;
        if (!setupVMA()) return false;
        if (!setupCommandPool()) return false;
        if (!setupShaderc()) return false;
        return runComputeTest();
    }

    // ─── Setup ───────────────────────────────────────────────────────

    static boolean setupVulkan() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                .pApplicationName(stack.UTF8Safe("Stage4"))
                .apiVersion(VK_API_VERSION_1_2);

            VkInstanceCreateInfo instCI = VkInstanceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                .pApplicationInfo(appInfo);

            PointerBuffer pInst = stack.mallocPointer(1);
            if (vkCreateInstance(instCI, null, pInst) != VK_SUCCESS) {
                System.out.println(FAIL + "vkCreateInstance"); return false;
            }
            instance = new VkInstance(pInst.get(0), instCI);

            IntBuffer pCount = stack.mallocInt(1);
            vkEnumeratePhysicalDevices(instance, pCount, null);
            PointerBuffer pDevices = stack.mallocPointer(pCount.get(0));
            vkEnumeratePhysicalDevices(instance, pCount, pDevices);

            physical = new VkPhysicalDevice(pDevices.get(0), instance);
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(physical, props);
            System.out.println(INFO + "Device: " + props.deviceNameString());

            // Find compute queue family
            vkGetPhysicalDeviceQueueFamilyProperties(physical, pCount, null);
            VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.calloc(pCount.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(physical, pCount, families);
            computeFamily = -1;
            for (int i = 0; i < pCount.get(0); i++) {
                if ((families.get(i).queueFlags() & VK_QUEUE_COMPUTE_BIT) != 0) {
                    computeFamily = i; break;
                }
            }
            if (computeFamily < 0) { System.out.println(FAIL + "No compute queue"); return false; }

            FloatBuffer queuePrio = stack.floats(1.0f);
            VkDeviceQueueCreateInfo.Buffer queueCI = VkDeviceQueueCreateInfo.calloc(1, stack);
            queueCI.get(0).sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                .queueFamilyIndex(computeFamily).pQueuePriorities(queuePrio);

            VkDeviceCreateInfo deviceCI = VkDeviceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                .pQueueCreateInfos(queueCI);

            PointerBuffer pDevice = stack.mallocPointer(1);
            if (vkCreateDevice(physical, deviceCI, null, pDevice) != VK_SUCCESS) {
                System.out.println(FAIL + "vkCreateDevice"); return false;
            }
            device = new VkDevice(pDevice.get(0), physical, deviceCI);

            PointerBuffer pQueue = stack.mallocPointer(1);
            vkGetDeviceQueue(device, computeFamily, 0, pQueue);
            computeQueue = new VkQueue(pQueue.get(0), device);

            System.out.println(PASS + "VkDevice + compute queue ready");
            return true;
        }
    }

    static boolean setupVMA() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VmaVulkanFunctions vmaFuncs = VmaVulkanFunctions.calloc(stack)
                .set(instance, device);  // ★ FIXED: pVulkanFunctions

            VmaAllocatorCreateInfo allocCI = VmaAllocatorCreateInfo.calloc(stack)
                .instance(instance)
                .physicalDevice(physical)
                .device(device)
                .pVulkanFunctions(vmaFuncs)
                .vulkanApiVersion(VK_API_VERSION_1_2);

            PointerBuffer pAlloc = stack.mallocPointer(1);
            int result = vmaCreateAllocator(allocCI, pAlloc);
            if (result != VK_SUCCESS) {
                System.out.println(FAIL + "vmaCreateAllocator: " + result); return false;
            }
            vmaAllocator = pAlloc.get(0);
            System.out.println(PASS + "VMA allocator ready");
            return true;
        }
    }

    static boolean setupCommandPool() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandPoolCreateInfo poolCI = VkCommandPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                .queueFamilyIndex(computeFamily)
                .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);

            LongBuffer pPool = stack.mallocLong(1);
            if (vkCreateCommandPool(device, poolCI, null, pPool) != VK_SUCCESS) {
                System.out.println(FAIL + "vkCreateCommandPool"); return false;
            }
            commandPool = pPool.get(0);
            System.out.println(PASS + "Command pool ready");
            return true;
        }
    }

    static boolean setupShaderc() {
        shadercCompiler = shaderc_compiler_initialize();
        if (shadercCompiler == 0) {
            System.out.println(FAIL + "shaderc_compiler_initialize"); return false;
        }
        System.out.println(PASS + "Shaderc compiler ready");
        return true;
    }

    // ─── Main compute test ────────────────────────────────────────────

    static boolean runComputeTest() {
        System.out.println();
        System.out.println("  ── D1: Compute Pipeline 作成 ──");

        // Compile shader
        long spvResult = compileShader(TEST_SHADER, "test_multiply.comp");
        if (spvResult == 0) return false;

        ByteBuffer spvCode = shaderc_result_get_bytes(spvResult);
        System.out.println(PASS + "SPIR-V: " + spvCode.remaining() / 4 + " words");

        long shaderModule;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkShaderModuleCreateInfo smCI = VkShaderModuleCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                .pCode(spvCode);

            LongBuffer pSM = stack.mallocLong(1);
            if (vkCreateShaderModule(device, smCI, null, pSM) != VK_SUCCESS) {
                System.out.println(FAIL + "vkCreateShaderModule"); return false;
            }
            shaderModule = pSM.get(0);
            System.out.println(PASS + "Shader module: 0x" + Long.toHexString(shaderModule));
        }
        shaderc_result_release(spvResult);

        // Descriptor set layout (binding 0 + 1 = storage buffers)
        long dsl;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(2, stack);
            bindings.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);

            VkDescriptorSetLayoutCreateInfo dslCI = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                .pBindings(bindings);

            LongBuffer pDSL = stack.mallocLong(1);
            if (vkCreateDescriptorSetLayout(device, dslCI, null, pDSL) != VK_SUCCESS) {
                System.out.println(FAIL + "vkCreateDescriptorSetLayout"); return false;
            }
            dsl = pDSL.get(0);

            // Push constant range
            VkPushConstantRange.Buffer pcRange = VkPushConstantRange.calloc(1, stack);
            pcRange.get(0).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(4);

            VkPipelineLayoutCreateInfo plCI = VkPipelineLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                .pSetLayouts(pDSL)
                .pPushConstantRanges(pcRange);

            LongBuffer pLayout = stack.mallocLong(1);
            if (vkCreatePipelineLayout(device, plCI, null, pLayout) != VK_SUCCESS) {
                System.out.println(FAIL + "vkCreatePipelineLayout"); return false;
            }
            long pipelineLayout = pLayout.get(0);
            System.out.println(PASS + "Pipeline layout: 0x" + Long.toHexString(pipelineLayout));

            // Compute pipeline
            VkPipelineShaderStageCreateInfo stageCI = VkPipelineShaderStageCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                .stage(VK_SHADER_STAGE_COMPUTE_BIT)
                .module(shaderModule)
                .pName(stack.UTF8("main"));

            VkComputePipelineCreateInfo.Buffer compPipeCI = VkComputePipelineCreateInfo.calloc(1, stack);
            compPipeCI.get(0).sType(VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO)
                .stage(stageCI)
                .layout(pipelineLayout);

            LongBuffer pPipeline = stack.mallocLong(1);
            if (vkCreateComputePipelines(device, VK_NULL_HANDLE, compPipeCI, null, pPipeline) != VK_SUCCESS) {
                System.out.println(FAIL + "vkCreateComputePipelines"); return false;
            }
            long pipeline = pPipeline.get(0);
            System.out.println(PASS + "Compute pipeline created: 0x" + Long.toHexString(pipeline));

            System.out.println();
            System.out.println("  ── D2: VMA バッファ + Descriptor Set ──");

            // Allocate input buffer (HOST_VISIBLE for upload)
            VkBufferCreateInfo stagingCI = VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .size(DATA_COUNT * 4L)
                .usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            VmaAllocationCreateInfo cpuAllocCI = VmaAllocationCreateInfo.calloc(stack)
                .usage(VMA_MEMORY_USAGE_CPU_TO_GPU)
                .flags(VMA_ALLOCATION_CREATE_MAPPED_BIT);

            LongBuffer pBuf = stack.mallocLong(1);
            PointerBuffer pAlloc = stack.mallocPointer(1);
            VmaAllocationInfo allocInfo = VmaAllocationInfo.calloc(stack);

            if (vmaCreateBuffer(vmaAllocator, stagingCI, cpuAllocCI, pBuf, pAlloc, allocInfo) != VK_SUCCESS) {
                System.out.println(FAIL + "vmaCreateBuffer (input)"); return false;
            }
            long inputBuf = pBuf.get(0);
            long inputAlloc = pAlloc.get(0);

            // Write test data [1.0, 2.0, 3.0, ..., 1024.0]
            long mappedPtr = allocInfo.pMappedData();
            if (mappedPtr != 0) {
                FloatBuffer mapped = MemoryUtil.memFloatBuffer(mappedPtr, DATA_COUNT);
                for (int i = 0; i < DATA_COUNT; i++) mapped.put(i, (float)(i + 1));
                System.out.println(PASS + "Input buffer: " + DATA_COUNT + " floats written (1.0 ~ " + DATA_COUNT + ".0)");
            }

            // Output buffer (GPU_ONLY, read back via staging)
            VkBufferCreateInfo outCI = VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .size(DATA_COUNT * 4L)
                .usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            VmaAllocationCreateInfo gpuAllocCI = VmaAllocationCreateInfo.calloc(stack)
                .usage(VMA_MEMORY_USAGE_CPU_TO_GPU)
                .flags(VMA_ALLOCATION_CREATE_MAPPED_BIT);

            if (vmaCreateBuffer(vmaAllocator, outCI, gpuAllocCI, pBuf, pAlloc, allocInfo) != VK_SUCCESS) {
                System.out.println(FAIL + "vmaCreateBuffer (output)"); return false;
            }
            long outputBuf   = pBuf.get(0);
            long outputAlloc = pAlloc.get(0);
            long outputMapped = allocInfo.pMappedData();
            System.out.println(PASS + "Output buffer allocated (GPU side)");

            // Descriptor pool + set
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack)
                .type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(2);

            VkDescriptorPoolCreateInfo dpCI = VkDescriptorPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                .pPoolSizes(poolSizes).maxSets(1);

            LongBuffer pPool = stack.mallocLong(1);
            if (vkCreateDescriptorPool(device, dpCI, null, pPool) != VK_SUCCESS) {
                System.out.println(FAIL + "vkCreateDescriptorPool"); return false;
            }
            long descPool = pPool.get(0);

            LongBuffer pDSLBuf = stack.longs(dsl);
            VkDescriptorSetAllocateInfo dsAI = VkDescriptorSetAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                .descriptorPool(descPool)
                .pSetLayouts(pDSLBuf);

            LongBuffer pDS = stack.mallocLong(1);
            if (vkAllocateDescriptorSets(device, dsAI, pDS) != VK_SUCCESS) {
                System.out.println(FAIL + "vkAllocateDescriptorSets"); return false;
            }
            long descSet = pDS.get(0);

            // Write descriptors
            VkDescriptorBufferInfo.Buffer bufInfos = VkDescriptorBufferInfo.calloc(2, stack);
            bufInfos.get(0).buffer(inputBuf).offset(0).range(DATA_COUNT * 4L);
            bufInfos.get(1).buffer(outputBuf).offset(0).range(DATA_COUNT * 4L);

            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
            writes.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .dstSet(descSet).dstBinding(0)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1)
                .pBufferInfo(VkDescriptorBufferInfo.create(bufInfos.address(), 1));
            writes.get(1).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .dstSet(descSet).dstBinding(1)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1)
                .pBufferInfo(VkDescriptorBufferInfo.create(bufInfos.address() + VkDescriptorBufferInfo.SIZEOF, 1));

            vkUpdateDescriptorSets(device, writes, null);
            System.out.println(PASS + "Descriptor set bound (binding 0=input, 1=output)");

            System.out.println();
            System.out.println("  ── D3: Command Buffer 録製 + Dispatch ──");

            // Allocate + record command buffer
            VkCommandBufferAllocateInfo cbAI = VkCommandBufferAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                .commandPool(commandPool)
                .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                .commandBufferCount(1);

            PointerBuffer pCB = stack.mallocPointer(1);
            if (vkAllocateCommandBuffers(device, cbAI, pCB) != VK_SUCCESS) {
                System.out.println(FAIL + "vkAllocateCommandBuffers"); return false;
            }
            VkCommandBuffer cb = new VkCommandBuffer(pCB.get(0), device);

            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            vkBeginCommandBuffer(cb, beginInfo);

            vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);

            LongBuffer pDescSet = stack.longs(descSet);
            vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE,
                pipelineLayout, 0, pDescSet, null);

            // Push constant: DATA_COUNT
            IntBuffer pcBuf = stack.ints(DATA_COUNT);
            vkCmdPushConstants(cb, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pcBuf);

            // Dispatch: ceil(1024 / 64) = 16 groups
            int groups = (DATA_COUNT + 63) / 64;
            vkCmdDispatch(cb, groups, 1, 1);

            vkEndCommandBuffer(cb);
            System.out.println(PASS + "Command buffer recorded: dispatch(" + groups + ", 1, 1)");

            // Submit
            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                .pCommandBuffers(pCB);

            int submitResult = vkQueueSubmit(computeQueue, submitInfo, VK_NULL_HANDLE);
            if (submitResult != VK_SUCCESS) {
                System.out.println(FAIL + "vkQueueSubmit: " + submitResult); return false;
            }
            vkQueueWaitIdle(computeQueue);
            System.out.println(PASS + "GPU dispatch complete (vkQueueWaitIdle returned)");

            System.out.println();
            System.out.println("  ── D4: 結果検証 (GPU → CPU 読み取り) ──");

            // Read back output
            if (outputMapped != 0) {
                FloatBuffer outData = MemoryUtil.memFloatBuffer(outputMapped, DATA_COUNT);
                boolean correct = true;
                int errors = 0;
                for (int i = 0; i < DATA_COUNT; i++) {
                    float expected = (float)(i + 1) * 2.0f;
                    float actual   = outData.get(i);
                    if (Math.abs(actual - expected) > 0.001f) {
                        if (errors < 5) {
                            System.out.println(FAIL + "  [" + i + "] expected=" + expected + " actual=" + actual);
                        }
                        errors++;
                        correct = false;
                    }
                }
                if (correct) {
                    System.out.println(PASS + "全 " + DATA_COUNT + " 要素の計算結果が正しい");
                    System.out.printf("  [INFO]   サンプル: [0]=%.1f [255]=%.1f [1023]=%.1f%n",
                        outData.get(0), outData.get(255), outData.get(1023));
                } else {
                    System.out.println(FAIL + errors + " / " + DATA_COUNT + " 要素が不正");
                    return false;
                }
            } else {
                System.out.println(INFO + "Output buffer not host-mapped, skipping readback verification");
            }

            // Cleanup pipeline objects
            vmaDestroyBuffer(vmaAllocator, inputBuf, inputAlloc);
            vmaDestroyBuffer(vmaAllocator, outputBuf, outputAlloc);
            vkDestroyDescriptorPool(device, descPool, null);
            vkDestroyDescriptorSetLayout(device, dsl, null);
            vkDestroyPipeline(device, pipeline, null);
            vkDestroyPipelineLayout(device, pipelineLayout, null);
            vkDestroyShaderModule(device, shaderModule, null);

            System.out.println(PASS + "全リソースクリーンアップ完了");
            return true;
        }
    }

    static long compileShader(String src, String name) {
        long options = shaderc_compile_options_initialize();
        shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);

        // CharSequence オーバーロード使用 (VulkanComputeContext.compileGLSL() と同じ)
        long result = shaderc_compile_into_spv(shadercCompiler, src, shaderc_compute_shader, name, "main", options);

        shaderc_compile_options_release(options);

        if (result == 0) { System.out.println(FAIL + "shaderc null"); return 0; }
        if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
            System.out.println(FAIL + "Shader compile error: " + shaderc_result_get_error_message(result));
            shaderc_result_release(result);
            return 0;
        }
        return result; // caller must release
    }

    static void cleanup() {
        if (commandPool != 0 && device != null) vkDestroyCommandPool(device, commandPool, null);
        if (vmaAllocator != 0) vmaDestroyAllocator(vmaAllocator);
        if (shadercCompiler != 0) shaderc_compiler_release(shadercCompiler);
        if (device   != null) vkDestroyDevice(device, null);
        if (instance != null) vkDestroyInstance(instance, null);
    }
}
