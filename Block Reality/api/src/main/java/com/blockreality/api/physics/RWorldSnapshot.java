package com.blockreality.api.physics;

import net.minecraft.core.BlockPos;
import java.util.Collections;
import java.util.Set;

/**
 * 唯讀的世界快照容器。
 * 使用 1D 陣列扁平化 3D 空間，保證 cache-friendly 存取。
 *
 * 索引公式: lx + sizeX * (ly + sizeY * lz)
 * → Y 軸連續排列，物理引擎沿柱狀 (column) 掃描時 cache 命中率最高。
 *
 * 不持有任何 net.minecraft.world.level.Level 參照。
 *
 * v3fix §4.2: 支援 changedPositions 以追蹤快照內已修改的位置。
 */
public class RWorldSnapshot {

    /** 安全煞車：預設最大快照方塊數 (≈ 40×40×40)，超過直接拒絕，防範 OOM */
    public static final int DEFAULT_MAX_SNAPSHOT_BLOCKS = 65536;

    /**
     * ★ Phase 1: 可配置的最大快照方塊數。
     * 預設值向後相容（65536），可透過 BRConfig.maxSnapshotBlocks 提高到 262144+。
     * 使用 volatile 確保配置更新後立即對所有執行緒可見。
     */
    private static volatile int maxSnapshotBlocks = DEFAULT_MAX_SNAPSHOT_BLOCKS;

    /** 向後相容：保留靜態欄位名 MAX_SNAPSHOT_BLOCKS 供現有程式碼使用 */
    public static final int MAX_SNAPSHOT_BLOCKS = DEFAULT_MAX_SNAPSHOT_BLOCKS;

    /**
     * ★ Phase 1: 設定運行時最大快照方塊數。
     * ★ audit-fix M-3: 移除 Math.max 限制 — 允許設定低於 DEFAULT 的值。
     * ForgeConfigSpec 的 defineInRange 已保證值在有效範圍內（65536~1048576），
     * 此處不需額外 clamp。
     */
    public static void setMaxSnapshotBlocks(int max) {
        maxSnapshotBlocks = max;
    }

    /** ★ Phase 1: 取得目前最大快照方塊數 */
    public static int getMaxSnapshotBlocks() {
        return maxSnapshotBlocks;
    }

    private final int startX, startY, startZ;
    private final int sizeX, sizeY, sizeZ;
    private final RBlockState[] blocks;
    private final long captureTimeNs;
    private final Set<BlockPos> changedPositions;

    public RWorldSnapshot(BlockPos start, BlockPos end) {
        this(start, end, Collections.emptySet());
    }

    /**
     * Public constructor with changed positions tracking.
     * Useful for incremental updates and physics analysis.
     *
     * @param start             Start corner (inclusive)
     * @param end               End corner (inclusive)
     * @param changedPositions  Set of positions that were modified (can be empty)
     */
    public RWorldSnapshot(BlockPos start, BlockPos end, Set<BlockPos> changedPositions) {
        this.startX = Math.min(start.getX(), end.getX());
        this.startY = Math.min(start.getY(), end.getY());
        this.startZ = Math.min(start.getZ(), end.getZ());

        this.sizeX = Math.abs(end.getX() - start.getX()) + 1;
        this.sizeY = Math.abs(end.getY() - start.getY()) + 1;
        this.sizeZ = Math.abs(end.getZ() - start.getZ()) + 1;

        int totalBlocks = sizeX * sizeY * sizeZ;
        int effectiveMax = getMaxSnapshotBlocks();
        if (totalBlocks > effectiveMax) {
            throw new IllegalArgumentException(
                String.format("Snapshot exceeds max_snapshot_blocks (%d). Attempted: %dx%dx%d = %d",
                    effectiveMax, sizeX, sizeY, sizeZ, totalBlocks)
            );
        }

        this.blocks = new RBlockState[totalBlocks];
        this.captureTimeNs = 0;
        this.changedPositions = changedPositions != null ? changedPositions : Collections.emptySet();
    }

    /** 內部建構子，由 SnapshotBuilder 使用（帶計時） */
    RWorldSnapshot(int startX, int startY, int startZ,
                   int sizeX, int sizeY, int sizeZ,
                   RBlockState[] blocks, long captureTimeNs) {
        this(startX, startY, startZ, sizeX, sizeY, sizeZ, blocks, captureTimeNs, Collections.emptySet());
    }

    /** 內部建構子，由 SnapshotBuilder / SparsePhysicsSnapshot 使用（帶計時及變更位置） */
    public RWorldSnapshot(int startX, int startY, int startZ,
                   int sizeX, int sizeY, int sizeZ,
                   RBlockState[] blocks, long captureTimeNs,
                   Set<BlockPos> changedPositions) {
        this.startX = startX;
        this.startY = startY;
        this.startZ = startZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.blocks = blocks;
        this.captureTimeNs = captureTimeNs;
        this.changedPositions = changedPositions != null ? changedPositions : Collections.emptySet();
    }

    /**
     * 1D 索引：lx + sizeX * (ly + sizeY * lz)
     * Y 連續 → 柱狀掃描 cache 友好
     */
    private int getIndex(int x, int y, int z) {
        return (x - startX) + sizeX * ((y - startY) + sizeY * (z - startZ));
    }

    public void setBlock(int x, int y, int z, RBlockState state) {
        if (isWithinBounds(x, y, z)) {
            blocks[getIndex(x, y, z)] = state;
        }
    }

    public RBlockState getBlock(int x, int y, int z) {
        if (!isWithinBounds(x, y, z)) return RBlockState.AIR;
        RBlockState state = blocks[getIndex(x, y, z)];
        return state != null ? state : RBlockState.AIR;
    }

    public boolean isWithinBounds(int x, int y, int z) {
        return x >= startX && x < startX + sizeX &&
               y >= startY && y < startY + sizeY &&
               z >= startZ && z < startZ + sizeZ;
    }

    // ─── Getters ───

    public int getStartX() { return startX; }
    public int getStartY() { return startY; }
    public int getStartZ() { return startZ; }
    public int getSizeX()  { return sizeX; }
    public int getSizeY()  { return sizeY; }
    public int getSizeZ()  { return sizeZ; }
    public int getTotalBlocks() { return sizeX * sizeY * sizeZ; }

    /** 快照擷取耗時 (奈秒) */
    public long getCaptureTimeNs() { return captureTimeNs; }

    /** 快照擷取耗時 (毫秒) */
    public double getCaptureTimeMs() { return captureTimeNs / 1_000_000.0; }

    /**
     * 以 1D 索引直接存取方塊（零物件配置路徑）。
     * 索引公式: lx + sizeX * (ly + sizeY * lz)
     */
    public RBlockState getBlockByIndex(int index) {
        if (index < 0 || index >= blocks.length) return RBlockState.AIR;
        RBlockState state = blocks[index];
        return state != null ? state : RBlockState.AIR;
    }

    /**
     * 將 1D 索引解碼為世界絕對座標。
     */
    public BlockPos indexToWorldPos(int index) {
        int lx = index % sizeX;
        int ly = (index / sizeX) % sizeY;
        int lz = index / (sizeX * sizeY);
        return new BlockPos(startX + lx, startY + ly, startZ + lz);
    }

    /**
     * Get the set of block positions that were modified in this snapshot.
     * v3fix §4.2: Used for tracking incremental changes during physics analysis.
     *
     * @return Unmodifiable set of changed block positions
     */
    public Set<BlockPos> getChangedPositions() {
        return Collections.unmodifiableSet(changedPositions);
    }

    /**
     * Check if a specific position was marked as changed.
     *
     * @param pos The block position to check
     * @return true if the position is in the changed set
     */
    public boolean isPositionChanged(BlockPos pos) {
        return changedPositions.contains(pos);
    }
}
