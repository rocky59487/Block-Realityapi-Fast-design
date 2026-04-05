package com.blockreality.api.physics;

import net.minecraft.core.BlockPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nonnull;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 全局結構 epoch 計數器與髒區域追蹤。
 *
 * <p>PFSF GPU 引擎透過 epoch 判斷哪些 island 需要重新計算。
 * 每次方塊放置/破壞時 epoch 遞增。
 */
public class ConnectivityCache {

    private static final Logger LOGGER = LogManager.getLogger("BlockReality/Physics");

    /** 全局結構 epoch — 每次結構變動遞增 */
    private static final AtomicLong globalEpoch = new AtomicLong(0);

    /** 髒區域集合（chunk 粒度） */
    private static final Set<Long> dirtyRegions = ConcurrentHashMap.newKeySet();

    /**
     * 通知結構變動 — 在 BlockPlaceEvent / BlockBreakEvent 觸發。
     */
    public static void notifyStructureChanged(@Nonnull BlockPos pos) {
        globalEpoch.incrementAndGet();
        long regionKey = chunkKey(pos.getX() >> 4, pos.getZ() >> 4);
        dirtyRegions.add(regionKey);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                dirtyRegions.add(chunkKey((pos.getX() >> 4) + dx, (pos.getZ() >> 4) + dz));
            }
        }
    }

    /** 取得目前結構 epoch */
    public static long getStructureEpoch() { return globalEpoch.get(); }

    /** 清除所有快取（世界重載時） */
    public static void clearCache() {
        dirtyRegions.clear();
        LOGGER.info("ConnectivityCache cleared");
    }

    /**
     * 驅逐過期的髒區域。
     * @return 被驅逐的條目數
     */
    public static int evictStaleEntries() {
        int size = dirtyRegions.size();
        if (size > 256) {
            dirtyRegions.clear();
            LOGGER.debug("[AD-7] Evicted {} stale dirty regions", size);
            return size;
        }
        return 0;
    }

    /** 取得快取統計 */
    public static String getCacheStats() {
        return String.format("epoch=%d, dirty=%d", globalEpoch.get(), dirtyRegions.size());
    }

    static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private ConnectivityCache() {}
}
