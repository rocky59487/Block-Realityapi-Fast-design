package com.blockreality.fastdesign.network;

import com.blockreality.fastdesign.client.GhostPreviewRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * S→C 封包：將 paste 預覽的方塊資料同步到客戶端，
 * 觸發 GhostPreviewRenderer 顯示半透明預覽。
 *
 * 行為：
 *   - active=true  → 設定預覽資料並啟動渲染
 *   - active=false → 清除預覽（confirm / cancel 時發送）
 */
public class PastePreviewSyncPacket {

    private final boolean active;
    private final BlockPos origin;
    private final Map<BlockPos, BlockState> blocks;

    /** 啟動預覽 */
    public PastePreviewSyncPacket(BlockPos origin, Map<BlockPos, BlockState> blocks) {
        this.active = true;
        this.origin = origin;
        this.blocks = blocks;
    }

    /** 清除預覽 */
    public PastePreviewSyncPacket() {
        this.active = false;
        this.origin = BlockPos.ZERO;
        this.blocks = Map.of();
    }

    // ─── 序列化 ───

    public static void encode(PastePreviewSyncPacket pkt, FriendlyByteBuf buf) {
        buf.writeBoolean(pkt.active);
        if (pkt.active) {
            buf.writeBlockPos(pkt.origin);
            buf.writeVarInt(pkt.blocks.size());
            for (var entry : pkt.blocks.entrySet()) {
                buf.writeBlockPos(entry.getKey());
                buf.writeVarInt(Block.getId(entry.getValue()));
            }
        }
    }

    public static PastePreviewSyncPacket decode(FriendlyByteBuf buf) {
        boolean active = buf.readBoolean();
        if (!active) {
            return new PastePreviewSyncPacket();
        }
        BlockPos origin = buf.readBlockPos();
        // Bounds check: validate origin is within world bounds (-30000000 to 30000000 X/Z, -64 to 320 Y)
        if (origin.getX() < -30000000 || origin.getX() > 30000000 ||
            origin.getZ() < -30000000 || origin.getZ() > 30000000 ||
            origin.getY() < -64 || origin.getY() > 320) {
            return new PastePreviewSyncPacket();
        }
        int count = buf.readVarInt();
        // Bounds check: limit blocks in preview to 65536
        if (count < 0 || count > 65536) {
            return new PastePreviewSyncPacket();
        }
        Map<BlockPos, BlockState> blocks = new HashMap<>(count);
        for (int i = 0; i < count; i++) {
            BlockPos pos = buf.readBlockPos();
            BlockState state = Block.stateById(buf.readVarInt());
            blocks.put(pos, state);
        }
        return new PastePreviewSyncPacket(origin, blocks);
    }

    // ─── 處理（CLIENT） ───

    public static void handle(PastePreviewSyncPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> handleClient(pkt));
        });
        ctx.setPacketHandled(true);
    }

    private static void handleClient(PastePreviewSyncPacket pkt) {
        if (pkt.active) {
            GhostPreviewRenderer.setPreview(pkt.blocks, pkt.origin);
        } else {
            GhostPreviewRenderer.clearPreview();
        }
    }
}
