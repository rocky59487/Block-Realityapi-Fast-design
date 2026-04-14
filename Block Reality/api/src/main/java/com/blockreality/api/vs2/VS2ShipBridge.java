package com.blockreality.api.vs2;

import com.blockreality.api.fragment.StructureFragment;
import com.blockreality.api.spi.IVS2Bridge;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * VS2 bridge implementation — reflective, no hard compile-time dependency.
 *
 * <h3>Assembly strategy</h3>
 * <ol>
 *   <li>Verify VS2 is loaded at runtime (cached once in constructor).</li>
 *   <li>Obtain VS2's {@code ServerShipWorld} by casting {@code MinecraftServer}
 *       to {@code IShipObjectWorldServerProvider} via reflection.</li>
 *   <li>Choose an <em>anchor block</em> — the fragment block closest to its CoM —
 *       as the seed position for VS2 ship creation.</li>
 *   <li>Call {@code ServerShipWorld.createNewShipAtBlock(Vector3i, boolean, double,
 *       ResourceLocation)} to create a new VS2 ship at that position.</li>
 *   <li>Set {@code ServerShip.setLinearVelocity(Vector3d)} and
 *       {@code ServerShip.setAngularVelocity(Vector3d)} from the pre-computed
 *       initial velocities stored in the {@link StructureFragment}.</li>
 * </ol>
 *
 * <h3>Reflection caching</h3>
 * All {@code Class}, {@code Constructor}, and {@code Method} references are resolved
 * once on first use via {@link #resolveReflection()} and cached in static fields.
 * Subsequent calls pay zero reflection-lookup cost.
 *
 * <h3>Circuit breaker</h3>
 * After {@link #MAX_CONSECUTIVE_FAILURES} consecutive assembly failures, the bridge
 * enters a cooldown period ({@link #COOLDOWN_TICKS} ticks). During cooldown,
 * {@link #isAvailable()} returns {@code false}, preventing repeated WARN log spam
 * and letting the fallback path handle all fragments. The breaker resets automatically
 * after the cooldown expires, and on any successful assembly.
 *
 * <h3>Ship lifecycle tracking</h3>
 * After successful assembly, the VS2 ship is tracked in {@link #activeShips}.
 * {@link #tickActiveShips(ServerLevel)} monitors each ship's velocity; when a ship
 * becomes stationary (velocity below threshold for {@link #SETTLE_TICKS} consecutive
 * ticks), it is disassembled and its blocks are placed as rubble in the world.
 * A hard lifetime cap ({@link #MAX_SHIP_LIFETIME_TICKS}) prevents memory leaks.
 *
 * <h3>Failure handling</h3>
 * Any reflection error or VS2 API mismatch is caught and logged at WARN level.
 * {@link #assembleAsShip} returns {@code false}, letting
 * {@code StructureFragmentManager} fall back to the built-in rigid-body path.
 *
 * <h3>Static analysis isolation</h3>
 * This class is only ever invoked from
 * {@link com.blockreality.api.fragment.StructureFragmentManager#spawnFragment},
 * which is downstream of the entire static load analysis pipeline
 * (PFSF → CollapseManager → StructureFragmentDetector). No static analysis
 * class is modified by this bridge.
 */
public final class VS2ShipBridge implements IVS2Bridge {

    private static final Logger LOGGER = LogManager.getLogger("BR-VS2Bridge");
    private static final String VS2_MOD_ID = "valkyrienskies";

    // ─── VS2 FQNs (checked at runtime, not at compile time) ───

    private static final String CLS_SHIP_WORLD_PROVIDER =
        "org.valkyrienskies.mod.common.IShipObjectWorldServerProvider";
    private static final String CLS_VECTOR3I = "org.joml.Vector3i";
    private static final String CLS_VECTOR3D = "org.joml.Vector3d";

    // ─── Reflection cache (resolved once, reused forever) ───

    private static volatile boolean reflectionResolved = false;
    private static Class<?>       cachedProviderCls;
    private static Class<?>       cachedV3iCls;
    private static Class<?>       cachedV3dCls;
    private static Constructor<?> cachedV3iCtor;
    private static Constructor<?> cachedV3dCtor;
    private static Method         cachedGetShipWorld;

    /**
     * {@code createNewShipAtBlock} is resolved lazily on the first
     * {@code assembleAsShip} call because it requires the concrete
     * {@code ServerShipWorld} class, which is only available after the
     * provider interface has been invoked once.
     */
    private static volatile Method cachedCreateShip;
    private static volatile Class<?> cachedShipWorldClass;

    // ─── Circuit breaker ───

    /** Max consecutive failures before entering cooldown. */
    private static final int MAX_CONSECUTIVE_FAILURES = 3;
    /** Cooldown duration in ticks (30 seconds at 20 TPS). */
    private static final long COOLDOWN_TICKS = 600;

    private int  consecutiveFailures = 0;
    private long cooldownUntilTick   = 0;

    // ─── Ship lifecycle tracking ───

    /** Ticks a ship must remain below velocity threshold to be considered settled. */
    private static final int SETTLE_TICKS = 40;
    /** Velocity magnitude threshold (m/s) for settle detection. */
    private static final double SETTLE_VEL_THRESHOLD = 0.05;
    /** Hard lifetime cap for tracked ships (30 seconds). */
    private static final int MAX_SHIP_LIFETIME_TICKS = 600;

    private final Map<UUID, VS2ShipEntry> activeShips = new LinkedHashMap<>();

    // ─── Instance state ───

    /** Cached at construction time — VS2 cannot be unloaded at runtime. */
    private final boolean modPresent;
    /** Set to true after the first successful assembly (for one-time INFO log). */
    private boolean firstSuccessLogged = false;

    // ═══════════════════════════════════════════════════════════════
    //  Construction
    // ═══════════════════════════════════════════════════════════════

    public VS2ShipBridge() {
        this.modPresent = ModList.get().isLoaded(VS2_MOD_ID);
        if (modPresent) {
            resolveReflection();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  IVS2Bridge implementation
    // ═══════════════════════════════════════════════════════════════

    @Override
    public boolean isAvailable() {
        if (!modPresent || !reflectionResolved || cachedProviderCls == null) return false;

        // Circuit breaker: if too many consecutive failures, pause for a while
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            var server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) return false;
            long currentTick = server.getTickCount();
            if (currentTick < cooldownUntilTick) return false;
            // Cooldown expired — reset and retry
            consecutiveFailures = 0;
            LOGGER.info("[BR-VS2Bridge] Cooldown expired after {} ticks, retrying VS2 assembly",
                COOLDOWN_TICKS);
        }
        return true;
    }

    @Override
    public boolean assembleAsShip(ServerLevel level, StructureFragment fragment) {
        // Caller (StructureFragmentManager) already checked isAvailable();
        // no redundant check here.

        Map<BlockPos, BlockState> blocks = fragment.blockSnapshot();
        if (blocks.isEmpty()) return false;

        try {
            // Step 1 — obtain VS2 ServerShipWorld
            Object shipWorld = getShipObjectWorld(level);
            if (shipWorld == null) {
                LOGGER.warn("[BR-VS2Bridge] Cannot obtain VS2 ShipObjectWorld for {}",
                    level.dimension().location());
                onFailure(level);
                return false;
            }

            // Step 2 — pick anchor: block nearest to CoM (best BFS seed for VS2)
            BlockPos anchor = nearestBlockToCoM(blocks, fragment);

            // B5: Validate anchor block still exists in the world
            BlockState anchorState = level.getBlockState(anchor);
            if (anchorState.isAir()) {
                LOGGER.warn("[BR-VS2Bridge] Anchor block at {} is air (removed before assembly), " +
                    "falling back", anchor);
                onFailure(level);
                return false;
            }

            // Step 3 — create VS2 ship at anchor position
            Object ship = createShipAtBlock(shipWorld, anchor, level);
            if (ship == null) {
                LOGGER.warn("[BR-VS2Bridge] VS2 createNewShipAtBlock returned null at {}", anchor);
                onFailure(level);
                return false;
            }

            // Step 4 — apply initial translational velocity (from BR physics)
            setLinearVelocity(ship, fragment.velX(), fragment.velY(), fragment.velZ());

            // Step 5 — apply initial angular velocity
            // For OVERTURNING collapses this is the physics-correct tipping ω;
            // for BM/shear failures it is the asymmetry-derived tumble.
            setAngularVelocity(ship,
                fragment.angVelX(), fragment.angVelY(), fragment.angVelZ());

            // Step 6 — attempt to set mass from BR's material data (best effort)
            trySetMass(ship, fragment.totalMass());

            // Success — reset circuit breaker
            consecutiveFailures = 0;

            // Track ship for lifecycle management (settle detection)
            activeShips.put(fragment.id(), new VS2ShipEntry(
                fragment.id(), ship, fragment, 0, 0));

            if (!firstSuccessLogged) {
                LOGGER.info("[BR-VS2Bridge] First successful VS2 ship assembly — " +
                    "bridge operational ({} blocks, mass={} kg)",
                    blocks.size(), (int) fragment.totalMass());
                firstSuccessLogged = true;
            }

            LOGGER.debug("[BR-VS2Bridge] Fragment {} ({} blocks) → VS2 ship, " +
                "v=({},{},{}) ω=({},{},{})",
                fragment.id(), blocks.size(),
                fragment.velX(), fragment.velY(), fragment.velZ(),
                fragment.angVelX(), fragment.angVelY(), fragment.angVelZ());
            return true;

        } catch (Exception e) {
            LOGGER.warn("[BR-VS2Bridge] Ship assembly failed for fragment {}, " +
                "falling back to StructureFragmentEntity: {}", fragment.id(), e.getMessage());
            LOGGER.debug("[BR-VS2Bridge] Stack trace:", e);
            onFailure(level);
            return false;
        }
    }

    @Override
    public void tickActiveShips(ServerLevel level) {
        if (activeShips.isEmpty()) return;

        Iterator<Map.Entry<UUID, VS2ShipEntry>> iter = activeShips.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<UUID, VS2ShipEntry> entry = iter.next();
            VS2ShipEntry se = entry.getValue();
            se.totalTicks++;

            // Hard lifetime cap — discard without rubble placement to prevent leaks
            if (se.totalTicks > MAX_SHIP_LIFETIME_TICKS) {
                LOGGER.debug("[BR-VS2Bridge] Ship {} exceeded max lifetime ({} ticks), removing",
                    se.fragmentId, MAX_SHIP_LIFETIME_TICKS);
                tryDestroyShip(se.shipRef);
                iter.remove();
                continue;
            }

            // Read ship velocity via reflection (best effort)
            double speed = readShipSpeed(se.shipRef);
            if (speed < 0) {
                // Reflection failed — can't monitor, keep tracking with timeout only
                continue;
            }

            if (speed < SETTLE_VEL_THRESHOLD) {
                se.settleCounter++;
            } else {
                se.settleCounter = 0;
            }

            // Settled — place rubble blocks and destroy VS2 ship
            if (se.settleCounter >= SETTLE_TICKS) {
                LOGGER.debug("[BR-VS2Bridge] Ship {} settled after {} ticks, placing rubble",
                    se.fragmentId, se.totalTicks);
                placeRubbleFromShip(level, se);
                tryDestroyShip(se.shipRef);
                iter.remove();
            }
        }
    }

    @Override
    public int getActiveShipCount() {
        return activeShips.size();
    }

    // ═══════════════════════════════════════════════════════════════
    //  Ship query API
    // ═══════════════════════════════════════════════════════════════

    @Override
    @Nullable
    public ShipDataSnapshot getShipSnapshot(UUID fragmentId) {
        VS2ShipEntry entry = activeShips.get(fragmentId);
        if (entry == null) return null;
        return buildSnapshot(entry);
    }

    @Override
    public List<ShipDataSnapshot> getAllShipSnapshots() {
        if (activeShips.isEmpty()) return List.of();
        List<ShipDataSnapshot> snapshots = new ArrayList<>(activeShips.size());
        for (VS2ShipEntry entry : activeShips.values()) {
            ShipDataSnapshot snap = buildSnapshot(entry);
            if (snap != null) snapshots.add(snap);
        }
        return Collections.unmodifiableList(snapshots);
    }

    /**
     * Build a {@link ShipDataSnapshot} from a tracking entry.
     * Position is read via VS2's {@code getTransform().getPositionInWorld()} (best-effort).
     * Falls back to the original CoM position from the fragment if reflection fails.
     */
    @Nullable
    private ShipDataSnapshot buildSnapshot(VS2ShipEntry entry) {
        // Read position (best-effort via reflection — getTransform → positionInWorld)
        double px = entry.fragment.comX();
        double py = entry.fragment.comY();
        double pz = entry.fragment.comZ();
        try {
            Object transform = readShipTransform(entry.shipRef);
            if (transform != null) {
                double[] pos = readVec3d(transform, "getPositionInWorld");
                if (pos != null) { px = pos[0]; py = pos[1]; pz = pos[2]; }
            }
        } catch (Exception ignored) { /* fall back to fragment CoM */ }

        // Read velocity (already used by settle detection)
        double vx = 0, vy = 0, vz = 0;
        try {
            Method getVel = findMethod(entry.shipRef.getClass(), "getLinearVelocity");
            if (getVel != null) {
                Object vel = getVel.invoke(entry.shipRef);
                if (vel != null) {
                    vx = (double) vel.getClass().getMethod("x").invoke(vel);
                    vy = (double) vel.getClass().getMethod("y").invoke(vel);
                    vz = (double) vel.getClass().getMethod("z").invoke(vel);
                }
            }
        } catch (Exception ignored) { /* velocity stays 0 */ }

        double speed = Math.sqrt(vx * vx + vy * vy + vz * vz);
        int blockCount = entry.fragment.blockSnapshot().size();

        return new ShipDataSnapshot(
            entry.fragmentId,
            px, py, pz,
            vx, vy, vz,
            speed,
            blockCount,
            entry.totalTicks,
            entry.settleCounter
        );
    }

    // ═══════════════════════════════════════════════════════════════
    //  Force application API
    // ═══════════════════════════════════════════════════════════════

    /**
     * Apply a world-space force impulse to a tracked VS2 ship.
     *
     * <p>Strategy (tried in order):
     * <ol>
     *   <li>{@code ship.applyImpulse(Vector3d)} — used in VS2 ≥ 2.3.0-beta.1</li>
     *   <li>{@code ship.applyForce(Vector3d)} — older VS2 physics API</li>
     *   <li>Force via {@code getShipData().setLinearMomentum()} — last resort</li>
     * </ol>
     *
     * <p>All paths are best-effort. If none match the installed VS2 version,
     * the call is silently ignored and {@code false} returned.
     */
    @Override
    public boolean applyForceToShip(UUID fragmentId, double fx, double fy, double fz) {
        VS2ShipEntry entry = activeShips.get(fragmentId);
        if (entry == null || entry.shipRef == null) return false;
        if (cachedV3dCls == null || cachedV3dCtor == null) return false;

        try {
            Object forceVec = cachedV3dCtor.newInstance(fx, fy, fz);

            // Try applyImpulse first (VS2 ≥ 2.3.0)
            Method applyImpulse = findMethod(entry.shipRef.getClass(), "applyImpulse", cachedV3dCls);
            if (applyImpulse != null) {
                applyImpulse.invoke(entry.shipRef, forceVec);
                LOGGER.debug("[BR-VS2Bridge] applyImpulse({},{},{}) → ship {}",
                    fx, fy, fz, entry.fragmentId);
                return true;
            }

            // Try applyForce (older VS2)
            Method applyForce = findMethod(entry.shipRef.getClass(), "applyForce", cachedV3dCls);
            if (applyForce != null) {
                applyForce.invoke(entry.shipRef, forceVec);
                LOGGER.debug("[BR-VS2Bridge] applyForce({},{},{}) → ship {}",
                    fx, fy, fz, entry.fragmentId);
                return true;
            }

            // Fallback: add impulse via linear momentum (F = Δp ≈ m·Δv)
            Method getData = findMethod(entry.shipRef.getClass(), "getShipData");
            if (getData != null) {
                Object data = getData.invoke(entry.shipRef);
                if (data != null) {
                    Method getMomentum = findMethod(data.getClass(), "getLinearMomentum");
                    Method setMomentum = findMethod(data.getClass(), "setLinearMomentum", cachedV3dCls);
                    if (getMomentum != null && setMomentum != null) {
                        Object p = getMomentum.invoke(data);
                        if (p != null) {
                            double px2 = (double) p.getClass().getMethod("x").invoke(p) + fx;
                            double py2 = (double) p.getClass().getMethod("y").invoke(p) + fy;
                            double pz2 = (double) p.getClass().getMethod("z").invoke(p) + fz;
                            Object newP = cachedV3dCtor.newInstance(px2, py2, pz2);
                            setMomentum.invoke(data, newP);
                            LOGGER.debug("[BR-VS2Bridge] momentum-impulse ({},{},{}) → ship {}",
                                fx, fy, fz, entry.fragmentId);
                            return true;
                        }
                    }
                }
            }

            LOGGER.debug("[BR-VS2Bridge] applyForceToShip: no suitable VS2 API found for force application");
            return false;

        } catch (Exception e) {
            LOGGER.debug("[BR-VS2Bridge] applyForceToShip failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public String getBridgeDiagnostics() {
        if (!modPresent) return "VS2Bridge[vs2_not_installed]";
        if (!reflectionResolved || cachedProviderCls == null)
            return "VS2Bridge[reflection_failed]";
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            var server = ServerLifecycleHooks.getCurrentServer();
            long tick = server != null ? server.getTickCount() : 0;
            long remaining = Math.max(0, cooldownUntilTick - tick);
            return String.format("VS2Bridge[circuit_open failures=%d cooldown=%dt]",
                consecutiveFailures, remaining);
        }
        return String.format("VS2Bridge[ok activeShips=%d failures=%d]",
            activeShips.size(), consecutiveFailures);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Circuit breaker helper
    // ═══════════════════════════════════════════════════════════════

    private void onFailure(ServerLevel level) {
        consecutiveFailures++;
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            var server = level.getServer();
            cooldownUntilTick = server.getTickCount() + COOLDOWN_TICKS;
            LOGGER.warn("[BR-VS2Bridge] {} consecutive failures — entering cooldown for {} ticks " +
                "(falling back to StructureFragmentEntity)", consecutiveFailures, COOLDOWN_TICKS);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Reflection resolution (one-time)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Resolve all VS2 reflection targets once. Thread-safe via synchronized.
     * On failure, fields remain {@code null} and {@link #isAvailable()} returns false.
     */
    private static synchronized void resolveReflection() {
        if (reflectionResolved) return;
        try {
            cachedProviderCls = Class.forName(CLS_SHIP_WORLD_PROVIDER);
            cachedV3iCls      = Class.forName(CLS_VECTOR3I);
            cachedV3dCls      = Class.forName(CLS_VECTOR3D);

            cachedV3iCtor = cachedV3iCls.getConstructor(int.class, int.class, int.class);
            cachedV3dCtor = cachedV3dCls.getConstructor(double.class, double.class, double.class);

            cachedGetShipWorld = cachedProviderCls.getMethod("getShipObjectWorld");

            LOGGER.debug("[BR-VS2Bridge] Reflection resolved: provider={}, V3i={}, V3d={}",
                cachedProviderCls.getName(), cachedV3iCls.getName(), cachedV3dCls.getName());
        } catch (Exception e) {
            LOGGER.warn("[BR-VS2Bridge] Reflection resolution failed — VS2 API may have changed", e);
            cachedProviderCls = null; // signals isAvailable() → false
        } finally {
            reflectionResolved = true;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Reflective VS2 API helpers (using cached references)
    // ═══════════════════════════════════════════════════════════════

    /**
     * VS2 mixes {@code IShipObjectWorldServerProvider} into {@code MinecraftServer}
     * at startup via its own mixin. We access it reflectively to avoid a hard
     * compile-time dependency on VS2 classes.
     */
    private static Object getShipObjectWorld(ServerLevel level) throws Exception {
        Object server = level.getServer();
        if (!cachedProviderCls.isInstance(server)) return null;
        return cachedGetShipWorld.invoke(server);
    }

    /**
     * Calls VS2's {@code ServerShipWorld.createNewShipAtBlock(Vector3i, boolean, double,
     * ResourceLocation)}.
     *
     * <p>The {@code createNewShipAtBlock} method is resolved lazily on first call
     * because we need the concrete runtime class of the ship world object.
     */
    private static Object createShipAtBlock(Object shipWorld, BlockPos anchor,
            ServerLevel level) throws Exception {
        Object pos = cachedV3iCtor.newInstance(anchor.getX(), anchor.getY(), anchor.getZ());

        // Lazy-resolve createNewShipAtBlock on the concrete shipWorld class
        Method createMethod = cachedCreateShip;
        if (createMethod == null || cachedShipWorldClass != shipWorld.getClass()) {
            createMethod = findMethod(shipWorld.getClass(),
                "createNewShipAtBlock",
                cachedV3iCls, boolean.class, double.class,
                net.minecraft.resources.ResourceLocation.class);
            if (createMethod == null) {
                LOGGER.warn("[BR-VS2Bridge] Cannot find createNewShipAtBlock on {}",
                    shipWorld.getClass().getName());
                return null;
            }
            cachedCreateShip = createMethod;
            cachedShipWorldClass = shipWorld.getClass();
        }

        return createMethod.invoke(shipWorld, pos, true, 1.0,
            level.dimension().location());
    }

    private static void setLinearVelocity(Object ship,
            double vx, double vy, double vz) throws Exception {
        Object vel = cachedV3dCtor.newInstance(vx, vy, vz);
        Method setter = findMethod(ship.getClass(), "setLinearVelocity", cachedV3dCls);
        if (setter != null) setter.invoke(ship, vel);
    }

    private static void setAngularVelocity(Object ship,
            double wx, double wy, double wz) throws Exception {
        Object vel = cachedV3dCtor.newInstance(wx, wy, wz);
        Method setter = findMethod(ship.getClass(), "setAngularVelocity", cachedV3dCls);
        if (setter != null) setter.invoke(ship, vel);
    }

    /**
     * B2: Attempt to set ship mass from Block Reality's material-based calculation.
     * Best-effort — if VS2's API doesn't expose a mass setter, this is silently skipped.
     */
    private static void trySetMass(Object ship, double mass) {
        try {
            // Try direct setMass(double) first
            Method setMass = findMethod(ship.getClass(), "setMass", double.class);
            if (setMass != null) {
                setMass.invoke(ship, mass);
                return;
            }
            // Try getShipData() → ShipInertiaData path
            Method getData = findMethod(ship.getClass(), "getShipData");
            if (getData != null) {
                Object data = getData.invoke(ship);
                if (data != null) {
                    Method setDataMass = findMethod(data.getClass(), "setMass", double.class);
                    if (setDataMass != null) {
                        setDataMass.invoke(data, mass);
                    }
                }
            }
        } catch (Exception e) {
            // Mass setting is best-effort — VS2 will compute its own mass from blocks
            LOGGER.debug("[BR-VS2Bridge] Could not set ship mass ({}): {}", mass, e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Ship lifecycle helpers
    // ═══════════════════════════════════════════════════════════════

    /**
     * Read VS2 ship transform object via reflection.
     * VS2 ships implement {@code Ship.getTransform()} returning a {@code ShipTransform}.
     *
     * @return {@code ShipTransform} object, or null if not available
     */
    @Nullable
    private static Object readShipTransform(Object ship) {
        try {
            Method getTransform = findMethod(ship.getClass(), "getTransform");
            if (getTransform == null) return null;
            return getTransform.invoke(ship);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Read a named {@code Vector3d}-returning method from an object via reflection.
     * JOML {@code Vector3d} exposes components via {@code x()}, {@code y()}, {@code z()}.
     *
     * @param obj        target object (e.g., ShipTransform)
     * @param methodName method to call (e.g., "getPositionInWorld")
     * @return {@code double[]{x, y, z}}, or null if reflection fails
     */
    @Nullable
    private static double[] readVec3d(Object obj, String methodName) {
        try {
            Method m = findMethod(obj.getClass(), methodName);
            if (m == null) return null;
            Object vec = m.invoke(obj);
            if (vec == null) return null;
            double x = (double) vec.getClass().getMethod("x").invoke(vec);
            double y = (double) vec.getClass().getMethod("y").invoke(vec);
            double z = (double) vec.getClass().getMethod("z").invoke(vec);
            return new double[]{x, y, z};
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Read the linear speed of a VS2 ship via reflection.
     * @return speed magnitude, or -1 if reflection fails
     */
    private static double readShipSpeed(Object ship) {
        try {
            Method getVel = findMethod(ship.getClass(), "getLinearVelocity");
            if (getVel == null) return -1;
            Object vel = getVel.invoke(ship);
            if (vel == null) return -1;

            // JOML Vector3d has x(), y(), z() methods
            Method xm = vel.getClass().getMethod("x");
            Method ym = vel.getClass().getMethod("y");
            Method zm = vel.getClass().getMethod("z");
            double vx = (double) xm.invoke(vel);
            double vy = (double) ym.invoke(vel);
            double vz = (double) zm.invoke(vel);
            return Math.sqrt(vx * vx + vy * vy + vz * vz);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Place rubble blocks from a settled VS2 ship's fragment snapshot.
     * Uses the original block snapshot (world-space) from the StructureFragment.
     * Only places blocks into currently-air positions to avoid overwriting.
     */
    private static void placeRubbleFromShip(ServerLevel level, VS2ShipEntry entry) {
        StructureFragment frag = entry.fragment;
        int placed = 0;
        for (Map.Entry<BlockPos, BlockState> e : frag.blockSnapshot().entrySet()) {
            BlockPos pos = e.getKey();
            BlockState state = e.getValue();
            if (state == null || state.isAir()) continue;
            if (!level.isLoaded(pos)) continue;
            if (level.getBlockState(pos).isAir()) {
                level.setBlock(pos, state, 3 /* UPDATE_ALL */);
                placed++;
            }
        }
        if (placed > 0) {
            LOGGER.debug("[BR-VS2Bridge] Placed {} rubble blocks for settled ship {}",
                placed, entry.fragmentId);
        }
    }

    /**
     * Attempt to destroy/disassemble a VS2 ship. Best-effort via reflection.
     */
    private static void tryDestroyShip(Object ship) {
        try {
            // Try common VS2 ship removal methods
            Method destroy = findMethod(ship.getClass(), "destroy");
            if (destroy != null) {
                destroy.invoke(ship);
                return;
            }
            Method disassemble = findMethod(ship.getClass(), "disassemble");
            if (disassemble != null) {
                disassemble.invoke(ship);
            }
        } catch (Exception e) {
            LOGGER.debug("[BR-VS2Bridge] Could not destroy VS2 ship: {}", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Utility
    // ═══════════════════════════════════════════════════════════════

    /**
     * Find a method by name + parameter types on a class.
     *
     * <p>Strategy:
     * <ol>
     *   <li>Try {@code cls.getMethod()} — searches entire hierarchy including
     *       interfaces. Finds public methods only. This is the common case for VS2.</li>
     *   <li>If not found, fall back to {@code getDeclaredMethod()} walking the
     *       superclass chain to find package-private / protected methods.</li>
     * </ol>
     *
     * @return the method, or {@code null} if not found anywhere
     */
    static Method findMethod(Class<?> cls, String name, Class<?>... params) {
        // Fast path: public method (getMethod searches full hierarchy + interfaces)
        try {
            return cls.getMethod(name, params);
        } catch (NoSuchMethodException ignored) {}

        // Slow path: walk superclass chain for non-public methods
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name, params);
                try {
                    m.setAccessible(true);
                } catch (Exception accessEx) {
                    // Java 17 module system may block setAccessible — log and try anyway
                    LOGGER.debug("[BR-VS2Bridge] setAccessible failed for {}.{}: {}",
                        c.getSimpleName(), name, accessEx.getMessage());
                }
                return m;
            } catch (NoSuchMethodException ignored) {}
        }
        return null;
    }

    /**
     * Select the fragment block whose world-centre is closest to the fragment's CoM.
     * This maximises the chance that VS2's BFS from the anchor reaches all blocks
     * rather than starting at a peripheral block and missing interior ones.
     */
    static BlockPos nearestBlockToCoM(Map<BlockPos, BlockState> blocks,
            StructureFragment fragment) {
        double cx = fragment.comX(), cy = fragment.comY(), cz = fragment.comZ();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos p : blocks.keySet()) {
            double dx = p.getX() + 0.5 - cx;
            double dy = p.getY() + 0.5 - cy;
            double dz = p.getZ() + 0.5 - cz;
            double d = dx * dx + dy * dy + dz * dz;
            if (d < bestDist) { bestDist = d; best = p; }
        }
        return best != null ? best : blocks.keySet().iterator().next();
    }

    // ═══════════════════════════════════════════════════════════════
    //  Ship tracking entry
    // ═══════════════════════════════════════════════════════════════

    /**
     * Mutable tracking entry for an active VS2 ship created from a BR fragment.
     */
    private static final class VS2ShipEntry {
        final UUID fragmentId;
        final Object shipRef;
        final StructureFragment fragment;
        int settleCounter;
        int totalTicks;

        VS2ShipEntry(UUID fragmentId, Object shipRef, StructureFragment fragment,
                     int settleCounter, int totalTicks) {
            this.fragmentId    = fragmentId;
            this.shipRef       = shipRef;
            this.fragment      = fragment;
            this.settleCounter = settleCounter;
            this.totalTicks    = totalTicks;
        }
    }
}
