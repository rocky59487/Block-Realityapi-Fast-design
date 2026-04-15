package com.blockreality.api.client.rendering.vulkan;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Vulkan 執行上下文 — 封裝 VkInstance / VkPhysicalDevice / VkDevice。
 *
 * 在 Minecraft 1.20.1（OpenGL 渲染器）中，Vulkan 路徑為「可選 Tier-3」：
 * 若平台不支援 Vulkan，此 context 將保持 null，
 * 所有依賴此類別的子系統（VkMemoryAllocator、VkAccelStructBuilder 等）
 * 應在 {@code isAvailable()} 為 false 時降級至 OpenGL 路徑。
 *
 * @see VkMemoryAllocator
 * @see VkAccelStructBuilder
 */
@OnlyIn(Dist.CLIENT)
public final class VkContext {

    private static final Logger LOG = LoggerFactory.getLogger("BR-VkContext");

    private VkInstance       vkInstance;
    private VkPhysicalDevice physicalDevice;
    private VkDevice         device;

    private boolean available = false;

    /**
     * 嘗試初始化 Vulkan 上下文，委託給 {@link com.blockreality.api.client.render.rt.BRVulkanDevice}。
     *
     * <p>若 BRVulkanDevice 尚未初始化，此方法會觸發其初始化流程。
     * 若平台不支援 Vulkan RT，安靜返回 false，系統降級至 OpenGL。
     *
     * @return true 若 Vulkan 可用且 RT 支援確認
     */
    public boolean init() {
        try {
            com.blockreality.api.client.render.rt.BRVulkanDevice.init();

            if (!com.blockreality.api.client.render.rt.BRVulkanDevice.isRTSupported()) {
                available = false;
                LOG.info("[VkContext] Vulkan RT 不支援，降級至 OpenGL");
                return false;
            }

            // 從 BRVulkanDevice 取得已初始化的 LWJGL 物件
            // BRVulkanDevice 的 LWJGL 物件為 package-private，透過 handle 重建包裝
            long instHandle = com.blockreality.api.client.render.rt.BRVulkanDevice.getVkInstance();
            long physHandle = com.blockreality.api.client.render.rt.BRVulkanDevice.getVkPhysicalDevice();
            long devHandle  = com.blockreality.api.client.render.rt.BRVulkanDevice.getVkDevice();

            if (instHandle == 0L || physHandle == 0L || devHandle == 0L) {
                available = false;
                LOG.warn("[VkContext] BRVulkanDevice 返回空 handle，降級至 OpenGL");
                return false;
            }

            // 直接取得 BRVulkanDevice 已建立的 LWJGL wrapper（無需重新包裝 handle）。
            // 原實作用偽造的空 VkInstanceCreateInfo.calloc()/VkDeviceCreateInfo.calloc()
            // 建立包裝物件，導致兩個問題：
            //   (1) 堆外記憶體永久洩漏（calloc 無 MemoryStack，且無 memFree）
            //   (2) 空 CreateInfo 生成錯誤的 VkCapabilities（缺少所有 KHR 擴展函數指針），
            //       後續 RT/AS 相關呼叫（如 vkCmdBuildAccelerationStructuresKHR）會 NPE
            // 修正：BRVulkanDevice 已有正確初始化的 LWJGL wrapper，直接引用即可。
            vkInstance     = com.blockreality.api.client.render.rt.BRVulkanDevice.getVkInstanceObj();
            physicalDevice = com.blockreality.api.client.render.rt.BRVulkanDevice.getVkPhysicalDeviceObj();
            device         = com.blockreality.api.client.render.rt.BRVulkanDevice.getVkDeviceObj();

            available = true;
            LOG.info("[VkContext] Vulkan RT 上下文初始化成功（委託 BRVulkanDevice）");
            return true;

        } catch (Throwable t) {
            available = false;
            LOG.warn("[VkContext] Vulkan 初始化失敗，降級至 OpenGL: {}", t.getMessage());
            return false;
        }
    }

    public void cleanup() {
        // 實際資源由 BRVulkanDevice.cleanup() 管理，此處僅清除引用
        vkInstance      = null;
        physicalDevice  = null;
        device          = null;
        available       = false;
    }

    // ─── Getters ────────────────────────────────────────────────

    /** @return true 若 Vulkan context 已成功初始化 */
    public boolean isAvailable()       { return available; }

    public VkInstance       getVkInstance()      { return vkInstance; }
    public VkPhysicalDevice getPhysicalDevice()  { return physicalDevice; }
    public VkDevice         getDevice()          { return device; }

    /** 便捷訪問：Vulkan device handle（long） */
    public long getDeviceHandle() {
        return com.blockreality.api.client.render.rt.BRVulkanDevice.getVkDevice();
    }

    /** 便捷訪問：command pool handle */
    public long getCommandPool() {
        return com.blockreality.api.client.render.rt.BRVulkanDevice.getCommandPool();
    }

    /** 便捷訪問：queue family index */
    public int getQueueFamilyIndex() {
        return com.blockreality.api.client.render.rt.BRVulkanDevice.getQueueFamilyIndex();
    }
}
