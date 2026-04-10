package com.blockreality.api.fragment;

import com.blockreality.api.material.RMaterial;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable data snapshot of a structurally disconnected chunk of blocks.
 *
 * Design rationale (why not Valkyrien Skies / Create approach):
 *   - VS: ship-assembly model — player explicitly designates a structure as a "ship"
 *   - Create: mechanical adhesion — contraptions are intentionally constructed
 *   - Ours: consequence-driven — fragments arise ONLY when ForceEquilibriumSolver /
 *     PFSF engine detects loss of all anchor paths; no player action needed.
 *     This means any player-built structure (ship, vehicle, building) automatically
 *     fragments realistically on failure, with zero extra player effort.
 *
 * Lifecycle:
 *   StructureFragmentDetector → StructureFragment → StructureFragmentEntity (physics tick)
 *   → settle → place rubble blocks / fire StructureFragmentSettleEvent
 *
 * @param id              Unique identifier (used to correlate server entity + CollapseJournal)
 * @param blockSnapshot   World-space block positions → visual BlockState (immutable snapshot)
 * @param materialMap     Per-block material for mass/inertia computation
 * @param comX/Y/Z        World-space center of mass (metres) at creation
 * @param totalMass       Total mass in kg (sum of density × 1 m³ per voxel)
 * @param velX/Y/Z        Initial CoM velocity (m/s) — inherited from collapse impulse
 * @param angVelX/Y/Z     Initial angular velocity (rad/s) — from asymmetric break geometry
 * @param creationTick    Server game tick at creation (diagnostics / CollapseJournal)
 */
public record StructureFragment(
    UUID   id,
    Map<BlockPos, BlockState> blockSnapshot,
    Map<BlockPos, RMaterial>  materialMap,
    double comX, double comY, double comZ,
    double totalMass,
    float velX,    float velY,    float velZ,
    float angVelX, float angVelY, float angVelZ,
    long   creationTick
) {
    // ─── Constants ───

    /**
     * Minimum block count to form a rigid fragment.
     * Fewer blocks → treated as individual rubble by CollapseManager (no entity overhead).
     */
    public static final int MIN_FRAGMENT_BLOCKS = 4;

    /**
     * Maximum block count in a single fragment entity.
     * Larger structures → skip entity spawn (settle immediately as rubble).
     * Prevents pathological cases (entire skyscrapers becoming one entity).
     */
    public static final int MAX_FRAGMENT_BLOCKS = 2048;

    /** Compact constructor: enforce immutability on collections. */
    public StructureFragment {
        blockSnapshot = Collections.unmodifiableMap(blockSnapshot);
        materialMap   = Collections.unmodifiableMap(materialMap);
    }

    /** Block count in this fragment. */
    public int blockCount() { return blockSnapshot.size(); }

    /** True when this fragment is within the entity-spawnable range. */
    public boolean isEntityEligible() {
        return blockCount() >= MIN_FRAGMENT_BLOCKS && blockCount() <= MAX_FRAGMENT_BLOCKS;
    }
}
