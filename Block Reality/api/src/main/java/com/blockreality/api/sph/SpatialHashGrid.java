package com.blockreality.api.sph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 空間雜湊格子 — SPH 鄰域搜索的 O(1) 查詢結構。
 *
 * <p>SPH 核心函數具有緊支撐性（compact support），即距離超過 2h 的粒子
 * 不會互相影響。利用這一性質，將空間劃分為邊長 = 2h 的格子，
 * 查詢鄰居時只需搜索周圍 3³ = 27 個格子。
 *
 * <p>使用 Teschner et al. (2003) 的空間雜湊方法，以三個大質數
 * 將格子座標映射到一維雜湊值，避免顯式儲存 3D 陣列。
 *
 * <h3>時間複雜度</h3>
 * <ul>
 *   <li>插入：O(1) amortized</li>
 *   <li>鄰域查詢：O(k)，k 為返回的鄰居數量</li>
 *   <li>建構：O(N)，N 為粒子總數</li>
 * </ul>
 *
 * <h3>參考文獻</h3>
 * <p>Teschner, M. et al. (2003). "Optimized Spatial Hashing for Collision
 * Detection of Deformable Objects". VMV 2003.</p>
 *
 * @see SPHKernel
 * @see SPHStressEngine
 */
public class SpatialHashGrid {

    /** 格子邊長 = 核心支撐半徑 = 2h */
    private final double cellSize;
    private final double inverseCellSize;

    /** 雜湊表：cell hash → 該格子內的粒子索引列表 */
    private final Map<Long, List<Integer>> grid;

    // Teschner 空間雜湊質數
    private static final long P1 = 73856093L;
    private static final long P2 = 19349663L;
    private static final long P3 = 83492791L;

    /**
     * 建構空間雜湊格子。
     *
     * @param smoothingLength SPH 平滑長度 h（格子大小 = 2h）
     */
    public SpatialHashGrid(double smoothingLength) {
        this.cellSize = SPHKernel.supportRadius(smoothingLength); // 2h
        this.inverseCellSize = 1.0 / this.cellSize;
        this.grid = new HashMap<>();
    }

    /**
     * 插入一個粒子到格子中。
     *
     * @param particleIndex 粒子在外部陣列中的索引
     * @param x             世界座標 X
     * @param y             世界座標 Y
     * @param z             世界座標 Z
     */
    public void insert(int particleIndex, double x, double y, double z) {
        long key = cellHash(cellX(x), cellY(y), cellZ(z));
        grid.computeIfAbsent(key, k -> new ArrayList<>(4)).add(particleIndex);
    }

    /**
     * 查詢給定座標的所有可能鄰居粒子。
     *
     * <p>搜索以 (x,y,z) 所在格子為中心的 3³ = 27 個格子，
     * 返回所有位於這些格子中的粒子索引。
     * 呼叫端需自行計算實際距離以精確篩選。
     *
     * @param x 查詢座標 X
     * @param y 查詢座標 Y
     * @param z 查詢座標 Z
     * @return 候選鄰居的粒子索引列表（可能包含自身和超出支撐半徑的粒子）
     */
    /**
     * ★ Audit fix: ThreadLocal 可重用緩衝，避免每次 getNeighbors() 分配新 ArrayList。
     * SPH 每粒子每步呼叫多次，百萬粒子下造成嚴重 GC 壓力。
     * 注意：返回的 List 在下次呼叫 getNeighbors() 時會被 clear，呼叫端需即時消費。
     */
    private static final ThreadLocal<List<Integer>> NEIGHBOR_BUFFER =
        ThreadLocal.withInitial(() -> new ArrayList<>(256));

    public List<Integer> getNeighbors(double x, double y, double z) {
        int cx = cellX(x);
        int cy = cellY(y);
        int cz = cellZ(z);

        List<Integer> neighbors = NEIGHBOR_BUFFER.get();
        neighbors.clear();

        // 搜索 3×3×3 = 27 個相鄰格子
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    long key = cellHash(cx + dx, cy + dy, cz + dz);
                    List<Integer> cell = grid.get(key);
                    if (cell != null) {
                        neighbors.addAll(cell);
                    }
                }
            }
        }

        return neighbors;
    }

    /**
     * 清除所有粒子資料，準備重用。
     */
    public void clear() {
        grid.clear();
    }

    /**
     * 返回格子中的粒子總數。
     */
    public int size() {
        int count = 0;
        for (List<Integer> cell : grid.values()) {
            count += cell.size();
        }
        return count;
    }

    // ─── 內部方法 ───

    private int cellX(double x) { return (int) Math.floor(x * inverseCellSize); }
    private int cellY(double y) { return (int) Math.floor(y * inverseCellSize); }
    private int cellZ(double z) { return (int) Math.floor(z * inverseCellSize); }

    /**
     * Teschner 空間雜湊：將 3D 格子座標映射到 1D 雜湊值。
     * 使用三個大質數的 XOR 組合，在均勻分佈的粒子中碰撞率極低。
     */
    private static long cellHash(int cx, int cy, int cz) {
        return ((long) cx * P1) ^ ((long) cy * P2) ^ ((long) cz * P3);
    }
}
