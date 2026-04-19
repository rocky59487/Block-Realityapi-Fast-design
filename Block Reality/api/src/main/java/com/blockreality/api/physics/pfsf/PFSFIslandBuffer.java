package com.blockreality.api.physics.pfsf;

import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.function.Consumer;
import java.util.Set;
import java.util.Enumeration;
import java.net.URL;
import java.io.InputStream;
import java.io.IOException;
import java.util.jar.Manifest;
import java.util.jar.Attributes;
import java.util.concurrent.atomic.AtomicInteger;

import static com.blockreality.api.physics.pfsf.PFSFConstants.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * 每個 Structure Island 的 GPU 緩衝區包裝。
 *
 * <p>VRAM Layout（per island, flat 3D array index = x + Lx*(y + Ly*z)）：</p>
 */
public class PFSFIslandBuffer {

    private static final Logger LOGGER = LoggerFactory.getLogger("PFSF-Buffer");

    public static final int BUFFER_FORMAT_VERSION = 2;

    private final int islandId;

    // ─── Grid dimensions ───
    private int Lx, Ly, Lz;
    private BlockPos origin; // AABB 最小角

    // ─── GPU buffer handles: coalesced single VMA allocation ───
    private long[] coalescedBuf;  // single [bufferHandle, allocationHandle]
    private long coalescedSize;

    // Sub-buffer offsets
    private long phiAOffset, phiBOffset;
    private long phiOffset;
    private long phiPrevOffset;
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

    private final PFSFPhaseFieldBuffers phaseField = new PFSFPhaseFieldBuffers();
    private final PFSFMultigridBuffers multigrid = new PFSFMultigridBuffers();
    private PFSFConvergenceState convergence;

    private boolean dirty = true;
    private boolean allocated = false;
    private boolean coarseOnly = false;

    int chebyshevIter = 0;
    float rhoSpecOverride;
    float maxPhiPrev = 0;
    float maxPhiPrevPrev = 0;
    boolean dampingActive = false;

    int oscillationCount = 0;
    float prevMaxMacroResidual = 0;
    int stableTickCount = 0;
    int lodLevel = PFSFConstants.LOD_FULL;
    int wakeTicksRemaining = 0;

    long topologyVersion = 0;
    private java.util.Map<net.minecraft.core.BlockPos, Integer> cachedArmMap;
    private java.util.Map<net.minecraft.core.BlockPos, Float> cachedArchFactorMap;
    private long cachedTopologyVersion = -1;
    float[] cachedMacroResiduals;

    private long[] vectorFieldBuf;
    private boolean vectorFieldAllocated = false;

    AMGPreconditioner amgPreconditioner;
    private long[] amgAggregationBuf;
    private long[] amgPWeightBuf;
    private long[] amgCoarseSrcBuf;
    private long[] amgCoarsePhiBuf;
    private boolean amgAllocated = false;

    private long[] pcgRBuf;
    private long[] pcgPBuf;
    private long[] pcgApBuf;
    private long[] pcgPartialBuf;
    private long[] pcgReductionBuf;
    private boolean pcgAllocated = false;

    private final AtomicInteger refCount = new AtomicInteger(1);

    public PFSFIslandBuffer(int islandId) {
        this.islandId = islandId;
        this.convergence = new PFSFConvergenceState(1);
    }

    public void allocate(int Lx, int Ly, int Lz, BlockPos origin) {
        if (allocated) {
            if (refCount.get() != 1) {
                LOGGER.warn("[PFSF] Island {} re-allocated while still retained (refCount={})", islandId, refCount.get());
            }
            freeGpuResources();
        }

        this.Lx = Lx;
        this.Ly = Ly;
        this.Lz = Lz;
        this.origin = origin;

        int N = getN();
        long floatN = (long) N * Float.BYTES;
        long float6N = (long) N * 6 * Float.BYTES;
        long byteN = N;

        int storageUsage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                | VK_BUFFER_USAGE_TRANSFER_SRC_BIT
                | VK_BUFFER_USAGE_TRANSFER_DST_BIT;

        int numMacroBlocks = getNumMacroBlocks();
        long blockOffsetsSize = (long) numMacroBlocks * Integer.BYTES;
        long macroResidualSize = (long) numMacroBlocks * Float.BYTES;

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

        coalescedBuf = VulkanComputeContext.allocateDeviceBuffer(coalescedSize, storageUsage);
        if (coalescedBuf == null) {
            LOGGER.error("[PFSF] Island {} coalesced buffer allocation failed", islandId);
            allocated = false;
            return;
        }

        phiOffset = phiAOffset;
        phiPrevOffset = phiBOffset;

        phaseField.allocate(N);
        stagingSize = float6N;
        stagingBuf = VulkanComputeContext.allocateStagingBuffer(stagingSize);

        convergence = new PFSFConvergenceState(getLmax());
        rhoSpecOverride = convergence.getRhoSpecOverride();

        allocated = true;
    }

    public void allocateMultigrid() { multigrid.allocate(Lx, Ly, Lz); }

    public void allocatePCG() {
        if (pcgAllocated || !allocated) return;
        int N = getN();
        long floatN = (long) N * Float.BYTES;
        int storageUsage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        pcgRBuf  = VulkanComputeContext.allocateDeviceBuffer(floatN, storageUsage);
        pcgPBuf  = VulkanComputeContext.allocateDeviceBuffer(floatN, storageUsage);
        pcgApBuf = VulkanComputeContext.allocateDeviceBuffer(floatN, storageUsage);
        int numPartials = (N + 511) / 512;
        pcgPartialBuf = VulkanComputeContext.allocateDeviceBuffer((long) numPartials * Float.BYTES, storageUsage);
        pcgReductionBuf = VulkanComputeContext.allocateDeviceBuffer(16, storageUsage);
        pcgAllocated = true;
    }

    public void freePCG() {
        freeBufferPair(pcgRBuf); pcgRBuf = null;
        freeBufferPair(pcgPBuf); pcgPBuf = null;
        freeBufferPair(pcgApBuf); pcgApBuf = null;
        freeBufferPair(pcgPartialBuf); pcgPartialBuf = null;
        freeBufferPair(pcgReductionBuf); pcgReductionBuf = null;
        pcgAllocated = false;
    }

    private void freeGpuResources() {
        freeBufferPair(coalescedBuf); coalescedBuf = null;
        freeBufferPair(stagingBuf); stagingBuf = null;
        freePCG();
        phaseField.free();
        multigrid.free();
        allocated = false;
    }

    @Deprecated public void free() { freeGpuResources(); }

    private void freeBufferPair(long[] pair) {
        if (pair != null) VulkanComputeContext.freeBuffer(pair[0], pair[1]);
    }

    public void uploadSourceAndConductivity(float[] source, float[] conductivity, byte[] type, float[] maxPhi, float[] rcomp, float[] rtens) {
        if (!allocated) return;
        var cmdBuf = VulkanComputeContext.beginSingleTimeCommands();
        stageAndCopyFloat(cmdBuf, sourceOffset, source);
        stageAndCopyFloat(cmdBuf, conductivityOffset, conductivity);
        stageAndCopyByte(cmdBuf, typeOffset, type);
        stageAndCopyFloat(cmdBuf, maxPhiOffset, maxPhi);
        stageAndCopyFloat(cmdBuf, rcompOffset, rcomp);
        stageAndCopyFloat(cmdBuf, rtensOffset, rtens);
        long fence = VulkanComputeContext.endSingleTimeCommandsWithFence(cmdBuf);
        VulkanComputeContext.waitFenceAndFree(fence, cmdBuf);
    }

    private void stageAndCopyFloat(org.lwjgl.vulkan.VkCommandBuffer cmdBuf, long dstOffset, float[] data) {
        long size = (long) data.length * Float.BYTES;
        java.nio.ByteBuffer mapped = VulkanComputeContext.mapBuffer(stagingBuf[1], stagingSize);
        mapped.asFloatBuffer().put(data);
        VulkanComputeContext.unmapBuffer(stagingBuf[1]);
        org.lwjgl.vulkan.VkBufferCopy.Buffer region = org.lwjgl.vulkan.VkBufferCopy.calloc(1).srcOffset(0).dstOffset(dstOffset).size(size);
        org.lwjgl.vulkan.VK10.vkCmdCopyBuffer(cmdBuf, stagingBuf[0], coalescedBuf[0], region);
        region.free();
    }

    private void stageAndCopyByte(org.lwjgl.vulkan.VkCommandBuffer cmdBuf, long dstOffset, byte[] data) {
        java.nio.ByteBuffer mapped = VulkanComputeContext.mapBuffer(stagingBuf[1], stagingSize);
        mapped.put(data);
        VulkanComputeContext.unmapBuffer(stagingBuf[1]);
        org.lwjgl.vulkan.VkBufferCopy.Buffer region = org.lwjgl.vulkan.VkBufferCopy.calloc(1).srcOffset(0).dstOffset(dstOffset).size(data.length);
        org.lwjgl.vulkan.VK10.vkCmdCopyBuffer(cmdBuf, stagingBuf[0], coalescedBuf[0], region);
        region.free();
    }

    public void uploadHydration(float[] hydration) {
        if (!allocated) return;
        long hydBuf = phaseField.getHydrationBuf();
        if (hydBuf != 0) uploadFloatBufferToHandle(hydBuf, hydration);
    }

    public void uploadBlockOffsets(int[] offsets) {
        if (!allocated) return;
        long size = (long) offsets.length * Integer.BYTES;
        ByteBuffer mapped = VulkanComputeContext.mapBuffer(stagingBuf[1], stagingSize);
        mapped.asIntBuffer().put(offsets);
        VulkanComputeContext.unmapBuffer(stagingBuf[1]);
        var cmdBuf = VulkanComputeContext.beginSingleTimeCommands();
        org.lwjgl.vulkan.VkBufferCopy.Buffer region = org.lwjgl.vulkan.VkBufferCopy.calloc(1).srcOffset(0).dstOffset(blockOffsetsOffset).size(size);
        org.lwjgl.vulkan.VK10.vkCmdCopyBuffer(cmdBuf, stagingBuf[0], coalescedBuf[0], region);
        region.free();
        VulkanComputeContext.endSingleTimeCommands(cmdBuf);
    }

    public void uploadCoarseData(float[] cCond, byte[] cType) {
        if (!multigrid.isAllocated()) return;
        uploadFloatBufferToHandle(multigrid.getConductivityL1Buf(), cCond);
        uploadByteBufferToHandle(multigrid.getTypeL1Buf(), cType);
    }

    public void uploadL2CoarseData(float[] cCond, byte[] cType) {
        if (!multigrid.isAllocated()) return;
        uploadFloatBufferToHandle(multigrid.getConductivityL2Buf(), cCond);
        uploadByteBufferToHandle(multigrid.getTypeL2Buf(), cType);
    }

    private void uploadFloatBufferToHandle(long handle, float[] data) {
        if (handle == 0) return;
        long size = (long) data.length * Float.BYTES;
        ByteBuffer mapped = VulkanComputeContext.mapBuffer(stagingBuf[1], stagingSize);
        mapped.asFloatBuffer().put(data);
        VulkanComputeContext.unmapBuffer(stagingBuf[1]);
        var cmdBuf = VulkanComputeContext.beginSingleTimeCommands();
        org.lwjgl.vulkan.VkBufferCopy.Buffer region = org.lwjgl.vulkan.VkBufferCopy.calloc(1).srcOffset(0).dstOffset(0).size(size);
        org.lwjgl.vulkan.VK10.vkCmdCopyBuffer(cmdBuf, stagingBuf[0], handle, region);
        region.free();
        VulkanComputeContext.endSingleTimeCommands(cmdBuf);
    }

    private void uploadByteBufferToHandle(long handle, byte[] data) {
        if (handle == 0) return;
        ByteBuffer mapped = VulkanComputeContext.mapBuffer(stagingBuf[1], stagingSize);
        mapped.put(data);
        VulkanComputeContext.unmapBuffer(stagingBuf[1]);
        var cmdBuf = VulkanComputeContext.beginSingleTimeCommands();
        org.lwjgl.vulkan.VkBufferCopy.Buffer region = org.lwjgl.vulkan.VkBufferCopy.calloc(1).srcOffset(0).dstOffset(0).size(data.length);
        org.lwjgl.vulkan.VK10.vkCmdCopyBuffer(cmdBuf, stagingBuf[0], handle, region);
        region.free();
        VulkanComputeContext.endSingleTimeCommands(cmdBuf);
    }

    public void asyncReadFailFlags(Consumer<byte[]> callback) {
        int N = getN();
        var cmdBuf = VulkanComputeContext.beginSingleTimeCommands();
        org.lwjgl.vulkan.VkBufferCopy.Buffer region = org.lwjgl.vulkan.VkBufferCopy.calloc(1).srcOffset(failFlagsOffset).dstOffset(0).size(N);
        org.lwjgl.vulkan.VK10.vkCmdCopyBuffer(cmdBuf, coalescedBuf[0], stagingBuf[0], region);
        region.free();
        VulkanComputeContext.endSingleTimeCommands(cmdBuf);
        ByteBuffer mapped = VulkanComputeContext.mapBuffer(stagingBuf[1], stagingSize);
        byte[] result = new byte[N];
        mapped.get(result);
        VulkanComputeContext.unmapBuffer(stagingBuf[1]);
        callback.accept(result);
    }

    public int flatIndex(BlockPos pos) {
        int x = pos.getX() - origin.getX();
        int y = pos.getY() - origin.getY();
        int z = pos.getZ() - origin.getZ();
        return x + Lx * (y + Ly * z);
    }

    public BlockPos fromFlatIndex(int i) {
        int x = i % Lx;
        int rem = i / Lx;
        int y = rem % Ly;
        int z = rem / Ly;
        return new BlockPos(x + origin.getX(), y + origin.getY(), z + origin.getZ());
    }

    public boolean contains(BlockPos pos) {
        int x = pos.getX() - origin.getX();
        int y = pos.getY() - origin.getY();
        int z = pos.getZ() - origin.getZ();
        return x >= 0 && x < Lx && y >= 0 && y < Ly && z >= 0 && z < Lz;
    }

    public int getIslandId() { return islandId; }
    public int getLx() { return Lx; }
    public int getLy() { return Ly; }
    public int getLz() { return Lz; }
    public int getN() { return Lx * Ly * Lz; }
    public int getLmax() { return Math.max(Lx, Math.max(Ly, Lz)); }
    public BlockPos getOrigin() { return origin; }
    public boolean isAllocated() { return allocated; }

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

    public long getPcgRBuf() { return pcgRBuf != null ? pcgRBuf[0] : 0; }
    public long getPcgPBuf() { return pcgPBuf != null ? pcgPBuf[0] : 0; }
    public long getPcgApBuf() { return pcgApBuf != null ? pcgApBuf[0] : 0; }
    public long getPcgPartialBuf() { return pcgPartialBuf != null ? pcgPartialBuf[0] : 0; }
    public long getPcgReductionBuf() { return pcgReductionBuf != null ? pcgReductionBuf[0] : 0; }

    public long getHFieldBuf() { return phaseField.getHFieldBuf(); }
    public long getDFieldBuf() { return phaseField.getDFieldBuf(); }
    public long getHydrationBuf() { return phaseField.getHydrationBuf(); }
    public long getHFieldSize() { return getPhiSize(); }
    public long getDFieldSize() { return getPhiSize(); }
    public long getHydrationSize() { return getPhiSize(); }

    public int getLxL1() { return multigrid.getLxL1(); }
    public int getLyL1() { return multigrid.getLyL1(); }
    public int getLzL1() { return multigrid.getLzL1(); }
    public int getLxL2() { return multigrid.getLxL2(); }
    public int getLyL2() { return multigrid.getLyL2(); }
    public int getLzL2() { return multigrid.getLzL2(); }
    public int getN_L1() { return multigrid.getN_L1(); }
    public int getN_L2() { return multigrid.getN_L2(); }

    public long getPhiSize() { return (long) getN() * Float.BYTES; }
    public long getConductivitySize() { return (long) getN() * 6 * Float.BYTES; }
    public long getTypeSize() { return getN(); }
    public long getFailFlagsSize() { return getN(); }

    public boolean isDirty() { return dirty; }
    public void markClean() { dirty = false; }
    public void markDirty() { dirty = true; }
    public boolean isCoarseOnly() { return coarseOnly; }
    public void setCoarseOnly(boolean coarseOnly) { this.coarseOnly = coarseOnly; }

    public int getStableTickCount() { return stableTickCount; }
    public void incrementStableCount() { stableTickCount++; }
    public void resetStableCount() { stableTickCount = 0; }

    public int getLodLevel() { return lodLevel; }
    public void setLodLevel(int lod) { this.lodLevel = lod; }
    public int getWakeTicksRemaining() { return wakeTicksRemaining; }
    public void setWakeTicksRemaining(int ticks) { this.wakeTicksRemaining = ticks; }
    public void decrementWakeTicks() { if (wakeTicksRemaining > 0) wakeTicksRemaining--; }

    public int getOscillationCount() { return oscillationCount; }
    public float getPrevMaxMacroResidual() { return prevMaxMacroResidual; }
    public float[] getCachedMacroResidualsView() { return cachedMacroResiduals; }

    public long getTopologyVersion() { return topologyVersion; }
    public void incrementTopologyVersion() { topologyVersion++; }
    public java.util.Map<net.minecraft.core.BlockPos, Integer> getCachedArmMap() { return cachedArmMap; }
    public void setCachedArmMap(java.util.Map<net.minecraft.core.BlockPos, Integer> map) { cachedArmMap = map; cachedTopologyVersion = topologyVersion; }
    public java.util.Map<net.minecraft.core.BlockPos, Float> getCachedArchFactorMap() { return cachedArchFactorMap; }
    public void setCachedArchFactorMap(java.util.Map<net.minecraft.core.BlockPos, Float> map) { cachedArchFactorMap = map; }
    public boolean isBfsCacheValid() { return cachedTopologyVersion == topologyVersion && cachedArmMap != null; }

    public void clearMacroBlockResiduals() {
        if (coalescedBuf == null) return;
        long size = getMacroBlockResidualSize();
        var cmdBuf = VulkanComputeContext.beginSingleTimeCommands();
        org.lwjgl.vulkan.VK10.vkCmdFillBuffer(cmdBuf, coalescedBuf[0], macroResidualOffset, size, 0);
        VulkanComputeContext.endSingleTimeCommands(cmdBuf);
    }

    public long getMacroBlockResidualBuf() { return coalescedBuf != null ? coalescedBuf[0] : 0; }
    public int getNumMacroBlocks() {
        int B = PFSFConstants.MORTON_BLOCK_SIZE;
        return ((Lx + B - 1) / B) * ((Ly + B - 1) / B) * ((Lz + B - 1) / B);
    }
    public long getMacroBlockResidualSize() { return (long) getNumMacroBlocks() * Float.BYTES; }

    public PFSFConvergenceState getConvergence() { return convergence; }
    public PFSFMultigridBuffers getMultigrid() { return multigrid; }
    public PFSFPhaseFieldBuffers getPhaseField() { return phaseField; }
    public boolean isPCGAllocated() { return pcgAllocated; }

    public long getAggregationBuf()  { return amgAggregationBuf  != null ? amgAggregationBuf[0]  : 0; }
    public long getPWeightBuf()      { return amgPWeightBuf       != null ? amgPWeightBuf[0]       : 0; }
    public long getCoarseSrcBuf()    { return amgCoarseSrcBuf     != null ? amgCoarseSrcBuf[0]     : 0; }
    public long getCoarsePhiBuf()    { return amgCoarsePhiBuf     != null ? amgCoarsePhiBuf[0]     : 0; }

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

    public long getDamageBuf() { return phaseField.getDamageBuf(); }
    public long getHistoryBuf() { return phaseField.getHistoryBuf(); }
    public long getGcBuf() { return phaseField.getGcBuf(); }
    public long getDamageSize() { return getPhiSize(); }

    public long getRtensSize() { return getPhiSize(); }
    public long getVectorFieldSize() { return (long) getN() * 3 * Float.BYTES; }
    public long getVectorFieldBuf() { return vectorFieldBuf != null ? vectorFieldBuf[0] : 0; }

    // ─── DirectByteBuffer Wrappers for JNI ───

    private ByteBuffer wrapStaging(long offset, long size) {
        if (stagingBuf == null) return null;
        // This is tricky because we use mapBuffer. For zero-copy JNI,
        // we should ideally have persistent mapped ByteBuffers.
        // For now, we return a temporary mapped buffer for registration.
        ByteBuffer bb = VulkanComputeContext.mapBuffer(stagingBuf[1], stagingSize);
        bb.position((int)offset);
        bb.limit((int)(offset + size));
        return bb.slice().order(java.nio.ByteOrder.LITTLE_ENDIAN);
    }

    public ByteBuffer getPhiBufAsBB()          { return wrapStaging(phiOffset, getPhiSize()); }
    public ByteBuffer getSourceBufAsBB()       { return wrapStaging(sourceOffset, getPhiSize()); }
    public ByteBuffer getCondBufAsBB()         { return wrapStaging(conductivityOffset, getConductivitySize()); }
    public ByteBuffer getTypeBufAsBB()         { return wrapStaging(typeOffset, getTypeSize()); }
    public ByteBuffer getRcompBufAsBB()        { return wrapStaging(rcompOffset, getPhiSize()); }
    public ByteBuffer getRtensBufAsBB()        { return wrapStaging(rtensOffset, getPhiSize()); }
    public ByteBuffer getMaxPhiBufAsBB()       { return wrapStaging(maxPhiOffset, getPhiSize()); }

    public void ensureVectorFieldAllocated() {
        if (vectorFieldAllocated) return;
        long size = (long) getN() * 3 * Float.BYTES;
        vectorFieldBuf = VulkanComputeContext.allocateDeviceBuffer(size, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        if (vectorFieldBuf != null) vectorFieldAllocated = true;
    }

    public void swapPhi() {
        long temp = phiOffset;
        phiOffset = phiPrevOffset;
        phiPrevOffset = temp;
    }

    public void acquire() { refCount.incrementAndGet(); }
    public boolean release() {
        if (refCount.decrementAndGet() <= 0) {
            freeGpuResources();
            return true;
        }
        return false;
    }

    private static long alignToDevice(long offset) {
        long alignment = VulkanComputeContext.getMinBufferAlignment();
        return (offset + (alignment - 1)) & ~(alignment - 1);
    }

    private static int ceilDiv(int a, int b) { return (a + b - 1) / b; }
}
