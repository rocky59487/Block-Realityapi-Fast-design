package com.blockreality.api.physics.pfsf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * On-demand Descriptor Pool 重置管理器。
 *
 * <h2>設計動機</h2>
 * 舊版固定每 20 tick 重置 descriptor pool，但：
 * <ul>
 *   <li>空閒時（無 dirty island）仍然浪費重置呼叫</li>
 *   <li>忙碌時 20 tick 可能不夠（pool exhaustion）</li>
 * </ul>
 *
 * <h2>新策略</h2>
 * 追蹤已分配的 descriptor set 數量，達到容量 75% 時才重置。
 * 同時保留最大間隔作為安全保障。
 */
public final class DescriptorPoolManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("PFSF-DescPool");

    /** 安全保障：即使未達容量，最長也在此間隔後重置 */
    private static final int MAX_RESET_INTERVAL = 40;

    /** 容量使用率觸發重置的閾值 */
    private static final float RESET_THRESHOLD = 0.75f;

    private final long pool;
    private final int maxSets;
    private final String ownerName;

    private int allocatedSets = 0;
    private int ticksSinceReset = 0;

    /**
     * @param pool     VkDescriptorPool handle
     * @param maxSets  pool 容量（建立時的 maxSets）
     * @param ownerName 擁有者名稱（日誌用）
     */
    public DescriptorPoolManager(long pool, int maxSets, String ownerName) {
        this.pool = pool;
        this.maxSets = maxSets;
        this.ownerName = ownerName;
    }

    /** 安全 fallback：若超出 100% 強制重置 */
    public void emergencyResetIfNeeded() {
        if (allocatedSets >= maxSets) {
            LOGGER.warn("[{}] Descriptor pool emergency reset: pool exhausted ({}/{})", ownerName, allocatedSets, maxSets);
            VulkanComputeContext.resetDescriptorPool(pool);
            allocatedSets = 0;
            ticksSinceReset = 0;
            // When pool is reset, all existing descriptor sets become invalid.
            // The engine already reallocates descriptor sets every frame in its rendering loop.
        }
    }

    /**
     * 每 tick 呼叫 — 判斷是否需要重置。
     *
     * @return true 若此 tick 執行了重置
     */
    public boolean tickResetIfNeeded() {
        ticksSinceReset++;

        boolean shouldReset = false;
        if (allocatedSets > maxSets * RESET_THRESHOLD) {
            shouldReset = true;
            LOGGER.debug("[{}] Descriptor pool reset: capacity threshold ({}/{})",
                    ownerName, allocatedSets, maxSets);
        } else if (ticksSinceReset >= MAX_RESET_INTERVAL) {
            shouldReset = true;
        }

        if (shouldReset) {
            VulkanComputeContext.resetDescriptorPool(pool);
            allocatedSets = 0;
            ticksSinceReset = 0;
            return true;
        }

        return false;
    }

    /** 通知已分配一個 descriptor set */
    public void notifyAllocated() {
        allocatedSets++;
        emergencyResetIfNeeded();
    }

    /** 通知已分配 n 個 descriptor set */
    public void notifyAllocated(int n) {
        allocatedSets += n;
        emergencyResetIfNeeded();
    }

    /** 取得底層 pool handle */
    public long getPool() { return pool; }

    /** 取得已分配的 set 數量 */
    public int getAllocatedSets() { return allocatedSets; }

    /** 取得容量使用率 */
    public float getUsageRatio() { return maxSets > 0 ? (float) allocatedSets / maxSets : 0; }

    // AtomicBoolean 保證 destroy() 在多執行緒下（Forge Tick + async Vulkan Callback）
    // 只執行一次：compareAndSet(false, true) 是原子操作，
    // 避免兩個執行緒同時看到 false 而各自執行 destroyDescriptorPool()（double-free）。
    private final java.util.concurrent.atomic.AtomicBoolean isDestroyed =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 銷毀底層 pool（冪等：多次呼叫安全，僅首次生效） */
    public void destroy() {
        if (!isDestroyed.compareAndSet(false, true)) return;
        if (pool != 0L) {
            VulkanComputeContext.destroyDescriptorPool(pool);
        }
    }
}
