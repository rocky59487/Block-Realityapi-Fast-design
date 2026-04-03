package com.blockreality.fastdesign.network;

import com.blockreality.api.blueprint.Blueprint;
import com.blockreality.api.blueprint.BlueprintIO;
import com.blockreality.api.command.PlayerSelectionManager;
import com.blockreality.api.placement.MultiBlockCalculator;
import com.blockreality.fastdesign.build.BuildModeState;
import com.blockreality.fastdesign.command.FdExtendedCommands;
import com.blockreality.fastdesign.command.DeltaUndoManager;
import com.blockreality.fastdesign.command.UndoManager;
import java.util.UUID;
import com.blockreality.fastdesign.sidecar.NurbsExporter;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Client → Server：Fast Design GUI 操作封包 — 開發手冊 §11.3
 *
 * 允許 FastDesignScreen 觸發伺服器端操作，無需輸入指令。
 *
 * 支援的操作：
 * - UNDO：觸發還原
 * - SAVE：儲存藍圖（附帶名稱字符串）
 * - LOAD：載入藍圖（附帶名稱字符串）
 * - EXPORT：觸發 NURBS 匯出
 * - COPY：複製選取區域到剪貼簿
 * - PASTE：在玩家位置粘貼剪貼簿
 * - CLEAR：清除選取區域方塊
 * - SET_POS1：在玩家位置設定 pos1
 * - SET_POS2：在玩家位置設定 pos2
 */
public class FdActionPacket {

    private static final Logger LOGGER = LogManager.getLogger("FD-Action");

    public enum Action {
        UNDO,
        REDO,
        SAVE,
        LOAD,
        EXPORT,
        COPY,
        PASTE,
        PASTE_CONFIRM,
        PASTE_CANCEL,
        CLEAR,
        SET_POS1,
        SET_POS2,
        // Level 3: 建築操作
        BUILD_SOLID,
        BUILD_WALLS,
        BUILD_ARCH,
        BUILD_BRACE,
        BUILD_SLAB,
        BUILD_REBAR,
        // Level 3: 編輯工具
        MIRROR,
        ROTATE,
        FILL,
        REPLACE,
        // Level 3: 進階功能
        HOLOGRAM_TOGGLE,
        OPEN_CAD,
        // Multi-block placement (Effortless Building port)
        PLACE_MULTI,
        // 選取管理
        DESELECT,
        SHIFT_SELECTION,
        RESIZE_SELECTION,
        EXCLUDE_BLOCK
    }

    private final Action action;
    private final String payload;

    /**
     * 建構封包
     *
     * @param action 操作類型
     * @param payload 可選的字符串資料（SAVE/LOAD 使用名稱，其他操作為空）
     */
    public FdActionPacket(Action action, String payload) {
        this.action = action;
        this.payload = payload != null ? payload : "";
    }

    /**
     * 簡便建構器（無資料）
     */
    public FdActionPacket(Action action) {
        this(action, "");
    }

    /**
     * 編碼封包至位元組緩衝區
     */
    public static void encode(FdActionPacket pkt, FriendlyByteBuf buf) {
        buf.writeInt(pkt.action.ordinal());
        buf.writeUtf(pkt.payload, 512);
    }

    /**
     * 從位元組緩衝區解碼封包
     */
    public static FdActionPacket decode(FriendlyByteBuf buf) {
        int actionOrdinal = buf.readInt();
        String payload = buf.readUtf(512);
        Action[] actions = Action.values();
        // Bounds check: enum ordinal must be within valid range
        if (actionOrdinal < 0 || actionOrdinal >= actions.length) {
            LOGGER.warn("Invalid FdActionPacket ordinal: {} (max={})", actionOrdinal, actions.length - 1);
            return new FdActionPacket(Action.UNDO);  // Safe default instead of throwing
        }
        return new FdActionPacket(actions[actionOrdinal], payload);
    }

    /**
     * 處理封包（伺服器側）
     *
     * 在主線程上執行所有操作，確保執行緒安全。
     */
    public static void handle(FdActionPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (!com.blockreality.api.network.BRNetwork.validateSender(player, "FdActionPacket")) return;

            ServerLevel level = player.serverLevel();
            if (level == null) {
                LOGGER.warn("FdActionPacket received but player level is null");
                return;
            }

            try {
                switch (pkt.action) {
                    case UNDO:
                        handleUndo(player, level);
                        break;
                    case REDO:
                        handleRedo(player, level);
                        break;
                    case SAVE:
                        handleSave(player, level, pkt.payload);
                        break;
                    case LOAD:
                        handleLoad(player, level, pkt.payload);
                        break;
                    case EXPORT:
                        handleExport(player, level);
                        break;
                    case COPY:
                        handleCopy(player, level);
                        break;
                    case PASTE:
                        handlePaste(player, level);
                        break;
                    case PASTE_CONFIRM:
                        handlePasteConfirm(player, level);
                        break;
                    case PASTE_CANCEL:
                        handlePasteCancel(player, level);
                        break;
                    case CLEAR:
                        handleClear(player, level);
                        break;
                    case SET_POS1:
                        handleSetPos1(player, level);
                        break;
                    case SET_POS2:
                        handleSetPos2(player, level);
                        break;
                    // Level 3: 建築操作
                    case BUILD_SOLID:
                        handleBuildSolid(player, level, pkt.payload);
                        break;
                    case BUILD_WALLS:
                        handleBuildWalls(player, level, pkt.payload);
                        break;
                    case BUILD_ARCH:
                        handleBuildArch(player, level, pkt.payload);
                        break;
                    case BUILD_BRACE:
                        handleBuildBrace(player, level, pkt.payload);
                        break;
                    case BUILD_SLAB:
                        handleBuildSlab(player, level, pkt.payload);
                        break;
                    case BUILD_REBAR:
                        handleBuildRebar(player, level, pkt.payload);
                        break;
                    // Level 3: 編輯工具
                    case MIRROR:
                        handleMirror(player, pkt.payload);
                        break;
                    case ROTATE:
                        handleRotate(player, pkt.payload);
                        break;
                    case FILL:
                        handleFill(player, level, pkt.payload);
                        break;
                    case REPLACE:
                        handleReplace(player, level, pkt.payload);
                        break;
                    // Level 3: 進階功能
                    case HOLOGRAM_TOGGLE:
                        handleHologramToggle(player);
                        break;
                    case OPEN_CAD:
                        handleOpenCad(player, level);
                        break;
                    case PLACE_MULTI:
                        handlePlaceMulti(player, level, pkt.payload);
                        break;
                    case DESELECT:
                        handleDeselect(player);
                        break;
                    case SHIFT_SELECTION:
                        handleShiftSelection(player, pkt.payload);
                        break;
                    case RESIZE_SELECTION:
                        handleResizeSelection(player, pkt.payload);
                        break;
                    case EXCLUDE_BLOCK:
                        handleExcludeBlock(player, pkt.payload);
                        break;
                    default:
                        LOGGER.warn("Unknown FdActionPacket action: {}", pkt.action);
                }
            } catch (Exception e) {
                LOGGER.error("Error handling FdActionPacket action {}", pkt.action, e);
                player.displayClientMessage(
                    Component.literal("§c[Fast Design] 操作失敗: " + e.getMessage()),
                    false
                );
            }
        });
        ctx.get().setPacketHandled(true);
    }

    /**
     * 還原最後的操作 — 優先使用 DeltaUndoManager，回退到舊版 UndoManager
     */
    private static void handleUndo(ServerPlayer player, ServerLevel level) {
        UUID uuid = player.getUUID();
        // 優先嘗試 Delta undo（v2.0 差異引擎）
        int blocksRestored = DeltaUndoManager.getUndoStackSize(uuid) > 0
            ? DeltaUndoManager.undo(uuid, level)
            : UndoManager.undo(uuid, level);
        if (blocksRestored > 0) {
            String desc = DeltaUndoManager.peekUndoDescription(uuid);
            player.displayClientMessage(
                Component.literal("§a[Fast Design] 已還原 " + blocksRestored + " 個方塊"),
                false
            );
        } else {
            player.displayClientMessage(
                Component.literal("§c[Fast Design] 沒有可還原的操作"),
                false
            );
        }
    }

    /**
     * 重做最近被撤銷的操作 — v2.0 新增
     */
    private static void handleRedo(ServerPlayer player, ServerLevel level) {
        UUID uuid = player.getUUID();
        int blocksRedone = DeltaUndoManager.redo(uuid, level);
        if (blocksRedone > 0) {
            player.displayClientMessage(
                Component.literal("§a[Fast Design] 已重做 " + blocksRedone + " 個方塊"),
                false
            );
        } else {
            player.displayClientMessage(
                Component.literal("§c[Fast Design] 沒有可重做的操作"),
                false
            );
        }
    }

    /**
     * 取消選取 — 清除玩家的選取區域並同步客戶端
     */
    private static void handleDeselect(ServerPlayer player) {
        UUID uuid = player.getUUID();
        if (!PlayerSelectionManager.hasSelection(uuid)) {
            player.displayClientMessage(
                Component.literal("§c[Fast Design] 沒有選取區域可以取消"),
                false
            );
            return;
        }
        PlayerSelectionManager.clear(uuid);
        com.blockreality.fastdesign.command.SelectionExclusionManager.clear(uuid);
        FdNetwork.CHANNEL.send(
            PacketDistributor.PLAYER.with(() -> player),
            FdSelectionSyncPacket.clearSelection()
        );
        player.displayClientMessage(
            Component.literal("§a[Fast Design] 選取區域已清除"),
            false
        );
    }

    /**
     * 上下平移選取區域 — payload: "up" 或 "down"
     */
    private static void handleShiftSelection(ServerPlayer player, String payload) {
        UUID uuid = player.getUUID();
        if (!PlayerSelectionManager.hasSelection(uuid)) {
            player.displayClientMessage(
                Component.literal("§c[Fast Design] 沒有選取區域可以移動"), false);
            return;
        }
        int dx = 0, dy = 0, dz = 0;
        switch (payload) {
            case "up"    -> dy = 1;
            case "down"  -> dy = -1;
            case "north" -> dz = -1;
            case "south" -> dz = 1;
            case "east"  -> dx = 1;
            case "west"  -> dx = -1;
            default -> { return; }
        }
        BlockPos p1 = PlayerSelectionManager.getPos1(uuid);
        BlockPos p2 = PlayerSelectionManager.getPos2(uuid);
        if (p1 == null || p2 == null) return; // ★ NPE 防護
        PlayerSelectionManager.setPos1(uuid, p1.offset(dx, dy, dz));
        PlayerSelectionManager.setPos2(uuid, p2.offset(dx, dy, dz));
        // 排除集也跟著偏移
        var excluded = com.blockreality.fastdesign.command.SelectionExclusionManager.getExcluded(uuid);
        if (!excluded.isEmpty()) {
            com.blockreality.fastdesign.command.SelectionExclusionManager.clear(uuid);
            for (BlockPos ep : excluded) {
                com.blockreality.fastdesign.command.SelectionExclusionManager.toggle(uuid, ep.offset(dx, dy, dz));
            }
        }
        syncSelection(player, uuid);
    }

    /**
     * 拖動選取邊界調整大小 — payload: "face,value"
     * face: x+, x-, y+, y-, z+, z-
     * value: 新的座標值
     */
    private static void handleResizeSelection(ServerPlayer player, String payload) {
        UUID uuid = player.getUUID();
        if (!PlayerSelectionManager.hasSelection(uuid)) {
            player.displayClientMessage(
                Component.literal("§c[Fast Design] 沒有選取區域可以調整"), false);
            return;
        }
        String[] parts = payload.split(",");
        if (parts.length != 2) return;
        try {
            String face = parts[0];
            int value = Integer.parseInt(parts[1]);

            var sel = PlayerSelectionManager.getSelection(uuid);
            if (sel == null) return; // ★ NPE ��護
            int minX = sel.min().getX(), minY = sel.min().getY(), minZ = sel.min().getZ();
            int maxX = sel.max().getX(), maxY = sel.max().getY(), maxZ = sel.max().getZ();

            switch (face) {
                case "x-" -> minX = Math.min(value, maxX);
                case "x+" -> maxX = Math.max(value, minX);
                case "y-" -> minY = Math.min(value, maxY);
                case "y+" -> maxY = Math.max(value, minY);
                case "z-" -> minZ = Math.min(value, maxZ);
                case "z+" -> maxZ = Math.max(value, minZ);
                default -> { return; }
            }

            PlayerSelectionManager.setPos1(uuid, new BlockPos(minX, minY, minZ));
            PlayerSelectionManager.setPos2(uuid, new BlockPos(maxX, maxY, maxZ));
            syncSelection(player, uuid);
        } catch (NumberFormatException ignored) {}
    }

    /**
     * 同步選取區域至客戶端（共用工具方法）
     */
    private static void syncSelection(ServerPlayer player, UUID uuid) {
        var sel = PlayerSelectionManager.getSelection(uuid);
        if (sel == null) return; // ★ NPE 防護
        FdNetwork.CHANNEL.send(
            PacketDistributor.PLAYER.with(() -> player),
            new FdSelectionSyncPacket(sel.min(), sel.max())
        );
    }

    /**
     * 個別排除方塊 — payload: "x,y,z"
     * 切換指定位置的排除狀態（排除↔恢復）
     */
    private static void handleExcludeBlock(ServerPlayer player, String payload) {
        UUID uuid = player.getUUID();
        if (!PlayerSelectionManager.hasSelection(uuid)) {
            player.displayClientMessage(
                Component.literal("§c[Fast Design] 沒有選取區域"), false);
            return;
        }
        String[] parts = payload.split(",");
        if (parts.length != 3) return;
        try {
            int x = Integer.parseInt(parts[0]);
            int y = Integer.parseInt(parts[1]);
            int z = Integer.parseInt(parts[2]);
            BlockPos pos = new BlockPos(x, y, z);

            // 確認在選取範圍內
            var sel = PlayerSelectionManager.getSelection(uuid);
            if (sel == null) return; // ★ NPE 防護
            if (pos.getX() < sel.min().getX() || pos.getX() > sel.max().getX()
             || pos.getY() < sel.min().getY() || pos.getY() > sel.max().getY()
             || pos.getZ() < sel.min().getZ() || pos.getZ() > sel.max().getZ()) {
                player.displayClientMessage(
                    Component.literal("§c[Fast Design] 該方塊不在選取範圍內"), false);
                return;
            }

            boolean nowExcluded = com.blockreality.fastdesign.command.SelectionExclusionManager.toggle(uuid, pos);
            int total = com.blockreality.fastdesign.command.SelectionExclusionManager.getExcludedCount(uuid);
            player.displayClientMessage(
                Component.literal(nowExcluded
                    ? "§e[Fast Design] 已排除方塊 " + pos.toShortString() + " §7(共 " + total + " 個排除)"
                    : "§a[Fast Design] 已恢復方塊 " + pos.toShortString() + " §7(共 " + total + " 個排除)"),
                true);
        } catch (NumberFormatException ignored) {}
    }

    /**
     * 儲存藍圖
     */
    private static void handleSave(ServerPlayer player, ServerLevel level, String name) {
        if (name == null || name.trim().isEmpty()) {
            player.displayClientMessage(
                Component.literal("§c[Fast Design] 藍圖名稱不能為空"),
                false
            );
            return;
        }

        try {
            PlayerSelectionManager.SelectionBox selection =
                PlayerSelectionManager.getSelection(player.getUUID());

            BlueprintIO.save(level, selection.min(), selection.max(), name, player.getName().getString());

            player.displayClientMessage(
                Component.literal("§a[Fast Design] 藍圖 \"" + name + "\" 已儲存"),
                false
            );
        } catch (IllegalStateException e) {
            player.displayClientMessage(
                Component.literal("§c[Fast Design] 必須先選取區域（設定 pos1 和 pos2）"),
                false
            );
        } catch (Exception e) {
            LOGGER.error("Failed to save blueprint: {}", name, e);
            player.displayClientMessage(
                Component.literal("§c[Fast Design] 儲存藍圖失敗: " + e.getMessage()),
                false
            );
        }
    }

    /**
     * 載入藍圖
     */
    private static void handleLoad(ServerPlayer player, ServerLevel level, String name) {
        if (!requireBuildPermission(player)) return; // ★ A-10 權限檢查
        if (name == null || name.trim().isEmpty()) {
            player.displayClientMessage(
                Component.literal("§c[Fast Design] 藍圖名稱不能為空"),
                false
            );
            return;
        }

        try {
            var bp = BlueprintIO.load(name);
            BlockPos origin = player.blockPosition();
            int placed = BlueprintIO.paste(level, bp, origin);

            player.displayClientMessage(
                Component.literal("§a[Fast Design] 藍圖 \"" + name + "\" 已載入 (" + placed + " 個方塊)"),
                false
            );
        } catch (Exception e) {
            LOGGER.error("Failed to load blueprint: {}", name, e);
            player.displayClientMessage(
                Component.literal("§c[Fast Design] 載入藍圖失敗: " + e.getMessage()),
                false
            );
        }
    }

    /**
     * 觸發 NURBS 匯出
     */
    private static void handleExport(ServerPlayer player, ServerLevel level) {
        try {
            PlayerSelectionManager.SelectionBox selection =
                PlayerSelectionManager.getSelection(player.getUUID());
            if (selection == null) {
                player.displayClientMessage(
                    Component.literal("§c[Fast Design] 沒有選取區域可匯出"), false);
                return;
            }

            var result = NurbsExporter.export(level, selection, NurbsExporter.ExportOptions.defaults());

            player.displayClientMessage(
                Component.literal("§a[Fast Design] NURBS 匯出完成"),
                false
            );
        } catch (IllegalStateException e) {
            player.displayClientMessage(
                Component.literal("§c[Fast Design] 必須先選取區域（設定 pos1 和 pos2）"),
                false
            );
        } catch (Exception e) {
            LOGGER.error("Failed to export NURBS", e);
            player.displayClientMessage(
                Component.literal("§c[Fast Design] 匯出失敗: " + e.getMessage()),
                false
            );
        }
    }

    /**
     * 複製選取區域到剪貼簿
     */
    private static void handleCopy(ServerPlayer player, ServerLevel level) {
        try {
            int count = FdExtendedCommands.doCopy(player, level);

            player.displayClientMessage(
                Component.literal("§a[Fast Design] 已複製 " + count + " 個方塊到剪貼簿"),
                false
            );
        } catch (Exception e) {
            LOGGER.error("Failed to copy selection", e);
            player.displayClientMessage(
                Component.literal("§c[Fast Design] 複製失敗: " + e.getMessage()),
                false
            );
        }
    }

    /**
     * 在玩家位置粘貼剪貼簿內容（進入預覽模式）
     *
     * ★ v4 spec §6.2 (Axiom) + §7.2 (SimpleBuilding)：
     *   預覽跟隨準心移動，右鍵直接放置，不需面板確認。
     */
    private static void handlePaste(ServerPlayer player, ServerLevel level) {
        if (!requireBuildPermission(player)) return; // ★ A-10 權限檢查
        try {
            UUID uuid = player.getUUID();
            Blueprint bp = FdExtendedCommands.getClipboard(uuid);
            if (bp == null) {
                player.displayClientMessage(
                    Component.literal("§c[FD] 剪貼簿為空！請先複製"), false);
                return;
            }
            // Store as pending (PastePlacePacket will read this)
            FdExtendedCommands.setPendingPaste(uuid, bp);
            BlockPos origin = player.blockPosition();
            int blockCount = bp.getBlocks().size();

            // 發送 S→C 封包，讓 client 顯示 ghost preview
            Map<BlockPos, BlockState> previewBlocks = new HashMap<>();
            for (Blueprint.BlueprintBlock bb : bp.getBlocks()) {
                if (bb.getBlockState() != null && !bb.getBlockState().isAir()) {
                    previewBlocks.put(new BlockPos(bb.getRelX(), bb.getRelY(), bb.getRelZ()),
                                      bb.getBlockState());
                }
            }
            FdNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new PastePreviewSyncPacket(origin, previewBlocks));

            // ★ v4: 交互提示 — 右鍵放置，ESC 取消
            player.displayClientMessage(
                Component.literal(String.format(
                    "§6[FD] §f預覽: §a%d §f個方塊 — §e右鍵§f放置 | §c面板取消",
                    blockCount)), false);
        } catch (Exception e) {
            LOGGER.error("Failed to show paste preview", e);
            player.displayClientMessage(
                Component.literal("§c[FD] 預覽失敗: " + e.getMessage()), false);
        }
    }

    /**
     * 確認粘貼並放置方塊
     */
    private static void handlePasteConfirm(ServerPlayer player, ServerLevel level) {
        if (!requireBuildPermission(player)) return;
        try {
            UUID uuid = player.getUUID();
            int placed = FdExtendedCommands.confirmPaste(player, level);

            // 清除 client 端 ghost preview
            FdNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new PastePreviewSyncPacket());

            player.displayClientMessage(
                Component.literal("§a[FD] 已放置 " + placed + " 個方塊"), false);
        } catch (Exception e) {
            LOGGER.error("Failed to confirm paste", e);
            player.displayClientMessage(
                Component.literal("§c[FD] 放置失敗: " + e.getMessage()), false);
        }
    }

    /**
     * 取消粘貼預覽
     */
    private static void handlePasteCancel(ServerPlayer player, ServerLevel level) {
        FdExtendedCommands.cancelPaste(player.getUUID());

        // 清除 client 端 ghost preview
        FdNetwork.CHANNEL.send(
            PacketDistributor.PLAYER.with(() -> player),
            new PastePreviewSyncPacket());

        player.displayClientMessage(
            Component.literal("§7[FD] 已取消貼上"), false);
    }

    /**
     * 清除選取區域的所有方塊
     */
    private static void handleClear(ServerPlayer player, ServerLevel level) {
        if (!requireBuildPermission(player)) return; // ★ A-10 權限檢查
        try {
            int cleared = FdExtendedCommands.doClear(player, level);

            player.displayClientMessage(
                Component.literal("§a[Fast Design] 已清除 " + cleared + " 個方塊"),
                false
            );
        } catch (Exception e) {
            LOGGER.error("Failed to clear selection", e);
            player.displayClientMessage(
                Component.literal("§c[Fast Design] 清除失敗: " + e.getMessage()),
                false
            );
        }
    }

    /**
     * 在玩家位置設定 pos1
     */
    private static void handleSetPos1(ServerPlayer player, ServerLevel level) {
        try {
            BlockPos pos = player.blockPosition();
            PlayerSelectionManager.setPos1(player.getUUID(), pos);

            BlockPos pos2 = PlayerSelectionManager.getPos2(player.getUUID());
            if (pos2 != null) {
                FdNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new FdSelectionSyncPacket(pos, pos2)
                );
            }

            player.displayClientMessage(
                Component.literal("§a[Fast Design] pos1 已設定為 " + pos),
                false
            );
        } catch (Exception e) {
            LOGGER.error("Failed to set pos1", e);
            player.displayClientMessage(
                Component.literal("§c[Fast Design] 設定 pos1 失敗: " + e.getMessage()),
                false
            );
        }
    }

    /**
     * 在玩家位置設定 pos2
     */
    private static void handleSetPos2(ServerPlayer player, ServerLevel level) {
        try {
            BlockPos pos = player.blockPosition();
            PlayerSelectionManager.setPos2(player.getUUID(), pos);

            BlockPos pos1 = PlayerSelectionManager.getPos1(player.getUUID());
            if (pos1 != null) {
                FdNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new FdSelectionSyncPacket(pos1, pos)
                );
            }

            player.displayClientMessage(
                Component.literal("§a[Fast Design] pos2 已設定為 " + pos),
                false
            );
        } catch (Exception e) {
            LOGGER.error("Failed to set pos2", e);
            player.displayClientMessage(
                Component.literal("§c[Fast Design] 設定 pos2 失敗: " + e.getMessage()),
                false
            );
        }
    }

    // ═══════════════ Level 3: Payload 解析工具 ═══════════════

    /**
     * 從 payload 字串解碼材質 ID
     * payload 格式: "material=concrete,spacing=4,block=minecraft:stone"
     */
    private static String parseMaterialFromPayload(String payload) {
        if (payload == null || payload.isEmpty()) return "concrete";
        for (String part : payload.split(",")) {
            if (part.startsWith("material=")) {
                return part.substring("material=".length());
            }
        }
        return "concrete";
    }

    /**
     * 從 payload 字串解碼鋼筋間距
     */
    private static int parseSpacingFromPayload(String payload) {
        if (payload == null || payload.isEmpty()) return 4;
        for (String part : payload.split(",")) {
            if (part.startsWith("spacing=")) {
                try {
                    return Integer.parseInt(part.substring("spacing=".length()));
                } catch (NumberFormatException e) {
                    return 4;
                }
            }
        }
        return 4;
    }

    /**
     * 從 payload 字串解碼自訂方塊 ID
     */
    private static String parseCustomBlockFromPayload(String payload) {
        if (payload == null || payload.isEmpty()) return "minecraft:stone";
        for (String part : payload.split(",")) {
            if (part.startsWith("block=")) {
                return part.substring("block=".length());
            }
        }
        return "minecraft:stone";
    }

    private static void handleBuildSolid(ServerPlayer player, ServerLevel level, String payload) {
        if (!requireBuildPermission(player)) return; // ★ A-10 權限檢查
        try {
            String materialId = parseMaterialFromPayload(payload);
            int count = FdExtendedCommands.doFillSolid(player, level, materialId);
            player.displayClientMessage(
                Component.literal("§a[Fast Design] 實心填充 " + count + " 個方塊 (" + materialId + ")"), false);
        } catch (Exception e) {
            LOGGER.error("BUILD_SOLID failed", e);
            player.displayClientMessage(
                Component.literal("§c[Fast Design] 實心填充失敗: " + e.getMessage()), false);
        }
    }

    private static void handleBuildWalls(ServerPlayer player, ServerLevel level, String payload) {
        if (!requireBuildPermission(player)) return; // ★ A-10 權限檢查
        try {
            String materialId = parseMaterialFromPayload(payload);
            int count = FdExtendedCommands.doWalls(player, level, materialId);
            player.displayClientMessage(
                Component.literal("§a[Fast Design] 建造牆壁 " + count + " 個方塊 (" + materialId + ")"), false);
        } catch (Exception e) {
            LOGGER.error("BUILD_WALLS failed", e);
            player.displayClientMessage(
                Component.literal("§c[Fast Design] 建造牆壁失敗: " + e.getMessage()), false);
        }
    }

    private static void handleBuildArch(ServerPlayer player, ServerLevel level, String payload) {
        if (!requireBuildPermission(player)) return; // ★ A-10 權限檢查
        try {
            String materialId = parseMaterialFromPayload(payload);
            int count = FdExtendedCommands.doArch(player, level, materialId);
            player.displayClientMessage(
                Component.literal("§a[Fast Design] 建造拱門 " + count + " 個方塊 (" + materialId + ")"), false);
        } catch (Exception e) {
            LOGGER.error("BUILD_ARCH failed", e);
            player.displayClientMessage(
                Component.literal("§c[Fast Design] 建造拱門失敗: " + e.getMessage()), false);
        }
    }

    private static void handleBuildBrace(ServerPlayer player, ServerLevel level, String payload) {
        if (!requireBuildPermission(player)) return; // ★ A-10 權限檢查
        try {
            String materialId = parseMaterialFromPayload(payload);
            int count = FdExtendedCommands.doBrace(player, level, materialId);
            player.displayClientMessage(
                Component.literal("§a[Fast Design] 建造斜撐 " + count + " 個方塊 (" + materialId + ")"), false);
        } catch (Exception e) {
            LOGGER.error("BUILD_BRACE failed", e);
            player.displayClientMessage(
                Component.literal("§c[Fast Design] 建造斜撐失敗: " + e.getMessage()), false);
        }
    }

    private static void handleBuildSlab(ServerPlayer player, ServerLevel level, String payload) {
        if (!requireBuildPermission(player)) return; // ★ A-10 權限檢查
        try {
            String materialId = parseMaterialFromPayload(payload);
            int count = FdExtendedCommands.doSlab(player, level, materialId);
            player.displayClientMessage(
                Component.literal("§a[Fast Design] 建造樓板 " + count + " 個方塊 (" + materialId + ")"), false);
        } catch (Exception e) {
            LOGGER.error("BUILD_SLAB failed", e);
            player.displayClientMessage(
                Component.literal("§c[Fast Design] 建造樓板失敗: " + e.getMessage()), false);
        }
    }

    private static void handleBuildRebar(ServerPlayer player, ServerLevel level, String payload) {
        if (!requireBuildPermission(player)) return; // ★ A-10 權限檢查
        try {
            int spacing = parseSpacingFromPayload(payload);
            // 伺服器端驗證鋼筋間距 (防止封包竄改繞過客戶端限制)
            if (spacing < 1 || spacing > 16) {
                player.displayClientMessage(
                    Component.literal("§c[Fast Design] 鋼筋間距必須在 1-16 之間"), false);
                return;
            }
            // ★ FIX CFG-1: 當間距超過 API 物理引擎的 RC 融合偵測範圍時，警告玩家
            int physicsMax = com.blockreality.api.config.BRConfig.INSTANCE.rcFusionRebarSpacingMax.get();
            if (spacing > physicsMax) {
                player.displayClientMessage(
                    Component.literal(String.format(
                        "§e[Fast Design] 警告：間距 %d 超過 RC 融合偵測範圍 (%d)，鋼筋將無法與混凝土融合",
                        spacing, physicsMax)), false);
            }
            int count = FdExtendedCommands.doRebarGrid(player, level, spacing);
            player.displayClientMessage(
                Component.literal("§a[Fast Design] 鋼筋網格 " + count + " 根 (間距=" + spacing + ")"), false);
        } catch (Exception e) {
            LOGGER.error("BUILD_REBAR failed", e);
            player.displayClientMessage(
                Component.literal("§c[Fast Design] 鋼筋網格失敗: " + e.getMessage()), false);
        }
    }

    // ═══════════════ Level 3: 編輯工具 Handlers ═══════════════

    private static void handleMirror(ServerPlayer player, String payload) {
        try {
            // payload 格式: "axis=x" 或直接 "x"
            String axis = "x"; // 預設 X 軸
            if (payload != null && !payload.isEmpty()) {
                if (payload.contains("axis=")) {
                    axis = payload.substring(payload.indexOf("axis=") + 5, payload.indexOf("axis=") + 6);
                } else if (payload.length() == 1) {
                    axis = payload;
                }
            }
            int count = FdExtendedCommands.doMirror(player, axis);
            player.displayClientMessage(
                Component.literal("§a[Fast Design] 鏡像剪貼簿 (" + axis.toUpperCase() + " 軸)"), false);
        } catch (Exception e) {
            LOGGER.error("MIRROR failed", e);
            player.displayClientMessage(
                Component.literal("§c[Fast Design] 鏡像失敗: " + e.getMessage()), false);
        }
    }

    private static void handleRotate(ServerPlayer player, String payload) {
        try {
            int degrees = 90; // 預設 90°
            if (payload != null && !payload.isEmpty()) {
                try {
                    degrees = Integer.parseInt(payload.replaceAll("[^0-9]", ""));
                } catch (NumberFormatException ignored) {}
            }
            int count = FdExtendedCommands.doRotate(player, degrees);
            player.displayClientMessage(
                Component.literal("§a[Fast Design] 旋轉剪貼簿 " + degrees + "°"), false);
        } catch (Exception e) {
            LOGGER.error("ROTATE failed", e);
            player.displayClientMessage(
                Component.literal("§c[Fast Design] 旋轉失敗: " + e.getMessage()), false);
        }
    }

    private static void handleFill(ServerPlayer player, ServerLevel level, String payload) {
        if (!requireBuildPermission(player)) return; // ★ A-10 權限檢查
        try {
            // payload 包含材質資訊，轉換為方塊 ID
            String materialId = parseMaterialFromPayload(payload);
            String customBlock = parseCustomBlockFromPayload(payload);
            String blockId = materialId.equals("custom") ? customBlock : materialId;
            int count = FdExtendedCommands.doFill(player, level, blockId);
            player.displayClientMessage(
                Component.literal("§a[Fast Design] 填充 " + count + " 個方塊 (" + blockId + ")"), false);
        } catch (Exception e) {
            LOGGER.error("FILL failed", e);
            player.displayClientMessage(
                Component.literal("§c[Fast Design] 填充失敗: " + e.getMessage()), false);
        }
    }

    private static void handleReplace(ServerPlayer player, ServerLevel level, String payload) {
        if (!requireBuildPermission(player)) return; // ★ A-10 權限檢查
        try {
            // payload 格式: "from=stone,to=concrete" 或 material payload
            String from = "minecraft:stone";
            String to = parseMaterialFromPayload(payload);

            for (String part : payload.split(",")) {
                if (part.startsWith("from=")) from = part.substring(5);
                if (part.startsWith("to=")) to = part.substring(3);
            }

            int count = FdExtendedCommands.doReplace(player, level, from, to);
            player.displayClientMessage(
                Component.literal("§a[Fast Design] 替換 " + count + " 個方塊"), false);
        } catch (Exception e) {
            LOGGER.error("REPLACE failed", e);
            player.displayClientMessage(
                Component.literal("§c[Fast Design] 替換失敗: " + e.getMessage()), false);
        }
    }

    // ═══════════════ Level 3: 進階功能 Handlers ═══════════════

    private static void handleHologramToggle(ServerPlayer player) {
        // 全息切換是客戶端操作，但透過封包確保合法性
        // 傳送一個空的同步封包通知客戶端切換
        player.displayClientMessage(
            Component.literal("§a[Fast Design] 全息投影已切換"), false);
    }

    private static void handleOpenCad(ServerPlayer player, ServerLevel level) {
        try {
            if (!PlayerSelectionManager.hasSelection(player.getUUID())) {
                player.displayClientMessage(
                    Component.literal("§c[Fast Design] 必須先選取區域才能開啟 CAD 檢視"), false);
                return;
            }
            var box = PlayerSelectionManager.getSelection(player.getUUID());
            if (box == null) return; // ★ NPE 防護（hasSelection 與 getSelection 間可能競態）
            var bp = BlueprintIO.captureBlueprint(level, box.min(), box.max(),
                "selection", player.getName().getString());
            var nbt = com.blockreality.api.blueprint.BlueprintNBT.write(bp);

            FdNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new OpenCadScreenPacket(nbt));
            player.displayClientMessage(
                Component.literal("§a[Fast Design] 開啟 CAD 檢視 (" +
                    box.sizeX() + "×" + box.sizeY() + "×" + box.sizeZ() + ")"), false);
        } catch (Exception e) {
            LOGGER.error("OPEN_CAD failed", e);
            player.displayClientMessage(
                Component.literal("§c[Fast Design] 開啟 CAD 失敗: " + e.getMessage()), false);
        }
    }

    // ═══════════════ Multi-Block Placement Handler ═══════════════

    /**
     * 處理多方塊放置 — 從客戶端 BuildModeState 發送的 PLACE_MULTI 封包。
     *
     * Payload 格式: "mode=LINE,ax=10,ay=64,az=20,bx=15,by=64,bz=25[,mx=...,my=...,mz=...]"
     * 伺服器端重新計算所有位置並放置方塊，防止客戶端作弊。
     */
    private static void handlePlaceMulti(ServerPlayer player, ServerLevel level, String payload) {
        if (!requireBuildPermission(player)) return; // ★ A-10 權限檢查
        try {
            if (!com.blockreality.fastdesign.config.FastDesignConfig.isWandEnabled()) {
                player.displayClientMessage(
                    Component.literal("§c[Fast Design] 建造工具已停用"), false);
                return;
            }

            BuildModeState.DecodedPayload decoded = BuildModeState.decodePayload(payload);

            if (decoded.pos1() == null || decoded.pos2() == null) {
                player.displayClientMessage(
                    Component.literal("§c[Fast Design] 多方塊放置缺少錨點座標"), false);
                return;
            }

            // 伺服器端重新計算位置 (anti-cheat: 不信任客戶端的位置列表)
            List<BlockPos> positions = MultiBlockCalculator.calculate(
                decoded.mode(), decoded.pos1(), decoded.pos2(), decoded.mirror());

            if (positions.isEmpty()) {
                player.displayClientMessage(
                    Component.literal("§c[Fast Design] 計算結果為空"), false);
                return;
            }

            // 限制最大放置數量 (防止濫用)
            int maxBlocks = 10000;
            if (positions.size() > maxBlocks) {
                player.displayClientMessage(
                    Component.literal("§c[Fast Design] 超過最大放置數量 (" + maxBlocks + ")"), false);
                return;
            }

            // 取得玩家手持方塊 (main hand 或 off hand)
            BlockState placeState = getHeldBlockState(player);
            if (placeState == null) {
                player.displayClientMessage(
                    Component.literal("§c[Fast Design] 需要手持方塊才能使用多方塊放置"), false);
                return;
            }

            // Delta undo: 捕獲操作前狀態
            List<BlockPos> validPositions = new java.util.ArrayList<>();
            for (BlockPos pos : positions) {
                if (level.isInWorldBounds(pos)) validPositions.add(pos);
            }
            var beforeMap = DeltaUndoManager.captureBeforeState(level, validPositions);

            // 放置方塊
            int placed = 0;
            for (BlockPos pos : validPositions) {
                level.setBlock(pos, placeState, Block.UPDATE_ALL);
                placed++;
            }

            // Delta undo: 記錄操作後狀態
            DeltaUndoManager.commitChanges(player.getUUID(), level, beforeMap, "multi-block place");

            player.displayClientMessage(
                Component.literal("§a[Fast Design] 已放置 " + placed + " 個方塊（" + decoded.mode() + "）"),
                false);

        } catch (Exception e) {
            LOGGER.error("Failed to handle multi-block placement", e);
            player.displayClientMessage(
                Component.literal("§c[Fast Design] 多方塊放置失敗: " + e.getMessage()),
                false);
        }
    }

    /**
     * 檢查玩家是否有建造權限（OP 或生存非旁觀模式）。
     * 若無權限則發送錯誤訊息並回傳 false。
     */
    private static boolean requireBuildPermission(ServerPlayer player) {
        if (player.isSpectator()) {
            player.displayClientMessage(
                Component.literal("§c[Fast Design] 旁觀模式無法使用建造功能"), false);
            return false;
        }
        return true;
    }

    /**
     * 取得玩家手持的方塊狀態。
     * 優先 main hand，其次 off hand，都不是方塊則回傳 null。
     */
    private static BlockState getHeldBlockState(ServerPlayer player) {
        var mainItem = player.getMainHandItem();
        if (mainItem.getItem() instanceof net.minecraft.world.item.BlockItem bi) {
            return bi.getBlock().defaultBlockState();
        }
        var offItem = player.getOffhandItem();
        if (offItem.getItem() instanceof net.minecraft.world.item.BlockItem bi) {
            return bi.getBlock().defaultBlockState();
        }
        return null;
    }
}