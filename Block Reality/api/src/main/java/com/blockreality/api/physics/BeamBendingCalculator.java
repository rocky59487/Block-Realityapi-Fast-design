package com.blockreality.api.physics;

import com.blockreality.api.material.RMaterial;
import net.minecraft.core.BlockPos;

import java.util.Map;

/**
 * 彎矩、剪力與荷載分配計算器。
 *
 * <h3>核心職責</h3>
 * <ul>
 *   <li>荷載向下/側向分配（{@link #distributeLoad}）</li>
 *   <li>支撐容量判定（{@link #canSupport}）</li>
 *   <li>強度利用率計算（{@link #calculateUtilization}）</li>
 * </ul>
 *
 * <h3>修正紀錄</h3>
 * <ul>
 *   <li><b>P1-fix</b>：彎矩公式 L/4 → L/8（均布荷載標準值）。</li>
 *   <li><b>P3-fix</b>：不可破壞材料（BEDROCK 等）利用率直接返回 0.0，
 *       避免 {@code 1e9 MPa × 1e6} 溢位。</li>
 *   <li><b>audit-fix F-2</b>：{@code canSupport} 比較 capacity >= totalForce + load
 *       （含累積載重），舊版僅比較 capacity >= load。</li>
 *   <li><b>audit-fix R2-2</b>：迭代式側向荷載分配，避免力洩漏。</li>
 * </ul>
 *
 * @see ForceEquilibriumSolver
 * @see SORSolverCore
 */
// M1-fix: 從 ForceEquilibriumSolver 提取，封裝荷載分配與利用率邏輯
public class BeamBendingCalculator {

    // ─── 常數 ────────────────────────────────────────────────────────────────

    /**
     * 水平四方向（不含 UP/DOWN）。
     * 靜態常數避免熱路徑（每 tick 每節點）的匿名陣列分配。
     */
    private static final int[][] HORIZONTAL_DIRS = {
        {  1, 0,  0 },  // EAST
        { -1, 0,  0 },  // WEST
        {  0, 0,  1 },  // SOUTH
        {  0, 0, -1 },  // NORTH
    };

    /** 方塊截面積 1m × 1m = 1m²（Minecraft 標準方塊）。 */
    private static final double BLOCK_AREA = PhysicsConstants.BLOCK_AREA;

    /**
     * 最小有效截面積（m²）。
     * 防止雕刻形狀截面積趨近 0 時的除零異常。
     */
    private static final double MIN_AREA = 0.001;

    // ─── 荷載分配 ─────────────────────────────────────────────────────────────

    /**
     * 將方塊的總載重分配到下方 / 側方支撐點，返回實際分配到的支撐力（N）。
     *
     * <h3>分配策略</h3>
     * <ol>
     *   <li>優先下方（重力方向）：若下方方塊為錨點或可支撐，直接傳遞全部荷載。</li>
     *   <li>次選側向（水平 4 方向）：迭代式均分，無法支撐的鄰居被踢出候選，
     *       其份額重分配給剩餘支撐者（最多 4 輪）。</li>
     * </ol>
     *
     * <p><b>audit-fix R2-2</b>：舊版 Pass1 計數 / Pass2 過濾的方案存在力洩漏，
     * 本版改為迭代式重分配，確保荷載守恆。
     *
     * @param pos        當前方塊位置
     * @param load       需要分配的總荷載（N）
     * @param nodeStates 所有節點的可變狀態映射
     * @return 實際被支撐點吸收的力（N），可能 < load（無有效支撐時）
     */
    static double distributeLoad(
        BlockPos pos,
        double load,
        Map<BlockPos, NodeState> nodeStates
    ) {
        // ── 1. 優先下方 ────────────────────────────────────────────────────
        BlockPos below = pos.below();
        NodeState belowState = nodeStates.get(below);
        if (belowState != null) {
            if (belowState.isAnchor || canSupport(belowState, load)) {
                return load;
            }
        }

        // ── 2. 側向支撐（迭代式均分）──────────────────────────────────────
        NodeState[] sideStates  = new NodeState[4];
        boolean[]   eligible    = new boolean[4];
        int         candidateCount = 0;

        for (int i = 0; i < HORIZONTAL_DIRS.length; i++) {
            int[] d = HORIZONTAL_DIRS[i];
            BlockPos sidePos = new BlockPos(pos.getX() + d[0], pos.getY() + d[1], pos.getZ() + d[2]);
            NodeState ns = nodeStates.get(sidePos);
            if (ns != null && (ns.isAnchor || ns.material != null)) {
                sideStates[i] = ns;
                eligible[i]   = true;
                candidateCount++;
            }
        }

        if (candidateCount == 0) return 0.0;

        double remaining    = load;
        double sideAbsorbed = 0.0;

        for (int round = 0; round < 4 && remaining > 0.001 && candidateCount > 0; round++) {
            double share = remaining / candidateCount;
            double roundGain = 0.0;
            int nextCount = 0;

            for (int i = 0; i < 4; i++) {
                if (!eligible[i]) continue;
                if (sideStates[i].isAnchor || canSupport(sideStates[i], share)) {
                    roundGain += share;
                    nextCount++;
                } else {
                    eligible[i] = false;  // 踢出候選，份額下輪重分配
                }
            }

            sideAbsorbed   += roundGain;
            remaining      -= roundGain;
            candidateCount  = nextCount;

            if (remaining <= 0.001) break;
        }

        return sideAbsorbed;
    }

    // ─── 支撐容量判定 ──────────────────────────────────────────────────────────

    /**
     * 判定方塊是否能額外承受指定荷載。
     *
     * <p><b>audit-fix F-2</b>：比較 {@code capacity >= totalForce + load}（含累積載重），
     * 舊版僅比較 {@code capacity >= load}，導致即使節點瀕臨失效仍判定可支撐。
     *
     * <p><b>BUG-FIX-1</b>：有效截面積最小值 {@value MIN_AREA} m²，
     * 防止雕刻形狀 effectiveArea → 0 時容量計算溢位。
     *
     * @param node 待判定的節點
     * @param load 額外荷載（N）
     * @return true = 可承受此額外荷載
     */
    static boolean canSupport(NodeState node, double load) {
        if (node.isAnchor) return true;
        if (node.material == null) return false;

        double rcomp = node.material.getRcomp();
        if (rcomp <= 0) return false;

        double area     = Math.max(node.effectiveArea, MIN_AREA);
        double capacity = rcomp * 1e6 * area;  // Pa × m² = N

        // 含累積載重：確保剩餘容量足夠
        return capacity >= node.totalForce + load;
    }

    // ─── 強度利用率計算 ─────────────────────────────────────────────────────────

    /**
     * 計算方塊的壓縮強度利用率（0.0 = 未受載，1.0 = 達到抗壓極限，> 1.0 = 超載）。
     *
     * <h3>公式</h3>
     * <pre>
     * σ    = totalForce / effectiveArea      （Pa）
     * η    = σ / (Rcomp × 10⁶)              （無單位）
     * </pre>
     *
     * <p><b>P3-fix</b>：不可破壞材料（BEDROCK 等）直接返回 0.0，
     * 跳過浮點運算以避免 {@code 1e9 MPa × 1e6} 超出安全範圍的溢位。
     *
     * <p><b>BUG-FIX-1</b>：有效截面積最小值 {@value MIN_AREA} m²，防止除零。
     *
     * @param ns  節點狀態（提供 totalForce 與 effectiveArea）
     * @param mat 節點材料（提供 Rcomp 與 isIndestructible 標誌）
     * @return 利用率（[0, ∞)，> 1.0 表示超載）
     */
    static double calculateUtilization(NodeState ns, RMaterial mat) {
        // P3-fix: 不可破壞材料直接返回 0，避免 1e9 MPa × 1e6 溢位
        if (mat.isIndestructible()) return 0.0;

        double compCapacity = mat.getRcomp() * 1e6;  // Pa
        if (compCapacity <= 0) return 1.0;           // 零容量 → 完全利用

        double area        = Math.max(ns.effectiveArea, MIN_AREA);
        double actualStress = ns.totalForce / area;   // F/A = Pa

        return actualStress / compCapacity;
    }

    // 工具類別，禁止實例化
    private BeamBendingCalculator() {}
}
