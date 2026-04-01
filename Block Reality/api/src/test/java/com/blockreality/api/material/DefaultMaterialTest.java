package com.blockreality.api.material;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DefaultMaterial 單元測試 — 驗證所有 enum 值的工程數據合理性。
 *
 * 測試策略：
 *   1. fromId() O(1) 查找正確性 + fallback
 *   2. 所有 enum 的數值範圍合理性（不為負、密度有界）
 *   3. 真實楊氏模量 vs 近似公式的偏差
 *   4. BEDROCK 的 1e15 不溢位驗證
 *   5. RMaterial default 方法與 override 一致性
 */
@DisplayName("DefaultMaterial — 預設材料 enum 測試")
class DefaultMaterialTest {

    // ═══════════════════════════════════════════════════════
    //  fromId 查找
    // ═══════════════════════════════════════════════════════

    @Nested
    @DisplayName("fromId() O(1) 查找")
    class FromIdTests {

        @ParameterizedTest
        @EnumSource(DefaultMaterial.class)
        @DisplayName("所有 enum 值都能通過 materialId 找回自己")
        void allMaterialsRoundTrip(DefaultMaterial material) {
            assertSame(material, DefaultMaterial.fromId(material.getMaterialId()));
        }

        @Test
        @DisplayName("未知 ID 回傳 CONCRETE（fallback）")
        void unknownIdFallback() {
            assertSame(DefaultMaterial.CONCRETE, DefaultMaterial.fromId("nonexistent_material"));
        }

        @Test
        @DisplayName("null ID 不拋例外（回傳 CONCRETE）")
        void nullIdFallback() {
            assertSame(DefaultMaterial.CONCRETE, DefaultMaterial.fromId(null));
        }

        @Test
        @DisplayName("空字串 ID 回傳 CONCRETE")
        void emptyIdFallback() {
            assertSame(DefaultMaterial.CONCRETE, DefaultMaterial.fromId(""));
        }
    }

    // ═══════════════════════════════════════════════════════
    //  ★ M8-fix: findById() Optional 查找
    // ═══════════════════════════════════════════════════════

    @Nested
    @DisplayName("findById() Optional 查找")
    class FindByIdTests {

        @ParameterizedTest
        @EnumSource(DefaultMaterial.class)
        @DisplayName("所有已知材料回傳 non-empty Optional")
        void knownIdReturnsPresent(DefaultMaterial material) {
            Optional<DefaultMaterial> result = DefaultMaterial.findById(material.getMaterialId());
            assertTrue(result.isPresent(), "findById 應找到已知材料 " + material.getMaterialId());
            assertSame(material, result.get());
        }

        @Test
        @DisplayName("未知 ID 回傳 empty Optional（不 fallback 到 CONCRETE）")
        void unknownIdReturnsEmpty() {
            Optional<DefaultMaterial> result = DefaultMaterial.findById("nonexistent_xyz");
            assertFalse(result.isPresent(), "未知 ID 應回傳 empty Optional，而非靜默 fallback");
        }

        @Test
        @DisplayName("null ID 回傳 empty Optional（不拋例外）")
        void nullIdReturnsEmpty() {
            Optional<DefaultMaterial> result = DefaultMaterial.findById(null);
            assertFalse(result.isPresent(), "null ID 應回傳 empty Optional");
        }

        @Test
        @DisplayName("空字串 ID 回傳 empty Optional")
        void emptyIdReturnsEmpty() {
            Optional<DefaultMaterial> result = DefaultMaterial.findById("");
            assertFalse(result.isPresent(), "空字串 ID 應回傳 empty Optional");
        }

        @Test
        @DisplayName("findById orElseThrow 可顯式處理未知材料")
        void orElseThrowUsage() {
            assertThrows(RuntimeException.class, () ->
                DefaultMaterial.findById("bad_id").orElseThrow(() -> new RuntimeException("未知材料"))
            );
        }
    }

    // ═══════════════════════════════════════════════════════
    //  數值合理性
    // ═══════════════════════════════════════════════════════

    @Nested
    @DisplayName("數值合理性驗證")
    class ValueSanityTests {

        @ParameterizedTest
        @EnumSource(DefaultMaterial.class)
        @DisplayName("抗壓強度 ≥ 0")
        void rcompNonNegative(DefaultMaterial m) {
            assertTrue(m.getRcomp() >= 0, m.name() + " Rcomp should be >= 0");
        }

        @ParameterizedTest
        @EnumSource(DefaultMaterial.class)
        @DisplayName("抗拉強度 ≥ 0")
        void rtensNonNegative(DefaultMaterial m) {
            assertTrue(m.getRtens() >= 0, m.name() + " Rtens should be >= 0");
        }

        @ParameterizedTest
        @EnumSource(DefaultMaterial.class)
        @DisplayName("抗剪強度 ≥ 0")
        void rshearNonNegative(DefaultMaterial m) {
            assertTrue(m.getRshear() >= 0, m.name() + " Rshear should be >= 0");
        }

        @ParameterizedTest
        @EnumSource(DefaultMaterial.class)
        @DisplayName("密度在合理範圍 (0, 10000] kg/m³")
        void densityInRange(DefaultMaterial m) {
            assertTrue(m.getDensity() > 0 && m.getDensity() <= 10000,
                m.name() + " density=" + m.getDensity());
        }

        @ParameterizedTest
        @EnumSource(DefaultMaterial.class)
        @DisplayName("materialId 非空且非 null")
        void materialIdNotEmpty(DefaultMaterial m) {
            assertNotNull(m.getMaterialId());
            assertFalse(m.getMaterialId().isEmpty(), m.name() + " should have non-empty ID");
        }
    }

    // ═══════════════════════════════════════════════════════
    //  楊氏模量真實值驗證
    // ═══════════════════════════════════════════════════════

    @Nested
    @DisplayName("楊氏模量 — 真實工程值")
    class ElasticModulusTests {

        @Test
        @DisplayName("STEEL E = 200 GPa（AISC / Eurocode 3）")
        void steelElasticModulus() {
            assertEquals(200e9, DefaultMaterial.STEEL.getYoungsModulusPa(), 1e9,
                "Steel E should be ~200 GPa");
        }

        @Test
        @DisplayName("CONCRETE E = 30 GPa（Eurocode 2, C30）")
        void concreteElasticModulus() {
            assertEquals(30e9, DefaultMaterial.CONCRETE.getYoungsModulusPa(), 1e9,
                "Concrete E should be ~30 GPa");
        }

        @Test
        @DisplayName("TIMBER E = 11 GPa（softwood, EN 338）")
        void timberElasticModulus() {
            assertEquals(11e9, DefaultMaterial.TIMBER.getYoungsModulusPa(), 1e9,
                "Timber E should be ~11 GPa");
        }

        @Test
        @DisplayName("GLASS E = 70 GPa（soda-lime glass）")
        void glassElasticModulus() {
            assertEquals(70e9, DefaultMaterial.GLASS.getYoungsModulusPa(), 1e9,
                "Glass E should be ~70 GPa");
        }

        @ParameterizedTest
        @EnumSource(DefaultMaterial.class)
        @DisplayName("所有材料 E > 0")
        void allElasticModulusPositive(DefaultMaterial m) {
            assertTrue(m.getYoungsModulusPa() > 0, m.name() + " E should be > 0");
        }
    }

    // ═══════════════════════════════════════════════════════
    //  泊松比驗證
    // ═══════════════════════════════════════════════════════

    @Nested
    @DisplayName("泊松比 — 範圍與工程值")
    class PoissonsRatioTests {

        @ParameterizedTest
        @EnumSource(DefaultMaterial.class)
        @DisplayName("泊松比在 [0, 0.5) 範圍內")
        void poissonsRatioInRange(DefaultMaterial m) {
            double nu = m.getPoissonsRatio();
            assertTrue(nu >= 0 && nu < 0.5,
                m.name() + " Poisson's ratio=" + nu + " should be in [0, 0.5)");
        }

        @Test
        @DisplayName("STEEL ν ≈ 0.29（AISC）")
        void steelPoissonsRatio() {
            assertEquals(0.29, DefaultMaterial.STEEL.getPoissonsRatio(), 0.02);
        }

        @Test
        @DisplayName("CONCRETE ν ≈ 0.20（Eurocode 2）")
        void concretePoissonsRatio() {
            assertEquals(0.20, DefaultMaterial.CONCRETE.getPoissonsRatio(), 0.05);
        }
    }

    // ═══════════════════════════════════════════════════════
    //  剪力模量推導
    // ═══════════════════════════════════════════════════════

    @Nested
    @DisplayName("剪力模量 G = E / (2(1+ν))")
    class ShearModulusTests {

        @Test
        @DisplayName("STEEL G ≈ 77.5 GPa")
        void steelShearModulus() {
            double G = DefaultMaterial.STEEL.getShearModulusPa();
            // G = 200e9 / (2 × 1.29) ≈ 77.5e9
            assertEquals(77.5e9, G, 2e9, "Steel G should be ~77.5 GPa");
        }

        @ParameterizedTest
        @EnumSource(DefaultMaterial.class)
        @DisplayName("G = E / (2(1+ν)) 恆等式成立")
        void shearModulusFormula(DefaultMaterial m) {
            double expected = m.getYoungsModulusPa() / (2.0 * (1.0 + m.getPoissonsRatio()));
            assertEquals(expected, m.getShearModulusPa(), 1e-6,
                m.name() + " shear modulus identity");
        }
    }

    // ═══════════════════════════════════════════════════════
    //  BEDROCK 特殊值驗證
    // ═══════════════════════════════════════════════════════

    @Nested
    @DisplayName("BEDROCK — 極值不溢位")
    class BedrockTests {

        @Test
        @DisplayName("Rcomp × area (1e15 MPa × 1m² = 1e21 N) 在 double 範圍內")
        void bedrockForceNotOverflow() {
            double force = DefaultMaterial.BEDROCK.getRcomp() * 1e6 * 1.0; // Pa × m² = N
            assertTrue(Double.isFinite(force), "BEDROCK compressive force should be finite");
            assertEquals(1e21, force, 1e15, "Should be ~1e21 N");
        }

        @Test
        @DisplayName("BEDROCK density × g 在合理範圍")
        void bedrockWeightReasonable() {
            double weight = DefaultMaterial.BEDROCK.getDensity() * 9.81;
            assertTrue(weight < 1e6, "BEDROCK weight should be << canSupport capacity");
        }

        @Test
        @DisplayName("getCombinedStrength() 不產生 NaN/Infinity")
        void bedrockCombinedStrengthFinite() {
            double cs = DefaultMaterial.BEDROCK.getCombinedStrength();
            assertTrue(Double.isFinite(cs), "BEDROCK combinedStrength should be finite");
        }

        @Test
        @DisplayName("BEDROCK isDuctile() = true（Rtens = Rcomp）")
        void bedrockIsDuctile() {
            // Rcomp/Rtens = 1 < 10 → ductile
            assertTrue(DefaultMaterial.BEDROCK.isDuctile());
        }
    }

    // ═══════════════════════════════════════════════════════
    //  RMaterial default 方法
    // ═══════════════════════════════════════════════════════

    @Nested
    @DisplayName("RMaterial default 方法一致性")
    class DefaultMethodTests {

        @Test
        @DisplayName("SAND isDuctile() = false（Rtens = 0）")
        void sandNotDuctile() {
            assertFalse(DefaultMaterial.SAND.isDuctile());
        }

        @Test
        @DisplayName("STEEL isDuctile() = true（Rcomp/Rtens = 0.7）")
        void steelIsDuctile() {
            assertTrue(DefaultMaterial.STEEL.isDuctile());
        }

        @Test
        @DisplayName("GLASS isDuctile() = false（Rcomp/Rtens = 200）")
        void glassNotDuctile() {
            assertFalse(DefaultMaterial.GLASS.isDuctile());
        }

        @Test
        @DisplayName("SAND maxSpan = 1（不能懸空）")
        void sandMaxSpan() {
            assertEquals(1, DefaultMaterial.SAND.getMaxSpan());
        }

        @Test
        @DisplayName("STEEL maxSpan > 10（鋼材長跨）")
        void steelMaxSpan() {
            assertTrue(DefaultMaterial.STEEL.getMaxSpan() > 10);
        }

        @ParameterizedTest
        @EnumSource(DefaultMaterial.class)
        @DisplayName("getMaxSpan() 在 [1, 64] 範圍內")
        void maxSpanInRange(DefaultMaterial m) {
            int span = m.getMaxSpan();
            assertTrue(span >= 1 && span <= 64, m.name() + " maxSpan=" + span);
        }
    }
}
