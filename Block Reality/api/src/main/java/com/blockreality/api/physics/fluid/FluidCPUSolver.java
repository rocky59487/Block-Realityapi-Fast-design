package com.blockreality.api.physics.fluid;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * CPU 參考求解器 — PFSF-Fluid 的 CPU 回退和測試基準。
 *
 * <p>提供兩種求解器：
 * <ul>
 *   <li>{@link #solve} — Jacobi（雙緩衝，用於測試與 GPU 比對）</li>
 *   <li>{@link #rbgsSolve} — Red-Black Gauss-Seidel（原地更新，收斂速度 2×，用於遊戲 CPU 回退）</li>
 * </ul>
 *
 * <h3>Ghost Cell Neumann BC（零通量邊界）</h3>
 * <p>對域外或固體牆方向，注入 ghost cell：H_ghost = H_current = φ + ρgh。
 * 這保證「φ = C − ρgh（H = constant）」在整個域中（含角落）是精確不動點，
 * 邊界處零通量 ∂H/∂n = 0 嚴格成立。
 *
 * <h3>體積守恆</h3>
 * <p>體積（volume[]）是獨立守恆量，Jacobi/RBGS 僅平衡勢場 φ；
 * 體積只在顯式源/匯操作時改變（{@link FluidGPUEngine#setFluidSource}）。
 */
public class FluidCPUSolver {

    private static final Logger LOGGER = LogManager.getLogger("BR-FluidCPU");

    // 6 鄰居偏移：+X, -X, +Y, -Y, +Z, -Z
    private static final int[][] NEIGHBOR_OFFSETS = {
        {1, 0, 0}, {-1, 0, 0},
        {0, 1, 0}, {0, -1, 0},
        {0, 0, 1}, {0, 0, -1}
    };

    // ═══════════════════════════════════════════════════════
    //  Jacobi 求解器（雙緩衝，供測試/GPU 比對）
    // ═══════════════════════════════════════════════════════

    /**
     * 執行一次 Jacobi 迭代步（雙緩衝：phiPrev → phi）。
     *
     * <p>固體牆面與域邊界使用 Ghost Cell Neumann BC：H_ghost = H_current，
     * 確保靜水壓狀態（H = const）在任意邊界條件下是精確不動點。
     *
     * @param region      目標流體區域
     * @param diffusionRate 擴散率 [0, MAX_DIFFUSION_RATE]
     * @return 本步最大勢能變化量 maxDelta（用於收斂判定）
     */
    public static float jacobiStep(FluidRegion region, float diffusionRate) {
        int sx = region.getSizeX();
        int sy = region.getSizeY();
        int sz = region.getSizeZ();

        float[] phi    = region.getPhi();
        float[] phiPrev = region.getPhiPrev();
        byte[]  type   = region.getType();
        float[] density = region.getDensity();
        float[] volume  = region.getVolume();
        float[] pressure = region.getPressure();

        float g       = (float) FluidConstants.GRAVITY;
        float damping = FluidConstants.DAMPING_FACTOR;
        float maxDelta = 0f;

        // 備份 phi → phiPrev（雙緩衝讀取）
        System.arraycopy(phi, 0, phiPrev, 0, phi.length);

        for (int z = 0; z < sz; z++) {
            for (int y = 0; y < sy; y++) {
                for (int x = 0; x < sx; x++) {
                    int idx = region.flatIndex(x, y, z);
                    FluidType ft = FluidType.fromId(type[idx] & 0xFF);
                    if (!ft.isFlowable()) continue;
                    if (volume[idx] < FluidConstants.MIN_VOLUME_FRACTION) continue;

                    float myPhi        = phiPrev[idx];
                    float myDensity    = density[idx];
                    float myHeight     = y + region.getOriginY();
                    float myGravPot    = myDensity * g * myHeight; // ρgh
                    float myTotalHead  = myPhi + myGravPot;        // H_current

                    float totalPhiIn = 0f;
                    // Ghost cells 確保 validNeighbors 恆為 6
                    int validNeighbors = 6;

                    for (int[] offset : NEIGHBOR_OFFSETS) {
                        int nx = x + offset[0];
                        int ny = y + offset[1];
                        int nz = z + offset[2];

                        // 域外 or 固體牆：Ghost Cell，H_ghost = H_current（零通量 Neumann BC）
                        if (nx < 0 || nx >= sx || ny < 0 || ny >= sy || nz < 0 || nz >= sz) {
                            totalPhiIn += myTotalHead;
                            continue;
                        }
                        int nIdx = region.flatIndex(nx, ny, nz);
                        FluidType nft = FluidType.fromId(type[nIdx] & 0xFF);
                        if (nft == FluidType.SOLID_WALL) {
                            totalPhiIn += myTotalHead;
                            continue;
                        }

                        float nHeight   = ny + region.getOriginY();
                        float nGravPot  = density[nIdx] * g * nHeight;
                        totalPhiIn += phiPrev[nIdx] + nGravPot;
                    }

                    // Jacobi 更新：φ_new = φ + (avg(H_n) - ρgh - φ) × α × d
                    float avgNeighborH = totalPhiIn / validNeighbors;
                    float newPhi = myPhi + (avgNeighborH - myGravPot - myPhi) * diffusionRate * damping;

                    // Outflow 限制 + NaN 保護
                    if (Float.isNaN(newPhi) || Float.isInfinite(newPhi) || newPhi < 0f) {
                        newPhi = 0f;
                    } else if (newPhi > FluidConstants.MAX_PHI_VALUE) {
                        newPhi = FluidConstants.MAX_PHI_VALUE;
                    }

                    phi[idx] = newPhi;
                    // volume 是獨立守恆量，不從 phi 推導
                    pressure[idx] = newPhi;

                    float delta = Math.abs(newPhi - myPhi);
                    if (delta > maxDelta) maxDelta = delta;
                }
            }
        }

        return maxDelta;
    }

    /**
     * 執行多步 Jacobi 迭代直到收斂或達到最大步數。
     *
     * @param region        目標流體區域
     * @param maxIterations 最大迭代次數
     * @param diffusionRate 擴散率
     * @return 實際執行的迭代次數
     */
    public static int solve(FluidRegion region, int maxIterations, float diffusionRate) {
        float rate = Math.min(diffusionRate, FluidConstants.MAX_DIFFUSION_RATE);
        for (int iter = 0; iter < maxIterations; iter++) {
            float maxDelta = jacobiStep(region, rate);
            if (maxDelta < FluidConstants.CONVERGENCE_THRESHOLD) {
                LOGGER.debug("[BR-FluidCPU] Jacobi converged after {} iters (delta={})", iter + 1, maxDelta);
                return iter + 1;
            }
        }
        return maxIterations;
    }

    // ═══════════════════════════════════════════════════════
    //  Red-Black Gauss-Seidel 求解器（原地更新，遊戲 CPU 回退）
    // ═══════════════════════════════════════════════════════

    /**
     * 執行一次 RBGS full sweep（red pass + black pass，in-place）。
     *
     * <p>相比 Jacobi，RBGS 在相同迭代次數下收斂快約 2×，
     * 因為每次 pass 立即使用已更新的鄰居值。
     * 無需備份 phiPrev，直接在 phi 上更新。
     *
     * @param region        目標流體區域
     * @param diffusionRate 擴散率
     * @return 本 sweep 最大勢能變化量
     */
    public static float rbgsStep(FluidRegion region, float diffusionRate) {
        float maxDelta = 0f;
        // Pass 0 = red (x+y+z 偶數)，Pass 1 = black (x+y+z 奇數)
        for (int parity = 0; parity <= 1; parity++) {
            float passDelta = rbgsPass(region, diffusionRate, parity);
            if (passDelta > maxDelta) maxDelta = passDelta;
        }
        return maxDelta;
    }

    /**
     * 單一 RBGS pass（僅更新 (x+y+z) % 2 == parity 的體素）。
     */
    private static float rbgsPass(FluidRegion region, float diffusionRate, int parity) {
        int sx = region.getSizeX();
        int sy = region.getSizeY();
        int sz = region.getSizeZ();

        float[] phi      = region.getPhi();   // 直接原地讀寫
        byte[]  type     = region.getType();
        float[] density  = region.getDensity();
        float[] volume   = region.getVolume();
        float[] pressure = region.getPressure();

        float g       = (float) FluidConstants.GRAVITY;
        float damping = FluidConstants.DAMPING_FACTOR;
        float maxDelta = 0f;

        for (int z = 0; z < sz; z++) {
            for (int y = 0; y < sy; y++) {
                // 依 parity 跳過半數體素：利用 x 奇偶性避免 (x+y+z)%2 逐體素計算
                int xStart = (y + z + parity) & 1; // 使 x+y+z 的奇偶符合 parity
                for (int x = xStart; x < sx; x += 2) {
                    int idx = region.flatIndex(x, y, z);
                    FluidType ft = FluidType.fromId(type[idx] & 0xFF);
                    if (!ft.isFlowable()) continue;
                    if (volume[idx] < FluidConstants.MIN_VOLUME_FRACTION) continue;

                    float myPhi       = phi[idx];         // 原地讀（可能已被本 pass 更新）
                    float myDensity   = density[idx];
                    float myHeight    = y + region.getOriginY();
                    float myGravPot   = myDensity * g * myHeight;
                    float myTotalHead = myPhi + myGravPot;

                    float totalPhiIn = 0f;

                    for (int[] offset : NEIGHBOR_OFFSETS) {
                        int nx = x + offset[0];
                        int ny = y + offset[1];
                        int nz = z + offset[2];

                        if (nx < 0 || nx >= sx || ny < 0 || ny >= sy || nz < 0 || nz >= sz) {
                            totalPhiIn += myTotalHead;
                            continue;
                        }
                        int nIdx = region.flatIndex(nx, ny, nz);
                        FluidType nft = FluidType.fromId(type[nIdx] & 0xFF);
                        if (nft == FluidType.SOLID_WALL) {
                            totalPhiIn += myTotalHead;
                            continue;
                        }
                        float nHeight  = ny + region.getOriginY();
                        float nGravPot = density[nIdx] * g * nHeight;
                        totalPhiIn += phi[nIdx] + nGravPot; // 使用當前 phi（已更新的鄰居）
                    }

                    float avgNeighborH = totalPhiIn / 6f;
                    float newPhi = myPhi + (avgNeighborH - myGravPot - myPhi) * diffusionRate * damping;

                    if (Float.isNaN(newPhi) || Float.isInfinite(newPhi) || newPhi < 0f) {
                        newPhi = 0f;
                    } else if (newPhi > FluidConstants.MAX_PHI_VALUE) {
                        newPhi = FluidConstants.MAX_PHI_VALUE;
                    }

                    float delta = Math.abs(newPhi - myPhi);
                    if (delta > maxDelta) maxDelta = delta;

                    phi[idx] = newPhi;
                    pressure[idx] = newPhi;
                }
            }
        }
        return maxDelta;
    }

    /**
     * 使用 RBGS 求解直到收斂或達到最大 sweep 數。
     *
     * <p>推薦用於遊戲 CPU 回退路徑；比 {@link #solve} 快 ~2×。
     *
     * @param region        目標流體區域
     * @param maxSweeps     最大 full sweep 數
     * @param diffusionRate 擴散率
     * @return 實際執行的 sweep 數
     */
    public static int rbgsSolve(FluidRegion region, int maxSweeps, float diffusionRate) {
        float rate = Math.min(diffusionRate, FluidConstants.MAX_DIFFUSION_RATE);
        for (int sweep = 0; sweep < maxSweeps; sweep++) {
            float maxDelta = rbgsStep(region, rate);
            if (maxDelta < FluidConstants.CONVERGENCE_THRESHOLD) {
                LOGGER.debug("[BR-FluidCPU] RBGS converged after {} sweeps (delta={})", sweep + 1, maxDelta);
                return sweep + 1;
            }
        }
        return maxSweeps;
    }

    // ═══════════════════════════════════════════════════════
    //  輔助工具
    // ═══════════════════════════════════════════════════════

    /**
     * 計算區域內所有流體的總體積（質量守恆驗證用）。
     *
     * @param region 流體區域
     * @return 總體積（所有流體體素的 volume 分率之和）
     */
    public static double computeTotalVolume(FluidRegion region) {
        float[] volume = region.getVolume();
        byte[]  type   = region.getType();
        double total = 0;
        for (int i = 0; i < region.getTotalVoxels(); i++) {
            if (FluidType.fromId(type[i] & 0xFF).isFlowable()) {
                total += volume[i];
            }
        }
        return total;
    }

    /**
     * 計算流體勢場的最大殘差（收斂監測用）。
     * residual(i) = |avg_H_neighbor - H_i|
     *
     * @param region 流體區域
     * @return 最大殘差
     */
    public static float computeMaxResidual(FluidRegion region) {
        int sx = region.getSizeX();
        int sy = region.getSizeY();
        int sz = region.getSizeZ();
        float[] phi    = region.getPhi();
        byte[]  type   = region.getType();
        float[] density = region.getDensity();
        float[] volume  = region.getVolume();
        float g = (float) FluidConstants.GRAVITY;
        float maxRes = 0f;

        for (int z = 0; z < sz; z++) {
            for (int y = 0; y < sy; y++) {
                for (int x = 0; x < sx; x++) {
                    int idx = region.flatIndex(x, y, z);
                    if (!FluidType.fromId(type[idx] & 0xFF).isFlowable()) continue;
                    if (volume[idx] < FluidConstants.MIN_VOLUME_FRACTION) continue;

                    float myH = phi[idx] + density[idx] * g * (y + region.getOriginY());
                    float totalH = 0f;
                    for (int[] off : NEIGHBOR_OFFSETS) {
                        int nx = x + off[0], ny = y + off[1], nz = z + off[2];
                        if (nx < 0 || nx >= sx || ny < 0 || ny >= sy || nz < 0 || nz >= sz) {
                            totalH += myH; continue;
                        }
                        int nIdx = region.flatIndex(nx, ny, nz);
                        if (FluidType.fromId(type[nIdx] & 0xFF) == FluidType.SOLID_WALL) {
                            totalH += myH; continue;
                        }
                        totalH += phi[nIdx] + density[nIdx] * g * (ny + region.getOriginY());
                    }
                    float res = Math.abs(totalH / 6f - myH);
                    if (res > maxRes) maxRes = res;
                }
            }
        }
        return maxRes;
    }
}
