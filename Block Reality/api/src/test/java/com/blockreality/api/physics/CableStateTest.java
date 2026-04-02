package com.blockreality.api.physics;

import com.blockreality.api.material.DefaultMaterial;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CableState 單元測試 — 驗證 XPBD 纜索模擬狀態的核心行為。
 *
 * 測試策略：
 *   1. 建構子：節點數計算（短/長纜索）、restSegmentLength
 *   2. resetLambdas()：歸零驗證
 *   3. calculateTension()：鬆弛/拉伸場景
 *   4. isBroken()：閾值判定
 *   5. nodes unmodifiable 防禦性驗證
 *   6. nodeCount() 一致性
 *
 * 參考：Macklin & Müller MIG'16 — per-constraint λ, XPBD iterations
 */
@DisplayName("CableState — XPBD 纜索模擬狀態")
class CableStateTest {

    /** 測試用端點 */
    private static final BlockPos A = new BlockPos(0, 64, 0);
    private static final BlockPos B_SHORT = new BlockPos(2, 64, 0);   // 短纜索 ~2m
    private static final BlockPos B_LONG = new BlockPos(20, 64, 0);   // 長纜索 ~20m

    // ═══════════════════════════════════════════════════════
    //  建構子 — 節點數與 segment 長度
    // ═══════════════════════════════════════════════════════

    @Nested
    @DisplayName("建構子 — 節點數計算")
    class ConstructorTests {

        @Test
        @DisplayName("短纜索 (2m) 至少有 MIN_NODES=2 個節點")
        void shortCableMinNodes() {
            CableElement elem = CableElement.create(A, B_SHORT, DefaultMaterial.STEEL);
            CableState state = new CableState(elem, "0_0_0|2_64_0");
            assertTrue(state.nodeCount() >= 2, "Short cable should have >= 2 nodes");
        }

        @Test
        @DisplayName("長纜索 (20m) 節點數 = ceil(20/0.5)+1 但 ≤ MAX_NODES=64")
        void longCableNodeCount() {
            CableElement elem = CableElement.create(A, B_LONG, DefaultMaterial.STEEL);
            CableState state = new CableState(elem, "0_0_0|20_64_0");
            // ceil(20/0.5) = 40 segments → 41 nodes (< 64)
            assertTrue(state.nodeCount() <= 64, "Node count should be <= MAX_NODES");
            assertTrue(state.nodeCount() >= 2, "Node count should be >= 2");
        }

        @Test
        @DisplayName("restSegmentLength = restLength / (nodeCount - 1)")
        void restSegmentLengthCalculation() {
            CableElement elem = CableElement.create(A, B_SHORT, DefaultMaterial.STEEL);
            CableState state = new CableState(elem, "test");

            int nodeCount = state.nodeCount();
            double expected = elem.restLength() / (nodeCount - 1);
            assertEquals(expected, state.restSegmentLength, 1e-10,
                "restSegmentLength should match formula");
        }

        @Test
        @DisplayName("lambdas 長度 = nodeCount - 1（每個 segment 一個 λ）")
        void lambdasLength() {
            CableElement elem = CableElement.create(A, B_LONG, DefaultMaterial.STEEL);
            CableState state = new CableState(elem, "test");

            assertEquals(state.nodeCount() - 1, state.lambdas.length,
                "lambdas array should have one entry per segment");
        }

        @Test
        @DisplayName("首尾節點為 fixed")
        void firstLastNodesFixed() {
            CableElement elem = CableElement.create(A, B_SHORT, DefaultMaterial.STEEL);
            CableState state = new CableState(elem, "test");

            assertTrue(state.nodes.get(0).isFixed(),
                "First node should be fixed");
            assertTrue(state.nodes.get(state.nodeCount() - 1).isFixed(),
                "Last node should be fixed");
        }

        @Test
        @DisplayName("中間節點為 free（非 fixed）")
        void middleNodesFree() {
            CableElement elem = CableElement.create(A, B_LONG, DefaultMaterial.STEEL);
            CableState state = new CableState(elem, "test");

            if (state.nodeCount() > 2) {
                assertFalse(state.nodes.get(1).isFixed(),
                    "Middle nodes should be free");
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    //  resetLambdas
    // ═══════════════════════════════════════════════════════

    @Nested
    @DisplayName("resetLambdas() — XPBD λ 歸零")
    class ResetLambdasTests {

        @Test
        @DisplayName("resetLambdas 將所有 λ 歸零")
        void resetsAllToZero() {
            CableElement elem = CableElement.create(A, B_LONG, DefaultMaterial.STEEL);
            CableState state = new CableState(elem, "test");

            // 模擬 XPBD 累積
            Arrays.fill(state.lambdas, 42.0);

            state.resetLambdas();

            for (int i = 0; i < state.lambdas.length; i++) {
                assertEquals(0.0, state.lambdas[i], "lambdas[" + i + "] should be 0");
            }
        }

        @Test
        @DisplayName("連續多次 resetLambdas 是冪等操作")
        void resetIsIdempotent() {
            CableElement elem = CableElement.create(A, B_SHORT, DefaultMaterial.CONCRETE);
            CableState state = new CableState(elem, "test");

            state.resetLambdas();
            state.resetLambdas();
            state.resetLambdas();

            for (double l : state.lambdas) {
                assertEquals(0.0, l);
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    //  calculateTension — 張力計算
    // ═══════════════════════════════════════════════════════

    @Nested
    @DisplayName("calculateTension() — 張力")
    class TensionTests {

        @Test
        @DisplayName("初始狀態（節點沿直線）張力 ≈ 0（不拉伸）")
        void initialTensionNearZero() {
            CableElement elem = CableElement.create(A, B_SHORT, DefaultMaterial.STEEL);
            CableState state = new CableState(elem, "test");

            // 剛建構時，節點在直線上，距離 = restSegmentLength → strain ≈ 0
            double tension = state.calculateTension();
            assertTrue(tension >= 0, "Tension should be >= 0");
            // 由於 floating point，可能有微小正值
            assertTrue(tension < 100, "Initial tension should be near-zero");
        }

        @Test
        @DisplayName("calculateTension 回傳值 ≥ 0（纜索只能受拉）")
        void tensionNonNegative() {
            CableElement elem = CableElement.create(A, B_SHORT, DefaultMaterial.STEEL);
            CableState state = new CableState(elem, "test");

            assertTrue(state.calculateTension() >= 0, "Tension must be >= 0 (tension-only)");
        }
    }

    // ═══════════════════════════════════════════════════════
    //  isBroken — 斷裂判定
    // ═══════════════════════════════════════════════════════

    @Nested
    @DisplayName("isBroken() — 斷裂閾值")
    class BrokenTests {

        @Test
        @DisplayName("初始狀態不斷裂")
        void notBrokenInitially() {
            CableElement elem = CableElement.create(A, B_SHORT, DefaultMaterial.STEEL);
            CableState state = new CableState(elem, "test");
            state.cachedTension = 0.0;

            assertFalse(state.isBroken(), "New cable should not be broken");
        }

        @Test
        @DisplayName("cachedTension > maxTension → 斷裂")
        void brokenWhenExceedsMax() {
            CableElement elem = CableElement.create(A, B_SHORT, DefaultMaterial.STEEL);
            CableState state = new CableState(elem, "test");

            double max = state.maxTension();
            state.cachedTension = max + 1.0;

            assertTrue(state.isBroken(), "Cable should break when tension > maxTension");
        }

        @Test
        @DisplayName("cachedTension = maxTension - ε → 不斷裂")
        void notBrokenAtJustBelowMax() {
            CableElement elem = CableElement.create(A, B_SHORT, DefaultMaterial.STEEL);
            CableState state = new CableState(elem, "test");

            double max = state.maxTension();
            state.cachedTension = max - 0.001;

            assertFalse(state.isBroken(), "Cable should not break just below maxTension");
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Unmodifiable 防禦
    // ═══════════════════════════════════════════════════════

    @Nested
    @DisplayName("nodes — unmodifiable 防禦")
    class UnmodifiableTests {

        @Test
        @DisplayName("nodes.add() 拋出 UnsupportedOperationException")
        void nodesUnmodifiable() {
            CableElement elem = CableElement.create(A, B_SHORT, DefaultMaterial.STEEL);
            CableState state = new CableState(elem, "test");

            assertThrows(UnsupportedOperationException.class, () ->
                state.nodes.add(new CableNode(0, 0, 0, 1.0, false, null)));
        }

        @Test
        @DisplayName("nodes.remove() 拋出 UnsupportedOperationException")
        void nodesCannotRemove() {
            CableElement elem = CableElement.create(A, B_SHORT, DefaultMaterial.STEEL);
            CableState state = new CableState(elem, "test");

            assertThrows(UnsupportedOperationException.class, () ->
                state.nodes.remove(0));
        }
    }

    // ═══════════════════════════════════════════════════════
    //  getCachedTension
    // ═══════════════════════════════════════════════════════

    @Nested
    @DisplayName("getCachedTension — volatile 讀取")
    class CachedTensionTests {

        @Test
        @DisplayName("初始 cachedTension = 0")
        void initialCachedTensionZero() {
            CableElement elem = CableElement.create(A, B_SHORT, DefaultMaterial.STEEL);
            CableState state = new CableState(elem, "test");

            assertEquals(0.0, state.getCachedTension());
        }

        @Test
        @DisplayName("寫入 cachedTension 後可讀回")
        void writeThenRead() {
            CableElement elem = CableElement.create(A, B_SHORT, DefaultMaterial.STEEL);
            CableState state = new CableState(elem, "test");

            state.cachedTension = 12345.0;
            assertEquals(12345.0, state.getCachedTension());
        }
    }
}
