package com.blockreality.api.physics.pfsf;

import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.function.Consumer;

import static com.blockreality.api.physics.pfsf.PFSFConstants.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * 每個 Structure Island 的 GPU 緩衝區包裝。
 *
 * <p>VRAM Layout（per island, flat 3D array index = x + Lx*(y + Ly*z)）：</p>
 * <pre>
 *   phi[]           float32[N]   勢能場
 *   phi_prev[]      float32[N]   Chebyshev t-1 幀
 *   source[]        float32[N]   自重 ρ_i
 *   conductivity[]  float32[6N]  6 向傳導率 σ_ij
 *   type[]          uint8[N]     0=air 1=solid 2=anchor
 *   fail_flags[]    uint8[N]     0=OK 1=cantilever 2=crush 3=orphan
 *   maxPhi[]        float32[N]   per-voxel 材料極限
 *   rcomp[]         float32[N]   per-voxel 抗壓強度 (MPa)
 * </pre>
 */
public class PFSFIslandBuffer {

    private static final Logger LOGGER = LoggerFactory.getLogger("PFSF-Buffer");

    private final int islandId;

    // ─── Grid dimensions ───
    private int Lx, Ly, Lz;
    private BlockPos origin; // AABB 最小角

    // ─── GPU buffer handles [bufferHandle, allocationHandle] ───
    private long[] phiBuf;
    private long[] phiPrevBuf;
    private long[] sourceBuf;
    private long[] conductivityBuf;
    private long[] typeBuf;
    private long[] failFlagsBuf;
    private long[] maxPhiBuf;
    private long[] rcompBuf;

    // ─── Staging buffer for CPU↔GPU transfer ───
    private long[] stagingBuf;
    private long stagingSize;

    // ─── Multigrid coarse levels ───
    private int Lx_L1, Ly_L1, Lz_L1;
    private int Lx_L2, Ly_L2, Lz_L2;
    private long[] phiL1Buf, phiPrevL1Buf, sourceL1Buf, conductivityL1Buf, typeL1Buf;
    private long[] phiL2Buf, phiPrevL2Buf, sourceL2Buf, conductivityL2Buf, typeL2Buf;
    private boolean multigridAllocated = false;

    // ─── State ───
    private boolean dirty = true;
    private boolean allocated = false;
    int chebyshevIter = 0;
    float rhoSpecOverride;
    float maxPhiPrev = 0;

    public PFSFIslandBuffer(int islandId) {
        this.islandId = islandId;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Allocation
    // ═══════════════════════════════════════════════════════════════

    /**
     * 分配所有 GPU buffer。
     *
     * @param Lx 網格 X 尺寸
     * @param Ly 網格 Y 尺寸
     * @param Lz 網格 Z 尺寸
     * @param origin AABB 最小角（世界座標）
     */
    public void allocate(int Lx, int Ly, int Lz, BlockPos origin) {
        if (allocated) free();

        this.Lx = Lx;
        this.Ly = Ly;
        this.Lz = Lz;
        this.origin = origin;

        int N = Lx * Ly * Lz;
        long floatN = (long) N * Float.BYTES;
        long float6N = (long) N * 6 * Float.BYTES;
        long byteN = N;

        int storageUsage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                | VK_BUFFER_USAGE_TRANSFER_SRC_BIT
                | VK_BUFFER_USAGE_TRANSFER_DST_BIT;

        phiBuf = VulkanComputeContext.allocateDeviceBuffer(floatN, storageUsage);
        phiPrevBuf = VulkanComputeContext.allocateDeviceBuffer(floatN, storageUsage);
        sourceBuf = VulkanComputeContext.allocateDeviceBuffer(floatN, storageUsage);
        conductivityBuf = VulkanComputeContext.allocateDeviceBuffer(float6N, storageUsage);
        typeBuf = VulkanComputeContext.allocateDeviceBuffer(byteN, storageUsage);
        failFlagsBuf = VulkanComputeContext.allocateDeviceBuffer(byteN, storageUsage);
        maxPhiBuf = VulkanComputeContext.allocateDeviceBuffer(floatN, storageUsage);
        rcompBuf = VulkanComputeContext.allocateDeviceBuffer(floatN, storageUsage);

        // Staging: 足夠容納最大的 buffer（conductivity = 6N floats）
        stagingSize = float6N;
        stagingBuf = VulkanComputeContext.allocateStagingBuffer(stagingSize);

        // 初始化 Chebyshev 參數
        rhoSpecOverride = PFSFScheduler.estimateSpectralRadius(getLmax());

        allocated = true;
        LOGGER.debug("[PFSF] Island {} allocated: {}×{}×{} = {} voxels, VRAM ≈ {} KB",
                islandId, Lx, Ly, Lz, N, (floatN * 4 + float6N + byteN * 2) / 1024);
    }

    /**
     * 分配多重網格粗網格 buffer。
     */
    public void allocateMultigrid() {
        if (multigridAllocated) return;

        Lx_L1 = ceilDiv(Lx, 2);
        Ly_L1 = ceilDiv(Ly, 2);
        Lz_L1 = ceilDiv(Lz, 2);

        Lx_L2 = ceilDiv(Lx_L1, 2);
        Ly_L2 = ceilDiv(Ly_L1, 2);
        Lz_L2 = ceilDiv(Lz_L1, 2);

        int N1 = Lx_L1 * Ly_L1 * Lz_L1;
        int N2 = Lx_L2 * Ly_L2 * Lz_L2;

        int storageUsage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                | VK_BUFFER_USAGE_TRANSFER_SRC_BIT
                | VK_BUFFER_USAGE_TRANSFER_DST_BIT;

        phiL1Buf = VulkanComputeContext.allocateDeviceBuffer((long) N1 * Float.BYTES, storageUsage);
        phiPrevL1Buf = VulkanComputeContext.allocateDeviceBuffer((long) N1 * Float.BYTES, storageUsage);
        sourceL1Buf = VulkanComputeContext.allocateDeviceBuffer((long) N1 * Float.BYTES, storageUsage);
        conductivityL1Buf = VulkanComputeContext.allocateDeviceBuffer((long) N1 * 6 * Float.BYTES, storageUsage);
        typeL1Buf = VulkanComputeContext.allocateDeviceBuffer(N1, storageUsage);

        phiL2Buf = VulkanComputeContext.allocateDeviceBuffer((long) N2 * Float.BYTES, storageUsage);
        phiPrevL2Buf = VulkanComputeContext.allocateDeviceBuffer((long) N2 * Float.BYTES, storageUsage);
        sourceL2Buf = VulkanComputeContext.allocateDeviceBuffer((long) N2 * Float.BYTES, storageUsage);
        conductivityL2Buf = VulkanComputeContext.allocateDeviceBuffer((long) N2 * 6 * Float.BYTES, storageUsage);
        typeL2Buf = VulkanComputeContext.allocateDeviceBuffer(N2, storageUsage);

        multigridAllocated = true;
    }

    /**
     * 釋放所有 GPU buffer。
     */
    public void free() {
        freeBufferPair(phiBuf);
        freeBufferPair(phiPrevBuf);
        freeBufferPair(sourceBuf);
        freeBufferPair(conductivityBuf);
        freeBufferPair(typeBuf);
        freeBufferPair(failFlagsBuf);
        freeBufferPair(maxPhiBuf);
        freeBufferPair(rcompBuf);
        freeBufferPair(stagingBuf);

        freeMultigrid();

        allocated = false;
    }

    public void freeMultigrid() {
        if (!multigridAllocated) return;
        freeBufferPair(phiL1Buf);
        freeBufferPair(phiPrevL1Buf);
        freeBufferPair(sourceL1Buf);
        freeBufferPair(conductivityL1Buf);
        freeBufferPair(typeL1Buf);
        freeBufferPair(phiL2Buf);
        freeBufferPair(phiPrevL2Buf);
        freeBufferPair(sourceL2Buf);
        freeBufferPair(conductivityL2Buf);
        freeBufferPair(typeL2Buf);
        multigridAllocated = false;
    }

    private void freeBufferPair(long[] pair) {
        if (pair != null && pair.length == 2) {
            VulkanComputeContext.freeBuffer(pair[0], pair[1]);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  CPU → GPU Upload
    // ═══════════════════════════════════════════════════════════════

    /**
     * 上傳源項、傳導率、類型、maxPhi、Rcomp 到 GPU。
     */
    public void uploadSourceAndConductivity(float[] source, float[] conductivity,
                                             byte[] type, float[] maxPhi, float[] rcomp) {
        if (!allocated) throw new IllegalStateException("Buffer not allocated");

        uploadFloatBuffer(sourceBuf, source);
        uploadFloatBuffer(conductivityBuf, conductivity);
        uploadByteBuffer(typeBuf, type);
        uploadFloatBuffer(maxPhiBuf, maxPhi);
        uploadFloatBuffer(rcompBuf, rcomp);
    }

    /**
     * 上傳粗網格 (L1) 的 conductivity 和 type 到 GPU。
     * 由 PFSFEngine.uploadCoarseGridData() 呼叫。
     */
    public void uploadCoarseData(float[] coarseCond, byte[] coarseType) {
        if (!multigridAllocated) return;
        uploadFloatBuffer(conductivityL1Buf, coarseCond);
        uploadByteBuffer(typeL1Buf, coarseType);
    }

    private void uploadFloatBuffer(long[] deviceBuf, float[] data) {
        long size = (long) data.length * Float.BYTES;
        ByteBuffer mapped = VulkanComputeContext.mapBuffer(stagingBuf[1]);
        FloatBuffer fb = mapped.asFloatBuffer();
        fb.put(data);
        VulkanComputeContext.unmapBuffer(stagingBuf[1]);

        // Copy staging → device via command buffer
        var cmdBuf = VulkanComputeContext.beginSingleTimeCommands();
        org.lwjgl.vulkan.VkBufferCopy.Buffer region = org.lwjgl.vulkan.VkBufferCopy.calloc(1)
                .srcOffset(0)
                .dstOffset(0)
                .size(size);
        org.lwjgl.vulkan.VK10.vkCmdCopyBuffer(cmdBuf, stagingBuf[0], deviceBuf[0], region);
        region.free();
        VulkanComputeContext.endSingleTimeCommands(cmdBuf);
    }

    private void uploadByteBuffer(long[] deviceBuf, byte[] data) {
        ByteBuffer mapped = VulkanComputeContext.mapBuffer(stagingBuf[1]);
        mapped.put(data);
        VulkanComputeContext.unmapBuffer(stagingBuf[1]);

        var cmdBuf = VulkanComputeContext.beginSingleTimeCommands();
        org.lwjgl.vulkan.VkBufferCopy.Buffer region = org.lwjgl.vulkan.VkBufferCopy.calloc(1)
                .srcOffset(0)
                .dstOffset(0)
                .size(data.length);
        org.lwjgl.vulkan.VK10.vkCmdCopyBuffer(cmdBuf, stagingBuf[0], deviceBuf[0], region);
        region.free();
        VulkanComputeContext.endSingleTimeCommands(cmdBuf);
    }

    // ═══════════════════════════════════════════════════════════════
    //  GPU → CPU Readback
    // ═══════════════════════════════════════════════════════════════

    /**
     * 非同步讀回 fail_flags[]。
     * （當前實作為同步讀回；後續可改為 fence-based 非同步）
     */
    public void asyncReadFailFlags(Consumer<byte[]> callback) {
        int N = getN();
        var cmdBuf = VulkanComputeContext.beginSingleTimeCommands();
        org.lwjgl.vulkan.VkBufferCopy.Buffer region = org.lwjgl.vulkan.VkBufferCopy.calloc(1)
                .srcOffset(0).dstOffset(0).size(N);
        org.lwjgl.vulkan.VK10.vkCmdCopyBuffer(cmdBuf, failFlagsBuf[0], stagingBuf[0], region);
        region.free();
        VulkanComputeContext.endSingleTimeCommands(cmdBuf);

        ByteBuffer mapped = VulkanComputeContext.mapBuffer(stagingBuf[1]);
        byte[] result = new byte[N];
        mapped.get(result);
        VulkanComputeContext.unmapBuffer(stagingBuf[1]);

        callback.accept(result);
    }

    /**
     * 讀回 phi[] 中的最大值（用於發散偵測）。
     */
    public float readMaxPhi() {
        int N = getN();
        long size = (long) N * Float.BYTES;

        var cmdBuf = VulkanComputeContext.beginSingleTimeCommands();
        org.lwjgl.vulkan.VkBufferCopy.Buffer region = org.lwjgl.vulkan.VkBufferCopy.calloc(1)
                .srcOffset(0).dstOffset(0).size(size);
        org.lwjgl.vulkan.VK10.vkCmdCopyBuffer(cmdBuf, phiBuf[0], stagingBuf[0], region);
        region.free();
        VulkanComputeContext.endSingleTimeCommands(cmdBuf);

        ByteBuffer mapped = VulkanComputeContext.mapBuffer(stagingBuf[1]);
        FloatBuffer fb = mapped.asFloatBuffer();
        float max = 0;
        for (int i = 0; i < N; i++) {
            max = Math.max(max, fb.get(i));
        }
        VulkanComputeContext.unmapBuffer(stagingBuf[1]);

        return max;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Index Conversion
    // ═══════════════════════════════════════════════════════════════

    /**
     * 世界座標 → 扁平索引。
     */
    public int flatIndex(BlockPos pos) {
        int x = pos.getX() - origin.getX();
        int y = pos.getY() - origin.getY();
        int z = pos.getZ() - origin.getZ();
        return x + Lx * (y + Ly * z);
    }

    /**
     * 扁平索引 → 世界座標。
     */
    public BlockPos fromFlatIndex(int i) {
        int x = i % Lx;
        int remainder = i / Lx;
        int y = remainder % Ly;
        int z = remainder / Ly;
        return new BlockPos(
                x + origin.getX(),
                y + origin.getY(),
                z + origin.getZ());
    }

    /**
     * 檢查位置是否在 AABB 範圍內。
     */
    public boolean contains(BlockPos pos) {
        int x = pos.getX() - origin.getX();
        int y = pos.getY() - origin.getY();
        int z = pos.getZ() - origin.getZ();
        return x >= 0 && x < Lx && y >= 0 && y < Ly && z >= 0 && z < Lz;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Getters
    // ═══════════════════════════════════════════════════════════════

    public int getIslandId() { return islandId; }
    public int getLx() { return Lx; }
    public int getLy() { return Ly; }
    public int getLz() { return Lz; }
    public int getN() { return Lx * Ly * Lz; }
    public int getLmax() { return Math.max(Lx, Math.max(Ly, Lz)); }
    public BlockPos getOrigin() { return origin; }
    public boolean isAllocated() { return allocated; }

    // Multigrid dimensions
    public int getLxL1() { return Lx_L1; }
    public int getLyL1() { return Ly_L1; }
    public int getLzL1() { return Lz_L1; }
    public int getLxL2() { return Lx_L2; }
    public int getLyL2() { return Ly_L2; }
    public int getLzL2() { return Lz_L2; }

    // GPU buffer handles (for descriptor binding)
    public long getPhiBuf() { return phiBuf[0]; }
    public long getPhiPrevBuf() { return phiPrevBuf[0]; }
    public long getSourceBuf() { return sourceBuf[0]; }
    public long getConductivityBuf() { return conductivityBuf[0]; }
    public long getTypeBuf() { return typeBuf[0]; }
    public long getFailFlagsBuf() { return failFlagsBuf[0]; }
    public long getMaxPhiBuf() { return maxPhiBuf[0]; }
    public long getRcompBuf() { return rcompBuf[0]; }
    public long getPhiL1Buf() { return phiL1Buf != null ? phiL1Buf[0] : 0; }
    public long getPhiPrevL1Buf() { return phiPrevL1Buf != null ? phiPrevL1Buf[0] : 0; }
    public long getSourceL1Buf() { return sourceL1Buf != null ? sourceL1Buf[0] : 0; }
    public long getConductivityL1Buf() { return conductivityL1Buf != null ? conductivityL1Buf[0] : 0; }
    public long getTypeL1Buf() { return typeL1Buf != null ? typeL1Buf[0] : 0; }
    public long getPhiL2Buf() { return phiL2Buf != null ? phiL2Buf[0] : 0; }
    public long getPhiPrevL2Buf() { return phiPrevL2Buf != null ? phiPrevL2Buf[0] : 0; }
    public long getSourceL2Buf() { return sourceL2Buf != null ? sourceL2Buf[0] : 0; }
    public long getConductivityL2Buf() { return conductivityL2Buf != null ? conductivityL2Buf[0] : 0; }
    public long getTypeL2Buf() { return typeL2Buf != null ? typeL2Buf[0] : 0; }
    public int getN_L1() { return Lx_L1 * Ly_L1 * Lz_L1; }
    public int getN_L2() { return Lx_L2 * Ly_L2 * Lz_L2; }

    // Buffer sizes (for descriptor range)
    public long getPhiSize() { return (long) getN() * Float.BYTES; }
    public long getConductivitySize() { return (long) getN() * 6 * Float.BYTES; }
    public long getTypeSize() { return getN(); }

    // State
    public boolean isDirty() { return dirty; }
    public void markDirty() { dirty = true; }
    public void markClean() { dirty = false; }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }
}
