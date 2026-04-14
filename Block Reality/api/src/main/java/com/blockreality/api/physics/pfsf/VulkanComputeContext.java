package com.blockreality.api.physics.pfsf;

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
    // ★ EIIE-fix: 改為 null 初始化，在 init() 中才建立，避免 VramBudgetManager
    //   初始化失敗時透過 ExceptionInInitializerError 拖垮整個 VulkanComputeContext。
    private static VramBudgetManager vramBudgetMgr;

    /** VRAM 分區 ID — 向下相容 alias（直接引用常數，不觸發 VramBudgetManager 類別載入） */
    public static final int PARTITION_PFSF  = 0;  // == VramBudgetManager.PARTITION_PFSF
    public static final int PARTITION_FLUID = 1;  // == VramBudgetManager.PARTITION_FLUID
    public static final int PARTITION_OTHER = 2;  // == VramBudgetManager.PARTITION_OTHER

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
    private static long minStorageBufferOffsetAlignment = 256; // safe fallback (actual value queried at init)

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
            // ★ EIIE-fix: vramBudgetMgr 在此處才建立，確保 VulkanComputeContext 類別載入不觸發它
            vramBudgetMgr = new VramBudgetManager();
            vramBudgetMgr.init(vkPhysicalDeviceObj, BRConfig.getVramUsagePercent());

            computeSupported = true;
            LOGGER.info("[PFSF] Vulkan Compute ready — {} (workgroup max: {}×{}×{}, SSBO max: {} MB, VRAM budget: {} MB)",
                    deviceName, maxWorkGroupSizeX, maxWorkGroupSizeY, maxWorkGroupSizeZ,
                    maxStorageBufferRange / (1024 * 1024),
                    vramBudgetMgr.getTotalBudget() / (1024 * 1024));

        } catch (Throwable e) {
            LOGGER.error("[PFSF] Vulkan Compute init failed: {} ({})", e.getMessage(), e.getClass().getSimpleName());
            if (e instanceof UnsatisfiedLinkError) {
                LOGGER.error("[PFSF]   → Native library load failure — 可能是 VMA 或 Shaderc native jar 未在 classpath");
            }
            computeSupported = false;
        }
    }

    /**
     * 嘗試複用 BRVulkanDevice 的 Vulkan 裝置。
     *
     * <p>純反射實作 — 不得直接引用 BRVulkanDevice 類別，因為該類別標記
     * {@code @OnlyIn(Dist.CLIENT)}，在專用伺服器上不存在。即使用
     * {@code Class.forName()} 做防護，編譯器生成的 import 仍會觸發
     * class loading cascade → {@code ExceptionInInitializerError}。</p>
     */
    private static boolean tryShareBRVulkanDevice() {
        try {
            Class<?> brVkDev = Class.forName("com.blockreality.api.client.render.rt.BRVulkanDevice");

            boolean isInit = (boolean) brVkDev.getMethod("isInitialized").invoke(null);
            boolean isRT   = (boolean) brVkDev.getMethod("isRTSupported").invoke(null);
            if (!isInit || !isRT) return false;

            vkInstanceObj       = (VkInstance)       brVkDev.getMethod("getVkInstanceObj").invoke(null);
            vkPhysicalDeviceObj = (VkPhysicalDevice) brVkDev.getMethod("getVkPhysicalDeviceObj").invoke(null);
            vkDeviceObj         = (VkDevice)         brVkDev.getMethod("getVkDeviceObj").invoke(null);
            computeQueueObj     = (VkQueue)          brVkDev.getMethod("getVkQueueObj").invoke(null);
            vkInstance          = (long) brVkDev.getMethod("getVkInstance").invoke(null);
            vkDevice            = (long) brVkDev.getMethod("getVkDevice").invoke(null);
            vkPhysicalDevice    = (long) brVkDev.getMethod("getVkPhysicalDevice").invoke(null);
            computeQueue        = (long) brVkDev.getMethod("getVkQueue").invoke(null);
            computeQueueFamily  = (int)  brVkDev.getMethod("getQueueFamilyIndex").invoke(null);
            deviceName          = (String) brVkDev.getMethod("getDeviceName").invoke(null);
            return true;
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            // Expected on dedicated server — BRVulkanDevice not present
            LOGGER.debug("[PFSF] BRVulkanDevice not available (server-side), standalone init");
            return false;
        } catch (Throwable e) {
            LOGGER.debug("[PFSF] Cannot share BRVulkanDevice: {}", e.toString());
            return false;
        }
    }

    /**
     * 獨立建立 compute-only Vulkan device。
     */
    private static boolean initStandalone() {
        // ─── Stage 1: 檢查 LWJGL Vulkan 模組是否在 classpath ───
        try {
            Class<?> vkClass = Class.forName("org.lwjgl.vulkan.VK");
            LOGGER.info("[PFSF] Found org.lwjgl.vulkan.VK — ClassLoader: {}", vkClass.getClassLoader());
        } catch (ClassNotFoundException e) {
            ClassLoader modCL = VulkanComputeContext.class.getClassLoader();
            LOGGER.error("[PFSF] ═══ LWJGL Vulkan module NOT on classpath! ═══");
            LOGGER.error("[PFSF]   ModClassLoader:    {}", modCL);
            LOGGER.error("[PFSF]   Parent:            {}", modCL != null ? modCL.getParent() : "null");
            LOGGER.error("[PFSF]   SystemClassLoader:  {}", ClassLoader.getSystemClassLoader());
            LOGGER.error("[PFSF]   Fix: 確認 build.gradle 的 afterEvaluate 區塊有加入 -Xbootclasspath/a:");
            return false;
        }

        // ─── Stage 2: 載入系統 Vulkan driver（vulkan-1.dll / libvulkan.so）───
        LOGGER.info("[PFSF] Loading system Vulkan driver...");
        LOGGER.info("[PFSF]   java.library.path = {}", System.getProperty("java.library.path"));
        LOGGER.info("[PFSF]   org.lwjgl.librarypath = {}", System.getProperty("org.lwjgl.librarypath", "(not set)"));
        try {
            org.lwjgl.vulkan.VK.create();
            LOGGER.info("[PFSF] System Vulkan driver loaded successfully");
        } catch (UnsatisfiedLinkError e) {
            LOGGER.error("[PFSF] ═══ System Vulkan driver NOT found! ═══");
            LOGGER.error("[PFSF]   Error: {}", e.getMessage());
            LOGGER.error("[PFSF]   Fix: 安裝/更新 NVIDIA 驅動程式，確認 vulkan-1.dll 存在於 C:\\WINDOWS\\SYSTEM32\\");
            return false;
        } catch (Throwable e) {
            LOGGER.warn("[PFSF] Vulkan not available: {} ({})", e.getMessage(), e.getClass().getSimpleName());
            return false;
        }

        // ─── Stage 2.5: 查詢實體版本（RTX 5000 / Blackwell + NVIDIA 575.xx 相容性診斷）───
        // vkEnumerateInstanceVersion 是 VK 1.1 全域函數；若不存在則代表只有 VK 1.0。
        try (MemoryStack stack0 = MemoryStack.stackPush()) {
            IntBuffer pVer = stack0.mallocInt(1);
            int verRes = VK11.vkEnumerateInstanceVersion(pVer);
            if (verRes == VK_SUCCESS) {
                int v = pVer.get(0);
                LOGGER.info("[PFSF] Vulkan instance loader version: {}.{}.{} (RTX 5000 Blackwell 預期 1.4.x)",
                        VK_VERSION_MAJOR(v), VK_VERSION_MINOR(v), VK_VERSION_PATCH(v));
                if (VK_VERSION_MAJOR(v) < 1 || (VK_VERSION_MAJOR(v) == 1 && VK_VERSION_MINOR(v) < 2)) {
                    LOGGER.error("[PFSF] Vulkan loader {}.{} < 1.2 — 請更新 GPU 驅動至支援 Vulkan 1.2+ 的版本",
                            VK_VERSION_MAJOR(v), VK_VERSION_MINOR(v));
                    return false;
                }
            } else {
                // 沒有此函數 = Vulkan 1.0 loader，無法繼續
                LOGGER.error("[PFSF] vkEnumerateInstanceVersion 失敗 ({}) — 驅動僅支援 Vulkan 1.0，需要 1.2+",
                        vkResultToString(verRes));
                return false;
            }
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
                LOGGER.error("[PFSF] vkCreateInstance failed: {} — 可能原因: Vulkan loader 版本不匹配或驅動未正確安裝",
                        vkResultToString(result));
                return false;
            }
            vkInstance = pInstance.get(0);
            vkInstanceObj = new VkInstance(vkInstance, instanceCI);

            // ─── Pick physical device（偏好 DISCRETE_GPU，記錄所有裝置）───
            // Khronos 建議：系統可能同時有 Intel iGPU + NVIDIA dGPU，
            // 必須主動選取獨立顯卡，否則 compute 效能極差或功能受限。
            IntBuffer deviceCount = stack.mallocInt(1);
            vkEnumeratePhysicalDevices(vkInstanceObj, deviceCount, null);
            if (deviceCount.get(0) == 0) {
                LOGGER.warn("[PFSF] No Vulkan physical devices found");
                return false;
            }

            PointerBuffer pDevices = stack.mallocPointer(deviceCount.get(0));
            vkEnumeratePhysicalDevices(vkInstanceObj, deviceCount, pDevices);

            LOGGER.info("[PFSF] Enumerating {} Vulkan device(s)...", deviceCount.get(0));
            int bestScore = -1;
            for (int i = 0; i < deviceCount.get(0); i++) {
                long pd = pDevices.get(i);
                VkPhysicalDevice candidate = new VkPhysicalDevice(pd, vkInstanceObj);
                VkPhysicalDeviceProperties cProps = VkPhysicalDeviceProperties.calloc(stack);
                vkGetPhysicalDeviceProperties(candidate, cProps);
                String cName    = cProps.deviceNameString();
                int    cType    = cProps.deviceType();
                int    cApiVer  = cProps.apiVersion();
                int    cQF      = findComputeQueueFamily(candidate, stack);

                // DISCRETE_GPU=4, INTEGRATED_GPU=2, VIRTUAL_GPU=1, CPU/OTHER=0, no compute=-1
                int score = (cQF >= 0) ? switch (cType) {
                    case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU   -> 4;
                    case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU -> 2;
                    case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU    -> 1;
                    default                                     -> 0;
                } : -1;

                LOGGER.info("[PFSF]   [{}] {} | type={} | VK {}.{}.{} | vendorID=0x{} | computeQF={} | score={}",
                        i, cName, physDevTypeName(cType),
                        VK_VERSION_MAJOR(cApiVer), VK_VERSION_MINOR(cApiVer), VK_VERSION_PATCH(cApiVer),
                        Integer.toHexString(cProps.vendorID()), cQF, score);

                if (score > bestScore) {
                    bestScore         = score;
                    vkPhysicalDevice  = pd;
                    vkPhysicalDeviceObj = candidate;
                    computeQueueFamily  = cQF;
                    deviceName          = cName;
                }
            }

            if (computeQueueFamily < 0) {
                LOGGER.warn("[PFSF] No GPU with compute queue family found on any Vulkan device");
                return false;
            }
            LOGGER.info("[PFSF] → Selected: {} (score={})", deviceName, bestScore);

            // ─── Create logical device with compute queue ───
            // Khronos Vulkan Guide 標準 pNext chain（已被 WickedEngine / MoltenVK 等主流引擎驗證）：
            //   deviceCI.pNext → features2 → vk12Features → vk11Features → NULL
            //
            // RTX 5070 Ti (Blackwell) + NVIDIA 575.xx：驅動報告 VK 1.4，
            // 建立 VK 1.2 裝置時必須同時宣告 VkPhysicalDeviceVulkan11Features +
            // VkPhysicalDeviceVulkan12Features（否則回傳 VK_ERROR_FEATURE_NOT_PRESENT）。
            // 做法：先 vkGetPhysicalDeviceFeatures2 查詢支援特性後原樣回傳，
            // 不主動啟用任何未支援的選項。
            //
            // VUID-VkDeviceCreateInfo-pNext-00373：
            //   若 pNext chain 含 VkPhysicalDeviceFeatures2，則 pEnabledFeatures 必須為 NULL。
            //   （calloc 已將 pEnabledFeatures 初始化為 0 / NULL，符合規範）
            VkPhysicalDeviceVulkan11Features vk11Features = VkPhysicalDeviceVulkan11Features.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES);
            VkPhysicalDeviceVulkan12Features vk12Features = VkPhysicalDeviceVulkan12Features.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES)
                    .pNext(vk11Features.address());       // vk12 → vk11 → NULL
            VkPhysicalDeviceFeatures2 features2 = VkPhysicalDeviceFeatures2.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2)
                    .pNext(vk12Features.address());       // features2 → vk12 → vk11
            VK11.vkGetPhysicalDeviceFeatures2(vkPhysicalDeviceObj, features2);
            LOGGER.debug("[PFSF] Features2 queried: samplerYcbcr={} bufferDeviceAddress={} imagelessFramebuffer={}",
                    vk11Features.samplerYcbcrConversion(),
                    vk12Features.bufferDeviceAddress(),
                    vk12Features.imagelessFramebuffer());

            float[] priorities = {1.0f};
            VkDeviceQueueCreateInfo.Buffer queueCI = VkDeviceQueueCreateInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                    .queueFamilyIndex(computeQueueFamily)
                    .pQueuePriorities(stack.floats(priorities));

            VkDeviceCreateInfo deviceCI = VkDeviceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                    .pNext(features2.address())   // RTX 5000 相容：宣告 Vulkan 1.2 device
                    .pQueueCreateInfos(queueCI);

            PointerBuffer pDevice = stack.mallocPointer(1);
            result = vkCreateDevice(vkPhysicalDeviceObj, deviceCI, null, pDevice);
            if (result != VK_SUCCESS) {
                LOGGER.error("[PFSF] vkCreateDevice failed: {} | RTX 5000: 若此為 VK_ERROR_FEATURE_NOT_PRESENT(-8), 檢查 pNext chain",
                        vkResultToString(result));
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
            // 詳細記錄例外類型與堆疊，方便診斷 NullPointerException / UnsatisfiedLinkError 等
            LOGGER.error("[PFSF] Standalone Vulkan init failed ({}) — {}",
                    e.getClass().getSimpleName(), e.getMessage() != null ? e.getMessage() : "(no message)", e);
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
                throw new RuntimeException("vkCreateCommandPool failed: " + vkResultToString(result));
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
                throw new RuntimeException("vmaCreateAllocator failed: " + vkResultToString(result)
                        + " | RTX 5000: 若此為 VK_ERROR_INCOMPATIBLE_DRIVER(-9), 確認 VMA native jar 版本與 Vulkan 1.2+ 相容");
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
            minStorageBufferOffsetAlignment = limits.minStorageBufferOffsetAlignment();
            LOGGER.info("[PFSF] minStorageBufferOffsetAlignment = {} bytes", minStorageBufferOffsetAlignment);
        }
    }

    public static long getMinStorageBufferOffsetAlignment() {
        return minStorageBufferOffsetAlignment;
    }

    /** 取得 GPU 裝置名稱（供 Crash Reporter 使用）。 */
    public static String getDeviceName() { return deviceName; }

    /** 是否使用了與 BRVulkanDevice 共享的裝置（Crash Reporter 診斷）。 */
    public static boolean isSharedDevice() { return sharedDevice; }

    /** PFSF Vulkan Compute 是否成功初始化。 */
    public static boolean isComputeSupported() { return computeSupported; }

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
     * v0.2a: 透過 VramBudgetManager 進行預算檢查，分配後記錄，失敗時回滾。
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

            // v3: 使用 VramBudgetManager 記錄 — 若預算超額則回滚 VMA 分配
            // ★ EIIE-fix: 加入 null guard，可能 Vulkan 初始化失敗導致 vramBudgetMgr 仍為 null
            if (vramBudgetMgr != null && !vramBudgetMgr.tryRecord(bufferHandle, size, partition)) {
                Vma.vmaDestroyBuffer(vmaAllocator, bufferHandle, allocationHandle);
                return null;  // 靜默失敗，讓呼叫者回退到 CPU
            }

            return new long[]{bufferHandle, allocationHandle};
        }
    }

    // ═══ VRAM 查詢 API（v3: 委託 VramBudgetManager）═══

    /** 取得 VRAM 預算管理器（可能為 null 若 Vulkan 未初始化） */
    public static VramBudgetManager getVramBudgetManager() { return vramBudgetMgr; }

    /** VRAM 壓力值 (0.0 ~ 1.0)，Vulkan 不可用時回傳 0 */
    public static float getVramPressure() {
        return vramBudgetMgr != null ? vramBudgetMgr.getPressure() : 0f;
    }

    /** 剩餘可用 VRAM (bytes)，Vulkan 不可用時回傳 0 */
    public static long getVramFreeMemory() {
        return vramBudgetMgr != null ? vramBudgetMgr.getFreeMemory() : 0L;
    }

    /** 查詢全域 VRAM 使用量（bytes），Vulkan 不可用時回傳 0 */
    public static long getTotalVramUsage() {
        return vramBudgetMgr != null ? vramBudgetMgr.getTotalUsage() : 0L;
    }

    /** 查詢指定分區的 VRAM 使用量（bytes），Vulkan 不可用時回傳 0 */
    public static long getPartitionUsage(int partition) {
        return vramBudgetMgr != null ? vramBudgetMgr.getPartitionUsage(partition) : 0L;
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
            if (vramBudgetMgr != null) vramBudgetMgr.recordFree(buffer);  // ★ EIIE-fix: null guard
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
                // A2-fix: shaderc_result_get_bytes() can return null on some GPU drivers
                // even when compilation status is success. Guard defensively.
                if (spirv == null || spirv.remaining() == 0) {
                    throw new RuntimeException("GLSL compilation produced empty SPIR-V output (" + fileName + ")");
                }
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
        try {
            vkWaitForFences(vkDeviceObj, fence, true, Long.MAX_VALUE);
        } finally {
            vkDestroyFence(vkDeviceObj, fence, null);
        }
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

    public static long getMinBufferAlignment() { return minStorageBufferOffsetAlignment; }

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

    // ═══════════════════════════════════════════════════════════════
    //  Utilities
    // ═══════════════════════════════════════════════════════════════

    /** 將 VkPhysicalDeviceType 值轉為可讀字串（供日誌診斷用）。 */
    private static String physDevTypeName(int type) {
        return switch (type) {
            case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU   -> "DISCRETE_GPU";
            case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU -> "INTEGRATED_GPU";
            case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU    -> "VIRTUAL_GPU";
            case VK_PHYSICAL_DEVICE_TYPE_CPU            -> "CPU";
            default                                     -> "OTHER";
        };
    }

    /**
     * 將 Vulkan 結果碼轉為可讀字串，方便診斷日誌。
     *
     * <p>涵蓋 VK 1.0/1.2 常用錯誤碼，包括 RTX 5000 (Blackwell) + NVIDIA 575.xx
     * 驅動相容性最常見的 VK_ERROR_FEATURE_NOT_PRESENT 與 VK_ERROR_INCOMPATIBLE_DRIVER。
     *
     * @param result VK API 回傳值
     * @return 格式 "VK_xxx (N)"，未知碼回傳 "VK_UNKNOWN (N)"
     */
    public static String vkResultToString(int result) {
        return switch (result) {
            case  0  -> "VK_SUCCESS (0)";
            case  1  -> "VK_NOT_READY (1)";
            case  2  -> "VK_TIMEOUT (2)";
            case  3  -> "VK_EVENT_SET (3)";
            case  4  -> "VK_EVENT_RESET (4)";
            case  5  -> "VK_INCOMPLETE (5)";
            case -1  -> "VK_ERROR_OUT_OF_HOST_MEMORY (-1)";
            case -2  -> "VK_ERROR_OUT_OF_DEVICE_MEMORY (-2)";
            case -3  -> "VK_ERROR_INITIALIZATION_FAILED (-3)";
            case -4  -> "VK_ERROR_DEVICE_LOST (-4)";
            case -5  -> "VK_ERROR_MEMORY_MAP_FAILED (-5)";
            case -6  -> "VK_ERROR_LAYER_NOT_PRESENT (-6)";
            case -7  -> "VK_ERROR_EXTENSION_NOT_PRESENT (-7)";
            case -8  -> "VK_ERROR_FEATURE_NOT_PRESENT (-8)";
            case -9  -> "VK_ERROR_INCOMPATIBLE_DRIVER (-9)";
            case -10 -> "VK_ERROR_TOO_MANY_OBJECTS (-10)";
            case -11 -> "VK_ERROR_FORMAT_NOT_SUPPORTED (-11)";
            case -12 -> "VK_ERROR_FRAGMENTED_POOL (-12)";
            case -13 -> "VK_ERROR_UNKNOWN (-13)";
            // VK 1.1+
            case -1000069000 -> "VK_ERROR_OUT_OF_POOL_MEMORY (-1000069000)";
            case -1000072003 -> "VK_ERROR_INVALID_EXTERNAL_HANDLE (-1000072003)";
            // VK 1.2+
            case -1000161000 -> "VK_ERROR_FRAGMENTATION (-1000161000)";
            case -1000257000 -> "VK_ERROR_INVALID_OPAQUE_CAPTURE_ADDRESS (-1000257000)";
            // VMA-specific
            case -1000001000 -> "VK_ERROR_SURFACE_LOST_KHR (-1000001000)";
            case -1000001004 -> "VK_ERROR_NATIVE_WINDOW_IN_USE_KHR (-1000001004)";
            default -> "VK_UNKNOWN (" + result + ")";
        };
    }
}
