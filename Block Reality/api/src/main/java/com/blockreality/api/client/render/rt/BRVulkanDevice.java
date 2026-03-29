package com.blockreality.api.client.render.rt;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
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
            } catch (Exception e) {
                LOGGER.warn("Vulkan not available on this system: {}", e.getMessage());
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

        } catch (Exception e) {
            LOGGER.error("Unexpected error during Vulkan initialization, RT disabled", e);
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

            // Instance extensions
            PointerBuffer instanceExtensions = stack.mallocPointer(2);
            instanceExtensions.put(stack.UTF8(VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME));
            instanceExtensions.put(stack.UTF8("VK_KHR_surface"));
            instanceExtensions.flip();

            VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                    .pApplicationInfo(appInfo)
                    .ppEnabledExtensionNames(instanceExtensions);

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

        } catch (Exception e) {
            LOGGER.error("Exception creating Vulkan instance: {}", e.getMessage());
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

                LOGGER.debug("Found GPU: {} (type={})", props.deviceNameString(), props.deviceType());

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

            for (int i = 0; i < extensionCount.get(0); i++) {
                String name = availableExtensions.get(i).extensionNameString();
                if (name.equals(VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME)) {
                    hasRTPipeline = true;
                } else if (name.equals(VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME)) {
                    hasAccelStruct = true;
                } else if (name.equals(VK_KHR_RAY_QUERY_EXTENSION_NAME)) {
                    hasRayQuery = true;
                } else if (name.equals(VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME)) {
                    hasExternalMemory = true;
                }
            }

        } catch (Exception e) {
            LOGGER.error("Exception checking extension support: {}", e.getMessage());
            hasRTPipeline = false;
            hasAccelStruct = false;
            hasRayQuery = false;
            hasExternalMemory = false;
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
                    "VK_KHR_external_memory_fd"  // Linux interop
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

    public static int findMemoryType(int typeFilter, int properties) {
        if (!initialized) return 0;
        LOGGER.warn("findMemoryType stub called");
        return 0;
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
        if (!initialized) return;
        LOGGER.warn("destroyShaderModule stub called");
    }

    public static int getRTShaderGroupHandleSize() {
        if (!initialized) return 32;
        LOGGER.warn("getRTShaderGroupHandleSize stub called, returning 32");
        return 32;
    }

    public static long createBuffer(long device, long size, int usage) {
        if (!initialized) return 0L;
        LOGGER.warn("createBuffer stub called");
        return 0L;
    }

    public static long allocateAndBindBuffer(long device, long buffer, int memProps) {
        if (!initialized) return 0L;
        LOGGER.warn("allocateAndBindBuffer stub called");
        return 0L;
    }

    public static void deviceWaitIdle(long device) {
        if (!initialized) return;
        LOGGER.warn("deviceWaitIdle stub called");
    }

    public static void destroyDescriptorPool(long device, long pool) {
        if (!initialized) return;
        LOGGER.warn("destroyDescriptorPool stub called");
    }

    public static void destroyBuffer(long device, long buffer) {
        if (!initialized) return;
        LOGGER.warn("destroyBuffer stub called");
    }

    public static void freeMemory(long device, long memory) {
        if (!initialized) return;
        LOGGER.warn("freeMemory stub called");
    }

    public static void destroyPipeline(long device, long pipeline) {
        if (!initialized) return;
        LOGGER.warn("destroyPipeline stub called");
    }

    public static void destroyPipelineLayout(long device, long layout) {
        if (!initialized) return;
        LOGGER.warn("destroyPipelineLayout stub called");
    }

    public static void destroyDescriptorSetLayout(long device, long layout) {
        if (!initialized) return;
        LOGGER.warn("destroyDescriptorSetLayout stub called");
    }

    public static long beginSingleTimeCommands(long device) {
        if (!initialized) return 0L;
        LOGGER.warn("beginSingleTimeCommands stub called");
        return allocateCommandBuffer();
    }

    public static void cmdBindPipeline(long cmd, int bindPoint, long pipeline) {
        if (!initialized) return;
        LOGGER.warn("cmdBindPipeline stub called");
    }

    public static void cmdBindDescriptorSets(long cmd, int bindPoint, long layout, int firstSet, long sets) {
        if (!initialized) return;
        LOGGER.warn("cmdBindDescriptorSets stub called");
    }

    public static long getBufferDeviceAddress(long device, long buffer) {
        if (!initialized) return 0L;
        LOGGER.warn("getBufferDeviceAddress stub called");
        return 0L;
    }

    public static void cmdTraceRaysKHR(long cmd, long rgenAddr, long rgenStride, long rgenSize,
                                       long missAddr, long missStride, long missSize,
                                       long hitAddr, long hitStride, long hitSize,
                                       int p1, int p2, int p3, int w, int h, int d) {
        if (!initialized) return;
        LOGGER.warn("cmdTraceRaysKHR stub called");
    }

    public static void endSingleTimeCommands(long device, long cmd) {
        if (!initialized) return;
        submitAndWait(cmd);
        freeCommandBuffer(cmd);
    }

    public static void updateRTDescriptorSet(long device, long set, long tlas, long imageView) {
        if (!initialized) return;
        LOGGER.warn("updateRTDescriptorSet stub called");
    }

    public static void updateCameraUBO(long device, long set, org.joml.Matrix4f invVP,
                                       float cx, float cy, float cz, float lx, float ly, float lz) {
        if (!initialized) return;
        LOGGER.warn("updateCameraUBO stub called");
    }

    public static long createRTDescriptorSetLayout(long device) {
        if (!initialized) return 0L;
        LOGGER.warn("createRTDescriptorSetLayout stub called");
        return 0L;
    }

    public static long createPipelineLayout(long device, long dsLayout) {
        if (!initialized) return 0L;
        LOGGER.warn("createPipelineLayout stub called");
        return 0L;
    }

    public static byte[] compileGLSLtoSPIRV(String glslSource, String name) {
        LOGGER.warn("compileGLSLtoSPIRV stub called for {}", name);
        return new byte[0];
    }

    public static long createShaderModule(long device, byte[] spirv) {
        if (!initialized) return 0L;
        LOGGER.warn("createShaderModule stub called");
        return 0L;
    }

    public static long createRayTracingPipeline(long device, long layout, long rgen, long miss, long chit, int maxRecursion) {
        if (!initialized) return 0L;
        LOGGER.warn("createRayTracingPipeline stub called");
        return 0L;
    }

    public static byte[] getRayTracingShaderGroupHandles(long device, long pipeline, int groupCount, int handleSize) {
        if (!initialized) return new byte[0];
        LOGGER.warn("getRayTracingShaderGroupHandles stub called");
        return new byte[groupCount * handleSize];
    }

    public static long mapMemory(long device, long memory, int offset, long size) {
        if (!initialized) return 0L;
        LOGGER.warn("mapMemory stub called");
        return 0L;
    }

    public static void memcpy(long dst, byte[] src, int srcOffset, int length) {
        LOGGER.warn("memcpy stub called");
    }

    public static void unmapMemory(long device, long memory) {
        if (!initialized) return;
        LOGGER.warn("unmapMemory stub called");
    }

    public static long createRTDescriptorPool(long device) {
        if (!initialized) return 0L;
        LOGGER.warn("createRTDescriptorPool stub called");
        return 0L;
    }

    public static long allocateDescriptorSet(long device, long pool, long layout) {
        if (!initialized) return 0L;
        LOGGER.warn("allocateDescriptorSet stub called");
        return 0L;
    }
}
