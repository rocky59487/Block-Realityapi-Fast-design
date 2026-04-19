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
                    /* PR#187 capy-ai R45: getCuringProgress collapses
                     * "not participating" and "tracked at 0%" both to
                     * 0.0f; the legacy `progress <= 0` skip dropped the
                     * latter — a fresh pour that should carry the
                     * maximum uncured penalty (uncured=1.0) was being
                     * silently excluded from the augmentation. Gate on
                     * isCuring() first so untracked voxels skip, then
                     * trust getCuringProgress for a real reading. */
                    if (!curing.isCuring(probe)) continue;
                    float progress = curing.getCuringProgress(probe);
                    if (progress >= 1.0f) continue;
                    float uncured = 1.0f - Math.max(0.0f, progress);
                    out.put(rowBase + x, uncured);
                    any = true;
                }
            }
        }
        return any;
    }
}
