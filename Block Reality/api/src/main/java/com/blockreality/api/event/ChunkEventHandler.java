package com.blockreality.api.event;

import com.blockreality.api.BlockRealityMod;
import com.blockreality.api.physics.LoadPathEngine;
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
 * Chunk Event Handler — Lightweight cleanup when chunks are unloaded.
 *
 * 設計原則：
 *   - 區塊卸載 ≠ 方塊破壞 — 不觸發崩塌、不生成 FallingBlockEntity
 *   - 只清理記憶體中的支撐鏈和纜索數據結構
 *   - 區塊重新加載時，NBT load() 會恢復 supportParent 鏈
 *
 * Forge 1.20.1 鉤子：
 *   - ChunkEvent.Unload：區塊卸載前觸發
 *   - RBlockEntity.onChunkUnloaded()：由 Forge 在 setRemoved() 前呼叫
 *
 * v3fix §10 合規：區塊卸載清理機制。
 *
 * @see LoadPathEngine#onChunkUnload(ServerLevel, ChunkPos)
 * @see com.blockreality.api.spi.ICableManager#removeChunkCables(ChunkPos)
 */
@Mod.EventBusSubscriber(modid = BlockRealityMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ChunkEventHandler {

    private static final Logger LOGGER = LogManager.getLogger("BR-ChunkEvent");

    /**
     * 區塊卸載事件 — 清理該區塊內的纜索和支撐鏈。
     *
     * Chunk unload event — clean up cables and support chains within the chunk.
     *
     * 執行順序：
     *   1. 跳過 client-side（物理只在 server 執行）
     *   2. 清理纜索附著（removeChunkCables）
     *   3. 清理支撐鏈（onChunkUnload — 僅歸零 parent/load，不觸發崩塌）
     *
     * @param event Forge ChunkEvent.Unload
     */
    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        ChunkPos chunkPos = event.getChunk().getPos();

        // 1. 清理纜索（兩端都在此 chunk 內的纜索）
        int cablesRemoved = ModuleRegistry.getCableManager().removeChunkCables(chunkPos);

        // 2. 清理支撐鏈（輕量，不觸發崩塌）
        LoadPathEngine.onChunkUnload(level, chunkPos);

        if (cablesRemoved > 0) {
            LOGGER.debug("[BR-ChunkEvent] Chunk [{}, {}] unload: removed {} cables",
                chunkPos.x, chunkPos.z, cablesRemoved);
        }
    }
}
