package com.blockreality.api.placement;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Multi-Block Placement Calculator — pure geometry functions.
 *
 * Given two anchor positions (pos1, pos2) and a BuildMode,
 * computes the full list of BlockPos that should be placed.
 *
 * All methods are static and side-effect-free; they only compute positions.
 * The caller is responsible for actually placing blocks / generating ghost previews.
 */
public final class MultiBlockCalculator {

    private MultiBlockCalculator() {}

    /**
     * Compute all block positions for the given build mode.
     *
     * @param mode   The active build mode
     * @param pos1   First anchor (click point or selection pos1)
     * @param pos2   Second anchor (click point or selection pos2)
     * @param mirror Optional mirror anchor (only used by MIRROR_X / MIRROR_Z; may be null)
     * @return unmodifiable list of positions to place
     */
    public static List<BlockPos> calculate(BuildMode mode, BlockPos pos1, BlockPos pos2,
                                            BlockPos mirror) {
        return switch (mode) {
            case NORMAL   -> Collections.singletonList(pos2);
            case LINE     -> computeLine(pos1, pos2);
            case WALL     -> computeWall(pos1, pos2);
            case CUBE     -> computeCube(pos1, pos2);
            case MIRROR_X -> computeMirror(pos1, pos2, mirror, 'x');
            case MIRROR_Z -> computeMirror(pos1, pos2, mirror, 'z');
        };
    }

    // ─── LINE ───────────────────────────────────────────────────

    /**
     * 3D Bresenham line from pos1 to pos2.
     * Produces the shortest staircase path through voxel space.
     */
    public static List<BlockPos> computeLine(BlockPos from, BlockPos to) {
        List<BlockPos> result = new ArrayList<>();

        int x0 = from.getX(), y0 = from.getY(), z0 = from.getZ();
        int x1 = to.getX(),   y1 = to.getY(),   z1 = to.getZ();

        int dx = Math.abs(x1 - x0), dy = Math.abs(y1 - y0), dz = Math.abs(z1 - z0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int sz = z0 < z1 ? 1 : -1;

        // Driving axis = the one with largest delta
        int max = Math.max(dx, Math.max(dy, dz));
        if (max == 0) {
            result.add(from.immutable());
            return result;
        }

        // DDA-style walk
        double stepX = (double)(x1 - x0) / max;
        double stepY = (double)(y1 - y0) / max;
        double stepZ = (double)(z1 - z0) / max;

        for (int i = 0; i <= max; i++) {
            int bx = x0 + (int) Math.round(stepX * i);
            int by = y0 + (int) Math.round(stepY * i);
            int bz = z0 + (int) Math.round(stepZ * i);
            result.add(new BlockPos(bx, by, bz));
        }
        return result;
    }

    // ─── WALL ───────────────────────────────────────────────────

    /**
     * Compute a 2D rectangular wall from pos1 to pos2.
     *
     * The wall plane is determined by the two axes with the largest span.
     * If pos1 and pos2 share the same X, the wall is in the YZ plane;
     * if they share the same Z, the wall is in the XY plane; otherwise XZ (floor).
     */
    public static List<BlockPos> computeWall(BlockPos pos1, BlockPos pos2) {
        List<BlockPos> result = new ArrayList<>();

        int minX = Math.min(pos1.getX(), pos2.getX());
        int maxX = Math.max(pos1.getX(), pos2.getX());
        int minY = Math.min(pos1.getY(), pos2.getY());
        int maxY = Math.max(pos1.getY(), pos2.getY());
        int minZ = Math.min(pos1.getZ(), pos2.getZ());
        int maxZ = Math.max(pos1.getZ(), pos2.getZ());

        int spanX = maxX - minX;
        int spanY = maxY - minY;
        int spanZ = maxZ - minZ;

        // Determine wall plane — collapse the thinnest axis
        if (spanX <= spanY && spanX <= spanZ) {
            // YZ plane wall at midX
            int x = (minX + maxX) / 2;
            for (int y = minY; y <= maxY; y++)
                for (int z = minZ; z <= maxZ; z++)
                    result.add(new BlockPos(x, y, z));
        } else if (spanZ <= spanX && spanZ <= spanY) {
            // XY plane wall at midZ
            int z = (minZ + maxZ) / 2;
            for (int x = minX; x <= maxX; x++)
                for (int y = minY; y <= maxY; y++)
                    result.add(new BlockPos(x, y, z));
        } else {
            // XZ plane floor at midY
            int y = (minY + maxY) / 2;
            for (int x = minX; x <= maxX; x++)
                for (int z = minZ; z <= maxZ; z++)
                    result.add(new BlockPos(x, y, z));
        }
        return result;
    }

    // ─── CUBE ───────────────────────────────────────────────────

    /**
     * 3D filled cuboid between pos1 and pos2 (inclusive).
     */
    public static List<BlockPos> computeCube(BlockPos pos1, BlockPos pos2) {
        List<BlockPos> result = new ArrayList<>();

        int minX = Math.min(pos1.getX(), pos2.getX());
        int maxX = Math.max(pos1.getX(), pos2.getX());
        int minY = Math.min(pos1.getY(), pos2.getY());
        int maxY = Math.max(pos1.getY(), pos2.getY());
        int minZ = Math.min(pos1.getZ(), pos2.getZ());
        int maxZ = Math.max(pos1.getZ(), pos2.getZ());

        for (int x = minX; x <= maxX; x++)
            for (int y = minY; y <= maxY; y++)
                for (int z = minZ; z <= maxZ; z++)
                    result.add(new BlockPos(x, y, z));
        return result;
    }

    // ─── MIRROR ─────────────────────────────────────────────────

    /**
     * Place at pos2, then mirror across the given axis through mirrorAnchor.
     *
     * @param pos1        First anchor (not directly used, reserved for multi-point modes)
     * @param pos2        The position being placed
     * @param mirrorAnchor The block position defining the mirror plane
     * @param axis        'x' = mirror across YZ plane, 'z' = mirror across XY plane
     * @return positions: the original pos2 + its mirrored counterpart
     */
    public static List<BlockPos> computeMirror(BlockPos pos1, BlockPos pos2,
                                                BlockPos mirrorAnchor, char axis) {
        List<BlockPos> result = new ArrayList<>();
        result.add(pos2);

        if (mirrorAnchor == null) return result;

        int mx, my, mz;
        mx = pos2.getX();
        my = pos2.getY();
        mz = pos2.getZ();

        switch (axis) {
            case 'x' -> mx = 2 * mirrorAnchor.getX() - pos2.getX();
            case 'z' -> mz = 2 * mirrorAnchor.getZ() - pos2.getZ();
            case 'y' -> my = 2 * mirrorAnchor.getY() - pos2.getY();
            default -> throw new IllegalArgumentException("Invalid mirror axis: '" + axis + "' (expected x/y/z)");
        }

        BlockPos mirrored = new BlockPos(mx, my, mz);
        if (!mirrored.equals(pos2)) {
            result.add(mirrored);
        }
        return result;
    }

    // ─── Utility ────────────────────────────────────────────────

    /**
     * Apply mirror to an entire list of positions.
     * Useful for combining LINE/WALL/CUBE with MIRROR.
     *
     * @param positions     Base positions
     * @param mirrorAnchor  The mirror plane anchor
     * @param axis          'x', 'y', or 'z'
     * @return original positions + mirrored positions (deduplicated on the mirror plane)
     */
    public static List<BlockPos> applyMirrorToList(List<BlockPos> positions,
                                                    BlockPos mirrorAnchor, char axis) {
        // 使用 LinkedHashSet 保持順序且 O(1) 去重
        java.util.LinkedHashSet<BlockPos> seen = new java.util.LinkedHashSet<>(positions);
        for (BlockPos pos : positions) {
            List<BlockPos> mirrored = computeMirror(null, pos, mirrorAnchor, axis);
            seen.addAll(mirrored);
        }
        return new ArrayList<>(seen);
    }
}
