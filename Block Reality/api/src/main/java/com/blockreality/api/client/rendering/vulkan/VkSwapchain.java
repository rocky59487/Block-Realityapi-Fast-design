package com.blockreality.api.client.rendering.vulkan;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.opengl.EXTMemoryObject;
import org.lwjgl.opengl.EXTMemoryObjectWin32;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Platform;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.opengl.EXTMemoryObject.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.GL_RGBA16F;
import static org.lwjgl.vulkan.KHRExternalMemoryFd.*;
import static org.lwjgl.vulkan.KHRExternalMemoryWin32.*;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;

/**
 * Vulkan Render Target + GL-Vulkan 互通管理（Phase 2-E）。
 *
 * Block Reality 混合渲染架構：
 *   Minecraft GL pipeline ─────── 保持不動
 *   Vulkan RT pipeline    ─── 渲染到 VkImage (RGBA16F)
 *                             │
 *                    GL_EXT_memory_object
 *                             │
 *                   GL texture（RGBA16F）
 *                             │
 *                    GL composite pass（50% blend）
 *
 * 平台差異：
 *   Windows → VK_KHR_external_memory_win32 + GL_EXT_memory_object_win32
 *   Linux   → VK_KHR_external_memory_fd    + GL_EXT_memory_object
 *
 * @see VkContext
 * @see VkMemoryAllocator
 * @see VkRTPipeline
 */
@OnlyIn(Dist.CLIENT)
public class VkSwapchain {

    private static final Logger LOG = LoggerFactory.getLogger("BR-VkSwapchain");

    private final VkContext         context;
    private final VkMemoryAllocator memoryAllocator;

    // ─── Vulkan Render Target ───
    private long rtOutputImage      = VK_NULL_HANDLE; // VkImage
    private long rtOutputImageView  = VK_NULL_HANDLE; // VkImageView
    private long rtOutputMemory     = VK_NULL_HANDLE; // VkDeviceMemory（手動管理，非 VMA）

    // ─── GL Interop ───
    private int  glTextureId        = 0;
    private int  glMemoryObject     = 0; // GL memory object（GL_EXT_memory_object）

    // ─── Sync ───
    private long renderFinishedFence     = VK_NULL_HANDLE;
    private long imageAvailableSemaphore = VK_NULL_HANDLE;

    // ─── Layout tracking ───
    private int currentImageLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    private int width, height;
    private boolean ready = false;

    private static final int RT_FORMAT = VK_FORMAT_R16G16B16A16_SFLOAT;

    public VkSwapchain(VkContext context, VkMemoryAllocator memoryAllocator) {
        this.context         = context;
        this.memoryAllocator = memoryAllocator;
    }

    // ═══ 建立 / 重建 Render Target ═══

    /**
     * 建立或重建 RT render target（視窗大小改變時呼叫）。
     *
     * 必須在 GL 執行緒呼叫（GL interop 操作需要 GL context）。
     *
     * @param width  輸出寬度（pixels）
     * @param height 輸出高度（pixels）
     * @return true 若成功
     */
    public boolean createRenderTarget(int width, int height) {
        if (ready) destroyRenderTarget();

        this.width  = width;
        this.height = height;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (!createVkImage(stack))       return false;
            if (!createVkImageView(stack))   return false;
            if (!importToGL(stack))          return false;
            if (!createSyncPrimitives(stack)) return false;

            // 轉換初始 image layout → GENERAL（storage image 需要）
            transitionImageLayout(stack,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);

            ready = true;
            LOG.info("VkSwapchain render target created: {}×{}", width, height);
            return true;

        } catch (Exception e) {
            LOG.error("VkSwapchain.createRenderTarget failed: {}", e.getMessage(), e);
            destroyRenderTarget();
            return false;
        }
    }

    // ─── Step 1: VkImage（exportable memory）───

    private boolean createVkImage(MemoryStack stack) {
        VkDevice device = context.getDevice();

        // 需要 external memory export 的 image
        VkExternalMemoryImageCreateInfo externalInfo = VkExternalMemoryImageCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO)
            .handleTypes(platformExternalMemoryHandleType());

        VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
            .pNext(externalInfo.address())
            .imageType(VK_IMAGE_TYPE_2D)
            .format(RT_FORMAT)
            .mipLevels(1)
            .arrayLayers(1)
            .samples(VK_SAMPLE_COUNT_1_BIT)
            .tiling(VK_IMAGE_TILING_OPTIMAL)
            .usage(VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
                   | VK_IMAGE_USAGE_SAMPLED_BIT)
            .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
            .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
        imageInfo.extent().width(width).height(height).depth(1);

        LongBuffer pImage = stack.mallocLong(1);
        int result = vkCreateImage(device, imageInfo, null, pImage);
        if (result != VK_SUCCESS) {
            LOG.error("vkCreateImage (RT output) failed: {}", result);
            return false;
        }
        rtOutputImage = pImage.get(0);

        // 分配 device-local memory（帶 export flag）
        return allocateExportableMemory(stack);
    }

    private boolean allocateExportableMemory(MemoryStack stack) {
        VkDevice device = context.getDevice();

        VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
        vkGetImageMemoryRequirements(device, rtOutputImage, memReqs);

        // 找 device-local memory type
        int memTypeIndex = findMemoryType(stack,
            memReqs.memoryTypeBits(),
            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        if (memTypeIndex < 0) {
            LOG.error("No suitable device-local memory type found for RT image");
            return false;
        }

        // Export memory allocate info（Windows/Linux 分支）
        VkExportMemoryAllocateInfo exportInfo = VkExportMemoryAllocateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO)
            .handleTypes(platformExternalMemoryHandleType());

        VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
            .pNext(exportInfo.address())
            .allocationSize(memReqs.size())
            .memoryTypeIndex(memTypeIndex);

        LongBuffer pMemory = stack.mallocLong(1);
        int result = vkAllocateMemory(device, allocInfo, null, pMemory);
        if (result != VK_SUCCESS) {
            LOG.error("vkAllocateMemory (exportable RT) failed: {}", result);
            return false;
        }
        rtOutputMemory = pMemory.get(0);

        vkBindImageMemory(device, rtOutputImage, rtOutputMemory, 0);
        return true;
    }

    // ─── Step 2: VkImageView ───

    private boolean createVkImageView(MemoryStack stack) {
        VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
            .image(rtOutputImage)
            .viewType(VK_IMAGE_VIEW_TYPE_2D)
            .format(RT_FORMAT);
        viewInfo.subresourceRange()
            .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
            .baseMipLevel(0)
            .levelCount(1)
            .baseArrayLayer(0)
            .layerCount(1);

        LongBuffer pView = stack.mallocLong(1);
        int result = vkCreateImageView(context.getDevice(), viewInfo, null, pView);
        if (result != VK_SUCCESS) {
            LOG.error("vkCreateImageView failed: {}", result);
            return false;
        }
        rtOutputImageView = pView.get(0);
        return true;
    }

    // ─── Step 3: GL interop ───

    private boolean importToGL(MemoryStack stack) {
        // 建立 GL memory object
        IntBuffer pGLMem = stack.mallocInt(1);
        EXTMemoryObject.glCreateMemoryObjectsEXT(pGLMem);
        glMemoryObject = pGLMem.get(0);
        if (glMemoryObject == 0) {
            LOG.error("glCreateMemoryObjectsEXT returned 0");
            return false;
        }

        // 匯出 Vulkan 記憶體 handle 並匯入 GL
        boolean success = Platform.get() == Platform.WINDOWS
            ? importWin32Handle(stack)
            : importFdHandle(stack);
        if (!success) return false;

        // 建立 GL texture（storage format = RGBA16F）
        IntBuffer pTex = stack.mallocInt(1);
        glGenTextures(pTex);
        glTextureId = pTex.get(0);

        // 以 external memory 作為存儲綁定 texture
        long texSize = (long) width * height * 8; // RGBA16F = 8 bytes per pixel
        EXTMemoryObject.glTextureStorageMem2DEXT(
            glTextureId, 1,
            GL_RGBA16F,
            width, height,
            glMemoryObject, 0L);

        LOG.debug("GL interop: texture={}, memObject={}", glTextureId, glMemoryObject);
        return true;
    }

    private boolean importWin32Handle(MemoryStack stack) {
        VkMemoryGetWin32HandleInfoKHR handleInfo = VkMemoryGetWin32HandleInfoKHR.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_MEMORY_GET_WIN32_HANDLE_INFO_KHR)
            .memory(rtOutputMemory)
            .handleType(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT_KHR);

        org.lwjgl.PointerBuffer pHandle = stack.mallocPointer(1);
        int result = vkGetMemoryWin32HandleKHR(context.getDevice(), handleInfo, pHandle);
        if (result != VK_SUCCESS) {
            LOG.error("vkGetMemoryWin32HandleKHR failed: {}", result);
            return false;
        }
        long win32Handle = pHandle.get(0);

        EXTMemoryObjectWin32.glImportMemoryWin32HandleEXT(
            glMemoryObject,
            (long) width * height * 8,
            EXTMemoryObjectWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT,
            win32Handle);
        return true;
    }

    private boolean importFdHandle(MemoryStack stack) {
        VkMemoryGetFdInfoKHR fdInfo = VkMemoryGetFdInfoKHR.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_MEMORY_GET_FD_INFO_KHR)
            .memory(rtOutputMemory)
            .handleType(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT_KHR);

        IntBuffer pFd = stack.mallocInt(1);
        int result = vkGetMemoryFdKHR(context.getDevice(), fdInfo, pFd);
        if (result != VK_SUCCESS) {
            LOG.error("vkGetMemoryFdKHR failed: {}", result);
            return false;
        }
        int fd = pFd.get(0);

        EXTMemoryObject.glImportMemoryFdEXT(
            glMemoryObject,
            (long) width * height * 8,
            GL_HANDLE_TYPE_OPAQUE_FD_EXT,
            fd);
        return true;
    }

    // ─── Step 4: Sync primitives ───

    private boolean createSyncPrimitives(MemoryStack stack) {
        VkDevice device = context.getDevice();

        VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
            .flags(VK_FENCE_CREATE_SIGNALED_BIT); // 初始為 signaled（第一幀無需等待）

        LongBuffer pFence = stack.mallocLong(1);
        if (vkCreateFence(device, fenceInfo, null, pFence) != VK_SUCCESS) return false;
        renderFinishedFence = pFence.get(0);

        VkSemaphoreCreateInfo semInfo = VkSemaphoreCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
        LongBuffer pSem = stack.mallocLong(1);
        if (vkCreateSemaphore(device, semInfo, null, pSem) != VK_SUCCESS) return false;
        imageAvailableSemaphore = pSem.get(0);

        return true;
    }

    // ─── Image Layout Transition ───

    /**
     * 轉換 RT image layout（透過 one-time command buffer）。
     */
    private void transitionImageLayout(MemoryStack stack, int oldLayout, int newLayout) {
        VkDevice device = context.getDevice();
        long pool = context.getCommandPool();

        VkCommandBufferAllocateInfo ai = VkCommandBufferAllocateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
            .commandPool(pool)
            .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
            .commandBufferCount(1);

        org.lwjgl.PointerBuffer pCmd = stack.mallocPointer(1);
        vkAllocateCommandBuffers(device, ai, pCmd);
        VkCommandBuffer cmd = new VkCommandBuffer(pCmd.get(0), device);

        vkBeginCommandBuffer(cmd, VkCommandBufferBeginInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
            .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT));

        // Barrier
        int srcAccess = 0, dstAccess = 0;
        int srcStage  = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
        int dstStage  = VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT;

        if (newLayout == VK_IMAGE_LAYOUT_GENERAL) {
            dstAccess = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
            dstStage  = VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR;
        } else if (newLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
            dstAccess = VK_ACCESS_SHADER_READ_BIT;
            dstStage  = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
        }

        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
        barrier.get(0)
            .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
            .oldLayout(oldLayout)
            .newLayout(newLayout)
            .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
            .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
            .image(rtOutputImage)
            .srcAccessMask(srcAccess)
            .dstAccessMask(dstAccess);
        barrier.get(0).subresourceRange()
            .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
            .baseMipLevel(0).levelCount(1)
            .baseArrayLayer(0).layerCount(1);

        vkCmdPipelineBarrier(cmd,
            srcStage, dstStage, 0,
            null, null, barrier);

        vkEndCommandBuffer(cmd);

        LongBuffer pFence = stack.mallocLong(1);
        vkCreateFence(device, VkFenceCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO), null, pFence);
        vkQueueSubmit(context.getGraphicsQueue(),
            VkSubmitInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                .pCommandBuffers(pCmd),
            pFence.get(0));
        vkWaitForFences(device, pFence, true, Long.MAX_VALUE);
        vkDestroyFence(device, pFence.get(0), null);
        vkFreeCommandBuffers(device, pool, pCmd);

        currentImageLayout = newLayout;
    }

    // ═══ 每幀操作 ═══

    /**
     * 等待上一幀 RT render 完成並重置 fence（在發起新 RT 前呼叫）。
     *
     * 等待 + 重置：這是 renderRT 的預備步驟。
     */
    public void waitForRenderFinished() {
        if (renderFinishedFence == VK_NULL_HANDLE) return;
        vkWaitForFences(context.getDevice(),
            new long[]{ renderFinishedFence }, true, Long.MAX_VALUE);
        vkResetFences(context.getDevice(), new long[]{ renderFinishedFence });
    }

    /**
     * 等待本幀 RT 完成（GL composite pass 前呼叫，Phase 3 GL-Vulkan 同步）。
     *
     * 只等待，不重置 fence；下幀 {@link #waitForRenderFinished()} 負責重置。
     * 超時設為 2 秒（正常 RT 遠不會超過此時間）。
     *
     * Phase 4 升級：改用 VK_KHR_external_semaphore + GL_EXT_semaphore
     * 實作 zero-copy 非阻塞 GL-Vulkan 同步。
     */
    public void waitForCurrentFrameRT() {
        if (renderFinishedFence == VK_NULL_HANDLE) return;
        // 2_000_000_000 ns = 2 seconds（避免 driver 無回應時永久 hang）
        vkWaitForFences(context.getDevice(),
            new long[]{ renderFinishedFence }, true, 2_000_000_000L);
        // 刻意不重置 fence：waitForRenderFinished() 在下幀重置
    }

    /**
     * 確保 image 處於 GENERAL layout（RT pipeline 需要）。
     */
    public void ensureGeneralLayout() {
        if (currentImageLayout != VK_IMAGE_LAYOUT_GENERAL) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                transitionImageLayout(stack, currentImageLayout, VK_IMAGE_LAYOUT_GENERAL);
            }
        }
    }

    // ═══ 銷毀 ═══

    private void destroyRenderTarget() {
        VkDevice device = context.getDevice();
        if (device == null) return;

        vkDeviceWaitIdle(device);

        if (renderFinishedFence != VK_NULL_HANDLE) {
            vkDestroyFence(device, renderFinishedFence, null);
            renderFinishedFence = VK_NULL_HANDLE;
        }
        if (imageAvailableSemaphore != VK_NULL_HANDLE) {
            vkDestroySemaphore(device, imageAvailableSemaphore, null);
            imageAvailableSemaphore = VK_NULL_HANDLE;
        }

        if (glTextureId != 0) {
            glDeleteTextures(glTextureId);
            glTextureId = 0;
        }
        if (glMemoryObject != 0) {
            EXTMemoryObject.glDeleteMemoryObjectsEXT(glMemoryObject);
            glMemoryObject = 0;
        }
        if (rtOutputImageView != VK_NULL_HANDLE) {
            vkDestroyImageView(device, rtOutputImageView, null);
            rtOutputImageView = VK_NULL_HANDLE;
        }
        if (rtOutputImage != VK_NULL_HANDLE) {
            vkDestroyImage(device, rtOutputImage, null);
            rtOutputImage = VK_NULL_HANDLE;
        }
        if (rtOutputMemory != VK_NULL_HANDLE) {
            vkFreeMemory(device, rtOutputMemory, null);
            rtOutputMemory = VK_NULL_HANDLE;
        }

        currentImageLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        ready = false;
    }

    /**
     * 完整清理（模組卸載時呼叫）。
     */
    public void cleanup() {
        destroyRenderTarget();
        LOG.info("VkSwapchain cleanup complete");
    }

    // ═══ 工具 ═══

    /** 根據平台選擇 external memory handle 類型 */
    private static int platformExternalMemoryHandleType() {
        return Platform.get() == Platform.WINDOWS
            ? VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT_KHR
            : VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT_KHR;
    }

    /** 找到符合 memoryTypeBits 且含有 requiredFlags 的 memory type index */
    private int findMemoryType(MemoryStack stack, int memoryTypeBits, int requiredFlags) {
        VkPhysicalDeviceMemoryProperties memProps =
            VkPhysicalDeviceMemoryProperties.calloc(stack);
        vkGetPhysicalDeviceMemoryProperties(context.getPhysicalDevice(), memProps);

        for (int i = 0; i < memProps.memoryTypeCount(); i++) {
            if ((memoryTypeBits & (1 << i)) != 0 &&
                (memProps.memoryTypes(i).propertyFlags() & requiredFlags) == requiredFlags) {
                return i;
            }
        }
        return -1;
    }

    // ═══ Accessors ═══

    public int  getGLTextureId()        { return glTextureId; }
    public long getRTOutputImage()      { return rtOutputImage; }
    public long getRTOutputImageView()  { return rtOutputImageView; }
    public long getRenderFinishedFence(){ return renderFinishedFence; }
    public int  getWidth()              { return width; }
    public int  getHeight()             { return height; }
    public boolean isReady()            { return ready; }
}
