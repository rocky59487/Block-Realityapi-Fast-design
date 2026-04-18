/**
 * @file vulkan_device.h
 * @brief Single-instance Vulkan device — takes over from Java
 *        VulkanComputeContext (which today may create a standalone
 *        compute device if BRVulkanDevice reflection fails; v0.3c
 *        routes both through this shared core to eliminate the
 *        reflection fragility and double-instance pitfall).
 */
#ifndef BR_CORE_VULKAN_DEVICE_H
#define BR_CORE_VULKAN_DEVICE_H

#include <vulkan/vulkan.h>

#include <cstdint>
#include <string>
#include <vector>

namespace br_core {

struct VulkanDeviceCapabilities {
    bool supports_rt_pipeline            = false;  ///< VK_KHR_ray_tracing_pipeline
    bool supports_acceleration_structure = false;  ///< VK_KHR_acceleration_structure
    bool supports_ray_query              = false;  ///< VK_KHR_ray_query
    bool supports_external_memory        = false;  ///< VK_KHR_external_memory_fd / win32
    bool supports_timeline_semaphore     = false;  ///< Vulkan 1.2 core
    bool supports_buffer_device_address  = false;  ///< Vulkan 1.2 core
    bool supports_synchronization2       = false;  ///< Vulkan 1.3 core
    bool supports_mesh_shader            = false;  ///< VK_EXT_mesh_shader (Ada+)
    bool supports_cluster_as             = false;  ///< VK_NV_cluster_acceleration_structure (Blackwell)
};

/**
 * Owns VkInstance / VkPhysicalDevice / VkDevice and the three queues
 * (graphics, async-compute-0, async-compute-1 where supported).
 */
class VulkanDevice {
public:
    VulkanDevice() = default;
    ~VulkanDevice();

    VulkanDevice(const VulkanDevice&)            = delete;
    VulkanDevice& operator=(const VulkanDevice&) = delete;

    /**
     * Initialise Vulkan. Optional vkGetInstanceProcAddr override lets
     * the Java side pass the GLFW-resolved function pointer, preserving
     * the Forge classloader workaround today baked into
     * BRVulkanDevice.java:91-100.
     *
     * Passing nullptr for vkGipa falls back to the system loader.
     * Returns true on success.
     */
    bool init(PFN_vkGetInstanceProcAddr vkGipa = nullptr);

    /** Tear the device down. Safe to call multiple times. */
    void shutdown();

    /** True once init() has succeeded and shutdown() has not yet been called. */
    bool is_ready() const { return device_ != VK_NULL_HANDLE; }

    // ── Handles (const after init) ─────────────────────────────────
    VkInstance       instance()        const { return instance_; }
    VkPhysicalDevice physical_device() const { return physical_; }
    VkDevice         device()          const { return device_; }

    VkQueue       graphics_queue()     const { return queue_graphics_; }
    std::uint32_t graphics_family()    const { return family_graphics_; }
    VkQueue       compute_queue(int n) const;     ///< n ∈ {0, 1}; falls back to graphics queue if absent
    std::uint32_t compute_family()     const { return family_compute_; }

    const VulkanDeviceCapabilities& caps() const { return caps_; }

    /** Human-readable GPU name — forwarded verbatim to Java diagnostics. */
    const std::string& device_name() const { return device_name_; }

private:
    bool create_instance(PFN_vkGetInstanceProcAddr vkGipa);
    bool pick_physical();
    bool create_device();
    void install_debug_messenger(PFN_vkGetInstanceProcAddr vkGipa);

    VkInstance               instance_         = VK_NULL_HANDLE;
    VkPhysicalDevice         physical_         = VK_NULL_HANDLE;
    VkDevice                 device_           = VK_NULL_HANDLE;
    VkQueue                  queue_graphics_   = VK_NULL_HANDLE;
    VkQueue                  queue_compute0_   = VK_NULL_HANDLE;
    VkQueue                  queue_compute1_   = VK_NULL_HANDLE;
    std::uint32_t            family_graphics_  = UINT32_MAX;
    std::uint32_t            family_compute_   = UINT32_MAX;
    VulkanDeviceCapabilities caps_{};
    std::string              device_name_;
    bool                     debug_layers_enabled_ = false;
    VkDebugUtilsMessengerEXT debug_messenger_  = VK_NULL_HANDLE;
    PFN_vkGetInstanceProcAddr debug_gipa_      = nullptr;
};

} // namespace br_core

#endif // BR_CORE_VULKAN_DEVICE_H
