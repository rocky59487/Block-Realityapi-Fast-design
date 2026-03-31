package com.blockreality.api.client.rendering.bridge;

import com.blockreality.api.client.rendering.lod.LODChunkManager;
import com.blockreality.api.client.rendering.lod.LODRenderDispatcher;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forge Chunk 事件 → LOD 管線橋接器。
 *
 * 監聽 Forge 的 ChunkEvent.Load / Unload，
 * 轉發給 {@link LODChunkManager} 觸發 LOD mesh 建構/回收。
 *
 * 相當於 Voxy 中 Fabric 的 ChunkBuilderPlugin，
 * 但使用 Forge 事件系統（這是 Voxy 移植的關鍵差異點）。
 *
 * @see LODChunkManager
 * @see LODRenderDispatcher
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = "blockreality", value = Dist.CLIENT)
public class ChunkRenderBridge {

    private static final Logger LOG = LoggerFactory.getLogger("BR-ChunkBridge");

    private static LODRenderDispatcher dispatcher;

    /**
     * 綁定 LOD 渲染調度器（模組初始化時呼叫）。
     */
    public static void bind(LODRenderDispatcher lodDispatcher) {
        dispatcher = lodDispatcher;
        LOG.info("ChunkRenderBridge bound to LODRenderDispatcher");
    }

    /**
     * 解綁（模組卸載時呼叫）。
     */
    public static void unbind() {
        dispatcher = null;
        LOG.info("ChunkRenderBridge unbound");
    }

    private static LODRenderDispatcher resolveDispatcher() {
        return dispatcher != null ? dispatcher : LODRenderDispatcher.getInstance();
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        LODRenderDispatcher disp = resolveDispatcher();
        if (disp == null || !disp.isInitialized()) return;
        if (event.getLevel() instanceof net.minecraft.world.level.Level lvl && lvl.isClientSide) {
            ChunkPos pos = event.getChunk().getPos();
            disp.getChunkManager().onChunkLoaded(pos.x, pos.z);
        }
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        LODRenderDispatcher disp = resolveDispatcher();
        if (disp == null || !disp.isInitialized()) return;
        if (event.getLevel() instanceof net.minecraft.world.level.Level lvl && lvl.isClientSide) {
            ChunkPos pos = event.getChunk().getPos();
            disp.getChunkManager().onChunkUnloaded(pos.x, pos.z);
        }
    }
}
