package com.blockreality.api.physics.pfsf;

import com.blockreality.api.material.RMaterial;
import com.blockreality.api.physics.StructureIslandRegistry.StructureIsland;
import com.blockreality.api.physics.StressField;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * PFSF 引擎 — Static Facade（v0.2a + BIFROST）。
 *
 * <p>保留原有 static API 以向下相容所有呼叫者（ServerTickHandler、
 * BlockRealityMod、BrCommand 等），內部委託給 {@link PFSFEngineInstance} singleton。</p>
 *
 * <p>BIFROST 擴展：{@link HybridPhysicsRouter} 根據結構形態路由至
 * PFSF（規則）或 FNO ML 後端（異形）。</p>
 *
 * @see IPFSFRuntime
 * @see HybridPhysicsRouter
 */
public final class PFSFEngine {

    private static PFSFEngineInstance instance;
    private static final HybridPhysicsRouter router = new HybridPhysicsRouter();
    private static final BIFROSTModelRegistry modelRegistry = new BIFROSTModelRegistry();
    private static final ChunkPhysicsLOD chunkLOD = new ChunkPhysicsLOD();
    private static final CognitiveLODManager cognitiveLOD = new CognitiveLODManager();

    private PFSFEngine() {}

    public static PFSFEngineInstance getInstance() { return instance; }
    public static HybridPhysicsRouter getRouter() { return router; }
    public static BIFROSTModelRegistry getModelRegistry() { return modelRegistry; }
    public static CognitiveLODManager getCognitiveLOD() { return cognitiveLOD; }

    /** 取得 Chunk 物理 LOD 管理器 */
    public static ChunkPhysicsLOD getChunkLOD() { return chunkLOD; }

    // ═══ Lifecycle ═══

    public static void init() {
        instance = new PFSFEngineInstance();
        instance.init();

        // BIFROST: load all ML models from config/blockreality/models/
        modelRegistry.init();

        // BIFROST: initialize hybrid router with the already-loaded surrogate runtime.
        // Use initWithRuntime() to inject the pre-loaded OnnxPFSFRuntime directly,
        // avoiding re-reading from disk and the previous "loaded" literal bug.
        OnnxPFSFRuntime surrogate = modelRegistry.getSurrogate();
        router.initWithRuntime(surrogate);
    }

    public static void shutdown() {
        router.shutdown();
        modelRegistry.shutdown();
        if (instance != null) {
            instance.shutdown();
            instance = null;
        }
    }

    public static boolean isAvailable() {
        return instance != null && instance.isAvailable();
    }

    public static String getStats() {
        if (instance == null) return "BIFROST: DISABLED";
        return instance.getStats() + " | " + router.getStats()
                + " | " + modelRegistry.getStats() + " | " + chunkLOD.getStats()
                + " | " + cognitiveLOD.getStats();
    }

    // ═══ Tick ═══

    public static void onServerTick(ServerLevel level, List<ServerPlayer> players, long currentEpoch) {
        if (instance != null) instance.onServerTick(level, players, currentEpoch);
    }

    // ═══ Sparse Dirty Notification ═══

    public static void notifyBlockChange(int islandId, BlockPos pos, RMaterial newMaterial,
                                          Set<BlockPos> anchors) {
        if (instance != null) instance.notifyBlockChange(islandId, pos, newMaterial, anchors);
    }

    // ═══ Configuration ═══

    public static void setMaterialLookup(Function<BlockPos, RMaterial> lookup) {
        if (instance != null) instance.setMaterialLookup(lookup);
    }

    public static void setAnchorLookup(Function<BlockPos, Boolean> lookup) {
        if (instance != null) instance.setAnchorLookup(lookup);
    }

    public static void setFillRatioLookup(Function<BlockPos, Float> lookup) {
        if (instance != null) instance.setFillRatioLookup(lookup);
    }

    public static void setCuringLookup(Function<BlockPos, Float> lookup) {
        if (instance != null) instance.setCuringLookup(lookup);
    }

    public static void setWindVector(net.minecraft.world.phys.Vec3 wind) {
        if (instance != null) instance.setWindVector(wind);
    }

    // ═══ Buffer Access ═══

    public static void removeBuffer(int islandId) {
        if (instance != null) instance.removeBuffer(islandId);
    }

    static StressField extractStressField(PFSFIslandBuffer buf) {
        return instance != null ? instance.extractStressField(buf) : null;
    }

    static long getDescriptorPool() {
        return instance != null ? instance.getDescriptorPool() : 0;
    }

    /** P2 重構：資料上傳上下文（供 PFSFDispatcher 使用） */
    static final class UploadContext {
        final StructureIsland island;
        final ServerLevel level;
        final Function<BlockPos, RMaterial> materialLookup;
        final Function<BlockPos, Boolean> anchorLookup;
        final Function<BlockPos, Float> fillRatioLookup;
        final Function<BlockPos, Float> curingLookup;
        final net.minecraft.world.phys.Vec3 windVec;

        UploadContext(StructureIsland island, ServerLevel level,
                      Function<BlockPos, RMaterial> mat, Function<BlockPos, Boolean> anchor,
                      Function<BlockPos, Float> fill, Function<BlockPos, Float> curing,
                      net.minecraft.world.phys.Vec3 wind) {
            this.island = island; this.level = level;
            this.materialLookup = mat; this.anchorLookup = anchor;
            this.fillRatioLookup = fill; this.curingLookup = curing; this.windVec = wind;
        }
    }
}
