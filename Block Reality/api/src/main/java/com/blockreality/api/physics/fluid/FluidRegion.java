package com.blockreality.api.physics.fluid;

import net.minecraft.core.BlockPos;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;

/**
 * 流體模擬區域 — 一個矩形體積內的流體狀態容器。
 *
 * <p>儲存 SoA (Structure of Arrays) 布局的流體資料，
 * 與 GPU buffer 一對一對應。CPU 端使用此類進行
 * 參考求解和查詢；GPU 端則直接操作 buffer。
 *
 * <p>陣列索引：<br>
 * - <b>Block-level</b>（既有 Jacobi 路徑）：index = bx + by*sizeX + bz*sizeX*sizeY<br>
 * - <b>Sub-cell level</b>（NS / ML 路徑，0.1m 解析度）：
 *   每個 block 細分為 10×10×10；{@link #subFlat} 計算平坦索引
 */
@ThreadSafe
public class FluidRegion {

    /** 每 block 在每個維度上的 sub-cell 數量（0.1m 解析度） */
    public static final int SUB = 10;

    private final int regionId;
    private final int originX, originY, originZ;
    private final int sizeX, sizeY, sizeZ;
    private final int totalVoxels;

    // sub-cell 尺寸（每個維度 ×SUB）
    private final int subSX, subSY, subSZ;
    private final int subTotalVoxels;

    // ─── SoA 流體資料（block-level，既有 Jacobi / RBGS 路徑） ───
    private final float[] phi;          // 流體勢能
    private final float[] phiPrev;      // 前一步勢能（雙緩衝）
    private final byte[] type;          // FluidType.getId()
    private final float[] density;      // 密度 (kg/m³)
    private final float[] pressure;     // 靜水壓 (Pa)，block-level（Jacobi 路徑寫入）
    private final float[] volume;       // 體積分率 [0,1]

    // ─── SoA 流體資料（sub-cell level，NS / ML 路徑） ───
    private final float[] vx;           // 速度 X (m/s)
    private final float[] vy;           // 速度 Y (m/s)
    private final float[] vz;           // 速度 Z (m/s)
    private final float[] vof;          // Volume-of-Fluid 分率 [0,1]，取代 sub-cell volume
    private final float[] subPressure;  // sub-cell 靜壓（Pa），ML / NS 路徑寫入

    private volatile boolean dirty = false;
    private volatile long lastModifiedTick = 0;

    public FluidRegion(int regionId, int originX, int originY, int originZ,
                       int sizeX, int sizeY, int sizeZ) {
        this.regionId = regionId;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.totalVoxels = sizeX * sizeY * sizeZ;

        this.subSX = sizeX * SUB;
        this.subSY = sizeY * SUB;
        this.subSZ = sizeZ * SUB;
        this.subTotalVoxels = subSX * subSY * subSZ;

        // block-level arrays
        this.phi = new float[totalVoxels];
        this.phiPrev = new float[totalVoxels];
        this.type = new byte[totalVoxels];
        this.density = new float[totalVoxels];
        this.pressure = new float[totalVoxels];
        this.volume = new float[totalVoxels];

        // sub-cell arrays
        this.vx = new float[subTotalVoxels];
        this.vy = new float[subTotalVoxels];
        this.vz = new float[subTotalVoxels];
        this.vof = new float[subTotalVoxels];
        this.subPressure = new float[subTotalVoxels];
    }

    // ─── 座標轉換 ───

    /** 方塊世界座標 → block-level 平坦索引，超出範圍返回 -1 */
    public int flatIndex(@Nonnull BlockPos pos) {
        int lx = pos.getX() - originX;
        int ly = pos.getY() - originY;
        int lz = pos.getZ() - originZ;
        if (lx < 0 || lx >= sizeX || ly < 0 || ly >= sizeY || lz < 0 || lz >= sizeZ) {
            return -1;
        }
        return lx + ly * sizeX + lz * sizeX * sizeY;
    }

    /** 區域內局部 block 座標 → block-level 平坦索引 */
    public int flatIndex(int lx, int ly, int lz) {
        return lx + ly * sizeX + lz * sizeX * sizeY;
    }

    /**
     * sub-cell 平坦索引。
     *
     * @param bx block 局部座標 X [0, sizeX)
     * @param by block 局部座標 Y [0, sizeY)
     * @param bz block 局部座標 Z [0, sizeZ)
     * @param sx sub-cell 偏移 X [0, SUB)
     * @param sy sub-cell 偏移 Y [0, SUB)
     * @param sz sub-cell 偏移 Z [0, SUB)
     * @return 平坦索引 (bx*SUB+sx) + (by*SUB+sy)*subSX + (bz*SUB+sz)*subSX*subSY
     */
    public int subFlat(int bx, int by, int bz, int sx, int sy, int sz) {
        int gx = bx * SUB + sx;
        int gy = by * SUB + sy;
        int gz = bz * SUB + sz;
        return gx + gy * subSX + gz * subSX * subSY;
    }

    /** 檢查世界座標是否在此區域內 */
    public boolean contains(@Nonnull BlockPos pos) {
        int lx = pos.getX() - originX;
        int ly = pos.getY() - originY;
        int lz = pos.getZ() - originZ;
        return lx >= 0 && lx < sizeX && ly >= 0 && ly < sizeY && lz >= 0 && lz < sizeZ;
    }

    // ─── Block-level 壓力（供 FluidStructureCoupler 使用） ───

    /**
     * 取得 block (bx, by, bz) 的平均壓力（10³ sub-cells 算術均值）。
     * 當 ML / NS 路徑已寫入 subPressure[] 時使用此方法，
     * 否則直接使用 pressure[] (block-level Jacobi 結果)。
     *
     * @param bx block 局部座標 X
     * @param by block 局部座標 Y
     * @param bz block 局部座標 Z
     * @return block-averaged 壓力 (Pa)
     */
    public float blockPressure(int bx, int by, int bz) {
        float sum = 0f;
        for (int sz = 0; sz < SUB; sz++) {
            for (int sy = 0; sy < SUB; sy++) {
                for (int sx = 0; sx < SUB; sx++) {
                    sum += subPressure[subFlat(bx, by, bz, sx, sy, sz)];
                }
            }
        }
        return sum / (SUB * SUB * SUB);
    }

    /**
     * 檢查此區域是否有任何 SOLID_WALL 邊界（用於 shouldUseMLFor 路由）。
     *
     * @return true 表示存在固體牆面，有流體-結構耦合需求
     */
    public boolean hasBoundaryWall() {
        for (int i = 0; i < totalVoxels; i++) {
            if ((type[i] & 0xFF) == FluidType.SOLID_WALL.getId()) {
                return true;
            }
        }
        return false;
    }

    // ─── 資料存取 ───

    /**
     * 僅更新體素類型和密度，保留 phi/pressure/volume（動態拓撲更新用）。
     *
     * @param index   平坦索引
     * @param newType 新的流體類型
     */
    public void setVoxelType(int index, FluidType newType) {
        if (index < 0 || index >= totalVoxels) return;
        type[index] = (byte) newType.getId();
        density[index] = (float) newType.getDensity();
        if (newType == FluidType.AIR) {
            volume[index] = 0f;
            phi[index] = 0f;
            pressure[index] = 0f;
        }
        dirty = true;
    }

    public void setFluidState(int index, FluidType fluidType, float vol, float phi, float press) {
        this.type[index] = (byte) fluidType.getId();
        this.volume[index] = vol;
        this.phi[index] = phi;
        this.pressure[index] = press;
        this.density[index] = (float) fluidType.getDensity();
        this.dirty = true;
    }

    public FluidState getFluidState(int index) {
        if (index < 0 || index >= totalVoxels) return FluidState.EMPTY;
        return new FluidState(
            FluidType.fromId(type[index] & 0xFF),
            volume[index],
            pressure[index],
            phi[index],
            0f, 0f, 0f  // block-level velocity not tracked; use sub-cell arrays
        );
    }

    public FluidState getFluidStateAt(@Nonnull BlockPos pos) {
        int idx = flatIndex(pos);
        if (idx < 0) return FluidState.EMPTY;
        return getFluidState(idx);
    }

    // ─── SoA 陣列直接存取（block-level，供既有 CPU/GPU Jacobi 路徑使用） ───

    public float[] getPhi() { return phi; }
    public float[] getPhiPrev() { return phiPrev; }
    public byte[] getType() { return type; }
    public float[] getDensity() { return density; }
    public float[] getPressure() { return pressure; }
    /** @deprecated 使用 {@link #getVof()} 取得 sub-cell VOF */
    @Deprecated
    public float[] getVolume() { return volume; }

    // ─── SoA 陣列直接存取（sub-cell level，供 NS / ML 路徑使用） ───

    public float[] getVx() { return vx; }
    public float[] getVy() { return vy; }
    public float[] getVz() { return vz; }
    public float[] getVof() { return vof; }
    public float[] getSubPressure() { return subPressure; }

    // ─── 區域屬性 ───

    public int getRegionId() { return regionId; }
    public int getOriginX() { return originX; }
    public int getOriginY() { return originY; }
    public int getOriginZ() { return originZ; }
    public int getSizeX() { return sizeX; }
    public int getSizeY() { return sizeY; }
    public int getSizeZ() { return sizeZ; }
    public int getTotalVoxels() { return totalVoxels; }
    public int getSubSX() { return subSX; }
    public int getSubSY() { return subSY; }
    public int getSubSZ() { return subSZ; }
    public int getSubTotalVoxels() { return subTotalVoxels; }

    public boolean isDirty() { return dirty; }
    public void clearDirty() { dirty = false; }
    public void markDirty() { dirty = true; }
    public void markDirty(boolean d) { dirty = d; }

    public long getLastModifiedTick() { return lastModifiedTick; }
    public void setLastModifiedTick(long tick) { lastModifiedTick = tick; }

    /** 計算此區域中非空氣體素數量 */
    public int getFluidVoxelCount() {
        int count = 0;
        for (int i = 0; i < totalVoxels; i++) {
            if (volume[i] > FluidConstants.MIN_VOLUME_FRACTION && type[i] != FluidType.AIR.getId()) {
                count++;
            }
        }
        return count;
    }
}
