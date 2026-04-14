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

    /**
     * Sub-cell 邊長（公尺）= BLOCK_SIZE_M / SUB = 0.1 m。
     * Semi-Lagrangian 回追需以此值為分母，將 m/s 速度轉換為
     * 每 tick 的 sub-cell 位移。使用 BLOCK_SIZE_M（1.0m）會造成
     * 位移值偏小 10 倍，流體幾乎靜止。
     */
    private static final float CELL_SIZE_M = FluidConstants.BLOCK_SIZE_M / FluidRegion.SUB; // 0.1 m

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
                // ★ 棋盤格奇偶分割：只處理 (x+y+z) % 2 == parity 的體素。
                // 推導：令 xStart = (y+z+parity)&1，則 (xStart+y+z)%2 = parity。
                // 之後 x += 2 保持奇偶不變。⚠️ 此邏輯依賴 z→y→x 迴圈順序。
                int xStart = (y + z + parity) & 1;
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
    //  Stable Fluids（Stam 1999）— sub-cell NS 求解器
    //  CPU fallback 路徑：不依賴 GPU，每 tick 執行四步驟。
    //  作用於 FluidRegion 的 sub-cell 陣列（vx/vy/vz/vof/subPressure）。
    // ═══════════════════════════════════════════════════════

    /**
     * Step 0：Semi-Lagrangian 標量場對流（VOF、密度等純量通用）。
     *
     * <p>與 {@link #advectVelocity} 使用相同的回追算法；
     * 但作用於單一 {@code float[]} 場，並將結果 clamp 到 [0, 1]（適用於 VOF）。
     *
     * <p>★ 這是 VOF 靜止 bug 的修復：不呼叫此方法時，vof[] 永遠不變，
     * Marching Cubes / 像素風渲染永遠顯示初始液面，無波動或流動。
     *
     * @param field 要對流的標量場（原地更新，結果 clamp 至 [0, 1]）
     * @param r     流體區域（提供速度場 vx/vy/vz）
     * @param dt    時間步長（s）
     */
    public static void advectScalar(float[] field, FluidRegion r, float dt) {
        int sx = r.getSubSX(), sy = r.getSubSY(), sz = r.getSubSZ();
        float[] vx = r.getVx(), vy = r.getVy(), vz = r.getVz();
        float[] old = field.clone();  // 雙緩衝：讀 old，寫 field

        for (int gz = 0; gz < sz; gz++) {
            for (int gy = 0; gy < sy; gy++) {
                for (int gx = 0; gx < sx; gx++) {
                    int idx = gx + gy * sx + gz * sx * sy;
                    // 回追粒子位置（semi-Lagrangian backward advection）
                    // 速度 m/s ÷ sub-cell 邊長 0.1m = sub-cell/s，再乘 dt 得 sub-cell 位移
                    float px = gx - dt * vx[idx] / CELL_SIZE_M;
                    float py = gy - dt * vy[idx] / CELL_SIZE_M;
                    float pz = gz - dt * vz[idx] / CELL_SIZE_M;
                    px = Math.max(0f, Math.min(sx - 1.001f, px));
                    py = Math.max(0f, Math.min(sy - 1.001f, py));
                    pz = Math.max(0f, Math.min(sz - 1.001f, pz));
                    float newVal = trilinear(old, px, py, pz, sx, sy, sz);
                    // VOF 必須保持在 [0, 1]
                    field[idx] = Math.max(0f, Math.min(1f, newVal));
                }
            }
        }
    }

    /**
     * 對 vx/vy/vz 陣列進行對流，保持速度場的 Lagrangian 守恆性。
     *
     * @param r  流體區域
     * @param dt 時間步長（s）
     */
    public static void advectVelocity(FluidRegion r, float dt) {
        int sx = r.getSubSX(), sy = r.getSubSY(), sz = r.getSubSZ();
        float[] vx = r.getVx(), vy = r.getVy(), vz = r.getVz();
        float[] vxOld = vx.clone(), vyOld = vy.clone(), vzOld = vz.clone();

        for (int gz = 0; gz < sz; gz++) {
            for (int gy = 0; gy < sy; gy++) {
                for (int gx = 0; gx < sx; gx++) {
                    int idx = gx + gy * sx + gz * sx * sy;
                    // 回追粒子位置（semi-Lagrangian backward advection）
                    // 速度 m/s ÷ CELL_SIZE_M 0.1m → sub-cell 位移
                    float px = gx - dt * vxOld[idx] / CELL_SIZE_M;
                    float py = gy - dt * vyOld[idx] / CELL_SIZE_M;
                    float pz = gz - dt * vzOld[idx] / CELL_SIZE_M;
                    // clamp 到域內
                    px = Math.max(0f, Math.min(sx - 1.001f, px));
                    py = Math.max(0f, Math.min(sy - 1.001f, py));
                    pz = Math.max(0f, Math.min(sz - 1.001f, pz));
                    // trilinear interp
                    vx[idx] = trilinear(vxOld, px, py, pz, sx, sy, sz);
                    vy[idx] = trilinear(vyOld, px, py, pz, sx, sy, sz);
                    vz[idx] = trilinear(vzOld, px, py, pz, sx, sy, sz);
                }
            }
        }
    }

    /** Trilinear interpolation over a flat 3-D array. */
    private static float trilinear(float[] f, float px, float py, float pz,
                                   int sx, int sy, int sz) {
        int x0 = (int) px, y0 = (int) py, z0 = (int) pz;
        int x1 = Math.min(x0 + 1, sx - 1);
        int y1 = Math.min(y0 + 1, sy - 1);
        int z1 = Math.min(z0 + 1, sz - 1);
        float fx = px - x0, fy = py - y0, fz = pz - z0;
        float c000 = f[x0 + y0 * sx + z0 * sx * sy];
        float c100 = f[x1 + y0 * sx + z0 * sx * sy];
        float c010 = f[x0 + y1 * sx + z0 * sx * sy];
        float c110 = f[x1 + y1 * sx + z0 * sx * sy];
        float c001 = f[x0 + y0 * sx + z1 * sx * sy];
        float c101 = f[x1 + y0 * sx + z1 * sx * sy];
        float c011 = f[x0 + y1 * sx + z1 * sx * sy];
        float c111 = f[x1 + y1 * sx + z1 * sx * sy];
        return (1 - fz) * ((1 - fy) * ((1 - fx) * c000 + fx * c100) + fy * ((1 - fx) * c010 + fx * c110))
             +       fz  * ((1 - fy) * ((1 - fx) * c001 + fx * c101) + fy * ((1 - fx) * c011 + fx * c111));
    }

    /**
     * Step 2：施加重力加速度（vy -= g·dt）。
     *
     * @param r  流體區域
     * @param dt 時間步長（s）
     */
    public static void applyGravity(FluidRegion r, float dt) {
        float[] vy = r.getVy();
        float[] vof = r.getVof();
        float dv = (float) FluidConstants.GRAVITY * dt;
        for (int i = 0; i < r.getSubTotalVoxels(); i++) {
            if (vof[i] > FluidConstants.MIN_VOLUME_FRACTION) {
                vy[i] -= dv;
            }
        }
    }

    /**
     * Step 3：計算速度場散度 ∇·u（Poisson 方程右端項 = ∇·u/dt）。
     *
     * @param r 流體區域
     * @return 散度陣列（sub-cell level，長度 = subTotalVoxels）
     */
    public static float[] computeDivergence(FluidRegion r) {
        int sx = r.getSubSX(), sy = r.getSubSY(), sz = r.getSubSZ();
        float[] vx = r.getVx(), vy = r.getVy(), vz = r.getVz();
        float[] div = new float[r.getSubTotalVoxels()];
        float h = FluidConstants.BLOCK_SIZE_M / FluidRegion.SUB; // 0.1m

        for (int gz = 0; gz < sz; gz++) {
            for (int gy = 0; gy < sy; gy++) {
                for (int gx = 0; gx < sx; gx++) {
                    int idx = gx + gy * sx + gz * sx * sy;
                    float dvx = vx[Math.min(gx + 1, sx - 1) + gy * sx + gz * sx * sy]
                              - vx[Math.max(gx - 1, 0) + gy * sx + gz * sx * sy];
                    float dvy = vy[gx + Math.min(gy + 1, sy - 1) * sx + gz * sx * sy]
                              - vy[gx + Math.max(gy - 1, 0) * sx + gz * sx * sy];
                    float dvz = vz[gx + gy * sx + Math.min(gz + 1, sz - 1) * sx * sy]
                              - vz[gx + gy * sx + Math.max(gz - 1, 0) * sx * sy];
                    div[idx] = (dvx + dvy + dvz) / (2f * h);
                }
            }
        }
        return div;
    }

    /**
     * Step 4a：Jacobi 壓力求解（Poisson ∇²p = ∇·u/dt）。
     * 迭代收斂後壓力場寫入 region.subPressure[]。
     *
     * @param r          流體區域
     * @param iterations Jacobi 迭代次數
     */
    public static void jacobiPressureSolve(FluidRegion r, int iterations) {
        int sx = r.getSubSX(), sy = r.getSubSY(), sz = r.getSubSZ();
        float[] p = r.getSubPressure();
        float[] div = computeDivergence(r);
        float h2 = (float) Math.pow(FluidConstants.BLOCK_SIZE_M / FluidRegion.SUB, 2); // (0.1m)²
        float[] pNew = new float[p.length];

        for (int iter = 0; iter < iterations; iter++) {
            for (int gz = 0; gz < sz; gz++) {
                for (int gy = 0; gy < sy; gy++) {
                    for (int gx = 0; gx < sx; gx++) {
                        int idx = gx + gy * sx + gz * sx * sy;
                        // 6-neighbour average
                        float sum = 0f;
                        sum += p[Math.min(gx+1,sx-1) + gy*sx + gz*sx*sy];
                        sum += p[Math.max(gx-1,0)   + gy*sx + gz*sx*sy];
                        sum += p[gx + Math.min(gy+1,sy-1)*sx + gz*sx*sy];
                        sum += p[gx + Math.max(gy-1,0)*sx   + gz*sx*sy];
                        sum += p[gx + gy*sx + Math.min(gz+1,sz-1)*sx*sy];
                        sum += p[gx + gy*sx + Math.max(gz-1,0)*sx*sy];
                        pNew[idx] = (sum - h2 * div[idx]) / 6f;
                    }
                }
            }
            System.arraycopy(pNew, 0, p, 0, p.length);
        }
    }

    /**
     * Step 4b：速度場投影（u -= ∇p，使速度場無散度）。
     * 必須在 {@link #jacobiPressureSolve} 後呼叫。
     *
     * @param r 流體區域
     */
    public static void projectVelocity(FluidRegion r) {
        int sx = r.getSubSX(), sy = r.getSubSY(), sz = r.getSubSZ();
        float[] p = r.getSubPressure();
        float[] vx = r.getVx(), vy = r.getVy(), vz = r.getVz();
        float h2 = 2f * FluidConstants.BLOCK_SIZE_M / FluidRegion.SUB; // 2×0.1m

        for (int gz = 0; gz < sz; gz++) {
            for (int gy = 0; gy < sy; gy++) {
                for (int gx = 0; gx < sx; gx++) {
                    int idx = gx + gy * sx + gz * sx * sy;
                    float dpx = p[Math.min(gx+1,sx-1) + gy*sx + gz*sx*sy]
                              - p[Math.max(gx-1,0)   + gy*sx + gz*sx*sy];
                    float dpy = p[gx + Math.min(gy+1,sy-1)*sx + gz*sx*sy]
                              - p[gx + Math.max(gy-1,0)*sx   + gz*sx*sy];
                    float dpz = p[gx + gy*sx + Math.min(gz+1,sz-1)*sx*sy]
                              - p[gx + gy*sx + Math.max(gz-1,0)*sx*sy];
                    vx[idx] -= dpx / h2;
                    vy[idx] -= dpy / h2;
                    vz[idx] -= dpz / h2;
                }
            }
        }
    }

    /**
     * 完整 Stable Fluids 一步（advect → gravity → pressure solve → project）。
     * 這是 CPU 路徑的單 tick 入口。
     *
     * @param r              流體區域
     * @param dt             時間步長（s），通常 FluidConstants.TICK_DT
     * @param pressureIters  Jacobi 壓力迭代次數
     */
    public static void stableFluidsStep(FluidRegion r, float dt, int pressureIters) {
        advectVelocity(r, dt);
        advectScalar(r.getVof(), r, dt);   // ★ 修復：VOF 對流使液面隨速度場移動
        applyGravity(r, dt);
        jacobiPressureSolve(r, pressureIters);
        projectVelocity(r);
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
