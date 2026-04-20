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

    /** 8 個角連接偏移（三軸同時斜向，26-connectivity 的角方向） */
    private static final int[][] CORNER_OFFSETS = {
            {1,1,1}, {1,1,-1}, {1,-1,1}, {1,-1,-1},
            {-1,1,1}, {-1,1,-1}, {-1,-1,1}, {-1,-1,-1}
    };

    // ═══════════════════════════════════════════════════════════════
    //  v0.3d Phase 2 — grid-native siblings for the three topology
    //  primitives. They accept the same {@code (byte[N] members,
    //  byte[N] anchors, lx, ly, lz)} layout used by libpfsf_compute so
    //  that callers already holding flat buffers (future plan-buffer
    //  path, JMH harnesses, GoldenParityTest) can bypass the
    //  BlockPos-keyed Java ref. Delegation route:
    //    hasComputeV2() → libpfsf_compute (fast path)
    //      else → ...JavaRef (bit-exact mirror)
    //  The original {@code Set<BlockPos>} entry points stay untouched —
    //  those live in Java until the island-buffer rewrite lands.
    // ═══════════════════════════════════════════════════════════════

    /**
     * Grid-native horizontal arm map. See
     * {@link #computeHorizontalArmMap(Set, Set)} for the world-space
     * entry point.
     *
     * @param members  byte[N] — 1 for island members, 0 elsewhere
     * @param anchors  byte[N] — 1 for anchors intersected with members
     * @param outArm   int32[N] — populated with Manhattan-X-Z distance
     *                 from the nearest anchor; 0 for unreachable / non-members
     */
    public static int computeArmMapGrid(byte[] members, byte[] anchors,
                                          int lx, int ly, int lz,
                                          int[] outArm) {
        if (NativePFSFBridge.hasComputeV2()) {
            try {
                return NativePFSFBridge.nativeComputeArmMap(members, anchors, lx, ly, lz, outArm);
            } catch (UnsatisfiedLinkError e) {
                // Binary loaded but symbol absent — fall back to javaRef.
            }
        }
        return computeArmMapGridJavaRef(members, anchors, lx, ly, lz, outArm);
    }

    /** Java reference — never deleted; see class-level note. */
    static int computeArmMapGridJavaRef(byte[] members, byte[] anchors,
                                          int lx, int ly, int lz,
                                          int[] outArm) {
        final int N = lx * ly * lz;
        final int UNREACHED = -1;
        for (int i = 0; i < N; i++) {
            outArm[i] = (members[i] != 0) ? UNREACHED : 0;
        }

        int[] queue = new int[N];
        int qHead = 0, qTail = 0;
        for (int i = 0; i < N; i++) {
            if (anchors[i] != 0 && members[i] != 0) {
                outArm[i] = 0;
                queue[qTail++] = i;
            }
        }
        // Horizontal-only: -X,+X,-Z,+Z
        final int[] dx = { -1, 1, 0, 0 };
        final int[] dz = {  0, 0,-1, 1 };
        final int lxy = lx * ly;
        while (qHead < qTail) {
            int cur = queue[qHead++];
            int cz = cur / lxy;
            int rem = cur - cz * lxy;
            int cy = rem / lx;
            int cx = rem - cy * lx;
            int curArm = outArm[cur];
            for (int k = 0; k < 4; k++) {
                int nx = cx + dx[k], nz = cz + dz[k];
                if (nx < 0 || nx >= lx || nz < 0 || nz >= lz) continue;
                int nb = nx + lx * (cy + ly * nz);
                if (members[nb] == 0) continue;
                if (outArm[nb] != UNREACHED) continue;
                outArm[nb] = curArm + 1;
                queue[qTail++] = nb;
            }
        }
        for (int i = 0; i < N; i++) if (outArm[i] == UNREACHED) outArm[i] = 0;
        return 0;
    }

    /**
     * Grid-native arch factor map — dual-path BFS.
     *
     * @param outArch float[N] — 0 for unreachable/single-sided, shorter/longer in [0,1] otherwise.
     */
    public static int computeArchFactorMapGrid(byte[] members, byte[] anchors,
                                                  int lx, int ly, int lz,
                                                  float[] outArch) {
        if (NativePFSFBridge.hasComputeV2()) {
            try {
                return NativePFSFBridge.nativeComputeArchFactorMap(members, anchors, lx, ly, lz, outArch);
            } catch (UnsatisfiedLinkError e) {
                // fall through
            }
        }
        return computeArchFactorMapGridJavaRef(members, anchors, lx, ly, lz, outArch);
    }

    static int computeArchFactorMapGridJavaRef(byte[] members, byte[] anchors,
                                                  int lx, int ly, int lz,
                                                  float[] outArch) {
        final int N = lx * ly * lz;
        for (int i = 0; i < N; i++) outArch[i] = 0.0f;

        int anchorCount = 0;
        for (int i = 0; i < N; i++) if (anchors[i] != 0 && members[i] != 0) anchorCount++;
        if (anchorCount < 2) return 0;

        int[] parent = new int[N];
        for (int i = 0; i < N; i++) parent[i] = i;
        final int lxy = lx * ly;
        for (int i = 0; i < N; i++) {
            if (anchors[i] == 0 || members[i] == 0) continue;
            int cz = i / lxy;
            int rem = i - cz * lxy;
            int cy = rem / lx;
            int cx = rem - cy * lx;
            int[] hx = { -1, 1, 0, 0 };
            int[] hz = {  0, 0,-1, 1 };
            for (int k = 0; k < 4; k++) {
                int nx = cx + hx[k], nz = cz + hz[k];
                if (nx < 0 || nx >= lx || nz < 0 || nz >= lz) continue;
                int nb = nx + lx * (cy + ly * nz);
                if (anchors[nb] != 0 && members[nb] != 0) {
                    int ra = findRoot(parent, i);
                    int rb = findRoot(parent, nb);
                    if (ra != rb) parent[ra] = rb;
                }
            }
        }

        // Bucket anchors by root.
        java.util.Map<Integer, java.util.List<Integer>> buckets = new java.util.LinkedHashMap<>();
        for (int i = 0; i < N; i++) {
            if (anchors[i] == 0 || members[i] == 0) continue;
            int r = findRoot(parent, i);
            buckets.computeIfAbsent(r, k -> new java.util.ArrayList<>()).add(i);
        }
        if (buckets.size() < 2) return 0;
        java.util.List<java.util.List<Integer>> sorted = new java.util.ArrayList<>(buckets.values());
        sorted.sort((a, b) -> Integer.compare(b.size(), a.size()));

        int[] distA = new int[N];
        int[] distB = new int[N];
        bfs6Conn(sorted.get(0), members, lx, ly, lz, distA);
        bfs6Conn(sorted.get(1), members, lx, ly, lz, distB);
        final int UNREACHED = Integer.MAX_VALUE;
        for (int i = 0; i < N; i++) {
            if (members[i] == 0) continue;
            int dA = distA[i], dB = distB[i];
            if (dA == UNREACHED || dB == UNREACHED) continue;
            float fa = dA, fb = dB;
            float shorter = Math.min(fa, fb);
            float longer  = Math.max(fa, fb);
            outArch[i] = longer > 0.0f ? shorter / longer : 1.0f;
        }
        return 0;
    }

    private static int findRoot(int[] parent, int x) {
        while (parent[x] != x) {
            parent[x] = parent[parent[x]];
            x = parent[x];
        }
        return x;
    }

    private static void bfs6Conn(java.util.List<Integer> sources, byte[] members,
                                   int lx, int ly, int lz, int[] outDist) {
        final int N = lx * ly * lz;
        for (int i = 0; i < N; i++) outDist[i] = Integer.MAX_VALUE;
        int[] queue = new int[N];
        int qHead = 0, qTail = 0;
        for (int s : sources) {
            outDist[s] = 0;
            queue[qTail++] = s;
        }
        final int[] dx = { -1, 1, 0, 0, 0, 0 };
        final int[] dy = {  0, 0,-1, 1, 0, 0 };
        final int[] dz = {  0, 0, 0, 0,-1, 1 };
        final int lxy = lx * ly;
        while (qHead < qTail) {
            int cur = queue[qHead++];
            int cz = cur / lxy;
            int rem = cur - cz * lxy;
            int cy = rem / lx;
            int cx = rem - cy * lx;
            int curDist = outDist[cur];
            for (int k = 0; k < 6; k++) {
                int nx = cx + dx[k], ny = cy + dy[k], nz = cz + dz[k];
                if (nx < 0 || nx >= lx || ny < 0 || ny >= ly || nz < 0 || nz >= lz) continue;
                int nb = nx + lx * (ny + ly * nz);
                if (members[nb] == 0) continue;
                if (outDist[nb] != Integer.MAX_VALUE) continue;
                outDist[nb] = curDist + 1;
                queue[qTail++] = nb;
            }
        }
    }

    /**
     * Grid-native phantom edge injector.
     *
     * @return number of diagonal slots written
     */
    public static int injectPhantomEdgesGrid(byte[] members,
                                               float[] conductivity,
                                               float[] rcomp,
                                               int lx, int ly, int lz,
                                               float edgePenalty,
                                               float cornerPenalty) {
        if (NativePFSFBridge.hasComputeV2()) {
            try {
                return NativePFSFBridge.nativeInjectPhantomEdges(
                        members, conductivity, rcomp, lx, ly, lz, edgePenalty, cornerPenalty);
            } catch (UnsatisfiedLinkError e) {
                // fall through
            }
        }
        return injectPhantomEdgesGridJavaRef(members, conductivity, rcomp, lx, ly, lz, edgePenalty, cornerPenalty);
    }

    static int injectPhantomEdgesGridJavaRef(byte[] members,
                                               float[] conductivity,
                                               float[] rcomp,
                                               int lx, int ly, int lz,
                                               float edgePenalty,
                                               float cornerPenalty) {
        final int N = lx * ly * lz;
        int injected = 0;
        for (int z = 0; z < lz; z++) {
            for (int y = 0; y < ly; y++) {
                for (int x = 0; x < lx; x++) {
                    int self = x + lx * (y + ly * z);
                    if (members[self] == 0) continue;
                    for (int[] off : EDGE_OFFSETS) {
                        int nx = x + off[0], ny = y + off[1], nz = z + off[2];
                        if (nx < 0 || nx >= lx || ny < 0 || ny >= ly || nz < 0 || nz >= lz) continue;
                        int nb = nx + lx * (ny + ly * nz);
                        if (members[nb] == 0) continue;
                        int dirIdx;
                        if (off[0] != 0) dirIdx = off[0] > 0 ? DIR_POS_X : DIR_NEG_X;
                        else if (off[1] != 0) dirIdx = off[1] > 0 ? DIR_POS_Y : DIR_NEG_Y;
                        else dirIdx = off[2] > 0 ? DIR_POS_Z : DIR_NEG_Z;
                        float ra = rcomp[self], rb = rcomp[nb];
                        float base = Math.min(ra, rb) * edgePenalty;
                        int slot = dirIdx * N + self;
                        if (conductivity[slot] == 0.0f) {
                            conductivity[slot] = base;
                            injected++;
                        }
                    }
                    for (int[] off : CORNER_OFFSETS) {
                        int nx = x + off[0], ny = y + off[1], nz = z + off[2];
                        if (nx < 0 || nx >= lx || ny < 0 || ny >= ly || nz < 0 || nz >= lz) continue;
                        int nb = nx + lx * (ny + ly * nz);
                        if (members[nb] == 0) continue;
                        int dirIdx = off[0] > 0 ? DIR_POS_X : DIR_NEG_X;
                        float ra = rcomp[self], rb = rcomp[nb];
                        float base = Math.min(ra, rb) * cornerPenalty;
                        int slot = dirIdx * N + self;
                        if (conductivity[slot] == 0.0f) {
                            conductivity[slot] = base;
                            injected++;
                        }
                    }
                }
            }
        }
        return injected;
    }

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
        // 衰減因子：邊連接的傳導率 = 面連接的 30%（符合 SHEAR_EDGE_PENALTY）
        float EDGE_FACTOR = 0.30f;
        // 衰減因子：角連接的傳導率 = 面連接的 15%（符合 SHEAR_CORNER_PENALTY）
        float CORNER_FACTOR = 0.15f;

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

            // ─── 角連接（8 個角方向，SHEAR_CORNER_PENALTY = 0.15） ───
            for (int[] offset : CORNER_OFFSETS) {
                BlockPos diag = pos.offset(offset[0], offset[1], offset[2]);
                if (!members.contains(diag)) continue;

                RMaterial matA = materialLookup != null ? materialLookup.apply(pos) : null;
                RMaterial matB = materialLookup != null ? materialLookup.apply(diag) : null;
                if (matA == null || matB == null) continue;

                float baseSigma = (float) Math.min(matA.getRcomp(), matB.getRcomp()) * CORNER_FACTOR;

                // 角連接注入到第一個非零分量方向（X 軸優先）
                int dirIdx = offset[0] > 0 ? DIR_POS_X : DIR_NEG_X;

                int x = pos.getX() - origin.getX();
                int y = pos.getY() - origin.getY();
                int z = pos.getZ() - origin.getZ();
                if (x < 0 || x >= Lx || y < 0 || y >= Ly || z < 0 || z >= Lz) continue;
                int flatIdx = x + Lx * (y + Ly * z);

                int idx = dirIdx * N + flatIdx;
                if (conductivity[idx] == 0.0f) {
                    conductivity[idx] = baseSigma;
                    injected++;
                }
            }
        }
        return injected;
    }

    // ═══════════════════════════════════════════════════════════════
    //  v2: 風壓動態源項 (Eurocode 1 簡化)
    // ═══════════════════════════════════════════════════════════════

    /**
     * 計算暴露面體素的風壓等效源項。
     *
     * <p>Eurocode 1 公式：q_p = 0.5 × ρ_air × v² × C_p</p>
     * <p>在勢場框架中，風壓轉化為側向源項加到暴露面體素上。</p>
     *
     * @param windSpeed   風速 (m/s)，由 BRConfig 取得
     * @param density     方塊材料密度 (kg/m³)
     * @param isExposed   該面是否暴露於空氣
     * @return 風壓等效源項（疊加到 ρ 上）
     */
    public static float computeWindPressure(float windSpeed, float density, boolean isExposed) {
        // v0.3d Phase 1: route to libpfsf_compute when available; the native
        // implementation is a bit-exact mirror of the Java reference and is
        // guarded by the golden-parity test.
        if (NativePFSFBridge.hasComputeV1()) {
            try {
                return NativePFSFBridge.nativeWindPressureSource(windSpeed, density, isExposed);
            } catch (UnsatisfiedLinkError e) {
                // Binary loaded but this symbol is absent — fall through.
            }
        }
        return computeWindPressureJavaRef(windSpeed, density, isExposed);
    }

    /**
     * Java reference implementation — never deleted.
     * Serves as: (1) source of truth for the native port, (2) GPU-less
     * dev fallback, (3) safety net for cross-generation ABI migrations.
     */
    static float computeWindPressureJavaRef(float windSpeed, float density, boolean isExposed) {
        if (!isExposed || windSpeed <= 0) return 0.0f;
        // q = WIND_BASE_PRESSURE × v² (MPa)
        // 轉為等效體積力密度：f_wind = q / (density × blockVolume)
        // 這使其與重力源項 ρ = density × g × volume 量綱一致
        float qMPa = PFSFConstants.WIND_BASE_PRESSURE * windSpeed * windSpeed;
        if (density <= 0) return 0.0f;
        // Convert to Pa for source term computation to match gravity dimension logic,
        // since density is in kg/m³ and gravity in m/s², the gravity body force is in N/m³ (which is Pa/m).
        // 1 MPa = 1e6 Pa
        float qPa = qMPa * 1e6f;
        return qPa / (density * (float) PFSFConstants.BLOCK_VOLUME);
    }

    // ═══════════════════════════════════════════════════════════════
    //  v2: Timoshenko 力矩預處理
    // ═══════════════════════════════════════════════════════════════

    /**
     * 使用 Timoshenko 樑理論計算等效源項修正因子，取代經驗常數 α/β。
     *
     * <p>截面慣性矩 I = b×h³/12 + Timoshenko 剪切修正 κ = 10(1+ν)/(12+11ν)</p>
     * <p>等效力矩放大：M_factor = 1 + (arm² × g × A) / (κ × G × I)</p>
     *
     * @param sectionWidth  截面寬度（格數，通常 1）
     * @param sectionHeight 截面高度（格數，通過局部 BFS 估計）
     * @param arm           水平力臂（到最近錨點的水平 Manhattan 距離）
     * @param youngsModulusGPa 楊氏模量 (GPa)
     * @param poissonRatio  泊松比（混凝土 ~0.2，鋼 ~0.3）
     * @return 力矩修正因子 ≥ 1.0
     */
    public static float computeTimoshenkoMomentFactor(float sectionWidth, float sectionHeight,
                                                       int arm, float youngsModulusGPa,
                                                       float poissonRatio) {
        // v0.3d Phase 1: route to libpfsf_compute when available.
        if (NativePFSFBridge.hasComputeV1()) {
            try {
                return NativePFSFBridge.nativeTimoshenkoMomentFactor(
                        sectionWidth, sectionHeight, arm, youngsModulusGPa, poissonRatio);
            } catch (UnsatisfiedLinkError e) {
                // Binary loaded but this symbol is absent — fall through.
            }
        }
        return computeTimoshenkoMomentFactorJavaRef(
                sectionWidth, sectionHeight, arm, youngsModulusGPa, poissonRatio);
    }

    /** Java reference implementation — never deleted (see class-level note). */
    static float computeTimoshenkoMomentFactorJavaRef(float sectionWidth, float sectionHeight,
                                                        int arm, float youngsModulusGPa,
                                                        float poissonRatio) {
        if (arm <= 0 || sectionHeight <= 0) return 1.0f;

        // 截面慣性矩 I = b * h³ / 12 (m⁴)
        float b = Math.max(sectionWidth, 1.0f);
        float h = Math.max(sectionHeight, 1.0f);
        float I = b * h * h * h / 12.0f;

        // Timoshenko 剪切修正因子 κ
        float nu = Math.max(0.0f, Math.min(poissonRatio, 0.5f));
        float kappa = 10.0f * (1.0f + nu) / (12.0f + 11.0f * nu);

        // 剪切模量 G = E / (2(1+ν)) (GPa → Pa)
        float E_Pa = youngsModulusGPa * 1e9f;
        float G_Pa = E_Pa / (2.0f * (1.0f + nu));

        // 截面積 A = b * h (m²)
        float A = b * h;

        // 力矩放大因子：考慮剪切變形的修正
        // 對於短深樑 (h > L/5)，剪切修正顯著；長跨樑 (h < L/10) 退化為 ~1
        float shearContribution = (float) (arm * arm) * (float) PFSFConstants.GRAVITY * A
                / (kappa * G_Pa * I + 1e-10f);
        return 1.0f + Math.min(shearContribution, 10.0f); // 上限防止極端值
    }

    /**
     * 使用 Timoshenko 修正計算 maxPhi，取代經驗 α/β 公式。
     */
    public static float computeMaxPhiTimoshenko(com.blockreality.api.material.RMaterial mat,
                                                 int arm, float sectionHeight) {
        if (mat == null) return 0.0f;
        float baseMaxPhi = (float) (mat.getRcomp() * 1e6 / (PFSFConstants.GRAVITY * mat.getDensity()));
        float factor = computeTimoshenkoMomentFactor(
                1.0f, sectionHeight, arm,
                (float) mat.getYoungsModulusGPa(),
                PFSFConstants.DEFAULT_POISSON_RATIO);
        return baseMaxPhi / factor;
    }
}
