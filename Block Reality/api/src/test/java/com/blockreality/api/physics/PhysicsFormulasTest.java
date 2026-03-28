package com.blockreality.api.physics;

import com.blockreality.api.material.DefaultMaterial;
import com.blockreality.api.material.RMaterial;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure mathematical physics formulas — dimensional analysis and numerical verification.
 *
 * These tests verify structural mechanics calculations without Minecraft dependencies.
 * All units are SI: Pa (pascals), N (newtons), m (meters), kg (kilograms), J (joules).
 *
 * Tests cover:
 *   - Moment capacity: M = Rtens × 1e6 × (b×h²/6)
 *   - Crushing capacity: F = Rcomp × 1e6 × A
 *   - Load to force conversion: F = ρ × g × V
 *   - Von Mises stress: σ_vm = √(σ_axial² + σ_bending²)
 *   - Utilization ratio dimensionality
 *   - Distributed beam moments: q × L² / 8
 *   - Section modulus for unit square: W = 1/6
 */
@DisplayName("Physics Formulas — Pure Math Verification")
class PhysicsFormulasTest {

    // Constants from structural mechanics
    private static final double GRAVITY = 9.81;  // m/s²
    private static final double BLOCK_SECTION_MODULUS = 1.0 / 6.0;  // W for 1m×1m square: bh²/6
    private static final double BLOCK_CROSS_SECTION_AREA = 1.0;  // m²

    // ═══════════════════════════════════════════════════════════════════════════════
    // 1. Moment Capacity Formula
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Moment capacity formula: M = Rtens × 1e6 × W")
    void testMomentCapacityFormula() {
        // M = Rtens (MPa) × 1e6 (Pa/MPa) × (bh²/6) (m³)
        // For unit square (1m×1m): W = 1/6 m³
        // M [N·m] = Pa × m³ = N/m² × m³ = N·m ✓

        double rtens = DefaultMaterial.CONCRETE.getRtens();  // 3.0 MPa
        double expectedMoment = rtens * 1e6 * BLOCK_SECTION_MODULUS;

        assertEquals(500_000.0, expectedMoment, 0.1,
            "CONCRETE (Rtens=3.0 MPa) should have moment capacity ≈ 500 kN·m");
    }

    @Test
    @DisplayName("CONCRETE moment capacity = 500,000 N·m (numerical)")
    void testConcreteNumericalMomentCapacity() {
        double rtens = 3.0;  // CONCRETE Rtens in MPa
        double momentCapacity = rtens * 1e6 * BLOCK_SECTION_MODULUS;

        assertEquals(500_000.0, momentCapacity, 1.0,
            "CONCRETE must have 500,000 N·m moment capacity");
    }

    @Test
    @DisplayName("TIMBER moment capacity ≈ 1.33 MN·m")
    void testTimberMomentCapacity() {
        double rtens = DefaultMaterial.TIMBER.getRtens();  // 8.0 MPa
        double momentCapacity = rtens * 1e6 * BLOCK_SECTION_MODULUS;

        double expected = 8.0 * 1e6 * (1.0/6.0);  // ≈ 1.33 MN·m
        assertEquals(expected, momentCapacity, expected * 0.01,
            "TIMBER should have higher moment capacity than CONCRETE");
    }

    @Test
    @DisplayName("STEEL moment capacity ≈ 8.33 MN·m")
    void testSteelMomentCapacity() {
        double rtens = DefaultMaterial.STEEL.getRtens();  // 500.0 MPa
        double momentCapacity = rtens * 1e6 * BLOCK_SECTION_MODULUS;

        double expected = 500.0 * 1e6 * (1.0/6.0);  // ≈ 8.33 MN·m
        assertEquals(expected, momentCapacity, expected * 0.01,
            "STEEL should have very high moment capacity");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 2. Crushing Capacity Formula
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Crushing capacity formula: F = Rcomp × 1e6 × A")
    void testCrushingCapacityFormula() {
        // F = Rcomp (MPa) × 1e6 (Pa/MPa) × A (m²)
        // F [N] = Pa × m² = N/m² × m² = N ✓

        double rcomp = DefaultMaterial.STONE.getRcomp();  // 30.0 MPa
        double area = BLOCK_CROSS_SECTION_AREA;  // 1.0 m²
        double capacity = rcomp * 1e6 * area;

        assertEquals(30.0 * 1e6, capacity, 0.1,
            "STONE (Rcomp=30 MPa) should have crushing capacity = 30 MN");
    }

    @Test
    @DisplayName("STONE crushing capacity = 30 MN")
    void testStoneNumericalCrushingCapacity() {
        double rcomp = 30.0;  // MPa
        double capacity = rcomp * 1e6 * 1.0;  // 1.0 m² area

        assertEquals(30.0 * 1e6, capacity, 1.0,
            "STONE must have 30 MN crushing capacity");
    }

    @Test
    @DisplayName("CONCRETE crushing capacity = 30 MN")
    void testConcreteNumericalCrushingCapacity() {
        double rcomp = DefaultMaterial.CONCRETE.getRcomp();  // 30.0 MPa
        double capacity = rcomp * 1e6 * 1.0;

        assertEquals(30.0 * 1e6, capacity, 1.0,
            "CONCRETE (Rcomp=30 MPa) must have 30 MN crushing capacity");
    }

    @Test
    @DisplayName("STEEL crushing capacity = 350 MN (much higher)")
    void testSteelNumericalCrushingCapacity() {
        double rcomp = DefaultMaterial.STEEL.getRcomp();  // 350.0 MPa
        double capacity = rcomp * 1e6 * 1.0;

        assertEquals(350.0 * 1e6, capacity, 1.0,
            "STEEL (Rcomp=350 MPa) must have 350 MN crushing capacity");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 3. Load to Force Conversion
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Load to force: F = ρ × g × V (dimensionally correct)")
    void testLoadToForceConversion() {
        // F [N] = ρ (kg/m³) × g (m/s²) × V (m³)
        // F = kg/m³ × m/s² × m³ = kg·m/s² = N ✓

        double density = DefaultMaterial.CONCRETE.getDensity();  // 2350 kg/m³
        double volume = 1.0;  // 1 m³ (unit block)
        double force = density * GRAVITY * volume;

        double expected = 2350.0 * 9.81;  // ≈ 23,065 N
        assertEquals(expected, force, expected * 0.01,
            "CONCRETE block self-weight should be ρ×g×V");
    }

    @Test
    @DisplayName("CONCRETE block (1m³) self-weight ≈ 23.1 kN")
    void testConcreteBlockSelfWeight() {
        double density = 2350.0;  // kg/m³
        double weight = density * 9.81 * 1.0;  // 1 m³

        assertEquals(23_065.0, weight, 100.0,
            "CONCRETE block weight should be ~23 kN");
    }

    @Test
    @DisplayName("TIMBER block (1m³) self-weight ≈ 5.9 kN")
    void testTimberBlockSelfWeight() {
        double density = DefaultMaterial.TIMBER.getDensity();  // 600 kg/m³
        double weight = density * 9.81;

        double expected = 600.0 * 9.81;  // ≈ 5,886 N
        assertEquals(expected, weight, expected * 0.01,
            "TIMBER block weight should be ~5.9 kN");
    }

    @Test
    @DisplayName("STEEL block (1m³) self-weight ≈ 77.1 kN (very heavy)")
    void testSteelBlockSelfWeight() {
        double density = DefaultMaterial.STEEL.getDensity();  // 7850 kg/m³
        double weight = density * 9.81;

        double expected = 7850.0 * 9.81;  // ≈ 77,069 N
        assertEquals(expected, weight, expected * 0.01,
            "STEEL block weight should be ~77 kN");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 4. Von Mises Stress Verification
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Von Mises: √(0.3² + 0.4²) = 0.5, not 0.7")
    void testVonMisesStressFormula() {
        // σ_vm = √(σ_axial² + σ_bending²)
        double sigma_axial = 0.3;
        double sigma_bending = 0.4;
        double vonMises = Math.sqrt(sigma_axial * sigma_axial + sigma_bending * sigma_bending);

        // Linear sum would be 0.7, but quadratic (Von Mises) gives 0.5
        assertEquals(0.5, vonMises, 0.01,
            "Von Mises should give √(0.3² + 0.4²) = 0.5");

        assertTrue(vonMises < sigma_axial + sigma_bending,
            "Von Mises should be less than linear sum (0.5 < 0.7)");
    }

    @Test
    @DisplayName("Von Mises: √(0.6² + 0.8²) = 1.0")
    void testVonMisesStressExample2() {
        double sigma1 = 0.6;
        double sigma2 = 0.8;
        double vonMises = Math.sqrt(sigma1 * sigma1 + sigma2 * sigma2);

        assertEquals(1.0, vonMises, 0.01,
            "Von Mises should give √(0.6² + 0.8²) = 1.0 = 0.6² + 0.8² (3-4-5 triangle)");
    }

    @Test
    @DisplayName("Pure axial (shear=0): Von Mises = axial ratio")
    void testVonMisesPureAxial() {
        double axialRatio = 0.75;
        double momentRatio = 0.0;
        double vonMises = Math.sqrt(axialRatio * axialRatio + momentRatio * momentRatio);

        assertEquals(0.75, vonMises, 0.01,
            "Pure axial should give utilization = axial ratio");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 5. Utilization Ratio Dimensionality
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Utilization ratio is dimensionless (0 to 1+ range)")
    void testUtilizationDimensionless() {
        // Utilization = force / capacity [dimensionless]
        // All terms are forces, ratio has no units

        double force = 15.0e6;  // 15 MN (N)
        double capacity = 30.0e6;  // 30 MN (N)
        double util = force / capacity;

        assertEquals(0.5, util, 0.01,
            "Utilization ratio should be dimensionless (N/N = dimensionless)");
        assertTrue(util >= 0.0 && util <= 1.0,
            "Typical utilization should be 0-1 for safe structures");
    }

    @Test
    @DisplayName("Stress utilization = actual stress / capacity stress")
    void testStressUtilization() {
        // σ = F/A (Pa)
        // Utilization = σ / σ_capacity = (F/A) / (Rcomp×1e6) [dimensionless]

        double force = 10.0e6;  // 10 MN
        double area = 1.0;  // 1 m²
        double stress = force / area;  // 10 MPa

        double rcomp = 30.0;  // MPa
        double rcompPa = rcomp * 1e6;  // Convert to Pa
        double utilization = stress / rcompPa;

        assertEquals(10.0 / 30.0, utilization, 0.01,
            "Utilization should be stress/capacity");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 6. Distributed Beam Moment
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Distributed load moment: M_max = q × L² / 8")
    void testDistributedBeamMoment() {
        // For simply-supported beam with uniform load q over span L:
        // M_max = q × L² / 8

        double q = 10.0e3;  // 10 kN/m distributed load
        double L = 4.0;  // 4 m span
        double expectedM = (q * L * L) / 8.0;

        assertEquals(20.0e3, expectedM, 0.1,
            "Distributed load on 4m span should give 20 kN·m moment");
    }

    @Test
    @DisplayName("Cantilever beam moment: M_max = q × L² / 2")
    void testCantileverBeamMoment() {
        // For cantilever with load at free end or distributed over length:
        // M_max = q × L² / 2 (fixed end)

        double q = 10.0e3;  // 10 kN/m
        double L = 2.0;  // 2 m span
        double expectedM = (q * L * L) / 2.0;

        assertEquals(20.0e3, expectedM, 0.1,
            "Cantilever moment should be q×L²/2");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 7. Section Modulus for Unit Square
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Section modulus for 1m×1m square: W = bh²/6 = 1/6")
    void testSectionModulusUnitSquare() {
        // For rectangular section: W = b × h² / 6
        // For 1m × 1m: W = 1 × 1² / 6 = 1/6 m³

        double b = 1.0;  // width (m)
        double h = 1.0;  // height (m)
        double W = (b * h * h) / 6.0;

        assertEquals(1.0 / 6.0, W, 1e-10,
            "1m×1m square should have W = 1/6 m³");
    }

    @Test
    @DisplayName("Moment capacity = Rtens × W (M = σ × W)")
    void testMomentCapacityFromSectionModulus() {
        // M_max = Rtens × W
        // Where W = I / y_max (section modulus)

        double rtens = 3.0e6;  // 3 MPa in Pa
        double W = 1.0 / 6.0;  // m³
        double M_max = rtens * W;

        assertEquals(500_000.0, M_max, 0.1,
            "Moment capacity should be Rtens × W = 500 kN·m for concrete");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 8. Moment of Inertia for Unit Square
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Moment of inertia for 1m×1m square: I = b⁴/12 = 1/12")
    void testMomentOfInertiaUnitSquare() {
        // For square section: I = b⁴/12
        // For 1m × 1m: I = 1⁴/12 = 1/12 m⁴

        double b = 1.0;
        double I = Math.pow(b, 4) / 12.0;

        assertEquals(1.0 / 12.0, I, 1e-10,
            "1m×1m square should have I = 1/12 m⁴");
    }

    @Test
    @DisplayName("Relationship: W = I / y_max (1/6 = (1/12) / 0.5)")
    void testSectionModulusFromInertia() {
        // W = I / y_max
        // For 1m×1m square: y_max = 0.5 m (distance to neutral axis)
        // W = (1/12) / 0.5 = 1/6 ✓

        double I = 1.0 / 12.0;
        double y_max = 0.5;
        double W = I / y_max;

        assertEquals(1.0 / 6.0, W, 1e-10,
            "Section modulus should equal I / y_max");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 9. Integration: Moment Capacity from I and Rtens
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Full formula: M_max = Rtens × I / y_max")
    void testFullMomentCapacityFormula() {
        // M_max = Rtens (Pa) × I (m⁴) / y_max (m) = N·m ✓

        RMaterial concrete = DefaultMaterial.CONCRETE;
        double rtens = concrete.getRtens() * 1e6;  // Convert to Pa
        double I = 1.0 / 12.0;
        double y_max = 0.5;

        double M_max = rtens * I / y_max;

        assertEquals(500_000.0, M_max, 1.0,
            "Full formula: M_max = Rtens×I/y_max should equal 500 kN·m");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 10. Material Property Comparisons
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Capacity ordering: STEEL >> CONCRETE ≈ STONE >> TIMBER > BRICK")
    void testMaterialCapacityOrdering() {
        double steelCapacity = DefaultMaterial.STEEL.getRcomp() * 1e6 * 1.0;
        double concreteCapacity = DefaultMaterial.CONCRETE.getRcomp() * 1e6 * 1.0;
        double stoneCapacity = DefaultMaterial.STONE.getRcomp() * 1e6 * 1.0;
        double timberCapacity = DefaultMaterial.TIMBER.getRcomp() * 1e6 * 1.0;
        double brickCapacity = DefaultMaterial.BRICK.getRcomp() * 1e6 * 1.0;

        assertTrue(steelCapacity > concreteCapacity, "STEEL > CONCRETE");
        assertEquals(concreteCapacity, stoneCapacity, 1.0, "CONCRETE ≈ STONE");
        assertTrue(concreteCapacity > brickCapacity, "CONCRETE > BRICK");
        assertTrue(brickCapacity > timberCapacity, "BRICK > TIMBER");
    }

    @Test
    @DisplayName("Moment capacity ordering: STEEL >> TIMBER >> CONCRETE")
    void testMaterialMomentCapacityOrdering() {
        double steelMoment = DefaultMaterial.STEEL.getRtens() * 1e6 * BLOCK_SECTION_MODULUS;
        double timberMoment = DefaultMaterial.TIMBER.getRtens() * 1e6 * BLOCK_SECTION_MODULUS;
        double concreteMoment = DefaultMaterial.CONCRETE.getRtens() * 1e6 * BLOCK_SECTION_MODULUS;

        assertTrue(steelMoment > timberMoment, "STEEL moment > TIMBER");
        assertTrue(timberMoment > concreteMoment, "TIMBER moment > CONCRETE");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 11. Stress from Force
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Stress formula: σ = F / A (dimensionally Pa = N/m²)")
    void testStressFormula() {
        // σ [Pa] = F [N] / A [m²] = N/m² = Pa ✓

        double force = 30.0e6;  // 30 MN
        double area = 1.0;  // 1 m²
        double stress = force / area;  // 30 MPa = 30e6 Pa

        assertEquals(30.0e6, stress, 0.1,
            "Stress should be force/area");
    }

    @Test
    @DisplayName("Utilization = stress / material capacity (Pa/Pa = dimensionless)")
    void testUtilizationFromStress() {
        double stress = 15.0e6;  // 15 MPa in Pa
        double capacity = 30.0e6;  // 30 MPa in Pa
        double utilization = stress / capacity;

        assertEquals(0.5, utilization, 0.01,
            "Utilization = stress / capacity (dimensionless)");
    }
}
