package com.blockreality.api.physics.pfsf;

import com.blockreality.api.material.RMaterial;
import com.blockreality.api.physics.StressField;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * PFSF 引擎 — Static Facade。
 *
 * <p>保留原有 static API 以向下相容所有呼叫者（ServerTickHandler、
 * BlockRealityMod、BrCommand 等），內部委託給 {@link IPFSFRuntime} singleton。</p>
 *
 * <p>預設實作為 {@link PFSFEngineInstance}（Java/LWJGL Vulkan）。
 * 未來可替換為 NativePFSFRuntime（C++ libpfsf via JNI）。</p>
 *
 * @since v0.3a (libpfsf Phase 0)
 * @see IPFSFRuntime
 * @see PFSFEngineInstance
 */
public final class PFSFEngine {

    private static IPFSFRuntime instance;
    private static final HybridPhysicsRouter router = new HybridPhysicsRouter();

    private PFSFEngine() {}

    /** 取得引擎實例（供進階用途，一般透過 static 方法即可）。 */
    public static IPFSFRuntime getInstance() { return instance; }

    /** 取得混合路由器（供 BrCommand 診斷用）。 */
    public static HybridPhysicsRouter getRouter() { return router; }

    // ═══ Lifecycle ═══

    public static void init() {
        instance = new PFSFEngineInstance();
        instance.init();

        // Initialize hybrid router — try to load FNO model
        String modelPath = null; // Phase 2: BRConfig.getFnoModelPath()
        router.init(modelPath);
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
        if (instance == null) return "PFSF Engine: DISABLED";
        return instance.getStats() + " | " + router.getStats();
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

    // ═══ Package-private (internal callers: PFSFFailureRecorder, PFSFRenderBridge) ═══

    static StressField extractStressField(PFSFIslandBuffer buf) {
        return instance instanceof PFSFEngineInstance eng ? eng.extractStressField(buf) : null;
    }

    static long getDescriptorPool() {
        return instance instanceof PFSFEngineInstance eng ? eng.getDescriptorPool() : 0;
    }
}
