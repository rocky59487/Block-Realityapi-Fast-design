package com.blockreality.api.physics.sparse;

import com.blockreality.api.physics.RBlockState;
import com.blockreality.api.physics.RWorldSnapshot;
import net.minecraft.core.BlockPos;

import java.util.Collections;
import java.util.Set;

/**
 * SVO 到現有物理引擎的橋接層。
 *
 * 提供與 RWorldSnapshot 完全相容的 API，讓 UnionFindEngine、
 * BeamStressEngine、SupportPathAnalyzer 等現有引擎無需修改即可使用 SVO 資料。
 *
 * 設計策略：
 *   - 小型查詢（< maxSnapshotBlocks）：萃取 SVO 子區域為 RWorldSnapshot（零改動路徑）
 *   - 大型查詢（> maxSnapshotBlocks）：直接操作 SVO，走新的 Section-based 引擎
 *
 * 這是 v2.0 → v3.0 過渡期的關鍵膠水層。
 *
 * @since v3.0 Phase 1
 */
public class SparsePhysicsSnapshot {

    private final SparseVoxelOctree svo;

    public SparsePhysicsSnapshot(SparseVoxelOctree svo) {
        this.svo = svo;
    }

    /**
     * 從 SVO 中萃取指定範圍的 RWorldSnapshot。
     * 用於向後相容現有物理引擎。
     *
     * 只萃取非空 Section 中的方塊，空 Section 不會觸發陣列分配。
     *
     * @param start 起點（含）
     * @param end   終點（含）
     * @return RWorldSnapshot（與舊 SnapshotBuilder.capture() 結果相同格式）
     * @throws IllegalArgumentException 若範圍超過 maxSnapshotBlocks
     */
    public RWorldSnapshot extractSnapshot(BlockPos start, BlockPos end) {
        return extractSnapshot(start, end, Collections.emptySet());
    }

    /**
     * 從 SVO 中萃取指定範圍的 RWorldSnapshot（帶變更追蹤）。
     *
     * @param start            起點（含）
     * @param end              終點（含）
     * @param changedPositions 變更的方塊位置集合
     * @return RWorldSnapshot
     */
    public RWorldSnapshot extractSnapshot(BlockPos start, BlockPos end,
                                            Set<BlockPos> changedPositions) {
        long t0 = System.nanoTime();

        int minX = Math.min(start.getX(), end.getX());
        int minY = Math.min(start.getY(), end.getY());
        int minZ = Math.min(start.getZ(), end.getZ());
        int maxX = Math.max(start.getX(), end.getX());
        int maxY = Math.max(start.getY(), end.getY());
        int maxZ = Math.max(start.getZ(), end.getZ());

        int sizeX = maxX - minX + 1;
        int sizeY = maxY - minY + 1;
        int sizeZ = maxZ - minZ + 1;
        int totalBlocks = sizeX * sizeY * sizeZ;

        // 使用 RWorldSnapshot 的配置限制
        int effectiveMax = RWorldSnapshot.getMaxSnapshotBlocks();
        if (totalBlocks > effectiveMax) {
            throw new IllegalArgumentException(
                String.format("Extract region exceeds max_snapshot_blocks (%d). Attempted: %dx%dx%d = %d. " +
                    "Use Section-based engines for larger regions.",
                    effectiveMax, sizeX, sizeY, sizeZ, totalBlocks)
            );
        }

        RBlockState[] blocks = new RBlockState[totalBlocks];
        int nonAirCount = 0;

        // 以 Section 為單位遍歷 SVO（跳過空 Section）
        int minSX = minX >> 4, maxSX = maxX >> 4;
        int minSY = minY >> 4, maxSY = maxY >> 4;
        int minSZ = minZ >> 4, maxSZ = maxZ >> 4;

        for (int sx = minSX; sx <= maxSX; sx++) {
            for (int sz = minSZ; sz <= maxSZ; sz++) {
                for (int sy = minSY; sy <= maxSY; sy++) {
                    VoxelSection section = svo.getSection(sx, sy, sz);
                    if (section == null || section.isEmpty()) continue;

                    // Section 與萃取範圍的交集
                    int secMinX = Math.max(minX, sx << 4);
                    int secMaxX = Math.min(maxX, (sx << 4) + 15);
                    int secMinY = Math.max(minY, sy << 4);
                    int secMaxY = Math.min(maxY, (sy << 4) + 15);
                    int secMinZ = Math.max(minZ, sz << 4);
                    int secMaxZ = Math.min(maxZ, (sz << 4) + 15);

                    for (int y = secMinY; y <= secMaxY; y++) {
                        for (int z = secMinZ; z <= secMaxZ; z++) {
                            for (int x = secMinX; x <= secMaxX; x++) {
                                RBlockState state = section.getBlock(x & 15, y & 15, z & 15);
                                if (state != RBlockState.AIR) {
                                    int idx = (x - minX) + sizeX * ((y - minY) + sizeY * (z - minZ));
                                    blocks[idx] = state;
                                    nonAirCount++;
                                }
                            }
                        }
                    }
                }
            }
        }

        long elapsed = System.nanoTime() - t0;

        return new RWorldSnapshot(minX, minY, minZ, sizeX, sizeY, sizeZ,
            blocks, elapsed, changedPositions);
    }

    /**
     * 檢查指定範圍是否可以安全萃取為 RWorldSnapshot。
     * 超過 maxSnapshotBlocks 的範圍應使用 Section-based 引擎。
     */
    public boolean canExtractAsSnapshot(BlockPos start, BlockPos end) {
        int sizeX = Math.abs(end.getX() - start.getX()) + 1;
        int sizeY = Math.abs(end.getY() - start.getY()) + 1;
        int sizeZ = Math.abs(end.getZ() - start.getZ()) + 1;
        return (long) sizeX * sizeY * sizeZ <= RWorldSnapshot.getMaxSnapshotBlocks();
    }

    /**
     * 取得底層 SVO 參照。
     * 用於 Section-based 引擎直接操作。
     */
    public SparseVoxelOctree getSVO() {
        return svo;
    }

    /**
     * 取得指定座標的方塊（直接委派給 SVO）。
     */
    public RBlockState getBlock(int x, int y, int z) {
        return svo.getBlock(x, y, z);
    }

    /**
     * 估算萃取範圍的非空方塊數（不實際分配陣列）。
     * 用於決定使用 RWorldSnapshot 還是 Section-based 引擎。
     */
    public int estimateNonAirCount(int minX, int minY, int minZ,
                                     int maxX, int maxY, int maxZ) {
        int estimate = 0;
        int minSX = minX >> 4, maxSX = maxX >> 4;
        int minSY = minY >> 4, maxSY = maxY >> 4;
        int minSZ = minZ >> 4, maxSZ = maxZ >> 4;

        for (int sx = minSX; sx <= maxSX; sx++) {
            for (int sz = minSZ; sz <= maxSZ; sz++) {
                for (int sy = minSY; sy <= maxSY; sy++) {
                    VoxelSection section = svo.getSection(sx, sy, sz);
                    if (section != null) {
                        estimate += section.getNonAirCount();
                    }
                }
            }
        }
        return estimate;
    }
}
