package com.blockreality.api.physics.pfsf.augbind;

import com.blockreality.api.physics.pfsf.NativePFSFBridge;
import com.blockreality.api.spi.IFluidManager;
import com.blockreality.api.spi.ModuleRegistry;
import net.minecraft.core.BlockPos;

import java.nio.FloatBuffer;

/**
 * v0.4 M2d — FLUID_PRESSURE augmentation binder.
 *
 * <p>Reads {@link IFluidManager#getFluidPressureAt} per voxel and
 * publishes it as an additive source contribution via
 * {@code OP_AUG_SOURCE_ADD} — hydrostatic pressure adds to the gravity
 * source term. Units: Pa (same convention as the fluid engine); sigma-
 * normalisation happens downstream in {@code PFSFDataBuilder}.
 */
public final class FluidAugBinder extends AbstractAugBinder {

    public FluidAugBinder() {
        super(NativePFSFBridge.AugKind.FLUID_PRESSURE, Float.BYTES);
    }

    @Override
    protected boolean isActive() {
        return ModuleRegistry.getFluidManager() != null;
    }

    @Override
    protected boolean fill(FloatBuffer out, BlockPos origin, int Lx, int Ly, int Lz) {
        IFluidManager fluid = ModuleRegistry.getFluidManager();
        if (fluid == null) return false;

        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        boolean any = false;
        for (int z = 0; z < Lz; ++z) {
            for (int y = 0; y < Ly; ++y) {
                int rowBase = Lx * (y + Ly * z);
                for (int x = 0; x < Lx; ++x) {
                    probe.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    float p = fluid.getFluidPressureAt(probe);
                    if (p != 0.0f) {
                        out.put(rowBase + x, p);
                        any = true;
                    }
                }
            }
        }
        return any;
    }
}
