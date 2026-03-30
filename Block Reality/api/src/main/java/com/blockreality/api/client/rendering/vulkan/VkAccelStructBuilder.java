package com.blockreality.api.client.rendering.vulkan;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.concurrent.ConcurrentHashMap;

import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * BLAS / TLAS 加速結構建構器（Phase 2-B）。
 *
 * 移植來源：Radiance MCVR AccelerationStructure.cpp + Sascha Willems raytracingbasic.cpp
 *
 * BLAS（Bottom-Level Acceleration Structure）：
 *   每個 LOD chunk mesh 對應一個 BLAS。
 *   靜態 chunk 一次 build，永不更新。
 *
 * TLAS（Top-Level Acceleration Structure）：
 *   整個場景的 BLAS instance 集合，每幀根據玩家視野更新。
 *
 * @see VkContext
 * @see VkMemoryAllocator
 * @see VkRTPipeline
 */
@OnlyIn(Dist.CLIENT)
public class VkAccelStructBuilder {

    private static final Logger LOG = LoggerFactory.getLogger("BR-VkAccel");

    /** 單一 BLAS 最大三角形數限制 */
    private static final int MAX_TRIANGLES_PER_BLAS = 65536;

    /** TLAS instance 最大數量（同時可見 chunk 數） */
    private static final int MAX_TLAS_INSTANCES = 4096;

    /** 頂點 stride：position(xyz) + normal(xyz) = 6 floats = 24 bytes */
    private static final int VERTEX_STRIDE_BYTES = 6 * Float.BYTES;

    private final VkContext       context;
    private final VkMemoryAllocator allocator;

    // ─── BLAS 追蹤 ───
    /** chunkKey → BLASEntry */
    private final ConcurrentHashMap<Long, BLASEntry> blasMap = new ConcurrentHashMap<>();

    // ─── TLAS ───
    private long tlasHandle     = 0;
    private long tlasBuffer     = 0;  // VkBuffer holding the AS data
    private long tlasAllocation = 0;
    private long tlasScratch    = 0;
    private long tlasScratchAlloc = 0;
    private long tlasInstanceBuffer    = 0;
    private long tlasInstanceAlloc     = 0;

    /** 持有一個 BLAS 的所有 Vulkan 資源 */
    private static final class BLASEntry {
        long handle;      // VkAccelerationStructureKHR
        long buffer;      // VkBuffer (device-local, holds AS data)
        long allocation;  // VMA allocation
        long deviceAddress; // for TLAS instance

        BLASEntry(long handle, long buffer, long allocation, long deviceAddress) {
            this.handle = handle;
            this.buffer = buffer;
            this.allocation = allocation;
            this.deviceAddress = deviceAddress;
        }
    }

    public VkAccelStructBuilder(VkContext context, VkMemoryAllocator allocator) {
        this.context   = context;
        this.allocator = allocator;
    }

    // ═══ BLAS 建構 ═══

    /**
     * 為指定 chunk 的 LOD mesh 建構 BLAS。
     *
     * 頂點格式假設為：float[]{x,y,z, nx,ny,nz, ...}（10 floats/vertex，
     * 但 RT 只使用前 3 floats 做位置）。
     *
     * 此方法以 one-time command buffer 提交 build 命令，會等 GPU 完成。
     *
     * @param chunkKey chunk 唯一鍵
     * @param vertices 頂點資料（10 floats per vertex，position at offset 0）
     * @param vertexCount 頂點數（非 floats 數）
     * @param indices  索引資料（int[]，三角形列表）
     * @return BLAS device address，失敗返回 0
     */
    public long buildBLAS(long chunkKey, float[] vertices, int vertexCount, int[] indices) {
        if (!context.isRTSupported()) return 0;
        if (vertices == null || indices == null || indices.length == 0) return 0;

        int triangleCount = indices.length / 3;
        if (triangleCount > MAX_TRIANGLES_PER_BLAS) {
            LOG.warn("buildBLAS: chunk {} has {} triangles, clamping to {}",
                chunkKey, triangleCount, MAX_TRIANGLES_PER_BLAS);
            triangleCount = MAX_TRIANGLES_PER_BLAS;
        }

        // 銷毀舊 BLAS（若存在）
        destroyBLAS(chunkKey);

        try {
            // 1. 上傳頂點/索引到 device buffer（透過 staging）
            long[] vertexBuf = uploadVertexBuffer(vertices, vertexCount);
            if (vertexBuf == null) return 0;
            long[] indexBuf  = uploadIndexBuffer(indices, triangleCount * 3);
            if (indexBuf == null) {
                allocator.freeBuffer(vertexBuf[0]);
                return 0;
            }

            long vBufHandle = vertexBuf[0];
            long iBufHandle = indexBuf[0];

            // 2. 取得 buffer device address
            long vertexAddr = getBufferDeviceAddress(vBufHandle);
            long indexAddr  = getBufferDeviceAddress(iBufHandle);

            // 3. 建構 BLAS
            long[] result = buildBLASFromBuffers(vertexAddr, vertexCount, indexAddr,
                                                  triangleCount);

            // scratch buffer 用畢可釋放（staging 也可，但留到下次 GC）
            allocator.freeBuffer(vBufHandle);
            allocator.freeBuffer(iBufHandle);

            if (result == null) return 0;

            long blasHandle  = result[0];
            long blasBuffer  = result[1];
            long blasAlloc   = result[2];
            long blasAddr    = result[3];

            BLASEntry entry = new BLASEntry(blasHandle, blasBuffer, blasAlloc, blasAddr);
            blasMap.put(chunkKey, entry);

            LOG.debug("buildBLAS: chunk {} OK, deviceAddr=0x{}", chunkKey,
                Long.toHexString(blasAddr));
            return blasAddr;

        } catch (Exception e) {
            LOG.error("buildBLAS: chunk {} failed: {}", chunkKey, e.getMessage(), e);
            return 0;
        }
    }

    /**
     * 銷毀指定 chunk 的 BLAS。
     */
    public void destroyBLAS(long chunkKey) {
        BLASEntry entry = blasMap.remove(chunkKey);
        if (entry == null) return;
        vkDestroyAccelerationStructureKHR(context.getDevice(), entry.handle, null);
        allocator.freeBuffer(entry.buffer);
    }

    // ═══ TLAS 建構 ═══

    /**
     * 重建 TLAS（每幀呼叫）。
     *
     * @param blasAddresses BLAS device address 陣列（每個可見 chunk 一個）
     * @param worldOffsets  對應的世界座標偏移（每個 chunk 一個 float[3]）
     * @return TLAS handle，失敗返回 0
     */
    public long rebuildTLAS(long[] blasAddresses, float[][] worldOffsets) {
        if (!context.isRTSupported()) return 0;
        if (blasAddresses == null || blasAddresses.length == 0) return 0;

        int instanceCount = Math.min(blasAddresses.length, MAX_TLAS_INSTANCES);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDevice device = context.getDevice();

            // 1. 填充 VkAccelerationStructureInstanceKHR 陣列（每個 64 bytes）
            int instanceStride = VkAccelerationStructureInstanceKHR.SIZEOF; // 64 bytes
            ByteBuffer instanceData = org.lwjgl.system.MemoryUtil.memAlloc(
                instanceCount * instanceStride);
            try {
                for (int i = 0; i < instanceCount; i++) {
                    VkAccelerationStructureInstanceKHR inst =
                        VkAccelerationStructureInstanceKHR.create(
                            org.lwjgl.system.MemoryUtil.memAddress(instanceData)
                            + (long) i * instanceStride);

                    // Transform：3×4 行主序 float buffer（LWJGL: matrix() returns FloatBuffer[12]）
                    float tx = worldOffsets != null && worldOffsets[i] != null
                        ? worldOffsets[i][0] : 0f;
                    float ty = worldOffsets != null && worldOffsets[i] != null
                        ? worldOffsets[i][1] : 0f;
                    float tz = worldOffsets != null && worldOffsets[i] != null
                        ? worldOffsets[i][2] : 0f;

                    // VkTransformMatrixKHR is row-major 3×4:
                    // [ m[0], m[1], m[2], m[3] ]  row 0
                    // [ m[4], m[5], m[6], m[7] ]  row 1
                    // [ m[8], m[9], m[10], m[11]] row 2
                    inst.transform().matrix().put(new float[]{
                        1.0f, 0.0f, 0.0f, tx,
                        0.0f, 1.0f, 0.0f, ty,
                        0.0f, 0.0f, 1.0f, tz
                    }).rewind();

                    inst.instanceCustomIndex(i);
                    inst.mask(0xFF);
                    inst.instanceShaderBindingTableRecordOffset(0);
                    inst.flags(VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR);
                    inst.accelerationStructureReference(blasAddresses[i]);
                }

                // 2. 上傳 instance data 到 device buffer
                ensureTLASInstanceBuffer((long) instanceCount * instanceStride);
                uploadToBuffer(tlasInstanceBuffer, tlasInstanceAlloc, instanceData,
                    (long) instanceCount * instanceStride);

            } finally {
                org.lwjgl.system.MemoryUtil.memFree(instanceData);
            }

            long instanceBufferAddr = getBufferDeviceAddress(tlasInstanceBuffer);

            // 3. 描述 TLAS geometry（instances）
            VkAccelerationStructureGeometryKHR.Buffer tlasGeometry =
                VkAccelerationStructureGeometryKHR.calloc(1, stack);
            tlasGeometry.get(0)
                .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR)
                .geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR)
                .flags(VK_GEOMETRY_OPAQUE_BIT_KHR);
            tlasGeometry.get(0).geometry().instances()
                .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_INSTANCES_DATA_KHR)
                .arrayOfPointers(false);
            tlasGeometry.get(0).geometry().instances().data().deviceAddress(instanceBufferAddr);

            // 4. 查詢 TLAS build size
            VkAccelerationStructureBuildGeometryInfoKHR tlasBuildInfo =
                VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR)
                    .type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                    .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                    .pGeometries(tlasGeometry);

            VkAccelerationStructureBuildSizesInfoKHR tlasSizes =
                VkAccelerationStructureBuildSizesInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_SIZES_INFO_KHR);

            IntBuffer pInstanceCount = stack.ints(instanceCount);
            vkGetAccelerationStructureBuildSizesKHR(device,
                VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                tlasBuildInfo, pInstanceCount, tlasSizes);

            long asSize      = tlasSizes.accelerationStructureSize();
            long scratchSize = tlasSizes.buildScratchSize();

            // 5. 建立或複用 TLAS buffer
            if (tlasHandle != 0) {
                // 銷毀舊 TLAS
                vkDestroyAccelerationStructureKHR(device, tlasHandle, null);
                tlasHandle = 0;
            }
            ensureTLASBuffer(asSize);
            ensureTLASScratch(scratchSize);

            // 6. 建立 VkAccelerationStructureKHR
            VkAccelerationStructureCreateInfoKHR tlasCreateInfo =
                VkAccelerationStructureCreateInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_CREATE_INFO_KHR)
                    .buffer(tlasBuffer)
                    .size(asSize)
                    .type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR);

            LongBuffer pTlas = stack.mallocLong(1);
            int result = vkCreateAccelerationStructureKHR(device, tlasCreateInfo, null, pTlas);
            if (result != VK_SUCCESS) {
                LOG.error("vkCreateAccelerationStructureKHR (TLAS) failed: {}", result);
                return 0;
            }
            tlasHandle = pTlas.get(0);

            // 7. 提交 build 命令
            long scratchAddr = getBufferDeviceAddress(tlasScratch);
            tlasBuildInfo
                .dstAccelerationStructure(tlasHandle)
                .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR);
            tlasBuildInfo.scratchData().deviceAddress(scratchAddr);

            VkAccelerationStructureBuildRangeInfoKHR.Buffer ranges =
                VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack);
            ranges.get(0)
                .primitiveCount(instanceCount)
                .primitiveOffset(0)
                .firstVertex(0)
                .transformOffset(0);

            submitOneTimeBuild(stack, cmd -> {
                PointerBuffer ppRanges = stack.pointers(ranges);
                VkAccelerationStructureBuildGeometryInfoKHR.Buffer infoBuf =
                    VkAccelerationStructureBuildGeometryInfoKHR.create(
                        tlasBuildInfo.address(), 1);
                vkCmdBuildAccelerationStructuresKHR(cmd, infoBuf, ppRanges);
            });

            LOG.debug("rebuildTLAS: {} instances, handle=0x{}", instanceCount,
                Long.toHexString(tlasHandle));
            return tlasHandle;

        } catch (Exception e) {
            LOG.error("rebuildTLAS failed: {}", e.getMessage(), e);
            return 0;
        }
    }

    // ═══ 私有輔助：buffer 建立 / 上傳 ═══

    /**
     * 上傳 float[] 頂點到 device-local buffer（透過 staging）。
     * 只上傳前 vertexCount 個頂點（stride=10 floats）。
     */
    private long[] uploadVertexBuffer(float[] vertices, int vertexCount) {
        int floatsToUpload = vertexCount * 10; // 10 floats per vertex（完整格式）
        long byteSize = (long) floatsToUpload * Float.BYTES;

        // device-local buffer
        long[] deviceBuf = allocator.allocateDeviceBuffer(byteSize,
            VK_BUFFER_USAGE_VERTEX_BUFFER_BIT |
            VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR |
            VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT |
            VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        if (deviceBuf == null) return null;

        // staging buffer
        long[] stagingBuf = allocator.allocateStagingBuffer(byteSize);
        if (stagingBuf == null) {
            allocator.freeBuffer(deviceBuf[0]);
            return null;
        }

        // copy float[] → staging
        float[] trimmed = floatsToUpload == vertices.length ? vertices
            : java.util.Arrays.copyOf(vertices, floatsToUpload);
        allocator.writeFloats(stagingBuf[1], trimmed);

        // submit copy: staging → device
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long stagingHandle = stagingBuf[0];
            long deviceHandle  = deviceBuf[0];
            submitOneTimeBuild(stack, cmd -> {
                VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack);
                region.get(0).size(byteSize);
                vkCmdCopyBuffer(cmd, stagingHandle, deviceHandle, region);
            });
        }
        allocator.freeBuffer(stagingBuf[0]);

        return deviceBuf;
    }

    /** 上傳 int[] 索引到 device-local buffer（透過 staging）。 */
    private long[] uploadIndexBuffer(int[] indices, int count) {
        long byteSize = (long) count * Integer.BYTES;

        long[] deviceBuf = allocator.allocateDeviceBuffer(byteSize,
            VK_BUFFER_USAGE_INDEX_BUFFER_BIT |
            VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR |
            VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT |
            VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        if (deviceBuf == null) return null;

        long[] stagingBuf = allocator.allocateStagingBuffer(byteSize);
        if (stagingBuf == null) {
            allocator.freeBuffer(deviceBuf[0]);
            return null;
        }

        // 將 int[] 轉 byte[] 後寫入 staging
        byte[] bytes = new byte[(int) byteSize];
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(bytes)
            .order(java.nio.ByteOrder.nativeOrder());
        for (int i = 0; i < count; i++) bb.putInt(indices[i]);
        allocator.writeToBuffer(stagingBuf[1], bytes);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            long stagingHandle = stagingBuf[0];
            long deviceHandle  = deviceBuf[0];
            submitOneTimeBuild(stack, cmd -> {
                VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack);
                region.get(0).size(byteSize);
                vkCmdCopyBuffer(cmd, stagingHandle, deviceHandle, region);
            });
        }
        allocator.freeBuffer(stagingBuf[0]);

        return deviceBuf;
    }

    /**
     * 從已上傳的 vertex/index device buffer 建構 BLAS。
     * 回傳 [blasHandle, blasBuffer, blasAllocation, deviceAddress]
     */
    private long[] buildBLASFromBuffers(long vertexAddr, int vertexCount,
                                         long indexAddr, int triangleCount) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDevice device = context.getDevice();

            // Geometry 描述
            VkAccelerationStructureGeometryKHR.Buffer geometry =
                VkAccelerationStructureGeometryKHR.calloc(1, stack);
            geometry.get(0)
                .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR)
                .geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                .flags(VK_GEOMETRY_OPAQUE_BIT_KHR);

            VkAccelerationStructureGeometryTrianglesDataKHR tri =
                geometry.get(0).geometry().triangles();
            tri.sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_TRIANGLES_DATA_KHR)
               .vertexFormat(VK_FORMAT_R32G32B32_SFLOAT)
               .vertexStride(10 * Float.BYTES)   // 10 floats per vertex（position at offset 0）
               .maxVertex(vertexCount - 1)
               .indexType(VK_INDEX_TYPE_UINT32);
            tri.vertexData().deviceAddress(vertexAddr);
            tri.indexData().deviceAddress(indexAddr);

            // Build info（用來查詢 size）
            VkAccelerationStructureBuildGeometryInfoKHR buildInfo =
                VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR)
                    .type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                    .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                    .pGeometries(geometry);

            VkAccelerationStructureBuildSizesInfoKHR sizes =
                VkAccelerationStructureBuildSizesInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_SIZES_INFO_KHR);

            IntBuffer pTriCount = stack.ints(triangleCount);
            vkGetAccelerationStructureBuildSizesKHR(device,
                VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                buildInfo, pTriCount, sizes);

            long asSize      = sizes.accelerationStructureSize();
            long scratchSize = sizes.buildScratchSize();

            // AS backing buffer
            long[] asBuf = allocator.allocateDeviceBuffer(asSize,
                VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR |
                VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT);
            if (asBuf == null) return null;

            // Scratch buffer
            long[] scratchBuf = allocator.allocateDeviceBuffer(scratchSize,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT |
                VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT);
            if (scratchBuf == null) {
                allocator.freeBuffer(asBuf[0]);
                return null;
            }

            // 建立 AS
            VkAccelerationStructureCreateInfoKHR createInfo =
                VkAccelerationStructureCreateInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_CREATE_INFO_KHR)
                    .buffer(asBuf[0])
                    .size(asSize)
                    .type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);

            LongBuffer pBlas = stack.mallocLong(1);
            int rc = vkCreateAccelerationStructureKHR(device, createInfo, null, pBlas);
            if (rc != VK_SUCCESS) {
                LOG.error("vkCreateAccelerationStructureKHR (BLAS) failed: {}", rc);
                allocator.freeBuffer(asBuf[0]);
                allocator.freeBuffer(scratchBuf[0]);
                return null;
            }
            long blasHandle = pBlas.get(0);

            // Submit build
            long scratchAddr = getBufferDeviceAddress(scratchBuf[0]);
            buildInfo
                .dstAccelerationStructure(blasHandle)
                .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR);
            buildInfo.scratchData().deviceAddress(scratchAddr);

            VkAccelerationStructureBuildRangeInfoKHR.Buffer ranges =
                VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack);
            ranges.get(0)
                .primitiveCount(triangleCount)
                .primitiveOffset(0)
                .firstVertex(0)
                .transformOffset(0);

            submitOneTimeBuild(stack, cmd -> {
                PointerBuffer ppRanges = stack.pointers(ranges);
                VkAccelerationStructureBuildGeometryInfoKHR.Buffer infoBuf =
                    VkAccelerationStructureBuildGeometryInfoKHR.create(
                        buildInfo.address(), 1);
                vkCmdBuildAccelerationStructuresKHR(cmd, infoBuf, ppRanges);
            });

            // Scratch 用畢釋放
            allocator.freeBuffer(scratchBuf[0]);

            // Device address
            VkAccelerationStructureDeviceAddressInfoKHR addrInfo =
                VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_DEVICE_ADDRESS_INFO_KHR)
                    .accelerationStructure(blasHandle);
            long blasAddr = vkGetAccelerationStructureDeviceAddressKHR(device, addrInfo);

            return new long[]{ blasHandle, asBuf[0], asBuf[1], blasAddr };
        }
    }

    // ═══ 私有輔助：TLAS resource 管理 ═══

    private void ensureTLASBuffer(long requiredSize) {
        if (tlasBuffer != 0) {
            allocator.freeBuffer(tlasBuffer);
        }
        long[] buf = allocator.allocateDeviceBuffer(requiredSize,
            VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR |
            VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT);
        if (buf == null) throw new RuntimeException("TLAS buffer allocation failed");
        tlasBuffer = buf[0]; tlasAllocation = buf[1];
    }

    private void ensureTLASScratch(long requiredSize) {
        if (tlasScratch != 0) {
            allocator.freeBuffer(tlasScratch);
        }
        long[] buf = allocator.allocateDeviceBuffer(requiredSize,
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT |
            VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT);
        if (buf == null) throw new RuntimeException("TLAS scratch allocation failed");
        tlasScratch = buf[0]; tlasScratchAlloc = buf[1];
    }

    private void ensureTLASInstanceBuffer(long requiredSize) {
        if (tlasInstanceBuffer != 0) {
            allocator.freeBuffer(tlasInstanceBuffer);
        }
        long[] buf = allocator.allocateDeviceBuffer(requiredSize,
            VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR |
            VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT |
            VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        if (buf == null) throw new RuntimeException("TLAS instance buffer allocation failed");
        tlasInstanceBuffer = buf[0]; tlasInstanceAlloc = buf[1];
    }

    /** 將 ByteBuffer 內容透過 staging 上傳到 device buffer */
    private void uploadToBuffer(long dstBuffer, long dstAlloc,
                                 ByteBuffer data, long byteSize) {
        long[] staging = allocator.allocateStagingBuffer(byteSize);
        if (staging == null) return;

        // 寫入 staging
        byte[] bytes = new byte[(int) byteSize];
        data.get(0, bytes);
        allocator.writeToBuffer(staging[1], bytes);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            long stagingHandle = staging[0];
            submitOneTimeBuild(stack, cmd -> {
                VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack);
                region.get(0).size(byteSize);
                vkCmdCopyBuffer(cmd, stagingHandle, dstBuffer, region);
            });
        }
        allocator.freeBuffer(staging[0]);
    }

    // ═══ 私有輔助：device address ═══

    private long getBufferDeviceAddress(long buffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferDeviceAddressInfo info = VkBufferDeviceAddressInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_DEVICE_ADDRESS_INFO)
                .buffer(buffer);
            return vkGetBufferDeviceAddress(context.getDevice(), info);
        }
    }

    // ═══ 私有輔助：one-time command buffer ═══

    @FunctionalInterface
    private interface CmdRecorder {
        void record(VkCommandBuffer cmd);
    }

    /**
     * 分配 command buffer、錄製、提交、等待完成、釋放。
     * 適用於 BLAS build 等一次性 GPU 操作。
     */
    private void submitOneTimeBuild(MemoryStack stack, CmdRecorder recorder) {
        VkDevice device = context.getDevice();
        long pool = context.getCommandPool();

        // 分配 command buffer
        VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
            .commandPool(pool)
            .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
            .commandBufferCount(1);

        PointerBuffer pCmd = stack.mallocPointer(1);
        if (vkAllocateCommandBuffers(device, allocInfo, pCmd) != VK_SUCCESS) {
            throw new RuntimeException("vkAllocateCommandBuffers failed");
        }
        VkCommandBuffer cmd = new VkCommandBuffer(pCmd.get(0), device);

        // 開始錄製
        VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
            .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
        vkBeginCommandBuffer(cmd, beginInfo);

        // 錄製操作
        recorder.record(cmd);

        vkEndCommandBuffer(cmd);

        // 建立 fence 等待完成
        VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);
        LongBuffer pFence = stack.mallocLong(1);
        vkCreateFence(device, fenceInfo, null, pFence);
        long fence = pFence.get(0);

        // 提交
        VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
            .pCommandBuffers(pCmd);
        vkQueueSubmit(context.getGraphicsQueue(), submitInfo, fence);

        // 等待完成
        vkWaitForFences(device, pFence, true, Long.MAX_VALUE);
        vkDestroyFence(device, fence, null);

        // 釋放 command buffer
        vkFreeCommandBuffers(device, pool, pCmd);
    }

    // ═══ 釋放 ═══

    /**
     * 釋放所有加速結構資源。
     */
    public void cleanup() {
        VkDevice device = context.getDevice();
        if (device == null) return;

        // 釋放所有 BLAS
        blasMap.forEach((key, entry) -> {
            vkDestroyAccelerationStructureKHR(device, entry.handle, null);
            allocator.freeBuffer(entry.buffer);
        });
        blasMap.clear();

        // 釋放 TLAS
        if (tlasHandle != 0) {
            vkDestroyAccelerationStructureKHR(device, tlasHandle, null);
            tlasHandle = 0;
        }
        if (tlasBuffer != 0) { allocator.freeBuffer(tlasBuffer); tlasBuffer = 0; }
        if (tlasScratch != 0) { allocator.freeBuffer(tlasScratch); tlasScratch = 0; }
        if (tlasInstanceBuffer != 0) {
            allocator.freeBuffer(tlasInstanceBuffer);
            tlasInstanceBuffer = 0;
        }

        LOG.info("VkAccelStructBuilder cleanup complete — {} BLAS freed", 0);
    }

    // ═══ 查詢 ═══

    public int  getBLASCount()       { return blasMap.size(); }
    public long getTLASHandle()      { return tlasHandle; }
    public boolean hasBLAS(long key) { return blasMap.containsKey(key); }
    public long getBLASAddress(long chunkKey) {
        BLASEntry e = blasMap.get(chunkKey);
        return e != null ? e.deviceAddress : 0;
    }
}
