package com.blockreality.api.physics.pfsf;

import net.minecraft.core.BlockPos;
import java.util.Set;

/**
 * Classifies structure islands as "regular" or "irregular" to decide
 * which physics backend to use:
 *
 * <ul>
 *   <li>Regular (score < threshold) → PFSF iterative solver (fast, proven)</li>
 *   <li>Irregular (score >= threshold) → FNO ML surrogate (handles cantilevers, arches, etc.)</li>
 * </ul>
 *
 * <p>Irregularity score ∈ [0, 1] based on:</p>
 * <ol>
 *   <li>Fill ratio vs AABB (empty space = irregular)</li>
 *   <li>Surface-to-volume ratio (jagged = irregular)</li>
 *   <li>Vertical profile variance (inconsistent layers = irregular)</li>
 *   <li>Overhang ratio (unsupported blocks / total)</li>
 * </ol>
 *
 * <p>Matches Python {@code classify_irregularity()} in brml for training alignment.</p>
 *
 * @since v0.3a
 * @see HybridPhysicsRouter
 */
public final class ShapeClassifier {

    /** Default threshold: structures above this use FNO. */
    public static final float DEFAULT_THRESHOLD = 0.45f;

    private ShapeClassifier() {}

    /**
     * Compute irregularity score for a set of block positions.
     *
     * @param members All block positions in the island.
     * @param anchors Anchor (fixed) positions.
     * @return Score in [0, 1]. Higher = more irregular.
     */
    public static float score(Set<BlockPos> members, Set<BlockPos> anchors) {
        if (members.isEmpty()) return 0.0f;

        int n = members.size();

        // ── AABB ──
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : members) {
            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
            minY = Math.min(minY, p.getY()); maxY = Math.max(maxY, p.getY());
            minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
        }
        int lx = maxX - minX + 1, ly = maxY - minY + 1, lz = maxZ - minZ + 1;
        int aabbVolume = lx * ly * lz;

        // 1. Fill ratio (lower = more irregular)
        float fill = (float) n / Math.max(aabbVolume, 1);

        // 2. Surface: count exposed faces
        int surfaceFaces = 0;
        for (BlockPos p : members) {
            for (var dir : net.minecraft.core.Direction.values()) {
                if (!members.contains(p.relative(dir))) {
                    surfaceFaces++;
                }
            }
        }
        float idealSurface = 6.0f * (float) Math.pow(n, 2.0 / 3.0);
        float surfaceRatio = surfaceFaces / Math.max(idealSurface, 1.0f);

        // 3. Vertical profile variance
        int[] layerCounts = new int[ly];
        for (BlockPos p : members) {
            layerCounts[p.getY() - minY]++;
        }
        float maxLayer = 0;
        for (int c : layerCounts) maxLayer = Math.max(maxLayer, c);
        float profileVar = 0;
        if (maxLayer > 0 && ly > 1) {
            float mean = 0;
            for (int c : layerCounts) mean += c / maxLayer;
            mean /= ly;
            for (int c : layerCounts) {
                float diff = (c / maxLayer) - mean;
                profileVar += diff * diff;
            }
            profileVar = (float) Math.sqrt(profileVar / ly);
        }

        // 4. Overhang ratio: blocks with no solid below
        int overhangs = 0;
        for (BlockPos p : members) {
            if (anchors.contains(p)) continue;
            BlockPos below = p.below();
            if (!members.contains(below) && !anchors.contains(below)) {
                overhangs++;
            }
        }
        float overhangRatio = (float) overhangs / Math.max(n, 1);

        // Weighted combination
        return Math.min(1.0f, Math.max(0.0f,
            0.25f * (1.0f - fill) +
            0.25f * Math.min(surfaceRatio, 2.0f) / 2.0f +
            0.25f * profileVar +
            0.25f * overhangRatio
        ));
    }

    /**
     * Quick check: is this structure irregular enough for FNO?
     */
    public static boolean isIrregular(Set<BlockPos> members, Set<BlockPos> anchors) {
        return score(members, anchors) >= DEFAULT_THRESHOLD;
    }

    /**
     * Quick check with custom threshold.
     */
    public static boolean isIrregular(Set<BlockPos> members, Set<BlockPos> anchors,
                                       float threshold) {
        return score(members, anchors) >= threshold;
    }
}
