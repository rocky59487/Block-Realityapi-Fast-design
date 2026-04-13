package com.blockreality.api.spi;

import com.blockreality.api.fragment.StructureFragment;
import net.minecraft.server.level.ServerLevel;

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
 * <h3>Thread safety</h3>
 * All methods are called from the server tick thread and need not be thread-safe.
 */
public interface IVS2Bridge {

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
}
