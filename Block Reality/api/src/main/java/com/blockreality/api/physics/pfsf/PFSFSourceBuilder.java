package com.blockreality.api.physics.pfsf;

import com.blockreality.api.material.RMaterial;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.*;

import static com.blockreality.api.physics.pfsf.PFSFConstants.*;

/**
 * PFSF 源項建構器 — 計算力臂、拱效應修正、每體素源項。
 *
 * 核心演算法：
 * <ol>
 *   <li>多源 BFS 計算水平力臂 arm_i（§2.4.1）</li>
 *   <li>雙色 BFS 計算 ArchFactor（§2.5.2）</li>
 *   <li>距離加壓源項：ρ' = ρ × [1 + α × arm × (1 - archFactor)]（§2.4 + §2.5.1）</li>
 * </ol>
 */
public final class PFSFSourceBuilder {

    private static final Direction[] HORIZONTAL_DIRS = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };

    private PFSFSourceBuilder() {}

    // ══════════════════════��════════════════════════════════════════
    //  §2.4.1 水平力臂計算
    // ═══════════════════════════════════════════════════════════════

    /**
     * 多源 BFS 計算每個體素到最近錨點的水平 Manhattan 距離。
     * 僅計算 X + Z 方向（忽略 Y），用於力矩修正。
     *
     * @param islandMembers island 中所有方塊位置
     * @param anchors       錨點位置集合
     * @return 每個體素的水平力臂（arm），錨點 = 0，無水平路徑者 = 0
     */
    public static Map<BlockPos, Integer> computeHorizontalArmMap(
            Set<BlockPos> islandMembers, Set<BlockPos> anchors) {

        Map<BlockPos, Integer> armMap = new HashMap<>();
        Deque<BlockPos> queue = new ArrayDeque<>();

        // 錨點作為 BFS 源，arm = 0
        for (BlockPos anchor : anchors) {
            if (islandMembers.contains(anchor)) {
                armMap.put(anchor, 0);
                queue.add(anchor);
            }
        }

        // BFS：僅沿水平方向擴展
        while (!queue.isEmpty()) {
            BlockPos cur = queue.poll();
            int curArm = armMap.get(cur);

            for (Direction dir : HORIZONTAL_DIRS) {
                BlockPos nb = cur.relative(dir);
                if (islandMembers.contains(nb) && !armMap.containsKey(nb)) {
                    armMap.put(nb, curArm + 1);
                    queue.add(nb);
                }
            }
        }

        // 無水平路徑到錨點的方塊（純垂直懸掛）：arm = 0
        // 由垂直 BFS 或預設處理
        return armMap;
    }

    // ══════════════════════════════════════════════════════���════════
    //  §2.5.2 拱效應修正（ArchFactor）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 計算每個體素的 ArchFactor ∈ [0.0, 1.0]。
     *
     * <p>核心思想：判斷方塊是否同時被兩個獨立錨點群組覆蓋。
     * ArchFactor = shorter/longer（雙側路徑均衡度）。</p>
     *
     * @param islandMembers island 中所有方塊位置
     * @param anchors       錨點位置集合
     * @return 每個體素的 ArchFactor，0.0 = 純懸臂，1.0 = 完整雙路徑支撐
     */
    public static Map<BlockPos, Double> computeArchFactorMap(
            Set<BlockPos> islandMembers, Set<BlockPos> anchors) {

        Map<BlockPos, Double> archFactorMap = new HashMap<>();

        if (anchors.size() < 2) {
            // 少於 2 個錨點，不可能有拱效應
            return archFactorMap;
        }

        // Step 1：Union-Find 將錨點依水平連通性分群
        UnionFind<BlockPos> anchorGroups = new UnionFind<>(anchors);
        for (BlockPos anchor : anchors) {
            for (Direction dir : HORIZONTAL_DIRS) {
                BlockPos nb = anchor.relative(dir);
                if (anchors.contains(nb)) {
                    anchorGroups.union(anchor, nb);
                }
            }
        }

        // 如果所有錨點屬同一連通群 → 無獨立錨點 → 全為 0
        if (anchorGroups.countRoots() < 2) {
            return archFactorMap;
        }

        // Step 2：取最大的兩個群組
        Map<BlockPos, Set<BlockPos>> groups = anchorGroups.getGroups();
        List<Set<BlockPos>> sortedGroups = new ArrayList<>(groups.values());
        sortedGroups.sort((a, b) -> b.size() - a.size());

        Set<BlockPos> groupA = sortedGroups.get(0);
        Set<BlockPos> groupB = sortedGroups.get(1);

        // Step 3：對每個群組執行 BFS，記錄可達方塊及最短距離
        Map<BlockPos, Double> distFromA = bfsFromGroup(groupA, islandMembers);
        Map<BlockPos, Double> distFromB = bfsFromGroup(groupB, islandMembers);

        // Step 4：計算每個方塊的 ArchFactor
        for (BlockPos pos : islandMembers) {
            boolean reachableA = distFromA.containsKey(pos);
            boolean reachableB = distFromB.containsKey(pos);

            if (reachableA && reachableB) {
                double dA = distFromA.get(pos);
                double dB = distFromB.get(pos);
                double shorter = Math.min(dA, dB);
                double longer = Math.max(dA, dB);
                if (longer > 0) {
                    archFactorMap.put(pos, shorter / longer);
                } else {
                    archFactorMap.put(pos, 1.0); // 兩側等距 = 0
                }
            }
            // 單側或不可達 → archFactor = 0（預設，不放入 map）
        }

        return archFactorMap;
    }

    /**
     * 從一組錨點出發，BFS 到 island 中所有可達方塊，記錄最短 Manhattan 距離。
     * BFS 沿所有 6 方向擴展（含垂直），距離 = 步數。
     */
    private static Map<BlockPos, Double> bfsFromGroup(Set<BlockPos> group,
                                                       Set<BlockPos> islandMembers) {
        Map<BlockPos, Double> dist = new HashMap<>();
        Deque<BlockPos> queue = new ArrayDeque<>();

        for (BlockPos anchor : group) {
            if (islandMembers.contains(anchor)) {
                dist.put(anchor, 0.0);
                queue.add(anchor);
            }
        }

        while (!queue.isEmpty()) {
            BlockPos cur = queue.poll();
            double curDist = dist.get(cur);

            for (Direction dir : Direction.values()) {
                BlockPos nb = cur.relative(dir);
                if (islandMembers.contains(nb) && !dist.containsKey(nb)) {
                    dist.put(nb, curDist + 1.0);
                    queue.add(nb);
                }
            }
        }

        return dist;
    }

    // ═══════════════════════════════════════════════════════════════
    //  源項計算
    // ═══════════════════════════════════════════════════════════════

    /**
     * 計算單一體素的源項 ρ'（含力矩修正和拱效應）。
     *
     * <pre>
     * baseWeight  = density × fillRatio × GRAVITY × BLOCK_VOLUME
     * momentFactor = 1 + MOMENT_ALPHA × arm × (1 - archFactor)
     * source = baseWeight × momentFactor
     * </pre>
     *
     * @param mat        材料（非 null）
     * @param fillRatio  鑿刻填充率 ∈ [0.0, 1.0]
     * @param arm        水平力臂（到錨點的水平 Manhattan 距離）
     * @param archFactor 拱效應因子 ∈ [0.0, 1.0]
     * @return 源項 ρ'（N，牛頓）
     */
    public static float computeSource(RMaterial mat, float fillRatio,
                                       int arm, double archFactor) {
        if (mat == null || mat.isIndestructible()) return 0.0f;

        double baseWeight = mat.getDensity() * fillRatio * GRAVITY * BLOCK_VOLUME;
        double momentFactor = 1.0 + MOMENT_ALPHA * arm * (1.0 - archFactor);
        return (float) (baseWeight * momentFactor);
    }

    /**
     * 計算材料的 maxPhi（勢能容量 / 等效懸臂極限）。
     *
     * <pre>
     * maxSpan = floor(sqrt(Rtens) × 2.0)  （與 WACEngine 一致）
     * maxPhi = maxSpan × avgWeight × GRAVITY
     * </pre>
     *
     * @param mat 材料
     * @return maxPhi（超過此值觸發 CANTILEVER_BREAK）
     */
    public static float computeMaxPhi(RMaterial mat) {
        if (mat == null || mat.isIndestructible()) return Float.MAX_VALUE;

        double rtens = mat.getRtens();
        int maxSpan = (int) Math.floor(Math.sqrt(rtens) * 2.0);
        maxSpan = Math.max(maxSpan, 1);
        maxSpan = Math.min(maxSpan, 64);

        // avgWeight：使用該材料自重作為參考
        double avgWeight = mat.getDensity() * GRAVITY * BLOCK_VOLUME;
        return (float) (maxSpan * avgWeight);
    }
}
