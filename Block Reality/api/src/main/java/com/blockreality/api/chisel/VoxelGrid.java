package com.blockreality.api.chisel;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

import java.util.Arrays;

/**
 * 10×10×10 子體素網格 — 每格 0.1m，儲存為 long[16]（1000 bits / 64 = 16 longs）。
 *
 * 索引方式：index = x + 10 * (y + 10 * z)
 * 與 RWorldSnapshot 的 Y 連續慣例一致。
 *
 * 設計考量：
 *   - 不可變物件（建構後不可修改），使用 Builder 進行編輯
 *   - 128 bytes 適合兩條 CPU cache line
 *   - NBT 序列化使用 putLongArray，極為緊湊
 *   - 選用 long[] 而非 BitSet 以避免動態大小開銷
 */
public final class VoxelGrid {

    public static final int SIZE = 10;
    public static final int TOTAL_VOXELS = SIZE * SIZE * SIZE; // 1000
    private static final int LONGS_NEEDED = (TOTAL_VOXELS + 63) / 64; // 16

    public static final String NBT_KEY = "br_voxels";

    private final long[] bits;

    // ─── 快取欄位（lazy） ───
    private transient int cachedCount = -1;

    private VoxelGrid(long[] bits) {
        if (bits.length != LONGS_NEEDED) {
            throw new IllegalArgumentException("VoxelGrid requires " + LONGS_NEEDED + " longs, got " + bits.length);
        }
        this.bits = bits;
    }

    // ─── 索引 ───

    private static int index(int x, int y, int z) {
        return x + SIZE * (y + SIZE * z);
    }

    private static void checkBounds(int x, int y, int z) {
        if (x < 0 || x >= SIZE || y < 0 || y >= SIZE || z < 0 || z >= SIZE) {
            throw new IndexOutOfBoundsException(
                "Voxel coordinates (" + x + "," + y + "," + z + ") out of bounds [0," + SIZE + ")");
        }
    }

    // ─── 查詢 ───

    /**
     * 查詢指定位置是否填充。
     */
    public boolean get(int x, int y, int z) {
        checkBounds(x, y, z);
        int idx = index(x, y, z);
        return (bits[idx >> 6] & (1L << (idx & 63))) != 0;
    }

    /**
     * 已填充的體素數量。
     */
    public int filledCount() {
        if (cachedCount < 0) {
            int count = 0;
            for (long word : bits) {
                count += Long.bitCount(word);
            }
            cachedCount = count;
        }
        return cachedCount;
    }

    /**
     * 填充率：filledCount / 1000.0
     */
    public double fillRatio() {
        return filledCount() / (double) TOTAL_VOXELS;
    }

    /**
     * 是否全部填滿（即完整方塊）。
     */
    public boolean isFull() {
        return filledCount() == TOTAL_VOXELS;
    }

    /**
     * 是否完全空白。
     */
    public boolean isEmpty() {
        return filledCount() == 0;
    }

    // ─── 序列化 ───

    /**
     * 取得 raw bit 陣列（複本）。
     */
    public long[] toLongArray() {
        return Arrays.copyOf(bits, bits.length);
    }

    /**
     * 從 long 陣列重建。
     */
    public static VoxelGrid fromLongArray(long[] data) {
        if (data.length != LONGS_NEEDED) {
            // 容錯：填入預設值
            long[] fixed = new long[LONGS_NEEDED];
            System.arraycopy(data, 0, fixed, 0, Math.min(data.length, LONGS_NEEDED));
            return new VoxelGrid(fixed);
        }
        return new VoxelGrid(Arrays.copyOf(data, data.length));
    }

    /**
     * 寫入 NBT。
     */
    public void saveToTag(CompoundTag tag) {
        tag.putLongArray(NBT_KEY, toLongArray());
    }

    /**
     * 從 NBT 讀取。
     */
    public static VoxelGrid loadFromTag(CompoundTag tag) {
        if (tag.contains(NBT_KEY)) {
            return fromLongArray(tag.getLongArray(NBT_KEY));
        }
        return full();
    }

    /**
     * 寫入網路封包。
     */
    public void writeToBuf(FriendlyByteBuf buf) {
        for (long word : bits) {
            buf.writeLong(word);
        }
    }

    /**
     * 從網路封包讀取。
     */
    public static VoxelGrid readFromBuf(FriendlyByteBuf buf) {
        long[] data = new long[LONGS_NEEDED];
        for (int i = 0; i < LONGS_NEEDED; i++) {
            data[i] = buf.readLong();
        }
        return new VoxelGrid(data);
    }

    // ─── 工廠方法 ───

    private static volatile VoxelGrid CACHED_FULL;
    private static volatile VoxelGrid CACHED_EMPTY;

    /**
     * 全部填滿的網格（完整 1m³ 方塊）。
     */
    public static VoxelGrid full() {
        if (CACHED_FULL == null) {
            long[] data = new long[LONGS_NEEDED];
            // 前 15 個 long 全填滿
            for (int i = 0; i < LONGS_NEEDED - 1; i++) {
                data[i] = -1L; // all bits set
            }
            // 最後一個 long 只需 1000 - 15*64 = 1000 - 960 = 40 bits
            data[LONGS_NEEDED - 1] = (1L << (TOTAL_VOXELS - 64 * (LONGS_NEEDED - 1))) - 1;
            CACHED_FULL = new VoxelGrid(data);
        }
        return CACHED_FULL;
    }

    /**
     * 全部空白的網格。
     */
    public static VoxelGrid empty() {
        if (CACHED_EMPTY == null) {
            CACHED_EMPTY = new VoxelGrid(new long[LONGS_NEEDED]);
        }
        return CACHED_EMPTY;
    }

    /**
     * 從模板形狀生成體素網格。
     */
    public static VoxelGrid fromShape(SubBlockShape shape) {
        return shape.generateVoxelGrid();
    }

    // ─── 可變建構器 ───

    /**
     * 建構器 — 用於自訂雕刻。
     */
    public static class Builder {
        private final long[] data;

        public Builder() {
            this.data = new long[LONGS_NEEDED];
        }

        /**
         * 從現有網格開始編輯。
         */
        public Builder(VoxelGrid existing) {
            this.data = existing.toLongArray();
        }

        /**
         * 設定或清除單個體素。
         */
        public Builder set(int x, int y, int z, boolean filled) {
            checkBounds(x, y, z);
            int idx = index(x, y, z);
            if (filled) {
                data[idx >> 6] |= (1L << (idx & 63));
            } else {
                data[idx >> 6] &= ~(1L << (idx & 63));
            }
            return this;
        }

        /**
         * 填充整個 Y 層（y=0~9）。
         */
        public Builder fillLayer(int y, boolean filled) {
            for (int z = 0; z < SIZE; z++) {
                for (int x = 0; x < SIZE; x++) {
                    set(x, y, z, filled);
                }
            }
            return this;
        }

        /**
         * 填充所有體素。
         */
        public Builder fillAll() {
            for (int i = 0; i < LONGS_NEEDED - 1; i++) {
                data[i] = -1L;
            }
            data[LONGS_NEEDED - 1] = (1L << (TOTAL_VOXELS - 64 * (LONGS_NEEDED - 1))) - 1;
            return this;
        }

        public VoxelGrid build() {
            return new VoxelGrid(Arrays.copyOf(data, data.length));
        }
    }

    // ─── Object ───

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VoxelGrid other)) return false;
        return Arrays.equals(bits, other.bits);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bits);
    }
}
