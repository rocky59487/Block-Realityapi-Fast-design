package com.blockreality.api.physics.pfsf;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * VRAM 預算管理器 — 自動偵測 GPU 顯存、追蹤分配/釋放、提供壓力指標。
 *
 * <p>修復 CRITICAL bug：舊 VulkanComputeContext.freeBuffer() 只呼叫 VMA destroy
 * 但不遞減 totalAllocatedBytes / partitionAllocatedBytes，導致計數器
 * 單調成長直到預算耗盡。</p>
 *
 * <p>此管理器使用 allocationMap 記錄每筆分配的大小和分區，
 * free() 時查表遞減，確保計數器準確。</p>
 *
 * <p>預算自動偵測：vkGetPhysicalDeviceMemoryProperties → DEVICE_LOCAL heaps 總和
 * × vramUsagePercent%，分區比例 PFSF 66% / Fluid 22% / Other 12%。</p>
 */
public final class VramBudgetManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("PFSF-VramBudget");

    /** VRAM 分區 ID */
    public static final int PARTITION_PFSF = 0;
    public static final int PARTITION_FLUID = 1;
    public static final int PARTITION_OTHER = 2;

    /** 分區比例 */
    private static final float PFSF_RATIO = 0.66f;
    private static final float FLUID_RATIO = 0.22f;
    private static final float OTHER_RATIO = 0.12f;

    /** 每筆分配的記錄 */
    record AllocationRecord(long size, int partition) {}

    // ─── 分配追蹤 ───
    private final ConcurrentHashMap<Long, AllocationRecord> allocationMap = new ConcurrentHashMap<>();

    // ─── 計數器 ───
    private final AtomicLong totalUsed = new AtomicLong(0);
    private final AtomicLong pfsfUsed = new AtomicLong(0);
    private final AtomicLong fluidUsed = new AtomicLong(0);
    private final AtomicLong otherUsed = new AtomicLong(0);

    // ─── 預算 ───
    private long totalBudget;
    private long pfsfBudget;
    private long fluidBudget;
    private long otherBudget;

    // ─── GPU 偵測結果 ───
    private long detectedDeviceLocalBytes;
    private int vramUsagePercent = 60;

    /**
     * 自動偵測 GPU DEVICE_LOCAL 顯存大小，並計算預算。
     *
     * @param physicalDevice Vulkan physical device
     * @param usagePercent   佔用比例 (30-80%)
     */
    public void init(VkPhysicalDevice physicalDevice, int usagePercent) {
        this.vramUsagePercent = Math.max(30, Math.min(usagePercent, 80));

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceMemoryProperties memProps =
                    VkPhysicalDeviceMemoryProperties.calloc(stack);
            org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceMemoryProperties(physicalDevice, memProps);

            long deviceLocalTotal = 0;
            for (int i = 0; i < memProps.memoryHeapCount(); i++) {
                int flags = memProps.memoryHeaps(i).flags();
                if ((flags & org.lwjgl.vulkan.VK10.VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0) {
                    deviceLocalTotal += memProps.memoryHeaps(i).size();
                }
            }
            this.detectedDeviceLocalBytes = deviceLocalTotal;
        }

        // 計算預算
        totalBudget = (long) (detectedDeviceLocalBytes * vramUsagePercent / 100.0);
        pfsfBudget = (long) (totalBudget * PFSF_RATIO);
        fluidBudget = (long) (totalBudget * FLUID_RATIO);
        otherBudget = (long) (totalBudget * OTHER_RATIO);

        LOGGER.info("[VramBudget] Detected {}MB DEVICE_LOCAL, budget={}MB ({}%), " +
                        "PFSF={}MB, Fluid={}MB, Other={}MB",
                detectedDeviceLocalBytes / (1024 * 1024),
                totalBudget / (1024 * 1024),
                vramUsagePercent,
                pfsfBudget / (1024 * 1024),
                fluidBudget / (1024 * 1024),
                otherBudget / (1024 * 1024));
    }

    /**
     * 手動設定預算（用於測試或無 GPU 偵測時的 fallback）。
     */
    public void initManual(long totalBytes, float pfsfRatio, float fluidRatio, float otherRatio) {
        this.detectedDeviceLocalBytes = totalBytes;
        this.totalBudget = totalBytes;
        this.pfsfBudget = (long) (totalBytes * pfsfRatio);
        this.fluidBudget = (long) (totalBytes * fluidRatio);
        this.otherBudget = (long) (totalBytes * otherRatio);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Allocation Tracking
    // ═══════════════════════════════════════════════════════════════

    /**
     * 嘗試記錄一筆分配。在 VMA 分配成功後呼叫。
     *
     * @param bufferHandle VMA buffer handle（作為 key）
     * @param size         分配大小 (bytes)
     * @param partition    分區 ID
     * @return true 若預算允許，false 若超預算
     */
    public boolean tryRecord(long bufferHandle, long size, int partition) {
        AtomicLong partCounter = getPartitionCounter(partition);
        long partBudget = getPartitionBudget(partition);

        // 分區預算檢查
        if (partCounter.get() + size > partBudget) {
            LOGGER.warn("[VramBudget] Partition '{}' budget exceeded: {}MB used, requesting {}KB, budget={}MB",
                    getPartitionName(partition),
                    partCounter.get() / (1024 * 1024),
                    size / 1024,
                    partBudget / (1024 * 1024));
            return false;
        }

        // 全域預算檢查
        if (totalUsed.get() + size > totalBudget) {
            LOGGER.warn("[VramBudget] Global budget exceeded: {}MB used, requesting {}KB, budget={}MB",
                    totalUsed.get() / (1024 * 1024), size / 1024, totalBudget / (1024 * 1024));
            return false;
        }

        // 記錄分配
        allocationMap.put(bufferHandle, new AllocationRecord(size, partition));
        totalUsed.addAndGet(size);
        partCounter.addAndGet(size);
        return true;
    }

    /**
     * 釋放記錄。在 VMA destroy 後呼叫。
     * CRITICAL fix：從 allocationMap 查 size + partition，遞減雙計數器。
     *
     * @param bufferHandle VMA buffer handle
     */
    public void recordFree(long bufferHandle) {
        AllocationRecord record = allocationMap.remove(bufferHandle);
        if (record == null) {
            // staging buffer 等不追蹤的分配
            return;
        }
        totalUsed.addAndGet(-record.size());
        getPartitionCounter(record.partition()).addAndGet(-record.size());
    }

    // ═══════════════════════════════════════════════════════════════
    //  Pressure Metrics
    // ═══════════════════════════════════════════════════════════════

    /** 全域 VRAM 壓力 ∈ [0, 1+] */
    public float getPressure() {
        if (totalBudget <= 0) return 0;
        return (float) totalUsed.get() / totalBudget;
    }

    /** 分區 VRAM 壓力 ∈ [0, 1+] */
    public float getPartitionPressure(int partition) {
        long budget = getPartitionBudget(partition);
        if (budget <= 0) return 0;
        return (float) getPartitionCounter(partition).get() / budget;
    }

    /** 剩餘可用 VRAM (bytes) */
    public long getFreeMemory() {
        return Math.max(0, totalBudget - totalUsed.get());
    }

    /** 已使用 VRAM (bytes) */
    public long getTotalUsed() {
        return totalUsed.get();
    }

    /** 分區已使用 VRAM (bytes) */
    public long getPartitionUsage(int partition) {
        return getPartitionCounter(partition).get();
    }

    /** 全域預算 (bytes) */
    public long getTotalBudget() {
        return totalBudget;
    }

    /** 偵測到的 GPU DEVICE_LOCAL 顯存 (bytes) */
    public long getDetectedDeviceLocalBytes() {
        return detectedDeviceLocalBytes;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Internal Helpers
    // ═══════════════════════════════════════════════════════════════

    private AtomicLong getPartitionCounter(int partition) {
        return switch (partition) {
            case PARTITION_FLUID -> fluidUsed;
            case PARTITION_OTHER -> otherUsed;
            default -> pfsfUsed;
        };
    }

    private long getPartitionBudget(int partition) {
        return switch (partition) {
            case PARTITION_FLUID -> fluidBudget;
            case PARTITION_OTHER -> otherBudget;
            default -> pfsfBudget;
        };
    }

    static String getPartitionName(int partition) {
        return switch (partition) {
            case PARTITION_FLUID -> "fluid";
            case PARTITION_OTHER -> "other";
            default -> "pfsf";
        };
    }

    /** 重置所有計數器（shutdown 時呼叫） */
    public void reset() {
        allocationMap.clear();
        totalUsed.set(0);
        pfsfUsed.set(0);
        fluidUsed.set(0);
        otherUsed.set(0);
    }
}
