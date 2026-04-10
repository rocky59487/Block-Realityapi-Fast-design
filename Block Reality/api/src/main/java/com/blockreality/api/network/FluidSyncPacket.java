package com.blockreality.api.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 流體同步封包 (S→C) — 同步流體體積和類型變化到客戶端。
 *
 * <p>每 {@code FluidConstants.SYNC_INTERVAL_TICKS} (10 ticks = 0.5s) 發送一次，
 * 僅包含自上次同步後有變化的體素。
 *
 * <p>客戶端收到後更新本地的渲染用流體狀態，驅動 WaterSurfaceNode 等。
 */
public class FluidSyncPacket {

    private static final Logger LOGGER = LogManager.getLogger("FluidSyncPacket");

    private final Map<BlockPos, FluidEntry> entries;

    public record FluidEntry(byte type, float volume) {}

    public FluidSyncPacket(Map<BlockPos, FluidEntry> entries) {
        this.entries = entries;
    }

    public static void encode(FluidSyncPacket pkt, FriendlyByteBuf buf) {
        buf.writeVarInt(pkt.entries.size());
        for (Map.Entry<BlockPos, FluidEntry> entry : pkt.entries.entrySet()) {
            buf.writeBlockPos(entry.getKey());
            buf.writeByte(entry.getValue().type());
            buf.writeFloat(entry.getValue().volume());
        }
    }

    public static FluidSyncPacket decode(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        // ★ P6-fix: 封包大小防護 — 超大封包記錄警告後忽略，不拋出例外（防止斷線）
        if (size < 0 || size > 8192) {
            LOGGER.warn("[FluidSyncPacket] Oversized or invalid packet (size={}), dropping", size);
            return new FluidSyncPacket(new HashMap<>());
        }
        Map<BlockPos, FluidEntry> entries = new HashMap<>(size);
        for (int i = 0; i < size; i++) {
            BlockPos pos = buf.readBlockPos();
            byte type = buf.readByte();
            float volume = buf.readFloat();
            entries.put(pos, new FluidEntry(type, volume));
        }
        return new FluidSyncPacket(entries);
    }

    public static void handle(FluidSyncPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            // 客戶端更新本地流體渲染狀態
            // FluidClientState.applySync(pkt.entries);
        });
        ctx.get().setPacketHandled(true);
    }

    public Map<BlockPos, FluidEntry> getEntries() { return entries; }
}
