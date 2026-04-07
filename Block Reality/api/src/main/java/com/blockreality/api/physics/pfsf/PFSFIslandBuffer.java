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
    // A1-fix: 使用 currentPhi/currentPhiPrev 指標實現邏輯交換
    private long[] phiBufA;       // 實體 buffer A
    private long[] phiBufB;       // 實體 buffer B
    private long[] phiBuf;        // 當前「寫入」指標（指向 A 或 B）
    private long[] phiPrevBuf;    // 當前「讀取」指標（指向另一個）
    private long[] sourceBuf;
    private long[] conductivityBuf;
    private long[] typeBuf;
    private long[] failFlagsBuf;
    private long[] maxPhiBuf;
    private long[] rcompBuf;
    private long[] rtensBuf;  // 各向異性：抗拉強度（MPa），用於 TENSION_BREAK

    // ─── v2.1: Morton Tiled Layout ───
    private long[] blockOffsetsBuf; // block_offsets[num_blocks]: micro-block 起始線性偏移

    // ─── Macro-block adaptive skip ───
    private long[] macroBlockResidualBuf; // per-macroblock max residual（GPU 寫入，CPU 讀回）

    // ─── Staging buffer for CPU↔GPU transfer ───
    private long[] stagingBuf;
    private long stagingSize;

    // ─── P1 重構：委託組件 ───
    private final PFSFPhaseFieldBuffers phaseField = new PFSFPhaseFieldBuffers();
    private final PFSFMultigridBuffers multigrid = new PFSFMultigridBuffers();
    private PFSFConvergenceState convergence;

    // ─── State ───
    private boolean dirty = true;
    private boolean allocated = false;

    // ─── P1: 向下相容 — 舊欄位委託給 convergence ───
    // 以下 package-private 欄位保留以相容現有 PFSFScheduler/Engine 的直接存取
    int chebyshevIter = 0;
    float rhoSpecOverride;
    float maxPhiPrev = 0;
    float maxPhiPrevPrev = 0;
    boolean dampingActive = false;

    // A4-fix: 引用計數，防止回調訪問已釋放的 buffer
    private final java.util.concurrent.atomic.AtomicInteger refCount =
            new java.util.concurrent.atomic.AtomicInteger(1);

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

        // A1-fix: 分配兩個實體 phi buffer，用指標交換實現 double-buffering
        phiBufA = VulkanComputeContext.allocateDeviceBuffer(floatN, storageUsage);
        phiBufB = VulkanComputeContext.allocateDeviceBuffer(floatN, storageUsage);
        phiBuf = phiBufA;
        phiPrevBuf = phiBufB;
        sourceBuf = VulkanComputeContext.allocateDeviceBuffer(floatN, storageUsage);
        conductivityBuf = VulkanComputeContext.allocateDeviceBuffer(float6N, storageUsage);
        typeBuf = VulkanComputeContext.allocateDeviceBuffer(byteN, storageUsage);
        failFlagsBuf = VulkanComputeContext.allocateDeviceBuffer(byteN, storageUsage);
        maxPhiBuf = VulkanComputeContext.allocateDeviceBuffer(floatN, storageUsage);
        rcompBuf = VulkanComputeContext.allocateDeviceBuffer(floatN, storageUsage);
        rtensBuf     = VulkanComputeContext.allocateDeviceBuffer(floatN, storageUsage);

        // P1 重構：委託相場 buffer 分配
        phaseField.allocate(N);

        // v2.1: Morton block offsets（預估最多 ceil(N/512) 個 micro-blocks）
        int numBlocks = (N + 511) / 512;
        blockOffsetsBuf = VulkanComputeContext.allocateDeviceBuffer((long) numBlocks * Integer.BYTES, storageUsage);

        // Macro-block adaptive skip: per-macroblock residual（8×8×8 = 512 體素/塊）
        int numMacroBlocks = getNumMacroBlocks();
        macroBlockResidualBuf = VulkanComputeContext.allocateDeviceBuffer(
                (long) numMacroBlocks * Float.BYTES, storageUsage);

        // Staging: 足夠容納最大的 buffer（conductivity = 6N floats）
        stagingSize = float6N;
        stagingBuf = VulkanComputeContext.allocateStagingBuffer(stagingSize);

        // P1 重構：初始化收斂狀態
        convergence = new PFSFConvergenceState(getLmax());
        rhoSpecOverride = convergence.getRhoSpecOverride();

        allocated = true;
        LOGGER.debug("[PFSF] Island {} allocated: {}×{}×{} = {} voxels, VRAM ≈ {} KB",
                islandId, Lx, Ly, Lz, N, (floatN * 4 + float6N + byteN * 2) / 1024);
    }

    /**
     * 分配多重網格粗網格 buffer（P1 重構：委託給 PFSFMultigridBuffers）。
     */
    public void allocateMultigrid() {
        multigrid.allocate(Lx, Ly, Lz);
    }

    /**
     * 釋放所有 GPU buffer。
     */
    public void free() {
        freeBufferPair(phiBufA);
        freeBufferPair(phiBufB);
        freeBufferPair(sourceBuf);
        freeBufferPair(conductivityBuf);
        freeBufferPair(typeBuf);
        freeBufferPair(failFlagsBuf);
        freeBufferPair(maxPhiBuf);
        freeBufferPair(rcompBuf);
        freeBufferPair(rtensBuf);
        freeBufferPair(blockOffsetsBuf);
        freeBufferPair(macroBlockResidualBuf);
        freeBufferPair(stagingBuf);

        // P1 重構：委託組件釋放
        phaseField.free();
        multigrid.free();

        allocated = false;
    }

    public void freeMultigrid() {
        multigrid.free();
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
                                             byte[] type, float[] maxPhi, float[] rcomp,
                                             float[] rtens) {
        if (!allocated) throw new IllegalStateException("Buffer not allocated");

        uploadFloatBuffer(sourceBuf, source);
        uploadFloatBuffer(conductivityBuf, conductivity);
        uploadByteBuffer(typeBuf, type);
        uploadFloatBuffer(maxPhiBuf, maxPhi);
        uploadFloatBuffer(rcompBuf, rcomp);
        uploadFloatBuffer(rtensBuf, rtens);
    }

    /**
     * 上傳水化度陣列到 GPU（v2.1 固化時間效應）。
     * CPU 端由 ICuringManager 計算，每 tick 在 uploadSourceAndConductivity 之前呼叫。
     */
    public void uploadHydration(float[] hydrationDegree) {
        if (!allocated) return;
        uploadFloatBuffer(hydrationBuf, hydrationDegree);
    }

    /**
     * 上傳 Morton block offsets 到 GPU（v2.1 Tiled Morton Layout）。
     * 由 PFSFDataBuilder.buildMortonLayout() 計算後上傳。
     */
    public void uploadBlockOffsets(int[] offsets) {
        if (!allocated || blockOffsetsBuf == null) return;
        long size = (long) offsets.length * Integer.BYTES;
        ByteBuffer mapped = VulkanComputeContext.mapBuffer(stagingBuf[1], stagingSize);
        mapped.asIntBuffer().put(offsets);
        VulkanComputeContext.unmapBuffer(stagingBuf[1]);
        var cmdBuf = VulkanComputeContext.beginSingleTimeCommands();
        org.lwjgl.vulkan.VkBufferCopy.Buffer region = org.lwjgl.vulkan.VkBufferCopy.calloc(1)
                .srcOffset(0).dstOffset(0).size(size);
        org.lwjgl.vulkan.VK10.vkCmdCopyBuffer(cmdBuf, stagingBuf[0], blockOffsetsBuf[0], region);
        region.free();
        VulkanComputeContext.endSingleTimeCommands(cmdBuf);
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

    /** v2: 上傳 L2 粗網格資料（W-Cycle 需要）。 */
    public void uploadL2CoarseData(float[] coarseCond, byte[] coarseType) {
        if (!multigridAllocated || conductivityL2Buf == null) return;
        uploadFloatBuffer(conductivityL2Buf, coarseCond);
        uploadByteBuffer(typeL2Buf, coarseType);
    }

    private void uploadFloatBuffer(long[] deviceBuf, float[] data) {
        long size = (long) data.length * Float.BYTES;
        ByteBuffer mapped = VulkanComputeContext.mapBuffer(stagingBuf[1], stagingSize);
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
        ByteBuffer mapped = VulkanComputeContext.mapBuffer(stagingBuf[1], stagingSize);
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

        ByteBuffer mapped = VulkanComputeContext.mapBuffer(stagingBuf[1], stagingSize);
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

        ByteBuffer mapped = VulkanComputeContext.mapBuffer(stagingBuf[1], stagingSize);
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
     * 世界座標 → Morton Z-Order 索引（v2 Phase B）。
     */
    public int flatIndex(BlockPos pos) {
        int x = pos.getX() - origin.getX();
        int y = pos.getY() - origin.getY();
        int z = pos.getZ() - origin.getZ();
        return MortonCode.encode(x, y, z);
    }

    /**
     * Morton Z-Order 索引 → 世界座標（v2 Phase B）。
     */
    public BlockPos fromFlatIndex(int i) {
        int x = MortonCode.decodeX(i);
        int y = MortonCode.decodeY(i);
        int z = MortonCode.decodeZ(i);
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
    /** v2 Phase B: Morton 需要 power-of-2 padded 維度 */
    public int getN() {
        int px = MortonCode.nextPow2(Lx);
        int py = MortonCode.nextPow2(Ly);
        int pz = MortonCode.nextPow2(Lz);
        return px * py * pz;
    }
    public int getLmax() { return Math.max(Lx, Math.max(Ly, Lz)); }
    public BlockPos getOrigin() { return origin; }
    public boolean isAllocated() { return allocated; }

    // Multigrid dimensions（P1：委託給 PFSFMultigridBuffers）
    public int getLxL1() { return multigrid.getLxL1(); }
    public int getLyL1() { return multigrid.getLyL1(); }
    public int getLzL1() { return multigrid.getLzL1(); }
    public int getLxL2() { return multigrid.getLxL2(); }
    public int getLyL2() { return multigrid.getLyL2(); }
    public int getLzL2() { return multigrid.getLzL2(); }

    // GPU buffer handles (for descriptor binding)
    public long getPhiBuf() { return phiBuf[0]; }
    public long getPhiPrevBuf() { return phiPrevBuf[0]; }
    public long getSourceBuf() { return sourceBuf[0]; }
    public long getConductivityBuf() { return conductivityBuf[0]; }
    public long getTypeBuf() { return typeBuf[0]; }
    public long getFailFlagsBuf() { return failFlagsBuf[0]; }
    public long getMaxPhiBuf() { return maxPhiBuf[0]; }
    public long getRcompBuf() { return rcompBuf[0]; }
    public long getRtensBuf()      { return rtensBuf      != null ? rtensBuf[0]      : 0; }
    // v2.1: Phase-Field + Hydration（P1：委託給 PFSFPhaseFieldBuffers）
    public long getHFieldBuf()     { return phaseField.getHFieldBuf(); }
    public long getDFieldBuf()     { return phaseField.getDFieldBuf(); }
    public long getHydrationBuf()  { return phaseField.getHydrationBuf(); }
    public long getBlockOffsetsBuf() { return blockOffsetsBuf != null ? blockOffsetsBuf[0] : 0; }
    public int  getNumBlocks()     { return (getN() + 511) / 512; }
    public long getHFieldSize()    { return getPhiSize(); }
    public long getDFieldSize()    { return getPhiSize(); }
    public long getHydrationSize() { return getPhiSize(); }
    public long getBlockOffsetsBufSize() { return (long) getNumBlocks() * Integer.BYTES; }
    // backward-compat aliases（P1：委託給 PFSFPhaseFieldBuffers）
    public long getDamageBuf()     { return phaseField.getDamageBuf(); }
    public long getHistoryBuf()    { return phaseField.getHistoryBuf(); }
    public long getGcBuf()         { return phaseField.getGcBuf(); }
    public long getDamageSize()    { return getPhiSize(); }
    // Multigrid buffer handles（P1：委託給 PFSFMultigridBuffers）
    public long getPhiL1Buf() { return multigrid.getPhiL1Buf(); }
    public long getPhiPrevL1Buf() { return multigrid.getPhiPrevL1Buf(); }
    public long getSourceL1Buf() { return multigrid.getSourceL1Buf(); }
    public long getConductivityL1Buf() { return multigrid.getConductivityL1Buf(); }
    public long getTypeL1Buf() { return multigrid.getTypeL1Buf(); }
    public long getPhiL2Buf() { return multigrid.getPhiL2Buf(); }
    public long getPhiPrevL2Buf() { return multigrid.getPhiPrevL2Buf(); }
    public long getSourceL2Buf() { return multigrid.getSourceL2Buf(); }
    public long getConductivityL2Buf() { return multigrid.getConductivityL2Buf(); }
    public long getTypeL2Buf() { return multigrid.getTypeL2Buf(); }
    public int getN_L1() { return multigrid.getN_L1(); }
    public int getN_L2() { return multigrid.getN_L2(); }

    // Buffer sizes (for descriptor range)
    public long getPhiSize() { return (long) getN() * Float.BYTES; }
    public long getConductivitySize() { return (long) getN() * 6 * Float.BYTES; }
    public long getTypeSize() { return getN(); }

    // State
    public boolean isDirty() { return dirty; }
    public void markDirty() { dirty = true; }
    public void markClean() { dirty = false; }

    // Macro-block adaptive skip
    public long getMacroBlockResidualBuf() { return macroBlockResidualBuf != null ? macroBlockResidualBuf[0] : 0; }
    public int getNumMacroBlocks() {
        int mbSize = PFSFConstants.MORTON_BLOCK_SIZE; // 8
        return ceilDiv(Lx, mbSize) * ceilDiv(Ly, mbSize) * ceilDiv(Lz, mbSize);
    }
    public long getMacroBlockResidualSize() { return (long) getNumMacroBlocks() * Float.BYTES; }

    // P1 重構：組件存取器
    public PFSFConvergenceState getConvergence() { return convergence; }
    public PFSFMultigridBuffers getMultigrid() { return multigrid; }
    public PFSFPhaseFieldBuffers getPhaseField() { return phaseField; }

    // ═══════════════════════════════════════════════════════════════
    //  A1-fix: Phi Double-Buffering Swap
    // ═══════════════════════════════════════════════════════════════

    /**
     * 交換 phi ↔ phiPrev 指標。每次 Jacobi 迭代後呼叫。
     * O(1) 操作，不涉及任何 GPU 記憶體複製。
     */
    public void swapPhi() {
        long[] temp = phiBuf;
        phiBuf = phiPrevBuf;
        phiPrevBuf = temp;
    }

    /** #5-fix: 交換 L1 粗網格的 phi ↔ phiPrev（P1：委託） */
    public void swapPhiL1() { multigrid.swapPhiL1(); }

    /** W-Cycle: 交換 L2 粗網格的 phi ↔ phiPrev（P1：委託） */
    public void swapPhiL2() { multigrid.swapPhiL2(); }

    // ═══════════════════════════════════════════════════════════════
    //  A4-fix: Reference Counting（防止 async 回調 UAF）
    // ═══════════════════════════════════════════════════════════════

    /** 增加引用計數（提交 async 工作時呼叫） */
    public void retain() { refCount.incrementAndGet(); }

    /**
     * 減少引用計數。歸零時自動釋放所有 GPU 資源。
     * @return true 若資源已被釋放
     */
    public boolean release() {
        if (refCount.decrementAndGet() <= 0) {
            free();
            return true;
        }
        return false;
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }
}
