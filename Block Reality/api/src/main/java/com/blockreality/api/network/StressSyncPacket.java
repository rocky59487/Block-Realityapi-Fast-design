package com.blockreality.api.network;

import com.blockreality.api.client.ClientStressCache;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * S→C 應力同步封包 — v3fix §1.8
 *
 * 將伺服器端的應力計算結果批量同步到客戶端。
 * 由 ResultApplicator 在應力寫回後觸發發送。
 *
 * 封包格式：
 *   [int: count]
 *   repeat count:
 *     [long: blockPos.asLong()]
 *     [float: stressLevel]
 */
public class StressSyncPacket {

    private final Map<BlockPos, Float> stressData;

    public StressSyncPacket(Map<BlockPos, Float> stressData) {
        this.stressData = stressData;
    }

    // ─── 序列化 ───

    public static void encode(StressSyncPacket packet, FriendlyByteBuf buf) {
        buf.writeInt(packet.stressData.size());
        for (Map.Entry<BlockPos, Float> entry : packet.stressData.entrySet()) {
            buf.writeLong(entry.getKey().asLong());
            buf.writeFloat(entry.getValue());
        }
    }

    public static StressSyncPacket decode(FriendlyByteBuf buf) {
        int count = buf.readInt();
        // Bounds check: limit stress entries to 65536
        if (count < 0 || count > 65536) {
            return new StressSyncPacket(new HashMap<>());
        }
        Map<BlockPos, Float> data = new HashMap<>(count);
        for (int i = 0; i < count; i++) {
            BlockPos pos = BlockPos.of(buf.readLong());
            float stress = buf.readFloat();
            data.put(pos, stress);
        }
        return new StressSyncPacket(data);
    }

    // ─── 處理（客戶端） ───

    public static void handle(StressSyncPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            // 安全地在客戶端執行（避免直接引用 client 類導致 server crash）
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                ClientStressCache.mergeStressData(packet.stressData);
            });
        });
        ctx.get().setPacketHandled(true);
    }
}
