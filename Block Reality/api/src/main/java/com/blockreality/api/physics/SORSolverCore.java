package com.blockreality.api.physics;

import net.minecraft.core.BlockPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;

/**
 * SOR（Successive Over-Relaxation）迭代核心。
 *
 * <h3>演算法說明</h3>
 * <p>Gauss-Seidel 的加速變體，使用鬆弛參數 ω（omega，預設 {@value #DEFAULT_OMEGA}）
 * 加速收斂。更新公式：
 * <pre>
 * x_new = (1 − ω) × x_old + ω × x_computed
 * </pre>
 *
 * <h3>自適應 ω 策略</h3>
 * <ul>
 *   <li>收斂率 > {@value #SLOW_CONVERGENCE_RATIO}（過慢）→ 增加 ω</li>
 *   <li>發散或數值異常 → 減少 ω</li>
 *   <li>ω 範圍限制在 [{@value #MIN_OMEGA}, {@value #MAX_OMEGA})</li>
 * </ul>
 *
 * <h3>終止條件</h3>
 * <p>使用全局殘差 ‖r‖ < ε 作為唯一終止條件
 * （節點層級早期終止會造成 Gauss-Seidel 偽收斂，已移除）。
 * 相對誤差 = maxDelta / maxForce；當 maxForce 極小時退化為絕對誤差。
 *
 * @see ForceEquilibriumSolver
 * @see BeamBendingCalculator
 */
// M1-fix: 從 ForceEquilibriumSolver 提取，封裝 SOR 收斂迴圈
class SORSolverCore {

    private static final Logger LOGGER = LogManager.getLogger("BR-SORSolver");

    // ─── SOR 參數常數 ─────────────────────────────────────────────────────────

    /** 最大迭代次數（超過後視為未收斂，繼續返回當前近似解）。 */
    static final int MAX_ITERATIONS = 100;

    /** 預設 SOR 鬆弛參數 ω，範圍 [1.0, 2.0)。 */
    static final double DEFAULT_OMEGA = 1.25;

    /** ω 下限（防止發散）。 */
    static final double MIN_OMEGA = 1.05;

    /** ω 上限（防止振盪）。 */
    static final double MAX_OMEGA = 1.95;

    /** ω 自適應調整步幅。 */
    static final double OMEGA_ADJUST_STEP = 0.05;

    /** 收斂率閾值（maxDelta / prevDelta > 此值視為收斂過慢）。 */
    static final double SLOW_CONVERGENCE_RATIO = 0.95;

    /**
     * 相對收斂閾值（maxDelta / maxForce < 此值視為收斂）。
     * 0.001 = 0.1% 的力變化即收斂，對任意規模結構均適用。
     */
    static final double RELATIVE_CONVERGENCE_THRESHOLD = 0.001;

    /**
     * 絕對收斂下限（N）。
     * 當 maxForce 趨近 0 時防止除零，退化為絕對誤差比較。
     */
    static final double ABSOLUTE_CONVERGENCE_FLOOR = 0.01;

    // ─── 求解輸出 ─────────────────────────────────────────────────────────────

    /**
     * SOR 收斂迴圈的執行摘要（與 {@link ForceEquilibriumSolver.ConvergenceDiagnostics} 不同，
     * 後者額外包含時間量測，由 {@link ForceEquilibriumSolver} 封裝）。
     *
     * @param converged     是否在 {@value #MAX_ITERATIONS} 次內收斂
     * @param iterations    實際執行的迭代次數
     * @param finalResidual 最終全局殘差 ‖r‖（N）
     * @param finalOmega    最終 ω 值
     */
    record SolveOutcome(boolean converged, int iterations, double finalResidual, double finalOmega) {}

    // ─── 主要入口 ─────────────────────────────────────────────────────────────

    /**
     * 執行完整 SOR 迭代迴圈直到收斂或達到最大迭代次數。
     *
     * <p>此方法就地更新 {@code nodeStates} 中所有 {@link NodeState} 的
     * {@code totalForce}、{@code supportForce} 欄位，呼叫者保留原始集合引用即可讀取結果。
     *
     * @param nodeStates   所有節點的可變狀態（in-place 更新）
     * @param sortedByY    按 Y 座標升序排列的方塊位置列表（由呼叫端排序一次傳入，避免重複 O(N log N)）
     * @param initialOmega 初始 ω，由 {@link ForceEquilibriumSolver#solve(java.util.Set, java.util.Map, java.util.Set)} 傳入
     * @return 收斂摘要
     */
    static SolveOutcome runSOR(
        Map<BlockPos, NodeState> nodeStates,
        List<BlockPos> sortedByY,
        double initialOmega
    ) {
        double currentOmega = clampOmega(initialOmega);
        double lastResidual = Double.MAX_VALUE;
        boolean converged   = false;
        int iterations      = 0;

        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            iterations  = iter + 1;
            double residual = iterationStep(nodeStates, sortedByY, currentOmega);

            // ── 自適應 ω 調整 ──────────────────────────────────────────────
            if (iter > 0) {
                double ratio = residual / lastResidual;
                if (ratio > SLOW_CONVERGENCE_RATIO) {
                    // 收斂過慢 → 加大 ω
                    currentOmega = Math.min(MAX_OMEGA, currentOmega + OMEGA_ADJUST_STEP);
                    LOGGER.debug("[SOR] Slow convergence ratio={}, ω→{}", String.format("%.3f", ratio),
                        String.format("%.3f", currentOmega));
                } else if (ratio < 0.0 || Double.isInfinite(residual)) {
                    // 發散或數值異常 → 縮小 ω
                    currentOmega = Math.max(MIN_OMEGA, currentOmega - OMEGA_ADJUST_STEP);
                    LOGGER.debug("[SOR] Divergence detected, ω→{}", String.format("%.3f", currentOmega));
                }
            }
            lastResidual = residual;

            // ── 收斂判定 ───────────────────────────────────────────────────
            double maxForce = 0.0;
            for (NodeState ns : nodeStates.values()) {
                maxForce = Math.max(maxForce, Math.abs(ns.totalForce));
            }
            boolean relativeOk = (maxForce > ABSOLUTE_CONVERGENCE_FLOOR)
                ? (residual / maxForce) < RELATIVE_CONVERGENCE_THRESHOLD
                : residual < ABSOLUTE_CONVERGENCE_FLOOR;

            if (relativeOk) {
                converged = true;
                LOGGER.debug("[SOR] Converged iter={} residual={} maxForce={} relErr={}",
                    iter,
                    String.format("%.6f", residual),
                    String.format("%.1f", maxForce),
                    maxForce > 0 ? String.format("%.6f", residual / maxForce) : "N/A");
                break;
            }
        }

        if (!converged) {
            LOGGER.warn("[SOR] Did not converge after {} iterations (residual={})",
                iterations, String.format("%.6f", lastResidual));
        }

        return new SolveOutcome(converged, iterations, lastResidual, currentOmega);
    }

    // ─── 單次迭代步驟 ──────────────────────────────────────────────────────────

    /**
     * 執行一次 SOR 迭代：更新所有非錨點節點的 totalForce + supportForce，
     * 返回本次迭代的全局最大力變化量（殘差 ‖r‖）。
     *
     * <p>遍歷順序依 Y 座標升序（由呼叫端的 {@code sortedByY} 保證），
     * 確保重力方向的載重傳遞在單次掃描中盡量一次到位。
     *
     * <p><b>全局殘差策略</b>：所有節點每次迭代都參與更新，不跳過任何節點，
     * 防止 Gauss-Seidel 的偽收斂現象（部分節點暫時收斂 → 鄰居更新後再次不平衡）。
     *
     * @param nodeStates 所有節點狀態（in-place 更新）
     * @param sortedByY  Y 升序的位置列表
     * @param omega      本次迭代使用的鬆弛參數 ω
     * @return 全局最大力變化量（N），用於收斂判定
     */
    static double iterationStep(
        Map<BlockPos, NodeState> nodeStates,
        List<BlockPos> sortedByY,
        double omega
    ) {
        double maxForceDelta = 0.0;

        for (BlockPos pos : sortedByY) {
            NodeState ns = nodeStates.get(pos);
            if (ns == null || ns.isAnchor) continue;

            // 計算總載重 = 自重 + 上方依賴載重
            double totalLoad = ns.weight;
            for (BlockPos depPos : ns.dependents) {
                NodeState dep = nodeStates.get(depPos);
                if (dep != null) totalLoad += dep.totalForce;
            }

            // 分配荷載（委託 BeamBendingCalculator）
            double distributed = BeamBendingCalculator.distributeLoad(pos, totalLoad, nodeStates);

            // SOR 鬆弛更新：x_new = (1−ω)×x_old + ω×x_computed
            double oldForce = ns.totalForce;
            double newForce = (1.0 - omega) * oldForce + omega * totalLoad;
            double delta    = Math.abs(newForce - oldForce);
            maxForceDelta   = Math.max(maxForceDelta, delta);

            // In-place 更新（避免 O(N×iter) 物件分配）
            ns.supportForce  = distributed;
            ns.totalForce    = newForce;
            ns.lastTotalForce = oldForce;
        }

        return maxForceDelta;
    }

    // ─── 工具方法 ─────────────────────────────────────────────────────────────

    private static double clampOmega(double omega) {
        return Math.max(MIN_OMEGA, Math.min(MAX_OMEGA, omega));
    }

    // 工具類別，禁止實例化
    private SORSolverCore() {}
}
