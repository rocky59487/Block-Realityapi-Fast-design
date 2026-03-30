package com.blockreality.api.client.rendering.vulkan;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.KHRGetPhysicalDeviceProperties2.VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRSurface.VK_KHR_SURFACE_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.vkEnumerateInstanceVersion;

/**
 * Vulkan 裝置初始化與上下文管理（Phase 2-A）。
 *
 * 移植來源：Radiance Mod MCVR VulkanBase.cpp → LWJGL Java API
 *
 * 初始化順序：
 *   VkInstance
 *     └─ VkPhysicalDevice（選擇支援 RT 的離散 GPU）
 *          └─ VkDevice（啟用 RT + buffer device address extensions）
 *               ├─ VkQueue（graphics queue）
 *               ├─ VkQueue（compute queue）
 *               └─ VkCommandPool
 *
 * 必要 Extension：
 *   Instance: VK_KHR_get_physical_device_properties2
 *   Device:   VK_KHR_ray_tracing_pipeline
 *             VK_KHR_acceleration_structure
 *             VK_KHR_deferred_host_operations
 *             VK_KHR_buffer_device_address
 *             VK_EXT_descriptor_indexing
 *             VK_KHR_spirv_1_4
 *             VK_KHR_shader_float_controls
 *
 * Block Reality 的 GL-Vulkan 混合策略：
 *   Minecraft 繼續使用 OpenGL；Vulkan 只用於 RT 計算，
 *   結果透過 GL_EXT_memory_object 匯入 GL texture 後合成。
 *
 * @see VkAccelStructBuilder
 * @see VkRTPipeline
 * @see VkMemoryAllocator
 */
@OnlyIn(Dist.CLIENT)
public final class VkContext {

    private static final Logger LOG = LoggerFactory.getLogger("BR-VkContext");

    // ─── 必要 Device Extensions ───
    public static final String[] REQUIRED_DEVICE_EXTENSIONS = {
        KHRAccelerationStructure.VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME,
        KHRRayTracingPipeline.VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME,
        KHRDeferredHostOperations.VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME,
        "VK_KHR_buffer_device_address",
        "VK_EXT_descriptor_indexing",
        "VK_KHR_spirv_1_4",
        "VK_KHR_shader_float_controls"
    };

    // ─── Vulkan 物件 ───
    private VkInstance       vkInstance;
    private VkPhysicalDevice physicalDevice;
    private VkDevice         device;
    private VkQueue          graphicsQueue;
    private VkQueue          computeQueue;
    private long             commandPool = VK_NULL_HANDLE;
    private long             debugMessenger = VK_NULL_HANDLE;

    private int graphicsQueueFamily = -1;
    private int computeQueueFamily  = -1;

    // ─── 狀態 ───
    private boolean initialized = false;
    private boolean rtSupported = false;
    private String  gpuName     = "Unknown";
    private long    vramBytes   = 0;

    // ─── 單例 ───
    private static VkContext instance;

    private VkContext() {}

    public static VkContext getInstance() {
        if (instance == null) instance = new VkContext();
        return instance;
    }

    // ═══ 初始化 ═══

    /**
     * 完整 Vulkan 上下文初始化。
     *
     * 必須在支援 Vulkan 的 GPU 上才能成功，
     * 失敗不影響遊戲運行（LOD 渲染仍可用）。
     *
     * @return true 若初始化成功且 RT 可用
     */
    public boolean init() {
        if (initialized) {
            LOG.warn("VkContext already initialized");
            return rtSupported;
        }

        try {
            if (!createInstance())      { return false; }
            if (!selectPhysicalDevice()){ cleanup(); return false; }
            if (!createLogicalDevice()) { cleanup(); return false; }
            if (!createCommandPool())   { cleanup(); return false; }

            initialized = true;
            LOG.info("VkContext initialized — GPU: {}, VRAM: {} MB, RT: {}",
                gpuName, vramBytes / (1024 * 1024), rtSupported);
            return rtSupported;

        } catch (Exception e) {
            LOG.error("VkContext init failed: {}", e.getMessage(), e);
            cleanup();
            return false;
        }
    }

    // ═══ 私有初始化步驟 ═══

    /** 1. 建立 VkInstance */
    private boolean createInstance() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 應用程式資訊
            VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                .pApplicationName(stack.UTF8("Block Reality"))
                .applicationVersion(VK_MAKE_VERSION(2, 0, 0))
                .pEngineName(stack.UTF8("BR-Engine"))
                .engineVersion(VK_MAKE_VERSION(2, 0, 0))
                .apiVersion(VK11.VK_API_VERSION_1_1);

            // Instance extensions
            List<String> instExtList = new ArrayList<>();
            instExtList.add(VK_KHR_SURFACE_EXTENSION_NAME);
            instExtList.add(VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME);

            // 在 debug build 加入 validation layer / debug utils
            boolean enableValidation = Boolean.getBoolean("br.vulkan.validation");
            if (enableValidation) {
                instExtList.add(VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
            }

            PointerBuffer pExtNames = stack.mallocPointer(instExtList.size());
            for (String ext : instExtList) pExtNames.put(stack.UTF8(ext));
            pExtNames.flip();

            PointerBuffer pLayerNames = null;
            if (enableValidation) {
                pLayerNames = stack.pointers(stack.UTF8("VK_LAYER_KHRONOS_validation"));
            }

            VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                .pApplicationInfo(appInfo)
                .ppEnabledExtensionNames(pExtNames)
                .ppEnabledLayerNames(pLayerNames);

            PointerBuffer pInstance = stack.mallocPointer(1);
            int result = vkCreateInstance(createInfo, null, pInstance);
            if (result != VK_SUCCESS) {
                LOG.error("vkCreateInstance failed: {}", vkResultString(result));
                return false;
            }
            vkInstance = new VkInstance(pInstance.get(0), createInfo);

            if (enableValidation) {
                setupDebugMessenger(stack);
            }
            return true;
        }
    }

    /** 2. 選擇物理裝置（優先選擇支援 RT 的離散 GPU） */
    private boolean selectPhysicalDevice() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pCount = stack.mallocInt(1);
            vkEnumeratePhysicalDevices(vkInstance, pCount, null);
            int count = pCount.get(0);

            if (count == 0) {
                LOG.error("No Vulkan-capable GPU found");
                return false;
            }

            PointerBuffer pDevices = stack.mallocPointer(count);
            vkEnumeratePhysicalDevices(vkInstance, pCount, pDevices);

            VkPhysicalDevice bestDevice = null;
            boolean bestHasRT = false;
            long    bestVRAM  = 0;

            for (int i = 0; i < count; i++) {
                VkPhysicalDevice candidate = new VkPhysicalDevice(pDevices.get(i), vkInstance);
                boolean hasRT = checkDeviceRTSupport(candidate, stack);
                long    vram  = getDeviceVRAM(candidate, stack);

                // 優先選擇：有 RT 的 > VRAM 大的
                if (bestDevice == null ||
                    (!bestHasRT && hasRT) ||
                    (bestHasRT == hasRT && vram > bestVRAM)) {
                    bestDevice = candidate;
                    bestHasRT = hasRT;
                    bestVRAM  = vram;
                }
            }

            physicalDevice = bestDevice;
            rtSupported    = bestHasRT;
            vramBytes      = bestVRAM;

            // 讀取 GPU 名稱
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(physicalDevice, props);
            gpuName = props.deviceNameString();

            LOG.info("Selected GPU: {} | RT: {} | VRAM: {} MB",
                gpuName, rtSupported, vramBytes / (1024 * 1024));

            // 即使沒有 RT，仍繼續初始化（LOD 仍可用，RT 功能停用）
            return true;
        }
    }

    /** 3. 建立邏輯裝置和 Queue */
    private boolean createLogicalDevice() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            findQueueFamilies(stack);
            if (graphicsQueueFamily < 0) {
                LOG.error("No suitable graphics queue family found");
                return false;
            }

            // Queue create infos
            boolean separateCompute = computeQueueFamily != graphicsQueueFamily;
            int queueInfoCount = separateCompute ? 2 : 1;

            VkDeviceQueueCreateInfo.Buffer queueInfos =
                VkDeviceQueueCreateInfo.calloc(queueInfoCount, stack);

            queueInfos.get(0)
                .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                .queueFamilyIndex(graphicsQueueFamily)
                .pQueuePriorities(stack.floats(1.0f));

            if (separateCompute) {
                queueInfos.get(1)
                    .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                    .queueFamilyIndex(computeQueueFamily)
                    .pQueuePriorities(stack.floats(1.0f));
            }

            // 啟用的 Device Extensions（若 RT 不支援則只啟用基本 extension）
            List<String> devExtList = new ArrayList<>();
            if (rtSupported) {
                for (String ext : REQUIRED_DEVICE_EXTENSIONS) devExtList.add(ext);
            }

            PointerBuffer ppExtNames = stack.mallocPointer(devExtList.size());
            for (String ext : devExtList) ppExtNames.put(stack.UTF8(ext));
            ppExtNames.flip();

            // 啟用 RT 相關 features
            VkPhysicalDeviceFeatures2 features2 = VkPhysicalDeviceFeatures2.calloc(stack)
                .sType(VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2);

            VkPhysicalDeviceBufferDeviceAddressFeatures bufferDeviceAddrFeatures = null;
            VkPhysicalDeviceRayTracingPipelineFeaturesKHR rtFeatures = null;
            VkPhysicalDeviceAccelerationStructureFeaturesKHR asFeatures = null;

            if (rtSupported) {
                bufferDeviceAddrFeatures = VkPhysicalDeviceBufferDeviceAddressFeatures.calloc(stack)
                    .sType(VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_BUFFER_DEVICE_ADDRESS_FEATURES)
                    .bufferDeviceAddress(true);

                asFeatures = VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(stack)
                    .sType(KHRAccelerationStructure.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR)
                    .accelerationStructure(true);

                rtFeatures = VkPhysicalDeviceRayTracingPipelineFeaturesKHR.calloc(stack)
                    .sType(KHRRayTracingPipeline.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_FEATURES_KHR)
                    .rayTracingPipeline(true);

                // pNext chain: features2 → bufferDeviceAddr → as → rt
                features2.pNext(bufferDeviceAddrFeatures.address());
                bufferDeviceAddrFeatures.pNext(asFeatures.address());
                asFeatures.pNext(rtFeatures.address());
            }

            VkDeviceCreateInfo deviceInfo = VkDeviceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                .pQueueCreateInfos(queueInfos)
                .ppEnabledExtensionNames(ppExtNames)
                .pNext(rtSupported ? features2.address() : 0);

            PointerBuffer pDevice = stack.mallocPointer(1);
            int result = vkCreateDevice(physicalDevice, deviceInfo, null, pDevice);
            if (result != VK_SUCCESS) {
                LOG.error("vkCreateDevice failed: {}", vkResultString(result));
                return false;
            }
            device = new VkDevice(pDevice.get(0), physicalDevice, deviceInfo);

            // 取得 Queue handles
            PointerBuffer pQueue = stack.mallocPointer(1);
            vkGetDeviceQueue(device, graphicsQueueFamily, 0, pQueue);
            graphicsQueue = new VkQueue(pQueue.get(0), device);

            if (separateCompute) {
                vkGetDeviceQueue(device, computeQueueFamily, 0, pQueue);
                computeQueue = new VkQueue(pQueue.get(0), device);
            } else {
                computeQueue = graphicsQueue;
            }

            return true;
        }
    }

    /** 4. 建立 Command Pool */
    private boolean createCommandPool() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                .queueFamilyIndex(graphicsQueueFamily)
                .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);

            LongBuffer pPool = stack.mallocLong(1);
            int result = vkCreateCommandPool(device, poolInfo, null, pPool);
            if (result != VK_SUCCESS) {
                LOG.error("vkCreateCommandPool failed: {}", vkResultString(result));
                return false;
            }
            commandPool = pPool.get(0);
            return true;
        }
    }

    // ═══ 輔助方法 ═══

    /** 列舉 Queue Family，找 graphics + compute */
    private void findQueueFamilies(MemoryStack stack) {
        IntBuffer pCount = stack.mallocInt(1);
        vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, pCount, null);
        int count = pCount.get(0);

        VkQueueFamilyProperties.Buffer families =
            VkQueueFamilyProperties.calloc(count, stack);
        vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, pCount, families);

        for (int i = 0; i < count; i++) {
            int flags = families.get(i).queueFlags();
            if (graphicsQueueFamily < 0 && (flags & VK_QUEUE_GRAPHICS_BIT) != 0) {
                graphicsQueueFamily = i;
            }
            if (computeQueueFamily < 0 && (flags & VK_QUEUE_COMPUTE_BIT) != 0) {
                computeQueueFamily = i;
            }
        }

        // 若找不到獨立 compute，fallback 到 graphics
        if (computeQueueFamily < 0) computeQueueFamily = graphicsQueueFamily;
    }

    /** 檢查裝置是否支援所有必要 RT extensions */
    private boolean checkDeviceRTSupport(VkPhysicalDevice dev, MemoryStack stack) {
        IntBuffer pCount = stack.mallocInt(1);
        vkEnumerateDeviceExtensionProperties(dev, (String) null, pCount, null);
        int count = pCount.get(0);

        VkExtensionProperties.Buffer exts = VkExtensionProperties.calloc(count, stack);
        vkEnumerateDeviceExtensionProperties(dev, (String) null, pCount, exts);

        int found = 0;
        for (int i = 0; i < count; i++) {
            String name = exts.get(i).extensionNameString();
            for (String req : REQUIRED_DEVICE_EXTENSIONS) {
                if (req.equals(name)) { found++; break; }
            }
        }
        return found == REQUIRED_DEVICE_EXTENSIONS.length;
    }

    /** 取得裝置 VRAM（heap 0 通常是 device-local heap） */
    private long getDeviceVRAM(VkPhysicalDevice dev, MemoryStack stack) {
        VkPhysicalDeviceMemoryProperties memProps =
            VkPhysicalDeviceMemoryProperties.calloc(stack);
        vkGetPhysicalDeviceMemoryProperties(dev, memProps);

        long vram = 0;
        for (int i = 0; i < memProps.memoryHeapCount(); i++) {
            VkMemoryHeap heap = memProps.memoryHeaps(i);
            if ((heap.flags() & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0) {
                vram = Math.max(vram, heap.size());
            }
        }
        return vram;
    }

    /** 設定 debug messenger（validation layer 用） */
    private void setupDebugMessenger(MemoryStack stack) {
        VkDebugUtilsMessengerCreateInfoEXT messengerInfo =
            VkDebugUtilsMessengerCreateInfoEXT.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT)
                .messageSeverity(
                    VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT |
                    VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
                .messageType(
                    VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT |
                    VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT)
                .pfnUserCallback((severity, type, pData, pUserData) -> {
                    VkDebugUtilsMessengerCallbackDataEXT data =
                        VkDebugUtilsMessengerCallbackDataEXT.create(pData);
                    if ((severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0) {
                        LOG.error("[Vulkan] {}", data.pMessageString());
                    } else {
                        LOG.warn("[Vulkan] {}", data.pMessageString());
                    }
                    return VK_FALSE;
                });

        LongBuffer pMessenger = stack.mallocLong(1);
        if (vkCreateDebugUtilsMessengerEXT(vkInstance, messengerInfo, null, pMessenger)
                == VK_SUCCESS) {
            debugMessenger = pMessenger.get(0);
        }
    }

    /** VkResult → 可讀字串 */
    private static String vkResultString(int result) {
        return switch (result) {
            case VK_SUCCESS                 -> "VK_SUCCESS";
            case VK_NOT_READY              -> "VK_NOT_READY";
            case VK_ERROR_OUT_OF_HOST_MEMORY   -> "VK_ERROR_OUT_OF_HOST_MEMORY";
            case VK_ERROR_OUT_OF_DEVICE_MEMORY -> "VK_ERROR_OUT_OF_DEVICE_MEMORY";
            case VK_ERROR_INITIALIZATION_FAILED-> "VK_ERROR_INITIALIZATION_FAILED";
            case VK_ERROR_DEVICE_LOST      -> "VK_ERROR_DEVICE_LOST";
            case VK_ERROR_EXTENSION_NOT_PRESENT-> "VK_ERROR_EXTENSION_NOT_PRESENT";
            case VK_ERROR_FEATURE_NOT_PRESENT  -> "VK_ERROR_FEATURE_NOT_PRESENT";
            case VK_ERROR_INCOMPATIBLE_DRIVER  -> "VK_ERROR_INCOMPATIBLE_DRIVER";
            default -> "VK_RESULT(" + result + ")";
        };
    }

    // ═══ 銷毀 ═══

    /**
     * 銷毀所有 Vulkan 資源（必須在遊戲關閉前呼叫）。
     */
    public void cleanup() {
        if (device != null) {
            vkDeviceWaitIdle(device);
            if (commandPool != VK_NULL_HANDLE) {
                vkDestroyCommandPool(device, commandPool, null);
                commandPool = VK_NULL_HANDLE;
            }
            vkDestroyDevice(device, null);
            device = null;
        }
        if (debugMessenger != VK_NULL_HANDLE && vkInstance != null) {
            vkDestroyDebugUtilsMessengerEXT(vkInstance, debugMessenger, null);
            debugMessenger = VK_NULL_HANDLE;
        }
        if (vkInstance != null) {
            vkDestroyInstance(vkInstance, null);
            vkInstance = null;
        }
        initialized = false;
        rtSupported = false;
        instance    = null;
        LOG.info("VkContext cleanup complete");
    }

    // ═══ Accessors ═══

    public VkInstance       getVkInstance()       { return vkInstance; }
    public VkPhysicalDevice getPhysicalDevice()   { return physicalDevice; }
    public VkDevice         getDevice()           { return device; }
    public VkQueue          getGraphicsQueue()    { return graphicsQueue; }
    public VkQueue          getComputeQueue()     { return computeQueue; }
    public long             getCommandPool()      { return commandPool; }
    public int              getGraphicsQueueFamily(){ return graphicsQueueFamily; }
    public int              getComputeQueueFamily() { return computeQueueFamily; }
    public boolean          isInitialized()       { return initialized; }
    public boolean          isRTSupported()       { return rtSupported; }
    public String           getGpuName()          { return gpuName; }
    public long             getVramBytes()        { return vramBytes; }
}
