package com.blockreality.api.physics.em;

import net.minecraft.core.BlockPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Joule heat → PFSF source term bridge.
 *
 * <p>Accumulates Joule heat power density P = J²/σ (W/m³) from the EM engine
 * and makes it available to PFSF as an additional thermal source term.
 *
 * <p>Usage: Each EM tick calls {@link #inject(BlockPos, float)} for hot spots.
 * Before the next PFSF data build, call {@link #drainPendingInjections()} to
 * retrieve and reset the accumulated map.
 *
 * <p>Thread safety: ConcurrentHashMap guards concurrent EM tick + PFSF drain.
 */
public final class EmThermalInjector {

    private static final Logger LOGGER = LogManager.getLogger("BR-EmThermal");

    /** Pending Joule heat injections: BlockPos → power density (W/m³) */
    private final ConcurrentHashMap<BlockPos, Float> pending = new ConcurrentHashMap<>();

    /**
     * Records a Joule heat injection at {@code pos}.
     *
     * @param pos           block position (world coordinates)
     * @param powerDensity  Joule heat power density P = J²/σ (W/m³)
     */
    public void inject(BlockPos pos, float powerDensity) {
        // Accumulate: multiple EM sources can overlap at same block
        pending.merge(pos, powerDensity, Float::sum);
    }

    /**
     * Returns and clears all pending injections.
     * Called by EmEngine before (or during) the PFSF data build phase
     * to transfer Joule heat as additional source terms.
     *
     * @return map of BlockPos → power density (W/m³); caller owns the result
     */
    public Map<BlockPos, Float> drainPendingInjections() {
        if (pending.isEmpty()) return Map.of();
        Map<BlockPos, Float> snapshot = new HashMap<>(pending);
        pending.clear();
        LOGGER.debug("[BR-EmThermal] Drained {} Joule heat injections", snapshot.size());
        return snapshot;
    }

    /** Returns the number of pending hot-spot injections (for diagnostics). */
    public int pendingCount() { return pending.size(); }
}
