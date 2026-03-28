package com.blockreality.api.command;

import com.blockreality.api.block.RBlockEntity;
import com.blockreality.api.physics.LoadPathEngine;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.List;

/**
 * /br_load [info|trace|rebuild]
 *
 * 載重傳導路徑引擎的診斷指令：
 *   info    — 查看準星指向方塊的載重資訊
 *   trace   — 追蹤完整支撐鏈（從方塊到地基）
 *   rebuild — 重建準星指向方塊的支撐關係
 */
public class LoadPathCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("br_load")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("info")
                    .executes(ctx -> showLoadInfo(ctx.getSource())))
                .then(Commands.literal("trace")
                    .executes(ctx -> traceLoadPath(ctx.getSource())))
                .then(Commands.literal("rebuild")
                    .executes(ctx -> rebuildSupport(ctx.getSource())))
                .executes(ctx -> showLoadInfo(ctx.getSource()))
        );
    }

    private static int showLoadInfo(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("[BR] 需要以玩家身份執行"));
            return 0;
        }

        HitResult hit = player.pick(5.0, 1.0f, false);
        if (hit.getType() != HitResult.Type.BLOCK) {
            source.sendFailure(Component.literal("[BR Load] 未指向任何方塊"));
            return 0;
        }

        BlockPos pos = ((BlockHitResult) hit).getBlockPos();
        ServerLevel level = source.getLevel();
        BlockEntity be = level.getBlockEntity(pos);

        if (be instanceof RBlockEntity rbe) {
            BlockPos parent = rbe.getSupportParent();
            String parentStr = parent != null
                ? String.format("(%d,%d,%d)", parent.getX(), parent.getY(), parent.getZ())
                : "NONE";

            String msg = String.format(
                "[BR Load] (%d,%d,%d)\n" +
                "  Material: %s | Self weight: %.0f kg\n" +
                "  Current load: %.0f kg\n" +
                "  Support parent: %s\n" +
                "  Anchored: %b | Stress: %.1f%%\n" +
                "  Rcomp capacity: %.0f kg",
                pos.getX(), pos.getY(), pos.getZ(),
                rbe.getMaterial().getMaterialId(), rbe.getSelfWeight(),
                rbe.getCurrentLoad(),
                parentStr,
                rbe.isAnchored(), rbe.getStressLevel() * 100f,
                rbe.getMaterial().getRcomp() * 1e6
            );
            source.sendSuccess(() -> Component.literal(msg), false);
        } else {
            source.sendSuccess(() -> Component.literal(
                String.format("[BR Load] (%d,%d,%d) 非 R-Block（無載重數據）",
                    pos.getX(), pos.getY(), pos.getZ())
            ), false);
        }
        return 1;
    }

    private static int traceLoadPath(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("[BR] 需要以玩家身份執行"));
            return 0;
        }

        HitResult hit = player.pick(5.0, 1.0f, false);
        if (hit.getType() != HitResult.Type.BLOCK) {
            source.sendFailure(Component.literal("[BR Load] 未指向任何方塊"));
            return 0;
        }

        BlockPos pos = ((BlockHitResult) hit).getBlockPos();
        ServerLevel level = source.getLevel();

        List<BlockPos> path = LoadPathEngine.traceLoadPath(level, pos);

        StringBuilder sb = new StringBuilder("[BR Load Path] ");
        sb.append(path.size()).append(" nodes:\n");

        for (int i = 0; i < path.size(); i++) {
            BlockPos p = path.get(i);
            BlockEntity be = level.getBlockEntity(p);
            String info;
            if (be instanceof RBlockEntity rbe) {
                info = String.format("  %d. (%d,%d,%d) %s load=%.0fkg %s",
                    i, p.getX(), p.getY(), p.getZ(),
                    rbe.getMaterial().getMaterialId(),
                    rbe.getCurrentLoad(),
                    rbe.isAnchored() ? "[ANCHOR]" : "");
            } else {
                info = String.format("  %d. (%d,%d,%d) [vanilla/ground]", i, p.getX(), p.getY(), p.getZ());
            }
            sb.append(info).append("\n");
        }

        final String result = sb.toString();
        source.sendSuccess(() -> Component.literal(result), false);
        return 1;
    }

    private static int rebuildSupport(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("[BR] 需要以玩家身份執行"));
            return 0;
        }

        HitResult hit = player.pick(5.0, 1.0f, false);
        if (hit.getType() != HitResult.Type.BLOCK) {
            source.sendFailure(Component.literal("[BR Load] 未指向任何方塊"));
            return 0;
        }

        BlockPos pos = ((BlockHitResult) hit).getBlockPos();
        ServerLevel level = source.getLevel();

        boolean success = LoadPathEngine.onBlockPlaced(level, pos);
        if (success) {
            source.sendSuccess(() -> Component.literal(
                String.format("[BR Load] 已重建 (%d,%d,%d) 的支撐關係",
                    pos.getX(), pos.getY(), pos.getZ())
            ), true);
        } else {
            source.sendFailure(Component.literal(
                String.format("[BR Load] (%d,%d,%d) 無法找到支撐！",
                    pos.getX(), pos.getY(), pos.getZ())
            ));
        }
        return success ? 1 : 0;
    }
}
