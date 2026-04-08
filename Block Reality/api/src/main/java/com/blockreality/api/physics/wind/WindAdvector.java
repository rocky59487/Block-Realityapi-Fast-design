package com.blockreality.api.physics.wind;

import com.blockreality.api.physics.solver.DiffusionRegion;

/**
 * 風場對流求解器 — Navier-Stokes 的 advection 步。
 *
 * <p>使用半隱式 Lagrangian 回溯法（Stam 1999 "Stable Fluids"）：
 * 從每個體素沿速度場反向回溯一步，用線性插值取得該位置的速度值。
 * 保證無條件穩定（CFL 限制只影響精度，不影響穩定性）。
 *
 * <p>這是風場唯一需要獨立實作的步驟。壓力投射步委託給
 * {@link com.blockreality.api.physics.solver.DiffusionSolver}。
 */
public class WindAdvector {

    /**
     * 執行一步 advection：u* = advect(u, dt)
     *
     * @param velocityX 速度場 X 分量 [N]（讀寫）
     * @param velocityY 速度場 Y 分量 [N]（讀寫）
     * @param velocityZ 速度場 Z 分量 [N]（讀寫）
     * @param region    擴散區域（提供 type[] 和尺寸資訊）
     * @param dt        時間步長（tick_dt × CFL）
     */
    public static void advect(float[] velocityX, float[] velocityY, float[] velocityZ,
                               DiffusionRegion region, float dt) {
        int sx = region.getSizeX(), sy = region.getSizeY(), sz = region.getSizeZ();
        byte[] type = region.getType();

        // 臨時緩衝（避免讀寫衝突）
        float[] newVx = new float[velocityX.length];
        float[] newVy = new float[velocityY.length];
        float[] newVz = new float[velocityZ.length];

        for (int z = 0; z < sz; z++) {
            for (int y = 0; y < sy; y++) {
                for (int x = 0; x < sx; x++) {
                    int idx = region.flatIndex(x, y, z);
                    if (type[idx] == DiffusionRegion.TYPE_SOLID_WALL) continue;

                    // 反向回溯：(x,y,z) - dt × u(x,y,z)
                    float srcX = x - dt * velocityX[idx];
                    float srcY = y - dt * velocityY[idx];
                    float srcZ = z - dt * velocityZ[idx];

                    // 邊界裁切
                    srcX = Math.max(0.5f, Math.min(srcX, sx - 1.5f));
                    srcY = Math.max(0.5f, Math.min(srcY, sy - 1.5f));
                    srcZ = Math.max(0.5f, Math.min(srcZ, sz - 1.5f));

                    // 三線性插值
                    int x0 = (int) srcX, y0 = (int) srcY, z0 = (int) srcZ;
                    float fx = srcX - x0, fy = srcY - y0, fz = srcZ - z0;
                    int x1 = Math.min(x0 + 1, sx - 1);
                    int y1 = Math.min(y0 + 1, sy - 1);
                    int z1 = Math.min(z0 + 1, sz - 1);

                    newVx[idx] = trilinear(velocityX, region, x0, y0, z0, x1, y1, z1, fx, fy, fz);
                    newVy[idx] = trilinear(velocityY, region, x0, y0, z0, x1, y1, z1, fx, fy, fz);
                    newVz[idx] = trilinear(velocityZ, region, x0, y0, z0, x1, y1, z1, fx, fy, fz);
                }
            }
        }

        System.arraycopy(newVx, 0, velocityX, 0, velocityX.length);
        System.arraycopy(newVy, 0, velocityY, 0, velocityY.length);
        System.arraycopy(newVz, 0, velocityZ, 0, velocityZ.length);
    }

    /**
     * 計算速度場散度 ∇·u（壓力投射步的源項）。
     */
    public static void computeDivergence(float[] vx, float[] vy, float[] vz,
                                          DiffusionRegion region) {
        int sx = region.getSizeX(), sy = region.getSizeY(), sz = region.getSizeZ();
        float[] source = region.getSource();

        for (int z = 0; z < sz; z++) {
            for (int y = 0; y < sy; y++) {
                for (int x = 0; x < sx; x++) {
                    int idx = region.flatIndex(x, y, z);
                    if (region.getType()[idx] == DiffusionRegion.TYPE_SOLID_WALL) {
                        source[idx] = 0f;
                        continue;
                    }
                    // 中心差分：∇·u = (u_{x+1}-u_{x-1})/2 + (v_{y+1}-v_{y-1})/2 + (w_{z+1}-w_{z-1})/2
                    float dvx = safeGet(vx, region, x+1,y,z) - safeGet(vx, region, x-1,y,z);
                    float dvy = safeGet(vy, region, x,y+1,z) - safeGet(vy, region, x,y-1,z);
                    float dvz = safeGet(vz, region, x,y,z+1) - safeGet(vz, region, x,y,z-1);
                    source[idx] = -(dvx + dvy + dvz) * 0.5f;
                }
            }
        }
    }

    /**
     * 從壓力場修正速度：u = u* - ∇p
     */
    public static void projectVelocity(float[] vx, float[] vy, float[] vz,
                                        DiffusionRegion region) {
        int sx = region.getSizeX(), sy = region.getSizeY(), sz = region.getSizeZ();
        float[] phi = region.getPhi();

        for (int z = 0; z < sz; z++) {
            for (int y = 0; y < sy; y++) {
                for (int x = 0; x < sx; x++) {
                    int idx = region.flatIndex(x, y, z);
                    if (region.getType()[idx] == DiffusionRegion.TYPE_SOLID_WALL) continue;

                    float gradPx = (safeGetPhi(phi, region, x+1,y,z) - safeGetPhi(phi, region, x-1,y,z)) * 0.5f;
                    float gradPy = (safeGetPhi(phi, region, x,y+1,z) - safeGetPhi(phi, region, x,y-1,z)) * 0.5f;
                    float gradPz = (safeGetPhi(phi, region, x,y,z+1) - safeGetPhi(phi, region, x,y,z-1)) * 0.5f;

                    vx[idx] -= gradPx;
                    vy[idx] -= gradPy;
                    vz[idx] -= gradPz;
                }
            }
        }
    }

    // ─── 輔助 ───

    private static float trilinear(float[] arr, DiffusionRegion r,
                                    int x0, int y0, int z0, int x1, int y1, int z1,
                                    float fx, float fy, float fz) {
        float c000 = arr[r.flatIndex(x0, y0, z0)];
        float c100 = arr[r.flatIndex(x1, y0, z0)];
        float c010 = arr[r.flatIndex(x0, y1, z0)];
        float c110 = arr[r.flatIndex(x1, y1, z0)];
        float c001 = arr[r.flatIndex(x0, y0, z1)];
        float c101 = arr[r.flatIndex(x1, y0, z1)];
        float c011 = arr[r.flatIndex(x0, y1, z1)];
        float c111 = arr[r.flatIndex(x1, y1, z1)];

        float c00 = c000 * (1-fx) + c100 * fx;
        float c10 = c010 * (1-fx) + c110 * fx;
        float c01 = c001 * (1-fx) + c101 * fx;
        float c11 = c011 * (1-fx) + c111 * fx;
        float c0  = c00  * (1-fy) + c10  * fy;
        float c1  = c01  * (1-fy) + c11  * fy;
        return c0 * (1-fz) + c1 * fz;
    }

    private static float safeGet(float[] arr, DiffusionRegion r, int x, int y, int z) {
        int sx = r.getSizeX(), sy = r.getSizeY(), sz = r.getSizeZ();
        x = Math.max(0, Math.min(x, sx-1));
        y = Math.max(0, Math.min(y, sy-1));
        z = Math.max(0, Math.min(z, sz-1));
        return arr[r.flatIndex(x, y, z)];
    }

    private static float safeGetPhi(float[] phi, DiffusionRegion r, int x, int y, int z) {
        int sx = r.getSizeX(), sy = r.getSizeY(), sz = r.getSizeZ();
        x = Math.max(0, Math.min(x, sx-1));
        y = Math.max(0, Math.min(y, sy-1));
        z = Math.max(0, Math.min(z, sz-1));
        return phi[r.flatIndex(x, y, z)];
    }
}
