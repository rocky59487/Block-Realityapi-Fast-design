package com.blockreality.api.physics.fluid;

import com.blockreality.api.physics.pfsf.VulkanComputeContext;
import net.minecraft.core.BlockPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import static org.lwjgl.vulkan.VK10.*;

/**
 * 流體區域 GPU 緩衝包裝 — 每個活動流體區域一個實例。
 *
 * <p>管理 Vulkan SSBO (Shader Storage Buffer Object) 的生命週期，
 * 包括分配、上傳、讀回和釋放。遵循 {@code PFSFIslandBuffer} 的
 * 雙緩衝 + 引用計數模式。
 *
 * <h3>Buffer Layout (SoA, flat 3D)</h3>
 * <pre>
 * -- block-level buffers (size = N = Lx*Ly*Lz) --
 * phiBufA[]       float32[N]      流體勢能（當前幀）
 * phiBufB[]       float32[N]      流體勢能（前一幀，雙緩衝）
 * densityBuf[]    float32[N]      密度 (kg/m³)
 * volumeBuf[]     float32[N]      體積分率 [0,1]
 * typeBuf[]       uint8[N]        FluidType ID
 * pressureBuf[]   float32[N]      靜水壓 (Pa, Jacobi 路徑)
 * boundaryBuf[]   float32[N]      邊界壓力（供結構耦合讀回）
 * stagingBuf[]    byte[subN*4]    CPU↔GPU 傳輸暫存
 *
 * -- sub-cell buffers (size = subN = Lx*10 * Ly*10 * Lz*10) --
 * vxBuf[]         float32[subN]   速度 X (m/s)
 * vyBuf[]         float32[subN]   速度 Y (m/s)
 * vzBuf[]         float32[subN]   速度 Z (m/s)
 * divergenceBuf[] float32[subN]   速度場散度（Poisson 求解中間結果）
 * </pre>
 */
public class FluidRegionBuffer {

    private static final Logger LOGGER = LogManager.getLogger("BR-FluidBuf");

    private final int regionId;
    private int Lx, Ly, Lz;
    private BlockPos origin;
    private int N;    // block-level voxels = Lx * Ly * Lz
    private int subN; // sub-cell voxels   = Lx*10 * Ly*10 * Lz*10

    // ─── GPU Buffer Handles (VMA allocation pairs: [buffer, allocation]) ───
    // block-level
    private long[] phiBufA;         // 當前幀勢能
    private long[] phiBufB;         // 前一幀勢能（雙緩衝）
    private long[] densityBuf;
    private long[] volumeBuf;
    private long[] typeBuf;
    private long[] pressureBuf;     // Jacobi 路徑壓力（block-level）
    private long[] boundaryBuf;     // 邊界壓力（CPU 讀回用）
    private long[] stagingBuf;      // CPU↔GPU 暫存

    // sub-cell (NS / ML 路徑，0.1m 解析度)
    private long[] vxBuf;           // 速度 X (m/s)，大小 subN
    private long[] vyBuf;           // 速度 Y (m/s)，大小 subN
    private long[] vzBuf;           // 速度 Z (m/s)，大小 subN
    private long[] divergenceBuf;   // ∇·u（Poisson 中間結果），大小 subN

    private boolean allocated = false;
    private volatile boolean dirty = true;

    // 引用計數（非同步安全，照 PFSFIslandBuffer A4-fix 模式）
    private final AtomicInteger refCount = new AtomicInteger(1);

    // 收斂追蹤
    private float maxPhiPrev = 0f;
    private float maxPhiPrevPrev = 0f;

    public FluidRegionBuffer(int regionId) {
        this.regionId = regionId;
    }

    /**
     * 分配所有 GPU 緩衝。
     *
     * <p>使用 {@code VulkanComputeContext} 在 VRAM 中分配緩衝。
     * 呼叫 {@link #free()} 釋放。
     */
    public void allocate(int Lx, int Ly, int Lz, BlockPos origin) {
        this.Lx = Lx;
        this.Ly = Ly;
        this.Lz = Lz;
        this.origin = origin;
        this.N = Lx * Ly * Lz;
        this.subN = (Lx * FluidRegion.SUB) * (Ly * FluidRegion.SUB) * (Lz * FluidRegion.SUB);

        // 若 Vulkan 不可用則跳過（CPU 路徑仍可運行）
        if (!VulkanComputeContext.isAvailable()) {
            this.allocated = true;  // CPU-only 模式：handles 皆為 0
            LOGGER.debug("[BR-FluidBuf] CPU-only mode for region #{}: {}×{}×{} = {} voxels",
                regionId, Lx, Ly, Lz, N);
            return;
        }

        int FLUID = VulkanComputeContext.PARTITION_FLUID;
        int storageUsage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        int readbackUsage = storageUsage | VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
        long floatN    = (long) N * Float.BYTES;
        long byteN     = (long) N;
        long floatSubN = (long) subN * Float.BYTES;

        phiBufA     = VulkanComputeContext.allocateDeviceBuffer(floatN,    storageUsage, FLUID);
        phiBufB     = VulkanComputeContext.allocateDeviceBuffer(floatN,    storageUsage, FLUID);
        densityBuf  = VulkanComputeContext.allocateDeviceBuffer(floatN,    storageUsage, FLUID);
        volumeBuf   = VulkanComputeContext.allocateDeviceBuffer(floatN,    storageUsage, FLUID);
        typeBuf     = VulkanComputeContext.allocateDeviceBuffer(byteN,     storageUsage, FLUID);
        pressureBuf = VulkanComputeContext.allocateDeviceBuffer(floatN,    storageUsage, FLUID);
        boundaryBuf = VulkanComputeContext.allocateDeviceBuffer(floatN,    readbackUsage, FLUID);
        vxBuf       = VulkanComputeContext.allocateDeviceBuffer(floatSubN, storageUsage, FLUID);
        vyBuf       = VulkanComputeContext.allocateDeviceBuffer(floatSubN, storageUsage, FLUID);
        vzBuf       = VulkanComputeContext.allocateDeviceBuffer(floatSubN, storageUsage, FLUID);
        divergenceBuf = VulkanComputeContext.allocateDeviceBuffer(floatSubN, storageUsage, FLUID);
        stagingBuf  = VulkanComputeContext.allocateStagingBuffer(floatSubN); // 最大為 sub-cell

        // 任一分配失敗則全部回滾
        long[][] bufs = {phiBufA, phiBufB, densityBuf, volumeBuf, typeBuf, pressureBuf,
                         boundaryBuf, vxBuf, vyBuf, vzBuf, divergenceBuf, stagingBuf};
        for (long[] b : bufs) {
            if (b == null) {
                LOGGER.error("[BR-FluidBuf] Region #{} VMA allocation failed; rolling back", regionId);
                free();
                return;
            }
        }

        this.allocated = true;
        LOGGER.debug("[BR-FluidBuf] Region #{}: {}×{}×{} N={} subN={}, VRAM≈{} KB",
            regionId, Lx, Ly, Lz, N, subN, estimateVRAMBytes() / 1024);
    }

    /**
     * 釋放所有 GPU 緩衝。
     */
    public void free() {
        if (!allocated) return;
        if (VulkanComputeContext.isAvailable()) {
            long[][] bufs = {phiBufA, phiBufB, densityBuf, volumeBuf, typeBuf,
                             pressureBuf, boundaryBuf, vxBuf, vyBuf, vzBuf, divergenceBuf, stagingBuf};
            for (long[] b : bufs) {
                if (b != null && b.length >= 2 && b[0] != 0)
                    VulkanComputeContext.freeBuffer(b[0], b[1]);
            }
        }
        phiBufA = phiBufB = densityBuf = volumeBuf = null;
        typeBuf = pressureBuf = boundaryBuf = stagingBuf = null;
        vxBuf = vyBuf = vzBuf = divergenceBuf = null;
        allocated = false;
        LOGGER.debug("[BR-FluidBuf] Freed region #{}", regionId);
    }

    /**
     * O(1) phi 指標交換（雙緩衝）。
     */
    public void swapPhi() {
        long[] tmp = phiBufA;
        phiBufA = phiBufB;
        phiBufB = tmp;
    }

    /**
     * 從 CPU FluidRegion 上傳資料到 GPU 緩衝（透過 staging buffer）。
     */
    public void uploadFromCPU(FluidRegion region) {
        if (!allocated || !VulkanComputeContext.isAvailable()) { dirty = false; return; }
        if (stagingBuf == null || stagingBuf[0] == 0) return;

        long floatN = (long) N * Float.BYTES;
        long floatSubN = (long) subN * Float.BYTES;

        uploadFloatArray(region.getPhi(),      floatN, phiBufA,     stagingBuf, floatSubN);
        uploadFloatArray(region.getDensity(),  floatN, densityBuf,  stagingBuf, floatSubN);
        uploadFloatArray(region.getVolume(),   floatN, volumeBuf,   stagingBuf, floatSubN);
        uploadFloatArray(region.getPressure(), floatN, pressureBuf, stagingBuf, floatSubN);
        uploadByteArray(region.getType(), N, typeBuf, stagingBuf);
        dirty = false;
    }

    private void uploadFloatArray(float[] src, long byteSize, long[] destBuf,
                                   long[] staging, long stagingCapacity) {
        if (src == null || destBuf == null || destBuf[0] == 0) return;
        ByteBuffer mapped = VulkanComputeContext.mapBuffer(staging[1], byteSize);
        if (mapped == null) return;
        mapped.asFloatBuffer().put(src, 0, (int)(byteSize / Float.BYTES));
        VulkanComputeContext.unmapBuffer(staging[1]);
        VkCommandBuffer cmd = VulkanComputeContext.beginSingleTimeCommands();
        if (cmd == null) return;
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VkBufferCopy.Buffer copy = org.lwjgl.vulkan.VkBufferCopy.calloc(1, stack)
                .srcOffset(0).dstOffset(0).size(byteSize);
            vkCmdCopyBuffer(cmd, staging[0], destBuf[0], copy);
        }
        VulkanComputeContext.endSingleTimeCommands(cmd);
    }

    private void uploadByteArray(byte[] src, int count, long[] destBuf, long[] staging) {
        if (src == null || destBuf == null || destBuf[0] == 0) return;
        ByteBuffer mapped = VulkanComputeContext.mapBuffer(staging[1], count);
        if (mapped == null) return;
        mapped.put(src, 0, count);
        VulkanComputeContext.unmapBuffer(staging[1]);
        VkCommandBuffer cmd = VulkanComputeContext.beginSingleTimeCommands();
        if (cmd == null) return;
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VkBufferCopy.Buffer copy = org.lwjgl.vulkan.VkBufferCopy.calloc(1, stack)
                .srcOffset(0).dstOffset(0).size(count);
            vkCmdCopyBuffer(cmd, staging[0], destBuf[0], copy);
        }
        VulkanComputeContext.endSingleTimeCommands(cmd);
    }

    /**
     * 讀回邊界壓力到 CPU（同步，透過 staging buffer）。
     */
    public void asyncReadBoundaryPressure(java.util.function.Consumer<float[]> callback) {
        if (!allocated || !VulkanComputeContext.isAvailable()) return;
        if (boundaryBuf == null || boundaryBuf[0] == 0 || stagingBuf == null) return;

        long byteSize = (long) N * Float.BYTES;
        // GPU compute → staging transfer
        VkCommandBuffer cmd = VulkanComputeContext.beginSingleTimeCommands();
        if (cmd == null) return;
        VulkanComputeContext.computeToTransferBarrier(cmd);
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VkBufferCopy.Buffer copy = org.lwjgl.vulkan.VkBufferCopy.calloc(1, stack)
                .srcOffset(0).dstOffset(0).size(byteSize);
            vkCmdCopyBuffer(cmd, boundaryBuf[0], stagingBuf[0], copy);
        }
        VulkanComputeContext.endSingleTimeCommands(cmd);  // 同步等待完成
        // staging → float[]
        ByteBuffer mapped = VulkanComputeContext.mapBuffer(stagingBuf[1], byteSize);
        if (mapped == null) return;
        float[] result = new float[N];
        mapped.asFloatBuffer().get(result);
        VulkanComputeContext.unmapBuffer(stagingBuf[1]);
        callback.accept(result);
    }

    // ─── 引用計數（A4-fix 模式） ───

    public void retain() {
        refCount.incrementAndGet();
    }

    public boolean release() {
        int count = refCount.decrementAndGet();
        if (count <= 0) {
            free();
            return true;
        }
        return false;
    }

    // ─── 收斂追蹤 ───

    public void updateMaxPhi(float maxPhiNow) {
        maxPhiPrevPrev = maxPhiPrev;
        maxPhiPrev = maxPhiNow;
    }

    public float getMaxPhiPrev() { return maxPhiPrev; }
    public float getMaxPhiPrevPrev() { return maxPhiPrevPrev; }

    // ─── Getters ───

    public int getRegionId() { return regionId; }
    public int getLx() { return Lx; }
    public int getLy() { return Ly; }
    public int getLz() { return Lz; }
    public int getN() { return N; }
    public BlockPos getOrigin() { return origin; }
    public boolean isAllocated() { return allocated; }
    public boolean isDirty() { return dirty; }
    public void markDirty() { dirty = true; }
    public void markClean() { dirty = false; }

    public long[] getPhiBufA() { return phiBufA; }
    public long[] getPhiBufB() { return phiBufB; }
    public long[] getDensityBuf() { return densityBuf; }
    public long[] getVolumeBuf() { return volumeBuf; }
    public long[] getTypeBuf() { return typeBuf; }
    public long[] getPressureBuf() { return pressureBuf; }
    public long[] getBoundaryBuf() { return boundaryBuf; }
    public long[] getStagingBuf() { return stagingBuf; }

    // sub-cell buffer getters
    public long[] getVxBuf() { return vxBuf; }
    public long[] getVyBuf() { return vyBuf; }
    public long[] getVzBuf() { return vzBuf; }
    public long[] getDivergenceBuf() { return divergenceBuf; }
    public int getSubN() { return subN; }

    /** 估算 VRAM 使用量（bytes） */
    public long estimateVRAMBytes() {
        // block-level: phi×2 + density + volume + pressure + boundary + staging = 7N×4 + N bytes
        // sub-cell: vx + vy + vz + divergence = 4×subN×4 bytes
        return (long) N * (7 * 4 + 1) + (long) subN * 4 * 4;
    }
}
