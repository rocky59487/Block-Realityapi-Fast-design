package com.blockreality.fastdesign.network;

import com.blockreality.api.blueprint.Blueprint;
import com.blockreality.api.blueprint.BlueprintNBT;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * S→C 開啟 CAD 畫面封包 — v3fix §2.2
 */
public class OpenCadScreenPacket {

    private final CompoundTag blueprintTag;

    public OpenCadScreenPacket(CompoundTag blueprintTag) {
        this.blueprintTag = blueprintTag;
    }

    public static void encode(OpenCadScreenPacket pkt, FriendlyByteBuf buf) {
        buf.writeNbt(pkt.blueprintTag);
    }

    public static OpenCadScreenPacket decode(FriendlyByteBuf buf) {
        return new OpenCadScreenPacket(buf.readNbt());
    }

    public static void handle(OpenCadScreenPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                Blueprint bp = BlueprintNBT.read(pkt.blueprintTag);
                net.minecraft.client.Minecraft.getInstance().setScreen(new com.blockreality.fastdesign.client.FastDesignScreen(bp));
            });
        });
        ctx.get().setPacketHandled(true);
    }
}
