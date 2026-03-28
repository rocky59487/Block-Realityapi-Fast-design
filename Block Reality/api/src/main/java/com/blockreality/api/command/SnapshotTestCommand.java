package com.blockreality.api.command;

import com.blockreality.api.physics.RBlockState;
import com.blockreality.api.physics.RWorldSnapshot;
import com.blockreality.api.physics.SnapshotBuilder;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * /br_test_snapshot [size]
 *
 * 以玩家為中心擷取 size×size×size 的快照，
 * 顯示耗時與非空氣方塊數，並在背景執行緒驗證讀取安全性。
 */
public class SnapshotTestCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("br_test_snapshot")
                .requires(source -> source.hasPermission(2)) // OP only
                .then(Commands.argument("size", IntegerArgumentType.integer(1, 40))
                    .executes(ctx -> {
                        int size = IntegerArgumentType.getInteger(ctx, "size");
                        return execute(ctx.getSource(), size);
                    })
                )
                .executes(ctx -> execute(ctx.getSource(), 32)) // 預設 32
        );
    }

    private static int execute(CommandSourceStack source, int size) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("[BR] 需要以玩家身份執行"));
            return 0;
        }

        ServerLevel level = source.getLevel();
        BlockPos center = player.blockPosition();

        int half = size / 2;
        BlockPos start = center.offset(-half, -half, -half);
        BlockPos end = center.offset(half - 1, half - 1, half - 1);

        try {
            // ─── 主執行緒擷取快照 ───
            RWorldSnapshot snapshot = SnapshotBuilder.capture(level, start, end);

            // 統計非空氣方塊數
            int nonAir = 0;
            for (int x = snapshot.getStartX(); x < snapshot.getStartX() + snapshot.getSizeX(); x++) {
                for (int y = snapshot.getStartY(); y < snapshot.getStartY() + snapshot.getSizeY(); y++) {
                    for (int z = snapshot.getStartZ(); z < snapshot.getStartZ() + snapshot.getSizeZ(); z++) {
                        if (snapshot.getBlock(x, y, z) != RBlockState.AIR) {
                            nonAir++;
                        }
                    }
                }
            }

            double ms = snapshot.getCaptureTimeMs();
            String msg = String.format(
                "[BR Snapshot] %dx%dx%d captured in %.2fms | %d/%d non-air blocks",
                snapshot.getSizeX(), snapshot.getSizeY(), snapshot.getSizeZ(),
                ms, nonAir, snapshot.getTotalBlocks()
            );
            source.sendSuccess(() -> Component.literal(msg), true);

            // ─── 背景執行緒安全性驗證 ───
            Thread verifier = new Thread(() -> {
                try {
                    int readCount = 0;
                    for (int x = snapshot.getStartX(); x < snapshot.getStartX() + snapshot.getSizeX(); x++) {
                        for (int y = snapshot.getStartY(); y < snapshot.getStartY() + snapshot.getSizeY(); y++) {
                            for (int z = snapshot.getStartZ(); z < snapshot.getStartZ() + snapshot.getSizeZ(); z++) {
                                RBlockState state = snapshot.getBlock(x, y, z);
                                if (state != null) readCount++;
                            }
                        }
                    }
                    final int finalCount = readCount;
                    source.sendSuccess(() -> Component.literal(
                        String.format("[BR Snapshot] Background thread read %d blocks — no ConcurrentModificationException", finalCount)
                    ), false);
                } catch (RuntimeException e) {
                    source.sendFailure(Component.literal("[BR Snapshot] Background thread FAILED: " + e.getMessage()));
                }
            }, "BR-Snapshot-Verifier");
            verifier.setDaemon(true);
            verifier.start();

            return 1;
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal("[BR] " + e.getMessage()));
            return 0;
        }
    }
}
