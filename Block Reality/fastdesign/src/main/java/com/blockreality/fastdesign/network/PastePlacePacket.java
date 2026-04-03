package com.blockreality.fastdesign.network;

import com.blockreality.api.blueprint.Blueprint;
import com.blockreality.api.blueprint.BlueprintIO;
import com.blockreality.fastdesign.command.FdExtendedCommands;
import com.blockreality.fastdesign.command.DeltaUndoManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * C→S 封包：玩家在預覽位置右鍵放置 paste（Axiom/SimpleBuilding 風格交互）。
 *
 * 行為：
 *   1. Client 端 GhostPreviewRenderer 追蹤玩家準心 → 計算放置位置
 *   2. 玩家右鍵 → 發送此封包（攜帶目標 BlockPos）
 *   3. Server 端讀取 pendingPaste 藍圖，在目標位置放置
 *   4. 放置成功後發送 PastePreviewSyncPacket(active=false) 清除預覽
 *
 * 與舊 PASTE_CONFIRM 的區別：
 *   - PASTE_CONFIRM 在原始 origin 位置放置
 *   - PastePlacePacket 在玩家準心指向的位置放置（即 GhostPreview 的當前位置）
 */
public class PastePlacePacket {

    private static final Logger LOGGER = LogManager.getLogger("FD-PastePlace");

    private final BlockPos targetPos;

    public PastePlacePacket(BlockPos targetPos) {
        this.targetPos = targetPos;
    }

    // ─── 序列化 ───

    public static void encode(PastePlacePacket pkt, FriendlyByteBuf buf) {
        buf.writeBlockPos(pkt.targetPos);
    }

    public static PastePlacePacket decode(FriendlyByteBuf buf) {
        return new PastePlacePacket(buf.readBlockPos());
    }

    // ─── 處理（SERVER） ───

    public static void handle(PastePlacePacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (!com.blockreality.api.network.BRNetwork.validateSender(player, "PastePlacePacket")) return;
            ServerLevel level = player.serverLevel();

            // 權限檢查
            if (player.isSpectator()) {
                player.displayClientMessage(
                    Component.literal("§c[FD] 旁觀模式無法放置"), true);
                return;
            }

            try {
                java.util.UUID uuid = player.getUUID();
                Blueprint bp = FdExtendedCommands.getPendingPaste(uuid);
                if (bp == null) {
                    player.displayClientMessage(
                        Component.literal("§c[FD] 沒有待放置的預覽"), true);
                    return;
                }

                // 記錄 Undo: 先捕獲放置前的狀態
                List<BlockPos> affectedPositions = new ArrayList<>();
                for (Blueprint.BlueprintBlock b : bp.getBlocks()) {
                    affectedPositions.add(pkt.targetPos.offset(b.getRelX(), b.getRelY(), b.getRelZ()));
                }
                Map<BlockPos, DeltaUndoManager.BlockChangeRecord> beforeMap =
                    DeltaUndoManager.captureBeforeState(level, affectedPositions);

                // 在玩家指定的目標位置放置
                int placed = BlueprintIO.paste(level, bp, pkt.targetPos);

                // 記錄 Undo: 提交差異
                DeltaUndoManager.commitChanges(uuid, level, beforeMap,
                    "paste at " + pkt.targetPos.toShortString());

                // 清除 pending paste
                FdExtendedCommands.clearPendingPaste(uuid);

                // 清除 client 端 ghost preview
                FdNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new PastePreviewSyncPacket());

                player.displayClientMessage(
                    Component.literal("§a[FD] 已放置 " + placed + " 個方塊"),
                    true);
            } catch (Exception e) {
                LOGGER.error("Failed to paste at {}", pkt.targetPos, e);
                player.displayClientMessage(
                    Component.literal("§c[FD] 放置失敗: " + e.getMessage()), true);
            }
        });
        ctx.setPacketHandled(true);
    }
}
