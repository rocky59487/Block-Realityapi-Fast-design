package com.blockreality.api.physics.pfsf;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * PFSF 非同步計算管線 — 解決 CPU-GPU 同步阻塞問題。
 *
 * <h2>問題</h2>
 * 舊架構每個 dispatch 都 vkQueueWaitIdle()（同步阻塞），GPU 和 CPU 交替閒置。
 * 一個 tick 11 次阻塞，CPU 空等 GPU 約 7ms（浪費 35% tick 預算）。
 *
 * <h2>解決方案：Triple-Buffered Async Pipeline</h2>
 * <pre>
 * Tick N:   [CPU 準備資料]  [GPU 計算 N-1]  [CPU 讀取 N-2 結果]
 * Tick N+1: [CPU 準備資料]  [GPU 計算 N]    [CPU 讀取 N-1 結果]
 * ─────────────────────────────────────────────────────────────
 * 效果：CPU 永不等待 GPU，GPU 永不等待 CPU
 * 延遲：結果比同步版晚 2 tick（100ms），人眼不可見
 * </pre>
 *
 * <h2>Fence-Based 非阻塞提交</h2>
 * <pre>
 * 替代 vkQueueWaitIdle()：
 * 1. 提交 command buffer 時附帶 VkFence
 * 2. 下一 tick 開頭用 vkGetFenceStatus() 非阻塞檢查
 * 3. 已完成 → 處理結果；未完成 → 跳過，下 tick 再查
 * </pre>
 */
public final class PFSFAsyncCompute {

    private static final Logger LOGGER = LoggerFactory.getLogger("PFSF-Async");

    /** 飛行中（in-flight）的 frame 數量。3 = triple buffering。 */
    private static final int MAX_FRAMES_IN_FLIGHT = 3;

    /** 一個飛行中的計算幀 */
    public static class ComputeFrame {
        long fence;               // VkFence handle
        VkCommandBuffer cmdBuf;   // 錄製好的 command buffer
        boolean submitted;        // 是否已提交
        boolean completed;        // fence 是否已信號化
        int islandId;             // 對應的 island
        Consumer<Void> onComplete; // 完成回調

        // Readback staging buffer（每 frame 獨立，避免讀寫衝突）
        long[] readbackStagingBuf;
        int readbackN;            // 要讀回的 voxel 數

        // A3-fix: 延遲釋放的 GPU buffer（在 pollCompleted 時才 free）
        long[] deferredFreeBuffers;

        void reset() {
            submitted = false;
            completed = false;
            islandId = -1;
            onComplete = null;
            readbackN = 0;
            deferredFreeBuffers = null;
        }
    }

    // ─── Frame Pool ───
    private static final Deque<ComputeFrame> availableFrames = new ArrayDeque<>();
    private static final Deque<ComputeFrame> submittedFrames = new ArrayDeque<>();
    private static boolean initialized = false;

    private PFSFAsyncCompute() {}

    // ═══════════════════════════════════════════════════════════════
    //  Initialization
    // ═══════════════════════════════════════════════════════════════

    /**
     * 初始化 triple-buffered 非同步計算管線。
     * 預分配 3 個 ComputeFrame（fence + command buffer + staging buffer）。
     */
    public static void init() {
        if (initialized) return;

        VkDevice device = VulkanComputeContext.getVkDeviceObj();
        if (device == null) return;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int i = 0; i < MAX_FRAMES_IN_FLIGHT; i++) {
                ComputeFrame frame = new ComputeFrame();

                // Create fence (signaled initially so first wait succeeds)
                VkFenceCreateInfo fenceCI = VkFenceCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
                        .flags(VK_FENCE_CREATE_SIGNALED_BIT);

                LongBuffer pFence = stack.mallocLong(1);
                int result = vkCreateFence(device, fenceCI, null, pFence);
                if (result != VK_SUCCESS) throw new RuntimeException("vkCreateFence failed");
                frame.fence = pFence.get(0);

                // Allocate command buffer
                VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                        .commandPool(VulkanComputeContext.getCommandPool())
                        .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                        .commandBufferCount(1);

                org.lwjgl.PointerBuffer pBuf = stack.mallocPointer(1);
                vkAllocateCommandBuffers(device, allocInfo, pBuf);
                frame.cmdBuf = new VkCommandBuffer(pBuf.get(0), device);

                availableFrames.add(frame);
            }
        }

        initialized = true;
        LOGGER.info("[PFSF] Async compute initialized: {} frames in flight", MAX_FRAMES_IN_FLIGHT);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Frame Acquisition
    // ═══════════════════════════════════════════════════════════════

    /**
     * 取得一個可用的 ComputeFrame。
     * 若所有 frame 都在飛行中，回傳 null（呼叫端應跳過此 tick）。
     *
     * @return 可用的 frame，或 null
     */
    public static ComputeFrame acquireFrame() {
        if (!initialized) return null;

        // 先回收已完成的 frame
        pollCompleted();

        ComputeFrame frame = availableFrames.poll();
        if (frame == null) {
            LOGGER.debug("[PFSF] All {} frames in flight, skipping this tick", MAX_FRAMES_IN_FLIGHT);
            return null;
        }

        // Reset fence for re-use
        VkDevice device = VulkanComputeContext.getVkDeviceObj();
        vkResetFences(device, frame.fence);

        // Reset command buffer
        vkResetCommandBuffer(frame.cmdBuf, 0);

        // Begin recording
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // B6-fix: 移除 ONE_TIME_SUBMIT_BIT（此 buffer 會 reset 後重用）
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                    .flags(0);
            vkBeginCommandBuffer(frame.cmdBuf, beginInfo);
        }

        frame.reset();
        return frame;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Non-Blocking Submit
    // ═══════════════════════════════════════════════════════════════

    /**
     * 非阻塞提交 ComputeFrame 到 GPU。
     * 不呼叫 vkQueueWaitIdle()！使用 fence 追蹤完成狀態。
     *
     * @param frame     錄製好的 command buffer
     * @param onComplete 完成時的回調（在下一次 pollCompleted 時執行，主線程上）
     */
    public static void submitAsync(ComputeFrame frame, Consumer<Void> onComplete) {
        vkEndCommandBuffer(frame.cmdBuf);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .pCommandBuffers(stack.pointers(frame.cmdBuf));

            // 提交時指定 fence → GPU 完成時自動信號化
            int result = vkQueueSubmit(VulkanComputeContext.getComputeQueue(), submitInfo, frame.fence);
            if (result != VK_SUCCESS) {
                LOGGER.error("[PFSF] vkQueueSubmit failed: {}", result);
                availableFrames.add(frame);
                return;
            }
        }

        frame.submitted = true;
        frame.onComplete = onComplete;
        submittedFrames.add(frame);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Non-Blocking Poll
    // ═══════════════════════════════════════════════════════════════

    /**
     * 非阻塞檢查已提交的 frame 是否完成。
     * 已完成的 frame 執行回調並回收到 pool。
     *
     * <b>每 tick 開頭呼叫一次</b>。
     */
    public static void pollCompleted() {
        if (!initialized) return;
        VkDevice device = VulkanComputeContext.getVkDeviceObj();

        int size = submittedFrames.size();
        for (int i = 0; i < size; i++) {
            ComputeFrame frame = submittedFrames.poll();
            if (frame == null) break;

            // 非阻塞查詢 fence 狀態
            int status = vkGetFenceStatus(device, frame.fence);

            if (status == VK_SUCCESS) {
                // GPU 已完成 → 執行回調
                frame.completed = true;
                if (frame.onComplete != null) {
                    try {
                        frame.onComplete.accept(null);
                    } catch (Exception e) {
                        LOGGER.error("[PFSF] Frame completion callback error: {}", e.getMessage());
                    }
                }
                // 釋放 readback staging（如果有的話）
                if (frame.readbackStagingBuf != null) {
                    VulkanComputeContext.freeBuffer(
                            frame.readbackStagingBuf[0], frame.readbackStagingBuf[1]);
                    frame.readbackStagingBuf = null;
                }
                // A3-fix: 釋放延遲的 GPU buffer
                if (frame.deferredFreeBuffers != null) {
                    VulkanComputeContext.freeBuffer(
                            frame.deferredFreeBuffers[0], frame.deferredFreeBuffers[1]);
                    frame.deferredFreeBuffers = null;
                }
                // 回收到 pool
                availableFrames.add(frame);
            } else if (status == VK_NOT_READY) {
                // 尚未完成 → 放回佇列尾部，下 tick 再查
                submittedFrames.add(frame);
            } else {
                // 錯誤 → 丟棄此 frame
                LOGGER.error("[PFSF] Fence error status: {}", status);
                availableFrames.add(frame);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Readback Helpers
    // ═══════════════════════════════════════════════════════════════

    /**
     * 在 command buffer 中錄製 GPU→staging copy（不阻塞）。
     * 實際資料讀取在 pollCompleted() 的回調中進行。
     */
    public static long[] recordReadback(ComputeFrame frame, long srcBuffer, long size) {
        long[] staging = VulkanComputeContext.allocateStagingBuffer(size);
        frame.readbackStagingBuf = staging;

        org.lwjgl.vulkan.VkBufferCopy.Buffer region = org.lwjgl.vulkan.VkBufferCopy.calloc(1)
                .srcOffset(0).dstOffset(0).size(size);
        vkCmdCopyBuffer(frame.cmdBuf, srcBuffer, staging[0], region);
        region.free();

        // Barrier: transfer → host read
        VulkanComputeContext.computeBarrier(frame.cmdBuf);

        return staging;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Cleanup
    // ═══════════════════════════════════════════════════════════════

    /**
     * 等待所有飛行中的 frame 完成並清理。
     */
    public static void shutdown() {
        if (!initialized) return;

        VkDevice device = VulkanComputeContext.getVkDeviceObj();

        // 等待所有 submitted frame
        for (ComputeFrame frame : submittedFrames) {
            if (frame.submitted && !frame.completed) {
                vkWaitForFences(device, frame.fence, true, Long.MAX_VALUE);
            }
            if (frame.readbackStagingBuf != null) {
                VulkanComputeContext.freeBuffer(
                        frame.readbackStagingBuf[0], frame.readbackStagingBuf[1]);
            }
        }

        // Destroy fences
        for (ComputeFrame frame : availableFrames) {
            vkDestroyFence(device, frame.fence, null);
        }
        for (ComputeFrame frame : submittedFrames) {
            vkDestroyFence(device, frame.fence, null);
        }

        availableFrames.clear();
        submittedFrames.clear();
        initialized = false;

        LOGGER.info("[PFSF] Async compute shut down");
    }

    /**
     * 取得管線狀態摘要。
     */
    public static String getStats() {
        return String.format("Async: %d available, %d in-flight",
                availableFrames.size(), submittedFrames.size());
    }
}
