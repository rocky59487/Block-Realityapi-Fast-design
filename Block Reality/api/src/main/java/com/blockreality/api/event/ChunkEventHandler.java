package com.blockreality.api.event;

import com.blockreality.api.BlockRealityMod;
import com.blockreality.api.spi.ModuleRegistry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 區塊事件處理器 — 處理 chunk 卸載時的輕量清理。
 *
 * 結構載重由 PFSF GPU 引擎管理，此處僅清理纜索。
 */
@Mod.EventBusSubscriber(modid = BlockRealityMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ChunkEventHandler {

    private static final Logger LOGGER = LogManager.getLogger("BR-ChunkEvent");

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        ChunkPos chunkPos = event.getChunk().getPos();

        // 清理纜索（兩端都在此 chunk 內的纜索）
        int cablesRemoved = ModuleRegistry.getCableManager().removeChunkCables(chunkPos);

        if (cablesRemoved > 0) {
            LOGGER.debug("[BR-ChunkEvent] Chunk [{}, {}] unload: removed {} cables",
                chunkPos.x, chunkPos.z, cablesRemoved);
        }
    }
}
