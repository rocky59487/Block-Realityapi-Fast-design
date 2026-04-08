package com.blockreality.api.spi;

import com.blockreality.api.material.DefaultMaterial;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;

class ModuleRegistryTest {

    @Test
    void testGetInstanceReturnsNonNull() {
        ModuleRegistry instance = ModuleRegistry.getInstance();
        assertNotNull(instance, "getInstance should return non-null singleton");
    }

    @Test
    void testGetInstanceReturnsSameSingleton() {
        ModuleRegistry instance1 = ModuleRegistry.getInstance();
        ModuleRegistry instance2 = ModuleRegistry.getInstance();
        assertSame(instance1, instance2, "getInstance should always return the same instance");
    }

    @Test
    void testGetCommandProvidersReturnsNonNull() {
        List<ICommandProvider> providers = ModuleRegistry.getCommandProviders();
        assertNotNull(providers, "getCommandProviders should return non-null collection");
    }

    @Test
    void testGetRenderLayerProvidersReturnsNonNull() {
        List<IRenderLayerProvider> renderProviders = ModuleRegistry.getRenderLayerProviders();
        assertNotNull(renderProviders, "getRenderLayerProviders should return non-null collection");
    }

    @Test
    void testGetMaterialRegistryReturnsNonNull() {
        IMaterialRegistry materialRegistry = ModuleRegistry.getMaterialRegistry();
        assertNotNull(materialRegistry, "getMaterialRegistry should return non-null");
    }

    @Test
    void testMaterialRegistryPreLoadedWithDefaultMaterials() {
        IMaterialRegistry materialRegistry = ModuleRegistry.getMaterialRegistry();
        assertNotNull(materialRegistry, "Material registry should be initialized");

        // Verify that at least one preset material is registered
        assertTrue(materialRegistry.getCount() > 0,
                "Material registry should be pre-loaded with default materials");

        // Verify that CONCRETE is registered
        assertTrue(materialRegistry.getMaterial(DefaultMaterial.CONCRETE.getMaterialId()).isPresent(),
                "CONCRETE should be registered in material registry");
    }

    @Test
    void testGetCuringManagerReturnsNonNull() {
        ICuringManager curingManager = ModuleRegistry.getCuringManager();
        assertNotNull(curingManager, "getCuringManager should return non-null");
    }

    @Test
    void testGetCableManagerReturnsNonNull() {
        ICableManager cableManager = ModuleRegistry.getCableManager();
        assertNotNull(cableManager, "getCableManager should return non-null");
    }

    @Test
    void testGetFusionDetectorReturnsNonNull() {
        IFusionDetector fusionDetector = ModuleRegistry.getFusionDetector();
        assertNotNull(fusionDetector, "getFusionDetector should return non-null");
    }

    @Test
    void testSetCableManagerRejectsNull() {
        assertThrows(NullPointerException.class, () -> {
            ModuleRegistry.setCableManager(null);
        }, "setCableManager should reject null");
    }

    @Test
    void testSetFusionDetectorRejectsNull() {
        assertThrows(NullPointerException.class, () -> {
            ModuleRegistry.setFusionDetector(null);
        }, "setFusionDetector should reject null");
    }

    @Test
    void testGetRegistrySummaryReturnsNonEmptyString() {
        String summary = ModuleRegistry.getRegistrySummary();
        assertNotNull(summary, "getRegistrySummary should return non-null");
        assertFalse(summary.isEmpty(), "getRegistrySummary should return non-empty string");
        assertTrue(summary.contains("Command Providers"),
                "Summary should contain 'Command Providers'");
    }
}
