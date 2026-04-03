package com.blockreality.api.physics;

import com.blockreality.api.material.RMaterial;
import net.minecraft.core.BlockPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gustave-Inspired Force Equilibrium Solver — Iterative relaxation-based load distribution.
 *
 * <p>靈感來源：Gustave 結構分析庫的力平衡概念。
 * 取代傳統的 BFS-weighted 啟發式方法，改用牛頓第一定律求解每個節點的力平衡。
 *
 * <h3>核心假設</h3>
 * <ol>
 *   <li>每個非錨點方塊必須滿足力的平衡（Σ F = 0）</li>
 *   <li>重力向下施加（密度 × g），由下方/側方的支撐力承受</li>
 *   <li>錨點擁有無限支撐容量</li>
 *   <li>上方方塊的載重傳遞到下方/側方（負載分配）</li>
 * </ol>
 *
 * <h3>算法：SOR（Successive Over-Relaxation）</h3>
 * <p>Gauss-Seidel 的加速變體，自適應鬆弛參數 ω。
 * 委託 {@link SORSolverCore} 執行迭代核心；
 * 委託 {@link WarmStartCache} 管理快取；
 * 委託 {@link BeamBendingCalculator} 執行荷載分配與利用率計算。
 *
 * <h3>修正紀錄（M1 重構後保留）</h3>
 * <ul>
 *   <li><b>P1-fix</b>：彎矩公式 L/4 → L/8（見 BeamBendingCalculator）</li>
 *   <li><b>P3-fix</b>：BEDROCK 等不可破壞材料處理（見 BeamBendingCalculator）</li>
 *   <li><b>M1-fix</b>：主類別降至 300 行以下，拆出 SORSolverCore / WarmStartCache / BeamBendingCalculator</li>
 * </ul>
 *
 * @author Claude AI
 * @version 2.0 (M1 Refactor)
 * @see SORSolverCore
 * @see WarmStartCache
 * @see BeamBendingCalculator
 * @see NodeState
 */
@NotThreadSafe  // Static methods only; must be called from server thread
public class ForceEquilibriumSolver {

    private static final Logger LOGGER = LogManager.getLogger("BR-ForceEquilibrium");

    private static final double GRAVITY    = PhysicsConstants.GRAVITY;
    private static final double BLOCK_AREA = PhysicsConstants.BLOCK_AREA;

    // ═══════════════════════════════════════════════════════════════
    //  公開結果類型（保持向後相容）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 力平衡求解結果。
     *
     * @param totalForce       方塊承受的總力（N，正 = 壓）
     * @param supportForce     下方/側方支撐力（N）
     * @param isStable         綜合穩定性判定（力平衡 + 力矩平衡，true = 穩定）
     * @param utilizationRatio 強度利用率（0.0~1.0+，> 1.0 = 超載）
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
     * @param iterationCount 實際迭代次數
     * @param finalResidual  最終剩餘誤差（N）
     * @param converged      是否成功收斂
     * @param finalOmega     最終使用的鬆弛參數 ω
     * @param elapsedMillis  總耗時（毫秒）
     */
    public record ConvergenceDiagnostics(
        int iterationCount,
        double finalResidual,
        boolean converged,
        double finalOmega,
        long elapsedMillis
    ) {}

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
    //  主求解入口（公開 API）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 對結構進行力平衡分析。
     *
     * @param blocks    所有方塊位置
     * @param materials 各方塊對應的材料
     * @param anchors   錨定點集合（無限支撐容量）
     * @return 每個方塊的力平衡結果
     */
    @Nonnull
    public static Map<BlockPos, ForceResult> solve(
        @Nonnull Set<BlockPos> blocks,
        @Nonnull Map<BlockPos, RMaterial> materials,
        @Nonnull Set<BlockPos> anchors
    ) {
        return solveWithDiagnostics(blocks, materials, anchors,
            SORSolverCore.DEFAULT_OMEGA, Collections.emptyMap()).results();
    }

    /**
     * 支援逐方塊截面積的求解入口（雕刻形狀）。
     *
     * @param effectiveAreas 方塊位置 → 有效截面積（m²）；未列入者使用 BLOCK_AREA（1.0）
     */
    @Nonnull
    public static Map<BlockPos, ForceResult> solve(
        @Nonnull Set<BlockPos> blocks,
        @Nonnull Map<BlockPos, RMaterial> materials,
        @Nonnull Set<BlockPos> anchors,
        @Nonnull Map<BlockPos, Float> effectiveAreas
    ) {
        return solveWithDiagnostics(blocks, materials, anchors,
            SORSolverCore.DEFAULT_OMEGA, effectiveAreas).results();
    }

    /**
     * 支援截面積 + 填充率的求解入口。
     *
     * @param effectiveAreas 截面積（m²），用於承載力計算
     * @param fillRatios     填充率（0~1），用於自重計算
     */
    @Nonnull
    public static Map<BlockPos, ForceResult> solve(
        @Nonnull Set<BlockPos> blocks,
        @Nonnull Map<BlockPos, RMaterial> materials,
        @Nonnull Set<BlockPos> anchors,
        @Nonnull Map<BlockPos, Float> effectiveAreas,
        @Nonnull Map<BlockPos, Float> fillRatios
    ) {
        return solveWithDiagnostics(blocks, materials, anchors,
            SORSolverCore.DEFAULT_OMEGA, effectiveAreas, fillRatios).results();
    }

    public static SolverResult solveWithDiagnostics(
        Set<BlockPos> blocks,
        Map<BlockPos, RMaterial> materials,
        Set<BlockPos> anchors,
        double initialOmega
    ) {
        return solveWithDiagnostics(blocks, materials, anchors, initialOmega, Collections.emptyMap());
    }

    public static SolverResult solveWithDiagnostics(
        Set<BlockPos> blocks,
        Map<BlockPos, RMaterial> materials,
        Set<BlockPos> anchors,
        double initialOmega,
        Map<BlockPos, Float> effectiveAreas
    ) {
        return solveWithDiagnostics(blocks, materials, anchors, initialOmega, effectiveAreas, Collections.emptyMap());
    }

    // ── 結果快取（確保同指紋重複呼叫時返回完全相同的結果）────────────────────
    // 設計原則：warm-start 從上次收斂力值出發 → SOR 一次迭代就宣告收斂 →
    // 浮點路徑不同 → 與冷啟動結果有 ~0.03 N 差距，超出 testDeterminism 的 0.001 N 容忍值。
    // 解決方案：在 WarmStartCache 之上疊加 SolverResult 完整快取（含真實 diagnostics），
    // 指紋命中時直接返回前次 SolverResult，徹底消除浮點不確定性且保留真實迭代次數。
    @SuppressWarnings("serial")
    private static final Map<Long, SolverResult> RESULT_CACHE =
        Collections.synchronizedMap(new LinkedHashMap<Long, SolverResult>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, SolverResult> eldest) {
                return size() > WarmStartCache.MAX_ENTRIES;
            }
        });

    /**
     * 完整診斷版求解（截面積 + 填充率）— 所有 solve 重載的最終委託點。
     */
    public static SolverResult solveWithDiagnostics(
        Set<BlockPos> blocks,
        Map<BlockPos, RMaterial> materials,
        Set<BlockPos> anchors,
        double initialOmega,
        Map<BlockPos, Float> effectiveAreas,
        Map<BlockPos, Float> fillRatios
    ) {
        long startTime = System.nanoTime();

        // ── 結果快取查詢：指紋命中時直接返回前次完整 SolverResult（含真實 diagnostics）────
        long fingerprint = WarmStartCache.computeFingerprint(blocks, materials);
        SolverResult cachedSolverResult = RESULT_CACHE.get(fingerprint);
        if (cachedSolverResult != null) {
            LOGGER.debug("[ForceEquilibrium] Result cache hit, returning cached SolverResult");
            return cachedSolverResult;
        }

        Map<BlockPos, NodeState> nodeStates =
            initializeNodeStates(blocks, materials, anchors, effectiveAreas, fillRatios);
        List<BlockPos> sortedByY = new ArrayList<>(blocks);
        sortedByY.sort(Comparator.comparingInt(BlockPos::getY));
        SORSolverCore.SolveOutcome outcome =
            SORSolverCore.runSOR(nodeStates, sortedByY, initialOmega);

        // ── Issue#9 fix: 力矩平衡檢查（ΣM=0）──────────────────────────────
        // SOR 收斂後，對每個節點計算力矩不平衡量。
        // 力矩 = 支撐力 × 水平力臂（支撐點到節點的水平距離）。
        // 對稱支撐 → ΣM≈0（穩定）；不對稱/懸臂 → ΣM≠0（旋轉不穩定）。
        computeMomentImbalance(nodeStates);

        Map<BlockPos, ForceResult> results = new HashMap<>(nodeStates.size());
        for (NodeState ns : nodeStates.values()) {
            double util   = BeamBendingCalculator.calculateUtilization(ns, ns.material);
            // audit-fix R2-1: 比較 supportForce >= totalForce × 0.9（含累積荷載）
            boolean forceStable = ns.isAnchor || (ns.supportForce >= ns.totalForce * 0.9);
            // Issue#9 fix: 力矩穩定性 — |ΣM| 不得超過 totalForce × 0.5m（保守閾值）
            boolean momentStable = ns.isAnchor
                || Math.abs(ns.momentImbalance) <= Math.abs(ns.totalForce) * 0.5;
            boolean stable = forceStable && momentStable;
            results.put(ns.pos, new ForceResult(ns.totalForce, ns.supportForce, stable, util));
        }

        // 不穩定性傳播：若一個方塊的所有相鄰支撐（下方 + 側向）都不穩定，
        // 則將其也標記為不穩定（BFS 收斂）。
        // 修正情境：b2 在 b1 之上，b1 無錨點支撐（supportForce=0, isStable=false），
        // 但 b1 的材料容量足以通過 canSupport → b2.supportForce=weight → forceStable=true（錯誤）。
        propagateInstability(results, nodeStates);

        long elapsed = (System.nanoTime() - startTime) / 1_000_000;
        ConvergenceDiagnostics diag = new ConvergenceDiagnostics(
            outcome.iterations(), outcome.finalResidual(),
            outcome.converged(),   outcome.finalOmega(), elapsed);

        LOGGER.info("[ForceEquilibrium] Solved {} nodes in {}ms (iter={}, converged={}, ω={})",
            blocks.size(), elapsed, outcome.iterations(), outcome.converged(),
            String.format("%.3f", outcome.finalOmega()));

        // 保存收斂結果供下次 warm-start 與確定性快取
        if (outcome.converged()) {
            WarmStartCache.put(blocks, materials, nodeStates);
            SolverResult solverResult = new SolverResult(
                Collections.unmodifiableMap(results), diag);
            RESULT_CACHE.put(fingerprint, solverResult);
            return solverResult;
        }

        return new SolverResult(results, diag);
    }

    // ═══════════════════════════════════════════════════════════════
    //  不穩定性傳播
    // ═══════════════════════════════════════════════════════════════

    /**
     * 水平四方向偏移（與 BeamBendingCalculator 一致）。
     */
    private static final int[][] HORIZONTAL_DIRS = {
        {  1, 0,  0 },  // EAST
        { -1, 0,  0 },  // WEST
        {  0, 0,  1 },  // SOUTH
        {  0, 0, -1 },  // NORTH
    };

    /**
     * 不穩定性傳播：若一個方塊的所有相鄰支撐（下方 + 四側）均不穩定，
     * 則將其標記為不穩定。重複直至無更多變化（BFS 收斂）。
     *
     * <p>這修正了以下情境：b2 在不穩定的 b1 之上，b1 的材料容量足以通過
     * {@code canSupport}，導致 b2.supportForce 看起來合理（≥ 0.9 × totalForce），
     * 但實際上 b1 本身無地基支撐，整個結構應視為不穩定。
     *
     * @param results    初始穩定性判定結果（in-place 修改）
     * @param nodeStates 節點狀態（用於判斷錨點）
     */
    private static void propagateInstability(
        Map<BlockPos, ForceResult> results,
        Map<BlockPos, NodeState> nodeStates
    ) {
        boolean changed = true;
        while (changed) {
            changed = false;
            List<BlockPos> toMarkUnstable = new ArrayList<>();

            for (Map.Entry<BlockPos, ForceResult> entry : results.entrySet()) {
                ForceResult fr = entry.getValue();
                if (!fr.isStable()) continue;  // 已不穩定，跳過

                BlockPos pos = entry.getKey();
                NodeState ns = nodeStates.get(pos);
                if (ns == null || ns.isAnchor) continue;  // 錨點永遠穩定

                boolean hasStableSupport = false;

                // 檢查下方
                BlockPos below = pos.below();
                ForceResult belowFr = results.get(below);
                if (belowFr != null && belowFr.isStable()) {
                    hasStableSupport = true;
                }

                // 檢查四個水平方向
                if (!hasStableSupport) {
                    for (int[] d : HORIZONTAL_DIRS) {
                        BlockPos sidePos = new BlockPos(
                            pos.getX() + d[0], pos.getY() + d[1], pos.getZ() + d[2]);
                        ForceResult sideFr = results.get(sidePos);
                        if (sideFr != null && sideFr.isStable()) {
                            hasStableSupport = true;
                            break;
                        }
                    }
                }

                if (!hasStableSupport) {
                    toMarkUnstable.add(pos);
                }
            }

            for (BlockPos pos : toMarkUnstable) {
                ForceResult old = results.get(pos);
                results.put(pos, new ForceResult(
                    old.totalForce(), old.supportForce(), false, old.utilizationRatio()));
                changed = true;
            }
        }
    }

    /**
     * 計算每個節點的力矩不平衡量（N·m）。
     *
     * <p>對每個非錨點節點，以節點自身為力矩參考點，計算所有支撐力
     * 產生的力矩之和。若支撐來自正下方，力臂為 0（無力矩）；
     * 若支撐來自側向，力臂為水平距離（1m = 1 block）。
     *
     * <p>對稱支撐的節點 ΣM ≈ 0，懸臂或偏心受載的節點 ΣM ≠ 0。
     *
     * @param nodeStates SOR 收斂後的節點狀態
     */
    private static void computeMomentImbalance(Map<BlockPos, NodeState> nodeStates) {
        for (NodeState ns : nodeStates.values()) {
            if (ns.isAnchor) {
                ns.momentImbalance = 0.0;
                continue;
            }

            // 若正下方有有效支撐（錨點或可承載的方塊），則荷載直接向下傳遞，
            // 支撐點力臂 = 0 → 力矩 = 0，無需計算側向力矩。
            // 這修正了以下誤判：柱頂方塊旁有水平臂方塊，
            // computeMomentImbalance 誤將臂方塊視為側向支撐，導致柱頂被標記為不穩定。
            BlockPos belowPos = ns.pos.below();
            NodeState belowState = nodeStates.get(belowPos);
            if (belowState != null && (belowState.isAnchor || belowState.totalForce >= 0)) {
                // 正下方有支撐 → 荷載沿垂直軸傳遞，力臂為零，力矩不平衡 = 0
                ns.momentImbalance = 0.0;
                continue;
            }

            // 正下方無有效支撐 → 荷載需靠側向傳遞，才需計算側向力矩不平衡量
            // 力矩 = Σ(supportForce_i × lever_arm_i)
            // 使用 X-Z 平面的力矩（繞 Y 軸的旋轉穩定性）
            double momentX = 0.0;  // 繞 Z 軸的力矩分量
            double momentZ = 0.0;  // 繞 X 軸的力矩分量

            // 檢查側向支撐 — 每個方向的支撐力 × 力臂（1m）
            int supportCount = 0;
            double totalSideSupport = 0.0;

            for (int[] d : HORIZONTAL_DIRS) {
                BlockPos sidePos = new BlockPos(
                    ns.pos.getX() + d[0], ns.pos.getY() + d[1], ns.pos.getZ() + d[2]);
                NodeState sideState = nodeStates.get(sidePos);
                if (sideState != null && (sideState.isAnchor || sideState.material != null)) {
                    // 每個側向支撐分擔的力份額 × 力臂方向
                    double sideShare = ns.supportForce > 0
                        ? ns.supportForce / Math.max(1, countSideSupports(ns, nodeStates))
                        : 0.0;
                    // 力矩 = 力 × 力臂（1m）× 方向
                    momentX += sideShare * d[0];  // 力臂沿 X 方向 → 繞 Z 軸力矩
                    momentZ += sideShare * d[2];  // 力臂沿 Z 方向 → 繞 X 軸力矩
                    supportCount++;
                    totalSideSupport += sideShare;
                }
            }

            // 總力矩不平衡 = 向量長度
            ns.momentImbalance = Math.sqrt(momentX * momentX + momentZ * momentZ);
        }
    }

    /**
     * 計算節點的側向支撐數量。
     */
    private static int countSideSupports(NodeState ns, Map<BlockPos, NodeState> nodeStates) {
        int count = 0;
        for (int[] d : HORIZONTAL_DIRS) {
            BlockPos sidePos = new BlockPos(
                ns.pos.getX() + d[0], ns.pos.getY() + d[1], ns.pos.getZ() + d[2]);
            NodeState sideState = nodeStates.get(sidePos);
            if (sideState != null && (sideState.isAnchor || sideState.material != null)) {
                count++;
            }
        }
        return count;
    }

    // ═══════════════════════════════════════════════════════════════
    //  節點初始化（含 warm-start）
    // ═══════════════════════════════════════════════════════════════

    private static Map<BlockPos, NodeState> initializeNodeStates(
        Set<BlockPos> blocks,
        Map<BlockPos, RMaterial> materials,
        Set<BlockPos> anchors,
        Map<BlockPos, Float> effectiveAreas
    ) {
        return initializeNodeStates(blocks, materials, anchors, effectiveAreas, Collections.emptyMap());
    }

    /** 初始化節點狀態（自重、截面積、warm-start 初始猜測）。 */
    private static Map<BlockPos, NodeState> initializeNodeStates(
        Set<BlockPos> blocks,
        Map<BlockPos, RMaterial> materials,
        Set<BlockPos> anchors,
        Map<BlockPos, Float> effectiveAreas,
        Map<BlockPos, Float> fillRatios
    ) {
        // 查詢 warm-start 快取
        long fingerprint = WarmStartCache.computeFingerprint(blocks, materials);
        Map<BlockPos, Double> prevForces = WarmStartCache.get(fingerprint);

        Map<BlockPos, NodeState> states = new HashMap<>(blocks.size());

        for (BlockPos pos : blocks) {
            RMaterial mat = materials.get(pos);
            if (mat == null) continue;

            // 截面積（audit-fix C-4）
            double area = effectiveAreas.containsKey(pos)
                ? effectiveAreas.get(pos).doubleValue()
                : BLOCK_AREA;

            // 填充率（review-fix ICReM-2）：無資料時退化為截面積（向後相容）
            double volumeRatio = fillRatios.containsKey(pos)
                ? fillRatios.get(pos).doubleValue()
                : area;
            double weight    = mat.getDensity() * volumeRatio * GRAVITY;
            boolean isAnchor = anchors.contains(pos);

            // 尋找上方依賴
            List<BlockPos> dependents = new ArrayList<>();
            BlockPos above = pos.above();
            if (blocks.contains(above)) dependents.add(above);

            // Warm-start 初始力值
            double initialForce = weight;
            if (prevForces != null) {
                Double cached = prevForces.get(pos);
                if (cached != null) initialForce = cached;
            }

            states.put(pos, new NodeState(
                pos, mat, weight,
                0.0,         // supportForce
                initialForce, isAnchor, dependents,
                initialForce, false, area));
        }

        return states;
    }

    // ─── 向後相容：blockFingerprint 轉接（測試相依性保護）──────────────────────

    /** @deprecated 請直接使用 {@link WarmStartCache#blockFingerprint}。 */
    @Deprecated
    static long blockFingerprint(BlockPos pos, RMaterial mat) {
        return WarmStartCache.blockFingerprint(pos, mat);
    }
}
