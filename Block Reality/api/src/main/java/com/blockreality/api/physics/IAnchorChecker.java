package com.blockreality.api.physics;

import net.minecraft.core.BlockPos;
import java.util.Set;

/**
 * 錨定連通性檢查器介面。
 *
 * 從結構圖出發，沿鋼筋路徑做 BFS，
 * 判斷每個節點是否能連通到錨定點。
 *
 * 實作：AnchorContinuityChecker（含 dirty flag 快取）
 *
 * @since 1.0.0
 */
public interface IAnchorChecker {
    /**
     * 檢查哪些方塊位置是錨定的（有路徑連通到地基/基岩）。
     * @param snapshot    唯讀快照
     * @param anchorSeeds 外部指定的錨定起點（基岩鄰接點等）
     * @return 錨定結果（anchoredPositions set + orphan set）
     */
    AnchorResult check(RWorldSnapshot snapshot, Set<BlockPos> anchorSeeds);
}
