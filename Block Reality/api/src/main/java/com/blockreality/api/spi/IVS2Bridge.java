package com.blockreality.api.spi;

import com.blockreality.api.fragment.StructureFragment;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * Optional SPI bridge to Valkyrien Skies 2 for fragment rigid-body dynamics.
 *
 * <h3>Division of responsibility</h3>
 * <ul>
 *   <li><b>Block Reality</b> — static structural analysis, failure detection (PFSF GPU
 *       solver), gravity CoM overturning check, and computation of initial linear/angular
 *       velocity for the resulting fragment.</li>
 *   <li><b>Valkyrien Skies 2</b> — free rigid-body simulation: rotation, rolling,
 *       collision response, and settling. Activated only when VS2 is installed.</li>
 * </ul>
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>At mod init, {@link com.blockreality.api.BlockRealityMod} checks whether VS2
 *       is loaded. If yes, it registers a {@link com.blockreality.api.vs2.VS2ShipBridge}
 *       via {@link com.blockreality.api.spi.ModuleRegistry#setVS2Bridge}.</li>
 *   <li>On every fragment spawn, {@link com.blockreality.api.fragment.StructureFragmentManager}
 *       calls {@link #assembleAsShip}. If VS2 handles it ({@code true}), no
 *       {@code StructureFragmentEntity} is spawned. Otherwise, the built-in
 *       {@code StructureFragmentEntity + StructureRigidBody} fallback activates.</li>
 *   <li>Each level tick, {@link #tickActiveShips} is called to monitor active VS2 ships
 *       for settle detection. When a ship's velocity drops below threshold, it is
 *       disassembled and rubble blocks are placed in the world.</li>
 * </ol>
 *
 * <h3>Ship query API</h3>
 * Use {@link #getShipSnapshot(UUID)} or {@link #getAllShipSnapshots()} to read the
 * runtime state (position, velocity, settle progress) of tracked VS2 ships. These are
 * primarily intended for diagnostics (/br debug vs2) and the node graph monitoring
 * pipeline, but may also be used by external mods via {@code ModuleRegistry.getVS2Bridge()}.
 *
 * <h3>Force application API</h3>
 * {@link #applyForceToShip(UUID, double, double, double)} allows Block Reality (or node
 * graph effects) to apply a world-space impulse/force to a tracked VS2 ship —
 * e.g., for explosion shockwaves, wind pressure, or chain-reaction physics interactions.
 *
 * <h3>Thread safety</h3>
 * All methods are called from the server tick thread and need not be thread-safe.
 */
public interface IVS2Bridge {

    // ─── Ship state snapshot ───────────────────────────────────────────────

    /**
     * Immutable snapshot of a VS2 ship's runtime state at a single tick.
     *
     * @param fragmentId    Block Reality fragment UUID (matches {@code StructureFragment.id()})
     * @param posX          world-space X position of the ship's centre of mass
     * @param posY          world-space Y position of the ship's centre of mass
     * @param posZ          world-space Z position of the ship's centre of mass
     * @param velX          linear velocity X (m/s, world-space)
     * @param velY          linear velocity Y (m/s, world-space)
     * @param velZ          linear velocity Z (m/s, world-space)
     * @param speed         linear speed magnitude (|velocity|, m/s)
     * @param blockCount    number of blocks in the original fragment
     * @param ageTicks      ticks elapsed since ship creation
     * @param settleCounter consecutive ticks below velocity threshold
     */
    record ShipDataSnapshot(
        UUID   fragmentId,
        double posX,    double posY,    double posZ,
        double velX,    double velY,    double velZ,
        double speed,
        int    blockCount,
        int    ageTicks,
        int    settleCounter
    ) {
        /** Convenience: format as a single-line debug string. */
        public String toDebugLine() {
            return String.format(
                "Ship[%s] pos=(%.1f,%.1f,%.1f) vel=(%.2f,%.2f,%.2f) spd=%.3f age=%dt settle=%d/%d blk=%d",
                fragmentId.toString().substring(0, 8),
                posX, posY, posZ,
                velX, velY, velZ, speed,
                ageTicks, settleCounter, 40,  // 40 = canonical SETTLE_TICKS constant
                blockCount
            );
        }
    }

    /**
     * Returns {@code true} if VS2 is installed and the bridge is operational.
     * Called each time a fragment is about to spawn — may be called frequently.
     */
    boolean isAvailable();

    /**
     * Assemble the fragment's block set into a VS2 ship and apply initial velocity.
     *
     * <p>The implementation must:
     * <ol>
     *   <li>Obtain VS2's {@code ServerShipWorld} from the level.</li>
     *   <li>Create a new ship at (or near) the fragment's centre-of-mass.</li>
     *   <li>Let VS2 collect the fragment's blocks (VS2 handles block transfer to ship space).</li>
     *   <li>Apply initial linear velocity from {@code fragment.vx/vy/vz}.</li>
     *   <li>Apply initial angular velocity from {@code fragment.angVelX/Y/Z}.</li>
     * </ol>
     *
     * @param level    the server level where the collapse happened
     * @param fragment fragment carrying: block snapshot, CoM position, initial velocities
     * @return {@code true}  — VS2 ship created; caller must NOT spawn a StructureFragmentEntity<br>
     *         {@code false} — VS2 unavailable or assembly failed; fall back to StructureFragmentEntity
     */
    boolean assembleAsShip(ServerLevel level, StructureFragment fragment);

    /**
     * Tick all active VS2 ships created by this bridge.
     *
     * <p>Implementations should monitor each tracked ship's velocity and detect
     * when it has settled (velocity below threshold for a sustained period).
     * On settle, the implementation should place rubble blocks in the world and
     * destroy the VS2 ship.
     *
     * <p>Called once per level tick from
     * {@link com.blockreality.api.fragment.StructureFragmentManager#tick()}.
     *
     * @param level the server level to place rubble blocks in
     */
    default void tickActiveShips(ServerLevel level) { /* no-op for NoOpVS2Bridge */ }

    /**
     * Returns the number of VS2 ships currently being tracked for settle detection.
     * Useful for diagnostics and monitoring.
     *
     * @return active ship count, or 0 if no ships are tracked
     */
    default int getActiveShipCount() { return 0; }

    // ─── Ship query API ───────────────────────────────────────────────────

    /**
     * Return a snapshot of a specific active VS2 ship's runtime state.
     *
     * <p>The snapshot is computed from the VS2 ship's current velocity and the
     * tracking entry's settle counter / age tick. Position is read via VS2's
     * {@code getTransform().getPositionInWorld()} (reflective, best-effort).
     *
     * @param fragmentId the Block Reality fragment UUID assigned at spawn
     * @return current snapshot, or {@code null} if the ship is not tracked
     *         (already settled, not yet assembled, or VS2 unavailable)
     */
    @Nullable
    default ShipDataSnapshot getShipSnapshot(UUID fragmentId) { return null; }

    /**
     * Return snapshots for <em>all</em> currently tracked VS2 ships.
     *
     * <p>Intended for diagnostics ({@code /br debug vs2}) and the node-graph
     * monitoring pipeline. The returned list is a fresh immutable copy and is
     * safe to hold across ticks.
     *
     * @return unmodifiable list of snapshots; empty if no ships are active
     */
    default List<ShipDataSnapshot> getAllShipSnapshots() { return List.of(); }

    // ─── Force application API ────────────────────────────────────────────

    /**
     * Apply a world-space force impulse to a tracked VS2 ship this tick.
     *
     * <p>Use cases:
     * <ul>
     *   <li>Explosion shockwave — push the rubble ship away from blast origin</li>
     *   <li>Wind pressure — constant lateral force from {@code WindNode} output</li>
     *   <li>Chain-reaction — adjacent collapsing island kicks the active ship</li>
     * </ul>
     *
     * <p>Implementation delegates to VS2's physics force API (reflective, best-effort).
     * If VS2 does not expose a suitable API, the call is silently ignored and
     * {@code false} is returned.
     *
     * @param fragmentId the Block Reality fragment UUID of the ship to push
     * @param fx         force X component (N, world-space)
     * @param fy         force Y component (N, world-space)
     * @param fz         force Z component (N, world-space)
     * @return {@code true} if the force was successfully applied; {@code false} if
     *         the ship is not tracked, VS2 is unavailable, or the API is missing
     */
    default boolean applyForceToShip(UUID fragmentId, double fx, double fy, double fz) {
        return false;
    }

    /**
     * Diagnostic string for the circuit breaker state.
     * Returns a human-readable description of the current bridge health.
     */
    default String getBridgeDiagnostics() {
        return "VS2Bridge[unavailable]";
    }
}
