package com.blockreality.api.physics;

import net.minecraft.core.BlockPos;

/**
 * 應力引擎介面。
 *
 * 接受快照 + 外力施加點，輸出應力場。
 * 預設實作：加權 BFS 距離衰減（偽 SPH）
 * 未來可換：真實 SPH 粒子法 / FEM
 *
 * 實作：SPHStressEngine（CompletableFuture 異步版本）
 *
 * @since 1.0.0
 */
public interface IStressEngine {
    /**
     * 計算以 eventPos 為中心的應力場。
     * @param snapshot  唯讀快照
     * @param eventPos  外力事件位置（爆炸中心、重壓點等）
     * @param radius    影響半徑（格數）
     * @return 應力場（每個方塊的應力值 0.0~2.0，≥1.0=損傷）
     */
    StressField solve(RWorldSnapshot snapshot, BlockPos eventPos, float radius);
}
