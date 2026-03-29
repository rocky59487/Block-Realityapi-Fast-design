package com.blockreality.api.network;

import com.blockreality.api.client.AnchorPathCache;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * S→C 錨定路徑同步封包 — 想法.docx AnchorPathVisualizer
 *
 * 將伺服器端的錨定 BFS 路徑同步到客戶端。
 * 客戶端使用 AnchorPathRenderer 以半透明線段渲染路徑。
 *
 * ★ R6-10 fix: 每條路徑新增 isAnchored 布林值，
 * 讓渲染器區分有效（綠色）和無效（紅色）路徑。
 *
 * 封包格式（v2）：
 *   [int: pathCount]
 *   repeat pathCount:
 *     [boolean: isAnchored]
 *     [int: nodeCount]
 *     repeat nodeCount:
 *       [long: blockPos.asLong()]
 */
public class AnchorPathSyncPacket {

    private final List<AnchorPathCache.PathEntry> entries;

    public AnchorPathSyncPacket(List<AnchorPathCache.PathEntry> entries) {
        this.entries = entries;
    }

    /**
     * 向後相容建構子 — 無錨定狀態，全部視為已錨定。
     */
    public static AnchorPathSyncPacket fromLegacy(List<List<BlockPos>> paths) {
        List<AnchorPathCache.PathEntry> entries = new ArrayList<>(paths.size());
        for (List<BlockPos> p : paths) {
            entries.add(new AnchorPathCache.PathEntry(p, true));
        }
        return new AnchorPathSyncPacket(entries);
    }

    public List<AnchorPathCache.PathEntry> getEntries() { return entries; }

    /** 向後相容 — 純路徑列表 */
    public List<List<BlockPos>> getPaths() {
        List<List<BlockPos>> result = new ArrayList<>(entries.size());
        for (AnchorPathCache.PathEntry e : entries) {
            result.add(e.nodes());
        }
        return result;
    }

    // ─── 序列化 ───

    public static void encode(AnchorPathSyncPacket packet, FriendlyByteBuf buf) {
        buf.writeInt(packet.entries.size());
        for (AnchorPathCache.PathEntry entry : packet.entries) {
            buf.writeBoolean(entry.isAnchored());
            buf.writeInt(entry.nodes().size());
            for (BlockPos pos : entry.nodes()) {
                buf.writeLong(pos.asLong());
            }
        }
    }

    public static AnchorPathSyncPacket decode(FriendlyByteBuf buf) {
        int pathCount = buf.readInt();
        List<AnchorPathCache.PathEntry> entries = new ArrayList<>(pathCount);
        for (int i = 0; i < pathCount; i++) {
            boolean isAnchored = buf.readBoolean();
            int nodeCount = buf.readInt();
            List<BlockPos> path = new ArrayList<>(nodeCount);
            for (int j = 0; j < nodeCount; j++) {
                path.add(BlockPos.of(buf.readLong()));
            }
            entries.add(new AnchorPathCache.PathEntry(path, isAnchored));
        }
        return new AnchorPathSyncPacket(entries);
    }

    // ─── 處理（客戶端） ───

    public static void handle(AnchorPathSyncPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                AnchorPathCache.updatePaths(packet.getEntries());
            });
        });
        ctx.get().setPacketHandled(true);
    }
}
