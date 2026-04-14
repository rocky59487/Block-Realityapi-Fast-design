package com.blockreality.api.physics.fluid;

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
 * 流體區域註冊表 — 管理所有活動的流體模擬區域。
 *
 * <p>流體區域是獨立的矩形體積，不綁定結構 island。
 * 每個區域以其原點 (minX, minY, minZ) 為 key，按玩家距離啟動/休眠。
 *
 * <p>類比 {@code StructureIslandRegistry}，但流體區域的生命週期
 * 由流體源放置/移除驅動，而非方塊連通性。
 */
@ThreadSafe
public class FluidRegionRegistry {

    private static final Logger LOGGER = LogManager.getLogger("BR-FluidRegion");

    private static final FluidRegionRegistry INSTANCE = new FluidRegionRegistry();

    private final Map<Long, FluidRegion> regions = new ConcurrentHashMap<>();
    private final AtomicInteger nextRegionId = new AtomicInteger(1);

    private FluidRegionRegistry() {}

    public static FluidRegionRegistry getInstance() { return INSTANCE; }

    /**
     * 取得或建立包含指定位置的流體區域。
     *
     * <p>如果 pos 不在任何現有區域內，會建立一個新區域。
     * 區域大小由 {@code BRConfig.getFluidMaxRegionSize()} 決定。
     *
     * @param pos 方塊位置
     * @param regionSize 區域每軸方塊數
     * @return 包含 pos 的流體區域
     */
    @Nonnull
    public FluidRegion getOrCreateRegion(@Nonnull BlockPos pos, int regionSize) {
        long key = regionKey(pos, regionSize);
        return regions.computeIfAbsent(key, k -> {
            int originX = Math.floorDiv(pos.getX(), regionSize) * regionSize;
            int originY = Math.floorDiv(pos.getY(), regionSize) * regionSize;
            int originZ = Math.floorDiv(pos.getZ(), regionSize) * regionSize;
            FluidRegion region = new FluidRegion(
                nextRegionId.getAndIncrement(),
                originX, originY, originZ,
                regionSize, regionSize, regionSize
            );
            LOGGER.debug("[BR-FluidRegion] Created region #{} at ({},{},{}), size {}³",
                region.getRegionId(), originX, originY, originZ, regionSize);
            return region;
        });
    }

    /**
     * 查詢包含指定位置的流體區域。
     *
     * @param pos 方塊位置
     * @param regionSize 區域每軸方塊數
     * @return 對應的區域，不存在則返回 null
     */
    @Nullable
    public FluidRegion getRegion(@Nonnull BlockPos pos, int regionSize) {
        long key = regionKey(pos, regionSize);
        return regions.get(key);
    }

    /**
     * 移除指定區域（休眠或清理時呼叫）。
     *
     * @param regionId 區域 ID
     * @return 是否成功移除
     */
    public boolean removeRegion(int regionId) {
        boolean removed = regions.values().removeIf(r -> r.getRegionId() == regionId);
        if (removed) {
            LOGGER.debug("[BR-FluidRegion] Removed region #{}", regionId);
        }
        return removed;
    }

    /** 取得所有活動區域（唯讀） */
    public Collection<FluidRegion> getActiveRegions() {
        return Collections.unmodifiableCollection(regions.values());
    }

    /** 活動區域數量 */
    public int getRegionCount() {
        return regions.size();
    }

    /**
     * 按 regionId 查找流體區域。
     *
     * @param regionId 區域 ID
     * @return 對應的區域，不存在返回 null
     */
    @Nullable
    public FluidRegion getRegionById(int regionId) {
        for (FluidRegion r : regions.values()) {
            if (r.getRegionId() == regionId) return r;
        }
        return null;
    }

    /** 清除所有區域（world unload 時呼叫） */
    public void clear() {
        regions.clear();
        LOGGER.info("[BR-FluidRegion] Cleared all fluid regions");
    }

    /**
     * 計算包含 pos 的區域 key。
     * 使用偏移後的 packed long 作為唯一識別。
     *
     * <p>原實作直接對負 rx/ry/rz 做位元遮罩（& 0x1FFFFF），
     * 當座標為負時符號位延伸會污染高位元，導致負/正座標的 key 碰撞。
     * 修正：加入偏移使所有值為非負，再遮罩；X/Z 偏移 2²¹ 支援 ±30M 世界邊界
     * 在 regionSize≥16 下不溢位（max |rx|=1,875,000 < 2,097,151）。
     */
    private static long regionKey(BlockPos pos, int regionSize) {
        int rx = Math.floorDiv(pos.getX(), regionSize);
        int ry = Math.floorDiv(pos.getY(), regionSize);
        int rz = Math.floorDiv(pos.getZ(), regionSize);
        // 偏移後確保非負（X/Z 偏移 2^21, Y 偏移 2^9 足以容納 Minecraft 高度範圍）
        long kx = ((long) rx + (1L << 21)) & 0x3FFFFFL; // 22 bits
        long ky = ((long) ry + (1L <<  9)) & 0x3FFL;    // 10 bits
        long kz = ((long) rz + (1L << 21)) & 0x3FFFFFL; // 22 bits
        return (kx << 32) | (ky << 22) | kz;
    }
}
