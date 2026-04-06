package com.blockreality.api.physics.fluid;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * CPU 參考 Jacobi 求解器 — PFSF-Fluid 的 CPU 回退和測試基準。
 *
 * <p>實作與 GPU {@code fluid_jacobi.comp.glsl} 完全相同的算法，
 * 用於：(1) 無 Vulkan GPU 時的回退執行、(2) GPU 結果的正確性驗證。
 *
 * <h3>核心算法</h3>
 * <pre>
 * 對每個流體體素 i（非空氣、非固體牆）：
 *   1. 累加六鄰居的流入通量：
 *      flux_in = Σ_j min(phi_j, vol_j × density_j × g × h_j) × diffusionRate
 *   2. 計算流出量限制（不可使自身體積為負）：
 *      flux_out = min(phi_i × diffusionRate × 6, vol_i × density_i × g)
 *   3. 更新勢能：
 *      phi_new(i) = phi_old(i) + (flux_in - flux_out) × damping
 *   4. 更新壓力：P_i = density_i × g × h_i
 * </pre>
 */
public class FluidCPUSolver {

    private static final Logger LOGGER = LogManager.getLogger("BR-FluidCPU");

    // 6 鄰居偏移：+X, -X, +Y, -Y, +Z, -Z
    private static final int[][] NEIGHBOR_OFFSETS = {
        {1, 0, 0}, {-1, 0, 0},
        {0, 1, 0}, {0, -1, 0},
        {0, 0, 1}, {0, 0, -1}
    };

    /**
     * 執行一次 Jacobi 迭代步。
     *
     * <p>從 {@code region.getPhiPrev()} 讀取，寫入 {@code region.getPhi()}，
     * 同時更新 {@code region.getPressure()} 和 {@code region.getVolume()}。
     *
     * @param region 目標流體區域
     * @param diffusionRate 擴散率 [0, MAX_DIFFUSION_RATE]
     * @return 本步最大勢能變化量（用於收斂判定）
     */
    public static float jacobiStep(FluidRegion region, float diffusionRate) {
        int sx = region.getSizeX();
        int sy = region.getSizeY();
        int sz = region.getSizeZ();

        float[] phi = region.getPhi();
        float[] phiPrev = region.getPhiPrev();
        byte[] type = region.getType();
        float[] density = region.getDensity();
        float[] volume = region.getVolume();
        float[] pressure = region.getPressure();

        float g = (float) FluidConstants.GRAVITY;
        float damping = FluidConstants.DAMPING_FACTOR;
        float maxDelta = 0f;

        // 備份 phi → phiPrev（雙緩衝）
        System.arraycopy(phi, 0, phiPrev, 0, phi.length);

        for (int z = 0; z < sz; z++) {
            for (int y = 0; y < sy; y++) {
                for (int x = 0; x < sx; x++) {
                    int idx = region.flatIndex(x, y, z);
                    FluidType ft = FluidType.fromId(type[idx] & 0xFF);

                    // 跳過空氣和固體
                    if (!ft.isFlowable()) continue;
                    if (volume[idx] < FluidConstants.MIN_VOLUME_FRACTION) continue;

                    float myPhi = phiPrev[idx];
                    float myDensity = density[idx];
                    float myHeight = y + region.getOriginY();
                    float gravityPotential = myDensity * g * myHeight;

                    float totalPhiIn = 0f;
                    int validNeighbors = 0;

                    for (int[] offset : NEIGHBOR_OFFSETS) {
                        int nx = x + offset[0];
                        int ny = y + offset[1];
                        int nz = z + offset[2];

                        // 邊界檢查 — 邊界視為 Neumann BC（零通量）
                        if (nx < 0 || nx >= sx || ny < 0 || ny >= sy || nz < 0 || nz >= sz) {
                            continue;
                        }

                        int nIdx = region.flatIndex(nx, ny, nz);
                        FluidType nft = FluidType.fromId(type[nIdx] & 0xFF);

                        // 固體牆面：不流通
                        if (nft == FluidType.SOLID_WALL) continue;

                        // 鄰居的總勢能 = 流體勢能 + 重力勢能
                        float nHeight = ny + region.getOriginY();
                        float nGravPotential = density[nIdx] * g * nHeight;
                        float nTotalPotential = phiPrev[nIdx] + nGravPotential;

                        totalPhiIn += nTotalPotential;
                        validNeighbors++;
                    }

                    if (validNeighbors == 0) continue;

                    // Jacobi 更新：新勢能 = 鄰居平均總勢能 - 自身重力勢能
                    float avgNeighborPhi = totalPhiIn / validNeighbors;
                    float newPhi = myPhi + (avgNeighborPhi - gravityPotential - myPhi) * diffusionRate * damping;

                    // Outflow 限制：不可使勢能為負
                    newPhi = Math.max(newPhi, 0f);

                    // NaN/Inf 保護
                    if (Float.isNaN(newPhi) || Float.isInfinite(newPhi)) {
                        newPhi = 0f;
                    }
                    if (newPhi > FluidConstants.MAX_PHI_VALUE) {
                        newPhi = FluidConstants.MAX_PHI_VALUE;
                    }

                    phi[idx] = newPhi;

                    // 更新體積分率（從勢能推導）
                    if (myDensity > 0) {
                        float maxPhi = myDensity * g * (myHeight + 1.0f); // 滿格水的勢能
                        volume[idx] = Math.min(newPhi / Math.max(maxPhi, 1e-6f), 1.0f);
                        volume[idx] = Math.max(volume[idx], FluidConstants.VOLUME_FLOOR);
                    }

                    // 更新壓力
                    pressure[idx] = newPhi;

                    float delta = Math.abs(newPhi - myPhi);
                    maxDelta = Math.max(maxDelta, delta);
                }
            }
        }

        return maxDelta;
    }

    /**
     * 執行多步 Jacobi 迭代直到收斂或達到最大步數。
     *
     * @param region 目標流體區域
     * @param maxIterations 最大迭代次數
     * @param diffusionRate 擴散率
     * @return 實際執行的迭代次數
     */
    public static int solve(FluidRegion region, int maxIterations, float diffusionRate) {
        float clampedRate = Math.min(diffusionRate, FluidConstants.MAX_DIFFUSION_RATE);

        for (int iter = 0; iter < maxIterations; iter++) {
            float maxDelta = jacobiStep(region, clampedRate);

            if (maxDelta < FluidConstants.CONVERGENCE_THRESHOLD) {
                LOGGER.debug("[BR-FluidCPU] Converged after {} iterations (maxDelta={})", iter + 1, maxDelta);
                return iter + 1;
            }
        }

        return maxIterations;
    }

    /**
     * 計算區域內所有流體的總體積（質量守恆驗證用）。
     *
     * @param region 流體區域
     * @return 總體積（方塊數 × 體積分率的和）
     */
    public static double computeTotalVolume(FluidRegion region) {
        float[] volume = region.getVolume();
        byte[] type = region.getType();
        double total = 0;
        for (int i = 0; i < region.getTotalVoxels(); i++) {
            FluidType ft = FluidType.fromId(type[i] & 0xFF);
            if (ft.isFlowable()) {
                total += volume[i];
            }
        }
        return total;
    }
}
