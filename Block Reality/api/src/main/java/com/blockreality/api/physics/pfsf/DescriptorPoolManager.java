package com.blockreality.api.physics.pfsf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Descriptor Pool 管理器 — 按需重置取代固定 20 tick 間隔。
 *
 * <p>舊方案每 20 tick 強制 reset，即使 pool 使用率極低。
 * 此管理器僅在使用率 > 80% 時才觸發 reset，減少不必要的 Vulkan 呼叫。</p>
 *
 * <p>崩塌事件可強制 reset（因為大量 island 重配置會快速消耗 descriptor set）。</p>
 */
public final class DescriptorPoolManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("PFSF-DescPool");

    /** 觸發 reset 的使用率閾值 */
    private static final float RESET_THRESHOLD = 0.80f;

    private final long pool;
    private final int capacity;
    private final AtomicInteger allocatedCount = new AtomicInteger(0);

    /**
     * @param pool     Vulkan descriptor pool handle
     * @param capacity pool 的 maxSets
     */
    public DescriptorPoolManager(long pool, int capacity) {
        this.pool = pool;
        this.capacity = capacity;
    }

    /**
     * 記錄一次 descriptor set 分配。
     * 在 VulkanComputeContext.allocateDescriptorSet() 後呼叫。
     */
    public void recordAllocation() {
        allocatedCount.incrementAndGet();
    }

    /**
     * 每 tick 開頭呼叫。只在使用率 > 80% 時重置 pool。
     *
     * @return true 若觸發了 reset
     */
    public boolean tickResetIfNeeded() {
        float usage = getUsageRatio();
        if (usage > RESET_THRESHOLD) {
            doReset();
            return true;
        }
        return false;
    }

    /**
     * 強制重置（崩塌事件後呼叫）。
     */
    public void forceReset() {
        doReset();
    }

    /**
     * 當前使用率 ∈ [0, 1]。
     */
    public float getUsageRatio() {
        if (capacity <= 0) return 0;
        return (float) allocatedCount.get() / capacity;
    }

    /**
     * Pool handle（供 VulkanComputeContext 使用）。
     */
    public long getPool() {
        return pool;
    }

    /**
     * 銷毀 pool（shutdown 時呼叫）。
     */
    public void destroy() {
        VulkanComputeContext.destroyDescriptorPool(pool);
    }

    private void doReset() {
        VulkanComputeContext.resetDescriptorPool(pool);
        int prevCount = allocatedCount.getAndSet(0);
        LOGGER.debug("[DescPool] Reset: {} sets freed (was {}% full)", prevCount,
                capacity > 0 ? (prevCount * 100 / capacity) : 0);
    }
}
