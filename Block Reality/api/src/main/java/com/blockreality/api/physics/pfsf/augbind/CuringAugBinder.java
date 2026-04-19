package com.blockreality.api.physics.pfsf.augbind;

import com.blockreality.api.physics.pfsf.NativePFSFBridge;
import com.blockreality.api.spi.ICuringManager;
import com.blockreality.api.spi.ModuleRegistry;
import net.minecraft.core.BlockPos;

import java.nio.FloatBuffer;

/**
 * v0.4 M2d — CURING_FIELD augmentation binder.
 *
 * <p>Reads {@link ICuringManager#getCuringProgress} per voxel and feeds
 * {@code (1 - progress)} — the "unmatured" fraction — as both a source
 * penalty ({@code OP_AUG_SOURCE_ADD}) and a resistance multiplier
 * ({@code OP_AUG_RCOMP_MUL}). An uncured pour has little compressive
 * strength and contributes extra self-weight; a fully-cured pour
 * behaves as baseline.
 *
 * <p>The dispatcher treats the same kind for source-add and rcomp-mul
 * because both opcodes share the {@code CURING_FIELD} slot; the native
 * side reads the DBB twice during plan execution.
 */
public final class CuringAugBinder extends AbstractAugBinder {

    public CuringAugBinder() {
        super(NativePFSFBridge.AugKind.CURING_FIELD, Float.BYTES);
    }

    @Override
    protected boolean isActive() {
        return ModuleRegistry.getCuringManager() != null;
    }

    @Override
    protected boolean fill(FloatBuffer out, BlockPos origin, int Lx, int Ly, int Lz) {
        ICuringManager curing = ModuleRegistry.getCuringManager();
        if (curing == null) return false;

        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        boolean any = false;
        for (int z = 0; z < Lz; ++z) {
            for (int y = 0; y < Ly; ++y) {
                int rowBase = Lx * (y + Ly * z);
                for (int x = 0; x < Lx; ++x) {
                    probe.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    float progress = curing.getCuringProgress(probe);
                    /* Clamp to [0,1] — an SPI that reports "not curing"
                     * should read as 1.0 (fully matured, no contribution)
                     * but some default impls return 0 for uncontrolled
                     * blocks. We normalize so (1 - progress) == 0 for
                     * both "not participating" and "fully cured". */
                    if (progress <= 0.0f || progress >= 1.0f) continue;
                    float uncured = 1.0f - progress;
                    out.put(rowBase + x, uncured);
                    any = true;
                }
            }
        }
        return any;
    }
}
