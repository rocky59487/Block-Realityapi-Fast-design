package com.blockreality.api.physics;

import com.blockreality.api.material.DefaultMaterial;
import com.blockreality.api.material.RMaterial;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for BeamElement — pure structural mechanics without Minecraft dependencies.
 *
 * Tests cover:
 *   - Basic creation and property validation
 *   - Von Mises utilization formula (√(σ²+τ²), not linear sum)
 *   - Pure axial, pure bending, and combined loading
 *   - Shear dominance scenarios
 *   - Crushing vs. buckling capacity selection
 *   - Moment capacity calculation
 *   - Composite stiffness (harmonic mean)
 *   - Strain energy formula
 *   - Safety factor reciprocal relationship
 */
@DisplayName("BeamElement — Structural Mechanics Tests")
class BeamElementTest {

    // ═══════════════════════════════════════════════════════════════════════════════
    // Test Fixtures: Common materials and positions
    // ═══════════════════════════════════════════════════════════════════════════════

    private static final BlockPos NODE_A = new BlockPos(0, 0, 0);
    private static final BlockPos NODE_B = new BlockPos(1, 0, 0);

    // ═══════════════════════════════════════════════════════════════════════════════
    // 1. Basic Creation and Property Validation
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("create() produces valid BeamElement with correct structure")
    void testCreateProducesValidBeam() {
        BeamElement beam = BeamElement.create(NODE_A, NODE_B,
            DefaultMaterial.CONCRETE, DefaultMaterial.CONCRETE);

        assertNotNull(beam, "BeamElement.create should not return null");
        assertEquals(NODE_A, beam.nodeA(), "Node A position should match");
        assertEquals(NODE_B, beam.nodeB(), "Node B position should match");
        assertNotNull(beam.material(), "Material should not be null");
    }

    @Test
    @DisplayName("Properties match unit constants: I=1/12, A=1.0, L=1.0")
    void testPropertiesUseUnitConstants() {
        BeamElement beam = BeamElement.create(NODE_A, NODE_B,
            DefaultMaterial.STONE, DefaultMaterial.STONE);

        assertEquals(BeamElement.UNIT_MOMENT_OF_INERTIA, beam.momentOfI(), 1e-10,
            "Moment of inertia should be 1/12");
        assertEquals(BeamElement.UNIT_AREA, beam.area(), 1e-10,
            "Cross-section area should be 1.0 m²");
        assertEquals(BeamElement.UNIT_LENGTH, beam.length(), 1e-10,
            "Beam length should be 1.0 m");
    }

    @Test
    @DisplayName("Weaker material dominates (wooden/concrete composite)")
    void testWeakerMaterialDominatesInComposite() {
        BeamElement steelWood = BeamElement.create(NODE_A, NODE_B,
            DefaultMaterial.STEEL, DefaultMaterial.TIMBER);

        // The composite should select the weaker material (TIMBER)
        RMaterial selected = steelWood.material();

        // TIMBER has combined strength ~14.8, STEEL has ~334
        // The weaker should be selected
        assertTrue(selected.getCombinedStrength() <= DefaultMaterial.STEEL.getCombinedStrength(),
            "Composite should select weaker material");
    }

    @Test
    @DisplayName("elasticMod uses composite stiffness formula (harmonic mean)")
    void testCompositeElasticModulus() {
        // E = 2·E₁·E₂ / (E₁ + E₂)
        double E_steel = DefaultMaterial.STEEL.getYoungsModulusPa();   // Pa
        double E_wood = DefaultMaterial.TIMBER.getYoungsModulusPa();
        double expectedE = 2.0 * E_steel * E_wood / (E_steel + E_wood);

        BeamElement beam = BeamElement.create(NODE_A, NODE_B,
            DefaultMaterial.STEEL, DefaultMaterial.TIMBER);

        assertEquals(expectedE, beam.elasticMod(), expectedE * 0.01,
            "Elastic modulus should use harmonic mean formula");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 2. Von Mises Utilization: √(σ²+τ²) not σ+τ
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Eurocode linear sum: axialRatio + momentRatio = 0.3 + 0.4 = 0.7")
    void testLinearInteractionFormula() {
        BeamElement beam = BeamElement.create(NODE_A, NODE_B,
            DefaultMaterial.CONCRETE, DefaultMaterial.CONCRETE);

        double maxAxial = beam.maxAxialForce();
        double maxMoment = beam.maxBendingMoment();

        // Apply loads scaled such that ratios are 0.3 and 0.4
        double axialForce = 0.3 * maxAxial;
        double moment = 0.4 * maxMoment;
        double shear = 0;

        double util = beam.utilizationRatio(axialForce, moment, shear);

        // Eurocode EN 1993-1-1 §6.2.1: N/N_max + M/M_max (linear interaction for co-axial stresses)
        // 0.3 + 0.4 = 0.7  (Von Mises √(0.3²+0.4²)=0.5 is incorrect for same-axis normal stresses)
        assertEquals(0.7, util, 0.01,
            "Eurocode linear interaction: axialRatio(0.3) + momentRatio(0.4) = 0.7");
    }

    @Test
    @DisplayName("Combined load equals linear sum (Eurocode: N/N_max + M/M_max)")
    void testCombinedLoadEqualsLinearSum() {
        BeamElement beam = BeamElement.create(NODE_A, NODE_B,
            DefaultMaterial.STEEL, DefaultMaterial.STEEL);

        double maxAxial = beam.maxAxialForce();
        double maxMoment = beam.maxBendingMoment();

        double axialRatio = 0.6;
        double momentRatio = 0.8;

        double axialForce = axialRatio * maxAxial;
        double moment = momentRatio * maxMoment;
        double shear = 0;

        double util = beam.utilizationRatio(axialForce, moment, shear);
        double expectedLinearSum = axialRatio + momentRatio; // 0.6 + 0.8 = 1.4

        // Eurocode linear interaction: util == axialRatio + momentRatio
        assertEquals(expectedLinearSum, util, 0.01,
            "Eurocode combined (" + util + ") should equal linear sum (" + expectedLinearSum + ")");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 3. Pure Axial Loading
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Pure axial: utilization = |F| / maxAxialForce")
    void testPureAxialUtilization() {
        BeamElement beam = BeamElement.create(NODE_A, NODE_B,
            DefaultMaterial.CONCRETE, DefaultMaterial.CONCRETE);

        double maxAxial = beam.maxAxialForce();
        double testForce = 0.5 * maxAxial;

        double util = beam.utilizationRatio(testForce, 0, 0);

        assertEquals(0.5, util, 0.01,
            "Pure axial utilization should be F / maxAxialForce");
    }

    @Test
    @DisplayName("Utilization is zero for zero load")
    void testZeroLoadGivesZeroUtilization() {
        BeamElement beam = BeamElement.create(NODE_A, NODE_B,
            DefaultMaterial.STONE, DefaultMaterial.STONE);

        double util = beam.utilizationRatio(0, 0, 0);

        assertEquals(0.0, util, 1e-10,
            "Utilization should be 0 for zero load");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 4. Pure Bending
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Pure bending: utilization = |M| / maxBendingMoment")
    void testPureBendingUtilization() {
        BeamElement beam = BeamElement.create(NODE_A, NODE_B,
            DefaultMaterial.TIMBER, DefaultMaterial.TIMBER);

        double maxMoment = beam.maxBendingMoment();
        double testMoment = 0.6 * maxMoment;

        double util = beam.utilizationRatio(0, testMoment, 0);

        assertEquals(0.6, util, 0.01,
            "Pure bending utilization should be M / maxBendingMoment");
    }

    @Test
    @DisplayName("maxBendingMoment = Rtens × 1e6 × I / yMax (yMax=0.5)")
    void testMaxBendingMomentFormula() {
        BeamElement beam = BeamElement.create(NODE_A, NODE_B,
            DefaultMaterial.CONCRETE, DefaultMaterial.CONCRETE);

        double rtens = DefaultMaterial.CONCRETE.getRtens();  // MPa
        double I = BeamElement.UNIT_MOMENT_OF_INERTIA;
        double yMax = 0.5;

        double expected = rtens * 1e6 * I / yMax;
        double actual = beam.maxBendingMoment();

        assertEquals(expected, actual, expected * 0.01,
            "maxBendingMoment should equal Rtens × 1e6 × I / yMax");
    }

    // For CONCRETE: Rtens=3.0 MPa, I=1/12, yMax=0.5
    // M_max = 3.0×1e6 × (1/12) / 0.5 = 500,000 N·m
    @Test
    @DisplayName("CONCRETE moment capacity = 500,000 N·m")
    void testConcreteMaxBendingMomentNumericalValue() {
        BeamElement beam = BeamElement.create(NODE_A, NODE_B,
            DefaultMaterial.CONCRETE, DefaultMaterial.CONCRETE);

        double maxMoment = beam.maxBendingMoment();
        double expected = 500_000.0;  // 3.0 × 1e6 × (1/12) / 0.5

        assertEquals(expected, maxMoment, expected * 0.01,
            "CONCRETE with Rtens=3.0 should have M_max = 500,000 N·m");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 5. Shear Dominance
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("When shear ratio > combined normal, utilization dominated by shear")
    void testShearDominance() {
        BeamElement beam = BeamElement.create(NODE_A, NODE_B,
            DefaultMaterial.STEEL, DefaultMaterial.STEEL);

        double maxAxial = beam.maxAxialForce();
        double maxMoment = beam.maxBendingMoment();
        double maxShear = beam.maxShearForce();

        // Apply small normal loads, large shear
        double axialForce = 0.2 * maxAxial;
        double moment = 0.1 * maxMoment;
        double shear = 0.8 * maxShear;

        double utilWithShear = beam.utilizationRatio(axialForce, moment, shear);
        double utilNoShear = beam.utilizationRatio(axialForce, moment, 0);

        // Utilization should jump due to shear dominance
        assertTrue(utilWithShear > utilNoShear,
            "Utilization should increase when shear dominates");
        assertTrue(utilWithShear >= 0.8,
            "Utilization should reflect shear ratio when shear dominates");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 6. maxAxialForce = min(crushLoad, eulerBuckling)
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("maxAxialForce formula: min(Rcomp×A, π²EI/L²)")
    void testMaxAxialForceFormula() {
        BeamElement beam = BeamElement.create(NODE_A, NODE_B,
            DefaultMaterial.CONCRETE, DefaultMaterial.CONCRETE);

        double rcomp = DefaultMaterial.CONCRETE.getRcomp();
        double crushLoad = rcomp * 1e6 * BeamElement.UNIT_AREA;

        double eulerLoad = beam.eulerBucklingLoad();

        double expectedMaxAxial = Math.min(crushLoad, eulerLoad);
        double actual = beam.maxAxialForce();

        assertEquals(expectedMaxAxial, actual, expectedMaxAxial * 0.01,
            "maxAxialForce should be min(crushLoad, eulerBucklingLoad)");
    }

    @Test
    @DisplayName("For short CONCRETE beam, crushing dominates buckling")
    void testCrushingDominatesForShortConcrete() {
        // CONCRETE: 1m length, short and stout
        // Crushing capacity: 30 MPa × 1 m² = 30,000,000 N = 30 MN
        // Buckling: π² × E × I / L² (E is very large in Pa, so buckling >> crushing)

        BeamElement beam = BeamElement.create(NODE_A, NODE_B,
            DefaultMaterial.CONCRETE, DefaultMaterial.CONCRETE);

        double crushLoad = DefaultMaterial.CONCRETE.getRcomp() * 1e6 * BeamElement.UNIT_AREA;
        double eulerLoad = beam.eulerBucklingLoad();

        // For a 1m concrete beam, buckling load should be much larger
        assertTrue(eulerLoad > crushLoad,
            "Euler buckling load should exceed crushing load for 1m concrete");

        // maxAxialForce should equal crushLoad
        assertEquals(crushLoad, beam.maxAxialForce(), crushLoad * 0.01,
            "For short concrete, crushing should dominate (maxAxial = crushLoad)");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 7. Composite Stiffness (Static Method)
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("compositeStiffness() uses harmonic mean: 2×E₁×E₂/(E₁+E₂)")
    void testCompositeStiffnessStaticMethod() {
        double E1 = DefaultMaterial.STONE.getYoungsModulusPa();
        double E2 = DefaultMaterial.TIMBER.getYoungsModulusPa();
        double expected = 2.0 * E1 * E2 / (E1 + E2);

        double actual = BeamElement.compositeStiffness(
            DefaultMaterial.STONE, DefaultMaterial.TIMBER);

        assertEquals(expected, actual, expected * 0.01,
            "compositeStiffness should use harmonic mean formula");
    }

    @Test
    @DisplayName("Composite stiffness is bounded: min < composite < max")
    void testCompositeStiffnessBounded() {
        double E1 = DefaultMaterial.STEEL.getYoungsModulusPa();
        double E2 = DefaultMaterial.TIMBER.getYoungsModulusPa();

        double composite = BeamElement.compositeStiffness(
            DefaultMaterial.STEEL, DefaultMaterial.TIMBER);

        assertTrue(composite > Math.min(E1, E2),
            "Composite should exceed the weaker material");
        assertTrue(composite < Math.max(E1, E2),
            "Composite should not exceed the stronger material");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 8. Strain Energy: U = 0.5 × F² / (E×A) × L
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Strain energy formula: U = 0.5 × F² / (EA) × L")
    void testStrainEnergyFormula() {
        BeamElement beam = BeamElement.create(NODE_A, NODE_B,
            DefaultMaterial.STEEL, DefaultMaterial.STEEL);

        double F = 1e6;  // 1 MN
        double expectedU = 0.5 * F * F / (beam.elasticMod() * beam.area()) * beam.length();

        double actualU = beam.strainEnergy(F);

        assertEquals(expectedU, actualU, expectedU * 0.01,
            "Strain energy should match 0.5×F²/(EA)×L");
    }

    @Test
    @DisplayName("Strain energy increases with force squared")
    void testStrainEnergyQuadratic() {
        BeamElement beam = BeamElement.create(NODE_A, NODE_B,
            DefaultMaterial.TIMBER, DefaultMaterial.TIMBER);

        double u1 = beam.strainEnergy(1.0);
        double u2 = beam.strainEnergy(2.0);
        double u3 = beam.strainEnergy(3.0);

        // U ∝ F², so U(2F) ≈ 4×U(F), U(3F) ≈ 9×U(F)
        assertEquals(u1 * 4, u2, u1 * 0.01,
            "Strain energy should scale as F²");
        assertEquals(u1 * 9, u3, u1 * 0.01,
            "Strain energy should scale as F²");
    }

    @Test
    @DisplayName("Strain energy is zero for zero load")
    void testStrainEnergyZeroForZeroLoad() {
        BeamElement beam = BeamElement.create(NODE_A, NODE_B,
            DefaultMaterial.CONCRETE, DefaultMaterial.CONCRETE);

        double u = beam.strainEnergy(0);

        assertEquals(0.0, u, 1e-10,
            "Strain energy should be zero for zero load");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 9. Safety Factor = 1 / utilizationRatio
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("safetyFactor = 1 / utilizationRatio")
    void testSafetyFactorReciprocal() {
        BeamElement beam = BeamElement.create(NODE_A, NODE_B,
            DefaultMaterial.STONE, DefaultMaterial.STONE);

        double maxAxial = beam.maxAxialForce();
        double force = 0.4 * maxAxial;

        double util = beam.utilizationRatio(force, 0, 0);
        double sf = beam.safetyFactor(force, 0, 0);

        assertEquals(1.0 / util, sf, sf * 0.01,
            "safetyFactor should equal 1 / utilizationRatio");
    }

    @Test
    @DisplayName("safetyFactor is POSITIVE_INFINITY for zero load")
    void testSafetyFactorInfinityForZeroLoad() {
        BeamElement beam = BeamElement.create(NODE_A, NODE_B,
            DefaultMaterial.TIMBER, DefaultMaterial.TIMBER);

        double sf = beam.safetyFactor(0, 0, 0);

        assertEquals(Double.POSITIVE_INFINITY, sf,
            "Safety factor should be Infinity for zero load");
    }

    @Test
    @DisplayName("safetyFactor > 1.0 indicates safe; < 1.0 indicates overload")
    void testSafetyFactorInterpretation() {
        BeamElement beam = BeamElement.create(NODE_A, NODE_B,
            DefaultMaterial.CONCRETE, DefaultMaterial.CONCRETE);

        double maxAxial = beam.maxAxialForce();

        // Half of capacity → SF = 2.0 (can load 2x more)
        double sf50 = beam.safetyFactor(0.5 * maxAxial, 0, 0);
        assertTrue(sf50 > 1.0 && sf50 < 3.0, "SF for 50% load should be ~2.0");

        // 100% of capacity → SF = 1.0
        double sf100 = beam.safetyFactor(maxAxial, 0, 0);
        assertEquals(1.0, sf100, 0.01, "SF for 100% load should be 1.0");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 10. Stiffness Methods
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("axialStiffness = EA/L (positive)")
    void testAxialStiffnessPositive() {
        BeamElement beam = BeamElement.create(NODE_A, NODE_B,
            DefaultMaterial.STEEL, DefaultMaterial.STEEL);

        double expected = beam.elasticMod() * beam.area() / beam.length();
        double actual = beam.axialStiffness();

        assertEquals(expected, actual, expected * 0.01,
            "Axial stiffness should be EA/L");
        assertTrue(actual > 0, "Axial stiffness must be positive");
    }

    @Test
    @DisplayName("bendingStiffness = EI/L³ (positive)")
    void testBendingStiffnessPositive() {
        BeamElement beam = BeamElement.create(NODE_A, NODE_B,
            DefaultMaterial.TIMBER, DefaultMaterial.TIMBER);

        double expected = beam.elasticMod() * beam.momentOfI()
            / (beam.length() * beam.length() * beam.length());
        double actual = beam.bendingStiffness();

        assertEquals(expected, actual, expected * 0.01,
            "Bending stiffness should be EI/L³");
        assertTrue(actual > 0, "Bending stiffness must be positive");
    }

    @Test
    @DisplayName("eulerBucklingLoad = π² × E × I / L²")
    void testEulerBucklingLoadFormula() {
        BeamElement beam = BeamElement.create(NODE_A, NODE_B,
            DefaultMaterial.CONCRETE, DefaultMaterial.CONCRETE);

        double effectiveLength = BeamElement.EFFECTIVE_LENGTH_FACTOR_K * beam.length();
        double expected = Math.PI * Math.PI * beam.elasticMod()
            * beam.momentOfI() / (effectiveLength * effectiveLength);
        double actual = beam.eulerBucklingLoad();

        assertEquals(expected, actual, expected * 0.01,
            "Euler buckling load should use π²EI/L²");
        assertTrue(actual > 0, "Euler buckling load must be positive");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 11. Negative Forces (Tension)
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Utilization uses absolute value of force")
    void testUtilizationUsesAbsoluteValue() {
        BeamElement beam = BeamElement.create(NODE_A, NODE_B,
            DefaultMaterial.STONE, DefaultMaterial.STONE);

        double maxAxial = beam.maxAxialForce();

        double utilPos = beam.utilizationRatio(0.5 * maxAxial, 0, 0);
        double utilNeg = beam.utilizationRatio(-0.5 * maxAxial, 0, 0);

        assertEquals(utilPos, utilNeg, 0.01,
            "Utilization should be same for +F and -F (absolute value)");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 12. Edge Cases and Robustness
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Very small forces give very small utilization")
    void testTinyForcesTinyUtilization() {
        BeamElement beam = BeamElement.create(NODE_A, NODE_B,
            DefaultMaterial.STEEL, DefaultMaterial.STEEL);

        double util = beam.utilizationRatio(1.0, 1.0, 1.0);  // 1 N each

        assertTrue(util < 0.01, "Tiny forces should give tiny utilization");
    }

    @Test
    @DisplayName("Multiple material combinations produce valid results")
    void testVariousMaterialCombos() {
        RMaterial[][] pairs = {
            {DefaultMaterial.STONE, DefaultMaterial.CONCRETE},
            {DefaultMaterial.TIMBER, DefaultMaterial.BRICK},
            {DefaultMaterial.STEEL, DefaultMaterial.OBSIDIAN}
        };

        for (RMaterial[] pair : pairs) {
            BeamElement beam = BeamElement.create(NODE_A, NODE_B, pair[0], pair[1]);

            assertNotNull(beam.material(), "Material should not be null");
            assertTrue(beam.maxAxialForce() > 0, "maxAxialForce must be positive");
            assertTrue(beam.maxBendingMoment() > 0, "maxBendingMoment must be positive");
            assertTrue(beam.axialStiffness() > 0, "axialStiffness must be positive");
        }
    }
}
