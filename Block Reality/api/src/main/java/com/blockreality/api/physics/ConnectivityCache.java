package com.blockreality.api.physics;

import com.blockreality.api.block.RBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nonnull;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Issue#9 重構：從 BFSConnectivityAnalyzer 提取的快取/epoch 管理層。
 *
 * <p>負責：
 * <ul>
 *   <li>全局結構 epoch 計數器（long，每次結構變動遞增）</li>
 *   <li>LRU 快取管理（{@link LinkedHashMap} accessOrder=true）</li>
 *   <li>Dirty region 追蹤（chunk 粒度）</li>
 *   <li>CAS 保護的連通分量重建</li>
 *   <li>快取驅逐（epoch 差距過大的條目）</li>
 * </ul>
 *
 * @see BFSConnectivityAnalyzer
 */
public class ConnectivityCache {

    private static final Logger LOGGER = LogManager.getLogger("BlockReality/Physics");

    // ═══════════════════════════════════════════════════════
    //  Epoch / Dirty Flag 增量更新機制
    // ═══════════════════════════════════════════════════════

    /** 全局結構 epoch — 每次結構變動遞增（改用 long 避免溢位） */
    private static final AtomicLong globalEpoch = new AtomicLong(0);

    /** CAS 保護：防止並發重建連通分量 */
    private static final AtomicBoolean rebuildingComponent = new AtomicBoolean(false);

    /** Per-component 細粒度鎖 */
    private static final ConcurrentHashMap<Integer, ReentrantLock> componentLocks = new ConcurrentHashMap<>();

    /** LRU 快取（accessOrder=true），上限 MAX_CACHE_SIZE 條目 */
    private static final int MAX_CACHE_SIZE = 1024;
    private static final Map<Long, BFSConnectivityAnalyzer.CachedResult> resultCache =
        Collections.synchronizedMap(
            new LinkedHashMap<Long, BFSConnectivityAnalyzer.CachedResult>(
                    MAX_CACHE_SIZE + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<Long, BFSConnectivityAnalyzer.CachedResult> eldest) {
                    return size() > MAX_CACHE_SIZE;
                }
            }
        );

    /** 髒區域集合（chunk 粒度） */
    private static final Set<Long> dirtyRegions = ConcurrentHashMap.newKeySet();

    /** 快取條目的最大存活 epoch 差距 */
    private static final int EPOCH_EVICTION_THRESHOLD = 64;

    // ═══════════════════════════════════════════════════════
    //  公開 API
    // ═══════════════════════════════════════════════════════

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

    /**
     * 帶快取的查詢 — epoch 未變且區域不髒時直接返回快取結果。
     */
    @Nonnull
    public static BFSConnectivityAnalyzer.PhysicsResult findUnsupportedBlocksCached(
            @Nonnull RWorldSnapshot snapshot, int scanMargin) {
        long regionKey = snapshotKey(
            snapshot.getStartX() >> 4, snapshot.getStartZ() >> 4,
            snapshot.getStartY(), snapshot.getSizeY());
        BFSConnectivityAnalyzer.CachedResult cached = resultCache.get(regionKey);

        if (cached != null && cached.isValid() && !dirtyRegions.contains(regionKey)) {
            LOGGER.debug("UnionFind cache hit for region {} (epoch={})", regionKey, cached.epoch());
            return cached.result();
        }

        BFSConnectivityAnalyzer.PhysicsResult result =
            BFSConnectivityAnalyzer.findUnsupportedBlocks(snapshot, scanMargin);
        resultCache.put(regionKey, new BFSConnectivityAnalyzer.CachedResult(result, globalEpoch.get()));
        dirtyRegions.remove(regionKey);
        return result;
    }

    /** 取得目前結構 epoch */
    public static long getStructureEpoch() { return globalEpoch.get(); }

    /** 清除所有快取（世界重載時） */
    public static void clearCache() {
        resultCache.clear();
        dirtyRegions.clear();
        LOGGER.info("UnionFind cache cleared");
    }

    /**
     * 驅逐過期的快取條目。
     * @return 被驅逐的條目數
     */
    public static int evictStaleEntries() {
        long currentEpoch = globalEpoch.get();
        int evicted = 0;
        synchronized (resultCache) {
            var iterator = resultCache.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                long entryEpoch = entry.getValue().epoch();
                if (currentEpoch - entryEpoch > EPOCH_EVICTION_THRESHOLD) {
                    iterator.remove();
                    evicted++;
                }
            }
        }
        if (evicted > 0) {
            LOGGER.debug("[AD-7] Evicted {} stale cache entries (threshold={}), epoch={}, cacheSize={}",
                evicted, EPOCH_EVICTION_THRESHOLD, currentEpoch, resultCache.size());
        }
        return evicted;
    }

    /** 取得快取統計 */
    public static String getCacheStats() {
        return String.format("epoch=%d, cached=%d, dirty=%d",
            globalEpoch.get(), resultCache.size(), dirtyRegions.size());
    }

    // ═══════════════════════════════════════════════════════
    //  CAS 保護的連通分量重建
    // ═══════════════════════════════════════════════════════

    public static BFSConnectivityAnalyzer.PhysicsResult rebuildConnectedComponents() {
        return rebuildConnectedComponents(null);
    }

    public static BFSConnectivityAnalyzer.PhysicsResult rebuildConnectedComponents(ServerLevel level) {
        if (!rebuildingComponent.compareAndSet(false, true)) {
            LOGGER.debug("[UnionFind] Rebuild already in progress, skipping concurrent rebuild");
            return null;
        }

        try {
            long t0 = System.nanoTime();
            LOGGER.debug("[UnionFind] CAS acquired, starting rebuild");

            Set<BlockPos> rblocks = new HashSet<>();
            List<BlockPos> anchorQueue = new ArrayList<>();

            if (level != null) {
                try {
                    var chunkMap = level.getChunkSource().chunkMap;
                    java.lang.reflect.Method getChunksMethod =
                        chunkMap.getClass().getDeclaredMethod("getChunks");
                    getChunksMethod.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    Iterable<net.minecraft.server.level.ChunkHolder> holders =
                        (Iterable<net.minecraft.server.level.ChunkHolder>) getChunksMethod.invoke(chunkMap);

                    for (net.minecraft.server.level.ChunkHolder holder : holders) {
                        net.minecraft.world.level.chunk.LevelChunk chunk = holder.getTickingChunk();
                        if (chunk == null) continue;

                        for (Map.Entry<BlockPos, net.minecraft.world.level.block.entity.BlockEntity> entry
                                : chunk.getBlockEntities().entrySet()) {
                            if (entry.getValue() instanceof RBlockEntity rbe) {
                                BlockPos pos = entry.getKey();
                                rblocks.add(pos);
                                BlockPos below = pos.below();
                                if (rbe.isAnchored()
                                    || level.getBlockState(below).isSolidRender(level, below)) {
                                    anchorQueue.add(pos);
                                }
                            }
                        }
                    }
                } catch (ReflectiveOperationException ex) {
                    LOGGER.warn("[UnionFind] Reflection failed on ChunkMap.getChunks(), using empty set", ex);
                }
            }

            if (rblocks.isEmpty()) {
                return new BFSConnectivityAnalyzer.PhysicsResult(
                    Set.of(), 0, 0, 0, System.nanoTime() - t0, false, false);
            }

            Set<BlockPos> supported = new HashSet<>(anchorQueue);
            Deque<BlockPos> queue = new ArrayDeque<>(anchorQueue);
            int visitCount = 0;
            while (!queue.isEmpty()) {
                BlockPos current = queue.poll();
                visitCount++;
                for (Direction dir : Direction.values()) {
                    BlockPos neighbor = current.relative(dir);
                    if (rblocks.contains(neighbor) && supported.add(neighbor)) {
                        queue.add(neighbor);
                    }
                }
            }

            Set<BlockPos> unsupported = new HashSet<>();
            for (BlockPos pos : rblocks) {
                if (!supported.contains(pos)) {
                    unsupported.add(pos);
                }
            }

            long elapsed = System.nanoTime() - t0;
            globalEpoch.incrementAndGet();

            LOGGER.debug("[UnionFind] Rebuild done: {} rblocks, {} anchors, {} unsupported, {} visits in {:.2f}ms",
                rblocks.size(), anchorQueue.size(), unsupported.size(), visitCount,
                elapsed / 1_000_000.0);

            return new BFSConnectivityAnalyzer.PhysicsResult(
                unsupported, rblocks.size(), anchorQueue.size(),
                visitCount, elapsed, false, false);
        } finally {
            rebuildingComponent.set(false);
        }
    }

    // ═══════════════════════════════════════════════════════
    //  工具方法
    // ═══════════════════════════════════════════════════════

    static ReentrantLock getComponentLock(int componentId) {
        return componentLocks.computeIfAbsent(componentId, k -> new ReentrantLock());
    }

    static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    static long snapshotKey(int cx, int cz, int startY, int sizeY) {
        long h = 0xcbf29ce484222325L;
        h ^= cx;   h *= 0x100000001b3L;
        h ^= cz;   h *= 0x100000001b3L;
        h ^= startY; h *= 0x100000001b3L;
        h ^= sizeY;  h *= 0x100000001b3L;
        return h;
    }

    private ConnectivityCache() {}
}
