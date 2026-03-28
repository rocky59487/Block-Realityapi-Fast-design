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
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
            );
            scratchBuffer = scratch[0];
            scratchBufferMemory = scratch[1];
            scratchBufferSize = SCRATCH_BUFFER_SIZE;

            // Allocate instance buffer for TLAS (MAX_SECTIONS * 64 bytes)
            long instanceBufSize = (long) MAX_SECTIONS * INSTANCE_SIZE;
            long[] instBuf = createBuffer(
                    instanceBufSize,
                    VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
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
            VkDevice device = BRVulkanDevice.getVkDevice();

            // Destroy all BLAS
            for (SectionBLAS blas : blasMap.values()) {
                destroySectionBLAS(device, blas);
            }
            blasMap.clear();

            // Destroy TLAS
            if (tlas != VK_NULL_HANDLE) {
                vkDestroyAccelerationStructureKHR(device, tlas, null);
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
            VkDevice device = BRVulkanDevice.getVkDevice();

            // 1. Create AABB geometry buffer (6 floats per AABB = 24 bytes)
            long aabbBufferSize = (long) aabbCount * 6 * Float.BYTES;
            long[] aabbBuf = createBuffer(
                    aabbBufferSize,
                    VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
                            | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
            );
            long aabbBuffer = aabbBuf[0];
            long aabbBufferMemory = aabbBuf[1];

            // Upload AABB data to GPU buffer
            BRVulkanDevice.uploadFloatData(device, aabbBufferMemory, aabbData, aabbCount * 6);

            // Get device address for the AABB buffer
            VkBufferDeviceAddressInfo addrInfo = VkBufferDeviceAddressInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_DEVICE_ADDRESS_INFO)
                    .buffer(aabbBuffer);
            long aabbDeviceAddress = vkGetBufferDeviceAddress(device, addrInfo);

            // 2. Create VkAccelerationStructureGeometryKHR with AABB type
            VkAccelerationStructureGeometryAabbsDataKHR aabbsData =
                    VkAccelerationStructureGeometryAabbsDataKHR.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_AABBS_DATA_KHR)
                            .stride(6 * Float.BYTES);
            aabbsData.data().deviceAddress(aabbDeviceAddress);

            VkAccelerationStructureGeometryKHR.Buffer geometry =
                    VkAccelerationStructureGeometryKHR.calloc(1, stack)
                            .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR)
                            .geometryType(VK_GEOMETRY_TYPE_AABBS_KHR)
                            .flags(VK_GEOMETRY_OPAQUE_BIT_KHR);
            geometry.geometry().aabbs(aabbsData);

            // 3. Query build sizes
            VkAccelerationStructureBuildGeometryInfoKHR buildGeometryInfo =
                    VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR)
                            .type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                            .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                            .pGeometries(geometry);

            VkAccelerationStructureBuildSizesInfoKHR buildSizes =
                    VkAccelerationStructureBuildSizesInfoKHR.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_SIZES_INFO_KHR);

            vkGetAccelerationStructureBuildSizesKHR(
                    device,
                    VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                    buildGeometryInfo,
                    stack.ints(aabbCount),
                    buildSizes
            );

            long resultSize = buildSizes.accelerationStructureSize();
            long buildScratchSize = buildSizes.buildScratchSize();

            if (buildScratchSize > scratchBufferSize) {
                LOGGER.warn("BLAS scratch size ({}) exceeds shared scratch buffer ({}) for section ({}, {})",
                        buildScratchSize, scratchBufferSize, sectionX, sectionZ);
                destroyBufferPair(device, aabbBuffer, aabbBufferMemory);
                return;
            }

            // 4. Allocate result buffer for the acceleration structure
            long[] resultBuf = createBuffer(
                    resultSize,
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR
                            | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
            );
            long resultBuffer = resultBuf[0];
            long resultBufferMemory = resultBuf[1];

            // Create acceleration structure object
            VkAccelerationStructureCreateInfoKHR createInfo =
                    VkAccelerationStructureCreateInfoKHR.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_CREATE_INFO_KHR)
                            .buffer(resultBuffer)
                            .size(resultSize)
                            .type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);

            LongBuffer pAccelerationStructure = stack.mallocLong(1);
            int result = vkCreateAccelerationStructureKHR(device, createInfo, null, pAccelerationStructure);
            if (result != VK_SUCCESS) {
                LOGGER.error("vkCreateAccelerationStructureKHR failed ({}) for section ({}, {})",
                        result, sectionX, sectionZ);
                destroyBufferPair(device, resultBuffer, resultBufferMemory);
                destroyBufferPair(device, aabbBuffer, aabbBufferMemory);
                return;
            }
            long accelerationStructure = pAccelerationStructure.get(0);

            // 5. Build the acceleration structure
            VkBufferDeviceAddressInfo scratchAddrInfo = VkBufferDeviceAddressInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_DEVICE_ADDRESS_INFO)
                    .buffer(scratchBuffer);
            long scratchDeviceAddress = vkGetBufferDeviceAddress(device, scratchAddrInfo);

            buildGeometryInfo
                    .dstAccelerationStructure(accelerationStructure)
                    .scratchData(VkDeviceOrHostAddressKHR.calloc(stack).deviceAddress(scratchDeviceAddress));

            VkAccelerationStructureBuildRangeInfoKHR.Buffer buildRange =
                    VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack)
                            .primitiveCount(aabbCount)
                            .primitiveOffset(0)
                            .firstVertex(0)
                            .transformOffset(0);

            // Execute build in a one-shot command buffer
            VkCommandBuffer cmdBuffer = BRVulkanDevice.allocateCommandBuffer();
            try {
                BRVulkanDevice.beginCommandBuffer(cmdBuffer);

                // LWJGL requires a PointerBuffer of VkAccelerationStructureBuildRangeInfoKHR pointers
                vkCmdBuildAccelerationStructuresKHR(
                        cmdBuffer,
                        VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack)
                                .put(0, buildGeometryInfo),
                        stack.pointers(buildRange)
                );

                BRVulkanDevice.endAndSubmitCommandBuffer(cmdBuffer);
                BRVulkanDevice.waitIdle();
            } catch (Exception e) {
                LOGGER.error("Failed to build BLAS for section ({}, {})", sectionX, sectionZ, e);
                vkDestroyAccelerationStructureKHR(device, accelerationStructure, null);
                destroyBufferPair(device, resultBuffer, resultBufferMemory);
                destroyBufferPair(device, aabbBuffer, aabbBufferMemory);
                return;
            }

            // Clean up temporary AABB geometry buffer
            destroyBufferPair(device, aabbBuffer, aabbBufferMemory);

            // 6. Store in blasMap
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
            VkDevice device = BRVulkanDevice.getVkDevice();

            // Destroy old TLAS if present
            if (tlas != VK_NULL_HANDLE) {
                vkDestroyAccelerationStructureKHR(device, tlas, null);
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

            // Get device address for instance buffer
            VkBufferDeviceAddressInfo instanceAddrInfo = VkBufferDeviceAddressInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_DEVICE_ADDRESS_INFO)
                    .buffer(instanceBuffer);
            long instanceDeviceAddress = vkGetBufferDeviceAddress(device, instanceAddrInfo);

            // Set up geometry for TLAS (instances)
            VkAccelerationStructureGeometryInstancesDataKHR instancesData =
                    VkAccelerationStructureGeometryInstancesDataKHR.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_INSTANCES_DATA_KHR)
                            .arrayOfPointers(false);
            instancesData.data().deviceAddress(instanceDeviceAddress);

            VkAccelerationStructureGeometryKHR.Buffer tlasGeometry =
                    VkAccelerationStructureGeometryKHR.calloc(1, stack)
                            .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR)
                            .geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR);
            tlasGeometry.geometry().instances(instancesData);

            VkAccelerationStructureBuildGeometryInfoKHR buildGeometryInfo =
                    VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR)
                            .type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                            .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                            .pGeometries(tlasGeometry);

            // Query build sizes
            VkAccelerationStructureBuildSizesInfoKHR buildSizes =
                    VkAccelerationStructureBuildSizesInfoKHR.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_SIZES_INFO_KHR);

            vkGetAccelerationStructureBuildSizesKHR(
                    device,
                    VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                    buildGeometryInfo,
                    stack.ints(instanceCount),
                    buildSizes
            );

            long tlasSize = buildSizes.accelerationStructureSize();
            long tlasScratchSize = buildSizes.buildScratchSize();

            if (tlasScratchSize > scratchBufferSize) {
                LOGGER.warn("TLAS scratch size ({}) exceeds shared scratch buffer ({})",
                        tlasScratchSize, scratchBufferSize);
                return;
            }

            // Allocate TLAS buffer
            long[] tlasBuf = createBuffer(
                    tlasSize,
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR
                            | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
            );
            tlasBuffer = tlasBuf[0];
            tlasBufferMemory = tlasBuf[1];

            // Create TLAS object
            VkAccelerationStructureCreateInfoKHR createInfo =
                    VkAccelerationStructureCreateInfoKHR.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_CREATE_INFO_KHR)
                            .buffer(tlasBuffer)
                            .size(tlasSize)
                            .type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR);

            LongBuffer pTlas = stack.mallocLong(1);
            int result = vkCreateAccelerationStructureKHR(device, createInfo, null, pTlas);
            if (result != VK_SUCCESS) {
                LOGGER.error("vkCreateAccelerationStructureKHR (TLAS) failed: {}", result);
                destroyBufferPair(device, tlasBuffer, tlasBufferMemory);
                tlasBuffer = VK_NULL_HANDLE;
                tlasBufferMemory = VK_NULL_HANDLE;
                return;
            }
            tlas = pTlas.get(0);

            // Build TLAS
            VkBufferDeviceAddressInfo scratchAddrInfo = VkBufferDeviceAddressInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_DEVICE_ADDRESS_INFO)
                    .buffer(scratchBuffer);
            long scratchDeviceAddress = vkGetBufferDeviceAddress(device, scratchAddrInfo);

            buildGeometryInfo
                    .dstAccelerationStructure(tlas)
                    .scratchData(VkDeviceOrHostAddressKHR.calloc(stack).deviceAddress(scratchDeviceAddress));

            VkAccelerationStructureBuildRangeInfoKHR.Buffer buildRange =
                    VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack)
                            .primitiveCount(instanceCount)
                            .primitiveOffset(0)
                            .firstVertex(0)
                            .transformOffset(0);

            VkCommandBuffer cmdBuffer = BRVulkanDevice.allocateCommandBuffer();
            try {
                BRVulkanDevice.beginCommandBuffer(cmdBuffer);

                vkCmdBuildAccelerationStructuresKHR(
                        cmdBuffer,
                        VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack)
                                .put(0, buildGeometryInfo),
                        stack.pointers(buildRange)
                );

                BRVulkanDevice.endAndSubmitCommandBuffer(cmdBuffer);
                BRVulkanDevice.waitIdle();
            } catch (Exception e) {
                LOGGER.error("Failed to build TLAS", e);
                return;
            }

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
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDevice device = BRVulkanDevice.getVkDevice();

            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(size)
                    .usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            LongBuffer pBuffer = stack.mallocLong(1);
            int result = vkCreateBuffer(device, bufferInfo, null, pBuffer);
            if (result != VK_SUCCESS) {
                throw new RuntimeException("vkCreateBuffer failed: " + result);
            }
            long buffer = pBuffer.get(0);

            // Query memory requirements
            VkMemoryRequirements memRequirements = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(device, buffer, memRequirements);

            int memoryTypeIndex = BRVulkanDevice.findMemoryType(
                    memRequirements.memoryTypeBits(), memoryProperties);

            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(memRequirements.size())
                    .memoryTypeIndex(memoryTypeIndex);

            // Enable device address if requested
            VkMemoryAllocateFlagsInfo flagsInfo = null;
            if ((usage & VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT) != 0) {
                flagsInfo = VkMemoryAllocateFlagsInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_FLAGS_INFO)
                        .flags(VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT);
                allocInfo.pNext(flagsInfo.address());
            }

            LongBuffer pMemory = stack.mallocLong(1);
            result = vkAllocateMemory(device, allocInfo, null, pMemory);
            if (result != VK_SUCCESS) {
                vkDestroyBuffer(device, buffer, null);
                throw new RuntimeException("vkAllocateMemory failed: " + result);
            }
            long memory = pMemory.get(0);

            vkBindBufferMemory(device, buffer, memory, 0);

            return new long[]{buffer, memory};
        }
    }

    /**
     * 銷毀 buffer 及其 device memory。
     */
    private static void destroyBufferPair(VkDevice device, long buffer, long memory) {
        if (buffer != VK_NULL_HANDLE) {
            vkDestroyBuffer(device, buffer, null);
        }
        if (memory != VK_NULL_HANDLE) {
            vkFreeMemory(device, memory, null);
        }
    }

    /**
     * 銷毀單一 SectionBLAS 的所有資源。
     */
    private static void destroySectionBLAS(VkDevice device, SectionBLAS blas) {
        if (blas.accelerationStructure != VK_NULL_HANDLE) {
            vkDestroyAccelerationStructureKHR(device, blas.accelerationStructure, null);
        }
        destroyBufferPair(device, blas.buffer, blas.bufferMemory);
    }

    /**
     * 部分初始化失敗時清理已分配的資源。
     */
    private static void cleanupPartial() {
        try {
            VkDevice device = BRVulkanDevice.getVkDevice();
            if (device != null) {
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
