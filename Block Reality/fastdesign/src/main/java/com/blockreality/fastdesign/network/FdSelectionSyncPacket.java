package com.blockreality.fastdesign.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server → Client：同步玩家的選取區域 — 開發手冊 §11.2
 */
public class FdSelectionSyncPacket {

    private final BlockPos min;
    private final BlockPos max;
    private final boolean hasSelection;

    public FdSelectionSyncPacket(BlockPos min, BlockPos max) {
        this.min = min;
        this.max = max;
        this.hasSelection = true;
    }

    private FdSelectionSyncPacket() {
        this.min = BlockPos.ZERO;
        this.max = BlockPos.ZERO;
        this.hasSelection = false;
    }

    public static FdSelectionSyncPacket clearSelection() {
        return new FdSelectionSyncPacket();
    }

    public static void encode(FdSelectionSyncPacket pkt, FriendlyByteBuf buf) {
        buf.writeBoolean(pkt.hasSelection);
        if (pkt.hasSelection) {
            buf.writeBlockPos(pkt.min);
            buf.writeBlockPos(pkt.max);
        }
    }

    public static FdSelectionSyncPacket decode(FriendlyByteBuf buf) {
        boolean has = buf.readBoolean();
        if (has) {
            BlockPos min = buf.readBlockPos();
            BlockPos max = buf.readBlockPos();
            return new FdSelectionSyncPacket(min, max);
        }
        return new FdSelectionSyncPacket();
    }

    public static void handle(FdSelectionSyncPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                if (pkt.hasSelection) {
                    com.blockreality.fastdesign.client.ClientSelectionHolder.update(pkt.min, pkt.max);
                } else {
                    com.blockreality.fastdesign.client.ClientSelectionHolder.clear();
                }
            });
        });
        ctx.get().setPacketHandled(true);
    }
}
