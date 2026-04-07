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
 * PFSF 引擎 — Static Facade（P0 重構）。
 *
 * <p>保留原有 static API 以向下相容所有呼叫者（ServerTickHandler、
 * BlockRealityMod、BrCommand 等），內部委託給 {@link PFSFEngineInstance} singleton。</p>
 *
 * <p>P0 效益：
 * <ul>
 *   <li>單元測試可直接建立 PFSFEngineInstance 注入 mock lookups</li>
 *   <li>多世界場景可建立多個 instance（每個 ServerLevel 一個）</li>
 *   <li>shutdown 後 instance 可 GC，無狀態殘留</li>
 * </ul>
 */
public final class PFSFEngine {

    private static PFSFEngineInstance instance;

    private PFSFEngine() {}

    /** 取得引擎實例（供進階用途，一般透過 static 方法即可） */
    public static PFSFEngineInstance getInstance() { return instance; }

    // ═══ Lifecycle ═══

    public static void init() {
        instance = new PFSFEngineInstance();
        instance.init();
    }

    public static void shutdown() {
        if (instance != null) {
            instance.shutdown();
            instance = null;
        }
    }

    public static boolean isAvailable() {
        return instance != null && instance.isAvailable();
    }

    public static String getStats() {
        return instance != null ? instance.getStats() : "PFSF Engine: NOT INITIALIZED";
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
