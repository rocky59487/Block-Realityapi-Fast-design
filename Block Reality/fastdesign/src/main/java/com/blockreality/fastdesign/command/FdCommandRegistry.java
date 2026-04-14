package com.blockreality.fastdesign.command;

import com.blockreality.api.block.RBlockEntity;
import com.blockreality.api.blueprint.Blueprint;
import com.blockreality.api.blueprint.BlueprintIO;
import com.blockreality.api.blueprint.BlueprintNBT;
import com.blockreality.api.command.PlayerSelectionManager;
import com.blockreality.api.material.DefaultMaterial;
import com.blockreality.fastdesign.config.FastDesignConfig;
import com.blockreality.fastdesign.network.FdNetwork;
import com.blockreality.fastdesign.network.OpenCadScreenPacket;
import com.blockreality.fastdesign.network.FdSelectionSyncPacket;
import net.minecraftforge.network.PacketDistributor;
import com.blockreality.api.registry.BRBlocks;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Fast Design 指令系統 — v3fix §2.1
 */
public class FdCommandRegistry {

    private static final Logger LOGGER = LogManager.getLogger("FD-CLI");

    /** Material name suggestions for tab completion */
    public static final SuggestionProvider<CommandSourceStack> MATERIAL_SUGGESTIONS =
        (ctx, builder) -> {
            for (DefaultMaterial m : DefaultMaterial.values()) {
                if (m.getMaterialId().toLowerCase().startsWith(builder.getRemainingLowerCase())) {
                    builder.suggest(m.getMaterialId());
                }
            }
            return builder.buildFuture();
        };

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("fd")
            .requires(src -> src.hasPermission(2))

            .then(Commands.literal("pos1")
                .executes(ctx -> setPos(ctx.getSource(), true)))

            .then(Commands.literal("pos2")
                .executes(ctx -> setPos(ctx.getSource(), false)))

            .then(Commands.literal("box")
                .then(Commands.argument("material", StringArgumentType.word())
                    .suggests(MATERIAL_SUGGESTIONS)
                    .executes(ctx -> {
                        if (!ctx.getSource().hasPermission(2)) {
                            ctx.getSource().sendFailure(Component.literal("§c[FD] 需要操作員權限"));
                            return 0;
                        }
                        return fillBox(ctx.getSource(),
                            StringArgumentType.getString(ctx, "material"));
                    })))

            .then(Commands.literal("extrude")
                .then(Commands.argument("direction", StringArgumentType.word())
                    .then(Commands.argument("distance", IntegerArgumentType.integer(1, 64))
                        .executes(ctx -> {
                            if (!ctx.getSource().hasPermission(2)) {
                                ctx.getSource().sendFailure(Component.literal("§c[FD] 需要操作員權限"));
                                return 0;
                            }
                            return extrude(ctx.getSource(),
                                StringArgumentType.getString(ctx, "direction"),
                                IntegerArgumentType.getInteger(ctx, "distance"));
                        }))))

            .then(Commands.literal("rebar-grid")
                .then(Commands.argument("spacing", IntegerArgumentType.integer(1, 8))
                    .executes(ctx -> {
                        if (!ctx.getSource().hasPermission(2)) {
                            ctx.getSource().sendFailure(Component.literal("§c[FD] 需要操作員權限"));
                            return 0;
                        }
                        return rebarGrid(ctx.getSource(),
                            IntegerArgumentType.getInteger(ctx, "spacing"));
                    })))

            .then(Commands.literal("save")
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(ctx -> {
                        if (!ctx.getSource().hasPermission(2)) {
                            ctx.getSource().sendFailure(Component.literal("§c[FD] 需要操作員權限"));
                            return 0;
                        }
                        return saveBp(ctx.getSource(),
                            StringArgumentType.getString(ctx, "name"));
                    })))

            .then(Commands.literal("load")
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(ctx -> {
                        if (!ctx.getSource().hasPermission(2)) {
                            ctx.getSource().sendFailure(Component.literal("§c[FD] 需要操作員權限"));
                            return 0;
                        }
                        return loadBp(ctx.getSource(),
                            StringArgumentType.getString(ctx, "name"));
                    })))


            .then(Commands.literal("undo")
                .executes(ctx -> {
                    if (!ctx.getSource().hasPermission(2)) {
                        ctx.getSource().sendFailure(Component.literal("§c[FD] 需要操作員權限"));
                        return 0;
                    }
                    return undoLast(ctx.getSource());
                }))

            .then(Commands.literal("redo")
                .executes(ctx -> {
                    if (!ctx.getSource().hasPermission(2)) {
                        ctx.getSource().sendFailure(Component.literal("§c[FD] 需要操作員權限"));
                        return 0;
                    }
                    return redoLast(ctx.getSource());
                }))

            .then(Commands.literal("deselect")
                .executes(ctx -> deselect(ctx.getSource())))

            .then(Commands.literal("cad")
                .executes(ctx -> openCadSelection(ctx.getSource()))
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(ctx -> openCad(ctx.getSource(),
                        StringArgumentType.getString(ctx, "name")))))

            .then(HologramCommand.buildHologramNode())

            .then(Commands.literal("panel")
                .executes(ctx -> openPanel(ctx.getSource())))

            // ── Extended Commands ──
            .then(FdExtendedCommands.copy())
            .then(FdExtendedCommands.paste())
            .then(FdExtendedCommands.mirror())
            .then(FdExtendedCommands.fill())
            .then(FdExtendedCommands.replace())
            .then(FdExtendedCommands.rotate())
            .then(FdExtendedCommands.clear())
            .then(FdExtendedCommands.walls())
            .then(FdExtendedCommands.info())
            .then(FdExtendedCommands.wand())
        );
    }

    private static int setPos(CommandSourceStack src, boolean isPos1) {
        ServerPlayer player = src.getPlayer();
        if (player == null) return fail(src, "Requires a player");

        BlockPos pos = player.blockPosition();
        UUID uuid = player.getUUID();

        if (isPos1) {
            PlayerSelectionManager.setPos1(uuid, pos);
        } else {
            PlayerSelectionManager.setPos2(uuid, pos);
        }

        String label = isPos1 ? "Pos1" : "Pos2";
        src.sendSuccess(() -> Component.literal(String.format(
            "§6[FD] §f%s = §a(%d, %d, %d)", label, pos.getX(), pos.getY(), pos.getZ()
        )), false);

        if (PlayerSelectionManager.hasSelection(uuid)) {
            var box = PlayerSelectionManager.getSelection(uuid);
            src.sendSuccess(() -> Component.literal(String.format(
                "§7  Selection: %dx%dx%d (%d blocks)",
                box.sizeX(), box.sizeY(), box.sizeZ(), box.volume()
            )), false);

            FdNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new FdSelectionSyncPacket(box.min(), box.max())
            );
        }

        return 1;
    }

    private static int fillBox(CommandSourceStack src, String materialName) {
        ServerPlayer player = src.getPlayer();
        if (player == null) return fail(src, "Requires a player");

        if (!PlayerSelectionManager.hasSelection(player.getUUID())) {
            return fail(src, "Set pos1 and pos2 first!");
        }

        DefaultMaterial material = DefaultMaterial.fromId(materialName);
        boolean validId = false;
        for (DefaultMaterial m : DefaultMaterial.values()) {
            if (m.getMaterialId().equals(materialName)) { validId = true; break; }
        }
        if (!validId) {
            return fail(src, "Unknown material: " + materialName +
                ". Available: concrete, rebar, steel, timber, stone, brick, glass, sand, obsidian");
        }

        BlockState blockState = getBlockStateForMaterial(material);

        var box = PlayerSelectionManager.getSelection(player.getUUID());
        if (box.volume() > FastDesignConfig.getMaxSelectionVolume()) {
            return fail(src, "Selection too large! Max " + FastDesignConfig.getMaxSelectionVolume() + " blocks, got " + box.volume());
        }

        ServerLevel level = (ServerLevel) player.level();
        UndoManager.pushSnapshot(player.getUUID(), level, box, "box " + materialName);

        int placed = 0;
        for (BlockPos pos : box.allPositions()) {
            BlockPos immutable = pos.immutable();
            level.setBlock(immutable, blockState, 3);
            BlockEntity be = level.getBlockEntity(immutable);
            if (be instanceof RBlockEntity rbe) {
                rbe.setMaterial(material);
            }
            placed++;
        }

        final int count = placed;
        src.sendSuccess(() -> Component.literal(String.format(
            "§6[FD] §fFilled §a%d §fblocks with §e%s", count, materialName
        )), true);

        return 1;
    }

    private static BlockState getBlockStateForMaterial(DefaultMaterial material) {
        return switch (material) {
            case REBAR -> BRBlocks.R_REBAR.get().defaultBlockState();
            case STEEL -> BRBlocks.R_STEEL.get().defaultBlockState();
            case TIMBER -> BRBlocks.R_TIMBER.get().defaultBlockState();
            default -> BRBlocks.R_CONCRETE.get().defaultBlockState();
        };
    }

    private static int extrude(CommandSourceStack src, String dirStr, int distance) {
        ServerPlayer player = src.getPlayer();
        if (player == null) return fail(src, "Requires a player");

        if (!PlayerSelectionManager.hasSelection(player.getUUID())) {
            return fail(src, "Set pos1 and pos2 first!");
        }

        Direction dir = parseDirection(dirStr);
        if (dir == null) {
            return fail(src, "Unknown direction: " + dirStr +
                ". Use: up, down, north, south, east, west");
        }

        var box = PlayerSelectionManager.getSelection(player.getUUID());
        ServerLevel level = (ServerLevel) player.level();

        List<BlockPos> targetPositions = new ArrayList<>();
        for (BlockPos pos : box.allPositions()) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) continue;
            for (int d = 1; d <= distance; d++) {
                targetPositions.add(pos.immutable().relative(dir, d));
            }
        }
        UndoManager.pushSnapshotForPositions(player.getUUID(), level, targetPositions,
            "extrude " + dirStr + " " + distance);

        int placed = 0;
        for (BlockPos pos : box.allPositions()) {
            BlockPos immutableSrc = pos.immutable();
            BlockState state = level.getBlockState(immutableSrc);
            if (state.isAir()) continue;

            for (int d = 1; d <= distance; d++) {
                BlockPos dst = immutableSrc.relative(dir, d);
                level.setBlock(dst, state, 3);

                BlockEntity srcBe = level.getBlockEntity(immutableSrc);
                BlockEntity dstBe = level.getBlockEntity(dst);
                if (srcBe instanceof RBlockEntity srcRbe && dstBe instanceof RBlockEntity dstRbe) {
                    dstRbe.setMaterial(srcRbe.getMaterial());
                }
                placed++;
            }
        }

        final int count = placed;
        src.sendSuccess(() -> Component.literal(String.format(
            "§6[FD] §fExtruded §a%d §fblocks §e%s §f× %d", count, dirStr, distance
        )), true);

        return 1;
    }

    private static Direction parseDirection(String str) {
        return switch (str.toLowerCase()) {
            case "up" -> Direction.UP;
            case "down" -> Direction.DOWN;
            case "north" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            case "east" -> Direction.EAST;
            case "west" -> Direction.WEST;
            default -> null;
        };
    }

    /**
     * 3D 鋼筋網格 — 三軸交叉（XZ 水平面 × Y 垂直柱），v3fix §3.5
     * 在選取區域內生成完整的三維鋼筋骨架：
     *   - XZ 平面：每隔 spacing 層 Y 生成一層水平網格
     *   - Y 方向：每隔 spacing 格在 X/Z 交叉點生成垂直柱
     */
    private static int rebarGrid(CommandSourceStack src, int spacing) {
        ServerPlayer player = src.getPlayer();
        if (player == null) return fail(src, "Requires a player");

        if (!PlayerSelectionManager.hasSelection(player.getUUID())) {
            return fail(src, "Set pos1 and pos2 first!");
        }

        var box = PlayerSelectionManager.getSelection(player.getUUID());
        ServerLevel level = (ServerLevel) player.level();
        BlockState rebarState = BRBlocks.R_REBAR.get().defaultBlockState();

        UndoManager.pushSnapshot(player.getUUID(), level, box, "rebar-grid " + spacing);

        int placed = 0;
        int minX = box.min().getX(), minY = box.min().getY(), minZ = box.min().getZ();
        int maxX = box.max().getX(), maxY = box.max().getY(), maxZ = box.max().getZ();

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    int relX = x - minX;
                    int relY = y - minY;
                    int relZ = z - minZ;

                    boolean isHorizontalLayer = (relY % spacing == 0);
                    boolean isOnGridLine = (relX % spacing == 0) || (relZ % spacing == 0);
                    boolean isVerticalColumn = (relX % spacing == 0) && (relZ % spacing == 0);

                    if ((isHorizontalLayer && isOnGridLine) || isVerticalColumn) {
                        BlockPos pos = new BlockPos(x, y, z);
                        level.setBlock(pos, rebarState, 3);
                        BlockEntity be = level.getBlockEntity(pos);
                        if (be instanceof RBlockEntity rbe) {
                            rbe.setMaterial(DefaultMaterial.REBAR);
                        }
                        placed++;
                    }
                }
            }
        }

        final int count = placed;
        src.sendSuccess(() -> Component.literal(String.format(
            "§6[FD] §f3D rebar grid: §a%d §fbars (spacing=%d, %dx%dx%d)",
            count, spacing, box.sizeX(), box.sizeY(), box.sizeZ()
        )), true);

        return 1;
    }

    private static int saveBp(CommandSourceStack src, String name) {
        ServerPlayer player = src.getPlayer();
        if (player == null) return fail(src, "Requires a player");

        if (!PlayerSelectionManager.hasSelection(player.getUUID())) {
            return fail(src, "Set pos1 and pos2 first!");
        }

        var box = PlayerSelectionManager.getSelection(player.getUUID());

        try {
            ServerLevel level = (ServerLevel) player.level();
            BlueprintIO.save(level, box.min(), box.max(), name, player.getName().getString());
            src.sendSuccess(() -> Component.literal(String.format(
                "§6[FD] §fBlueprint '§a%s§f' saved (%dx%dx%d)",
                name, box.sizeX(), box.sizeY(), box.sizeZ()
            )), true);
            return 1;
        } catch (IOException e) {
            return fail(src, "Save failed: " + e.getMessage());
        }
    }

    private static int loadBp(CommandSourceStack src, String name) {
        ServerPlayer player = src.getPlayer();
        if (player == null) return fail(src, "Requires a player");

        try {
            var bp = BlueprintIO.load(name);
            ServerLevel level = (ServerLevel) player.level();
            BlockPos origin = player.blockPosition();
            int placed = BlueprintIO.paste(level, bp, origin);

            src.sendSuccess(() -> Component.literal(String.format(
                "§6[FD] §fLoaded '§a%s§f' at (%d,%d,%d) — %d blocks",
                name, origin.getX(), origin.getY(), origin.getZ(), placed
            )), true);
            return 1;
        } catch (IOException e) {
            return fail(src, "Load failed: " + e.getMessage());
        }
    }


    /**
     * /fd cad (no args) — capture current selection and open CAD view.
     */
    private static int openCadSelection(CommandSourceStack src) {
        ServerPlayer player = src.getPlayer();
        if (player == null) return fail(src, "Requires a player");

        if (!PlayerSelectionManager.hasSelection(player.getUUID())) {
            return fail(src, "Set pos1 and pos2 first, or use /fd cad <name> to open a saved blueprint");
        }

        var box = PlayerSelectionManager.getSelection(player.getUUID());
        try {
            Blueprint bp = BlueprintIO.captureBlueprint(
                (ServerLevel) player.level(), box.min(), box.max(),
                "selection", player.getName().getString());
            OpenCadScreenPacket packet = new OpenCadScreenPacket(BlueprintNBT.write(bp));
            FdNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player), packet);
            src.sendSuccess(() -> Component.literal(String.format(
                "§6[FD] §fOpening CAD view for selection (%dx%dx%d)...",
                box.sizeX(), box.sizeY(), box.sizeZ()
            )), false);
            return 1;
        } catch (Exception e) {
            return fail(src, "Failed to capture selection: " + e.getMessage());
        }
    }

    private static int openCad(CommandSourceStack src, String name) {
        ServerPlayer player = src.getPlayer();
        if (player == null) return fail(src, "Requires a player");

        try {
            Blueprint bp = BlueprintIO.load(name);
            OpenCadScreenPacket packet = new OpenCadScreenPacket(BlueprintNBT.write(bp));
            FdNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                packet
            );

            src.sendSuccess(() -> Component.literal(String.format(
                "§6[FD] §fOpening CAD view for '§a%s§f'...", name
            )), false);
            return 1;
        } catch (IOException e) {
            return fail(src, "Failed to open CAD: " + e.getMessage());
        }
    }

    private static int undoLast(CommandSourceStack src) {
        ServerPlayer player = src.getPlayer();
        if (player == null) return fail(src, "Requires a player");

        UUID uuid = player.getUUID();
        ServerLevel level = (ServerLevel) player.level();

        // DeltaUndoManager 優先（支援 redo 堆疊）；無記錄時 fallback 至舊版 UndoManager
        if (DeltaUndoManager.getUndoStackSize(uuid) > 0) {
            String desc = DeltaUndoManager.peekUndoDescription(uuid);
            int restored = DeltaUndoManager.undo(uuid, level);
            final String opDesc = desc != null ? desc : "unknown";
            src.sendSuccess(() -> Component.literal(String.format(
                "§6[FD] §fUndo '§e%s§f' — restored §a%d §fblocks", opDesc, restored
            )), true);
            return 1;
        }

        String desc = UndoManager.peekDescription(uuid);
        if (desc == null) {
            return fail(src, "Nothing to undo!");
        }
        int restored = UndoManager.undo(uuid, level);
        final String opDesc = desc;
        src.sendSuccess(() -> Component.literal(String.format(
            "§6[FD] §fUndo '§e%s§f' — restored §a%d §fblocks", opDesc, restored
        )), true);
        return 1;
    }

    private static int redoLast(CommandSourceStack src) {
        ServerPlayer player = src.getPlayer();
        if (player == null) return fail(src, "Requires a player");

        UUID uuid = player.getUUID();
        ServerLevel level = (ServerLevel) player.level();

        if (DeltaUndoManager.getRedoStackSize(uuid) == 0) {
            return fail(src, "Nothing to redo!");
        }

        String desc = DeltaUndoManager.peekRedoDescription(uuid);
        int restored = DeltaUndoManager.redo(uuid, level);
        final String opDesc = desc != null ? desc : "unknown";
        src.sendSuccess(() -> Component.literal(String.format(
            "§6[FD] §fRedo '§e%s§f' — restored §a%d §fblocks", opDesc, restored
        )), true);
        return 1;
    }

    /**
     * /fd deselect — 取消選取區域
     */
    private static int deselect(CommandSourceStack src) {
        ServerPlayer player = src.getPlayer();
        if (player == null) return fail(src, "Requires a player");

        UUID uuid = player.getUUID();
        if (!PlayerSelectionManager.hasSelection(uuid)) {
            return fail(src, "沒有選取區域可以取消");
        }

        PlayerSelectionManager.clear(uuid);

        // 同步客戶端清除選取框渲染
        FdNetwork.CHANNEL.send(
            net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
            FdSelectionSyncPacket.clearSelection()
        );

        // 清除幽靈預覽（如果有）
        src.sendSuccess(() -> Component.literal("§6[FD] §f選取區域已清除"), true);
        return 1;
    }

    /**
     * /fd panel — 提示玩家開啟 Control Panel
     * 由於 Screen 只能在客戶端開啟，這裡發送提示信息
     */
    private static int openPanel(CommandSourceStack src) {
        ServerPlayer player = src.getPlayer();
        if (player == null) return fail(src, "Requires a player");

        src.sendSuccess(() -> Component.literal(
            "§6[FD] §f請按 §eG 鍵§f 開啟控制面板，或 §eShift+右鍵§f 使用游標開啟"
        ), false);
        return 1;
    }

    private static int fail(CommandSourceStack src, String msg) {
        src.sendFailure(Component.literal("§c[FD] " + msg));
        return 0;
    }
}
