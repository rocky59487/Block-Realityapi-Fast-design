package com.blockreality.fastdesign.command;

import com.blockreality.api.command.PlayerSelectionManager;
import com.blockreality.api.construction.ConstructionPhase;
import com.blockreality.api.construction.ConstructionZone;
import com.blockreality.api.construction.ConstructionZoneManager;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.UUID;

/**
 * 施工區域指令 — v3fix §3.2
 */
public class ConstructionCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("br_zone")
            .requires(src -> src.hasPermission(2))

            .then(Commands.literal("pos1")
                .executes(ctx -> setPos(ctx.getSource(), true)))

            .then(Commands.literal("pos2")
                .executes(ctx -> setPos(ctx.getSource(), false)))

            .then(Commands.literal("create")
                .executes(ctx -> createZone(ctx.getSource())))

            .then(Commands.literal("advance")
                .executes(ctx -> advanceZone(ctx.getSource())))

            .then(Commands.literal("info")
                .executes(ctx -> zoneInfo(ctx.getSource())))

            .then(Commands.literal("list")
                .executes(ctx -> listZones(ctx.getSource())))

            .then(Commands.literal("remove")
                .executes(ctx -> removeZone(ctx.getSource())))
        );
    }

    private static int setPos(CommandSourceStack src, boolean isPos1) {
        ServerPlayer player = src.getPlayer();
        if (player == null) return fail(src, "Requires a player");

        BlockPos pos = player.blockPosition();
        if (isPos1) {
            PlayerSelectionManager.setPos1(player.getUUID(), pos);
        } else {
            PlayerSelectionManager.setPos2(player.getUUID(), pos);
        }
        String label = isPos1 ? "Pos1" : "Pos2";
        src.sendSuccess(() -> Component.literal(String.format(
            "§6[FD-CI] §f%s set to §a(%d, %d, %d)", label, pos.getX(), pos.getY(), pos.getZ()
        )), false);
        return 1;
    }

    private static int createZone(CommandSourceStack src) {
        ServerPlayer player = src.getPlayer();
        if (player == null) return fail(src, "Requires a player");

        if (!PlayerSelectionManager.hasSelection(player.getUUID())) {
            return fail(src, "Set pos1 and pos2 first!");
        }
        var box = PlayerSelectionManager.getSelection(player.getUUID());
        BlockPos min = box.min();
        BlockPos max = box.max();

        ServerLevel level = (ServerLevel) player.level();
        ConstructionZoneManager manager = ConstructionZoneManager.get(level);
        ConstructionZone zone = manager.createZone(min, max, player.getName().getString());

        src.sendSuccess(() -> Component.literal(String.format(
            "§6[FD-CI] §a施工區域已建立！\n§fID: §7%s\n§f範圍: (%d,%d,%d) → (%d,%d,%d)\n§f當前工序: §e%s",
            zone.getZoneId().toString().substring(0, 8),
            min.getX(), min.getY(), min.getZ(),
            max.getX(), max.getY(), max.getZ(),
            zone.getCurrentPhase().getDisplayNameZh()
        )), true);
        return 1;
    }

    private static int advanceZone(CommandSourceStack src) {
        ServerPlayer player = src.getPlayer();
        if (player == null) return fail(src, "Requires a player");

        ServerLevel level = (ServerLevel) player.level();
        ConstructionZoneManager manager = ConstructionZoneManager.get(level);
        ConstructionZone zone = manager.getZoneAt(player.blockPosition());

        if (zone == null) return fail(src, "You are not inside a construction zone!");

        return zone.advance(level, player) ? 1 : 0;
    }

    private static int zoneInfo(CommandSourceStack src) {
        ServerPlayer player = src.getPlayer();
        if (player == null) return fail(src, "Requires a player");

        ServerLevel level = (ServerLevel) player.level();
        ConstructionZoneManager manager = ConstructionZoneManager.get(level);
        ConstructionZone zone = manager.getZoneAt(player.blockPosition());

        if (zone == null) return fail(src, "You are not inside a construction zone!");

        src.sendSuccess(() -> Component.literal(String.format(
            "§6[FD-CI] 施工區域資訊\n" +
            "§fID: §7%s\n" +
            "§f建立者: §7%s\n" +
            "§f範圍: (%d,%d,%d) → (%d,%d,%d) [體積: %d]\n" +
            "§f當前工序: §e%s\n" +
            "§f本階段已放置: §a%d §f方塊",
            zone.getZoneId().toString().substring(0, 8),
            zone.getCreatorName(),
            zone.getMin().getX(), zone.getMin().getY(), zone.getMin().getZ(),
            zone.getMax().getX(), zone.getMax().getY(), zone.getMax().getZ(),
            zone.volume(),
            zone.getCurrentPhase().getDisplayNameZh(),
            zone.getBlocksPlacedInPhase()
        )), false);
        return 1;
    }

    private static int listZones(CommandSourceStack src) {
        ServerPlayer player = src.getPlayer();
        if (player == null) return fail(src, "Requires a player");

        ServerLevel level = (ServerLevel) player.level();
        ConstructionZoneManager manager = ConstructionZoneManager.get(level);
        Collection<ConstructionZone> zones = manager.getAllZones();

        if (zones.isEmpty()) {
            src.sendSuccess(() -> Component.literal("§6[FD-CI] §7No construction zones."), false);
            return 1;
        }

        src.sendSuccess(() -> Component.literal(
            "§6[FD-CI] §f施工區域 (" + zones.size() + "):"
        ), false);
        for (ConstructionZone zone : zones) {
            src.sendSuccess(() -> Component.literal(String.format(
                "  §a• §f%s §7(%s) §f— §e%s §7[%d blocks placed]",
                zone.getZoneId().toString().substring(0, 8),
                zone.getCreatorName(),
                zone.getCurrentPhase().getDisplayNameZh(),
                zone.getBlocksPlacedInPhase()
            )), false);
        }
        return 1;
    }

    private static int removeZone(CommandSourceStack src) {
        ServerPlayer player = src.getPlayer();
        if (player == null) return fail(src, "Requires a player");

        ServerLevel level = (ServerLevel) player.level();
        ConstructionZoneManager manager = ConstructionZoneManager.get(level);
        ConstructionZone zone = manager.getZoneAt(player.blockPosition());

        if (zone == null) return fail(src, "You are not inside a construction zone!");

        UUID id = zone.getZoneId();
        manager.removeZone(id);
        src.sendSuccess(() -> Component.literal(
            "§6[FD-CI] §c施工區域已移除：§7" + id.toString().substring(0, 8)
        ), true);
        return 1;
    }

    private static int fail(CommandSourceStack src, String msg) {
        src.sendFailure(Component.literal("§c[FD-CI] " + msg));
        return 0;
    }
}
