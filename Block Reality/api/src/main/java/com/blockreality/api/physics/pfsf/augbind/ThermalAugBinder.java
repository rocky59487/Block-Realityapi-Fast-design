package com.blockreality.api.physics.pfsf.augbind;

import com.blockreality.api.physics.pfsf.NativePFSFBridge;
import com.blockreality.api.spi.IThermalManager;
import com.blockreality.api.spi.ModuleRegistry;
import net.minecraft.core.BlockPos;

import java.nio.FloatBuffer;

/**
 * v0.4 M2d — THERMAL_FIELD augmentation binder.
 *
 * <p>Reads {@link IThermalManager#getTemperatureAt} per voxel and
 * publishes the temperature delta above the ambient (20 °C) baseline as
 * a scalar source contribution. The dispatcher's
 * {@code OP_AUG_SOURCE_ADD} opcode then layers this onto {@code source[i]}
 * at the {@code POST_SOURCE} plan stage.
 *
 * <p>Scaling: temperature delta in °C is handed to the solver raw; the
 * Java-side {@code PFSFDataBuilder} sigma-normalization then divides it
 * by {@code sigmaMax} along with the rest of the source term (matching
 * the {@code applyAugmentationSourceAddJavaRef} oracle in M2f).
 */
public final class ThermalAugBinder extends AbstractAugBinder {

    private static final float AMBIENT_C = 20.0f;

    public ThermalAugBinder() {
        super(NativePFSFBridge.AugKind.THERMAL_FIELD, Float.BYTES);
    }

    @Override
    protected boolean isActive() {
        return ModuleRegistry.getThermalManager() != null;
    }

    @Override
    protected boolean fill(FloatBuffer out, BlockPos origin, int Lx, int Ly, int Lz) {
        IThermalManager thermal = ModuleRegistry.getThermalManager();
        if (thermal == null) return false;

        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        boolean any = false;
        for (int z = 0; z < Lz; ++z) {
            for (int y = 0; y < Ly; ++y) {
                int rowBase = Lx * (y + Ly * z);
                for (int x = 0; x < Lx; ++x) {
                    probe.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    float t = thermal.getTemperatureAt(probe);
                    float delta = t - AMBIENT_C;
                    if (delta != 0.0f) {
                        out.put(rowBase + x, delta);
                        any = true;
                    }
                }
            }
        }
        return any;
    }
}
