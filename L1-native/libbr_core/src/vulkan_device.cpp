/**
 * @file vulkan_device.cpp
 * @brief Single-instance Vulkan bootstrap for v0.3c.
 */
#include "br_core/vulkan_device.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <cstdio>
#include <cstring>
#include <vector>

namespace br_core {

// Runtime strict-mode flag — set by create_instance() once it has merged the
// compile-time -DBR_VK_STRICT=ON default with the runtime BR_VK_STRICT env
// override. The debug callback consults this to decide whether to abort on
// validation-error messages. Atomic so the callback (potentially running on
// a driver thread) always sees a consistent value.
namespace {
std::atomic<bool>& strict_mode_flag() {
    static std::atomic<bool> flag{false};
    return flag;
}
} // namespace

void VulkanDevice::set_strict_mode(bool on) {
    strict_mode_flag().store(on, std::memory_order_relaxed);
}

bool VulkanDevice::is_strict_mode_active() {
    return strict_mode_flag().load(std::memory_order_relaxed);
}

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

    // Strict-mode gate: enabled by the CMake option `-DBR_VK_STRICT=ON`
    // (compile-time macro BR_VK_STRICT=1) and/or the env var `BR_VK_STRICT`.
    //   compile-default  env unset  env="0"   env="1"   result
    //        OFF           off       off      on        runtime opt-in
    //        ON            on        off      on        compile-time default, env can disable
    bool strict = false;
#if defined(BR_VK_STRICT) && (BR_VK_STRICT + 0) == 1
    strict = true;
#endif
    if (const char* env_strict = std::getenv("BR_VK_STRICT")) {
        if (env_strict[0] == '1')      strict = true;
        else if (env_strict[0] == '0') strict = false;
    }
    // Publish the resolved flag so the debug callback honours runtime env
    // overrides, not just the compile-time default.
    set_strict_mode(strict);

    std::vector<const char*> want_layers;
    std::vector<const char*> want_exts;
    if (strict && has_layer(layers, "VK_LAYER_KHRONOS_validation")) {
        want_layers.push_back("VK_LAYER_KHRONOS_validation");
        debug_layers_enabled_ = true;
        if (has_extension(exts, VK_EXT_DEBUG_UTILS_EXTENSION_NAME)) {
            want_exts.push_back(VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
        }
    }

    // v0.4 M1g — MoltenVK portability path.
    // On macOS the only Vulkan ICD is MoltenVK, which advertises itself
    // as a "portability" driver (not fully spec-conformant — e.g. it
    // wraps Metal, so BC texture compression and tessellation absent).
    // Vulkan 1.3.216 added VK_KHR_portability_enumeration: the loader
    // filters portability ICDs out of the physical-device list unless
    // the instance sets VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR
    // AND enables "VK_KHR_portability_enumeration". Without both the
    // loader returns an empty physical-device list on macOS and
    // create_device() below falls over with "no devices available".
    //
    // Using string literals + numeric flag keeps this buildable against
    // older Vulkan-Headers that don't define the macros yet.
    constexpr const char* kPortabilityEnumExt = "VK_KHR_portability_enumeration";
    constexpr VkInstanceCreateFlags kEnumeratePortabilityBit = 0x00000001;
    bool portability_enum_enabled = false;
    if (has_extension(exts, kPortabilityEnumExt)) {
        want_exts.push_back(kPortabilityEnumExt);
        portability_enum_enabled = true;
    }

    VkInstanceCreateInfo ci{};
    ci.sType                   = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    ci.pApplicationInfo        = &app;
    ci.flags                   = portability_enum_enabled ? kEnumeratePortabilityBit : 0;
    ci.enabledLayerCount       = static_cast<std::uint32_t>(want_layers.size());
    ci.ppEnabledLayerNames     = want_layers.empty() ? nullptr : want_layers.data();
    ci.enabledExtensionCount   = static_cast<std::uint32_t>(want_exts.size());
    ci.ppEnabledExtensionNames = want_exts.empty() ? nullptr : want_exts.data();

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

    if (debug_layers_enabled_ && !want_exts.empty()) {
        install_debug_messenger(vkGipa);
    }
    return true;
}

namespace {

VKAPI_ATTR VkBool32 VKAPI_CALL br_vk_debug_callback(
    VkDebugUtilsMessageSeverityFlagBitsEXT severity,
    VkDebugUtilsMessageTypeFlagsEXT /*type*/,
    const VkDebugUtilsMessengerCallbackDataEXT* data,
    void* /*user_data*/) {
    const char* tag =
        (severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)   ? "ERROR"   :
        (severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT) ? "WARNING" :
        (severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT)    ? "INFO"    :
                                                                       "VERBOSE";
    const char* msg = (data && data->pMessage) ? data->pMessage : "(null)";
    std::fprintf(stderr, "[br_core][vk-%s] %s\n", tag, msg);
    // Strict mode honours both the compile-time -DBR_VK_STRICT=ON and the
    // runtime BR_VK_STRICT env override. The resolved flag is persisted by
    // create_instance() via set_strict_mode() below, so the callback can
    // consult it without re-parsing env on every message.
    if ((severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) &&
        br_core::VulkanDevice::is_strict_mode_active()) {
        std::fflush(stderr);
        std::abort();
    }
    return VK_FALSE;
}

} // namespace

void VulkanDevice::install_debug_messenger(PFN_vkGetInstanceProcAddr vkGipa) {
    if (instance_ == VK_NULL_HANDLE) return;

    auto resolve = [&](const char* name) -> PFN_vkVoidFunction {
        if (vkGipa) return vkGipa(instance_, name);
        return vkGetInstanceProcAddr(instance_, name);
    };
    auto pCreate = reinterpret_cast<PFN_vkCreateDebugUtilsMessengerEXT>(
        resolve("vkCreateDebugUtilsMessengerEXT"));
    if (!pCreate) return;

    debug_gipa_ = vkGipa; // remember for symmetric destroy in shutdown()

    VkDebugUtilsMessengerCreateInfoEXT mci{};
    mci.sType = VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT;
    mci.messageSeverity =
        VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT |
        VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT;
    mci.messageType =
        VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT     |
        VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT  |
        VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT;
    mci.pfnUserCallback = &br_vk_debug_callback;

    if (pCreate(instance_, &mci, nullptr, &debug_messenger_) != VK_SUCCESS) {
        debug_messenger_ = VK_NULL_HANDLE;
    }
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

    // Extension feature structs. Vulkan requires these to be chained into
    // VkDeviceCreateInfo::pNext (via VkPhysicalDeviceFeatures2) with the
    // desired bits set — otherwise even with the extension enabled in
    // ppEnabledExtensionNames, the corresponding feature (rayQuery,
    // accelerationStructure, rayTracingPipeline, meshShader, …) is
    // NOT actually enabled on the VkDevice. Any later RT / mesh resource
    // creation would fail with VK_ERROR_FEATURE_NOT_PRESENT and caps()
    // would lie about what the device actually supports.
    //
    // Pattern: one struct per extension, probe against the physical
    // device via its own pNext chain, downgrade the cap if the driver
    // says the feature isn't supported, then chain the (same) struct
    // — with unwanted sub-bits zeroed — into f2.pNext for create.
    VkPhysicalDeviceAccelerationStructureFeaturesKHR as_feat{};
    as_feat.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR;
    VkPhysicalDeviceRayTracingPipelineFeaturesKHR rtp_feat{};
    rtp_feat.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_FEATURES_KHR;
    VkPhysicalDeviceRayQueryFeaturesKHR rq_feat{};
    rq_feat.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_QUERY_FEATURES_KHR;
#ifdef VK_EXT_mesh_shader
    VkPhysicalDeviceMeshShaderFeaturesEXT mesh_feat{};
    mesh_feat.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MESH_SHADER_FEATURES_EXT;
#endif
    VkPhysicalDeviceSynchronization2Features sync2_feat{};
    sync2_feat.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SYNCHRONIZATION_2_FEATURES;

    {
        VkPhysicalDeviceFeatures2 probe{};
        probe.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
        void* head = nullptr;
        auto link = [&head](auto* node) {
            node->pNext = head;
            head = node;
        };
        if (caps_.supports_acceleration_structure) link(&as_feat);
        if (caps_.supports_rt_pipeline)            link(&rtp_feat);
        if (caps_.supports_ray_query)              link(&rq_feat);
#ifdef VK_EXT_mesh_shader
        if (caps_.supports_mesh_shader)            link(&mesh_feat);
#else
        caps_.supports_mesh_shader = false;
#endif
        if (caps_.supports_synchronization2)       link(&sync2_feat);
        probe.pNext = head;
        vkGetPhysicalDeviceFeatures2(physical_, &probe);
    }
    // Downgrade caps whose critical feature bit turned out false.
    if (caps_.supports_acceleration_structure && !as_feat.accelerationStructure) {
        caps_.supports_acceleration_structure = false;
        caps_.supports_ray_query              = false;
        caps_.supports_rt_pipeline            = false;
    }
    if (caps_.supports_rt_pipeline      && !rtp_feat.rayTracingPipeline)  caps_.supports_rt_pipeline = false;
    if (caps_.supports_ray_query        && !rq_feat.rayQuery)             caps_.supports_ray_query   = false;
#ifdef VK_EXT_mesh_shader
    if (caps_.supports_mesh_shader      && !mesh_feat.meshShader)         caps_.supports_mesh_shader = false;
#endif
    if (caps_.supports_synchronization2 && !sync2_feat.synchronization2)  caps_.supports_synchronization2 = false;

    // Clear sub-bits we don't want even if the driver offered them —
    // capture-replay / indirect-build / host-commands / upload bits are
    // tunables that require matching pipeline-creation paths we don't
    // implement. Keep only the headline feature per struct.
    as_feat.accelerationStructureCaptureReplay                   = VK_FALSE;
    as_feat.accelerationStructureIndirectBuild                   = VK_FALSE;
    as_feat.accelerationStructureHostCommands                    = VK_FALSE;
    as_feat.descriptorBindingAccelerationStructureUpdateAfterBind = VK_FALSE;
    rtp_feat.rayTracingPipelineShaderGroupHandleCaptureReplay      = VK_FALSE;
    rtp_feat.rayTracingPipelineShaderGroupHandleCaptureReplayMixed = VK_FALSE;
#ifdef VK_EXT_mesh_shader
    mesh_feat.primitiveFragmentShadingRateMeshShader = VK_FALSE;
    mesh_feat.multiviewMeshShader                     = VK_FALSE;
#endif

    VkPhysicalDeviceFeatures2 f2{};
    f2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
    // Build the enable chain fresh: start with f12, then push each
    // surviving extension struct onto the front (pNext = prev head).
    void* enable_head = &f12;
    f12.pNext = nullptr;
    auto link_enable = [&enable_head](auto* node) {
        node->pNext = enable_head;
        enable_head = node;
    };
    if (caps_.supports_acceleration_structure) link_enable(&as_feat);
    if (caps_.supports_rt_pipeline)            link_enable(&rtp_feat);
    if (caps_.supports_ray_query)              link_enable(&rq_feat);
#ifdef VK_EXT_mesh_shader
    if (caps_.supports_mesh_shader)            link_enable(&mesh_feat);
#endif
    if (caps_.supports_synchronization2)       link_enable(&sync2_feat);
    f2.pNext = enable_head;

    // Enable on the logical device every extension whose capability the
    // probe step advertised — otherwise caps() would lie: downstream code
    // that branches on caps_.supports_rt_pipeline / _acceleration_structure
    // / _ray_query / _synchronization2 / _mesh_shader / _cluster_as /
    // _external_memory would hit VK_ERROR_EXTENSION_NOT_PRESENT at object
    // creation time because vkCreateDevice() was called with
    // enabledExtensionCount == 0.
    std::uint32_t avail_count = 0;
    vkEnumerateDeviceExtensionProperties(physical_, nullptr, &avail_count, nullptr);
    std::vector<VkExtensionProperties> avail(avail_count);
    if (avail_count) vkEnumerateDeviceExtensionProperties(physical_, nullptr, &avail_count, avail.data());

    std::vector<const char*> enabled_exts;
    auto enable_cap = [&](bool& cap, const char* name) {
        if (!cap) return;
        if (has_extension(avail, name)) {
            enabled_exts.push_back(name);
        } else {
            // Probe said yes but the extension isn't actually exposed on this
            // device — downgrade the cap so caps() doesn't lie to callers.
            cap = false;
        }
    };
    enable_cap(caps_.supports_rt_pipeline,            VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME);
    enable_cap(caps_.supports_acceleration_structure, VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME);
    enable_cap(caps_.supports_ray_query,              VK_KHR_RAY_QUERY_EXTENSION_NAME);
    enable_cap(caps_.supports_synchronization2,       "VK_KHR_synchronization2");
    enable_cap(caps_.supports_mesh_shader,            "VK_EXT_mesh_shader");
    enable_cap(caps_.supports_cluster_as,             "VK_NV_cluster_acceleration_structure");

    // VK_KHR_acceleration_structure and VK_KHR_ray_tracing_pipeline each
    // list VK_KHR_deferred_host_operations as a hard dependency. If we
    // request either without also enabling it, vkCreateDevice returns
    // VK_ERROR_EXTENSION_NOT_PRESENT on conformant drivers and takes the
    // whole native bootstrap offline — even on RT-capable GPUs that would
    // happily serve the compute-only PFSF path. Append it once, guarded
    // on actual exposure by the physical device.
    if ((caps_.supports_rt_pipeline || caps_.supports_acceleration_structure) &&
        has_extension(avail, "VK_KHR_deferred_host_operations")) {
        enabled_exts.push_back("VK_KHR_deferred_host_operations");
    } else if (caps_.supports_rt_pipeline || caps_.supports_acceleration_structure) {
        // Driver claims RT/AS but not the required companion — downgrade
        // both caps so callers don't try to use a half-enabled chain.
        caps_.supports_rt_pipeline            = false;
        caps_.supports_acceleration_structure = false;
        // ray_query is a consumer of AS; if AS is off, RQ is useless.
        caps_.supports_ray_query              = false;
        // Also prune already-appended names so vkCreateDevice doesn't see them.
        enabled_exts.erase(
            std::remove_if(enabled_exts.begin(), enabled_exts.end(),
                [](const char* s) {
                    return std::strcmp(s, VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME) == 0 ||
                           std::strcmp(s, VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME) == 0 ||
                           std::strcmp(s, VK_KHR_RAY_QUERY_EXTENSION_NAME) == 0;
                }),
            enabled_exts.end());
    }
    // external_memory is a family; enable whichever platform variant is present.
    if (caps_.supports_external_memory) {
        bool any = false;
        if (has_extension(avail, "VK_KHR_external_memory_fd"))    { enabled_exts.push_back("VK_KHR_external_memory_fd");    any = true; }
        if (has_extension(avail, "VK_KHR_external_memory_win32")) { enabled_exts.push_back("VK_KHR_external_memory_win32"); any = true; }
        if (!any) caps_.supports_external_memory = false;
    }

    // v0.4 M1g — VK_KHR_portability_subset is MANDATORY when the
    // physical device advertises it (spec: VUID-VkDeviceCreateInfo-
    // pProperties-04451). MoltenVK on macOS always does. Skipping it
    // here would return VK_ERROR_VALIDATION_FAILED_EXT on conformant
    // runtimes and abort create_device().
    if (has_extension(avail, "VK_KHR_portability_subset")) {
        enabled_exts.push_back("VK_KHR_portability_subset");
    }

    VkDeviceCreateInfo dci{};
    dci.sType                   = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    dci.pNext                   = &f2;
    dci.queueCreateInfoCount    = static_cast<std::uint32_t>(qcis.size());
    dci.pQueueCreateInfos       = qcis.data();
    dci.enabledExtensionCount   = static_cast<std::uint32_t>(enabled_exts.size());
    dci.ppEnabledExtensionNames = enabled_exts.empty() ? nullptr : enabled_exts.data();

    VkResult r = vkCreateDevice(physical_, &dci, nullptr, &device_);
    if (r != VK_SUCCESS) {
        std::fprintf(stderr, "[br_core] vkCreateDevice failed: %d\n", static_cast<int>(r));
        device_ = VK_NULL_HANDLE;
        // Downgrade caps that we failed to enable — keep the capability
        // vector honest so the next candidate (or the caller) doesn't
        // branch on a feature that is not actually live.
        caps_.supports_rt_pipeline            = false;
        caps_.supports_acceleration_structure = false;
        caps_.supports_ray_query              = false;
        caps_.supports_synchronization2       = false;
        caps_.supports_mesh_shader            = false;
        caps_.supports_cluster_as             = false;
        caps_.supports_external_memory        = false;
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
        if (debug_messenger_ != VK_NULL_HANDLE) {
            auto resolve = [&](const char* name) -> PFN_vkVoidFunction {
                if (debug_gipa_) return debug_gipa_(instance_, name);
                return vkGetInstanceProcAddr(instance_, name);
            };
            auto pDestroy = reinterpret_cast<PFN_vkDestroyDebugUtilsMessengerEXT>(
                resolve("vkDestroyDebugUtilsMessengerEXT"));
            if (pDestroy) pDestroy(instance_, debug_messenger_, nullptr);
            debug_messenger_ = VK_NULL_HANDLE;
            debug_gipa_ = nullptr;
        }
        vkDestroyInstance(instance_, nullptr);
        instance_ = VK_NULL_HANDLE;
    }
    queue_graphics_ = queue_compute0_ = queue_compute1_ = VK_NULL_HANDLE;
    family_graphics_ = family_compute_ = UINT32_MAX;
    physical_ = VK_NULL_HANDLE;
    caps_ = {};
    device_name_.clear();
    debug_layers_enabled_ = false;
}

} // namespace br_core
