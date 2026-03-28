package com.blockreality.api.physics.sparse;

import com.blockreality.api.physics.RBlockState;
import net.minecraft.core.BlockPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 稀疏體素八叉樹 — Block Reality v3.0 的核心空間資料結構。
 *
 * 參考來源：
 *   - Embeddium/Sodium 的 Section-based Chunk 管理
 *   - 《Efficient Sparse Voxel Octrees》(Laine & Karras, 2010)
 *
 * 設計目標：
 *   承載 1200×1200×300 (432M blocks) 範圍的世界快照，
 *   記憶體用量 ∝ 非空體積（而非總體積）。
 *
 * 結構：
 *   - 以 16³ VoxelSection 為基本單元
 *   - Long2Object HashMap 索引（section position → VoxelSection）
 *   - 空氣區段不分配記憶體
 *   - 支援增量更新（dirty section tracking）
 *
 * Section Key 編碼：
 *   key = ((long)(sx & 0xFFFFF) << 40) | ((long)(sy & 0xFFF) << 28) | (sz & 0xFFFFFFF)
 *   支援範圍：X/Z ±524,287 sections (±8,388,608 blocks), Y ±2,047 sections (±32,768 blocks)
 *
 * 效能特性：
 *   getBlock(x,y,z)  — O(1): hash lookup + array index
 *   setBlock(x,y,z)  — O(1): hash lookup + array index + 可能的 section 升級
 *   iterate()        — O(non-air sections × 4096)
 *   memoryUsage      — O(非空 sections × 64KB)
 *
 * @since v3.0 Phase 1
 */
@NotThreadSafe
public class SparseVoxelOctree {

    private static final Logger LOGGER = LogManager.getLogger("BR/SVO");

    /** Section 邊長 */
    private static final int SECTION_SIZE = VoxelSection.SIZE; // 16

    /** Section 位移量 (log2(16) = 4) */
    private static final int SECTION_SHIFT = 4;

    /** Section 內遮罩 (16 - 1 = 0xF) */
    private static final int SECTION_MASK = SECTION_SIZE - 1;

    // ═══ 核心儲存 ═══

    /** Section 映射表：sectionKey → VoxelSection */
    private final HashMap<Long, VoxelSection> sections;

    /** 世界邊界（用於快速越界檢查） */
    private final int minX, minY, minZ;
    private final int maxX, maxY, maxZ;

    /** 統計 */
    private int totalNonAirBlocks;
    private long lastModifiedTick;

    /** 自動壓縮：累計移除方塊數 — 達到閾值時自動觸發 compact() */
    private int removalsSinceLastCompact;
    private static final int AUTO_COMPACT_THRESHOLD = 1024;

    // ═══ 建構 ═══

    /**
     * 建立指定範圍的稀疏體素八叉樹。
     *
     * @param minX 最小 X（含）
     * @param minY 最小 Y（含）
     * @param minZ 最小 Z（含）
     * @param maxX 最大 X（含）
     * @param maxY 最大 Y（含）
     * @param maxZ 最大 Z（含）
     */
    public SparseVoxelOctree(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;

        // 預估 Section 數量（假設 10% 非空）
        int sectionsX = ((maxX - minX) >> SECTION_SHIFT) + 1;
        int sectionsY = ((maxY - minY) >> SECTION_SHIFT) + 1;
        int sectionsZ = ((maxZ - minZ) >> SECTION_SHIFT) + 1;
        int estimatedSections = Math.max(16, (sectionsX * sectionsY * sectionsZ) / 10);

        this.sections = new HashMap<>(estimatedSections);
        this.totalNonAirBlocks = 0;
        this.lastModifiedTick = 0;
        this.removalsSinceLastCompact = 0;
    }

    /**
     * 便捷建構：從 BlockPos 範圍建立。
     */
    public SparseVoxelOctree(BlockPos min, BlockPos max) {
        this(
            Math.min(min.getX(), max.getX()),
            Math.min(min.getY(), max.getY()),
            Math.min(min.getZ(), max.getZ()),
            Math.max(min.getX(), max.getX()),
            Math.max(min.getY(), max.getY()),
            Math.max(min.getZ(), max.getZ())
        );
    }

    // ═══ 讀取 ═══

    /**
     * 取得指定世界座標的方塊狀態。O(1)。
     *
     * @return RBlockState（越界或空氣回傳 RBlockState.AIR）
     */
    public RBlockState getBlock(int x, int y, int z) {
        if (!isWithinBounds(x, y, z)) return RBlockState.AIR;

        long key = sectionKey(x >> SECTION_SHIFT, y >> SECTION_SHIFT, z >> SECTION_SHIFT);
        VoxelSection section = sections.get(key);
        if (section == null) return RBlockState.AIR;

        return section.getBlock(x & SECTION_MASK, y & SECTION_MASK, z & SECTION_MASK);
    }

    /**
     * 取得指定 BlockPos 的方塊狀態。
     */
    public RBlockState getBlock(BlockPos pos) {
        return getBlock(pos.getX(), pos.getY(), pos.getZ());
    }

    // ═══ 寫入 ═══

    /**
     * 設定指定世界座標的方塊狀態。O(1)。
     * 自動建立/移除 Section。
     */
    public void setBlock(int x, int y, int z, @Nullable RBlockState state) {
        if (!isWithinBounds(x, y, z)) return;

        int sx = x >> SECTION_SHIFT;
        int sy = y >> SECTION_SHIFT;
        int sz = z >> SECTION_SHIFT;
        long key = sectionKey(sx, sy, sz);

        boolean isAir = (state == null || state == RBlockState.AIR);

        VoxelSection section = sections.get(key);

        if (section == null) {
            if (isAir) return; // 不為空氣建立空 Section
            section = new VoxelSection(sx << SECTION_SHIFT, sy << SECTION_SHIFT, sz << SECTION_SHIFT);
            sections.put(key, section);
        }

        // 追蹤非空氣計數變化
        boolean wasSolid = section.getBlock(x & SECTION_MASK, y & SECTION_MASK, z & SECTION_MASK) != RBlockState.AIR;

        section.setBlock(x & SECTION_MASK, y & SECTION_MASK, z & SECTION_MASK, state);

        boolean isSolid = !isAir;
        if (!wasSolid && isSolid) totalNonAirBlocks++;
        else if (wasSolid && !isSolid) {
            totalNonAirBlocks--;
            removalsSinceLastCompact++;
        }

        // 移除變空的 Section
        if (section.isEmpty()) {
            sections.remove(key);
        }

        // 自動壓縮：累計移除超過閾值時觸發
        if (removalsSinceLastCompact >= AUTO_COMPACT_THRESHOLD) {
            compact();
            removalsSinceLastCompact = 0;
        }
    }

    /**
     * 設定指定 BlockPos 的方塊狀態。
     */
    public void setBlock(BlockPos pos, @Nullable RBlockState state) {
        setBlock(pos.getX(), pos.getY(), pos.getZ(), state);
    }

    // ═══ Section 直接操作（SnapshotBuilder 批次填充使用） ═══

    /**
     * 直接放入一個已填充的 Section。
     * 用於 IncrementalSnapshotBuilder 的批次建構。
     *
     * @param sx      Section X 座標
     * @param sy      Section Y 座標
     * @param sz      Section Z 座標
     * @param section 已填充的 VoxelSection
     */
    public void putSection(int sx, int sy, int sz, VoxelSection section) {
        long key = sectionKey(sx, sy, sz);
        VoxelSection old = sections.put(key, section);

        // 更新非空氣計數
        if (old != null) totalNonAirBlocks -= old.getNonAirCount();
        totalNonAirBlocks += section.getNonAirCount();
    }

    /**
     * 取得指定位置的 Section（可能為 null = 全空氣）。
     */
    @Nullable
    public VoxelSection getSection(int sx, int sy, int sz) {
        return sections.get(sectionKey(sx, sy, sz));
    }

    /**
     * 檢查 Section 是否存在（非空氣）。
     */
    public boolean hasSection(int sx, int sy, int sz) {
        return sections.containsKey(sectionKey(sx, sy, sz));
    }

    // ═══ 遍歷 ═══

    /**
     * 遍歷所有非空氣方塊。
     * callback 接收 (世界座標 BlockPos, RBlockState)。
     *
     * 效能：只遍歷非空 Section，跳過空氣。
     */
    public void forEachNonAir(BiConsumer<BlockPos, RBlockState> callback) {
        for (VoxelSection section : sections.values()) {
            if (section.isEmpty()) continue;

            int wx = section.getWorldX();
            int wy = section.getWorldY();
            int wz = section.getWorldZ();

            if (section.isHomogeneous()) {
                RBlockState state = section.getBlockByIndex(0);
                if (state != RBlockState.AIR) {
                    for (int lz = 0; lz < SECTION_SIZE; lz++) {
                        for (int ly = 0; ly < SECTION_SIZE; ly++) {
                            for (int lx = 0; lx < SECTION_SIZE; lx++) {
                                callback.accept(new BlockPos(wx + lx, wy + ly, wz + lz), state);
                            }
                        }
                    }
                }
            } else {
                for (int idx = 0; idx < VoxelSection.VOLUME; idx++) {
                    RBlockState state = section.getBlockByIndex(idx);
                    if (state != null && state != RBlockState.AIR) {
                        int[] local = VoxelSection.indexToLocal(idx);
                        callback.accept(
                            new BlockPos(wx + local[0], wy + local[1], wz + local[2]),
                            state
                        );
                    }
                }
            }
        }
    }

    /**
     * 遍歷所有已分配的 Section。
     * callback 接收 (sectionKey, VoxelSection)。
     */
    public void forEachSection(BiConsumer<Long, VoxelSection> callback) {
        for (Map.Entry<Long, VoxelSection> entry : sections.entrySet()) {
            callback.accept(entry.getKey(), entry.getValue());
        }
    }

    /**
     * 遍歷所有已標記為 dirty 的 Section。
     * callback 接收 (sectionKey, VoxelSection)。
     */
    public void forEachDirtySection(BiConsumer<Long, VoxelSection> callback) {
        for (Map.Entry<Long, VoxelSection> entry : sections.entrySet()) {
            if (entry.getValue().isDirty()) {
                callback.accept(entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * 清除所有 Section 的 dirty 標記。
     */
    public void clearAllDirty() {
        for (VoxelSection section : sections.values()) {
            section.clearDirty();
        }
    }

    /**
     * 收集所有 dirty Section 的 key 列表。
     */
    public List<Long> getDirtySectionKeys() {
        List<Long> dirtyKeys = new ArrayList<>();
        for (Map.Entry<Long, VoxelSection> entry : sections.entrySet()) {
            if (entry.getValue().isDirty()) {
                dirtyKeys.add(entry.getKey());
            }
        }
        return dirtyKeys;
    }

    // ═══ Section Key 編碼 ═══

    /**
     * 將 Section 座標編碼為 long key。
     *
     * 編碼格式：
     *   bits [63..40] = sx (20 bits, 有號)
     *   bits [39..28] = sy (12 bits, 有號)
     *   bits [27..0]  = sz (28 bits, 有號)
     */
    public static long sectionKey(int sx, int sy, int sz) {
        return ((long) (sx & 0xFFFFF) << 40)
             | ((long) (sy & 0xFFF) << 28)
             | (sz & 0xFFFFFFF);
    }

    /**
     * 從 key 解碼 Section X 座標。
     */
    public static int sectionKeyX(long key) {
        int raw = (int) ((key >> 40) & 0xFFFFF);
        // 符號擴展 20-bit
        return (raw << 12) >> 12;
    }

    /**
     * 從 key 解碼 Section Y 座標。
     */
    public static int sectionKeyY(long key) {
        int raw = (int) ((key >> 28) & 0xFFF);
        // 符號擴展 12-bit
        return (raw << 20) >> 20;
    }

    /**
     * 從 key 解碼 Section Z 座標。
     */
    public static int sectionKeyZ(long key) {
        int raw = (int) (key & 0xFFFFFFF);
        // 符號擴展 28-bit
        return (raw << 4) >> 4;
    }

    /**
     * 世界座標 → Section 座標。
     */
    public static int toSectionCoord(int worldCoord) {
        return worldCoord >> SECTION_SHIFT;
    }

    // ═══ 邊界與容量 ═══

    /**
     * 檢查世界座標是否在範圍內。
     */
    public boolean isWithinBounds(int x, int y, int z) {
        return x >= minX && x <= maxX
            && y >= minY && y <= maxY
            && z >= minZ && z <= maxZ;
    }

    /**
     * 取得範圍在 X 軸方向跨越的 Section 數量。
     */
    public int getSectionsX() { return ((maxX - minX) >> SECTION_SHIFT) + 1; }
    public int getSectionsY() { return ((maxY - minY) >> SECTION_SHIFT) + 1; }
    public int getSectionsZ() { return ((maxZ - minZ) >> SECTION_SHIFT) + 1; }

    // ═══ 統計與診斷 ═══

    public int getMinX() { return minX; }
    public int getMinY() { return minY; }
    public int getMinZ() { return minZ; }
    public int getMaxX() { return maxX; }
    public int getMaxY() { return maxY; }
    public int getMaxZ() { return maxZ; }

    public int getSizeX() { return maxX - minX + 1; }
    public int getSizeY() { return maxY - minY + 1; }
    public int getSizeZ() { return maxZ - minZ + 1; }

    /** 總體積（方塊數） */
    public long getTotalVolume() { return (long) getSizeX() * getSizeY() * getSizeZ(); }

    /** 已分配的 Section 數量 */
    public int getAllocatedSectionCount() { return sections.size(); }

    /** 總 Section 數量（包含未分配的空 Section） */
    public int getTotalSectionCount() { return getSectionsX() * getSectionsY() * getSectionsZ(); }

    /** 非空氣方塊總數 */
    public int getTotalNonAirBlocks() { return totalNonAirBlocks; }

    /** 稀疏率 = 已分配 / 總 Sections */
    public double getSparsityRatio() {
        int total = getTotalSectionCount();
        return total > 0 ? (double) sections.size() / total : 0.0;
    }

    /** 估算記憶體用量（bytes） */
    public long estimateMemoryBytes() {
        long mapOverhead = 48 + (long) sections.size() * 64; // HashMap entry overhead
        long sectionMemory = 0;
        for (VoxelSection section : sections.values()) {
            sectionMemory += section.estimateMemoryBytes();
        }
        return mapOverhead + sectionMemory;
    }

    /** 估算記憶體用量（MB） */
    public double estimateMemoryMB() {
        return estimateMemoryBytes() / (1024.0 * 1024.0);
    }

    public long getLastModifiedTick() { return lastModifiedTick; }
    public void setLastModifiedTick(long tick) { this.lastModifiedTick = tick; }

    /**
     * 壓縮所有 Section（回收空 Section、壓縮均質 Section）。
     * 建議在快照建構完成後呼叫一次。
     *
     * @return 被移除的空 Section 數量
     */
    public int compact() {
        int removed = 0;
        var iterator = sections.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            VoxelSection section = entry.getValue();
            section.compact();
            if (section.isEmpty()) {
                iterator.remove();
                removed++;
            }
        }
        removalsSinceLastCompact = 0;
        if (removed > 0) {
            LOGGER.debug("[SVO] Compacted: removed {} empty sections, {} remaining",
                removed, sections.size());
        }
        return removed;
    }

    /**
     * 清除所有資料。
     */
    public void clear() {
        sections.clear();
        totalNonAirBlocks = 0;
    }

    @Override
    public String toString() {
        return String.format(
            "SVO[%d,%d,%d → %d,%d,%d | %dx%dx%d | sections=%d/%d (%.1f%%) | nonAir=%d | mem=%.1fMB]",
            minX, minY, minZ, maxX, maxY, maxZ,
            getSizeX(), getSizeY(), getSizeZ(),
            sections.size(), getTotalSectionCount(), getSparsityRatio() * 100,
            totalNonAirBlocks, estimateMemoryMB()
        );
    }
}
