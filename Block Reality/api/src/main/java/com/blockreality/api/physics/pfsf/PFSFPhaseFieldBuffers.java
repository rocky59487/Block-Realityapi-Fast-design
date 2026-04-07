package com.blockreality.api.physics.pfsf;

import static org.lwjgl.vulkan.VK10.*;

/**
 * PFSF 相場斷裂 Buffer — 從 PFSFIslandBuffer 提取。
 *
 * <p>管理 Ambati 2015 混合相場的 GPU buffer：
 * <ul>
 *   <li>{@code hField[N]} — 最大應變能歷史（不可逆遞增）</li>
 *   <li>{@code dField[N]} — 損傷場 d ∈ [0,1]，d>0.95 → 斷裂</li>
 *   <li>{@code hydration[N]} — 養護度 ∈ [0,1]，影響 G_c 尺度</li>
 * </ul>
 *
 * <p>v3 重構：移除 3 個冗餘 backward-compat buffer（damage/history/gc），
 * 改用 getter 委託。每 island 節省 12N bytes VRAM。</p>
 *
 * <p>P1 重構：原本 6 個 buffer handle + 3 個 alias 散在 PFSFIslandBuffer。</p>
 */
public final class PFSFPhaseFieldBuffers {

    // ─── v2.1 buffers（唯一實體） ───
    private long[] hFieldBuf;       // max strain energy history
    private long[] dFieldBuf;       // crack phase field
    private long[] hydrationBuf;    // curing degree

    private boolean allocated = false;

    /**
     * 分配相場 + 養護度 buffer。
     *
     * @param N 體素總數
     */
    public void allocate(int N) {
        if (allocated) return;

        int usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                | VK_BUFFER_USAGE_TRANSFER_SRC_BIT
                | VK_BUFFER_USAGE_TRANSFER_DST_BIT;

        long floatN = (long) N * Float.BYTES;

        hFieldBuf    = VulkanComputeContext.allocateDeviceBuffer(floatN, usage);
        dFieldBuf    = VulkanComputeContext.allocateDeviceBuffer(floatN, usage);
        hydrationBuf = VulkanComputeContext.allocateDeviceBuffer(floatN, usage);

        allocated = true;
    }

    public void free() {
        if (!allocated) return;
        freeBufferPair(hFieldBuf);
        freeBufferPair(dFieldBuf);
        freeBufferPair(hydrationBuf);
        hFieldBuf = null;
        dFieldBuf = null;
        hydrationBuf = null;
        allocated = false;
    }

    // ─── Getters ───

    public boolean isAllocated() { return allocated; }

    public long getHFieldBuf()    { return safe(hFieldBuf); }
    public long getDFieldBuf()    { return safe(dFieldBuf); }
    public long getHydrationBuf() { return safe(hydrationBuf); }

    // v2 Phase C backward-compat aliases（v3: 委託到實體 buffer，不再分配獨立記憶體）
    public long getDamageBuf()    { return getDFieldBuf(); }
    public long getHistoryBuf()   { return getHFieldBuf(); }
    public long getGcBuf()        { return getHydrationBuf(); }

    // ─── Helpers ───

    private static long safe(long[] pair) { return pair != null ? pair[0] : 0; }
    private static void freeBufferPair(long[] pair) {
        if (pair != null && pair.length == 2) VulkanComputeContext.freeBuffer(pair[0], pair[1]);
    }
}
