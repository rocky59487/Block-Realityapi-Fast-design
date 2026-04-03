package com.blockreality.fastdesign.client;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * Client-side 選取狀態 — v3fix §4 / 開發手冊 §11
 * 管理客戶端目前方塊選取範圍(pos1, pos2)並計算體積大小。
 *
 * <p>範例套用:
 * <pre>
 * // 更新選取範圍
 * ClientSelectionHolder.update(pos1, pos2);
 *
 * // 獲取選取資訊並讀取大小
 * ClientSelectionHolder.SelectionData data = ClientSelectionHolder.get();
 * if (data != null) {
 *     int vol = data.volume();
 * }
 * </pre>
 */
public class ClientSelectionHolder {

    private static volatile @Nullable BlockPos min;
    private static volatile @Nullable BlockPos max;

    /**
     * 更新選取範圍的最小與最大座標。
     * @param newMin 選取區域的最小座標
     * @param newMax 選取區域的最大座標
     */
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
