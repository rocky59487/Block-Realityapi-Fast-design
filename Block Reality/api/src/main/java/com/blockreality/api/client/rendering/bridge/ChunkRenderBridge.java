package com.blockreality.api.client.rendering.bridge;

import com.blockreality.api.client.rendering.lod.BRVoxelLODManager;
import com.blockreality.api.client.rendering.lod.LODChunkManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Chunk Render Bridge — 從 Minecraft 世界讀取方塊資料，供 LOD 系統使用。
 *
 * <p>實作 {@link LODChunkManager.BlockDataProvider}，
 * 透過 {@code ClientLevel} 讀取 16×16×16 section 的方塊 ID。
 *
 * <p>同時提供靜態方法供 {@link ForgeRenderEventBridge} 呼叫，
 * 將 chunk/section 事件轉發至 {@link BRVoxelLODManager}。
 *
 * @author Block Reality Team
 */
@OnlyIn(Dist.CLIENT)
public final class ChunkRenderBridge implements LODChunkManager.BlockDataProvider {

    private static final Logger LOG = LoggerFactory.getLogger("BR-ChunkBridge");

    /** 單例 */
    private static final ChunkRenderBridge INSTANCE = new ChunkRenderBridge();

    public static ChunkRenderBridge getInstance() { return INSTANCE; }

    private ChunkRenderBridge() {}

    // ─────────────────────────────────────────────────────────────────
    //  BlockDataProvider 實作
    // ─────────────────────────────────────────────────────────────────

    /**
     * 從 ClientLevel 取得指定 section 的方塊 ID 陣列。
     *
     * @param sectionX section X（等同 chunk X）
     * @param sectionY section Y（-4 to 19 for 1.20.1）
     * @param sectionZ section Z（等同 chunk Z）
     * @return short[16×16×16]，索引格式 y*256+z*16+x；若 section 未載入則返回 null
     */
    @Override
    public short[] getBlockData(int sectionX, int sectionY, int sectionZ) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return null;

        LevelChunk chunk = level.getChunkSource().getChunkNow(sectionX, sectionZ);
        if (chunk == null) return null;

        // 1.20.1 section index：sectionY + 4（因為 minSection = -4）
        int minSection = level.getMinSection(); // typically -4
        int sectionIdx = sectionY - minSection;

        if (sectionIdx < 0 || sectionIdx >= chunk.getSectionsCount()) return null;

        net.minecraft.world.level.chunk.LevelChunkSection section = chunk.getSections()[sectionIdx];
        if (section == null || section.hasOnlyAir()) {
            return null; // 全空氣，跳過
        }

        short[] blocks = new short[16 * 16 * 16];
        net.minecraft.world.level.block.state.BlockState air =
            net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();

        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    net.minecraft.world.level.block.state.BlockState state = section.getBlockState(x, y, z);
                    if (state != null && !state.isAir()) {
                        // 使用 Block registry ID 作為識別
                        int id = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                            .getId(state.getBlock());
                        blocks[y * 256 + z * 16 + x] = (short) Math.min(id, Short.MAX_VALUE);
                    }
                }
            }
        }
        return blocks;
    }

    // ─────────────────────────────────────────────────────────────────
    //  LOD Manager 初始化
    // ─────────────────────────────────────────────────────────────────

    /**
     * 在 GL context 就緒後呼叫，初始化 LOD 系統。
     */
    public static void initLODSystem() {
        BRVoxelLODManager manager = BRVoxelLODManager.getInstance();
        manager.init(INSTANCE);
        LOG.info("LOD system initialized via ChunkRenderBridge");
    }

    /**
     * 當世界卸載（玩家離開伺服器/單機存檔）時呼叫。
     */
    public static void onWorldUnload() {
        BRVoxelLODManager manager = BRVoxelLODManager.getInstance();
        manager.shutdown();
        LOG.info("LOD system shutdown on world unload");
    }

    // ─────────────────────────────────────────────────────────────────
    //  Chunk 事件轉發
    // ─────────────────────────────────────────────────────────────────

    /** 當 chunk 載入時由 ForgeRenderEventBridge 呼叫。 */
    public static void onChunkLoad(int chunkX, int chunkZ, LevelAccessor level) {
        if (!(level instanceof ClientLevel clientLevel)) return;

        BRVoxelLODManager mgr = BRVoxelLODManager.getInstance();
        int minSection = clientLevel.getMinSection();
        int maxSection = clientLevel.getMaxSection();

        for (int sy = minSection; sy < maxSection; sy++) {
            mgr.onSectionLoad(chunkX, sy, chunkZ);
        }
    }

    /** 當 chunk 卸載時由 ForgeRenderEventBridge 呼叫。 */
    public static void onChunkUnload(int chunkX, int chunkZ) {
        BRVoxelLODManager.getInstance().onChunkUnload(chunkX, chunkZ);
    }

    /** 當方塊更新時由 ForgeRenderEventBridge 呼叫。 */
    public static void onBlockChange(int worldX, int worldY, int worldZ) {
        BRVoxelLODManager.getInstance().onBlockChange(worldX, worldY, worldZ);
    }
}
