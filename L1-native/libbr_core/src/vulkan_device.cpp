/**
 * @file vulkan_device.cpp
 * @brief Single-instance Vulkan bootstrap for v0.3c.
 */
#include "br_core/vulkan_device.h"

#include <algorithm>
#include <array>
#include <cstdio>
#include <cstring>
#include <vector>

namespace br_core {

namespace {

constexpr const char* kEngineName = "BlockReality";

bool has_extension(const std::vector<VkExtensionProperties>& exts, const char* name) {
    for (const auto& e : exts) {
        if (std::strcmp(e.extensionName, name) == 0) return true;
    }
    return false;
}

bool has_layer(const std::vector<VkLayerProperties>& layers, const char* name) {
    for (const auto& l : layers) {
        if (std::strcmp(l.layerName, name) == 0) return true;
    }
    return false;
}

} // namespace

VulkanDevice::~VulkanDevice() {
    shutdown();
}

VkQueue VulkanDevice::compute_queue(int n) const {
    if (n == 0 && queue_compute0_ != VK_NULL_HANDLE) return queue_compute0_;
    if (n == 1 && queue_compute1_ != VK_NULL_HANDLE) return queue_compute1_;
    return queue_graphics_;
}

bool VulkanDevice::init(PFN_vkGetInstanceProcAddr vkGipa) {
    if (device_ != VK_NULL_HANDLE) return true;
    if (!create_instance(vkGipa)) return false;

    // Enumerate all physical devices and sort by preference rank so that
    // create_device() is retried on each in rank order until one succeeds.
    std::uint32_t pdev_count = 0;
    vkEnumeratePhysicalDevices(instance_, &pdev_count, nullptr);
    if (pdev_count == 0) { shutdown(); return false; }
    std::vector<VkPhysicalDevice> pdevs(pdev_count);
    vkEnumeratePhysicalDevices(instance_, &pdev_count, pdevs.data());

    auto gpu_rank = [](VkPhysicalDevice pd) {
        VkPhysicalDeviceProperties props{};
        vkGetPhysicalDeviceProperties(pd, &props);
        switch (props.deviceType) {
            case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU:   return 3;
            case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU: return 2;
            default:                                     return 1;
        }
    };
    std::sort(pdevs.begin(), pdevs.end(),
        [&gpu_rank](VkPhysicalDevice a, VkPhysicalDevice b) {
            return gpu_rank(a) > gpu_rank(b);
        });

    for (auto pd : pdevs) {
        physical_ = pd;
        if (!pick_physical()) {
            physical_ = VK_NULL_HANDLE;
            continue;
        }
        if (create_device()) return true;
        // Candidate failed — reset per-device state before trying the next one.
        if (device_ != VK_NULL_HANDLE) {
            vkDestroyDevice(device_, nullptr);
            device_ = VK_NULL_HANDLE;
        }
        family_graphics_ = family_compute_ = UINT32_MAX;
        caps_             = {};
        device_name_.clear();
    }
    shutdown();
    return false;
}

bool VulkanDevice::create_instance(PFN_vkGetInstanceProcAddr vkGipa) {
    VkApplicationInfo app{};
    app.sType              = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.pApplicationName   = "BlockReality";
    app.applicationVersion = VK_MAKE_VERSION(0, 3, 0);
    app.pEngineName        = kEngineName;
    app.engineVersion      = VK_MAKE_VERSION(0, 3, 0);
    app.apiVersion         = VK_API_VERSION_1_2;

    // Resolve global functions via vkGipa if provided (Forge classloader bypass)
    PFN_vkEnumerateInstanceLayerProperties pfnEnumerateLayers = vkEnumerateInstanceLayerProperties;
    PFN_vkEnumerateInstanceExtensionProperties pfnEnumerateExts = vkEnumerateInstanceExtensionProperties;
    PFN_vkCreateInstance pfnCreateInstance = vkCreateInstance;

    if (vkGipa) {
        auto get_proc = [&](const char* name) { return vkGipa(VK_NULL_HANDLE, name); };
        auto p_layers = get_proc("vkEnumerateInstanceLayerProperties");
        auto p_exts   = get_proc("vkEnumerateInstanceExtensionProperties");
        auto p_create = get_proc("vkCreateInstance");
        if (p_layers) pfnEnumerateLayers = reinterpret_cast<PFN_vkEnumerateInstanceLayerProperties>(p_layers);
        if (p_exts)   pfnEnumerateExts   = reinterpret_cast<PFN_vkEnumerateInstanceExtensionProperties>(p_exts);
        if (p_create) pfnCreateInstance  = reinterpret_cast<PFN_vkCreateInstance>(p_create);
    }

    // Query instance layers / extensions.
    std::uint32_t layer_count = 0;
    pfnEnumerateLayers(&layer_count, nullptr);
    std::vector<VkLayerProperties> layers(layer_count);
    if (layer_count) pfnEnumerateLayers(&layer_count, layers.data());

    std::uint32_t ext_count = 0;
    pfnEnumerateExts(nullptr, &ext_count, nullptr);
    std::vector<VkExtensionProperties> exts(ext_count);
    if (ext_count) pfnEnumerateExts(nullptr, &ext_count, exts.data());

    std::vector<const char*> want_layers;
    const char* env_strict = std::getenv("BR_VK_STRICT");
    if (env_strict && env_strict[0] == '1' &&
        has_layer(layers, "VK_LAYER_KHRONOS_validation")) {
        want_layers.push_back("VK_LAYER_KHRONOS_validation");
        debug_layers_enabled_ = true;
    }

    VkInstanceCreateInfo ci{};
    ci.sType                   = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    ci.pApplicationInfo        = &app;
    ci.enabledLayerCount       = static_cast<std::uint32_t>(want_layers.size());
    ci.ppEnabledLayerNames     = want_layers.empty() ? nullptr : want_layers.data();
    ci.enabledExtensionCount   = 0;
    ci.ppEnabledExtensionNames = nullptr;

    if (!pfnCreateInstance) {
        std::fprintf(stderr, "[br_core] vkCreateInstance function pointer not found\n");
        return false;
    }

    VkResult r = pfnCreateInstance(&ci, nullptr, &instance_);
    if (r != VK_SUCCESS) {
        std::fprintf(stderr, "[br_core] vkCreateInstance failed: %d\n", static_cast<int>(r));
        instance_ = VK_NULL_HANDLE;
        return false;
    }
    return true;
}

bool VulkanDevice::pick_physical() {
    // physical_ must be pre-set by the caller (init()). This function only
    // fills device_name_ and caps_ from that device — no enumeration here.
    if (physical_ == VK_NULL_HANDLE) return false;

    VkPhysicalDeviceProperties props{};
    vkGetPhysicalDeviceProperties(physical_, &props);
    device_name_ = props.deviceName;

    // Probe capabilities.
    std::uint32_t ec = 0;
    vkEnumerateDeviceExtensionProperties(physical_, nullptr, &ec, nullptr);
    std::vector<VkExtensionProperties> exts(ec);
    if (ec) vkEnumerateDeviceExtensionProperties(physical_, nullptr, &ec, exts.data());

    caps_.supports_rt_pipeline            = has_extension(exts, VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME);
    caps_.supports_acceleration_structure = has_extension(exts, VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME);
    caps_.supports_ray_query              = has_extension(exts, VK_KHR_RAY_QUERY_EXTENSION_NAME);
    caps_.supports_external_memory        = has_extension(exts, "VK_KHR_external_memory_fd") ||
                                             has_extension(exts, "VK_KHR_external_memory_win32");

    VkPhysicalDeviceVulkan12Features f12_probe{};
    f12_probe.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES;
    VkPhysicalDeviceFeatures2 f2_probe{};
    f2_probe.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
    f2_probe.pNext = &f12_probe;
    vkGetPhysicalDeviceFeatures2(physical_, &f2_probe);

    caps_.supports_timeline_semaphore     = f12_probe.timelineSemaphore == VK_TRUE;
    caps_.supports_buffer_device_address  = f12_probe.bufferDeviceAddress == VK_TRUE;
    caps_.supports_synchronization2       = has_extension(exts, "VK_KHR_synchronization2");
    caps_.supports_mesh_shader            = has_extension(exts, "VK_EXT_mesh_shader");
    caps_.supports_cluster_as             = has_extension(exts, "VK_NV_cluster_acceleration_structure");
    return true;
}

bool VulkanDevice::create_device() {
    // Enumerate queue families.
    std::uint32_t qc = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(physical_, &qc, nullptr);
    std::vector<VkQueueFamilyProperties> qprops(qc);
    vkGetPhysicalDeviceQueueFamilyProperties(physical_, &qc, qprops.data());

    // Pick a graphics+compute family (required) and an async-compute-only family (optional).
    for (std::uint32_t i = 0; i < qc; ++i) {
        if ((qprops[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) &&
            (qprops[i].queueFlags & VK_QUEUE_COMPUTE_BIT)) {
            family_graphics_ = i;
            break;
        }
    }
    for (std::uint32_t i = 0; i < qc; ++i) {
        if ((qprops[i].queueFlags & VK_QUEUE_COMPUTE_BIT) &&
            !(qprops[i].queueFlags & VK_QUEUE_GRAPHICS_BIT)) {
            family_compute_ = i;
            break;
        }
    }
    if (family_graphics_ == UINT32_MAX) return false;
    if (family_compute_ == UINT32_MAX) family_compute_ = family_graphics_;

    float prios[2] = {1.0f, 1.0f};

    std::vector<VkDeviceQueueCreateInfo> qcis;
    VkDeviceQueueCreateInfo qci_g{};
    qci_g.sType            = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    qci_g.queueFamilyIndex = family_graphics_;
    qci_g.queueCount       = 1;
    qci_g.pQueuePriorities = prios;
    qcis.push_back(qci_g);

    bool have_dedicated_compute = (family_compute_ != family_graphics_);
    if (have_dedicated_compute) {
        VkDeviceQueueCreateInfo qci_c{};
        qci_c.sType            = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
        qci_c.queueFamilyIndex = family_compute_;
        std::uint32_t avail = qprops[family_compute_].queueCount;
        qci_c.queueCount       = avail >= 2 ? 2 : 1;
        qci_c.pQueuePriorities = prios;
        qcis.push_back(qci_c);
    }

    VkPhysicalDeviceVulkan12Features f12{};
    f12.sType              = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES;
    f12.timelineSemaphore  = caps_.supports_timeline_semaphore ? VK_TRUE : VK_FALSE;
    f12.bufferDeviceAddress = caps_.supports_buffer_device_address ? VK_TRUE : VK_FALSE;

    VkPhysicalDeviceFeatures2 f2{};
    f2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
    f2.pNext = &f12;

    VkDeviceCreateInfo dci{};
    dci.sType                = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    dci.pNext                = &f2;
    dci.queueCreateInfoCount = static_cast<std::uint32_t>(qcis.size());
    dci.pQueueCreateInfos    = qcis.data();

    VkResult r = vkCreateDevice(physical_, &dci, nullptr, &device_);
    if (r != VK_SUCCESS) {
        std::fprintf(stderr, "[br_core] vkCreateDevice failed: %d\n", static_cast<int>(r));
        device_ = VK_NULL_HANDLE;
        return false;
    }

    vkGetDeviceQueue(device_, family_graphics_, 0, &queue_graphics_);
    if (have_dedicated_compute) {
        vkGetDeviceQueue(device_, family_compute_, 0, &queue_compute0_);
        if (qprops[family_compute_].queueCount >= 2) {
            vkGetDeviceQueue(device_, family_compute_, 1, &queue_compute1_);
        }
    }
    return true;
}

void VulkanDevice::shutdown() {
    if (device_ != VK_NULL_HANDLE) {
        vkDeviceWaitIdle(device_);
        vkDestroyDevice(device_, nullptr);
        device_ = VK_NULL_HANDLE;
    }
    if (instance_ != VK_NULL_HANDLE) {
        vkDestroyInstance(instance_, nullptr);
        instance_ = VK_NULL_HANDLE;
    }
    queue_graphics_ = queue_compute0_ = queue_compute1_ = VK_NULL_HANDLE;
    family_graphics_ = family_compute_ = UINT32_MAX;
    physical_ = VK_NULL_HANDLE;
    caps_ = {};
    device_name_.clear();
}

} // namespace br_core
