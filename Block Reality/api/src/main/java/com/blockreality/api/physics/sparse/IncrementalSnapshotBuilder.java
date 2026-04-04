package com.blockreality.api.physics.sparse;

import com.blockreality.api.block.RBlockEntity;
import com.blockreality.api.chisel.ChiselState;
import com.blockreality.api.material.RMaterial;
import com.blockreality.api.material.VanillaMaterialMap;
import com.blockreality.api.physics.RBlockState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * 增量式快照建構器 — 取代舊的 SnapshotBuilder.capture() 全量掃描。
 *
 * 三階段設計：
 *   Phase 1: 快速索引 — 只掃描 Section 標頭，建立 SVO 骨架
 *            利用 hasOnlyAir() 跳過空 Section
 *            時間: O(Section 數量) ≈ < 5ms for 107K sections
 *
 *   Phase 2: 延遲填充 — 首次存取 Section 時才讀取 block state
 *            物理引擎觸及某 Section → 觸發 populateSection()
 *            可用 PhysicsExecutor 多執行緒並行填充
 *
 *   Phase 3: 增量同步 — 只更新 dirty sections
 *            ServerTickHandler 收集 BlockEvent → 標記 dirty sections
 *            下次計算前只重建 dirty sections（而非全量重掃）
 *
 * 參考來源：
 *   - Embeddium/Sodium: Section-based chunk rebuild trigger
 *   - C²ME: Concurrent chunk access patterns
 *
 * @since v3.0 Phase 1
 */
public class IncrementalSnapshotBuilder {

    private static final Logger LOGGER = LogManager.getLogger("BR/IncrementalSnapshot");

    // ═══════════════════════════════════════════════════════
    //  Phase 1: 快速索引 — 建立 SVO 骨架（主執行緒）
    // ═══════════════════════════════════════════════════════

    /**
     * 在主執行緒中快速掃描，建立 SVO 骨架。
     * 只檢查 Section 是否為空，不讀取個別 block state。
     *
     * 必須在主執行緒 (Server Thread) 呼叫。
     *
     * @param level 伺服器世界
     * @param minX  最小 X（含）
     * @param minY  最小 Y（含）
     * @param minZ  最小 Z（含）
     * @param maxX  最大 X（含）
     * @param maxY  最大 Y（含）
     * @param maxZ  最大 Z（含）
     * @return 已建立骨架的 SVO（Section 尚未填充方塊資料）
     */
    public static SparseVoxelOctree buildSkeleton(ServerLevel level,
                                                    int minX, int minY, int minZ,
                                                    int maxX, int maxY, int maxZ) {
        long t0 = System.nanoTime();

        SparseVoxelOctree svo = new SparseVoxelOctree(minX, minY, minZ, maxX, maxY, maxZ);

        int minCX = minX >> 4, maxCX = maxX >> 4;
        int minCZ = minZ >> 4, maxCZ = maxZ >> 4;

        int sectionsScanned = 0;
        int nonEmptySections = 0;

        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;

                int minSY = Math.max(chunk.getMinBuildHeight() >> 4, minY >> 4);
                int maxSY = Math.min((chunk.getMaxBuildHeight() - 1) >> 4, maxY >> 4);

                for (int sy = minSY; sy <= maxSY; sy++) {
                    int sectionIdx = chunk.getSectionIndex(sy << 4);
                    if (sectionIdx < 0 || sectionIdx >= chunk.getSectionsCount()) continue;

                    LevelChunkSection mcSection = chunk.getSection(sectionIdx);
                    sectionsScanned++;

                    if (mcSection == null || mcSection.hasOnlyAir()) continue;

                    // 非空 Section → 建立空殼 VoxelSection（稍後填充）
                    int worldSX = cx;
                    int worldSZ = cz;
                    VoxelSection vs = new VoxelSection(cx << 4, sy << 4, cz << 4);
                    // 標記為 dirty 以觸發後續填充
                    vs.markDirty();
                    svo.putSection(cx, sy, cz, vs);
                    nonEmptySections++;
                }
            }
        }

        long elapsed = System.nanoTime() - t0;
        LOGGER.debug("[IncrementalSnapshot] Skeleton built: scanned {} sections, {} non-empty, in {:.2f}ms",
            sectionsScanned, nonEmptySections, elapsed / 1e6);

        return svo;
    }

    /**
     * 便捷版本：使用 BlockPos 範圍。
     */
    public static SparseVoxelOctree buildSkeleton(ServerLevel level, BlockPos min, BlockPos max) {
        return buildSkeleton(level,
            Math.min(min.getX(), max.getX()),
            Math.min(min.getY(), max.getY()),
            Math.min(min.getZ(), max.getZ()),
            Math.max(min.getX(), max.getX()),
            Math.max(min.getY(), max.getY()),
            Math.max(min.getZ(), max.getZ())
        );
    }

    // ═══════════════════════════════════════════════════════
    //  Phase 2: Section 填充 — 讀取方塊資料
    // ═══════════════════════════════════════════════════════

    /**
     * 填充單個 Section 的方塊資料。
     * 必須在主執行緒呼叫（需存取 ServerLevel）。
     *
     * @param level 伺服器世界
     * @param svo   目標 SVO
     * @param sx    Section X 座標
     * @param sy    Section Y 座標
     * @param sz    Section Z 座標
     * @return 填充的非空氣方塊數（0 = Section 可能已為空或不存在）
     */
    public static int populateSection(ServerLevel level, SparseVoxelOctree svo,
                                       int sx, int sy, int sz) {
        VoxelSection section = svo.getSection(sx, sy, sz);
        if (section == null) return 0;

        LevelChunk chunk = level.getChunkSource().getChunkNow(sx, sz);
        if (chunk == null) return 0;

        int sectionIdx = chunk.getSectionIndex(sy << 4);
        if (sectionIdx < 0 || sectionIdx >= chunk.getSectionsCount()) return 0;

        LevelChunkSection mcSection = chunk.getSection(sectionIdx);
        if (mcSection == null || mcSection.hasOnlyAir()) return 0;

        // 計算此 Section 與 SVO 邊界的交集
        int secMinX = Math.max(svo.getMinX(), sx << 4);
        int secMaxX = Math.min(svo.getMaxX(), (sx << 4) + 15);
        int secMinY = Math.max(svo.getMinY(), sy << 4);
        int secMaxY = Math.min(svo.getMaxY(), (sy << 4) + 15);
        int secMinZ = Math.max(svo.getMinZ(), sz << 4);
        int secMaxZ = Math.min(svo.getMaxZ(), (sz << 4) + 15);

        RBlockState[] blocks = new RBlockState[VoxelSection.VOLUME];
        short nonAirCount = 0;

        for (int y = secMinY; y <= secMaxY; y++) {
            for (int z = secMinZ; z <= secMaxZ; z++) {
                for (int x = secMinX; x <= secMaxX; x++) {
                    BlockState mcState = mcSection.getBlockState(x & 15, y & 15, z & 15);

                    if (!mcState.isAir()) {
                        BlockEntity be = chunk.getBlockEntity(new BlockPos(x, y, z),
                            LevelChunk.EntityCreationType.CHECK);

                        RBlockState rState = (be != null)
                            ? translateWithEntity(mcState, be)
                            : translate(mcState);

                        int lx = x & 15;
                        int ly = y & 15;
                        int lz = z & 15;
                        int idx = lx + 16 * (ly + 16 * lz);
                        blocks[idx] = rState;
                        nonAirCount++;
                    }
                }
            }
        }

        section.populate(blocks, nonAirCount);
        section.clearDirty();

        return nonAirCount;
    }

    /**
     * 批次填充所有 dirty Section。
     * 必須在主執行緒呼叫。
     *
     * @return 總填充的非空氣方塊數
     */
    public static int populateAllDirty(ServerLevel level, SparseVoxelOctree svo) {
        long t0 = System.nanoTime();
        int totalFilled = 0;
        int sectionsPopulated = 0;

        List<Long> dirtyKeys = svo.getDirtySectionKeys();
        for (long key : dirtyKeys) {
            int sx = SparseVoxelOctree.sectionKeyXStatic(key);
            int sy = SparseVoxelOctree.sectionKeyYStatic(key);
            int sz = SparseVoxelOctree.sectionKeyZStatic(key);

            int filled = populateSection(level, svo, sx, sy, sz);
            totalFilled += filled;
            sectionsPopulated++;
        }

        long elapsed = System.nanoTime() - t0;
        LOGGER.debug("[IncrementalSnapshot] Populated {} dirty sections ({} blocks) in {:.2f}ms",
            sectionsPopulated, totalFilled, elapsed / 1e6);

        return totalFilled;
    }

    // ═══════════════════════════════════════════════════════
    //  Phase 3: 增量同步 — 只更新變更的 Section
    // ═══════════════════════════════════════════════════════

    /**
     * 增量更新：只重建包含變更方塊的 Section。
     *
     * @param level            伺服器世界
     * @param svo              目標 SVO
     * @param changedPositions 變更的方塊位置集合
     * @return 更新的 Section 數量
     */
    public static int incrementalUpdate(ServerLevel level, SparseVoxelOctree svo,
                                         Set<BlockPos> changedPositions) {
        if (changedPositions.isEmpty()) return 0;

        long t0 = System.nanoTime();

        // 收集受影響的 Section
        java.util.HashSet<Long> affectedSections = new java.util.HashSet<>();
        for (BlockPos pos : changedPositions) {
            int sx = pos.getX() >> 4;
            int sy = pos.getY() >> 4;
            int sz = pos.getZ() >> 4;
            affectedSections.add(SparseVoxelOctree.sectionKey(sx, sy, sz));
        }

        // 重建受影響的 Section
        int updated = 0;
        for (long key : affectedSections) {
            int sx = SparseVoxelOctree.sectionKeyXStatic(key);
            int sy = SparseVoxelOctree.sectionKeyYStatic(key);
            int sz = SparseVoxelOctree.sectionKeyZStatic(key);

            // 確保 Section 存在（可能是新建的區域）
            VoxelSection section = svo.getSection(sx, sy, sz);
            if (section == null) {
                section = new VoxelSection(sx << 4, sy << 4, sz << 4);
                svo.putSection(sx, sy, sz, section);
            }

            populateSection(level, svo, sx, sy, sz);
            updated++;
        }

        long elapsed = System.nanoTime() - t0;
        LOGGER.debug("[IncrementalSnapshot] Incremental update: {} positions → {} sections in {:.2f}ms",
            changedPositions.size(), updated, elapsed / 1e6);

        return updated;
    }

    // ═══════════════════════════════════════════════════════
    //  完整快照建構（Phase 1 + Phase 2 一步完成）
    // ═══════════════════════════════════════════════════════

    /**
     * 一步建構完整快照（向後相容 SnapshotBuilder.capture 的使用場景）。
     * 適用於較小範圍或首次建構。
     *
     * @param level 伺服器世界
     * @param min   最小座標
     * @param max   最大座標
     * @return 完整填充的 SVO
     */
    public static SparseVoxelOctree captureComplete(ServerLevel level, BlockPos min, BlockPos max) {
        SparseVoxelOctree svo = buildSkeleton(level, min, max);
        populateAllDirty(level, svo);
        svo.compact();
        return svo;
    }

    /**
     * 一步建構完整快照（座標版本）。
     */
    public static SparseVoxelOctree captureComplete(ServerLevel level,
                                                      int minX, int minY, int minZ,
                                                      int maxX, int maxY, int maxZ) {
        SparseVoxelOctree svo = buildSkeleton(level, minX, minY, minZ, maxX, maxY, maxZ);
        populateAllDirty(level, svo);
        svo.compact();
        return svo;
    }

    // ═══════════════════════════════════════════════════════
    //  BlockState 轉譯（從 SnapshotBuilder 移植）
    // ═══════════════════════════════════════════════════════

    /**
     * 將 Minecraft BlockState 轉換為 RBlockState。
     * 與 SnapshotBuilder.translate() 邏輯完全一致。
     */
    private static RBlockState translate(BlockState mcState) {
        String blockId = BuiltInRegistries.BLOCK.getKey(mcState.getBlock()).toString();
        boolean isAnchor = mcState.is(Blocks.BEDROCK) || mcState.is(Blocks.BARRIER);
        RMaterial mat = VanillaMaterialMap.getInstance().getMaterial(blockId);

        return new RBlockState(
            blockId,
            (float) mat.getDensity(),
            (float) mat.getRcomp(),
            (float) mat.getRtens(),
            isAnchor
        );
    }

    /**
     * 帶 BlockEntity 感知的轉譯。
     * 與 SnapshotBuilder.translateWithEntity() 邏輯完全一致。
     */
    private static RBlockState translateWithEntity(BlockState mcState, BlockEntity be) {
        if (be instanceof RBlockEntity rbe) {
            RMaterial mat = rbe.getMaterial();
            ChiselState cs = rbe.getChiselState();
            return new RBlockState(
                mat.getMaterialId(),
                (float) (mat.getDensity() * cs.fillRatio()),
                (float) mat.getRcomp(),
                (float) mat.getRtens(),
                rbe.isAnchored(),
                (float) cs.crossSectionArea(),
                (float) cs.momentOfInertiaX(),
                (float) cs.sectionModulusX(),
                (float) cs.momentOfInertiaY(),
                (float) cs.sectionModulusY()
            );
        }
        return translate(mcState);
    }
}
