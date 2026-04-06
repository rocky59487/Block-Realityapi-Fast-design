package com.blockreality.api.physics.fluid;

import net.minecraft.core.BlockPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 流體區域 GPU 緩衝包裝 — 每個活動流體區域一個實例。
 *
 * <p>管理 Vulkan SSBO (Shader Storage Buffer Object) 的生命週期，
 * 包括分配、上傳、讀回和釋放。遵循 {@code PFSFIslandBuffer} 的
 * 雙緩衝 + 引用計數模式。
 *
 * <h3>Buffer Layout (SoA, flat 3D, Morton-tiled 8×8×8)</h3>
 * <pre>
 * phiBufA[]       float32[N]   流體勢能（當前幀）
 * phiBufB[]       float32[N]   流體勢能（前一幀，雙緩衝）
 * densityBuf[]    float32[N]   密度 (kg/m³)
 * volumeBuf[]     float32[N]   體積分率 [0,1]
 * typeBuf[]       uint8[N]     FluidType ID
 * pressureBuf[]   float32[N]   靜水壓 (Pa)
 * velocityBuf[]   float32[3N]  導出速度場 (vx, vy, vz)
 * boundaryBuf[]   float32[N]   邊界壓力（供結構耦合讀回）
 * stagingBuf[]    byte[N*4]    CPU↔GPU 傳輸暫存
 * </pre>
 */
public class FluidRegionBuffer {

    private static final Logger LOGGER = LogManager.getLogger("BR-FluidBuf");

    private final int regionId;
    private int Lx, Ly, Lz;
    private BlockPos origin;
    private int N; // totalVoxels = Lx * Ly * Lz

    // ─── GPU Buffer Handles (VMA allocation pairs: [buffer, allocation]) ───
    private long[] phiBufA;         // 當前幀勢能
    private long[] phiBufB;         // 前一幀勢能（雙緩衝）
    private long[] densityBuf;
    private long[] volumeBuf;
    private long[] typeBuf;
    private long[] pressureBuf;
    private long[] velocityBuf;     // 3N floats
    private long[] boundaryBuf;     // 邊界壓力（CPU 讀回用）
    private long[] stagingBuf;      // CPU↔GPU 暫存

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

        // 在實際 Vulkan 實作中，此處呼叫 VulkanComputeContext.allocateBuffer()
        // 目前為結構佔位，GPU buffer handle 為 null 直到 Vulkan 初始化
        this.phiBufA = new long[2];
        this.phiBufB = new long[2];
        this.densityBuf = new long[2];
        this.volumeBuf = new long[2];
        this.typeBuf = new long[2];
        this.pressureBuf = new long[2];
        this.velocityBuf = new long[2];  // 3N floats
        this.boundaryBuf = new long[2];
        this.stagingBuf = new long[2];

        this.allocated = true;
        LOGGER.debug("[BR-FluidBuf] Allocated region #{}: {}×{}×{} = {} voxels",
            regionId, Lx, Ly, Lz, N);
    }

    /**
     * 釋放所有 GPU 緩衝。
     */
    public void free() {
        if (!allocated) return;
        // 在實際 Vulkan 實作中，此處呼叫 VulkanComputeContext.freeBuffer()
        phiBufA = phiBufB = densityBuf = volumeBuf = null;
        typeBuf = pressureBuf = velocityBuf = boundaryBuf = stagingBuf = null;
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
     * 從 CPU FluidRegion 上傳資料到 GPU 緩衝。
     */
    public void uploadFromCPU(FluidRegion region) {
        // 實際實作：透過 staging buffer 上傳 SoA 資料到 GPU
        // phi[], density[], volume[], type[], pressure[]
        dirty = false;
    }

    /**
     * 非同步讀回邊界壓力到 CPU。
     */
    public void asyncReadBoundaryPressure(java.util.function.Consumer<float[]> callback) {
        // 實際實作：Map staging buffer，讀取 boundaryBuf 資料，呼叫 callback
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
    public long[] getVelocityBuf() { return velocityBuf; }
    public long[] getBoundaryBuf() { return boundaryBuf; }
    public long[] getStagingBuf() { return stagingBuf; }

    /** 估算 VRAM 使用量（bytes） */
    public long estimateVRAMBytes() {
        // phi×2 + density + volume + pressure + boundary = 6N×4 bytes
        // type = N bytes, velocity = 3N×4 bytes, staging = N×4 bytes
        return (long) N * (6 * 4 + 1 + 3 * 4 + 4);
    }
}
