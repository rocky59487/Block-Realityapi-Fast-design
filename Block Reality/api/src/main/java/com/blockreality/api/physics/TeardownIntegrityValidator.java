package com.blockreality.api.physics;

import com.blockreality.api.block.RBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Issue#9 重構：從 BFSConnectivityAnalyzer 提取的 Teardown 式增量結構完整性檢查。
 *
 * <p>靈感來源：Teardown (Dennis Gustafsson)。
 * 核心概念：方塊被破壞時，不重新掃描整個快照，
 * 而是只檢查被移除方塊的鄰居是否仍然能到達錨定點。
 *
 * <p>效能優勢：O(K) 而非 O(N)，K = 受影響的連通分量大小。
 *
 * @see BFSConnectivityAnalyzer
 */
public class TeardownIntegrityValidator {

    private static final Logger LOGGER = LogManager.getLogger("BlockReality/Physics");

    /**
     * Teardown 式增量結構完整性檢查 — 在方塊被破壞後呼叫。
     *
     * @param level    伺服器世界
     * @param removed  被移除方塊的位置
     * @return 需要坍塌的方塊集合（可能為空 = 結構仍完整）
     */
    public static Set<BlockPos> validateLocalIntegrity(ServerLevel level, BlockPos removed) {
        long t0 = System.nanoTime();

        int maxBlocks = BFSConnectivityAnalyzer.getStructureBfsMaxBlocks();
        long maxMs = BFSConnectivityAnalyzer.getStructureBfsMaxMs();

        // ─── Step 1: 收集受影響的 RBlock 鄰居 ───
        List<BlockPos> affectedNeighbors = new ArrayList<>();
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = removed.relative(dir);
            BlockEntity be = level.getBlockEntity(neighbor);
            if (be instanceof RBlockEntity) {
                affectedNeighbors.add(neighbor);
            }
        }

        if (affectedNeighbors.isEmpty()) {
            return Set.of();
        }

        // ─── Step 2: 對每個受影響鄰居，反向 BFS 尋找錨定點 ───
        Set<BlockPos> confirmed = new HashSet<>();
        Set<BlockPos> floating = new HashSet<>();

        for (BlockPos start : affectedNeighbors) {
            if (confirmed.contains(start) || floating.contains(start)) continue;

            Set<BlockPos> visited = new HashSet<>();
            Deque<BlockPos> queue = new ArrayDeque<>();
            queue.add(start);
            visited.add(start);

            boolean foundAnchor = false;
            long deadline = System.currentTimeMillis() + maxMs;
            int visitCount = 0;
            boolean budgetExhausted = false;

            while (!queue.isEmpty()) {
                if (visitCount >= maxBlocks) {
                    budgetExhausted = true;
                    break;
                }
                if ((visitCount & 0xFF) == 0 && System.currentTimeMillis() > deadline) {
                    budgetExhausted = true;
                    break;
                }

                BlockPos current = queue.poll();
                visitCount++;

                if (isAnchorBlock(level, current)) {
                    foundAnchor = true;
                    break;
                }

                if (confirmed.contains(current)) {
                    foundAnchor = true;
                    break;
                }

                for (Direction dir : Direction.values()) {
                    BlockPos neighbor = current.relative(dir);
                    if (visited.contains(neighbor)) continue;
                    if (neighbor.equals(removed)) continue;

                    BlockState state = level.getBlockState(neighbor);
                    if (state.isAir()) continue;

                    visited.add(neighbor);
                    BlockEntity be = level.getBlockEntity(neighbor);
                    if (be instanceof RBlockEntity) {
                        queue.add(neighbor);
                    } else if (!state.isAir()) {
                        foundAnchor = true;
                        break;
                    }
                }
                if (foundAnchor) break;
            }

            // ─── Step 3: 判定結果 ───
            if (foundAnchor || budgetExhausted) {
                confirmed.addAll(visited);
            } else {
                for (BlockPos pos : visited) {
                    BlockEntity be = level.getBlockEntity(pos);
                    if (be instanceof RBlockEntity) {
                        floating.add(pos);
                    }
                }
            }
        }

        long elapsed = System.nanoTime() - t0;
        if (!floating.isEmpty()) {
            LOGGER.info("[Teardown] Local integrity check at {}: {} floating blocks found in {}ms",
                removed.toShortString(), floating.size(), String.format("%.2f", elapsed / 1e6));
        } else {
            LOGGER.debug("[Teardown] Local integrity check at {}: structure intact ({}ms)",
                removed.toShortString(), String.format("%.2f", elapsed / 1e6));
        }

        return floating;
    }

    /**
     * 檢查方塊是否為錨定點。
     */
    static boolean isAnchorBlock(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);

        if (state.is(net.minecraft.world.level.block.Blocks.BEDROCK) ||
            state.is(net.minecraft.world.level.block.Blocks.BARRIER)) {
            return true;
        }

        if (pos.getY() <= level.getMinBuildHeight() + 1) {
            return true;
        }

        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof RBlockEntity rbe && rbe.isAnchored()) {
            return true;
        }

        return false;
    }

    private TeardownIntegrityValidator() {}
}
