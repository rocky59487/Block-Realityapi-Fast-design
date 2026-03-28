package com.blockreality.fastdesign.command;

import com.blockreality.api.blueprint.Blueprint;
import com.blockreality.api.blueprint.BlueprintIO;
import com.blockreality.api.command.PlayerSelectionManager;
import com.blockreality.fastdesign.config.FastDesignConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * 藍圖指令 — v3fix §2.3
 */
public class BlueprintCommand {

    private static final Logger LOGGER = LogManager.getLogger("FD-Blueprint");

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("br_blueprint")
            .requires(src -> src.hasPermission(2))

            .then(Commands.literal("pos1")
                .executes(ctx -> setPos1(ctx.getSource())))

            .then(Commands.literal("pos2")
                .executes(ctx -> setPos2(ctx.getSource())))

            .then(Commands.literal("save")
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(ctx -> saveBlueprint(ctx.getSource(),
                        StringArgumentType.getString(ctx, "name")))))

            .then(Commands.literal("load")
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(ctx -> loadBlueprint(ctx.getSource(),
                        StringArgumentType.getString(ctx, "name")))))

            .then(Commands.literal("list")
                .executes(ctx -> listBlueprints(ctx.getSource())))

            .then(Commands.literal("delete")
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(ctx -> deleteBlueprint(ctx.getSource(),
                        StringArgumentType.getString(ctx, "name")))))
        );
    }

    private static int setPos1(CommandSourceStack src) {
        ServerPlayer player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.literal("§c[FD] This command requires a player"));
            return 0;
        }
        BlockPos pos = player.blockPosition();
        PlayerSelectionManager.setPos1(player.getUUID(), pos);
        src.sendSuccess(() -> Component.literal(
            String.format("§6[FD] §fPos1 set to §a(%d, %d, %d)", pos.getX(), pos.getY(), pos.getZ())
        ), false);
        return 1;
    }

    private static int setPos2(CommandSourceStack src) {
        ServerPlayer player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.literal("§c[FD] This command requires a player"));
            return 0;
        }
        BlockPos pos = player.blockPosition();
        PlayerSelectionManager.setPos2(player.getUUID(), pos);
        src.sendSuccess(() -> Component.literal(
            String.format("§6[FD] §fPos2 set to §a(%d, %d, %d)", pos.getX(), pos.getY(), pos.getZ())
        ), false);
        return 1;
    }

    private static int saveBlueprint(CommandSourceStack src, String name) {
        ServerPlayer player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.literal("§c[FD] This command requires a player"));
            return 0;
        }

        UUID uuid = player.getUUID();
        if (!PlayerSelectionManager.hasSelection(uuid)) {
            src.sendFailure(Component.literal("§c[FD] Set pos1 and pos2 first!"));
            return 0;
        }

        var box = PlayerSelectionManager.getSelection(uuid);
        BlockPos min = box.min();
        BlockPos max = box.max();

        int maxVol = FastDesignConfig.getMaxSelectionVolume();
        if (box.volume() > maxVol) {
            src.sendFailure(Component.literal(
                "§c[FD] Selection too large! Max " + maxVol + " blocks, got " + box.volume()));
            return 0;
        }

        try {
            ServerLevel level = (ServerLevel) player.level();
            String author = player.getName().getString();
            BlueprintIO.save(level, min, max, name, author);

            src.sendSuccess(() -> Component.literal(
                String.format("§6[FD] §fBlueprint '§a%s§f' saved — %dx%dx%d",
                    name,
                    max.getX() - min.getX() + 1,
                    max.getY() - min.getY() + 1,
                    max.getZ() - min.getZ() + 1)
            ), true);
            return 1;
        } catch (IOException e) {
            LOGGER.error("[Blueprint] Save failed", e);
            src.sendFailure(Component.literal("§c[FD] Save failed: " + e.getMessage()));
            return 0;
        }
    }

    private static int loadBlueprint(CommandSourceStack src, String name) {
        ServerPlayer player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.literal("§c[FD] This command requires a player"));
            return 0;
        }

        try {
            Blueprint bp = BlueprintIO.load(name);
            ServerLevel level = (ServerLevel) player.level();
            BlockPos origin = player.blockPosition();
            int placed = BlueprintIO.paste(level, bp, origin);

            src.sendSuccess(() -> Component.literal(
                String.format("§6[FD] §fBlueprint '§a%s§f' pasted at (%d, %d, %d) — %d blocks",
                    name, origin.getX(), origin.getY(), origin.getZ(), placed)
            ), true);
            return 1;
        } catch (IOException e) {
            LOGGER.error("[Blueprint] Load failed", e);
            src.sendFailure(Component.literal("§c[FD] Load failed: " + e.getMessage()));
            return 0;
        }
    }

    private static int listBlueprints(CommandSourceStack src) {
        try {
            List<String> names = BlueprintIO.listBlueprints();
            if (names.isEmpty()) {
                src.sendSuccess(() -> Component.literal("§6[FD] §7No blueprints saved yet."), false);
            } else {
                src.sendSuccess(() -> Component.literal(
                    "§6[FD] §fBlueprints (" + names.size() + "):"
                ), false);
                for (String name : names) {
                    src.sendSuccess(() -> Component.literal("  §a• §f" + name), false);
                }
            }
            return 1;
        } catch (IOException e) {
            src.sendFailure(Component.literal("§c[FD] List failed: " + e.getMessage()));
            return 0;
        }
    }

    private static int deleteBlueprint(CommandSourceStack src, String name) {
        try {
            boolean deleted = BlueprintIO.delete(name);
            if (deleted) {
                src.sendSuccess(() -> Component.literal(
                    "§6[FD] §fBlueprint '§c" + name + "§f' deleted."
                ), true);
            } else {
                src.sendFailure(Component.literal("§c[FD] Blueprint '" + name + "' not found."));
            }
            return deleted ? 1 : 0;
        } catch (IOException e) {
            src.sendFailure(Component.literal("§c[FD] Delete failed: " + e.getMessage()));
            return 0;
        }
    }
}
