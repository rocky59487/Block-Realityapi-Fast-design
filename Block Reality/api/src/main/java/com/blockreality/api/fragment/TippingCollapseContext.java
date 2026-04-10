package com.blockreality.api.fragment;

import com.blockreality.api.physics.OverturningStabilityChecker;

/**
 * ThreadLocal bridge: PFSFEngineInstance (overturning detection) →
 * StructureFragmentDetector (angular velocity override).
 *
 * <p>Forge fires {@code RStructureCollapseEvent} synchronously on the server thread,
 * so a ThreadLocal is safe here — the entire detection → event → fragment pipeline
 * runs on the same thread within a single {@code onServerTick()} call.
 *
 * <p>Usage pattern:
 * <ol>
 *   <li>PFSFEngineInstance calls {@link #set(OverturningStabilityChecker.Result)} before posting the
 *       collapse event for an OVERTURNING island.</li>
 *   <li>StructureFragmentDetector calls {@link #consume()} inside the event handler.
 *       The value is removed from the ThreadLocal on consumption to prevent stale data
 *       leaking into subsequent (non-overturning) collapse events in the same tick.</li>
 * </ol>
 */
public final class TippingCollapseContext {

    private static final ThreadLocal<OverturningStabilityChecker.Result> CURRENT =
        new ThreadLocal<>();

    private TippingCollapseContext() {}

    /** Store the overturning result for this collapse. */
    public static void set(OverturningStabilityChecker.Result result) {
        CURRENT.set(result);
    }

    /**
     * Retrieve and clear the stored result.
     *
     * @return the result set by the current overturning collapse, or {@code null} if
     *         this collapse was not triggered by overturning
     */
    public static OverturningStabilityChecker.Result consume() {
        OverturningStabilityChecker.Result r = CURRENT.get();
        CURRENT.remove();
        return r;
    }
}
