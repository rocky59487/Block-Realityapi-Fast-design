package com.blockreality.api.vs2;

import com.blockreality.api.fragment.StructureFragment;
import com.blockreality.api.spi.IVS2Bridge;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fml.ModList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * VS2 bridge implementation — reflective, no hard compile-time dependency.
 *
 * <h3>Assembly strategy</h3>
 * <ol>
 *   <li>Verify VS2 is loaded at runtime.</li>
 *   <li>Obtain VS2's {@code ServerShipWorld} by casting {@code MinecraftServer}
 *       to {@code IShipObjectWorldServerProvider} via reflection.</li>
 *   <li>Choose an <em>anchor block</em> — the fragment block closest to its CoM —
 *       as the seed position for VS2 ship creation.</li>
 *   <li>Call {@code ServerShipWorld.createNewShipAtBlock(Vector3i, boolean, double,
 *       ResourceLocation)} to create a new VS2 ship at that position.
 *       VS2 will BFS-collect all adjacent blocks, assembling the entire fragment
 *       into the ship's chunk space automatically.</li>
 *   <li>Set {@code ServerShip.setLinearVelocity(Vector3d)} and
 *       {@code ServerShip.setAngularVelocity(Vector3d)} from the pre-computed
 *       initial velocities stored in the {@link StructureFragment}.</li>
 * </ol>
 *
 * <h3>Failure handling</h3>
 * Any reflection error or VS2 API mismatch is caught and logged at WARN level.
 * {@link #assembleAsShip} returns {@code false}, letting
 * {@code StructureFragmentManager} fall back to the built-in rigid-body path.
 * This ensures Block Reality works correctly even when VS2 updates its API.
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

    // VS2 interface / class FQNs (checked at runtime, not at compile time)
    private static final String CLS_SHIP_WORLD_PROVIDER =
        "org.valkyrienskies.mod.common.IShipObjectWorldServerProvider";
    private static final String CLS_VECTOR3I   = "org.joml.Vector3i";
    private static final String CLS_VECTOR3D   = "org.joml.Vector3d";

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded(VS2_MOD_ID);
    }

    @Override
    public boolean assembleAsShip(ServerLevel level, StructureFragment fragment) {
        if (!isAvailable()) return false;

        Map<BlockPos, BlockState> blocks = fragment.blockSnapshot();
        if (blocks.isEmpty()) return false;

        try {
            // Step 1 — obtain VS2 ServerShipWorld
            Object shipWorld = getShipObjectWorld(level);
            if (shipWorld == null) {
                LOGGER.warn("[BR-VS2Bridge] Cannot obtain VS2 ShipObjectWorld for {}",
                    level.dimension().location());
                return false;
            }

            // Step 2 — pick anchor: block nearest to CoM (best BFS seed for VS2)
            BlockPos anchor = nearestBlockToCoM(blocks, fragment);

            // Step 3 — create VS2 ship at anchor position
            Object ship = createShipAtBlock(shipWorld, anchor, level);
            if (ship == null) {
                LOGGER.warn("[BR-VS2Bridge] VS2 createNewShipAtBlock returned null at {}", anchor);
                return false;
            }

            // Step 4 — apply initial translational velocity (from BR physics)
            setLinearVelocity(ship, fragment.velX(), fragment.velY(), fragment.velZ());

            // Step 5 — apply initial angular velocity
            // For OVERTURNING collapses this is the physics-correct tipping ω;
            // for BM/shear failures it is the asymmetry-derived tumble.
            setAngularVelocity(ship,
                fragment.angVelX(), fragment.angVelY(), fragment.angVelZ());

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
            return false;
        }
    }

    // ─── Reflective VS2 API helpers ───────────────────────────────────────────

    /**
     * VS2 mixes {@code IShipObjectWorldServerProvider} into {@code MinecraftServer}
     * at startup via its own mixin. We access it reflectively to avoid a hard
     * compile-time dependency on VS2 classes.
     */
    private static Object getShipObjectWorld(ServerLevel level) throws Exception {
        Class<?> providerCls = Class.forName(CLS_SHIP_WORLD_PROVIDER);
        Object server = level.getServer();
        if (!providerCls.isInstance(server)) return null;
        Method getter = providerCls.getMethod("getShipObjectWorld");
        return getter.invoke(server);
    }

    /**
     * Calls VS2's {@code ServerShipWorld.createNewShipAtBlock(Vector3i, boolean, double,
     * ResourceLocation)}.
     *
     * <ul>
     *   <li>Position: anchor block coordinates (JOML {@code Vector3i}).</li>
     *   <li>isDynamic: {@code true} — ship participates in physics immediately.</li>
     *   <li>scale: {@code 1.0} — 1:1 block scale.</li>
     *   <li>dimensionId: dimension resource location of the collapse level.</li>
     * </ul>
     */
    private static Object createShipAtBlock(Object shipWorld, BlockPos anchor,
            ServerLevel level) throws Exception {
        Class<?> v3iCls = Class.forName(CLS_VECTOR3I);
        Object pos = v3iCls.getConstructor(int.class, int.class, int.class)
            .newInstance(anchor.getX(), anchor.getY(), anchor.getZ());

        // Locate the method — VS2 may name it createNewShipAtBlock or createNewShip
        Method createMethod = findMethod(shipWorld.getClass(),
            "createNewShipAtBlock",
            v3iCls, boolean.class, double.class, net.minecraft.resources.ResourceLocation.class);

        if (createMethod == null) {
            LOGGER.warn("[BR-VS2Bridge] Cannot find createNewShipAtBlock on {}",
                shipWorld.getClass().getName());
            return null;
        }
        return createMethod.invoke(shipWorld, pos, true, 1.0,
            level.dimension().location());
    }

    private static void setLinearVelocity(Object ship,
            double vx, double vy, double vz) throws Exception {
        Class<?> v3dCls = Class.forName(CLS_VECTOR3D);
        Object vel = v3dCls.getConstructor(double.class, double.class, double.class)
            .newInstance(vx, vy, vz);
        Method setter = findMethod(ship.getClass(), "setLinearVelocity", v3dCls);
        if (setter != null) setter.invoke(ship, vel);
    }

    private static void setAngularVelocity(Object ship,
            double wx, double wy, double wz) throws Exception {
        Class<?> v3dCls = Class.forName(CLS_VECTOR3D);
        Object vel = v3dCls.getConstructor(double.class, double.class, double.class)
            .newInstance(wx, wy, wz);
        Method setter = findMethod(ship.getClass(), "setAngularVelocity", v3dCls);
        if (setter != null) setter.invoke(ship, vel);
    }

    // ─── Utility ─────────────────────────────────────────────────────────────

    /**
     * Walk the class hierarchy (including interfaces) to find a method by name +
     * parameter types. Returns {@code null} if not found.
     */
    private static Method findMethod(Class<?> cls, String name, Class<?>... params) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {}
            for (Class<?> iface : c.getInterfaces()) {
                try {
                    Method m = iface.getMethod(name, params);
                    m.setAccessible(true);
                    return m;
                } catch (NoSuchMethodException ignored) {}
            }
        }
        return null;
    }

    /**
     * Select the fragment block whose world-centre is closest to the fragment's CoM.
     * This maximises the chance that VS2's BFS from the anchor reaches all blocks
     * rather than starting at a peripheral block and missing interior ones.
     */
    private static BlockPos nearestBlockToCoM(Map<BlockPos, BlockState> blocks,
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
}
