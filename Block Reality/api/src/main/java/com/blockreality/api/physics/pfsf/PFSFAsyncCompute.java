package com.blockreality.api.physics.pfsf;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Consumer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * PFSF 非同步計算管線 — 解決 CPU-GPU 同步阻塞問題。
 */
public final class PFSFAsyncCompute {

    private static final Logger LOGGER = LoggerFactory.getLogger("PFSF-Async");

    /** 飛行中（in-flight）的 frame 數量。3 = triple buffering。 */
    private static final int MAX_FRAMES_IN_FLIGHT = 3;

    /** 預分配的 readback staging 大小（固定，足夠容納 failure compact 結果） */
    private static final long READBACK_STAGING_SIZE = (2048 + 2) * 4L; // MAX_FAILURE_PER_TICK + header

    /** 一個飛行中的計算幀 */
    public static class ComputeFrame {
        long fence;
        VkCommandBuffer cmdBuf;
        boolean submitted;
        boolean completed;
        int islandId;
        Runnable onComplete;

        // 1a-fix: 預分配的 readback staging（persistent，不再每 frame 動態分配）
        long[] readbackStagingBuf;   // 初始化時分配，frame lifetime
        long readbackStagingSize;    // 固定大小
        int readbackN;

        // A3-fix: 延遲釋放的 GPU buffer
        long[] deferredFreeBuffers;

        // PhiMax reduction: 結果 staging buffer + 中間 partial buffer
        long[] phiMaxStagingBuf;   // staging for final max readback (4 bytes)
        long[] phiMaxPartialBuf;   // GPU-only partial results buffer (deferred free)

        void reset() {
            submitted = false;
            completed = false;
            islandId = -1;
            onComplete = null;
            readbackN = 0;
            deferredFreeBuffers = null;
            phiMaxPartialBuf = null;
            // 注意：readbackStagingBuf / phiMaxStagingBuf 不 reset（persistent）
        }
    }

    // ─── Frame Pool ───
    private static final Deque<ComputeFrame> availableFrames = new ConcurrentLinkedDeque<>();
    private static final Deque<ComputeFrame> submittedFrames = new ConcurrentLinkedDeque<>();
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

                // 1a-fix: 預分配 readback staging（persistent，零 runtime 分配）
                frame.readbackStagingBuf = VulkanComputeContext.allocateStagingBuffer(READBACK_STAGING_SIZE);
                // PhiMax: 4 bytes staging for final max float readback
                frame.phiMaxStagingBuf = VulkanComputeContext.allocateStagingBuffer(4L);
                frame.readbackStagingSize = READBACK_STAGING_SIZE;

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

        // C3-fix: 確認 fence 已 signaled 才 reset（防止 reset 飛行中的 fence）
        VkDevice device = VulkanComputeContext.getVkDeviceObj();
        int status = vkGetFenceStatus(device, frame.fence);
        if (status == VK_NOT_READY) {
            // 尚未完成，放回 pool 等下一 tick
            availableFrames.add(frame);
            return null;
        }
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
    public static void submitAsync(ComputeFrame frame, Runnable onComplete) {
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
    //  Batch Submit (Ping-Pong Parallel)
    // ═══════════════════════════════════════════════════════════════

    /**
     * 批次提交多個 ComputeFrame 到 GPU。
     *
     * <p>v3 Ping-Pong parallel：一次提交多個 island 的 command buffer，
     * 各自使用獨立 fence + 獨立 vkQueueSubmit（更好的 driver 排程）。</p>
     *
     * <p>若 batch.size()==1，直接委託 submitAsync。</p>
     *
     * @param batch     錄製好的 frame 列表（max 3）
     * @param callbacks 各 frame 完成時的回調
     */
    public static void submitBatch(List<ComputeFrame> batch, List<Runnable> callbacks) {
        if (batch.isEmpty()) return;

        if (batch.size() == 1) {
            submitAsync(batch.get(0), callbacks.get(0));
            return;
        }

        // End all command buffers first
        for (ComputeFrame frame : batch) {
            vkEndCommandBuffer(frame.cmdBuf);
        }

        // Submit each with its own fence via separate vkQueueSubmit calls
        // (not combined VkSubmitInfo — better driver scheduling for independent islands)
        for (int i = 0; i < batch.size(); i++) {
            ComputeFrame frame = batch.get(i);
            Runnable callback = callbacks.get(i);

            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                        .pCommandBuffers(stack.pointers(frame.cmdBuf));

                int result = vkQueueSubmit(VulkanComputeContext.getComputeQueue(), submitInfo, frame.fence);
                if (result != VK_SUCCESS) {
                    LOGGER.error("[PFSF] vkQueueSubmit batch[{}] failed: {}", i, result);
                    availableFrames.add(frame);
                    // Critical fix: If vkQueueSubmit fails, the frame is not submitted to the GPU.
                    // We must manually trigger its callback so the engine can release the finalBuf references.
                    if (callback != null) {
                        try {
                            callback.run();
                        } catch (Exception e) {
                            LOGGER.error("[PFSF] Failed to run failure callback: {}", e.getMessage());
                        }
                    }
                    continue;
                }
            }

            frame.submitted = true;
            frame.onComplete = callback;
            submittedFrames.add(frame);
        }

        LOGGER.debug("[PFSF] Batch submitted: {} frames", batch.size());
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
                        frame.onComplete.run();
                    } catch (Exception e) {
                        LOGGER.error("[PFSF] Frame completion callback error: {}", e.getMessage());
                    }
                }
                // 1a-fix: readback staging 是 persistent 的，不釋放
                // （在 init 時預分配，shutdown 時才釋放）

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
     * 1a-fix: 使用 frame 預分配的 staging（零 runtime VMA 呼叫）。
     */
    public static long[] recordReadback(ComputeFrame frame, long srcBuffer, long size) {
        // 使用 frame 自己的 pre-allocated staging（不再動態分配）
        long[] staging = frame.readbackStagingBuf;
        long copySize = Math.min(size, frame.readbackStagingSize);
        // #2-fix: 警告截斷（而非靜默丟棄）
        if (size > frame.readbackStagingSize) {
            LOGGER.warn("[PFSF] Readback truncated: requested {} bytes, staging only {} bytes",
                    size, frame.readbackStagingSize);
        }

        // Barrier: compute shader write → transfer read（copy 前必須）
        VulkanComputeContext.computeToTransferBarrier(frame.cmdBuf);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VkBufferCopy.Buffer region = org.lwjgl.vulkan.VkBufferCopy.calloc(1, stack)
                    .srcOffset(0).dstOffset(0).size(copySize);
            vkCmdCopyBuffer(frame.cmdBuf, srcBuffer, staging[0], region);
        }
        // 注意：host 可見性由 fence signal 保證（HOST_COHERENT staging），不需要額外 barrier

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

        // 等待所有 submitted frame，然後釋放所有 persistent staging
        for (ComputeFrame frame : submittedFrames) {
            if (frame.submitted && !frame.completed) {
                vkWaitForFences(device, frame.fence, true, Long.MAX_VALUE);
            }
            freeFrameStaging(frame);
            vkDestroyFence(device, frame.fence, null);
        }

        // 釋放 available frame 的 persistent staging 和 fence
        for (ComputeFrame frame : availableFrames) {
            vkDestroyFence(device, frame.fence, null);
            freeFrameStaging(frame);
        }

        availableFrames.clear();
        submittedFrames.clear();
        initialized = false;

        LOGGER.info("[PFSF] Async compute shut down");
    }

    /** 釋放 frame 的所有 persistent staging buffer（readback + phiMax）。 */
    private static void freeFrameStaging(ComputeFrame frame) {
        if (frame.readbackStagingBuf != null) {
            VulkanComputeContext.freeBuffer(frame.readbackStagingBuf[0], frame.readbackStagingBuf[1]);
            frame.readbackStagingBuf = null;
        }
        if (frame.phiMaxStagingBuf != null) {
            VulkanComputeContext.freeBuffer(frame.phiMaxStagingBuf[0], frame.phiMaxStagingBuf[1]);
            frame.phiMaxStagingBuf = null;
        }
    }

    /**
     * 取得管線狀態摘要。
     */
    public static String getStats() {
        return String.format("Async: %d available, %d in-flight",
                availableFrames.size(), submittedFrames.size());
    }
}
