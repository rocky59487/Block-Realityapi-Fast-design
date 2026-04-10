package com.blockreality.api.fragment;

import com.blockreality.api.registry.BREntities;
import com.blockreality.api.spi.ModuleRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Per-level server-side lifecycle manager for structure fragments.
 *
 * Responsibilities
 * ────────────────
 * · Accept fragment data from {@link StructureFragmentDetector} via {@link #enqueue}.
 * · Spawn {@link StructureFragmentEntity} instances on the next level tick (rate-limited
 *   to {@link #MAX_SPAWNS_PER_TICK} to avoid a single collapse flooding the entity list).
 * · Receive settle notifications from the entity and place rubble blocks in the world.
 *
 * Thread safety
 * ─────────────
 * · {@link #pending} is a {@link ConcurrentLinkedQueue}: safe to enqueue from any thread
 *   (e.g. the forge event bus thread) and dequeue from the server tick thread.
 * · {@link #INSTANCES} uses a {@link WeakHashMap} so managers are GC'd automatically
 *   when their {@link ServerLevel} is unloaded.
 */
public class StructureFragmentManager {

    private static final Logger LOGGER = LogManager.getLogger("BR-Fragment");

    /** Maximum fragments to spawn per level tick (prevents entity storm on large collapses). */
    private static final int MAX_SPAWNS_PER_TICK = 8;

    // ─── Per-level singleton ───

    private static final Map<ServerLevel, StructureFragmentManager> INSTANCES =
        Collections.synchronizedMap(new WeakHashMap<>());

    public static StructureFragmentManager get(ServerLevel level) {
        return INSTANCES.computeIfAbsent(level, StructureFragmentManager::new);
    }

    /** Clear all instances (called on server stop to avoid cross-world leaks). */
    public static void clearAll() {
        INSTANCES.clear();
    }

    /**
     * Explicitly remove the manager for a world that is unloading.
     * Called from ServerTickHandler.onWorldUnload to prevent WeakHashMap key retention
     * (value holds strong reference back to key, preventing GC).
     */
    public static void onWorldUnload(ServerLevel level) {
        StructureFragmentManager mgr = INSTANCES.remove(level);
        if (mgr != null) {
            LOGGER.debug("[BR-Fragment] Manager for {} removed on world unload",
                level.dimension().location());
        }
    }

    // ─── Instance ───

    private final ServerLevel level;
    private final ConcurrentLinkedQueue<StructureFragment> pending = new ConcurrentLinkedQueue<>();

    private StructureFragmentManager(ServerLevel level) {
        this.level = level;
    }

    /**
     * Enqueue a fragment for entity spawning on the next tick.
     * Called from {@link StructureFragmentDetector} (HIGH-priority forge event thread).
     */
    public void enqueue(StructureFragment frag) {
        pending.add(frag);
    }

    /**
     * Called from {@code ServerTickHandler.onLevelTick()} every tick.
     * Pops up to {@link #MAX_SPAWNS_PER_TICK} fragments and spawns their entities.
     */
    public void tick() {
        int spawned = 0;
        StructureFragment frag;
        while (spawned < MAX_SPAWNS_PER_TICK && (frag = pending.poll()) != null) {
            spawnFragment(frag);
            spawned++;
        }
    }

    // ─── Internal ───

    private void spawnFragment(StructureFragment frag) {
        // C8: Try VS2 bridge first (if VS2 is installed).
        // VS2 takes over all free rigid-body dynamics; BR only provided the initial conditions.
        com.blockreality.api.spi.IVS2Bridge bridge = ModuleRegistry.getVS2Bridge();
        if (bridge.isAvailable()) {
            boolean assembled = bridge.assembleAsShip(level, frag);
            if (assembled) {
                LOGGER.debug("[BR-Fragment] Fragment {} ({} blocks) delegated to VS2 ship",
                    frag.id(), frag.blockSnapshot().size());
                return; // VS2 owns this fragment — no StructureFragmentEntity needed
            }
            LOGGER.warn("[BR-Fragment] VS2 bridge returned false for fragment {}, " +
                "falling back to StructureFragmentEntity", frag.id());
        }

        // Fallback: built-in fragment entity + StructureRigidBody
        StructureFragmentEntity entity =
            new StructureFragmentEntity(BREntities.STRUCTURE_FRAGMENT.get(), level, frag);
        entity.moveTo(frag.comX(), frag.comY(), frag.comZ(), 0f, 0f);
        boolean added = level.addFreshEntity(entity);
        if (!added) {
            LOGGER.warn("[BR-Fragment] Failed to spawn fragment entity at ({},{},{})",
                frag.comX(), frag.comY(), frag.comZ());
        }
    }

    /**
     * Called by {@link StructureFragmentEntity} when its rigid body settles.
     *
     * Places each fragment block at its final rotated world position.
     * Blocks are placed only where the target position is currently air,
     * to avoid overwriting player-placed blocks or structures that appeared
     * during the fragment's flight.
     *
     * Rotation: local block centre → quaternion sandwich → CoM-relative world offset.
     *
     * @param entity  the entity that just settled (its position = final CoM)
     */
    public void onFragmentSettle(StructureFragmentEntity entity) {
        double px = entity.getX();
        double py = entity.getY();
        double pz = entity.getZ();

        // Read rotation quaternion from SynchedEntityData
        double qx = entity.getRotQx();
        double qy = entity.getRotQy();
        double qz = entity.getRotQz();
        double qw = entity.getRotQw();

        int placed = 0;
        for (Map.Entry<BlockPos, BlockState> e : entity.getLocalSnapshot().entrySet()) {
            BlockPos lp    = e.getKey();
            BlockState state = e.getValue();
            if (state == null || state.isAir()) continue;

            // Block centre in local space
            double lcx = lp.getX() + 0.5;
            double lcy = lp.getY() + 0.5;
            double lcz = lp.getZ() + 0.5;

            // Rotate local centre by final quaternion → world-space offset from CoM
            double[] rotated = rotateByQuat(lcx, lcy, lcz, qx, qy, qz, qw);

            // World block position: floor of (CoM + rotated centre) gives block corner
            int wx = (int) Math.floor(px + rotated[0]);
            int wy = (int) Math.floor(py + rotated[1]);
            int wz = (int) Math.floor(pz + rotated[2]);

            BlockPos worldPos = new BlockPos(wx, wy, wz);
            if (!level.isLoaded(worldPos)) continue;

            // Only place if the target is empty (preserve any blocks that landed there)
            if (level.getBlockState(worldPos).isAir()) {
                level.setBlock(worldPos, state, 3 /* UPDATE_ALL */);
                placed++;
            }
        }

        if (placed > 0) {
            LOGGER.debug("[BR-Fragment] Settled fragment at ({:.1f},{:.1f},{:.1f}) — placed {} rubble blocks",
                px, py, pz, placed);
        }
    }

    // ─── Quaternion rotation ───

    /**
     * Rotate vector (x, y, z) by unit quaternion (qx, qy, qz, qw).
     * Uses the Hamilton-product sandwich: v' = q ⊗ (0, x, y, z) ⊗ q*.
     *
     * @return double[3] {x', y', z'}
     */
    private static double[] rotateByQuat(double x, double y, double z,
                                          double qx, double qy, double qz, double qw) {
        // Step 1: t = q ⊗ (0, x, y, z)
        double tw = -(qx*x + qy*y + qz*z);
        double tx =  qw*x + qy*z - qz*y;
        double ty =  qw*y + qz*x - qx*z;
        double tz =  qw*z + qx*y - qy*x;

        // Step 2: result = t ⊗ q*    where q* = (-qx, -qy, -qz, qw)
        return new double[]{
            tx*qw + tw*(-qx) + ty*(-qz) - tz*(-qy),
            ty*qw + tw*(-qy) + tz*(-qx) - tx*(-qz),
            tz*qw + tw*(-qz) + tx*(-qy) - ty*(-qx)
        };
    }
}
