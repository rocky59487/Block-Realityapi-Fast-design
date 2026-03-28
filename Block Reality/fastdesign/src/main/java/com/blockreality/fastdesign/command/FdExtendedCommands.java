package com.blockreality.fastdesign.command;

import com.blockreality.fastdesign.command.DeltaUndoManager;
import com.blockreality.api.block.RBlockEntity;
import com.blockreality.api.blueprint.Blueprint;
import com.blockreality.api.blueprint.BlueprintIO;
import com.blockreality.api.blueprint.BlueprintNBT;
import com.blockreality.api.command.PlayerSelectionManager;
import com.blockreality.api.material.DefaultMaterial;
import com.blockreality.api.registry.BRBlocks;
import com.blockreality.fastdesign.config.FastDesignConfig;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FD Extended Commands — clipboard, mirror, rotate, fill, replace, clear, walls, info, wand
 */
public class FdExtendedCommands {

    private static final Logger LOGGER = LogManager.getLogger("FD-CLI");

    // Clipboard: UUID -> Blueprint
    private static final Map<UUID, Blueprint> CLIPBOARD = new ConcurrentHashMap<>();

    // Pending paste: UUID -> Blueprint (waiting for confirm/cancel)
    private static final Map<UUID, Blueprint> PENDING_PASTE = new ConcurrentHashMap<>();

    // Material suggestions provider
    public static final SuggestionProvider<CommandSourceStack> MATERIAL_SUGGESTIONS =
            (ctx, builder) -> {
                for (DefaultMaterial mat : DefaultMaterial.values()) {
                    builder.suggest(mat.getMaterialId());
                }
                return builder.buildFuture();
            };

    // ==================== /fd copy ====================
    public static LiteralArgumentBuilder<CommandSourceStack> copy() {
        return Commands.literal("copy")
                .executes(ctx -> executeCopy(ctx.getSource()));
    }

    private static int executeCopy(CommandSourceStack src) {
        ServerPlayer player = src.getPlayer();
        if (player == null) return fail(src, "Requires a player");

        UUID uuid = player.getUUID();
        if (!PlayerSelectionManager.hasSelection(uuid)) {
            return fail(src, "Set pos1 and pos2 first!");
        }

        var box = PlayerSelectionManager.getSelection(uuid);
        if (box.volume() > FastDesignConfig.getMaxSelectionVolume()) {
            return fail(src, "Selection too large! Max " + FastDesignConfig.getMaxSelectionVolume() + " blocks, got " + box.volume());
        }

        try {
            ServerLevel level = (ServerLevel) player.level();
            Blueprint bp = BlueprintIO.capture(level, box.min(), box.max());
            CLIPBOARD.put(uuid, bp);

            src.sendSuccess(() -> Component.literal(String.format(
                    "§6[FD] §fCopied §a%d §fblocks to clipboard (%dx%dx%d)",
                    box.volume(), box.sizeX(), box.sizeY(), box.sizeZ()
            )), false);
            return 1;
        } catch (Exception e) {
            return fail(src, "Copy failed: " + e.getMessage());
        }
    }

    // ==================== /fd paste ====================
    public static LiteralArgumentBuilder<CommandSourceStack> paste() {
        return Commands.literal("paste")
                .executes(ctx -> executePreviewPaste(ctx.getSource()))
                .then(Commands.literal("confirm")
                        .executes(ctx -> executeConfirmPaste(ctx.getSource())))
                .then(Commands.literal("cancel")
                        .executes(ctx -> executeCancelPaste(ctx.getSource())));
    }

    /** /fd paste — Generate preview and wait for confirmation */
    private static int executePreviewPaste(CommandSourceStack src) {
        ServerPlayer player = src.getPlayer();
        if (player == null) return fail(src, "Requires a player");

        UUID uuid = player.getUUID();
        Blueprint bp = CLIPBOARD.get(uuid);
        if (bp == null) {
            return fail(src, "Clipboard is empty! Use /fd copy first");
        }

        try {
            // Store pending paste state
            PENDING_PASTE.put(uuid, bp);

            // Send preview message
            src.sendSuccess(() -> Component.literal(
                    "§6[FD] §f已生成貼上預覽 — 輸入 §a/fd paste confirm §f確認放置，§c/fd paste cancel §f取消"
            ), false);
            return 1;
        } catch (Exception e) {
            return fail(src, "Preview failed: " + e.getMessage());
        }
    }

    /** /fd paste confirm — Actually place the blocks */
    private static int executeConfirmPaste(CommandSourceStack src) {
        ServerPlayer player = src.getPlayer();
        if (player == null) return fail(src, "Requires a player");

        UUID uuid = player.getUUID();
        Blueprint bp = PENDING_PASTE.get(uuid);
        if (bp == null) {
            return fail(src, "No pending paste! Use /fd paste first");
        }

        try {
            ServerLevel level = (ServerLevel) player.level();
            BlockPos origin = player.blockPosition();

            // Create undo snapshot before pasting (iterate blocks directly — Blueprint has no bounds())
            List<BlockPos> affectedPositions = new ArrayList<>();
            for (Blueprint.BlueprintBlock b : bp.getBlocks()) {
                affectedPositions.add(origin.offset(b.getRelX(), b.getRelY(), b.getRelZ()));
            }
            var beforeMap = DeltaUndoManager.captureBeforeState(level, affectedPositions);
            int placed = BlueprintIO.paste(level, bp, origin);
            DeltaUndoManager.commitChanges(uuid, level, beforeMap, "paste");

            // Clear pending state
            PENDING_PASTE.remove(uuid);

            src.sendSuccess(() -> Component.literal(String.format(
                    "§6[FD] §fPasted §a%d §fblocks at (%d,%d,%d)",
                    placed, origin.getX(), origin.getY(), origin.getZ()
            )), true);
            return 1;
        } catch (Exception e) {
            return fail(src, "Paste failed: " + e.getMessage());
        }
    }

    /** /fd paste cancel — Cancel pending paste */
    private static int executeCancelPaste(CommandSourceStack src) {
        ServerPlayer player = src.getPlayer();
        if (player == null) return fail(src, "Requires a player");

        UUID uuid = player.getUUID();
        if (!PENDING_PASTE.containsKey(uuid)) {
            return fail(src, "No pending paste to cancel");
        }

        PENDING_PASTE.remove(uuid);
        src.sendSuccess(() -> Component.literal("§6[FD] §cPaste preview cancelled"), false);
        return 1;
    }

    // ==================== /fd mirror <axis> ====================
    public static LiteralArgumentBuilder<CommandSourceStack> mirror() {
        return Commands.literal("mirror")
                .then(Commands.argument("axis", StringArgumentType.word())
                        .executes(ctx -> executeMirror(ctx.getSource(),
                                StringArgumentType.getString(ctx, "axis"))));
    }

    private static int executeMirror(CommandSourceStack src, String axis) {
        ServerPlayer player = src.getPlayer();
        if (player == null) return fail(src, "Requires a player");

        UUID uuid = player.getUUID();
        Blueprint bp = CLIPBOARD.get(uuid);
        if (bp == null) {
            return fail(src, "Clipboard is empty! Use /fd copy first");
        }

        final String axisLower = axis.toLowerCase();
        if (!axisLower.matches("[xyz]")) {
            return fail(src, "Unknown axis: " + axisLower + ". Use: x, y, z");
        }

        try {
            Blueprint mirrored = bp.mirror(axisLower.charAt(0));
            CLIPBOARD.put(uuid, mirrored);

            src.sendSuccess(() -> Component.literal(String.format(
                    "§6[FD] §fMirrored clipboard along §a%s§f axis", axisLower.toUpperCase()
            )), false);
            return 1;
        } catch (Exception e) {
            return fail(src, "Mirror failed: " + e.getMessage());
        }
    }

    // ==================== /fd rotate <90|180|270> ====================
    public static LiteralArgumentBuilder<CommandSourceStack> rotate() {
        return Commands.literal("rotate")
                .then(Commands.argument("degrees", IntegerArgumentType.integer())
                        .executes(ctx -> executeRotate(ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "degrees"))));
    }

    private static int executeRotate(CommandSourceStack src, int degrees) {
        ServerPlayer player = src.getPlayer();
        if (player == null) return fail(src, "Requires a player");

        UUID uuid = player.getUUID();
        Blueprint bp = CLIPBOARD.get(uuid);
        if (bp == null) {
            return fail(src, "Clipboard is empty! Use /fd copy first");
        }

        if (degrees % 90 != 0 || degrees < 0 || degrees >= 360) {
            return fail(src, "Degrees must be 0, 90, 180, or 270");
        }

        try {
            int rotations = degrees / 90;
            Blueprint rotated = bp.rotateY(rotations);
            CLIPBOARD.put(uuid, rotated);

            src.sendSuccess(() -> Component.literal(String.format(
                    "§6[FD] §fRotated clipboard by §a%d°§f around Y axis", degrees
            )), false);
            return 1;
        } catch (Exception e) {
            return fail(src, "Rotate failed: " + e.getMessage());
        }
    }

    // ==================== /fd fill <block> ====================
    public static LiteralArgumentBuilder<CommandSourceStack> fill() {
        return Commands.literal("fill")
                .then(Commands.argument("block", StringArgumentType.word())
                        .suggests(MATERIAL_SUGGESTIONS)
                        .executes(ctx -> executeFill(ctx.getSource(),
                                StringArgumentType.getString(ctx, "block"))));
    }

    private static int executeFill(CommandSourceStack src, String blockName) {
        ServerPlayer player = src.getPlayer();
        if (player == null) return fail(src, "Requires a player");

        UUID uuid = player.getUUID();
        if (!PlayerSelectionManager.hasSelection(uuid)) {
            return fail(src, "Set pos1 and pos2 first!");
        }

        var box = PlayerSelectionManager.getSelection(uuid);
        if (box.volume() > FastDesignConfig.getMaxSelectionVolume()) {
            return fail(src, "Selection too large! Max " + FastDesignConfig.getMaxSelectionVolume() + " blocks");
        }

        BlockState blockState = parseBlockState(blockName);
        if (blockState == null) {
            return fail(src, "Unknown block: " + blockName);
        }

        try {
            ServerLevel level = (ServerLevel) player.level();
            List<BlockPos> positions = new ArrayList<>();
            for (BlockPos pos : box.allPositions()) { positions.add(pos.immutable()); }
            var beforeMap = DeltaUndoManager.captureBeforeState(level, positions);

            int placed = 0;
            for (BlockPos pos : positions) {
                level.setBlock(pos, blockState, 3);
                placed++;
            }
            DeltaUndoManager.commitChanges(uuid, level, beforeMap, "fill " + blockName);

            final int count = placed;
            src.sendSuccess(() -> Component.literal(String.format(
                    "§6[FD] §fFilled §a%d §fblocks with §e%s", count, blockName
            )), true);
            return 1;
        } catch (Exception e) {
            return fail(src, "Fill failed: " + e.getMessage());
        }
    }

    // ==================== /fd replace <from> <to> ====================
    public static LiteralArgumentBuilder<CommandSourceStack> replace() {
        return Commands.literal("replace")
                .then(Commands.argument("from", StringArgumentType.word())
                        .then(Commands.argument("to", StringArgumentType.word())
                                .executes(ctx -> executeReplace(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "from"),
                                        StringArgumentType.getString(ctx, "to")))));
    }

    private static int executeReplace(CommandSourceStack src, String fromName, String toName) {
        ServerPlayer player = src.getPlayer();
        if (player == null) return fail(src, "Requires a player");

        UUID uuid = player.getUUID();
        if (!PlayerSelectionManager.hasSelection(uuid)) {
            return fail(src, "Set pos1 and pos2 first!");
        }

        var box = PlayerSelectionManager.getSelection(uuid);
        if (box.volume() > FastDesignConfig.getMaxSelectionVolume()) {
            return fail(src, "Selection too large! Max " + FastDesignConfig.getMaxSelectionVolume() + " blocks");
        }

        BlockState fromState = parseBlockState(fromName);
        BlockState toState = parseBlockState(toName);

        if (fromState == null) {
            return fail(src, "Unknown block: " + fromName);
        }
        if (toState == null) {
            return fail(src, "Unknown block: " + toName);
        }

        try {
            ServerLevel level = (ServerLevel) player.level();
            List<BlockPos> positions = new ArrayList<>();
            for (BlockPos pos : box.allPositions()) { positions.add(pos.immutable()); }
            var beforeMap = DeltaUndoManager.captureBeforeState(level, positions);

            int replaced = 0;
            for (BlockPos pos : positions) {
                BlockState current = level.getBlockState(pos);
                if (current.getBlock() == fromState.getBlock()) {
                    level.setBlock(pos, toState, 3);
                    replaced++;
                }
            }
            DeltaUndoManager.commitChanges(uuid, level, beforeMap, "replace " + fromName + "->" + toName);

            final int count = replaced;
            src.sendSuccess(() -> Component.literal(String.format(
                    "§6[FD] §fReplaced §a%d §fblocks (§e%s§f → §e%s§f)",
                    count, fromName, toName
            )), true);
            return 1;
        } catch (Exception e) {
            return fail(src, "Replace failed: " + e.getMessage());
        }
    }

    // ==================== /fd clear ====================
    public static LiteralArgumentBuilder<CommandSourceStack> clear() {
        return Commands.literal("clear")
                .executes(ctx -> executeClear(ctx.getSource()));
    }

    private static int executeClear(CommandSourceStack src) {
        ServerPlayer player = src.getPlayer();
        if (player == null) return fail(src, "Requires a player");

        UUID uuid = player.getUUID();
        if (!PlayerSelectionManager.hasSelection(uuid)) {
            return fail(src, "Set pos1 and pos2 first!");
        }

        var box = PlayerSelectionManager.getSelection(uuid);
        if (box.volume() > FastDesignConfig.getMaxSelectionVolume()) {
            return fail(src, "Selection too large! Max " + FastDesignConfig.getMaxSelectionVolume() + " blocks");
        }

        try {
            ServerLevel level = (ServerLevel) player.level();
            List<BlockPos> positions = new ArrayList<>();
            for (BlockPos pos : box.allPositions()) { positions.add(pos.immutable()); }
            var beforeMap = DeltaUndoManager.captureBeforeState(level, positions);

            int cleared = 0;
            for (BlockPos pos : positions) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                cleared++;
            }
            DeltaUndoManager.commitChanges(uuid, level, beforeMap, "clear");

            final int count = cleared;
            src.sendSuccess(() -> Component.literal(String.format(
                    "§6[FD] §fCleared §a%d §fblocks", count
            )), true);
            return 1;
        } catch (Exception e) {
            return fail(src, "Clear failed: " + e.getMessage());
        }
    }

    // ==================== /fd walls <material> ====================
    public static LiteralArgumentBuilder<CommandSourceStack> walls() {
        return Commands.literal("walls")
                .then(Commands.argument("material", StringArgumentType.word())
                        .suggests(MATERIAL_SUGGESTIONS)
                        .executes(ctx -> executeWalls(ctx.getSource(),
                                StringArgumentType.getString(ctx, "material"))));
    }

    private static int executeWalls(CommandSourceStack src, String materialName) {
        ServerPlayer player = src.getPlayer();
        if (player == null) return fail(src, "Requires a player");

        UUID uuid = player.getUUID();
        if (!PlayerSelectionManager.hasSelection(uuid)) {
            return fail(src, "Set pos1 and pos2 first!");
        }

        var box = PlayerSelectionManager.getSelection(uuid);
        DefaultMaterial material = Arrays.stream(DefaultMaterial.values())
                .filter(m -> m.getMaterialId().equals(materialName))
                .findFirst().orElse(null);

        if (material == null) {
            return fail(src, "Unknown material: " + materialName);
        }

        BlockState blockState = getBlockStateForMaterial(material);

        try {
            ServerLevel level = (ServerLevel) player.level();
            List<BlockPos> allPos = new ArrayList<>();
            for (BlockPos p : box.allPositions()) { allPos.add(p.immutable()); }
            var beforeMap = DeltaUndoManager.captureBeforeState(level, allPos);

            int placed = 0;
            int minX = box.min().getX(), maxX = box.max().getX();
            int minY = box.min().getY(), maxY = box.max().getY();
            int minZ = box.min().getZ(), maxZ = box.max().getZ();

            // Front and back walls (constant Z)
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    // Front wall
                    BlockPos posZ0 = new BlockPos(x, y, minZ);
                    level.setBlock(posZ0, blockState, 3);
                    setRBlockMaterial(level, posZ0, material);
                    placed++;

                    // Back wall
                    BlockPos posZ1 = new BlockPos(x, y, maxZ);
                    level.setBlock(posZ1, blockState, 3);
                    setRBlockMaterial(level, posZ1, material);
                    placed++;
                }
            }

            // Left and right walls (constant X, excluding corners already done)
            for (int z = minZ + 1; z < maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    // Left wall
                    BlockPos posX0 = new BlockPos(minX, y, z);
                    level.setBlock(posX0, blockState, 3);
                    setRBlockMaterial(level, posX0, material);
                    placed++;

                    // Right wall
                    BlockPos posX1 = new BlockPos(maxX, y, z);
                    level.setBlock(posX1, blockState, 3);
                    setRBlockMaterial(level, posX1, material);
                    placed++;
                }
            }

            DeltaUndoManager.commitChanges(uuid, level, beforeMap, "walls " + materialName);
            final int count = placed;
            src.sendSuccess(() -> Component.literal(String.format(
                    "§6[FD] §fBuilt walls: §a%d §fblocks with §e%s", count, materialName
            )), true);
            return 1;
        } catch (Exception e) {
            return fail(src, "Walls failed: " + e.getMessage());
        }
    }

    // ==================== /fd info ====================
    public static LiteralArgumentBuilder<CommandSourceStack> info() {
        return Commands.literal("info")
                .executes(ctx -> executeInfo(ctx.getSource()));
    }

    private static int executeInfo(CommandSourceStack src) {
        ServerPlayer player = src.getPlayer();
        if (player == null) return fail(src, "Requires a player");

        UUID uuid = player.getUUID();

        src.sendSuccess(() -> Component.literal("§6[FD] === Selection Info ==="), false);

        if (PlayerSelectionManager.hasSelection(uuid)) {
            var box = PlayerSelectionManager.getSelection(uuid);
            src.sendSuccess(() -> Component.literal(String.format(
                    "§6[FD] §fPos1: §a(%d, %d, %d)",
                    box.min().getX(), box.min().getY(), box.min().getZ()
            )), false);
            src.sendSuccess(() -> Component.literal(String.format(
                    "§6[FD] §fPos2: §a(%d, %d, %d)",
                    box.max().getX(), box.max().getY(), box.max().getZ()
            )), false);
            src.sendSuccess(() -> Component.literal(String.format(
                    "§6[FD] §fSize: §a%dx%dx%d§f, Volume: §a%d§f blocks",
                    box.sizeX(), box.sizeY(), box.sizeZ(), box.volume()
            )), false);
        } else {
            src.sendSuccess(() -> Component.literal("§6[FD] §fNo selection (set pos1 and pos2)"), false);
        }

        Blueprint clipboard = CLIPBOARD.get(uuid);
        if (clipboard != null) {
            src.sendSuccess(() -> Component.literal(String.format(
                    "§6[FD] §fClipboard: §a%d§f blocks", clipboard.getBlockCount()
            )), false);
        } else {
            src.sendSuccess(() -> Component.literal("§6[FD] §fClipboard: §7empty"), false);
        }

        int undoDepth = DeltaUndoManager.getUndoStackSize(uuid);
        int redoDepth = DeltaUndoManager.getRedoStackSize(uuid);
        src.sendSuccess(() -> Component.literal(String.format(
                "§6[FD] §fUndo stack: §a%d§f operations | Redo stack: §a%d§f operations", undoDepth, redoDepth
        )), false);

        return 1;
    }

    // ==================== /fd wand ====================
    public static LiteralArgumentBuilder<CommandSourceStack> wand() {
        return Commands.literal("wand")
                .executes(ctx -> executeWand(ctx.getSource()));
    }

    private static int executeWand(CommandSourceStack src) {
        ServerPlayer player = src.getPlayer();
        if (player == null) return fail(src, "Requires a player");

        // 優先給正式的 FD 游標
        ItemStack wand = new ItemStack(com.blockreality.fastdesign.registry.FdItems.FD_WAND.get());

        player.getInventory().add(wand);
        player.containerMenu.broadcastChanges();

        src.sendSuccess(() -> Component.literal(
                "§6[FD] §f已給予 §6FD 選取游標§f — 左鍵設 A，右鍵設 B！"
        ), false);

        return 1;
    }

    // ==================== Helpers ====================

    /**
     * 收集選取框內排除掉被個別取消選取的位置後的所有方塊位置
     */
    private static List<BlockPos> collectNonExcluded(UUID uuid, PlayerSelectionManager.SelectionBox box) {
        List<BlockPos> positions = new ArrayList<>();
        for (BlockPos pos : box.allPositions()) {
            BlockPos immutable = pos.immutable();
            if (!SelectionExclusionManager.isExcluded(uuid, immutable)) {
                positions.add(immutable);
            }
        }
        return positions;
    }

    private static BlockState parseBlockState(String blockName) {
        try {
            ResourceLocation id = ResourceLocation.tryParse(blockName);
            if (id != null && BuiltInRegistries.BLOCK.containsKey(id)) {
                return BuiltInRegistries.BLOCK.get(id).defaultBlockState();
            }
            // Try without namespace
            id = new ResourceLocation("minecraft", blockName);
            if (BuiltInRegistries.BLOCK.containsKey(id)) {
                return BuiltInRegistries.BLOCK.get(id).defaultBlockState();
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to parse block: " + blockName, e);
        }
        return null;
    }

    private static BlockState getBlockStateForMaterial(DefaultMaterial material) {
        return switch (material) {
            case REBAR -> BRBlocks.R_REBAR.get().defaultBlockState();
            case STEEL -> BRBlocks.R_STEEL.get().defaultBlockState();
            case TIMBER -> BRBlocks.R_TIMBER.get().defaultBlockState();
            default -> BRBlocks.R_CONCRETE.get().defaultBlockState();
        };
    }

    private static void setRBlockMaterial(ServerLevel level, BlockPos pos, DefaultMaterial material) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof RBlockEntity rbe) {
            rbe.setMaterial(material);
        }
    }

    private static int fail(CommandSourceStack src, String msg) {
        src.sendFailure(Component.literal("§c[FD] " + msg));
        return 0;
    }

    // ==================== Public API for FdActionPacket ====================

    /**
     * 複製選取區域到玩家剪貼簿（不需要 CommandSourceStack）
     *
     * @return 複製的方塊數量，失敗時拋出異常
     */
    public static int doCopy(ServerPlayer player, ServerLevel level) throws Exception {
        UUID uuid = player.getUUID();
        if (!PlayerSelectionManager.hasSelection(uuid)) {
            throw new IllegalStateException("必須先選取區域（設定 pos1 和 pos2）");
        }

        var box = PlayerSelectionManager.getSelection(uuid);
        if (box.volume() > FastDesignConfig.getMaxSelectionVolume()) {
            throw new IllegalStateException("Selection too large! Max " + FastDesignConfig.getMaxSelectionVolume() + " blocks, got " + box.volume());
        }

        Blueprint bp = BlueprintIO.capture(level, box.min(), box.max());
        CLIPBOARD.put(uuid, bp);
        return (int) box.volume();
    }

    /**
     * 在玩家位置粘貼剪貼簿內容（不需要 CommandSourceStack）
     *
     * @return 放置的方塊數量，失敗時拋出異常
     */
    public static int doPaste(ServerPlayer player, ServerLevel level) throws Exception {
        UUID uuid = player.getUUID();
        Blueprint bp = CLIPBOARD.get(uuid);
        if (bp == null) {
            throw new IllegalStateException("剪貼簿為空！先使用 Copy");
        }

        BlockPos origin = player.blockPosition();

        List<BlockPos> affectedPositions = new ArrayList<>();
        for (Blueprint.BlueprintBlock b : bp.getBlocks()) {
            affectedPositions.add(origin.offset(b.getRelX(), b.getRelY(), b.getRelZ()));
        }
        var beforeMap = DeltaUndoManager.captureBeforeState(level, affectedPositions);
        int result = BlueprintIO.paste(level, bp, origin);
        DeltaUndoManager.commitChanges(uuid, level, beforeMap, "paste");
        return result;
    }

    /**
     * 清除選取區域的所有方塊（不需要 CommandSourceStack）
     *
     * @return 清除的方塊數量，失敗時拋出異常
     */
    public static int doClear(ServerPlayer player, ServerLevel level) throws Exception {
        UUID uuid = player.getUUID();
        if (!PlayerSelectionManager.hasSelection(uuid)) {
            throw new IllegalStateException("必須先選取區域（設定 pos1 和 pos2）");
        }

        var box = PlayerSelectionManager.getSelection(uuid);
        if (box.volume() > FastDesignConfig.getMaxSelectionVolume()) {
            throw new IllegalStateException("Selection too large! Max " + FastDesignConfig.getMaxSelectionVolume() + " blocks");
        }

        List<BlockPos> positions = collectNonExcluded(uuid, box);
        var beforeMap = DeltaUndoManager.captureBeforeState(level, positions);

        int cleared = 0;
        for (BlockPos pos : positions) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            cleared++;
        }
        DeltaUndoManager.commitChanges(uuid, level, beforeMap, "clear");
        return cleared;
    }

    /**
     * 檢查玩家是否有剪貼簿內容
     */
    public static boolean hasClipboard(UUID uuid) {
        return CLIPBOARD.containsKey(uuid);
    }

    /**
     * 取得玩家剪貼簿的方塊數量
     */
    public static int getClipboardSize(UUID uuid) {
        Blueprint bp = CLIPBOARD.get(uuid);
        return bp == null ? 0 : bp.getBlocks().size();
    }

    /**
     * 取得玩家剪貼簿（用於預覽模式）
     */
    public static Blueprint getClipboard(UUID uuid) {
        return CLIPBOARD.get(uuid);
    }

    /**
     * 設定待確認的粘貼內容（用於預覽模式）
     */
    public static void setPendingPaste(UUID uuid, Blueprint bp) {
        PENDING_PASTE.put(uuid, bp);
    }

    /**
     * 確認粘貼並放置方塊
     */
    public static int confirmPaste(ServerPlayer player, ServerLevel level) throws Exception {
        UUID uuid = player.getUUID();
        Blueprint bp = PENDING_PASTE.get(uuid);
        if (bp == null) {
            throw new IllegalStateException("No pending paste! Use /fd paste first");
        }

        try {
            BlockPos origin = player.blockPosition();

            // Create undo snapshot before pasting
            List<BlockPos> affectedPositions = new ArrayList<>();
            for (Blueprint.BlueprintBlock b : bp.getBlocks()) {
                affectedPositions.add(origin.offset(b.getRelX(), b.getRelY(), b.getRelZ()));
            }
            var beforeMap = DeltaUndoManager.captureBeforeState(level, affectedPositions);
            int placed = BlueprintIO.paste(level, bp, origin);
            DeltaUndoManager.commitChanges(uuid, level, beforeMap, "paste");

            // Clear pending state
            PENDING_PASTE.remove(uuid);
            return placed;
        } catch (Exception e) {
            throw new Exception("Paste failed: " + e.getMessage(), e);
        }
    }

    /**
     * 取得待確認的粘貼藍圖（PastePlacePacket 使用）
     */
    public static Blueprint getPendingPaste(UUID uuid) {
        return PENDING_PASTE.get(uuid);
    }

    /**
     * 清除待確認的粘貼狀態（放置完成後呼叫）
     */
    public static void clearPendingPaste(UUID uuid) {
        PENDING_PASTE.remove(uuid);
    }

    /**
     * 取消粘貼預覽
     */
    public static void cancelPaste(UUID uuid) {
        PENDING_PASTE.remove(uuid);
    }

    // ==================== Public API: Mirror / Rotate ====================

    /**
     * 鏡像剪貼簿（不需要 CommandSourceStack）
     * @param axis 'x', 'y', or 'z'
     * @return 鏡像後的方塊數量
     */
    public static int doMirror(ServerPlayer player, String axis) throws Exception {
        UUID uuid = player.getUUID();
        Blueprint bp = CLIPBOARD.get(uuid);
        if (bp == null) {
            throw new IllegalStateException("剪貼簿為空！先使用 Copy");
        }
        if (!axis.matches("[xyzXYZ]")) {
            throw new IllegalArgumentException("未知軸向: " + axis + ". 請使用: x, y, z");
        }
        Blueprint mirrored = bp.mirror(axis.toLowerCase().charAt(0));
        CLIPBOARD.put(uuid, mirrored);
        return mirrored.getBlocks().size();
    }

    /**
     * 旋轉剪貼簿（不需要 CommandSourceStack）
     * @param degrees 0, 90, 180, or 270
     * @return 旋轉後的方塊數量
     */
    public static int doRotate(ServerPlayer player, int degrees) throws Exception {
        UUID uuid = player.getUUID();
        Blueprint bp = CLIPBOARD.get(uuid);
        if (bp == null) {
            throw new IllegalStateException("剪貼簿為空！先使用 Copy");
        }
        if (degrees % 90 != 0 || degrees < 0 || degrees >= 360) {
            throw new IllegalArgumentException("角度必須為 0, 90, 180, 或 270");
        }
        Blueprint rotated = bp.rotateY(degrees / 90);
        CLIPBOARD.put(uuid, rotated);
        return rotated.getBlocks().size();
    }

    /**
     * 填充選取區域（不需要 CommandSourceStack）
     * @param blockName 方塊 ID
     * @return 填充的方塊數量
     */
    public static int doFill(ServerPlayer player, ServerLevel level, String blockName) throws Exception {
        UUID uuid = player.getUUID();
        if (!PlayerSelectionManager.hasSelection(uuid)) {
            throw new IllegalStateException("必須先選取區域（設定 pos1 和 pos2）");
        }
        var box = PlayerSelectionManager.getSelection(uuid);
        if (box.volume() > FastDesignConfig.getMaxSelectionVolume()) {
            throw new IllegalStateException("選取過大！最大 " + FastDesignConfig.getMaxSelectionVolume() + " 方塊");
        }
        BlockState blockState = parseBlockState(blockName);
        if (blockState == null) {
            throw new IllegalArgumentException("未知方塊: " + blockName);
        }
        List<BlockPos> positions = collectNonExcluded(uuid, box);
        var beforeMap = DeltaUndoManager.captureBeforeState(level, positions);

        int placed = 0;
        for (BlockPos pos : positions) {
            level.setBlock(pos, blockState, 3);
            placed++;
        }
        DeltaUndoManager.commitChanges(uuid, level, beforeMap, "fill " + blockName);
        return placed;
    }

    /**
     * 替換選取區域方塊（不需要 CommandSourceStack）
     * @param fromName 來源方塊 ID
     * @param toName 目標方塊 ID
     * @return 替換的方塊數量
     */
    public static int doReplace(ServerPlayer player, ServerLevel level,
                                 String fromName, String toName) throws Exception {
        UUID uuid = player.getUUID();
        if (!PlayerSelectionManager.hasSelection(uuid)) {
            throw new IllegalStateException("必須先選取區域（設定 pos1 和 pos2）");
        }
        var box = PlayerSelectionManager.getSelection(uuid);
        if (box.volume() > FastDesignConfig.getMaxSelectionVolume()) {
            throw new IllegalStateException("選取過大！最大 " + FastDesignConfig.getMaxSelectionVolume() + " 方塊");
        }
        BlockState fromState = parseBlockState(fromName);
        BlockState toState = parseBlockState(toName);
        if (fromState == null) throw new IllegalArgumentException("未知方塊: " + fromName);
        if (toState == null) throw new IllegalArgumentException("未知方塊: " + toName);
        List<BlockPos> positions = collectNonExcluded(uuid, box);
        var beforeMap = DeltaUndoManager.captureBeforeState(level, positions);

        int replaced = 0;
        for (BlockPos pos : positions) {
            BlockState current = level.getBlockState(pos);
            if (current.getBlock() == fromState.getBlock()) {
                level.setBlock(pos, toState, 3);
                replaced++;
            }
        }
        DeltaUndoManager.commitChanges(uuid, level, beforeMap, "replace " + fromName + "->" + toName);
        return replaced;
    }

    /**
     * 建造牆壁（不需要 CommandSourceStack）
     * @param materialName 材質 ID
     * @return 放置的方塊數量
     */
    public static int doWalls(ServerPlayer player, ServerLevel level, String materialName) throws Exception {
        UUID uuid = player.getUUID();
        if (!PlayerSelectionManager.hasSelection(uuid)) {
            throw new IllegalStateException("必須先選取區域（設定 pos1 和 pos2）");
        }
        var box = PlayerSelectionManager.getSelection(uuid);
        DefaultMaterial material = Arrays.stream(DefaultMaterial.values())
                .filter(m -> m.getMaterialId().equals(materialName))
                .findFirst().orElse(null);
        if (material == null) {
            throw new IllegalArgumentException("未知材質: " + materialName);
        }
        BlockState blockState = getBlockStateForMaterial(material);
        List<BlockPos> undoPositions = new ArrayList<>();
        for (BlockPos p : box.allPositions()) {
            undoPositions.add(p.immutable());
        }
        var beforeMap = DeltaUndoManager.captureBeforeState(level, undoPositions);

        int placed = 0;
        int minX = box.min().getX(), maxX = box.max().getX();
        int minY = box.min().getY(), maxY = box.max().getY();
        int minZ = box.min().getZ(), maxZ = box.max().getZ();

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                BlockPos posZ0 = new BlockPos(x, y, minZ);
                level.setBlock(posZ0, blockState, 3);
                setRBlockMaterial(level, posZ0, material);
                placed++;
                BlockPos posZ1 = new BlockPos(x, y, maxZ);
                level.setBlock(posZ1, blockState, 3);
                setRBlockMaterial(level, posZ1, material);
                placed++;
            }
        }
        for (int z = minZ + 1; z < maxZ; z++) {
            for (int y = minY; y <= maxY; y++) {
                BlockPos posX0 = new BlockPos(minX, y, z);
                level.setBlock(posX0, blockState, 3);
                setRBlockMaterial(level, posX0, material);
                placed++;
                BlockPos posX1 = new BlockPos(maxX, y, z);
                level.setBlock(posX1, blockState, 3);
                setRBlockMaterial(level, posX1, material);
                placed++;
            }
        }
        DeltaUndoManager.commitChanges(uuid, level, beforeMap, "walls " + materialName);
        return placed;
    }

    /**
     * 實心填充材質（不需要 CommandSourceStack）
     * @param materialName 材質 ID
     * @return 填充的方塊數量
     */
    public static int doFillSolid(ServerPlayer player, ServerLevel level, String materialName) throws Exception {
        UUID uuid = player.getUUID();
        if (!PlayerSelectionManager.hasSelection(uuid)) {
            throw new IllegalStateException("必須先選取區域（設定 pos1 和 pos2）");
        }
        var box = PlayerSelectionManager.getSelection(uuid);
        if (box.volume() > FastDesignConfig.getMaxSelectionVolume()) {
            throw new IllegalStateException("選取過大！最大 " + FastDesignConfig.getMaxSelectionVolume() + " 方塊");
        }
        DefaultMaterial material = Arrays.stream(DefaultMaterial.values())
                .filter(m -> m.getMaterialId().equals(materialName))
                .findFirst().orElse(null);
        if (material == null) {
            throw new IllegalArgumentException("未知材質: " + materialName);
        }
        BlockState blockState = getBlockStateForMaterial(material);
        List<BlockPos> undoPositions = new ArrayList<>();
        for (BlockPos p : box.allPositions()) {
            undoPositions.add(p.immutable());
        }
        var beforeMap = DeltaUndoManager.captureBeforeState(level, undoPositions);

        int placed = 0;
        for (BlockPos pos : box.allPositions()) {
            BlockPos immutable = pos.immutable();
            level.setBlock(immutable, blockState, 3);
            setRBlockMaterial(level, immutable, material);
            placed++;
        }
        DeltaUndoManager.commitChanges(uuid, level, beforeMap, "solid " + materialName);
        return placed;
    }

    /**
     * 建造樓板 — 選取區域底面一層（不需要 CommandSourceStack）
     * @param materialName 材質 ID
     * @return 放置的方塊數量
     */
    public static int doSlab(ServerPlayer player, ServerLevel level, String materialName) throws Exception {
        UUID uuid = player.getUUID();
        if (!PlayerSelectionManager.hasSelection(uuid)) {
            throw new IllegalStateException("必須先選取區域（設定 pos1 和 pos2）");
        }
        var box = PlayerSelectionManager.getSelection(uuid);
        DefaultMaterial material = Arrays.stream(DefaultMaterial.values())
                .filter(m -> m.getMaterialId().equals(materialName))
                .findFirst().orElse(null);
        if (material == null) {
            throw new IllegalArgumentException("未知材質: " + materialName);
        }
        BlockState blockState = getBlockStateForMaterial(material);
        List<BlockPos> undoPositions = new ArrayList<>();
        for (BlockPos p : box.allPositions()) {
            undoPositions.add(p.immutable());
        }
        var beforeMap = DeltaUndoManager.captureBeforeState(level, undoPositions);

        int placed = 0;
        int y = box.min().getY();
        for (int x = box.min().getX(); x <= box.max().getX(); x++) {
            for (int z = box.min().getZ(); z <= box.max().getZ(); z++) {
                BlockPos pos = new BlockPos(x, y, z);
                level.setBlock(pos, blockState, 3);
                setRBlockMaterial(level, pos, material);
                placed++;
            }
        }
        DeltaUndoManager.commitChanges(uuid, level, beforeMap, "slab " + materialName);
        return placed;
    }

    /**
     * 建造拱門 — 在選取區域 XZ 平面的 X 軸方向生成半圓拱（不需要 CommandSourceStack）
     * @param materialName 材質 ID
     * @return 放置的方塊數量
     */
    public static int doArch(ServerPlayer player, ServerLevel level, String materialName) throws Exception {
        UUID uuid = player.getUUID();
        if (!PlayerSelectionManager.hasSelection(uuid)) {
            throw new IllegalStateException("必須先選取區域（設定 pos1 和 pos2）");
        }
        var box = PlayerSelectionManager.getSelection(uuid);
        DefaultMaterial material = Arrays.stream(DefaultMaterial.values())
                .filter(m -> m.getMaterialId().equals(materialName))
                .findFirst().orElse(null);
        if (material == null) {
            throw new IllegalArgumentException("未知材質: " + materialName);
        }
        BlockState blockState = getBlockStateForMaterial(material);
        List<BlockPos> undoPositions = new ArrayList<>();
        for (BlockPos p : box.allPositions()) {
            undoPositions.add(p.immutable());
        }
        var beforeMap = DeltaUndoManager.captureBeforeState(level, undoPositions);

        int placed = 0;
        int minX = box.min().getX(), maxX = box.max().getX();
        int minY = box.min().getY(), maxY = box.max().getY();
        int minZ = box.min().getZ(), maxZ = box.max().getZ();

        double spanX = maxX - minX;
        double heightY = maxY - minY;
        double cx = (minX + maxX) / 2.0;

        if (spanX == 0) {
            throw new IllegalStateException("拱門需要 X 軸至少 2 格寬");
        }

        // 在每個 Z 層生成拱門輪廓
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                // 半圓拱: y = minY + heightY * sqrt(1 - ((x-cx)/(spanX/2))^2)
                double normalizedX = (x - cx) / (spanX / 2.0);
                if (Math.abs(normalizedX) > 1.0) continue;

                double archY = minY + heightY * Math.sqrt(1.0 - normalizedX * normalizedX);
                int blockY = (int) Math.round(archY);

                // 放置拱頂方塊和兩側柱
                // 柱: 從 minY 到 blockY
                for (int y = minY; y <= Math.min(blockY, maxY); y++) {
                    // 只放邊緣（拱門輪廓）
                    boolean isEdge = (x == minX || x == maxX || y == blockY);
                    if (isEdge) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (level.getBlockState(pos).isAir()) {
                            level.setBlock(pos, blockState, 3);
                            setRBlockMaterial(level, pos, material);
                            placed++;
                        }
                    }
                }
            }
        }
        DeltaUndoManager.commitChanges(uuid, level, beforeMap, "arch " + materialName);
        return placed;
    }

    /**
     * 建造斜撐 — 在選取區域對角線生成 X 型支撐（不需要 CommandSourceStack）
     * @param materialName 材質 ID
     * @return 放置的方塊數量
     */
    public static int doBrace(ServerPlayer player, ServerLevel level, String materialName) throws Exception {
        UUID uuid = player.getUUID();
        if (!PlayerSelectionManager.hasSelection(uuid)) {
            throw new IllegalStateException("必須先選取區域（設定 pos1 和 pos2）");
        }
        var box = PlayerSelectionManager.getSelection(uuid);
        DefaultMaterial material = Arrays.stream(DefaultMaterial.values())
                .filter(m -> m.getMaterialId().equals(materialName))
                .findFirst().orElse(null);
        if (material == null) {
            throw new IllegalArgumentException("未知材質: " + materialName);
        }
        BlockState blockState = getBlockStateForMaterial(material);
        List<BlockPos> undoPositions = new ArrayList<>();
        for (BlockPos p : box.allPositions()) {
            undoPositions.add(p.immutable());
        }
        var beforeMap = DeltaUndoManager.captureBeforeState(level, undoPositions);

        int placed = 0;
        int minX = box.min().getX(), maxX = box.max().getX();
        int minY = box.min().getY(), maxY = box.max().getY();
        int minZ = box.min().getZ(), maxZ = box.max().getZ();

        int sizeX = maxX - minX;
        int sizeY = maxY - minY;

        if (sizeX == 0 || sizeY == 0) {
            throw new IllegalStateException("斜撐需要 X 和 Y 至少各 2 格");
        }

        // 在每個 Z 層生成 X 型斜撐
        for (int z = minZ; z <= maxZ; z++) {
            for (int y = minY; y <= maxY; y++) {
                double t = (double)(y - minY) / sizeY;

                // 對角線 1: 左下 → 右上
                int x1 = minX + (int) Math.round(t * sizeX);
                // 對角線 2: 右下 → 左上
                int x2 = maxX - (int) Math.round(t * sizeX);

                BlockPos pos1 = new BlockPos(x1, y, z);
                if (level.getBlockState(pos1).isAir()) {
                    level.setBlock(pos1, blockState, 3);
                    setRBlockMaterial(level, pos1, material);
                    placed++;
                }
                if (x2 != x1) {
                    BlockPos pos2 = new BlockPos(x2, y, z);
                    if (level.getBlockState(pos2).isAir()) {
                        level.setBlock(pos2, blockState, 3);
                        setRBlockMaterial(level, pos2, material);
                        placed++;
                    }
                }
            }
        }
        DeltaUndoManager.commitChanges(uuid, level, beforeMap, "brace " + materialName);
        return placed;
    }

    /**
     * 鋼筋網格（不需要 CommandSourceStack）
     * @param spacing 網格間距
     * @return 放置的方塊數量
     */
    public static int doRebarGrid(ServerPlayer player, ServerLevel level, int spacing) throws Exception {
        UUID uuid = player.getUUID();
        if (!PlayerSelectionManager.hasSelection(uuid)) {
            throw new IllegalStateException("必須先選取區域（設定 pos1 和 pos2）");
        }
        var box = PlayerSelectionManager.getSelection(uuid);
        BlockState rebarState = BRBlocks.R_REBAR.get().defaultBlockState();
        List<BlockPos> undoPositions = new ArrayList<>();
        for (BlockPos p : box.allPositions()) {
            undoPositions.add(p.immutable());
        }
        var beforeMap = DeltaUndoManager.captureBeforeState(level, undoPositions);

        int placed = 0;
        int minX = box.min().getX(), minY = box.min().getY(), minZ = box.min().getZ();
        int maxX = box.max().getX(), maxY = box.max().getY(), maxZ = box.max().getZ();

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    int relX = x - minX, relY = y - minY, relZ = z - minZ;
                    boolean isHorizontalLayer = (relY % spacing == 0);
                    boolean isOnGridLine = (relX % spacing == 0) || (relZ % spacing == 0);
                    boolean isVerticalColumn = (relX % spacing == 0) && (relZ % spacing == 0);

                    if ((isHorizontalLayer && isOnGridLine) || isVerticalColumn) {
                        BlockPos pos = new BlockPos(x, y, z);
                        level.setBlock(pos, rebarState, 3);
                        setRBlockMaterial(level, pos, DefaultMaterial.REBAR);
                        placed++;
                    }
                }
            }
        }
        DeltaUndoManager.commitChanges(uuid, level, beforeMap, "rebar-grid " + spacing);
        return placed;
    }
}
