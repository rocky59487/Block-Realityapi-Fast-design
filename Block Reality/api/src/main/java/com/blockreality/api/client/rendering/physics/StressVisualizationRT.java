package com.blockreality.api.client.rendering.physics;

import com.blockreality.api.client.ClientStressCache;
import com.blockreality.api.client.rendering.vulkan.VkMemoryAllocator;
import net.minecraft.core.BlockPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Map;

import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * 應力視覺化 RT 橋接器 — 計劃 §3-F（修訂版：空間雜湊 + 雙緩衝）。
 *
 * <p>將 {@link ClientStressCache} 的應力數據打包成 Vulkan SSBO，
 * 供 closesthit shader 在路徑追蹤時以 O(1) 查找並混合應力熱圖色。
 *
 * <h3>SSBO 格式（std430）</h3>
 * <pre>
 * layout(set=0, binding=4, std430) readonly buffer StressBuffer {
 *     int   count;
 *     int   _pad0, _pad1, _pad2;
 *     // HASH_SLOTS × slot：
 *     //   int   bX;  (HASH_EMPTY = INT_MIN 表空槽)
 *     //   int   bY, bZ;
 *     //   float stress;
 *     //   float _pad[4];  (32 bytes/slot total)
 * } sb;
 * </pre>
 *
 * <h3>雜湊策略</h3>
 * HASH_SLOTS=8192，Knuth 乘法雜湊，線性探測 MAX_PROBES=16，
 * 負載因子 ≤ 0.5，空槽哨兵 bX == Integer.MIN_VALUE。
 *
 * <h3>雙緩衝</h3>
 * ssboBuffers[readIdx] 給 GPU；ssboBuffers[writeIdx] 給 CPU。
 * 每幀 uploadIfDirty() 後呼叫 swapBuffers()。
 *
 * @see ClientStressCache
 * @see com.blockreality.api.client.rendering.vulkan.VkRTPipeline
 */
@OnlyIn(Dist.CLIENT)
public class StressVisualizationRT {

    private static final Logger LOG = LoggerFactory.getLogger("BR-StressRT");

    // ─── SSBO layout 常數 ───────────────────────────────────────────────
    private static final int HEADER_BYTES = 16;
    private static final int ENTRY_BYTES  = 32;
    private static final int HASH_SLOTS   = 8192;
    private static final int MAX_PROBES   = 16;
    private static final int HASH_EMPTY   = Integer.MIN_VALUE;
    private static final int SSBO_BYTES   = HEADER_BYTES + HASH_SLOTS * ENTRY_BYTES;

    // ─── Vulkan 資源（雙緩衝） ────────────────────────────────────────────
    private VkMemoryAllocator allocator        = null;
    private final long[]      ssboBuffers      = {VK_NULL_HANDLE, VK_NULL_HANDLE};
    private final long[]      ssboAllocations  = {0L, 0L};
    private volatile int      readIdx          = 0;
    private volatile int      writeIdx         = 1;

    // ─── dirty 追蹤 ───────────────────────────────────────────────────────
    private int  lastCacheSize = -1;
    private long lastUploadMs  = 0;
    private static final long UPLOAD_INTERVAL_MS = 200;

    // ─── 單例 ─────────────────────────────────────────────────────────────
    private static StressVisualizationRT instance;

    public static StressVisualizationRT getInstance() {
        if (instance == null) instance = new StressVisualizationRT();
        return instance;
    }

    private StressVisualizationRT() {}

    // ═══ 生命週期 ════════════════════════════════════════════════════════

    public boolean init(VkMemoryAllocator vkAllocator) {
        this.allocator = vkAllocator;

        for (int i = 0; i < 2; i++) {
            long[] result = vkAllocator.allocateBuffer(
                SSBO_BYTES,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                VMA_MEMORY_USAGE_AUTO,
                VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT |
                VMA_ALLOCATION_CREATE_MAPPED_BIT);

            if (result == null) {
                LOG.error("StressVisualizationRT: SSBO[{}] allocation failed", i);
                cleanup();
                return false;
            }
            ssboBuffers[i]     = result[0];
            ssboAllocations[i] = result[1];
            clearBuffer(vkAllocator, ssboAllocations[i]);
        }

        readIdx  = 0;
        writeIdx = 1;

        LOG.info("StressVisualizationRT: double-buffered SSBO allocated (2 x {} KB, {} slots)",
            SSBO_BYTES / 1024, HASH_SLOTS);
        return true;
    }

    private void clearBuffer(VkMemoryAllocator vkAllocator, long allocation) {
        ByteBuffer buf = MemoryUtil.memAlloc(SSBO_BYTES);
        try {
            buf.rewind();
            buf.putInt(0); buf.putInt(0); buf.putInt(0); buf.putInt(0);
            for (int s = 0; s < HASH_SLOTS; s++) {
                buf.putInt(HASH_EMPTY);
                buf.putInt(0);
                buf.putInt(0);
                buf.putFloat(0.0f);
                buf.putFloat(0.0f); buf.putFloat(0.0f);
                buf.putFloat(0.0f); buf.putFloat(0.0f);
            }
            buf.flip();
            byte[] bytes = new byte[SSBO_BYTES];
            buf.get(bytes);
            vkAllocator.writeToBuffer(allocation, bytes);
        } finally {
            MemoryUtil.memFree(buf);
        }
    }

    public void cleanup() {
        if (allocator != null) {
            for (int i = 0; i < 2; i++) {
                if (ssboBuffers[i] != VK_NULL_HANDLE) {
                    allocator.freeBuffer(ssboBuffers[i]);
                    ssboBuffers[i]     = VK_NULL_HANDLE;
                    ssboAllocations[i] = 0L;
                }
            }
        }
        allocator = null;
        instance  = null;
        LOG.info("StressVisualizationRT: cleanup complete");
    }

    // ═══ 每幀更新 ════════════════════════════════════════════════════════

    public void uploadIfDirty() {
        if (ssboBuffers[writeIdx] == VK_NULL_HANDLE || allocator == null) return;

        Map<BlockPos, Float> cache   = ClientStressCache.getCache();
        int                  current = cache.size();
        long                 now     = System.currentTimeMillis();

        boolean dirty  = (current != lastCacheSize);
        boolean timeOk = (now - lastUploadMs) >= UPLOAD_INTERVAL_MS;

        if (!dirty && !timeOk) return;
        if (current == 0 && lastCacheSize == 0) return;

        uploadStressData(cache);
        lastCacheSize = current;
        lastUploadMs  = now;
    }

    private void uploadStressData(Map<BlockPos, Float> cache) {
        ByteBuffer buf = MemoryUtil.memAlloc(SSBO_BYTES);
        try {
            buf.rewind();

            final int COUNT_OFFSET = 0;
            buf.putInt(0); buf.putInt(0); buf.putInt(0); buf.putInt(0);

            final int SLOTS_OFFSET = HEADER_BYTES;
            for (int s = 0; s < HASH_SLOTS; s++) {
                int off = SLOTS_OFFSET + s * ENTRY_BYTES;
                buf.putInt  (off,      HASH_EMPTY);
                buf.putInt  (off + 4,  0);
                buf.putInt  (off + 8,  0);
                buf.putFloat(off + 12, 0.0f);
                buf.putFloat(off + 16, 0.0f);
                buf.putFloat(off + 20, 0.0f);
                buf.putFloat(off + 24, 0.0f);
                buf.putFloat(off + 28, 0.0f);
            }

            int written = 0;
            for (Map.Entry<BlockPos, Float> e : cache.entrySet()) {
                BlockPos pos    = e.getKey();
                float    stress = e.getValue();
                int      hash   = knuthHash(pos.getX(), pos.getY(), pos.getZ());

                for (int probe = 0; probe < MAX_PROBES; probe++) {
                    int slot = (hash + probe) & (HASH_SLOTS - 1);
                    int off  = SLOTS_OFFSET + slot * ENTRY_BYTES;

                    if (buf.getInt(off) == HASH_EMPTY) {
                        buf.putInt  (off,      pos.getX());
                        buf.putInt  (off + 4,  pos.getY());
                        buf.putInt  (off + 8,  pos.getZ());
                        buf.putFloat(off + 12, stress);
                        written++;
                        break;
                    }
                }
            }

            buf.putInt(COUNT_OFFSET, written);

            byte[] bytes = new byte[SSBO_BYTES];
            buf.position(0);
            buf.limit(SSBO_BYTES);
            buf.get(bytes);
            allocator.writeToBuffer(ssboAllocations[writeIdx], bytes);

            LOG.trace("StressRT upload: {} entries ({} slots)", written, HASH_SLOTS);
        } finally {
            MemoryUtil.memFree(buf);
        }
    }

    private static int knuthHash(int x, int y, int z) {
        int h = x;
        h = h * 31 + y;
        h = h * 31 + z;
        h *= 0x9E3779B9;
        return h & (HASH_SLOTS - 1);
    }

    // ═══ Getters / 幀同步 ════════════════════════════════════════════════

    public long getSSBOHandle() { return ssboBuffers[readIdx]; }

    public void swapBuffers() {
        int prev = readIdx;
        readIdx  = writeIdx;
        writeIdx = prev;
    }

    public int     getEntryCount() { return Math.min(ClientStressCache.getCache().size(), HASH_SLOTS / 2); }
    public boolean isReady()       { return ssboBuffers[readIdx] != VK_NULL_HANDLE; }
}
