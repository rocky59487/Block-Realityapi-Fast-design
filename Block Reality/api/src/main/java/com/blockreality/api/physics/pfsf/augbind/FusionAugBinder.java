package com.blockreality.api.physics.pfsf.augbind;

import com.blockreality.api.material.RMaterial;
import com.blockreality.api.physics.pfsf.NativePFSFBridge;
import com.blockreality.api.physics.pfsf.PFSFEngine;
import net.minecraft.core.BlockPos;

import java.nio.FloatBuffer;
import java.util.function.Function;

/**
 * v0.4 M2e — FUSION_MASK augmentation binder.
 *
 * <p>Marks voxels whose material is the RC fusion node with a
 * conductivity boost multiplier (matches the {@code compBoost} coefficient
 * baked into {@link com.blockreality.api.material.DefaultMaterial#RC_NODE}).
 * Unfused voxels write {@code 1.0f} so {@code OP_AUG_COND_MUL} is a
 * no-op.
 *
 * <p>Uses the engine's material lookup hook instead of reaching into
 * {@link net.minecraft.server.level.ServerLevel} so the binder stays
 * thread-safe on the PFSF worker.
 */
public final class FusionAugBinder extends AbstractAugBinder {

    /** Conductivity multiplier applied at RC fusion nodes. Matches
     *  {@code rcCompBoost} in {@code BRConfig} (default 1.1). Kept local
     *  here so the binder doesn't force a BRConfig import in API clients
     *  that pre-compute DBBs. */
    private static final float RC_FUSION_BOOST = 1.1f;

    public FusionAugBinder() {
        super(NativePFSFBridge.AugKind.FUSION_MASK, Float.BYTES);
    }

    @Override
    protected boolean isActive() {
        return PFSFEngine.getMaterialLookup() != null;
    }

    @Override
    protected boolean fill(FloatBuffer out, BlockPos origin, int Lx, int Ly, int Lz) {
        Function<BlockPos, RMaterial> lookup = PFSFEngine.getMaterialLookup();
        if (lookup == null) return false;

        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        boolean any = false;
        for (int z = 0; z < Lz; ++z) {
            for (int y = 0; y < Ly; ++y) {
                int rowBase = Lx * (y + Ly * z);
                for (int x = 0; x < Lx; ++x) {
                    probe.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    RMaterial mat = lookup.apply(probe);
                    if (mat == null) continue;
                    /* RC_NODE is identified by its registry name rather
                     * than an enum reference — custom materials can also
                     * opt in by naming themselves "rc_node" in their
                     * registry entry. */
                    if ("rc_node".equals(mat.getMaterialId())) {
                        out.put(rowBase + x, RC_FUSION_BOOST);
                        any = true;
                    }
                }
            }
        }
        return any;
    }
}
