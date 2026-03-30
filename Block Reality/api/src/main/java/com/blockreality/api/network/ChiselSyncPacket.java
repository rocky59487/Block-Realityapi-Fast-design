package com.blockreality.api.network;

import com.blockreality.api.block.RBlockEntity;
import com.blockreality.api.chisel.ChiselState;
import com.blockreality.api.chisel.SubBlockShape;
import com.blockreality.api.chisel.VoxelGrid;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * S→C 雕刻狀態同步封包。
 *
 * 封包格式：
 *   [long: blockPos.asLong()]
 *   [utf: shapeName]
 *   [boolean: hasCustomVoxels]
 *   if hasCustomVoxels:
 *     [16 × long: voxel data]
 *
 * 模板形狀: ~20 bytes
 * 自訂形狀: ~148 bytes
 */
public class ChiselSyncPacket {

    private final BlockPos pos;
    private final String shapeName;
    private final long[] voxelData; // null for templates

    public ChiselSyncPacket(BlockPos pos, ChiselState state) {
        this.pos = pos;
        this.shapeName = state.shape().getSerializedName();
        this.voxelData = state.isCustom() ? state.voxelGrid().toLongArray() : null;
    }

    private ChiselSyncPacket(BlockPos pos, String shapeName, long[] voxelData) {
        this.pos = pos;
        this.shapeName = shapeName;
        this.voxelData = voxelData;
    }

    // ─── 序列化 ───

    public static void encode(ChiselSyncPacket packet, FriendlyByteBuf buf) {
        buf.writeLong(packet.pos.asLong());
        buf.writeUtf(packet.shapeName);
        boolean hasCustom = packet.voxelData != null;
        buf.writeBoolean(hasCustom);
        if (hasCustom) {
            for (long word : packet.voxelData) {
                buf.writeLong(word);
            }
        }
    }

    public static ChiselSyncPacket decode(FriendlyByteBuf buf) {
        BlockPos pos = BlockPos.of(buf.readLong());
        // Bounds check: validate block coordinates are within world bounds (-30000000 to 30000000 X/Z, -64 to 320 Y)
        if (pos.getX() < -30000000 || pos.getX() > 30000000 ||
            pos.getZ() < -30000000 || pos.getZ() > 30000000 ||
            pos.getY() < -64 || pos.getY() > 320) {
            return new ChiselSyncPacket(BlockPos.ZERO, "TEMPLATE", null);
        }
        String shapeName = buf.readUtf(256);  // Bounds check: reasonable max string length
        boolean hasCustom = buf.readBoolean();
        long[] voxelData = null;
        if (hasCustom) {
            voxelData = new long[16];
            for (int i = 0; i < 16; i++) {
                voxelData[i] = buf.readLong();
            }
        }
        return new ChiselSyncPacket(pos, shapeName, voxelData);
    }

    // ─── 處理（客戶端） ───

    public static void handle(ChiselSyncPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                handleOnClient(packet);
            });
        });
        ctx.get().setPacketHandled(true);
    }

    private static void handleOnClient(ChiselSyncPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        BlockEntity be = mc.level.getBlockEntity(packet.pos);
        if (be instanceof RBlockEntity rbe) {
            SubBlockShape shape = SubBlockShape.fromString(packet.shapeName);
            ChiselState state;
            if (shape == SubBlockShape.CUSTOM && packet.voxelData != null) {
                state = ChiselState.ofCustom(VoxelGrid.fromLongArray(packet.voxelData));
            } else {
                state = ChiselState.ofShape(shape);
            }
            rbe.setChiselState(state);
        }
    }
}
