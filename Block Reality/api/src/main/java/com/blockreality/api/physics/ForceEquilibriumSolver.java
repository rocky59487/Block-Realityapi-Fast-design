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
     * @param isStable         力平衡判定（true = 穩定）
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

        Map<BlockPos, NodeState> nodeStates =
            initializeNodeStates(blocks, materials, anchors, effectiveAreas, fillRatios);
        List<BlockPos> sortedByY = new ArrayList<>(blocks);
        sortedByY.sort(Comparator.comparingInt(BlockPos::getY));
        SORSolverCore.SolveOutcome outcome =
            SORSolverCore.runSOR(nodeStates, sortedByY, initialOmega);
        Map<BlockPos, ForceResult> results = new HashMap<>(nodeStates.size());
        for (NodeState ns : nodeStates.values()) {
            double util   = BeamBendingCalculator.calculateUtilization(ns, ns.material);
            // audit-fix R2-1: 比較 supportForce >= totalForce × 0.9（含累積荷載）
            boolean stable = ns.isAnchor || (ns.supportForce >= ns.totalForce * 0.9);
            results.put(ns.pos, new ForceResult(ns.totalForce, ns.supportForce, stable, util));
        }

        long elapsed = (System.nanoTime() - startTime) / 1_000_000;
        ConvergenceDiagnostics diag = new ConvergenceDiagnostics(
            outcome.iterations(), outcome.finalResidual(),
            outcome.converged(),   outcome.finalOmega(), elapsed);

        LOGGER.info("[ForceEquilibrium] Solved {} nodes in {}ms (iter={}, converged={}, ω={})",
            blocks.size(), elapsed, outcome.iterations(), outcome.converged(),
            String.format("%.3f", outcome.finalOmega()));

        // 保存收斂結果供下次 warm-start
        if (outcome.converged()) {
            WarmStartCache.put(blocks, materials, nodeStates);
        }

        return new SolverResult(results, diag);
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
