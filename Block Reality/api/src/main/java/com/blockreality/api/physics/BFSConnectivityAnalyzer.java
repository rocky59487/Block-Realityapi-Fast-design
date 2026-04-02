package com.blockreality.api.physics;

import com.blockreality.api.config.BRConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.concurrent.ThreadSafe;
import java.util.BitSet;
import javax.annotation.Nonnull;
import java.util.HashSet;
import java.util.Set;

/**
 * BFS 連通塊引擎 — 從 Anchor 擴散，找出所有失去支撐的懸空方塊。
 *
 * <p>Issue#9 重構：快取/epoch 管理已提取至 {@link ConnectivityCache}，
 * Teardown 式增量驗證已提取至 {@link TeardownIntegrityValidator}。
 * 本類別僅保留純 BFS 演算法及向後相容的委託方法。
 *
 * <p>錨定策略（v3 — Scan Margin）：
 * <ul>
 *   <li>掃描區 = 使用者指定範圍 + margin（預設 4 格）</li>
 *   <li>Anchor = 掃描區邊界上的所有非空氣方塊（有限元素邊界條件）</li>
 *   <li>崩塌區 = 僅限內部（排除 margin 的區域）</li>
 * </ul>
 *
 * <p>效能設計：零 GC（BitSet + int[] queue）、1D index、雙煞車（max blocks + max ms）。
 *
 * @see ConnectivityCache
 * @see TeardownIntegrityValidator
 */
@ThreadSafe
public class BFSConnectivityAnalyzer {

    private static final Logger LOGGER = LogManager.getLogger("BlockReality/Physics");

    private static final int BFS_MAX_BLOCKS = RWorldSnapshot.MAX_SNAPSHOT_BLOCKS;
    private static final long BFS_MAX_MS = 50;

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
    //  向後相容委託 → ConnectivityCache
    // ═══════════════════════════════════════════════════════

    /** @deprecated 請使用 {@link ConnectivityCache#notifyStructureChanged} */
    public static void notifyStructureChanged(@Nonnull BlockPos pos) {
        ConnectivityCache.notifyStructureChanged(pos);
    }

    /** @deprecated 請使用 {@link ConnectivityCache#findUnsupportedBlocksCached} */
    @Nonnull
    public static PhysicsResult findUnsupportedBlocksCached(@Nonnull RWorldSnapshot snapshot, int scanMargin) {
        return ConnectivityCache.findUnsupportedBlocksCached(snapshot, scanMargin);
    }

    /** @deprecated 請使用 {@link ConnectivityCache#getStructureEpoch} */
    public static long getStructureEpoch() { return ConnectivityCache.getStructureEpoch(); }

    /** @deprecated 請使用 {@link ConnectivityCache#clearCache} */
    public static void clearCache() { ConnectivityCache.clearCache(); }

    /** @deprecated 請使用 {@link ConnectivityCache#evictStaleEntries} */
    public static int evictStaleEntries() { return ConnectivityCache.evictStaleEntries(); }

    /** @deprecated 請使用 {@link ConnectivityCache#getCacheStats} */
    public static String getCacheStats() { return ConnectivityCache.getCacheStats(); }

    /** @deprecated 請使用 {@link ConnectivityCache#rebuildConnectedComponents()} */
    public static PhysicsResult rebuildConnectedComponents() {
        return ConnectivityCache.rebuildConnectedComponents();
    }

    /** @deprecated 請使用 {@link ConnectivityCache#rebuildConnectedComponents(ServerLevel)} */
    public static PhysicsResult rebuildConnectedComponents(ServerLevel level) {
        return ConnectivityCache.rebuildConnectedComponents(level);
    }

    // ═══════════════════════════════════════════════════════
    //  向後相容委託 → TeardownIntegrityValidator
    // ═══════════════════════════════════════════════════════

    /** @deprecated 請使用 {@link TeardownIntegrityValidator#validateLocalIntegrity} */
    public static Set<BlockPos> validateLocalIntegrity(ServerLevel level, BlockPos removed) {
        return TeardownIntegrityValidator.validateLocalIntegrity(level, removed);
    }

    // ═══════════════════════════════════════════════════════
    //  BFS 演算法（核心邏輯，保留於此類別）
    // ═══════════════════════════════════════════════════════

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
        BitSet floating = (BitSet) nonAir.clone();
        floating.andNot(supported);

        Set<BlockPos> unsupported = new HashSet<>();
        for (int idx = floating.nextSetBit(0); idx >= 0; idx = floating.nextSetBit(idx + 1)) {
            int lx = idx % sizeX;
            int ly = (idx / sizeX) % sizeY;
            int lz = idx / (sizeX * sizeY);

            // ★ R6-1 fix: Y 軸不套用 margin
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
     */
    public static Set<BlockPos> findUnsupportedBlocksInSection(
            RWorldSnapshot snapshot, int sectionX, int sectionY, int sectionZ) {

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
        int[] anchorBuf = new int[4096];
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

                    if (state.isAnchor()) {
                        anchorBuf[anchorCnt++] = idx;
                        continue;
                    }

                    boolean hasCrossBoundarySupport = false;
                    for (int d = 0; d < 6; d++) {
                        int nx = wx + DX[d], ny = wy + DY[d], nz = wz + DZ[d];
                        if (nx < secMinX || nx > secMaxX || ny < secMinY || ny > secMaxY || nz < secMinZ || nz > secMaxZ) {
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
    //  記錄類型（保留於此以維持向後相容）
    // ═══════════════════════════════════════════════════════

    /**
     * 快取結果 — 攜帶 epoch 標記
     */
    public record CachedResult(PhysicsResult result, long epoch) {
        public boolean isValid() { return epoch == ConnectivityCache.getStructureEpoch(); }
    }

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
