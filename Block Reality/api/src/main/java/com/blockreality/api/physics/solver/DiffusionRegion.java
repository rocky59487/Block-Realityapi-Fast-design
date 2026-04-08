package com.blockreality.api.physics.solver;

import net.minecraft.core.BlockPos;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;

/**
 * 通用擴散區域 — 所有物理域（流體/風/熱/電磁）的共用 SoA 資料容器。
 *
 * <p>儲存 ∇·(σ∇φ) = f 所需的全部陣列：
 * <ul>
 *   <li>{@code phi[]} — 主求解變量（流體勢能/溫度/電位/壓力）</li>
 *   <li>{@code conductivity[]} — 擴散係數 σ（密度/熱擴散率/電導率）</li>
 *   <li>{@code source[]} — 源項 f（重力/熱源/電荷密度）</li>
 *   <li>{@code type[]} — 體素類型（0=空/1=活動/4=固體牆）</li>
 * </ul>
 *
 * <p>各物理域透過 {@link DomainTranslator} 將自己的物理量映射到這些陣列。
 */
@ThreadSafe
public class DiffusionRegion {

    /** 體素類型常數 */
    public static final byte TYPE_EMPTY = 0;
    public static final byte TYPE_ACTIVE = 1;
    public static final byte TYPE_SOLID_WALL = 4;

    private final int regionId;
    private final int originX, originY, originZ;
    private final int sizeX, sizeY, sizeZ;
    private final int totalVoxels;

    // ─── SoA 資料（通用） ───
    private final float[] phi;
    private final float[] phiPrev;
    private final float[] conductivity;  // σ（各域含義不同）
    private final float[] source;        // f（各域含義不同）
    private final byte[] type;

    private volatile boolean dirty = false;

    public DiffusionRegion(int regionId, int originX, int originY, int originZ,
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
        this.conductivity = new float[totalVoxels];
        this.source = new float[totalVoxels];
        this.type = new byte[totalVoxels];
    }

    // ─── 座標轉換 ───

    public int flatIndex(int lx, int ly, int lz) {
        return lx + ly * sizeX + lz * sizeX * sizeY;
    }

    public int flatIndex(@Nonnull BlockPos pos) {
        int lx = pos.getX() - originX;
        int ly = pos.getY() - originY;
        int lz = pos.getZ() - originZ;
        if (lx < 0 || lx >= sizeX || ly < 0 || ly >= sizeY || lz < 0 || lz >= sizeZ) return -1;
        return lx + ly * sizeX + lz * sizeX * sizeY;
    }

    public boolean contains(@Nonnull BlockPos pos) {
        int lx = pos.getX() - originX;
        int ly = pos.getY() - originY;
        int lz = pos.getZ() - originZ;
        return lx >= 0 && lx < sizeX && ly >= 0 && ly < sizeY && lz >= 0 && lz < sizeZ;
    }

    // ─── 體素操作 ───

    public void setVoxel(int index, byte voxelType, float sigma, float phi, float src) {
        this.type[index] = voxelType;
        this.conductivity[index] = sigma;
        this.phi[index] = phi;
        this.source[index] = src;
        this.dirty = true;
    }

    // ─── SoA 直接存取（供求解器使用） ───

    public float[] getPhi() { return phi; }
    public float[] getPhiPrev() { return phiPrev; }
    public float[] getConductivity() { return conductivity; }
    public float[] getSource() { return source; }
    public byte[] getType() { return type; }

    // ─── 屬性 ───

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

    /** 計算活動體素數量 */
    public int getActiveVoxelCount() {
        int count = 0;
        for (int i = 0; i < totalVoxels; i++) {
            if (type[i] == TYPE_ACTIVE) count++;
        }
        return count;
    }
}
