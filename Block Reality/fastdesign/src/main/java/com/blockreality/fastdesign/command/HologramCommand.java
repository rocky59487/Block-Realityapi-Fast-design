package com.blockreality.fastdesign.command;

import com.blockreality.api.blueprint.Blueprint;
import com.blockreality.api.blueprint.BlueprintIO;
import com.blockreality.api.blueprint.BlueprintNBT;
import com.blockreality.fastdesign.network.FdNetwork;
import com.blockreality.fastdesign.network.HologramSyncPacket;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

import java.io.IOException;

/**
 * 全息投影指令 — v3fix §3.1
 */
public class HologramCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // 保留空殼供 FastDesignMod 統一呼叫，實際子樹由 buildHologramNode() 提供
    }

    public static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> buildHologramNode() {
        return Commands.literal("hologram")

                .then(Commands.literal("load")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> loadHologram(ctx.getSource(),
                            StringArgumentType.getString(ctx, "name")))))

                .then(Commands.literal("clear")
                    .executes(ctx -> clearHologram(ctx.getSource())))

                .then(Commands.literal("move")
                    .then(Commands.argument("dx", IntegerArgumentType.integer(-64, 64))
                        .then(Commands.argument("dy", IntegerArgumentType.integer(-64, 64))
                            .then(Commands.argument("dz", IntegerArgumentType.integer(-64, 64))
                                .executes(ctx -> moveHologram(ctx.getSource(),
                                    IntegerArgumentType.getInteger(ctx, "dx"),
                                    IntegerArgumentType.getInteger(ctx, "dy"),
                                    IntegerArgumentType.getInteger(ctx, "dz")))))))

                .then(Commands.literal("rotate")
                    .executes(ctx -> rotateHologram(ctx.getSource())));
    }

    private static int loadHologram(CommandSourceStack src, String name) {
        ServerPlayer player = src.getPlayer();
        if (player == null) return fail(src, "Requires a player");

        try {
            Blueprint bp = BlueprintIO.load(name);
            CompoundTag bpTag = BlueprintNBT.write(bp);

            HologramSyncPacket packet = HologramSyncPacket.load(bpTag,
                player.blockPosition().getX(),
                player.blockPosition().getY(),
                player.blockPosition().getZ());

            FdNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                packet
            );

            src.sendSuccess(() -> Component.literal(String.format(
                "§6[FD] §fHologram '§a%s§f' projected (%d blocks)",
                name, bp.getBlockCount()
            )), false);
            return 1;

        } catch (IOException e) {
            return fail(src, "Failed to load hologram: " + e.getMessage());
        }
    }

    private static int clearHologram(CommandSourceStack src) {
        ServerPlayer player = src.getPlayer();
        if (player == null) return fail(src, "Requires a player");

        FdNetwork.CHANNEL.send(
            PacketDistributor.PLAYER.with(() -> player),
            HologramSyncPacket.clear()
        );

        src.sendSuccess(() -> Component.literal("§6[FD] §fHologram cleared."), false);
        return 1;
    }

    private static int moveHologram(CommandSourceStack src, int dx, int dy, int dz) {
        ServerPlayer player = src.getPlayer();
        if (player == null) return fail(src, "Requires a player");

        FdNetwork.CHANNEL.send(
            PacketDistributor.PLAYER.with(() -> player),
            HologramSyncPacket.move(dx, dy, dz)
        );

        src.sendSuccess(() -> Component.literal(String.format(
            "§6[FD] §fHologram moved by (%d, %d, %d)", dx, dy, dz
        )), false);
        return 1;
    }

    private static int rotateHologram(CommandSourceStack src) {
        ServerPlayer player = src.getPlayer();
        if (player == null) return fail(src, "Requires a player");

        FdNetwork.CHANNEL.send(
            PacketDistributor.PLAYER.with(() -> player),
            HologramSyncPacket.rotate()
        );

        src.sendSuccess(() -> Component.literal("§6[FD] §fHologram rotated 90°."), false);
        return 1;
    }

    private static int fail(CommandSourceStack src, String msg) {
        src.sendFailure(Component.literal("§c[FD] " + msg));
        return 0;
    }
}
