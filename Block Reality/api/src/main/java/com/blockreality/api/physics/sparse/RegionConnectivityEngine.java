package com.blockreality.api.physics.sparse;

import com.blockreality.api.physics.RBlockState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.concurrent.ThreadSafe;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Region 級連通性引擎 — 階層式物理 Layer 3。
 *
 * 參考來源：Valkyrien Skies 2 的 Physics World 分離架構。
 *
 * 核心思想：
 *   每個 VoxelSection (16³) 視為一個「超級節點」，
 *   Section 之間的邊由邊界面的連續方塊決定。
 *   在 Section 級做 Union-Find，複雜度 O(Section 數量) ≈ O(107K)。
 *
 * 用途：
 *   1. 快速判斷大型結構是否仍連接地面（< 10ms）
 *   2. 識別「結構島」(structural islands) 做並行分割
 *   3. 倒塌事件：整個 island 脫離 → 觸發 Layer 2 (CoarseFEM) 分析
 *
 * 執行頻率：每 5 秒（100 ticks）執行一次全域掃描。
 *
 * @since v3.0 Phase 2
 */
@ThreadSafe
public class RegionConnectivityEngine {

    private static final Logger LOGGER = LogManager.getLogger("BR/RegionConnectivity");

    /** 6 方向偏移（Section 級鄰居） */
    private static final int[][] SECTION_NEIGHBORS = {
        {1, 0, 0}, {-1, 0, 0},
        {0, 1, 0}, {0, -1, 0},
        {0, 0, 1}, {0, 0, -1}
    };

    // ═══ Union-Find 資料結構 ═══

    private final Map<Long, Long> parent;
    private final Map<Long, Integer> rank;

    /** 每個 island 的 Section 集合 */
    private final Map<Integer, Set<Long>> islands;

    /** 錨定 Section（包含錨定方塊的 Section） */
    private final Set<Long> anchorSections;

    /** 最近一次分析結果 */
    private volatile ConnectivityResult lastResult;

    public RegionConnectivityEngine() {
        this.parent = new ConcurrentHashMap<>();
        this.rank = new ConcurrentHashMap<>();
        this.islands = new ConcurrentHashMap<>();
        this.anchorSections = ConcurrentHashMap.newKeySet();
    }

    // ═══ 分析入口 ═══

    /**
     * 執行全域 Section 級連通性分析。
     *
     * 演算法：
     *   1. 遍歷所有非空 Section
     *   2. 識別錨定 Section（包含 isAnchor 方塊或 Y=minBuildHeight 的 Section）
     *   3. 檢查每對相鄰 Section 的邊界面是否有連續方塊
     *   4. Union-Find 合併連通的 Section
     *   5. 標記與錨定 Section 連通的 island 為「支撐」
     *   6. 非支撐 island = 懸浮結構
     *
     * @param svo 稀疏體素八叉樹
     * @return 連通性分析結果
     */
    public ConnectivityResult analyze(SparseVoxelOctree svo) {
        long t0 = System.nanoTime();

        // 重置 Union-Find
        parent.clear();
        rank.clear();
        anchorSections.clear();

        // Phase 1: 收集所有非空 Section，識別錨定 Section
        List<Long> nonEmptySections = new ArrayList<>();

        svo.forEachDirtySection((key, section) -> {}); // 清除 dirty 不影響此處

        // 遍歷 SVO 的所有 Section
        int minSX = svo.getMinX() >> 4, maxSX = svo.getMaxX() >> 4;
        int minSY = svo.getMinY() >> 4, maxSY = svo.getMaxY() >> 4;
        int minSZ = svo.getMinZ() >> 4, maxSZ = svo.getMaxZ() >> 4;

        for (int sx = minSX; sx <= maxSX; sx++) {
            for (int sy = minSY; sy <= maxSY; sy++) {
                for (int sz = minSZ; sz <= maxSZ; sz++) {
                    VoxelSection section = svo.getSection(sx, sy, sz);
                    if (section == null || section.isEmpty()) continue;

                    long key = SparseVoxelOctree.sectionKey(sx, sy, sz);
                    nonEmptySections.add(key);
                    makeSet(key);

                    // 檢查是否為錨定 Section
                    if (containsAnchor(section) || isGroundLevel(sy, svo.getMinY())) {
                        anchorSections.add(key);
                    }
                }
            }
        }

        // Phase 2: 檢查相鄰 Section 的邊界連通性
        int edgesFound = 0;
        for (long key : nonEmptySections) {
            int sx = SparseVoxelOctree.sectionKeyX(key);
            int sy = SparseVoxelOctree.sectionKeyY(key);
            int sz = SparseVoxelOctree.sectionKeyZ(key);

            for (int[] offset : SECTION_NEIGHBORS) {
                int nx = sx + offset[0];
                int ny = sy + offset[1];
                int nz = sz + offset[2];

                VoxelSection neighbor = svo.getSection(nx, ny, nz);
                if (neighbor == null || neighbor.isEmpty()) continue;

                long neighborKey = SparseVoxelOctree.sectionKey(nx, ny, nz);

                // 檢查邊界面是否有連續方塊
                if (hasBoundaryConnection(svo, sx, sy, sz, offset)) {
                    union(key, neighborKey);
                    edgesFound++;
                }
            }
        }

        // Phase 3: 收集 islands
        Map<Long, List<Long>> islandMap = new HashMap<>();
        for (long key : nonEmptySections) {
            long root = find(key);
            islandMap.computeIfAbsent(root, k -> new ArrayList<>()).add(key);
        }

        // Phase 4: 判定支撐狀態
        Set<Long> supportedRoots = new HashSet<>();
        for (long anchor : anchorSections) {
            supportedRoots.add(find(anchor));
        }

        int totalIslands = islandMap.size();
        int supportedIslands = 0;
        int floatingIslands = 0;
        List<List<Long>> floatingSections = new ArrayList<>();

        for (var entry : islandMap.entrySet()) {
            if (supportedRoots.contains(entry.getKey())) {
                supportedIslands++;
            } else {
                floatingIslands++;
                floatingSections.add(entry.getValue());
            }
        }

        long elapsed = System.nanoTime() - t0;

        ConnectivityResult result = new ConnectivityResult(
            nonEmptySections.size(),
            totalIslands,
            supportedIslands,
            floatingIslands,
            floatingSections,
            anchorSections.size(),
            edgesFound / 2, // 每條邊被計算兩次
            elapsed
        );

        this.lastResult = result;

        LOGGER.debug("[RegionConnectivity] {} sections, {} islands ({} supported, {} floating), {}ms",
            nonEmptySections.size(), totalIslands, supportedIslands, floatingIslands,
            String.format("%.2f", elapsed / 1e6));

        return result;
    }

    // ═══ 邊界連通檢查 ═══

    /**
     * 檢查兩個相鄰 Section 的邊界面是否有連續方塊。
     * 只需在邊界面找到至少一對相鄰的非空氣方塊即算連通。
     */
    private boolean hasBoundaryConnection(SparseVoxelOctree svo,
                                            int sx, int sy, int sz, int[] offset) {
        VoxelSection sectionA = svo.getSection(sx, sy, sz);
        VoxelSection sectionB = svo.getSection(sx + offset[0], sy + offset[1], sz + offset[2]);
        if (sectionA == null || sectionB == null) return false;

        // 根據方向確定邊界面的掃描範圍
        if (offset[0] == 1) { // +X 方向：A 的 x=15 面 vs B 的 x=0 面
            for (int ly = 0; ly < 16; ly++) {
                for (int lz = 0; lz < 16; lz++) {
                    if (sectionA.getBlock(15, ly, lz) != RBlockState.AIR &&
                        sectionB.getBlock(0, ly, lz) != RBlockState.AIR) {
                        return true;
                    }
                }
            }
        } else if (offset[0] == -1) { // -X
            for (int ly = 0; ly < 16; ly++) {
                for (int lz = 0; lz < 16; lz++) {
                    if (sectionA.getBlock(0, ly, lz) != RBlockState.AIR &&
                        sectionB.getBlock(15, ly, lz) != RBlockState.AIR) {
                        return true;
                    }
                }
            }
        } else if (offset[1] == 1) { // +Y
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    if (sectionA.getBlock(lx, 15, lz) != RBlockState.AIR &&
                        sectionB.getBlock(lx, 0, lz) != RBlockState.AIR) {
                        return true;
                    }
                }
            }
        } else if (offset[1] == -1) { // -Y
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    if (sectionA.getBlock(lx, 0, lz) != RBlockState.AIR &&
                        sectionB.getBlock(lx, 15, lz) != RBlockState.AIR) {
                        return true;
                    }
                }
            }
        } else if (offset[2] == 1) { // +Z
            for (int lx = 0; lx < 16; lx++) {
                for (int ly = 0; ly < 16; ly++) {
                    if (sectionA.getBlock(lx, ly, 15) != RBlockState.AIR &&
                        sectionB.getBlock(lx, ly, 0) != RBlockState.AIR) {
                        return true;
                    }
                }
            }
        } else if (offset[2] == -1) { // -Z
            for (int lx = 0; lx < 16; lx++) {
                for (int ly = 0; ly < 16; ly++) {
                    if (sectionA.getBlock(lx, ly, 0) != RBlockState.AIR &&
                        sectionB.getBlock(lx, ly, 15) != RBlockState.AIR) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * 檢查 Section 是否包含錨定方塊。
     */
    private boolean containsAnchor(VoxelSection section) {
        if (section.isEmpty()) return false;
        if (section.isHomogeneous()) {
            return section.getBlockByIndex(0).isAnchor();
        }
        for (int i = 0; i < VoxelSection.VOLUME; i++) {
            RBlockState state = section.getBlockByIndex(i);
            if (state != null && state != RBlockState.AIR && state.isAnchor()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 檢查 Section 是否在地面層（最低 Y Section）。
     */
    private boolean isGroundLevel(int sy, int minWorldY) {
        return sy <= (minWorldY >> 4) + 1;
    }

    // ═══ Union-Find 操作 ═══

    private void makeSet(long key) {
        parent.put(key, key);
        rank.put(key, 0);
    }

    private long find(long key) {
        long p = parent.getOrDefault(key, key);
        if (p != key) {
            p = find(p);
            parent.put(key, p); // 路徑壓縮
        }
        return p;
    }

    private void union(long a, long b) {
        long rootA = find(a);
        long rootB = find(b);
        if (rootA == rootB) return;

        int rankA = rank.getOrDefault(rootA, 0);
        int rankB = rank.getOrDefault(rootB, 0);

        if (rankA < rankB) {
            parent.put(rootA, rootB);
        } else if (rankA > rankB) {
            parent.put(rootB, rootA);
        } else {
            parent.put(rootB, rootA);
            rank.put(rootA, rankA + 1);
        }
    }

    // ═══ 查詢 API ═══

    /**
     * 取得最近一次分析結果。
     */
    public ConnectivityResult getLastResult() {
        return lastResult;
    }

    // ═══ 結果記錄 ═══

    /**
     * Region 級連通性分析結果。
     */
    public record ConnectivityResult(
        int totalSections,
        int totalIslands,
        int supportedIslands,
        int floatingIslands,
        List<List<Long>> floatingSectionGroups,
        int anchorSections,
        int edges,
        long computeTimeNs
    ) {
        public double computeTimeMs() { return computeTimeNs / 1e6; }
        public boolean hasFloatingStructures() { return floatingIslands > 0; }

        @Override
        public String toString() {
            return String.format(
                "RegionConnectivity[sections=%d, islands=%d (supported=%d, floating=%d), anchors=%d, edges=%d, %.2fms]",
                totalSections, totalIslands, supportedIslands, floatingIslands,
                anchorSections, edges, computeTimeMs()
            );
        }
    }
}
