package com.blockreality.api.physics.pfsf;

import com.blockreality.api.config.BRConfig;
import com.blockreality.api.material.RMaterial;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.blockreality.api.physics.StructureIslandRegistry;
import com.blockreality.api.physics.StructureIslandRegistry.StructureIsland;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Façade + {@link IPFSFRuntime} adapter for the native {@code libblockreality_pfsf}
 * runtime.
 */
public final class NativePFSFRuntime {

    private static final Logger LOGGER = LoggerFactory.getLogger("PFSF-NativeRT");

    public static final String ACTIVATION_PROPERTY = "blockreality.native.pfsf";

    private static final boolean KERNELS_PORTED = true;

    private static final boolean       FLAG_ENABLED   = Boolean.getBoolean(ACTIVATION_PROPERTY);
    private static final AtomicBoolean INIT_ATTEMPTED = new AtomicBoolean(false);

    private static volatile long    handle = 0L;
    private static volatile boolean active = false;

    private static final RuntimeView VIEW = new RuntimeView();

    private NativePFSFRuntime() {}

    public static boolean isActive()        { return active;      }
    public static boolean isFlagEnabled()   { return FLAG_ENABLED; }
    public static boolean isLibraryLoaded() { return NativePFSFBridge.isAvailable(); }
    public static boolean areKernelsPorted(){ return KERNELS_PORTED; }

    public static IPFSFRuntime asRuntime() { return VIEW; }

    public static synchronized void init() {
        if (!INIT_ATTEMPTED.compareAndSet(false, true)) return;

        if (!FLAG_ENABLED) {
            LOGGER.debug("Native PFSF runtime disabled: -D{} is not set.", ACTIVATION_PROPERTY);
            return;
        }
        if (!NativePFSFBridge.isAvailable()) {
            LOGGER.warn("Native PFSF runtime requested (-D{}=true) but libblockreality_pfsf was not loaded.", ACTIVATION_PROPERTY);
            return;
        }

        long h = 0L;
        try {
            h = NativePFSFBridge.nativeCreate(50_000, Math.max(1, BRConfig.getPFSFTickBudgetMs()), 512L * 1024 * 1024, true, true);
            if (h == 0L) return;

            int rc = NativePFSFBridge.nativeInit(h);
            if (rc != NativePFSFBridge.PFSFResult.OK) {
                NativePFSFBridge.nativeDestroy(h);
                return;
            }

            handle = h;
            active = true;
            try {
                NativePFSFBridge.nativeSetPCGEnabled(h, BRConfig.isPFSFPCGEnabled());
            } catch (UnsatisfiedLinkError ignored) {}
            LOGGER.info("Native PFSF runtime attached (handle=0x{}, kernels_ported={})", Long.toHexString(h), KERNELS_PORTED);
        } catch (Throwable t) {
            LOGGER.error("Native PFSF init failed", t);
            if (h != 0L) NativePFSFBridge.nativeDestroy(h);
            active = false;
            handle = 0L;
        }
    }

    public static synchronized void shutdown() {
        long h = handle;
        handle = 0L; active = false;
        INIT_ATTEMPTED.set(false);
        if (h != 0L) NativePFSFBridge.nativeDestroy(h);
    }

    public static String getStatus() {
        if (active) return String.format("Native PFSF: %s", KERNELS_PORTED ? "ROUTING" : "ATTACHED");
        return "Native PFSF: INACTIVE";
    }

    /**
     * Notifies the native engine that an island is being removed (eviction, world unload, etc.).
     * Called from PFSFEngine.removeBuffer so IslandBufferEvictor and AABB-expansion paths
     * also clean up the native GPU allocation — not just explicit API callers.
     */
    static void notifyIslandRemoved(int islandId) {
        if (active && handle != 0L) {
            NativePFSFBridge.nativeRemoveIsland(handle, islandId);
            VIEW.nativeIslandDims.remove(islandId);
        }
    }

    static final class RuntimeView implements IPFSFRuntime {
        private long lastProcessedEpoch = -1;

        // Tracks (lx, ly, lz) registered per island in the native engine.
        // nativeAddIsland is called only when an island is new or its AABB changed.
        // C++ getOrCreate re-allocates GPU buffers when dims differ, so re-adding is safe.
        final java.util.Map<Integer, int[]> nativeIslandDims =
                new java.util.concurrent.ConcurrentHashMap<>();

        @Override public void init() { NativePFSFRuntime.init(); }
        @Override public void shutdown() { NativePFSFRuntime.shutdown(); }
        @Override public boolean isAvailable() { return active && KERNELS_PORTED; }
        @Override public String getStats() { return getStatus(); }

        @Override
        public void onServerTick(ServerLevel level, List<ServerPlayer> players, long currentEpoch) {
            if (!isAvailable()) return;

            Map<Integer, StructureIsland> dirtyIslands = StructureIslandRegistry.getDirtyIslands(lastProcessedEpoch);
            if (dirtyIslands.isEmpty()) {
                lastProcessedEpoch = currentEpoch;
                return;
            }

            // Two-pass: first prepare all islands, then tick together.
            // This lets nativeTickDbb batch all dirty islands in one submit.
            java.util.List<Integer> tickableIds = new java.util.ArrayList<>(dirtyIslands.size());
            for (int id : dirtyIslands.keySet()) {
                StructureIsland island = dirtyIslands.get(id);
                PFSFIslandBuffer buf = PFSFBufferManager.getBuffer(id);
                if (buf == null) buf = PFSFBufferManager.getOrCreateBuffer(island);
                if (buf == null) continue;  // VRAM budget rejected

                // Register island with native engine (or re-register on AABB change).
                int lx = buf.getLx(), ly = buf.getLy(), lz = buf.getLz();
                int[] registered = nativeIslandDims.get(id);
                if (registered == null || registered[0] != lx || registered[1] != ly || registered[2] != lz) {
                    net.minecraft.core.BlockPos origin = buf.getOrigin();
                    int rc = NativePFSFBridge.nativeAddIsland(handle, id,
                            origin.getX(), origin.getY(), origin.getZ(), lx, ly, lz);
                    if (rc != NativePFSFBridge.PFSFResult.OK) {
                        LOGGER.warn("nativeAddIsland failed for island {} (rc={})", id, rc);
                        continue;
                    }
                    nativeIslandDims.put(id, new int[]{lx, ly, lz});
                }

                // Compute & normalise source/conductivity into hostCoalescedBuf (zero-copy DBB).
                PFSFDataBuilder.updateSourceAndConductivity(buf, island, level,
                        PFSFEngine.getMaterialLookup(), PFSFEngine.getAnchorLookup(),
                        PFSFEngine.getFillRatioLookup(), PFSFEngine.getCuringLookup(),
                        PFSFEngine.getCurrentWindVec(), null);

                // Register persistent host-side DBBs so native reads from hostCoalescedBuf.
                int regRc = NativePFSFBridge.nativeRegisterIslandBuffers(handle, id,
                        buf.getPhiBufAsBB(), buf.getSourceBufAsBB(), buf.getCondBufAsBB(),
                        buf.getTypeBufAsBB(), buf.getRcompBufAsBB(), buf.getRtensBufAsBB(),
                        buf.getMaxPhiBufAsBB());
                if (regRc != NativePFSFBridge.PFSFResult.OK) {
                    LOGGER.warn("nativeRegisterIslandBuffers failed for island {} (rc={})", id, regRc);
                    continue;
                }

                tickableIds.add(id);
            }

            if (tickableIds.isEmpty()) {
                lastProcessedEpoch = currentEpoch;
                return;
            }

            int[] dirtyIds = tickableIds.stream().mapToInt(Integer::intValue).toArray();
            ByteBuffer failBuf = ByteBuffer.allocateDirect(4 + 1024 * 16)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            failBuf.putInt(0, 0); // C++ reads header count and appends; must start at 0
            int rc = NativePFSFBridge.nativeTickDbb(handle, dirtyIds, currentEpoch, failBuf);
            if (rc == NativePFSFBridge.PFSFResult.OK) {
                int count = failBuf.getInt(0);
                for (int i = 0; i < Math.min(count, 1024); i++) {
                    int x        = failBuf.getInt(4  + i * 16);
                    int y        = failBuf.getInt(8  + i * 16);
                    int z        = failBuf.getInt(12 + i * 16);
                    int nativeType = failBuf.getInt(16 + i * 16);
                    BlockPos pos = new BlockPos(x, y, z);
                    com.blockreality.api.physics.FailureType type = switch (nativeType) {
                        case 2  -> com.blockreality.api.physics.FailureType.CRUSHING;
                        case 3  -> com.blockreality.api.physics.FailureType.NO_SUPPORT;
                        case 4  -> com.blockreality.api.physics.FailureType.TENSION_BREAK;
                        default -> com.blockreality.api.physics.FailureType.CANTILEVER_BREAK;
                    };
                    com.blockreality.api.collapse.CollapseManager.triggerPFSFCollapse(level, pos, type);
                }
                for (int id : dirtyIds) StructureIslandRegistry.markProcessed(id);
            }
            lastProcessedEpoch = currentEpoch;
        }

        @Override
        public void notifyBlockChange(int islandId, BlockPos pos, RMaterial newMaterial, Set<BlockPos> anchors) {
            if (!active) return;
            NativePFSFBridge.nativeMarkFullRebuild(handle, islandId);
        }

        @Override public void setMaterialLookup(Function<BlockPos, RMaterial> lookup) {}
        @Override public void setAnchorLookup(Function<BlockPos, Boolean> lookup) {}
        @Override public void setFillRatioLookup(Function<BlockPos, Float> lookup) {}
        @Override public void setCuringLookup(Function<BlockPos, Float> lookup) {}

        @Override
        public void setWindVector(Vec3 wind) {
            if (active && wind != null) NativePFSFBridge.nativeSetWind(handle, (float)wind.x, (float)wind.y, (float)wind.z);
        }

        @Override
        public void removeBuffer(int islandId) {
            // Native engine GPU cleanup is handled by notifyIslandRemoved (called from PFSFEngine).
            // This override exists for IPFSFRuntime contract completeness.
            notifyIslandRemoved(islandId);
        }
    }
}
