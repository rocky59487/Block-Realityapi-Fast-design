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

    // ★ EIIE-fix: 改為 null 初始化，在 init() 中才建立實例，
    //   避免類別載入時就觸發複雜建構子（HybridPhysicsRouter 等）引發 ExceptionInInitializerError。
    private static PFSFEngineInstance instance;
    private static HybridPhysicsRouter router;
    private static BIFROSTModelRegistry modelRegistry;
    private static ChunkPhysicsLOD chunkLOD;
    private static CognitiveLODManager cognitiveLOD;

    private PFSFEngine() {}

    public static PFSFEngineInstance getInstance() { return instance; }
    public static HybridPhysicsRouter getRouter() { return router; }
    public static BIFROSTModelRegistry getModelRegistry() { return modelRegistry; }
    public static CognitiveLODManager getCognitiveLOD() { return cognitiveLOD; }

    /**
     * Strategy selection point for the PFSF solver backend.
     *
     * <p>Returns the native {@link IPFSFRuntime} adapter iff
     * {@link NativePFSFRuntime#asRuntime()} reports
     * {@link IPFSFRuntime#isAvailable()} — i.e. the activation flag is on,
     * {@code libblockreality_pfsf} loaded, {@code pfsf_init()} succeeded, AND
     * the solver kernels have been ported (gated by the internal
     * {@code KERNELS_PORTED} constant, M2b milestone). Otherwise returns the
     * Java {@link PFSFEngineInstance}.</p>
     *
     * <p>Call sites that want to opt into native routing without waiting for
     * the M6 flag-flip should prefer this accessor over {@link #getInstance()}.
     * Until M2b the two are equivalent in production — the Strategy seam
     * exists so we can swap solvers without touching callers.</p>
     */
    public static IPFSFRuntime getRuntime() {
        IPFSFRuntime native_ = NativePFSFRuntime.asRuntime();
        return native_.isAvailable() ? native_ : instance;
    }

    /** 取得 Chunk 物理 LOD 管理器 */
    public static ChunkPhysicsLOD getChunkLOD() { return chunkLOD; }

    // ═══ Lifecycle ═══

    public static void init() {
        // v0.3c Phase 1: try the native runtime first. Safe no-op unless the
        // -Dblockreality.native.pfsf=true flag is set AND libblockreality_pfsf
        // loaded. Java solver still initialises as the authoritative path for
        // Phase 1 — native is attached alongside it for diagnostic parity.
        NativePFSFRuntime.init();

        // ★ EIIE-fix: 在 init() 中建立所有實例，保證執行順序可控
        router        = new HybridPhysicsRouter();
        modelRegistry = new BIFROSTModelRegistry();
        chunkLOD      = new ChunkPhysicsLOD();
        cognitiveLOD  = new CognitiveLODManager();

        instance = new PFSFEngineInstance();
        instance.init();

        // BIFROST: load all ML models from config/blockreality/models/
        modelRegistry.init();

        // BIFROST: initialize hybrid router with the already-loaded surrogate runtime.
        OnnxPFSFRuntime surrogate = modelRegistry.getSurrogate();
        router.initWithRuntime(surrogate);
    }

    public static void shutdown() {
        if (router != null) router.shutdown();
        if (modelRegistry != null) modelRegistry.shutdown();
        if (instance != null) {
            instance.shutdown();
            instance = null;
        }
        // Mirror init() order — release the native handle last.
        NativePFSFRuntime.shutdown();
    }

    public static boolean isAvailable() {
        return instance != null && instance.isAvailable();
    }

    public static String getStats() {
        if (instance == null) return "BIFROST: DISABLED";
        return instance.getStats() + " | " + router.getStats()
                + " | " + modelRegistry.getStats() + " | " + chunkLOD.getStats()
                + " | " + cognitiveLOD.getStats()
                + " | " + NativePFSFRuntime.getStatus();
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

    // ═══ Lookup accessors — v0.4 M2e aug binders read material/anchor/
    //     curing/fillRatio by BlockPos without re-deriving them from the
    //     ServerLevel (which the binder hot-path doesn't hold a reference
    //     to). Returns {@code null} when the corresponding hook hasn't
    //     been wired, so callers must null-check. ═══

    public static Function<BlockPos, RMaterial> getMaterialLookup() {
        return instance != null ? instance.getMaterialLookup() : null;
    }

    public static Function<BlockPos, Boolean> getAnchorLookup() {
        return instance != null ? instance.getAnchorLookup() : null;
    }

    public static Function<BlockPos, Float> getFillRatioLookup() {
        return instance != null ? instance.getFillRatioLookup() : null;
    }

    public static Function<BlockPos, Float> getCuringLookup() {
        return instance != null ? instance.getCuringLookup() : null;
    }

    public static net.minecraft.world.phys.Vec3 getCurrentWindVec() {
        return instance != null ? instance.getCurrentWindVec() : null;
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
        /** 流體壓力查詢（FluidStructureCoupler 提供；null = 無流體耦合）*/
        final Function<BlockPos, Float> fluidPressureLookup;

        UploadContext(StructureIsland island, ServerLevel level,
                      Function<BlockPos, RMaterial> mat, Function<BlockPos, Boolean> anchor,
                      Function<BlockPos, Float> fill, Function<BlockPos, Float> curing,
                      net.minecraft.world.phys.Vec3 wind,
                      Function<BlockPos, Float> fluidPressure) {
            this.island = island; this.level = level;
            this.materialLookup = mat; this.anchorLookup = anchor;
            this.fillRatioLookup = fill; this.curingLookup = curing; this.windVec = wind;
            this.fluidPressureLookup = fluidPressure;
        }
    }
}
