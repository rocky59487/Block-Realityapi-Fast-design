package com.blockreality.api.network;
import com.blockreality.api.physics.FailureType;

import com.blockreality.api.client.ClientCollapseCache;
import com.blockreality.api.physics.FailureType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * ★ review-fix ICReM-5: S→C 崩塌效果封包
 *
 * 將崩塌的失敗類型同步到客戶端，使不同破壞模式有不同的視覺效果：
 *   - CANTILEVER_BREAK (0): 整段懸臂一起斷裂掉落，附帶斷裂動畫
 *   - CRUSHING (1):         漸進式壓碎，材質逐漸裂開
 *   - NO_SUPPORT (2):       直接掉落（無支撐）
 *
 * 封包格式：
 *   [int: count]
 *   repeat count:
 *     [long: blockPos.asLong()]
 *     [byte: failureType ordinal]
 *     [int: materialId]
 */
public class CollapseEffectPacket {

    private final Map<BlockPos, CollapseInfo> collapseData;

    public record CollapseInfo(FailureType type, int materialId) {}

    public CollapseEffectPacket(Map<BlockPos, CollapseInfo> collapseData) {
        this.collapseData = collapseData;
    }

    // ─── 序列化 ───

    public static void encode(CollapseEffectPacket packet, FriendlyByteBuf buf) {
        buf.writeInt(packet.collapseData.size());
        for (Map.Entry<BlockPos, CollapseInfo> entry : packet.collapseData.entrySet()) {
            buf.writeLong(entry.getKey().asLong());
            buf.writeByte(entry.getValue().type().ordinal());
            buf.writeInt(entry.getValue().materialId());
        }
    }

    public static CollapseEffectPacket decode(FriendlyByteBuf buf) {
        int count = buf.readInt();
        // Bounds check: limit collapse entries to 65536
        if (count < 0 || count > 65536) {
            return new CollapseEffectPacket(new HashMap<>());
        }
        Map<BlockPos, CollapseInfo> data = new HashMap<>(count);
        for (int i = 0; i < count; i++) {
            BlockPos pos = BlockPos.of(buf.readLong());
            byte typeOrdinal = buf.readByte();
            FailureType[] types = FailureType.values();
            // Bounds check: enum ordinal must be within valid range
            if (typeOrdinal < 0 || typeOrdinal >= types.length) {
                continue;  // Skip invalid entries
            }
            FailureType type = types[typeOrdinal];
            int materialId = buf.readInt();
            data.put(pos, new CollapseInfo(type, materialId));
        }
        return new CollapseEffectPacket(data);
    }

    // ─── 處理（客戶端） ───

    public static void handle(CollapseEffectPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                ClientCollapseCache.processCollapseEffects(packet.collapseData);
            });
        });
        ctx.get().setPacketHandled(true);
    }
}
