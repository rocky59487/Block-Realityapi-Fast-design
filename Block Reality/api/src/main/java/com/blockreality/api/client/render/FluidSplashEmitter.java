package com.blockreality.api.client.render;

import com.blockreality.api.physics.fluid.FluidRegion;
import com.blockreality.api.physics.fluid.FluidType;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.concurrent.ConcurrentHashMap;

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

    /** 壓縮波觸發壓力閾值（4 atm in Pa） */
    private static final float COMPRESSION_PRESSURE_THRESHOLD = 4.0f * 101325.0f;

    /** 壓縮波冷卻（regionId → 上次觸發的遊戲 tick） */
    private static final ConcurrentHashMap<Integer, Long> compressionCooldown = new ConcurrentHashMap<>();

    /** 壓縮波冷卻期（ticks） */
    private static final int COMPRESSION_COOLDOWN_TICKS = 10;

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
     * 壓縮波 BUBBLE 粒子發射 — 壓力超過 4 atm 時，從高壓 sub-cell 向四周散射 BUBBLE 粒子。
     *
     * <p>每個 region 最多每 {@link #COMPRESSION_COOLDOWN_TICKS} ticks 觸發一次。
     *
     * @param region       流體區域
     * @param worldOx      區域世界原點 X（方塊座標）
     * @param worldOy      區域世界原點 Y
     * @param worldOz      區域世界原點 Z
     * @param maxPressure  全場最大壓力（Pa），用於確定擴散速度
     * @param emitSink     粒子發射回調
     */
    public static void emitCompressionWave(FluidRegion region,
                                            int worldOx, int worldOy, int worldOz,
                                            float maxPressure,
                                            ParticleEmitSink emitSink) {
        if (maxPressure < COMPRESSION_PRESSURE_THRESHOLD) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        long currentTick = mc.level.getGameTime();

        int id = region.getRegionId();
        Long lastTick = compressionCooldown.get(id);
        if (lastTick != null && currentTick - lastTick < COMPRESSION_COOLDOWN_TICKS) return;
        compressionCooldown.put(id, currentTick);

        // 找到最高壓 sub-cell（用 block-level pressure 陣列）
        float[] pressure = region.getPressure();
        int subSX = region.getSubSX();
        int subSY = region.getSubSY();
        int subSZ = region.getSubSZ();

        // block-level 陣列的索引範圍可能小於 sub-cell 陣列
        int bSX = region.getSizeX();
        int bSY = region.getSizeY();
        int bSZ = region.getSizeZ();

        int peakBx = bSX / 2, peakBy = bSY / 2, peakBz = bSZ / 2;
        float peakP = 0.0f;
        for (int bz = 0; bz < bSZ; bz++) {
            for (int by = 0; by < bSY; by++) {
                for (int bx = 0; bx < bSX; bx++) {
                    int idx = bx + by * bSX + bz * bSX * bSY;
                    if (pressure[idx] > peakP) {
                        peakP = pressure[idx];
                        peakBx = bx; peakBy = by; peakBz = bz;
                    }
                }
            }
        }

        // 世界座標（方塊中心）
        float cx = worldOx + peakBx + 0.5f;
        float cy = worldOy + peakBy + 0.5f;
        float cz = worldOz + peakBz + 0.5f;

        // 發射 8~12 顆 BUBBLE 粒子向四周擴散
        float pressureRatio = maxPressure / 101325.0f;
        float baseSpeed = 0.3f + pressureRatio * 0.1f;
        int count = 8 + (int)(Math.random() * 5);  // 8~12
        for (int i = 0; i < count; i++) {
            double angle = (2.0 * Math.PI * i) / count;
            float dvx = (float)(Math.cos(angle) * baseSpeed);
            float dvz = (float)(Math.sin(angle) * baseSpeed);
            float dvy = baseSpeed * 0.5f;
            emitSink.emit(cx, cy, cz, dvx, dvy, dvz);
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
