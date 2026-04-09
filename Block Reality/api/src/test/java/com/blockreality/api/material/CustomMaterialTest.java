package com.blockreality.api.material;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CustomMaterialTest {

    @Test
    void testBuilderCreatesValidMaterial() {
        RMaterial material = new CustomMaterial.Builder("test_material")
                .rcomp(30.0)
                .rtens(25.0)
                .rshear(15.0)
                .build();

        assertNotNull(material, "Builder should create non-null material");
        assertEquals("test_material", material.getMaterialId());
        assertEquals(30.0, material.getRcomp(), 1e-6);
    }

    @Test
    void testBuilderValidationNullIdThrows() {
        assertThrows(IllegalArgumentException.class, () -> {
            new CustomMaterial.Builder(null)
                    .rcomp(30.0)
                    .rtens(25.0)
                    .rshear(15.0)
                    .build();
        }, "Builder should throw when id is null");
    }

    @Test
    void testBuilderValidationEmptyIdThrows() {
        assertThrows(IllegalArgumentException.class, () -> {
            new CustomMaterial.Builder("")
                    .rcomp(30.0)
                    .build();
        }, "Builder should throw when id is empty");
    }

    @Test
    void testBuilderValidationNegativeRcompThrows() {
        assertThrows(IllegalArgumentException.class, () -> {
            new CustomMaterial.Builder("test_material")
                    .rcomp(-10.0)
                    .rtens(25.0)
                    .rshear(15.0)
                    .build();
        }, "Builder should throw when rcomp is negative");
    }

    @Test
    void testBuilderValidationAllZerosThrows() {
        assertThrows(IllegalStateException.class, () -> {
            new CustomMaterial.Builder("test_material")
                    .rcomp(0.0)
                    .rtens(0.0)
                    .rshear(0.0)
                    .build();
        }, "Builder should throw when all strength values are zero");
    }

    @Test
    void testGetMaterialIdReturnsCorrectId() {
        String expectedId = "custom_id_123";
        RMaterial material = new CustomMaterial.Builder(expectedId)
                .rcomp(25.0)
                .rtens(20.0)
                .rshear(12.0)
                .build();

        assertEquals(expectedId, material.getMaterialId());
    }

    @Test
    void testGetMaxSpanReturnsValidRange() {
        RMaterial material = new CustomMaterial.Builder("test_material")
                .rcomp(30.0)
                .rtens(25.0)
                .rshear(15.0)
                .build();

        int maxSpan = material.getMaxSpan();
        assertTrue(maxSpan >= 1 && maxSpan <= 64,
                "Max span should be in valid range [1, 64], got: " + maxSpan);
    }

    @Test
    void testIsDuctileReturnsCorrectValue() {
        // Ductile materials: Rcomp/Rtens < 10 → 30/25 = 1.2 → ductile
        RMaterial ductileMaterial = new CustomMaterial.Builder("test_ductile")
                .rcomp(30.0)
                .rtens(25.0)
                .rshear(15.0)
                .build();

        // Brittle materials: Rcomp/Rtens >= 10 → 400/2 = 200 → brittle
        RMaterial brittleMaterial = new CustomMaterial.Builder("test_brittle")
                .rcomp(400.0)
                .rtens(2.0)
                .rshear(5.0)
                .build();

        assertTrue(ductileMaterial.isDuctile(), "Ductile material should return true");
        assertFalse(brittleMaterial.isDuctile(), "Brittle material should return false");
    }

    @Test
    void testGetCombinedStrengthComputation() {
        RMaterial material = new CustomMaterial.Builder("test_material")
                .rcomp(30.0)
                .rtens(25.0)
                .rshear(15.0)
                .build();

        double combinedStrength = material.getCombinedStrength();
        assertTrue(combinedStrength > 0, "Combined strength should be positive");

        // getCombinedStrength() = cbrt(rcomp * rtens * rshear) = cbrt(30*25*15) = cbrt(11250)
        double expected = Math.cbrt(30.0 * 25.0 * 15.0);
        assertEquals(expected, combinedStrength, 1e-6,
                "Combined strength should be geometric mean of three axes");
    }

    @Test
    void testDensityDefaultAndCustom() {
        // Default density
        RMaterial defaultDensity = new CustomMaterial.Builder("default_density")
                .rcomp(30.0)
                .build();
        assertEquals(1000.0, defaultDensity.getDensity(), 1e-6,
                "Default density should be 1000 kg/m³");

        // Custom density
        RMaterial customDensity = new CustomMaterial.Builder("custom_density")
                .rcomp(30.0)
                .density(2400.0)
                .build();
        assertEquals(2400.0, customDensity.getDensity(), 1e-6,
                "Custom density should be set correctly");
    }

    @Test
    void testNegativeDensityThrows() {
        assertThrows(IllegalArgumentException.class, () -> {
            new CustomMaterial.Builder("test")
                    .rcomp(30.0)
                    .density(-100.0)
                    .build();
        }, "Builder should throw when density is negative");
    }
}
