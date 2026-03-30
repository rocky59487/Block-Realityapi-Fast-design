package com.blockreality.api.client.rendering.vulkan;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.*;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * VMA（Vulkan Memory Allocator）封裝器（Phase 2-A）。
 *
 * 使用 LWJGL lwjgl-vma binding 管理所有 GPU 記憶體分配。
 * 所有 Vulkan buffer 和 image 的記憶體分配統一透過此類別，
 * 避免直接呼叫 vkAllocateMemory / vkFreeMemory。
 *
 * 分配類型：
 *   - Device-local（GPU 專用，最快，不可 CPU 讀寫）
 *     → 用於 BLAS/TLAS、頂點/索引 buffer、storage image
 *   - Host-visible（CPU 可寫入，GPU 可讀取）
 *     → 用於 staging buffer（資料上傳中轉）
 *   - Host-coherent（自動 flush/invalidate）
 *     → 用於 uniform buffer（每幀更新）
 *
 * @see VkContext
 * @see VkAccelStructBuilder
 */
@OnlyIn(Dist.CLIENT)
public final class VkMemoryAllocator {

    private static final Logger LOG = LoggerFactory.getLogger("BR-VkVMA");

    // ─── VMA allocator handle ───
    private long allocator = VK_NULL_HANDLE;

    private final VkContext context;

    // ─── 分配追蹤（用於 cleanup 驗證） ───
    /** handle → allocation handle（用於 vmaDestroyBuffer） */
    private final ConcurrentHashMap<Long, Long> bufferAllocations  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> imageAllocations   = new ConcurrentHashMap<>();

    private final AtomicLong totalAllocatedBytes = new AtomicLong(0);

    public VkMemoryAllocator(VkContext context) {
        this.context = context;
    }

    // ═══ 初始化 ═══

    /**
     * 建立 VMA allocator。
     *
     * 必須在 VkContext 初始化後呼叫。
     *
     * @return true 若成功
     */
    public boolean init() {
        if (allocator != VK_NULL_HANDLE) {
            LOG.warn("VkMemoryAllocator already initialized");
            return true;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VmaVulkanFunctions vkFunctions = VmaVulkanFunctions.calloc(stack)
                .set(context.getVkInstance(), context.getDevice());

            VmaAllocatorCreateInfo createInfo = VmaAllocatorCreateInfo.calloc(stack)
                .physicalDevice(context.getPhysicalDevice())
                .device(context.getDevice())
                .instance(context.getVkInstance())
                .pVulkanFunctions(vkFunctions)
                // 啟用 buffer device address（RT 必要）
                .flags(VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT);

            LongBuffer pAllocator = stack.mallocLong(1);
            int result = vmaCreateAllocator(createInfo, pAllocator);
            if (result != VK_SUCCESS) {
                LOG.error("vmaCreateAllocator failed: {}", result);
                return false;
            }
            allocator = pAllocator.get(0);
            LOG.info("VkMemoryAllocator initialized");
            return true;
        }
    }

    // ═══ Buffer 分配 ═══

    /**
     * 分配 device-local buffer（GPU 專用，最快速）。
     *
     * 適用於：BLAS/TLAS、頂點/索引 buffer、storage buffer
     *
     * @param size  buffer 大小（bytes）
     * @param usage VkBufferUsageFlags（需包含目標用途）
     * @return [VkBuffer handle, VmaAllocation handle]，失敗返回 null
     */
    public long[] allocateDeviceBuffer(long size, int usage) {
        return allocateBuffer(size, usage,
            VMA_MEMORY_USAGE_AUTO,
            VMA_ALLOCATION_CREATE_DEDICATED_MEMORY_BIT);
    }

    /**
     * 分配 staging buffer（CPU 可寫入，用於資料上傳到 GPU）。
     *
     * @param size buffer 大小（bytes）
     * @return [VkBuffer handle, VmaAllocation handle]，失敗返回 null
     */
    public long[] allocateStagingBuffer(long size) {
        return allocateBuffer(size,
            VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
            VMA_MEMORY_USAGE_AUTO,
            VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT |
            VMA_ALLOCATION_CREATE_MAPPED_BIT);
    }

    /**
     * 分配 uniform buffer（CPU 每幀寫入，小型資料）。
     *
     * @param size buffer 大小（bytes）
     * @return [VkBuffer handle, VmaAllocation handle]，失敗返回 null
     */
    public long[] allocateUniformBuffer(long size) {
        return allocateBuffer(size,
            VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
            VMA_MEMORY_USAGE_AUTO,
            VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT |
            VMA_ALLOCATION_CREATE_MAPPED_BIT);
    }

    /** 通用 buffer 分配 */
    private long[] allocateBuffer(long size, int usage, int vmaUsage, int vmaFlags) {
        if (allocator == VK_NULL_HANDLE) return null;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bufInfo = VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .size(size)
                .usage(usage)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            VmaAllocationCreateInfo allocInfo = VmaAllocationCreateInfo.calloc(stack)
                .usage(vmaUsage)
                .flags(vmaFlags);

            LongBuffer pBuffer     = stack.mallocLong(1);
            LongBuffer pAllocation = stack.mallocLong(1);

            int result = vmaCreateBuffer(allocator, bufInfo, allocInfo, pBuffer, pAllocation, null);
            if (result != VK_SUCCESS) {
                LOG.error("vmaCreateBuffer failed: {}", result);
                return null;
            }

            long buf   = pBuffer.get(0);
            long alloc = pAllocation.get(0);

            bufferAllocations.put(buf, alloc);
            totalAllocatedBytes.addAndGet(size);

            return new long[]{buf, alloc};
        }
    }

    // ═══ Image 分配 ═══

    /**
     * 分配 storage image（RT 輸出目標）。
     *
     * @param width   圖像寬度
     * @param height  圖像高度
     * @param format  VkFormat（通常 VK_FORMAT_R16G16B16A16_SFLOAT）
     * @param usage   VkImageUsageFlags
     * @return [VkImage handle, VmaAllocation handle]，失敗返回 null
     */
    public long[] allocateImage(int width, int height, int format, int usage) {
        if (allocator == VK_NULL_HANDLE) return null;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo imgInfo = VkImageCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                .imageType(VK_IMAGE_TYPE_2D)
                .format(format)
                .mipLevels(1)
                .arrayLayers(1)
                .samples(VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK_IMAGE_TILING_OPTIMAL)
                .usage(usage)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);

            imgInfo.extent()
                .width(width)
                .height(height)
                .depth(1);

            VmaAllocationCreateInfo allocInfo = VmaAllocationCreateInfo.calloc(stack)
                .usage(VMA_MEMORY_USAGE_AUTO)
                .flags(VMA_ALLOCATION_CREATE_DEDICATED_MEMORY_BIT);

            LongBuffer pImage      = stack.mallocLong(1);
            LongBuffer pAllocation = stack.mallocLong(1);

            int result = vmaCreateImage(allocator, imgInfo, allocInfo, pImage, pAllocation, null);
            if (result != VK_SUCCESS) {
                LOG.error("vmaCreateImage failed: {}", result);
                return null;
            }

            long img   = pImage.get(0);
            long alloc = pAllocation.get(0);
            imageAllocations.put(img, alloc);

            return new long[]{img, alloc};
        }
    }

    // ═══ 寫入 / 映射 ═══

    /**
     * 將資料寫入 host-visible buffer（適用於 staging 和 uniform buffer）。
     *
     * @param allocation VmaAllocation handle
     * @param data       要寫入的 byte 資料
     */
    public void writeToBuffer(long allocation, byte[] data) {
        if (allocator == VK_NULL_HANDLE) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.PointerBuffer ppData = stack.mallocPointer(1);
            vmaMapMemory(allocator, allocation, ppData);
            long ptr = ppData.get(0);
            org.lwjgl.system.MemoryUtil.memCopy(
                org.lwjgl.system.MemoryUtil.memAddress(
                    org.lwjgl.system.MemoryUtil.memByteBuffer(ptr, data.length)),
                org.lwjgl.system.MemoryUtil.memAddress(
                    org.lwjgl.system.MemoryUtil.memByteBuffer(
                        org.lwjgl.system.MemoryUtil.memAddress(data), data.length)),
                data.length);
            vmaUnmapMemory(allocator, allocation);
        }
    }

    /**
     * 將 float 資料寫入 host-visible buffer（uniform 常用）。
     */
    public void writeFloats(long allocation, float[] data) {
        if (allocator == VK_NULL_HANDLE) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.PointerBuffer ppData = stack.mallocPointer(1);
            vmaMapMemory(allocator, allocation, ppData);
            org.lwjgl.system.MemoryUtil.memFloatBuffer(ppData.get(0), data.length)
                .put(data)
                .flip();
            vmaUnmapMemory(allocator, allocation);
        }
    }

    // ═══ 釋放 ═══

    /**
     * 釋放 buffer 和其記憶體。
     */
    public void freeBuffer(long bufferHandle) {
        Long alloc = bufferAllocations.remove(bufferHandle);
        if (alloc == null) return;
        vmaDestroyBuffer(allocator, bufferHandle, alloc);
    }

    /**
     * 釋放 image 和其記憶體。
     */
    public void freeImage(long imageHandle) {
        Long alloc = imageAllocations.remove(imageHandle);
        if (alloc == null) return;
        vmaDestroyImage(allocator, imageHandle, alloc);
    }

    /**
     * 銷毀 VMA allocator 及所有未釋放資源（cleanup 用）。
     */
    public void cleanup() {
        if (allocator == VK_NULL_HANDLE) return;

        int leaked = bufferAllocations.size() + imageAllocations.size();
        if (leaked > 0) {
            LOG.warn("VkMemoryAllocator: {} leaked allocation(s) at cleanup", leaked);
        }

        // 強制釋放所有剩餘分配
        bufferAllocations.forEach((buf, alloc) ->
            vmaDestroyBuffer(allocator, buf, alloc));
        imageAllocations.forEach((img, alloc) ->
            vmaDestroyImage(allocator, img, alloc));

        bufferAllocations.clear();
        imageAllocations.clear();

        vmaDestroyAllocator(allocator);
        allocator = VK_NULL_HANDLE;

        LOG.info("VkMemoryAllocator cleanup — total allocated: {} MB",
            totalAllocatedBytes.get() / (1024 * 1024));
    }

    // ═══ 統計 ═══

    public boolean isInitialized()        { return allocator != VK_NULL_HANDLE; }
    public long getTotalAllocatedBytes()  { return totalAllocatedBytes.get(); }
    public int  getBufferAllocationCount(){ return bufferAllocations.size(); }
    public int  getImageAllocationCount() { return imageAllocations.size(); }
}
