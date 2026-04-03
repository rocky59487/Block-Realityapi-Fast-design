package com.blockreality.api.spi;

import com.blockreality.api.block.RBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.List;

/**
 * 載重傳導路徑管理介面 (Load Path Manager SPI)
 *
 * Load Path Engine (LoadPathEngine) — Manages structural load transmission through block hierarchies.
 *
 * 核心責任 (Core Responsibilities):
 *   - 追蹤結構載重分配（每個方塊記住自己的支撐點）
 *   - 當方塊被放置或破壞時，更新載重路徑
 *   - 級聯崩塌判定（支撐被破壞後的連鎖反應）
 *   - 診斷與追蹤支撐鏈
 *
 * 線程安全 (Thread Safety):
 *   Method calls are thread-safe for concurrent access from game and physics engine threads.
 *   All operations are localized to the affected load path only (no global scans).
 *
 * 設計模式 (Design Pattern):
 *   支撐樹 (Support Tree) — 每個方塊記住「我的重量傳給誰 (Parent)」，形成一棵往下長的樹。
 *   效能：O(H) 放置，O(K) 破壞，H = 層高，K = 受影響的依賴者數量。
 *
 * @since 1.0.0
 */
public interface ILoadPathManager {

    /**
     * 當 RBlock 被放置時呼叫 — 確立支撐點並傳遞自重。
     *
     * Flow (執行流程):
     *   1. 計算自重 (density × 1m³)
     *   2. 在鄰居中找最佳支撐者 (findBestSupport)
     *   3. 設定 supportParent
     *   4. 沿傳導路徑往下傳遞載重 (propagateLoadDown)
     *   5. 檢查路徑上是否有方塊被壓碎
     *
     * @param level 伺服器世界 (ServerLevel) / Server world
     * @param pos 方塊位置 (BlockPos) / Block position to place
     * @return true 如果成功找到支撐 / true if support was found and established
     *         false 如果方塊懸空（找不到支撐且非錨定點） / false if block is unsupported and will fall
     */
    boolean onBlockPlaced(ServerLevel level, BlockPos pos);

    /**
     * 當 RBlock 被破壞時呼叫 — 移除載重並檢查級聯崩塌。
     *
     * Flow (執行流程):
     *   1. 從 parent 移除此方塊的總載重
     *   2. 收集所有依賴此方塊的孤兒方塊
     *   3. 每個孤兒嘗試找新的支撐者（重新連接）
     *   4. 找不到新支撐的孤兒 → 級聯崩塌
     *
     * @param level 伺服器世界 / Server world
     * @param brokenPos 被破壞的方塊位置 / Position of block being broken
     * @return 崩塌的方塊數量 / Number of blocks that collapsed in cascade
     */
    int onBlockBroken(ServerLevel level, BlockPos brokenPos);

    /**
     * 修復版的破壞事件處理 — 使用事先快取的 BE 資料。
     *
     * 此版本用於避免 BUG-2：在 server.execute() 後，BlockEntity 可能已被移除，
     * 所以必須事先快取 supportParent 和 currentLoad。
     *
     * Flow (執行流程):
     *   Same as onBlockBroken, but uses cached parameters instead of reading from deleted BlockEntity.
     *
     * @param level 伺服器世界 / Server world
     * @param brokenPos 被破壞的方塊位置 / Position of block being broken
     * @param cachedParent 破壞前讀取的支撐點（可 null） / Cached supportParent before block was broken (nullable)
     * @param cachedLoad 破壞前讀取的總載重 / Cached currentLoad before block was broken
     * @return 崩塌的方塊數量 / Number of blocks that collapsed in cascade
     */
    int onBlockBrokenCached(ServerLevel level, BlockPos brokenPos, BlockPos cachedParent, float cachedLoad);

    /**
     * 在 6 個鄰居中找到最佳支撐者。
     *
     * 優先級 (Priority):
     *   1. 正下方有方塊 → 直接壓力傳遞（最自然）
     *   2. 側向有高 Rtens 的方塊 → 懸臂/側撐（需要抗拉材料）
     *   3. 上方 → 懸吊結構（特殊情況）
     *
     * 條件 (Criteria):
     *   - 候選者必須自己有支撐 (hasSupport) 或是錨定點 / Candidate must have its own support or be anchored
     *   - 側向支撐需要候選者的 Rtens > 0（抗拉才能水平傳力） / Lateral support requires Rtens > 0
     *   - 不會形成環路（候選者的 parent chain 不包含自己） / Cannot create cycles in support tree
     *
     * @param level 伺服器世界 / Server world
     * @param pos 方塊位置 / Block position looking for support
     * @param self RBlockEntity 參考 / Reference to the RBlockEntity being placed
     * @return 最佳支撐者的位置，null = 找不到任何支撐 / Position of best support, or null if none found
     */
    BlockPos findBestSupport(ServerLevel level, BlockPos pos, RBlockEntity self);

    /**
     * 沿支撐樹向下傳遞載重變化。
     *
     * 此方法追蹤支撐鏈（parent → parent's parent → ...）並累積載重，
     * 同時檢查每層是否被壓碎，並觸發相應事件。
     *
     * 線程安全 (Thread Safety):
     *   All load propagation is localized to the support path only.
     *   No global scanning or locking required.
     *
     * @param level 伺服器世界 / Server world
     * @param startPos 開始傳遞的位置 / Position where propagation starts
     * @param delta 載重變化量 / Load change (positive = add load, negative = remove load)
     *              正數 = 增加載重，負數 = 減少載重
     */
    void propagateLoadDown(ServerLevel level, BlockPos startPos, float delta);

    /**
     * 追蹤某方塊的完整支撐鏈 — 從它一路到地基/錨定點。
     *
     * 用於診斷和調試。This traces the entire support hierarchy from a block
     * down to bedrock, a barrier block, or an anchored pile.
     *
     * 用途 (Usage):
     *   - /br_load trace 指令使用
     *   - 除錯支撐結構
     *   - 查詢載重路徑
     *
     * @param level 伺服器世界 / Server world
     * @param pos 起始方塊位置 / Starting block position
     * @return 支撐鏈上所有方塊位置（從 pos 到根），空 = 無支撐
     *         List of all positions in the support chain from pos to the anchor,
     *         empty list if no support chain exists
     */
    List<BlockPos> traceLoadPath(ServerLevel level, BlockPos pos);
}
