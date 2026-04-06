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
 * <p>陣列索引使用 row-major: index = x + y * sizeX + z * sizeX * sizeY
 */
@ThreadSafe
public class FluidRegion {

    private final int regionId;
    private final int originX, originY, originZ;
    private final int sizeX, sizeY, sizeZ;
    private final int totalVoxels;

    // ─── SoA 流體資料（CPU 端） ───
    private final float[] phi;          // 流體勢能
    private final float[] phiPrev;      // 前一步勢能（雙緩衝）
    private final byte[] type;          // FluidType.getId()
    private final float[] density;      // 密度 (kg/m³)
    private final float[] pressure;     // 靜水壓 (Pa)
    private final float[] volume;       // 體積分率 [0,1]

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

        this.phi = new float[totalVoxels];
        this.phiPrev = new float[totalVoxels];
        this.type = new byte[totalVoxels];
        this.density = new float[totalVoxels];
        this.pressure = new float[totalVoxels];
        this.volume = new float[totalVoxels];
    }

    // ─── 座標轉換 ───

    /** 方塊世界座標 → 區域內平坦索引，超出範圍返回 -1 */
    public int flatIndex(@Nonnull BlockPos pos) {
        int lx = pos.getX() - originX;
        int ly = pos.getY() - originY;
        int lz = pos.getZ() - originZ;
        if (lx < 0 || lx >= sizeX || ly < 0 || ly >= sizeY || lz < 0 || lz >= sizeZ) {
            return -1;
        }
        return lx + ly * sizeX + lz * sizeX * sizeY;
    }

    /** 區域內局部座標 → 平坦索引 */
    public int flatIndex(int lx, int ly, int lz) {
        return lx + ly * sizeX + lz * sizeX * sizeY;
    }

    /** 檢查世界座標是否在此區域內 */
    public boolean contains(@Nonnull BlockPos pos) {
        int lx = pos.getX() - originX;
        int ly = pos.getY() - originY;
        int lz = pos.getZ() - originZ;
        return lx >= 0 && lx < sizeX && ly >= 0 && ly < sizeY && lz >= 0 && lz < sizeZ;
    }

    // ─── 資料存取 ───

    /**
     * 僅更新體素類型和密度，保留 phi/pressure/volume（動態拓撲更新用）。
     *
     * <p>用於固體牆崩塌時（SOLID_WALL → AIR），避免清除鄰居已擴散進來的勢場。
     *
     * @param index   平坦索引
     * @param newType 新的流體類型
     */
    public void setVoxelType(int index, FluidType newType) {
        if (index < 0 || index >= totalVoxels) return;
        type[index] = (byte) newType.getId();
        density[index] = (float) newType.getDensity();
        // 如果轉為 AIR，清除殘留體積（固體不應有流體體積）
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
            phi[index]
        );
    }

    public FluidState getFluidStateAt(@Nonnull BlockPos pos) {
        int idx = flatIndex(pos);
        if (idx < 0) return FluidState.EMPTY;
        return getFluidState(idx);
    }

    // ─── SoA 陣列直接存取（供 CPU solver 使用） ───

    public float[] getPhi() { return phi; }
    public float[] getPhiPrev() { return phiPrev; }
    public byte[] getType() { return type; }
    public float[] getDensity() { return density; }
    public float[] getPressure() { return pressure; }
    public float[] getVolume() { return volume; }

    // ─── 區域屬性 ───

    public int getRegionId() { return regionId; }
    public int getOriginX() { return originX; }
    public int getOriginY() { return originY; }
    public int getOriginZ() { return originZ; }
    public int getSizeX() { return sizeX; }
    public int getSizeY() { return sizeY; }
    public int getSizeZ() { return sizeZ; }
    public int getTotalVoxels() { return totalVoxels; }

    public boolean isDirty() { return dirty; }
    public void clearDirty() { dirty = false; }
    public void markDirty() { dirty = true; }

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
