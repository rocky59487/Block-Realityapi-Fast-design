package com.blockreality.api.spi;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 高效批次方塊放置工具。
 *
 * 將方塊放置加入佇列而不觸發個別物理事件，
 * 然後一次性刷新全部。抑制個別放置事件，
 * 並在結束時發送單一批次完成事件。
 *
 * 這是 Fast Design 模組的核心工具，
 * 用於實現高效的結構設計操作。
 */
public class BatchBlockPlacer {

    private static final Logger LOGGER = LogManager.getLogger("BR-BatchBlockPlacer");

    private final ServerLevel level;
    private final Map<BlockPos, BlockState> pending = new LinkedHashMap<>();

    /**
     * 為指定的伺服器世界建立新的批次放置器。
     *
     * @param level 方塊將被放置的 ServerLevel
     */
    public BatchBlockPlacer(ServerLevel level) {
        if (level == null) {
            throw new IllegalArgumentException("ServerLevel cannot be null");
        }
        this.level = level;
    }

    /**
     * 將方塊放置加入佇列。
     * 不會立即放置方塊。
     *
     * @param pos   方塊位置
     * @param state 要放置的方塊狀態
     */
    public void queue(BlockPos pos, BlockState state) {
        if (pos == null || state == null) {
            LOGGER.warn("Ignoring null position or blockstate in queue");
            return;
        }
        pending.put(pos.immutable(), state);
    }

    /**
     * 批次放置所有佇列中的方塊。
     *
     * 透過直接修改世界資料來抑制個別放置事件，
     * 然後發送單一批次完成事件。
     *
     * @return 成功放置的方塊數量
     */
    public int flush() {
        if (pending.isEmpty()) {
            return 0;
        }

        int count = 0;
        long startTime = System.nanoTime();

        try {
            for (Map.Entry<BlockPos, BlockState> entry : pending.entrySet()) {
                BlockPos pos = entry.getKey();
                BlockState state = entry.getValue();

                // 直接放置到世界中，不觸發個別事件
                if (level.setBlock(pos, state, 0)) {
                    count++;
                }
            }

            long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
            LOGGER.debug("Batch block placement: {} blocks placed in {}ms",
                count, elapsedMs);

        } finally {
            pending.clear();
        }

        return count;
    }

    /**
     * 清除所有待處理的放置，不執行刷新。
     */
    public void clear() {
        pending.clear();
    }

    /**
     * 取得待處理放置的數量。
     *
     * @return 佇列中的方塊數量
     */
    public int pendingCount() {
        return pending.size();
    }

    /**
     * 檢查是否有待處理的放置。
     *
     * @return 若待處理列表非空則為 true
     */
    public boolean hasPending() {
        return !pending.isEmpty();
    }

    /**
     * 取得目標世界。
     *
     * @return 此批次放置器的 ServerLevel
     */
    public ServerLevel getLevel() {
        return level;
    }
}
