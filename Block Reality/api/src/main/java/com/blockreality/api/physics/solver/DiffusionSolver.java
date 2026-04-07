package com.blockreality.api.physics.solver;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 通用擴散求解器 — 所有物理域共用的 Jacobi/RBGS 求解器。
 *
 * <p>求解 ∇·(σ∇φ) = f，其中 σ、f、φ 的物理含義由各域的
 * {@link DomainTranslator} 定義。
 *
 * <h3>Ghost Cell Neumann BC（零通量邊界）</h3>
 * <p>固體牆/域外方向注入 ghost cell：H_ghost = H_current。
 *
 * <h3>重力項控制</h3>
 * <p>{@code gravityWeight=1.0} 時（流體），將 ρgh 加入總勢能；
 * {@code gravityWeight=0.0} 時（熱/EM/風），純擴散無重力。
 *
 * <p>此類從 {@code FluidCPUSolver} 提取通用邏輯，避免 4 個域各自重複。
 */
public class DiffusionSolver {

    private static final Logger LOGGER = LogManager.getLogger("BR-DiffSolver");

    private static final float CONVERGENCE_THRESHOLD = 1e-4f;
    private static final float DAMPING_FACTOR = 0.998f;
    private static final float MAX_PHI_VALUE = 1e8f;
    private static final float GRAVITY = 9.81f;

    private static final int[][] NEIGHBOR_OFFSETS = {
        {1, 0, 0}, {-1, 0, 0},
        {0, 1, 0}, {0, -1, 0},
        {0, 0, 1}, {0, 0, -1}
    };

    // ═══════════════════════════════════════════════════════
    //  Jacobi 求解器（雙緩衝）
    // ═══════════════════════════════════════════════════════

    /**
     * 執行一次通用 Jacobi 迭代步。
     *
     * @param region        通用擴散區域
     * @param diffusionRate 擴散率
     * @param gravityWeight 重力權重：0=無重力, 1=流體重力
     * @return 最大殘差
     */
    public static float jacobiStep(DiffusionRegion region, float diffusionRate, float gravityWeight) {
        int sx = region.getSizeX(), sy = region.getSizeY(), sz = region.getSizeZ();
        float[] phi = region.getPhi();
        float[] phiPrev = region.getPhiPrev();
        float[] sigma = region.getConductivity();
        byte[] type = region.getType();

        float maxDelta = 0f;
        System.arraycopy(phi, 0, phiPrev, 0, phi.length);

        for (int z = 0; z < sz; z++) {
            for (int y = 0; y < sy; y++) {
                for (int x = 0; x < sx; x++) {
                    int idx = region.flatIndex(x, y, z);
                    if (type[idx] != DiffusionRegion.TYPE_ACTIVE) continue;

                    float myPhi = phiPrev[idx];
                    float mySigma = sigma[idx];
                    float myHeight = y + region.getOriginY();
                    float myGravPot = mySigma * GRAVITY * myHeight * gravityWeight;
                    float myTotalHead = myPhi + myGravPot;

                    float totalH = 0f;

                    for (int[] off : NEIGHBOR_OFFSETS) {
                        int nx = x + off[0], ny = y + off[1], nz = z + off[2];
                        if (nx < 0 || nx >= sx || ny < 0 || ny >= sy || nz < 0 || nz >= sz) {
                            totalH += myTotalHead; // ghost cell
                            continue;
                        }
                        int nIdx = region.flatIndex(nx, ny, nz);
                        if (type[nIdx] == DiffusionRegion.TYPE_SOLID_WALL) {
                            totalH += myTotalHead; // ghost cell
                            continue;
                        }
                        float nHeight = ny + region.getOriginY();
                        float nGravPot = sigma[nIdx] * GRAVITY * nHeight * gravityWeight;
                        totalH += phiPrev[nIdx] + nGravPot;
                    }

                    float avgH = totalH / 6f;
                    float newPhi = myPhi + (avgH - myGravPot - myPhi) * diffusionRate * DAMPING_FACTOR;

                    // 源項注入
                    newPhi += region.getSource()[idx] * diffusionRate;

                    if (Float.isNaN(newPhi) || Float.isInfinite(newPhi) || newPhi < -MAX_PHI_VALUE) {
                        newPhi = 0f;
                    } else if (newPhi > MAX_PHI_VALUE) {
                        newPhi = MAX_PHI_VALUE;
                    }

                    phi[idx] = newPhi;
                    float delta = Math.abs(newPhi - myPhi);
                    if (delta > maxDelta) maxDelta = delta;
                }
            }
        }
        return maxDelta;
    }

    // ═══════════════════════════════════════════════════════
    //  RBGS 求解器（原地更新，~2× 收斂）
    // ═══════════════════════════════════════════════════════

    /**
     * 執行一次 RBGS full sweep（red + black pass）。
     */
    public static float rbgsStep(DiffusionRegion region, float diffusionRate, float gravityWeight) {
        float maxDelta = 0f;
        for (int parity = 0; parity <= 1; parity++) {
            float d = rbgsPass(region, diffusionRate, gravityWeight, parity);
            if (d > maxDelta) maxDelta = d;
        }
        return maxDelta;
    }

    private static float rbgsPass(DiffusionRegion region, float rate, float gw, int parity) {
        int sx = region.getSizeX(), sy = region.getSizeY(), sz = region.getSizeZ();
        float[] phi = region.getPhi();
        float[] sigma = region.getConductivity();
        float[] src = region.getSource();
        byte[] type = region.getType();
        float maxDelta = 0f;

        for (int z = 0; z < sz; z++) {
            for (int y = 0; y < sy; y++) {
                // 棋盤格奇偶：(xStart+y+z)%2 = parity
                int xStart = (y + z + parity) & 1;
                for (int x = xStart; x < sx; x += 2) {
                    int idx = region.flatIndex(x, y, z);
                    if (type[idx] != DiffusionRegion.TYPE_ACTIVE) continue;

                    float myPhi = phi[idx];
                    float mySigma = sigma[idx];
                    float myHeight = y + region.getOriginY();
                    float myGravPot = mySigma * GRAVITY * myHeight * gw;
                    float myTotalHead = myPhi + myGravPot;

                    float totalH = 0f;
                    for (int[] off : NEIGHBOR_OFFSETS) {
                        int nx = x + off[0], ny = y + off[1], nz = z + off[2];
                        if (nx < 0 || nx >= sx || ny < 0 || ny >= sy || nz < 0 || nz >= sz) {
                            totalH += myTotalHead;
                            continue;
                        }
                        int nIdx = region.flatIndex(nx, ny, nz);
                        if (type[nIdx] == DiffusionRegion.TYPE_SOLID_WALL) {
                            totalH += myTotalHead;
                            continue;
                        }
                        float nH = ny + region.getOriginY();
                        totalH += phi[nIdx] + sigma[nIdx] * GRAVITY * nH * gw;
                    }

                    float avgH = totalH / 6f;
                    float newPhi = myPhi + (avgH - myGravPot - myPhi) * rate * DAMPING_FACTOR;
                    newPhi += src[idx] * rate;

                    if (Float.isNaN(newPhi) || Float.isInfinite(newPhi) || newPhi < -MAX_PHI_VALUE) {
                        newPhi = 0f;
                    } else if (newPhi > MAX_PHI_VALUE) {
                        newPhi = MAX_PHI_VALUE;
                    }

                    float delta = Math.abs(newPhi - myPhi);
                    if (delta > maxDelta) maxDelta = delta;
                    phi[idx] = newPhi;
                }
            }
        }
        return maxDelta;
    }

    // ═══════════════════════════════════════════════════════
    //  收斂求解器
    // ═══════════════════════════════════════════════════════

    /** Jacobi 求解直到收斂。 */
    public static int solve(DiffusionRegion region, int maxIter, float rate, float gravityWeight) {
        for (int i = 0; i < maxIter; i++) {
            float d = jacobiStep(region, rate, gravityWeight);
            if (d < CONVERGENCE_THRESHOLD) {
                LOGGER.debug("[DiffSolver] Jacobi converged after {} iters (delta={})", i + 1, d);
                return i + 1;
            }
        }
        return maxIter;
    }

    /** RBGS 求解直到收斂。 */
    public static int rbgsSolve(DiffusionRegion region, int maxIter, float rate, float gravityWeight) {
        for (int i = 0; i < maxIter; i++) {
            float d = rbgsStep(region, rate, gravityWeight);
            if (d < CONVERGENCE_THRESHOLD) {
                LOGGER.debug("[DiffSolver] RBGS converged after {} sweeps (delta={})", i + 1, d);
                return i + 1;
            }
        }
        return maxIter;
    }

    /** 計算最大殘差。 */
    public static float computeMaxResidual(DiffusionRegion region, float gravityWeight) {
        int sx = region.getSizeX(), sy = region.getSizeY(), sz = region.getSizeZ();
        float[] phi = region.getPhi();
        float[] sigma = region.getConductivity();
        byte[] type = region.getType();
        float maxRes = 0f;

        for (int z = 0; z < sz; z++) {
            for (int y = 0; y < sy; y++) {
                for (int x = 0; x < sx; x++) {
                    int idx = region.flatIndex(x, y, z);
                    if (type[idx] != DiffusionRegion.TYPE_ACTIVE) continue;
                    float h = y + region.getOriginY();
                    float myH = phi[idx] + sigma[idx] * GRAVITY * h * gravityWeight;
                    float totalH = 0f;
                    for (int[] off : NEIGHBOR_OFFSETS) {
                        int nx = x + off[0], ny = y + off[1], nz = z + off[2];
                        if (nx < 0 || nx >= sx || ny < 0 || ny >= sy || nz < 0 || nz >= sz) {
                            totalH += myH; continue;
                        }
                        int nIdx = region.flatIndex(nx, ny, nz);
                        if (type[nIdx] == DiffusionRegion.TYPE_SOLID_WALL) {
                            totalH += myH; continue;
                        }
                        float nH = ny + region.getOriginY();
                        totalH += phi[nIdx] + sigma[nIdx] * GRAVITY * nH * gravityWeight;
                    }
                    float res = Math.abs(totalH / 6f - myH);
                    if (res > maxRes) maxRes = res;
                }
            }
        }
        return maxRes;
    }
}
