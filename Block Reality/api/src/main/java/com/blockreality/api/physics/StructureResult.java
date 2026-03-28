package com.blockreality.api.physics;

import net.minecraft.core.BlockPos;
import java.util.Map;
import java.util.Set;

/**
 * 結構引擎輸出結果。
 *
 * @param unstableBlocks  失去支撐的方塊位置集合
 * @param stressMap       每個方塊的應力比 (0.0~1.0+)
 * @param elapsedMs       計算耗時
 */
public record StructureResult(
    Set<BlockPos> unstableBlocks,
    Map<BlockPos, Float> stressMap,
    double elapsedMs
) {
    public int unstableCount() { return unstableBlocks.size(); }
    public boolean isStable() { return unstableBlocks.isEmpty(); }
}
