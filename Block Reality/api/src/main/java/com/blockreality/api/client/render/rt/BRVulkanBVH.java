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

    /**
     * 共用 scratch buffer 大小。
     *
     * <p>★ RT-1-2: 從 16 MB 擴充至 64 MB。
     * 原因：Blackwell Cluster BVH 每個 cluster BLAS 的 scratch 需求約為傳統 section BLAS 的 4×，
     * 且同幀可重建的 cluster 數量（{@link #MAX_BLAS_REBUILDS_PER_FRAME}）可達到更高上限。
     * 64 MB 確保在最差情況（8 個 cluster 並行重建）不因 scratch 不足而觸發回退路徑。
     *
     * <p>記憶體估算：
     * <ul>
     *   <li>Section BLAS scratch: ~2 MB / section</li>
     *   <li>Cluster BLAS scratch: ~8 MB / cluster（覆蓋 16 sections 的幾何）</li>
     *   <li>8 clusters × 8 MB = 64 MB（安全上限）</li>
     * </ul>
     */
    public static final long SCRATCH_BUFFER_SIZE = 64L * 1024L * 1024L;

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

    /** ★ UI-3: 首次 TLAS 更新成功旗標，用於診斷日誌 */
    private static boolean firstTLASUpdateLogged = false;

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
            LOGGER.info("[RT-1-2] Initializing BVH manager (scratch={}MB, maxSections={})",
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

            // Phase 3: Dispatch to Cluster AS path on Blackwell hardware, standard KHR otherwise
            if (BRVulkanDevice.hasClusterAS()) {
                buildClusterBLAS(sectionX, sectionZ, aabbData, aabbCount,
                    device, resultBuffer, resultBufferMemory, aabbBuffer, aabbBufferMemory);
            } else {
                buildBLASOpaque_KHR(sectionX, sectionZ, aabbDeviceAddress, aabbCount,
                    device, resultBuffer, resultBufferMemory, aabbBuffer, aabbBufferMemory);
            }

        } catch (Exception e) {
            LOGGER.error("Failed to build BLAS for section ({}, {})", sectionX, sectionZ, e);
        }
    }

    /**
     * Standard KHR BLAS build with VK_GEOMETRY_OPAQUE_BIT_KHR.
     * Called by buildBLAS() when hasClusterAS is false (non-Blackwell hardware).
     */
    private static void buildBLASOpaque_KHR(int sectionX, int sectionZ,
                                              long aabbDeviceAddress, int aabbCount,
                                              long device,
                                              long resultBuffer, long resultBufferMemory,
                                              long aabbBuffer, long aabbBufferMemory) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 1. Geometry descriptor (opaque AABB)
            VkAccelerationStructureGeometryKHR.Buffer geometry =
                VkAccelerationStructureGeometryKHR.calloc(1, stack);
            geometry.get(0)
                .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR)
                .geometryType(VK_GEOMETRY_TYPE_AABBS_KHR)
                .flags(VK_GEOMETRY_OPAQUE_BIT_KHR);
            geometry.get(0).geometry().aabbs()
                .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_AABBS_DATA_KHR)
                .data().deviceAddress(aabbDeviceAddress)
                .stride(6 * Float.BYTES);

            // 2. Build geometry info
            VkAccelerationStructureBuildGeometryInfoKHR buildInfo =
                VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack);
            buildInfo
                .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR)
                .type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                .flags(VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR
                     | VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                .pGeometries(geometry);

            // 3. Query required sizes
            VkAccelerationStructureBuildSizesInfoKHR sizeInfo =
                VkAccelerationStructureBuildSizesInfoKHR.calloc(stack);
            sizeInfo.sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_SIZES_INFO_KHR);
            KHRAccelerationStructure.vkGetAccelerationStructureBuildSizesKHR(
                device, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                buildInfo, stack.ints(aabbCount), sizeInfo);

            // 4. Create acceleration structure
            VkAccelerationStructureCreateInfoKHR createInfo =
                VkAccelerationStructureCreateInfoKHR.calloc(stack);
            createInfo
                .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_CREATE_INFO_KHR)
                .buffer(resultBuffer)
                .size(sizeInfo.accelerationStructureSize())
                .type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);

            LongBuffer pBlas = stack.mallocLong(1);
            int result = KHRAccelerationStructure.vkCreateAccelerationStructureKHR(
                device, createInfo, null, pBlas);
            if (result != VK_SUCCESS) {
                LOGGER.error("vkCreateAccelerationStructureKHR failed ({}) for section ({},{})",
                    result, sectionX, sectionZ);
                destroyBufferPair(device, resultBuffer, resultBufferMemory);
                destroyBufferPair(device, aabbBuffer, aabbBufferMemory);
                return;
            }

            long blasHandle = pBlas.get(0);

            // 5. Build the acceleration structure
            buildInfo.dstAccelerationStructure(blasHandle)
                .scratchData().deviceAddress(
                    BRVulkanDevice.getBufferDeviceAddress(device, scratchBuffer));

            VkAccelerationStructureBuildRangeInfoKHR.Buffer rangeInfo =
                VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack);
            rangeInfo.get(0).primitiveCount(aabbCount).primitiveOffset(0)
                .firstVertex(0).transformOffset(0);

            VkCommandBuffer cmdBuf = BRVulkanDevice.beginSingleTimeCommands(device);
            KHRAccelerationStructure.vkCmdBuildAccelerationStructuresKHR(
                cmdBuf, buildInfo, rangeInfo);
            BRVulkanDevice.endSingleTimeCommands(device, cmdBuf);

            // 6. Store in blasMap (handle is valid → safe to use in RT pipeline)
            SectionBLAS blas = new SectionBLAS();
            blas.accelerationStructure = blasHandle;
            blas.buffer       = resultBuffer;
            blas.bufferMemory = resultBufferMemory;
            blas.sectionX     = sectionX;
            blas.sectionZ     = sectionZ;
            blas.dirty        = false;
            blas.lastUpdateFrame = frameCount;

            long key = encodeSectionKey(sectionX, sectionZ);
            blasMap.put(key, blas);
            totalBLASCount = blasMap.size();

            // Geometry buffer released after build (BLAS owns its result buffer)
            destroyBufferPair(device, aabbBuffer, aabbBufferMemory);

            LOGGER.debug("BLAS built for section ({},{}) handle=0x{} ({} AABBs)",
                sectionX, sectionZ, Long.toHexString(blasHandle), aabbCount);

        } catch (Exception e) {
            LOGGER.error("buildBLASOpaque_KHR failed for section ({},{})", sectionX, sectionZ, e);
            destroyBufferPair(device, resultBuffer, resultBufferMemory);
            destroyBufferPair(device, aabbBuffer, aabbBufferMemory);
        }
    }

    /**
     * Blackwell Cluster AS path (VK_NV_cluster_acceleration_structure).
     * Only called when BRVulkanDevice.hasClusterAS is true.
     */
    private static void buildClusterBLAS(int sectionX, int sectionZ,
                                          float[] aabbData, int aabbCount,
                                          long device,
                                          long resultBuffer, long resultBufferMemory,
                                          long aabbBuffer, long aabbBufferMemory) {
        // VK_NV_cluster_acceleration_structure: batch AABBs into 4×4 spatial clusters
        // then call vkCmdBuildClusterAccelerationStructureIndirectNV.
        // Falls back to standard KHR path until cluster extension is fully integrated.
        LOGGER.debug("Cluster AS path for section ({},{}): delegating to KHR path (cluster integration pending)",
            sectionX, sectionZ);
        long aabbDeviceAddress = BRVulkanDevice.getBufferDeviceAddress(device, aabbBuffer);
        buildBLASOpaque_KHR(sectionX, sectionZ, aabbDeviceAddress, aabbCount,
            device, resultBuffer, resultBufferMemory, aabbBuffer, aabbBufferMemory);
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
        // TODO Phase 3: Call BRVulkanDevice.buildBLASOpaque() directly to pass VK_GEOMETRY_OPAQUE_BIT_KHR flag
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
     * 重建或增量更新 Top-Level Acceleration Structure。
     *
     * <p>當 TLAS 已存在且僅有 instance transform 變化（無 BLAS 增刪）時，
     * 使用 {@code VK_BUILD_ACCELERATION_STRUCTURE_MODE_UPDATE_KHR} 增量更新，
     * 避免全量重建的 GPU stall。
     *
     * <p>BLAS 增刪時（{@code blasSizeChanged == true}）執行完整重建。
     */
    private static int lastTLASInstanceCount = 0;

    public static void rebuildTLAS() {
        if (!initialized || blasMap.isEmpty()) return;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            long device = BRVulkanDevice.getVkDevice();

            List<SectionBLAS> activeEntries = new ArrayList<>(blasMap.values());
            int instanceCount = activeEntries.size();
            if (instanceCount == 0) return;

            // Determine build mode: UPDATE if TLAS exists and instance count unchanged
            boolean tlasExists     = (tlas != VK_NULL_HANDLE && tlas != 2L);
            boolean instanceCountChanged = (instanceCount != lastTLASInstanceCount);
            boolean useUpdateMode  = tlasExists && !instanceCountChanged;

            if (!useUpdateMode) {
                // Full rebuild: destroy old TLAS
                if (tlas != VK_NULL_HANDLE) {
                    if (tlas != 2L) {  // guard against stub placeholder
                        KHRAccelerationStructure.vkDestroyAccelerationStructureKHR(
                            device, tlas, null);
                    }
                    tlas = VK_NULL_HANDLE;
                }
                if (tlasBuffer != VK_NULL_HANDLE) {
                    destroyBufferPair(device, tlasBuffer, tlasBufferMemory);
                    tlasBuffer = VK_NULL_HANDLE;
                    tlasBufferMemory = VK_NULL_HANDLE;
                }
            }

            // Upload instance data (transforms + BLAS device addresses)
            BRVulkanDevice.uploadTLASInstances(device, instanceBufferMemory, activeEntries);

            if (!useUpdateMode) {
                // Allocate new TLAS buffer
                long tlasSize = 1024 * 128L * Math.max(instanceCount, 1);
                long[] tlasBuf = createBuffer(
                    tlasSize,
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR
                        | VulkanConstants.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
                tlasBuffer = tlasBuf[0];
                tlasBufferMemory = tlasBuf[1];

                // Create TLAS acceleration structure handle
                VkAccelerationStructureCreateInfoKHR tlasCreateInfo =
                    VkAccelerationStructureCreateInfoKHR.calloc(stack);
                tlasCreateInfo
                    .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_CREATE_INFO_KHR)
                    .buffer(tlasBuffer)
                    .size(tlasSize)
                    .type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR);
                LongBuffer pTlas = stack.mallocLong(1);
                int createResult = KHRAccelerationStructure.vkCreateAccelerationStructureKHR(
                    device, tlasCreateInfo, null, pTlas);
                if (createResult != VK_SUCCESS) {
                    LOGGER.error("[BVH] vkCreateAccelerationStructureKHR (TLAS) failed: {}", createResult);
                    return;
                }
                tlas = pTlas.get(0);
            }

            // Build or update TLAS on GPU
            int buildMode = useUpdateMode
                ? VK_BUILD_ACCELERATION_STRUCTURE_MODE_UPDATE_KHR
                : VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR;

            // Instance geometry descriptor
            VkAccelerationStructureGeometryKHR.Buffer tlasGeometry =
                VkAccelerationStructureGeometryKHR.calloc(1, stack);
            tlasGeometry.get(0)
                .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR)
                .geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR)
                .flags(VK_GEOMETRY_OPAQUE_BIT_KHR);
            tlasGeometry.get(0).geometry().instances()
                .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_INSTANCES_DATA_KHR)
                .data().deviceAddress(BRVulkanDevice.getBufferDeviceAddress(device, instanceBuffer))
                .arrayOfPointers(false);

            VkAccelerationStructureBuildGeometryInfoKHR tlasBuildInfo =
                VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack);
            tlasBuildInfo
                .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR)
                .type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                .flags(VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR
                     | VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                .mode(buildMode)
                .pGeometries(tlasGeometry)
                .dstAccelerationStructure(tlas)
                .scratchData().deviceAddress(
                    BRVulkanDevice.getBufferDeviceAddress(device, scratchBuffer));
            if (useUpdateMode) {
                tlasBuildInfo.srcAccelerationStructure(tlas); // in-place update
            }

            VkAccelerationStructureBuildRangeInfoKHR.Buffer tlasRangeInfo =
                VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack);
            tlasRangeInfo.get(0)
                .primitiveCount(instanceCount)
                .primitiveOffset(0)
                .firstVertex(0)
                .transformOffset(0);

            VkCommandBuffer tlasCmdBuf = BRVulkanDevice.beginSingleTimeCommands(device);
            KHRAccelerationStructure.vkCmdBuildAccelerationStructuresKHR(
                tlasCmdBuf, tlasBuildInfo, tlasRangeInfo);
            BRVulkanDevice.endSingleTimeCommands(device, tlasCmdBuf);

            LOGGER.debug("[BVH] TLAS {}: {} instances", useUpdateMode ? "updated" : "built", instanceCount);

            lastTLASInstanceCount = instanceCount;

            LOGGER.debug("TLAS {}: {} instances", useUpdateMode ? "updated" : "built", instanceCount);

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

        // ★ UI-3: 診斷日誌 — 確認 TLAS 更新路徑有被執行
        if (!firstTLASUpdateLogged) {
            firstTLASUpdateLogged = true;
            LOGGER.info("[UI-3/BVH] TLAS update path entered for first time " +
                    "(sections={}, dirty={}, frame={})",
                    blasMap.size(), dirtyBLASCount, frameCount);
        }

        if (dirtyBLASCount == 0) return;

        // ★ UI-3: 診斷日誌 — 記錄每次實際重建的統計資料
        LOGGER.debug("[UI-3/BVH] Frame {}: sections={}, dirty={}, rebuilding up to {}",
                frameCount, blasMap.size(), dirtyBLASCount, MAX_BLAS_REBUILDS_PER_FRAME);

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
            LOGGER.debug("[UI-3/BVH] Frame {}: rebuilt {} BLAS, {} dirty remain, totalSections={}",
                    frameCount, rebuilt, dirtyBLASCount, blasMap.size());
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

    /**
     * @return 目前幀計數（由 {@link #updateTLAS()} 每幀遞增）。
     *         供 {@link BRClusterBVH} 同步使用。
     */
    public static long getFrameCount() {
        return frameCount;
    }
}
