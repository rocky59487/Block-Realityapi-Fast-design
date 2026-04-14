package com.blockreality.api.physics.fluid;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 流體-實體耦合器 — 每 tick 對處於流體中的活體實體施加浮力與拖曳力。
 *
 * <h3>物理模型</h3>
 * <ul>
 *   <li><b>浮力</b>（阿基米德）：F_b = ρ × g × V_submerged</li>
 *   <li><b>拖曳力</b>（流速驅動）：F_d = ½ × ρ × Cd × A × v_rel²，方向平行流速</li>
 * </ul>
 *
 * <h3>精度</h3>
 * <p>AABB → sub-cell 格映射使用 0.1m（{@link FluidRegion#SUB}）精度。
 * 部分重疊的 AABB 邊緣格計為全覆蓋（保守估計）。
 *
 * <h3>查詢工具</h3>
 * <p>{@link #querySurfaceY} 允許在任意 XZ 世界座標查詢液面高度，
 * 可用於客戶端波浪碰撞偵測或 AI 導航。
 *
 * <h3>啟用方式</h3>
 * <p>在 Mod 初始化時呼叫 {@link #registerEventListeners()}。
 * 已在 {@link com.blockreality.api.BlockRealityMod} 的 commonSetup 中自動呼叫。
 *
 * @see FluidRegion
 * @see FluidRegionRegistry
 */
public class FluidEntityCoupler {

    // ── 流體物理常數 ──
    private static final float WATER_DENSITY     = 1000f;  // kg/m³
    private static final float DRAG_CD           = 0.8f;   // 人形阻力係數
    private static final float ENTITY_CROSS_AREA = 0.5f;   // m²（人形正面截面估算）
    private static final float GRAVITY           = 9.81f;  // m/s²

    // ── Minecraft 模擬常數 ──
    private static final float TICK_DT      = 0.05f;  // 1/20 s
    private static final float ENTITY_MASS  = 70f;    // kg（人形）
    private static final float MAX_DV       = 0.5f;   // m/s per tick，防爆速上限
    private static final float FLUID_VOF_THRESHOLD = 0.5f;

    // ── 建構子私有（全靜態工具類）──
    private FluidEntityCoupler() {}

    /**
     * 向 Forge EventBus 註冊此類的事件監聽器。
     * 需在 {@code FMLCommonSetupEvent.enqueueWork()} 中呼叫一次。
     */
    public static void registerEventListeners() {
        MinecraftForge.EVENT_BUS.register(FluidEntityCoupler.class);
    }

    // ════════════════════════════════════════════════════
    //  Forge Event — 每 tick 施加流體力
    // ════════════════════════════════════════════════════

    /**
     * 每 living entity tick 觸發（SERVER side）。
     * 掃描所有活動 FluidRegion，計算並施加浮力 + 拖曳力。
     */
    @SubscribeEvent
    public static void onLivingTick(LivingEvent.LivingTickEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide()) return;

        AABB box = entity.getBoundingBox();
        FluidRegionRegistry registry = FluidRegionRegistry.getInstance();

        double totalFx = 0, totalFy = 0, totalFz = 0;
        boolean inFluid = false;

        for (FluidRegion region : registry.getActiveRegions()) {
            if (!overlapsRegion(box, region)) continue;

            ForceResult f = computeForce(box, region);
            if (f.fluidCells == 0) continue;

            inFluid = true;
            totalFx += f.fx;
            totalFy += f.fy;
            totalFz += f.fz;
        }

        if (!inFluid) return;

        // 合力 → 速度增量（F = ma → dv = F·dt/m）
        double dvx = clampDv(totalFx * TICK_DT / ENTITY_MASS);
        double dvy = clampDv(totalFy * TICK_DT / ENTITY_MASS);
        double dvz = clampDv(totalFz * TICK_DT / ENTITY_MASS);

        Vec3 cur = entity.getDeltaMovement();
        entity.setDeltaMovement(cur.x + dvx, cur.y + dvy, cur.z + dvz);
    }

    // ════════════════════════════════════════════════════
    //  公開工具：液面高度查詢
    // ════════════════════════════════════════════════════

    /**
     * 查詢指定 XZ 世界座標的流體表面高度（Y 座標）。
     *
     * <p>從 sub-cell 網格頂部向下掃描，找到第一個 {@code vof > 0.5} 的格，
     * 並以線性插值計算精確 Y 高度（精度 < 0.1m）。
     *
     * @param region 流體區域
     * @param wx     世界座標 X
     * @param wz     世界座標 Z
     * @return 液面 Y 世界座標（float）；無流體時回傳 {@code region.getOriginY()}
     */
    public static float querySurfaceY(FluidRegion region, float wx, float wz) {
        final float cellSize = 0.1f;
        int subSX = region.getSubSX();
        int subSY = region.getSubSY();
        int subSZ = region.getSubSZ();
        float[] vof = region.getVof();

        int gx = clamp((int)((wx - region.getOriginX()) / cellSize), 0, subSX - 1);
        int gz = clamp((int)((wz - region.getOriginZ()) / cellSize), 0, subSZ - 1);

        for (int gy = subSY - 1; gy >= 0; gy--) {
            int idx = gx + gy * subSX + gz * subSX * subSY;
            if (vof[idx] > FLUID_VOF_THRESHOLD) {
                // 線性插值：求等值面精確 Y
                float vAbove = (gy + 1 < subSY)
                    ? vof[gx + (gy + 1) * subSX + gz * subSX * subSY]
                    : 0f;
                float vHere = vof[idx];
                float frac = (vHere - FLUID_VOF_THRESHOLD) / (vHere - vAbove + 1e-6f);
                frac = Math.max(0f, Math.min(1f, frac));
                return region.getOriginY() + (gy + 1 - frac) * cellSize;
            }
        }
        return region.getOriginY();
    }

    // ════════════════════════════════════════════════════
    //  內部實作
    // ════════════════════════════════════════════════════

    private static ForceResult computeForce(AABB box, FluidRegion region) {
        final float cellSize = 0.1f;
        int subSX = region.getSubSX();
        int subSY = region.getSubSY();
        int subSZ = region.getSubSZ();
        float[] vx = region.getVx(), vy = region.getVy(), vz = region.getVz();
        float[] vof = region.getVof();

        // AABB → sub-cell 格座標範圍（clamp 到域內）
        int x0 = clamp((int)((box.minX - region.getOriginX()) / cellSize), 0, subSX - 1);
        int y0 = clamp((int)((box.minY - region.getOriginY()) / cellSize), 0, subSY - 1);
        int z0 = clamp((int)((box.minZ - region.getOriginZ()) / cellSize), 0, subSZ - 1);
        int x1 = clamp((int)Math.ceil((box.maxX - region.getOriginX()) / cellSize), 0, subSX - 1);
        int y1 = clamp((int)Math.ceil((box.maxY - region.getOriginY()) / cellSize), 0, subSY - 1);
        int z1 = clamp((int)Math.ceil((box.maxZ - region.getOriginZ()) / cellSize), 0, subSZ - 1);

        double sumVx = 0, sumVy = 0, sumVz = 0;
        int fluidCells = 0;

        for (int gz = z0; gz <= z1; gz++)
        for (int gy = y0; gy <= y1; gy++)
        for (int gx = x0; gx <= x1; gx++) {
            int i = gx + gy * subSX + gz * subSX * subSY;
            float v = vof[i];
            if (v < FLUID_VOF_THRESHOLD) continue;
            fluidCells++;
            sumVx += vx[i] * v;
            sumVy += vy[i] * v;
            sumVz += vz[i] * v;
        }

        if (fluidCells == 0) return ForceResult.ZERO;

        // 浮力（阿基米德）
        float cellVol   = cellSize * cellSize * cellSize;   // 0.001 m³
        float buoyancy  = fluidCells * cellVol * WATER_DENSITY * GRAVITY;

        // 拖曳力（F_d = ½ρ Cd A |v_rel|·v_rel）
        double invN  = 1.0 / fluidCells;
        double avgVx = sumVx * invN;
        double avgVy = sumVy * invN;
        double avgVz = sumVz * invN;
        double dragK = 0.5 * WATER_DENSITY * DRAG_CD * ENTITY_CROSS_AREA;

        double fx = avgVx * Math.abs(avgVx) * dragK;
        double fy = buoyancy + avgVy * Math.abs(avgVy) * dragK;
        double fz = avgVz * Math.abs(avgVz) * dragK;

        return new ForceResult((float)fx, (float)fy, (float)fz, fluidCells);
    }

    private static boolean overlapsRegion(AABB box, FluidRegion region) {
        return box.maxX >= region.getOriginX()
            && box.minX <= region.getOriginX() + region.getSizeX()
            && box.maxY >= region.getOriginY()
            && box.minY <= region.getOriginY() + region.getSizeY()
            && box.maxZ >= region.getOriginZ()
            && box.minZ <= region.getOriginZ() + region.getSizeZ();
    }

    private static double clampDv(double dv) {
        return Math.max(-MAX_DV, Math.min(MAX_DV, dv));
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** 力計算結果（不可變記錄）。 */
    private record ForceResult(float fx, float fy, float fz, int fluidCells) {
        static final ForceResult ZERO = new ForceResult(0, 0, 0, 0);
    }
}
