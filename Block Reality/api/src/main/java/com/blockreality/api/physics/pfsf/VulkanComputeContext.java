package com.blockreality.api.physics.pfsf;

import com.blockreality.api.client.render.rt.BRVulkanDevice;
import com.blockreality.api.config.BRConfig;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * PFSF Vulkan Compute 環境包裝。
 *
 * 職責：
 * <ul>
 *   <li>初始化 Vulkan instance/device/compute queue（複用 BRVulkanDevice 如可用）</li>
 *   <li>VMA 記憶體分配器</li>
 *   <li>shaderc GLSL→SPIR-V 編譯</li>
 *   <li>Compute Pipeline 建立與管理</li>
 *   <li>Command Buffer 錄製與提交</li>
 * </ul>
 *
 * 所有操作 graceful degradation：初始化失敗時 {@link #isAvailable()} 回傳 false，
 * 呼叫端應 fallback 到 CPU 路徑。
 */
public final class VulkanComputeContext {

    private static final Logger LOGGER = LoggerFactory.getLogger("PFSF-VulkanCtx");

    // ═══════════════════════════════════════════════════════════════
    //  Vulkan Handles
    // ═══════════════════════════════════════════════════════════════
    private static boolean initialized = false;

    // v3: VRAM 智慧預算管理（自動偵測 + 動態分區）
    private static final VramBudgetManager vramBudgetMgr = new VramBudgetManager();

    /** VRAM 分區 ID — 向下相容 alias */
    public static final int PARTITION_PFSF = VramBudgetManager.PARTITION_PFSF;
    public static final int PARTITION_FLUID = VramBudgetManager.PARTITION_FLUID;
    public static final int PARTITION_OTHER = VramBudgetManager.PARTITION_OTHER;

    private static boolean computeSupported = false;

    private static VkInstance vkInstanceObj;
    private static VkPhysicalDevice vkPhysicalDeviceObj;
    private static VkDevice vkDeviceObj;
    private static VkQueue computeQueueObj;

    private static long vkInstance;
    private static long vkDevice;
    private static long vkPhysicalDevice;
    private static long computeQueue;

    // Shaderc compiler singleton — created once, released in shutdown()
    private static long shadercCompiler = 0;
    private static int computeQueueFamily = -1;
    private static long commandPool;
    private static long vmaAllocator;

    private static String deviceName = "unknown";
    private static int maxWorkGroupSizeX, maxWorkGroupSizeY, maxWorkGroupSizeZ;
    private static long maxStorageBufferRange;

    // Shared with BRVulkanDevice?
    private static boolean sharedDevice = false;

    private VulkanComputeContext() {}

    // ═══════════════════════════════════════════════════════════════
    //  Initialization
    // ═══════════════════════════════════════════════════════════════

    /**
     * 初始化 Vulkan Compute 環境。
     * 優先複用 BRVulkanDevice 已建立的裝置；若不可用則獨立建立 compute-only device。
     * 失敗時 graceful degradation，不拋例外。
     */
    public static synchronized void init() {
        if (initialized) return;
        initialized = true;

        LOGGER.info("[PFSF] Initializing Vulkan Compute context...");

        try {
            if (tryShareBRVulkanDevice()) {
                sharedDevice = true;
                LOGGER.info("[PFSF] Shared Vulkan device with BRVulkanDevice: {}", deviceName);
            } else {
                if (initStandalone()) {
                    LOGGER.info("[PFSF] Standalone Vulkan compute device: {}", deviceName);
                } else {
                    LOGGER.warn("[PFSF] Vulkan compute not available, falling back to CPU");
                    return;
                }
            }

            // Create command pool
            createCommandPool();

            // Initialize VMA allocator
            initVMA();

            // Query device limits
            queryDeviceLimits();

            // v3: 自動偵測 VRAM 並初始化預算
            vramBudgetMgr.init(vkPhysicalDeviceObj, BRConfig.getVramUsagePercent());

            computeSupported = true;
            LOGGER.info("[PFSF] Vulkan Compute ready — {} (workgroup max: {}×{}×{}, SSBO max: {} MB, VRAM budget: {} MB)",
                    deviceName, maxWorkGroupSizeX, maxWorkGroupSizeY, maxWorkGroupSizeZ,
                    maxStorageBufferRange / (1024 * 1024),
                    vramBudgetMgr.getTotalBudget() / (1024 * 1024));

        } catch (Throwable e) {
            LOGGER.error("[PFSF] Vulkan Compute init failed: {}", e.getMessage());
            computeSupported = false;
        }
    }

    /**
     * 嘗試複用 BRVulkanDevice 的 Vulkan 裝置。
     */
    private static boolean tryShareBRVulkanDevice() {
        try {
            // BRVulkanDevice is @OnlyIn(Dist.CLIENT) — may not be loaded on server
            Class.forName("com.blockreality.api.client.render.rt.BRVulkanDevice");
            if (!BRVulkanDevice.isInitialized() || !BRVulkanDevice.isRTSupported()) {
                return false;
            }
            vkInstanceObj = BRVulkanDevice.getVkInstanceObj();
            vkPhysicalDeviceObj = BRVulkanDevice.getVkPhysicalDeviceObj();
            vkDeviceObj = BRVulkanDevice.getVkDeviceObj();
            computeQueueObj = BRVulkanDevice.getVkQueueObj();
            vkInstance = BRVulkanDevice.getVkInstance();
            vkDevice = BRVulkanDevice.getVkDevice();
            vkPhysicalDevice = BRVulkanDevice.getVkPhysicalDevice();
            computeQueue = BRVulkanDevice.getVkQueue();
            computeQueueFamily = BRVulkanDevice.getQueueFamilyIndex();
            deviceName = BRVulkanDevice.getDeviceName();
            return true;
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            return false;
        } catch (Throwable e) {
            LOGGER.debug("[PFSF] Cannot share BRVulkanDevice: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 獨立建立 compute-only Vulkan device。
     */
    private static boolean initStandalone() {
        try {
            // 先檢查 LWJGL Vulkan 模組是否在 classpath
            Class.forName("org.lwjgl.vulkan.VK");
        } catch (ClassNotFoundException e) {
            LOGGER.error("[PFSF] LWJGL Vulkan module NOT on classpath! " +
                    "Ensure lwjgl-vulkan-3.3.5.jar is included in the mod jar. " +
                    "ClassLoader: {}", VulkanComputeContext.class.getClassLoader());
            return false;
        }

        try {
            org.lwjgl.vulkan.VK.create();
        } catch (Throwable e) {
            LOGGER.warn("[PFSF] Vulkan not available: {} ({})", e.getMessage(), e.getClass().getSimpleName());
            return false;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // ─── Create VkInstance ───
            VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                    .pApplicationName(stack.UTF8("BlockReality-PFSF"))
                    .applicationVersion(VK_MAKE_VERSION(1, 0, 0))
                    .pEngineName(stack.UTF8("PFSF"))
                    .engineVersion(VK_MAKE_VERSION(1, 2, 0))
                    .apiVersion(VK_API_VERSION_1_2);

            VkInstanceCreateInfo instanceCI = VkInstanceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                    .pApplicationInfo(appInfo);

            PointerBuffer pInstance = stack.mallocPointer(1);
            int result = vkCreateInstance(instanceCI, null, pInstance);
            if (result != VK_SUCCESS) {
                LOGGER.warn("[PFSF] vkCreateInstance failed: {}", result);
                return false;
            }
            vkInstance = pInstance.get(0);
            vkInstanceObj = new VkInstance(vkInstance, instanceCI);

            // ─── Pick physical device ───
            IntBuffer deviceCount = stack.mallocInt(1);
            vkEnumeratePhysicalDevices(vkInstanceObj, deviceCount, null);
            if (deviceCount.get(0) == 0) {
                LOGGER.warn("[PFSF] No Vulkan physical devices found");
                return false;
            }

            PointerBuffer pDevices = stack.mallocPointer(deviceCount.get(0));
            vkEnumeratePhysicalDevices(vkInstanceObj, deviceCount, pDevices);

            // Pick first device with compute queue
            for (int i = 0; i < deviceCount.get(0); i++) {
                long pd = pDevices.get(i);
                VkPhysicalDevice candidate = new VkPhysicalDevice(pd, vkInstanceObj);
                int qf = findComputeQueueFamily(candidate, stack);
                if (qf >= 0) {
                    vkPhysicalDevice = pd;
                    vkPhysicalDeviceObj = candidate;
                    computeQueueFamily = qf;

                    VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
                    vkGetPhysicalDeviceProperties(vkPhysicalDeviceObj, props);
                    deviceName = props.deviceNameString();
                    break;
                }
            }
            if (computeQueueFamily < 0) {
                LOGGER.warn("[PFSF] No compute queue family found");
                return false;
            }

            // ─── Create logical device with compute queue ───
            float[] priorities = {1.0f};
            VkDeviceQueueCreateInfo.Buffer queueCI = VkDeviceQueueCreateInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                    .queueFamilyIndex(computeQueueFamily)
                    .pQueuePriorities(stack.floats(priorities));

            VkDeviceCreateInfo deviceCI = VkDeviceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                    .pQueueCreateInfos(queueCI);

            PointerBuffer pDevice = stack.mallocPointer(1);
            result = vkCreateDevice(vkPhysicalDeviceObj, deviceCI, null, pDevice);
            if (result != VK_SUCCESS) {
                LOGGER.warn("[PFSF] vkCreateDevice failed: {}", result);
                return false;
            }
            vkDevice = pDevice.get(0);
            vkDeviceObj = new VkDevice(vkDevice, vkPhysicalDeviceObj, deviceCI);

            // Get compute queue
            PointerBuffer pQueue = stack.mallocPointer(1);
            vkGetDeviceQueue(vkDeviceObj, computeQueueFamily, 0, pQueue);
            computeQueue = pQueue.get(0);
            computeQueueObj = new VkQueue(computeQueue, vkDeviceObj);

            return true;
        } catch (Throwable e) {
            LOGGER.warn("[PFSF] Standalone Vulkan init failed: {}", e.getMessage());
            return false;
        }
    }

    private static int findComputeQueueFamily(VkPhysicalDevice device, MemoryStack stack) {
        IntBuffer qfCount = stack.mallocInt(1);
        vkGetPhysicalDeviceQueueFamilyProperties(device, qfCount, null);
        VkQueueFamilyProperties.Buffer qfProps = VkQueueFamilyProperties.calloc(qfCount.get(0), stack);
        vkGetPhysicalDeviceQueueFamilyProperties(device, qfCount, qfProps);

        for (int i = 0; i < qfCount.get(0); i++) {
            if ((qfProps.get(i).queueFlags() & VK_QUEUE_COMPUTE_BIT) != 0) {
                return i;
            }
        }
        return -1;
    }

    private static void createCommandPool() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandPoolCreateInfo poolCI = VkCommandPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(computeQueueFamily);

            LongBuffer pPool = stack.mallocLong(1);
            int result = vkCreateCommandPool(vkDeviceObj, poolCI, null, pPool);
            if (result != VK_SUCCESS) {
                throw new RuntimeException("vkCreateCommandPool failed: " + result);
            }
            commandPool = pPool.get(0);
        }
    }

    private static void initVMA() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VmaAllocatorCreateInfo allocatorCI = VmaAllocatorCreateInfo.calloc(stack)
                    .instance(vkInstanceObj)
                    .physicalDevice(vkPhysicalDeviceObj)
                    .device(vkDeviceObj)
                    .vulkanApiVersion(VK_API_VERSION_1_2);

            PointerBuffer pAllocator = stack.mallocPointer(1);
            int result = Vma.vmaCreateAllocator(allocatorCI, pAllocator);
            if (result != VK_SUCCESS) {
                throw new RuntimeException("vmaCreateAllocator failed: " + result);
            }
            vmaAllocator = pAllocator.get(0);
        }
    }

    private static void queryDeviceLimits() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(vkPhysicalDeviceObj, props);

            VkPhysicalDeviceLimits limits = props.limits();
            maxWorkGroupSizeX = limits.maxComputeWorkGroupSize(0);
            maxWorkGroupSizeY = limits.maxComputeWorkGroupSize(1);
            maxWorkGroupSizeZ = limits.maxComputeWorkGroupSize(2);
            maxStorageBufferRange = Integer.toUnsignedLong(limits.maxStorageBufferRange());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Buffer Allocation (VMA)
    // ═══════════════════════════════════════════════════════════════

    /**
     * 分配 GPU buffer（使用預設 PFSF 分區）。
     * 向下相容：現有呼叫者不需修改。
     */
    public static long[] allocateDeviceBuffer(long size, int usage) {
        return allocateDeviceBuffer(size, usage, PARTITION_PFSF);
    }

    /**
     * 分配 GPU buffer（指定 VRAM 分區）。
     * v3: 透過 VramBudgetManager 進行預算檢查，分配後記錄，失敗時回滾。
     *
     * @param partition PARTITION_PFSF / PARTITION_FLUID / PARTITION_OTHER
     * @return [bufferHandle, allocationHandle]，或 null 若預算超額
     */
    public static long[] allocateDeviceBuffer(long size, int usage, int partition) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bufCI = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(size)
                    .usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            VmaAllocationCreateInfo allocCI = VmaAllocationCreateInfo.calloc(stack)
                    .usage(Vma.VMA_MEMORY_USAGE_GPU_ONLY);

            LongBuffer pBuffer = stack.mallocLong(1);
            PointerBuffer pAlloc = stack.mallocPointer(1);

            int result = Vma.vmaCreateBuffer(vmaAllocator, bufCI, allocCI, pBuffer, pAlloc, null);
            if (result != VK_SUCCESS) {
                throw new RuntimeException("vmaCreateBuffer failed: " + result);
            }

            long bufferHandle = pBuffer.get(0);
            long allocationHandle = pAlloc.get(0);

            // v3: 使用 VramBudgetManager 記錄 — 若預算超額則回滾 VMA 分配
            if (!vramBudgetMgr.tryRecord(bufferHandle, size, partition)) {
                Vma.vmaDestroyBuffer(vmaAllocator, bufferHandle, allocationHandle);
                return null;  // 靜默失敗，讓呼叫者回退到 CPU
            }

            return new long[]{bufferHandle, allocationHandle};
        }
    }

    // ═══ VRAM 查詢 API（v3: 委託 VramBudgetManager）═══

    /** 取得 VRAM 預算管理器 */
    public static VramBudgetManager getVramBudgetManager() { return vramBudgetMgr; }

    /** VRAM 壓力值 (0.0 ~ 1.0) */
    public static float getVramPressure() { return vramBudgetMgr.getPressure(); }

    /** 剩餘可用 VRAM (bytes) */
    public static long getVramFreeMemory() { return vramBudgetMgr.getFreeMemory(); }

    /** 查詢全域 VRAM 使用量（bytes） */
    public static long getTotalVramUsage() { return vramBudgetMgr.getTotalUsage(); }

    /** 查詢指定分區的 VRAM 使用量（bytes） */
    public static long getPartitionUsage(int partition) {
        return vramBudgetMgr.getPartitionUsage(partition);
    }

    /**
     * @deprecated 使用 VramBudgetManager 自動偵測，此方法為空操作。
     */
    @Deprecated
    public static void setVramBudget(int totalMB, int pfsfMB, int fluidMB, int otherMB) {
        LOGGER.warn("[VulkanCompute] setVramBudget() is deprecated — VRAM budget is auto-detected by VramBudgetManager");
    }

    /**
     * 分配 staging buffer（host-visible, host-coherent）用於 CPU↔GPU 傳輸。
     */
    public static long[] allocateStagingBuffer(long size) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bufCI = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(size)
                    .usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            VmaAllocationCreateInfo allocCI = VmaAllocationCreateInfo.calloc(stack)
                    .usage(Vma.VMA_MEMORY_USAGE_CPU_ONLY)
                    .flags(Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT);

            LongBuffer pBuffer = stack.mallocLong(1);
            PointerBuffer pAlloc = stack.mallocPointer(1);

            int result = Vma.vmaCreateBuffer(vmaAllocator, bufCI, allocCI, pBuffer, pAlloc, null);
            if (result != VK_SUCCESS) {
                throw new RuntimeException("vmaCreateBuffer (staging) failed: " + result);
            }
            return new long[]{pBuffer.get(0), pAlloc.get(0)};
        }
    }

    /**
     * 釋放 VMA buffer。
     * v3 CRITICAL fix: 釋放前遞減 VRAM 計數器（舊版完全遺漏！）
     */
    public static void freeBuffer(long buffer, long allocation) {
        if (buffer != 0 && allocation != 0) {
            vramBudgetMgr.recordFree(buffer);
            Vma.vmaDestroyBuffer(vmaAllocator, buffer, allocation);
        }
    }

    /**
     * Map staging buffer → CPU 指標。
     * C2-fix: 接受 size 參數，回傳正確大小的 ByteBuffer。
     */
    public static ByteBuffer mapBuffer(long allocation, long size) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pData = stack.mallocPointer(1);
            Vma.vmaMapMemory(vmaAllocator, allocation, pData);
            return MemoryUtil.memByteBuffer(pData.get(0), (int) size);
        }
    }

    public static void unmapBuffer(long allocation) {
        Vma.vmaUnmapMemory(vmaAllocator, allocation);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Shader Compilation (shaderc)
    // ═══════════════════════════════════════════════════════════════

    /**
     * 編譯 GLSL compute shader 為 SPIR-V bytecode。
     *
     * @param glslSource GLSL 原始碼
     * @param fileName   檔名（錯誤訊息用）
     * @return SPIR-V bytecode
     * @throws RuntimeException 編譯失敗
     */
    public static synchronized ByteBuffer compileGLSL(String glslSource, String fileName) {
        if (shadercCompiler == 0) {
            shadercCompiler = Shaderc.shaderc_compiler_initialize();
            if (shadercCompiler == 0) throw new RuntimeException("Failed to init shaderc compiler");
        }
        long compiler = shadercCompiler;

        long options = Shaderc.shaderc_compile_options_initialize();
        try {
            Shaderc.shaderc_compile_options_set_target_env(options,
                    Shaderc.shaderc_target_env_vulkan,
                    Shaderc.shaderc_env_version_vulkan_1_2);
            Shaderc.shaderc_compile_options_set_optimization_level(options,
                    Shaderc.shaderc_optimization_level_performance);

            long result = Shaderc.shaderc_compile_into_spv(compiler, glslSource,
                    Shaderc.shaderc_glsl_compute_shader, fileName, "main", options);
            try {
                if (Shaderc.shaderc_result_get_compilation_status(result)
                        != Shaderc.shaderc_compilation_status_success) {
                    String errorMsg = Shaderc.shaderc_result_get_error_message(result);
                    throw new RuntimeException("GLSL compilation failed (" + fileName + "): " + errorMsg);
                }

                ByteBuffer spirv = Shaderc.shaderc_result_get_bytes(result);
                ByteBuffer copy = MemoryUtil.memAlloc(spirv.remaining());
                copy.put(spirv);
                copy.flip();
                return copy;
            } finally {
                Shaderc.shaderc_result_release(result);
            }
        } finally {
            Shaderc.shaderc_compile_options_release(options);
            // Note: shadercCompiler is a singleton; released in shutdown()
        }
    }

    /**
     * 從 classpath 載入 GLSL 原始碼。
     */
    public static String loadShaderSource(String resourcePath) throws IOException {
        try (InputStream is = VulkanComputeContext.class.getClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (is == null) throw new IOException("Shader not found: " + resourcePath);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Compute Pipeline
    // ═══════════════════════════════════════════════════════════════

    /**
     * 建立 Compute Pipeline。
     *
     * @param spirvCode     SPIR-V bytecode
     * @param layoutHandle  VkPipelineLayout handle
     * @return VkPipeline handle
     */
    public static long createComputePipeline(ByteBuffer spirvCode, long layoutHandle) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Create shader module
            VkShaderModuleCreateInfo moduleCI = VkShaderModuleCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                    .pCode(spirvCode);

            LongBuffer pModule = stack.mallocLong(1);
            int result = vkCreateShaderModule(vkDeviceObj, moduleCI, null, pModule);
            if (result != VK_SUCCESS) throw new RuntimeException("vkCreateShaderModule failed");
            long shaderModule = pModule.get(0);

            // Create pipeline
            VkPipelineShaderStageCreateInfo stageCI = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(shaderModule)
                    .pName(stack.UTF8("main"));

            VkComputePipelineCreateInfo.Buffer pipelineCI = VkComputePipelineCreateInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO)
                    .stage(stageCI)
                    .layout(layoutHandle);

            LongBuffer pPipeline = stack.mallocLong(1);
            result = vkCreateComputePipelines(vkDeviceObj, VK_NULL_HANDLE, pipelineCI, null, pPipeline);

            // Destroy shader module (no longer needed after pipeline creation)
            vkDestroyShaderModule(vkDeviceObj, shaderModule, null);

            if (result != VK_SUCCESS) throw new RuntimeException("vkCreateComputePipelines failed");
            return pPipeline.get(0);
        }
    }

    /**
     * 建立 Descriptor Set Layout。
     *
     * @param bindingCount 綁定數量（全部 STORAGE_BUFFER type）
     * @return VkDescriptorSetLayout handle
     */
    public static long createDescriptorSetLayout(int bindingCount) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings =
                    VkDescriptorSetLayoutBinding.calloc(bindingCount, stack);

            for (int i = 0; i < bindingCount; i++) {
                bindings.get(i)
                        .binding(i)
                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .descriptorCount(1)
                        .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            }

            VkDescriptorSetLayoutCreateInfo layoutCI = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .pBindings(bindings);

            LongBuffer pLayout = stack.mallocLong(1);
            int result = vkCreateDescriptorSetLayout(vkDeviceObj, layoutCI, null, pLayout);
            if (result != VK_SUCCESS) throw new RuntimeException("vkCreateDescriptorSetLayout failed");
            return pLayout.get(0);
        }
    }

    /**
     * 建立 Pipeline Layout（含 push constant range）。
     *
     * @param dsLayout         Descriptor set layout
     * @param pushConstantSize Push constant 大小（bytes）
     * @return VkPipelineLayout handle
     */
    public static long createPipelineLayout(long dsLayout, int pushConstantSize) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT)
                    .offset(0)
                    .size(pushConstantSize);

            VkPipelineLayoutCreateInfo layoutCI = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(stack.longs(dsLayout))
                    .pPushConstantRanges(pushRange);

            LongBuffer pLayout = stack.mallocLong(1);
            int result = vkCreatePipelineLayout(vkDeviceObj, layoutCI, null, pLayout);
            if (result != VK_SUCCESS) throw new RuntimeException("vkCreatePipelineLayout failed");
            return pLayout.get(0);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Command Buffer
    // ═══════════════════════════════════════════════════════════════

    /**
     * 分配並開始錄製一個一次性 command buffer。
     */
    public static VkCommandBuffer beginSingleTimeCommands() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(commandPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);

            PointerBuffer pBuf = stack.mallocPointer(1);
            vkAllocateCommandBuffers(vkDeviceObj, allocInfo, pBuf);

            VkCommandBuffer cmdBuf = new VkCommandBuffer(pBuf.get(0), vkDeviceObj);

            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

            vkBeginCommandBuffer(cmdBuf, beginInfo);
            return cmdBuf;
        }
    }

    /**
     * 結束錄製並提交 command buffer，等待完成後釋放。
     */
    public static void endSingleTimeCommands(VkCommandBuffer cmdBuf) {
        vkEndCommandBuffer(cmdBuf);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .pCommandBuffers(stack.pointers(cmdBuf));

            int result = vkQueueSubmit(computeQueueObj, submitInfo, VK_NULL_HANDLE);
            if (result != VK_SUCCESS) {
                LOGGER.error("[PFSF] vkQueueSubmit failed: {}", result);
            } else {
                vkQueueWaitIdle(computeQueueObj);
            }
        }

        vkFreeCommandBuffers(vkDeviceObj, commandPool, cmdBuf);
    }

    // ═══════════════════════════════════════════════════════════════
    //  v3: Fence-Based Async Commands（取代 vkQueueWaitIdle）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 結束錄製並提交 command buffer，回傳 fence handle（非阻塞）。
     * 呼叫者需稍後呼叫 {@link #waitFenceAndFree(long, VkCommandBuffer)} 等待完成並釋放 command buffer。
     */
    /**
     * 結束錄製並提交 command buffer，回傳 fence handle（非阻塞）。
     * 呼叫者需稍後呼叫 {@link #waitFenceAndFree(long, VkCommandBuffer)} 等待完成並釋放 command buffer。
     */
    public static long endSingleTimeCommandsWithFence(VkCommandBuffer cmdBuf) {
        vkEndCommandBuffer(cmdBuf);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkFenceCreateInfo fenceCI = VkFenceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);
            LongBuffer pFence = stack.mallocLong(1);
            int fenceResult = vkCreateFence(vkDeviceObj, fenceCI, null, pFence);
            if (fenceResult != VK_SUCCESS) {
                vkFreeCommandBuffers(vkDeviceObj, commandPool, cmdBuf);
                throw new RuntimeException("vkCreateFence failed: " + fenceResult);
            }
            long fence = pFence.get(0);

            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .pCommandBuffers(stack.pointers(cmdBuf));

            int submitResult = vkQueueSubmit(computeQueueObj, submitInfo, fence);
            if (submitResult != VK_SUCCESS) {
                vkDestroyFence(vkDeviceObj, fence, null);
                vkFreeCommandBuffers(vkDeviceObj, commandPool, cmdBuf);
                throw new RuntimeException("vkQueueSubmit failed: " + submitResult);
            }
            return fence;
        }
    }

    /** 等待 fence 完成（阻塞），並釋放對應的 command buffer。 */
    public static void waitFenceAndFree(long fence, VkCommandBuffer cmdBuf) {
        vkWaitForFences(vkDeviceObj, fence, true, Long.MAX_VALUE);
        vkDestroyFence(vkDeviceObj, fence, null);
        if (cmdBuf != null) {
            vkFreeCommandBuffers(vkDeviceObj, commandPool, cmdBuf);
        }
    }

    /**
     * @deprecated 使用 {@link #waitFenceAndFree(long, VkCommandBuffer)} 以避免 command buffer 洩漏。
     */
    @Deprecated
    public static void waitFence(long fence) {
        vkWaitForFences(vkDeviceObj, fence, true, Long.MAX_VALUE);
        vkDestroyFence(vkDeviceObj, fence, null);
    }

    /**
     * 插入 compute → compute memory barrier（確保前一 dispatch 寫入完畢再開始下一次讀取）。
     */
    public static void computeBarrier(VkCommandBuffer cmdBuf) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT);

            vkCmdPipelineBarrier(cmdBuf,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    0, barrier, null, null);
        }
    }

    /**
     * Compute shader 寫入 → Transfer（copy）讀取的 barrier。
     * 在 vkCmdCopyBuffer 之前呼叫，確保 compute dispatch 寫入的資料
     * 在 copy 操作時已完全可見。
     */
    public static void computeToTransferBarrier(VkCommandBuffer cmdBuf) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);

            vkCmdPipelineBarrier(cmdBuf,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0, barrier, null, null);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Descriptor Set
    // ═══════════════════════════════════════════════════════════════

    /**
     * 建立 Descriptor Pool（指定最大 set 數量和 STORAGE_BUFFER 數量）。
     */
    public static long createDescriptorPool(int maxSets, int maxStorageBuffers) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorPoolSize.Buffer poolSize = VkDescriptorPoolSize.calloc(1, stack)
                    .type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(maxStorageBuffers);

            VkDescriptorPoolCreateInfo poolCI = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                    .flags(VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT)
                    .maxSets(maxSets)
                    .pPoolSizes(poolSize);

            LongBuffer pPool = stack.mallocLong(1);
            int result = vkCreateDescriptorPool(vkDeviceObj, poolCI, null, pPool);
            if (result != VK_SUCCESS) throw new RuntimeException("vkCreateDescriptorPool failed");
            return pPool.get(0);
        }
    }

    /**
     * 建立獨立 Descriptor Pool（各引擎各自一個，避免重置互相影響）。
     *
     * <p>PFSF 和 Fluid 應各持有自己的 pool，各自在不同時機重置，
     * 避免 PFSF 每 20 tick 重置時使 Fluid 的 pending descriptor 失效。</p>
     */
    public static long createIsolatedDescriptorPool(int maxSets, int maxStorageBuffers, String ownerName) {
        long pool = createDescriptorPool(maxSets, maxStorageBuffers);
        LOGGER.info("[VulkanCompute] Created isolated descriptor pool for '{}': maxSets={}, maxBuffers={}",
                ownerName, maxSets, maxStorageBuffers);
        return pool;
    }

    /**
     * C8-fix: 銷毀 descriptor pool。
     */
    public static void destroyDescriptorPool(long pool) {
        vkDestroyDescriptorPool(vkDeviceObj, pool, null);
    }

    /**
     * A2-fix: 重置 descriptor pool（O(1) 操作，釋放所有已分配的 set）。
     * 每 tick 開頭呼叫，避免 pool 耗盡。
     */
    public static void resetDescriptorPool(long pool) {
        int result = vkResetDescriptorPool(vkDeviceObj, pool, 0);
        if (result != VK_SUCCESS) {
            LOGGER.warn("[PFSF] vkResetDescriptorPool failed: {}", result);
        }
    }

    /**
     * 從 pool 分配一個 descriptor set。
     */
    /**
     * 從 pool 分配一個 descriptor set。
     *
     * @return descriptor set handle，或 0 若 pool 已滿（VK_ERROR_OUT_OF_POOL_MEMORY）。
     *         呼叫者應在收到 0 時重置 pool 後重試，而非假設永遠成功。
     */
    public static long allocateDescriptorSet(long pool, long layout) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(pool)
                    .pSetLayouts(stack.longs(layout));

            LongBuffer pSet = stack.mallocLong(1);
            int result = vkAllocateDescriptorSets(vkDeviceObj, allocInfo, pSet);
            if (result == VK11.VK_ERROR_OUT_OF_POOL_MEMORY || result == VK_ERROR_FRAGMENTED_POOL) {
                LOGGER.warn("[PFSF] Descriptor pool full ({}), caller must reset pool", result);
                return 0;
            }
            if (result != VK_SUCCESS) {
                throw new RuntimeException("vkAllocateDescriptorSets failed: " + result);
            }
            return pSet.get(0);
        }
    }

    /**
     * 綁定 buffer 到 descriptor set 的指定 binding。
     */
    public static void bindBufferToDescriptor(long descriptorSet, int binding,
                                                long buffer, long offset, long range) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorBufferInfo.Buffer bufInfo = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(buffer)
                    .offset(offset)
                    .range(range);

            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(descriptorSet)
                    .dstBinding(binding)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .pBufferInfo(bufInfo);

            vkUpdateDescriptorSets(vkDeviceObj, write, null);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Cleanup
    // ═══════════════════════════════════════════════════════════════

    /**
     * 清理所有 Vulkan 資源。
     */
    public static synchronized void shutdown() {
        if (!initialized) return;

        if (computeSupported) {
            vkDeviceWaitIdle(vkDeviceObj);

            // Destroy pipeline/layout/dsLayout handles before device is torn down
            PFSFPipelineFactory.destroyAll();

            if (commandPool != 0) {
                vkDestroyCommandPool(vkDeviceObj, commandPool, null);
                commandPool = 0;
            }

            if (vmaAllocator != 0) {
                Vma.vmaDestroyAllocator(vmaAllocator);
                vmaAllocator = 0;
            }

            if (!sharedDevice) {
                if (vkDevice != 0) {
                    vkDestroyDevice(vkDeviceObj, null);
                    vkDevice = 0;
                }
                if (vkInstance != 0) {
                    vkDestroyInstance(vkInstanceObj, null);
                    vkInstance = 0;
                }
            }
        }

        if (shadercCompiler != 0) {
            Shaderc.shaderc_compiler_release(shadercCompiler);
            shadercCompiler = 0;
        }

        computeSupported = false;
        initialized = false;
        LOGGER.info("[PFSF] Vulkan Compute context shut down");
    }

    // ═══════════════════════════════════════════════════════════════
    //  Queries
    // ═══════════════════════════════════════════════════════════════

    public static boolean isAvailable() {
        return computeSupported;
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static VkDevice getVkDeviceObj() {
        return vkDeviceObj;
    }

    public static long getVmaAllocator() {
        return vmaAllocator;
    }

    public static VkQueue getComputeQueue() {
        return computeQueueObj;
    }

    public static long getCommandPool() {
        return commandPool;
    }

    /**
     * 回傳 GPU 裝置資訊字串（供 /br vulkan_test 命令使用）。
     */
    public static String getDeviceInfo() {
        if (!computeSupported) {
            return "PFSF Vulkan Compute: NOT AVAILABLE" +
                    (initialized ? " (init attempted but failed)" : " (not initialized)");
        }
        return String.format(
                "PFSF Vulkan Compute: %s | Shared=%s | WorkGroup=%d×%d×%d | SSBO Max=%d MB",
                deviceName, sharedDevice,
                maxWorkGroupSizeX, maxWorkGroupSizeY, maxWorkGroupSizeZ,
                maxStorageBufferRange / (1024 * 1024));
    }
}
