package com.blockreality.api.physics.pfsf;

import com.blockreality.api.material.RMaterial;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Function;

/**
 * AI Critical Marker — 6-feature classifier for structural criticality.
 *
 * <p>Features per block (6-dim vector):</p>
 * <ol>
 *   <li><b>volume</b> — number of blocks in the connected component above</li>
 *   <li><b>aspect_ratio</b> — height / max(width, depth) of the island</li>
 *   <li><b>overhang_ratio</b> — unsupported blocks / total blocks</li>
 *   <li><b>material_strength</b> — normalized Rcomp of this block</li>
 *   <li><b>foundation_ratio</b> — anchors / total blocks</li>
 *   <li><b>anchor_distance</b> — BFS distance to nearest anchor</li>
 * </ol>
 *
 * <p>Output: criticality score ∈ [0, 1]. High score = removing this block
 * would likely cause structural failure.</p>
 *
 * <p>Used by {@link CognitiveLODManager} to decide which blocks trigger
 * re-computation when broken.</p>
 *
 * @since v1.0 (BIFROST)
 */
public final class AICriticalMarker {

    private static final Logger LOGGER = LoggerFactory.getLogger("BIFROST-Critical");

    /** 6-feature weights (learned from structural analysis heuristics). */
    private static final float[] WEIGHTS = {
        0.20f,  // volume: more blocks above → more critical
        0.15f,  // aspect_ratio: tall thin → more critical
        0.25f,  // overhang_ratio: more overhangs → more critical
        -0.10f, // material_strength: stronger → less critical (negative)
        -0.15f, // foundation_ratio: more anchors → less critical
        0.15f,  // anchor_distance: far from anchor → more critical
    };
    private static final float BIAS = 0.3f;

    private AICriticalMarker() {}

    /**
     * Compute 6-feature vector for a block.
     *
     * @return float[6] feature vector, normalized to [0, 1]
     */
    public static float[] extractFeatures(
            BlockPos pos, Set<BlockPos> members, Set<BlockPos> anchors,
            Function<BlockPos, RMaterial> materialLookup) {

        int totalBlocks = members.size();
        if (totalBlocks == 0) return new float[6];

        // 1. Volume above: BFS upward from pos
        int volumeAbove = 0;
        Queue<BlockPos> q = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        q.add(pos.above());
        while (!q.isEmpty() && visited.size() < 500) {
            BlockPos cur = q.poll();
            if (!members.contains(cur) || visited.contains(cur)) continue;
            visited.add(cur);
            volumeAbove++;
            for (Direction d : Direction.values()) {
                if (d != Direction.DOWN) q.add(cur.relative(d));
            }
        }

        // 2. Aspect ratio of island
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : members) {
            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
            minY = Math.min(minY, p.getY()); maxY = Math.max(maxY, p.getY());
            minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
        }
        float height = maxY - minY + 1;
        float width = Math.max(maxX - minX + 1, maxZ - minZ + 1);
        float aspectRatio = width > 0 ? height / width : 1.0f;

        // 3. Overhang ratio
        int overhangs = 0;
        for (BlockPos p : members) {
            if (!anchors.contains(p) && !members.contains(p.below())) overhangs++;
        }

        // 4. Material strength
        float matStrength = 0.5f;
        if (materialLookup != null) {
            RMaterial mat = materialLookup.apply(pos);
            if (mat != null) matStrength = (float) Math.min(mat.getRcomp() / 250.0, 1.0);
        }

        // 5. Foundation ratio
        float foundationRatio = (float) anchors.size() / totalBlocks;

        // 6. Anchor distance (BFS)
        int anchorDist = bfsDistance(pos, anchors, members);

        return new float[]{
            Math.min((float) volumeAbove / totalBlocks, 1.0f),
            Math.min(aspectRatio / 5.0f, 1.0f),
            (float) overhangs / totalBlocks,
            matStrength,
            foundationRatio,
            Math.min(anchorDist / 20.0f, 1.0f),
        };
    }

    /**
     * Compute criticality score from features.
     *
     * @return score ∈ [0, 1] — higher = more critical
     */
    public static float score(float[] features) {
        float s = BIAS;
        for (int i = 0; i < 6; i++) {
            s += WEIGHTS[i] * features[i];
        }
        return Math.max(0, Math.min(1, s));
    }

    /**
     * Quick check: is this block critical?
     */
    public static boolean isCritical(BlockPos pos, Set<BlockPos> members,
                                      Set<BlockPos> anchors,
                                      Function<BlockPos, RMaterial> materialLookup,
                                      float threshold) {
        return score(extractFeatures(pos, members, anchors, materialLookup)) >= threshold;
    }

    private static int bfsDistance(BlockPos from, Set<BlockPos> targets, Set<BlockPos> members) {
        if (targets.contains(from)) return 0;
        Queue<BlockPos> queue = new ArrayDeque<>();
        Map<BlockPos, Integer> dist = new HashMap<>();
        queue.add(from);
        dist.put(from, 0);
        while (!queue.isEmpty()) {
            BlockPos cur = queue.poll();
            int d = dist.get(cur);
            if (d > 20) return 20;
            for (Direction dir : Direction.values()) {
                BlockPos nb = cur.relative(dir);
                if (targets.contains(nb)) return d + 1;
                if (members.contains(nb) && !dist.containsKey(nb)) {
                    dist.put(nb, d + 1);
                    queue.add(nb);
                }
            }
        }
        return 20; // unreachable
    }
}
