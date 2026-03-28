package com.blockreality.api.network;

import com.blockreality.api.BlockRealityMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Block Reality 網路頻道註冊 — v3fix §1.8
 *
 * 使用 SimpleChannel 管理 S→C 封包（應力同步等）。
 */
@javax.annotation.concurrent.ThreadSafe // AtomicInteger packet ID
public class BRNetwork {

    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation(BlockRealityMod.MOD_ID, "main"),
        () -> PROTOCOL_VERSION,
        PROTOCOL_VERSION::equals,
        PROTOCOL_VERSION::equals
    );

    // ★ M-2 fix: 改用 AtomicInteger 確保線程安全
    private static final AtomicInteger packetId = new AtomicInteger(0);

    /**
     * 註冊所有封包類型。應在 mod 初始化時呼叫。
     */
    public static void register() {
        CHANNEL.registerMessage(
            packetId.getAndIncrement(),
            StressSyncPacket.class,
            StressSyncPacket::encode,
            StressSyncPacket::decode,
            StressSyncPacket::handle
        );

        CHANNEL.registerMessage(
            packetId.getAndIncrement(),
            AnchorPathSyncPacket.class,
            AnchorPathSyncPacket::encode,
            AnchorPathSyncPacket::decode,
            AnchorPathSyncPacket::handle
        );

        CHANNEL.registerMessage(
            packetId.getAndIncrement(),
            ChiselSyncPacket.class,
            ChiselSyncPacket::encode,
            ChiselSyncPacket::decode,
            ChiselSyncPacket::handle
        );

        // ★ 雕刻刀控制封包 (C→S)：選區調整 + 橡皮擦模式
        CHANNEL.registerMessage(
            packetId.getAndIncrement(),
            ChiselControlPacket.class,
            ChiselControlPacket::encode,
            ChiselControlPacket::decode,
            ChiselControlPacket::handle
        );

    }
}
