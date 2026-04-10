package com.blockreality.fastdesign.client;

import com.blockreality.api.client.GhostBlockRenderer;
import com.blockreality.fastdesign.build.BuildModeState;
import com.blockreality.fastdesign.network.FdActionPacket;
import com.blockreality.fastdesign.network.FdNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Client-only handler for FdWandItem multi-block placement logic.
 *
 * 客戶端專用 — 處理 FdWandItem 的多方塊放置邏輯。
 * 獨立為一個 @OnlyIn(CLIENT) 類別，避免 FdWandItem 在伺服器端載入時
 * 因為參照 GhostBlockRenderer 等客戶端類別而崩潰。
 *
 * @see com.blockreality.fastdesign.item.FdWandItem
 */
@OnlyIn(Dist.CLIENT)
public final class WandClientHandler {

    private WandClientHandler() {}

    /**
     * 客戶端多方塊模式邏輯 — 由 FdWandItem 的 DistExecutor 調用。
     *
     * Ctrl+右鍵: 設定鏡像錨點
     * 第一次右鍵: 設定第一錨點 (anchor)
     * 第二次右鍵: 確認放置 → 發送 PLACE_MULTI 封包到伺服器
     */
    public static void handleMultiBlock(Player player, BlockPos pos) {
        if (!BuildModeState.isMultiBlockMode()) {
            return; // NORMAL 模式不需要客戶端處理
        }

        // Ctrl+右鍵: 設定鏡像錨點
        if (net.minecraft.client.gui.screens.Screen.hasControlDown()) {
            BuildModeState.setMirrorAnchor(pos);
            player.displayClientMessage(
                Component.literal("§6[FD] §d鏡像錨點: §f" + pos.toShortString()),
                true
            );
            return;
        }

        if (!BuildModeState.hasAnchor()) {
            // ─── 第一次點擊: 設定錨點 ───
            BuildModeState.setAnchor(pos);
            player.displayClientMessage(
                Component.literal("§6[FD] §a錨點 A 設定: §f" + pos.toShortString() +
                    " §7(右鍵第二點確認)"),
                true
            );
        } else {
            // ─── 第二次點擊: 確認放置 ───
            String payload = BuildModeState.encodePayload(pos);

            // 發送 PLACE_MULTI 封包到伺服器
            FdNetwork.CHANNEL.sendToServer(
                new FdActionPacket(FdActionPacket.Action.PLACE_MULTI, payload)
            );

            // 清除預覽和錨點
            GhostBlockRenderer.clearPreview();
            BuildModeState.reset();

            player.displayClientMessage(
                Component.literal("§6[FD] §a多方塊放置已確認"),
                true
            );
        }
    }

    public static void openControlPanel() {
        net.minecraft.client.Minecraft.getInstance().setScreen(new ControlPanelScreen());
    }
}
