package com.blockreality.fastdesign.client;

import com.blockreality.api.command.PlayerSelectionManager;
import com.blockreality.fastdesign.FastDesignMod;
import com.blockreality.fastdesign.config.FastDesignConfig;
import com.blockreality.fastdesign.item.FdWandItem;
import com.blockreality.fastdesign.network.FdNetwork;
import com.blockreality.fastdesign.network.FdSelectionSyncPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

/**
 * FD 選取杖事件處理器 — 開發手冊 §9.2
 *
 * 攔截玩家用選取游標進行左鍵（pos1）點擊。
 * 右鍵（pos2）由 FdWandItem.useOn() 直接處理。
 *
 * ★ 已移除舊版 carrot_on_a_stick + {fd_wand:1b} NBT 回退相容，
 *   統一使用正式 FdWandItem。
 */
@Mod.EventBusSubscriber(modid = FastDesignMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class SelectionWandHandler {

    /**
     * 檢查物品是否為 FD 選取杖。
     */
    private static boolean isFdWand(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof FdWandItem;
    }

    /**
     * 左鍵點擊（設定 pos1）
     *
     * 注意：Item 類別沒有 left-click-on-block 的 override，
     * 所以左鍵必須透過 PlayerInteractEvent.LeftClickBlock 事件攔截。
     */
    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (!FastDesignConfig.isWandEnabled()) return;

        Player player = event.getEntity();
        ItemStack heldItem = player.getMainHandItem();

        if (!isFdWand(heldItem)) return;

        // 只在伺服器端處理
        if (player.level().isClientSide) {
            event.setCanceled(true);
            return;
        }

        BlockPos pos = event.getPos();

        // 設定 pos1
        PlayerSelectionManager.setPos1(player.getUUID(), pos);

        // 同步到客戶端 + ActionBar 反饋
        if (player instanceof ServerPlayer sp) {
            BlockPos pos2 = PlayerSelectionManager.getPos2(player.getUUID());
            if (pos2 != null) {
                FdNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> sp),
                    new FdSelectionSyncPacket(pos, pos2)
                );

                // 完整 ActionBar 顯示
                var box = PlayerSelectionManager.getSelection(player.getUUID());
                sp.displayClientMessage(Component.literal(String.format(
                    "§6[FD] §aA: (%d,%d,%d) §7→ §cB: (%d,%d,%d) §7| §f%d×%d×%d = %d blocks",
                    pos.getX(), pos.getY(), pos.getZ(),
                    pos2.getX(), pos2.getY(), pos2.getZ(),
                    box.sizeX(), box.sizeY(), box.sizeZ(), box.volume()
                )), true);
            } else {
                FdNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> sp),
                    new FdSelectionSyncPacket(pos, pos)
                );
                sp.displayClientMessage(
                    Component.literal("§6[FD] §fPos1 設定: §a" + pos.toShortString()),
                    true
                );
            }
        }

        // 取消事件（防止破壞方塊）
        event.setCanceled(true);
    }
}
