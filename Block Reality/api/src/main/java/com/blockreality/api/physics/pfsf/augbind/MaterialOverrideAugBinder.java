package com.blockreality.api.physics.pfsf.augbind;

import com.blockreality.api.physics.pfsf.NativePFSFBridge;
import net.minecraft.core.BlockPos;

import java.nio.FloatBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * v0.4 M2e — MATERIAL_OVR augmentation binder.
 *
 * <p>Runtime per-voxel conductivity multiplier. External mods register
 * an {@link Override} function via {@link #setOverrideProvider}; the
 * binder reads that function per voxel and feeds the factor into
 * {@code OP_AUG_COND_MUL}. Voxels outside the override's domain should
 * return {@code 1.0f} or the function may return {@code null} / NaN to
 * signal "no override" (the binder treats any of those as a 1.0 no-op).
 *
 * <p>Typical uses: damaged-block overlays (factor {@literal <} 1), ice
 * film on steel (factor {@literal <} 1 via moisture stiffening loss),
 * scaffolding-cured bracing (factor {@literal >} 1 for temporary rigidity).
 *
 * <p>This binder has no built-in SPI — it is intentionally an open
 * hook. When no provider is registered, {@link #isActive} returns
 * false and the slot is cleared.
 */
public final class MaterialOverrideAugBinder extends AbstractAugBinder {

    /** Provider signature: takes a block pos, returns the multiplier
     *  for that voxel. Return 1.0f / null / NaN for "no override". */
    @FunctionalInterface
    public interface OverrideProvider {
        Float factorAt(BlockPos pos);
    }

    /* Per-binder-instance provider. We use AtomicReference instead of
     * volatile to document the write-once-per-configure semantics the
     * SPI owner is expected to follow. */
    private final AtomicReference<OverrideProvider> provider = new AtomicReference<>(null);

    /* Held for test resetForBinding — tests clear every binder's
     * provider to keep fixtures isolated. */
    private static final ConcurrentHashMap<MaterialOverrideAugBinder, Object> INSTANCES =
            new ConcurrentHashMap<>();

    public MaterialOverrideAugBinder() {
        super(NativePFSFBridge.AugKind.MATERIAL_OVR, Float.BYTES);
        INSTANCES.put(this, Boolean.TRUE);
    }

    /** Install a provider. Passing {@code null} removes the provider
     *  and the binder becomes inactive on the next tick. */
    public MaterialOverrideAugBinder setOverrideProvider(OverrideProvider p) {
        provider.set(p);
        return this;
    }

    /** Convenience — wrap a standard {@link Function} as a provider. */
    public MaterialOverrideAugBinder setOverrideProvider(Function<BlockPos, Float> fn) {
        provider.set(fn == null ? null : fn::apply);
        return this;
    }

    @Override
    protected boolean isActive() {
        return provider.get() != null;
    }

    @Override
    protected boolean fill(FloatBuffer out, BlockPos origin, int Lx, int Ly, int Lz) {
        OverrideProvider p = provider.get();
        if (p == null) return false;

        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        boolean any = false;
        for (int z = 0; z < Lz; ++z) {
            for (int y = 0; y < Ly; ++y) {
                int rowBase = Lx * (y + Ly * z);
                for (int x = 0; x < Lx; ++x) {
                    probe.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    Float f = p.factorAt(probe);
                    if (f == null) continue;
                    float v = f;
                    /* NaN is treated as "no override"; anything else is
                     * published even if 1.0 so the opcode still applies
                     * a trivial multiply — the parity oracle depends on
                     * the DBB being registered whenever isActive(). */
                    if (Float.isNaN(v)) continue;
                    out.put(rowBase + x, v);
                    any = true;
                }
            }
        }
        return any;
    }

    /** Test-only — clear every MATERIAL_OVR binder's provider. */
    public static void clearAllProvidersForTesting() {
        for (MaterialOverrideAugBinder b : INSTANCES.keySet()) b.provider.set(null);
    }
}
