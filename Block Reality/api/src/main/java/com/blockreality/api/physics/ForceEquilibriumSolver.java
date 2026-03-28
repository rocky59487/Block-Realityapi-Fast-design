package com.blockreality.api.physics;

import com.blockreality.api.material.RMaterial;
import net.minecraft.core.BlockPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.concurrent.NotThreadSafe;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gustave-Inspired Force Equilibrium Solver — Iterative relaxation-based load distribution.
 *
 * 靈感來源：Gustave 結構分析庫的力平衡概念。
 * 取代傳統的 BFS-weighted 啟發式方法，改用牛頓第一定律求解每個節點的力平衡。
 *
 * 核心假設：
 *   1. 每個非錨點方塊必須滿足力的平衡（Σ F = 0）
 *   2. 重力向下施加（密度 × g），由下方/側方的支撐力承受
 *   3. 錨點擁有無限支撐容量
 *   4. 上方方塊的載重傳遞到下方/側方（負載分配）
 *
 * 算法：Gauss-Seidel 迭代鬆弛法（高效、無需矩陣求解）
 *   Max iterations: 100
 *   Convergence threshold: 0.01 (最大力 delta < 0.01N)
 *
 * @author Claude AI
 * @version 1.0 (Gustave Integration)
 */
@NotThreadSafe  // Static methods only; must be called from server thread
public class ForceEquilibriumSolver {

    private static final Logger LOGGER = LogManager.getLogger("BR-ForceEquilibrium");

    /** ★ review-fix #12: 使用共用常數 */
    private static final double GRAVITY = PhysicsConstants.GRAVITY;

    /** 最大迭代次數 */
    private static final int MAX_ITERATIONS = 100;

    /**
     * ★ v4-fix: 相對收斂閾值 — 取代絕對閾值 (0.01N)。
     * 使用相對誤差 = maxDelta / maxForce，對任意規模結構都適用。
     * 0.001 = 0.1% 的力變化即視為收斂。
     */
    private static final double RELATIVE_CONVERGENCE_THRESHOLD = 0.001;

    /** 絕對收斂下限 (N) — 當最大力極小時防止除零 */
    private static final double ABSOLUTE_CONVERGENCE_FLOOR = 0.01;

    /**
     * ★ 移除節點層級早期終止 — 改為僅全局殘差判定收斂。
     * 原因（Gauss-Seidel / SOR 理論）：
     *   節點 A 可能在節點 B 更新前「暫時收斂」，但 B 更新後 A 的力平衡被打破。
     *   先收斂的節點跳過更新 → 非對稱誤差累積 → 偽收斂。
     *   ScienceDirect / MIT 11.3 建議全局殘差 ‖r‖ < ε 作為唯一終止條件。
     */

    /** 預設 SOR 鬆弛參數 (ω) — 區間 [1.0, 2.0) */
    private static final double DEFAULT_OMEGA = 1.25;

    /** 最小鬆弛參數（防止發散） */
    private static final double MIN_OMEGA = 1.05;

    /** 最大鬆弛參數（防止振盪） */
    private static final double MAX_OMEGA = 1.95;

    /** 鬆弛參數調整步幅 */
    private static final double OMEGA_ADJUST_STEP = 0.05;

    /** 收斂率閾值（判定是否緩慢收斂） */
    private static final double SLOW_CONVERGENCE_RATIO = 0.95;

    // ─── Warm-Start Cache (v7 M-2) ───

    /**
     * ★ M-2: Warm-start cache — saves previous solve results for incremental updates.
     *
     * #5 fix: Key 改用 long fingerprint 替代 Set.hashCode()（int），大幅降低碰撞率。
     *         fingerprint = sorted BlockPos.asLong stream 的 polynomial rolling hash。
     *
     * #6 fix: 改用 LinkedHashMap(accessOrder=true) + synchronizedMap 實現真正的 LRU 驅逐。
     *         原先的 ConcurrentHashMap.keySet().iterator().next() 是隨機驅逐，不是 LRU。
     *
     * Value: Map of BlockPos → last converged totalForce
     *
     * When a structure changes by only 1-2 blocks, warm-start provides near-converged
     * initial values, reducing iterations from ~40 to ~5-10.
     */
    private static final int WARM_START_MAX_ENTRIES = 64;

    @SuppressWarnings("serial")
    private static final Map<Long, Map<BlockPos, Double>> WARM_START_CACHE =
        Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, Map<BlockPos, Double>> eldest) {
                return size() > WARM_START_MAX_ENTRIES;
            }
        });

    /**
     * 水平 4 方向（不含 UP/DOWN）— 供 distributeLoad 側方支撐遍歷使用。
     * ★ new-fix N3: 原先 distributeLoad 每次呼叫建立匿名 new int[][]{{...}}，
     * 在熱路徑（每 tick 每個非錨點節點各呼叫一次）造成無謂 heap 分配。
     * 改為 static final 常數，零分配。
     * 原 NEIGHBOR_DIRS（6方向）移除，改為更精確命名的 HORIZONTAL_DIRS（4方向）。
     */
    private static final int[][] HORIZONTAL_DIRS = {
        {1, 0, 0},   // EAST
        {-1, 0, 0},  // WEST
        {0, 0, 1},   // SOUTH
        {0, 0, -1}   // NORTH
    };

    /**
     * 力平衡求解結果。
     *
     * @param totalForce      方塊承受的總力 (N，正=壓)
     * @param supportForce    下方/側方支撐力 (N)
     * @param isStable        力平衡判定 (true=穩定，false=無有效支撐)
     * @param utilizationRatio 強度利用率 (0.0~1.0+)
     */
    public record ForceResult(
        double totalForce,
        double supportForce,
        boolean isStable,
        double utilizationRatio
    ) {}

    /**
     * 求解收斂診斷信息。
     *
     * @param iterationCount    實際迭代次數
     * @param finalResidual     最終剩餘誤差 (N)
     * @param converged         是否成功收斂
     * @param finalOmega        最終使用的鬆弛參數
     * @param elapsedMillis     總耗時 (毫秒)
     */
    public record ConvergenceDiagnostics(
        int iterationCount,
        double finalResidual,
        boolean converged,
        double finalOmega,
        long elapsedMillis
    ) {}

    /**
     * 內部節點狀態 — 迭代期間追蹤力分配。
     * ★ review-fix #10: 改為 mutable class，避免每次迭代為每個節點分配新 record 物件。
     * 原先 O(N×iter) 的 record 分配（100 iter × 1000 nodes = 100K 物件）造成顯著 GC 壓力。
     */
    private static final class NodeState {
        final BlockPos pos;
        final RMaterial material;
        final double weight;
        final boolean isAnchor;
        final List<BlockPos> dependents;
        /** 有效截面積 (m²) — 雕刻形狀可能小於 1.0 */
        final double effectiveArea;
        double supportForce;
        double totalForce;
        double lastTotalForce;
        boolean converged;

        NodeState(BlockPos pos, RMaterial material, double weight, double supportForce,
                  double totalForce, boolean isAnchor, List<BlockPos> dependents,
                  double lastTotalForce, boolean converged, double effectiveArea) {
            this.pos = pos;
            this.material = material;
            this.weight = weight;
            this.supportForce = supportForce;
            this.totalForce = totalForce;
            this.isAnchor = isAnchor;
            this.dependents = dependents;
            this.lastTotalForce = lastTotalForce;
            this.converged = converged;
            this.effectiveArea = effectiveArea;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 主求解入口
    // ═══════════════════════════════════════════════════════════════

    /**
     * 對結構進行力平衡分析 - 使用 Successive Over-Relaxation (SOR) 加速收斂。
     *
     * 算法說明：
     * - 使用 SOR 方法（Gauss-Seidel 的加速變體）加速迭代收斂
     * - 鬆弛參數 ω (omega) 預設 1.25，可根據收斂速率自適應調整
     * - 若收斂緩慢（殘差比 > 0.95），增加 ω；若發散，減少 ω
     * - 使用全局殘差 ‖r‖ < ε 作為唯一終止條件（避免偽收斂）
     *
     * SOR 更新公式：
     *   x_new = (1 - ω) * x_old + ω * x_computed
     *
     * 參數：
     * @param blocks       所有方塊位置
     * @param materials    各方塊對應的材料
     * @param anchors      錨定點集合 (無限支撐容量)
     *
     * 返回值：
     * @return 每個方塊的力平衡結果
     *
     * 複雜度：
     * - 時間：O(N × iter)，其中 N 為節點數，iter 通常 < 100
     * - 空間：O(N) 用於節點狀態追蹤
     *
     * @see #solveWithDiagnostics(Set, Map, Set, double)
     */
    public static Map<BlockPos, ForceResult> solve(
        Set<BlockPos> blocks,
        Map<BlockPos, RMaterial> materials,
        Set<BlockPos> anchors
    ) {
        return solveWithDiagnostics(blocks, materials, anchors, DEFAULT_OMEGA, Collections.emptyMap()).results();
    }

    /**
     * ★ audit-fix C-4: 支援逐方塊截面積的求解入口。
     * 雕刻形狀的截面積 < 1.0m²，需透過此 overload 傳入。
     *
     * @param effectiveAreas 方塊位置 → 有效截面積 (m²)。未列入的方塊使用 BLOCK_AREA (1.0)。
     */
    public static Map<BlockPos, ForceResult> solve(
        Set<BlockPos> blocks,
        Map<BlockPos, RMaterial> materials,
        Set<BlockPos> anchors,
        Map<BlockPos, Float> effectiveAreas
    ) {
        return solveWithDiagnostics(blocks, materials, anchors, DEFAULT_OMEGA, effectiveAreas).results();
    }

    /**
     * 對結構進行力平衡分析，返回詳細的收斂診斷信息。
     *
     * @param blocks       所有方塊位置
     * @param materials    各方塊對應的材料
     * @param anchors      錨定點集合
     * @param initialOmega 初始 SOR 鬆弛參數 (建議 1.25)
     * @return 包含結果和診斷的複合對象
     */
    public static SolverResult solveWithDiagnostics(
        Set<BlockPos> blocks,
        Map<BlockPos, RMaterial> materials,
        Set<BlockPos> anchors,
        double initialOmega
    ) {
        return solveWithDiagnostics(blocks, materials, anchors, initialOmega, Collections.emptyMap());
    }

    /**
     * ★ audit-fix C-4: 完整版求解入口，支援逐方塊截面積。
     */
    public static SolverResult solveWithDiagnostics(
        Set<BlockPos> blocks,
        Map<BlockPos, RMaterial> materials,
        Set<BlockPos> anchors,
        double initialOmega,
        Map<BlockPos, Float> effectiveAreas
    ) {
        long startTime = System.nanoTime();

        // 初始化節點狀態（★ audit-fix C-4: 傳入 effectiveAreas）
        Map<BlockPos, NodeState> nodeStates = initializeNodeStates(blocks, materials, anchors, effectiveAreas);

        // ★ review-fix #19: 排序一次，供所有迭代重複使用（節省 O(N log N) × iter 開銷）
        List<BlockPos> sortedByY = new ArrayList<>(blocks);
        sortedByY.sort(Comparator.comparingInt(BlockPos::getY));

        // SOR 迭代迴圈（自適應鬆弛參數）
        boolean converged = false;
        int iterationCount = 0;
        double currentOmega = Math.max(MIN_OMEGA, Math.min(MAX_OMEGA, initialOmega));
        double lastResidual = Double.MAX_VALUE;

        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            iterationCount = iter + 1;
            double maxForceDelta = iterationStepWithSOR(nodeStates, sortedByY, currentOmega);

            // 自適應調整鬆弛參數
            if (iter > 0) {
                double convergenceRatio = maxForceDelta / lastResidual;
                if (convergenceRatio > SLOW_CONVERGENCE_RATIO) {
                    // 收斂過慢，增加 ω 加速
                    currentOmega = Math.min(MAX_OMEGA, currentOmega + OMEGA_ADJUST_STEP);
                    LOGGER.debug("[ForceEquilibrium] Slow convergence, increasing ω to {}", String.format("%.3f", currentOmega));
                } else if (convergenceRatio < 0.0 || Double.isInfinite(maxForceDelta)) {
                    // 發散或數值異常，減少 ω 穩定
                    currentOmega = Math.max(MIN_OMEGA, currentOmega - OMEGA_ADJUST_STEP);
                    LOGGER.debug("[ForceEquilibrium] Divergence detected, decreasing ω to {}", String.format("%.3f", currentOmega));
                }
            }

            lastResidual = maxForceDelta;

            // ★ v4-fix: 相對收斂判定 — 適用任意規模結構
            // 找出當前最大力，用於計算相對誤差
            double maxForce = 0.0;
            for (NodeState ns : nodeStates.values()) {
                maxForce = Math.max(maxForce, Math.abs(ns.totalForce));
            }
            boolean metRelativeThreshold = (maxForce > ABSOLUTE_CONVERGENCE_FLOOR)
                ? (maxForceDelta / maxForce) < RELATIVE_CONVERGENCE_THRESHOLD
                : maxForceDelta < ABSOLUTE_CONVERGENCE_FLOOR;

            if (metRelativeThreshold) {
                converged = true;
                LOGGER.debug("[ForceEquilibrium] Converged at iteration {} (delta={}, maxForce={}, relative={})",
                    iter, String.format("%.6f", maxForceDelta), String.format("%.1f", maxForce),
                    maxForce > 0 ? String.format("%.6f", maxForceDelta / maxForce) : "N/A");
                break;
            }
        }

        if (!converged) {
            LOGGER.warn("[ForceEquilibrium] Did not converge after {} iterations (residual: {})",
                iterationCount, String.format("%.6f", lastResidual));
        }

        // 轉換為結果格式
        Map<BlockPos, ForceResult> results = new HashMap<>();
        for (NodeState ns : nodeStates.values()) {
            RMaterial mat = ns.material;
            double util = calculateUtilization(ns, mat);
            // ★ audit-fix R2-1: 穩定性應比較 supportForce 與 totalForce（含累積荷載），
            //   舊版比較 ns.weight（僅自重），導致承載千噸的柱底方塊
            //   只要支撐自身 2 噸就判定 stable。
            boolean stable = ns.isAnchor || (ns.supportForce >= ns.totalForce * 0.9);
            results.put(ns.pos, new ForceResult(
                ns.totalForce,
                ns.supportForce,
                stable,
                util
            ));
        }

        long elapsed = (System.nanoTime() - startTime) / 1_000_000;
        ConvergenceDiagnostics diag = new ConvergenceDiagnostics(
            iterationCount,
            lastResidual,
            converged,
            currentOmega,
            elapsed
        );

        LOGGER.info("[ForceEquilibrium] Solved {} nodes in {}ms (iter={}, converged={}, ω={})",
            blocks.size(), elapsed, iterationCount, converged, String.format("%.3f", currentOmega));

        // ★ M-2: 保存收斂結果供下次 warm-start
        if (converged) {
            saveToWarmStartCache(blocks, materials, nodeStates);
        }

        return new SolverResult(results, diag);
    }

    /**
     * SOR 求解結果複合容器。
     *
     * @param results     每個方塊的力平衡結果
     * @param diagnostics 收斂診斷信息
     */
    public record SolverResult(
        Map<BlockPos, ForceResult> results,
        ConvergenceDiagnostics diagnostics
    ) {}

    // ═══════════════════════════════════════════════════════════════
    // 內部迭代邏輯
    // ═══════════════════════════════════════════════════════════════

    /**
     * 初始化節點狀態。
     * - 自重 = 密度 × g
     * - 依賴項 = 尋找上方的相鄰方塊
     * - ★ M-2: 若有 warm-start 快取，使用前次收斂力值作為初始猜測
     */
    private static Map<BlockPos, NodeState> initializeNodeStates(
        Set<BlockPos> blocks,
        Map<BlockPos, RMaterial> materials,
        Set<BlockPos> anchors,
        Map<BlockPos, Float> effectiveAreas
    ) {
        Map<BlockPos, NodeState> states = new HashMap<>();

        // ★ M-2: 嘗試讀取 warm-start 快取
        // #5 fix: 使用 long fingerprint 替代 int hashCode 降低碰撞率
        // ★ Score-fix #2: 傳入 materials，fingerprint 包含材料資訊
        long structureFingerprint = computeStructureFingerprint(blocks, materials);
        Map<BlockPos, Double> prevForces = WARM_START_CACHE.get(structureFingerprint);

        for (BlockPos pos : blocks) {
            RMaterial mat = materials.get(pos);
            if (mat == null) continue;

            // ★ audit-fix C-4: 從 effectiveAreas 讀取實際截面積，未指定則預設 BLOCK_AREA
            double area = effectiveAreas.containsKey(pos)
                ? effectiveAreas.get(pos).doubleValue()
                : BLOCK_AREA;

            // ★ audit-fix F-3: 自重 = density × volume × g
            //   完整方塊: volume = 1m × 1m × 1m = 1m³ → weight = density × g（與舊版一致）
            //   雕刻方塊: volume ≈ area × 1m（高度固定 1m），weight 依比例縮小
            double weight = mat.getDensity() * area * GRAVITY;  // kg/m³ × m² × 1m × m/s² = N
            boolean isAnchor = anchors.contains(pos);
            List<BlockPos> dependents = new ArrayList<>();

            // 尋找上方依賴（UP 方向）
            BlockPos above = pos.above();
            if (blocks.contains(above)) {
                dependents.add(above);
            }

            // ★ M-2: 使用 warm-start 或預設（自重）
            double initialForce = weight;
            if (prevForces != null) {
                Double cached = prevForces.get(pos);
                if (cached != null) {
                    initialForce = cached;
                }
            }

            NodeState ns = new NodeState(
                pos,
                mat,
                weight,
                0.0,            // 初始支撐力 = 0
                initialForce,   // ★ M-2: warm-start 或自重
                isAnchor,
                dependents,
                initialForce,   // lastTotalForce
                false,          // converged 初始 = false
                area            // ★ audit-fix C-4: 使用實際截面積
            );
            states.put(pos, ns);
        }

        return states;
    }

    /**
     * ★ M-2: 保存收斂結果到 warm-start 快取。
     * 使用 LRU-style 驅逐策略（超過上限時移除最早的條目）。
     */
    private static void saveToWarmStartCache(Set<BlockPos> blocks,
                                              Map<BlockPos, RMaterial> materials,
                                              Map<BlockPos, NodeState> nodeStates) {
        // #5 fix: long fingerprint  ★ Score-fix #2: 傳入 materials
        long structureFingerprint = computeStructureFingerprint(blocks, materials);
        Map<BlockPos, Double> forceMap = new HashMap<>();
        for (NodeState ns : nodeStates.values()) {
            forceMap.put(ns.pos, ns.totalForce);
        }
        // #6 fix: LinkedHashMap(accessOrder=true) 自動 LRU 驅逐（removeEldestEntry），
        // 不需要手動檢查 size 和移除
        WARM_START_CACHE.put(structureFingerprint, forceMap);
    }

    /**
     * #5 fix: 計算結構指紋（long）— 替代 Set.hashCode()（int）。
     *
     * ★ review-fix #8: 改用 FNV-1a 64-bit hash 替代 base-31 polynomial hash。
     * base-31 對 BlockPos.asLong 的位元分佈會造成系統性碰撞（相鄰結構高碰撞率）。
     * FNV-1a 使用 XOR-then-multiply 策略，對任意輸入都有更均勻的 avalanche 效果。
     *
     * ★ Score-fix #2: 納入材料強度資訊。原本只 hash BlockPos，導致形狀相同但
     * 材料不同的結構（例如同形狀的混凝土 vs 木材）命中相同 fingerprint，
     * 讀取到語義完全錯誤的 warm-start force，造成偽收斂或初始殘差過大。
     * 修法：對每個 pos，將 BlockPos.asLong() 與 material.getCombinedStrength() 的
     * IEEE 754 bits 一起 hash，確保形狀 + 材料都相同才命中快取。
     *
     * @param blocks    結構中所有方塊位置
     * @param materials 各位置對應的材料（可包含 null，視為空氣跳過）
     * @return 64-bit fingerprint
     */
    private static final long FNV1A_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV1A_PRIME = 0x100000001b3L;

    private static long computeStructureFingerprint(Set<BlockPos> blocks,
                                                     Map<BlockPos, RMaterial> materials) {
        // 排序後 hash：BlockPos.asLong() XOR (material.getCombinedStrength() bits)
        // 兩者 XOR 後再疊乘，確保位置和材料都影響 fingerprint
        return blocks.stream()
            .sorted(Comparator.comparingLong(BlockPos::asLong))
            .mapToLong(pos -> blockFingerprint(pos, materials.get(pos)))
            .reduce(FNV1A_OFFSET_BASIS, (hash, val) -> (hash ^ val) * FNV1A_PRIME);
    }

    /**
     * 單一方塊的 fingerprint 貢獻值。
     * 供 delta fingerprint 使用：增刪方塊時 XOR 進/出即可。
     */
    static long blockFingerprint(BlockPos pos, RMaterial mat) {
        long posHash = pos.asLong();
        long matBits = (mat != null)
            ? Double.doubleToRawLongBits(mat.getCombinedStrength())
            : 0L;
        return posHash ^ (matBits * FNV1A_PRIME);
    }

    // ★ audit-fix C-2: deltaFingerprint 已移除。
    // XOR delta 與 FNV-1a chain 不等價（XOR 是交換結合的，FNV-1a chain 是有序的），
    // 導致 delta 更新產生的 fingerprint 與全量重算不同，造成 warm-start cache 假命中。
    // 結構通常 < 1000 blocks，全量 computeStructureFingerprint 的 O(N log N) 完全可接受。

    /**
     * 執行一次 SOR (Successive Over-Relaxation) 迭代步驟。
     *
     * 核心 SOR 機制：
     * 1. 計算節點的新力值（基於當前狀態）
     * 2. 使用鬆弛公式：x_new = (1-ω)*x_old + ω*x_computed
     * 3. 全局殘差 ‖r‖ < ε 作為終止條件（呼叫端判定，見 solve 主迴圈）
     *
     * @param nodeStates 所有節點的當前狀態
     * @param sortedByY  按 Y 座標排序的方塊位置列表（★ review-fix #19: 由呼叫端排序一次傳入）
     * @param omega      SOR 鬆弛參數 (1.0 = Gauss-Seidel, 1.0~2.0 = SOR)
     * @return 此次迭代的全局最大力變化（剩餘誤差）
     */
    private static double iterationStepWithSOR(
        Map<BlockPos, NodeState> nodeStates,
        List<BlockPos> sortedByY,
        double omega
    ) {
        double maxForceDelta = 0.0;

        for (BlockPos pos : sortedByY) {
            NodeState ns = nodeStates.get(pos);
            if (ns == null || ns.isAnchor) continue;

            // ★ 全局收斂：不再跳過任何節點。所有節點每次迭代都參與更新，
            // 確保 Gauss-Seidel 的傳播性質正確（Wikipedia: Gauss-Seidel method）。

            // 計算此方塊的總載重 = 自重 + 上方依賴載重
            double totalLoad = ns.weight;
            for (BlockPos depPos : ns.dependents) {
                NodeState depState = nodeStates.get(depPos);
                if (depState != null) {
                    totalLoad += depState.totalForce;
                }
            }

            // 嘗試分配到下方/側方支撐點
            double distributedForce = distributeLoad(pos, totalLoad, nodeStates);

            // ═══════ SOR 鬆弛更新 ═══════
            // x_new = (1-ω)*x_old + ω*x_computed
            double oldForce = ns.totalForce;
            double computedForce = totalLoad;
            double newForce = (1.0 - omega) * oldForce + omega * computedForce;

            double forceDelta = Math.abs(newForce - oldForce);
            maxForceDelta = Math.max(maxForceDelta, forceDelta);

            // ★ review-fix #10: 就地更新 mutable NodeState，不再分配新物件
            ns.supportForce = distributedForce;
            ns.totalForce = newForce;
            ns.lastTotalForce = oldForce;
        }

        return maxForceDelta;
    }

    /**
     * 將方塊的載重分配到下方/側方支撐點。
     * 優先向下（重力方向），若下方無支撐則向側方。
     *
     * @return 實際分配到的支撐力
     */
    private static double distributeLoad(
        BlockPos pos,
        double load,
        Map<BlockPos, NodeState> nodeStates
    ) {
        // 優先下方
        BlockPos below = pos.below();
        NodeState belowState = nodeStates.get(below);
        if (belowState != null) {
            // 檢查下方支撐點的容量
            if (belowState.isAnchor || canSupport(belowState, load)) {
                return load;  // 下方支撐充足
            }
        }

        // 側方支撐（水平 4 方向）— 迭代式均分，保證力守恆
        // ★ new-fix N3: 改用 HORIZONTAL_DIRS 靜態常數，消除熱路徑匿名陣列分配
        // ★ audit-fix R2-2: 迭代式分配 — 失敗鄰居的份額重分配給剩餘支撐者
        //   原 F-1 修: Pass1 數存在鄰居、Pass2 用 canSupport 過濾 → 力洩漏
        //   新版: 反覆重分配直到全部分配或無人可撐，最多 4 輪（4 方向）

        // 收集候選側方支撐
        BlockPos[] sidePositions = new BlockPos[4];
        NodeState[] sideStates = new NodeState[4];
        boolean[] eligible = new boolean[4];
        int candidateCount = 0;

        for (int i = 0; i < HORIZONTAL_DIRS.length; i++) {
            int[] dir = HORIZONTAL_DIRS[i];
            sidePositions[i] = new BlockPos(pos.getX() + dir[0], pos.getY() + dir[1], pos.getZ() + dir[2]);
            sideStates[i] = nodeStates.get(sidePositions[i]);
            if (sideStates[i] != null && (sideStates[i].isAnchor || sideStates[i].material != null)) {
                eligible[i] = true;
                candidateCount++;
            }
        }

        if (candidateCount == 0) return 0.0;

        // 迭代式分配：每輪均分未分配的荷載到剩餘候選者
        double remaining = load;
        double sideSupport = 0.0;

        for (int round = 0; round < 4 && remaining > 0.001 && candidateCount > 0; round++) {
            double sharePerSide = remaining / candidateCount;
            double roundAbsorbed = 0.0;
            int nextCandidateCount = 0;

            for (int i = 0; i < 4; i++) {
                if (!eligible[i]) continue;
                if (sideStates[i].isAnchor || canSupport(sideStates[i], sharePerSide)) {
                    roundAbsorbed += sharePerSide;
                    nextCandidateCount++;
                } else {
                    // 此鄰居無法支撐 → 踢出候選，份額留給下一輪重分配
                    eligible[i] = false;
                }
            }

            sideSupport += roundAbsorbed;
            remaining -= roundAbsorbed;
            candidateCount = nextCandidateCount;

            // 如果本輪所有候選都成功，remaining 已歸零，跳出
            if (remaining <= 0.001) break;
        }

        return sideSupport;
    }

    /** 方塊截面積 1m × 1m = 1 m²（Minecraft 方塊標準尺寸）
     *  ★ new-fix N5: 引用 PhysicsConstants.BLOCK_AREA，消除重複定義 */
    private static final double BLOCK_AREA = PhysicsConstants.BLOCK_AREA;

    /**
     * 判定方塊能否支撐指定的額外載重（力，單位 N）。
     * ★ v4-fix: 正確的力/容量比較
     *   容量 = Rcomp(Pa) × A(m²) = N
     *   load = 額外力 (N)
     * ★ audit-fix F-2: 比較 capacity >= totalForce + load（含累積載重），
     *   舊版僅比較 capacity >= load，忽略已累積的 totalForce，
     *   導致即使節點已瀕臨崩潰仍判定為可支撐。
     */
    private static boolean canSupport(NodeState node, double load) {
        if (node.isAnchor) return true;
        // ★ H-5 fix: 材料 null 或 Rcomp ≤ 0 時無法支撐
        if (node.material == null) return false;
        double rcomp = node.material.getRcomp();
        if (rcomp <= 0) return false;
        // 使用方塊的實際截面積（雕刻形狀可能 < 1.0m²）
        double capacity = rcomp * 1e6 * node.effectiveArea;  // Pa × m² = N
        // ★ audit-fix F-2: 含累積載重
        return capacity >= node.totalForce + load;
    }

    /**
     * 計算方塊的強度利用率。
     * ★ v4-fix: 正確的應力計算
     *   應力 σ = F / A (Pa)
     *   利用率 = σ / Rcomp
     */
    private static double calculateUtilization(NodeState ns, RMaterial mat) {
        double compCapacity = mat.getRcomp() * 1e6;  // Pa
        if (compCapacity <= 0) return 1.0;
        // 使用方塊的實際截面積（雕刻形狀可能 < 1.0m²）
        double actualStress = ns.totalForce / ns.effectiveArea;  // F/A = Pa
        return actualStress / compCapacity;
    }
}
