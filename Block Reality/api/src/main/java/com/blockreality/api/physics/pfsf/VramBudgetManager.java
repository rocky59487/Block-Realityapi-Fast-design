package com.blockreality.api.physics.pfsf;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * VRAM 智慧預算管理器 — 自動偵測 GPU 顯存容量，按比例分配。
 *
 * <h2>設計動機</h2>
 * 舊版硬編碼 768MB 預算，在 2GB 小卡上浪費/在 24GB 大卡上太保守。
 * 現改為自動偵測 + 使用者可調比例（預設 60%）。
 *
 * <h2>分區架構</h2>
 * <pre>
 *   PFSF   66.7%  — 結構引擎（最大消費者）
 *   Fluid  20.8%  — 流體引擎
 *   Other  12.5%  — Thermal/Wind/EM
 * </pre>
 *
 * <h2>執行緒安全</h2>
 * 所有計數器使用 AtomicLong，多線程並行分配安全。
 * tryRecord/recordFree 保證不漏記（CRITICAL fix: 舊版 freeBuffer 未遞減計數器）。
 */
public final class VramBudgetManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("PFSF-VRAM");

    /** VRAM 分區 ID */
    public static final int PARTITION_PFSF = 0;
    public static final int PARTITION_FLUID = 1;
    public static final int PARTITION_OTHER = 2;

    // ─── 分區比例（固定） ───
    private static final float PFSF_RATIO  = 0.667f;
    private static final float FLUID_RATIO = 0.208f;
    private static final float OTHER_RATIO = 0.125f;

    // ─── 偵測到的硬體資訊 ───
    private long detectedVramBytes = 0;
    private int usagePercent = 60;

    // ─── 計算出的預算 ───
    private long totalBudget = 768L * 1024 * 1024;   // fallback default
    private long pfsfBudget;
    private long fluidBudget;
    private long otherBudget;

    // ─── 使用量計數器 ───
    private final AtomicLong totalAllocated = new AtomicLong(0);
    private final AtomicLong pfsfAllocated  = new AtomicLong(0);
    private final AtomicLong fluidAllocated = new AtomicLong(0);
    private final AtomicLong otherAllocated = new AtomicLong(0);

    // ─── Per-buffer size tracking（CRITICAL: freeBuffer 需要知道 size） ───
    private final ConcurrentHashMap<Long, Long> bufferSizeMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Integer> bufferPartitionMap = new ConcurrentHashMap<>();

    private boolean initialized = false;

    /**
     * 初始化 VRAM 預算 — 自動偵測 GPU 顯存。
     *
     * @param physicalDevice Vulkan 物理裝置
     * @param usagePercent   VRAM 使用比例 (30-80%)
     */
    public void init(VkPhysicalDevice physicalDevice, int usagePercent) {
        this.usagePercent = Math.max(30, Math.min(usagePercent, 80));

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceMemoryProperties memProps =
                    VkPhysicalDeviceMemoryProperties.calloc(stack);
            org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceMemoryProperties(physicalDevice, memProps);

            long maxHeap = 0;
            for (int i = 0; i < memProps.memoryHeapCount(); i++) {
                var heap = memProps.memoryHeaps(i);
                int flags = heap.flags();
                // We want DEVICE_LOCAL but NOT HOST_VISIBLE to exclude BAR memory
                if ((flags & org.lwjgl.vulkan.VK10.VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0 &&
                    (flags & org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) == 0) {
                    maxHeap = Math.max(maxHeap, heap.size());
                }
            }
            // Fallback: If no heap was found that is purely DEVICE_LOCAL without HOST_VISIBLE
            // (rare, but theoretically possible on some unified memory architectures)
            if (maxHeap == 0) {
                for (int i = 0; i < memProps.memoryHeapCount(); i++) {
                    var heap = memProps.memoryHeaps(i);
                    int flags = heap.flags();
                    if ((flags & org.lwjgl.vulkan.VK10.VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0) {
                        maxHeap = Math.max(maxHeap, heap.size());
                    }
                }
            }

            if (maxHeap > 0) {
                detectedVramBytes = maxHeap;
                totalBudget = maxHeap * this.usagePercent / 100;
            } else {
                LOGGER.warn("[VRAM] Could not detect VRAM size, using fallback 768MB");
                totalBudget = 768L * 1024 * 1024;
            }
        } catch (Throwable e) {
            LOGGER.warn("[VRAM] VRAM detection failed: {}, using fallback", e.getMessage());
            totalBudget = 768L * 1024 * 1024;
        }

        pfsfBudget  = (long) (totalBudget * PFSF_RATIO);
        fluidBudget = (long) (totalBudget * FLUID_RATIO);
        otherBudget = (long) (totalBudget * OTHER_RATIO);

        initialized = true;
        LOGGER.info("[VRAM] Budget initialized: detected={}MB, usage={}%, total={}MB (pfsf={}MB, fluid={}MB, other={}MB)",
                detectedVramBytes / (1024 * 1024), this.usagePercent,
                totalBudget / (1024 * 1024),
                pfsfBudget / (1024 * 1024), fluidBudget / (1024 * 1024), otherBudget / (1024 * 1024));
    }

    /**
     * 嘗試記錄一次 VRAM 分配。
     *
     * @param bufferHandle VMA buffer handle（用於追蹤 free 時遞減）
     * @param size         分配大小 (bytes)
     * @param partition    分區 ID
     * @return true 若預算允許，false 若超額
     */
    public boolean tryRecord(long bufferHandle, long size, int partition) {
        AtomicLong partitionCounter = getPartitionCounter(partition);
        long partitionBudget = getPartitionBudget(partition);

        // Record speculatively
        long newPartitionTotal = partitionCounter.addAndGet(size);
        long newGlobalTotal = totalAllocated.addAndGet(size);

        // Check if exceeded limits
        boolean exceeded = false;
        if (newPartitionTotal > partitionBudget) {
            LOGGER.warn("[VRAM] Partition '{}' budget exceeded: {}MB used, requesting {}KB, budget={}MB",
                    getPartitionName(partition),
                    (newPartitionTotal - size) / (1024 * 1024),
                    size / 1024,
                    partitionBudget / (1024 * 1024));
            exceeded = true;
        } else if (newGlobalTotal > totalBudget) {
            LOGGER.warn("[VRAM] Global budget exceeded: {}MB used, requesting {}KB, budget={}MB",
                    (newGlobalTotal - size) / (1024 * 1024), size / 1024,
                    totalBudget / (1024 * 1024));
            exceeded = true;
        }

        if (exceeded) {
            // Rollback
            partitionCounter.addAndGet(-size);
            totalAllocated.addAndGet(-size);
            return false;
        }
        bufferSizeMap.put(bufferHandle, size);
        bufferPartitionMap.put(bufferHandle, partition);
        return true;
    }

    /**
     * 記錄 VRAM 釋放（CRITICAL fix: 舊版完全沒有遞減計數器）。
     *
     * @param bufferHandle 要釋放的 buffer handle
     */
    public void recordFree(long bufferHandle) {
        Long size = bufferSizeMap.remove(bufferHandle);
        if (size == null) return;  // 未追蹤的 buffer（staging 等），並且避免 addAndGet(-null) NPE

        Integer partition = bufferPartitionMap.remove(bufferHandle);
        if (partition == null) partition = PARTITION_PFSF;

        totalAllocated.addAndGet(-size);
        getPartitionCounter(partition).addAndGet(-size);
    }

    // ═══ 查詢 API ═══

    /** 全域 VRAM 使用量 (bytes) */
    public long getTotalUsage() { return totalAllocated.get(); }

    /** 指定分區 VRAM 使用量 (bytes) */
    public long getPartitionUsage(int partition) {
        return getPartitionCounter(partition).get();
    }

    /** 全域 VRAM 預算 (bytes) */
    public long getTotalBudget() { return totalBudget; }

    /** 指定分區預算 (bytes) */
    public long getPartitionBudget(int partition) {
        return switch (partition) {
            case PARTITION_FLUID -> fluidBudget;
            case PARTITION_OTHER -> otherBudget;
            default -> pfsfBudget;
        };
    }

    /** VRAM 壓力值 (0.0 ~ 1.0) */
    public float getPressure() {
        if (totalBudget <= 0) return 1.0f;
        return (float) totalAllocated.get() / totalBudget;
    }

    /** 剩餘可用 VRAM (bytes) */
    public long getFreeMemory() {
        return Math.max(0, totalBudget - totalAllocated.get());
    }

    /** 偵測到的 GPU VRAM 總量 (bytes) */
    public long getDetectedVram() { return detectedVramBytes; }

    /** 是否已初始化 */
    public boolean isInitialized() { return initialized; }

    /** 配置的使用比例 (%) */
    public int getUsagePercent() { return usagePercent; }

    // ═══ 向下相容的 deprecated API ═══

    /** @deprecated 由 VramBudgetManager 自動管理，此方法僅供向下相容 */
    @Deprecated
    public int getTotalBudgetMB() { return (int) (totalBudget / (1024 * 1024)); }

    // ═══ Internal ═══

    private AtomicLong getPartitionCounter(int partition) {
        return switch (partition) {
            case PARTITION_FLUID -> fluidAllocated;
            case PARTITION_OTHER -> otherAllocated;
            default -> pfsfAllocated;
        };
    }

    String getPartitionName(int partition) {
        return switch (partition) {
            case PARTITION_FLUID -> "fluid";
            case PARTITION_OTHER -> "other";
            default -> "pfsf";
        };
    }
}
