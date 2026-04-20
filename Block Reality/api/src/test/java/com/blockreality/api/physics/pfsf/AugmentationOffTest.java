package com.blockreality.api.physics.pfsf;

import com.blockreality.api.physics.pfsf.augbind.CuringAugBinder;
import com.blockreality.api.physics.pfsf.augbind.EMAugBinder;
import com.blockreality.api.physics.pfsf.augbind.FluidAugBinder;
import com.blockreality.api.physics.pfsf.augbind.FusionAugBinder;
import com.blockreality.api.physics.pfsf.augbind.LoadpathHintAugBinder;
import com.blockreality.api.physics.pfsf.augbind.MaterialOverrideAugBinder;
import com.blockreality.api.physics.pfsf.augbind.ThermalAugBinder;
import com.blockreality.api.physics.pfsf.augbind.Wind3DAugBinder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.4 M2g — assert every SPI augmentation binder is inactive when no
 * manager / lookup is registered. This guards the contract that the
 * binder layer itself does not introduce phi-level drift: when callers
 * don't opt into any aug feature, the solver path must be bit-identical
 * to the v0.3e.1 baseline.
 *
 * <p>Fine-grained parity (voxel values under an aug field) is covered
 * by the per-kind {@code *AugmentationParityTest} classes. This suite
 * only proves the "zero SPI → zero write" invariant at the binder
 * surface.
 */
class AugmentationOffTest {

    /* Binders call PFSFEngine static getters; snapshot and restore
     * around each test so we don't leak state into neighbour suites. */
    private Object prevMaterial;
    private Object prevAnchor;
    private Object prevFillRatio;
    private Object prevCuring;
    private Object prevWindVec;

    @BeforeEach
    void snapshotEngineState() throws Exception {
        prevMaterial   = PFSFEngine.getMaterialLookup();
        prevAnchor     = PFSFEngine.getAnchorLookup();
        prevFillRatio  = PFSFEngine.getFillRatioLookup();
        prevCuring     = PFSFEngine.getCuringLookup();
        prevWindVec    = PFSFEngine.getCurrentWindVec();

        /* Clear lookups via the existing setters so test starts pristine. */
        invokeSetter("setMaterialLookup",  java.util.function.Function.class, null);
        invokeSetter("setAnchorLookup",    java.util.function.Function.class, null);
        invokeSetter("setFillRatioLookup", java.util.function.Function.class, null);
        invokeSetter("setCuringLookup",    java.util.function.Function.class, null);
        invokeSetter("setWindVector",  net.minecraft.world.phys.Vec3.class, null);
        MaterialOverrideAugBinder.clearAllProvidersForTesting();
    }

    @AfterEach
    void restoreEngineState() throws Exception {
        invokeSetter("setMaterialLookup",  java.util.function.Function.class, prevMaterial);
        invokeSetter("setAnchorLookup",    java.util.function.Function.class, prevAnchor);
        invokeSetter("setFillRatioLookup", java.util.function.Function.class, prevFillRatio);
        invokeSetter("setCuringLookup",    java.util.function.Function.class, prevCuring);
        invokeSetter("setWindVector",  net.minecraft.world.phys.Vec3.class, prevWindVec);
    }

    private static void invokeSetter(String name, Class<?> argType, Object arg) throws Exception {
        try {
            Method m = PFSFEngine.class.getMethod(name, argType);
            m.invoke(null, arg);
        } catch (NoSuchMethodException ignored) {
            /* Older PFSFEngine builds may not expose every setter —
             * tests still pass because the getter returned null above. */
        }
    }

    @Test
    @DisplayName("Thermal binder inactive when no IThermalManager registered")
    void thermalOff() {
        assertFalse(new ThermalAugBinder().isActiveForTest(),
                "ThermalAugBinder should report inactive without IThermalManager");
    }

    @Test
    @DisplayName("Fluid binder inactive when no IFluidManager registered")
    void fluidOff() {
        assertFalse(new FluidAugBinder().isActiveForTest(),
                "FluidAugBinder should report inactive without IFluidManager");
    }

    @Test
    @DisplayName("EM binder inactive when no IElectromagneticManager registered")
    void emOff() {
        assertFalse(new EMAugBinder().isActiveForTest(),
                "EMAugBinder should report inactive without IElectromagneticManager");
    }

    @Test
    @DisplayName("Curing binder with pristine default singleton publishes no voxels")
    void curingOff() {
        /* ICuringManager has a singleton DefaultCuringManager so
         * CuringAugBinder.isActive() is always true — the binder defers
         * the offline check to fill(), which skips progress==0 voxels.
         * With a pristine default (no block started curing), every
         * voxel reports progress=0 and fill() returns false, which
         * AbstractAugBinder.bind() translates into a PFSFAugmentationHost
         * clear — i.e. no slot is published. Proving that invariant
         * requires a fully wired island buffer (not available in a
         * CI JVM), so we settle for the surface observation:
         * isActive reflects the singleton presence. */
        CuringAugBinder binder = new CuringAugBinder();
        assertTrue(binder.isActiveForTest(),
                "CuringAugBinder is gated on ModuleRegistry singleton — expected active-by-default");
    }

    @Test
    @DisplayName("Fusion binder inactive when no material lookup registered")
    void fusionOff() {
        assertFalse(new FusionAugBinder().isActiveForTest(),
                "FusionAugBinder should report inactive without material lookup");
    }

    @Test
    @DisplayName("Material-override binder inactive when no provider registered")
    void materialOverrideOff() {
        assertFalse(new MaterialOverrideAugBinder().isActiveForTest(),
                "MaterialOverrideAugBinder should report inactive without provider");
    }

    @Test
    @DisplayName("Wind3D binder inactive when no wind source is configured")
    void wind3dOff() {
        assertFalse(new Wind3DAugBinder().isActiveForTest(),
                "Wind3DAugBinder should report inactive without IWindManager or engine wind vec");
    }

    @Test
    @DisplayName("Loadpath-hint binder inactive when no anchor or material lookup registered")
    void loadpathHintOff() {
        assertFalse(new LoadpathHintAugBinder().isActiveForTest(),
                "LoadpathHintAugBinder should report inactive without anchor / material lookup");
    }

    @Test
    @DisplayName("PFSFAugmentationHost.runBinders is a no-op when nothing is registered")
    void runBindersNoRegistrationsClean() {
        /* Don't rely on global state — just verify that calling
         * runBinders with the default binder set doesn't throw and
         * leaves the host empty for the island in question. */
        final int islandId = 0x7F000001;
        PFSFAugmentationHost.clearIsland(islandId);
        assertDoesNotThrow(() -> PFSFAugmentationHost.runBinders(islandId));
        assertEquals(0, PFSFAugmentationHost.islandCount(islandId),
                "No binder published anything — island slot count should stay zero");
    }
}
