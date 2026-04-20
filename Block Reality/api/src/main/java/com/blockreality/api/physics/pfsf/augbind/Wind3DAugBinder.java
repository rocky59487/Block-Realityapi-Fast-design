package com.blockreality.api.physics.pfsf.augbind;

import com.blockreality.api.physics.pfsf.NativePFSFBridge;
import com.blockreality.api.physics.pfsf.PFSFEngine;
import com.blockreality.api.spi.IWindManager;
import com.blockreality.api.spi.ModuleRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.nio.FloatBuffer;

/**
 * v0.4 M2e — WIND_FIELD_3D augmentation binder.
 *
 * <p>Publishes a 3-component wind vector {@code (vx, vy, vz)} per voxel
 * in m/s. The dispatcher's {@code OP_AUG_WIND_3D_BIAS} opcode consumes
 * this DBB to bias the directional conductivity:
 * {@code cond[d][i] *= 1 ± k·dot(dir[d], wind[i])}.
 *
 * <p>Sources:
 * <ol>
 *   <li>The engine's global {@code currentWindVec} is required — it
 *       supplies the uniform direction. If it is {@code null} or of
 *       zero magnitude the binder stays inactive (we will not fabricate
 *       a direction).</li>
 *   <li>If {@link IWindManager} is also registered, its
 *       {@code getWindSpeedAt} supplies per-voxel wind speed (multiplied
 *       by the global direction) — good enough for localised wind
 *       tunnels.</li>
 *   <li>Without a wind manager, the global vector is applied uniformly
 *       to every voxel.</li>
 * </ol>
 */
public final class Wind3DAugBinder extends AbstractAugBinder {

    /** 3 floats × 4 bytes per voxel. */
    private static final int STRIDE = 3 * Float.BYTES;

    public Wind3DAugBinder() {
        super(NativePFSFBridge.AugKind.WIND_FIELD_3D, STRIDE);
    }

    @Override
    protected boolean isActive() {
        // A wind manager alone is not enough — we need a direction vector
        // to publish (IWindManager exposes speed only). Without a global
        // direction from the engine we must stay inactive; otherwise we'd
        // inject a fabricated +X bias into every voxel of every tick.
        return PFSFEngine.getCurrentWindVec() != null;
    }

    @Override
    protected boolean fill(FloatBuffer out, BlockPos origin, int Lx, int Ly, int Lz) {
        Vec3 globalWind = PFSFEngine.getCurrentWindVec();
        if (globalWind == null) return false;

        IWindManager wind = ModuleRegistry.getWindManager();

        // PR#187 capy-ai R30: globalWind is non-null here (guarded above).
        // If its magnitude is effectively zero there is no meaningful
        // direction either — skip publishing rather than invent one.
        double dx = globalWind.x, dy = globalWind.y, dz = globalWind.z;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len <= 1e-9) return false;
        dx /= len; dy /= len; dz /= len;
        float baseSpeed = (float) len;

        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        boolean any = false;
        for (int z = 0; z < Lz; ++z) {
            for (int y = 0; y < Ly; ++y) {
                int voxelBase = Lx * (y + Ly * z);
                for (int x = 0; x < Lx; ++x) {
                    probe.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    float speed;
                    if (wind != null) {
                        speed = wind.getWindSpeedAt(probe);
                    } else {
                        speed = baseSpeed;
                    }
                    if (speed == 0.0f) continue;

                    int idx = (voxelBase + x) * 3;
                    out.put(idx,     (float) (speed * dx));
                    out.put(idx + 1, (float) (speed * dy));
                    out.put(idx + 2, (float) (speed * dz));
                    any = true;
                }
            }
        }
        return any;
    }
}
