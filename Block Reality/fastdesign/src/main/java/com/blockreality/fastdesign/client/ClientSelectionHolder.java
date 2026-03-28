package com.blockreality.fastdesign.client;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * Client-side 選取狀態 — v3fix §4 / 開發手冊 §11
 */
public class ClientSelectionHolder {

    private static volatile @Nullable BlockPos min;
    private static volatile @Nullable BlockPos max;

    public static void update(BlockPos newMin, BlockPos newMax) {
        min = newMin.immutable();
        max = newMax.immutable();
    }

    public static void clear() {
        min = null;
        max = null;
    }

    @Nullable
    public static SelectionData get() {
        BlockPos localMin = min;
        BlockPos localMax = max;
        if (localMin == null || localMax == null) return null;
        return new SelectionData(localMin, localMax);
    }

    public static boolean hasSelection() {
        return min != null && max != null;
    }

    public record SelectionData(BlockPos min, BlockPos max) {
        public int sizeX() { return max.getX() - min.getX() + 1; }
        public int sizeY() { return max.getY() - min.getY() + 1; }
        public int sizeZ() { return max.getZ() - min.getZ() + 1; }
        public int volume() { return sizeX() * sizeY() * sizeZ(); }
    }
}
