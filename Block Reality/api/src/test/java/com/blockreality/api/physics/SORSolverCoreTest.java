package com.blockreality.api.physics;

import com.blockreality.api.material.DefaultMaterial;
import com.blockreality.api.material.RMaterial;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Issue#9: SORSolverCore 單元測試。
 *
 * 測試涵蓋：
 *   - SOR 常數合理性
 *   - 單次迭代步驟正確性
 *   - 收斂行為驗證
 *   - 自適應 ω 策略
 *   - 邊界情況處理
 */
@DisplayName("SORSolverCore — SOR 迭代核心測試")
class SORSolverCoreTest {

    private static final double GRAVITY = PhysicsConstants.GRAVITY;
    private static final double TOLERANCE = 0.01;

    // ═══════════════════════════════════════════════════════════════
    //  常數驗證
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("SOR 參數常數")
    class ConstantsTest {

        @Test
        @DisplayName("MAX_ITERATIONS 在合理範圍內")
        void testMaxIterationsReasonable() {
            assertTrue(SORSolverCore.MAX_ITERATIONS >= 50,
                "MAX_ITERATIONS 應 >= 50 以確保大結構收斂");
            assertTrue(SORSolverCore.MAX_ITERATIONS <= 500,
                "MAX_ITERATIONS 應 <= 500 以避免無限迴圈");
        }

        @Test
        @DisplayName("DEFAULT_OMEGA 在 [1.0, 2.0) 範圍內")
        void testDefaultOmegaInRange() {
            assertTrue(SORSolverCore.DEFAULT_OMEGA >= 1.0);
            assertTrue(SORSolverCore.DEFAULT_OMEGA < 2.0);
        }

        @Test
        @DisplayName("MIN_OMEGA < DEFAULT_OMEGA < MAX_OMEGA")
        void testOmegaBoundsOrdering() {
            assertTrue(SORSolverCore.MIN_OMEGA < SORSolverCore.DEFAULT_OMEGA);
            assertTrue(SORSolverCore.DEFAULT_OMEGA < SORSolverCore.MAX_OMEGA);
        }

        @Test
        @DisplayName("RELATIVE_CONVERGENCE_THRESHOLD 是正數且 < 1")
        void testConvergenceThreshold() {
            assertTrue(SORSolverCore.RELATIVE_CONVERGENCE_THRESHOLD > 0);
            assertTrue(SORSolverCore.RELATIVE_CONVERGENCE_THRESHOLD < 1.0);
        }

        @Test
        @DisplayName("ABSOLUTE_CONVERGENCE_FLOOR 是正數")
        void testAbsoluteFloor() {
            assertTrue(SORSolverCore.ABSOLUTE_CONVERGENCE_FLOOR > 0);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  單次迭代
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("iterationStep 單次迭代")
    class IterationStepTest {

        @Test
        @DisplayName("單一方塊在錨點上 — 迭代不應改變錨點")
        void testAnchorNodeUnchanged() {
            BlockPos anchorPos = new BlockPos(0, 0, 0);
            BlockPos blockPos = new BlockPos(0, 1, 0);

            Map<BlockPos, NodeState> states = new HashMap<>();
            states.put(anchorPos, createNodeState(anchorPos, DefaultMaterial.STONE, true));
            states.put(blockPos, createNodeState(blockPos, DefaultMaterial.STONE, false));

            List<BlockPos> sortedByY = List.of(anchorPos, blockPos);

            SORSolverCore.IterationResult result =
                SORSolverCore.iterationStep(states, sortedByY, SORSolverCore.DEFAULT_OMEGA);

            // 錨點的 totalForce 不應被修改
            NodeState anchorState = states.get(anchorPos);
            assertEquals(anchorState.weight, anchorState.totalForce,
                "錨點的 totalForce 不應在迭代中被修改");
        }

        @Test
        @DisplayName("返回的 IterationResult 包含非負值")
        void testIterationResultNonNegative() {
            BlockPos anchorPos = new BlockPos(0, 0, 0);
            BlockPos blockPos = new BlockPos(0, 1, 0);

            Map<BlockPos, NodeState> states = new HashMap<>();
            states.put(anchorPos, createNodeState(anchorPos, DefaultMaterial.STONE, true));
            states.put(blockPos, createNodeState(blockPos, DefaultMaterial.TIMBER, false));
            // 設定 dependent
            states.get(blockPos).dependents.clear();

            List<BlockPos> sortedByY = List.of(anchorPos, blockPos);

            SORSolverCore.IterationResult result =
                SORSolverCore.iterationStep(states, sortedByY, SORSolverCore.DEFAULT_OMEGA);

            assertTrue(result.maxForceDelta() >= 0, "maxForceDelta 應 >= 0");
            assertTrue(result.maxImbalance() >= 0, "maxImbalance 應 >= 0");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  完整 SOR 收斂
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("runSOR 收斂行為")
    class RunSORTest {

        @Test
        @DisplayName("單一錨點 — 立即收斂")
        void testSingleAnchorConvergesImmediately() {
            BlockPos anchorPos = new BlockPos(0, 0, 0);

            Map<BlockPos, NodeState> states = new HashMap<>();
            states.put(anchorPos, createNodeState(anchorPos, DefaultMaterial.STONE, true));
            List<BlockPos> sortedByY = List.of(anchorPos);

            SORSolverCore.SolveOutcome outcome =
                SORSolverCore.runSOR(states, sortedByY, SORSolverCore.DEFAULT_OMEGA);

            assertTrue(outcome.converged(), "單一錨點應立即收斂");
            assertEquals(1, outcome.iterations(), "應只需 1 次迭代");
        }

        @Test
        @DisplayName("簡單垂直堆疊 — 應收斂")
        void testVerticalStackConverges() {
            // anchor(y=0) → stone(y=1) → stone(y=2)
            BlockPos anchor = new BlockPos(0, 0, 0);
            BlockPos b1 = new BlockPos(0, 1, 0);
            BlockPos b2 = new BlockPos(0, 2, 0);

            Map<BlockPos, NodeState> states = new HashMap<>();
            states.put(anchor, createNodeState(anchor, DefaultMaterial.STONE, true));
            states.put(b1, createNodeState(b1, DefaultMaterial.STONE, false));
            states.put(b2, createNodeState(b2, DefaultMaterial.STONE, false));

            // 設定依賴：b1 的 dependent 是 b2
            states.get(b1).dependents.clear();
            states.get(b1).dependents.add(b2);
            states.get(b2).dependents.clear();

            List<BlockPos> sortedByY = List.of(anchor, b1, b2);

            SORSolverCore.SolveOutcome outcome =
                SORSolverCore.runSOR(states, sortedByY, SORSolverCore.DEFAULT_OMEGA);

            assertTrue(outcome.converged(), "垂直堆疊應收斂");
            assertTrue(outcome.iterations() <= SORSolverCore.MAX_ITERATIONS);
            assertTrue(outcome.finalResidual() >= 0);
        }

        @Test
        @DisplayName("空節點集 — 應立即收斂")
        void testEmptyNodesConverges() {
            Map<BlockPos, NodeState> states = new HashMap<>();
            List<BlockPos> sortedByY = List.of();

            SORSolverCore.SolveOutcome outcome =
                SORSolverCore.runSOR(states, sortedByY, SORSolverCore.DEFAULT_OMEGA);

            assertTrue(outcome.converged(), "空集合應立即收斂");
        }

        @Test
        @DisplayName("finalOmega 在有效範圍內")
        void testFinalOmegaInRange() {
            BlockPos anchor = new BlockPos(0, 0, 0);
            BlockPos block = new BlockPos(0, 1, 0);

            Map<BlockPos, NodeState> states = new HashMap<>();
            states.put(anchor, createNodeState(anchor, DefaultMaterial.STONE, true));
            states.put(block, createNodeState(block, DefaultMaterial.STONE, false));

            List<BlockPos> sortedByY = List.of(anchor, block);

            SORSolverCore.SolveOutcome outcome =
                SORSolverCore.runSOR(states, sortedByY, SORSolverCore.DEFAULT_OMEGA);

            assertTrue(outcome.finalOmega() >= SORSolverCore.MIN_OMEGA,
                "finalOmega 應 >= MIN_OMEGA");
            assertTrue(outcome.finalOmega() <= SORSolverCore.MAX_OMEGA,
                "finalOmega 應 <= MAX_OMEGA");
        }

        @Test
        @DisplayName("SolveOutcome 記錄 fields 語意正確")
        void testSolveOutcomeFields() {
            SORSolverCore.SolveOutcome outcome =
                new SORSolverCore.SolveOutcome(true, 42, 0.005, 1.3);

            assertTrue(outcome.converged());
            assertEquals(42, outcome.iterations());
            assertEquals(0.005, outcome.finalResidual(), 1e-9);
            assertEquals(1.3, outcome.finalOmega(), 1e-9);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Issue#9 fix: 收斂判定使用力不平衡量
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Issue#9 fix: 力不平衡收斂判定")
    class ImbalanceConvergenceTest {

        @Test
        @DisplayName("IterationResult 記錄同時攜帶 forceDelta 和 imbalance")
        void testIterationResultDualValues() {
            SORSolverCore.IterationResult result =
                new SORSolverCore.IterationResult(1.5, 0.8);

            assertEquals(1.5, result.maxForceDelta(), 1e-9);
            assertEquals(0.8, result.maxImbalance(), 1e-9);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  工具方法
    // ═══════════════════════════════════════════════════════════════

    private NodeState createNodeState(BlockPos pos, RMaterial material, boolean isAnchor) {
        double weight = material.getDensity() * 1.0 * GRAVITY; // 1m³ 方塊
        List<BlockPos> dependents = new ArrayList<>();
        BlockPos above = pos.above();
        dependents.add(above); // 預設加上方，測試可覆寫

        return new NodeState(
            pos, material, weight,
            0.0,    // supportForce
            weight, // totalForce (初始 = 自重)
            isAnchor,
            dependents,
            weight, // lastTotalForce
            false,  // converged
            PhysicsConstants.BLOCK_AREA // effectiveArea
        );
    }
}
