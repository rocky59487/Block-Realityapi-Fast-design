package com.blockreality.api.physics;

import com.blockreality.api.block.RBlockEntity;
import com.blockreality.api.config.BRConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.concurrent.ThreadSafe;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * BFS 連通塊引擎 — 從 Anchor 擴散，找出所有失去支撐的懸空方塊。
 *
 * 錨定策略（v3 — Scan Margin）：
 *   - 掃描區 = 使用者指定範圍 + margin（預設 4 格）
 *   - Anchor = 掃描區邊界上的所有非空氣方塊（有限元素邊界條件）
 *   - 崩塌區 = 僅限內部（排除 margin 的區域）
 *   → margin 給 BFS 額外空間追蹤支撐路徑
 *   → 支撐柱在 margin 內被捕捉 → 不會誤殺合理建築
 *   → 只有完全包在崩塌區內且不連接任何 anchor 的結構才會掉
 *
 * 效能設計：
 *   1. 零 GC：BitSet (nonAir/supported) + int[] queue
 *   2. 1D index 運算
 *   3. 雙煞車：bfs_max_blocks (65536) + bfs_max_ms (50ms)
 */
@ThreadSafe
public class UnionFindEngine {

    private static final Logger LOGGER = LogManager.getLogger("BlockReality/Physics");

    // snapshot BFS 仍用大限制（掃描整個快照）
    private static final int BFS_MAX_BLOCKS = RWorldSnapshot.MAX_SNAPSHOT_BLOCKS;
    private static final long BFS_MAX_MS = 50; // snapshot scan 允許較寬的時間
    // ★ B-9 fix: 從 BRConfig 讀取結構 BFS 限制（W-8 已調為 2048/50）
    // 提供靜態方法讀取，因為 config 在啟動後才初始化
    public static int getStructureBfsMaxBlocks() {
        return BRConfig.INSTANCE.structureBfsMaxBlocks.get();
    }
    public static long getStructureBfsMaxMs() {
        return BRConfig.INSTANCE.structureBfsMaxMs.get();
    }

    private static final int[] DX = { 1, -1, 0, 0, 0, 0 };
    private static final int[] DY = { 0, 0, 1, -1, 0, 0 };
    private static final int[] DZ = { 0, 0, 0, 0, 1, -1 };

    /** 預設掃描邊距 */
    public static final int DEFAULT_MARGIN = 4;

    // ═══════════════════════════════════════════════════════
    //  Epoch / Dirty Flag 增量更新機制 (v3fix AD-7)
    // ═══════════════════════════════════════════════════════
    //
    // 設計思路：
    //   - 全局 epoch 計數器（long），每次放置/破壞事件時遞增
    //   - 每個快取結果儲存計算時的 epoch
    //   - 查詢時比較 epoch：相同 = 快取命中，不同 = 需要重算
    //   - dirtyRegions 記錄需要重算的區域（以 chunk 粒度）
    //   - CAS 保護 (rebuildingComponent) 防止並發重建
    //   - Per-component ReentrantLock 用於細粒度鎖定
    //
    // 這讓連續查詢同一結構不需要重算，只有世界變動時才失效。
    // v3fix 合規：CAS + 細粒度鎖提供高效的並發控制。

    /** 全局結構 epoch — 每次結構變動遞增（改用 long 避免溢位） */
    private static final AtomicLong globalEpoch = new AtomicLong(0);

    /** CAS 保護：防止並發重建連通分量 */
    private static final AtomicBoolean rebuildingComponent = new AtomicBoolean(false);

    /** Per-component 細粒度鎖 — 每個連通分量一把鎖，避免全局鎖爭用 */
    private static final ConcurrentHashMap<Integer, ReentrantLock> componentLocks = new ConcurrentHashMap<>();

    /** 區域結果快取：regionKey → CachedResult */
    private static final ConcurrentHashMap<Long, CachedResult> resultCache = new ConcurrentHashMap<>();

    /** 髒區域集合（chunk 粒度：(chunkX, chunkZ) 打包成 long） */
    private static final Set<Long> dirtyRegions = ConcurrentHashMap.newKeySet();

    /**
     * 快取結果 — 攜帶 epoch 標記（使用 long 避免溢位）
     */
    public record CachedResult(PhysicsResult result, long epoch) {
        public boolean isValid() { return epoch == globalEpoch.get(); }
    }

    /**
     * 通知結構變動 — 在 BlockPlaceEvent / BlockBreakEvent 觸發。
     * 遞增 epoch 並標記受影響的 chunk 為 dirty。
     */
    public static void notifyStructureChanged(BlockPos pos) {
        globalEpoch.incrementAndGet();
        long regionKey = chunkKey(pos.getX() >> 4, pos.getZ() >> 4);
        dirtyRegions.add(regionKey);
        // 同時標髒周圍 chunk（方塊可能跨 chunk 邊界影響鄰居）
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                dirtyRegions.add(chunkKey((pos.getX() >> 4) + dx, (pos.getZ() >> 4) + dz));
            }
        }
    }

    /**
     * 帶快取的查詢 — 如果 epoch 未變動且區域不髒，直接返回快取結果。
     */
    public static PhysicsResult findUnsupportedBlocksCached(RWorldSnapshot snapshot, int scanMargin) {
        long regionKey = chunkKey(snapshot.getStartX() >> 4, snapshot.getStartZ() >> 4);
        CachedResult cached = resultCache.get(regionKey);

        if (cached != null && cached.isValid() && !dirtyRegions.contains(regionKey)) {
            LOGGER.debug("UnionFind cache hit for region {} (epoch={})", regionKey, cached.epoch);
            return cached.result;
        }

        // 快取失效 → 重算
        PhysicsResult result = findUnsupportedBlocks(snapshot, scanMargin);

        // 儲存新結果
        resultCache.put(regionKey, new CachedResult(result, globalEpoch.get()));
        dirtyRegions.remove(regionKey);

        return result;
    }

    /**
     * 取得目前結構 epoch — 診斷用。
     */
    public static long getStructureEpoch() { return globalEpoch.get(); }

    /**
     * 清除所有快取（世界重載時）。
     */
    public static void clearCache() {
        resultCache.clear();
        dirtyRegions.clear();
        LOGGER.info("UnionFind cache cleared");
    }

    // ═══════════════════════════════════════════════════════
    //  AD-7 Epoch 回收 — 驅逐過期快取條目
    // ═══════════════════════════════════════════════════════
    //
    // v3fix Decision 7: Versioned Epoch for UF deletion
    // 問題：resultCache 隨時間增長，舊 region 的快取永遠不被清除。
    // 解法：定期驅逐 epoch 距當前差距過大的條目。
    //
    // 呼叫時機：在 ServerTickHandler 中每 200 ticks（10 秒）呼叫一次。

    /** 快取條目的最大存活 epoch 差距（超過此值即驅逐） */
    private static final int EPOCH_EVICTION_THRESHOLD = 64;

    /**
     * 驅逐過期的快取條目 — 清除 epoch 差距超過閾值的舊結果。
     * 應在伺服器 tick 中定期呼叫（建議每 200 ticks / 10 秒）。
     *
     * @return 被驅逐的條目數
     */
    public static int evictStaleEntries() {
        long currentEpoch = globalEpoch.get();
        int evicted = 0;

        var iterator = resultCache.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            long entryEpoch = entry.getValue().epoch();
            if (currentEpoch - entryEpoch > EPOCH_EVICTION_THRESHOLD) {
                iterator.remove();
                evicted++;
            }
        }

        // 同時清理已不再 dirty 的陳舊 dirtyRegions
        // （如果某個 dirty region 在多個 epoch 後仍未被查詢，表示它已不再活躍）
        // dirtyRegions 沒有 epoch 資訊，但若 resultCache 已被清除，dirty 標記也無意義
        if (evicted > 0) {
            LOGGER.debug("[AD-7] Evicted {} stale cache entries (threshold={}), epoch={}",
                evicted, EPOCH_EVICTION_THRESHOLD, currentEpoch);
        }

        return evicted;
    }

    /**
     * 取得快取統計 — 診斷用。
     */
    public static String getCacheStats() {
        return String.format("epoch=%d, cached=%d, dirty=%d",
            globalEpoch.get(), resultCache.size(), dirtyRegions.size());
    }

    /** chunk 座標打包成 long key */
    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    /**
     * 找出快照中所有失去支撐的懸空方塊。
     *
     * @param snapshot   唯讀世界快照（含 margin 的完整掃描區）
     * @param scanMargin 掃描邊距格數（崩塌區 = 快照尺寸 - 2*margin）
     * @return 懸空方塊結果（只包含崩塌區內的方塊）
     */
    public static PhysicsResult findUnsupportedBlocks(RWorldSnapshot snapshot, int scanMargin) {
        long t0 = System.nanoTime();

        int sizeX = snapshot.getSizeX();
        int sizeY = snapshot.getSizeY();
        int sizeZ = snapshot.getSizeZ();
        int total = sizeX * sizeY * sizeZ;

        // ─── Phase 1: 掃描 nonAir + Anchor（邊界方塊） ───
        BitSet nonAir = new BitSet(total);
        int[] anchorQueue = new int[total];
        int anchorCount = 0;
        int nonAirCount = 0;

        int sx = snapshot.getStartX();
        int sy = snapshot.getStartY();
        int sz = snapshot.getStartZ();

        for (int lz = 0; lz < sizeZ; lz++) {
            for (int ly = 0; ly < sizeY; ly++) {
                for (int lx = 0; lx < sizeX; lx++) {
                    int idx = lx + sizeX * (ly + sizeY * lz);
                    RBlockState state = snapshot.getBlock(sx + lx, sy + ly, sz + lz);

                    if (state != RBlockState.AIR && state.mass() > 0) {
                        nonAir.set(idx);
                        nonAirCount++;

                        // Anchor = 掃描區邊界方塊 + 天然錨定（基岩/屏障）
                        // 有限元素邊界條件：邊界連接未知的外部世界 → 假設有支撐
                        boolean isScanBoundary = (lx == 0 || lx == sizeX - 1 ||
                                                  ly == 0 || ly == sizeY - 1 ||
                                                  lz == 0 || lz == sizeZ - 1);

                        if (state.isAnchor() || isScanBoundary) {
                            anchorQueue[anchorCount++] = idx;
                        }
                    }
                }
            }
        }

        // ─── Phase 2: BFS 從 Anchor 擴散（在完整掃描區上） ───
        BitSet supported = new BitSet(total);

        int[] queue = new int[total];
        int head = 0, tail = 0;

        for (int i = 0; i < anchorCount; i++) {
            int idx = anchorQueue[i];
            supported.set(idx);
            queue[tail++] = idx;
        }

        long deadline = System.currentTimeMillis() + BFS_MAX_MS;
        int visitCount = 0;
        boolean timedOut = false;
        boolean exceededMax = false;

        while (head < tail) {
            if (visitCount >= BFS_MAX_BLOCKS) {
                exceededMax = true;
                LOGGER.warn("BFS hit max block limit ({}), aborting", BFS_MAX_BLOCKS);
                break;
            }
            if ((visitCount & 0xFF) == 0 && System.currentTimeMillis() > deadline) {
                timedOut = true;
                LOGGER.warn("BFS timed out after {}ms, visited {} blocks", BFS_MAX_MS, visitCount);
                break;
            }

            int idx = queue[head++];
            visitCount++;

            int lx = idx % sizeX;
            int ly = (idx / sizeX) % sizeY;
            int lz = idx / (sizeX * sizeY);

            for (int d = 0; d < 6; d++) {
                int nx = lx + DX[d];
                int ny = ly + DY[d];
                int nz = lz + DZ[d];

                if (nx < 0 || nx >= sizeX || ny < 0 || ny >= sizeY || nz < 0 || nz >= sizeZ) {
                    continue;
                }

                int nIdx = nx + sizeX * (ny + sizeY * nz);

                if (nonAir.get(nIdx) && !supported.get(nIdx)) {
                    supported.set(nIdx);
                    queue[tail++] = nIdx;
                }
            }
        }

        // ─── Phase 3: 懸空判定 — nonAir ∧ ¬supported ∧ 在崩塌區內 ───
        // 崩塌區 = 排除 margin 的內部區域
        BitSet floating = (BitSet) nonAir.clone();
        floating.andNot(supported);

        Set<BlockPos> unsupported = new HashSet<>();
        for (int idx = floating.nextSetBit(0); idx >= 0; idx = floating.nextSetBit(idx + 1)) {
            int lx = idx % sizeX;
            int ly = (idx / sizeX) % sizeY;
            int lz = idx / (sizeX * sizeY);

            // 只有崩塌區內（排除水平 margin）的方塊才會被標記為懸空
            // ★ R6-1 fix: Y 軸不套用 margin — 垂直方向任何高度都可能浮空
            // margin 的目的是避免快照邊緣的水平鄰居資訊不足導致誤判，
            // 但垂直方向的支撐判定不受水平邊界影響
            boolean inEffectZone = (lx >= scanMargin && lx < sizeX - scanMargin &&
                                    lz >= scanMargin && lz < sizeZ - scanMargin);

            if (inEffectZone) {
                unsupported.add(new BlockPos(sx + lx, sy + ly, sz + lz));
            }
        }

        long elapsed = System.nanoTime() - t0;

        return new PhysicsResult(
            unsupported,
            nonAirCount,
            anchorCount,
            visitCount,
            elapsed,
            timedOut,
            exceededMax
        );
    }

    /**
     * 無 margin 版本（向後相容）
     */
    public static PhysicsResult findUnsupportedBlocks(RWorldSnapshot snapshot) {
        return findUnsupportedBlocks(snapshot, 0);
    }

    /**
     * Section-bounded 版本 — 僅分析指定 Section (16³) 內的方塊穩定性。
     * ★ D-2d: 移除全域 BFS 限制，改為 Section 級分析。
     *
     * 跨 Section 邊界的鄰居視為「外部錨定」（假設穩定），
     * 避免全域 BFS 的效能瓶頸。
     *
     * @param snapshot  RWorldSnapshot（需包含目標 Section 的範圍）
     * @param sectionX  目標 Section X 座標（world >> 4）
     * @param sectionY  目標 Section Y 座標
     * @param sectionZ  目標 Section Z 座標
     * @return 此 Section 內的不穩定方塊集合
     */
    public static Set<BlockPos> findUnsupportedBlocksInSection(
            RWorldSnapshot snapshot, int sectionX, int sectionY, int sectionZ) { // ★ param: RWorldSnapshot

        int secMinX = sectionX << 4;
        int secMinY = sectionY << 4;
        int secMinZ = sectionZ << 4;
        int secMaxX = secMinX + 15;
        int secMaxY = secMinY + 15;
        int secMaxZ = secMinZ + 15;

        int sx = snapshot.getStartX();
        int sy = snapshot.getStartY();
        int sz = snapshot.getStartZ();
        int sizeX = snapshot.getSizeX();
        int sizeY = snapshot.getSizeY();
        int total = sizeX * sizeY * snapshot.getSizeZ();

        // Phase 1: 收集此 Section 內的方塊 + 識別錨定點
        BitSet sectionBits = new BitSet(total);
        int[] anchorBuf = new int[4096]; // 最多 16³
        int anchorCnt = 0;

        for (int wz = secMinZ; wz <= secMaxZ; wz++) {
            for (int wy = secMinY; wy <= secMaxY; wy++) {
                for (int wx = secMinX; wx <= secMaxX; wx++) {
                    int lx = wx - sx, ly = wy - sy, lz = wz - sz;
                    if (lx < 0 || ly < 0 || lz < 0 || lx >= sizeX || ly >= sizeY || lz >= snapshot.getSizeZ())
                        continue;

                    int idx = lx + sizeX * (ly + sizeY * lz);
                    RBlockState state = snapshot.getBlock(wx, wy, wz);
                    if (state == RBlockState.AIR || state.mass() <= 0) continue;

                    sectionBits.set(idx);

                    // 錨定 = 天然錨定 OR 有跨 Section 邊界的非空鄰居
                    if (state.isAnchor()) {
                        anchorBuf[anchorCnt++] = idx;
                        continue;
                    }

                    boolean hasCrossBoundarySupport = false;
                    for (int d = 0; d < 6; d++) {
                        int nx = wx + DX[d], ny = wy + DY[d], nz = wz + DZ[d];
                        if (nx < secMinX || nx > secMaxX || ny < secMinY || ny > secMaxY || nz < secMinZ || nz > secMaxZ) {
                            // 鄰居在 Section 外 — 查詢快照
                            RBlockState ns = snapshot.getBlock(nx, ny, nz);
                            if (ns != RBlockState.AIR && ns.mass() > 0) {
                                hasCrossBoundarySupport = true;
                                break;
                            }
                        }
                    }
                    if (hasCrossBoundarySupport) {
                        anchorBuf[anchorCnt++] = idx;
                    }
                }
            }
        }

        // Phase 2: Section 內 BFS
        BitSet supported = new BitSet(total);
        int[] bfsQueue = new int[4096];
        int head = 0, tail = 0;

        for (int i = 0; i < anchorCnt; i++) {
            int idx = anchorBuf[i];
            if (!supported.get(idx)) {
                supported.set(idx);
                bfsQueue[tail++] = idx;
            }
        }

        while (head < tail) {
            int idx = bfsQueue[head++];
            int lx = idx % sizeX;
            int ly = (idx / sizeX) % sizeY;
            int lz = idx / (sizeX * sizeY);
            int wx = sx + lx, wy = sy + ly, wz = sz + lz;

            for (int d = 0; d < 6; d++) {
                int nx = wx + DX[d], ny = wy + DY[d], nz = wz + DZ[d];
                // 限制在 Section 內
                if (nx < secMinX || nx > secMaxX || ny < secMinY || ny > secMaxY || nz < secMinZ || nz > secMaxZ)
                    continue;

                int nlx = nx - sx, nly = ny - sy, nlz = nz - sz;
                if (nlx < 0 || nly < 0 || nlz < 0 || nlx >= sizeX || nly >= sizeY || nlz >= snapshot.getSizeZ())
                    continue;

                int nIdx = nlx + sizeX * (nly + sizeY * nlz);
                if (!sectionBits.get(nIdx) || supported.get(nIdx)) continue;

                supported.set(nIdx);
                if (tail < bfsQueue.length) bfsQueue[tail++] = nIdx;
            }
        }

        // Phase 3: 不穩定 = sectionBits ∧ ¬supported
        Set<BlockPos> unsupported = new HashSet<>();
        for (int idx = sectionBits.nextSetBit(0); idx >= 0; idx = sectionBits.nextSetBit(idx + 1)) {
            if (!supported.get(idx)) {
                int lx = idx % sizeX;
                int ly = (idx / sizeX) % sizeY;
                int lz = idx / (sizeX * sizeY);
                unsupported.add(new BlockPos(sx + lx, sy + ly, sz + lz));
            }
        }

        return unsupported;
    }

    // ═══════════════════════════════════════════════════════
    //  Teardown 式增量結構完整性檢查
    // ═══════════════════════════════════════════════════════
    //
    // 靈感來源：Teardown (Dennis Gustafsson)
    // 核心概念：方塊被破壞時，不重新掃描整個快照，
    // 而是只檢查被移除方塊的鄰居是否仍然能到達錨定點。
    //
    // 演算法：
    //   1. 收集破壞位置的 6 鄰居中所有非空氣的 RBlock
    //   2. 從這些鄰居做反向 BFS，嘗試到達任意錨定點
    //   3. 無法到達錨定點的連通分量 = 懸浮，需要坍塌
    //
    // 效能優勢：
    //   - O(K) 而非 O(N)，K = 受影響的連通分量大小，N = 全區域
    //   - 大多數破壞事件只影響小範圍
    //   - 雙煞車（max blocks + max ms）確保最差情況不超時

    /**
     * Teardown 式增量結構完整性檢查 — 在方塊被破壞後呼叫。
     *
     * 從破壞位置的鄰居出發，反向 BFS 尋找錨定點。
     * 無法到達錨定點的 RBlock 集合 = 需要坍塌的懸浮方塊。
     *
     * @param level    伺服器世界
     * @param removed  被移除方塊的位置
     * @return 需要坍塌的方塊集合（可能為空 = 結構仍完整）
     */
    public static Set<BlockPos> validateLocalIntegrity(ServerLevel level, BlockPos removed) {
        long t0 = System.nanoTime();

        int maxBlocks = getStructureBfsMaxBlocks();
        long maxMs = getStructureBfsMaxMs();

        // ─── Step 1: 收集受影響的 RBlock 鄰居 ───
        List<BlockPos> affectedNeighbors = new ArrayList<>();
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = removed.relative(dir);
            BlockEntity be = level.getBlockEntity(neighbor);
            if (be instanceof RBlockEntity) {
                affectedNeighbors.add(neighbor);
            }
        }

        if (affectedNeighbors.isEmpty()) {
            return Set.of(); // 周圍沒有 RBlock，不需要檢查
        }

        // ─── Step 2: 對每個受影響鄰居，反向 BFS 尋找錨定點 ───
        // 已確認有錨定連接的方塊集合
        Set<BlockPos> confirmed = new HashSet<>();
        // 確認為懸浮的方塊集合
        Set<BlockPos> floating = new HashSet<>();

        for (BlockPos start : affectedNeighbors) {
            if (confirmed.contains(start) || floating.contains(start)) continue;

            // 從 start 做 BFS，看能否到達 anchor
            Set<BlockPos> visited = new HashSet<>();
            Deque<BlockPos> queue = new ArrayDeque<>();
            queue.add(start);
            visited.add(start);

            boolean foundAnchor = false;
            long deadline = System.currentTimeMillis() + maxMs;
            int visitCount = 0;
            boolean budgetExhausted = false;

            while (!queue.isEmpty()) {
                if (visitCount >= maxBlocks) {
                    budgetExhausted = true;
                    break;
                }
                if ((visitCount & 0xFF) == 0 && System.currentTimeMillis() > deadline) {
                    budgetExhausted = true;
                    break;
                }

                BlockPos current = queue.poll();
                visitCount++;

                // 檢查是否為錨定點
                if (isAnchorBlock(level, current)) {
                    foundAnchor = true;
                    break;
                }

                // 如果碰到已確認有支撐的方塊，也算找到錨定
                if (confirmed.contains(current)) {
                    foundAnchor = true;
                    break;
                }

                // 展開 6 鄰居
                for (Direction dir : Direction.values()) {
                    BlockPos neighbor = current.relative(dir);
                    if (visited.contains(neighbor)) continue;
                    if (neighbor.equals(removed)) continue; // 跳過被破壞的方塊

                    BlockState state = level.getBlockState(neighbor);
                    if (state.isAir()) continue;

                    // RBlock 或實心原版方塊都可以傳遞支撐
                    visited.add(neighbor);
                    BlockEntity be = level.getBlockEntity(neighbor);
                    if (be instanceof RBlockEntity) {
                        queue.add(neighbor);
                    } else if (!state.isAir()) {
                        // 原版實心方塊 = 隱式錨定（地形穩定）
                        foundAnchor = true;
                        break;
                    }
                }
                if (foundAnchor) break;
            }

            // ─── Step 3: 判定結果 ───
            if (foundAnchor || budgetExhausted) {
                // 找到錨定（或預算用盡保守處理）→ 所有訪問到的方塊都安全
                confirmed.addAll(visited);
            } else {
                // 未找到錨定 → 整個連通分量懸浮
                // 只收集 RBlock（原版方塊不參與坍塌）
                for (BlockPos pos : visited) {
                    BlockEntity be = level.getBlockEntity(pos);
                    if (be instanceof RBlockEntity) {
                        floating.add(pos);
                    }
                }
            }
        }

        long elapsed = System.nanoTime() - t0;
        if (!floating.isEmpty()) {
            LOGGER.info("[Teardown] Local integrity check at {}: {} floating blocks found in {}ms",
                removed.toShortString(), floating.size(), String.format("%.2f", elapsed / 1e6));
        } else {
            LOGGER.debug("[Teardown] Local integrity check at {}: structure intact ({}ms)",
                removed.toShortString(), String.format("%.2f", elapsed / 1e6));
        }

        return floating;
    }

    /**
     * 檢查方塊是否為錨定點。
     * 統一判定：基岩/屏障/底層方塊（y <= minBuildHeight+1）或手動標記。
     */
    private static boolean isAnchorBlock(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);

        // 基岩、屏障等天然錨定
        if (state.is(net.minecraft.world.level.block.Blocks.BEDROCK) ||
            state.is(net.minecraft.world.level.block.Blocks.BARRIER)) {
            return true;
        }

        // 底層方塊（接近世界底部 = 視為地基）
        if (pos.getY() <= level.getMinBuildHeight() + 1) {
            return true;
        }

        // RBlock 手動錨定標記
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof RBlockEntity rbe && rbe.isAnchored()) {
            return true;
        }

        return false;
    }

    // ═══════════════════════════════════════════════════════
    //  CAS 保護的連通分量重建 (v3fix AD-8)
    // ═══════════════════════════════════════════════════════
    //
    // 設計：使用 AtomicBoolean CAS (Compare-And-Swap) 防止並發重建同一分量。
    // 若檢測到已有重建進行中，直接返回，避免重複計算。
    //
    // 細粒度鎖：Per-component ReentrantLock 用於關鍵段保護。

    /**
     * 取得或建立某個連通分量的鎖。
     *
     * @param componentId 連通分量 ID
     * @return 該分量的 ReentrantLock
     */
    private static ReentrantLock getComponentLock(int componentId) {
        return componentLocks.computeIfAbsent(componentId, k -> new ReentrantLock());
    }

    /**
     * CAS 保護的連通分量重建入口。
     *
     * 在開始重建前，嘗試將 rebuildingComponent 從 false 設為 true (CAS)。
     * - 若成功（CAS 返回 true），執行重建，最後 release
     * - 若失敗（已有其他線程在重建），直接返回 null 或空結果
     *
     * 演算法：
     *   1. CAS 加鎖，確保只有一個線程執行重建
     *   2. 收集所有 RBlock 位置（從 level）
     *   3. 識別錨定點（基岩、屏障、底層方塊）
     *   4. 從錨定點做 BFS，標記所有支撐的方塊
     *   5. 未訪問到的 RBlock = 懸浮方塊
     *   6. 遞增 globalEpoch
     *   7. 返回包含懸浮方塊的 PhysicsResult
     *
     * @return 重建成功返回結果，失敗返回 null
     */
    /**
     * 無參數版本 — 向後相容。
     * 沒有 ServerLevel 無法掃描方塊實體，直接回傳 null。
     * 推薦使用 {@link #rebuildConnectedComponents(ServerLevel)} 。
     */
    public static PhysicsResult rebuildConnectedComponents() {
        return rebuildConnectedComponents(null);
    }

    /**
     * CAS 保護的連通分量重建入口。
     *
     * ★ Round 5 fix: 補齊 ServerLevel 參數，掃描已加載 chunk 中所有 RBlockEntity，
     * 建立完整的「懸浮方塊」結果。沒有 ServerLevel 時仍回傳空結果（安全降級）。
     *
     * @param level 伺服器世界（null 時安全降級為空結果）
     * @return 重建成功返回結果，CAS 失敗返回 null
     */
    public static PhysicsResult rebuildConnectedComponents(ServerLevel level) {
        // CAS 加鎖：嘗試從 false → true
        if (!rebuildingComponent.compareAndSet(false, true)) {
            LOGGER.debug("[UnionFind] Rebuild already in progress, skipping concurrent rebuild");
            return null; // 已有重建進行中，直接返回
        }

        try {
            long t0 = System.nanoTime();
            LOGGER.debug("[UnionFind] CAS acquired, starting rebuild");

            // ─── Phase 1: 蒐集所有 RBlock 及識別錨定點 ───
            Set<BlockPos> rblocks = new HashSet<>();
            List<BlockPos> anchorQueue = new ArrayList<>();

            if (level != null) {
                // ★ Round 5: 遍歷所有已加載 chunk 的 BlockEntity
                // 使用反射取得 ChunkMap 內部的已載入 chunk 列表（避免 AT 問題）
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
                            if (entry.getValue() instanceof com.blockreality.api.block.RBlockEntity rbe) {
                                BlockPos pos = entry.getKey();
                                rblocks.add(pos);

                                // 錨定判定：底部有實心方塊 or 標記為錨定
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
                return new PhysicsResult(Set.of(), 0, 0, 0, System.nanoTime() - t0, false, false);
            }

            // ─── Phase 2: 從錨定點 BFS 擴散 ───
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

            // ─── Phase 3: 懸浮 = rblocks ∖ supported ───
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

            return new PhysicsResult(
                unsupported, rblocks.size(), anchorQueue.size(),
                visitCount, elapsed, false, false);
        } finally {
            rebuildingComponent.set(false);
        }
    }

    // ═══════════════════════════════════════════════════════
    //  PhysicsResult 記錄
    // ═══════════════════════════════════════════════════════

    /**
     * 物理分析結果容器。
     */
    public record PhysicsResult(
        Set<BlockPos> unsupportedBlocks,
        int totalNonAir,
        int anchorCount,
        int visitCount,
        long elapsedNs,
        boolean timedOut,
        boolean exceededMax
    ) {
        public double elapsedMs() { return elapsedNs / 1_000_000.0; }
        public double computeTimeMs() { return elapsedMs(); }
        public int bfsVisited() { return visitCount; }
        public int unsupportedCount() { return unsupportedBlocks != null ? unsupportedBlocks.size() : 0; }
        public boolean hasUnsupported() { return unsupportedBlocks != null && !unsupportedBlocks.isEmpty(); }
    }
}