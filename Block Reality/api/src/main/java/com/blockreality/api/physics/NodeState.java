package com.blockreality.api.physics;

import com.blockreality.api.material.RMaterial;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * 力平衡求解器的單一節點可變狀態。
 *
 * <p>由 {@link SORSolverCore}、{@link BeamBendingCalculator}、{@link WarmStartCache}
 * 共用，從原 {@code ForceEquilibriumSolver} 的私有內部類別提取至套件可見層級。
 *
 * <p>設計為 <em>mutable</em>，避免 SOR 迭代中每節點 O(N×iter) 的物件分配壓力。
 *
 * @see ForceEquilibriumSolver
 */
// M1-fix: 從 ForceEquilibriumSolver 私有內部類別提取，供子模組共用
public class NodeState {

    // ─── 不可變屬性（由 initializeNodeStates 初始化後不再變動）────────────────

    /** 方塊座標 */
    final BlockPos pos;

    /** 方塊材料（可為 null，視為空氣跳過） */
    final RMaterial material;

    /** 自重（N） = 密度 × 填充率 × g */
    final double weight;

    /** 是否為錨點（無限支撐容量，如地板、岩壁） */
    final boolean isAnchor;

    /** 上方依賴此節點的方塊列表（用於累積向下傳遞的載重） */
    final List<BlockPos> dependents;

    /**
     * 有效截面積（m²）。
     * 雕刻形狀 < 1.0，完整方塊 = {@link PhysicsConstants#BLOCK_AREA}。
     */
    final double effectiveArea;

    // ─── 可變狀態（SOR 迭代期間更新）────────────────────────────────────────

    /** 下方/側方提供的支撐力（N） */
    double supportForce;

    /** 節點承受的總力（N，正=壓），SOR 迭代更新的主要變數 */
    double totalForce;

    /** 上次迭代的 totalForce（用於計算殘差 / delta） */
    double lastTotalForce;

    /** 節點是否已達到收斂（全局殘差收斂後整體判定，此欄位供除錯用） */
    boolean converged;

    /**
     * Issue#9 fix: 力矩不平衡量（N·m）。
     * SOR 收斂後由 ForceEquilibriumSolver 計算，用於 ΣM=0 旋轉穩定性判定。
     * 正值表示存在未平衡的旋轉力矩。
     */
    double momentImbalance;

    // ─── 3D 力/力矩擴充（工程力學擴充）──────────────────────────────────

    /**
     * 三維合力向量（N + N·m）。
     * 包含 Fx, Fy, Fz 力分量與 Mx, My, Mz 力矩分量。
     * SOR 收斂後由 ForceEquilibriumSolver 計算。
     *
     * <p>Fy 對應原有的 totalForce（向下為負），
     * Fx/Fz 為側向力（風/地震荷載），
     * Mx/My/Mz 為力矩（含側向力產生的傾覆力矩）。
     *
     * @since 1.1.0
     */
    ForceVector3D forceVector3D = ForceVector3D.ZERO;

    /**
     * 荷載組合中的控制組合。
     * 記錄哪個 LRFD 組合產生了最大設計力，用於診斷與報告。
     *
     * @since 1.1.0
     */
    LoadCombination controllingCombination = null;

    // ─── 建構子 ──────────────────────────────────────────────────────────────

    NodeState(
        BlockPos pos,
        RMaterial material,
        double weight,
        double supportForce,
        double totalForce,
        boolean isAnchor,
        List<BlockPos> dependents,
        double lastTotalForce,
        boolean converged,
        double effectiveArea
    ) {
        this.pos           = pos;
        this.material      = material;
        this.weight        = weight;
        this.supportForce  = supportForce;
        this.totalForce    = totalForce;
        this.isAnchor      = isAnchor;
        this.dependents    = dependents;
        this.lastTotalForce = lastTotalForce;
        this.converged     = converged;
        this.effectiveArea = effectiveArea;
    }
}
