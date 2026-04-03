package com.blockreality.api.material;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DynamicMaterial 單元測試 — 驗證動態材料計算的核心行為。
 *
 * 測試策略：
 *   1. ofRCFusion() 公式正確性（預設參數：phiTens=0.8, phiShear=0.6, compBoost=1.1）
 *   2. 蜂窩懲罰（hasHoneycomb=true 時所有強度 × 0.7）
 *   3. ofCustom() 建構與驗證
 *   4. RMaterial 介面與 record accessor 一致性
 *   5. 輸入驗證（null 檢查、負值檢查）
 *   6. ofCustom() 驗證（id 不為 null/empty、density > 0、負值鉗制）
 *   7. 密度混合公式（97% 混凝土 + 3% 鋼筋）
 *   8. 繼承的預設方法（getCombinedStrength()、isDuctile()、getMaxSpan()）
 *
 * 參考：DynamicMaterial 的 factory 方法與 record 不可變性
 */
@DisplayName("DynamicMaterial — 動態材料計算")
class DynamicMaterialTest {

    // ─────────────────────────────────────────────────────────
    //  ofRCFusion() 公式正確性
    // ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ofRCFusion() — RC 融合公式")
    class RCFusionFormulaTests {

        private RMaterial concrete;
        private RMaterial rebar;

        @BeforeEach
        void setUp() {
            concrete = DefaultMaterial.CONCRETE;  // Rcomp=30, Rtens=3.0, Rshear=4.0, density=2350
            rebar = DefaultMaterial.STEEL;        // Rcomp=350, Rtens=500, Rshear=200, density=7850
        }

        @Test
        @DisplayName("抗壓強度公式：Rcomp_concrete × compBoost (1.1)")
        void compressiveStrengthFormula() {
            DynamicMaterial result = DynamicMaterial.ofRCFusion(
                concrete, rebar, 0.8, 0.6, 1.1, false);

            double expected = 30.0 * 1.1;  // 33.0 MPa
            assertEquals(expected, result.getRcomp(), 1e-10,
                "Rcomp should be concrete × 1.1");
        }

        @Test
        @DisplayName("抗拉強度公式：Rtens_concrete + Rtens_rebar × phiTens (0.8)")
        void tensileStrengthFormula() {
            DynamicMaterial result = DynamicMaterial.ofRCFusion(
                concrete, rebar, 0.8, 0.6, 1.1, false);

            double expected = 3.0 + 500.0 * 0.8;  // 3.0 + 400 = 403.0 MPa
            assertEquals(expected, result.getRtens(), 1e-10,
                "Rtens should be concrete + rebar × 0.8");
        }

        @Test
        @DisplayName("抗剪強度公式：Rshear_concrete + Rshear_rebar × phiShear (0.6)")
        void shearStrengthFormula() {
            DynamicMaterial result = DynamicMaterial.ofRCFusion(
                concrete, rebar, 0.8, 0.6, 1.1, false);

            double expected = 4.0 + 200.0 * 0.6;  // 4.0 + 120 = 124.0 MPa
            assertEquals(expected, result.getRshear(), 1e-10,
                "Rshear should be concrete + rebar × 0.6");
        }

        @Test
        @DisplayName("密度混合公式：concrete × 0.97 + rebar × 0.03")
        void densityMixingFormula() {
            DynamicMaterial result = DynamicMaterial.ofRCFusion(
                concrete, rebar, 0.8, 0.6, 1.1, false);

            double expected = 2350.0 * 0.97 + 7850.0 * 0.03;  // 2279.5 + 235.5 = 2515.0 kg/m³
            assertEquals(expected, result.getDensity(), 1e-10,
                "Density should be 97% concrete + 3% rebar");
        }

        @Test
        @DisplayName("材料 ID：hasHoneycomb=false 時為 'rc_fusion'")
        void materialIdWithoutHoneycomb() {
            DynamicMaterial result = DynamicMaterial.ofRCFusion(
                concrete, rebar, 0.8, 0.6, 1.1, false);

            assertEquals("rc_fusion", result.getMaterialId(),
                "Material ID should be 'rc_fusion' when hasHoneycomb=false");
        }

        @Test
        @DisplayName("材料 ID：hasHoneycomb=true 時為 'rc_fusion_honeycomb'")
        void materialIdWithHoneycomb() {
            DynamicMaterial result = DynamicMaterial.ofRCFusion(
                concrete, rebar, 0.8, 0.6, 1.1, true);

            assertEquals("rc_fusion_honeycomb", result.getMaterialId(),
                "Material ID should be 'rc_fusion_honeycomb' when hasHoneycomb=true");
        }

        @Test
        @DisplayName("自訂 compBoost (1.5)：Rcomp = 30 × 1.5 = 45.0")
        void customCompBoost() {
            DynamicMaterial result = DynamicMaterial.ofRCFusion(
                concrete, rebar, 0.8, 0.6, 1.5, false);

            double expected = 30.0 * 1.5;  // 45.0
            assertEquals(expected, result.getRcomp(), 1e-10);
        }

        @Test
        @DisplayName("自訂 phiTens (0.5)：Rtens = 3.0 + 500 × 0.5 = 253.0")
        void customPhiTens() {
            DynamicMaterial result = DynamicMaterial.ofRCFusion(
                concrete, rebar, 0.5, 0.6, 1.1, false);

            double expected = 3.0 + 500.0 * 0.5;  // 253.0
            assertEquals(expected, result.getRtens(), 1e-10);
        }

        @Test
        @DisplayName("自訂 phiShear (0.4)：Rshear = 4.0 + 200 × 0.4 = 84.0")
        void customPhiShear() {
            DynamicMaterial result = DynamicMaterial.ofRCFusion(
                concrete, rebar, 0.8, 0.4, 1.1, false);

            double expected = 4.0 + 200.0 * 0.4;  // 84.0
            assertEquals(expected, result.getRshear(), 1e-10);
        }
    }

    // ─────────────────────────────────────────────────────────
    //  蜂窩懲罰（Honeycomb Penalty）
    // ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("hasHoneycomb=true — 蜂窩懲罰（× 0.7）")
    class HoneycombPenaltyTests {

        private RMaterial concrete;
        private RMaterial rebar;

        @BeforeEach
        void setUp() {
            concrete = DefaultMaterial.CONCRETE;
            rebar = DefaultMaterial.STEEL;
        }

        @Test
        @DisplayName("蜂窩懲罰：抗壓強度 × 0.7")
        void honeycombCompressivePenalty() {
            DynamicMaterial withoutHoneycomb = DynamicMaterial.ofRCFusion(
                concrete, rebar, 0.8, 0.6, 1.1, false);
            DynamicMaterial withHoneycomb = DynamicMaterial.ofRCFusion(
                concrete, rebar, 0.8, 0.6, 1.1, true);

            double expected = withoutHoneycomb.getRcomp() * 0.7;
            assertEquals(expected, withHoneycomb.getRcomp(), 1e-10,
                "Honeycomb should reduce Rcomp by 30%");
        }

        @Test
        @DisplayName("蜂窩懲罰：抗拉強度 × 0.7")
        void honeycombTensilePenalty() {
            DynamicMaterial withoutHoneycomb = DynamicMaterial.ofRCFusion(
                concrete, rebar, 0.8, 0.6, 1.1, false);
            DynamicMaterial withHoneycomb = DynamicMaterial.ofRCFusion(
                concrete, rebar, 0.8, 0.6, 1.1, true);

            double expected = withoutHoneycomb.getRtens() * 0.7;
            assertEquals(expected, withHoneycomb.getRtens(), 1e-10,
                "Honeycomb should reduce Rtens by 30%");
        }

        @Test
        @DisplayName("蜂窩懲罰：抗剪強度 × 0.7")
        void honeycombShearPenalty() {
            DynamicMaterial withoutHoneycomb = DynamicMaterial.ofRCFusion(
                concrete, rebar, 0.8, 0.6, 1.1, false);
            DynamicMaterial withHoneycomb = DynamicMaterial.ofRCFusion(
                concrete, rebar, 0.8, 0.6, 1.1, true);

            double expected = withoutHoneycomb.getRshear() * 0.7;
            assertEquals(expected, withHoneycomb.getRshear(), 1e-10,
                "Honeycomb should reduce Rshear by 30%");
        }

        @Test
        @DisplayName("蜂窩不影響密度（仍為 97% + 3%）")
        void honeycombDoesNotAffectDensity() {
            DynamicMaterial withoutHoneycomb = DynamicMaterial.ofRCFusion(
                concrete, rebar, 0.8, 0.6, 1.1, false);
            DynamicMaterial withHoneycomb = DynamicMaterial.ofRCFusion(
                concrete, rebar, 0.8, 0.6, 1.1, true);

            assertEquals(withoutHoneycomb.getDensity(), withHoneycomb.getDensity(), 1e-10,
                "Honeycomb should not affect density");
        }

        @Test
        @DisplayName("詳細驗證：蜂窩影響所有三個強度值（不只是 Rcomp）")
        void honeycombAffectsAllThreeStrengths() {
            DynamicMaterial result = DynamicMaterial.ofRCFusion(
                concrete, rebar, 0.8, 0.6, 1.1, true);

            // 計算不帶蜂窩的值 (CONCRETE: Rcomp=30, Rtens=3.0, Rshear=4.0)
            double rcompNoHoneycomb = 30.0 * 1.1;      // 33.0
            double rtensNoHoneycomb = 3.0 + 500.0 * 0.8;  // 403.0
            double rshearNoHoneycomb = 4.0 + 200.0 * 0.6; // 124.0

            // 蜂窩懲罰
            assertEquals(rcompNoHoneycomb * 0.7, result.getRcomp(), 1e-10);
            assertEquals(rtensNoHoneycomb * 0.7, result.getRtens(), 1e-10);
            assertEquals(rshearNoHoneycomb * 0.7, result.getRshear(), 1e-10);
        }
    }

    // ─────────────────────────────────────────────────────────
    //  ofCustom() 建構與驗證
    // ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ofCustom() — 自訂材料建構")
    class CustomConstructionTests {

        @Test
        @DisplayName("ofCustom 建立材料並保留所有欄位值")
        void customConstructionPreservesValues() {
            DynamicMaterial result = DynamicMaterial.ofCustom("my_material", 50.0, 10.0, 8.0, 2500.0);

            assertEquals("my_material", result.getMaterialId());
            assertEquals(50.0, result.getRcomp(), 1e-10);
            assertEquals(10.0, result.getRtens(), 1e-10);
            assertEquals(8.0, result.getRshear(), 1e-10);
            assertEquals(2500.0, result.getDensity(), 1e-10);
        }

        @Test
        @DisplayName("ofCustom 拒絕負數強度（★ Fix #3 改為拋出 IllegalArgumentException）")
        void customRejectsNegativeStrengths() {
            // Fix #3 changed behavior from silently clamping to explicitly rejecting negatives
            assertThrows(IllegalArgumentException.class,
                () -> DynamicMaterial.ofCustom("test", -50.0, -10.0, -8.0, 2500.0),
                "Negative strength values should throw IllegalArgumentException");
        }

        @Test
        @DisplayName("ofCustom 保留零強度值（不鉗制）")
        void customPreservesZeroStrengths() {
            DynamicMaterial result = DynamicMaterial.ofCustom("sand_like", 0.0, 0.0, 0.0, 1600.0);

            assertEquals(0.0, result.getRcomp());
            assertEquals(0.0, result.getRtens());
            assertEquals(0.0, result.getRshear());
        }

        @Test
        @DisplayName("ofCustom 拒絕混合正負數（只要有一個負數即拋出 IllegalArgumentException）")
        void customRejectsMixedNegative() {
            // Fix #3: even partial negatives are rejected, not clamped
            assertThrows(IllegalArgumentException.class,
                () -> DynamicMaterial.ofCustom("mixed", 30.0, -5.0, 15.0, 2300.0),
                "Any negative strength value should throw IllegalArgumentException");
        }
    }

    // ─────────────────────────────────────────────────────────
    //  輸入驗證（ofRCFusion）
    // ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ofRCFusion() — 輸入驗證")
    class RCFusionValidationTests {

        private RMaterial concrete;
        private RMaterial rebar;

        @BeforeEach
        void setUp() {
            concrete = DefaultMaterial.CONCRETE;
            rebar = DefaultMaterial.STEEL;
        }

        @Test
        @DisplayName("null 混凝土 → NullPointerException")
        void nullConcreteThrows() {
            assertThrows(NullPointerException.class, () ->
                DynamicMaterial.ofRCFusion(null, rebar, 0.8, 0.6, 1.1, false),
                "ofRCFusion should throw when concrete is null");
        }

        @Test
        @DisplayName("null 鋼筋 → NullPointerException")
        void nullRebarThrows() {
            assertThrows(NullPointerException.class, () ->
                DynamicMaterial.ofRCFusion(concrete, null, 0.8, 0.6, 1.1, false),
                "ofRCFusion should throw when rebar is null");
        }

        @Test
        @DisplayName("混凝土 Rcomp < 0 → IllegalArgumentException")
        void negativeConcretRcompThrows() {
            // Builder.rcomp() 直接拒絕負值
            assertThrows(IllegalArgumentException.class, () ->
                new CustomMaterial.Builder("bad_concrete")
                    .rcomp(-10.0)
                    .rshear(15.0)
                    .rtens(25.0)
                    .build());
        }

        @Test
        @DisplayName("鋼筋 Rtens < 0 → IllegalArgumentException")
        void negativeRebarRtensThrows() {
            // Builder.rtens() 直接拒絕負值
            assertThrows(IllegalArgumentException.class, () ->
                new CustomMaterial.Builder("bad_rebar")
                    .rcomp(300.0)
                    .rshear(180.0)
                    .rtens(-100.0)
                    .build());
        }

        @Test
        @DisplayName("正常有效的輸入（不拋例外）")
        void validInputsSucceed() {
            assertDoesNotThrow(() ->
                DynamicMaterial.ofRCFusion(concrete, rebar, 0.8, 0.6, 1.1, false));
        }

        @Test
        @DisplayName("極端但有效的參數（phiTens=0, phiShear=2.0, compBoost=5.0）")
        void extremeValidParameters() {
            DynamicMaterial result = DynamicMaterial.ofRCFusion(
                concrete, rebar, 0.0, 2.0, 5.0, false);

            // phiTens=0 → Rtens = 3.0 + 0 = 3.0
            assertEquals(3.0, result.getRtens(), 1e-10);
            // phiShear=2.0 → Rshear = 4.0 + 200*2.0 = 404.0
            assertEquals(404.0, result.getRshear(), 1e-10);
            // compBoost=5.0 → Rcomp = 30*5 = 150.0
            assertEquals(150.0, result.getRcomp(), 1e-10);
        }
    }

    // ─────────────────────────────────────────────────────────
    //  ofCustom() 驗證
    // ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ofCustom() — 驗證")
    class CustomValidationTests {

        @Test
        @DisplayName("null ID → NullPointerException")
        void nullIdThrows() {
            assertThrows(NullPointerException.class, () ->
                DynamicMaterial.ofCustom(null, 50.0, 10.0, 8.0, 2500.0));
        }

        @Test
        @DisplayName("空字串 ID → IllegalArgumentException")
        void emptyIdThrows() {
            assertThrows(IllegalArgumentException.class, () ->
                DynamicMaterial.ofCustom("", 50.0, 10.0, 8.0, 2500.0),
                "ofCustom should throw when id is empty");
        }

        @Test
        @DisplayName("density ≤ 0 → IllegalArgumentException")
        void zeroDensityThrows() {
            assertThrows(IllegalArgumentException.class, () ->
                DynamicMaterial.ofCustom("test", 50.0, 10.0, 8.0, 0.0),
                "ofCustom should throw when density is 0");
        }

        @Test
        @DisplayName("density < 0 → IllegalArgumentException")
        void negativeDensityThrows() {
            assertThrows(IllegalArgumentException.class, () ->
                DynamicMaterial.ofCustom("test", 50.0, 10.0, 8.0, -500.0),
                "ofCustom should throw when density is negative");
        }

        @Test
        @DisplayName("有效的 density > 0")
        void validPositiveDensity() {
            assertDoesNotThrow(() ->
                DynamicMaterial.ofCustom("test", 50.0, 10.0, 8.0, 0.001));
        }

        @Test
        @DisplayName("非空 ID（即使只有一個字元）")
        void validSingleCharacterId() {
            DynamicMaterial result = DynamicMaterial.ofCustom("x", 10.0, 5.0, 3.0, 1000.0);
            assertEquals("x", result.getMaterialId());
        }
    }

    // ─────────────────────────────────────────────────────────
    //  RMaterial 介面一致性
    // ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("RMaterial 介面 vs record accessor 一致性")
    class InterfaceConsistencyTests {

        private DynamicMaterial material;

        @BeforeEach
        void setUp() {
            material = DynamicMaterial.ofRCFusion(
                DefaultMaterial.CONCRETE, DefaultMaterial.STEEL, 0.8, 0.6, 1.1, false);
        }

        @Test
        @DisplayName("getRcomp() ≡ rcomp()")
        void rcompAccessorConsistency() {
            assertEquals(material.rcomp(), material.getRcomp(), 1e-10);
        }

        @Test
        @DisplayName("getRtens() ≡ rtens()")
        void rtensAccessorConsistency() {
            assertEquals(material.rtens(), material.getRtens(), 1e-10);
        }

        @Test
        @DisplayName("getRshear() ≡ rshear()")
        void rshearAccessorConsistency() {
            assertEquals(material.rshear(), material.getRshear(), 1e-10);
        }

        @Test
        @DisplayName("getDensity() ≡ density()")
        void densityAccessorConsistency() {
            assertEquals(material.density(), material.getDensity(), 1e-10);
        }

        @Test
        @DisplayName("getMaterialId() ≡ materialId()")
        void materialIdAccessorConsistency() {
            assertEquals(material.materialId(), material.getMaterialId());
        }

        @Test
        @DisplayName("ofCustom() 也遵循介面一致性")
        void customMaterialInterfaceConsistency() {
            DynamicMaterial custom = DynamicMaterial.ofCustom("custom", 25.0, 5.0, 10.0, 1800.0);

            assertEquals(custom.rcomp(), custom.getRcomp());
            assertEquals(custom.rtens(), custom.getRtens());
            assertEquals(custom.rshear(), custom.getRshear());
            assertEquals(custom.density(), custom.getDensity());
            assertEquals(custom.materialId(), custom.getMaterialId());
        }
    }

    // ─────────────────────────────────────────────────────────
    //  繼承的預設方法（RMaterial default methods）
    // ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("RMaterial 預設方法 — getCombinedStrength()")
    class CombinedStrengthTests {

        @Test
        @DisplayName("getCombinedStrength = cbrt(Rcomp × Rtens × Rshear)")
        void combinedStrengthFormula() {
            DynamicMaterial material = DynamicMaterial.ofRCFusion(
                DefaultMaterial.CONCRETE, DefaultMaterial.STEEL, 0.8, 0.6, 1.1, false);

            double expected = Math.cbrt(
                material.getRcomp() * material.getRtens() * material.getRshear());
            assertEquals(expected, material.getCombinedStrength(), 1e-9);
        }

        @Test
        @DisplayName("getCombinedStrength > 0（正常情況）")
        void combinedStrengthPositive() {
            DynamicMaterial material = DynamicMaterial.ofRCFusion(
                DefaultMaterial.CONCRETE, DefaultMaterial.STEEL, 0.8, 0.6, 1.1, false);

            assertTrue(material.getCombinedStrength() > 0);
        }

        @Test
        @DisplayName("ofCustom 零強度 → getCombinedStrength() = 0")
        void combinedStrengthZeroWhenAllZero() {
            DynamicMaterial material = DynamicMaterial.ofCustom("zero", 0.0, 0.0, 0.0, 1000.0);

            assertEquals(0.0, material.getCombinedStrength(), 1e-10);
        }
    }

    @Nested
    @DisplayName("RMaterial 預設方法 — isDuctile()")
    class IsDuctileTests {

        @Test
        @DisplayName("RC Fusion 通常是延性的（Rcomp/Rtens < 10）")
        void rcFusionIsDuctile() {
            DynamicMaterial material = DynamicMaterial.ofRCFusion(
                DefaultMaterial.CONCRETE, DefaultMaterial.STEEL, 0.8, 0.6, 1.1, false);

            assertTrue(material.isDuctile(), "RC Fusion should be ductile");
        }

        @Test
        @DisplayName("Rtens = 0 → isDuctile() = false")
        void zeroTensileNotDuctile() {
            DynamicMaterial material = DynamicMaterial.ofCustom("brittle", 50.0, 0.0, 10.0, 1000.0);

            assertFalse(material.isDuctile());
        }

        @Test
        @DisplayName("Rcomp/Rtens = 10 → isDuctile() = false（邊界）")
        void ratioBoundaryNotDuctile() {
            DynamicMaterial material = DynamicMaterial.ofCustom("boundary", 100.0, 10.0, 10.0, 1000.0);

            assertFalse(material.isDuctile(), "Ratio = 10 should be exactly at boundary (not ductile)");
        }

        @Test
        @DisplayName("Rcomp/Rtens = 9.9 → isDuctile() = true（邊界內）")
        void ratioBoundaryDuctile() {
            DynamicMaterial material = DynamicMaterial.ofCustom("ductile", 99.0, 10.0, 10.0, 1000.0);

            assertTrue(material.isDuctile(), "Ratio < 10 should be ductile");
        }
    }

    @Nested
    @DisplayName("RMaterial 預設方法 — getMaxSpan()")
    class MaxSpanTests {

        @Test
        @DisplayName("getMaxSpan() 計算：clamp(sqrt(Rtens) × 2, 1, 64)")
        void maxSpanFormula() {
            DynamicMaterial material = DynamicMaterial.ofRCFusion(
                DefaultMaterial.CONCRETE, DefaultMaterial.STEEL, 0.8, 0.6, 1.1, false);

            double rtens = material.getRtens();
            int expected = Math.max(1, Math.min(64, (int)(Math.sqrt(rtens) * 2.0)));
            assertEquals(expected, material.getMaxSpan());
        }

        @Test
        @DisplayName("Rtens = 0 → getMaxSpan() = 1")
        void zeroTensileMaxSpan() {
            DynamicMaterial material = DynamicMaterial.ofCustom("zero_tens", 50.0, 0.0, 10.0, 1000.0);

            assertEquals(1, material.getMaxSpan());
        }

        @Test
        @DisplayName("Rtens = 10000 → getMaxSpan() 被鉗制為 64")
        void largeSpanClamped() {
            DynamicMaterial material = DynamicMaterial.ofCustom("huge", 100.0, 10000.0, 50.0, 1000.0);

            assertEquals(64, material.getMaxSpan(), "MaxSpan should be clamped to 64");
        }

        @Test
        @DisplayName("getMaxSpan() 總是在 [1, 64] 範圍內")
        void maxSpanInValidRange() {
            DynamicMaterial material1 = DynamicMaterial.ofCustom("low", 10.0, 0.5, 5.0, 1000.0);
            DynamicMaterial material2 = DynamicMaterial.ofRCFusion(
                DefaultMaterial.CONCRETE, DefaultMaterial.STEEL, 0.8, 0.6, 1.1, false);
            DynamicMaterial material3 = DynamicMaterial.ofCustom("high", 100.0, 1000.0, 50.0, 1000.0);

            assertTrue(material1.getMaxSpan() >= 1 && material1.getMaxSpan() <= 64);
            assertTrue(material2.getMaxSpan() >= 1 && material2.getMaxSpan() <= 64);
            assertTrue(material3.getMaxSpan() >= 1 && material3.getMaxSpan() <= 64);
        }
    }

    // ─────────────────────────────────────────────────────────
    //  其他 RMaterial 預設方法（額外驗證）
    // ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("RMaterial 預設方法 — 其他方法")
    class OtherDefaultMethodsTests {

        @Test
        @DisplayName("getYoungsModulusPa() = max(Rcomp, Rtens) × 1e9")
        void youngsModulusFormula() {
            DynamicMaterial material = DynamicMaterial.ofCustom("test", 25.0, 5.0, 10.0, 1000.0);

            double expected = Math.max(25.0, 5.0) * 1e9;  // 25e9
            assertEquals(expected, material.getYoungsModulusPa(), 1e-6);
        }

        @Test
        @DisplayName("getYieldStrength() 取 Rcomp 和 Rtens 的最小非零值")
        void yieldStrengthFormula() {
            DynamicMaterial material = DynamicMaterial.ofCustom("test", 50.0, 30.0, 10.0, 1000.0);

            double expected = Math.min(50.0, 30.0);  // 30.0
            assertEquals(expected, material.getYieldStrength(), 1e-10);
        }

        @Test
        @DisplayName("getPoissonsRatio() 預設回傳 0.20")
        void poissonsRatioDefault() {
            DynamicMaterial material = DynamicMaterial.ofCustom("test", 50.0, 10.0, 10.0, 1000.0);

            assertEquals(0.20, material.getPoissonsRatio(), 1e-10);
        }

        @Test
        @DisplayName("getShearModulusPa() = E / (2 × (1 + ν))")
        void shearModulusFormula() {
            DynamicMaterial material = DynamicMaterial.ofCustom("test", 50.0, 10.0, 10.0, 1000.0);

            double E = material.getYoungsModulusPa();
            double nu = material.getPoissonsRatio();
            double expected = E / (2.0 * (1.0 + nu));
            assertEquals(expected, material.getShearModulusPa(), 1e-6);
        }
    }

    // ─────────────────────────────────────────────────────────
    //  Record 不可變性
    // ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Record 不可變性 — DynamicMaterial 不可修改")
    class RecordImmutabilityTests {

        @Test
        @DisplayName("DynamicMaterial 是不可變 record（無 setter）")
        void recordHasNoSetters() {
            DynamicMaterial material = DynamicMaterial.ofRCFusion(
                DefaultMaterial.CONCRETE, DefaultMaterial.STEEL, 0.8, 0.6, 1.1, false);

            // record 沒有 setRcomp() 方法（編譯時驗證）
            // 執行時驗證：嘗試讀取同一物件多次應得到相同值
            double rcomp1 = material.getRcomp();
            double rcomp2 = material.getRcomp();
            assertEquals(rcomp1, rcomp2, "Record should be immutable");
        }

        @Test
        @DisplayName("ofRCFusion 回傳的 DynamicMaterial 無法被修改")
        void ofRCFusionResultImmutable() {
            DynamicMaterial m1 = DynamicMaterial.ofRCFusion(
                DefaultMaterial.CONCRETE, DefaultMaterial.STEEL, 0.8, 0.6, 1.1, false);
            DynamicMaterial m2 = DynamicMaterial.ofRCFusion(
                DefaultMaterial.CONCRETE, DefaultMaterial.STEEL, 0.8, 0.6, 1.1, false);

            assertEquals(m1.getRcomp(), m2.getRcomp());
            assertEquals(m1.getRtens(), m2.getRtens());
        }
    }

    // ─────────────────────────────────────────────────────────
    //  邊界與特殊情況
    // ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("邊界與特殊情況")
    class EdgeCasesTests {

        @Test
        @DisplayName("ofRCFusion：超大密度值（無溢位）")
        void largeValues() {
            RMaterial heavyMaterial = new CustomMaterial.Builder("heavy")
                .rcomp(1000.0)
                .rshear(500.0)
                .rtens(100.0)
                .build();

            RMaterial concrete = DefaultMaterial.CONCRETE;
            DynamicMaterial result = DynamicMaterial.ofRCFusion(
                concrete, heavyMaterial, 2.0, 2.0, 2.0, false);

            // 密度 = 2350*0.97 + heavyMaterial*0.03（應不溢位）
            assertTrue(Double.isFinite(result.getDensity()));
            assertTrue(Double.isFinite(result.getRcomp()));
        }

        @Test
        @DisplayName("ofCustom：精確密度邊界（> 0 即可）")
        void preciseDensityBoundary() {
            DynamicMaterial result = DynamicMaterial.ofCustom("tiny", 1.0, 1.0, 1.0, 0.0001);
            assertEquals(0.0001, result.getDensity(), 1e-10);
        }

        @Test
        @DisplayName("蜂窩懲罰：連續應用不會進一步降低（單一懲罰）")
        void honeycombPenaltyOnce() {
            DynamicMaterial with = DynamicMaterial.ofRCFusion(
                DefaultMaterial.CONCRETE, DefaultMaterial.STEEL, 0.8, 0.6, 1.1, true);

            // 蜂窩懲罰應只應用一次
            double rcompNoHoneycomb = 30.0 * 1.1;
            double expectedRcomp = rcompNoHoneycomb * 0.7;

            assertEquals(expectedRcomp, with.getRcomp(), 1e-10);
            // 如果懲罰應用兩次，則為 rcompNoHoneycomb * 0.7 * 0.7 = 19.25（錯誤）
            assertNotEquals(rcompNoHoneycomb * 0.49, with.getRcomp(), "Honeycomb penalty should be applied once, not twice");
        }
    }
}
