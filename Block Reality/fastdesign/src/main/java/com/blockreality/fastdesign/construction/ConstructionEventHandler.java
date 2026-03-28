package com.blockreality.fastdesign.construction;

import com.blockreality.api.construction.ConstructionZone;
import com.blockreality.api.construction.ConstructionZoneManager;
import com.blockreality.fastdesign.FastDesignMod;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 施工工序事件攔截器 — v3fix §3.2
 *
 * 負責擴充互動：攔截方塊放置，檢查是否符合當前工序規範。
 * 養護 tick 由 API 層的 ServerTickHandler 驅動。
 */
@Mod.EventBusSubscriber(modid = FastDesignMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ConstructionEventHandler {

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        ServerLevel level = (ServerLevel) event.getLevel();
        ConstructionZoneManager manager = ConstructionZoneManager.get(level);

        ConstructionZone zone = manager.getZoneAt(event.getPos());
        if (zone == null) return;

        String blockId = ForgeRegistries.BLOCKS.getKey(
            event.getPlacedBlock().getBlock()).toString();

        if (!zone.canPlace(blockId)) {
            event.setCanceled(true);
            player.sendSystemMessage(Component.literal(String.format(
                "§c[FD-CI] §f禁止放置！當前工序：§e%s\n§7允許方塊：%s",
                zone.getCurrentPhase().getDisplayNameZh(),
                zone.getCurrentPhase().getAllowedListDisplay()
            )));
        } else {
            zone.recordBlockPlaced(event.getPos());
        }
    }
}
