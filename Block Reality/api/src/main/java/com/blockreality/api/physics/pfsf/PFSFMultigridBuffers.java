package com.blockreality.api.physics.pfsf;

import static org.lwjgl.vulkan.VK10.*;

/**
 * PFSF 多網格粗網格 Buffer — 從 PFSFIslandBuffer 提取。
 *
 * <p>管理 L1（2× 降採樣）和 L2（4× 降採樣）的 GPU buffer。
 * W-Cycle 多網格需要 L0→L1→L2 三層；V-Cycle 只用 L0→L1。</p>
 *
 * <p>P1 重構：原本 10 個 buffer handle + 6 個維度欄位散在 PFSFIslandBuffer，
 * 現集中到此類。</p>
 */
public final class PFSFMultigridBuffers {

    // ─── L1 (2× coarse) ───
    private int Lx_L1, Ly_L1, Lz_L1;
    private long[] phiL1Buf, phiPrevL1Buf, sourceL1Buf, conductivityL1Buf, typeL1Buf;

    // ─── L2 (4× coarse) ───
    private int Lx_L2, Ly_L2, Lz_L2;
    private long[] phiL2Buf, phiPrevL2Buf, sourceL2Buf, conductivityL2Buf, typeL2Buf;

    private boolean allocated = false;

    /**
     * 分配 L1 + L2 粗網格 buffer。
     */
    public void allocate(int fineLx, int fineLy, int fineLz) {
        if (allocated) return;

        Lx_L1 = ceilDiv(fineLx, 2);
        Ly_L1 = ceilDiv(fineLy, 2);
        Lz_L1 = ceilDiv(fineLz, 2);

        Lx_L2 = ceilDiv(Lx_L1, 2);
        Ly_L2 = ceilDiv(Ly_L1, 2);
        Lz_L2 = ceilDiv(Lz_L1, 2);

        int N1 = Lx_L1 * Ly_L1 * Lz_L1;
        int N2 = Lx_L2 * Ly_L2 * Lz_L2;

        int usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                | VK_BUFFER_USAGE_TRANSFER_SRC_BIT
                | VK_BUFFER_USAGE_TRANSFER_DST_BIT;

        phiL1Buf = VulkanComputeContext.allocateDeviceBuffer((long) N1 * Float.BYTES, usage);
        phiPrevL1Buf = VulkanComputeContext.allocateDeviceBuffer((long) N1 * Float.BYTES, usage);
        sourceL1Buf = VulkanComputeContext.allocateDeviceBuffer((long) N1 * Float.BYTES, usage);
        conductivityL1Buf = VulkanComputeContext.allocateDeviceBuffer((long) N1 * 6 * Float.BYTES, usage);
        typeL1Buf = VulkanComputeContext.allocateDeviceBuffer(N1, usage);

        phiL2Buf = VulkanComputeContext.allocateDeviceBuffer((long) N2 * Float.BYTES, usage);
        phiPrevL2Buf = VulkanComputeContext.allocateDeviceBuffer((long) N2 * Float.BYTES, usage);
        sourceL2Buf = VulkanComputeContext.allocateDeviceBuffer((long) N2 * Float.BYTES, usage);
        conductivityL2Buf = VulkanComputeContext.allocateDeviceBuffer((long) N2 * 6 * Float.BYTES, usage);
        typeL2Buf = VulkanComputeContext.allocateDeviceBuffer(N2, usage);

        allocated = true;
    }

    public void free() {
        if (!allocated) return;
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
        allocated = false;
    }

    // ─── Phi Swap ───

    public void swapPhiL1() {
        long[] temp = phiL1Buf;
        phiL1Buf = phiPrevL1Buf;
        phiPrevL1Buf = temp;
    }

    public void swapPhiL2() {
        long[] temp = phiL2Buf;
        phiL2Buf = phiPrevL2Buf;
        phiPrevL2Buf = temp;
    }

    // ─── Getters ───

    public boolean isAllocated() { return allocated; }
    public int getLxL1() { return Lx_L1; }
    public int getLyL1() { return Ly_L1; }
    public int getLzL1() { return Lz_L1; }
    public int getLxL2() { return Lx_L2; }
    public int getLyL2() { return Ly_L2; }
    public int getLzL2() { return Lz_L2; }
    public int getN_L1() { return Lx_L1 * Ly_L1 * Lz_L1; }
    public int getN_L2() { return Lx_L2 * Ly_L2 * Lz_L2; }

    public long getPhiL1Buf() { return safe(phiL1Buf); }
    public long getPhiPrevL1Buf() { return safe(phiPrevL1Buf); }
    public long getSourceL1Buf() { return safe(sourceL1Buf); }
    public long getConductivityL1Buf() { return safe(conductivityL1Buf); }
    public long getTypeL1Buf() { return safe(typeL1Buf); }
    public long getPhiL2Buf() { return safe(phiL2Buf); }
    public long getPhiPrevL2Buf() { return safe(phiPrevL2Buf); }
    public long getSourceL2Buf() { return safe(sourceL2Buf); }
    public long getConductivityL2Buf() { return safe(conductivityL2Buf); }
    public long getTypeL2Buf() { return safe(typeL2Buf); }

    // ─── Helpers ───

    private static long safe(long[] pair) { return pair != null ? pair[0] : 0; }
    private static void freeBufferPair(long[] pair) {
        if (pair != null && pair.length == 2) VulkanComputeContext.freeBuffer(pair[0], pair[1]);
    }
    private static int ceilDiv(int a, int b) { return (a + b - 1) / b; }
}
