package com.blockreality.api.physics.pfsf.augbind;

import com.blockreality.api.physics.pfsf.NativePFSFBridge;
import com.blockreality.api.spi.IElectromagneticManager;
import com.blockreality.api.spi.ModuleRegistry;
import net.minecraft.core.BlockPos;

import java.nio.FloatBuffer;

/**
 * v0.4 M2d — EM_FIELD augmentation binder.
 *
 * <p>Reads {@link IElectromagneticManager#getCurrentDensityAt} per voxel
 * and feeds {@code J²} as a Joule-heating source term via
 * {@code OP_AUG_SOURCE_ADD}. Pairs with the Joule → thermal coupling:
 * the EM solver is already running on its own diffusion grid, we just
 * thread its dissipation back into PFSF's source so structural
 * thermal-stress picks it up.
 */
public final class EMAugBinder extends AbstractAugBinder {

    public EMAugBinder() {
        super(NativePFSFBridge.AugKind.EM_FIELD, Float.BYTES);
    }

    @Override
    protected boolean isActive() {
        return ModuleRegistry.getEmManager() != null;
    }

    @Override
    protected boolean fill(FloatBuffer out, BlockPos origin, int Lx, int Ly, int Lz) {
        IElectromagneticManager em = ModuleRegistry.getEmManager();
        if (em == null) return false;

        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        boolean any = false;
        for (int z = 0; z < Lz; ++z) {
            for (int y = 0; y < Ly; ++y) {
                int rowBase = Lx * (y + Ly * z);
                for (int x = 0; x < Lx; ++x) {
                    probe.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    float j = em.getCurrentDensityAt(probe);
                    float heat = j * j;
                    if (heat != 0.0f) {
                        out.put(rowBase + x, heat);
                        any = true;
                    }
                }
            }
        }
        return any;
    }
}
