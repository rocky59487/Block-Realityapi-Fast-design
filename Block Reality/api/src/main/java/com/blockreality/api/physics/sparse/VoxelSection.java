package com.blockreality.api.physics.sparse;

import com.blockreality.api.physics.RBlockState;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * 16³ 體素區段 — SparseVoxelOctree 的基本儲存單元。
 *
 * 三態設計（參考 Embeddium/Sodium 的 PalettedContainer）：
 *   EMPTY         — 全空氣，不分配陣列（零記憶體）
 *   HOMOGENEOUS   — 整段同材質，只儲存一個 RBlockState（16 bytes）
 *   HETEROGENEOUS — 混合材質，分配完整 4096 元素陣列（64 KB）
 *
 * 索引公式（Y-連續，與 RWorldSnapshot 一致）：
 *   lx + 16 * (ly + 16 * lz)
 *
 * 記憶體估算（建築場景 10% 非空氣）：
 *   107K total sections × 10% heterogeneous = 10.7K × 64KB = 686 MB
 *   對比全量 1D 陣列的 6.9 GB → 節省 90%
 *
 * @since v3.0 Phase 1
 */
@NotThreadSafe
public class VoxelSection {

    /** Section 邊長（固定 16，與 Minecraft chunk section 一致） */
    public static final int SIZE = 16;

    /** Section 總體素數 */
    public static final int VOLUME = SIZE * SIZE * SIZE; // 4096

    /** Section 狀態 */
    public enum Type {
        /** 全空氣 — blocks == null, homogeneousState == null */
        EMPTY,
        /** 整段同材質 — blocks == null, homogeneousState != null */
        HOMOGENEOUS,
        /** 混合材質 — blocks != null (4096 elements) */
        HETEROGENEOUS
    }

    // ═══ 狀態欄位 ═══

    private Type type;

    /** HOMOGENEOUS 模式下的統一方塊狀態 */
    @Nullable
    private RBlockState homogeneousState;

    /** HETEROGENEOUS 模式下的方塊陣列（延遲分配） */
    @Nullable
    private RBlockState[] blocks;

    /** 非空氣方塊計數（用於快速跳過空區段 + 壓縮判定） */
    private short nonAirCount;

    /**
     * HOMOGENEOUS 模式下，第一個被明確設定的方塊索引。
     * 用於在 upgradeToHeterogeneous() 時精確還原實際佔用的格子，
     * 而不是錯誤地填滿全部 4096 個位置。
     * -1 表示「未知」（由舊的序列化路徑建立）。
     */
    private int homogeneousIdx = -1;

    /** 髒標記 — Section 內容變更後設為 true，物理引擎消費後重置 */
    private boolean dirty;

    /** Section 在世界空間的基準座標（Section 左下角的世界座標） */
    private final int worldX, worldY, worldZ;

    // ═══ 建構 ═══

    /**
     * 建立一個空的 VoxelSection。
     *
     * @param worldX Section 左下角世界 X（必須是 16 的倍數）
     * @param worldY Section 左下角世界 Y
     * @param worldZ Section 左下角世界 Z（必須是 16 的倍數）
     */
    public VoxelSection(int worldX, int worldY, int worldZ) {
        this.worldX = worldX;
        this.worldY = worldY;
        this.worldZ = worldZ;
        this.type = Type.EMPTY;
        this.nonAirCount = 0;
        this.dirty = false;
    }

    // ═══ 讀取 ═══

    /**
     * 取得指定局部座標的方塊狀態。
     *
     * @param lx 局部 X [0, 15]
     * @param ly 局部 Y [0, 15]
     * @param lz 局部 Z [0, 15]
     * @return RBlockState（空氣則回傳 RBlockState.AIR）
     */
    public RBlockState getBlock(int lx, int ly, int lz) {
        return switch (type) {
            case EMPTY -> RBlockState.AIR;
            case HOMOGENEOUS -> homogeneousState != null ? homogeneousState : RBlockState.AIR;
            case HETEROGENEOUS -> {
                RBlockState state = blocks[index(lx, ly, lz)];
                yield state != null ? state : RBlockState.AIR;
            }
        };
    }

    /**
     * 以 1D 索引直接存取（零物件配置路徑）。
     */
    public RBlockState getBlockByIndex(int idx) {
        return switch (type) {
            case EMPTY -> RBlockState.AIR;
            case HOMOGENEOUS -> homogeneousState != null ? homogeneousState : RBlockState.AIR;
            case HETEROGENEOUS -> {
                RBlockState state = blocks[idx];
                yield state != null ? state : RBlockState.AIR;
            }
        };
    }

    // ═══ 寫入 ═══

    /**
     * 設定指定局部座標的方塊狀態。
     * 自動處理狀態轉換（EMPTY → HOMOGENEOUS → HETEROGENEOUS）。
     *
     * @param lx    局部 X [0, 15]
     * @param ly    局部 Y [0, 15]
     * @param lz    局部 Z [0, 15]
     * @param state 方塊狀態（null 或 AIR 視為空氣）
     */
    public void setBlock(int lx, int ly, int lz, @Nullable RBlockState state) {
        boolean isAir = (state == null || state == RBlockState.AIR);
        int idx = index(lx, ly, lz);

        switch (type) {
            case EMPTY -> {
                if (isAir) return; // 空→空，無動作
                // 第一個非空方塊 → 升級為 HOMOGENEOUS
                type = Type.HOMOGENEOUS;
                homogeneousState = state;
                homogeneousIdx = idx;   // 記錄確切位置，upgrade 時使用
                nonAirCount = 1;
                dirty = true;
            }
            case HOMOGENEOUS -> {
                if (isAir) {
                    // 移除方塊 → 需要知道原先是否所有位置都是這個材質
                    // HOMOGENEOUS 下所有位置都是同一狀態，移除一個要升級為 HETEROGENEOUS
                    upgradeToHeterogeneous();
                    blocks[idx] = null;
                    nonAirCount--;
                    dirty = true;
                    tryCompact();
                } else if (state.equals(homogeneousState)) {
                    // 即使材質相同，第二個方塊也必須升級為 HETEROGENEOUS 以精確追蹤各個位置。
                    // 若繼續停在 HOMOGENEOUS，getBlock() 會對全部 4096 位置回傳同一狀態，
                    // 導致 forEachNonAir 遍歷 4096 個虛假方塊，也讓上層計數器失準。
                    upgradeToHeterogeneous();
                    boolean targetWasAir = (blocks[idx] == null || blocks[idx] == RBlockState.AIR);
                    blocks[idx] = state;
                    if (targetWasAir) nonAirCount++;
                    dirty = true;
                } else {
                    // 設定不同材質 → 升級為 HETEROGENEOUS
                    // upgradeToHeterogeneous() 只在 homogeneousIdx 填入舊方塊；
                    // 若 idx != homogeneousIdx，blocks[idx] 升級後仍為 null（空氣），
                    // 所以這是一次「空→非空」操作，必須增加 nonAirCount。
                    upgradeToHeterogeneous();
                    boolean targetWasAir = (blocks[idx] == null || blocks[idx] == RBlockState.AIR);
                    blocks[idx] = state;
                    if (targetWasAir) nonAirCount++;
                    dirty = true;
                }
            }
            case HETEROGENEOUS -> {
                RBlockState old = blocks[idx];
                boolean oldIsAir = (old == null || old == RBlockState.AIR);

                if (isAir && !oldIsAir) {
                    blocks[idx] = null;
                    nonAirCount--;
                    dirty = true;
                    tryCompact();
                } else if (!isAir && oldIsAir) {
                    blocks[idx] = state;
                    nonAirCount++;
                    dirty = true;
                } else if (!isAir) {
                    blocks[idx] = state;
                    dirty = true;
                }
                // air→air: 無動作
            }
        }
    }

    // ═══ 狀態轉換 ═══

    /**
     * HOMOGENEOUS → HETEROGENEOUS：分配空陣列，只在已記錄的位置還原舊方塊。
     *
     * <p>舊做法（Arrays.fill + nonAirCount=VOLUME）假設 HOMOGENEOUS 代表「全段同材質」，
     * 但實際使用中 HOMOGENEOUS 僅追蹤「有哪些格子曾被明確設定」；
     * 因此升級時只應還原已知的 homogeneousIdx 位置，而非填滿全部 4096 格。
     *
     * <p>若 homogeneousIdx == -1（舊序列化路徑），則回退至原本的全填充行為。
     */
    private void upgradeToHeterogeneous() {
        blocks = new RBlockState[VOLUME];
        if (homogeneousState != null) {
            if (homogeneousIdx >= 0 && homogeneousIdx < VOLUME) {
                // 只在確切的記錄位置放回舊方塊，nonAirCount 維持不變
                blocks[homogeneousIdx] = homogeneousState;
            } else {
                // 未知位置（舊格式）：保守地填充全部，維持原有行為
                java.util.Arrays.fill(blocks, homogeneousState);
                nonAirCount = VOLUME;
            }
        }
        homogeneousState = null;
        homogeneousIdx = -1;
        type = Type.HETEROGENEOUS;
    }

    /**
     * 嘗試壓縮：如果 nonAirCount 為 0 → 降級為 EMPTY。
     * 如果所有非空方塊都是同一材質 → 降級為 HOMOGENEOUS。
     */
    private void tryCompact() {
        if (nonAirCount <= 0) {
            blocks = null;
            homogeneousState = null;
            type = Type.EMPTY;
            nonAirCount = 0;
            return;
        }

        // 檢查是否可壓縮為 HOMOGENEOUS（所有非空方塊相同）
        // 只在 nonAirCount <= VOLUME/2 時嘗試（避免頻繁掃描大陣列）
        if (type == Type.HETEROGENEOUS && blocks != null && nonAirCount <= VOLUME / 2) {
            RBlockState first = null;
            boolean allSame = true;
            int firstIdx = -1;
            for (int i = 0; i < VOLUME; i++) {
                RBlockState s = blocks[i];
                if (s != null && s != RBlockState.AIR) {
                    if (first == null) {
                        first = s;
                        firstIdx = i;
                    } else if (!s.equals(first)) {
                        allSame = false;
                        break;
                    }
                }
            }
            // Issue#svo-compact-fix: 只有在 nonAirCount == 1 時才允許降級為 HOMOGENEOUS。
            // 若 nonAirCount > 1，homogeneousIdx 無法唯一表示所有方塊的位置；
            // 後續 upgradeToHeterogeneous() 在 idx==-1 路徑會執行 Arrays.fill(VOLUME)
            // 並強制 nonAirCount=VOLUME，導致 totalNonAirBlocks 嚴重失準。
            // 對多塊同材質 Section 保留 HETEROGENEOUS（額外記憶體，但計數正確）。
            if (allSame && first != null && nonAirCount == 1) {
                blocks = null;
                homogeneousState = first;
                homogeneousIdx = firstIdx;  // 精確記錄唯一方塊位置，避免 upgradeToHeterogeneous fallback
                type = Type.HOMOGENEOUS;
            }
        }
    }

    /**
     * 強制壓縮 — 外部呼叫，用於定期記憶體回收。
     */
    public void compact() {
        if (type == Type.HETEROGENEOUS) {
            tryCompact();
        }
    }

    // ═══ 批次填充（SnapshotBuilder 使用） ═══

    /**
     * 直接設定完整的方塊陣列（跳過逐一 setBlock 的開銷）。
     * 呼叫者負責提供正確大小的陣列。
     *
     * @param blockArray 4096 元素的 RBlockState 陣列（null 元素 = 空氣）
     * @param count      非空氣方塊數量
     */
    public void populate(RBlockState[] blockArray, short count) {
        if (count <= 0) {
            this.type = Type.EMPTY;
            this.blocks = null;
            this.homogeneousState = null;
            this.nonAirCount = 0;
        } else {
            this.type = Type.HETEROGENEOUS;
            this.blocks = blockArray;
            this.homogeneousState = null;
            this.nonAirCount = count;
        }
        this.dirty = true;
    }

    // ═══ 索引 ═══

    /**
     * 局部座標 → 1D 索引（Y-連續排列）。
     * lx + 16 * (ly + 16 * lz)
     */
    private static int index(int lx, int ly, int lz) {
        return lx + SIZE * (ly + SIZE * lz);
    }

    /**
     * 1D 索引 → 局部座標。
     */
    public static int[] indexToLocal(int idx) {
        int lx = idx & 0xF;           // idx % 16
        int ly = (idx >> 4) & 0xF;    // (idx / 16) % 16
        int lz = (idx >> 8) & 0xF;    // idx / 256
        return new int[]{lx, ly, lz};
    }

    // ═══ Getters ═══

    public Type getType() { return type; }
    public short getNonAirCount() { return nonAirCount; }
    public boolean isEmpty() { return type == Type.EMPTY; }
    public boolean isHomogeneous() { return type == Type.HOMOGENEOUS; }
    public boolean isDirty() { return dirty; }
    public void clearDirty() { this.dirty = false; }
    public void markDirty() { this.dirty = true; }

    public int getWorldX() { return worldX; }
    public int getWorldY() { return worldY; }
    public int getWorldZ() { return worldZ; }

    /**
     * 遍歷此 Section 中所有非空氣方塊。
     * callback 接收 (localX, localY, localZ, state)。
     */
    public void forEachNonAir(VoxelVisitor visitor) {
        if (type == Type.EMPTY) return;
        if (type == Type.HOMOGENEOUS && homogeneousState != null) {
            for (int y = 0; y < SIZE; y++)
                for (int z = 0; z < SIZE; z++)
                    for (int x = 0; x < SIZE; x++)
                        visitor.visit(x, y, z, homogeneousState);
            return;
        }
        if (blocks == null) return;
        for (int i = 0; i < VOLUME; i++) {
            RBlockState s = blocks[i];
            if (s != null && s != RBlockState.AIR) {
                int x = i & 0xF;
                int y = (i >> 4) & 0xF;
                int z = (i >> 8) & 0xF;
                visitor.visit(x, y, z, s);
            }
        }
    }

    @FunctionalInterface
    public interface VoxelVisitor {
        void visit(int localX, int localY, int localZ, RBlockState state);
    }

    /**
     * 估算記憶體用量（bytes）。
     */
    public long estimateMemoryBytes() {
        long base = 48; // object header + fields
        return switch (type) {
            case EMPTY -> base;
            case HOMOGENEOUS -> base + 16; // one RBlockState reference
            case HETEROGENEOUS -> base + (long) VOLUME * 8; // 4096 references (64-bit JVM)
        };
    }

    @Override
    public String toString() {
        return String.format("VoxelSection[%d,%d,%d type=%s nonAir=%d dirty=%b]",
            worldX, worldY, worldZ, type, nonAirCount, dirty);
    }
}
