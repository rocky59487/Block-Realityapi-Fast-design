package com.blockreality.api.physics;

import net.minecraft.core.BlockPos;
import java.util.Set;

/**
 * 錨定連通性檢查輸出。
 *
 * @param anchoredPositions 有錨定路徑的方塊位置
 * @param orphanPositions   無錨定路徑的孤立方塊（候選崩塌）
 */
public record AnchorResult(
    Set<BlockPos> anchoredPositions,
    Set<BlockPos> orphanPositions
) {
    public boolean isAnchored(BlockPos pos) { return anchoredPositions.contains(pos); }
    public int orphanCount() { return orphanPositions.size(); }
}
