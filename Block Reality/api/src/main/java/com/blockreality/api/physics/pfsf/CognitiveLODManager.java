package com.blockreality.api.physics.pfsf;

import com.blockreality.api.config.BRConfig;
import com.blockreality.api.material.RMaterial;
import com.blockreality.api.physics.StructureIslandRegistry;
import com.blockreality.api.physics.StructureIslandRegistry.StructureIsland;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Cognitive LOD Manager — distance-based physics degradation + critical point triggering.
 *
 * <h2>Core Logic</h2>
 * <pre>
 * Player Distance → Compute Intensity:
 *   0-16 blocks:   FULL     (every tick, all iterations)
 *   16-48 blocks:  MEDIUM   (every 2 ticks, half iterations)
 *   48-96 blocks:  LOW      (every 4 ticks, quarter iterations)
 *   96+ blocks:    DORMANT  (only recompute on critical point break)
 *
 * Critical Point Break Trigger:
 *   1. AICriticalMarker.isCritical(brokenPos) → true?
 *   2. mlPredictor.estimateImpact(pos) → impactRadius
 *   3. pfsf.solveLocal(impactZone) → few RBGS iterations → collapse check
 * </pre>
 *
 * <h2>Principle</h2>
 * Only critical block destruction triggers computation in dormant regions.
 * Far-away structures sleep until a keystone is removed — then a local,
 * focused solve runs in a tiny domain (radius = impact zone).
 *
 * @since v1.0 (BIFROST)
 * @see AICriticalMarker
 * @see ChunkPhysicsLOD
 */
public class CognitiveLODManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("BIFROST-CogLOD");

    public enum ComputeLevel {
        FULL(1, 1.0f),        // every tick, full steps
        MEDIUM(2, 0.5f),      // every 2 ticks, half steps
        LOW(4, 0.25f),        // every 4 ticks, quarter steps
        DORMANT(0, 0.0f);     // no ticking — only on critical break

        public final int tickInterval;
        public final float stepMultiplier;

        ComputeLevel(int interval, float mult) {
            this.tickInterval = interval;
            this.stepMultiplier = mult;
        }
    }

    // Distance thresholds (blocks)
    private static final int FULL_RANGE    = 16;
    private static final int MEDIUM_RANGE  = 48;
    private static final int LOW_RANGE     = 96;

    /** Per-island cached compute level. */
    private final ConcurrentHashMap<Integer, ComputeLevel> islandLevels = new ConcurrentHashMap<>();

    /** Critical points per island. */
    private final ConcurrentHashMap<Integer, Set<BlockPos>> criticalPoints = new ConcurrentHashMap<>();

    /** Material lookup (shared). */
    private Function<BlockPos, RMaterial> materialLookup;

    /** Criticality threshold (configurable). */
    private float criticalThreshold = 0.55f;

    public void setMaterialLookup(Function<BlockPos, RMaterial> lookup) {
        this.materialLookup = lookup;
    }

    /**
     * Update compute levels for all islands based on player positions.
     * Called once per tick before island iteration.
     */
    public void updateLevels(List<ServerPlayer> players) {
        for (var entry : StructureIslandRegistry.getAllIslands().entrySet()) {
            int islandId = entry.getKey();
            StructureIsland island = entry.getValue();

            double minDist = Double.MAX_VALUE;
            BlockPos center = island.getCenter();
            for (ServerPlayer p : players) {
                double d = p.blockPosition().distSqr(center);
                minDist = Math.min(minDist, d);
            }
            double dist = Math.sqrt(minDist);

            ComputeLevel level;
            if (dist <= FULL_RANGE) level = ComputeLevel.FULL;
            else if (dist <= MEDIUM_RANGE) level = ComputeLevel.MEDIUM;
            else if (dist <= LOW_RANGE) level = ComputeLevel.LOW;
            else level = ComputeLevel.DORMANT;

            islandLevels.put(islandId, level);
        }
    }

    /**
     * Should this island be computed this tick?
     */
    public boolean shouldCompute(int islandId, int tickCounter) {
        ComputeLevel level = islandLevels.getOrDefault(islandId, ComputeLevel.FULL);
        if (level == ComputeLevel.DORMANT) return false;
        return tickCounter % level.tickInterval == 0;
    }

    /**
     * Get step multiplier for adaptive iteration count.
     */
    public float getStepMultiplier(int islandId) {
        ComputeLevel level = islandLevels.getOrDefault(islandId, ComputeLevel.FULL);
        return level.stepMultiplier;
    }

    /**
     * Handle block break event — check if it's a critical point.
     *
     * @return impact radius if critical (triggers local solve), or 0 if not critical
     */
    public int onBlockBreak(BlockPos pos, int islandId, StructureIsland island) {
        if (island == null) return 0;

        Set<BlockPos> members = island.getMembers();
        Set<BlockPos> anchors = island.getAnchors();

        // Check if this block is critical using 6-feature classifier
        float[] features = AICriticalMarker.extractFeatures(
                pos, members, anchors, materialLookup);
        float criticality = AICriticalMarker.score(features);

        if (criticality < criticalThreshold) return 0;

        // Estimate impact radius from features
        // Higher volume above + higher overhang ratio → larger impact
        int impactRadius = estimateImpactRadius(features, criticality);

        LOGGER.info("[CogLOD] Critical block broken at {} (score={:.2f}, impact={})",
                pos, criticality, impactRadius);

        return impactRadius;
    }

    /**
     * Estimate the radius of the impact zone when a critical block is removed.
     *
     * Based on: volume above (feature[0]) × overhang ratio (feature[2]) × criticality
     */
    private int estimateImpactRadius(float[] features, float criticality) {
        float volumeFactor = features[0];    // blocks above / total
        float overhangFactor = features[2];  // overhang ratio
        float anchorDist = features[5];      // distance to anchor

        // Base radius: 4-16 blocks
        int radius = 4 + (int)(12 * criticality * (volumeFactor + overhangFactor + anchorDist) / 3.0f);
        return Math.max(4, Math.min(radius, 32));
    }

    /**
     * Scan an island and cache its critical points.
     * Called when island is first loaded or after significant modification.
     */
    public void scanCriticalPoints(int islandId, StructureIsland island) {
        Set<BlockPos> members = island.getMembers();
        Set<BlockPos> anchors = island.getAnchors();
        Set<BlockPos> critical = ConcurrentHashMap.newKeySet();

        for (BlockPos pos : members) {
            if (anchors.contains(pos)) continue;
            if (AICriticalMarker.isCritical(pos, members, anchors,
                    materialLookup, criticalThreshold)) {
                critical.add(pos);
            }
        }

        criticalPoints.put(islandId, critical);
        LOGGER.debug("[CogLOD] Island {} has {} critical points / {} blocks",
                islandId, critical.size(), members.size());
    }

    /**
     * Check if a position is a known critical point.
     */
    public boolean isCriticalPoint(int islandId, BlockPos pos) {
        Set<BlockPos> points = criticalPoints.get(islandId);
        return points != null && points.contains(pos);
    }

    public void removeIsland(int islandId) {
        islandLevels.remove(islandId);
        criticalPoints.remove(islandId);
    }

    public String getStats() {
        int[] counts = new int[4];
        for (ComputeLevel level : islandLevels.values()) {
            counts[level.ordinal()]++;
        }
        int totalCritical = criticalPoints.values().stream().mapToInt(Set::size).sum();
        return String.format("CogLOD: %d full + %d med + %d low + %d dormant, %d critical pts",
                counts[0], counts[1], counts[2], counts[3], totalCritical);
    }

    public void setCriticalThreshold(float t) { this.criticalThreshold = t; }
    public float getCriticalThreshold() { return criticalThreshold; }
}
