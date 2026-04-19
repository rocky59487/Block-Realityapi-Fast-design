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

    /**
     * Bumped whenever the GPU buffer layout or dirty-bit / markClean
     * semantics change. Any hot cache (snapshot, serialized state,
     * reconstructed-from-disk island) carrying a version lower than the
     * current constant must be discarded and rebuilt from world state
     * instead of being replayed, otherwise pre-bump `markClean` behavior
     * could skip a necessary tick on load.
     *
     * <p>History:
     * <ul>
     *   <li>1 — initial v0.3d layout
     *   <li>2 — v0.3e.1 hotfix 10.2/10.3: dispatched-gated markClean and
     *       h_field_buf as unconditional scratch slot (stored caches from
     *       older builds assumed markClean ran every tick)
     * </ul>
     */
    public static final int BUFFER_FORMAT_VERSION = 2;

    private final int islandId;

    // ─── Grid dimensions ───
    private int Lx, Ly, Lz;
    private BlockPos origin; // AABB 最小角

    // ─── GPU buffer handles: coalesced single VMA allocation ───
    // All device-local island buffers share a single VMA allocation with sub-buffer offsets.
    // A1-fix: phi double-buffering via offset swap (phiAOffset / phiBOffset)
    private long[] coalescedBuf;  // single [bufferHandle, allocationHandle]
    private long coalescedSize;

    // Sub-buffer offsets (aligned to 16 bytes for storage buffer requirements)
    private long phiAOffset, phiBOffset;
    private long phiOffset;       // 當前「寫入」偏移（指向 A 或 B）
    private long phiPrevOffset;   // 當前「讀取」偏移（指向另一個）
    private long sourceOffset;
    private long conductivityOffset;
    private long typeOffset;
    private long failFlagsOffset;
    private long maxPhiOffset;
    private long rcompOffset;
    private long rtensOffset;
    private long blockOffsetsOffset;
    private long macroResidualOffset;

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
    private boolean coarseOnly = false;  // v3: true if allocated at L1 half-resolution

    // ─── P1: 向下相容 — 舊欄位委託給 convergence ───
    // 以下 package-private 欄位保留以相容現有 PFSFScheduler/Engine 的直接存取
    int chebyshevIter = 0;
    float rhoSpecOverride;
    float maxPhiPrev = 0;
    float maxPhiPrevPrev = 0;
    boolean dampingActive = false;

    // v3: 多指標發散偵測
    int oscillationCount = 0;           // 連續方向交替次數（用於延長震盪偵測）
    float prevMaxMacroResidual = 0;     // 上一 tick 的最大 macro-block 殘差

    // v3: 收斂穩定計數（連續 stableTickCount tick phi 變化 < 1% 後跳過計算）
    int stableTickCount = 0;

    // v3: LOD 物理
    int lodLevel = PFSFConstants.LOD_FULL;
    int wakeTicksRemaining = 0;

    // v3: BFS 快取
    long topologyVersion = 0;
    private java.util.Map<net.minecraft.core.BlockPos, Integer> cachedArmMap;
    private java.util.Map<net.minecraft.core.BlockPos, Float> cachedArchFactorMap;
    private long cachedTopologyVersion = -1;

    // v3: Macro-block 殘差快取
    float[] cachedMacroResiduals;

    // ─── WSS-HQR Vector Field buffer (Module 1) ───
    // float[N×3] u,v,w 向量場，僅在 isVectorSolveNeeded() 時分配（on-demand）
    private long[] vectorFieldBuf;  // [bufferHandle, allocationHandle]
    private boolean vectorFieldAllocated = false;

    // ─── AMG GPU buffers (Module 2) ───
    // 4 個 GPU buffer：fine→coarse mapping, prolongation weights, coarse residual, coarse correction
    // CPU 資料由 AMGPreconditioner.build() 後透過 uploadAMGData() 上傳
    AMGPreconditioner amgPreconditioner;    // package-private for PFSFDispatcher
    private long[] amgAggregationBuf;  // int[N_fine]     fine→coarse mapping
    private long[] amgPWeightBuf;      // float[N_fine]   prolongation weights
    private long[] amgCoarseSrcBuf;    // float[N_coarse] restricted residual (uint for atomic)
    private long[] amgCoarsePhiBuf;    // float[N_coarse] coarse correction
    private boolean amgAllocated = false;

    // ─── PCG (Preconditioned Conjugate Gradient) buffers ───
    // 額外 3 個 float[N] 向量 + 2 個 reduction buffer，僅在 hybrid solver 啟用時分配
    private long[] pcgRBuf;         // 殘差向量 r[N]
    private long[] pcgPBuf;         // 搜索方向 p[N]
    private long[] pcgApBuf;        // 矩陣-向量乘積 Ap[N]
    private long[] pcgPartialBuf;   // dot product partial sums [ceil(N/512)]
    private long[] pcgReductionBuf; // reduction 結果 [4 slots: rTr_old, pAp, rTr_new, spare]
    private boolean pcgAllocated = false;

    // A4-fix: 引用計數，防止回調訪問已釋放的 buffer
    private final java.util.concurrent.atomic.AtomicInteger refCount =
            new java.util.concurrent.atomic.AtomicInteger(1);

    public PFSFIslandBuffer(int islandId) {
        this.islandId = islandId;
        // Bug #2 fix: 預初始化收斂狀態，防止 allocate() 前 getConvergence() NPE
        this.convergence = new PFSFConvergenceState(1);
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
        if (allocated) {
            // P2-B: 不呼叫 public free()（可能繞過 refCount 語意）
            // 使用私有 freeGpuResources()，並在有其他持有者時記錄警告
            if (refCount.get() != 1) {
                LOGGER.warn("[PFSF] Island {} re-allocated while still retained (refCount={}); possible UAF risk",
                        islandId, refCount.get());
            }
            freeGpuResources();
        }

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

        // v2.1: Morton block offsets（預估最多 ceil(N/512) 個 micro-blocks）
        int numBlocks = (N + 511) / 512;
        long blockOffsetsSize = (long) numBlocks * Integer.BYTES;

        // Macro-block adaptive skip: per-macroblock residual（8×8×8 = 512 體素/塊）
        int numMacroBlocks = getNumMacroBlocks();
        long macroResidualSize = (long) numMacroBlocks * Float.BYTES;

        // ─── Coalesced allocation: calculate total size with device-specific offset alignment ───
        long offset = 0;
        phiAOffset = offset;           offset = alignToDevice(offset + floatN);
        phiBOffset = offset;           offset = alignToDevice(offset + floatN);
        sourceOffset = offset;         offset = alignToDevice(offset + floatN);
        conductivityOffset = offset;   offset = alignToDevice(offset + float6N);
        typeOffset = offset;           offset = alignToDevice(offset + byteN);
        failFlagsOffset = offset;      offset = alignToDevice(offset + byteN);
        maxPhiOffset = offset;         offset = alignToDevice(offset + floatN);
        rcompOffset = offset;          offset = alignToDevice(offset + floatN);
        rtensOffset = offset;          offset = alignToDevice(offset + floatN);
        blockOffsetsOffset = offset;   offset = alignToDevice(offset + blockOffsetsSize);
        macroResidualOffset = offset;  offset = alignToDevice(offset + macroResidualSize);
        coalescedSize = offset;

        // Single VMA allocation for all device-local island buffers
        coalescedBuf = VulkanComputeContext.allocateDeviceBuffer(coalescedSize, storageUsage);
        if (coalescedBuf == null) {
            LOGGER.error("[PFSF] Island {} coalesced buffer allocation failed (VRAM budget exceeded, {} KB requested)",
                    islandId, coalescedSize / 1024);
            allocated = false;
            return;
        }

        // A1-fix: phi double-buffering via offset swap
        phiOffset = phiAOffset;
        phiPrevOffset = phiBOffset;

        // P1 重構：委託相場 buffer 分配
        phaseField.allocate(N);
        if (!phaseField.isAllocated()) {
            LOGGER.error("[PFSF] Island {} phaseField allocation failed; rolling back coalesced+staging", islandId);
            VulkanComputeContext.freeBuffer(coalescedBuf[0], coalescedBuf[1]);
            coalescedBuf = null;
            allocated = false;
            return;
        }

        // Staging: 足夠容納最大的 buffer（conductivity = 6N floats）
        stagingSize = float6N;
        stagingBuf = VulkanComputeContext.allocateStagingBuffer(stagingSize);
        if (stagingBuf == null) {
            LOGGER.error("[PFSF] Island {} staging buffer allocation failed", islandId);
            VulkanComputeContext.freeBuffer(coalescedBuf[0], coalescedBuf[1]);
            coalescedBuf = null;
            allocated = false;
            return;
        }

        // P1 重構：初始化收斂狀態
        convergence = new PFSFConvergenceState(getLmax());
        rhoSpecOverride = convergence.getRhoSpecOverride();

        allocated = true;
        LOGGER.debug("[PFSF] Island {} allocated: {}×{}×{} = {} voxels, coalesced VRAM ≈ {} KB",
                islandId, Lx, Ly, Lz, N, coalescedSize / 1024);
    }

    /**
     * 分配多重網格粗網格 buffer（P1 重構：委託給 PFSFMultigridBuffers）。
     */
    public void allocateMultigrid() {
        multigrid.allocate(Lx, Ly, Lz);
    }

    // ─── PCG Buffer Allocation ───

    /**
     * 分配 PCG 所需的額外 GPU buffer（r, p, Ap + reduction buffers）。
     *
     * <p>額外 VRAM = 3*N*4 + ceil(N/512)*4 + 16 bytes per island。
     * 僅在 hybrid RBGS+PCG solver 啟用時呼叫。</p>
     */
    public void allocatePCG() {
        if (pcgAllocated) return;
        if (!allocated) throw new IllegalStateException("Must allocate main buffers before PCG");

        int N = getN();
        long floatN = (long) N * Float.BYTES;
        int storageUsage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                | VK_BUFFER_USAGE_TRANSFER_SRC_BIT
                | VK_BUFFER_USAGE_TRANSFER_DST_BIT;

        pcgRBuf  = VulkanComputeContext.allocateDeviceBuffer(floatN, storageUsage);
        pcgPBuf  = VulkanComputeContext.allocateDeviceBuffer(floatN, storageUsage);
        pcgApBuf = VulkanComputeContext.allocateDeviceBuffer(floatN, storageUsage);

        // Partial sums: ceil(N / 512) floats for dot product reduction
        int numPartials = (N + 511) / 512;
        pcgPartialBuf = VulkanComputeContext.allocateDeviceBuffer(
                (long) numPartials * Float.BYTES, storageUsage);

        // Reduction result: 4 float slots (rTr_old, pAp, rTr_new, spare)
        pcgReductionBuf = VulkanComputeContext.allocateDeviceBuffer(
                (long) PFSFPCGRecorder.PCG_REDUCTION_SLOTS * Float.BYTES, storageUsage);

        // Rollback on partial allocation failure — prevent VRAM leak
        if (pcgRBuf == null || pcgPBuf == null || pcgApBuf == null
                || pcgPartialBuf == null || pcgReductionBuf == null) {
            LOGGER.warn("[PFSF] Island {} PCG buffer allocation failed (VRAM budget), rolling back", islandId);
            freePCG();
            return;
        }

        pcgAllocated = true;
        LOGGER.debug("[PFSF] Island {} PCG buffers allocated: 3×{}×4 = {} KB extra VRAM",
                islandId, N, (3L * floatN) / 1024);
    }

    /**
     * 釋放 PCG GPU buffer。
     */
    public void freePCG() {
        freeBufferPair(pcgRBuf);  pcgRBuf = null;
        freeBufferPair(pcgPBuf);  pcgPBuf = null;
        freeBufferPair(pcgApBuf); pcgApBuf = null;
        freeBufferPair(pcgPartialBuf);   pcgPartialBuf = null;
        freeBufferPair(pcgReductionBuf); pcgReductionBuf = null;
        pcgAllocated = false;
    }

    /** PCG buffer 是否已分配 */
    public boolean isPCGAllocated() { return pcgAllocated; }

    /**
     * 實際釋放所有 GPU buffer 的私有方法。
     * 不操作 refCount — 由 release() 和 allocate() 內部呼叫。
     */
    private void freeGpuResources() {
        freeBufferPair(coalescedBuf);
        coalescedBuf = null;
        freeBufferPair(stagingBuf);

        // PCG buffers
        freePCG();

        // P1 重構：委託組件釋放
        phaseField.free();
        multigrid.free();

        allocated = false;
    }

    /**
     * 釋放所有 GPU buffer。
     * @deprecated 外部呼叫端應使用 {@link #release()} 以正確遞減 refCount；
     *             直接呼叫 free() 會繞過引用計數，可能導致 UAF。
     */
    @Deprecated
    public void free() {
        freeGpuResources();
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
     * v3: 批次上傳 — 單一 command buffer 錄製 6 次 copy + 1 次 fence wait
     * （取代舊版 6 次獨立 submit + vkQueueWaitIdle）
     */
    public void uploadSourceAndConductivity(float[] source, float[] conductivity,
                                             byte[] type, float[] maxPhi, float[] rcomp,
                                             float[] rtens) {
        if (!allocated) throw new IllegalStateException("Buffer not allocated");

        // ─── Data validation ───
        int N = Lx * Ly * Lz;
        if (source.length != N) throw new IllegalArgumentException(
                "[PFSF] source.length=" + source.length + " != N=" + N);
        if (conductivity.length != 6 * N) throw new IllegalArgumentException(
                "[PFSF] conductivity.length=" + conductivity.length + " != 6N=" + (6 * N));
        if (type.length != N) throw new IllegalArgumentException(
                "[PFSF] type.length=" + type.length + " != N=" + N);
        // NaN/Inf check on critical arrays
        for (int i = 0; i < N; i++) {
            if (Float.isNaN(source[i]) || Float.isInfinite(source[i])) {
                throw new IllegalArgumentException("[PFSF] NaN/Inf in source[" + i + "]=" + source[i]);
            }
        }

        // v3: 批次上傳 — 所有 copy 錄製到同一個 command buffer
        var cmdBuf = VulkanComputeContext.beginSingleTimeCommands();

        stageAndCopyFloat(cmdBuf, sourceOffset, source);
        stageAndCopyFloat(cmdBuf, conductivityOffset, conductivity);
        stageAndCopyByte(cmdBuf, typeOffset, type);
        stageAndCopyFloat(cmdBuf, maxPhiOffset, maxPhi);
        stageAndCopyFloat(cmdBuf, rcompOffset, rcomp);
        stageAndCopyFloat(cmdBuf, rtensOffset, rtens);

        // 單次 fence wait（取代 6 次 vkQueueWaitIdle），完成後釋放 command buffer
        long fence = VulkanComputeContext.endSingleTimeCommandsWithFence(cmdBuf);
        VulkanComputeContext.waitFenceAndFree(fence, cmdBuf);
    }

    /** v3: 暫存 float 資料到 staging 並錄製 copy 到 coalesced buffer 的指定 offset（不 submit） */
    private void stageAndCopyFloat(org.lwjgl.vulkan.VkCommandBuffer cmdBuf, long dstOffset, float[] data) {
        long size = (long) data.length * Float.BYTES;
        java.nio.ByteBuffer mapped = VulkanComputeContext.mapBuffer(stagingBuf[1], stagingSize);
        mapped.asFloatBuffer().put(data);
        VulkanComputeContext.unmapBuffer(stagingBuf[1]);
        org.lwjgl.vulkan.VkBufferCopy.Buffer region = org.lwjgl.vulkan.VkBufferCopy.calloc(1)
                .srcOffset(0).dstOffset(dstOffset).size(size);
        org.lwjgl.vulkan.VK10.vkCmdCopyBuffer(cmdBuf, stagingBuf[0], coalescedBuf[0], region);
        region.free();
    }

    /** v3: 暫存 byte 資料到 staging 並錄製 copy 到 coalesced buffer 的指定 offset（不 submit） */
    private void stageAndCopyByte(org.lwjgl.vulkan.VkCommandBuffer cmdBuf, long dstOffset, byte[] data) {
        java.nio.ByteBuffer mapped = VulkanComputeContext.mapBuffer(stagingBuf[1], stagingSize);
        mapped.put(data);
        VulkanComputeContext.unmapBuffer(stagingBuf[1]);
        org.lwjgl.vulkan.VkBufferCopy.Buffer region = org.lwjgl.vulkan.VkBufferCopy.calloc(1)
                .srcOffset(0).dstOffset(dstOffset).size(data.length);
        org.lwjgl.vulkan.VK10.vkCmdCopyBuffer(cmdBuf, stagingBuf[0], coalescedBuf[0], region);
        region.free();
    }

    /**
     * 上傳水化度陣列到 GPU（v2.1 固化時間效應）。
     * CPU 端由 ICuringManager 計算，每 tick 在 uploadSourceAndConductivity 之前呼叫。
     */
    public void uploadHydration(float[] hydrationDegree) {
        if (!allocated || !phaseField.isAllocated()) return;
        // Bug #1 fix: 委託 phaseField 組件的 buffer handle
        long hydBuf = phaseField.getHydrationBuf();
        if (hydBuf == 0) return;
        uploadFloatBufferToHandle(hydBuf, hydrationDegree);
    }

    /**
     * 上傳 Morton block offsets 到 GPU（v2.1 Tiled Morton Layout）。
     * 由 PFSFDataBuilder.buildMortonLayout() 計算後上傳。
     */
    public void uploadBlockOffsets(int[] offsets) {
        if (!allocated || coalescedBuf == null) return;
        long size = (long) offsets.length * Integer.BYTES;
        ByteBuffer mapped = VulkanComputeContext.mapBuffer(stagingBuf[1], stagingSize);
        mapped.asIntBuffer().put(offsets);
        VulkanComputeContext.unmapBuffer(stagingBuf[1]);
        var cmdBuf = VulkanComputeContext.beginSingleTimeCommands();
        org.lwjgl.vulkan.VkBufferCopy.Buffer region = org.lwjgl.vulkan.VkBufferCopy.calloc(1)
                .srcOffset(0).dstOffset(blockOffsetsOffset).size(size);
        org.lwjgl.vulkan.VK10.vkCmdCopyBuffer(cmdBuf, stagingBuf[0], coalescedBuf[0], region);
        region.free();
        VulkanComputeContext.endSingleTimeCommands(cmdBuf);
    }

    /**
     * 上傳粗網格 (L1) 的 conductivity 和 type 到 GPU。
     * Bug #1 fix: 委託 multigrid 組件的 buffer handle。
     */
    public void uploadCoarseData(float[] coarseCond, byte[] coarseType) {
        if (!multigrid.isAllocated()) return;
        long condL1 = multigrid.getConductivityL1Buf();
        long typeL1 = multigrid.getTypeL1Buf();
        if (condL1 != 0) uploadFloatBufferToHandle(condL1, coarseCond);
        if (typeL1 != 0) uploadByteBufferToHandle(typeL1, coarseType);
    }

    /** v2: 上傳 L2 粗網格資料（W-Cycle 需要）。 */
    public void uploadL2CoarseData(float[] coarseCond, byte[] coarseType) {
        if (!multigrid.isAllocated()) return;
        long condL2 = multigrid.getConductivityL2Buf();
        long typeL2 = multigrid.getTypeL2Buf();
        if (condL2 != 0) uploadFloatBufferToHandle(condL2, coarseCond);
        if (typeL2 != 0) uploadByteBufferToHandle(typeL2, coarseType);
    }

    /** Bug #1 fix: 上傳 float 到指定 buffer handle（非 long[] pair） */
    private void uploadFloatBufferToHandle(long deviceBufHandle, float[] data) {
        long size = (long) data.length * Float.BYTES;
        ByteBuffer mapped = VulkanComputeContext.mapBuffer(stagingBuf[1], stagingSize);
        mapped.asFloatBuffer().put(data);
        VulkanComputeContext.unmapBuffer(stagingBuf[1]);
        var cmdBuf = VulkanComputeContext.beginSingleTimeCommands();
        org.lwjgl.vulkan.VkBufferCopy.Buffer region = org.lwjgl.vulkan.VkBufferCopy.calloc(1)
                .srcOffset(0).dstOffset(0).size(size);
        org.lwjgl.vulkan.VK10.vkCmdCopyBuffer(cmdBuf, stagingBuf[0], deviceBufHandle, region);
        region.free();
        VulkanComputeContext.endSingleTimeCommands(cmdBuf);
    }

    /** Bug #1 fix: 上傳 byte 到指定 buffer handle */
    private void uploadByteBufferToHandle(long deviceBufHandle, byte[] data) {
        ByteBuffer mapped = VulkanComputeContext.mapBuffer(stagingBuf[1], stagingSize);
        mapped.put(data);
        VulkanComputeContext.unmapBuffer(stagingBuf[1]);
        var cmdBuf = VulkanComputeContext.beginSingleTimeCommands();
        org.lwjgl.vulkan.VkBufferCopy.Buffer region = org.lwjgl.vulkan.VkBufferCopy.calloc(1)
                .srcOffset(0).dstOffset(0).size(data.length);
        org.lwjgl.vulkan.VK10.vkCmdCopyBuffer(cmdBuf, stagingBuf[0], deviceBufHandle, region);
        region.free();
        VulkanComputeContext.endSingleTimeCommands(cmdBuf);
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
                .srcOffset(failFlagsOffset).dstOffset(0).size(N);
        org.lwjgl.vulkan.VK10.vkCmdCopyBuffer(cmdBuf, coalescedBuf[0], stagingBuf[0], region);
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
     * @deprecated v3: 使用 GPU 2-pass reduction（PFSFFailureRecorder.recordPhiMaxReduction）
     *             取代此 CPU 全量讀回。此方法讀回整個 phi[]（4MB/1M voxels）+ CPU 線性掃描，
     *             GPU reduction 僅讀回 4 bytes。
     */
    @Deprecated
    public float readMaxPhi() {
        int N = getN();
        long size = (long) N * Float.BYTES;

        var cmdBuf = VulkanComputeContext.beginSingleTimeCommands();
        org.lwjgl.vulkan.VkBufferCopy.Buffer region = org.lwjgl.vulkan.VkBufferCopy.calloc(1)
                .srcOffset(phiOffset).dstOffset(0).size(size);
        org.lwjgl.vulkan.VK10.vkCmdCopyBuffer(cmdBuf, coalescedBuf[0], stagingBuf[0], region);
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

    // GPU buffer handles (for descriptor binding) — all return the coalesced buffer handle
    // Null-guarded to prevent NPE if allocation failed (coalescedBuf == null)
    public long getPhiBuf() { return coalescedBuf != null ? coalescedBuf[0] : 0; }
    public long getPhiPrevBuf() { return coalescedBuf != null ? coalescedBuf[0] : 0; }
    public long getSourceBuf() { return coalescedBuf != null ? coalescedBuf[0] : 0; }
    public long getConductivityBuf() { return coalescedBuf != null ? coalescedBuf[0] : 0; }
    public long getTypeBuf() { return coalescedBuf != null ? coalescedBuf[0] : 0; }
    public long getFailFlagsBuf() { return coalescedBuf != null ? coalescedBuf[0] : 0; }
    public long getMaxPhiBuf() { return coalescedBuf != null ? coalescedBuf[0] : 0; }
    public long getRcompBuf() { return coalescedBuf != null ? coalescedBuf[0] : 0; }
    public long getRtensBuf() { return coalescedBuf != null ? coalescedBuf[0] : 0; }
    public long getBlockOffsetsBuf() { return coalescedBuf != null ? coalescedBuf[0] : 0; }

    // Sub-buffer offsets within the coalesced allocation (16-byte aligned)
    public long getPhiOffset() { return phiOffset; }
    public long getPhiPrevOffset() { return phiPrevOffset; }
    public long getSourceOffset() { return sourceOffset; }
    public long getConductivityOffset() { return conductivityOffset; }
    public long getTypeOffset() { return typeOffset; }
    public long getFailFlagsOffset() { return failFlagsOffset; }
    public long getMaxPhiOffset() { return maxPhiOffset; }
    public long getRcompOffset() { return rcompOffset; }
    public long getRtensOffset() { return rtensOffset; }
    public long getBlockOffsetsOffset() { return blockOffsetsOffset; }
    public long getMacroResidualOffset() { return macroResidualOffset; }

    // ─── PCG buffer handles (separate allocations, not coalesced) ───
    public long getPcgRBuf()         { return pcgRBuf  != null ? pcgRBuf[0]  : 0; }
    public long getPcgPBuf()         { return pcgPBuf  != null ? pcgPBuf[0]  : 0; }
    public long getPcgApBuf()        { return pcgApBuf != null ? pcgApBuf[0] : 0; }
    public long getPcgPartialBuf()   { return pcgPartialBuf   != null ? pcgPartialBuf[0]   : 0; }
    public long getPcgReductionBuf() { return pcgReductionBuf != null ? pcgReductionBuf[0] : 0; }

    // v2.1: Phase-Field + Hydration（P1：委託給 PFSFPhaseFieldBuffers — separate allocation）
    public long getHFieldBuf()     { return phaseField.getHFieldBuf(); }
    public long getDFieldBuf()     { return phaseField.getDFieldBuf(); }
    public long getHydrationBuf()  { return phaseField.getHydrationBuf(); }
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
    public long getTypeSize()         { return getN(); }
    /** Size of the rtens (float per voxel) region — same as getPhiSize(). */
    public long getRtensSize()        { return getPhiSize(); }
    /** Size of the failFlags (byte per voxel) region. */
    public long getFailFlagsSize()    { return getN(); }

    // State
    public boolean isDirty() { return dirty; }
    public void markDirty() { dirty = true; }
    public void markClean() { dirty = false; }
    /** v3: 是否以粗網格（L1 半維度）分配 */
    public boolean isCoarseOnly() { return coarseOnly; }
    /** v3: 設定粗網格模式 */
    public void setCoarseOnly(boolean coarseOnly) { this.coarseOnly = coarseOnly; }

    // v3: 收斂穩定計數
    public int getStableTickCount() { return stableTickCount; }
    public void incrementStableCount() { stableTickCount++; }
    public void resetStableCount() { stableTickCount = 0; }

    // v3: LOD
    public int getLodLevel() { return lodLevel; }
    public void setLodLevel(int lod) { this.lodLevel = lod; }
    public int getWakeTicksRemaining() { return wakeTicksRemaining; }
    public void setWakeTicksRemaining(int ticks) { this.wakeTicksRemaining = ticks; }
    public void decrementWakeTicks() { if (wakeTicksRemaining > 0) wakeTicksRemaining--; }

    // v0.4 M3g: read-only accessors for /br debug pfsf LOD table. Package-
    // private fields stay writable from same-package hot paths; external
    // consumers (debug command, telemetry) only need to observe.
    public int getOscillationCount() { return oscillationCount; }
    public float getPrevMaxMacroResidual() { return prevMaxMacroResidual; }
    public float[] getCachedMacroResidualsView() { return cachedMacroResiduals; }

    // v3: BFS 快取
    public long getTopologyVersion() { return topologyVersion; }
    public void incrementTopologyVersion() { topologyVersion++; }
    public java.util.Map<net.minecraft.core.BlockPos, Integer> getCachedArmMap() { return cachedArmMap; }
    public void setCachedArmMap(java.util.Map<net.minecraft.core.BlockPos, Integer> map) { cachedArmMap = map; cachedTopologyVersion = topologyVersion; }
    public java.util.Map<net.minecraft.core.BlockPos, Float> getCachedArchFactorMap() { return cachedArchFactorMap; }
    public void setCachedArchFactorMap(java.util.Map<net.minecraft.core.BlockPos, Float> map) { cachedArchFactorMap = map; }
    public boolean isBfsCacheValid() { return cachedTopologyVersion == topologyVersion && cachedArmMap != null; }

    /**
     * Bug #3 fix: 清除 macro-block 殘差 buffer（每次 failure_scan 前呼叫）。
     * 若不清除，atomicMax 導致殘差只增不減，已收斂區塊永遠不會被跳過。
     */
    public void clearMacroBlockResiduals() {
        if (coalescedBuf == null) return;
        long size = getMacroBlockResidualSize();
        var cmdBuf = VulkanComputeContext.beginSingleTimeCommands();
        org.lwjgl.vulkan.VK10.vkCmdFillBuffer(cmdBuf, coalescedBuf[0], macroResidualOffset, size, 0);
        VulkanComputeContext.endSingleTimeCommands(cmdBuf);
    }

    // Macro-block adaptive skip
    public long getMacroBlockResidualBuf() { return coalescedBuf != null ? coalescedBuf[0] : 0; }
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
     * 交換 phi ↔ phiPrev 偏移量。每次 Jacobi 迭代後呼叫。
     * O(1) 操作，不涉及任何 GPU 記憶體複製。
     */
    public void swapPhi() {
        long temp = phiOffset;
        phiOffset = phiPrevOffset;
        phiPrevOffset = temp;
    }

    /** #5-fix: 交換 L1 粗網格的 phi ↔ phiPrev（P1：委託） */
    public void swapPhiL1() { multigrid.swapPhiL1(); }

    /** W-Cycle: 交換 L2 粗網格的 phi ↔ phiPrev（P1：委託） */
    public void swapPhiL2() { multigrid.swapPhiL2(); }

    // ═══════════════════════════════════════════════════════════════
    //  A4-fix / P2-B: Reference Counting（防止 async 回調 UAF）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 增加引用計數（提交 async 工作前呼叫）。
     * 必須與 {@link #release()} 成對使用。
     */
    public void acquire() { refCount.incrementAndGet(); }

    /** @deprecated 使用 {@link #acquire()} — 語意更清晰 */
    @Deprecated
    public void retain() { acquire(); }

    /**
     * 減少引用計數。歸零時自動呼叫 {@link #freeGpuResources()} 釋放所有 GPU 資源。
     * @return true 若資源已被釋放（refCount 歸零）
     */
    public boolean release() {
        if (refCount.decrementAndGet() <= 0) {
            freeGpuResources(); // 直接呼叫私有方法，避免呼叫已 @Deprecated 的 free()
            return true;
        }
        return false;
    }

    /**
     * 對齊到 GPU 要求的 minStorageBufferOffsetAlignment。
     * 不同 GPU 的對齊要求不同（NVIDIA 通常 16，Intel Arc 可能 256）。
     */
    private static long alignToDevice(long offset) {
        long alignment = VulkanComputeContext.getMinBufferAlignment();
        return (offset + (alignment - 1)) & ~(alignment - 1);
    }

    // ─── AMG GPU buffer accessors and upload ───

    public long getAggregationBuf()  { return amgAggregationBuf  != null ? amgAggregationBuf[0]  : 0; }
    public long getPWeightBuf()      { return amgPWeightBuf       != null ? amgPWeightBuf[0]       : 0; }
    public long getCoarseSrcBuf()    { return amgCoarseSrcBuf     != null ? amgCoarseSrcBuf[0]     : 0; }
    public long getCoarsePhiBuf()    { return amgCoarsePhiBuf     != null ? amgCoarsePhiBuf[0]     : 0; }

    /**
     * CPU→GPU one-time upload of AMG data after {@link AMGPreconditioner#build()} completes.
     * Allocates GPU buffers on first call.
     *
     * @param aggregation fine→coarse mapping int[N_fine]
     * @param pWeights    prolongation weights float[N_fine]
     * @param nCoarse     number of coarse aggregate nodes
     */
    public void uploadAMGData(int[] aggregation, float[] pWeights, int nCoarse) {
        int nFine = aggregation.length;
        long aggSize    = (long) nFine * Integer.BYTES;
        long pwSize     = (long) nFine * Float.BYTES;
        long coarseSize = (long) nCoarse * Float.BYTES;

        if (!amgAllocated) {
            amgAggregationBuf = VulkanComputeContext.allocateDeviceBuffer(
                aggSize, org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                        | org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT);
            amgPWeightBuf = VulkanComputeContext.allocateDeviceBuffer(
                pwSize, org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                       | org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT);
            // CoarseSrc as uint buffer for float atomicAdd CAS (VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)
            amgCoarseSrcBuf = VulkanComputeContext.allocateDeviceBuffer(
                coarseSize, org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                            | org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT);
            amgCoarsePhiBuf = VulkanComputeContext.allocateDeviceBuffer(
                coarseSize, org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                            | org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT);
            amgAllocated = (amgAggregationBuf != null && amgPWeightBuf != null
                         && amgCoarseSrcBuf != null && amgCoarsePhiBuf != null);
            if (!amgAllocated) {
                LOGGER.error("[PFSF] AMG buffer allocation failed for island {}", islandId);
                return;
            }
        }

        // Upload int[] aggregation via staging buffer
        {
            java.nio.ByteBuffer stagingAgg = org.lwjgl.system.MemoryUtil.memAlloc((int) aggSize);
            for (int a : aggregation) stagingAgg.putInt(a);
            stagingAgg.flip();
            long[] staging = VulkanComputeContext.allocateStagingBuffer(aggSize);
            java.nio.ByteBuffer mapped = VulkanComputeContext.mapBuffer(staging[1], aggSize);
            org.lwjgl.system.MemoryUtil.memCopy(
                org.lwjgl.system.MemoryUtil.memAddress(stagingAgg),
                org.lwjgl.system.MemoryUtil.memAddress(mapped), aggSize);
            VulkanComputeContext.unmapBuffer(staging[1]);
            org.lwjgl.system.MemoryUtil.memFree(stagingAgg);
            org.lwjgl.vulkan.VkCommandBuffer cmd = VulkanComputeContext.beginSingleTimeCommands();
            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                org.lwjgl.vulkan.VkBufferCopy.Buffer region =
                    org.lwjgl.vulkan.VkBufferCopy.calloc(1, stack)
                        .srcOffset(0).dstOffset(0).size(aggSize);
                org.lwjgl.vulkan.VK10.vkCmdCopyBuffer(cmd, staging[0], amgAggregationBuf[0], region);
            }
            VulkanComputeContext.endSingleTimeCommands(cmd);
            VulkanComputeContext.freeBuffer(staging[0], staging[1]);
        }

        // Upload float[] pWeights via staging buffer
        {
            java.nio.ByteBuffer stagingPW = org.lwjgl.system.MemoryUtil.memAlloc((int) pwSize);
            for (float w : pWeights) stagingPW.putFloat(w);
            stagingPW.flip();
            long[] staging = VulkanComputeContext.allocateStagingBuffer(pwSize);
            java.nio.ByteBuffer mapped = VulkanComputeContext.mapBuffer(staging[1], pwSize);
            org.lwjgl.system.MemoryUtil.memCopy(
                org.lwjgl.system.MemoryUtil.memAddress(stagingPW),
                org.lwjgl.system.MemoryUtil.memAddress(mapped), pwSize);
            VulkanComputeContext.unmapBuffer(staging[1]);
            org.lwjgl.system.MemoryUtil.memFree(stagingPW);
            org.lwjgl.vulkan.VkCommandBuffer cmd = VulkanComputeContext.beginSingleTimeCommands();
            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                org.lwjgl.vulkan.VkBufferCopy.Buffer region =
                    org.lwjgl.vulkan.VkBufferCopy.calloc(1, stack)
                        .srcOffset(0).dstOffset(0).size(pwSize);
                org.lwjgl.vulkan.VK10.vkCmdCopyBuffer(cmd, staging[0], amgPWeightBuf[0], region);
            }
            VulkanComputeContext.endSingleTimeCommands(cmd);
            VulkanComputeContext.freeBuffer(staging[0], staging[1]);
        }

        LOGGER.debug("[PFSF] AMG data uploaded for island {}: {} fine nodes, {} coarse nodes",
            islandId, nFine, nCoarse);
    }

    // ─── WSS-HQR Vector Field buffer accessors ───

    /** Returns VkBuffer handle for the vector field buffer, or 0 if not yet allocated. */
    public long getVectorFieldBuf() {
        return (vectorFieldBuf != null) ? vectorFieldBuf[0] : 0;
    }

    /** Size of vectorField buffer in bytes: N × 3 × sizeof(float). */
    public long getVectorFieldSize() {
        return (long) getN() * 3 * Float.BYTES;
    }

    /**
     * Ensures the vector field buffer is allocated (on-demand).
     * Called before the first WSS-HQR dispatch for this island.
     */
    public void ensureVectorFieldAllocated() {
        if (vectorFieldAllocated) return;
        long size = getVectorFieldSize();
        vectorFieldBuf = VulkanComputeContext.allocateDeviceBuffer(
            size,
            org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
            | org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        if (vectorFieldBuf != null) {
            vectorFieldAllocated = true;
            LOGGER.debug("[PFSF] vectorFieldBuf allocated for island {} ({} KB)",
                islandId, size / 1024);
        } else {
            LOGGER.error("[PFSF] vectorFieldBuf allocation FAILED for island {}", islandId);
        }
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }
}
