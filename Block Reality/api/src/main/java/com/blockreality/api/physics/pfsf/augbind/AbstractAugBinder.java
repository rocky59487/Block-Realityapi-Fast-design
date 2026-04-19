package com.blockreality.api.physics.pfsf.augbind;

import com.blockreality.api.physics.pfsf.NativePFSFBridge;
import com.blockreality.api.physics.pfsf.PFSFAugmentationHost;
import com.blockreality.api.physics.pfsf.PFSFBufferManager;
import com.blockreality.api.physics.pfsf.PFSFIslandBuffer;
import net.minecraft.core.BlockPos;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v0.4 M2d — base class for SPI augmentation binders.
 *
 * <p>A binder fires once per island per tick (see
 * {@link PFSFAugmentationHost#runBinders(int)}) and is responsible for
 * populating a direct {@link ByteBuffer} matching the island's voxel
 * grid with per-voxel values that a PFSF plan opcode will consume. The
 * buffer layout is flat row-major — {@code index = x + Lx*(y + Ly*z)}
 * with {@code (x,y,z)} as offsets from {@link PFSFIslandBuffer#getOrigin()}.
 *
 * <p>Subclasses implement {@link #isActive()} (cheap guard, avoids the
 * per-voxel sweep when the SPI manager isn't wired) and
 * {@link #fill(FloatBuffer, BlockPos, int, int, int)} (the actual fill).
 * The base class caches the DBB per-island so fresh ticks don't reallocate
 * and strong-refs it via {@link PFSFAugmentationHost#publish} so the GC
 * cannot reclaim the buffer while the native registry still holds its
 * raw address.
 *
 * <p>When {@link #fill} reports {@code false} (no non-zero voxel), the
 * slot is cleared — the dispatcher's aug opcode then treats this island
 * as unaugmented for this kind, which is the "SPI offline" code path
 * that {@code AugmentationOffTest} asserts against.
 */
public abstract class AbstractAugBinder implements PFSFAugmentationHost.AugBinder {

    private final int kind;
    private final int strideBytes;
    private final Map<Integer, ByteBuffer> bufCache = new ConcurrentHashMap<>();

    /**
     * @param kind        one of {@link NativePFSFBridge.AugKind}
     * @param strideBytes bytes per voxel entry (4 = float, 12 = float3)
     */
    protected AbstractAugBinder(int kind, int strideBytes) {
        if (strideBytes <= 0 || (strideBytes & 3) != 0) {
            throw new IllegalArgumentException(
                    "strideBytes must be a positive multiple of 4 (got " + strideBytes + ")");
        }
        this.kind = kind;
        this.strideBytes = strideBytes;
    }

    public final int kind() { return kind; }
    public final int strideBytes() { return strideBytes; }

    @Override
    public final void bind(int islandId) {
        if (!isActive()) {
            /* SPI offline → drop any stale registration. The first tick
             * after a manager is unregistered still needs this clear, so
             * we can't skip it — otherwise the native dispatcher would
             * read a zombie DBB. */
            PFSFAugmentationHost.clear(islandId, kind);
            return;
        }
        PFSFIslandBuffer buf = PFSFBufferManager.getBuffer(islandId);
        if (buf == null || !buf.isAllocated()) return;

        int Lx = buf.getLx();
        int Ly = buf.getLy();
        int Lz = buf.getLz();
        if (Lx <= 0 || Ly <= 0 || Lz <= 0) return;
        int n = Lx * Ly * Lz;
        int bytes = n * strideBytes;

        ByteBuffer dbb = bufCache.get(islandId);
        if (dbb == null || dbb.capacity() < bytes) {
            dbb = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
            bufCache.put(islandId, dbb);
        } else {
            /* Zero the live region so sparse fillers don't leak last
             * tick's non-zero entries into voxels that are no-op this
             * tick. */
            for (int i = 0; i < bytes; ++i) dbb.put(i, (byte) 0);
        }

        FloatBuffer fb = dbb.asFloatBuffer();
        fb.position(0);
        fb.limit(bytes / Float.BYTES);

        boolean any;
        try {
            any = fill(fb, buf.getOrigin(), Lx, Ly, Lz);
        } catch (Throwable t) {
            /* Same contract as PFSFAugmentationHost.runBinders — we
             * swallow binder faults rather than blowing up the tick. */
            any = false;
        }

        if (!any) {
            PFSFAugmentationHost.clear(islandId, kind);
            return;
        }
        PFSFAugmentationHost.publish(islandId, kind, dbb, strideBytes);
    }

    /**
     * Cheap guard — return false when the matching SPI manager isn't
     * registered, so the per-voxel sweep is skipped. Default: always
     * active; override to check {@code ModuleRegistry.getXxxManager()}.
     */
    protected boolean isActive() { return true; }

    /**
     * Public test accessor for {@link #isActive()} — {@code AugmentationOffTest}
     * needs to probe every binder's offline state but the SPI contract
     * keeps the method protected so production call sites can't cheat.
     */
    public final boolean isActiveForTest() { return isActive(); }

    /**
     * Fill the DBB with per-voxel contributions.
     *
     * @param out    cleared float view — index {@code x + Lx*(y + Ly*z)}
     *               (multiply by {@code strideBytes/4} for multi-component).
     * @param origin world-space min corner.
     * @return whether at least one voxel was written non-zero.
     */
    protected abstract boolean fill(FloatBuffer out, BlockPos origin, int Lx, int Ly, int Lz);

    /**
     * Call on island dispose so the binder's DBB cache is reclaimable.
     * {@link PFSFAugmentationHost#clearIsland} is expected to run
     * separately — this only drops the Java-side cache.
     */
    public final void releaseIsland(int islandId) {
        bufCache.remove(islandId);
    }

    /**
     * Drop every cached DBB this binder owns. Called by
     * {@link PFSFAugmentationHost#unregisterBinder} on engine shutdown so
     * direct memory does not leak across re-init cycles. This path is
     * essential on native-off builds, where
     * {@link PFSFAugmentationHost#clearAllFully()} finds zero entries in
     * {@code STRONG_REFS} (publish short-circuited on {@code hasComputeV5()==false})
     * and would otherwise skip the per-island release loop entirely —
     * leaving the accumulated bufCache retained until the JVM exits.
     */
    public final void releaseAllCached() {
        bufCache.clear();
    }
}
