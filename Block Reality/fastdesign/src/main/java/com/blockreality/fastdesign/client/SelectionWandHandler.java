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
 * 支援兩種游標形式：
 * 1. 正式 FdWandItem（優先）
 * 2. 舊版 carrot_on_a_stick + {fd_wand:1b} NBT（回退相容）
 */
@Mod.EventBusSubscriber(modid = FastDesignMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class SelectionWandHandler {

    /**
     * 檢查物品是否為 FD 選取杖（新版 or 舊版）。
     */
    private static boolean isFdWand(ItemStack stack) {
        if (stack.isEmpty()) return false;

        // 優先：正式 FdWandItem
        if (stack.getItem() instanceof FdWandItem) return true;

        // 回退：舊版 NBT tag 相容
        return stack.getTag() != null && stack.getTag().getBoolean("fd_wand");
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

    /**
     * 右鍵點擊方塊 — 僅攔截舊版 NBT 杖
     *
     * 新版 FdWandItem 的右鍵由 Item.useOn() 直接處理，
     * 此事件只為舊版 carrot_on_a_stick NBT hack 保留回退相容。
     */
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!FastDesignConfig.isWandEnabled()) return;

        Player player = event.getEntity();
        ItemStack heldItem = player.getMainHandItem();

        // 新版 FdWandItem 由 useOn() 處理，這裡跳過
        if (heldItem.getItem() instanceof FdWandItem) return;

        // 只處理舊版 NBT wand
        if (heldItem.isEmpty() || heldItem.getTag() == null
                || !heldItem.getTag().getBoolean("fd_wand")) {
            return;
        }

        if (player.level().isClientSide) {
            event.setCanceled(true);
            return;
        }

        BlockPos pos = event.getPos();
        PlayerSelectionManager.setPos2(player.getUUID(), pos);

        if (player instanceof ServerPlayer sp) {
            BlockPos pos1 = PlayerSelectionManager.getPos1(player.getUUID());
            if (pos1 != null) {
                FdNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> sp),
                    new FdSelectionSyncPacket(pos1, pos)
                );
            }
            sp.displayClientMessage(
                Component.literal("§6[FD] §fPos2 設定: §a" + pos.toShortString()),
                true
            );
        }

        event.setCanceled(true);
    }
}
