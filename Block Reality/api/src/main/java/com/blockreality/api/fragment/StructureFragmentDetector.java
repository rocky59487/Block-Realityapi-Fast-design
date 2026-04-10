package com.blockreality.api.fragment;

import com.blockreality.api.block.RBlockEntity;
import com.blockreality.api.event.RStructureCollapseEvent;
import com.blockreality.api.material.DefaultMaterial;
import com.blockreality.api.material.RMaterial;
import com.blockreality.api.physics.PhysicsConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.*;

/**
 * Structural Fragment Detection Engine.
 *
 * Triggered by {@link RStructureCollapseEvent} (fired by CollapseManager BEFORE
 * block removal). Runs a multi-root BFS flood-fill to find face-connected groups
 * within the collapsing block set. Each qualifying group becomes a
 * {@link StructureFragment} and is forwarded to {@link StructureFragmentManager}
 * for entity spawning.
 *
 * Algorithm (O(N) BFS, N = collapsingBlocks.size())
 * ──────────────────────────────────────────────────
 *   1. Snapshot BlockState + RMaterial for every collapsing block (world still intact).
 *   2. Build adjacency on the 6-connected face graph of {collapsingBlocks}.
 *   3. Flood-fill from each unvisited seed → connected component.
 *   4. For each component with MIN_FRAGMENT_BLOCKS ≤ size ≤ MAX_FRAGMENT_BLOCKS:
 *        a. Compute centre-of-mass (mass-weighted).
 *        b. Compute initial velocity (outward from collapse epicentre, downward bias).
 *        c. Compute initial angular velocity (from asymmetric mass distribution).
 *        d. Create StructureFragment and hand off to StructureFragmentManager.
 *
 * This is independent of Valkyrien Skies (no ship-assembly model) and Create
 * (no mechanical link graph): fragmentation is a pure structural consequence.
 */
@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class StructureFragmentDetector {

    private StructureFragmentDetector() {}

    // 6-face neighbour offsets
    private static final int[] DX = { 1,-1, 0, 0, 0, 0 };
    private static final int[] DY = { 0, 0, 1,-1, 0, 0 };
    private static final int[] DZ = { 0, 0, 0, 0, 1,-1 };

    /**
     * Main entry point — fires at HIGH priority so fragments are registered
     * before CollapseManager processes individual block removals.
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onStructureCollapse(RStructureCollapseEvent event) {
        ServerLevel level = event.getLevel();
        if (level == null) return;

        Set<BlockPos> collapsing = event.getCollapsingBlocks();
        if (collapsing == null || collapsing.size() < StructureFragment.MIN_FRAGMENT_BLOCKS) return;

        // ─── 1. Snapshot before removal ───
        Map<BlockPos, BlockState> stateSnap = new HashMap<>(collapsing.size());
        Map<BlockPos, RMaterial>  matSnap   = new HashMap<>(collapsing.size());
        for (BlockPos pos : collapsing) {
            BlockState bs = level.getBlockState(pos);
            if (bs.isAir()) continue;
            stateSnap.put(pos, bs);
            matSnap.put(pos, resolveMaterial(level, pos));
        }
        if (stateSnap.isEmpty()) return;

        // ─── 2. Connected-component BFS ───
        List<Set<BlockPos>> components = findConnectedComponents(stateSnap.keySet());

        BlockPos trigger = event.getTriggerPos();

        // ─── 3. Emit fragments for qualifying components ───
        for (Set<BlockPos> comp : components) {
            if (comp.size() < StructureFragment.MIN_FRAGMENT_BLOCKS) continue;

            Map<BlockPos, BlockState> compStates = new HashMap<>(comp.size());
            Map<BlockPos, RMaterial>  compMats   = new HashMap<>(comp.size());
            for (BlockPos p : comp) {
                compStates.put(p, stateSnap.get(p));
                compMats.put(p, matSnap.getOrDefault(p, DefaultMaterial.CONCRETE));
            }

            double totalMass = computeTotalMass(comp, compMats);
            double[] com     = computeCoM(comp, compMats, totalMass);
            float[]  vel     = computeInitialVelocity(com, trigger);
            float[]  angVel  = computeInitialAngVel(comp, com, vel);

            StructureFragment frag = new StructureFragment(
                UUID.randomUUID(),
                compStates,
                compMats,
                com[0], com[1], com[2],
                totalMass,
                vel[0], vel[1], vel[2],
                angVel[0], angVel[1], angVel[2],
                level.getGameTime()
            );

            if (frag.isEntityEligible()) {
                StructureFragmentManager.get(level).enqueue(frag);
            }
            // Oversized: CollapseManager handles as individual rubble (intentional)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  BFS component detection (O(N))
    // ═══════════════════════════════════════════════════════════════

    private static List<Set<BlockPos>> findConnectedComponents(Set<BlockPos> positions) {
        Set<BlockPos>           unvisited  = new HashSet<>(positions);
        List<Set<BlockPos>>     components = new ArrayList<>();

        while (!unvisited.isEmpty()) {
            BlockPos          seed  = unvisited.iterator().next();
            Set<BlockPos>     comp  = new LinkedHashSet<>();
            Deque<BlockPos>   queue = new ArrayDeque<>();
            queue.add(seed);
            unvisited.remove(seed);

            while (!queue.isEmpty()) {
                BlockPos curr = queue.poll();
                comp.add(curr);
                for (int i = 0; i < 6; i++) {
                    BlockPos nb = new BlockPos(
                        curr.getX() + DX[i],
                        curr.getY() + DY[i],
                        curr.getZ() + DZ[i]
                    );
                    if (unvisited.remove(nb)) queue.add(nb);
                }
            }
            components.add(comp);
        }
        return components;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Physics initial conditions
    // ═══════════════════════════════════════════════════════════════

    private static double computeTotalMass(Set<BlockPos> blocks, Map<BlockPos, RMaterial> mats) {
        double mass = 0;
        for (BlockPos p : blocks)
            mass += mats.getOrDefault(p, DefaultMaterial.CONCRETE).getDensity()
                  * PhysicsConstants.BLOCK_AREA; // 1 m³ per voxel (BLOCK_AREA = 1 m²)
        return Math.max(mass, 1.0);
    }

    /** Mass-weighted centre of mass (world space, block-corner to block-centre offset). */
    private static double[] computeCoM(Set<BlockPos> blocks, Map<BlockPos, RMaterial> mats, double totalMass) {
        double cx = 0, cy = 0, cz = 0;
        for (BlockPos p : blocks) {
            double m = mats.getOrDefault(p, DefaultMaterial.CONCRETE).getDensity()
                     * PhysicsConstants.BLOCK_AREA;
            cx += (p.getX() + 0.5) * m;
            cy += (p.getY() + 0.5) * m;
            cz += (p.getZ() + 0.5) * m;
        }
        return new double[]{ cx / totalMass, cy / totalMass, cz / totalMass };
    }

    /**
     * Initial translational velocity:
     *   - Downward bias  : -1.5 m/s (gravity pre-applied so fragment arcs immediately)
     *   - Lateral bias   : away from collapse epicentre (feels explosive)
     *   - Magnitude caps : 3 m/s lateral, -4 m/s vertical
     */
    private static float[] computeInitialVelocity(double[] com, BlockPos trigger) {
        double dx = com[0] - (trigger.getX() + 0.5);
        double dz = com[2] - (trigger.getZ() + 0.5);
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.5) dist = 0.5;

        float lateralSpd = (float) Math.min(2.5, 3.0 / dist);
        return new float[]{
            (float)(dx / dist) * lateralSpd,
            -1.5f,
            (float)(dz / dist) * lateralSpd
        };
    }

    /**
     * Initial angular velocity derived from the spatial asymmetry of the fragment.
     * Off-centre mass distribution generates a torque arm; combined with initial
     * translational velocity this creates realistic tumbling.
     *
     * ω_x ∝ σ_z  (variance of Z positions → tumble around X axis)
     * ω_z ∝ σ_x  (variance of X positions → tumble around Z axis)
     */
    private static float[] computeInitialAngVel(Set<BlockPos> blocks, double[] com, float[] vel) {
        double varX = 0, varZ = 0;
        for (BlockPos p : blocks) {
            double ddx = (p.getX() + 0.5) - com[0];
            double ddz = (p.getZ() + 0.5) - com[2];
            varX += ddx * ddx;
            varZ += ddz * ddz;
        }
        double scale = 0.8 / Math.sqrt(blocks.size());
        // Direction: cross velocity with world-up approximation
        float ax = (float)(Math.signum(vel[2]) * Math.sqrt(varZ) * scale);
        float az = (float)(-Math.signum(vel[0]) * Math.sqrt(varX) * scale);
        return new float[]{ ax, 0.0f, az };
    }

    // ─── Material lookup ───

    private static RMaterial resolveMaterial(ServerLevel level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof RBlockEntity rbe)
            return rbe.getMaterial();
        return DefaultMaterial.CONCRETE;
    }
}
