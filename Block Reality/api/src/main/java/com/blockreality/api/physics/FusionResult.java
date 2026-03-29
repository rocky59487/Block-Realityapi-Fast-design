package com.blockreality.api.physics;

import com.blockreality.api.material.RMaterial;
import net.minecraft.core.BlockPos;
import java.util.Map;
import java.util.Set;

/**
 * RC 融合偵測輸出。
 *
 * @param upgradedBlocks  pos → 融合後的材料
 * @param honeycombBlocks 發生蜂窩缺陷的方塊位置
 */
public record FusionResult(
    Map<BlockPos, RMaterial> upgradedBlocks,
    Set<BlockPos> honeycombBlocks
) {
    public int fusionCount() { return upgradedBlocks.size(); }
}
