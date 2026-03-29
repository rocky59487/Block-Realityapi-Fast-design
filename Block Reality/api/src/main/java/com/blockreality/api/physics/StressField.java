package com.blockreality.api.physics;

import net.minecraft.core.BlockPos;
import java.util.Map;
import java.util.Set;

/**
 * 應力場輸出。
 *
 * @param stressValues  pos → 應力值 (0.0~2.0)，≥1.0 表示損傷
 * @param damagedBlocks 損傷方塊（stressLevel ≥ 1.0）
 */
public record StressField(
    Map<BlockPos, Float> stressValues,
    Set<BlockPos> damagedBlocks
) {
    // ★ M-1 fix: 防禦性複製 — 防止呼叫者修改 record 內部狀態
    public StressField {
        stressValues = Map.copyOf(stressValues);
        damagedBlocks = Set.copyOf(damagedBlocks);
    }

    public float getStress(BlockPos pos) {
        return stressValues.getOrDefault(pos, 0f);
    }
    public boolean isDamaged(BlockPos pos) { return damagedBlocks.contains(pos); }
}
