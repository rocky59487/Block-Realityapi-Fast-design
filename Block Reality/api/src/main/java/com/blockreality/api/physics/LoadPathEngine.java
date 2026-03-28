package com.blockreality.api.physics;

import com.blockreality.api.block.RBlockEntity;
import com.blockreality.api.chisel.ChiselState;
import com.blockreality.api.config.BRConfig;
import com.blockreality.api.event.LoadPathChangedEvent;
import com.blockreality.api.event.StressUpdateEvent;
import com.blockreality.api.material.RMaterial;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.MinecraftForge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.concurrent.NotThreadSafe;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 載重傳導路徑引擎 (Load Path Engine)
 *
 * 核心概念：把建築變成一棵「往下長」的支撐樹。
 * 每個方塊記住「我的重量傳給誰 (Parent)」，
 * 當方塊被放置或破壞時，只沿著傳導路徑做局部更新。
 *
 * 三大操作：
 *   1. onBlockPlaced — 方塊被放置時：找到最佳支撐者，把自重傳下去
 *   2. onBlockBroken — 方塊被破壞時：通知依賴者尋找新的支撐，找不到就崩塌
 *   3. findBestSupport — 在鄰居中找到最強的支撐者
 *
 * 效能：
 *   - 放置：O(H)，H = 樓層高度（載重沿樹往下傳遞）
 *   - 破壞：O(K)，K = 受影響的依賴者數量（局部重新連接）
 *   - 完全不需要全局 BFS 掃描
 *
 * 力學規則：
 *   - 重力方向：-Y（Minecraft 世界座標）
 *   - 純壓力傳遞路徑：垂直向下（直覺路徑）
 *   - 側向支撐：需要 Rtens > 0 的材料（鋼筋、鐵、木材 = 有抗拉 = 可懸挑）
 *   - 壓碎判定：currentLoad × g > Rcomp × 1e6 × A (質量→力→與容量比較)
 */
@NotThreadSafe  // Static methods; must be called from server thread only
public class LoadPathEngine {

    private static final Logger LOGGER = LogManager.getLogger("BR-LoadPath");

    /** 重力加速度 (m/s²) */
    private static final double GRAVITY = 9.81;

    /** 方塊截面積 1m × 1m = 1 m² */
    private static final double BLOCK_CROSS_SECTION_AREA = 1.0;

    /**
     * ★ M-4: FallingBlockEntity 即時崩塌上限。
     * 超過此數量的方塊改為掉落物 (ItemEntity)，避免大量 FallingBlockEntity 卡伺服器。
     * v3fix §1.6 規定預設 64。
     */
    private static final int MAX_FALLING_ENTITIES = 64;

    /**
     * 6 方向鄰居 (Minecraft Direction enum)
     */
    private static final Direction[] ALL_DIRECTIONS = Direction.values();

    /**
     * 優先搜索方向：先往下找支撐，然後側向
     */
    private static final Direction[] SUPPORT_SEARCH_ORDER = {
        Direction.DOWN,         // 最佳：正下方
        Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, // 側向
        Direction.UP            // 最差：往上（懸吊結構，非常特殊）
    };

    // ═══════════════════════════════════════════════════════
    //  事件入口 1：方塊被放置
    // ═══════════════════════════════════════════════════════

    /**
     * 當 RBlock 被放置時呼叫。
     *
     * 流程：
     *   1. 計算自重 (density × 1m³)
     *   2. 找最佳支撐者 (findBestSupport)
     *   3. 設定 supportParent
     *   4. 將自重沿傳導路徑往下傳遞 (propagateLoadDown)
     *   5. 檢查傳遞路徑上是否有方塊被壓碎
     *
     * @return 是否成功找到支撐（false = 懸空方塊，會直接掉落）
     */
    public static boolean onBlockPlaced(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof RBlockEntity rbe)) return false;

        // ★ A-5 語意釐清: currentLoad = 自重 + 所有依賴方塊的累積荷載
        // 新放置時僅有自重（尚無依賴者），後續 propagateLoadDown 會累加到支撐鏈上
        // 破壞時 cachedLoad 包含完整累積荷載，正確從支撐鏈中移除
        float selfWeight = rbe.getSelfWeight();
        rbe.setCurrentLoad(selfWeight);

        // 找支撐者
        BlockPos supporter = findBestSupport(level, pos, rbe);

        if (supporter != null) {
            rbe.setSupportParent(supporter);
            // 沿路徑往下傳遞載重
            propagateLoadDown(level, supporter, selfWeight);
            return true;
        }

        // 沒有支撐 — 檢查是否為錨定點
        if (rbe.isAnchored()) {
            return true;
        }

        // 真的懸空了
        LOGGER.debug("[LoadPath] Block at {} has no support, will fall", pos);
        return false;
    }

    // ═══════════════════════════════════════════════════════
    //  事件入口 2：方塊被破壞
    // ═══════════════════════════════════════════════════════

    /**
     * 當 RBlock 被破壞時呼叫。
     *
     * 流程：
     *   1. 從自己的 parent 上移除自己的總載重
     *   2. 收集所有以自己為 parent 的鄰居方塊 (orphans)
     *   3. 每個 orphan 嘗試找新的支撐者 (re-parent)
     *   4. 找不到支撐的 orphan → 級聯崩塌
     *
     * @return 崩塌的方塊數量
     */
    public static int onBlockBroken(ServerLevel level, BlockPos brokenPos) {
        BlockEntity be = level.getBlockEntity(brokenPos);
        if (!(be instanceof RBlockEntity rbe)) return 0;

        float totalLoad = rbe.getCurrentLoad();
        BlockPos parent = rbe.getSupportParent();
        return onBlockBrokenCached(level, brokenPos, parent, totalLoad);
    }

    /**
     * BUG-2 修復版：使用事先快取的 BE 資料，不依賴 BE 在 server.execute() 後仍存在。
     *
     * @param brokenPos     被破壞的方塊位置
     * @param cachedParent  破壞前讀取的 supportParent（可 null）
     * @param cachedLoad    破壞前讀取的 currentLoad
     */
    public static int onBlockBrokenCached(ServerLevel level, BlockPos brokenPos,
                                           BlockPos cachedParent, float cachedLoad) {
        // Step 1: 從 parent 移除載重（用快取值，不讀已消失的 BE）
        if (cachedParent != null) {
            propagateLoadDown(level, cachedParent, -cachedLoad);
        }

        // Step 2: 收集所有以我為 parent 的鄰居 (orphans)
        List<BlockPos> orphans = findOrphans(level, brokenPos);

        if (orphans.isEmpty()) return 0;

        // Step 3: 每個 orphan 嘗試找新支撐
        int collapseCount = 0;
        Deque<BlockPos> collapseQueue = new ArrayDeque<>(orphans);

        while (!collapseQueue.isEmpty()) {
            BlockPos orphanPos = collapseQueue.poll();
            BlockEntity orphanBe = level.getBlockEntity(orphanPos);
            if (!(orphanBe instanceof RBlockEntity orphanRbe)) continue;

            // 已經是錨定點，不會掉
            if (orphanRbe.isAnchored()) continue;

            // 嘗試找新支撐（排除已斷開的 brokenPos）
            BlockPos newSupport = findBestSupport(level, orphanPos, orphanRbe);

            if (newSupport != null && !newSupport.equals(brokenPos)) {
                // 成功重新連接
                float orphanLoad = orphanRbe.getCurrentLoad();
                // 從舊 parent 移除（如果還在的話）
                BlockPos oldParent = orphanRbe.getSupportParent();
                if (oldParent != null && !oldParent.equals(brokenPos)) {
                    propagateLoadDown(level, oldParent, -orphanLoad);
                }
                orphanRbe.setSupportParent(newSupport);
                propagateLoadDown(level, newSupport, orphanLoad);
            } else {
                // 找不到支撐 — 崩塌！
                collapseCount++;

                // ★ W-1 fix: 掉落前先清理 BE 狀態，避免幽靈鏈結
                float orphanLoad = orphanRbe.getCurrentLoad();
                BlockPos oldParent = orphanRbe.getSupportParent();
                // 從舊 parent 移除載重（如果 parent 還存在且不是已破壞的方塊）
                if (oldParent != null && !oldParent.equals(brokenPos)) {
                    propagateLoadDown(level, oldParent, -orphanLoad);
                }
                orphanRbe.setSupportParent(null);
                orphanRbe.setCurrentLoad(0f);

                // 收集這個 orphan 的依賴者（級聯）— 必須在清除前收集
                List<BlockPos> cascadeOrphans = findOrphans(level, orphanPos);
                collapseQueue.addAll(cascadeOrphans);

                // ★ M-4 fix: 執行掉落，超過上限改為 ItemEntity 避免卡伺服器
                BlockState state = level.getBlockState(orphanPos);
                if (!state.isAir()) {
                    level.removeBlockEntity(orphanPos);
                    if (collapseCount <= MAX_FALLING_ENTITIES) {
                        FallingBlockEntity.fall(level, orphanPos, state);
                    } else {
                        // 超過上限 — 降級為掉落物
                        ItemStack drop = new ItemStack(state.getBlock().asItem());
                        if (!drop.isEmpty()) {
                            ItemEntity item = new ItemEntity(level,
                                orphanPos.getX() + 0.5, orphanPos.getY() + 0.5, orphanPos.getZ() + 0.5,
                                drop);
                            level.addFreshEntity(item);
                        }
                        level.removeBlock(orphanPos, false);
                    }
                }
            }
        }

        if (collapseCount > 0) {
            LOGGER.info("[LoadPath] Cascade collapse: {} blocks fell from break at {}",
                collapseCount, brokenPos);
        }

        return collapseCount;
    }

    // ═══════════════════════════════════════════════════════
    //  核心：找最佳支撐者
    // ═══════════════════════════════════════════════════════

    /**
     * 在 6 個鄰居中找到最佳支撐者。
     *
     * 優先級：
     *   1. 正下方有方塊 → 直接壓力傳遞（最自然）
     *   2. 側向有高 Rtens 的方塊 → 懸臂/側撐（鋼筋、鐵）
     *   3. 上方 → 懸吊結構（特殊情況）
     *
     * 條件：
     *   - 候選者必須自己有支撐 (hasSupport) 或是錨定點
     *   - 側向支撐需要候選者的 Rtens > 0（抗拉才能水平傳力）
     *   - 不會形成環路（候選者的 parent chain 不包含自己）
     *
     * @return 最佳支撐者的位置，null = 找不到任何支撐
     */
    public static BlockPos findBestSupport(ServerLevel level, BlockPos pos, RBlockEntity self) {
        BlockPos bestSupport = null;
        double bestScore = -1;

        for (Direction dir : SUPPORT_SEARCH_ORDER) {
            BlockPos neighborPos = pos.relative(dir);
            BlockState neighborState = level.getBlockState(neighborPos);

            // 空氣不能支撐
            if (neighborState.isAir()) continue;

            // 天然錨定（基岩/屏障/底層/ANCHOR_PILE）— 統一由 AnchorContinuityChecker 判斷
            if (AnchorContinuityChecker.isNaturalAnchor(level, neighborPos)) {
                return neighborPos; // 立刻回傳，最佳支撐
            }

            // 有 RBlockEntity 的方塊
            BlockEntity neighborBe = level.getBlockEntity(neighborPos);
            if (neighborBe instanceof RBlockEntity neighborRbe) {
                // 候選者必須自己有支撐
                if (!neighborRbe.hasSupport()) continue;

                // ★ W-4 fix + T-3 fix: 可配置深度的 parent chain 環路偵測
                // 檢查候選者的 parent chain 中是否包含 pos（自己）
                int cycleDepth = BRConfig.INSTANCE.cycleDetectMaxDepth.get();
                if (wouldCreateCycle(level, pos, neighborPos, cycleDepth)) continue;

                RMaterial neighborMat = neighborRbe.getMaterial();
                double score = calculateSupportScore(dir, neighborMat, neighborRbe);

                if (score > bestScore) {
                    bestScore = score;
                    bestSupport = neighborPos;
                }
            } else {
                // 原版非空氣方塊（石頭、泥土等）— 視為有支撐的基礎
                // 只有正下方算數（原版方塊沒有抗拉數據）
                if (dir == Direction.DOWN) {
                    double score = 100.0; // 地面 = 基礎分
                    if (score > bestScore) {
                        bestScore = score;
                        bestSupport = neighborPos;
                    }
                }
            }
        }

        return bestSupport;
    }

    /**
     * 計算支撐分數 — 決定哪個鄰居是最佳支撐者。
     *
     * 評分邏輯：
     *   - 正下方 (DOWN): baseScore = 1000 + Rcomp（壓力傳遞，最自然）
     *   - 側向 (N/S/E/W): baseScore = Rtens × 10（需要抗拉能力）
     *   - 正上方 (UP): baseScore = Rtens × 5（懸吊，罕見）
     *   - 加分：候選者是錨定點 → +5000
     *   - 減分：候選者載重接近極限 → score × (1 - utilization)
     */
    private static double calculateSupportScore(Direction dir, RMaterial mat, RBlockEntity rbe) {
        double baseScore;

        if (dir == Direction.DOWN) {
            // 壓力傳遞 — 看抗壓
            baseScore = 1000.0 + mat.getRcomp();
        } else if (dir == Direction.UP) {
            // 懸吊 — 需要抗拉
            if (mat.getRtens() <= 0) return -1;
            baseScore = mat.getRtens() * 5.0;
        } else {
            // 側向 — 需要抗拉（懸臂效應）
            if (mat.getRtens() <= 0) return -1;
            baseScore = mat.getRtens() * 10.0;
        }

        // 錨定加分
        if (rbe.isAnchored()) {
            baseScore += 5000.0;
        }

        // ★ v4-fix: 載重利用率折扣 — 正確的力/應力單位轉換
        // capacity(N) = Rcomp(Pa) × A(m²), loadForce(N) = mass(kg) × g(m/s²)
        double effectiveArea = rbe.getChiselState().crossSectionArea();
        double capacity = mat.getRcomp() * 1e6 * effectiveArea; // Pa × m² = N
        if (capacity > 0) {
            double loadForce = rbe.getCurrentLoad() * GRAVITY; // kg → N
            double utilization = loadForce / capacity;
            if (utilization >= 1.0) return -1; // 已超載
            baseScore *= (1.0 - utilization * 0.5); // 最多折半
        }

        return baseScore;
    }

    /**
     * ★ W-4: 環路偵測 — 從 candidatePos 沿 parent chain 往上追溯 maxDepth 層，
     * 檢查是否會回到 selfPos。
     */
    private static boolean wouldCreateCycle(ServerLevel level, BlockPos selfPos,
                                             BlockPos candidatePos, int maxDepth) {
        BlockPos current = candidatePos;
        for (int i = 0; i < maxDepth; i++) {
            BlockEntity be = level.getBlockEntity(current);
            if (!(be instanceof RBlockEntity rbe)) return false;
            BlockPos parent = rbe.getSupportParent();
            if (parent == null) return false;
            if (parent.equals(selfPos)) return true;
            current = parent;
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════
    //  核心：載重傳遞
    // ═══════════════════════════════════════════════════════

    /**
     * 沿支撐樹向下傳遞載重變化。
     *
     * @param level    世界
     * @param startPos 開始傳遞的位置
     * @param delta    載重變化量（正 = 增加，負 = 減少）
     */
    public static void propagateLoadDown(ServerLevel level, BlockPos startPos, float delta) {
        if (delta == 0f) return;

        BlockPos current = startPos;
        int maxDepth = BRConfig.INSTANCE.anchorBfsMaxDepth.get();
        int steps = 0;

        while (current != null && steps < maxDepth) {
            BlockEntity be = level.getBlockEntity(current);
            if (!(be instanceof RBlockEntity rbe)) break;

            float oldLoad = rbe.getCurrentLoad();
            float newLoad = rbe.addLoad(delta);

            // ★ Post LoadPathChangedEvent
            MinecraftForge.EVENT_BUS.post(new LoadPathChangedEvent(level, current, oldLoad, newLoad));

            // ★ v4-fix: 壓碎檢查 — 正確的力/容量單位
            // loadForce(N) = mass(kg) × g(m/s²), capacity(N) = Rcomp(Pa) × A(m²)
            RMaterial mat = rbe.getMaterial();
            double effectiveArea = rbe.getChiselState().crossSectionArea();
            double capacity = mat.getRcomp() * 1e6 * effectiveArea; // Pa × m² = N
            double loadForce = newLoad * GRAVITY; // kg → N
            float oldStress = 0f;
            float newStress = 0f;

            if (capacity < Float.MAX_VALUE && loadForce > capacity) {
                float ratio = (float) (loadForce / capacity);
                oldStress = rbe.getStressLevel();
                newStress = Math.min(1.0f, ratio);
                rbe.setStressLevelBatch(newStress);
                // 嚴重超載 → 標記為待崩塌（由 tick 或下一次事件處理）
                if (ratio > 1.5f) {
                    LOGGER.warn("[LoadPath] Block at {} critically overloaded: {}N / {}N",
                        current, String.format("%.0f", loadForce), String.format("%.0f", capacity));
                }
            } else {
                // 更新應力視覺化
                oldStress = rbe.getStressLevel();
                newStress = capacity > 0 ? (float) (loadForce / capacity) : 0f;
                newStress = Math.min(1.0f, newStress);
                rbe.setStressLevelBatch(newStress);
            }

            // ★ Post StressUpdateEvent if stress changed
            if (Math.abs(newStress - oldStress) > 0.001f) {
                MinecraftForge.EVENT_BUS.post(new StressUpdateEvent(level, current, oldStress, newStress));
            }

            // 繼續往 parent 傳
            current = rbe.getSupportParent();
            steps++;
        }
    }

    // ═══════════════════════════════════════════════════════
    //  輔助：收集依賴者
    // ═══════════════════════════════════════════════════════

    /**
     * 找出所有以 targetPos 為 supportParent 的鄰居方塊。
     * 這些方塊在 targetPos 被破壞後會變成孤兒。
     */
    private static List<BlockPos> findOrphans(ServerLevel level, BlockPos targetPos) {
        List<BlockPos> orphans = new ArrayList<>();

        for (Direction dir : ALL_DIRECTIONS) {
            BlockPos neighborPos = targetPos.relative(dir);
            BlockEntity neighborBe = level.getBlockEntity(neighborPos);
            if (neighborBe instanceof RBlockEntity neighborRbe) {
                BlockPos parentPos = neighborRbe.getSupportParent();
                if (targetPos.equals(parentPos)) {
                    orphans.add(neighborPos);
                }
            }
        }

        return orphans;
    }

    // ═══════════════════════════════════════════════════════
    //  區塊卸載清理（Chunk Unload — 輕量清理，不觸發崩塌）
    // ═══════════════════════════════════════════════════════

    /**
     * 區塊卸載時的輕量清理。
     *
     * 遍歷該 chunk 內的所有 RBlockEntity，
     * 將其 supportParent 設為 null、currentLoad 歸零，
     * 但 **不** 觸發 FallingBlockEntity 或級聯崩塌。
     *
     * 當區塊重新加載時，RBlockEntity.load() 從 NBT 恢復 supportParent，
     * 若 parent 仍存在則自動重建支撐鏈。
     *
     * @param level    伺服器世界
     * @param chunkPos 正在卸載的區塊座標
     */
    public static void onChunkUnload(ServerLevel level, ChunkPos chunkPos) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z);
        if (chunk == null) return;

        int cleaned = 0;
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (be instanceof RBlockEntity rbe) {
                // ★ R6-3 fix: 只斷開 supportParent 指標，不修改 currentLoad。
                //
                // 原因：onChunkUnload 後 RBlockEntity.saveAdditional() 會被呼叫，
                // 如果此處重設 currentLoad = selfWeight，該值會被寫入 NBT。
                // 區塊重載後，父節點仍記錄舊的累積載重，但子節點已被重設為 selfWeight，
                // 導致整棵支撐樹的載重加總不守恆（父 > 子之和）。
                //
                // 正確做法：保留 currentLoad 原值寫入 NBT，
                // 區塊重載時由 supportParent 的 NBT 恢復自動重建支撐鏈，
                // 載重在 onBlockPlaced 的級聯重算中自然修正。
                rbe.setSupportParent(null);
                // 不呼叫 setCurrentLoad — 保留 NBT 原值
                cleaned++;
            }
        }

        if (cleaned > 0) {
            LOGGER.debug("[LoadPath] Chunk [{}, {}] unload: cleared {} support chains (load preserved in NBT)",
                chunkPos.x, chunkPos.z, cleaned);
        }
    }

    // ═══════════════════════════════════════════════════════
    //  診斷工具
    // ═══════════════════════════════════════════════════════

    /**
     * 追蹤某方塊的完整支撐鏈 — 從它一路到地基/錨定點。
     * 用於 /br_load trace 指令。
     *
     * @return 支撐鏈上所有方塊位置（從 pos 到根），空 = 無支撐
     */
    public static List<BlockPos> traceLoadPath(ServerLevel level, BlockPos pos) {
        List<BlockPos> path = new ArrayList<>();
        // ★ Round 5 fix: 使用 HashSet 做 O(1) 環路偵測，取代 O(n) List.contains()
        java.util.Set<BlockPos> visited = new java.util.HashSet<>();
        path.add(pos);
        visited.add(pos);

        BlockPos current = pos;
        int maxDepth = BRConfig.INSTANCE.anchorBfsMaxDepth.get();
        int steps = 0;

        while (steps < maxDepth) {
            BlockEntity be = level.getBlockEntity(current);
            if (!(be instanceof RBlockEntity rbe)) break;

            if (rbe.isAnchored()) break; // 到達錨定點

            BlockPos parent = rbe.getSupportParent();
            if (parent == null) break; // 斷鏈
            if (!visited.add(parent)) {
                LOGGER.error("[LoadPath] CYCLE DETECTED in support tree at {}!", parent);
                break;
            }

            path.add(parent);
            current = parent;
            steps++;
        }

        return path;
    }
}
