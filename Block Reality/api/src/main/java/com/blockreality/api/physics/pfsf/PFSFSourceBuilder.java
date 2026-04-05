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
        return computeMaxPhi(mat, 0, 0.0);
    }

    /**
     * C4-fix: 計算空間相依的 maxPhi。
     * 考慮力臂和拱效應對破壞閾值的影響。
     *
     * @param mat        材料
     * @param arm        到最近錨點的水平距離
     * @param archFactor 拱效應因子 [0,1]
     * @return maxPhi
     */
    public static float computeMaxPhi(RMaterial mat, int arm, double archFactor) {
        if (mat == null || mat.isIndestructible()) return Float.MAX_VALUE;

        double rtens = mat.getRtens();
        int maxSpan = (int) Math.floor(Math.sqrt(rtens) * 2.0);
        maxSpan = Math.max(maxSpan, 1);
        maxSpan = Math.min(maxSpan, 64);

        double avgWeight = mat.getDensity() * GRAVITY * BLOCK_VOLUME;
        double basePhi = maxSpan * avgWeight;

        // C4-fix: 距錨點越遠的體素，容許的 phi 越高（因為正常累積更多）
        // 但不可超過材料極限。拱效應提升容許值。
        double armBonus = 1.0 + 0.1 * arm;
        double archBonus = 1.0 + 0.5 * archFactor;
        return (float) (basePhi * armBonus * archBonus);
    }

    // ═══════════════════════════════════════════════════════════════
    //  對角線虛擬邊（Diagonal Phantom Edges）
    //  替代 26-鄰域方案：CPU 端偵測邊/角連接，生成虛擬面連接
    //  GPU 仍跑 6-鄰域，零額外開銷
    // ═══════════════════════════════════════════════════════════════

    /** 12 個邊連接偏移（face-adjacent pairs 之外的 edge-adjacent） */
    private static final int[][] EDGE_OFFSETS = {
            {1,1,0}, {1,-1,0}, {-1,1,0}, {-1,-1,0},  // XY 平面邊
            {1,0,1}, {1,0,-1}, {-1,0,1}, {-1,0,-1},  // XZ 平面邊
            {0,1,1}, {0,1,-1}, {0,-1,1}, {0,-1,-1}   // YZ 平面邊
    };

    /**
     * 偵測只有邊/角連接（無面連接）的方塊對，為它們注入虛擬傳導率。
     * <p>
     * 原理：若方塊 A 和 B 只透過對角線相連（邊接觸或角接觸），
     * 在 6-鄰域下它們是「斷開的」。此方法找到這些對，
     * 在它們共享的面方向上注入一個衰減的 σ 值，
     * 讓 GPU 的 6-鄰域迭代「感知」到連接存在。
     *
     * @param members      island 成員
     * @param conductivity 已填好的 SoA conductivity 陣列（會被原地修改）
     * @param N            體素總數
     * @param Lx, Ly, Lz   網格尺寸
     * @param origin       AABB 原點
     * @param materialLookup 材料查詢
     * @return 注入的虛擬邊數量
     */
    public static int injectDiagonalPhantomEdges(
            Set<BlockPos> members, float[] conductivity, int N,
            int Lx, int Ly, int Lz, BlockPos origin,
            java.util.function.Function<BlockPos, RMaterial> materialLookup) {

        int injected = 0;
        // 衰減因子：邊連接的傳導率 = 面連接的 30%（面積比 ≈ 線/面 ≈ 0.3）
        float EDGE_FACTOR = 0.30f;

        for (BlockPos pos : members) {
            for (int[] offset : EDGE_OFFSETS) {
                BlockPos diag = pos.offset(offset[0], offset[1], offset[2]);
                if (!members.contains(diag)) continue;

                // 確認它們之間沒有面連接（即不是直接 6-鄰居）
                // 邊連接意味著兩個共享面方向上至少有一個是空的
                boolean hasFaceConnection = false;
                for (Direction dir : Direction.values()) {
                    BlockPos between = pos.relative(dir);
                    if (between.equals(diag)) { hasFaceConnection = true; break; }
                }
                if (hasFaceConnection) continue;

                // 找出最佳的面方向來注入虛擬 σ
                // 策略：選擇 offset 中非零分量對應的方向之一
                RMaterial matA = materialLookup != null ? materialLookup.apply(pos) : null;
                RMaterial matB = materialLookup != null ? materialLookup.apply(diag) : null;
                if (matA == null || matB == null) continue;

                float baseSigma = (float) Math.min(matA.getRcomp(), matB.getRcomp()) * EDGE_FACTOR;

                // 注入到第一個非零分量的方向
                int dirIdx = -1;
                if (offset[0] != 0) dirIdx = offset[0] > 0 ? DIR_POS_X : DIR_NEG_X;
                else if (offset[1] != 0) dirIdx = offset[1] > 0 ? DIR_POS_Y : DIR_NEG_Y;
                else if (offset[2] != 0) dirIdx = offset[2] > 0 ? DIR_POS_Z : DIR_NEG_Z;

                if (dirIdx < 0) continue;

                // 計算扁平索引
                int x = pos.getX() - origin.getX();
                int y = pos.getY() - origin.getY();
                int z = pos.getZ() - origin.getZ();
                if (x < 0 || x >= Lx || y < 0 || y >= Ly || z < 0 || z >= Lz) continue;
                int flatIdx = x + Lx * (y + Ly * z);

                // SoA layout: sigma[dir * N + i]
                // 只在現有 σ 為 0 時注入（不覆蓋已有的面連接）
                int idx = dirIdx * N + flatIdx;
                if (conductivity[idx] == 0.0f) {
                    conductivity[idx] = baseSigma;
                    injected++;
                }
            }
        }
        return injected;
    }
}
