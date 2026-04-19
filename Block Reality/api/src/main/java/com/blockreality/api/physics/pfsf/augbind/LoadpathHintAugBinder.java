package com.blockreality.api.physics.pfsf.augbind;

import com.blockreality.api.physics.pfsf.NativePFSFBridge;
import com.blockreality.api.physics.pfsf.PFSFEngine;
import net.minecraft.core.BlockPos;

import java.nio.FloatBuffer;
import java.util.function.Function;

/**
 * v0.4 M2e — LOADPATH_HINT augmentation binder.
 *
 * <p>Informational slot. This kind does not feed any
 * {@code plan_dispatcher} opcode — the v0.4 plan deliberately leaves
 * loadpath hints as a Java-side query surface for mods (HUD overlays,
 * diagnostic commands, AI critics). The binder still publishes via
 * {@link com.blockreality.api.physics.pfsf.PFSFAugmentationHost} so
 * {@code PFSFAugmentationHost.queryVersion} reflects a live mapping.
 *
 * <p>Layout: one float per voxel.
 * <ul>
 *   <li>{@code 1.0f} — the voxel is an anchor (loadpath root).</li>
 *   <li>{@code 0.5f} — structural carrier (non-anchor solid voxel
 *       visible through the material lookup, which implies at least
 *       support participation).</li>
 *   <li>{@code 0.0f} — air / unsupported / not a loadpath
 *       participant.</li>
 * </ul>
 *
 * <p>Active only when the engine has an anchor or material lookup
 * configured — otherwise the binder can't classify voxels and stays
 * silent.
 */
public final class LoadpathHintAugBinder extends AbstractAugBinder {

    public LoadpathHintAugBinder() {
        super(NativePFSFBridge.AugKind.LOADPATH_HINT, Float.BYTES);
    }

    @Override
    protected boolean isActive() {
        return PFSFEngine.getAnchorLookup() != null
                || PFSFEngine.getMaterialLookup() != null;
    }

    @Override
    protected boolean fill(FloatBuffer out, BlockPos origin, int Lx, int Ly, int Lz) {
        Function<BlockPos, Boolean> anchorLookup = PFSFEngine.getAnchorLookup();
        Function<BlockPos, ?>         materialLookup = PFSFEngine.getMaterialLookup();
        if (anchorLookup == null && materialLookup == null) return false;

        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        boolean any = false;
        for (int z = 0; z < Lz; ++z) {
            for (int y = 0; y < Ly; ++y) {
                int rowBase = Lx * (y + Ly * z);
                for (int x = 0; x < Lx; ++x) {
                    probe.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    float v = 0.0f;
                    if (anchorLookup != null) {
                        Boolean isAnchor = anchorLookup.apply(probe);
                        if (Boolean.TRUE.equals(isAnchor)) v = 1.0f;
                    }
                    if (v == 0.0f && materialLookup != null) {
                        if (materialLookup.apply(probe) != null) v = 0.5f;
                    }
                    if (v != 0.0f) {
                        out.put(rowBase + x, v);
                        any = true;
                    }
                }
            }
        }
        return any;
    }
}
