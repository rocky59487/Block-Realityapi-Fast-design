package com.blockreality.fastdesign.network;

import com.blockreality.fastdesign.FastDesignMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fast Design 獨立網路頻道 — 開發手冊 §11
 *
 * 與 BRNetwork 完全獨立，管理 Fast Design 專屬的 S→C 封包。
 */
public class FdNetwork {

    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation(FastDesignMod.MOD_ID, "main"),
        () -> PROTOCOL_VERSION,
        PROTOCOL_VERSION::equals,
        PROTOCOL_VERSION::equals
    );

    private static final AtomicInteger packetId = new AtomicInteger(0);

    /**
     * 註冊所有 Fast Design 封包類型。
     */
    public static void register() {
        CHANNEL.registerMessage(
            packetId.getAndIncrement(),
            FdSelectionSyncPacket.class,
            FdSelectionSyncPacket::encode,
            FdSelectionSyncPacket::decode,
            FdSelectionSyncPacket::handle
        );

        CHANNEL.registerMessage(
            packetId.getAndIncrement(),
            OpenCadScreenPacket.class,
            OpenCadScreenPacket::encode,
            OpenCadScreenPacket::decode,
            OpenCadScreenPacket::handle
        );

        CHANNEL.registerMessage(
            packetId.getAndIncrement(),
            HologramSyncPacket.class,
            HologramSyncPacket::encode,
            HologramSyncPacket::decode,
            HologramSyncPacket::handle
        );

        CHANNEL.registerMessage(
            packetId.getAndIncrement(),
            FdActionPacket.class,
            FdActionPacket::encode,
            FdActionPacket::decode,
            FdActionPacket::handle
        );

        CHANNEL.registerMessage(
            packetId.getAndIncrement(),
            PastePreviewSyncPacket.class,
            PastePreviewSyncPacket::encode,
            PastePreviewSyncPacket::decode,
            PastePreviewSyncPacket::handle
        );

        CHANNEL.registerMessage(
            packetId.getAndIncrement(),
            PastePlacePacket.class,
            PastePlacePacket::encode,
            PastePlacePacket::decode,
            PastePlacePacket::handle
        );
    }
}
