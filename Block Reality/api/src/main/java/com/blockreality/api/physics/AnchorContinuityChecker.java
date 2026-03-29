package com.blockreality.api.physics;

import com.blockreality.api.block.RBlockEntity;
import com.blockreality.api.config.BRConfig;
import com.blockreality.api.material.BlockType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 錨定連通性檢查器 (Feature 5)
 *
 * 規格（v3fix + 想法.docx）：
 *   從 RC_NODE 或 REBAR 節點出發，沿鋼筋路徑做 6-connectivity BFS，
 *   判斷是否能到達錨定點（地基/基岩）。
 *
 * 三種錨定源：
 *   1. y ≤ level.getMinBuildHeight() + 1（最底層地板）
 *   2. BlockState.below() == Blocks.BEDROCK（基岩鄰接）
 *   3. RBlockEntity.blockType == BlockType.ANCHOR_PILE（手動錨定樁）
 *
 * 效能設計（dirty flag 快取）：
 *   - 結果快取：Map<BlockPos, Boolean>（ConcurrentHashMap 線程安全）
 *   - Dirty 標記：Set<BlockPos>（ConcurrentHashMap.newKeySet）
 *   - 觸發：BlockPlaceEvent / BlockBreakEvent 的 26-鄰居掃描
 *   - 快取命中時：O(1)
 *   - 快取失效時：O(k·α(k))，k = 髒組件大小
 *
 * 實作 IAnchorChecker 介面（傳入 snapshot 版本）。
 * 同時提供 live level 版本（直接對 ServerLevel 操作，用於即時事件）。
 */
public class AnchorContinuityChecker implements IAnchorChecker {

    private static final Logger LOGGER = LogManager.getLogger("BR-AnchorChecker");
    private static final Direction[] ALL_DIRS = Direction.values();

    /** 全域單例（每個 ServerLevel 用同一個實例即可） */
    private static final AnchorContinuityChecker INSTANCE = new AnchorContinuityChecker();
    public static AnchorContinuityChecker getInstance() { return INSTANCE; }

    // ─── 快取層 ──────────────────────────────────────────────
    /** 錨定判定快取：pos → isAnchored */
    private final Map<BlockPos, Boolean> anchorCache = new ConcurrentHashMap<>();

    /** 髒位置集合：需要重新計算 */
    private final Set<BlockPos> dirtySet = ConcurrentHashMap.newKeySet();

    // ═══════════════════════════════════════════════════════
    //  IAnchorChecker 介面實作（快照版本）
    // ═══════════════════════════════════════════════════════

    @Override
    public AnchorResult check(RWorldSnapshot snapshot, Set<BlockPos> anchorSeeds) {
        Set<BlockPos> anchored = new HashSet<>();
        Set<BlockPos> orphans = new HashSet<>();

        int maxDepth = BRConfig.INSTANCE.anchorBfsMaxDepth.get();
        Deque<BlockPos> queue = new ArrayDeque<>(anchorSeeds);
        Set<BlockPos> visited = new HashSet<>(anchorSeeds);
        anchored.addAll(anchorSeeds);

        // BFS 從種子出發
        int steps = 0;
        while (!queue.isEmpty() && steps < maxDepth * 100) {
            BlockPos current = queue.poll();
            steps++;

            for (Direction dir : ALL_DIRS) {
                BlockPos neighbor = current.relative(dir);
                if (visited.contains(neighbor)) continue;

                int lx = neighbor.getX() - snapshot.getStartX();
                int ly = neighbor.getY() - snapshot.getStartY();
                int lz = neighbor.getZ() - snapshot.getStartZ();

                if (lx < 0 || ly < 0 || lz < 0
                    || lx >= snapshot.getSizeX()
                    || ly >= snapshot.getSizeY()
                    || lz >= snapshot.getSizeZ()) continue;

                RBlockState rbs = snapshot.getBlock(lx, ly, lz);
                if (rbs == null || rbs.blockId().equals("minecraft:air")) continue;

                visited.add(neighbor);
                anchored.add(neighbor);
                queue.add(neighbor);
            }
        }

        // 快照中所有非空氣方塊若沒被 BFS 觸及 = 孤島
        for (int lx = 0; lx < snapshot.getSizeX(); lx++) {
            for (int ly = 0; ly < snapshot.getSizeY(); ly++) {
                for (int lz = 0; lz < snapshot.getSizeZ(); lz++) {
                    RBlockState rbs = snapshot.getBlock(lx, ly, lz);
                    if (rbs == null || rbs.blockId().equals("minecraft:air")) continue;
                    BlockPos worldPos = new BlockPos(
                        snapshot.getStartX() + lx,
                        snapshot.getStartY() + ly,
                        snapshot.getStartZ() + lz
                    );
                    if (!anchored.contains(worldPos)) {
                        orphans.add(worldPos);
                    }
                }
            }
        }

        return new AnchorResult(anchored, orphans);
    }

    // ═══════════════════════════════════════════════════════
    //  Live Level 版本（即時事件用）
    // ═══════════════════════════════════════════════════════

    /**
     * 判定某個 RBlock 是否有錨定路徑（live level 版本，帶快取）。
     *
     * @param level 伺服器世界
     * @param pos   要檢查的方塊位置
     * @return true = 錨定（有路徑到地基），false = 孤立
     */
    public boolean isAnchored(ServerLevel level, BlockPos pos) {
        // 快取命中
        if (!dirtySet.contains(pos) && anchorCache.containsKey(pos)) {
            return anchorCache.get(pos);
        }

        // 快取失效 → 重新計算
        boolean result = computeAnchorBFS(level, pos);
        anchorCache.put(pos, result);
        dirtySet.remove(pos);
        return result;
    }

    /**
     * 從 pos 出發，沿結構路徑 BFS 直到找到錨定點。
     *
     * ★ W-6 fix: BFS 只走 RBlock 和天然錨定（基岩、底層）。
     * 不走一般原版方塊（泥土、石頭），避免 BFS 擴散到整個地形
     * 浪費預算卻找不到真正的結構錨定路徑。
     *
     * 例外：正下方的原版方塊視為地基支撐（隱式錨定），直接判定為 anchored。
     */
    private boolean computeAnchorBFS(ServerLevel level, BlockPos startPos) {
        int maxDepth = BRConfig.INSTANCE.anchorBfsMaxDepth.get();
        Deque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();

        queue.add(startPos);
        visited.add(startPos);

        int steps = 0;
        while (!queue.isEmpty() && steps < maxDepth) {
            BlockPos current = queue.poll();
            steps++;

            // 判定是否為錨定點
            if (isNaturalAnchor(level, current)) {
                return true;
            }

            // 繼續 BFS — 只走 RBlock 或天然錨定方塊
            for (Direction dir : ALL_DIRS) {
                BlockPos neighbor = current.relative(dir);
                if (visited.contains(neighbor)) continue;

                BlockState neighborState = level.getBlockState(neighbor);
                if (neighborState.isAir()) continue;

                // 快取中已知是錨定的 → 直接命中
                Boolean cached = anchorCache.get(neighbor);
                if (Boolean.TRUE.equals(cached) && !dirtySet.contains(neighbor)) {
                    return true;
                }

                // ★ B-1 fix: 合併重複的 getBlockEntity 呼叫
                BlockEntity neighborBe = level.getBlockEntity(neighbor);

                // ★ W-6: 正下方的原版實心方塊 = 地基支撐 → 直接判定錨定
                if (dir == Direction.DOWN && !(neighborBe instanceof RBlockEntity)) {
                    // 原版方塊在正下方 = 地基，隱式錨定
                    return true;
                }

                // ★ D-2 fix (原 W-6 加強): 只讓 REBAR / RC_NODE 加入 BFS 擴散
                // 想法.docx 規定：錨定路徑必須沿「鋼筋路徑」傳遞
                // CONCRETE / PLAIN 方塊不能傳遞錨定（只有鋼筋能傳遞抗拉力）
                if (neighborBe instanceof RBlockEntity neighborRbe) {
                    BlockType neighborType = neighborRbe.getBlockType();
                    if (neighborType == BlockType.REBAR || neighborType == BlockType.RC_NODE
                        || neighborType == BlockType.ANCHOR_PILE) {
                        visited.add(neighbor);
                        queue.add(neighbor);
                    }
                    // CONCRETE / PLAIN 不加入 BFS — 不是鋼筋路徑
                }
            }
        }

        return false;
    }

    // ─── 錨定源判定 ──────────────────────────────────────────

    /**
     * 判定是否為天然錨定點。
     *
     * 三種錨定源（v3fix spec）：
     *   1. y ≤ minBuildHeight + 1
     *   2. 正下方是基岩
     *   3. BlockType.ANCHOR_PILE（手動錨定樁）
     */
    public static boolean isNaturalAnchor(ServerLevel level, BlockPos pos) {
        // 1. 最底層
        if (pos.getY() <= level.getMinBuildHeight() + 1) return true;

        // 2. 正下方是基岩或屏障
        BlockState below = level.getBlockState(pos.below());
        if (below.is(Blocks.BEDROCK) || below.is(Blocks.BARRIER)) return true;

        // 3. 本身是基岩
        BlockState state = level.getBlockState(pos);
        if (state.is(Blocks.BEDROCK) || state.is(Blocks.BARRIER)) return true;

        // 4. ANCHOR_PILE BlockType（手動指定錨定點）
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof RBlockEntity rbe && rbe.getBlockType() == BlockType.ANCHOR_PILE) {
            return true;
        }

        return false;
    }

    // ─── 快取管理 ────────────────────────────────────────────

    /**
     * 標記一個位置為 dirty（及其 26 鄰居），下次查詢時重算。
     * 在 BlockPlaceEvent / BlockBreakEvent 觸發。
     */
    public void markDirty(BlockPos pos) {
        dirtySet.add(pos);
        anchorCache.remove(pos);

        // 26-鄰居都要標髒（變化可能影響周圍的錨定判斷）
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    BlockPos neighbor = pos.offset(dx, dy, dz);
                    dirtySet.add(neighbor);
                    anchorCache.remove(neighbor);
                }
            }
        }
    }

    /**
     * 清空快取（世界重載時使用）。
     */
    public void clearCache() {
        anchorCache.clear();
        dirtySet.clear();
    }

    /**
     * 快取命中率統計（診斷用）。
     */
    public String getCacheStats() {
        return String.format("AnchorCache: size=%d, dirty=%d",
            anchorCache.size(), dirtySet.size());
    }
}
