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
 * M10: PFSF 應力場同步封包（Server → Client）。
 * <p>
 * 將 GPU 計算的歸一化應力值同步給客戶端，用於：
 * <ul>
 *   <li>應力熱力圖渲染（R 鍵 overlay）</li>
 *   <li>客戶端預測性崩塌粒子效果</li>
 *   <li>HUD 上的結構安全度指示器</li>
 * </ul>
 * <p>
 * 頻寬控制：
 * - 只同步 stress ≥ 0.3 的方塊（安全區不傳）
 * - 每 island 每 10 tick 最多同步一次
 * - 最大 4096 entries/packet（~50KB）
 */
public class PFSFStressSyncPacket {

    /** 最大同步 entries（防止巨型封包卡網路） */
    private static final int MAX_ENTRIES = 4096;

    private final int islandId;
    private final Map<BlockPos, Float> stressData;

    public PFSFStressSyncPacket(int islandId, Map<BlockPos, Float> stressData) {
        this.islandId = islandId;
        this.stressData = stressData;
    }

    // ─── Encode: Server → Network ───

    public static void encode(PFSFStressSyncPacket packet, FriendlyByteBuf buf) {
        buf.writeInt(packet.islandId);
        int count = Math.min(packet.stressData.size(), MAX_ENTRIES);
        buf.writeInt(count);

        int written = 0;
        for (Map.Entry<BlockPos, Float> entry : packet.stressData.entrySet()) {
            if (written >= count) break;
            buf.writeLong(entry.getKey().asLong());
            buf.writeFloat(entry.getValue());
            written++;
        }
    }

    // ─── Decode: Network → Client ───

    public static PFSFStressSyncPacket decode(FriendlyByteBuf buf) {
        int islandId = buf.readInt();
        int count = buf.readInt();
        if (count < 0 || count > MAX_ENTRIES) {
            return new PFSFStressSyncPacket(islandId, Map.of());
        }

        Map<BlockPos, Float> data = new HashMap<>(count);
        for (int i = 0; i < count; i++) {
            BlockPos pos = BlockPos.of(buf.readLong());
            float stress = buf.readFloat();
            data.put(pos, stress);
        }
        return new PFSFStressSyncPacket(islandId, data);
    }

    // ─── Handle: Client-side ───

    public static void handle(PFSFStressSyncPacket packet,
                               Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                // 合併到客戶端應力快取（現有 ClientStressCache 已支援）
                ClientStressCache.mergeStressData(packet.stressData);
            });
        });
        ctx.get().setPacketHandled(true);
    }

    // ─── Getters ───

    public int getIslandId() { return islandId; }
    public Map<BlockPos, Float> getStressData() { return stressData; }
}
