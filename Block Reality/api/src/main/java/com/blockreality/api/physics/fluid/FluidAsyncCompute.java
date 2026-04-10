package com.blockreality.api.physics.fluid;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.blockreality.api.physics.pfsf.VulkanComputeContext;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;

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
        public org.lwjgl.vulkan.VkCommandBuffer commandBuffer; // VkCommandBuffer
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
     * VkFence、VkCommandBuffer 和預分配讀回暫存。
     */
    public static void init() {
        if (initialized) return;

        for (int i = 0; i < MAX_FRAMES_IN_FLIGHT; i++) {
            FluidComputeFrame frame = new FluidComputeFrame();
            // 實際 Vulkan 初始化：
            // frame.fence = VulkanComputeContext.createFence(true); // signaled
            // frame.commandBuffer = VulkanComputeContext.allocateCommandBuffer();
            // frame.readbackStagingBuf = VulkanComputeContext.allocateStagingBuffer(READBACK_STAGING_SIZE);
            frame.readbackStagingBuf = new long[2]; // placeholder
            frame.readbackStagingSize = READBACK_STAGING_SIZE;
            frame.reset();
            availableFrames.push(frame);
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
     * @return 可用幀，或 null
     */
    public static FluidComputeFrame acquireFrame() {
        if (!initialized) return null;

        pollCompleted();

        if (availableFrames.isEmpty()) return null;

        FluidComputeFrame frame = availableFrames.pop();
        frame.reset();
        // 實際 Vulkan：vkResetFences, vkResetCommandBuffer, vkBeginCommandBuffer
        return frame;
    }

    /**
     * 非阻塞提交計算幀到 GPU。
     *
     * @param frame 已記錄指令的計算幀
     * @param onComplete GPU 完成時的回呼
     */
    public static void submitAsync(FluidComputeFrame frame, Consumer<Void> onComplete) {
        frame.onComplete = onComplete;
        frame.submitted = true;
        // 實際 Vulkan：vkEndCommandBuffer, vkQueueSubmit(fence=frame.fence)
        submittedFrames.push(frame);
    }

    /**
     * 輪詢已完成的 GPU 幀（非阻塞）。
     *
     * <p>使用 {@code vkGetFenceStatus()} 檢查，不等待。
     * 完成的幀執行回呼後回收到可用池。
     */
    public static void pollCompleted() {
        int size = submittedFrames.size();
        for (int i = 0; i < size; i++) {
            FluidComputeFrame frame = submittedFrames.poll();
            if (frame == null) break;

            // 實際 Vulkan：int status = vkGetFenceStatus(device, frame.fence)
            boolean gpuDone = true; // placeholder: 假設完成

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
     * 關閉流體計算管線，等待所有飛行幀完成。
     */
    public static void shutdown() {
        if (!initialized) return;

        // 實際 Vulkan：vkWaitForFences 等待所有已提交的幀
        pollCompleted();

        // 釋放所有幀的暫存和 fence
        for (FluidComputeFrame frame : availableFrames) {
            if (frame.readbackStagingBuf != null) {
                // Buffer[0] is vkBuffer, Buffer[1] is VmaAllocation placeholder layout
                VulkanComputeContext.freeBuffer(frame.readbackStagingBuf[0], frame.readbackStagingBuf[1]);
                frame.readbackStagingBuf = null;
            }
            if (frame.fence != 0) {
                org.lwjgl.vulkan.VK10.vkDestroyFence(
                    VulkanComputeContext.getVkDeviceObj(), frame.fence, null);
                frame.fence = 0;
            }
        }
        for (FluidComputeFrame frame : submittedFrames) {
            if (frame.readbackStagingBuf != null) {
                VulkanComputeContext.freeBuffer(frame.readbackStagingBuf[0], frame.readbackStagingBuf[1]);
                frame.readbackStagingBuf = null;
            }
            if (frame.fence != 0) {
                org.lwjgl.vulkan.VK10.vkDestroyFence(
                    VulkanComputeContext.getVkDeviceObj(), frame.fence, null);
                frame.fence = 0;
            }
        }
        availableFrames.clear();
        submittedFrames.clear();

        initialized = false;
        LOGGER.info("[BR-FluidAsync] Shutdown complete");
    }

    public static boolean isInitialized() { return initialized; }
}
