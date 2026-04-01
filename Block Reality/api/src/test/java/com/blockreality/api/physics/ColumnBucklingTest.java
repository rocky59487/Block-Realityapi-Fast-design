package com.blockreality.api.physics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ColumnBucklingCalculator 測試 — M9 Johnson 拋物線公式驗證
 *
 * 測試策略：
 *   1. criticalSlendernessRatio() — λ_c 與 AISC 值對比
 *   2. eulerBucklingStress() — 歐拉公式正確性（長柱）
 *   3. johnsonBucklingStress() — Johnson 拋物線（短/中柱）
 *   4. criticalBucklingStress() — 統一入口：公式選擇、夾制、C¹ 連續性
 *   5. 特殊值與護欄：λ=0、λ→∞、負值輸入
 *   6. Minecraft 方塊柱實際應用場景
 *
 * 數值容忍度：5%（對應 CONTRIBUTING.md 中的測試規範）
 */
@DisplayName("ColumnBucklingCalculator — M9 Johnson 拋物線公式")
class ColumnBucklingTest {

    /** 全域 5% 容忍度（CONTRIBUTING.md 規範） */
    private static final double TOLERANCE_5PCT = 0.05;

    // ═══════════════════════════════════════════════════════
    //  臨界細長比 λ_c = π√(2E/Fy)
    // ═══════════════════════════════════════════════════════

    @Nested
    @DisplayName("criticalSlendernessRatio() — λ_c 計算")
    class CriticalSlendernessRatioTests {

        @Test
        @DisplayName("鋼材（E=200 GPa, Fy=345 MPa）λ_c ≈ 107（AISC A36/A992 典型值）")
        void steelLambdaC() {
            double E = 200e9;  // Pa
            double Fy = 345e6; // Pa (Q345 / A992)
            double lambdaC = ColumnBucklingCalculator.criticalSlendernessRatio(E, Fy);
            // λ_c = π × √(2 × 200e9 / 345e6) = π × √(1159.4) ≈ 107.0
            assertEquals(107.0, lambdaC, 107.0 * TOLERANCE_5PCT,
                "鋼材 Q345 的臨界細長比應約為 107");
        }

        @Test
        @DisplayName("高強鋼（E=200 GPa, Fy=690 MPa）λ_c < 鋼材 Q345 λ_c（強度高 → 轉換點小）")
        void highStrengthSteelLambdaC() {
            double lambdaC_q345 = ColumnBucklingCalculator.criticalSlendernessRatio(200e9, 345e6);
            double lambdaC_hs   = ColumnBucklingCalculator.criticalSlendernessRatio(200e9, 690e6);
            assertTrue(lambdaC_hs < lambdaC_q345,
                "降伏強度越高，臨界細長比 λ_c 越小（轉換點前移）");
        }

        @Test
        @DisplayName("λ_c 隨 E 增大而增大（模量高 → 轉換點大）")
        void lambdaCIncreasesWithModulus() {
            double Fy = 250e6;
            double lambdaC_lo = ColumnBucklingCalculator.criticalSlendernessRatio(100e9, Fy);
            double lambdaC_hi = ColumnBucklingCalculator.criticalSlendernessRatio(200e9, Fy);
            assertTrue(lambdaC_hi > lambdaC_lo,
                "楊氏模量越高，臨界細長比 λ_c 越大");
        }

        @Test
        @DisplayName("λ_c 與公式吻合：π × √(2E/Fy)")
        void lambdaCMatchesFormula() {
            double E = 70e9;   // Glass ~70 GPa
            double Fy = 100e6; // Glass Rcomp in Pa
            double expected = Math.PI * Math.sqrt(2.0 * E / Fy);
            double actual = ColumnBucklingCalculator.criticalSlendernessRatio(E, Fy);
            assertEquals(expected, actual, expected * 1e-9,
                "criticalSlendernessRatio 應精確匹配 π√(2E/Fy)");
        }

        @Test
        @DisplayName("E ≤ 0 拋 IllegalArgumentException")
        void invalidEThrows() {
            assertThrows(IllegalArgumentException.class,
                () -> ColumnBucklingCalculator.criticalSlendernessRatio(0, 345e6));
            assertThrows(IllegalArgumentException.class,
                () -> ColumnBucklingCalculator.criticalSlendernessRatio(-1e9, 345e6));
        }

        @Test
        @DisplayName("Fy ≤ 0 拋 IllegalArgumentException")
        void invalidFyThrows() {
            assertThrows(IllegalArgumentException.class,
                () -> ColumnBucklingCalculator.criticalSlendernessRatio(200e9, 0));
            assertThrows(IllegalArgumentException.class,
                () -> ColumnBucklingCalculator.criticalSlendernessRatio(200e9, -1e6));
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Euler 挫屈應力 σ_cr = π²E/λ²
    // ═══════════════════════════════════════════════════════

    @Nested
    @DisplayName("eulerBucklingStress() — 歐拉長柱公式")
    class EulerBucklingStressTests {

        @Test
        @DisplayName("λ=100, E=200 GPa → σ_cr ≈ 197 MPa（AISC 典型長柱）")
        void eulerTypicalSteel() {
            double E = 200e9;
            double lambda = 100.0;
            double sigmaCr = ColumnBucklingCalculator.eulerBucklingStress(E, lambda);
            // π² × 200e9 / 10000 = 197.4e6 Pa
            assertEquals(197.4e6, sigmaCr, 197.4e6 * TOLERANCE_5PCT,
                "λ=100 鋼柱歐拉臨界應力應約 197 MPa");
        }

        @Test
        @DisplayName("σ_cr 與 1/λ² 成比例（細長比加倍，應力降為 1/4）")
        void eulerInverseSquareRelationship() {
            double E = 200e9;
            double sigma1 = ColumnBucklingCalculator.eulerBucklingStress(E, 50.0);
            double sigma2 = ColumnBucklingCalculator.eulerBucklingStress(E, 100.0);
            assertEquals(4.0, sigma1 / sigma2, 4.0 * TOLERANCE_5PCT,
                "細長比加倍，歐拉應力應降為 1/4（1/λ² 關係）");
        }

        @Test
        @DisplayName("σ_cr 與 E 成正比")
        void eulerLinearWithModulus() {
            double sigma1 = ColumnBucklingCalculator.eulerBucklingStress(100e9, 100.0);
            double sigma2 = ColumnBucklingCalculator.eulerBucklingStress(200e9, 100.0);
            assertEquals(2.0, sigma2 / sigma1, 2.0 * TOLERANCE_5PCT,
                "歐拉應力與楊氏模量 E 成正比");
        }

        @ParameterizedTest
        @ValueSource(doubles = {10.0, 50.0, 100.0, 200.0, 500.0})
        @DisplayName("歐拉應力對多種細長比均返回正有限值")
        void eulerPositiveFinite(double lambda) {
            double sigma = ColumnBucklingCalculator.eulerBucklingStress(200e9, lambda);
            assertTrue(sigma > 0 && Double.isFinite(sigma),
                "λ=" + lambda + " 時歐拉應力應為正有限值");
        }

        @Test
        @DisplayName("λ ≤ 0 拋 IllegalArgumentException")
        void invalidLambdaThrows() {
            assertThrows(IllegalArgumentException.class,
                () -> ColumnBucklingCalculator.eulerBucklingStress(200e9, 0.0));
            assertThrows(IllegalArgumentException.class,
                () -> ColumnBucklingCalculator.eulerBucklingStress(200e9, -10.0));
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Johnson 拋物線 σ_cr = Fy × [1 − Fy×λ²/(4π²E)]
    // ═══════════════════════════════════════════════════════

    @Nested
    @DisplayName("johnsonBucklingStress() — Johnson 拋物線公式")
    class JohnsonBucklingStressTests {

        @Test
        @DisplayName("λ=0（剛體）→ σ_cr = Fy（材料強度控制）")
        void johnsonAtLambdaZero() {
            double Fy = 345e6;
            double sigma = ColumnBucklingCalculator.johnsonBucklingStress(200e9, Fy, 0.0);
            assertEquals(Fy, sigma, Fy * 1e-6,
                "λ=0 時 Johnson 應力應等於 F_y（材料全截面降伏）");
        }

        @Test
        @DisplayName("鋼材 λ=50（短柱）σ_cr 在合理範圍")
        void johnsonShortSteelColumn() {
            double E = 200e9;
            double Fy = 345e6;
            double lambda = 50.0;
            double sigma = ColumnBucklingCalculator.johnsonBucklingStress(E, Fy, lambda);
            // σ_cr = 345e6 × [1 - 345e6 × 2500 / (4π² × 200e9)]
            //       = 345e6 × [1 - 862.5e9 / 7896e9]
            //       = 345e6 × [1 - 0.1093]
            //       ≈ 307.3 MPa
            assertEquals(307.3e6, sigma, 307.3e6 * TOLERANCE_5PCT,
                "λ=50 鋼柱 Johnson 應力應約 307 MPa");
        }

        @Test
        @DisplayName("Johnson 應力單調遞減（λ 增大 → σ_cr 減小）")
        void johnsonMonotonicallyDecreasing() {
            double E = 200e9;
            double Fy = 345e6;
            double sigma1 = ColumnBucklingCalculator.johnsonBucklingStress(E, Fy, 20.0);
            double sigma2 = ColumnBucklingCalculator.johnsonBucklingStress(E, Fy, 60.0);
            double sigma3 = ColumnBucklingCalculator.johnsonBucklingStress(E, Fy, 100.0);
            assertTrue(sigma1 > sigma2 && sigma2 > sigma3,
                "Johnson 應力應隨細長比增大而單調遞減");
        }

        @Test
        @DisplayName("λ 負值拋 IllegalArgumentException")
        void negativeLambdaThrows() {
            assertThrows(IllegalArgumentException.class,
                () -> ColumnBucklingCalculator.johnsonBucklingStress(200e9, 345e6, -1.0));
        }

        @Test
        @DisplayName("Johnson 應力 ≤ Fy（不超過降伏強度）")
        void johnsonNeverExceedsYield() {
            double Fy = 250e6;
            for (double lambda = 0; lambda <= 120; lambda += 10) {
                double sigma = ColumnBucklingCalculator.johnsonBucklingStress(200e9, Fy, lambda);
                assertTrue(sigma <= Fy + 1e-6,
                    "λ=" + lambda + " 時 Johnson 應力不應超過 F_y");
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    //  統一入口 criticalBucklingStress() — 公式切換與夾制
    // ═══════════════════════════════════════════════════════

    @Nested
    @DisplayName("criticalBucklingStress() — 自動公式選擇與結果夾制")
    class CriticalBucklingStressTests {

        @Test
        @DisplayName("λ < λ_c 時使用 Johnson（短柱應力高於純 Euler）")
        void shortColumnUsesJohnson() {
            double E = 200e9;
            double Fy = 345e6;
            double lambdaC = ColumnBucklingCalculator.criticalSlendernessRatio(E, Fy);
            double lambdaShort = lambdaC * 0.5; // 遠在轉換點以下

            double actualSigma = ColumnBucklingCalculator.criticalBucklingStress(E, Fy, lambdaShort);
            double eulerSigma  = ColumnBucklingCalculator.eulerBucklingStress(E, lambdaShort);

            // Johnson 應力 < Euler 應力（Johnson 更保守）
            assertTrue(actualSigma < eulerSigma,
                "短柱使用 Johnson 公式，結果應小於 Euler（更保守）");
        }

        @Test
        @DisplayName("λ > λ_c 時使用 Euler（長柱）")
        void longColumnUsesEuler() {
            double E = 200e9;
            double Fy = 345e6;
            double lambdaC = ColumnBucklingCalculator.criticalSlendernessRatio(E, Fy);
            double lambdaLong = lambdaC * 2.0;

            double actualSigma = ColumnBucklingCalculator.criticalBucklingStress(E, Fy, lambdaLong);
            double eulerSigma  = ColumnBucklingCalculator.eulerBucklingStress(E, lambdaLong);

            assertEquals(eulerSigma, actualSigma, eulerSigma * 1e-9,
                "長柱（λ > λ_c）應直接使用 Euler 公式，結果應完全匹配");
        }

        @Test
        @DisplayName("λ = λ_c 時 Euler 與 Johnson 連續（差距 < 1%）")
        void continuityAtTransitionPoint() {
            double E = 200e9;
            double Fy = 345e6;
            double lambdaC = ColumnBucklingCalculator.criticalSlendernessRatio(E, Fy);

            double sigmaJohnson = ColumnBucklingCalculator.johnsonBucklingStress(E, Fy, lambdaC);
            double sigmaEuler   = ColumnBucklingCalculator.eulerBucklingStress(E, lambdaC);

            // 在轉換點，兩個公式應相切（理論上完全相等）
            assertEquals(sigmaEuler, sigmaJohnson, sigmaEuler * 0.01,
                "λ = λ_c 時 Euler 與 Johnson 應連續（差距 < 1%），確保 C¹ 連續性");
        }

        @ParameterizedTest
        @DisplayName("σ_cr 始終夾制在 [0, Fy]")
        @CsvSource({
            "200e9, 345e6, 0.0",
            "200e9, 345e6, 1.0",
            "200e9, 345e6, 50.0",
            "200e9, 345e6, 107.0",
            "200e9, 345e6, 200.0",
            "200e9, 345e6, 500.0",
            "30e9, 30e6, 100.0",
            "11e9, 5e6, 200.0"
        })
        void resultClampedToFy(double E, double Fy, double lambda) {
            double sigma = ColumnBucklingCalculator.criticalBucklingStress(E, Fy, lambda);
            assertTrue(sigma >= 0,
                "臨界應力不應為負（λ=" + lambda + "）");
            assertTrue(sigma <= Fy + 1e-6,
                "臨界應力不應超過 F_y（λ=" + lambda + "）");
        }

        @Test
        @DisplayName("λ=0（極短柱）→ σ_cr = Fy（材料強度上限）")
        void zeroSlendernessYieldsStrength() {
            double Fy = 345e6;
            double sigma = ColumnBucklingCalculator.criticalBucklingStress(200e9, Fy, 0.0);
            assertEquals(Fy, sigma, Fy * 1e-6,
                "λ=0 時臨界應力等於降伏強度（極短柱不挫屈）");
        }

        @Test
        @DisplayName("λ 負值拋 IllegalArgumentException")
        void negativeLambdaThrows() {
            assertThrows(IllegalArgumentException.class,
                () -> ColumnBucklingCalculator.criticalBucklingStress(200e9, 345e6, -1.0));
        }

        @Test
        @DisplayName("E 或 Fy ≤ 0 拋 IllegalArgumentException")
        void invalidInputsThrow() {
            assertThrows(IllegalArgumentException.class,
                () -> ColumnBucklingCalculator.criticalBucklingStress(0, 345e6, 100.0));
            assertThrows(IllegalArgumentException.class,
                () -> ColumnBucklingCalculator.criticalBucklingStress(200e9, 0, 100.0));
            assertThrows(IllegalArgumentException.class,
                () -> ColumnBucklingCalculator.criticalBucklingStress(-200e9, 345e6, 100.0));
        }

        @Test
        @DisplayName("大細長比（λ=500）返回極小但有限正值")
        void veryLargeSlendernessFinite() {
            double sigma = ColumnBucklingCalculator.criticalBucklingStress(200e9, 345e6, 500.0);
            assertTrue(sigma > 0, "λ=500 時臨界應力應 > 0");
            assertTrue(Double.isFinite(sigma), "λ=500 時臨界應力應為有限值");
            assertTrue(sigma < 1e6, "λ=500 時臨界應力應極小（< 1 MPa）");
        }
    }

    // ═══════════════════════════════════════════════════════
    //  細長比計算與方塊迴轉半徑
    // ═══════════════════════════════════════════════════════

    @Nested
    @DisplayName("細長比計算與方塊幾何")
    class SlendernessRatioTests {

        @Test
        @DisplayName("標準方塊迴轉半徑 r ≈ 0.2887 m（1m×1m 截面）")
        void blockRadiusOfGyration() {
            double r = ColumnBucklingCalculator.blockRadiusOfGyration();
            // r = sqrt(I/A) = sqrt((1/12)/1) = 1/sqrt(12) ≈ 0.2887
            assertEquals(0.2887, r, 0.2887 * TOLERANCE_5PCT,
                "1m×1m 方形截面迴轉半徑應約 0.2887 m");
        }

        @Test
        @DisplayName("兩端鉸接柱（K=1.0），L=5m → λ ≈ 17.3")
        void pinnedEndColumn() {
            double r = ColumnBucklingCalculator.blockRadiusOfGyration();
            double lambda = ColumnBucklingCalculator.slendernessRatio(1.0, 5.0, r);
            // λ = 1.0 × 5.0 / 0.2887 ≈ 17.32
            assertEquals(17.32, lambda, 17.32 * TOLERANCE_5PCT,
                "兩端鉸接 5m 柱的細長比應約 17.3");
        }

        @Test
        @DisplayName("懸臂柱（K=2.0），L=3m → λ 為鉸接的 2 倍")
        void cantileveredColumnDoubledLambda() {
            double r = ColumnBucklingCalculator.blockRadiusOfGyration();
            double lambdaPinned     = ColumnBucklingCalculator.slendernessRatio(1.0, 3.0, r);
            double lambdaCantilever = ColumnBucklingCalculator.slendernessRatio(2.0, 3.0, r);
            assertEquals(2.0, lambdaCantilever / lambdaPinned, 0.001,
                "懸臂柱（K=2）的細長比應為鉸接柱（K=1）的 2 倍");
        }

        @Test
        @DisplayName("r ≤ 0 拋 IllegalArgumentException")
        void invalidRadiusThrows() {
            assertThrows(IllegalArgumentException.class,
                () -> ColumnBucklingCalculator.slendernessRatio(1.0, 5.0, 0.0));
            assertThrows(IllegalArgumentException.class,
                () -> ColumnBucklingCalculator.slendernessRatio(1.0, 5.0, -0.1));
        }

        @Test
        @DisplayName("L=0（零長度柱）→ λ=0（不拋例外）")
        void zeroLengthColumn() {
            double r = ColumnBucklingCalculator.blockRadiusOfGyration();
            double lambda = ColumnBucklingCalculator.slendernessRatio(1.0, 0.0, r);
            assertEquals(0.0, lambda, 1e-10,
                "零長度柱的細長比應為 0");
        }
    }

    // ═══════════════════════════════════════════════════════
    //  利用率計算
    // ═══════════════════════════════════════════════════════

    @Nested
    @DisplayName("bucklingUtilizationRatio() — 挫屈利用率")
    class UtilizationRatioTests {

        @Test
        @DisplayName("σ_actual < σ_cr → UR < 1.0（安全）")
        void safeColumnUrBelowOne() {
            double E = 200e9;
            double Fy = 345e6;
            double lambda = 50.0; // 短柱，Johnson 區間
            double sigmaCr = ColumnBucklingCalculator.criticalBucklingStress(E, Fy, lambda);
            double actualStress = sigmaCr * 0.8; // 80% 利用率

            double ur = ColumnBucklingCalculator.bucklingUtilizationRatio(actualStress, E, Fy, lambda);
            assertEquals(0.8, ur, 0.8 * TOLERANCE_5PCT,
                "實際應力為 80% σ_cr 時，利用率應為 0.8");
        }

        @Test
        @DisplayName("σ_actual = σ_cr → UR = 1.0（臨界狀態）")
        void criticalColumnUrEqualsOne() {
            double E = 200e9;
            double Fy = 345e6;
            double lambda = 120.0; // 長柱，Euler 區間
            double sigmaCr = ColumnBucklingCalculator.criticalBucklingStress(E, Fy, lambda);

            double ur = ColumnBucklingCalculator.bucklingUtilizationRatio(sigmaCr, E, Fy, lambda);
            assertEquals(1.0, ur, 0.01,
                "實際應力等於 σ_cr 時，利用率應為 1.0（臨界挫屈）");
        }

        @Test
        @DisplayName("σ_actual > σ_cr → UR > 1.0（失效）")
        void failedColumnUrAboveOne() {
            double E = 200e9;
            double Fy = 345e6;
            double lambda = 200.0;
            double sigmaCr = ColumnBucklingCalculator.criticalBucklingStress(E, Fy, lambda);
            double overloadStress = sigmaCr * 1.5; // 超載 50%

            double ur = ColumnBucklingCalculator.bucklingUtilizationRatio(overloadStress, E, Fy, lambda);
            assertTrue(ur > 1.0, "超載時利用率應 > 1.0（表示挫屈失效）");
        }

        @Test
        @DisplayName("σ_actual = 0 → UR = 0（無荷載）")
        void zeroLoadUrIsZero() {
            double ur = ColumnBucklingCalculator.bucklingUtilizationRatio(0.0, 200e9, 345e6, 100.0);
            assertEquals(0.0, ur, 1e-10, "零荷載時利用率應為 0");
        }
    }

    // ═══════════════════════════════════════════════════════
    //  與 DefaultMaterial 整合驗證
    // ═══════════════════════════════════════════════════════

    @Nested
    @DisplayName("與 DefaultMaterial 整合 — 真實材料參數")
    class DefaultMaterialIntegrationTests {

        @Test
        @DisplayName("STEEL（E=200 GPa, Fy=345 MPa）短柱 λ=30 使用 Johnson，σ_cr > 300 MPa")
        void steelShortColumnJohnson() {
            com.blockreality.api.material.DefaultMaterial steel =
                com.blockreality.api.material.DefaultMaterial.STEEL;
            double E  = steel.getYoungsModulusPa();
            double Fy = steel.getYieldStrength() * 1e6; // MPa → Pa

            double sigma = ColumnBucklingCalculator.criticalBucklingStress(E, Fy, 30.0);
            assertTrue(sigma > 300e6,
                "鋼材短柱（λ=30）臨界應力應 > 300 MPa");
        }

        @Test
        @DisplayName("CONCRETE（E=30 GPa, Fy=30 MPa）中柱 λ=50，σ_cr 非負有限")
        void concreteColumnFiniteResult() {
            com.blockreality.api.material.DefaultMaterial concrete =
                com.blockreality.api.material.DefaultMaterial.CONCRETE;
            double E  = concrete.getYoungsModulusPa();
            double Fy = concrete.getYieldStrength() * 1e6;

            double sigma = ColumnBucklingCalculator.criticalBucklingStress(E, Fy, 50.0);
            assertTrue(sigma >= 0 && Double.isFinite(sigma),
                "混凝土柱臨界應力應為非負有限值");
        }

        @Test
        @DisplayName("TIMBER（E=11 GPa, Fy=5 MPa）各細長比均返回有限值")
        void timberAllSlendernessFinite() {
            com.blockreality.api.material.DefaultMaterial timber =
                com.blockreality.api.material.DefaultMaterial.TIMBER;
            double E  = timber.getYoungsModulusPa();
            double Fy = timber.getYieldStrength() * 1e6;

            for (double lambda : new double[]{0, 10, 50, 100, 200}) {
                double sigma = ColumnBucklingCalculator.criticalBucklingStress(E, Fy, lambda);
                assertTrue(Double.isFinite(sigma) && sigma >= 0,
                    "TIMBER λ=" + lambda + " 應返回有限非負值");
            }
        }

        @Test
        @DisplayName("BEDROCK（isIndestructible）高強度短柱 λ=1 → σ_cr = Fy（不溢出）")
        void bedrockShortColumnNoOverflow() {
            com.blockreality.api.material.DefaultMaterial bedrock =
                com.blockreality.api.material.DefaultMaterial.BEDROCK;
            double E  = bedrock.getYoungsModulusPa();
            double Fy = bedrock.getYieldStrength() * 1e6;

            double sigma = ColumnBucklingCalculator.criticalBucklingStress(E, Fy, 1.0);
            assertTrue(Double.isFinite(sigma),
                "BEDROCK 短柱應力不應溢出 double 範圍");
            assertTrue(sigma > 0,
                "BEDROCK 短柱應力應 > 0");
        }
    }
}
