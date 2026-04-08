package com.blockreality.api.physics.solver;

import net.minecraft.core.BlockPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 通用擴散區域註冊表 — 每個物理域一個實例。
 *
 * <p>管理活動的模擬區域，按玩家距離啟動/休眠。
 * 各物理域的 Engine 持有自己的 registry 實例。
 */
@ThreadSafe
public class DiffusionRegionRegistry {

    private static final Logger LOGGER = LogManager.getLogger("BR-DiffRegion");

    private final String domainId;
    private final Map<Long, DiffusionRegion> regions = new ConcurrentHashMap<>();
    private final AtomicInteger nextRegionId = new AtomicInteger(1);

    public DiffusionRegionRegistry(String domainId) {
        this.domainId = domainId;
    }

    @Nonnull
    public DiffusionRegion getOrCreateRegion(@Nonnull BlockPos pos, int regionSize) {
        long key = regionKey(pos, regionSize);
        return regions.computeIfAbsent(key, k -> {
            int ox = Math.floorDiv(pos.getX(), regionSize) * regionSize;
            int oy = Math.floorDiv(pos.getY(), regionSize) * regionSize;
            int oz = Math.floorDiv(pos.getZ(), regionSize) * regionSize;
            DiffusionRegion r = new DiffusionRegion(
                nextRegionId.getAndIncrement(), ox, oy, oz, regionSize, regionSize, regionSize);
            LOGGER.debug("[{}] Created region #{} at ({},{},{})", domainId, r.getRegionId(), ox, oy, oz);
            return r;
        });
    }

    @Nullable
    public DiffusionRegion getRegion(@Nonnull BlockPos pos, int regionSize) {
        return regions.get(regionKey(pos, regionSize));
    }

    public Collection<DiffusionRegion> getActiveRegions() {
        return Collections.unmodifiableCollection(regions.values());
    }

    public int getRegionCount() { return regions.size(); }

    public void clear() {
        regions.clear();
        LOGGER.info("[{}] Cleared all regions", domainId);
    }

    private static long regionKey(BlockPos pos, int size) {
        int rx = Math.floorDiv(pos.getX(), size);
        int ry = Math.floorDiv(pos.getY(), size);
        int rz = Math.floorDiv(pos.getZ(), size);
        return ((long) rx & 0x1FFFFF) << 42 | ((long) ry & 0x1FFFFF) << 21 | ((long) rz & 0x1FFFFF);
    }
}
