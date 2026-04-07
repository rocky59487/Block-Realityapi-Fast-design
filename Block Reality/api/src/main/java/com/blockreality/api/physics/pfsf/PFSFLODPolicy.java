package com.blockreality.api.physics.pfsf;

import com.blockreality.api.config.BRConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

import static com.blockreality.api.physics.pfsf.PFSFConstants.*;

/**
 * LOD 物理策略 — 根據玩家距離分級計算精度。
 *
 * <ul>
 *   <li>LOD_FULL (< 32 格)：全精度</li>
 *   <li>LOD_STANDARD (32-96 格)：步數 × 0.5</li>
 *   <li>LOD_COARSE (96-256 格)：步數 × 0.25</li>
 *   <li>LOD_DORMANT (> 256 格)：跳過（事件喚醒例外）</li>
 * </ul>
 */
public final class PFSFLODPolicy {

    private PFSFLODPolicy() {}

    /**
     * 根據 island 到最近玩家的距離計算 LOD 等級。
     *
     * @param buf     island buffer（取得中心座標）
     * @param players 線上玩家列表
     * @return LOD 等級
     */
    public static int computeLodLevel(PFSFIslandBuffer buf, List<ServerPlayer> players) {
        if (players == null || players.isEmpty()) {
            return LOD_FULL; // 無玩家時全精度（安全：伺服器不會漏算）
        }

        BlockPos origin = buf.getOrigin();
        if (origin == null) return LOD_FULL;

        double cx = origin.getX() + buf.getLx() / 2.0;
        double cy = origin.getY() + buf.getLy() / 2.0;
        double cz = origin.getZ() + buf.getLz() / 2.0;

        double minDistSq = Double.MAX_VALUE;
        for (ServerPlayer player : players) {
            double dx = player.getX() - cx;
            double dy = player.getY() - cy;
            double dz = player.getZ() - cz;
            minDistSq = Math.min(minDistSq, dx * dx + dy * dy + dz * dz);
        }

        double dist = Math.sqrt(minDistSq);

        int fullDist = BRConfig.getLodFullPrecisionDistance();
        int stdDist = BRConfig.getLodStandardDistance();
        int coarseDist = BRConfig.getLodCoarseDistance();

        if (dist < fullDist) return LOD_FULL;
        if (dist < stdDist) return LOD_STANDARD;
        if (dist < coarseDist) return LOD_COARSE;
        return LOD_DORMANT;
    }

    /**
     * 根據 LOD 等級調整步數。
     */
    public static int adjustStepsForLod(int steps, int lodLevel) {
        return switch (lodLevel) {
            case LOD_STANDARD -> Math.max(1, steps / 2);
            case LOD_COARSE -> Math.max(1, steps / 4);
            case LOD_DORMANT -> 0;
            default -> steps;
        };
    }
}
