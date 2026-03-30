package com.blockreality.fastdesign.network;

import com.blockreality.api.blueprint.Blueprint;
import com.blockreality.api.blueprint.BlueprintNBT;
import com.blockreality.fastdesign.client.HologramState;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * S→C 全息投影同步封包 — v3fix §3.1
 */
public class HologramSyncPacket {

    private enum Action { LOAD, CLEAR, MOVE, ROTATE }

    private final Action action;
    private CompoundTag blueprintTag;
    private int originX, originY, originZ;
    private int dx, dy, dz;

    private HologramSyncPacket(Action action) {
        this.action = action;
    }

    public static HologramSyncPacket load(CompoundTag bpTag, int ox, int oy, int oz) {
        HologramSyncPacket pkt = new HologramSyncPacket(Action.LOAD);
        pkt.blueprintTag = bpTag;
        pkt.originX = ox;
        pkt.originY = oy;
        pkt.originZ = oz;
        return pkt;
    }

    public static HologramSyncPacket clear() {
        return new HologramSyncPacket(Action.CLEAR);
    }

    public static HologramSyncPacket move(int dx, int dy, int dz) {
        HologramSyncPacket pkt = new HologramSyncPacket(Action.MOVE);
        pkt.dx = dx;
        pkt.dy = dy;
        pkt.dz = dz;
        return pkt;
    }

    public static HologramSyncPacket rotate() {
        return new HologramSyncPacket(Action.ROTATE);
    }

    public static void encode(HologramSyncPacket pkt, FriendlyByteBuf buf) {
        buf.writeEnum(pkt.action);
        switch (pkt.action) {
            case LOAD -> {
                buf.writeNbt(pkt.blueprintTag);
                buf.writeInt(pkt.originX);
                buf.writeInt(pkt.originY);
                buf.writeInt(pkt.originZ);
            }
            case MOVE -> {
                buf.writeInt(pkt.dx);
                buf.writeInt(pkt.dy);
                buf.writeInt(pkt.dz);
            }
            case CLEAR, ROTATE -> {}
        }
    }

    public static HologramSyncPacket decode(FriendlyByteBuf buf) {
        Action action = buf.readEnum(Action.class);
        HologramSyncPacket pkt = new HologramSyncPacket(action);
        switch (action) {
            case LOAD -> {
                pkt.blueprintTag = buf.readNbt();
                pkt.originX = buf.readInt();
                pkt.originY = buf.readInt();
                pkt.originZ = buf.readInt();
                // Bounds check: validate origin is within world bounds (-30000000 to 30000000 X/Z, -64 to 320 Y)
                if (pkt.originX < -30000000 || pkt.originX > 30000000 ||
                    pkt.originZ < -30000000 || pkt.originZ > 30000000 ||
                    pkt.originY < -64 || pkt.originY > 320) {
                    return new HologramSyncPacket(Action.CLEAR);
                }
                if (pkt.blueprintTag == null) {
                    // 讀完所有欄位後再判斷，避免 buffer 對齊錯誤
                    return new HologramSyncPacket(Action.CLEAR);
                }
            }
            case MOVE -> {
                pkt.dx = buf.readInt();
                pkt.dy = buf.readInt();
                pkt.dz = buf.readInt();
                // Bounds check: movement delta should be reasonable (±1024 blocks)
                if (Math.abs(pkt.dx) > 1024 || Math.abs(pkt.dy) > 1024 || Math.abs(pkt.dz) > 1024) {
                    return new HologramSyncPacket(Action.CLEAR);
                }
            }
            case CLEAR, ROTATE -> {}
        }
        return pkt;
    }

    public static void handle(HologramSyncPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> handleOnClient(pkt));
        });
        ctx.get().setPacketHandled(true);
    }

    private static void handleOnClient(HologramSyncPacket pkt) {
        switch (pkt.action) {
            case LOAD -> {
                Blueprint bp = BlueprintNBT.read(pkt.blueprintTag);
                HologramState.load(bp, new BlockPos(pkt.originX, pkt.originY, pkt.originZ));
            }
            case CLEAR -> HologramState.clear();
            case MOVE -> HologramState.setOffset(pkt.dx, pkt.dy, pkt.dz);
            case ROTATE -> HologramState.rotate();
        }
    }
}
