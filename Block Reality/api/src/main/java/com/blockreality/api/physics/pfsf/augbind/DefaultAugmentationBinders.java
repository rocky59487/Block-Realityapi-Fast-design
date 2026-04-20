package com.blockreality.api.physics.pfsf.augbind;

import com.blockreality.api.physics.pfsf.PFSFAugmentationHost;
import com.blockreality.api.physics.pfsf.PFSFAugmentationHost.AugBinder;

import java.util.ArrayList;
import java.util.List;

/**
 * v0.4 M2 — production registration of the 8 SPI augmentation binders.
 *
 * <p>Prior to PR#187 capy-ai R15, {@link PFSFAugmentationHost#registerBinder}
 * was only called from the test suite. In production the {@code BINDERS}
 * list stayed empty, so {@link PFSFAugmentationHost#runBinders(int)} ran
 * zero passes per island and the v0.4 augmentation pipeline was inert for
 * every real server tick — exactly the failure mode the M2 milestone was
 * supposed to close.
 *
 * <p>This helper owns the canonical 8-binder set and keeps installation
 * idempotent: the engine boots call {@link #install()} and the shutdown
 * path calls {@link #uninstall()} so a server restart or module reload
 * does not accumulate duplicate bindings. Each binder independently
 * short-circuits via {@code isActive()} when its SPI manager isn't
 * attached, so a mod that only uses the thermal SPI pays zero per-voxel
 * cost on the other 7.
 */
public final class DefaultAugmentationBinders {

    private DefaultAugmentationBinders() {}

    /** The currently-installed binder instances, so {@link #uninstall()} can
     *  remove the same objects it added. Guarded by the class monitor. */
    private static final List<AugBinder> INSTALLED = new ArrayList<>();

    /**
     * Register the 8 canonical binders (thermal, fluid, EM, curing, fusion,
     * material-override, wind-3D, loadpath-hint). Called once from
     * {@code PFSFEngineInstance.init()}. Safe to call repeatedly — the
     * second and later calls are no-ops while {@link #uninstall()} hasn't
     * been invoked.
     */
    public static synchronized void install() {
        if (!INSTALLED.isEmpty()) return;
        INSTALLED.add(new ThermalAugBinder());
        INSTALLED.add(new FluidAugBinder());
        INSTALLED.add(new EMAugBinder());
        INSTALLED.add(new CuringAugBinder());
        INSTALLED.add(new FusionAugBinder());
        INSTALLED.add(new MaterialOverrideAugBinder());
        INSTALLED.add(new Wind3DAugBinder());
        INSTALLED.add(new LoadpathHintAugBinder());
        for (AugBinder binder : INSTALLED) {
            PFSFAugmentationHost.registerBinder(binder);
        }
    }

    /**
     * Remove every binder installed by {@link #install()}. Called from
     * {@code PFSFEngineInstance.shutdown()} so a re-init cycle does not
     * duplicate registrations.
     */
    public static synchronized void uninstall() {
        if (INSTALLED.isEmpty()) return;
        for (AugBinder binder : INSTALLED) {
            PFSFAugmentationHost.unregisterBinder(binder);
        }
        INSTALLED.clear();
    }

    /** Test helper — exposes the current installed count. */
    public static synchronized int installedCount() {
        return INSTALLED.size();
    }
}
