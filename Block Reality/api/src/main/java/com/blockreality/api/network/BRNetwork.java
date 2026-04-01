package com.blockreality.api.network;

import com.blockreality.api.BlockRealityMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Block Reality 網路頻道註冊 — v3fix §1.8
 *
 * 使用 SimpleChannel 管理 S→C 封包（應力同步等）。
 *
 * ★ P6-fix (2025-04): 加入封包安全防護：
 *   - MAX_PACKET_BYTES：單封包最大位元組數（OOM 防護）
 *   - PacketGuard：C→S 封包伺服器端發送者驗證工具
 *   - 封包頻率限制（防止刷封包 DoS）
 */
@javax.annotation.concurrent.ThreadSafe // AtomicInteger packet ID
public class BRNetwork {

    private static final Logger LOGGER = LogManager.getLogger("BlockReality/Network");
    private static final String PROTOCOL_VERSION = "1";

    /**
     * ★ P6-fix: 單封包最大允許位元組數 (512 KB)。
     * 超過此限制的封包將在解碼後的 validate() 中被拒絕。
     * 目前最大封包 StressSyncPacket 估計上限：
     *   65536 方塊 × (3+4) byte = ~448 KB，設 512 KB 有足夠餘裕。
     */
    public static final int MAX_PACKET_BYTES = 512 * 1024;

    /**
     * ★ P6-fix: C→S 封包最小間隔 (ms)。
     * 防止客戶端以每幀速度刷送控制封包造成伺服器 DoS。
     */
    private static final long MIN_PACKET_INTERVAL_MS = 50L; // 最多 20 pkt/s

    /** 每位玩家上次 C→S 封包時間戳（毫秒）。 */
    private static final Map<UUID, Long> lastPacketTime = new ConcurrentHashMap<>();

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation(BlockRealityMod.MOD_ID, "main"),
        () -> PROTOCOL_VERSION,
        PROTOCOL_VERSION::equals,
        PROTOCOL_VERSION::equals
    );

    // ★ M-2 fix: 改用 AtomicInteger 確保線程安全
    private static final AtomicInteger packetId = new AtomicInteger(0);
    // ★ NET-1 fix: 防止重複註冊導致 packet ID 碰撞
    private static volatile boolean registered = false;

    /**
     * ★ P6-fix: C→S 封包發送者驗證工具。
     *
     * 在每個 C→S 封包 handler 的開頭呼叫此方法進行統一驗證：
     *   1. sender 非 null（防止伺服器端自送封包誤判）
     *   2. 封包頻率限制（MIN_PACKET_INTERVAL_MS 最小間隔）
     *
     * 使用範例：
     * <pre>{@code
     *   if (!BRNetwork.validateSender(ctx.get().getSender(), "ChiselControl")) return;
     * }</pre>
     *
     * @param player  封包發送者（可為 null）
     * @param packetName  封包名稱（用於日誌）
     * @return true 表示驗證通過，false 表示應拒絕此封包
     */
    public static boolean validateSender(ServerPlayer player, String packetName) {
        if (player == null) {
            LOGGER.warn("[Network/P6] {} received with null sender, dropping", packetName);
            return false;
        }
        // 封包頻率限制
        long now = System.currentTimeMillis();
        Long last = lastPacketTime.put(player.getUUID(), now);
        if (last != null && (now - last) < MIN_PACKET_INTERVAL_MS) {
            LOGGER.warn("[Network/P6] {} flood from player {} (interval {}ms < {}ms), dropping",
                packetName, player.getName().getString(), now - last, MIN_PACKET_INTERVAL_MS);
            return false;
        }
        return true;
    }

    /**
     * ★ P6-fix: 玩家離線時清理頻率限制快取，防止長時間運行後 Map 膨脹。
     * 由 ServerPlayerLoggedOutEvent handler 呼叫。
     */
    public static void cleanupPlayer(UUID uuid) {
        lastPacketTime.remove(uuid);
    }

    /**
     * 註冊所有封包類型。應在 mod 初始化時呼叫。
     * ★ 加入重複呼叫防護 — 若 register() 被呼叫兩次，第二次跳過。
     */
    public static void register() {
        if (registered) return;
        registered = true;
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

        // ★ review-fix ICReM-5: 崩塌效果封包 (S→C)：傳送失敗類型到客戶端
        CHANNEL.registerMessage(
            packetId.getAndIncrement(),
            CollapseEffectPacket.class,
            CollapseEffectPacket::encode,
            CollapseEffectPacket::decode,
            CollapseEffectPacket::handle
        );
    }
}
