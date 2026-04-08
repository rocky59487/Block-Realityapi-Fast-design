package com.blockreality.api.client.render.rt;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;
import static org.lwjgl.vulkan.VK12.*;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.*;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.KHRRayQuery.*;
import static org.lwjgl.vulkan.KHRDeferredHostOperations.*;
import static org.lwjgl.vulkan.KHRBufferDeviceAddress.*;
import static org.lwjgl.vulkan.KHRExternalMemory.*;
import static org.lwjgl.vulkan.KHRGetPhysicalDeviceProperties2.*;
import static org.lwjgl.vulkan.EXTDebugUtils.*;

/**
 * Manages Vulkan device initialization for the hybrid GL+VK ray tracing pipeline.
 * Uses LWJGL Vulkan bindings. All operations are designed for graceful degradation:
 * if Vulkan or RT extensions are unavailable, the system silently disables RT support
 * without crashing the game.
 */
@OnlyIn(Dist.CLIENT)
public final class BRVulkanDevice {

    private static final Logger LOGGER = LoggerFactory.getLogger("BR-VulkanDev");

    private static boolean initialized = false;
    private static boolean rtSupported = false;
    private static long vkInstance;           // VkInstance handle
    private static long vkPhysicalDevice;     // VkPhysicalDevice handle
    private static long vkDevice;             // VkDevice handle
    private static long vkQueue;              // Graphics+Compute queue
    private static int queueFamilyIndex;
    private static long commandPool;
    private static String deviceName = "unknown";
    private static int driverVersion;
    private static boolean hasRTPipeline;     // VK_KHR_ray_tracing_pipeline
    private static boolean hasAccelStruct;    // VK_KHR_acceleration_structure
    private static boolean hasRayQuery;       // VK_KHR_ray_query
    private static boolean hasExternalMemory; // VK_KHR_external_memory

    // ─── RT-0-1: Blackwell / Ada 世代擴展偵測 ──────────────────────────────
    /** VK_NV_ray_tracing_invocation_reorder — Ada SM 8.9 SER（著色器呼叫重排序）*/
    private static boolean hasSER          = false;
    /** VK_EXT_opacity_micromap — Ada OMM 硬體透明度微圖 */
    private static boolean hasOMM          = false;
    /** VK_NV_cluster_acceleration_structure — Blackwell SM 10.x Cluster BVH */
    private static boolean hasClusterAS    = false;
    /** VK_NV_cooperative_vector — Blackwell Cooperative Vectors（Mega Geometry 前置） */
    private static boolean hasCoopVector   = false;
    /** VK_NV_mesh_shader — 可選 Mesh Shader（Blackwell Cluster 加速） */
    private static boolean hasMeshShader   = false;

    // LWJGL wrapper objects (needed for method dispatch)
    private static VkInstance vkInstanceObj;
    private static VkPhysicalDevice vkPhysicalDeviceObj;
    private static VkDevice vkDeviceObj;
    private static VkQueue vkQueueObj;

    private BRVulkanDevice() {}

    /**
     * Full Vulkan initialization. Creates instance, picks a discrete GPU, checks for
     * ray tracing extensions, creates logical device with queues, and creates a command pool.
     * If any step fails, RT support is disabled and the method returns gracefully.
     */
    public static void init() {
        if (initialized) {
            LOGGER.warn("BRVulkanDevice already initialized, skipping");
            return;
        }

        LOGGER.info("Initializing Vulkan device for RT pipeline...");

        try {
            // Step 1: Check if Vulkan is available at all
            try {
                org.lwjgl.vulkan.VK.create();
            } catch (NoClassDefFoundError ncdfe) {
                // ★ FIX: 明確偵測 LWJGL Vulkan JAR 缺失（NoClassDefFoundError）。
                //   根本原因通常是 Minecraft 自帶的 LWJGL 3.3.1 覆蓋了 3.3.5，
                //   導致 lwjgl-vulkan-3.3.5.jar 中的類別在運行期找不到。
                //   解法：確認 api/build.gradle 中 lwjgl-vulkan:3.3.5 已宣告為
                //   'api' + 'runtimeOnly'，並在 minecraft.runs.client 區塊設定
                //   lazyToken('minecraft_classpath') 強制加入 classpath。
                LOGGER.error("[BR-VulkanDev] LWJGL Vulkan 類別找不到：{}", ncdfe.getMessage());
                LOGGER.error("[BR-VulkanDev] 根本原因：lwjgl-vulkan-3.3.5.jar 未在 runClient classpath 上，"
                           + "可能被 Minecraft 自帶的 LWJGL 3.3.1 覆蓋。");
                LOGGER.error("[BR-VulkanDev] 修正方式：執行 './gradlew --stop' 清除 Gradle daemon，"
                           + "再重新執行 './gradlew :fastdesign:runClient'。");
                LOGGER.warn("[BR-VulkanDev] RT 管線已停用，遊戲仍可正常運行（降級至 OpenGL 路徑）。");
                rtSupported = false;
                return;
            } catch (UnsatisfiedLinkError ule) {
                // 系統 Vulkan 驅動程式不存在（例如：虛擬機或老舊 GPU）
                LOGGER.warn("[BR-VulkanDev] 系統 Vulkan 驅動程式未找到：{}", ule.getMessage());
                LOGGER.warn("[BR-VulkanDev] 請確認 GPU 驅動已更新至支援 Vulkan 1.2+ 的版本。");
                LOGGER.warn("[BR-VulkanDev] RT 管線已停用，遊戲仍可正常運行（降級至 OpenGL 路徑）。");
                rtSupported = false;
                return;
            } catch (Throwable e) {
                LOGGER.warn("[BR-VulkanDev] Vulkan 初始化失敗（{}）：{}",
                    e.getClass().getSimpleName(), e.getMessage());
                rtSupported = false;
                return;
            }

            // Step 2: Create VkInstance
            if (!createInstance()) {
                LOGGER.warn("Failed to create Vulkan instance, RT disabled");
                rtSupported = false;
                return;
            }

            // Step 3: Pick physical device (prefer discrete GPU)
            if (!pickPhysicalDevice()) {
                LOGGER.warn("No suitable Vulkan physical device found, RT disabled");
                rtSupported = false;
                cleanupPartial();
                return;
            }

            // Step 4: Check extension support
            checkExtensionSupport();

            // Step 5: Create logical device with enabled extensions and queue
            if (!createLogicalDevice()) {
                LOGGER.warn("Failed to create Vulkan logical device, RT disabled");
                rtSupported = false;
                cleanupPartial();
                return;
            }

            // Step 6: Create command pool
            if (!createCommandPool()) {
                LOGGER.warn("Failed to create Vulkan command pool, RT disabled");
                rtSupported = false;
                cleanupPartial();
                return;
            }

            // Step 7: Determine RT support
            rtSupported = hasRTPipeline && hasAccelStruct;
            initialized = true;

            LOGGER.info("Vulkan device initialized successfully:");
            LOGGER.info("  GPU: {}", deviceName);
            LOGGER.info("  Driver version: {}.{}.{}",
                    VK_VERSION_MAJOR(driverVersion),
                    VK_VERSION_MINOR(driverVersion),
                    VK_VERSION_PATCH(driverVersion));
            LOGGER.info("  RT Pipeline: {}", hasRTPipeline);
            LOGGER.info("  Acceleration Structure: {}", hasAccelStruct);
            LOGGER.info("  Ray Query: {}", hasRayQuery);
            LOGGER.info("  External Memory: {}", hasExternalMemory);
            LOGGER.info("  Ray Tracing supported: {}", rtSupported);
            // RT-0-1: 記錄 Blackwell/Ada 擴展偵測結果
            LOGGER.info("  [RT-0-1] SER (invocation reorder): {}", hasSER);
            LOGGER.info("  [RT-0-1] OMM (opacity micromap):   {}", hasOMM);
            LOGGER.info("  [RT-0-1] Cluster AS (Blackwell):   {}", hasClusterAS);
            LOGGER.info("  [RT-0-1] Coop Vector (Blackwell):  {}", hasCoopVector);
            LOGGER.info("  [RT-0-1] Mesh Shader:              {}", hasMeshShader);

        } catch (Throwable e) {
            LOGGER.error("Fatal error during Vulkan initialization, RT disabled", e);
            rtSupported = false;
            initialized = false;
            cleanupPartial();
        }
    }

    private static boolean createInstance() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                    .pApplicationName(stack.UTF8("BlockReality"))
                    .applicationVersion(VK_MAKE_VERSION(1, 0, 0))
                    .pEngineName(stack.UTF8("BR-RT"))
                    .engineVersion(VK_MAKE_VERSION(1, 0, 0))
                    .apiVersion(VK_API_VERSION_1_2);

            // Fetch available instance extensions
            IntBuffer extCount = stack.mallocInt(1);
            vkEnumerateInstanceExtensionProperties((ByteBuffer)null, extCount, null);
            VkExtensionProperties.Buffer availableExts = VkExtensionProperties.malloc(extCount.get(0), stack);
            vkEnumerateInstanceExtensionProperties((ByteBuffer)null, extCount, availableExts);

            java.util.Set<String> supported = new java.util.HashSet<>();
            for (int i = 0; i < extCount.get(0); i++) {
                supported.add(availableExts.get(i).extensionNameString());
            }

            // Instance extensions (Only request what's actually there)
            java.util.List<String> toEnable = new java.util.ArrayList<>();
            // VK_KHR_get_physical_device_properties2
            if (supported.contains("VK_KHR_get_physical_device_properties2")) {
                toEnable.add("VK_KHR_get_physical_device_properties2");
            }
            // VK_KHR_external_memory_capabilities
            if (supported.contains("VK_KHR_external_memory_capabilities")) {
                toEnable.add("VK_KHR_external_memory_capabilities");
            }
            // VK_KHR_external_semaphore_capabilities
            if (supported.contains("VK_KHR_external_semaphore_capabilities")) {
                toEnable.add("VK_KHR_external_semaphore_capabilities");
            }

            PointerBuffer ppEnabledExtensions = stack.mallocPointer(toEnable.size());
            for (String ext : toEnable) {
                ppEnabledExtensions.put(stack.UTF8(ext));
            }
            ppEnabledExtensions.flip();

            VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                    .pApplicationInfo(appInfo)
                    .ppEnabledExtensionNames(ppEnabledExtensions);

            // Enable validation layers in debug builds only
            boolean enableValidation = System.getProperty("blockreality.vk.validation", "false").equals("true");
            if (enableValidation) {
                PointerBuffer layers = stack.mallocPointer(1);
                layers.put(stack.UTF8("VK_LAYER_KHRONOS_validation"));
                layers.flip();
                createInfo.ppEnabledLayerNames(layers);
                LOGGER.info("Vulkan validation layers enabled");
            }

            PointerBuffer pInstance = stack.mallocPointer(1);
            int result = vkCreateInstance(createInfo, null, pInstance);
            if (result != VK_SUCCESS) {
                LOGGER.error("vkCreateInstance failed with error code: {}", result);
                return false;
            }

            vkInstanceObj = new VkInstance(pInstance.get(0), createInfo);
            vkInstance = pInstance.get(0);
            return true;

        } catch (Throwable e) {
            LOGGER.error("Exception creating Vulkan instance: ", e);
            return false;
        }
    }

    private static boolean pickPhysicalDevice() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer deviceCount = stack.mallocInt(1);
            int result = vkEnumeratePhysicalDevices(vkInstanceObj, deviceCount, null);
            if (result != VK_SUCCESS || deviceCount.get(0) == 0) {
                LOGGER.warn("No Vulkan physical devices found");
                return false;
            }

            PointerBuffer pDevices = stack.mallocPointer(deviceCount.get(0));
            result = vkEnumeratePhysicalDevices(vkInstanceObj, deviceCount, pDevices);
            if (result != VK_SUCCESS) {
                LOGGER.error("Failed to enumerate physical devices: {}", result);
                return false;
            }

            // Prefer discrete GPU, fall back to first available
            VkPhysicalDevice chosenDevice = null;
            VkPhysicalDeviceProperties chosenProps = null;
            boolean foundDiscrete = false;

            for (int i = 0; i < deviceCount.get(0); i++) {
                VkPhysicalDevice candidate = new VkPhysicalDevice(pDevices.get(i), vkInstanceObj);
                VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
                vkGetPhysicalDeviceProperties(candidate, props);

                LOGGER.info("Found GPU: {} (type={})", props.deviceNameString(), props.deviceType());

                if (props.deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU && !foundDiscrete) {
                    chosenDevice = candidate;
                    chosenProps = props;
                    foundDiscrete = true;
                } else if (chosenDevice == null) {
                    chosenDevice = candidate;
                    chosenProps = props;
                }
            }

            if (chosenDevice == null) {
                return false;
            }

            vkPhysicalDeviceObj = chosenDevice;
            vkPhysicalDevice = chosenDevice.address();
            deviceName = chosenProps.deviceNameString();
            driverVersion = chosenProps.driverVersion();

            // Find a queue family that supports both graphics and compute
            IntBuffer queueFamilyCount = stack.mallocInt(1);
            vkGetPhysicalDeviceQueueFamilyProperties(vkPhysicalDeviceObj, queueFamilyCount, null);

            VkQueueFamilyProperties.Buffer queueFamilies =
                    VkQueueFamilyProperties.calloc(queueFamilyCount.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(vkPhysicalDeviceObj, queueFamilyCount, queueFamilies);

            queueFamilyIndex = -1;
            for (int i = 0; i < queueFamilyCount.get(0); i++) {
                int flags = queueFamilies.get(i).queueFlags();
                if ((flags & VK_QUEUE_GRAPHICS_BIT) != 0 && (flags & VK_QUEUE_COMPUTE_BIT) != 0) {
                    queueFamilyIndex = i;
                    break;
                }
            }

            if (queueFamilyIndex == -1) {
                LOGGER.warn("No queue family with both graphics and compute support found");
                return false;
            }

            return true;

        } catch (Exception e) {
            LOGGER.error("Exception picking physical device: {}", e.getMessage());
            return false;
        }
    }

    private static void checkExtensionSupport() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer extensionCount = stack.mallocInt(1);
            vkEnumerateDeviceExtensionProperties(vkPhysicalDeviceObj, (ByteBuffer) null, extensionCount, null);

            VkExtensionProperties.Buffer availableExtensions =
                    VkExtensionProperties.calloc(extensionCount.get(0), stack);
            vkEnumerateDeviceExtensionProperties(vkPhysicalDeviceObj, (ByteBuffer) null, extensionCount, availableExtensions);

            hasRTPipeline = false;
            hasAccelStruct = false;
            hasRayQuery = false;
            hasExternalMemory = false;
            // RT-0-1: 重置 Blackwell/Ada 擴展標誌
            hasSER        = false;
            hasOMM        = false;
            hasClusterAS  = false;
            hasCoopVector = false;
            hasMeshShader = false;

            for (int i = 0; i < extensionCount.get(0); i++) {
                String name = availableExtensions.get(i).extensionNameString();
                switch (name) {
                    // ── 核心 RT 擴展 ──────────────────────────────────────────
                    case "VK_KHR_ray_tracing_pipeline"       -> hasRTPipeline    = true;
                    case "VK_KHR_acceleration_structure"     -> hasAccelStruct   = true;
                    case "VK_KHR_ray_query"                  -> hasRayQuery      = true;
                    case "VK_KHR_external_memory"            -> hasExternalMemory = true;
                    // ── RT-0-1: Ada（SM 8.9）擴展 ─────────────────────────────
                    case "VK_NV_ray_tracing_invocation_reorder" -> hasSER        = true;
                    case "VK_EXT_opacity_micromap"              -> hasOMM        = true;
                    // ── RT-0-1: Blackwell（SM 10.x）擴展 ──────────────────────
                    case "VK_NV_cluster_acceleration_structure" -> hasClusterAS  = true;
                    case "VK_NV_cooperative_vector"             -> hasCoopVector = true;
                    case "VK_NV_mesh_shader"                    -> hasMeshShader = true;
                }
            }

            LOGGER.debug("Blackwell/Ada extensions: SER={} OMM={} ClusterAS={} CoopVec={} MeshShader={}",
                hasSER, hasOMM, hasClusterAS, hasCoopVector, hasMeshShader);

        } catch (Exception e) {
            LOGGER.error("Exception checking extension support: {}", e.getMessage());
            hasRTPipeline = false;
            hasAccelStruct = false;
            hasRayQuery = false;
            hasExternalMemory = false;
            hasSER = false;
            hasOMM = false;
            hasClusterAS = false;
            hasCoopVector = false;
            hasMeshShader = false;
        }
    }

    private static boolean createLogicalDevice() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer queuePriority = stack.floats(1.0f);
            VkDeviceQueueCreateInfo.Buffer queueCreateInfo = VkDeviceQueueCreateInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                    .queueFamilyIndex(queueFamilyIndex)
                    .pQueuePriorities(queuePriority);

            // Build list of extensions to enable
            // Always request these base extensions if available
            String[] requiredExtensions = {
                    VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME,
                    VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME,
                    VK_KHR_RAY_QUERY_EXTENSION_NAME,
                    VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME,
                    VK_KHR_BUFFER_DEVICE_ADDRESS_EXTENSION_NAME,
                    VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME,
                    "VK_KHR_external_memory_fd",           // Linux interop
                    "VK_KHR_external_memory_win32",        // Windows interop (Required for Win10/11)
                    "VK_KHR_external_semaphore_win32",     // Windows interop
                    // RT-0-1: Ada 選用擴展（有則啟用）
                    "VK_NV_ray_tracing_invocation_reorder", // SER — Ada SM8.9+
                    "VK_EXT_opacity_micromap",              // OMM — Ada SM8.9+
                    // RT-0-1: Blackwell 選用擴展
                    "VK_NV_cluster_acceleration_structure", // Cluster BVH — Blackwell SM10+
                    "VK_NV_cooperative_vector",             // Coop Vectors — Blackwell SM10+
                    "VK_NV_mesh_shader"                     // Mesh Shader — Blackwell (optional)
            };

            // Only enable extensions that are actually supported
            int enabledCount = 0;
            boolean[] supported = new boolean[requiredExtensions.length];

            IntBuffer extensionCount = stack.mallocInt(1);
            vkEnumerateDeviceExtensionProperties(vkPhysicalDeviceObj, (ByteBuffer) null, extensionCount, null);
            VkExtensionProperties.Buffer availableExtensions =
                    VkExtensionProperties.calloc(extensionCount.get(0), stack);
            vkEnumerateDeviceExtensionProperties(vkPhysicalDeviceObj, (ByteBuffer) null, extensionCount, availableExtensions);

            for (int i = 0; i < requiredExtensions.length; i++) {
                for (int j = 0; j < extensionCount.get(0); j++) {
                    if (availableExtensions.get(j).extensionNameString().equals(requiredExtensions[i])) {
                        supported[i] = true;
                        enabledCount++;
                        break;
                    }
                }
            }

            PointerBuffer enabledExtensions = stack.mallocPointer(enabledCount);
            for (int i = 0; i < requiredExtensions.length; i++) {
                if (supported[i]) {
                    enabledExtensions.put(stack.UTF8(requiredExtensions[i]));
                    LOGGER.debug("Enabling device extension: {}", requiredExtensions[i]);
                } else {
                    LOGGER.debug("Device extension not available: {}", requiredExtensions[i]);
                }
            }
            enabledExtensions.flip();

            // Enable Vulkan 1.2 features needed for RT
            VkPhysicalDeviceVulkan12Features features12 = VkPhysicalDeviceVulkan12Features.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES)
                    .bufferDeviceAddress(true);

            VkPhysicalDeviceFeatures2 features2 = VkPhysicalDeviceFeatures2.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2)
                    .pNext(features12.address());

            VkDeviceCreateInfo deviceCreateInfo = VkDeviceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                    .pNext(features2.address())
                    .pQueueCreateInfos(queueCreateInfo)
                    .ppEnabledExtensionNames(enabledExtensions);

            PointerBuffer pDevice = stack.mallocPointer(1);
            int result = vkCreateDevice(vkPhysicalDeviceObj, deviceCreateInfo, null, pDevice);
            if (result != VK_SUCCESS) {
                LOGGER.error("vkCreateDevice failed with error code: {}", result);
                return false;
            }

            vkDeviceObj = new VkDevice(pDevice.get(0), vkPhysicalDeviceObj, deviceCreateInfo);
            vkDevice = pDevice.get(0);

            // Retrieve the queue
            PointerBuffer pQueue = stack.mallocPointer(1);
            vkGetDeviceQueue(vkDeviceObj, queueFamilyIndex, 0, pQueue);
            vkQueueObj = new VkQueue(pQueue.get(0), vkDeviceObj);
            vkQueue = pQueue.get(0);

            return true;

        } catch (Exception e) {
            LOGGER.error("Exception creating logical device: {}", e.getMessage());
            return false;
        }
    }

    private static boolean createCommandPool() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(queueFamilyIndex);

            LongBuffer pCommandPool = stack.mallocLong(1);
            int result = vkCreateCommandPool(vkDeviceObj, poolInfo, null, pCommandPool);
            if (result != VK_SUCCESS) {
                LOGGER.error("vkCreateCommandPool failed with error code: {}", result);
                return false;
            }

            commandPool = pCommandPool.get(0);
            return true;

        } catch (Exception e) {
            LOGGER.error("Exception creating command pool: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Destroy the Vulkan device, command pool, and instance in reverse creation order.
     */
    public static void cleanup() {
        if (!initialized) {
            return;
        }

        LOGGER.info("Cleaning up Vulkan device...");

        try {
            if (vkDeviceObj != null) {
                vkDeviceWaitIdle(vkDeviceObj);
            }
        } catch (Exception e) {
            LOGGER.warn("Error waiting for device idle during cleanup: {}", e.getMessage());
        }

        try {
            if (commandPool != VK_NULL_HANDLE && vkDeviceObj != null) {
                vkDestroyCommandPool(vkDeviceObj, commandPool, null);
                commandPool = VK_NULL_HANDLE;
            }
        } catch (Exception e) {
            LOGGER.warn("Error destroying command pool: {}", e.getMessage());
        }

        try {
            if (vkDeviceObj != null) {
                vkDestroyDevice(vkDeviceObj, null);
                vkDeviceObj = null;
                vkDevice = VK_NULL_HANDLE;
            }
        } catch (Exception e) {
            LOGGER.warn("Error destroying device: {}", e.getMessage());
        }

        try {
            if (vkInstanceObj != null) {
                vkDestroyInstance(vkInstanceObj, null);
                vkInstanceObj = null;
                vkInstance = VK_NULL_HANDLE;
            }
        } catch (Exception e) {
            LOGGER.warn("Error destroying instance: {}", e.getMessage());
        }

        vkPhysicalDeviceObj = null;
        vkPhysicalDevice = VK_NULL_HANDLE;
        vkQueueObj = null;
        vkQueue = VK_NULL_HANDLE;
        queueFamilyIndex = 0;

        initialized = false;
        rtSupported = false;
        hasRTPipeline = false;
        hasAccelStruct = false;
        hasRayQuery = false;
        hasExternalMemory = false;
        deviceName = "unknown";
        driverVersion = 0;

        LOGGER.info("Vulkan device cleanup complete");
    }

    /**
     * Partial cleanup used when initialization fails partway through.
     */
    private static void cleanupPartial() {
        try {
            if (commandPool != VK_NULL_HANDLE && vkDeviceObj != null) {
                vkDestroyCommandPool(vkDeviceObj, commandPool, null);
                commandPool = VK_NULL_HANDLE;
            }
        } catch (Exception ignored) {}

        try {
            if (vkDeviceObj != null) {
                vkDestroyDevice(vkDeviceObj, null);
                vkDeviceObj = null;
                vkDevice = VK_NULL_HANDLE;
            }
        } catch (Exception ignored) {}

        try {
            if (vkInstanceObj != null) {
                vkDestroyInstance(vkInstanceObj, null);
                vkInstanceObj = null;
                vkInstance = VK_NULL_HANDLE;
            }
        } catch (Exception ignored) {}

        vkPhysicalDeviceObj = null;
        vkPhysicalDevice = VK_NULL_HANDLE;
        vkQueueObj = null;
        vkQueue = VK_NULL_HANDLE;
    }

    // --- Getters ---

    public static boolean isInitialized() {
        return initialized;
    }

    /**
     * Returns true if both VK_KHR_ray_tracing_pipeline and VK_KHR_acceleration_structure
     * are available on the selected device.
     */
    public static boolean isRTSupported() {
        return rtSupported;
    }

    public static boolean hasRayQuery() {
        return hasRayQuery;
    }

    public static boolean hasExternalMemory() {
        return hasExternalMemory;
    }

    // ── LWJGL 物件存取（供 BRVulkanRT 直接使用 LWJGL API）─────────────────────

    /**
     * 回傳 LWJGL VkInstance 包裝物件。
     * 用於 PFSF VulkanComputeContext 共享裝置。
     */
    public static VkInstance getVkInstanceObj() { return vkInstanceObj; }

    /**
     * 回傳 LWJGL VkDevice 包裝物件（非 raw handle）。
     * 用於需要直接呼叫 LWJGL Vulkan API 的場合（如 vkCreateImage）。
     */
    public static VkDevice getVkDeviceObj() { return vkDeviceObj; }

    /**
     * 回傳 LWJGL VkPhysicalDevice 包裝物件。
     * 用於 vkGetPhysicalDeviceMemoryProperties 等呼叫。
     */
    public static VkPhysicalDevice getVkPhysicalDeviceObj() { return vkPhysicalDeviceObj; }

    /**
     * 回傳 LWJGL VkQueue 包裝物件。
     * 用於 vkQueueSubmit 等呼叫。
     */
    public static VkQueue getVkQueueObj() { return vkQueueObj; }

    /**
     * 回傳用於單次提交指令的 VkCommandPool handle。
     */
    public static long getCommandPoolHandle() { return commandPool; }

    public static long getVkInstance() {
        return vkInstance;
    }

    public static long getVkPhysicalDevice() {
        return vkPhysicalDevice;
    }

    public static long getVkDevice() {
        return vkDevice;
    }

    public static long getVkQueue() {
        return vkQueue;
    }

    public static int getQueueFamilyIndex() {
        return queueFamilyIndex;
    }

    public static long getCommandPool() {
        return commandPool;
    }

    public static String getDeviceName() {
        return deviceName;
    }

    /**
     * 回傳 GPU 的本地 VRAM 大小（MB）。
     *
     * <p>供 {@code BRRenderTier.getRtSubTier()} 用於判斷 RT_ULTRA / RT_HIGH / RT_BALANCED 分級：
     * <ul>
     *   <li>≥ 8192 MB + Ada → RT_ULTRA（全效果 + OMM + SER）</li>
     *   <li>≥ 8192 MB       → RT_HIGH（全 RT，2 bounce）</li>
     *   <li>&lt; 8192 MB    → RT_BALANCED（RT，降低 ray count）</li>
     * </ul>
     *
     *
     * @return VRAM 大小（MB），0 表示未知
     */
    public static int getDeviceVramMb() {
        if (!initialized || vkPhysicalDeviceObj == null) return 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceMemoryProperties memProps = VkPhysicalDeviceMemoryProperties.calloc(stack);
            vkGetPhysicalDeviceMemoryProperties(vkPhysicalDeviceObj, memProps);

            long totalDeviceLocal = 0;
            for (int i = 0; i < memProps.memoryHeapCount(); i++) {
                VkMemoryHeap heap = memProps.memoryHeaps(i);
                if ((heap.flags() & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0) {
                    totalDeviceLocal += heap.size();
                }
            }
            return (int)(totalDeviceLocal / (1024 * 1024));
        } catch (Exception e) {
            LOGGER.warn("Failed to query VRAM: {}", e.getMessage());
            return 0;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  GL/VK Interop — 外部記憶體 fd 匯出（供 BRVKGLSync 使用）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 匯出 RT 輸出 VkImage 的記憶體為 POSIX opaque fd。
     *
     * <p>供 {@link BRVKGLSync} 透過 {@code GL_EXT_memory_object_fd} 匯入，
     * 建立 VK→GL 零拷貝共享紋理。
     *
     * @return POSIX fd（≥ 0），或 -1 表示不支援/尚未實作
     */
    public static int exportRTOutputMemoryFd() {
        return BRVulkanRT.exportOutputMemoryFd();
    }

    /**
     * 匯出 VK RT 完成 Semaphore 為 POSIX opaque fd。
     *
     * <p>供 {@link BRVKGLSync} 透過 {@code GL_EXT_semaphore_fd} 匯入，
     * 讓 GL 等待 VK RT dispatch 完成而無需 CPU 介入（{@code glFinish} 替代方案）。
     *
     * @return POSIX fd（≥ 0），或 -1 表示不支援/尚未實作
     */
    public static int exportVKDoneSemaphoreFd() {
        return BRVulkanRT.exportDoneSemaphoreFd();
    }

    /**
     * CPU readback：從 Vulkan RT 輸出 image 讀取像素（Fallback 路徑）。
     *
     * <p>當 {@code GL_EXT_memory_object_fd} 不可用時，
     * {@link BRVKGLSync} 使用此方法取得像素數據，再透過 PBO 上傳至 GL texture。
     *
     * <h3>Full Implementation (TODO Phase 6)</h3>
     * <ol>
     *   <li>使用 host-visible VkBuffer（staging buffer）</li>
     *   <li>{@code vkCmdCopyImageToBuffer}：RT output image → staging buffer</li>
     *   <li>{@code vkWaitForFences} 確保 copy 完成</li>
     *   <li>{@code vkMapMemory} 取得像素指標，包裝為 ByteBuffer 回傳</li>
     * </ol>
     *
     * @return RGBA16F 像素數據（寬×高×8 bytes），或 null 表示不可用
     */
    public static java.nio.ByteBuffer readbackRTOutputPixels() {
        // Phase 6F: delegate to BRVulkanRT which fills readbackBuffer in traceRays() each frame
        return BRVulkanRT.getReadbackBuffer();
    }

    // --- Command buffer utilities ---

    /**
     * Allocates a single-use command buffer from the command pool.
     *
     * @return the command buffer handle, or VK_NULL_HANDLE on failure
     */
    public static long allocateCommandBuffer() {
        if (!initialized || vkDeviceObj == null) {
            LOGGER.warn("Cannot allocate command buffer: device not initialized");
            return VK_NULL_HANDLE;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(commandPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);

            PointerBuffer pCommandBuffer = stack.mallocPointer(1);
            int result = vkAllocateCommandBuffers(vkDeviceObj, allocInfo, pCommandBuffer);
            if (result != VK_SUCCESS) {
                LOGGER.error("Failed to allocate command buffer: {}", result);
                return VK_NULL_HANDLE;
            }

            long cmdBuffer = pCommandBuffer.get(0);

            // Begin the command buffer for single-use recording
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

            VkCommandBuffer cmdBufferObj = new VkCommandBuffer(cmdBuffer, vkDeviceObj);
            result = vkBeginCommandBuffer(cmdBufferObj, beginInfo);
            if (result != VK_SUCCESS) {
                LOGGER.error("Failed to begin command buffer: {}", result);
                vkFreeCommandBuffers(vkDeviceObj, commandPool, pCommandBuffer);
                return VK_NULL_HANDLE;
            }

            return cmdBuffer;

        } catch (Exception e) {
            LOGGER.error("Exception allocating command buffer: {}", e.getMessage());
            return VK_NULL_HANDLE;
        }
    }

    /**
     * Ends recording on the command buffer, submits it to the queue, and waits
     * for completion using a fence. The command buffer is NOT freed by this method.
     *
     * @param commandBuffer the command buffer handle to submit
     */
    public static void submitAndWait(long commandBuffer) {
        if (!initialized || vkDeviceObj == null || commandBuffer == VK_NULL_HANDLE) {
            LOGGER.warn("Cannot submit command buffer: invalid state");
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer cmdBufferObj = new VkCommandBuffer(commandBuffer, vkDeviceObj);
            int result = vkEndCommandBuffer(cmdBufferObj);
            if (result != VK_SUCCESS) {
                LOGGER.error("Failed to end command buffer: {}", result);
                return;
            }

            // Create a fence for synchronization
            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);

            LongBuffer pFence = stack.mallocLong(1);
            result = vkCreateFence(vkDeviceObj, fenceInfo, null, pFence);
            if (result != VK_SUCCESS) {
                LOGGER.error("Failed to create fence: {}", result);
                return;
            }
            long fence = pFence.get(0);

            try {
                PointerBuffer pCmdBuffers = stack.mallocPointer(1).put(0, commandBuffer);

                VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                        .pCommandBuffers(pCmdBuffers);

                result = vkQueueSubmit(vkQueueObj, submitInfo, fence);
                if (result != VK_SUCCESS) {
                    LOGGER.error("Failed to submit command buffer to queue: {}", result);
                    return;
                }

                // Wait for the fence (10 second timeout)
                result = vkWaitForFences(vkDeviceObj, pFence, true, 10_000_000_000L);
                if (result != VK_SUCCESS) {
                    LOGGER.error("Fence wait failed or timed out: {}", result);
                }

            } finally {
                vkDestroyFence(vkDeviceObj, fence, null);
            }

        } catch (Exception e) {
            LOGGER.error("Exception submitting command buffer: {}", e.getMessage());
        }
    }

    /**
     * Frees a command buffer back to the command pool.
     *
     * @param commandBuffer the command buffer handle to free
     */
    public static void freeCommandBuffer(long commandBuffer) {
        if (!initialized || vkDeviceObj == null || commandBuffer == VK_NULL_HANDLE) {
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pCmdBuffer = stack.mallocPointer(1).put(0, commandBuffer);
            vkFreeCommandBuffers(vkDeviceObj, commandPool, pCmdBuffer);
        } catch (Exception e) {
            LOGGER.error("Exception freeing command buffer: {}", e.getMessage());
        }
    }

    // ── Stub methods for RT pipeline ─────────────────────────────────────

    public static long getDevice() {
        return getVkDevice();
    }

    public static void uploadFloatData(long device, long memory, float[] data, int count) {
        if (!initialized) return;
        LOGGER.warn("uploadFloatData stub called");
    }

    /**
     * 找到符合 typeFilter（VkMemoryRequirements.memoryTypeBits）
     * 且具備指定 {@code properties}（VK_MEMORY_PROPERTY_*）的記憶體類型索引。
     *
     * @param typeFilter  VkMemoryRequirements.memoryTypeBits（位元遮罩）
     * @param properties  所需記憶體屬性（e.g. DEVICE_LOCAL | HOST_VISIBLE）
     * @return 記憶體類型索引（0 表示失敗）
     */
    public static int findMemoryType(int typeFilter, int properties) {
        if (!initialized || vkPhysicalDeviceObj == null) return 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceMemoryProperties memProps =
                VkPhysicalDeviceMemoryProperties.malloc(stack);
            vkGetPhysicalDeviceMemoryProperties(vkPhysicalDeviceObj, memProps);
            for (int i = 0; i < memProps.memoryTypeCount(); i++) {
                boolean typeMatch = (typeFilter & (1 << i)) != 0;
                boolean propMatch = (memProps.memoryTypes(i).propertyFlags() & properties) == properties;
                if (typeMatch && propMatch) {
                    return i;
                }
            }
            LOGGER.error("findMemoryType: no suitable type for filter={} props={}",
                Integer.toBinaryString(typeFilter), Integer.toHexString(properties));
            return 0;
        }
    }

    public static void beginCommandBuffer(long cmdBuffer) {
        if (!initialized) return;
        LOGGER.warn("beginCommandBuffer stub called");
    }

    public static void endAndSubmitCommandBuffer(long cmdBuffer) {
        if (!initialized) return;
        LOGGER.warn("endAndSubmitCommandBuffer stub called");
    }

    public static void waitIdle() {
        if (!initialized) return;
        LOGGER.warn("waitIdle stub called");
    }

    public static void uploadTLASInstances(long device, long memory, java.util.List<?> entries) {
        if (!initialized) return;
        LOGGER.warn("uploadTLASInstances stub called");
    }

    public static void destroyShaderModule(long device, long module) {
        if (!initialized || vkDeviceObj == null || module == 0L) return;
        vkDestroyShaderModule(vkDeviceObj, module, null);
    }

    /**
     * RT shader group handle size（P7-A）：
     * 從 VkPhysicalDeviceRayTracingPipelinePropertiesKHR 取得真實值。
     */
    public static int getRTShaderGroupHandleSize() {
        if (!initialized || vkPhysicalDeviceObj == null) return 32;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceRayTracingPipelinePropertiesKHR rtProps =
                VkPhysicalDeviceRayTracingPipelinePropertiesKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_PROPERTIES_KHR);
            VkPhysicalDeviceProperties2 props2 = VkPhysicalDeviceProperties2.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2)
                .pNext(rtProps.address());
            vkGetPhysicalDeviceProperties2(vkPhysicalDeviceObj, props2);
            int handleSize = rtProps.shaderGroupHandleSize();
            LOGGER.debug("[RT] shaderGroupHandleSize={}", handleSize);
            return handleSize > 0 ? handleSize : 32;
        } catch (Exception e) { return 32; }
    }

    /** P7-C：建立 VkBuffer（不分配記憶體）。usage = VkBufferUsageFlags。 */
    public static long createBuffer(long device, long size, int usage) {
        if (!initialized || vkDeviceObj == null || size <= 0) return 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pBuf = stack.mallocLong(1);
            int r = vkCreateBuffer(vkDeviceObj,
                VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(size).usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE),
                null, pBuf);
            if (r != VK_SUCCESS) { LOGGER.error("[Buffer] vkCreateBuffer failed: {}", r); return 0L; }
            return pBuf.get(0);
        } catch (Exception e) { LOGGER.error("[Buffer] createBuffer failed", e); return 0L; }
    }

    /**
     * P7-C：為 buffer 分配 VkDeviceMemory 並綁定。
     * 若 buffer 用途含 SHADER_DEVICE_ADDRESS，自動加 DEVICE_ADDRESS allocate flag。
     */
    public static long allocateAndBindBuffer(long device, long buffer, int memProps) {
        if (!initialized || vkDeviceObj == null || buffer == 0L) return 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryRequirements reqs = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(vkDeviceObj, buffer, reqs);
            int typeIdx = findMemoryType(reqs.memoryTypeBits(), memProps);
            if (typeIdx < 0) { LOGGER.error("[Buffer] findMemoryType failed"); return 0L; }

            // SHADER_DEVICE_ADDRESS 需要額外 VkMemoryAllocateFlagsInfo
            VkMemoryAllocateFlagsInfo flagsInfo = VkMemoryAllocateFlagsInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_FLAGS_INFO)
                .flags(VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT);
            LongBuffer pMem = stack.mallocLong(1);
            int r = vkAllocateMemory(vkDeviceObj,
                VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .pNext(flagsInfo.address())
                    .allocationSize(reqs.size())
                    .memoryTypeIndex(typeIdx),
                null, pMem);
            if (r != VK_SUCCESS) { LOGGER.error("[Buffer] vkAllocateMemory failed: {}", r); return 0L; }
            vkBindBufferMemory(vkDeviceObj, buffer, pMem.get(0), 0L);
            return pMem.get(0);
        } catch (Exception e) { LOGGER.error("[Buffer] allocateAndBindBuffer failed", e); return 0L; }
    }

    /** P7-C */
    public static void deviceWaitIdle(long device) {
        if (!initialized || vkDeviceObj == null) return;
        vkDeviceWaitIdle(vkDeviceObj);
    }

    /** P7-E cleanup */
    public static void destroyDescriptorPool(long device, long pool) {
        if (!initialized || vkDeviceObj == null || pool == 0L) return;
        vkDestroyDescriptorPool(vkDeviceObj, pool, null);
    }

    /** P7-C */
    public static void destroyBuffer(long device, long buffer) {
        if (!initialized || vkDeviceObj == null || buffer == 0L) return;
        vkDestroyBuffer(vkDeviceObj, buffer, null);
    }

    /** P7-C */
    public static void freeMemory(long device, long memory) {
        if (!initialized || vkDeviceObj == null || memory == 0L) return;
        vkFreeMemory(vkDeviceObj, memory, null);
    }

    /** P7-E cleanup */
    public static void destroyPipeline(long device, long pipeline) {
        if (!initialized || vkDeviceObj == null || pipeline == 0L) return;
        vkDestroyPipeline(vkDeviceObj, pipeline, null);
    }

    /** P7-E cleanup */
    public static void destroyPipelineLayout(long device, long layout) {
        if (!initialized || vkDeviceObj == null || layout == 0L) return;
        vkDestroyPipelineLayout(vkDeviceObj, layout, null);
    }

    /** P7-E cleanup */
    public static void destroyDescriptorSetLayout(long device, long layout) {
        if (!initialized || vkDeviceObj == null || layout == 0L) return;
        vkDestroyDescriptorSetLayout(vkDeviceObj, layout, null);
    }

    /** 分配並開始錄製一次性 command buffer（P7-B：已委派至真實 allocateCommandBuffer）。 */
    public static long beginSingleTimeCommands(long device) {
        if (!initialized) return 0L;
        return allocateCommandBuffer();
    }

    /** P7-F：真實 vkCmdBindPipeline。 */
    public static void cmdBindPipeline(long cmd, int bindPoint, long pipeline) {
        if (!initialized || vkDeviceObj == null || cmd == 0L || pipeline == 0L) return;
        vkCmdBindPipeline(new VkCommandBuffer(cmd, vkDeviceObj), bindPoint, pipeline);
    }

    /** P7-F：真實 vkCmdBindDescriptorSets（單一 descriptor set）。 */
    public static void cmdBindDescriptorSets(long cmd, int bindPoint, long layout,
            int firstSet, long descriptorSet) {
        if (!initialized || vkDeviceObj == null || cmd == 0L || descriptorSet == 0L) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindDescriptorSets(new VkCommandBuffer(cmd, vkDeviceObj),
                bindPoint, layout, firstSet,
                stack.longs(descriptorSet), null);
        }
    }

    /** P7-C：取得 VkBuffer 的 GPU 裝置位址（需 SHADER_DEVICE_ADDRESS_BIT usage）。 */
    public static long getBufferDeviceAddress(long device, long buffer) {
        if (!initialized || vkDeviceObj == null || buffer == 0L) return 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            return vkGetBufferDeviceAddress(vkDeviceObj,
                VkBufferDeviceAddressInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_DEVICE_ADDRESS_INFO)
                    .buffer(buffer));
        } catch (Exception e) { LOGGER.error("[Buffer] getBufferDeviceAddress failed", e); return 0L; }
    }

    /**
     * P7-F：真實 vkCmdTraceRaysKHR。
     * addr/stride/size 三元組分別包裝成 VkStridedDeviceAddressRegionKHR。
     * callable region 設為全零（p1/p2/p3 未使用）。
     */
    public static void cmdTraceRaysKHR(long cmd,
            long rgenAddr, long rgenStride, long rgenSize,
            long missAddr, long missStride, long missSize,
            long hitAddr,  long hitStride,  long hitSize,
            int p1, int p2, int p3, int w, int h, int d) {
        if (!initialized || vkDeviceObj == null || cmd == 0L) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdTraceRaysKHR(new VkCommandBuffer(cmd, vkDeviceObj),
                VkStridedDeviceAddressRegionKHR.calloc(stack)
                    .deviceAddress(rgenAddr).stride(rgenStride).size(rgenSize),
                VkStridedDeviceAddressRegionKHR.calloc(stack)
                    .deviceAddress(missAddr).stride(missStride).size(missSize),
                VkStridedDeviceAddressRegionKHR.calloc(stack)
                    .deviceAddress(hitAddr).stride(hitStride).size(hitSize),
                VkStridedDeviceAddressRegionKHR.calloc(stack), // callable: empty
                w, h, d);
        }
    }

    public static void endSingleTimeCommands(long device, long cmd) {
        if (!initialized) return;
        submitAndWait(cmd);
        freeCommandBuffer(cmd);
    }

    /**
     * ★ P0-fix: 結束 command buffer 並提交，同時 signal 指定的 semaphore。
     * 用於 Vulkan→GL interop：確保 RT 完成後 GL 才讀取共享紋理。
     *
     * @param device 邏輯裝置 handle
     * @param cmd    command buffer handle
     * @param signalSemaphore  完成時 signal 的 VkSemaphore（0 表示不 signal，退回普通路徑）
     */
    public static void endSingleTimeCommandsWithSignal(long device, long cmd, long signalSemaphore) {
        if (!initialized || vkDeviceObj == null) return;
        if (signalSemaphore == 0L) {
            endSingleTimeCommands(device, cmd);
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer cmdObj = new VkCommandBuffer(cmd, vkDeviceObj);
            int result = vkEndCommandBuffer(cmdObj);
            if (result != VK_SUCCESS) {
                LOGGER.error("[P0-fix] Failed to end command buffer: {}", result);
                freeCommandBuffer(cmd);
                return;
            }

            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);
            LongBuffer pFence = stack.mallocLong(1);
            result = vkCreateFence(vkDeviceObj, fenceInfo, null, pFence);
            if (result != VK_SUCCESS) {
                freeCommandBuffer(cmd);
                return;
            }
            long fence = pFence.get(0);

            try {
                PointerBuffer pCmdBuffers = stack.mallocPointer(1).put(0, cmd);
                LongBuffer pSignal = stack.mallocLong(1).put(0, signalSemaphore).flip();

                VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                        .pCommandBuffers(pCmdBuffers)
                        .pSignalSemaphores(pSignal);

                result = vkQueueSubmit(vkQueueObj, submitInfo, fence);
                if (result != VK_SUCCESS) {
                    LOGGER.error("[P0-fix] vkQueueSubmit with signal semaphore failed: {}", result);
                    return;
                }

                vkWaitForFences(vkDeviceObj, pFence, true, 10_000_000_000L);
            } finally {
                vkDestroyFence(vkDeviceObj, fence, null);
            }
        } catch (Exception e) {
            LOGGER.error("[P0-fix] endSingleTimeCommandsWithSignal error: {}", e.getMessage());
        }
        freeCommandBuffer(cmd);
    }

    /**
     * P7-E：更新 RT descriptor set。
     * Binding 0 = TLAS（VkWriteDescriptorSetAccelerationStructureKHR）
     * Binding 1 = RT output storage image（VK_IMAGE_LAYOUT_GENERAL）
     */
    public static void updateRTDescriptorSet(long device, long set, long tlas, long imageView) {
        if (!initialized || vkDeviceObj == null || set == 0L) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 計算有幾個 write 操作
            int writeCount = (tlas != 0L ? 1 : 0) + (imageView != 0L ? 1 : 0);
            if (writeCount == 0) return;
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(writeCount, stack);
            int idx = 0;

            if (tlas != 0L) {
                // Binding 0: TLAS（pNext 鏈帶 VkWriteDescriptorSetAccelerationStructureKHR）
                VkWriteDescriptorSetAccelerationStructureKHR tlasInfo =
                    VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_ACCELERATION_STRUCTURE_KHR)
                        .pAccelerationStructures(stack.longs(tlas));
                writes.get(idx++)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .pNext(tlasInfo.address())
                    .dstSet(set).dstBinding(0).descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR);
            }

            if (imageView != 0L) {
                // Binding 1: storage image
                writes.get(idx)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(set).dstBinding(1).descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .pImageInfo(VkDescriptorImageInfo.calloc(1, stack)
                        .imageView(imageView)
                        .imageLayout(VK_IMAGE_LAYOUT_GENERAL));
            }

            vkUpdateDescriptorSets(vkDeviceObj, writes, null);
            LOGGER.debug("[DS] updateRTDescriptorSet: tlas={} imageView={}", tlas, imageView);
        } catch (Exception e) { LOGGER.error("[DS] updateRTDescriptorSet failed", e); }
    }

    /**
     * 將當前幀相機數據寫入 CameraUBO offset 0（invViewProj）和 offset 160/176（cameraPos/sunDir）。
     *
     * <p>每幀在 traceRays() 之前呼叫，確保 raygen shader 取得最新相機矩陣。
     *
     * @param device   VkDevice handle（未使用，保留供未來擴展）
     * @param set      descriptor set handle（未使用，數據直接寫入 HOST_COHERENT buffer）
     * @param invVP    inverse(view * projection) 矩陣，JOML column-major
     * @param cx cy cz 相機世界座標
     * @param lx ly lz 正規化太陽/主光源方向
     */
    public static void updateCameraUBO(long device, long set, org.joml.Matrix4f invVP,
                                       float cx, float cy, float cz, float lx, float ly, float lz) {
        if (!initialized || cameraUboMemory == 0L) {
            LOGGER.debug("[UBO] updateCameraUBO skipped (not allocated)");
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pData = stack.mallocPointer(1);
            int r = vkMapMemory(vkDeviceObj, cameraUboMemory, 0, 256, 0, pData);
            if (r != VK_SUCCESS) {
                LOGGER.error("[UBO] updateCameraUBO: vkMapMemory failed ({})", r);
                return;
            }
            long base = pData.get(0);

            // ── offset 0–63: mat4 invViewProj（column-major, 16 × 4 bytes） ────────
            MemoryUtil.memPutFloat(base +  0, invVP.m00());
            MemoryUtil.memPutFloat(base +  4, invVP.m01());
            MemoryUtil.memPutFloat(base +  8, invVP.m02());
            MemoryUtil.memPutFloat(base + 12, invVP.m03());
            MemoryUtil.memPutFloat(base + 16, invVP.m10());
            MemoryUtil.memPutFloat(base + 20, invVP.m11());
            MemoryUtil.memPutFloat(base + 24, invVP.m12());
            MemoryUtil.memPutFloat(base + 28, invVP.m13());
            MemoryUtil.memPutFloat(base + 32, invVP.m20());
            MemoryUtil.memPutFloat(base + 36, invVP.m21());
            MemoryUtil.memPutFloat(base + 40, invVP.m22());
            MemoryUtil.memPutFloat(base + 44, invVP.m23());
            MemoryUtil.memPutFloat(base + 48, invVP.m30());
            MemoryUtil.memPutFloat(base + 52, invVP.m31());
            MemoryUtil.memPutFloat(base + 56, invVP.m32());
            MemoryUtil.memPutFloat(base + 60, invVP.m33());

            // ── offset 160–175: vec4 cameraPos（.xyz = 世界座標, .w = 0） ──────────
            // （offset 64=prevInvVP 64B, 128=weatherData 16B, 144=frameIndex 4B,
            //   148–159=pad 12B → 下一個 vec4 對齊至 offset 160）
            MemoryUtil.memPutFloat(base + 160, cx);
            MemoryUtil.memPutFloat(base + 164, cy);
            MemoryUtil.memPutFloat(base + 168, cz);
            MemoryUtil.memPutFloat(base + 172, 0.0f);

            // ── offset 176–191: vec4 sunDir（.xyz = 正規化方向, .w = 0） ──────────
            MemoryUtil.memPutFloat(base + 176, lx);
            MemoryUtil.memPutFloat(base + 180, ly);
            MemoryUtil.memPutFloat(base + 184, lz);
            MemoryUtil.memPutFloat(base + 188, 0.0f);

            vkUnmapMemory(vkDeviceObj, cameraUboMemory);
        }
    }

    /**
     * P7-E（更新）：建立 RT pipeline 的 VkDescriptorSetLayout。
     * 7 個 binding 完整對應 GLSL raygen/chit/miss shader 宣告：
     * <pre>
     * Binding 0: ACCELERATION_STRUCTURE（TLAS，allRT）
     * Binding 1: STORAGE_IMAGE         （RT output RGBA16F，raygen）
     * Binding 2: COMBINED_IMAGE_SAMPLER（GBuffer depth，raygen）
     * Binding 3: COMBINED_IMAGE_SAMPLER（GBuffer normal，raygen）
     * Binding 4: COMBINED_IMAGE_SAMPLER（GBuffer material roughness/metallic，raygen）
     * Binding 5: COMBINED_IMAGE_SAMPLER（GBuffer motion vector，raygen）
     * Binding 6: UNIFORM_BUFFER        （CameraUBO 256B，allRT）
     * </pre>
     */
    public static long createRTDescriptorSetLayout(long device) {
        if (!initialized || vkDeviceObj == null) return 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int allRT    = VK_SHADER_STAGE_RAYGEN_BIT_KHR
                         | VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR
                         | VK_SHADER_STAGE_MISS_BIT_KHR;
            int raygenOnly = VK_SHADER_STAGE_RAYGEN_BIT_KHR;

            VkDescriptorSetLayoutBinding.Buffer bindings =
                VkDescriptorSetLayoutBinding.calloc(7, stack);
            // 0: TLAS
            bindings.get(0).binding(0)
                .descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                .descriptorCount(1).stageFlags(allRT);
            // 1: RT output image (storage)
            bindings.get(1).binding(1)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .descriptorCount(1).stageFlags(raygenOnly);
            // 2: GBuffer depth
            bindings.get(2).binding(2)
                .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(1).stageFlags(raygenOnly);
            // 3: GBuffer normal (world-space octahedron)
            bindings.get(3).binding(3)
                .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(1).stageFlags(raygenOnly);
            // 4: GBuffer material (roughness.r, metallic.g)
            bindings.get(4).binding(4)
                .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(1).stageFlags(raygenOnly);
            // 5: GBuffer motion vector (TAA/ReSTIR temporal reuse)
            bindings.get(5).binding(5)
                .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(1).stageFlags(raygenOnly);
            // 6: CameraUBO (invVP, prevInvVP, weatherData, frameIndex, cameraPos, sunDir)
            bindings.get(6).binding(6)
                .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                .descriptorCount(1).stageFlags(allRT);

            LongBuffer pLayout = stack.mallocLong(1);
            int r = vkCreateDescriptorSetLayout(vkDeviceObj,
                VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .pBindings(bindings),
                null, pLayout);
            if (r != VK_SUCCESS) { LOGGER.error("[DS] vkCreateDescriptorSetLayout failed: {}", r); return 0L; }
            LOGGER.debug("[DS] RT descriptor set layout created (7 bindings): {}", pLayout.get(0));
            return pLayout.get(0);
        } catch (Exception e) { LOGGER.error("[DS] createRTDescriptorSetLayout failed", e); return 0L; }
    }

    /** P7-E：建立 VkPipelineLayout（單一 descriptor set，無 push constant）。 */
    public static long createPipelineLayout(long device, long dsLayout) {
        if (!initialized || vkDeviceObj == null) return 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pLayout = stack.mallocLong(1);
            int r = vkCreatePipelineLayout(vkDeviceObj,
                VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(stack.longs(dsLayout)),
                null, pLayout);
            if (r != VK_SUCCESS) { LOGGER.error("[DS] vkCreatePipelineLayout failed: {}", r); return 0L; }
            return pLayout.get(0);
        } catch (Exception e) { LOGGER.error("[DS] createPipelineLayout failed", e); return 0L; }
    }

    /**
     * GLSL → SPIR-V 編譯（P7-A：shaderc 執行期編譯）。
     * shader 類型由 name 副檔名推斷（.rgen / .rmiss / .rchit / .rahit / .comp / .vert / .frag）。
     */
    public static byte[] compileGLSLtoSPIRV(String glslSource, String name) {
        long compiler = Shaderc.shaderc_compiler_initialize();
        if (compiler == 0L) {
            LOGGER.error("[SPIR-V] shaderc_compiler_initialize failed for {}", name);
            return new byte[0];
        }
        long options = Shaderc.shaderc_compile_options_initialize();
        Shaderc.shaderc_compile_options_set_target_env(options,
            Shaderc.shaderc_target_env_vulkan,
            Shaderc.shaderc_env_version_vulkan_1_3);
        Shaderc.shaderc_compile_options_set_optimization_level(options,
            Shaderc.shaderc_optimization_level_performance);

        int kind = shadercKindFromName(name);
        long result = Shaderc.shaderc_compile_into_spv(
            compiler, glslSource, kind, name, "main", options);
        Shaderc.shaderc_compile_options_release(options);
        Shaderc.shaderc_compiler_release(compiler);

        int status = Shaderc.shaderc_result_get_compilation_status(result);
        if (status != Shaderc.shaderc_compilation_status_success) {
            LOGGER.error("[SPIR-V] {} compile error: {}",
                name, Shaderc.shaderc_result_get_error_message(result));
            Shaderc.shaderc_result_release(result);
            return new byte[0];
        }

        ByteBuffer spvBytes = Shaderc.shaderc_result_get_bytes(result);
        long spvSize = Shaderc.shaderc_result_get_length(result);
        byte[] spv   = new byte[(int) spvSize];
        if (spvBytes != null) spvBytes.get(spv);
        Shaderc.shaderc_result_release(result);
        LOGGER.info("[SPIR-V] {} compiled OK ({} bytes)", name, spvSize);
        return spv;
    }

    /** 根據 shader 名稱推斷 shaderc shader kind。 */
    private static int shadercKindFromName(String name) {
        if (name.endsWith(".rgen"))  return Shaderc.shaderc_raygen_shader;
        if (name.endsWith(".rmiss")) return Shaderc.shaderc_miss_shader;
        if (name.endsWith(".rchit")) return Shaderc.shaderc_closesthit_shader;
        if (name.endsWith(".rahit")) return Shaderc.shaderc_anyhit_shader;
        if (name.endsWith(".comp"))  return Shaderc.shaderc_compute_shader;
        if (name.endsWith(".vert"))  return Shaderc.shaderc_vertex_shader;
        if (name.endsWith(".frag"))  return Shaderc.shaderc_fragment_shader;
        return Shaderc.shaderc_glsl_infer_from_source;
    }

    /** SPIR-V byte[] → VkShaderModule handle。 */
    public static long createShaderModule(long device, byte[] spirv) {
        if (!initialized || vkDeviceObj == null || spirv == null || spirv.length == 0) return 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer spvBuf = stack.malloc(spirv.length);
            spvBuf.put(spirv).flip();
            LongBuffer pModule = stack.mallocLong(1);
            int r = vkCreateShaderModule(vkDeviceObj,
                VkShaderModuleCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                    .pCode(spvBuf),
                null, pModule);
            if (r != VK_SUCCESS) { LOGGER.error("[Shader] vkCreateShaderModule failed: {}", r); return 0L; }
            return pModule.get(0);
        } catch (Exception e) { LOGGER.error("[Shader] createShaderModule failed", e); return 0L; }
    }

    /**
     * P7-D：建立無 anyhit 的 RT pipeline（向後相容版本）。
     * 委派至帶 anyhit 的主路徑，ahit=0 表示不使用。
     */
    public static long createRayTracingPipeline(long device, long layout,
            long rgen, long miss, long chit, int maxRecursion) {
        return createRayTracingPipelineWithAnyHit(device, layout, rgen, miss, chit, 0L, maxRecursion);
    }

    /**
     * P7-D：建立帶有 any-hit 著色器的 RT pipeline（3 個 shader 群組）。
     * Group 0 = GENERAL(raygen) | Group 1 = GENERAL(miss) | Group 2 = TRIANGLES_HIT(chit+ahit)
     */
    public static long createRayTracingPipelineWithAnyHit(long device, long layout,
            long rgen, long miss, long chit, long ahit, int maxRecursion) {
        if (!initialized || vkDeviceObj == null) return 0L;
        boolean hasAhit = ahit != 0L;
        int stageCount = hasAhit ? 4 : 3;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer entryPoint = stack.UTF8("main");
            VkPipelineShaderStageCreateInfo.Buffer stages =
                VkPipelineShaderStageCreateInfo.calloc(stageCount, stack);
            stages.get(0).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                .stage(VK_SHADER_STAGE_RAYGEN_BIT_KHR).module(rgen).pName(entryPoint);
            stages.get(1).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                .stage(VK_SHADER_STAGE_MISS_BIT_KHR).module(miss).pName(entryPoint);
            stages.get(2).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                .stage(VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR).module(chit).pName(entryPoint);
            if (hasAhit) {
                stages.get(3).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_ANY_HIT_BIT_KHR).module(ahit).pName(entryPoint);
            }

            VkRayTracingShaderGroupCreateInfoKHR.Buffer groups =
                VkRayTracingShaderGroupCreateInfoKHR.calloc(3, stack);
            // Group 0: raygen (GENERAL)
            groups.get(0).sType(VK_STRUCTURE_TYPE_RAY_TRACING_SHADER_GROUP_CREATE_INFO_KHR)
                .type(VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR).generalShader(0)
                .closestHitShader(VK_SHADER_UNUSED_KHR).anyHitShader(VK_SHADER_UNUSED_KHR)
                .intersectionShader(VK_SHADER_UNUSED_KHR);
            // Group 1: miss (GENERAL)
            groups.get(1).sType(VK_STRUCTURE_TYPE_RAY_TRACING_SHADER_GROUP_CREATE_INFO_KHR)
                .type(VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR).generalShader(1)
                .closestHitShader(VK_SHADER_UNUSED_KHR).anyHitShader(VK_SHADER_UNUSED_KHR)
                .intersectionShader(VK_SHADER_UNUSED_KHR);
            // Group 2: hit group (TRIANGLES)
            groups.get(2).sType(VK_STRUCTURE_TYPE_RAY_TRACING_SHADER_GROUP_CREATE_INFO_KHR)
                .type(VK_RAY_TRACING_SHADER_GROUP_TYPE_TRIANGLES_HIT_GROUP_KHR)
                .generalShader(VK_SHADER_UNUSED_KHR).closestHitShader(2)
                .anyHitShader(hasAhit ? 3 : VK_SHADER_UNUSED_KHR)
                .intersectionShader(VK_SHADER_UNUSED_KHR);

            LongBuffer pPipeline = stack.mallocLong(1);
            VkRayTracingPipelineCreateInfoKHR.Buffer pipelineInfo =
                VkRayTracingPipelineCreateInfoKHR.calloc(1, stack);
            pipelineInfo.get(0)
                .sType(VK_STRUCTURE_TYPE_RAY_TRACING_PIPELINE_CREATE_INFO_KHR)
                .pStages(stages).pGroups(groups)
                .maxPipelineRayRecursionDepth(maxRecursion)
                .layout(layout);
            int r = vkCreateRayTracingPipelinesKHR(vkDeviceObj,
                VK_NULL_HANDLE, VK_NULL_HANDLE,
                pipelineInfo,
                null, pPipeline);
            if (r != VK_SUCCESS) {
                LOGGER.error("[RTPipeline] vkCreateRayTracingPipelinesKHR failed: {}", r);
                return 0L;
            }
            LOGGER.info("[RTPipeline] created handle={} ahit={}", pPipeline.get(0), hasAhit);
            return pPipeline.get(0);
        } catch (Exception e) { LOGGER.error("[RTPipeline] creation failed", e); return 0L; }
    }

    /**
     * Alias for createRayTracingPipelineWithAnyHit — used by BRVulkanRT.
     * The lowercase 'hit' variant matches historical code in BRVulkanRT.java.
     */
    public static long createRTPipelineWithAnyhit(long device, long layout,
            long rgen, long miss, long chit, long ahit, int maxRecursion) {
        return createRayTracingPipelineWithAnyHit(device, layout, rgen, miss, chit, ahit, maxRecursion);
    }

    /** P7-D：取得 SBT 的 shader group handles byte array。 */
    public static byte[] getRayTracingShaderGroupHandles(long device, long pipeline,
            int groupCount, int handleSize) {
        if (!initialized || vkDeviceObj == null || pipeline == 0L) return new byte[0];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int totalSize = groupCount * handleSize;
            ByteBuffer buf = stack.malloc(totalSize);
            int r = vkGetRayTracingShaderGroupHandlesKHR(vkDeviceObj,
                pipeline, 0, groupCount, buf);
            if (r != VK_SUCCESS) {
                LOGGER.error("[SBT] vkGetRayTracingShaderGroupHandlesKHR failed: {}", r);
                return new byte[0];
            }
            byte[] result = new byte[totalSize];
            buf.get(result);
            return result;
        } catch (Exception e) { LOGGER.error("[SBT] getRayTracingShaderGroupHandles failed", e); return new byte[0]; }
    }

    /** P7-C：映射 VkDeviceMemory 到 host，回傳指標。 */
    public static long mapMemory(long device, long memory, int offset, long size) {
        if (!initialized || vkDeviceObj == null || memory == 0L) return 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pData = stack.mallocPointer(1);
            int r = vkMapMemory(vkDeviceObj, memory, (long) offset, size, 0, pData);
            if (r != VK_SUCCESS) { LOGGER.error("[Mem] vkMapMemory failed: {}", r); return 0L; }
            return pData.get(0);
        } catch (Exception e) { LOGGER.error("[Mem] mapMemory failed", e); return 0L; }
    }

    /** P7-C：byte[] → GPU mapped memory 直接複製（零拷貝）。 */
    public static void memcpy(long dst, byte[] src, int srcOffset, int length) {
        if (dst == 0L || src == null || length <= 0) return;
        MemoryUtil.memByteBuffer(dst, length).put(src, srcOffset, length);
    }

    /**
     * 將 {@link java.nio.ByteBuffer} 資料複製到 GPU mapped memory。
     *
     * <p>與 {@link #memcpy(long, byte[], int, int)} 相同功能，
     * 但接受 {@code ByteBuffer} 作為來源（避免 {@code ByteBuffer → byte[]} 額外複製）。
     * 供 {@link com.blockreality.api.client.rendering.vulkan.BRAdaRTConfig#uploadDAGToGPU()}
     * 上傳 {@code BRSparseVoxelDAG.serializeForGPU()} 結果使用。
     *
     * <p>實作應使用 {@code org.lwjgl.system.MemoryUtil.memCopy()} 或
     * {@code sun.misc.Unsafe} 進行直接記憶體複製（零拷貝）。
     *
     * @param dst    目標 GPU mapped memory pointer（由 {@link #mapMemory} 返回）
     * @param src    來源 {@code ByteBuffer}（position 到 limit 的資料）
     * @param length 複製長度（bytes），必須 ≤ {@code src.remaining()}
     */
    /** P7-C：ByteBuffer → GPU mapped memory 直接複製。 */
    public static void memcpyBuffer(long dst, java.nio.ByteBuffer src, int length) {
        if (dst == 0L || src == null || length <= 0) return;
        MemoryUtil.memByteBuffer(dst, length).put(src.duplicate().limit(length));
    }

    /** P7-C */
    public static void unmapMemory(long device, long memory) {
        if (!initialized || vkDeviceObj == null || memory == 0L) return;
        vkUnmapMemory(vkDeviceObj, memory);
    }

    /**
     * P7-E（更新）：建立 VkDescriptorPool，能分配 1 個 7-binding RT descriptor set。
     * 對應 createRTDescriptorSetLayout 的 7 個 binding：
     *   1× AS, 1× storage image, 4× combined sampler（depth/normal/material/motion）, 1× UBO
     */
    public static long createRTDescriptorPool(long device) {
        if (!initialized || vkDeviceObj == null) return 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(4, stack);
            poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1);
            poolSizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1);
            // 4 samplers: depth + normal + material + motion
            poolSizes.get(2).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(4);
            poolSizes.get(3).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1);

            LongBuffer pPool = stack.mallocLong(1);
            int r = vkCreateDescriptorPool(vkDeviceObj,
                VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                    .maxSets(1).pPoolSizes(poolSizes),
                null, pPool);
            if (r != VK_SUCCESS) { LOGGER.error("[DS] vkCreateDescriptorPool failed: {}", r); return 0L; }
            return pPool.get(0);
        } catch (Exception e) { LOGGER.error("[DS] createRTDescriptorPool failed", e); return 0L; }
    }

    /** P7-E：從 pool 分配一個 descriptor set。 */
    public static long allocateDescriptorSet(long device, long pool, long layout) {
        if (!initialized || vkDeviceObj == null || pool == 0L || layout == 0L) return 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pSet = stack.mallocLong(1);
            int r = vkAllocateDescriptorSets(vkDeviceObj,
                VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(pool)
                    .pSetLayouts(stack.longs(layout)),
                pSet);
            if (r != VK_SUCCESS) { LOGGER.error("[DS] vkAllocateDescriptorSets failed: {}", r); return 0L; }
            return pSet.get(0);
        } catch (Exception e) { LOGGER.error("[DS] allocateDescriptorSet failed", e); return 0L; }
    }

    // ── GPU Memory ──────────────────────────────────────────────────────────

    // Singleton CameraUBO buffer + memory (256 bytes, HOST_VISIBLE | HOST_COHERENT)
    public static long cameraUboBuffer = 0L;
    public static long cameraUboMemory = 0L;

    /**
     * CameraUBO 記憶體佈局（256 bytes，std140 對齊）：
     * <pre>
     *  offset   0 –  63 : mat4 invViewProj       （當前幀逆 view-projection）
     *  offset  64 – 127 : mat4 prevInvViewProj    （前一幀，供 SVGF/temporal reuse）
     *  offset 128 – 143 : vec4 weatherData        （.x=wetness, .y=snowCoverage）
     *  offset 144 – 147 : float frameIndex        （Halton/blue-noise 隨機化用）
     *  offset 148 – 159 : padding                 （對齊下一個 vec4 至 offset 160）
     *  offset 160 – 175 : vec4 cameraPos          （.xyz = 相機世界座標）
     *  offset 176 – 191 : vec4 sunDir             （.xyz = 正規化太陽方向）
     *  offset 192 – 255 : reserved
     * </pre>
     * 對應 GLSL（binding=4）：
     * <pre>
     * layout(set=0, binding=4) uniform CameraUBO {
     *     mat4  invViewProj;
     *     mat4  prevInvViewProj;
     *     vec4  weatherData;
     *     float frameIndex;
     *     float _pad0, _pad1, _pad2;
     *     vec4  cameraPos;
     *     vec4  sunDir;
     * } cam;
     * </pre>
     */
    public static long createCameraUBO(long device, long descriptorSet) {
        if (!initialized || vkDeviceObj == null) return 0L;
        if (cameraUboBuffer != 0L) {
            LOGGER.debug("[UBO] createCameraUBO: already allocated (buf={})", cameraUboBuffer);
            return cameraUboBuffer;
        }
        final int UBO_SIZE = 256;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // ── 1. 建立 VkBuffer ──────────────────────────────────────────────────
            LongBuffer pBuf = stack.mallocLong(1);
            int r = vkCreateBuffer(vkDeviceObj,
                VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(UBO_SIZE)
                    .usage(VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE),
                null, pBuf);
            if (r != VK_SUCCESS) { LOGGER.error("[UBO] vkCreateBuffer failed: {}", r); return 0L; }
            long buf = pBuf.get(0);

            // ── 2. 查詢記憶體需求 ─────────────────────────────────────────────────
            VkMemoryRequirements memReqs = VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(vkDeviceObj, buf, memReqs);

            int memType = findMemoryType(memReqs.memoryTypeBits(),
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            if (memType <= 0) {
                LOGGER.error("[UBO] findMemoryType failed for CameraUBO");
                vkDestroyBuffer(vkDeviceObj, buf, null);
                return 0L;
            }

            // ── 3. 分配記憶體（不需 DEVICE_ADDRESS flag） ─────────────────────────
            LongBuffer pMem = stack.mallocLong(1);
            r = vkAllocateMemory(vkDeviceObj,
                VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(memReqs.size())
                    .memoryTypeIndex(memType),
                null, pMem);
            if (r != VK_SUCCESS) {
                LOGGER.error("[UBO] vkAllocateMemory failed: {}", r);
                vkDestroyBuffer(vkDeviceObj, buf, null);
                return 0L;
            }
            long mem = pMem.get(0);
            vkBindBufferMemory(vkDeviceObj, buf, mem, 0);

            // ── 4. 清零初始化 ──────────────────────────────────────────────────────
            PointerBuffer pData = stack.mallocPointer(1);
            if (vkMapMemory(vkDeviceObj, mem, 0, UBO_SIZE, 0, pData) == VK_SUCCESS) {
                MemoryUtil.memSet(pData.get(0), 0, UBO_SIZE);
                vkUnmapMemory(vkDeviceObj, mem);
            }

            // ── 5. 儲存靜態 handle ────────────────────────────────────────────────
            cameraUboBuffer = buf;
            cameraUboMemory = mem;

            // ── 6. 更新 descriptor set binding 4（UNIFORM_BUFFER） ───────────────
            if (descriptorSet != 0L) {
                VkDescriptorBufferInfo.Buffer bufInfo = VkDescriptorBufferInfo.calloc(1, stack);
                bufInfo.get(0).buffer(buf).offset(0).range(UBO_SIZE);

                VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(1, stack);
                writes.get(0)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(descriptorSet)
                    .dstBinding(6)
                    .descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .pBufferInfo(bufInfo);
                vkUpdateDescriptorSets(vkDeviceObj, writes, null);
                LOGGER.debug("[UBO] descriptor set {} binding 6 → CameraUBO buf={}", descriptorSet, buf);
            }

            LOGGER.info("[UBO] CameraUBO created: buf={} mem={} size={}B", buf, mem, UBO_SIZE);
            return buf;
        } catch (Exception e) {
            LOGGER.error("[UBO] createCameraUBO failed", e);
            return 0L;
        }
    }

    // ── Weather + frame index UBO updaters (called by BRVulkanRT) ──────────

    /**
     * 將 prevInvViewProj 寫入 CameraUBO offset 64（第二個 mat4）。
     *
     * <p>CameraUBO 記憶體佈局（256 bytes 對齊）：
     * <pre>
     *  offset   0 – 63  : mat4 invViewProj      （當前幀）
     *  offset  64 – 127 : mat4 prevInvViewProj   （前一幀，本方法寫入）
     *  offset 128 – 143 : vec4 weatherData
     *  offset 144 – 147 : float frameIndex
     * </pre>
     * 供 SVGF temporal reprojection / motion vector 計算使用。
     */
    public static void updatePrevInvViewProjUBO(long device, long descriptorSet,
                                                 org.joml.Matrix4f prevInvVP) {
        if (!initialized || cameraUboMemory == 0L) {
            LOGGER.debug("updatePrevInvViewProjUBO: UBO not allocated");
            return;
        }
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            org.lwjgl.PointerBuffer pData = stack.mallocPointer(1);
            int result = org.lwjgl.vulkan.VK10.vkMapMemory(
                    vkDeviceObj, cameraUboMemory, 0, 256, 0, pData);
            if (result == org.lwjgl.vulkan.VK10.VK_SUCCESS) {
                long addr = pData.get(0) + 64L; // offset 64 = second mat4
                // JOML mat4 is column-major — write 16 floats in column order
                org.lwjgl.system.MemoryUtil.memPutFloat(addr +  0, prevInvVP.m00());
                org.lwjgl.system.MemoryUtil.memPutFloat(addr +  4, prevInvVP.m01());
                org.lwjgl.system.MemoryUtil.memPutFloat(addr +  8, prevInvVP.m02());
                org.lwjgl.system.MemoryUtil.memPutFloat(addr + 12, prevInvVP.m03());
                org.lwjgl.system.MemoryUtil.memPutFloat(addr + 16, prevInvVP.m10());
                org.lwjgl.system.MemoryUtil.memPutFloat(addr + 20, prevInvVP.m11());
                org.lwjgl.system.MemoryUtil.memPutFloat(addr + 24, prevInvVP.m12());
                org.lwjgl.system.MemoryUtil.memPutFloat(addr + 28, prevInvVP.m13());
                org.lwjgl.system.MemoryUtil.memPutFloat(addr + 32, prevInvVP.m20());
                org.lwjgl.system.MemoryUtil.memPutFloat(addr + 36, prevInvVP.m21());
                org.lwjgl.system.MemoryUtil.memPutFloat(addr + 40, prevInvVP.m22());
                org.lwjgl.system.MemoryUtil.memPutFloat(addr + 44, prevInvVP.m23());
                org.lwjgl.system.MemoryUtil.memPutFloat(addr + 48, prevInvVP.m30());
                org.lwjgl.system.MemoryUtil.memPutFloat(addr + 52, prevInvVP.m31());
                org.lwjgl.system.MemoryUtil.memPutFloat(addr + 56, prevInvVP.m32());
                org.lwjgl.system.MemoryUtil.memPutFloat(addr + 60, prevInvVP.m33());
                org.lwjgl.vulkan.VK10.vkUnmapMemory(vkDeviceObj, cameraUboMemory);
            } else {
                LOGGER.debug("updatePrevInvViewProjUBO: vkMapMemory failed ({})", result);
            }
        }
    }

    /**
     * Write weather uniforms (wetness, snowCoverage) into the CameraUBO at the
     * weatherData field (offset 128 bytes from UBO start, vec4 layout).
     */
    public static void updateWeatherUBO(long device, long descriptorSet,
                                         float wetness, float snowCoverage) {
        if (!initialized || cameraUboMemory == 0L) {
            LOGGER.debug("updateWeatherUBO: wetness={}, snow={} (UBO not allocated)", wetness, snowCoverage);
            return;
        }
        
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            org.lwjgl.PointerBuffer pData = stack.mallocPointer(1);
            int result = org.lwjgl.vulkan.VK10.vkMapMemory(vkDeviceObj, cameraUboMemory, 0, 256, 0, pData);
            if (result == org.lwjgl.vulkan.VK10.VK_SUCCESS) {
                long address = pData.get(0);
                // offset 128 for weatherData
                org.lwjgl.system.MemoryUtil.memPutFloat(address + 128, wetness);
                org.lwjgl.system.MemoryUtil.memPutFloat(address + 132, snowCoverage);
                
                org.lwjgl.vulkan.VK10.vkUnmapMemory(vkDeviceObj, cameraUboMemory);
            } else {
                LOGGER.error("Failed to map memory for CameraUBO weather data, error: {}", result);
            }
        }
    }

    /**
     * Write the current frame index into the CameraUBO's frameIndex field
     * (float at offset 144 bytes from UBO start).
     */
    /**
     * 建立含 OMM（Opacity Micromap）的 BLAS — 專用於 triangle geometry（Phase 3 路徑）。
     *
     * <p>目前 BLAS 使用 AABB geometry（{@code VkAccelerationStructureGeometryAabbsDataKHR}），
     * OMM 僅支援 triangle geometry（{@code VkAccelerationStructureGeometryTrianglesDataKHR}）。
     * 此方法為 Phase 3 LOD 0 遷移至 triangle geometry 後預留的 OMM 整合入口。
     *
     * <h4>OMM 格式（VK_EXT_opacity_micromap）</h4>
     * <ul>
     *   <li>{@code VK_OPACITY_MICROMAP_FORMAT_2_STATE_EXT}：每 micro-triangle 1 bit
     *       （0 = FULLY_TRANSPARENT, 1 = FULLY_OPAQUE）</li>
     *   <li>Subdivision level 0 = 1 micro-triangle per triangle（等同 per-face 精度）</li>
     * </ul>
     *
     * <h4>OMM state 分配策略</h4>
     * <ul>
     *   <li>solidBlockFaces → {@code FULLY_OPAQUE}（石頭/混凝土/鋼鐵等，跳過 any-hit）</li>
     *   <li>transparentFaces → {@code UNKNOWN_OPAQUE}（玻璃/水/葉片，觸發 any-hit）</li>
     * </ul>
     *
     * @param device          Vulkan logical device
     * @param sectionX        section X 座標
     * @param sectionZ        section Z 座標
     * @param triangleData    triangle vertex buffer device address
     * @param triangleCount   triangle 總數
     * @param ommStateData    OMM bitfield（每 triangle 1 bit，2-state format）
     *                        長度 = {@code ceil(triangleCount / 8)} bytes
     * @return 新建 BLAS handle（0 = 失敗）
     */
    public static long buildBLASWithOMM(long device, int sectionX, int sectionZ,
                                         long triangleData, int triangleCount,
                                         byte[] ommStateData) {
        LOGGER.info("buildBLASWithOMM unsupported in Forge 1.20.1 / LWJGL 3.3.1. Requires EXTOpacityMicromap extension. Gracefully falling back.");
        // OMM (Opacity Micromap) requires `EXTOpacityMicromap` and `vkCreateMicromapEXT`,
        // which are not available in the current LWJGL version bound to Minecraft 1.20.1.
        return 0L;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  RT-0-1: Blackwell / Ada 擴展偵測結果 Getter
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 是否支援 VK_NV_ray_tracing_invocation_reorder（SER）。
     * Ada Lovelace（SM 8.9, RTX 40xx）及以上支援。
     * SER 允許 GPU 按材料 / 幾何重新排序 RT invocation，大幅減少 warp 分歧。
     *
     * @return true if SER extension is available
     */
    public static boolean hasSER() { return hasSER; }

    /**
     * 是否支援 VK_EXT_opacity_micromap（OMM）。
     * Ada Lovelace 及以上支援；Blackwell 同樣支援。
     * OMM 使玻璃/水/葉片的 alpha-test 由硬體在 BVH 遍歷時直接處理。
     *
     * @return true if OMM extension is available
     */
    public static boolean hasOMM() { return hasOMM; }

    /**
     * 是否支援 VK_NV_cluster_acceleration_structure（Cluster BVH）。
     * Blackwell（SM 10.x, RTX 50xx）專屬。
     * Cluster BVH 可將鄰近 LOD section 打包成 cluster，減少 TLAS instance 數量 8-16×。
     *
     * @return true if Cluster AS extension is available
     */
    public static boolean hasClusterAS() { return hasClusterAS; }

    /**
     * 是否支援 VK_NV_cooperative_vector（Cooperative Vectors）。
     * Blackwell 專屬；為 MegaGeometry 和 Neural Rendering 前置依賴。
     *
     * @return true if Cooperative Vector extension is available
     */
    public static boolean hasCoopVector() { return hasCoopVector; }

    /**
     * 是否支援 VK_NV_mesh_shader。
     * Blackwell 可利用 Mesh Shader 加速 Cluster 幾何提交。
     *
     * @return true if Mesh Shader extension is available
     */
    public static boolean hasMeshShader() { return hasMeshShader; }

    /**
     * 根據已偵測的擴展，推斷 GPU 世代 Tier（供其他模組參考，無需依賴 BRAdaRTConfig）。
     * <ul>
     *   <li>Blackwell（ClusterAS + CoopVec）→ 2</li>
     *   <li>Ada（SER）→ 1</li>
     *   <li>Legacy RT（RT Pipeline + Accel Struct）→ 0</li>
     *   <li>不支援 RT → -1</li>
     * </ul>
     *
     * @return inferred GPU tier (-1 to 2)
     */
    public static int inferredGpuTier() {
        if (!rtSupported) return -1;
        if (hasClusterAS && hasCoopVector) return 2; // Blackwell
        if (hasSER) return 1;                        // Ada
        return 0;                                    // Legacy RT (Ampere / Turing)
    }

    public static void updateFrameIndexUBO(long device, long descriptorSet, long frameIndex) {
        if (!initialized || cameraUboMemory == 0L) {
            LOGGER.debug("updateFrameIndexUBO: frame={} (UBO not allocated)", frameIndex);
            return;
        }

        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            org.lwjgl.PointerBuffer pData = stack.mallocPointer(1);
            int result = org.lwjgl.vulkan.VK10.vkMapMemory(vkDeviceObj, cameraUboMemory, 0, 256, 0, pData);
            if (result == org.lwjgl.vulkan.VK10.VK_SUCCESS) {
                long address = pData.get(0);
                // offset 144 for frameIndex (float representation for Halton use)
                org.lwjgl.system.MemoryUtil.memPutFloat(address + 144, (float) frameIndex);
                
                org.lwjgl.vulkan.VK10.vkUnmapMemory(vkDeviceObj, cameraUboMemory);
            } else {
                LOGGER.error("Failed to map memory for CameraUBO frame data, error: {}", result);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // P0-B: RTAO Descriptor Set Layouts + Pipeline Layout + Image Helpers
    // GLSL sets: set0(TLAS b0, AO out b4, AO hist b5), set1(depth b0, normal b1),
    //            set2(CameraFrame UBO b0)
    // ══════════════════════════════════════════════════════════════════════════

    /** RTAO set 0: binding 0=TLAS, 4=AOOutput (storage rg16f), 5=AOHistory (storage rg16f). */
    public static long createRTAOSet0Layout(long device) {
        if (!initialized || vkDeviceObj == null) return 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(3, stack);
            b.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            b.get(1).binding(4).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            b.get(2).binding(5).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            LongBuffer pL = stack.mallocLong(1);
            int r = vkCreateDescriptorSetLayout(vkDeviceObj,
                VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO).pBindings(b),
                null, pL);
            if (r != VK_SUCCESS) { LOGGER.error("[RTAO] set0 layout failed: {}", r); return 0L; }
            return pL.get(0);
        } catch (Exception e) { LOGGER.error("[RTAO] createRTAOSet0Layout", e); return 0L; }
    }

    /** RTAO set 1: binding 0=depth sampler, 1=normal sampler (both COMBINED_IMAGE_SAMPLER). */
    public static long createRTAOSet1Layout(long device) {
        if (!initialized || vkDeviceObj == null) return 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(2, stack);
            b.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            b.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            LongBuffer pL = stack.mallocLong(1);
            int r = vkCreateDescriptorSetLayout(vkDeviceObj,
                VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO).pBindings(b),
                null, pL);
            if (r != VK_SUCCESS) { LOGGER.error("[RTAO] set1 layout failed: {}", r); return 0L; }
            return pL.get(0);
        } catch (Exception e) { LOGGER.error("[RTAO] createRTAOSet1Layout", e); return 0L; }
    }

    /** RTAO set 2: binding 0=CameraFrame UBO (scalar). */
    public static long createRTAOSet2Layout(long device) {
        if (!initialized || vkDeviceObj == null) return 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(1, stack);
            b.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            LongBuffer pL = stack.mallocLong(1);
            int r = vkCreateDescriptorSetLayout(vkDeviceObj,
                VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO).pBindings(b),
                null, pL);
            if (r != VK_SUCCESS) { LOGGER.error("[RTAO] set2 layout failed: {}", r); return 0L; }
            return pL.get(0);
        } catch (Exception e) { LOGGER.error("[RTAO] createRTAOSet2Layout", e); return 0L; }
    }

    /** RTAO pipeline layout using 3 descriptor set layouts (set 0, 1, 2). */
    public static long createRTAOPipelineLayout(long device, long set0, long set1, long set2) {
        if (!initialized || vkDeviceObj == null) return 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pL = stack.mallocLong(1);
            int r = vkCreatePipelineLayout(vkDeviceObj,
                VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(stack.longs(set0, set1, set2)),
                null, pL);
            if (r != VK_SUCCESS) { LOGGER.error("[RTAO] pipeline layout failed: {}", r); return 0L; }
            return pL.get(0);
        } catch (Exception e) { LOGGER.error("[RTAO] createRTAOPipelineLayout", e); return 0L; }
    }

    /**
     * RTAO descriptor pool: 1×AS + 2×STORAGE_IMAGE + 2×COMBINED_SAMPLER + 1×UBO,
     * maxSets=3.
     */
    public static long createRTAODescriptorPool(long device) {
        if (!initialized || vkDeviceObj == null) return 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorPoolSize.Buffer ps = VkDescriptorPoolSize.calloc(4, stack);
            ps.get(0).type(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1);
            ps.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(2);
            ps.get(2).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(2);
            ps.get(3).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1);
            LongBuffer pP = stack.mallocLong(1);
            int r = vkCreateDescriptorPool(vkDeviceObj,
                VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                    .maxSets(3).pPoolSizes(ps),
                null, pP);
            if (r != VK_SUCCESS) { LOGGER.error("[RTAO] pool failed: {}", r); return 0L; }
            return pP.get(0);
        } catch (Exception e) { LOGGER.error("[RTAO] createRTAODescriptorPool", e); return 0L; }
    }

    /**
     * Allocates 3 descriptor sets [set0, set1, set2] from the given pool.
     * Returns long[3] or null on failure.
     */
    public static long[] allocateRTAODescriptorSets(long pool, long l0, long l1, long l2) {
        if (!initialized || vkDeviceObj == null) return null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pSets = stack.mallocLong(3);
            int r = vkAllocateDescriptorSets(vkDeviceObj,
                VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(pool)
                    .pSetLayouts(stack.longs(l0, l1, l2)),
                pSets);
            if (r != VK_SUCCESS) { LOGGER.error("[RTAO] allocate sets failed: {}", r); return null; }
            return new long[]{ pSets.get(0), pSets.get(1), pSets.get(2) };
        } catch (Exception e) { LOGGER.error("[RTAO] allocateRTAODescriptorSets", e); return null; }
    }

    /**
     * Creates a 2D VkImage + VkDeviceMemory + VkImageView (DEVICE_LOCAL).
     * Returns long[3] = {image, memory, imageView} or null on failure.
     *
     * @param format VkFormat constant (e.g. VK_FORMAT_R16G16_SFLOAT)
     * @param usage  VkImageUsageFlags
     * @param aspect VkImageAspectFlags (VK_IMAGE_ASPECT_COLOR_BIT for colour)
     */
    public static long[] createImage2D(long device, int width, int height,
                                        int format, int usage, int aspect) {
        if (!initialized || vkDeviceObj == null) return null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pImg = stack.mallocLong(1);
            VkExtent3D ext = VkExtent3D.calloc(stack).width(width).height(height).depth(1);
            int r = vkCreateImage(vkDeviceObj,
                VkImageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(format)
                    .extent(ext)
                    .mipLevels(1).arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED),
                null, pImg);
            if (r != VK_SUCCESS) { LOGGER.error("[Image] vkCreateImage failed: {}", r); return null; }
            long image = pImg.get(0);

            VkMemoryRequirements reqs = VkMemoryRequirements.malloc(stack);
            vkGetImageMemoryRequirements(vkDeviceObj, image, reqs);
            int memType = findMemoryType(reqs.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            if (memType <= 0) {
                vkDestroyImage(vkDeviceObj, image, null);
                LOGGER.error("[Image] findMemoryType DEVICE_LOCAL failed");
                return null;
            }
            LongBuffer pMem = stack.mallocLong(1);
            r = vkAllocateMemory(vkDeviceObj,
                VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(reqs.size()).memoryTypeIndex(memType),
                null, pMem);
            if (r != VK_SUCCESS) {
                vkDestroyImage(vkDeviceObj, image, null);
                LOGGER.error("[Image] vkAllocateMemory failed: {}", r);
                return null;
            }
            long mem = pMem.get(0);
            vkBindImageMemory(vkDeviceObj, image, mem, 0);

            VkImageSubresourceRange viewRange = VkImageSubresourceRange.calloc(stack)
                .aspectMask(aspect)
                .baseMipLevel(0).levelCount(1)
                .baseArrayLayer(0).layerCount(1);

            LongBuffer pView = stack.mallocLong(1);
            r = vkCreateImageView(vkDeviceObj,
                VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(image).viewType(VK_IMAGE_VIEW_TYPE_2D).format(format)
                    .subresourceRange(viewRange),
                null, pView);
            if (r != VK_SUCCESS) {
                vkDestroyImage(vkDeviceObj, image, null);
                vkFreeMemory(vkDeviceObj, mem, null);
                LOGGER.error("[Image] vkCreateImageView failed: {}", r);
                return null;
            }
            return new long[]{ image, mem, pView.get(0) };
        } catch (Exception e) { LOGGER.error("[Image] createImage2D failed", e); return null; }
    }

    /**
     * Transitions a VkImage layout via one-shot command buffer.
     * Uses TOP_OF_PIPE → COMPUTE_SHADER stage (correct for init from UNDEFINED).
     */
    public static void transitionImageLayout(long device, long image,
                                              int oldLayout, int newLayout, int aspectMask) {
        if (!initialized || vkDeviceObj == null || image == 0L) return;
        long cmd = beginSingleTimeCommands(device);
        if (cmd == 0L) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageSubresourceRange transRange = VkImageSubresourceRange.calloc(stack)
                .aspectMask(aspectMask)
                .baseMipLevel(0).levelCount(1)
                .baseArrayLayer(0).layerCount(1);
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
            barrier.get(0)
                .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .oldLayout(oldLayout).newLayout(newLayout)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image)
                .subresourceRange(transRange)
                .srcAccessMask(0)
                .dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
            vkCmdPipelineBarrier(new VkCommandBuffer(cmd, vkDeviceObj),
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                0, null, null, barrier);
        }
        endSingleTimeCommands(device, cmd);
    }

    /** Creates a NEAREST + CLAMP_TO_EDGE VkSampler for GBuffer reads. */
    public static long createNearestSampler(long device) {
        if (!initialized || vkDeviceObj == null) return 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pS = stack.mallocLong(1);
            int r = vkCreateSampler(vkDeviceObj,
                VkSamplerCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                    .magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .maxLod(0.0f),
                null, pS);
            if (r != VK_SUCCESS) { LOGGER.error("[Sampler] vkCreateSampler failed: {}", r); return 0L; }
            return pS.get(0);
        } catch (Exception e) { LOGGER.error("[Sampler] createNearestSampler", e); return 0L; }
    }

    /** Destroys a VkSampler. */
    public static void destroySampler(long device, long sampler) {
        if (!initialized || vkDeviceObj == null || sampler == 0L) return;
        vkDestroySampler(vkDeviceObj, sampler, null);
    }

    /** Destroys resources created by createImage2D (view + image + memory). */
    public static void destroyImage2D(long device, long image, long memory, long view) {
        if (!initialized || vkDeviceObj == null) return;
        if (view   != 0L) vkDestroyImageView(vkDeviceObj, view, null);
        if (image  != 0L) vkDestroyImage(vkDeviceObj, image, null);
        if (memory != 0L) vkFreeMemory(vkDeviceObj, memory, null);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // P0-C: GPU buffer zero-fill（ReSTIR DI/GI Reservoir 初始化）
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 透過一次性 command buffer 執行 vkCmdFillBuffer，將 buffer 填充為指定值，
     * 並插入 TRANSFER_WRITE → COMPUTE+RT 的 pipeline barrier。
     *
     * <p>用於 ReSTIR DI/GI Reservoir buffer 的 GPU 端清零（空 reservoir：
     * lightIdx=0, W=0.0f, M=0, flags=0）。
     *
     * @param device VkDevice handle（保留參數，實際使用 vkDeviceObj）
     * @param buffer 目標 VkBuffer（必須含 VK_BUFFER_USAGE_TRANSFER_DST_BIT）
     * @param offset 起始 byte offset（對 reservoir 清零傳 0）
     * @param size   填充 byte 數；傳 -1L 表示 VK_WHOLE_SIZE
     * @param data   填充值（清零傳 0）
     */
    public static void cmdFillBuffer(long device, long buffer, long offset, long size, int data) {
        if (!initialized || vkDeviceObj == null || buffer == 0L) return;
        long cmd = beginSingleTimeCommands(device);
        if (cmd == 0L) {
            LOGGER.error("[Buffer] cmdFillBuffer: beginSingleTimeCommands failed");
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdFillBuffer(new VkCommandBuffer(cmd, vkDeviceObj), buffer, offset, size, data);
            // Barrier: TRANSFER_WRITE → COMPUTE | RT_SHADER read/write
            VkBufferMemoryBarrier.Buffer barrier = VkBufferMemoryBarrier.calloc(1, stack);
            barrier.get(0)
                .sType(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .buffer(buffer)
                .offset(offset)
                .size(size);
            vkCmdPipelineBarrier(new VkCommandBuffer(cmd, vkDeviceObj),
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                0, null, barrier, null);
        } catch (Exception e) {
            LOGGER.error("[Buffer] cmdFillBuffer exception", e);
        }
        endSingleTimeCommands(device, cmd);
        LOGGER.debug("[Buffer] cmdFillBuffer: buf={} offset={} size={} data=0x{}",
            buffer, offset, size, Integer.toHexString(data));
    }
    /**
     * Uploads ByteBuffer data to a device-local buffer via a host-visible staging buffer.
     * Used by SVDAGLOD3Tracer and similar subsystems that push CPU data to the GPU.
     *
     * @param device    Vulkan logical device handle (reserved; uses vkDeviceObj internally)
     * @param buffer    Target VkBuffer (must have VK_BUFFER_USAGE_TRANSFER_DST_BIT)
     * @param data      Source ByteBuffer (position 0 .. limit = data to upload)
     * @param size      Number of bytes to upload
     */
    public static void uploadBufferData(long device, long buffer, java.nio.ByteBuffer data, int size) {
        if (!initialized || vkDeviceObj == null || buffer == 0L || data == null || size <= 0) return;

        // Create a host-visible staging buffer
        long stagingBuf = createBuffer(device, size,
            VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
        if (stagingBuf == 0L) {
            LOGGER.error("[Upload] failed to create staging buffer (size={})", size);
            return;
        }
        long stagingMem = allocateAndBindBuffer(device, stagingBuf,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        if (stagingMem == 0L) {
            destroyBuffer(device, stagingBuf);
            LOGGER.error("[Upload] failed to allocate staging memory");
            return;
        }

        // Map, copy, unmap
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pData = stack.mallocPointer(1);
            if (vkMapMemory(vkDeviceObj, stagingMem, 0, size, 0, pData) != VK_SUCCESS) {
                destroyBuffer(device, stagingBuf);
                freeMemory(device, stagingMem);
                return;
            }
            MemoryUtil.memCopy(MemoryUtil.memAddress(data), pData.get(0), size);
            vkUnmapMemory(vkDeviceObj, stagingMem);
        } catch (Exception e) {
            LOGGER.error("[Upload] copy failed", e);
            destroyBuffer(device, stagingBuf);
            freeMemory(device, stagingMem);
            return;
        }

        // Submit a one-time copy command
        long cmd = beginSingleTimeCommands(device);
        if (cmd != 0L) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack);
                region.get(0).srcOffset(0).dstOffset(0).size(size);
                vkCmdCopyBuffer(new VkCommandBuffer(cmd, vkDeviceObj), stagingBuf, buffer, region);
            }
            endSingleTimeCommands(device, cmd);
        }

        destroyBuffer(device, stagingBuf);
        freeMemory(device, stagingMem);
        LOGGER.debug("[Upload] uploaded {} bytes to buffer {}", size, buffer);
    }
}

