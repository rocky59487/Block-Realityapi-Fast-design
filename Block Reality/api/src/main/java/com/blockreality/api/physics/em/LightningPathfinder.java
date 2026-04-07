package com.blockreality.api.physics.em;

import com.blockreality.api.physics.solver.DiffusionRegion;
import net.minecraft.core.BlockPos;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

/**
 * 閃電路徑搜尋器 — 在電位場中沿梯度下降找最短放電路徑。
 *
 * <p>閃電從最高電位點出發，每步移動到 6 鄰居中電位最低的方向，
 * 直到到達接地點（φ ≈ 0）或超過最大步數。
 *
 * <p>物理依據：閃電沿電場方向 E = -∇φ 傳播，即從高電位到低電位。
 */
public class LightningPathfinder {

    private static final int MAX_PATH_LENGTH = 256;
    private static final int[][] OFFSETS = {
        {1,0,0}, {-1,0,0}, {0,1,0}, {0,-1,0}, {0,0,1}, {0,0,-1}
    };

    /**
     * 從起點沿電位梯度下降搜尋閃電路徑。
     *
     * @param region 已求解的電位場
     * @param start  閃電起點（局部座標）
     * @return 路徑（世界座標），從起點到接地點
     */
    @Nonnull
    public static List<BlockPos> findPath(@Nonnull DiffusionRegion region, @Nonnull BlockPos start) {
        List<BlockPos> path = new ArrayList<>();
        int sx = region.getSizeX(), sy = region.getSizeY(), sz = region.getSizeZ();

        int x = start.getX() - region.getOriginX();
        int y = start.getY() - region.getOriginY();
        int z = start.getZ() - region.getOriginZ();

        if (x < 0 || x >= sx || y < 0 || y >= sy || z < 0 || z >= sz) return path;

        for (int step = 0; step < MAX_PATH_LENGTH; step++) {
            path.add(new BlockPos(
                x + region.getOriginX(),
                y + region.getOriginY(),
                z + region.getOriginZ()
            ));

            int idx = region.flatIndex(x, y, z);
            float currentPhi = region.getPhi()[idx];

            // 已接地
            if (Math.abs(currentPhi) < 1.0f) break;

            // 找電位最低的鄰居
            float minPhi = currentPhi;
            int bestX = x, bestY = y, bestZ = z;

            for (int[] off : OFFSETS) {
                int nx = x + off[0], ny = y + off[1], nz = z + off[2];
                if (nx < 0 || nx >= sx || ny < 0 || ny >= sy || nz < 0 || nz >= sz) continue;
                int nIdx = region.flatIndex(nx, ny, nz);
                float nPhi = region.getPhi()[nIdx];
                if (nPhi < minPhi) {
                    minPhi = nPhi;
                    bestX = nx; bestY = ny; bestZ = nz;
                }
            }

            // 無更低電位（局部最小值），停止
            if (bestX == x && bestY == y && bestZ == z) break;

            x = bestX; y = bestY; z = bestZ;
        }

        return path;
    }

    /**
     * 找到區域內電位最高的體素（閃電起點）。
     */
    @Nonnull
    public static BlockPos findHighestPotential(@Nonnull DiffusionRegion region) {
        float maxPhi = Float.NEGATIVE_INFINITY;
        int bestIdx = 0;
        float[] phi = region.getPhi();
        byte[] type = region.getType();

        for (int i = 0; i < region.getTotalVoxels(); i++) {
            if (type[i] == DiffusionRegion.TYPE_ACTIVE && phi[i] > maxPhi) {
                maxPhi = phi[i];
                bestIdx = i;
            }
        }

        // 反算座標
        int sx = region.getSizeX(), sy = region.getSizeY();
        int x = bestIdx % sx;
        int y = (bestIdx / sx) % sy;
        int z = bestIdx / (sx * sy);

        return new BlockPos(
            x + region.getOriginX(),
            y + region.getOriginY(),
            z + region.getOriginZ()
        );
    }
}
