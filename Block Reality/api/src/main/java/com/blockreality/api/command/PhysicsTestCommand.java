package com.blockreality.api.command;

import com.blockreality.api.physics.PhysicsExecutor;
import com.blockreality.api.physics.RWorldSnapshot;
import com.blockreality.api.physics.SnapshotBuilder;
import com.blockreality.api.physics.UnionFindEngine;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * /br_test_physics [size]            — 分析（含 scan margin）
 * /br_test_physics [size] collapse   — 分析 + 崩塌
 *
 * Scan Margin 機制：
 *   使用者指定 size=32 → 引擎自動掃描 40×40×40（margin=4）
 *   BFS 在 40³ 上跑，但只有內部 32³ 的方塊會被崩塌
 *   margin 區域捕捉外部支撐路徑，防止誤殺合理建築
 */
public class PhysicsTestCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("br_test_physics")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("size", IntegerArgumentType.integer(1, 40))
                    .then(Commands.literal("collapse")
                        .executes(ctx -> execute(
                            ctx.getSource(),
                            IntegerArgumentType.getInteger(ctx, "size"),
                            true
                        ))
                    )
                    .executes(ctx -> execute(
                        ctx.getSource(),
                        IntegerArgumentType.getInteger(ctx, "size"),
                        false
                    ))
                )
                .executes(ctx -> execute(ctx.getSource(), 32, false))
        );
    }

    private static int execute(CommandSourceStack source, int size, boolean doCollapse) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("[BR] 需要以玩家身份執行"));
            return 0;
        }

        ServerLevel level = source.getLevel();
        BlockPos center = player.blockPosition();

        // ─── Scan Margin 計算 ───
        // 掃描區 = size + 2*margin，不超過 MAX_SNAPSHOT_BLOCKS
        int margin = UnionFindEngine.DEFAULT_MARGIN;
        int scanSize = size + margin * 2;
        int maxScanEdge = (int) Math.cbrt(RWorldSnapshot.MAX_SNAPSHOT_BLOCKS);

        // 如果掃描區超過上限，縮減 margin
        if (scanSize > maxScanEdge) {
            margin = Math.max(0, (maxScanEdge - size) / 2);
            scanSize = size + margin * 2;
        }

        int scanHalf = scanSize / 2;
        BlockPos start = center.offset(-scanHalf, -scanHalf, -scanHalf);
        BlockPos end = center.offset(scanHalf - 1, scanHalf - 1, scanHalf - 1);

        final int effectiveMargin = margin;

        try {
            // ─── Step 1: 主執行緒擷取快照（含 margin 的掃描區） ───
            RWorldSnapshot snapshot = SnapshotBuilder.capture(level, start, end);

            final String snapMsg = String.format(
                "[BR] Scan %dx%dx%d (effect %dx%dx%d, margin=%d) captured in %.2fms",
                scanSize, scanSize, scanSize,
                size, size, size, effectiveMargin,
                snapshot.getCaptureTimeMs()
            );
            source.sendSuccess(() -> Component.literal(snapMsg), false);

            // ─── Step 2: 背景 BFS（在完整掃描區上跑，結果只含崩塌區） ───
            PhysicsExecutor.submit(snapshot, effectiveMargin).thenAccept(result -> {
                String resultMsg = String.format(
                    "[BR Physics] BFS done in %.2fms | anchors=%d, visited=%d, unsupported=%d/%d",
                    result.computeTimeMs(),
                    result.anchorCount(),
                    result.bfsVisited(),
                    result.unsupportedCount(),
                    result.totalNonAir()
                );
                source.sendSuccess(() -> Component.literal(resultMsg), true);

                if (result.timedOut()) {
                    source.sendFailure(Component.literal("[BR Physics] WARNING: BFS timed out!"));
                }

                // 列出前 10 個懸空方塊座標
                if (result.unsupportedCount() > 0) {
                    int shown = 0;
                    StringBuilder sb = new StringBuilder("[BR Physics] Unsupported: ");
                    for (BlockPos pos : result.unsupportedBlocks()) {
                        if (shown >= 10) {
                            sb.append(String.format("... +%d more", result.unsupportedCount() - 10));
                            break;
                        }
                        if (shown > 0) sb.append(", ");
                        sb.append(String.format("(%d,%d,%d)", pos.getX(), pos.getY(), pos.getZ()));
                        shown++;
                    }
                    final String blockList = sb.toString();
                    source.sendSuccess(() -> Component.literal(blockList), false);
                }

                // ─── Step 3 (BR-006): 崩塌 → 排回主執行緒 ───
                if (doCollapse && result.unsupportedCount() > 0) {
                    source.getServer().execute(() -> {
                        int collapsed = 0;
                        for (BlockPos pos : result.unsupportedBlocks()) {
                            BlockState state = level.getBlockState(pos);
                            if (!state.isAir()) {
                                FallingBlockEntity.fall(level, pos, state);
                                collapsed++;
                            }
                        }
                        final int c = collapsed;
                        source.sendSuccess(() -> Component.literal(
                            String.format("[BR Collapse] %d blocks → FallingBlockEntity", c)
                        ), true);
                    });
                }

            }).exceptionally(ex -> {
                source.sendFailure(Component.literal("[BR Physics] FAILED: " + ex.getMessage()));
                return null;
            });

            return 1;
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal("[BR] " + e.getMessage()));
            return 0;
        }
    }
}
