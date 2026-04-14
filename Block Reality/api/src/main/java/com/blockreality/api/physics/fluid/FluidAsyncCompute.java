package com.blockreality.api.physics.fluid;

import com.blockreality.api.physics.pfsf.VulkanComputeContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * 流體三重緩衝非同步 GPU 計算管線。
 *
 * <p>遵循 {@code PFSFAsyncCompute} 的三重緩衝模式：
 * <pre>
 * Tick N:   [CPU 準備資料]  [GPU 計算 N-1]  [CPU 讀取 N-2 結果]
 * </pre>
 *
 * <p>與 PFSF 共享 {@code VulkanComputeContext}（device、allocator），
 * 但使用獨立的 fence 池和 command buffer，避免互相干擾。
 *
 * <p>延遲 2 tick（100ms）取得結果，但 CPU 和 GPU 永不互等。
 */
public class FluidAsyncCompute {

    private static final Logger LOGGER = LogManager.getLogger("BR-FluidAsync");

    /** 飛行中的最大幀數（三重緩衝） */
    private static final int MAX_FRAMES_IN_FLIGHT = 3;

    /** 讀回暫存區大小（邊界壓力，最大 128³ × 4 bytes） */
    private static final int READBACK_STAGING_SIZE = 128 * 128 * 128 * 4;

    // ─── 幀池 ───
    private static final Deque<FluidComputeFrame> availableFrames = new ArrayDeque<>(MAX_FRAMES_IN_FLIGHT);
    private static final Deque<FluidComputeFrame> submittedFrames = new ArrayDeque<>(MAX_FRAMES_IN_FLIGHT);

    private static boolean initialized = false;

    /**
     * 計算幀 — 代表一次 GPU 提交。
     */
    public static class FluidComputeFrame {
        public long fence;                     // VkFence
        public VkCommandBuffer commandBuffer;  // VkCommandBuffer
        public boolean submitted;
        public int regionId;
        public Consumer<Void> onComplete;

        // 預分配持久暫存（零運行時 VMA 呼叫）
        public long[] readbackStagingBuf;
        public long readbackStagingSize;
        public int readbackN;

        public void reset() {
            submitted = false;
            regionId = -1;
            onComplete = null;
            readbackN = 0;
        }
    }

    /**
     * 初始化流體非同步計算管線。
     *
     * <p>建立 3 個 {@link FluidComputeFrame}，各含獨立的
     * VkFence（已 signaled）、VkCommandBuffer 和預分配讀回暫存。
     */
    public static void init() {
        if (initialized) return;

        VkDevice device = VulkanComputeContext.getVkDeviceObj();
        if (device == null) {
            LOGGER.error("[BR-FluidAsync] VulkanComputeContext not ready, cannot init");
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
                .flags(VK_FENCE_CREATE_SIGNALED_BIT);  // signaled = acquireFrame can reset immediately

            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                .commandPool(VulkanComputeContext.getCommandPool())
                .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                .commandBufferCount(1);

            for (int i = 0; i < MAX_FRAMES_IN_FLIGHT; i++) {
                FluidComputeFrame frame = new FluidComputeFrame();

                // Fence
                java.nio.LongBuffer pFence = stack.mallocLong(1);
                int r = vkCreateFence(device, fenceInfo, null, pFence);
                if (r != VK_SUCCESS) {
                    LOGGER.error("[BR-FluidAsync] vkCreateFence failed: {}", r);
                    return;
                }
                frame.fence = pFence.get(0);

                // Command buffer
                org.lwjgl.PointerBuffer pCmdBuf = stack.mallocPointer(1);
                r = vkAllocateCommandBuffers(device, allocInfo, pCmdBuf);
                if (r != VK_SUCCESS) {
                    LOGGER.error("[BR-FluidAsync] vkAllocateCommandBuffers failed: {}", r);
                    return;
                }
                frame.commandBuffer = new VkCommandBuffer(pCmdBuf.get(0), device);

                // Readback staging (VMA)
                frame.readbackStagingBuf = VulkanComputeContext.allocateStagingBuffer(READBACK_STAGING_SIZE);
                if (frame.readbackStagingBuf == null) {
                    LOGGER.error("[BR-FluidAsync] readback staging allocation failed for frame {}, skipping", i);
                    continue;  // 不把此 frame 加入 availableFrames
                }
                frame.readbackStagingSize = READBACK_STAGING_SIZE;
                frame.reset();
                availableFrames.push(frame);
            }
        }

        initialized = true;
        LOGGER.info("[BR-FluidAsync] Initialized {} compute frames", MAX_FRAMES_IN_FLIGHT);
    }

    /**
     * 非阻塞取得可用計算幀。
     *
     * <p>先輪詢已完成的幀，再從池中取出。
     * 若所有 3 幀都在飛行中，返回 null（本 tick 跳過）。
     *
     * @return 可用幀（已 reset fence + begun command buffer），或 null
     */
    public static FluidComputeFrame acquireFrame() {
        if (!initialized) return null;

        pollCompleted();

        if (availableFrames.isEmpty()) return null;

        FluidComputeFrame frame = availableFrames.pop();
        frame.reset();

        VkDevice device = VulkanComputeContext.getVkDeviceObj();
        if (device == null || frame.commandBuffer == null) return null;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkResetFences(device, stack.longs(frame.fence));
            vkResetCommandBuffer(frame.commandBuffer, 0);

            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            vkBeginCommandBuffer(frame.commandBuffer, beginInfo);
        }

        return frame;
    }

    /**
     * 非阻塞提交計算幀到 GPU。
     *
     * <p>結束 command buffer 錄製並提交到 compute queue，由 fence 追蹤完成。
     *
     * @param frame 已記錄指令的計算幀
     * @param onComplete GPU 完成時的回呼
     */
    public static void submitAsync(FluidComputeFrame frame, Consumer<Void> onComplete) {
        frame.onComplete = onComplete;
        frame.submitted = true;

        if (frame.commandBuffer == null) {
            submittedFrames.push(frame);
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkEndCommandBuffer(frame.commandBuffer);

            VkSubmitInfo.Buffer submitInfo = VkSubmitInfo.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                .pCommandBuffers(stack.pointers(frame.commandBuffer));
            vkQueueSubmit(VulkanComputeContext.getComputeQueue(), submitInfo, frame.fence);
        }

        submittedFrames.push(frame);
    }

    /**
     * 輪詢已完成的 GPU 幀（非阻塞）。
     *
     * <p>使用 {@code vkGetFenceStatus()} 檢查，不等待。
     * 完成的幀執行回呼後回收到可用池。
     */
    public static void pollCompleted() {
        VkDevice device = VulkanComputeContext.getVkDeviceObj();
        int size = submittedFrames.size();
        for (int i = 0; i < size; i++) {
            FluidComputeFrame frame = submittedFrames.poll();
            if (frame == null) break;

            boolean gpuDone = (device == null || frame.fence == 0)
                || (vkGetFenceStatus(device, frame.fence) == VK_SUCCESS);

            if (gpuDone) {
                if (frame.onComplete != null) {
                    try {
                        frame.onComplete.accept(null);
                    } catch (Exception e) {
                        LOGGER.error("[BR-FluidAsync] Error in completion callback for region {}",
                            frame.regionId, e);
                    }
                }
                frame.reset();
                availableFrames.push(frame);
            } else {
                submittedFrames.push(frame); // 還沒完成，放回去
            }
        }
    }

    /**
     * 關閉流體計算管線，等待所有飛行幀完成再釋放資源。
     *
     * <p>此方法刻意使用 {@link VulkanComputeContext#waitFence(long)}（已 @Deprecated）
     * 而非 {@code waitFenceAndFree(fence, cmdBuf)}，原因：
     * <ul>
     *   <li>shutdown 路徑中，command buffer 由 {@link #releaseFrame(FluidComputeFrame)} 統一釋放</li>
     *   <li>若改用 {@code waitFenceAndFree} 會導致 cmdBuf 被 double-free</li>
     *   <li>此處只需「等待 fence + 銷毀 fence」語意，不需要釋放 cmdBuf</li>
     * </ul>
     */
    @SuppressWarnings("deprecation")
    public static void shutdown() {
        if (!initialized) return;

        // Block until all submitted GPU frames finish; only fence is destroyed here.
        // Command buffers are freed later by releaseFrame() — do NOT use waitFenceAndFree().
        for (FluidComputeFrame frame : submittedFrames) {
            if (frame.fence != 0) {
                VulkanComputeContext.waitFence(frame.fence);
            }
        }
        pollCompleted();

        // 釋放所有幀的暫存和 fence
        for (FluidComputeFrame frame : availableFrames) {
            releaseFrame(frame);
        }
        for (FluidComputeFrame frame : submittedFrames) {
            releaseFrame(frame);
        }
        availableFrames.clear();
        submittedFrames.clear();

        initialized = false;
        LOGGER.info("[BR-FluidAsync] Shutdown complete");
    }

    private static void releaseFrame(FluidComputeFrame frame) {
        VkDevice device = VulkanComputeContext.getVkDeviceObj();
        if (frame.readbackStagingBuf != null && frame.readbackStagingBuf.length >= 2) {
            VulkanComputeContext.freeBuffer(frame.readbackStagingBuf[0], frame.readbackStagingBuf[1]);
            frame.readbackStagingBuf = null;
        }
        if (frame.fence != 0 && device != null) {
            vkDestroyFence(device, frame.fence, null);
            frame.fence = 0;
        }
        // commandBuffer is freed implicitly when the command pool is destroyed
        frame.commandBuffer = null;
    }

    public static boolean isInitialized() { return initialized; }
}
