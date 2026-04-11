package com.blockreality.api.client.render;

import com.blockreality.api.physics.fluid.FluidRegion;
import com.blockreality.api.physics.fluid.FluidType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 流體粒子發射器 — 在高速流體-固體邊界處發射水花和泡沫粒子。
 *
 * <p>掃描 {@link FluidRegion} sub-cell 速度場，在速度 ≥ {@link #SPLASH_THRESHOLD}
 * 且相鄰非流體 sub-cell 的邊界位置發射粒子，提供視覺水花效果。
 *
 * <h3>發射條件</h3>
 * <ul>
 *   <li>速度量值 ≥ {@link #SPLASH_THRESHOLD} (2.0 m/s)</li>
 *   <li>當前 sub-cell 為流體（VOF > 0.1）</li>
 *   <li>至少一個 6-鄰居 sub-cell 為空氣（VOF < 0.1）或固體</li>
 * </ul>
 *
 * <p>粒子發射委託給 {@link AnimationEngine}（若可用），
 * 否則直接呼叫 Minecraft 原版粒子系統作為回退。
 *
 * @see FluidSurfaceMesher
 * @see FluidRenderBridge
 */
@OnlyIn(Dist.CLIENT)
public class FluidSplashEmitter {

    /** 觸發水花的最低速度量值 (m/s) */
    public static final float SPLASH_THRESHOLD = 2.0f;

    /** VOF 閾值：VOF < MIN_VOF 視為空氣 */
    private static final float MIN_VOF = 0.1f;

    /** 每 tick 最多發射的粒子數（防止過量） */
    private static final int MAX_PARTICLES_PER_TICK = 64;

    /**
     * 掃描 FluidRegion 的 sub-cell 速度場，在高速邊界處發射粒子。
     *
     * <p>每 tick 從客戶端渲染執行緒呼叫（在 {@link FluidRenderBridge#onClientTick} 中）。
     * 方法本身不分配 GPU 資源，所有粒子透過現有 Minecraft 粒子系統發射。
     *
     * @param region     流體區域（提供 vx/vy/vz/vof 資料）
     * @param worldOx    區域世界原點 X（方塊座標）
     * @param worldOy    區域世界原點 Y
     * @param worldOz    區域世界原點 Z
     * @param emitSink   粒子發射回調（接收世界座標 x, y, z 和速度 vx, vy, vz）
     */
    public static void emitAtBoundary(FluidRegion region,
                                      int worldOx, int worldOy, int worldOz,
                                      ParticleEmitSink emitSink) {
        int subSX = region.getSubSX();
        int subSY = region.getSubSY();
        int subSZ = region.getSubSZ();
        float[] vx  = region.getVx();
        float[] vy  = region.getVy();
        float[] vz  = region.getVz();
        float[] vof = region.getVof();

        int emitted = 0;
        float cellSize = 0.1f; // sub-cell 0.1m

        for (int gz = 0; gz < subSZ && emitted < MAX_PARTICLES_PER_TICK; gz++) {
            for (int gy = 0; gy < subSY && emitted < MAX_PARTICLES_PER_TICK; gy++) {
                for (int gx = 0; gx < subSX && emitted < MAX_PARTICLES_PER_TICK; gx++) {
                    int idx = gx + gy * subSX + gz * subSX * subSY;

                    // 必須是流體 sub-cell
                    if (vof[idx] < MIN_VOF) continue;

                    float speed = (float) Math.sqrt(
                        vx[idx]*vx[idx] + vy[idx]*vy[idx] + vz[idx]*vz[idx]);
                    if (speed < SPLASH_THRESHOLD) continue;

                    // 檢查是否有空氣鄰居（邊界條件）
                    if (!hasFreeNeighbor(vof, gx, gy, gz, subSX, subSY, subSZ)) continue;

                    // 計算世界座標
                    float wx = worldOx + gx * cellSize + cellSize * 0.5f;
                    float wy = worldOy + gy * cellSize + cellSize * 0.5f;
                    float wz = worldOz + gz * cellSize + cellSize * 0.5f;

                    emitSink.emit(wx, wy, wz, vx[idx], vy[idx], vz[idx]);
                    emitted++;
                }
            }
        }
    }

    /**
     * 是否有至少一個 6-鄰居的 VOF < MIN_VOF（空氣邊界）。
     */
    private static boolean hasFreeNeighbor(float[] vof,
                                            int gx, int gy, int gz,
                                            int subSX, int subSY, int subSZ) {
        int[][] dirs = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        for (int[] d : dirs) {
            int nx = gx + d[0], ny = gy + d[1], nz = gz + d[2];
            if (nx < 0 || nx >= subSX || ny < 0 || ny >= subSY || nz < 0 || nz >= subSZ) {
                return true; // 邊界 → 視為自由表面
            }
            int nIdx = nx + ny * subSX + nz * subSX * subSY;
            if (vof[nIdx] < MIN_VOF) return true;
        }
        return false;
    }

    /**
     * 粒子發射回調接口（解耦粒子系統依賴）。
     */
    @FunctionalInterface
    public interface ParticleEmitSink {
        /**
         * 在指定世界座標以指定速度發射一個水花粒子。
         *
         * @param wx  世界座標 X
         * @param wy  世界座標 Y
         * @param wz  世界座標 Z
         * @param vx  初速度 X (m/s)
         * @param vy  初速度 Y (m/s)
         * @param vz  初速度 Z (m/s)
         */
        void emit(float wx, float wy, float wz, float vx, float vy, float vz);
    }
}
