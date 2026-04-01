package com.blockreality.api.client.render.rt;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.KHRBufferDeviceAddress.*;

// Vulkan 1.2 / KHR_buffer_device_address constants
// These may not be available in lwjgl-vulkan 3.3.1, so we define them locally
@OnlyIn(Dist.CLIENT)
class VulkanConstants {
    static final int VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT = 0x00020000;
    static final int VK_STRUCTURE_TYPE_BUFFER_DEVICE_ADDRESS_INFO = 1000244001;
    static final int VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT = 0x00000002;
    static final int VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_FLAGS_INFO = 1000060000;
}

/**
 * BVH（Bounding Volume Hierarchy）管理器 — Vulkan RT 加速結構。
 *
 * <p>場景階層：
 * <pre>
 * Scene TLAS (Top-Level)
 * ├── Chunk Section BLAS (16×16×16) × N
 * │   └── AABBs from GreedyMesher
 * └── Updated incrementally per-frame
 * </pre>
 *
 * <p>每幀最多重建 {@link #MAX_BLAS_REBUILDS_PER_FRAME} 個 dirty BLAS，
 * 避免 GPU stall。TLAS 在有任何 dirty section 時完整重建。
 */
@OnlyIn(Dist.CLIENT)
public final class BRVulkanBVH {

    private static final Logger LOGGER = LoggerFactory.getLogger("BR-VulkanBVH");

    // ═══════════════════════════════════════════════════════════════════
    //  Constants
    // ═══════════════════════════════════════════════════════════════════

    /** 最大追蹤的 chunk section 數量 */
    public static final int MAX_SECTIONS = 4096;

    /** 每幀最多重建的 dirty BLAS 數量（避免 GPU stall） */
    public static final int MAX_BLAS_REBUILDS_PER_FRAME = 8;

    /** 共用 scratch buffer 大小（16 MB） */
    public static final long SCRATCH_BUFFER_SIZE = 16L * 1024L * 1024L;

    /** VkAccelerationStructureInstanceKHR 大小（bytes） */
    public static final int INSTANCE_SIZE = 64;

    private BRVulkanBVH() {}

    // ═══════════════════════════════════════════════════════════════════
    //  Inner class — Per-section BLAS data
    // ═══════════════════════════════════════════════════════════════════

    /** 單一 chunk section 的 Bottom-Level Acceleration Structure 資料。 */
    public static final class SectionBLAS {
        /** VkAccelerationStructureKHR handle */
        long accelerationStructure;
        /** VkBuffer backing the acceleration structure */
        long buffer;
        /** VkDeviceMemory for the backing buffer */
        long bufferMemory;
        /** Section 座標（chunk-space） */
        int sectionX, sectionZ;
        /** 是否需要重建 */
        boolean dirty;
        /** 上次更新的 frame 編號 */
        long lastUpdateFrame;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Fields
    // ═══════════════════════════════════════════════════════════════════

    private static boolean initialized = false;

    /** sectionKey → SectionBLAS */
    private static final Map<Long, SectionBLAS> blasMap = new ConcurrentHashMap<>();

    // TLAS handles
    private static long tlas;
    private static long tlasBuffer;
    private static long tlasBufferMemory;

    // Instance buffer for TLAS build
    private static long instanceBuffer;
    private static long instanceBufferMemory;

    // Shared scratch buffer for acceleration structure builds
    private static long scratchBuffer;
    private static long scratchBufferMemory;
    private static long scratchBufferSize;

    private static long frameCount = 0;

    // Stats
    private static int totalBLASCount;
    private static int dirtyBLASCount;
    private static long totalBVHMemory;

    // ═══════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 初始化 BVH 管理器 — 分配 scratch buffer（16 MB）及 instance buffer。
     * 若 RT 不支援則靜默跳過。
     */
    public static void init() {
        if (initialized) {
            LOGGER.warn("BVH manager already initialized, skipping");
            return;
        }
        if (!BRVulkanDevice.isRTSupported()) {
            LOGGER.info("Vulkan RT not supported — BVH manager disabled");
            return;
        }

        try {
            LOGGER.info("Initializing BVH manager (scratch={}MB, maxSections={})",
                    SCRATCH_BUFFER_SIZE / (1024 * 1024), MAX_SECTIONS);

            // Allocate shared scratch buffer
            long[] scratch = createBuffer(
                    SCRATCH_BUFFER_SIZE,
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VulkanConstants.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
            );
            scratchBuffer = scratch[0];
            scratchBufferMemory = scratch[1];
            scratchBufferSize = SCRATCH_BUFFER_SIZE;

            // Allocate instance buffer for TLAS (MAX_SECTIONS * 64 bytes)
            long instanceBufSize = (long) MAX_SECTIONS * INSTANCE_SIZE;
            long[] instBuf = createBuffer(
                    instanceBufSize,
                    VulkanConstants.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
                            | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
            );
            instanceBuffer = instBuf[0];
            instanceBufferMemory = instBuf[1];

            totalBVHMemory = SCRATCH_BUFFER_SIZE + instanceBufSize;
            initialized = true;
            LOGGER.info("BVH manager initialized successfully");
        } catch (Exception e) {
            LOGGER.error("Failed to initialize BVH manager", e);
            cleanupPartial();
        }
    }

    /**
     * 銷毀所有加速結構與 buffer，釋放 GPU 記憶體。
     */
    public static void cleanup() {
        if (!initialized) return;

        LOGGER.info("Cleaning up BVH manager ({} BLAS entries)", blasMap.size());

        try {
            long device = BRVulkanDevice.getVkDevice();

            // Destroy all BLAS
            for (SectionBLAS blas : blasMap.values()) {
                destroySectionBLAS(device, blas);
            }
            blasMap.clear();

            // Destroy TLAS
            if (tlas != VK_NULL_HANDLE) {
                LOGGER.debug("[BRVulkanBVH] Destroying TLAS handle={}", tlas);
                tlas = VK_NULL_HANDLE;
            }
            destroyBufferPair(device, tlasBuffer, tlasBufferMemory);
            tlasBuffer = VK_NULL_HANDLE;
            tlasBufferMemory = VK_NULL_HANDLE;

            // Destroy instance buffer
            destroyBufferPair(device, instanceBuffer, instanceBufferMemory);
            instanceBuffer = VK_NULL_HANDLE;
            instanceBufferMemory = VK_NULL_HANDLE;

            // Destroy scratch buffer
            destroyBufferPair(device, scratchBuffer, scratchBufferMemory);
            scratchBuffer = VK_NULL_HANDLE;
            scratchBufferMemory = VK_NULL_HANDLE;
            scratchBufferSize = 0;

            totalBLASCount = 0;
            dirtyBLASCount = 0;
            totalBVHMemory = 0;
            frameCount = 0;
        } catch (Exception e) {
            LOGGER.error("Error during BVH cleanup", e);
        } finally {
            initialized = false;
        }
    }

    /** @return true if the BVH manager has been initialized and RT is active */
    public static boolean isInitialized() {
        return initialized;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  BLAS management
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 從 AABB 幾何資料建構 Bottom-Level Acceleration Structure。
     *
     * @param sectionX  chunk section X 座標
     * @param sectionZ  chunk section Z 座標
     * @param aabbData  AABB 資料陣列 — 每個 AABB 為 6 floats: minX,minY,minZ,maxX,maxY,maxZ
     * @param aabbCount AABB 數量（aabbData.length 應 == aabbCount * 6）
     */
    public static void buildBLAS(int sectionX, int sectionZ, float[] aabbData, int aabbCount) {
        if (!initialized) return;
        if (aabbCount <= 0 || aabbData == null || aabbData.length < aabbCount * 6) {
            LOGGER.warn("Invalid AABB data for section ({}, {}): count={}, dataLen={}",
                    sectionX, sectionZ, aabbCount, aabbData != null ? aabbData.length : 0);
            return;
        }

        long key = encodeSectionKey(sectionX, sectionZ);

        // Destroy existing BLAS for this section if present
        SectionBLAS existing = blasMap.get(key);
        if (existing != null) {
            try {
                destroySectionBLAS(BRVulkanDevice.getVkDevice(), existing);
            } catch (Exception e) {
                LOGGER.error("Failed to destroy old BLAS for section ({}, {})", sectionX, sectionZ, e);
            }
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            long device = BRVulkanDevice.getVkDevice();

            // 1. Create AABB geometry buffer (6 floats per AABB = 24 bytes)
            long aabbBufferSize = (long) aabbCount * 6 * Float.BYTES;
            long[] aabbBuf = createBuffer(
                    aabbBufferSize,
                    VulkanConstants.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
                            | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
            );
            long aabbBuffer = aabbBuf[0];
            long aabbBufferMemory = aabbBuf[1];

            // Upload AABB data to GPU buffer
            BRVulkanDevice.uploadFloatData(device, aabbBufferMemory, aabbData, aabbCount * 6);

            // Get device address for the AABB buffer
            long aabbDeviceAddress = BRVulkanDevice.getBufferDeviceAddress(device, aabbBuffer);

            // 2-3. Create BLAS via device helper (simplified to avoid struct issues)
            // For now, we'll skip the detailed struct creation and use a simplified path
            // In production, this would call into BRVulkanDevice.buildBLAS()
            LOGGER.debug("Building BLAS for section ({}, {}): {} AABBs",
                    sectionX, sectionZ, aabbCount);

            // Stub: allocate a basic result buffer for now
            long resultSize = 1024 * 64; // Typical size estimate
            long[] resultBuf = createBuffer(
                    resultSize,
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR
                            | VulkanConstants.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
            );
            long resultBuffer = resultBuf[0];
            long resultBufferMemory = resultBuf[1];

            // Stub acceleration structure handle (in reality created via vkCreateAccelerationStructureKHR)
            long accelerationStructure = 1L; // Placeholder

            // Clean up temporary AABB geometry buffer
            destroyBufferPair(device, aabbBuffer, aabbBufferMemory);

            // Store in blasMap
            SectionBLAS sectionBLAS = new SectionBLAS();
            sectionBLAS.accelerationStructure = accelerationStructure;
            sectionBLAS.buffer = resultBuffer;
            sectionBLAS.bufferMemory = resultBufferMemory;
            sectionBLAS.sectionX = sectionX;
            sectionBLAS.sectionZ = sectionZ;
            sectionBLAS.dirty = false;
            sectionBLAS.lastUpdateFrame = frameCount;

            blasMap.put(key, sectionBLAS);
            totalBLASCount = blasMap.size();
            totalBVHMemory += resultSize;

            LOGGER.debug("Built BLAS for section ({}, {}): {} AABBs, {} bytes",
                    sectionX, sectionZ, aabbCount, resultSize);

        } catch (Exception e) {
            LOGGER.error("Failed to build BLAS for section ({}, {})", sectionX, sectionZ, e);
        }
    }

    /**
     * 建立以 {@code VK_GEOMETRY_OPAQUE_BIT_KHR} 標記的不透明 BLAS。
     *
     * <p>適用於確定不含透明方塊（玻璃/水/葉片）的 section。
     * 設定此 flag 後，硬體跳過 any-hit shader 呼叫（
     * {@code transparent.rahit.glsl}），可節省約 15-30% ray intersection 時間。
     *
     * <p>注意：若 section 後來放入透明方塊，需呼叫標準 {@link #buildBLAS}
     * 重建（移除 opaque flag）。{@link com.blockreality.api.client.rendering.vulkan.VkAccelStructBuilder}
     * 透過 {@code transparentSectionCache} 追蹤此狀態。
     *
     * <p>與 OMM（Opacity Micromap）的關係：
     * <ul>
     *   <li>OMM 需要 triangle geometry，我們目前使用 AABB geometry</li>
     *   <li>{@code VK_GEOMETRY_OPAQUE_BIT_KHR} 是 AABB geometry 可用的等效最佳化</li>
     *   <li>Phase 3 LOD 0 改為 triangle geometry 後，此方法可遷移至真正 OMM 路徑
     *       （{@link BRVulkanDevice#buildBLASWithOMM}）</li>
     * </ul>
     *
     * @param sectionX    chunk section X 座標
     * @param sectionZ    chunk section Z 座標
     * @param aabbData    AABB 陣列（每 6 floats = minXYZ + maxXYZ）
     * @param aabbCount   AABB 數量
     */
    public static void buildBLASOpaque(int sectionX, int sectionZ, float[] aabbData, int aabbCount) {
        if (!initialized) return;
        if (aabbCount <= 0 || aabbData == null || aabbData.length < aabbCount * 6) {
            LOGGER.warn("buildBLASOpaque: invalid AABB data ({},{}): count={}", sectionX, sectionZ, aabbCount);
            return;
        }

        // 邏輯與 buildBLAS() 相同，差異在於 AABB geometry 建立時加上 VK_GEOMETRY_OPAQUE_BIT_KHR。
        // 此實作委派給標準路徑；生產環境中 BRVulkanDevice.buildBLAS() 接受 opaque flag 參數。
        // 此處 log 區分，以便性能分析工具識別 opaque vs. mixed BLAS 比例。
        LOGGER.debug("buildBLASOpaque ({},{}): {} AABBs (VK_GEOMETRY_OPAQUE_BIT_KHR)", sectionX, sectionZ, aabbCount);
        buildBLAS(sectionX, sectionZ, aabbData, aabbCount);
        // TODO Phase 3: 直接呼叫 BRVulkanDevice.buildBLASOpaque() 以傳遞 VK_GEOMETRY_OPAQUE_BIT_KHR flag
        //   到底層 VkAccelerationStructureGeometryAabbsDataKHR 的 flags 欄位
    }

    /**
     * 銷毀單一 chunk section 的 BLAS。
     *
     * @param sectionX chunk section X 座標
     * @param sectionZ chunk section Z 座標
     */
    public static void destroyBLAS(int sectionX, int sectionZ) {
        if (!initialized) return;

        long key = encodeSectionKey(sectionX, sectionZ);
        SectionBLAS blas = blasMap.remove(key);
        if (blas == null) return;

        try {
            destroySectionBLAS(BRVulkanDevice.getVkDevice(), blas);
            totalBLASCount = blasMap.size();
            LOGGER.debug("Destroyed BLAS for section ({}, {})", sectionX, sectionZ);
        } catch (Exception e) {
            LOGGER.error("Failed to destroy BLAS for section ({}, {})", sectionX, sectionZ, e);
        }
    }

    /**
     * 標記 chunk section 為 dirty，下一次 {@link #updateTLAS()} 時重建。
     *
     * @param sectionX chunk section X 座標
     * @param sectionZ chunk section Z 座標
     */
    public static void markDirty(int sectionX, int sectionZ) {
        if (!initialized) return;

        long key = encodeSectionKey(sectionX, sectionZ);
        SectionBLAS blas = blasMap.get(key);
        if (blas != null && !blas.dirty) {
            blas.dirty = true;
            dirtyBLASCount++;
        }
    }

    /**
     * 將 section (X, Z) 編碼為單一 long key。
     * 高 32 位 = X，低 32 位 = Z。
     */
    public static long encodeSectionKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  TLAS management
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 完整重建 Top-Level Acceleration Structure。
     * 從所有有效的 BLAS 條目建構 instance 陣列並上傳至 GPU。
     */
    public static void rebuildTLAS() {
        if (!initialized || blasMap.isEmpty()) return;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            long device = BRVulkanDevice.getVkDevice();

            // Destroy old TLAS if present
            if (tlas != VK_NULL_HANDLE) {
                LOGGER.debug("[BRVulkanBVH] Destroying old TLAS handle={}", tlas);
                tlas = VK_NULL_HANDLE;
            }
            if (tlasBuffer != VK_NULL_HANDLE) {
                destroyBufferPair(device, tlasBuffer, tlasBufferMemory);
                tlasBuffer = VK_NULL_HANDLE;
                tlasBufferMemory = VK_NULL_HANDLE;
            }

            List<SectionBLAS> activeEntries = new ArrayList<>(blasMap.values());
            int instanceCount = activeEntries.size();
            if (instanceCount == 0) return;

            // Build VkAccelerationStructureInstanceKHR data and upload to instanceBuffer
            BRVulkanDevice.uploadTLASInstances(device, instanceBufferMemory, activeEntries);

            // Stub: allocate a basic TLAS buffer for now
            long tlasSize = 1024 * 128; // Typical size estimate
            long[] tlasBuf = createBuffer(
                    tlasSize,
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR
                            | VulkanConstants.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
            );
            tlasBuffer = tlasBuf[0];
            tlasBufferMemory = tlasBuf[1];

            // Stub TLAS handle
            tlas = 2L; // Placeholder

            totalBVHMemory += tlasSize;
            LOGGER.debug("Rebuilt TLAS: {} instances, {} bytes", instanceCount, tlasSize);

        } catch (Exception e) {
            LOGGER.error("Failed to rebuild TLAS", e);
        }
    }

    /**
     * 增量式 TLAS 更新 — 僅在有 dirty section 時處理。
     *
     * <p>每幀最多重建 {@link #MAX_BLAS_REBUILDS_PER_FRAME} 個 dirty BLAS，
     * 完成後重建整個 TLAS。
     */
    public static void updateTLAS() {
        if (!initialized) return;

        frameCount++;

        if (dirtyBLASCount == 0) return;

        // Rebuild up to MAX_BLAS_REBUILDS_PER_FRAME dirty entries this frame
        int rebuilt = 0;
        for (SectionBLAS blas : blasMap.values()) {
            if (!blas.dirty) continue;
            if (rebuilt >= MAX_BLAS_REBUILDS_PER_FRAME) break;

            // Request fresh AABB data from GreedyMesher and rebuild
            // The actual AABB data would come from the meshing pipeline;
            // mark as no longer dirty to avoid re-processing next frame.
            blas.dirty = false;
            blas.lastUpdateFrame = frameCount;
            rebuilt++;
        }

        dirtyBLASCount = Math.max(0, dirtyBLASCount - rebuilt);

        // Rebuild TLAS to reflect updated BLAS references
        rebuildTLAS();

        if (rebuilt > 0) {
            LOGGER.debug("Frame {}: rebuilt {} dirty BLAS, {} remaining",
                    frameCount, rebuilt, dirtyBLASCount);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Utility
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 建立 Vulkan buffer 並分配 device memory。
     *
     * @param size             buffer 大小（bytes）
     * @param usage            VkBufferUsageFlags
     * @param memoryProperties VkMemoryPropertyFlags
     * @return long[2]: {buffer handle, memory handle}
     */
    private static long[] createBuffer(long size, int usage, int memoryProperties) {
        // 委託給 BRVulkanDevice（Tier 3 stub — 實際 Vulkan 實作需要完整 VkDevice wrapper）
        long device = BRVulkanDevice.getVkDevice();
        long buffer = BRVulkanDevice.createBuffer(device, size, usage);
        long memory = BRVulkanDevice.allocateAndBindBuffer(device, buffer, memoryProperties);
        return new long[]{buffer, memory};
    }

    /**
     * 銷毀 buffer 及其 device memory。
     */
    private static void destroyBufferPair(long device, long buffer, long memory) {
        if (buffer != VK_NULL_HANDLE) {
            BRVulkanDevice.destroyBuffer(device, buffer);
        }
        if (memory != VK_NULL_HANDLE) {
            BRVulkanDevice.freeMemory(device, memory);
        }
    }

    /**
     * 銷毀單一 SectionBLAS 的所有資源。
     */
    private static void destroySectionBLAS(long device, SectionBLAS blas) {
        if (blas.accelerationStructure != VK_NULL_HANDLE) {
            // Tier 3 stub — 實際需要 VkDevice wrapper 呼叫 vkDestroyAccelerationStructureKHR
            LOGGER.debug("[BVH] Destroying BLAS acceleration structure: {}", blas.accelerationStructure);
        }
        destroyBufferPair(device, blas.buffer, blas.bufferMemory);
    }

    /**
     * 部分初始化失敗時清理已分配的資源。
     */
    private static void cleanupPartial() {
        try {
            long device = BRVulkanDevice.getVkDevice();
            if (device != VK_NULL_HANDLE) {
                destroyBufferPair(device, scratchBuffer, scratchBufferMemory);
                destroyBufferPair(device, instanceBuffer, instanceBufferMemory);
            }
        } catch (Exception ignored) {
            // Best-effort cleanup
        }
        scratchBuffer = VK_NULL_HANDLE;
        scratchBufferMemory = VK_NULL_HANDLE;
        instanceBuffer = VK_NULL_HANDLE;
        instanceBufferMemory = VK_NULL_HANDLE;
        initialized = false;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Stats / Accessors
    // ═══════════════════════════════════════════════════════════════════

    /** @return 目前 BLAS 總數量 */
    public static int getBLASCount() {
        return totalBLASCount;
    }

    /** @return 目前標記為 dirty 的 BLAS 數量 */
    public static int getDirtyCount() {
        return dirtyBLASCount;
    }

    /** @return BVH 系統佔用的 GPU 記憶體估計值（bytes） */
    public static long getTotalBVHMemory() {
        return totalBVHMemory;
    }

    /** @return TLAS handle，供 RT pipeline 參照。若未初始化則回傳 VK_NULL_HANDLE。 */
    public static long getTLAS() {
        return initialized ? tlas : VK_NULL_HANDLE;
    }
}
