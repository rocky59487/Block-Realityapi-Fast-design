package com.blockreality.api.physics;

import com.blockreality.api.config.BRConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * 物理精度層級 — 基於結構到最近玩家的距離決定分析精度。
 *
 * ★ Phase 4: LOD (Level of Detail) 物理系統。
 * 遠離玩家的結構使用低精度引擎，節省 CPU 預算。
 *
 * 各層級對應引擎：
 *   FULL      → BeamStressEngine + ForceEquilibriumSolver（最高精度）
 *   STANDARD  → SupportPathAnalyzer（加權 BFS，<50ms）
 *   COARSE    → LoadPathEngine（O(H) 樹走訪）
 *   DORMANT   → 不主動計算，僅使用快取結果
 */
public enum PhysicsTier {

    /** 完整精度：適用於玩家附近的結構 */
    FULL(0),

    /** 標準精度：中距離結構 */
    STANDARD(1),

    /** 粗略精度：遠距離結構 */
    COARSE(2),

    /** 休眠：極遠距離，不主動計算 */
    DORMANT(3);

    private final int level;

    PhysicsTier(int level) {
        this.level = level;
    }

    public int getLevel() { return level; }

    /**
     * 根據結構 AABB 中心到最近玩家的距離，決定物理精度層級。
     *
     * @param islandMin  島嶼 AABB 最小角
     * @param islandMax  島嶼 AABB 最大角
     * @param players    線上玩家列表
     * @return 適用的物理精度層級
     */
    public static PhysicsTier forIsland(BlockPos islandMin, BlockPos islandMax, List<ServerPlayer> players) {
        if (players.isEmpty()) return DORMANT;

        // 計算 AABB 中心
        double cx = (islandMin.getX() + islandMax.getX()) / 2.0;
        double cy = (islandMin.getY() + islandMax.getY()) / 2.0;
        double cz = (islandMin.getZ() + islandMax.getZ()) / 2.0;

        // 找到最近的玩家距離
        double minDistSq = Double.MAX_VALUE;
        for (ServerPlayer player : players) {
            double dx = player.getX() - cx;
            double dy = player.getY() - cy;
            double dz = player.getZ() - cz;
            double distSq = dx * dx + dy * dy + dz * dz;
            minDistSq = Math.min(minDistSq, distSq);
        }

        double minDist = Math.sqrt(minDistSq);

        // 從 BRConfig 讀取距離閾值
        int fullDist = BRConfig.INSTANCE.lodFullPrecisionDistance.get();
        int standardDist = BRConfig.INSTANCE.lodStandardDistance.get();
        int coarseDist = BRConfig.INSTANCE.lodCoarseDistance.get();

        if (minDist <= fullDist) return FULL;
        if (minDist <= standardDist) return STANDARD;
        if (minDist <= coarseDist) return COARSE;
        return DORMANT;
    }

    /**
     * 簡化版本：使用單一方塊位置（非 AABB）判斷。
     */
    public static PhysicsTier forPosition(BlockPos pos, List<ServerPlayer> players) {
        return forIsland(pos, pos, players);
    }
}
